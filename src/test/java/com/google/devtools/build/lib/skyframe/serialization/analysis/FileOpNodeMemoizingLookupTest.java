// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis;

import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.skyframe.FileOpNodeOrFuture.EmptyFileOpNode.EMPTY_FILE_OP_NODE;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.skyframe.AbstractNestedFileOpNodes.NestedFileOpNodes;
import com.google.devtools.build.lib.skyframe.AbstractNestedFileOpNodes.NestedFileOpNodesWithSources;
import com.google.devtools.build.lib.skyframe.DirectoryListingKey;
import com.google.devtools.build.lib.skyframe.FileKey;
import com.google.devtools.build.lib.skyframe.FileOpNodeOrFuture.FileOpNode;
import com.google.devtools.build.lib.skyframe.FileOpNodeOrFuture.FileOpNodeOrEmpty;
import com.google.devtools.build.lib.skyframe.FileOpNodeOrFuture.FutureFileOpNode;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class FileOpNodeMemoizingLookupTest extends BuildIntegrationTestCase {
  // TODO: b/364831651 - consider adding test cases covering other scenarios, like symlinks.

  private static final int CONCURRENCY = 4;

  @Test
  public void fileOpNodes_areConsistent() throws Exception {
    // This test case contains a glob to exercise DirectoryListingKey.
    write("hello/x.txt", "x");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = glob(["*.txt"]),
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);

    buildTarget("//hello:target");

    InMemoryGraph graph = getSkyframeExecutor().getEvaluator().getInMemoryGraph();

    var fileOpDataMap = new FileOpNodeMemoizingLookup(graph);

    var actionLookups = new ArrayList<ActionLookupKey>();
    var actions = new ArrayList<ActionLookupData>();

    for (SkyKey key : graph.getDoneValues().keySet()) {
      if (key instanceof ActionLookupKey lookupKey) {
        actionLookups.add(lookupKey);
      }
      if (key instanceof ActionLookupData lookupData) {
        actions.add(lookupData);
      }
    }

    var futures = new ConcurrentLinkedQueue<ListenableFuture<Void>>();
    var pool = new ForkJoinPool(CONCURRENCY);
    var allAdded = new CountDownLatch(actionLookups.size() + actions.size());

    for (ActionLookupKey lookupKey : actionLookups) {
      pool.execute(
          () -> {
            futures.add(verifyFileOpNodeForActionLookupKey(graph, fileOpDataMap, lookupKey));
            allAdded.countDown();
          });
    }
    for (ActionLookupData lookupData : actions) {
      pool.execute(
          () -> {
            futures.add(verifyFileOpNodeForActionLookupData(graph, fileOpDataMap, lookupData));
            allAdded.countDown();
          });
    }

    allAdded.await();
    // Should not raise any exceptions.
    var unused = Futures.whenAllSucceed(futures).call(() -> null, directExecutor()).get();
  }

  private static ListenableFuture<Void> verifyFileOpNodeForActionLookupKey(
      InMemoryGraph graph, FileOpNodeMemoizingLookup fileOpDataMap, ActionLookupKey lookupKey) {
    // For action lookup values, verifies that the file dependencies are an exact match for the ones
    // in the transitive closure.
    Function<FileOpNodeOrEmpty, Void> verify =
        node -> {
          var nodes = new HashSet<FileOpNode>();
          var sources = new HashSet<FileKey>();
          flattenNodeOrEmpty(node, nodes, sources, new HashSet<>());
          assertWithMessage("for key=%s", lookupKey)
              .that(nodes)
              .isEqualTo(collectTransitiveFileOpNodes(graph, lookupKey));
          return null;
        };
    switch (fileOpDataMap.computeNode(lookupKey)) {
      case FileOpNodeOrEmpty nodeOrEmpty:
        var unusedNull = verify.apply(nodeOrEmpty);
        return immediateVoidFuture();
      case FutureFileOpNode future:
        return Futures.transform(future, verify, directExecutor());
    }
  }

  private static ListenableFuture<Void> verifyFileOpNodeForActionLookupData(
      InMemoryGraph graph, FileOpNodeMemoizingLookup fileOpDataMap, ActionLookupData lookupData) {
    // For actions, verifies that the union of the files and sources of the file op data of the
    // action's owner is a superset of the file dependencies of the action. There's a small
    // overapproximation here.
    Function<FileOpNodeOrEmpty, Void> verify =
        node -> {
          var nodes = new HashSet<FileOpNode>();
          var sources = new HashSet<FileKey>();
          flattenNodeOrEmpty(node, nodes, sources, new HashSet<>());
          ImmutableSet<FileOpNode> realFileDeps = collectTransitiveFileOpNodes(graph, lookupData);

          var assertBuilder = assertWithMessage("for key=%s", lookupData);
          assertBuilder.that(nodes).containsNoneIn(sources); // Sources are distinct from nodes.
          // All sources are contained in the real file deps.
          assertBuilder.that(realFileDeps).containsAtLeastElementsIn(sources);

          nodes.addAll(sources);
          // Sources may be an overapproximation by design. In this particular case, it happens to
          // be an exact match, but that could conceivably change with code changes.
          assertBuilder.that(nodes).containsAtLeastElementsIn(realFileDeps);
          return null;
        };
    // Note, that this looks up the incrementality data for the action by its ActionLookupKey.
    switch (fileOpDataMap.computeNode(lookupData.getActionLookupKey())) {
      case FileOpNodeOrEmpty nodeOrEmpty:
        var unusedNull = verify.apply(nodeOrEmpty);
        return immediateVoidFuture();
      case FutureFileOpNode future:
        return Futures.transform(future, verify, directExecutor());
    }
  }

  /**
   * Flattens the given node or empty node into the given sets of nodes and sources.
   *
   * <p>The given sets are modified in place.
   */
  private static void flattenNodeOrEmpty(
      FileOpNodeOrEmpty maybeNode,
      Set<FileOpNode> nodes,
      Set<FileKey> sources,
      Set<FileOpNode> visited) {
    switch (maybeNode) {
      case EMPTY_FILE_OP_NODE:
        return;
      case FileOpNode node:
        flattenNode(node, nodes, sources, visited);
        return;
    }
  }

  private static void flattenNode(
      FileOpNode node, Set<FileOpNode> nodes, Set<FileKey> sources, Set<FileOpNode> visited) {
    if (!visited.add(node)) {
      return;
    }
    switch (node) {
      case FileKey file:
        nodes.add(file);
        break;
      case DirectoryListingKey directory:
        nodes.add(directory);
        break;
      case NestedFileOpNodes nested:
        for (int i = 0; i < nested.analysisDependenciesCount(); i++) {
          flattenNode(nested.getAnalysisDependency(i), nodes, sources, visited);
        }
        break;
      case NestedFileOpNodesWithSources withSources:
        for (int i = 0; i < withSources.analysisDependenciesCount(); i++) {
          flattenNode(withSources.getAnalysisDependency(i), nodes, sources, visited);
        }
        for (int i = 0; i < withSources.sourceCount(); i++) {
          sources.add(withSources.getSource(i));
        }
        break;
    }
  }

  private static ImmutableSet<FileOpNode> collectTransitiveFileOpNodes(
      InMemoryGraph graph, SkyKey key) {
    var visited = new HashSet<SkyKey>();
    var nodes = new HashSet<FileOpNode>();
    collectTransitiveFileOpNodes(graph, key, visited, nodes);
    return ImmutableSet.copyOf(nodes);
  }

  private static void collectTransitiveFileOpNodes(
      InMemoryGraph graph, SkyKey key, Set<SkyKey> visited, Set<FileOpNode> nodes) {
    if (!visited.add(key)) {
      return;
    }
    if (key instanceof FileOpNode fileOp) {
      // The FileOpNodeMemoizingLookup doesn't recurse beyond FileKey or DirectoryListingKeys. The
      // inner details
      // of those entries are handled by FileDependencySerializer.
      nodes.add(fileOp);
      return;
    }
    for (SkyKey dep : graph.getIfPresent(key).getDirectDeps()) {
      collectTransitiveFileOpNodes(graph, dep, visited, nodes);
    }
  }
}
