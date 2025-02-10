// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.devtools.build.lib.packages.TargetUtils.isTestRuleName;
import static com.google.devtools.build.lib.skyframe.CoverageReportValue.COVERAGE_REPORT_KEY;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.OutputChecker;
import com.google.devtools.build.lib.analysis.AnalysisResult;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ExtraActionArtifactsProvider;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.ProviderCollection;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.analysis.TopLevelArtifactHelper;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.test.TestProvider;
import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.remote.options.RemoteOutputsMode;
import com.google.devtools.build.lib.remote.util.ConcurrentPathTrie;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** An {@link OutputChecker} that checks the TTL of remote metadata. */
public class RemoteOutputChecker implements OutputChecker {
  private enum CommandMode {
    UNKNOWN,
    BUILD,
    TEST,
    RUN,
    COVERAGE;
  }

  private final Clock clock;
  private final CommandMode commandMode;
  private final RemoteOutputsMode outputsMode;
  private final ImmutableList<Pattern> patternsToDownload;
  @Nullable private final RemoteOutputChecker lastRemoteOutputChecker;

  private final ConcurrentPathTrie pathsToDownload = new ConcurrentPathTrie();

  public RemoteOutputChecker(
      Clock clock,
      String commandName,
      RemoteOutputsMode outputsMode,
      ImmutableList<Pattern> patternsToDownload) {
    this(clock, commandName, outputsMode, patternsToDownload, /* lastRemoteOutputChecker= */ null);
  }

  public RemoteOutputChecker(
      Clock clock,
      String commandName,
      RemoteOutputsMode outputsMode,
      ImmutableList<Pattern> patternsToDownload,
      RemoteOutputChecker lastRemoteOutputChecker) {
    this.clock = clock;
    this.commandMode =
        switch (commandName) {
          case "build" -> CommandMode.BUILD;
          case "test" -> CommandMode.TEST;
          case "run" -> CommandMode.RUN;
          case "coverage" -> CommandMode.COVERAGE;
          default -> CommandMode.UNKNOWN;
        };
    this.outputsMode = outputsMode;
    this.patternsToDownload = patternsToDownload;
    this.lastRemoteOutputChecker = lastRemoteOutputChecker;
  }

  // Skymeld-only.
  public void afterTopLevelTargetAnalysis(
      ConfiguredTarget configuredTarget,
      Supplier<TopLevelArtifactContext> topLevelArtifactContextSupplier) {
    if (outputsMode == RemoteOutputsMode.ALL) {
      // For ALL, there's no need to keep track of toplevel targets - we download everything.
      return;
    }
    addTopLevelTarget(configuredTarget, configuredTarget, topLevelArtifactContextSupplier);
  }

  // Skymeld-only.
  public void afterTestAnalyzedEvent(ConfiguredTarget configuredTarget) {
    if (outputsMode == RemoteOutputsMode.ALL) {
      // For ALL, there's no need to keep track of toplevel targets - we download everything.
      return;
    }
    addTargetUnderTest(configuredTarget);
  }

  // Skymeld-only.
  public void afterAspectAnalysis(
      ConfiguredAspect configuredAspect,
      Supplier<TopLevelArtifactContext> topLevelArtifactContextSupplier) {
    if (outputsMode == RemoteOutputsMode.ALL) {
      // For ALL, there's no need to keep track of toplevel targets - we download everything.
      return;
    }
    addTopLevelTarget(
        configuredAspect, /* configuredTarget= */ null, topLevelArtifactContextSupplier);
  }

  // Skymeld-only.
  public void coverageArtifactsKnown(ImmutableSet<Artifact> coverageArtifacts) {
    if (outputsMode == RemoteOutputsMode.ALL) {
      // For ALL, there's no need to keep track of toplevel targets - we download everything.
      return;
    }
    maybeAddCoverageArtifacts(coverageArtifacts);
  }

  // Non-Skymeld only.
  public void afterAnalysis(AnalysisResult analysisResult) {
    if (outputsMode == RemoteOutputsMode.ALL) {
      // For ALL, there's no need to keep track of toplevel targets - we download everything.
      return;
    }
    for (var target : analysisResult.getTargetsToBuild()) {
      addTopLevelTarget(target, target, analysisResult::getTopLevelContext);
    }
    for (var aspect : analysisResult.getAspectsMap().values()) {
      addTopLevelTarget(aspect, /* configuredTarget= */ null, analysisResult::getTopLevelContext);
    }
    var targetsToTest = analysisResult.getTargetsToTest();
    if (targetsToTest != null) {
      for (var target : targetsToTest) {
        addTargetUnderTest(target);
      }
      maybeAddCoverageArtifacts(analysisResult.getArtifactsToBuild());
    }
  }

  private void addTopLevelTarget(
      ProviderCollection target,
      @Nullable ConfiguredTarget configuredTarget,
      Supplier<TopLevelArtifactContext> topLevelArtifactContextSupplier) {
    if (shouldAddTopLevelTarget(configuredTarget)) {
      var topLevelArtifactContext = topLevelArtifactContextSupplier.get();
      var artifactsToBuild =
          TopLevelArtifactHelper.getAllArtifactsToBuild(target, topLevelArtifactContext)
              .getImportantArtifacts();
      addOutputsToDownload(artifactsToBuild.toList());
      addRunfiles(target);
      addExtraActionArtifacts(target);
    }
  }

  private void addRunfiles(ProviderCollection buildTarget) {
    var runfilesProvider = buildTarget.getProvider(FilesToRunProvider.class);
    if (runfilesProvider == null) {
      return;
    }
    var runfilesSupport = runfilesProvider.getRunfilesSupport();
    if (runfilesSupport == null) {
      return;
    }
    var runfiles = runfilesSupport.getRunfiles();
    for (Artifact runfile : runfiles.getArtifacts().toList()) {
      if (runfile.isSourceArtifact()) {
        continue;
      }
      addOutputToDownload(runfile);
    }
    for (var symlink : runfiles.getSymlinks().toList()) {
      var artifact = symlink.getArtifact();
      if (artifact.isSourceArtifact()) {
        continue;
      }
      addOutputToDownload(artifact);
    }
    for (var symlink : runfiles.getRootSymlinks().toList()) {
      var artifact = symlink.getArtifact();
      if (artifact.isSourceArtifact()) {
        continue;
      }
      addOutputToDownload(artifact);
    }
  }

  private void addExtraActionArtifacts(ProviderCollection target) {
    ExtraActionArtifactsProvider extraActionArtifactsProvider =
        target.getProvider(ExtraActionArtifactsProvider.class);
    if (extraActionArtifactsProvider != null) {
      addOutputsToDownload(extraActionArtifactsProvider.getExtraActionArtifacts().toList());
    }
  }

  private void addTargetUnderTest(ProviderCollection target) {
    TestProvider testProvider = checkNotNull(target.getProvider(TestProvider.class));
    if (outputsMode != RemoteOutputsMode.MINIMAL && commandMode == CommandMode.TEST) {
      // In test mode, download the outputs of the test runner action.
      addOutputsToDownload(testProvider.getTestParams().getOutputs());
    }
    if (commandMode == CommandMode.COVERAGE) {
      // In coverage mode, download the per-test and aggregated coverage files.
      // Do this even for MINIMAL, since coverage (unlike test) doesn't produce any observable
      // results other than outputs.
      addOutputsToDownload(testProvider.getTestParams().getCoverageArtifacts());
    }
  }

  private void maybeAddCoverageArtifacts(ImmutableSet<Artifact> artifactsToBuild) {
    if (commandMode != CommandMode.COVERAGE) {
      return;
    }
    for (Artifact artifactToBuild : artifactsToBuild) {
      if (artifactToBuild.getArtifactOwner().equals(COVERAGE_REPORT_KEY)) {
        addOutputToDownload(artifactToBuild);
      }
    }
  }

  private void addOutputsToDownload(Iterable<? extends ActionInput> files) {
    for (ActionInput file : files) {
      addOutputToDownload(file);
    }
  }

  public void addOutputToDownload(ActionInput file) {
    if (file instanceof Artifact && ((Artifact) file).isTreeArtifact()) {
      pathsToDownload.addPrefix(file.getExecPath());
    } else {
      pathsToDownload.add(file.getExecPath());
    }
  }

  private boolean shouldAddTopLevelTarget(@Nullable ConfiguredTarget configuredTarget) {
    return switch (commandMode) {
      // Always download outputs of toplevel targets in run mode.
      case RUN -> true;
      case COVERAGE, TEST -> {
        // Do not download test binary in test/coverage mode.
        if (configuredTarget instanceof RuleConfiguredTarget ruleConfiguredTarget) {
          var isTestRule = isTestRuleName(ruleConfiguredTarget.getRuleClassString());
          yield !isTestRule && outputsMode != RemoteOutputsMode.MINIMAL;
        }
        yield outputsMode != RemoteOutputsMode.MINIMAL;
      }
      default -> outputsMode != RemoteOutputsMode.MINIMAL;
    };
  }

  private boolean matchesPattern(PathFragment execPath) {
    for (var pattern : patternsToDownload) {
      if (pattern.matcher(execPath.toString()).matches()) {
        return true;
      }
    }
    return false;
  }

  /** Returns whether this {@link ActionInput} should be downloaded. */
  public boolean shouldDownloadOutput(ActionInput output) {
    checkState(
        !(output instanceof Artifact && ((Artifact) output).isTreeArtifact()),
        "shouldDownloadOutput should not be called on a tree artifact");
    return shouldDownloadOutput(output.getExecPath());
  }

  /** Returns whether an {@link ActionInput} with the given path should be downloaded. */
  public boolean shouldDownloadOutput(PathFragment execPath) {
    return outputsMode == RemoteOutputsMode.ALL
        || pathsToDownload.contains(execPath)
        || matchesPattern(execPath);
  }

  @Override
  public boolean shouldTrustArtifact(ActionInput file, FileArtifactValue metadata) {
    // Local metadata is always trusted.
    if (!metadata.isRemote()) {
      return true;
    }

    // If Bazel should download this file, but it does not exist locally, returns false to rerun
    // the generating action to trigger the download (just like in the normal build, when local
    // outputs are missing).

    if (lastRemoteOutputChecker != null) {
      // This is an incremental build. If the file was downloaded by previous build and is now
      // missing, invalidate the action.
      if (lastRemoteOutputChecker.shouldDownloadOutput(file)) {
        return false;
      }
    }

    if (shouldDownloadOutput(file)) {
      return false;
    }

    return metadata.isAlive(clock.now());
  }

  public void maybeInvalidateSkyframeValues(MemoizingEvaluator memoizingEvaluator) {
    if (lastRemoteOutputChecker == null) {
      return;
    }

    // If the outputsMode or commandMode is changed, we invalidate completion functions. Otherwise,
    // some requested outputs might not be correctly downloaded.
    if (lastRemoteOutputChecker.outputsMode != outputsMode
        || lastRemoteOutputChecker.commandMode != commandMode) {
      memoizingEvaluator.delete(
          k -> {
            var functionName = k.functionName();
            return functionName.equals(SkyFunctions.TARGET_COMPLETION)
                || functionName.equals(SkyFunctions.ASPECT_COMPLETION);
          });
    }
  }
}
