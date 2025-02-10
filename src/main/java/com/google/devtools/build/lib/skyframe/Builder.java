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

package com.google.devtools.build.lib.skyframe;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.OutputChecker;
import com.google.devtools.build.lib.actions.TestExecException;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.common.options.OptionsProvider;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A Builder consumes top-level artifacts, targets, and tests,, and executes them in some
 * topological order, possibly concurrently, using some dependency-checking policy.
 *
 * <p> The methods of the Builder interface are typically long-running, but honor the
 * {@link java.lang.Thread#interrupt} contract: if an interrupt is delivered to the thread in which
 * a call to buildTargets or buildArtifacts is active, the Builder attempts to terminate the call
 * prematurely, throwing InterruptedException.  No guarantee is made about the timeliness of such
 * termination, as it depends on the ability of the Actions being executed to be interrupted, but
 * typically any running subprocesses will be quickly killed.
 */
public interface Builder {

  /**
   * Transitively build all given artifacts, targets, and tests, and all necessary prerequisites
   * thereof. For sequential implementations of this interface, the top-level requests will be built
   * in the iteration order of the Set provided; for concurrent implementations, the order is
   * undefined.
   *
   * <p>This method should not be invoked more than once concurrently on the same Builder instance.
   *
   * @param artifacts the set of Artifacts to build
   * @param parallelTests tests to execute in parallel with the other top-level targetsToBuild and
   *     artifacts.
   * @param exclusiveTests are executed one at a time, only after all other tasks have completed
   * @param targetsToBuild Set of targets which will be built
   * @param targetsToSkip Set of targets which should be skipped (they still show up in build
   *     results, but with a "SKIPPED" status and without the cost of any actual build work)
   * @param aspects Set of aspects that will be built
   * @param executor an opaque application-specific value that will be passed down to the execute()
   *     method of any Action executed during this call
   * @param lastExecutionTimeRange If not null, the start/finish time of the last build that run the
   *     execution phase.
   * @param topLevelArtifactContext contains the options which determine the artifacts to build for
   *     the top-level targets.
   * @throws BuildFailedException if there were problems establishing the action execution
   *     environment, if the metadata of any file during the build could not be obtained, if any
   *     input files are missing, or if an action fails during execution
   * @throws InterruptedException if there was an asynchronous stop request
   * @throws TestExecException if any test fails
   */
  @ThreadCompatible
  void buildArtifacts(
      Reporter reporter,
      Set<Artifact> artifacts,
      Set<ConfiguredTarget> parallelTests,
      Set<ConfiguredTarget> exclusiveTests,
      Set<ConfiguredTarget> targetsToBuild,
      Set<ConfiguredTarget> targetsToSkip,
      ImmutableSet<AspectKey> aspects,
      Executor executor,
      OptionsProvider options,
      @Nullable Range<Long> lastExecutionTimeRange,
      TopLevelArtifactContext topLevelArtifactContext,
      OutputChecker outputChecker)
      throws BuildFailedException, AbruptExitException, InterruptedException, TestExecException;
}
