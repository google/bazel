// Copyright 2025 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.actions;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.devtools.build.lib.vfs.PathFragment;
import net.starlark.java.eval.StarlarkSemantics;

/** Holder class for symbols used by the PathMapper interface that shouldn't be public. */
final class PathMapperConstants {

  public static final StarlarkSemantics.Key<PathMapper> SEMANTICS_KEY =
      new StarlarkSemantics.Key<>("path_mapper", PathMapper.NOOP);
  public static final LoadingCache<ArtifactRoot, PathMapper.MappedArtifactRoot> mappedSourceRoots =
      Caffeine.newBuilder()
          .weakKeys()
          .build(sourceRoot -> new PathMapper.MappedArtifactRoot(sourceRoot.getExecPath()));

  private static final PathFragment BAZEL_OUT = PathFragment.create("bazel-out");
  private static final PathFragment BLAZE_OUT = PathFragment.create("blaze-out");

  /**
   * A special instance for use in {@link AbstractAction#computeKey} when path mapping is generally
   * enabled for an action.
   *
   * <p>When computing an action key, the following approaches to taking path mapping into account
   * do <b>not</b> work:
   *
   * <ul>
   *   <li>Using the actual path mapper is prohibitive since constructing it requires checking for
   *       collisions among the action input's paths when computing the action key, which flattens
   *       the input depsets of all actions that opt into path mapping and also increases CPU usage.
   *   <li>Unconditionally using {@link
   *       com.google.devtools.build.lib.analysis.actions.StrippingPathMapper} can result in stale
   *       action keys when an action is opted out of path mapping at execution time due to input
   *       path collisions after stripping. See path_mapping_test for an example.
   *   <li>Using {@link PathMapper#NOOP} does not distinguish between map_each results built from
   *       strings and those built from {@link
   *       com.google.devtools.build.lib.starlarkbuildapi.FileApi#getExecPathStringForStarlark}.
   *       While the latter will be mapped at execution time, the former won't, resulting in the
   *       same digest for actions that behave differently at execution time. This is covered by
   *       tests in StarlarkRuleImplementationFunctionsTest.
   * </ul>
   *
   * <p>Instead, we use a special path mapping instance that preserves the equality relations
   * between the original config segments, but prepends a fixed string to distinguish hard-coded
   * path strings from mapped paths. This relies on actions using path mapping to be "root
   * agnostic": they must not contain logic that depends on any particular (output) root path.
   */
  static final PathMapper FOR_FINGERPRINTING =
      execPath -> {
        if (!execPath.startsWith(BAZEL_OUT) && !execPath.startsWith(BLAZE_OUT)) {
          // This is not an output path.
          return execPath;
        }
        String execPathString = execPath.getPathString();
        int startOfConfigSegment = execPathString.indexOf('/') + 1;
        if (startOfConfigSegment == 0) {
          return execPath;
        }
        return PathFragment.createAlreadyNormalized(
            execPathString.substring(0, startOfConfigSegment)
                + "pm-"
                + execPathString.substring(startOfConfigSegment));
      };

  private PathMapperConstants() {}
}
