// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableTable;
import com.google.devtools.build.lib.analysis.platform.DeclaredToolchainInfo;
import com.google.devtools.build.lib.cmdline.Label;
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
 * A value which represents every toolchain known to Bazel and available for toolchain resolution.
 *
 * @param rejectedToolchains Any toolchains that were rejected, along with a reason. The row keys
 *     are the toolchain type labels, column keys are toolchain target (not implementation) labels,
 *     and cells are the reason. Only non-null if {@link RegisteredToolchainsValue.Key#debug} is
 *     {@code true}.
 */
@AutoCodec
public record RegisteredToolchainsValue(
    ImmutableList<DeclaredToolchainInfo> registeredToolchains,
    @Nullable ImmutableTable<Label, Label, String> rejectedToolchains)
    implements SkyValue {
  public RegisteredToolchainsValue {
    requireNonNull(registeredToolchains, "registeredToolchains");
  }

  /** Returns the {@link SkyKey} for {@link RegisteredToolchainsValue}s. */
  public static Key key(BuildConfigurationKey configurationKey, boolean debug) {
    return Key.of(configurationKey, debug);
  }

  /** A {@link SkyKey} for {@code RegisteredToolchainsValue}. */
  @AutoCodec
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
      return SkyFunctions.REGISTERED_TOOLCHAINS;
    }

    BuildConfigurationKey getConfigurationKey() {
      return configurationKey;
    }

    boolean debug() {
      return debug;
    }

    @Override
    public String toString() {
      return "RegisteredToolchainsValue.Key{"
          + "configurationKey: "
          + configurationKey
          + ", debug: "
          + debug
          + "}";
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
    public final SkyKeyInterner<Key> getSkyKeyInterner() {
      return interner;
    }
  }

  public static RegisteredToolchainsValue create(
      ImmutableList<DeclaredToolchainInfo> registeredToolchains,
      @Nullable ImmutableTable<Label, Label, String> rejectedToolchains) {
    return new RegisteredToolchainsValue(registeredToolchains, rejectedToolchains);
  }

}
