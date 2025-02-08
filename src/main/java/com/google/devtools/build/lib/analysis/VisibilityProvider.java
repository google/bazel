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

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.PackageSpecification;
import com.google.devtools.build.lib.packages.PackageSpecification.PackageGroupContents;

/**
 * Provider class for configured targets that have a visibility.
 *
 * <p>This is the analysis-time equivalent of the visibility attribute, with package groups
 * recursively expanded. This provider also tracks a bit indicating whether the target was created
 * in a symbolic macro, which is not necessarily otherwise available in the prerequisite object at
 * analysis time.
 *
 * <p>The contents of this provider are determined in {@link
 * ConfiguredTargetFactory#convertVisibility}. It is consumed by the visibility check in {@link
 * CommonPrerequisiteValidator#isVisibleToLocation}.
 */
public interface VisibilityProvider extends TransitiveInfoProvider {

  NestedSet<PackageGroupContents> PUBLIC_VISIBILITY =
      NestedSetBuilder.create(
          Order.STABLE_ORDER,
          PackageGroupContents.create(ImmutableList.of(PackageSpecification.everything())));

  NestedSet<PackageGroupContents> PRIVATE_VISIBILITY =
      NestedSetBuilder.emptySet(Order.STABLE_ORDER);

  /**
   * Returns the target's visibility, as determined from recursively resolving and expanding the
   * package groups it references.
   *
   * <p>Morally, this should represent the expansion of the target's {@link
   * Target#getActualVisibility "actual" visibility}. However, as an optimization, for targets that
   * are *not* declared within a symbolic macro, we substitute the {@link Target#getVisibility "raw"
   * or "default" visibility} for the actual visibility. The optimized version omits the package
   * where the target was instantiated, avoiding extra allocations in the common case of a target
   * that has public or private visibility. The caller must compensate for this optimization by
   * allowing visibility to the target's own package if the target was not created in a macro.
   */
  NestedSet<PackageGroupContents> getVisibility();

  /**
   * Returns whether this target was instantiated in one or more symbolic macros.
   *
   * <p>This information can be determined from the {@link Rule} object, but that's not necessarily
   * available from a prerequisite object at analysis time.
   *
   * <p>This bit is used by the {@link CommonPrerequisiteValidator#isSameLogicalPackage} hook, which
   * powers the hack that targets in {@code //javatests/foo} are allowed to see targets in {@code
   * //java/foo}. (This feature is only active within Google and disabled for OSS Bazel.) The
   * semantics are that targets created in symbolic macros are never automatically visible to {@code
   * //javatests/foo} packages, regardless of the package or declaration location.
   *
   * <p>This bit is also used to work around the optimization mentioned above for {@link
   * #getVisibility}.
   */
  boolean isCreatedInSymbolicMacro();
}
