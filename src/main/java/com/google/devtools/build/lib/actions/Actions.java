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

package com.google.devtools.build.lib.actions;

import static java.util.concurrent.TimeUnit.MINUTES;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.escape.Escaper;
import com.google.common.escape.Escapers;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.SkyframeAwareAction;
import com.google.devtools.build.lib.vfs.OsPathPolicy;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.WalkableGraph;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/** Utility class for actions. */
public final class Actions {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static final Escaper PATH_ESCAPER =
      Escapers.builder()
          .addEscape('_', "_U")
          .addEscape('/', "_S")
          .addEscape('\\', "_B")
          .addEscape(':', "_C")
          .addEscape('@', "_A")
          .build();

  private Actions() {}

  /**
   * Determines whether the given action needs to depend on the build ID.
   *
   * <p>Such actions are not shareable across servers.
   */
  public static boolean dependsOnBuildId(ActionAnalysisMetadata action) {
    // Volatile build actions may need to execute even if none of their known inputs have changed.
    // Depending on the build ID ensures that these actions have a chance to execute.
    // SkyframeAwareActions do not need to depend on the build ID because their volatility is due to
    // their dependence on Skyframe nodes that are not captured in the action cache. Any changes to
    // those nodes will cause this action to be rerun, so a build ID dependency is unnecessary.
    if (!(action instanceof Action)) {
      return false;
    }
    if (action instanceof NotifyOnActionCacheHit) {
      return true;
    }
    return ((Action) action).isVolatile() && !(action instanceof SkyframeAwareAction);
  }

  /**
   * Checks if the two actions are equivalent. This method exists to support sharing actions between
   * configured targets for cases where there is no canonical target that could own the action. In
   * the action graph construction this case shows up as two actions generating the same output
   * file.
   *
   * <p>This method implements an equivalence relationship across actions, based on the action
   * class, the key, and the list of inputs and outputs.
   */
  public static boolean canBeShared(
      ActionKeyContext actionKeyContext, ActionAnalysisMetadata a, ActionAnalysisMetadata b)
      throws InterruptedException {
    return a.isShareable()
        && b.isShareable()
        && a.getMnemonic().equals(b.getMnemonic())
        // Non-Actions cannot be shared.
        && a instanceof Action
        && b instanceof Action
        && a.getKey(actionKeyContext, /* artifactExpander= */ null)
            .equals(b.getKey(actionKeyContext, /* artifactExpander= */ null))
        && artifactsEqualWithoutOwner(
            a.getMandatoryInputs().toList(), b.getMandatoryInputs().toList())
        && artifactsEqualWithoutOwner(a.getOutputs(), b.getOutputs());
  }

  /**
   * Checks whether provided actions are equivalent and adds a log line in case we may be overly
   * permissive in the result. Returned result is the same as for {@link
   * #canBeShared(ActionKeyContext, ActionAnalysisMetadata, ActionAnalysisMetadata)}.
   *
   * <p>TODO(b/160181927): Remove the logging once we move shared actions detection to execution
   * phase.
   */
  static boolean canBeSharedLogForPotentialFalsePositives(
      ActionKeyContext actionKeyContext,
      ActionAnalysisMetadata actionA,
      ActionAnalysisMetadata actionB)
      throws InterruptedException {
    if (!canBeShared(actionKeyContext, actionA, actionB)) {
      return false;
    }
    Optional<Artifact> treeArtifactInput =
        actionA.getMandatoryInputs().toList().stream().filter(Artifact::isTreeArtifact).findFirst();
    treeArtifactInput.ifPresent(
        treeArtifact ->
            logger.atInfo().atMostEvery(5, MINUTES).log(
                "Shared action: %s has a tree artifact input: %s -- shared actions"
                    + " detection is overly permissive in this case and may allow"
                    + " sharing of different actions",
                actionA, treeArtifact));
    return true;
  }

  private static boolean artifactsEqualWithoutOwner(
      Collection<Artifact> collection1, Collection<Artifact> collection2) {
    if (collection1.size() != collection2.size()) {
      return false;
    }
    Iterator<Artifact> iterator1 = collection1.iterator();
    Iterator<Artifact> iterator2 = collection2.iterator();
    while (iterator1.hasNext()) {
      Artifact artifact1 = iterator1.next();
      Artifact artifact2 = iterator2.next();
      if (!artifact1.equalsWithoutOwner(artifact2)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Assigns generating action keys to artifacts, and finds action conflicts. An action conflict
   * happens if two actions generate the same output artifact. Shared actions are not allowed. See
   * {@link #canBeShared} for details.
   *
   * @param actions a list of actions to check for action conflict.
   * @throws ActionConflictException iff there are two actions generate the same output
   */
  public static void assignOwnersAndThrowIfConflict(
      ActionKeyContext actionKeyContext,
      ImmutableList<ActionAnalysisMetadata> actions,
      ActionLookupKey actionLookupKey)
      throws ActionConflictException, InterruptedException, ArtifactGeneratedByOtherRuleException {
    assignOwnersAndThrowIfConflictMaybeToleratingSharedActions(
        actionKeyContext, actions, actionLookupKey, /* allowSharedAction= */ false);
  }

  /**
   * Assigns generating action keys to artifacts and finds action conflicts. An action conflict
   * happens if two actions generate the same output artifact. Shared actions are tolerated. See
   * {@link #canBeShared} for details. Should be called by a configured target/aspect on the actions
   * it owns. Should not be used for "global" checks of multiple configured targets: use {@link
   * #findArtifactPrefixConflicts} for that.
   *
   * @param actions a list of actions to check for action conflicts, all generated by the same
   *     configured target/aspect.
   * @throws ActionConflictException iff there are two unshareable actions generating the same
   *     output
   */
  public static void assignOwnersAndThrowIfConflictToleratingSharedActions(
      ActionKeyContext actionKeyContext,
      ImmutableList<ActionAnalysisMetadata> actions,
      ActionLookupKey actionLookupKey)
      throws ActionConflictException, InterruptedException, ArtifactGeneratedByOtherRuleException {
    assignOwnersAndThrowIfConflictMaybeToleratingSharedActions(
        actionKeyContext, actions, actionLookupKey, /* allowSharedAction= */ true);
  }

  private static void verifyGeneratingActionKeys(
      Artifact.DerivedArtifact output,
      ActionLookupData otherKey,
      boolean allowSharedAction,
      ActionKeyContext actionKeyContext,
      ImmutableList<ActionAnalysisMetadata> actions)
      throws ActionConflictException, InterruptedException {
    ActionLookupData firstKey = output.getGeneratingActionKey();
    Preconditions.checkState(
        firstKey.getActionLookupKey().equals(otherKey.getActionLookupKey()),
        "Mismatched lookup keys? %s %s %s",
        output,
        firstKey,
        otherKey);
    int actionIndex = firstKey.getActionIndex();
    int otherIndex = otherKey.getActionIndex();
    if (actionIndex != otherIndex
        && (!allowSharedAction
            || !Actions.canBeSharedLogForPotentialFalsePositives(
                actionKeyContext, actions.get(actionIndex), actions.get(otherIndex)))) {
      throw ActionConflictException.create(
          actionKeyContext, output, actions.get(actionIndex), actions.get(otherIndex));
    }
  }

  /**
   * Checks {@code actions} for conflicts and sets each artifact's generating action key.
   *
   * <p>Conflicts can happen in one of two ways: the same artifact can be the output of multiple
   * unshareable actions (or shareable actions if {@code allowSharedAction} is false), or two
   * artifacts with the same execPath can be the outputs of different unshareable actions.
   */
  private static void assignOwnersAndThrowIfConflictMaybeToleratingSharedActions(
      ActionKeyContext actionKeyContext,
      ImmutableList<ActionAnalysisMetadata> actions,
      ActionLookupKey actionLookupKey,
      boolean allowSharedAction)
      throws ActionConflictException, InterruptedException, ArtifactGeneratedByOtherRuleException {
    Map<PathFragment, Artifact.DerivedArtifact> seenArtifacts = new HashMap<>();
    // Loop over the actions, looking at all outputs for conflicts.
    int actionIndex = 0;
    for (ActionAnalysisMetadata action : actions) {
      ActionLookupData generatingActionKey =
          dependsOnBuildId(action)
              ? ActionLookupData.createUnshareable(actionLookupKey, actionIndex)
              : ActionLookupData.create(actionLookupKey, actionIndex);
      for (Artifact artifact : action.getOutputs()) {
        Preconditions.checkState(
            !artifact.isSourceArtifact(),
            "Source in outputs: %s %s %s",
            artifact,
            generatingActionKey,
            action);
        Artifact.DerivedArtifact output = (Artifact.DerivedArtifact) artifact;
        // Has an artifact with this execPath been seen before?
        Artifact.DerivedArtifact equalOutput =
            seenArtifacts.putIfAbsent(output.getExecPath(), output);
        if (equalOutput != null) {
          // Yes: assert that its generating action and this artifact's are compatible.
          verifyGeneratingActionKeys(
              equalOutput, generatingActionKey, allowSharedAction, actionKeyContext, actions);
        }
        // Was this output already seen, so it has a generating action key set?
        if (!output.hasGeneratingActionKey()) {
          // Common case: artifact hasn't been seen before.
          output.setGeneratingActionKey(generatingActionKey);
        } else {
          ActionLookupData oldKey = output.getGeneratingActionKey();
          if (!actionLookupKey.equals(oldKey.getActionLookupKey())) {
            // The rule is claiming to produce an output that one of its inputs produced. Silly!
            throw new ArtifactGeneratedByOtherRuleException(
                String.format(
                    "File '%s' is produced by %s but is already generated by rule %s",
                    output.prettyPrint(),
                    action.prettyPrint(),
                    oldKey.getActionLookupKey().getLabel()));
          }
          // Key is already set: verify that the generating action and this action are compatible.
          verifyGeneratingActionKeys(
              output, generatingActionKey, allowSharedAction, actionKeyContext, actions);
        }
      }
      actionIndex++;
    }
  }

  private static final Comparator<Artifact> EXEC_PATH_PREFIX_COMPARATOR =
      (lhs, rhs) -> {
        // We need to use the OS path policy in case the OS is case insensitive.
        OsPathPolicy os = OsPathPolicy.getFilePathOs();
        String str1 = lhs.getExecPathString();
        String str2 = rhs.getExecPathString();
        int len1 = str1.length();
        int len2 = str2.length();
        int n = Math.min(len1, len2);
        for (int i = 0; i < n; ++i) {
          char c1 = str1.charAt(i);
          char c2 = str2.charAt(i);
          int res = os.compare(c1, c2);
          if (res != 0) {
            if (c1 == PathFragment.SEPARATOR_CHAR) {
              return -1;
            } else if (c2 == PathFragment.SEPARATOR_CHAR) {
              return 1;
            }
            return res;
          }
        }
        return len1 - len2;
      };

  /**
   * Check whether two artifacts are a runfiles tree - runfiles output manifest pair.
   *
   * <p>This is necessary because these are exempt from the "path of one artifact cannot be a prefix
   * of another" rule. This is like this for historical reasons.
   */
  public static boolean isRunfilesArtifactPair(Artifact runfilesTree, Artifact runfilesManifest) {
    if (!runfilesTree.isRunfilesTree()) {
      // The outside artifact is not a runfiles tree. No go.
      return false;
    }

    // Now check whether the path of the inner artifact matches the expected path of a runfiles
    // output manifest.
    return runfilesManifest
        .getExecPathString()
        .equals(runfilesTree.getExecPath().getRelative("MANIFEST").getPathString());
  }

  /**
   * Finds Artifact prefix conflicts between generated artifacts. An artifact prefix conflict
   * happens if one action generates an artifact whose path is a strict prefix of another artifact's
   * path. Those two artifacts cannot exist simultaneously in the output tree.
   *
   * @param actionGraph the {@link ActionGraph} to query for artifact conflicts
   * @param artifacts all generated artifacts in the build
   * @return An immutable map between actions that generated the conflicting artifacts and their
   *     associated {@link ActionConflictException}
   */
  public static ImmutableMap<ActionAnalysisMetadata, ActionConflictException>
      findArtifactPrefixConflicts(ActionGraph actionGraph, Collection<Artifact> artifacts) {
    // No actions in graph -- currently happens only in tests. Special-cased because .next() call
    // below is unconditional.
    if (artifacts.isEmpty()) {
      return ImmutableMap.of();
    }

    Artifact[] artifactArray = artifacts.toArray(new Artifact[0]);
    Arrays.parallelSort(artifactArray, EXEC_PATH_PREFIX_COMPARATOR);

    // Keep deterministic ordering of bad actions.
    Map<ActionAnalysisMetadata, ActionConflictException> badActions = new LinkedHashMap<>();
    Iterator<Artifact> iter = Iterators.forArray(artifactArray);

    // Report an error for every derived artifact which is a strict prefix of another.
    // If x << y << z (where x << y means "y starts with x"), then we only report (x,y), (x,z), but
    // not (y,z).
    for (Artifact artifactJ = iter.next(); iter.hasNext(); ) {
      // For each comparison, we have a prefix candidate (pathI) and a suffix candidate (pathJ).
      // At the beginning of the loop, we set pathI to the last suffix candidate, since it has not
      // yet been tested as a prefix candidate, and then set pathJ to the paths coming after pathI,
      // until we come to one that does not contain pathI as a prefix. pathI is then verified not to
      // be the prefix of any path, so we start the next run of the loop.
      Artifact artifactI = artifactJ;
      PathFragment pathI = artifactI.getExecPath();
      // Compare pathI to the paths coming after it.
      while (iter.hasNext()) {
        artifactJ = iter.next();
        PathFragment pathJ = artifactJ.getExecPath();
        // Check length first so that we only detect strict prefix conflicts. Equal exec paths are
        // possible from shared actions.
        if (pathJ.getPathString().length() > pathI.getPathString().length()
            && pathJ.startsWith(pathI)
            && !isRunfilesArtifactPair(artifactI, artifactJ)) {
          ActionAnalysisMetadata actionI =
              Preconditions.checkNotNull(actionGraph.getGeneratingAction(artifactI), artifactI);
          ActionAnalysisMetadata actionJ =
              Preconditions.checkNotNull(actionGraph.getGeneratingAction(artifactJ), artifactJ);
          ActionConflictException exception =
              ActionConflictException.createPrefix(artifactI, artifactJ, actionI, actionJ);
          badActions.put(actionI, exception);
          badActions.put(actionJ, exception);
        } else { // pathJ didn't have prefix pathI, so no conflict possible for pathI.
          break;
        }
      }
    }
    return ImmutableMap.copyOf(badActions);
  }

  /**
   * Returns the escaped name for a given relative path as a string. This takes a short relative
   * path and turns it into a string suitable for use as a filename. Invalid filename characters are
   * escaped with an '_' + a single character token.
   */
  public static String escapedPath(String path) {
    return PATH_ESCAPER.escape(path);
  }

  /**
   * Returns a string that is usable as a unique path component for a label. It is guaranteed that
   * no other label maps to this string.
   */
  public static String escapeLabel(Label label) {
    String path = label.getPackageName() + ":" + label.getName();
    if (!label.getRepository().isMain()) {
      path = label.getRepository().getName() + "@" + path;
    }
    return PATH_ESCAPER.escape(path);
  }

  /**
   * Signals the rare case of a rule that claims to generate a file that was actually provided to it
   * by one of its dependencies.
   */
  public static class ArtifactGeneratedByOtherRuleException extends Exception {
    private ArtifactGeneratedByOtherRuleException(String message) {
      super(message);
    }
  }

  @Nullable
  public static ActionAnalysisMetadata getGeneratingAction(WalkableGraph graph, Artifact artifact)
      throws InterruptedException {
    if (artifact.isSourceArtifact()) {
      return null;
    }

    return getAction(graph, ((Artifact.DerivedArtifact) artifact).getGeneratingActionKey());
  }

  public static ActionAnalysisMetadata getAction(
      WalkableGraph graph, ActionLookupData actionLookupData) throws InterruptedException {
    var actionLookupKey = actionLookupData.getActionLookupKey();

    // In analysis caching build with cache hits, deserialized ActionLookupValues do not contain
    // actions, so the generating action for the artifact does not exist in the graph. It would
    // require a reanalysis of the entire configured target subgraph to produce the action,
    // nullifying the benefits of analysis caching.
    //
    // In practice this should be fine for critical path computation and execution graph log,
    // because this represents a pruned subgraph for the action and there was no work done other
    // than deserialization.
    if (graph.getValue(actionLookupKey) instanceof ActionLookupValue actionLookupValue) {
      // Not all ActionLookupKeys resolve to an ActionLookupValue, e.g. RemoteConfiguredTargetValue.
      if (actionLookupValue.getNumActions() > 0) {
        return actionLookupValue.getActions().get(actionLookupData.getActionIndex());
      }
    }

    return null;
  }
}
