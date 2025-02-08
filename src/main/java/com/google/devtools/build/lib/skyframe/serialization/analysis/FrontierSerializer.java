// Copyright 2024 The Bazel Authors. All rights reserved.
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
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.FrontierSerializer.SelectionMarking.ACTIVE;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.FrontierSerializer.SelectionMarking.FRONTIER_CANDIDATE;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.LongVersionGetterTestInjection.getVersionGetterForTesting;
import static com.google.devtools.build.lib.util.TestType.isInTest;
import static java.util.concurrent.ForkJoinPool.commonPool;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.Artifact.SourceArtifact;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.RemoteAnalysisCaching;
import com.google.devtools.build.lib.server.FailureDetails.RemoteAnalysisCaching.Code;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectBaseKey;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecs;
import com.google.devtools.build.lib.skyframe.serialization.ProfileCollector;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.FrontierNodeVersion;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions.RemoteAnalysisCacheMode;
import com.google.devtools.build.lib.versioning.LongVersionGetter;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.InMemoryNodeEntry;
import com.google.devtools.build.skyframe.SkyKey;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Implements frontier serialization with pprof dumping using {@code
 * --experimental_remote_analysis_cache_mode=upload}.
 */
public final class FrontierSerializer {
  private FrontierSerializer() {}

  /**
   * Serializes the frontier contained in the current Skyframe graph into a {@link ProfileCollector}
   * writing the resulting proto to {@code path}.
   *
   * @return empty if successful, otherwise a result containing the appropriate error
   */
  public static Optional<FailureDetail> serializeAndUploadFrontier(
      RemoteAnalysisCachingDependenciesProvider dependenciesProvider,
      SkyframeExecutor skyframeExecutor,
      LongVersionGetter versionGetter,
      Reporter reporter,
      EventBus eventBus)
      throws InterruptedException {
    // Starts initializing ObjectCodecs in a background thread as it can take some time.
    var futureCodecs = new FutureTask<>(dependenciesProvider::getObjectCodecs);
    commonPool().execute(futureCodecs);

    var stopwatch = new ResettingStopwatch(Stopwatch.createStarted());
    InMemoryGraph graph = skyframeExecutor.getEvaluator().getInMemoryGraph();

    ImmutableMap<SkyKey, SelectionMarking> selection =
        computeSelection(graph, dependenciesProvider::withinActiveDirectories);

    reporter.handle(
        Event.info(
            String.format("Found %d active or frontier keys in %s", selection.size(), stopwatch)));

    if (dependenciesProvider.mode() == RemoteAnalysisCacheMode.DUMP_UPLOAD_MANIFEST_ONLY) {
      reporter.handle(
          Event.warn("Dry run of upload, dumping selection to stdout (warning: can be large!)"));
      dumpUploadManifest(
          new PrintStream(
              new BufferedOutputStream(reporter.getOutErr().getOutputStream(), 1024 * 1024)),
          selection);
      return Optional.empty();
    }

    ObjectCodecs codecs;
    try {
      codecs = futureCodecs.get();
    } catch (ExecutionException e) {
      // No exceptions are expected here.
      throw new IllegalStateException("failed to initialize ObjectCodecs", e.getCause());
    }
    if (codecs == null) {
      String message = "serialization not supported";
      reporter.error(null, message);
      return Optional.of(createFailureDetail(message, Code.SERIALIZED_FRONTIER_PROFILE_FAILED));
    }

    reporter.handle(Event.info(String.format("Initializing codecs took %s\n", stopwatch)));

    FrontierNodeVersion frontierVersion;
    try {
      frontierVersion = dependenciesProvider.getSkyValueVersion();
    } catch (SerializationException e) {
      String message = "error computing frontier version " + e.getMessage();
      reporter.error(null, message);
      return Optional.of(createFailureDetail(message, Code.SERIALIZED_FRONTIER_PROFILE_FAILED));
    }

    var profileCollector = new ProfileCollector();
    var frontierValueCount = new AtomicInteger();

    if (versionGetter == null) {
      if (isInTest()) {
        versionGetter = getVersionGetterForTesting();
      } else {
        throw new NullPointerException("missing versionGetter");
      }
    }

    ListenableFuture<Void> writeStatus =
        SelectedEntrySerializer.uploadSelection(
            graph,
            versionGetter,
            codecs,
            frontierVersion,
            selection,
            dependenciesProvider.getFingerprintValueService(),
            eventBus,
            profileCollector,
            frontierValueCount);

    reporter.handle(
        Event.info(
            String.format("Serialized %s frontier entries in %s", frontierValueCount, stopwatch)));

    try {
      var unusedNull = writeStatus.get();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      String message = cause.getMessage();
      if (!(cause instanceof SerializationException || cause instanceof IOException)) {
        message = "with unexpected exception type " + cause.getClass().getName() + ": " + message;
      }
      reporter.error(/* location= */ null, message, cause);
      return Optional.of(createFailureDetail(message, Code.SERIALIZED_FRONTIER_PROFILE_FAILED));
    }
    reporter.handle(
        Event.info(String.format("Waiting for write futures took an additional %s", stopwatch)));

    String profilePath = dependenciesProvider.serializedFrontierProfile();
    if (profilePath.isEmpty()) {
      return Optional.empty();
    }

    try (var fileOutput = new FileOutputStream(profilePath);
        var bufferedOutput = new BufferedOutputStream(fileOutput)) {
      profileCollector.toProto().writeTo(bufferedOutput);
    } catch (IOException e) {
      String message = "Error writing serialization profile to file: " + e.getMessage();
      reporter.error(null, message, e);
      return Optional.of(createFailureDetail(message, Code.SERIALIZED_FRONTIER_PROFILE_FAILED));
    }
    return Optional.empty();
  }

  private static void dumpUploadManifest(PrintStream out, Map<SkyKey, SelectionMarking> selection) {
    var frontierCandidates = ImmutableList.builder();
    var activeSet = ImmutableList.builder();
    selection
        .entrySet()
        .forEach(
            entry -> {
              switch (entry.getValue()) {
                case ACTIVE -> activeSet.add(entry.getKey().getCanonicalName());
                case FRONTIER_CANDIDATE ->
                    frontierCandidates.add(entry.getKey().getCanonicalName());
              }
            });
    frontierCandidates.build().stream()
        .sorted()
        .forEach(k -> out.println("FRONTIER_CANDIDATE: " + k));
    activeSet.build().stream().sorted().forEach(k -> out.println("ACTIVE: " + k));
    out.flush();
  }

  @VisibleForTesting
  enum SelectionMarking {
    /**
     * The entry is marked as a frontier candidate.
     *
     * <p>If a node is still a frontier candidate at the end of the selection process, it is a
     * frontier node and should be serialized.
     */
    FRONTIER_CANDIDATE,
    /** The node is in the active set and will not be serialized. */
    ACTIVE
  }

  @VisibleForTesting
  static ImmutableMap<SkyKey, SelectionMarking> computeSelection(
      InMemoryGraph graph, Predicate<PackageIdentifier> matcher) {
    ConcurrentHashMap<SkyKey, SelectionMarking> selection = new ConcurrentHashMap<>();
    graph.parallelForEach(
        node -> {
          switch (node.getKey()) {
            case ActionLookupKey key when key.getLabel() != null -> {
              if (matcher.test(key.getLabel().getPackageIdentifier())) {
                markActiveAndTraverseEdges(graph, key, selection);
              }
            }
            case ActionLookupData data -> {
              if (data.valueIsShareable()) {
                selection.putIfAbsent(data, FRONTIER_CANDIDATE);
              } else {
                // If this is UnshareableActionLookupData, then its value will never be shared and
                // the ActionExecutionFunction will be re-evaluated locally. To evaluate it locally,
                // it will need the corresponding full ActionLookupKey's value, so that cannot be
                // cached as well. So, mark the ActionLookupKey (and its rdeps) as active,
                // so the deserializing build will not incorrectly cache hit on a CT/Aspect
                // that owns such actions, which should be evaluated locally then.
                markActiveAndTraverseEdges(graph, data.getActionLookupKey(), selection);
              }
            }
            case Artifact artifact -> {
              switch (artifact) {
                case DerivedArtifact derived:
                  if (!derived.valueIsShareable()) {
                    return;
                  }
                  // Artifact#key is the canonical function to produce the SkyKey that will build
                  // this artifact. We want to avoid serializing ordinary DerivedArtifacts, which
                  // are never built by Skyframe directly, and the function will return
                  // ActionLookupData as the canonical key for those artifacts instead.
                  SkyKey artifactKey = Artifact.key(derived);
                  if (artifactKey instanceof ActionLookupData) {
                    return; // Already handled in the ActionLookupData switch case above.
                  }
                  selection.putIfAbsent(artifactKey, FRONTIER_CANDIDATE);
                  break;
                case SourceArtifact source:
                  break; // Skips source artifacts because they are cheap to compute.
              }
            }
            default -> {}
          }
        });

    // Filter for ActionExecutionValues owned by active analysis nodes and skip them, because
    // they should be evaluated locally.
    return selection.entrySet().parallelStream()
        .map(
            entry -> {
              if (!(entry.getKey() instanceof ActionLookupData ald)) {
                return entry;
              }
              if (entry.getValue() == FRONTIER_CANDIDATE
                  && selection.get(ald.getActionLookupKey()) == ACTIVE) {
                return Map.entry(entry.getKey(), ACTIVE);
              }
              return entry;
            })
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private static void markActiveAndTraverseEdges(
      InMemoryGraph graph,
      ActionLookupKey root,
      ConcurrentHashMap<SkyKey, SelectionMarking> selection) {
    Label label = root.getLabel();
    if (label == null) {
      return;
    }
    if (selection.put(root, ACTIVE) == ACTIVE) {
      return;
    }
    if (root instanceof AspectBaseKey aspectKey) {
      // Whenever an aspect is marked active, its base configured target must also be marked active.
      // This avoids a situation where an aspect inspects a deserialized configured target, which
      // may crash because the configured target doesn't have its actions.
      //
      // This is possible when an aspect is in the UTC of an active node via an attribute label.
      markActiveAndTraverseEdges(graph, aspectKey.getBaseConfiguredTargetKey(), selection);
    }

    InMemoryNodeEntry node = checkNotNull(graph.getIfPresent(root), root);
    if (!node.isDone()) {
      // This node was marked dirty or changed in the most recent build, but its value was not
      // necessary by any node in that evaluation, so it was never evaluated. Because this node was
      // never evaluated, it doesn't need to be added to the active set -- it is essentially
      // disconnected from the current graph evaluation.
      //
      // However, this node's direct deps may still be frontier candidates, but only if they are
      // reachable from another active node, and candidate selection will be handled by them.
      return;
    }

    for (SkyKey dep : node.getDirectDeps()) {
      if (!(dep instanceof ActionLookupKey actionLookupKey)) {
        continue;
      }

      // Three cases where a child node is disqualified to be a frontier candidate:
      //
      // 1) It doesn't have a label (e.g. BuildInfoKey). These nodes are not deserialized by the
      // analysis functions we care about.
      // 2) It is _already_ marked as ACTIVE, which means it was visited as an rdep from an active
      // root. putIfAbsent will be a no-op.
      // 3) It _will_ be marked as ACTIVE when visited as a rdep from an active root later, and
      // overrides its FRONTIER_CANDIDATE state.
      //
      // In all cases, frontier candidates will never include nodes in the active directories. This
      // is enforced after selection completes.
      if (actionLookupKey.getLabel() != null) {
        selection.putIfAbsent(actionLookupKey, FRONTIER_CANDIDATE);
      }
    }
    for (SkyKey rdep : node.getReverseDepsForDoneEntry()) {
      if (!(rdep instanceof ActionLookupKey parent)) {
        continue;
      }
      // The active set can include nodes outside of the active directories iff they are in the UTC
      // of a root in the active directories.
      markActiveAndTraverseEdges(graph, parent, selection);
    }
  }

  /** Stopwatch that resets upon reporting the time via {@link #toString}. */
  private record ResettingStopwatch(Stopwatch stopwatch) {
    @Override
    public String toString() {
      String text = stopwatch.toString();
      stopwatch.reset().start();
      return text;
    }
  }

  public static FailureDetail createFailureDetail(String message, Code detailedCode) {
    return FailureDetail.newBuilder()
        .setMessage(message)
        .setRemoteAnalysisCaching(RemoteAnalysisCaching.newBuilder().setCode(detailedCode))
        .build();
  }
}
