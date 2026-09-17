/*
 * Copyright (c) 2019, 2023, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.graal.llvm.runtime;

import static com.oracle.svm.shared.util.VMError.shouldNotReachHere;
import static org.graalvm.nativeimage.c.function.CFunction.Transition.NO_TRANSITION;

import java.util.function.BooleanSupplier;

import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.struct.RawField;
import org.graalvm.nativeimage.c.struct.RawStructure;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.word.Pointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.WordFactory;

import com.oracle.graal.pointsto.infrastructure.UniverseMetaAccess;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.c.InitializeReservedRegistersPrologue;
import com.oracle.svm.core.graal.llvm.util.LLVMDirectives;
import com.oracle.svm.guest.staging.core.graal.stackvalue.UnsafeStackValue;
import com.oracle.svm.core.snippets.ExceptionUnwind;
import com.oracle.svm.core.stack.StackOverflowCheck;
import com.oracle.svm.guest.staging.c.function.CEntryPointOptions;
import com.oracle.svm.hosted.code.CEntryPointCallStubSupport;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.DisallowLayered;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;

import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;

@CContext(LLVMDirectives.class)
public class LLVMExceptionUnwind {

    /*
     * Exception handling using libunwind happens using the following steps:
     *
     * 1. The exception is raised using _Unwind_RaiseException
     *
     * 2. libunwind walks the stack twice, and for each Java call frame calls the personality
     * function below
     *
     * 3. During the first stack walk (UA_SEARCH_PHASE), the personality function tells whether it
     * has a registered handler able to handle the exception (URC_HANDLER_FOUND).
     *
     * 4. During the second stack walk (UA_CLEANUP_PHASE), the frame that accepted the exception
     * prepares the context to jump to the handler (URC_INSTALL_CONTEXT).
     *
     * In order for the personality function to function normally, it needs the context from the
     * thread which threw the exception to be restored when the function is called. This is done
     * through the thread argument which is passed to libunwind in place of the exception object
     * (see raiseException()). Libunwind then passes the value back as the third argument to the
     * personality function. The actual exception is then retrieved from the thread-local variable
     * SnippetRuntime.currentException.
     *
     * When preparing to jump to the handler, the exception object is placed in the return register,
     * from which it will get extracted after the landingpad instruction (see
     * NodeLLVMBuilder.emitReadExceptionObject).
     */
    @CEntryPoint(include = IncludeForLLVMOnly.class, publishAs = CEntryPoint.Publish.NotPublished)
    @CEntryPointOptions(prologue = InitializeReservedRegistersPrologue.class, epilogue = CEntryPointOptions.NoEpilogue.class)
    @Uninterruptible(reason = "Must not execute a recurring callback before returning", calleeMustBe = false)
    @SuppressWarnings("unused")
    public static int personality(int version, int action, IsolateThread thread, _Unwind_Exception unwindException, _Unwind_Context context) {
        CIntPointer ipBeforeInstruction = UnsafeStackValue.get(Integer.BYTES);
        Pointer ip = getIPInfo(context, ipBeforeInstruction);
        if (ipBeforeInstruction.read() == 0) {
            ip = ip.subtract(1);
        }
        Pointer functionStart = getRegionStart(context);
        long pcOffset = ip.rawValue() - functionStart.rawValue();

        Pointer lsda = getLanguageSpecificData(context);
        long handlerInfo = GCCExceptionTable.getHandlerInfo(lsda, pcOffset);

        if (handlerInfo == GCCExceptionTable.NO_HANDLER) {
            return _URC_CONTINUE_UNWIND();
        }

        if ((action & _UA_SEARCH_PHASE()) != 0) {
            if (GCCExceptionTable.isCleanup(handlerInfo)) {
                return _URC_CONTINUE_UNWIND();
            }
            return _URC_HANDLER_FOUND();
        } else if ((action & _UA_CLEANUP_PHASE()) != 0) {
            setIP(context, functionStart.add((int) GCCExceptionTable.getHandlerOffset(handlerInfo)));
            Throwable exception = ExceptionUnwind.currentException.get();
            if (!GCCExceptionTable.isCleanup(handlerInfo) && exception instanceof StackOverflowError && StackOverflowCheck.singleton().isYellowZoneAvailable()) {
                StackOverflowCheck.singleton().protectYellowZone();
            }
            return _URC_INSTALL_CONTEXT();
        } else {
            return _URC_FATAL_PHASE1_ERROR();
        }
    }

    @Uninterruptible(reason = "Called before Java state is restored")
    public static Throwable retrieveException() {
        Throwable exception = ExceptionUnwind.currentException.get();
        ExceptionUnwind.currentException.set(null);
        return exception;
    }

    private static final class IncludeForLLVMOnly implements BooleanSupplier {
        @Override
        public boolean getAsBoolean() {
            return SubstrateOptions.useLLVMBackend();
        }
    }

    public static ResolvedJavaMethod getPersonalityStub(MetaAccessProvider metaAccess) {
        try {
            return ((UniverseMetaAccess) metaAccess).getUniverse().lookup(CEntryPointCallStubSupport.singleton()
                            .getStubForMethod(LLVMExceptionUnwind.class.getMethod("personality", int.class, int.class, IsolateThread.class, _Unwind_Exception.class, _Unwind_Context.class)));
        } catch (NoSuchMethodException e) {
            throw shouldNotReachHere(e);
        }
    }

    public static ResolvedJavaMethod getRetrieveExceptionMethod(MetaAccessProvider metaAccess) {
        try {
            return metaAccess.lookupJavaMethod(LLVMExceptionUnwind.class.getMethod("retrieveException"));
        } catch (NoSuchMethodException e) {
            throw shouldNotReachHere(e);
        }
    }

    public static ExceptionUnwind createRaiseExceptionHandler() {
        return new LLVMExceptionUnwindHandler();
    }

    @SingletonTraits(access = AllAccess.class, layeredCallbacks = NoLayeredCallbacks.class, other = DisallowLayered.class)
    private static final class LLVMExceptionUnwindHandler extends ExceptionUnwind {
        @Override
        @Uninterruptible(reason = "Code that is fully uninterruptible may throw and catch exceptions. Therefore, the exception handling must be fully uninterruptible as well.")
        protected void customUnwindException(Pointer callerSP) {
            _Unwind_Exception exceptionStructure = UnsafeStackValue.get(_Unwind_Exception.class);
            exceptionStructure.set_exception_class(CurrentIsolate.getCurrentThread());
            exceptionStructure.set_exception_cleanup(WordFactory.nullPointer());
            raiseException(exceptionStructure);
        }
    }

    // Allow methods with non-standard names: Checkstyle: stop

    // The following declarations mirror <unwind.h>. They are written out by hand rather than
    // read from the header (@CConstant, @CStruct) because Windows has no <unwind.h> at image
    // build time: libunwind ships one only on the platforms whose toolchain headers the C
    // compiler used for the header queries can see. The values are fixed by the Itanium C++ ABI
    // and are the same in libgcc and in libunwind on every platform this backend targets.
    //
    // See:
    // - https://clang.llvm.org/doxygen/unwind_8h_source.html
    // - https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=libgcc/unwind-generic.h

    /* _Unwind_Reason_Code */
    private static int _URC_FATAL_PHASE1_ERROR() {
        return 3;
    }

    private static int _URC_HANDLER_FOUND() {
        return 6;
    }

    private static int _URC_INSTALL_CONTEXT() {
        return 7;
    }

    private static int _URC_CONTINUE_UNWIND() {
        return 8;
    }

    /* _Unwind_Action */
    private static int _UA_SEARCH_PHASE() {
        return 1;
    }

    private static int _UA_CLEANUP_PHASE() {
        return 2;
    }

    @CFunction(value = "_Unwind_RaiseException", transition = NO_TRANSITION)
    public static native int raiseException(_Unwind_Exception exception);

    @CFunction(value = "_Unwind_GetIP", transition = NO_TRANSITION)
    public static native Pointer getIP(_Unwind_Context context);

    @CFunction(value = "_Unwind_GetIPInfo", transition = NO_TRANSITION)
    public static native Pointer getIPInfo(_Unwind_Context context, CIntPointer ipBeforeInstruction);

    @CFunction(value = "_Unwind_SetIP", transition = NO_TRANSITION)
    public static native Pointer setIP(_Unwind_Context context, Pointer ip);

    @CFunction(value = "_Unwind_GetRegionStart", transition = NO_TRANSITION)
    public static native Pointer getRegionStart(_Unwind_Context context);

    @CFunction(value = "_Unwind_GetLanguageSpecificData", transition = NO_TRANSITION)
    public static native Pointer getLanguageSpecificData(_Unwind_Context context);
}

/**
 * Layout of libunwind's {@code struct _Unwind_Exception}. Declared as a raw structure so that no
 * header is needed at build time: {@code private_} has six words on Windows (SEH mode) and two
 * elsewhere, and six is a safe superset. Raw structures are laid out by
 * {@code RawStructureLayoutPlanner} in field-size-descending, then alphabetical order; with all
 * fields one word wide that puts {@code exception_class} at offset 0 and {@code exception_cleanup}
 * at offset 8, which is what the C struct requires - libunwind passes the first field's value to
 * the personality function as its {@code exceptionClass} argument. Alignment: libunwind only reads
 * the fields through the pointer, so the 16-byte alignment of the C declaration is not required.
 * <p>
 * This is a top-level type on purpose. A raw structure nested in {@link LLVMExceptionUnwind} would
 * inherit that class's {@link CContext}, and only raw structures in the built-in context get their
 * layout planned.
 * <p>
 * Every {@code private_} word needs a getter and a setter because {@code @RawStructure} fields do;
 * nothing in Java reads or writes them, they are storage for libunwind.
 */
@RawStructure
interface _Unwind_Exception extends PointerBase {
    @RawField
    PointerBase exception_class();

    @RawField
    void set_exception_class(PointerBase value);

    @RawField
    PointerBase exception_cleanup();

    @RawField
    void set_exception_cleanup(PointerBase value);

    @RawField
    long private0();

    @RawField
    void set_private0(long value);

    @RawField
    long private1();

    @RawField
    void set_private1(long value);

    @RawField
    long private2();

    @RawField
    void set_private2(long value);

    @RawField
    long private3();

    @RawField
    void set_private3(long value);

    @RawField
    long private4();

    @RawField
    void set_private4(long value);

    @RawField
    long private5();

    @RawField
    void set_private5(long value);
}

/** Opaque pointer to libunwind's {@code struct _Unwind_Context}. */
interface _Unwind_Context extends PointerBase {
}

// Checkstyle: resume
