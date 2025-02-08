// Copyright 2017 The Bazel Authors. All rights reserved.
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

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents aspects that should be applied to a configured target as part of {@link Dependency}.
 *
 * <p>One can consider the configured target graph as being a DAG in two dimensions: one is the DAG
 * analogous to the target graph and the other is a DAG between aspects applied to the same
 * configured target. This class represents the latter. The full "aspect dependency graph" is
 * computed when traversing the configured target graph. The analysis of the aspects attached to the
 * same configured target is done by simply unwrapping the graph of {@link AspectDeps} instances.
 *
 * <p>{@link Dependency} encapsulates all information that is needed to analyze an edge between an
 * AspectValue or a ConfiguredTargetValue and their direct dependencies, and {@link
 * AspectCollection} represents an aspect-related part of this information.
 *
 * <p>Analysis arrives to a particular node in target graph with an ordered list of aspects that
 * need to be applied. Some of those aspects should visible to the node in question; some of them
 * are not directly visible, but are visible to other aspects, as specified by {@link
 * com.google.devtools.build.lib.packages.AspectDefinition#getRequiredProvidersForAspects()}.
 *
 * <p>As an example, of all these things in interplay, consider android_binary rule depending on
 * java_proto_library rule depending on proto_library rule; consider further that we analyze the
 * android_binary with some ide_info aspect:
 *
 * <pre>
 *    proto_library(name = "pl") + ide_info_aspect
 *       ^
 *       | [java_proto_aspect]
 *    java_proto_library(name = "jpl") + ide_info_aspect
 *       ^
 *       | [DexArchiveAspect]
 *    android_binary(name = "ab") + ide_info_aspect
 * </pre>
 *
 * ide_info_aspect is interested in java_proto_aspect, but not in DexArchiveAspect.
 *
 * <p>Let's look is the {@link AspectCollection} for a Dependency representing a jpl->pl edge for
 * ide_info_aspect application to target <code>jpl</code>:
 *
 * <ul>
 *   <li>the full list of aspects is [java_proto_aspect, DexArchiveAspect, ide_info_aspect] in this
 *       order (the order is determined by the order in which aspects originate on {@code
 *       ab->...->pl} path).
 *   <li>however, DexArchiveAspect is not visible to either ide_info_aspect or java_proto_aspect, so
 *       the reduced list(and a result of {@link #getUsedAspects()} ) will be [java_proto_aspect,
 *       ide_info_aspect]
 *   <li>both java_proto_aspect and ide_info_aspect will be visible to <code>jpl + ide_info_aspect
 *       </code> node: the former because java_proto_library originates java_proto_aspect, and the
 *       aspect applied to the node sees the same dependencies; and the latter because the aspect
 *       sees itself on all targets it propagates to. So {@link #getUsedAspects()} will return both
 *       of them.
 *   <li>Since ide_info_aspect declared its interest in java_proto_aspect and the latter comes
 *       before it in the order, {@link AspectDeps} for ide_info_aspect will contain
 *       java_proto_aspect (so the application of ide_info_aspect to <code>pl</code> target will see
 *       java_proto_aspect as well).
 * </ul>
 *
 * More details on members of {@link AspectCollection} follow, as well as more examples of aspect
 * visibility rules.
 *
 * <p>{@link AspectDeps} is a class that represents an aspect and all aspects that are directly
 * visible to it.
 *
 * <p>{@link #getUsedAspects()} return all aspects that should be applied to the target, in
 * topological order.
 *
 * <p>In the following scenario, consider rule r<sub>i</sub> sending an aspect a<sub>i</sub> to its
 * dependency:
 *
 * <pre>
 *      [r0]
 *       ^
 *  (a1) |
 *      [r1]
 *  (a2) |
 *      [r2]
 *  (a3) |
 *      [r3]
 * </pre>
 *
 * When a3 is propagated to target r0, the analysis arrives there with a path [a1, a2, a3]. Since we
 * analyse the propagation of aspect a3, the only visible aspect is a3.
 *
 * <p>Let's first assume that aspect a3 wants to see aspects a1 and a2, but aspects a1 and a2 are
 * not interested in each other (according to their {@link
 * com.google.devtools.build.lib.packages.AspectDefinition#getRequiredProvidersForAspects()}).
 *
 * <p>Since a3 is interested in all aspects, the result of {@link #getUsedAspects()} will be [a1,
 * a2, a3], and {@link AspectCollection} will be:
 *
 * <ul>
 *   <li>a3 -> [a1, a2]
 *   <li>a2 -> []
 *   <li>a1 -> []
 * </ul>
 *
 * <p>Now what happens if a3 is interested in a2 but not a1, and a2 is interested in a1? Again, all
 * aspects are transitively interesting to a visible a3, so {@link #getUsedAspects()} will be [a1,
 * a2, a3], but {@link AspectCollection} will now be:
 *
 * <ul>
 *   <li>a3 -> [a2]
 *   <li>a2 -> [a1]
 *   <li>a1 -> []
 * </ul>
 *
 * <p>As a final example, what happens if a3 is interested in a1, and a1 is interested in a2, but a3
 * is not interested in a2? Now the result of {@link #getUsedAspects()} will be [a1, a3]. a1 is
 * interested in a2, but a2 comes later in the path than a1, so a1 does not see it (a1 only started
 * propagating on r1 -> r0 edge, and there is now a2 originating on that path). And {@link
 * AspectCollection} will now be:
 *
 * <ul>
 *   <li>a3 -> [a1]
 *   <li>a1 -> []
 * </ul>
 *
 * Note that is does not matter if a2 is interested in a1 or not - since no one after it in the path
 * is interested in it, a2 is filtered out.
 */
@Immutable
public final class AspectCollection {
  /** The name of the native aspect that collects validation outputs. */
  public static final String VALIDATION_ASPECT_NAME = "ValidateTarget";

  /** aspects that should be visible to a dependency */
  private final ImmutableSet<AspectDeps> usedAspects;

  public static final AspectCollection EMPTY = new AspectCollection(ImmutableSet.<AspectDeps>of());

  private AspectCollection(ImmutableSet<AspectDeps> usedAspects) {
    this.usedAspects = usedAspects;
  }

  public ImmutableSet<AspectDeps> getUsedAspects() {
    return usedAspects;
  }

  public boolean isEmpty() {
    return usedAspects.isEmpty();
  }

  @Override
  public String toString() {
    return "AspectCollection{" + usedAspects + "}";
  }

  @Override
  public int hashCode() {
    return usedAspects.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof AspectCollection that)) {
      return false;
    }
    return this.usedAspects.equals(that.usedAspects);
  }

  /**
   * Represents an aspect with all the aspects it depends on (within an {@link AspectCollection}.
   *
   * <p>We preserve the order of aspects to correspond to the order originally specified in the call
   * to {@link AspectCollection#create}, although that is not strictly needed semantically.
   *
   * <p>This data structure cannot be a simple list. Consider the case when four aspects [a1, a2,
   * a3, a4] are attached and a4 is interested in a3, a3 in a2 and a2 in a1.
   *
   * <p>In this case, when analyzing a3, only a2 will be in its direct dependencies (since we don't
   * want to merge in the dependencies of a1), but then a2 would have no way of knowing that a1 was
   * also propagated.
   *
   * <p>(a list of (dependent aspect, visible) pairs would work, though and the code would probably
   * be somewhat simpler)
   */
  public record AspectDeps(AspectDescriptor aspect, ImmutableList<AspectDeps> usedAspects) {
    public AspectDeps {
      requireNonNull(aspect, "aspect");
      requireNonNull(usedAspects, "usedAspects");
    }

    private static AspectDeps create(
        AspectDescriptor aspect, ImmutableList<AspectDeps> usedAspects) {
      return new AspectDeps(aspect, usedAspects);
    }
  }

  /**
   * Creates an {@link AspectKey} for the given root aspect, {@code aspectDeps}.
   *
   * <p>Converts the DAG of {@link AspectDescriptor}s rooted at {@code aspectDeps} into an
   * isomorphic DAG of {@link AspectKey} with corresponding {@link AspectKey#getAspectDescriptor}
   * values. All resulting {@link AspectKey}s have {@link AspectKey#getBaseConfiguredTargetKey}
   * equal to {@code baseKey}.
   *
   * <p>As a side effect, {@code visited} is populated with all the DAG nodes with each map entry
   * value's descriptor matching the map entry key.
   */
  @CanIgnoreReturnValue
  public static AspectKey buildAspectKey(
      AspectDeps aspectDeps,
      Map<AspectDescriptor, AspectKey> visited,
      ConfiguredTargetKey baseKey) {
    AspectDescriptor aspect = aspectDeps.aspect();
    AspectKey aspectKey = visited.get(aspect);
    if (aspectKey != null) {
      return aspectKey;
    }

    ImmutableList<AspectDeps> usedAspects = aspectDeps.usedAspects();
    var usedAspectKeys = ImmutableList.<AspectKey>builderWithExpectedSize(usedAspects.size());
    for (AspectCollection.AspectDeps usedAspect : usedAspects) {
      usedAspectKeys.add(buildAspectKey(usedAspect, visited, baseKey));
    }

    aspectKey = AspectKeyCreator.createAspectKey(aspect, usedAspectKeys.build(), baseKey);
    visited.put(aspect, aspectKey);
    return aspectKey;
  }

  public static AspectCollection createForTests(AspectDescriptor... descriptors) {
    return createForTests(ImmutableSet.copyOf(descriptors));
  }

  public static AspectCollection createForTests(ImmutableSet<AspectDescriptor> descriptors) {
    ImmutableSet.Builder<AspectDeps> depsBuilder = ImmutableSet.builder();
    for (AspectDescriptor descriptor : descriptors) {
      depsBuilder.add(AspectDeps.create(descriptor, ImmutableList.<AspectDeps>of()));
    }
    return new AspectCollection(depsBuilder.build());
  }

  /**
   * Creates an {@link AspectCollection} from an ordered list of aspects and a set of visible
   * aspects.
   *
   * <p>The order of aspects is reverse to the order in which they originated, with the earliest
   * originating occurring last in the list.
   */
  public static AspectCollection create(Iterable<Aspect> aspectPath)
      throws AspectCycleOnPathException {
    LinkedHashMap<AspectDescriptor, Aspect> aspectMap = deduplicateAspects(aspectPath);
    LinkedHashMap<AspectDescriptor, ArrayList<AspectDescriptor>> deps =
        new LinkedHashMap<>();

    // Calculate all needed aspects. Already discovered aspects are in key set of deps.
    // 1) Start from the end of the path. The aspect only sees other aspects that are
    //    before it
    // 2) Otherwise, check whether 'aspect' is visible to or required by any already seen aspects.
    // If it is visible to 'depAspect' or explicitly required by it, add the 'aspect' to a list of
    // aspects visible to 'depAspect'.
    // At the end of this algorithm, key set of 'deps' contains the original aspect list in reverse
    // (since we iterate the original list in reverse).
    //
    // deps[aspect] contains all aspects that 'aspect' needs, in reverse order.
    for (Map.Entry<AspectDescriptor, Aspect> aspect :
        ImmutableList.copyOf(aspectMap.entrySet()).reverse()) {
      for (AspectDescriptor depAspectDescriptor : deps.keySet()) {
        Aspect depAspect = aspectMap.get(depAspectDescriptor);
        // As any aspect can add validation outputs, the special validation aspect that collects
        // their outputs has to depend on all aspects.
        if (depAspect
                .getDefinition()
                .getRequiredProvidersForAspects()
                .isSatisfiedBy(aspect.getValue().getDefinition().getAdvertisedProviders())
            || depAspect.getDefinition().requires(aspect.getValue())
            || depAspect.getAspectClass().getName().equals(VALIDATION_ASPECT_NAME)) {
          deps.get(depAspectDescriptor).add(aspect.getKey());
        }
      }

      deps.put(aspect.getKey(), new ArrayList<>());
    }

    // Calculate the path for every directly required aspect
    HashMap<AspectDescriptor, AspectDeps> aspectPaths = new HashMap<>();
    ImmutableSet.Builder<AspectDeps> result = ImmutableSet.builder();
    for (AspectDescriptor aspect : aspectMap.keySet()) {
      result.add(buildAspectDeps(aspect, aspectPaths, deps));
    }
    return new AspectCollection(result.build());
  }

  /**
   * Deduplicate aspects in path.
   *
   * @throws AspectCycleOnPathException if an aspect occurs twice on the path and
   *         the second occurrence sees a different set of aspects.
   */
  private static LinkedHashMap<AspectDescriptor, Aspect> deduplicateAspects(
      Iterable<Aspect> aspectPath) throws AspectCycleOnPathException {

    LinkedHashMap<AspectDescriptor, Aspect> aspectMap = new LinkedHashMap<>();
    ArrayList<Aspect> seenAspects = new ArrayList<>();
    for (Aspect aspect : aspectPath) {
      if (!aspectMap.containsKey(aspect.getDescriptor())) {
        aspectMap.put(aspect.getDescriptor(), aspect);
        seenAspects.add(aspect);
      } else {
        validateDuplicateAspect(aspect, seenAspects);
      }
    }
    return aspectMap;
  }

  /**
   * Detect inconsistent duplicate occurrence of an aspect on the path. There is a previous
   * occurrence of {@code aspect} in {@code seenAspects}.
   *
   * <p>If in between that previous occurrence and the newly discovered occurrence there is an
   * aspect that is visible to or required by {@code aspect}, then the second occurrence is
   * inconsistent - the set of aspects it sees is different from the first one.
   */
  private static void validateDuplicateAspect(Aspect aspect, ArrayList<Aspect> seenAspects)
      throws AspectCycleOnPathException {
    for (int i = seenAspects.size() - 1; i >= 0; i--) {
      Aspect seenAspect = seenAspects.get(i);
      if (aspect.getDescriptor().equals(seenAspect.getDescriptor())) {
        // This is a previous occurrence of the same aspect.
        return;
      }

      if (aspect
              .getDefinition()
              .getRequiredProvidersForAspects()
              .isSatisfiedBy(seenAspect.getDefinition().getAdvertisedProviders())
          || aspect.getDefinition().requires(seenAspect)) {
        throw new AspectCycleOnPathException(aspect.getDescriptor(), seenAspect.getDescriptor());
      }
    }
  }

  private static AspectDeps buildAspectDeps(AspectDescriptor descriptor,
      HashMap<AspectDescriptor, AspectDeps> aspectPaths,
      LinkedHashMap<AspectDescriptor, ArrayList<AspectDescriptor>> deps) {
    if (aspectPaths.containsKey(descriptor)) {
      return aspectPaths.get(descriptor);
    }

    ImmutableList.Builder<AspectDeps> aspectPathBuilder = ImmutableList.builder();
    ArrayList<AspectDescriptor> depList = deps.get(descriptor);

    // deps[aspect] contains all aspects visible to 'aspect' in reverse order.
    for (int i = depList.size() - 1; i >= 0; i--) {
      aspectPathBuilder.add(buildAspectDeps(depList.get(i), aspectPaths, deps));
    }
    AspectDeps aspectPath = AspectDeps.create(descriptor, aspectPathBuilder.build());
    aspectPaths.put(descriptor, aspectPath);
    return aspectPath;
  }

  /**
   * Signals an inconsistency on aspect path: an aspect occurs twice on the path and the second
   * occurrence sees a different set of aspects.
   *
   * <p>{@link #getAspect()} is the aspect occurring twice, and {@link #getPreviousAspect()} is the
   * aspect that the second occurrence sees but the first does not.
   */
  public static class AspectCycleOnPathException extends Exception {

    private final AspectDescriptor aspect;
    private final AspectDescriptor previousAspect;

    public AspectCycleOnPathException(AspectDescriptor aspect, AspectDescriptor previousAspect) {
      super(String.format("Aspect %s is applied twice, both before and after aspect %s",
          aspect.getDescription(), previousAspect.getDescription()
      ));
      this.aspect = aspect;
      this.previousAspect = previousAspect;
    }

    public AspectDescriptor getAspect() {
      return aspect;
    }

    public AspectDescriptor getPreviousAspect() {
      return previousAspect;
    }
  }
}
