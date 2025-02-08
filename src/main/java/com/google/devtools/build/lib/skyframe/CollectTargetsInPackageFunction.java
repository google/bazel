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

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.TargetPatternResolverUtil;
import com.google.devtools.build.skyframe.GraphTraversingHelper;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import javax.annotation.Nullable;

/**
 * Declares a dependency on all targets in a package, to ensure those targets are in the graph. Does
 * no error-checking on the package id provided, so callers should have already verified that there
 * is a package with this id.
 */
class CollectTargetsInPackageFunction implements SkyFunction {
  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {
    CollectTargetsInPackageValue.CollectTargetsInPackageKey argument =
        (CollectTargetsInPackageValue.CollectTargetsInPackageKey) skyKey.argument();
    PackageIdentifier packageId = argument.packageId();
    PackageValue packageValue;
    try {
      packageValue = (PackageValue) env.getValueOrThrow(packageId, NoSuchPackageException.class);
    } catch (NoSuchPackageException e) {
      // If the argument is a package that doesn't exist, the aggregator function can return
      // a success value immediately.
      return CollectTargetsInPackageValue.INSTANCE;
    }
    if (env.valuesMissing()) {
      return null;
    }
    Package pkg = packageValue.getPackage();
    if (pkg.containsErrors()) {
      env.getListener()
          .handle(
              Event.error(
                  "package contains errors: " + packageId.getPackageFragment().getPathString()));
    }
    return GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissing(
            env,
            Iterables.transform(
                TargetPatternResolverUtil.resolvePackageTargets(pkg, argument.filteringPolicy()),
                TO_TRANSITIVE_TRAVERSAL_KEY))
        ? null
        : CollectTargetsInPackageValue.INSTANCE;
  }

  private static final Function<Target, SkyKey> TO_TRANSITIVE_TRAVERSAL_KEY =
      new Function<Target, SkyKey>() {
        @Override
        public SkyKey apply(Target target) {
          return TransitiveTraversalValue.key(target.getLabel());
        }
      };
}
