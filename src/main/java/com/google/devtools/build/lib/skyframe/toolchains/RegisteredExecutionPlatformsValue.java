// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.skyframe.toolchains;

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A value which represents every execution platform known to Bazel and available to run actions.
 *
 * @param rejectedPlatforms Any execution platforms that were rejected, along with a reason. The
 *     keys are the platform label, and the value is the rejection reason. Only non-null if {@link
 *     RegisteredExecutionPlatformsValue.Key#debug} is {@code true}.
 */
@AutoCodec
public record RegisteredExecutionPlatformsValue(
    ImmutableList<ConfiguredTargetKey> registeredExecutionPlatformKeys,
    @Nullable ImmutableMap<Label, String> rejectedPlatforms)
    implements SkyValue {
  public RegisteredExecutionPlatformsValue {
    requireNonNull(registeredExecutionPlatformKeys, "registeredExecutionPlatformKeys");
  }

  /** Returns the {@link SkyKey} for {@link RegisteredExecutionPlatformsValue}s. */
  public static SkyKey key(BuildConfigurationKey configurationKey, boolean debug) {
    return Key.of(configurationKey, debug);
  }

  /** {@link SkyKey} implementation used for {@link RegisteredExecutionPlatformsFunction}. */
  @AutoCodec
  @VisibleForSerialization
  static class Key implements SkyKey {
    private static final SkyKeyInterner<Key> interner = SkyKey.newInterner();

    private final BuildConfigurationKey configurationKey;
    private final boolean debug;

    private Key(BuildConfigurationKey configurationKey, boolean debug) {
      this.configurationKey = configurationKey;
      this.debug = debug;
    }

    private static Key of(BuildConfigurationKey configurationKey, boolean debug) {
      return interner.intern(new Key(configurationKey, debug));
    }

    @VisibleForSerialization
    @AutoCodec.Interner
    static Key intern(Key key) {
      return interner.intern(key);
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.REGISTERED_EXECUTION_PLATFORMS;
    }

    BuildConfigurationKey configurationKey() {
      return configurationKey;
    }

    boolean debug() {
      return debug;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof Key that)) {
        return false;
      }
      return Objects.equals(this.configurationKey, that.configurationKey)
          && this.debug == that.debug;
    }

    @Override
    public int hashCode() {
      return Objects.hash(configurationKey, debug);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper("RegisteredExecutionPlatformsValue.Key")
          .add("configurationKey", configurationKey())
          .add("debug", debug())
          .toString();
    }

    @Override
    public final SkyKeyInterner<Key> getSkyKeyInterner() {
      return interner;
    }
  }

  static RegisteredExecutionPlatformsValue create(
      ImmutableList<ConfiguredTargetKey> registeredExecutionPlatformKeys,
      ImmutableMap<Label, String> rejectedPlatforms) {
    return new RegisteredExecutionPlatformsValue(
        registeredExecutionPlatformKeys, rejectedPlatforms);
  }
}
