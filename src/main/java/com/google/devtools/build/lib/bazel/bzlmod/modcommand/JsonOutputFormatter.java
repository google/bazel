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

import static com.google.common.collect.ImmutableSortedSet.toImmutableSortedSet;

import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode.IsCycle;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode.IsExpanded;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode.IsIndirect;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode.NodeMetadata;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModOptions.ExtensionShow;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.OutputFormatters.OutputFormatter;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;
import javax.annotation.Nullable;

/** Outputs graph-based results of {@link ModExecutor} in JSON format. */
public class JsonOutputFormatter extends OutputFormatter {
  private Set<ModuleExtensionId> seenExtensions;

  @Override
  public void output() {
    seenExtensions = new HashSet<>();
    JsonObject root = printModule(ModuleKey.ROOT, null, IsExpanded.TRUE, IsIndirect.FALSE);
    root.addProperty("root", true);
    printer.println(
        new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root));
  }

  public String printKey(ModuleKey key) {
    if (key.equals(ModuleKey.ROOT)) {
      return "<root>";
    }
    return key.toString();
  }

  // Helper to print module extensions similarly to printModule
  private JsonObject printExtension(
      ModuleKey key, ModuleExtensionId extensionId, boolean unexpanded) {
    JsonObject json = new JsonObject();
    json.addProperty("key", extensionId.toString());
    json.addProperty("unexpanded", unexpanded);
    if (options.extensionInfo == ExtensionShow.USAGES) {
      return json;
    }
    ImmutableSortedSet<String> repoImports =
        ImmutableSortedSet.copyOf(extensionRepoImports.get(extensionId).inverse().get(key));
    JsonArray usedRepos = new JsonArray();
    for (String usedRepo : repoImports) {
      usedRepos.add(usedRepo);
    }
    json.add("used_repos", usedRepos);

    if (unexpanded || options.extensionInfo == ExtensionShow.REPOS) {
      return json;
    }
    ImmutableSortedSet<String> unusedRepos =
        ImmutableSortedSet.copyOf(
            Sets.difference(
                extensionRepos.get(extensionId), extensionRepoImports.get(extensionId).keySet()));
    JsonArray unusedReposJson = new JsonArray();
    for (String unusedRepo : unusedRepos) {
      unusedReposJson.add(unusedRepo);
    }
    json.add("unused_repos", unusedReposJson);
    return json;
  }

  // Depth-first traversal to display modules (while explicitly detecting cycles)
  JsonObject printModule(
      ModuleKey key, @Nullable ModuleKey parent, IsExpanded expanded, IsIndirect indirect) {
    ResultNode node = result.get(key);
    AugmentedModule module = depGraph.get(key);
    JsonObject json = new JsonObject();
    json.addProperty("key", printKey(key));
    json.addProperty("name", module.name());
    json.addProperty("version", module.version().toString());
    String apparentName;
    if (parent != null) {
      // The apparent repository name under which parent refers to key.
      apparentName = depGraph.get(parent).deps().inverse().get(key);
    } else {
      // The apparent repository name under which key refers to itself.
      apparentName = module.repoName();
    }
    json.addProperty("apparentName", apparentName);

    if (indirect == IsIndirect.FALSE && options.verbose && parent != null) {
      Explanation explanation = getExtraResolutionExplanation(key, parent);
      if (explanation != null) {
        if (!module.isUsed()) {
          json.addProperty("unused", true);
          json.addProperty("resolvedVersion", explanation.changedVersion().toString());
        } else {
          json.addProperty("originalVersion", explanation.changedVersion().toString());
        }
        json.addProperty("resolutionReason", explanation.changedVersion().toString());
        if (explanation.requestedByModules() != null) {
          JsonArray requestedBy = new JsonArray();
          explanation.requestedByModules().forEach(k -> requestedBy.add(printKey(k)));
          json.add("resolvedRequestedBy", requestedBy);
        }
      }
    }

    if (expanded == IsExpanded.FALSE) {
      json.addProperty("unexpanded", true);
      return json;
    }

    JsonArray deps = new JsonArray();
    JsonArray indirectDeps = new JsonArray();
    JsonArray cycles = new JsonArray();
    for (Entry<ModuleKey, NodeMetadata> e : node.getChildrenSortedByEdgeType()) {
      ModuleKey childKey = e.getKey();
      IsExpanded childExpanded = e.getValue().isExpanded();
      IsIndirect childIndirect = e.getValue().isIndirect();
      IsCycle childCycles = e.getValue().isCycle();
      if (childCycles == IsCycle.TRUE) {
        cycles.add(printModule(childKey, key, IsExpanded.FALSE, IsIndirect.FALSE));
      } else if (childIndirect == IsIndirect.TRUE) {
        indirectDeps.add(printModule(childKey, key, childExpanded, IsIndirect.TRUE));
      } else {
        deps.add(printModule(childKey, key, childExpanded, IsIndirect.FALSE));
      }
    }
    json.add("dependencies", deps);
    json.add("indirectDependencies", indirectDeps);
    json.add("cycles", cycles);

    if (options.extensionInfo == ExtensionShow.HIDDEN) {
      return json;
    }
    ImmutableSortedSet<ModuleExtensionId> extensionsUsed =
        extensionRepoImports.keySet().stream()
            .filter(e -> extensionRepoImports.get(e).inverse().containsKey(key))
            .collect(toImmutableSortedSet(ModuleExtensionId.LEXICOGRAPHIC_COMPARATOR));
    JsonArray extensionUsages = new JsonArray();
    for (ModuleExtensionId extensionId : extensionsUsed) {
      boolean unexpandedExtension = !seenExtensions.add(extensionId);
      extensionUsages.add(printExtension(key, extensionId, unexpandedExtension));
    }
    json.add("extensionUsages", extensionUsages);

    return json;
  }
}
