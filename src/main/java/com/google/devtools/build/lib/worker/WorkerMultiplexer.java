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

package com.google.devtools.build.lib.worker;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.exec.TreeDeleter;
import com.google.devtools.build.lib.sandbox.SandboxHelpers;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs;
import com.google.devtools.build.lib.shell.Subprocess;
import com.google.devtools.build.lib.shell.SubprocessBuilder;
import com.google.devtools.build.lib.shell.SubprocessFactory;
import com.google.devtools.build.lib.util.StringEncoding;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.worker.WorkerProcessStatus.Status;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import com.google.errorprone.annotations.concurrent.LazyInit;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import javax.annotation.Nullable;

/**
 * An intermediate worker that sends requests and receives responses from the worker processes.
 * There is at most one of these per {@code WorkerKey}, corresponding to one worker process. {@code
 * WorkerMultiplexer} objects run in separate long-lived threads. {@code WorkerProxy} objects call
 * into them to send requests. When a worker process returns a {@code WorkResponse}, {@code
 * WorkerMultiplexer} wakes up the relevant {@code WorkerProxy} to retrieve the response.
 */
public class WorkerMultiplexer {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /**
   * A queue of {@link WorkRequest} instances that need to be sent to the worker. {@link
   * WorkerProxy} instances add to this queue, while the requestSender subthread remove requests and
   * send them to the worker. This prevents dynamic execution interrupts from corrupting the {@code
   * stdin} of the worker process.
   */
  @VisibleForTesting final BlockingQueue<WorkRequest> pendingRequests = new LinkedBlockingQueue<>();
  /**
   * A map of {@code WorkResponse}s received from the worker process. They are stored in this map
   * keyed by the request id until the corresponding {@code WorkerProxy} picks them up.
   */
  private final ConcurrentMap<Integer, WorkResponse> workerProcessResponse =
      new ConcurrentHashMap<>();
  /**
   * A map of semaphores corresponding to {@code WorkRequest}s. After sending the {@code
   * WorkRequest}, {@code WorkerProxy} will wait on a semaphore to be released. {@code
   * WorkerMultiplexer} is responsible for releasing the corresponding semaphore in order to signal
   * {@code WorkerProxy} that the {@code WorkerResponse} has been received.
   */
  private final ConcurrentMap<Integer, Semaphore> responseChecker = new ConcurrentHashMap<>();
  /**
   * The worker process that this WorkerMultiplexer should be talking to. This should only be set
   * once, when creating a new process. If the process dies or its stdio streams get corrupted, the
   * {@code WorkerMultiplexer} gets discarded as well and a new one gets created as needed.
   */
  @VisibleForTesting Subprocess process;
  /** The implementation of the worker protocol (JSON or Proto). */
  private WorkerProtocolImpl workerProtocol;
  /** InputStream from the worker process. */
  @LazyInit private RecordingInputStream recordingStream;

  /** Status of the worker process. */
  private final WorkerProcessStatus status;

  /**
   * The log file of the actual running worker process. It is shared between all WorkerProxy
   * instances for this multiplexer.
   */
  private final Path logFile;

  /** The worker key that this multiplexer is for. */
  private final WorkerKey workerKey;

  /** For testing only, allow a way to fake subprocesses. */
  private SubprocessFactory subprocessFactory;

  /** A separate thread that sends requests. */
  private Thread requestSender;

  /** A separate thread that receives responses. */
  private Thread responseReceiver;

  /**
   * The active Reporter object, non-null if {@code --worker_verbose} is set. This must be cleared
   * at the end of a command execution.
   */
  private EventHandler reporter;

  /**
   * Shutdown hook to make sure we wait for the process to finish on JVM shutdown, to avoid creating
   * zombie processes. Unfortunately, shutdown hooks are not guaranteed to be called, but this is
   * the best we can do. This must be set when a process is created.
   */
  private Thread shutdownHook;

  WorkerMultiplexer(Path logFile, WorkerKey workerKey) {
    this.status = new WorkerProcessStatus();
    this.logFile = logFile;
    this.workerKey = workerKey;
  }

  /** Sets or clears the reporter for outputting verbose info. */
  synchronized void setReporter(@Nullable EventHandler reporter) {
    this.reporter = reporter;
  }

  /** Reports a string to the user if reporting is enabled. */
  private synchronized void report(@Nullable String s) {
    if (this.reporter != null && s != null) {
      this.reporter.handle(Event.info(s));
    }
  }

  public WorkerProcessStatus getStatus() {
    return status;
  }

  /**
   * Creates a worker process corresponding to this {@code WorkerMultiplexer}, if it doesn't already
   * exist. Also starts up the subthreads handling reading and writing requests and responses, and
   * sets up the sandbox root dir with the required worker files.
   */
  public synchronized void createSandboxedProcess(
      Path workDir,
      Set<PathFragment> workerFiles,
      SandboxInputs inputFiles,
      TreeDeleter treeDeleter)
      throws IOException, InterruptedException {
    // TODO: Make blaze clean remove the workdir.
    if (this.process == null) {
      // This should be a once-only operation.
      workDir.createDirectoryAndParents();
      workDir.deleteTreesBelow();
      LinkedHashSet<PathFragment> dirsToCreate = new LinkedHashSet<>();
      Set<PathFragment> inputsToCreate = new HashSet<>();
      SandboxHelpers.populateInputsAndDirsToCreate(
          ImmutableSet.of(),
          inputsToCreate,
          dirsToCreate,
          workerFiles,
          SandboxOutputs.getEmptyInstance());
      SandboxHelpers.cleanExisting(
          workDir.getParentDirectory(),
          inputFiles,
          inputsToCreate,
          dirsToCreate,
          workDir,
          treeDeleter);
      SandboxHelpers.createDirectories(dirsToCreate, workDir, /* strict=*/ false);
      WorkerExecRoot.createInputs(inputsToCreate, inputFiles.limitedCopy(workerFiles), workDir);
      createProcess(workDir);
    }
  }

  /**
   * Creates a worker process corresponding to this {@code WorkerMultiplexer}, if it doesn't already
   * exist. Also starts up the subthreads handling reading and writing requests and responses.
   */
  public synchronized void createProcess(Path workDir) throws IOException {
    if (this.process == null) {
      if (this.status.isKilled()) {
        throw new IOException("Multiplexer destroyed before created process");
      }
      this.shutdownHook =
          new Thread(
              () -> {
                this.shutdownHook = null;
                this.destroyMultiplexer();
              });
      Runtime.getRuntime().addShutdownHook(shutdownHook);
      ImmutableList<String> args = workerKey.getArgs();
      File executable = new File(StringEncoding.internalToPlatform(args.get(0)));
      if (!executable.isAbsolute() && executable.getParent() != null) {
        List<String> newArgs = new ArrayList<>(args);
        newArgs.set(
            0,
            StringEncoding.platformToInternal(
                new File(workDir.getPathFile(), StringEncoding.internalToPlatform(newArgs.get(0)))
                    .getAbsolutePath()));
        args = ImmutableList.copyOf(newArgs);
      }
      SubprocessBuilder processBuilder =
          subprocessFactory != null
              ? new SubprocessBuilder(subprocessFactory)
              : new SubprocessBuilder();
      processBuilder.setArgv(args);
      processBuilder.setWorkingDirectory(workDir.getPathFile());
      processBuilder.setStderr(logFile.getPathFile());
      processBuilder.setEnv(workerKey.getEnv());
      this.process = processBuilder.start();
      status.maybeUpdateStatus(Status.ALIVE);

      recordingStream = new RecordingInputStream(process.getInputStream());
      recordingStream.startRecording(4096);
      if (workerProtocol == null) {
        switch (workerKey.getProtocolFormat()) {
          case JSON:
            workerProtocol = new JsonWorkerProtocol(process.getOutputStream(), recordingStream);
            break;
          case PROTO:
            workerProtocol = new ProtoWorkerProtocol(process.getOutputStream(), recordingStream);
            break;
        }
      }
      String id = workerKey.getMnemonic() + "-" + workerKey.hashCode();
      logger.atInfo().log(
          "Created multiplexer process %s for worker %s", process.getProcessId(), id);
      // TODO(larsrc): Consider moving sender/receiver threads into separate classes.
      this.requestSender =
          new Thread(
              () -> {
                while (process.isAlive() && sendRequest()) {}
              });
      this.requestSender.setName("multiplexer-request-sender-" + id);
      this.requestSender.start();
      this.responseReceiver =
          new Thread(
              () -> {
                while (process.isAlive() && readResponse()) {}
              });
      this.responseReceiver.setName("multiplexer-response-receiver-" + id);
      this.responseReceiver.start();
    } else if (!this.process.isAlive()) {
      throw new IOException("Process is dead");
    }
  }

  /**
   * Returns the path of the log file shared by all multiplex workers using this process. May be
   * null if the process has not started yet.
   */
  public Path getLogFile() {
    return logFile;
  }

  /**
   * Signals this object to destroy itself, including the worker process. The object might not be
   * fully destroyed at the end of this call, but will terminate soon. This is considered a
   * deliberate destruction.
   */
  public synchronized void destroyMultiplexer() {
    if (this.process != null) {
      destroyProcess();
    }
    // The WorkerProcessStatus is only set as killed once all WorkerProxy instances are destroyed.
    status.setKilled();
  }

  /**
   * Destroys the worker subprocess. This might block forever if the subprocess refuses to die. It
   * is safe to call this multiple times.
   */
  private synchronized void destroyProcess() {
    boolean wasInterrupted = false;
    try {
      this.process.destroy();
      while (true) {
        try {
          this.process.waitFor();
          return;
        } catch (InterruptedException ie) {
          wasInterrupted = true;
        }
      }
    } finally {
      if (shutdownHook != null) {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
        shutdownHook = null;
      }
      // Stop the subthreads only when the process is dead, or their loops will go on.
      if (this.requestSender != null) {
        this.requestSender.interrupt();
      }
      if (this.responseReceiver != null) {
        this.responseReceiver.interrupt();
      }
      // Might as well release any waiting workers
      for (Semaphore semaphore : responseChecker.values()) {
        semaphore.release();
      }
      // Read this for detailed explanation: http://www.ibm.com/developerworks/library/j-jtp05236/
      if (wasInterrupted) {
        Thread.currentThread().interrupt(); // preserve interrupted status
      }
    }
  }

  /**
   * Sends the WorkRequest to worker process. This method is called on the thread of a {@code
   * WorkerProxy}, and so is subject to interrupts by dynamic execution.
   */
  public synchronized void putRequest(WorkRequest request) throws IOException {
    if (!process.isAlive()) {
      throw new IOException(
          "Attempting to send request " + request.getRequestId() + " to dead process");
    }
    responseChecker.put(request.getRequestId(), new Semaphore(0));
    pendingRequests.add(request);
  }

  /**
   * Waits on a semaphore for the {@code WorkResponse} returned from worker process. This method is
   * called on the thread of a {@code WorkerProxy}, and so is subject to interrupts by dynamic
   * execution.
   */
  public WorkResponse getResponse(Integer requestId) throws InterruptedException, IOException {
    try {
      if (!process.isAlive()) {
        // If the process has died, all we can do is return what may already have been returned.
        return workerProcessResponse.get(requestId);
      }

      Semaphore waitForResponse = responseChecker.get(requestId);

      if (waitForResponse == null) {
        report("Null response semaphore for " + requestId);
        // If there is no semaphore for this request, it probably failed to send, so we just return
        // what we got, probably nothing.
        return workerProcessResponse.get(requestId);
      }

      // Wait for the multiplexer to get our response and release this semaphore. If the multiplexer
      // process dies, the semaphore gets released with no response available.
      waitForResponse.acquire();

      if (workerProcessResponse.get(requestId) == null && !process.isAlive()) {
        throw new IOException("Worker process for " + workerKey.getMnemonic() + " has died");
      }
      return workerProcessResponse.get(requestId);
    } finally {
      responseChecker.remove(requestId);
      workerProcessResponse.remove(requestId);
    }
  }

  /**
   * Sends a single pending request, if there are any. Blocks until a request is available.
   *
   * <p>This is only called by the {@code requestSender} thread and so cannot be interrupted by
   * dynamic execution cancellation, but only by a call to {@link #destroyProcess()}.
   */
  private boolean sendRequest() {
    WorkRequest request;
    try {
      request = pendingRequests.take();
    } catch (InterruptedException e) {
      return false;
    }
    try {
      workerProtocol.putRequest(request);
    } catch (IOException e) {
      // We can't know how much of the request was sent, so we have to assume the worker's input
      // now contains garbage, and this request is lost.
      // TODO(b/177637516): Signal that this action failed for presumably transient reasons.
      report("Failed to send request " + request.getRequestId());
      Semaphore s = responseChecker.remove(request.getRequestId());
      if (s != null) {
        s.release();
      }
      // TODO(b/177637516): Leave process in a moribound state so pending responses can be returned.
      destroyProcess();
      return false;
    }
    return true;
  }

  /**
   * Reads a {@code WorkResponse} from worker process, puts that {@code WorkResponse} in {@code
   * workerProcessResponse}, and releases the semaphore for the {@code WorkerProxy}.
   *
   * <p>This is only called on the readResponses subthread and so cannot be interrupted by dynamic
   * execution cancellation, but only by a call to {@link #destroyProcess()}.
   *
   * @return True if the worker is still in a consistent state.
   */
  private boolean readResponse() {
    WorkResponse parsedResponse;
    try {
      parsedResponse = workerProtocol.getResponse();
    } catch (IOException e) {
      if (!(e instanceof InterruptedIOException)) {
        report(
            String.format(
                "Error while reading response from multiplexer process for %s: %s",
                workerKey.getMnemonic(), e));
      }
      // We can't know how much of the response was read, so we have to assume the worker's output
      // now contains garbage, and we can't reliably read any further responses.
      destroyProcess();
      return false;
    }

    // A null parsedResponse can only happen if the input stream is closed, in which case we
    // drop everything.
    if (parsedResponse == null) {
      report(
          String.format(
              "Multiplexer process for %s has closed its output stream", workerKey.getMnemonic()));
      destroyProcess();
      return false;
    }

    int requestId = parsedResponse.getRequestId();
    workerProcessResponse.put(requestId, parsedResponse);

    // TODO(b/151767359): When allowing cancellation, just remove responses that have no matching
    // entry in responseChecker.
    Semaphore semaphore = responseChecker.get(requestId);
    if (semaphore != null) {
      // This wakes up the WorkerProxy that should receive this response.
      semaphore.release();
    } else {
      report(String.format("Multiplexer for %s found no semaphore", workerKey.getMnemonic()));
      workerProcessResponse.remove(requestId);
    }
    return true;
  }

  String getRecordingStreamMessage() {
    // Once we read junk, we can't trust the rest of the stream
    synchronized (recordingStream) {
      recordingStream.readRemaining();
      return recordingStream.getRecordedDataAsString();
    }
  }

  /** Returns true if this process has died for other reasons than a call to {@code #destroy()}. */
  boolean diedUnexpectedly() {
    return this.process != null && !this.process.isAlive() && !status.isKilled();
  }

  /** Returns the exit value of multiplexer's process, if it has exited. */
  Optional<Integer> getExitValue() {
    return this.process != null && !this.process.isAlive()
        ? Optional.of(this.process.exitValue())
        : Optional.empty();
  }

  /** For testing only, to verify that maps are cleared after responses are reaped. */
  @VisibleForTesting
  boolean noOutstandingRequests() {
    return responseChecker.isEmpty() && workerProcessResponse.isEmpty();
  }

  @VisibleForTesting
  void setProcessFactory(SubprocessFactory factory) {
    subprocessFactory = factory;
  }

  public long getProcessId() {
    if (process == null) {
      return -1;
    }
    return process.getProcessId();
  }
}
