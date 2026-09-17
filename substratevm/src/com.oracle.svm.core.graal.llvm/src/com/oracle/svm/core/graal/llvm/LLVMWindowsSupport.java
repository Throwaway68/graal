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
 * Windows (PE/COFF) specifics of the LLVM backend. There is no partial link on Windows, so all Java
 * code is compiled into one object whose functions live in the grouped section {@code .text$svm1}.
 * COFF linkers sort {@code .text$*} contributions by suffix, so two empty marker sections define the
 * code-section start and end symbols that the image object declares as undefined (see
 * NativeImage.getTextSectionStartSymbol/EndSymbol).
 */
public final class LLVMWindowsSupport {
    public static final String START_SECTION = ".text$svm0";
    public static final String CODE_SECTION = ".text$svm1";
    public static final String END_SECTION = ".text$svm2";

    private LLVMWindowsSupport() {
    }

    public static boolean isWindows() {
        return Platform.includedIn(Platform.WINDOWS.class);
    }

    /** Bitcode of a module holding the two marker sections (and, later, the SEH personality shim). */
    public static byte[] buildSupportModule(String startSymbol, String endSymbol) {
        try (LLVMIRBuilder builder = new LLVMIRBuilder("svm-windows-support")) {
            builder.setTarget(LLVMTargetSpecific.get().getTargetTriple());
            String asm = String.join("\n",
                            ".section " + START_SECTION + ",\"xr\"",
                            ".p2align 4",
                            ".globl " + startSymbol,
                            startSymbol + ":",
                            ".section " + END_SECTION + ",\"xr\"",
                            ".p2align 4",
                            ".globl " + endSymbol,
                            endSymbol + ":",
                            "");
            builder.setModuleInlineAsm(asm);
            return builder.getBitcode();
        }
    }
}
