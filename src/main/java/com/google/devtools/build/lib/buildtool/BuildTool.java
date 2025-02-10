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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.devtools.build.lib.buildtool.AnalysisPhaseRunner.evaluateProjectFile;
import static com.google.devtools.common.options.OptionsParser.STARLARK_SKIPPED_PREFIXES;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableClassToInstanceMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.actions.Artifact.ArtifactSerializationContext;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.TestExecException;
import com.google.devtools.build.lib.analysis.AnalysisAndExecutionResult;
import com.google.devtools.build.lib.analysis.AnalysisResult;
import com.google.devtools.build.lib.analysis.BuildView;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.Project;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionException;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile.LocalFileType;
import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader.UploadContext;
import com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil;
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions;
import com.google.devtools.build.lib.buildtool.AnalysisPhaseRunner.ProjectEvaluationResult;
import com.google.devtools.build.lib.buildtool.SkyframeMemoryDumper.DisplayMode;
import com.google.devtools.build.lib.buildtool.buildevent.BuildCompleteEvent;
import com.google.devtools.build.lib.buildtool.buildevent.BuildInterruptedEvent;
import com.google.devtools.build.lib.buildtool.buildevent.BuildStartingEvent;
import com.google.devtools.build.lib.buildtool.buildevent.NoExecutionEvent;
import com.google.devtools.build.lib.buildtool.buildevent.StartingAqueryDumpAfterBuildEvent;
import com.google.devtools.build.lib.buildtool.buildevent.UpdateOptionsEvent;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.collect.PathFragmentPrefixTrie;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.OutputFilter;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.LoadingFailedException;
import com.google.devtools.build.lib.profiler.ProfilePhase;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.query2.aquery.ActionGraphProtoOutputFormatterCallback;
import com.google.devtools.build.lib.runtime.BlazeOptionHandler.SkyframeExecutorTargetLoader;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.CommandLineEvent.CanonicalCommandLineEvent;
import com.google.devtools.build.lib.runtime.ConfigFlagDefinitions;
import com.google.devtools.build.lib.runtime.StarlarkOptionsParser;
import com.google.devtools.build.lib.runtime.StarlarkOptionsParser.BuildSettingLoader;
import com.google.devtools.build.lib.server.FailureDetails.ActionQuery;
import com.google.devtools.build.lib.server.FailureDetails.BuildConfiguration.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.BuildResultListener;
import com.google.devtools.build.lib.skyframe.PrerequisitePackageFunction;
import com.google.devtools.build.lib.skyframe.ProjectValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue.RepositoryMappingResolutionException;
import com.google.devtools.build.lib.skyframe.SequencedSkyframeExecutor;
import com.google.devtools.build.lib.skyframe.SkyfocusOptions;
import com.google.devtools.build.lib.skyframe.SkyframeBuildView.BuildDriverKeyTestContext;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.TargetPatternPhaseValue;
import com.google.devtools.build.lib.skyframe.actiongraph.v2.ActionGraphDump;
import com.google.devtools.build.lib.skyframe.actiongraph.v2.AqueryOutputHandler;
import com.google.devtools.build.lib.skyframe.actiongraph.v2.AqueryOutputHandler.OutputType;
import com.google.devtools.build.lib.skyframe.actiongraph.v2.InvalidAqueryOutputFormatException;
import com.google.devtools.build.lib.skyframe.config.FlagSetValue;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecs;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.FrontierNodeVersion;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.RetrievalResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FrontierSerializer;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FrontierViolationChecker;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingDependenciesProvider;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingEventListener;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions.RemoteAnalysisCacheMode;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.CrashFailureDetails;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.InterruptedFailureDetails;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.IntVersion;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.common.options.OptionPriority.PriorityCategory;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;
import com.google.devtools.common.options.RegexPatternOption;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import javax.annotation.Nullable;

/**
 * Provides the bulk of the implementation of the 'blaze build' command.
 *
 * <p>The various concrete build command classes handle the command options and request setup, then
 * delegate the handling of the request (the building of targets) to this class.
 *
 * <p>The main entry point is {@link #buildTargets}.
 *
 * <p>Most of analysis is handled in {@link com.google.devtools.build.lib.analysis.BuildView}, and
 * execution in {@link ExecutionTool}.
 */
public class BuildTool {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static final String SKYFRAME_MEMORY_DUMP_FILE = "skyframe_memory.json";

  private static final AnalysisPostProcessor NOOP_POST_PROCESSOR =
      (req, env, runtime, analysisResult) -> {};

  /** Hook for inserting extra post-analysis-phase processing. Used for implementing {a,c}query. */
  public interface AnalysisPostProcessor {
    void process(
        BuildRequest request,
        CommandEnvironment env,
        BlazeRuntime runtime,
        AnalysisResult analysisResult)
        throws InterruptedException, ViewCreationFailedException, ExitException;
  }

  private final CommandEnvironment env;
  private final BlazeRuntime runtime;
  private final AnalysisPostProcessor analysisPostProcessor;

  /**
   * Constructs a BuildTool.
   *
   * @param env a reference to the command environment of the currently executing command
   */
  public BuildTool(CommandEnvironment env) {
    this(env, NOOP_POST_PROCESSOR);
  }

  public BuildTool(CommandEnvironment env, AnalysisPostProcessor postProcessor) {
    this.env = env;
    this.runtime = env.getRuntime();
    this.analysisPostProcessor = postProcessor;
  }

  /**
   * The crux of the build system: builds the targets specified in the request.
   *
   * <p>Performs loading, analysis and execution for the specified set of targets, honoring the
   * configuration options in the BuildRequest. Returns normally iff successful, throws an exception
   * otherwise.
   *
   * <p>Callers must ensure that {@link #stopRequest} is called after this method, even if it
   * throws.
   *
   * <p>The caller is responsible for setting up and syncing the package cache.
   *
   * <p>During this function's execution, the actualTargets and successfulTargets fields of the
   * request object are set.
   *
   * @param request the build request that this build tool is servicing, which specifies various
   *     options; during this method's execution, the actualTargets and successfulTargets fields of
   *     the request object are populated
   * @param result the build result that is the mutable result of this build
   * @param validator target validator
   */
  public void buildTargets(
      BuildRequest request,
      BuildResult result,
      TargetValidator validator,
      OptionsParser optionsParser)
      throws BuildFailedException,
          InterruptedException,
          ViewCreationFailedException,
          TargetParsingException,
          LoadingFailedException,
          AbruptExitException,
          InvalidConfigurationException,
          TestExecException,
          ExitException,
          PostExecutionDumpException,
          RepositoryMappingResolutionException,
          OptionsParsingException {
    try (SilentCloseable c = Profiler.instance().profile("validateOptions")) {
      validateOptions(request);
    }
    BuildOptions buildOptions;
    try (SilentCloseable c = Profiler.instance().profile("createBuildOptions")) {
      buildOptions = runtime.createBuildOptions(request);
    }

    boolean catastrophe = false;
    try {
      try (SilentCloseable c = Profiler.instance().profile("BuildStartingEvent")) {
        env.getEventBus().post(BuildStartingEvent.create(env, request));
      }
      logger.atInfo().log("Build identifier: %s", request.getId());

      // Exit if there are any pending exceptions from modules.
      env.throwPendingException();

      initializeOutputFilter(request);

      TargetPatternPhaseValue targetPatternPhaseValue;
      Profiler.instance().markPhase(ProfilePhase.TARGET_PATTERN_EVAL);
      try (SilentCloseable c = Profiler.instance().profile("evaluateTargetPatterns")) {
        targetPatternPhaseValue = evaluateTargetPatterns(env, request, validator);
      }
      env.setWorkspaceName(targetPatternPhaseValue.getWorkspaceName());

      ProjectEvaluationResult projectEvaluationResult =
          evaluateProjectFile(
              request, buildOptions, request.getUserOptions(), targetPatternPhaseValue, env);

      if (!projectEvaluationResult.buildOptions().isEmpty()) {
        // First parse the native options from the project file.
        optionsParser.parse(
            PriorityCategory.COMMAND_LINE,
            projectEvaluationResult.projectFile().get().toString(),
            projectEvaluationResult.buildOptions().stream()
                .filter(o -> STARLARK_SKIPPED_PREFIXES.stream().noneMatch(o::startsWith))
                .collect(toImmutableList()));
        // Then parse the starlark options from the project file.
        BuildSettingLoader buildSettingLoader = new SkyframeExecutorTargetLoader(env);
        StarlarkOptionsParser starlarkOptionsParser =
            StarlarkOptionsParser.builder()
                .buildSettingLoader(buildSettingLoader)
                .nativeOptionsParser(optionsParser)
                .build();
        Preconditions.checkState(
            starlarkOptionsParser.parseGivenArgs(
                projectEvaluationResult.buildOptions().stream()
                    .filter(o -> STARLARK_SKIPPED_PREFIXES.stream().anyMatch(o::startsWith))
                    .collect(toImmutableList())));

        env.getEventBus()
            .post(new CanonicalCommandLineEvent(runtime, request.getCommandName(), optionsParser));
        env.getEventBus().post(new UpdateOptionsEvent(optionsParser));
      }
      buildOptions = runtime.createBuildOptions(optionsParser);
      var analysisCachingDeps =
          RemoteAnalysisCachingDependenciesProviderImpl.forAnalysis(
              env, projectEvaluationResult.activeDirectoriesMatcher());

      if (env.withMergedAnalysisAndExecutionSourceOfTruth()) {
        // a.k.a. Skymeld.
        buildTargetsWithMergedAnalysisExecution(
            request, result, targetPatternPhaseValue, buildOptions, analysisCachingDeps);
      } else {
        buildTargetsWithoutMergedAnalysisExecution(
            request, result, targetPatternPhaseValue, buildOptions, analysisCachingDeps);
      }

      logAnalysisCachingStatsAndMaybeUploadFrontier(analysisCachingDeps);

      if (env.getSkyframeExecutor().getSkyfocusState().enabled()) {
        // Skyfocus only works at the end of a successful build.
        ImmutableSet<Label> topLevelTargets =
            result.getActualTargets().stream()
                .map(ConfiguredTarget::getLabel)
                .collect(toImmutableSet());
        env.getSkyframeExecutor()
            .runSkyfocus(
                topLevelTargets,
                projectEvaluationResult.activeDirectoriesMatcher(),
                env.getReporter(),
                env.getBlazeWorkspace().getPersistentActionCache(),
                env.getOptions());
      }
    } catch (Error | RuntimeException e) {
      // Don't handle the error here. We will do so in stopRequest.
      catastrophe = true;
      throw e;
    } catch (Exception e) {
      throw e;
    } finally {
      if (!catastrophe) {
        // Delete dirty nodes to ensure that they do not accumulate indefinitely.
        long versionWindow = request.getViewOptions().versionWindowForDirtyNodeGc;
        if (versionWindow != -1) {
          env.getSkyframeExecutor().deleteOldNodes(versionWindow);
        }
        // The workspace status actions will not run with certain flags, or if an error occurs early
        // in the build. Ensure that build info is posted on every build.
        env.ensureBuildInfoPosted();
      }
    }
  }

  private static TargetPatternPhaseValue evaluateTargetPatterns(
      CommandEnvironment env, final BuildRequest request, final TargetValidator validator)
      throws LoadingFailedException, TargetParsingException, InterruptedException {
    boolean keepGoing = request.getKeepGoing();
    TargetPatternPhaseValue result =
        env.getSkyframeExecutor()
            .loadTargetPatternsWithFilters(
                env.getReporter(),
                request.getTargets(),
                env.getRelativeWorkingDirectory(),
                request.getLoadingOptions(),
                request.getLoadingPhaseThreadCount(),
                keepGoing,
                request.shouldRunTests());
    if (validator != null) {
      ImmutableSet<Target> targets =
          result.getTargets(env.getReporter(), env.getSkyframeExecutor().getPackageManager());
      validator.validateTargets(targets, keepGoing);
    }
    return result;
  }

  private void buildTargetsWithoutMergedAnalysisExecution(
      BuildRequest request,
      BuildResult result,
      TargetPatternPhaseValue targetPatternPhaseValue,
      BuildOptions buildOptions,
      RemoteAnalysisCachingDependenciesProvider analysisCachingDeps)
      throws BuildFailedException,
          ViewCreationFailedException,
          TargetParsingException,
          LoadingFailedException,
          AbruptExitException,
          RepositoryMappingResolutionException,
          InterruptedException,
          InvalidConfigurationException,
          TestExecException,
          ExitException,
          PostExecutionDumpException {
    AnalysisResult analysisResult =
        AnalysisPhaseRunner.execute(
            env, request, targetPatternPhaseValue, buildOptions, analysisCachingDeps);
    ExecutionTool executionTool = null;
    try {
      // We cannot move the executionTool down to the execution phase part since it does set up the
      // symlinks for tools.
      // TODO(twerth): Extract embedded tool setup from execution tool and move object creation to
      // execution phase.
      executionTool = new ExecutionTool(env, request);
      if (request.getBuildOptions().performAnalysisPhase) {
        if (!analysisResult.getExclusiveTests().isEmpty()
            && executionTool.getTestActionContext().forceExclusiveTestsInParallel()) {
          String testStrategy = request.getOptions(ExecutionOptions.class).testStrategy;
          for (ConfiguredTarget test : analysisResult.getExclusiveTests()) {
            getReporter()
                .handle(
                    Event.warn(
                        test.getLabel()
                            + " is tagged exclusive, but --test_strategy="
                            + testStrategy
                            + " forces parallel test execution."));
          }
          analysisResult = analysisResult.withExclusiveTestsAsParallelTests();
        }
        if (!analysisResult.getExclusiveIfLocalTests().isEmpty()
            && executionTool.getTestActionContext().forceExclusiveIfLocalTestsInParallel()) {
          analysisResult = analysisResult.withExclusiveIfLocalTestsAsParallelTests();
        }

        result.setBuildConfiguration(analysisResult.getConfiguration());
        result.setActualTargets(analysisResult.getTargetsToBuild());
        result.setTestTargets(analysisResult.getTargetsToTest());

        try (SilentCloseable c = Profiler.instance().profile("analysisPostProcessor.process")) {
          analysisPostProcessor.process(request, env, runtime, analysisResult);
        }

        if (needsExecutionPhase(request.getBuildOptions())) {
          try (SilentCloseable closeable = Profiler.instance().profile("ExecutionTool.init")) {
            executionTool.init();
          }
          executionTool.executeBuild(
              request.getId(),
              analysisResult,
              result,
              analysisResult.getPackageRoots(),
              request.getTopLevelArtifactContext());
        } else {
          env.getReporter().post(new NoExecutionEvent());
        }
        FailureDetail delayedFailureDetail = analysisResult.getFailureDetail();
        if (delayedFailureDetail != null) {
          throw new BuildFailedException(
              delayedFailureDetail.getMessage(), DetailedExitCode.of(delayedFailureDetail));
        }

        // Only run this post-build step for builds with SequencedSkyframeExecutor.
        if (env.getSkyframeExecutor() instanceof SequencedSkyframeExecutor) {
          // Enabling this feature will disable Skymeld, so it only runs in the non-Skymeld path.
          if (request.getBuildOptions().aqueryDumpAfterBuildFormat != null) {
            try (SilentCloseable c = Profiler.instance().profile("postExecutionDumpSkyframe")) {
              dumpSkyframeStateAfterBuild(
                  request.getOptions(BuildEventProtocolOptions.class),
                  request.getBuildOptions().aqueryDumpAfterBuildFormat,
                  request.getBuildOptions().aqueryDumpAfterBuildOutputFile);
            } catch (CommandLineExpansionException | IOException | TemplateExpansionException e) {
              throw new PostExecutionDumpException(e);
            } catch (InvalidAqueryOutputFormatException e) {
              throw new PostExecutionDumpException(
                  "--skyframe_state must be used with "
                      + "--output=proto|streamed_proto|textproto|jsonproto.",
                  e);
            }
          }
        }
      }
    } finally {
      if (executionTool != null) {
        executionTool.shutdown();
      }
    }
  }

  /** Performs the merged analysis and execution phase. */
  private void buildTargetsWithMergedAnalysisExecution(
      BuildRequest request,
      BuildResult result,
      TargetPatternPhaseValue targetPatternPhaseValue,
      BuildOptions buildOptions,
      RemoteAnalysisCachingDependenciesProvider remoteAnalysisCachingDependenciesProvider)
      throws InterruptedException,
          AbruptExitException,
          ViewCreationFailedException,
          BuildFailedException,
          TestExecException,
          InvalidConfigurationException,
          RepositoryMappingResolutionException {
    // See https://github.com/bazelbuild/rules_nodejs/issues/3693.
    env.getSkyframeExecutor().clearSyscallCache();

    boolean hasCatastrophe = false;

    ExecutionTool executionTool = new ExecutionTool(env, request);
    // This timer measures time from the first execution activity to the last.
    Stopwatch executionTimer = Stopwatch.createUnstarted();

    // TODO(b/199053098): implement support for --nobuild.
    AnalysisAndExecutionResult analysisAndExecutionResult = null;
    boolean buildCompleted = false;
    try {
      analysisAndExecutionResult =
          AnalysisAndExecutionPhaseRunner.execute(
              env,
              request,
              buildOptions,
              targetPatternPhaseValue,
              () -> executionTool.prepareForExecution(executionTimer),
              result::setBuildConfiguration,
              new BuildDriverKeyTestContext() {
                @Override
                public String getTestStrategy() {
                  return request.getOptions(ExecutionOptions.class).testStrategy;
                }

                @Override
                public boolean forceExclusiveTestsInParallel() {
                  return executionTool.getTestActionContext().forceExclusiveTestsInParallel();
                }

                @Override
                public boolean forceExclusiveIfLocalTestsInParallel() {
                  return executionTool
                      .getTestActionContext()
                      .forceExclusiveIfLocalTestsInParallel();
                }
              },
              remoteAnalysisCachingDependenciesProvider);
      buildCompleted = true;

      // This value is null when there's no analysis.
      if (analysisAndExecutionResult == null) {
        return;
      }
    } catch (InvalidConfigurationException
        | RepositoryMappingResolutionException
        | ViewCreationFailedException
        | BuildFailedException
        | TestExecException e) {
      // These are non-catastrophic.
      buildCompleted = true;
      throw e;
    } catch (Error | RuntimeException e) {
      // These are catastrophic.
      hasCatastrophe = true;
      throw e;
    } finally {
      if (result.getBuildConfiguration() != null) {
        // We still need to do this even in case of an exception.
        result.setConvenienceSymlinks(
            executionTool.handleConvenienceSymlinks(
                env.getBuildResultListener().getAnalyzedTargets(), result.getBuildConfiguration()));
      }
      executionTool.unconditionalExecutionPhaseFinalizations(
          executionTimer, env.getSkyframeExecutor());

      // For the --noskymeld code path, this is done after the analysis phase.
      BuildResultListener buildResultListener = env.getBuildResultListener();
      result.setActualTargets(buildResultListener.getAnalyzedTargets());
      result.setTestTargets(buildResultListener.getAnalyzedTests());

      if (!hasCatastrophe) {
        executionTool.nonCatastrophicFinalizations(
            result,
            env.getBlazeWorkspace().getInUseActionCacheWithoutFurtherLoading(),
            /* explanationHandler= */ null,
            buildCompleted);
      }
    }

    // This is the --keep_going code path: Time to throw the delayed exceptions.
    // Keeping legacy behavior: for execution errors, keep the message of the BuildFailedException
    // empty.
    if (analysisAndExecutionResult.getExecutionDetailedExitCode() != null) {
      throw new BuildFailedException(
          null, analysisAndExecutionResult.getExecutionDetailedExitCode());
    }

    FailureDetail delayedFailureDetail = analysisAndExecutionResult.getFailureDetail();
    if (delayedFailureDetail != null) {
      throw new BuildFailedException(
          delayedFailureDetail.getMessage(), DetailedExitCode.of(delayedFailureDetail));
    }
  }

  private void dumpSkyframeMemory(
      BuildResult buildResult, BuildEventProtocolOptions bepOptions, String format)
      throws PostExecutionDumpException, InterruptedException {
    if (!env.getSkyframeExecutor().tracksStateForIncrementality()) {
      throw new PostExecutionDumpException(
          "Skyframe memory dump requested, but incremental state is not tracked", null);
    }

    boolean reportTransient = true;
    boolean reportConfiguration = true;
    boolean reportPrecomputed = true;
    boolean reportWorkspaceStatus = true;

    for (String flag : Splitter.on(",").split(format)) {
      switch (flag) {
        case "json" -> {} // JSON is the only format we support, no need to note it explicitly
        case "notransient" -> reportTransient = false;
        case "noconfig" -> reportConfiguration = false;
        case "noprecomputed" -> reportPrecomputed = false;
        case "noworkspacestatus" -> reportWorkspaceStatus = false;
        default -> throw new PostExecutionDumpException("Unknown flag: '" + flag + "'", null);
      }
    }

    try {
      OutputStream outputStream;
      UploadContext streamingContext = null;

      if (bepOptions.streamingLogFileUploads) {
        streamingContext =
            runtime
                .getBuildEventArtifactUploaderFactoryMap()
                .select(bepOptions.buildEventUploadStrategy)
                .create(env)
                .startUpload(LocalFileType.PERFORMANCE_LOG, null);
        outputStream = streamingContext.getOutputStream();
        buildResult
            .getBuildToolLogCollection()
            .addUriFuture(SKYFRAME_MEMORY_DUMP_FILE, streamingContext.uriFuture());
      } else {
        Path localPath = env.getOutputBase().getRelative(SKYFRAME_MEMORY_DUMP_FILE);
        outputStream = localPath.getOutputStream();
        buildResult.getBuildToolLogCollection().addLocalFile(SKYFRAME_MEMORY_DUMP_FILE, localPath);
      }

      try (PrintStream printStream = new PrintStream(outputStream)) {

        SkyframeMemoryDumper dumper =
            new SkyframeMemoryDumper(
                DisplayMode.SUMMARY,
                null,
                runtime.getRuleClassProvider(),
                env.getSkyframeExecutor().getEvaluator().getInMemoryGraph(),
                reportTransient,
                reportConfiguration,
                reportPrecomputed,
                reportWorkspaceStatus);
        dumper.dumpFull(printStream);
      }
    } catch (IOException | SkyframeMemoryDumper.DumpFailedException e) {
      throw new PostExecutionDumpException("cannot write Skyframe dump: " + e.getMessage(), e);
    }
  }

  /**
   * Produces an aquery dump of the state of Skyframe.
   *
   * <p>There are 2 possible output channels: a local file or a remote FS.
   */
  private void dumpSkyframeStateAfterBuild(
      @Nullable BuildEventProtocolOptions besOptions,
      String format,
      @Nullable PathFragment outputFilePathFragment)
      throws CommandLineExpansionException,
          IOException,
          InvalidAqueryOutputFormatException,
          TemplateExpansionException {
    Preconditions.checkState(env.getSkyframeExecutor() instanceof SequencedSkyframeExecutor);

    UploadContext streamingContext = null;
    Path localOutputFilePath = null;
    String outputFileName;

    if (outputFilePathFragment == null) {
      outputFileName = getDefaultOutputFileName(format);
      if (besOptions != null && besOptions.streamingLogFileUploads) {
        streamingContext =
            runtime
                .getBuildEventArtifactUploaderFactoryMap()
                .select(besOptions.buildEventUploadStrategy)
                .create(env)
                .startUpload(LocalFileType.PERFORMANCE_LOG, /* inputSupplier= */ null);
      } else {
        localOutputFilePath = env.getOutputBase().getRelative(outputFileName);
      }
    } else {
      localOutputFilePath = env.getOutputBase().getRelative(outputFilePathFragment);
      outputFileName = localOutputFilePath.getBaseName();
    }

    if (localOutputFilePath != null) {
      getReporter().handle(Event.info("Writing aquery dump to " + localOutputFilePath));
      getReporter()
          .post(new StartingAqueryDumpAfterBuildEvent(localOutputFilePath, outputFileName));
    } else {
      getReporter().handle(Event.info("Streaming aquery dump."));
      getReporter().post(new StartingAqueryDumpAfterBuildEvent(streamingContext, outputFileName));
    }

    try (OutputStream outputStream = initOutputStream(streamingContext, localOutputFilePath);
        PrintStream printStream = new PrintStream(outputStream);
        AqueryOutputHandler aqueryOutputHandler =
            ActionGraphProtoOutputFormatterCallback.constructAqueryOutputHandler(
                OutputType.fromString(format), outputStream, printStream)) {
      // These options are fixed for simplicity. We'll add more configurability if the need arises.
      ActionGraphDump actionGraphDump =
          new ActionGraphDump(
              /* includeActionCmdLine= */ false,
              /* includeArtifacts= */ true,
              /* includePrunedInputs= */ true,
              /* actionFilters= */ null,
              /* includeParamFiles= */ false,
              /* includeFileWriteContents= */ false,
              aqueryOutputHandler,
              getReporter());
      AqueryProcessor.dumpActionGraph(env, aqueryOutputHandler, actionGraphDump);
    }
  }

  private static String getDefaultOutputFileName(String format) {
    switch (format) {
      case "proto":
        return "aquery_dump.proto";
      case "streamed_proto":
        return "aquery_dump.pb";
      case "textproto":
        return "aquery_dump.textproto";
      case "jsonproto":
        return "aquery_dump.json";
      default:
        throw new IllegalArgumentException("Unsupported format type: " + format);
    }
  }

  private static OutputStream initOutputStream(
      @Nullable UploadContext streamingContext, Path outputFilePath) throws IOException {
    if (streamingContext != null) {
      return new BufferedOutputStream(streamingContext.getOutputStream());
    }
    return new BufferedOutputStream(outputFilePath.getOutputStream());
  }

  private void reportExceptionError(Exception e) {
    if (e.getMessage() != null) {
      getReporter().handle(Event.error(e.getMessage()));
    }
  }

  public BuildResult processRequest(
      BuildRequest request, TargetValidator validator, OptionsParsingResult options) {
    return processRequest(request, validator, /* postBuildCallback= */ null, options);
  }

  /**
   * The crux of the build system. Builds the targets specified in the request using the specified
   * Executor.
   *
   * <p>Performs loading, analysis and execution for the specified set of targets, honoring the
   * configuration options in the BuildRequest. Returns normally iff successful, throws an exception
   * otherwise.
   *
   * <p>The caller is responsible for setting up and syncing the package cache.
   *
   * <p>During this function's execution, the actualTargets and successfulTargets fields of the
   * request object are set.
   *
   * @param request the build request that this build tool is servicing, which specifies various
   *     options; during this method's execution, the actualTargets and successfulTargets fields of
   *     the request object are populated
   * @param validator an optional target validator
   * @param postBuildCallback an optional callback called after the build has been completed
   *     successfully.
   * @param options the options parsing result containing the options parsed so far, excluding those
   *     from flagsets. This will be cast to an {@link OptionsParser} in order to add any options
   *     from flagsets.
   * @return the result as a {@link BuildResult} object
   */
  public BuildResult processRequest(
      BuildRequest request,
      TargetValidator validator,
      PostBuildCallback postBuildCallback,
      OptionsParsingResult options) {
    BuildResult result = new BuildResult(request.getStartTime());
    maybeSetStopOnFirstFailure(request, result);
    Throwable crash = null;
    DetailedExitCode detailedExitCode = null;
    try {
      try (SilentCloseable c = Profiler.instance().profile("buildTargets")) {
        // This OptionsParsingResult is essentially a wrapper around the OptionsParser in
        // https://github.com/bazelbuild/bazel/blob/master/src/main/java/com/google/devtools/build/lib/runtime/BlazeCommandDispatcher.java#L341. Casting it back to
        // an OptionsParser is safe, and necessary in order to add any options from flagsets.
        buildTargets(request, result, validator, (OptionsParser) options);
      }
      detailedExitCode = DetailedExitCode.success();
      if (postBuildCallback != null) {
        try (SilentCloseable c = Profiler.instance().profile("postBuildCallback.process")) {
          result.setPostBuildCallbackFailureDetail(
              postBuildCallback.process(result.getSuccessfulTargets()));
        } catch (InterruptedException e) {
          detailedExitCode =
              InterruptedFailureDetails.detailedExitCode("post build callback interrupted");
        }
      }

      if (env.getSkyframeExecutor() instanceof SequencedSkyframeExecutor
          && request.getBuildOptions().skyframeMemoryDump != null) {
        try (SilentCloseable c = Profiler.instance().profile("BuildTool.dumpSkyframeMemory")) {
          dumpSkyframeMemory(
              result,
              request.getOptions(BuildEventProtocolOptions.class),
              request.getBuildOptions().skyframeMemoryDump);
        }
      }
    } catch (BuildFailedException e) {
      if (!e.isErrorAlreadyShown()) {
        // The actual error has not already been reported by the Builder.
        // TODO(janakr): This is wrong: --keep_going builds with errors don't have a message in
        //  this BuildFailedException, so any error message that is only reported here will be
        //  missing for --keep_going builds. All error reporting should be done at the site of the
        //  error, if only for clearer behavior.
        reportExceptionError(e);
      }
      if (e.isCatastrophic()) {
        result.setCatastrophe();
      }
      detailedExitCode = e.getDetailedExitCode();
    } catch (InterruptedException e) {
      // We may have been interrupted by an error, or the user's interruption may have raced with
      // an error, so check to see if we should report that error code instead.
      detailedExitCode = env.getRuntime().getCrashExitCode();
      AbruptExitException environmentPendingAbruptExitException = env.getPendingException();
      if (detailedExitCode == null && environmentPendingAbruptExitException != null) {
        detailedExitCode = environmentPendingAbruptExitException.getDetailedExitCode();
        // Report the exception from the environment - the exception we're handling here is just an
        // interruption.
        reportExceptionError(environmentPendingAbruptExitException);
      }
      if (detailedExitCode == null) {
        String message = "build interrupted";
        detailedExitCode = InterruptedFailureDetails.detailedExitCode(message);
        env.getReporter().handle(Event.error(message));
        env.getEventBus().post(new BuildInterruptedEvent());
      } else {
        result.setCatastrophe();
      }
    } catch (TargetParsingException | LoadingFailedException e) {
      detailedExitCode = e.getDetailedExitCode();
      reportExceptionError(e);
    } catch (RepositoryMappingResolutionException e) {
      detailedExitCode = e.getDetailedExitCode();
      reportExceptionError(e);
    } catch (ViewCreationFailedException e) {
      detailedExitCode = DetailedExitCode.of(ExitCode.PARSING_FAILURE, e.getFailureDetail());
      reportExceptionError(e);
    } catch (ExitException e) {
      detailedExitCode = e.getDetailedExitCode();
      reportExceptionError(e);
    } catch (TestExecException e) {
      // ExitCode.SUCCESS means that build was successful. Real return code of program
      // is going to be calculated in TestCommand.doTest().
      detailedExitCode = DetailedExitCode.success();
      reportExceptionError(e);
    } catch (InvalidConfigurationException e) {
      detailedExitCode = e.getDetailedExitCode();
      reportExceptionError(e);
      // TODO(gregce): With "global configurations" we cannot tie a configuration creation failure
      // to a single target and have to halt the entire build. Once configurations are genuinely
      // created as part of the analysis phase they should report their error on the level of the
      // target(s) that triggered them.
      result.setCatastrophe();
    } catch (AbruptExitException e) {
      detailedExitCode = e.getDetailedExitCode();
      reportExceptionError(e);
      result.setCatastrophe();
    } catch (PostExecutionDumpException e) {
      detailedExitCode =
          DetailedExitCode.of(
              FailureDetail.newBuilder()
                  .setMessage(e.getMessage())
                  .setActionQuery(
                      ActionQuery.newBuilder()
                          .setCode(ActionQuery.Code.SKYFRAME_STATE_AFTER_EXECUTION)
                          .build())
                  .build());
      reportExceptionError(e);
    } catch (Throwable throwable) {
      crash = throwable;
      detailedExitCode = CrashFailureDetails.detailedExitCodeForThrowable(crash);
      Throwables.throwIfUnchecked(throwable);
      throw new IllegalStateException(throwable);
    } finally {
      if (detailedExitCode == null) {
        detailedExitCode =
            CrashFailureDetails.detailedExitCodeForThrowable(
                new IllegalStateException("Unspecified DetailedExitCode"));
      }
      try (SilentCloseable c = Profiler.instance().profile("stopRequest")) {
        stopRequest(result, crash, detailedExitCode);
      }
    }

    return result;
  }

  /**
   * Handles post-build analysis caching operations.
   *
   * <ol>
   *   <li>If this is a cache-writing build, then this will serialize and upload the frontier
   *       Skyframe values.
   *   <li>If this is a cache-reading build, then this will report the cache hit stats while
   *       downloading the frontier Skyframe values during analysis.
   * </ol>
   */
  private void logAnalysisCachingStatsAndMaybeUploadFrontier(
      RemoteAnalysisCachingDependenciesProvider dependenciesProvider)
      throws InterruptedException, AbruptExitException {
    if (!(env.getSkyframeExecutor() instanceof SequencedSkyframeExecutor)) {
      return;
    }

    switch (dependenciesProvider.mode()) {
      case UPLOAD:
      // fall through
      case DUMP_UPLOAD_MANIFEST_ONLY:
        uploadFrontier(dependenciesProvider);
        break;
      case DOWNLOAD:
        reportRemoteAnalysisCachingStats();
        break;
      case OFF:
        break;
    }
  }

  private static void maybeSetStopOnFirstFailure(BuildRequest request, BuildResult result) {
    if (shouldStopOnFailure(request)) {
      result.setStopOnFirstFailure(true);
    }
  }

  private static boolean shouldStopOnFailure(BuildRequest request) {
    return !(request.getKeepGoing() && request.getExecutionOptions().testKeepGoing);
  }

  /** Initializes the output filter to the value given with {@code --output_filter}. */
  private void initializeOutputFilter(BuildRequest request) {
    RegexPatternOption outputFilterOption = request.getBuildOptions().outputFilter;
    if (outputFilterOption != null) {
      getReporter()
          .setOutputFilter(
              OutputFilter.RegexOutputFilter.forPattern(outputFilterOption.regexPattern()));
    }
  }

  private static boolean needsExecutionPhase(BuildRequestOptions options) {
    return options.performAnalysisPhase && options.performExecutionPhase;
  }

  /**
   * Stops processing the specified request.
   *
   * <p>This logs the build result, cleans up and stops the clock.
   *
   * @param result result to update
   * @param crash any unexpected {@link RuntimeException} or {@link Error}, may be null
   * @param detailedExitCode describes the exit code and an optional detailed failure value to add
   *     to {@code result}
   */
  public void stopRequest(
      BuildResult result, @Nullable Throwable crash, DetailedExitCode detailedExitCode) {
    Preconditions.checkState((crash == null) || !detailedExitCode.isSuccess());
    result.setUnhandledThrowable(crash);
    result.setDetailedExitCode(detailedExitCode);
    if (!detailedExitCode.isSuccess()) {
      logger.atInfo().log(
          "Unsuccessful command ended with FailureDetail: %s", detailedExitCode.getFailureDetail());
    }

    InterruptedException ie = null;

    // The stop time has to be captured before we send the BuildCompleteEvent.
    result.setStopTime(runtime.getClock().currentTimeMillis());

    // Skip the build complete events so that modules can run blazeShutdownOnCrash without thinking
    // that the build completed normally. BlazeCommandDispatcher will call handleCrash.
    if (crash == null) {
      try {
        Profiler.instance().markPhase(ProfilePhase.FINISH);
      } catch (InterruptedException e) {
        env.getReporter().handle(Event.error("Build interrupted during command completion"));
        ie = e;
      }

      env.getEventBus()
          .post(
              new BuildCompleteEvent(
                  result,
                  ImmutableList.of(
                      BuildEventIdUtil.buildToolLogs(), BuildEventIdUtil.buildMetrics())));
    }
    // Post the build tool logs event; the corresponding local files may be contributed from
    // modules, and this has to happen after posting the BuildCompleteEvent because that's when
    // modules add their data to the collection.
    env.getEventBus().post(result.getBuildToolLogCollection().freeze().toEvent());
    if (ie != null) {
      if (detailedExitCode.isSuccess()) {
        result.setDetailedExitCode(
            InterruptedFailureDetails.detailedExitCode(
                "Build interrupted during command completion"));
      } else if (!detailedExitCode.getExitCode().equals(ExitCode.INTERRUPTED)) {
        logger.atWarning().withCause(ie).log(
            "Suppressed interrupted exception during stop request because already failing with: %s",
            detailedExitCode);
      }
    }
  }

  /**
   * Validates the options for this BuildRequest.
   *
   * <p>Issues warnings for the use of deprecated options, and warnings or errors for any option
   * settings that conflict.
   */
  @VisibleForTesting
  public void validateOptions(BuildRequest request) {
    for (String issue : request.validateOptions()) {
      getReporter().handle(Event.warn(issue));
    }
  }

  /** Returns the project directories found in a project file. */
  public static PathFragmentPrefixTrie getWorkingSetMatcherForSkyfocus(
      Label projectFile, SkyframeExecutor skyframeExecutor, ExtendedEventHandler eventHandler)
      throws InvalidConfigurationException {
    ProjectValue.Key key = new ProjectValue.Key(projectFile);
    EvaluationResult<SkyValue> result =
        skyframeExecutor.evaluateSkyKeys(
            eventHandler, ImmutableList.of(key), /* keepGoing= */ false);

    if (result.hasError()) {
      // InvalidConfigurationException is chosen for convenience, and it's distinguished from
      // the other InvalidConfigurationException cases by Code.INVALID_PROJECT.
      throw new InvalidConfigurationException(
          "unexpected error reading project configuration: " + result.getError(),
          Code.INVALID_PROJECT);
    }

    return PathFragmentPrefixTrie.of(((ProjectValue) result.get(key)).getDefaultActiveDirectory());
  }

  /** Returns the set of options from the selected {@code projectFile} in command line format. */
  public static ImmutableSet<String> applySclConfigs(
      BuildOptions buildOptionsBeforeFlagSets,
      ImmutableMap<String, String> userOptions,
      ConfigFlagDefinitions configFlagDefinitions,
      Label projectFile,
      boolean enforceCanonicalConfigs,
      SkyframeExecutor skyframeExecutor,
      ExtendedEventHandler eventHandler)
      throws InvalidConfigurationException {

    FlagSetValue flagSetValue =
        Project.modifyBuildOptionsWithFlagSets(
            projectFile,
            buildOptionsBeforeFlagSets,
            userOptions,
            configFlagDefinitions,
            enforceCanonicalConfigs,
            eventHandler,
            skyframeExecutor);

    // Options from the selected project config.
    return flagSetValue.getOptionsFromFlagset();
  }

  private Reporter getReporter() {
    return env.getReporter();
  }

  /** Describes a failure that isn't severe enough to halt the command in keep_going mode. */
  // TODO(mschaller): consider promoting this to be a sibling of AbruptExitException.
  public static class ExitException extends Exception {

    private final DetailedExitCode detailedExitCode;

    ExitException(DetailedExitCode detailedExitCode) {
      super(checkNotNull(detailedExitCode.getFailureDetail(), "failure detail").getMessage());
      this.detailedExitCode = detailedExitCode;
    }

    DetailedExitCode getDetailedExitCode() {
      return detailedExitCode;
    }
  }

  private static final class RemoteAnalysisCachingDependenciesProviderImpl
      implements RemoteAnalysisCachingDependenciesProvider {
    private final RemoteAnalysisCacheMode mode;
    private final String serializedFrontierProfile;
    private final Supplier<ObjectCodecs> analysisObjectCodecsSupplier;
    private final PathFragmentPrefixTrie activeDirectoriesMatcher;
    private final RemoteAnalysisCachingEventListener listener;
    private final HashCode blazeInstallMD5;
    private final Future<FingerprintValueService> fingerprintValueServiceFuture;

    /** Cache lookup parameter requiring integration with external version control. */
    private final IntVersion evaluatingVersion;

    /** Cache lookup parameter requiring integration with external version control. */
    private final Optional<ClientId> snapshot;

    // Non-final because the top level BuildConfigurationValue is determined just before analysis
    // begins in BuildView for the download/deserialization pass, which is later than when this
    // object was created in BuildTool.
    private String topLevelConfigChecksum;

    private final ModifiedFileSet diffFromEvaluatingVersion;

    public static RemoteAnalysisCachingDependenciesProvider forAnalysis(
        CommandEnvironment env, Optional<PathFragmentPrefixTrie> activeDirectoriesMatcher)
        throws InterruptedException, AbruptExitException {
      var options = env.getOptions().getOptions(RemoteAnalysisCachingOptions.class);
      if (options == null
          || !env.getCommand().buildPhase().executes()
          || activeDirectoriesMatcher.isEmpty()) {
        return DisabledDependenciesProvider.INSTANCE;
      }

      RemoteAnalysisCacheMode mode = options.mode;
      if (mode == RemoteAnalysisCacheMode.OFF) {
        return DisabledDependenciesProvider.INSTANCE;
      }

      var dependenciesProvider =
          new RemoteAnalysisCachingDependenciesProviderImpl(
              env, activeDirectoriesMatcher.get(), options.mode, options.serializedFrontierProfile);

      switch (options.mode) {
        case RemoteAnalysisCacheMode.DUMP_UPLOAD_MANIFEST_ONLY:
          // Skips FrontierViolationChecker for manifest dump because it's useful for debugging how
          // violations change the frontier.
          return dependenciesProvider;
        case RemoteAnalysisCacheMode.UPLOAD:
        case RemoteAnalysisCacheMode.DOWNLOAD:
          return FrontierViolationChecker.check(
              dependenciesProvider,
              env.getOptions().getOptions(SkyfocusOptions.class).frontierViolationCheck,
              env.getReporter(),
              env.getSkyframeExecutor().getEvaluator(),
              env.getRuntime().getProductName());
        default:
          throw new IllegalStateException("Unknown RemoteAnalysisCacheMode: " + options.mode);
      }
    }

    private RemoteAnalysisCachingDependenciesProviderImpl(
        CommandEnvironment env,
        PathFragmentPrefixTrie activeDirectoriesMatcher,
        RemoteAnalysisCacheMode mode,
        String serializedFrontierProfile)
        throws InterruptedException {
      this.mode = mode;
      this.serializedFrontierProfile = serializedFrontierProfile;
      this.analysisObjectCodecsSupplier =
          Suppliers.memoize(
              () ->
                  initAnalysisObjectCodecs(
                      requireNonNull(
                              env.getBlazeWorkspace().getAnalysisObjectCodecRegistrySupplier())
                          .get(),
                      env.getRuntime().getRuleClassProvider(),
                      env.getBlazeWorkspace().getSkyframeExecutor()));
      this.fingerprintValueServiceFuture =
          CompletableFuture.supplyAsync(
              () ->
                  env.getBlazeWorkspace()
                      .getFingerprintValueServiceFactory()
                      .create(env.getOptions()));
      this.activeDirectoriesMatcher = activeDirectoriesMatcher;
      this.listener = env.getRemoteAnalysisCachingEventListener();
      if (env.getSkyframeBuildView().getBuildConfiguration() != null) {
        // null at construction time during deserializing build. Will be set later during analysis.
        this.topLevelConfigChecksum =
            BuildView.getTopLevelConfigurationTrimmedOfTestOptionsChecksum(
                env.getSkyframeBuildView().getBuildConfiguration().getOptions(), env.getReporter());
      }
      this.blazeInstallMD5 = requireNonNull(env.getDirectories().getInstallMD5());
      this.diffFromEvaluatingVersion = env.getSkyframeExecutor().getDiffFromEvaluatingVersion();

      var workspaceInfoFromDiff = env.getWorkspaceInfoFromDiff();
      if (workspaceInfoFromDiff == null) {
        // If there is no workspace info, we cannot confidently version the nodes. Use the min
        // version as a sentinel.
        this.evaluatingVersion = IntVersion.of(Long.MIN_VALUE);
        this.snapshot = Optional.empty();
      } else {
        this.evaluatingVersion = workspaceInfoFromDiff.getEvaluatingVersion();
        this.snapshot = workspaceInfoFromDiff.getSnapshot();
      }
    }

    private static ObjectCodecs initAnalysisObjectCodecs(
        ObjectCodecRegistry registry,
        RuleClassProvider ruleClassProvider,
        SkyframeExecutor skyframeExecutor) {
      ImmutableClassToInstanceMap.Builder<Object> serializationDeps =
          ImmutableClassToInstanceMap.builder()
              .put(
                  ArtifactSerializationContext.class,
                  skyframeExecutor.getSkyframeBuildView().getArtifactFactory()::getSourceArtifact)
              .put(RuleClassProvider.class, ruleClassProvider)
              // We need a RootCodecDependencies but don't care about the likely roots.
              .put(Root.RootCodecDependencies.class, new Root.RootCodecDependencies())
              // This is needed to determine TargetData for a ConfiguredTarget during serialization.
              .put(PrerequisitePackageFunction.class, skyframeExecutor::getExistingPackage);

      return new ObjectCodecs(registry, serializationDeps.build());
    }

    @Override
    public RemoteAnalysisCacheMode mode() {
      return mode;
    }

    @Override
    public String serializedFrontierProfile() {
      return serializedFrontierProfile;
    }

    @Override
    public boolean withinActiveDirectories(PackageIdentifier pkg) {
      return activeDirectoriesMatcher.includes(pkg.getPackageFragment());
    }

    private volatile FrontierNodeVersion frontierNodeVersionSingleton = null;

    /**
     * Returns a byte array to uniquely version SkyValues for serialization.
     *
     * <p>This should only be caalled when Bazel has determined values for all version components
     * for instantiating a {@link FrontierNodeVersion}.
     *
     * <p>This could be in the constructor if we know about the {@code topLevelConfig} component at
     * initialization, but it is created much later during the deserialization pass.
     */
    @Override
    public FrontierNodeVersion getSkyValueVersion() {
      if (frontierNodeVersionSingleton == null) {
        synchronized (this) {
          // Avoid re-initializing the value for subsequent threads with double checked locking.
          if (frontierNodeVersionSingleton == null) {
            frontierNodeVersionSingleton =
                new FrontierNodeVersion(
                    topLevelConfigChecksum,
                    activeDirectoriesMatcher.toString(),
                    blazeInstallMD5,
                    evaluatingVersion,
                    snapshot);
            logger.atInfo().log(
                "Remote analysis caching SkyValue version: %s (actual evaluating version: %s)",
                frontierNodeVersionSingleton, evaluatingVersion);
            listener.recordSkyValueVersion(frontierNodeVersionSingleton);
          }
        }
      }
      return frontierNodeVersionSingleton;
    }

    @Override
    public ObjectCodecs getObjectCodecs() {
      return analysisObjectCodecsSupplier.get();
    }

    @Override
    public FingerprintValueService getFingerprintValueService() {
      try {
        return fingerprintValueServiceFuture.get();
      } catch (InterruptedException | ExecutionException e) {
        throw new IllegalStateException("Unable to initialize fingerprint value service", e);
      }
    }

    @Override
    public void recordRetrievalResult(RetrievalResult retrievalResult, SkyKey key) {
      listener.recordRetrievalResult(retrievalResult, key);
    }

    @Override
    public void recordSerializationException(SerializationException e) {
      listener.recordSerializationException(e);
    }

    @Override
    public void setTopLevelConfigChecksum(String topLevelConfigChecksum) {
      this.topLevelConfigChecksum = topLevelConfigChecksum;
    }

    @Override
    public ModifiedFileSet getDiffFromEvaluatingVersion() {
      return checkNotNull(
          diffFromEvaluatingVersion,
          String.format("expected to be not null when the mode is upload or download: %s", mode));
    }
  }

  private void uploadFrontier(RemoteAnalysisCachingDependenciesProvider dependenciesProvider)
      throws InterruptedException, AbruptExitException {
    try (SilentCloseable closeable = Profiler.instance().profile("serializeAndUploadFrontier")) {
      Optional<FailureDetail> maybeFailureDetail =
          FrontierSerializer.serializeAndUploadFrontier(
              dependenciesProvider,
              env.getSkyframeExecutor(),
              env.getVersionGetter(),
              env.getReporter(),
              env.getEventBus());
      if (maybeFailureDetail.isPresent()) {
        throw new AbruptExitException(DetailedExitCode.of(maybeFailureDetail.get()));
      }
    }
  }

  private void reportRemoteAnalysisCachingStats() {
    var listener = env.getRemoteAnalysisCachingEventListener();
    env.getReporter()
        .handle(
            Event.info(
                String.format(
                    "Remote analysis caching stats: %s cache hits (%s analysis, %s execution),"
                        + " %s cache misses (%s analysis, %s execution).",
                    listener.getAnalysisNodeCacheHits() + listener.getExecutionNodeCacheHits(),
                    listener.getAnalysisNodeCacheHits(),
                    listener.getExecutionNodeCacheHits(),
                    listener.getAnalysisNodeCacheMisses() + listener.getExecutionNodeCacheMisses(),
                    listener.getAnalysisNodeCacheMisses(),
                    listener.getExecutionNodeCacheMisses())));

    FrontierViolationChecker.accumulateCacheHitsAcrossInvocations(listener);
  }
}
