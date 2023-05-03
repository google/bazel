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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.devtools.build.lib.packages.TargetUtils.isTestRuleName;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue.RemoteFileArtifactValue;
import com.google.devtools.build.lib.actions.RemoteArtifactChecker;
import com.google.devtools.build.lib.analysis.AnalysisResult;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.analysis.TopLevelArtifactHelper;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.clock.Clock;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** A {@link RemoteArtifactChecker} that checks the TTL of remote metadata. */
public class RemoteOutputChecker implements RemoteArtifactChecker {
  private enum CommandMode {
    UNKNOWN,
    BUILD,
    TEST,
    RUN,
    COVERAGE;
  }

  private final Clock clock;
  private final CommandMode commandMode;
  private final boolean downloadToplevel;
  private final ImmutableList<Pattern> patternsToDownload;
  private final Set<Artifact> toplevelArtifactsToDownload = Sets.newConcurrentHashSet();

  public RemoteOutputChecker(
      Clock clock,
      String commandName,
      boolean downloadToplevel,
      ImmutableList<Pattern> patternsToDownload) {
    this.clock = clock;
    switch (commandName) {
      case "build":
        this.commandMode = CommandMode.BUILD;
        break;
      case "test":
        this.commandMode = CommandMode.TEST;
        break;
      case "run":
        this.commandMode = CommandMode.RUN;
        break;
      case "coverage":
        this.commandMode = CommandMode.COVERAGE;
        break;
      default:
        this.commandMode = CommandMode.UNKNOWN;
    }
    this.downloadToplevel = downloadToplevel;
    this.patternsToDownload = patternsToDownload;
  }

  // TODO(chiwang): Code path reserved for skymeld.
  public void afterTopLevelTargetAnalysis(
      ConfiguredTarget configuredTarget,
      Supplier<TopLevelArtifactContext> topLevelArtifactContextSupplier) {
    addTopLevelTarget(configuredTarget, topLevelArtifactContextSupplier);
  }

  public void afterAnalysis(AnalysisResult analysisResult) {
    for (var target : analysisResult.getTargetsToBuild()) {
      addTopLevelTarget(target, analysisResult::getTopLevelContext);
    }
    var targetsToTest = analysisResult.getTargetsToTest();
    if (targetsToTest != null) {
      for (var target : targetsToTest) {
        addTopLevelTarget(target, analysisResult::getTopLevelContext);
      }
    }
  }

  private void addTopLevelTarget(
      ConfiguredTarget toplevelTarget,
      Supplier<TopLevelArtifactContext> topLevelArtifactContextSupplier) {
    if (shouldDownloadToplevelOutputs(toplevelTarget)) {
      var topLevelArtifactContext = topLevelArtifactContextSupplier.get();
      var artifactsToBuild =
          TopLevelArtifactHelper.getAllArtifactsToBuild(toplevelTarget, topLevelArtifactContext)
              .getImportantArtifacts();
      toplevelArtifactsToDownload.addAll(artifactsToBuild.toList());

      addRunfiles(toplevelTarget);
    }
  }

  private void addRunfiles(ConfiguredTarget buildTarget) {
    var runfilesProvider = buildTarget.getProvider(FilesToRunProvider.class);
    if (runfilesProvider == null) {
      return;
    }
    var runfilesSupport = runfilesProvider.getRunfilesSupport();
    if (runfilesSupport == null) {
      return;
    }
    for (Artifact runfile : runfilesSupport.getRunfiles().getArtifacts().toList()) {
      if (runfile.isSourceArtifact()) {
        continue;
      }
      toplevelArtifactsToDownload.add(runfile);
    }
  }

  private boolean shouldDownloadToplevelOutputs(ConfiguredTarget configuredTarget) {
    switch (commandMode) {
      case RUN:
        // Always download outputs of toplevel targets in RUN mode
        return true;
      case COVERAGE:
      case TEST:
        // Do not download test binary in test/coverage mode.
        if (configuredTarget instanceof RuleConfiguredTarget) {
          var ruleConfiguredTarget = (RuleConfiguredTarget) configuredTarget;
          var isTestRule = isTestRuleName(ruleConfiguredTarget.getRuleClassString());
          return !isTestRule && downloadToplevel;
        }
        return downloadToplevel;
      default:
        return downloadToplevel;
    }
  }

  public boolean shouldDownloadFile(Artifact file) {
    checkArgument(!file.isTreeArtifact(), "file must not be a tree.");

    if (file instanceof TreeFileArtifact) {
      if (toplevelArtifactsToDownload.contains(file.getParent())) {
        return true;
      }
    } else if (toplevelArtifactsToDownload.contains(file)) {
      return true;
    }

    return outputMatchesPattern(file);
  }

  private boolean outputMatchesPattern(Artifact output) {
    for (var pattern : patternsToDownload) {
      if (pattern.matcher(output.getExecPathString()).matches()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean shouldTrustRemoteArtifact(Artifact file, RemoteFileArtifactValue metadata) {
    if (shouldDownloadFile(file)) {
      // If Bazel should download this file, but it does not exist locally, returns false to rerun
      // the generating action to trigger the download (just like in the normal build, when local
      // outputs are missing).
      return false;
    }

    return metadata.isAlive(clock.now());
  }
}
