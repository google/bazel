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
package com.google.devtools.build.lib.rules.cpp;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyComputer;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionTemplate;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import javax.annotation.Nullable;

/**
 * An {@link ActionTemplate} that expands into {@link LtoBackendAction}s at execution time. Is is
 * similar to {@link com.google.devtools.build.lib.analysis.actions.SpawnActionTemplate}.
 */
public final class LtoBackendActionTemplate extends ActionKeyComputer
    implements ActionTemplate<LtoBackendAction> {
  private final LtoBackendAction.Builder ltoBackendActionbuilder;
  private final CcToolchainVariables buildVariables;

  // An input tree artifact containing the full bitcode. It is never null.
  private final SpecialArtifact fullBitcodeTreeArtifact;

  // An input tree artifact containing ".thinlto.bc" and ".imports" files, generated together with
  // It will be null when this is a shared non-lto backend.
  @Nullable private final SpecialArtifact indexAndImportsTreeArtifact;

  // An output tree artifact that will contain the native objects. In a sibling directory to
  // indexTreeArtifact. The objects will be generated in the same location as defined in the .param
  // file created during the lto indexing step.
  private final SpecialArtifact objectFileTreeArtifact;

  // The corresponding dwoFile if fission is used.
  private final SpecialArtifact dwoFileTreeArtifact;

  private final FeatureConfiguration featureConfiguration;

  private final boolean usePic;

  private final BitcodeFiles bitcodeFiles;

  private final ActionOwner actionOwner;
  private final NestedSet<Artifact> mandatoryInputs;
  private final NestedSet<Artifact> allInputs;

  /**
   * Creates an LtoBackendActionTemplate.
   *
   * @param indexAndImportsTreeArtifact the TreeArtifact that contains .thinlto.bc. and .imports
   *     files.
   * @param fullBitcodeTreeArtifact the TreeArtifact that contains .pic.o files.
   * @param objectFileTreeArtifact the TreeArtifact that contains .pic.o files.
   * @param dwoFileTreeArtifact the TreeArtifact that contains .dwo files.
   * @param featureConfiguration the feature configuration.
   * @param ltoBackendActionbuilder An almost completely configured {@link LtoBackendAction.Builder}
   *     without the input and output files set. It is used as a template to instantiate expanded
   *     {@link LtoBackendAction}s.
   * @param buildVariables the building variables.
   * @param usePic whether to use PIC or not.
   * @param actionOwner the owner of this {@link ActionTemplate}.
   */
  LtoBackendActionTemplate(
      SpecialArtifact indexAndImportsTreeArtifact,
      SpecialArtifact fullBitcodeTreeArtifact,
      SpecialArtifact objectFileTreeArtifact,
      SpecialArtifact dwoFileTreeArtifact,
      FeatureConfiguration featureConfiguration,
      LtoBackendAction.Builder ltoBackendActionbuilder,
      CcToolchainVariables buildVariables,
      boolean usePic,
      BitcodeFiles bitcodeFiles,
      ActionOwner actionOwner) {
    this.ltoBackendActionbuilder = ltoBackendActionbuilder;
    this.buildVariables = buildVariables;
    this.indexAndImportsTreeArtifact = indexAndImportsTreeArtifact;
    this.fullBitcodeTreeArtifact = fullBitcodeTreeArtifact;
    this.objectFileTreeArtifact = objectFileTreeArtifact;
    this.dwoFileTreeArtifact = dwoFileTreeArtifact;
    this.actionOwner = checkNotNull(actionOwner, objectFileTreeArtifact);
    this.featureConfiguration = featureConfiguration;
    this.usePic = usePic;
    this.bitcodeFiles = bitcodeFiles;

    NestedSetBuilder<Artifact> mandatoryInputsBuilder =
        NestedSetBuilder.<Artifact>compileOrder()
            .add(fullBitcodeTreeArtifact)
            .addTransitive(ltoBackendActionbuilder.getInputsAndTools());
    if (indexAndImportsTreeArtifact != null) {
      mandatoryInputsBuilder.add(indexAndImportsTreeArtifact);
    }
    this.mandatoryInputs = mandatoryInputsBuilder.build();
    this.allInputs = mandatoryInputs;
  }

  /** Helper functions for generateActionsForInputArtifacts */
  private String pathFragmentToRelativePath(PathFragment parentPath, PathFragment path) {
    return path.relativeTo(parentPath).getSafePathString();
  }

  private String removeImportsExtension(String path) {
    return FileSystemUtils.removeExtension(path);
  }

  private String removeThinltoBcExtension(String path) {
    return FileSystemUtils.removeExtension(FileSystemUtils.removeExtension(path));
  }

  @Nullable
  private TreeFileArtifact generateDwoArtifact(
      String fullBitcodeRelativePath, ActionLookupKey artifactOwner) {
    if (dwoFileTreeArtifact != null) {
      return TreeFileArtifact.createTemplateExpansionOutput(
          dwoFileTreeArtifact,
          FileSystemUtils.replaceExtension(PathFragment.create(fullBitcodeRelativePath), ".dwo"),
          artifactOwner);
    } else {
      return null;
    }
  }

  private TreeFileArtifact generateOutputObjArtifact(
      String fullBitcodeRelativePath, ActionLookupKey artifactOwner) {
    return TreeFileArtifact.createTemplateExpansionOutput(
        objectFileTreeArtifact, fullBitcodeRelativePath, artifactOwner);
  }

  /**
   * Generates actions for the input artifacts.
   *
   * <p>If indexAndImportsTreeArtifact is null, {@link getInputTreeArtifact} will return the
   * fullBitcodeTreeArtifact and the input artifacts for this action template will be the
   * fullBitcodeTreeFileArtifacts otherwise it will be the indexAndImportsTreeFileArtifacts.
   *
   * <p>We use indexAndImportsTreeFileArtifact when possible instead of making both cases expand the
   * fullBitcodeTreeFileArtifacts so we can split the contents of indexAndImportsTreeFileArtifact
   * into index files and import files, and use it to give better error messages.
   */
  @Override
  public ImmutableList<LtoBackendAction> generateActionsForInputArtifacts(
      ImmutableSet<TreeFileArtifact> inputTreeFileArtifacts, ActionLookupKey artifactOwner)
      throws ActionExecutionException {
    if (indexAndImportsTreeArtifact != null) {
      return generateActionsForLtoArtifacts(inputTreeFileArtifacts, artifactOwner);
    } else {
      return generateActionsForNonLtoArtifacts(inputTreeFileArtifacts, artifactOwner);
    }
  }

  /**
   * When generating actions for the shared nonlto backend, we do not use
   * indexAndImportsTreeArtifact, instead we only use the fullBitcodeTreeArtifact files.
   */
  private ImmutableList<LtoBackendAction> generateActionsForNonLtoArtifacts(
      ImmutableSet<TreeFileArtifact> fullBitcodeTreeFileArtifacts, ActionLookupKey artifactOwner) {

    ImmutableList.Builder<LtoBackendAction> expandedActions =
        ImmutableList.builderWithExpectedSize(fullBitcodeTreeFileArtifacts.size());
    PathFragment fullBitcodeParentPath = fullBitcodeTreeArtifact.getExecPath();
    for (TreeFileArtifact inputTreeFileArtifact : fullBitcodeTreeFileArtifacts) {
      PathFragment path = inputTreeFileArtifact.getExecPath();
      String fullBitcodeRelativePath = pathFragmentToRelativePath(fullBitcodeParentPath, path);
      TreeFileArtifact objTreeFileArtifact =
          generateOutputObjArtifact(fullBitcodeRelativePath, artifactOwner);
      TreeFileArtifact dwoFileArtifact =
          generateDwoArtifact(fullBitcodeRelativePath, artifactOwner);
      LtoBackendAction.Builder builderCopy = new LtoBackendAction.Builder(ltoBackendActionbuilder);

      LtoBackendArtifacts.addArtifactsLtoBackendAction(
          builderCopy,
          buildVariables,
          featureConfiguration,
          /* index= */ null,
          /* imports= */ null,
          inputTreeFileArtifact,
          objTreeFileArtifact,
          bitcodeFiles,
          dwoFileArtifact,
          usePic,
          /* bitcodeFilePath= */ null,
          /* isDummyAction= */ false);
      expandedActions.add((LtoBackendAction) builderCopy.buildForActionTemplate(actionOwner));
    }

    return expandedActions.build();
  }

  /**
   * Given all the files inside indexAndImportsTreeArtifact, we find the corresponding index and
   * imports files. Then we use their path together with the fullBitcodeTreeArtifact path to derive
   * the path of the original full bitcode file. Then for each imports file, we create an lto
   * backend action that depends on that import file, on the corresponding index file, and on the
   * whole fullBitcodeTreeArtifact, which it uses to find the full bitcode file. TODO(antunesi):
   * make the generated action depend only on the corresponding full bitcode file rather than depend
   * on the whole tree artifact that contains the full bitcode file.
   */
  private ImmutableList<LtoBackendAction> generateActionsForLtoArtifacts(
      ImmutableSet<TreeFileArtifact> indexAndImportsTreeFileArtifacts,
      ActionLookupKey artifactOwner)
      throws ActionExecutionException {
    ImmutableList.Builder<LtoBackendAction> expandedActions = new ImmutableList.Builder<>();

    final FileType thinltoBcSourceType = CppFileTypes.LTO_INDEXING_ANALYSIS_FILE;
    final FileType importsType = CppFileTypes.LTO_IMPORTS_FILE;

    ImmutableList.Builder<TreeFileArtifact> importsBuilder = ImmutableList.builder();
    ImmutableMap.Builder<String, TreeFileArtifact> nameToThinLtoBuilder =
        new ImmutableMap.Builder<>();

    PathFragment indexAndImportParentPath = indexAndImportsTreeArtifact.getExecPath();

    for (TreeFileArtifact inputTreeFileArtifact : indexAndImportsTreeFileArtifacts) {
      PathFragment path = inputTreeFileArtifact.getExecPath();
      boolean isThinLto = thinltoBcSourceType.matches(path);
      boolean isImport = importsType.matches(path);

      if (isThinLto) {
        String thinLtoNoExtension =
            removeThinltoBcExtension(pathFragmentToRelativePath(indexAndImportParentPath, path));
        nameToThinLtoBuilder.put(thinLtoNoExtension, inputTreeFileArtifact);
      } else if (isImport) {
        importsBuilder.add(inputTreeFileArtifact);
      } else {
        String message =
            String.format(
                "Artifact '%s' expanded from the directory artifact '%s' is neither imports nor"
                    + " thinlto .",
                inputTreeFileArtifact.getExecPathString(),
                fullBitcodeTreeArtifact.getExecPathString()); // kinda wrong, should be index
        throw new ActionExecutionException(
            message, this, /* catastrophe= */ false, makeDetailedExitCode(message));
      }
    }

    // Maps each imports to a .bc file
    ImmutableList<TreeFileArtifact> imports = importsBuilder.build();
    ImmutableMap<String, TreeFileArtifact> nameToThinLto = nameToThinLtoBuilder.buildOrThrow();
    if (imports.size() != nameToThinLto.size()) {
      String message =
          String.format(
              "Either both or neither bitcodeFiles and imports files should be null. %s %s" + ".",
              indexAndImportsTreeFileArtifacts,
              fullBitcodeTreeArtifact.getExecPathString()); // kinda wrong, should be index
      throw new ActionExecutionException(
          message, this, /* catastrophe= */ false, makeDetailedExitCode(message));
    }

    for (TreeFileArtifact importFile : imports) {
      PathFragment path = importFile.getExecPath();
      // The relative path of the fullBitcodeFile with respect to the fullBitcodeTreeArtifact
      String fullBitcodeRelativePath =
          removeImportsExtension(pathFragmentToRelativePath(indexAndImportParentPath, path));
      TreeFileArtifact thinLtoFile = nameToThinLto.get(fullBitcodeRelativePath);
      PathFragment fullBitcodePath =
          fullBitcodeTreeArtifact.getExecPath().getRelative(fullBitcodeRelativePath);
      TreeFileArtifact objTreeFileArtifact =
          generateOutputObjArtifact(fullBitcodeRelativePath, artifactOwner);
      TreeFileArtifact dwoFileArtifact =
          generateDwoArtifact(fullBitcodeRelativePath, artifactOwner);

      LtoBackendAction.Builder builderCopy = new LtoBackendAction.Builder(ltoBackendActionbuilder);

      LtoBackendArtifacts.addArtifactsLtoBackendAction(
          builderCopy,
          buildVariables,
          featureConfiguration,
          thinLtoFile,
          importFile,
          fullBitcodeTreeArtifact,
          objTreeFileArtifact,
          bitcodeFiles,
          dwoFileArtifact,
          usePic,
          fullBitcodePath.toString(),
          /* isDummyAction= */ false);
      expandedActions.add((LtoBackendAction) builderCopy.buildForActionTemplate(actionOwner));
    }

    return expandedActions.build();
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      Fingerprint fp)
      throws CommandLineExpansionException, InterruptedException {

    LtoBackendAction dummyAction = getDummyAction();
    dummyAction.computeKey(actionKeyContext, artifactExpander, fp);
  }

  /**
   * This is an action that is not valid, because its input bitcode file is a TreeArtifact rather
   * than a specific file. It is useful for calculating keys and inputs of the Action Template by
   * reusing functionality from LtoBackendAction.
   */
  private LtoBackendAction getDummyAction() {
    LtoBackendAction.Builder builderCopy = new LtoBackendAction.Builder(ltoBackendActionbuilder);
    // This is a dummy action that would not work, because the bitcode file path is a directory
    // rather than a file.
    LtoBackendArtifacts.addArtifactsLtoBackendAction(
        builderCopy,
        buildVariables,
        featureConfiguration,
        indexAndImportsTreeArtifact,
        indexAndImportsTreeArtifact,
        fullBitcodeTreeArtifact,
        objectFileTreeArtifact,
        bitcodeFiles,
        dwoFileTreeArtifact,
        usePic,
        null,
        /* isDummyAction= */ true);

    return (LtoBackendAction) builderCopy.buildForActionTemplate(actionOwner);
  }

  /**
   * Returns the input tree artifact.
   *
   * <p>If indexAndImportsTreeArtifact is null then we are using a shared nonlto backend, and
   * therefore we should only use the full bitcode files, instead of getting the files from
   * indexAndImportsTreeArtifact.
   */
  @Override
  public SpecialArtifact getInputTreeArtifact() {
    return firstNonNull(indexAndImportsTreeArtifact, fullBitcodeTreeArtifact);
  }

  @Override
  public SpecialArtifact getOutputTreeArtifact() {
    return objectFileTreeArtifact;
  }

  @Override
  public ActionOwner getOwner() {
    return actionOwner;
  }

  @Override
  public boolean isShareable() {
    return false;
  }

  @Override
  public String getMnemonic() {
    return "LtoBackendActionTemplate";
  }

  @Override
  public NestedSet<Artifact> getMandatoryInputs() {
    return mandatoryInputs;
  }

  @Override
  public NestedSet<Artifact> getInputFilesForExtraAction(
      ActionExecutionContext actionExecutionContext) {
    return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  }

  @Override
  public ImmutableSet<Artifact> getMandatoryOutputs() {
    return ImmutableSet.of();
  }

  @Override
  public NestedSet<Artifact> getTools() {
    return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  }

  @Override
  public NestedSet<Artifact> getInputs() {
    return allInputs;
  }

  @Override
  public NestedSet<Artifact> getOriginalInputs() {
    return allInputs;
  }

  @Override
  public ImmutableSet<Artifact> getOutputs() {
    ImmutableSet.Builder<Artifact> builder = ImmutableSet.builder();
    builder.add(objectFileTreeArtifact);
    if (dwoFileTreeArtifact != null) {
      builder.add(dwoFileTreeArtifact);
    }
    return builder.build();
  }

  @Override
  public ImmutableList<String> getClientEnvironmentVariables() {
    return ImmutableList.of();
  }

  @Override
  public NestedSet<Artifact> getSchedulingDependencies() {
    return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  }

  @Override
  public String prettyPrint() {
    return "LtoBackendActionTemplate compiling " + fullBitcodeTreeArtifact.getExecPathString();
  }

  @Override
  public String describe() {
    return "Lto backend compiling all C++ files in " + fullBitcodeTreeArtifact.prettyPrint();
  }

  @Override
  public String toString() {
    return prettyPrint();
  }

  private static DetailedExitCode makeDetailedExitCode(String message) {
    return DetailedExitCode.of(
        FailureDetails.FailureDetail.newBuilder()
            .setMessage(message)
            .setExecution(
                FailureDetails.Execution.newBuilder()
                    .setCode(
                        FailureDetails.Execution.Code
                            .PERSISTENT_ACTION_OUTPUT_DIRECTORY_CREATION_FAILURE))
            .build());
  }
}
