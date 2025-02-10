// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.time.Duration;

/** An {@link IdleTask} to run a {@link InstallBaseGarbageCollector}. */
public final class InstallBaseGarbageCollectorIdleTask implements IdleTask {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final InstallBaseGarbageCollector gc;

  private InstallBaseGarbageCollectorIdleTask(InstallBaseGarbageCollector gc) {
    this.gc = gc;
  }

  /**
   * Creates a new {@link InstallBaseGarbageCollectorIdleTask} according to the options.
   *
   * @param installBase the server install base
   * @param maxAge how long an install base must remain unused to be considered stale
   * @return the idle task
   */
  public static InstallBaseGarbageCollectorIdleTask create(Path installBase, Duration maxAge) {
    return new InstallBaseGarbageCollectorIdleTask(
        new InstallBaseGarbageCollector(installBase.getParentDirectory(), installBase, maxAge));
  }

  @VisibleForTesting
  public InstallBaseGarbageCollector getGarbageCollector() {
    return gc;
  }

  @Override
  public void run() {
    try {
      logger.atInfo().log("Install base garbage collection started");
      gc.run();
    } catch (IOException e) {
      logger.atInfo().withCause(e).log("Install base garbage collection failed");
    } catch (InterruptedException e) {
      logger.atInfo().withCause(e).log("Install base garbage collection interrupted");
    }
  }
}
