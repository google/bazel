/// Copyright 2017 The Bazel Authors. All rights reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.remote.GrpcCacheClient.getResourceName;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.fail;
import static org.mockito.AdditionalAnswers.answerVoid;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import build.bazel.remote.execution.v2.ActionCacheGrpc.ActionCacheImplBase;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageImplBase;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.DigestFunction;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.ExecuteRequest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.ExecutionCapabilities;
import build.bazel.remote.execution.v2.ExecutionGrpc.ExecutionImplBase;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.FindMissingBlobsRequest;
import build.bazel.remote.execution.v2.FindMissingBlobsResponse;
import build.bazel.remote.execution.v2.GetActionResultRequest;
import build.bazel.remote.execution.v2.OutputDirectory;
import build.bazel.remote.execution.v2.OutputFile;
import build.bazel.remote.execution.v2.RequestMetadata;
import build.bazel.remote.execution.v2.ServerCapabilities;
import build.bazel.remote.execution.v2.Tree;
import build.bazel.remote.execution.v2.WaitExecutionRequest;
import com.google.bytestream.ByteStreamGrpc.ByteStreamImplBase;
import com.google.bytestream.ByteStreamProto.QueryWriteStatusRequest;
import com.google.bytestream.ByteStreamProto.QueryWriteStatusResponse;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.bytestream.ByteStreamProto.WriteRequest;
import com.google.bytestream.ByteStreamProto.WriteResponse;
import com.google.common.collect.ImmutableClassToInstanceMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionInputMap;
import com.google.devtools.build.lib.actions.ActionOutputDirectoryHelper;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.SimpleSpawn;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.StaticInputMetadataProvider;
import com.google.devtools.build.lib.analysis.BlazeVersionInfo;
import com.google.devtools.build.lib.authandtls.CallCredentialsProvider;
import com.google.devtools.build.lib.authandtls.GoogleAuthUtils;
import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.util.FakeOwner;
import com.google.devtools.build.lib.remote.RemoteRetrier.ExponentialBackoff;
import com.google.devtools.build.lib.remote.common.RemotePathResolver;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.FakeSpawnExecutionContext;
import com.google.devtools.build.lib.remote.util.TestUtils;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.util.TempPathGenerator;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.OutputPermissions;
import com.google.devtools.build.lib.vfs.OutputService;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.Options;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.rpc.Code;
import com.google.rpc.PreconditionFailure;
import com.google.rpc.PreconditionFailure.Violation;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.util.MutableHandlerRegistry;
import io.reactivex.rxjava3.core.Single;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

/** Tests for {@link RemoteSpawnRunner} in combination with {@link GrpcRemoteExecutor}. */
@RunWith(JUnit4.class)
public class RemoteSpawnRunnerWithGrpcRemoteExecutorTest {
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private RemoteOutputChecker remoteOutputChecker; // download nothing by default.

  private static final DigestUtil DIGEST_UTIL =
      new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256);

  private final Reporter reporter = new Reporter(new EventBus());
  private final MutableHandlerRegistry serviceRegistry = new MutableHandlerRegistry();
  private FileSystem fs;
  private Path execRoot;
  private ArtifactRoot artifactRoot;
  private TempPathGenerator tempPathGenerator;
  private Path logDir;
  private SimpleSpawn simpleSpawn;
  private FakeActionInputFileCache fakeFileCache;
  private Digest inputDigest;
  private Digest cmdDigest;
  private Command command;
  private RemoteSpawnRunner client;
  private FileOutErr outErr;
  private RemoteOptions remoteOptions;
  private RemoteExecutionCache remoteCache;
  private Server fakeServer;
  private ListeningScheduledExecutorService retryService;

  private static final OutputFile DUMMY_OUTPUT =
      OutputFile.newBuilder()
          .setPath("dummy.txt")
          .setDigest(
              Digest.newBuilder()
                  .setHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                  .setSizeBytes(0)
                  .build())
          .build();

  private static final Tree DUMMY_OUTPUT_TREE =
      Tree.newBuilder()
          .setRoot(
              Directory.newBuilder()
                  .addFiles(
                      FileNode.newBuilder()
                          .setName(DUMMY_OUTPUT.getPath())
                          .setDigest(DUMMY_OUTPUT.getDigest())
                          .setIsExecutable(true)
                          .build())
                  .build())
          .build();

  private static final OutputDirectory DUMMY_OUTPUT_DIRECTORY =
      OutputDirectory.newBuilder()
          .setPath("dummy")
          .setTreeDigest(DIGEST_UTIL.compute(DUMMY_OUTPUT_TREE))
          .build();

  @Before
  public final void setUp() throws Exception {
    String fakeServerName = "fake server for " + getClass();
    // Use a mutable service registry for later registering the service impl for each test case.
    fakeServer =
        InProcessServerBuilder.forName(fakeServerName)
            .fallbackHandlerRegistry(serviceRegistry)
            .directExecutor()
            .build()
            .start();

    Chunker.setDefaultChunkSizeForTesting(1000); // Enough for everything to be one chunk.
    fs = new InMemoryFileSystem(new JavaClock(), DigestHashFunction.SHA256);
    execRoot = fs.getPath("/execroot/main");
    execRoot.createDirectoryAndParents();
    artifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "outputs");
    artifactRoot.getRoot().asPath().createDirectoryAndParents();
    tempPathGenerator = new TempPathGenerator(fs.getPath("/execroot/_tmp/actions/remote"));
    logDir = fs.getPath("/server-logs");
    fakeFileCache = new FakeActionInputFileCache(execRoot);
    simpleSpawn =
        new SimpleSpawn(
            new FakeOwner("Mnemonic", "Progress Message", "//dummy:label"),
            ImmutableList.of("/bin/echo", "Hi!"),
            ImmutableMap.of("VARIABLE", "value"),
            /* executionInfo= */ ImmutableMap.<String, String>of(),
            /* filesetMappings= */ ImmutableMap.of(),
            /* inputs= */ NestedSetBuilder.create(
                Order.STABLE_ORDER, ActionInputHelper.fromPath("input")),
            /* tools= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            /* outputs= */ ImmutableSet.of(
                new ActionInput() {
                  @Override
                  public String getExecPathString() {
                    return "foo";
                  }

                  @Override
                  public boolean isDirectory() {
                    return false;
                  }

                  @Override
                  public boolean isSymlink() {
                    return false;
                  }

                  @Override
                  public PathFragment getExecPath() {
                    return PathFragment.create("foo");
                  }
                },
                new ActionInput() {
                  @Override
                  public String getExecPathString() {
                    return "bar";
                  }

                  @Override
                  public boolean isDirectory() {
                    return false;
                  }

                  @Override
                  public boolean isSymlink() {
                    return false;
                  }

                  @Override
                  public PathFragment getExecPath() {
                    return PathFragment.create("bar");
                  }
                }),
            /* mandatoryOutputs= */ ImmutableSet.of(),
            ResourceSet.ZERO);

    Path stdout = fs.getPath("/tmp/stdout");
    Path stderr = fs.getPath("/tmp/stderr");
    stdout.getParentDirectory().createDirectoryAndParents();
    stderr.getParentDirectory().createDirectoryAndParents();
    outErr = new FileOutErr(stdout, stderr);
    remoteOptions = Options.getDefaults(RemoteOptions.class);

    remoteOptions.remoteHeaders =
        ImmutableList.of(
            Maps.immutableEntry("CommonKey1", "CommonValue1"),
            Maps.immutableEntry("CommonKey2", "CommonValue2"));
    remoteOptions.remoteExecHeaders =
        ImmutableList.of(
            Maps.immutableEntry("ExecKey1", "ExecValue1"),
            Maps.immutableEntry("ExecKey2", "ExecValue2"));
    remoteOptions.remoteCacheHeaders =
        ImmutableList.of(
            Maps.immutableEntry("CacheKey1", "CacheValue1"),
            Maps.immutableEntry("CacheKey2", "CacheValue2"));

    retryService = MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1));
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(
            () -> new ExponentialBackoff(remoteOptions),
            RemoteRetrier.RETRIABLE_GRPC_EXEC_ERRORS,
            retryService);
    ReferenceCountedChannel channel =
        new ReferenceCountedChannel(
            new ChannelConnectionWithServerCapabilitiesFactory() {
              @Override
              public Single<ChannelConnectionWithServerCapabilities> create() {
                ManagedChannel ch =
                    InProcessChannelBuilder.forName(fakeServerName)
                        .intercept(TracingMetadataUtils.newExecHeadersInterceptor(remoteOptions))
                        .directExecutor()
                        .build();
                ServerCapabilities caps =
                    ServerCapabilities.newBuilder()
                        .setLowApiVersion(ApiVersion.low.toSemVer())
                        .setHighApiVersion(ApiVersion.high.toSemVer())
                        .setExecutionCapabilities(
                            ExecutionCapabilities.newBuilder().setExecEnabled(true).build())
                        .build();
                return Single.just(
                    new ChannelConnectionWithServerCapabilities(ch, Single.just(caps)));
              }

              @Override
              public int maxConcurrency() {
                return 100;
              }
            });

    GrpcRemoteExecutor executor =
        new GrpcRemoteExecutor(channel.retain(), CallCredentialsProvider.NO_CREDENTIALS, retrier);
    CallCredentialsProvider callCredentialsProvider =
        GoogleAuthUtils.newCallCredentialsProvider(null);
    GrpcCacheClient cacheProtocol =
        new GrpcCacheClient(
            channel.retain(), callCredentialsProvider, remoteOptions, retrier, DIGEST_UTIL);
    remoteCache =
        new RemoteExecutionCache(
            cacheProtocol, /* diskCacheClient= */ null, remoteOptions, DIGEST_UTIL);
    RemoteExecutionService remoteExecutionService =
        new RemoteExecutionService(
            directExecutor(),
            reporter,
            /* verboseFailures= */ true,
            execRoot,
            RemotePathResolver.createDefault(execRoot),
            "build-req-id",
            "command-id",
            DIGEST_UTIL,
            remoteOptions,
            remoteCache,
            executor,
            tempPathGenerator,
            /* captureCorruptedOutputsDir= */ null,
            remoteOutputChecker,
            mock(OutputService.class),
            Sets.newConcurrentHashSet());
    client =
        new RemoteSpawnRunner(
            execRoot,
            remoteOptions,
            Options.getDefaults(ExecutionOptions.class),
            /* verboseFailures= */ true,
            /* cmdlineReporter= */ null,
            retryService,
            logDir,
            remoteExecutionService,
            DIGEST_UTIL);

    inputDigest =
        fakeFileCache.createScratchInput(simpleSpawn.getInputFiles().getSingleton(), "xyz");
    command =
        Command.newBuilder()
            .addAllArguments(ImmutableList.of("/bin/echo", "Hi!"))
            .addEnvironmentVariables(
                Command.EnvironmentVariable.newBuilder()
                    .setName("VARIABLE")
                    .setValue("value")
                    .build())
            .addAllOutputPaths(ImmutableList.of("bar", "foo"))
            .build();
    cmdDigest = DIGEST_UTIL.compute(command);
    channel.release();
  }

  @After
  public void tearDown() throws Exception {
    retryService.shutdownNow();
    retryService.awaitTermination(
        com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS, SECONDS);

    fakeServer.shutdownNow();
    fakeServer.awaitTermination();
  }

  @Test
  public void cacheHit() throws Exception {
    serviceRegistry.addService(
        new ActionCacheImplBase() {
          @Override
          public void getActionResult(
              GetActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
            responseObserver.onNext(ActionResult.getDefaultInstance());
            responseObserver.onCompleted();
          }
        });

    FakeSpawnExecutionContext context = getSpawnContext(simpleSpawn);

    SpawnResult result = client.exec(simpleSpawn, context);
    assertThat(result.setupSuccess()).isTrue();
    assertThat(result.isCacheHit()).isTrue();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(outErr.hasRecordedOutput()).isFalse();
    assertThat(outErr.hasRecordedStderr()).isFalse();
  }

  @Test
  public void failedAction() throws Exception {
    serviceRegistry.addService(
        new ActionCacheImplBase() {
          @Override
          public void getActionResult(
              GetActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
            responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
          }
        });
    final ActionResult actionResult = ActionResult.newBuilder().setExitCode(1).build();
    serviceRegistry.addService(
        new ExecutionImplBase() {
          @Override
          public void execute(ExecuteRequest request, StreamObserver<Operation> responseObserver) {
            responseObserver.onNext(
                Operation.newBuilder()
                    .setDone(true)
                    .setResponse(
                        Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build()))
                    .build());
            responseObserver.onCompleted();
          }
        });
    serviceRegistry.addService(
        new ContentAddressableStorageImplBase() {
          @Override
          public void findMissingBlobs(
              FindMissingBlobsRequest request,
              StreamObserver<FindMissingBlobsResponse> responseObserver) {
            responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance());
            responseObserver.onCompleted();
          }
        });

    FakeSpawnExecutionContext context = getSpawnContext(simpleSpawn);
    SpawnResult result = client.exec(simpleSpawn, context);
    assertThat(result.exitCode()).isEqualTo(1);
  }

  @Test
  public void noOutputs() throws Exception {
    serviceRegistry.addService(
        new ActionCacheImplBase() {
          @Override
          public void getActionResult(
              GetActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
            responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
          }
        });
    final ActionResult actionResult = ActionResult.getDefaultInstance();
    serviceRegistry.addService(
        new ExecutionImplBase() {
          @Override
          public void execute(ExecuteRequest request, StreamObserver<Operation> responseObserver) {
            responseObserver.onNext(
                Operation.newBuilder()
                    .setDone(true)
                    .setResponse(
                        Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build()))
                    .build());
            responseObserver.onCompleted();
          }
        });
    serviceRegistry.addService(
        new ContentAddressableStorageImplBase() {
          @Override
          public void findMissingBlobs(
              FindMissingBlobsRequest request,
              StreamObserver<FindMissingBlobsResponse> responseObserver) {
            responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance());
            responseObserver.onCompleted();
          }
        });

    FakeSpawnExecutionContext context = getSpawnContext(simpleSpawn);
    client.exec(simpleSpawn, context);
  }

  @Test
  public void cacheHitWithOutput() throws Exception {
    final Digest stdOutDigest = DIGEST_UTIL.computeAsUtf8("stdout");
    final Digest stdErrDigest = DIGEST_UTIL.computeAsUtf8("stderr");
    serviceRegistry.addService(
        new ActionCacheImplBase() {
          @Override
          public void getActionResult(
              GetActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
            responseObserver.onNext(
                ActionResult.newBuilder()
                    .addOutputFiles(DUMMY_OUTPUT)
                    .setStdoutDigest(stdOutDigest)
                    .setStderrDigest(stdErrDigest)
                    .build());
            responseObserver.onCompleted();
          }
        });
    serviceRegistry.addService(
        new FakeImmutableCacheByteStreamImpl(stdOutDigest, "stdout", stdErrDigest, "stderr"));

    FakeSpawnExecutionContext context = getSpawnContext(simpleSpawn);
    SpawnResult result = client.exec(simpleSpawn, context);

    assertThat(result.setupSuccess()).isTrue();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.isCacheHit()).isTrue();
    assertThat(outErr.outAsLatin1()).isEqualTo("stdout");
    assertThat(outErr.errAsLatin1()).isEqualTo("stderr");
  }

  @Test
  public void cacheHitWithInlineOutput() throws Exception {
    serviceRegistry.addService(
        new ActionCacheImplBase() {
          @Override
          public void getActionResult(
              GetActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
            responseObserver.onNext(
                ActionResult.newBuilder()
                    .addOutputFiles(DUMMY_OUTPUT)
                    .setStdoutRaw(ByteString.copyFromUtf8("stdout"))
                    .setStderrRaw(ByteString.copyFromUtf8("stderr"))
                    .build());
            responseObserver.onCompleted();
          }
        });

    FakeSpawnExecutionContext context = getSpawnContext(simpleSpawn);
    SpawnResult result = client.exec(simpleSpawn, context);

    assertThat(result.setupSuccess()).isTrue();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.isCacheHit()).isTrue();
    assertThat(outErr.outAsLatin1()).isEqualTo("stdout");
    assertThat(outErr.errAsLatin1()).isEqualTo("stderr");
  }

  private static Answer<StreamObserver<WriteRequest>> blobWriteAnswer(final byte[] data) {
    final Digest digest = DIGEST_UTIL.compute(data);
    return new Answer<StreamObserver<WriteRequest>>() {
      @Override
      public StreamObserver<WriteRequest> answer(InvocationOnMock invocation) {
        @SuppressWarnings("unchecked")
        final StreamObserver<WriteResponse> responseObserver =
            (StreamObserver<WriteResponse>) invocation.getArguments()[0];
        return new StreamObserver<WriteRequest>() {
          @Override
          public void onNext(WriteRequest request) {
            assertThat(request.getResourceName()).contains(DigestUtil.toString(digest));
            assertThat(request.getFinishWrite()).isTrue();
            assertThat(request.getData().toByteArray()).isEqualTo(data);
            responseObserver.onNext(
                WriteResponse.newBuilder().setCommittedSize(request.getData().size()).build());
          }

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }

          @Override
          public void onError(Throwable t) {
            fail("An error occurred: " + t);
          }
        };
      }
    };
  }

  private static Answer<StreamObserver<WriteRequest>> blobWriteAnswerError() {
    return new Answer<StreamObserver<WriteRequest>>() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<WriteRequest> answer(final InvocationOnMock invocation) {
        return new StreamObserver<WriteRequest>() {
          @Override
          public void onNext(WriteRequest request) {
            ((StreamObserver<WriteResponse>) invocation.getArguments()[0])
                .onError(Status.UNAVAILABLE.asRuntimeException());
          }

          @Override
          public void onCompleted() {}

          @Override
          public void onError(Throwable t) {
            fail("An unexpected client-side error occurred: " + t);
          }
        };
      }
    };
  }

  /** Capture the request headers from a client. Useful for testing metadata propagation. */
  private static class RequestHeadersValidator implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      RequestMetadata meta = headers.get(TracingMetadataUtils.METADATA_KEY);
      assertThat(meta.getCorrelatedInvocationsId()).isEqualTo("build-req-id");
      assertThat(meta.getToolInvocationId()).isEqualTo("command-id");
      assertThat(meta.getActionId()).isNotEmpty();
      assertThat(meta.getToolDetails().getToolName()).isEqualTo("bazel");
      assertThat(meta.getToolDetails().getToolVersion())
          .isEqualTo(BlazeVersionInfo.instance().getVersion());
      return next.startCall(call, headers);
    }
  }

  @Test
  public void extraHeaders() throws Exception {
    BindableService actionCache =
        new ActionCacheImplBase() {
          @Override
          public void getActionResult(
              GetActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
            responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
          }
        };
    serviceRegistry.addService(actionCache);

    BindableService cas =
        new ContentAddressableStorageImplBase() {
          @Override
          public void findMissingBlobs(
              FindMissingBlobsRequest request,
              StreamObserver<FindMissingBlobsResponse> responseObserver) {
            responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance());
            responseObserver.onCompleted();
          }
        };
    serviceRegistry.addService(cas);

    BindableService execService =
        new ExecutionImplBase() {
          @Override
          public void execute(ExecuteRequest request, StreamObserver<Operation> responseObserver) {
            responseObserver.onNext(Operation.getDefaultInstance());
            responseObserver.onCompleted();
          }
        };
    ServerInterceptor interceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call,
              Metadata metadata,
              ServerCallHandler<ReqT, RespT> next) {
            assertThat(
                    metadata.get(Metadata.Key.of("CommonKey1", Metadata.ASCII_STRING_MARSHALLER)))
                .isEqualTo("CommonValue1");
            assertThat(
                    metadata.get(Metadata.Key.of("CommonKey2", Metadata.ASCII_STRING_MARSHALLER)))
                .isEqualTo("CommonValue2");
            assertThat(metadata.get(Metadata.Key.of("ExecKey1", Metadata.ASCII_STRING_MARSHALLER)))
                .isEqualTo("ExecValue1");
            assertThat(metadata.get(Metadata.Key.of("ExecKey2", Metadata.ASCII_STRING_MARSHALLER)))
                .isEqualTo("ExecValue2");
            assertThat(metadata.get(Metadata.Key.of("CacheKey1", Metadata.ASCII_STRING_MARSHALLER)))
                .isEqualTo(null);
            assertThat(metadata.get(Metadata.Key.of("CacheKey2", Metadata.ASCII_STRING_MARSHALLER)))
                .isEqualTo(null);
            return next.startCall(call, metadata);
          }
        };
    serviceRegistry.addService(ServerInterceptors.intercept(execService, interceptor));

    FakeSpawnExecutionContext context = getSpawnContext(simpleSpawn);
    client.exec(simpleSpawn, context);
  }

  @Test
  public void remotelyExecute() throws Exception {
    BindableService actionCache =
        new ActionCacheImplBase() {
          @Override
          public void getActionResult(
              GetActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
            responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
          }
        };
    serviceRegistry.addService(
        ServerInterceptors.intercept(actionCache, new RequestHeadersValidator()));
    final ActionResult actionResult =
        ActionResult.newBuilder()
            .addOutputFiles(DUMMY_OUTPUT)
            .setStdoutRaw(ByteString.copyFromUtf8("stdout"))
            .setStderrRaw(ByteString.copyFromUtf8("stderr"))
            .build();
    BindableService execService =
        new ExecutionImplBase() {
          @Override
          public void execute(ExecuteRequest request, StreamObserver<Operation> responseObserver) {
            responseObserver.onNext(
                Operation.newBuilder()
                    .setDone(true)
                    .setResponse(
                        Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build()))
                    .build());
            responseObserver.onCompleted();
          }
        };
    serviceRegistry.addService(
        ServerInterceptors.intercept(execService, new RequestHeadersValidator()));
    BindableService cas =
        new ContentAddressableStorageImplBase() {
          @Override
          public void findMissingBlobs(
              FindMissingBlobsRequest request,
              StreamObserver<FindMissingBlobsResponse> responseObserver) {
            final Set<Digest> requested = ImmutableSet.copyOf(request.getBlobDigestsList());
            assertThat(requested).contains(cmdDigest);
            assertThat(requested).contains(inputDigest);
            responseObserver.onNext(
                FindMissingBlobsResponse.newBuilder().addMissingBlobDigests(inputDigest).build());
            responseObserver.onCompleted();
          }
        };
    serviceRegistry.addService(ServerInterceptors.intercept(cas, new RequestHeadersValidator()));

    ByteStreamImplBase mockByteStreamImpl = spy(ByteStreamImplBase.class);
    doAnswer(blobWriteAnswer("xyz".getBytes(UTF_8))).when(mockByteStreamImpl).write(any());
    serviceRegistry.addService(
        ServerInterceptors.intercept(mockByteStreamImpl, new RequestHeadersValidator()));

    FakeSpawnExecutionContext context = getSpawnContext(simpleSpawn);

    SpawnResult result = client.exec(simpleSpawn, context);
    assertThat(result.setupSuccess()).isTrue();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.isCacheHit()).isFalse();
    assertThat(outErr.outAsLatin1()).isEqualTo("stdout");
    assertThat(outErr.errAsLatin1()).isEqualTo("stderr");
    verify(mockByteStreamImpl).write(ArgumentMatchers.<StreamObserver<WriteResponse>>any());
  }

  private static Answer<Void> answerWith(@Nullable Operation op, Status status) {
    return invocationOnMock -> {
      @SuppressWarnings("unchecked")
      StreamObserver<Operation> responseObserver =
          (StreamObserver<Operation>) invocationOnMock.getArguments()[1];
      if (op != null) {
        responseObserver.onNext(op);
      }
      if (status.isOk()) {
        responseObserver.onCompleted();
      } else {
        responseObserver.onError(status.asRuntimeException());
      }
      return null;
    };
  }

  @Test
  public void remotelyExecuteRetries() throws Exception {
    when(remoteOutputChecker.shouldDownloadOutput(ArgumentMatchers.<PathFragment>any()))
        .thenReturn(true);

    serviceRegistry.addService(
        new ActionCacheImplBase() {
          private int numErrors = 4;

          @Override
          public void getActionResult(
              GetActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
            responseObserver.onError(
                (numErrors-- <= 0 ? Status.NOT_FOUND : Status.UNAVAILABLE).asRuntimeException());
          }
        });
    final Digest resultDigest = DIGEST_UTIL.compute("bla".getBytes(UTF_8));
    final ActionResult actionResult =
        ActionResult.newBuilder()
            .setStdoutRaw(ByteString.copyFromUtf8("stdout"))
            .setStderrRaw(ByteString.copyFromUtf8("stderr"))
            .addOutputFiles(OutputFile.newBuilder().setPath("foo").setDigest(resultDigest).build())
            .build();
    final String opName = "operations/xyz";

    ExecuteResponse executeResponseWithError =
        ExecuteResponse.newBuilder()
            .setStatus(
                com.google.rpc.Status.newBuilder().setCode(Code.INTERNAL.getNumber()).build())
            .build();
    Operation operationWithExecuteError =
        Operation.newBuilder()
            .setName(opName)
            .setDone(true)
            .setResponse(Any.pack(executeResponseWithError))
            .build();
    Operation unfinishedOperation = Operation.newBuilder().setName(opName).build();
    Operation opSuccess =
        Operation.newBuilder()
            .setName(opName)
            .setDone(true)
            .setResponse(Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build()))
            .build();

    ExecutionImplBase mockExecutionImpl = spy(ExecutionImplBase.class);
    // Flow of this test:
    // - call execute, get retriable gRPC error
    // - retry: call execute, get retriable Operation error
    // - retry: call execute, get an Operation, then a retriable gRPC error
    // - retry: call waitExecute, get a retriable gRPC error
    // - retry: call waitExecute, get retriable Operation error
    // - retry: call execute, get successful operation, ignore further errors.
    doAnswer(answerWith(null, Status.UNAVAILABLE))
        .doAnswer(answerWith(operationWithExecuteError, Status.OK))
        .doAnswer(answerWith(unfinishedOperation, Status.UNAVAILABLE))
        .doAnswer(answerWith(opSuccess, Status.UNAVAILABLE)) // last status should be ignored.
        .when(mockExecutionImpl)
        .execute(
            ArgumentMatchers.<ExecuteRequest>any(),
            ArgumentMatchers.<StreamObserver<Operation>>any());
    doAnswer(answerWith(null, Status.UNAVAILABLE))
        .doAnswer(answerWith(operationWithExecuteError, Status.OK))
        .when(mockExecutionImpl)
        .waitExecution(
            ArgumentMatchers.<WaitExecutionRequest>any(),
            ArgumentMatchers.<StreamObserver<Operation>>any());
    serviceRegistry.addService(mockExecutionImpl);

    serviceRegistry.addService(
        new ContentAddressableStorageImplBase() {
          private int numErrors = 4;

          @Override
          public void findMissingBlobs(
              FindMissingBlobsRequest request,
              StreamObserver<FindMissingBlobsResponse> responseObserver) {
            if (numErrors-- > 0) {
              responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
              return;
            }

            final Set<Digest> requested = ImmutableSet.copyOf(request.getBlobDigestsList());
            assertThat(requested).contains(cmdDigest);
            assertThat(requested).contains(inputDigest);
            responseObserver.onNext(
                FindMissingBlobsResponse.newBuilder().addMissingBlobDigests(inputDigest).build());
            responseObserver.onCompleted();
          }
        });

    ByteStreamImplBase mockByteStreamImpl = spy(ByteStreamImplBase.class);
    doAnswer(blobWriteAnswerError()) // Error on the input file.
        .doAnswer(blobWriteAnswerError()) // Error on the input file again.
        .doAnswer(blobWriteAnswer("xyz".getBytes(UTF_8))) // Upload input file successfully.
        .when(mockByteStreamImpl)
        .write(any());
    doAnswer(
            answerVoid(
                (QueryWriteStatusRequest request,
                    StreamObserver<QueryWriteStatusResponse> responseObserver) -> {
                  responseObserver.onNext(
                      QueryWriteStatusResponse.newBuilder()
                          .setCommittedSize(0)
                          .setComplete(false)
                          .build());
                  responseObserver.onCompleted();
                }))
        .when(mockByteStreamImpl)
        .queryWriteStatus(any(), any());
    doAnswer(
            invocationOnMock -> {
              @SuppressWarnings("unchecked")
              StreamObserver<ReadResponse> responseObserver =
                  (StreamObserver<ReadResponse>) invocationOnMock.getArguments()[1];
              responseObserver.onError(Status.INTERNAL.asRuntimeException()); // Will retry.
              return null;
            })
        .doAnswer(
            invocationOnMock -> {
              @SuppressWarnings("unchecked")
              StreamObserver<ReadResponse> responseObserver =
                  (StreamObserver<ReadResponse>) invocationOnMock.getArguments()[1];
              responseObserver.onNext(
                  ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("bla")).build());
              responseObserver.onCompleted();
              return null;
            })
        .when(mockByteStreamImpl)
        .read(
            ArgumentMatchers.<ReadRequest>any(),
            ArgumentMatchers.<StreamObserver<ReadResponse>>any());
    serviceRegistry.addService(mockByteStreamImpl);

    FakeSpawnExecutionContext context = getSpawnContext(simpleSpawn);
    SpawnResult result = client.exec(simpleSpawn, context);

    assertThat(result.setupSuccess()).isTrue();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.isCacheHit()).isFalse();
    assertThat(outErr.outAsLatin1()).isEqualTo("stdout");
    assertThat(outErr.errAsLatin1()).isEqualTo("stderr");
    verify(mockExecutionImpl, Mockito.times(4))
        .execute(
            ArgumentMatchers.<ExecuteRequest>any(),
            ArgumentMatchers.<StreamObserver<Operation>>any());
    verify(mockExecutionImpl, Mockito.times(2))
        .waitExecution(
            ArgumentMatchers.<WaitExecutionRequest>any(),
            ArgumentMatchers.<StreamObserver<Operation>>any());
    verify(mockByteStreamImpl, Mockito.times(2))
        .read(
            ArgumentMatchers.<ReadRequest>any(),
            ArgumentMatchers.<StreamObserver<ReadResponse>>any());
    verify(mockByteStreamImpl, Mockito.times(3))
        .write(ArgumentMatchers.<StreamObserver<WriteResponse>>any());
  }

  @Test
  public void remotelyExecuteRetriesWaitResult() throws Exception {
    when(remoteOutputChecker.shouldDownloadOutput(ArgumentMatchers.<PathFragment>any()))
        .thenReturn(true);

    // This test's flow is similar to the previous, except the result
    // will eventually be returned by the waitExecute function.
    serviceRegistry.addService(
        new ActionCacheImplBase() {
          @Override
          public void getActionResult(
              GetActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
            responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
          }
        });
    final Digest resultDigest = DIGEST_UTIL.compute("bla".getBytes(UTF_8));
    final ActionResult actionResult =
        ActionResult.newBuilder()
            .setStdoutRaw(ByteString.copyFromUtf8("stdout"))
            .setStderrRaw(ByteString.copyFromUtf8("stderr"))
            .addOutputFiles(OutputFile.newBuilder().setPath("foo").setDigest(resultDigest).build())
            .build();
    final String opName = "operations/xyz";

    Operation unfinishedOperation = Operation.newBuilder().setName(opName).build();
    Operation opSuccess =
        Operation.newBuilder()
            .setName(opName)
            .setDone(true)
            .setResponse(Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build()))
            .build();

    ExecutionImplBase mockExecutionImpl = spy(ExecutionImplBase.class);
    // Flow of this test:
    // - call execute, get an Operation, then a retriable gRPC error
    // - retry: call waitExecute, get NOT_FOUND (operation lost)
    // - retry: call execute, get NOT_FOUND (operation lost)
    // - retry: call execute, get an Operation, then a retriable gRPC error
    // - retry: call waitExecute, get successful operation, ignore further errors.
    doAnswer(answerWith(unfinishedOperation, Status.UNAVAILABLE))
        .doAnswer(answerWith(unfinishedOperation, Status.NOT_FOUND))
        .doAnswer(answerWith(unfinishedOperation, Status.UNAVAILABLE))
        .when(mockExecutionImpl)
        .execute(any(), any());
    doAnswer(answerWith(unfinishedOperation, Status.NOT_FOUND))
        .doAnswer(answerWith(opSuccess, Status.UNAVAILABLE)) // This error is ignored.
        .when(mockExecutionImpl)
        .waitExecution(any(), any());
    serviceRegistry.addService(mockExecutionImpl);

    serviceRegistry.addService(
        new ContentAddressableStorageImplBase() {

          @Override
          public void findMissingBlobs(
              FindMissingBlobsRequest request,
              StreamObserver<FindMissingBlobsResponse> responseObserver) {
            final Set<Digest> requested = ImmutableSet.copyOf(request.getBlobDigestsList());
            assertThat(requested).contains(cmdDigest);
            assertThat(requested).contains(inputDigest);
            responseObserver.onNext(
                FindMissingBlobsResponse.newBuilder().addMissingBlobDigests(inputDigest).build());
            responseObserver.onCompleted();
          }
        });

    ByteStreamImplBase mockByteStreamImpl = spy(ByteStreamImplBase.class);
    doAnswer(blobWriteAnswer("xyz".getBytes(UTF_8))).when(mockByteStreamImpl).write(any());
    doAnswer(
            invocationOnMock -> {
              @SuppressWarnings("unchecked")
              StreamObserver<ReadResponse> responseObserver =
                  (StreamObserver<ReadResponse>) invocationOnMock.getArguments()[1];
              responseObserver.onNext(
                  ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("bla")).build());
              responseObserver.onCompleted();
              return null;
            })
        .when(mockByteStreamImpl)
        .read(
            ArgumentMatchers.<ReadRequest>any(),
            ArgumentMatchers.<StreamObserver<ReadResponse>>any());
    serviceRegistry.addService(mockByteStreamImpl);

    FakeSpawnExecutionContext context = getSpawnContext(simpleSpawn);
    SpawnResult result = client.exec(simpleSpawn, context);

    assertThat(result.setupSuccess()).isTrue();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.isCacheHit()).isFalse();
    assertThat(outErr.outAsLatin1()).isEqualTo("stdout");
    assertThat(outErr.errAsLatin1()).isEqualTo("stderr");
    verify(mockExecutionImpl, Mockito.times(3))
        .execute(
            ArgumentMatchers.<ExecuteRequest>any(),
            ArgumentMatchers.<StreamObserver<Operation>>any());
    verify(mockExecutionImpl, Mockito.times(2))
        .waitExecution(
            ArgumentMatchers.<WaitExecutionRequest>any(),
            ArgumentMatchers.<StreamObserver<Operation>>any());
    verify(mockByteStreamImpl)
        .read(
            ArgumentMatchers.<ReadRequest>any(),
            ArgumentMatchers.<StreamObserver<ReadResponse>>any());
    verify(mockByteStreamImpl, Mockito.times(1))
        .write(ArgumentMatchers.<StreamObserver<WriteResponse>>any());
  }

  @Test
  public void passUnavailableErrorWithStackTrace() throws Exception {
    serviceRegistry.addService(
        new ActionCacheImplBase() {
          @Override
          public void getActionResult(
              GetActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
            responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
          }
        });

    FakeSpawnExecutionContext context = getSpawnContext(simpleSpawn);
    SpawnResult result = client.exec(simpleSpawn, context);

    assertThat(result.status()).isEqualTo(SpawnResult.Status.EXECUTION_FAILED_CATASTROPHICALLY);
    // Ensure we also got back the stack trace due to verboseFailures=true
    assertThat(result.getFailureMessage()).contains("com.google.devtools.build.lib.remote");
  }

  @Test
  public void passInternalErrorWithStackTrace() throws Exception {
    serviceRegistry.addService(
        new ActionCacheImplBase() {
          @Override
          public void getActionResult(
              GetActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
            responseObserver.onError(Status.INTERNAL.withDescription("whoa").asRuntimeException());
          }
        });

    FakeSpawnExecutionContext context = getSpawnContext(simpleSpawn);
    SpawnResult result = client.exec(simpleSpawn, context);

    assertThat(result.getFailureMessage()).contains("whoa"); // Error details.
    // Ensure we also got back the stack trace due to verboseFailures=true
    assertThat(result.getFailureMessage()).contains("com.google.devtools.build.lib.remote");
  }

  @Test
  public void passCacheMissErrorWithStackTrace() throws Exception {
    serviceRegistry.addService(
        new ActionCacheImplBase() {
          @Override
          public void getActionResult(
              GetActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
            responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
          }
        });
    Digest stdOutDigest = DIGEST_UTIL.computeAsUtf8("bla");
    final ActionResult actionResult =
        ActionResult.newBuilder()
            .addOutputFiles(DUMMY_OUTPUT)
            .setStdoutDigest(stdOutDigest)
            .build();
    serviceRegistry.addService(
        new ExecutionImplBase() {
          @Override
          public void execute(ExecuteRequest request, StreamObserver<Operation> responseObserver) {
            responseObserver.onNext(
                Operation.newBuilder()
                    .setDone(true)
                    .setResponse(
                        Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build()))
                    .build());
            responseObserver.onCompleted();
          }
        });
    serviceRegistry.addService(
        new ContentAddressableStorageImplBase() {
          @Override
          public void findMissingBlobs(
              FindMissingBlobsRequest request,
              StreamObserver<FindMissingBlobsResponse> responseObserver) {
            responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance());
            responseObserver.onCompleted();
          }
        });
    String stdOutResourceName =
        getResourceName(
            remoteOptions.remoteInstanceName, stdOutDigest, false, DigestFunction.Value.SHA256);
    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
            assertThat(request.getResourceName()).isEqualTo(stdOutResourceName);
            responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
          }
        });

    FakeSpawnExecutionContext context = getSpawnContext(simpleSpawn);
    SpawnResult result = client.exec(simpleSpawn, context);

    assertThat(result.status()).isEqualTo(SpawnResult.Status.REMOTE_CACHE_FAILED);
    assertThat(result.getFailureMessage()).contains(DigestUtil.toString(stdOutDigest));
    // Ensure we also got back the stack trace.
    assertThat(result.getFailureMessage())
        .contains("RemoteSpawnRunnerWithGrpcRemoteExecutorTest.passCacheMissErrorWithStackTrace");
  }

  @Test
  public void passRepeatedOrphanedCacheMissErrorWithStackTrace() throws Exception {
    final Digest stdOutDigest = DIGEST_UTIL.computeAsUtf8("bloo");
    final ActionResult actionResult =
        ActionResult.newBuilder()
            .addOutputFiles(DUMMY_OUTPUT)
            .setStdoutDigest(stdOutDigest)
            .build();
    serviceRegistry.addService(
        new ActionCacheImplBase() {
          @Override
          public void getActionResult(
              GetActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
            responseObserver.onNext(actionResult);
            responseObserver.onCompleted();
          }
        });
    serviceRegistry.addService(
        new ExecutionImplBase() {
          @Override
          public void execute(ExecuteRequest request, StreamObserver<Operation> responseObserver) {
            responseObserver.onNext(
                Operation.newBuilder()
                    .setDone(true)
                    .setResponse(
                        Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build()))
                    .build());
            responseObserver.onCompleted();
          }
        });
    serviceRegistry.addService(
        new ContentAddressableStorageImplBase() {
          @Override
          public void findMissingBlobs(
              FindMissingBlobsRequest request,
              StreamObserver<FindMissingBlobsResponse> responseObserver) {
            responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance());
            responseObserver.onCompleted();
          }
        });
    String stdOutResourceName =
        getResourceName(
            remoteOptions.remoteInstanceName, stdOutDigest, false, DigestFunction.Value.SHA256);
    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
            assertThat(request.getResourceName()).isEqualTo(stdOutResourceName);
            responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
          }
        });

    FakeSpawnExecutionContext context = getSpawnContext(simpleSpawn);
    SpawnResult result = client.exec(simpleSpawn, context);

    assertThat(result.status()).isEqualTo(SpawnResult.Status.REMOTE_CACHE_FAILED);
    assertThat(result.getFailureMessage()).contains(DigestUtil.toString(stdOutDigest));
    // Ensure we also got back the stack trace because verboseFailures=true
    assertThat(result.getFailureMessage())
        .contains("passRepeatedOrphanedCacheMissErrorWithStackTrace");
  }

  @Test
  public void remotelyReExecuteOrphanedCachedActions() throws Exception {
    final Digest stdOutDigest = DIGEST_UTIL.computeAsUtf8("stdout");
    final ActionResult actionResult =
        ActionResult.newBuilder()
            .addOutputFiles(DUMMY_OUTPUT)
            .setStdoutDigest(stdOutDigest)
            .build();
    serviceRegistry.addService(
        new ActionCacheImplBase() {
          @Override
          public void getActionResult(
              GetActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
            responseObserver.onNext(actionResult);
            responseObserver.onCompleted();
          }
        });
    serviceRegistry.addService(
        new ByteStreamImplBase() {
          private boolean first = true;

          @Override
          public void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
            // First read is a cache miss, next read succeeds.
            if (first) {
              first = false;
              responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
            } else {
              responseObserver.onNext(
                  ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("stdout")).build());
              responseObserver.onCompleted();
            }
          }

          @Override
          public StreamObserver<WriteRequest> write(
              StreamObserver<WriteResponse> responseObserver) {
            return new StreamObserver<WriteRequest>() {
              @Override
              public void onNext(WriteRequest request) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }

              @Override
              public void onError(Throwable t) {
                fail("An error occurred: " + t);
              }
            };
          }
        });
    AtomicInteger numExecuteCalls = new AtomicInteger();
    serviceRegistry.addService(
        new ExecutionImplBase() {
          @Override
          public void execute(ExecuteRequest request, StreamObserver<Operation> responseObserver) {
            numExecuteCalls.incrementAndGet();
            assertThat(request.getSkipCacheLookup()).isTrue(); // Action will be re-executed.
            responseObserver.onNext(
                Operation.newBuilder()
                    .setDone(true)
                    .setResponse(
                        Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build()))
                    .build());
            responseObserver.onCompleted();
          }
        });
    serviceRegistry.addService(
        new ContentAddressableStorageImplBase() {
          @Override
          public void findMissingBlobs(
              FindMissingBlobsRequest request,
              StreamObserver<FindMissingBlobsResponse> responseObserver) {
            // Nothing is missing.
            responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance());
            responseObserver.onCompleted();
          }
        });

    FakeSpawnExecutionContext context = getSpawnContext(simpleSpawn);
    SpawnResult result = client.exec(simpleSpawn, context);

    assertThat(result.setupSuccess()).isTrue();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.isCacheHit()).isFalse();
    assertThat(outErr.outAsLatin1()).isEqualTo("stdout");
    assertThat(numExecuteCalls.get()).isEqualTo(1);
  }

  @Test
  public void remotelyReExecuteOrphanedDirectoryCachedActions() throws Exception {
    final ActionResult actionResult =
        ActionResult.newBuilder().addOutputDirectories(DUMMY_OUTPUT_DIRECTORY).build();
    serviceRegistry.addService(
        new ActionCacheImplBase() {
          @Override
          public void getActionResult(
              GetActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
            responseObserver.onNext(actionResult);
            responseObserver.onCompleted();
          }
        });
    String dummyTreeResourceName =
        getResourceName(
            remoteOptions.remoteInstanceName,
            DUMMY_OUTPUT_DIRECTORY.getTreeDigest(),
            false,
            DigestFunction.Value.SHA256);
    serviceRegistry.addService(
        new ByteStreamImplBase() {
          private boolean first = true;

          @Override
          public void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
            String resourceName = request.getResourceName();
            if (resourceName.equals(dummyTreeResourceName)) {
              // First read is a cache miss, next read succeeds.
              if (first) {
                first = false;
                responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
              } else {
                responseObserver.onNext(
                    ReadResponse.newBuilder().setData(DUMMY_OUTPUT_TREE.toByteString()).build());
                responseObserver.onCompleted();
              }
            } else {
              responseObserver.onNext(ReadResponse.getDefaultInstance());
            }
          }

          @Override
          public StreamObserver<WriteRequest> write(
              StreamObserver<WriteResponse> responseObserver) {
            return new StreamObserver<WriteRequest>() {
              @Override
              public void onNext(WriteRequest request) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }

              @Override
              public void onError(Throwable t) {
                fail("An error occurred: " + t);
              }
            };
          }
        });
    AtomicInteger numExecuteCalls = new AtomicInteger();
    serviceRegistry.addService(
        new ExecutionImplBase() {
          @Override
          public void execute(ExecuteRequest request, StreamObserver<Operation> responseObserver) {
            numExecuteCalls.incrementAndGet();
            assertThat(request.getSkipCacheLookup()).isTrue(); // Action will be re-executed.
            responseObserver.onNext(
                Operation.newBuilder()
                    .setDone(true)
                    .setResponse(
                        Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build()))
                    .build());
            responseObserver.onCompleted();
          }
        });
    serviceRegistry.addService(
        new ContentAddressableStorageImplBase() {
          @Override
          public void findMissingBlobs(
              FindMissingBlobsRequest request,
              StreamObserver<FindMissingBlobsResponse> responseObserver) {
            // Nothing is missing.
            responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance());
            responseObserver.onCompleted();
          }
        });

    FakeSpawnExecutionContext context = getSpawnContext(simpleSpawn);
    SpawnResult result = client.exec(simpleSpawn, context);

    assertThat(result.setupSuccess()).isTrue();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.isCacheHit()).isFalse();
    assertThat(numExecuteCalls.get()).isEqualTo(1);
  }

  @Test
  public void retryUploadAndExecuteOnMissingInputs() throws Exception {
    serviceRegistry.addService(
        new ActionCacheImplBase() {
          @Override
          public void getActionResult(
              GetActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
            responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
          }
        });
    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
            responseObserver.onNext(
                ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("bla")).build());
            responseObserver.onCompleted();
          }

          @Override
          public StreamObserver<WriteRequest> write(
              StreamObserver<WriteResponse> responseObserver) {
            return new StreamObserver<WriteRequest>() {
              @Override
              public void onNext(WriteRequest request) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }

              @Override
              public void onError(Throwable t) {
                fail("An error occurred: " + t);
              }
            };
          }
        });
    final ActionResult actionResult =
        ActionResult.newBuilder().addOutputFiles(DUMMY_OUTPUT).build();
    AtomicInteger numExecuteCalls = new AtomicInteger();
    serviceRegistry.addService(
        new ExecutionImplBase() {
          @Override
          public void execute(ExecuteRequest request, StreamObserver<Operation> responseObserver) {
            if (numExecuteCalls.incrementAndGet() == 1) {
              // Missing input.
              Violation viol = Violation.newBuilder().setType("MISSING").build();
              com.google.rpc.Status status =
                  com.google.rpc.Status.newBuilder()
                      .setCode(Code.FAILED_PRECONDITION.getNumber())
                      .addDetails(
                          Any.pack(PreconditionFailure.newBuilder().addViolations(viol).build()))
                      .build();
              responseObserver.onNext(
                  Operation.newBuilder()
                      .setDone(true)
                      .setResponse(Any.pack(ExecuteResponse.newBuilder().setStatus(status).build()))
                      .build());
              responseObserver.onCompleted();
            } else {
              assertThat(request.getSkipCacheLookup()).isFalse();
              responseObserver.onNext(
                  Operation.newBuilder()
                      .setDone(true)
                      .setResponse(
                          Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build()))
                      .build());
              responseObserver.onCompleted();
            }
          }
        });
    AtomicInteger numCacheUploads = new AtomicInteger();
    serviceRegistry.addService(
        new ContentAddressableStorageImplBase() {
          @Override
          public void findMissingBlobs(
              FindMissingBlobsRequest request,
              StreamObserver<FindMissingBlobsResponse> responseObserver) {
            numCacheUploads.incrementAndGet();
            // Nothing is missing.
            responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance());
            responseObserver.onCompleted();
          }
        });

    FakeSpawnExecutionContext context = getSpawnContext(simpleSpawn);
    SpawnResult result = client.exec(simpleSpawn, context);

    assertThat(result.setupSuccess()).isTrue();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.isCacheHit()).isFalse();
    assertThat(numCacheUploads.get()).isEqualTo(2);
    assertThat(numExecuteCalls.get()).isEqualTo(2);
  }

  @Test
  public void execWaitsOnUnfinishedCompletion() throws Exception {
    serviceRegistry.addService(
        new ActionCacheImplBase() {
          @Override
          public void getActionResult(
              GetActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
            responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
          }
        });

    final String opName = "operations/xyz";
    final Digest resultDigest = DIGEST_UTIL.compute("bla".getBytes(UTF_8));
    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
            responseObserver.onNext(
                ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("bla")).build());
            responseObserver.onCompleted();
          }
        });
    final ActionResult actionResult =
        ActionResult.newBuilder()
            .setStdoutRaw(ByteString.copyFromUtf8("stdout"))
            .setStderrRaw(ByteString.copyFromUtf8("stderr"))
            .addOutputFiles(OutputFile.newBuilder().setPath("foo").setDigest(resultDigest).build())
            .build();
    final Operation unfinishedOperation = Operation.newBuilder().setName(opName).build();
    final Operation completeOperation =
        unfinishedOperation.toBuilder()
            .setDone(true)
            .setResponse(Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build()))
            .build();
    final WaitExecutionRequest waitExecutionRequest =
        WaitExecutionRequest.newBuilder().setName(opName).build();
    ExecutionImplBase mockExecutionImpl = spy(ExecutionImplBase.class);
    // Flow of this test:
    // - call execute, get an unfinished Operation, then the stream completes
    // - call waitExecute, get an unfinished Operation, then the stream completes
    // - call waitExecute, get a finished Operation
    doAnswer(answerWith(unfinishedOperation, Status.OK))
        .when(mockExecutionImpl)
        .execute(
            ArgumentMatchers.<ExecuteRequest>any(),
            ArgumentMatchers.<StreamObserver<Operation>>any());
    doAnswer(answerWith(unfinishedOperation, Status.OK))
        .doAnswer(answerWith(completeOperation, Status.OK))
        .when(mockExecutionImpl)
        .waitExecution(
            ArgumentMatchers.eq(waitExecutionRequest),
            ArgumentMatchers.<StreamObserver<Operation>>any());
    serviceRegistry.addService(mockExecutionImpl);

    serviceRegistry.addService(
        new ContentAddressableStorageImplBase() {

          @Override
          public void findMissingBlobs(
              FindMissingBlobsRequest request,
              StreamObserver<FindMissingBlobsResponse> responseObserver) {
            responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance());
            responseObserver.onCompleted();
          }
        });

    FakeSpawnExecutionContext context = getSpawnContext(simpleSpawn);
    SpawnResult result = client.exec(simpleSpawn, context);

    assertThat(result.setupSuccess()).isTrue();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.isCacheHit()).isFalse();
    verify(mockExecutionImpl, Mockito.times(1))
        .execute(
            ArgumentMatchers.<ExecuteRequest>any(),
            ArgumentMatchers.<StreamObserver<Operation>>any());
    verify(mockExecutionImpl, Mockito.times(2))
        .waitExecution(
            Mockito.eq(waitExecutionRequest), ArgumentMatchers.<StreamObserver<Operation>>any());
  }

  private FakeSpawnExecutionContext getSpawnContext(Spawn spawn) {
    var actionInputFetcher =
        new RemoteActionInputFetcher(
            new Reporter(new EventBus()),
            "none",
            "none",
            remoteCache,
            execRoot,
            tempPathGenerator,
            remoteOutputChecker,
            ActionOutputDirectoryHelper.createForTesting(),
            OutputPermissions.READONLY);

    var actionFileSystem =
        new RemoteActionFileSystem(
            fs,
            execRoot.asFragment(),
            artifactRoot.getRoot().asPath().relativeTo(execRoot).getPathString(),
            new ActionInputMap(0),
            ImmutableList.of(),
            StaticInputMetadataProvider.empty(),
            actionInputFetcher);

    return new FakeSpawnExecutionContext(
        spawn, fakeFileCache, execRoot, outErr, ImmutableClassToInstanceMap.of(), actionFileSystem);
  }
}
