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
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Optional;

/** The result of {@link ModuleFileFunction}. */
public interface ModuleFileValue extends SkyValue {

  ModuleFileValue.Key KEY_FOR_ROOT_MODULE = key(ModuleKey.ROOT);

  /**
   * The module resulting from the module file evaluation. Note that the name and version of this
   * module might not match the one in the requesting {@link SkyKey} in certain circumstances (for
   * example, for the root module, or when non-registry overrides are in play.
   */
  InterimModule module();

  /**
   * Hashes of files obtained (or known to be missing) from registries while obtaining this module
   * file.
   */
  ImmutableMap<String, Optional<Checksum>> registryFileHashes();

  /** The {@link ModuleFileValue} for non-root modules. */
  @AutoCodec
  record NonRootModuleFileValue(
      InterimModule module, ImmutableMap<String, Optional<Checksum>> registryFileHashes)
      implements ModuleFileValue {}

  /**
   * The {@link ModuleFileValue} for the root module, containing additional information about
   * overrides.
   *
   * @param overrides The overrides specified by the evaluated module file. The key is the module
   *     name and the value is the override itself.
   * @param nonRegistryOverrideCanonicalRepoNameLookup A mapping from a canonical repo name to the
   *     name of the module. Only works for modules with non-registry overrides.
   * @param moduleFilePaths The set of relative paths to the root MODULE.bazel file itself and all
   *     its transitive includes.
   */
  @AutoCodec
  record RootModuleFileValue(
      InterimModule module,
      ImmutableMap<String, ModuleOverride> overrides,
      ImmutableMap<RepositoryName, String> nonRegistryOverrideCanonicalRepoNameLookup,
      ImmutableSet<PathFragment> moduleFilePaths)
      implements ModuleFileValue {
    @Override
    public ImmutableMap<String, Optional<Checksum>> registryFileHashes() {
      // The root module is not obtained from a registry.
      return ImmutableMap.of();
    }
  }

  static Key key(ModuleKey moduleKey) {
    return Key.create(moduleKey);
  }

  /** {@link SkyKey} for {@link ModuleFileValue} computation. */
  @AutoCodec
  record Key(ModuleKey moduleKey) implements SkyKey {
    private static final SkyKeyInterner<Key> interner = SkyKey.newInterner();

    @AutoCodec.Instantiator
    static Key create(ModuleKey moduleKey) {
      return interner.intern(new Key(moduleKey));
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.MODULE_FILE;
    }

    @Override
    public SkyKeyInterner<Key> getSkyKeyInterner() {
      return interner;
    }
  }
}
