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
package com.google.devtools.build.lib.runtime.commands;

import static com.google.devtools.build.lib.runtime.Command.BuildPhase.ANALYZES;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.config.CoreOptions.IncludeConfigFragmentsEnum;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.BuildTool;
import com.google.devtools.build.lib.buildtool.CqueryProcessor;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.cmdline.TargetPattern.Parser;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.query2.cquery.ConfiguredTargetQueryEnvironment;
import com.google.devtools.build.lib.query2.cquery.CqueryOptions;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.engine.QueryParser;
import com.google.devtools.build.lib.query2.engine.QuerySyntaxException;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.runtime.LoadingPhaseThreadsOption;
import com.google.devtools.build.lib.server.FailureDetails.ConfigurableQuery;
import com.google.devtools.build.lib.server.FailureDetails.ConfigurableQuery.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue.RepositoryMappingResolutionException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.InterruptedFailureDetails;
import com.google.devtools.common.options.OptionPriority.PriorityCategory;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Handles the 'cquery' command on the Blaze command line. */
@Command(
    name = "cquery",
    buildPhase = ANALYZES,
    // We inherit from TestCommand so that we pick up changes like `test --test_arg=foo` in .bazelrc
    // files.
    // Without doing this, there is no easy way to use the output of cquery to determine whether a
    // test has changed between two invocations, because the testrunner action is not easily
    // introspectable.
    inheritsOptionsFrom = {TestCommand.class},
    options = {CqueryOptions.class},
    usesConfigurationOptions = true,
    shortDescription = "Loads, analyzes, and queries the specified targets w/ configurations.",
    allowResidue = true,
    binaryStdOut = true,
    completion = "label",
    help = "resource:cquery.txt")
public final class CqueryCommand implements BlazeCommand {

  @Override
  public void editOptions(OptionsParser optionsParser) {
    CqueryOptions cqueryOptions = optionsParser.getOptions(CqueryOptions.class);
    try {
      if (!cqueryOptions.transitions.equals(CqueryOptions.Transitions.NONE)) {
        optionsParser.parse(
            PriorityCategory.COMPUTED_DEFAULT,
            "Option required by setting the --transitions flag",
            ImmutableList.of("--output=transitions"));
      }
      optionsParser.parse(
          PriorityCategory.COMPUTED_DEFAULT,
          "Options required by cquery",
          ImmutableList.of("--nobuild"));
      optionsParser.parse(
          PriorityCategory.COMPUTED_DEFAULT,
          "cquery should include 'tags = [\"manual\"]' targets by default",
          ImmutableList.of("--build_manual_tests"));
      optionsParser.parse(
          PriorityCategory.SOFTWARE_REQUIREMENT,
          // https://github.com/bazelbuild/bazel/issues/11078
          "cquery should not exclude test_suite rules",
          ImmutableList.of("--noexpand_test_suites"));
      if (cqueryOptions.showRequiredConfigFragments != IncludeConfigFragmentsEnum.OFF) {
        optionsParser.parse(
            PriorityCategory.COMPUTED_DEFAULT,
            "Options required by cquery's --show_config_fragments flag",
            ImmutableList.of(
                "--include_config_fragments_provider="
                    + cqueryOptions.showRequiredConfigFragments));
      }
      optionsParser.parse(
          PriorityCategory.SOFTWARE_REQUIREMENT,
          "cquery should not exclude tests",
          ImmutableList.of("--nobuild_tests_only"));
    } catch (OptionsParsingException e) {
      throw new IllegalStateException("Cquery's known options failed to parse", e);
    }
  }

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    TargetPattern.Parser mainRepoTargetParser;
    try {
      RepositoryMapping repoMapping =
          env.getSkyframeExecutor()
              .getMainRepoMapping(
                  env.getOptions().getOptions(KeepGoingOption.class).keepGoing,
                  env.getOptions().getOptions(LoadingPhaseThreadsOption.class).threads,
                  env.getReporter());
      mainRepoTargetParser =
          new Parser(env.getRelativeWorkingDirectory(), RepositoryName.MAIN, repoMapping);
    } catch (RepositoryMappingResolutionException e) {
      env.getReporter().handle(Event.error(e.getMessage()));
      return BlazeCommandResult.detailedExitCode(e.getDetailedExitCode());
    } catch (InterruptedException e) {
      String errorMessage = "Fetch interrupted: " + e.getMessage();
      env.getReporter().handle(Event.error(errorMessage));
      return BlazeCommandResult.detailedExitCode(
          InterruptedFailureDetails.detailedExitCode(errorMessage));
    }

    String query = null;
    try {
      query =
          QueryOptionHelper.readQuery(
              options.getOptions(CqueryOptions.class), options, env, /* allowEmptyQuery= */ false);
    } catch (QueryException e) {
      return BlazeCommandResult.failureDetail(e.getFailureDetail());
    }

    HashMap<String, QueryFunction> functions = new HashMap<>();
    for (QueryFunction queryFunction : ConfiguredTargetQueryEnvironment.FUNCTIONS) {
      functions.put(queryFunction.getName(), queryFunction);
    }
    for (QueryFunction queryFunction : env.getRuntime().getQueryFunctions()) {
      functions.put(queryFunction.getName(), queryFunction);
    }
    QueryExpression expr;
    try {
      expr = QueryParser.parse(query, functions);
    } catch (QuerySyntaxException e) {
      String message =
          String.format(
              "Error while parsing '%s': %s", QueryExpression.truncate(query), e.getMessage());
      env.getReporter().handle(Event.error(message));
      return createFailureResult(message, Code.EXPRESSION_PARSE_FAILURE);
    }

    List<String> topLevelTargets = options.getOptions(CqueryOptions.class).universeScope;
    Set<String> targetPatternSet = new LinkedHashSet<>();
    if (topLevelTargets.isEmpty()) {
      expr.collectTargetPatterns(targetPatternSet);
      topLevelTargets = new ArrayList<>(targetPatternSet);
    }
    BlazeRuntime runtime = env.getRuntime();

    BuildRequest request =
        BuildRequest.builder()
            .setCommandName(getClass().getAnnotation(Command.class).name())
            .setId(env.getCommandId())
            .setOptions(options)
            .setStartupOptions(runtime.getStartupOptionsProvider())
            .setOutErr(env.getReporter().getOutErr())
            .setTargets(topLevelTargets)
            .setStartTimeMillis(env.getCommandStartTime())
            .setCheckforActionConflicts(false)
            .setReportIncompatibleTargets(false)
            .build();
    DetailedExitCode detailedExitCode =
        new BuildTool(env, new CqueryProcessor(expr, mainRepoTargetParser))
            .processRequest(request, null, options)
            .getDetailedExitCode();
    return BlazeCommandResult.detailedExitCode(detailedExitCode);
  }

  private static BlazeCommandResult createFailureResult(String message, Code detailedCode) {
    return BlazeCommandResult.failureDetail(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setConfigurableQuery(ConfigurableQuery.newBuilder().setCode(detailedCode))
            .build());
  }
}
