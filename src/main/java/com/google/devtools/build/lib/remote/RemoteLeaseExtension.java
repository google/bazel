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
package com.google.devtools.build.lib.remote;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.RequestMetadata;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionCacheUtils;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.cache.ActionCache;
import com.google.devtools.build.lib.actions.cache.ActionCache.Entry;
import com.google.devtools.build.lib.actions.cache.ActionCache.Entry.SerializableTreeArtifactValue;
import com.google.devtools.build.lib.concurrent.ExecutorUtil;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.remote.LeaseService.LeaseExtension;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.skyframe.ActionExecutionValue;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/** A {@link LeaseExtension} implementation that uses REAPI. */
public class RemoteLeaseExtension implements LeaseExtension {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private static final Predicate<SkyKey> ACTION_FILTER =
      SkyFunctionName.functionIs(SkyFunctions.ACTION_EXECUTION);

  private final ScheduledExecutorService scheduledExecutor =
      Executors.newSingleThreadScheduledExecutor(
          new ThreadFactoryBuilder().setNameFormat("lease-extension-%d").build());

  private final ReentrantLock lock = new ReentrantLock();

  private final MemoizingEvaluator memoizingEvaluator;
  @Nullable private final ActionCache actionCache;
  private final CombinedCache combinedCache;
  private final Duration remoteCacheTtl;
  private final RemoteActionExecutionContext context;

  public RemoteLeaseExtension(
      MemoizingEvaluator memoizingEvaluator,
      @Nullable ActionCache actionCache,
      String buildRequestId,
      String commandId,
      CombinedCache combinedCache,
      Duration remoteCacheTtl) {
    this.memoizingEvaluator = memoizingEvaluator;
    this.actionCache = actionCache;
    this.combinedCache = combinedCache;
    this.remoteCacheTtl = remoteCacheTtl;
    RequestMetadata requestMetadata =
        TracingMetadataUtils.buildMetadata(buildRequestId, commandId, "lease-extension", null);
    this.context = RemoteActionExecutionContext.create(requestMetadata);
  }

  @Override
  public void start() {
    // Immediately extend leases for outputs that are already known to skyframe. For clean build,
    // the set of outputs is empty. For incremental build, it contains outputs that were not
    // invalidated after skyframe's dirtiness check.
    var unused = scheduledExecutor.schedule(this::extendLeases, 0, MILLISECONDS);
  }

  private void extendLeases() {
    // Acquire the lock to prevent multiple doExtendLeases() running.
    lock.lock();
    try (var silentCloseable = Profiler.instance().profile("doExtendLeases")) {
      doExtendLeases();
    } finally {
      lock.unlock();
    }
  }

  private void doExtendLeases() {
    var valuesMap = memoizingEvaluator.getValues();
    // We will extend leases for all known outputs so the earliest time when one output could be
    // expired is (now + ttl).
    var earliestExpiration = Instant.now().plus(remoteCacheTtl);

    try {
      for (var entry : valuesMap.entrySet()) {
        SkyKey key = entry.getKey();
        SkyValue value = entry.getValue();
        if (value != null && ACTION_FILTER.test(key)) {
          var action = getActionFromSkyKey(key);
          var actionExecutionValue = (ActionExecutionValue) value;
          var remoteFiles = collectRemoteFiles(actionExecutionValue);
          if (!remoteFiles.isEmpty()) {
            // Lease extensions are performed on action basis, not by collecting all outputs and
            // issue one giant `FindMissingBlobs` call to avoid increasing memory footprint. Since
            // this happens in the background, increased network calls are acceptable.
            try (var silentCloseable1 = Profiler.instance().profile(action.describe())) {
              extendLeaseForAction(action, remoteFiles, earliestExpiration);
            }
          }
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    } catch (Throwable e) {
      logger.atWarning().withCause(e).log("Failed to extend the lease");
    }

    // Only extend the leases again when one of the outputs is about to expire.
    var now = Instant.now();
    Duration delay;
    if (earliestExpiration.isAfter(now)) {
      delay = Duration.between(now, earliestExpiration);
    } else {
      delay = Duration.ZERO;
    }
    var unused = scheduledExecutor.schedule(this::extendLeases, delay.toMillis(), MILLISECONDS);
  }

  private static boolean isRemoteMetadataWithTtl(FileArtifactValue metadata) {
    return metadata.isRemote() && metadata.getExpirationTime() != null;
  }

  private ImmutableList<Map.Entry<? extends Artifact, FileArtifactValue>> collectRemoteFiles(
      ActionExecutionValue actionExecutionValue) {
    var result = ImmutableList.<Map.Entry<? extends Artifact, FileArtifactValue>>builder();
    for (var entry : actionExecutionValue.getAllFileValues().entrySet()) {
      if (isRemoteMetadataWithTtl(entry.getValue())) {
        result.add(entry);
      }
    }

    for (var treeMetadata : actionExecutionValue.getAllTreeArtifactValues().values()) {
      for (var entry : treeMetadata.getChildValues().entrySet()) {
        if (isRemoteMetadataWithTtl(entry.getValue())) {
          result.add(entry);
        }
      }
    }

    return result.build();
  }

  /** Returns {@code true} iff the outputs of the action */
  private void extendLeaseForAction(
      Action action,
      ImmutableList<Map.Entry<? extends Artifact, FileArtifactValue>> remoteFiles,
      Instant expirationTime)
      throws IOException, InterruptedException {
    ImmutableSet<Digest> missingDigests;
    try (var silentCloseable = Profiler.instance().profile("findMissingDigests")) {
      // We assume remote server will extend the leases for all referenced blobs by a
      // FindMissingBlobs call.
      missingDigests =
          getFromFuture(
              combinedCache.findMissingDigests(
                  context,
                  Iterables.transform(
                      remoteFiles, remoteFile -> buildDigest(remoteFile.getValue()))));
    }

    var token = getActionCacheToken(action);
    for (var remoteFile : remoteFiles) {
      var artifact = remoteFile.getKey();
      var metadata = remoteFile.getValue();
      // Only extend the lease for the remote output if it is still alive remotely.
      if (!missingDigests.contains(buildDigest(metadata))) {
        metadata.setExpirationTime(expirationTime);
        if (token != null) {
          if (artifact instanceof TreeFileArtifact treeFileArtifact) {
            token.extendOutputTreeFile(treeFileArtifact, expirationTime);
          } else {
            token.extendOutputFile(artifact, expirationTime);
          }
        }
      }
    }

    if (actionCache != null && token != null && token.dirty) {
      // Only update the action cache entry if the token was updated because it usually involves
      // serialization.
      actionCache.put(token.key, token.entry);
    }
  }

  @Override
  public void stop() {
    if (ExecutorUtil.uninterruptibleShutdownNow(scheduledExecutor)) {
      Thread.currentThread().interrupt();
    }
  }

  private static Digest buildDigest(FileArtifactValue metadata) {
    return DigestUtil.buildDigest(metadata.getDigest(), metadata.getSize());
  }

  private Action getActionFromSkyKey(SkyKey key) throws InterruptedException {
    var actionLookupData = (ActionLookupData) key.argument();
    var actionLookupValue =
        (ActionLookupValue)
            checkNotNull(
                memoizingEvaluator.getExistingValue(actionLookupData.getActionLookupKey()));
    return actionLookupValue.getAction(actionLookupData.getActionIndex());
  }

  @Nullable
  private ActionCacheToken getActionCacheToken(Action action) {
    if (actionCache != null) {
      var actionCacheEntryWithKey = ActionCacheUtils.getCacheEntryWithKey(actionCache, action);
      if (actionCacheEntryWithKey != null) {
        return new ActionCacheToken(
            actionCacheEntryWithKey.getKey(), actionCacheEntryWithKey.getValue());
      }
    }

    return null;
  }

  private static class ActionCacheToken {
    final String key;
    final ActionCache.Entry entry;
    private boolean dirty;

    ActionCacheToken(String key, Entry entry) {
      this.key = key;
      this.entry = entry;
    }

    void extendOutputFile(Artifact artifact, Instant expirationTime) {
      var metadata = entry.getOutputFile(artifact);
      if (metadata != null) {
        metadata.setExpirationTime(expirationTime);
        dirty = true;
      }
    }

    void extendOutputTreeFile(TreeFileArtifact treeFile, Instant expirationTime) {
      SerializableTreeArtifactValue treeMetadata = entry.getOutputTree(treeFile.getParent());
      if (treeMetadata != null) {
        var metadata = treeMetadata.childValues().get(treeFile.getTreeRelativePathString());
        if (metadata != null) {
          metadata.setExpirationTime(expirationTime);
          dirty = true;
        }
      }
    }
  }
}
