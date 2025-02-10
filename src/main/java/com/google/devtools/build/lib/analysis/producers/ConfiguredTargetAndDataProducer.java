// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.producers;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.InconsistentNullConfigException;
import com.google.devtools.build.lib.analysis.TransitiveDependencyState;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.skyframe.BaseTargetPrerequisitesSupplier;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.ConfiguredValueCreationException;
import com.google.devtools.build.lib.skyframe.PackageValue;
import com.google.devtools.build.lib.skyframe.RemoteConfiguredTargetValue;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.state.StateMachine;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Determines {@link ConfiguredTargetAndData} from {@link ConfiguredTargetKey}.
 *
 * <p>The resulting package and configuration are based on the resulting {@link ConfiguredTarget}
 * and may be different from what is in the key, for example, if there is an alias.
 */
public final class ConfiguredTargetAndDataProducer
    implements StateMachine,
        Consumer<SkyValue>,
        StateMachine.ValueOrException3Sink<
            ConfiguredValueCreationException,
            NoSuchThingException,
            InconsistentNullConfigException> {
  /** Interface for accepting values produced by this class. */
  public interface ResultSink {
    void acceptConfiguredTargetAndData(ConfiguredTargetAndData value, int index);

    void acceptConfiguredTargetAndDataError(ConfiguredValueCreationException error);

    void acceptConfiguredTargetAndDataError(NoSuchThingException error);

    void acceptConfiguredTargetAndDataError(InconsistentNullConfigException error);
  }

  // -------------------- Input --------------------
  private final ConfiguredTargetKey key;
  private final ImmutableList<String> transitionKeys;
  private final TransitiveDependencyState transitiveState;

  /**
   * Cache for {@link ConfiguredTargetValue} and {@link BuildConfigurationValue}
   *
   * <p>Check {@link AspectFunction#baseTargetPrerequisitesSupplier} for more details
   */
  @Nullable private final BaseTargetPrerequisitesSupplier baseTargetPrerequisitesSupplier;

  // -------------------- Output --------------------
  private final ResultSink sink;
  private final int outputIndex;

  // -------------------- Internal State --------------------
  private ConfiguredTargetValue configuredTargetValue;
  @Nullable // Null if the configured target key's configuration key is null.
  private BuildConfigurationValue configurationValue;
  private Package pkg;

  public ConfiguredTargetAndDataProducer(
      ConfiguredTargetKey key,
      ImmutableList<String> transitionKeys,
      TransitiveDependencyState transitiveState,
      ResultSink sink,
      int outputIndex,
      @Nullable BaseTargetPrerequisitesSupplier baseTargetPrerequisitesSupplier) {
    this.key = key;
    this.transitionKeys = transitionKeys;
    this.transitiveState = transitiveState;
    this.sink = sink;
    this.outputIndex = outputIndex;
    this.baseTargetPrerequisitesSupplier = baseTargetPrerequisitesSupplier;
  }

  @Override
  public StateMachine step(Tasks tasks) throws InterruptedException {
    var cachedConfiguredTargetValue =
        baseTargetPrerequisitesSupplier == null
            ? null
            : baseTargetPrerequisitesSupplier.getPrerequisite(key);
    if (cachedConfiguredTargetValue != null) {
      acceptValue(cachedConfiguredTargetValue);
    } else {
      tasks.lookUp(
          key,
          ConfiguredValueCreationException.class,
          NoSuchThingException.class,
          InconsistentNullConfigException.class,
          (ValueOrException3Sink<
                  ConfiguredValueCreationException,
                  NoSuchThingException,
                  InconsistentNullConfigException>)
              this);
    }
    return this::fetchConfigurationAndPackage;
  }

  private void acceptValue(ConfiguredTargetValue configuredTargetValue) {
    this.configuredTargetValue = configuredTargetValue;
    if (transitiveState.storeTransitivePackages()) {
      transitiveState.updateTransitivePackages(
          ConfiguredTargetKey.fromConfiguredTarget(configuredTargetValue.getConfiguredTarget()),
          configuredTargetValue.getTransitivePackages());
    }
  }

  @Override
  public void acceptValueOrException3(
      @Nullable SkyValue value,
      @Nullable ConfiguredValueCreationException error,
      @Nullable NoSuchThingException missingTargetError,
      @Nullable InconsistentNullConfigException visibilityError) {
    if (value != null) {
      acceptValue((ConfiguredTargetValue) value);
      return;
    }
    if (error != null) {
      transitiveState.addTransitiveCauses(error.getRootCauses());
      sink.acceptConfiguredTargetAndDataError(error);
      return;
    }
    if (missingTargetError != null) {
      sink.acceptConfiguredTargetAndDataError(missingTargetError);
      return;
    }
    if (visibilityError != null) {
      sink.acceptConfiguredTargetAndDataError(visibilityError);
      return;
    }
    throw new IllegalArgumentException("both value and error were null");
  }

  private StateMachine fetchConfigurationAndPackage(Tasks tasks) throws InterruptedException {
    if (configuredTargetValue == null) {
      return DONE; // There was a previous error.
    }

    var configuredTarget = configuredTargetValue.getConfiguredTarget();
    var configurationKey = configuredTarget.getConfigurationKey();
    if (configurationKey != null) {
      this.configurationValue =
          baseTargetPrerequisitesSupplier == null
              ? null
              : baseTargetPrerequisitesSupplier.getPrerequisiteConfiguration(configurationKey);
      if (configurationValue == null) {
        tasks.lookUp(configurationKey, (Consumer<SkyValue>) this);
      }
    }

    if (configuredTargetValue instanceof RemoteConfiguredTargetValue) {
      // Skips package lookup. The RemoteConfiguredTargetValue includes its own TargetData.
      return this::constructResult;
    }

    // An alternative to this is to optimistically fetch the package using the label of the
    // configured target key. However, the actual package may differ when this is an
    // AliasConfiguredTarget and would need to be refetched.

    var packageId = configuredTarget.getLabel().getPackageIdentifier();
    this.pkg = transitiveState.getDependencyPackage(packageId);
    if (pkg == null) {
      // In incremental builds, it is possible that the package won't be present in the cache. For
      // example, suppose that a configured target A has two children B and C. If B is dirty, it
      // causes A's re-evaluation, which causes this fetch to be performed for C. However, C has not
      // been evaluated this build.
      tasks.lookUp(packageId, (Consumer<SkyValue>) this);
    }

    return this::constructResult;
  }

  @Override
  public void accept(SkyValue value) {
    if (value instanceof BuildConfigurationValue buildConfigurationValue) {
      this.configurationValue = buildConfigurationValue;
      return;
    }
    if (value instanceof PackageValue packageValue) {
      this.pkg = packageValue.getPackage();
      return;
    }
    throw new IllegalArgumentException("unexpected value: " + value);
  }

  private StateMachine constructResult(Tasks tasks) {
    ConfiguredTarget configuredTarget = configuredTargetValue.getConfiguredTarget();
    if (configuredTargetValue instanceof RemoteConfiguredTargetValue remoteValue) {
      sink.acceptConfiguredTargetAndData(
          new ConfiguredTargetAndData(
              configuredTarget, remoteValue.getTargetData(), configurationValue, transitionKeys),
          outputIndex);
      return DONE;
    }

    Target target;
    try {
      target = pkg.getTarget(configuredTarget.getLabel().getName());
    } catch (NoSuchTargetException e) {
      // The package was fetched based on the label of the configured target. Since the configured
      // target exists, it must have existed in the package when it was created.
      throw new IllegalStateException("Target already verified for " + configuredTarget, e);
    }
    sink.acceptConfiguredTargetAndData(
        new ConfiguredTargetAndData(configuredTarget, target, configurationValue, transitionKeys),
        outputIndex);
    return DONE;
  }
}
