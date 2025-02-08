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

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint.getFingerprintForTesting;
import static com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.immediateWriteStatus;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.common.testing.GcFinalization;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore.MissingFingerprintValueException;
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint;
import com.google.devtools.build.lib.skyframe.serialization.PutOperation;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.SettableWriteStatus;
import java.lang.ref.WeakReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link NestedSetSerializationCache}. */
@RunWith(JUnit4.class)
public final class NestedSetSerializationCacheTest {

  private static final Object DEFAULT_CONTEXT = new Object();

  private final NestedSetSerializationCache cache =
      new NestedSetSerializationCache(BugReporter.defaultInstance());

  @Test
  public void putFutureIfAbsent_newFingerprint_returnsNull() {
    PackedFingerprint fingerprint1 = getFingerprintForTesting("abc");
    PackedFingerprint fingerprint2 = getFingerprintForTesting("xyz");
    SettableFuture<Object[]> future1 = SettableFuture.create();
    SettableFuture<Object[]> future2 = SettableFuture.create();

    assertThat(cache.putFutureIfAbsent(fingerprint1, future1, DEFAULT_CONTEXT)).isNull();
    assertThat(cache.putFutureIfAbsent(fingerprint2, future2, DEFAULT_CONTEXT)).isNull();
  }

  @Test
  public void putFutureIfAbsent_existingFingerprint_returnsExistingFuture() {
    PackedFingerprint fingerprint = getFingerprintForTesting("abc");
    SettableFuture<Object[]> future = SettableFuture.create();

    assertThat(cache.putFutureIfAbsent(fingerprint, future, DEFAULT_CONTEXT)).isNull();
    assertThat(cache.putFutureIfAbsent(fingerprint, SettableFuture.create(), DEFAULT_CONTEXT))
        .isSameInstanceAs(future);
    assertThat(cache.putFutureIfAbsent(fingerprint, SettableFuture.create(), DEFAULT_CONTEXT))
        .isSameInstanceAs(future);
  }

  @Test
  public void putFutureIfAbsent_rejectsAlreadyDoneFuture() {
    PackedFingerprint fingerprint = getFingerprintForTesting("abc");
    SettableFuture<Object[]> future = SettableFuture.create();
    future.set(new Object[0]);

    assertThrows(
        IllegalArgumentException.class,
        () -> cache.putFutureIfAbsent(fingerprint, future, DEFAULT_CONTEXT));
  }

  @Test
  public void putFutureIfAbsent_futureCompletes_unwrapsContents() {
    PackedFingerprint fingerprint = getFingerprintForTesting("abc");
    SettableFuture<Object[]> future1 = SettableFuture.create();
    SettableFuture<Object[]> future2 = SettableFuture.create();
    Object[] contents = new Object[0];

    var unused = cache.putFutureIfAbsent(fingerprint, future1, DEFAULT_CONTEXT);
    future1.set(contents);

    assertThat(cache.putFutureIfAbsent(fingerprint, future2, DEFAULT_CONTEXT))
        .isSameInstanceAs(contents);
  }

  @Test
  public void putFutureIfAbsent_futureCompletes_cachesFingerprint() {
    PackedFingerprint fingerprint = getFingerprintForTesting("abc");
    SettableFuture<Object[]> future = SettableFuture.create();
    Object[] contents = new Object[0];

    var unused = cache.putFutureIfAbsent(fingerprint, future, DEFAULT_CONTEXT);
    future.set(contents);

    PutOperation result = cache.fingerprintForContents(contents);
    assertThat(result.fingerprint()).isEqualTo(fingerprint);
    assertThat(result.writeStatus().isDone()).isTrue();
  }

  @Test
  public void putFutureIfAbsent_futureFails_notifiesBugReporter() {
    BugReporter mockBugReporter = mock(BugReporter.class);
    NestedSetSerializationCache cacheWithCustomBugReporter =
        new NestedSetSerializationCache(mockBugReporter);
    PackedFingerprint fingerprint = getFingerprintForTesting("abc");
    SettableFuture<Object[]> future = SettableFuture.create();
    Throwable e = new MissingFingerprintValueException(fingerprint);

    var unused = cacheWithCustomBugReporter.putFutureIfAbsent(fingerprint, future, DEFAULT_CONTEXT);
    future.setException(e);

    verify(mockBugReporter).sendNonFatalBugReport(e);
  }

  @Test
  public void putIfAbsent_newFingerprintAndContents_returnsNullAndCachesBothDirections() {
    PackedFingerprint fingerprint1 = getFingerprintForTesting("abc");
    PackedFingerprint fingerprint2 = getFingerprintForTesting("xyz");
    Object[] contents1 = new Object[] {"abc"};
    Object[] contents2 = new Object[] {"xyz"};
    PutOperation result1 = new PutOperation(fingerprint1, new SettableWriteStatus());
    PutOperation result2 = new PutOperation(fingerprint2, new SettableWriteStatus());

    assertThat(cache.putIfAbsent(contents1, result1, DEFAULT_CONTEXT)).isNull();
    assertThat(cache.putIfAbsent(contents2, result2, DEFAULT_CONTEXT)).isNull();
    assertThat(cache.fingerprintForContents(contents1)).isSameInstanceAs(result1);
    assertThat(cache.fingerprintForContents(contents2)).isSameInstanceAs(result2);
    assertThat(cache.putFutureIfAbsent(fingerprint1, SettableFuture.create(), DEFAULT_CONTEXT))
        .isSameInstanceAs(contents1);
    assertThat(cache.putFutureIfAbsent(fingerprint2, SettableFuture.create(), DEFAULT_CONTEXT))
        .isSameInstanceAs(contents2);
  }

  @Test
  public void putIfAbsent_existingFingerprintAndContents_returnsExistingResult() {
    PackedFingerprint fingerprint = getFingerprintForTesting("abc");
    Object[] contents = new Object[0];
    PutOperation result1 = new PutOperation(fingerprint, new SettableWriteStatus());
    PutOperation result2 = new PutOperation(fingerprint, new SettableWriteStatus());
    PutOperation result3 = new PutOperation(fingerprint, new SettableWriteStatus());

    assertThat(cache.putIfAbsent(contents, result1, DEFAULT_CONTEXT)).isNull();
    assertThat(cache.putIfAbsent(contents, result2, DEFAULT_CONTEXT)).isSameInstanceAs(result1);
    assertThat(cache.putIfAbsent(contents, result3, DEFAULT_CONTEXT)).isSameInstanceAs(result1);
  }

  @Test
  public void putIfAbsent_calledDuringPendingDeserialization_overwritesFuture() {
    PackedFingerprint fingerprint = getFingerprintForTesting("abc");
    SettableFuture<Object[]> future = SettableFuture.create();
    Object[] contents = new Object[0];
    PutOperation result = new PutOperation(fingerprint, new SettableWriteStatus());

    assertThat(cache.putFutureIfAbsent(fingerprint, future, DEFAULT_CONTEXT)).isNull();
    assertThat(cache.putIfAbsent(contents, result, DEFAULT_CONTEXT)).isNull();
    assertThat(cache.fingerprintForContents(contents)).isSameInstanceAs(result);
    assertThat(cache.putFutureIfAbsent(fingerprint, SettableFuture.create(), DEFAULT_CONTEXT))
        .isSameInstanceAs(contents);

    // After the future completes, the contents should still be cached (doesn't matter which array).
    Object[] deserializedContents = new Object[0];
    future.set(deserializedContents);
    assertThat(cache.putFutureIfAbsent(fingerprint, SettableFuture.create(), DEFAULT_CONTEXT))
        .isAnyOf(contents, deserializedContents);

    // Both arrays should have a PutOperation.
    PutOperation resultForDeserializedContents = cache.fingerprintForContents(deserializedContents);
    assertThat(resultForDeserializedContents.fingerprint()).isEqualTo(fingerprint);
    assertThat(resultForDeserializedContents.writeStatus().isDone()).isTrue();
    assertThat(cache.fingerprintForContents(contents)).isSameInstanceAs(result);
  }

  @Test
  public void putFutureIfAbsent_cacheEntriesHaveLifetimeOfContents() {
    PackedFingerprint fingerprint = getFingerprintForTesting("abc");
    SettableFuture<Object[]> future = SettableFuture.create();

    var unused = cache.putFutureIfAbsent(fingerprint, future, DEFAULT_CONTEXT);

    // Before completing, still cached while future in memory.
    GcFinalization.awaitFullGc();
    assertThat(cache.putFutureIfAbsent(fingerprint, SettableFuture.create(), DEFAULT_CONTEXT))
        .isSameInstanceAs(future);

    // After completing, still cached while contents in memory even if future is gone.
    WeakReference<SettableFuture<Object[]>> futureRef = new WeakReference<>(future);
    Object[] contents = new Object[0];
    future.set(contents);
    future = null;
    GcFinalization.awaitClear(futureRef);
    assertThat(cache.putFutureIfAbsent(fingerprint, SettableFuture.create(), DEFAULT_CONTEXT))
        .isSameInstanceAs(contents);
    PutOperation result = cache.fingerprintForContents(contents);
    assertThat(result.fingerprint()).isEqualTo(fingerprint);
    assertThat(result.writeStatus().isDone()).isTrue();

    // Cleared after references are gone, and the cycle of putFutureIfAbsent starts over.
    WeakReference<Object[]> contentsRef = new WeakReference<>(contents);
    contents = null;
    GcFinalization.awaitClear(contentsRef);
    assertThat(cache.putFutureIfAbsent(fingerprint, SettableFuture.create(), DEFAULT_CONTEXT))
        .isNull();
  }

  @Test
  public void putIfAbsent_cacheEntriesHaveLifetimeOfContents() {
    PackedFingerprint fingerprint = getFingerprintForTesting("abc");
    Object[] contents = new Object[0];
    PutOperation result = new PutOperation(fingerprint, immediateWriteStatus());

    var unused = cache.putIfAbsent(contents, result, DEFAULT_CONTEXT);

    // Still cached while in memory.
    GcFinalization.awaitFullGc();
    assertThat(cache.fingerprintForContents(contents)).isSameInstanceAs(result);
    assertThat(cache.putFutureIfAbsent(fingerprint, SettableFuture.create(), DEFAULT_CONTEXT))
        .isSameInstanceAs(contents);

    // Cleared after references are gone, and the cycle of putFutureIfAbsent starts over.
    WeakReference<Object[]> ref = new WeakReference<>(contents);
    contents = null;
    GcFinalization.awaitClear(ref);
    assertThat(cache.putFutureIfAbsent(fingerprint, SettableFuture.create(), DEFAULT_CONTEXT))
        .isNull();
  }

  @Test
  public void putFutureIfAbsent_usesContextToDistinguish() {
    PackedFingerprint fingerprint = getFingerprintForTesting("abc");
    String contextLower = "lower";
    String contextUpper = "UPPER";
    SettableFuture<Object[]> futureLower = SettableFuture.create();
    SettableFuture<Object[]> futureUpper = SettableFuture.create();

    assertThat(cache.putFutureIfAbsent(fingerprint, futureLower, contextLower)).isNull();
    assertThat(cache.putFutureIfAbsent(fingerprint, futureUpper, contextUpper)).isNull();
    assertThat(cache.putFutureIfAbsent(fingerprint, SettableFuture.create(), contextLower))
        .isSameInstanceAs(futureLower);
    assertThat(cache.putFutureIfAbsent(fingerprint, SettableFuture.create(), contextUpper))
        .isSameInstanceAs(futureUpper);

    Object[] contentsLower = new Object[] {"abc"};
    Object[] contentsUpper = new Object[] {"ABC"};
    futureLower.set(contentsLower);
    futureUpper.set(contentsUpper);

    PutOperation resultLower = cache.fingerprintForContents(contentsLower);
    PutOperation resultUpper = cache.fingerprintForContents(contentsUpper);
    assertThat(resultLower.fingerprint()).isEqualTo(fingerprint);
    assertThat(resultUpper.fingerprint()).isEqualTo(fingerprint);
    assertThat(resultLower.writeStatus().isDone()).isTrue();
    assertThat(resultUpper.writeStatus().isDone()).isTrue();
    assertThat(cache.putFutureIfAbsent(fingerprint, SettableFuture.create(), contextLower))
        .isSameInstanceAs(contentsLower);
    assertThat(cache.putFutureIfAbsent(fingerprint, SettableFuture.create(), contextUpper))
        .isSameInstanceAs(contentsUpper);
  }

  @Test
  public void putIfAbsent_usesContextToDistinguish() {
    PackedFingerprint fingerprint = getFingerprintForTesting("abc");
    String contextLower = "lower";
    String contextUpper = "UPPER";
    Object[] contentsLower = new Object[] {"abc"};
    Object[] contentsUpper = new Object[] {"ABC"};
    PutOperation resultLower1 = new PutOperation(fingerprint, new SettableWriteStatus());
    PutOperation resultUpper1 = new PutOperation(fingerprint, new SettableWriteStatus());
    PutOperation resultLower2 = new PutOperation(fingerprint, new SettableWriteStatus());
    PutOperation resultUpper2 = new PutOperation(fingerprint, new SettableWriteStatus());

    assertThat(cache.putIfAbsent(contentsLower, resultLower1, contextLower)).isNull();
    assertThat(cache.putIfAbsent(contentsUpper, resultUpper1, contextUpper)).isNull();
    assertThat(cache.putIfAbsent(contentsLower, resultLower2, contextLower))
        .isSameInstanceAs(resultLower1);
    assertThat(cache.putIfAbsent(contentsUpper, resultUpper2, contextUpper))
        .isSameInstanceAs(resultUpper1);

    assertThat(cache.fingerprintForContents(contentsLower)).isSameInstanceAs(resultLower1);
    assertThat(cache.fingerprintForContents(contentsUpper)).isSameInstanceAs(resultUpper1);
    assertThat(cache.putFutureIfAbsent(fingerprint, SettableFuture.create(), contextLower))
        .isSameInstanceAs(contentsLower);
    assertThat(cache.putFutureIfAbsent(fingerprint, SettableFuture.create(), contextUpper))
        .isSameInstanceAs(contentsUpper);
  }

  @Test
  public void contextComparedByValueEquality() {
    class Context {
      @Override
      public int hashCode() {
        return 1;
      }

      @Override
      public boolean equals(Object o) {
        return o instanceof Context;
      }
    }
    PackedFingerprint fingerprint = getFingerprintForTesting("abc");
    SettableFuture<Object[]> future = SettableFuture.create();
    Object[] contents = new Object[0];
    PutOperation result1 = new PutOperation(fingerprint, new SettableWriteStatus());
    PutOperation result2 = new PutOperation(fingerprint, new SettableWriteStatus());

    assertThat(cache.putFutureIfAbsent(fingerprint, future, new Context())).isNull();
    assertThat(cache.putFutureIfAbsent(fingerprint, SettableFuture.create(), new Context()))
        .isSameInstanceAs(future);

    assertThat(cache.putIfAbsent(contents, result1, new Context())).isNull();
    assertThat(cache.putIfAbsent(contents, result2, new Context())).isSameInstanceAs(result1);
  }
}
