// Copyright 2016 The Bazel Authors. All rights reserved.
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

import static com.google.devtools.build.lib.sandbox.LinuxSandboxCommandLineBuilder.NetworkNamespace.NETNS;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.exec.TreeDeleter;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.sandbox.CgroupsInfo;
import com.google.devtools.build.lib.sandbox.LinuxSandboxCommandLineBuilder;
import com.google.devtools.build.lib.sandbox.LinuxSandboxUtil;
import com.google.devtools.build.lib.sandbox.SandboxHelpers;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs;
import com.google.devtools.build.lib.sandbox.cgroups.VirtualCgroupFactory;
import com.google.devtools.build.lib.shell.Subprocess;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.IOException;
import java.util.Set;
import java.util.SortedMap;
import javax.annotation.Nullable;

/** A {@link SingleplexWorker} that runs inside a sandboxed execution root. */
final class SandboxedWorker extends SingleplexWorker {

  // Need to have this data class because we can't depend on SandboxOptions in here.
  record WorkerSandboxOptions(
      Path sandboxBinary,
      boolean fakeHostname,
      boolean fakeUsername,
      boolean debugMode,
      ImmutableSet<PathFragment> tmpfsPath,
      ImmutableSet<String> writablePaths,
      int memoryLimit,
      ImmutableSet<Path> inaccessiblePaths,
      ImmutableMap<String, String> additionalMountPaths) {}

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private final WorkerExecRoot workerExecRoot;

  /** Options specific to hardened sandbox, null if not using that. */
  @Nullable private final WorkerSandboxOptions hardenedSandboxOptions;

  private Path inaccessibleHelperDir;
  private Path inaccessibleHelperFile;
  private final TreeDeleter treeDeleter;

  SandboxedWorker(
      WorkerKey workerKey,
      int workerId,
      Path workDir,
      Path logFile,
      WorkerOptions workerOptions,
      @Nullable WorkerSandboxOptions hardenedSandboxOptions,
      TreeDeleter treeDeleter,
      boolean useInMemoryTracking,
      @Nullable VirtualCgroupFactory cgroupFactory) {
    super(workerKey, workerId, workDir, logFile, workerOptions, cgroupFactory);
    Path tmpDirPath = SandboxHelpers.getTmpDirPath(workDir);
    this.workerExecRoot =
        new WorkerExecRoot(
            workDir,
            hardenedSandboxOptions != null
                ? ImmutableList.of(PathFragment.create(tmpDirPath.getPathString()))
                : ImmutableList.of(),
            useInMemoryTracking);
    this.hardenedSandboxOptions = hardenedSandboxOptions;
    this.treeDeleter = treeDeleter;
  }

  @Override
  public boolean isSandboxed() {
    return true;
  }

  private ImmutableSet<Path> getWritableDirs(Path sandboxExecRoot) throws IOException {
    // We have to make the TEST_TMPDIR directory writable if it is specified.
    ImmutableSet.Builder<Path> writableDirs =
        ImmutableSet.<Path>builder().add(sandboxExecRoot).add(sandboxExecRoot.getRelative("/tmp"));

    FileSystem fs = sandboxExecRoot.getFileSystem();
    for (String writablePath : hardenedSandboxOptions.writablePaths()) {
      Path path = fs.getPath(writablePath);
      writableDirs.add(path);
      if (path.isSymbolicLink()) {
        writableDirs.add(path.resolveSymbolicLinks());
      }
    }

    writableDirs.add(fs.getPath("/dev/shm").resolveSymbolicLinks());
    writableDirs.add(fs.getPath("/tmp"));

    return writableDirs.build();
  }

  private SortedMap<Path, Path> getBindMounts(Path sandboxExecRoot, @Nullable Path sandboxTmp)
      throws UserExecException, IOException {
    FileSystem fs = sandboxExecRoot.getFileSystem();
    Path tmpPath = fs.getPath("/tmp");
    final SortedMap<Path, Path> bindMounts = Maps.newTreeMap();
    bindMounts.put(tmpPath, sandboxTmp);
    SandboxHelpers.mountAdditionalPaths(
        hardenedSandboxOptions.additionalMountPaths(), sandboxExecRoot, bindMounts);

    inaccessibleHelperFile = LinuxSandboxUtil.getInaccessibleHelperFile(sandboxExecRoot);
    inaccessibleHelperDir = LinuxSandboxUtil.getInaccessibleHelperDir(sandboxExecRoot);
    for (Path inaccessiblePath : hardenedSandboxOptions.inaccessiblePaths()) {
      if (inaccessiblePath.isDirectory(Symlinks.NOFOLLOW)) {
        bindMounts.put(inaccessiblePath, inaccessibleHelperDir);
      } else {
        bindMounts.put(inaccessiblePath, inaccessibleHelperFile);
      }
    }
    // TODO(larsrc): Handle hermetic tmp
    LinuxSandboxUtil.validateBindMounts(bindMounts);
    return bindMounts;
  }

  @Override
  protected Subprocess createProcess() throws IOException, UserExecException {
    ImmutableList<String> args = makeExecPathAbsolute(workerKey.getArgs());

    // We put the sandbox inside a unique subdirectory using the worker's ID.
    if (cgroupFactory != null) {
      cgroup = cgroupFactory.create(workerId, ImmutableMap.of());
    } else if (options.useCgroupsOnLinux || hardenedSandboxOptions != null) {
      // In the event that the memory limit is 0, we defer to using Blaze's WorkerLifecycleManager
      // to kill workers rather than cgroup's OOM killer.
      cgroup =
          CgroupsInfo.getBlazeSpawnsCgroup()
              .createIndividualSpawnCgroup(
                  "worker_sandbox_" + workerId,
                  hardenedSandboxOptions != null ? hardenedSandboxOptions.memoryLimit() : 0);
    }

    // TODO(larsrc): Check that execRoot and outputBase are not under /tmp
    if (hardenedSandboxOptions != null) {
      Path sandboxTmp = SandboxHelpers.getTmpDirPath(workDir);
      sandboxTmp.createDirectoryAndParents();

      // Mostly tests require network, and some blaze run commands, but no workers.
      LinuxSandboxCommandLineBuilder commandLineBuilder =
          LinuxSandboxCommandLineBuilder.commandLineBuilder(
                  this.hardenedSandboxOptions.sandboxBinary())
              .setWritableFilesAndDirectories(getWritableDirs(workDir))
              .setTmpfsDirectories(hardenedSandboxOptions.tmpfsPath())
              .setPersistentProcess(true)
              .setBindMounts(getBindMounts(workDir, sandboxTmp))
              .setUseFakeHostname(hardenedSandboxOptions.fakeHostname())
              .setCreateNetworkNamespace(NETNS);

      if (cgroup != null && cgroup.exists()) {
        commandLineBuilder.setCgroupsDirs(cgroup.paths());
      }

      if (this.hardenedSandboxOptions.fakeUsername()) {
        commandLineBuilder.setUseFakeUsername(true);
      }

      args = commandLineBuilder.buildForCommand(args);
    }

    Subprocess process = createProcessBuilder(args).start();

    // If using hardened sandbox (aka linux-sandbox), the linux-sandbox parent process moves the
    // sandboxed children processes (pid 1, 2) into the cgroup. But we still need to move the
    // linux-sandbox process into the worker cgroup. On the other hand, without linux-sandbox, Blaze
    // needs to do this itself for the spawned worker process.
    if (cgroup != null && cgroup.exists()) {
      cgroup.addProcess(process.getProcessId());
    }
    return process;
  }

  @Override
  public void prepareExecution(
      SandboxInputs inputFiles, SandboxOutputs outputs, Set<PathFragment> workerFiles)
      throws IOException, InterruptedException, UserExecException {
    try (SilentCloseable c = Profiler.instance().profile("workerExecRoot.createFileSystem")) {
      workerExecRoot.createFileSystem(workerFiles, inputFiles, outputs, treeDeleter);
    }

    super.prepareExecution(inputFiles, outputs, workerFiles);
  }

  @Override
  public void finishExecution(Path execRoot, SandboxOutputs outputs) throws IOException {
    super.finishExecution(execRoot, outputs);
    if (cgroup != null && cgroup.exists()) {
      // This is only to not leave too much behind in the cgroups tree, can ignore errors.
      cgroup.destroy();
    }
    workerExecRoot.copyOutputs(execRoot, outputs);
  }

  @Override
  void destroy() {
    super.destroy();
    try {
      if (inaccessibleHelperFile != null) {
        inaccessibleHelperFile.delete();
      }
      if (inaccessibleHelperDir != null) {
        inaccessibleHelperDir.delete();
      }
      if (cgroup != null && cgroup.exists()) {
        // This is only to not leave too much behind in the cgroups tree, can ignore errors.
        cgroup.destroy();
      }
      workDir.deleteTree();
      if (hardenedSandboxOptions != null) {
        SandboxHelpers.getTmpDirPath(workDir).deleteTree();
      }
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Caught IOException while deleting workdir.");
    }
  }
}
