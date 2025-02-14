// Copyright 2019 The Bazel Authors. All rights reserved.
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

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.analysis.platform.PlatformProviderUtils;
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.server.FailureDetails.Toolchain.Code;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.ConfiguredValueCreationException;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;
import javax.annotation.Nullable;

/** Helper class that looks up {@link ToolchainTypeInfo} data. */
public class ToolchainTypeLookupUtil {

  @Nullable
  public static ImmutableMap<Label, ToolchainTypeInfo> resolveToolchainTypes(
      Environment env,
      ImmutableSet<ToolchainTypeRequirement> toolchainTypes,
      BuildConfigurationValue configuration)
      throws InterruptedException, InvalidToolchainTypeException {

    ImmutableSet<ConfiguredTargetKey> toolchainTypeKeys =
        toolchainTypes.stream()
            .map(ToolchainTypeRequirement::toolchainType)
            .map(
                label ->
                    ConfiguredTargetKey.builder()
                        .setLabel(label)
                        .setConfiguration(configuration)
                        .build())
            .collect(toImmutableSet());

    SkyframeLookupResult values = env.getValuesAndExceptions(toolchainTypeKeys);
    boolean valuesMissing = env.valuesMissing();
    SequencedMap<Label, ToolchainTypeInfo> results = valuesMissing ? null : new LinkedHashMap<>();
    for (ConfiguredTargetKey key : toolchainTypeKeys) {
      Label originalLabel = key.getLabel();
      ToolchainTypeInfo toolchainTypeInfo = findToolchainTypeInfo(key, values);
      if (!valuesMissing && toolchainTypeInfo != null) {
        // These are only different if the toolchain type was aliased.
        results.put(originalLabel, toolchainTypeInfo);
        results.put(toolchainTypeInfo.typeLabel(), toolchainTypeInfo);
      }
    }
    if (valuesMissing) {
      return null;
    }

    return ImmutableMap.copyOf(results);
  }

  @Nullable
  private static ToolchainTypeInfo findToolchainTypeInfo(
      ConfiguredTargetKey key, SkyframeLookupResult values) throws InvalidToolchainTypeException {
    try {
      ConfiguredTargetValue ctv =
          (ConfiguredTargetValue)
              values.getOrThrow(
                  key,
                  ConfiguredValueCreationException.class,
                  NoSuchThingException.class,
                  ActionConflictException.class);
      if (ctv == null) {
        return null;
      }

      ConfiguredTarget configuredTarget = ctv.getConfiguredTarget();
      ToolchainTypeInfo toolchainTypeInfo = PlatformProviderUtils.toolchainType(configuredTarget);
      if (toolchainTypeInfo == null) {
        if (PlatformProviderUtils.declaredToolchainInfo(configuredTarget) != null) {
          throw new InvalidToolchainTypeException(
              configuredTarget.getLabel(),
              "is a toolchain instance. Is the rule definition for the target you're building "
                  + "setting \"toolchains =\" to a toolchain() instead of the expected "
                  + "toolchain_type()?");
        }
        throw new InvalidToolchainTypeException(configuredTarget.getLabel());
      }

      return toolchainTypeInfo;
    } catch (ConfiguredValueCreationException e) {
      throw new InvalidToolchainTypeException(e);
    } catch (NoSuchThingException e) {
      throw new InvalidToolchainTypeException(e);
    } catch (ActionConflictException e) {
      throw new InvalidToolchainTypeException(key.getLabel(), e);
    }
  }

  /** Exception used when a toolchain type label is not a valid toolchain type. */
  public static final class InvalidToolchainTypeException extends ToolchainException {
    private static final String DEFAULT_ERROR = "does not provide ToolchainTypeInfo";

    InvalidToolchainTypeException(Label label) {
      super(formatError(label, DEFAULT_ERROR));
    }

    InvalidToolchainTypeException(ConfiguredValueCreationException e) {
      // Just propagate the inner exception, because it's directly actionable.
      super(e);
    }

    public InvalidToolchainTypeException(NoSuchThingException e) {
      // Just propagate the inner exception, because it's directly actionable.
      super(e);
    }

    public InvalidToolchainTypeException(Label label, ActionConflictException e) {
      super(formatError(label, DEFAULT_ERROR), e);
    }

    InvalidToolchainTypeException(Label label, String error) {
      super(formatError(label, error));
    }

    @Override
    protected Code getDetailedCode() {
      return Code.INVALID_TOOLCHAIN_TYPE;
    }

    private static String formatError(Label label, String error) {
      return String.format("Target %s was referenced as a toolchain type, but %s", label, error);
    }
  }
}
