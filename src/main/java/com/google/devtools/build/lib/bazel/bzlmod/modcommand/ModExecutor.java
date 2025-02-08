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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap;
import static com.google.common.collect.ImmutableSortedSet.toImmutableSortedSet;
import static java.util.Comparator.reverseOrder;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodRepoRuleValue;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey;
import com.google.devtools.build.lib.bazel.bzlmod.Tag;
import com.google.devtools.build.lib.bazel.bzlmod.Version;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode.IsExpanded;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode.IsIndirect;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode.NodeMetadata;
import com.google.devtools.build.lib.packages.LabelPrinter;
import com.google.devtools.build.lib.packages.RawAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.query2.query.output.BuildOutputFormatter.AttributeReader;
import com.google.devtools.build.lib.query2.query.output.BuildOutputFormatter.TargetOutputter;
import com.google.devtools.build.lib.query2.query.output.PossibleAttributeValues;
import com.google.devtools.build.lib.util.MaybeCompleteSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.starlark.java.eval.Starlark;

/**
 * Executes inspection queries for {@link com.google.devtools.build.lib.bazel.commands.ModCommand}
 * and prints the resulted output to the reporter's output stream using the different defined {@link
 * OutputFormatters}.
 */
public class ModExecutor {

  private final ImmutableMap<ModuleKey, AugmentedModule> depGraph;
  private final ImmutableTable<ModuleExtensionId, ModuleKey, ModuleExtensionUsage> extensionUsages;
  private final ImmutableSetMultimap<ModuleExtensionId, String> extensionRepos;
  private final Optional<MaybeCompleteSet<ModuleExtensionId>> extensionFilter;
  private final ModOptions options;
  private final PrintWriter printer;
  private ImmutableMap<ModuleExtensionId, ImmutableSetMultimap<String, ModuleKey>>
      extensionRepoImports;

  public ModExecutor(
      ImmutableMap<ModuleKey, AugmentedModule> depGraph, ModOptions options, Writer writer) {
    this(
        depGraph,
        ImmutableTable.of(),
        ImmutableSetMultimap.of(),
        Optional.of(MaybeCompleteSet.completeSet()),
        options,
        writer);
  }

  public ModExecutor(
      ImmutableMap<ModuleKey, AugmentedModule> depGraph,
      ImmutableTable<ModuleExtensionId, ModuleKey, ModuleExtensionUsage> extensionUsages,
      ImmutableSetMultimap<ModuleExtensionId, String> extensionRepos,
      Optional<MaybeCompleteSet<ModuleExtensionId>> extensionFilter,
      ModOptions options,
      Writer writer) {
    this.depGraph = depGraph;
    this.extensionUsages = extensionUsages;
    this.extensionRepos = extensionRepos;
    this.extensionFilter = extensionFilter;
    this.options = options;
    this.printer = new PrintWriter(writer);
    // Easier lookup table for repo imports by module.
    // It is updated after pruneByDepthAndLink to filter out pruned modules.
    this.extensionRepoImports = computeRepoImportsTable(depGraph.keySet());
  }

  public void graph(ImmutableSet<ModuleKey> from) {
    ImmutableMap<ModuleKey, ResultNode> result =
        expandAndPrune(from, computeExtensionFilterTargets(), false);
    OutputFormatters.getFormatter(options.outputFormat)
        .output(result, depGraph, extensionRepos, extensionRepoImports, printer, options);
  }

  public void path(ImmutableSet<ModuleKey> from, ImmutableSet<ModuleKey> to) {
    MaybeCompleteSet<ModuleKey> targets =
        MaybeCompleteSet.unionElements(computeExtensionFilterTargets(), to);
    ImmutableMap<ModuleKey, ResultNode> result = expandAndPrune(from, targets, true);
    OutputFormatters.getFormatter(options.outputFormat)
        .output(result, depGraph, extensionRepos, extensionRepoImports, printer, options);
  }

  public void allPaths(ImmutableSet<ModuleKey> from, ImmutableSet<ModuleKey> to) {
    MaybeCompleteSet<ModuleKey> targets =
        MaybeCompleteSet.unionElements(computeExtensionFilterTargets(), to);
    ImmutableMap<ModuleKey, ResultNode> result = expandAndPrune(from, targets, false);
    OutputFormatters.getFormatter(options.outputFormat)
        .output(result, depGraph, extensionRepos, extensionRepoImports, printer, options);
  }

  public void showRepo(ImmutableMap<String, BzlmodRepoRuleValue> targetRepoRuleValues) {
    RuleDisplayOutputter outputter = new RuleDisplayOutputter(printer);
    for (Entry<String, BzlmodRepoRuleValue> e : targetRepoRuleValues.entrySet()) {
      printer.printf("## %s:\n", e.getKey());
      outputter.outputRule(e.getValue().getRule());
    }
    printer.flush();
  }

  public void showExtension(
      ImmutableSet<ModuleExtensionId> extensions, ImmutableSet<ModuleKey> fromUsages)
      throws InvalidArgumentException {
    for (ModuleExtensionId extension : extensions) {
      displayExtension(extension, fromUsages);
    }
    printer.flush();
  }

  /**
   * The core function which produces the {@link ResultNode} graph for all the graph-generating
   * queries above. First, it expands the result graph starting from the {@code from} modules, up
   * until the {@code to} target modules if they are specified. If {@code singlePath} is set, it
   * will only contain a single path to one of the targets. <br>
   * Then it calls {@link ResultGraphPruner#pruneByDepth()} to prune nodes after the specified
   * {@code depth} (root is at depth 0). If the query specifies any {@code to} targets, even if they
   * are below the specified depth, they will still be included in the graph using some indirect
   * (dotted) edges. If {@code from} nodes other than the root are specified, they will be pinned
   * (connected directly under the root - using indirect edges if necessary).
   */
  @VisibleForTesting
  ImmutableMap<ModuleKey, ResultNode> expandAndPrune(
      ImmutableSet<ModuleKey> from, MaybeCompleteSet<ModuleKey> targets, boolean singlePath) {
    final MaybeCompleteSet<ModuleKey> coloredPaths = colorReversePathsToRoot(targets);
    ImmutableMap.Builder<ModuleKey, ResultNode> resultBuilder = new ImmutableMap.Builder<>();
    ResultNode.Builder rootBuilder = ResultNode.builder();

    ImmutableSet<ModuleKey> rootDirectChildren =
        depGraph.get(ModuleKey.ROOT).getAllDeps(options.includeUnused).keySet();
    ImmutableSet<ModuleKey> rootPinnedChildren =
        getPinnedChildrenOfRootInTheResultGraph(rootDirectChildren, from).stream()
            .filter(coloredPaths::contains)
            .filter(this::filterBuiltin)
            .collect(toImmutableSortedSet(ModuleKey.LEXICOGRAPHIC_COMPARATOR));
    rootPinnedChildren.forEach(
        moduleKey ->
            rootBuilder.addChild(
                moduleKey,
                IsExpanded.TRUE,
                rootDirectChildren.contains(moduleKey) ? IsIndirect.FALSE : IsIndirect.TRUE));
    resultBuilder.put(ModuleKey.ROOT, rootBuilder.build());

    Set<ModuleKey> seen = new HashSet<>(rootPinnedChildren);
    Deque<ModuleKey> toVisit = new ArrayDeque<>(rootPinnedChildren);
    seen.add(ModuleKey.ROOT);

    while (!toVisit.isEmpty()) {
      ModuleKey key = toVisit.pop();
      AugmentedModule module = depGraph.get(key);
      ResultNode.Builder nodeBuilder = ResultNode.builder();
      nodeBuilder.setTarget(!targets.isComplete() && targets.contains(key));

      ImmutableSortedSet<ModuleKey> moduleDeps = module.getAllDeps(options.includeUnused).keySet();
      for (ModuleKey childKey : moduleDeps) {
        if (!coloredPaths.contains(childKey)) {
          continue;
        }
        if (isBuiltin(childKey) && !options.includeBuiltin) {
          continue;
        }
        if (seen.contains(childKey)) {
          // Single paths should not contain cycles or unexpanded (duplicate) children
          // TODO(andreisolo): Move the single path extraction to DFS otherwise it can produce a
          //  wrong answer in cycle edge-case A -> B -> C -> B with target D will not find ABD
          //                                        \__ D
          if (!singlePath) {
            nodeBuilder.addChild(childKey, IsExpanded.FALSE, IsIndirect.FALSE);
          }
          continue;
        }
        nodeBuilder.addChild(childKey, IsExpanded.TRUE, IsIndirect.FALSE);
        seen.add(childKey);
        toVisit.add(childKey);
        if (singlePath) {
          break;
        }
      }

      resultBuilder.put(key, nodeBuilder.build());
    }
    return new ResultGraphPruner(targets, resultBuilder.buildOrThrow()).pruneByDepth();
  }

  private class ResultGraphPruner {

    private final Map<ModuleKey, ResultNode> oldResult;
    private final Map<ModuleKey, ResultNode.Builder> resultBuilder;
    private final Set<ModuleKey> parentStack;
    private final MaybeCompleteSet<ModuleKey> targets;

    /**
     * Constructs a ResultGraphPruner to prune the result graph after the specified depth.
     *
     * @param targets If not complete, it means that the result graph contains paths to some
     *     specific targets. This will cause some branches to contain, after the specified depths,
     *     some targets or target parents. As any other nodes omitted, transitive edges (embedding
     *     multiple edges) will be stored as <i>indirect</i>.
     * @param oldResult The unpruned result graph.
     */
    ResultGraphPruner(MaybeCompleteSet<ModuleKey> targets, Map<ModuleKey, ResultNode> oldResult) {
      this.oldResult = oldResult;
      this.resultBuilder = new HashMap<>();
      this.parentStack = new HashSet<>();
      this.targets = targets;
    }

    /**
     * Prunes the result tree after the specified depth using DFS (because some nodes may still
     * appear after the max depth).
     */
    private ImmutableMap<ModuleKey, ResultNode> pruneByDepth() {
      ResultNode.Builder rootBuilder = ResultNode.builder();
      resultBuilder.put(ModuleKey.ROOT, rootBuilder);

      parentStack.add(ModuleKey.ROOT);

      for (Entry<ModuleKey, NodeMetadata> e :
          oldResult.get(ModuleKey.ROOT).getChildrenSortedByKey()) {
        rootBuilder.addChild(e.getKey(), IsExpanded.TRUE, e.getValue().isIndirect());
        visitVisible(e.getKey(), 1, ModuleKey.ROOT, IsExpanded.TRUE);
      }

      // Build everything at the end to allow children to add themselves to their parent's
      // adjacency list.
      ImmutableMap<ModuleKey, ResultNode> result =
          resultBuilder.entrySet().stream()
              .collect(
                  toImmutableSortedMap(
                      ModuleKey.LEXICOGRAPHIC_COMPARATOR,
                      Entry::getKey,
                      e -> e.getValue().build()));
      // Filter imports for nodes that were pruned during this process.
      extensionRepoImports = computeRepoImportsTable(result.keySet());
      return result;
    }

    // Handles graph traversal within the specified depth.
    private void visitVisible(
        ModuleKey moduleKey, int depth, ModuleKey parentKey, IsExpanded expanded) {
      parentStack.add(moduleKey);
      ResultNode oldNode = oldResult.get(moduleKey);
      ResultNode.Builder nodeBuilder =
          resultBuilder.computeIfAbsent(moduleKey, k -> ResultNode.builder());

      nodeBuilder.setTarget(oldNode.isTarget());
      if (depth > 1) {
        resultBuilder.get(parentKey).addChild(moduleKey, expanded, IsIndirect.FALSE);
      }

      if (expanded == IsExpanded.FALSE) {
        parentStack.remove(moduleKey);
        return;
      }
      for (Entry<ModuleKey, NodeMetadata> e : oldNode.getChildrenSortedByKey()) {
        ModuleKey childKey = e.getKey();
        IsExpanded childExpanded = e.getValue().isExpanded();
        if (notCycle(childKey)) {
          if (depth < options.depth) {
            visitVisible(childKey, depth + 1, moduleKey, childExpanded);
          } else if (!targets.isComplete()) {
            visitDetached(childKey, moduleKey, moduleKey, childExpanded);
          }
        } else if (options.cycles) {
          nodeBuilder.addCycle(childKey);
        }
      }
      parentStack.remove(moduleKey);
    }

    // Detached mode is only present in withTargets and handles adding targets and target parents
    // living below the specified depth to the graph.
    private void visitDetached(
        ModuleKey moduleKey,
        ModuleKey parentKey,
        ModuleKey lastVisibleParentKey,
        IsExpanded expanded) {
      parentStack.add(moduleKey);
      ResultNode oldNode = oldResult.get(moduleKey);
      ResultNode.Builder nodeBuilder = ResultNode.builder();
      nodeBuilder.setTarget(oldNode.isTarget());

      if (oldNode.isTarget() || isTargetParent(oldNode)) {
        ResultNode.Builder parentBuilder = resultBuilder.get(lastVisibleParentKey);
        IsIndirect childIndirect =
            lastVisibleParentKey.equals(parentKey) ? IsIndirect.FALSE : IsIndirect.TRUE;
        parentBuilder.addChild(moduleKey, expanded, childIndirect);
        resultBuilder.put(moduleKey, nodeBuilder);
        lastVisibleParentKey = moduleKey;
      }

      if (expanded == IsExpanded.FALSE) {
        parentStack.remove(moduleKey);
        return;
      }
      for (Entry<ModuleKey, NodeMetadata> e : oldNode.getChildrenSortedByKey()) {
        ModuleKey childKey = e.getKey();
        IsExpanded childExpanded = e.getValue().isExpanded();
        if (notCycle(childKey)) {
          visitDetached(childKey, moduleKey, lastVisibleParentKey, childExpanded);
        } else if (options.cycles) {
          nodeBuilder.addCycle(childKey);
        }
      }
      parentStack.remove(moduleKey);
    }

    private boolean notCycle(ModuleKey key) {
      return !parentStack.contains(key);
    }

    private boolean isTargetParent(ResultNode node) {
      return node.getChildren().keys().stream()
          .filter(Predicate.not(parentStack::contains))
          .anyMatch(targets::contains);
    }
  }

  /**
   * Return a sorted list of modules that will be the direct children of the root in the result
   * graph (original root's direct dependencies along with the specified targets).
   */
  private ImmutableSortedSet<ModuleKey> getPinnedChildrenOfRootInTheResultGraph(
      ImmutableSet<ModuleKey> rootDirectDeps, ImmutableSet<ModuleKey> fromTargets) {
    Set<ModuleKey> targetKeys = new HashSet<>(fromTargets);
    if (fromTargets.contains(ModuleKey.ROOT)) {
      targetKeys.remove(ModuleKey.ROOT);
      targetKeys.addAll(rootDirectDeps);
    }
    return ImmutableSortedSet.copyOf(ModuleKey.LEXICOGRAPHIC_COMPARATOR, targetKeys);
  }

  private static boolean intersect(
      MaybeCompleteSet<ModuleExtensionId> a, Set<ModuleExtensionId> b) {
    if (a.isComplete()) {
      return !b.isEmpty();
    }
    return !Collections.disjoint(a.getElementsIfNotComplete(), b);
  }

  /**
   * If the extensionFilter option is set, computes the set of target modules that use the specified
   * extension(s) and adds them to the list of specified targets if the query is a path(s) query.
   */
  private MaybeCompleteSet<ModuleKey> computeExtensionFilterTargets() {
    if (extensionFilter.isEmpty()) {
      // If no --extension_filter is set, don't do anything here.
      return MaybeCompleteSet.completeSet();
    }
    return MaybeCompleteSet.copyOf(
        depGraph.keySet().stream()
            .filter(this::filterUnused)
            .filter(this::filterBuiltin)
            .filter(k -> intersect(extensionFilter.get(), extensionUsages.column(k).keySet()))
            .collect(toImmutableSet()));
  }

  /**
   * Color all reverse paths from the target modules to the root so only modules which are part of
   * these paths will be included in the output graph during the breadth-first traversal.
   */
  private MaybeCompleteSet<ModuleKey> colorReversePathsToRoot(MaybeCompleteSet<ModuleKey> to) {
    if (to.isComplete()) {
      return MaybeCompleteSet.completeSet();
    }

    Set<ModuleKey> seen = new HashSet<>(to.getElementsIfNotComplete());
    Deque<ModuleKey> toVisit = new ArrayDeque<>(to.getElementsIfNotComplete());

    while (!toVisit.isEmpty()) {
      ModuleKey key = toVisit.pop();
      AugmentedModule module = depGraph.get(key);
      Set<ModuleKey> parents = new HashSet<>(module.dependants());
      if (options.includeUnused) {
        parents.addAll(module.originalDependants());
      }
      for (ModuleKey parent : parents) {
        if (isBuiltin(parent) && !options.includeBuiltin) {
          continue;
        }
        if (seen.contains(parent)) {
          continue;
        }
        seen.add(parent);
        toVisit.add(parent);
      }
    }

    return MaybeCompleteSet.copyOf(seen);
  }

  /** Compute the multimap of repo imports to modules for each extension. */
  private ImmutableMap<ModuleExtensionId, ImmutableSetMultimap<String, ModuleKey>>
      computeRepoImportsTable(ImmutableSet<ModuleKey> presentModules) {
    ImmutableMap.Builder<ModuleExtensionId, ImmutableSetMultimap<String, ModuleKey>> resultBuilder =
        new ImmutableMap.Builder<>();
    for (ModuleExtensionId extension : extensionUsages.rowKeySet()) {
      if (extensionFilter.isPresent() && !extensionFilter.get().contains(extension)) {
        continue;
      }
      ImmutableSetMultimap.Builder<ModuleKey, String> modulesToImportsBuilder =
          new ImmutableSetMultimap.Builder<>();
      for (Entry<ModuleKey, ModuleExtensionUsage> usage :
          extensionUsages.rowMap().get(extension).entrySet()) {
        if (!presentModules.contains(usage.getKey())) {
          continue;
        }
        for (ModuleExtensionUsage.Proxy proxy : usage.getValue().getProxies()) {
          modulesToImportsBuilder.putAll(usage.getKey(), proxy.getImports().values());
        }
      }
      resultBuilder.put(extension, modulesToImportsBuilder.build().inverse());
    }
    return resultBuilder.buildOrThrow();
  }

  private boolean filterUnused(ModuleKey key) {
    AugmentedModule module = depGraph.get(key);
    return options.includeUnused || module.isUsed();
  }

  private boolean filterBuiltin(ModuleKey key) {
    return options.includeBuiltin || !isBuiltin(key);
  }

  /** Helper to display show_extension info. */
  private void displayExtension(ModuleExtensionId extension, ImmutableSet<ModuleKey> fromUsages)
      throws InvalidArgumentException {
    printer.printf("## %s:\n", extension.toString());
    printer.println();
    printer.println("Fetched repositories:");
    if (!extensionRepoImports.containsKey(extension)) {
      throw new InvalidArgumentException(
          String.format("No extension %s exists in the dependency graph", extension));
    }
    ImmutableSortedSet<String> usedRepos =
        ImmutableSortedSet.copyOf(extensionRepoImports.get(extension).keySet());
    ImmutableSortedSet<String> unusedRepos =
        ImmutableSortedSet.copyOf(Sets.difference(extensionRepos.get(extension), usedRepos));
    for (String repo : usedRepos) {
      printer.printf(
          "  - %s (imported by %s)\n",
          repo,
          extensionRepoImports.get(extension).get(repo).stream()
              .sorted(ModuleKey.LEXICOGRAPHIC_COMPARATOR)
              .map(ModuleKey::toString)
              .collect(joining(", ")));
    }
    for (String repo : unusedRepos) {
      printer.printf("  - %s\n", repo);
    }
    printer.println();
    if (fromUsages.isEmpty()) {
      fromUsages = ImmutableSet.copyOf(extensionUsages.rowMap().get(extension).keySet());
    }
    for (ModuleKey module : fromUsages) {
      if (!extensionUsages.contains(extension, module)) {
        continue;
      }
      ModuleExtensionUsage usage = extensionUsages.get(extension, module);
      // TODO: maybe consider printing each proxy separately? Might be relevant for included
      //  segments.
      printer.printf(
          "## Usage in %s from %s:%s\n",
          module,
          usage.getProxies().getFirst().getLocation().file(),
          usage.getProxies().getFirst().getLocation().line());
      for (Tag tag : usage.getTags()) {
        printer.printf(
            "%s.%s(%s)\n",
            extension.extensionName(),
            tag.getTagName(),
            tag.getAttributeValues().attributes().entrySet().stream()
                .map(e -> String.format("%s=%s", e.getKey(), Starlark.repr(e.getValue())))
                .collect(joining(", ")));
      }
      printer.printf("use_repo(\n");
      printer.printf("  %s,\n", extension.extensionName());
      for (ModuleExtensionUsage.Proxy proxy : usage.getProxies()) {
        for (Entry<String, String> repo : proxy.getImports().entrySet()) {
          printer.printf(
              "  %s,\n",
              repo.getKey().equals(repo.getValue())
                  ? String.format("\"%s\"", repo.getKey())
                  : String.format("%s=\"%s\"", repo.getKey(), repo.getValue()));
        }
      }
      printer.printf(")\n\n");
    }
  }

  private boolean isBuiltin(ModuleKey key) {
    return key.equals(new ModuleKey("bazel_tools", Version.EMPTY))
        || key.equals(new ModuleKey("local_config_platform", Version.EMPTY));
  }

  /** A node representing a module that forms the result graph. */
  @AutoValue
  public abstract static class ResultNode {

    /** Whether the module is one of the targets in a paths query. */
    abstract boolean isTarget();

    enum IsExpanded {
      FALSE,
      TRUE
    }

    enum IsIndirect {
      FALSE,
      TRUE
    }

    enum IsCycle {
      FALSE,
      TRUE
    }

    /**
     * Detailed edge type for the {@link ResultNode} graph.
     *
     * @param isExpanded Whether the node should be expanded from this edge (the same node can
     *     appear in multiple places in a flattened graph).
     * @param isIndirect Whether the edge is a direct edge or an indirect (transitive) one.
     * @param isCycle Whether the edge is cycling back inside the flattened graph.
     */
    public record NodeMetadata(IsExpanded isExpanded, IsIndirect isIndirect, IsCycle isCycle) {
      public NodeMetadata {
        requireNonNull(isExpanded, "isExpanded");
        requireNonNull(isIndirect, "isIndirect");
        requireNonNull(isCycle, "isCycle");
      }

      private static NodeMetadata create(
          IsExpanded isExpanded, IsIndirect isIndirect, IsCycle isCycle) {
        return new NodeMetadata(isExpanded, isIndirect, isCycle);
      }
    }

    /** List of children mapped to detailed edge types. */
    protected abstract ImmutableSetMultimap<ModuleKey, NodeMetadata> getChildren();

    public ImmutableSortedSet<Entry<ModuleKey, NodeMetadata>> getChildrenSortedByKey() {
      return ImmutableSortedSet.copyOf(
          Entry.comparingByKey(ModuleKey.LEXICOGRAPHIC_COMPARATOR), getChildren().entries());
    }

    public ImmutableSortedSet<Entry<ModuleKey, NodeMetadata>> getChildrenSortedByEdgeType() {
      return ImmutableSortedSet.copyOf(
          Comparator.<Entry<ModuleKey, NodeMetadata>, IsCycle>comparing(
                  e -> e.getValue().isCycle(), reverseOrder())
              .thenComparing(e -> e.getValue().isExpanded())
              .thenComparing(e -> e.getValue().isIndirect())
              .thenComparing(Entry::getKey, ModuleKey.LEXICOGRAPHIC_COMPARATOR),
          getChildren().entries());
    }

    static ResultNode.Builder builder() {
      return new AutoValue_ModExecutor_ResultNode.Builder().setTarget(false);
    }

    @AutoValue.Builder
    abstract static class Builder {

      abstract ResultNode.Builder setTarget(boolean value);

      abstract ImmutableSetMultimap.Builder<ModuleKey, NodeMetadata> childrenBuilder();

      @CanIgnoreReturnValue
      final Builder addChild(ModuleKey value, IsExpanded expanded, IsIndirect indirect) {
        childrenBuilder().put(value, NodeMetadata.create(expanded, indirect, IsCycle.FALSE));
        return this;
      }

      @CanIgnoreReturnValue
      final Builder addCycle(ModuleKey value) {
        childrenBuilder()
            .put(value, NodeMetadata.create(IsExpanded.FALSE, IsIndirect.FALSE, IsCycle.TRUE));
        return this;
      }

      abstract ResultNode build();
    }
  }

  /**
   * Uses Query's {@link TargetOutputter} to display the generating repo rule and other information.
   */
  static class RuleDisplayOutputter {
    private static final AttributeReader attrReader =
        (rule, attr) ->
            // Query's implementation copied
            PossibleAttributeValues.forRuleAndAttribute(
                rule, attr, /* mayTreatMultipleAsNone= */ true);
    private final TargetOutputter targetOutputter;
    private final PrintWriter printer;

    RuleDisplayOutputter(PrintWriter printer) {
      this.printer = printer;
      this.targetOutputter =
          new TargetOutputter(
              this.printer,
              (rule, attr) -> RawAttributeMapper.of(rule).isConfigurable(attr.getName()),
              "\n",
              LabelPrinter.legacy());
    }

    private void outputRule(Rule rule) {
      try {
        targetOutputter.outputRule(rule, attrReader, this.printer);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
