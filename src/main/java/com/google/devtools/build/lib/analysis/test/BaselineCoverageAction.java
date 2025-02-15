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

import com.google.common.annotations.VisibleForTesting;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.Artifacts;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.AbstractFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.DeterministicWriter;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.PrintWriter;
import javax.annotation.Nullable;

/** Generates baseline (empty) coverage for the given non-test target. */
@VisibleForTesting
@Immutable
public final class BaselineCoverageAction extends AbstractFileWriteAction {
  private final NestedSet<Artifact> instrumentedFiles;

  private BaselineCoverageAction(
      ActionOwner owner, NestedSet<Artifact> instrumentedFiles, Artifact primaryOutput) {
    super(owner, NestedSetBuilder.emptySet(Order.STABLE_ORDER), primaryOutput);
    this.instrumentedFiles = instrumentedFiles;
  }

  @VisibleForTesting
  public NestedSet<Artifact> getInstrumentedFilesForTesting() {
    return instrumentedFiles;
  }

  @Override
  public String getMnemonic() {
    return "BaselineCoverage";
  }

  @Override
  public void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      Fingerprint fp) {
    // TODO(b/150305897): No UUID?
    // TODO(b/150308417): Sort?
    Artifacts.addToFingerprint(fp, instrumentedFiles.toList());
  }

  @Override
  public DeterministicWriter newDeterministicWriter(ActionExecutionContext ctx) {
    return out -> {
      PrintWriter writer = new PrintWriter(out);
      for (Artifact file : instrumentedFiles.toList()) {
        writer.write("SF:" + file.getExecPathString() + "\n");
        writer.write("end_of_record\n");
      }
      writer.flush();
    };
  }

  static BaselineCoverageAction create(
      RuleContext ruleContext, NestedSet<Artifact> instrumentedFiles) {
    // Baseline coverage artifacts will still go into "testlogs" directory.
    Artifact coverageData =
        ruleContext.getPackageRelativeArtifact(
            PathFragment.create(ruleContext.getTarget().getName())
                .getChild("baseline_coverage.dat"),
            ruleContext.getTestLogsDirectory());
    return new BaselineCoverageAction(
        ruleContext.getActionOwner(), instrumentedFiles, coverageData);
  }
}
