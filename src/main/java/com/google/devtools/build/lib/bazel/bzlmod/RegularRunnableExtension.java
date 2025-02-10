// Copyright 2024 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.base.StandardSystemProperty.OS_ARCH;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.rules.repository.NeedsSkyframeRestartException;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction;
import com.google.devtools.build.lib.runtime.ProcessWrapper;
import com.google.devtools.build.lib.runtime.RepositoryRemoteExecutor;
import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps;
import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code;
import com.google.devtools.build.lib.skyframe.BzlLoadFailedException;
import com.google.devtools.build.lib.skyframe.BzlLoadFunction;
import com.google.devtools.build.lib.skyframe.BzlLoadValue;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.WorkerSkyKeyComputeState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.SymbolGenerator;
import net.starlark.java.spelling.SpellChecker;
import net.starlark.java.syntax.Location;

/**
 * A regular module extension defined with {@code module_extension} and used with {@code
 * use_extension}.
 */
final class RegularRunnableExtension implements RunnableExtension {
  private final BzlLoadValue bzlLoadValue;
  private final ModuleExtension extension;
  private final ImmutableMap<String, Optional<String>> staticEnvVars;
  private final BlazeDirectories directories;
  private final Supplier<Map<String, String>> repoEnvironmentSupplier;
  private final double timeoutScaling;
  @Nullable private final ProcessWrapper processWrapper;
  @Nullable private final RepositoryRemoteExecutor repositoryRemoteExecutor;
  @Nullable private final DownloadManager downloadManager;

  RegularRunnableExtension(
      BzlLoadValue bzlLoadValue,
      ModuleExtension extension,
      ImmutableMap<String, Optional<String>> staticEnvVars,
      BlazeDirectories directories,
      Supplier<Map<String, String>> repoEnvironmentSupplier,
      double timeoutScaling,
      @Nullable ProcessWrapper processWrapper,
      @Nullable RepositoryRemoteExecutor repositoryRemoteExecutor,
      @Nullable DownloadManager downloadManager) {
    this.bzlLoadValue = bzlLoadValue;
    this.extension = extension;
    this.staticEnvVars = staticEnvVars;
    this.directories = directories;
    this.repoEnvironmentSupplier = repoEnvironmentSupplier;
    this.timeoutScaling = timeoutScaling;
    this.processWrapper = processWrapper;
    this.repositoryRemoteExecutor = repositoryRemoteExecutor;
    this.downloadManager = downloadManager;
  }

  private static BzlLoadValue loadBzlFile(
      Label bzlFileLabel,
      Location sampleUsageLocation,
      StarlarkSemantics starlarkSemantics,
      Environment env)
      throws ExternalDepsException, InterruptedException {
    // Check that the .bzl label isn't crazy.
    try {
      BzlLoadFunction.checkValidLoadLabel(bzlFileLabel, starlarkSemantics);
    } catch (LabelSyntaxException e) {
      throw ExternalDepsException.withCauseAndMessage(
          Code.BAD_MODULE, e, "invalid module extension label");
    }

    // Load the .bzl file pointed to by the label.
    BzlLoadValue bzlLoadValue;
    try {
      bzlLoadValue =
          (BzlLoadValue)
              env.getValueOrThrow(
                  BzlLoadValue.keyForBzlmod(bzlFileLabel), BzlLoadFailedException.class);
    } catch (BzlLoadFailedException e) {
      throw ExternalDepsException.withCauseAndMessage(
          Code.BAD_MODULE,
          e,
          "Error loading '%s' for module extensions, requested by %s: %s",
          bzlFileLabel,
          sampleUsageLocation,
          e.getMessage());
    }
    return bzlLoadValue;
  }

  /** Returns null if a Skyframe restart is required. */
  @Nullable
  static RegularRunnableExtension load(
      ModuleExtensionId extensionId,
      SingleExtensionUsagesValue usagesValue,
      StarlarkSemantics starlarkSemantics,
      Environment env,
      BlazeDirectories directories,
      Supplier<Map<String, String>> repoEnvironmentSupplier,
      double timeoutScaling,
      @Nullable ProcessWrapper processWrapper,
      @Nullable RepositoryRemoteExecutor repositoryRemoteExecutor,
      @Nullable DownloadManager downloadManager)
      throws InterruptedException, ExternalDepsException {
    ModuleExtensionUsage sampleUsage = usagesValue.getExtensionUsages().values().iterator().next();
    Location sampleUsageLocation = sampleUsage.getProxies().getFirst().getLocation();
    BzlLoadValue bzlLoadValue =
        loadBzlFile(extensionId.bzlFileLabel(), sampleUsageLocation, starlarkSemantics, env);
    if (bzlLoadValue == null) {
      return null;
    }
    // TODO(wyv): Consider whether there's a need to check .bzl load visibility
    // (BzlLoadFunction#checkLoadVisibilities).
    // TODO(wyv): Consider refactoring to use PackageFunction#loadBzlModules, or the simpler API
    // that may be created by b/237658764.

    // Check that the .bzl file actually exports a module extension by our name.
    Object exported = bzlLoadValue.getModule().getGlobal(extensionId.extensionName());
    if (!(exported instanceof ModuleExtension extension)) {
      ImmutableSet<String> exportedExtensions =
          bzlLoadValue.getModule().getGlobals().entrySet().stream()
              .filter(e -> e.getValue() instanceof ModuleExtension)
              .map(Entry::getKey)
              .collect(toImmutableSet());
      throw ExternalDepsException.withMessage(
          Code.BAD_MODULE,
          "%s does not export a module extension called %s, yet its use is requested at %s%s",
          extensionId.bzlFileLabel(),
          extensionId.extensionName(),
          sampleUsageLocation,
          SpellChecker.didYouMean(extensionId.extensionName(), exportedExtensions));
    }

    // TODO Handle environ deps not in repo env supplier (non-default env vars)
    ImmutableMap<String, Optional<String>> envVars =
        RepositoryFunction.getEnvVarValues(env, ImmutableSet.copyOf(extension.envVariables()));
    if (envVars == null) {
      return null;
    }
    return new RegularRunnableExtension(
        bzlLoadValue,
        extension,
        envVars,
        directories,
        repoEnvironmentSupplier,
        timeoutScaling,
        processWrapper,
        repositoryRemoteExecutor,
        downloadManager);
  }

  @Override
  public ModuleExtensionEvalFactors getEvalFactors() {
    return ModuleExtensionEvalFactors.create(
        extension.osDependent() ? OS.getCurrent().toString() : "",
        extension.archDependent() ? OS_ARCH.value() : "");
  }

  @Override
  public ImmutableMap<String, Optional<String>> getStaticEnvVars() {
    return staticEnvVars;
  }

  @Override
  public byte[] getBzlTransitiveDigest() {
    return BazelModuleContext.of(bzlLoadValue.getModule()).bzlTransitiveDigest();
  }

  @Nullable
  @Override
  public RunModuleExtensionResult run(
      Environment env,
      SingleExtensionUsagesValue usagesValue,
      StarlarkSemantics starlarkSemantics,
      ModuleExtensionId extensionId,
      RepositoryMapping mainRepositoryMapping)
      throws InterruptedException, ExternalDepsException {
    // See below (the `catch CancellationException` clause) for why there's a `while` loop here.
    while (true) {
      var state = env.getState(WorkerSkyKeyComputeState<RunModuleExtensionResult>::new);
      try {
        return state.startOrContinueWork(
            env,
            "module-extension-" + extensionId,
            (workerEnv) ->
                runInternal(
                    workerEnv, usagesValue, starlarkSemantics, extensionId, mainRepositoryMapping));
      } catch (ExecutionException e) {
        Throwables.throwIfInstanceOf(e.getCause(), ExternalDepsException.class);
        Throwables.throwIfInstanceOf(e.getCause(), InterruptedException.class);
        Throwables.throwIfUnchecked(e.getCause());
        throw new IllegalStateException(
            "unexpected exception type: " + e.getCause().getClass(), e.getCause());
      } catch (CancellationException e) {
        // This can only happen if the state object was invalidated due to memory pressure, in
        // which case we can simply reattempt eval.
      }
    }
  }

  @Nullable
  private RunModuleExtensionResult runInternal(
      Environment env,
      SingleExtensionUsagesValue usagesValue,
      StarlarkSemantics starlarkSemantics,
      ModuleExtensionId extensionId,
      RepositoryMapping mainRepositoryMapping)
      throws InterruptedException, ExternalDepsException {
    env.getListener().post(ModuleExtensionEvaluationProgress.ongoing(extensionId, "starting"));
    ModuleExtensionEvalStarlarkThreadContext threadContext =
        new ModuleExtensionEvalStarlarkThreadContext(
            extensionId,
            usagesValue.getExtensionUniqueName() + "+",
            extensionId.bzlFileLabel().getPackageIdentifier(),
            BazelModuleContext.of(bzlLoadValue.getModule()).repoMapping(),
            usagesValue.getRepoOverrides(),
            mainRepositoryMapping,
            directories,
            env.getListener());
    Optional<ModuleExtensionMetadata> moduleExtensionMetadata;
    var repoMappingRecorder = new Label.RepoMappingRecorder();
    repoMappingRecorder.mergeEntries(bzlLoadValue.getRecordedRepoMappings());
    try (Mutability mu =
            Mutability.create("module extension", usagesValue.getExtensionUniqueName());
        ModuleExtensionContext moduleContext =
            createContext(env, usagesValue, starlarkSemantics, extensionId, repoMappingRecorder)) {
      StarlarkThread thread =
          StarlarkThread.create(
              mu,
              starlarkSemantics,
              /* contextDescription= */ "",
              SymbolGenerator.create(extensionId));
      thread.setPrintHandler(Event.makeDebugPrintHandler(env.getListener()));
      threadContext.storeInThread(thread);
      // This is used by the `Label()` constructor in Starlark, to record any attempts to resolve
      // apparent repo names to canonical repo names. See #20721 for why this is necessary.
      thread.setThreadLocal(Label.RepoMappingRecorder.class, repoMappingRecorder);
      try (SilentCloseable c =
          Profiler.instance()
              .profile(ProfilerTask.BZLMOD, () -> "evaluate module extension: " + extensionId)) {
        Object returnValue =
            Starlark.positionalOnlyCall(thread, extension.implementation(), moduleContext);
        if (returnValue != Starlark.NONE && !(returnValue instanceof ModuleExtensionMetadata)) {
          throw ExternalDepsException.withMessage(
              ExternalDeps.Code.BAD_MODULE,
              "expected module extension %s to return None or extension_metadata, got %s",
              extensionId,
              Starlark.type(returnValue));
        }
        if (returnValue instanceof ModuleExtensionMetadata retMetadata) {
          moduleExtensionMetadata = Optional.of(retMetadata);
        } else {
          moduleExtensionMetadata = Optional.empty();
        }
      } catch (NeedsSkyframeRestartException e) {
        // Restart by returning null.
        return null;
      }
      moduleContext.markSuccessful();
      env.getListener().post(ModuleExtensionEvaluationProgress.finished(extensionId));
      return new RunModuleExtensionResult(
          moduleContext.getRecordedFileInputs(),
          moduleContext.getRecordedDirentsInputs(),
          moduleContext.getRecordedEnvVarInputs(),
          threadContext.createRepos(starlarkSemantics),
          moduleExtensionMetadata,
          repoMappingRecorder.recordedEntries());
    } catch (EvalException e) {
      env.getListener().handle(Event.error(e.getInnermostLocation(), e.getMessageWithStack()));
      throw ExternalDepsException.withMessage(
          ExternalDeps.Code.BAD_MODULE, "error evaluating module extension %s", extensionId);
    } catch (IOException e) {
      throw ExternalDepsException.withCauseAndMessage(
          ExternalDeps.Code.EXTERNAL_DEPS_UNKNOWN,
          e,
          "Failed to clean up module context directory");
    }
  }

  private ModuleExtensionContext createContext(
      Environment env,
      SingleExtensionUsagesValue usagesValue,
      StarlarkSemantics starlarkSemantics,
      ModuleExtensionId extensionId,
      Label.RepoMappingRecorder repoMappingRecorder)
      throws ExternalDepsException {
    Path workingDirectory =
        directories
            .getOutputBase()
            .getRelative(LabelConstants.MODULE_EXTENSION_WORKING_DIRECTORY_LOCATION)
            .getRelative(usagesValue.getExtensionUniqueName());
    ArrayList<StarlarkBazelModule> modules = new ArrayList<>();
    for (AbridgedModule abridgedModule : usagesValue.getAbridgedModules()) {
      ModuleKey moduleKey = abridgedModule.getKey();
      modules.add(
          StarlarkBazelModule.create(
              abridgedModule,
              extension,
              usagesValue.getRepoMappings().get(moduleKey),
              usagesValue.getExtensionUsages().get(moduleKey),
              repoMappingRecorder));
    }
    ModuleExtensionUsage rootUsage = usagesValue.getExtensionUsages().get(ModuleKey.ROOT);
    boolean rootModuleHasNonDevDependency =
        rootUsage != null && rootUsage.getHasNonDevUseExtension();
    return new ModuleExtensionContext(
        workingDirectory,
        directories,
        env,
        // TODO Add clientEnvironmentSupplier for `getenv` dynamic deps
        repoEnvironmentSupplier.get(),
        downloadManager,
        timeoutScaling,
        processWrapper,
        starlarkSemantics,
        repositoryRemoteExecutor,
        extensionId,
        StarlarkList.immutableCopyOf(modules),
        rootModuleHasNonDevDependency);
  }
}
