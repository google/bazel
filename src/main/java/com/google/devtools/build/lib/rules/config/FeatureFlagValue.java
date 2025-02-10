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
// limitations under the License

package com.google.devtools.build.lib.rules.config;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Marker interface for detecting feature flags in the Starlark setting map. */
public interface FeatureFlagValue {
  /** A feature flag value for a flag known to be set to a particular value. */
  @AutoCodec
  public record SetValue(String value) implements FeatureFlagValue {
    public SetValue {
      requireNonNull(value, "value");
    }

    static SetValue of(String value) {
      return new SetValue(value);
    }

    @Override
    public final String toString() {
      return String.format("FeatureFlagValue.SetValue{%s}", value());
    }
  }

  /** A feature flag value for a flag known to be set to its default value. */
  enum DefaultValue implements FeatureFlagValue {
    INSTANCE;

    @Override
    public String toString() {
      return "FeatureFlagValue.DefaultValue{}";
    }
  }

  /** A feature flag value for a flag which was requested but which value was already trimmed. */
  enum UnknownValue implements FeatureFlagValue {
    INSTANCE;

    @Override
    public String toString() {
      return "FeatureFlagValue.UnknownValue{}";
    }
  }

  /** Returns a new BuildOptions with a new map of feature flag values. */
  static BuildOptions replaceFlagValues(BuildOptions original, Map<Label, String> newValues) {
    BuildOptions.Builder result = original.toBuilder();
    for (Map.Entry<Label, Object> entry : original.getStarlarkOptions().entrySet()) {
      if (entry.getValue() instanceof FeatureFlagValue) {
        result.removeStarlarkOption(entry.getKey());
      }
    }
    ImmutableMap.Builder<Label, Object> newValueObjects = new ImmutableMap.Builder<>();
    for (Map.Entry<Label, String> entry : newValues.entrySet()) {
      newValueObjects.put(entry.getKey(), SetValue.of(entry.getValue()));
    }
    result.addStarlarkOptions(newValueObjects.buildOrThrow());
    BuildOptions builtResult = result.build();
    var configFeatureFlagOptions = builtResult.get(ConfigFeatureFlagOptions.class);
    if (configFeatureFlagOptions != null) {
      configFeatureFlagOptions.allFeatureFlagValuesArePresent = true;
    }
    return builtResult;
  }

  /** Returns a new BuildOptions with the feature flag values trimmed down to the given flags. */
  static BuildOptions trimFlagValues(BuildOptions original, Set<Label> availableFlags) {
    // An important performance property of this method is that we don't create a new BuildOptions
    // instance unless we really need one. This particularly saves the expensive cost of
    // BuildOptions.hashCode(). Since this method is called unconditionally over every configured
    // target, this has real observable effect on build analysis time.
    Set<Label> seenFlags = new LinkedHashSet<>();
    Set<Label> flagsToTrim = new LinkedHashSet<>();
    Map<Label, Object> unknownFlagsToAdd = new LinkedHashMap<>();
    var originalConfigFeatureFlagOptions = original.get(ConfigFeatureFlagOptions.class);
    boolean changeAllValuesPresentOption =
        originalConfigFeatureFlagOptions != null
            && originalConfigFeatureFlagOptions.allFeatureFlagValuesArePresent;

    // What do we need to change?
    original.getStarlarkOptions().entrySet().stream()
        .filter(entry -> entry.getValue() instanceof FeatureFlagValue)
        .forEach(featureFlagEntry -> seenFlags.add(featureFlagEntry.getKey()));
    flagsToTrim.addAll(Sets.difference(seenFlags, availableFlags));
    FeatureFlagValue unknownFlagValue =
        changeAllValuesPresentOption ? DefaultValue.INSTANCE : UnknownValue.INSTANCE;
    for (Label unknownFlag : Sets.difference(availableFlags, seenFlags)) {
      unknownFlagsToAdd.put(unknownFlag, unknownFlagValue);
    }

    // Nothing changed? Return the original BuildOptions.
    if (flagsToTrim.isEmpty() && unknownFlagsToAdd.isEmpty() && !changeAllValuesPresentOption) {
      return original;
    }

    // Else construct a new one. This should not be the common case.
    BuildOptions.Builder result = original.toBuilder();
    for (Label trimmedFlag : flagsToTrim) {
      result.removeStarlarkOption(trimmedFlag);
    }
    unknownFlagsToAdd.forEach((flag, value) -> result.addStarlarkOption(flag, value));
    BuildOptions builtResult = result.build();
    var builtConfigFeatureFlagOptions = builtResult.get(ConfigFeatureFlagOptions.class);
    if (builtConfigFeatureFlagOptions != null) {
      builtConfigFeatureFlagOptions.allFeatureFlagValuesArePresent = false;
    }
    return builtResult;
  }
}
