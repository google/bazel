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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformProviderUtils;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.server.FailureDetails.Toolchain.Code;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.ConfiguredValueCreationException;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/** Helper class that looks up {@link ConstraintValueInfo} data. */
public class ConstraintValueLookupUtil {

  @Nullable
  public static List<ConstraintValueInfo> getConstraintValueInfo(
      Iterable<ConfiguredTargetKey> constraintValueKeys, Environment env)
      throws InterruptedException, InvalidConstraintValueException {

    SkyframeLookupResult values = env.getValuesAndExceptions(constraintValueKeys);
    boolean valuesMissing = env.valuesMissing();
    List<ConstraintValueInfo> constraintValues = valuesMissing ? null : new ArrayList<>();
    for (ConfiguredTargetKey key : constraintValueKeys) {
      var constraintValueInfo = findConstraintValueInfo(key, values);
      if (!valuesMissing && constraintValueInfo != null) {
        constraintValues.addAll(constraintValueInfo);
      }
    }
    if (valuesMissing) {
      return null;
    }
    return constraintValues;
  }

  /**
   * Returns the {@link ConstraintValueInfo} provider from the {@link ConfiguredTarget} in the
   * {@link SkyframeLookupResult}, or {@code null} if the {@link ConfiguredTarget} is not present.
   * If the {@link ConfiguredTarget} does not have a {@link ConstraintValueInfo} provider, a {@link
   * InvalidConstraintValueException} is thrown.
   */
  @Nullable
  private static ImmutableSet<ConstraintValueInfo> findConstraintValueInfo(
      ConfiguredTargetKey key, SkyframeLookupResult values) throws InvalidConstraintValueException {
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

      var configuredTarget = ctv.getConfiguredTarget();

      var constraintValueInfo = PlatformProviderUtils.constraintValue(configuredTarget);
      if (constraintValueInfo != null) {
        return ImmutableSet.of(constraintValueInfo);
      }

      var platformInfo = PlatformProviderUtils.platform(configuredTarget);
      if (platformInfo != null) {
        var constraints = platformInfo.constraints();
        return ImmutableSet.copyOf(Iterables.transform(constraints.transitiveConstraintSettings(), constraints::get));
      }

      throw new InvalidConstraintValueException(configuredTarget.getLabel());
    } catch (ConfiguredValueCreationException e) {
      throw new InvalidConstraintValueException(key.getLabel(), e);
    } catch (NoSuchThingException e) {
      throw new InvalidConstraintValueException(key.getLabel(), e);
    } catch (ActionConflictException e) {
      throw new InvalidConstraintValueException(key.getLabel(), e);
    }
  }

  /** Exception used when a constraint value label is not a valid constraint value. */
  public static final class InvalidConstraintValueException extends ToolchainException {
    InvalidConstraintValueException(Label label) {
      super(formatError(label));
    }

    InvalidConstraintValueException(Label label, ConfiguredValueCreationException e) {
      super(formatError(label), e);
    }

    public InvalidConstraintValueException(Label label, NoSuchThingException e) {
      // Just propagate the inner exception, because it's directly actionable.
      super(e);
    }

    public InvalidConstraintValueException(Label label, ActionConflictException e) {
      super(formatError(label), e);
    }

    @Override
    protected Code getDetailedCode() {
      return Code.INVALID_CONSTRAINT_VALUE;
    }

    private static String formatError(Label label) {
      return String.format(
          "Target %s was referenced as a constraint_value, "
              + "but does not provide ConstraintValueInfo",
          label);
    }
  }
}
