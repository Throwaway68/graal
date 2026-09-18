/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.image;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.core.BuildDirectoryProvider;
import com.oracle.svm.shared.util.SubstrateUtil;
import com.oracle.svm.core.util.InterruptImageBuilding;
import com.oracle.svm.hosted.DeadlockWatchdog;
import com.oracle.svm.hosted.c.util.FileUtils;

public class LLVMToolchain {
    public static String runLLVMCommand(String command, Path directory, String... args) throws RunFailureException {
        List<String> cmd = new ArrayList<>();
        cmd.add(getLLVMBinDir().resolve(command).toString());
        Collections.addAll(cmd, args);
        return runCommand(directory, cmd);
    }

    public static String runLLVMCommand(String command, Path directory, List<String> args) throws RunFailureException {
        List<String> cmd = new ArrayList<>(args);
        cmd.add(0, getLLVMBinDir().resolve(command).toString());
        return runCommand(directory, cmd);
    }

    public static String runCommand(Path directory, List<String> cmd) throws RunFailureException {
        int status;
        String output;

        Process llvmProcess = null;
        Heartbeat heartbeat = null;
        try {
            ProcessBuilder llvmCommand = FileUtils.prepareCommand(cmd, directory);
            llvmCommand.redirectErrorStream(true);

            FileUtils.traceCommand(llvmCommand);

            llvmProcess = llvmCommand.start();
            heartbeat = new Heartbeat(llvmProcess);

            try (InputStream inputStream = llvmProcess.getInputStream()) {
                List<String> lines = FileUtils.readAllLines(inputStream);

                FileUtils.traceCommandOutput(lines);

                output = String.join(System.lineSeparator(), lines);
            }

            status = llvmProcess.waitFor();
        } catch (IOException e) {
            status = -1;
            output = e.getMessage();
        } catch (InterruptedException e) {
            String commandLine = SubstrateUtil.getShellCommandString(cmd, false);
            throw new InterruptImageBuilding("Interrupted during llvm command execution: " + commandLine);
        } finally {
            if (heartbeat != null) {
                heartbeat.close();
            }
            if (llvmProcess != null) {
                llvmProcess.destroy();
            }
        }

        if (status != 0) {
            throw new RunFailureException(status, output);
        }
        return output;
    }

    public static Path getLLVMBinDir() {
        final String property = System.getProperty("llvm.bin.dir");
        if (property != null) {
            return Paths.get(property);
        }
        Path runtimeDir = BuildDirectoryProvider.singleton().getHome();
        if (runtimeDir == null) {
            throw new IllegalStateException("Could not find java.home");
        }
        if (System.getProperty("java.specification.version").startsWith("1.")) {
            runtimeDir = runtimeDir.resolve("jre");
        }
        return runtimeDir.resolve("lib").resolve("llvm").resolve("bin");
    }

    /**
     * Tells the image generator's deadlock watchdog that the build is progressing while an LLVM
     * tool runs.
     *
     * The watchdog aborts the build when nothing has reported activity for
     * {@code -H:DeadlockWatchdogInterval} minutes (10 by default), which is why every long phase of
     * the builder calls {@link DeadlockWatchdog#recordActivity}. An external tool cannot, and
     * {@code CompletionExecutor} records activity only when a command <em>starts</em>. On PE/COFF
     * that is fatal for a large image: {@code LLVMNativeImageCodeCache.createBitcodeBatches} uses a
     * single batch there (there is no relocatable link on PE/COFF), so the whole image goes through
     * one {@code llvm-link} invocation, and an image big enough to spend ten minutes in it is
     * killed although it is making progress.
     *
     * The heartbeat reports activity only for as long as the process is actually alive, so a real
     * deadlock inside the builder - with no LLVM tool running - is still detected as before. The
     * price is that an LLVM tool which itself hangs is now invisible to the watchdog and will run
     * until something else stops it: in CI that is the gate step's own timeout
     * (`timeout-minutes` in `graalvm-gate.yml`), which is the backstop this relies on.
     */
    private static final class Heartbeat {
        private static final long INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);

        private final Thread thread;
        private volatile boolean stopped;

        Heartbeat(Process process) {
            thread = new Thread(() -> {
                while (!stopped) {
                    try {
                        if (process.waitFor(INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                            return;
                        }
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (ImageSingletons.contains(DeadlockWatchdog.class)) {
                        DeadlockWatchdog.singleton().recordActivity();
                    }
                }
            }, "llvm-tool-heartbeat");
            thread.setDaemon(true);
            thread.start();
        }

        void close() {
            stopped = true;
            thread.interrupt();
        }
    }

    public static final class RunFailureException extends Exception {
        private static final long serialVersionUID = 1L;

        private int status;
        private String output;

        private RunFailureException(int status, String output) {
            this.status = status;
            this.output = output;
        }

        public int getStatus() {
            return status;
        }

        public String getOutput() {
            return output;
        }
    }
}
