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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.skyframe.FileOpNodeOrFuture.EmptyFileOpNode.EMPTY_FILE_OP_NODE;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.Artifact.SourceArtifact;
import com.google.devtools.build.lib.analysis.configuredtargets.InputFileConfiguredTarget;
import com.google.devtools.build.lib.concurrent.QuiescingFuture;
import com.google.devtools.build.lib.skyframe.AbstractNestedFileOpNodes;
import com.google.devtools.build.lib.skyframe.FileKey;
import com.google.devtools.build.lib.skyframe.FileOpNodeOrFuture;
import com.google.devtools.build.lib.skyframe.FileOpNodeOrFuture.FileOpNode;
import com.google.devtools.build.lib.skyframe.FileOpNodeOrFuture.FileOpNodeOrEmpty;
import com.google.devtools.build.lib.skyframe.FileOpNodeOrFuture.FutureFileOpNode;
import com.google.devtools.build.lib.skyframe.NonRuleConfiguredTargetValue;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * Computes a mapping from {@link ActionLookupKey}s to {@link FileOpNodeOrFuture}s, representing the
 * complete set of file system operation dependencies required to evaluate each key.
 *
 * <p>This class tracks file dependencies for a particular build. It uses the file and source
 * partitioning in {@link AbstractNestedFileOpNodes} to provide a view of file dependencies for
 * configured targets and actions. For configured targets, only the analysis dependencies (BUILD,
 * .bzl files) are relevant. For actions, the source (.h, .cpp, .java) files must also be
 * considered.
 *
 * <p><b>Approximation for Efficiency:</b> To avoid the excessive overhead of storing precise file
 * dependencies per action, an over-approximation is used. This may lead to occasional spurious
 * cache misses but guarantees no false cache hits. The approximation includes all source
 * dependencies declared by the configured target that were visited during the build.
 *
 * <p>Not all actions of a configured target are executed, and include scanning may eliminate
 * dependencies, so the actual set of source files visited by a build may be a subset of the
 * declared ones. This will never skip an actual action file dependency of the build. While this is
 * correct, it's possible that different builds at the same version will have slightly different
 * representations of the sets of sources.
 *
 * <p><b>Why Approximation?</b> <br>
 * Storing the exact file dependencies for each action individually would be too expensive. It would
 * negate the benefits of the compact nested representation used for configured target dependencies.
 * The chosen approximation balances accuracy with performance.
 *
 * <p><b>Different Sources in Multiple Builds</b> <br>
 * Suppose there are multiple builds that share configured targets, but request different actions
 * from those configured targets. The configured target data is deterministic and shared, but the
 * invalidation information for source files could differ. When invalidating the configured target,
 * the source files are ignored, so even if a second build overwrites the configured target of the
 * first, invalidation of the configured target still works exactly the same way. For actions,
 * overwriting of the configured target doesn't affect correctness either because each action
 * directly references the invalidation data created by its respective build.
 */
final class FileOpNodeMemoizingLookup {
  private final InMemoryGraph graph;

  private final ValueOrFutureMap<SkyKey, FileOpNodeOrFuture, FileOpNodeOrEmpty, FutureFileOpNode>
      nodes =
          new ValueOrFutureMap<>(
              new ConcurrentHashMap<>(),
              FutureFileOpNode::new,
              this::populateFutureFileOpNode,
              FutureFileOpNode.class);

  FileOpNodeMemoizingLookup(InMemoryGraph graph) {
    this.graph = graph;
  }

  FileOpNodeOrFuture computeNode(ActionLookupKey key) {
    return nodes.getValueOrFuture(key);
  }

  private FileOpNodeOrFuture populateFutureFileOpNode(FutureFileOpNode ownedFuture) {
    var collector = new FileOpNodeCollector();

    accumulateTransitiveFileSystemOperations(ownedFuture.key(), collector);
    collector.notifyAllFuturesAdded();

    if (collector.isDone()) {
      try {
        return ownedFuture.completeWith(Futures.getDone(collector));
      } catch (ExecutionException e) {
        return ownedFuture.failWith(e);
      }
    }
    return ownedFuture.completeWith(collector);
  }

  private void accumulateTransitiveFileSystemOperations(SkyKey key, FileOpNodeCollector collector) {
    for (SkyKey dep : checkNotNull(graph.getIfPresent(key), key).getDirectDeps()) {
      switch (dep) {
        case FileOpNode immediateNode:
          collector.addNode(immediateNode);
          break;
        default:
          addNodeForKey(dep, collector);
          break;
      }
    }
  }

  private void addNodeForKey(SkyKey key, FileOpNodeCollector collector) {
    if (key instanceof ActionLookupKey actionLookupKey) {
      var nodeEntry = checkNotNull(graph.getIfPresent(key), key);
      // If the corresponding value is an InputFileConfiguredTarget, it indicates an execution time
      // file dependency.
      if ((checkNotNull(nodeEntry.getValue(), actionLookupKey)
              instanceof NonRuleConfiguredTargetValue nonRuleConfiguredTargetValue)
          && (nonRuleConfiguredTargetValue.getConfiguredTarget()
              instanceof InputFileConfiguredTarget inputFileConfiguredTarget)) {
        // The source artifact's file becomes an execution time dependency of actions owned by
        // configured targets with this InputFileConfiguredTarget as a dependency.
        SourceArtifact source = inputFileConfiguredTarget.getArtifact();
        var fileKey =
            FileKey.create(RootedPath.toRootedPath(source.getRoot().getRoot(), source.getPath()));
        if (graph.getIfPresent(fileKey) != null) {
          // If the file value is not present in the graph, it means that no action executed
          // actually depended on that file.
          //
          // TODO: b/364831651 - for greater determinism, consider performing additional Skyframe
          // evaluations for these unused dependencies.
          collector.addSource(fileKey);
        }
      }
    }

    // TODO: b/364831651 - This adds all traversed SkyKeys to `nodes`. Consider if certain types
    // should be excluded from memoization.
    switch (nodes.getValueOrFuture(key)) {
      case EMPTY_FILE_OP_NODE:
        break;
      case FileOpNode node:
        collector.addNode(node);
        break;
      case FutureFileOpNode future:
        collector.addFuture(future);
        break;
    }
  }

  private static final class FileOpNodeCollector extends QuiescingFuture<FileOpNodeOrEmpty>
      implements FutureCallback<FileOpNodeOrEmpty> {
    private final Set<FileOpNode> nodes = ConcurrentHashMap.newKeySet();
    private final HashSet<FileKey> sourceFiles = new HashSet<>();

    @Override
    protected FileOpNodeOrEmpty getValue() {
      return AbstractNestedFileOpNodes.from(nodes, sourceFiles);
    }

    private void addNode(FileOpNode node) {
      nodes.add(node);
    }

    private void addSource(FileKey sourceFile) {
      sourceFiles.add(sourceFile);
    }

    private void addFuture(FutureFileOpNode future) {
      increment();
      Futures.addCallback(future, (FutureCallback<FileOpNodeOrEmpty>) this, directExecutor());
    }

    private void notifyAllFuturesAdded() {
      decrement();
    }

    /**
     * Implementation of {@link FutureCallback<FileOpNode>}.
     *
     * @deprecated do not call, only used for callback processing
     */
    @Deprecated
    @Override
    public void onSuccess(FileOpNodeOrEmpty nodeOrEmpty) {
      switch (nodeOrEmpty) {
        case EMPTY_FILE_OP_NODE:
          break;
        case FileOpNode node:
          addNode(node);
          break;
      }
      decrement();
    }

    /**
     * Implementation of {@link FutureCallback<FileOpNode>}.
     *
     * @deprecated do not call, only used for callback processing
     */
    @Deprecated
    @Override
    public void onFailure(Throwable t) {
      notifyException(t);
    }
  }
}
