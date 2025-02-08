// Copyright 2024 The Bazel Authors. All rights reserved.
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.sandbox.Cgroup;
import com.google.devtools.build.lib.sandbox.CgroupsInfo;
import com.google.devtools.build.lib.sandbox.cgroups.VirtualCgroup;
import com.google.devtools.build.lib.sandbox.cgroups.controller.v2.UnifiedMemory;
import com.google.devtools.build.lib.vfs.util.FsApparatus;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CgroupsInfoCollectorTest {

  private final FsApparatus scratch = FsApparatus.newNative();

  @Test
  public void testCollectResourceUsage_returnsValidCgroupInfoMemoryUsage() {
    Clock clock = BlazeClock.instance();
    CgroupsInfo cgroupsInfo1 = mock(CgroupsInfo.class);
    when(cgroupsInfo1.getMemoryUsageInKb()).thenReturn(1000);
    when(cgroupsInfo1.exists()).thenReturn(true);
    CgroupsInfo cgroupsInfo2 = mock(CgroupsInfo.class);
    when(cgroupsInfo2.exists()).thenReturn(false);
    when(cgroupsInfo2.getMemoryUsageInKb()).thenReturn(2000);
    CgroupsInfo cgroupsInfo3 = mock(CgroupsInfo.class);
    when(cgroupsInfo3.exists()).thenReturn(true);
    when(cgroupsInfo3.getMemoryUsageInKb()).thenReturn(3000);

    ResourceSnapshot snapshot =
        CgroupsInfoCollector.instance()
            .collectResourceUsage(
                ImmutableMap.of(1L, cgroupsInfo1, 2L, cgroupsInfo2, 3L, cgroupsInfo3), clock);
    assertThat(snapshot.pidToMemoryInKb()).containsExactly(1L, 1000, 3L, 3000);
  }

  @Test
  public void testCollectResourceUsage_returnsValidCgroupMemoryUsage() throws IOException {
    Clock clock = BlazeClock.instance();
    ImmutableMap.Builder<Long, Cgroup> pidToCgroups = ImmutableMap.builder();

    for (int i = 1; i < 4; i++) {
      UnifiedMemory memory = null;
      if (i != 2) {
        Path memoryPath = scratch.path("cgroup-" + i + "/memory").getPathFile().toPath();
        scratch.file(memoryPath + "/memory.current", String.valueOf(i * 1000 * 1024));
        memory = new UnifiedMemory(memoryPath);
      }
      VirtualCgroup cgroup = VirtualCgroup.create(/* cpu= */ null, memory, ImmutableSet.of());
      pidToCgroups.put((long) i, cgroup);
    }

    ResourceSnapshot snapshot =
        CgroupsInfoCollector.instance().collectResourceUsage(pidToCgroups.buildOrThrow(), clock);

    // Results from cgroups 2 should not be in the snapshot since it doesn't exist.
    assertThat(snapshot.pidToMemoryInKb()).containsExactly(1L, 1000, 3L, 3000);
  }
}
