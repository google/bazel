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

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;

/**
 * An override specifying that the module should not be retrieved from a registry or participate in
 * version resolution, and should come from a custom repo rule instantiation instead. To evaluate
 * the module file of such modules, we need to first fetch the entire module contents and find the
 * module file in the root of the module.
 */
@AutoCodec
public record NonRegistryOverride(RepoSpec repoSpec) implements ModuleOverride {
  // Starlark rules loaded from bazel_tools that may define Bazel module repositories with
  // non-registry overrides and thus must be loaded without relying on any other modules or the main
  // repo mapping.
  public static final ImmutableSet<RepoRuleId> BOOTSTRAP_REPO_RULES =
      ImmutableSet.of(ArchiveRepoSpecBuilder.HTTP_ARCHIVE, GitRepoSpecBuilder.GIT_REPOSITORY);
}
