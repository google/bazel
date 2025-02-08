// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.CachingPackageLocator;
import com.google.devtools.build.lib.packages.LabelPrinter;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.pkgcache.TargetPatternPreloader;
import com.google.devtools.build.lib.pkgcache.TargetProvider;
import com.google.devtools.build.lib.query2.common.AbstractBlazeQueryEnvironment;
import com.google.devtools.build.lib.query2.common.QueryTransitivePackagePreloader;
import com.google.devtools.build.lib.query2.common.UniverseScope;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting;
import com.google.devtools.build.lib.query2.query.BlazeQueryEnvironment;
import com.google.devtools.build.lib.query2.query.GraphlessBlazeQueryEnvironment;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.WalkableGraph.WalkableGraphFactory;
import java.util.Set;
import javax.annotation.Nullable;

/** A factory that creates instances of {@code AbstractBlazeQueryEnvironment<Target>}. */
public class QueryEnvironmentFactory {
  /** Creates an appropriate {@link AbstractBlazeQueryEnvironment} based on the given options. */
  public AbstractBlazeQueryEnvironment<Target> create(
      @Nullable QueryTransitivePackagePreloader queryTransitivePackagePreloader,
      WalkableGraphFactory graphFactory,
      TargetProvider targetProvider,
      CachingPackageLocator cachingPackageLocator,
      TargetPatternPreloader targetPatternPreloader,
      TargetPattern.Parser targetParser,
      PathFragment relativeWorkingDirectory,
      boolean keepGoing,
      boolean strictScope,
      boolean orderedResults,
      UniverseScope universeScope,
      int loadingPhaseThreads,
      Predicate<Label> labelFilter,
      ExtendedEventHandler eventHandler,
      Set<Setting> settings,
      Iterable<QueryFunction> extraFunctions,
      @Nullable PathPackageLocator packagePath,
      boolean useGraphlessQuery,
      LabelPrinter labelPrinter) {
    Preconditions.checkNotNull(universeScope);
    if (canUseSkyQuery(orderedResults, universeScope, packagePath, strictScope, labelFilter)) {
      return new SkyQueryEnvironment(
          keepGoing,
          loadingPhaseThreads,
          eventHandler,
          settings,
          extraFunctions,
          targetParser,
          relativeWorkingDirectory,
          graphFactory,
          universeScope,
          packagePath,
          labelPrinter);
    } else if (useGraphlessQuery) {
      return new GraphlessBlazeQueryEnvironment(
          queryTransitivePackagePreloader,
          targetProvider,
          cachingPackageLocator,
          targetPatternPreloader,
          targetParser,
          keepGoing,
          strictScope,
          loadingPhaseThreads,
          labelFilter,
          eventHandler,
          settings,
          extraFunctions,
          labelPrinter);
    } else {
      return new BlazeQueryEnvironment(
          queryTransitivePackagePreloader,
          targetProvider,
          cachingPackageLocator,
          targetPatternPreloader,
          targetParser,
          keepGoing,
          strictScope,
          loadingPhaseThreads,
          labelFilter,
          eventHandler,
          settings,
          extraFunctions,
          labelPrinter);
    }
  }

  protected static boolean canUseSkyQuery(
      boolean orderedResults,
      UniverseScope universeScope,
      @Nullable PathPackageLocator packagePath,
      boolean strictScope,
      Predicate<Label> labelFilter) {
    return !orderedResults
        && !universeScope.isEmpty()
        && packagePath != null
        && strictScope
        && labelFilter == Rule.ALL_LABELS;
  }
}
