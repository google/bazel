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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.configuredtargets.InputFileConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.test.TestProvider;
import com.google.devtools.build.lib.collect.nestedset.Depset.TypeException;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSet.Node;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.profiler.AutoProfiler;
import com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils;
import com.google.devtools.build.lib.query2.common.CqueryNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A small static class containing utility methods for handling the inclusion of extra top-level
 * artifacts into the build.
 */
public final class TopLevelArtifactHelper {
  /** Set of {@link Artifact}s in an output group. */
  @Immutable
  public static final class ArtifactsInOutputGroup {
    private final boolean important;
    private final boolean incomplete;
    private final NestedSet<Artifact> artifacts;

    private ArtifactsInOutputGroup(
        boolean important, boolean incomplete, NestedSet<Artifact> artifacts) {
      this.important = important;
      this.incomplete = incomplete;
      this.artifacts = checkNotNull(artifacts);
    }

    public NestedSet<Artifact> getArtifacts() {
      return artifacts;
    }

    /** Returns {@code true} if the user should know about this output group. */
    public boolean areImportant() {
      return important;
    }

    public boolean isIncomplete() {
      return incomplete;
    }
  }

  /**
   * The set of artifacts to build.
   *
   * <p>There are two kinds: the ones that the user cares about (e.g. files to build) and the ones
   * they don't (e.g. baseline coverage artifacts). The latter type doesn't get reported on various
   * outputs, e.g. on the console output listing the output artifacts of targets on the command
   * line.
   */
  @Immutable
  public static final class ArtifactsToBuild {
    private final ImmutableMap<String, ArtifactsInOutputGroup> artifacts;
    private final boolean allOutputGroupsImportant;

    private ArtifactsToBuild(
        ImmutableMap<String, ArtifactsInOutputGroup> artifacts, boolean allOutputGroupsImportant) {
      this.artifacts = checkNotNull(artifacts);
      this.allOutputGroupsImportant = allOutputGroupsImportant;
    }

    /** Returns the artifacts that the user should know about. */
    public NestedSet<Artifact> getImportantArtifacts() {
      NestedSetBuilder<Artifact> builder = NestedSetBuilder.stableOrder();
      for (ArtifactsInOutputGroup artifactsInOutputGroup : artifacts.values()) {
        if (artifactsInOutputGroup.areImportant()) {
          builder.addTransitive(artifactsInOutputGroup.getArtifacts());
        }
      }
      return builder.build();
    }

    /** Returns the actual set of artifacts that need to be built. */
    public NestedSet<Artifact> getAllArtifacts() {
      NestedSetBuilder<Artifact> builder = NestedSetBuilder.stableOrder();
      for (ArtifactsInOutputGroup artifactsInOutputGroup : artifacts.values()) {
        builder.addTransitive(artifactsInOutputGroup.getArtifacts());
      }
      return builder.build();
    }

    /**
     * Returns the set of all {@link Artifact}s grouped by their corresponding output group.
     *
     * <p>If an {@link Artifact} belongs to two or more output groups, it appears once in each
     * output group.
     */
    public ImmutableMap<String, ArtifactsInOutputGroup> getAllArtifactsByOutputGroup() {
      return artifacts;
    }

    /**
     * Returns if all of the output groups returned by {@link #getAllArtifactsByOutputGroup()} are
     * "important" - implying that all artifacts will be reported in BEP events.
     */
    public boolean areAllOutputGroupsImportant() {
      return allOutputGroupsImportant;
    }
  }

  private TopLevelArtifactHelper() {
    // Prevent instantiation.
  }

  private static final Duration MIN_LOGGING = Duration.ofMillis(10);

  /**
   * Returns the set of all top-level output artifacts.
   *
   * <p>In contrast with {@link AnalysisResult#getArtifactsToBuild}, which only returns artifacts to
   * request from the build tool, this method returns <em>all</em> artifacts produced by top-level
   * targets (including tests) and aspects.
   */
  public static ImmutableSet<Artifact> findAllTopLevelArtifacts(AnalysisResult analysisResult) {
    try (AutoProfiler ignored =
        GoogleAutoProfilerUtils.logged("finding top level artifacts", MIN_LOGGING)) {

      ImmutableSet.Builder<Artifact> artifacts = ImmutableSet.builder();
      artifacts.addAll(analysisResult.getArtifactsToBuild());

      TopLevelArtifactContext ctx = analysisResult.getTopLevelContext();
      Set<NestedSet.Node> visited = new HashSet<>();

      for (ProviderCollection provider :
          Iterables.concat(
              analysisResult.getTargetsToBuild(), analysisResult.getAspectsMap().values())) {
        for (ArtifactsInOutputGroup group :
            getAllArtifactsToBuild(provider, ctx).getAllArtifactsByOutputGroup().values()) {
          memoizedAddAll(group.getArtifacts(), artifacts, visited);
        }
      }

      if (analysisResult.getTargetsToTest() != null) {
        for (ConfiguredTarget testTarget : analysisResult.getTargetsToTest()) {
          artifacts.addAll(TestProvider.getTestStatusArtifacts(testTarget));
        }
      }

      return artifacts.build();
    }
  }

  private static void memoizedAddAll(
      NestedSet<Artifact> current,
      ImmutableSet.Builder<Artifact> artifacts,
      Set<NestedSet.Node> visited) {
    if (!visited.add(current.toNode())) {
      return;
    }
    artifacts.addAll(current.getLeaves());
    for (NestedSet<Artifact> child : current.getNonLeaves()) {
      memoizedAddAll(child, artifacts, visited);
    }
  }

  /**
   * Returns all artifacts to build if this target is requested as a top-level target. The resulting
   * set includes the temps and either the files to compile, if {@code context.compileOnly() ==
   * true}, or the files to run.
   *
   * <p>Calls to this method should generally return quickly; however, the runfiles computation can
   * be lazy, in which case it can be expensive on the first call. Subsequent calls may or may not
   * return the same {@code Iterable} instance.
   */
  public static ArtifactsToBuild getAllArtifactsToBuild(
      ProviderCollection target, TopLevelArtifactContext context) {
    return getAllArtifactsToBuild(OutputGroupInfo.get(target), getFilesToBuild(target), context);
  }

  static ArtifactsToBuild getAllArtifactsToBuild(
      @Nullable OutputGroupInfo outputGroupInfo,
      @Nullable NestedSet<Artifact> filesToBuild,
      TopLevelArtifactContext context) {
    ImmutableMap.Builder<String, ArtifactsInOutputGroup> allOutputGroups =
        ImmutableMap.builderWithExpectedSize(context.outputGroups().size());
    boolean allOutputGroupsImportant = true;
    for (String outputGroup : context.outputGroups()) {
      NestedSetBuilder<Artifact> results = NestedSetBuilder.stableOrder();

      if (outputGroup.equals(OutputGroupInfo.DEFAULT) && filesToBuild != null) {
        results.addTransitive(filesToBuild);
      }

      if (outputGroupInfo != null) {
        results.addTransitive(outputGroupInfo.getOutputGroup(outputGroup));
      }

      // Ignore output groups that have no artifacts.
      if (results.isEmpty()) {
        continue;
      }

      boolean isImportantGroup =
          !outputGroup.startsWith(OutputGroupInfo.HIDDEN_OUTPUT_GROUP_PREFIX);

      allOutputGroupsImportant &= isImportantGroup;

      ArtifactsInOutputGroup artifacts =
          new ArtifactsInOutputGroup(isImportantGroup, /*incomplete=*/ false, results.build());

      allOutputGroups.put(outputGroup, artifacts);
    }

    return new ArtifactsToBuild(
        allOutputGroups.buildOrThrow(), /*allOutputGroupsImportant=*/ allOutputGroupsImportant);
  }

  /**
   * Returns files to build directly from {@link FileProvider} or from {@code files} under {@link
   * DefaultInfo} provider.
   */
  @Nullable
  private static NestedSet<Artifact> getFilesToBuild(ProviderCollection target) {
    if (target.getProvider(FileProvider.class) != null) {
      return target.getProvider(FileProvider.class).getFilesToBuild();
    } else if (target.get(DefaultInfo.PROVIDER.getKey()) != null) {
      DefaultInfo defaultInfo = (DefaultInfo) target.get(DefaultInfo.PROVIDER.getKey());
      if (defaultInfo.getFiles() != null) {
        try {
          return defaultInfo.getFiles().getSet(Artifact.class);
        } catch (TypeException e) {
          throw new IllegalStateException("Error getting 'files' field of 'DefaultInfo'", e);
        }
      }
    }
    return null;
  }

  /**
   * Returns false if the build outputs provided by the target should never be shown to users.
   *
   * <p>Always returns false for hidden rules and source file targets.
   */
  public static boolean shouldConsiderForDisplay(CqueryNode configuredTarget) {
    // TODO(bazel-team): this is quite ugly. Add a marker provider for this check.
    if (configuredTarget instanceof InputFileConfiguredTarget) {
      // Suppress display of source files (because we do no work to build them).
      return false;
    }
    if (configuredTarget instanceof RuleConfiguredTarget ruleCt) {
      if (ruleCt.getRuleClassString().contains("$")) {
        // Suppress display of hidden rules
        return false;
      }
    }
    return true;
  }

  /**
   * Returns true if the given artifact should be shown to users as a build output.
   *
   * <p>Always returns false for runfiles tree and source artifacts.
   */
  public static boolean shouldDisplay(Artifact artifact) {
    return !artifact.isSourceArtifact() && !artifact.isRunfilesTree();
  }

  /**
   * Recursive procedure filtering a target/aspect's declared {@code
   * NestedSet<ArtifactsInOutputGroup>} and {@code NestedSet<Artifact>} to only include {@link
   * Artifact Artifacts} that were produced by successful actions.
   */
  public static class SuccessfulArtifactFilter {
    private final Set<Node> artifactSetCanBeSkipped = new HashSet<>();
    private final HashMap<Node, NestedSet<Artifact>> artifactSetToFilteredSet = new HashMap<>();

    private final ImmutableSet<Artifact> builtArtifacts;

    public SuccessfulArtifactFilter(ImmutableSet<Artifact> builtArtifacts) {
      this.builtArtifacts = builtArtifacts;
    }

    /**
     * Filters the declared output groups to only include artifacts that were actually built.
     *
     * <p>If no filtering is performed then the input NestedSet is returned directly.
     */
    public ImmutableMap<String, ArtifactsInOutputGroup> filterArtifactsInOutputGroup(
        ImmutableMap<String, ArtifactsInOutputGroup> outputGroups) {
      boolean leavesDirty = false;
      ImmutableMap.Builder<String, ArtifactsInOutputGroup> resultBuilder = ImmutableMap.builder();
      for (Map.Entry<String, ArtifactsInOutputGroup> entry : outputGroups.entrySet()) {
        ArtifactsInOutputGroup artifactsInOutputGroup = entry.getValue();
        ArtifactsInOutputGroup filteredArtifactsInOutputGroup;
        NestedSet<Artifact> filteredArtifacts =
            filterArtifactNestedSetToBuiltArtifacts(artifactsInOutputGroup.getArtifacts());
        if (filteredArtifacts == null) {
          filteredArtifactsInOutputGroup = artifactsInOutputGroup;
        } else {
          filteredArtifactsInOutputGroup =
              new ArtifactsInOutputGroup(
                  artifactsInOutputGroup.areImportant(), /*incomplete=*/ true, filteredArtifacts);
          leavesDirty = true;
        }
        if (!filteredArtifactsInOutputGroup.getArtifacts().isEmpty()) {
          resultBuilder.put(entry.getKey(), filteredArtifactsInOutputGroup);
        }
      }
      if (!leavesDirty) {
        return outputGroups;
      }
      return resultBuilder.buildOrThrow();
    }

    /**
     * Recursively filters the declared artifacts to only include artifacts that were actually
     * built.
     *
     * <p>Returns {@code null} if no artifacts are filtered out of the input.
     */
    @Nullable
    private NestedSet<Artifact> filterArtifactNestedSetToBuiltArtifacts(
        NestedSet<Artifact> declaredArtifacts) {
      Node declaredArtifactsNode = declaredArtifacts.toNode();
      if (artifactSetCanBeSkipped.contains(declaredArtifactsNode)) {
        return null;
      }
      NestedSet<Artifact> memoizedFilteredSet = artifactSetToFilteredSet.get(declaredArtifactsNode);
      if (memoizedFilteredSet != null) {
        return memoizedFilteredSet;
      }

      // Scan the Artifact leaves for any artifact not present in builtArtifacts. If an un-built
      // artifact is found, exit the loop early, and construct the list of filteredArtifacts later.
      // This avoids unnecessary allocation in the case where all artifacts are built.
      boolean leavesDirty = false;
      ImmutableList<Artifact> leaves = declaredArtifacts.getLeaves();
      for (Artifact a : leaves) {
        if (!builtArtifacts.contains(a)) {
          leavesDirty = true;
          break;
        }
      }
      // Unconditionally populate filteredNonLeaves by filtering each NestedSet<Artifact> non-leaf
      // successor, and set nonLeavesDirty if anything is filtered out. The filteredNonLeaves list
      // will only be used if leavesDirty is true or nonLeavesDirty is true.
      boolean nonLeavesDirty = false;
      ImmutableList<NestedSet<Artifact>> nonLeaves = declaredArtifacts.getNonLeaves();
      List<NestedSet<Artifact>> filteredNonLeaves = new ArrayList<>(nonLeaves.size());
      for (NestedSet<Artifact> nonLeaf : nonLeaves) {
        NestedSet<Artifact> filteredNonLeaf = filterArtifactNestedSetToBuiltArtifacts(nonLeaf);
        // Null indicates no filtering happened and the input may be used as-is.
        if (filteredNonLeaf != null) {
          nonLeavesDirty = true;
        } else {
          filteredNonLeaf = nonLeaf;
        }
        if (!filteredNonLeaf.isEmpty()) {
          filteredNonLeaves.add(filteredNonLeaf);
        }
      }
      if (!leavesDirty && !nonLeavesDirty) {
        artifactSetCanBeSkipped.add(declaredArtifactsNode);
        // Returning null indicates no filtering happened and the input may be used as-is.
        return null;
      }
      NestedSetBuilder<Artifact> newSetBuilder =
          new NestedSetBuilder<>(declaredArtifacts.getOrder());
      for (Artifact a : leaves) {
        if (builtArtifacts.contains(a)) {
          newSetBuilder.add(a);
        }
      }
      for (NestedSet<Artifact> filteredNonLeaf : filteredNonLeaves) {
        newSetBuilder.addTransitive(filteredNonLeaf);
      }
      NestedSet<Artifact> result = newSetBuilder.build();
      artifactSetToFilteredSet.put(declaredArtifactsNode, result);
      return result;
    }
  }
}
