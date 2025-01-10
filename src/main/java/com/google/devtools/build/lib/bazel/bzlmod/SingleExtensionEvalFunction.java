// Copyright 2021 The Bazel Authors. All rights reserved.
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

import static com.google.common.collect.ImmutableBiMap.toImmutableBiMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.bazel.bzlmod.RunnableExtension.RunModuleExtensionResult;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.rules.repository.NeedsSkyframeRestartException;
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput;
import com.google.devtools.build.lib.runtime.ProcessWrapper;
import com.google.devtools.build.lib.runtime.RepositoryRemoteExecutor;
import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkSemantics;

/**
 * Evaluates a single module extension. This function loads the .bzl file containing the extension,
 * runs its implementation function with a module_ctx object containing all relevant information,
 * and returns the generated repos.
 */
public class SingleExtensionEvalFunction implements SkyFunction {
  private final BlazeDirectories directories;
  private final Supplier<Map<String, String>> clientEnvironmentSupplier;

  private double timeoutScaling = 1.0;
  @Nullable private ProcessWrapper processWrapper = null;
  @Nullable private RepositoryRemoteExecutor repositoryRemoteExecutor = null;
  @Nullable private DownloadManager downloadManager = null;

  public SingleExtensionEvalFunction(
      BlazeDirectories directories, Supplier<Map<String, String>> clientEnvironmentSupplier) {
    this.directories = directories;
    this.clientEnvironmentSupplier = clientEnvironmentSupplier;
  }

  public void setDownloadManager(DownloadManager downloadManager) {
    this.downloadManager = downloadManager;
  }

  public void setTimeoutScaling(double timeoutScaling) {
    this.timeoutScaling = timeoutScaling;
  }

  public void setProcessWrapper(ProcessWrapper processWrapper) {
    this.processWrapper = processWrapper;
  }

  public void setRepositoryRemoteExecutor(RepositoryRemoteExecutor repositoryRemoteExecutor) {
    this.repositoryRemoteExecutor = repositoryRemoteExecutor;
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SingleExtensionEvalFunctionException, InterruptedException {
    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (starlarkSemantics == null) {
      return null;
    }
    RepositoryMappingValue mainRepoMappingValue =
        (RepositoryMappingValue)
            env.getValue(RepositoryMappingValue.KEY_FOR_ROOT_MODULE_WITHOUT_WORKSPACE_REPOS);
    if (mainRepoMappingValue == null) {
      return null;
    }

    ModuleExtensionId extensionId = (ModuleExtensionId) skyKey.argument();
    SingleExtensionUsagesValue usagesValue =
        (SingleExtensionUsagesValue) env.getValue(SingleExtensionUsagesValue.key(extensionId));
    if (usagesValue == null) {
      return null;
    }
    RunnableExtension extension;
    try {
      if (extensionId.isInnate()) {
        extension =
            InnateRunnableExtension.load(
                extensionId, usagesValue, starlarkSemantics, env, directories);
      } else {
        extension =
            RegularRunnableExtension.load(
                extensionId,
                usagesValue,
                starlarkSemantics,
                env,
                directories,
                clientEnvironmentSupplier,
                timeoutScaling,
                processWrapper,
                repositoryRemoteExecutor,
                downloadManager);
      }
    } catch (ExternalDepsException e) {
      throw new SingleExtensionEvalFunctionException(e);
    }
    if (extension == null) {
      return null;
    }

    // Check the lockfile first for that module extension
    LockFileModuleExtension lockedExtension = null;
    LockfileMode lockfileMode = BazelLockFileFunction.LOCKFILE_MODE.get(env);
    if (!lockfileMode.equals(LockfileMode.OFF)) {
      BazelLockFileValue lockfile = (BazelLockFileValue) env.getValue(BazelLockFileValue.KEY);
      if (lockfile == null) {
        return null;
      }
      var lockedExtensionMap = lockfile.getModuleExtensions().get(extensionId);
      lockedExtension =
          lockedExtensionMap == null ? null : lockedExtensionMap.get(extension.getEvalFactors());
      if (lockedExtension != null) {
        try (SilentCloseable c =
            Profiler.instance()
                .profile(ProfilerTask.BZLMOD, () -> "check lockfile for " + extensionId)) {
          SingleExtensionValue singleExtensionValue =
              tryGettingValueFromLockFile(
                  env,
                  extensionId,
                  extension,
                  usagesValue,
                  extension.getEvalFactors(),
                  lockedExtension);
          if (singleExtensionValue != null) {
            return singleExtensionValue;
          }
        } catch (NeedsSkyframeRestartException e) {
          return null;
        }
      }
    }

    // Run that extension!
    RunModuleExtensionResult moduleExtensionResult;
    try {
      moduleExtensionResult =
          extension.run(
              env,
              usagesValue,
              starlarkSemantics,
              extensionId,
              mainRepoMappingValue.repositoryMapping());
    } catch (ExternalDepsException e) {
      throw new SingleExtensionEvalFunctionException(e);
    }
    if (moduleExtensionResult == null) {
      return null;
    }
    ImmutableMap<String, RepoSpec> generatedRepoSpecs = moduleExtensionResult.generatedRepoSpecs();
    Optional<ModuleExtensionMetadata> moduleExtensionMetadata =
        moduleExtensionResult.moduleExtensionMetadata();

    if (!lockfileMode.equals(LockfileMode.OFF)) {
      var nonVisibleRepoNames =
          moduleExtensionResult.recordedRepoMappingEntries().values().stream()
              .filter(repoName -> !repoName.isVisible())
              .map(RepositoryName::toString)
              .collect(joining(", "));
      if (!nonVisibleRepoNames.isEmpty()) {
        env.getListener()
            .handle(
                Event.warn(
                    String.format(
                        "The module extension %s produced an invalid lockfile entry because it"
                            + " referenced %s. Please report this issue to its maintainers.",
                        extensionId, nonVisibleRepoNames)));
      }
    }
    if (lockfileMode.equals(LockfileMode.ERROR)) {
      boolean extensionShouldHaveBeenLocked =
          moduleExtensionMetadata.map(metadata -> !metadata.getReproducible()).orElse(true);
      // If this extension was not found in the lockfile, and after evaluation we found that it is
      // not reproducible, then error indicating that it was expected to be in the lockfile.
      if (lockedExtension == null && extensionShouldHaveBeenLocked) {
        throw new SingleExtensionEvalFunctionException(
            ExternalDepsException.withMessage(
                Code.BAD_LOCKFILE,
                "The module extension '%s'%s does not exist in the lockfile",
                extensionId,
                extension.getEvalFactors().isEmpty()
                    ? ""
                    : " for platform " + extension.getEvalFactors()));
      }
    }

    Optional<LockFileModuleExtension.WithFactors> lockFileInfo;
    // At this point the extension has been evaluated successfully, but SingleExtensionEvalFunction
    // may still fail if imported repositories were not generated. However, since imports do not
    // influence the evaluation of the extension and the validation also runs when the extension
    // result is taken from the lockfile, we can already populate the lockfile info. This is
    // necessary to prevent the extension from rerunning when only the imports change.
    if (lockfileMode == LockfileMode.UPDATE || lockfileMode == LockfileMode.REFRESH) {
      var envVariables =
          ImmutableMap.<RepoRecordedInput.EnvVar, Optional<String>>builder()
              // The environment variable dependencies statically declared via the 'environ'
              // attribute.
              .putAll(RepoRecordedInput.EnvVar.wrap(extension.getStaticEnvVars()))
              // The environment variable dependencies dynamically declared via the 'getenv' method.
              .putAll(moduleExtensionResult.recordedEnvVarInputs())
              .buildKeepingLast();

      lockFileInfo =
          Optional.of(
              new LockFileModuleExtension.WithFactors(
                  extension.getEvalFactors(),
                  LockFileModuleExtension.builder()
                      .setBzlTransitiveDigest(extension.getBzlTransitiveDigest())
                      .setUsagesDigest(
                          SingleExtensionUsagesValue.hashForEvaluation(
                              GsonTypeAdapterUtil.SINGLE_EXTENSION_USAGES_VALUE_GSON, usagesValue))
                      .setRecordedFileInputs(moduleExtensionResult.recordedFileInputs())
                      .setRecordedDirentsInputs(moduleExtensionResult.recordedDirentsInputs())
                      .setEnvVariables(ImmutableSortedMap.copyOf(envVariables))
                      .setGeneratedRepoSpecs(generatedRepoSpecs)
                      .setModuleExtensionMetadata(moduleExtensionMetadata)
                      .setRecordedRepoMappingEntries(
                          moduleExtensionResult.recordedRepoMappingEntries())
                      .build()));
    } else {
      lockFileInfo = Optional.empty();
    }
    return createSingleExtensionValue(
        generatedRepoSpecs, moduleExtensionMetadata, extensionId, usagesValue, lockFileInfo, env);
  }

  /**
   * Tries to get the evaluation result from the lockfile, if it's still up-to-date. Otherwise,
   * returns {@code null}.
   *
   * @throws NeedsSkyframeRestartException in case we need a skyframe restart. Note that we
   *     <em>don't</em> return {@code null} in this case!
   */
  @Nullable
  private SingleExtensionValue tryGettingValueFromLockFile(
      Environment env,
      ModuleExtensionId extensionId,
      RunnableExtension extension,
      SingleExtensionUsagesValue usagesValue,
      ModuleExtensionEvalFactors evalFactors,
      LockFileModuleExtension lockedExtension)
      throws SingleExtensionEvalFunctionException,
          InterruptedException,
          NeedsSkyframeRestartException {
    LockfileMode lockfileMode = BazelLockFileFunction.LOCKFILE_MODE.get(env);
    DiffRecorder diffRecorder =
        new DiffRecorder(/* recordMessages= */ lockfileMode.equals(LockfileMode.ERROR));
    try {
      // Put faster diff detections earlier, so that we can short-circuit in UPDATE mode.
      if (!Arrays.equals(
          extension.getBzlTransitiveDigest(), lockedExtension.getBzlTransitiveDigest())) {
        diffRecorder.record(
            "The implementation of the extension '"
                + extensionId
                + "' or one of its transitive .bzl files has changed");
      }
      if (didRecordedInputsChange(
          env,
          directories,
          // didRecordedInputsChange expects possibly null String values.
          Maps.transformValues(lockedExtension.getEnvVariables(), v -> v.orElse(null)))) {
        diffRecorder.record(
            "The environment variables the extension '"
                + extensionId
                + "' depends on (or their values) have changed");
      }
      // Check extension data in lockfile is still valid, disregarding usage information that is not
      // relevant for the evaluation of the extension.
      if (!Arrays.equals(
          SingleExtensionUsagesValue.hashForEvaluation(
              GsonTypeAdapterUtil.SINGLE_EXTENSION_USAGES_VALUE_GSON, usagesValue),
          lockedExtension.getUsagesDigest())) {
        diffRecorder.record("The usages of the extension '" + extensionId + "' have changed");
      }
      if (didRepoMappingsChange(env, lockedExtension.getRecordedRepoMappingEntries())) {
        diffRecorder.record(
            "The repo mappings of certain repos used by the extension '"
                + extensionId
                + "' have changed");
      }
      if (didRecordedInputsChange(env, directories, lockedExtension.getRecordedFileInputs())) {
        diffRecorder.record(
            "One or more files the extension '" + extensionId + "' is using have changed");
      }
      if (didRecordedInputsChange(env, directories, lockedExtension.getRecordedDirentsInputs())) {
        diffRecorder.record(
            "One or more directory listings watched by the extension '"
                + extensionId
                + "' have changed");
      }
    } catch (DiffFoundEarlyExitException ignored) {
      // ignored
    }
    if (!diffRecorder.anyDiffsDetected()) {
      return createSingleExtensionValue(
          lockedExtension.getGeneratedRepoSpecs(),
          lockedExtension.getModuleExtensionMetadata(),
          extensionId,
          usagesValue,
          Optional.of(new LockFileModuleExtension.WithFactors(evalFactors, lockedExtension)),
          env);
    }
    if (lockfileMode.equals(LockfileMode.ERROR)) {
      throw new SingleExtensionEvalFunctionException(
          ExternalDepsException.withMessage(
              Code.BAD_LOCKFILE,
              "MODULE.bazel.lock is no longer up-to-date because: %s. "
                  + "Please run `bazel mod deps --lockfile_mode=update` to update your lockfile.",
              diffRecorder.getRecordedDiffMessages()));
    }
    return null;
  }

  private static final class DiffFoundEarlyExitException extends Exception {}

  private static final class DiffRecorder {
    private boolean diffDetected = false;
    private final ImmutableList.Builder<String> diffMessages;

    DiffRecorder(boolean recordMessages) {
      diffMessages = recordMessages ? ImmutableList.builder() : null;
    }

    private void record(String message) throws DiffFoundEarlyExitException {
      diffDetected = true;
      if (diffMessages != null) {
        diffMessages.add(message);
      } else {
        throw new DiffFoundEarlyExitException();
      }
    }

    public boolean anyDiffsDetected() {
      return diffDetected;
    }

    public String getRecordedDiffMessages() {
      return String.join(",", diffMessages.build());
    }
  }

  private static boolean didRepoMappingsChange(
      Environment env, ImmutableTable<RepositoryName, String, RepositoryName> recordedRepoMappings)
      throws InterruptedException, NeedsSkyframeRestartException {
    // Request repo mappings for any 'source repos' in the recorded mapping entries.
    // Note specially that the main repo needs to be treated differently: if any .bzl file from the
    // main repo was used for module extension eval, it _has_ to be before WORKSPACE is evaluated
    // (see relevant code in BzlLoadFunction#getRepositoryMapping), so we only request the main repo
    // mapping _without_ WORKSPACE repos. See #20942 for more information.
    SkyframeLookupResult result =
        env.getValuesAndExceptions(
            recordedRepoMappings.rowKeySet().stream()
                .map(
                    repoName ->
                        repoName.isMain()
                            ? RepositoryMappingValue.KEY_FOR_ROOT_MODULE_WITHOUT_WORKSPACE_REPOS
                            : RepositoryMappingValue.key(repoName))
                .collect(toImmutableSet()));
    if (env.valuesMissing()) {
      // This likely means that one of the 'source repos' in the recorded mapping entries is no
      // longer there.
      throw new NeedsSkyframeRestartException();
    }
    for (Table.Cell<RepositoryName, String, RepositoryName> cell : recordedRepoMappings.cellSet()) {
      RepositoryMappingValue repoMappingValue =
          (RepositoryMappingValue)
              result.get(
                  cell.getRowKey().isMain()
                      ? RepositoryMappingValue.KEY_FOR_ROOT_MODULE_WITHOUT_WORKSPACE_REPOS
                      : RepositoryMappingValue.key(cell.getRowKey()));
      if (repoMappingValue == null) {
        throw new NeedsSkyframeRestartException();
      }
      // Very importantly, `repoMappingValue` here could be for a repo that's no longer existent in
      // the dep graph. See
      // bazel_lockfile_test.testExtensionRepoMappingChange_sourceRepoNoLongerExistent for a test
      // case.
      if (repoMappingValue.equals(RepositoryMappingValue.NOT_FOUND_VALUE)
          || !cell.getValue()
              .equals(repoMappingValue.repositoryMapping().get(cell.getColumnKey()))) {
        // Wee woo wee woo -- diff detected!
        return true;
      }
    }
    return false;
  }

  private static boolean didRecordedInputsChange(
      Environment env,
      BlazeDirectories directories,
      Map<? extends RepoRecordedInput, String> recordedInputs)
      throws InterruptedException, NeedsSkyframeRestartException {
    Optional<String> outdated =
        RepoRecordedInput.isAnyValueOutdated(env, directories, recordedInputs);
    if (env.valuesMissing()) {
      throw new NeedsSkyframeRestartException();
    }
    return outdated.isPresent();
  }

  /**
   * Validates the result of the module extension evaluation against the declared imports, throwing
   * an exception if validation fails, and returns a SingleExtensionValue otherwise.
   *
   * <p>Since extension evaluation does not depend on the declared imports, the result of the
   * evaluation of the extension implementation function can be reused and persisted in the lockfile
   * even if validation fails.
   */
  private SingleExtensionValue createSingleExtensionValue(
      ImmutableMap<String, RepoSpec> generatedRepoSpecs,
      Optional<ModuleExtensionMetadata> moduleExtensionMetadata,
      ModuleExtensionId extensionId,
      SingleExtensionUsagesValue usagesValue,
      Optional<LockFileModuleExtension.WithFactors> lockFileInfo,
      Environment env)
      throws SingleExtensionEvalFunctionException {
    Optional<RootModuleFileFixup> fixup = Optional.empty();
    if (moduleExtensionMetadata.isPresent()
        && usagesValue.getExtensionUsages().containsKey(ModuleKey.ROOT)) {
      try {
        // TODO: ModuleExtensionMetadata#generateFixup should throw ExternalDepsException instead of
        // EvalException.
        fixup =
            moduleExtensionMetadata
                .get()
                .generateFixup(
                    usagesValue.getExtensionUsages().get(ModuleKey.ROOT),
                    generatedRepoSpecs.keySet());
      } catch (EvalException e) {
        env.getListener().handle(Event.error(e.getInnermostLocation(), e.getMessageWithStack()));
        throw new SingleExtensionEvalFunctionException(
            ExternalDepsException.withMessage(
                Code.BAD_MODULE,
                "error evaluating module extension %s in %s",
                extensionId.extensionName(),
                extensionId.bzlFileLabel()));
      }
    }

    return SingleExtensionValue.create(
        generatedRepoSpecs,
        generatedRepoSpecs.keySet().stream()
            .collect(
                toImmutableBiMap(
                    e ->
                        RepositoryName.createUnvalidated(
                            usagesValue.getExtensionUniqueName() + "+" + e),
                    Function.identity())),
        lockFileInfo,
        fixup);
  }

  private static final class SingleExtensionEvalFunctionException extends SkyFunctionException {
    SingleExtensionEvalFunctionException(ExternalDepsException cause) {
      super(cause, Transience.PERSISTENT);
    }
  }
}
