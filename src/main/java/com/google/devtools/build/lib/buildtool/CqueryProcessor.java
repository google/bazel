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
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.query2.PostAnalysisQueryEnvironment.TopLevelConfigurations;
import com.google.devtools.build.lib.query2.common.CommonQueryOptions;
import com.google.devtools.build.lib.query2.common.CqueryNode;
import com.google.devtools.build.lib.query2.cquery.ConfiguredTargetQueryEnvironment;
import com.google.devtools.build.lib.query2.cquery.CqueryOptions;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.skyframe.WalkableGraph;
import net.starlark.java.eval.StarlarkSemantics;

/** Performs {@code cquery} processing. */
public final class CqueryProcessor extends PostAnalysisQueryProcessor<CqueryNode> {

  public CqueryProcessor(
      QueryExpression queryExpression, TargetPattern.Parser mainRepoTargetParser) {
    super(queryExpression, mainRepoTargetParser);
  }

  @Override
  protected CommonQueryOptions getQueryOptions(CommandEnvironment env) {
    return env.getOptions().getOptions(CqueryOptions.class);
  }

  @Override
  protected ConfiguredTargetQueryEnvironment getQueryEnvironment(
      BuildRequest request,
      CommandEnvironment env,
      TopLevelConfigurations configurations,
      ImmutableMap<String, BuildConfigurationValue> transitiveConfigurations,
      WalkableGraph walkableGraph)
      throws InterruptedException {
    ImmutableList<QueryFunction> extraFunctions =
        new ImmutableList.Builder<QueryFunction>()
            .addAll(ConfiguredTargetQueryEnvironment.CQUERY_FUNCTIONS)
            .addAll(env.getRuntime().getQueryFunctions())
            .build();
    CqueryOptions cqueryOptions = request.getOptions(CqueryOptions.class);
    StarlarkSemantics starlarkSemantics =
        env.getSkyframeExecutor()
            .getEffectiveStarlarkSemantics(env.getOptions().getOptions(BuildLanguageOptions.class));
    return new ConfiguredTargetQueryEnvironment(
        request.getKeepGoing(),
        env.getReporter(),
        extraFunctions,
        configurations,
        transitiveConfigurations,
        mainRepoTargetParser,
        env.getPackageManager().getPackagePath(),
        () -> walkableGraph,
        cqueryOptions,
        request.getTopLevelArtifactContext(),
        request
            .getOptions(CqueryOptions.class)
            .getLabelPrinter(starlarkSemantics, mainRepoTargetParser.getRepoMapping()));
  }
}
