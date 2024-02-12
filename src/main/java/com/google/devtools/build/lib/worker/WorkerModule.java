// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.worker;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.Subscribe;
import com.google.devtools.build.lib.buildtool.buildevent.BuildCompleteEvent;
import com.google.devtools.build.lib.buildtool.buildevent.BuildStartingEvent;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.RunfilesTreeUpdater;
import com.google.devtools.build.lib.exec.SpawnStrategyRegistry;
import com.google.devtools.build.lib.exec.TreeDeleter;
import com.google.devtools.build.lib.exec.local.LocalEnvProvider;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BlazeWorkspace;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.commands.events.CleanStartingEvent;
import com.google.devtools.build.lib.sandbox.AsynchronousTreeDeleter;
import com.google.devtools.build.lib.sandbox.CgroupsInfo;
import com.google.devtools.build.lib.sandbox.LinuxSandboxUtil;
import com.google.devtools.build.lib.sandbox.SandboxHelpers;
import com.google.devtools.build.lib.sandbox.SandboxOptions;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.worker.SandboxedWorker.WorkerSandboxOptions;
import com.google.devtools.build.lib.worker.WorkerPoolImpl.WorkerPoolConfig;
import com.google.devtools.common.options.OptionsBase;
import java.io.IOException;
import javax.annotation.Nullable;

/** A module that adds the WorkerActionContextProvider to the available action context providers. */
public class WorkerModule extends BlazeModule {
  private CommandEnvironment env;

  private WorkerFactory workerFactory;
  private TreeDeleter treeDeleter;
  @VisibleForTesting WorkerPoolImpl workerPool;
  @Nullable private WorkerLifecycleManager workerLifecycleManager;

  @Override
  public Iterable<Class<? extends OptionsBase>> getCommandOptions(Command command) {
    return "build".equals(command.name())
        ? ImmutableList.of(WorkerOptions.class)
        : ImmutableList.of();
  }

  @Override
  public void beforeCommand(CommandEnvironment env) {
    this.env = env;
    env.getEventBus().register(this);
    WorkerProcessMetricsCollector.instance().beforeCommand();
    WorkerMultiplexerManager.beforeCommand(env.getReporter());
  }

  @Subscribe
  public void cleanStarting(CleanStartingEvent event) {
    if (workerPool != null) {
      WorkerOptions options = event.getOptionsProvider().getOptions(WorkerOptions.class);
      workerFactory.setReporter(options.workerVerbose ? env.getReporter() : null);
      shutdownPool(
          "Clean command is running, shutting down worker pool...",
          /* alwaysLog= */ false,
          options.workerVerbose);
    }
  }

  /**
   * Handles updating worker factories and pools when a build starts. If either the workerDir or the
   * sandboxing flag has changed, we need to recreate the factory, and we clear out logs at the same
   * time. If options affecting the pools have changed, we just change the pools.
   */
  @Subscribe
  public void buildStarting(BuildStartingEvent event) {
    WorkerOptions options = checkNotNull(event.request().getOptions(WorkerOptions.class));
    if (workerFactory != null) {
      workerFactory.setReporter(options.workerVerbose ? env.getReporter() : null);
    }
    Path workerDir =
        env.getOutputBase().getRelative(env.getRuntime().getProductName() + "-workers");
    BlazeWorkspace workspace = env.getBlazeWorkspace();
    WorkerSandboxOptions workerSandboxOptions;
    if (options.sandboxHardening) {
      SandboxOptions sandboxOptions = event.request().getOptions(SandboxOptions.class);
      workerSandboxOptions =
          WorkerSandboxOptions.create(
              LinuxSandboxUtil.getLinuxSandbox(workspace),
              sandboxOptions.sandboxFakeHostname,
              sandboxOptions.sandboxFakeUsername,
              sandboxOptions.sandboxDebug,
              ImmutableList.copyOf(sandboxOptions.sandboxTmpfsPath),
              ImmutableList.copyOf(sandboxOptions.sandboxWritablePath),
              sandboxOptions.memoryLimitMb,
              sandboxOptions.getInaccessiblePaths(env.getRuntime().getFileSystem()),
              ImmutableList.copyOf(sandboxOptions.sandboxAdditionalMounts));
    } else {
      workerSandboxOptions = null;
    }
    Path trashBase = workerDir.getRelative("_moved_trash_dir");
    if (treeDeleter == null) {
      treeDeleter = new AsynchronousTreeDeleter(trashBase);
    }
    WorkerFactory newWorkerFactory =
        new WorkerFactory(workerDir, options, workerSandboxOptions, treeDeleter);
    if (!newWorkerFactory.equals(workerFactory)) {
      if (workerDir.exists()) {
        try {
          // Clean out old log files.
          for (Path logFile : workerDir.getDirectoryEntries()) {
            if (logFile.getBaseName().endsWith(".log")) {
              try {
                logFile.delete();
              } catch (IOException e) {
                env.getReporter()
                    .handle(
                        Event.warn(
                            String.format(
                                "Could not delete old worker log '%s': %s",
                                logFile, e.getMessage())));
              }
            }
          }
        } catch (IOException e) {
          env.getReporter()
              .handle(
                  Event.warn(
                      String.format(
                          "Could not delete old worker logs in '%s': %s",
                          workerDir, e.getMessage())));
        }
      }

      shutdownPool(
          "Worker factory configuration has changed, restarting worker pool...",
          /* alwaysLog= */ true,
          options.workerVerbose);
      workerFactory = newWorkerFactory;
      workerFactory.setReporter(options.workerVerbose ? env.getReporter() : null);
    }

    WorkerPoolConfig newConfig =
        new WorkerPoolConfig(
            workerFactory, options.workerMaxInstances, options.workerMaxMultiplexInstances);

    // If the config changed compared to the last run, we have to create a new pool.
    if (workerPool == null || !newConfig.equals(workerPool.getWorkerPoolConfig())) {
      shutdownPool(
          "Worker pool configuration has changed, restarting worker pool...",
          /* alwaysLog= */ true,
          options.workerVerbose);
    }

    if (workerPool == null) {
      workerPool = new WorkerPoolImpl(newConfig);
      // If workerPool is restarted then we should recreate metrics.
      WorkerProcessMetricsCollector.instance().clear();
    }

    // Override the flag value if we can't actually use cgroups so that we at least fallback to ps.
    boolean useCgroupsOnLinux = options.useCgroupsOnLinux && CgroupsInfo.isSupported();
    WorkerProcessMetricsCollector.instance().setUseCgroupsOnLinux(useCgroupsOnLinux);

    // Start collecting after a pool is defined
    workerLifecycleManager = new WorkerLifecycleManager(workerPool, options);
    workerLifecycleManager.setReporter(env.getReporter());
    workerLifecycleManager.setDaemon(true);
    workerLifecycleManager.start();

    // Reset the pool at the beginning of each build.
    workerPool.reset();
  }

  @Override
  public void registerSpawnStrategies(
      SpawnStrategyRegistry.Builder registryBuilder, CommandEnvironment env) {
    checkNotNull(workerPool);
    LocalEnvProvider localEnvProvider = LocalEnvProvider.forCurrentOs(env.getClientEnv());
    WorkerSpawnRunner spawnRunner =
        new WorkerSpawnRunner(
            new SandboxHelpers(),
            env.getExecRoot(),
            env.getPackageLocator().getPathEntries(),
            workerPool,
            env.getReporter(),
            localEnvProvider,
            env.getBlazeWorkspace().getBinTools(),
            env.getLocalResourceManager(),
            RunfilesTreeUpdater.forCommandEnvironment(env),
            env.getOptions().getOptions(WorkerOptions.class),
            WorkerProcessMetricsCollector.instance(),
            env.getClock());
    ExecutionOptions executionOptions =
        checkNotNull(env.getOptions().getOptions(ExecutionOptions.class));
    registryBuilder.registerStrategy(
        new WorkerSpawnStrategy(env.getExecRoot(), spawnRunner, executionOptions), "worker");
  }

  @Subscribe
  public void buildComplete(BuildCompleteEvent event) throws InterruptedException {
    WorkerOptions options = env.getOptions().getOptions(WorkerOptions.class);
    if (options != null && options.workerQuitAfterBuild) {
      shutdownPool(
          "Build completed, shutting down worker pool...",
          /* alwaysLog= */ false,
          options.workerVerbose);
    }
    if (workerLifecycleManager != null) {
      workerLifecycleManager.stopProcessing();
      workerLifecycleManager.interrupt();
      workerLifecycleManager = null;
    }
    WorkerProcessMetricsCollector.instance().clearKilledWorkerProcessMetrics();
  }

  /** Shuts down the worker pool and sets {#code workerPool} to null. */
  private void shutdownPool(String reason, boolean alwaysLog, boolean workerVerbose) {
    Preconditions.checkArgument(!reason.isEmpty());

    if (workerPool != null) {
      if (workerVerbose || alwaysLog) {
        env.getReporter().handle(Event.info(reason));
      }
      workerPool.close();
      workerPool = null;
    }
  }

  @Override
  public void afterCommand() {
    this.env = null;

    if (this.workerFactory != null) {
      this.workerFactory.setReporter(null);
    }
    WorkerMultiplexerManager.afterCommand();
  }
}
