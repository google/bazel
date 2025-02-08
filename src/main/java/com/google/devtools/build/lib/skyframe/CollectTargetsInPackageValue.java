// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import static java.util.Objects.requireNonNull;

import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.pkgcache.FilteringPolicy;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

/** Singleton result of {@link CollectTargetsInPackageFunction}. */
public class CollectTargetsInPackageValue implements SkyValue {
  @SerializationConstant
  public static final CollectTargetsInPackageValue INSTANCE = new CollectTargetsInPackageValue();

  private CollectTargetsInPackageValue() {}

  /**
   * Creates a key for evaluation of {@link CollectTargetsInPackageFunction}. See that class's
   * comment for what callers should have done beforehand.
   */
  public static CollectTargetsInPackageKey key(
      PackageIdentifier packageId, FilteringPolicy filteringPolicy) {
    return CollectTargetsInPackageKey.create(packageId, filteringPolicy);
  }

  /** {@link SkyKey} argument. */
  @AutoCodec
  public record CollectTargetsInPackageKey(
      PackageIdentifier packageId, FilteringPolicy filteringPolicy) implements SkyKey {
    public CollectTargetsInPackageKey {
      requireNonNull(packageId, "packageId");
      requireNonNull(filteringPolicy, "filteringPolicy");
    }

    private static final SkyKeyInterner<CollectTargetsInPackageKey> interner = SkyKey.newInterner();

    @VisibleForSerialization
    @AutoCodec.Instantiator
    public static CollectTargetsInPackageKey create(
        PackageIdentifier packageId, FilteringPolicy filteringPolicy) {
      return interner.intern(new CollectTargetsInPackageKey(packageId, filteringPolicy));
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.COLLECT_TARGETS_IN_PACKAGE;
    }

    @Override
    public SkyKeyInterner<CollectTargetsInPackageKey> getSkyKeyInterner() {
      return interner;
    }
  }
}
