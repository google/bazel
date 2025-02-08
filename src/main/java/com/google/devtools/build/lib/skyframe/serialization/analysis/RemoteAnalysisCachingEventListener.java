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

import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multiset;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import com.google.devtools.build.lib.concurrent.ThreadSafety;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.FrontierNodeVersion;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.NoCachedData;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.Restart;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.RetrievalResult;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.RetrievedValue;
import com.google.devtools.build.skyframe.ExecutionPhaseSkyKey;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** An {@link com.google.common.eventbus.EventBus} listener for remote analysis caching events. */
@ThreadSafety.ThreadSafe
public class RemoteAnalysisCachingEventListener {

  /**
   * An event for when a Skyframe node has been serialized, but its associated write futures (i.e.
   * RPC latency) may not be done yet.
   */
  public record SerializedNodeEvent(SkyKey key) {
    public SerializedNodeEvent {
      checkNotNull(key);
    }
  }

  private final Set<SkyKey> serializedKeys = ConcurrentHashMap.newKeySet();
  private final Set<SkyKey> cacheHits = ConcurrentHashMap.newKeySet();
  private final Set<SkyKey> cacheMisses = ConcurrentHashMap.newKeySet();
  private final AtomicInteger analysisCacheHits = new AtomicInteger();
  private final AtomicInteger analysisCacheMisses = new AtomicInteger();
  private final AtomicInteger executionCacheHits = new AtomicInteger();
  private final AtomicInteger executionCacheMisses = new AtomicInteger();
  private final Set<SerializationException> serializationExceptions = ConcurrentHashMap.newKeySet();

  private FrontierNodeVersion skyValueVersion;

  @Subscribe
  @AllowConcurrentEvents
  @SuppressWarnings("unused")
  public void onSerializationComplete(SerializedNodeEvent event) {
    serializedKeys.add(event.key());
  }

  /** Returns the counts of {@link SkyFunctionName} from serialized nodes of this invocation. */
  public Multiset<SkyFunctionName> getSkyfunctionCounts() {
    Multiset<SkyFunctionName> counts = HashMultiset.create();
    serializedKeys.forEach(key -> counts.add(key.functionName()));
    return counts;
  }

  /** Returns the count of serialized nodes of this invocation. */
  public int getSerializedKeysCount() {
    return serializedKeys.size();
  }

  public Set<SkyKey> getSerializedKeys() {
    return ImmutableSet.copyOf(serializedKeys);
  }

  public Set<SkyKey> getCacheHits() {
    return ImmutableSet.copyOf(cacheHits);
  }

  @ThreadSafe
  public void recordRetrievalResult(RetrievalResult result, SkyKey key) {
    if (result instanceof Restart) {
      return;
    }

    switch (result) {
      case RetrievedValue unusedValue -> {
        if (!cacheHits.add(key)) {
          return;
        }
        if (isExecutionNode(key)) {
          executionCacheHits.incrementAndGet();
        } else {
          analysisCacheHits.incrementAndGet();
        }
      }
      case NoCachedData unusedNoCachedData -> {
        if (!cacheMisses.add(key)) {
          return;
        }
        if (isExecutionNode(key)) {
          executionCacheMisses.incrementAndGet();
        } else {
          analysisCacheMisses.incrementAndGet();
        }
      }
      case Restart unusedRestart ->
          throw new IllegalStateException(
              "should have returned earlier"); // restart counts are not useful (yet).
    }
  }

  private static boolean isExecutionNode(SkyKey key) {
    return key instanceof ExecutionPhaseSkyKey;
  }

  /**
   * Returns the number of successful analysis SkyValue retrievals from the {@link
   * com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService} .
   */
  public int getAnalysisNodeCacheHits() {
    return analysisCacheHits.get();
  }

  /**
   * Returns the number of successful analysis SkyValue retrievals from the {@link
   * com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService} .
   */
  public int getAnalysisNodeCacheMisses() {
    return analysisCacheMisses.get();
  }

  /**
   * Returns the number of unsuccessful execution SkyValue retrievals from the {@link
   * com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService} .
   */
  public int getExecutionNodeCacheHits() {
    return executionCacheHits.get();
  }

  /**
   * Returns the number of unsuccessful execution SkyValue retrievals from the {@link
   * com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService} .
   */
  public int getExecutionNodeCacheMisses() {
    return executionCacheMisses.get();
  }

  /** Records a {@link SerializationException} encountered during SkyValue retrievals. */
  public void recordSerializationException(SerializationException e) {
    serializationExceptions.add(e);
  }

  /**
   * Returns the number of {@link SerializationException}s that were thrown during this invocation.
   */
  public int getSerializationExceptionCounts() {
    return serializationExceptions.size();
  }

  public void recordSkyValueVersion(FrontierNodeVersion version) {
    this.skyValueVersion = version;
  }

  /** Returns the {@link FrontierNodeVersion} for versioning the SkyValues in this build. */
  public FrontierNodeVersion getSkyValueVersion() {
    return skyValueVersion;
  }
}
