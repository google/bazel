// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.remote.worker;

import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Command.EnvironmentVariable;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.ExecuteRequest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.ExecutionGrpc.ExecutionImplBase;
import build.bazel.remote.execution.v2.Platform;
import build.bazel.remote.execution.v2.Platform.Property;
import build.bazel.remote.execution.v2.RequestMetadata;
import build.bazel.remote.execution.v2.WaitExecutionRequest;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.exec.BinTools;
import com.google.devtools.build.lib.exec.local.LocalEnvProvider;
import com.google.devtools.build.lib.remote.ExecutionStatusException;
import com.google.devtools.build.lib.remote.UploadManifest;
import com.google.devtools.build.lib.remote.common.CacheNotFoundException;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient.ActionKey;
import com.google.devtools.build.lib.remote.common.RemotePathResolver;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.shell.AbnormalTerminationException;
import com.google.devtools.build.lib.shell.CommandException;
import com.google.devtools.build.lib.shell.CommandResult;
import com.google.devtools.build.lib.shell.FutureCommandResult;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.runfiles.Runfiles;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.util.Durations;
import com.google.rpc.Code;
import com.google.rpc.Status;
import io.grpc.StatusException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/** A basic implementation of an {@link ExecutionImplBase} service. */
final class ExecutionServer extends ExecutionImplBase {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  // The name of the container image entry in the Platform proto
  // (see third_party/googleapis/devtools/remoteexecution/*/remote_execution.proto and
  // remote_default_exec_properties in
  // src/main/java/com/google/devtools/build/lib/remote/RemoteOptions.java)
  private static final String CONTAINER_IMAGE_ENTRY_NAME = "container-image";
  private static final String DOCKER_IMAGE_PREFIX = "docker://";

  // How long to wait for the uid command.
  private static final Duration uidTimeout = Duration.ofMillis(30);

  private static final int LOCAL_EXEC_ERROR = -1;

  private final Path workPath;
  private final Path sandboxPath;
  private final RemoteWorkerOptions workerOptions;
  private final OnDiskBlobStoreCache cache;
  private final ConcurrentHashMap<String, ListenableFuture<ActionResult>> operationsCache;
  private final ListeningExecutorService executorService;
  private final DigestUtil digestUtil;
  private final LocalEnvProvider localEnvProvider;
  private final BinTools binTools;

  public ExecutionServer(
      Path workPath,
      Path sandboxPath,
      RemoteWorkerOptions workerOptions,
      OnDiskBlobStoreCache cache,
      ConcurrentHashMap<String, ListenableFuture<ActionResult>> operationsCache,
      DigestUtil digestUtil) {
    this.workPath = workPath;
    this.sandboxPath = sandboxPath;
    this.workerOptions = workerOptions;
    this.cache = cache;
    this.operationsCache = operationsCache;
    this.digestUtil = digestUtil;
    ThreadPoolExecutor realExecutor =
        new ThreadPoolExecutor(
            // This is actually the max number of concurrent jobs.
            workerOptions.jobs,
            // Since we use an unbounded queue, the executor ignores this value, but it still checks
            // that it is greater or equal to the value above.
            workerOptions.jobs,
            // Shut down idle threads after one minute. Threads aren't all that expensive, but we
            // also
            // don't need to keep them around if we don't need them.
            1,
            TimeUnit.MINUTES,
            // We use an unbounded queue for now.
            // TODO(ulfjack): We need to reject work eventually.
            new LinkedBlockingQueue<>(),
            new ThreadFactoryBuilder().setNameFormat("subprocess-handler-%d").build());
    // Allow the core threads to die.
    realExecutor.allowCoreThreadTimeOut(true);
    this.executorService = MoreExecutors.listeningDecorator(realExecutor);
    this.localEnvProvider = LocalEnvProvider.forCurrentOs(System.getenv());
    String xcodeLocator;
    try {
      xcodeLocator =
          Runfiles.preload()
              .withSourceRepository("")
              .rlocation(
                  "io_bazel/src/tools/remote/src/main/java/com/google/devtools/build/remote/worker/xcode-locator");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    this.binTools =
        BinTools.forEmbeddedBin(
            workPath.getFileSystem().getPath(xcodeLocator).getParentDirectory(),
            ImmutableList.of("xcode-locator"));
  }

  @Override
  public void waitExecution(WaitExecutionRequest wr, StreamObserver<Operation> responseObserver) {
    final String opName = wr.getName();
    ListenableFuture<ActionResult> future = operationsCache.get(opName);
    if (future == null) {
      responseObserver.onError(
          StatusProto.toStatusRuntimeException(
              Status.newBuilder()
                  .setCode(Code.NOT_FOUND.getNumber())
                  .setMessage("Operation not found: " + opName)
                  .build()));
      return;
    }
    ((ServerCallStreamObserver<Operation>) responseObserver)
        .setOnCancelHandler(() -> operationsCache.remove(opName));
    waitExecution(opName, future, responseObserver);
  }

  private void waitExecution(
      String opName,
      ListenableFuture<ActionResult> future,
      StreamObserver<Operation> responseObserver) {
    future.addListener(
        () -> {
          try {
            try {
              ActionResult result = future.get();
              responseObserver.onNext(
                  Operation.newBuilder()
                      .setName(opName)
                      .setDone(true)
                      .setResponse(Any.pack(ExecuteResponse.newBuilder().setResult(result).build()))
                      .build());
              responseObserver.onCompleted();
            } catch (ExecutionException e) {
              Throwables.throwIfUnchecked(e.getCause());
              throw (Exception) e.getCause();
            }
          } catch (Exception e) {
            ExecuteResponse resp;
            if (e instanceof ExecutionStatusException executionStatusException) {
              resp = executionStatusException.getResponse();
            } else {
              logger.atSevere().withCause(e).log("Work failed: %s", opName);
              resp =
                  ExecuteResponse.newBuilder()
                      .setStatus(StatusUtils.internalErrorStatus(e))
                      .build();
            }
            responseObserver.onNext(
                Operation.newBuilder()
                    .setName(opName)
                    .setDone(true)
                    .setResponse(Any.pack(resp))
                    .build());
            responseObserver.onCompleted();
            if (e instanceof InterruptedException) {
              Thread.currentThread().interrupt();
            }
          } finally {
            operationsCache.remove(opName);
          }
        },
        MoreExecutors.directExecutor());
  }

  @Override
  public void execute(ExecuteRequest request, StreamObserver<Operation> responseObserver) {
    RequestMetadata metadata = TracingMetadataUtils.fromCurrentContext();
    RemoteActionExecutionContext context = RemoteActionExecutionContext.create(metadata);

    final String opName = UUID.randomUUID().toString();
    ListenableFuture<ActionResult> future =
        executorService.submit(() -> execute(context, request, opName));
    operationsCache.put(opName, future);
    ((ServerCallStreamObserver<Operation>) responseObserver)
        .setOnCancelHandler(() -> operationsCache.remove(opName));
    // Send the first operation.
    responseObserver.onNext(Operation.newBuilder().setName(opName).build());
    // When the operation completes, send the result.
    waitExecution(opName, future, responseObserver);
  }

  @SuppressWarnings("LogAndThrow")
  private ActionResult execute(
      RemoteActionExecutionContext context, ExecuteRequest request, String id)
      throws IOException, InterruptedException, StatusException {
    Path tempRoot = workPath.getRelative("build-" + id);
    String workDetails = "";
    try {
      tempRoot.createDirectory();
      RequestMetadata meta = context.getRequestMetadata();
      workDetails =
          String.format(
              "build-request-id: %s command-id: %s action-id: %s",
              meta.getCorrelatedInvocationsId(), meta.getToolInvocationId(), meta.getActionId());
      logger.atFine().log("Received work for: %s", workDetails);
      ActionResult result =
          execute(
              context,
              request.getActionDigest(),
              ImmutableSet.copyOf(request.getInlineOutputFilesList()),
              tempRoot);
      logger.atFine().log("Completed %s", workDetails);
      return result;
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Work failed: %s", workDetails);
      throw e;
    } finally {
      if (workerOptions.debug) {
        logger.atInfo().log("Preserving work directory %s", tempRoot);
      } else {
        try {
          tempRoot.deleteTree();
        } catch (IOException e) {
          logger.atSevere().withCause(e).log("Failed to delete tmp directory %s", tempRoot);
        }
      }
    }
  }

  private ActionResult execute(
      RemoteActionExecutionContext context,
      Digest actionDigest,
      Set<String> inlineOutputFiles,
      Path execRoot)
      throws IOException, InterruptedException, StatusException {
    Command command;
    Action action;
    ActionKey actionKey = digestUtil.asActionKey(actionDigest);
    try {
      action =
          Action.parseFrom(
              getFromFuture(cache.downloadBlob(context, actionDigest)),
              ExtensionRegistry.getEmptyRegistry());
      command =
          Command.parseFrom(
              getFromFuture(cache.downloadBlob(context, action.getCommandDigest())),
              ExtensionRegistry.getEmptyRegistry());
      cache.downloadTree(context, action.getInputRootDigest(), execRoot);
    } catch (CacheNotFoundException e) {
      throw StatusUtils.missingBlobError(e.getMissingDigest());
    }

    Path workingDirectory = execRoot.getRelative(command.getWorkingDirectory());
    workingDirectory.createDirectoryAndParents();

    List<Path> outputs;
    if (command.getOutputPathsCount() == 0) {
      outputs =
          new ArrayList<>(command.getOutputDirectoriesCount() + command.getOutputFilesCount());
      for (String output : command.getOutputFilesList()) {
        var file = workingDirectory.getRelative(output);
        if (file.exists()) {
          throw new FileAlreadyExistsException("Output file already exists: " + file);
        }
        file.getParentDirectory().createDirectoryAndParents();
        outputs.add(file);
      }
      for (String output : command.getOutputDirectoriesList()) {
        Path file = workingDirectory.getRelative(output);
        if (file.exists()) {
          if (!file.isDirectory()) {
            throw new FileAlreadyExistsException(
                "Non-directory exists at output directory path: " + file);
          }
        }
        file.getParentDirectory().createDirectoryAndParents();
        outputs.add(file);
      }
    } else {
      outputs = new ArrayList<>(command.getOutputPathsCount());
      for (String output : command.getOutputPathsList()) {
        var file = workingDirectory.getRelative(output);
        // Since https://github.com/bazelbuild/bazel/pull/15818,
        // Bazel includes all expected output directories as part of Action's inputs.
        //
        // Ensure no output file exists before execution happen.
        // Ignore if output directories pre-exist.
        if (file.exists() && !file.isDirectory()) {
          throw new FileAlreadyExistsException("Output file already exists: " + file);
        }
        file.getParentDirectory().createDirectoryAndParents();
        outputs.add(file);
      }
    }

    // TODO(ulfjack): This is basically a copy of LocalSpawnRunner. Ideally, we'd use that
    // implementation instead of copying it.
    com.google.devtools.build.lib.shell.Command cmd =
        getCommand(command, workingDirectory.getPathString());
    Instant startTime = Instant.now();
    CommandResult cmdResult = null;

    String uuid = UUID.randomUUID().toString();
    Path stdout = execRoot.getChild("stdout-" + uuid);
    Path stderr = execRoot.getChild("stderr-" + uuid);
    try (FileOutErr outErr = new FileOutErr(stdout, stderr)) {

      FutureCommandResult futureCmdResult = null;
      try {
        futureCmdResult = cmd.executeAsync(outErr.getOutputStream(), outErr.getErrorStream());
      } catch (CommandException e) {
        Throwables.throwIfInstanceOf(e.getCause(), IOException.class);
      }

      if (futureCmdResult != null) {
        try {
          cmdResult = futureCmdResult.get();
        } catch (AbnormalTerminationException e) {
          cmdResult = e.getResult();
        }
      }

      Duration wallTime = Duration.between(startTime, Instant.now());
      long timeoutMillis =
          action.hasTimeout()
              ? Durations.toMillis(action.getTimeout())
              : TimeUnit.MINUTES.toMillis(15);
      boolean wasTimeout =
          (cmdResult != null && cmdResult.terminationStatus().timedOut())
              || wasTimeout(timeoutMillis, wallTime.toMillis());
      final int exitCode;
      Status errStatus = null;
      ExecuteResponse.Builder resp = ExecuteResponse.newBuilder();
      if (wasTimeout) {
        final String errMessage =
            String.format(
                "Command:\n%s\nexceeded deadline of %f seconds.",
                Arrays.toString(command.getArgumentsList().toArray()), timeoutMillis / 1000.0);
        logger.atWarning().log("%s", errMessage);
        errStatus =
            Status.newBuilder()
                .setCode(Code.DEADLINE_EXCEEDED.getNumber())
                .setMessage(errMessage)
                .build();
        exitCode = LOCAL_EXEC_ERROR;
      } else if (cmdResult == null) {
        exitCode = LOCAL_EXEC_ERROR;
      } else {
        exitCode = cmdResult.terminationStatus().getRawExitCode();
      }

      ActionResult result = null;
      try {
        UploadManifest manifest =
            UploadManifest.create(
                cache.getRemoteOptions(),
                cache.getRemoteCacheCapabilities(),
                digestUtil,
                RemotePathResolver.createDefault(workingDirectory),
                actionKey,
                action,
                command,
                outputs,
                outErr,
                exitCode,
                startTime,
                (int) wallTime.toMillis());
        result = manifest.upload(context, cache, NullEventHandler.INSTANCE);
      } catch (ExecException e) {
        if (errStatus == null) {
          errStatus =
              Status.newBuilder()
                  .setCode(Code.FAILED_PRECONDITION.getNumber())
                  .setMessage(e.getMessage())
                  .build();
        }
      }

      if (result == null) {
        result = ActionResult.newBuilder().setExitCode(exitCode).build();
      }

      for (int i = 0; i < result.getOutputFilesCount(); i++) {
        var outputFile = result.getOutputFiles(i);
        if (inlineOutputFiles.contains(outputFile.getPath())) {
          try {
            ByteString content =
                ByteString.copyFrom(cache.downloadBlob(context, outputFile.getDigest()).get());
            result =
                result.toBuilder()
                    .setOutputFiles(i, outputFile.toBuilder().setContents(content))
                    .build();
          } catch (ExecutionException e) {
            // Inlining is best-effort. If it fails, we just don't inline the file.
          }
          break;
        }
      }

      resp.setResult(result);

      if (errStatus != null) {
        resp.setStatus(errStatus);
        throw new ExecutionStatusException(errStatus, resp.build());
      }

      return result;
    }
  }

  // Returns true if the OS being run on is Windows (or some close approximation thereof).
  private static boolean isWindows() {
    return System.getProperty("os.name").startsWith("Windows");
  }

  private static boolean wasTimeout(long timeoutMillis, long wallTimeMillis) {
    return timeoutMillis > 0 && wallTimeMillis > timeoutMillis;
  }

  private Map<String, String> getEnvironmentVariables(Command command)
      throws IOException, InterruptedException {
    HashMap<String, String> result = new HashMap<>();
    for (EnvironmentVariable v : command.getEnvironmentVariablesList()) {
      result.put(v.getName(), v.getValue());
    }
    return new HashMap<>(localEnvProvider.rewriteLocalEnv(result, binTools, "/tmp"));
  }

  // Gets the uid of the current user. If uid could not be successfully fetched (e.g., on other
  // platforms, if for some reason the timeout was not met, if "id -u" returned non-numeric
  // number, etc), logs a WARNING and return -1.
  // This is used to set "-u UID" flag for commands running inside Docker containers. There are
  // only a small handful of cases where uid is vital (e.g., if strict permissions are set on the
  // output files), so most use cases would work without setting uid.
  private static long getUid() throws InterruptedException {
    com.google.devtools.build.lib.shell.Command cmd =
        new com.google.devtools.build.lib.shell.Command(
            new String[] {"id", "-u"},
            /* environmentVariables= */ null,
            /* workingDirectory= */ null,
            uidTimeout);
    try {
      ByteArrayOutputStream stdout = new ByteArrayOutputStream();
      ByteArrayOutputStream stderr = new ByteArrayOutputStream();
      cmd.execute(stdout, stderr);
      return Long.parseLong(stdout.toString().trim());
    } catch (CommandException | NumberFormatException e) {
      logger.atWarning().withCause(e).log(
          "Could not get UID for passing to Docker container. Proceeding without it");
      return -1;
    }
  }

  // Checks Action for docker container definition. If no docker container specified, returns
  // null. Otherwise returns docker container name from the parameters.
  private static String dockerContainer(Command cmd) throws StatusException {
    String result = null;
    for (Platform.Property property : cmd.getPlatform().getPropertiesList()) {
      if (property.getName().equals(CONTAINER_IMAGE_ENTRY_NAME)) {
        if (result != null) {
          // Multiple container name entries
          throw StatusUtils.invalidArgumentError(
              "platform", // Field name.
              String.format(
                  "Multiple entries for %s in action.Platform", CONTAINER_IMAGE_ENTRY_NAME));
        }
        result = property.getValue();
        if (!result.startsWith(DOCKER_IMAGE_PREFIX)) {
          throw StatusUtils.invalidArgumentError(
              "platform", // Field name.
              String.format(
                  "%s: Docker images must be stored in gcr.io with an image spec in the form "
                      + "'docker://gcr.io/{IMAGE_NAME}'",
                  CONTAINER_IMAGE_ENTRY_NAME));
        }
        result = result.substring(DOCKER_IMAGE_PREFIX.length());
      }
    }
    return result;
  }

  private static String platformAsString(@Nullable Platform platform) {
    if (platform == null) {
      return "";
    }

    String separator = "";
    StringBuilder value = new StringBuilder();
    for (Property property : platform.getPropertiesList()) {
      value.append(separator).append(property.getName()).append("=").append(property.getValue());
      separator = ",";
    }
    return value.toString();
  }

  // Converts the Command proto into the shell Command object.
  // If no docker container is specified, creates a Command straight from the
  // arguments. Otherwise, returns a Command that would run the specified command inside the
  // specified docker container.
  private com.google.devtools.build.lib.shell.Command getCommand(Command cmd, String pathString)
      throws StatusException, InterruptedException, IOException {
    Map<String, String> environmentVariables = getEnvironmentVariables(cmd);
    // This allows Bazel's integration tests to test for the remote platform.
    environmentVariables.put("BAZEL_REMOTE_PLATFORM", platformAsString(cmd.getPlatform()));
    String container = dockerContainer(cmd);
    if (container != null) {
      // Run command inside a docker container.
      ArrayList<String> newCommandLineElements = new ArrayList<>(cmd.getArgumentsCount());
      newCommandLineElements.add("docker");
      newCommandLineElements.add("run");

      // -u doesn't currently make sense for Windows:
      // https://github.com/docker/for-win/issues/636#issuecomment-293653788
      if (!isWindows()) {
        long uid = getUid();
        if (uid >= 0) {
          newCommandLineElements.add("-u");
          newCommandLineElements.add(Long.toString(uid));
        }
      }

      String dockerPathString = pathString + "-docker";
      newCommandLineElements.add("-v");
      newCommandLineElements.add(pathString + ":" + dockerPathString);
      newCommandLineElements.add("-w");
      newCommandLineElements.add(dockerPathString);

      for (Map.Entry<String, String> entry : environmentVariables.entrySet()) {
        String key = entry.getKey();
        String value = entry.getValue();

        newCommandLineElements.add("-e");
        newCommandLineElements.add(key + "=" + value);
      }

      newCommandLineElements.add(container);

      newCommandLineElements.addAll(cmd.getArgumentsList());

      return new com.google.devtools.build.lib.shell.Command(
          newCommandLineElements.toArray(new String[0]), null, new File(pathString));
    } else if (sandboxPath != null) {
      // Run command with sandboxing.
      ArrayList<String> newCommandLineElements = new ArrayList<>(cmd.getArgumentsCount());
      newCommandLineElements.add(sandboxPath.getPathString());
      if (workerOptions.sandboxingBlockNetwork) {
        newCommandLineElements.add("-N");
      }
      for (String writablePath : workerOptions.sandboxingWritablePaths) {
        newCommandLineElements.add("-w");
        newCommandLineElements.add(writablePath);
      }
      for (String tmpfsDir : workerOptions.sandboxingTmpfsDirs) {
        newCommandLineElements.add("-e");
        newCommandLineElements.add(tmpfsDir);
      }
      newCommandLineElements.add("--");
      newCommandLineElements.addAll(cmd.getArgumentsList());
      return new com.google.devtools.build.lib.shell.Command(
          newCommandLineElements.toArray(new String[0]),
          environmentVariables,
          new File(pathString));
    } else {
      // Just run the command.
      return new com.google.devtools.build.lib.shell.Command(
          cmd.getArgumentsList().toArray(new String[0]),
          environmentVariables,
          new File(pathString));
    }
  }
}
