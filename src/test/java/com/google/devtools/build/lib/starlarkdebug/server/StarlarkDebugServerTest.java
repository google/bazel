// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.starlarkdebug.server;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.util.EventCollectionApparatus;
import com.google.devtools.build.lib.starlarkdebug.server.StarlarkDebugServer.DebugCallback;
import com.google.devtools.build.lib.starlarkdebugging.StarlarkDebuggingProtos;
import com.google.devtools.build.lib.starlarkdebugging.StarlarkDebuggingProtos.Breakpoint;
import com.google.devtools.build.lib.starlarkdebugging.StarlarkDebuggingProtos.ContinueExecutionRequest;
import com.google.devtools.build.lib.starlarkdebugging.StarlarkDebuggingProtos.DebugEvent;
import com.google.devtools.build.lib.starlarkdebugging.StarlarkDebuggingProtos.DebugRequest;
import com.google.devtools.build.lib.starlarkdebugging.StarlarkDebuggingProtos.EvaluateRequest;
import com.google.devtools.build.lib.starlarkdebugging.StarlarkDebuggingProtos.Frame;
import com.google.devtools.build.lib.starlarkdebugging.StarlarkDebuggingProtos.ListFramesRequest;
import com.google.devtools.build.lib.starlarkdebugging.StarlarkDebuggingProtos.ListFramesResponse;
import com.google.devtools.build.lib.starlarkdebugging.StarlarkDebuggingProtos.Location;
import com.google.devtools.build.lib.starlarkdebugging.StarlarkDebuggingProtos.PauseReason;
import com.google.devtools.build.lib.starlarkdebugging.StarlarkDebuggingProtos.PausedThread;
import com.google.devtools.build.lib.starlarkdebugging.StarlarkDebuggingProtos.Scope;
import com.google.devtools.build.lib.starlarkdebugging.StarlarkDebuggingProtos.SetBreakpointsRequest;
import com.google.devtools.build.lib.starlarkdebugging.StarlarkDebuggingProtos.StartDebuggingRequest;
import com.google.devtools.build.lib.starlarkdebugging.StarlarkDebuggingProtos.StartDebuggingResponse;
import com.google.devtools.build.lib.starlarkdebugging.StarlarkDebuggingProtos.Stepping;
import com.google.devtools.build.lib.starlarkdebugging.StarlarkDebuggingProtos.Value;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.starlark.java.eval.Debug;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.SyntaxError;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Integration tests for {@link StarlarkDebugServer}. */
@RunWith(JUnit4.class)
public class StarlarkDebugServerTest {

  private final ExecutorService executor = Executors.newFixedThreadPool(2);
  private final EventCollectionApparatus events =
      new EventCollectionApparatus(EventKind.ALL_EVENTS);
  private final ThreadObjectMap dummyObjectMap = new ThreadObjectMap();

  private MockDebugClient client;
  private StarlarkDebugServer server;

  /**
   * Returns the {@link Value} proto message corresponding to the given object and label. Subsequent
   * calls may return values with different IDs.
   */
  private Value getValueProto(String label, Object value) {
    return DebuggerSerialization.getValueProto(dummyObjectMap, label, value);
  }

  private ImmutableList<Value> getChildren(Value value) {
    Object object = dummyObjectMap.getValue(value.getId());
    return object != null
        ? DebuggerSerialization.getChildren(dummyObjectMap, object)
        : ImmutableList.of();
  }

  private static ServerSocket getServerSocket() throws IOException {
    return new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
  }

  @Before
  public void setUpServerAndClient() throws Exception {
    ServerSocket serverSocket = getServerSocket();
    Future<StarlarkDebugServer> future =
        executor.submit(
            () ->
                StarlarkDebugServer.createAndWaitForConnection(
                    events.reporter(), serverSocket, false, DebugCallback.noop()));
    client = new MockDebugClient();
    client.connect(
        serverSocket.getInetAddress(), serverSocket.getLocalPort(), Duration.ofSeconds(10));

    server = future.get(10, TimeUnit.SECONDS);
    assertThat(server).isNotNull();
    Debug.setDebugger(server);
  }

  @After
  public void shutDown() throws Exception {
    if (client != null) {
      client.close();
    }
    if (server != null) {
      server.close();
    }
  }

  @Test
  public void testStartDebuggingResponseReceived() throws Exception {
    DebugEvent response =
        client.sendRequestAndWaitForResponse(
            DebugRequest.newBuilder()
                .setSequenceNumber(1)
                .setStartDebugging(StartDebuggingRequest.getDefaultInstance())
                .build());
    assertThat(response)
        .isEqualTo(
            DebugEvent.newBuilder()
                .setSequenceNumber(1)
                .setStartDebugging(StartDebuggingResponse.newBuilder().build())
                .build());
  }

  @Test
  public void testPausedUntilStartDebuggingRequestReceived() throws Exception {
    ParserInput buildFile = createInput("/a/build/file/BUILD", "x = [1,2,3]");

    Thread evaluationThread = execInWorkerThread(buildFile, null);
    String threadName = evaluationThread.getName();
    long threadId = evaluationThread.getId();

    // wait for BUILD evaluation to start
    DebugEvent event = client.waitForEvent(DebugEvent::hasThreadPaused, Duration.ofSeconds(5));

    Location expectedLocation =
        DebugEventHelper.getLocationProto(
            net.starlark.java.syntax.Location.fromFileLineColumn("/a/build/file/BUILD", 1, 1));

    assertThat(event)
        .isEqualTo(
            DebugEventHelper.threadPausedEvent(
                StarlarkDebuggingProtos.PausedThread.newBuilder()
                    .setId(threadId)
                    .setName(threadName)
                    .setPauseReason(PauseReason.INITIALIZING)
                    .setLocation(expectedLocation)
                    .build()));

    sendStartDebuggingRequest();
    event = client.waitForEvent(DebugEvent::hasThreadContinued, Duration.ofSeconds(5));
    assertThat(event).isEqualTo(DebugEventHelper.threadContinuedEvent(threadId));
  }

  @Test
  public void testResumeAllThreads() throws Exception {
    sendStartDebuggingRequest();
    ParserInput buildFile =
        createInput(
            "/a/build/file/BUILD",
            """
            x = [1,2,3]
            y = [2,3,4]
            """);

    Location breakpoint =
        Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD").build();
    setBreakpoints(ImmutableList.of(breakpoint));

    // evaluate in two separate worker threads
    execInWorkerThread(buildFile, null);
    execInWorkerThread(buildFile, null);

    // wait for both breakpoints to be hit
    boolean paused =
        client.waitForEvents(
            list -> list.stream().filter(DebugEvent::hasThreadPaused).count() == 2,
            Duration.ofSeconds(5));

    assertThat(paused).isTrue();

    client.sendRequestAndWaitForResponse(
        DebugRequest.newBuilder()
            .setSequenceNumber(45)
            .setContinueExecution(ContinueExecutionRequest.getDefaultInstance())
            .build());

    boolean resumed =
        client.waitForEvents(
            list -> list.stream().filter(DebugEvent::hasThreadContinued).count() == 2,
            Duration.ofSeconds(5));

    assertThat(resumed).isTrue();
  }

  @Test
  public void testPauseAtBreakpoint() throws Exception {
    sendStartDebuggingRequest();
    ParserInput buildFile =
        createInput(
            "/a/build/file/BUILD",
            """
            x = [1,2,3]
            y = [2,3,4]
            """);

    Location breakpoint =
        Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD").build();
    setBreakpoints(ImmutableList.of(breakpoint));

    Thread evaluationThread = execInWorkerThread(buildFile, null);
    String threadName = evaluationThread.getName();
    long threadId = evaluationThread.getId();

    // wait for breakpoint to be hit
    DebugEvent event = client.waitForEvent(DebugEvent::hasThreadPaused, Duration.ofSeconds(5));

    StarlarkDebuggingProtos.PausedThread expectedThreadState =
        StarlarkDebuggingProtos.PausedThread.newBuilder()
            .setName(threadName)
            .setId(threadId)
            .setPauseReason(PauseReason.HIT_BREAKPOINT)
            .setLocation(breakpoint.toBuilder().setColumnNumber(1))
            .build();

    assertThat(event).isEqualTo(DebugEventHelper.threadPausedEvent(expectedThreadState));
  }

  @Test
  public void testDoNotPauseAtUnsatisfiedConditionalBreakpoint() throws Exception {
    sendStartDebuggingRequest();
    ParserInput buildFile =
        createInput(
            "/a/build/file/BUILD",
            """
            x = [1,2,3]
            y = [2,3,4]
            z = 1
            """);

    ImmutableList<Breakpoint> breakpoints =
        ImmutableList.of(
            Breakpoint.newBuilder()
                .setLocation(Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD"))
                .setExpression("x[0] == 2")
                .build(),
            Breakpoint.newBuilder()
                .setLocation(Location.newBuilder().setLineNumber(3).setPath("/a/build/file/BUILD"))
                .setExpression("x[0] == 1")
                .build());
    setBreakpoints(breakpoints);

    Thread evaluationThread = execInWorkerThread(buildFile, null);
    String threadName = evaluationThread.getName();
    long threadId = evaluationThread.getId();
    Breakpoint expectedBreakpoint = breakpoints.get(1);

    DebugEvent event = client.waitForEvent(DebugEvent::hasThreadPaused, Duration.ofSeconds(5));
    assertThat(event)
        .isEqualTo(
            DebugEventHelper.threadPausedEvent(
                StarlarkDebuggingProtos.PausedThread.newBuilder()
                    .setName(threadName)
                    .setId(threadId)
                    .setLocation(expectedBreakpoint.getLocation().toBuilder().setColumnNumber(1))
                    .setPauseReason(PauseReason.HIT_BREAKPOINT)
                    .build()));
  }

  @Test
  public void testPauseAtSatisfiedConditionalBreakpoint() throws Exception {
    sendStartDebuggingRequest();
    ParserInput buildFile =
        createInput(
            "/a/build/file/BUILD",
            """
            x = [1,2,3]
            y = [2,3,4]
            """);

    Location location =
        Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD").build();
    Breakpoint breakpoint =
        Breakpoint.newBuilder().setLocation(location).setExpression("x[0] == 1").build();
    setBreakpoints(ImmutableList.of(breakpoint));

    Thread evaluationThread = execInWorkerThread(buildFile, null);
    String threadName = evaluationThread.getName();
    long threadId = evaluationThread.getId();

    // wait for breakpoint to be hit
    DebugEvent event = client.waitForEvent(DebugEvent::hasThreadPaused, Duration.ofSeconds(5));

    StarlarkDebuggingProtos.PausedThread expectedThreadState =
        StarlarkDebuggingProtos.PausedThread.newBuilder()
            .setName(threadName)
            .setId(threadId)
            .setPauseReason(PauseReason.HIT_BREAKPOINT)
            .setLocation(location.toBuilder().setColumnNumber(1))
            .build();

    assertThat(event).isEqualTo(DebugEventHelper.threadPausedEvent(expectedThreadState));
  }

  @Test
  public void testPauseAtInvalidConditionBreakpointWithError() throws Exception {
    sendStartDebuggingRequest();
    ParserInput buildFile =
        createInput(
            "/a/build/file/BUILD",
            """
            x = [1,2,3]
            y = [2,3,4]
            """);

    Location location =
        Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD").build();
    Breakpoint breakpoint =
        Breakpoint.newBuilder().setLocation(location).setExpression("z[0] == 1").build();
    setBreakpoints(ImmutableList.of(breakpoint));

    Thread evaluationThread = execInWorkerThread(buildFile, null);
    String threadName = evaluationThread.getName();
    long threadId = evaluationThread.getId();

    // wait for breakpoint to be hit
    DebugEvent event = client.waitForEvent(DebugEvent::hasThreadPaused, Duration.ofSeconds(5));

    StarlarkDebuggingProtos.PausedThread expectedThreadState =
        StarlarkDebuggingProtos.PausedThread.newBuilder()
            .setName(threadName)
            .setId(threadId)
            .setPauseReason(PauseReason.CONDITIONAL_BREAKPOINT_ERROR)
            .setLocation(location.toBuilder().setColumnNumber(1))
            .setConditionalBreakpointError(
                StarlarkDebuggingProtos.Error.newBuilder().setMessage("name 'z' is not defined"))
            .build();

    assertThat(event).isEqualTo(DebugEventHelper.threadPausedEvent(expectedThreadState));
  }

  @Test
  public void testListFramesForInvalidThread() throws Exception {
    sendStartDebuggingRequest();
    DebugEvent event =
        client.sendRequestAndWaitForResponse(
            DebugRequest.newBuilder()
                .setSequenceNumber(1)
                .setListFrames(ListFramesRequest.newBuilder().setThreadId(20).build())
                .build());
    assertThat(event.hasError()).isTrue();
    assertThat(event.getError().getMessage()).contains("Thread 20 is not paused");
  }

  @Test
  public void testSimpleListFramesRequest() throws Exception {
    sendStartDebuggingRequest();
    ParserInput buildFile =
        createInput(
            "/a/build/file/BUILD",
            """
            x = [1,2,3]
            y = [2,3,4]
            """);

    Location breakpoint =
        Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD").build();
    setBreakpoints(ImmutableList.of(breakpoint));

    Thread evaluationThread = execInWorkerThread(buildFile, null);
    long threadId = evaluationThread.getId();

    // wait for breakpoint to be hit
    client.waitForEvent(DebugEvent::hasThreadPaused, Duration.ofSeconds(5));

    ListFramesResponse frames = listFrames(threadId);
    assertThat(frames.getFrameCount()).isEqualTo(1);
    assertFramesEqualIgnoringValueIdentifiers(
        frames.getFrame(0),
        Frame.newBuilder()
            .setFunctionName(StarlarkThread.TOP_LEVEL)
            .setLocation(breakpoint.toBuilder().setColumnNumber(1))
            .addScope(
                Scope.newBuilder()
                    .setName("global")
                    .addBinding(
                        getValueProto(
                            "x",
                            StarlarkList.immutableOf(
                                StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3)))))
            .build());
  }

  @Test
  public void testListFramesWithUninitializedCellRequest() throws Exception {
    sendStartDebuggingRequest();
    ParserInput bzlFile =
        createInput(
            "/a/build/file/foo.bzl",
            """
            def outer():
               x = [1,2,3] # <- breakpoint

               def inner():
                   x.append(4)

               inner()

            outer()
            """);

    Location breakpoint =
        Location.newBuilder().setLineNumber(2).setPath("/a/build/file/foo.bzl").build();
    setBreakpoints(ImmutableList.of(breakpoint));

    Module module = Module.create();
    Thread evaluationThread = execInWorkerThread(bzlFile, module);
    long threadId = evaluationThread.getId();

    // wait for breakpoint to be hit
    client.waitForEvent(DebugEvent::hasThreadPaused, Duration.ofSeconds(5));

    ListFramesResponse frames = listFrames(threadId);
    assertThat(frames.getFrameCount()).isEqualTo(2);
    // critically x is not present as a local
    assertFramesEqualIgnoringValueIdentifiers(
        frames.getFrame(0),
        Frame.newBuilder()
            .setFunctionName("outer")
            .setLocation(breakpoint.toBuilder().setColumnNumber(4))
            .addScope(
                Scope.newBuilder()
                    .setName("global")
                    .addBinding(getValueProto("outer", module.getGlobal("outer"))))
            .build());
    assertFramesEqualIgnoringValueIdentifiers(
        frames.getFrame(1),
        Frame.newBuilder()
            .setFunctionName(StarlarkThread.TOP_LEVEL)
            .setLocation(breakpoint.toBuilder().setLineNumber(9).setColumnNumber(6))
            .addScope(
                Scope.newBuilder()
                    .setName("global")
                    .addBinding(getValueProto("outer", module.getGlobal("outer"))))
            .build());
  }

  @Test
  public void testListFramesWithInitializedCellRequest() throws Exception {
    sendStartDebuggingRequest();
    ParserInput bzlFile =
        createInput(
            "/a/build/file/foo.bzl",
            """
            def outer():
               x = [1]
               pass          # <- breakpoint

               def inner():
                   x.append(4)

               inner()

            outer()
            """);

    Location breakpoint =
        Location.newBuilder().setLineNumber(3).setPath("/a/build/file/foo.bzl").build();
    setBreakpoints(ImmutableList.of(breakpoint));

    Module module = Module.create();
    Thread evaluationThread = execInWorkerThread(bzlFile, module);
    long threadId = evaluationThread.getId();

    // wait for breakpoint to be hit
    client.waitForEvent(DebugEvent::hasThreadPaused, Duration.ofSeconds(5));

    ListFramesResponse frames = listFrames(threadId);
    assertThat(frames.getFrameCount()).isEqualTo(2);
    // critically x is present as a local
    assertFramesEqualIgnoringValueIdentifiers(
        frames.getFrame(0),
        Frame.newBuilder()
            .setFunctionName("outer")
            .setLocation(breakpoint.toBuilder().setColumnNumber(4))
            .addScope(
                Scope.newBuilder()
                    .setName("local")
                    .addBinding(getValueProto("x", StarlarkList.immutableOf(StarlarkInt.of(1)))))
            .addScope(
                Scope.newBuilder()
                    .setName("global")
                    .addBinding(getValueProto("outer", module.getGlobal("outer"))))
            .build());
    assertFramesEqualIgnoringValueIdentifiers(
        frames.getFrame(1),
        Frame.newBuilder()
            .setFunctionName(StarlarkThread.TOP_LEVEL)
            .setLocation(breakpoint.toBuilder().setLineNumber(10).setColumnNumber(6))
            .addScope(
                Scope.newBuilder()
                    .setName("global")
                    .addBinding(getValueProto("outer", module.getGlobal("outer"))))
            .build());
  }

  @Test
  public void testGetChildrenRequest() throws Exception {
    sendStartDebuggingRequest();
    ParserInput buildFile =
        createInput(
            "/a/build/file/BUILD",
            """
            x = [1,2,3]
            y = [2,3,4]
            """);

    Location breakpoint =
        Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD").build();
    setBreakpoints(ImmutableList.of(breakpoint));

    Thread evaluationThread = execInWorkerThread(buildFile, null);
    long threadId = evaluationThread.getId();

    // wait for breakpoint to be hit
    client.waitForEvent(DebugEvent::hasThreadPaused, Duration.ofSeconds(5));

    ListFramesResponse frames = listFrames(threadId);
    Value xValue = frames.getFrame(0).getScope(0).getBinding(0);

    assertValuesEqualIgnoringId(
        xValue,
        getValueProto(
            "x",
            StarlarkList.immutableOf(StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3))));

    List<Value> children = getChildren(xValue);

    assertThat(children)
        .isEqualTo(
            ImmutableList.of(
                getValueProto("[0]", 1), getValueProto("[1]", 2), getValueProto("[2]", 3)));
  }

  @Test
  public void testListFramesShadowedBinding() throws Exception {
    sendStartDebuggingRequest();
    ParserInput bzlFile =
        createInput(
            "/a/build/file/test.bzl",
            """
            a = 1
            c = 3
            def fn():
              a = 2
              b = 1
              b + 1
            fn()
            """);

    Location breakpoint =
        Location.newBuilder().setPath("/a/build/file/test.bzl").setLineNumber(6).build();
    setBreakpoints(ImmutableList.of(breakpoint));

    Module module = Module.create();
    Thread evaluationThread = execInWorkerThread(bzlFile, module);
    long threadId = evaluationThread.getId();

    // wait for breakpoint to be hit
    client.waitForEvent(DebugEvent::hasThreadPaused, Duration.ofSeconds(5));

    ListFramesResponse frames = listFrames(threadId);
    assertThat(frames.getFrameCount()).isEqualTo(2);

    assertFramesEqualIgnoringValueIdentifiers(
        frames.getFrame(0),
        Frame.newBuilder()
            .setFunctionName("fn")
            .setLocation(breakpoint.toBuilder().setColumnNumber(3))
            .addScope(
                Scope.newBuilder()
                    .setName("local")
                    .addBinding(getValueProto("a", StarlarkInt.of(2)))
                    .addBinding(getValueProto("b", StarlarkInt.of(1))))
            .addScope(
                Scope.newBuilder()
                    .setName("global")
                    .addBinding(getValueProto("c", StarlarkInt.of(3)))
                    .addBinding(getValueProto("fn", module.getGlobal("fn"))))
            .build());

    assertFramesEqualIgnoringValueIdentifiers(
        frames.getFrame(1),
        Frame.newBuilder()
            .setFunctionName(StarlarkThread.TOP_LEVEL)
            .setLocation(
                Location.newBuilder()
                    .setPath("/a/build/file/test.bzl")
                    .setLineNumber(7)
                    .setColumnNumber(3))
            .addScope(
                Scope.newBuilder()
                    .setName("global")
                    .addBinding(getValueProto("a", StarlarkInt.of(1)))
                    .addBinding(getValueProto("c", StarlarkInt.of(3)))
                    .addBinding(getValueProto("fn", module.getGlobal("fn"))))
            .build());
  }

  @Test
  public void testEvaluateRequestWithExpression() throws Exception {
    sendStartDebuggingRequest();
    ParserInput buildFile =
        createInput(
            "/a/build/file/BUILD",
            """
            x = [1,2,3]
            y = [2,3,4]
            """);

    Location breakpoint =
        Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD").build();
    setBreakpoints(ImmutableList.of(breakpoint));

    Thread evaluationThread = execInWorkerThread(buildFile, null);
    long threadId = evaluationThread.getId();

    // wait for breakpoint to be hit
    client.waitForEvent(DebugEvent::hasThreadPaused, Duration.ofSeconds(5));

    DebugEvent response =
        client.sendRequestAndWaitForResponse(
            DebugRequest.newBuilder()
                .setSequenceNumber(123)
                .setEvaluate(
                    EvaluateRequest.newBuilder().setThreadId(threadId).setStatement("x[1]").build())
                .build());
    assertThat(response.hasEvaluate()).isTrue();
    assertThat(response.getEvaluate().getResult())
        .isEqualTo(getValueProto("Evaluation result", StarlarkInt.of(2)));
  }

  @Test
  public void testEvaluateRequestWithAssignmentStatement() throws Exception {
    sendStartDebuggingRequest();
    ParserInput buildFile =
        createInput(
            "/a/build/file/BUILD",
            """
            x = [1,2,3]
            y = [2,3,4]
            """);

    Location breakpoint =
        Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD").build();
    setBreakpoints(ImmutableList.of(breakpoint));

    Thread evaluationThread = execInWorkerThread(buildFile, null);
    long threadId = evaluationThread.getId();

    // wait for breakpoint to be hit
    client.waitForEvent(DebugEvent::hasThreadPaused, Duration.ofSeconds(5));

    DebugEvent response =
        client.sendRequestAndWaitForResponse(
            DebugRequest.newBuilder()
                .setSequenceNumber(123)
                .setEvaluate(
                    EvaluateRequest.newBuilder()
                        .setThreadId(threadId)
                        .setStatement("x = [5,6]")
                        .build())
                .build());
    assertThat(response.getEvaluate().getResult())
        .isEqualTo(getValueProto("Evaluation result", Starlark.NONE));

    ListFramesResponse frames = listFrames(threadId);
    assertThat(frames.getFrame(0).getScope(0).getBindingList())
        .contains(
            getValueProto(
                "x", StarlarkList.of(/*mutability=*/ null, StarlarkInt.of(5), StarlarkInt.of(6))));
  }

  @Test
  public void testEvaluateRequestWithExpressionStatementMutatingState() throws Exception {
    sendStartDebuggingRequest();
    ParserInput buildFile =
        createInput(
            "/a/build/file/BUILD",
            """
            x = [1,2,3]
            y = [2,3,4]
            """);

    Location breakpoint =
        Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD").build();
    setBreakpoints(ImmutableList.of(breakpoint));

    Thread evaluationThread = execInWorkerThread(buildFile, null);
    long threadId = evaluationThread.getId();

    // wait for breakpoint to be hit
    client.waitForEvent(DebugEvent::hasThreadPaused, Duration.ofSeconds(5));

    DebugEvent response =
        client.sendRequestAndWaitForResponse(
            DebugRequest.newBuilder()
                .setSequenceNumber(123)
                .setEvaluate(
                    EvaluateRequest.newBuilder()
                        .setThreadId(threadId)
                        .setStatement("x.append(4)")
                        .build())
                .build());
    assertThat(response.getEvaluate().getResult())
        .isEqualTo(getValueProto("Evaluation result", Starlark.NONE));

    ListFramesResponse frames = listFrames(threadId);
    assertThat(frames.getFrame(0).getScope(0).getBindingList())
        .contains(
            getValueProto(
                "x",
                StarlarkList.immutableOf(
                    StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3), StarlarkInt.of(4))));
  }

  @Test
  public void testEvaluateRequestThrowingException() throws Exception {
    sendStartDebuggingRequest();
    ParserInput buildFile =
        createInput(
            "/a/build/file/BUILD",
            """
            x = [1,2,3]
            y = [2,3,4]
            """);

    Location breakpoint =
        Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD").build();
    setBreakpoints(ImmutableList.of(breakpoint));

    Thread evaluationThread = execInWorkerThread(buildFile, null);
    long threadId = evaluationThread.getId();

    // wait for breakpoint to be hit
    client.waitForEvent(DebugEvent::hasThreadPaused, Duration.ofSeconds(5));

    DebugEvent response =
        client.sendRequestAndWaitForResponse(
            DebugRequest.newBuilder()
                .setSequenceNumber(123)
                .setEvaluate(
                    EvaluateRequest.newBuilder().setThreadId(threadId).setStatement("z[0]").build())
                .build());
    assertThat(response.hasError()).isTrue();
    assertThat(response.getError().getMessage()).isEqualTo("name 'z' is not defined");
  }

  // b/143713658
  @Test
  public void testEvaluateRequest_resolvesGlobalsAndLocals() throws Exception {
    sendStartDebuggingRequest();
    ParserInput buildFile =
        createInput(
            "/a/build/file/foo.bzl",
            """
            _global = [1,2,3]

            def _func(my_arg):
              pass

            _func(my_arg = [4,5,6])
            """);

    Location breakpoint =
        Location.newBuilder().setLineNumber(4).setPath("/a/build/file/foo.bzl").build();
    setBreakpoints(ImmutableList.of(breakpoint));

    Thread evaluationThread = execInWorkerThread(buildFile, null);
    long threadId = evaluationThread.getId();

    // wait for breakpoint to be hit
    client.waitForEvent(DebugEvent::hasThreadPaused, Duration.ofSeconds(5));

    DebugEvent responseForGlobal =
        client.sendRequestAndWaitForResponse(
            DebugRequest.newBuilder()
                .setSequenceNumber(123)
                .setEvaluate(
                    EvaluateRequest.newBuilder()
                        .setThreadId(threadId)
                        .setStatement("_global[1]")
                        .build())
                .build());

    assertThat(responseForGlobal.hasEvaluate()).isTrue();
    assertThat(responseForGlobal.getEvaluate().getResult())
        .isEqualTo(getValueProto("Evaluation result", StarlarkInt.of(2)));

    DebugEvent responseForLocal =
        client.sendRequestAndWaitForResponse(
            DebugRequest.newBuilder()
                .setSequenceNumber(124)
                .setEvaluate(
                    EvaluateRequest.newBuilder()
                        .setThreadId(threadId)
                        .setStatement("my_arg[1]")
                        .build())
                .build());

    assertThat(responseForLocal.hasError()).isTrue();
    assertThat(responseForLocal.getError().getMessage()).isEqualTo("name 'my_arg' is not defined");
  }

  @Test
  public void testStepIntoFunction() throws Exception {
    sendStartDebuggingRequest();
    ParserInput bzlFile =
        createInput(
            "/a/build/file/test.bzl",
            """
            def fn():
              a = 2
              return a
            x = fn()
            y = [2,3,4]
            """);

    Location breakpoint =
        Location.newBuilder().setLineNumber(4).setPath("/a/build/file/test.bzl").build();
    setBreakpoints(ImmutableList.of(breakpoint));

    Thread evaluationThread = execInWorkerThread(bzlFile, null);
    long threadId = evaluationThread.getId();

    // wait for breakpoint to be hit
    DebugEvent event = client.waitForEvent(DebugEvent::hasThreadPaused, Duration.ofSeconds(5));

    assertThat(event.getThreadPaused().getThread().getLocation().getLineNumber()).isEqualTo(4);

    client.unnumberedEvents.clear();
    client.sendRequestAndWaitForResponse(
        DebugRequest.newBuilder()
            .setSequenceNumber(2)
            .setContinueExecution(
                ContinueExecutionRequest.newBuilder()
                    .setThreadId(threadId)
                    .setStepping(Stepping.INTO)
                    .build())
            .build());
    event = client.waitForEvent(DebugEvent::hasThreadPaused, Duration.ofSeconds(5));

    // check we're paused inside the function
    assertThat(listFrames(threadId).getFrameCount()).isEqualTo(2);

    // and verify the location and pause reason as well
    Location expectedLocation = breakpoint.toBuilder().setLineNumber(2).setColumnNumber(3).build();

    StarlarkDebuggingProtos.PausedThread pausedThread = event.getThreadPaused().getThread();
    assertThat(pausedThread.getPauseReason()).isEqualTo(PauseReason.STEPPING);
    assertThat(pausedThread.getLocation()).isEqualTo(expectedLocation);
  }

  @Test
  public void testStepOverFunction() throws Exception {
    sendStartDebuggingRequest();
    ParserInput bzlFile =
        createInput(
            "/a/build/file/test.bzl",
            """
            def fn():
              a = 2
              return a
            x = fn()
            y = [2,3,4]
            """);

    Location breakpoint =
        Location.newBuilder().setLineNumber(4).setPath("/a/build/file/test.bzl").build();
    setBreakpoints(ImmutableList.of(breakpoint));

    Thread evaluationThread = execInWorkerThread(bzlFile, null);
    long threadId = evaluationThread.getId();

    // wait for breakpoint to be hit
    DebugEvent event = client.waitForEvent(DebugEvent::hasThreadPaused, Duration.ofSeconds(5));

    assertThat(event.getThreadPaused().getThread().getLocation().getLineNumber()).isEqualTo(4);

    client.unnumberedEvents.clear();
    client.sendRequestAndWaitForResponse(
        DebugRequest.newBuilder()
            .setSequenceNumber(2)
            .setContinueExecution(
                ContinueExecutionRequest.newBuilder()
                    .setThreadId(threadId)
                    .setStepping(Stepping.OVER)
                    .build())
            .build());
    event = client.waitForEvent(DebugEvent::hasThreadPaused, Duration.ofSeconds(5));

    Location expectedLocation = breakpoint.toBuilder().setLineNumber(5).setColumnNumber(1).build();
    PausedThread pausedThread = event.getThreadPaused().getThread();
    assertThat(pausedThread.getPauseReason()).isEqualTo(PauseReason.STEPPING);
    assertThat(pausedThread.getLocation()).isEqualTo(expectedLocation);
  }

  @Test
  public void testStepOutOfFunction() throws Exception {
    sendStartDebuggingRequest();
    ParserInput bzlFile =
        createInput(
            "/a/build/file/test.bzl",
            """
            def fn():
              a = 2
              return a
            x = fn()
            y = [2,3,4]
            """);

    Location breakpoint =
        Location.newBuilder().setLineNumber(2).setPath("/a/build/file/test.bzl").build();
    setBreakpoints(ImmutableList.of(breakpoint));

    Thread evaluationThread = execInWorkerThread(bzlFile, null);
    long threadId = evaluationThread.getId();

    // wait for breakpoint to be hit
    client.waitForEvent(DebugEvent::hasThreadPaused, Duration.ofSeconds(5));

    assertThat(listFrames(threadId).getFrameCount()).isEqualTo(2);

    client.unnumberedEvents.clear();
    client.sendRequestAndWaitForResponse(
        DebugRequest.newBuilder()
            .setSequenceNumber(2)
            .setContinueExecution(
                ContinueExecutionRequest.newBuilder()
                    .setThreadId(threadId)
                    .setStepping(Stepping.OUT)
                    .build())
            .build());
    DebugEvent event = client.waitForEvent(DebugEvent::hasThreadPaused, Duration.ofSeconds(5));

    PausedThread pausedThread = event.getThreadPaused().getThread();
    Location expectedLocation = breakpoint.toBuilder().setLineNumber(5).setColumnNumber(1).build();

    assertThat(pausedThread.getPauseReason()).isEqualTo(PauseReason.STEPPING);
    assertThat(pausedThread.getLocation()).isEqualTo(expectedLocation);
  }

  private void setBreakpoints(Collection<Location> locations) throws Exception {
    setBreakpoints(
        locations
            .stream()
            .map(l -> Breakpoint.newBuilder().setLocation(l).build())
            .collect(Collectors.toList()));
  }

  private void setBreakpoints(Iterable<Breakpoint> breakpoints) throws Exception {
    DebugEvent response =
        client.sendRequestAndWaitForResponse(
            DebugRequest.newBuilder()
                .setSequenceNumber(10)
                .setSetBreakpoints(SetBreakpointsRequest.newBuilder().addAllBreakpoint(breakpoints))
                .build());
    assertThat(response.hasSetBreakpoints()).isTrue();
    assertThat(response.getSequenceNumber()).isEqualTo(10);
  }

  private void sendStartDebuggingRequest() throws Exception {
    client.sendRequestAndWaitForResponse(
        DebugRequest.newBuilder()
            .setSequenceNumber(1)
            .setStartDebugging(StartDebuggingRequest.getDefaultInstance())
            .build());
  }

  private ListFramesResponse listFrames(long threadId) throws Exception {
    DebugEvent event =
        client.sendRequestAndWaitForResponse(
            DebugRequest.newBuilder()
                .setSequenceNumber(1)
                .setListFrames(ListFramesRequest.newBuilder().setThreadId(threadId).build())
                .build());
    assertThat(event.hasListFrames()).isTrue();
    assertThat(event.getSequenceNumber()).isEqualTo(1);
    return event.getListFrames();
  }

  private static ParserInput createInput(String filename, String... lines) {
    return ParserInput.fromString(Joiner.on("\n").join(lines), filename);
  }

  /**
   * Creates and starts a worker thread parsing, resolving, and executing the given Starlark file to
   * populate the specified module, or if none is given, in a fresh module with a default
   * environment.
   */
  private static Thread execInWorkerThread(ParserInput input, @Nullable Module module) {
    Thread javaThread =
        new Thread(
            () -> {
              try (Mutability mu = Mutability.create("test")) {
                StarlarkThread thread =
                    StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);
                Starlark.execFile(
                    input, FileOptions.DEFAULT, module != null ? module : Module.create(), thread);
              } catch (SyntaxError.Exception | EvalException | InterruptedException ex) {
                throw new AssertionError(ex);
              }
            });
    javaThread.start();
    return javaThread;
  }

  /**
   * Asserts that the given frames are equal after clearing the identifier from all {@link Value}s.
   */
  private static void assertFramesEqualIgnoringValueIdentifiers(Frame frame1, Frame frame2) {
    assertThat(clearIds(frame1)).isEqualTo(clearIds(frame2));
  }

  private static Frame clearIds(Frame frame) {
    Frame.Builder builder = frame.toBuilder();
    for (int i = 0; i < frame.getScopeCount(); i++) {
      builder.setScope(i, clearIds(builder.getScope(i)));
    }
    return builder.build();
  }

  private static Scope clearIds(Scope scope) {
    Scope.Builder builder = scope.toBuilder();
    for (int i = 0; i < scope.getBindingCount(); i++) {
      builder.getBindingBuilder(i).clearId();
    }
    return builder.build();
  }

  private static void assertValuesEqualIgnoringId(Value value1, Value value2) {
    assertThat(clearId(value1)).isEqualTo(clearId(value2));
  }

  private static Value clearId(Value value) {
    return value.toBuilder().clearId().build();
  }
}
