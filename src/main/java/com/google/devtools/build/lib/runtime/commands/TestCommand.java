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

package com.google.devtools.build.lib.runtime.commands;

import static com.google.devtools.build.lib.runtime.Command.BuildPhase.EXECUTES;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.AspectCollection;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.BuildRequestOptions;
import com.google.devtools.build.lib.buildtool.BuildResult;
import com.google.devtools.build.lib.buildtool.BuildTool;
import com.google.devtools.build.lib.buildtool.InstrumentationFilterSupport;
import com.google.devtools.build.lib.buildtool.PathPrettyPrinter;
import com.google.devtools.build.lib.buildtool.buildevent.TestingCompleteEvent;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.ExecutionOptions.TestOutputFormat;
import com.google.devtools.build.lib.runtime.AggregatingTestListener;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.TerminalTestResultNotifier;
import com.google.devtools.build.lib.runtime.TestResultNotifier;
import com.google.devtools.build.lib.runtime.TestSummaryOptions;
import com.google.devtools.build.lib.runtime.TestSummaryPrinter.TestLogPathFormatter;
import com.google.devtools.build.lib.runtime.UiOptions;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.TestCommand.Code;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.InterruptedFailureDetails;
import com.google.devtools.build.lib.util.io.AnsiTerminalPrinter;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.OptionPriority.PriorityCategory;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;
import java.util.Collection;
import java.util.List;

/** Handles the 'test' command on the Blaze command line. */
@Command(
    name = "test",
    buildPhase = EXECUTES,
    inheritsOptionsFrom = {BuildCommand.class},
    options = {TestSummaryOptions.class},
    shortDescription = "Builds and runs the specified test targets.",
    help = "resource:test.txt",
    completion = "label-test",
    allowResidue = true)
public class TestCommand implements BlazeCommand {
  /** Returns the name of the command to ask the project file for. */
  // TODO(hdm): move into BlazeRuntime?  It feels odd to duplicate the annotation here.
  protected String commandName() {
    return "test";
  }

  @Override
  public void editOptions(OptionsParser optionsParser) {
    TestOutputFormat testOutput = optionsParser.getOptions(ExecutionOptions.class).testOutput;
    try {
      if (testOutput == ExecutionOptions.TestOutputFormat.STREAMED) {
        optionsParser.parse(
            PriorityCategory.SOFTWARE_REQUIREMENT,
            "streamed output requires locally run tests, without sharding",
            ImmutableList.of("--test_sharding_strategy=disabled", "--test_strategy=exclusive"));
      }
    } catch (OptionsParsingException e) {
      throw new IllegalStateException("Known options failed to parse", e);
    }
  }

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    TestOutputFormat testOutput = options.getOptions(ExecutionOptions.class).testOutput;
    if (testOutput == ExecutionOptions.TestOutputFormat.STREAMED) {
      env.getReporter().handle(Event.warn(
          "Streamed test output requested. All tests will be run without sharding, "
          + "one at a time"));
    }

    AnsiTerminalPrinter printer =
        new AnsiTerminalPrinter(
            env.getReporter().getOutErr().getOutputStream(),
            options.getOptions(UiOptions.class).useColor());

    // Initialize test handler.
    AggregatingTestListener testListener =
        new AggregatingTestListener(
            options.getOptions(TestSummaryOptions.class),
            options.getOptions(ExecutionOptions.class),
            env.getEventBus());

    env.getEventBus().register(testListener);
    return doTest(env, options, testListener, printer);
  }

  private BlazeCommandResult doTest(
      CommandEnvironment env,
      OptionsParsingResult options,
      AggregatingTestListener testListener,
      AnsiTerminalPrinter printer) {
    BlazeRuntime runtime = env.getRuntime();
    // Run simultaneous build and test.
    List<String> targets;
    try {
      targets = TargetPatternsHelper.readFrom(env, options);
    } catch (TargetPatternsHelper.TargetPatternsHelperException e) {
      env.getReporter().handle(Event.error(e.getMessage()));
      return BlazeCommandResult.failureDetail(e.getFailureDetail());
    }
    RepositoryMapping mainRepoMapping;
    try {
      mainRepoMapping = env.getSkyframeExecutor().getMainRepoMapping(env.getReporter());
    } catch (InterruptedException e) {
      String message = "test command interrupted";
      env.getReporter().handle(Event.error(message));
      return BlazeCommandResult.detailedExitCode(
          InterruptedFailureDetails.detailedExitCode(message));
    } catch (RepositoryMappingValue.RepositoryMappingResolutionException e) {
      env.getReporter().handle(Event.error(e.getMessage()));
      return BlazeCommandResult.detailedExitCode(e.getDetailedExitCode());
    }

    BuildRequest.Builder builder =
        BuildRequest.builder()
            .setCommandName(getClass().getAnnotation(Command.class).name())
            .setId(env.getCommandId())
            .setOptions(options)
            .setStartupOptions(runtime.getStartupOptionsProvider())
            .setOutErr(env.getReporter().getOutErr())
            .setTargets(targets)
            .setStartTimeMillis(env.getCommandStartTime())
            .setRunTests(true);
    if (options.getOptions(CoreOptions.class).collectCodeCoverage
        && !options.containsExplicitOption(
            InstrumentationFilterSupport.INSTRUMENTATION_FILTER_FLAG)) {
      builder.setNeedsInstrumentationFilter(true);
    }
    BuildRequest request = builder.build();

    BuildResult buildResult = new BuildTool(env).processRequest(request, null, options);

    Collection<ConfiguredTarget> testTargets = buildResult.getTestTargets();
    // TODO(bazel-team): don't handle isEmpty here or fix up a bunch of tests
    if (buildResult.getSuccessfulTargets() == null) {
      // This can happen if there were errors in the target parsing or loading phase
      // (original exitcode=BUILD_FAILURE) or if there weren't but --noanalyze was given
      // (original exitcode=SUCCESS).
      String message = "Couldn't start the build. Unable to run tests";
      env.getReporter().handle(Event.error(message));
      DetailedExitCode detailedExitCode =
          buildResult.getSuccess()
              ? DetailedExitCode.of(
                  FailureDetail.newBuilder()
                      .setMessage(message)
                      .setTestCommand(
                          FailureDetails.TestCommand.newBuilder().setCode(Code.TEST_WITH_NOANALYZE))
                      .build())
              : buildResult.getDetailedExitCode();
      env.getEventBus()
          .post(
              new TestingCompleteEvent(detailedExitCode.getExitCode(), buildResult.getStopTime()));
      return BlazeCommandResult.detailedExitCode(detailedExitCode);
    }
    // TODO(bazel-team): the check above shadows NO_TESTS_FOUND, but switching the conditions breaks
    // more tests
    if (testTargets.isEmpty()) {
      String message = "No test targets were found, yet testing was requested";
      env.getReporter().handle(Event.error(null, message));

      DetailedExitCode detailedExitCode =
          buildResult.getSuccess()
              ? DetailedExitCode.of(
                  FailureDetail.newBuilder()
                      .setMessage(message)
                      .setTestCommand(
                          FailureDetails.TestCommand.newBuilder().setCode(Code.NO_TEST_TARGETS))
                      .build())
              : buildResult.getDetailedExitCode();
      env.getEventBus()
          .post(new NoTestsFound(detailedExitCode.getExitCode(), buildResult.getStopTime()));
      return BlazeCommandResult.detailedExitCode(detailedExitCode);
    }

    DetailedExitCode testResults =
        analyzeTestResults(
            request, buildResult, testListener, options, env, printer, mainRepoMapping);

    if (testResults.isSuccess() && !buildResult.getSuccess()) {
      // If all tests run successfully, test summary should include warning if
      // there were build errors not associated with the test targets.
      printer.printLn(
          AnsiTerminalPrinter.Mode.ERROR
              + "All tests passed but there were other errors during the build.\n"
              + AnsiTerminalPrinter.Mode.DEFAULT);
    }

    DetailedExitCode detailedExitCode =
        DetailedExitCode.DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
            buildResult.getDetailedExitCode(), testResults);
    env.getEventBus()
        .post(new TestingCompleteEvent(detailedExitCode.getExitCode(), buildResult.getStopTime()));
    return BlazeCommandResult.detailedExitCode(detailedExitCode);
  }

  /**
   * Analyzes test results and prints summary information. Returns a {@link DetailedExitCode}
   * summarizing those test results.
   */
  private static DetailedExitCode analyzeTestResults(
      BuildRequest buildRequest,
      BuildResult buildResult,
      AggregatingTestListener listener,
      OptionsParsingResult options,
      CommandEnvironment env,
      AnsiTerminalPrinter printer,
      RepositoryMapping mainRepoMapping) {
    ImmutableSet<ConfiguredTargetKey> validatedTargets;
    if (buildRequest.useValidationAspect()) {
      validatedTargets =
          buildResult.getSuccessfulAspects().stream()
              .filter(key -> AspectCollection.VALIDATION_ASPECT_NAME.equals(key.getAspectName()))
              .map(AspectKey::getBaseConfiguredTargetKey)
              .collect(ImmutableSet.toImmutableSet());
    } else {
      validatedTargets = null;
    }

    TestResultNotifier notifier =
        new TerminalTestResultNotifier(
            printer,
            makeTestLogPathFormatter(buildResult.getConvenienceSymlinks(), options, env),
            options,
            mainRepoMapping);
    return listener.differentialAnalyzeAndReport(
        buildResult.getTestTargets(), buildResult.getSkippedTargets(), validatedTargets, notifier);
  }

  private static TestLogPathFormatter makeTestLogPathFormatter(
      ImmutableMap<PathFragment, PathFragment> convenienceSymlinks,
      OptionsParsingResult options,
      CommandEnvironment env) {
    BlazeRuntime runtime = env.getRuntime();
    TestSummaryOptions summaryOptions = options.getOptions(TestSummaryOptions.class);
    if (!summaryOptions.printRelativeTestLogPaths) {
      return Path::getPathString;
    }
    String productName = runtime.getProductName();
    BuildRequestOptions requestOptions = env.getOptions().getOptions(BuildRequestOptions.class);
    PathPrettyPrinter pathPrettyPrinter =
        new PathPrettyPrinter(requestOptions.getSymlinkPrefix(productName), convenienceSymlinks);
    return path -> pathPrettyPrinter.getPrettyPath(path.asFragment()).getPathString();
  }
}
