// Copyright 2018 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionInputMapSink;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FilesetOutputTree;
import com.google.devtools.build.lib.actions.RunfilesArtifactValue;
import com.google.devtools.build.lib.analysis.actions.SymlinkAction;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue.ArchivedRepresentation;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Map;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;

/** Static utilities for working with action inputs. */
final class ActionInputMapHelper {

  private ActionInputMapHelper() {}

  static void addToMap(
      ActionInputMapSink inputMap,
      BiConsumer<Artifact, TreeArtifactValue> treeArtifactConsumer,
      Map<Artifact, FilesetOutputTree> filesetsInsideRunfiles,
      Map<Artifact, FilesetOutputTree> topLevelFilesets,
      Artifact key,
      SkyValue value,
      Environment env)
      throws InterruptedException {
    addToMap(
        inputMap,
        treeArtifactConsumer,
        filesetsInsideRunfiles,
        topLevelFilesets,
        key,
        value,
        env,
        MetadataConsumerForMetrics.NO_OP);
  }

  /**
   * Adds a value obtained by an Artifact skyvalue lookup to the action input map. May do Skyframe
   * lookups.
   */
  static void addToMap(
      ActionInputMapSink inputMap,
      BiConsumer<Artifact, TreeArtifactValue> treeArtifactConsumer,
      Map<Artifact, FilesetOutputTree> filesetsInsideRunfiles,
      Map<Artifact, FilesetOutputTree> topLevelFilesets,
      Artifact key,
      SkyValue value,
      Environment env,
      MetadataConsumerForMetrics consumer)
      throws InterruptedException {
    if (value instanceof RunfilesArtifactValue runfilesArtifactValue) {
      // Note: we don't expand the .runfiles/MANIFEST file into the inputs. The reason for that
      // being that the MANIFEST file contains absolute paths that don't work with remote execution.
      // Instead, the way the SpawnInputExpander expands runfiles is via the Runfiles class
      // which contains all artifacts in the runfiles tree minus the MANIFEST file.
      runfilesArtifactValue.forEachFile(
          (artifact, metadata) -> {
            inputMap.put(artifact, metadata, /* depOwner= */ key);
            if (artifact.isFileset()) {
              FilesetOutputTree filesetOutput = getFilesetOutput(env, (SpecialArtifact) artifact);
              if (filesetOutput != null) {
                filesetsInsideRunfiles.put(artifact, filesetOutput);
                consumer.accumulate(filesetOutput);
              }
            } else {
              consumer.accumulate(metadata);
            }
          });
      runfilesArtifactValue.forEachTree(
          (treeArtifact, metadata) -> {
            expandTreeArtifactAndPopulateArtifactData(
                treeArtifact, metadata, treeArtifactConsumer, inputMap, /* depOwner= */ key);
            consumer.accumulate(metadata);
          });
      // We have to cache the "digest" of the aggregating value itself, because the action cache
      // checker may want it.
      inputMap.putRunfilesMetadata(key, runfilesArtifactValue, /* depOwner= */ key);
    } else if (value instanceof TreeArtifactValue treeArtifactValue) {
      expandTreeArtifactAndPopulateArtifactData(
          key, treeArtifactValue, treeArtifactConsumer, inputMap, /* depOwner= */ key);
      consumer.accumulate(treeArtifactValue);
    } else if (value instanceof ActionExecutionValue) {
      // Actions resulting from the expansion of an ActionTemplate consume only one of the files
      // in a tree artifact. However, the input prefetcher and the Linux sandbox require access to
      // the tree metadata to determine the prefetch location of a tree artifact materialized as a
      // symlink to another (cf. TreeArtifactValue#getResolvedPath()).
      if (key.isChildOfDeclaredDirectory()) {
        SpecialArtifact treeArtifact = key.getParent();
        TreeArtifactValue treeArtifactValue =
            ((ActionExecutionValue) value).getTreeArtifactValue(treeArtifact);
        expandTreeArtifactAndPopulateArtifactData(
            treeArtifact, treeArtifactValue, treeArtifactConsumer, inputMap, treeArtifact);
        consumer.accumulate(treeArtifactValue);
      }
      FileArtifactValue metadata = ((ActionExecutionValue) value).getExistingFileArtifactValue(key);
      inputMap.put(key, metadata, key);
      if (key.isFileset()) {
        FilesetOutputTree filesetOutput = getFilesetOutput(env, (SpecialArtifact) key);
        if (filesetOutput != null) {
          topLevelFilesets.put(key, filesetOutput);
          consumer.accumulate(filesetOutput);
        }
      } else {
        consumer.accumulate(metadata);
      }
    } else {
      checkArgument(value instanceof FileArtifactValue, "Unexpected value %s", value);
      FileArtifactValue metadata = (FileArtifactValue) value;
      inputMap.put(key, metadata, /*depOwner=*/ key);
      consumer.accumulate(metadata);
    }
  }

  @Nullable
  private static FilesetOutputTree getFilesetOutput(Environment env, SpecialArtifact actionInput)
      throws InterruptedException {
    checkState(actionInput.isFileset(), actionInput);
    ActionLookupData generatingActionKey = actionInput.getGeneratingActionKey();
    ActionLookupKey filesetActionLookupKey = generatingActionKey.getActionLookupKey();

    SkyValue maybeActionLookupValue = env.getValue(filesetActionLookupKey);

    // With Frontier-based Skycache, the SkyValue for the fileset analysis object may be a
    // RemoteConfiguredTargetValue if it's not in the active set, and the remote fileset CT value
    // doesn't contain actions for lookup. That is OK because we're only interested in the fileset
    // output, which is not stored in the actions, but in the (potentially Skycache-cached)
    // ActionExecutionValue.
    //
    // Additionally, `if (generatingAction instanceof SymlinkAction)` block below is also only
    // triggered when building fileset symlinks, which is disabled in a Skycache build.
    //
    // So, for a Skycache build, we can skip the transitive action lookups below and obtain the
    // FilesetOutputTree directory from the Fileset ActionExecutionValue immediately (which should
    // be a Skycache cache hit anyway).
    if (maybeActionLookupValue instanceof RemoteConfiguredTargetValue) {
      return getFilesetOutputFromKey(generatingActionKey, env);
    }

    // Non-Skycache builds from here.
    ActionLookupValue filesetActionLookupValue = (ActionLookupValue) maybeActionLookupValue;
    ActionAnalysisMetadata generatingAction =
        filesetActionLookupValue.getAction(generatingActionKey.getActionIndex());
    ActionLookupData filesetActionKey;

    if (generatingAction instanceof SymlinkAction) {
      DerivedArtifact outputManifest =
          (DerivedArtifact) generatingAction.getInputs().getSingleton();
      ActionLookupData manifestGeneratingKey = outputManifest.getGeneratingActionKey();
      checkState(
          manifestGeneratingKey.getActionLookupKey().equals(filesetActionLookupKey),
          "Mismatched actions and artifacts: %s %s %s %s",
          actionInput,
          outputManifest,
          filesetActionLookupKey,
          manifestGeneratingKey);
      ActionAnalysisMetadata symlinkTreeAction =
          filesetActionLookupValue.getAction(manifestGeneratingKey.getActionIndex());
      DerivedArtifact inputManifest =
          (DerivedArtifact) symlinkTreeAction.getInputs().getSingleton();
      ActionLookupData inputManifestGeneratingKey = inputManifest.getGeneratingActionKey();
      checkState(
          inputManifestGeneratingKey.getActionLookupKey().equals(filesetActionLookupKey),
          "Mismatched actions and artifacts: %s %s %s %s",
          actionInput,
          inputManifest,
          filesetActionLookupKey,
          inputManifestGeneratingKey);
      filesetActionKey = inputManifestGeneratingKey;
    } else {
      filesetActionKey = generatingActionKey;
    }

    return getFilesetOutputFromKey(filesetActionKey, env);
  }

  private static FilesetOutputTree getFilesetOutputFromKey(
      ActionLookupData filesetActionKey, Environment env) throws InterruptedException {
    // TODO: properly handle exceptions coming from here, or prove they can never happen in
    // practice.
    ActionExecutionValue filesetValue = (ActionExecutionValue) env.getValue(filesetActionKey);
    if (filesetValue == null) {
      // At this point skyframe does not guarantee that the filesetValue will be ready, since
      // the current action does not directly depend on the outputs of the
      // SkyframeFilesetManifestAction whose ActionExecutionValue
      // (com.google.devtools.build.lib.skyframe.Fileset) is needed here.
      return null;
    }
    return filesetValue.getFilesetOutput();
  }

  private static void expandTreeArtifactAndPopulateArtifactData(
      Artifact treeArtifact,
      TreeArtifactValue value,
      BiConsumer<Artifact, TreeArtifactValue> treeArtifactConsumer,
      ActionInputMapSink inputMap,
      Artifact depOwner) {
    treeArtifactConsumer.accept(treeArtifact, value);
    inputMap.putTreeArtifact((SpecialArtifact) treeArtifact, value, depOwner);
    addArchivedTreeArtifactMaybe(value, inputMap, depOwner);
  }

  private static void addArchivedTreeArtifactMaybe(
      TreeArtifactValue value,
      ActionInputMapSink inputMap,
      Artifact depOwner) {
    if (value.getArchivedRepresentation().isEmpty()) {
      return;
    }

    ArchivedRepresentation archivedRepresentation = value.getArchivedRepresentation().get();
    inputMap.put(
        archivedRepresentation.archivedTreeFileArtifact(),
        archivedRepresentation.archivedFileValue(),
        depOwner);
  }
}
