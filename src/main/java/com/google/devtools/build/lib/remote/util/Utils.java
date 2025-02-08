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
package com.google.devtools.build.lib.remote.util;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Throwables.getStackTraceAsString;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.stream.Collectors.joining;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Platform;
import com.google.common.base.Ascii;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AsyncCallable;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnMetrics;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.Spawns;
import com.google.devtools.build.lib.authandtls.CallCredentialsProvider;
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelperException;
import com.google.devtools.build.lib.remote.ExecutionStatusException;
import com.google.devtools.build.lib.remote.common.BulkTransferException;
import com.google.devtools.build.lib.remote.common.CacheNotFoundException;
import com.google.devtools.build.lib.remote.common.OutputDigestMismatchException;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient.ActionKey;
import com.google.devtools.build.lib.remote.common.RemoteExecutionCapabilitiesException;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.RemoteExecution;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Durations;
import com.google.rpc.BadRequest;
import com.google.rpc.Code;
import com.google.rpc.DebugInfo;
import com.google.rpc.Help;
import com.google.rpc.LocalizedMessage;
import com.google.rpc.PreconditionFailure;
import com.google.rpc.QuotaFailure;
import com.google.rpc.RequestInfo;
import com.google.rpc.ResourceInfo;
import com.google.rpc.RetryInfo;
import com.google.rpc.Status;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import javax.annotation.Nullable;

/** Utility methods for the remote package. * */
public final class Utils {

  private Utils() {}

  /**
   * Returns the result of a {@link ListenableFuture} if successful, or throws any checked {@link
   * Exception} directly if it's an {@link IOException} or else wraps it in an {@link IOException}.
   *
   * <p>Cancel the future on {@link InterruptedException}
   */
  public static <T> T getFromFuture(ListenableFuture<T> f)
      throws IOException, InterruptedException {
    return getFromFuture(f, /* cancelOnInterrupt */ true);
  }

  /**
   * Returns the result of a {@link ListenableFuture} if successful, or throws any checked {@link
   * Exception} directly if it's an {@link IOException} or else wraps it in an {@link IOException}.
   *
   * @param cancelOnInterrupt cancel the future on {@link InterruptedException} if {@code true}.
   */
  public static <T> T getFromFuture(ListenableFuture<T> f, boolean cancelOnInterrupt)
      throws IOException, InterruptedException {
    try {
      return f.get();
    } catch (CancellationException e) {
      throw new InterruptedException();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof InterruptedException interruptedException) {
        throw interruptedException;
      }
      if (cause instanceof IOException ioException) {
        throw ioException;
      }
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IOException(cause);
    } catch (InterruptedException e) {
      if (cancelOnInterrupt) {
        f.cancel(true);
      }
      throw e;
    }
  }

  /**
   * Returns the (exec root relative) path of a spawn output that should be made available via
   * {@link SpawnResult#getInMemoryOutput(ActionInput)}.
   */
  @Nullable
  public static PathFragment getInMemoryOutputPath(Spawn spawn) {
    String outputPath =
        spawn.getExecutionInfo().get(ExecutionRequirements.REMOTE_EXECUTION_INLINE_OUTPUTS);
    if (outputPath != null) {
      return PathFragment.create(outputPath);
    }
    return null;
  }

  /** Constructs a {@link SpawnResult}. */
  public static SpawnResult createSpawnResult(
      DigestUtil digestUtil,
      ActionKey actionKey,
      int exitCode,
      boolean cacheHit,
      String runnerName,
      @Nullable InMemoryOutput inMemoryOutput,
      Timestamp executionStartTimestamp,
      Timestamp executionCompletedTimestamp,
      SpawnMetrics spawnMetrics,
      String mnemonic) {
    SpawnResult.Builder builder =
        new SpawnResult.Builder()
            .setStatus(
                exitCode == 0 ? SpawnResult.Status.SUCCESS : SpawnResult.Status.NON_ZERO_EXIT)
            .setExitCode(exitCode)
            .setRunnerName(cacheHit ? runnerName + " cache hit" : runnerName)
            .setCacheHit(cacheHit)
            .setStartTime(timestampToInstant(executionStartTimestamp))
            .setWallTimeInMs(
                (int)
                    java.time.Duration.between(
                            timestampToInstant(executionStartTimestamp),
                            timestampToInstant(executionCompletedTimestamp))
                        .toMillis())
            .setSpawnMetrics(spawnMetrics)
            .setRemote(true)
            .setDigest(digestUtil.asSpawnLogProto(actionKey));
    if (exitCode != 0) {
      builder.setFailureDetail(
          FailureDetail.newBuilder()
              .setMessage(mnemonic + " returned a non-zero exit code when running remotely")
              .setSpawn(
                  FailureDetails.Spawn.newBuilder()
                      .setCode(FailureDetails.Spawn.Code.NON_ZERO_EXIT))
              .build());
    }
    if (inMemoryOutput != null) {
      builder.setInMemoryOutput(inMemoryOutput.getOutput(), inMemoryOutput.getContents());
    }
    return builder.build();
  }

  public static Instant timestampToInstant(Timestamp timestamp) {
    return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
  }

  private static String statusName(int code) {
    // 'convert_underscores' to 'Convert Underscores'
    String name = Code.forNumber(code).getValueDescriptor().getName();
    return Arrays.stream(name.split("_"))
        .map(word -> Ascii.toUpperCase(word.substring(0, 1)) + Ascii.toLowerCase(word.substring(1)))
        .collect(joining(" "));
  }

  private static String errorDetailsMessage(Iterable<Any> details)
      throws InvalidProtocolBufferException {
    String messages = "";
    for (Any detail : details) {
      messages += "  " + errorDetailMessage(detail) + "\n";
    }
    return messages;
  }

  private static String durationMessage(Duration duration) {
    // this will give us seconds, might want to consider something nicer (graduating ms, s, m, h, d,
    // w?)
    return Durations.toString(duration);
  }

  private static String retryInfoMessage(RetryInfo retryInfo) {
    return "Retry delay recommendation of " + durationMessage(retryInfo.getRetryDelay());
  }

  private static String debugInfoMessage(DebugInfo debugInfo) {
    String message = "";
    if (debugInfo.getStackEntriesCount() > 0) {
      message +=
          "Debug Stack Information:\n  " + String.join("\n  ", debugInfo.getStackEntriesList());
    }
    if (!debugInfo.getDetail().isEmpty()) {
      if (debugInfo.getStackEntriesCount() > 0) {
        message += "\n";
      }
      message += "Debug Details: " + debugInfo.getDetail();
    }
    return message;
  }

  private static String quotaFailureMessage(QuotaFailure quotaFailure) {
    String message = "Quota Failure";
    if (quotaFailure.getViolationsCount() > 0) {
      message += ":";
    }
    for (QuotaFailure.Violation violation : quotaFailure.getViolationsList()) {
      message += "\n    " + violation.getSubject() + ": " + violation.getDescription();
    }
    return message;
  }

  private static String preconditionFailureMessage(PreconditionFailure preconditionFailure) {
    String message = "Precondition Failure";
    if (preconditionFailure.getViolationsCount() > 0) {
      message += ":";
    }
    for (PreconditionFailure.Violation violation : preconditionFailure.getViolationsList()) {
      message +=
          "\n    ("
              + violation.getType()
              + ") "
              + violation.getSubject()
              + ": "
              + violation.getDescription();
    }
    return message;
  }

  private static String badRequestMessage(BadRequest badRequest) {
    String message = "Bad Request";
    if (badRequest.getFieldViolationsCount() > 0) {
      message += ":";
    }
    for (BadRequest.FieldViolation fieldViolation : badRequest.getFieldViolationsList()) {
      message += "\n    " + fieldViolation.getField() + ": " + fieldViolation.getDescription();
    }
    return message;
  }

  private static String requestInfoMessage(RequestInfo requestInfo) {
    return "Request Info: " + requestInfo.getRequestId() + " => " + requestInfo.getServingData();
  }

  private static String resourceInfoMessage(ResourceInfo resourceInfo) {
    String message =
        "Resource Info: "
            + resourceInfo.getResourceType()
            + ": name='"
            + resourceInfo.getResourceName()
            + "', owner='"
            + resourceInfo.getOwner()
            + "'";
    if (!resourceInfo.getDescription().isEmpty()) {
      message += ", description: " + resourceInfo.getDescription();
    }
    return message;
  }

  private static String helpMessage(Help help) {
    String message = "Help";
    if (help.getLinksCount() > 0) {
      message += ":";
    }
    for (Help.Link link : help.getLinksList()) {
      message += "\n    " + link.getDescription() + ": " + link.getUrl();
    }
    return message;
  }

  private static String errorDetailMessage(Any detail) throws InvalidProtocolBufferException {
    if (detail.is(RetryInfo.class)) {
      return retryInfoMessage(detail.unpack(RetryInfo.class));
    }
    if (detail.is(DebugInfo.class)) {
      return debugInfoMessage(detail.unpack(DebugInfo.class));
    }
    if (detail.is(QuotaFailure.class)) {
      return quotaFailureMessage(detail.unpack(QuotaFailure.class));
    }
    if (detail.is(PreconditionFailure.class)) {
      return preconditionFailureMessage(detail.unpack(PreconditionFailure.class));
    }
    if (detail.is(BadRequest.class)) {
      return badRequestMessage(detail.unpack(BadRequest.class));
    }
    if (detail.is(RequestInfo.class)) {
      return requestInfoMessage(detail.unpack(RequestInfo.class));
    }
    if (detail.is(ResourceInfo.class)) {
      return resourceInfoMessage(detail.unpack(ResourceInfo.class));
    }
    if (detail.is(Help.class)) {
      return helpMessage(detail.unpack(Help.class));
    }
    return "Unrecognized error detail: " + detail;
  }

  private static String localizedStatusMessage(Status status)
      throws InvalidProtocolBufferException {
    String languageTag = Locale.getDefault().toLanguageTag();
    for (Any detail : status.getDetailsList()) {
      if (detail.is(LocalizedMessage.class)) {
        LocalizedMessage message = detail.unpack(LocalizedMessage.class);
        if (message.getLocale().equals(languageTag)) {
          return message.getMessage();
        }
      }
    }
    return status.getMessage();
  }

  private static String executionStatusExceptionErrorMessage(ExecutionStatusException e)
      throws InvalidProtocolBufferException {
    Status status = e.getOriginalStatus();
    return statusName(status.getCode())
        + ": "
        + localizedStatusMessage(status)
        + "\n"
        + errorDetailsMessage(status.getDetailsList());
  }

  private static String grpcAwareErrorMessage(IOException e) {
    io.grpc.Status errStatus = io.grpc.Status.fromThrowable(e);
    if (e.getCause() instanceof ExecutionStatusException) {
      // Display error message returned by the remote service.
      try {
        return "Remote Execution Failure:\n"
            + executionStatusExceptionErrorMessage((ExecutionStatusException) e.getCause());
      } catch (InvalidProtocolBufferException protoEx) {
        return "Error occurred attempting to format an error message for "
            + errStatus
            + ": "
            + Throwables.getStackTraceAsString(protoEx);
      }
    }
    if (!errStatus.getCode().equals(io.grpc.Status.UNKNOWN.getCode())) {
      // Display error message returned by the gRPC library, prefixed by the status code.
      StringBuilder sb = new StringBuilder();
      sb.append(errStatus.getCode().name());
      sb.append(": ");
      sb.append(errStatus.getDescription());
      // If the error originated from a credential helper, print additional debugging information.
      for (Throwable t = errStatus.getCause(); t != null; t = t.getCause()) {
        if (t instanceof CredentialHelperException) {
          sb.append(": ");
          sb.append(t.getMessage());
          break;
        }
      }
      return sb.toString();
    }
    return e.getMessage();
  }

  public static String grpcAwareErrorMessage(Throwable error, boolean verboseFailures) {
    String errorMessage;
    if (error instanceof IOException ioException) {
      errorMessage = grpcAwareErrorMessage(ioException);
    } else {
      errorMessage = error.getMessage();
    }

    if (isNullOrEmpty(errorMessage)) {
      errorMessage = error.getClass().getSimpleName();
    }

    if (verboseFailures) {
      // On --verbose_failures print the whole stack trace
      errorMessage += "\n" + getStackTraceAsString(error);
    }

    return errorMessage;
  }

  @SuppressWarnings("ProtoParseWithRegistry")
  public static ListenableFuture<ActionResult> downloadAsActionResult(
      ActionKey actionDigest,
      BiFunction<Digest, OutputStream, ListenableFuture<Void>> downloadFunction) {
    ByteArrayOutputStream data = new ByteArrayOutputStream(/* size= */ 1024);
    ListenableFuture<Void> download = downloadFunction.apply(actionDigest.getDigest(), data);
    return FluentFuture.from(download)
        .transformAsync(
            (v) -> {
              try {
                return Futures.immediateFuture(ActionResult.parseFrom(data.toByteArray()));
              } catch (InvalidProtocolBufferException e) {
                return immediateFailedFuture(e);
              }
            },
            directExecutor())
        .catching(CacheNotFoundException.class, (e) -> null, directExecutor());
  }

  public static void verifyBlobContents(Digest expected, Digest actual) throws IOException {
    if (!expected.equals(actual)) {
      throw new OutputDigestMismatchException(expected, actual);
    }
  }

  public static Action buildAction(
      Digest command,
      Digest inputRoot,
      @Nullable Platform platform,
      java.time.Duration timeout,
      boolean cacheable,
      @Nullable ByteString salt) {
    Action.Builder action = Action.newBuilder();
    action.setCommandDigest(command);
    action.setInputRootDigest(inputRoot);
    if (!timeout.isZero()) {
      action.setTimeout(Duration.newBuilder().setSeconds(timeout.toSeconds()));
    }
    if (!cacheable) {
      action.setDoNotCache(true);
    }
    if (platform != null) {
      action.setPlatform(platform);
    }
    if (salt != null) {
      action.setSalt(salt);
    }
    return action.build();
  }

  /** An in-memory output file. */
  public static final class InMemoryOutput {
    private final ActionInput output;
    private final ByteString contents;

    public InMemoryOutput(ActionInput output, ByteString contents) {
      this.output = output;
      this.contents = contents;
    }

    public ActionInput getOutput() {
      return output;
    }

    public ByteString getContents() {
      return contents;
    }
  }

  /**
   * Call an asynchronous code block. If the block throws unauthenticated error, refresh the
   * credentials using {@link CallCredentialsProvider} and call it again.
   *
   * <p>If any other exception thrown by the code block, it will be caught and wrapped in the
   * returned {@link ListenableFuture}.
   */
  public static <V> ListenableFuture<V> refreshIfUnauthenticatedAsync(
      AsyncCallable<V> call, CallCredentialsProvider callCredentialsProvider) {
    checkNotNull(call);
    checkNotNull(callCredentialsProvider);

    try {
      return Futures.catchingAsync(
          call.call(),
          Throwable.class,
          (e) -> refreshIfUnauthenticatedAsyncOnException(e, call, callCredentialsProvider),
          directExecutor());
    } catch (Throwable t) {
      return refreshIfUnauthenticatedAsyncOnException(t, call, callCredentialsProvider);
    }
  }

  private static <V> ListenableFuture<V> refreshIfUnauthenticatedAsyncOnException(
      Throwable t, AsyncCallable<V> call, CallCredentialsProvider callCredentialsProvider) {
    io.grpc.Status status = io.grpc.Status.fromThrowable(t);
    if (status != null
        && (status.getCode() == io.grpc.Status.Code.UNAUTHENTICATED
            || status.getCode() == io.grpc.Status.Code.PERMISSION_DENIED)) {
      try {
        callCredentialsProvider.refresh();
        return call.call();
      } catch (Throwable tt) {
        t.addSuppressed(tt);
      }
    }

    return immediateFailedFuture(t);
  }

  /** Same as {@link #refreshIfUnauthenticatedAsync} but calling a synchronous code block. */
  public static <V> V refreshIfUnauthenticated(
      Callable<V> call, CallCredentialsProvider callCredentialsProvider)
      throws IOException, InterruptedException {
    checkNotNull(call);
    checkNotNull(callCredentialsProvider);

    try {
      return call.call();
    } catch (Exception e) {
      io.grpc.Status status = io.grpc.Status.fromThrowable(e);
      if (status != null
          && (status.getCode() == io.grpc.Status.Code.UNAUTHENTICATED
              || status.getCode() == io.grpc.Status.Code.PERMISSION_DENIED)) {
        try {
          callCredentialsProvider.refresh();
          return call.call();
        } catch (Exception ex) {
          e.addSuppressed(ex);
        }
      }

      Throwables.throwIfInstanceOf(e, IOException.class);
      Throwables.throwIfInstanceOf(e, InterruptedException.class);
      Throwables.throwIfUnchecked(e);
      throw new AssertionError(e);
    }
  }

  private static final ImmutableList<String> UNITS = ImmutableList.of("KiB", "MiB", "GiB", "TiB");
  // Format as single digit decimal number.
  private static final DecimalFormat BYTE_COUNT_FORMAT =
      new DecimalFormat("0.0", new DecimalFormatSymbols(Locale.US));

  /**
   * Converts the number of bytes to a human readable string, e.g. 1024 -> 1 KiB.
   *
   * <p>Negative numbers are not allowed.
   */
  public static String bytesCountToDisplayString(long bytes) {
    Preconditions.checkArgument(bytes >= 0);

    if (bytes < 1024) {
      return bytes + " B";
    }

    int unitIndex = 0;
    long value = bytes;
    while ((unitIndex + 1) < UNITS.size() && value >= (1 << 20)) {
      value >>= 10;
      unitIndex++;
    }

    return String.format("%s %s", BYTE_COUNT_FORMAT.format(value / 1024.0), UNITS.get(unitIndex));
  }

  public static boolean shouldUploadLocalResultsToRemoteCache(
      RemoteOptions remoteOptions, Map<String, String> executionInfo) {
    return remoteOptions.remoteUploadLocalResults
        && Spawns.mayBeCachedRemotely(executionInfo)
        && !executionInfo.containsKey(ExecutionRequirements.NO_REMOTE_CACHE_UPLOAD);
  }

  public static void waitForBulkTransfer(
      Iterable<? extends ListenableFuture<?>> transfers, boolean cancelRemainingOnInterrupt)
      throws BulkTransferException, InterruptedException {
    BulkTransferException bulkTransferException = null;
    InterruptedException interruptedException = null;
    boolean interrupted = Thread.currentThread().isInterrupted();
    for (ListenableFuture<?> transfer : transfers) {
      try {
        if (interruptedException == null) {
          // Wait for all transfers to finish.
          getFromFuture(transfer, cancelRemainingOnInterrupt);
        } else {
          transfer.cancel(true);
        }
      } catch (IOException e) {
        if (bulkTransferException == null) {
          bulkTransferException = new BulkTransferException();
        }
        bulkTransferException.add(e);
      } catch (InterruptedException e) {
        interrupted = Thread.interrupted() || interrupted;
        interruptedException = e;
        if (!cancelRemainingOnInterrupt) {
          // leave the rest of the transfers alone
          break;
        }
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    if (interruptedException != null) {
      if (bulkTransferException != null) {
        interruptedException.addSuppressed(bulkTransferException);
      }
      throw interruptedException;
    }
    if (bulkTransferException != null) {
      throw bulkTransferException;
    }
  }

  public static ListenableFuture<Void> mergeBulkTransfer(
      Iterable<ListenableFuture<Void>> transfers) {
    return Futures.whenAllComplete(transfers)
        .callAsync(
            () -> {
              BulkTransferException bulkTransferException = null;

              for (var transfer : transfers) {
                IOException error = null;
                try {
                  transfer.get();
                } catch (CancellationException e) {
                  return immediateFailedFuture(new InterruptedException());
                } catch (InterruptedException e) {
                  return immediateFailedFuture(e);
                } catch (ExecutionException e) {
                  var cause = e.getCause();
                  if (cause instanceof InterruptedException) {
                    return immediateFailedFuture(cause);
                  } else if (cause instanceof IOException ioException) {
                    error = ioException;
                  } else {
                    error = new IOException(cause);
                  }
                }

                if (error == null) {
                  continue;
                }

                if (bulkTransferException == null) {
                  bulkTransferException = new BulkTransferException();
                }
                bulkTransferException.add(error);
              }

              if (bulkTransferException != null) {
                return immediateFailedFuture(bulkTransferException);
              }

              return immediateVoidFuture();
            },
            directExecutor());
  }

  public static ExecException createExecExceptionForCredentialHelperException(
      CredentialHelperException e) {
    return new EnvironmentalExecException(
        e,
        FailureDetail.newBuilder()
            .setRemoteOptions(
                FailureDetails.RemoteOptions.newBuilder()
                    .setCode(FailureDetails.RemoteOptions.Code.CREDENTIALS_READ_FAILURE)
                    .build())
            .setMessage("Exec failed due to CredentialHelperException")
            .build());
  }

  public static ExecException createExecExceptionFromRemoteExecutionCapabilitiesException(
      RemoteExecutionCapabilitiesException e) {
    return new EnvironmentalExecException(
        e.getCause(),
        FailureDetail.newBuilder()
            .setRemoteExecution(
                RemoteExecution.newBuilder()
                    .setCode(RemoteExecution.Code.CAPABILITIES_QUERY_FAILURE)
                    .build())
            .setMessage("Failed to query remote execution capabilities")
            .build());
  }
}
