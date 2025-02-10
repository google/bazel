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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.common.io.CharSink;
import com.google.common.io.Files;
import com.google.devtools.build.lib.sandbox.Cgroup;
import com.google.devtools.build.lib.sandbox.cgroups.controller.Controller;
import com.google.devtools.build.lib.sandbox.cgroups.controller.v1.LegacyCpu;
import com.google.devtools.build.lib.sandbox.cgroups.controller.v1.LegacyMemory;
import com.google.devtools.build.lib.sandbox.cgroups.controller.v2.UnifiedCpu;
import com.google.devtools.build.lib.sandbox.cgroups.controller.v2.UnifiedMemory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * This class creates and exposes a virtual cgroup for the Bazel process and allows creating child
 * cgroups. Resources are exposed as {@link Controller}s, each representing a subsystem within the
 * virtual cgroup and that could in theory belong to different real cgroups.
 */
@AutoValue
public abstract class VirtualCgroup implements Cgroup {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private static final File PROC_SELF_MOUNTS_PATH = new File("/proc/self/mounts");
  private static final File PROC_SELF_CGROUP_PATH = new File("/proc/self/cgroup");

  private static final Supplier<VirtualCgroup> instanceSupplier =
      Suppliers.memoize(VirtualCgroup::createInstance);

  @Nullable
  public abstract Controller.Cpu cpu();

  @Nullable
  public abstract Controller.Memory memory();

  @Override
  public abstract ImmutableSet<Path> paths();

  private final Queue<VirtualCgroup> children = new ConcurrentLinkedQueue<>();

  public static VirtualCgroup getInstance() {
    return instanceSupplier.get();
  }

  private static VirtualCgroup createInstance() {
    VirtualCgroup root;
    try {
      root = createRoot().createChild("blaze_" + ProcessHandle.current().pid() + "_spawns.slice");
    } catch (IOException e) {
      logger.atInfo().withCause(e).log("Failed to create root cgroup");
      root = NULL;
    }
    Runtime.getRuntime().addShutdownHook(new Thread(VirtualCgroup::deleteInstance));
    return root;
  }

  public static void deleteInstance() {
    getInstance().destroy();
  }

  public static final VirtualCgroup NULL =
      new AutoValue_VirtualCgroup(null, null, ImmutableSet.of());

  static VirtualCgroup createRoot() throws IOException {
    return createRoot(PROC_SELF_MOUNTS_PATH, PROC_SELF_CGROUP_PATH);
  }

  static VirtualCgroup createRoot(File procMounts, File procCgroup) throws IOException {
    return createRoot(Mount.parse(procMounts), Hierarchy.parse(procCgroup));
  }

  static VirtualCgroup createRoot(List<Mount> mounts, ImmutableList<Hierarchy> hierarchies)
      throws IOException {
    return createRoot(
        mounts,
        hierarchies.stream()
            .flatMap(h -> h.controllers().stream().map(c -> Map.entry(c, h)))
            // For cgroup v2, there are no controllers specified in the proc/pid/cgroup file
            // So the keep will be empty and unique. For cgroup v1, there could potentially
            // be multiple mounting points for the same controller, but they represent a
            // "view of the same hierarchy" so it is ok to just keep one.
            // Ref. https://man7.org/linux/man-pages/man7/cgroups.7.html
            .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
  }

  private static VirtualCgroup createRoot(List<Mount> mounts, Map<String, Hierarchy> hierarchies)
      throws IOException {
    Controller.Memory memory = null;
    Controller.Cpu cpu = null;
    ImmutableSet.Builder<Path> paths = ImmutableSet.builder();

    for (Mount m : mounts) {
      if (memory != null && cpu != null) {
        break;
      }

      if (m.isV2()) {
        Hierarchy h = hierarchies.get("");
        if (h == null) {
          continue;
        }
        Path cgroup = m.path().resolve(Path.of("/").relativize(h.path()));
        logger.atInfo().log("Found cgroup v2 at %s", cgroup);
        if (!cgroup.equals(m.path())) {
          // Because of the "no internal processes" rule, it is not possible to
          // create a non-empty child cgroups on non-root cgroups with member processes
          // Instead, we go up one level in the hierarchy and declare a sibling.
          cgroup = cgroup.getParent();
          logger.atInfo().log(
              "Due to the no internal processes rule, using cgroup %s instead.", cgroup);
        }
        if (!cgroup.toFile().canWrite()) {
          logger.atInfo().log("Found non-writable cgroup v2 at %s", cgroup);
          continue;
        }
        paths.add(cgroup);

        try (Scanner scanner = new Scanner(cgroup.resolve("cgroup.controllers").toFile(), UTF_8)) {
          while (scanner.hasNext()) {
            switch (scanner.next()) {
              case "memory":
                if (memory != null) {
                  continue;
                }
                logger.atFine().log("Found v2 memory controller at %s", cgroup);
                memory = new UnifiedMemory(cgroup);
                break;
              case "cpu":
                if (cpu != null) {
                  continue;
                }
                logger.atFine().log("Found v2 cpu controller at %s", cgroup);
                cpu = new UnifiedCpu(cgroup);
                break;
              default: // Ignore all other controllers.
            }
          }
        }
      } else {
        for (String opt : m.opts()) {
          Hierarchy h = hierarchies.get(opt);
          if (h == null) {
            continue;
          }
          Path cgroup = m.path().resolve(Path.of("/").relativize(h.path()));
          if (!cgroup.toFile().canWrite()) {
            logger.atInfo().log("Found non-writable cgroup v1 at %s", cgroup);
            continue;
          }
          paths.add(cgroup);

          switch (opt) {
            case "memory":
              if (memory != null) {
                continue;
              }
              logger.atFine().log("Found v1 memory controller at %s", cgroup);
              memory = new LegacyMemory(cgroup);
              break;
            case "cpu":
              if (cpu != null) {
                continue;
              }
              logger.atFine().log("Found v1 cpu controller at %s", cgroup);
              cpu = new LegacyCpu(cgroup);
              break;
            default: // Ignore all other controllers.
          }
        }
      }
    }

    return new AutoValue_VirtualCgroup(cpu, memory, paths.build());
  }

  @VisibleForTesting
  public static VirtualCgroup create(
      Controller.Cpu cpu, Controller.Memory memory, ImmutableSet<Path> paths) {
    return new AutoValue_VirtualCgroup(cpu, memory, paths);
  }

  @Override
  public void destroy() {
    this.children.forEach(VirtualCgroup::destroy);
    this.paths().stream().map(Path::toFile).filter(File::exists).forEach(File::delete);
  }

  public VirtualCgroup createChild(String name) throws IOException {
    Controller.Cpu cpu = null;
    Controller.Memory memory = null;
    ImmutableSet.Builder<Path> paths = ImmutableSet.builder();
    if (memory() != null) {
      memory = memory().child(name);
      paths.add(memory.getPath());
    }
    if (cpu() != null) {
      cpu = cpu().child(name);
      paths.add(cpu.getPath());
    }
    VirtualCgroup child = new AutoValue_VirtualCgroup(cpu, memory, paths.build());
    this.children.add(child);
    return child;
  }

  @Override
  public void addProcess(long pid) throws IOException {
    String pidStr = Long.toString(pid);
    for (Path path : paths()) {
      File procs = path.resolve("cgroup.procs").toFile();
      CharSink sink = Files.asCharSink(procs, StandardCharsets.UTF_8);
      sink.write(pidStr);
    }
  }

  @Override
  public int getMemoryUsageInKb() {
    try {
      return memory() == null ? 0 : (int) (memory().getUsageInBytes() / 1024);
    } catch (IOException e) {
      return 0;
    }
  }

  @Override
  public boolean exists() {
    return memory() != null && memory().exists();
  }
}
