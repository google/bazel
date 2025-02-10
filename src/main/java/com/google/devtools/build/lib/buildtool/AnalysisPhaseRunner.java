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
package com.google.devtools.build.lib.buildtool;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.devtools.build.lib.analysis.config.BuildConfigurationValue.configurationId;
import static com.google.devtools.build.lib.buildtool.AnalysisPhaseRunner.ProjectFileFeature.ANALYSIS_CACHING;
import static com.google.devtools.build.lib.buildtool.AnalysisPhaseRunner.ProjectFileFeature.SCL_CONFIG;
import static com.google.devtools.build.lib.buildtool.AnalysisPhaseRunner.ProjectFileFeature.SKYFOCUS;
import static com.google.devtools.build.lib.server.FailureDetails.RemoteAnalysisCaching.Code.PROJECT_FILE_NOT_FOUND;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions.RemoteAnalysisCacheMode.OFF;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.TestExecException;
import com.google.devtools.build.lib.analysis.AnalysisPhaseCompleteEvent;
import com.google.devtools.build.lib.analysis.AnalysisResult;
import com.google.devtools.build.lib.analysis.BuildView;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.Project;
import com.google.devtools.build.lib.analysis.ProjectResolutionException;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.buildeventstream.AbortedEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.Aborted.AbortReason;
import com.google.devtools.build.lib.buildtool.buildevent.NoAnalyzeEvent;
import com.google.devtools.build.lib.buildtool.buildevent.TestFilteringCompleteEvent;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.cmdline.TargetPattern.Parser;
import com.google.devtools.build.lib.collect.PathFragmentPrefixTrie;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.pkgcache.LoadingFailedException;
import com.google.devtools.build.lib.profiler.ProfilePhase;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.server.FailureDetails.BuildConfiguration.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.RemoteAnalysisCaching;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue.RepositoryMappingResolutionException;
import com.google.devtools.build.lib.skyframe.TargetPatternPhaseValue;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.AspectAnalyzedEvent;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TestAnalyzedEvent;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TopLevelTargetAnalyzedEvent;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TopLevelTargetSkippedEvent;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingDependenciesProvider;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.RegexFilter;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Performs target pattern eval, configuration creation, loading and analysis. */
public final class AnalysisPhaseRunner {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private AnalysisPhaseRunner() {}

  public static AnalysisResult execute(
      CommandEnvironment env,
      BuildRequest request,
      TargetPatternPhaseValue targetPatternPhaseValue,
      BuildOptions buildOptions,
      RemoteAnalysisCachingDependenciesProvider remoteAnalysisCachingDependenciesProvider)
      throws BuildFailedException,
          InterruptedException,
          ViewCreationFailedException,
          TargetParsingException,
          LoadingFailedException,
          AbruptExitException,
          InvalidConfigurationException,
          RepositoryMappingResolutionException {

    // Compute the heuristic instrumentation filter if needed.
    if (request.needsInstrumentationFilter()) {
      try (SilentCloseable c = Profiler.instance().profile("Compute instrumentation filter")) {
        String instrumentationFilter =
            InstrumentationFilterSupport.computeInstrumentationFilter(
                env.getReporter(),
                // TODO(ulfjack): Expensive. Make this part of the TargetPatternPhaseValue or write
                // a new SkyFunction to compute it?
                targetPatternPhaseValue.getTestsToRun(env.getReporter(), env.getPackageManager()));
        try {
          // We're modifying the buildOptions in place, which is not ideal, but we also don't want
          // to pay the price for making a copy. Maybe reconsider later if this turns out to be a
          // problem (and the performance loss may not be a big deal).
          buildOptions.get(CoreOptions.class).instrumentationFilter =
              new RegexFilter.RegexFilterConverter().convert(instrumentationFilter);
        } catch (OptionsParsingException e) {
          throw new InvalidConfigurationException(Code.HEURISTIC_INSTRUMENTATION_FILTER_INVALID, e);
        }
      }
    }

    // Exit if there are any pending exceptions from modules.
    env.throwPendingException();

    AnalysisResult analysisResult = null;
    if (request.getBuildOptions().performAnalysisPhase) {
      Profiler.instance().markPhase(ProfilePhase.ANALYZE);

      try (SilentCloseable c = Profiler.instance().profile("runAnalysisPhase")) {
        analysisResult =
            runAnalysisPhase(
                env,
                request,
                targetPatternPhaseValue,
                buildOptions,
                remoteAnalysisCachingDependenciesProvider);
      }

      for (BlazeModule module : env.getRuntime().getBlazeModules()) {
        module.afterAnalysis(env, request, buildOptions, analysisResult);
      }

      if (request.shouldRunTests()) {
        reportTargetsWithTests(
            env,
            analysisResult.getTargetsToBuild(),
            Preconditions.checkNotNull(analysisResult.getTargetsToTest()));
      } else {
        reportTargets(env, analysisResult.getTargetsToBuild());
      }

      postAbortedEventsForSkippedTargets(env, analysisResult.getTargetsToSkip());
    } else {
      env.getReporter().handle(Event.progress("Loading complete."));
      env.getReporter().post(new NoAnalyzeEvent());
      logger.atInfo().log("No analysis requested, so finished");
      FailureDetail failureDetail =
          BuildView.createAnalysisFailureDetail(
              targetPatternPhaseValue, /* skyframeAnalysisResult= */ null);
      if (failureDetail != null) {
        throw new BuildFailedException(
            failureDetail.getMessage(), DetailedExitCode.of(failureDetail));
      }
    }

    return analysisResult;
  }

  /** A simple container for storing processed evaluation results of the PROJECT.scl file. */
  record ProjectEvaluationResult(
      ImmutableSet<String> buildOptions,
      Optional<PathFragmentPrefixTrie> activeDirectoriesMatcher,
      Optional<Label> projectFile) {

    public ProjectEvaluationResult {
      checkArgument(buildOptions != null, "buildOptions cannot be null.");
      checkArgument(activeDirectoriesMatcher != null, "activeDirectoriesMatcher cannot be null.");
      checkArgument(projectFile != null, "projectFile cannot be null.");
    }
  }

  enum ProjectFileFeature {
    ANALYSIS_CACHING,
    SCL_CONFIG,
    SKYFOCUS;
  }

  /**
   * Evaluates the PROJECT.scl file and set corresponding options, if required by specific feature
   * flags (e.g. canonical configurations, remote analysis caching).
   *
   * <p>May have a side-effect from updating the fields in the {@link CommandEnvironment} {@code
   * env} parameter.
   *
   * <p>Shared by both Skymeld and non-Skymeld analysis.
   */
  static ProjectEvaluationResult evaluateProjectFile(
      BuildRequest request,
      BuildOptions buildOptions,
      ImmutableMap<String, String> userOptions,
      TargetPatternPhaseValue targetPatternPhaseValue,
      CommandEnvironment env)
      throws LoadingFailedException, InvalidConfigurationException {
    EnumSet<ProjectFileFeature> featureFlags = EnumSet.noneOf(ProjectFileFeature.class);

    if (env.getCommand().buildPhase().executes()
        && env.getOptions().getOptions(RemoteAnalysisCachingOptions.class).mode != OFF) {
      // RemoteAnalysisCachingOptions is never null because it's a build command flag, and this
      // method only runs for build commands.
      featureFlags.add(ANALYSIS_CACHING);
    }

    if (!Strings.isNullOrEmpty(buildOptions.get(CoreOptions.class).sclConfig)
        || request.getBuildOptions().enforceProjectConfigs) {
      featureFlags.add(SCL_CONFIG);
    }

    if (env.getSkyframeExecutor().getSkyfocusState().enabled()) {
      featureFlags.add(SKYFOCUS);
    }

    if (featureFlags.isEmpty()) {
      // All feature flags disabled.
      return new ProjectEvaluationResult(ImmutableSet.of(), Optional.empty(), Optional.empty());
    }

    Label projectFile = null;
    try {
      projectFile =
          Project.getProjectFile(
              targetPatternPhaseValue.getTargetLabels(),
              env.getSkyframeExecutor(),
              env.getReporter());
    } catch (ProjectResolutionException e) {
      throw new LoadingFailedException(
          e.getMessage(),
          DetailedExitCode.of(ExitCode.PARSING_FAILURE, FailureDetail.getDefaultInstance()));
    }

    if (featureFlags.contains(ANALYSIS_CACHING) && projectFile == null) {
      // TODO: b/353233779 - consider falling back on full serialization when there is no
      // project matcher.
      String message =
          "Failed to find PROJECT.scl file for: " + targetPatternPhaseValue.getTargetLabels();
      throw new LoadingFailedException(
          message,
          DetailedExitCode.of(
              FailureDetail.newBuilder()
                  .setMessage(message)
                  .setRemoteAnalysisCaching(
                      RemoteAnalysisCaching.newBuilder().setCode(PROJECT_FILE_NOT_FOUND))
                  .build()));
    }

    PathFragmentPrefixTrie projectMatcher = null;

    if (featureFlags.contains(ANALYSIS_CACHING) || featureFlags.contains(SKYFOCUS)) {
      projectMatcher =
          projectFile == null
              ? null // Skyfocus can work without a project directory matcher.
              : BuildTool.getWorkingSetMatcherForSkyfocus(
                  projectFile, env.getSkyframeExecutor(), env.getReporter());
    }

    if (featureFlags.contains(SCL_CONFIG) && projectFile != null) {
      // Do not apply canonical configurations if the project file doesn't exist.
      ImmutableSet<String> options =
          BuildTool.applySclConfigs(
              buildOptions,
              userOptions,
              env.getConfigFlagDefinitions(),
              projectFile,
              request.getBuildOptions().enforceProjectConfigs,
              env.getSkyframeExecutor(),
              env.getReporter());
      return new ProjectEvaluationResult(
          options, Optional.ofNullable(projectMatcher), Optional.ofNullable(projectFile));
    }

    return new ProjectEvaluationResult(
        ImmutableSet.of(), Optional.ofNullable(projectMatcher), Optional.ofNullable(projectFile));
  }

  static void postAbortedEventsForSkippedTargets(
      CommandEnvironment env, ImmutableSet<ConfiguredTarget> targetsToSkip) {
    for (ConfiguredTarget target : targetsToSkip) {
      BuildConfigurationValue config =
          env.getSkyframeExecutor()
              .getConfiguration(env.getReporter(), target.getConfigurationKey());
      Label label = target.getOriginalLabel();
      env.getEventBus()
          .post(
              new AbortedEvent(
                  BuildEventIdUtil.targetCompleted(label, configurationId(config)),
                  AbortReason.SKIPPED,
                  String.format("Target %s build was skipped.", label),
                  label));
    }
  }

  /**
   * Performs the initial phases 0-2 of the build: Setup, Loading and Analysis.
   *
   * <p>Postcondition: On success, populates the BuildRequest's set of targets to build.
   *
   * @return null if loading / analysis phases were successful; a useful error message if loading or
   *     analysis phase errors were encountered and request.keepGoing.
   * @throws InterruptedException if the current thread was interrupted.
   * @throws ViewCreationFailedException if analysis failed for any reason.
   */
  private static AnalysisResult runAnalysisPhase(
      CommandEnvironment env,
      BuildRequest request,
      TargetPatternPhaseValue loadingResult,
      BuildOptions targetOptions,
      RemoteAnalysisCachingDependenciesProvider remoteAnalysisCachingDependenciesProvider)
      throws InterruptedException,
          InvalidConfigurationException,
          RepositoryMappingResolutionException,
          ViewCreationFailedException {
    Stopwatch timer = Stopwatch.createStarted();
    env.getReporter().handle(Event.progress("Loading complete.  Analyzing..."));

    ImmutableSet<Label> explicitTargetPatterns =
        getExplicitTargetPatterns(
            env,
            request.getTargets(),
            request.getKeepGoing(),
            request.getLoadingPhaseThreadCount());

    BuildView view =
        new BuildView(
            env.getDirectories(),
            env.getRuntime().getRuleClassProvider(),
            env.getSkyframeExecutor(),
            env.getRuntime().getCoverageReportActionFactory(request));
    AnalysisResult analysisResult;
    try {
      analysisResult =
          view.update(
              loadingResult,
              targetOptions,
              explicitTargetPatterns,
              request.getAspects(),
              request.getAspectsParameters(),
              request.getViewOptions(),
              request.getKeepGoing(),
              request.getViewOptions().skipIncompatibleExplicitTargets,
              request.getCheckForActionConflicts(),
              env.getQuiescingExecutors(),
              request.getTopLevelArtifactContext(),
              request.reportIncompatibleTargets(),
              env.getReporter(),
              env.getEventBus(),
              env.getRuntime().getBugReporter(),
              /* includeExecutionPhase= */ false,
              /* skymeldAnalysisOverlapPercentage= */ 0,
              /* resourceManager= */ null,
              /* buildResultListener= */ null,
              /* executionSetupCallback= */ null,
              /* buildConfigurationsCreatedCallback= */ null,
              /* buildDriverKeyTestContext= */ null,
              env.getAdditionalConfigurationChangeEvent(),
              remoteAnalysisCachingDependenciesProvider);
    } catch (BuildFailedException | TestExecException | AbruptExitException unexpected) {
      throw new IllegalStateException("Unexpected execution exception type: ", unexpected);
    }

    // TODO(bazel-team): Merge these into one event.
    env.getEventBus()
        .post(
            new AnalysisPhaseCompleteEvent(
                analysisResult.getTargetsToBuild(),
                view.getEvaluatedCounts(),
                view.getEvaluatedActionsCounts(),
                view.getEvaluatedActionsCountsByMnemonic(),
                timer.stop().elapsed(TimeUnit.MILLISECONDS),
                view.getAndClearPkgManagerStatistics(),
                env.getSkyframeExecutor().wasAnalysisCacheInvalidatedAndResetBit()));
    ImmutableSet<BuildConfigurationKey> configurationKeys =
        Stream.concat(
                analysisResult.getTargetsToBuild().stream()
                    .map(ConfiguredTarget::getConfigurationKey)
                    .distinct(),
                analysisResult.getTargetsToTest() == null
                    ? Stream.empty()
                    : analysisResult.getTargetsToTest().stream()
                        .map(ConfiguredTarget::getConfigurationKey)
                        .distinct())
            .filter(Objects::nonNull)
            .distinct()
            .collect(ImmutableSet.toImmutableSet());
    Map<BuildConfigurationKey, BuildConfigurationValue> configurationMap =
        env.getSkyframeExecutor().getConfigurations(env.getReporter(), configurationKeys);
    env.getEventBus()
        .post(
            new TestFilteringCompleteEvent(
                analysisResult.getTargetsToBuild(),
                analysisResult.getTargetsToTest(),
                analysisResult.getTargetsToSkip(),
                configurationMap));
    postTopLevelStatusEvents(env, analysisResult, configurationMap);

    return analysisResult;
  }

  /** Post the appropriate {@link com.google.devtools.build.lib.skyframe.TopLevelStatusEvents}. */
  private static void postTopLevelStatusEvents(
      CommandEnvironment env,
      AnalysisResult analysisResult,
      Map<BuildConfigurationKey, BuildConfigurationValue> configurationMap) {
    for (ConfiguredTarget configuredTarget : analysisResult.getTargetsToBuild()) {
      env.getEventBus().post(TopLevelTargetAnalyzedEvent.create(configuredTarget));
      if (analysisResult.getTargetsToSkip().contains(configuredTarget)) {
        env.getEventBus().post(TopLevelTargetSkippedEvent.create(configuredTarget));
      }

      if (analysisResult.getTargetsToTest() != null
          && analysisResult.getTargetsToTest().contains(configuredTarget)) {
        env.getEventBus()
            .post(
                TestAnalyzedEvent.create(
                    configuredTarget,
                    configurationMap.get(configuredTarget.getConfigurationKey()),
                    /* isSkipped= */ analysisResult.getTargetsToSkip().contains(configuredTarget)));
      }
    }

    for (Entry<AspectKey, ConfiguredAspect> entry : analysisResult.getAspectsMap().entrySet()) {
      env.getEventBus().post(AspectAnalyzedEvent.create(entry.getKey(), entry.getValue()));
    }
  }

  static void reportTargetsWithTests(
      CommandEnvironment env,
      Collection<ConfiguredTarget> targetsToBuild,
      Collection<ConfiguredTarget> targetsToTest) {
    int testCount = targetsToTest.size();
    int targetCount = targetsToBuild.size() - testCount;
    if (targetCount == 0) {
      env.getReporter()
          .handle(
              Event.info(
                  "Found "
                      + testCount
                      + (testCount == 1 ? " test target..." : " test targets...")));
    } else {
      env.getReporter()
          .handle(
              Event.info(
                  "Found "
                      + targetCount
                      + (targetCount == 1 ? " target and " : " targets and ")
                      + testCount
                      + (testCount == 1 ? " test target..." : " test targets...")));
    }
  }

  static void reportTargets(CommandEnvironment env, Collection<ConfiguredTarget> targetsToBuild) {
    int targetCount = targetsToBuild.size();
    env.getReporter()
        .handle(
            Event.info("Found " + targetCount + (targetCount == 1 ? " target..." : " targets...")));
  }

  /**
   * Turns target patterns from the command line into parsed equivalents for single targets.
   *
   * <p>Globbing targets like ":all" and "..." are ignored here and will not be in the returned set.
   *
   * @param env the action's environment.
   * @param requestedTargetPatterns the list of target patterns specified on the command line.
   * @param keepGoing --keep_going command line option.
   * @param loadingPhaseThreads no of threads to be used in execution.
   * @return the set of stringified labels of target patterns that represent single targets. The
   *     stringified labels are in the "unambiguous canonical form".
   * @throws ViewCreationFailedException if a pattern fails to parse for some reason.
   */
  private static ImmutableSet<Label> getExplicitTargetPatterns(
      CommandEnvironment env,
      List<String> requestedTargetPatterns,
      boolean keepGoing,
      int loadingPhaseThreads)
      throws ViewCreationFailedException,
          RepositoryMappingResolutionException,
          InterruptedException {
    ImmutableSet.Builder<Label> explicitTargetPatterns = ImmutableSet.builder();

    // TODO(andreisolo): Don't re-compute these here as they should be already computed inside the
    //  TargetPatternPhaseValue
    RepositoryMapping mainRepoMapping =
        env.getSkyframeExecutor()
            .getMainRepoMapping(keepGoing, loadingPhaseThreads, env.getReporter());
    TargetPattern.Parser parser =
        new Parser(env.getRelativeWorkingDirectory(), RepositoryName.MAIN, mainRepoMapping);

    for (String requestedTargetPattern : requestedTargetPatterns) {
      if (requestedTargetPattern.startsWith("-")) {
        // Excluded patterns are by definition not explicitly requested so we can move on to the
        // next target pattern.
        continue;
      }

      // Parse the pattern. This should always work because this is at least the second time we're
      // doing it. The previous time is in runAnalysisPhase(). Still, if parsing does fail we
      // propagate the exception up.
      TargetPattern parsedPattern;
      try {
        parsedPattern = parser.parse(requestedTargetPattern);
      } catch (TargetParsingException e) {
        throw new ViewCreationFailedException(
            "Failed to parse target pattern even though it was previously parsed successfully",
            e.getDetailedExitCode().getFailureDetail(),
            e);
      }

      if (parsedPattern.getType() == TargetPattern.Type.SINGLE_TARGET) {
        explicitTargetPatterns.add(parsedPattern.getSingleTargetLabel());
      }
    }

    return ImmutableSet.copyOf(explicitTargetPatterns.build());
  }
}
