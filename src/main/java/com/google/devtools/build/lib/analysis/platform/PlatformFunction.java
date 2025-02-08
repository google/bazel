// Copyright 2024 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis.platform;

import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.config.CommonOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.Label.PackageContext;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.ConfiguredValueCreationException;
import com.google.devtools.build.lib.skyframe.PackageValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.config.ParsedFlagsValue;
import com.google.devtools.build.lib.skyframe.toolchains.PlatformLookupUtil;
import com.google.devtools.build.lib.skyframe.toolchains.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import javax.annotation.Nullable;

/**
 * Validates that a {@link Label} is a platform, then requests its {@link ConfiguredTargetKey}.
 * Extracts the {@link PlatformInfo} from the analyzed configured target and parses any {@link
 * PlatformInfo#flags()}.
 */
public final class PlatformFunction implements SkyFunction {

  /** Returns the {@link ConfiguredTargetKey} requested when evaluating the given platform. */
  public static ConfiguredTargetKey configuredTargetDep(Label platformLabel) {
    // Platforms do not rely on the configuration. Use a dummy blank configuration to reduce the
    // number of skyframe nodes created.
    return ConfiguredTargetKey.builder()
        .setLabel(platformLabel)
        .setConfigurationKey(BuildConfigurationKey.create(CommonOptions.EMPTY_OPTIONS))
        .build();
  }

  @Nullable
  @Override
  public PlatformValue compute(SkyKey skyKey, Environment env)
      throws PlatformFunctionException, InterruptedException {
    var platformLabel = (Label) skyKey.argument();
    var pkgId = platformLabel.getPackageIdentifier();

    // Load the Package first to verify the Target. The ConfiguredTarget should not be loaded until
    // after verification. See https://github.com/bazelbuild/bazel/pull/10307.
    //
    // In distributed analysis, these packages will be duplicated across shards.
    Target target;
    try {
      var pkgValue = (PackageValue) env.getValueOrThrow(pkgId, NoSuchPackageException.class);
      if (pkgValue == null) {
        return null;
      }
      target = pkgValue.getPackage().getTarget(platformLabel.getName());
    } catch (NoSuchPackageException | NoSuchTargetException e) {
      throw new PlatformFunctionException(new InvalidPlatformException(e));
    }

    if (!PlatformLookupUtil.hasPlatformInfo(target)) {
      throw new PlatformFunctionException(new InvalidPlatformException(platformLabel));
    }

    ConfiguredTarget configuredTarget;
    try {
      var configuredTargetValue =
          (ConfiguredTargetValue)
              env.getValueOrThrow(
                  configuredTargetDep(platformLabel), ConfiguredValueCreationException.class);
      if (configuredTargetValue == null) {
        return null;
      }
      configuredTarget = configuredTargetValue.getConfiguredTarget();
    } catch (ConfiguredValueCreationException e) {
      throw new PlatformFunctionException(new InvalidPlatformException(platformLabel, e));
    }

    PlatformInfo platformInfo = PlatformProviderUtils.platform(configuredTarget);
    if (platformInfo == null) {
      throw new PlatformFunctionException(
          new InvalidPlatformException(configuredTarget.getLabel()));
    }

    if (platformInfo.flags().isEmpty()) {
      return PlatformValue.noFlags(platformInfo);
    }

    var repoMappingValue =
        (RepositoryMappingValue)
            env.getValue(RepositoryMappingValue.key(platformLabel.getRepository()));
    if (repoMappingValue == null) {
      return null;
    }

    var parsedFlagsKey =
        ParsedFlagsValue.Key.create(
            platformInfo.flags(),
            PackageContext.of(pkgId, repoMappingValue.repositoryMapping()),
            // Include default values so that any flags explicitly reset to the default are kept.
            /* includeDefaultValues= */ true);
    var parsedFlagsValue = (ParsedFlagsValue) env.getValue(parsedFlagsKey);
    if (parsedFlagsValue == null) {
      return null;
    }

    return PlatformValue.withFlags(platformInfo, parsedFlagsValue);
  }

  private static final class PlatformFunctionException extends SkyFunctionException {
    PlatformFunctionException(InvalidPlatformException cause) {
      super(cause, Transience.PERSISTENT);
    }
  }
}
