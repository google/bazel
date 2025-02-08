// Copyright 2015 The Bazel Authors. All rights reserved.
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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.graph.ImmutableGraph;
import com.google.common.util.concurrent.Callables;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.actions.OutputChecker;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.actions.util.DummyExecutor;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.testutil.TimestampGranularityUtils;
import com.google.devtools.build.lib.util.CrashFailureDetails;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.HashCodes;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.GroupedDeps;
import com.google.devtools.build.skyframe.InvalidationProgressReceiver;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link SkyframeAwareAction}. */
@RunWith(JUnit4.class)
public class SkyframeAwareActionTest extends TimestampBuilderTestCase {
  private Builder builder;
  private Executor executor;
  private TrackingEvaluationProgressReceiver progressReceiver;

  @Before
  public final void createBuilder() throws Exception {
    progressReceiver = new TrackingEvaluationProgressReceiver();
    builder = createBuilder(inMemoryCache, 1, /* keepGoing= */ false, progressReceiver);
  }

  @Before
  public final void createExecutor() {
    executor = new DummyExecutor(fileSystem, rootDirectory);
  }

  private static final class TrackingEvaluationProgressReceiver
      extends InvalidationProgressReceiver {

    public static final class InvalidatedKey {
      public final SkyKey skyKey;
      public final InvalidationState state;

      InvalidatedKey(SkyKey skyKey, InvalidationState state) {
        this.skyKey = skyKey;
        this.state = state;
      }

      @Override
      public boolean equals(Object obj) {
        return obj instanceof InvalidatedKey invalidatedKey
            && this.skyKey.equals(invalidatedKey.skyKey)
            && this.state.equals(invalidatedKey.state);
      }

      @Override
      public int hashCode() {
        return HashCodes.hashObjects(skyKey, state);
      }
    }

    private static final class EvaluatedEntry {
      final SkyKey skyKey;
      final EvaluationState state;

      EvaluatedEntry(SkyKey skyKey, EvaluationState state) {
        this.skyKey = skyKey;
        this.state = state;
      }

      @Override
      public boolean equals(Object obj) {
        return obj instanceof EvaluatedEntry evaluatedEntry
            && this.skyKey.equals(evaluatedEntry.skyKey)
            && this.state.equals(evaluatedEntry.state);
      }

      @Override
      public int hashCode() {
        return HashCodes.hashObjects(skyKey, state);
      }
    }

    public final Set<InvalidatedKey> invalidated = Sets.newConcurrentHashSet();
    public final Set<SkyKey> enqueued = Sets.newConcurrentHashSet();
    public final Set<EvaluatedEntry> evaluated = Sets.newConcurrentHashSet();

    public void reset() {
      invalidated.clear();
      enqueued.clear();
      evaluated.clear();
    }

    public boolean wasInvalidated(SkyKey skyKey) {
      for (InvalidatedKey e : invalidated) {
        if (e.skyKey.equals(skyKey)) {
          return true;
        }
      }
      return false;
    }

    public EvaluatedEntry getEvaluatedEntry(SkyKey forKey) {
      for (EvaluatedEntry e : evaluated) {
        if (e.skyKey.equals(forKey)) {
          return e;
        }
      }
      return null;
    }

    @Override
    protected void invalidated(SkyKey skyKey, InvalidationState state) {
      invalidated.add(new InvalidatedKey(skyKey, state));
    }

    @Override
    public void enqueueing(SkyKey skyKey) {
      enqueued.add(skyKey);
    }

    @Override
    public void evaluated(
        SkyKey skyKey,
        EvaluationState state,
        @Nullable SkyValue value,
        @Nullable ErrorInfo error,
        @Nullable GroupedDeps directDeps) {
      evaluated.add(new EvaluatedEntry(skyKey, state));
    }
  }

  /** A mock action that counts how many times it was executed. */
  private static class ExecutionCountingAction extends AbstractAction {
    private final AtomicInteger executionCounter;

    ExecutionCountingAction(Artifact input, Artifact output, AtomicInteger executionCounter) {
      super(
          ActionsTestUtil.NULL_ACTION_OWNER,
          NestedSetBuilder.create(Order.STABLE_ORDER, input),
          ImmutableSet.of(output));
      this.executionCounter = executionCounter;
    }

    @Override
    public ActionResult execute(ActionExecutionContext actionExecutionContext)
        throws ActionExecutionException, InterruptedException {
      executionCounter.incrementAndGet();

      // This action first reads its input file (there can be only one). For the purpose of these
      // tests we assume that the input file is short, maybe just 10 bytes long.
      byte[] input = new byte[10];
      int inputLen = 0;
      try (InputStream in = getInputs().getSingleton().getPath().getInputStream()) {
        inputLen = in.read(input);
      } catch (IOException e) {
        throw new ActionExecutionException(
            e, this, false, CrashFailureDetails.detailedExitCodeForThrowable(e));
      }

      // This action then writes the contents of the input to the (only) output file, and appends an
      // extra "x" character too.
      try (OutputStream out = getPrimaryOutput().getPath().getOutputStream()) {
        out.write(input, 0, inputLen);
        out.write('x');
      } catch (IOException e) {
        throw new ActionExecutionException(
            e, this, false, CrashFailureDetails.detailedExitCodeForThrowable(e));
      }
      return ActionResult.EMPTY;
    }

    @Override
    public String getMnemonic() {
      return "ExecutionCountingAction";
    }

    @Override
    protected void computeKey(
        ActionKeyContext actionKeyContext,
        @Nullable ArtifactExpander artifactExpander,
        Fingerprint fp) {
      fp.addString(getPrimaryOutput().getExecPathString());
      fp.addInt(executionCounter.get());
    }
  }

  private static class ExecutionCountingCacheBypassingAction extends ExecutionCountingAction {
    ExecutionCountingCacheBypassingAction(
        Artifact input, Artifact output, AtomicInteger executionCounter) {
      super(input, output, executionCounter);
    }

    @Override
    public boolean executeUnconditionally() {
      return true;
    }

    @Override
    public boolean isVolatile() {
      return true;
    }
  }

  /** A mock skyframe-aware action that counts how many times it was executed. */
  private static final class SkyframeAwareExecutionCountingAction
      extends ExecutionCountingCacheBypassingAction implements SkyframeAwareAction {
    private final SkyKey actionDepKey;

    SkyframeAwareExecutionCountingAction(
        Artifact input, Artifact output, AtomicInteger executionCounter, SkyKey actionDepKey) {
      super(input, output, executionCounter);
      this.actionDepKey = actionDepKey;
    }

    @Override
    public Object processSkyframeValues(
        ImmutableList<? extends SkyKey> keys, SkyframeLookupResult values, boolean valuesMissing) {
      assertThat(keys).containsExactly(actionDepKey);
      return null;
    }

    @Override
    public ImmutableList<SkyKey> getDirectSkyframeDependencies() {
      return ImmutableList.of(actionDepKey);
    }

    @Override
    public ImmutableGraph<SkyKey> getSkyframeDependenciesForRewinding(SkyKey self) {
      throw new UnsupportedOperationException();
    }
  }

  private interface ExecutionCountingActionFactory {
    ExecutionCountingAction create(Artifact input, Artifact output, AtomicInteger executionCounter);
  }

  private enum ChangeArtifact {
    DONT_CHANGE,
    CHANGE_MTIME {
      @Override
      boolean changeMtime() {
        return true;
      }
    },
    CHANGE_MTIME_AND_CONTENT {
      @Override
      boolean changeMtime() {
        return true;
      }

      @Override
      boolean changeContent() {
        return true;
      }
    };

    boolean changeMtime() {
      return false;
    }

    boolean changeContent() {
      return false;
    }
  }

  private enum ExpectActionIs {
    NOT_DIRTIED {
      @Override
      boolean actuallyClean() {
        return true;
      }
    },
    DIRTIED_BUT_VERIFIED_CLEAN {
      @Override
      boolean dirtied() {
        return true;
      }

      @Override
      boolean actuallyClean() {
        return true;
      }
    },

    // REBUILT_BUT_ACTION_CACHE_HIT,
    // This would be a bug, symptom of a skyframe-aware action that doesn't bypass the action cache
    // and is incorrectly regarded as an action cache hit when its inputs stayed the same but its
    // "skyframe dependencies" changed.

    REEXECUTED {
      @Override
      boolean dirtied() {
        return true;
      }

      @Override
      boolean reexecuted() {
        return true;
      }
    };

    boolean dirtied() {
      return false;
    }

    boolean actuallyClean() {
      return false;
    }

    boolean reexecuted() {
      return false;
    }
  }

  /** Ensure that a file's ctime was updated from an older value. */
  private static void checkCtimeUpdated(Path path, long oldCtime) throws IOException {
    if (oldCtime >= path.stat().getLastChangeTime()) {
      throw new IllegalStateException(String.format("path=(%s), ctime=(%d)", path, oldCtime));
    }
  }

  private void maybeChangeFile(Artifact file, ChangeArtifact changeRequest) throws Exception {
    if (changeRequest == ChangeArtifact.DONT_CHANGE) {
      return;
    }

    Path path = file.getPath();

    if (changeRequest.changeMtime()) {
      long ctime = path.stat().getLastChangeTime();
      // Ensure enough time elapsed for file updates to have a visible effect on the file's ctime.
      TimestampGranularityUtils.waitForTimestampGranularity(ctime, reporter.getOutErr());
      // waitForTimestampGranularity waits long enough for System.currentTimeMillis() to be greater
      // than the time at the setCommandStartTime() call. Therefore setting
      // System.currentTimeMillis() is guaranteed to advance the file's ctime.
      path.setLastModifiedTime(System.currentTimeMillis());
      checkCtimeUpdated(path, ctime);
    }

    if (changeRequest.changeContent()) {
      long ctime = path.stat().getLastChangeTime();
      // Ensure enough time elapsed for file updates to have a visible effect on the file's ctime.
      TimestampGranularityUtils.waitForTimestampGranularity(ctime, reporter.getOutErr());
      appendToFile(path);
      checkCtimeUpdated(path, ctime);
    }

    // Invalidate the file state value to inform Skyframe that the file may have changed.
    // This will also invalidate the action execution value.
    differencer.invalidate(
        ImmutableList.of(
            FileStateValue.key(
                RootedPath.toRootedPath(file.getRoot().getRoot(), file.getRootRelativePath()))));
  }

  private void assertActionExecutions(
      ExecutionCountingActionFactory actionFactory,
      ChangeArtifact changeActionInput,
      Callable<Void> betweenBuilds,
      ExpectActionIs expectActionIs)
      throws Exception {
    // Set up the action's input, output, owner and most importantly the execution counter.
    Artifact actionInput = createSourceArtifact("foo/action-input.txt");
    Artifact actionOutput = createDerivedArtifact("foo/action-output.txt");
    AtomicInteger executionCounter = new AtomicInteger(0);

    scratch.file(actionInput.getPath().getPathString(), "foo");

    // Generating actions of artifacts are found by looking them up in the graph. The lookup value
    // must be present in the graph before execution.
    Action action = actionFactory.create(actionInput, actionOutput, executionCounter);
    registerAction(action);

    // Build the output for the first time.
    builder.buildArtifacts(
        reporter,
        ImmutableSet.of(actionOutput),
        null,
        null,
        null,
        null,
        null,
        executor,
        options,
        null,
        null,
        OutputChecker.TRUST_LOCAL_ONLY);

    // Check that our invalidation receiver is working correctly. We'll rely on it again.
    SkyKey actionKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0);
    TrackingEvaluationProgressReceiver.EvaluatedEntry evaluatedAction =
        progressReceiver.getEvaluatedEntry(actionKey);
    assertThat(evaluatedAction).isNotNull();

    // Mutate the action input if requested.
    maybeChangeFile(actionInput, changeActionInput);

    // Execute user code before next build.
    betweenBuilds.call();

    // Rebuild the output.
    progressReceiver.reset();
    builder.buildArtifacts(
        reporter,
        ImmutableSet.of(actionOutput),
        null,
        null,
        null,
        null,
        null,
        executor,
        options,
        null,
        null,
        OutputChecker.TRUST_LOCAL_ONLY);

    if (expectActionIs.dirtied()) {
      assertThat(progressReceiver.wasInvalidated(actionKey)).isTrue();

      TrackingEvaluationProgressReceiver.EvaluatedEntry newEntry =
          progressReceiver.getEvaluatedEntry(actionKey);
      assertThat(newEntry).isNotNull();
      if (expectActionIs.actuallyClean()) {
        // Action was dirtied but verified clean.
        assertThat(newEntry.state.versionChanged()).isFalse();
      } else {
        // Action was dirtied and rebuilt. It was either reexecuted or was an action cache hit,
        // doesn't matter here.
        assertThat(newEntry.state.versionChanged()).isTrue();
      }
    } else {
      // Action was not dirtied.
      assertThat(progressReceiver.wasInvalidated(actionKey)).isFalse();
    }

    // Assert that the action was executed the right number of times. Whether the action execution
    // function was called again is up for the test method to verify.
    assertThat(executionCounter.get()).isEqualTo(expectActionIs.reexecuted() ? 2 : 1);
  }

  private RootedPath createSkyframeDepOfAction() throws Exception {
    scratch.file(rootDirectory.getRelative("action.dep").getPathString(), "blah");
    return RootedPath.toRootedPath(Root.fromPath(rootDirectory), PathFragment.create("action.dep"));
  }

  private static void appendToFile(Path path) throws Exception {
    try (OutputStream stm = path.getOutputStream(/*append=*/ true)) {
      stm.write("blah".getBytes(StandardCharsets.UTF_8));
    }
  }

  @Test
  public void testCacheCheckingActionWithContentChangingInput() throws Exception {
    assertActionWithContentChangingInput(/* unconditionalExecution= */ false);
  }

  @Test
  public void testCacheBypassingActionWithContentChangingInput() throws Exception {
    assertActionWithContentChangingInput(/* unconditionalExecution= */ true);
  }

  private void assertActionWithContentChangingInput(boolean unconditionalExecution)
      throws Exception {
    // Assert that a simple, non-skyframe-aware action is executed twice
    // if its input's content changes between builds.
    assertActionExecutions(
        new ExecutionCountingActionFactory() {
          @Override
          public ExecutionCountingAction create(
              Artifact input, Artifact output, AtomicInteger executionCounter) {
            return unconditionalExecution
                ? new ExecutionCountingCacheBypassingAction(input, output, executionCounter)
                : new ExecutionCountingAction(input, output, executionCounter);
          }
        },
        ChangeArtifact.CHANGE_MTIME_AND_CONTENT,
        Callables.<Void>returning(null),
        ExpectActionIs.REEXECUTED);
  }

  @Test
  public void testCacheCheckingActionWithMtimeChangingInput() throws Exception {
    assertActionWithMtimeChangingInput(/* unconditionalExecution= */ false);
  }

  @Test
  public void testCacheBypassingActionWithMtimeChangingInput() throws Exception {
    assertActionWithMtimeChangingInput(/* unconditionalExecution= */ true);
  }

  private void assertActionWithMtimeChangingInput(final boolean unconditionalExecution)
      throws Exception {
    // Assert that a simple, non-skyframe-aware action is executed only once
    // if its input's mtime changes but its contents stay the same between builds.
    assertActionExecutions(
        new ExecutionCountingActionFactory() {
          @Override
          public ExecutionCountingAction create(
              Artifact input, Artifact output, AtomicInteger executionCounter) {
            return unconditionalExecution
                ? new ExecutionCountingCacheBypassingAction(input, output, executionCounter)
                : new ExecutionCountingAction(input, output, executionCounter);
          }
        },
        ChangeArtifact.CHANGE_MTIME,
        Callables.<Void>returning(null),
        unconditionalExecution
            ? ExpectActionIs.REEXECUTED
            : ExpectActionIs.DIRTIED_BUT_VERIFIED_CLEAN);
  }

  @Test
  public void testCacheCheckingActionWithNonChangingInput() throws Exception {
    assertActionWithNonChangingInput(/* unconditionalExecution= */ false);
  }

  @Test
  public void testCacheBypassingActionWithNonChangingInput() throws Exception {
    assertActionWithNonChangingInput(/* unconditionalExecution= */ true);
  }

  private void assertActionWithNonChangingInput(boolean unconditionalExecution) throws Exception {
    // Assert that a simple, non-skyframe-aware action is executed only once
    // if its input does not change at all between builds.
    assertActionExecutions(
        new ExecutionCountingActionFactory() {
          @Override
          public ExecutionCountingAction create(
              Artifact input, Artifact output, AtomicInteger executionCounter) {
            return unconditionalExecution
                ? new ExecutionCountingCacheBypassingAction(input, output, executionCounter)
                : new ExecutionCountingAction(input, output, executionCounter);
          }
        },
        ChangeArtifact.DONT_CHANGE,
        Callables.<Void>returning(null),
        ExpectActionIs.NOT_DIRTIED);
  }

  private void assertActionWithMaybeChangingInputAndChangingSkyframeDeps(
      ChangeArtifact changeInputFile) throws Exception {
    final RootedPath depPath = createSkyframeDepOfAction();
    final SkyKey skyframeDep = FileStateValue.key(depPath);

    // Assert that an action-cache-check-bypassing action is executed twice if its skyframe deps
    // change while its input does not. The skyframe dependency is established by making the action
    // skyframe-aware and updating the value between builds.
    assertActionExecutions(
        new ExecutionCountingActionFactory() {
          @Override
          public ExecutionCountingAction create(
              Artifact input, Artifact output, AtomicInteger executionCounter) {
            return new SkyframeAwareExecutionCountingAction(
                input, output, executionCounter, skyframeDep);
          }
        },
        changeInputFile,
        new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            // Invalidate the dependency and change what its value will be in the next build. This
            // should enforce rebuilding of the action.
            appendToFile(depPath.asPath());
            differencer.invalidate(ImmutableList.of(skyframeDep));
            return null;
          }
        },
        ExpectActionIs.REEXECUTED);
  }

  @Test
  public void testActionWithNonChangingInputButChangingSkyframeDeps() throws Exception {
    assertActionWithMaybeChangingInputAndChangingSkyframeDeps(ChangeArtifact.DONT_CHANGE);
  }

  @Test
  public void testActionWithChangingInputMtimeAndChangingSkyframeDeps() throws Exception {
    assertActionWithMaybeChangingInputAndChangingSkyframeDeps(ChangeArtifact.CHANGE_MTIME);
  }

  @Test
  public void testActionWithChangingInputAndChangingSkyframeDeps() throws Exception {
    assertActionWithMaybeChangingInputAndChangingSkyframeDeps(
        ChangeArtifact.CHANGE_MTIME_AND_CONTENT);
  }

  @Test
  public void testActionWithNonChangingInputAndNonChangingSkyframeDeps() throws Exception {
    final SkyKey skyframeDep = FileStateValue.key(createSkyframeDepOfAction());

    // Assert that an action-cache-check-bypassing action is executed only once if neither its input
    // nor its Skyframe dependency changes between builds.
    assertActionExecutions(
        new ExecutionCountingActionFactory() {
          @Override
          public ExecutionCountingAction create(
              Artifact input, Artifact output, AtomicInteger executionCounter) {
            return new SkyframeAwareExecutionCountingAction(
                input, output, executionCounter, skyframeDep);
          }
        },
        ChangeArtifact.DONT_CHANGE,
        new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            // Invalidate the dependency but leave its value up-to-date, so the action should not
            // be rebuilt.
            differencer.invalidate(ImmutableList.of(skyframeDep));
            return null;
          }
        },
        ExpectActionIs.DIRTIED_BUT_VERIFIED_CLEAN);
  }

  private abstract static class SingleOutputAction extends AbstractAction {
    SingleOutputAction(@Nullable Artifact input, Artifact output) {
      super(
          ActionsTestUtil.NULL_ACTION_OWNER,
          input == null
              ? NestedSetBuilder.emptySet(Order.STABLE_ORDER)
              : NestedSetBuilder.create(Order.STABLE_ORDER, input),
          ImmutableSet.of(output));
    }

    protected static final class Buffer {
      final int size;
      final byte[] data;

      Buffer(byte[] data, int size) {
        this.data = data;
        this.size = size;
      }
    }

    protected Buffer readInput() throws ActionExecutionException {
      byte[] input = new byte[100];
      int inputLen = 0;
      try (InputStream in = getPrimaryInput().getPath().getInputStream()) {
        inputLen = in.read(input, 0, input.length);
      } catch (IOException e) {
        throw new ActionExecutionException(
            e, this, false, CrashFailureDetails.detailedExitCodeForThrowable(e));
      }
      return new Buffer(input, inputLen);
    }

    protected void writeOutput(@Nullable Buffer buf, String data) throws ActionExecutionException {
      try (OutputStream out = getPrimaryOutput().getPath().getOutputStream()) {
        if (buf != null) {
          out.write(buf.data, 0, buf.size);
        }
        out.write(data.getBytes(StandardCharsets.UTF_8), 0, data.length());
      } catch (IOException e) {
        throw new ActionExecutionException(
            e, this, false, CrashFailureDetails.detailedExitCodeForThrowable(e));
      }
    }

    @Override
    public String getMnemonic() {
      return "MockActionMnemonic";
    }

    @Override
    protected void computeKey(
        ActionKeyContext actionKeyContext,
        @Nullable ArtifactExpander artifactExpander,
        Fingerprint fp) {
      fp.addInt(42);
    }
  }

  private abstract static class SingleOutputSkyframeAwareAction extends SingleOutputAction
      implements SkyframeAwareAction {
    SingleOutputSkyframeAwareAction(@Nullable Artifact input, Artifact output) {
      super(input, output);
    }

    @Override
    public boolean executeUnconditionally() {
      return true;
    }

    @Override
    public boolean isVolatile() {
      return true;
    }
  }

  /**
   * Regression test to avoid a potential race condition in {@link ActionExecutionFunction}.
   *
   * <p>The test ensures that when ActionExecutionFunction executes a Skyframe-aware action
   * (implementor of {@link SkyframeAwareAction}), ActionExecutionFunction first requests the inputs
   * of the action and ensures they are built before requesting any of its Skyframe dependencies.
   *
   * <p>This strict ordering is very important to avoid the race condition, which could arise if the
   * compute method were too eager to request all dependencies: request input files but even if some
   * are missing, request also the skyframe-dependencies. The race is described in this method's
   * body.
   */
  @Test
  public void testRaceConditionBetweenInputAcquisitionAndSkyframeDeps() throws Exception {
    // Sequence of events on threads A and B, showing SkyFunctions and requested SkyKeys, leading
    // to an InconsistentFilesystemException:
    //
    // _______________[Thread A]_________________|_______________[Thread B]_________________
    // ActionExecutionFunction(gen2_action:      | idle
    //   genfiles/gen1 -> genfiles/foo/bar/gen2) |
    // ARTIFACT:genfiles/gen1                    |
    // MOCK_VALUE:dummy_argument                 |
    // env.valuesMissing():yes ==> return        |
    //                                           |
    // ArtifactFunction(genfiles/gen1)           | MockFunction()
    // CONFIGURED_TARGET://foo:gen1              | FILE:genfiles/foo
    // ACTION_EXECUTION:gen1_action              | env.valuesMissing():yes ==> return
    // env.valuesMissing():yes ==> return        |
    //                                           | FileFunction(genfiles/foo)
    // ActionExecutionFunction(gen1_action)      | FILE:genfiles
    // ARTIFACT:genfiles/gen0                    | env.valuesMissing():yes ==> return
    // env.valuesMissing():yes ==> return        |
    //                                           | FileFunction(genfiles)
    // ArtifactFunction(genfiles/gen0)           | FILE_STATE:genfiles
    // CONFIGURED_TARGET://foo:gen0              | env.valuesMissing():yes ==> return
    // ACTION_EXECUTION:gen0_action              |
    // env.valuesMissing():yes ==> return        | FileStateFunction(genfiles)
    //                                           | stat genfiles
    // ActionExecutionFunction(gen0_action)      | return FileStateValue:non-existent
    // create output directory: genfiles         |
    // working                                   | FileFunction(genfiles/foo)
    //                                           | FILE:genfiles
    //                                           | FILE_STATE:genfiles/foo
    //                                           | env.valuesMissing():yes ==> return
    //                                           |
    //                                           | FileStateFunction(genfiles/foo)
    //                                           | stat genfiles/foo
    //                                           | return FileStateValue:non-existent
    //                                           |
    // done, created genfiles/gen0               | FileFunction(genfiles/foo)
    // return ActionExecutionValue(gen0_action)  | FILE:genfiles
    //                                           | FILE_STATE:genfiles/foo
    // ArtifactFunction(genfiles/gen0)           | return FileValue(genfiles/foo:non-existent)
    // CONFIGURED_TARGET://foo:gen0              |
    // ACTION_EXECUTION:gen0_action              | MockFunction()
    // return ArtifactSkyKey(genfiles/gen0)      | FILE:genfiles/foo
    //                                           | FILE:genfiles/foo/bar/gen1
    // ActionExecutionFunction(gen1_action)      | env.valuesMissing():yes ==> return
    // ARTIFACT:genfiles/gen0                    |
    // create output directory: genfiles/foo/bar | FileFunction(genfiles/foo/bar/gen1)
    // done, created genfiles/foo/bar/gen1       | FILE:genfiles/foo/bar
    // return ActionExecutionValue(gen1_action)  | env.valuesMissing():yes ==> return
    //                                           |
    // idle                                      | FileFunction(genfiles/foo/bar)
    //                                           | FILE:genfiles/foo
    //                                           | FILE_STATE:genfiles/foo/bar
    //                                           | env.valuesMissing():yes ==> return
    //                                           |
    //                                           | FileStateFunction(genfiles/foo/bar)
    //                                           | stat genfiles/foo/bar
    //                                           | return FileStateValue:directory
    //                                           |
    //                                           | FileFunction(genfiles/foo/bar)
    //                                           | FILE:genfiles/foo
    //                                           | FILE_STATE:genfiles/foo/bar
    //                                           | throw InconsistentFilesystemException:
    //                                           |     genfiles/foo doesn't exist but
    //                                           |     genfiles/foo/bar does!

    Artifact genFile1 = createDerivedArtifact("foo/bar/gen1.txt");
    Artifact genFile2 = createDerivedArtifact("gen2.txt");

    registerAction(
        new SingleOutputAction(null, genFile1) {
          @Override
          public ActionResult execute(ActionExecutionContext actionExecutionContext)
              throws ActionExecutionException, InterruptedException {
            writeOutput(null, "gen1");
            return ActionResult.EMPTY;
          }
        });

    registerAction(
        new SingleOutputSkyframeAwareAction(genFile1, genFile2) {

          @Override
          public ImmutableList<SkyKey> getDirectSkyframeDependencies() {
            return ImmutableList.of();
          }

          @Override
          public Object processSkyframeValues(
              ImmutableList<? extends SkyKey> keys,
              SkyframeLookupResult values,
              boolean valuesMissing) {
            assertThat(keys).isEmpty();
            assertThat(valuesMissing).isFalse();
            return null;
          }

          @Override
          public ImmutableGraph<SkyKey> getSkyframeDependenciesForRewinding(SkyKey self) {
            throw new UnsupportedOperationException();
          }

          @Override
          public ActionResult execute(ActionExecutionContext actionExecutionContext)
              throws ActionExecutionException {
            writeOutput(readInput(), "gen2");
            return ActionResult.EMPTY;
          }
        });

    builder.buildArtifacts(
        reporter,
        ImmutableSet.of(genFile2),
        null,
        null,
        null,
        null,
        null,
        executor,
        options,
        null,
        null,
        OutputChecker.TRUST_LOCAL_ONLY);
  }
}
