// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.devtools.build.lib.actions.BasicActionLookupValue;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import javax.annotation.Nullable;

/** An aspect in the context of the Skyframe graph. */
public class AspectValue extends BasicActionLookupValue
    implements ConfiguredAspect, RuleConfiguredObjectValue {

  public static AspectValue create(
      AspectKey key,
      Aspect aspect,
      ConfiguredAspect configuredAspect,
      @Nullable NestedSet<Package> transitivePackages) {
    return transitivePackages == null
        ? new AspectValue(aspect, configuredAspect)
        : new AspectValueWithTransitivePackages(key, aspect, configuredAspect, transitivePackages);
  }

  public static AspectValue createForAlias(
      AspectKey key,
      Aspect aspect,
      ConfiguredAspect configuredAspect,
      @Nullable NestedSet<Package> transitivePackages) {
    return transitivePackages == null
        ? new AspectValueForAlias(aspect, configuredAspect)
        : new AspectValueWithTransitivePackagesForAlias(
            key, aspect, configuredAspect, transitivePackages);
  }

  // These variables are only non-final because they may be clear()ed to save memory. They are null
  // only after they are cleared except for transitivePackagesForPackageRootResolution.
  @Nullable private Aspect aspect;
  @Nullable private TransitiveInfoProviderMap providers;

  // We store this in a boolean because the aspect variable from which it comes may be cleared to
  // save memory.
  private final boolean writesOutputToMasterLog;

  private AspectValue(Aspect aspect, ConfiguredAspect configuredAspect) {
    super(configuredAspect.getActions());
    this.aspect = checkNotNull(aspect);
    this.providers = configuredAspect.getProviders();
    this.writesOutputToMasterLog =
        aspect.getDefinition().getAttributes().containsKey("$print_to_master_log");
  }

  public AspectKey getKeyForTransitivePackageTracking() {
    throw new UnsupportedOperationException("Only supported if transitive packages are tracked.");
  }

  public final Aspect getAspect() {
    return checkNotNull(aspect);
  }

  @Override
  public TransitiveInfoProviderMap getProviders() {
    return checkNotNull(providers);
  }

  public boolean getWritesOutputToMasterLog() {
    return writesOutputToMasterLog;
  }

  @Override
  public boolean isCleared() {
    return this.aspect == null;
  }

  /**
   * Clears data from this value.
   *
   * <p>Should only be used when user specifies --discard_analysis_cache. Must be called at most
   * once per value, after which this object's other methods cannot be called.
   */
  public void clear(boolean clearEverything) {
    if (clearEverything) {
      aspect = null;
      providers = null;
    }
  }

  @Nullable
  @Override
  public NestedSet<Package> getTransitivePackages() {
    return null;
  }

  @Override
  public final ProviderCollection getConfiguredObject() {
    return this;
  }

  @Override
  protected ToStringHelper getStringHelper() {
    return super.getStringHelper().add("aspect", aspect).add("providers", providers);
  }

  @Override
  public String toString() {
    return getStringHelper().toString();
  }

  private static class AspectValueWithTransitivePackages extends AspectValue {
    @Nullable private transient NestedSet<Package> transitivePackages; // Null after clear().
    @Nullable private AspectKey key;

    private AspectValueWithTransitivePackages(
        AspectKey key,
        Aspect aspect,
        ConfiguredAspect configuredAspect,
        NestedSet<Package> transitivePackages) {
      super(aspect, configuredAspect);
      this.transitivePackages = checkNotNull(transitivePackages);
      this.key = checkNotNull(key);
    }

    @Override
    public NestedSet<Package> getTransitivePackages() {
      return transitivePackages;
    }

    @Override
    public AspectKey getKeyForTransitivePackageTracking() {
      return checkNotNull(key);
    }

    @Override
    public void clear(boolean clearEverything) {
      super.clear(clearEverything);
      transitivePackages = null;
      key = null;
    }

    @Override
    protected ToStringHelper getStringHelper() {
      return super.getStringHelper().add("key", key).add("transitivePackages", transitivePackages);
    }
  }

  private static final class AspectValueForAlias extends AspectValue {
    private AspectValueForAlias(Aspect aspect, ConfiguredAspect configuredAspect) {
      super(aspect, configuredAspect);
    }
  }

  private static final class AspectValueWithTransitivePackagesForAlias
      extends AspectValueWithTransitivePackages {
    private AspectValueWithTransitivePackagesForAlias(
        AspectKey key,
        Aspect aspect,
        ConfiguredAspect configuredAspect,
        NestedSet<Package> transitivePackages) {
      super(key, aspect, configuredAspect, transitivePackages);
    }
  }

  public static boolean isForAliasTarget(AspectValue aspectValue) {
    return aspectValue instanceof AspectValueForAlias
        || aspectValue instanceof AspectValueWithTransitivePackagesForAlias;
  }
}
