// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories;
import com.google.devtools.build.lib.skyframe.DiffAwareness.View;
import com.google.devtools.build.lib.skyframe.LocalDiffAwareness.SequentialView;
import com.google.devtools.build.lib.testing.common.FakeOptions;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.common.options.OptionsProvider;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Set;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Integration tests for LocalDiffAwareness.
 */
@RunWith(JUnit4.class)
public class LocalDiffAwarenessTest extends BuildIntegrationTestCase {

  /** Try this many times to pick up file changes. Inotify needs some nanoseconds of patience. */
  private static final int MAX_RETRY_COUNT = 20;

  private OptionsProvider watchFsEnabledProvider;
  private LocalDiffAwareness localDiff;
  private DiffAwareness.View oldView;
  private Path testCaseRoot;
  private Path testCaseIgnoredDir;

  @org.junit.Rule
  public TestName name = new TestName();

  @Before
  public final void initializeSettings() throws Exception  {
    LocalDiffAwareness.Factory factory = new LocalDiffAwareness.Factory(ImmutableList.<String>of());
    // Make sure all test functions have their own directory to test
    testCaseRoot = testRoot.getChild(name.getMethodName());
    testCaseRoot.createDirectoryAndParents();
    testCaseIgnoredDir = testCaseRoot.getChild("ignored-dir");
    testCaseIgnoredDir.createDirectoryAndParents();

    LocalDiffAwareness.Options localDiffOptions = new LocalDiffAwareness.Options();
    localDiffOptions.watchFS = true;
    watchFsEnabledProvider = FakeOptions.of(localDiffOptions);

    localDiff =
        (LocalDiffAwareness)
            factory.maybeCreate(
                Root.fromPath(testCaseRoot),
                IgnoredSubdirectories.of(
                    ImmutableSet.of(testCaseIgnoredDir.asFragment().toRelative())),
                watchFsEnabledProvider);

    // Ignore test failures when run on a Mac.
    //
    // On a Mac, LocalDiffAwareness.Factory#maybeCreate will produce a MacOSXFsEventsDiffAwareness.
    // There's a known issue with the underlying implementation
    // (https://github.com/bazelbuild/bazel/issues/10776); basically all the test cases in here
    // consistently fail, presumably due to the same underlying issue with FSEvents. Also,
    // MacOSXFsEventsDiffAwareness is already unit-tested separately anyway (although not very well
    // because of the bug).
    Assume.assumeFalse(OS.DARWIN.equals(OS.getCurrent()));
  }

  @After
  public final void closeLocalDiff() throws Exception {
    localDiff.close();
  }

  @After
  public final void deleteTestCaseRoot() throws Exception {
    testCaseRoot.deleteTree();
  }

  private void captureFirstView(OptionsProvider options) throws BrokenDiffAwarenessException {
    oldView = localDiff.getCurrentView(options);
    Preconditions.checkNotNull(localDiff);
  }

  @Test
  public void areInSequenceWithEverythingModifiedShouldAlwaysReturnFalse() throws Exception {
    captureFirstView(watchFsEnabledProvider);
    SequentialView old = (SequentialView) oldView;
    SequentialView everythingMod = (SequentialView) LocalDiffAwareness.EVERYTHING_MODIFIED;
    assertThat(LocalDiffAwareness.areInSequence(old, everythingMod)).isFalse();
    assertThat(LocalDiffAwareness.areInSequence(everythingMod, old)).isFalse();
    assertThat(LocalDiffAwareness.areInSequence(everythingMod, everythingMod)).isFalse();
  }

  @Test
  public void testFileAdded() throws Exception {
    captureFirstView(watchFsEnabledProvider);
    touch("foo.txt");
    new ModifiedFileSetChecker().modify("foo.txt").check();
  }

  @Test
  public void testSymlink() throws Exception {
    captureFirstView(watchFsEnabledProvider);
    touch("a");
    symlink("b", "a");
    new ModifiedFileSetChecker().modify("a").modify("b").check();
  }

  @Test
  public void testSymlinkBroken() throws Exception {
    captureFirstView(watchFsEnabledProvider);
    symlink("b", "a");
    new ModifiedFileSetChecker().modify("b").check();
  }

  @Test
  public void testFileModified() throws Exception {
    captureFirstView(watchFsEnabledProvider);

    touch("foo.txt");
    new ModifiedFileSetChecker().modify("foo.txt").check();

    new ModifiedFileSetChecker().check();

    touch("foo.txt");
    new ModifiedFileSetChecker().modify("foo.txt").check();
  }

  @Test
  public void testIgnoredFileModified() throws Exception {
    captureFirstView(watchFsEnabledProvider);

    touch(testCaseIgnoredDir.getRelative("foo").getPathString());
    new ModifiedFileSetChecker().check();
  }

  @Test
  public void testFileRemoved() throws Exception {
    captureFirstView(watchFsEnabledProvider);

    touch("foo.txt");
    new ModifiedFileSetChecker().modify("foo.txt").check();

    rm("foo.txt");
    new ModifiedFileSetChecker().modify("foo.txt").check();
  }

  @Test
  public void testFileAddAndRemove() throws Exception {
    captureFirstView(watchFsEnabledProvider);

    touch("foo.txt");
    touch("bar.txt");
    new ModifiedFileSetChecker().modify("foo.txt").modify("bar.txt").check();

    rm("foo.txt");
    touch("foo.txt");
    new ModifiedFileSetChecker().modify("foo.txt").check();
  }

  @Test
  public void testAddDirectory() throws Exception {
    captureFirstView(watchFsEnabledProvider);

    mkdir("equestria");
    touch("equestria/foo.txt");
    new ModifiedFileSetChecker().modify("equestria").modify("equestria/foo.txt").check();
  }

  @Test
  public void testRemoveDirectory() throws Exception {
    captureFirstView(watchFsEnabledProvider);

    mkdir("equestria");
    touch("equestria/foo.txt");
    new ModifiedFileSetChecker().modify("equestria").modify("equestria/foo.txt").check();


    rm("equestria");
    new ModifiedFileSetChecker().modify("equestria").modify("equestria/foo.txt").check();
  }

  @Test
  public void testLotsOfChanges() throws Exception {
    captureFirstView(watchFsEnabledProvider);

    mkdir("pre");
    touch("pre/pre.txt");
    new ModifiedFileSetChecker().modify("pre").modify("pre/pre.txt").check();

    mkdir("a");
    touch("a/a1.txt");
    touch("a/a2.txt");
    rm("a");
    mkdir("a");
    touch("a/a1.txt");
    touch("a/a2.txt");
    mkdir("a/b");
    touch("a/b/b1.txt");
    new ModifiedFileSetChecker()
    .modify("a")
    .modify("a/a1.txt")
    .modify("a/a2.txt")
    .modify("a/b")
    .modify("a/b/b1.txt")
    .check();

    rm("a/b/b1.txt");
    touch("a/b/b2.txt");
    rm("a/b");
    mkdir("a/b");
    rm("a/b");
    mkdir("a/b");
    touch("a/b/b3.txt");
    new ModifiedFileSetChecker()
    .modify("a/b")
    .modify("a/b/b1.txt")
    .modify("a/b/b2.txt")
    .modify("a/b/b3.txt")
    .check();

    rm("a/b/b3.txt");
    new ModifiedFileSetChecker().modify("a/b/b3.txt").check();
  }

  private static OptionsProvider createWatchFsDisabledProvider() {
    final LocalDiffAwareness.Options localDiffOptions = new LocalDiffAwareness.Options();
    localDiffOptions.watchFS = false;
    return FakeOptions.of(localDiffOptions);
  }

  @Test
  public void testEnableWatchFs() throws Exception {
    OptionsProvider watchFsDisabledProvider = createWatchFsDisabledProvider();
    captureFirstView(watchFsDisabledProvider);

    new ModifiedFileSetChecker().checkEverythingModified(watchFsDisabledProvider);

    touch("a.txt");
    new ModifiedFileSetChecker().checkEverythingModified(watchFsDisabledProvider);

    touch("b.txt");
    new ModifiedFileSetChecker().checkEverythingModified(watchFsEnabledProvider);

    touch("c.txt");
    new ModifiedFileSetChecker().modify("c.txt").check();
  }

  @Test
  public void testDisableWatchFs() throws Exception {
    OptionsProvider watchFsDisabledProvider = createWatchFsDisabledProvider();
    captureFirstView(watchFsEnabledProvider);

    new ModifiedFileSetChecker().check();

    assertThrows(
        BrokenDiffAwarenessException.class,
        () -> localDiff.getCurrentView(watchFsDisabledProvider));
  }

  @Test
  public void modifiedPathIsntUnderWatchRoot() {
    java.nio.file.Path otherRootDirectoryNioPath = Paths.get("/notundertestroot");
    assertThat(otherRootDirectoryNioPath.startsWith(Paths.get(testCaseRoot.getPathString())))
        .isFalse();

    View oldView =
        new LocalDiffAwareness.SequentialView(
            localDiff, /*position=*/ 0, /*modifiedAbsolutePaths=*/ ImmutableSet.of());
    View newView =
        new LocalDiffAwareness.SequentialView(
            localDiff,
            /*position=*/ 1,
            /*modifiedAbsolutePaths=*/ ImmutableSet.of(
                otherRootDirectoryNioPath.resolve("foo.txt")));
    Throwable throwable =
        assertThrows(BrokenDiffAwarenessException.class, () -> localDiff.getDiff(oldView, newView));
    assertThat(throwable)
        .hasMessageThat()
        // Do a round-trip through PathFragment to deal with Windows path separators.
        .contains(
            PathFragment.create("/notundertestroot/foo.txt is not under ").getPathString()
                + PathFragment.create(testCaseRoot.getPathString()).getPathString());
  }

  private void touch(String pathString) throws IOException {
    Path path = testCaseRoot.getRelative(pathString);
    FileSystemUtils.createEmptyFile(path);
    TestUtils.writeLines(path, "Sunshine, sunshine, ladybugs awake!");
  }

  private void mkdir(String pathString) throws IOException {
    Path path = testCaseRoot.getRelative(pathString);
    path.createDirectoryAndParents();
  }

  private void rm(String pathString) throws IOException {
    Path path = testCaseRoot.getRelative(pathString);
    path.deleteTree();
  }

  private void symlink(String from, String to) throws IOException {
    Path fromPath = testCaseRoot.getRelative(from);
    Path toPath = testCaseRoot.getRelative(to);
    FileSystemUtils.ensureSymbolicLink(fromPath, toPath);
  }

  private class ModifiedFileSetChecker {
    private final Set<PathFragment> modified = Sets.newHashSet();

    public void check() throws Exception {
      // Unfortunately, inotify needs a few milliseconds (more than a few in the worst case)
      // after a change to pick up a list of changed files. Trying a few times to make sure.
      for (int i = 0; i < MAX_RETRY_COUNT; i++) {
        Thread.sleep(150);
        DiffAwareness.View newView = localDiff.getCurrentView(watchFsEnabledProvider);
        ModifiedFileSet modifiedFileSet = localDiff.getDiff(oldView, newView);
        oldView = newView;
        assertThat(modifiedFileSet.treatEverythingAsModified()).isFalse();
        if (modifiedFileSet.modifiedSourceFiles().isEmpty()) {
          continue;
        }
        assertThat(modifiedFileSet.modifiedSourceFiles()).isEqualTo(modified);
        return;
      }
      // If we never received any changes, make sure this is what we actually expect.
      assertThat(modified).isEmpty();
    }

    public void checkEverythingModified(OptionsProvider options) throws Exception {
      DiffAwareness.View newView = localDiff.getCurrentView(options);
      ModifiedFileSet modifiedFileSet = localDiff.getDiff(oldView, newView);
      oldView = newView;
      assertThat(modifiedFileSet.treatEverythingAsModified()).isTrue();
    }

    @CanIgnoreReturnValue
    public ModifiedFileSetChecker modify(String filename) {
      modified.add(PathFragment.create(filename));
      return this;
    }
  }
}
