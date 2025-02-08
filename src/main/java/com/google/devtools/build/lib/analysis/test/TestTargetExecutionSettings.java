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

package com.google.devtools.build.lib.analysis.test;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesSupport;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.RunUnder;
import com.google.devtools.build.lib.analysis.config.RunUnder.CommandRunUnder;
import com.google.devtools.build.lib.analysis.constraints.ConstraintConstants;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.Path;
import javax.annotation.Nullable;

/**
 * Container for common test execution settings shared by all
 * all TestRunnerAction instances for the given test target.
 */
public final class TestTargetExecutionSettings {

  private final CommandLine testArguments;
  private final String testFilter;
  private final int totalShards;
  private final int totalRuns;
  private final RunUnder runUnder;
  private final Artifact runUnderExecutable;
  private final Artifact executable;
  private final boolean runfilesSymlinksCreated;
  @Nullable private final Path runfilesDir;
  private final Runfiles runfiles;
  private final Artifact runfilesInputManifest;
  private final Artifact instrumentedFileManifest;
  private final boolean testRunnerFailFast;
  private final OS executionOs;

  TestTargetExecutionSettings(
      RuleContext ruleContext,
      RunfilesSupport runfilesSupport,
      Artifact executable,
      Artifact instrumentedFileManifest,
      int shards,
      int runs)
      throws InterruptedException { // due to CommandLine.arguments
    Preconditions.checkArgument(TargetUtils.isTestRule(ruleContext.getRule()));
    Preconditions.checkArgument(shards >= 0);
    BuildConfigurationValue config = ruleContext.getConfiguration();
    TestConfiguration testConfig = config.getFragment(TestConfiguration.class);

    CommandLine targetArgs = runfilesSupport.getArgs();
    testArguments =
        CommandLine.concat(targetArgs, ImmutableList.copyOf(testConfig.getTestArguments()));

    totalShards = shards;
    totalRuns = runs;
    runUnder = config.getRunUnder();
    runUnderExecutable = getRunUnderExecutable(ruleContext);

    this.testFilter = testConfig.getTestFilter();
    this.testRunnerFailFast = testConfig.getTestRunnerFailFast();
    this.executable = executable;
    this.runfilesSymlinksCreated = runfilesSupport.getRunfilesTree().isBuildRunfileLinks();
    this.runfilesDir = runfilesSupport.getRunfilesDirectory();
    this.runfiles = runfilesSupport.getRunfiles();
    this.runfilesInputManifest = runfilesSupport.getRunfilesInputManifest();
    this.instrumentedFileManifest = instrumentedFileManifest;
    this.executionOs =
        ConstraintConstants.getOsFromConstraints(ruleContext.getExecutionPlatform().constraints());
  }

  @Nullable
  private static Artifact getRunUnderExecutable(RuleContext ruleContext) {
    TransitiveInfoCollection runUnderTarget = ruleContext.getRunUnderPrerequisite();
    return runUnderTarget == null
        ? null
        : runUnderTarget.getProvider(FilesToRunProvider.class).getExecutable();
  }

  public Artifact getRunUnderExecutable() {
    return runUnderExecutable;
  }

  public CommandLine getArgs() {
    return testArguments;
  }

  public String getTestFilter() {
    return testFilter;
  }

  public boolean getTestRunnerFailFast() {
    return testRunnerFailFast;
  }

  public int getTotalShards() {
    return totalShards;
  }

  public int getTotalRuns() {
    return totalRuns;
  }

  public RunUnder getRunUnder() {
    return runUnder;
  }

  public Artifact getExecutable() {
    return executable;
  }

  /** @return whether or not the runfiles symlinks were created */
  public boolean getRunfilesSymlinksCreated() {
    return runfilesSymlinksCreated;
  }

  /** @return the directory of the runfiles. */
  @Nullable
  public Path getRunfilesDir() {
    return runfilesDir;
  }

  /** @return the runfiles for the test */
  public Runfiles getRunfiles() {
    return runfiles;
  }

  /**
   * Returns the input runfiles manifest for this test.
   *
   * <p>This always returns the input manifest outside of the runfiles tree.
   *
   * @see com.google.devtools.build.lib.analysis.RunfilesSupport#getRunfilesInputManifest()
   */
  public Artifact getInputManifest() {
    return runfilesInputManifest;
  }

  /**
   * Returns instrumented file manifest or null if code coverage is not
   * collected.
   */
  public Artifact getInstrumentedFileManifest() {
    return instrumentedFileManifest;
  }

  public OS getExecutionOs() {
    return executionOs;
  }

  public boolean needsShell() {
    if (getRunUnder() instanceof CommandRunUnder commandRunUnder) {
      String command = commandRunUnder.command();
      // --run_under commands that do not contain '/' are either shell built-ins or need to be
      // located on the PATH env, so we wrap them in a shell invocation. Note that we
      // shell-tokenize
      // the --run_under parameter and getCommand only returns the first such token.
      return !command.contains("/") && (!executionOs.equals(OS.WINDOWS) || !command.contains("\\"));
    } else {
      return false;
    }
  }
}
