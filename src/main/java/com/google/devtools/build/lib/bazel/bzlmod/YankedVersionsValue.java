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

package com.google.devtools.build.lib.bazel.bzlmod;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Optional;

/** A class holding information about the versions of a particular module that have been yanked. */
@AutoCodec
public record YankedVersionsValue(Optional<ImmutableMap<Version, String>> yankedVersions)
    implements SkyValue {
  public YankedVersionsValue {
    requireNonNull(yankedVersions, "yankedVersions");
  }

  /** A value representing a module without yanked versions. */
  public static final YankedVersionsValue NONE_YANKED = create(Optional.of(ImmutableMap.of()));

  public static YankedVersionsValue create(Optional<ImmutableMap<Version, String>> yankedVersions) {
    return new YankedVersionsValue(yankedVersions);
  }

  /** The key for {@link YankedVersionsFunction}. */
  @AutoCodec
  record Key(String moduleName, String registryUrl) implements SkyKey {
    Key {
      requireNonNull(moduleName, "moduleName");
      requireNonNull(registryUrl, "registryUrl");
    }

    private static final SkyKeyInterner<Key> interner = SkyKey.newInterner();

    @AutoCodec.Instantiator
    static Key create(String moduleName, String registryUrl) {
      return interner.intern(new Key(moduleName, registryUrl));
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.YANKED_VERSIONS;
    }

    @Override
    public SkyKeyInterner<Key> getSkyKeyInterner() {
      return interner;
    }
  }
}
