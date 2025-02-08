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
package com.google.devtools.build.lib.skyframe;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.producers.GlobComputationProducer;
import com.google.devtools.build.lib.packages.producers.GlobError;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.state.Driver;
import javax.annotation.Nullable;

/**
 * Computes {@link GlobValue}s for a package with only one Glob node created. The recursive globbing
 * logic is inlined in one {@link com.google.devtools.build.skyframe.SkyFunction#compute}
 * invocation.
 *
 * <p>The recursion inlined in one {@link com.google.devtools.build.skyframe.SkyFunction#compute}
 * invocation is realized by using {@link com.google.devtools.build.skyframe.state.StateMachine} for
 * structured concurrency when querying dependent {@link SkyKey}s, and {@link SkyKeyComputeState} to
 * cache computation state between skyframe restarts.
 */
public class GlobFunctionWithRecursionInSingleFunction extends GlobFunction {

  /**
   * Stores {@link GlobFunctionWithRecursionInSingleFunction} computation state of the same glob
   * pattern between skyframe restarts.
   */
  private static class State implements SkyKeyComputeState, GlobComputationProducer.ResultSink {

    /**
     * Drives a {@link GlobComputationProducer} that sets the {@link #globMatchingResult} when
     * complete.
     */
    @Nullable // Non-null while in-flight.
    private Driver globComputationDriver;

    @Nullable IgnoredSubdirectories ignoredSubdirectories;

    private ImmutableSet<PathFragment> globMatchingResult;
    private GlobError error;

    @Override
    public void acceptPathFragmentsWithoutPackageFragment(
        ImmutableSet<PathFragment> globMatchingResult) {
      if (error == null) {
        // If an exception has already been discovered and accepted during previous computation, we
        // should not accept any matching result.
        this.globMatchingResult = globMatchingResult;
      }
    }

    @Override
    public void acceptGlobError(GlobError error) {
      if (this.error == null) {
        // Keeps the first reported error if there are multiple.
        this.error = error;
      }
    }
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws GlobException, InterruptedException {
    GlobDescriptor glob = (GlobDescriptor) skyKey.argument();
    State state = env.getState(State::new);

    if (state.ignoredSubdirectories == null) {
      RepositoryName repositoryName = glob.getPackageId().getRepository();
      IgnoredSubdirectoriesValue ignoredSubDirectories =
          (IgnoredSubdirectoriesValue) env.getValue(IgnoredSubdirectoriesValue.key(repositoryName));
      if (env.valuesMissing()) {
        return null;
      }
      state.ignoredSubdirectories = ignoredSubDirectories.asIgnoredSubdirectories();
    }

    if (state.globComputationDriver == null) {
      state.globComputationDriver =
          new Driver(
              new GlobComputationProducer(
                  glob, state.ignoredSubdirectories, regexPatternCache, state));
    }

    if (!state.globComputationDriver.drive(env)) {
      // Even though glob computation has not completed, we still want to throw exceptions
      // discovered in the current Skyframe session.
      GlobException.handleExceptions(state.error);
      return null;
    }

    GlobException.handleExceptions(state.error);
    return new GlobValueWithImmutableSet(state.globMatchingResult);
  }
}
