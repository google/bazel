// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote;

import static java.util.concurrent.TimeUnit.SECONDS;

import build.bazel.remote.execution.v2.ExecuteRequest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.ExecutionGrpc;
import build.bazel.remote.execution.v2.ExecutionGrpc.ExecutionBlockingStub;
import build.bazel.remote.execution.v2.RequestMetadata;
import build.bazel.remote.execution.v2.ServerCapabilities;
import build.bazel.remote.execution.v2.WaitExecutionRequest;
import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.authandtls.CallCredentialsProvider;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.remote.RemoteRetrier.ProgressiveBackoff;
import com.google.devtools.build.lib.remote.Retrier.Backoff;
import com.google.devtools.build.lib.remote.common.OperationObserver;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteExecutionClient;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.longrunning.Operation;
import com.google.longrunning.Operation.ResultCase;
import com.google.rpc.Status;
import io.grpc.Channel;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.reactivex.rxjava3.functions.Function;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * A remote work executor that uses gRPC for communicating the work, inputs and outputs.
 *
 * <p>It differs from {@link GrpcRemoteExecutor} by setting timeout on each execution calls to
 * ensure we never be stuck due to network issues.
 *
 * @see <a href="https://docs.google.com/document/d/1NgDPsCIwprDdqC1zj0qQrh5KGK2hQTSTux1DAvi4rSc">
 *     Keepalived Remote Execution</a>
 */
@ThreadSafe
public class ExperimentalGrpcRemoteExecutor implements RemoteExecutionClient {

  private final RemoteOptions remoteOptions;
  private final ReferenceCountedChannel channel;
  private final CallCredentialsProvider callCredentialsProvider;
  private final RemoteRetrier retrier;

  private final AtomicBoolean closed = new AtomicBoolean();

  public ExperimentalGrpcRemoteExecutor(
      RemoteOptions remoteOptions,
      ReferenceCountedChannel channel,
      CallCredentialsProvider callCredentialsProvider,
      RemoteRetrier retrier) {
    this.remoteOptions = remoteOptions;
    this.channel = channel;
    this.callCredentialsProvider = callCredentialsProvider;
    this.retrier = retrier;
  }

  private ExecutionBlockingStub executionBlockingStub(RequestMetadata metadata, Channel channel) {
    return ExecutionGrpc.newBlockingStub(channel)
        .withInterceptors(TracingMetadataUtils.attachMetadataInterceptor(metadata))
        .withCallCredentials(callCredentialsProvider.getCallCredentials())
        .withDeadlineAfter(remoteOptions.remoteTimeout.toSeconds(), SECONDS);
  }

  private static class Execution {
    private final ExecuteRequest request;
    private final OperationObserver observer;
    private final RemoteRetrier retrier;
    private final CallCredentialsProvider callCredentialsProvider;
    // Count retry times for Execute() calls and can't be reset.
    private final Backoff executeBackoff;
    // Count retry times for WaitExecution() calls and is reset when we receive any response from
    // the server that is not an error.
    private final ProgressiveBackoff waitExecutionBackoff;
    private final Function<ExecuteRequest, Iterator<Operation>> executeFunction;
    private final Function<WaitExecutionRequest, Iterator<Operation>> waitExecutionFunction;

    // Last response (without error) we received from server.
    private Operation lastOperation;

    Execution(
        ExecuteRequest request,
        OperationObserver observer,
        RemoteRetrier retrier,
        CallCredentialsProvider callCredentialsProvider,
        Function<ExecuteRequest, Iterator<Operation>> executeFunction,
        Function<WaitExecutionRequest, Iterator<Operation>> waitExecutionFunction) {
      this.request = request;
      this.observer = observer;
      this.retrier = retrier;
      this.callCredentialsProvider = callCredentialsProvider;
      this.executeBackoff = this.retrier.newBackoff();
      this.waitExecutionBackoff = new ProgressiveBackoff(this.retrier::newBackoff);
      this.executeFunction = executeFunction;
      this.waitExecutionFunction = waitExecutionFunction;
    }

    ExecuteResponse start() throws IOException, InterruptedException {
      // Execute has two components: the Execute call and (optionally) the WaitExecution call.
      // This is the simple flow without any errors:
      //
      // - A call to Execute returns streamed updates on an Operation object.
      // - We wait until the Operation is finished.
      //
      // Error possibilities:
      // - An Execute call may fail with a retriable error (raise a StatusRuntimeException).
      //   - If the failure occurred before the first Operation is returned and tells us the
      //     execution is accepted, we retry the call.
      //   - Otherwise, we call WaitExecution on the Operation.
      // - A WaitExecution call may fail with a retriable error (raise a StatusRuntimeException).
      //   In that case, we retry the WaitExecution call on the same operation object.
      // - A WaitExecution call may fail with a NOT_FOUND error (raise a StatusRuntimeException).
      //   That means the Operation was lost on the server, and we will retry to Execute.
      // - Any call can return an Operation object with an error status in the result. Such
      //   Operations are completed and failed; however, some of these errors may be retriable.
      //   These errors should trigger a retry of the Execute call, resulting in a new Operation.
      Preconditions.checkState(lastOperation == null);

      ExecuteResponse response = null;
      // Exit the loop as long as we get a response from either Execute() or WaitExecution().
      while (response == null) {
        // We use refreshIfUnauthenticated inside retry block. If use it outside, retrier will stop
        // retrying when received a unauthenticated error, and propagate to refreshIfUnauthenticated
        // which will then call retrier again. It will reset the retry time counter so we could
        // retry more than --remote_retry times which is not expected.
        if (lastOperation == null) {
          response =
              retrier.execute(
                  () -> Utils.refreshIfUnauthenticated(this::execute, callCredentialsProvider),
                  executeBackoff);
        }

        // If no response from Execute(), use WaitExecution() in a "loop" which is implemented
        // inside the retry block.
        //
        // The cases to exit the loop:
        //   1. Received the final response.
        //   2. Received a un-retriable gRPC error.
        //   3. Received NOT_FOUND error where we will retry Execute() (by returning null).
        //   4. Received consecutive retriable gRPC errors (up to max retry times).
        if (response == null) {
          response =
              retrier.execute(
                  () ->
                      Utils.refreshIfUnauthenticated(this::waitExecution, callCredentialsProvider),
                  waitExecutionBackoff);
        }
      }

      return response;
    }

    @Nullable
    ExecuteResponse execute() throws IOException {
      Preconditions.checkState(lastOperation == null);

      try {
        Iterator<Operation> operationStream = executeFunction.apply(request);
        return handleOperationStream(operationStream, /* waitExecution= */ false);
      } catch (Throwable e) {
        // If lastOperation is not null, we know the execution request is accepted by the server. In
        // this case, we will fallback to WaitExecution() loop when the stream is broken.
        if (lastOperation != null) {
          // By returning null, we are going to fallback to WaitExecution() loop.
          return null;
        }
        throw new IOException(e);
      }
    }

    @Nullable
    ExecuteResponse waitExecution() throws IOException {
      Preconditions.checkState(lastOperation != null);

      WaitExecutionRequest request =
          WaitExecutionRequest.newBuilder().setName(lastOperation.getName()).build();
      try {
        Iterator<Operation> operationStream = waitExecutionFunction.apply(request);
        return handleOperationStream(operationStream, /* waitExecution= */ true);
      } catch (StatusRuntimeException e) {
        // Handle the error possibility of a NOT_FOUND stream error in addition to the status
        // embedded in an operation from the stream, since both are possible and should retry
        // Execute().
        //
        // Retry is again contingent upon executeBackoff exhaustion, and if NOT_FOUND was
        // thrown in handleOperationStream as a result of exhaustion already, this does not
        // change its counter or retriability state.
        if (e.getStatus().getCode() == Code.NOT_FOUND && executeBackoff.nextDelayMillis(e) >= 0) {
          lastOperation = null;
          return null;
        }
        throw new IOException(e);
      } catch (Throwable e) {
        lastOperation = null;
        throw new IOException(e);
      }
    }

    /** Process a stream of operations from Execute() or WaitExecution(). */
    @Nullable
    ExecuteResponse handleOperationStream(
        Iterator<Operation> operationStream, boolean waitExecution) throws IOException {
      try {
        while (operationStream.hasNext()) {
          Operation operation = operationStream.next();

          // Either done or should be repeated
          lastOperation = operation.getDone() ? null : operation;

          ExecuteResponse response;
          try {
            response = extractResponseOrThrowIfError(operation);
          } catch (StatusRuntimeException e) {
            // An operation error means Operation has been terminally completed, retry Execute().
            //
            // However, we only retry Execute() if executeBackoff should retry. Also increase the
            // retry
            // counter at the same time (done by nextDelayMillis()).
            if (waitExecution
                && (retrier.isRetriable(e) || e.getStatus().getCode() == Code.NOT_FOUND)
                && executeBackoff.nextDelayMillis(e) >= 0) {
              lastOperation = null;
              return null;
            }
            throw e;
          }

          // We don't want to reset executeBackoff since if there is an error:
          //   1. If happened before we received a first response, we want to ensure the retry
          // counter
          //      is increased and call Execute() again.
          //   2. Otherwise, we will fallback to WaitExecution() loop.
          //
          // This also prevent us from being stuck at a infinite loop:
          //   Execute() -> WaitExecution() -> Execute()
          //
          // However, we do want to reset waitExecutionBackoff so we can "infinitely" wait
          // for the execution to complete as long as they are making progress (by returning a
          // response that is not an error).
          waitExecutionBackoff.reset();

          // Update execution progress to the observer.
          //
          // After called `execute` above, the action is actually waiting for an available gRPC
          // connection to be sent. Once we get a reply from server, we know the connection is up
          // and indicate to the caller the fact by forwarding the `operation`.
          //
          // The accurate execution status of the action relies on the server
          // implementation:
          //   1. Server can reply the accurate status in `operation.metadata.stage`;
          //   2. Server may send a reply without metadata. In this case, we assume the action is
          //      accepted by the server and will be executed ASAP;
          //   3. Server may execute the action silently and send a reply once it is done.
          observer.onNext(operation);

          if (response != null) {
            return response;
          }
        }

        // The operation stream completed successfully but without a result.
        return null;
      } finally {
        close(operationStream);
      }
    }

    static void close(Iterator<Operation> operationStream) {
      // The blocking streaming call closes correctly only when trailers and a Status are received
      // from the server so that onClose() is called on this call's CallListener. Under normal
      // circumstances (no cancel/errors), these are guaranteed to be sent by the server only if
      // operationStream.hasNext() has been called after all replies from the stream have been
      // consumed.
      try {
        while (operationStream.hasNext()) {
          operationStream.next();
        }
      } catch (StatusRuntimeException e) {
        // Cleanup: ignore exceptions, because the meaningful errors have already been propagated.
      }
    }

    static void throwIfError(Status status, @Nullable ExecuteResponse resp) {
      if (status.getCode() == Code.OK.value()) {
        return;
      }
      throw new ExecutionStatusException(status, resp);
    }

    @Nullable
    static ExecuteResponse extractResponseOrThrowIfError(Operation operation) throws IOException {
      if (operation.getResultCase() == Operation.ResultCase.ERROR) {
        throwIfError(operation.getError(), null);
      }

      if (operation.getDone()) {
        if (operation.getResultCase() == ResultCase.RESULT_NOT_SET) {
          throw new ExecutionStatusException(
              Status.newBuilder()
                  .setCode(com.google.rpc.Code.DATA_LOSS_VALUE)
                  .setMessage("Unexpected result of remote execution: no result")
                  .build(),
              null);
        }
        Preconditions.checkState(operation.getResultCase() != Operation.ResultCase.RESULT_NOT_SET);
        ExecuteResponse response = operation.getResponse().unpack(ExecuteResponse.class);
        if (response.hasStatus()) {
          throwIfError(response.getStatus(), response);
        }
        if (!response.hasResult()) {
          throw new ExecutionStatusException(
              Status.newBuilder()
                  .setCode(com.google.rpc.Code.DATA_LOSS_VALUE)
                  .setMessage("Unexpected result of remote execution: no result")
                  .build(),
              response);
        }
        return response;
      }

      return null;
    }
  }

  @Override
  public ServerCapabilities getServerCapabilities() throws IOException {
    return channel.getServerCapabilities();
  }

  @Override
  public ExecuteResponse executeRemotely(
      RemoteActionExecutionContext context, ExecuteRequest request, OperationObserver observer)
      throws IOException, InterruptedException {
    Execution execution =
        new Execution(
            request,
            observer,
            retrier,
            callCredentialsProvider,
            (req) ->
                channel.withChannelBlocking(
                    channel ->
                        this.executionBlockingStub(context.getRequestMetadata(), channel)
                            .execute(req)),
            (req) ->
                channel.withChannelBlocking(
                    channel ->
                        this.executionBlockingStub(context.getRequestMetadata(), channel)
                            .waitExecution(req)));
    return execution.start();
  }

  @Override
  public void close() {
    if (closed.getAndSet(true)) {
      return;
    }
    channel.release();
  }
}
