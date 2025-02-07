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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.actions.ThreadStateReceiver;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.io.FileSymlinkException;
import com.google.devtools.build.lib.io.InconsistentFilesystemException;
import com.google.devtools.build.lib.packages.AutoloadSymbols;
import com.google.devtools.build.lib.packages.BuildFileContainsErrorsException;
import com.google.devtools.build.lib.packages.BuildFileNotFoundException;
import com.google.devtools.build.lib.packages.CachingPackageLocator;
import com.google.devtools.build.lib.packages.Globber;
import com.google.devtools.build.lib.packages.InvalidPackageNameException;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NonSkyframeGlobber;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Package.ConfigSettingVisibilityPolicy;
import com.google.devtools.build.lib.packages.PackageArgs;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.PackageValidator.InvalidPackageException;
import com.google.devtools.build.lib.packages.RuleVisibility;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Filesystem;
import com.google.devtools.build.lib.server.FailureDetails.PackageLoading;
import com.google.devtools.build.lib.server.FailureDetails.PackageLoading.Code;
import com.google.devtools.build.lib.skyframe.IgnoredSubdirectoriesValue.InvalidIgnorePathException;
import com.google.devtools.build.lib.skyframe.PackageFunctionWithMultipleGlobDeps.SkyframeGlobbingIOException;
import com.google.devtools.build.lib.skyframe.RepoFileFunction.BadRepoFileException;
import com.google.devtools.build.lib.skyframe.RepoPackageArgsFunction.RepoPackageArgsValue;
import com.google.devtools.build.lib.skyframe.StarlarkBuiltinsFunction.BuiltinsFailedException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.DetailedIOException;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.ForOverride;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.SyntaxError;

/** A SkyFunction for {@link PackageValue}s. */
public abstract class PackageFunction implements SkyFunction {

  protected final PackageFactory packageFactory;
  protected final CachingPackageLocator packageLocator;
  private final AtomicBoolean showLoadingProgress;
  private final AtomicInteger numPackagesSuccessfullyLoaded;
  @Nullable private final PackageProgressReceiver packageProgress;

  // Not final only for testing.
  @Nullable private BzlLoadFunction bzlLoadFunctionForInlining;

  private final ActionOnIOExceptionReadingBuildFile actionOnIOExceptionReadingBuildFile;

  private final ActionOnFilesystemErrorCodeLoadingBzlFile actionOnFilesystemErrorCodeLoadingBzlFile;

  private final boolean shouldUseRepoDotBazel;

  protected final Function<SkyKey, ThreadStateReceiver> threadStateReceiverFactoryForMetrics;

  private final AtomicReference<Semaphore> cpuBoundSemaphore;

  /**
   * CompiledBuildFile holds information extracted from the BUILD syntax tree before it was
   * discarded, such as the compiled program, its glob literals, and its mapping from each function
   * call site to its {@code generator_name} attribute value.
   */
  // TODO(adonovan): when we split PackageCompileFunction out, move this there, and make it
  // non-public. (Since CompiledBuildFile contains a Module (the prelude), when we split it out,
  // the code path that requests it will have to support inlining a la BzlLoadFunction.)
  public static class CompiledBuildFile {
    // Either errors is null, or all the other fields are.
    @Nullable private final ImmutableList<SyntaxError> errors;
    @Nullable private final Program prog;
    @Nullable private final ImmutableList<String> globs;
    @Nullable private final ImmutableList<String> globsWithDirs;
    @Nullable private final ImmutableList<String> subpackages;
    @Nullable private final ImmutableMap<Location, String> generatorMap;
    @Nullable private final ImmutableMap<String, Object> predeclared;

    boolean ok() {
      return prog != null;
    }

    // success
    CompiledBuildFile(
        Program prog,
        ImmutableList<String> globs,
        ImmutableList<String> globsWithDirs,
        ImmutableList<String> subpackages,
        ImmutableMap<Location, String> generatorMap,
        ImmutableMap<String, Object> predeclared) {
      this.errors = null;
      this.prog = prog;
      this.globs = globs;
      this.subpackages = subpackages;
      this.globsWithDirs = globsWithDirs;
      this.generatorMap = generatorMap;
      this.predeclared = predeclared;
    }

    // failure
    CompiledBuildFile(List<SyntaxError> errors) {
      this.errors = ImmutableList.copyOf(errors);
      this.prog = null;
      this.globs = null;
      this.globsWithDirs = null;
      this.subpackages = null;
      this.generatorMap = null;
      this.predeclared = null;
    }
  }

  protected PackageFunction(
      PackageFactory packageFactory,
      CachingPackageLocator pkgLocator,
      AtomicBoolean showLoadingProgress,
      AtomicInteger numPackagesSuccessfullyLoaded,
      @Nullable BzlLoadFunction bzlLoadFunctionForInlining,
      @Nullable PackageProgressReceiver packageProgress,
      ActionOnIOExceptionReadingBuildFile actionOnIOExceptionReadingBuildFile,
      ActionOnFilesystemErrorCodeLoadingBzlFile actionOnFilesystemErrorCodeLoadingBzlFile,
      boolean shouldUseRepoDotBazel,
      Function<SkyKey, ThreadStateReceiver> threadStateReceiverFactoryForMetrics,
      AtomicReference<Semaphore> cpuBoundSemaphore) {
    this.bzlLoadFunctionForInlining = bzlLoadFunctionForInlining;
    this.packageFactory = packageFactory;
    this.packageLocator = pkgLocator;
    this.showLoadingProgress = showLoadingProgress;
    this.numPackagesSuccessfullyLoaded = numPackagesSuccessfullyLoaded;
    this.packageProgress = packageProgress;
    this.actionOnIOExceptionReadingBuildFile = actionOnIOExceptionReadingBuildFile;
    this.actionOnFilesystemErrorCodeLoadingBzlFile = actionOnFilesystemErrorCodeLoadingBzlFile;
    this.shouldUseRepoDotBazel = shouldUseRepoDotBazel;
    this.threadStateReceiverFactoryForMetrics = threadStateReceiverFactoryForMetrics;
    this.cpuBoundSemaphore = cpuBoundSemaphore;
  }

  public void setBzlLoadFunctionForInliningForTesting(BzlLoadFunction bzlLoadFunctionForInlining) {
    this.bzlLoadFunctionForInlining = bzlLoadFunctionForInlining;
  }

  /**
   * What to do when encountering an {@link IOException} trying to read the contents of a BUILD
   * file.
   *
   * <p>Any choice besides {@link
   * ActionOnIOExceptionReadingBuildFile.UseOriginalIOException#INSTANCE} is potentially
   * incrementally unsound: if the initial {@link IOException} is transient, then Blaze will
   * "incorrectly" not attempt to redo package loading for this BUILD file on incremental builds.
   *
   * <p>The fact that this behavior is configurable and potentially unsound is a concession to
   * certain desired use cases with fancy filesystems.
   */
  public interface ActionOnIOExceptionReadingBuildFile {
    /**
     * Given the {@link IOException} encountered when reading the contents of the given BUILD file,
     * returns the contents that should be used, or {@code null} if the original {@link IOException}
     * should be respected (that is, we should error-out with a package loading error).
     */
    @Nullable
    byte[] maybeGetBuildFileContentsToUse(
        PathFragment buildFilePathFragment, IOException originalExn);

    /**
     * A {@link ActionOnIOExceptionReadingBuildFile} whose {@link #maybeGetBuildFileContentsToUse}
     * has the sensible behavior of always respecting the initial {@link IOException}.
     */
    public static class UseOriginalIOException implements ActionOnIOExceptionReadingBuildFile {
      public static final UseOriginalIOException INSTANCE = new UseOriginalIOException();

      private UseOriginalIOException() {}

      @Override
      @Nullable
      public byte[] maybeGetBuildFileContentsToUse(
          PathFragment buildFilePathFragment, IOException originalExn) {
        return null;
      }
    }
  }

  /**
   * What to do when encountering a {@link Filesystem} error code while trying to load a bzl file.
   *
   * <p>This class should decide whether the Filesystem error code takes precedence over the
   * PackageLoading error code.
   */
  public interface ActionOnFilesystemErrorCodeLoadingBzlFile {
    boolean shouldTakePrecedenceOverPackageLoadingCode(Filesystem.Code filesystemCode);

    /** By default, always use the PackageLoading error code. */
    public static ActionOnFilesystemErrorCodeLoadingBzlFile ALWAYS_USE_PACKAGE_LOADING_CODE =
        filesystemCode -> false;
  }

  /** Ways that {@link PackageFunction} can perform globbing. */
  public enum GlobbingStrategy {
    /**
     * Globs are resolved using {@code PackageFunctionWithMultipleGlobDeps#SkyframeHybridGlobber},
     * which declares proper Skyframe dependencies.
     *
     * <p>This strategy is formerly named {@code SKYFRAME_HYBRID}.
     *
     * <p>Use when {@link PackageFunction} will be used to load packages incrementally (e.g. on both
     * clean builds and incremental builds, perhaps with cached globs). This used to be Bazel's
     * normal use-case and still is the preferred strategy if incremental evaluation performance
     * requirement is strict.
     */
    MULTIPLE_GLOB_HYBRID,

    /**
     * Globs are resolved using {@code PackageFunctionWithSingleGlobsDep#GlobsGlobber}. This
     * strategy is similar to {@link #MULTIPLE_GLOB_HYBRID} except that there is a single GLOBS
     * Skyframe dependency including all globs defined in the package's BUILD file.
     *
     * <p>The {@code GLOBS} strategy is designed to replace {@code SKYFRAME_HYBRID} as Bazel's
     * normal use case in that it coarsens the Glob-land subgraph and saving memory without
     * meaningfully sacrificing performance.
     *
     * <p>However, incremental evaluation performance might regress when switching from {@link
     * #MULTIPLE_GLOB_HYBRID} to {@link #SINGLE_GLOBS_HYBRID}. See {@link GlobFunction} for more
     * details.
     */
    SINGLE_GLOBS_HYBRID,

    /**
     * Globs are resolved using {@link NonSkyframeGlobber}, which does not declare Skyframe
     * dependencies.
     *
     * <p>This is a performance optimization only for use when {@link PackageFunction} will never be
     * used to load packages incrementally. Do not use this unless you know what you are doing;
     * Bazel will be intentionally incrementally incorrect!
     */
    NON_SKYFRAME
  }

  protected static void maybeThrowFilesystemInconsistency(
      PackageIdentifier pkgId, Exception skyframeException, boolean packageWasInError)
      throws InternalInconsistentFilesystemException {
    if (!packageWasInError) {
      throw new InternalInconsistentFilesystemException(
          pkgId,
          "Encountered error '"
              + skyframeException.getMessage()
              + "' but didn't encounter it when doing the same thing earlier in the build");
    }
  }

  /**
   * Queries GLOB deps in Skyframe if necessary, and handles package's glob deps symlink issues
   * discovered by Skyframe globbing.
   */
  @ForOverride
  protected abstract void handleGlobDepsAndPropagateFilesystemExceptions(
      PackageIdentifier packageIdentifier,
      Root packageRoot,
      LoadedPackage loadedPackage,
      Environment env,
      boolean packageWasInError)
      throws InternalInconsistentFilesystemException, FileSymlinkException, InterruptedException;

  /**
   * Adds a dependency on the WORKSPACE file, representing it as a special type of package.
   *
   * @throws PackageFunctionException if there is an error computing the workspace file or adding
   *     its rules to the //external package.
   */
  @Nullable
  private SkyValue getExternalPackage(Environment env)
      throws PackageFunctionException, InterruptedException {
    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (env.valuesMissing()) {
      return null;
    }
    if (!starlarkSemantics.getBool(BuildLanguageOptions.ENABLE_WORKSPACE)) {
      throw PackageFunctionException.builder()
          .setType(PackageFunctionException.Type.NO_SUCH_PACKAGE)
          .setTransience(Transience.PERSISTENT)
          .setPackageIdentifier(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER)
          .setMessage(
              "//external package is not available since the WORKSPACE file is disabled, please"
                  + " migrate to Bzlmod or temporarily enable WORKSPACE via --enable_workspace. See"
                  + " https://bazel.build/external/migration#bind-targets.")
          .setPackageLoadingCode(PackageLoading.Code.WORKSPACE_FILE_ERROR)
          .build();
    }

    SkyKey workspaceKey = ExternalPackageFunction.key();
    PackageValue workspace = null;
    try {
      // This may throw a NoSuchPackageException if the WORKSPACE file was malformed or had other
      // problems. Since this function can't add much context, we silently bubble it up.
      workspace =
          (PackageValue)
              env.getValueOrThrow(
                  workspaceKey,
                  IOException.class,
                  EvalException.class,
                  BzlLoadFailedException.class);
    } catch (IOException | EvalException | BzlLoadFailedException e) {
      String message = "Error encountered while dealing with the WORKSPACE file: " + e.getMessage();
      throw PackageFunctionException.builder()
          .setType(PackageFunctionException.Type.NO_SUCH_PACKAGE)
          .setTransience(Transience.PERSISTENT)
          .setPackageIdentifier(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER)
          .setMessage(message)
          .setPackageLoadingCode(PackageLoading.Code.WORKSPACE_FILE_ERROR)
          .build();
    }
    if (workspace == null) {
      return null;
    }

    Package pkg = workspace.getPackage();
    if (packageFactory != null) {
      try {
        packageFactory.afterDoneLoadingPackage(
            pkg,
            starlarkSemantics,
            // This is a lie.
            /* loadTimeNanos= */ 0L,
            env.getListener());
      } catch (InvalidPackageException e) {
        throw new PackageFunctionException(e, Transience.PERSISTENT);
      }
    }
    if (!pkg.containsErrors()) {
      numPackagesSuccessfullyLoaded.incrementAndGet();
    }
    return new PackageValue(pkg);
  }

  /**
   * Stores information needed to load the package. Subclasses are expected to provide different
   * types of containers which store glob deps information.
   */
  protected abstract static class LoadedPackage {
    final Package.Builder builder;
    final long loadTimeNanos;

    LoadedPackage(Package.Builder builder, long loadTimeNanos) {
      this.builder = builder;
      this.loadTimeNanos = loadTimeNanos;
    }
  }

  private static class State implements SkyKeyComputeState {
    @Nullable private CompiledBuildFile compiledBuildFile;
    @Nullable private LoadedPackage loadedPackage;
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey key, Environment env)
      throws PackageFunctionException, InterruptedException {
    PackageIdentifier packageId = (PackageIdentifier) key.argument();
    if (packageId.equals(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER)) {
      return getExternalPackage(env);
    }

    SkyKey packageLookupKey = PackageLookupValue.key(packageId);
    PackageLookupValue packageLookupValue;
    try {
      packageLookupValue =
          (PackageLookupValue)
              env.getValueOrThrow(
                  packageLookupKey,
                  BadRepoFileException.class,
                  InvalidIgnorePathException.class,
                  BuildFileNotFoundException.class,
                  InconsistentFilesystemException.class);
    } catch (InvalidIgnorePathException e) {
      throw PackageFunctionException.builder()
          .setType(PackageFunctionException.Type.NO_SUCH_PACKAGE)
          .setTransience(Transience.PERSISTENT)
          .setPackageIdentifier(packageId)
          .setMessage(e.getMessage())
          .setException(e)
          .setPackageLoadingCode(PackageLoading.Code.BAD_IGNORED_DIRECTORIES)
          .build();
    } catch (BadRepoFileException e) {
      throw badRepoFileException(e, packageId);
    } catch (BuildFileNotFoundException e) {
      throw new PackageFunctionException(e, Transience.PERSISTENT);
    } catch (InconsistentFilesystemException e) {
      // This error is not transient from the perspective of the PackageFunction.
      throw PackageFunctionException.builder()
          .setType(PackageFunctionException.Type.NO_SUCH_PACKAGE)
          .setTransience(Transience.PERSISTENT)
          .setPackageIdentifier(packageId)
          .setMessage(e.getMessage())
          .setException(e)
          .setPackageLoadingCode(PackageLoading.Code.PERSISTENT_INCONSISTENT_FILESYSTEM_ERROR)
          .build();
    }
    if (packageLookupValue == null) {
      return null;
    }

    if (!packageLookupValue.packageExists()) {
      PackageFunctionException.Builder exceptionBuilder =
          PackageFunctionException.builder()
              .setPackageIdentifier(packageId)
              .setTransience(Transience.PERSISTENT);
      switch (packageLookupValue.getErrorReason()) {
        case NO_BUILD_FILE:
          String message = PackageLookupFunction.explainNoBuildFileValue(packageId, env);
          throw exceptionBuilder
              .setType(PackageFunctionException.Type.BUILD_FILE_NOT_FOUND)
              .setMessage(message)
              .setPackageLoadingCode(PackageLoading.Code.BUILD_FILE_MISSING)
              .build();
        case DELETED_PACKAGE:
        case REPOSITORY_NOT_FOUND:
          throw exceptionBuilder
              .setType(PackageFunctionException.Type.BUILD_FILE_NOT_FOUND)
              .setMessage(packageLookupValue.getErrorMsg())
              .setPackageLoadingCode(PackageLoading.Code.REPOSITORY_MISSING)
              .build();
        case INVALID_PACKAGE_NAME:
          throw exceptionBuilder
              .setType(PackageFunctionException.Type.INVALID_PACKAGE_NAME)
              .setMessage(packageLookupValue.getErrorMsg())
              .setPackageLoadingCode(PackageLoading.Code.INVALID_NAME)
              .build();
      }
      // We should never get here.
      throw new IllegalStateException();
    }

    StarlarkBuiltinsValue starlarkBuiltinsValue;
    try {
      // Bazel: we do autoloads for all BUILD files if enabled
      AutoloadSymbols autoloadSymbols = AutoloadSymbols.AUTOLOAD_SYMBOLS.get(env);
      if (autoloadSymbols == null) {
        return null;
      }
      if (bzlLoadFunctionForInlining == null) {
        starlarkBuiltinsValue =
            (StarlarkBuiltinsValue)
                env.getValueOrThrow(
                    StarlarkBuiltinsValue.key(/* withAutoloads= */ autoloadSymbols.isEnabled()),
                    BuiltinsFailedException.class);
      } else {
        starlarkBuiltinsValue =
            StarlarkBuiltinsFunction.computeInline(
                StarlarkBuiltinsValue.key(/* withAutoloads= */ autoloadSymbols.isEnabled()),
                BzlLoadFunction.InliningState.create(env),
                packageFactory.getRuleClassProvider().getBazelStarlarkEnvironment(),
                bzlLoadFunctionForInlining);
      }
    } catch (BuiltinsFailedException e) {
      throw PackageFunctionException.builder()
          .setType(PackageFunctionException.Type.BUILD_FILE_CONTAINS_ERRORS)
          .setPackageIdentifier(packageId)
          .setTransience(Transience.PERSISTENT)
          .setMessage(
              String.format("Internal error while loading Starlark builtins: %s", e.getMessage()))
          .setPackageLoadingCode(PackageLoading.Code.BUILTINS_INJECTION_FAILURE)
          .build();
    }

    if (env.valuesMissing()) {
      return null;
    }

    // TODO(adonovan): put BUILD compilation from BUILD execution in separate Skyframe functions
    // like we do for .bzl files, so that we don't need to recompile BUILD files each time their
    // .bzl dependencies change.

    State state = env.getState(State::new);
    if (state.loadedPackage == null) {
      state.loadedPackage =
          loadPackage(
              packageLookupValue,
              packageId,
              starlarkBuiltinsValue,
              packageLookupValue.getRoot(),
              env,
              key,
              state);
      if (state.loadedPackage == null) {
        return null;
      }
    }
    PackageFunctionException pfeFromNonSkyframeGlobbing = null;
    Package.Builder pkgBuilder = state.loadedPackage.builder;
    try {
      pkgBuilder.buildPartial();
      // Since the Skyframe dependencies we request below in
      // handleGlobDepsAndPropagateFilesystemExceptions are requested independently of the ones
      // requested here in
      // handleLabelsCrossingSubpackagesAndPropagateInconsistentFilesystemExceptions, we don't
      // bother checking for missing values and instead piggyback on the env.missingValues() call
      // for the former. This avoids a Skyframe restart.
      // Note that handleLabelsCrossingSubpackagesAndPropagateInconsistentFilesystemExceptions
      // expects to mutate pkgBuilder.getTargets(), and thus can only be safely called if
      // pkgBuilder.buildPartial() didn't throw.
      handleLabelsCrossingSubpackagesAndPropagateInconsistentFilesystemExceptions(
          packageLookupValue.getRoot(), packageId, pkgBuilder, env);
    } catch (NoSuchPackageException e) {
      // If non-Skyframe globbing encounters an IOException, #buildPartial will throw a
      // NoSuchPackageException. If that happens, we prefer throwing an exception derived from
      // Skyframe globbing. See the comments in #handleGlobDepsAndPropagateFilesystemExceptions.
      // Therefore we store the exception encountered here and maybe use it later.
      Transience transience =
          switch (e.getCause()) {
            case DetailedIOException detailed -> detailed.getTransience();
            case SkyframeGlobbingIOException skyframeGlobbing -> Transience.PERSISTENT;
            case null, default -> Transience.TRANSIENT;
          };
      pfeFromNonSkyframeGlobbing = new PackageFunctionException(e, transience);
    } catch (InternalInconsistentFilesystemException e) {
      throw e.throwPackageFunctionException();
    }

    try {
      handleGlobDepsAndPropagateFilesystemExceptions(
          packageId,
          packageLookupValue.getRoot(),
          state.loadedPackage,
          env,
          pkgBuilder.containsErrors());
    } catch (InternalInconsistentFilesystemException e) {
      throw e.throwPackageFunctionException();
    } catch (FileSymlinkException e) {
      String message = "Symlink issue while evaluating globs: " + e.getUserFriendlyMessage();
      throw PackageFunctionException.builder()
          .setType(PackageFunctionException.Type.NO_SUCH_PACKAGE)
          .setTransience(Transience.PERSISTENT)
          .setPackageIdentifier(packageId)
          .setMessage(message)
          .setPackageLoadingCode(PackageLoading.Code.EVAL_GLOBS_SYMLINK_ERROR)
          .build();
    }

    if (pfeFromNonSkyframeGlobbing != null) {
      // Throw before checking for missing values, since this may be our last chance to throw if in
      // nokeep-going error bubbling.
      throw pfeFromNonSkyframeGlobbing;
    }

    if (env.valuesMissing()) {
      return null;
    }

    Package pkg = pkgBuilder.finishBuild();

    pkgBuilder.getLocalEventHandler().replayOn(env.getListener());

    try {
      packageFactory.afterDoneLoadingPackage(
          pkg,
          starlarkBuiltinsValue.starlarkSemantics,
          state.loadedPackage.loadTimeNanos,
          env.getListener());
    } catch (InvalidPackageException e) {
      throw new PackageFunctionException(e, Transience.PERSISTENT);
    }
    if (!pkg.containsErrors()) {
      numPackagesSuccessfullyLoaded.incrementAndGet();
    }
    return new PackageValue(pkg);
  }

  @Nullable
  private static FileValue getBuildFileValue(Environment env, RootedPath buildFileRootedPath)
      throws InterruptedException {
    FileValue buildFileValue;
    try {
      buildFileValue =
          (FileValue) env.getValueOrThrow(FileValue.key(buildFileRootedPath), IOException.class);
    } catch (IOException e) {
      throw new IllegalStateException(
          "Package lookup succeeded but encountered error when "
              + "getting FileValue for BUILD file directly.",
          e);
    }
    if (buildFileValue == null) {
      return null;
    }
    checkState(buildFileValue.exists(), "Package lookup succeeded but BUILD file doesn't exist");
    return buildFileValue;
  }

  /**
   * Loads the .bzl modules whose names and load-locations are {@code programLoads}, and whose
   * corresponding Skyframe keys are {@code keys}.
   *
   * <p>Validates load visibility for loaded modules.
   *
   * <p>Returns a map from module name to module, or null for a Skyframe restart.
   *
   * <p>The {@code packageId} is used only for error reporting.
   *
   * <p>This function is called for load statements in BUILD and WORKSPACE files. For loads in .bzl
   * files, see {@link BzlLoadFunction}.
   */
  /*
   * TODO(b/237658764): This logic has several problems:
   *
   * - It is partly duplicated by loadPrelude() below.
   * - The meaty computeBzlLoads* helpers are almost copies of BzlLoadFunction#computeBzlLoads*.
   * - This function is called from WorkspaceFileFunction and BzlmodRepoRuleFunction (and morally
   *   probably should be called by SingleExtensionEvalFunction rather than requesting a BzlLoadKey
   *   directly). But the API is awkward for these callers.
   * - InliningState is not shared across all callers within a BUILD file; see the comment in
   *   computeBzlLoadsWithInlining.
   *
   * To address these issues, we can instead make public the two BzlLoadFunction#computeBzlLoads*
   * methods. Their programLoads parameter is only used for wrapping exceptions in
   * BzlLoadFailedException#whileLoadingDep, but we can probably push that wrapping to the caller.
   * If we fix PackageFunction to use a shared InliningState, then our computeBzlLoadsWithInlining
   * method will take it as a param and its signature will then basically match the one in
   * BzlLoadFunction.
   *
   * At that point we can eliminate our own computeBzlLoads* methods in favor of the BzlLoadFunction
   * ones. We could factor out the piece of loadBzlModules that dispatches to computeBzlLoads* and
   * translates the possible exception, and push the visibility checking and loadedModules map
   * construction to the caller, so that loadPrelude can become just a call to the factored-out
   * code.
   */
  // TODO(18422): Cleanup/refactor this method's signature.
  @Nullable
  static ImmutableMap<String, Module> loadBzlModules(
      Environment env,
      PackageIdentifier packageId,
      String requestingFileDescription,
      List<Pair<String, Location>> programLoads,
      List<BzlLoadValue.Key> keys,
      StarlarkSemantics semantics,
      @Nullable BzlLoadFunction bzlLoadFunctionForInlining,
      boolean checkVisibility,
      ActionOnFilesystemErrorCodeLoadingBzlFile actionOnFilesystemErrorCodeLoadingBzlFile)
      throws NoSuchPackageException, InterruptedException {
    List<BzlLoadValue> bzlLoads;
    try {
      bzlLoads =
          bzlLoadFunctionForInlining == null
              ? computeBzlLoadsNoInlining(env, keys)
              : computeBzlLoadsWithInlining(env, keys, bzlLoadFunctionForInlining);
      if (bzlLoads == null) {
        return null; // Skyframe deps unavailable
      }
      // Validate that the current BUILD/WORKSPACE file satisfies each loaded dependency's
      // load visibility.
      if (checkVisibility) {
        BzlLoadFunction.checkLoadVisibilities(
            packageId,
            requestingFileDescription,
            bzlLoads,
            keys,
            programLoads,
            /* demoteErrorsToWarnings= */ !semantics.getBool(
                BuildLanguageOptions.CHECK_BZL_VISIBILITY),
            env.getListener());
      }
    } catch (BzlLoadFailedException e) {
      Throwable rootCause = Throwables.getRootCause(e);
      PackageFunctionException.Builder exceptionBuilder =
          PackageFunctionException.builder()
              .setType(PackageFunctionException.Type.BUILD_FILE_CONTAINS_ERRORS)
              .setPackageIdentifier(packageId)
              .setException(rootCause instanceof IOException ? (IOException) rootCause : null)
              .setMessage(e.getMessage());

      Filesystem.Code filesystemCode =
          e.getDetailedExitCode().getFailureDetail().getFilesystem().getCode();
      if (actionOnFilesystemErrorCodeLoadingBzlFile.shouldTakePrecedenceOverPackageLoadingCode(
          filesystemCode)) {
        exceptionBuilder.setFilesystemCode(filesystemCode);
      } else {
        exceptionBuilder.setPackageLoadingCode(PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR);
      }
      exceptionBuilder.setTransience(e.getTransience());
      throw exceptionBuilder.buildCause();
    }

    // Build map of loaded modules.
    Map<String, Module> loadedModules = Maps.newLinkedHashMapWithExpectedSize(bzlLoads.size());
    for (int i = 0; i < bzlLoads.size(); i++) {
      loadedModules.put(programLoads.get(i).first, bzlLoads.get(i).getModule()); // dups ok
    }
    return ImmutableMap.copyOf(loadedModules);
  }

  // Loads the prelude identified by the label. Returns null for a skyframe restart.
  @Nullable
  private static Module loadPrelude(
      Environment env,
      PackageIdentifier packageId,
      Label label,
      @Nullable BzlLoadFunction bzlLoadFunctionForInlining)
      throws NoSuchPackageException, InterruptedException {
    List<BzlLoadValue.Key> keys = ImmutableList.of(BzlLoadValue.keyForBuildPrelude(label));
    try {
      List<BzlLoadValue> loads =
          bzlLoadFunctionForInlining == null
              ? computeBzlLoadsNoInlining(env, keys)
              : computeBzlLoadsWithInlining(env, keys, bzlLoadFunctionForInlining);
      if (loads == null) {
        return null; // skyframe restart
      }
      // No need to validate visibility since we're processing an internal load on behalf of Bazel.
      return loads.get(0).getModule();

    } catch (BzlLoadFailedException e) {
      Throwable rootCause = Throwables.getRootCause(e);
      throw PackageFunctionException.builder()
          .setType(PackageFunctionException.Type.BUILD_FILE_CONTAINS_ERRORS)
          .setPackageIdentifier(packageId)
          .setException(rootCause instanceof IOException ? (IOException) rootCause : null)
          .setMessage(e.getMessage())
          .setPackageLoadingCode(PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR)
          .buildCause();
    }
  }

  /**
   * Compute the BzlLoadValue for all given keys using vanilla Skyframe evaluation, returning {@code
   * null} if Skyframe deps were missing and have been requested.
   */
  @Nullable
  private static List<BzlLoadValue> computeBzlLoadsNoInlining(
      Environment env, List<BzlLoadValue.Key> keys)
      throws InterruptedException, BzlLoadFailedException {
    List<BzlLoadValue> bzlLoads = Lists.newArrayListWithExpectedSize(keys.size());
    SkyframeLookupResult starlarkLookupResults = env.getValuesAndExceptions(keys);
    for (BzlLoadValue.Key key : keys) {
      // TODO(adonovan): if get fails, report the source location
      // in the corresponding programLoads[i] (see caller).
      bzlLoads.add(
          (BzlLoadValue) starlarkLookupResults.getOrThrow(key, BzlLoadFailedException.class));
    }
    return env.valuesMissing() ? null : bzlLoads;
  }

  /**
   * Compute the BzlLoadValue for all given keys by "inlining" the BzlLoadFunction and bypassing
   * traditional Skyframe evaluation, returning {@code null} if Skyframe deps were missing and have
   * been requested.
   */
  @Nullable
  private static List<BzlLoadValue> computeBzlLoadsWithInlining(
      Environment env, List<BzlLoadValue.Key> keys, BzlLoadFunction bzlLoadFunctionForInlining)
      throws InterruptedException, BzlLoadFailedException {
    List<BzlLoadValue> bzlLoads = Lists.newArrayListWithExpectedSize(keys.size());
    // See the comment about the desire for deterministic graph structure in BzlLoadFunction for the
    // motivation of this approach to exception handling.
    BzlLoadFailedException deferredException = null;
    // Compute BzlLoadValue for each key, sharing the same inlining state, i.e. cache of loaded
    // modules. This ensures that each .bzl is loaded only once, regardless of diamond dependencies
    // or cache eviction. (Multiple loads of the same .bzl would screw up identity equality of some
    // Starlark symbols -- see comments in BzlLoadFunction#computeInline.)
    // TODO(brandjon): Note that using a fresh InliningState in each call to this function means
    // that we don't get sharing between the top-level callers -- namely, the callers that retrieve
    // the BUILD file's loads, the prelude file, and the @_builtins. Since there's still a global
    // cache of bzls, this is only really a problem if the same bzl can appear in more than one of
    // those contexts. This *can* happen if a dependency of the prelude file is also reachable
    // through regular loads, but *only* in OSS Bazel, where inlining is not really used. The fix
    // would be to thread a single InliningState through all call sites within the same call to
    // compute().
    BzlLoadFunction.InliningState inliningState = BzlLoadFunction.InliningState.create(env);
    for (BzlLoadValue.Key key : keys) {
      SkyValue skyValue;
      try {
        // Will complete right away if this key has been seen before in inliningState -- regardless
        // of whether it was evaluated successfully, had missing deps, or was found to be in error.
        skyValue = bzlLoadFunctionForInlining.computeInline(key, inliningState);
      } catch (BzlLoadFailedException e) {
        if (deferredException == null) {
          deferredException = e;
        }
        continue;
      }
      if (skyValue != null) {
        bzlLoads.add((BzlLoadValue) skyValue);
      }
      // A null value for `skyValue` can occur when it (or its transitive loads) has a Skyframe dep
      // that is missing or in error. It can also occur if there's a transitive load on a bzl that
      // was already seen by inliningState and which returned null. In both these cases, we want to
      // continue making our inline calls, so as to maximize the number of dependent (non-inlined)
      // SkyFunctions that are requested and avoid a quadratic number of restarts.
    }
    if (deferredException != null) {
      throw deferredException;
    }
    return env.valuesMissing() ? null : bzlLoads;
  }

  /**
   * For each of a {@link Package.Builder}'s targets, propagate the target's corresponding {@link
   * InconsistentFilesystemException} (if any) and verify that the target's label does not cross
   * subpackage boundaries.
   *
   * @param pkgBuilder a {@link Package.Builder} whose {@code getTargets()} set is mutable (i.e.
   *     {@code pkgBuilder.buildPartial()} must have been successfully called).
   */
  private static void handleLabelsCrossingSubpackagesAndPropagateInconsistentFilesystemExceptions(
      Root pkgRoot, PackageIdentifier pkgId, Package.Builder pkgBuilder, Environment env)
      throws InternalInconsistentFilesystemException, InterruptedException {
    PathFragment pkgDir = pkgId.getPackageFragment();
    // Contains a key for each package whose label that might have a presence of a subpackage.
    // Values are all potential subpackages of the label.
    List<Pair<Target, List<PackageLookupValue.Key>>> targetsAndSubpackagePackageLookupKeys =
        new ArrayList<>();
    Set<PackageLookupValue.Key> allPackageLookupKeys = new HashSet<>();
    for (Target target : pkgBuilder.getTargets()) {
      Label label = target.getLabel();
      PathFragment dir = Label.getContainingDirectory(label);
      if (dir.equals(pkgDir)) {
        continue;
      }
      List<PackageLookupValue.Key> subpackagePackageLookupKeys = new ArrayList<>();
      String labelName = label.getName();
      PathFragment labelAsRelativePath = PathFragment.create(labelName).getParentDirectory();
      PathFragment subpackagePath = pkgDir;
      for (String segment : labelAsRelativePath.segments()) {
        // Please note that the order from the shallowest path to the deepest is preserved.
        subpackagePath = subpackagePath.getRelative(segment);
        PackageLookupValue.Key currentPackageLookupKey =
            PackageLookupValue.key(PackageIdentifier.create(pkgId.getRepository(), subpackagePath));
        subpackagePackageLookupKeys.add(currentPackageLookupKey);
        allPackageLookupKeys.add(currentPackageLookupKey);
      }
      targetsAndSubpackagePackageLookupKeys.add(Pair.of(target, subpackagePackageLookupKeys));
    }

    if (targetsAndSubpackagePackageLookupKeys.isEmpty()) {
      return;
    }

    SkyframeLookupResult packageLookupResults = env.getValuesAndExceptions(allPackageLookupKeys);
    if (env.valuesMissing()) {
      return;
    }

    for (Pair<Target, List<PackageLookupValue.Key>> targetAndSubpackagePackageLookupKeys :
        targetsAndSubpackagePackageLookupKeys) {
      Target target = targetAndSubpackagePackageLookupKeys.getFirst();
      List<PackageLookupValue.Key> targetPackageLookupKeys =
          targetAndSubpackagePackageLookupKeys.getSecond();
      // Iterate from the deepest potential subpackage to the shallowest in that we only want to
      // display the deepest subpackage in the error message for each target.
      for (PackageLookupValue.Key packageLookupKey : Lists.reverse(targetPackageLookupKeys)) {
        PackageLookupValue packageLookupValue;
        try {
          packageLookupValue =
              (PackageLookupValue)
                  packageLookupResults.getOrThrow(
                      packageLookupKey,
                      BuildFileNotFoundException.class,
                      InconsistentFilesystemException.class);
        } catch (BuildFileNotFoundException e) {
          env.getListener().handle(Event.error(null, e.getMessage()));
          packageLookupValue = null;
        } catch (InconsistentFilesystemException e) {
          throw new InternalInconsistentFilesystemException(pkgId, e);
        }

        if (maybeAddEventAboutLabelCrossingSubpackage(
            pkgBuilder, pkgRoot, target, packageLookupKey.argument(), packageLookupValue)) {
          pkgBuilder.getTargets().remove(target);
          pkgBuilder.setContainsErrors();
          break;
        }
      }
    }
  }

  private static boolean maybeAddEventAboutLabelCrossingSubpackage(
      Package.Builder pkgBuilder,
      Root pkgRoot,
      Target target,
      PackageIdentifier subpackageIdentifier,
      @Nullable PackageLookupValue packageLookupValue) {
    if (packageLookupValue == null) {
      return true;
    }
    String errMsg =
        PackageLookupValue.getErrorMessageForLabelCrossingPackageBoundary(
            pkgRoot, target.getLabel(), subpackageIdentifier, packageLookupValue);
    if (errMsg != null) {
      Event error =
          Package.error(target.getLocation(), errMsg, Code.LABEL_CROSSES_PACKAGE_BOUNDARY);
      pkgBuilder.getLocalEventHandler().handle(error);
      return true;
    } else {
      return false;
    }
  }

  private static PackageFunctionException badRepoFileException(
      Exception cause, PackageIdentifier packageId) {
    return PackageFunctionException.builder()
        .setType(PackageFunctionException.Type.BUILD_FILE_CONTAINS_ERRORS)
        .setPackageIdentifier(packageId)
        .setTransience(Transience.PERSISTENT)
        .setException(cause)
        .setMessage("bad REPO.bazel file")
        .setPackageLoadingCode(PackageLoading.Code.BAD_REPO_FILE)
        .build();
  }

  @ForOverride
  protected abstract Globber makeGlobber(
      NonSkyframeGlobber nonSkyframeGlobber,
      PackageIdentifier packageId,
      Root packageRoot,
      Environment env);

  /**
   * Constructs a {@link Package} object for the given package. Note that the returned package may
   * be in error.
   *
   * <p>May return null if the computation has to be restarted.
   */
  @Nullable
  private LoadedPackage loadPackage(
      PackageLookupValue packageLookupValue,
      PackageIdentifier packageId,
      StarlarkBuiltinsValue starlarkBuiltinsValue,
      Root packageRoot,
      Environment env,
      SkyKey keyForMetrics,
      State state)
      throws InterruptedException, PackageFunctionException {
    WorkspaceNameValue workspaceNameValue =
        (WorkspaceNameValue) env.getValue(WorkspaceNameValue.key());
    RepositoryMappingValue repositoryMappingValue =
        (RepositoryMappingValue)
            env.getValue(RepositoryMappingValue.key(packageId.getRepository()));
    RepositoryMappingValue mainRepositoryMappingValue =
        (RepositoryMappingValue) env.getValue(RepositoryMappingValue.key(RepositoryName.MAIN));
    RootedPath buildFileRootedPath = packageLookupValue.getRootedPath(packageId);
    FileValue buildFileValue = getBuildFileValue(env, buildFileRootedPath);
    RuleVisibility defaultVisibility = PrecomputedValue.DEFAULT_VISIBILITY.get(env);
    ConfigSettingVisibilityPolicy configSettingVisibilityPolicy =
        PrecomputedValue.CONFIG_SETTING_VISIBILITY_POLICY.get(env);
    IgnoredSubdirectoriesValue repositoryIgnoredSubdirectories =
        (IgnoredSubdirectoriesValue)
            env.getValue(IgnoredSubdirectoriesValue.key(packageId.getRepository()));
    RepoPackageArgsValue repoPackageArgsValue;
    if (shouldUseRepoDotBazel) {
      try {
        repoPackageArgsValue =
            (RepoPackageArgsValue)
                env.getValueOrThrow(
                    RepoPackageArgsFunction.key(packageId.getRepository()),
                    IOException.class,
                    BadRepoFileException.class);
      } catch (IOException | BadRepoFileException e) {
        throw badRepoFileException(e, packageId);
      }
    } else {
      repoPackageArgsValue = RepoPackageArgsValue.EMPTY;
    }

    if (env.valuesMissing()) {
      return null;
    }

    String workspaceName = workspaceNameValue.getName();
    RepositoryMapping repositoryMapping = repositoryMappingValue.repositoryMapping();
    RepositoryMapping mainRepositoryMapping = mainRepositoryMappingValue.repositoryMapping();
    Label preludeLabel = null;

    // Load (optional) prelude, which determines environment.
    ImmutableMap<String, Object> preludeBindings = null;
    // Can be null in tests.
    if (packageFactory != null) {
      // Load the prelude from the same repository as the package being loaded.
      Label rawPreludeLabel = packageFactory.getRuleClassProvider().getPreludeLabel();
      if (rawPreludeLabel != null) {
        PackageIdentifier preludePackage =
            PackageIdentifier.create(
                packageId.getRepository(), rawPreludeLabel.getPackageFragment());
        preludeLabel = Label.createUnvalidated(preludePackage, rawPreludeLabel.getName());
        Module prelude;
        try {
          prelude = loadPrelude(env, packageId, preludeLabel, bzlLoadFunctionForInlining);
        } catch (NoSuchPackageException e) {
          throw new PackageFunctionException(e, Transience.PERSISTENT);
        }
        if (prelude == null) {
          return null; // skyframe restart
        }
        preludeBindings = prelude.getGlobals();
      }
    }

    // TODO(adonovan): opt: evaluate splitting this part out as a separate Skyframe
    // function (PackageCompileFunction, by analogy with BzlCompileFunction).
    // There's a tradeoff between the memory costs of unconditionally storing
    // the PackageCompileValue and the time savings of not having to recompute
    // it situationally, so it's not an obvious strict win.

    // vv ---- begin PackageCompileFunction ---- vv

    if (packageProgress != null) {
      packageProgress.startReadPackage(packageId);
    }
    boolean committed = false;
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.CREATE_PACKAGE, packageId.toString())) {
      CompiledBuildFile compiled = state.compiledBuildFile;
      if (compiled == null) {
        if (showLoadingProgress.get()) {
          env.getListener().handle(Event.progress("Loading package: " + packageId));
        }
        compiled =
            compileBuildFile(
                packageId,
                buildFileRootedPath,
                buildFileValue,
                starlarkBuiltinsValue,
                preludeBindings,
                env.getListener());
        state.compiledBuildFile = compiled;
      }

      // ^^ ---- end PackageCompileFunction ---- ^^

      ImmutableMap<String, Module> loadedModules = null;
      if (compiled.ok()) {
        // Parse the labels in the file's load statements.
        ImmutableList<Pair<String, Location>> programLoads =
            BzlLoadFunction.getLoadsFromProgram(compiled.prog);
        ImmutableList<Label> loadLabels =
            BzlLoadFunction.getLoadLabels(
                env.getListener(),
                programLoads,
                packageId,
                repositoryMapping,
                starlarkBuiltinsValue.starlarkSemantics);
        if (loadLabels == null) {
          throw PackageFunctionException.builder()
              .setType(PackageFunctionException.Type.BUILD_FILE_CONTAINS_ERRORS)
              .setPackageIdentifier(packageId)
              .setTransience(Transience.PERSISTENT)
              .setMessage("malformed load statements")
              .setPackageLoadingCode(PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR)
              .build();
        }

        // Compute key for each label in loads.
        ImmutableList.Builder<BzlLoadValue.Key> keys =
            ImmutableList.builderWithExpectedSize(loadLabels.size());
        for (Label loadLabel : loadLabels) {
          keys.add(BzlLoadValue.keyForBuild(loadLabel));
        }

        // Load .bzl modules in parallel.
        Label buildFileLabel;
        try {
          buildFileLabel =
              Label.create(
                  packageId,
                  packageLookupValue.getBuildFileName().getFilenameFragment().getPathString());
        } catch (LabelSyntaxException e) {
          throw new IllegalStateException("Failed to construct label representing BUILD file", e);
        }
        try {
          loadedModules =
              loadBzlModules(
                  env,
                  packageId,
                  "file " + buildFileLabel.getCanonicalForm(),
                  programLoads,
                  keys.build(),
                  starlarkBuiltinsValue.starlarkSemantics,
                  bzlLoadFunctionForInlining,
                  /* checkVisibility= */ true,
                  actionOnFilesystemErrorCodeLoadingBzlFile);
        } catch (NoSuchPackageException e) {
          throw new PackageFunctionException(e, Transience.PERSISTENT);
        }
        if (loadedModules == null) {
          return null; // skyframe restart
        }
      }

      // From this point on, no matter whether the function returns
      // successfully or throws an exception, there will be no more
      // Skyframe restarts.
      committed = true;

      long startTimeNanos = BlazeClock.nanoTime();

      Globber globber =
          makeGlobber(
              packageFactory.createNonSkyframeGlobber(
                  buildFileRootedPath.asPath().getParentDirectory(),
                  packageId,
                  repositoryIgnoredSubdirectories.asIgnoredSubdirectories(),
                  packageLocator,
                  threadStateReceiverFactoryForMetrics.apply(keyForMetrics)),
              packageId,
              packageRoot,
              env);

      // Create the package,
      // even if it will be empty because we cannot attempt execution.
      Package.Builder pkgBuilder =
          packageFactory.newPackageBuilder(
              packageId,
              buildFileRootedPath,
              workspaceName,
              repositoryMappingValue.associatedModuleName(),
              repositoryMappingValue.associatedModuleVersion(),
              starlarkBuiltinsValue.starlarkSemantics,
              repositoryMapping,
              mainRepositoryMapping,
              cpuBoundSemaphore.get(),
              /* (Nullable) */ compiled.generatorMap,
              configSettingVisibilityPolicy,
              globber);

      pkgBuilder.mergePackageArgsFrom(
          PackageArgs.builder().setDefaultVisibility(defaultVisibility));
      pkgBuilder.mergePackageArgsFrom(repoPackageArgsValue.getPackageArgs());

      if (compiled.ok()) {
        packageFactory.executeBuildFile(
            pkgBuilder,
            compiled.prog,
            compiled.globs,
            compiled.globsWithDirs,
            compiled.subpackages,
            compiled.predeclared,
            loadedModules,
            starlarkBuiltinsValue.starlarkSemantics);
        pkgBuilder.expandAllRemainingMacros(starlarkBuiltinsValue.starlarkSemantics);
      } else {
        // Execution not attempted due to static errors.
        for (SyntaxError err : compiled.errors) {
          pkgBuilder
              .getLocalEventHandler()
              .handle(
                  Package.error(err.location(), err.message(), PackageLoading.Code.SYNTAX_ERROR));
        }
        pkgBuilder.setContainsErrors();
      }

      long loadTimeNanos = Math.max(BlazeClock.nanoTime() - startTimeNanos, 0L);
      return newLoadedPackage(pkgBuilder, globber, loadTimeNanos);
    } finally {
      if (committed) {
        // We're done executing the BUILD file. Therefore, we can discard the compiled BUILD file...
        state.compiledBuildFile = null;
        if (packageProgress != null) {
          // ... and also note that we're done.
          packageProgress.doneReadPackage(packageId);
        }
      }
    }
  }

  @ForOverride
  protected abstract LoadedPackage newLoadedPackage(
      Package.Builder packageBuilder, @Nullable Globber globber, long loadTimeNanos);

  // Reads, parses, resolves, and compiles a BUILD file.
  // A read error is reported as PackageFunctionException.
  // A syntax error is reported by returning a CompiledBuildFile with errors.
  private CompiledBuildFile compileBuildFile(
      PackageIdentifier packageId,
      RootedPath buildFilePath,
      FileValue buildFileValue,
      StarlarkBuiltinsValue starlarkBuiltinsValue,
      @Nullable Map<String, Object> preludeBindings,
      ExtendedEventHandler reporter)
      throws PackageFunctionException {
    // Though it could be in principle, `cpuBoundSemaphore` is not held here as this method does
    // not show up in profiles as being significantly impacted by thrashing. It could be worth doing
    // so, in which case it should be released when reading the file below.
    StarlarkSemantics semantics = starlarkBuiltinsValue.starlarkSemantics;

    // read BUILD file
    Path inputFile = buildFilePath.asPath();
    byte[] buildFileBytes = null;
    try {
      buildFileBytes =
          buildFileValue.isSpecialFile()
              ? FileSystemUtils.readContent(inputFile)
              : FileSystemUtils.readWithKnownFileSize(inputFile, buildFileValue.getSize());
    } catch (IOException e) {
      buildFileBytes =
          actionOnIOExceptionReadingBuildFile.maybeGetBuildFileContentsToUse(
              inputFile.asFragment(), e);
      if (buildFileBytes == null) {
        // Note that we did the work that led to this IOException, so we should
        // conservatively report this error as transient.
        throw PackageFunctionException.builder()
            .setType(PackageFunctionException.Type.BUILD_FILE_CONTAINS_ERRORS)
            .setTransience(Transience.TRANSIENT)
            .setPackageIdentifier(packageId)
            .setMessage(e.getMessage())
            .setException(e)
            .setPackageLoadingCode(PackageLoading.Code.BUILD_FILE_MISSING)
            .build();
      }
      // If control flow reaches here, we're in territory that is deliberately unsound.
      // See the javadoc for ActionOnIOExceptionReadingBuildFile.
    }
    StoredEventHandler handler = new StoredEventHandler();
    ParserInput input;
    try {
      input =
          StarlarkUtil.createParserInput(
              buildFileBytes,
              inputFile.toString(),
              semantics.get(BuildLanguageOptions.INCOMPATIBLE_ENFORCE_STARLARK_UTF8),
              handler);
    } catch (StarlarkUtil.InvalidUtf8Exception e) {
      handler.replayOn(reporter);
      throw PackageFunctionException.builder()
          .setType(PackageFunctionException.Type.BUILD_FILE_CONTAINS_ERRORS)
          .setPackageIdentifier(packageId)
          .setTransience(Transience.PERSISTENT)
          .setException(e)
          .setMessage("error reading " + inputFile.toString())
          .setPackageLoadingCode(PackageLoading.Code.STARLARK_EVAL_ERROR)
          .build();
    }
    handler.replayOn(reporter);

    // Options for processing BUILD files.
    FileOptions options =
        FileOptions.builder()
            .requireLoadStatementsFirst(false)
            // For historical reasons, BUILD files are allowed to load a symbol
            // and then reassign it later. (It is unclear why this is necessary).
            // TODO(adonovan): remove this flag and make loads bind file-locally,
            // as in .bzl files. One can always use a renaming load statement.
            .loadBindsGlobally(true)
            .allowToplevelRebinding(true)
            .build();

    // parse
    StarlarkFile file = StarlarkFile.parse(input, options);
    if (!file.ok()) {
      return new CompiledBuildFile(file.errors());
    }

    // Check syntax. Make a pass over the syntax tree to:
    // - reject forbidden BUILD syntax
    // - extract literal glob patterns for prefetching
    // - record the generator_name of each top-level macro call
    Set<String> globs = new HashSet<>();
    Set<String> globsWithDirs = new HashSet<>();
    Set<String> subpackages = new HashSet<>();
    Map<Location, String> generatorMap = new HashMap<>();
    try {
      PackageFactory.checkBuildSyntax(file, globs, globsWithDirs, subpackages, generatorMap);
    } catch (SyntaxError.Exception ex) {
      return new CompiledBuildFile(ex.errors());
    }

    // Construct static environment for resolution/compilation.
    // The Resolver.Module defines the set of accessible names
    // (plus special errors for flag-disabled ones), but it is
    // materialized as an ephemeral eval.Module such as will be
    // used later during execution; the two environments must match.
    // TODO(#11437): Remove conditional once disabling injection is no longer allowed.
    Map<String, Object> predeclared =
        semantics.get(BuildLanguageOptions.EXPERIMENTAL_BUILTINS_BZL_PATH).isEmpty()
            ? packageFactory
                .getRuleClassProvider()
                .getBazelStarlarkEnvironment()
                .getUninjectedBuildEnv()
            : starlarkBuiltinsValue.predeclaredForBuild;
    if (preludeBindings != null) {
      predeclared = new LinkedHashMap<>(predeclared);
      predeclared.putAll(preludeBindings);
    }
    Module module = Module.withPredeclared(semantics, predeclared);

    // Compile BUILD file.
    Program prog;
    try {
      prog = Program.compileFile(file, module);
    } catch (SyntaxError.Exception ex) {
      return new CompiledBuildFile(ex.errors());
    }

    // success
    return new CompiledBuildFile(
        prog,
        ImmutableList.copyOf(globs),
        ImmutableList.copyOf(globsWithDirs),
        ImmutableList.copyOf(subpackages),
        ImmutableMap.copyOf(generatorMap),
        ImmutableMap.copyOf(predeclared));
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Builder class for {@link PackageFunction}. */
  public static final class Builder {
    @Nullable private PackageFactory packageFactory;
    @Nullable private CachingPackageLocator pkgLocator;
    private AtomicBoolean showLoadingProgress = new AtomicBoolean(false);
    private AtomicInteger numPackagesSuccessfullyLoaded = new AtomicInteger(0);
    @Nullable private BzlLoadFunction bzlLoadFunctionForInlining;
    @Nullable private PackageProgressReceiver packageProgress;
    private ActionOnIOExceptionReadingBuildFile actionOnIOExceptionReadingBuildFile =
        PackageFunction.ActionOnIOExceptionReadingBuildFile.UseOriginalIOException.INSTANCE;
    private ActionOnFilesystemErrorCodeLoadingBzlFile actionOnFilesystemErrorCodeLoadingBzlFile =
        PackageFunction.ActionOnFilesystemErrorCodeLoadingBzlFile.ALWAYS_USE_PACKAGE_LOADING_CODE;
    private boolean shouldUseRepoDotBazel = true;
    private GlobbingStrategy globbingStrategy = GlobbingStrategy.SINGLE_GLOBS_HYBRID;
    private Function<SkyKey, ThreadStateReceiver> threadStateReceiverFactoryForMetrics =
        k -> ThreadStateReceiver.NULL_INSTANCE;
    private AtomicReference<Semaphore> cpuBoundSemaphore = new AtomicReference<>();

    @CanIgnoreReturnValue
    public Builder setPackageFactory(PackageFactory packageFactory) {
      this.packageFactory = packageFactory;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setPackageLocator(CachingPackageLocator pkgLocator) {
      this.pkgLocator = pkgLocator;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setShowLoadingProgress(AtomicBoolean showLoadingProgress) {
      this.showLoadingProgress = showLoadingProgress;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setNumPackagesSuccessfullyLoaded(AtomicInteger numPackagesSuccessfullyLoaded) {
      this.numPackagesSuccessfullyLoaded = numPackagesSuccessfullyLoaded;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setBzlLoadFunctionForInlining(BzlLoadFunction bzlLoadFunctionForInlining) {
      this.bzlLoadFunctionForInlining = bzlLoadFunctionForInlining;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setPackageProgress(PackageProgressReceiver packageProgress) {
      this.packageProgress = packageProgress;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setActionOnIOExceptionReadingBuildFile(
        ActionOnIOExceptionReadingBuildFile actionOnIOExceptionReadingBuildFile) {
      this.actionOnIOExceptionReadingBuildFile = actionOnIOExceptionReadingBuildFile;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setActionOnFilesystemErrorCodeLoadingBzlFile(
        ActionOnFilesystemErrorCodeLoadingBzlFile actionOnFilesystemErrorCodeLoadingBzlFile) {
      this.actionOnFilesystemErrorCodeLoadingBzlFile = actionOnFilesystemErrorCodeLoadingBzlFile;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setShouldUseRepoDotBazel(boolean shouldUseRepoDotBazel) {
      this.shouldUseRepoDotBazel = shouldUseRepoDotBazel;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setGlobbingStrategy(GlobbingStrategy globbingStrategy) {
      this.globbingStrategy = globbingStrategy;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setThreadStateReceiverFactoryForMetrics(
        Function<SkyKey, ThreadStateReceiver> threadStateReceiverFactoryForMetrics) {
      this.threadStateReceiverFactoryForMetrics = threadStateReceiverFactoryForMetrics;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setCpuBoundSemaphore(AtomicReference<Semaphore> cpuBoundSemaphore) {
      this.cpuBoundSemaphore = cpuBoundSemaphore;
      return this;
    }

    public PackageFunction build() {
      switch (globbingStrategy) {
        case MULTIPLE_GLOB_HYBRID -> {
          return new PackageFunctionWithMultipleGlobDeps(
              packageFactory,
              pkgLocator,
              showLoadingProgress,
              numPackagesSuccessfullyLoaded,
              bzlLoadFunctionForInlining,
              packageProgress,
              actionOnIOExceptionReadingBuildFile,
              actionOnFilesystemErrorCodeLoadingBzlFile,
              shouldUseRepoDotBazel,
              threadStateReceiverFactoryForMetrics,
              cpuBoundSemaphore);
        }
        case SINGLE_GLOBS_HYBRID -> {
          return new PackageFunctionWithSingleGlobsDep(
              packageFactory,
              pkgLocator,
              showLoadingProgress,
              numPackagesSuccessfullyLoaded,
              bzlLoadFunctionForInlining,
              packageProgress,
              actionOnIOExceptionReadingBuildFile,
              actionOnFilesystemErrorCodeLoadingBzlFile,
              shouldUseRepoDotBazel,
              threadStateReceiverFactoryForMetrics,
              cpuBoundSemaphore);
        }
        case NON_SKYFRAME -> {
          return new PackageFunctionWithoutGlobDeps(
              packageFactory,
              pkgLocator,
              showLoadingProgress,
              numPackagesSuccessfullyLoaded,
              bzlLoadFunctionForInlining,
              packageProgress,
              actionOnIOExceptionReadingBuildFile,
              actionOnFilesystemErrorCodeLoadingBzlFile,
              shouldUseRepoDotBazel,
              threadStateReceiverFactoryForMetrics,
              cpuBoundSemaphore);
        }
      }
      throw new IllegalStateException();
    }
  }

  /**
   * Wraps {@link InconsistentFilesystemException}. This is only internally used by {@link
   * PackageFunction}.
   */
  protected static class InternalInconsistentFilesystemException extends Exception {
    private boolean isTransient;
    private final PackageIdentifier packageIdentifier;

    /**
     * Used to represent a filesystem inconsistency discovered outside the {@link PackageFunction}.
     */
    public InternalInconsistentFilesystemException(
        PackageIdentifier packageIdentifier, InconsistentFilesystemException e) {
      super(e.getMessage(), e);
      this.packageIdentifier = packageIdentifier;
      // This is not a transient error from the perspective of the PackageFunction.
      this.isTransient = false;
    }

    /** Used to represent a filesystem inconsistency discovered by the {@link PackageFunction}. */
    public InternalInconsistentFilesystemException(
        PackageIdentifier packageIdentifier, String inconsistencyMessage) {
      this(packageIdentifier, new InconsistentFilesystemException(inconsistencyMessage));
      this.isTransient = true;
    }

    public boolean isTransient() {
      return isTransient;
    }

    private PackageFunctionException throwPackageFunctionException()
        throws PackageFunctionException {
      throw PackageFunctionException.builder()
          .setType(PackageFunctionException.Type.NO_SUCH_PACKAGE)
          .setPackageIdentifier(packageIdentifier)
          .setMessage(this.getMessage())
          .setException((Exception) this.getCause())
          .setPackageLoadingCode(
              isTransient()
                  ? Code.TRANSIENT_INCONSISTENT_FILESYSTEM_ERROR
                  : Code.PERSISTENT_INCONSISTENT_FILESYSTEM_ERROR)
          .setTransience(isTransient() ? Transience.TRANSIENT : Transience.PERSISTENT)
          .build();
    }
  }

  /**
   * Used to declare all the exception types that can be wrapped in the exception thrown by {@link
   * PackageFunction#compute}.
   */
  static class PackageFunctionException extends SkyFunctionException {
    public PackageFunctionException(NoSuchPackageException e, Transience transience) {
      super(e, transience);
    }

    public PackageFunctionException(BadRepoFileException e, Transience transience) {
      super(e, transience);
    }

    static Builder builder() {
      return new Builder();
    }

    /**
     * An enum to help create the different types of {@link NoSuchPackageException}. PackageFunction
     * contains a myriad of different types of exceptions that extend NoSuchPackageException for
     * different scenarios.
     */
    enum Type {
      BUILD_FILE_CONTAINS_ERRORS {
        @Override
        BuildFileContainsErrorsException create(
            PackageIdentifier packId, String msg, DetailedExitCode detailedExitCode, Exception e) {
          return e instanceof IOException ioException
              ? new BuildFileContainsErrorsException(packId, msg, ioException, detailedExitCode)
              : new BuildFileContainsErrorsException(packId, msg, detailedExitCode);
        }
      },
      BUILD_FILE_NOT_FOUND {
        @Override
        BuildFileNotFoundException create(
            PackageIdentifier packId, String msg, DetailedExitCode detailedExitCode, Exception e) {
          return new BuildFileNotFoundException(packId, msg, detailedExitCode);
        }
      },
      INVALID_PACKAGE_NAME {
        @Override
        InvalidPackageNameException create(
            PackageIdentifier packId, String msg, DetailedExitCode detailedExitCode, Exception e) {
          return new InvalidPackageNameException(packId, msg, detailedExitCode);
        }
      },
      NO_SUCH_PACKAGE {
        @Override
        NoSuchPackageException create(
            PackageIdentifier packId, String msg, DetailedExitCode detailedExitCode, Exception e) {
          return e != null
              ? new NoSuchPackageException(packId, msg, e, detailedExitCode)
              : new NoSuchPackageException(packId, msg, detailedExitCode);
        }
      };

      abstract NoSuchPackageException create(
          PackageIdentifier packId, String msg, DetailedExitCode detailedExitCode, Exception e);
    }

    /**
     * The builder class for {@link PackageFunctionException} and its {@link NoSuchPackageException}
     * cause.
     */
    static class Builder {
      private Type exceptionType;
      private PackageIdentifier packageIdentifier;
      private Transience transience;
      private Exception exception;
      private String message;
      private PackageLoading.Code packageLoadingCode;
      private Filesystem.Code filesystemCode;

      @CanIgnoreReturnValue
      Builder setType(Type exceptionType) {
        this.exceptionType = exceptionType;
        return this;
      }

      @CanIgnoreReturnValue
      Builder setPackageIdentifier(PackageIdentifier packageIdentifier) {
        this.packageIdentifier = packageIdentifier;
        return this;
      }

      @CanIgnoreReturnValue
      private Builder setTransience(Transience transience) {
        this.transience = transience;
        return this;
      }

      @CanIgnoreReturnValue
      private Builder setException(Exception exception) {
        this.exception = exception;
        return this;
      }

      @CanIgnoreReturnValue
      Builder setMessage(String message) {
        this.message = message;
        return this;
      }

      @CanIgnoreReturnValue
      Builder setPackageLoadingCode(PackageLoading.Code packageLoadingCode) {
        this.packageLoadingCode = packageLoadingCode;
        return this;
      }

      @CanIgnoreReturnValue
      Builder setFilesystemCode(Filesystem.Code filesystemCode) {
        this.filesystemCode = filesystemCode;
        return this;
      }

      @Override
      public int hashCode() {
        return Objects.hash(
            exceptionType, packageIdentifier, transience, exception, message, packageLoadingCode);
      }

      @Override
      public boolean equals(Object other) {
        if (this == other) {
          return true;
        }
        if (!(other instanceof Builder otherBuilder)) {
          return false;
        }
        return Objects.equals(exceptionType, otherBuilder.exceptionType)
            && Objects.equals(packageIdentifier, otherBuilder.packageIdentifier)
            && Objects.equals(transience, otherBuilder.transience)
            && Objects.equals(exception, otherBuilder.exception)
            && Objects.equals(message, otherBuilder.message)
            && Objects.equals(packageLoadingCode, otherBuilder.packageLoadingCode);
      }

      NoSuchPackageException buildCause() {
        checkNotNull(exceptionType, "The NoSuchPackageException type must be set.");

        DetailedExitCode detailedExitCode;
        if (filesystemCode != null) {
          detailedExitCode = createDetailedExitCodeWithFilesystemCode(message, filesystemCode);
        } else {
          checkNotNull(
              packageLoadingCode,
              "Either the Filesystem code or the PackageLoading code must be set.");
          detailedExitCode =
              createDetailedExitCodeWithPackageLoadingCode(message, packageLoadingCode);
        }

        return exceptionType.create(packageIdentifier, message, detailedExitCode, exception);
      }

      PackageFunctionException build() {
        return new PackageFunctionException(
            buildCause(), checkNotNull(transience, "Transience must be set"));
      }

      private static DetailedExitCode createDetailedExitCodeWithPackageLoadingCode(
          String message, PackageLoading.Code packageLoadingCode) {
        return DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setPackageLoading(PackageLoading.newBuilder().setCode(packageLoadingCode).build())
                .build());
      }

      private static DetailedExitCode createDetailedExitCodeWithFilesystemCode(
          String message, Filesystem.Code filesystemCode) {
        return DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setFilesystem(Filesystem.newBuilder().setCode(filesystemCode))
                .build());
      }
    }
  }
}
