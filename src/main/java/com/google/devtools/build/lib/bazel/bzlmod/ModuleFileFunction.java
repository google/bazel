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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.difflib.patch.PatchFailedException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.bazel.bzlmod.CompiledModuleFile.IncludeStatement;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.NonRootModuleFileValue;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.RootModuleFileValue;
import com.google.devtools.build.lib.bazel.repository.PatchUtil;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum.MissingChecksumException;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.BazelStarlarkEnvironment;
import com.google.devtools.build.lib.packages.StarlarkExportable;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue;
import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code;
import com.google.devtools.build.lib.skyframe.ClientEnvironmentFunction;
import com.google.devtools.build.lib.skyframe.ClientEnvironmentValue;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction;
import com.google.devtools.build.lib.skyframe.PackageLookupValue;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.PrecomputedValue.Precomputed;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import com.google.errorprone.annotations.FormatMethod;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;

/**
 * Takes a {@link ModuleKey} and its override (if any), retrieves the module file from a registry or
 * as directed by the override, and evaluates the module file.
 */
public class ModuleFileFunction implements SkyFunction {

  // Never empty.
  public static final Precomputed<ImmutableSet<String>> REGISTRIES =
      new Precomputed<>("registries");
  public static final Precomputed<Boolean> IGNORE_DEV_DEPS =
      new Precomputed<>("ignore_dev_dependency");

  public static final Precomputed<Map<String, ModuleOverride>> MODULE_OVERRIDES =
      new Precomputed<>("module_overrides");

  private final BazelStarlarkEnvironment starlarkEnv;
  private final Path workspaceRoot;
  private final ImmutableMap<String, NonRegistryOverride> builtinModules;
  @Nullable private DownloadManager downloadManager;

  private static final String BZLMOD_REMINDER =
      """
      ###############################################################################
      # Bazel now uses Bzlmod by default to manage external dependencies.
      # Please consider migrating your external dependencies from WORKSPACE to MODULE.bazel.
      #
      # For more details, please check https://github.com/bazelbuild/bazel/issues/18958
      ###############################################################################
      """;

  /**
   * @param builtinModules A list of "built-in" modules that are treated as implicit dependencies of
   *     every other module (including other built-in modules). These modules are defined as
   *     non-registry overrides.
   */
  public ModuleFileFunction(
      BazelStarlarkEnvironment starlarkEnv,
      Path workspaceRoot,
      ImmutableMap<String, NonRegistryOverride> builtinModules) {
    this.starlarkEnv = starlarkEnv;
    this.workspaceRoot = workspaceRoot;
    this.builtinModules = builtinModules;
  }

  private static class State implements Environment.SkyKeyComputeState {
    // The following fields are used during root module file evaluation. We try to compile the root
    // module file itself first, and then read, parse, and compile any included module files layer
    // by layer, in a BFS fashion (hence the `horizon` field). Finally, everything is collected into
    // the `includeLabelToCompiledModuleFile` map for use during actual Starlark execution.
    CompiledModuleFile compiledRootModuleFile;
    ImmutableList<IncludeStatement> horizon;
    HashMap<String, CompiledModuleFile> includeLabelToCompiledModuleFile = new HashMap<>();
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws ModuleFileFunctionException, InterruptedException {
    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (starlarkSemantics == null) {
      return null;
    }

    if (skyKey.equals(ModuleFileValue.KEY_FOR_ROOT_MODULE)) {
      return computeForRootModule(starlarkSemantics, env);
    }

    ClientEnvironmentValue allowedYankedVersionsFromEnv =
        (ClientEnvironmentValue)
            env.getValue(
                ClientEnvironmentFunction.key(
                    YankedVersionsUtil.BZLMOD_ALLOWED_YANKED_VERSIONS_ENV));
    if (allowedYankedVersionsFromEnv == null) {
      return null;
    }

    ModuleFileValue.Key moduleFileKey = (ModuleFileValue.Key) skyKey;
    ModuleKey moduleKey = moduleFileKey.getModuleKey();
    GetModuleFileResult getModuleFileResult;
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.BZLMOD, () -> "fetch module file: " + moduleKey)) {
      getModuleFileResult =
          getModuleFile(moduleKey, moduleFileKey.getOverride(), starlarkSemantics, env);
    }
    if (getModuleFileResult == null) {
      return null;
    }
    getModuleFileResult.downloadEventHandler.replayOn(env.getListener());

    CompiledModuleFile compiledModuleFile;
    try {
      compiledModuleFile =
          CompiledModuleFile.parseAndCompile(
              getModuleFileResult.moduleFile,
              moduleKey,
              starlarkSemantics,
              starlarkEnv,
              env.getListener());
    } catch (ExternalDepsException e) {
      throw new ModuleFileFunctionException(e, Transience.PERSISTENT);
    }
    if (!compiledModuleFile.includeStatements().isEmpty()) {
      throw errorf(
          Code.BAD_MODULE,
          "include() directive found at %s, but it can only be used in the root module",
          compiledModuleFile.includeStatements().getFirst().location());
    }
    ModuleThreadContext moduleThreadContext =
        execModuleFile(
            compiledModuleFile,
            /* includeLabelToParsedModuleFile= */ null,
            moduleKey,
            // Dev dependencies should always be ignored if the current module isn't the root module
            /* ignoreDevDeps= */ true,
            builtinModules,
            // Disable printing for modules from registries. We don't want them to be able to spam
            // the console during resolution, but module files potentially edited by the user as
            // part of a non-registry override should permit printing to aid debugging.
            /* printIsNoop= */ getModuleFileResult.registry != null,
            starlarkSemantics,
            env.getListener());

    // Perform some sanity checks.
    InterimModule module;
    try {
      module = moduleThreadContext.buildModule(getModuleFileResult.registry);
    } catch (EvalException e) {
      env.getListener().handle(Event.error(e.getInnermostLocation(), e.getMessageWithStack()));
      throw errorf(Code.BAD_MODULE, "error executing MODULE.bazel file for %s", moduleKey);
    }
    if (!module.getName().equals(moduleKey.getName())) {
      throw errorf(
          Code.BAD_MODULE,
          "the MODULE.bazel file of %s declares a different name (%s)",
          moduleKey,
          module.getName());
    }
    if (!moduleKey.getVersion().isEmpty() && !module.getVersion().equals(moduleKey.getVersion())) {
      throw errorf(
          Code.BAD_MODULE,
          "the MODULE.bazel file of %s declares a different version (%s)",
          moduleKey,
          module.getVersion());
    }

    return NonRootModuleFileValue.create(
        module,
        RegistryFileDownloadEvent.collectToMap(
            getModuleFileResult.downloadEventHandler.getPosts()));
  }

  public void setDownloadManager(DownloadManager downloadManager) {
    this.downloadManager = downloadManager;
  }

  @Nullable
  private SkyValue computeForRootModule(StarlarkSemantics starlarkSemantics, Environment env)
      throws ModuleFileFunctionException, InterruptedException {
    State state = env.getState(State::new);
    if (state.compiledRootModuleFile == null) {
      RootedPath moduleFilePath = getModuleFilePath(workspaceRoot);
      if (env.getValue(FileValue.key(moduleFilePath)) == null) {
        return null;
      }
      byte[] moduleFileContents;
      if (moduleFilePath.asPath().exists()) {
        moduleFileContents = readModuleFile(moduleFilePath.asPath());
      } else {
        moduleFileContents = BZLMOD_REMINDER.getBytes(UTF_8);
        createModuleFile(moduleFilePath.asPath(), moduleFileContents);
        env.getListener()
            .handle(
                Event.warn(
                    "--enable_bzlmod is set, but no MODULE.bazel file was found at the workspace"
                        + " root. Bazel will create an empty MODULE.bazel file. Please consider"
                        + " migrating your external dependencies from WORKSPACE to MODULE.bazel."
                        + " For more details, please refer to"
                        + " https://github.com/bazelbuild/bazel/issues/18958."));
      }
      try {
        state.compiledRootModuleFile =
            CompiledModuleFile.parseAndCompile(
                ModuleFile.create(moduleFileContents, moduleFilePath.asPath().toString()),
                ModuleKey.ROOT,
                starlarkSemantics,
                starlarkEnv,
                env.getListener());
      } catch (ExternalDepsException e) {
        throw new ModuleFileFunctionException(e, Transience.PERSISTENT);
      }
      state.horizon = state.compiledRootModuleFile.includeStatements();
    }
    while (!state.horizon.isEmpty()) {
      var newHorizon =
          advanceHorizon(
              state.includeLabelToCompiledModuleFile,
              state.horizon,
              env,
              starlarkSemantics,
              starlarkEnv);
      if (newHorizon == null) {
        return null;
      }
      state.horizon = newHorizon;
    }
    return evaluateRootModuleFile(
        state.compiledRootModuleFile,
        ImmutableMap.copyOf(state.includeLabelToCompiledModuleFile),
        builtinModules,
        MODULE_OVERRIDES.get(env),
        IGNORE_DEV_DEPS.get(env),
        starlarkSemantics,
        env.getListener());
  }

  /**
   * Reads, parses, and compiles all included module files named by {@code horizon}, stores the
   * result in {@code includeLabelToCompiledModuleFile}, and finally returns the include statements
   * of these newly compiled module files as a new "horizon".
   */
  @Nullable
  private static ImmutableList<IncludeStatement> advanceHorizon(
      HashMap<String, CompiledModuleFile> includeLabelToCompiledModuleFile,
      ImmutableList<IncludeStatement> horizon,
      Environment env,
      StarlarkSemantics starlarkSemantics,
      BazelStarlarkEnvironment starlarkEnv)
      throws ModuleFileFunctionException, InterruptedException {
    var includeLabels = new ArrayList<Label>(horizon.size());
    for (var includeStatement : horizon) {
      if (!includeStatement.includeLabel().startsWith("//")) {
        throw errorf(
            Code.BAD_MODULE,
            "bad include label '%s' at %s: include() must be called with main repo labels "
                + "(starting with double slashes)",
            includeStatement.includeLabel(),
            includeStatement.location());
      }
      if (!includeStatement.includeLabel().endsWith(".MODULE.bazel")) {
        throw errorf(
            Code.BAD_MODULE,
            "bad include label '%s' at %s: the file to be included must have a name ending in"
                + " '.MODULE.bazel'",
            includeStatement.includeLabel(),
            includeStatement.location());
      }
      try {
        includeLabels.add(Label.parseCanonical(includeStatement.includeLabel()));
      } catch (LabelSyntaxException e) {
        throw errorf(
            Code.BAD_MODULE,
            "bad include label '%s' at %s: %s",
            includeStatement.includeLabel(),
            includeStatement.location(),
            e.getMessage());
      }
    }
    SkyframeLookupResult result =
        env.getValuesAndExceptions(
            includeLabels.stream()
                .map(l -> (SkyKey) PackageLookupValue.key(l.getPackageIdentifier()))
                .collect(toImmutableSet()));
    var rootedPaths = new ArrayList<RootedPath>(horizon.size());
    for (int i = 0; i < horizon.size(); i++) {
      Label includeLabel = includeLabels.get(i);
      PackageLookupValue pkgLookupValue =
          (PackageLookupValue)
              result.get(PackageLookupValue.key(includeLabel.getPackageIdentifier()));
      if (pkgLookupValue == null) {
        return null;
      }
      if (!pkgLookupValue.packageExists()) {
        String message = pkgLookupValue.getErrorMsg();
        if (pkgLookupValue == PackageLookupValue.NO_BUILD_FILE_VALUE) {
          message =
              PackageLookupFunction.explainNoBuildFileValue(
                  includeLabel.getPackageIdentifier(), env);
        }
        throw errorf(
            Code.BAD_MODULE,
            "unable to load package for '%s' included at %s: %s",
            horizon.get(i).includeLabel(),
            horizon.get(i).location(),
            message);
      }
      rootedPaths.add(
          RootedPath.toRootedPath(pkgLookupValue.getRoot(), includeLabel.toPathFragment()));
    }
    result =
        env.getValuesAndExceptions(
            rootedPaths.stream().map(FileValue::key).collect(toImmutableSet()));
    var newHorizon = ImmutableList.<IncludeStatement>builder();
    for (int i = 0; i < horizon.size(); i++) {
      FileValue fileValue = (FileValue) result.get(FileValue.key(rootedPaths.get(i)));
      if (fileValue == null) {
        return null;
      }
      if (!fileValue.isFile()) {
        throw errorf(
            Code.BAD_MODULE,
            "error reading '%s' included at %s: not a regular file",
            horizon.get(i).includeLabel(),
            horizon.get(i).location());
      }
      byte[] bytes;
      try {
        bytes = FileSystemUtils.readContent(rootedPaths.get(i).asPath());
      } catch (IOException e) {
        throw errorf(
            Code.BAD_MODULE,
            "error reading '%s' included at %s: %s",
            horizon.get(i).includeLabel(),
            horizon.get(i).location(),
            e.getMessage());
      }
      try {
        var compiledModuleFile =
            CompiledModuleFile.parseAndCompile(
                ModuleFile.create(bytes, rootedPaths.get(i).asPath().toString()),
                ModuleKey.ROOT,
                starlarkSemantics,
                starlarkEnv,
                env.getListener());
        includeLabelToCompiledModuleFile.put(horizon.get(i).includeLabel(), compiledModuleFile);
        newHorizon.addAll(compiledModuleFile.includeStatements());
      } catch (ExternalDepsException e) {
        throw new ModuleFileFunctionException(e, Transience.PERSISTENT);
      }
    }
    return newHorizon.build();
  }

  public static RootedPath getModuleFilePath(Path workspaceRoot) {
    return RootedPath.toRootedPath(
        Root.fromPath(workspaceRoot), LabelConstants.MODULE_DOT_BAZEL_FILE_NAME);
  }

  public static RootModuleFileValue evaluateRootModuleFile(
      CompiledModuleFile compiledRootModuleFile,
      ImmutableMap<String, CompiledModuleFile> includeLabelToCompiledModuleFile,
      ImmutableMap<String, NonRegistryOverride> builtinModules,
      Map<String, ModuleOverride> commandOverrides,
      boolean ignoreDevDeps,
      StarlarkSemantics starlarkSemantics,
      ExtendedEventHandler eventHandler)
      throws ModuleFileFunctionException, InterruptedException {
    ModuleThreadContext moduleThreadContext =
        execModuleFile(
            compiledRootModuleFile,
            includeLabelToCompiledModuleFile,
            ModuleKey.ROOT,
            ignoreDevDeps,
            builtinModules,
            /* printIsNoop= */ false,
            starlarkSemantics,
            eventHandler);
    InterimModule module;
    try {
      module = moduleThreadContext.buildModule(/* registry= */ null);
    } catch (EvalException e) {
      eventHandler.handle(Event.error(e.getInnermostLocation(), e.getMessageWithStack()));
      throw errorf(Code.BAD_MODULE, "error executing MODULE.bazel file for the root module");
    }
    for (ModuleExtensionUsage usage : module.getExtensionUsages()) {
      ModuleExtensionUsage.Proxy firstProxy = usage.getProxies().getFirst();
      if (usage.getIsolationKey().isPresent() && firstProxy.getImports().isEmpty()) {
        throw errorf(
            Code.BAD_MODULE,
            "the isolated usage at %s of extension %s defined in %s has no effect as no "
                + "repositories are imported from it. Either import one or more repositories "
                + "generated by the extension with use_repo or remove the usage.",
            firstProxy.getLocation(),
            usage.getExtensionName(),
            usage.getExtensionBzlFile());
      }
    }

    ImmutableMap<String, ModuleOverride> moduleOverrides = moduleThreadContext.buildOverrides();
    ImmutableMap<String, ModuleOverride> overrides =
        ImmutableMap.<String, ModuleOverride>builder()
            .putAll(moduleOverrides)
            .putAll(commandOverrides)
            .buildKeepingLast();

    // Check that overrides don't contain the root module itself.
    ModuleOverride rootOverride = overrides.get(module.getName());
    if (rootOverride != null) {
      throw errorf(Code.BAD_MODULE, "invalid override for the root module found: %s", rootOverride);
    }
    ImmutableMap<RepositoryName, String> nonRegistryOverrideCanonicalRepoNameLookup =
        Maps.filterValues(overrides, override -> override instanceof NonRegistryOverride)
            .keySet()
            .stream()
            .collect(
                toImmutableMap(
                    // A module with a non-registry override always has a unique version across the
                    // entire dep graph.
                    name ->
                        ModuleKey.create(name, Version.EMPTY)
                            .getCanonicalRepoNameWithoutVersion(starlarkSemantics),
                    name -> name));
    ImmutableSet<PathFragment> moduleFilePaths =
        Stream.concat(
                Stream.of(LabelConstants.MODULE_DOT_BAZEL_FILE_NAME),
                includeLabelToCompiledModuleFile.keySet().stream()
                    .map(label -> Label.parseCanonicalUnchecked(label).toPathFragment()))
            .collect(toImmutableSet());
    return RootModuleFileValue.create(
        module, overrides, nonRegistryOverrideCanonicalRepoNameLookup, moduleFilePaths);
  }

  private static ModuleThreadContext execModuleFile(
      CompiledModuleFile compiledRootModuleFile,
      @Nullable ImmutableMap<String, CompiledModuleFile> includeLabelToParsedModuleFile,
      ModuleKey moduleKey,
      boolean ignoreDevDeps,
      ImmutableMap<String, NonRegistryOverride> builtinModules,
      boolean printIsNoop,
      StarlarkSemantics starlarkSemantics,
      ExtendedEventHandler eventHandler)
      throws ModuleFileFunctionException, InterruptedException {
    ModuleThreadContext context =
        new ModuleThreadContext(
            builtinModules, moduleKey, ignoreDevDeps, includeLabelToParsedModuleFile);
    try (SilentCloseable c =
            Profiler.instance()
                .profile(ProfilerTask.BZLMOD, () -> "evaluate module file: " + moduleKey);
        Mutability mu = Mutability.create("module file", moduleKey)) {
      StarlarkThread thread = new StarlarkThread(mu, starlarkSemantics);
      context.storeInThread(thread);
      if (printIsNoop) {
        thread.setPrintHandler((t, msg) -> {});
      } else {
        thread.setPrintHandler(Event.makeDebugPrintHandler(eventHandler));
      }
      thread.setPostAssignHook(
          (name, value) -> {
            if (value instanceof StarlarkExportable) {
              StarlarkExportable exportable = (StarlarkExportable) value;
              if (!exportable.isExported()) {
                exportable.export(eventHandler, null, name);
              }
            }
          });
      compiledRootModuleFile.runOnThread(thread);
    } catch (EvalException e) {
      eventHandler.handle(Event.error(e.getInnermostLocation(), e.getMessageWithStack()));
      throw errorf(Code.BAD_MODULE, "error executing MODULE.bazel file for %s", moduleKey);
    }
    return context;
  }

  /**
   * Result of a {@link #getModuleFile} call.
   *
   * @param registry can be null if this module has a non-registry override.
   */
  private record GetModuleFileResult(
      ModuleFile moduleFile,
      @Nullable Registry registry,
      StoredEventHandler downloadEventHandler) {}

  @Nullable
  private GetModuleFileResult getModuleFile(
      ModuleKey key,
      @Nullable ModuleOverride override,
      StarlarkSemantics starlarkSemantics,
      Environment env)
      throws ModuleFileFunctionException, InterruptedException {
    // If there is a non-registry override for this module, we need to fetch the corresponding repo
    // first and read the module file from there.
    if (override instanceof NonRegistryOverride) {
      // A module with a non-registry override always has a unique version across the entire dep
      // graph.
      RepositoryName canonicalRepoName = key.getCanonicalRepoNameWithoutVersion(starlarkSemantics);
      RepositoryDirectoryValue repoDir =
          (RepositoryDirectoryValue) env.getValue(RepositoryDirectoryValue.key(canonicalRepoName));
      if (repoDir == null) {
        return null;
      }
      RootedPath moduleFilePath =
          RootedPath.toRootedPath(
              Root.fromPath(repoDir.getPath()), LabelConstants.MODULE_DOT_BAZEL_FILE_NAME);
      if (env.getValue(FileValue.key(moduleFilePath)) == null) {
        return null;
      }
      Label moduleFileLabel =
          Label.createUnvalidated(
              PackageIdentifier.create(canonicalRepoName, PathFragment.EMPTY_FRAGMENT),
              LabelConstants.MODULE_DOT_BAZEL_FILE_NAME.getBaseName());
      return new GetModuleFileResult(
          ModuleFile.create(
              readModuleFile(moduleFilePath.asPath()),
              moduleFileLabel.getUnambiguousCanonicalForm()),
          /* registry= */ null,
          new StoredEventHandler());
    }

    // Otherwise, we should get the module file from a registry.
    if (key.getVersion().isEmpty()) {
      // Print a friendlier error message if the user forgets to specify a version *and* doesn't
      // have a non-registry override.
      throw errorf(
          Code.MODULE_NOT_FOUND,
          "bad bazel_dep on module '%s' with no version. Did you forget to specify a version, or a"
              + " non-registry override?",
          key.getName());
    }
    ImmutableSet<String> registries = Objects.requireNonNull(REGISTRIES.get(env));
    if (override instanceof RegistryOverride) {
      String overrideRegistry = ((RegistryOverride) override).getRegistry();
      if (!overrideRegistry.isEmpty()) {
        registries = ImmutableSet.of(overrideRegistry);
      }
    } else if (override != null) {
      // This should never happen.
      // TODO(wyv): make ModuleOverride a sealed interface so this is checked at compile time.
      throw new IllegalStateException(
          String.format(
              "unrecognized override type %s for module %s",
              override.getClass().getSimpleName(), key));
    }

    List<RegistryKey> registryKeys =
        registries.stream().map(RegistryKey::create).collect(toImmutableList());
    var registryResult = env.getValuesAndExceptions(registryKeys);
    if (env.valuesMissing()) {
      return null;
    }
    List<Registry> registryObjects = new ArrayList<>(registryKeys.size());
    for (RegistryKey registryKey : registryKeys) {
      Registry registry = (Registry) registryResult.get(registryKey);
      if (registry == null) {
        return null;
      }
      registryObjects.add(registry);
    }

    // Now go through the list of registries and use the first one that contains the requested
    // module.
    StoredEventHandler downloadEventHandler = new StoredEventHandler();
    List<String> notFoundTrace = null;
    for (Registry registry : registryObjects) {
      try {
        ModuleFile originalModuleFile;
        try {
          originalModuleFile =
              registry.getModuleFile(key, downloadEventHandler, this.downloadManager);
        } catch (Registry.NotFoundException e) {
          if (notFoundTrace == null) {
            notFoundTrace = new ArrayList<>();
          }
          notFoundTrace.add(e.getMessage());
          continue;
        }
        ModuleFile moduleFile = maybePatchModuleFile(originalModuleFile, override, env);
        if (moduleFile == null) {
          return null;
        }
        return new GetModuleFileResult(moduleFile, registry, downloadEventHandler);
      } catch (MissingChecksumException e) {
        throw new ModuleFileFunctionException(
            ExternalDepsException.withCause(Code.BAD_LOCKFILE, e));
      } catch (IOException e) {
        throw errorf(
            Code.ERROR_ACCESSING_REGISTRY, e, "Error accessing registry %s", registry.getUrl());
      }
    }

    throw errorf(
        Code.MODULE_NOT_FOUND,
        "module %s not found in registries:\n* %s",
        key,
        String.join("\n* ", notFoundTrace));
  }

  /**
   * Applies any patches specified in registry overrides.
   *
   * <p>This allows users to modify MODULE.bazel files and thus influence resolution and visibility
   * for modules via patches without having to replace the entire module via a non-registry
   * override.
   *
   * <p>Note: Only patch files from the main repo are applied, all other patches are ignored. This
   * is necessary as we can't load other repos during resolution (unless they are subject to a
   * non-registry override). Patch commands are also not supported as they cannot be selectively
   * applied to the module file only.
   */
  @Nullable
  private ModuleFile maybePatchModuleFile(
      ModuleFile moduleFile, ModuleOverride override, Environment env)
      throws InterruptedException, ModuleFileFunctionException {
    if (!(override instanceof SingleVersionOverride singleVersionOverride)) {
      return moduleFile;
    }
    var patchesInMainRepo =
        singleVersionOverride.getPatches().stream()
            .filter(label -> label.getRepository().isMain())
            .collect(toImmutableList());
    if (patchesInMainRepo.isEmpty()) {
      return moduleFile;
    }

    // Get the patch paths.
    var patchPackageLookupKeys =
        patchesInMainRepo.stream()
            .map(Label::getPackageIdentifier)
            .map(pkg -> (SkyKey) PackageLookupValue.key(pkg))
            .collect(toImmutableList());
    var patchPackageLookupResult = env.getValuesAndExceptions(patchPackageLookupKeys);
    if (env.valuesMissing()) {
      return null;
    }
    List<RootedPath> patchPaths = new ArrayList<>();
    for (int i = 0; i < patchesInMainRepo.size(); i++) {
      var pkgLookupValue =
          (PackageLookupValue) patchPackageLookupResult.get(patchPackageLookupKeys.get(i));
      if (pkgLookupValue == null) {
        return null;
      }
      if (!pkgLookupValue.packageExists()) {
        Label label = patchesInMainRepo.get(i);
        throw errorf(
            Code.BAD_MODULE,
            "unable to load package for single_version_override patch %s: %s",
            label,
            PackageLookupFunction.explainNoBuildFileValue(label.getPackageIdentifier(), env));
      }
      patchPaths.add(
          RootedPath.toRootedPath(
              pkgLookupValue.getRoot(), patchesInMainRepo.get(i).toPathFragment()));
    }

    // Register dependencies on the patch files and ensure that they exist.
    var fileKeys = Iterables.transform(patchPaths, FileValue::key);
    var fileValueResult = env.getValuesAndExceptions(fileKeys);
    if (env.valuesMissing()) {
      return null;
    }
    for (var key : fileKeys) {
      var fileValue = (FileValue) fileValueResult.get(key);
      if (fileValue == null) {
        return null;
      }
      if (!fileValue.isFile()) {
        throw errorf(
            Code.BAD_MODULE,
            "error reading single_version_override patch %s: not a regular file",
            key.argument());
      }
    }

    // Apply the patches to the module file only.
    InMemoryFileSystem patchFs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    try {
      Path moduleRoot = patchFs.getPath("/module");
      moduleRoot.createDirectoryAndParents();
      Path moduleFilePath = moduleRoot.getChild("MODULE.bazel");
      FileSystemUtils.writeContent(moduleFilePath, moduleFile.getContent());
      for (var patchPath : patchPaths) {
        try {
          PatchUtil.applyToSingleFile(
              patchPath.asPath(),
              singleVersionOverride.getPatchStrip(),
              moduleRoot,
              moduleFilePath);
        } catch (PatchFailedException e) {
          throw errorf(
              Code.BAD_MODULE,
              "error applying single_version_override patch %s to module file: %s",
              patchPath.asPath(),
              e.getMessage());
        }
      }
      return ModuleFile.create(
          FileSystemUtils.readContent(moduleFilePath), moduleFile.getLocation());
    } catch (IOException e) {
      throw errorf(
          Code.BAD_MODULE,
          "error applying single_version_override patches to module file: %s",
          e.getMessage());
    }
  }

  private static byte[] readModuleFile(Path path) throws ModuleFileFunctionException {
    try {
      return FileSystemUtils.readWithKnownFileSize(path, path.getFileSize());
    } catch (IOException e) {
      throw errorf(
          Code.MODULE_NOT_FOUND,
          "MODULE.bazel expected but not found at %s: %s",
          path,
          e.getMessage());
    }
  }

  private static void createModuleFile(Path path, byte[] bytes) throws ModuleFileFunctionException {
    try {
      FileSystemUtils.writeContent(path, bytes);
    } catch (IOException e) {
      throw errorf(
          Code.EXTERNAL_DEPS_UNKNOWN,
          "MODULE.bazel cannot be created at %s: %s",
          path,
          e.getMessage());
    }
  }

  @FormatMethod
  private static ModuleFileFunctionException errorf(Code code, String format, Object... args) {
    return new ModuleFileFunctionException(ExternalDepsException.withMessage(code, format, args));
  }

  @FormatMethod
  private static ModuleFileFunctionException errorf(
      Code code, Throwable cause, String format, Object... args) {
    return new ModuleFileFunctionException(
        ExternalDepsException.withCauseAndMessage(code, cause, format, args));
  }

  static final class ModuleFileFunctionException extends SkyFunctionException {

    ModuleFileFunctionException(ExternalDepsException cause) {
      super(cause, Transience.TRANSIENT);
    }

    ModuleFileFunctionException(ExternalDepsException cause, Transience transience) {
      super(cause, transience);
    }
  }

  public static ImmutableMap<String, NonRegistryOverride> getBuiltinModules(
      Path embeddedBinariesRoot) {
    return ImmutableMap.of(
        // @bazel_tools is a special repo that we pull from the extracted install dir.
        "bazel_tools",
        LocalPathOverride.create(embeddedBinariesRoot.getChild("embedded_tools").getPathString()),
        // @local_config_platform is currently generated by the native repo rule
        // local_config_platform
        // It has to be a special repo for now because:
        //   - It's embedded in local_config_platform.WORKSPACE and depended on by many
        // toolchains.
        //   - The canonical name "local_config_platform" is hardcoded in Bazel code.
        //     See {@link PlatformOptions}
        "local_config_platform",
        new NonRegistryOverride() {
          @Override
          public RepoSpec getRepoSpec() {
            return RepoSpec.builder()
                .setRuleClassName("local_config_platform")
                .setAttributes(AttributeValues.create(ImmutableMap.of()))
                .build();
          }

          @Override
          public BazelModuleInspectorValue.AugmentedModule.ResolutionReason getResolutionReason() {
            // NOTE: It is not exactly a LOCAL_PATH_OVERRIDE, but there is no inspection
            // ResolutionReason for builtin modules
            return BazelModuleInspectorValue.AugmentedModule.ResolutionReason.LOCAL_PATH_OVERRIDE;
          }
        });
  }
}
