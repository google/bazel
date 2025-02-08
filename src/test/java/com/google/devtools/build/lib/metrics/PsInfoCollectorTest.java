// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.metrics;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.clock.Clock;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class PsInfoCollectorTest {

  private final PsInfoCollector spyCollector = spy(PsInfoCollector.instance());

  @Test
  public void testCollectStats_ignoreSpaces() {
    String psOutput = "    PID  \t  PPID \t  RSS\n   2 1 3216 \t\n  \t 3 1 \t 4096 \t";
    InputStream psStream = new ByteArrayInputStream(psOutput.getBytes(UTF_8));
    Process process = mock(Process.class);
    when(process.getInputStream()).thenReturn(psStream);

    ImmutableMap<Long, PsInfoCollector.PsInfo> pidToPsInfo =
        PsInfoCollector.collectDataFromPsProcess(process);

    ImmutableMap<Long, PsInfoCollector.PsInfo> expectedPidToPsInfo =
        ImmutableMap.of(
            2L, new PsInfoCollector.PsInfo(2, 1, 3216), 3L, new PsInfoCollector.PsInfo(3, 1, 4096));
    assertThat(pidToPsInfo).isEqualTo(expectedPidToPsInfo);
  }

  @Test
  public void testCollectStats_multipleSubprocesses() {
    Clock clock = BlazeClock.instance();
    // pstree of these processes
    // 0-+-1---3-+-7
    //   |       `-8
    //   |-2-+-4
    //   |   `-9
    //   |-5
    //   `-10

    // ps command results:
    // PID PPID RSS
    // 1   0    3216
    // 2   0    4232
    // 3   1    1234
    // 4   2    1001
    // 5   0    40000
    // 7   3    2345
    // 8   3    3456
    // 9   2    1032
    // 10  0    1024
    ImmutableMap<Long, PsInfoCollector.PsInfo> psInfos =
        ImmutableMap.of(
            1L, new PsInfoCollector.PsInfo(1, 0, 3216),
            2L, new PsInfoCollector.PsInfo(2, 0, 4232),
            3L, new PsInfoCollector.PsInfo(3, 1, 1234),
            4L, new PsInfoCollector.PsInfo(4, 2, 1001),
            5L, new PsInfoCollector.PsInfo(5, 0, 40000),
            7L, new PsInfoCollector.PsInfo(7, 3, 2345),
            8L, new PsInfoCollector.PsInfo(8, 3, 3456),
            9L, new PsInfoCollector.PsInfo(9, 2, 1032),
            10L, new PsInfoCollector.PsInfo(10, 0, 1024));
    ImmutableSet<Long> pids = ImmutableSet.of(1L, 2L, 5L, 6L);
    when(spyCollector.collectDataFromPs()).thenReturn(psInfos);

    ResourceSnapshot resourceSnapshot = spyCollector.collectResourceUsage(pids, clock);

    ImmutableMap<Long, Integer> expectedMemoryUsageByPid =
        ImmutableMap.of(1L, 3216 + 1234 + 2345 + 3456, 2L, 4232 + 1001 + 1032, 5L, 40000);
    assertThat(resourceSnapshot.pidToMemoryInKb()).isEqualTo(expectedMemoryUsageByPid);
  }
}
