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

package com.google.devtools.build.lib.bazel.bzlmod.modcommand;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule.ResolutionReason;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey;
import com.google.devtools.build.lib.bazel.bzlmod.Version;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModOptions.OutputFormat;
import java.io.PrintWriter;
import javax.annotation.Nullable;

/**
 * Contains the output formatters for the graph-based results of {@link ModExecutor} that can be
 * specified using {@link ModOptions#outputFormat}.
 */
public final class OutputFormatters {

  private static final OutputFormatter textFormatter = new TextOutputFormatter();
  private static final OutputFormatter jsonFormatter = new JsonOutputFormatter();
  private static final OutputFormatter graphvizFormatter = new GraphvizOutputFormatter();

  private OutputFormatters() {}

  static OutputFormatter getFormatter(OutputFormat format) {
    return switch (format) {
      case TEXT -> textFormatter;
      case JSON -> jsonFormatter;
      case GRAPH -> graphvizFormatter;
      case null -> throw new IllegalArgumentException("Output format cannot be null.");
    };
  }

  abstract static class OutputFormatter {

    protected ImmutableMap<ModuleKey, ResultNode> result;
    protected ImmutableMap<ModuleKey, AugmentedModule> depGraph;
    protected ImmutableSetMultimap<ModuleExtensionId, String> extensionRepos;
    protected ImmutableMap<ModuleExtensionId, ImmutableSetMultimap<String, ModuleKey>>
        extensionRepoImports;
    protected PrintWriter printer;
    protected ModOptions options;

    /**
     * Compact representation of the data provided by the {@code --verbose} flag.
     *
     * @param changedVersion The version from/to which the module was changed after resolution.
     * @param requestedByModules The list of modules who originally requested the selected version
     *     in the case of Minimal-Version-Selection.
     */
    record Explanation(
        Version changedVersion,
        ResolutionReason resolutionReason,
        @Nullable ImmutableSet<ModuleKey> requestedByModules) {
      Explanation {
        requireNonNull(changedVersion, "changedVersion");
        requireNonNull(resolutionReason, "resolutionReason");
      }

      static Explanation create(
          Version version, ResolutionReason reason, ImmutableSet<ModuleKey> requestedByModules) {
        return new Explanation(version, reason, requestedByModules);
      }

      /**
       * Gets the exact label that is printed next to the module if the {@code --verbose} flag is
       * enabled.
       */
      String toExplanationString(boolean unused) {
        String changedVersionLabel =
            changedVersion().equals(Version.EMPTY) ? "_" : changedVersion().toString();
        String toOrWasString = unused ? "to" : "was";
        String reasonString =
            requestedByModules() != null
                ? requestedByModules().stream().map(ModuleKey::toString).collect(joining(", "))
                : Ascii.toLowerCase(resolutionReason().toString());
        return String.format("(%s %s, cause %s)", toOrWasString, changedVersionLabel, reasonString);
      }
    }

    /** Exposed API of the formatter during which the necessary objects are injected. */
    void output(
        ImmutableMap<ModuleKey, ResultNode> result,
        ImmutableMap<ModuleKey, AugmentedModule> depGraph,
        ImmutableSetMultimap<ModuleExtensionId, String> extensionRepos,
        ImmutableMap<ModuleExtensionId, ImmutableSetMultimap<String, ModuleKey>>
            extensionRepoImports,
        PrintWriter printer,
        ModOptions options) {
      this.result = result;
      this.depGraph = depGraph;
      this.extensionRepos = extensionRepos;
      this.extensionRepoImports = extensionRepoImports;
      this.printer = printer;
      this.options = options;
      output();
      printer.flush();
    }

    /** Internal implementation of the formatter output function. */
    protected abstract void output();

    /**
     * Exists only for testing, because normally the depGraph and options are injected inside the
     * public API call.
     */
    protected Explanation getExtraResolutionExplanation(
        ModuleKey key,
        ModuleKey parent,
        ImmutableMap<ModuleKey, AugmentedModule> depGraph,
        ModOptions options) {
      this.depGraph = depGraph;
      this.options = options;
      return getExtraResolutionExplanation(key, parent);
    }

    /**
     * Returns {@code null} if the module version has not changed during resolution or if the module
     * is <i>&lt;root&gt;</i>.
     */
    @Nullable
    protected Explanation getExtraResolutionExplanation(ModuleKey key, ModuleKey parent) {
      if (key.equals(ModuleKey.ROOT)) {
        return null;
      }
      AugmentedModule module = depGraph.get(key);
      AugmentedModule parentModule = depGraph.get(parent);
      String repoName = parentModule.getAllDeps(options.includeUnused).get(key);
      Version changedVersion;
      ImmutableSet<ModuleKey> changedByModules = null;
      ResolutionReason reason = parentModule.depReasons().get(repoName);
      AugmentedModule replacement =
          module.isUsed() ? module : depGraph.get(parentModule.deps().get(repoName));
      if (reason != ResolutionReason.ORIGINAL) {
        if (!module.isUsed()) {
          changedVersion = replacement.version();
        } else {
          AugmentedModule old = depGraph.get(parentModule.unusedDeps().get(repoName));
          changedVersion = old.version();
        }
        if (reason == ResolutionReason.MINIMAL_VERSION_SELECTION) {
          changedByModules = replacement.originalDependants();
        }
        return Explanation.create(changedVersion, reason, changedByModules);
      }
      return null;
    }
  }
}
