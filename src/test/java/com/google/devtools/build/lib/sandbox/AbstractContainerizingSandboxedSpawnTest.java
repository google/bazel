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

package com.google.devtools.build.lib.sandbox;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.exec.TreeDeleter;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link SandboxedSpawn}. */
@RunWith(JUnit4.class)
public class AbstractContainerizingSandboxedSpawnTest {

  private Path sandboxPath;
  private Path sandboxExecRoot;

  @Before
  public void createSandboxExecRoot() throws IOException {
    Scratch scratch = new Scratch(new InMemoryFileSystem(DigestHashFunction.SHA256));
    sandboxPath = scratch.dir("/sandbox");
    sandboxExecRoot = scratch.dir("/sandbox/execroot");
  }

  @Test
  public void testMoveOutputs() throws Exception {
    FileSystem fileSystem = new InMemoryFileSystem(DigestHashFunction.SHA256);
    Path testRoot = fileSystem.getPath(TestUtils.tmpDir());
    testRoot.createDirectoryAndParents();

    Path execRoot = testRoot.getRelative("execroot");
    execRoot.createDirectory();

    Path outputFile = execRoot.getRelative("very/output.txt");
    Path outputLink = execRoot.getRelative("very/output.link");
    Path outputDangling = execRoot.getRelative("very/output.dangling");
    Path outputDir = execRoot.getRelative("very/output.dir");
    Path outputInUncreatedTargetDir = execRoot.getRelative("uncreated/output.txt");

    ImmutableSet<PathFragment> outputs =
        ImmutableSet.of(
            outputFile.relativeTo(execRoot),
            outputLink.relativeTo(execRoot),
            outputDangling.relativeTo(execRoot),
            outputInUncreatedTargetDir.relativeTo(execRoot));
    ImmutableSet<PathFragment> outputDirs = ImmutableSet.of(outputDir.relativeTo(execRoot));
    for (PathFragment path : outputs) {
      execRoot.getRelative(path).getParentDirectory().createDirectoryAndParents();
    }
    for (PathFragment path : outputDirs) {
      execRoot.getRelative(path).createDirectoryAndParents();
    }

    FileSystemUtils.createEmptyFile(outputFile);
    outputLink.createSymbolicLink(PathFragment.create("output.txt"));
    outputDangling.createSymbolicLink(PathFragment.create("doesnotexist"));
    outputDir.createDirectory();
    FileSystemUtils.createEmptyFile(outputDir.getRelative("test.txt"));
    FileSystemUtils.createEmptyFile(outputInUncreatedTargetDir);

    Path outputsDir = testRoot.getRelative("outputs");
    outputsDir.createDirectory();
    outputsDir.getRelative("very").createDirectory();
    SandboxHelpers.moveOutputs(SandboxOutputs.create(outputs, outputDirs), execRoot, outputsDir);

    assertThat(outputsDir.getRelative("very/output.txt").isFile(Symlinks.NOFOLLOW)).isTrue();
    assertThat(outputsDir.getRelative("very/output.link").isSymbolicLink()).isTrue();
    assertThat(outputsDir.getRelative("very/output.link").resolveSymbolicLinks())
        .isEqualTo(outputsDir.getRelative("very/output.txt"));
    assertThat(outputsDir.getRelative("very/output.dangling").isSymbolicLink()).isTrue();
    assertThrows(
        IOException.class,
        () -> outputsDir.getRelative("very/output.dangling").resolveSymbolicLinks());
    assertThat(outputsDir.getRelative("very/output.dir").isDirectory(Symlinks.NOFOLLOW)).isTrue();
    assertThat(outputsDir.getRelative("very/output.dir/test.txt").isFile(Symlinks.NOFOLLOW))
        .isTrue();
    assertThat(outputsDir.getRelative("uncreated/output.txt").isFile(Symlinks.NOFOLLOW)).isTrue();
  }

  /** Watches a logger for file copy warnings (instead of moves) and counts them. */
  private static class FileCopyWarningTracker extends Handler {
    int warningsCounter = 0;

    @Override
    public void publish(LogRecord record) {
      if (record.getMessage().contains("different file systems")) {
        warningsCounter++;
      }
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }

  @Test
  public void testMoveOutputs_warnOnceIfCopyHappened() throws Exception {
    class MultipleDeviceFS extends InMemoryFileSystem {
      MultipleDeviceFS() {
        super(DigestHashFunction.SHA256);
      }

      @Override
      public void renameTo(PathFragment source, PathFragment target) throws IOException {
        throw new IOException("EXDEV");
      }
    }
    FileSystem fileSystem = new MultipleDeviceFS();
    Path testRoot = fileSystem.getPath(TestUtils.tmpDir());
    testRoot.createDirectoryAndParents();

    FileCopyWarningTracker tracker = new FileCopyWarningTracker();
    Logger logger = Logger.getLogger(SandboxHelpers.class.getName());
    logger.setUseParentHandlers(false);
    logger.addHandler(tracker);

    Path execRoot = testRoot.getRelative("execroot");
    execRoot.createDirectory();

    Path outputFile1 = execRoot.getRelative("very/output1.txt");
    Path outputFile2 = execRoot.getRelative("much/output2.txt");

    ImmutableSet<PathFragment> outputs =
        ImmutableSet.of(outputFile1.relativeTo(execRoot), outputFile2.relativeTo(execRoot));
    for (PathFragment path : outputs) {
      execRoot.getRelative(path).getParentDirectory().createDirectoryAndParents();
    }

    FileSystemUtils.createEmptyFile(outputFile1);
    FileSystemUtils.createEmptyFile(outputFile2);

    Path outputsDir = testRoot.getRelative("outputs");
    outputsDir.createDirectory();
    outputsDir.getRelative("very").createDirectory();
    outputsDir.getRelative("much").createDirectory();
    SandboxHelpers.moveOutputs(
        SandboxOutputs.create(outputs, ImmutableSet.of()), execRoot, outputsDir);

    assertThat(outputsDir.getRelative("very/output1.txt").isFile(Symlinks.NOFOLLOW)).isTrue();
    assertThat(outputsDir.getRelative("much/output2.txt").isFile(Symlinks.NOFOLLOW)).isTrue();

    assertThat(tracker.warningsCounter).isEqualTo(1);
  }

  @Test
  public void createFileSystem_createsDirectoriesForAndInputFiles() throws Exception {
    SandboxInputs sandboxInputs =
        createSandboxInputs(/*files=*/ ImmutableList.of("a/b"), /*symlinks=*/ ImmutableList.of());
    SandboxOutputs sandboxOutputs =
        SandboxOutputs.create(/*files=*/ ImmutableSet.of(), /*dirs=*/ ImmutableSet.of());
    AbstractContainerizingSandboxedSpawn sandboxedSpawn =
        createContainerizingSandboxedSpawn(sandboxInputs, sandboxOutputs);

    sandboxedSpawn.createFileSystem();

    assertThat(listDirectory(sandboxExecRoot)).containsExactly(directory("a"), file("a/b"));
  }

  @Test
  public void createFileSystem_createsDirectoriesForAndInputSymlinks() throws Exception {
    SandboxInputs sandboxInputs =
        createSandboxInputs(/*files=*/ ImmutableList.of(), /*symlinks=*/ ImmutableList.of("a/b/c"));
    SandboxOutputs sandboxOutputs =
        SandboxOutputs.create(/*files=*/ ImmutableSet.of(), /*dirs=*/ ImmutableSet.of());
    AbstractContainerizingSandboxedSpawn sandboxedSpawn =
        createContainerizingSandboxedSpawn(sandboxInputs, sandboxOutputs);

    sandboxedSpawn.createFileSystem();

    assertThat(listDirectory(sandboxExecRoot))
        .containsExactly(directory("a"), directory("a/b"), symlink("a/b/c"));
  }

  @Test
  public void createFileSystem_uplevelReference_createsSiblingDirectory() throws Exception {
    SandboxInputs sandboxInputs =
        createSandboxInputs(
            /*files=*/ ImmutableList.of("../a/b"), /*symlinks=*/ ImmutableList.of());
    SandboxOutputs sandboxOutputs =
        SandboxOutputs.create(/*files=*/ ImmutableSet.of(), /*dirs=*/ ImmutableSet.of());
    AbstractContainerizingSandboxedSpawn sandboxedSpawn =
        createContainerizingSandboxedSpawn(sandboxInputs, sandboxOutputs);

    sandboxedSpawn.createFileSystem();

    assertThat(listDirectory(sandboxExecRoot.getParentDirectory()))
        .containsExactly(directory("a"), file("a/b"), directory("execroot"));
  }

  @Test
  public void createFileSystem_createsDirectoriesForOutputFiles() throws Exception {
    SandboxInputs sandboxInputs =
        createSandboxInputs(/*files=*/ ImmutableList.of(), /*symlinks=*/ ImmutableList.of());
    SandboxOutputs sandboxOutputs =
        SandboxOutputs.create(
            /*files=*/ ImmutableSet.of(PathFragment.create("a/b"), PathFragment.create("c/d/e")),
            /*dirs=*/ ImmutableSet.of());
    AbstractContainerizingSandboxedSpawn sandboxedSpawn =
        createContainerizingSandboxedSpawn(sandboxInputs, sandboxOutputs);

    sandboxedSpawn.createFileSystem();

    assertThat(listDirectory(sandboxExecRoot))
        .containsExactly(directory("a"), directory("c"), directory("c/d"));
  }

  @Test
  public void createFileSystem_createsOutputDirectories() throws Exception {
    SandboxInputs sandboxInputs =
        createSandboxInputs(/*files=*/ ImmutableList.of(), /*symlinks=*/ ImmutableList.of());
    SandboxOutputs sandboxOutputs =
        SandboxOutputs.create(
            /*files=*/ ImmutableSet.of(),
            /*dirs=*/ ImmutableSet.of(PathFragment.create("a/b"), PathFragment.create("c/d/e")));
    AbstractContainerizingSandboxedSpawn sandboxedSpawn =
        createContainerizingSandboxedSpawn(sandboxInputs, sandboxOutputs);

    sandboxedSpawn.createFileSystem();

    assertThat(listDirectory(sandboxExecRoot))
        .containsExactly(
            directory("a"), directory("a/b"), directory("c"), directory("c/d"), directory("c/d/e"));
  }

  @Test
  public void createFileSystem_nestedFileAndDirectory_createsDirectoriesAndFile() throws Exception {
    SandboxInputs sandboxInputs =
        createSandboxInputs(
            /*files=*/ ImmutableList.of("a/b/file"), /*symlinks=*/ ImmutableList.of());
    SandboxOutputs sandboxOutputs =
        SandboxOutputs.create(
            /*files=*/ ImmutableSet.of(), /*dirs=*/ ImmutableSet.of(PathFragment.create("a")));
    AbstractContainerizingSandboxedSpawn sandboxedSpawn =
        createContainerizingSandboxedSpawn(sandboxInputs, sandboxOutputs);

    sandboxedSpawn.createFileSystem();

    assertThat(listDirectory(sandboxExecRoot))
        .containsExactly(directory("a"), directory("a/b"), file("a/b/file"));
  }

  @Test
  public void createFileSystem_overlappingPaths_createsAllDirectories() throws Exception {
    SandboxInputs sandboxInputs =
        createSandboxInputs(
            /*files=*/ ImmutableList.of("1/2/file1"),
            /*symlinks=*/ ImmutableList.of("1/2/3/symlink"));
    SandboxOutputs sandboxOutputs =
        SandboxOutputs.create(
            /*files=*/ ImmutableSet.of(PathFragment.create("1/2/file2")),
            /*dirs=*/ ImmutableSet.of(PathFragment.create("1"), PathFragment.create("2/3/4")));
    AbstractContainerizingSandboxedSpawn sandboxedSpawn =
        createContainerizingSandboxedSpawn(sandboxInputs, sandboxOutputs);

    sandboxedSpawn.createFileSystem();

    assertThat(listDirectory(sandboxExecRoot))
        .containsExactly(
            directory("1"),
            directory("1/2"),
            file("1/2/file1"),
            directory("1/2/3"),
            symlink("1/2/3/symlink"),
            directory("2"),
            directory("2/3"),
            directory("2/3/4"));
  }

  @Test
  public void createFileSystem_fileInUpUpLevelReference_fails() {
    SandboxInputs sandboxInputs =
        createSandboxInputs(
            /*files=*/ ImmutableList.of("../../file"), /*symlinks=*/ ImmutableList.of());
    SandboxOutputs sandboxOutputs =
        SandboxOutputs.create(/*files=*/ ImmutableSet.of(), /*dirs=*/ ImmutableSet.of());
    AbstractContainerizingSandboxedSpawn sandboxedSpawn =
        createContainerizingSandboxedSpawn(sandboxInputs, sandboxOutputs);

    assertThrows(IllegalArgumentException.class, sandboxedSpawn::createFileSystem);
  }

  @Test
  public void createFileSystem_overlappingSymlinkAndParent_createsCorrectParentsAndFails()
      throws Exception {
    SandboxInputs sandboxInputs =
        createSandboxInputs(
            /*files=*/ ImmutableList.of("1/2/3/file", "1/4/file"),
            /*symlinks=*/ ImmutableMap.of("1/2", "4"));
    SandboxOutputs sandboxOutputs =
        SandboxOutputs.create(/*files=*/ ImmutableSet.of(), /*dirs=*/ ImmutableSet.of());
    AbstractContainerizingSandboxedSpawn sandboxedSpawn =
        createContainerizingSandboxedSpawn(sandboxInputs, sandboxOutputs);

    assertThrows(IOException.class, sandboxedSpawn::createFileSystem);

    ImmutableList<PathEntry> entries = listDirectory(sandboxExecRoot);
    assertThat(entries)
        .containsAtLeast(directory("1"), directory("1/2"), directory("1/2/3"), directory("1/4"));
    assertThat(entries).doesNotContain(directory("1/4/3"));
  }

  private AbstractContainerizingSandboxedSpawn createContainerizingSandboxedSpawn(
      SandboxInputs sandboxInputs, SandboxOutputs sandboxOutputs) {
    return new AbstractContainerizingSandboxedSpawn(
        sandboxPath,
        sandboxExecRoot,
        /* arguments= */ ImmutableList.of(),
        /* environment= */ ImmutableMap.of(),
        sandboxInputs,
        sandboxOutputs,
        /* writableDirs= */ ImmutableSet.of(),
        mock(TreeDeleter.class),
        /* sandboxDebugPath= */ null,
        /* statisticsPath= */ null,
        "Mnemonic") {

      @Override
      protected void copyFile(Path source, Path target) {
        throw new UnsupportedOperationException();
      }
    };
  }

  private static SandboxInputs createSandboxInputs(
      ImmutableList<String> files, ImmutableList<String> symlinks) {
    return createSandboxInputs(
        files,
        symlinks.stream().collect(toImmutableMap(Function.identity(), ignored -> "anywhere")));
  }

  private static SandboxInputs createSandboxInputs(
      ImmutableList<String> files, ImmutableMap<String, String> symlinks) {
    Map<PathFragment, Path> filesMap = Maps.newHashMapWithExpectedSize(files.size());
    for (String file : files) {
      filesMap.put(PathFragment.create(file), null);
    }
    return new SandboxInputs(
        filesMap,
        /* virtualInputs= */ ImmutableMap.of(),
        symlinks.entrySet().stream()
            .collect(
                toImmutableMap(
                    e -> PathFragment.create(e.getKey()), e -> PathFragment.create(e.getValue()))));
  }

  /** Return a list of all entries under the provided directory recursively. */
  private static ImmutableList<PathEntry> listDirectory(Path directory) throws IOException {
    Collection<Path> entries = FileSystemUtils.traverseTree(directory, ignored -> true);
    ImmutableList.Builder<PathEntry> result = ImmutableList.builderWithExpectedSize(entries.size());
    for (Path path : entries) {
      PathFragment relativePath = path.asFragment().relativeTo(directory.asFragment());
      FileStatus stat = path.stat(Symlinks.NOFOLLOW);
      if (stat.isFile()) {
        result.add(PathEntry.create(relativePath, PathEntry.Type.FILE));
      } else if (stat.isDirectory()) {
        result.add(PathEntry.create(relativePath, PathEntry.Type.DIRECTORY));
      } else if (stat.isSymbolicLink()) {
        result.add(PathEntry.create(relativePath, PathEntry.Type.SYMLINK));
      } else {
        throw new AssertionError("Unexpected file type for " + path);
      }
    }
    return result.build();
  }

  record PathEntry(PathFragment relativePath, Type type) {
    PathEntry {
      requireNonNull(relativePath, "relativePath");
      requireNonNull(type, "type");
    }

    enum Type {
      FILE,
      DIRECTORY,
      SYMLINK
    }

    static PathEntry create(PathFragment path, Type type) {
      return new PathEntry(path, type);
    }
  }

  PathEntry file(String path) {
    return PathEntry.create(PathFragment.create(path), PathEntry.Type.FILE);
  }

  PathEntry directory(String path) {
    return PathEntry.create(PathFragment.create(path), PathEntry.Type.DIRECTORY);
  }

  PathEntry symlink(String path) {
    return PathEntry.create(PathFragment.create(path), PathEntry.Type.SYMLINK);
  }
}
