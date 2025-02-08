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
package com.google.devtools.build.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.truth.TruthJUnit.assume;
import static com.google.devtools.build.lib.testutil.EventIterableSubjectFactory.assertThatEvents;
import static com.google.devtools.build.skyframe.ErrorInfoSubjectFactory.assertThatErrorInfo;
import static com.google.devtools.build.skyframe.EvaluationResultSubjectFactory.assertThatEvaluationResult;
import static com.google.devtools.build.skyframe.GraphTester.CONCATENATE;
import static com.google.devtools.build.skyframe.GraphTester.COPY;
import static com.google.devtools.build.skyframe.GraphTester.NODE_TYPE;
import static com.google.devtools.build.skyframe.GraphTester.nonHermeticKey;
import static com.google.devtools.build.skyframe.GraphTester.skyKey;
import static java.util.Objects.requireNonNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.EventBus;
import com.google.common.testing.GcFinalization;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.events.DelegatingEventHandler;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventCollector;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.testutil.TestThread;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.skyframe.Differencer.DiffWithDelta.Delta;
import com.google.devtools.build.skyframe.GraphTester.NotComparableStringValue;
import com.google.devtools.build.skyframe.GraphTester.StringValue;
import com.google.devtools.build.skyframe.GraphTester.TestFunction;
import com.google.devtools.build.skyframe.GraphTester.ValueComputer;
import com.google.devtools.build.skyframe.MemoizingEvaluator.GraphTransformerForTesting;
import com.google.devtools.build.skyframe.NodeEntry.DirtyType;
import com.google.devtools.build.skyframe.NotifyingHelper.EventType;
import com.google.devtools.build.skyframe.NotifyingHelper.Listener;
import com.google.devtools.build.skyframe.NotifyingHelper.Order;
import com.google.devtools.build.skyframe.QueryableGraph.Reason;
import com.google.devtools.build.skyframe.SkyFunction.Reset;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.proto.GraphInconsistency.Inconsistency;
import com.google.errorprone.annotations.ForOverride;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

/** Tests for a {@link MemoizingEvaluator}. */
public abstract class MemoizingEvaluatorTest {

  protected MemoizingEvaluatorTester tester;
  protected EventCollector eventCollector;
  protected ExtendedEventHandler reporter;
  protected EmittedEventState emittedEventState;

  // Knobs that control the size / duration of larger tests.
  private static final int TEST_NODE_COUNT = 100;
  private static final int TESTED_NODES = 10;
  private static final int RUNS = 10;

  @Before
  public void initializeTester() {
    initializeTester(null);
    initializeReporter();
  }

  private void initializeTester(@Nullable TrackingProgressReceiver customProgressReceiver) {
    emittedEventState = new EmittedEventState();
    tester = new MemoizingEvaluatorTester();
    if (customProgressReceiver != null) {
      tester.setProgressReceiver(customProgressReceiver);
    }
    tester.initialize();
  }

  protected TrackingProgressReceiver createTrackingProgressReceiver(
      boolean checkEvaluationResults) {
    return new TrackingProgressReceiver(checkEvaluationResults);
  }

  @After
  public void assertNoTrackedErrors() {
    TrackingAwaiter.INSTANCE.assertNoErrors();
  }

  protected RecordingDifferencer getRecordingDifferencer() {
    return new SequencedRecordingDifferencer();
  }

  @ForOverride
  protected abstract MemoizingEvaluator getMemoizingEvaluator(
      ImmutableMap<SkyFunctionName, SkyFunction> functions,
      Differencer differencer,
      EvaluationProgressReceiver progressReceiver,
      GraphInconsistencyReceiver graphInconsistencyReceiver,
      EventFilter eventFilter);

  /** Invoked immediately before each call to {@link MemoizingEvaluator#evaluate}. */
  @ForOverride
  protected void beforeEvaluation() {}

  /**
   * Invoked immediately after {@link MemoizingEvaluator#evaluate} with the {@link EvaluationResult}
   * or {@code null} if an exception was thrown.
   */
  @ForOverride
  protected void afterEvaluation(@Nullable EvaluationResult<?> result, EvaluationContext context)
      throws InterruptedException {}

  @ForOverride
  protected boolean cyclesDetected() {
    return true;
  }

  @ForOverride
  protected boolean resetSupported() {
    return true;
  }

  // TODO(jhorvitz): Skip irrelevant test cases if this is false.
  @ForOverride
  protected boolean incrementalitySupported() {
    return true;
  }

  private void initializeReporter() {
    eventCollector = new EventCollector();
    reporter = new Reporter(new EventBus(), eventCollector);
    tester.resetPlayedEvents();
  }

  @Test
  public void smoke() throws Exception {
    tester.set("x", new StringValue("y"));
    StringValue value = (StringValue) tester.evalAndGet("x");
    assertThat(value.getValue()).isEqualTo("y");
  }

  @Test
  public void evaluateEmptySet() throws InterruptedException {
    tester.eval(false, new SkyKey[0]);
    tester.eval(true, new SkyKey[0]);
  }

  @Test
  public void injectGraphTransformer_transformedGraphUsedForInMemoryGraph() {
    assume().that(tester.evaluator).isInstanceOf(AbstractInMemoryMemoizingEvaluator.class);
    InMemoryGraph realGraph = tester.evaluator.getInMemoryGraph();
    InMemoryGraph mockGraph = mock(InMemoryGraph.class);

    tester.evaluator.injectGraphTransformerForTesting(
        new GraphTransformerForTesting() {
          @Override
          public InMemoryGraph transform(InMemoryGraph graph) {
            assertThat(graph).isSameInstanceAs(realGraph);
            return mockGraph;
          }

          @Override
          public ProcessableGraph transform(ProcessableGraph graph) {
            throw new AssertionError(graph);
          }
        });

    assertThat(tester.evaluator.getInMemoryGraph()).isSameInstanceAs(mockGraph);
  }

  @Test
  public void injectGraphTransformer_transformedGraphUsedForEvaluation() throws Exception {
    Listener listener = mock(Listener.class);
    tester.evaluator.injectGraphTransformerForTesting(
        NotifyingHelper.makeNotifyingTransformer(listener));
    SkyKey key = skyKey("key");
    SkyValue val = new StringValue("val");
    tester.getOrCreate(key).setConstantValue(val);

    assertThat(tester.evalAndGet(/* keepGoing= */ false, key)).isEqualTo(val);

    verify(listener).accept(key, EventType.GET_BATCH, Order.BEFORE, Reason.PRE_OR_POST_EVALUATION);
  }

  @Test
  public void injectGraphTransformer_multipleTransformersAppliedInOrder() throws Exception {
    Listener inner = mock(Listener.class);
    Listener outer = mock(Listener.class);
    tester.evaluator.injectGraphTransformerForTesting(
        NotifyingHelper.makeNotifyingTransformer(inner));
    tester.evaluator.injectGraphTransformerForTesting(
        NotifyingHelper.makeNotifyingTransformer(outer));
    SkyKey key = skyKey("key");
    SkyValue val = new StringValue("val");
    tester.getOrCreate(key).setConstantValue(val);

    assertThat(tester.evalAndGet(/* keepGoing= */ false, key)).isEqualTo(val);

    InOrder inOrder = inOrder(inner, outer);
    inOrder.verify(outer).accept(key, EventType.SET_VALUE, Order.BEFORE, val);
    inOrder.verify(inner).accept(key, EventType.SET_VALUE, Order.BEFORE, val);
    inOrder.verify(inner).accept(key, EventType.SET_VALUE, Order.AFTER, val);
    inOrder.verify(outer).accept(key, EventType.SET_VALUE, Order.AFTER, val);
  }

  @Test
  public void invalidationWithNothingChanged() throws Exception {
    tester.set("x", new StringValue("y")).setWarning("fizzlepop");
    StringValue value = (StringValue) tester.evalAndGet("x");
    assertThat(value.getValue()).isEqualTo("y");
    assertThatEvents(eventCollector).containsExactly("fizzlepop");

    initializeReporter();
    tester.invalidate();
    value = (StringValue) tester.evalAndGet("x");
    assertThat(value.getValue()).isEqualTo("y");
  }

  @Test
  // Regression test for bug: "[skyframe-m1]: registerIfDone() crash".
  public void bubbleRace() throws Exception {
    // The top-level value declares dependencies on a "badValue" in error, and a "sleepyValue"
    // which is very slow. After "badValue" fails, the builder interrupts the "sleepyValue" and
    // attempts to re-run "top" for error bubbling. Make sure this doesn't cause a precondition
    // failure because "top" still has an outstanding dep ("sleepyValue").
    tester
        .getOrCreate("top")
        .setBuilder(
            (skyKey, env) -> {
              env.getValue(skyKey("sleepyValue"));
              try {
                env.getValueOrThrow(skyKey("badValue"), SomeErrorException.class);
              } catch (SomeErrorException e) {
                // In order to trigger this bug, we need to request a dep on an already computed
                // value.
                env.getValue(skyKey("otherValue1"));
              }
              if (!env.valuesMissing()) {
                throw new AssertionError("SleepyValue should always be unavailable");
              }
              return null;
            });
    tester
        .getOrCreate("sleepyValue")
        .setBuilder(
            (skyKey, env) -> {
              Thread.sleep(99999);
              throw new AssertionError("I should have been interrupted");
            });
    tester.getOrCreate("badValue").addDependency("otherValue1").setHasError(true);
    tester.getOrCreate("otherValue1").setConstantValue(new StringValue("otherVal1"));

    EvaluationResult<SkyValue> result = tester.eval(false, "top");
    assertThatEvaluationResult(result).hasSingletonErrorThat(skyKey("top"));
  }

  @Test
  public void testEnvProvidesTemporaryDirectDeps() throws Exception {
    AtomicInteger counter = new AtomicInteger();
    List<SkyKey> deps = Collections.synchronizedList(new ArrayList<>());
    SkyKey topKey = skyKey("top");
    SkyKey bottomKey = skyKey("bottom");
    SkyValue bottomValue = new StringValue("bottom");
    tester
        .getOrCreate(topKey)
        .setBuilder(
            (skyKey, env) -> {
              if (counter.getAndIncrement() > 0) {
                deps.addAll(env.getTemporaryDirectDeps().getDepGroup(0));
              } else {
                assertThat(env.getTemporaryDirectDeps().numGroups()).isEqualTo(0);
              }
              return env.getValue(bottomKey);
            });
    tester.getOrCreate(bottomKey).setConstantValue(bottomValue);
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ true, "top");
    assertThat(result.get(topKey)).isEqualTo(bottomValue);
    assertThat(deps).containsExactly(bottomKey);
  }

  @Test
  public void cachedErrorShutsDownThreadpool() throws Exception {
    // When a node throws an error on the first build,
    SkyKey cachedErrorKey = skyKey("error");
    tester.getOrCreate(cachedErrorKey).setHasError(true);
    assertThat(tester.evalAndGetError(/*keepGoing=*/ true, cachedErrorKey)).isNotNull();
    // And on the second build, it is requested as a dep,
    SkyKey topKey = skyKey("top");
    tester.getOrCreate(topKey).addDependency(cachedErrorKey).setComputedValue(CONCATENATE);
    // And another node throws an error, but waits to throw until the child error is thrown,
    SkyKey newErrorKey = skyKey("newError");
    tester
        .getOrCreate(newErrorKey)
        .setBuilder(
            new ChainedFunction.Builder()
                .setWaitForException(true)
                .setWaitToFinish(new CountDownLatch(0))
                .setValue(null)
                .build());
    // Then when evaluation happens,
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, newErrorKey, topKey);
    // The result has an error,
    assertThatEvaluationResult(result).hasError();
    // But the new error is not persisted to the graph, since the child error shut down evaluation.
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(newErrorKey).isNull();
  }

  @Test
  public void interruptBitCleared() {
    SkyKey interruptKey = skyKey("interrupt");
    tester.getOrCreate(interruptKey).setBuilder(INTERRUPT_BUILDER);
    assertThrows(
        InterruptedException.class, () -> tester.eval(/* keepGoing= */ true, interruptKey));
    assertThat(Thread.interrupted()).isFalse();
  }

  @Test
  public void crashAfterInterruptCrashes() {
    SkyKey failKey = skyKey("fail");
    SkyKey badInterruptkey = skyKey("bad-interrupt");
    // Given a SkyFunction implementation which is improperly coded to throw a runtime exception
    // when it is interrupted,
    CountDownLatch badInterruptStarted = new CountDownLatch(1);
    tester
        .getOrCreate(badInterruptkey)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) {
                badInterruptStarted.countDown();
                try {
                  Thread.sleep(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
                  throw new AssertionError("Shouldn't have slept so long");
                } catch (InterruptedException e) {
                  throw new RuntimeException("I don't like being woken up!", e);
                }
              }
            });
    // And another SkyFunction that waits for the first to start, and then throws,
    tester
        .getOrCreate(failKey)
        .setBuilder(
            new ChainedFunction(
                null,
                badInterruptStarted,
                null,
                /*waitForException=*/ false,
                null,
                ImmutableList.of()));

    // When it is interrupted during evaluation (here, caused by the failure of the throwing
    // SkyFunction during a no-keep-going evaluation), then the ParallelEvaluator#evaluate call
    // throws a RuntimeException e where e.getCause() is the RuntimeException thrown by that
    // SkyFunction.
    RuntimeException e =
        assertThrows(
            RuntimeException.class,
            () -> tester.eval(/*keepGoing=*/ false, badInterruptkey, failKey));
    assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("I don't like being woken up!");
  }

  @Test
  public void interruptAfterFailFails() throws Exception {
    SkyKey failKey = skyKey("fail");
    SkyKey interruptedKey = skyKey("interrupted");
    // Given a SkyFunction implementation that is properly coded to as not to throw a
    // runtime exception when it is interrupted,
    CountDownLatch interruptStarted = new CountDownLatch(1);
    tester
        .getOrCreate(interruptedKey)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
                interruptStarted.countDown();
                Thread.sleep(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
                throw new AssertionError("Shouldn't have slept so long");
              }
            });
    // And another SkyFunction that waits for the first to start, and then throws,
    tester
        .getOrCreate(failKey)
        .setBuilder(
            new ChainedFunction(
                null,
                interruptStarted,
                null,
                /*waitForException=*/ false,
                null,
                ImmutableList.of()));

    // When it is interrupted during evaluation (here, caused by the failure of a sibling node
    // during a no-keep-going evaluation),
    EvaluationResult<StringValue> result =
        tester.eval(/*keepGoing=*/ false, interruptedKey, failKey);
    // Then the ParallelEvaluator#evaluate call returns an EvaluationResult that has no error for
    // the interrupted SkyFunction.
    assertWithMessage(result.toString()).that(result.hasError()).isTrue();
    assertWithMessage(result.toString()).that(result.getError(failKey)).isNotNull();
    assertWithMessage(result.toString()).that(result.getError(interruptedKey)).isNull();
  }

  @Test
  public void deleteValues() throws Exception {
    tester
        .getOrCreate("top")
        .setComputedValue(CONCATENATE)
        .addDependency("d1")
        .addDependency("d2")
        .addDependency("d3");
    tester.set("d1", new StringValue("1"));
    StringValue d2 = new StringValue("2");
    tester.set("d2", d2);
    StringValue d3 = new StringValue("3");
    tester.set("d3", d3);
    tester.eval(true, "top");

    tester.delete("d1");
    tester.eval(true, "d3");

    assertThat(tester.getDirtyKeys()).isEmpty();
    assertThat(tester.getDeletedKeys()).isEqualTo(ImmutableSet.of(skyKey("d1"), skyKey("top")));
    assertThat(tester.getExistingValue("top")).isNull();
    assertThat(tester.getExistingValue("d1")).isNull();
    assertThat(tester.getExistingValue("d2")).isEqualTo(d2);
    assertThat(tester.getExistingValue("d3")).isEqualTo(d3);
  }

  @Test
  public void deleteOldNodesTest() throws Exception {
    SkyKey d2Key = nonHermeticKey("d2");
    tester
        .getOrCreate("top")
        .setComputedValue(CONCATENATE)
        .addDependency("d1")
        .addDependency(d2Key);
    tester.set("d1", new StringValue("one"));
    tester.set(d2Key, new StringValue("two"));
    tester.eval(true, "top");

    tester.set(d2Key, new StringValue("three"));
    tester.invalidate();
    tester.eval(true, d2Key);

    // The graph now contains the three above nodes (and ERROR_TRANSIENCE).
    assertThat(tester.evaluator.getValues().keySet())
        .containsExactly(skyKey("top"), skyKey("d1"), d2Key, ErrorTransienceValue.KEY);

    String[] noKeys = {};
    tester.evaluator.deleteDirty(2);
    tester.eval(true, noKeys);

    // The top node's value is dirty, but less than two generations old, so it wasn't deleted.
    assertThat(tester.evaluator.getValues().keySet())
        .containsExactly(skyKey("top"), skyKey("d1"), d2Key, ErrorTransienceValue.KEY);

    tester.evaluator.deleteDirty(2);
    tester.eval(true, noKeys);

    // The top node's value was dirty, and was two generations old, so it was deleted.
    assertThat(tester.evaluator.getValues().keySet())
        .containsExactly(skyKey("d1"), d2Key, ErrorTransienceValue.KEY);
  }

  @Test
  public void deleteDirtyCleanedValue() throws Exception {
    SkyKey leafKey = nonHermeticKey("leafKey");
    tester.getOrCreate(leafKey).setConstantValue(new StringValue("value"));
    SkyKey topKey = skyKey("topKey");
    tester.getOrCreate(topKey).addDependency(leafKey).setComputedValue(CONCATENATE);

    assertThat(tester.evalAndGet(/*keepGoing=*/ false, topKey)).isEqualTo(new StringValue("value"));
    failBuildAndRemoveValue(leafKey);
    tester.evaluator.deleteDirty(0);
  }

  @Test
  public void deleteNonexistentValues() throws Exception {
    tester.getOrCreate("d1").setConstantValue(new StringValue("1"));
    tester.delete("d1");
    tester.delete("d2");
    tester.eval(true, "d1");
  }

  @Test
  public void signalValueEnqueued() throws Exception {
    tester
        .getOrCreate("top1")
        .setComputedValue(CONCATENATE)
        .addDependency("d1")
        .addDependency("d2");
    tester.getOrCreate("top2").setComputedValue(CONCATENATE).addDependency("d3");
    tester.getOrCreate("top3");
    assertThat(tester.getEnqueuedValues()).isEmpty();

    tester.set("d1", new StringValue("1"));
    tester.set("d2", new StringValue("2"));
    tester.set("d3", new StringValue("3"));
    tester.eval(true, "top1");
    assertThat(tester.getEnqueuedValues())
        .containsExactlyElementsIn(MemoizingEvaluatorTester.toSkyKeys("top1", "d1", "d2"));

    tester.eval(true, "top2");
    assertThat(tester.getEnqueuedValues())
        .containsExactlyElementsIn(
            MemoizingEvaluatorTester.toSkyKeys("top1", "d1", "d2", "top2", "d3"));
  }

  @Test
  public void warningViaMultiplePaths() throws Exception {
    tester.set("d1", new StringValue("d1")).setWarning("warn-d1");
    tester.set("d2", new StringValue("d2")).setWarning("warn-d2");
    tester.getOrCreate("top").setComputedValue(CONCATENATE).addDependency("d1").addDependency("d2");
    initializeReporter();
    tester.evalAndGet("top");
    assertThatEvents(eventCollector).containsExactly("warn-d1", "warn-d2");
  }

  @Test
  public void warningBeforeErrorOnFailFastBuild() throws Exception {
    tester.set("dep", new StringValue("dep")).setWarning("warn-dep");
    SkyKey topKey = skyKey("top");
    tester.getOrCreate(topKey).setHasError(true).addDependency("dep");
    for (int i = 0; i < 2; i++) {
      initializeReporter();
      EvaluationResult<StringValue> result = tester.eval(false, "top");
      assertThatEvaluationResult(result)
          .hasSingletonErrorThat(topKey)
          .hasExceptionThat()
          .hasMessageThat()
          .isEqualTo(topKey.toString());
      assertThatEvaluationResult(result)
          .hasSingletonErrorThat(topKey)
          .hasExceptionThat()
          .isInstanceOf(SomeErrorException.class);
      if (i == 0) {
        assertThatEvents(eventCollector).containsExactly("warn-dep");
      }
    }
  }

  @Test
  public void warningAndErrorOnFailFastBuild() throws Exception {
    SkyKey topKey = skyKey("top");
    tester.set(topKey, new StringValue("top")).setWarning("warning msg").setHasError(true);
    for (int i = 0; i < 2; i++) {
      initializeReporter();
      EvaluationResult<StringValue> result = tester.eval(false, "top");
      assertThatEvaluationResult(result)
          .hasSingletonErrorThat(topKey)
          .hasExceptionThat()
          .hasMessageThat()
          .isEqualTo(topKey.toString());
      assertThatEvaluationResult(result)
          .hasSingletonErrorThat(topKey)
          .hasExceptionThat()
          .isInstanceOf(SomeErrorException.class);
      if (i == 0) {
        assertThatEvents(eventCollector).containsExactly("warning msg");
      }
    }
  }

  @Test
  public void warningAndErrorOnFailFastBuildAfterKeepGoingBuild() throws Exception {
    SkyKey topKey = skyKey("top");
    tester.set(topKey, new StringValue("top")).setWarning("warning msg").setHasError(true);
    for (int i = 0; i < 2; i++) {
      initializeReporter();
      EvaluationResult<StringValue> result = tester.eval(i == 0, "top");
      assertThatEvaluationResult(result)
          .hasSingletonErrorThat(topKey)
          .hasExceptionThat()
          .hasMessageThat()
          .isEqualTo(topKey.toString());
      assertThatEvaluationResult(result)
          .hasSingletonErrorThat(topKey)
          .hasExceptionThat()
          .isInstanceOf(SomeErrorException.class);
      if (i == 0) {
        assertThatEvents(eventCollector).containsExactly("warning msg");
      }
    }
  }

  @Test
  public void twoTLTsOnOneWarningValue() throws Exception {
    tester.set("t1", new StringValue("t1")).addDependency("dep");
    tester.set("t2", new StringValue("t2")).addDependency("dep");
    tester.set("dep", new StringValue("dep")).setWarning("look both ways before crossing");
    initializeReporter();
    tester.eval(/* keepGoing= */ false, "t1", "t2");
    assertThatEvents(eventCollector).containsExactly("look both ways before crossing");
  }

  @Test
  public void errorValueDepOnWarningValue() throws Exception {
    tester.getOrCreate("error-value").setHasError(true).addDependency("warning-value");
    tester
        .set("warning-value", new StringValue("warning-value"))
        .setWarning("don't chew with your mouth open");

    initializeReporter();
    tester.evalAndGetError(/* keepGoing= */ true, "error-value");
    assertThatEvents(eventCollector).containsExactly("don't chew with your mouth open");
  }

  @Test
  public void progressMessageOnlyPrintedTheFirstTime() throws Exception {
    // Skyframe does not store progress messages. Here we only see the message on the first build.
    tester.set("x", new StringValue("y")).setProgress("just letting you know");

    StringValue value = (StringValue) tester.evalAndGet("x");
    assertThat(value.getValue()).isEqualTo("y");
    assertThatEvents(eventCollector).containsExactly("just letting you know");

    initializeReporter();
    value = (StringValue) tester.evalAndGet("x");
    assertThat(value.getValue()).isEqualTo("y");
    assertThatEvents(eventCollector).isEmpty();
  }

  @Test
  public void depMessageBeforeNodeMessageOrNodeValue() throws Exception {
    SkyKey top = skyKey("top");
    AtomicBoolean depWarningEmitted = new AtomicBoolean(false);
    injectGraphListenerForTesting(
        (key, type, order, context) -> {
          if (key.equals(top) && type == EventType.SET_VALUE) {
            assertThat(depWarningEmitted.get()).isTrue();
          }
        },
        /*deterministic=*/ false);
    String depWarning = "dep warning";
    Event topWarning = Event.warn("top warning");
    reporter =
        new DelegatingEventHandler(reporter) {
          @Override
          public void handle(Event e) {
            if (e.getMessage().equals(depWarning)) {
              depWarningEmitted.set(true);
            }
            if (e.equals(topWarning)) {
              assertThat(depWarningEmitted.get()).isTrue();
            }
            super.handle(e);
          }
        };
    SkyKey leaf = skyKey("leaf");
    tester.getOrCreate(leaf).setWarning(depWarning).setConstantValue(new StringValue("leaf"));
    tester
        .getOrCreate(top)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
                SkyValue depValue = env.getValue(leaf);
                if (depValue != null) {
                  // Default GraphTester implementation warns before requesting deps, which doesn't
                  // work
                  // for ordering assertions with memoizing evaluator subclsses that don't store
                  // events
                  // and instead just pass them through directly. By warning after the dep is done
                  // we
                  // avoid that issue.
                  env.getListener().handle(topWarning);
                }
                return depValue;
              }
            });
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, top);
    assertThatEvaluationResult(result).hasEntryThat(top).isEqualTo(new StringValue("leaf"));
    assertThatEvents(eventCollector).containsExactly(depWarning, topWarning.getMessage()).inOrder();
  }

  @Test
  public void invalidationWithChangeAndThenNothingChanged() throws Exception {
    SkyKey bKey = nonHermeticKey("b");
    tester.getOrCreate("a").addDependency(bKey).setComputedValue(COPY);
    tester.set(bKey, new StringValue("y"));
    StringValue original = (StringValue) tester.evalAndGet("a");
    assertThat(original.getValue()).isEqualTo("y");
    tester.set(bKey, new StringValue("z"));
    tester.invalidate();
    StringValue old = (StringValue) tester.evalAndGet("a");
    assertThat(old.getValue()).isEqualTo("z");
    tester.invalidate();
    StringValue current = (StringValue) tester.evalAndGet("a");
    assertThat(current).isEqualTo(old);
  }

  @Test
  public void noKeepGoingErrorAfterKeepGoingError() throws Exception {
    SkyKey topKey = nonHermeticKey("top");
    SkyKey errorKey = skyKey("error");
    tester.getOrCreate(errorKey).setHasError(true);
    tester.getOrCreate(topKey).addDependency(errorKey).setComputedValue(CONCATENATE);
    EvaluationResult<StringValue> result = tester.eval(/* keepGoing= */ true, topKey);
    assertThatEvaluationResult(result).hasSingletonErrorThat(topKey);
    tester.getOrCreate(topKey, /* markAsModified= */ true);
    tester.invalidate();
    result = tester.eval(/* keepGoing= */ false, topKey);
    assertThatEvaluationResult(result).hasSingletonErrorThat(topKey);
  }

  @Test
  public void transientErrorValueInvalidation() throws Exception {
    // Verify that invalidating errors causes all transient error values to be rerun.
    tester
        .getOrCreate("error-value")
        .setHasTransientError(true)
        .setProgress("just letting you know");

    tester.evalAndGetError(/*keepGoing=*/ true, "error-value");
    assertThatEvents(eventCollector).containsExactly("just letting you know");

    // Change the progress message.
    tester
        .getOrCreate("error-value")
        .setHasTransientError(true)
        .setProgress("letting you know more");

    // Without invalidating errors, we shouldn't show the new progress message.
    for (int i = 0; i < 2; i++) {
      initializeReporter();
      tester.evalAndGetError(/*keepGoing=*/ true, "error-value");
      assertThatEvents(eventCollector).isEmpty();
    }

    // When invalidating errors, we should show the new progress message.
    initializeReporter();
    tester.invalidateTransientErrors();
    tester.evalAndGetError(/*keepGoing=*/ true, "error-value");
    assertThatEvents(eventCollector).containsExactly("letting you know more");
  }

  @Test
  public void transientPruning() throws Exception {
    SkyKey leaf = nonHermeticKey("leaf");
    tester.getOrCreate("top").setHasTransientError(true).addDependency(leaf);
    tester.set(leaf, new StringValue("leafy"));
    tester.evalAndGetError(/*keepGoing=*/ true, "top");
    tester.getOrCreate(leaf, /*markAsModified=*/ true);
    tester.invalidate();
    tester.evalAndGetError(/*keepGoing=*/ true, "top");
  }

  @Test
  public void simpleDependency() throws Exception {
    tester.getOrCreate("ab").addDependency("a").setComputedValue(COPY);
    tester.set("a", new StringValue("me"));
    StringValue value = (StringValue) tester.evalAndGet("ab");
    assertThat(value.getValue()).isEqualTo("me");
  }

  @Test
  public void incrementalSimpleDependency() throws Exception {
    SkyKey aKey = nonHermeticKey("a");
    tester.getOrCreate("ab").addDependency(aKey).setComputedValue(COPY);
    tester.set(aKey, new StringValue("me"));
    tester.evalAndGet("ab");

    tester.set(aKey, new StringValue("other"));
    tester.invalidate();
    StringValue value = (StringValue) tester.evalAndGet("ab");
    assertThat(value.getValue()).isEqualTo("other");
  }

  @Test
  public void diamondDependency() throws Exception {
    SkyKey diamondBase = setupDiamondDependency();
    tester.set(diamondBase, new StringValue("me"));
    StringValue value = (StringValue) tester.evalAndGet("a");
    assertThat(value.getValue()).isEqualTo("meme");
  }

  @Test
  public void incrementalDiamondDependency() throws Exception {
    SkyKey diamondBase = setupDiamondDependency();
    tester.set(diamondBase, new StringValue("me"));
    tester.evalAndGet("a");

    tester.set(diamondBase, new StringValue("other"));
    tester.invalidate();
    StringValue value = (StringValue) tester.evalAndGet("a");
    assertThat(value.getValue()).isEqualTo("otherother");
  }

  private SkyKey setupDiamondDependency() {
    SkyKey diamondBase = nonHermeticKey("d");
    tester.getOrCreate("a").addDependency("b").addDependency("c").setComputedValue(CONCATENATE);
    tester.getOrCreate("b").addDependency(diamondBase).setComputedValue(COPY);
    tester.getOrCreate("c").addDependency(diamondBase).setComputedValue(COPY);
    return diamondBase;
  }

  // ParallelEvaluator notifies ValueProgressReceiver of already-built top-level values in error: we
  // built "top" and "mid" as top-level targets; "mid" contains an error. We make sure "mid" is
  // built as a dependency of "top" before enqueuing mid as a top-level target (by using a latch),
  // so that the top-level enqueuing finds that mid has already been built. The progress receiver
  // should be notified that mid has been built.
  @Test
  public void alreadyAnalyzedBadTarget() throws Exception {
    SkyKey mid = skyKey("zzmid");
    CountDownLatch valueSet = new CountDownLatch(1);
    injectGraphListenerForTesting(
        (key, type, order, context) -> {
          if (!key.equals(mid)) {
            return;
          }
          switch (type) {
            case ADD_REVERSE_DEP:
              if (context == null) {
                // Context is null when we are enqueuing this value as a top-level job.
                TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(valueSet, "value not set");
              }
              break;
            case SET_VALUE:
              valueSet.countDown();
              break;
            default:
              break;
          }
        },
        /* deterministic= */ true);
    SkyKey top = skyKey("aatop");
    tester.getOrCreate(top).addDependency(mid).setComputedValue(CONCATENATE);
    tester.getOrCreate(mid).setHasError(true);
    tester.eval(/* keepGoing= */ false, top, mid);
    assertThat(valueSet.getCount()).isEqualTo(0L);
    assertThat(tester.progressReceiver.evaluated).containsExactly(mid);
  }

  @Test
  public void receiverToldOfVerifiedValueDependingOnCycle() throws Exception {
    SkyKey leaf = nonHermeticKey("leaf");
    SkyKey cycle = skyKey("cycle");
    SkyKey top = skyKey("top");
    tester.set(leaf, new StringValue("leaf"));
    tester.getOrCreate(cycle).addDependency(cycle);
    tester.getOrCreate(top).addDependency(leaf).addDependency(cycle);
    tester.eval(/* keepGoing= */ true, top);
    assertThat(tester.progressReceiver.evaluated).containsExactly(leaf, top, cycle);
    tester.progressReceiver.clear();
    tester.getOrCreate(leaf, /* markAsModified= */ true);
    tester.invalidate();
    tester.eval(/* keepGoing= */ true, top);
    assertThat(tester.progressReceiver.evaluated).containsExactly(leaf, top);
  }

  @Test
  public void incrementalAddedDependency() throws Exception {
    SkyKey aKey = nonHermeticKey("a");
    SkyKey bKey = nonHermeticKey("b");
    tester.getOrCreate(aKey).addDependency(bKey).setComputedValue(CONCATENATE);
    tester.set(bKey, new StringValue("first"));
    tester.set("c", new StringValue("second"));
    tester.evalAndGet(/* keepGoing= */ false, aKey);

    tester.getOrCreate(aKey).addDependency("c");
    tester.set(bKey, new StringValue("now"));
    tester.invalidate();
    StringValue value = (StringValue) tester.evalAndGet(/* keepGoing= */ false, aKey);
    assertThat(value.getValue()).isEqualTo("nowsecond");
  }

  @Test
  public void manyValuesDependOnSingleValue() throws Exception {
    SkyKey leaf = nonHermeticKey("leaf");
    String[] values = new String[TEST_NODE_COUNT];
    for (int i = 0; i < values.length; i++) {
      values[i] = Integer.toString(i);
      tester.getOrCreate(values[i]).addDependency(leaf).setComputedValue(COPY);
    }
    tester.set(leaf, new StringValue("leaf"));

    EvaluationResult<StringValue> result = tester.eval(/* keepGoing= */ false, values);
    for (String value : values) {
      SkyValue actual = result.get(skyKey(value));
      assertThat(actual).isEqualTo(new StringValue("leaf"));
    }

    for (int j = 0; j < TESTED_NODES; j++) {
      tester.set(leaf, new StringValue("other" + j));
      tester.invalidate();
      result = tester.eval(/* keepGoing= */ false, values);
      for (int i = 0; i < values.length; i++) {
        SkyValue actual = result.get(skyKey(values[i]));
        assertWithMessage("Run " + j + ", value " + i)
            .that(actual)
            .isEqualTo(new StringValue("other" + j));
      }
    }
  }

  @Test
  public void singleValueDependsOnManyValues() throws Exception {
    SkyKey[] values = new SkyKey[TEST_NODE_COUNT];
    StringBuilder expected = new StringBuilder();
    for (int i = 0; i < values.length; i++) {
      String iString = Integer.toString(i);
      values[i] = nonHermeticKey(iString);
      tester.set(values[i], new StringValue(iString));
      expected.append(iString);
    }
    SkyKey rootKey = skyKey("root");
    TestFunction value = tester.getOrCreate(rootKey).setComputedValue(CONCATENATE);
    for (SkyKey skyKey : values) {
      value.addDependency(skyKey);
    }

    EvaluationResult<StringValue> result = tester.eval(/* keepGoing= */ false, rootKey);
    assertThat(result.get(rootKey)).isEqualTo(new StringValue(expected.toString()));

    for (int j = 0; j < 10; j++) {
      expected.setLength(0);
      for (int i = 0; i < values.length; i++) {
        String s = "other" + i + " " + j;
        tester.set(values[i], new StringValue(s));
        expected.append(s);
      }
      tester.invalidate();

      result = tester.eval(/* keepGoing= */ false, rootKey);
      assertThat(result.get(rootKey)).isEqualTo(new StringValue(expected.toString()));
    }
  }

  @Test
  public void twoRailLeftRightDependencies() throws Exception {
    SkyKey leaf = nonHermeticKey("leaf");
    String[] leftValues = new String[TEST_NODE_COUNT];
    String[] rightValues = new String[TEST_NODE_COUNT];
    for (int i = 0; i < leftValues.length; i++) {
      leftValues[i] = "left-" + i;
      rightValues[i] = "right-" + i;
      if (i == 0) {
        tester.getOrCreate(leftValues[i]).addDependency(leaf).setComputedValue(COPY);
        tester.getOrCreate(rightValues[i]).addDependency(leaf).setComputedValue(COPY);
      } else {
        tester
            .getOrCreate(leftValues[i])
            .addDependency(leftValues[i - 1])
            .addDependency(rightValues[i - 1])
            .setComputedValue(new PassThroughSelected(skyKey(leftValues[i - 1])));
        tester
            .getOrCreate(rightValues[i])
            .addDependency(leftValues[i - 1])
            .addDependency(rightValues[i - 1])
            .setComputedValue(new PassThroughSelected(skyKey(rightValues[i - 1])));
      }
    }
    tester.set(leaf, new StringValue("leaf"));

    String lastLeft = "left-" + (TEST_NODE_COUNT - 1);
    String lastRight = "right-" + (TEST_NODE_COUNT - 1);

    EvaluationResult<StringValue> result = tester.eval(/* keepGoing= */ false, lastLeft, lastRight);
    assertThat(result.get(skyKey(lastLeft))).isEqualTo(new StringValue("leaf"));
    assertThat(result.get(skyKey(lastRight))).isEqualTo(new StringValue("leaf"));

    for (int j = 0; j < TESTED_NODES; j++) {
      String value = "other" + j;
      tester.set(leaf, new StringValue(value));
      tester.invalidate();
      result = tester.eval(/* keepGoing= */ false, lastLeft, lastRight);
      assertThat(result.get(skyKey(lastLeft))).isEqualTo(new StringValue(value));
      assertThat(result.get(skyKey(lastRight))).isEqualTo(new StringValue(value));
    }
  }

  @Test
  public void noKeepGoingAfterKeepGoingCycle() throws Exception {
    initializeTester();
    SkyKey aKey = skyKey("a");
    SkyKey bKey = skyKey("b");
    SkyKey topKey = skyKey("top");
    SkyKey midKey = skyKey("mid");
    SkyKey goodKey = skyKey("good");
    StringValue goodValue = new StringValue("good");
    tester.set(goodKey, goodValue);
    tester.getOrCreate(topKey).addDependency(midKey);
    tester.getOrCreate(midKey).addDependency(aKey);
    tester.getOrCreate(aKey).addDependency(bKey);
    tester.getOrCreate(bKey).addDependency(aKey);
    EvaluationResult<StringValue> result = tester.eval(/* keepGoing= */ true, topKey, goodKey);
    assertThat(result.get(goodKey)).isEqualTo(goodValue);
    assertThat(result.get(topKey)).isNull();
    ErrorInfo errorInfo = result.getError(topKey);
    CycleInfo cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    if (cyclesDetected()) {
      assertThat(cycleInfo.getCycle()).containsExactly(aKey, bKey).inOrder();
      assertThat(cycleInfo.getPathToCycle()).containsExactly(topKey, midKey).inOrder();
    }

    tester.invalidate();
    result = tester.eval(/*keepGoing=*/ false, topKey, goodKey);
    assertThat(result.get(topKey)).isNull();
    errorInfo = result.getError(topKey);
    cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    if (cyclesDetected()) {
      assertThat(cycleInfo.getCycle()).containsExactly(aKey, bKey).inOrder();
      assertThat(cycleInfo.getPathToCycle()).containsExactly(topKey, midKey).inOrder();
    }
  }

  @Test
  public void keepGoingCycleAlreadyPresent() throws Exception {
    SkyKey selfEdge = skyKey("selfEdge");
    tester.getOrCreate(selfEdge).addDependency(selfEdge).setComputedValue(CONCATENATE);
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ true, selfEdge);
    assertThatEvaluationResult(result).hasError();
    CycleInfo cycleInfo = Iterables.getOnlyElement(result.getError(selfEdge).getCycleInfo());
    if (cyclesDetected()) {
      CycleInfoSubjectFactory.assertThat(cycleInfo).hasCycleThat().containsExactly(selfEdge);
      CycleInfoSubjectFactory.assertThat(cycleInfo).hasPathToCycleThat().isEmpty();
    }
    SkyKey parent = skyKey("parent");
    tester.getOrCreate(parent).addDependency(selfEdge).setComputedValue(CONCATENATE);
    EvaluationResult<StringValue> result2 = tester.eval(/*keepGoing=*/ true, parent);
    assertThatEvaluationResult(result).hasError();
    CycleInfo cycleInfo2 = Iterables.getOnlyElement(result2.getError(parent).getCycleInfo());
    if (cyclesDetected()) {
      CycleInfoSubjectFactory.assertThat(cycleInfo2).hasCycleThat().containsExactly(selfEdge);
      CycleInfoSubjectFactory.assertThat(cycleInfo2).hasPathToCycleThat().containsExactly(parent);
    }
  }

  private void changeCycle(boolean keepGoing) throws Exception {
    SkyKey aKey = skyKey("a");
    SkyKey bKey = nonHermeticKey("b");
    SkyKey topKey = skyKey("top");
    SkyKey midKey = skyKey("mid");
    tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(COPY);
    tester.getOrCreate(midKey).addDependency(aKey).setComputedValue(COPY);
    tester.getOrCreate(aKey).addDependency(bKey).setComputedValue(COPY);
    tester.getOrCreate(bKey).addDependency(aKey);
    EvaluationResult<StringValue> result = tester.eval(keepGoing, topKey);
    assertThat(result.get(topKey)).isNull();
    ErrorInfo errorInfo = result.getError(topKey);
    CycleInfo cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    if (cyclesDetected()) {
      assertThat(cycleInfo.getCycle()).containsExactly(aKey, bKey).inOrder();
      assertThat(cycleInfo.getPathToCycle()).containsExactly(topKey, midKey).inOrder();
    }

    tester.getOrCreate(bKey).removeDependency(aKey);
    tester.set(bKey, new StringValue("bValue"));
    tester.invalidate();
    result = tester.eval(keepGoing, topKey);
    assertThat(result.get(topKey)).isEqualTo(new StringValue("bValue"));
    assertThat(result.getError(topKey)).isNull();
  }

  @Test
  public void changeCycle_NoKeepGoing() throws Exception {
    changeCycle(false);
  }

  @Test
  public void changeCycle_KeepGoing() throws Exception {
    changeCycle(true);
  }

  /**
   * @see ParallelEvaluatorTest#cycleAboveIndependentCycle()
   */
  @Test
  public void cycleAboveIndependentCycle() throws Exception {
    makeGraphDeterministic();
    SkyKey aKey = skyKey("a");
    SkyKey bKey = skyKey("b");
    SkyKey cKey = nonHermeticKey("c");
    SkyKey leafKey = nonHermeticKey("leaf");
    // When aKey depends on leafKey and bKey,
    tester
        .getOrCreate(aKey)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
                env.getValuesAndExceptions(ImmutableList.of(leafKey, bKey));
                return null;
              }
            });
    // And bKey depends on cKey,
    tester.getOrCreate(bKey).addDependency(cKey);
    // And cKey depends on aKey and bKey in that order,
    tester.getOrCreate(cKey).addDependency(aKey).addDependency(bKey);
    // And leafKey is a leaf node,
    tester.set(leafKey, new StringValue("leafy"));
    // Then when we evaluate,
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ true, aKey);
    // aKey has an error,
    assertThat(result.get(aKey)).isNull();
    if (cyclesDetected()) {
      // And both cycles were found underneath aKey: the (aKey->bKey->cKey) cycle, and the
      // aKey->(bKey->cKey) cycle. This is because cKey depended on aKey and then bKey, so it pushed
      // them down on the stack in that order, so bKey was processed first. It found its cycle, then
      // popped off the stack, and then aKey was processed and found its cycle.
      assertThatEvaluationResult(result)
          .hasErrorEntryForKeyThat(aKey)
          .hasCycleInfoThat()
          .containsExactly(
              new CycleInfo(ImmutableList.of(aKey, bKey, cKey)),
              new CycleInfo(ImmutableList.of(aKey), ImmutableList.of(bKey, cKey)));
    } else {
      assertThatEvaluationResult(result)
          .hasErrorEntryForKeyThat(aKey)
          .hasCycleInfoThat()
          .hasSize(1);
    }
    // When leafKey is changed, so that aKey will be marked as NEEDS_REBUILDING,
    tester.set(leafKey, new StringValue("crunchy"));
    // And cKey is invalidated, so that cycle checking will have to explore the full graph,
    tester.getOrCreate(cKey, /*markAsModified=*/ true);
    tester.invalidate();
    // Then when we evaluate,
    EvaluationResult<StringValue> result2 = tester.eval(/*keepGoing=*/ true, aKey);
    // Things are just as before.
    assertThat(result2.get(aKey)).isNull();
    if (cyclesDetected()) {
      assertThatEvaluationResult(result)
          .hasErrorEntryForKeyThat(aKey)
          .hasCycleInfoThat()
          .containsExactly(
              new CycleInfo(ImmutableList.of(aKey, bKey, cKey)),
              new CycleInfo(ImmutableList.of(aKey), ImmutableList.of(bKey, cKey)));
    } else {
      assertThatEvaluationResult(result)
          .hasErrorEntryForKeyThat(aKey)
          .hasCycleInfoThat()
          .hasSize(1);
    }
  }

  /** Regression test: "crash in cycle checker with dirty values". */
  @Test
  public void cycleAndSelfEdgeWithDirtyValue() throws Exception {
    initializeTester();
    // The cycle detection algorithm non-deterministically traverses into children nodes, so
    // use explicit determinism.
    makeGraphDeterministic();
    SkyKey cycleKey1 = nonHermeticKey("ZcycleKey1");
    SkyKey cycleKey2 = skyKey("AcycleKey2");
    tester
        .getOrCreate(cycleKey1)
        .addDependency(cycleKey2)
        .addDependency(cycleKey1)
        .setComputedValue(CONCATENATE);
    tester.getOrCreate(cycleKey2).addDependency(cycleKey1).setComputedValue(COPY);
    EvaluationResult<StringValue> result = tester.eval(/* keepGoing= */ true, cycleKey1);
    assertThat(result.get(cycleKey1)).isNull();
    ErrorInfo errorInfo = result.getError(cycleKey1);
    CycleInfo cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    if (cyclesDetected()) {
      assertThat(cycleInfo.getCycle()).containsExactly(cycleKey1).inOrder();
      assertThat(cycleInfo.getPathToCycle()).isEmpty();
    }
    tester.getOrCreate(cycleKey1, /*markAsModified=*/ true);
    tester.invalidate();
    result = tester.eval(/*keepGoing=*/ true, cycleKey1, cycleKey2);
    assertThat(result.get(cycleKey1)).isNull();
    errorInfo = result.getError(cycleKey1);
    cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    if (cyclesDetected()) {
      assertThat(cycleInfo.getCycle()).containsExactly(cycleKey1).inOrder();
      assertThat(cycleInfo.getPathToCycle()).isEmpty();
    }
    cycleInfo =
        Iterables.getOnlyElement(
            tester.evaluator.getExistingErrorForTesting(cycleKey2).getCycleInfo());
    if (cyclesDetected()) {
      assertThat(cycleInfo.getCycle()).containsExactly(cycleKey1).inOrder();
      assertThat(cycleInfo.getPathToCycle()).containsExactly(cycleKey2).inOrder();
    }
  }

  @Test
  public void cycleAndSelfEdgeWithDirtyValueInSameGroup() throws Exception {
    makeGraphDeterministic();
    SkyKey cycleKey1 = skyKey("ZcycleKey1");
    SkyKey cycleKey2 = skyKey("AcycleKey2");
    tester.getOrCreate(cycleKey2).addDependency(cycleKey2).setComputedValue(CONCATENATE);
    tester
        .getOrCreate(cycleKey1)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
                // The order here is important -- 2 before 1.
                SkyframeLookupResult result =
                    env.getValuesAndExceptions(ImmutableList.of(cycleKey2, cycleKey1));
                Preconditions.checkState(env.valuesMissing(), result);
                return null;
              }
            });
    // Evaluate twice to make sure nothing strange happens with invalidation the second time.
    for (int i = 0; i < 2; i++) {
      EvaluationResult<SkyValue> result = tester.eval(/*keepGoing=*/ true, cycleKey1);
      assertThat(result.get(cycleKey1)).isNull();
      ErrorInfo errorInfo = result.getError(cycleKey1);
      CycleInfo cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
      if (cyclesDetected()) {
        assertThat(cycleInfo.getCycle()).containsExactly(cycleKey1).inOrder();
        assertThat(cycleInfo.getPathToCycle()).isEmpty();
      }
    }
  }

  /** Regression test: "crash in cycle checker with dirty values". */
  @Test
  public void cycleWithDirtyValue() throws Exception {
    SkyKey cycleKey1 = nonHermeticKey("cycleKey1");
    SkyKey cycleKey2 = skyKey("cycleKey2");
    tester.getOrCreate(cycleKey1).addDependency(cycleKey2).setComputedValue(COPY);
    tester.getOrCreate(cycleKey2).addDependency(cycleKey1).setComputedValue(COPY);
    EvaluationResult<StringValue> result = tester.eval(/* keepGoing= */ true, cycleKey1);
    assertThat(result.get(cycleKey1)).isNull();
    ErrorInfo errorInfo = result.getError(cycleKey1);
    CycleInfo cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    if (cyclesDetected()) {
      assertThat(cycleInfo.getCycle()).containsExactly(cycleKey1, cycleKey2).inOrder();
      assertThat(cycleInfo.getPathToCycle()).isEmpty();
    }
    tester.getOrCreate(cycleKey1, /*markAsModified=*/ true);
    tester.invalidate();
    result = tester.eval(/*keepGoing=*/ true, cycleKey1);
    assertThat(result.get(cycleKey1)).isNull();
    errorInfo = result.getError(cycleKey1);
    cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    if (cyclesDetected()) {
      assertThat(cycleInfo.getCycle()).containsExactly(cycleKey1, cycleKey2).inOrder();
      assertThat(cycleInfo.getPathToCycle()).isEmpty();
    }
  }

  /**
   * {@link ParallelEvaluator} can be configured to not store errors alongside recovered values.
   *
   * @param errorsStoredAlongsideValues true if we expect Skyframe to store the error for the cycle
   *     in ErrorInfo. If true, supportsTransientExceptions must be true as well.
   * @param supportsTransientExceptions true if we expect Skyframe to mark an ErrorInfo as transient
   *     for certain exception types.
   * @param useTransientError true if the test should set the {@link TestFunction} it creates to
   *     throw a transient error.
   */
  protected void parentOfCycleAndErrorInternal(
      boolean errorsStoredAlongsideValues,
      boolean supportsTransientExceptions,
      boolean useTransientError)
      throws Exception {
    initializeTester();
    if (errorsStoredAlongsideValues) {
      Preconditions.checkArgument(supportsTransientExceptions);
    }
    SkyKey cycleKey1 = skyKey("cycleKey1");
    SkyKey cycleKey2 = skyKey("cycleKey2");
    SkyKey mid = skyKey("mid");
    SkyKey errorKey = skyKey("errorKey");
    tester.getOrCreate(cycleKey1).addDependency(cycleKey2).setComputedValue(COPY);
    tester.getOrCreate(cycleKey2).addDependency(cycleKey1).setComputedValue(COPY);
    TestFunction errorFunction = tester.getOrCreate(errorKey);
    if (useTransientError) {
      errorFunction.setHasTransientError(true);
    } else {
      errorFunction.setHasError(true);
    }
    tester
        .getOrCreate(mid)
        .addErrorDependency(errorKey, new StringValue("recovered"))
        .setComputedValue(COPY);
    SkyKey top = skyKey("top");
    CountDownLatch topEvaluated = new CountDownLatch(2);
    tester
        .getOrCreate(top)
        .setBuilder(
            new ChainedFunction(
                topEvaluated,
                null,
                null,
                false,
                new StringValue("unused"),
                ImmutableList.of(mid, cycleKey1)));
    EvaluationResult<StringValue> evalResult = tester.eval(true, top);
    assertThatEvaluationResult(evalResult).hasError();
    ErrorInfo errorInfo = evalResult.getError(top);
    assertThat(topEvaluated.getCount()).isEqualTo(1);
    if (errorsStoredAlongsideValues) {
      if (useTransientError) {
        // The parent should be transitively transient, since it transitively depends on a transient
        // error.
        assertThat(errorInfo.isTransitivelyTransient()).isTrue();
      } else {
        assertThatErrorInfo(errorInfo).isNotTransient();
      }
      assertThat(errorInfo.getException())
          .hasMessageThat()
          .isEqualTo(NODE_TYPE.getName() + ":errorKey");
    } else {
      // When errors are not stored alongside values, transient errors that are recovered from do
      // not make the parent transient
      if (supportsTransientExceptions) {
        assertThatErrorInfo(errorInfo).isTransient();
        assertThatErrorInfo(errorInfo).hasExceptionThat().isNotNull();
      } else {
        assertThatErrorInfo(errorInfo).isNotTransient();
        assertThatErrorInfo(errorInfo).hasExceptionThat().isNull();
      }
    }
    if (cyclesDetected()) {
      assertThatErrorInfo(errorInfo)
          .hasCycleInfoThat()
          .containsExactly(
              new CycleInfo(ImmutableList.of(top), ImmutableList.of(cycleKey1, cycleKey2)));
    } else {
      assertThatErrorInfo(errorInfo).hasCycleInfoThat().hasSize(1);
    }
    // But the parent itself shouldn't have a direct dep on the special error transience node.
    assertThatEvaluationResult(evalResult)
        .hasDirectDepsInGraphThat(top)
        .doesNotContain(ErrorTransienceValue.KEY);
  }

  @Test
  public void parentOfCycleAndError() throws Exception {
    parentOfCycleAndErrorInternal(
        /*errorsStoredAlongsideValues=*/ true,
        /*supportsTransientExceptions=*/ true,
        /*useTransientError=*/ true);
  }

  /**
   * Regression test: IllegalStateException in BuildingState.isReady(). The ParallelEvaluator used
   * to assume during cycle-checking that all values had been built as fully as possible -- that
   * evaluation had not been interrupted. However, we also do cycle-checking in nokeep-going mode
   * when a value throws an error (possibly prematurely shutting down evaluation) but that error
   * then bubbles up into a cycle.
   *
   * <p>We want to achieve the following state: we are checking for a cycle; the value we examine
   * has not yet finished checking its children to see if they are dirty; but all children checked
   * so far have been unchanged. This value is "otherTop". We first build otherTop, then mark its
   * first child changed (without actually changing it), and then do a second build. On the second
   * build, we also build "top", which requests a cycle that depends on an error. We wait to signal
   * otherTop that its first child is done until the error throws and shuts down evaluation. The
   * error then bubbles up to the cycle, and so the bubbling is aborted. Finally, cycle checking
   * happens, and otherTop is examined, as desired.
   */
  @Test
  public void cycleAndErrorAndReady() throws Exception {
    // This value will not have finished building on the second build when the error is thrown.
    SkyKey otherTop = skyKey("otherTop");
    SkyKey errorKey = skyKey("error");
    // Is the graph state all set up and ready for the error to be thrown? The three values are
    // exceptionMarker, cycle2Key, and dep1 (via signaling otherTop).
    CountDownLatch valuesReady = new CountDownLatch(3);
    // Is evaluation being shut down? This is counted down by the exceptionMarker's builder, after
    // it has waited for the threadpool's exception latch to be released.
    CountDownLatch errorThrown = new CountDownLatch(1);
    // We don't do anything on the first build.
    AtomicBoolean secondBuild = new AtomicBoolean(false);
    injectGraphListenerForTesting(
        (key, type, order, context) -> {
          if (!secondBuild.get()) {
            return;
          }
          if (key.equals(otherTop) && type == EventType.SIGNAL) {
            // otherTop is being signaled that dep1 is done. Tell the error value that it is ready,
            // then wait until the error is thrown, so that otherTop's builder is not re-entered.
            valuesReady.countDown();
            TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(errorThrown, "error not thrown");
          }
        },
        /* deterministic= */ true);
    SkyKey dep1 = nonHermeticKey("dep1");
    tester.set(dep1, new StringValue("dep1"));
    SkyKey dep2 = skyKey("dep2");
    tester.set(dep2, new StringValue("dep2"));
    // otherTop should request the deps one at a time, so that it can be in the CHECK_DEPENDENCIES
    // state even after one dep is re-evaluated.
    tester
        .getOrCreate(otherTop)
        .setBuilder(
            (skyKey, env) -> {
              env.getValue(dep1);
              if (env.valuesMissing()) {
                return null;
              }
              env.getValue(dep2);
              return env.valuesMissing() ? null : new StringValue("otherTop");
            });
    // Prime the graph with otherTop, so we can dirty it next build.
    assertThat(tester.evalAndGet(/* keepGoing= */ false, otherTop))
        .isEqualTo(new StringValue("otherTop"));
    // Mark dep1 changed, so otherTop will be dirty and request re-evaluation of dep1.
    tester.getOrCreate(dep1, /* markAsModified= */ true);
    SkyKey topKey = skyKey("top");
    // Note that since DeterministicHelper alphabetizes reverse deps, it is important that
    // "cycle2" comes before "top".
    SkyKey cycle1Key = skyKey("cycle1");
    SkyKey cycle2Key = skyKey("cycle2");
    tester.getOrCreate(topKey).addDependency(cycle1Key).setComputedValue(CONCATENATE);
    tester
        .getOrCreate(cycle1Key)
        .addDependency(errorKey)
        .addDependency(cycle2Key)
        .setComputedValue(CONCATENATE);
    tester
        .getOrCreate(errorKey)
        .setBuilder(
            new ChainedFunction(
                /* notifyStart= */ null,
                /* waitToFinish= */ valuesReady,
                /* notifyFinish= */ null,
                /* waitForException= */ false,
                /* value= */ null,
                ImmutableList.of()));
    // Make sure cycle2Key has declared its dependence on cycle1Key before error throws.
    tester
        .getOrCreate(cycle2Key)
        .setBuilder(
            new ChainedFunction(
                /*notifyStart=*/ valuesReady,
                null,
                null,
                false,
                new StringValue("never returned"),
                ImmutableList.of(cycle1Key)));
    // Value that waits until an exception is thrown to finish building. We use it just to be
    // informed when the threadpool is shutting down.
    SkyKey exceptionMarker = skyKey("exceptionMarker");
    tester
        .getOrCreate(exceptionMarker)
        .setBuilder(
            new ChainedFunction(
                /*notifyStart=*/ valuesReady,
                /*waitToFinish=*/ new CountDownLatch(0),
                /*notifyFinish=*/ errorThrown,
                /*waitForException=*/ true,
                new StringValue("exception marker"),
                ImmutableList.of()));
    tester.invalidate();
    secondBuild.set(true);
    // otherTop must be first, since we check top-level values for cycles in the order in which
    // they appear here.
    EvaluationResult<StringValue> result =
        tester.eval(/*keepGoing=*/ false, otherTop, topKey, exceptionMarker);
    Iterable<CycleInfo> cycleInfos = result.getError(topKey).getCycleInfo();
    assertWithMessage(result.toString()).that(cycleInfos).isNotEmpty();
    CycleInfo cycleInfo = Iterables.getOnlyElement(cycleInfos);
    if (cyclesDetected()) {
      assertThat(result.errorMap().keySet()).containsExactly(topKey);
      assertThat(cycleInfo.getPathToCycle()).containsExactly(topKey);
      assertThat(cycleInfo.getCycle()).containsExactly(cycle1Key, cycle2Key);
    }
  }

  @Test
  public void breakCycle() throws Exception {
    SkyKey aKey = nonHermeticKey("a");
    SkyKey bKey = nonHermeticKey("b");
    // When aKey and bKey depend on each other,
    tester.getOrCreate(aKey).addDependency(bKey);
    tester.getOrCreate(bKey).addDependency(aKey);
    // And they are evaluated,
    EvaluationResult<StringValue> result = tester.eval(/* keepGoing= */ true, aKey, bKey);
    // Then the evaluation is in error,
    assertThatEvaluationResult(result).hasError();
    // And each node has the expected cycle.
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(aKey)
        .hasCycleInfoThat()
        .isNotEmpty();
    CycleInfo aCycleInfo = Iterables.getOnlyElement(result.getError(aKey).getCycleInfo());
    if (cyclesDetected()) {
      assertThat(aCycleInfo.getCycle()).containsExactly(aKey, bKey).inOrder();
      assertThat(aCycleInfo.getPathToCycle()).isEmpty();
    }
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(bKey)
        .hasCycleInfoThat()
        .isNotEmpty();
    CycleInfo bCycleInfo = Iterables.getOnlyElement(result.getError(bKey).getCycleInfo());
    if (cyclesDetected()) {
      assertThat(bCycleInfo.getCycle()).containsExactly(bKey, aKey).inOrder();
      assertThat(bCycleInfo.getPathToCycle()).isEmpty();
    }

    // When both dependencies are broken,
    tester.getOrCreate(bKey).removeDependency(aKey);
    tester.set(bKey, new StringValue("bValue"));
    tester.getOrCreate(aKey).removeDependency(bKey);
    tester.set(aKey, new StringValue("aValue"));
    tester.invalidate();
    // And the nodes are re-evaluated,
    result = tester.eval(/*keepGoing=*/ true, aKey, bKey);
    // Then evaluation is successful and the nodes have the expected values.
    assertThatEvaluationResult(result).hasEntryThat(aKey).isEqualTo(new StringValue("aValue"));
    assertThatEvaluationResult(result).hasEntryThat(bKey).isEqualTo(new StringValue("bValue"));
  }

  @Test
  public void nodeInvalidatedThenDoubleCycle() throws InterruptedException {
    makeGraphDeterministic();
    // When topKey depends on depKey, and both are top-level nodes in the graph,
    SkyKey topKey = nonHermeticKey("bKey");
    SkyKey depKey = nonHermeticKey("aKey");
    tester.getOrCreate(topKey).addDependency(depKey).setConstantValue(new StringValue("a"));
    tester.getOrCreate(depKey).setConstantValue(new StringValue("b"));
    // Then evaluation is as expected.
    EvaluationResult<StringValue> result1 = tester.eval(/* keepGoing= */ true, topKey, depKey);
    assertThatEvaluationResult(result1).hasEntryThat(topKey).isEqualTo(new StringValue("a"));
    assertThatEvaluationResult(result1).hasEntryThat(depKey).isEqualTo(new StringValue("b"));
    assertThatEvaluationResult(result1).hasNoError();
    // When both nodes acquire self-edges, with topKey still also depending on depKey, in the same
    // group,
    tester.getOrCreate(depKey, /* markAsModified= */ true).addDependency(depKey);
    tester
        .getOrCreate(topKey, /* markAsModified= */ true)
        .setConstantValue(null)
        .removeDependency(depKey)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
                // Order depKey first - makeGraphDeterministic() only makes the batch maps returned
                // by the graph deterministic, not the order of temporary direct deps. This makes
                // the order of deps match (alphabetized by the string representation).
                env.getValuesAndExceptions(ImmutableList.of(depKey, topKey));
                assertThat(env.valuesMissing()).isTrue();
                return null;
              }
            });
    tester.invalidate();
    // Then evaluation is as expected -- topKey has removed its dep on depKey (since depKey was not
    // done when topKey found its cycle), and both topKey and depKey have cycles.
    EvaluationResult<StringValue> result2 = tester.eval(/*keepGoing=*/ true, topKey, depKey);
    if (cyclesDetected()) {
      assertThatEvaluationResult(result2)
          .hasErrorEntryForKeyThat(topKey)
          .hasCycleInfoThat()
          .containsExactly(new CycleInfo(ImmutableList.of(topKey)));
      assertThatEvaluationResult(result2).hasDirectDepsInGraphThat(topKey).containsExactly(topKey);
      assertThatEvaluationResult(result2)
          .hasErrorEntryForKeyThat(depKey)
          .hasCycleInfoThat()
          .containsExactly(new CycleInfo(ImmutableList.of(depKey)));
    } else {
      assertThatEvaluationResult(result2)
          .hasErrorEntryForKeyThat(topKey)
          .hasCycleInfoThat()
          .hasSize(1);
      assertThatEvaluationResult(result2)
          .hasErrorEntryForKeyThat(depKey)
          .hasCycleInfoThat()
          .hasSize(1);
    }
    // When the nodes return to their original, error-free state,
    tester
        .getOrCreate(topKey, /*markAsModified=*/ true)
        .setBuilder(null)
        .addDependency(depKey)
        .setConstantValue(new StringValue("a"));
    tester.getOrCreate(depKey, /*markAsModified=*/ true).removeDependency(depKey);
    tester.invalidate();
    // Then evaluation is as expected.
    EvaluationResult<StringValue> result3 = tester.eval(/*keepGoing=*/ true, topKey, depKey);
    assertThatEvaluationResult(result3).hasEntryThat(topKey).isEqualTo(new StringValue("a"));
    assertThatEvaluationResult(result3).hasEntryThat(depKey).isEqualTo(new StringValue("b"));
    assertThatEvaluationResult(result3).hasNoError();
  }

  @SuppressWarnings("PreferJavaTimeOverload")
  @Test
  public void limitEvaluatorThreads() throws Exception {
    initializeTester();

    int numKeys = 10;
    Object lock = new Object();
    AtomicInteger inProgressCount = new AtomicInteger();
    int[] maxValue = {0};

    SkyKey topLevel = skyKey("toplevel");
    TestFunction topLevelBuilder = tester.getOrCreate(topLevel);
    for (int i = 0; i < numKeys; i++) {
      topLevelBuilder.addDependency("subKey" + i);
      tester
          .getOrCreate("subKey" + i)
          .setComputedValue(
              (deps, env) -> {
                int val = inProgressCount.incrementAndGet();
                synchronized (lock) {
                  if (val > maxValue[0]) {
                    maxValue[0] = val;
                  }
                }
                Uninterruptibles.sleepUninterruptibly(5, TimeUnit.SECONDS);

                inProgressCount.decrementAndGet();
                return new StringValue("abc");
              });
    }
    topLevelBuilder.setConstantValue(new StringValue("xyz"));

    EvaluationResult<StringValue> result =
        tester.eval(
            /* keepGoing= */ true,
            /* mergingSkyframeAnalysisExecutionPhases= */ false,
            /* numThreads= */ 5,
            topLevel);
    assertThat(result.hasError()).isFalse();
    assertThat(maxValue[0]).isEqualTo(5);
  }

  @Test
  public void nodeIsChangedWithoutBeingEvaluated() throws Exception {
    SkyKey buildFile = nonHermeticKey("buildfile");
    SkyKey top = skyKey("top");
    SkyKey dep = nonHermeticKey("dep");
    tester.set(buildFile, new StringValue("depend on dep"));
    StringValue depVal = new StringValue("this is dep");
    tester.set(dep, depVal);
    tester
        .getOrCreate(top)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env)
                  throws SkyFunctionException, InterruptedException {
                StringValue val = (StringValue) env.getValue(buildFile);
                if (env.valuesMissing()) {
                  return null;
                }
                if (val.getValue().equals("depend on dep")) {
                  StringValue result = (StringValue) env.getValue(dep);
                  return env.valuesMissing() ? null : result;
                }
                throw new GenericFunctionException(
                    new SomeErrorException("bork"), Transience.PERSISTENT);
              }
            });
    assertThat(tester.evalAndGet("top")).isEqualTo(depVal);
    StringValue newDepVal = new StringValue("this is new dep");
    tester.set(dep, newDepVal);
    tester.set(buildFile, new StringValue("don't depend on dep"));
    tester.invalidate();
    tester.eval(/*keepGoing=*/ false, top);
    tester.set(buildFile, new StringValue("depend on dep"));
    tester.invalidate();
    assertThat(tester.evalAndGet("top")).isEqualTo(newDepVal);
  }

  /**
   * Regression test: error on clearMaybeDirtyValue. We do an evaluation of topKey, which registers
   * dependencies on midKey and errorKey. midKey enqueues slowKey, and waits. errorKey throws an
   * error, which bubbles up to topKey. If topKey does not unregister its dependence on midKey, it
   * will have a dangling reference to midKey after unfinished values are cleaned from the graph.
   * Note that slowKey will wait until errorKey has thrown and the threadpool has caught the
   * exception before returning, so the Evaluator will already have stopped enqueuing new jobs, so
   * midKey is not evaluated.
   */
  @Test
  public void incompleteDirectDepsAreClearedBeforeInvalidation() throws Exception {
    CountDownLatch slowStart = new CountDownLatch(1);
    CountDownLatch errorFinish = new CountDownLatch(1);
    SkyKey errorKey = nonHermeticKey("error");
    tester
        .getOrCreate(errorKey)
        .setBuilder(
            new ChainedFunction(
                /*notifyStart=*/ null,
                /*waitToFinish=*/ slowStart,
                /*notifyFinish=*/ errorFinish,
                /*waitForException=*/ false,
                /*value=*/ null,
                /*deps=*/ ImmutableList.of()));
    SkyKey slowKey = skyKey("slow");
    tester
        .getOrCreate(slowKey)
        .setBuilder(
            new ChainedFunction(
                /*notifyStart=*/ slowStart,
                /*waitToFinish=*/ errorFinish,
                /*notifyFinish=*/ null,
                /*waitForException=*/ true,
                new StringValue("slow"),
                /*deps=*/ ImmutableList.of()));
    SkyKey midKey = skyKey("mid");
    tester.getOrCreate(midKey).addDependency(slowKey).setComputedValue(COPY);
    SkyKey topKey = skyKey("top");
    tester
        .getOrCreate(topKey)
        .addDependency(midKey)
        .addDependency(errorKey)
        .setComputedValue(CONCATENATE);
    // slowKey starts -> errorKey finishes, written to graph -> slowKey finishes & (Visitor aborts)
    // -> topKey builds.
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, topKey);
    assertThatEvaluationResult(result).hasSingletonErrorThat(topKey);
    // Make sure midKey didn't finish building.
    assertThat(tester.getExistingValue(midKey)).isNull();
    // Give slowKey a nice ordinary builder.
    tester
        .getOrCreate(slowKey, /*markAsModified=*/ false)
        .setBuilder(null)
        .setConstantValue(new StringValue("slow"));
    // Put midKey into the graph. It won't have a reverse dependence on topKey.
    tester.evalAndGet(/*keepGoing=*/ false, midKey);
    tester.differencer.invalidate(ImmutableList.of(errorKey));
    // topKey should not access midKey as if it were already registered as a dependency.
    tester.eval(/*keepGoing=*/ false, topKey);
  }

  /**
   * Regression test: error on clearMaybeDirtyValue. Same as the previous test, but the second
   * evaluation is keepGoing, which should cause an access of the children of topKey.
   */
  @Test
  public void incompleteDirectDepsAreClearedBeforeKeepGoing() throws Exception {
    initializeTester();
    CountDownLatch slowStart = new CountDownLatch(1);
    CountDownLatch errorFinish = new CountDownLatch(1);
    SkyKey errorKey = skyKey("error");
    tester
        .getOrCreate(errorKey)
        .setBuilder(
            new ChainedFunction(
                /*notifyStart=*/ null,
                /*waitToFinish=*/ slowStart,
                /*notifyFinish=*/ errorFinish,
                /*waitForException=*/ false,
                /*value=*/ null,
                /*deps=*/ ImmutableList.of()));
    SkyKey slowKey = skyKey("slow");
    tester
        .getOrCreate(slowKey)
        .setBuilder(
            new ChainedFunction(
                /*notifyStart=*/ slowStart,
                /*waitToFinish=*/ errorFinish,
                /*notifyFinish=*/ null,
                /*waitForException=*/ true,
                new StringValue("slow"),
                /*deps=*/ ImmutableList.of()));
    SkyKey midKey = skyKey("mid");
    tester.getOrCreate(midKey).addDependency(slowKey).setComputedValue(COPY);
    SkyKey topKey = skyKey("top");
    tester
        .getOrCreate(topKey)
        .addDependency(midKey)
        .addDependency(errorKey)
        .setComputedValue(CONCATENATE);
    // slowKey starts -> errorKey finishes, written to graph -> slowKey finishes & (Visitor aborts)
    // -> topKey builds.
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, topKey);
    assertThatEvaluationResult(result).hasSingletonErrorThat(topKey);
    // Make sure midKey didn't finish building.
    assertThat(tester.getExistingValue(midKey)).isNull();
    // Give slowKey a nice ordinary builder.
    tester
        .getOrCreate(slowKey, /*markAsModified=*/ false)
        .setBuilder(null)
        .setConstantValue(new StringValue("slow"));
    // Put midKey into the graph. It won't have a reverse dependence on topKey.
    tester.evalAndGet(/*keepGoing=*/ false, midKey);
    // topKey should not access midKey as if it were already registered as a dependency.
    // We don't invalidate errors, but because topKey wasn't actually written to the graph last
    // build, it should be rebuilt here.
    tester.eval(/*keepGoing=*/ true, topKey);
  }

  /**
   * Regression test: tests that pass before other build actions fail yield crash in non -k builds.
   */
  private void passThenFailToBuild(boolean successFirst) throws Exception {
    CountDownLatch blocker = new CountDownLatch(1);
    SkyKey successKey = skyKey("success");
    tester
        .getOrCreate(successKey)
        .setBuilder(
            new ChainedFunction(
                /*notifyStart=*/ null,
                /*waitToFinish=*/ null,
                /*notifyFinish=*/ blocker,
                /*waitForException=*/ false,
                new StringValue("yippee"),
                /*deps=*/ ImmutableList.of()));
    SkyKey slowFailKey = skyKey("slow_then_fail");
    tester
        .getOrCreate(slowFailKey)
        .setBuilder(
            new ChainedFunction(
                /*notifyStart=*/ null,
                /*waitToFinish=*/ blocker,
                /*notifyFinish=*/ null,
                /*waitForException=*/ false,
                /*value=*/ null,
                /*deps=*/ ImmutableList.of()));

    EvaluationResult<StringValue> result;
    if (successFirst) {
      result = tester.eval(/*keepGoing=*/ false, successKey, slowFailKey);
    } else {
      result = tester.eval(/*keepGoing=*/ false, slowFailKey, successKey);
    }
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(slowFailKey);
    assertThat(result.values()).containsExactly(new StringValue("yippee"));
  }

  @Test
  public void passThenFailToBuild() throws Exception {
    passThenFailToBuild(true);
  }

  @Test
  public void passThenFailToBuildAlternateOrder() throws Exception {
    passThenFailToBuild(false);
  }

  @Test
  public void incompleteDirectDepsForDirtyValue() throws Exception {
    SkyKey topKey = nonHermeticKey("top");
    tester.set(topKey, new StringValue("initial"));
    // Put topKey into graph so it will be dirtied on next run.
    assertThat(tester.evalAndGet(/*keepGoing=*/ false, topKey))
        .isEqualTo(new StringValue("initial"));
    CountDownLatch slowStart = new CountDownLatch(1);
    CountDownLatch errorFinish = new CountDownLatch(1);
    SkyKey errorKey = skyKey("error");
    tester
        .getOrCreate(errorKey)
        .setBuilder(
            new ChainedFunction(
                /*notifyStart=*/ null,
                /*waitToFinish=*/ slowStart,
                /*notifyFinish=*/ errorFinish,
                /*waitForException=*/ false,
                /*value=*/ null,
                /*deps=*/ ImmutableList.of()));
    SkyKey slowKey = skyKey("slow");
    tester
        .getOrCreate(slowKey)
        .setBuilder(
            new ChainedFunction(
                /*notifyStart=*/ slowStart,
                /*waitToFinish=*/ errorFinish,
                /*notifyFinish=*/ null,
                /*waitForException=*/ true,
                new StringValue("slow"),
                /*deps=*/ ImmutableList.of()));
    SkyKey midKey = skyKey("mid");
    tester.getOrCreate(midKey).addDependency(slowKey).setComputedValue(COPY);
    tester.set(topKey, null);
    tester
        .getOrCreate(topKey)
        .addDependency(midKey)
        .addDependency(errorKey)
        .setComputedValue(CONCATENATE);
    tester.invalidate();
    // slowKey starts -> errorKey finishes, written to graph -> slowKey finishes & (Visitor aborts)
    // -> topKey builds.
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, topKey);
    assertThatEvaluationResult(result).hasSingletonErrorThat(topKey);
    // Make sure midKey didn't finish building.
    assertThat(tester.getExistingValue(midKey)).isNull();
    // Give slowKey a nice ordinary builder.
    tester
        .getOrCreate(slowKey, /*markAsModified=*/ false)
        .setBuilder(null)
        .setConstantValue(new StringValue("slow"));
    // Put midKey into the graph. It won't have a reverse dependence on topKey.
    tester.evalAndGet(/*keepGoing=*/ false, midKey);
    // topKey should not access midKey as if it were already registered as a dependency.
    // We don't invalidate errors, but since topKey wasn't actually written to the graph before, it
    // will be rebuilt.
    tester.eval(/*keepGoing=*/ true, topKey);
  }

  @Test
  public void continueWithErrorDep() throws Exception {
    SkyKey afterKey = nonHermeticKey("after");
    SkyKey errorKey = skyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    tester.set(afterKey, new StringValue("after"));
    SkyKey parentKey = skyKey("parent");
    tester
        .getOrCreate(parentKey)
        .addErrorDependency(errorKey, new StringValue("recovered"))
        .setComputedValue(CONCATENATE)
        .addDependency(afterKey);
    EvaluationResult<StringValue> result = tester.eval(/* keepGoing= */ true, parentKey);
    assertThat(result.errorMap()).isEmpty();
    assertThat(result.get(parentKey).getValue()).isEqualTo("recoveredafter");
    tester.set(afterKey, new StringValue("before"));
    tester.invalidate();
    result = tester.eval(/*keepGoing=*/ true, parentKey);
    assertThat(result.errorMap()).isEmpty();
    assertThat(result.get(parentKey).getValue()).isEqualTo("recoveredbefore");
  }

  @Test
  public void continueWithErrorDepTurnedGood() throws Exception {
    SkyKey errorKey = nonHermeticKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    tester.set("after", new StringValue("after"));
    SkyKey parentKey = skyKey("parent");
    tester
        .getOrCreate(parentKey)
        .addErrorDependency(errorKey, new StringValue("recovered"))
        .setComputedValue(CONCATENATE)
        .addDependency("after");
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ true, parentKey);
    assertThat(result.errorMap()).isEmpty();
    assertThat(result.get(parentKey).getValue()).isEqualTo("recoveredafter");
    tester.set(errorKey, new StringValue("reformed")).setHasError(false);
    tester.invalidate();
    result = tester.eval(/*keepGoing=*/ true, parentKey);
    assertThat(result.errorMap()).isEmpty();
    assertThat(result.get(parentKey).getValue()).isEqualTo("reformedafter");
  }

  @Test
  public void errorDepAlreadyThereThenTurnedGood() throws Exception {
    SkyKey errorKey = nonHermeticKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    SkyKey parentKey = skyKey("parent");
    tester
        .getOrCreate(parentKey)
        .addErrorDependency(errorKey, new StringValue("recovered"))
        .setHasError(true);
    // Prime the graph by putting the error value in it beforehand.
    assertThat(tester.evalAndGetError(/*keepGoing=*/ true, errorKey)).isNotNull();
    // Request the parent.
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, parentKey);
    assertThatEvaluationResult(result).hasSingletonErrorThat(parentKey);
    // Change the error value to no longer throw.
    tester.set(errorKey, new StringValue("reformed")).setHasError(false);
    tester
        .getOrCreate(parentKey, /*markAsModified=*/ false)
        .setHasError(false)
        .setComputedValue(COPY);
    tester.differencer.invalidate(ImmutableList.of(errorKey));
    tester.invalidate();
    // Request the parent again. This time it should succeed.
    result = tester.eval(/*keepGoing=*/ false, parentKey);
    assertThat(result.errorMap()).isEmpty();
    assertThat(result.get(parentKey).getValue()).isEqualTo("reformed");
    // Confirm that the parent no longer depends on the error transience value -- make it
    // unbuildable again, but without invalidating it, and invalidate transient errors. The parent
    // should not be rebuilt.
    tester.getOrCreate(parentKey, /*markAsModified=*/ false).setHasError(true);
    tester.invalidateTransientErrors();
    result = tester.eval(/*keepGoing=*/ false, parentKey);
    assertThat(result.errorMap()).isEmpty();
    assertThat(result.get(parentKey).getValue()).isEqualTo("reformed");
  }

  /**
   * Regression test for 2014 bug: error transience value is registered before newly requested deps.
   * A value requests a child, gets it back immediately, and then throws, causing the error
   * transience value to be registered as a dep. The following build, the error is invalidated via
   * that child.
   */
  @Test
  public void doubleDepOnErrorTransienceValue() throws Exception {
    SkyKey leafKey = nonHermeticKey("leaf");
    tester.set(leafKey, new StringValue("leaf"));
    // Prime the graph by putting leaf in beforehand.
    assertThat(tester.evalAndGet(/*keepGoing=*/ false, leafKey)).isEqualTo(new StringValue("leaf"));
    SkyKey topKey = skyKey("top");
    tester.getOrCreate(topKey).addDependency(leafKey).setHasError(true);
    // Build top -- it has an error.
    tester.evalAndGetError(/*keepGoing=*/ true, topKey);
    // Invalidate top via leaf, and rebuild.
    tester.set(leafKey, new StringValue("leaf2"));
    tester.invalidate();
    tester.evalAndGetError(/*keepGoing=*/ true, topKey);
  }

  /** Regression test for crash bug. */
  @Test
  public void errorTransienceDepCleared() throws Exception {
    SkyKey top = skyKey("top");
    SkyKey leaf = nonHermeticKey("leaf");
    tester.set(leaf, new StringValue("leaf"));
    tester.getOrCreate(top).addDependency(leaf).setHasTransientError(true);
    EvaluationResult<StringValue> result = tester.eval(/* keepGoing= */ false, top);
    assertWithMessage(result.toString()).that(result.hasError()).isTrue();
    tester.getOrCreate(leaf, /* markAsModified= */ true);
    tester.invalidate();
    SkyKey irrelevant = skyKey("irrelevant");
    tester.set(irrelevant, new StringValue("irrelevant"));
    tester.eval(/* keepGoing= */ true, irrelevant);
    tester.invalidateTransientErrors();
    result = tester.eval(/*keepGoing=*/ true, top);
    assertWithMessage(result.toString()).that(result.hasError()).isTrue();
  }

  @Test
  public void incompleteValueAlreadyThereNotUsed() throws Exception {
    initializeTester();
    SkyKey errorKey = skyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    SkyKey midKey = skyKey("mid");
    tester
        .getOrCreate(midKey)
        .addErrorDependency(errorKey, new StringValue("recovered"))
        .setComputedValue(COPY);
    SkyKey parentKey = skyKey("parent");
    tester
        .getOrCreate(parentKey)
        .addErrorDependency(midKey, new StringValue("don't use this"))
        .setComputedValue(COPY);
    // Prime the graph by evaluating the mid-level value. It shouldn't be stored in the graph
    // because it was only called during the bubbling-up phase.
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, midKey);
    assertThat(result.get(midKey)).isNull();
    assertThatEvaluationResult(result).hasSingletonErrorThat(midKey);
    // In a keepGoing build, midKey should be re-evaluated.
    assertThat(((StringValue) tester.evalAndGet(/*keepGoing=*/ true, parentKey)).getValue())
        .isEqualTo("recovered");
  }

  /**
   * "top" requests a dependency group in which the first value, called "error", throws an
   * exception, so "mid" and "mid2", which depend on "slow", never get built.
   */
  @Test
  public void errorInDependencyGroup() throws Exception {
    SkyKey topKey = skyKey("top");
    CountDownLatch slowStart = new CountDownLatch(1);
    CountDownLatch errorFinish = new CountDownLatch(1);
    SkyKey errorKey = nonHermeticKey("error");
    tester
        .getOrCreate(errorKey)
        .setBuilder(
            new ChainedFunction(
                /*notifyStart=*/ null,
                /*waitToFinish=*/ slowStart,
                /*notifyFinish=*/ errorFinish,
                /*waitForException=*/ false,
                // ChainedFunction throws when value is null.
                /*value=*/ null,
                /*deps=*/ ImmutableList.of()));
    SkyKey slowKey = skyKey("slow");
    tester
        .getOrCreate(slowKey)
        .setBuilder(
            new ChainedFunction(
                /*notifyStart=*/ slowStart,
                /*waitToFinish=*/ errorFinish,
                /*notifyFinish=*/ null,
                /*waitForException=*/ true,
                new StringValue("slow"),
                /*deps=*/ ImmutableList.of()));
    SkyKey midKey = skyKey("mid");
    tester.getOrCreate(midKey).addDependency(slowKey).setComputedValue(COPY);
    SkyKey mid2Key = skyKey("mid2");
    tester.getOrCreate(mid2Key).addDependency(slowKey).setComputedValue(COPY);
    tester.set(topKey, null);
    tester
        .getOrCreate(topKey)
        .setBuilder(
            (skyKey, env) -> {
              env.getValuesAndExceptions(ImmutableList.of(errorKey, midKey, mid2Key));
              if (env.valuesMissing()) {
                return null;
              }
              return new StringValue("top");
            });

    // Assert that build fails and "error" really is in error.
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, topKey);
    assertThat(result.hasError()).isTrue();
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(topKey);

    // Ensure that evaluation succeeds if errorKey does not throw an error.
    tester.getOrCreate(errorKey).setBuilder(null);
    tester.set(errorKey, new StringValue("ok"));
    tester.invalidate();
    assertThat(tester.evalAndGet("top")).isEqualTo(new StringValue("top"));
  }

  /**
   * Regression test -- if value top requests {depA, depB}, depC, with depA and depC there and depB
   * absent, and then throws an exception, the stored deps should be depA, depC (in different
   * groups), not {depA, depC} (same group).
   */
  @Test
  public void valueInErrorWithGroups() throws Exception {
    SkyKey topKey = skyKey("top");
    SkyKey groupDepA = nonHermeticKey("groupDepA");
    SkyKey groupDepB = skyKey("groupDepB");
    SkyKey depC = nonHermeticKey("depC");
    tester.set(groupDepA, new SkyKeyValue(depC));
    tester.set(groupDepB, new StringValue(""));
    tester.getOrCreate(depC).setHasError(true);
    tester
        .getOrCreate(topKey)
        .setBuilder(
            (skyKey, env) -> {
              SkyKeyValue val =
                  (SkyKeyValue)
                      env.getValuesAndExceptions(ImmutableList.of(groupDepA, groupDepB))
                          .get(groupDepA);
              if (env.valuesMissing()) {
                return null;
              }
              try {
                env.getValueOrThrow(val.key, SomeErrorException.class);
              } catch (SomeErrorException e) {
                throw new GenericFunctionException(e, Transience.PERSISTENT);
              }
              return env.valuesMissing() ? null : new StringValue("top");
            });

    EvaluationResult<SkyValue> evaluationResult = tester.eval(/*keepGoing=*/ true, groupDepA, depC);
    assertThat(((SkyKeyValue) evaluationResult.get(groupDepA)).key).isEqualTo(depC);
    assertThatEvaluationResult(evaluationResult).hasErrorEntryForKeyThat(depC);
    evaluationResult = tester.eval(/*keepGoing=*/ false, topKey);
    assertThatEvaluationResult(evaluationResult).hasErrorEntryForKeyThat(topKey);

    tester.set(groupDepA, new SkyKeyValue(groupDepB));
    tester.getOrCreate(depC, /*markAsModified=*/ true);
    tester.invalidate();
    evaluationResult = tester.eval(/*keepGoing=*/ false, topKey);
    assertWithMessage(evaluationResult.toString()).that(evaluationResult.hasError()).isFalse();
    assertThat(evaluationResult.get(topKey)).isEqualTo(new StringValue("top"));
  }

  private static class SkyKeyValue implements SkyValue {
    private final SkyKey key;

    private SkyKeyValue(SkyKey key) {
      this.key = key;
    }
  }

  @Test
  public void errorOnlyEmittedOnce() throws Exception {
    initializeTester();
    tester.set("x", new StringValue("y")).setWarning("fizzlepop");
    StringValue value = (StringValue) tester.evalAndGet("x");
    assertThat(value.getValue()).isEqualTo("y");
    assertThatEvents(eventCollector).containsExactly("fizzlepop");

    tester.invalidate();
    value = (StringValue) tester.evalAndGet("x");
    assertThat(value.getValue()).isEqualTo("y");
    // No new events emitted.
  }

  /**
   * We are checking here that we are resilient to a race condition in which a value that is
   * checking its children for dirtiness is signaled by all of its children, putting it in a ready
   * state, before the thread has terminated. Optionally, one of its children may throw an error,
   * shutting down the threadpool. The essential race is that a child about to throw signals its
   * parent and the parent's builder restarts itself before the exception is thrown. Here, the
   * signaling happens while dirty dependencies are being checked. We control the timing by blocking
   * "top"'s registering itself on its deps.
   */
  private void dirtyChildEnqueuesParentDuringCheckDependencies(boolean throwError)
      throws Exception {
    // Value to be built. It will be signaled to rebuild before it has finished checking its deps.
    SkyKey top = skyKey("a_top");
    // otherTop is alphabetically after top.
    SkyKey otherTop = skyKey("z_otherTop");
    // Dep that blocks before it acknowledges being added as a dep by top, so the firstKey value has
    // time to signal top. (Importantly its key is alphabetically after 'firstKey').
    SkyKey slowAddingDep = skyKey("slowDep");
    // Value that is modified on the second build. Its thread won't finish until it signals top,
    // which will wait for the signal before it enqueues its next dep. We prevent the thread from
    // finishing by having the graph listener block on the second reverse dep to signal.
    SkyKey firstKey = nonHermeticKey("first");
    tester.set(firstKey, new StringValue("biding"));
    // Don't perform any blocking on the first build.
    AtomicBoolean delayTopSignaling = new AtomicBoolean(false);
    CountDownLatch topSignaled = new CountDownLatch(1);
    CountDownLatch topRequestedDepOrRestartedBuild = new CountDownLatch(1);
    CountDownLatch parentsRequested = new CountDownLatch(2);
    injectGraphListenerForTesting(
        (key, type, order, context) -> {
          if (!delayTopSignaling.get()) {
            return;
          }
          if (key.equals(otherTop) && type == EventType.SIGNAL) {
            TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                topRequestedDepOrRestartedBuild, "top's builder did not start in time");
            return;
          }
          if (key.equals(firstKey) && type == EventType.ADD_REVERSE_DEP && order == Order.AFTER) {
            parentsRequested.countDown();
            return;
          }
          if (key.equals(firstKey) && type == EventType.CHECK_IF_DONE && order == Order.AFTER) {
            parentsRequested.countDown();
            if (throwError) {
              topRequestedDepOrRestartedBuild.countDown();
            }
            return;
          }
          if (key.equals(top) && type == EventType.SIGNAL && order == Order.AFTER) {
            // top is signaled by firstKey (since slowAddingDep is blocking), so slowAddingDep
            // is now free to acknowledge top as a parent.
            topSignaled.countDown();
            return;
          }
          if (key.equals(firstKey) && type == EventType.SET_VALUE && order == Order.BEFORE) {
            // Make sure both parents add themselves as rdeps.
            TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                parentsRequested, "parents did not request dep in time");
          }
          if (key.equals(slowAddingDep)
              && type == EventType.CHECK_IF_DONE
              && top.equals(context)
              && order == Order.BEFORE) {
            // If top is trying to declare a dep on slowAddingDep, wait until firstKey has
            // signaled top. Then this add dep will return DONE and top will be signaled,
            // making it ready, so it will be enqueued.
            TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                topSignaled, "first key didn't signal top in time");
          }
        },
        /* deterministic= */ true);
    tester.set(slowAddingDep, new StringValue("dep"));
    AtomicInteger numTopInvocations = new AtomicInteger(0);
    tester
        .getOrCreate(top)
        .setBuilder(
            (key, env) -> {
              numTopInvocations.incrementAndGet();
              if (delayTopSignaling.get()) {
                // The graph listener will block on firstKey's signaling of otherTop above until
                // this thread starts running.
                topRequestedDepOrRestartedBuild.countDown();
              }
              // top's builder just requests both deps in a group.
              env.getValuesAndExceptions(ImmutableList.of(firstKey, slowAddingDep));
              return env.valuesMissing() ? null : new StringValue("top");
            });
    // First build : just prime the graph.
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, top);
    assertThat(result.hasError()).isFalse();
    assertThat(result.get(top)).isEqualTo(new StringValue("top"));
    assertThat(numTopInvocations.get()).isEqualTo(2);
    // Now dirty the graph, and maybe have firstKey throw an error.
    if (throwError) {
      tester
          .getOrCreate(firstKey, /*markAsModified=*/ true)
          .setConstantValue(null)
          .setBuilder(
              new SkyFunction() {
                @Nullable
                @Override
                public SkyValue compute(SkyKey skyKey, Environment env)
                    throws SkyFunctionException {
                  TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                      parentsRequested, "both parents didn't request in time");
                  throw new GenericFunctionException(
                      new SomeErrorException(firstKey.toString()), Transience.PERSISTENT);
                }
              });
    } else {
      tester
          .getOrCreate(firstKey, /*markAsModified=*/ true)
          .setConstantValue(new StringValue("new"));
    }
    tester.getOrCreate(otherTop).addDependency(firstKey).setComputedValue(CONCATENATE);
    tester.invalidate();
    delayTopSignaling.set(true);
    result = tester.eval(/*keepGoing=*/ false, top, otherTop);
    if (throwError) {
      assertThatEvaluationResult(result).hasError();
      assertThat(result.keyNames()).isEmpty(); // No successfully evaluated values.
      assertThatEvaluationResult(result).hasErrorEntryForKeyThat(top);
      assertWithMessage(
              "on the incremental build, top's builder should have only been used in error "
                  + "bubbling")
          .that(numTopInvocations.get())
          .isEqualTo(3);
    } else {
      assertThatEvaluationResult(result).hasEntryThat(top).isEqualTo(new StringValue("top"));
      assertThatEvaluationResult(result).hasNoError();
      assertWithMessage(
              "on the incremental build, top's builder should have only been executed once in "
                  + "normal evaluation")
          .that(numTopInvocations.get())
          .isEqualTo(3);
    }
    assertThat(topSignaled.getCount()).isEqualTo(0);
    assertThat(topRequestedDepOrRestartedBuild.getCount()).isEqualTo(0);
  }

  @Test
  public void dirtyChildEnqueuesParentDuringCheckDependencies_ThrowDoesntEnqueue()
      throws Exception {
    dirtyChildEnqueuesParentDuringCheckDependencies(/*throwError=*/ true);
  }

  @Test
  public void dirtyChildEnqueuesParentDuringCheckDependencies_NoThrow() throws Exception {
    dirtyChildEnqueuesParentDuringCheckDependencies(/*throwError=*/ false);
  }

  @Test
  public void removeReverseDepFromRebuildingNode() throws Exception {
    SkyKey topKey = skyKey("top");
    SkyKey midKey = nonHermeticKey("mid");
    SkyKey changedKey = nonHermeticKey("changed");
    tester.getOrCreate(changedKey).setConstantValue(new StringValue("first"));
    // When top depends on mid,
    tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(CONCATENATE);
    // And mid depends on changed,
    tester.getOrCreate(midKey).addDependency(changedKey).setComputedValue(CONCATENATE);
    CountDownLatch changedKeyStarted = new CountDownLatch(1);
    CountDownLatch changedKeyCanFinish = new CountDownLatch(1);
    AtomicBoolean controlTiming = new AtomicBoolean(false);
    injectGraphListenerForTesting(
        (key, type, order, context) -> {
          if (!controlTiming.get()) {
            return;
          }
          if (key.equals(midKey) && type == EventType.CHECK_IF_DONE && order == Order.BEFORE) {
            TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                changedKeyStarted, "changed key didn't start");
          } else if (key.equals(changedKey)
              && type == EventType.REMOVE_REVERSE_DEP
              && order == Order.AFTER
              && midKey.equals(context)) {
            changedKeyCanFinish.countDown();
          }
        },
        /* deterministic= */ false);
    // Then top builds as expected.
    assertThat(tester.evalAndGet(/*keepGoing=*/ false, topKey)).isEqualTo(new StringValue("first"));
    // When changed is modified,
    tester
        .getOrCreate(changedKey, /*markAsModified=*/ true)
        .setConstantValue(null)
        .setBuilder(
            // And changed is not allowed to finish building until it is released,
            new ChainedFunction(
                changedKeyStarted,
                changedKeyCanFinish,
                null,
                false,
                new StringValue("second"),
                ImmutableList.of()));
    // And mid is independently marked as modified,
    tester
        .getOrCreate(midKey, /*markAsModified=*/ true)
        .removeDependency(changedKey)
        .setComputedValue(null)
        .setConstantValue(new StringValue("mid"));
    tester.invalidate();
    SkyKey newTopKey = skyKey("newTop");
    // And changed will start rebuilding independently of midKey, because it's requested directly by
    // newTop
    tester.getOrCreate(newTopKey).addDependency(changedKey).setComputedValue(CONCATENATE);
    // And we control the timing using the graph listener above to make sure that:
    // (1) before we do anything with mid, changed has already started, and
    // (2) changed key can't finish until mid tries to remove its reverse dep from changed,
    controlTiming.set(true);
    // Then this evaluation completes without crashing.
    tester.eval(/*keepGoing=*/ false, newTopKey, topKey);
  }

  @Test
  public void dirtyThenDeleted() throws Exception {
    SkyKey topKey = nonHermeticKey("top");
    SkyKey leafKey = nonHermeticKey("leaf");
    tester.getOrCreate(topKey).addDependency(leafKey).setComputedValue(CONCATENATE);
    tester.set(leafKey, new StringValue("leafy"));
    assertThat(tester.evalAndGet(/* keepGoing= */ false, topKey))
        .isEqualTo(new StringValue("leafy"));
    tester.getOrCreate(topKey, /* markAsModified= */ true);
    tester.invalidate();
    assertThat(tester.evalAndGet(/* keepGoing= */ false, leafKey))
        .isEqualTo(new StringValue("leafy"));
    tester.delete("top");
    tester.getOrCreate(leafKey, /* markAsModified= */ true);
    tester.invalidate();
    assertThat(tester.evalAndGet(/* keepGoing= */ false, leafKey))
        .isEqualTo(new StringValue("leafy"));
  }

  /**
   * Basic test for a {@link SkyFunction.Reset} with no rewinding of dependencies.
   *
   * <p>Ensures that {@link NodeEntry#getResetDirectDeps} is used correctly by Skyframe to avoid
   * registering duplicate rdep edges when {@code dep} is requested both before and after a reset.
   *
   * <p>This test covers the case where {@code dep} is newly requested post-reset during a {@link
   * SkyFunction#compute} invocation that returns a {@link SkyValue}, which exercises a different
   * {@link AbstractParallelEvaluator} code path than the scenario covered by {@link
   * #resetSelfOnly_extraDepMissingAfterReset_initialBuild}.
   */
  @Test
  public void resetSelfOnly_singleDep_initialBuild() throws Exception {
    assume().that(resetSupported()).isTrue();

    var inconsistencyReceiver = recordInconsistencies();
    SkyKey top = skyKey("top");
    SkyKey dep = skyKey("dep");

    tester
        .getOrCreate(top)
        .setBuilder(
            new SkyFunction() {
              private boolean alreadyReset = false;

              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
                var depValue = env.getValue(dep);
                if (depValue == null) {
                  return null;
                }
                if (!alreadyReset) {
                  alreadyReset = true;
                  return Reset.selfOnly(top);
                }
                return new StringValue("topVal");
              }
            });
    tester.getOrCreate(dep).setConstantValue(new StringValue("depVal"));

    var result = tester.eval(/* keepGoing= */ false, top);

    assertThatEvaluationResult(result).hasEntryThat(top).isEqualTo(new StringValue("topVal"));
    assertThat(inconsistencyReceiver).containsExactly(InconsistencyData.resetRequested(top));

    if (!incrementalitySupported()) {
      return; // Skip assertions on dependency edges when they aren't kept.
    }

    assertThatEvaluationResult(result).hasReverseDepsInGraphThat(dep).containsExactly(top);
    assertThatEvaluationResult(result).hasDirectDepsInGraphThat(top).containsExactly(dep);
  }

  /**
   * Similar to {@link #resetSelfOnly_singleDep_initialBuild} except that the reset occurs on the
   * node's incremental build.
   */
  @Test
  public void resetSelfOnly_singleDep_incrementalBuild() throws Exception {
    assume().that(resetSupported()).isTrue();
    assume().that(incrementalitySupported()).isTrue();

    var inconsistencyReceiver = recordInconsistencies();
    SkyKey top = skyKey("top");
    SkyKey dep = nonHermeticKey("dep");

    tester
        .getOrCreate(top)
        .setBuilder(
            new SkyFunction() {
              private boolean alreadyReset = false;

              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
                var depValue = (StringValue) env.getValue(dep);
                if (depValue == null) {
                  return null;
                }
                if (depValue.getValue().equals("depVal2") && !alreadyReset) {
                  alreadyReset = true;
                  return Reset.selfOnly(top);
                }
                return new StringValue("topVal");
              }
            });

    tester.getOrCreate(dep).setConstantValue(new StringValue("depVal1"));
    tester.eval(/* keepGoing= */ false, top);
    assertThat(inconsistencyReceiver).isEmpty();

    tester.set(dep, new StringValue("depVal2"));
    tester.invalidate();
    var result = tester.eval(/* keepGoing= */ false, top);

    assertThatEvaluationResult(result).hasEntryThat(top).isEqualTo(new StringValue("topVal"));
    assertThat(inconsistencyReceiver).containsExactly(InconsistencyData.resetRequested(top));
    assertThatEvaluationResult(result).hasReverseDepsInGraphThat(dep).containsExactly(top);
    assertThatEvaluationResult(result).hasDirectDepsInGraphThat(top).containsExactly(dep);
  }

  /**
   * Test for a {@link SkyFunction.Reset} with no rewinding of dependencies, with a missing
   * dependency requested post-reset.
   *
   * <p>Ensures that {@link NodeEntry#getResetDirectDeps} is used correctly by Skyframe to avoid
   * registering duplicate rdep edges when {@code dep} is requested both before and after a reset.
   *
   * <p>This test covers the case where {@code dep} is newly requested post-reset in a {@link
   * SkyFunction#compute} invocation that returns {@code null} (because {@code extraDep} is
   * missing), which exercises a different {@link AbstractParallelEvaluator} code path than the
   * scenario covered by {@link #resetSelfOnly_singleDep_initialBuild}.
   */
  @Test
  public void resetSelfOnly_extraDepMissingAfterReset_initialBuild() throws Exception {
    assume().that(resetSupported()).isTrue();

    var inconsistencyReceiver = recordInconsistencies();
    SkyKey top = skyKey("top");
    SkyKey dep = skyKey("dep");
    SkyKey extraDep = skyKey("extraDep");

    tester
        .getOrCreate(top)
        .setBuilder(
            new SkyFunction() {
              private boolean alreadyReset = false;

              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
                var depValue = env.getValue(dep);
                if (depValue == null) {
                  return null;
                }
                if (!alreadyReset) {
                  alreadyReset = true;
                  return Reset.selfOnly(top);
                }
                var extraDepValue = env.getValue(extraDep);
                if (extraDepValue == null) {
                  return null;
                }
                return new StringValue("topVal");
              }
            });
    tester.getOrCreate(dep).setConstantValue(new StringValue("depVal"));
    tester.getOrCreate(extraDep).setConstantValue(new StringValue("extraDepVal"));

    var result = tester.eval(/* keepGoing= */ false, top);

    assertThatEvaluationResult(result).hasEntryThat(top).isEqualTo(new StringValue("topVal"));
    assertThat(inconsistencyReceiver).containsExactly(InconsistencyData.resetRequested(top));

    if (!incrementalitySupported()) {
      return; // Skip assertions on dependency edges when they aren't kept.
    }

    assertThatEvaluationResult(result).hasReverseDepsInGraphThat(dep).containsExactly(top);
    assertThatEvaluationResult(result)
        .hasDirectDepsInGraphThat(top)
        .containsExactly(dep, extraDep)
        .inOrder();
    assertThatEvaluationResult(result).hasReverseDepsInGraphThat(extraDep).containsExactly(top);
  }

  /**
   * Similar to {@link #resetSelfOnly_extraDepMissingAfterReset_initialBuild} except that the reset
   * occurs on the node's incremental build.
   */
  @Test
  public void resetSelfOnly_extraDepMissingAfterReset_incrementalBuild() throws Exception {
    assume().that(resetSupported()).isTrue();
    assume().that(incrementalitySupported()).isTrue();

    var inconsistencyReceiver = recordInconsistencies();
    SkyKey top = skyKey("top");
    SkyKey dep = nonHermeticKey("dep");
    SkyKey extraDep = skyKey("extraDep");

    tester
        .getOrCreate(top)
        .setBuilder(
            new SkyFunction() {
              private boolean alreadyReset = false;

              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
                var depValue = (StringValue) env.getValue(dep);
                if (depValue == null) {
                  return null;
                }
                if (depValue.getValue().equals("depVal2") && !alreadyReset) {
                  alreadyReset = true;
                  return Reset.selfOnly(top);
                }
                var extraDepValue = env.getValue(extraDep);
                if (extraDepValue == null) {
                  return null;
                }
                return new StringValue("topVal");
              }
            });
    tester.getOrCreate(dep).setConstantValue(new StringValue("depVal1"));
    tester.getOrCreate(extraDep).setConstantValue(new StringValue("extraDepVal"));
    tester.eval(/* keepGoing= */ false, top);
    assertThat(inconsistencyReceiver).isEmpty();

    tester.set(dep, new StringValue("depVal2"));
    tester.invalidate();
    var result = tester.eval(/* keepGoing= */ false, top);

    assertThatEvaluationResult(result).hasEntryThat(top).isEqualTo(new StringValue("topVal"));
    assertThat(inconsistencyReceiver).containsExactly(InconsistencyData.resetRequested(top));
    assertThatEvaluationResult(result).hasReverseDepsInGraphThat(dep).containsExactly(top);
    assertThatEvaluationResult(result)
        .hasDirectDepsInGraphThat(top)
        .containsExactly(dep, extraDep)
        .inOrder();
    assertThatEvaluationResult(result).hasReverseDepsInGraphThat(extraDep).containsExactly(top);
  }

  /**
   * Tests that if a dependency is requested prior to a {@link SkyFunction.Reset} but not after,
   * then the corresponding reverse dep edge is removed.
   *
   * <p>This happens in practice with input-discovering actions, which use mutable state to track
   * input discovery, resulting in unstable dependencies.
   */
  @Test
  public void resetSelfOnly_depNotRequestedAgainAfterReset() throws Exception {
    assume().that(resetSupported()).isTrue();

    var inconsistencyReceiver = recordInconsistencies();
    SkyKey top = skyKey("top");
    SkyKey flakyDep = skyKey("flakyDep");
    SkyKey stableDep = skyKey("stableDep");

    tester
        .getOrCreate(top)
        .setBuilder(
            new SkyFunction() {
              private boolean alreadyReset = false;

              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
                env.getValuesAndExceptions(
                    alreadyReset
                        ? ImmutableList.of(stableDep)
                        : ImmutableList.of(stableDep, flakyDep));
                if (env.valuesMissing()) {
                  return null;
                }
                if (!alreadyReset) {
                  alreadyReset = true;
                  return Reset.selfOnly(top);
                }
                return new StringValue("topVal");
              }
            });
    tester.getOrCreate(stableDep).setConstantValue(new StringValue("stableDepVal"));
    tester.getOrCreate(flakyDep).setConstantValue(new StringValue("flakyDepVal"));

    var result = tester.eval(/* keepGoing= */ false, top);

    assertThatEvaluationResult(result).hasEntryThat(top).isEqualTo(new StringValue("topVal"));
    assertThat(inconsistencyReceiver).containsExactly(InconsistencyData.resetRequested(top));

    if (!incrementalitySupported()) {
      return; // Skip assertions on dependency edges when they aren't kept.
    }

    assertThatEvaluationResult(result).hasDirectDepsInGraphThat(top).containsExactly(stableDep);
    assertThatEvaluationResult(result).hasReverseDepsInGraphThat(stableDep).containsExactly(top);
    assertThatEvaluationResult(result).hasReverseDepsInGraphThat(flakyDep).isEmpty();
  }

  /**
   * Tests that reset nodes are properly handled during invalidation after an aborted evaluation.
   *
   * <p>Invalidation deletes any nodes that are incomplete from the prior evaluation (in this case
   * {@code top}). It should also remove the corresponding reverse dep edge from {@code dep} even
   * though {@code top} does not have {@code dep} as a temporary direct dep when the evaluation is
   * aborted.
   *
   * <p>An aborted evaluation can happen in practice when there is an error on a {@code
   * --nokeep_going} build or if the user hits ctrl+c.
   */
  @Test
  public void resetSelfOnly_evaluationAborted() throws Exception {
    assume().that(resetSupported()).isTrue();
    assume().that(incrementalitySupported()).isTrue();

    var inconsistencyReceiver = recordInconsistencies();
    SkyKey top = skyKey("top");
    SkyKey dep = skyKey("dep");

    tester
        .getOrCreate(top)
        .setBuilder(
            new SkyFunction() {
              private boolean alreadyReset = false;

              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
                if (alreadyReset) {
                  throw new InterruptedException("Evaluation aborted");
                }
                var depValue = env.getValue(dep);
                if (depValue == null) {
                  return null;
                }
                alreadyReset = true;
                return Reset.selfOnly(top);
              }
            });
    tester.getOrCreate(dep).setConstantValue(new StringValue("depVal"));

    assertThrows(InterruptedException.class, () -> tester.eval(/* keepGoing= */ false, top));
    assertThat(inconsistencyReceiver).containsExactly(InconsistencyData.resetRequested(top));
    inconsistencyReceiver.clear();

    var result = tester.eval(/* keepGoing= */ false, dep);

    assertThatEvaluationResult(result).hasEntryThat(dep).isEqualTo(new StringValue("depVal"));
    assertThat(inconsistencyReceiver).isEmpty();
    assertThat(tester.evaluator.getValues()).doesNotContainKey(top);
    assertThatEvaluationResult(result).hasReverseDepsInGraphThat(dep).isEmpty();
  }

  /** Basic test of rewinding. */
  @Test
  public void rewindOneDep() throws Exception {
    assume().that(resetSupported()).isTrue();

    var inconsistencyReceiver = recordInconsistencies();
    var rewindableFunction = new RewindableFunction();
    SkyKey top = skyKey("top");
    SkyKey dep = skyKey("dep");

    tester.getOrCreate(dep).setBuilder(rewindableFunction);
    tester
        .getOrCreate(top)
        .setBuilder(
            (skyKey, env) -> {
              var depValue = (StringValue) env.getValue(dep);
              if (depValue == null) {
                return null;
              }
              if (depValue.equals(RewindableFunction.STALE_VALUE)) {
                var rewindGraph = Reset.newRewindGraphFor(top);
                rewindGraph.putEdge(top, dep);
                return Reset.of(rewindGraph);
              }
              assertThat(depValue).isEqualTo(RewindableFunction.FRESH_VALUE);
              return new StringValue("topVal");
            });

    var result = tester.eval(/* keepGoing= */ false, top);

    assertThatEvaluationResult(result).hasEntryThat(top).isEqualTo(new StringValue("topVal"));
    assertThat(inconsistencyReceiver)
        .containsExactly(
            InconsistencyData.resetRequested(top),
            InconsistencyData.rewind(top, ImmutableSet.of(dep)));
    assertThat(rewindableFunction.calls).isEqualTo(2);

    if (!incrementalitySupported()) {
      return; // Skip assertions on dependency edges when they aren't kept.
    }

    assertThatEvaluationResult(result).hasReverseDepsInGraphThat(dep).containsExactly(top);
    assertThatEvaluationResult(result).hasDirectDepsInGraphThat(top).containsExactly(dep);
  }

  /**
   * Tests the case where multiple parents attempt to rewind the same node concurrently, one
   * successfully dirties the node, and the other observes the node as already dirty.
   */
  @Test
  public void twoParentsRewindSameDep_markedDirtyOnce() throws Exception {
    assume().that(resetSupported()).isTrue();

    var inconsistencyReceiver = recordInconsistencies();
    var rewindableFunction = new RewindableFunction();
    CyclicBarrier parentBarrier = new CyclicBarrier(2);
    SkyKey top1 = skyKey("top1");
    SkyKey top2 = skyKey("top2");
    SkyKey dep = skyKey("dep");

    tester.getOrCreate(dep).setBuilder(rewindableFunction);
    injectGraphListenerForTesting(
        (key, type, order, context) -> {
          if (type == EventType.MARK_DIRTY && order == Order.AFTER) {
            awaitUnchecked(parentBarrier);
          }
        },
        /* deterministic= */ false);
    SkyFunction parentFunction =
        (skyKey, env) -> {
          var depValue = (StringValue) env.getValue(dep);
          if (depValue == null) {
            return null;
          }
          if (depValue.equals(RewindableFunction.STALE_VALUE)) {
            awaitUnchecked(parentBarrier);
            var rewindGraph = Reset.newRewindGraphFor(skyKey);
            rewindGraph.putEdge(skyKey, dep);
            return Reset.of(rewindGraph);
          }
          assertThat(depValue).isEqualTo(RewindableFunction.FRESH_VALUE);
          return new StringValue("topVal");
        };
    tester.getOrCreate(top1).setBuilder(parentFunction);
    tester.getOrCreate(top2).setBuilder(parentFunction);

    var result = tester.eval(/* keepGoing= */ false, top1, top2);

    assertThatEvaluationResult(result).hasEntryThat(top1).isEqualTo(new StringValue("topVal"));
    assertThatEvaluationResult(result).hasEntryThat(top2).isEqualTo(new StringValue("topVal"));
    assertThat(inconsistencyReceiver)
        .containsExactly(
            InconsistencyData.resetRequested(top1),
            InconsistencyData.rewind(top1, ImmutableSet.of(dep)),
            InconsistencyData.resetRequested(top2),
            InconsistencyData.rewind(top2, ImmutableSet.of(dep)));
    assertThat(rewindableFunction.calls).isEqualTo(2);

    if (!incrementalitySupported()) {
      return; // Skip assertions on dependency edges when they aren't kept.
    }

    assertThatEvaluationResult(result).hasDirectDepsInGraphThat(top1).containsExactly(dep);
    assertThatEvaluationResult(result).hasDirectDepsInGraphThat(top2).containsExactly(dep);
    assertThatEvaluationResult(result).hasReverseDepsInGraphThat(dep).containsExactly(top1, top2);
  }

  /**
   * Tests the case where multiple parents attempt to rewind the same node concurrently and one
   * successfully dirties the node, which then completes before the second parent dirties the node
   * again.
   */
  @Test
  public void twoParentsRewindSameDep_markedDirtyTwice() throws Exception {
    assume().that(resetSupported()).isTrue();

    var inconsistencyReceiver = recordInconsistencies();
    var rewindableFunction = new RewindableFunction();
    CyclicBarrier parentBarrier = new CyclicBarrier(2);
    AtomicBoolean isFirstParent = new AtomicBoolean(true);
    CountDownLatch firstParentDone = new CountDownLatch(1);
    SkyKey top1 = skyKey("top1");
    SkyKey top2 = skyKey("top2");
    SkyKey dep = skyKey("dep");

    tester.getOrCreate(dep).setBuilder(rewindableFunction);
    injectGraphListenerForTesting(
        (key, type, order, context) -> {
          if (type == EventType.MARK_DIRTY
              && order == Order.BEFORE
              && !isFirstParent.getAndSet(false)) {
            // Lost the race. Wait for the first parent to finish so we rewind again. We could just
            // wait for dep to finish, but then we might mark it dirty before the first parent uses
            // it, which would lead to flaky BUILDING_PARENT_FOUND_UNDONE_CHILD inconsistencies.
            Uninterruptibles.awaitUninterruptibly(firstParentDone);
          }
        },
        /* deterministic= */ false);
    SkyFunction parentFunction =
        (skyKey, env) -> {
          var depValue = (StringValue) env.getValue(dep);
          if (depValue == null) {
            return null;
          }
          if (depValue.equals(RewindableFunction.STALE_VALUE)) {
            awaitUnchecked(parentBarrier);
            var rewindGraph = Reset.newRewindGraphFor(skyKey);
            rewindGraph.putEdge(skyKey, dep);
            return Reset.of(rewindGraph);
          }
          assertThat(depValue).isEqualTo(RewindableFunction.FRESH_VALUE);
          firstParentDone.countDown();
          return new StringValue("topVal");
        };
    tester.getOrCreate(top1).setBuilder(parentFunction);
    tester.getOrCreate(top2).setBuilder(parentFunction);

    var result = tester.eval(/* keepGoing= */ false, top1, top2);

    assertThatEvaluationResult(result).hasEntryThat(top1).isEqualTo(new StringValue("topVal"));
    assertThatEvaluationResult(result).hasEntryThat(top2).isEqualTo(new StringValue("topVal"));
    assertThat(inconsistencyReceiver)
        .containsExactly(
            InconsistencyData.resetRequested(top1),
            InconsistencyData.rewind(top1, ImmutableSet.of(dep)),
            InconsistencyData.resetRequested(top2),
            InconsistencyData.rewind(top2, ImmutableSet.of(dep)));
    assertThat(rewindableFunction.calls).isEqualTo(3);

    if (!incrementalitySupported()) {
      return; // Skip assertions on dependency edges when they aren't kept.
    }

    assertThatEvaluationResult(result).hasDirectDepsInGraphThat(top1).containsExactly(dep);
    assertThatEvaluationResult(result).hasDirectDepsInGraphThat(top2).containsExactly(dep);
    assertThatEvaluationResult(result).hasReverseDepsInGraphThat(dep).containsExactly(top1, top2);
  }

  /**
   * Regression test for b/315301248.
   *
   * <p>In a {@code --nokeep_going} build, multiple parents attempt to rewind the same node
   * concurrently. One successfully dirties the node, which then completes with an error before the
   * second parent attempts to dirty the node again. If the second rewinding attempt actually
   * transitions the node from done (in error) to dirty, we would crash during error bubbling, which
   * reasonably expects the errorful node to be done.
   *
   * <p>The solution is to ignore rewinding attempts on errorful nodes.
   */
  @Test
  public void twoParentsRewindSameDep_depEvaluatesToErrorAfterRewind() throws Exception {
    assume().that(resetSupported()).isTrue();

    var inconsistencyReceiver = recordInconsistencies();
    var rewindableErrorFunction =
        new SkyFunction() {
          private int calls = 0;

          @Nullable
          @Override
          public SkyValue compute(SkyKey skyKey, Environment env) throws SkyFunctionException {
            if (++calls == 1) {
              return RewindableFunction.STALE_VALUE;
            }
            throw new GenericFunctionException(new SomeErrorException("error"));
          }
        };
    CyclicBarrier parentBarrier = new CyclicBarrier(2);
    AtomicBoolean isFirstParent = new AtomicBoolean(true);
    CountDownLatch depErrorSet = new CountDownLatch(1);
    SkyKey top1 = skyKey("top1");
    SkyKey top2 = skyKey("top2");
    SkyKey dep = skyKey("dep");

    tester.getOrCreate(dep).setBuilder(rewindableErrorFunction);
    injectGraphListenerForTesting(
        (key, type, order, context) -> {
          if (type == EventType.MARK_DIRTY
              && order == Order.BEFORE
              && !isFirstParent.getAndSet(false)) {
            // Lost the race. Wait for dep have its error set so that we attempt to rewind a done
            // node in error.
            Uninterruptibles.awaitUninterruptibly(depErrorSet);
          } else if (key.equals(dep)
              && type == EventType.SET_VALUE
              && order == Order.AFTER
              && ValueWithMetadata.getMaybeErrorInfo((SkyValue) context) != null) {
            depErrorSet.countDown();
          }
        },
        /* deterministic= */ false);
    SkyFunction parentFunction =
        (skyKey, env) -> {
          var depValue = (StringValue) env.getValue(dep);
          if (depValue == null) {
            return null;
          }
          assertThat(depValue).isEqualTo(RewindableFunction.STALE_VALUE);
          awaitUnchecked(parentBarrier);
          var rewindGraph = Reset.newRewindGraphFor(skyKey);
          rewindGraph.putEdge(skyKey, dep);
          return Reset.of(rewindGraph);
        };
    tester.getOrCreate(top1).setBuilder(parentFunction);
    tester.getOrCreate(top2).setBuilder(parentFunction);

    var result = tester.eval(/* keepGoing= */ false, top1, top2);

    assertThatEvaluationResult(result).hasError();
    assertThat(result.errorMap().keySet()).containsAnyOf(top1, top2);
    assertThat(inconsistencyReceiver)
        .containsExactly(
            InconsistencyData.resetRequested(top1),
            InconsistencyData.rewind(top1, ImmutableSet.of(dep)),
            InconsistencyData.resetRequested(top2),
            InconsistencyData.rewind(top2, ImmutableSet.of(dep)));

    if (!incrementalitySupported()) {
      return; // Skip assertions on dependency edges when they aren't kept.
    }

    result = tester.eval(/* keepGoing= */ false, dep);

    // The parents never completed, so an incremental build deletes them. Check that they are no
    // longer in the graph and that rdeps are removed from dep.
    assertThatEvaluationResult(result).hasSingletonErrorThat(dep).isNotNull();
    assertThat(tester.evaluator.getValues().keySet()).containsNoneOf(top1, top2);
    assertThatEvaluationResult(result).hasReverseDepsInGraphThat(dep).isEmpty();
  }

  /**
   * Tests that when a node is rewound and evaluates to an error, its reverse transitive closure is
   * deleted from the graph, including parents that were done before rewinding took place.
   */
  @Test
  public void depWithDoneParentEvaluatesToErrorAfterRewind_reverseTransitiveClosureDeleted()
      throws Exception {
    assume().that(resetSupported()).isTrue();
    assume().that(incrementalitySupported()).isTrue();

    var inconsistencyReceiver = recordInconsistencies();
    var rewindableErrorFunction =
        new SkyFunction() {
          private int calls = 0;

          @Nullable
          @Override
          public SkyValue compute(SkyKey skyKey, Environment env) throws SkyFunctionException {
            if (++calls == 1) {
              return RewindableFunction.STALE_VALUE;
            }
            throw new GenericFunctionException(new SomeErrorException("error"));
          }
        };
    CountDownLatch goodTopDone = new CountDownLatch(1);
    SkyKey goodTop = skyKey("goodTop");
    SkyKey badTop = skyKey("badTop");
    SkyKey dep = nonHermeticKey("dep");

    injectGraphListenerForTesting(
        (key, type, order, context) -> {
          if (key.equals(goodTop) && type == EventType.SET_VALUE && order == Order.AFTER) {
            goodTopDone.countDown();
          }
        },
        /* deterministic= */ false);
    tester.getOrCreate(dep).setBuilder(rewindableErrorFunction);
    tester.getOrCreate(goodTop).addDependency(dep).setComputedValue(COPY);
    tester
        .getOrCreate(badTop)
        .setBuilder(
            (skyKey, env) -> {
              if (env.getValue(dep) == null) {
                return null;
              }
              goodTopDone.await();
              var rewindGraph = Reset.newRewindGraphFor(badTop);
              rewindGraph.putEdge(badTop, dep);
              return Reset.of(rewindGraph);
            });

    var result = tester.eval(/* keepGoing= */ false, goodTop, badTop);

    assertThatEvaluationResult(result).hasError();
    assertThat(result.errorMap().keySet()).containsExactly(badTop);
    assertThatEvaluationResult(result)
        .hasEntryThat(goodTop)
        .isEqualTo(RewindableFunction.STALE_VALUE);
    assertThat(inconsistencyReceiver)
        .containsExactly(
            InconsistencyData.resetRequested(badTop),
            InconsistencyData.rewind(badTop, ImmutableSet.of(dep)));

    result = tester.eval(/* keepGoing= */ false, new SkyKey[0]);

    assertThatEvaluationResult(result).hasNoError();
    assertThat(tester.getEvaluator().getValues().keySet()).containsNoneOf(dep, badTop, goodTop);
  }

  private static void awaitUnchecked(CyclicBarrier barrier) {
    try {
      barrier.await();
    } catch (InterruptedException | BrokenBarrierException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Test for a rewind graph with depth > 1.
   *
   * <p>Since {@code mid} simply propagates the value of {@code bottom}, {@code top} must rewind
   * both {@code mid} and {@code bottom}. See {@link
   * com.google.devtools.build.lib.actions.ActionExecutionMetadata#mayInsensitivelyPropagateInputs}
   * for the case that this test is simulating.
   */
  @Test
  public void rewindTransitiveDep() throws Exception {
    assume().that(resetSupported()).isTrue();

    var inconsistencyReceiver = recordInconsistencies();
    var rewindableFunction = new RewindableFunction();
    SkyKey top = skyKey("top");
    SkyKey mid = skyKey("mid");
    SkyKey bottom = skyKey("bottom");

    tester.getOrCreate(bottom).setBuilder(rewindableFunction);
    tester.getOrCreate(mid).addDependency(bottom).setComputedValue(COPY);
    tester
        .getOrCreate(top)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
                var midValue = (StringValue) env.getValue(mid);
                if (midValue == null) {
                  return null;
                }
                if (midValue.equals(RewindableFunction.STALE_VALUE)) {
                  var rewindGraph = Reset.newRewindGraphFor(top);
                  rewindGraph.putEdge(top, mid);
                  rewindGraph.putEdge(mid, bottom);
                  return Reset.of(rewindGraph);
                }
                assertThat(midValue).isEqualTo(RewindableFunction.FRESH_VALUE);
                return new StringValue("topVal");
              }
            });

    var result = tester.eval(/* keepGoing= */ false, top);

    assertThatEvaluationResult(result).hasEntryThat(top).isEqualTo(new StringValue("topVal"));
    assertThat(inconsistencyReceiver)
        .containsExactly(
            InconsistencyData.resetRequested(top),
            InconsistencyData.rewind(top, ImmutableSet.of(mid, bottom)));
    assertThat(rewindableFunction.calls).isEqualTo(2);

    if (!incrementalitySupported()) {
      return; // Skip assertions on dependency edges when they aren't kept.
    }

    assertThatEvaluationResult(result).hasDirectDepsInGraphThat(top).containsExactly(mid);
    assertThatEvaluationResult(result).hasReverseDepsInGraphThat(mid).containsExactly(top);
    assertThatEvaluationResult(result).hasDirectDepsInGraphThat(mid).containsExactly(bottom);
    assertThatEvaluationResult(result).hasReverseDepsInGraphThat(bottom).containsExactly(mid);
  }

  /**
   * Tests that incompletely rewound nodes are properly handled during invalidation after an aborted
   * evaluation.
   *
   * <p>Covers the concern described at b/149243918#comment9: without rewinding, we have an
   * invariant that after an evaluation, a done node cannot depend on a dirty node. The invalidator
   * leverges this invariant by short-circuiting when it visits a dirty node, under the assumption
   * that any rdeps are either already dirty or present in the invalidation frontier.
   *
   * <p>In this test, a value propagates from {@code bottom} to {@code mid} to {@code goodTop}.
   * However, after {@code goodTop} is done, {@code badTop} rewinds {@code mid}, and then the
   * evaluation is aborted. On the incremental build, {@code bottom} changes. We must recompute
   * {@code goodTop}, but the only path from {@code bottom} to {@code goodTop} goes through {@code
   * mid}, which is dirty, and so the invalidator will never visit {@code goodTop}.
   *
   * <p>The solution: instead of relying on bottom-up invalidation, rewound nodes are treated like
   * inflight nodes and deleted (along with their reverse transitive closure) prior to the next
   * evaluation.
   */
  @Test
  public void evaluationAbortedWithRewoundNodeOnInvalidationPath_dirty() throws Exception {
    assume().that(resetSupported()).isTrue();
    assume().that(incrementalitySupported()).isTrue();

    var inconsistencyReceiver = recordInconsistencies();
    CountDownLatch goodTopDone = new CountDownLatch(1);
    SkyKey goodTop = skyKey("goodTop");
    SkyKey badTop = skyKey("badTop");
    SkyKey mid = skyKey("mid");
    SkyKey bottom = nonHermeticKey("bottom");

    injectGraphListenerForTesting(
        (key, type, order, context) -> {
          if (key.equals(goodTop) && type == EventType.SET_VALUE && order == Order.AFTER) {
            goodTopDone.countDown();
          }
        },
        /* deterministic= */ false);
    tester.getOrCreate(goodTop).addDependency(mid).setComputedValue(COPY);
    tester
        .getOrCreate(badTop)
        .setBuilder(
            new SkyFunction() {
              private boolean alreadyReset = false;

              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
                if (alreadyReset) {
                  throw new InterruptedException("Evaluation aborted");
                }
                if (env.getValue(mid) == null) {
                  return null;
                }
                goodTopDone.await();
                alreadyReset = true;
                var rewindGraph = Reset.newRewindGraphFor(badTop);
                rewindGraph.putEdge(badTop, mid);
                return Reset.of(rewindGraph);
              }
            });
    tester.getOrCreate(mid).addDependency(bottom).setComputedValue(COPY);
    tester.getOrCreate(bottom).setConstantValue(new StringValue("val1"));

    assertThrows(
        InterruptedException.class, () -> tester.eval(/* keepGoing= */ false, goodTop, badTop));
    assertThat(inconsistencyReceiver)
        .containsExactly(
            InconsistencyData.resetRequested(badTop),
            InconsistencyData.rewind(badTop, ImmutableSet.of(mid)));

    tester.set(bottom, new StringValue("val2"));
    tester.invalidate();
    var result = tester.eval(/* keepGoing= */ false, goodTop);

    assertThatEvaluationResult(result).hasEntryThat(goodTop).isEqualTo(new StringValue("val2"));
    assertThatEvaluationResult(result).hasDirectDepsInGraphThat(goodTop).containsExactly(mid);
    assertThatEvaluationResult(result).hasReverseDepsInGraphThat(mid).containsExactly(goodTop);
    assertThatEvaluationResult(result).hasDirectDepsInGraphThat(mid).containsExactly(bottom);
    assertThatEvaluationResult(result).hasReverseDepsInGraphThat(bottom).containsExactly(mid);
  }

  /**
   * Similar to {@link #evaluationAbortedWithRewoundNodeOnInvalidationPath_dirty} except that the
   * rewound node is inflight when the evaluation is aborted, so this actually works without the
   * special handling added for rewound nodes.
   */
  @Test
  public void evaluationAbortedWithRewoundNodeOnInvalidationPath_inflight() throws Exception {
    assume().that(resetSupported()).isTrue();
    assume().that(incrementalitySupported()).isTrue();

    var inconsistencyReceiver = recordInconsistencies();
    CountDownLatch goodTopDone = new CountDownLatch(1);
    AtomicBoolean rewindingInProgress = new AtomicBoolean(false);
    SkyKey goodTop = skyKey("goodTop");
    SkyKey badTop = skyKey("badTop");
    SkyKey mid = nonHermeticKey("mid");
    SkyKey bottom = nonHermeticKey("bottom");

    injectGraphListenerForTesting(
        (key, type, order, context) -> {
          if (key.equals(goodTop) && type == EventType.SET_VALUE && order == Order.AFTER) {
            goodTopDone.countDown();
          }
        },
        /* deterministic= */ false);
    tester.getOrCreate(goodTop).addDependency(mid).setComputedValue(COPY);
    tester
        .getOrCreate(badTop)
        .setBuilder(
            (skyKey, env) -> {
              if (env.getValue(mid) == null) {
                return null;
              }
              goodTopDone.await();
              rewindingInProgress.set(true);
              var rewindGraph = Reset.newRewindGraphFor(badTop);
              rewindGraph.putEdge(badTop, mid);
              return Reset.of(rewindGraph);
            });
    tester
        .getOrCreate(mid)
        .setBuilder(
            (skyKey, env) -> {
              if (rewindingInProgress.get()) {
                throw new InterruptedException("Evaluation aborted");
              }
              return env.getValue(bottom);
            });
    tester.getOrCreate(bottom).setConstantValue(new StringValue("val1"));

    assertThrows(
        InterruptedException.class, () -> tester.eval(/* keepGoing= */ false, goodTop, badTop));
    assertThat(inconsistencyReceiver)
        .containsExactly(
            InconsistencyData.resetRequested(badTop),
            InconsistencyData.rewind(badTop, ImmutableSet.of(mid)));

    rewindingInProgress.set(false);
    tester.set(bottom, new StringValue("val2"));
    tester.invalidate();
    var result = tester.eval(/* keepGoing= */ false, goodTop);

    assertThatEvaluationResult(result).hasEntryThat(goodTop).isEqualTo(new StringValue("val2"));
    assertThatEvaluationResult(result).hasDirectDepsInGraphThat(goodTop).containsExactly(mid);
    assertThatEvaluationResult(result).hasReverseDepsInGraphThat(mid).containsExactly(goodTop);
    assertThatEvaluationResult(result).hasDirectDepsInGraphThat(mid).containsExactly(bottom);
    assertThatEvaluationResult(result).hasReverseDepsInGraphThat(bottom).containsExactly(mid);
  }

  private RecordingInconsistencyReceiver recordInconsistencies() {
    var inconsistencyReceiver = new RecordingInconsistencyReceiver();
    tester.setGraphInconsistencyReceiver(inconsistencyReceiver);
    tester.initialize();
    return inconsistencyReceiver;
  }

  private static final class RecordingInconsistencyReceiver
      implements GraphInconsistencyReceiver, Iterable<InconsistencyData> {
    private final List<InconsistencyData> inconsistencies = new ArrayList<>();

    @Override
    public synchronized void noteInconsistencyAndMaybeThrow(
        SkyKey key, @Nullable Collection<SkyKey> otherKeys, Inconsistency inconsistency) {
      inconsistencies.add(InconsistencyData.create(key, otherKeys, inconsistency));
    }

    @Override
    public Iterator<InconsistencyData> iterator() {
      return inconsistencies.iterator();
    }

    void clear() {
      inconsistencies.clear();
    }
  }

  /**
   * {@link SkyFunction} for rewinding tests that returns {@link #STALE_VALUE} the first time and
   * {@link #FRESH_VALUE} thereafter.
   */
  private static final class RewindableFunction implements SkyFunction {
    static final StringValue STALE_VALUE = new StringValue("stale");
    static final StringValue FRESH_VALUE = new StringValue("fresh");

    private int calls = 0;

    @Override
    public SkyValue compute(SkyKey skyKey, Environment env) {
      return ++calls == 1 ? STALE_VALUE : FRESH_VALUE;
    }
  }

  /**
   * The same dep is requested in two groups, but its value determines what the other dep in the
   * second group is. When it changes, the other dep in the second group should not be requested.
   */
  @Test
  public void sameDepInTwoGroups() throws Exception {
    initializeTester();

    // leaf4 should not be built in the second build.
    SkyKey leaf4 = skyKey("leaf4");
    AtomicBoolean shouldNotBuildLeaf4 = new AtomicBoolean(false);
    injectGraphListenerForTesting(
        (key, type, order, context) -> {
          if (shouldNotBuildLeaf4.get()
              && key.equals(leaf4)
              && type != EventType.REMOVE_REVERSE_DEP
              && type != EventType.GET_BATCH) {
            throw new IllegalStateException(
                "leaf4 should not have been considered this build: "
                    + type
                    + ", "
                    + order
                    + ", "
                    + context);
          }
        },
        /* deterministic= */ false);
    tester.set(leaf4, new StringValue("leaf4"));

    // Create leaf0, leaf1 and leaf2 values with values "leaf2", "leaf3", "leaf4" respectively.
    // These will be requested as one dependency group. In the second build, leaf2 will have the
    // value "leaf5".
    List<SkyKey> leaves = new ArrayList<>();
    for (int i = 0; i <= 2; i++) {
      SkyKey leaf = i == 2 ? nonHermeticKey("leaf" + i) : skyKey("leaf" + i);
      leaves.add(leaf);
      tester.set(leaf, new StringValue("leaf" + (i + 2)));
    }

    // Create "top" value. It depends on all leaf values in two overlapping dependency groups.
    SkyKey topKey = skyKey("top");
    SkyValue topValue = new StringValue("top");
    tester
        .getOrCreate(topKey)
        .setBuilder(
            (skyKey, env) -> {
              // Request the first group, [leaf0, leaf1, leaf2].
              // In the first build, it has values ["leaf2", "leaf3", "leaf4"].
              // In the second build it has values ["leaf2", "leaf3", "leaf5"]
              SkyframeLookupResult values = env.getValuesAndExceptions(leaves);
              if (env.valuesMissing()) {
                return null;
              }

              // Request the second group. In the first build it's [leaf2, leaf4].
              // In the second build it's [leaf2, leaf5]
              env.getValuesAndExceptions(
                  ImmutableList.of(
                      leaves.get(2), skyKey(((StringValue) values.get(leaves.get(2))).getValue())));
              if (env.valuesMissing()) {
                return null;
              }

              return topValue;
            });

    // First build: assert we can evaluate "top".
    assertThat(tester.evalAndGet(/*keepGoing=*/ false, topKey)).isEqualTo(topValue);

    // Second build: replace "leaf4" by "leaf5" in leaf2's value. Assert leaf4 is not requested.
    SkyKey leaf5 = skyKey("leaf5");
    tester.set(leaf5, new StringValue("leaf5"));
    tester.set(leaves.get(2), new StringValue("leaf5"));
    tester.invalidate();
    shouldNotBuildLeaf4.set(true);
    assertThat(tester.evalAndGet(/*keepGoing=*/ false, topKey)).isEqualTo(topValue);
  }

  @Test
  public void dirtyAndChanged() throws Exception {
    SkyKey leaf = nonHermeticKey("leaf");
    SkyKey mid = nonHermeticKey("mid");
    SkyKey top = skyKey("top");
    tester.getOrCreate(top).addDependency(mid).setComputedValue(COPY);
    tester.getOrCreate(mid).addDependency(leaf).setComputedValue(COPY);
    tester.set(leaf, new StringValue("leafy"));
    // For invalidation.
    tester.set("dummy", new StringValue("dummy"));
    StringValue topValue = (StringValue) tester.evalAndGet("top");
    assertThat(topValue.getValue()).isEqualTo("leafy");
    tester.set(leaf, new StringValue("crunchy"));
    tester.invalidate();
    // For invalidation.
    tester.evalAndGet("dummy");
    tester.getOrCreate(mid, /*markAsModified=*/ true);
    tester.invalidate();
    topValue = (StringValue) tester.evalAndGet("top");
    assertThat(topValue.getValue()).isEqualTo("crunchy");
  }

  /**
   * Test whether a value that was already marked changed will be incorrectly marked dirty, not
   * changed, if another thread tries to mark it just dirty. To exercise this, we need to have a
   * race condition where both threads see that the value is not dirty yet, then the "changed"
   * thread marks the value changed before the "dirty" thread marks the value dirty. To accomplish
   * this, we use a countdown latch to make the "dirty" thread wait until the "changed" thread is
   * done, and another countdown latch to make both of them wait until they have both checked if the
   * value is currently clean.
   */
  @Test
  public void dirtyAndChangedValueIsChanged() throws Exception {
    SkyKey parent = nonHermeticKey("parent");
    AtomicBoolean blockingEnabled = new AtomicBoolean(false);
    CountDownLatch waitForChanged = new CountDownLatch(1);
    // changed thread checks value entry once (to see if it is changed). dirty thread checks twice,
    // to see if it is changed, and if it is dirty.
    CountDownLatch threadsStarted = new CountDownLatch(3);
    injectGraphListenerForTesting(
        (key, type, order, context) -> {
          if (!blockingEnabled.get()) {
            return;
          }
          if (!key.equals(parent)) {
            return;
          }
          if (type == EventType.IS_CHANGED && order == Order.BEFORE) {
            threadsStarted.countDown();
          }
          // Dirtiness only checked by dirty thread.
          if (type == EventType.IS_DIRTY && order == Order.BEFORE) {
            threadsStarted.countDown();
          }
          if (type == EventType.MARK_DIRTY) {
            TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                threadsStarted, "Both threads did not query if value isChanged in time");
            if (order == Order.BEFORE) {
              DirtyType dirtyType = (DirtyType) context;
              if (dirtyType.equals(DirtyType.DIRTY)) {
                TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                    waitForChanged, "'changed' thread did not mark value changed in time");
                return;
              }
            }
            if (order == Order.AFTER) {
              DirtyType dirtyType = ((NotifyingHelper.MarkDirtyAfterContext) context).dirtyType();
              if (dirtyType.equals(DirtyType.CHANGE)) {
                waitForChanged.countDown();
              }
            }
          }
        },
        /* deterministic= */ false);
    SkyKey leaf = nonHermeticKey("leaf");
    tester.set(leaf, new StringValue("leaf"));
    tester.getOrCreate(parent).addDependency(leaf).setComputedValue(CONCATENATE);
    EvaluationResult<StringValue> result;
    result = tester.eval(/* keepGoing= */ false, parent);
    assertThat(result.get(parent).getValue()).isEqualTo("leaf");
    // Invalidate leaf, but don't actually change it. It will transitively dirty parent
    // concurrently with parent directly dirtying itself.
    tester.getOrCreate(leaf, /* markAsModified= */ true);
    SkyKey other2 = skyKey("other2");
    tester.set(other2, new StringValue("other2"));
    // Invalidate parent, actually changing it.
    tester.getOrCreate(parent, /* markAsModified= */ true).addDependency(other2);
    tester.invalidate();
    blockingEnabled.set(true);
    result = tester.eval(/* keepGoing= */ false, parent);
    assertThat(result.get(parent).getValue()).isEqualTo("leafother2");
    assertThat(waitForChanged.getCount()).isEqualTo(0);
    assertThat(threadsStarted.getCount()).isEqualTo(0);
  }

  @Test
  public void hermeticSkyFunctionCanThrowTransientErrorThenRecover() throws Exception {
    SkyKey leaf = skyKey("leaf");
    SkyKey top = skyKey("top");
    // When top depends on leaf, but throws a transient error,
    tester
        .getOrCreate(top)
        .addDependency(leaf)
        .setHasTransientError(true)
        .setComputedValue(CONCATENATE);
    StringValue value = new StringValue("value");
    tester.getOrCreate(leaf).setConstantValue(value);
    // And the first build throws a transient error (as expected),
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ true, top);
    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(top).hasExceptionThat().isNotNull();
    // And then top's transient error is removed,
    tester.getOrCreate(top, /*markAsModified=*/ false).setHasTransientError(false);
    tester.invalidateTransientErrors();
    // Then top evaluates successfully, even though it was hermetic and didn't give the same result
    // on successive evaluations with the same inputs.
    result = tester.eval(/*keepGoing=*/ true, top);
    assertThatEvaluationResult(result).hasNoError();
    assertThatEvaluationResult(result).hasEntryThat(top).isEqualTo(value);
  }

  @Test
  public void singleValueDependsOnManyDirtyValues() throws Exception {
    SkyKey[] values = new SkyKey[TEST_NODE_COUNT];
    StringBuilder expected = new StringBuilder();
    for (int i = 0; i < values.length; i++) {
      String valueName = Integer.toString(i);
      values[i] = nonHermeticKey(valueName);
      tester.set(values[i], new StringValue(valueName));
      expected.append(valueName);
    }
    SkyKey topKey = skyKey("top");
    TestFunction value = tester.getOrCreate(topKey).setComputedValue(CONCATENATE);
    for (SkyKey skyKey : values) {
      value.addDependency(skyKey);
    }

    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, topKey);
    assertThat(result.get(topKey)).isEqualTo(new StringValue(expected.toString()));

    for (int j = 0; j < RUNS; j++) {
      for (SkyKey skyKey : values) {
        tester.getOrCreate(skyKey, /*markAsModified=*/ true);
      }
      // This value has an error, but we should never discover it because it is not marked changed
      // and all of its dependencies re-evaluate to the same thing.
      tester.getOrCreate(topKey, /*markAsModified=*/ false).setHasError(true);
      tester.invalidate();

      result = tester.eval(/* keepGoing= */ false, topKey);
      assertThat(result.get(topKey)).isEqualTo(new StringValue(expected.toString()));
    }
  }

  /**
   * Tests scenario where we have dirty values in the graph, and then one of them is deleted since
   * its evaluation did not complete before an error was thrown. Can either test the graph via an
   * evaluation of that deleted value, or an invalidation of a child, and can either remove the
   * thrown error or throw it again on that evaluation.
   */
  private void dirtyValueChildrenProperlyRemovedOnEarlyBuildAbort(
      boolean reevaluateMissingValue, boolean removeError) throws Exception {
    SkyKey errorKey = nonHermeticKey("error");
    tester.set(errorKey, new StringValue("biding time"));
    SkyKey slowKey = nonHermeticKey("slow");
    tester.set(slowKey, new StringValue("slow"));
    SkyKey midKey = skyKey("mid");
    tester.getOrCreate(midKey).addDependency(slowKey).setComputedValue(COPY);
    SkyKey lastKey = nonHermeticKey("last");
    tester.set(lastKey, new StringValue("last"));
    SkyKey motherKey = skyKey("mother");
    tester
        .getOrCreate(motherKey)
        .addDependency(errorKey)
        .addDependency(midKey)
        .addDependency(lastKey)
        .setComputedValue(CONCATENATE);
    SkyKey fatherKey = skyKey("father");
    tester
        .getOrCreate(fatherKey)
        .addDependency(errorKey)
        .addDependency(midKey)
        .addDependency(lastKey)
        .setComputedValue(CONCATENATE);
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, motherKey, fatherKey);
    assertThat(result.get(motherKey).getValue()).isEqualTo("biding timeslowlast");
    assertThat(result.get(fatherKey).getValue()).isEqualTo("biding timeslowlast");
    tester.set(slowKey, null);
    // Each parent depends on errorKey, midKey, lastKey. We keep slowKey waiting until errorKey is
    // finished. So there is no way lastKey can be enqueued by either parent. Thus, the parent that
    // is cleaned has not interacted with lastKey this build. Still, lastKey's reverse dep on that
    // parent should be removed.
    CountDownLatch errorFinish = new CountDownLatch(1);
    tester.set(errorKey, null);
    tester
        .getOrCreate(errorKey)
        .setBuilder(
            new ChainedFunction(
                /*notifyStart=*/ null,
                /*waitToFinish=*/ null,
                /*notifyFinish=*/ errorFinish,
                /*waitForException=*/ false,
                /*value=*/ null,
                /*deps=*/ ImmutableList.of()));
    tester
        .getOrCreate(slowKey)
        .setBuilder(
            new ChainedFunction(
                /*notifyStart=*/ null,
                /*waitToFinish=*/ errorFinish,
                /*notifyFinish=*/ null,
                /*waitForException=*/ true,
                new StringValue("leaf2"),
                /*deps=*/ ImmutableList.of()));
    tester.invalidate();
    // errorKey finishes, written to graph -> leafKey maybe starts+finishes & (Visitor aborts)
    // -> one of mother or father builds. The other one should be cleaned, and no references to it
    // left in the graph.
    result = tester.eval(/*keepGoing=*/ false, motherKey, fatherKey);
    assertThat(result.hasError()).isTrue();
    // Only one of mother or father should be in the graph.
    assertWithMessage(result.getError(motherKey) + ", " + result.getError(fatherKey))
        .that((result.getError(motherKey) == null) != (result.getError(fatherKey) == null))
        .isTrue();
    SkyKey parentKey =
        (reevaluateMissingValue == (result.getError(motherKey) == null)) ? motherKey : fatherKey;
    // Give slowKey a nice ordinary builder.
    tester
        .getOrCreate(slowKey, /*markAsModified=*/ false)
        .setBuilder(null)
        .setConstantValue(new StringValue("leaf2"));
    if (removeError) {
      tester
          .getOrCreate(errorKey, /*markAsModified=*/ true)
          .setBuilder(null)
          .setConstantValue(new StringValue("reformed"));
    }
    String lastString = "last";
    if (!reevaluateMissingValue) {
      // Mark the last key modified if we're not trying the absent value again. This invalidation
      // will test if lastKey still has a reference to the absent value.
      lastString = "last2";
      tester.set(lastKey, new StringValue(lastString));
    }
    tester.invalidate();
    result = tester.eval(/*keepGoing=*/ false, parentKey);
    if (removeError) {
      assertThat(result.get(parentKey).getValue()).isEqualTo("reformedleaf2" + lastString);
    } else {
      assertThat(result.getError(parentKey)).isNotNull();
    }
  }

  /**
   * The following four tests (dirtyChildrenProperlyRemovedWith*) test the consistency of the graph
   * after a failed build in which a dirty value should have been deleted from the graph. The
   * consistency is tested via either evaluating the missing value, or the re-evaluating the present
   * value, and either clearing the error or keeping it. To evaluate the present value, we
   * invalidate the error value to force re-evaluation. Related to bug "skyframe m1: graph may not
   * be properly cleaned on interrupt or failure".
   */
  @Test
  public void dirtyChildrenProperlyRemovedWithInvalidateRemoveError() throws Exception {
    dirtyValueChildrenProperlyRemovedOnEarlyBuildAbort(
        /*reevaluateMissingValue=*/ false, /*removeError=*/ true);
  }

  @Test
  public void dirtyChildrenProperlyRemovedWithInvalidateKeepError() throws Exception {
    dirtyValueChildrenProperlyRemovedOnEarlyBuildAbort(
        /*reevaluateMissingValue=*/ false, /*removeError=*/ false);
  }

  @Test
  public void dirtyChildrenProperlyRemovedWithReevaluateRemoveError() throws Exception {
    dirtyValueChildrenProperlyRemovedOnEarlyBuildAbort(
        /*reevaluateMissingValue=*/ true, /*removeError=*/ true);
  }

  @Test
  public void dirtyChildrenProperlyRemovedWithReevaluateKeepError() throws Exception {
    dirtyValueChildrenProperlyRemovedOnEarlyBuildAbort(
        /*reevaluateMissingValue=*/ true, /*removeError=*/ false);
  }

  /**
   * Regression test: enqueue so many values that some of them won't have started processing, and
   * then either interrupt processing or have a child throw an error. In the latter case, this also
   * tests that a value that hasn't started processing can still have a child error bubble up to it.
   * In both cases, it tests that the graph is properly cleaned of the dirty values and references
   * to them.
   */
  private void manyDirtyValuesClearChildrenOnFail(boolean interrupt) throws Exception {
    SkyKey leafKey = nonHermeticKey("leaf");
    tester.set(leafKey, new StringValue("leafy"));
    SkyKey lastKey = nonHermeticKey("last");
    tester.set(lastKey, new StringValue("last"));
    List<SkyKey> tops = new ArrayList<>();
    // Request far more top-level values than there are threads, so some of them will block until
    // the
    // leaf child is enqueued for processing.
    for (int i = 0; i < 10000; i++) {
      SkyKey topKey = skyKey("top" + i);
      tester
          .getOrCreate(topKey)
          .addDependency(leafKey)
          .addDependency(lastKey)
          .setComputedValue(CONCATENATE);
      tops.add(topKey);
    }
    tester.eval(/*keepGoing=*/ false, tops.toArray(new SkyKey[0]));
    CountDownLatch notifyStart = new CountDownLatch(1);
    tester.set(leafKey, null);
    if (interrupt) {
      // leaf will wait for an interrupt if desired. We cannot use the usual ChainedFunction
      // because we need to actually throw the interrupt.
      AtomicBoolean shouldSleep = new AtomicBoolean(true);
      tester
          .getOrCreate(leafKey, /*markAsModified=*/ true)
          .setBuilder(
              (skyKey, env) -> {
                notifyStart.countDown();
                if (shouldSleep.get()) {
                  // Should be interrupted within 5 seconds.
                  Thread.sleep(5000);
                  throw new AssertionError("leaf was not interrupted");
                }
                return new StringValue("crunchy");
              });
      tester.invalidate();
      TestThread evalThread =
          new TestThread(
              () ->
                  assertThrows(
                      InterruptedException.class,
                      () -> tester.eval(/*keepGoing=*/ false, tops.toArray(new SkyKey[0]))));
      evalThread.start();
      assertThat(notifyStart.await(TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      evalThread.interrupt();
      evalThread.joinAndAssertState(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
      // Free leafKey to compute next time.
      shouldSleep.set(false);
    } else {
      // Non-interrupt case. Just throw an error in the child.
      tester.getOrCreate(leafKey, /*markAsModified=*/ true).setHasError(true);
      tester.invalidate();
      // The error thrown may non-deterministically bubble up to a parent that has not yet started
      // processing, but has been enqueued for processing.
      tester.eval(/*keepGoing=*/ false, tops.toArray(new SkyKey[0]));
      tester.getOrCreate(leafKey, /*markAsModified=*/ true).setHasError(false);
      tester.set(leafKey, new StringValue("crunchy"));
    }
    // lastKey was not touched during the previous build, but its reverse deps on its parents should
    // still be accurate.
    tester.set(lastKey, new StringValue("new last"));
    tester.invalidate();
    EvaluationResult<StringValue> result =
        tester.eval(/*keepGoing=*/ false, tops.toArray(new SkyKey[0]));
    for (SkyKey topKey : tops) {
      assertWithMessage(topKey.toString())
          .that(result.get(topKey).getValue())
          .isEqualTo("crunchynew last");
    }
  }

  /**
   * Regression test: make sure that if an evaluation fails before a dirty value starts evaluation
   * (in particular, before it is reset), the graph remains consistent.
   */
  @Test
  public void manyDirtyValuesClearChildrenOnError() throws Exception {
    manyDirtyValuesClearChildrenOnFail(/*interrupt=*/ false);
  }

  /**
   * Regression test: Make sure that if an evaluation is interrupted before a dirty value starts
   * evaluation (in particular, before it is reset), the graph remains consistent.
   */
  @Test
  public void manyDirtyValuesClearChildrenOnInterrupt() throws Exception {
    manyDirtyValuesClearChildrenOnFail(/*interrupt=*/ true);
  }

  private SkyKey makeTestKey(SkyKey node0) {
    SkyKey key = null;
    // Create a long chain of nodes. Most of them will not actually be dirtied, but the last one to
    // be dirtied will enqueue its parent for dirtying, so it will be in the queue for the next run.
    for (int i = 0; i < TEST_NODE_COUNT; i++) {
      key = i == 0 ? node0 : skyKey("node" + i);
      if (i > 1) {
        tester.getOrCreate(key).addDependency("node" + (i - 1)).setComputedValue(COPY);
      } else if (i == 1) {
        tester.getOrCreate(key).addDependency(node0).setComputedValue(COPY);
      } else {
        tester.set(key, new StringValue("node0"));
      }
    }
    return key;
  }

  /**
   * Regression test for case where the user requests that we delete nodes that are already in the
   * queue to be dirtied. We should handle that gracefully and not complain.
   */
  @Test
  public void deletingDirtyNodes() throws Exception {
    SkyKey node0 = nonHermeticKey("node0");
    SkyKey key = makeTestKey(node0);
    // Seed the graph.
    assertThat(((StringValue) tester.evalAndGet(/*keepGoing=*/ false, key)).getValue())
        .isEqualTo("node0");
    // Start the dirtying process.
    tester.set(node0, new StringValue("new"));
    tester.invalidate();

    // Interrupt current thread on a next invalidate call
    Thread thread = Thread.currentThread();
    tester.progressReceiver.setNextInvalidationCallback(thread::interrupt);

    assertThrows(InterruptedException.class, () -> tester.eval(/*keepGoing=*/ false, key));

    // Cleanup + paranoid check
    tester.progressReceiver.setNextInvalidationCallback(null);
    // Now delete all the nodes. The node that was going to be dirtied is also deleted, which we
    // should handle.
    tester.evaluator.delete(Predicates.alwaysTrue());
    assertThat(((StringValue) tester.evalAndGet(/*keepGoing=*/ false, key)).getValue())
        .isEqualTo("new");
  }

  @Test
  public void changePruning() throws Exception {
    SkyKey leaf = nonHermeticKey("leaf");
    SkyKey mid = skyKey("mid");
    SkyKey top = skyKey("top");
    tester.getOrCreate(top).addDependency(mid).setComputedValue(COPY);
    tester.getOrCreate(mid).addDependency(leaf).setComputedValue(COPY);
    tester.set(leaf, new StringValue("leafy"));
    StringValue topValue = (StringValue) tester.evalAndGet("top");
    assertThat(topValue.getValue()).isEqualTo("leafy");
    // Mark leaf changed, but don't actually change it.
    tester.getOrCreate(leaf, /* markAsModified= */ true);
    // mid will give an error if re-evaluated, but it shouldn't be because it is not marked changed,
    // and its dirty child will evaluate to the same element.
    tester.getOrCreate(mid, /* markAsModified= */ false).setHasError(true);
    tester.invalidate();
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, top);
    assertThat(result.hasError()).isFalse();
    topValue = result.get(top);
    assertThat(topValue.getValue()).isEqualTo("leafy");
    assertThat(tester.getDirtyKeys()).isEmpty();
    assertThat(tester.getDeletedKeys()).isEmpty();
  }

  @Test
  public void changePruningWithDoneValue() throws Exception {
    SkyKey leaf = nonHermeticKey("leaf");
    SkyKey mid = skyKey("mid");
    SkyKey top = skyKey("top");
    SkyKey suffix = skyKey("suffix");
    StringValue suffixValue = new StringValue("suffix");
    tester.set(suffix, suffixValue);
    tester.getOrCreate(top).addDependency(mid).addDependency(suffix).setComputedValue(CONCATENATE);
    tester.getOrCreate(mid).addDependency(leaf).addDependency(suffix).setComputedValue(CONCATENATE);
    SkyValue leafyValue = new StringValue("leafy");
    tester.set(leaf, leafyValue);
    StringValue value = (StringValue) tester.evalAndGet("top");
    assertThat(value.getValue()).isEqualTo("leafysuffixsuffix");
    // Mark leaf changed, but don't actually change it.
    tester.getOrCreate(leaf, /* markAsModified= */ true);
    // mid will give an error if re-evaluated, but it shouldn't be because it is not marked changed,
    // and its dirty child will evaluate to the same element.
    tester.getOrCreate(mid, /*markAsModified=*/ false).setHasError(true);
    tester.invalidate();
    value = (StringValue) tester.evalAndGet(/*keepGoing=*/ false, leaf);
    assertThat(value.getValue()).isEqualTo("leafy");
    assertThat(tester.getDirtyKeys()).containsExactly(mid, top);
    assertThat(tester.getDeletedKeys()).isEmpty();
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, top);
    assertWithMessage(result.toString()).that(result.hasError()).isFalse();
    value = result.get(top);
    assertThat(value.getValue()).isEqualTo("leafysuffixsuffix");
    assertThat(tester.getDirtyKeys()).isEmpty();
    assertThat(tester.getDeletedKeys()).isEmpty();
  }

  @Test
  public void changePruningAfterParentPrunes() throws Exception {
    SkyKey leaf = nonHermeticKey("leaf");
    SkyKey top = skyKey("top");
    tester.set(leaf, new StringValue("leafy"));
    // When top depends on leaf, but always returns the same value,
    StringValue fixedTopValue = new StringValue("top");
    AtomicBoolean topEvaluated = new AtomicBoolean(false);
    tester
        .getOrCreate(top)
        .setBuilder(
            (skyKey, env) -> {
              topEvaluated.set(true);
              return env.getValue(leaf) == null ? null : fixedTopValue;
            });
    // And top is evaluated,
    StringValue topValue = (StringValue) tester.evalAndGet("top");
    // Then top's value is as expected,
    assertThat(topValue).isEqualTo(fixedTopValue);
    // And top was actually evaluated.
    assertThat(topEvaluated.get()).isTrue();
    // When leaf is changed,
    tester.set(leaf, new StringValue("crunchy"));
    tester.invalidate();
    topEvaluated.set(false);
    // And top is evaluated,
    StringValue topValue2 = (StringValue) tester.evalAndGet("top");
    // Then top's value is as expected,
    assertThat(topValue2).isEqualTo(fixedTopValue);
    // And top was actually evaluated.
    assertThat(topEvaluated.get()).isTrue();
    // When leaf is invalidated but not actually changed,
    tester.getOrCreate(leaf, /*markAsModified=*/ true);
    tester.invalidate();
    topEvaluated.set(false);
    // And top is evaluated,
    StringValue topValue3 = (StringValue) tester.evalAndGet("top");
    // Then top's value is as expected,
    assertThat(topValue3).isEqualTo(fixedTopValue);
    // And top was *not* actually evaluated, because change pruning cut off evaluation.
    assertThat(topEvaluated.get()).isFalse();
  }

  @Test
  public void changePruningFromOtherNodeAfterParentPrunes() throws Exception {
    SkyKey leaf = nonHermeticKey("leaf");
    SkyKey other = nonHermeticKey("other");
    SkyKey top = skyKey("top");
    tester.set(leaf, new StringValue("leafy"));
    tester.set(other, new StringValue("other"));
    // When top depends on leaf and other, but always returns the same value,
    StringValue fixedTopValue = new StringValue("top");
    AtomicBoolean topEvaluated = new AtomicBoolean(false);
    tester
        .getOrCreate(top)
        .setBuilder(
            (skyKey, env) -> {
              topEvaluated.set(true);

              return env.getValue(other) == null || env.getValue(leaf) == null
                  ? null
                  : fixedTopValue;
            });
    // And top is evaluated,
    StringValue topValue = (StringValue) tester.evalAndGet("top");
    // Then top's value is as expected,
    assertThat(topValue).isEqualTo(fixedTopValue);
    // And top was actually evaluated.
    assertThat(topEvaluated.get()).isTrue();
    // When leaf is changed,
    tester.set(leaf, new StringValue("crunchy"));
    tester.invalidate();
    topEvaluated.set(false);
    // And top is evaluated,
    StringValue topValue2 = (StringValue) tester.evalAndGet("top");
    // Then top's value is as expected,
    assertThat(topValue2).isEqualTo(fixedTopValue);
    // And top was actually evaluated.
    assertThat(topEvaluated.get()).isTrue();
    // When other is invalidated but not actually changed,
    tester.getOrCreate(other, /*markAsModified=*/ true);
    tester.invalidate();
    topEvaluated.set(false);
    // And top is evaluated,
    StringValue topValue3 = (StringValue) tester.evalAndGet("top");
    // Then top's value is as expected,
    assertThat(topValue3).isEqualTo(fixedTopValue);
    // And top was *not* actually evaluated, because change pruning cut off evaluation.
    assertThat(topEvaluated.get()).isFalse();
  }

  @Test
  public void changedChildChangesDepOfParent() throws Exception {
    SkyKey buildFile = nonHermeticKey("buildFile");
    ValueComputer authorDrink =
        (deps, env) -> {
          String author = ((StringValue) deps.get(buildFile)).getValue();
          StringValue beverage;
          switch (author) {
            case "hemingway":
              beverage = (StringValue) env.getValue(skyKey("absinthe"));
              break;
            case "joyce":
              beverage = (StringValue) env.getValue(skyKey("whiskey"));
              break;
            default:
              throw new IllegalStateException(author);
          }
          if (beverage == null) {
            return null;
          }
          return new StringValue(author + " drank " + beverage.getValue());
        };

    tester.set(buildFile, new StringValue("hemingway"));
    SkyKey absinthe = skyKey("absinthe");
    tester.set(absinthe, new StringValue("absinthe"));
    SkyKey whiskey = skyKey("whiskey");
    tester.set(whiskey, new StringValue("whiskey"));
    SkyKey top = skyKey("top");
    tester.getOrCreate(top).addDependency(buildFile).setComputedValue(authorDrink);
    StringValue topValue = (StringValue) tester.evalAndGet("top");
    assertThat(topValue.getValue()).isEqualTo("hemingway drank absinthe");
    tester.set(buildFile, new StringValue("joyce"));
    // Don't evaluate absinthe successfully anymore.
    tester.getOrCreate(absinthe, /*markAsModified=*/ false).setHasError(true);
    tester.invalidate();
    topValue = (StringValue) tester.evalAndGet("top");
    assertThat(topValue.getValue()).isEqualTo("joyce drank whiskey");
      assertThat(tester.getDirtyKeys()).containsExactly(buildFile, top);
      assertThat(tester.getDeletedKeys()).isEmpty();
  }

  @Test
  public void dirtyDepIgnoresChildren() throws Exception {
    SkyKey leaf = nonHermeticKey("leaf");
    SkyKey mid = skyKey("mid");
    SkyKey top = skyKey("top");
    tester.set(mid, new StringValue("ignore"));
    tester.getOrCreate(top).addDependency(mid).setComputedValue(COPY);
    tester.getOrCreate(mid).addDependency(leaf);
    tester.set(leaf, new StringValue("leafy"));
    StringValue topValue = (StringValue) tester.evalAndGet("top");
    assertThat(topValue.getValue()).isEqualTo("ignore");
    assertThat(tester.getDirtyKeys()).isEmpty();
    assertThat(tester.getDeletedKeys()).isEmpty();
    // Change leaf.
    tester.set(leaf, new StringValue("crunchy"));
    tester.invalidate();
    topValue = (StringValue) tester.evalAndGet("top");
    assertThat(topValue.getValue()).isEqualTo("ignore");
      assertThat(tester.getDirtyKeys()).containsExactly(leaf);
      assertThat(tester.getDeletedKeys()).isEmpty();
    tester.set(leaf, new StringValue("smushy"));
    tester.invalidate();
    topValue = (StringValue) tester.evalAndGet("top");
    assertThat(topValue.getValue()).isEqualTo("ignore");
      assertThat(tester.getDirtyKeys()).containsExactly(leaf);
      assertThat(tester.getDeletedKeys()).isEmpty();
  }

  private static final SkyFunction INTERRUPT_BUILDER =
      (skyKey, env) -> {
        throw new InterruptedException();
      };

  /**
   * Utility function to induce a graph clean of whatever value is requested, by trying to build
   * this value and interrupting the build as soon as this value's function evaluation starts.
   */
  private void failBuildAndRemoveValue(SkyKey value) throws InterruptedException {
    tester.set(value, null);
    // Evaluator will think leaf was interrupted because it threw, so it will be cleaned from graph.
    tester.getOrCreate(value, /* markAsModified= */ true).setBuilder(INTERRUPT_BUILDER);
    tester.invalidate();
    assertThrows(InterruptedException.class, () -> tester.eval(/* keepGoing= */ false, value));
    tester.getOrCreate(value, /* markAsModified= */ false).setBuilder(null);
  }

  /**
   * Make sure that when a dirty value is building, the fact that a child may no longer exist in the
   * graph doesn't cause problems.
   */
  @Test
  public void dirtyBuildAfterFailedBuild() throws Exception {
    SkyKey leaf = nonHermeticKey("leaf");
    SkyKey top = skyKey("top");
    tester.getOrCreate(top).addDependency(leaf).setComputedValue(COPY);
    tester.set(leaf, new StringValue("leafy"));
    StringValue topValue = (StringValue) tester.evalAndGet("top");
    assertThat(topValue.getValue()).isEqualTo("leafy");
    assertThat(tester.getDirtyKeys()).isEmpty();
    assertThat(tester.getDeletedKeys()).isEmpty();
    failBuildAndRemoveValue(leaf);
    // Leaf should no longer exist in the graph. Check that this doesn't cause problems.
    tester.set(leaf, null);
    tester.set(leaf, new StringValue("crunchy"));
    tester.invalidate();
    topValue = (StringValue) tester.evalAndGet("top");
    assertThat(topValue.getValue()).isEqualTo("crunchy");
  }

  /**
   * Regression test: error when clearing reverse deps on dirty value about to be rebuilt, because
   * child values were deleted and recreated in interim, forgetting they had reverse dep on dirty
   * value in the first place.
   */
  @Test
  public void changedBuildAfterFailedThenSuccessfulBuild() throws Exception {
    SkyKey leaf = nonHermeticKey("leaf");
    SkyKey top = nonHermeticKey("top");
    tester.getOrCreate(top).addDependency(leaf).setComputedValue(COPY);
    tester.set(leaf, new StringValue("leafy"));
    StringValue topValue = (StringValue) tester.evalAndGet(/* keepGoing= */ false, top);
    assertThat(topValue.getValue()).isEqualTo("leafy");
    assertThat(tester.getDirtyKeys()).isEmpty();
    assertThat(tester.getDeletedKeys()).isEmpty();
    failBuildAndRemoveValue(leaf);
    tester.set(leaf, new StringValue("crunchy"));
    tester.invalidate();
    tester.eval(/* keepGoing= */ false, leaf);
    // Leaf no longer has reverse dep on top. Check that this doesn't cause problems, even if the
    // top value is evaluated unconditionally.
    tester.getOrCreate(top, /*markAsModified=*/ true);
    tester.invalidate();
    topValue = (StringValue) tester.evalAndGet(/*keepGoing=*/ false, top);
    assertThat(topValue.getValue()).isEqualTo("crunchy");
  }

  /**
   * Regression test: child value that has been deleted since it and its parent were marked dirty no
   * longer knows it has a reverse dep on its parent.
   *
   * <p>Start with:
   *
   * <pre>
   *              top0  ... top1000
   *                  \  | /
   *                   leaf
   * </pre>
   *
   * Then fail to build leaf. Now the entry for leaf should have no "memory" that it was ever
   * depended on by tops. Now build tops, but fail again.
   */
  @Test
  public void manyDirtyValuesClearChildrenOnSecondFail() throws Exception {
    SkyKey leafKey = nonHermeticKey("leaf");
    tester.set(leafKey, new StringValue("leafy"));
    SkyKey lastKey = skyKey("last");
    tester.set(lastKey, new StringValue("last"));
    List<SkyKey> tops = new ArrayList<>();
    // Request far more top-level values than there are threads, so some of them will block until
    // the leaf child is enqueued for processing.
    for (int i = 0; i < 10000; i++) {
      SkyKey topKey = skyKey("top" + i);
      tester
          .getOrCreate(topKey)
          .addDependency(leafKey)
          .addDependency(lastKey)
          .setComputedValue(CONCATENATE);
      tops.add(topKey);
    }
    tester.eval(/*keepGoing=*/ false, tops.toArray(new SkyKey[0]));
    failBuildAndRemoveValue(leafKey);
    // Request the tops. Since leaf was deleted from the graph last build, it no longer knows that
    // its parents depend on it. When leaf throws, at least one of its parents (hopefully) will not
    // have re-informed leaf that the parent depends on it, exposing the bug, since the parent
    // should then not try to clean the reverse dep from leaf.
    tester.set(leafKey, null);
    // Evaluator will think leaf was interrupted because it threw, so it will be cleaned from graph.
    tester.getOrCreate(leafKey, /*markAsModified=*/ true).setBuilder(INTERRUPT_BUILDER);
    tester.invalidate();
    assertThrows(
        InterruptedException.class,
        () -> tester.eval(/*keepGoing=*/ false, tops.toArray(new SkyKey[0])));
  }

  @Test
  public void failedDirtyBuild() throws Exception {
    SkyKey leaf = nonHermeticKey("leaf");
    SkyKey top = skyKey("top");
    tester
        .getOrCreate(top)
        .addErrorDependency(leaf, new StringValue("recover"))
        .setComputedValue(COPY);
    tester.set(leaf, new StringValue("leafy"));
    StringValue topValue = (StringValue) tester.evalAndGet("top");
    assertThat(topValue.getValue()).isEqualTo("leafy");
    assertThat(tester.getDirtyKeys()).isEmpty();
    assertThat(tester.getDeletedKeys()).isEmpty();
    // Change leaf.
    tester.getOrCreate(leaf, /* markAsModified= */ true).setHasError(true);
    tester.getOrCreate(top, /* markAsModified= */ false).setHasError(true);
    tester.invalidate();
    EvaluationResult<StringValue> result = tester.eval(/* keepGoing= */ false, top);
    assertThatEvaluationResult(result).hasSingletonErrorThat(top);
  }

  @Test
  public void failedDirtyBuildInBuilder() throws Exception {
    SkyKey leaf = nonHermeticKey("leaf");
    SkyKey secondError = nonHermeticKey("secondError");
    SkyKey top = skyKey("top");
    tester
        .getOrCreate(top)
        .addDependency(leaf)
        .addErrorDependency(secondError, new StringValue("recover"))
        .setComputedValue(CONCATENATE);
    tester.set(secondError, new StringValue("secondError")).addDependency(leaf);
    tester.set(leaf, new StringValue("leafy"));
    StringValue topValue = (StringValue) tester.evalAndGet("top");
    assertThat(topValue.getValue()).isEqualTo("leafysecondError");
    assertThat(tester.getDirtyKeys()).isEmpty();
    assertThat(tester.getDeletedKeys()).isEmpty();
    // Invalidate leaf.
    tester.getOrCreate(leaf, /*markAsModified=*/ true);
    tester.set(leaf, new StringValue("crunchy"));
    tester.getOrCreate(secondError, /*markAsModified=*/ true).setHasError(true);
    tester.getOrCreate(top, /*markAsModified=*/ false).setHasError(true);
    tester.invalidate();
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, top);
    assertThatEvaluationResult(result).hasSingletonErrorThat(top);
  }

  @Test
  public void dirtyErrorTransienceValue() throws Exception {
    initializeTester();
    SkyKey error = skyKey("error");
    tester.getOrCreate(error).setHasError(true);
    assertThat(tester.evalAndGetError(/*keepGoing=*/ true, error)).isNotNull();
    tester.invalidateTransientErrors();
    SkyKey secondError = skyKey("secondError");
    tester.getOrCreate(secondError).setHasError(true);
    // secondError declares a new dependence on ErrorTransienceValue, but not until it has already
    // thrown an error.
    assertThat(tester.evalAndGetError(/*keepGoing=*/ true, secondError)).isNotNull();
  }

  @Test
  public void dirtyDependsOnErrorTurningGood() throws Exception {
    SkyKey error = nonHermeticKey("error");
    tester.getOrCreate(error).setHasError(true);
    SkyKey topKey = skyKey("top");
    tester.getOrCreate(topKey).addDependency(error).setComputedValue(COPY);
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, topKey);
    assertThatEvaluationResult(result).hasSingletonErrorThat(topKey);
    tester.getOrCreate(error).setHasError(false);
    StringValue val = new StringValue("reformed");
    tester.set(error, val);
    tester.invalidate();
    result = tester.eval(/*keepGoing=*/ false, topKey);
    assertThatEvaluationResult(result).hasEntryThat(topKey).isEqualTo(val);
    assertThatEvaluationResult(result).hasNoError();
  }

  /** Regression test for crash bug. */
  @Test
  public void dirtyWithOwnErrorDependsOnTransientErrorTurningGood() throws Exception {
    SkyKey error = nonHermeticKey("error");
    tester.getOrCreate(error).setHasTransientError(true);
    SkyKey topKey = skyKey("top");
    SkyFunction errorFunction =
        (skyKey, env) -> {
          try {
            return env.getValueOrThrow(error, SomeErrorException.class);
          } catch (SomeErrorException e) {
            throw new GenericFunctionException(e, Transience.PERSISTENT);
          }
        };
    tester.getOrCreate(topKey).setBuilder(errorFunction);
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, topKey);
    tester.invalidateTransientErrors();
    assertThatEvaluationResult(result).hasSingletonErrorThat(topKey);
    tester.getOrCreate(error).setHasTransientError(false);
    StringValue reformed = new StringValue("reformed");
    tester.set(error, reformed);
    tester
        .getOrCreate(topKey, /*markAsModified=*/ false)
        .setBuilder(null)
        .addDependency(error)
        .setComputedValue(COPY);
    tester.invalidate();
    tester.invalidateTransientErrors();
    result = tester.eval(/*keepGoing=*/ false, topKey);
    assertThatEvaluationResult(result).hasEntryThat(topKey).isEqualTo(reformed);
    assertThatEvaluationResult(result).hasNoError();
  }

  /**
   * Make sure that when an error is thrown, it is given for handling only to parents that have
   * already registered a dependence on the value that threw the error.
   *
   * <pre>
   *  topBubbleKey  topErrorFirstKey
   *    |       \    /
   *  midKey  errorKey
   *    |
   * slowKey
   * </pre>
   *
   * On the second build, errorKey throws, and the threadpool aborts before midKey finishes.
   * topBubbleKey therefore has not yet requested errorKey this build. If errorKey bubbles up to it,
   * topBubbleKey must be able to handle that. (The evaluator can deal with this either by not
   * allowing errorKey to bubble up to topBubbleKey, or by dealing with that case.)
   */
  @Test
  public void errorOnlyBubblesToRequestingParents() throws Exception {
    // We need control over the order of reverse deps, so use a deterministic graph.
    makeGraphDeterministic();
    SkyKey errorKey = nonHermeticKey("error");
    tester.set(errorKey, new StringValue("biding time"));
    SkyKey slowKey = nonHermeticKey("slow");
    tester.set(slowKey, new StringValue("slow"));
    SkyKey midKey = skyKey("mid");
    tester.getOrCreate(midKey).addDependency(slowKey).setComputedValue(COPY);
    SkyKey topErrorFirstKey = skyKey("2nd top alphabetically");
    tester.getOrCreate(topErrorFirstKey).addDependency(errorKey).setComputedValue(CONCATENATE);
    SkyKey topBubbleKey = skyKey("1st top alphabetically");
    tester
        .getOrCreate(topBubbleKey)
        .addDependency(midKey)
        .addDependency(errorKey)
        .setComputedValue(CONCATENATE);
    // First error-free evaluation, to put all values in graph.
    EvaluationResult<StringValue> result =
        tester.eval(/*keepGoing=*/ false, topErrorFirstKey, topBubbleKey);
    assertThat(result.get(topErrorFirstKey).getValue()).isEqualTo("biding time");
    assertThat(result.get(topBubbleKey).getValue()).isEqualTo("slowbiding time");
    // Set up timing of child values: slowKey waits to finish until errorKey has thrown an
    // exception that has been caught by the threadpool.
    tester.set(slowKey, null);
    CountDownLatch errorFinish = new CountDownLatch(1);
    tester.set(errorKey, null);
    tester
        .getOrCreate(errorKey)
        .setBuilder(
            new ChainedFunction(
                /*notifyStart=*/ null,
                /*waitToFinish=*/ null,
                /*notifyFinish=*/ errorFinish,
                /*waitForException=*/ false,
                /*value=*/ null,
                /*deps=*/ ImmutableList.of()));
    tester
        .getOrCreate(slowKey)
        .setBuilder(
            new ChainedFunction(
                /*notifyStart=*/ null,
                /*waitToFinish=*/ errorFinish,
                /*notifyFinish=*/ null,
                /*waitForException=*/ true,
                new StringValue("leaf2"),
                /*deps=*/ ImmutableList.of()));
    tester.invalidate();
    // errorKey finishes, written to graph -> slowKey maybe starts+finishes & (Visitor aborts)
    // -> some top key builds.
    result = tester.eval(/*keepGoing=*/ false, topErrorFirstKey, topBubbleKey);
    assertThat(result.hasError()).isTrue();
    assertWithMessage(result.toString()).that(result.getError(topErrorFirstKey)).isNotNull();
  }

  @Test
  public void dirtyWithRecoveryErrorDependsOnErrorTurningGood() throws Exception {
    SkyKey error = nonHermeticKey("error");
    tester.getOrCreate(error).setHasError(true);
    SkyKey topKey = skyKey("top");
    SkyFunction recoveryErrorFunction =
        (skyKey, env) -> {
          try {
            env.getValueOrThrow(error, SomeErrorException.class);
          } catch (SomeErrorException e) {
            throw new GenericFunctionException(e, Transience.PERSISTENT);
          }
          return null;
        };
    tester.getOrCreate(topKey).setBuilder(recoveryErrorFunction);
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, topKey);
    assertThatEvaluationResult(result).hasSingletonErrorThat(topKey);
    tester.getOrCreate(error).setHasError(false);
    StringValue reformed = new StringValue("reformed");
    tester.set(error, reformed);
    tester.getOrCreate(topKey).setBuilder(null).addDependency(error).setComputedValue(COPY);
    tester.invalidate();
    result = tester.eval(/*keepGoing=*/ false, topKey);
    assertThatEvaluationResult(result).hasEntryThat(topKey).isEqualTo(reformed);
    assertThatEvaluationResult(result).hasNoError();
  }

  /**
   * Similar to {@link ParallelEvaluatorTest#errorTwoLevelsDeep}, except here we request multiple
   * toplevel values.
   */
  @Test
  public void errorPropagationToTopLevelValues() throws Exception {
    SkyKey topKey = skyKey("top");
    SkyKey midKey = skyKey("mid");
    SkyKey badKey = skyKey("bad");
    tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(CONCATENATE);
    tester.getOrCreate(midKey).addDependency(badKey).setComputedValue(CONCATENATE);
    tester.getOrCreate(badKey).setHasError(true);
    EvaluationResult<SkyValue> result = tester.eval(/* keepGoing= */ false, topKey, midKey);
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(midKey);
    // Do it again with keepGoing.  We should also see an error for the top key this time.
    result = tester.eval(/* keepGoing= */ true, topKey, midKey);
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(midKey);
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(topKey);
  }

  @Test
  public void breakWithInterruptibleErrorDep() throws Exception {
    SkyKey errorKey = skyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    SkyKey parentKey = skyKey("parent");
    tester
        .getOrCreate(parentKey)
        .addErrorDependency(errorKey, new StringValue("recovered"))
        .setComputedValue(CONCATENATE);
    // When the error value throws, the propagation will cause an interrupted exception in parent.
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, parentKey);
    assertThat(result.keyNames()).isEmpty();
    assertThatEvaluationResult(result).hasErrorMapThat().hasSize(1);
    assertThatEvaluationResult(result).hasErrorMapThat().containsKey(parentKey);
    assertThat(Thread.interrupted()).isFalse();
    result = tester.eval(/*keepGoing=*/ true, parentKey);
    assertThatEvaluationResult(result).hasNoError();
    assertThatEvaluationResult(result)
        .hasEntryThat(parentKey)
        .isEqualTo(new StringValue("recovered"));
  }

  /**
   * Regression test: "clearing incomplete values on --keep_going build is racy". Tests that if a
   * value is requested on the first (non-keep-going) build and its child throws an error, when the
   * second (keep-going) build runs, there is not a race that keeps it as a reverse dep of its
   * children.
   */
  @Test
  public void raceClearingIncompleteValues() throws Exception {
    // Make sure top is enqueued before mid, to avoid a deadlock.
    SkyKey topKey = skyKey("aatop");
    SkyKey midKey = skyKey("zzmid");
    SkyKey badKey = skyKey("bad");
    AtomicBoolean waitForSecondCall = new AtomicBoolean(false);
    CountDownLatch otherThreadWinning = new CountDownLatch(1);
    AtomicReference<Thread> firstThread = new AtomicReference<>();
    injectGraphListenerForTesting(
        (key, type, order, context) -> {
          if (!waitForSecondCall.get()) {
            return;
          }
          if (key.equals(midKey)) {
            if (type == EventType.CREATE_IF_ABSENT) {
              // The first thread to create midKey will not be the first thread to add a reverse dep
              // to it.
              firstThread.compareAndSet(null, Thread.currentThread());
              return;
            }
            if (type == EventType.ADD_REVERSE_DEP) {
              if (order == Order.BEFORE && Thread.currentThread().equals(firstThread.get())) {
                // If this thread created midKey, block until the other thread adds a dep on it.
                TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                    otherThreadWinning, "other thread didn't pass this one");
              } else if (order == Order.AFTER
                  && !Thread.currentThread().equals(firstThread.get())) {
                // This thread has added a dep. Allow the other thread to proceed.
                otherThreadWinning.countDown();
              }
            }
          }
        },
        /* deterministic= */ true);
    tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(CONCATENATE);
    tester.getOrCreate(midKey).addDependency(badKey).setComputedValue(CONCATENATE);
    tester.getOrCreate(badKey).setHasError(true);
    EvaluationResult<SkyValue> result = tester.eval(/*keepGoing=*/ false, topKey, midKey);
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(midKey);
    waitForSecondCall.set(true);
    result = tester.eval(/*keepGoing=*/ true, topKey, midKey);
    assertThat(firstThread.get()).isNotNull();
    assertThat(otherThreadWinning.getCount()).isEqualTo(0);
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(midKey);
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(topKey);
  }

  @Test
  public void breakWithErrorDep() throws Exception {
    SkyKey errorKey = skyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    tester.set("after", new StringValue("after"));
    SkyKey parentKey = skyKey("parent");
    tester
        .getOrCreate(parentKey)
        .addErrorDependency(errorKey, new StringValue("recovered"))
        .setComputedValue(CONCATENATE)
        .addDependency("after");
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, parentKey);
    assertThatEvaluationResult(result).hasSingletonErrorThat(parentKey);
    result = tester.eval(/*keepGoing=*/ true, parentKey);
    assertThatEvaluationResult(result).hasNoError();
    assertThatEvaluationResult(result)
        .hasEntryThat(parentKey)
        .isEqualTo(new StringValue("recoveredafter"));
  }

  @Test
  public void raceConditionWithNoKeepGoingErrors_InflightError() throws Exception {
    // Given a graph of two nodes, errorKey and otherErrorKey,
    SkyKey errorKey = skyKey("errorKey");
    SkyKey otherErrorKey = skyKey("otherErrorKey");

    CountDownLatch errorCommitted = new CountDownLatch(1);

    CountDownLatch otherStarted = new CountDownLatch(1);

    CountDownLatch otherDone = new CountDownLatch(1);

    AtomicInteger numOtherInvocations = new AtomicInteger(0);
    AtomicReference<String> bogusInvocationMessage = new AtomicReference<>(null);
    AtomicReference<String> nonNullValueMessage = new AtomicReference<>(null);

    tester
        .getOrCreate(errorKey)
        .setBuilder(
            (skyKey, env) -> {
              // Given that errorKey waits for otherErrorKey to begin evaluation before completing
              // its evaluation,
              TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                  otherStarted, "otherErrorKey's SkyFunction didn't start in time.");
              // And given that errorKey throws an error,
              throw new GenericFunctionException(
                  new SomeErrorException("error"), Transience.PERSISTENT);
            });
    tester
        .getOrCreate(otherErrorKey)
        .setBuilder(
            (skyKey, env) -> {
              otherStarted.countDown();
              int invocations = numOtherInvocations.incrementAndGet();
              // And given that otherErrorKey waits for errorKey's error to be committed before
              // trying to get errorKey's value,
              TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                  errorCommitted, "errorKey's error didn't get committed to the graph in time");
              try {
                SkyValue value = env.getValueOrThrow(errorKey, SomeErrorException.class);
                if (value != null) {
                  nonNullValueMessage.set("bogus non-null value " + value);
                }
                if (invocations != 1) {
                  bogusInvocationMessage.set("bogus invocation count: " + invocations);
                }
                otherDone.countDown();
                // And given that otherErrorKey throws an error,
                throw new GenericFunctionException(
                    new SomeErrorException("other"), Transience.PERSISTENT);
              } catch (SomeErrorException e) {
                fail();
                return null;
              }
            });
    injectGraphListenerForTesting(
        (key, type, order, context) -> {
          if (key.equals(errorKey) && type == EventType.SET_VALUE && order == Order.AFTER) {
            errorCommitted.countDown();
            TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                otherDone, "otherErrorKey's SkyFunction didn't finish in time.");
          }
        },
        /*deterministic=*/ false);

    // When the graph is evaluated in noKeepGoing mode,
    EvaluationResult<StringValue> result =
        tester.eval(/*keepGoing=*/ false, errorKey, otherErrorKey);

    // Then the result reports that an error occurred,
    assertThat(result.hasError()).isTrue();

    // And no value is committed for otherErrorKey,
    assertThat(tester.evaluator.getExistingErrorForTesting(otherErrorKey)).isNull();
    assertThat(tester.evaluator.getExistingValue(otherErrorKey)).isNull();

    // And no value was committed for errorKey,
    assertWithMessage(nonNullValueMessage.get()).that(nonNullValueMessage.get()).isNull();

    // And the SkyFunction for otherErrorKey was evaluated exactly once.
    assertThat(numOtherInvocations.get()).isEqualTo(1);
    assertWithMessage(bogusInvocationMessage.get()).that(bogusInvocationMessage.get()).isNull();
  }

  @Test
  public void absentParent() throws Exception {
    SkyKey errorKey = nonHermeticKey("my_error_value");
    tester.set(errorKey, new StringValue("biding time"));
    SkyKey absentParentKey = skyKey("absentParent");
    tester.getOrCreate(absentParentKey).addDependency(errorKey).setComputedValue(CONCATENATE);
    assertThat(tester.evalAndGet(/*keepGoing=*/ false, absentParentKey))
        .isEqualTo(new StringValue("biding time"));
    tester.getOrCreate(errorKey, /*markAsModified=*/ true).setHasError(true);
    SkyKey newParent = skyKey("newParent");
    tester.getOrCreate(newParent).addDependency(errorKey).setComputedValue(CONCATENATE);
    tester.invalidate();
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, newParent);
    assertThatEvaluationResult(result).hasSingletonErrorThat(newParent);
  }

  @Test
  public void notComparableNotPrunedNoEvent() throws Exception {
    checkNotComparableNotPruned(false);
  }

  @Test
  public void notComparableNotPrunedEvent() throws Exception {
    checkNotComparableNotPruned(true);
  }

  private void checkNotComparableNotPruned(boolean hasEvent) throws Exception {
    SkyKey parent = skyKey("parent");
    SkyKey child = nonHermeticKey("child");
    NotComparableStringValue notComparableString = new NotComparableStringValue("not comparable");
    if (hasEvent) {
      tester.getOrCreate(child).setConstantValue(notComparableString).setWarning("shmoop");
    } else {
      tester.getOrCreate(child).setConstantValue(notComparableString);
    }
    AtomicInteger parentEvaluated = new AtomicInteger();
    StringValue val = new StringValue("some val");
    tester
        .getOrCreate(parent)
        .addDependency(child)
        .setComputedValue(
            (deps, env) -> {
              parentEvaluated.incrementAndGet();
              return val;
            });
    assertThat(tester.evalAndGet(/* keepGoing= */ false, parent)).isEqualTo(val);
    assertThat(parentEvaluated.get()).isEqualTo(1);
    if (hasEvent) {
      assertThatEvents(eventCollector).containsExactly("shmoop");
    } else {
      assertThatEvents(eventCollector).isEmpty();
    }
    eventCollector.clear();

    tester.resetPlayedEvents();
    tester.getOrCreate(child, /*markAsModified=*/ true);
    tester.invalidate();
    assertThat(tester.evalAndGet(/* keepGoing= */ false, parent)).isEqualTo(val);
    assertThat(parentEvaluated.get()).isEqualTo(2);
    if (hasEvent) {
      assertThatEvents(eventCollector).containsExactly("shmoop");
    } else {
      assertThatEvents(eventCollector).isEmpty();
    }
  }

  @Test
  public void changePruningWithEvent() throws Exception {
    SkyKey parent = skyKey("parent");
    SkyKey child = nonHermeticKey("child");
    tester.getOrCreate(child).setConstantValue(new StringValue("child")).setWarning("bloop");
    // Restart once because child isn't ready.
    CountDownLatch parentEvaluated = new CountDownLatch(3);
    StringValue parentVal = new StringValue("parent");
    tester
        .getOrCreate(parent)
        .setBuilder(
            new ChainedFunction(
                parentEvaluated, null, null, false, parentVal, ImmutableList.of(child)));
    assertThat(tester.evalAndGet(/* keepGoing= */ false, parent)).isEqualTo(parentVal);
    assertThat(parentEvaluated.getCount()).isEqualTo(1);
    assertThatEvents(eventCollector).containsExactly("bloop");
    eventCollector.clear();
    tester.resetPlayedEvents();
    tester.getOrCreate(child, /*markAsModified=*/ true);
    tester.invalidate();
    assertThat(tester.evalAndGet(/*keepGoing=*/ false, parent)).isEqualTo(parentVal);
    assertThatEvents(eventCollector).containsExactly("bloop");
    assertThat(parentEvaluated.getCount()).isEqualTo(1);
  }

  @Test
  public void changePruningWithIntermittentEvent() throws Exception {
    String parentEvent = "parent_event";
    String waitEvent = "wait_event";
    String childEvent = "child_event";
    SkyKey wait = skyKey("wait_key");
    SkyKey parent = skyKey("parent_key");
    SkyKey child = nonHermeticKey("child_key");
    StringValue parentStringValue = new StringValue("parent_value");
    StringValue waitStringValue = new StringValue("wait_value");
    CountDownLatch parentEvaluated = new CountDownLatch(2);

    reporter =
        new DelegatingEventHandler(reporter) {
          @Override
          public void handle(Event e) {
            super.handle(e);
            // Release the CountDownLatch every time the parent node fires the event
            if (e.getMessage().equals(parentEvent)) {
              parentEvaluated.countDown();
            }
          }
        };

    tester
        .getOrCreate(wait)
        .setBuilder(
            (skyKey, env) -> {
              // Wait for the parent and child actions to complete before computing wait node
              parentEvaluated.await(TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
              assertThatEvents(eventCollector).containsExactly(childEvent, parentEvent);

              env.getListener().handle(Event.progress(waitEvent));
              return waitStringValue;
            });
    tester
        .getOrCreate(child)
        .setConstantValue(new StringValue("child_value"))
        .setWarning(childEvent);
    tester
        .getOrCreate(parent)
        .addDependency(child)
        .setConstantValue(parentStringValue)
        .setErrorEvent(parentEvent);

    assertThat(tester.evalAndGet(/*keepGoing=*/ false, parent)).isEqualTo(parentStringValue);
    assertThatEvents(eventCollector).containsExactly(childEvent, parentEvent);
    assertThat(parentEvaluated.getCount()).isEqualTo(1);

    // Reset the event collector and mark the child as modified without actually changing values
    eventCollector.clear();
    tester.resetPlayedEvents();
    tester.getOrCreate(child, /*markAsModified=*/ true);
    tester.invalidate();

    EvaluationResult<StringValue> result = tester.eval(false, parent, wait);
    assertThat(result.values()).containsExactly(parentStringValue, waitStringValue);

    // These assertions are to check that all events fired at the end of evaluation.
    assertThat(parentEvaluated.getCount()).isEqualTo(0);
    assertThatEvents(eventCollector).containsExactly(childEvent, parentEvent, waitEvent);
  }

  @Test
  public void depEventPredicate() throws Exception {
    SkyKey parent = skyKey("parent");
    SkyKey excludedDep = skyKey("excludedDep");
    SkyKey includedDep = skyKey("includedDep");
    tester.setEventFilter(
        new EventFilter() {
          @Override
          public boolean storeEvents() {
            return true;
          }

          @Override
          public boolean shouldPropagate(SkyKey depKey, SkyKey primaryKey) {
            return !primaryKey.equals(parent) || depKey.equals(includedDep);
          }
        });
    tester.initialize();
    tester
        .getOrCreate(parent)
        .addDependency(excludedDep)
        .addDependency(includedDep)
        .setComputedValue(CONCATENATE);
    tester
        .getOrCreate(excludedDep)
        .setErrorEvent("excludedDep error message")
        .setConstantValue(new StringValue("excludedDep"));
    tester
        .getOrCreate(includedDep)
        .setErrorEvent("includedDep error message")
        .setConstantValue(new StringValue("includedDep"));
    tester.eval(/* keepGoing= */ false, includedDep, excludedDep);
    assertThatEvents(eventCollector)
        .containsExactly("excludedDep error message", "includedDep error message");
    eventCollector.clear();
    emittedEventState.clear();
    tester.eval(/* keepGoing= */ true, parent);
    assertThatEvents(eventCollector).containsExactly("includedDep error message");
    assertThat(
            ValueWithMetadata.getEvents(
                    tester
                        .evaluator
                        .getExistingEntryAtCurrentlyEvaluatingVersion(parent)
                        .getValueMaybeWithMetadata())
                .toList())
        .containsExactly(Event.error("includedDep error message"));
  }

  // Tests that we have a sane implementation of error transience.
  @Test
  public void errorTransienceBug() throws Exception {
    tester.getOrCreate("key").setHasTransientError(true);
    assertThat(tester.evalAndGetError(/*keepGoing=*/ true, "key").getException()).isNotNull();
    StringValue value = new StringValue("hi");
    tester.getOrCreate("key").setHasTransientError(false).setConstantValue(value);
    tester.invalidateTransientErrors();
    assertThat(tester.evalAndGet("key")).isEqualTo(value);
    // This works because the version of the ValueEntry for the ErrorTransience value is always
    // increased on each InMemoryMemoizingEvaluator#evaluate call. But that's not the only way to
    // implement error transience; another valid implementation would be to unconditionally mark
    // values depending on the ErrorTransience value as being changed (rather than merely dirtied)
    // during invalidation.
  }

  @Test
  public void transientErrorTurningGoodHasNoError() throws Exception {
    initializeTester();
    SkyKey errorKey = skyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasTransientError(true);
    ErrorInfo errorInfo = tester.evalAndGetError(/*keepGoing=*/ true, errorKey);
    assertThat(errorInfo).isNotNull();
    // Re-evaluates to same thing when errors are invalidated
    tester.invalidateTransientErrors();
    errorInfo = tester.evalAndGetError(/*keepGoing=*/ true, errorKey);
    assertThat(errorInfo).isNotNull();
    StringValue value = new StringValue("reformed");
    tester
        .getOrCreate(errorKey, /*markAsModified=*/ false)
        .setHasTransientError(false)
        .setConstantValue(value);
    tester.invalidateTransientErrors();
    StringValue stringValue = (StringValue) tester.evalAndGet(/*keepGoing=*/ true, errorKey);
    assertThat(value).isSameInstanceAs(stringValue);
    // Value builder will now throw, but we should never get to it because it isn't dirty.
    tester.getOrCreate(errorKey, /*markAsModified=*/ false).setHasTransientError(true);
    tester.invalidateTransientErrors();
    stringValue = (StringValue) tester.evalAndGet(/*keepGoing=*/ true, errorKey);
    assertThat(stringValue).isEqualTo(value);
  }

  @Test
  public void transientErrorTurnsGoodOnSecondTry() throws Exception {
    SkyKey leafKey = skyKey("leaf");
    SkyKey errorKey = skyKey("error");
    SkyKey topKey = skyKey("top");
    StringValue value = new StringValue("val");
    tester.getOrCreate(topKey).addDependency(errorKey).setConstantValue(value);
    tester
        .getOrCreate(errorKey)
        .addDependency(leafKey)
        .setConstantValue(value)
        .setHasTransientError(true);
    tester.getOrCreate(leafKey).setConstantValue(new StringValue("leaf"));
    ErrorInfo errorInfo = tester.evalAndGetError(/* keepGoing= */ true, topKey);
    assertThat(errorInfo).isNotNull();
    assertThatErrorInfo(errorInfo).isTransient();
    tester.invalidateTransientErrors();
    errorInfo = tester.evalAndGetError(/*keepGoing=*/ true, topKey);
    assertThat(errorInfo).isNotNull();
    assertThatErrorInfo(errorInfo).isTransient();
    tester.invalidateTransientErrors();
    tester.getOrCreate(errorKey, /*markAsModified=*/ false).setHasTransientError(false);
    assertThat(tester.evalAndGet(/*keepGoing=*/ true, topKey)).isEqualTo(value);
  }

  @Test
  public void deleteInvalidatedValue() throws Exception {
    SkyKey top = skyKey("top");
    SkyKey toDelete = nonHermeticKey("toDelete");
    // Must be a concatenation -- COPY doesn't actually copy.
    tester.getOrCreate(top).addDependency(toDelete).setComputedValue(CONCATENATE);
    tester.set(toDelete, new StringValue("toDelete"));
    SkyValue value = tester.evalAndGet("top");
    SkyKey forceInvalidation = skyKey("forceInvalidation");
    tester.set(forceInvalidation, new StringValue("forceInvalidation"));
    tester.getOrCreate(toDelete, /* markAsModified= */ true);
    tester.invalidate();
    tester.eval(/* keepGoing= */ false, forceInvalidation);
    tester.delete("toDelete");
    WeakReference<SkyValue> ref = new WeakReference<>(value);
    value = null;
    tester.eval(/*keepGoing=*/ false, forceInvalidation);
    tester.invalidate(); // So that invalidation receiver doesn't hang on to reference.
    GcFinalization.awaitClear(ref);
  }

  /**
   * General stress/fuzz test of the evaluator with failure. Construct a large graph, and then throw
   * exceptions during building at various points.
   */
  @Test
  public void twoRailLeftRightDependenciesWithFailure() throws Exception {
    initializeTester();
    SkyKey[] leftValues = new SkyKey[TEST_NODE_COUNT];
    SkyKey[] rightValues = new SkyKey[TEST_NODE_COUNT];
    for (int i = 0; i < TEST_NODE_COUNT; i++) {
      leftValues[i] = nonHermeticKey("left-" + i);
      rightValues[i] = skyKey("right-" + i);
      if (i == 0) {
        tester.getOrCreate(leftValues[i]).addDependency("leaf").setComputedValue(COPY);
        tester.getOrCreate(rightValues[i]).addDependency("leaf").setComputedValue(COPY);
      } else {
        tester
            .getOrCreate(leftValues[i])
            .addDependency(leftValues[i - 1])
            .addDependency(rightValues[i - 1])
            .setComputedValue(new PassThroughSelected(leftValues[i - 1]));
        tester
            .getOrCreate(rightValues[i])
            .addDependency(leftValues[i - 1])
            .addDependency(rightValues[i - 1])
            .setComputedValue(new PassThroughSelected(rightValues[i - 1]));
      }
    }
    tester.set("leaf", new StringValue("leaf"));

    SkyKey lastLeft = nonHermeticKey("left-" + (TEST_NODE_COUNT - 1));
    SkyKey lastRight = skyKey("right-" + (TEST_NODE_COUNT - 1));

    for (int i = 0; i < TESTED_NODES; i++) {
      try {
        tester.getOrCreate(leftValues[i], /* markAsModified= */ true).setHasError(true);
        tester.invalidate();
        EvaluationResult<StringValue> result =
            tester.eval(/* keepGoing= */ false, lastLeft, lastRight);
        assertThat(result.hasError()).isTrue();
        tester.differencer.invalidate(ImmutableList.of(leftValues[i]));
        tester.invalidate();
        result = tester.eval(/* keepGoing= */ false, lastLeft, lastRight);
        assertThat(result.hasError()).isTrue();
        tester.getOrCreate(leftValues[i], /*markAsModified=*/ true).setHasError(false);
        tester.invalidate();
        result = tester.eval(/* keepGoing= */ false, lastLeft, lastRight);
        assertThat(result.get(lastLeft)).isEqualTo(new StringValue("leaf"));
        assertThat(result.get(lastRight)).isEqualTo(new StringValue("leaf"));
      } catch (Exception e) {
        System.err.println("twoRailLeftRightDependenciesWithFailure exception on run " + i);
        throw e;
      }
    }
  }

  @Test
  public void valueInjection() throws Exception {
    SkyKey key = nonHermeticKey("new_value");
    Delta delta = Delta.justNew(new StringValue("val"));

    tester.differencer.inject(ImmutableMap.of(key, delta));
    assertThat(tester.evalAndGet(/* keepGoing= */ false, key)).isEqualTo(delta.newValue());
  }

  @Test
  public void valueInjectionOverExistingEntry() throws Exception {
    SkyKey key = nonHermeticKey("key");
    Delta delta = Delta.justNew(new StringValue("val"));

    tester.getOrCreate(key).setConstantValue(new StringValue("old_val"));
    tester.differencer.inject(ImmutableMap.of(key, delta));
    assertThat(tester.evalAndGet(/* keepGoing= */ false, key)).isEqualTo(delta.newValue());
  }

  @Test
  public void valueInjectionOverExistingDirtyEntry() throws Exception {
    SkyKey key = nonHermeticKey("key");
    Delta delta = Delta.justNew(new StringValue("val"));

    tester.getOrCreate(key).setConstantValue(new StringValue("old_val"));
    tester.differencer.inject(ImmutableMap.of(key, delta));
    tester.eval(/*keepGoing=*/ false, new SkyKey[0]); // Create the value.

    tester.differencer.invalidate(ImmutableList.of(key));
    tester.eval(/*keepGoing=*/ false, new SkyKey[0]); // Mark value as dirty.

    tester.differencer.inject(ImmutableMap.of(key, delta));
    tester.eval(/*keepGoing=*/ false, new SkyKey[0]); // Inject again.
    assertThat(tester.evalAndGet(/* keepGoing= */ false, key)).isEqualTo(delta.newValue());
  }

  @Test
  public void valueInjectionOverExistingEntryMarkedForInvalidation() throws Exception {
    SkyKey key = nonHermeticKey("key");
    Delta delta = Delta.justNew(new StringValue("val"));

    tester.getOrCreate(key).setConstantValue(new StringValue("old_val"));
    tester.differencer.invalidate(ImmutableList.of(key));
    tester.differencer.inject(ImmutableMap.of(key, delta));
    assertThat(tester.evalAndGet(/* keepGoing= */ false, key)).isEqualTo(delta.newValue());
  }

  @Test
  public void valueInjectionOverExistingEntryMarkedForDeletion() throws Exception {
    SkyKey key = nonHermeticKey("key");
    Delta delta = Delta.justNew(new StringValue("val"));

    tester.getOrCreate(key).setConstantValue(new StringValue("old_val"));
    tester.evaluator.delete(Predicates.alwaysTrue());
    tester.differencer.inject(ImmutableMap.of(key, delta));
    assertThat(tester.evalAndGet(/* keepGoing= */ false, key)).isEqualTo(delta.newValue());
  }

  @Test
  public void valueInjectionOverExistingEqualEntryMarkedForInvalidation() throws Exception {
    SkyKey key = nonHermeticKey("key");
    Delta delta = Delta.justNew(new StringValue("val"));

    tester.differencer.inject(ImmutableMap.of(key, delta));
    assertThat(tester.evalAndGet(/* keepGoing= */ false, key)).isEqualTo(delta.newValue());

    tester.differencer.invalidate(ImmutableList.of(key));
    tester.differencer.inject(ImmutableMap.of(key, delta));
    assertThat(tester.evalAndGet(/* keepGoing= */ false, key)).isEqualTo(delta.newValue());
  }

  @Test
  public void valueInjectionOverExistingEqualEntryMarkedForDeletion() throws Exception {
    SkyKey key = nonHermeticKey("key");
    Delta delta = Delta.justNew(new StringValue("val"));

    tester.differencer.inject(ImmutableMap.of(key, delta));
    assertThat(tester.evalAndGet(/* keepGoing= */ false, key)).isEqualTo(delta.newValue());

    tester.evaluator.delete(Predicates.alwaysTrue());
    tester.differencer.inject(ImmutableMap.of(key, delta));
    assertThat(tester.evalAndGet(/* keepGoing= */ false, key)).isEqualTo(delta.newValue());
  }

  @Test
  public void valueInjectionOverValueWithDeps() throws Exception {
    SkyKey key = nonHermeticKey("key");
    SkyKey otherKey = nonHermeticKey("other");
    Delta delta = Delta.justNew(new StringValue("val"));
    StringValue prevVal = new StringValue("foo");

    tester.getOrCreate(otherKey).setConstantValue(prevVal);
    tester.getOrCreate(key).addDependency(otherKey).setComputedValue(COPY);
    assertThat(tester.evalAndGet(/* keepGoing= */ false, key)).isEqualTo(prevVal);
    tester.differencer.inject(ImmutableMap.of(key, delta));
    StringValue depVal = new StringValue("newfoo");
    tester.getOrCreate(otherKey).setConstantValue(depVal);
    tester.differencer.invalidate(ImmutableList.of(otherKey));
    // Injected value is ignored for value with deps.
    assertThat(tester.evalAndGet(/*keepGoing=*/ false, key)).isEqualTo(depVal);
  }

  @Test
  public void valueInjectionOverEqualValueWithDeps() throws Exception {
    SkyKey key = nonHermeticKey("key");
    Delta delta = Delta.justNew(new StringValue("val"));

    tester.getOrCreate("other").setConstantValue(delta.newValue());
    tester.getOrCreate(key).addDependency("other").setComputedValue(COPY);
    assertThat(tester.evalAndGet(/* keepGoing= */ false, key)).isEqualTo(delta.newValue());
    tester.differencer.inject(ImmutableMap.of(key, delta));
    assertThat(tester.evalAndGet(/* keepGoing= */ false, key)).isEqualTo(delta.newValue());
  }

  @Test
  public void valueInjectionOverValueWithErrors() throws Exception {
    SkyKey key = nonHermeticKey("key");
    Delta delta = Delta.justNew(new StringValue("val"));

    tester.getOrCreate(key).setHasError(true);
    tester.evalAndGetError(/*keepGoing=*/ true, key);

    tester.differencer.inject(ImmutableMap.of(key, delta));
    assertThat(tester.evalAndGet(false, key)).isEqualTo(delta.newValue());
  }

  @Test
  public void valueInjectionInvalidatesReverseDeps() throws Exception {
    SkyKey childKey = nonHermeticKey("child");
    SkyKey parentKey = skyKey("parent");
    StringValue oldVal = new StringValue("old_val");

    tester.getOrCreate(childKey).setConstantValue(oldVal);
    tester.getOrCreate(parentKey).addDependency(childKey).setComputedValue(COPY);

    EvaluationResult<SkyValue> result = tester.eval(false, parentKey);
    assertThat(result.hasError()).isFalse();
    assertThat(result.get(parentKey)).isEqualTo(oldVal);

    Delta delta = Delta.justNew(new StringValue("val"));
    tester.differencer.inject(ImmutableMap.of(childKey, delta));
    assertThat(tester.evalAndGet(/* keepGoing= */ false, childKey)).isEqualTo(delta.newValue());
    // Injecting a new child should have invalidated the parent.
    assertThat(tester.getExistingValue("parent")).isNull();

    tester.eval(false, childKey);
    assertThat(tester.getExistingValue(childKey)).isEqualTo(delta.newValue());
    assertThat(tester.getExistingValue("parent")).isNull();
    assertThat(tester.evalAndGet("parent")).isEqualTo(delta.newValue());
  }

  @Test
  public void valueInjectionOverExistingEqualEntryDoesNotInvalidate() throws Exception {
    SkyKey childKey = nonHermeticKey("child");
    SkyKey parentKey = skyKey("parent");
    Delta delta = Delta.justNew(new StringValue("same_val"));

    tester.getOrCreate(parentKey).addDependency(childKey).setComputedValue(COPY);
    tester.getOrCreate(childKey).setConstantValue(new StringValue("same_val"));
    assertThat(tester.evalAndGet("parent")).isEqualTo(delta.newValue());

    tester.differencer.inject(ImmutableMap.of(childKey, delta));
    assertThat(tester.getExistingValue(childKey)).isEqualTo(delta.newValue());
    // Since we are injecting an equal value, the parent should not have been invalidated.
    assertThat(tester.getExistingValue("parent")).isEqualTo(delta.newValue());
  }

  @Test
  public void valueInjectionInterrupt() throws Exception {
    SkyKey key = nonHermeticKey("key");
    Delta delta = Delta.justNew(new StringValue("val"));

    tester.differencer.inject(ImmutableMap.of(key, delta));
    Thread.currentThread().interrupt();
    assertThrows(InterruptedException.class, () -> tester.evalAndGet(/*keepGoing=*/ false, key));
    SkyValue newVal = tester.evalAndGet(/*keepGoing=*/ false, key);
    assertThat(newVal).isEqualTo(delta.newValue());
  }

  protected void runTestPersistentErrorsNotRerun(boolean includeTransientError) throws Exception {
    SkyKey topKey = skyKey("top");
    SkyKey transientErrorKey = skyKey("transientError");
    SkyKey persistentErrorKey1 = skyKey("persistentError1");
    SkyKey persistentErrorKey2 = skyKey("persistentError2");

    TestFunction topFunction =
        tester
            .getOrCreate(topKey)
            .addErrorDependency(persistentErrorKey1, new StringValue("doesn't matter"))
            .setHasError(true);
    tester.getOrCreate(persistentErrorKey1).setHasError(true);
    if (includeTransientError) {
      topFunction.addErrorDependency(transientErrorKey, new StringValue("doesn't matter"));
      tester
          .getOrCreate(transientErrorKey)
          .addErrorDependency(persistentErrorKey2, new StringValue("doesn't matter"))
          .setHasTransientError(true);
    }
    tester.getOrCreate(persistentErrorKey2).setHasError(true);

    tester.evalAndGetError(/*keepGoing=*/ true, topKey);
    if (includeTransientError) {
      assertThat(tester.getEnqueuedValues())
          .containsExactly(topKey, transientErrorKey, persistentErrorKey1, persistentErrorKey2);
    } else {
      assertThat(tester.getEnqueuedValues()).containsExactly(topKey, persistentErrorKey1);
    }

    tester.invalidate();
    tester.invalidateTransientErrors();
    tester.evalAndGetError(/*keepGoing=*/ true, topKey);
    if (includeTransientError) {
      // TODO(bazel-team): We can do better here once we implement change pruning for errors.
      assertThat(tester.getEnqueuedValues()).containsExactly(topKey, transientErrorKey);
    } else {
      assertThat(tester.getEnqueuedValues()).isEmpty();
    }
  }

  @Test
  public void persistentErrorsNotRerun() throws Exception {
    runTestPersistentErrorsNotRerun(/*includeTransientError=*/ true);
  }

  /**
   * The following two tests check that the evaluator shuts down properly when encountering an error
   * that is marked dirty but later verified to be unchanged from a prior build. In that case, the
   * invariant that its parents are not enqueued for evaluation should be maintained.
   */
  /**
   * Test that a parent of a cached but invalidated error doesn't successfully build. First build
   * the error. Then invalidate the error via a dependency (so it will not actually change) and
   * build two new parents. Parent A will request error and abort since error isn't done yet. error
   * is then revalidated, and A is restarted. If A does not throw upon encountering the error, and
   * instead sets its value, then we throw in parent B, which waits for error to be done before
   * requesting it. Then there will be the impossible situation of a node that was built during this
   * evaluation depending on a node in error.
   */
  @Test
  public void shutDownBuildOnCachedError_Done() throws Exception {
    // errorKey will be invalidated due to its dependence on invalidatedKey, but later revalidated
    // since invalidatedKey re-evaluates to the same value on a subsequent build.
    SkyKey errorKey = skyKey("error");
    SkyKey invalidatedKey = nonHermeticKey("invalidated-leaf");
    tester.set(invalidatedKey, new StringValue("invalidated-leaf-value"));
    tester.getOrCreate(errorKey).addDependency(invalidatedKey).setHasError(true);
    // Names are alphabetized in reverse deps of errorKey.
    SkyKey fastToRequestSlowToSetValueKey = skyKey("A-slow-set-value-parent");
    SkyKey failingKey = skyKey("B-fast-fail-parent");
    tester
        .getOrCreate(fastToRequestSlowToSetValueKey)
        .addDependency(errorKey)
        .setComputedValue(CONCATENATE);
    tester.getOrCreate(failingKey).addDependency(errorKey).setComputedValue(CONCATENATE);
    // We only want to force a particular order of operations at some points during evaluation.
    AtomicBoolean synchronizeThreads = new AtomicBoolean(false);
    // We don't expect slow-set-value to actually be built, but if it is, we wait for it.
    CountDownLatch slowBuilt = new CountDownLatch(1);
    injectGraphListenerForTesting(
        (key, type, order, context) -> {
          if (!synchronizeThreads.get()) {
            return;
          }
          if (type == EventType.GET_LIFECYCLE_STATE && key.equals(failingKey)) {
            // Wait for the build to abort or for the other node to incorrectly build.
            try {
              assertThat(slowBuilt.await(TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                  .isTrue();
            } catch (InterruptedException e) {
              // This is ok, because it indicates the build is shutting down.
              Thread.currentThread().interrupt();
            }
          } else if (type == EventType.SET_VALUE
              && key.equals(fastToRequestSlowToSetValueKey)
              && order == Order.AFTER) {
            // This indicates a problem -- this parent shouldn't be built since it depends on
            // an error.
            slowBuilt.countDown();
            // Before this node actually sets its value (and then throws an exception) we wait
            // for the other node to throw an exception.
            try {
              Thread.sleep(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
              throw new IllegalStateException("uninterrupted in " + key);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        },
        /* deterministic= */ true);
    // Initialize graph.
    tester.eval(/*keepGoing=*/ true, errorKey);
    tester.getOrCreate(invalidatedKey, /*markAsModified=*/ true);
    tester.invalidate();
    synchronizeThreads.set(true);
    tester.eval(/*keepGoing=*/ false, fastToRequestSlowToSetValueKey, failingKey);
  }

  /**
   * Test that the invalidated parent of a cached but invalidated error doesn't get marked clean.
   * First build the parent -- it will contain an error. Then invalidate the error via a dependency
   * (so it will not actually change) and then build the parent and another node that depends on the
   * error. The other node will wait to throw until the parent is signaled that all of its
   * dependencies are done, or until it is interrupted. If it throws, the parent will be
   * VERIFIED_CLEAN but not done, which is not a valid state once evaluation shuts down. The
   * evaluator avoids this situation by throwing when the error is encountered, even though the
   * error isn't evaluated or requested by an evaluating node.
   */
  @Test
  public void shutDownBuildOnCachedError_Verified() throws Exception {
    // TrackingProgressReceiver does unnecessary examination of node values.
    initializeTester(createTrackingProgressReceiver(/* checkEvaluationResults= */ false));
    // errorKey will be invalidated due to its dependence on invalidatedKey, but later revalidated
    // since invalidatedKey re-evaluates to the same value on a subsequent build.
    SkyKey errorKey = skyKey("error");
    SkyKey invalidatedKey = nonHermeticKey("invalidated-leaf");
    SkyKey changedKey = nonHermeticKey("changed-leaf");
    tester.set(invalidatedKey, new StringValue("invalidated-leaf-value"));
    tester.set(changedKey, new StringValue("changed-leaf-value"));
    // Names are alphabetized in reverse deps of errorKey.
    SkyKey cachedParentKey = skyKey("A-cached-parent");
    SkyKey uncachedParentKey = skyKey("B-uncached-parent");
    tester.getOrCreate(errorKey).addDependency(invalidatedKey).setHasError(true);
    tester.getOrCreate(cachedParentKey).addDependency(errorKey).setComputedValue(CONCATENATE);
    tester
        .getOrCreate(uncachedParentKey)
        .addDependency(changedKey)
        .addDependency(errorKey)
        .setComputedValue(CONCATENATE);
    // We only want to force a particular order of operations at some points during evaluation. In
    // particular, we don't want to force anything during error bubbling.
    AtomicBoolean synchronizeThreads = new AtomicBoolean(false);
    CountDownLatch shutdownAwaiterStarted = new CountDownLatch(1);
    injectGraphListenerForTesting(
        new Listener() {
          private final CountDownLatch cachedSignaled = new CountDownLatch(1);

          @Override
          public void accept(SkyKey key, EventType type, Order order, Object context) {
            if (!synchronizeThreads.get() || order != Order.BEFORE || type != EventType.SIGNAL) {
              return;
            }
            TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                shutdownAwaiterStarted, "shutdown awaiter not started");
            if (key.equals(uncachedParentKey)) {
              // When the uncached parent is first signaled by its changed dep, make sure that
              // we wait until the cached parent is signaled too.
              try {
                assertThat(cachedSignaled.await(TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isTrue();
              } catch (InterruptedException e) {
                // Before the relevant bug was fixed, this code was not interrupted, and the
                // uncached parent got to build, yielding an inconsistent state at a later point
                // during evaluation. With the bugfix, the cached parent is never signaled
                // before the evaluator shuts down, and so the above code is interrupted.
                Thread.currentThread().interrupt();
              }
            } else if (key.equals(cachedParentKey)) {
              // This branch should never be reached by a well-behaved evaluator, since when the
              // error node is reached, the evaluator should shut down. However, we don't test
              // for that behavior here because that would be brittle and we expect that such an
              // evaluator will crash hard later on in any case.
              cachedSignaled.countDown();
              try {
                // Sleep until we're interrupted by the evaluator, so we know it's shutting
                // down.
                Thread.sleep(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
                Thread currentThread = Thread.currentThread();
                throw new IllegalStateException(
                    "no interruption in time in "
                        + key
                        + " for "
                        + (currentThread.isInterrupted() ? "" : "un")
                        + "interrupted "
                        + currentThread
                        + " with hash "
                        + System.identityHashCode(currentThread)
                        + " at "
                        + System.currentTimeMillis());
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
          }
        },
        /* deterministic= */ true);
    // Initialize graph.
    tester.eval(/*keepGoing=*/ true, cachedParentKey, uncachedParentKey);
    tester.getOrCreate(invalidatedKey, /*markAsModified=*/ true);
    tester.set(changedKey, new StringValue("new value"));
    tester.invalidate();
    synchronizeThreads.set(true);
    SkyKey waitForShutdownKey = skyKey("wait-for-shutdown");
    tester
        .getOrCreate(waitForShutdownKey)
        .setBuilder(
            (skyKey, env) -> {
              shutdownAwaiterStarted.countDown();
              TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                  ((SkyFunctionEnvironment) env).getExceptionLatchForTesting(),
                  "exception not thrown");
              // Threadpool is shutting down. Don't try to synchronize anything in the future
              // during error bubbling.
              synchronizeThreads.set(false);
              throw new InterruptedException();
            });
    EvaluationResult<StringValue> result =
        tester.eval(/*keepGoing=*/ false, cachedParentKey, uncachedParentKey, waitForShutdownKey);
    assertWithMessage(result.toString()).that(result.hasError()).isTrue();
    tester.getOrCreate(invalidatedKey, /*markAsModified=*/ true);
    tester.invalidate();
    result = tester.eval(/*keepGoing=*/ false, cachedParentKey, uncachedParentKey);
    assertWithMessage(result.toString()).that(result.hasError()).isTrue();
  }

  /**
   * Tests that a race between a node being marked clean and another node requesting it is benign.
   * Here, we first evaluate errorKey, depending on invalidatedKey. Then we invalidate
   * invalidatedKey (without actually changing it) and evaluate errorKey and topKey together.
   * Through forced synchronization, we make sure that the following sequence of events happens:
   *
   * <ol>
   *   <li>topKey requests errorKey;
   *   <li>errorKey is marked clean;
   *   <li>topKey finishes its first evaluation and registers its deps;
   *   <li>topKey restarts, since it sees that its only dep, errorKey, is done;
   *   <li>topKey sees the error thrown by errorKey and throws the error, shutting down the
   *       threadpool;
   * </ol>
   */
  @Test
  public void cachedErrorCausesRestart() throws Exception {
    // TrackingProgressReceiver does unnecessary examination of node values.
    initializeTester(createTrackingProgressReceiver(/* checkEvaluationResults= */ false));
    SkyKey errorKey = skyKey("error");
    SkyKey invalidatedKey = nonHermeticKey("invalidated");
    SkyKey topKey = skyKey("top");
    tester.getOrCreate(errorKey).addDependency(invalidatedKey).setHasError(true);
    tester.getOrCreate(invalidatedKey).setConstantValue(new StringValue("constant"));
    CountDownLatch topSecondEval = new CountDownLatch(2);
    CountDownLatch topRequestedError = new CountDownLatch(1);
    CountDownLatch errorMarkedClean = new CountDownLatch(1);
    injectGraphListenerForTesting(
        (key, type, order, context) -> {
          if (errorKey.equals(key) && type == EventType.MARK_CLEAN) {
            if (order == Order.BEFORE) {
              TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                  topRequestedError, "top didn't request");
            } else {
              errorMarkedClean.countDown();
              TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                  topSecondEval, "top didn't restart");
              // Make sure that the other thread notices the error and interrupts this thread.
              try {
                Thread.sleep(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
          }
        },
        /* deterministic= */ false);
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, errorKey);
    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(errorKey)
        .hasExceptionThat()
        .isNotNull();
    tester
        .getOrCreate(topKey)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
                topSecondEval.countDown();
                env.getValue(errorKey);
                topRequestedError.countDown();
                assertThat(env.valuesMissing()).isTrue();
                TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                    errorMarkedClean, "error not marked clean");
                return null;
              }
            });
    tester.getOrCreate(invalidatedKey, /*markAsModified=*/ true);
    tester.invalidate();
    EvaluationResult<StringValue> result2 = tester.eval(/*keepGoing=*/ false, errorKey, topKey);
    assertThatEvaluationResult(result2).hasError();
    assertThatEvaluationResult(result2)
        .hasErrorEntryForKeyThat(errorKey)
        .hasExceptionThat()
        .isNotNull();
    assertThatEvaluationResult(result2)
        .hasErrorEntryForKeyThat(topKey)
        .hasExceptionThat()
        .isNotNull();
  }

  @Test
  public void cachedChildErrorDepWithSiblingDepOnNoKeepGoingEval() throws Exception {
    SkyKey parent1Key = skyKey("parent1");
    SkyKey parent2Key = skyKey("parent2");
    SkyKey errorKey = nonHermeticKey("error");
    SkyKey otherKey = skyKey("other");
    SkyFunction parentBuilder =
        (skyKey, env) -> {
          env.getValue(errorKey);
          env.getValue(otherKey);
          if (env.valuesMissing()) {
            return null;
          }
          return new StringValue("parent");
        };
    tester.getOrCreate(parent1Key).setBuilder(parentBuilder);
    tester.getOrCreate(parent2Key).setBuilder(parentBuilder);
    tester.getOrCreate(errorKey).setConstantValue(new StringValue("no error yet"));
    tester.getOrCreate(otherKey).setConstantValue(new StringValue("other"));
    tester.eval(/*keepGoing=*/ true, parent1Key);
    tester.eval(/*keepGoing=*/ false, parent2Key);
    tester.getOrCreate(errorKey, /*markAsModified=*/ true).setHasError(true);
    tester.invalidate();
    tester.eval(/*keepGoing=*/ true, parent1Key);
    tester.eval(/*keepGoing=*/ false, parent2Key);
  }

  private void injectGraphListenerForTesting(Listener listener, boolean deterministic) {
    tester.evaluator.injectGraphTransformerForTesting(
        DeterministicHelper.makeTransformer(listener, deterministic));
  }

  private void makeGraphDeterministic() {
    tester.evaluator.injectGraphTransformerForTesting(DeterministicHelper.MAKE_DETERMINISTIC);
  }

  private static final class PassThroughSelected implements ValueComputer {
    private final SkyKey key;

    PassThroughSelected(SkyKey key) {
      this.key = key;
    }

    @Override
    public SkyValue compute(Map<SkyKey, SkyValue> deps, SkyFunction.Environment env) {
      return Preconditions.checkNotNull(deps.get(key));
    }
  }

  private void removedNodeComesBack() throws Exception {
    SkyKey top = nonHermeticKey("top");
    SkyKey mid = skyKey("mid");
    SkyKey leaf = nonHermeticKey("leaf");
    // When top depends on mid, which depends on leaf,
    tester.getOrCreate(top).addDependency(mid).setComputedValue(CONCATENATE);
    tester.getOrCreate(mid).addDependency(leaf).setComputedValue(CONCATENATE);
    StringValue leafValue = new StringValue("leaf");
    tester.set(leaf, leafValue);
    // Then when top is evaluated, its value is as expected.
    assertThat(tester.evalAndGet(/* keepGoing= */ true, top)).isEqualTo(leafValue);
    // When top is changed to no longer depend on mid,
    StringValue topValue = new StringValue("top");
    tester
        .getOrCreate(top, /* markAsModified= */ true)
        .removeDependency(mid)
        .setComputedValue(null)
        .setConstantValue(topValue);
    // And leaf is invalidated,
    tester.getOrCreate(leaf, /*markAsModified=*/ true);
    // Then when top is evaluated, its value is as expected,
    tester.invalidate();
    assertThat(tester.evalAndGet(/*keepGoing=*/ true, top)).isEqualTo(topValue);
      // And there is no value for mid in the graph,
      assertThat(tester.evaluator.getExistingValue(mid)).isNull();
      assertThat(tester.evaluator.getExistingErrorForTesting(mid)).isNull();
      // Or for leaf.
      assertThat(tester.evaluator.getExistingValue(leaf)).isNull();
      assertThat(tester.evaluator.getExistingErrorForTesting(leaf)).isNull();

    // When top is changed to depend directly on leaf,
    tester
        .getOrCreate(top, /*markAsModified=*/ true)
        .addDependency(leaf)
        .setConstantValue(null)
        .setComputedValue(CONCATENATE);
    // Then when top is evaluated, its value is as expected,
    tester.invalidate();
    assertThat(tester.evalAndGet(/*keepGoing=*/ true, top)).isEqualTo(leafValue);
      // and there is no value for mid in the graph,
      assertThat(tester.evaluator.getExistingValue(mid)).isNull();
      assertThat(tester.evaluator.getExistingErrorForTesting(mid)).isNull();
  }

  // Tests that a removed and then reinstated node doesn't try to invalidate its erstwhile parent
  // when it is invalidated.
  @Test
  public void removedNodeComesBackAndInvalidates() throws Exception {
    removedNodeComesBack();
    // When leaf is invalidated again,
    tester.getOrCreate(skyKey("leaf"), /* markAsModified= */ true);
    // Then when top is evaluated, its value is as expected.
    tester.invalidate();
    assertThat(tester.evalAndGet(/* keepGoing= */ true, nonHermeticKey("top")))
        .isEqualTo(new StringValue("leaf"));
  }

  // Tests that a removed and then reinstated node behaves properly when its parent disappears and
  // then reappears.
  @Test
  public void removedNodeComesBackAndOtherInvalidates() throws Exception {
    removedNodeComesBack();
    SkyKey top = nonHermeticKey("top");
    SkyKey mid = skyKey("mid");
    SkyKey leaf = nonHermeticKey("leaf");
    // When top is invalidated again,
    tester.getOrCreate(top, /* markAsModified= */ true).removeDependency(leaf).addDependency(mid);
    // Then when top is evaluated, its value is as expected.
    tester.invalidate();
    assertThat(tester.evalAndGet(/* keepGoing= */ true, top)).isEqualTo(new StringValue("leaf"));
  }

  // Tests that a removed and then reinstated node doesn't have a reverse dep on a former parent.
  @Test
  public void removedInvalidatedNodeComesBackAndOtherInvalidates() throws Exception {
    SkyKey top = nonHermeticKey("top");
    SkyKey leaf = nonHermeticKey("leaf");
    // When top depends on leaf,
    tester.getOrCreate(top).addDependency(leaf).setComputedValue(CONCATENATE);
    StringValue leafValue = new StringValue("leaf");
    tester.set(leaf, leafValue);
    // Then when top is evaluated, its value is as expected.
    assertThat(tester.evalAndGet(/* keepGoing= */ true, top)).isEqualTo(leafValue);
    // When top is changed to no longer depend on leaf,
    StringValue topValue = new StringValue("top");
    tester
        .getOrCreate(top, /* markAsModified= */ true)
        .removeDependency(leaf)
        .setComputedValue(null)
        .setConstantValue(topValue);
    // And leaf is invalidated,
    tester.getOrCreate(leaf, /*markAsModified=*/ true);
    // Then when top is evaluated, its value is as expected,
    tester.invalidate();
    assertThat(tester.evalAndGet(/*keepGoing=*/ true, top)).isEqualTo(topValue);
      // And there is no value for leaf in the graph.
      assertThat(tester.evaluator.getExistingValue(leaf)).isNull();
      assertThat(tester.evaluator.getExistingErrorForTesting(leaf)).isNull();
    // When leaf is evaluated, so that it is present in the graph again,
    assertThat(tester.evalAndGet(/*keepGoing=*/ true, leaf)).isEqualTo(leafValue);
    // And top is changed to depend on leaf again,
    tester
        .getOrCreate(top, /*markAsModified=*/ true)
        .addDependency(leaf)
        .setConstantValue(null)
        .setComputedValue(CONCATENATE);
    // Then when top is evaluated, its value is as expected.
    tester.invalidate();
    assertThat(tester.evalAndGet(/*keepGoing=*/ true, top)).isEqualTo(leafValue);
  }

  @Test
  public void cleanReverseDepFromDirtyNodeNotInBuild() throws Exception {
    SkyKey topKey = nonHermeticKey("top");
    SkyKey inactiveKey = nonHermeticKey("inactive");
    Thread mainThread = Thread.currentThread();
    AtomicBoolean shouldInterrupt = new AtomicBoolean(false);
    injectGraphListenerForTesting(
        (key, type, order, context) -> {
          if (shouldInterrupt.get()
              && key.equals(topKey)
              && type == EventType.IS_READY
              && order == Order.BEFORE) {
            mainThread.interrupt();
            shouldInterrupt.set(false);
            try {
              // Make sure threadpool propagates interrupt.
              Thread.sleep(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        },
        /* deterministic= */ false);
    // When top depends on inactive,
    tester.getOrCreate(topKey).addDependency(inactiveKey).setComputedValue(COPY);
    StringValue val = new StringValue("inactive");
    // And inactive is constant,
    tester.set(inactiveKey, val);
    // Then top evaluates normally.
    assertThat(tester.evalAndGet(/*keepGoing=*/ true, topKey)).isEqualTo(val);
    // When evaluation will be interrupted as soon as top starts evaluating,
    shouldInterrupt.set(true);
    // And inactive is dirty,
    tester.getOrCreate(inactiveKey, /*markAsModified=*/ true);
    // And so is top,
    tester.getOrCreate(topKey, /*markAsModified=*/ true);
    tester.invalidate();
    assertThrows(InterruptedException.class, () -> tester.eval(/*keepGoing=*/ false, topKey));
    // But inactive is still present,
    assertThat(tester.evaluator.getExistingEntryAtCurrentlyEvaluatingVersion(inactiveKey))
        .isNotNull();
    // And still dirty,
    assertThat(tester.evaluator.getExistingEntryAtCurrentlyEvaluatingVersion(inactiveKey).isDirty())
        .isTrue();
    // And re-evaluates successfully,
    assertThat(tester.evalAndGet(/*keepGoing=*/ true, inactiveKey)).isEqualTo(val);
    // But top is gone from the graph,
    assertThat(tester.evaluator.getExistingEntryAtCurrentlyEvaluatingVersion(topKey)).isNull();
    // And we can successfully invalidate and re-evaluate inactive again.
    tester.getOrCreate(inactiveKey, /*markAsModified=*/ true);
    tester.invalidate();
    assertThat(tester.evalAndGet(/*keepGoing=*/ true, inactiveKey)).isEqualTo(val);
  }

  @Test
  public void errorChanged() throws Exception {
    SkyKey error = nonHermeticKey("error");
    tester.getOrCreate(error).setHasError(true);
    assertThatErrorInfo(tester.evalAndGetError(/*keepGoing=*/ true, error))
        .hasExceptionThat()
        .isNotNull();
    tester.getOrCreate(error, /*markAsModified=*/ true);
    tester.invalidate();
    assertThatErrorInfo(tester.evalAndGetError(/*keepGoing=*/ true, error))
        .hasExceptionThat()
        .isNotNull();
  }

  @Test
  public void duplicateUnfinishedDeps_NoKeepGoing() throws Exception {
    runTestDuplicateUnfinishedDeps(/*keepGoing=*/ false);
  }

  @Test
  public void duplicateUnfinishedDeps_KeepGoing() throws Exception {
    runTestDuplicateUnfinishedDeps(/*keepGoing=*/ true);
  }

  @Test
  public void externalDep() throws Exception {
    externalDep(1, 0);
    externalDep(2, 0);
    externalDep(1, 1);
    externalDep(1, 2);
    externalDep(2, 1);
    externalDep(2, 2);
  }

  private void externalDep(int firstPassCount, int secondPassCount) throws Exception {
    SkyKey parentKey = skyKey("parentKey");
    CountDownLatch firstPassLatch = new CountDownLatch(1);
    CountDownLatch secondPassLatch = new CountDownLatch(1);
    tester
        .getOrCreate(parentKey)
        .setBuilder(
            new SkyFunction() {
              // Skyframe doesn't have native support for continuations, so we use fields here. A
              // simple continuation API in Skyframe could be Environment providing a
              // setContinuation(SkyContinuation) method, where SkyContinuation provides a compute
              // method similar to SkyFunction. When restarting the node, Skyframe would then call
              // the continuation rather than the original SkyFunction. If we do that, we should
              // consider only allowing calls to dependOnFuture in combination with setContinuation.
              private List<SettableFuture<SkyValue>> firstPass;
              private List<SettableFuture<SkyValue>> secondPass;

              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) {
                if (firstPass == null) {
                  firstPass = new ArrayList<>();
                  for (int i = 0; i < firstPassCount; i++) {
                    SettableFuture<SkyValue> future = SettableFuture.create();
                    firstPass.add(future);
                    env.dependOnFuture(future);
                  }
                  assertThat(env.valuesMissing()).isTrue();
                  Thread helper =
                      new Thread(
                          () -> {
                            try {
                              firstPassLatch.await();
                              for (int i = 0; i < firstPassCount; i++) {
                                firstPass.get(i).set(new StringValue("value1"));
                              }
                            } catch (InterruptedException e) {
                              throw new RuntimeException(e);
                            }
                          });
                  helper.start();
                  return null;
                } else if (secondPass == null && secondPassCount > 0) {
                  for (int i = 0; i < firstPassCount; i++) {
                    assertThat(firstPass.get(i).isDone()).isTrue();
                  }
                  secondPass = new ArrayList<>();
                  for (int i = 0; i < secondPassCount; i++) {
                    SettableFuture<SkyValue> future = SettableFuture.create();
                    secondPass.add(future);
                    env.dependOnFuture(future);
                  }
                  assertThat(env.valuesMissing()).isTrue();
                  Thread helper =
                      new Thread(
                          () -> {
                            try {
                              secondPassLatch.await();
                              for (int i = 0; i < secondPassCount; i++) {
                                secondPass.get(i).set(new StringValue("value2"));
                              }
                            } catch (InterruptedException e) {
                              throw new RuntimeException(e);
                            }
                          });
                  helper.start();
                  return null;
                }
                for (int i = 0; i < secondPassCount; i++) {
                  assertThat(secondPass.get(i).isDone()).isTrue();
                }
                return new StringValue("done!");
              }
            });
    tester.evaluator.injectGraphTransformerForTesting(
        NotifyingHelper.makeNotifyingTransformer(
            new Listener() {
              private boolean firstPassDone;

              @Override
              public void accept(SkyKey key, EventType type, Order order, Object context) {
                // NodeEntry.addExternalDep is called as part of bookkeeping at the end of
                // AbstractParallelEvaluator.Evaluate#run.
                if (key == parentKey && type == EventType.ADD_EXTERNAL_DEP) {
                  if (!firstPassDone) {
                    firstPassLatch.countDown();
                    firstPassDone = true;
                  } else {
                    secondPassLatch.countDown();
                  }
                }
              }
            }));
    EvaluationResult<StringValue> result = tester.eval(/*keepGoing=*/ false, parentKey);
    assertThat(result.hasError()).isFalse();
    assertThat(result.get(parentKey)).isEqualTo(new StringValue("done!"));
  }

  private void runTestDuplicateUnfinishedDeps(boolean keepGoing) throws Exception {
    SkyKey parentKey = skyKey("parent");
    SkyKey childKey = skyKey("child");
    SkyValue childValue = new StringValue("child");
    tester
        .getOrCreate(childKey)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env) {
                if (keepGoing) {
                  return childValue;
                } else {
                  throw new IllegalStateException("shouldn't get here");
                }
              }
            });
    SomeErrorException parentExn = new SomeErrorException("bad");
    AtomicInteger numParentComputeCalls = new AtomicInteger(0);
    tester
        .getOrCreate(parentKey)
        .setBuilder(
            new SkyFunction() {
              @Nullable
              @Override
              public SkyValue compute(SkyKey skyKey, Environment env)
                  throws SkyFunctionException, InterruptedException {
                numParentComputeCalls.incrementAndGet();
                if (!keepGoing || numParentComputeCalls.get() == 1) {
                  Preconditions.checkState(env.getValue(childKey) == null);
                  Preconditions.checkState(env.getValue(childKey) == null);
                } else {
                  Preconditions.checkState(env.getValue(childKey).equals(childValue));
                  Preconditions.checkState(env.getValue(childKey).equals(childValue));
                }
                throw new GenericFunctionException(parentExn, Transience.PERSISTENT);
              }
            });

    Exception exception = tester.evalAndGetError(keepGoing, parentKey).getException();
    assertThat(exception).isInstanceOf(SomeErrorException.class);
    assertThat(exception).hasMessageThat().isEqualTo("bad");
  }

  /** Data encapsulating a graph inconsistency found during evaluation. */
  record InconsistencyData(
      SkyKey key, ImmutableSet<SkyKey> otherKeys, Inconsistency inconsistency) {
    InconsistencyData {
      requireNonNull(key, "key");
      requireNonNull(otherKeys, "otherKeys");
      requireNonNull(inconsistency, "inconsistency");
    }

    static InconsistencyData resetRequested(SkyKey key) {
      return create(key, /* otherKeys= */ null, Inconsistency.RESET_REQUESTED);
    }

    static InconsistencyData rewind(SkyKey parent, ImmutableSet<SkyKey> children) {
      return create(parent, children, Inconsistency.PARENT_FORCE_REBUILD_OF_CHILD);
    }

    static InconsistencyData create(
        SkyKey key, @Nullable Collection<SkyKey> otherKeys, Inconsistency inconsistency) {
      return new InconsistencyData(
          key,
          otherKeys == null ? ImmutableSet.of() : ImmutableSet.copyOf(otherKeys),
          inconsistency);
    }
  }

  /** A graph tester that is specific to the memoizing evaluator, with some convenience methods. */
  protected final class MemoizingEvaluatorTester extends GraphTester {
    private RecordingDifferencer differencer;
    private MemoizingEvaluator evaluator;
    private TrackingProgressReceiver progressReceiver =
        createTrackingProgressReceiver(/*checkEvaluationResults=*/ true);
    private GraphInconsistencyReceiver graphInconsistencyReceiver =
        GraphInconsistencyReceiver.THROWING;
    private EventFilter eventFilter = EventFilter.FULL_STORAGE;

    /** Constructs a new {@link #evaluator}, so call before injecting a transformer into it! */
    public void initialize() {
      this.differencer = getRecordingDifferencer();
      this.evaluator =
          getMemoizingEvaluator(
              getSkyFunctionMap(),
              differencer,
              progressReceiver,
              graphInconsistencyReceiver,
              eventFilter);
    }

    /**
     * Sets the {@link #progressReceiver}. {@link #initialize} must be called after this to have any
     * effect.
     */
    public void setProgressReceiver(TrackingProgressReceiver progressReceiver) {
      this.progressReceiver = progressReceiver;
    }

    /**
     * Sets the {@link #eventFilter}. {@link #initialize} must be called after this to have any
     * effect.
     */
    public void setEventFilter(EventFilter eventFilter) {
      this.eventFilter = eventFilter;
    }

    /**
     * Sets the {@link #graphInconsistencyReceiver}. {@link #initialize} must be called after this
     * to have any effect.
     */
    public void setGraphInconsistencyReceiver(
        GraphInconsistencyReceiver graphInconsistencyReceiver) {
      this.graphInconsistencyReceiver = graphInconsistencyReceiver;
    }

    public MemoizingEvaluator getEvaluator() {
      return evaluator;
    }

    public void invalidate() throws InterruptedException {
      evaluator.noteEvaluationsAtSameVersionMayBeFinished(reporter);
      differencer.invalidate(getModifiedValues());
      clearModifiedValues();
      progressReceiver.clear();
    }

    public void invalidateTransientErrors() {
      differencer.invalidateTransientErrors();
    }

    public void delete(String key) {
      evaluator.delete(Predicates.equalTo(skyKey(key)));
    }

    public void resetPlayedEvents() {
      emittedEventState.clear();
    }

    public Set<SkyKey> getDirtyKeys() {
      return progressReceiver.dirty;
    }

    public Set<SkyKey> getDeletedKeys() {
      return progressReceiver.deleted;
    }

    public Set<SkyKey> getEnqueuedValues() {
      return progressReceiver.enqueued;
    }

    public <T extends SkyValue> EvaluationResult<T> eval(
        boolean keepGoing,
        boolean mergingSkyframeAnalysisExecutionPhases,
        int numThreads,
        SkyKey... keys)
        throws InterruptedException {
      assertThat(getModifiedValues()).isEmpty();
      EvaluationContext evaluationContext =
          EvaluationContext.newBuilder()
              .setKeepGoing(keepGoing)
              .setMergingSkyframeAnalysisExecutionPhases(mergingSkyframeAnalysisExecutionPhases)
              .setParallelism(numThreads)
              .setEventHandler(reporter)
              .build();
      BugReport.maybePropagateLastCrashIfInTest();
      EvaluationResult<T> result = null;
      beforeEvaluation();
      try {
        result = evaluator.evaluate(ImmutableList.copyOf(keys), evaluationContext);
        return result;
      } finally {
        afterEvaluation(result, evaluationContext);
      }
    }

    public <T extends SkyValue> EvaluationResult<T> eval(boolean keepGoing, SkyKey... keys)
        throws InterruptedException {
      return eval(keepGoing, /* mergingSkyframeAnalysisExecutionPhases= */ false, 100, keys);
    }

    public <T extends SkyValue> EvaluationResult<T> eval(
        boolean keepGoing, boolean mergingSkyframeAnalysisExecutionPhases, SkyKey... keys)
        throws InterruptedException {
      return eval(keepGoing, mergingSkyframeAnalysisExecutionPhases, 100, keys);
    }

    public <T extends SkyValue> EvaluationResult<T> eval(boolean keepGoing, String... keys)
        throws InterruptedException {
      return eval(keepGoing, toSkyKeys(keys).toArray(new SkyKey[0]));
    }

    public SkyValue evalAndGet(boolean keepGoing, String key) throws InterruptedException {
      return evalAndGet(keepGoing, skyKey(key));
    }

    public SkyValue evalAndGet(String key) throws InterruptedException {
      return evalAndGet(/*keepGoing=*/ false, key);
    }

    public SkyValue evalAndGet(boolean keepGoing, SkyKey key) throws InterruptedException {
      EvaluationResult<StringValue> evaluationResult = eval(keepGoing, key);
      SkyValue result = evaluationResult.get(key);
      assertWithMessage(evaluationResult.toString()).that(result).isNotNull();
      return result;
    }

    public ErrorInfo evalAndGetError(boolean keepGoing, SkyKey key) throws InterruptedException {
      EvaluationResult<StringValue> evaluationResult = eval(keepGoing, key);
      assertThatEvaluationResult(evaluationResult).hasErrorEntryForKeyThat(key);
      return evaluationResult.getError(key);
    }

    public ErrorInfo evalAndGetError(
        boolean keepGoing, boolean mergingSkyframeAnalysisExecutionPhases, SkyKey key)
        throws InterruptedException {
      EvaluationResult<StringValue> evaluationResult =
          eval(keepGoing, mergingSkyframeAnalysisExecutionPhases, key);
      assertThatEvaluationResult(evaluationResult).hasErrorEntryForKeyThat(key);
      return evaluationResult.getError(key);
    }

    public ErrorInfo evalAndGetError(boolean keepGoing, String key) throws InterruptedException {
      return evalAndGetError(keepGoing, skyKey(key));
    }

    @Nullable
    public SkyValue getExistingValue(SkyKey key) throws InterruptedException {
      return evaluator.getExistingValue(key);
    }

    @Nullable
    public SkyValue getExistingValue(String key) throws InterruptedException {
      return getExistingValue(skyKey(key));
    }
  }
}
