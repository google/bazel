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

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModuleArg.ModuleArgConverter;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.Label.RepoContext;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.server.FailureDetails.ModCommand.Code;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.Converters.CommaSeparatedNonEmptyOptionListConverter;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.Optional;

/**
 * Represents a reference to a module extension, parsed from a command-line argument in the form of
 * {@code <module><bzl_file_label>%<extension_name>}. The {@code <module>} part is parsed as a
 * {@link ModuleArg}. Valid examples include {@code @rules_java//java:extensions.bzl%toolchains},
 * {@code rules_java@6.1.1//java:extensions.bzl%toolchains}, etc.
 */
public record ExtensionArg(ModuleArg moduleArg, String repoRelativeBzlLabel, String extensionName) {
  public ExtensionArg {
    requireNonNull(moduleArg, "moduleArg");
    requireNonNull(repoRelativeBzlLabel, "repoRelativeBzlLabel");
    requireNonNull(extensionName, "extensionName");
  }

  public static ExtensionArg create(
      ModuleArg moduleArg, String repoRelativeBzlLabel, String extensionName) {
    return new ExtensionArg(moduleArg, repoRelativeBzlLabel, extensionName);
  }

  /** Resolves this {@link ExtensionArg} to a {@link ModuleExtensionId}. */
  public final ModuleExtensionId resolveToExtensionId(
      ImmutableMap<String, ImmutableSet<ModuleKey>> modulesIndex,
      ImmutableMap<ModuleKey, AugmentedModule> depGraph,
      ImmutableMap<ModuleKey, RepositoryName> moduleKeyToCanonicalNames,
      ImmutableBiMap<String, ModuleKey> baseModuleDeps,
      ImmutableBiMap<String, ModuleKey> baseModuleUnusedDeps)
      throws InvalidArgumentException {
    ImmutableSet<ModuleKey> refModules =
        moduleArg()
            .resolveToModuleKeys(
                modulesIndex,
                depGraph,
                moduleKeyToCanonicalNames,
                baseModuleDeps,
                baseModuleUnusedDeps,
                /* includeUnused= */ false,
                /* warnUnused= */ false);
    if (refModules.size() != 1) {
      throw new InvalidArgumentException(
          String.format(
              "Module %s, as part of the extension specifier, should represent exactly one module"
                  + " version. Choose one of: %s.",
              moduleArg(), refModules),
          Code.INVALID_ARGUMENTS);
    }
    ModuleKey key = Iterables.getOnlyElement(refModules);
    try {
      Label label =
          Label.parseWithRepoContext(
              repoRelativeBzlLabel(),
              RepoContext.of(
                  moduleKeyToCanonicalNames.get(key),
                  // Intentionally allow no repo mapping here: it's a repo-relative label!
                  RepositoryMapping.create(ImmutableMap.of(), moduleKeyToCanonicalNames.get(key))));
      // TODO(wyv): support isolated extension usages?
      return ModuleExtensionId.create(label, extensionName(), Optional.empty());
    } catch (LabelSyntaxException e) {
      throw new InvalidArgumentException(
          String.format("bad label format in %s: %s", repoRelativeBzlLabel(), e.getMessage()),
          Code.INVALID_ARGUMENTS,
          e);
    }
  }

  @Override
  public final String toString() {
    return moduleArg() + repoRelativeBzlLabel() + "%" + extensionName();
  }

  /** Converter for {@link ExtensionArg}. */
  public static class ExtensionArgConverter extends Converter.Contextless<ExtensionArg> {
    public static final ExtensionArgConverter INSTANCE = new ExtensionArgConverter();

    @Override
    public ExtensionArg convert(String input) throws OptionsParsingException {
      int slashIdx = input.indexOf('/');
      if (slashIdx < 0) {
        throw new OptionsParsingException("Invalid argument " + input + ": missing .bzl label");
      }
      int percentIdx = input.indexOf('%');
      if (percentIdx < slashIdx) {
        throw new OptionsParsingException("Invalid argument " + input + ": missing extension name");
      }
      ModuleArg moduleArg = ModuleArgConverter.INSTANCE.convert(input.substring(0, slashIdx));
      return ExtensionArg.create(
          moduleArg, input.substring(slashIdx, percentIdx), input.substring(percentIdx + 1));
    }

    @Override
    public String getTypeDescription() {
      return "an <extension> identifier in the format of <module><bzl_label>%<extension_name>";
    }
  }

  /** Converter for a comma-separated list of {@link ExtensionArg}s. */
  public static class CommaSeparatedExtensionArgListConverter
      extends Converter.Contextless<ImmutableList<ExtensionArg>> {

    @Override
    public ImmutableList<ExtensionArg> convert(String input) throws OptionsParsingException {
      ImmutableList<String> args = new CommaSeparatedNonEmptyOptionListConverter().convert(input);
      ImmutableList.Builder<ExtensionArg> extensionArgs = new ImmutableList.Builder<>();
      for (String arg : args) {
        extensionArgs.add(ExtensionArgConverter.INSTANCE.convert(arg));
      }
      return extensionArgs.build();
    }

    @Override
    public String getTypeDescription() {
      return "a comma-separated list of <extension>s";
    }
  }
}
