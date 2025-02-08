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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.server.ServerWatcherRunnable.ProcMeminfoLowMemoryChecker;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.unix.ProcMeminfoParser;
import com.google.devtools.build.lib.util.OS;
import io.grpc.Server;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ServerWatcherRunnable}. */
@RunWith(JUnit4.class)
public class ServerWatcherRunnableTest {
  private ManualClock clock;
  private Server mockServer;

  @Before
  public final void setManualClock() {
    clock = new ManualClock();
    mockServer = mock(Server.class);
    BlazeClock.setClock(clock);
  }

  @Test
  public void testBasicIdleCheck() throws Exception {
    CommandManager mockCommands = mock(CommandManager.class);
    ServerWatcherRunnable underTest =
        new ServerWatcherRunnable(
            mockServer, /*maxIdleSeconds=*/ 10, /*shutdownOnLowSysMem=*/ false, mockCommands);
    Thread thread = new Thread(underTest);
    when(mockCommands.isEmpty()).thenReturn(true);
    AtomicInteger checkIdleCounter = new AtomicInteger();
    doAnswer(
            invocation -> {
              checkIdleCounter.incrementAndGet();
              verify(mockServer, never()).shutdown();
              clock.advanceMillis(Duration.ofSeconds(5).toMillis());
              return null;
            })
        .when(mockCommands)
        .waitForChange(anyLong());

    thread.start();
    thread.join(TestUtils.WAIT_TIMEOUT_MILLISECONDS);

    verify(mockServer).shutdown();
    assertThat(checkIdleCounter.get()).isEqualTo(2);
  }

  @Test
  public void runLowAbsoluteHighPercentageMemoryCheck() throws Exception {
    if (!usingLinux()) {
      return;
    }
    assertThat(doesIdleLowMemoryCheckShutdown(/*freeRamKb=*/ 5000, /*totalRamKb=*/ 10000))
        .isFalse();
  }

  @Test
  public void runHighAbsoluteLowPercentageMemoryCheck() throws Exception {
    if (!usingLinux()) {
      return;
    }
    assertThat(doesIdleLowMemoryCheckShutdown(/*freeRamKb=*/ 1L << 21, /*totalRamKb=*/ 1L << 30))
        .isFalse();
  }

  @Test
  public void runLowAbsoluteLowPercentageMemoryCheck() throws Exception {
    if (!usingLinux()) {
      return;
    }
    assertThat(doesIdleLowMemoryCheckShutdown(/*freeRamKb=*/ 5000, /*totalRamKb=*/ 1000000))
        .isTrue();
  }

  @Test
  public void testshutdownOnLowSysMemDisabled() throws Exception {
    if (!usingLinux()) {
      return;
    }
    assertThat(
            doesIdleLowMemoryCheckShutdown(
                /*freeRamKb=*/ 5000, /*totalRamKb=*/ 1000000, /*shutdownOnLowSysMem=*/ false))
        .isFalse();
  }

  private boolean doesIdleLowMemoryCheckShutdown(long freeRamKb, long totalRamKb) throws Exception {
    return doesIdleLowMemoryCheckShutdown(freeRamKb, totalRamKb, /*shutdownOnLowSysMem=*/ true);
  }

  private boolean doesIdleLowMemoryCheckShutdown(
      long freeRamKb, long totalRamKb, boolean shutdownOnLowSysMem) throws Exception {
    CommandManager mockCommandManager = mock(CommandManager.class);
    ProcMeminfoParser mockParser = mock(ProcMeminfoParser.class);
    ServerWatcherRunnable underTest =
        new ServerWatcherRunnable(
            mockServer,
            // Shut down after an hour if we see no memory issues.
            /* maxIdleSeconds= */ Duration.ofHours(1).toSeconds(),
            shutdownOnLowSysMem,
            mockCommandManager,
            new ProcMeminfoLowMemoryChecker(() -> mockParser));
    Thread thread = new Thread(underTest);
    when(mockCommandManager.isEmpty()).thenReturn(true);
    AtomicInteger serverWatcherLoopCounter = new AtomicInteger();

    when(mockParser.getFreeRamKb()).thenReturn(freeRamKb);
    when(mockParser.getTotalKb()).thenReturn(totalRamKb);
    doAnswer(
            invocation -> {
              serverWatcherLoopCounter.incrementAndGet();
              clock.advanceMillis(Duration.ofMinutes(1).toMillis());
              return null;
            })
        .when(mockCommandManager)
        .waitForChange(Duration.ofSeconds(5).toMillis());

    thread.start();
    thread.join(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
    verify(mockServer).shutdown();

    // If we shut down due to memory pressure, it will only be after 5 minutes of being idle.
    return serverWatcherLoopCounter.get() == 5;
  }

  private boolean usingLinux() {
    return OS.getCurrent() == OS.LINUX;
  }
}
