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

package com.google.devtools.build.lib.sandbox.cgroups.controller.v2;

import com.google.devtools.build.lib.sandbox.cgroups.controller.Controller;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

/** V2 cpu controller. */
public class UnifiedCpu extends UnifiedController implements Controller.Cpu {
  private final Path path;

  public UnifiedCpu(Path path) {
    this.path = path;
  }

  @Override
  public Cpu child(String name) throws IOException {
    return new UnifiedCpu(getChild(name));
  }

  @Override
  public Path getPath() {
    return path;
  }

  @Override
  public void setCpus(double cpus) throws IOException {
    long period;
    try (Scanner scanner = new Scanner(Files.newBufferedReader(path.resolve("cpu.max")))) {
      period = scanner.skip("\\S+\\s").nextInt();
    }
    long quota = Math.round(period * cpus);
    String limit = String.format("%d %d", quota, period);
    Files.writeString(path.resolve("cpu.max"), limit);
  }

  @Override
  public long getCpus() throws IOException {
    try (Scanner scanner = new Scanner(Files.newBufferedReader(path.resolve("cpu.max")))) {
      if (!scanner.hasNextLong()) {
        // Not a number, assume no limit and all processors available
        return Runtime.getRuntime().availableProcessors();
      }
      long quota = scanner.nextLong();
      long period = scanner.nextLong();
      return quota / period;
    }
  }
}
