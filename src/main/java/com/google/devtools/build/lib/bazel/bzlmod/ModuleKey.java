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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import java.util.Comparator;

/** A module name, version pair that identifies a module in the external dependency graph. */
@AutoValue
public abstract class ModuleKey {

  /**
   * A mapping from module name to repository name for certain special "well-known" modules.
   *
   * <p>The repository name of certain modules are required to be exact strings (instead of the
   * normal format seen in {@link #getCanonicalRepoName()}) due to backwards compatibility reasons.
   * For example, bazel_tools must be known as "@bazel_tools" for WORKSPACE repos to work correctly.
   *
   * <p>TODO(wyv): After we make all flag values go through repo mapping, we can remove the concept
   * of well-known modules altogether.
   */
  private static final ImmutableMap<String, RepositoryName> WELL_KNOWN_MODULES =
      ImmutableMap.of(
          "bazel_tools",
          RepositoryName.BAZEL_TOOLS);

  public static final ModuleKey ROOT = create("", Version.EMPTY);

  public static final Comparator<ModuleKey> LEXICOGRAPHIC_COMPARATOR =
      Comparator.comparing(ModuleKey::getName).thenComparing(ModuleKey::getVersion);

  public static ModuleKey create(String name, Version version) {
    return new AutoValue_ModuleKey(name, version);
  }

  /** The name of the module. Must be empty for the root module. */
  public abstract String getName();

  /** The version of the module. Must be empty iff the module has a {@link NonRegistryOverride}. */
  public abstract Version getVersion();

  @Override
  public final String toString() {
    if (this.equals(ROOT)) {
      return "<root>";
    }
    return getName() + "@" + (getVersion().isEmpty() ? "_" : getVersion().toString());
  }

  /** Returns the canonical name of the repo backing this module. */
  public RepositoryName getCanonicalRepoName() {
    if (WELL_KNOWN_MODULES.containsKey(getName())) {
      return WELL_KNOWN_MODULES.get(getName());
    }
    if (ROOT.equals(this)) {
      return RepositoryName.MAIN;
    }
    return RepositoryName.createUnvalidated(
        String.format("%s~%s", getName(), getVersion().isEmpty() ? "override" : getVersion()));
  }
}
