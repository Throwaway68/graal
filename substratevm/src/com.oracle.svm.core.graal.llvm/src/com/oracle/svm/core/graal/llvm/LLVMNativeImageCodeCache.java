/*
 * Copyright (c) 2018, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.graal.llvm;

import static com.oracle.svm.core.graal.llvm.LLVMToolchainUtils.llvmAddTextSectionSymbols;
import static com.oracle.svm.core.graal.llvm.LLVMToolchainUtils.llvmCleanupRISCVAttributes;
import static com.oracle.svm.core.graal.llvm.LLVMToolchainUtils.llvmCleanupStackMaps;
import static com.oracle.svm.core.graal.llvm.LLVMToolchainUtils.llvmCompile;
import static com.oracle.svm.core.graal.llvm.LLVMToolchainUtils.llvmLink;
import static com.oracle.svm.core.graal.llvm.LLVMToolchainUtils.llvmOptimize;
import static com.oracle.svm.core.graal.llvm.LLVMToolchainUtils.nativeLink;
import static com.oracle.svm.hosted.image.NativeImage.RWDATA_CGLOBALS_PARTITION_OFFSET;
import static com.oracle.svm.shared.util.VMError.shouldNotReachHereUnexpectedInput;

import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.graalvm.collections.Pair;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.WordBase;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.util.Timer.StopTimer;
import com.oracle.graal.pointsto.util.TimerCollection;
import com.oracle.objectfile.LayoutDecisionMap;
import com.oracle.objectfile.ObjectFile;
import com.oracle.objectfile.ObjectFile.Element;
import com.oracle.objectfile.SectionName;
import com.oracle.svm.core.Isolates;
import com.oracle.svm.core.graal.code.CGlobalDataDirectReference;
import com.oracle.svm.core.graal.code.CGlobalDataInfo;
import com.oracle.svm.core.graal.llvm.LLVMToolchainUtils.BatchExecutor;
import com.oracle.svm.core.graal.llvm.objectfile.LLVMObjectFile;
import com.oracle.svm.core.graal.llvm.runtime.LLVMExceptionUnwind;
import com.oracle.svm.core.graal.llvm.util.LLVMObjectFileReader;
import com.oracle.svm.core.graal.llvm.util.LLVMObjectFileReader.LLVMCodeSection;
import com.oracle.svm.core.graal.llvm.util.LLVMObjectFileReader.LLVMTextSectionInfo;
import com.oracle.svm.core.graal.llvm.util.LLVMOptions;
import com.oracle.svm.core.graal.llvm.util.LLVMStackMapInfo;
import com.oracle.svm.core.heap.SubstrateReferenceMap;
import com.oracle.svm.guest.staging.c.CGlobalDataImpl;
import com.oracle.svm.guest.staging.core.jdk.UninterruptibleUtils.AtomicInteger;
import com.oracle.svm.hosted.NativeImageOptions;
import com.oracle.svm.hosted.image.NativeImage;
import com.oracle.svm.hosted.image.NativeImage.NativeTextSectionImpl;
import com.oracle.svm.hosted.image.NativeImageCodeCache;
import com.oracle.svm.hosted.image.NativeImageHeap;
import com.oracle.svm.hosted.image.RelocatableBuffer;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.shared.util.VMError;

import jdk.graal.compiler.code.CompilationResult;
import jdk.graal.compiler.core.common.NumUtil;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.debug.Indent;
import jdk.vm.ci.code.site.Call;
import jdk.vm.ci.code.site.DataPatch;
import jdk.vm.ci.code.site.DataSectionReference;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaType;

@Platforms(Platform.HOSTED_ONLY.class)
public class LLVMNativeImageCodeCache extends NativeImageCodeCache {
    private HostedMethod[] methodIndex;
    private final Path basePath;
    private int batchSize;
    private final LLVMObjectFileReader objectFileReader;
    private final List<ObjectFile.Symbol> globalSymbols = new ArrayList<>();
    private final StackMapDumper stackMapDumper;

    LLVMNativeImageCodeCache(Map<HostedMethod, CompilationResult> compilations, NativeImageHeap imageHeap, Platform targetPlatform, Path tempDir) {
        super(compilations, imageHeap, targetPlatform);

        try {
            basePath = tempDir.resolve("llvm");
            Files.createDirectory(basePath);
        } catch (IOException e) {
            throw new GraalError(e);
        }

        this.stackMapDumper = getStackMapDumper(LLVMOptions.DumpLLVMStackMap.hasBeenSet());
        this.objectFileReader = new LLVMObjectFileReader(stackMapDumper);
    }

    @Override
    public int getCodeCacheSize() {
        return getCodeAreaSize();
    }

    @Override
    public boolean definesTextSectionBoundarySymbols() {
        return false;
    }

    @Override
    public int codeSizeFor(HostedMethod method) {
        return compilationResultFor(method).getTargetCodeSize();
    }

    @Override
    @SuppressWarnings({"unused", "try"})
    public void layoutMethods(DebugContext debug, BigBang bb) {
        try (Indent indent = debug.logAndIndent("layout methods")) {
            BatchExecutor executor = new BatchExecutor(debug, bb);
            try (StopTimer t = TimerCollection.createTimerAndStart("(bitcode)")) {
                writeBitcode(executor);
            }
            int numBatches;
            try (StopTimer t = TimerCollection.createTimerAndStart("(prelink)")) {
                numBatches = createBitcodeBatches(executor, debug);
            }
            try (StopTimer t = TimerCollection.createTimerAndStart("(llvm)")) {
                compileBitcodeBatches(executor, debug, numBatches);
            }
            try (StopTimer t = TimerCollection.createTimerAndStart("(postlink)")) {
                linkCompiledBatches(debug, executor, numBatches);
            }
        }
    }

    private void writeBitcode(BatchExecutor executor) {
        methodIndex = new HostedMethod[getOrderedCompilations().size()];
        AtomicInteger num = new AtomicInteger(-1);
        executor.forEach(getOrderedCompilations(), pair -> _ -> {
            int id = num.incrementAndGet();
            methodIndex[id] = pair.getLeft();

            try (FileOutputStream fos = new FileOutputStream(getBitcodePath(id).toString())) {
                fos.write(pair.getRight().getTargetCode());
            } catch (IOException e) {
                throw new GraalError(e);
            }
        });
    }

    private int createBitcodeBatches(BatchExecutor executor, DebugContext debug) {
        batchSize = LLVMOptions.LLVMMaxFunctionsPerBatch.getValue();
        int numThreads = NativeImageOptions.getActualNumberOfThreads();
        int idealSize = NumUtil.divideAndRoundUp(methodIndex.length, numThreads);
        if (idealSize < batchSize) {
            batchSize = idealSize;
        }

        if (LLVMWindowsSupport.isWindows()) {
            batchSize = 0; /* one object: there is no relocatable link on PE/COFF */
        }

        if (batchSize == 0) {
            batchSize = methodIndex.length;
        }
        int numBatches = NumUtil.divideAndRoundUp(methodIndex.length, batchSize);
        if (batchSize > 1) {
            /* Avoid empty batches with small batch sizes */
            numBatches -= (numBatches * batchSize - methodIndex.length) / batchSize;

            executor.forEach(numBatches, batchId -> _ -> {
                List<String> batchInputs = IntStream.range(getBatchStart(batchId), getBatchEnd(batchId)).mapToObj(this::getBitcodeFilename)
                                .collect(Collectors.toList());
                if (LLVMWindowsSupport.isWindows() && batchId == 0) {
                    batchInputs.add(writeSupportModule());
                }
                llvmLink(debug, getBatchBitcodeFilename(batchId), batchInputs, basePath, this::getFunctionName);
            });
        }

        return numBatches;
    }

    /**
     * Writes the bitcode of the Windows support module, which defines the code section boundary
     * symbols and the SEH personality glue, and returns its file name so that it can be linked into
     * the single batch.
     */
    private String writeSupportModule() {
        String name = "win-support.bc";
        HostedMethod personalityStub = (HostedMethod) LLVMExceptionUnwind.getPersonalityStub(getImageHeap().hMetaAccess);
        verifyPersonalityStub(personalityStub);
        byte[] bitcode = LLVMWindowsSupport.buildSupportModule(NativeImage.getTextSectionStartSymbol(), NativeImage.getTextSectionEndSymbol(), personalityStub.getUniqueShortName());
        try (FileOutputStream fos = new FileOutputStream(basePath.resolve(name).toString())) {
            fos.write(bitcode);
        } catch (IOException e) {
            throw new GraalError(e);
        }
        return name;
    }

    /**
     * Compiles a Mach-O marker object defining {@code symbol} at the current end of
     * {@code __TEXT,__text} and returns its file name, to be linked in front of or behind the
     * batches.
     */
    private String writeMarkerObject(DebugContext debug, String name, String symbol) {
        String bitcode = name + ".bc";
        String object = name + ".o";
        try (FileOutputStream fos = new FileOutputStream(basePath.resolve(bitcode).toString())) {
            fos.write(LLVMDarwinSupport.buildMarkerModule(name, symbol));
        } catch (IOException e) {
            throw new GraalError(e);
        }
        /* Not getFunctionName: that one reads a batch id out of the file name. */
        llvmCompile(debug, object, bitcode, basePath, fileName -> fileName);
        return object;
    }

    /**
     * The marker objects only bracket the Java code if the relocatable link concatenates the
     * {@code __TEXT,__text} of its inputs in input order. It does, but nothing in the object format
     * says it has to, and getting it wrong would shift every method offset in the image rather than
     * fail the build, so the linked object is checked.
     */
    private static void verifyMarkers(LLVMTextSectionInfo textSectionInfo) {
        String start = LLVMDarwinSupport.machOSymbol(NativeImage.getTextSectionStartSymbol());
        String end = LLVMDarwinSupport.machOSymbol(NativeImage.getTextSectionEndSymbol());
        Integer startOffset = textSectionInfo.getSymbolOffset(start);
        Integer endOffset = textSectionInfo.getSymbolOffset(end);
        VMError.guarantee(startOffset != null && startOffset == 0, "%s is at %s of the code section, not at its start", start, startOffset);
        VMError.guarantee(endOffset != null && endOffset == textSectionInfo.getCodeSize(), "%s is at %s of the %s byte code section, not at its end", end, endOffset,
                        textSectionInfo.getCodeSize());
    }

    /**
     * The support module declares the personality stub by hand, as
     * {@code i32 (i32, i32, i64, i64, i64)} with the Graal calling convention, because it is built
     * without an {@code LLVMGenerator}. A change to the signature of
     * {@code LLVMExceptionUnwind.personality} must therefore fail the build rather than silently
     * produce a call with the wrong ABI.
     */
    private void verifyPersonalityStub(HostedMethod stub) {
        ResolvedJavaType wordBase = getImageHeap().hMetaAccess.lookupJavaType(WordBase.class);
        JavaType[] parameters = stub.getSignature().toParameterTypes(stub.hasReceiver() ? stub.getDeclaringClass() : null);
        /* Entry point: libunwind reaches it from C, and only an entry point may be entered that way. */
        boolean matches = stub.isEntryPoint() && parameters.length == 5 && stub.getSignature().getReturnType().getJavaKind() == JavaKind.Int;
        for (int i = 0; matches && i < parameters.length; i++) {
            ResolvedJavaType parameter = parameters[i].resolve(null);
            matches = (i < 2) ? parameter.getJavaKind() == JavaKind.Int : wordBase.isAssignableFrom(parameter);
        }
        VMError.guarantee(matches, "The Windows support module declares %s as i32 (i32, i32, i64, i64, i64), which does not match its signature %s", stub, stub.getSignature());
    }

    private void compileBitcodeBatches(BatchExecutor executor, DebugContext debug, int numBatches) {
        stackMapDumper.startDumpingFunctions();

        executor.forEach(numBatches, batchId -> _ -> {
            llvmOptimize(debug, getBatchOptimizedFilename(batchId), getBatchBitcodeFilename(batchId), basePath, this::getFunctionName);
            llvmCompile(debug, getBatchCompiledFilename(batchId), getBatchOptimizedFilename(batchId), basePath, this::getFunctionName);

            LLVMStackMapInfo stackMap = objectFileReader.parseStackMap(getBatchCompiledPath(batchId));
            /* On Windows a statepoint record can name the padding byte behind its call. */
            LLVMCodeSection codeSection = LLVMWindowsSupport.isWindows() ? objectFileReader.parseCodeSection(getBatchCompiledPath(batchId)) : null;
            IntStream.range(getBatchStart(batchId), getBatchEnd(batchId)).forEach(id -> objectFileReader.readStackMap(stackMap, codeSection, compilationResultFor(methodIndex[id]), methodIndex[id], id));
        });
    }

    private void linkCompiledBatches(DebugContext debug, BatchExecutor executor, int numBatches) {
        if (LLVMWindowsSupport.isWindows()) {
            /* PE/COFF has no relocatable link: the single batch object is the linked object. */
            VMError.guarantee(numBatches == 1, "Windows uses a single LLVM batch");
            try {
                Files.copy(getBatchCompiledPath(0), getLinkedPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new GraalError(e);
            }
        } else {
            List<String> inputs = new ArrayList<>();
            /*
             * On Mach-O the code section boundary symbols cannot be added to the linked object
             * afterwards (llvm-objcopy --add-symbol is ELF only), so they come from two marker
             * objects that bracket the batches in the input order of the relocatable link.
             */
            if (LLVMDarwinSupport.isDarwin()) {
                inputs.add(writeMarkerObject(debug, "svm-code-section-start", NativeImage.getTextSectionStartSymbol()));
            }
            IntStream.range(0, numBatches).mapToObj(this::getBatchCompiledFilename).forEach(inputs::add);
            if (LLVMDarwinSupport.isDarwin()) {
                inputs.add(writeMarkerObject(debug, "svm-code-section-end", NativeImage.getTextSectionEndSymbol()));
            }
            nativeLink(debug, getLinkedFilename(), inputs, basePath, this::getFunctionName);
        }

        LLVMTextSectionInfo textSectionInfo = objectFileReader.parseCode(getLinkedPath());
        if (LLVMDarwinSupport.isDarwin()) {
            verifyMarkers(textSectionInfo);
        }

        List<Pair<HostedMethod, CompilationResult>> orderedCompilations = getOrderedCompilations();
        executor.forEach(orderedCompilations, pair -> _ -> {
            HostedMethod method = pair.getLeft();
            method.setCodeAddressOffset(textSectionInfo.getOffset(method.getUniqueShortName()));
        });
        orderedCompilations.sort(Comparator.comparingInt(o -> o.getLeft().getCodeAddressOffset()));

        executor.forEach(orderedCompilations.size(), i -> _ -> {
            var pair = orderedCompilations.get(i);
            HostedMethod method = pair.getLeft();
            CompilationResult compilation = pair.getRight();
            int offset = method.getCodeAddressOffset();
            HostedMethod nextMethod = i + 1 == orderedCompilations.size() ? null : orderedCompilations.get(i + 1).getLeft();
            int nextFunctionStartOffset = nextMethod == null ? NumUtil.safeToInt(textSectionInfo.getCodeSize()) : nextMethod.getCodeAddressOffset();
            VMError.guarantee(offset < nextFunctionStartOffset, "Methods %s and %s have the same offset: %d", method, nextMethod == null ? "end of section" : nextMethod, offset);
            int functionSize = nextFunctionStartOffset - offset;

            compilation.setTargetCode(null, functionSize);
        });

        stackMapDumper.dumpOffsets(textSectionInfo);
        stackMapDumper.close();

        llvmCleanupStackMaps(debug, getLinkedFilename(), basePath);
        long codeAreaSize = textSectionInfo.getCodeSize();
        assert codeAreaSize <= Integer.MAX_VALUE;
        llvmCleanupRISCVAttributes(debug, getLinkedFilename(), basePath);
        if (!LLVMWindowsSupport.isWindows() && !LLVMDarwinSupport.isDarwin()) {
            /* Elsewhere the symbols come from marker sections (Windows) or marker objects (Darwin). */
            llvmAddTextSectionSymbols(debug, getLinkedFilename(), NativeImage.getTextSectionStartSymbol(), NativeImage.getTextSectionEndSymbol(), codeAreaSize, basePath);
        }
        setCodeAreaSize((int) textSectionInfo.getCodeSize());
    }

    private Path getBitcodePath(int id) {
        return basePath.resolve(getBitcodeFilename(id));
    }

    private String getBitcodeFilename(int id) {
        return "f" + id + ".bc";
    }

    private String getBatchBitcodeFilename(int id) {
        return ((batchSize == 1) ? "f" : "b") + id + ".bc";
    }

    private String getBatchOptimizedFilename(int id) {
        return ((batchSize == 1) ? "f" : "b") + id + "o.bc";
    }

    private Path getBatchCompiledPath(int id) {
        return basePath.resolve(getBatchCompiledFilename(id));
    }

    private String getBatchCompiledFilename(int id) {
        return ((batchSize == 1) ? "f" : "b") + id + ".o";
    }

    private Path getLinkedPath() {
        return basePath.resolve(getLinkedFilename());
    }

    private static String getLinkedFilename() {
        /* cl.exe only passes files it recognizes as objects on to the linker. */
        return LLVMWindowsSupport.isWindows() ? "llvm.obj" : "llvm.o";
    }

    private int getBatchStart(int id) {
        return id * batchSize;
    }

    private int getBatchEnd(int id) {
        return Math.min((id + 1) * batchSize, methodIndex.length);
    }

    private String getFunctionName(String fileName) {
        String function;
        if (fileName.equals(getLinkedFilename())) {
            function = "the final object file";
        } else {
            char type = fileName.charAt(0);
            String idString = fileName.substring(1, fileName.indexOf('.'));
            if (idString.charAt(idString.length() - 1) == 'o') {
                idString = idString.substring(0, idString.length() - 1);
            }
            int id = Integer.parseInt(idString);

            switch (type) {
                case 'f':
                    function = methodIndex[id].getQualifiedName();
                    break;
                case 'b':
                    function = "batch " + id + " (f" + getBatchStart(id) + "-f" + getBatchEnd(id) + "). Use -H:LLVMMaxFunctionsPerBatch=1 to compile each method individually.";
                    break;
                default:
                    throw shouldNotReachHereUnexpectedInput(type);
            }
        }
        return function + " (" + basePath.resolve(fileName) + ")";
    }

    @Override
    public void patchMethods(DebugContext debug, RelocatableBuffer relocs, ObjectFile objectFile) {
        Element rodataSection = objectFile.elementForName(SectionName.RODATA.getFormatDependentName(objectFile.getFormat()));
        Element dataSection = objectFile.elementForName(SectionName.DATA.getFormatDependentName(objectFile.getFormat()));
        /*
         * The symbols defined below exist so that the separate code object can reference the
         * image's data by name; they need external linkage, and nothing more. On PE/COFF
         * `exported` additionally writes a `/EXPORT:<name>` directive into the object's .drectve
         * section, i.e. it puts the symbol into the executable's export table, and link.exe
         * refuses more than 65535 of those with "LNK1189: library limit of 65535 objects
         * exceeded". A hello world stays under that limit, the gate's javac-image does not: it
         * defines 79,723 of these symbols. ELF and Mach-O keep the value upstream passes, where
         * `exported` only decides dynamic symbol table membership.
         */
        boolean exported = objectFile.getFormat() != ObjectFile.Format.PECOFF;
        for (Pair<HostedMethod, CompilationResult> pair : getOrderedCompilations()) {
            CompilationResult result = pair.getRight();
            for (DataPatch dataPatch : result.getDataPatches()) {
                if (dataPatch.reference instanceof CGlobalDataDirectReference ref) {
                    CGlobalDataInfo info = ref.getDataInfo();
                    CGlobalDataImpl<?> data = info.getData();
                    if (info.isSymbolReference() && !isImageHeapSymbol(data.symbolName) && objectFile.getOrCreateSymbolTable().getSymbol(data.symbolName) == null) {
                        objectFile.createUndefinedSymbol(data.symbolName, true);
                    }

                    String symbolName = (String) dataPatch.note;
                    if (data.symbolName == null && objectFile.getOrCreateSymbolTable().getSymbol(symbolName) == null) {
                        objectFile.createDefinedSymbol(symbolName, dataSection, info.getOffset() + RWDATA_CGLOBALS_PARTITION_OFFSET, 0, false, true, exported);
                    }
                } else if (dataPatch.reference instanceof DataSectionReference) {
                    DataSectionReference reference = (DataSectionReference) dataPatch.reference;

                    int offset = reference.getOffset();

                    String symbolName = (String) dataPatch.note;
                    if (objectFile.getOrCreateSymbolTable().getSymbol(symbolName) == null) {
                        objectFile.createDefinedSymbol(symbolName, rodataSection, offset, 0, false, true, exported);
                    }
                }
            }
        }
    }

    private static boolean isImageHeapSymbol(String symbolName) {
        return symbolName.equals(Isolates.IMAGE_HEAP_BEGIN_SYMBOL_NAME) ||
                        symbolName.equals(Isolates.IMAGE_HEAP_END_SYMBOL_NAME) ||
                        symbolName.equals(Isolates.IMAGE_HEAP_RELOCATABLE_BEGIN_SYMBOL_NAME) ||
                        symbolName.equals(Isolates.IMAGE_HEAP_RELOCATABLE_END_SYMBOL_NAME) ||
                        symbolName.equals(Isolates.IMAGE_HEAP_A_RELOCATABLE_POINTER_SYMBOL_NAME) ||
                        symbolName.equals(Isolates.IMAGE_HEAP_WRITABLE_BEGIN_SYMBOL_NAME) ||
                        symbolName.equals(Isolates.IMAGE_HEAP_WRITABLE_END_SYMBOL_NAME) ||
                        symbolName.equals(Isolates.IMAGE_HEAP_WRITABLE_PATCHED_BEGIN_SYMBOL_NAME) ||
                        symbolName.equals(Isolates.IMAGE_HEAP_WRITABLE_PATCHED_END_SYMBOL_NAME);
    }

    @Override
    public NativeTextSectionImpl getTextSectionImpl(RelocatableBuffer buffer, ObjectFile objectFile, NativeImageCodeCache codeCache) {
        return new NativeTextSectionImpl(buffer, objectFile, codeCache) {
            @Override
            protected void defineMethodSymbol(String name, boolean global, boolean exported, Element section, HostedMethod method, CompilationResult result) {
                /*
                 * The code lives in the linked LLVM object, so this object can only declare the
                 * symbol - but `exported` must still be passed on. On PE/COFF an export is a
                 * `/EXPORT:` directive, and without it a shared library exports none of its entry
                 * points: `helloworld --shared` built one whose `run_main` ctypes could not find
                 * (run 35357858740). Elsewhere the argument is ignored.
                 */
                ObjectFile.Symbol symbol = objectFile.createUndefinedSymbol(name, true, exported);
                if (global) {
                    globalSymbols.add(symbol);
                }
            }

            /*
             * The real text content is linked from llvm.o. Keep this section present while building
             * the image object, but do not emit a second zero-filled text section into that object.
             */
            @Override
            public int getOrDecideSize(Map<Element, LayoutDecisionMap> alreadyDecided, int sizeHint) {
                return 0;
            }

            @Override
            public byte[] getOrDecideContent(Map<Element, LayoutDecisionMap> alreadyDecided, byte[] contentHint) {
                return new byte[0];
            }

            @Override
            public int getMemSize(Map<Element, LayoutDecisionMap> alreadyDecided) {
                return 0;
            }
        };
    }

    @Override
    public void writeCode(RelocatableBuffer buffer) {
        /* Do nothing, code is written at link stage */
    }

    @Override
    public Path[] getCCInputFiles(Path tempDirectory, String imageName) {
        Path[] allInputFiles;
        if (LLVMOptions.UseLLVMDataSection.getValue()) {
            allInputFiles = new Path[2];
            allInputFiles[0] = basePath.resolve(LLVMObjectFile.getLinkedFilename());
        } else {
            Path[] nativeImageFiles = super.getCCInputFiles(tempDirectory, imageName);
            allInputFiles = Arrays.copyOf(nativeImageFiles, nativeImageFiles.length + 1);
        }
        Path bitcodeFileName = getLinkedPath();
        allInputFiles[allInputFiles.length - 1] = bitcodeFileName;
        return allInputFiles;
    }

    @Override
    public List<ObjectFile.Symbol> getSymbols(ObjectFile objectFile) {
        return globalSymbols;
    }

    private StackMapDumper getStackMapDumper(boolean enable) {
        if (enable) {
            return new EnabledStackMapDumper();
        } else {
            return new DisabledStackMapDumper();
        }
    }

    public interface StackMapDumper {
        void dumpOffsets(LLVMTextSectionInfo textSectionInfo);

        void startDumpingFunctions();

        void startDumpingFunction(String methodSymbolName, int id, int totalFrameSize);

        void dumpCallSite(Call call, int actualPcOffset, SubstrateReferenceMap referenceMap);

        void endDumpingFunction();

        void close();
    }

    private final class EnabledStackMapDumper implements StackMapDumper {
        private final FileWriter stackMapDump;

        {
            try {
                stackMapDump = new FileWriter(LLVMOptions.DumpLLVMStackMap.getValue());
            } catch (IOException e) {
                throw new GraalError(e);
            }
        }

        private final ThreadLocal<StringBuilder> functionDump = new ThreadLocal<>();

        @Override
        public void dumpOffsets(LLVMTextSectionInfo textSectionInfo) {
            dump("\nOffsets\n=======\n");
            List<Pair<HostedMethod, CompilationResult>> orderedCompilations = getOrderedCompilations();
            for (int i = 0; i < orderedCompilations.size(); ++i) {
                var pair = orderedCompilations.get(i);
                int startOffset = pair.getLeft().getCodeAddressOffset();
                CompilationResult compilationResult = pair.getRight();
                assert i == 0 || checkPreviousFunctionEnd(orderedCompilations.get(i - 1), startOffset) : orderedCompilations.get(i - 1).getRight().getName();

                String methodName = textSectionInfo.getSymbol(startOffset);
                dump("[" + startOffset + "] " + methodName + " (" + compilationResult.getTargetCodeSize() + ")\n");
            }
        }

        private static boolean checkPreviousFunctionEnd(Pair<HostedMethod, CompilationResult> previousPair, int startOffset) {
            return previousPair.getLeft().getCodeAddressOffset() + previousPair.getRight().getTargetCodeSize() == startOffset;
        }

        @Override
        public void startDumpingFunctions() {
            dump("Patchpoints\n===========\n");
        }

        @Override
        public void startDumpingFunction(String methodSymbolName, int id, int totalFrameSize) {
            StringBuilder builder = new StringBuilder();
            builder.append(methodSymbolName);
            builder.append(" -> f");
            builder.append(id);
            builder.append(" (");
            builder.append(totalFrameSize);
            builder.append(")\n");
            functionDump.set(builder);
        }

        @Override
        public void dumpCallSite(Call call, int actualPcOffset, SubstrateReferenceMap referenceMap) {
            StringBuilder builder = functionDump.get();
            builder.append("  [");
            builder.append(actualPcOffset);
            builder.append("] -> ");
            builder.append(call.target != null ? ((HostedMethod) call.target).format("%H.%n") : "???");
            builder.append(" (");
            builder.append(call.pcOffset);
            builder.append(") ");
            referenceMap.dump(builder);
            builder.append("\n");
        }

        @Override
        public void endDumpingFunction() {
            dump(functionDump.get().toString());
        }

        @Override
        public void close() {
            try {
                stackMapDump.close();
            } catch (IOException e) {
                throw new GraalError(e);
            }
        }

        private void dump(String str) {
            try {
                stackMapDump.write(str);
            } catch (IOException e) {
                throw new GraalError(e);
            }
        }
    }

    private static final class DisabledStackMapDumper implements StackMapDumper {
        @Override
        public void dumpOffsets(LLVMTextSectionInfo textSectionInfo) {
        }

        @Override
        public void startDumpingFunctions() {
        }

        @Override
        public void startDumpingFunction(String methodSymbolName, int id, int totalFrameSize) {
        }

        @Override
        public void dumpCallSite(Call call, int actualPcOffset, SubstrateReferenceMap referenceMap) {
        }

        @Override
        public void endDumpingFunction() {
        }

        @Override
        public void close() {
        }
    }
}
