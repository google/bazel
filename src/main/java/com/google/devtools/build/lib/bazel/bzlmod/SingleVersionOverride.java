// Copyright 2021 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;

/**
 * Specifies that the module should:
 *
 * <ul>
 *   <li>be pinned to a single version,
 *   <li>and/or come from a specific registry (instead of the default list),
 *   <li>and/or use some local patches.
 * </ul>
 *
 * @param version The version to pin the module to. Can be empty if it shouldn't be pinned (in which
 *     case it will still participate in version resolution).
 * @param patches The labels of patches to apply after retrieving per the registry.
 * @param patchCmds The patch commands to execute after retrieving per the registry. Should be a
 *     list of commands.
 * @param patchStrip The number of path segments to strip from the paths in the supplied patches.
 */
@AutoCodec
public record SingleVersionOverride(
    Version version,
    @Override String registry,
    ImmutableList<Label> patches,
    ImmutableList<String> patchCmds,
    int patchStrip)
    implements RegistryOverride {
  public SingleVersionOverride {
    requireNonNull(version, "version");
    requireNonNull(registry, "registry");
    requireNonNull(patches, "patches");
    requireNonNull(patchCmds, "patchCmds");
  }

  @Override
  public String getRegistry() {
    return registry();
  }

  public static SingleVersionOverride create(
      Version version,
      String registry,
      ImmutableList<Label> patches,
      ImmutableList<String> patchCmds,
      int patchStrip) {
    return new SingleVersionOverride(version, registry, patches, patchCmds, patchStrip);
  }

}
