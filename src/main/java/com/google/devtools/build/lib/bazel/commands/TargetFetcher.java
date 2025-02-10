// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.BuildResult;
import com.google.devtools.build.lib.buildtool.BuildTool;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.common.options.OptionPriority.PriorityCategory;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;
import java.util.List;

/** Fetches all repos needed for building a given set of targets. */
public class TargetFetcher {
  private final CommandEnvironment env;

  private TargetFetcher(CommandEnvironment env) {
    this.env = env;
  }

  /** Creates a no-build build request to fetch all repos needed to build these targets */
  public static BuildResult fetchTargets(
      CommandEnvironment env, OptionsParsingResult options, List<String> targets)
      throws TargetFetcherException {
    return new TargetFetcher(env).fetchTargets(options, targets);
  }

  private BuildResult fetchTargets(OptionsParsingResult options, List<String> targets)
      throws TargetFetcherException {
    BuildRequest request =
        BuildRequest.builder()
            .setCommandName(env.getCommandName())
            .setId(env.getCommandId())
            .setOptions(options)
            .setStartupOptions(env.getRuntime().getStartupOptionsProvider())
            .setOutErr(env.getReporter().getOutErr())
            .setTargets(targets)
            .setStartTimeMillis(env.getCommandStartTime())
            .build();

    BuildResult result = new BuildTool(env).processRequest(request, null, options);
    if (!result.getSuccess()) {
      throw new TargetFetcherException(
          "Fetching some target dependencies failed with errors: "
              + result.getDetailedExitCode().getFailureDetail().getMessage());
    }
    return result;
  }

  static void injectNoBuildOption(OptionsParser optionsParser) {
    try {
      optionsParser.parse(
          PriorityCategory.COMPUTED_DEFAULT,
          "Options required to fetch target",
          ImmutableList.of("--nobuild"));
    } catch (OptionsParsingException e) {
      throw new IllegalStateException("Fetch target needed option failed to parse", e);
    }
  }

  static class TargetFetcherException extends Exception {
    public TargetFetcherException(String message) {
      super(message);
    }
  }
}
