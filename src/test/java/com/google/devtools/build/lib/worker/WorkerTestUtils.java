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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.SimpleSpawn;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.shell.Subprocess;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Utilities that come in handy when unit-testing the worker code. */
public class WorkerTestUtils {

  private WorkerTestUtils() {}

  /** A helper method to create a fake Spawn with the given execution info. */
  static Spawn createSpawn(ImmutableMap<String, String> executionInfo) {
    return createSpawn(ImmutableList.of(), executionInfo);
  }

  static Spawn createSpawn(
      ImmutableList<String> arguments, ImmutableMap<String, String> executionInfo) {
    return new SimpleSpawn(
        new ActionsTestUtil.NullAction(),
        arguments,
        /* environment= */ ImmutableMap.of(),
        executionInfo,
        /* inputs= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        /* outputs= */ ImmutableSet.of(),
        ResourceSet.ZERO);
  }

  /** A helper method to create a WorkerKey through WorkerParser. */
  static WorkerKey createWorkerKey(
      WorkerProtocolFormat protocolFormat,
      FileSystem fs,
      String mnemonic,
      boolean multiplex,
      boolean sandboxed,
      boolean dynamic,
      String... args) {
    WorkerOptions workerOptions = new WorkerOptions();
    workerOptions.workerMultiplex = multiplex;
    workerOptions.workerSandboxing = sandboxed;

    return createWorkerKeyFromOptions(
        protocolFormat,
        fs.getPath("/outputbase"),
        workerOptions,
        dynamic,
        createSpawn(execRequirementsBuilder(mnemonic).buildOrThrow()),
        args);
  }

  static WorkerKey createWorkerKey(
      FileSystem fileSystem, String mnemonic, boolean proxied, String... args) {
    return createWorkerKey(
        WorkerProtocolFormat.PROTO,
        fileSystem,
        mnemonic,
        proxied,
        /* sandboxed= */ false,
        /* dynamic= */ false,
        args);
  }

  static WorkerKey createWorkerKey(WorkerProtocolFormat protocolFormat, FileSystem fs) {
    return createWorkerKey(protocolFormat, fs, false);
  }

  static WorkerKey createWorkerKey(
      String mnemonic, FileSystem fs, boolean multiplex, boolean sandboxed) {
    return createWorkerKey(
        WorkerProtocolFormat.PROTO, fs, mnemonic, multiplex, sandboxed, /* dynamic= */ false);
  }

  static WorkerKey createWorkerKey(String mnemonic, FileSystem fs, boolean sandboxed) {
    return createWorkerKey(
        WorkerProtocolFormat.PROTO,
        fs,
        mnemonic,
        /* multiplex= */ false,
        sandboxed,
        /* dynamic= */ false);
  }

  static WorkerKey createWorkerKey(String mnemonic, FileSystem fs) {
    return createWorkerKey(
        WorkerProtocolFormat.PROTO,
        fs,
        mnemonic,
        /* multiplex= */ false,
        /* sandboxed= */ false,
        /* dynamic= */ false);
  }

  static WorkerKey createWorkerKey(
      WorkerProtocolFormat protocolFormat, FileSystem fs, boolean dynamic) {
    return createWorkerKey(
        protocolFormat,
        fs,
        /* mnemonic= */ "dummy",
        /* multiplex= */ true,
        /* sandboxed= */ true,
        dynamic,
        /* args...= */ "arg1",
        "arg2",
        "arg3");
  }

  static WorkerKey createWorkerKey(
      FileSystem fs, boolean multiplex, boolean sandboxed, boolean dynamic) {
    return createWorkerKey(
        WorkerProtocolFormat.PROTO,
        fs,
        /* mnemonic= */ "dummy",
        multiplex,
        sandboxed,
        dynamic,
        /* args...= */ "arg1",
        "arg2",
        "arg3");
  }

  /**
   * Creates a worker key based on a set of options. The {@code extraRequirements} are added to the
   * {@link Spawn} execution info with the value "1". The "supports-workers" and
   * "supports-multiplex-workers" execution requirements are always set.
   *
   * @param outputBase Global (for the test) outputBase.
   */
  static WorkerKey createWorkerKeyWithRequirements(
      Path outputBase,
      WorkerOptions workerOptions,
      String mnemonic,
      boolean dynamic,
      String... extraRequirements) {
    ImmutableMap.Builder<String, String> builder = execRequirementsBuilder(mnemonic);
    for (String req : extraRequirements) {
      builder.put(req, "1");
    }
    Spawn spawn = createSpawn(builder.buildOrThrow());

    return WorkerParser.createWorkerKey(
        spawn,
        /* workerArgs= */ ImmutableList.of(),
        /* env= */ ImmutableMap.of("env1", "foo", "env2", "bar"),
        /* execRoot= */ outputBase.getChild("execroot"),
        /* workerFilesCombinedHash= */ HashCode.fromInt(0),
        /* workerFiles= */ ImmutableSortedMap.of(),
        workerOptions,
        dynamic,
        WorkerProtocolFormat.PROTO);
  }

  static ImmutableMap.Builder<String, String> execRequirementsBuilder(String mnemonic) {
    return ImmutableMap.<String, String>builder()
        .put(ExecutionRequirements.WORKER_KEY_MNEMONIC, mnemonic)
        .put(ExecutionRequirements.REQUIRES_WORKER_PROTOCOL, "proto")
        .put(ExecutionRequirements.SUPPORTS_WORKERS, "1")
        .put(ExecutionRequirements.SUPPORTS_MULTIPLEX_WORKERS, "1");
  }

  static WorkerKey createWorkerKeyFromOptions(
      WorkerProtocolFormat protocolFormat,
      Path outputBase,
      WorkerOptions workerOptions,
      boolean dynamic,
      Spawn spawn,
      String... args) {

    return WorkerParser.createWorkerKey(
        spawn,
        /* workerArgs= */ ImmutableList.copyOf(args),
        /* env= */ ImmutableMap.of("env1", "foo", "env2", "bar"),
        /* execRoot= */ outputBase.getChild("execroot"),
        /* workerFilesCombinedHash= */ HashCode.fromInt(0),
        /* workerFiles= */ ImmutableSortedMap.of(),
        workerOptions,
        dynamic,
        protocolFormat);
  }

  /** A worker that uses a fake subprocess for I/O. */
  static class TestWorker extends SingleplexWorker {
    private final FakeSubprocess fakeSubprocess;

    TestWorker(
        WorkerKey workerKey,
        int workerId,
        final Path workDir,
        Path logFile,
        FakeSubprocess fakeSubprocess,
        WorkerOptions options) {
      super(workerKey, workerId, workDir, logFile, options, null);
      this.fakeSubprocess = fakeSubprocess;
    }

    @Override
    protected Subprocess createProcess() {
      return fakeSubprocess;
    }

    FakeSubprocess getFakeSubprocess() {
      return fakeSubprocess;
    }
  }

  /**
   * The {@link Worker} object uses a {@link Subprocess} to interact with persistent worker
   * binaries. Since this test is strictly testing {@link Worker} and not any outside persistent
   * worker binaries, a {@link FakeSubprocess} instance is used to fake the {@link InputStream} and
   * {@link OutputStream} that normally write and read from a persistent worker.
   */
  static class FakeSubprocess implements Subprocess {
    private final InputStream inputStream;
    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    private final ByteArrayInputStream errStream = new ByteArrayInputStream(new byte[0]);
    private boolean wasDestroyed = false;

    /** Creates a fake Subprocess that writes {@code bytes} to its "stdout". */
    FakeSubprocess(byte[] bytes) throws IOException {
      inputStream = new ByteArrayInputStream(bytes);
    }

    FakeSubprocess(InputStream responseStream) throws IOException {
      this.inputStream = responseStream;
    }

    @Override
    public InputStream getInputStream() {
      return inputStream;
    }

    @Override
    public OutputStream getOutputStream() {
      return outputStream;
    }

    @Override
    public InputStream getErrorStream() {
      return errStream;
    }

    @Override
    public synchronized boolean destroy() {
      for (Closeable stream : new Closeable[] {inputStream, outputStream, errStream}) {
        try {
          stream.close();
        } catch (IOException e) {
          throw new IllegalStateException(e);
        }
      }

      wasDestroyed = true;
      return true;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public boolean finished() {
      return true;
    }

    @Override
    public boolean timedout() {
      return false;
    }

    @Override
    public void waitFor() throws InterruptedException {
      // Do nothing.
    }

    @Override
    public void close() {
      // Do nothing.
    }

    @Override
    public synchronized boolean isAlive() {
      return !wasDestroyed;
    }

    @Override
    public long getProcessId() {
      return 0;
    }
  }

  public static WorkerPool createTestWorkerPool(Worker worker) {
    return new WorkerPool() {
      @Override
      public int getMaxTotalPerKey(WorkerKey key) {
        return 1;
      }

      @Override
      public int getNumActive(WorkerKey key) {
        return 0;
      }

      @Override
      public boolean hasAvailableQuota(WorkerKey key) {
        return true;
      }

      @Override
      public ImmutableSet<Integer> evictWorkers(ImmutableSet<Integer> workerIdsToEvict)
          throws InterruptedException {
        return ImmutableSet.of();
      }

      @Override
      public ImmutableSet<Integer> getIdleWorkers() throws InterruptedException {
        return ImmutableSet.of();
      }

      @Override
      public Worker borrowWorker(WorkerKey key) throws IOException, InterruptedException {
        return worker;
      }

      @Override
      public void returnWorker(WorkerKey key, Worker worker) {}

      @Override
      public void invalidateWorker(Worker worker) throws InterruptedException {}

      @Override
      public void reset() {}

      @Override
      public void close() {}
    };
  }
}
