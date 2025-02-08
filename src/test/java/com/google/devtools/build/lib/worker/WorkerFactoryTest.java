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
package com.google.devtools.build.lib.worker;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.lib.worker.WorkerProcessStatus.Status;
import java.io.IOException;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link WorkerFactory}. */
@RunWith(JUnit4.class)
public class WorkerFactoryTest {

  final FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);

  @After
  public void tearDown() {
    WorkerMultiplexerManager.resetForTesting();
  }

  /**
   * Regression test for b/64689608: The execroot of the sandboxed worker process must end with the
   * workspace name, just like the normal execroot does.
   */
  @Test
  public void sandboxedWorkerPathEndsWithWorkspaceName() throws Exception {
    Path workerBaseDir = fs.getPath("/outputbase/bazel-workers");
    WorkerFactory workerFactory = new WorkerFactory(workerBaseDir, new WorkerOptions());
    WorkerKey workerKey = createWorkerKey(/* mustBeSandboxed= */ true, /* multiplex= */ false);
    Path sandboxedWorkerPath = workerFactory.getSandboxedWorkerPath(workerKey, 1);

    assertThat(sandboxedWorkerPath.getBaseName()).isEqualTo("workspace");
  }

  /** WorkerFactory should create correct worker type based on WorkerKey. */
  @Test
  public void workerCreationTypeCheck() throws Exception {
    Path workerBaseDir = fs.getPath("/outputbase/bazel-workers");
    WorkerFactory workerFactory = new WorkerFactory(workerBaseDir, new WorkerOptions());
    WorkerKey sandboxedWorkerKey =
        createWorkerKey(/* mustBeSandboxed= */ true, /* multiplex= */ false);
    Worker sandboxedWorker = workerFactory.create(sandboxedWorkerKey);
    assertThat(sandboxedWorker.getClass()).isEqualTo(SandboxedWorker.class);

    WorkerKey nonProxiedWorkerKey =
        createWorkerKey(/* mustBeSandboxed= */ false, /* multiplex= */ false);
    Worker nonProxiedWorker = workerFactory.create(nonProxiedWorkerKey);
    assertThat(nonProxiedWorker.getClass()).isEqualTo(SingleplexWorker.class);

    WorkerKey proxiedWorkerKey =
        createWorkerKey(/* mustBeSandboxed= */ false, /* multiplex= */ true);
    Worker proxiedWorker = workerFactory.create(proxiedWorkerKey);
    // If proxied = true, WorkerProxy is created along with a WorkerMultiplexer.
    // Destroy WorkerMultiplexer to avoid unexpected behavior in WorkerMultiplexerManagerTest.
    WorkerMultiplexerManager.removeInstance(proxiedWorkerKey);
    assertThat(proxiedWorker.getClass()).isEqualTo(WorkerProxy.class);
  }

  /** Proxied workers with the same WorkerKey should share the log file. */
  @Test
  public void testMultiplexWorkersShareLogfiles() throws Exception {
    Path workerBaseDir = fs.getPath("/outputbase/bazel-workers");
    WorkerFactory workerFactory = new WorkerFactory(workerBaseDir, new WorkerOptions());

    WorkerKey workerKey1 =
        createWorkerKey(/* mustBeSandboxed= */ false, /* multiplex= */ true, "arg1");
    Worker proxiedWorker1a = workerFactory.create(workerKey1);
    Worker proxiedWorker1b = workerFactory.create(workerKey1);
    WorkerKey workerKey2 =
        createWorkerKey(/* mustBeSandboxed= */ false, /* multiplex= */ true, "arg2");
    Worker proxiedWorker2 = workerFactory.create(workerKey2);
    assertThat(proxiedWorker1a.getLogFile()).isEqualTo(proxiedWorker1b.getLogFile());
    assertThat(proxiedWorker1a.getLogFile()).isNotEqualTo(proxiedWorker2.getLogFile());
  }

  /** WorkerFactory should create the base dir if needed and fail if that's impossible. */
  @Test
  public void testCreate_createsWorkerDirectory() throws Exception {
    Path workerBaseDir = fs.getPath("/outputbase/bazel-workers");
    WorkerFactory workerFactory = new WorkerFactory(workerBaseDir, new WorkerOptions());
    WorkerKey sandboxedWorkerKey = createWorkerKey(/* mustBeSandboxed */ true, /* proxied */ false);
    assertThat(workerBaseDir.isDirectory()).isFalse();
    workerFactory.create(sandboxedWorkerKey);
    assertThat(workerBaseDir.isDirectory()).isTrue();

    workerBaseDir.delete();
    workerBaseDir.createSymbolicLink(workerBaseDir.getRelative("whatevs"));
    assertThat(workerBaseDir.isDirectory()).isFalse();
    assertThrows(IOException.class, () -> workerFactory.create(sandboxedWorkerKey));
  }

  @Test
  public void testDoomedWorkerValidation() throws Exception {
    Path workerBaseDir = fs.getPath("/outputbase/bazel-workers");
    WorkerFactory workerFactory = new WorkerFactory(workerBaseDir, new WorkerOptions());

    WorkerKey workerKey =
        createWorkerKey(/* mustBeSandboxed= */ false, /* multiplex= */ false, "arg1");
    Worker worker = workerFactory.create(workerKey);

    assertThat(workerFactory.validateWorker(workerKey, worker)).isTrue();

    worker.getStatus().maybeUpdateStatus(Status.KILLED_DUE_TO_MEMORY_PRESSURE);

    assertThat(workerFactory.validateWorker(workerKey, worker)).isFalse();
  }

  protected WorkerKey createWorkerKey(boolean mustBeSandboxed, boolean multiplex, String... args) {
    return new WorkerKey(
        /* args= */ ImmutableList.copyOf(args),
        /* env= */ ImmutableMap.of(),
        /* execRoot= */ fs.getPath("/outputbase/execroot/workspace"),
        /* mnemonic= */ "dummy",
        /* workerFilesCombinedHash= */ HashCode.fromInt(0),
        /* workerFilesWithDigests= */ ImmutableSortedMap.of(),
        /* sandboxed= */ mustBeSandboxed,
        /* useInMemoryTracking= */ false,
        /* multiplex= */ multiplex,
        /* cancellable= */ false,
        WorkerProtocolFormat.PROTO);
  }
}
