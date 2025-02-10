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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionException;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.query2.PostAnalysisQueryEnvironment;
import com.google.devtools.build.lib.query2.PostAnalysisQueryEnvironment.TopLevelConfigurations;
import com.google.devtools.build.lib.query2.aquery.ActionGraphProtoOutputFormatterCallback;
import com.google.devtools.build.lib.query2.aquery.ActionGraphQueryEnvironment;
import com.google.devtools.build.lib.query2.aquery.AqueryActionFilter;
import com.google.devtools.build.lib.query2.aquery.AqueryOptions;
import com.google.devtools.build.lib.query2.engine.ActionFilterFunction;
import com.google.devtools.build.lib.query2.engine.FunctionExpression;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.ArgumentType;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.QueryRuntimeHelper;
import com.google.devtools.build.lib.runtime.QueryRuntimeHelper.QueryRuntimeHelperException;
import com.google.devtools.build.lib.server.FailureDetails.ActionQuery;
import com.google.devtools.build.lib.server.FailureDetails.ActionQuery.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.SequencedSkyframeExecutor;
import com.google.devtools.build.lib.skyframe.actiongraph.v2.ActionGraphDump;
import com.google.devtools.build.lib.skyframe.actiongraph.v2.AqueryConsumingOutputHandler;
import com.google.devtools.build.lib.skyframe.actiongraph.v2.AqueryOutputHandler;
import com.google.devtools.build.lib.skyframe.actiongraph.v2.AqueryOutputHandler.OutputType;
import com.google.devtools.build.lib.skyframe.actiongraph.v2.InvalidAqueryOutputFormatException;
import com.google.devtools.build.skyframe.WalkableGraph;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkSemantics;

/** Performs {@code aquery} processing. */
public final class AqueryProcessor extends PostAnalysisQueryProcessor<ConfiguredTargetValue> {
  private final AqueryActionFilter actionFilters;

  public AqueryProcessor(
      @Nullable QueryExpression queryExpression, TargetPattern.Parser mainRepoTargetParser)
      throws AqueryActionFilterException {
    super(queryExpression, mainRepoTargetParser);
    actionFilters = buildActionFilters(queryExpression);
  }

  @Override
  protected AqueryOptions getQueryOptions(CommandEnvironment env) {
    return env.getOptions().getOptions(AqueryOptions.class);
  }

  /** Outputs the current action graph from Skyframe. */
  public BlazeCommandResult dumpActionGraphFromSkyframe(CommandEnvironment env) {
    AqueryOptions aqueryOptions = getQueryOptions(env);
    try (QueryRuntimeHelper queryRuntimeHelper =
        env.getRuntime().getQueryRuntimeHelperFactory().create(env, aqueryOptions)) {

      PrintStream printStream =
          queryRuntimeHelper.getOutputStreamForQueryOutput() == null
              ? null
              : new PrintStream(queryRuntimeHelper.getOutputStreamForQueryOutput());

      try (AqueryOutputHandler aqueryOutputHandler =
          ActionGraphProtoOutputFormatterCallback.constructAqueryOutputHandler(
              OutputType.fromString(aqueryOptions.outputFormat),
              queryRuntimeHelper.getOutputStreamForQueryOutput(),
              printStream)) {
        ActionGraphDump actionGraphDump =
            new ActionGraphDump(
                aqueryOptions.includeCommandline,
                aqueryOptions.includeArtifacts,
                aqueryOptions.includePrunedInputs,
                actionFilters,
                aqueryOptions.includeParamFiles,
                aqueryOptions.includeFileWriteContents,
                aqueryOutputHandler,
                env.getReporter());
        dumpActionGraph(env, aqueryOutputHandler, actionGraphDump);

      } catch (InvalidAqueryOutputFormatException e) {
        String message =
            "--skyframe_state must be used with --output=proto|textproto|jsonproto. "
                + e.getMessage();
        env.getReporter().handle(Event.error(message));
        return getFailureResult(message, Code.SKYFRAME_STATE_PREREQ_UNMET);
      }
      return BlazeCommandResult.success();
    } catch (CommandLineExpansionException e) {
      String message = "Error while parsing command: " + e.getMessage();
      env.getReporter().handle(Event.error(message));
      return getFailureResult(message, Code.COMMAND_LINE_EXPANSION_FAILURE);
    } catch (TemplateExpansionException e) {
      String message = "Error while expanding template: " + e.getMessage();
      env.getReporter().handle(Event.error(message));
      return getFailureResult(message, Code.TEMPLATE_EXPANSION_FAILURE);
    } catch (IOException e) {
      String message = "Error while emitting output: " + e.getMessage();
      env.getReporter().handle(Event.error(message));
      return getFailureResult(message, Code.OUTPUT_FAILURE);
    } catch (QueryRuntimeHelperException e) {
      env.getReporter().handle(Event.error(e.getMessage()));
      return BlazeCommandResult.failureDetail(e.getFailureDetail());
    }
  }

  public static void dumpActionGraph(
      CommandEnvironment env,
      AqueryOutputHandler aqueryOutputHandler,
      ActionGraphDump actionGraphDump)
      throws CommandLineExpansionException, TemplateExpansionException, IOException {
    if (aqueryOutputHandler instanceof AqueryConsumingOutputHandler) {
      ((SequencedSkyframeExecutor) env.getSkyframeExecutor())
          .dumpSkyframeStateInParallel(
              actionGraphDump, (AqueryConsumingOutputHandler) aqueryOutputHandler);
    } else {
      ((SequencedSkyframeExecutor) env.getSkyframeExecutor()).dumpSkyframeState(actionGraphDump);
    }
  }

  @Override
  protected PostAnalysisQueryEnvironment<ConfiguredTargetValue> getQueryEnvironment(
      BuildRequest request,
      CommandEnvironment env,
      TopLevelConfigurations topLevelConfigurations,
      ImmutableMap<String, BuildConfigurationValue> transitiveConfigurations,
      WalkableGraph walkableGraph) {
    ImmutableList<QueryFunction> extraFunctions =
        new ImmutableList.Builder<QueryFunction>()
            .addAll(ActionGraphQueryEnvironment.AQUERY_FUNCTIONS)
            .addAll(env.getRuntime().getQueryFunctions())
            .build();
    AqueryOptions aqueryOptions = request.getOptions(AqueryOptions.class);

    StarlarkSemantics starlarkSemantics =
        env.getSkyframeExecutor()
            .getEffectiveStarlarkSemantics(env.getOptions().getOptions(BuildLanguageOptions.class));
    ActionGraphQueryEnvironment queryEnvironment =
        new ActionGraphQueryEnvironment(
            request.getKeepGoing(),
            env.getReporter(),
            extraFunctions,
            topLevelConfigurations,
            transitiveConfigurations,
            mainRepoTargetParser,
            env.getPackageManager().getPackagePath(),
            () -> walkableGraph,
            aqueryOptions,
            request
                .getOptions(AqueryOptions.class)
                .getLabelPrinter(starlarkSemantics, mainRepoTargetParser.getRepoMapping()));
    queryEnvironment.setActionFilters(actionFilters);

    return queryEnvironment;
  }

  /**
   * Return the action filters in the form { inputs: <pattern>, outputs: <pattern>, ... }
   *
   * @param queryExpression The query expression from aquery command
   * @return the action filters
   * @throws AqueryActionFilterException if an aquery filter function is preceded by any other
   *     function types
   */
  private AqueryActionFilter buildActionFilters(@Nullable QueryExpression queryExpression)
      throws AqueryActionFilterException {
    AqueryActionFilter.Builder actionFiltersBuilder = AqueryActionFilter.builder();

    if (!(queryExpression instanceof FunctionExpression)) {
      return actionFiltersBuilder.build();
    }

    Optional<FunctionExpression> functionExpressionOptional =
        Optional.of((FunctionExpression) queryExpression);

    FunctionExpression nonAqueryFilterFunctionExpression = null;

    // Unwrap the function layers
    // Validate that aquery filter functions (inputs, outputs, mnemonics) are not preceded
    // by any other function types
    while (functionExpressionOptional.isPresent()) {
      FunctionExpression functionExpression = functionExpressionOptional.get();

      if (functionExpression.getFunction() instanceof ActionFilterFunction actionFilterFunction) {
        if (nonAqueryFilterFunctionExpression != null) {
          throw new AqueryActionFilterException(
              "aquery filter functions (inputs, outputs, mnemonic) produce actions, and therefore "
                  + "can't be the input of other function types: "
                  + nonAqueryFilterFunctionExpression.getFunction().getName());
        }

        String patternString = functionExpression.getArgs().get(0).getWord();
        try {
          actionFiltersBuilder.put(actionFilterFunction.getName(), Pattern.compile(patternString));
        } catch (PatternSyntaxException e) {
          throw new AqueryActionFilterException("Wrong query syntax: " + e.getMessage());
        }
      } else {
        nonAqueryFilterFunctionExpression = functionExpression;
      }

      functionExpressionOptional = getNextFunctionExpression(functionExpression);
    }

    return actionFiltersBuilder.build();
  }

  /**
   * Unwrap input {@code functionExpression} to get the next FunctionExpression in the query
   *
   * @param functionExpression the current function expression
   * @return the Optional of the next FunctionExpression in the query
   */
  private Optional<FunctionExpression> getNextFunctionExpression(
      FunctionExpression functionExpression) {
    for (Argument arg : functionExpression.getArgs()) {
      if (arg.getType() == ArgumentType.EXPRESSION
          && arg.getExpression() instanceof FunctionExpression) {
        return Optional.of((FunctionExpression) arg.getExpression());
      }
    }
    return Optional.empty();
  }

  /** Custom exception class for aquery filtering */
  public static class AqueryActionFilterException extends Exception {
    AqueryActionFilterException(String message) {
      super(message);
    }
  }

  private static BlazeCommandResult getFailureResult(String message, Code detailedCode) {
    return BlazeCommandResult.failureDetail(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setActionQuery(ActionQuery.newBuilder().setCode(detailedCode))
            .build());
  }
}
