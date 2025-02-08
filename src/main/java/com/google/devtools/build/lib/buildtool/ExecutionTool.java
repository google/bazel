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
package com.google.devtools.build.lib.buildtool;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Predicate;
import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.common.eventbus.Subscribe;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionCacheChecker;
import com.google.devtools.build.lib.actions.ActionExecutionStatusReporter;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher;
import com.google.devtools.build.lib.actions.ArtifactFactory;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.DynamicStrategyRegistry;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.MachineLoadProvider;
import com.google.devtools.build.lib.actions.OutputChecker;
import com.google.devtools.build.lib.actions.PackageRoots;
import com.google.devtools.build.lib.actions.ResourceManager;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.TestExecException;
import com.google.devtools.build.lib.actions.cache.ActionCache;
import com.google.devtools.build.lib.actions.cache.PostableActionCacheStats;
import com.google.devtools.build.lib.actions.cache.Protos.ActionCacheStatistics;
import com.google.devtools.build.lib.analysis.AnalysisResult;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.analysis.TopLevelArtifactHelper;
import com.google.devtools.build.lib.analysis.WorkspaceStatusAction;
import com.google.devtools.build.lib.analysis.actions.SymlinkTreeActionContext;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.test.TestActionContext;
import com.google.devtools.build.lib.buildtool.BuildRequestOptions.ConvenienceSymlinksMode;
import com.google.devtools.build.lib.buildtool.buildevent.ConvenienceSymlinksIdentifiedEvent;
import com.google.devtools.build.lib.buildtool.buildevent.ExecutionPhaseCompleteEvent;
import com.google.devtools.build.lib.buildtool.buildevent.ExecutionProgressReceiverAvailableEvent;
import com.google.devtools.build.lib.buildtool.buildevent.ExecutionStartingEvent;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.BlazeExecutor;
import com.google.devtools.build.lib.exec.CheckUpToDateFilter;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.ExecutorBuilder;
import com.google.devtools.build.lib.exec.ExecutorLifecycleListener;
import com.google.devtools.build.lib.exec.ModuleActionContextRegistry;
import com.google.devtools.build.lib.exec.RemoteLocalFallbackRegistry;
import com.google.devtools.build.lib.exec.SpawnStrategyRegistry;
import com.google.devtools.build.lib.exec.SpawnStrategyResolver;
import com.google.devtools.build.lib.exec.SymlinkTreeStrategy;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.profiler.AutoProfiler;
import com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils;
import com.google.devtools.build.lib.profiler.ProfilePhase;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.InstrumentationOutput;
import com.google.devtools.build.lib.runtime.InstrumentationOutputFactory.DestinationRelativeTo;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.Execution;
import com.google.devtools.build.lib.server.FailureDetails.Execution.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.ActionExecutionInactivityWatchdog;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.BuildResultListener;
import com.google.devtools.build.lib.skyframe.Builder;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.IncrementalPackageRoots;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.SomeExecutionStartedEvent;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.OutputService;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * This class manages the execution phase. The entry point is {@link #executeBuild}.
 *
 * <p>This is only intended for use by {@link BuildTool}.
 *
 * <p>This class contains an ActionCache, and refers to the Blaze Runtime's BuildView and
 * PackageCache.
 *
 * <p>Lifetime of an instance: 1 invocation.
 *
 * @see BuildTool
 * @see com.google.devtools.build.lib.analysis.BuildView
 */
public class ExecutionTool {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final CommandEnvironment env;
  private final BlazeRuntime runtime;
  private final BuildRequest request;
  private BlazeExecutor executor;
  private final ActionInputPrefetcher prefetcher;
  private final ImmutableSet<ExecutorLifecycleListener> executorLifecycleListeners;

  private final SpawnStrategyRegistry spawnStrategyRegistry;
  private final ModuleActionContextRegistry actionContextRegistry;

  private boolean informedOutputServiceToStartTheBuild = false;

  ExecutionTool(CommandEnvironment env, BuildRequest request)
      throws AbruptExitException, InterruptedException {
    this.env = env;
    this.runtime = env.getRuntime();
    this.request = request;

    try {
      env.getExecRoot().createDirectoryAndParents();
    } catch (IOException e) {
      throw createExitException("Execroot creation failed", Code.EXECROOT_CREATION_FAILURE, e);
    }

    ExecutorBuilder executorBuilder = new ExecutorBuilder();
    ModuleActionContextRegistry.Builder actionContextRegistryBuilder =
        ModuleActionContextRegistry.builder();
    SpawnStrategyRegistry.Builder spawnStrategyRegistryBuilder =
        SpawnStrategyRegistry.builder(env.getInvocationPolicy().getStrategyPolicy());
    actionContextRegistryBuilder.register(SpawnStrategyResolver.class, new SpawnStrategyResolver());

    for (BlazeModule module : runtime.getBlazeModules()) {
      try (SilentCloseable ignored = Profiler.instance().profile(module + ".executorInit")) {
        module.executorInit(env, request, executorBuilder);
      }

      try (SilentCloseable ignored =
          Profiler.instance().profile(module + ".registerActionContexts")) {
        module.registerActionContexts(actionContextRegistryBuilder, env, request);
      }

      try (SilentCloseable ignored =
          Profiler.instance().profile(module + ".registerSpawnStrategies")) {
        module.registerSpawnStrategies(spawnStrategyRegistryBuilder, env);
      }
    }
    actionContextRegistryBuilder.register(
        SymlinkTreeActionContext.class,
        new SymlinkTreeStrategy(
            env.getOutputService(),
            env.getExecRoot(),
            env.getBlazeWorkspace().getBinTools(),
            env.getWorkspaceName()));
    // TODO(philwo) - the ExecutionTool should not add arbitrary dependencies on its own, instead
    // these dependencies should be added to the ActionContextConsumer of the module that actually
    // depends on them.
    actionContextRegistryBuilder
        .restrictTo(WorkspaceStatusAction.Context.class, "")
        .restrictTo(SymlinkTreeActionContext.class, "");

    this.prefetcher = executorBuilder.getActionInputPrefetcher();
    this.executorLifecycleListeners = executorBuilder.getExecutorLifecycleListeners();

    // There are many different SpawnActions, and we want to control the action context they use
    // independently from each other, for example, to run genrules locally and Java compile action
    // in prod. Thus, for SpawnActions, we decide the action context to use not only based on the
    // context class, but also the mnemonic of the action.
    ExecutionOptions options = request.getOptions(ExecutionOptions.class);
    // TODO(jmmv): This should live in some testing-related Blaze module, not here.
    actionContextRegistryBuilder.restrictTo(TestActionContext.class, options.testStrategy);

    SpawnStrategyRegistry spawnStrategyRegistry = spawnStrategyRegistryBuilder.build();
    actionContextRegistryBuilder.register(SpawnStrategyRegistry.class, spawnStrategyRegistry);
    actionContextRegistryBuilder.register(DynamicStrategyRegistry.class, spawnStrategyRegistry);
    actionContextRegistryBuilder.register(RemoteLocalFallbackRegistry.class, spawnStrategyRegistry);

    this.actionContextRegistry = actionContextRegistryBuilder.build();
    this.spawnStrategyRegistry = spawnStrategyRegistry;
  }

  Executor getExecutor() throws AbruptExitException {
    if (executor == null) {
      executor = createExecutor();
      for (ExecutorLifecycleListener executorLifecycleListener : executorLifecycleListeners) {
        executorLifecycleListener.executorCreated();
      }
    }
    return executor;
  }

  /** Creates an executor for the current set of blaze runtime, execution options, and request. */
  private BlazeExecutor createExecutor() {
    return new BlazeExecutor(
        runtime.getFileSystem(),
        env.getExecRoot(),
        getReporter(),
        runtime.getClock(),
        runtime.getBugReporter(),
        request,
        actionContextRegistry,
        spawnStrategyRegistry);
  }

  void init() throws AbruptExitException {
    getExecutor();
  }

  void shutdown() {
    for (ExecutorLifecycleListener executorLifecycleListener : executorLifecycleListeners) {
      executorLifecycleListener.executionPhaseEnding();
    }
  }

  TestActionContext getTestActionContext() {
    return actionContextRegistry.getContext(TestActionContext.class);
  }

  /**
   * Sets up for execution.
   *
   * <p>This method concentrates the setup steps for execution, which were previously scattered over
   * several classes. We need this in order to merge analysis & execution phases.
   *
   * <p>TODO(b/213040766): Write tests for these setup steps.
   */
  void prepareForExecution(Stopwatch executionTimer)
      throws AbruptExitException,
          BuildFailedException,
          InterruptedException,
          InvalidConfigurationException {
    init();
    BuildRequestOptions buildRequestOptions = request.getBuildOptions();
    SkyframeExecutor skyframeExecutor = env.getSkyframeExecutor();
    boolean localActionsSupported =
        env.getOutputService().actionFileSystemType().shouldDoTopLevelOutputSetup();

    try (SilentCloseable c = Profiler.instance().profile("preparingExecroot")) {
      IncrementalPackageRoots incrementalPackageRoots =
          IncrementalPackageRoots.createAndRegisterToEventBus(
              getExecRoot(),
              // Single package path is a Skymeld prerequisite.
              Iterables.getOnlyElement(env.getPackageLocator().getPathEntries()),
              env.getEventBus(),
              env.getDirectories().getProductName() + "-",
              skyframeExecutor.getIgnoredPaths(),
              request.getOptions(BuildLanguageOptions.class).experimentalSiblingRepositoryLayout,
              runtime.getWorkspace().doesAllowExternalRepositories());
      if (localActionsSupported) {
        incrementalPackageRoots.eagerlyPlantSymlinksToSingleSourceRoot();
      }

      env.getSkyframeBuildView()
          .getArtifactFactory()
          .setPackageRoots(incrementalPackageRoots.getPackageRootLookup());
    }

    OutputService outputService = env.getOutputService();
    ModifiedFileSet modifiedOutputFiles =
        startBuildAndDetermineModifiedOutputFiles(request.getId(), outputService);
    if (localActionsSupported) {
      // Must be created after the output path is created above.
      try (SilentCloseable c = Profiler.instance().profile("createActionLogDirectory")) {
        createActionLogDirectory();
      }
    }

    ActionCache actionCache = null;
    if (buildRequestOptions.useActionCache) {
      try (SilentCloseable c = Profiler.instance().profile("load/reset action cache")) {
        actionCache = getOrLoadActionCache();
        actionCache.resetStatistics();
      }
    }
    SkyframeBuilder skyframeBuilder;
    try (SilentCloseable c = Profiler.instance().profile("createBuilder")) {
      skyframeBuilder =
          (SkyframeBuilder)
              createBuilder(
                  request,
                  actionCache,
                  skyframeExecutor,
                  modifiedOutputFiles,
                  outputService.shouldStoreRemoteOutputMetadataInActionCache());
    }

    skyframeExecutor.drainChangedFiles();

    var outputChecker =
        env.getOutputService() != null
            ? env.getOutputService().getOutputChecker()
            : OutputChecker.TRUST_LOCAL_ONLY;
    skyframeExecutor.detectModifiedOutputFiles(
        modifiedOutputFiles,
        env.getBlazeWorkspace().getLastExecutionTimeRange(),
        outputChecker,
        buildRequestOptions.fsvcThreads);
    try (SilentCloseable c = Profiler.instance().profile("configureActionExecutor")) {
      skyframeExecutor.configureActionExecutor(
          skyframeBuilder.getFileCache(), skyframeBuilder.getActionInputPrefetcher());
    }

    skyframeExecutor.deleteActionsIfRemoteOptionsChanged(request);
    try (SilentCloseable c =
        Profiler.instance().profile("prepareSkyframeActionExecutorForExecution")) {
      skyframeExecutor.prepareSkyframeActionExecutorForExecution(
          env.getReporter(),
          executor,
          request,
          skyframeBuilder.getActionCacheChecker(),
          skyframeBuilder.getActionOutputDirectoryHelper());
    }

    env.getEventBus()
        .register(
            new ExecutionProgressReceiverSetup(
                skyframeExecutor, env, executionTimer, buildRequestOptions.progressReportInterval));
    for (ExecutorLifecycleListener executorLifecycleListener : executorLifecycleListeners) {
      try (SilentCloseable c =
          Profiler.instance().profile(executorLifecycleListener + ".executionPhaseStarting")) {
        executorLifecycleListener.executionPhaseStarting(
            null, () -> null, skyframeExecutor.getEphemeralCheckIfOutputConsumed());
      }
    }
    try (SilentCloseable c = Profiler.instance().profile("configureResourceManager")) {
      configureResourceManager(env.getLocalResourceManager(), request);
    }

    announceEnteringDirIfEmacs();
  }

  /**
   * Performs the execution phase (phase 3) of the build, in which the Builder is applied to the
   * action graph to bring the targets up to date. (This function will return prior to
   * execution-proper if --nobuild was specified.)
   *
   * @param buildId UUID of the build id
   * @param analysisResult the analysis phase output
   * @param buildResult the mutable build result
   * @param packageRoots package roots collected from loading phase and {@link
   *     BuildConfigurationValue} creation. May be empty if using virtual roots.
   */
  void executeBuild(
      UUID buildId,
      AnalysisResult analysisResult,
      BuildResult buildResult,
      PackageRoots packageRoots,
      TopLevelArtifactContext topLevelArtifactContext)
      throws BuildFailedException, InterruptedException, TestExecException, AbruptExitException {
    Stopwatch timer = Stopwatch.createStarted();
    prepare(packageRoots);

    ActionGraph actionGraph = analysisResult.getActionGraph();

    OutputService outputService = env.getOutputService();
    ModifiedFileSet modifiedOutputFiles =
        startBuildAndDetermineModifiedOutputFiles(buildId, outputService);

    if (outputService.actionFileSystemType().shouldDoTopLevelOutputSetup()) {
      // Must be created after the output path is created above.
      createActionLogDirectory();
    }

    buildResult.setConvenienceSymlinks(
        handleConvenienceSymlinks(
            analysisResult.getTargetsToBuild(), analysisResult.getConfiguration()));

    BuildRequestOptions options = request.getBuildOptions();
    ActionCache actionCache = null;
    if (options.useActionCache) {
      actionCache = getOrLoadActionCache();
      actionCache.resetStatistics();
    }
    SkyframeExecutor skyframeExecutor = env.getSkyframeExecutor();
    Builder builder;
    try (SilentCloseable c = Profiler.instance().profile("createBuilder")) {
      builder =
          createBuilder(
              request,
              actionCache,
              skyframeExecutor,
              modifiedOutputFiles,
              outputService.shouldStoreRemoteOutputMetadataInActionCache());
    }

    //
    // Execution proper.  All statements below are logically nested in
    // begin/end pairs.  No early returns or exceptions please!
    //

    Collection<ConfiguredTarget> configuredTargets = buildResult.getActualTargets();
    try (SilentCloseable c = Profiler.instance().profile("ExecutionStartingEvent")) {
      env.getEventBus().post(new ExecutionStartingEvent(configuredTargets));
    }

    getReporter().handle(Event.progress("Building..."));

    // Conditionally record dependency-checker log:
    ExplanationHandler explanationHandler =
        installExplanationHandler(
            request.getBuildOptions().explanationPath, request.getOptionsDescription());

    announceEnteringDirIfEmacs();

    Throwable catastrophe = null;
    boolean buildCompleted = false;
    try {
      if (request.getViewOptions().discardAnalysisCache
          || !skyframeExecutor.tracksStateForIncrementality()) {
        // Free memory by removing cache entries that aren't going to be needed.
        try (SilentCloseable c = Profiler.instance().profile("clearAnalysisCache")) {
          env.getSkyframeBuildView()
              .clearAnalysisCache(
                  analysisResult.getTargetsToBuild(), analysisResult.getAspectsMap().keySet());
        }
      }

      for (ExecutorLifecycleListener executorLifecycleListener : executorLifecycleListeners) {
        try (SilentCloseable c =
            Profiler.instance().profile(executorLifecycleListener + ".executionPhaseStarting")) {
          executorLifecycleListener.executionPhaseStarting(
              actionGraph,
              // If this supplier is ever consumed by more than one ActionContextProvider, it can be
              // pulled out of the loop and made a memoizing supplier.
              () -> TopLevelArtifactHelper.findAllTopLevelArtifacts(analysisResult),
              /* ephemeralCheckIfOutputConsumed= */ null);
        }
      }
      skyframeExecutor.drainChangedFiles();

      try (SilentCloseable c = Profiler.instance().profile("configureResourceManager")) {
        configureResourceManager(env.getLocalResourceManager(), request);
      }

      Profiler.instance().markPhase(ProfilePhase.EXECUTE);
      var outputChecker =
          env.getOutputService() != null
              ? env.getOutputService().getOutputChecker()
              : OutputChecker.TRUST_LOCAL_ONLY;
      builder.buildArtifacts(
          env.getReporter(),
          analysisResult.getArtifactsToBuild(),
          analysisResult.getParallelTests(),
          Sets.union(analysisResult.getExclusiveTests(), analysisResult.getExclusiveIfLocalTests()),
          analysisResult.getTargetsToBuild(),
          analysisResult.getTargetsToSkip(),
          analysisResult.getAspectsMap().keySet(),
          executor,
          request,
          env.getBlazeWorkspace().getLastExecutionTimeRange(),
          topLevelArtifactContext,
          outputChecker);
      buildCompleted = true;
    } catch (BuildFailedException | TestExecException e) {
      buildCompleted = true;
      throw e;
    } catch (Error | RuntimeException e) {
      catastrophe = e;
    } finally {
      unconditionalExecutionPhaseFinalizations(timer, skyframeExecutor);

      if (catastrophe != null) {
        Throwables.throwIfUnchecked(catastrophe);
      }
      // NOTE: No finalization activities below will run in the event of a catastrophic error!
      nonCatastrophicFinalizations(buildResult, actionCache, explanationHandler, buildCompleted);
    }
  }

  private ModifiedFileSet startBuildAndDetermineModifiedOutputFiles(
      UUID buildId, OutputService outputService)
      throws BuildFailedException, AbruptExitException, InterruptedException {
    ModifiedFileSet modifiedOutputFiles;
    try (SilentCloseable c = Profiler.instance().profile("outputService.startBuild")) {
      modifiedOutputFiles =
          outputService.startBuild(
              buildId,
              env.getWorkspaceName(),
              env.getReporter(),
              request.getBuildOptions().finalizeActions);
      informedOutputServiceToStartTheBuild = true;
    }
    if (!request.getPackageOptions().checkOutputFiles) {
      // Do not skip output invalidation if the output tree is empty: this can happen after it's
      // cleaned or corrupted.
      if (!modifiedOutputFiles.treatEverythingAsDeleted()) {
        return ModifiedFileSet.NOTHING_MODIFIED;
      }
    }
    return modifiedOutputFiles;
  }

  private void announceEnteringDirIfEmacs() {
    if (request.isRunningInEmacs()) {
      // The syntax of this message is tightly constrained by lisp/progmodes/compile.el in emacs
      request
          .getOutErr()
          .printErrLn(runtime.getProductName() + ": Entering directory `" + getExecRoot() + "/'");
    }
  }

  private void announceLeavingDirIfEmacs() {
    if (request.isRunningInEmacs()) {
      request
          .getOutErr()
          .printErrLn(runtime.getProductName() + ": Leaving directory `" + getExecRoot() + "/'");
    }
  }

  /** These steps get performed after execution, if there's no catastrophic exception. */
  void nonCatastrophicFinalizations(
      BuildResult buildResult,
      ActionCache actionCache,
      @Nullable ExplanationHandler explanationHandler,
      boolean buildCompleted)
      throws BuildFailedException, AbruptExitException, InterruptedException {
    env.recordLastExecutionTime();

    announceLeavingDirIfEmacs();
    if (buildCompleted) {
      getReporter().handle(Event.progress("Building complete."));
    }

    if (buildCompleted) {
      saveActionCache(actionCache);
    }

    BuildResultListener buildResultListener = env.getBuildResultListener();
    try (SilentCloseable c = Profiler.instance().profile("Show results")) {
      buildResult.setSuccessfulTargets(
          determineSuccessfulTargets(
              buildResultListener.getAnalyzedTargets(), buildResultListener.getBuiltTargets()));
      buildResult.setSuccessfulAspects(
          determineSuccessfulAspects(
              buildResultListener.getAnalyzedAspects().keySet(),
              buildResultListener.getBuiltAspects()));
      buildResult.setSkippedTargets(buildResultListener.getSkippedTargets());
      BuildResultPrinter buildResultPrinter = new BuildResultPrinter(env);
      buildResultPrinter.showBuildResult(
          request,
          buildResult,
          buildResultListener.getAnalyzedTargets(),
          buildResultListener.getSkippedTargets(),
          buildResultListener.getAnalyzedAspects());
    }

    if (explanationHandler != null) {
      uninstallExplanationHandler(explanationHandler);
      try {
        explanationHandler.close();
      } catch (IOException ignored) {
        // Ignored
      }
    }
    // Finalize the output service last if required, so that if we do throw an exception, we know
    // that all the other code has already run.
    if (informedOutputServiceToStartTheBuild) {
      boolean isBuildSuccessful =
          buildResult.getSuccessfulTargets().size()
              == buildResultListener.getAnalyzedTargets().size();
      env.getOutputService().finalizeBuild(isBuildSuccessful);
    }
  }

  /**
   * These steps get performed after the end of execution, regardless of whether there's a
   * catastrophe or not.
   */
  void unconditionalExecutionPhaseFinalizations(
      Stopwatch executionTimer, SkyframeExecutor skyframeExecutor) {
    // These may flush logs, which may help if there is a catastrophic failure.
    for (ExecutorLifecycleListener executorLifecycleListener : executorLifecycleListeners) {
      executorLifecycleListener.executionPhaseEnding();
    }

    // Handlers process these events and others (e.g. CommandCompleteEvent), even in the event of
    // a catastrophic failure. Posting these is consistent with other behavior.
    env.getEventBus().post(skyframeExecutor.createExecutionFinishedEvent());

    // With Skymeld, the timer is started with the first execution activity in the build and ends
    // when the build is done. A running timer indicates that some execution activity happened.
    //
    // Sometimes there's no execution in the build: e.g. when there's only 1 target, and we fail at
    // the analysis phase. In such a case, we shouldn't send out this event. This is consistent with
    // the noskymeld behavior.
    if (executionTimer.isRunning()) {
      env.getEventBus()
          .post(new ExecutionPhaseCompleteEvent(executionTimer.stop().elapsed().toMillis()));
    }
  }

  private void prepare(PackageRoots packageRoots) throws AbruptExitException, InterruptedException {
    Optional<ImmutableMap<PackageIdentifier, Root>> packageRootMap =
        packageRoots.getPackageRootsMap();
    if (packageRootMap.isPresent()) {
      // Prepare for build.
      Profiler.instance().markPhase(ProfilePhase.PREPARE);

      // Plant the symlink forest.
      try (SilentCloseable c = Profiler.instance().profile("plantSymlinkForest")) {
        SymlinkForest symlinkForest =
            new SymlinkForest(
                packageRootMap.get(),
                getExecRoot(),
                runtime.getProductName(),
                request.getOptions(BuildLanguageOptions.class).experimentalSiblingRepositoryLayout);
        symlinkForest.plantSymlinkForest();
      } catch (IOException e) {
        String message = String.format("Source forest creation failed: %s", e.getMessage());
        throw new AbruptExitException(
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setSymlinkForest(
                        FailureDetails.SymlinkForest.newBuilder()
                            .setCode(FailureDetails.SymlinkForest.Code.CREATION_FAILED))
                    .build()),
            e);
      }
    }
  }

  private static void logDeleteTreeFailure(
      Path directory, String description, IOException deleteTreeFailure) {
    logger.atWarning().withCause(deleteTreeFailure).log(
        "Failed to delete %s '%s'", description, directory);
    if (directory.exists()) {
      try {
        Collection<Path> entries = directory.getDirectoryEntries();
        StringBuilder directoryDetails =
            new StringBuilder("'")
                .append(directory)
                .append("' contains ")
                .append(entries.size())
                .append(" entries:");
        for (Path entry : entries) {
          directoryDetails.append(" '").append(entry.getBaseName()).append("'");
        }
        logger.atWarning().log("%s", directoryDetails);
      } catch (IOException e) {
        logger.atWarning().withCause(e).log("'%s' exists but could not be read", directory);
      }
    } else {
      logger.atWarning().log("'%s' does not exist", directory);
    }
  }

  private void createActionLogDirectory() throws AbruptExitException {
    Path directory = env.getActionTempsDirectory();
    if (directory.exists()) {
      try (SilentCloseable c = Profiler.instance().profile("directory.deleteTree")) {
        directory.deleteTree();
      } catch (IOException e) {
        // TODO(b/140567980): Remove when we determine the cause of occasional deleteTree() failure.
        logDeleteTreeFailure(directory, "action output directory", e);
        throw createExitException(
            "Couldn't delete action output directory",
            Code.TEMP_ACTION_OUTPUT_DIRECTORY_DELETION_FAILURE,
            e);
      }
    }

    try (SilentCloseable c = Profiler.instance().profile("directory.createDirectoryAndParents")) {
      directory.createDirectoryAndParents();
    } catch (IOException e) {
      throw createExitException(
          "Couldn't create action output directory",
          Code.TEMP_ACTION_OUTPUT_DIRECTORY_CREATION_FAILURE,
          e);
    }
  }

  /**
   * Obtains the {@link BuildConfigurationValue} for a given {@link BuildOptions} for the purpose of
   * symlink creation.
   *
   * <p>In the event of a {@link InvalidConfigurationException}, a warning is emitted and null is
   * returned.
   */
  @Nullable
  private static BuildConfigurationValue getConfiguration(
      SkyframeExecutor executor, Reporter reporter, BuildOptions options) {
    try {
      return executor.getConfiguration(reporter, options, /* keepGoing= */ false);
    } catch (InvalidConfigurationException e) {
      reporter.handle(
          Event.warn(
              "Couldn't get configuration for convenience symlink creation: " + e.getMessage()));
      return null;
    }
  }

  /**
   * Handles what action to perform on the convenience symlinks. If the mode is {@link
   * ConvenienceSymlinksMode#IGNORE}, then skip any creating or cleaning of convenience symlinks.
   * Otherwise, manage the convenience symlinks and then post a {@link
   * ConvenienceSymlinksIdentifiedEvent} build event.
   *
   * @return map of convenience symlink name to target
   */
  ImmutableMap<PathFragment, PathFragment> handleConvenienceSymlinks(
      ImmutableSet<ConfiguredTarget> targetsToBuild, BuildConfigurationValue configuration) {
    try (SilentCloseable c =
        Profiler.instance().profile("ExecutionTool.handleConvenienceSymlinks")) {
      OutputDirectoryLinksUtils.SymlinkCreationResult convenienceSymlinks =
          OutputDirectoryLinksUtils.EMPTY_SYMLINK_CREATION_RESULT;
      if (request.getBuildOptions().experimentalConvenienceSymlinks
          != ConvenienceSymlinksMode.IGNORE) {
        convenienceSymlinks =
            createConvenienceSymlinks(request.getBuildOptions(), targetsToBuild, configuration);
      }
      if (request.getBuildOptions().experimentalConvenienceSymlinksBepEvent) {
        env.getEventBus()
            .post(
                new ConvenienceSymlinksIdentifiedEvent(
                    convenienceSymlinks.getConvenienceSymlinkProtos()));
      }
      return convenienceSymlinks.getCreatedSymlinks();
    }
  }

  /**
   * Creates convenience symlinks based on the target configurations.
   *
   * <p>Top-level targets may have different configurations than the top-level configuration. This
   * is because targets may apply configuration transitions.
   *
   * <p>If all top-level targets have the same configuration - even if that isn't the top-level
   * configuration - symlinks point to that configuration.
   *
   * <p>If top-level targets have mixed configurations and at least one of them has the top-level
   * configuration, symliks point to the top-level configuration.
   *
   * <p>If top-level targets have mixed configurations and none has the top-level configuration,
   * symlinks aren't created. Furthermore, lingering symlinks from the last build are deleted. This
   * is to prevent confusion by pointing to an outdated directory the current build never used.
   */
  private OutputDirectoryLinksUtils.SymlinkCreationResult createConvenienceSymlinks(
      BuildRequestOptions buildRequestOptions,
      ImmutableSet<ConfiguredTarget> targetsToBuild,
      BuildConfigurationValue configuration) {
    SkyframeExecutor executor = env.getSkyframeExecutor();
    Reporter reporter = env.getReporter();

    // Gather configurations to consider.
    ImmutableSet<BuildConfigurationValue> targetConfigs;
    if (targetsToBuild.isEmpty()) {
      targetConfigs = ImmutableSet.of(configuration);
    } else {
      // Collect the configuration of each top-level requested target. These may be different than
      // the build's top-level configuration because of self-transitions.
      ImmutableSet<BuildConfigurationValue> requestedTargetConfigs =
          targetsToBuild.stream()
              .map(ConfiguredTarget::getActual)
              .map(ConfiguredTarget::getConfigurationKey)
              .filter(Objects::nonNull)
              .distinct()
              .map((key) -> executor.getConfiguration(reporter, key))
              .collect(toImmutableSet());
      if (requestedTargetConfigs.size() == 1) {
        // All top-level targets have the same configuration, so use that one.
        targetConfigs = requestedTargetConfigs;
      } else if (requestedTargetConfigs.stream()
          .anyMatch(
              c -> c.getOutputDirectoryName().equals(configuration.getOutputDirectoryName()))) {
        // Mixed configs but at least one matches the top-level config's output path (this doesn't
        // mean it's the same as the top-level config: --trim_test_configuration means non-test
        // targets use the default output path but lack the top-level config's TestOptions). Set
        // symlinks to the top-level config so at least non-transitioned targets resolve. See
        // https://github.com/bazelbuild/bazel/issues/17081.
        targetConfigs = ImmutableSet.of(configuration);
      } else {
        // Mixed configs, none of which include the top-level config. Delete the symlinks because
        // they won't contain any relevant data. This is handled in the
        // createOutputDirectorySymlinks call below.
        targetConfigs = requestedTargetConfigs;
      }
    }

    String productName = runtime.getProductName();
    try (SilentCloseable c =
        Profiler.instance().profile("OutputDirectoryLinksUtils.createOutputDirectoryLinks")) {
      return OutputDirectoryLinksUtils.createOutputDirectoryLinks(
          runtime.getRuleClassProvider().getSymlinkDefinitions(),
          buildRequestOptions,
          env.getWorkspaceName(),
          env.getWorkspace(),
          env.getDirectories(),
          getReporter(),
          targetConfigs,
          options -> getConfiguration(executor, reporter, options),
          productName);
    }
  }

  /**
   * If a path is supplied, creates and installs an ExplanationHandler. Returns an instance on
   * success. Reports an error and returns null otherwise.
   */
  @Nullable
  private ExplanationHandler installExplanationHandler(
      PathFragment explanationPath, String allOptions) {
    if (explanationPath == null) {
      return null;
    }
    ExplanationHandler handler;
    try {
      InstrumentationOutput instrumentationOutput =
          runtime
              .getInstrumentationOutputFactory()
              .createInstrumentationOutput(
                  /* name= */ "explain",
                  /* destination= */ explanationPath,
                  DestinationRelativeTo.WORKSPACE_OR_HOME,
                  env,
                  getReporter(),
                  /* append= */ null,
                  /* internal= */ null);
      handler = new ExplanationHandler(instrumentationOutput.createOutputStream(), allOptions);
    } catch (IOException e) {
      getReporter()
          .handle(
              Event.warn(
                  String.format(
                      "Cannot write explanation of rebuilds to file '%s': %s",
                      explanationPath, e.getMessage())));
      return null;
    }
    getReporter()
        .handle(Event.info("Writing explanation of rebuilds to '" + explanationPath + "'"));
    getReporter().addHandler(handler);
    return handler;
  }

  /** Uninstalls the specified ExplanationHandler (if any) and closes the log file. */
  private void uninstallExplanationHandler(ExplanationHandler handler) {
    if (handler != null) {
      getReporter().removeHandler(handler);
      handler.log.close();
    }
  }

  /**
   * An ErrorEventListener implementation that records DEPCHECKER events into a log file, iff the
   * --explain flag is specified during a build.
   */
  private static class ExplanationHandler implements EventHandler, AutoCloseable {
    private final PrintWriter log;

    private ExplanationHandler(OutputStream log, String optionsDescription) {
      this.log = new PrintWriter(new OutputStreamWriter(log, StandardCharsets.UTF_8));
      this.log.println("Build options: " + optionsDescription);
    }

    @Override
    public void close() throws IOException {
      this.log.close();
    }

    @Override
    public void handle(Event event) {
      if (event.getKind() == EventKind.DEPCHECKER) {
        log.println(event.getMessage());
      }
    }
  }

  /**
   * Computes the result of the build. Sets the list of successful (up-to-date) targets in the
   * request object.
   *
   * @param configuredTargets The configured targets whose artifacts are to be built.
   */
  private static ImmutableSet<ConfiguredTarget> determineSuccessfulTargets(
      Collection<ConfiguredTarget> configuredTargets, Set<ConfiguredTargetKey> builtTargets) {
    // Maintain the ordering by copying builtTargets into an ImmutableSet.Builder in the same
    // iteration order as configuredTargets.
    ImmutableSet.Builder<ConfiguredTarget> successfulTargets = ImmutableSet.builder();
    for (ConfiguredTarget target : configuredTargets) {
      if (builtTargets.contains(ConfiguredTargetKey.fromConfiguredTarget(target))) {
        successfulTargets.add(target);
      }
    }
    return successfulTargets.build();
  }

  private static ImmutableSet<AspectKey> determineSuccessfulAspects(
      ImmutableSet<AspectKey> aspects, Set<AspectKey> builtAspects) {
    // Maintain the ordering.
    return aspects.stream().filter(builtAspects::contains).collect(ImmutableSet.toImmutableSet());
  }

  /** Get action cache if present or reload it from the on-disk cache. */
  private ActionCache getOrLoadActionCache() throws AbruptExitException {
    try {
      return env.getBlazeWorkspace().getOrLoadPersistentActionCache(getReporter());
    } catch (IOException e) {
      String message =
          String.format(
              "Couldn't create action cache: %s. If error persists, use 'bazel clean'.",
              e.getMessage());
      throw new AbruptExitException(
          DetailedExitCode.of(
              FailureDetail.newBuilder()
                  .setMessage(message)
                  .setActionCache(
                      FailureDetails.ActionCache.newBuilder()
                          .setCode(FailureDetails.ActionCache.Code.INITIALIZATION_FAILURE))
                  .build()),
          e);
    }
  }

  private Builder createBuilder(
      BuildRequest request,
      @Nullable ActionCache actionCache,
      SkyframeExecutor skyframeExecutor,
      ModifiedFileSet modifiedOutputFiles,
      boolean shouldStoreRemoteOutputMetadataInActionCache) {
    BuildRequestOptions options = request.getBuildOptions();

    skyframeExecutor.setActionOutputRoot(env.getActionTempsDirectory());

    Predicate<Action> executionFilter =
        CheckUpToDateFilter.fromOptions(request.getOptions(ExecutionOptions.class));
    ArtifactFactory artifactFactory = env.getSkyframeBuildView().getArtifactFactory();
    return new SkyframeBuilder(
        skyframeExecutor,
        env.getLocalResourceManager(),
        new ActionCacheChecker(
            actionCache,
            artifactFactory,
            skyframeExecutor.getActionKeyContext(),
            executionFilter,
            ActionCacheChecker.CacheConfig.builder()
                .setEnabled(options.useActionCache)
                .setVerboseExplanations(options.verboseExplanations)
                .setStoreOutputMetadata(shouldStoreRemoteOutputMetadataInActionCache)
                .build()),
        modifiedOutputFiles,
        env.getFileCache(),
        prefetcher,
        env.getOutputDirectoryHelper(),
        env.getRuntime().getBugReporter());
  }

  @VisibleForTesting
  public static void configureResourceManager(ResourceManager resourceMgr, BuildRequest request) {
    ExecutionOptions options = request.getOptions(ExecutionOptions.class);
    ImmutableMap<String, Double> cpuRam =
        ImmutableMap.of(
            ResourceSet.CPU,
            // Replace with 1.0 * ResourceConverter.HOST_CPUS.get() after flag deprecation
            options.localCpuResources,
            ResourceSet.MEMORY,
            // Replace with 0.67 * ResourceConverter.HOST_RAM.get() after flag deprecation
            options.localRamResources);
    ImmutableMap<String, Double> resources =
        Streams.concat(
                options.localExtraResources.stream(),
                cpuRam.entrySet().stream(),
                options.localResources.stream())
            .collect(
                ImmutableMap.toImmutableMap(
                    Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v2));

    resourceMgr.setAvailableResources(
        ResourceSet.create(
            resources, options.usingLocalTestJobs() ? options.localTestJobs : Integer.MAX_VALUE));

    resourceMgr.initializeCpuLoadFunctionality(
        MachineLoadProvider.instance(),
        options.experimentalCpuLoadScheduling,
        options.experimentalCpuLoadSchedulingWindowSize);
    resourceMgr.scheduleCpuLoadWindowUpdate();
  }

  /**
   * Writes the action cache files to disk, reporting any errors that occurred during writing and
   * capturing statistics.
   */
  private void saveActionCache(@Nullable ActionCache actionCache) {
    ActionCacheStatistics.Builder builder = ActionCacheStatistics.newBuilder();

    if (actionCache != null) {
      actionCache.mergeIntoActionCacheStatistics(builder);
      Duration duration = actionCache.getLoadTime();
      if (duration != null) {
        builder.setLoadTimeInMs(duration.toMillis());
      }

      AutoProfiler p =
          GoogleAutoProfilerUtils.profiledAndLogged("Saving action cache", ProfilerTask.INFO);
      try {
        builder.setSizeInBytes(actionCache.save());
      } catch (IOException e) {
        builder.setSizeInBytes(0);
        getReporter().handle(Event.error("I/O error while writing action log: " + e.getMessage()));
      } finally {
        builder.setSaveTimeInMs(Duration.ofNanos(p.completeAndGetElapsedTimeNanos()).toMillis());
      }
    }
    env.getReporter().post(new PostableActionCacheStats(builder.build()));
  }

  private Reporter getReporter() {
    return env.getReporter();
  }

  private Path getExecRoot() {
    return env.getExecRoot();
  }

  private static AbruptExitException createExitException(
      String messagePrefix, Code detailedCode, IOException e) {
    return new AbruptExitException(
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(String.format("%s: %s", messagePrefix, e.getMessage()))
                .setExecution(Execution.newBuilder().setCode(detailedCode))
                .build()),
        e);
  }

  /**
   * A listener that prepares the ExecutionProgressReceiver upon receiving the first
   * SomeExecutionStartedEvent. Only activated once a build.
   */
  private static class ExecutionProgressReceiverSetup {
    private final SkyframeExecutor skyframeExecutor;
    private final CommandEnvironment env;

    private final Stopwatch executionUnstartedTimer;
    private final AtomicBoolean progressReceiverStarted = new AtomicBoolean(false);

    private final int progressReportInterval;

    ExecutionProgressReceiverSetup(
        SkyframeExecutor skyframeExecutor,
        CommandEnvironment env,
        Stopwatch executionUnstartedTimer,
        int progressReportInterval) {
      this.skyframeExecutor = skyframeExecutor;
      this.env = env;
      this.executionUnstartedTimer = executionUnstartedTimer;
      this.progressReportInterval = progressReportInterval;
    }

    @Subscribe
    public void setupExecutionProgressReceiver(SomeExecutionStartedEvent event) {
      if (progressReceiverStarted.compareAndSet(false, true)) {
        // TODO(leba): count test actions
        ExecutionProgressReceiver executionProgressReceiver =
            new ExecutionProgressReceiver(/* exclusiveTestsCount= */ 0, env.getEventBus());
        env.getEventBus()
            .post(new ExecutionProgressReceiverAvailableEvent(executionProgressReceiver));

        ActionExecutionStatusReporter statusReporter =
            ActionExecutionStatusReporter.create(env.getReporter(), skyframeExecutor.getEventBus());
        skyframeExecutor.setActionExecutionProgressReportingObjects(
            executionProgressReceiver, executionProgressReceiver, statusReporter);
        skyframeExecutor.setExecutionProgressReceiver(executionProgressReceiver);

        skyframeExecutor.setAndStartWatchdog(
            new ActionExecutionInactivityWatchdog(
                executionProgressReceiver.createInactivityMonitor(statusReporter),
                executionProgressReceiver.createInactivityReporter(
                    statusReporter, skyframeExecutor.getIsBuildingExclusiveArtifacts()),
                progressReportInterval));
      }
      // no lock necessary since this method is thread-safe.
      if (event.countedInExecutionTime() && !executionUnstartedTimer.isRunning()) {
        executionUnstartedTimer.start();
        env.getEventBus().unregister(this);
      }
    }
  }
}
