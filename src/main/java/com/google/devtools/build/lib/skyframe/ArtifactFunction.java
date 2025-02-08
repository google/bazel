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
package com.google.devtools.build.lib.skyframe;

import static com.google.devtools.build.lib.skyframe.SkyValueRetrieverUtils.maybeFetchSkyValueRemotely;
import static com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.INITIAL_STATE;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.ActionTemplate;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.actions.FilesetTraversalParams.DirectTraversalRoot;
import com.google.devtools.build.lib.actions.RunfilesArtifactValue;
import com.google.devtools.build.lib.actions.RunfilesTreeAction;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.server.FailureDetails.Execution;
import com.google.devtools.build.lib.server.FailureDetails.Execution.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.ActionTemplateExpansionValue.ActionTemplateExpansionKey;
import com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException;
import com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.ResolvedFile;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.RetrievalResult;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.SerializableSkyKeyComputeState;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.SerializationState;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingDependenciesProvider;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.DetailedIOException;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.XattrProvider;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * A builder of values for {@link Artifact} keys when the key is not a simple generated artifact. To
 * save memory, ordinary generated artifacts (non-runfiles, non-tree) have their metadata accessed
 * directly from the corresponding {@link ActionExecutionValue}. This SkyFunction is therefore only
 * usable for source, runfiles trees and tree artifacts.
 */
public final class ArtifactFunction implements SkyFunction {

  private final Supplier<Boolean> mkdirForTreeArtifacts;
  private final MetadataConsumerForMetrics sourceArtifactsSeen;
  private final XattrProvider xattrProvider;
  private final Supplier<RemoteAnalysisCachingDependenciesProvider> cachingDependenciesSupplier;

  /** A {@link SkyValue} representing a missing input file. */
  public static final class MissingArtifactValue implements SkyValue {
    private final DetailedExitCode detailedExitCode;

    private MissingArtifactValue(Artifact missingArtifact) {
      FailureDetail failureDetail =
          FailureDetail.newBuilder()
              .setMessage(constructErrorMessage(missingArtifact, "missing input file"))
              .setExecution(Execution.newBuilder().setCode(Code.SOURCE_INPUT_MISSING))
              .build();
      this.detailedExitCode = DetailedExitCode.of(failureDetail);
    }

    DetailedExitCode getDetailedExitCode() {
      return detailedExitCode;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("detailedExitCode", detailedExitCode).toString();
    }
  }

  private static class State implements SerializableSkyKeyComputeState {
    private SerializationState serializationState = INITIAL_STATE;

    @Override
    public SerializationState getSerializationState() {
      return serializationState;
    }

    @Override
    public void setSerializationState(SerializationState state) {
      this.serializationState = state;
    }
  }

  public ArtifactFunction(
      Supplier<Boolean> mkdirForTreeArtifacts,
      MetadataConsumerForMetrics sourceArtifactsSeen,
      XattrProvider xattrProvider,
      Supplier<RemoteAnalysisCachingDependenciesProvider> cachingDependenciesSupplier) {
    this.mkdirForTreeArtifacts = mkdirForTreeArtifacts;
    this.sourceArtifactsSeen = sourceArtifactsSeen;
    this.xattrProvider = xattrProvider;
    this.cachingDependenciesSupplier = cachingDependenciesSupplier;
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws ArtifactFunctionException, InterruptedException {
    Artifact artifact = (Artifact) skyKey;

    if (artifact.getArtifactOwner().getLabel() != null) {
      RetrievalResult retrievalResult =
          maybeFetchSkyValueRemotely(artifact, env, cachingDependenciesSupplier.get(), State::new);
      switch (retrievalResult) {
        case SkyValueRetriever.Restart unused:
          return null;
        case SkyValueRetriever.RetrievedValue v:
          return v.value();
        case SkyValueRetriever.NoCachedData unused:
          break;
      }
    }

    if (!artifact.hasKnownGeneratingAction()) {
      // If the artifact has no known generating action, it is a source artifact.
      return createSourceValue(artifact, env);
    }
    Artifact.DerivedArtifact derivedArtifact = (DerivedArtifact) artifact;

    ArtifactDependencies artifactDependencies =
        ArtifactDependencies.discoverDependencies(derivedArtifact, env);
    if (artifactDependencies == null) {
      return null;
    }

    // If the action is an ActionTemplate, we need to expand the ActionTemplate into concrete
    // actions, execute those actions in parallel and then aggregate the action execution results.
    ActionTemplate<?> actionTemplate = artifactDependencies.maybeGetTemplateActionForTreeArtifact();
    if (actionTemplate != null) {
      if (mkdirForTreeArtifacts.get()) {
        mkdirForTreeArtifact(artifact, env, actionTemplate);
      }
      return createTreeArtifactValueFromActionKey(artifactDependencies, env);
    }

    ActionLookupData generatingActionKey = derivedArtifact.getGeneratingActionKey();
    ActionExecutionValue actionValue = (ActionExecutionValue) env.getValue(generatingActionKey);
    if (actionValue == null) {
      return null;
    }

    if (artifact.isTreeArtifact()) {
      // We got a request for the whole tree artifact. We can just return the associated
      // TreeArtifactValue.
      return Preconditions.checkNotNull(actionValue.getTreeArtifactValue(artifact), artifact);
    }

    Preconditions.checkState(artifact.isRunfilesTree(), artifact);
    Action action =
        Preconditions.checkNotNull(
            artifactDependencies.actionLookupValue.getAction(generatingActionKey.getActionIndex()),
            "Null runfiles tree action? %s",
            artifactDependencies);

    return createRunfilesArtifactValue(artifact, (RunfilesTreeAction) action, env);
  }

  private static void mkdirForTreeArtifact(
      Artifact artifact, Environment env, ActionTemplate<?> actionForFailure)
      throws ArtifactFunctionException {
    try {
      artifact.getPath().createDirectoryAndParents();
    } catch (IOException e) {
      String errorMessage =
          String.format(
              "Failed to create output directory for TreeArtifact %s: %s",
              artifact.getExecPath(), e.getMessage());
      env.getListener()
          .handle(Event.error(actionForFailure.getOwner().getLocation(), errorMessage));
      // We could throw this as an IOException and expect our callers to catch and reprocess it,
      // but we know the action at fault, so we should be in charge.
      DetailedExitCode code =
          DetailedExitCode.of(
              FailureDetail.newBuilder()
                  .setMessage(errorMessage)
                  .setExecution(
                      Execution.newBuilder().setCode(Code.TREE_ARTIFACT_DIRECTORY_CREATION_FAILURE))
                  .build());
      throw new ArtifactFunctionException(
          new ActionExecutionException(errorMessage, e, actionForFailure, false, code));
    }
  }

  @SuppressWarnings("LenientFormatStringValidation")
  @Nullable
  private static TreeArtifactValue createTreeArtifactValueFromActionKey(
      ArtifactDependencies artifactDependencies, Environment env) throws InterruptedException {
    // Request the list of expanded action keys from the ActionTemplate.
    ImmutableList<ActionLookupData> expandedActionExecutionKeys =
        artifactDependencies.getActionTemplateExpansionKeys(env);
    if (expandedActionExecutionKeys == null) {
      return null; // The expanded actions are not yet available.
    }

    SkyframeLookupResult expandedActionValues =
        env.getValuesAndExceptions(expandedActionExecutionKeys);
    if (env.valuesMissing()) {
      return null; // The execution values of the expanded actions are not yet all available.
    }

    // Aggregate the metadata for individual TreeFileArtifacts into a TreeArtifactValue for the
    // parent TreeArtifact.
    SpecialArtifact parent = (SpecialArtifact) artifactDependencies.artifact;
    TreeArtifactValue.Builder treeBuilder = TreeArtifactValue.newBuilder(parent);

    for (ActionLookupData actionKey : expandedActionExecutionKeys) {
      boolean sawTreeChild = false;
      ActionExecutionValue actionExecutionValue =
          (ActionExecutionValue) expandedActionValues.get(actionKey);
      if (actionExecutionValue == null) {
        return null;
      }

      for (Map.Entry<Artifact, FileArtifactValue> entry :
          actionExecutionValue.getAllFileValues().entrySet()) {
        Artifact artifact = entry.getKey();
        Preconditions.checkState(
            artifact.hasParent(),
            "Parentless artifact %s found in ActionExecutionValue for %s: %s %s",
            artifact,
            actionKey,
            actionExecutionValue,
            artifactDependencies);

        if (artifact.getParent().equals(parent)) {
          sawTreeChild = true;
          treeBuilder.putChild((TreeFileArtifact) artifact, entry.getValue());
        }
      }

      Preconditions.checkState(
          sawTreeChild,
          "Action denoted by %s does not output any TreeFileArtifacts from %s",
          actionKey,
          artifactDependencies);
    }

    TreeArtifactValue tree = treeBuilder.build();
    return tree;
  }

  @Nullable
  private SkyValue createSourceValue(Artifact artifact, Environment env)
      throws InterruptedException, ArtifactFunctionException {
    RootedPath path = RootedPath.toRootedPath(artifact.getRoot().getRoot(), artifact.getPath());
    SkyKey fileSkyKey = FileValue.key(path);
    FileValue fileValue;
    try {
      fileValue = (FileValue) env.getValueOrThrow(fileSkyKey, IOException.class);
    } catch (DetailedIOException e) {
      throw new ArtifactFunctionException(
          SourceArtifactException.createdDetailed(artifact, e), Transience.PERSISTENT);
    } catch (IOException e) {
      throw new ArtifactFunctionException(
          SourceArtifactException.create(artifact, e), Transience.PERSISTENT);
    }
    if (fileValue == null) {
      return null;
    }
    if (!fileValue.exists()) {
      return new MissingArtifactValue(artifact);
    }

    if (fileValue.isDirectory()) {
      env.getListener().post(SourceDirectoryEvent.create(artifact.getExecPath()));
    }

    if (!fileValue.isDirectory() || !TrackSourceDirectoriesFlag.trackSourceDirectories()) {
      FileArtifactValue metadata;
      try {
        metadata = FileArtifactValue.createForSourceArtifact(artifact, fileValue, xattrProvider);
      } catch (IOException e) {
        throw new ArtifactFunctionException(
            SourceArtifactException.create(artifact, e), Transience.TRANSIENT);
      }
      sourceArtifactsSeen.accumulate(metadata);
      return metadata;
    }
    // For directory artifacts that are not Filesets, we initiate a directory traversal here, and
    // compute a hash from the directory structure.
    // We rely on the guarantees of RecursiveFilesystemTraversalFunction for correctness.
    //
    // This approach may have unexpected interactions with --package_path. In particular, the exec
    // root is set up from the loading / analysis phase, and it is now too late to change it;
    // therefore, this may traverse a different set of files depending on which targets are built
    // at the same time and what the package-path layout is (this may be moot if there is only one
    // entry). Or this may return a set of files that's inconsistent with those actually available
    // to the action (for local execution).
    //
    // In the future, we need to make this result the source of truth for the files available to
    // the action so that we at least have consistency.
    TraversalRequest request =
        DirectoryArtifactTraversalRequest.create(
            DirectTraversalRoot.forRootedPath(path), /*skipTestingForSubpackage=*/ true, artifact);
    RecursiveFilesystemTraversalValue value;
    try {
      value =
          (RecursiveFilesystemTraversalValue)
              env.getValueOrThrow(request, RecursiveFilesystemTraversalException.class);
    } catch (RecursiveFilesystemTraversalException e) {
      // Use a switch to guarantee that if a new type is added, this stops compiling.
      switch (e.getType()) {
        case DANGLING_SYMLINK:
        case FILE_OPERATION_FAILURE:
        case SYMLINK_CYCLE_OR_INFINITE_EXPANSION:
        case CANNOT_TRAVERSE_SOURCE_DIRECTORY:
          throw new ArtifactFunctionException(
              SourceArtifactException.create(artifact, e), Transience.PERSISTENT);
        case INCONSISTENT_FILESYSTEM:
          throw new ArtifactFunctionException(
              SourceArtifactException.create(artifact, e), Transience.TRANSIENT);
        case CANNOT_CROSS_PACKAGE_BOUNDARY:
          throw new IllegalStateException(
              String.format(
                  "Cannot cross package boundary: %s %s %s", artifact, fileValue, request),
              e);
        case GENERATED_PATH_CONFLICT:
          throw new IllegalStateException(
              String.format(
                  "Generated conflict in source tree: %s %s %s", artifact, fileValue, request),
              e);
        // TODO: b/7075837 - This code path is possible when BAZEL_TRACK_SOURCE_DIRECTORIES is set.
        case DETAILED_IO_EXCEPTION:
          throw new IllegalStateException(
              String.format(
                  "%s: %s %s %s", e.getCause().getMessage(), artifact, fileValue, request),
              e);
      }
      throw new IllegalStateException("Can't get here", e);
    }
    if (value == null) {
      return null;
    }
    Fingerprint fp = new Fingerprint();
    for (ResolvedFile file : value.getTransitiveFiles().toList()) {
      fp.addString(file.getNameInSymlinkTree().getPathString());
      fp.addBytes(file.getMetadata().getDigest());
    }
    return FileArtifactValue.createForDirectoryWithHash(fp.digestAndReset());
  }

  @Nullable
  private static RunfilesArtifactValue createRunfilesArtifactValue(
      Artifact artifact, RunfilesTreeAction action, SkyFunction.Environment env)
      throws InterruptedException {
    ImmutableList<Artifact> inputs = action.getInputs().toList();
    SkyframeLookupResult values = env.getValuesAndExceptions(Artifact.keys(inputs));
    if (env.valuesMissing()) {
      return null;
    }

    ImmutableList.Builder<Artifact> files = ImmutableList.builder();
    ImmutableList.Builder<FileArtifactValue> fileValues = ImmutableList.builder();
    ImmutableList.Builder<Artifact> trees = ImmutableList.builder();
    ImmutableList.Builder<TreeArtifactValue> treeValues = ImmutableList.builder();

    // Sort for better equality in RunfilesArtifactValue.
    ImmutableList<Artifact> sortedInputs =
        ImmutableList.sortedCopyOf(Artifact.EXEC_PATH_COMPARATOR, inputs);
    for (Artifact input : sortedInputs) {
      SkyValue inputValue = values.get(Artifact.key(input));
      if (inputValue == null) {
        return null;
      }
      if (inputValue instanceof FileArtifactValue) {
        files.add(input);
        fileValues.add((FileArtifactValue) inputValue);
      } else if (inputValue instanceof ActionExecutionValue) {
        files.add(input);
        fileValues.add(((ActionExecutionValue) inputValue).getExistingFileArtifactValue(input));
      } else if (inputValue instanceof TreeArtifactValue) {
        trees.add(input);
        treeValues.add((TreeArtifactValue) inputValue);
      } else {
        // We do not recurse into runfiles tree artifacts.
        Preconditions.checkState(
            !(inputValue instanceof RunfilesArtifactValue),
            "%s %s %s",
            artifact,
            action,
            inputValue);
      }
    }

    return new RunfilesArtifactValue(
        action.getRunfilesTree(),
        files.build(),
        fileValues.build(),
        trees.build(),
        treeValues.build());
  }

  @Override
  public String extractTag(SkyKey skyKey) {
    return Label.print(((Artifact) skyKey).getOwner());
  }

  @Nullable
  static ActionLookupValue getActionLookupValue(
      ActionLookupKey actionLookupKey, SkyFunction.Environment env) throws InterruptedException {
    ActionLookupValue value = (ActionLookupValue) env.getValue(actionLookupKey);
    if (value == null) {
      Preconditions.checkState(
          actionLookupKey == CoverageReportValue.COVERAGE_REPORT_KEY,
          "Not-yet-present artifact owner: %s",
          actionLookupKey.getCanonicalName());
      return null;
    }
    return value;
  }

  private static final class ArtifactFunctionException extends SkyFunctionException {
    ArtifactFunctionException(ActionExecutionException e) {
      super(e, Transience.TRANSIENT);
    }

    ArtifactFunctionException(SourceArtifactException e, Transience transience) {
      super(e, transience);
    }
  }

  private static String constructErrorMessage(Artifact artifact, String error) {
    Label ownerLabel = artifact.getOwner();
    if (ownerLabel == null || ownerLabel.getName().equals(".")) {
      // Discovered inputs may not have an owner. Directory source artifacts may be owned by a label
      // ':.' which will crash toPathFragment below.
      return String.format("%s '%s'", error, artifact.getExecPathString());
    }

    PathFragment labelFragment = ownerLabel.toPathFragment();
    if (ownerLabel.getRepository().isMain()) {
      if (labelFragment.equals(artifact.getExecPath())) {
        // No additional useful information from path.
        return String.format("%s '%s'", error, ownerLabel);
      }
    } else {
      // Not worth threading sibling repository layout config value all the way here: if either
      // match, we know the label isn't useful.
      for (boolean siblingRepositoryLayout : ImmutableList.of(Boolean.FALSE, Boolean.TRUE)) {
        if (ownerLabel
            .getRepository()
            .getExecPath(siblingRepositoryLayout)
            .getRelative(labelFragment)
            .equals(artifact.getExecPath())) {
          return String.format("%s '%s'", error, ownerLabel);
        }
      }
    }

    // TODO(bazel-team): when is this hit?
    BugReport.sendBugReport(
        new IllegalStateException("Unexpected special owner? " + artifact + ", " + ownerLabel));
    return String.format("%s '%s', owner: '%s'", error, artifact.getExecPathString(), ownerLabel);
  }

  /** Describes dependencies of derived artifacts. */
  public static final class ArtifactDependencies {
    private final DerivedArtifact artifact;
    private final ActionLookupValue actionLookupValue;

    private ArtifactDependencies(DerivedArtifact artifact, ActionLookupValue actionLookupValue) {
      this.artifact = artifact;
      this.actionLookupValue = actionLookupValue;
    }

    /**
     * Constructs an {@link ArtifactDependencies} for the provided {@code derivedArtifact}. Returns
     * {@code null} if any dependencies are not yet ready.
     */
    @Nullable
    public static ArtifactDependencies discoverDependencies(
        Artifact.DerivedArtifact derivedArtifact, SkyFunction.Environment env)
        throws InterruptedException {

      ActionLookupData generatingActionKey = derivedArtifact.getGeneratingActionKey();
      ActionLookupValue actionLookupValue =
          ArtifactFunction.getActionLookupValue(generatingActionKey.getActionLookupKey(), env);
      if (actionLookupValue == null) {
        return null;
      }

      return new ArtifactDependencies(derivedArtifact, actionLookupValue);
    }

    public boolean isTemplateActionForTreeArtifact() {
      return maybeGetTemplateActionForTreeArtifact() != null;
    }

    @Nullable
    ActionTemplate<?> maybeGetTemplateActionForTreeArtifact() {
      if (!artifact.isTreeArtifact()) {
        return null;
      }
      ActionAnalysisMetadata result =
          actionLookupValue.getActions().get(artifact.getGeneratingActionKey().getActionIndex());
      return result instanceof ActionTemplate ? (ActionTemplate<?>) result : null;
    }

    /**
     * Returns action template expansion keys or {@code null} if that information is unavailable.
     *
     * <p>Must only be called if {@link #isTemplateActionForTreeArtifact} returns {@code true}.
     */
    @Nullable
    public ImmutableList<ActionLookupData> getActionTemplateExpansionKeys(
        SkyFunction.Environment env) throws InterruptedException {
      Preconditions.checkState(
          isTemplateActionForTreeArtifact(), "Action is unexpectedly non-template: %s", this);
      ActionTemplateExpansionKey key =
          ActionTemplateExpansionValue.key(
              artifact.getArtifactOwner(), artifact.getGeneratingActionKey().getActionIndex());
      ActionTemplateExpansionValue value = (ActionTemplateExpansionValue) env.getValue(key);
      if (value == null) {
        return null;
      }
      ImmutableList.Builder<ActionLookupData> expandedActionExecutionKeys =
          ImmutableList.builderWithExpectedSize(value.getNumActions());
      for (ActionAnalysisMetadata action : value.getActions()) {
        expandedActionExecutionKeys.add(
            ((DerivedArtifact) action.getPrimaryOutput()).getGeneratingActionKey());
      }
      return expandedActionExecutionKeys.build();
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("artifact", artifact)
          .add("generatingActionKey", artifact.getGeneratingActionKey())
          .add("actionLookupValue", actionLookupValue)
          .toString();
    }
  }
  /** An {@link Exception} thrown representing a source input {@link IOException}. */
  public static final class SourceArtifactException extends Exception implements DetailedException {
    private final DetailedExitCode detailedExitCode;

    private SourceArtifactException(DetailedExitCode detailedExitCode, Exception e) {
      super(detailedExitCode.getFailureDetail().getMessage(), e);
      this.detailedExitCode = detailedExitCode;
    }

    private static SourceArtifactException create(Artifact artifact, IOException e) {
      DetailedExitCode detailedExitCode =
          DetailedExitCode.of(
              FailureDetail.newBuilder()
                  .setMessage(
                      constructErrorMessage(artifact, "error reading file") + ": " + e.getMessage())
                  .setExecution(Execution.newBuilder().setCode(Code.SOURCE_INPUT_IO_EXCEPTION))
                  .build());
      return new SourceArtifactException(detailedExitCode, e);
    }

    private static SourceArtifactException createdDetailed(
        Artifact artifact, DetailedIOException e) {
      FailureDetail failureDetailWithUpdatedErrorMessage =
          e.getDetailedExitCode().getFailureDetail().toBuilder()
              .setMessage(
                  constructErrorMessage(
                      artifact,
                      "error reading file: "
                          + e.getDetailedExitCode().getFailureDetail().getMessage()))
              .build();
      return new SourceArtifactException(
          DetailedExitCode.of(failureDetailWithUpdatedErrorMessage), e);
    }

    private static SourceArtifactException create(
        Artifact artifact, RecursiveFilesystemTraversalException e) {
      FailureDetail failureDetail =
          FailureDetail.newBuilder()
              .setMessage(
                  constructErrorMessage(artifact, "error traversing directory")
                      + ": "
                      + e.getMessage())
              .setExecution(Execution.newBuilder().setCode(Code.SOURCE_INPUT_IO_EXCEPTION))
              .build();
      return new SourceArtifactException(DetailedExitCode.of(failureDetail), e);
    }

    @Override
    public DetailedExitCode getDetailedExitCode() {
      return detailedExitCode;
    }
  }

  /**
   * Key for depending on all files under a source directory. Only requested when {@linkplain
   * TrackSourceDirectoriesFlag tracking source directories}.
   */
  public static final class DirectoryArtifactTraversalRequest extends TraversalRequest {

    private static final SkyKeyInterner<DirectoryArtifactTraversalRequest> interner =
        SkyKey.newInterner();

    private static DirectoryArtifactTraversalRequest create(
        DirectTraversalRoot root, boolean skipTestingForSubpackage, Artifact artifact) {
      return interner.intern(
          new DirectoryArtifactTraversalRequest(root, skipTestingForSubpackage, artifact));
    }

    private final DirectTraversalRoot root;
    private final boolean skipTestingForSubpackage;
    private final Artifact artifact;

    private DirectoryArtifactTraversalRequest(
        DirectTraversalRoot root, boolean skipTestingForSubpackage, Artifact artifact) {
      this.root = root;
      this.skipTestingForSubpackage = skipTestingForSubpackage;
      this.artifact = artifact;
    }

    @Override
    public DirectTraversalRoot root() {
      return root;
    }

    @Override
    protected boolean isRootGenerated() {
      return false;
    }

    @Override
    protected boolean strictOutputFiles() {
      return true;
    }

    @Override
    protected boolean skipTestingForSubpackage() {
      return skipTestingForSubpackage;
    }

    @Override
    protected boolean emitEmptyDirectoryNodes() {
      return true;
    }

    @Override
    protected String errorInfo() {
      return "Directory artifact " + artifact.prettyPrint();
    }

    @Override
    protected TraversalRequest duplicateWithOverrides(
        DirectTraversalRoot newRoot, boolean newSkipTestingForSubpackage) {
      return create(newRoot, newSkipTestingForSubpackage, artifact);
    }

    @Override
    public SkyKeyInterner<DirectoryArtifactTraversalRequest> getSkyKeyInterner() {
      return interner;
    }

    @Override
    public int hashCode() {
      // Artifact is only for error info and not considered in hash code or equality.
      return root.hashCode() * 31 + Boolean.hashCode(skipTestingForSubpackage);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof DirectoryArtifactTraversalRequest other)) {
        return false;
      }
      // Artifact is only for error info and not considered in hash code or equality.
      return root.equals(other.root) && skipTestingForSubpackage == other.skipTestingForSubpackage;
    }
  }
}
