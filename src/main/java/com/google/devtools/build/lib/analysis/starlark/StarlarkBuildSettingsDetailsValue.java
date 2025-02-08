// Copyright 2022 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis.starlark;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.errorprone.annotations.CheckReturnValue;
import java.util.Map;
import java.util.Set;

/**
 * This contains information about a list of given Starlark build options, specifically their
 * defaults and the (final) actual values of alias {@link Label}.
 *
 * <p>For memory-efficiency reasons, aliasToActual contains only aliases in keys. Other attributes
 * contain only actual build setting as keys.
 *
 * <p>Potentially aliased targets can be unaliased with aliasToActual().getWithDefault(raw, raw);
 *
 * @param buildSettingToDefault Map from each build option to its default value. Does not include
 *     aliases.
 * @param buildSettingToType Map from each build option to its type information. Does not include
 *     aliases.
 * @param buildSettingIsAllowsMultiple If build option is in this set, is an allows_multiple option.
 *     Does not include aliases.
 * @param aliasToActual Map from an alias Label to actual Label it points to.
 */
@CheckReturnValue
@Immutable
@ThreadSafe
@AutoCodec
public record StarlarkBuildSettingsDetailsValue(
    ImmutableMap<Label, Object> buildSettingToDefault,
    ImmutableMap<Label, Type<?>> buildSettingToType,
    ImmutableSet<Label> buildSettingIsAllowsMultiple,
    ImmutableMap<Label, Label> aliasToActual)
    implements SkyValue {
  public StarlarkBuildSettingsDetailsValue {
    requireNonNull(buildSettingToDefault, "buildSettingToDefault");
    requireNonNull(buildSettingToType, "buildSettingToType");
    requireNonNull(buildSettingIsAllowsMultiple, "buildSettingIsAllowsMultiple");
    requireNonNull(aliasToActual, "aliasToActual");
  }

  /**
   * Create a single StarlarkBuildSettingsDetailsValue that can be quickly returned for transitions
   * that use no Starlark build settings
   */
  public static final StarlarkBuildSettingsDetailsValue EMPTY =
      create(ImmutableMap.of(), ImmutableMap.of(), ImmutableSet.of(), ImmutableMap.of());

  public static StarlarkBuildSettingsDetailsValue create(
      Map<Label, Object> buildSettingDefaults,
      Map<Label, Type<?>> buildSettingToType,
      Set<Label> buildSettingIsAllowsMultiple,
      Map<Label, Label> aliasToActual) {
    return new StarlarkBuildSettingsDetailsValue(
        ImmutableMap.copyOf(buildSettingDefaults),
        ImmutableMap.copyOf(buildSettingToType),
        ImmutableSet.copyOf(buildSettingIsAllowsMultiple),
        ImmutableMap.copyOf(aliasToActual));
  }

  public static Key key(Set<Label> buildSettings) {
    return Key.create(ImmutableSet.copyOf(buildSettings));
  }

  /** {@link SkyKey} implementation used for {@link StarlarkBuildSettingsDetailsValue}. */
  @CheckReturnValue
  @Immutable
  @ThreadSafe
  @AutoCodec
  public record Key(ImmutableSet<Label> buildSettings) implements SkyKey {
    public Key {
      requireNonNull(buildSettings, "buildSettings");
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.STARLARK_BUILD_SETTINGS_DETAILS;
    }

    static Key create(ImmutableSet<Label> buildSettings) {
      return new Key(buildSettings);
    }
  }
}
