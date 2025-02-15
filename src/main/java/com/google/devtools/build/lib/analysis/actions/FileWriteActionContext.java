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
package com.google.devtools.build.lib.analysis.actions;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.SpawnResult;

/**
 * The action context for {@link AbstractFileWriteAction} instances (technically instances of
 * subclasses).
 */
public interface FileWriteActionContext extends ActionContext {

  ImmutableList<SpawnResult> writeOutputToFile(
      AbstractAction action,
      ActionExecutionContext actionExecutionContext,
      DeterministicWriter deterministicWriter,
      boolean makeExecutable,
      boolean isRemotable,
      Artifact output)
      throws InterruptedException, ExecException;

  /**
   * Writes the output created by the {@link DeterministicWriter} to the sole output of the given
   * action.
   */
  default ImmutableList<SpawnResult> writeOutputToFile(
      AbstractAction action,
      ActionExecutionContext actionExecutionContext,
      DeterministicWriter deterministicWriter,
      boolean makeExecutable,
      boolean isRemotable)
      throws InterruptedException, ExecException {
    return writeOutputToFile(
        action,
        actionExecutionContext,
        deterministicWriter,
        makeExecutable,
        isRemotable,
        Iterables.getOnlyElement(action.getOutputs()));
  }

  /**
   * Returns whether the {@link DeterministicWriter} may be retained after {@link
   * #writeOutputToFile} returns.
   */
  default boolean mayRetainWriter() {
    return false;
  }
}
