// Copyright 2020 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.rules.ninja.actions;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSortedMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.bazel.rules.ninja.file.GenericParsingException;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.concurrent.ExecutionException;

/**
 * Helper class for caching computation of transitive inclusion of usual targets into phony.
 * (We can not compute all artifacts for all phony targets because some of them may not be created
 * by a subgraph of required actions.)
 */
public class PhonyTargetArtifacts {
  private final LoadingCache<PathFragment, NestedSet<Artifact>> cache;

  public PhonyTargetArtifacts(
      ImmutableSortedMap<PathFragment, PhonyTarget> phonyTargetsMap,
      NinjaGraphArtifactsHelper artifactsHelper) {
    // phonyClosureNames are flattened list of the phony target names in the transitive closure.
    CacheLoader<PathFragment, NestedSet<Artifact>> loader = new CacheLoader<PathFragment, NestedSet<Artifact>>() {
      @Override
      public NestedSet<Artifact> load(PathFragment phonyName) throws Exception {
        PhonyTarget phonyTarget = phonyTargetsMap.get(phonyName);
        Preconditions.checkNotNull(phonyTarget);
        NestedSetBuilder<Artifact> builder = NestedSetBuilder.stableOrder();
        for (PathFragment input : phonyTarget.getDirectUsualInputs()) {
          builder.add(artifactsHelper.getInputArtifact(input));
        }
        // phonyClosureNames are flattened list of the phony target names in the transitive closure.
        for (PathFragment phonyClosureName : phonyTarget.getPhonyClosureNames()) {
          NestedSet<Artifact> nestedSet = cache.get(phonyClosureName);
          Preconditions.checkNotNull(nestedSet);
          builder.addTransitive(nestedSet);
        }
        return builder.build();
      }
    };
    cache = CacheBuilder.newBuilder().build(loader);
  }

  public NestedSet<Artifact> getPhonyTargetArtifacts(PathFragment phonyName)
      throws GenericParsingException {
    try {
      return cache.get(phonyName);
    } catch (ExecutionException e) {
      Throwables.throwIfInstanceOf(e, GenericParsingException.class);
      Throwables.throwIfInstanceOf(e, Error.class);
      throw new RuntimeException(e);
    }
  }
}
