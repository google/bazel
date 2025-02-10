// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.util;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.GoogleLogger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.annotation.Nullable;

/**
 * Provides an external way for the Bazel server to communicate its exit code to the client, when
 * the main gRPC channel is unavailable because the exit is too abrupt or originated in an async
 * thread.
 *
 * <p>Uses Java 8 {@link Path} objects rather than Bazel ones to avoid depending on the rest of
 * Bazel.
 */
// TODO(b/138456686): When the Bazel server is completely converted to use FailureDetail messages
//  for its failure modes, this publishing mechanism and the file it creates can probably be
//  deleted. We'll need to confirm that nothing other than the Bazel client consumes it.
public class CustomExitCodePublisher {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static final String EXIT_CODE_FILENAME = "exit_code_to_use_on_abrupt_exit";
  @Nullable private static volatile Path abruptExitCodeFilePath = null;

  private CustomExitCodePublisher() {}

  public static void setAbruptExitStatusFileDir(String path) {
    abruptExitCodeFilePath = Paths.get(path).resolve(EXIT_CODE_FILENAME);
  }

  public static void maybeDeleteAbruptExitStatusFile() {
    if (abruptExitCodeFilePath != null) {
      try {
        boolean deleted = Files.deleteIfExists(abruptExitCodeFilePath);
        if (deleted) {
          logger.atInfo().log("Deleted old abrupt exit status file");
        }
      } catch (IOException ioe) {
        logger.atWarning().withCause(ioe).log("Failed to delete old abrupt exit status file");
      }
    }
  }

  @VisibleForTesting
  public static void resetAbruptExitStatusFile() {
    abruptExitCodeFilePath = null;
  }

  public static boolean maybeWriteExitStatusFile(int exitCode) {
    Path path = CustomExitCodePublisher.abruptExitCodeFilePath;
    if (path != null) {
      try {
        Files.write(path, String.valueOf(exitCode).getBytes(StandardCharsets.UTF_8));
        return true;
      } catch (IOException ioe) {
        System.err.printf(
            "io error writing %d to abrupt exit status file %s: %s\n",
            exitCode, path, ioe.getMessage());
      }
    }
    return false;
  }
}
