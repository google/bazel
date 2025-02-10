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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.GoogleLogger;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/** A factory for creating {@link VirtualCgroup} instances. */
public class VirtualCgroupFactory {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final String name;
  private final ImmutableMap<String, Double> defaultLimits;
  private final VirtualCgroup root;
  private final ConcurrentHashMap<Integer, VirtualCgroup> cgroups;
  private final boolean alwaysCreate;

  /** A factory that always returns {@link VirtualCgroup#NULL}. */
  public static final VirtualCgroupFactory NOOP =
      new VirtualCgroupFactory("noop_", VirtualCgroup.NULL, ImmutableMap.of(), false);

  public VirtualCgroupFactory(
      String name,
      VirtualCgroup root,
      ImmutableMap<String, Double> defaultLimits,
      boolean alwaysCreate) {
    this.name = Preconditions.checkNotNull(name);
    this.defaultLimits = Preconditions.checkNotNull(defaultLimits);
    this.root = Preconditions.checkNotNull(root);
    this.alwaysCreate = alwaysCreate;
    this.cgroups = new ConcurrentHashMap<>();
  }

  /**
   * Creates a new cgroup with the specified limits.
   *
   * @param id the id of the cgroup
   * @param limits the limits to be set on this cgroup
   * @return a new cgroup
   */
  public VirtualCgroup create(Integer id, ImmutableMap<String, Double> limits) throws IOException {
    if (!alwaysCreate && defaultLimits.isEmpty() && limits.isEmpty()) {
      return VirtualCgroup.NULL;
    }

    Double cpuLimit = limits.getOrDefault("cpu", defaultLimits.getOrDefault("cpu", 0.0));
    long memoryLimit =
        (long)
            (limits.getOrDefault("memory", defaultLimits.getOrDefault("memory", 0.0))
                * 1024
                * 1024);

    if (!alwaysCreate && cpuLimit == 0 && memoryLimit == 0) {
      return VirtualCgroup.NULL;
    }

    String childName = this.name + id + ".scope";
    VirtualCgroup cgroup = root.createChild(childName);
    logger.atInfo().log("Created child cgroup %s", childName);
    cgroups.put(id, cgroup);
    if (memoryLimit > 0 && cgroup.memory() != null) {
      cgroup.memory().setMaxBytes(memoryLimit);
      if (cgroup.memory().getMaxBytes() == memoryLimit) {
        logger.atInfo().log(
            "Successfully set cgroup memory limit for %s to %d", childName, memoryLimit);
      } else {
        logger.atWarning().log(
            "Failed to set cgroup memory limit for %s to %d", childName, memoryLimit);
      }
    }
    if (cpuLimit > 0 && cgroup.cpu() != null) {
      cgroup.cpu().setCpus(cpuLimit);
      if (cgroup.cpu().getCpus() == cpuLimit) {
        logger.atInfo().log("Successfully set cgroup cpu limit for %s to %f", childName, cpuLimit);
      } else {
        logger.atWarning().log("Failed to set cgroup cpu limit for %s to %f", childName, cpuLimit);
      }
    }

    return cgroup;
  }

  /** Returns the cgroup with the given id. */
  public VirtualCgroup get(Integer id) {
    return cgroups.get(id);
  }

  /** Removes the cgroup with the given id. */
  @CanIgnoreReturnValue
  public VirtualCgroup remove(Integer id) {
    VirtualCgroup cgroup = cgroups.remove(id);
    if (cgroup != null) {
      cgroup.destroy();
    }
    return cgroup;
  }
}
