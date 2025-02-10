// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.includescanning;

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactFactory;
import com.google.devtools.build.lib.actions.ArtifactResolver;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.BuildRequestOptions;
import com.google.devtools.build.lib.concurrent.ExecutorUtil;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadHostile;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.exec.ExecutorBuilder;
import com.google.devtools.build.lib.exec.ExecutorLifecycleListener;
import com.google.devtools.build.lib.exec.ModuleActionContextRegistry;
import com.google.devtools.build.lib.includescanning.IncludeParser.Inclusion;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.rules.cpp.CppIncludeExtractionContext;
import com.google.devtools.build.lib.rules.cpp.CppIncludeScanningContext;
import com.google.devtools.build.lib.rules.cpp.CppOptions;
import com.google.devtools.build.lib.rules.cpp.IncludeScanner.IncludeScanningHeaderData;
import com.google.devtools.build.lib.rules.cpp.SwigIncludeScanningContext;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.WorkspaceBuilder;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.IncludeScanning;
import com.google.devtools.build.lib.skyframe.EphemeralCheckIfOutputConsumed;
import com.google.devtools.build.lib.skyframe.MutableSupplier;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.vfs.IORuntimeException;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.common.options.OptionsBase;
import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Module that provides implementations of {@link CppIncludeExtractionContext}, {@link
 * CppIncludeScanningContext}, and {@link SwigIncludeScanningContext}.
 */
public class IncludeScanningModule extends BlazeModule {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final MutableSupplier<SpawnIncludeScanner> spawnIncludeScannerSupplier =
      new MutableSupplier<>();
  private final MutableSupplier<ArtifactFactory> artifactFactory = new MutableSupplier<>();
  private IncludeScannerLifecycleManager lifecycleManager;

  @Nullable
  protected PathFragment getIncludeHintsFilename() {
    return null;
  }

  @Override
  @ThreadHostile
  public void registerActionContexts(
      ModuleActionContextRegistry.Builder registryBuilder,
      CommandEnvironment env,
      BuildRequest buildRequest) {
    registryBuilder
        .register(CppIncludeExtractionContext.class, new CppIncludeExtractionContextImpl(env))
        .register(SwigIncludeScanningContext.class, lifecycleManager.getSwigActionContext())
        .register(CppIncludeScanningContext.class, lifecycleManager.getCppActionContext());
    registryBuilder
        .restrictTo(CppIncludeExtractionContext.class, "")
        .restrictTo(SwigIncludeScanningContext.class, "")
        .restrictTo(CppIncludeScanningContext.class, "");
  }

  @Override
  @ThreadHostile
  public void executorInit(CommandEnvironment env, BuildRequest request, ExecutorBuilder builder) {
    lifecycleManager =
        new IncludeScannerLifecycleManager(
            env, request, spawnIncludeScannerSupplier, getIncludeHintsFilename() != null);
    builder.addExecutorLifecycleListener(lifecycleManager);
  }

  @Override
  public Iterable<Class<? extends OptionsBase>> getCommandOptions(Command command) {
    return "build".equals(command.name())
        ? ImmutableList.of(IncludeScanningOptions.class)
        : ImmutableList.of();
  }

  @Override
  public void beforeCommand(CommandEnvironment env) {
    CppOptions cppOptions = env.getOptions().getOptions(CppOptions.class);
    if (cppOptions != null && cppOptions.experimentalIncludeScanning) {
      env.getReporter()
          .handle(Event.warn("Include scanning enabled. This feature is unsupported."));
    }
    artifactFactory.set(env.getSkyframeBuildView().getArtifactFactory());
  }

  @Override
  public void afterCommand() {
    spawnIncludeScannerSupplier.set(null);
    artifactFactory.set(null);
    lifecycleManager = null;
  }

  @Override
  public void workspaceInit(
      BlazeRuntime runtime, BlazeDirectories directories, WorkspaceBuilder builder) {
    builder.addSkyFunctions(getSkyFunctions(getIncludeHintsFilename()));
  }

  @VisibleForTesting
  public static ImmutableMap<SkyFunctionName, SkyFunction> getSkyFunctions(
      @Nullable PathFragment includeHintsFile) {
    ImmutableMap.Builder<SkyFunctionName, SkyFunction> skyFunctions = ImmutableMap.builder();
    if (includeHintsFile != null) {
      skyFunctions.put(
          IncludeScanningSkyFunctions.INCLUDE_HINTS, new IncludeHintsFunction(includeHintsFile));
    }
    return skyFunctions.buildOrThrow();
  }

  /** Implementation of {@link CppIncludeExtractionContext}. */
  public static final class CppIncludeExtractionContextImpl implements CppIncludeExtractionContext {
    private final CommandEnvironment env;

    CppIncludeExtractionContextImpl(CommandEnvironment env) {
      this.env = env;
    }

    @Override
    public ArtifactResolver getArtifactResolver() {
      return env.getSkyframeBuildView().getArtifactFactory();
    }
  }

  /** SwigIncludeScanningContextImpl implements SwigIncludeScanningContext. */
  public static final class SwigIncludeScanningContextImpl implements SwigIncludeScanningContext {
    private final CommandEnvironment env;
    private final Supplier<SpawnIncludeScanner> spawnScannerSupplier;
    private final Supplier<ExecutorService> includePool;
    private final ConcurrentMap<Artifact, ListenableFuture<Collection<Inclusion>>> cache =
        new ConcurrentHashMap<>();

    SwigIncludeScanningContextImpl(
        CommandEnvironment env,
        Supplier<SpawnIncludeScanner> spawnScannerSupplier,
        Supplier<ExecutorService> includePool) {
      this.env = env;
      this.spawnScannerSupplier = spawnScannerSupplier;
      this.includePool = includePool;
    }

    @Override
    public void extractSwigIncludes(
        Set<Artifact> includes,
        ActionExecutionMetadata actionExecutionMetadata,
        ActionExecutionContext actionExecContext,
        Artifact source,
        ImmutableSet<Artifact> legalOutputPaths,
        ImmutableList<PathFragment> swigIncludePaths,
        Artifact grepIncludes)
        throws IOException, ExecException, InterruptedException {
      SwigIncludeScanner scanner =
          new SwigIncludeScanner(
              includePool.get(),
              shouldShuffle(env),
              spawnScannerSupplier.get(),
              cache,
              swigIncludePaths,
              env.getDirectories(),
              env.getSkyframeBuildView().getArtifactFactory(),
              env.getExecRoot());
      // For Swig include scanning, just point to the output file in the map.
      ImmutableMap<PathFragment, Artifact> pathToDeclaredHeader =
          legalOutputPaths.stream()
              .collect(
                  toImmutableMap(
                      Artifact::getExecPath,
                      artifact -> artifact,
                      // Headers may be generated by shared actions. If both shared actions' outputs
                      // are present, just use the first (b/304564144).
                      (a, b) -> a));
      try {
        scanner.processAsync(
            source,
            ImmutableList.of(source),
            new IncludeScanningHeaderData.Builder(
                    pathToDeclaredHeader, /* modularHeaders= */ ImmutableSet.of())
                .build(),
            ImmutableList.of(),
            includes,
            actionExecutionMetadata,
            actionExecContext,
            grepIncludes);
      } catch (IORuntimeException e) {
        throw e.getCauseIOException();
      } catch (NoSuchPackageException e) {
        throw new IllegalStateException("Swig has no hints! For " + source, e);
      }
    }
  }

  /**
   * Lifecycle manager for the include scanner. Maintains an {@linkplain IncludeScannerSupplier
   * supplier} which can be used to access the (potentially shared) scanners and exposes {@linkplain
   * #getSwigActionContext() action} {@linkplain #getCppActionContext() contexts} based on them.
   */
  private static final class IncludeScannerLifecycleManager implements ExecutorLifecycleListener {
    private final CommandEnvironment env;
    private final IncludeScanningOptions options;
    private final boolean useIncludeHints;

    private final Supplier<SpawnIncludeScanner> spawnScannerSupplier;
    private IncludeScannerSupplier includeScannerSupplier;
    private ExecutorService includePool;

    IncludeScannerLifecycleManager(
        CommandEnvironment env,
        BuildRequest buildRequest,
        MutableSupplier<SpawnIncludeScanner> spawnScannerSupplier,
        boolean useIncludeHints) {
      this.env = env;
      this.options = buildRequest.getOptions(IncludeScanningOptions.class);
      this.useIncludeHints = useIncludeHints;

      spawnScannerSupplier.set(
          new SpawnIncludeScanner(
              env.getExecRoot(),
              options.experimentalRemoteExtractionThreshold,
              env.getSyscallCache()));
      this.spawnScannerSupplier = spawnScannerSupplier;
      env.getEventBus().register(this);
    }

    private CppIncludeScanningContextImpl getCppActionContext() {
      return new CppIncludeScanningContextImpl(() -> includeScannerSupplier);
    }

    private SwigIncludeScanningContextImpl getSwigActionContext() {
      return new SwigIncludeScanningContextImpl(env, spawnScannerSupplier, () -> includePool);
    }

    @Override
    public void executionPhaseStarting(
        ActionGraph unusedActionGraph,
        Supplier<ImmutableSet<Artifact>> unusedTopLevelArtifacts,
        @Nullable EphemeralCheckIfOutputConsumed unusedCheck)
        throws AbruptExitException, InterruptedException {
      IncludeParser.HintsRules hintsRules;
      if (useIncludeHints) {
        try (var sc = Profiler.instance().profile("evaluateSkyKeyForExecutionSetup")) {
          hintsRules =
              (IncludeParser.HintsRules)
                  env.getSkyframeExecutor()
                      .evaluateSkyKeyForExecutionSetup(
                          env.getReporter(), IncludeHintsFunction.INCLUDE_HINTS_KEY);
        } catch (ExecException e) {
          throw new AbruptExitException(
              DetailedExitCode.of(
                  FailureDetail.newBuilder()
                      .setMessage("could not initialize include hints: " + e.getMessage())
                      .setIncludeScanning(
                          IncludeScanning.newBuilder()
                              .setCode(IncludeScanning.Code.INITIALIZE_INCLUDE_HINTS_ERROR))
                      .build()),
              e);
        }
      } else {
        hintsRules = IncludeParser.HintsRules.EMPTY;
      }
      includeScannerSupplier.init(
          new IncludeParser(
              new IncludeParser.Hints(
                  hintsRules,
                  env.getSyscallCache(),
                  env.getSkyframeBuildView().getArtifactFactory())));
    }

    @Override
    public void executionPhaseEnding() {
      if (options.experimentalReuseIncludeScanningThreads) {
        if (includePool != null && !includePool.isShutdown()) {
          ExecutorUtil.uninterruptibleShutdownNow(includePool);
        }
        includePool = null;
      }
    }

    @SuppressWarnings("AllowVirtualThreads")
    @Override
    public void executorCreated() {
      var useAsyncExecution = useAsyncExecution(env);
      int threads = options.includeScanningParallelism;
      if (useAsyncExecution) {
        includePool =
            Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("Include scanner ", 0).factory());
      } else if (threads > 0) {
        logger.atInfo().log("Include scanning configured to use a pool with %d threads", threads);
        if (options.experimentalReuseIncludeScanningThreads) {
          includePool =
              new ThreadPoolExecutor(
                  threads,
                  threads,
                  0L,
                  TimeUnit.SECONDS,
                  new SynchronousQueue<>(),
                  new ThreadFactoryBuilder().setNameFormat("Include scanner %d").build(),
                  (r, e) -> r.run());
        } else {
          includePool = ExecutorUtil.newSlackPool(threads, "Include scanner");
        }

      } else {
        logger.atInfo().log("Include scanning configured to use a direct executor");
        includePool = MoreExecutors.newDirectExecutorService();
      }
      includeScannerSupplier =
          new IncludeScannerSupplier(
              env.getDirectories(),
              includePool,
              shouldShuffle(env),
              env.getSkyframeBuildView().getArtifactFactory(),
              spawnScannerSupplier,
              env.getExecRoot());

      spawnScannerSupplier.get().setOutputService(env.getOutputService());
      spawnScannerSupplier.get().setInMemoryOutput(options.inMemoryIncludesFiles);
    }
  }

  private static boolean useAsyncExecution(CommandEnvironment env) {
    var buildRequestOptions = env.getOptions().getOptions(BuildRequestOptions.class);
    return buildRequestOptions != null && buildRequestOptions.useAsyncExecution;
  }

  private static boolean shouldShuffle(CommandEnvironment env) {
    // Don't shuffle if using virtual threads, otherwise it introduces high CPU regression on
    // machines with large number of cores.
    return !useAsyncExecution(env);
  }
}
