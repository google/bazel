// Copyright 2021 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.worker;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.ExecutionRequirements.SUPPORTS_MULTIPLEX_SANDBOXING;
import static com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat.PROTO;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerTestUtils.FakeSubprocess;
import java.io.IOException;
import java.io.PipedInputStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for WorkerProxy */
@RunWith(JUnit4.class)
public class SandboxedWorkerProxyTest {
  final FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
  private Path globalExecRoot;
  private Path workerBaseDir;
  private Path globalOutputBase;

  @Before
  public void setUp() throws IOException {
    Path testRoot = fs.getPath(com.google.devtools.build.lib.testutil.TestUtils.tmpDir());

    globalOutputBase = testRoot.getChild("outputbase");
    globalExecRoot = globalOutputBase.getChild("execroot");
    globalExecRoot.createDirectoryAndParents();

    workerBaseDir = testRoot.getRelative("bazel-workers");
    workerBaseDir.createDirectoryAndParents();
  }

  @Test
  public void prepareExecution_createsFilesInSandbox() throws IOException, InterruptedException {
    SandboxedWorkerProxy proxy = createSandboxedWorkerProxy();
    Path workDir = workerBaseDir.getChild("Mnem-multiplex-worker-workdir").getChild("execroot");
    Path sandboxDir =
        workDir
            .getChild("__sandbox")
            .getChild(Integer.toString(proxy.getWorkerId()))
            .getChild("execroot");
    SandboxHelper sandboxHelper =
        new SandboxHelper(globalExecRoot, workDir)
            .addAndCreateInputFile("anInputFile", "anInputFile", "Just stuff")
            // Worker files are expected to also be inputs.
            .addInputFile("worker.sh", "worker.sh")
            .addOutput("very/output.txt")
            .addAndCreateWorkerFile("worker.sh", "#!/bin/bash");

    PipedInputStream serverInputStream = new PipedInputStream();
    proxy.workerMultiplexer.setProcessFactory(params -> new FakeSubprocess(serverInputStream));

    proxy.prepareExecution(
        sandboxHelper.getSandboxInputs(),
        sandboxHelper.getSandboxOutputs(),
        sandboxHelper.getWorkerFiles());

    assertThat(workDir.isDirectory()).isTrue();
    assertThat(workDir.getChild("worker.sh").exists()).isTrue();
    assertThat(workDir.getChild("worker.sh").isSymbolicLink()).isTrue();
    assertThat(sandboxDir.isDirectory()).isTrue();
    assertThat(sandboxDir.getChild("anInputFile").exists()).isTrue();
    assertThat(sandboxDir.getChild("anInputFile").isSymbolicLink()).isTrue();
    assertThat(sandboxDir.getChild("very").exists()).isTrue();
    assertThat(sandboxDir.getChild("very").isDirectory()).isTrue();
  }

  @Test
  public void putRequest_setsSandboxDir() throws IOException, InterruptedException {
    SandboxedWorkerProxy worker = createFakedSandboxedWorkerProxy();
    Path workDir = workerBaseDir.getChild("Mnem-multiplex-worker-workdir").getChild("execroot");
    SandboxHelper sandboxHelper =
        new SandboxHelper(globalExecRoot, workDir)
            .addAndCreateInputFile("anInputFile", "anInputFile", "Just stuff")
            .addOutput("very/output.txt")
            .addAndCreateWorkerFile("worker.sh", "#!/bin/bash");
    worker.prepareExecution(
        sandboxHelper.getSandboxInputs(),
        sandboxHelper.getSandboxOutputs(),
        sandboxHelper.getWorkerFiles());
    worker.putRequest(WorkRequest.newBuilder().setRequestId(2).build());
    assertThat(worker.workerMultiplexer.pendingRequests).isNotEmpty();
    WorkRequest actualRequest = worker.workerMultiplexer.pendingRequests.take();
    assertThat(actualRequest.getRequestId()).isEqualTo(2);
    assertThat(actualRequest.getSandboxDir())
        .isEqualTo("__sandbox/" + worker.getWorkerId() + "/execroot");
  }

  @Test
  public void finishExecution_copiesOutputs() throws IOException, InterruptedException {
    SandboxedWorkerProxy worker = createFakedSandboxedWorkerProxy();
    Path workDir = workerBaseDir.getChild("Mnem-multiplex-worker-workdir").getChild("execroot");
    SandboxHelper sandboxHelper =
        new SandboxHelper(globalExecRoot, workDir)
            .addAndCreateInputFile("anInputFile", "anInputFile", "Just stuff")
            .addOutput("very/output.txt")
            .addOutput("rootFile")
            .addAndCreateWorkerFile("worker.sh", "#!/bin/bash");
    worker.prepareExecution(
        sandboxHelper.getSandboxInputs(),
        sandboxHelper.getSandboxOutputs(),
        sandboxHelper.getWorkerFiles());
    worker.putRequest(WorkRequest.newBuilder().setRequestId(2).build());
    WorkRequest actualRequest = worker.workerMultiplexer.pendingRequests.take();
    String requestSandboxSubdir = actualRequest.getSandboxDir();

    // Pretend to do work.
    sandboxHelper.createExecRootFile(
        Joiner.on("/").join(requestSandboxSubdir, "very/output.txt"), "some output");
    sandboxHelper.createExecRootFile("very/output.txt", "some wrongly placed output");
    sandboxHelper.createExecRootFile(
        Joiner.on("/").join(requestSandboxSubdir, "rootFile"), "some output in root");
    sandboxHelper.createExecRootFile(
        Joiner.on("/").join(requestSandboxSubdir, "randomFile"), "some randomOutput");

    worker.finishExecution(globalExecRoot, sandboxHelper.getSandboxOutputs());

    assertThat(globalExecRoot.getChild("randomFile").exists()).isFalse();
    assertThat(FileSystemUtils.readContentToString(globalExecRoot.getChild("rootFile")))
        .isEqualTo("some output in root");
    assertThat(
            FileSystemUtils.readContentToString(
                globalExecRoot.getChild("very").getChild("output.txt")))
        .isEqualTo("some output");
  }

  private SandboxedWorkerProxy createSandboxedWorkerProxy() throws IOException {
    ImmutableMap.Builder<String, String> req = WorkerTestUtils.execRequirementsBuilder("Mnem");
    req.put(SUPPORTS_MULTIPLEX_SANDBOXING, "1");
    Spawn spawn = WorkerTestUtils.createSpawn(req.buildOrThrow());

    WorkerOptions options = new WorkerOptions();
    options.workerMultiplex = true;
    options.multiplexSandboxing = true;

    WorkerKey key =
        WorkerTestUtils.createWorkerKeyFromOptions(
            PROTO, globalOutputBase, options, true, spawn, "worker.sh");
    WorkerFactory factory = new WorkerFactory(workerBaseDir, options);
    return (SandboxedWorkerProxy) factory.create(key);
  }

  private SandboxedWorkerProxy createFakedSandboxedWorkerProxy() throws IOException {
    ImmutableMap.Builder<String, String> req = WorkerTestUtils.execRequirementsBuilder("Mnem");
    req.put(SUPPORTS_MULTIPLEX_SANDBOXING, "1");
    Spawn spawn = WorkerTestUtils.createSpawn(req.buildOrThrow());

    WorkerOptions options = new WorkerOptions();
    options.workerMultiplex = true;
    options.multiplexSandboxing = true;

    WorkerKey key =
        WorkerTestUtils.createWorkerKeyFromOptions(
            PROTO, globalOutputBase, options, true, spawn, "worker.sh");
    WorkerMultiplexerManager.injectForTesting(
        key,
        new WorkerMultiplexer(globalExecRoot.getChild("testWorker.log"), key) {
          @Override
          public synchronized void createProcess(Path workDir) throws IOException {
            PipedInputStream serverInputStream = new PipedInputStream();
            super.process = new FakeSubprocess(serverInputStream);
          }
        });
    WorkerFactory factory = new WorkerFactory(workerBaseDir, options);
    return (SandboxedWorkerProxy) factory.create(key);
  }
}
