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
package com.google.devtools.build.lib.skyframe.serialization.testutils;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.immediateWriteStatus;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.WriteStatus;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import javax.annotation.Nullable;

/**
 * A {@link FingerprintValueStore} implementation that queues {@link FingerprintValueStore#get}
 * operations and makes their completion controllable by the caller.
 */
public final class GetRecordingStore implements FingerprintValueStore {
  private final ConcurrentHashMap<KeyBytesProvider, byte[]> fingerprintToContents =
      new ConcurrentHashMap<>();

  private final LinkedBlockingQueue<GetRequest> requestQueue = new LinkedBlockingQueue<>();

  @Override
  public WriteStatus put(KeyBytesProvider fingerprint, byte[] serializedBytes) {
    fingerprintToContents.put(fingerprint, serializedBytes);
    return immediateWriteStatus();
  }

  @Override
  public ListenableFuture<byte[]> get(KeyBytesProvider fingerprint) {
    SettableFuture<byte[]> response = SettableFuture.create();
    requestQueue.offer(new GetRequest(this, fingerprint, response));
    return response;
  }

  public GetRequest takeFirstRequest() throws InterruptedException {
    return requestQueue.take();
  }

  @Nullable
  public GetRequest pollRequest() {
    return requestQueue.poll();
  }

  /** Encapsulates a {@link #get} operation. */
  public record GetRequest(
      GetRecordingStore parent, KeyBytesProvider fingerprint, SettableFuture<byte[]> response) {
    /**
     * Completes the {@link #response} by looking up the {@link #fingerprint} in the {@link
     * #parent}'s in-memory map.
     */
    public void complete() {
      response().set(checkNotNull(parent().fingerprintToContents.get(fingerprint())));
    }
  }
}
