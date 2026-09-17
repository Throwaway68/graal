/*
 * Copyright (c) 2026, 2026, Oracle and/or its affiliates. All rights reserved.
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

import java.nio.file.Path;

import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;
import org.graalvm.nativeimage.Platform;

import com.oracle.svm.core.graal.llvm.util.LLVMIRBuilder;
import com.oracle.svm.core.graal.llvm.util.LLVMIRBuilder.LLVMCallingConvention;
import com.oracle.svm.core.graal.llvm.util.LLVMIRBuilder.LinkageType;
import com.oracle.svm.core.graal.llvm.util.LLVMTargetSpecific;
import com.oracle.svm.hosted.image.LLVMToolchain;

/**
 * Windows (PE/COFF) specifics of the LLVM backend. There is no partial link on Windows, so all Java
 * code is compiled into one object whose functions live in the grouped section {@code .text$svm1}.
 * COFF linkers sort {@code .text$*} contributions by suffix, so two empty marker sections define the
 * code-section start and end symbols that the image object declares as undefined (see
 * NativeImage.getTextSectionStartSymbol/EndSymbol).
 * <p>
 * The same support module also carries the exception-handling glue. Windows dispatches exceptions
 * through SEH: the {@code .xdata} record of every function names a <em>language handler</em>, which
 * the OS calls with the Win64 C convention. The Java personality
 * ({@code LLVMExceptionUnwind.personality}) is an Itanium personality routine instead, so
 * {@link #SEH_PERSONALITY} is the language handler that every Java function names, and it forwards
 * to libunwind's {@code _GCC_specific_handler}, which translates between the two protocols.
 */
public final class LLVMWindowsSupport {
    public static final String START_SECTION = ".text$svm0";
    public static final String CODE_SECTION = ".text$svm1";
    public static final String END_SECTION = ".text$svm2";
    /**
     * Section of the SEH glue below. It sorts after {@link #END_SECTION}, so the glue lands right
     * behind the Java code but outside {@code [__svm_code_section, __svm_text_end)} - it is never a
     * Java frame, nothing ever looks its address up in the code info, and keeping it out of
     * {@link #CODE_SECTION} means it can never shift a method offset there (LLVMObjectFileReader
     * takes the first section whose name starts with {@code .text$svm1}, and method offsets are
     * relative to the start of that section).
     */
    public static final String SEH_SECTION = ".text$svm3";

    /** SEH language handler named by the {@code .xdata} record of every Java function. */
    public static final String SEH_PERSONALITY = "__svm_seh_personality";
    /** C-ABI thunk in front of the Java personality, see {@link #addPersonalityShim}. */
    private static final String SEH_PERSONALITY_ROUTINE = "__svm_seh_personality_routine";
    /** libunwind's bridge from an SEH language handler to an Itanium personality routine. */
    private static final String GCC_SPECIFIC_HANDLER = "_GCC_specific_handler";
    /** Target triple libunwind is built for; it is the one that predefines {@code __SEH__}. */
    private static final String LIBUNWIND_TARGET = "x86_64-w64-windows-gnu";

    private LLVMWindowsSupport() {
    }

    public static boolean isWindows() {
        return Platform.includedIn(Platform.WINDOWS.class);
    }

    /**
     * Type of an SEH language handler:
     * {@code EXCEPTION_DISPOSITION handler(EXCEPTION_RECORD *, void *frame, CONTEXT *, DISPATCHER_CONTEXT *)}.
     */
    public static LLVMTypeRef sehPersonalityType(LLVMIRBuilder builder) {
        LLVMTypeRef pointer = builder.rawPointerType();
        return builder.functionType(builder.intType(), pointer, pointer, pointer, pointer);
    }

    /**
     * libunwind in SEH mode, shipped in the Windows LLVM toolchain bundle next to the tools. The
     * same archive is installed as {@code unwind.lib} as well; both names are the same file.
     */
    public static Path libunwindArchive() {
        return LLVMToolchain.getLLVMBinDir().getParent().resolve(Path.of("lib", LIBUNWIND_TARGET, "libunwind.a"));
    }

    /** Bitcode of a module holding the two marker sections and the SEH personality glue. */
    public static byte[] buildSupportModule(String startSymbol, String endSymbol, String personalityStubName) {
        try (LLVMIRBuilder builder = new LLVMIRBuilder("svm-windows-support")) {
            builder.setTarget(LLVMTargetSpecific.get().getTargetTriple());
            builder.setModuleInlineAsm(markerAsm(startSymbol, endSymbol));
            addPersonalityShim(builder, personalityStubName);
            assert builder.verifyBitcode();
            return builder.getBitcode();
        }
    }

    private static String markerAsm(String startSymbol, String endSymbol) {
        return String.join("\n",
                        ".section " + START_SECTION + ",\"xr\"",
                        ".p2align 4",
                        ".globl " + startSymbol,
                        startSymbol + ":",
                        ".section " + END_SECTION + ",\"xr\"",
                        ".p2align 4",
                        ".globl " + endSymbol,
                        endSymbol + ":",
                        "");
    }

    /**
     * Emits the two functions that connect Windows exception dispatching to the Java personality:
     *
     * <pre>
     * EXCEPTION_DISPOSITION __svm_seh_personality(rec, frame, context, dispatcher) {
     *     return _GCC_specific_handler(rec, frame, context, dispatcher, __svm_seh_personality_routine);
     * }
     * int __svm_seh_personality_routine(version, action, thread, exception, unwindContext) {
     *     return &lt;Java personality stub&gt;(version, action, thread, exception, unwindContext);
     * }
     * </pre>
     *
     * The thunk exists because of the calling convention. Compiled Java methods, the personality
     * stub included, use the Graal calling convention, which on Win64 passes the arguments in the
     * same registers as the C one but reserves no 32-byte home space; libunwind calls the
     * personality routine with the C convention, which does. The two therefore disagree about where
     * the fifth argument lives, and only about that. The thunk is a C function, so it reads the
     * arguments where libunwind puts them, and re-issues the call with the Graal convention.
     */
    private static void addPersonalityShim(LLVMIRBuilder builder, String personalityStubName) {
        LLVMTypeRef intType = builder.intType();
        LLVMTypeRef wordType = builder.longType();
        LLVMTypeRef pointerType = builder.rawPointerType();

        /*
         * int personality(int version, int action, IsolateThread thread, _Unwind_Exception
         * exception, _Unwind_Context context), in the shape LLVMGenerator emits for the entry point
         * stub of LLVMExceptionUnwind.personality: word-typed parameters are i64. The signature is
         * checked against the stub's in LLVMNativeImageCodeCache.writeSupportModule.
         */
        LLVMTypeRef personalityType = builder.functionType(intType, intType, intType, wordType, wordType, wordType);
        LLVMValueRef javaPersonality = builder.getFunction(personalityStubName, personalityType);
        builder.setFunctionCallingConvention(javaPersonality, LLVMCallingConvention.GraalCallingConvention);

        LLVMValueRef routine = builder.addFunction(SEH_PERSONALITY_ROUTINE, personalityType);
        LLVMIRBuilder.setLinkage(routine, LinkageType.External);
        LLVMIRBuilder.setSection(routine, SEH_SECTION);
        builder.positionAtEnd(builder.appendBasicBlock(routine, "entry"));
        LLVMValueRef reasonCode = builder.buildCall(javaPersonality, LLVMIRBuilder.getParam(routine, 0), LLVMIRBuilder.getParam(routine, 1),
                        LLVMIRBuilder.getParam(routine, 2), LLVMIRBuilder.getParam(routine, 3), LLVMIRBuilder.getParam(routine, 4));
        builder.setInstructionCallingConvention(reasonCode, LLVMCallingConvention.GraalCallingConvention);
        builder.buildRet(reasonCode);

        /*
         * EXCEPTION_DISPOSITION _GCC_specific_handler(EXCEPTION_RECORD *, void *frame, CONTEXT *,
         * DISPATCHER_CONTEXT *, _Unwind_Personality_Fn).
         */
        LLVMValueRef gccSpecificHandler = builder.getFunction(GCC_SPECIFIC_HANDLER,
                        builder.functionType(intType, pointerType, pointerType, pointerType, pointerType,
                                        builder.functionPointerType(intType, intType, intType, wordType, wordType, wordType)));

        LLVMValueRef shim = builder.addFunction(SEH_PERSONALITY, sehPersonalityType(builder));
        LLVMIRBuilder.setLinkage(shim, LinkageType.External);
        LLVMIRBuilder.setSection(shim, SEH_SECTION);
        builder.positionAtEnd(builder.appendBasicBlock(shim, "entry"));
        LLVMValueRef disposition = builder.buildCall(gccSpecificHandler, LLVMIRBuilder.getParam(shim, 0), LLVMIRBuilder.getParam(shim, 1),
                        LLVMIRBuilder.getParam(shim, 2), LLVMIRBuilder.getParam(shim, 3), routine);
        builder.buildRet(disposition);
    }
}
