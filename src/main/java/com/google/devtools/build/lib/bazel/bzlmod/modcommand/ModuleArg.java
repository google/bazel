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
package com.google.devtools.build.lib.bazel.bzlmod.modcommand;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey;
import com.google.devtools.build.lib.bazel.bzlmod.Version;
import com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.server.FailureDetails.ModCommand.Code;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.Converters.CommaSeparatedNonEmptyOptionListConverter;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.Optional;
import net.starlark.java.eval.EvalException;

/**
 * Represents a reference to one or more modules in the external dependency graph, used for
 * modquery. This is parsed from a command-line argument (either as the value of a flag, or just as
 * a bare argument), and can take one of various forms (see implementations).
 */
public interface ModuleArg {

  /** Resolves this module argument to a set of module keys. */
  ImmutableSet<ModuleKey> resolveToModuleKeys(
      ImmutableMap<String, ImmutableSet<ModuleKey>> modulesIndex,
      ImmutableMap<ModuleKey, AugmentedModule> depGraph,
      ImmutableMap<ModuleKey, RepositoryName> moduleKeyToCanonicalNames,
      ImmutableBiMap<String, ModuleKey> baseModuleDeps,
      ImmutableBiMap<String, ModuleKey> baseModuleUnusedDeps,
      boolean includeUnused,
      boolean warnUnused)
      throws InvalidArgumentException;

  /** Resolves this module argument to a set of repo names. */
  ImmutableMap<String, RepositoryName> resolveToRepoNames(
      ImmutableMap<String, ImmutableSet<ModuleKey>> modulesIndex,
      ImmutableMap<ModuleKey, AugmentedModule> depGraph,
      ImmutableMap<ModuleKey, RepositoryName> moduleKeyToCanonicalNames,
      RepositoryMapping mapping)
      throws InvalidArgumentException;

  /**
   * Refers to a specific version of a module. Parsed from {@code <module>@<version>}. {@code
   * <version>} can be the special string {@code _} to signify the empty version (for non-registry
   * overrides).
   */
  public record SpecificVersionOfModule(ModuleKey moduleKey) implements ModuleArg {
    public SpecificVersionOfModule {
      requireNonNull(moduleKey, "moduleKey");
    }

    static SpecificVersionOfModule create(ModuleKey key) {
      return new SpecificVersionOfModule(key);
    }

    private void throwIfNonexistent(
        ImmutableMap<String, ImmutableSet<ModuleKey>> modulesIndex,
        ImmutableMap<ModuleKey, AugmentedModule> depGraph,
        boolean includeUnused,
        boolean warnUnused)
        throws InvalidArgumentException {
      AugmentedModule mod = depGraph.get(moduleKey());
      if (mod != null && !includeUnused && warnUnused && !mod.isUsed()) {
        // Warn the user when unused modules are allowed and the specified version exists, but the
        // --include_unused flag was not set.
        throw new InvalidArgumentException(
            String.format(
                "Module version %s is unused as a result of module resolution. Use the"
                    + " --include_unused flag to include it.",
                moduleKey()),
            Code.INVALID_ARGUMENTS);
      }
      if (mod == null || (!includeUnused && !mod.isUsed())) {
        ImmutableSet<ModuleKey> existingKeys = modulesIndex.get(moduleKey().name());
        if (existingKeys == null) {
          throw new InvalidArgumentException(
              String.format(
                  "Module %s does not exist in the dependency graph.", moduleKey().name()),
              Code.INVALID_ARGUMENTS);
        }
        // If --include_unused is not true, unused modules will be considered non-existent and an
        // error will be thrown.
        ImmutableSet<ModuleKey> filteredKeys =
            existingKeys.stream()
                .filter(k -> includeUnused || depGraph.get(k).isUsed())
                .collect(toImmutableSet());
        throw new InvalidArgumentException(
            String.format(
                "Module version %s does not exist, available versions: %s.",
                moduleKey(), filteredKeys),
            Code.INVALID_ARGUMENTS);
      }
    }

    @Override
    public final ImmutableSet<ModuleKey> resolveToModuleKeys(
        ImmutableMap<String, ImmutableSet<ModuleKey>> modulesIndex,
        ImmutableMap<ModuleKey, AugmentedModule> depGraph,
        ImmutableMap<ModuleKey, RepositoryName> moduleKeyToCanonicalNames,
        ImmutableBiMap<String, ModuleKey> baseModuleDeps,
        ImmutableBiMap<String, ModuleKey> baseModuleUnusedDeps,
        boolean includeUnused,
        boolean warnUnused)
        throws InvalidArgumentException {
      throwIfNonexistent(modulesIndex, depGraph, includeUnused, warnUnused);
      return ImmutableSet.of(moduleKey());
    }

    @Override
    public ImmutableMap<String, RepositoryName> resolveToRepoNames(
        ImmutableMap<String, ImmutableSet<ModuleKey>> modulesIndex,
        ImmutableMap<ModuleKey, AugmentedModule> depGraph,
        ImmutableMap<ModuleKey, RepositoryName> moduleKeyToCanonicalNames,
        RepositoryMapping mapping)
        throws InvalidArgumentException {
      throwIfNonexistent(
          modulesIndex, depGraph, /* includeUnused= */ false, /* warnUnused= */ false);
      return ImmutableMap.of(moduleKey().toString(), moduleKeyToCanonicalNames.get(moduleKey()));
    }

    @Override
    public final String toString() {
      return moduleKey().toString();
    }
  }

  /** Refers to all versions of a module. Parsed from {@code <module>}. */
  public record AllVersionsOfModule(String moduleName) implements ModuleArg {
    public AllVersionsOfModule {
      requireNonNull(moduleName, "moduleName");
    }

    static AllVersionsOfModule create(String moduleName) {
      return new AllVersionsOfModule(moduleName);
    }

    private ImmutableSet<ModuleKey> resolveInternal(
        ImmutableMap<String, ImmutableSet<ModuleKey>> modulesIndex,
        ImmutableMap<ModuleKey, AugmentedModule> depGraph,
        boolean includeUnused,
        boolean warnUnused)
        throws InvalidArgumentException {
      ImmutableSet<ModuleKey> existingKeys = modulesIndex.get(moduleName());
      if (existingKeys == null) {
        throw new InvalidArgumentException(
            String.format("Module %s does not exist in the dependency graph.", moduleName()),
            Code.INVALID_ARGUMENTS);
      }
      ImmutableSet<ModuleKey> filteredKeys =
          existingKeys.stream()
              .filter(k -> includeUnused || depGraph.get(k).isUsed())
              .collect(toImmutableSet());
      if (filteredKeys.isEmpty()) {
        if (warnUnused) {
          throw new InvalidArgumentException(
              String.format(
                  "Module %s is unused as a result of module resolution. Use the --include_unused"
                      + " flag to include it.",
                  moduleName()),
              Code.INVALID_ARGUMENTS);
        }
        throw new InvalidArgumentException(
            String.format("Module %s does not exist in the dependency graph.", moduleName()),
            Code.INVALID_ARGUMENTS);
      }
      return filteredKeys;
    }

    @Override
    public ImmutableSet<ModuleKey> resolveToModuleKeys(
        ImmutableMap<String, ImmutableSet<ModuleKey>> modulesIndex,
        ImmutableMap<ModuleKey, AugmentedModule> depGraph,
        ImmutableMap<ModuleKey, RepositoryName> moduleKeyToCanonicalNames,
        ImmutableBiMap<String, ModuleKey> baseModuleDeps,
        ImmutableBiMap<String, ModuleKey> baseModuleUnusedDeps,
        boolean includeUnused,
        boolean warnUnused)
        throws InvalidArgumentException {
      return resolveInternal(modulesIndex, depGraph, includeUnused, warnUnused);
    }

    @Override
    public ImmutableMap<String, RepositoryName> resolveToRepoNames(
        ImmutableMap<String, ImmutableSet<ModuleKey>> modulesIndex,
        ImmutableMap<ModuleKey, AugmentedModule> depGraph,
        ImmutableMap<ModuleKey, RepositoryName> moduleKeyToCanonicalNames,
        RepositoryMapping mapping)
        throws InvalidArgumentException {
      return resolveInternal(
              modulesIndex, depGraph, /* includeUnused= */ false, /* warnUnused= */ false)
          .stream()
          .collect(toImmutableMap(ModuleKey::toString, moduleKeyToCanonicalNames::get));
    }

    @Override
    public final String toString() {
      return moduleName();
    }
  }

  /**
   * Refers to a module with the given apparent repo name, in the context of {@code --base_module}
   * (or when parsing that flag itself, in the context of the root module). Parsed from
   * {@code @<name>}.
   */
  public record ApparentRepoName(String name) implements ModuleArg {
    public ApparentRepoName {
      requireNonNull(name, "name");
    }

    static ApparentRepoName create(String name) {
      return new ApparentRepoName(name);
    }

    @Override
    public ImmutableSet<ModuleKey> resolveToModuleKeys(
        ImmutableMap<String, ImmutableSet<ModuleKey>> modulesIndex,
        ImmutableMap<ModuleKey, AugmentedModule> depGraph,
        ImmutableMap<ModuleKey, RepositoryName> moduleKeyToCanonicalNames,
        ImmutableBiMap<String, ModuleKey> baseModuleDeps,
        ImmutableBiMap<String, ModuleKey> baseModuleUnusedDeps,
        boolean includeUnused,
        boolean warnUnused)
        throws InvalidArgumentException {
      ImmutableSet.Builder<ModuleKey> builder = new ImmutableSet.Builder<>();
      ModuleKey dep = baseModuleDeps.get(name());
      if (dep != null) {
        builder.add(dep);
      }
      ModuleKey unusedDep = baseModuleUnusedDeps.get(name());
      if (includeUnused && unusedDep != null) {
        builder.add(unusedDep);
      }
      var result = builder.build();
      if (result.isEmpty()) {
        throw new InvalidArgumentException(
            String.format(
                "No module with the apparent repo name @%s exists in the dependency graph", name()),
            Code.INVALID_ARGUMENTS);
      }
      return result;
    }

    @Override
    public ImmutableMap<String, RepositoryName> resolveToRepoNames(
        ImmutableMap<String, ImmutableSet<ModuleKey>> modulesIndex,
        ImmutableMap<ModuleKey, AugmentedModule> depGraph,
        ImmutableMap<ModuleKey, RepositoryName> moduleKeyToCanonicalNames,
        RepositoryMapping mapping)
        throws InvalidArgumentException {
      RepositoryName repoName = mapping.get(name());
      if (!repoName.isVisible()) {
        throw new InvalidArgumentException(
            String.format(
                "No repo visible as %s from @%s", name(), repoName.getOwnerRepoDisplayString()),
            Code.INVALID_ARGUMENTS);
      }
      return ImmutableMap.of(toString(), repoName);
    }

    @Override
    public final String toString() {
      return "@" + name();
    }
  }

  /** Refers to a module with the given canonical repo name. Parsed from {@code @@<name>}. */
  public record CanonicalRepoName(RepositoryName repoName) implements ModuleArg {
    public CanonicalRepoName {
      requireNonNull(repoName, "repoName");
    }

    static CanonicalRepoName create(RepositoryName repoName) {
      return new CanonicalRepoName(repoName);
    }

    @Override
    public ImmutableSet<ModuleKey> resolveToModuleKeys(
        ImmutableMap<String, ImmutableSet<ModuleKey>> modulesIndex,
        ImmutableMap<ModuleKey, AugmentedModule> depGraph,
        ImmutableMap<ModuleKey, RepositoryName> moduleKeyToCanonicalNames,
        ImmutableBiMap<String, ModuleKey> baseModuleDeps,
        ImmutableBiMap<String, ModuleKey> baseModuleUnusedDeps,
        boolean includeUnused,
        boolean warnUnused)
        throws InvalidArgumentException {
      Optional<AugmentedModule> mod =
          depGraph.values().stream()
              .filter(m -> repoName().equals(moduleKeyToCanonicalNames.get(m.key())))
              .findAny();
      if (mod.isPresent() && !includeUnused && warnUnused && !mod.get().isUsed()) {
        // Warn the user when unused modules are allowed and the specified version exists, but the
        // --include_unused flag was not set.
        throw new InvalidArgumentException(
            String.format(
                "Module version %s is unused as a result of module resolution. Use the"
                    + " --include_unused flag to include it.",
                mod.get().key()),
            Code.INVALID_ARGUMENTS);
      }
      if (mod.isEmpty() || (!includeUnused && !mod.get().isUsed())) {
        // If --include_unused is not true, unused modules will be considered non-existent and an
        // error will be thrown.
        throw new InvalidArgumentException(
            String.format(
                "No module with the canonical repo name @@%s exists in the dependency graph",
                repoName().getName()),
            Code.INVALID_ARGUMENTS);
      }
      return ImmutableSet.of(mod.get().key());
    }

    @Override
    public ImmutableMap<String, RepositoryName> resolveToRepoNames(
        ImmutableMap<String, ImmutableSet<ModuleKey>> modulesIndex,
        ImmutableMap<ModuleKey, AugmentedModule> depGraph,
        ImmutableMap<ModuleKey, RepositoryName> moduleKeyToCanonicalNames,
        RepositoryMapping mapping) {
      return ImmutableMap.of(toString(), repoName());
    }

    @Override
    public final String toString() {
      return "@@" + repoName().getName();
    }
  }

  /** Converter for {@link ModuleArg}. */
  final class ModuleArgConverter extends Converter.Contextless<ModuleArg> {
    public static final ModuleArgConverter INSTANCE = new ModuleArgConverter();

    @Override
    public ModuleArg convert(String input) throws OptionsParsingException {
      if (Ascii.equalsIgnoreCase(input, "<root>")) {
        return SpecificVersionOfModule.create(ModuleKey.ROOT);
      }
      if (input.startsWith("@@")) {
        try {
          return CanonicalRepoName.create(RepositoryName.create(input.substring(2)));
        } catch (LabelSyntaxException e) {
          throw new OptionsParsingException("invalid argument '" + input + "': " + e.getMessage());
        }
      }
      if (input.startsWith("@")) {
        String apparentRepoName = input.substring(1);
        try {
          RepositoryName.validateUserProvidedRepoName(apparentRepoName);
        } catch (EvalException e) {
          throw new OptionsParsingException("invalid argument '" + input + "': " + e.getMessage());
        }
        return ApparentRepoName.create(apparentRepoName);
      }
      int atIdx = input.indexOf('@');
      if (atIdx >= 0) {
        String moduleName = input.substring(0, atIdx);
        String versionStr = input.substring(atIdx + 1);
        if (versionStr.isEmpty()) {
          throw new OptionsParsingException(
              "invalid argument '" + input + "': use _ for the empty version");
        }
        try {
          Version version = versionStr.equals("_") ? Version.EMPTY : Version.parse(versionStr);
          return SpecificVersionOfModule.create(new ModuleKey(moduleName, version));
        } catch (ParseException e) {
          throw new OptionsParsingException("invalid argument '" + input + "': " + e.getMessage());
        }
      }
      return AllVersionsOfModule.create(input);
    }

    @Override
    public String getTypeDescription() {
      return "\"<root>\" for the root module; <module>@<version> for a specific version of a"
          + " module; <module> for all versions of a module; @<name> for a repo with the"
          + " given apparent name; or @@<name> for a repo with the given canonical name";
    }
  }

  /** Converter for a comma-separated list of {@link ModuleArg}s. */
  class CommaSeparatedModuleArgListConverter
      extends Converter.Contextless<ImmutableList<ModuleArg>> {

    @Override
    public ImmutableList<ModuleArg> convert(String input) throws OptionsParsingException {
      ImmutableList<String> args = new CommaSeparatedNonEmptyOptionListConverter().convert(input);
      ImmutableList.Builder<ModuleArg> moduleArgs = new ImmutableList.Builder<>();
      for (String arg : args) {
        moduleArgs.add(ModuleArgConverter.INSTANCE.convert(arg));
      }
      return moduleArgs.build();
    }

    @Override
    public String getTypeDescription() {
      return "a comma-separated list of <module>s";
    }
  }
}
