// Copyright 2020 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.server;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.StringEncoding;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import javax.annotation.concurrent.GuardedBy;

/** A thread that watches if the PID file changes and shuts down the server immediately if so. */
public class PidFileWatcher extends Thread {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  
  private final Path pidFile;
  private final int serverPid;
  private final Runnable onPidFileChange;

  @GuardedBy("this")
  private boolean shuttingDown = false;

  public PidFileWatcher(Path pidFile, int serverPid) {
    this(
        pidFile,
        serverPid,
        () -> Runtime.getRuntime().halt(ExitCode.BLAZE_INTERNAL_ERROR.getNumericExitCode()));
  }

  /** Test-only constructor allowing a more gentle {@link #onPidFileChange} callback. */
  @VisibleForTesting
  PidFileWatcher(Path pidFile, int serverPid, Runnable onPidFileChange) {
    super("pid-file-watcher");
    this.pidFile = pidFile;
    this.serverPid = serverPid;
    this.onPidFileChange = onPidFileChange;
    setDaemon(true);
  }

  /**
   * Signals a shutdown is in progress, thus pid file changes can be expected and should not trigger
   * an aggressive shutdown.
   */
  synchronized void signalShutdown() {
    shuttingDown = true;
  }

  @Override
  public void run() {
    do {
      Uninterruptibles.sleepUninterruptibly(5, SECONDS);
    } while (runPidFileChecks());
  }

  /**
   * Runs one iteration of pid file checks. Returns whether or not the main loop should continue, or
   * crashes the program via {@link #onPidFileChange} callback.
   */
  @VisibleForTesting
  boolean runPidFileChecks() {
    if (pidFileValid(pidFile, serverPid)) {
      return true;
    }

    synchronized (this) {
      if (shuttingDown) {
        logger.atWarning().log(
            "PID file deleted or overwritten but shutdown is already in progress");
        return false;
      }

      shuttingDown = true;
      // Someone overwrote the PID file. Maybe it's another server, so shut down as quickly
      // as possible without even running the shutdown hooks (that would delete it)
      logger.atSevere().log("PID file deleted or overwritten, exiting as quickly as possible");
      onPidFileChange.run();
      throw new IllegalStateException("Should have crashed.");
    }
  }

  private static boolean pidFileValid(Path pidFile, int expectedPid) {
    String pidFileContents;
    try {
      pidFileContents =
          StringEncoding.internalToUnicode(FileSystemUtils.readContentToString(pidFile));
    } catch (IOException e) {
      logger.atInfo().log("Cannot read PID file: %s", e.getMessage());
      return false;
    }

    try {
      return Integer.parseInt(pidFileContents) == expectedPid;
    } catch (NumberFormatException e) {
      logger.atWarning().withCause(e).log("Pid was invalid: %s", pidFileContents);
      return false;
    }
  }
}
