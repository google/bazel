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

package com.google.devtools.build.lib.skyframe;

import static org.junit.Assume.assumeFalse;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.skyframe.DiffAwareness.View;
import com.google.devtools.build.lib.testing.common.FakeOptions;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.OptionsProvider;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link MacOSXFsEventsDiffAwareness} */
@RunWith(JUnit4.class)
public class MacOSXFsEventsDiffAwarenessTest {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static void rmdirs(Path directory) throws IOException {
    Files.walkFileTree(
        directory,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private MacOSXFsEventsDiffAwareness underTest;
  private Path watchedPath;
  private OptionsProvider watchFsEnabledProvider;

  @Before
  public void setUp() throws Exception {
    watchedPath = com.google.common.io.Files.createTempDir().getCanonicalFile().toPath();
    underTest = new MacOSXFsEventsDiffAwareness(watchedPath);
    LocalDiffAwareness.Options localDiffOptions = new LocalDiffAwareness.Options();
    localDiffOptions.watchFS = true;
    watchFsEnabledProvider = FakeOptions.of(localDiffOptions);
  }

  @After
  public void tearDown() throws Exception {
    underTest.close();
    rmdirs(watchedPath);
  }

  private void scratchDir(String path) throws IOException {
    Path p = watchedPath.resolve(path);
    p.toFile().mkdirs();
  }

  private void scratchFile(String path, String contents) throws IOException {
    Path p = watchedPath.resolve(path);
    com.google.common.io.Files.asCharSink(p.toFile(), StandardCharsets.UTF_8).write(contents);
  }

  private void scratchFile(String path) throws IOException {
    scratchFile(path, "");
  }

  /**
   * Checks that the union of the diffs between the current view and each member of some consecutive
   * sequence of views is the specific set of given files.
   *
   * @param view1 the view to compare to
   * @param rawPaths the files to expect in the view
   * @return the new view
   */
  @CanIgnoreReturnValue
  private View assertDiff(View view1, Iterable<String> rawPaths)
      throws IncompatibleViewException, BrokenDiffAwarenessException, InterruptedException {
    Set<PathFragment> allPaths = new HashSet<>();
    for (String path : rawPaths) {
      allPaths.add(PathFragment.create(path));
    }
    Set<PathFragment> pathsYetToBeSeen = new HashSet<>(allPaths);

    // fsevents may be delayed (especially under machine load), which means that we may not notice
    // all file system changes in one go. Try enough times (multiple seconds) for the events to be
    // delivered. Given that each time we call getCurrentView we may get a subset of the total
    // events we expect, track the events we have already seen by subtracting them from the
    // pathsYetToBeSeen set.
    int attempts = 0;
    for (; ; ) {
      View view2 = underTest.getCurrentView(watchFsEnabledProvider);

      ModifiedFileSet diff = underTest.getDiff(view1, view2);
      // If fsevents lost events (e.g. because we weren't fast enough processing them or because
      // too many happened at the same time), there is nothing we can do. Yes, this means that if
      // our fsevents monitor always returns "everything modified", we aren't really testing
      // anything here... but let's assume we don't have such an obvious bug...
      assumeFalse("Lost events; diff unknown", diff.equals(ModifiedFileSet.EVERYTHING_MODIFIED));

      ImmutableSet<PathFragment> modifiedSourceFiles = diff.modifiedSourceFiles();
      allPaths.removeAll(modifiedSourceFiles);
      pathsYetToBeSeen.removeAll(modifiedSourceFiles);
      if (pathsYetToBeSeen.isEmpty()) {
        // Found all paths that we wanted to see as modified so now check that we didn't get any
        // extra paths we did not expect.
        if (!allPaths.isEmpty()) {
          throw new AssertionError("Paths " + allPaths + " unexpectedly reported as modified");
        }
        return view2;
      }

      if (attempts == 600) {
        throw new AssertionError("Paths " + pathsYetToBeSeen + " not found as modified");
      }
      logger.atInfo().log("Still have to see %d paths", pathsYetToBeSeen.size());
      Thread.sleep(100);
      attempts++;
      view1 = view2; // getDiff requires views to be sequential if we want to get meaningful data.
    }
  }

  @Test
  @Ignore("Test is flaky; see https://github.com/bazelbuild/bazel/issues/10776")
  public void testSimple() throws Exception {
    View view1 = underTest.getCurrentView(watchFsEnabledProvider);

    scratchDir("a/b");
    scratchFile("a/b/c");
    scratchDir("b/c");
    scratchFile("b/c/d");
    View view2 = assertDiff(view1, Arrays.asList("a", "a/b", "a/b/c", "b", "b/c", "b/c/d"));

    rmdirs(watchedPath.resolve("a"));
    rmdirs(watchedPath.resolve("b"));
    assertDiff(view2, Arrays.asList("a", "a/b", "a/b/c", "b", "b/c", "b/c/d"));
  }

  @Test
  @Ignore("Test is flaky; see https://github.com/bazelbuild/bazel/issues/10776")
  public void testRenameDirectory() throws Exception {
    scratchDir("dir1");
    scratchFile("dir1/file.c", "first");
    scratchDir("dir2");
    scratchFile("dir2/file.c", "second");
    View view1 = underTest.getCurrentView(watchFsEnabledProvider);

    Files.move(watchedPath.resolve("dir1"), watchedPath.resolve("dir3"));
    Files.move(watchedPath.resolve("dir2"), watchedPath.resolve("dir1"));
    assertDiff(
        view1, Arrays.asList("dir1", "dir1/file.c", "dir2", "dir2/file.c", "dir3", "dir3/file.c"));
  }

  @Test
  @Ignore("Test is flaky; see https://github.com/bazelbuild/bazel/issues/10776")
  public void testStress() throws Exception {
    View view1 = underTest.getCurrentView(watchFsEnabledProvider);

    // Attempt to cause fsevents to drop events by performing a lot of concurrent file accesses
    // which then may result in our own callback in fsevents.cc not being able to keep up.
    // There is no guarantee that we'll trigger this condition, but on 2020-02-28 on a Mac Pro
    // 2013, this happened pretty predictably with the settings below.
    logger.atInfo().log("Starting file creation under %s", watchedPath);
    ExecutorService executor = Executors.newCachedThreadPool();
    int nThreads = 100;
    int nFilesPerThread = 100;
    Multimap<String, String> dirToFilesToCreate = HashMultimap.create();
    for (int i = 0; i < nThreads; i++) {
      String dir = "" + i;
      for (int j = 0; j < nFilesPerThread; j++) {
        String file = dir + "/" + j;
        dirToFilesToCreate.put(dir, file);
      }
    }
    CountDownLatch latch = new CountDownLatch(nThreads);
    AtomicReference<IOException> firstError = new AtomicReference<>(null);
    dirToFilesToCreate
        .asMap()
        .forEach(
            (dir, files) -> {
              Future<?> unused =
                  executor.submit(
                      () -> {
                        try {
                          scratchDir(dir);
                          for (String file : files) {
                            scratchFile(file);
                          }
                        } catch (IOException e) {
                          firstError.compareAndSet(null, e);
                        }
                        latch.countDown();
                      });
            });
    latch.await();
    executor.shutdown();
    IOException e = firstError.get();
    if (e != null) {
      throw e;
    }

    assertDiff(view1, Iterables.concat(dirToFilesToCreate.keySet(), dirToFilesToCreate.values()));
  }
}
