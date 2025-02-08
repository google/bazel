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

/**
 * Represents a mounted cgroup pseudo-filesystem.
 *
 * @param opts Mount point options for this mount. In the context cgroups, this will contain the
 *     controllers that are mounted at this mount point.
 */
public record Mount(Path path, String type, ImmutableList<String> opts) {
  public Mount {
    requireNonNull(path, "path");
    requireNonNull(type, "type");
    requireNonNull(opts, "opts");
  }

  /**
   * A regexp that matches cgroups entries in {@code /proc/mounts}.
   *
   * <p>The format is documented in https://man7.org/linux/man-pages/man5/fstab.5.html
   */
  private static final Pattern CGROUPS_MOUNT_PATTERN =
      Pattern.compile("^[^\\s#]\\S*\\s+(?<file>\\S*)\\s+(?<vfstype>cgroup2?)\\s+(?<mntops>\\S*).*");

  public boolean isV2() {
    return type().equals("cgroup2");
  }

  static Mount create(Path path, String type, ImmutableList<String> opts) {
    return new Mount(path, type, opts);
  }

  /**
   * Parses the cgroup mounts from the provided file and returns a list of mounts.
   *
   * @param procMounts a file containing the cgroup mounts, typically {@code /proc/mounts}.
   * @return The list of cgroup mounts in the file.
   */
  static ImmutableList<Mount> parse(File procMounts) throws IOException {
    ImmutableList.Builder<Mount> mounts = ImmutableList.builder();

    for (String mount : Files.readLines(procMounts, StandardCharsets.UTF_8)) {
      Matcher m = CGROUPS_MOUNT_PATTERN.matcher(mount);
      if (!m.matches()) {
        continue;
      }

      String path = m.group("file");
      String type = m.group("vfstype");
      ImmutableList<String> opts = ImmutableList.copyOf(m.group("mntops").split(","));
      mounts.add(Mount.create(Paths.get(path), type, opts));
    }
    return mounts.build();
  }
}
