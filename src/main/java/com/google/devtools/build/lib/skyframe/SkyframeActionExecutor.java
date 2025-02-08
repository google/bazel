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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Comparators.max;
import static com.google.common.collect.Comparators.min;
import static com.google.devtools.build.lib.buildtool.BuildRequestOptions.MAX_JOBS;
import static java.lang.Math.max;
import static java.lang.Math.min;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionCacheChecker;
import com.google.devtools.build.lib.actions.ActionCacheChecker.Token;
import com.google.devtools.build.lib.actions.ActionCompletionEvent;
import com.google.devtools.build.lib.actions.ActionConcurrencyMeter;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionContext.ActionContextRegistry;
import com.google.devtools.build.lib.actions.ActionExecutedEvent;
import com.google.devtools.build.lib.actions.ActionExecutedEvent.ErrorTiming;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext.LostInputsCheck;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionExecutionStatusReporter;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputMap;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLogBufferPathGenerator;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionOutputDirectoryHelper;
import com.google.devtools.build.lib.actions.ActionOutputDirectoryHelper.CreateOutputDirectoryException;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.ActionResultReceivedEvent;
import com.google.devtools.build.lib.actions.ActionScanningCompletedEvent;
import com.google.devtools.build.lib.actions.ActionStartedEvent;
import com.google.devtools.build.lib.actions.AlreadyReportedActionExecutionException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.OwnerlessArtifactWrapper;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.CachedActionEvent;
import com.google.devtools.build.lib.actions.DelegatingPairInputMetadataProvider;
import com.google.devtools.build.lib.actions.DiscoveredModulesPruner;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FilesetOutputTree;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.LostInputsActionExecutionException;
import com.google.devtools.build.lib.actions.NotifyOnActionCacheHit;
import com.google.devtools.build.lib.actions.NotifyOnActionCacheHit.ActionCachedContext;
import com.google.devtools.build.lib.actions.OutputChecker;
import com.google.devtools.build.lib.actions.PackageRootResolver;
import com.google.devtools.build.lib.actions.ScanningActionEvent;
import com.google.devtools.build.lib.actions.SpawnActionExecutionException;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.StoppedScanningActionEvent;
import com.google.devtools.build.lib.actions.ThreadStateReceiver;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.actions.cache.OutputMetadataStore;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions;
import com.google.devtools.build.lib.buildtool.BuildRequestOptions;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.Execution;
import com.google.devtools.build.lib.server.FailureDetails.Execution.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.ActionExecutionState.ActionStep;
import com.google.devtools.build.lib.skyframe.ActionExecutionState.ActionStepOrResult;
import com.google.devtools.build.lib.skyframe.ActionExecutionState.SharedActionCallback;
import com.google.devtools.build.lib.util.CrashFailureDetails;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ResourceUsage;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystem.NotASymlinkException;
import com.google.devtools.build.lib.vfs.OutputPermissions;
import com.google.devtools.build.lib.vfs.OutputService;
import com.google.devtools.build.lib.vfs.OutputService.ActionFileSystemType;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.XattrProvider;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.common.options.OptionsProvider;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Action executor: takes care of preparing an action for execution, executing it, validating that
 * all output artifacts were created, error reporting, etc.
 */
public final class SkyframeActionExecutor {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static final OutputMetadataStore THROWING_OUTPUT_METADATA_STORE_FOR_ACTIONFS =
      new OutputMetadataStore() {
        @Override
        public FileArtifactValue getOutputMetadata(ActionInput output) {
          throw new IllegalStateException();
        }

        @Override
        public TreeArtifactValue getTreeArtifactValue(SpecialArtifact treeArtifact) {
          throw new IllegalStateException();
        }

        @Override
        public void markOmitted(Artifact output) {
          throw new IllegalStateException();
        }

        @Override
        public boolean artifactOmitted(Artifact artifact) {
          throw new IllegalStateException();
        }

        @Override
        public void resetOutputs(Iterable<? extends Artifact> outputs) {
          throw new IllegalStateException();
        }

        @Override
        public void injectFile(Artifact output, FileArtifactValue metadata) {
          throw new IllegalStateException(
              "Unexpected output during input discovery: " + output + " (" + metadata + ")");
        }

        @Override
        public void injectTree(SpecialArtifact output, TreeArtifactValue tree) {
          // ActionFS injects only metadata for files.
          throw new UnsupportedOperationException(
              String.format(
                  "Unexpected injection of: %s for a tree artifact value: %s", output, tree));
        }
      };

  private final ActionKeyContext actionKeyContext;
  private final MetadataConsumerForMetrics outputArtifactsSeen;
  private final MetadataConsumerForMetrics outputArtifactsFromActionCache;
  private final SyscallCache syscallCache;
  private final Function<SkyKey, ThreadStateReceiver> threadStateReceiverFactory;
  private Reporter reporter;
  private ImmutableMap<String, String> clientEnv = ImmutableMap.of();
  private Executor executorEngine;
  private ExtendedEventHandler progressSuppressingEventHandler;
  private ActionLogBufferPathGenerator actionLogBufferPathGenerator;
  private ActionCacheChecker actionCacheChecker;
  private final Profiler profiler = Profiler.instance();

  // We keep track of actions already executed this build in order to avoid executing a shared
  // action twice. Note that we may still unnecessarily re-execute the action on a subsequent
  // build: say actions A and B are shared. If A is requested on the first build and then B is
  // requested on the second build, we will execute B even though its output files are up to date.
  // However, we will not re-execute A on a subsequent build.
  // We do not allow the shared action to re-execute in the same build, even after the first
  // action has finished execution, because a downstream action might be reading the output file
  // at the same time as the shared action was writing to it.
  //
  // This map is also used for Actions that try to execute twice because they have discovered
  // headers -- the SkyFunction tries to declare a dep on the missing headers and has to restart.
  // We don't want to execute the action again on the second entry to the SkyFunction.
  // In both cases, we store the already-computed ActionExecutionValue to avoid having to compute it
  // again.
  private ConcurrentMap<OwnerlessArtifactWrapper, ActionExecutionState> buildActionMap;

  // We also keep track of actions which were rewound this build, possibly from a
  // previously-completed state. When re-evaluated, these actions should not emit progress events,
  // in order to not confuse the downstream consumers of action-related event streams, which may
  // (reasonably) have expected an action to be executed at most once per build.
  //
  // Note: actions which fail due to lost inputs, and get reset (having not completed successfully),
  // will not have any events suppressed during their second evaluation. Consumers of events which
  // get emitted before execution (e.g. ActionStartedEvent, SpawnExecutedEvent) must support
  // receiving more than one of those events per action.
  private Set<OwnerlessArtifactWrapper> rewoundActions;

  private ActionOutputDirectoryHelper outputDirectoryHelper;

  private OptionsProvider options;
  private final AtomicBoolean hadExecutionError = new AtomicBoolean(false);
  private boolean freeDiscoveredInputsAfterExecution;
  private InputMetadataProvider perBuildFileCache;
  private ActionInputPrefetcher actionInputPrefetcher;

  /** These variables are nulled out between executions. */
  @Nullable private ProgressSupplier progressSupplier;

  @Nullable private ActionCompletedReceiver completionReceiver;

  private final AtomicReference<ActionExecutionStatusReporter> statusReporterRef;
  private OutputService outputService;
  private boolean finalizeActions;
  private boolean rewindingEnabled;
  private final Supplier<ImmutableList<Root>> sourceRootSupplier;

  private DiscoveredModulesPruner discoveredModulesPruner;

  @Nullable private Semaphore cacheHitSemaphore;

  private boolean useAsyncExecution;

  /**
   * If not null, we use this meter to limit the number of concurrent actions.
   *
   * <p>With internal changes in JDK19, ForkJoinPool can spawn additional threads (work-stealing)
   * which means we couldn't rely on it if we want the number of concurrent actions to be exactly
   * equal to --jobs.
   *
   * <p>When async exec is enabled, we execute actions with virtual threads, so there is no thread
   * pool. Thus, this meter is used to limit the number of concurrent actions.
   */
  @Nullable private ActionConcurrencyMeter actionConcurrencyMeter;

  SkyframeActionExecutor(
      ActionKeyContext actionKeyContext,
      MetadataConsumerForMetrics outputArtifactsSeen,
      MetadataConsumerForMetrics outputArtifactsFromActionCache,
      AtomicReference<ActionExecutionStatusReporter> statusReporterRef,
      Supplier<ImmutableList<Root>> sourceRootSupplier,
      SyscallCache syscallCache,
      Function<SkyKey, ThreadStateReceiver> threadStateReceiverFactory) {
    this.actionKeyContext = actionKeyContext;
    this.outputArtifactsSeen = outputArtifactsSeen;
    this.outputArtifactsFromActionCache = outputArtifactsFromActionCache;
    this.statusReporterRef = statusReporterRef;
    this.sourceRootSupplier = sourceRootSupplier;
    this.syscallCache = syscallCache;
    this.threadStateReceiverFactory = threadStateReceiverFactory;
  }

  SharedActionCallback getSharedActionCallback(
      ExtendedEventHandler eventHandler,
      boolean hasDiscoveredInputs,
      Action action,
      ActionLookupData actionLookupData) {
    return new SharedActionCallback() {
      @Override
      public void actionStarted() {
        if (hasDiscoveredInputs) {
          eventHandler.post(new ActionScanningCompletedEvent(action, actionLookupData));
        }
      }

      @Override
      public void actionCompleted() {
        if (completionReceiver != null) {
          completionReceiver.actionCompleted(actionLookupData);
        }
      }
    };
  }

  void prepareForExecution(
      Reporter reporter,
      Executor executor,
      OptionsProvider options,
      ActionCacheChecker actionCacheChecker,
      ActionOutputDirectoryHelper outputDirectoryHelper,
      OutputService outputService,
      boolean trackIncrementalState) {
    this.reporter = checkNotNull(reporter);
    this.executorEngine = checkNotNull(executor);
    this.progressSuppressingEventHandler = new ProgressSuppressingEventHandler(reporter);

    var buildRequestOptions = options.getOptions(BuildRequestOptions.class);

    // Start with a new map each build so there's no issue with internal resizing.
    this.buildActionMap = Maps.newConcurrentMap();
    this.rewoundActions = Sets.newConcurrentHashSet();
    this.hadExecutionError.set(false);
    this.actionCacheChecker = checkNotNull(actionCacheChecker);
    // Don't cache possibly stale data from the last build.
    this.options = options;
    // Cache some option values for performance, since we consult them on every action.
    this.finalizeActions = buildRequestOptions.finalizeActions;
    this.rewindingEnabled = buildRequestOptions.rewindLostInputs;
    this.outputService = checkNotNull(outputService);
    this.outputDirectoryHelper = outputDirectoryHelper;

    // Retaining discovered inputs is only worthwhile for incremental builds or builds with extra
    // actions, which consume their shadowed action's discovered inputs.
    freeDiscoveredInputsAfterExecution =
        !trackIncrementalState && options.getOptions(CoreOptions.class).actionListeners.isEmpty();

    this.useAsyncExecution = buildRequestOptions.useAsyncExecution;

    this.cacheHitSemaphore =
        (!this.useAsyncExecution && options.getOptions(CoreOptions.class).throttleActionCacheCheck)
            ? new Semaphore(ResourceUsage.getAvailableProcessors())
            : null;

    if (buildRequestOptions.useSemaphoreForJobs || this.useAsyncExecution) {
      var minActiveAction = buildRequestOptions.jobs;
      var maxActiveAction =
          this.useAsyncExecution
              ? min(MAX_JOBS, buildRequestOptions.asyncExecutionMaxConcurrentActions)
              : buildRequestOptions.jobs;
      this.actionConcurrencyMeter =
          new ActionConcurrencyMeter(minActiveAction, max(minActiveAction, maxActiveAction));
    }
  }

  public void setActionLogBufferPathGenerator(
      ActionLogBufferPathGenerator actionLogBufferPathGenerator) {
    this.actionLogBufferPathGenerator = actionLogBufferPathGenerator;
  }

  public void setClientEnv(Map<String, String> clientEnv) {
    // Copy once here, instead of on every construction of ActionExecutionContext.
    this.clientEnv = ImmutableMap.copyOf(clientEnv);
  }

  public OutputService getOutputService() {
    return outputService;
  }

  @Nullable
  public ActionInputPrefetcher getActionInputPrefetcher() {
    return actionInputPrefetcher;
  }

  ActionFileSystemType actionFileSystemType() {
    return outputService.actionFileSystemType();
  }

  Path getExecRoot() {
    return executorEngine.getExecRoot();
  }

  ActionContextRegistry getActionContextRegistry() {
    return executorEngine;
  }

  boolean useArchivedTreeArtifacts(ActionAnalysisMetadata action) {
    return options
        .getOptions(CoreOptions.class)
        .archivedArtifactsMnemonicsFilter
        .test(action.getMnemonic());
  }

  boolean publishTargetSummaries() {
    return options.getOptions(BuildEventProtocolOptions.class).publishTargetSummary;
  }

  public boolean rewindingEnabled() {
    return rewindingEnabled;
  }

  OutputPermissions getOutputPermissions() {
    return options.getOptions(CoreOptions.class).experimentalWritableOutputs
        ? OutputPermissions.WRITABLE
        : OutputPermissions.READONLY;
  }

  XattrProvider getXattrProvider() {
    return checkNotNull(outputService.getXattrProvider(syscallCache));
  }

  /** REQUIRES: {@link #actionFileSystemType()} to be not {@code DISABLED}. */
  FileSystem createActionFileSystem(
      String relativeOutputPath,
      ActionInputMap inputArtifactData,
      Iterable<Artifact> outputArtifacts) {
    return outputService.createActionFileSystem(
        executorEngine.getFileSystem(),
        executorEngine.getExecRoot().asFragment(),
        relativeOutputPath,
        sourceRootSupplier.get(),
        inputArtifactData,
        outputArtifacts,
        rewindingEnabled);
  }

  private void updateActionFileSystemContext(
      Action action,
      FileSystem actionFileSystem,
      Environment env,
      OutputMetadataStore outputMetadataStore,
      ImmutableMap<Artifact, FilesetOutputTree> filesets) {
    outputService.updateActionFileSystemContext(
        action, actionFileSystem, env, outputMetadataStore, filesets);
  }

  void executionOver() {
    // These may transitively holds a bunch of heavy objects, so it's important to clear it at the
    // end of a build.
    this.reporter = null;
    this.options = null;
    this.executorEngine = null;
    this.progressSuppressingEventHandler = null;
    this.outputService = null;
    this.buildActionMap = null;
    this.rewoundActions = null;
    this.actionCacheChecker = null;
    this.outputDirectoryHelper = null;
    if (this.actionConcurrencyMeter != null) {
      this.actionConcurrencyMeter.stop();
      this.actionConcurrencyMeter = null;
    }
  }

  /**
   * Due to multi-threading, a null return value from this method does not guarantee that there is
   * no such action - a concurrent thread may already be executing the same (shared) action. Any
   * such race is resolved in the subsequent call to {@link #executeAction}.
   */
  @Nullable
  ActionExecutionState probeActionExecution(Action action) {
    return buildActionMap.get(new OwnerlessArtifactWrapper(action.getPrimaryOutput()));
  }

  /** Determines whether the given action was rewound during the current build. */
  public boolean wasRewound(Action action) {
    return rewoundActions.contains(new OwnerlessArtifactWrapper(action.getPrimaryOutput()));
  }

  /**
   * Determines whether the action should have its progress events emitted.
   *
   * <p>Returns {@code false} for rewound actions, indicating that their progress events should be
   * suppressed.
   */
  boolean shouldEmitProgressEvents(Action action) {
    return !wasRewound(action);
  }

  /**
   * Called to prepare action execution states for rewinding after {@code failedAction} observed
   * lost inputs.
   */
  public void prepareForRewinding(
      ActionLookupData failedKey, Action failedAction, ImmutableList<Action> depsToRewind) {
    var ownerlessArtifactWrapper = new OwnerlessArtifactWrapper(failedAction.getPrimaryOutput());
    ActionExecutionState state = buildActionMap.get(ownerlessArtifactWrapper);
    if (state != null) {
      // If an action failed from lost inputs during input discovery then it won't have a state to
      // obsolete.
      state.obsolete(failedKey, buildActionMap, ownerlessArtifactWrapper);
    }
    if (!actionFileSystemType().inMemoryFileSystem()) {
      outputDirectoryHelper.invalidateTreeArtifactDirectoryCreation(failedAction.getOutputs());
    }
    for (Action dep : depsToRewind) {
      prepareDepForRewinding(failedKey, dep);
    }
  }

  public void prepareDepForRewinding(SkyKey failedKey, Action dep) {
    OwnerlessArtifactWrapper ownerlessArtifactWrapper =
        new OwnerlessArtifactWrapper(dep.getPrimaryOutput());
    ActionExecutionState actionExecutionState = buildActionMap.get(ownerlessArtifactWrapper);
    if (actionExecutionState != null) {
      actionExecutionState.obsolete(failedKey, buildActionMap, ownerlessArtifactWrapper);
    }
    rewoundActions.add(ownerlessArtifactWrapper);
    if (!actionFileSystemType().inMemoryFileSystem()) {
      outputDirectoryHelper.invalidateTreeArtifactDirectoryCreation(dep.getOutputs());
    }
    // Evict the rewinding action from the action cache to ensure that it is executed.
    if (actionCacheChecker.enabled()) {
      actionCacheChecker.removeCacheEntry(dep);
    }
  }

  /**
   * Executes the provided action on the current thread. Returns the ActionExecutionValue with the
   * result, either computed here or already computed on another thread.
   */
  @SuppressWarnings("SynchronizeOnNonFinalField")
  ActionExecutionValue executeAction(
      Environment env,
      Action action,
      InputMetadataProvider inputMetadataProvider,
      ActionOutputMetadataStore outputMetadataStore,
      long actionStartTime,
      ActionLookupData actionLookupData,
      ArtifactExpander artifactExpander,
      ImmutableMap<Artifact, FilesetOutputTree> expandedFilesets,
      ImmutableMap<Artifact, FilesetOutputTree> topLevelFilesets,
      @Nullable FileSystem actionFileSystem,
      @Nullable Object skyframeDepsResult,
      ActionPostprocessing postprocessing,
      boolean hasDiscoveredInputs)
      throws ActionExecutionException, InterruptedException {
    if (actionFileSystem != null) {
      updateActionFileSystemContext(
          action, actionFileSystem, env, outputMetadataStore, expandedFilesets);
    }

    ActionExecutionContext actionExecutionContext =
        getContext(
            action,
            inputMetadataProvider,
            outputMetadataStore,
            artifactExpander,
            topLevelFilesets,
            actionFileSystem,
            skyframeDepsResult,
            actionLookupData);

    if (actionCacheChecker.isActionExecutionProhibited(action)) {
      // We can't execute an action (e.g. because --check_???_up_to_date option was used). Fail the
      // build instead.
      String message = action.prettyPrint() + " is not up-to-date";
      DetailedExitCode code = createDetailedExitCode(message, Code.ACTION_NOT_UP_TO_DATE);
      ActionExecutionException e = new ActionExecutionException(message, action, false, code);
      Event error = Event.error(e.getMessage());
      synchronized (reporter) {
        reporter.handle(error);
      }
      throw e;
    }

    // Use computeIfAbsent to handle concurrent attempts to execute the same shared action.
    ActionExecutionState activeAction =
        buildActionMap.computeIfAbsent(
            new OwnerlessArtifactWrapper(action.getPrimaryOutput()),
            (unusedKey) ->
                new ActionExecutionState(
                    actionLookupData,
                    new ActionRunner(
                        action,
                        inputMetadataProvider,
                        outputMetadataStore,
                        actionStartTime,
                        actionExecutionContext,
                        actionLookupData,
                        postprocessing)));

    SharedActionCallback callback =
        getSharedActionCallback(env.getListener(), hasDiscoveredInputs, action, actionLookupData);

    ActionExecutionValue result = null;
    ActionExecutionException finalException = null;

    try {
      result = activeAction.getResultOrDependOnFuture(env, actionLookupData, action, callback);
    } catch (ActionExecutionException e) {
      finalException = e;
    }

    if (result != null || finalException != null) {
      closeContext(actionExecutionContext, action, finalException);
    }
    return result;
  }

  void maybeAcquireActionExecutionSemaphore() {
    if (actionConcurrencyMeter != null) {
      // Acquire uninterruptibly because ActionExecutionFunction is not expected to check for
      // interrupts. See test SequencedSkyframeExecutorTest#testThreeSharedActionsRacing.
      actionConcurrencyMeter.acquireUninterruptibly();
    }
  }

  void maybeReleaseActionExecutionSemaphore() {
    if (actionConcurrencyMeter != null) {
      actionConcurrencyMeter.release();
    }
  }

  private ExtendedEventHandler selectEventHandler(Action action) {
    return selectEventHandler(shouldEmitProgressEvents(action));
  }

  private ExtendedEventHandler selectEventHandler(boolean emitProgressEvents) {
    return emitProgressEvents ? reporter : progressSuppressingEventHandler;
  }

  private ActionExecutionContext getContext(
      Action action,
      InputMetadataProvider inputMetadataProvider,
      OutputMetadataStore outputMetadataStore,
      ArtifactExpander artifactExpander,
      ImmutableMap<Artifact, FilesetOutputTree> topLevelFilesets,
      @Nullable FileSystem actionFileSystem,
      @Nullable Object skyframeDepsResult,
      ActionLookupData actionLookupData) {
    boolean emitProgressEvents = shouldEmitProgressEvents(action);
    ArtifactPathResolver artifactPathResolver =
        ArtifactPathResolver.createPathResolver(actionFileSystem, executorEngine.getExecRoot());
    FileOutErr fileOutErr = actionLogBufferPathGenerator.generate(artifactPathResolver);
    return new ActionExecutionContext(
        executorEngine,
        createFileCache(inputMetadataProvider, actionFileSystem),
        actionInputPrefetcher,
        actionKeyContext,
        outputMetadataStore,
        rewindingEnabled,
        lostInputsCheck(actionFileSystem, action, outputService),
        fileOutErr,
        selectEventHandler(emitProgressEvents),
        clientEnv,
        topLevelFilesets,
        artifactExpander,
        actionFileSystem,
        skyframeDepsResult,
        discoveredModulesPruner,
        syscallCache,
        threadStateReceiverFactory.apply(actionLookupData));
  }

  private static void closeContext(
      ActionExecutionContext context,
      Action action,
      @Nullable ActionExecutionException finalException)
      throws ActionExecutionException {
    try (context) {
      if (finalException != null) {
        throw finalException;
      }
    } catch (IOException e) {
      String message = "Failed to close action output: " + e.getMessage();
      DetailedExitCode code = createDetailedExitCode(message, Code.ACTION_OUTPUT_CLOSE_FAILURE);
      throw new ActionExecutionException(message, e, action, /* catastrophe= */ false, code);
    }
  }

  /**
   * Checks the action cache to see if {@code action} needs to be executed, or is up to date.
   * Returns a token with the semantics of {@link ActionCacheChecker#getTokenIfNeedToExecute}: null
   * if the action is up to date, and non-null if it needs to be executed, in which case that token
   * should be provided to the ActionCacheChecker after execution.
   */
  Token checkActionCache(
      ExtendedEventHandler eventHandler,
      Action action,
      InputMetadataProvider inputMetadataProvider,
      OutputMetadataStore outputMetadataStore,
      ArtifactPathResolver artifactPathResolver,
      ArtifactExpander artifactExpander,
      long actionStartTime,
      List<Artifact> resolvedCacheArtifacts,
      Map<String, String> clientEnv)
      throws ActionExecutionException, InterruptedException {
    Token token;
    RemoteOptions remoteOptions;
    SortedMap<String, String> remoteDefaultProperties;
    EventHandler handler;
    OutputChecker outputChecker = null;

    if (cacheHitSemaphore != null) {
      try (SilentCloseable c = profiler.profile(ProfilerTask.ACTION_CHECK, "acquiring semaphore")) {
        cacheHitSemaphore.acquire();
      }
    }
    try (SilentCloseable c = profiler.profile(ProfilerTask.ACTION_CHECK, action.describe())) {
      remoteOptions = this.options.getOptions(RemoteOptions.class);
      remoteDefaultProperties =
          remoteOptions != null
              ? remoteOptions.getRemoteDefaultExecProperties()
              : ImmutableSortedMap.of();
      outputChecker = outputService.getOutputChecker();
      handler =
          options.getOptions(BuildRequestOptions.class).explanationPath != null ? reporter : null;
      token =
          actionCacheChecker.getTokenIfNeedToExecute(
              action,
              resolvedCacheArtifacts,
              clientEnv,
              getOutputPermissions(),
              handler,
              inputMetadataProvider,
              outputMetadataStore,
              artifactExpander,
              remoteDefaultProperties,
              outputChecker);

      if (token == null) {
        boolean eventPosted = false;

        if (action instanceof NotifyOnActionCacheHit notify) {
          ExtendedEventHandler contextEventHandler = selectEventHandler(action);
          ActionCachedContext context =
              new ActionCachedContext() {
                @Override
                public ExtendedEventHandler getEventHandler() {
                  return contextEventHandler;
                }

                @Override
                public Path getExecRoot() {
                  return executorEngine.getExecRoot();
                }

                @Override
                public ArtifactPathResolver getPathResolver() {
                  return artifactPathResolver;
                }

                @Override
                public <T extends ActionContext> T getContext(Class<? extends T> type) {
                  return executorEngine.getContext(type);
                }
              };
          boolean recordActionCacheHit = notify.actionCacheHit(context);
          if (!recordActionCacheHit) {
            token =
                actionCacheChecker.getTokenUnconditionallyAfterFailureToRecordActionCacheHit(
                    action,
                    resolvedCacheArtifacts,
                    clientEnv,
                    getOutputPermissions(),
                    handler,
                    inputMetadataProvider,
                    outputMetadataStore,
                    artifactExpander,
                    remoteDefaultProperties,
                    outputChecker);
          }
        }

        // We still need to check the outputs so that output file data is available to the value.
        // Filesets cannot be cached in the action cache, so it is fine to pass null here.
        var unused =
            checkOutputs(
                action,
                outputMetadataStore,
                /* filesetOutputForMetrics= */ null,
                /* isActionCacheHitForMetrics= */ true);
        if (!eventPosted) {
          eventHandler.post(
              new CachedActionEvent(
                  action, inputMetadataProvider, actionStartTime, BlazeClock.nanoTime()));
        }
      }
    } catch (UserExecException e) {
      throw ActionExecutionException.fromExecException(e, action);
    } finally {
      if (cacheHitSemaphore != null) {
        cacheHitSemaphore.release();
      }
    }
    return token;
  }

  void updateActionCache(
      Action action,
      InputMetadataProvider inputMetadataProvider,
      OutputMetadataStore outputMetadataStore,
      ArtifactExpander artifactExpander,
      Token token,
      Map<String, String> clientEnv)
      throws ActionExecutionException, InterruptedException {
    if (!actionCacheChecker.enabled()) {
      return;
    }
    final SortedMap<String, String> remoteDefaultProperties;
    try {
      RemoteOptions remoteOptions = this.options.getOptions(RemoteOptions.class);
      remoteDefaultProperties =
          remoteOptions != null
              ? remoteOptions.getRemoteDefaultExecProperties()
              : ImmutableSortedMap.of();
    } catch (UserExecException e) {
      throw ActionExecutionException.fromExecException(e, action);
    }

    try {
      actionCacheChecker.updateActionCache(
          action,
          token,
          inputMetadataProvider,
          outputMetadataStore,
          artifactExpander,
          clientEnv,
          getOutputPermissions(),
          remoteDefaultProperties);
    } catch (IOException e) {
      // Skyframe has already done all the filesystem access needed for outputs and swallows
      // IOExceptions for inputs. So an IOException is impossible here.
      throw new IllegalStateException(
          "failed to update action cache for "
              + action.prettyPrint()
              + ", but all outputs should already have been checked",
          e);
    }
  }

  @Nullable
  List<Artifact> getActionCachedInputs(Action action, PackageRootResolver resolver)
      throws AlreadyReportedActionExecutionException, InterruptedException {
    try {
      return actionCacheChecker.getCachedInputs(action, resolver);
    } catch (PackageRootResolver.PackageRootException e) {
      printError(e.getMessage(), action);
      throw new AlreadyReportedActionExecutionException(
          new ActionExecutionException(
              e,
              action,
              /* catastrophe= */ false,
              DetailedExitCode.of(
                  FailureDetail.newBuilder()
                      .setMessage(e.getMessage())
                      .setIncludeScanning(e.getError())
                      .build())));
    }
  }

  /**
   * Perform dependency discovery for action, which must discover its inputs.
   *
   * <p>This method is just a wrapper around {@link Action#discoverInputs} that properly processes
   * any {@link ActionExecutionException} thrown before rethrowing it to the caller.
   */
  NestedSet<Artifact> discoverInputs(
      Action action,
      ActionLookupData actionLookupData,
      InputMetadataProvider inputMetadataProvider,
      OutputMetadataStore outputMetadataStore,
      Environment env,
      @Nullable FileSystem actionFileSystem)
      throws ActionExecutionException, InterruptedException {
    FileOutErr fileOutErr =
        actionLogBufferPathGenerator.generate(
            ArtifactPathResolver.createPathResolver(
                actionFileSystem, executorEngine.getExecRoot()));
    ExtendedEventHandler eventHandler = selectEventHandler(action);
    ActionExecutionContext actionExecutionContext =
        ActionExecutionContext.forInputDiscovery(
            executorEngine,
            createFileCache(inputMetadataProvider, actionFileSystem),
            actionInputPrefetcher,
            actionKeyContext,
            outputMetadataStore,
            rewindingEnabled,
            lostInputsCheck(actionFileSystem, action, outputService),
            fileOutErr,
            eventHandler,
            clientEnv,
            env,
            actionFileSystem,
            discoveredModulesPruner,
            syscallCache,
            threadStateReceiverFactory.apply(actionLookupData));
    if (actionFileSystem != null) {
      updateActionFileSystemContext(
          action,
          actionFileSystem,
          env,
          THROWING_OUTPUT_METADATA_STORE_FOR_ACTIONFS,
          /* filesets= */ ImmutableMap.of());
      // Note that when not using ActionFS, a global setup of the parent directories of the OutErr
      // streams is sufficient.
      setupActionFsFileOutErr(fileOutErr, action);
    }
    eventHandler.post(new ScanningActionEvent(action));

    ActionExecutionException finalException = null;
    try {
      NestedSet<Artifact> artifacts = action.discoverInputs(actionExecutionContext);

      // Input discovery may have been affected by lost inputs. If an action filesystem is used, it
      // may know whether inputs were lost. We should fail fast if any were; rewinding may be able
      // to fix it.
      checkActionFileSystemForLostInputs(actionFileSystem, action, outputService);

      return artifacts;
    } catch (ActionExecutionException e) {
      // Input discovery failures may be caused by lost inputs. Lost input failures have higher
      // priority because rewinding may be able to restore what was lost and allow the action to
      // complete without error.
      if (!(e instanceof LostInputsActionExecutionException)) {
        try {
          checkActionFileSystemForLostInputs(actionFileSystem, action, outputService);
        } catch (LostInputsActionExecutionException lostInputsException) {
          e = lostInputsException;
        }
      }

      Path primaryOutputPath = actionExecutionContext.getInputPath(action.getPrimaryOutput());
      if (e instanceof LostInputsActionExecutionException lostInputsException) {
        // If inputs were lost during input discovery, then enrich the exception, informing action
        // rewinding machinery that these lost inputs are now Skyframe deps of the action.
        lostInputsException.setFromInputDiscovery();
        enrichLostInputsException(
            primaryOutputPath, actionLookupData, fileOutErr, lostInputsException);
        finalException = lostInputsException;
      } else {
        finalException =
            processAndGetExceptionToThrow(
                env.getListener(),
                primaryOutputPath,
                action,
                e,
                fileOutErr,
                ErrorTiming.BEFORE_EXECUTION);
      }
      throw finalException;
    } finally {
      eventHandler.post(new StoppedScanningActionEvent(action));
      closeContext(actionExecutionContext, action, finalException);
    }
  }

  private InputMetadataProvider createFileCache(
      InputMetadataProvider graphFileCache, @Nullable FileSystem actionFileSystem) {
    if (actionFileSystem instanceof InputMetadataProvider inputMetadataProvider) {
      return inputMetadataProvider;
    }
    return new DelegatingPairInputMetadataProvider(graphFileCache, perBuildFileCache);
  }

  /**
   * This method should be called if the builder encounters an error during execution. This allows
   * the builder to record that it encountered at least one error, and may make it swallow its
   * output to prevent spamming the user any further.
   */
  void recordExecutionError() {
    hadExecutionError.set(true);
  }

  /**
   * Returns true if the Builder is winding down (i.e. cancelling outstanding actions and preparing
   * to abort.) The builder is winding down iff:
   *
   * <ul>
   *   <li>we had an execution error
   *   <li>we are not running with --keep_going
   * </ul>
   */
  private boolean isBuilderAborting() {
    return hadExecutionError.get() && !options.getOptions(KeepGoingOption.class).keepGoing;
  }

  public void configure(
      InputMetadataProvider fileCache,
      ActionInputPrefetcher actionInputPrefetcher,
      DiscoveredModulesPruner discoveredModulesPruner) {
    this.perBuildFileCache = fileCache;
    this.actionInputPrefetcher = actionInputPrefetcher;
    this.discoveredModulesPruner = discoveredModulesPruner;
  }

  /**
   * Temporary interface to allow delegation of action postprocessing to ActionExecutionFunction.
   * The current implementation requires access to local fields in ActionExecutionFunction.
   */
  interface ActionPostprocessing {
    void run(
        Environment env,
        Action action,
        InputMetadataProvider inputMetadataProvider,
        OutputMetadataStore outputMetadataStore,
        Map<String, String> clientEnv)
        throws InterruptedException, ActionExecutionException;
  }

  /** Represents an action that needs to be run. */
  private final class ActionRunner extends ActionStep {
    private final Action action;
    private final InputMetadataProvider inputMetadataProvider;
    private final ActionOutputMetadataStore outputMetadataStore;
    private final long actionStartTimeNanos;
    private final ActionExecutionContext actionExecutionContext;
    private final ActionLookupData actionLookupData;
    @Nullable private final ActionExecutionStatusReporter statusReporter;
    private final ActionPostprocessing postprocessing;

    ActionRunner(
        Action action,
        InputMetadataProvider inputMetadataProvider,
        ActionOutputMetadataStore outputMetadataStore,
        long actionStartTimeNanos,
        ActionExecutionContext actionExecutionContext,
        ActionLookupData actionLookupData,
        ActionPostprocessing postprocessing) {
      this.action = action;
      this.inputMetadataProvider = inputMetadataProvider;
      this.outputMetadataStore = outputMetadataStore;
      this.actionStartTimeNanos = actionStartTimeNanos;
      this.actionExecutionContext = actionExecutionContext;
      this.actionLookupData = actionLookupData;
      this.statusReporter = statusReporterRef.get();
      this.postprocessing = postprocessing;
    }

    @SuppressWarnings("LogAndThrow") // Thrown exception shown in user output, not info logs.
    @Override
    public ActionStepOrResult run(Environment env)
        throws LostInputsActionExecutionException, InterruptedException {
      // There are three ExtendedEventHandler instances available while this method is running.
      //   SkyframeActionExecutor.this.reporter
      //   actionExecutionContext.getEventHandler
      //   env.getListener
      // Apparently, one isn't enough.
      //
      // Progress events that are generated in this class should be posted to env.getListener, while
      // progress events that are generated in the Action implementation are posted to
      // actionExecutionContext.getEventHandler. The reason for this is action rewinding, in which
      // case env.getListener may be a ProgressSuppressingEventHandler. See shouldEmitProgressEvents
      // and rewoundActions.
      //
      // It is also unclear why we are posting anything directly to reporter. That probably
      // shouldn't happen.
      try (SilentCloseable c =
          profiler.profileAction(
              ProfilerTask.ACTION,
              action.getMnemonic(),
              action.describe(),
              action.getPrimaryOutput().getExecPathString(),
              getOwnerLabelAsString(action),
              getOwnerConfigurationAsString(action))) {
        String message = action.getProgressMessage();
        if (message != null) {
          reporter.startTask(null, prependExecPhaseStats(message));
        }

        boolean lostInputs = false;

        try {
          ActionStartedEvent event = new ActionStartedEvent(action, actionStartTimeNanos);
          if (statusReporter != null) {
            statusReporter.updateStatus(event);
          }
          env.getListener().post(event);
          if (actionFileSystemType().shouldDoEagerActionPrep()) {
            try (SilentCloseable d = profiler.profile(ProfilerTask.INFO, "action.prepare")) {
              // This call generally deletes any files at locations that are declared outputs of the
              // action, although some actions perform additional work, while others intentionally
              // keep previous outputs in place.
              action.prepare(
                  actionExecutionContext.getExecRoot(),
                  actionExecutionContext.getPathResolver(),
                  outputService.bulkDeleter(),
                  useArchivedTreeArtifacts(action));
            } catch (IOException e) {
              logger.atWarning().withCause(e).log(
                  "failed to delete output files before executing action: '%s'", action);
              throw toActionExecutionException(
                  "failed to delete output files before executing action",
                  e,
                  action,
                  null,
                  Code.ACTION_OUTPUTS_DELETION_FAILURE);
            }
          }

          if (actionFileSystemType().inMemoryFileSystem()) {
            // There's nothing to delete when the action file system is used, but we must ensure
            // that the output directories for stdout and stderr exist.
            setupActionFsFileOutErr(actionExecutionContext.getFileOutErr(), action);
            createActionFsOutputDirectories(action, actionExecutionContext.getPathResolver());
          } else {
            createOutputDirectories(action);
          }

          return executeAction(env.getListener(), action);
        } catch (LostInputsActionExecutionException e) {
          lostInputs = true;
          throw e;
        } catch (ActionExecutionException e) {
          return ActionStepOrResult.of(e);
        } finally {
          notifyActionCompletion(env.getListener(), !lostInputs);
        }
      }
    }

    private String getOwnerLabelAsString(Action action) {
      ActionOwner owner = action.getOwner();
      if (owner == null) {
        return "";
      }
      Label ownerLabel = owner.getLabel();
      if (ownerLabel == null) {
        return "";
      }
      return ownerLabel.getCanonicalForm();
    }

    private String getOwnerConfigurationAsString(Action action) {
      ActionOwner owner = action.getOwner();
      if (owner == null) {
        return "";
      }
      return owner.getConfigurationChecksum();
    }

    private void notifyActionCompletion(
        ExtendedEventHandler eventHandler, boolean postActionCompletionEvent) {
      if (statusReporter != null) {
        statusReporter.remove(action);
      }
      if (postActionCompletionEvent) {
        eventHandler.post(
            new ActionCompletionEvent(
                actionStartTimeNanos,
                BlazeClock.nanoTime(),
                action,
                inputMetadataProvider,
                actionLookupData));
      }
      String message = action.getProgressMessage();
      if (message != null) {
        if (completionReceiver != null) {
          completionReceiver.actionCompleted(actionLookupData);
        }
        reporter.finishTask(null, prependExecPhaseStats(message));
      }
    }

    private void maybeSignalLostInputs(ActionExecutionException e, Path primaryOutputPath)
        throws LostInputsActionExecutionException {
      LostInputsActionExecutionException lostInputsException = null;
      // Action failures may be caused by lost inputs. Lost input failures have higher priority
      // because rewinding may be able to restore what was lost and allow the action to complete
      // without error.
      if (e instanceof LostInputsActionExecutionException lostInputsActionExecutionException) {
        lostInputsException = lostInputsActionExecutionException;
      } else {
        try {
          checkActionFileSystemForLostInputs(
              actionExecutionContext.getActionFileSystem(), action, outputService);
        } catch (LostInputsActionExecutionException e2) {
          lostInputsException = e2;
        }
      }

      if (lostInputsException == null) {
        return;
      }

      // If inputs are lost, then avoid publishing ActionExecutedEvent or reporting the error.
      // Action rewinding will rerun this failed action after trying to regenerate the lost
      // inputs.
      lostInputsException.setActionStartedEventAlreadyEmitted();
      enrichLostInputsException(
          primaryOutputPath,
          actionLookupData,
          actionExecutionContext.getFileOutErr(),
          lostInputsException);
      throw lostInputsException;
    }

    /** Executes the given action. */
    private ActionStepOrResult executeAction(ExtendedEventHandler eventHandler, Action action)
        throws LostInputsActionExecutionException, InterruptedException {
      ActionResult result;
      try (SilentCloseable c = profiler.profile(ProfilerTask.INFO, "Action.execute")) {
        checkForUnsoundDirectoryInputs(action, actionExecutionContext.getInputMetadataProvider());

        result = action.execute(actionExecutionContext);

        // An action's result (or intermediate state) may have been affected by lost inputs. If an
        // action filesystem is used, it may know whether inputs were lost. We should fail fast if
        // any were; rewinding may be able to fix it.
        checkActionFileSystemForLostInputs(
            actionExecutionContext.getActionFileSystem(), action, outputService);
      } catch (ActionExecutionException e) {
        Path primaryOutputPath = actionExecutionContext.getInputPath(action.getPrimaryOutput());
        maybeSignalLostInputs(e, primaryOutputPath);
        return ActionStepOrResult.of(
            processAndGetExceptionToThrow(
                eventHandler,
                primaryOutputPath,
                action,
                e,
                actionExecutionContext.getFileOutErr(),
                ErrorTiming.AFTER_EXECUTION));
      } catch (InterruptedException e) {
        return ActionStepOrResult.of(e);
      }

      try {
        ActionExecutionValue actionExecutionValue;
        try (SilentCloseable c =
            profiler.profile(ProfilerTask.ACTION_COMPLETE, "actuallyCompleteAction")) {
          actionExecutionValue = actuallyCompleteAction(eventHandler, result);
        }
        return new ActionPostprocessingStep(actionExecutionValue);
      } catch (ActionExecutionException e) {
        return ActionStepOrResult.of(e);
      }
    }

    @SuppressWarnings("LogAndThrow") // Thrown exception shown in user output, not info logs.
    private ActionExecutionValue actuallyCompleteAction(
        ExtendedEventHandler eventHandler, ActionResult actionResult)
        throws ActionExecutionException, InterruptedException {
      boolean outputAlreadyDumped = false;
      if (actionResult != ActionResult.EMPTY) {
        eventHandler.post(new ActionResultReceivedEvent(action, actionResult));
      }

      // Action terminated fine, now report the output.
      // The .showOutput() method is not necessarily a quick check: in its
      // current implementation it uses regular expression matching.
      FileOutErr outErrBuffer = actionExecutionContext.getFileOutErr();
      if (outErrBuffer.hasRecordedOutput()) {
        if (action.showsOutputUnconditionally()
            || reporter.showOutput(Label.print(action.getOwner().getLabel()))) {
          dumpRecordedOutErr(reporter, action, outErrBuffer);
          outputAlreadyDumped = true;
        }
      }

      OutputMetadataStore outputMetadataStore = actionExecutionContext.getOutputMetadataStore();
      FileOutErr fileOutErr = actionExecutionContext.getFileOutErr();
      Artifact primaryOutput = action.getPrimaryOutput();
      Path primaryOutputPath = actionExecutionContext.getInputPath(primaryOutput);
      try {
        checkState(
            action.inputsKnown(),
            "Action %s successfully executed, but inputs still not known",
            action);

        if (!checkOutputs(
            action,
            outputMetadataStore,
            actionExecutionContext.getFilesetOutput(),
            /* isActionCacheHitForMetrics= */ false)) {
          throw toActionExecutionException(
              "not all outputs were created or valid",
              null,
              action,
              outputAlreadyDumped ? null : fileOutErr,
              Code.ACTION_OUTPUTS_NOT_CREATED);
        }

        if (finalizeActions) {
          try (SilentCloseable c =
              profiler.profile(ProfilerTask.INFO, "outputService.finalizeAction")) {
            outputService.finalizeAction(action, outputMetadataStore);
          } catch (EnvironmentalExecException | IOException e) {
            logger.atWarning().withCause(e).log("unable to finalize action: '%s'", action);
            throw toActionExecutionException(
                "unable to finalize action",
                e,
                action,
                fileOutErr,
                Code.ACTION_FINALIZATION_FAILURE);
          }
        }
      } catch (ActionExecutionException actionException) {
        // Success in execution but failure in completion.
        reportActionExecution(
            eventHandler,
            primaryOutputPath,
            /* primaryOutputMetadata= */ null,
            action,
            actionResult,
            actionException,
            fileOutErr,
            ErrorTiming.AFTER_EXECUTION);
        throw actionException;
      } catch (IllegalStateException exception) {
        // More serious internal error, but failure still reported.
        reportActionExecution(
            eventHandler,
            primaryOutputPath,
            /* primaryOutputMetadata= */ null,
            action,
            actionResult,
            new ActionExecutionException(
                exception,
                action,
                true,
                CrashFailureDetails.detailedExitCodeForThrowable(exception)),
            fileOutErr,
            ErrorTiming.AFTER_EXECUTION);
        throw exception;
      }

      FileArtifactValue primaryOutputMetadata;
      try {
        primaryOutputMetadata = outputMetadataStore.getOutputMetadata(primaryOutput);
      } catch (IOException e) {
        throw new IllegalStateException("Metadata already obtained for " + primaryOutput, e);
      }

      reportActionExecution(
          eventHandler,
          primaryOutputPath,
          primaryOutputMetadata,
          action,
          actionResult,
          null,
          fileOutErr,
          ErrorTiming.NO_ERROR);

      FilesetOutputTree filesetOutput = actionExecutionContext.getFilesetOutput();
      checkState(
          filesetOutput.isEmpty() || action instanceof SkyframeAwareAction,
          "Unexpected to find outputSymlinks set in an action which is not a SkyframeAwareAction."
              + "\nAction: %s"
              + "\nSymlinks: %s",
          action,
          filesetOutput);
      return ActionExecutionValue.createFromOutputMetadataStore(
          this.outputMetadataStore, filesetOutput, action);
    }

    /**
     * A closure to post-process the executed action, doing work like updating cached state with any
     * newly discovered inputs, and writing the result to the action cache.
     */
    private class ActionPostprocessingStep extends ActionStep {
      private final ActionExecutionValue value;

      ActionPostprocessingStep(ActionExecutionValue value) {
        this.value = value;
      }

      @Override
      public ActionStepOrResult run(Environment env) {
        try (SilentCloseable c = profiler.profile(ProfilerTask.INFO, "postprocessing.run")) {
          postprocessing.run(
              env,
              action,
              inputMetadataProvider,
              outputMetadataStore,
              actionExecutionContext.getClientEnv());
          if (env.valuesMissing()) {
            return this;
          }
        } catch (InterruptedException e) {
          return ActionStepOrResult.of(e);
        } catch (ActionExecutionException e) {
          return ActionStepOrResult.of(e);
        }

        // Once the action has been written to the action cache, we can free its discovered inputs.
        if (freeDiscoveredInputsAfterExecution && action.discoversInputs()) {
          action.resetDiscoveredInputs();
        }
        return ActionStepOrResult.of(value);
      }
    }
  }

  /**
   * Create output directories for an ActionFS. The action-local filesystem starts empty, so we
   * expect the output directory creation to always succeed. There can be no interference from state
   * left behind by prior builds or other actions intra-build.
   */
  private void createActionFsOutputDirectories(
      Action action, ArtifactPathResolver artifactPathResolver) throws ActionExecutionException {
    try {
      outputDirectoryHelper.createActionFsOutputDirectories(
          action.getOutputs(), artifactPathResolver);
    } catch (CreateOutputDirectoryException e) {
      throw toActionExecutionException(
          String.format(
              "failed to create output directory '%s': %s", e.getDirectoryPath(), e.getMessage()),
          e,
          action,
          null,
          Code.ACTION_FS_OUTPUT_DIRECTORY_CREATION_FAILURE);
    }
  }

  private void createOutputDirectories(Action action) throws ActionExecutionException {
    try {
      outputDirectoryHelper.createOutputDirectories(action.getOutputs());
    } catch (CreateOutputDirectoryException e) {
      throw toActionExecutionException(
          String.format(
              "failed to create output directory '%s': %s", e.getDirectoryPath(), e.getMessage()),
          e,
          action,
          /* actionOutput= */ null,
          Code.ACTION_OUTPUT_DIRECTORY_CREATION_FAILURE);
    }
  }

  /**
   * Returns a progress message like:
   *
   * <p>[2608/6445] Compiling foo/bar.cc [exec]
   */
  private String prependExecPhaseStats(String message) {
    if (progressSupplier == null) {
      return "";
    }
    return progressSupplier.getProgressString() + " " + message;
  }

  private static void setupActionFsFileOutErr(FileOutErr fileOutErr, Action action)
      throws ActionExecutionException {
    try {
      fileOutErr.getOutputPath().getParentDirectory().createDirectoryAndParents();
      fileOutErr.getErrorPath().getParentDirectory().createDirectoryAndParents();
    } catch (IOException e) {
      String message =
          String.format(
              "failed to create output directory for output streams '%s': %s",
              fileOutErr.getErrorPath(), e.getMessage());
      DetailedExitCode code =
          createDetailedExitCode(message, Code.ACTION_FS_OUT_ERR_DIRECTORY_CREATION_FAILURE);
      throw new ActionExecutionException(message, e, action, false, code);
    }
  }

  /** Must not be called with a {@link LostInputsActionExecutionException}. */
  ActionExecutionException processAndGetExceptionToThrow(
      ExtendedEventHandler eventHandler,
      Path primaryOutputPath,
      Action action,
      ActionExecutionException e,
      FileOutErr outErrBuffer,
      ErrorTiming errorTiming) {
    checkArgument(
        !(e instanceof LostInputsActionExecutionException),
        "unexpected LostInputs exception: %s",
        e);

    reportActionExecution(
        eventHandler,
        primaryOutputPath,
        /* primaryOutputMetadata= */ null,
        action,
        null,
        e,
        outErrBuffer,
        errorTiming);
    // Return the exception to rethrow. This can have two effects:
    // If we're still building, the exception will get retrieved by the completor and rethrown.
    // If we're aborting, the exception will never be retrieved from the completor, since the
    // completor is waiting for all outstanding jobs to finish. After they have finished, it will
    // only rethrow the exception that initially caused it to abort and not check the exit status of
    // any actions that had finished in the meantime.

    // If we already printed the error for the exception we mark it as already reported
    // so that we do not print it again in upper levels.
    // Note that we need to report it here since we want immediate feedback of the errors
    // and in some cases the upper-level printing mechanism only prints one of the errors.
    return printError(e.getMessage(), e.getAction(), outErrBuffer)
        ? new AlreadyReportedActionExecutionException(e)
        : e;
  }

  /**
   * Enriches the exception so it can be confirmed as the primary action in a shared action set and
   * so that, if rewinding fails, an ActionExecutedEvent can be published, and the error reported.
   */
  private static void enrichLostInputsException(
      Path primaryOutputPath,
      ActionLookupData actionLookupData,
      FileOutErr outErrBuffer,
      LostInputsActionExecutionException lostInputsException) {
    lostInputsException.setPrimaryAction(actionLookupData);
    lostInputsException.setPrimaryOutputPath(primaryOutputPath);
    lostInputsException.setFileOutErr(outErrBuffer);
  }

  private static void reportMissingOutputFile(
      Action action, Artifact output, Reporter reporter, boolean isSymlink, IOException exception) {
    boolean genrule = action.getMnemonic().equals("Genrule");
    String prefix = (genrule ? "declared output '" : "output '") + output.prettyPrint() + "' ";
    logger.atWarning().log(
        "Error creating %s%s%s: %s",
        isSymlink ? "symlink " : "", prefix, genrule ? " by genrule" : "", exception.getMessage());
    if (isSymlink) {
      String msg = prefix + "is a dangling symbolic link";
      reporter.handle(Event.error(action.getOwner().getLocation(), msg));
    } else {
      String suffix =
          genrule
              ? " by genrule. This is probably because the genrule actually didn't create this"
                  + " output, or because the output was a directory and the genrule was run"
                  + " remotely (note that only the contents of declared file outputs are copied"
                  + " from genrules run remotely)"
              : "";
      reporter.handle(
          Event.error(action.getOwner().getLocation(), prefix + "was not created" + suffix));
    }
  }

  private static void reportOutputTreeArtifactErrors(
      Action action, Artifact output, Reporter reporter, IOException e) {
    String errorMessage;
    if (e instanceof FileNotFoundException) {
      errorMessage = String.format("output tree artifact %s was not created", output.prettyPrint());
    } else {
      errorMessage =
          String.format(
              "error while validating output tree artifact %s: %s",
              output.prettyPrint(), e.getMessage());
    }

    reporter.handle(Event.error(action.getOwner().getLocation(), errorMessage));
  }

  /**
   * Validates that all action input contents were not lost if they were read, and if an action file
   * system was used. Throws a {@link LostInputsActionExecutionException} describing the lost inputs
   * if any were.
   */
  private static void checkActionFileSystemForLostInputs(
      @Nullable FileSystem actionFileSystem, Action action, OutputService outputService)
      throws LostInputsActionExecutionException {
    if (actionFileSystem != null) {
      outputService.checkActionFileSystemForLostInputs(actionFileSystem, action);
    }
  }

  private static LostInputsCheck lostInputsCheck(
      @Nullable FileSystem actionFileSystem, Action action, OutputService outputService) {
    return actionFileSystem == null
        ? LostInputsCheck.NONE
        : () -> outputService.checkActionFileSystemForLostInputs(actionFileSystem, action);
  }

  /**
   * Validates that all action outputs were created or intentionally omitted. This can result in
   * chmod calls on the output files; see {@link ActionOutputMetadataStore}.
   *
   * @return false if some outputs are missing or invalid, true - otherwise.
   */
  private boolean checkOutputs(
      Action action,
      OutputMetadataStore outputMetadataStore,
      @Nullable FilesetOutputTree filesetOutputForMetrics,
      boolean isActionCacheHitForMetrics)
      throws InterruptedException {
    boolean success = true;
    try (SilentCloseable c = profiler.profile(ProfilerTask.INFO, "checkOutputs")) {
      for (Artifact output : action.getOutputs()) {
        // getOutputMetadata() has the side effect of adding the artifact to the cache if it's not
        // there already (e.g., due to a previous call to OutputMetadataStore.injectFile()),
        // therefore we only call it if we know the artifact is not omitted.
        if (!outputMetadataStore.artifactOmitted(output)) {
          try {
            FileArtifactValue metadata = outputMetadataStore.getOutputMetadata(output);

            if (!checkForUnsoundDirectoryOutput(action, output, metadata)) {
              return false;
            }

            addOutputToMetrics(
                output,
                metadata,
                outputMetadataStore,
                filesetOutputForMetrics,
                isActionCacheHitForMetrics,
                action);
          } catch (IOException e) {
            success = false;
            if (output.isTreeArtifact()) {
              reportOutputTreeArtifactErrors(action, output, reporter, e);
            } else if (output.isSymlink() && e instanceof NotASymlinkException) {
              reporter.handle(
                  Event.error(
                      action.getOwner().getLocation(),
                      String.format(
                          "declared output '%s' is not a symlink", output.prettyPrint())));
            } else {
              // Are all other exceptions caught due to missing files?
              reportMissingOutputFile(
                  action, output, reporter, output.getPath().isSymbolicLink(), e);
            }
          }
        }
      }
    }
    return success;
  }

  private void addOutputToMetrics(
      Artifact output,
      FileArtifactValue metadata,
      OutputMetadataStore outputMetadataStore,
      @Nullable FilesetOutputTree filesetOutput,
      boolean isActionCacheHit,
      Action actionForDebugging)
      throws IOException, InterruptedException {
    if (metadata == null) {
      BugReport.sendBugReport(
          new IllegalStateException(
              String.format(
                  "Metadata for %s not present in %s (for %s)",
                  output, outputMetadataStore, actionForDebugging)));
      return;
    }
    if (output.isFileset() && filesetOutput != null) {
      outputArtifactsSeen.accumulate(filesetOutput);
    } else if (!output.isTreeArtifact()) {
      outputArtifactsSeen.accumulate(metadata);
      if (isActionCacheHit) {
        outputArtifactsFromActionCache.accumulate(metadata);
      }
    } else {
      TreeArtifactValue treeArtifactValue;
      try {
        treeArtifactValue = outputMetadataStore.getTreeArtifactValue((SpecialArtifact) output);
      } catch (IOException e) {
        BugReport.sendBugReport(
            new IllegalStateException(
                String.format(
                    "Unexpected IO exception after metadata %s was retrieved for %s (action %s)",
                    metadata, output, actionForDebugging)));
        throw e;
      }
      outputArtifactsSeen.accumulate(treeArtifactValue);
      if (isActionCacheHit) {
        outputArtifactsFromActionCache.accumulate(treeArtifactValue);
      }
    }
  }

  private void checkForUnsoundDirectoryInputs(Action action, InputMetadataProvider metadataProvider)
      throws ActionExecutionException {
    if (TrackSourceDirectoriesFlag.trackSourceDirectories()) {
      return;
    }

    if (action.getMnemonic().equals("FilesetTraversal")) {
      // Omit warning for filesets (b/1437948).
      return;
    }

    // Report "directory dependency checking" warning only for non-generated directories (generated
    // ones will have been reported earlier, in the checkForUnsoundDirectoryOutput call for the
    // respective producing action).
    for (Artifact input : action.getMandatoryInputs().toList()) {
      // Assume that if the file did not exist, we would not have gotten here.
      try {
        if (input.isSourceArtifact()
            && metadataProvider.getInputMetadata(input).getType().isDirectory()) {
          // TODO(ulfjack): What about dependency checking of special files?
          reporter.handle(
              getEventForUnsoundDirectory(
                  EventKind.WARNING,
                  "input %s is a directory; dependency checking of directories is unsound",
                  input,
                  action.getOwner()));
        }
      } catch (IOException e) {
        throw ActionExecutionException.fromExecException(
            new EnvironmentalExecException(
                e, FailureDetails.Execution.Code.INPUT_DIRECTORY_CHECK_IO_EXCEPTION),
            action);
      }
    }
  }

  private boolean checkForUnsoundDirectoryOutput(
      Action action, Artifact output, FileArtifactValue metadata) {
    if (output.isDirectory() || output.isSymlink() || !metadata.getType().isDirectory()) {
      return true;
    }
    reporter.handle(
        getEventForUnsoundDirectory(
            EventKind.ERROR,
            "output %s is a directory but was not declared as such",
            output,
            action.getOwner()));
    return false;
  }

  private static Event getEventForUnsoundDirectory(
      EventKind kind, String format, Artifact artifact, ActionOwner owner) {
    Label label = owner.getLabel();
    String artifactString =
        label != null
            ? String.format("'%s' of %s", artifact.prettyPrint(), label)
            : artifact.prettyPrint();
    Event event = Event.of(kind, owner.getLocation(), String.format(format, artifactString));
    return label != null ? event.withTag(label.toString()) : event;
  }

  /**
   * Convenience function for creating an ActionExecutionException reporting that the action failed
   * due to the exception cause, if there is an additional explanatory message that clarifies the
   * message of the exception. Combines the user-provided message and the exception's message and
   * reports the combination as error.
   *
   * @param message A small text that explains why the action failed
   * @param cause The exception that caused the action to fail
   * @param action The action that failed
   * @param actionOutput The output of the failed Action. May be null, if there is no output to
   *     display
   * @param detailedCode The fine-grained failure code describing the failure
   */
  private ActionExecutionException toActionExecutionException(
      String message,
      Throwable cause,
      Action action,
      FileOutErr actionOutput,
      FailureDetails.Execution.Code detailedCode) {
    DetailedExitCode code = createDetailedExitCode(message, detailedCode);
    ActionExecutionException ex;
    if (cause == null) {
      ex = new ActionExecutionException(message, action, false, code);
    } else {
      ex = new ActionExecutionException(message, cause, action, false, code);
    }
    String reportMessage = ex.getMessage();
    if (cause != null && cause.getMessage() != null) {
      reportMessage += ": " + cause.getMessage();
    }
    printError(reportMessage, action, actionOutput);
    return ex;
  }

  private static DetailedExitCode createDetailedExitCode(String message, Code detailedCode) {
    return DetailedExitCode.of(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setExecution(Execution.newBuilder().setCode(detailedCode))
            .build());
  }

  /**
   * Prints the given error {@code message} ascribed to {@code action}. May be called multiple times
   * for the same action if there are multiple errors: will print all of them.
   */
  void printError(String message, ActionAnalysisMetadata action) {
    printError(message, action, null);
  }

  /**
   * For the action 'action' that failed due to 'message' with the output 'actionOutput', notify the
   * user about the error. To notify the user, the method displays the output of the action and
   * reports an error via the reporter.
   *
   * @param message The reason why the action failed
   * @param action The action that failed, must not be null.
   * @param actionOutput The output of the failed Action. May be null, if there is no output to
   *     display
   * @return whether error was printed
   */
  private boolean printError(
      String message, ActionAnalysisMetadata action, @Nullable FileOutErr actionOutput) {
    message = action.describe() + " failed: " + message;
    return dumpRecordedOutErr(
        reporter, Event.error(action.getOwner().getLocation(), message), actionOutput);
  }

  /**
   * Dumps the output from the action.
   *
   * @param action The action whose output is being dumped
   * @param outErrBuffer The OutErr that recorded the actions output
   */
  private void dumpRecordedOutErr(
      EventHandler eventHandler, Action action, FileOutErr outErrBuffer) {
    Event event = Event.info("From " + action.describe() + ":");
    dumpRecordedOutErr(eventHandler, event, outErrBuffer);
  }

  /**
   * Dumps output from the action along with {@code prefixEvent} if the build is not aborting.
   *
   * @param prefixEvent An event to post before dumping the output
   * @param outErrBuffer The OutErr that recorded the actions output
   * @return whether output was displayed (false if aborting)
   */
  private boolean dumpRecordedOutErr(
      EventHandler eventHandler, Event prefixEvent, FileOutErr outErrBuffer) {
    // For some actions (e.g., many local actions) the pollInterruptedStatus()
    // won't notice that we had an interrupted job. It will continue.
    // For that reason we must take care to NOT report errors if we're
    // in the 'aborting' mode: Any cancelled action would show up here.
    if (isBuilderAborting()) {
      return false;
    }
    if (outErrBuffer != null && outErrBuffer.hasRecordedOutput()) {
      // Bind the output to the prefix event.
      eventHandler.handle(prefixEvent.withProcessOutput(new ActionOutputEventData(outErrBuffer)));
    } else {
      eventHandler.handle(prefixEvent);
    }
    return true;
  }

  private static void reportActionExecution(
      ExtendedEventHandler eventHandler,
      Path primaryOutputPath,
      @Nullable FileArtifactValue primaryOutputMetadata,
      Action action,
      @Nullable ActionResult actionResult,
      ActionExecutionException exception,
      FileOutErr outErr,
      ErrorTiming errorTiming) {
    Path stdout = null;
    Path stderr = null;

    if (outErr.hasRecordedStdout()) {
      stdout = outErr.getOutputPath();
    }
    if (outErr.hasRecordedStderr()) {
      stderr = outErr.getErrorPath();
    }
    // Collect MetadataLogs and spawn start times/end times from the Action's SpawnResults.
    ImmutableList<SpawnResult> spawnResults =
        findSpawnResultsInActionResultAndException(actionResult, exception);
    Instant firstStartTime = Instant.MAX;
    Instant lastEndTime = Instant.MIN;
    for (SpawnResult spawnResult : spawnResults) {
      // Not all SpawnResults have a start time, and some use Instant.MIN/MAX instead of null.
      @Nullable Instant startTime = spawnResult.getStartTime();
      if (startTime != null && !startTime.equals(Instant.MIN) && !startTime.equals(Instant.MAX)) {
        Instant endTime = startTime.plusMillis(spawnResult.getWallTimeInMs());
        firstStartTime = min(firstStartTime, startTime);
        lastEndTime = max(lastEndTime, endTime);
      }
    }
    eventHandler.post(
        new ActionExecutedEvent(
            action.getPrimaryOutput().getExecPath(),
            action,
            exception,
            primaryOutputPath,
            action.getPrimaryOutput(),
            primaryOutputMetadata,
            stdout,
            stderr,
            errorTiming,
            firstStartTime.equals(Instant.MAX) ? null : firstStartTime,
            lastEndTime.equals(Instant.MIN) ? null : firstStartTime));
  }

  /**
   * Extracts the {@link SpawnResult SpawnResults} from either a completed {@link ActionResult} or a
   * {@link SpawnActionExecutionException}.
   *
   * <p>Returns an empty list for any other kind of {@link ActionExecutionException}.
   */
  private static ImmutableList<SpawnResult> findSpawnResultsInActionResultAndException(
      @Nullable ActionResult actionResult, @Nullable ActionExecutionException exception) {
    if (actionResult != null) {
      return actionResult.spawnResults();
    }
    if (exception instanceof SpawnActionExecutionException spawnActionExecutionException) {
      return ImmutableList.of(spawnActionExecutionException.getSpawnResult());
    }
    return ImmutableList.of();
  }

  /** An object supplying data for action execution progress reporting. */
  public interface ProgressSupplier {
    /** Returns the progress string to prefix action execution messages with. */
    String getProgressString();
  }

  /** An object that can be notified about action completion. */
  public interface ActionCompletedReceiver {
    /** Receives a completed action. */
    void actionCompleted(ActionLookupData actionLookupData);
  }

  void setActionExecutionProgressReportingObjects(
      @Nullable ProgressSupplier progressSupplier,
      @Nullable ActionCompletedReceiver completionReceiver) {
    this.progressSupplier = progressSupplier;
    this.completionReceiver = completionReceiver;
  }

  /** Adapts a {@link FileOutErr} to an {@link Event.ProcessOutput}. */
  private static class ActionOutputEventData implements Event.ProcessOutput {
    private final FileOutErr fileOutErr;

    private ActionOutputEventData(FileOutErr fileOutErr) {
      this.fileOutErr = fileOutErr;
    }

    @Override
    public String getStdOutPath() {
      return fileOutErr.getOutputPathFragment().getPathString();
    }

    @Override
    public long getStdOutSize() throws IOException {
      return fileOutErr.outSize();
    }

    @Override
    public byte[] getStdOut() {
      return fileOutErr.outAsBytes();
    }

    @Override
    public String getStdErrPath() {
      return fileOutErr.getErrorPathFragment().getPathString();
    }

    @Override
    public long getStdErrSize() throws IOException {
      return fileOutErr.errSize();
    }

    @Override
    public byte[] getStdErr() {
      return fileOutErr.errAsBytes();
    }
  }
}
