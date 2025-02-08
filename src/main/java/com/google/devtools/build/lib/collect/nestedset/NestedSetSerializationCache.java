// Copyright 2021 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.collect.nestedset;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.immediateWriteStatus;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint;
import com.google.devtools.build.lib.skyframe.serialization.PutOperation;
import com.google.devtools.build.lib.skyframe.serialization.SerializationConstants;
import javax.annotation.Nullable;

/**
 * A bidirectional, in-memory, weak cache for fingerprint ⇔ {@link NestedSet} associations.
 *
 * <p>For use by {@link NestedSetStore} to minimize work during {@link NestedSet} (de)serialization.
 *
 * <p>The cache supports the possibility of semantically different arrays having the same serialized
 * representation. For this reason, a context object is included in the key for the fingerprint ⇒
 * array mapping. This object should encapsulate all additional context necessary to deserialize a
 * {@link NestedSet} element. The array ⇒ fingerprint mapping, on the other hand, is expected to be
 * deterministic.
 */
class NestedSetSerializationCache {

  /**
   * Fingerprint to array cache.
   *
   * <p>The values in this cache are always {@code Object[]} or {@code ListenableFuture<Object[]>}.
   * We avoid a common wrapper object both for memory efficiency and because our cache eviction
   * policy is based on value GC, and wrapper objects would defeat that.
   *
   * <p>While a fetch for the contents is outstanding, the value in the cache will be a {@link
   * ListenableFuture}. When it is resolved, it is replaced with the unwrapped {@code Object[]}.
   * This is done because if the array is a transitive member, its future may be GC'd, and we want
   * entries to stay in this cache while the contents are still live.
   */
  private final Cache<FingerprintKey, Object> fingerprintToContents =
      Caffeine.newBuilder()
          .initialCapacity(SerializationConstants.DESERIALIZATION_POOL_SIZE)
          .weakValues()
          .build();

  /** {@code Object[]} contents to fingerprint. Maintained for fast fingerprinting. */
  private final Cache<Object[], PutOperation> contentsToFingerprint =
      Caffeine.newBuilder()
          .initialCapacity(SerializationConstants.DESERIALIZATION_POOL_SIZE)
          .weakKeys()
          .build();

  private final BugReporter bugReporter;

  NestedSetSerializationCache(BugReporter bugReporter) {
    this.bugReporter = bugReporter;
  }

  /**
   * Returns contents (an {@code Object[]} or a {@code ListenableFuture<Object[]>}) for the {@link
   * NestedSet} associated with the given fingerprint if there was already one. Otherwise associates
   * {@code future} with {@code fingerprint} and returns {@code null}.
   *
   * <p>Upon a {@code null} return, the caller should ensure that the given future is eventually set
   * with the fetched contents.
   *
   * <p>Upon a non-{@code null} return, the caller should discard the given future in favor of the
   * returned contents, blocking for them if the return value is itself a future.
   *
   * @param fingerprint the fingerprint of the desired {@link NestedSet} contents
   * @param context the context needed to deterministically deserialize the contents associated with
   *     {@code fingerprint}
   * @param future a freshly created {@link SettableFuture}
   */
  @Nullable
  Object putFutureIfAbsent(
      PackedFingerprint fingerprint, SettableFuture<Object[]> future, Object context) {
    checkArgument(!future.isDone(), "Must pass a fresh future: %s", future);
    Object existing =
        fingerprintToContents.asMap().putIfAbsent(new FingerprintKey(fingerprint, context), future);
    if (existing != null) {
      return existing;
    }
    // This is the first request of this fingerprint.
    unwrapWhenDone(fingerprint, future, context);
    return null;
  }

  /**
   * Registers a {@link FutureCallback} that associates the provided fingerprint and the contents of
   * the future, when it completes.
   */
  private void unwrapWhenDone(
      PackedFingerprint fingerprint, ListenableFuture<Object[]> futureContents, Object context) {
    Futures.addCallback(
        futureContents,
        new FutureCallback<Object[]>() {
          @Override
          public void onSuccess(Object[] contents) {
            // Store a PutOperation so that we can skip fingerprinting this array and writing it to
            // storage (it's already there - we just fetched it). Also replace the cached future
            // with the unwrapped contents, since the future may be GC'd. If there was a call to
            // putIfAbsent with this fingerprint while the future was pending, we may overwrite a
            // fingerprint ⇒ array mapping, but this is fine since both arrays have the same
            // contents. In this case, it would be nice to also complete the other array's write
            // future, but the semantics of SettableFuture makes this difficult (set after setFuture
            // has no effect).
            var unused =
                putIfAbsent(
                    contents, new PutOperation(fingerprint, immediateWriteStatus()), context);
          }

          @Override
          public void onFailure(Throwable t) {
            // Failure to fetch the NestedSet contents is unexpected, but the failed future can be
            // stored as the NestedSet children. This way the exception is only propagated if the
            // NestedSet is consumed (unrolled).
            bugReporter.sendNonFatalBugReport(t);
          }
        },
        directExecutor());
  }

  /**
   * Retrieves the fingerprint associated with the given {@link NestedSet} contents, or {@code null}
   * if the given contents are not known.
   */
  @Nullable
  PutOperation fingerprintForContents(Object[] contents) {
    return contentsToFingerprint.getIfPresent(contents);
  }

  /**
   * Ensures that a fingerprint ⟺ contents association is cached in both directions.
   *
   * <p>If the given fingerprint and array are already <em>fully</em> cached, returns the existing
   * {@link PutOperation}. Otherwise returns {@code null}.
   *
   * <p>If the given fingerprint is only <em>partially</em> cached (meaning that {@link
   * #putFutureIfAbsent} has been called but the associated future has not yet completed), then the
   * cached future is overwritten in favor of the actual contents.
   */
  @Nullable
  PutOperation putIfAbsent(Object[] contents, PutOperation result, Object context) {
    PutOperation existingResult = contentsToFingerprint.asMap().putIfAbsent(contents, result);
    if (existingResult != null) {
      return existingResult;
    }
    fingerprintToContents.put(new FingerprintKey(result.fingerprint(), context), contents);
    return null;
  }

  record FingerprintKey(PackedFingerprint fingerprint, Object context) {}
}
