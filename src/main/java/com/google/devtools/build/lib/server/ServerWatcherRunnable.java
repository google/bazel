// Copyright 2018 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.platform.SystemMemoryPressureMonitor;
import com.google.devtools.build.lib.platform.SystemMemoryPressureMonitor.Level;
import com.google.devtools.build.lib.unix.ProcMeminfoParser;
import com.google.devtools.build.lib.util.OS;
import io.grpc.Server;
import java.io.IOException;
import java.time.Duration;

/**
 * Runnable that checks to see if a {@link Server} server has been idle for too long and shuts down
 * the server if so.
 */
class ServerWatcherRunnable implements Runnable {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private static final Duration IDLE_MEMORY_CHECK_INTERVAL = Duration.ofSeconds(5);
  private static final Duration TIME_IDLE_BEFORE_MEMORY_CHECK = Duration.ofMinutes(5);
  private static final long FREE_MEMORY_KB_ABSOLUTE_THRESHOLD = 1L << 20;
  private static final double FREE_MEMORY_PERCENTAGE_THRESHOLD = 0.05;

  private final Server server;
  private final long maxIdleSeconds;
  private final CommandManager commandManager;
  private final LowMemoryChecker lowMemoryChecker;
  private final boolean shutdownOnLowSysMem;

  /** Generic abstraction to check for low memory conditions on different platforms. */
  private abstract static class LowMemoryChecker {

    /** Timestamp of the moment the server went idle. */
    private long lastIdleTimeMillis = -1;

    /** Creates a memory checker that makes sense for the current platform. */
    static LowMemoryChecker forCurrentOS() {
      switch (OS.getCurrent()) {
        case LINUX:
          return new ProcMeminfoLowMemoryChecker(ProcMeminfoParser::new);

        default:
          return new MemoryPressureLowMemoryChecker();
      }
    }

    /** Checks if the server should shut down due to a low memory condition. */
    final boolean shouldShutdown() {
      checkState(lastIdleTimeMillis >= 0, "reset() ought to have been called before this");

      if (BlazeClock.instance().currentTimeMillis() - lastIdleTimeMillis
          < TIME_IDLE_BEFORE_MEMORY_CHECK.toMillis()) {
        // Only run memory check if the server has been idle for longer than
        // TIME_IDLE_BEFORE_MEMORY_CHECK.
        return false;
      }

      return check();
    }

    /** Returns true if the system has observed low memory conditions. */
    abstract boolean check();

    /** Notifies the checker that the server went idle at the given timestamp. */
    void reset(long lastIdleTimeMillis) {
      this.lastIdleTimeMillis = lastIdleTimeMillis;
    }
  }

  /**
   * A low memory conditions checker that relies on memory pressure state.
   *
   * <p>Memory pressure state is provided by the platform-agnostic {@link
   * SystemMemoryPressureMonitor} class, which may be a no-op for the current platform.
   */
  private static class MemoryPressureLowMemoryChecker extends LowMemoryChecker {

    @Override
    boolean check() {
      return SystemMemoryPressureMonitor.getInstance().level() != Level.NORMAL;
    }
  }

  /** A low memory condition checker that uses instantaneous data from {@code /proc/meminfo}. */
  static class ProcMeminfoLowMemoryChecker extends LowMemoryChecker {

    /** Supplier for a {@link ProcMeminfoParser}. */
    interface ProcMeminfoParserSupplier {
      ProcMeminfoParser get() throws IOException;
    }

    private final ProcMeminfoParserSupplier supplier;

    ProcMeminfoLowMemoryChecker(ProcMeminfoParserSupplier supplier) {
      this.supplier = supplier;
    }

    @Override
    boolean check() {
      try {
        ProcMeminfoParser meminfoParser = supplier.get();
        long freeRamKb = meminfoParser.getFreeRamKb();
        long usedRamKb = meminfoParser.getTotalKb();
        double fractionRamFree = ((double) freeRamKb) / usedRamKb;

        // Shutdown when both the absolute amount and percentage of free RAM is lower than the set
        // thresholds.
        return fractionRamFree < FREE_MEMORY_PERCENTAGE_THRESHOLD
            && freeRamKb < FREE_MEMORY_KB_ABSOLUTE_THRESHOLD;
      } catch (IOException e) {
        logger.atWarning().withCause(e).log("Unable to read memory info.");
        return false;
      }
    }
  }

  ServerWatcherRunnable(
      Server server,
      long maxIdleSeconds,
      boolean shutdownOnLowSysMem,
      CommandManager commandManager) {
    this(
        server,
        maxIdleSeconds,
        shutdownOnLowSysMem,
        commandManager,
        LowMemoryChecker.forCurrentOS());
  }

  @VisibleForTesting
  ServerWatcherRunnable(
      Server server,
      long maxIdleSeconds,
      boolean shutdownOnLowSysMem,
      CommandManager commandManager,
      LowMemoryChecker lowMemoryChecker) {
    Preconditions.checkArgument(
        maxIdleSeconds > 0,
        "Expected to only check idleness when --max_idle_secs > 0 but it was %s",
        maxIdleSeconds);
    this.server = server;
    this.maxIdleSeconds = maxIdleSeconds;
    this.commandManager = commandManager;
    this.lowMemoryChecker = lowMemoryChecker;
    this.shutdownOnLowSysMem = shutdownOnLowSysMem;
  }

  @Override
  public void run() {
    boolean idle = commandManager.isEmpty();
    boolean wasIdle = false;
    long shutdownTimeMillis = -1;

    while (true) {
      if (!wasIdle && idle) {
        long now = BlazeClock.instance().currentTimeMillis();
        shutdownTimeMillis = now + Duration.ofSeconds(maxIdleSeconds).toMillis();
        lowMemoryChecker.reset(now);
      }

      try {
        if (idle) {
          Verify.verify(shutdownTimeMillis > 0);
          if (shutdownOnLowSysMem && lowMemoryChecker.shouldShutdown()) {
            logger.atSevere().log("Available RAM is low. Shutting down idle server...");
            break;
          }
          // Re-run the check every 5 seconds if no other commands have been sent to the server.
          commandManager.waitForChange(IDLE_MEMORY_CHECK_INTERVAL.toMillis());
        } else {
          commandManager.waitForChange();
        }
      } catch (InterruptedException e) {
        // Dealt with by checking the current time below.
      }

      wasIdle = idle;
      idle = commandManager.isEmpty();
      if (wasIdle && idle && BlazeClock.instance().currentTimeMillis() >= shutdownTimeMillis) {
        logger.atInfo().log("About to shutdown due to idleness");
        break;
      }
    }
    server.shutdown();
  }
}
