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

package com.google.devtools.build.lib.sandbox.cgroups;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.Files;
import com.google.devtools.build.lib.sandbox.cgroups.controller.Controller.Cpu;
import com.google.devtools.build.lib.sandbox.cgroups.controller.v1.LegacyCpu;
import com.google.devtools.build.lib.sandbox.cgroups.controller.v2.UnifiedCpu;
import com.google.devtools.build.lib.vfs.util.FsApparatus;
import java.io.File;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CpuTest {

  private final FsApparatus scratch = FsApparatus.newNative();

  @Test
  public void setCpuLimit_v1() throws IOException {
    File quota = scratch.file("cgroup/cpu/cpu.cfs_quota_us", "-1").getPathFile();
    scratch.file("cgroup/cpu/cpu.cfs_period_us", "1000");
    Cpu cpu = new LegacyCpu(scratch.path("cgroup/cpu").getPathFile().toPath());
    cpu.setCpus(3);
    assertThat(Files.asCharSource(quota, UTF_8).read()).isEqualTo("3000");
  }

  @Test
  public void getCpuLimit_v1() throws IOException {
    scratch.file("cgroup/cpu/cpu.cfs_quota_us", "4000");
    scratch.file("cgroup/cpu/cpu.cfs_period_us", "1000");
    Cpu cpu = new LegacyCpu(scratch.path("cgroup/cpu").getPathFile().toPath());
    assertThat(cpu.getCpus()).isEqualTo(4);
  }

  @Test
  public void setCpuLimit_v2() throws IOException {
    File limit = scratch.file("cgroup/cpu/cpu.max", "-1 100000").getPathFile();
    Cpu cpu = new UnifiedCpu(scratch.path("cgroup/cpu").getPathFile().toPath());
    cpu.setCpus(5);
    assertThat(Files.asCharSource(limit, UTF_8).read()).isEqualTo("500000 100000");
  }

  @Test
  public void setCpuLimitNewLine_v2() throws IOException {
    File limit = scratch.file("cgroup/cpu/cpu.max", "-1 100000\n").getPathFile();
    Cpu cpu = new UnifiedCpu(scratch.path("cgroup/cpu").getPathFile().toPath());
    cpu.setCpus(5);
    assertThat(Files.asCharSource(limit, UTF_8).read()).isEqualTo("500000 100000");
  }

  @Test
  public void getCpuLimit_v2() throws IOException {
    scratch.file("cgroup/cpu/cpu.max", "6000 1000");
    Cpu cpu = new UnifiedCpu(scratch.path("cgroup/cpu").getPathFile().toPath());
    assertThat(cpu.getCpus()).isEqualTo(6);
  }

  @Test
  public void getCpuLimitNewLine_v2() throws IOException {
    scratch.file("cgroup/cpu/cpu.max", "6000 1000\n");
    Cpu cpu = new UnifiedCpu(scratch.path("cgroup/cpu").getPathFile().toPath());
    assertThat(cpu.getCpus()).isEqualTo(6);
  }

  @Test
  public void getCpuLimitMax_v2() throws IOException {
    scratch.file("cgroup/cpu/cpu.max", "max 1000\n");
    Cpu cpu = new UnifiedCpu(scratch.path("cgroup/cpu").getPathFile().toPath());
    assertThat(cpu.getCpus()).isEqualTo(Runtime.getRuntime().availableProcessors());
  }
}
