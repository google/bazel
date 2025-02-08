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
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.concurrent.QuiescingExecutor;
import com.google.devtools.build.lib.packages.producers.GlobComputationProducer;
import com.google.devtools.build.lib.packages.producers.GlobError;
import com.google.devtools.build.lib.skyframe.GlobsValue.GlobRequest;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.ConcurrentSkyFunctionEnvironment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState;
import com.google.devtools.build.skyframe.SkyFunctionEnvironment;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.state.Driver;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * A {@link SkyFunction} for {@link GlobsValue}, which drives the glob matching process for all
 * globs within a package.
 *
 * <p>{@link GlobsFunction} has two benefits over {@link GlobFunction}:
 *
 * <ul>
 *   <li>The multiple GLOB nodes rdeping on the package are aggregated into a single one. This
 *       reduces some memory overhead, especially when number of globs defined in the BUILD file is
 *       very large.
 *   <li>Evaluating all globs within a package starts to have some structured logical concurrency,
 *       thus reducing the number of Skyframe restarts.
 * </ul>
 *
 * <p>{@link GlobsFunction} is the only {@link SkyFunction} taking advantage of {@link
 * SkyFunctionEnvironment#getParallelEvaluationExecutor()}. State Machines are driven in-parallel on
 * both {@link #compute} and the "skyframe-evaluator" ForkJoinPool's threads.
 *
 * <p>Skyframe globbing was previously performed via multiple {@link GlobFunction}s. Each glob
 * expression of the package leads to at least one GLOB node in the dependency graph. These glob
 * nodes evaluation are also done on the "skyframe-evaluator" FJP. So when skyframe globbing is done
 * by this {@link GlobsFunction}, there is no increase in the actual workload. As a result, we
 * consider it reasonable to introduce the existing "skyframe-evaluator" parallelism to {@link
 * GlobsFunction}.
 */
public final class GlobsFunction implements SkyFunction {

  protected ConcurrentHashMap<String, Pattern> regexPatternCache = new ConcurrentHashMap<>();

  private static class State implements SkyKeyComputeState, GlobComputationProducer.ResultSink {
    @Nullable private List<Driver> globDrivers;
    @Nullable IgnoredSubdirectories ignoredSubdirectories;

    private final Set<PathFragment> matchings = Sets.newConcurrentHashSet();
    private volatile GlobError error;

    /**
     * This method does not necessarily need to be a synchronized one. As long as some error was
     * captured, the {@link GlobsFunction#compute} will ignore {@link #matchings} and throws the
     * captured {@link #error}. However, any operation {@link #matchings} has to be thread-safe.
     */
    @Override
    public void acceptPathFragmentsWithoutPackageFragment(
        ImmutableSet<PathFragment> pathFragments) {
      if (error == null) {
        // If an exception has already been discovered and accepted during previous computation, we
        // should not accept any matching result.
        matchings.addAll(pathFragments);
      }
    }

    @Override
    public synchronized void acceptGlobError(GlobError globError) {
      if (error == null) {
        // Keeps the first reported error if there are multiple.
        this.error = globError;
      }
    }
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {
    GlobsValue.Key globsKey = (GlobsValue.Key) skyKey;
    State state = env.getState(State::new);

    if (state.ignoredSubdirectories == null) {
      RepositoryName repositoryName = globsKey.getPackageIdentifier().getRepository();
      IgnoredSubdirectoriesValue ignoredSubdirectories =
          (IgnoredSubdirectoriesValue) env.getValue(IgnoredSubdirectoriesValue.key(repositoryName));
      if (env.valuesMissing()) {
        return null;
      }
      state.ignoredSubdirectories = ignoredSubdirectories.asIgnoredSubdirectories();
    }

    if (state.globDrivers == null) {
      state.globDrivers = new ArrayList<>();
      for (GlobRequest globRequest : globsKey.getGlobRequests()) {
        GlobDescriptor globDescriptor =
            GlobDescriptor.create(
                globsKey.getPackageIdentifier(),
                globsKey.getPackageRoot(),
                // TODO(b/290998109): Support non-empty subdir when replacing Glob with Globs in
                // IncludeParser.
                PathFragment.EMPTY_FRAGMENT,
                globRequest.getPattern(),
                globRequest.getGlobOperation());
        state.globDrivers.add(
            new Driver(
                new GlobComputationProducer(
                    globDescriptor, state.ignoredSubdirectories, regexPatternCache, state)));
      }
    }

    ConcurrentSkyFunctionEnvironment concurrentEnvironment =
        new ConcurrentSkyFunctionEnvironment((SkyFunctionEnvironment) env);
    AtomicBoolean allComplete = new AtomicBoolean(true);
    AtomicReference<InterruptedException> possibleInterruptedExceptionRef = new AtomicReference<>();
    BlockingQueue<Runnable> stateMachineRunnablesQueue = new LinkedBlockingQueue<>();
    CountDownLatch countDownLatch = new CountDownLatch(state.globDrivers.size());
    for (Driver driver : state.globDrivers) {
      stateMachineRunnablesQueue.put(
          () -> {
            try {
              if (!driver.drive(concurrentEnvironment)) {
                allComplete.set(false);
              }
            } catch (InterruptedException e) {
              possibleInterruptedExceptionRef.compareAndSet(/* expectedValue= */ null, e);
            } finally {
              countDownLatch.countDown();
            }
          });
    }

    // This allows work to be shared with the current Skyframe thread.
    Runnable drainStateMachineQueue =
        () -> {
          Runnable next;
          boolean isInterrupted = false;
          while ((next = stateMachineRunnablesQueue.poll()) != null) {
            if (isInterrupted) {
              countDownLatch.countDown();
              continue;
            }
            next.run();
            if (Thread.interrupted()) {
              isInterrupted = true;
              possibleInterruptedExceptionRef.compareAndSet(
                  /* expectedValue= */ null, new InterruptedException());
            }
          }
        };

    // Schedule the State Machines to be driven on "skyframe-evaluator" threads.
    QuiescingExecutor executor = env.getParallelEvaluationExecutor();
    if (executor != null) {
      for (int i = 0; i < state.globDrivers.size() - 1; ++i) {
        // When executor is a MultiExecutorQueueVisitor, calling execute without providing the
        // threadPoolType will execute the runnable on the regular "skyframe-evaluator" threads.
        executor.execute(drainStateMachineQueue);
      }
    }

    // Also take advantage of the current thread to drive some State Machines.
    drainStateMachineQueue.run();

    // It is possible State Machines run on external threads finish later than the ones on current
    // thread. So we need to wait for all State Machine `Runnable`s to complete before proceeding.
    // Using `Uninterruptibles.awaitUninterruptibly` is necessary in that all State Machine workers
    // threads should complete before GlobsFunction#compute() re-throws the InterruptedException.
    // Otherwise, downstream logic on the main thread could race with unfinished State Machine
    // workers threads.
    Uninterruptibles.awaitUninterruptibly(countDownLatch);
    if (Thread.interrupted()) {
      possibleInterruptedExceptionRef.compareAndSet(
          /* expectedValue= */ null, new InterruptedException());
    }
    if (possibleInterruptedExceptionRef.get() != null) {
      throw possibleInterruptedExceptionRef.get();
    }

    if (!allComplete.get()) {
      GlobException.handleExceptions(state.error);
      return null;
    }

    GlobException.handleExceptions(state.error);
    return new GlobsValue(ImmutableSet.copyOf(state.matchings));
  }
}
