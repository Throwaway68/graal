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

import org.graalvm.nativeimage.Platform;

import com.oracle.svm.core.graal.llvm.util.LLVMIRBuilder;
import com.oracle.svm.core.graal.llvm.util.LLVMTargetSpecific;

/**
 * Darwin (Mach-O) specifics of the LLVM backend.
 * <p>
 * Two tools of the LLVM toolchain cannot do on Mach-O what the backend uses them for on ELF, and
 * this class holds what replaces them:
 * <ul>
 * <li>{@code ld64.lld} does not implement {@code -r}. It warns ("Option `-r' is not yet
 * implemented") and links an executable instead, which fails on the first unresolved image-heap
 * symbol. The partial link therefore goes through {@link #PARTIAL_LINKER}, the platform linker,
 * which the image build needs anyway for its final link.</li>
 * <li>{@code llvm-objcopy --add-symbol} is rejected with "option is not supported for MachO", so
 * the code-section boundary symbols cannot be added to the linked object after the fact. They come
 * from the two marker objects of {@link #buildMarkerModule} instead, which the partial link takes
 * as its first and its last input; {@code ld -r} concatenates the {@code __TEXT,__text}
 * contributions of its inputs in input order, so they bracket the Java code exactly.</li>
 * </ul>
 */
public final class LLVMDarwinSupport {

    /** The Mach-O symbol table spells every name with a leading underscore. */
    public static final String SYMBOL_PREFIX = "_";

    /**
     * Platform linker. Mach-O has no separate {@code -r} capable linker in the LLVM toolchain
     * bundle, and every macOS image build runs the platform linker for the final link anyway.
     */
    public static final String PARTIAL_LINKER = "ld";

    /** Section every Java function is compiled into, and so the one the markers belong in. */
    private static final String CODE_SECTION = "__TEXT,__text,regular,pure_instructions";

    private LLVMDarwinSupport() {
    }

    public static boolean isDarwin() {
        return Platform.includedIn(Platform.DARWIN.class);
    }

    /** The name {@code symbol} has in the object file. */
    public static String machOSymbol(String symbol) {
        return SYMBOL_PREFIX + symbol;
    }

    /**
     * Bitcode of a module whose only content is a global label at the start of {@code __text}. The
     * module carries no alignment directive, so its (empty) section can never pad the merged
     * {@code __text}: the end marker lands on the last byte of the last function and the code size
     * the image records is the size of the code.
     */
    public static byte[] buildMarkerModule(String moduleName, String symbol) {
        try (LLVMIRBuilder builder = new LLVMIRBuilder(moduleName)) {
            builder.setTarget(LLVMTargetSpecific.get().getTargetTriple());
            builder.setModuleInlineAsm(markerAsm(machOSymbol(symbol)));
            assert builder.verifyBitcode();
            return builder.getBitcode();
        }
    }

    private static String markerAsm(String symbol) {
        return String.join("\n",
                        ".section " + CODE_SECTION,
                        ".globl " + symbol,
                        symbol + ":",
                        "");
    }
}
