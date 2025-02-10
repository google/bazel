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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey;
import com.google.devtools.build.lib.bazel.bzlmod.Version;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode.IsCycle;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode.IsExpanded;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode.IsIndirect;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode.NodeMetadata;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModOptions.Charset;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModOptions.ExtensionShow;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.OutputFormatters.OutputFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

/** Outputs graph-based results of {@link ModExecutor} in a human-readable text format. */
public class TextOutputFormatter extends OutputFormatter {

  private Deque<Boolean> isLastChildStack;
  private DrawCharset drawCharset;
  private Set<ModuleExtensionId> seenExtensions;
  private StringBuilder str;

  @Override
  public void output() {
    if (options.charset == Charset.ASCII) {
      drawCharset = DrawCharset.ASCII;
    } else {
      drawCharset = DrawCharset.UTF8;
    }
    isLastChildStack = new ArrayDeque<>();
    seenExtensions = new HashSet<>();
    str = new StringBuilder();
    printModule(ModuleKey.ROOT, null, IsExpanded.TRUE, IsIndirect.FALSE, IsCycle.FALSE, 0);
    this.printer.println(str);
  }

  // Prints the indents and the tree drawing characters.
  private void printTreeDrawing(IsIndirect indirect, int depth) {
    if (depth > 0) {
      int indents = isLastChildStack.size() - 1;
      Iterator<Boolean> value = isLastChildStack.descendingIterator();
      for (int i = 0; i < indents; i++) {
        boolean isLastChild = value.next();
        if (isLastChild) {
          str.append(drawCharset.emptyIndent);
        } else {
          str.append(drawCharset.prevChildIndent);
        }
      }
      if (indirect == IsIndirect.TRUE) {
        if (isLastChildStack.getFirst()) {
          str.append(drawCharset.lastIndirectChildIndent);
        } else {
          str.append(drawCharset.indirectChildIndent);
        }
      } else {
        if (isLastChildStack.getFirst()) {
          str.append(drawCharset.lastChildIndent);

        } else {
          str.append(drawCharset.childIndent);
        }
      }
    }
  }

  // Helper to print module extensions similarly to printModule.
  private void printExtension(
      ModuleKey key, ModuleExtensionId extensionId, boolean unexpanded, int depth) {
    printTreeDrawing(IsIndirect.FALSE, depth);
    str.append('$');
    str.append(extensionId);
    str.append(' ');
    if (unexpanded && options.extensionInfo == ExtensionShow.ALL) {
      str.append("... ");
    }
    str.append("\n");
    if (options.extensionInfo == ExtensionShow.USAGES) {
      return;
    }
    ImmutableSortedSet<String> repoImports =
        ImmutableSortedSet.copyOf(extensionRepoImports.get(extensionId).inverse().get(key));
    ImmutableSortedSet<String> unusedRepos = ImmutableSortedSet.of();
    if (!unexpanded && options.extensionInfo == ExtensionShow.ALL) {
      unusedRepos =
          ImmutableSortedSet.copyOf(
              Sets.difference(
                  extensionRepos.get(extensionId), extensionRepoImports.get(extensionId).keySet()));
    }
    int totalChildrenNum = repoImports.size() + unusedRepos.size();
    int currChild = 1;
    for (String usedRepo : repoImports) {
      isLastChildStack.push(currChild++ == totalChildrenNum);
      printExtensionRepo(usedRepo, IsIndirect.FALSE, depth + 1);
      isLastChildStack.pop();
    }
    if (unexpanded || options.extensionInfo == ExtensionShow.REPOS) {
      return;
    }
    for (String unusedPackage : unusedRepos) {
      isLastChildStack.push(currChild++ == totalChildrenNum);
      printExtensionRepo(unusedPackage, IsIndirect.TRUE, depth + 1);
      isLastChildStack.pop();
    }
  }

  // Prints an extension repo line.
  private void printExtensionRepo(String repoName, IsIndirect indirectLink, int depth) {
    printTreeDrawing(indirectLink, depth);
    str.append(repoName).append("\n");
  }

  // Depth-first traversal to print the actual output
  private void printModule(
      ModuleKey key,
      ModuleKey parent,
      IsExpanded expanded,
      IsIndirect indirect,
      IsCycle cycle,
      int depth) {
    printTreeDrawing(indirect, depth);

    ResultNode node = Objects.requireNonNull(result.get(key));
    if (key.equals(ModuleKey.ROOT)) {
      AugmentedModule rootModule = depGraph.get(ModuleKey.ROOT);
      Preconditions.checkNotNull(rootModule);
      str.append(
          String.format(
              "<root> (%s@%s)",
              rootModule.name(),
              rootModule.version().equals(Version.EMPTY) ? "_" : rootModule.version()));
    } else {
      str.append(key).append(" ");
    }

    int totalChildrenNum = node.getChildren().size();

    ImmutableSortedSet<ModuleExtensionId> extensionsUsed =
        extensionRepoImports.keySet().stream()
            .filter(e -> extensionRepoImports.get(e).inverse().containsKey(key))
            .collect(toImmutableSortedSet(ModuleExtensionId.LEXICOGRAPHIC_COMPARATOR));
    if (options.extensionInfo != ExtensionShow.HIDDEN) {
      totalChildrenNum += extensionsUsed.size();
    }

    if (cycle == IsCycle.TRUE) {
      str.append("(cycle) ");
    } else if (expanded == IsExpanded.FALSE) {
      str.append("(*) ");
    } else {
      if (totalChildrenNum != 0 && node.isTarget()) {
        str.append("# ");
      }
    }
    AugmentedModule module = Objects.requireNonNull(depGraph.get(key));
    if (!options.verbose && !module.isUsed()) {
      str.append("(unused) ");
    }
    // If the edge is indirect, the parent is not only unknown, but the node could have come
    // from multiple paths merged in the process, so we skip the resolution explanation.
    if (indirect == IsIndirect.FALSE && options.verbose && parent != null) {
      Explanation explanation = getExtraResolutionExplanation(key, parent);
      if (explanation != null) {
        str.append(explanation.toExplanationString(!module.isUsed()));
      }
    }

    str.append("\n");

    if (expanded == IsExpanded.FALSE) {
      return;
    }

    int currChild = 1;
    if (options.extensionInfo != ExtensionShow.HIDDEN) {
      for (ModuleExtensionId extensionId : extensionsUsed) {
        boolean unexpandedExtension = !seenExtensions.add(extensionId);
        isLastChildStack.push(currChild++ == totalChildrenNum);
        printExtension(key, extensionId, unexpandedExtension, depth + 1);
        isLastChildStack.pop();
      }
    }
    for (Entry<ModuleKey, NodeMetadata> e : node.getChildrenSortedByEdgeType()) {
      ModuleKey childKey = e.getKey();
      IsExpanded childExpanded = e.getValue().isExpanded();
      IsIndirect childIndirect = e.getValue().isIndirect();
      IsCycle childCycles = e.getValue().isCycle();
      isLastChildStack.push(currChild++ == totalChildrenNum);
      printModule(childKey, key, childExpanded, childIndirect, childCycles, depth + 1);
      isLastChildStack.pop();
    }
  }

  enum DrawCharset {
    ASCII("    ", "|   ", "|___", "|...", "|___", "|..."),
    UTF8("    ", "│   ", "├───", "├╌╌╌", "└───", "└╌╌╌");
    final String emptyIndent;
    final String prevChildIndent;
    final String childIndent;
    final String indirectChildIndent;
    final String lastChildIndent;
    final String lastIndirectChildIndent;

    DrawCharset(
        String emptyIndent,
        String prevChildIndent,
        String childIndent,
        String indirectChildIndent,
        String lastChildIndent,
        String lastIndirectChildIndent) {
      this.emptyIndent = emptyIndent;
      this.prevChildIndent = prevChildIndent;
      this.childIndent = childIndent;
      this.indirectChildIndent = indirectChildIndent;
      this.lastChildIndent = lastChildIndent;
      this.lastIndirectChildIndent = lastIndirectChildIndent;
    }
  }
}
