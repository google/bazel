// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.stream.Collectors.groupingByConcurrent;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileStateType;
import com.google.devtools.build.lib.actions.OutputChecker;
import com.google.devtools.build.lib.concurrent.ExecutorUtil;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.profiler.AutoProfiler;
import com.google.devtools.build.lib.profiler.AutoProfiler.ElapsedTimeReceiver;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.skyframe.SkyValueDirtinessChecker.DirtyResult;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue.ArchivedRepresentation;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.BatchStat;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatusWithDigest;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.XattrProvider;
import com.google.devtools.build.skyframe.Differencer;
import com.google.devtools.build.skyframe.Differencer.DiffWithDelta.Delta;
import com.google.devtools.build.skyframe.FunctionHermeticity;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.QueryableGraph.Reason;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.Version;
import com.google.devtools.build.skyframe.WalkableGraph;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * A helper class to find dirty values by accessing the filesystem directly (contrast with {@link
 * DiffAwareness}).
 */
public class FilesystemValueChecker {

  /**
   * Allows to override the {@link XattrProvider} when getting xattr (or digest) for output files.
   */
  public interface XattrProviderOverrider {
    XattrProvider getXattrProvider(SyscallCache syscallCache);

    XattrProviderOverrider NO_OVERRIDE = syscallCache -> syscallCache;
  }

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static final Predicate<SkyKey> ACTION_FILTER =
      SkyFunctionName.functionIs(SkyFunctions.ACTION_EXECUTION);

  @Nullable private final TimestampGranularityMonitor tsgm;
  private final SyscallCache syscallCache;
  private final XattrProviderOverrider xattrProviderOverrider;
  private final int numThreads;

  public FilesystemValueChecker(
      @Nullable TimestampGranularityMonitor tsgm,
      SyscallCache syscallCache,
      XattrProviderOverrider xattrProviderOverrider,
      int numThreads) {
    this.tsgm = tsgm;
    this.syscallCache = syscallCache;
    this.xattrProviderOverrider = xattrProviderOverrider;
    this.numThreads = numThreads;
  }

  /**
   * Returns a {@link Differencer.DiffWithDelta} containing keys from the give map that are dirty
   * according to the passed-in {@code dirtinessChecker}.
   */
  // TODO(bazel-team): Refactor these methods so that FilesystemValueChecker only operates on a
  // WalkableGraph.
  public ImmutableBatchDirtyResult getDirtyKeys(
      Map<SkyKey, SkyValue> valuesMap, SkyValueDirtinessChecker dirtinessChecker)
      throws InterruptedException {
    return getDirtyValues(
        new MapBackedValueFetcher(valuesMap),
        valuesMap.keySet(),
        dirtinessChecker,
        /* checkMissingValues= */ false,
        /* inMemoryGraph= */ null);
  }

  public ImmutableBatchDirtyResult getDirtyKeys(
      InMemoryGraph inMemoryGraph, SkyValueDirtinessChecker dirtinessChecker)
      throws InterruptedException {
    Map<SkyKey, SkyValue> valuesMap = inMemoryGraph.getValues();
    return getDirtyValues(
        new MapBackedValueFetcher(valuesMap),
        valuesMap.keySet(),
        dirtinessChecker,
        /* checkMissingValues= */ false,
        inMemoryGraph);
  }

  /**
   * Returns a {@link Differencer.DiffWithDelta} containing keys that are dirty according to the
   * passed-in {@code dirtinessChecker}.
   */
  public Differencer.DiffWithDelta getNewAndOldValues(
      WalkableGraph walkableGraph,
      Collection<SkyKey> keys,
      SkyValueDirtinessChecker dirtinessChecker)
      throws InterruptedException {
    return getDirtyValues(
        new WalkableGraphBackedValueFetcher(walkableGraph),
        keys,
        dirtinessChecker,
        /* checkMissingValues= */ true,
        /* inMemoryGraph= */ null);
  }

  private interface ValueFetcher {
    @Nullable
    SkyValue get(SkyKey key) throws InterruptedException;
  }

  private static class WalkableGraphBackedValueFetcher implements ValueFetcher {
    private final WalkableGraph walkableGraph;

    private WalkableGraphBackedValueFetcher(WalkableGraph walkableGraph) {
      this.walkableGraph = walkableGraph;
    }

    @Override
    @Nullable
    public SkyValue get(SkyKey key) throws InterruptedException {
      return walkableGraph.getValue(key);
    }
  }

  private static class MapBackedValueFetcher implements ValueFetcher {
    private final Map<SkyKey, SkyValue> valuesMap;

    private MapBackedValueFetcher(Map<SkyKey, SkyValue> valuesMap) {
      this.valuesMap = valuesMap;
    }

    @Override
    @Nullable
    public SkyValue get(SkyKey key) {
      return valuesMap.get(key);
    }
  }

  /** Callback for modified output files for logging/metrics. */
  @FunctionalInterface
  @ThreadSafe
  interface ModifiedOutputsReceiver {

    /**
     * Called on every modified artifact detected by {@link #getDirtyActionValues}.
     *
     * @param maybeModifiedTime Best effort modified time, -1 when not available/missing.
     * @param artifact Modified output artifact.
     */
    void reportModifiedOutputFile(long maybeModifiedTime, Artifact artifact);
  }

  /**
   * Return a collection of action values which have output files that are not in-sync with the
   * on-disk file value (were modified externally).
   */
  Collection<SkyKey> getDirtyActionValues(
      Map<SkyKey, SkyValue> valuesMap,
      @Nullable final BatchStat batchStatter,
      ModifiedFileSet modifiedOutputFiles,
      OutputChecker outputChecker,
      ModifiedOutputsReceiver modifiedOutputsReceiver)
      throws InterruptedException {
    if (modifiedOutputFiles == ModifiedFileSet.NOTHING_MODIFIED) {
      logger.atInfo().log("Not checking for dirty actions since nothing was modified");
      return ImmutableList.of();
    }

    logger.atInfo().log("Accumulating dirty actions and batching them into shards");
    int numShards = Runtime.getRuntime().availableProcessors() * 4;
    Collection<List<Map.Entry<SkyKey, ActionExecutionValue>>> actionKeyShards;
    try (SilentCloseable c =
        Profiler.instance().profile("getDirtyActionValues/filterAndBatchActions")) {
      actionKeyShards = batchActionKeysIntoShards(numShards, valuesMap);
    }

    ExecutorService executor =
        Executors.newFixedThreadPool(
            numShards,
            new ThreadFactoryBuilder()
                .setNameFormat("FileSystem Output File Invalidator %d")
                .build());

    Collection<SkyKey> dirtyKeys = Sets.newConcurrentHashSet();

    final ImmutableSet<PathFragment> knownModifiedOutputFiles =
        modifiedOutputFiles.treatEverythingAsModified()
            ? null
            : modifiedOutputFiles.modifiedSourceFiles();

    // Initialized lazily through a supplier because it is only used to check modified
    // TreeArtifacts, which are not frequently used in builds.
    Supplier<NavigableSet<PathFragment>> sortedKnownModifiedOutputFiles =
        Suppliers.memoize(
            new Supplier<NavigableSet<PathFragment>>() {
              @Nullable
              @Override
              public NavigableSet<PathFragment> get() {
                if (knownModifiedOutputFiles == null) {
                  return null;
                } else {
                  return ImmutableSortedSet.copyOf(knownModifiedOutputFiles);
                }
              }
            });

    boolean interrupted;
    try (SilentCloseable c = Profiler.instance().profile("getDirtyActionValues/statFiles")) {
      for (List<Map.Entry<SkyKey, ActionExecutionValue>> shard : actionKeyShards) {
        Runnable job =
            (batchStatter == null)
                ? outputStatJob(
                    dirtyKeys,
                    shard,
                    knownModifiedOutputFiles,
                    sortedKnownModifiedOutputFiles,
                    outputChecker,
                    modifiedOutputsReceiver)
                : batchStatJob(
                    dirtyKeys,
                    shard,
                    batchStatter,
                    knownModifiedOutputFiles,
                    sortedKnownModifiedOutputFiles,
                    outputChecker,
                    modifiedOutputsReceiver);
        executor.execute(job);
      }

      interrupted = ExecutorUtil.interruptibleShutdown(executor);
    }
    if (dirtyKeys.isEmpty()) {
      logger.atInfo().log("Completed output file stat checks, no modified outputs found");
    } else {
      logger.atInfo().log(
          "Completed output file stat checks, %d actions' outputs changed, first few: %s",
          dirtyKeys.size(), Iterables.limit(dirtyKeys, 10));
    }
    if (interrupted) {
      throw new InterruptedException();
    }
    return dirtyKeys;
  }

  @SuppressWarnings("unchecked")
  private Collection<List<Map.Entry<SkyKey, ActionExecutionValue>>> batchActionKeysIntoShards(
      int numShards, Map<SkyKey, SkyValue> valuesMap) {
    return (Collection)
        valuesMap.entrySet().stream()
            .parallel()
            .filter(e -> ACTION_FILTER.apply(e.getKey()))
            .map(e -> (Map.Entry<?, ?>) e)
            .collect(groupingByConcurrent(k -> ThreadLocalRandom.current().nextInt(numShards)))
            .values();
  }

  private Runnable batchStatJob(
      Collection<SkyKey> dirtyKeys,
      List<Map.Entry<SkyKey, ActionExecutionValue>> shard,
      BatchStat batchStatter,
      ImmutableSet<PathFragment> knownModifiedOutputFiles,
      Supplier<NavigableSet<PathFragment>> sortedKnownModifiedOutputFiles,
      OutputChecker outputChecker,
      ModifiedOutputsReceiver modifiedOutputsReceiver) {
    return () -> {
      Map<Artifact, Map.Entry<SkyKey, ActionExecutionValue>> fileToKeyAndValue = new HashMap<>();
      Map<Artifact, Map.Entry<SkyKey, ActionExecutionValue>> treeArtifactsToKeyAndValue =
          new HashMap<>();
      for (Map.Entry<SkyKey, ActionExecutionValue> keyAndValue : shard) {
        ActionExecutionValue actionValue = keyAndValue.getValue();
        if (actionValue == null) {
          dirtyKeys.add(keyAndValue.getKey());
        } else {
          for (Artifact artifact : actionValue.getAllFileValues().keySet()) {
            if (!artifact.isRunfilesTree() && shouldCheckFile(knownModifiedOutputFiles, artifact)) {
              fileToKeyAndValue.put(artifact, keyAndValue);
            }
          }

          for (Map.Entry<Artifact, TreeArtifactValue> entry :
              actionValue.getAllTreeArtifactValues().entrySet()) {
            Artifact treeArtifact = entry.getKey();
            TreeArtifactValue tree = entry.getValue();
            for (TreeFileArtifact child : tree.getChildren()) {
              if (shouldCheckFile(knownModifiedOutputFiles, child)) {
                fileToKeyAndValue.put(child, keyAndValue);
              }
            }
            tree.getArchivedRepresentation()
                .map(ArchivedRepresentation::archivedTreeFileArtifact)
                .filter(
                    archivedTreeArtifact ->
                        shouldCheckFile(knownModifiedOutputFiles, archivedTreeArtifact))
                .ifPresent(
                    archivedTreeArtifact ->
                        fileToKeyAndValue.put(archivedTreeArtifact, keyAndValue));
            if (shouldCheckTreeArtifact(sortedKnownModifiedOutputFiles.get(), treeArtifact)) {
              treeArtifactsToKeyAndValue.put(treeArtifact, keyAndValue);
            }
          }
        }
      }

      List<Artifact> artifacts = ImmutableList.copyOf(fileToKeyAndValue.keySet());
      List<FileStatusWithDigest> stats;
      try {
        stats = batchStatter.batchStat(Artifact.asPathFragments(artifacts));
      } catch (IOException e) {
        logger.atWarning().withCause(e).log(
            "Unable to process batch stat, falling back to individual stats");
        outputStatJob(
                dirtyKeys,
                shard,
                knownModifiedOutputFiles,
                sortedKnownModifiedOutputFiles,
                outputChecker,
                modifiedOutputsReceiver)
            .run();
        return;
      } catch (InterruptedException e) {
        logger.atInfo().log("Interrupted doing batch stat");
        Thread.currentThread().interrupt();
        // We handle interrupt in the main thread.
        return;
      }

      Preconditions.checkState(
          artifacts.size() == stats.size(),
          "artifacts.size() == %s stats.size() == %s",
          artifacts.size(),
          stats.size());
      for (int i = 0; i < artifacts.size(); i++) {
        Artifact artifact = artifacts.get(i);
        FileStatusWithDigest stat = stats.get(i);
        Map.Entry<SkyKey, ActionExecutionValue> keyAndValue = fileToKeyAndValue.get(artifact);
        ActionExecutionValue actionValue = keyAndValue.getValue();
        SkyKey key = keyAndValue.getKey();
        FileArtifactValue lastKnownData = actionValue.getExistingFileArtifactValue(artifact);
        try {
          FileArtifactValue newData =
              ActionOutputMetadataStore.fileArtifactValueFromArtifact(
                  artifact, stat, xattrProviderOverrider.getXattrProvider(syscallCache), tsgm);
          if (newData.couldBeModifiedSince(lastKnownData)) {
            modifiedOutputsReceiver.reportModifiedOutputFile(
                stat != null ? stat.getLastChangeTime() : -1, artifact);
            dirtyKeys.add(key);
          }
        } catch (IOException e) {
          logger.atWarning().withCause(e).log(
              "Error for %s (%s %s %s)", artifact, stat, keyAndValue, lastKnownData);
          // This is an unexpected failure getting a digest or symlink target.
          modifiedOutputsReceiver.reportModifiedOutputFile(-1, artifact);
          dirtyKeys.add(key);
        }
      }

      // Unfortunately, there exists no facility to batch list directories.
      // We must use direct filesystem calls.
      for (Map.Entry<Artifact, Map.Entry<SkyKey, ActionExecutionValue>> entry :
          treeArtifactsToKeyAndValue.entrySet()) {
        Artifact artifact = entry.getKey();
        try {
          if (treeArtifactIsDirty(
              entry.getKey(), entry.getValue().getValue().getTreeArtifactValue(artifact))) {
            // Count the changed directory as one "file".
            // TODO(bazel-team): There are no tests for this codepath.
            modifiedOutputsReceiver.reportModifiedOutputFile(
                getBestEffortModifiedTime(artifact.getPath()), artifact);
            dirtyKeys.add(entry.getValue().getKey());
          }
        } catch (InterruptedException e) {
          logger.atInfo().log("Interrupted doing batch stat");
          Thread.currentThread().interrupt();
          // We handle interrupt in the main thread.
          return;
        }
      }
    };
  }

  private Runnable outputStatJob(
      Collection<SkyKey> dirtyKeys,
      List<Map.Entry<SkyKey, ActionExecutionValue>> shard,
      ImmutableSet<PathFragment> knownModifiedOutputFiles,
      Supplier<NavigableSet<PathFragment>> sortedKnownModifiedOutputFiles,
      OutputChecker outputChecker,
      ModifiedOutputsReceiver modifiedOutputsReceiver) {
    return new Runnable() {
      @Override
      public void run() {
        try {
          for (Map.Entry<SkyKey, ActionExecutionValue> keyAndValue : shard) {
            ActionExecutionValue value = keyAndValue.getValue();
            if (value == null
                || actionValueIsDirtyWithDirectSystemCalls(
                    value,
                    knownModifiedOutputFiles,
                    sortedKnownModifiedOutputFiles,
                    outputChecker,
                    modifiedOutputsReceiver)) {
              dirtyKeys.add(keyAndValue.getKey());
            }
          }
        } catch (InterruptedException e) {
          // This code is called from getDirtyActionValues() and is running under an Executor. This
          // means that getDirtyActionValues() will take care of house-keeping in case of an
          // interrupt; all that matters is that we exit as quickly as possible.
          logger.atInfo().log("Interrupted doing non-batch stat");
          Thread.currentThread().interrupt();
        }
      }
    };
  }

  private boolean treeArtifactIsDirty(Artifact artifact, TreeArtifactValue value)
      throws InterruptedException {
    Path path = artifact.getPath();
    if (path.isSymbolicLink()) {
      return true; // TreeArtifacts may not be symbolic links.
    }

    // This could be improved by short-circuiting as soon as we see a child that is not present in
    // the TreeArtifactValue, but it doesn't seem to be a major source of overhead.
    // visitTree() is called from multiple threads in parallel so this need to be a concurrent set
    Set<PathFragment> currentLocalChildren = Sets.newConcurrentHashSet();
    try {
      TreeArtifactValue.visitTree(
          path,
          (child, type, traversedSymlink) -> {
            if (type != Dirent.Type.DIRECTORY) {
              currentLocalChildren.add(child);
            }
          });
    } catch (IOException e) {
      return true;
    }

    if (currentLocalChildren.isEmpty() && value.isEntirelyRemote()) {
      return false;
    }

    var lastKnownLocalChildren =
        value.getChildValues().entrySet().stream()
            .filter(
                entry -> {
                  var metadata = entry.getValue();
                  // For remote metadata, a non-null contents proxy indicates that the file has
                  // been materialized in the local filesystem.
                  return !metadata.isRemote() || metadata.getContentsProxy() != null;
                })
            .map(entry -> entry.getKey().getParentRelativePath())
            .collect(toImmutableSet());

    return !currentLocalChildren.equals(lastKnownLocalChildren);
  }

  private boolean artifactIsDirtyWithDirectSystemCalls(
      ImmutableSet<PathFragment> knownModifiedOutputFiles,
      OutputChecker outputChecker,
      Map.Entry<? extends Artifact, FileArtifactValue> entry,
      ModifiedOutputsReceiver modifiedOutputsReceiver) {
    Artifact file = entry.getKey();
    FileArtifactValue lastKnownData = entry.getValue();
    if (file.isRunfilesTree() || !shouldCheckFile(knownModifiedOutputFiles, file)) {
      return false;
    }
    try {
      FileArtifactValue fileMetadata =
          ActionOutputMetadataStore.fileArtifactValueFromArtifact(
              file, null, xattrProviderOverrider.getXattrProvider(syscallCache), tsgm);
      boolean isTrustedRemoteValue =
          fileMetadata.getType() == FileStateType.NONEXISTENT
              && lastKnownData.isRemote()
              && outputChecker.shouldTrustArtifact(file, lastKnownData);
      if (!isTrustedRemoteValue && fileMetadata.couldBeModifiedSince(lastKnownData)) {
        modifiedOutputsReceiver.reportModifiedOutputFile(
            fileMetadata.getType() != FileStateType.NONEXISTENT
                ? file.getPath().getLastModifiedTime(Symlinks.FOLLOW)
                : -1,
            file);
        return true;
      }
      return false;
    } catch (IOException e) {
      // This is an unexpected failure getting a digest or symlink target.
      modifiedOutputsReceiver.reportModifiedOutputFile(/* maybeModifiedTime= */ -1, file);
      return true;
    }
  }

  private boolean actionValueIsDirtyWithDirectSystemCalls(
      ActionExecutionValue actionValue,
      ImmutableSet<PathFragment> knownModifiedOutputFiles,
      Supplier<NavigableSet<PathFragment>> sortedKnownModifiedOutputFiles,
      OutputChecker outputChecker,
      ModifiedOutputsReceiver modifiedOutputsReceiver)
      throws InterruptedException {
    boolean isDirty = false;
    for (Map.Entry<Artifact, FileArtifactValue> entry : actionValue.getAllFileValues().entrySet()) {
      if (artifactIsDirtyWithDirectSystemCalls(
          knownModifiedOutputFiles, outputChecker, entry, modifiedOutputsReceiver)) {
        isDirty = true;
      }
    }

    for (Map.Entry<Artifact, TreeArtifactValue> entry :
        actionValue.getAllTreeArtifactValues().entrySet()) {
      TreeArtifactValue tree = entry.getValue();

      for (Map.Entry<TreeFileArtifact, FileArtifactValue> childEntry :
          tree.getChildValues().entrySet()) {
        if (artifactIsDirtyWithDirectSystemCalls(
            knownModifiedOutputFiles, outputChecker, childEntry, modifiedOutputsReceiver)) {
          isDirty = true;
        }
      }
      isDirty =
          isDirty
              || tree.getArchivedRepresentation()
                  .map(
                      archivedRepresentation ->
                          artifactIsDirtyWithDirectSystemCalls(
                              knownModifiedOutputFiles,
                              outputChecker,
                              Maps.immutableEntry(
                                  archivedRepresentation.archivedTreeFileArtifact(),
                                  archivedRepresentation.archivedFileValue()),
                              modifiedOutputsReceiver))
                  .orElse(false);

      Artifact treeArtifact = entry.getKey();
      if (shouldCheckTreeArtifact(sortedKnownModifiedOutputFiles.get(), treeArtifact)
          && treeArtifactIsDirty(treeArtifact, entry.getValue())) {
        // Count the changed directory as one "file".
        modifiedOutputsReceiver.reportModifiedOutputFile(
            getBestEffortModifiedTime(treeArtifact.getPath()), treeArtifact);
        isDirty = true;
      }
    }

    return isDirty;
  }

  private static long getBestEffortModifiedTime(Path path) {
    try {
      return path.exists() ? path.getLastModifiedTime() : -1;
    } catch (IOException e) {
      logger.atWarning().atMostEvery(1, MINUTES).withCause(e).log(
          "Failed to get modified time for output at: %s", path);
      return -1;
    }
  }

  private static boolean shouldCheckFile(
      ImmutableSet<PathFragment> knownModifiedOutputFiles, Artifact artifact) {
    return knownModifiedOutputFiles == null
        || knownModifiedOutputFiles.contains(artifact.getExecPath());
  }

  private static boolean shouldCheckTreeArtifact(
      @Nullable NavigableSet<PathFragment> knownModifiedOutputFiles, Artifact treeArtifact) {
    // If null, everything needs to be checked.
    if (knownModifiedOutputFiles == null) {
      return true;
    }

    // Here we do the following to see whether a TreeArtifact is modified:
    // 1. Sort the set of modified file paths in lexicographical order using TreeSet.
    // 2. Get the first modified output file path that is greater than or equal to the exec path of
    //    the TreeArtifact to check.
    // 3. Check whether the returned file path contains the exec path of the TreeArtifact as a
    //    prefix path.
    PathFragment artifactExecPath = treeArtifact.getExecPath();
    PathFragment headPath = knownModifiedOutputFiles.ceiling(artifactExecPath);

    return headPath != null && headPath.startsWith(artifactExecPath);
  }

  private ImmutableBatchDirtyResult getDirtyValues(
      ValueFetcher fetcher,
      Collection<SkyKey> keys,
      SkyValueDirtinessChecker checker,
      boolean checkMissingValues,
      @Nullable InMemoryGraph inMemoryGraph)
      throws InterruptedException {
    ExecutorService executor =
        Executors.newFixedThreadPool(
            numThreads,
            new ThreadFactoryBuilder().setNameFormat("FileSystem Value Invalidator %d").build());

    final AtomicInteger numKeysChecked = new AtomicInteger(0);
    MutableBatchDirtyResult batchResult = new MutableBatchDirtyResult(numKeysChecked);
    ElapsedTimeReceiver elapsedTimeReceiver =
        elapsedTimeNanos -> {
          if (elapsedTimeNanos > 0) {
            logger.atInfo().log(
                "Spent %d nanoseconds checking %d filesystem nodes (%d scanned)",
                elapsedTimeNanos, numKeysChecked.get(), keys.size());
          }
        };
    try (AutoProfiler prof = AutoProfiler.create(elapsedTimeReceiver)) {
      for (final SkyKey key : keys) {
        if (!checker.applies(key)) {
          continue;
        }
        Preconditions.checkState(
            key.functionName().getHermeticity() == FunctionHermeticity.NONHERMETIC,
            "Only non-hermetic keys can be dirty roots: %s",
            key);
        executor.execute(
            () -> {
              SkyValue value;
              try {
                value = fetcher.get(key);
              } catch (InterruptedException e) {
                // Exit fast. Interrupt is handled below on the main thread.
                return;
              }
              if (!checkMissingValues && value == null) {
                return;
              }
              @Nullable
              Version oldMtsv =
                  inMemoryGraph != null
                      ? inMemoryGraph
                          .get(/* requestor= */ null, Reason.OTHER, key)
                          .getMaxTransitiveSourceVersion()
                      : null;
              numKeysChecked.incrementAndGet();
              DirtyResult result;
              try {
                result = checker.check(key, value, oldMtsv, syscallCache, tsgm);
              } catch (IOException e) {
                // Treat IOException as dirty with an unknown value. If this key is requested during
                // an evaluation, we'll attempt to evaluate it - the error may turn out to be
                // permanent or transient.
                result = DirtyResult.dirty();
              }
              if (result.isDirty()) {
                batchResult.add(
                    key, value, result.getNewValue(), result.getNewMaxTransitiveSourceVersion());
              }
            });
      }

      // If a Runnable above crashes, this shutdown can still succeed but the whole server will come
      // down shortly.
      if (ExecutorUtil.interruptibleShutdown(executor)) {
        throw new InterruptedException();
      }
    }
    return batchResult.toImmutable();
  }

  /** An immutable {@link com.google.devtools.build.skyframe.Differencer.DiffWithDelta}. */
  public static class ImmutableBatchDirtyResult implements Differencer.DiffWithDelta {
    private final Collection<SkyKey> dirtyKeysWithoutNewValues;
    private final Map<SkyKey, Delta> dirtyKeysWithNewAndOldValues;
    private final int numKeysChecked;

    private ImmutableBatchDirtyResult(
        Collection<SkyKey> dirtyKeysWithoutNewValues,
        Map<SkyKey, Delta> dirtyKeysWithNewAndOldValues,
        int numKeysChecked) {
      this.dirtyKeysWithoutNewValues = dirtyKeysWithoutNewValues;
      this.dirtyKeysWithNewAndOldValues = dirtyKeysWithNewAndOldValues;
      this.numKeysChecked = numKeysChecked;
    }

    @Override
    public Collection<SkyKey> changedKeysWithoutNewValues() {
      return dirtyKeysWithoutNewValues;
    }

    @Override
    public Map<SkyKey, Delta> changedKeysWithNewValues() {
      return dirtyKeysWithNewAndOldValues;
    }

    public int getNumKeysChecked() {
      return numKeysChecked;
    }
  }

  /**
   * Result of a batch call to {@link SkyValueDirtinessChecker#check}. Partitions the dirty values
   * based on whether we have a new value available for them or not.
   */
  private static class MutableBatchDirtyResult {
    private final Set<SkyKey> concurrentDirtyKeysWithoutNewValues =
        Collections.newSetFromMap(new ConcurrentHashMap<SkyKey, Boolean>());
    private final ConcurrentHashMap<SkyKey, Delta> concurrentDirtyKeysWithNewAndOldValues =
        new ConcurrentHashMap<>();
    private final AtomicInteger numChecked;

    private MutableBatchDirtyResult(AtomicInteger numChecked) {
      this.numChecked = numChecked;
    }

    private void add(
        SkyKey key,
        @Nullable SkyValue oldValue,
        @Nullable SkyValue newValue,
        @Nullable Version newMaxTransitiveSourceVersion) {
      if (newValue == null) {
        concurrentDirtyKeysWithoutNewValues.add(key);
      } else {
        // TODO(b/139545639) - handle old mtsv's and null mtsv's
        if (oldValue == null) {
          concurrentDirtyKeysWithNewAndOldValues.put(
              key, Delta.justNew(newValue, newMaxTransitiveSourceVersion));
        } else {
          concurrentDirtyKeysWithNewAndOldValues.put(
              key, Delta.changed(oldValue, newValue, newMaxTransitiveSourceVersion));
        }
      }
    }

    private ImmutableBatchDirtyResult toImmutable() {
      return new ImmutableBatchDirtyResult(
          concurrentDirtyKeysWithoutNewValues,
          concurrentDirtyKeysWithNewAndOldValues,
          numChecked.get());
    }
  }
}
