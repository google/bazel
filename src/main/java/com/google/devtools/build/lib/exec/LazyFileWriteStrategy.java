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

package com.google.devtools.build.lib.exec;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.FileArtifactValue.FileWriteOutputArtifactValue;
import com.google.devtools.build.lib.actions.RunningActionEvent;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.analysis.actions.DeterministicWriter;
import com.google.devtools.build.lib.profiler.AutoProfiler;
import com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils;
import java.time.Duration;

/**
 * A strategy for executing an {@link
 * com.google.devtools.build.lib.analysis.actions.AbstractFileWriteAction} that avoids writing the
 * file to disk if possible.
 */
public final class LazyFileWriteStrategy extends EagerFileWriteStrategy {
  private static final Duration MIN_LOGGING = Duration.ofMillis(100);

  @Override
  public ImmutableList<SpawnResult> writeOutputToFile(
      AbstractAction action,
      ActionExecutionContext actionExecutionContext,
      DeterministicWriter deterministicWriter,
      boolean makeExecutable,
      boolean isRemotable,
      Artifact output)
      throws ExecException {
    if (!isRemotable || actionExecutionContext.getActionFileSystem() == null) {
      return super.writeOutputToFile(
          action, actionExecutionContext, deterministicWriter, makeExecutable, isRemotable, output);
    }
    actionExecutionContext.getEventHandler().post(new RunningActionEvent(action, "local"));
    try (AutoProfiler p =
        GoogleAutoProfilerUtils.logged("hashing output of " + action.prettyPrint(), MIN_LOGGING)) {
      // TODO: Bazel currently marks all output files as executable after local execution and stages
      // all files as executable for remote execution, so we don't keep track of the executable
      // bit yet.
      actionExecutionContext
          .getOutputMetadataStore()
          .injectFile(
              output,
              FileWriteOutputArtifactValue.hashAndCreate(
                  deterministicWriter,
                  actionExecutionContext
                      .getActionFileSystem()
                      .getDigestFunction()
                      .getHashFunction()));
    }
    return ImmutableList.of();
  }

  @Override
  public boolean mayRetainWriter() {
    return true;
  }
}
