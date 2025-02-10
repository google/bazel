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

package com.google.devtools.build.lib.skyframe.config;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;

/**
 * {@link SkyKey} for {@link com.google.devtools.build.lib.analysis.config.BuildConfigurationValue}.
 */
@AutoCodec
public final class BuildConfigurationKey implements SkyKey {

  private static final SkyKeyInterner<BuildConfigurationKey> interner = SkyKey.newInterner();

  /**
   * Returns the key for a requested configuration.
   *
   * @param options the {@link BuildOptions} object the {@link BuildOptions} should be rebuilt from
   */
  @AutoCodec.Instantiator
  public static BuildConfigurationKey create(BuildOptions options) {
    return interner.intern(new BuildConfigurationKey(options));
  }

  private final BuildOptions options;

  private BuildConfigurationKey(BuildOptions options) {
    this.options = Preconditions.checkNotNull(options);
  }

  public BuildOptions getOptions() {
    return options;
  }

  @Override
  public SkyFunctionName functionName() {
    return SkyFunctions.BUILD_CONFIGURATION;
  }

  public String getOptionsChecksum() {
    return options.checksum();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BuildConfigurationKey otherConfig)) {
      return false;
    }
    return options.equals(otherConfig.options);
  }

  @Override
  public int hashCode() {
    return options.hashCode();
  }

  @Override
  public String toString() {
    // This format is depended on by integration tests.
    return "BuildConfigurationKey[" + options.checksum() + "]";
  }

  @Override
  public SkyKeyInterner<BuildConfigurationKey> getSkyKeyInterner() {
    return interner;
  }

  public static DeferredObjectCodec<BuildConfigurationKey> codec() {
    return CodecHolder.INSTANCE.codec;
  }

  /** Enum pattern for avoiding cyclic class loading deadlocks. */
  private enum CodecHolder {
    INSTANCE;

    @SuppressWarnings("ImmutableEnumChecker") // it's immutable
    private final DeferredObjectCodec<BuildConfigurationKey> codec;

    private CodecHolder() {
      Class<?> codecClass;
      try {
        codecClass =
            Class.forName(
                "com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey_AutoCodec");
      } catch (ClassNotFoundException e) {
        // Okay, the codec class doesn't exist in the bootstrap jar file.
        this.codec = null;
        return;
      }
      try {
        @SuppressWarnings("unchecked")
        var castCodec =
            (DeferredObjectCodec<BuildConfigurationKey>)
                codecClass.getDeclaredConstructor().newInstance();
        this.codec = castCodec;
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("couldn't instantiate BuildConfigurationKey_AutoCodec", e);
      }
    }
  }
}
