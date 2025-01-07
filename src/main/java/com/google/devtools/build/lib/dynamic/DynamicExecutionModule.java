// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.dynamic;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnStrategy;
import com.google.devtools.build.lib.actions.Spawns;
import com.google.devtools.build.lib.buildtool.BuildRequestOptions;
import com.google.devtools.build.lib.concurrent.ExecutorUtil;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.ExecutionPolicy;
import com.google.devtools.build.lib.exec.SpawnStrategyRegistry;
import com.google.devtools.build.lib.exec.local.LocalExecutionOptions;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.server.FailureDetails.ExecutionOptions;
import com.google.devtools.build.lib.server.FailureDetails.ExecutionOptions.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.common.options.OptionsBase;
import com.google.errorprone.annotations.ForOverride;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** {@link BlazeModule} providing support for dynamic spawn execution and scheduling. */
public class DynamicExecutionModule extends BlazeModule {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private ExecutorService executorService;
  Set<Integer> ignoreLocalSignals = ImmutableSet.of();
  protected Reporter reporter;
  protected boolean verboseFailures;
  private LocalExecutionOptions localOptions;

  public DynamicExecutionModule() {}

  @VisibleForTesting
  DynamicExecutionModule(ExecutorService executorService) {
    this.executorService = executorService;
  }

  @Override
  public Iterable<Class<? extends OptionsBase>> getCommandOptions(Command command) {
    return "build".equals(command.name())
        ? ImmutableList.of(DynamicExecutionOptions.class)
        : ImmutableList.<Class<? extends OptionsBase>>of();
  }

  @SuppressWarnings("AllowVirtualThreads")
  @Override
  public void beforeCommand(CommandEnvironment env) {
    var buildRequestOptions = env.getOptions().getOptions(BuildRequestOptions.class);
    if (buildRequestOptions != null && buildRequestOptions.useAsyncExecution) {
      executorService =
          Executors.newThreadPerTaskExecutor(
              Thread.ofVirtual().name("dynamic-execution-thread-", 0).factory());
    } else {
      executorService =
          Executors.newCachedThreadPool(
              new ThreadFactoryBuilder().setNameFormat("dynamic-execution-thread-%d").build());
    }
    env.getEventBus().register(this);
    com.google.devtools.build.lib.exec.ExecutionOptions executionOptions =
        env.getOptions().getOptions(com.google.devtools.build.lib.exec.ExecutionOptions.class);
    verboseFailures = executionOptions != null && executionOptions.verboseFailures;
    DynamicExecutionOptions dynamicOptions =
        env.getOptions().getOptions(DynamicExecutionOptions.class);
    localOptions = env.getOptions().getOptions(LocalExecutionOptions.class);
    ignoreLocalSignals =
        dynamicOptions != null && dynamicOptions.ignoreLocalSignals != null
            ? dynamicOptions.ignoreLocalSignals
            : ImmutableSet.of();
    reporter = env.getReporter();
  }

  @VisibleForTesting
  ImmutableMap<String, List<String>> getLocalStrategies(
      DynamicExecutionOptions options, boolean sandboxingSupported) throws AbruptExitException {
    // Options that set "allowMultiple" to true ignore the default value, so we replicate that
    // functionality here.
    ImmutableMap.Builder<String, List<String>> localAndWorkerStrategies = ImmutableMap.builder();
    ImmutableList.Builder<String> defaultLocalStrategies = ImmutableList.builder();
    defaultLocalStrategies.add("worker");
    if (sandboxingSupported) {
      defaultLocalStrategies.add("sandboxed");
    }
    if (localOptions != null && localOptions.localLockfreeOutput) {
      // Without local lock free, having standalone execution risks very bad performance.
      defaultLocalStrategies.add("standalone");
    }
    localAndWorkerStrategies.put("", defaultLocalStrategies.build());

    for (Map.Entry<String, List<String>> entry : options.dynamicLocalStrategy) {
      localAndWorkerStrategies.put(entry);
      throwIfContainsDynamic(entry.getValue(), "--dynamic_local_strategy");
    }
    return localAndWorkerStrategies.buildKeepingLast();
  }

  private ImmutableMap<String, List<String>> getRemoteStrategies(DynamicExecutionOptions options)
      throws AbruptExitException {
    Map<String, List<String>> strategies = new HashMap<>(); // Needed to dedup
    for (Map.Entry<String, List<String>> e : options.dynamicRemoteStrategy) {
      throwIfContainsDynamic(e.getValue(), "--dynamic_remote_strategy");
      strategies.put(e.getKey(), e.getValue());
    }
    return options.dynamicRemoteStrategy.isEmpty()
        ? ImmutableMap.of("", ImmutableList.of(remoteStrategyName()))
        : ImmutableMap.copyOf(strategies);
  }

  @ForOverride
  protected String remoteStrategyName() {
    return "remote";
  }

  @Override
  public void registerSpawnStrategies(
      SpawnStrategyRegistry.Builder registryBuilder, CommandEnvironment env)
      throws AbruptExitException {
    DynamicExecutionOptions options = env.getOptions().getOptions(DynamicExecutionOptions.class);
    com.google.devtools.build.lib.exec.ExecutionOptions execOptions =
        env.getOptions().getOptions(com.google.devtools.build.lib.exec.ExecutionOptions.class);
    registerSpawnStrategies(
        registryBuilder,
        options,
        execOptions.getLocalResource().get(ResourceSet.CPU).intValue(),
        env.getOptions().getOptions(BuildRequestOptions.class).jobs);
  }

  // CommandEnvironment is difficult to access in tests, so use this method for testing.
  @VisibleForTesting
  final void registerSpawnStrategies(
      SpawnStrategyRegistry.Builder registryBuilder,
      DynamicExecutionOptions options,
      int numCpus,
      int jobs)
      throws AbruptExitException {
    if (!options.internalSpawnScheduler) {
      return;
    }

    SpawnStrategy strategy =
        new DynamicSpawnStrategy(
            executorService,
            options,
            this::getExecutionPolicy,
            this::getPostProcessingSpawnForLocalExecution,
            numCpus,
            jobs,
            this::canIgnoreFailure);
    registryBuilder.registerStrategy(strategy, "dynamic", "dynamic_worker");
    boolean sandboxingSupported = registryBuilder.isStrategyRegistered("sandboxed");
    registryBuilder.addDynamicLocalStrategies(getLocalStrategies(options, sandboxingSupported));
    registryBuilder.addDynamicRemoteStrategies(getRemoteStrategies(options));
  }

  private void throwIfContainsDynamic(List<String> strategies, String flagName)
      throws AbruptExitException {
    ImmutableSet<String> identifiers = ImmutableSet.of("dynamic", "dynamic_worker");
    if (!Sets.intersection(identifiers, ImmutableSet.copyOf(strategies)).isEmpty()) {
      String message =
          String.format(
              "Cannot use strategy %s in flag %s as it would create a cycle during" + " execution",
              identifiers, flagName);
      throw new AbruptExitException(
          DetailedExitCode.of(
              FailureDetail.newBuilder()
                  .setMessage(message)
                  .setExecutionOptions(
                      ExecutionOptions.newBuilder().setCode(Code.INVALID_CYCLIC_DYNAMIC_STRATEGY))
                  .build()));
    }
  }

  /**
   * Use the {@link Spawn} metadata to determine if it can be executed locally, remotely, or both.
   *
   * @param spawn the {@link Spawn} action
   * @return the {@link ExecutionPolicy} containing local/remote execution policies
   */
  protected ExecutionPolicy getExecutionPolicy(Spawn spawn) {
    if (!Spawns.mayBeExecutedRemotely(spawn)) {
      return ExecutionPolicy.LOCAL_EXECUTION_ONLY;
    }
    if (!Spawns.mayBeExecutedLocally(spawn)) {
      return ExecutionPolicy.REMOTE_EXECUTION_ONLY;
    }

    return ExecutionPolicy.ANYWHERE;
  }

  /**
   * Returns a post processing {@link Spawn} if one needs to be executed after given {@link Spawn}
   * when running locally.
   *
   * <p>The intention of this is to allow post-processing of the original {@linkplain Spawn spawn}
   * when executing it locally. In particular, such spawn should never create outputs which are not
   * included in the generating action of the original one.
   */
  protected Optional<Spawn> getPostProcessingSpawnForLocalExecution(Spawn spawn) {
    return Optional.empty();
  }

  /**
   * If true, the failure passed in can be ignored in one branch to allow the other branch to finish
   * it instead. This can e.g. allow ignoring remote execution timeouts or local-only permission
   * failures.
   *
   * @param spawn The spawn being executed.
   * @param exitCode The exit code from executing the spawn
   * @param errorMessage Error messages returned from executing the spawn
   * @param outErr The location of the stdout and stderr from the spawn.
   * @param isLocal True if this is the locally-executed branch.
   * @return True if this failure is one that we want to allow the other branch to succeed at, even
   *     though this branch failed already.
   */
  protected boolean canIgnoreFailure(
      Spawn spawn,
      ActionExecutionContext context,
      int exitCode,
      String errorMessage,
      FileOutErr outErr,
      boolean isLocal) {
    // By convention, when killed by a signal, a process gives exit code (128 + signal number).
    // More accurate information could be had through {@code waitid(2)}, but Java does not expose
    // that. But accuracy is not critical here, at worst we are a bit slower in getting either
    // a success or a failure.
    int signal = exitCode - 128;
    if (isLocal && ignoreLocalSignals.contains(signal)) {
      if (verboseFailures) {
        reporter.handle(
            Event.info(
                String.format(
                    "Local execution for %s stopped by signal %d, ignoring in favor of remote"
                        + " execution.",
                    spawn.getResourceOwner().prettyPrint(), signal)));
      }
      logger.atInfo().log("Ignoring dynamic local branch killed by signal %d", signal);
      return true;
    }
    return false;
  }

  @FunctionalInterface
  interface IgnoreFailureCheck {
    boolean canIgnoreFailure(
        Spawn spawn,
        ActionExecutionContext context,
        int exitCode,
        String errorMessage,
        FileOutErr outErr,
        boolean isLocal);
  }

  @Override
  public void afterCommand() {
    ExecutorUtil.interruptibleShutdown(executorService);
    executorService = null;
  }
}
