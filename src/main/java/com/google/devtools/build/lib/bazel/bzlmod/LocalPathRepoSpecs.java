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

import com.google.common.collect.ImmutableMap;

/** A utility class to create {@link RepoSpec}s for {@code local_repository}. */
public final class LocalPathRepoSpecs {
  private LocalPathRepoSpecs() {}

  // TODO: wyv@ - switch to Starlark local_repository. And maybe add support for
  //   new_local_repository?
  public static final RepoRuleId LOCAL_REPOSITORY = new RepoRuleId(null, "local_repository");

  public static RepoSpec create(String path) {
    return new RepoSpec(LOCAL_REPOSITORY, AttributeValues.create(ImmutableMap.of("path", path)));
  }
}
