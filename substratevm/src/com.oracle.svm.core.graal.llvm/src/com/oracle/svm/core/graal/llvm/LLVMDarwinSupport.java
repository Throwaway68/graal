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

/**
 * Darwin (Mach-O) specifics of the LLVM backend.
 * <p>
 * {@code ld64.lld} does not implement {@code -r}: it warns ("Option `-r\' is not yet implemented.
 * Stay tuned...") and links an executable instead, which dies on the first reference the batches
 * make to the image heap. The relocatable link of the compiled batches therefore goes through
 * {@link #PARTIAL_LINKER}, the platform linker, which every macOS image build runs for its final
 * link anyway.
 */
public final class LLVMDarwinSupport {

    /**
     * Platform linker. The LLVM toolchain bundle has no other Mach-O linker, and a relocatable link
     * needs no flags beyond {@code -r}: {@code ld} takes the architecture from its inputs.
     */
    public static final String PARTIAL_LINKER = "ld";

    private LLVMDarwinSupport() {
    }

    public static boolean isDarwin() {
        return Platform.includedIn(Platform.DARWIN.class);
    }
}
