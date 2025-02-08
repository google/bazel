// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.commands;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableListMultimap.toImmutableListMultimap;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModOptions.Charset.UTF8;
import static com.google.devtools.build.lib.runtime.Command.BuildPhase.LOADS;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.io.CharSource;
import com.google.devtools.build.lib.analysis.NoBuildEvent;
import com.google.devtools.build.lib.analysis.NoBuildRequestFinishedEvent;
import com.google.devtools.build.lib.bazel.bzlmod.BazelDepGraphValue;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModTidyValue;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodRepoRuleValue;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey;
import com.google.devtools.build.lib.bazel.bzlmod.RootModuleFileFixup;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ExtensionArg;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ExtensionArg.ExtensionArgConverter;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.InvalidArgumentException;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModOptions;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModOptions.ModSubcommand;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModOptions.ModSubcommandConverter;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModuleArg;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModuleArg.ModuleArgConverter;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.pkgcache.PackageOptions;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.LoadingPhaseThreadsOption;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.ModCommand.Code;
import com.google.devtools.build.lib.shell.AbnormalTerminationException;
import com.google.devtools.build.lib.shell.CommandException;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.CommandBuilder;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.InterruptedFailureDetails;
import com.google.devtools.build.lib.util.MaybeCompleteSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;
import javax.annotation.Nullable;

/** Queries the Bzlmod external dependency graph. */
@Command(
    name = ModCommand.NAME,
    buildPhase = LOADS,
    options = {
      ModOptions.class,
      PackageOptions.class,
      LoadingPhaseThreadsOption.class
    },
    help = "resource:mod.txt",
    shortDescription = "Queries the Bzlmod external dependency graph",
    allowResidue = true)
public final class ModCommand implements BlazeCommand {

  public static final String NAME = "mod";

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    env.getEventBus()
        .post(
            new NoBuildEvent(
                env.getCommandName(),
                env.getCommandStartTime(),
                /* separateFinishedEvent= */ true,
                /* showProgress= */ true,
                /* id= */ null));
    BlazeCommandResult result = execInternal(env, options);
    env.getEventBus()
        .post(
            new NoBuildRequestFinishedEvent(
                result.getExitCode(), env.getRuntime().getClock().currentTimeMillis()));
    return result;
  }

  private BlazeCommandResult execInternal(CommandEnvironment env, OptionsParsingResult options) {
    ModOptions modOptions = options.getOptions(ModOptions.class);
    Preconditions.checkArgument(modOptions != null);

    if (options.getResidue().isEmpty()) {
      String errorMessage =
          String.format(
              "No subcommand specified, choose one of : %s.", ModSubcommand.printValues());
      return reportAndCreateFailureResult(env, errorMessage, Code.MOD_COMMAND_UNKNOWN);
    }

    // The first element in the residue must be the subcommand, and then comes a list of arguments.
    String subcommandStr = options.getResidue().get(0);
    ModSubcommand subcommand;
    try {
      subcommand = new ModSubcommandConverter().convert(subcommandStr);
    } catch (OptionsParsingException e) {
      String errorMessage =
          String.format("Invalid subcommand, choose one from : %s.", ModSubcommand.printValues());
      return reportAndCreateFailureResult(env, errorMessage, Code.MOD_COMMAND_UNKNOWN);
    }
    List<String> args = options.getResidue().subList(1, options.getResidue().size());

    ImmutableList.Builder<RepositoryMappingValue.Key> repositoryMappingKeysBuilder =
        ImmutableList.builder();
    if (subcommand.equals(ModSubcommand.DUMP_REPO_MAPPING)) {
      if (args.isEmpty()) {
        // Make this case an error so that we are free to add a mode that emits all mappings in a
        // single JSON object later.
        return reportAndCreateFailureResult(
            env, "No repository name(s) specified", Code.INVALID_ARGUMENTS);
      }
      for (String arg : args) {
        try {
          repositoryMappingKeysBuilder.add(RepositoryMappingValue.key(RepositoryName.create(arg)));
        } catch (LabelSyntaxException e) {
          return reportAndCreateFailureResult(env, e.getMessage(), Code.INVALID_ARGUMENTS);
        }
      }
    }
    ImmutableList<RepositoryMappingValue.Key> repoMappingKeys =
        repositoryMappingKeysBuilder.build();

    BazelDepGraphValue depGraphValue;
    @Nullable BazelModuleInspectorValue moduleInspector;
    @Nullable BazelModTidyValue modTidyValue;
    ImmutableList<RepositoryMappingValue> repoMappingValues;

    SkyframeExecutor skyframeExecutor = env.getSkyframeExecutor();
    LoadingPhaseThreadsOption threadsOption = options.getOptions(LoadingPhaseThreadsOption.class);

    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setParallelism(threadsOption.threads)
            .setEventHandler(env.getReporter())
            .build();

    try {
      env.syncPackageLoading(options);

      ImmutableSet.Builder<SkyKey> keys = ImmutableSet.builder();
      if (subcommand.equals(ModSubcommand.DUMP_REPO_MAPPING)) {
        keys.addAll(repoMappingKeys);
      } else if (subcommand.equals(ModSubcommand.TIDY)) {
        keys.add(BazelModTidyValue.KEY);
      } else {
        keys.add(BazelDepGraphValue.KEY, BazelModuleInspectorValue.KEY);
      }
      EvaluationResult<SkyValue> evaluationResult =
          skyframeExecutor.prepareAndGet(keys.build(), evaluationContext);

      if (evaluationResult.hasError()) {
        Exception e = evaluationResult.getError().getException();
        String message = "Unexpected error during module graph evaluation.";
        if (e != null) {
          message = e.getMessage();
        }
        return reportAndCreateFailureResult(env, message, Code.INVALID_ARGUMENTS);
      }

      depGraphValue = (BazelDepGraphValue) evaluationResult.get(BazelDepGraphValue.KEY);

      moduleInspector =
          (BazelModuleInspectorValue) evaluationResult.get(BazelModuleInspectorValue.KEY);

      modTidyValue = (BazelModTidyValue) evaluationResult.get(BazelModTidyValue.KEY);

      repoMappingValues =
          repoMappingKeys.stream()
              .map(evaluationResult::get)
              .map(RepositoryMappingValue.class::cast)
              .collect(toImmutableList());
    } catch (InterruptedException e) {
      String errorMessage = "mod command interrupted: " + e.getMessage();
      env.getReporter().handle(Event.error(errorMessage));
      return BlazeCommandResult.detailedExitCode(
          InterruptedFailureDetails.detailedExitCode(errorMessage));
    } catch (AbruptExitException e) {
      env.getReporter().handle(Event.error(null, "Unknown error: " + e.getMessage()));
      return BlazeCommandResult.detailedExitCode(e.getDetailedExitCode());
    }

    // Handle commands that do not require BazelModuleInspectorValue.
    if (subcommand.equals(ModSubcommand.DUMP_REPO_MAPPING)) {
      String missingRepos =
          IntStream.range(0, repoMappingKeys.size())
              .filter(i -> repoMappingValues.get(i) == RepositoryMappingValue.NOT_FOUND_VALUE)
              .mapToObj(repoMappingKeys::get)
              .map(RepositoryMappingValue.Key::repoName)
              .map(RepositoryName::getName)
              .collect(joining(", "));
      if (!missingRepos.isEmpty()) {
        return reportAndCreateFailureResult(
            env, "Repositories not found: " + missingRepos, Code.INVALID_ARGUMENTS);
      }
      try (SilentCloseable c =
          Profiler.instance().profile(ProfilerTask.BZLMOD, "execute mod " + subcommand)) {
        dumpRepoMappings(
            repoMappingValues,
            new OutputStreamWriter(
                env.getReporter().getOutErr().getOutputStream(),
                modOptions.charset == UTF8 ? UTF_8 : US_ASCII));
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      return BlazeCommandResult.success();
    } else if (subcommand == ModSubcommand.TIDY) {
      // tidy doesn't take extra arguments.
      if (!args.isEmpty()) {
        return reportAndCreateFailureResult(
            env, "the 'tidy' command doesn't take extra arguments", Code.TOO_MANY_ARGUMENTS);
      }
      try (SilentCloseable c =
          Profiler.instance().profile(ProfilerTask.BZLMOD, "execute mod " + subcommand)) {
        return runTidy(env, modTidyValue);
      }
    }

    // Extract and check the --base_module argument first to use it when parsing the other args.
    // Can only be a TargetModule or a repoName relative to the ROOT.
    ModuleKey baseModuleKey;
    AugmentedModule rootModule = moduleInspector.depGraph().get(ModuleKey.ROOT);
    try {
      ImmutableSet<ModuleKey> keys =
          modOptions.baseModule.resolveToModuleKeys(
              moduleInspector.modulesIndex(),
              moduleInspector.depGraph(),
              moduleInspector.moduleKeyToCanonicalNames(),
              rootModule.deps(),
              rootModule.unusedDeps(),
              false,
              false);
      if (keys.size() > 1) {
        throw new InvalidArgumentException(
            String.format(
                "The --base_module option can only specify exactly one module version, choose one"
                    + " of: %s.",
                keys.stream().map(ModuleKey::toString).collect(joining(", "))),
            Code.INVALID_ARGUMENTS);
      }
      baseModuleKey = Iterables.getOnlyElement(keys);
    } catch (InvalidArgumentException e) {
      return reportAndCreateFailureResult(
          env,
          String.format(
              "In --base_module %s option: %s (Note that unused modules cannot be used here)",
              modOptions.baseModule, e.getMessage()),
          Code.INVALID_ARGUMENTS);
    }

    // The args can have different types depending on the subcommand, so create multiple containers
    // which can be filled accordingly.
    ImmutableSet<ModuleKey> argsAsModules = null;
    ImmutableSortedSet<ModuleExtensionId> argsAsExtensions = null;
    ImmutableMap<String, RepositoryName> argsAsRepos = null;

    AugmentedModule baseModule =
        Objects.requireNonNull(moduleInspector.depGraph().get(baseModuleKey));
    RepositoryMapping baseModuleMapping = depGraphValue.getFullRepoMapping(baseModuleKey);
    try {
      switch (subcommand) {
        case GRAPH -> {
          // GRAPH doesn't take extra arguments.
          if (!args.isEmpty()) {
            throw new InvalidArgumentException(
                "the 'graph' command doesn't take extra arguments", Code.TOO_MANY_ARGUMENTS);
          }
        }
        case SHOW_REPO -> {
          ImmutableMap.Builder<String, RepositoryName> targetToRepoName =
              new ImmutableMap.Builder<>();
          for (String arg : args) {
            try {
              targetToRepoName.putAll(
                  ModuleArgConverter.INSTANCE
                      .convert(arg)
                      .resolveToRepoNames(
                          moduleInspector.modulesIndex(),
                          moduleInspector.depGraph(),
                          moduleInspector.moduleKeyToCanonicalNames(),
                          baseModuleMapping));
            } catch (InvalidArgumentException | OptionsParsingException e) {
              throw new InvalidArgumentException(
                  String.format(
                      "In repo argument %s: %s (Note that unused modules cannot be used here)",
                      arg, e.getMessage()),
                  Code.INVALID_ARGUMENTS,
                  e);
            }
          }
          argsAsRepos = targetToRepoName.buildKeepingLast();
        }
        case SHOW_EXTENSION -> {
          ImmutableSortedSet.Builder<ModuleExtensionId> extensionsBuilder =
              new ImmutableSortedSet.Builder<>(ModuleExtensionId.LEXICOGRAPHIC_COMPARATOR);
          for (String arg : args) {
            try {
              extensionsBuilder.add(
                  ExtensionArgConverter.INSTANCE
                      .convert(arg)
                      .resolveToExtensionId(
                          moduleInspector.modulesIndex(),
                          moduleInspector.depGraph(),
                          moduleInspector.moduleKeyToCanonicalNames(),
                          baseModule.deps(),
                          baseModule.unusedDeps()));
            } catch (InvalidArgumentException | OptionsParsingException e) {
              throw new InvalidArgumentException(
                  String.format("In extension argument %s: %s", arg, e.getMessage()),
                  Code.INVALID_ARGUMENTS,
                  e);
            }
          }
          argsAsExtensions = extensionsBuilder.build();
        }
        default -> {
          ImmutableSet.Builder<ModuleKey> keysBuilder = new ImmutableSet.Builder<>();
          for (String arg : args) {
            try {
              keysBuilder.addAll(
                  ModuleArgConverter.INSTANCE
                      .convert(arg)
                      .resolveToModuleKeys(
                          moduleInspector.modulesIndex(),
                          moduleInspector.depGraph(),
                          moduleInspector.moduleKeyToCanonicalNames(),
                          baseModule.deps(),
                          baseModule.unusedDeps(),
                          modOptions.includeUnused,
                          /* warnUnused= */ true));
            } catch (InvalidArgumentException | OptionsParsingException e) {
              throw new InvalidArgumentException(
                  String.format("In module argument %s: %s", arg, e.getMessage()),
                  Code.INVALID_ARGUMENTS);
            }
          }
          argsAsModules = keysBuilder.build();
        }
      }
    } catch (InvalidArgumentException e) {
      return reportAndCreateFailureResult(env, e.getMessage(), e.getCode());
    }
    /* Extract and check the --from and --extension_usages argument */
    ImmutableSet<ModuleKey> fromKeys;
    ImmutableSet<ModuleKey> usageKeys;
    try {
      fromKeys =
          moduleArgListToKeys(
              modOptions.modulesFrom,
              moduleInspector.modulesIndex(),
              moduleInspector.depGraph(),
              moduleInspector.moduleKeyToCanonicalNames(),
              baseModule.deps(),
              baseModule.unusedDeps(),
              modOptions.includeUnused);
    } catch (InvalidArgumentException e) {
      return reportAndCreateFailureResult(
          env,
          String.format("In --from %s option: %s", modOptions.modulesFrom, e.getMessage()),
          Code.INVALID_ARGUMENTS);
    }

    try {
      usageKeys =
          moduleArgListToKeys(
              modOptions.extensionUsages,
              moduleInspector.modulesIndex(),
              moduleInspector.depGraph(),
              moduleInspector.moduleKeyToCanonicalNames(),
              baseModule.deps(),
              baseModule.unusedDeps(),
              modOptions.includeUnused);
    } catch (InvalidArgumentException e) {
      return reportAndCreateFailureResult(
          env,
          String.format(
              "In --extension_usages %s option: %s (Note that unused modules cannot be used"
                  + " here)",
              modOptions.extensionUsages, e.getMessage()),
          Code.INVALID_ARGUMENTS);
    }

    /* Extract and check the --extension_filter argument */
    Optional<MaybeCompleteSet<ModuleExtensionId>> filterExtensions = Optional.empty();
    if (subcommand.isGraph() && modOptions.extensionFilter != null) {
      if (modOptions.extensionFilter.isEmpty()) {
        filterExtensions = Optional.of(MaybeCompleteSet.completeSet());
      } else {
        try {
          filterExtensions =
              Optional.of(
                  MaybeCompleteSet.copyOf(
                      extensionArgListToIds(
                          modOptions.extensionFilter,
                          moduleInspector.modulesIndex(),
                          moduleInspector.depGraph(),
                          moduleInspector.moduleKeyToCanonicalNames(),
                          baseModule.deps(),
                          baseModule.unusedDeps())));
        } catch (InvalidArgumentException e) {
          return reportAndCreateFailureResult(
              env,
              String.format(
                  "In --extension_filter %s option: %s",
                  modOptions.extensionFilter, e.getMessage()),
              Code.INVALID_ARGUMENTS);
        }
      }
    }

    ImmutableMap<String, BzlmodRepoRuleValue> targetRepoRuleValues = null;
    try {
      // If subcommand is a SHOW, also request the BzlmodRepoRuleValues from Skyframe.
      if (subcommand == ModSubcommand.SHOW_REPO) {
        ImmutableSet<SkyKey> skyKeys =
            argsAsRepos.values().stream().map(BzlmodRepoRuleValue::key).collect(toImmutableSet());
        EvaluationResult<SkyValue> result =
            env.getSkyframeExecutor().prepareAndGet(skyKeys, evaluationContext);
        if (result.hasError()) {
          Exception e = result.getError().getException();
          String message = "Unexpected error during repository rule evaluation.";
          if (e != null) {
            message = e.getMessage();
          }
          return reportAndCreateFailureResult(env, message, Code.INVALID_ARGUMENTS);
        }
        targetRepoRuleValues =
            argsAsRepos.entrySet().stream()
                .collect(
                    toImmutableMap(
                        Entry::getKey,
                        e ->
                            (BzlmodRepoRuleValue)
                                result.get(BzlmodRepoRuleValue.key(e.getValue()))));
        for (Map.Entry<String, BzlmodRepoRuleValue> entry : targetRepoRuleValues.entrySet()) {
          if (entry.getValue() == BzlmodRepoRuleValue.REPO_RULE_NOT_FOUND_VALUE) {
            return reportAndCreateFailureResult(
                env,
                String.format("In repo argument %s: no such repo", entry.getKey()),
                Code.INVALID_ARGUMENTS);
          }
        }
      }
    } catch (InterruptedException e) {
      String errorMessage = "mod command interrupted: " + e.getMessage();
      env.getReporter().handle(Event.error(errorMessage));
      return BlazeCommandResult.detailedExitCode(
          InterruptedFailureDetails.detailedExitCode(errorMessage));
    }

    // Workaround to allow different default value for DEPS and EXPLAIN, and also use
    // Integer.MAX_VALUE instead of the exact number string.
    if (modOptions.depth < 1) {
      modOptions.depth =
          switch (subcommand) {
            case EXPLAIN -> 1;
            case DEPS -> 2;
            default -> Integer.MAX_VALUE;
          };
    }

    ModExecutor modExecutor =
        new ModExecutor(
            moduleInspector.depGraph(),
            depGraphValue.getExtensionUsagesTable(),
            moduleInspector.extensionToRepoInternalNames(),
            filterExtensions,
            modOptions,
            new OutputStreamWriter(
                env.getReporter().getOutErr().getOutputStream(),
                modOptions.charset == UTF8 ? UTF_8 : US_ASCII));

    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.BZLMOD, "execute mod " + subcommand)) {
      switch (subcommand) {
        case GRAPH -> modExecutor.graph(fromKeys);
        case DEPS -> modExecutor.graph(argsAsModules);
        case PATH -> modExecutor.path(fromKeys, argsAsModules);
        case ALL_PATHS, EXPLAIN -> modExecutor.allPaths(fromKeys, argsAsModules);
        case SHOW_REPO -> modExecutor.showRepo(targetRepoRuleValues);
        case SHOW_EXTENSION -> modExecutor.showExtension(argsAsExtensions, usageKeys);
        default -> throw new IllegalStateException("Unexpected subcommand: " + subcommand);
      }
    } catch (InvalidArgumentException e) {
      return reportAndCreateFailureResult(env, e.getMessage(), Code.INVALID_ARGUMENTS);
    }

    if (moduleInspector.errors().isEmpty()) {
      return BlazeCommandResult.success();
    } else {
      return reportAndCreateFailureResult(
          env,
          String.format(
              "Results may be incomplete as %d extension%s failed.",
              moduleInspector.errors().size(), moduleInspector.errors().size() == 1 ? "" : "s"),
          Code.ERROR_DURING_GRAPH_INSPECTION);
    }
  }

  private BlazeCommandResult runTidy(CommandEnvironment env, BazelModTidyValue modTidyValue) {
    ImmutableListMultimap<PathFragment, String> allCommandsPerFile =
        modTidyValue.fixups().stream()
            .flatMap(fixup -> fixup.moduleFilePathToBuildozerCommands().entries().stream())
            .collect(toImmutableListMultimap(Entry::getKey, Entry::getValue));
    StringBuilder buildozerInput = new StringBuilder();
    for (PathFragment moduleFilePath : modTidyValue.moduleFilePaths()) {
      buildozerInput.append("//").append(moduleFilePath).append(":all|");
      for (String command : allCommandsPerFile.get(moduleFilePath)) {
        buildozerInput.append(command).append('|');
      }
      buildozerInput.append("format\n");
    }

    try (var stdin = CharSource.wrap(buildozerInput).asByteSource(UTF_8).openStream()) {
      new CommandBuilder()
          .setWorkingDir(env.getWorkspace())
          .addArg(modTidyValue.buildozer().getPathString())
          .addArg("-f")
          .addArg("-")
          .build()
          .executeAsync(stdin, /* killSubprocessOnInterrupt= */ true)
          .get();
    } catch (InterruptedException | CommandException | IOException e) {
      String suffix = "";
      if (e instanceof AbnormalTerminationException abnormalTerminationException) {
        if (abnormalTerminationException.getResult().terminationStatus().getRawExitCode() == 3) {
          // Buildozer exits with exit code 3 if it didn't make any changes.
          return reportAndCreateTidyResult(env, modTidyValue);
        }
        suffix =
            ":\n" + new String(((AbnormalTerminationException) e).getResult().getStderr(), UTF_8);
      }
      return reportAndCreateFailureResult(
          env,
          "Unexpected error while running buildozer: " + e.getMessage() + suffix,
          Code.BUILDOZER_FAILED);
    }

    for (RootModuleFileFixup fixupEvent : modTidyValue.fixups()) {
      env.getReporter().handle(Event.info(fixupEvent.getSuccessMessage()));
    }

    return reportAndCreateTidyResult(env, modTidyValue);
  }

  private static BlazeCommandResult reportAndCreateTidyResult(
      CommandEnvironment env, BazelModTidyValue modTidyValue) {
    if (modTidyValue.errors().isEmpty()) {
      return BlazeCommandResult.success();
    } else {
      return reportAndCreateFailureResult(
          env,
          String.format(
              "Failed to process %d extension%s due to errors.",
              modTidyValue.errors().size(), modTidyValue.errors().size() == 1 ? "" : "s"),
          Code.ERROR_DURING_GRAPH_INSPECTION);
    }
  }

  /** Collects a list of {@link ModuleArg} into a set of {@link ModuleKey}s. */
  private static ImmutableSet<ModuleKey> moduleArgListToKeys(
      ImmutableList<ModuleArg> argList,
      ImmutableMap<String, ImmutableSet<ModuleKey>> modulesIndex,
      ImmutableMap<ModuleKey, AugmentedModule> depGraph,
      ImmutableMap<ModuleKey, RepositoryName> moduleKeyToCanonicalNames,
      ImmutableBiMap<String, ModuleKey> baseModuleDeps,
      ImmutableBiMap<String, ModuleKey> baseModuleUnusedDeps,
      boolean includeUnused)
      throws InvalidArgumentException {
    ImmutableSet.Builder<ModuleKey> allTargetKeys = new ImmutableSet.Builder<>();
    for (ModuleArg moduleArg : argList) {
      allTargetKeys.addAll(
          moduleArg.resolveToModuleKeys(
              modulesIndex,
              depGraph,
              moduleKeyToCanonicalNames,
              baseModuleDeps,
              baseModuleUnusedDeps,
              includeUnused,
              true));
    }
    return allTargetKeys.build();
  }

  private static ImmutableSortedSet<ModuleExtensionId> extensionArgListToIds(
      ImmutableList<ExtensionArg> args,
      ImmutableMap<String, ImmutableSet<ModuleKey>> modulesIndex,
      ImmutableMap<ModuleKey, AugmentedModule> depGraph,
      ImmutableMap<ModuleKey, RepositoryName> moduleKeyToCanonicalNames,
      ImmutableBiMap<String, ModuleKey> baseModuleDeps,
      ImmutableBiMap<String, ModuleKey> baseModuleUnusedDeps)
      throws InvalidArgumentException {
    ImmutableSortedSet.Builder<ModuleExtensionId> extensionsBuilder =
        new ImmutableSortedSet.Builder<>(ModuleExtensionId.LEXICOGRAPHIC_COMPARATOR);
    for (ExtensionArg arg : args) {
      extensionsBuilder.add(
          arg.resolveToExtensionId(
              modulesIndex,
              depGraph,
              moduleKeyToCanonicalNames,
              baseModuleDeps,
              baseModuleUnusedDeps));
    }
    return extensionsBuilder.build();
  }

  private static BlazeCommandResult reportAndCreateFailureResult(
      CommandEnvironment env, String message, Code detailedCode) {
    String fullMessage =
        switch (detailedCode) {
          case MISSING_ARGUMENTS, TOO_MANY_ARGUMENTS, INVALID_ARGUMENTS ->
              String.format(
                  "%s%s Type '%s help mod' for syntax and help.",
                  message, message.endsWith(".") ? "" : ".", env.getRuntime().getProductName());
          default -> message;
        };
    env.getReporter().handle(Event.error(fullMessage));
    return createFailureResult(fullMessage, detailedCode);
  }

  private static BlazeCommandResult createFailureResult(String message, Code detailedCode) {
    return BlazeCommandResult.detailedExitCode(
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setModCommand(FailureDetails.ModCommand.newBuilder().setCode(detailedCode).build())
                .setMessage(message)
                .build()));
  }

  public static void dumpRepoMappings(List<RepositoryMappingValue> repoMappings, Writer writer)
      throws IOException {
    Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    for (RepositoryMappingValue repoMapping : repoMappings) {
      JsonWriter jsonWriter = gson.newJsonWriter(writer);
      jsonWriter.beginObject();
      for (Entry<String, RepositoryName> entry :
          repoMapping.repositoryMapping().entries().entrySet()) {
        jsonWriter.name(entry.getKey());
        jsonWriter.value(entry.getValue().getName());
      }
      jsonWriter.endObject();
      writer.write('\n');
    }
    writer.flush();
  }
}
