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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputMap;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher.Priority;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher.Reason;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.CompletionContext;
import com.google.devtools.build.lib.actions.CompletionContext.PathResolverFactory;
import com.google.devtools.build.lib.actions.EventReportingArtifacts;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FilesetOutputTree;
import com.google.devtools.build.lib.actions.ImportantOutputHandler;
import com.google.devtools.build.lib.actions.ImportantOutputHandler.ImportantOutputException;
import com.google.devtools.build.lib.actions.ImportantOutputHandler.LostArtifacts;
import com.google.devtools.build.lib.actions.InputFileErrorException;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.OutputChecker;
import com.google.devtools.build.lib.actions.TopLevelOutputException;
import com.google.devtools.build.lib.analysis.ConfiguredObjectValue;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.analysis.TopLevelArtifactHelper;
import com.google.devtools.build.lib.analysis.TopLevelArtifactHelper.ArtifactsInOutputGroup;
import com.google.devtools.build.lib.analysis.TopLevelArtifactHelper.ArtifactsToBuild;
import com.google.devtools.build.lib.analysis.TopLevelArtifactHelper.SuccessfulArtifactFilter;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.causes.LabelCause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;
import com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.RemoteExecution;
import com.google.devtools.build.lib.skyframe.ArtifactFunction.MissingArtifactValue;
import com.google.devtools.build.lib.skyframe.ArtifactFunction.SourceArtifactException;
import com.google.devtools.build.lib.skyframe.MetadataConsumerForMetrics.FilesMetricConsumer;
import com.google.devtools.build.lib.skyframe.rewinding.ActionRewindException;
import com.google.devtools.build.lib.skyframe.rewinding.ActionRewindStrategy;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.starlark.java.syntax.Location;

/** CompletionFunction builds the artifactsToBuild collection of a {@link ConfiguredTarget}. */
public final class CompletionFunction<
        ValueT extends ConfiguredObjectValue,
        ResultT extends SkyValue,
        KeyT extends TopLevelActionLookupKeyWrapper>
    implements SkyFunction {

  /**
   * A strategy for completing the build.
   *
   * <p>Any Skyframe lookups in methods passed an {@link Environment} must return an already-done
   * value. For example, it is acceptable to call {@link
   * ConfiguredTargetAndData#fromExistingConfiguredTargetInSkyframe}.
   */
  interface Completor<
      ValueT, ResultT extends SkyValue, KeyT extends TopLevelActionLookupKeyWrapper> {

    /** Creates an event reporting an absent input artifact. */
    Event getRootCauseError(KeyT key, ValueT value, LabelCause rootCause, Environment env)
        throws InterruptedException;

    Object getLocationIdentifier(KeyT key, ValueT value, Environment env)
        throws InterruptedException;

    /** Provides a successful completion value. */
    ResultT getResult();

    /**
     * Creates a failed completion event.
     *
     * <p>The event must be {@linkplain Postable#storeForReplay stored}.
     */
    Postable createFailed(
        KeyT skyKey,
        ValueT value,
        NestedSet<Cause> rootCauses,
        CompletionContext ctx,
        ImmutableMap<String, ArtifactsInOutputGroup> outputs,
        Environment env)
        throws InterruptedException;

    /**
     * Creates a succeeded completion event.
     *
     * <p>The event must be {@linkplain Postable#storeForReplay stored}.
     */
    EventReportingArtifacts createSucceeded(
        KeyT skyKey,
        ValueT value,
        CompletionContext completionContext,
        ArtifactsToBuild artifactsToBuild,
        Environment env)
        throws InterruptedException;
  }

  private final PathResolverFactory pathResolverFactory;
  private final Completor<ValueT, ResultT, KeyT> completor;
  private final SkyframeActionExecutor skyframeActionExecutor;
  private final FilesMetricConsumer topLevelArtifactsMetric;
  private final ActionRewindStrategy actionRewindStrategy;
  private final BugReporter bugReporter;
  private final Supplier<Boolean> isSkymeld;

  CompletionFunction(
      PathResolverFactory pathResolverFactory,
      Completor<ValueT, ResultT, KeyT> completor,
      SkyframeActionExecutor skyframeActionExecutor,
      FilesMetricConsumer topLevelArtifactsMetric,
      ActionRewindStrategy actionRewindStrategy,
      BugReporter bugReporter,
      Supplier<Boolean> isSkymeld) {
    this.pathResolverFactory = checkNotNull(pathResolverFactory);
    this.completor = checkNotNull(completor);
    this.skyframeActionExecutor = checkNotNull(skyframeActionExecutor);
    this.topLevelArtifactsMetric = checkNotNull(topLevelArtifactsMetric);
    this.actionRewindStrategy = checkNotNull(actionRewindStrategy);
    this.bugReporter = checkNotNull(bugReporter);
    this.isSkymeld = isSkymeld;
  }

  @SuppressWarnings("unchecked") // Cast to KeyT
  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws CompletionFunctionException, InterruptedException {
    WorkspaceNameValue workspaceNameValue =
        (WorkspaceNameValue) env.getValue(WorkspaceNameValue.key());
    if (workspaceNameValue == null) {
      return null;
    }

    KeyT key = (KeyT) skyKey;
    Pair<ValueT, ArtifactsToBuild> valueAndArtifactsToBuild = getValueAndArtifactsToBuild(key, env);
    if (env.valuesMissing()) {
      return null;
    }
    ValueT value = valueAndArtifactsToBuild.first;
    ArtifactsToBuild artifactsToBuild = valueAndArtifactsToBuild.second;

    // Ensure that coverage artifacts are built before a target is considered completed.
    ImmutableList<Artifact> allArtifacts = artifactsToBuild.getAllArtifacts().toList();
    InstrumentedFilesInfo instrumentedFilesInfo =
        value.getConfiguredObject().get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR);
    Iterable<Artifact> artifactsToRequest = allArtifacts;
    Artifact baselineCoverage = null;
    FileArtifactValue baselineCoverageValue = null;
    if (value.getConfiguredObject() instanceof ConfiguredTarget && instrumentedFilesInfo != null) {
      baselineCoverage = instrumentedFilesInfo.getBaselineCoverageArtifact();
      if (baselineCoverage != null) {
        artifactsToRequest =
            Iterables.concat(artifactsToRequest, ImmutableList.of(baselineCoverage));
      }
    }
    SkyframeLookupResult inputDeps = env.getValuesAndExceptions(Artifact.keys(artifactsToRequest));

    boolean allArtifactsAreImportant = artifactsToBuild.areAllOutputGroupsImportant();

    ActionInputMap inputMap = new ActionInputMap(bugReporter, allArtifacts.size());
    // Prepare an ActionInputMap for important artifacts separately, to be used by BEP events. The
    // _validation output group can contain orders of magnitude more unimportant artifacts than
    // there are important artifacts, and BEP events will retain the ActionInputMap until the
    // event is delivered to transports. If the BEP events reference *all* artifacts it can increase
    // heap high-watermark by multiple GB.
    ActionInputMap importantInputMap;
    ImmutableCollection<Artifact> importantArtifacts;
    if (allArtifactsAreImportant) {
      importantArtifacts = allArtifacts;
      importantInputMap = inputMap;
    } else {
      importantArtifacts = artifactsToBuild.getImportantArtifacts().toSet();
      importantInputMap = new ActionInputMap(bugReporter, importantArtifacts.size());
    }

    // TODO: b/239184359 - Can we just get the tree artifacts from the ActionInputMap?
    Map<Artifact, TreeArtifactValue> treeArtifacts = new HashMap<>();

    Map<Artifact, FilesetOutputTree> expandedFilesets = new HashMap<>();
    Map<Artifact, FilesetOutputTree> topLevelFilesets = new HashMap<>();

    ActionExecutionException firstActionExecutionException = null;
    NestedSetBuilder<Cause> rootCausesBuilder = NestedSetBuilder.stableOrder();
    Set<Artifact> builtArtifacts = new HashSet<>();
    // Don't double-count files due to Skyframe restarts.
    FilesMetricConsumer currentConsumer = new FilesMetricConsumer();
    for (Artifact input : artifactsToRequest) {
      try {
        SkyValue artifactValue =
            inputDeps.getOrThrow(
                Artifact.key(input), ActionExecutionException.class, SourceArtifactException.class);
        if (artifactValue == null) {
          continue;
        }
        if (artifactValue instanceof MissingArtifactValue) {
          handleSourceFileError(
              input,
              ((MissingArtifactValue) artifactValue).getDetailedExitCode(),
              rootCausesBuilder,
              env,
              value,
              key);
        } else if (input.equals(baselineCoverage)) {
          baselineCoverageValue =
              ((ActionExecutionValue) artifactValue).getExistingFileArtifactValue(baselineCoverage);
        } else {
          builtArtifacts.add(input);
          ActionInputMapHelper.addToMap(
              inputMap,
              treeArtifacts::put,
              expandedFilesets,
              topLevelFilesets,
              input,
              artifactValue,
              env,
              currentConsumer);
          if (!allArtifactsAreImportant && importantArtifacts.contains(input)) {
            // Calling #addToMap a second time with `input` and `artifactValue` will perform no-op
            // updates to the secondary collections passed in (eg. treeArtifacts, expandedFilesets).
            // MetadataConsumerForMetrics.NO_OP is used to avoid double-counting.
            ActionInputMapHelper.addToMap(
                importantInputMap,
                treeArtifacts::put,
                expandedFilesets,
                topLevelFilesets,
                input,
                artifactValue,
                env);
          }
        }
      } catch (ActionExecutionException e) {
        rootCausesBuilder.addTransitive(e.getRootCauses());
        // Prefer a catastrophic exception as the one we propagate.
        if (firstActionExecutionException == null
            || (!firstActionExecutionException.isCatastrophe() && e.isCatastrophe())) {
          firstActionExecutionException = e;
        }
      } catch (SourceArtifactException e) {
        if (!input.isSourceArtifact()) {
          bugReporter.logUnexpected(
              e, "Non-source artifact had SourceArtifactException: %s", input);
        }
        handleSourceFileError(input, e.getDetailedExitCode(), rootCausesBuilder, env, value, key);
      }
    }
    expandedFilesets.putAll(topLevelFilesets);

    CompletionContext ctx =
        CompletionContext.create(
            treeArtifacts,
            expandedFilesets,
            baselineCoverageValue,
            key.topLevelArtifactContext().expandFilesets(),
            key.topLevelArtifactContext().fullyResolveFilesetSymlinks(),
            inputMap,
            importantInputMap,
            pathResolverFactory,
            skyframeActionExecutor.getExecRoot(),
            workspaceNameValue.getName());

    NestedSet<Cause> rootCauses = rootCausesBuilder.build();
    if (!rootCauses.isEmpty()) {
      Reset reset = null;
      if (!builtArtifacts.isEmpty()) {
        // In error bubbling, we may be interrupted by Skyframe. Ensure that the interrupt doesn't
        // prevent us from staging built artifacts and posting the failed event.
        boolean interruptedDuringErrorBubbling = env.inErrorBubbling() && Thread.interrupted();
        try {
          reset =
              informImportantOutputHandler(
                  key,
                  value,
                  env,
                  ImmutableList.copyOf(
                      allArtifactsAreImportant
                          ? builtArtifacts
                          : Iterables.filter(builtArtifacts, importantArtifacts::contains)),
                  rootCauses,
                  ctx,
                  artifactsToBuild,
                  builtArtifacts);
        } finally {
          if (interruptedDuringErrorBubbling) {
            Thread.currentThread().interrupt();
          }
        }
      }
      postFailedEvent(key, value, rootCauses, ctx, artifactsToBuild, builtArtifacts, env);
      if (reset != null) {
        // Only return a reset after posting the failed event. If we're in --nokeep_going mode, the
        // attempt to rewind will be ignored, so this is our only opportunity to post the event. If
        // we're in --keep_going mode, rewinding will take place, the event won't actually get
        // emitted (per the spec of SkyFunction.Environment#getListener for stored events), and
        // we'll get another opportunity to post an event after rewinding.
        return reset;
      }
      if (firstActionExecutionException != null) {
        throw new CompletionFunctionException(firstActionExecutionException);
      }
      Object locationPrefix = completor.getLocationIdentifier(key, value, env);
      Pair<DetailedExitCode, String> codeAndMessage =
          ActionExecutionFunction.createSourceErrorCodeAndMessage(rootCauses.toList(), key);
      String message;
      if (locationPrefix instanceof Location) {
        message = codeAndMessage.getSecond();
        env.getListener().handle(Event.error((Location) locationPrefix, message));
      } else {
        message = locationPrefix + " " + codeAndMessage.getSecond();
        env.getListener().handle(Event.error(message));
      }
      throw new CompletionFunctionException(
          new InputFileErrorException(message, codeAndMessage.getFirst()));
    }

    // Only check for missing values *after* reporting errors: if there are missing files in a build
    // with --nokeep_going, there may be missing dependencies during error bubbling, we still need
    // to report the error.
    if (env.valuesMissing()) {
      return null;
    }

    Reset reset =
        informImportantOutputHandler(
            key, value, env, importantArtifacts, rootCauses, ctx, artifactsToBuild, builtArtifacts);
    if (reset != null) {
      return reset; // Initiate action rewinding to regenerate lost outputs.
    }

    ensureToplevelArtifacts(env, importantArtifacts, inputMap);

    Postable event = completor.createSucceeded(key, value, ctx, artifactsToBuild, env);
    checkStored(event, key);
    env.getListener().post(event);
    topLevelArtifactsMetric.mergeIn(currentConsumer);

    return completor.getResult();
  }

  private void ensureToplevelArtifacts(
      Environment env, ImmutableCollection<Artifact> importantArtifacts, ActionInputMap inputMap)
      throws CompletionFunctionException, InterruptedException {
    // For skymeld, a non-toplevel target might become a toplevel after it has been executed. This
    // is the last chance to download the missing toplevel outputs in this case before sending out
    // TargetCompleteEvent. See https://github.com/bazelbuild/bazel/issues/20737.
    if (!isSkymeld.get()) {
      return;
    }

    var actionInputPrefetcher = skyframeActionExecutor.getActionInputPrefetcher();
    if (actionInputPrefetcher == null || actionInputPrefetcher == ActionInputPrefetcher.NONE) {
      return;
    }

    var outputService = skyframeActionExecutor.getOutputService();
    var outputChecker = outputService.getOutputChecker();
    if (outputChecker == OutputChecker.TRUST_ALL) {
      return;
    }

    var futures = new ArrayList<ListenableFuture<Void>>();

    for (var artifact : importantArtifacts) {
      downloadArtifact(env, outputChecker, actionInputPrefetcher, inputMap, artifact, futures);
    }

    for (var runfileTree : inputMap.getRunfilesTrees()) {
      for (var artifact : runfileTree.getArtifacts().toList()) {
        downloadArtifact(env, outputChecker, actionInputPrefetcher, inputMap, artifact, futures);
      }
    }

    try {
      var unused = Futures.whenAllSucceed(futures).call(() -> null, directExecutor()).get();
    } catch (ExecutionException e) {
      throw new CompletionFunctionException(
          new TopLevelOutputException(
              e.getMessage(),
              DetailedExitCode.of(
                  FailureDetail.newBuilder()
                      .setMessage(e.getMessage())
                      .setRemoteExecution(
                          RemoteExecution.newBuilder()
                              .setCode(RemoteExecution.Code.TOPLEVEL_OUTPUTS_DOWNLOAD_FAILURE)
                              .build())
                      .build())));
    }
  }

  private void downloadArtifact(
      Environment env,
      OutputChecker outputChecker,
      ActionInputPrefetcher actionInputPrefetcher,
      ActionInputMap inputMap,
      Artifact artifact,
      List<ListenableFuture<Void>> futures)
      throws InterruptedException {
    if (!(artifact instanceof DerivedArtifact derivedArtifact)) {
      return;
    }

    // Metadata can be null during error bubbling, only download outputs that are already
    // generated. b/342188273
    if (artifact.isTreeArtifact()) {
      var treeMetadata = inputMap.getTreeMetadata(artifact.getExecPath());
      if (treeMetadata == null) {
        return;
      }

      var filesToDownload = new ArrayList<ActionInput>(treeMetadata.getChildValues().size());
      for (var child : treeMetadata.getChildValues().entrySet()) {
        var treeFile = child.getKey();
        var metadata = child.getValue();
        if (!outputChecker.shouldTrustArtifact(treeFile, metadata)) {
          filesToDownload.add(treeFile);
        }
      }
      if (!filesToDownload.isEmpty()) {
        var action =
            ActionUtils.getActionForLookupData(env, derivedArtifact.getGeneratingActionKey());
        var future =
            actionInputPrefetcher.prefetchFiles(
                action, filesToDownload, inputMap, Priority.LOW, Reason.OUTPUTS);
        futures.add(future);
      }
    } else {
      var metadata = inputMap.getInputMetadata(artifact);
      if (metadata == null) {
        return;
      }

      if (!outputChecker.shouldTrustArtifact(artifact, metadata)) {
        var action =
            ActionUtils.getActionForLookupData(env, derivedArtifact.getGeneratingActionKey());
        var future =
            actionInputPrefetcher.prefetchFiles(
                action, ImmutableList.of(artifact), inputMap, Priority.LOW, Reason.OUTPUTS);
        futures.add(future);
      }
    }
  }

  private void postFailedEvent(
      KeyT key,
      ValueT value,
      NestedSet<Cause> rootCauses,
      CompletionContext ctx,
      ArtifactsToBuild artifactsToBuild,
      Set<Artifact> builtArtifacts,
      Environment env)
      throws InterruptedException {
    ImmutableMap<String, ArtifactsInOutputGroup> builtOutputs =
        new SuccessfulArtifactFilter(ImmutableSet.copyOf(builtArtifacts))
            .filterArtifactsInOutputGroup(artifactsToBuild.getAllArtifactsByOutputGroup());
    Postable event = completor.createFailed(key, value, rootCauses, ctx, builtOutputs, env);
    checkStored(event, key);
    env.getListener().post(event);
  }

  private void handleSourceFileError(
      Artifact input,
      DetailedExitCode detailedExitCode,
      NestedSetBuilder<Cause> rootCausesBuilder,
      Environment env,
      ValueT value,
      KeyT key)
      throws InterruptedException {
    LabelCause cause =
        ActionExecutionFunction.createLabelCause(
            input, detailedExitCode, key.actionLookupKey().getLabel(), bugReporter);
    rootCausesBuilder.add(cause);
    env.getListener().handle(completor.getRootCauseError(key, value, cause, env));
    skyframeActionExecutor.recordExecutionError();
  }

  @Nullable
  static <ValueT extends ConfiguredObjectValue>
      Pair<ValueT, ArtifactsToBuild> getValueAndArtifactsToBuild(
          TopLevelActionLookupKeyWrapper key, Environment env) throws InterruptedException {
    @SuppressWarnings("unchecked")
    ValueT value = (ValueT) env.getValue(key.actionLookupKey());
    if (env.valuesMissing()) {
      return null;
    }

    TopLevelArtifactContext topLevelContext = key.topLevelArtifactContext();
    ArtifactsToBuild artifactsToBuild =
        TopLevelArtifactHelper.getAllArtifactsToBuild(value.getConfiguredObject(), topLevelContext);
    return Pair.of(value, artifactsToBuild);
  }

  /**
   * Calls {@link ImportantOutputHandler#processOutputsAndGetLostArtifacts}.
   *
   * <p>If any outputs are lost, returns a {@link Reset} which can be used to initiate action
   * rewinding and regenerate the lost outputs. Otherwise, returns {@code null}.
   */
  @Nullable
  private Reset informImportantOutputHandler(
      KeyT key,
      ValueT value,
      Environment env,
      ImmutableCollection<Artifact> importantArtifacts,
      NestedSet<Cause> rootCauses,
      CompletionContext ctx,
      ArtifactsToBuild artifactsToBuild,
      Set<Artifact> builtArtifacts)
      throws CompletionFunctionException, InterruptedException {
    var importantOutputHandler =
        skyframeActionExecutor.getActionContextRegistry().getContext(ImportantOutputHandler.class);
    if (importantOutputHandler == null) {
      return null;
    }

    Label label = key.actionLookupKey().getLabel();
    InputMetadataProvider metadataProvider =
        new ActionInputMetadataProvider(
            skyframeActionExecutor.getExecRoot().asFragment(),
            ctx.getImportantInputMap(),
            ctx.getExpandedFilesets());
    try {
      LostArtifacts lostOutputs;
      try (var ignored =
          GoogleAutoProfilerUtils.logged(
              "Informing important output handler of top-level outputs for " + label,
              ImportantOutputHandler.LOG_THRESHOLD)) {
        lostOutputs =
            importantOutputHandler.processOutputsAndGetLostArtifacts(
                key.topLevelArtifactContext().expandFilesets()
                    ? importantArtifacts
                    : Iterables.filter(importantArtifacts, artifact -> !artifact.isFileset()),
                ctx,
                metadataProvider);
      }
      if (lostOutputs.isEmpty()) {
        return null;
      }

      // Filter out lost outputs from the set of built artifacts so that they are not reported. If
      // rewinding is successful, we'll report them later on.
      for (ActionInput lostOutput : lostOutputs.byDigest().values()) {
        builtArtifacts.remove(lostOutput);
        builtArtifacts.removeAll(lostOutputs.owners().getDepOwners(lostOutput));
      }

      return actionRewindStrategy.prepareRewindPlanForLostTopLevelOutputs(
          key,
          ImmutableSet.copyOf(Artifact.keys(importantArtifacts)),
          lostOutputs.byDigest(),
          lostOutputs.owners(),
          env);
    } catch (ActionRewindException | ImportantOutputException e) {
      LabelCause cause = new LabelCause(label, e.getDetailedExitCode());
      rootCauses = NestedSetBuilder.fromNestedSet(rootCauses).add(cause).build();
      env.getListener().handle(completor.getRootCauseError(key, value, cause, env));
      skyframeActionExecutor.recordExecutionError();
      postFailedEvent(key, value, rootCauses, ctx, artifactsToBuild, builtArtifacts, env);
      throw new CompletionFunctionException(
          new TopLevelOutputException(e.getMessage(), e.getDetailedExitCode()));
    }
  }

  private static void checkStored(Postable event, TopLevelActionLookupKeyWrapper key) {
    checkState(
        event.storeForReplay(), "Completion events must be stored, got %s for %s", event, key);
  }

  @Override
  public String extractTag(SkyKey skyKey) {
    return Label.print(((TopLevelActionLookupKeyWrapper) skyKey).actionLookupKey().getLabel());
  }

  private static final class CompletionFunctionException extends SkyFunctionException {
    private final ActionExecutionException actionException;

    CompletionFunctionException(ActionExecutionException e) {
      super(e, Transience.PERSISTENT);
      this.actionException = e;
    }

    CompletionFunctionException(InputFileErrorException e) {
      // Not transient from the point of view of this SkyFunction.
      super(e, Transience.PERSISTENT);
      this.actionException = null;
    }

    CompletionFunctionException(TopLevelOutputException e) {
      super(e, Transience.TRANSIENT);
      this.actionException = null;
    }

    @Override
    public boolean isCatastrophic() {
      return actionException != null && actionException.isCatastrophe();
    }
  }
}
