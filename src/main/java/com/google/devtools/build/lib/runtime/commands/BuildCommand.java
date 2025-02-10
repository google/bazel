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

import com.google.devtools.build.lib.analysis.AnalysisOptions;
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.BuildRequestOptions;
import com.google.devtools.build.lib.buildtool.BuildTool;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.local.LocalExecutionOptions;
import com.google.devtools.build.lib.pkgcache.LoadingOptions;
import com.google.devtools.build.lib.pkgcache.PackageOptions;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.runtime.LoadingPhaseThreadsOption;
import com.google.devtools.build.lib.skyframe.SkyfocusOptions;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.common.options.OptionsParsingResult;
import java.util.List;

/**
 * Handles the 'build' command on the Blaze command line, including targets named by arguments
 * passed to Blaze.
 */
@Command(
    name = "build",
    buildPhase = EXECUTES,
    options = {
      BuildRequestOptions.class,
      ExecutionOptions.class,
      LocalExecutionOptions.class,
      PackageOptions.class,
      AnalysisOptions.class,
      LoadingOptions.class,
      KeepGoingOption.class,
      LoadingPhaseThreadsOption.class,
      BuildEventProtocolOptions.class,
      SkyfocusOptions.class,
      RemoteAnalysisCachingOptions.class,
    },
    usesConfigurationOptions = true,
    shortDescription = "Builds the specified targets.",
    allowResidue = true,
    completion = "label",
    help = "resource:build.txt")
public final class BuildCommand implements BlazeCommand {

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    BlazeRuntime runtime = env.getRuntime();
    List<String> targets;
    try {
      targets = TargetPatternsHelper.readFrom(env, options);
    } catch (TargetPatternsHelper.TargetPatternsHelperException e) {
      env.getReporter().handle(Event.error(e.getMessage()));
      return BlazeCommandResult.failureDetail(e.getFailureDetail());
    }
    if (targets.isEmpty()) {
      env.getReporter()
          .handle(
              Event.warn(
                  "Usage: "
                      + runtime.getProductName()
                      + " build <options> <targets>."
                      + "\nInvoke `"
                      + runtime.getProductName()
                      + " help build` for full description of usage and options."
                      + "\nYour request is correct, but requested an empty set of targets."
                      + " Nothing will be built."));
    }

    BuildRequest request;
    try (SilentCloseable closeable = Profiler.instance().profile("BuildRequest.create")) {

      request =
          BuildRequest.builder()
              .setCommandName(getClass().getAnnotation(Command.class).name())
              .setId(env.getCommandId())
              .setOptions(options)
              .setStartupOptions(runtime.getStartupOptionsProvider())
              .setOutErr(env.getReporter().getOutErr())
              .setTargets(targets)
              .setStartTimeMillis(env.getCommandStartTime())
              .build();
    }
    DetailedExitCode detailedExitCode =
        new BuildTool(env).processRequest(request, null, options).getDetailedExitCode();
    return BlazeCommandResult.detailedExitCode(detailedExitCode);
  }
}
