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
import com.google.devtools.build.lib.bazel.bzlmod.Version;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode.IsIndirect;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode.NodeMetadata;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModOptions.ExtensionShow;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.OutputFormatters.OutputFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

/**
 * Outputs graph-based results of {@link ModExecutor} in the Graphviz <i>dot</i> format which can be
 * further pipelined to create an image graph visualization.
 */
public class GraphvizOutputFormatter extends OutputFormatter {
  private StringBuilder str;

  @Override
  public void output() {
    str = new StringBuilder();
    str.append("digraph mygraph {\n")
        .append("  ")
        .append("node [ shape=box ]\n")
        .append("  ")
        .append("edge [ fontsize=8 ]\n");
    Set<ModuleKey> seen = new HashSet<>();
    Set<ModuleExtensionId> seenExtensions = new HashSet<>();
    Deque<ModuleKey> toVisit = new ArrayDeque<>();
    seen.add(ModuleKey.ROOT);
    toVisit.add(ModuleKey.ROOT);

    while (!toVisit.isEmpty()) {
      ModuleKey key = toVisit.pop();
      AugmentedModule module = Objects.requireNonNull(depGraph.get(key));
      ResultNode node = Objects.requireNonNull(result.get(key));
      String sourceId = toId(key);

      if (key.equals(ModuleKey.ROOT)) {
        String rootLabel = String.format("<root> (%s@%s)", module.name(), module.version());
        str.append(String.format("  \"<root>\" [ label=\"%s\" ]\n", rootLabel));
      } else if (node.isTarget() || !module.isUsed()) {
        String shapeString = node.isTarget() ? "diamond" : "box";
        String styleString = module.isUsed() ? "solid" : "dotted";
        str.append(
            String.format("  %s [ shape=%s style=%s ]\n", toId(key), shapeString, styleString));
      }

      if (options.extensionInfo != ExtensionShow.HIDDEN) {
        ImmutableSortedSet<ModuleExtensionId> extensionsUsed =
            extensionRepoImports.keySet().stream()
                .filter(e -> extensionRepoImports.get(e).inverse().containsKey(key))
                .collect(toImmutableSortedSet(ModuleExtensionId.LEXICOGRAPHIC_COMPARATOR));
        for (ModuleExtensionId extensionId : extensionsUsed) {
          if (options.extensionInfo == ExtensionShow.USAGES) {
            str.append(String.format("  %s -> \"%s\"\n", toId(key), toId(extensionId)));
            continue;
          }
          if (seenExtensions.add(extensionId)) {
            printExtension(extensionId);
          }
          ImmutableSortedSet<String> repoImports =
              ImmutableSortedSet.copyOf(extensionRepoImports.get(extensionId).inverse().get(key));
          for (String repo : repoImports) {
            str.append(String.format("  %s -> %s\n", toId(key), toId(extensionId, repo)));
          }
        }
      }
      for (Entry<ModuleKey, NodeMetadata> e : node.getChildrenSortedByKey()) {
        ModuleKey childKey = e.getKey();
        IsIndirect childIndirect = e.getValue().isIndirect();
        String childId = toId(childKey);
        if (childIndirect == IsIndirect.FALSE) {
          String reasonLabel = getReasonLabel(childKey, key);
          str.append(String.format("  %s -> %s [ %s ]\n", sourceId, childId, reasonLabel));
        } else {
          str.append(String.format("  %s -> %s [ style=dashed ]\n", sourceId, childId));
        }
        if (seen.add(childKey)) {
          toVisit.add(childKey);
        }
      }
    }
    str.append("}");
    printer.println(str);
    printer.flush();
  }

  private String toId(ModuleKey key) {
    if (key.equals(ModuleKey.ROOT)) {
      return "\"<root>\"";
    }
    return String.format(
        "\"%s@%s\"", key.name(), key.version().equals(Version.EMPTY) ? "_" : key.version());
  }

  private String toId(ModuleExtensionId id) {
    return id.toString();
  }

  private String toId(ModuleExtensionId id, String repo) {
    return String.format("\"%s%%%s\"", toId(id), repo);
  }

  private void printExtension(ModuleExtensionId id) {
    str.append(String.format("  subgraph \"cluster_%s\" {\n", toId(id)));
    str.append(String.format("    label=\"%s\"\n", toId(id)));
    if (options.extensionInfo == ExtensionShow.USAGES) {
      return;
    }
    ImmutableSortedSet<String> usedRepos =
        ImmutableSortedSet.copyOf(extensionRepoImports.get(id).keySet());
    for (String repo : usedRepos) {
      str.append(String.format("    %s [ label=\"%s\" ]\n", toId(id, repo), repo));
    }
    if (options.extensionInfo == ExtensionShow.REPOS) {
      return;
    }
    ImmutableSortedSet<String> unusedRepos =
        ImmutableSortedSet.copyOf(Sets.difference(extensionRepos.get(id), usedRepos));
    for (String repo : unusedRepos) {
      str.append(String.format("    %s [ label=\"%s\" style=dotted ]\n", toId(id, repo), repo));
    }
    str.append("  }\n");
  }

  private String getReasonLabel(ModuleKey key, ModuleKey parent) {
    if (!options.verbose) {
      return "";
    }
    Explanation explanation = getExtraResolutionExplanation(key, parent);
    if (explanation == null) {
      return "";
    }
    String label = explanation.resolutionReason().getLabel();
    if (!label.isEmpty()) {
      return String.format("label=%s", label);
    }
    return "";
  }
}
