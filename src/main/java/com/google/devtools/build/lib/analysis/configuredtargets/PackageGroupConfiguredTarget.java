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

package com.google.devtools.build.lib.analysis.configuredtargets;

import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.PackageSpecificationProvider;
import com.google.devtools.build.lib.analysis.TargetContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.analysis.VisibilityProvider;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.PackageGroup;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import javax.annotation.Nullable;

/**
 * Dummy ConfiguredTarget for package groups. Contains no functionality, since package groups are
 * not really first-class Targets.
 */
@Immutable
@AutoCodec
public class PackageGroupConfiguredTarget extends AbstractConfiguredTarget {
  private final PackageSpecificationProvider packageSpecificationProvider;

  @Override
  public <P extends TransitiveInfoProvider> P getProvider(Class<P> provider) {
    if (provider == FileProvider.class) {
      return provider.cast(FileProvider.EMPTY); // can't fail
    }
    if (provider == PackageSpecificationProvider.class) {
      return provider.cast(packageSpecificationProvider);
    } else {
      return super.getProvider(provider);
    }
  }

  public PackageGroupConfiguredTarget(
      ActionLookupKey actionLookupKey, TargetContext targetContext, PackageGroup packageGroup) {
    // Package groups are always public (see PackageGroup#getVisibility).
    this(actionLookupKey, PackageSpecificationProvider.create(targetContext, packageGroup));
  }

  @VisibleForSerialization
  @AutoCodec.Instantiator
  PackageGroupConfiguredTarget(
      ActionLookupKey lookupKey, PackageSpecificationProvider packageSpecificationProvider) {
    // Package groups are always public (see PackageGroup#getVisibility).
    super(lookupKey, VisibilityProvider.PUBLIC_VISIBILITY);
    this.packageSpecificationProvider = packageSpecificationProvider;
  }

  @Override
  public boolean isCreatedInSymbolicMacro() {
    // Answer is irrelevant because package groups are always public.
    return false;
  }

  @Override
  @Nullable
  protected Info rawGetStarlarkProvider(Provider.Key providerKey) {
    if (providerKey.equals(packageSpecificationProvider.getProvider().getKey())) {
      return packageSpecificationProvider;
    }
    return null;
  }

  @Override
  @Nullable
  protected Object rawGetStarlarkProvider(String providerKey) {
    return null;
  }
}
