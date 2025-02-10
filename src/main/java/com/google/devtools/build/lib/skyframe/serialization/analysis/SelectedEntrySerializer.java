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
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.skyframe.FileOpNodeOrFuture.EmptyFileOpNode.EMPTY_FILE_OP_NODE;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.FrontierSerializer.SelectionMarking.FRONTIER_CANDIDATE;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.ConstantFileData.CONSTANT_FILE;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.ConstantListingData.CONSTANT_LISTING;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.ConstantNodeData.CONSTANT_NODE;
import static com.google.devtools.build.lib.skyframe.serialization.proto.DataType.DATA_TYPE_ANALYSIS_NODE;
import static com.google.devtools.build.lib.skyframe.serialization.proto.DataType.DATA_TYPE_EMPTY;
import static com.google.devtools.build.lib.skyframe.serialization.proto.DataType.DATA_TYPE_EXECUTION_NODE;
import static com.google.devtools.build.lib.skyframe.serialization.proto.DataType.DATA_TYPE_FILE;
import static com.google.devtools.build.lib.skyframe.serialization.proto.DataType.DATA_TYPE_LISTING;
import static java.util.concurrent.ForkJoinPool.commonPool;

import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.concurrent.QuiescingFuture;
import com.google.devtools.build.lib.skyframe.FileOpNodeOrFuture.FileOpNode;
import com.google.devtools.build.lib.skyframe.FileOpNodeOrFuture.FileOpNodeOrEmpty;
import com.google.devtools.build.lib.skyframe.FileOpNodeOrFuture.FutureFileOpNode;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecs;
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint;
import com.google.devtools.build.lib.skyframe.serialization.ProfileCollector;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.skyframe.serialization.SerializationResult;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.FrontierNodeVersion;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FrontierSerializer.SelectionMarking;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.FileInvalidationDataInfo;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.FutureFileDataInfo;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.FutureListingDataInfo;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.FutureNodeDataInfo;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.InvalidationDataInfo;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.ListingInvalidationDataInfo;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.NodeInvalidationDataInfo;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingEventListener.SerializedNodeEvent;
import com.google.devtools.build.lib.versioning.LongVersionGetter;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.InMemoryNodeEntry;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Supports uploading a selected set of {@link SkyKey}s to a {@link FingerprintValueService}.
 *
 * <p>Execution is encapsulated via the {@link #uploadSelection} method.
 *
 * <p>Each persisted entry consists of the following:
 *
 * <ul>
 *   <li><b>Key</b>: formatted as {@code fingerprint(<version stamp>, <serialized SkyKey>)}.
 *   <li><b>Invalidation Data and Value</b>: formatted as {@code <invalidation data>, <serialized
 *       SkyValue>}. The {@code invalidation data} consists of a {@link DataType} number followed by
 *       a corresponding key, if it is not {@link DATA_TYPE_EMPTY}.
 * </ul>
 *
 * <p>Note that both {@code <invalidation data>} and {@code <serialized SkyValue>} are intended to
 * have relatively small immediate representations. When there is a large amount of data, it will be
 * expressed via references (e.g., keys to a other {@link FingerprintValueService} entries).
 */
final class SelectedEntrySerializer implements Consumer<Map.Entry<SkyKey, SelectionMarking>> {
  private final InMemoryGraph graph;
  private final ObjectCodecs codecs;
  private final FrontierNodeVersion frontierVersion;

  private final FingerprintValueService fingerprintValueService;

  private final FileOpNodeMemoizingLookup fileOpNodes;
  private final FileDependencySerializer fileDependencySerializer;

  private final WriteStatusesFuture writeStatuses;

  private final EventBus eventBus;
  private final ProfileCollector profileCollector;
  private final AtomicInteger frontierValueCount;

  /**
   * Uploads the {@link FRONTIER_CANDIDATE} marked entries of {@code selection} to {@code
   * fingerprintValueService}.
   */
  static ListenableFuture<Void> uploadSelection(
      InMemoryGraph graph,
      LongVersionGetter versionGetter,
      ObjectCodecs codecs,
      FrontierNodeVersion frontierVersion,
      ImmutableMap<SkyKey, SelectionMarking> selection,
      FingerprintValueService fingerprintValueService,
      EventBus eventBus,
      ProfileCollector profileCollector,
      AtomicInteger frontierValueCount) {
    var fileOpNodes = new FileOpNodeMemoizingLookup(graph);
    var fileDependencySerializer =
        new FileDependencySerializer(versionGetter, graph, fingerprintValueService);
    var writeStatuses = new WriteStatusesFuture();
    var serializer =
        new SelectedEntrySerializer(
            graph,
            codecs,
            frontierVersion,
            fingerprintValueService,
            fileOpNodes,
            fileDependencySerializer,
            writeStatuses,
            eventBus,
            profileCollector,
            frontierValueCount);
    selection.entrySet().parallelStream().forEach(serializer);
    writeStatuses.notifyAllStarted();
    return writeStatuses;
  }

  private SelectedEntrySerializer(
      InMemoryGraph graph,
      ObjectCodecs codecs,
      FrontierNodeVersion frontierVersion,
      FingerprintValueService fingerprintValueService,
      FileOpNodeMemoizingLookup fileOpNodes,
      FileDependencySerializer fileDependencySerializer,
      WriteStatusesFuture writeStatuses,
      EventBus eventBus,
      ProfileCollector profileCollector,
      AtomicInteger frontierValueCount) {
    this.graph = graph;
    this.codecs = codecs;
    this.frontierVersion = frontierVersion;
    this.fingerprintValueService = fingerprintValueService;
    this.fileOpNodes = fileOpNodes;
    this.fileDependencySerializer = fileDependencySerializer;
    this.writeStatuses = writeStatuses;
    this.eventBus = eventBus;
    this.profileCollector = profileCollector;
    this.frontierValueCount = frontierValueCount;
  }

  @Override
  public void accept(Map.Entry<SkyKey, SelectionMarking> entry) {
    if (!entry.getValue().equals(FRONTIER_CANDIDATE)) {
      return;
    }
    SkyKey key = entry.getKey();

    // TODO: b/371508153 - only upload nodes that were freshly computed by this invocation and
    // unaffected by local, un-submitted changes.

    try {
      switch (key) {
        case ActionLookupKey actionLookupKey:
          uploadEntry(actionLookupKey, actionLookupKey);
          break;
        case ActionLookupData lookupData:
          uploadEntry(lookupData, checkNotNull(lookupData.getActionLookupKey(), lookupData));
          break;
        case DerivedArtifact artifact:
          // This case handles the subclasses of DerivedArtifact. DerivedArtifact itself will show
          // up here as ActionLookupData.
          uploadEntry(artifact, checkNotNull(artifact.getArtifactOwner(), artifact));
          break;
        default:
          throw new AssertionError("Unexpected selected type: " + key + " " + key.getClass());
      }
      frontierValueCount.getAndIncrement();
      eventBus.post(new SerializedNodeEvent(key));
    } catch (SerializationException e) {
      writeStatuses.addWriteStatus(immediateFailedFuture(e));
    }
  }

  /**
   * Uploads the entry associated with {@code key} persisting alongside it, the file dependencies
   * associated with {@code dependencyKey}.
   */
  private void uploadEntry(SkyKey key, ActionLookupKey dependencyKey)
      throws SerializationException {
    writeStatuses.selectedEntryStarting();
    commonPool().execute(new FileOpNodeProcessor(serializeEntry(key), dependencyKey));
  }

  /** Key and value bytes representing a serialized Skyframe entry. */
  private abstract static sealed class SerializedEntry
      permits SerializedAnalysisEntry, SerializedExecutionEntry {
    private final PackedFingerprint versionedKey;
    private final byte[] valueBytes;

    private SerializedEntry(PackedFingerprint versionedKey, byte[] valueBytes) {
      this.versionedKey = versionedKey;
      this.valueBytes = valueBytes;
    }

    abstract boolean isExecutionValue();
  }

  private static final class SerializedAnalysisEntry extends SerializedEntry {
    private SerializedAnalysisEntry(PackedFingerprint versionedKey, byte[] valueBytes) {
      super(versionedKey, valueBytes);
    }

    @Override
    boolean isExecutionValue() {
      return false;
    }
  }

  private static final class SerializedExecutionEntry extends SerializedEntry {
    private SerializedExecutionEntry(PackedFingerprint versionedKey, byte[] valueBytes) {
      super(versionedKey, valueBytes);
    }

    @Override
    boolean isExecutionValue() {
      return true;
    }
  }

  private SerializedEntry serializeEntry(SkyKey key) throws SerializationException {
    SerializationResult<ByteString> keyBytes =
        codecs.serializeMemoizedAndBlocking(fingerprintValueService, key, profileCollector);
    writeStatuses.addWriteStatus(keyBytes.getFutureToBlockWritesOn());

    InMemoryNodeEntry node = checkNotNull(graph.getIfPresent(key), key);
    SerializationResult<ByteString> valueBytes =
        codecs.serializeMemoizedAndBlocking(
            fingerprintValueService, node.getValue(), profileCollector);
    writeStatuses.addWriteStatus(valueBytes.getFutureToBlockWritesOn());

    PackedFingerprint versionedKey =
        fingerprintValueService.fingerprint(
            frontierVersion.concat(keyBytes.getObject().toByteArray()));

    byte[] bytes = valueBytes.getObject().toByteArray();
    return key instanceof ActionLookupKey
        ? new SerializedAnalysisEntry(versionedKey, bytes)
        : new SerializedExecutionEntry(versionedKey, bytes);
  }

  private final class FileOpNodeProcessor implements FutureCallback<FileOpNodeOrEmpty>, Runnable {
    private final SerializedEntry entry;
    private final ActionLookupKey dependencyKey;

    private FileOpNodeProcessor(SerializedEntry entry, ActionLookupKey dependencyKey) {
      this.entry = entry;
      this.dependencyKey = dependencyKey;
    }

    @Override
    public void run() {
      switch (fileOpNodes.computeNode(dependencyKey)) {
        case FileOpNodeOrEmpty nodeOrEmpty:
          onSuccess(nodeOrEmpty);
          break;
        case FutureFileOpNode future:
          Futures.addCallback(future, this, directExecutor());
          break;
      }
    }

    @Override
    public final void onSuccess(FileOpNodeOrEmpty nodeOrEmpty) {
      var handler = new InvalidationDataInfoHandler();
      switch (nodeOrEmpty) {
        case FileOpNode node:
          switch (fileDependencySerializer.registerDependency(node)) {
            case InvalidationDataInfo dataInfo:
              handler.onSuccess(dataInfo);
              break;
            case FutureFileDataInfo futureFile:
              Futures.addCallback(futureFile, handler, directExecutor());
              break;
            case FutureListingDataInfo futureListing:
              Futures.addCallback(futureListing, handler, directExecutor());
              break;
            case FutureNodeDataInfo futureNode:
              Futures.addCallback(futureNode, handler, directExecutor());
              break;
          }
          break;
        case EMPTY_FILE_OP_NODE:
          handler.onSuccess(/* dataInfo= */ null);
          break;
      }
    }

    @Override
    public final void onFailure(Throwable t) {
      writeStatuses.notifyWriteFailure(t);
    }

    private final class InvalidationDataInfoHandler
        implements FutureCallback<InvalidationDataInfo> {
      /**
       * Saves the entry for to the {@link FingerprintValueService}.
       *
       * <p>The entry includes both value and the associated invalidation data. More precisely, it
       * consists of the following components.
       *
       * <ol>
       *   <li>If {@code dataInfo} is null, the invalidation data is the {@link DATA_TYPE_EMPTY}
       *       value only.
       *   <li>Otherwise when {@code dataInfo} is non-null, the invalidation data starts with a type
       *       value, {@link DATA_TYPE_FILE}, {@link DATA_TYPE_LISTING}, {@link
       *       DATA_TYPE_ANALYSIS_NODE} or {@link DATA_TYPE_EXECUTION_NODE}, depending on {@code
       *       dataInfo}'s and {@link FileOpNodeProcessor#entry}'s type. The invalidation data cache
       *       key follows the type value.
       *   <li>The {@link #entry}'s value bytes.
       * </ol>
       */
      @Override
      public void onSuccess(@Nullable InvalidationDataInfo dataInfo) {
        ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
        CodedOutputStream codedOut = CodedOutputStream.newInstance(bytesOut);

        try {
          switch (dataInfo) {
            case CONSTANT_FILE:
            case CONSTANT_LISTING:
            case CONSTANT_NODE:
            // fall through
            case null:
              codedOut.writeEnumNoTag(DATA_TYPE_EMPTY.getNumber());
              break;
            case FileInvalidationDataInfo file:
              codedOut.writeEnumNoTag(DATA_TYPE_FILE.getNumber());
              codedOut.writeStringNoTag(file.cacheKey());
              writeStatuses.addWriteStatus(file.writeStatus());
              break;
            case ListingInvalidationDataInfo listing:
              codedOut.writeEnumNoTag(DATA_TYPE_LISTING.getNumber());
              codedOut.writeStringNoTag(listing.cacheKey());
              writeStatuses.addWriteStatus(listing.writeStatus());
              break;
            case NodeInvalidationDataInfo node:
              codedOut.writeEnumNoTag(
                  (entry.isExecutionValue() ? DATA_TYPE_EXECUTION_NODE : DATA_TYPE_ANALYSIS_NODE)
                      .getNumber());
              node.cacheKey().writeTo(codedOut);
              writeStatuses.addWriteStatus(node.writeStatus());
              break;
          }
          codedOut.writeRawBytes(entry.valueBytes);
          codedOut.flush();
        } catch (IOException e) {
          // A ByteArrayOutputStream backed CodedOutputStream doesn't throw IOExceptions.
          throw new AssertionError(e);
        }
        byte[] entryBytes = bytesOut.toByteArray();
        writeStatuses.addWriteStatus(fingerprintValueService.put(entry.versionedKey, entryBytes));

        // IMPORTANT: when this completes, no more write statuses can be added.
        writeStatuses.selectedEntryDone();
      }

      @Override
      public final void onFailure(Throwable t) {
        writeStatuses.notifyWriteFailure(t);
      }
    }
  }

  private static class WriteStatusesFuture extends QuiescingFuture<Void>
      implements FutureCallback<Void> {
    private void selectedEntryStarting() {
      increment();
    }

    private void selectedEntryDone() {
      decrement();
    }

    private void notifyAllStarted() {
      decrement();
    }

    private void notifyWriteFailure(Throwable t) {
      notifyException(t);
    }

    @Override
    protected Void getValue() {
      return null;
    }

    private void addWriteStatus(@Nullable ListenableFuture<Void> writeStatus) {
      if (writeStatus == null) {
        return;
      }
      increment();
      Futures.addCallback(writeStatus, (FutureCallback<Void>) this, directExecutor());
    }

    /**
     * Implementation of {@link FutureCallback<void>}.
     *
     * @deprecated only for use via {@link #addWriteStatus}
     */
    @Override
    @Deprecated // only called via addWriteStatus
    public void onSuccess(Void unused) {
      decrement();
    }

    /**
     * Implementation of {@link FutureCallback<void>}.
     *
     * @deprecated only for use via {@link #addWriteStatus}
     */
    @Override
    @Deprecated // only called via addWriteStatus
    public void onFailure(Throwable t) {
      notifyWriteFailure(t);
    }
  }
}
