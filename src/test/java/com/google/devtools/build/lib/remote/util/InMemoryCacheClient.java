// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.util;

import build.bazel.remote.execution.v2.ActionCacheUpdateCapabilities;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.CacheCapabilities;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.ServerCapabilities;
import build.bazel.remote.execution.v2.SymlinkAbsolutePathStrategy;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.remote.common.CacheNotFoundException;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient;
import java.io.IOException;
import java.io.OutputStream;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** A {@link RemoteCacheClient} that stores its contents in memory. */
public class InMemoryCacheClient implements RemoteCacheClient {

  private final ListeningExecutorService executorService =
      MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(100));
  private final ConcurrentMap<Digest, Exception> downloadFailures = new ConcurrentHashMap<>();
  private final ConcurrentMap<ActionKey, ActionResult> ac = new ConcurrentHashMap<>();
  private final ConcurrentMap<Digest, byte[]> cas;

  private AtomicInteger numSuccess = new AtomicInteger();
  private AtomicInteger numFailures = new AtomicInteger();
  private final ConcurrentMap<Digest, AtomicInteger> numFindMissingDigests =
      new ConcurrentHashMap<>();

  public InMemoryCacheClient(Map<Digest, byte[]> casEntries) {
    this.cas = new ConcurrentHashMap<>();
    for (Map.Entry<Digest, byte[]> entry : casEntries.entrySet()) {
      cas.put(entry.getKey(), entry.getValue());
    }
  }

  public InMemoryCacheClient() {
    this.cas = new ConcurrentHashMap<>();
  }

  public void addDownloadFailure(Digest digest, Exception e) {
    downloadFailures.put(digest, e);
  }

  public int getNumSuccessfulDownloads() {
    return numSuccess.get();
  }

  public int getNumFailedDownloads() {
    return numFailures.get();
  }

  public Map<Digest, Integer> getNumFindMissingDigests() {
    return numFindMissingDigests.entrySet().stream()
        .map(entry -> new SimpleEntry<>(entry.getKey(), entry.getValue().get()))
        .collect(Collectors.toMap(SimpleEntry::getKey, SimpleEntry::getValue));
  }

  @Override
  public ListenableFuture<Void> downloadBlob(
      RemoteActionExecutionContext context, Digest digest, OutputStream out) {
    Exception failure = downloadFailures.get(digest);
    if (failure != null) {
      numFailures.incrementAndGet();
      return Futures.immediateFailedFuture(failure);
    }

    byte[] data = cas.get(digest);
    if (data == null) {
      return Futures.immediateFailedFuture(new CacheNotFoundException(digest));
    }

    try {
      out.write(data);
      out.flush();
    } catch (IOException e) {
      numFailures.incrementAndGet();
      return Futures.immediateFailedFuture(e);
    }
    numSuccess.incrementAndGet();
    return Futures.immediateFuture(null);
  }

  @Override
  public ServerCapabilities getServerCapabilities() {
    var cacheCapabilities =
        CacheCapabilities.newBuilder()
            .setActionCacheUpdateCapabilities(
                ActionCacheUpdateCapabilities.newBuilder().setUpdateEnabled(true).build())
            .setSymlinkAbsolutePathStrategy(SymlinkAbsolutePathStrategy.Value.ALLOWED)
            .build();
    return ServerCapabilities.newBuilder().setCacheCapabilities(cacheCapabilities).build();
  }

  @Override
  public ListenableFuture<String> getAuthority() {
    return Futures.immediateFuture("");
  }

  @Override
  public ListenableFuture<ActionResult> downloadActionResult(
      RemoteActionExecutionContext context,
      ActionKey actionKey,
      boolean inlineOutErr,
      Set<String> inlineOutputFiles) {
    ActionResult actionResult = ac.get(actionKey);
    return Futures.immediateFuture(actionResult);
  }

  @Override
  public ListenableFuture<Void> uploadActionResult(
      RemoteActionExecutionContext context, ActionKey actionKey, ActionResult actionResult) {
    ac.put(actionKey, actionResult);
    return Futures.immediateFuture(null);
  }

  @Override
  public ListenableFuture<Void> uploadBlob(
      RemoteActionExecutionContext context, Digest digest, Blob blob) {
    try (blob) {
      cas.put(digest, blob.get().readAllBytes());
    } catch (IOException e) {
      return Futures.immediateFailedFuture(e);
    }
    return Futures.immediateFuture(null);
  }

  @Override
  public ListenableFuture<ImmutableSet<Digest>> findMissingDigests(
      RemoteActionExecutionContext context, Iterable<Digest> digests) {
    return executorService.submit(
        () -> {
          ImmutableSet.Builder<Digest> missingBuilder = ImmutableSet.builder();
          for (Digest digest : digests) {
            numFindMissingDigests
                .computeIfAbsent(digest, (key) -> new AtomicInteger(0))
                .incrementAndGet();
            if (!cas.containsKey(digest)) {
              missingBuilder.add(digest);
            }
          }
          return missingBuilder.build();
        });
  }

  @Override
  public void close() {
    cas.clear();
    ac.clear();
  }
}
