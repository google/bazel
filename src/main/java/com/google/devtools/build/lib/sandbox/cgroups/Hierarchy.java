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

package com.google.devtools.build.lib.sandbox.cgroups;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Represents a cgroup hierarchy of a process from `/proc/self/cgroup`. */
public record Hierarchy(int id, ImmutableList<String> controllers, Path path) {
  public Hierarchy {
    requireNonNull(controllers, "controllers");
    requireNonNull(path, "path");
  }

  public boolean isV2() {
    return controllers().size() == 1 && controllers().contains("") && id() == 0;
  }

  /**
   * A regexp that matches entries in {@code /proc/self/cgroup}.
   *
   * <p>The format is documented in https://man7.org/linux/man-pages/man7/cgroups.7.html
   */
  private static final Pattern PROC_CGROUPS_PATTERN =
      Pattern.compile("^(?<id>\\d+):(?<controllers>[^:]*):(?<file>.+)");

  static Hierarchy create(int id, ImmutableList<String> controllers, Path path) {
    return new Hierarchy(id, controllers, path);
  }

  static ImmutableList<Hierarchy> parse(File procCgroup) throws IOException {
    ImmutableList.Builder<Hierarchy> hierarchies = ImmutableList.builder();
    for (String line : Files.readLines(procCgroup, StandardCharsets.UTF_8)) {
      Matcher m = PROC_CGROUPS_PATTERN.matcher(line);
      if (!m.matches()) {
        continue;
      }

      Integer id = Integer.parseInt(m.group("id"));
      String path = m.group("file");
      ImmutableList<String> controllers = ImmutableList.copyOf(m.group("controllers").split(","));
      hierarchies.add(Hierarchy.create(id, controllers, Paths.get(path)));
    }
    return hierarchies.build();
  }
}
