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

package com.google.devtools.build.lib.bazel.commands;

import static com.google.common.primitives.Booleans.countTrue;
import static com.google.devtools.build.lib.runtime.Command.BuildPhase.ANALYZES;
import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.NoBuildEvent;
import com.google.devtools.build.lib.analysis.NoBuildRequestFinishedEvent;
import com.google.devtools.build.lib.bazel.bzlmod.BazelFetchAllValue;
import com.google.devtools.build.lib.bazel.commands.RepositoryFetcher.RepositoryFetcherException;
import com.google.devtools.build.lib.bazel.commands.TargetFetcher.TargetFetcherException;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.pkgcache.PackageOptions;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.runtime.LoadingPhaseThreadsOption;
import com.google.devtools.build.lib.runtime.commands.TargetPatternsHelper;
import com.google.devtools.build.lib.runtime.commands.TestCommand;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.FetchCommand.Code;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue.RepositoryMappingResolutionException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.InterruptedFailureDetails;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingResult;
import java.util.List;
import javax.annotation.Nullable;

/** Fetches external repositories. Which is so fetch. */
@Command(
    name = FetchCommand.NAME,
    buildPhase = ANALYZES,
    inheritsOptionsFrom = {TestCommand.class},
    options = {
      FetchOptions.class,
      PackageOptions.class,
      KeepGoingOption.class,
      LoadingPhaseThreadsOption.class
    },
    usesConfigurationOptions = true,
    allowResidue = true,
    shortDescription = "Fetches external repositories that are prerequisites to the targets.",
    help = "resource:fetch.txt",
    completion = "label")
public final class FetchCommand implements BlazeCommand {

  public static final String NAME = "fetch";

  @Override
  public void editOptions(OptionsParser optionsParser) {
    TargetFetcher.injectNoBuildOption(optionsParser);
  }

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    BlazeCommandResult invalidResult = validateOptions(env, options);
    if (invalidResult != null) {
      return invalidResult;
    }

    env.getEventBus()
        .post(
            new NoBuildEvent(
                env.getCommandName(),
                env.getCommandStartTime(),
                /* separateFinishedEvent= */ true,
                /* showProgress= */ true,
                env.getCommandId().toString()));

    FetchOptions fetchOptions = options.getOptions(FetchOptions.class);
    if (fetchOptions.force) {
      // Using commandId as the value -instead of true/false- to make sure to invalidate skyframe
      // and to actually force fetch each time
      env.getSkyframeExecutor()
          .injectExtraPrecomputedValues(
              ImmutableList.of(
                  PrecomputedValue.injected(
                      fetchOptions.configure
                          ? RepositoryDelegatorFunction.FORCE_FETCH_CONFIGURE
                          : RepositoryDelegatorFunction.FORCE_FETCH,
                      env.getCommandId().toString())));
    }

    BlazeCommandResult result;
    LoadingPhaseThreadsOption threadsOption = options.getOptions(LoadingPhaseThreadsOption.class);
    List<String> targets;
    try {
      targets = TargetPatternsHelper.readFrom(env, options);
    } catch (TargetPatternsHelper.TargetPatternsHelperException e) {
      env.getReporter().handle(Event.error(e.getMessage()));
      return BlazeCommandResult.failureDetail(e.getFailureDetail());
    }
    try {
      if (!targets.isEmpty()) {
        if (!fetchOptions.repos.isEmpty()) {
          return createFailedBlazeCommandResult(
              env.getReporter(), "Target patterns and --repo cannot both be specified");
        }
        result = fetchTarget(env, options, targets);
      } else if (!fetchOptions.repos.isEmpty()) {
        result = fetchRepos(env, threadsOption, fetchOptions.repos);
      } else { // --all or just 'fetch' (equivalent) or --configure
        result = fetchAll(env, threadsOption, fetchOptions.configure);
      }
    } catch (InterruptedException e) {
      return createFailedBlazeCommandResult(
          env.getReporter(), "Fetch interrupted: " + e.getMessage());
    }

    env.getEventBus()
        .post(
            new NoBuildRequestFinishedEvent(
                result.getExitCode(), env.getRuntime().getClock().currentTimeMillis()));
    return result;
  }

  @Nullable
  private BlazeCommandResult validateOptions(CommandEnvironment env, OptionsParsingResult options) {
    PackageOptions pkgOptions = options.getOptions(PackageOptions.class);
    if (!pkgOptions.fetch) {
      return createFailedBlazeCommandResult(
          env.getReporter(), Code.OPTIONS_INVALID, "You cannot run fetch with --nofetch");
    }
    FetchOptions fetchOptions = options.getOptions(FetchOptions.class);
    int optionsCount =
        countTrue(
            fetchOptions.all,
            fetchOptions.configure,
            !fetchOptions.repos.isEmpty(),
            !options.getResidue().isEmpty());
    if (optionsCount > 1) {
      return createFailedBlazeCommandResult(
          env.getReporter(),
          Code.OPTIONS_INVALID,
          "Only one fetch option can be provided for fetch command");
    }
    return null;
  }

  private BlazeCommandResult fetchAll(
      CommandEnvironment env,
      LoadingPhaseThreadsOption threadsOption,
      boolean configureEnabled)
      throws InterruptedException {
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setParallelism(threadsOption.threads)
            .setEventHandler(env.getReporter())
            .build();

    EvaluationResult<SkyValue> evaluationResult =
        env.getSkyframeExecutor()
            .prepareAndGet(
                ImmutableSet.of(BazelFetchAllValue.key(configureEnabled)), evaluationContext);
    if (evaluationResult.hasError()) {
      Exception e = evaluationResult.getError().getException();
      return createFailedBlazeCommandResult(
          env.getReporter(),
          e != null ? e.getMessage() : "Unexpected error during fetching all external deps.");
    }

    env.getReporter().handle(Event.info("All external dependencies fetched successfully."));
    return BlazeCommandResult.success();
  }

  private BlazeCommandResult fetchRepos(
      CommandEnvironment env, LoadingPhaseThreadsOption threadsOption, List<String> repos)
      throws InterruptedException {
    ImmutableMap<RepositoryName, RepositoryDirectoryValue> repositoryNamesAndValues;
    try {
      repositoryNamesAndValues = RepositoryFetcher.fetchRepos(repos, env, threadsOption);
    } catch (RepositoryMappingResolutionException e) {
      return createFailedBlazeCommandResult(
          env.getReporter(), "Invalid repo name: " + e.getMessage(), e.getDetailedExitCode());
    } catch (RepositoryFetcherException e) {
      return createFailedBlazeCommandResult(env.getReporter(), e.getMessage());
    }

    String notFoundRepos =
        repositoryNamesAndValues.values().stream()
            .filter(value -> !value.repositoryExists())
            .map(value -> value.getErrorMsg())
            .collect(joining("; "));
    if (!notFoundRepos.isEmpty()) {
      return createFailedBlazeCommandResult(
          env.getReporter(), "Fetching some repos failed with errors: " + notFoundRepos);
    }
    env.getReporter().handle(Event.info("All requested repos fetched successfully."));
    return BlazeCommandResult.success();
  }

  private BlazeCommandResult fetchTarget(
      CommandEnvironment env, OptionsParsingResult options, List<String> targets) {
    try {
      var unused = TargetFetcher.fetchTargets(env, options, targets);
    } catch (TargetFetcherException e) {
      return createFailedBlazeCommandResult(
          env.getReporter(), Code.QUERY_EVALUATION_ERROR, e.getMessage());
    }
    env.getReporter()
        .handle(
            Event.info(
                "All external dependencies for the requested targets fetched successfully."));
    return BlazeCommandResult.success();
  }

  private static BlazeCommandResult createFailedBlazeCommandResult(
      Reporter reporter, Code fetchCommandCode, String message) {
    return createFailedBlazeCommandResult(
        reporter,
        message,
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setFetchCommand(
                    FailureDetails.FetchCommand.newBuilder().setCode(fetchCommandCode).build())
                .build()));
  }

  private static BlazeCommandResult createFailedBlazeCommandResult(
      Reporter reporter, String errorMessage) {
    return createFailedBlazeCommandResult(
        reporter, errorMessage, InterruptedFailureDetails.detailedExitCode(errorMessage));
  }

  private static BlazeCommandResult createFailedBlazeCommandResult(
      Reporter reporter, String message, DetailedExitCode exitCode) {
    reporter.handle(Event.error(message));
    return BlazeCommandResult.detailedExitCode(exitCode);
  }
}
