// Copyright 2020 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.worker;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.worker.WorkRequestHandler.RequestInfo;
import com.google.devtools.build.lib.worker.WorkRequestHandler.WorkRequestCallback;
import com.google.devtools.build.lib.worker.WorkRequestHandler.WorkRequestHandlerBuilder;
import com.google.devtools.build.lib.worker.WorkRequestHandler.WorkerMessageProcessor;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.MockitoAnnotations;

/** Tests for the WorkRequestHandler */
@RunWith(JUnit4.class)
public class WorkRequestHandlerTest {

  private final WorkRequestHandler.WorkerIO testWorkerIO = createTestWorkerIO();

  @Before
  public void init() {
    MockitoAnnotations.initMocks(this);
  }

  @After
  public void after() throws Exception {
    testWorkerIO.close();
  }

  @Test
  public void testNormalWorkRequest() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    WorkRequestHandler handler =
        new WorkRequestHandler(
            (args, err) -> 1,
            new PrintStream(new ByteArrayOutputStream()),
            new ProtoWorkerMessageProcessor(new ByteArrayInputStream(new byte[0]), out));

    List<String> args = Arrays.asList("--sources", "A.java");
    WorkRequest request = WorkRequest.newBuilder().addAllArguments(args).build();
    handler.respondToRequest(testWorkerIO, request, new RequestInfo(null));

    WorkResponse response =
        WorkResponse.parseDelimitedFrom(new ByteArrayInputStream(out.toByteArray()));
    assertThat(response.getRequestId()).isEqualTo(0);
    assertThat(response.getExitCode()).isEqualTo(1);
    assertThat(response.getOutput()).isEmpty();
  }

  @Test
  public void testMultiplexWorkRequest() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    WorkRequestHandler handler =
        new WorkRequestHandler(
            (args, err) -> 0,
            new PrintStream(new ByteArrayOutputStream()),
            new ProtoWorkerMessageProcessor(new ByteArrayInputStream(new byte[0]), out));

    List<String> args = Arrays.asList("--sources", "A.java");
    WorkRequest request = WorkRequest.newBuilder().addAllArguments(args).setRequestId(42).build();
    handler.respondToRequest(testWorkerIO, request, new RequestInfo(null));

    WorkResponse response =
        WorkResponse.parseDelimitedFrom(new ByteArrayInputStream(out.toByteArray()));
    assertThat(response.getRequestId()).isEqualTo(42);
    assertThat(response.getExitCode()).isEqualTo(0);
    assertThat(response.getOutput()).isEmpty();
  }

  @Test
  public void testMultiplexWorkRequest_stopsThreadsOnShutdown()
      throws IOException, InterruptedException {
    PipedOutputStream src = new PipedOutputStream();
    PipedInputStream dest = new PipedInputStream();

    // Work request threads release this when they have started.
    Semaphore started = new Semaphore(0);
    // Work request threads wait forever on this, so we can see how they react to closed stdin.
    Semaphore eternity = new Semaphore(0);
    // Released when the work request handler thread has noticed the closed stdin and interrupted
    // the work request threads.
    Semaphore stopped = new Semaphore(0);
    List<Thread> workerThreads = new ArrayList<>();
    StoppableWorkerMessageProcessor messageProcessor =
        new StoppableWorkerMessageProcessor(
            new ProtoWorkerMessageProcessor(
                new PipedInputStream(src), new PipedOutputStream(dest)));
    WorkRequestHandler handler =
        new WorkRequestHandler(
            (args, err) -> {
              // Each call to this runs in its own thread.
              synchronized (workerThreads) {
                workerThreads.add(Thread.currentThread());
              }
              started.release();
              try {
                eternity.acquire(); // This blocks until the thread is interrupted at shutdown.
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return 0;
            },
            new PrintStream(new ByteArrayOutputStream()),
            messageProcessor);

    List<String> args = Arrays.asList("--sources", "A.java");
    Thread t =
        new Thread(
            () -> {
              try {
                handler.processRequests();
                stopped.release();
              } catch (IOException e) {
                throw new AssertionError("Unhandled exception", e);
              }
            });
    t.start();
    WorkRequest request1 = WorkRequest.newBuilder().addAllArguments(args).setRequestId(42).build();
    request1.writeDelimitedTo(src);
    WorkRequest request2 = WorkRequest.newBuilder().addAllArguments(args).setRequestId(43).build();
    request2.writeDelimitedTo(src);
    src.flush();

    started.acquire(2);
    assertThat(workerThreads).hasSize(2);
    // Now both request threads are started, closing the input to the "worker" should shut it down.
    src.close();
    stopped.acquire();
    while (workerThreads.get(0).isAlive() || workerThreads.get(1).isAlive()) {
      Thread.sleep(1);
    }
    assertThat(workerThreads.get(0).isAlive()).isFalse();
    assertThat(workerThreads.get(1).isAlive()).isFalse();
  }

  @Test
  public void testMultiplexWorkRequest_stopsWorkerOnException()
      throws IOException, InterruptedException {
    PipedOutputStream src = new PipedOutputStream();
    PipedInputStream dest = new PipedInputStream();

    // Work request threads release this when they have started.
    Semaphore started = new Semaphore(0);
    // One work request threads waits forever on this, so the second one can throw an exception
    Semaphore eternity = new Semaphore(0);
    // Released when the work request handler thread has been stopped after a worker thread died.
    Semaphore stopped = new Semaphore(0);
    List<Thread> workerThreads = new ArrayList<>();
    StoppableWorkerMessageProcessor messageProcessor =
        new StoppableWorkerMessageProcessor(
            new ProtoWorkerMessageProcessor(
                new PipedInputStream(src), new PipedOutputStream(dest)));
    WorkRequestHandler handler =
        new WorkRequestHandler(
            (args, err) -> {
              // Each call to this runs in its own thread.
              try {
                synchronized (workerThreads) {
                  workerThreads.add(Thread.currentThread());
                }
                started.release();
                if (workerThreads.size() < 2) {
                  eternity.acquire(); // This blocks forever.
                } else {
                  // This is triggered by the second WorkRequest. This causes the PipedInputStream
                  // under the hood to throw an InterruptedIOException. This process helps us
                  // simulate the situation when the infinite loop in the WorkRequestHandler catches
                  // an IOException while calling messageProcess.readWorkRequest(). This exception
                  // will then trigger the path we're testing to stop the worker.
                  messageProcessor.interruptReader();
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return 0;
            },
            new PrintStream(new ByteArrayOutputStream()),
            messageProcessor);

    List<String> args = Arrays.asList("--sources", "A.java");
    Thread t =
        new Thread(
            () -> {
              try {
                handler.processRequests();
                stopped.release();
              } catch (IOException e) {
                throw new AssertionError("Unhandled exception", e);
              }
            });
    t.start();
    WorkRequest request1 = WorkRequest.newBuilder().addAllArguments(args).setRequestId(42).build();
    request1.writeDelimitedTo(src);
    WorkRequest request2 = WorkRequest.newBuilder().addAllArguments(args).setRequestId(43).build();
    request2.writeDelimitedTo(src);
    src.flush();

    started.acquire(2);
    assertThat(workerThreads).hasSize(2);
    stopped.acquire();
    while (workerThreads.get(0).isAlive() || workerThreads.get(1).isAlive()) {
      Thread.sleep(1);
    }
    assertThat(workerThreads.get(0).isAlive()).isFalse();
    assertThat(workerThreads.get(1).isAlive()).isFalse();
  }

  @Test
  public void testOutput() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    WorkRequestHandler handler =
        new WorkRequestHandler(
            (args, err) -> {
              err.println("Failed!");
              return 1;
            },
            new PrintStream(new ByteArrayOutputStream()),
            new ProtoWorkerMessageProcessor(new ByteArrayInputStream(new byte[0]), out));

    List<String> args = Arrays.asList("--sources", "A.java");
    WorkRequest request = WorkRequest.newBuilder().addAllArguments(args).build();
    handler.respondToRequest(testWorkerIO, request, new RequestInfo(null));

    WorkResponse response =
        WorkResponse.parseDelimitedFrom(new ByteArrayInputStream(out.toByteArray()));
    assertThat(response.getRequestId()).isEqualTo(0);
    assertThat(response.getExitCode()).isEqualTo(1);
    assertThat(response.getOutput()).contains("Failed!");
  }

  @Test
  public void testException() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    WorkRequestHandler handler =
        new WorkRequestHandler(
            (args, err) -> {
              throw new RuntimeException("Exploded!");
            },
            new PrintStream(new ByteArrayOutputStream()),
            new ProtoWorkerMessageProcessor(new ByteArrayInputStream(new byte[0]), out));

    List<String> args = Arrays.asList("--sources", "A.java");
    WorkRequest request = WorkRequest.newBuilder().addAllArguments(args).build();
    handler.respondToRequest(testWorkerIO, request, new RequestInfo(null));

    WorkResponse response =
        WorkResponse.parseDelimitedFrom(new ByteArrayInputStream(out.toByteArray()));
    assertThat(response.getRequestId()).isEqualTo(0);
    assertThat(response.getExitCode()).isEqualTo(1);
    assertThat(response.getOutput()).startsWith("java.lang.RuntimeException: Exploded!");
  }

  @Test
  public void testCancelRequest_exactlyOneResponseSent() throws IOException, InterruptedException {
    boolean[] handlerCalled = new boolean[] {false};
    boolean[] cancelCalled = new boolean[] {false};
    PipedOutputStream src = new PipedOutputStream();
    PipedInputStream dest = new PipedInputStream();
    Semaphore done = new Semaphore(0);
    Semaphore finish = new Semaphore(0);
    List<String> failures = new ArrayList<>();

    StoppableWorkerMessageProcessor messageProcessor =
        new StoppableWorkerMessageProcessor(
            new ProtoWorkerMessageProcessor(
                new PipedInputStream(src), new PipedOutputStream(dest)));
    WorkRequestHandler handler =
        new WorkRequestHandlerBuilder(
                (args, err) -> {
                  handlerCalled[0] = true;
                  err.println("Such work! Much progress! Wow!");
                  return 1;
                },
                new PrintStream(new ByteArrayOutputStream()),
                messageProcessor)
            .setCancelCallback(
                (i, t) -> {
                  cancelCalled[0] = true;
                })
            .build();

    runRequestHandlerThread(done, handler, finish, failures);
    WorkRequest.newBuilder().setRequestId(42).build().writeDelimitedTo(src);
    WorkRequest.newBuilder().setRequestId(42).setCancel(true).build().writeDelimitedTo(src);
    WorkResponse response = WorkResponse.parseDelimitedFrom(dest);
    messageProcessor.stop();
    done.acquire();

    assertThat(handlerCalled[0] || cancelCalled[0]).isTrue();
    assertThat(response.getRequestId()).isEqualTo(42);
    if (response.getWasCancelled()) {
      assertThat(response.getOutput()).isEmpty();
      assertThat(response.getExitCode()).isEqualTo(0);
    } else {
      assertThat(response.getOutput()).isEqualTo("Such work! Much progress! Wow!\n");
      assertThat(response.getExitCode()).isEqualTo(1);
    }

    // Checks that nothing more was sent.
    assertThat(dest.available()).isEqualTo(0);
    finish.release();

    // Checks that there weren't other unexpected failures.
    assertThat(failures).isEmpty();
  }

  @Test
  public void testCancelRequest_sendsResponseWhenDone() throws IOException, InterruptedException {
    Semaphore waitForCancel = new Semaphore(0);
    Semaphore handlerCalled = new Semaphore(0);
    Semaphore cancelCalled = new Semaphore(0);
    PipedOutputStream src = new PipedOutputStream();
    PipedInputStream dest = new PipedInputStream();
    Semaphore done = new Semaphore(0);
    Semaphore requestDone = new Semaphore(0);
    Semaphore finish = new Semaphore(0);
    List<String> failures = new ArrayList<>();

    StoppableWorkerMessageProcessor messageProcessor =
        new StoppableWorkerMessageProcessor(
            new ProtoWorkerMessageProcessor(
                new PipedInputStream(src), new PipedOutputStream(dest)));
    // We force the regular handling to not finish until after we have read the cancel response,
    // to avoid flakiness.
    WorkRequestHandler handler =
        new WorkRequestHandlerBuilder(
                (args, err) -> {
                  // This handler waits until the main thread has sent a cancel request.
                  handlerCalled.release(2);
                  try {
                    waitForCancel.acquire();
                  } catch (InterruptedException e) {
                    failures.add("Unexpected interrupt waiting for cancel request");
                    e.printStackTrace();
                  }
                  requestDone.release();
                  return 0;
                },
                new PrintStream(new ByteArrayOutputStream()),
                messageProcessor)
            .setCancelCallback(
                (i, t) -> {
                  cancelCalled.release();
                })
            .build();

    runRequestHandlerThread(done, handler, finish, failures);
    WorkRequest.newBuilder().setRequestId(42).build().writeDelimitedTo(src);
    // Make sure the handler is called before sending the cancel request, or we might process
    // the cancellation entirely before that.
    handlerCalled.acquire();
    WorkRequest.newBuilder().setRequestId(42).setCancel(true).build().writeDelimitedTo(src);
    cancelCalled.acquire();
    waitForCancel.release();
    // Give the other request a chance to process, so we can check that no other response is sent
    requestDone.acquire();
    messageProcessor.stop();
    done.acquire();

    WorkResponse response = WorkResponse.parseDelimitedFrom(dest);
    assertThat(handlerCalled.availablePermits()).isEqualTo(1); // Released 2, one was acquired
    assertThat(cancelCalled.availablePermits()).isEqualTo(0);
    assertThat(response.getRequestId()).isEqualTo(42);
    assertThat(response.getOutput()).isEmpty();
    assertThat(response.getWasCancelled()).isTrue();

    // Checks that nothing more was sent.
    assertThat(dest.available()).isEqualTo(0);
    src.close();
    finish.release();

    // Checks that there weren't other unexpected failures.
    assertThat(failures).isEmpty();
  }

  @Test
  public void testCancelRequest_noDoubleCancelResponse() throws IOException, InterruptedException {
    Semaphore waitForCancel = new Semaphore(0);
    Semaphore cancelCalled = new Semaphore(0);
    PipedOutputStream src = new PipedOutputStream();
    PipedInputStream dest = new PipedInputStream();
    Semaphore done = new Semaphore(0);
    Semaphore requestsDone = new Semaphore(0);
    Semaphore finish = new Semaphore(0);
    List<String> failures = new ArrayList<>();

    // We force the regular handling to not finish until after we have read the cancel response,
    // to avoid flakiness.
    StoppableWorkerMessageProcessor messageProcessor =
        new StoppableWorkerMessageProcessor(
            new ProtoWorkerMessageProcessor(
                new PipedInputStream(src), new PipedOutputStream(dest)));
    WorkRequestHandler handler =
        new WorkRequestHandlerBuilder(
                (args, err) -> {
                  try {
                    waitForCancel.acquire();
                  } catch (InterruptedException e) {
                    failures.add("Unexpected interrupt waiting for cancel request");
                    e.printStackTrace();
                  }
                  requestsDone.release();
                  return 0;
                },
                new PrintStream(new ByteArrayOutputStream()),
                messageProcessor)
            .setCancelCallback(
                (i, t) -> {
                  cancelCalled.release();
                })
            .build();

    runRequestHandlerThread(done, handler, finish, failures);
    WorkRequest.newBuilder().setRequestId(42).build().writeDelimitedTo(src);
    WorkRequest.newBuilder().setRequestId(42).setCancel(true).build().writeDelimitedTo(src);
    WorkRequest.newBuilder().setRequestId(42).setCancel(true).build().writeDelimitedTo(src);
    cancelCalled.acquire();
    waitForCancel.release();
    requestsDone.acquire();
    messageProcessor.stop();
    done.acquire();

    WorkResponse response = WorkResponse.parseDelimitedFrom(dest);
    assertThat(cancelCalled.availablePermits()).isLessThan(2);
    assertThat(response.getRequestId()).isEqualTo(42);
    assertThat(response.getOutput()).isEmpty();
    assertThat(response.getWasCancelled()).isTrue();

    // Checks that nothing more was sent.
    assertThat(dest.available()).isEqualTo(0);
    src.close();
    finish.release();

    // Checks that there weren't other unexpected failures.
    assertThat(failures).isEmpty();
  }

  @Test
  public void testCancelRequest_sendsNoResponseWhenAlreadySent()
      throws IOException, InterruptedException {
    Semaphore handlerCalled = new Semaphore(0);
    PipedOutputStream src = new PipedOutputStream();
    PipedInputStream dest = new PipedInputStream();
    Semaphore done = new Semaphore(0);
    Semaphore finish = new Semaphore(0);
    List<String> failures = new ArrayList<>();

    // We force the cancel request to not happen until after we have read the normal response,
    // to avoid flakiness.
    StoppableWorkerMessageProcessor messageProcessor =
        new StoppableWorkerMessageProcessor(
            new ProtoWorkerMessageProcessor(
                new PipedInputStream(src), new PipedOutputStream(dest)));
    WorkRequestHandler handler =
        new WorkRequestHandlerBuilder(
                (args, err) -> {
                  handlerCalled.release();
                  err.println("Such work! Much progress! Wow!");
                  return 2;
                },
                new PrintStream(new ByteArrayOutputStream()),
                messageProcessor)
            .setCancelCallback((i, t) -> {})
            .build();

    runRequestHandlerThread(done, handler, finish, failures);
    WorkRequest.newBuilder().setRequestId(42).build().writeDelimitedTo(src);
    WorkResponse response = WorkResponse.parseDelimitedFrom(dest);
    WorkRequest.newBuilder().setRequestId(42).setCancel(true).build().writeDelimitedTo(src);
    messageProcessor.stop();
    done.acquire();

    assertThat(response).isNotNull();

    assertThat(handlerCalled.availablePermits()).isEqualTo(1);
    assertThat(response.getRequestId()).isEqualTo(42);
    assertThat(response.getWasCancelled()).isFalse();
    assertThat(response.getExitCode()).isEqualTo(2);
    assertThat(response.getOutput()).isEqualTo("Such work! Much progress! Wow!\n");

    // Checks that nothing more was sent.
    assertThat(dest.available()).isEqualTo(0);
    src.close();
    finish.release();

    // Checks that there weren't other unexpected failures.
    assertThat(failures).isEmpty();
  }

  @Test
  public void testWorkRequestHandler_withWorkRequestCallback() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    WorkRequestCallback callback =
        new WorkRequestCallback((request, err) -> request.getArgumentsCount());
    WorkRequestHandler handler =
        new WorkRequestHandlerBuilder(
                callback,
                new PrintStream(new ByteArrayOutputStream()),
                new ProtoWorkerMessageProcessor(new ByteArrayInputStream(new byte[0]), out))
            .build();

    List<String> args = Arrays.asList("--sources", "B.java");
    WorkRequest request = WorkRequest.newBuilder().addAllArguments(args).build();
    handler.respondToRequest(testWorkerIO, request, new RequestInfo(null));

    WorkResponse response =
        WorkResponse.parseDelimitedFrom(new ByteArrayInputStream(out.toByteArray()));
    assertThat(response.getRequestId()).isEqualTo(0);
    assertThat(response.getExitCode()).isEqualTo(2);
    assertThat(response.getOutput()).isEmpty();
  }

  private void runRequestHandlerThread(
      Semaphore done, WorkRequestHandler handler, Semaphore finish, List<String> failures) {
    // This thread just makes sure the WorkRequestHandler does work asynchronously.
    new Thread(
            () -> {
              try {
                handler.processRequests();
                while (!handler.activeRequests.isEmpty()) {
                  Thread.sleep(1);
                }
              } catch (IOException e) {
                failures.add("Unexpected I/O error talking to worker thread");
                e.printStackTrace();
              } catch (InterruptedException e) {
                // Getting interrupted while waiting for requests to finish is OK.
              }
              try {
                done.release();
                finish.acquire();
              } catch (InterruptedException e) {
                // Getting interrupted at the end is OK.
              }
            })
        .start();
  }

  @Test
  public void testWorkerIO_doesWrapSystemStreams() throws Exception {
    // Save the original streams
    InputStream originalInputStream = System.in;
    PrintStream originalOutputStream = System.out;
    PrintStream originalErrorStream = System.err;

    // Swap in the test streams to assert against
    ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(new byte[0]);
    System.setIn(byteArrayInputStream);
    PrintStream outputBuffer = new PrintStream(new ByteArrayOutputStream(), true);
    System.setOut(outputBuffer);
    System.setErr(outputBuffer);

    try (outputBuffer;
        byteArrayInputStream;
        WorkRequestHandler.WorkerIO io = WorkRequestHandler.WorkerIO.capture()) {
      // Assert that the WorkerIO returns the correct wrapped streams and the new System instance
      // has been swapped out with the wrapped one
      assertThat(io.getOriginalInputStream()).isSameInstanceAs(byteArrayInputStream);
      assertThat(System.in).isNotSameInstanceAs(byteArrayInputStream);

      assertThat(io.getOriginalOutputStream()).isSameInstanceAs(outputBuffer);
      assertThat(System.out).isNotSameInstanceAs(outputBuffer);

      assertThat(io.getOriginalErrorStream()).isSameInstanceAs(outputBuffer);
      assertThat(System.err).isNotSameInstanceAs(outputBuffer);
    } finally {
      // Swap back in the original streams
      System.setIn(originalInputStream);
      System.setOut(originalOutputStream);
      System.setErr(originalErrorStream);
    }
  }

  @Test
  public void testWorkerIO_doesCaptureStandardOutAndErrorStreams() throws Exception {
    try (WorkRequestHandler.WorkerIO io = WorkRequestHandler.WorkerIO.capture()) {
      // Assert that nothing has been captured in the new instance
      assertThat(io.readCapturedAsUtf8String()).isEmpty();

      // Assert that the standard out/error stream redirect to our own streams
      System.out.print("This is a standard out message!");
      System.err.print("This is a standard error message!");
      assertThat(io.readCapturedAsUtf8String())
          .isEqualTo("This is a standard out message!This is a standard error message!");

      // Assert that readCapturedAsUtf8String calls reset on the captured stream after a read
      assertThat(io.readCapturedAsUtf8String()).isEmpty();

      System.out.print("out 1");
      System.err.print("err 1");
      System.out.print("out 2");
      System.err.print("err 2");
      assertThat(io.readCapturedAsUtf8String()).isEqualTo("out 1err 1out 2err 2");
      assertThat(io.readCapturedAsUtf8String()).isEmpty();
    }
  }

  private WorkRequestHandler.WorkerIO createTestWorkerIO() {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    return new WorkRequestHandler.WorkerIO(System.in, System.out, System.err, captured, captured);
  }

  /** A wrapper around a WorkerMessageProcessor that can be stopped by calling {@code #stop()}. */
  private static class StoppableWorkerMessageProcessor implements WorkerMessageProcessor {
    private final WorkerMessageProcessor delegate;
    private final AtomicBoolean stop = new AtomicBoolean(false);
    private Thread readerThread;

    public StoppableWorkerMessageProcessor(WorkerMessageProcessor delegate) {
      this.delegate = delegate;
    }

    @Override
    public WorkRequest readWorkRequest() throws IOException {
      readerThread = Thread.currentThread();
      if (stop.get()) {
        return null;
      } else {
        try {
          return delegate.readWorkRequest();
        } catch (InterruptedIOException e) {
          // Being interrupted is only an error if we didn't ask for it.
          if (!stop.get()) {
            throw e;
          } else {
            return null;
          }
        }
      }
    }

    @Override
    public void writeWorkResponse(WorkResponse workResponse) throws IOException {
      delegate.writeWorkResponse(workResponse);
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }

    public void stop() {
      stop.set(true);
      if (readerThread != null) {
        readerThread.interrupt();
      }
    }

    public void interruptReader() {
      readerThread.interrupt();
    }
  }
}
