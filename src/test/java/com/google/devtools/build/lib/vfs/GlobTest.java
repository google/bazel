// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.vfs;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.lib.vfs.util.TestUnixGlobPathDiscriminator;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests {@link UnixGlob} */
@RunWith(JUnit4.class)
public class GlobTest {

  private Path tmpPath;
  private FileSystem fs;
  private Path throwOnReaddir = null;
  private Path throwOnStat = null;

  @Before
  public final void initializeFileSystem() throws Exception {
    fs =
        new InMemoryFileSystem(DigestHashFunction.SHA256) {
          @Override
          public Collection<Dirent> readdir(PathFragment path, boolean followSymlinks)
              throws IOException {
            if (throwOnReaddir != null && throwOnReaddir.asFragment().equals(path)) {
              throw new FileNotFoundException(path.getPathString());
            }
            return super.readdir(path, followSymlinks);
          }

          @Override
          public FileStatus statIfFound(PathFragment path, boolean followSymlinks)
              throws IOException {
            if (throwOnStat != null && throwOnStat.asFragment().equals(path)) {
              throw new FileNotFoundException(path.getPathString());
            }
            return super.statIfFound(path, followSymlinks);
          }
        };
    tmpPath = fs.getPath("/globtmp");

    final ImmutableList<String> directories =
        ImmutableList.of(
            "foo/bar/wiz", "foo/barnacle/wiz", "food/barnacle/wiz", "fool/barnacle/wiz");

    for (String dir : directories) {
      tmpPath.getRelative(dir).createDirectoryAndParents();
    }
    FileSystemUtils.createEmptyFile(tmpPath.getRelative("foo/bar/wiz/file"));
  }

  @After
  public void resetInteruppt() {
    Thread.interrupted();
  }

  @Test
  public void testQuestionMarkMatch() throws Exception {
    assertGlobMatches("foo?", /* => */ "food", "fool");
  }

  @Test
  public void testQuestionMarkNoMatch() throws Exception {
    assertGlobMatches("food/bar?" /* => nothing */);
  }

  @Test
  public void testStartsWithStar() throws Exception {
    assertGlobMatches("*oo", /* => */ "foo");
  }

  @Test
  public void testStartsWithStarWithMiddleStar() throws Exception {
    assertGlobMatches("*f*o", /* => */ "foo");
  }

  @Test
  public void testEndsWithStar() throws Exception {
    assertGlobMatches("foo*", /* => */ "foo", "food", "fool");
  }

  @Test
  public void testEndsWithStarWithMiddleStar() throws Exception {
    assertGlobMatches("f*oo*", /* => */ "foo", "food", "fool");
  }

  @Test
  public void testMiddleStar() throws Exception {
    assertGlobMatches("f*o", /* => */ "foo");
  }

  @Test
  public void testTwoMiddleStars() throws Exception {
    assertGlobMatches("f*o*o", /* => */ "foo");
  }

  @Test
  public void testSingleStarPatternWithNamedChild() throws Exception {
    assertGlobMatches("*/bar", /* => */ "foo/bar");
  }

  @Test
  public void testSingleStarPatternWithChildGlob() throws Exception {
    assertGlobMatches(
        "*/bar*", /* => */ "foo/bar", "foo/barnacle", "food/barnacle", "fool/barnacle");
  }

  @Test
  public void testSingleStarAsChildGlob() throws Exception {
    assertGlobMatches("foo/*/wiz", /* => */ "foo/bar/wiz", "foo/barnacle/wiz");
  }

  @Test
  public void testNoAsteriskAndFilesDontExist() throws Exception {
    // Note un-UNIX like semantics:
    assertGlobMatches("ceci/n'est/pas/une/globbe" /* => nothing */);
  }

  @Test
  public void testSingleAsteriskUnderNonexistentDirectory() throws Exception {
    // Note un-UNIX like semantics:
    assertGlobMatches("not-there/*" /* => nothing */);
  }

  @Test
  public void testFilteredResults_noDirs() throws Exception {

    assertThat(
            new UnixGlob.Builder(tmpPath, SyscallCache.NO_CACHE)
                .addPatterns("**")
                .setPathDiscriminator(
                    new TestUnixGlobPathDiscriminator(
                        p -> /*traversalPredicate=*/ true,
                        /*resultPredicate=*/ (p, isDir) -> !isDir))
                .globInterruptible())
        .containsExactlyElementsIn(resolvePaths("foo/bar/wiz/file"));
  }

  @Test
  public void testFilteredResults_noFiles() throws Exception {
    assertThat(
            new UnixGlob.Builder(tmpPath, SyscallCache.NO_CACHE)
                .addPatterns("**")
                .setPathDiscriminator(
                    new TestUnixGlobPathDiscriminator(
                        /*traversalPredicate=*/ p -> true,
                        /*resultPredicate=*/ (p, isDir) -> isDir))
                .globInterruptible())
        .containsExactlyElementsIn(
            resolvePaths(
                "",
                "foo",
                "foo/bar",
                "foo/bar/wiz",
                "foo/barnacle",
                "foo/barnacle/wiz",
                "food",
                "food/barnacle",
                "food/barnacle/wiz",
                "fool",
                "fool/barnacle",
                "fool/barnacle/wiz"));
  }

  @Test
  public void testFilteredResults_pathMatch() throws Exception {

    Path wanted = tmpPath.getRelative("food/barnacle/wiz");

    assertThat(
            new UnixGlob.Builder(tmpPath, SyscallCache.NO_CACHE)
                .addPatterns("**")
                .setPathDiscriminator(
                    new TestUnixGlobPathDiscriminator(
                        /*traversalPredicate=*/ p -> true,
                        /*resultPredicate=*/ (path, isDir) -> path.equals(wanted)))
                .globInterruptible())
        .containsExactly(wanted);
  }

  @Test
  public void testTraversal_onlyFoo() throws Exception {
    // Use a directory traversal filter to only walk the root dir and "foo", but not "fool or "food"
    // So we'll end up the directories, "fool" and "food", but not sub-dirs.
    assertThat(
            new UnixGlob.Builder(tmpPath, SyscallCache.NO_CACHE)
                .addPatterns("**")
                .setPathDiscriminator(
                    new TestUnixGlobPathDiscriminator(
                        /*traversalPredicate=*/ path ->
                            path.equals(tmpPath)
                                || path.getPathString().contains("foo/")
                                || path.getPathString().endsWith("foo"),
                        /*resultPredicate=*/ (x, isDir) -> true))
                .globInterruptible())
        .containsExactlyElementsIn(
            resolvePaths(
                "",
                "foo",
                "foo/bar",
                "foo/bar/wiz",
                "foo/bar/wiz/file",
                "foo/barnacle",
                "foo/barnacle/wiz",
                "fool",
                "food"));
  }

  @Test
  public void testGlobWithNonExistentBase() throws Exception {
    Collection<Path> globResult =
        new UnixGlob.Builder(fs.getPath("/does/not/exist"), SyscallCache.NO_CACHE)
            .addPattern("*.txt")
            .globInterruptible();
    assertThat(globResult).isEmpty();
  }

  @Test
  public void testGlobUnderFile() throws Exception {
    assertGlobMatches("foo/bar/wiz/file/*" /* => nothing */);
  }

  private void assertGlobMatches(String pattern, String... expecteds) throws Exception {
    assertGlobMatches(Collections.singleton(pattern), expecteds);
  }

  private void assertGlobMatches(Collection<String> pattern, String... expecteds) throws Exception {
    assertThat(
            new UnixGlob.Builder(tmpPath, SyscallCache.NO_CACHE)
                .addPatterns(pattern)
                .globInterruptible())
        .containsExactlyElementsIn(resolvePaths(expecteds));
  }

  private Set<Path> resolvePaths(String... relativePaths) {
    Set<Path> expectedFiles = new HashSet<>();
    for (String expected : relativePaths) {
      Path file = expected.equals(".") ? tmpPath : tmpPath.getRelative(expected);
      expectedFiles.add(file);
    }
    return expectedFiles;
  }

  @Test
  public void testIOFailureOnStat() {
    SyscallCache syscallCache =
        new SyscallCache() {
          @Override
          public FileStatus statIfFound(Path path, Symlinks symlinks) throws IOException {
            throw new IOException("EIO");
          }

          @Override
          public Collection<Dirent> readdir(Path path) {
            throw new IllegalStateException();
          }

          @Override
          public DirentTypeWithSkip getType(Path path, Symlinks symlinks) {
            throw new IllegalStateException();
          }

          @Override
          public void clear() {
            throw new IllegalStateException();
          }
        };

    IOException e =
        assertThrows(
            IOException.class,
            () ->
                new UnixGlob.Builder(tmpPath, syscallCache).addPattern("foo/bar/wiz/file").glob());
    assertThat(e).hasMessageThat().isEqualTo("EIO");
  }

  @Test
  public void testGlobWithoutWildcardsDoesNotCallReaddir() throws Exception {
    SyscallCache syscallCache =
        new SyscallCache() {
          @Override
          public FileStatus statIfFound(Path path, Symlinks symlinks) throws IOException {
            return SyscallCache.NO_CACHE.statIfFound(path, symlinks);
          }

          @Override
          public Collection<Dirent> readdir(Path path) {
            throw new IllegalStateException();
          }

          @Override
          public DirentTypeWithSkip getType(Path path, Symlinks symlinks) {
            throw new IllegalStateException();
          }

          @Override
          public void clear() {
            throw new IllegalStateException();
          }
        };

    assertThat(new UnixGlob.Builder(tmpPath, syscallCache).addPattern("foo/bar/wiz/file").glob())
        .containsExactly(tmpPath.getRelative("foo/bar/wiz/file"));
  }

  @Test
  public void testIllegalPatterns() throws Exception {
    assertIllegalPattern("foo**bar");
    assertIllegalPattern("");
    assertIllegalPattern(".");
    assertIllegalPattern("/foo");
    assertIllegalPattern("./foo");
    assertIllegalPattern("foo/");
    assertIllegalPattern("foo/./bar");
    assertIllegalPattern("../foo/bar");
    assertIllegalPattern("foo//bar");
  }

  /** Tests that globs can contain Java regular expression special characters */
  @Test
  public void testSpecialRegexCharacter() throws Exception {
    Path tmpPath2 = fs.getPath("/globtmp2");
    tmpPath2.createDirectoryAndParents();
    Path aDotB = tmpPath2.getChild("a.b");
    FileSystemUtils.createEmptyFile(aDotB);
    Path aPlusB = tmpPath2.getChild("a+b");
    FileSystemUtils.createEmptyFile(aPlusB);
    Path aWordCharacterB = tmpPath2.getChild("a\\wb");
    FileSystemUtils.createEmptyFile(aWordCharacterB);
    Path disjunctionsAndBrackets = tmpPath2.getChild("aab|a{1,2}[ab]");
    FileSystemUtils.createEmptyFile(disjunctionsAndBrackets);
    Path lineNoise = tmpPath2.getChild("\\|}[{[].+");
    FileSystemUtils.createEmptyFile(lineNoise);
    FileSystemUtils.createEmptyFile(tmpPath2.getChild("aab"));
    // Note: these contain two asterisks because otherwise a RE is not built,
    // as an optimization.
    assertThat(
            new UnixGlob.Builder(tmpPath2, SyscallCache.NO_CACHE)
                .addPattern("*a.b*")
                .globInterruptible())
        .containsExactly(aDotB);
    assertThat(
            new UnixGlob.Builder(tmpPath2, SyscallCache.NO_CACHE)
                .addPattern("*a+b*")
                .globInterruptible())
        .containsExactly(aPlusB);
    assertThat(
            new UnixGlob.Builder(tmpPath2, SyscallCache.NO_CACHE)
                .addPattern("*a\\wb*")
                .globInterruptible())
        .containsExactly(aWordCharacterB);
    assertThat(
            new UnixGlob.Builder(tmpPath2, SyscallCache.NO_CACHE)
                .addPattern("*aab|a{1,2}[ab]*")
                .globInterruptible())
        .containsExactly(disjunctionsAndBrackets);
    assertThat(
            new UnixGlob.Builder(tmpPath2, SyscallCache.NO_CACHE)
                .addPattern("*\\|}[{[].+*")
                .globInterruptible())
        .containsExactly(lineNoise);
  }

  /**
   * Test that '(' and ')' in glob patterns are ignored if the glob is compiled to regexp.
   *
   * <p>TODO(b/154003471) Change the behavior and start treating '(' and ')' as literal characters
   * in glob patterns. This will require an incompatible flag.
   */
  @Test
  public void testParenthesesInRegex() throws Exception {
    Path tmpPath3 = fs.getPath("/globtmp3");
    tmpPath3.createDirectoryAndParents();
    Path fooBar = tmpPath3.getChild("foo bar");
    FileSystemUtils.createEmptyFile(fooBar);
    Path fooBarInParentheses = tmpPath3.getChild("foo (bar)");
    FileSystemUtils.createEmptyFile(fooBarInParentheses);
    // Note: these contain two asterisks because otherwise a RE is not built,
    // as an optimization.
    assertThat(
            new UnixGlob.Builder(tmpPath3, SyscallCache.NO_CACHE)
                .addPattern("*foo (bar)*")
                .globInterruptible())
        .containsExactly(fooBar);
    assertThat(
            new UnixGlob.Builder(tmpPath3, SyscallCache.NO_CACHE)
                .addPattern("(*foo bar*)")
                .globInterruptible())
        .containsExactly(fooBar);
    assertThat(
            new UnixGlob.Builder(tmpPath3, SyscallCache.NO_CACHE)
                .addPattern("*)((foo ))bar(*")
                .globInterruptible())
        .containsExactly(fooBar);
    assertThat(
            new UnixGlob.Builder(tmpPath3, SyscallCache.NO_CACHE)
                .addPattern("*foo (bar*")
                .globInterruptible())
        .containsExactly(fooBar);
    assertThat(
            new UnixGlob.Builder(tmpPath3, SyscallCache.NO_CACHE)
                .addPattern("*foo bar*)")
                .globInterruptible())
        .containsExactly(fooBar);
    // Note: the following glob pattern doesn't contain asterisks, and a RE wouldn't be expected to
    // be built.
    assertThat(
            new UnixGlob.Builder(tmpPath3, SyscallCache.NO_CACHE)
                .addPattern("foo (bar)")
                .globInterruptible())
        .containsExactly(fooBarInParentheses);
  }

  @Test
  public void testMatchesCallWithNoCache() {
    assertThat(UnixGlob.matches("*a*b", "CaCb", null)).isTrue();
  }

  @Test
  public void testMultiplePatterns() throws Exception {
    assertGlobMatches(Lists.newArrayList("foo", "fool"), "foo", "fool");
  }

  @Test
  public void testMatcherMethodRecursiveBelowDir() throws Exception {
    FileSystemUtils.createEmptyFile(tmpPath.getRelative("foo/file"));
    String pattern = "foo/**/*";
    assertThat(UnixGlob.matches(pattern, "foo/bar")).isTrue();
    assertThat(UnixGlob.matches(pattern, "foo/bar/baz")).isTrue();
    assertThat(UnixGlob.matches(pattern, "foo")).isFalse();
    assertThat(UnixGlob.matches(pattern, "foob")).isFalse();
    assertThat(UnixGlob.matches("**/foo", "foo")).isTrue();
  }

  @Test
  public void testMultiplePatternsWithOverlap() throws Exception {
    assertGlobMatchesAnyOrder(Lists.newArrayList("food", "foo?"), "food", "fool");
    assertGlobMatchesAnyOrder(Lists.newArrayList("food", "?ood", "f??d"), "food");
    assertThat(resolvePaths("food", "fool", "foo"))
        .containsExactlyElementsIn(
            new UnixGlob.Builder(tmpPath, SyscallCache.NO_CACHE)
                .addPatterns("food", "xxx", "*")
                .glob());
  }

  private void assertGlobMatchesAnyOrder(ArrayList<String> patterns, String... paths)
      throws Exception {
    assertThat(resolvePaths(paths))
        .containsExactlyElementsIn(
            new UnixGlob.Builder(tmpPath, SyscallCache.NO_CACHE)
                .addPatterns(patterns)
                .globInterruptible());
  }

  private void assertIllegalPattern(String pattern) throws Exception {
    UnixGlob.BadPattern e =
        assertThrows(
            UnixGlob.BadPattern.class,
            () ->
                new UnixGlob.Builder(tmpPath, SyscallCache.NO_CACHE)
                    .addPattern(pattern)
                    .globInterruptible());
    assertThat(e).hasMessageThat().containsMatch("in glob pattern");
  }

  @Test
  public void testHiddenFiles() throws Exception {
    for (String dir : ImmutableList.of(".hidden", "..also.hidden", "not.hidden")) {
      tmpPath.getRelative(dir).createDirectoryAndParents();
    }

    // Note that these are not in the result: ".", ".."
    assertGlobMatches("*", "not.hidden", "foo", "fool", "food", ".hidden", "..also.hidden");

    assertGlobMatches("*.hidden", "not.hidden");

    assertGlobMatches(".*also*", "..also.hidden");
  }

  @Test
  public void testIOException() throws Exception {
    throwOnReaddir = fs.getPath("/throw_on_readdir");
    throwOnReaddir.createDirectory();
    assertThrows(
        IOException.class,
        () -> new UnixGlob.Builder(throwOnReaddir, SyscallCache.NO_CACHE).addPattern("**").glob());
  }

  @Test
  public void testFastFailureithInterrupt() throws Exception {
    Thread.currentThread().interrupt();
    throwOnStat = tmpPath;
    FileNotFoundException e =
        assertThrows(
            FileNotFoundException.class,
            () -> new UnixGlob.Builder(tmpPath, SyscallCache.NO_CACHE).glob());
    assertThat(e).hasMessageThat().contains("globtmp");
  }

  @Test
  public void testCheckCanBeInterrupted() throws Exception {
    final Thread mainThread = Thread.currentThread();
    final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(10);

    var interruptExactlyOnce = new AtomicBoolean(false);
    // Ensures the cancellation occurs while the glob is running.
    var waitInPredicate = new CountDownLatch(1);

    Predicate<Path> interrupterPredicate =
        new Predicate<Path>() {
          @Override
          public boolean test(Path input) {
            if (interruptExactlyOnce.compareAndSet(false, true)) {
              mainThread.interrupt();
            } else {
              try {
                assertThat(waitInPredicate.await(TestUtils.WAIT_TIMEOUT_SECONDS, SECONDS)).isTrue();
              } catch (InterruptedException e) {
                throw new AssertionError(e);
              }
            }
            return true;
          }
        };

    UnixGlobPathDiscriminator interrupterDiscriminator =
        new TestUnixGlobPathDiscriminator(
            /*traversalPredicate=*/ interrupterPredicate, /*resultPredicate=*/ (x, isDir) -> true);

    Future<?> globResult =
        new UnixGlob.Builder(tmpPath, SyscallCache.NO_CACHE)
            .addPattern("**")
            .setPathDiscriminator(interrupterDiscriminator)
            .setExecutor(executor)
            .globAsync();
    assertThrows(InterruptedException.class, () -> globResult.get());

    globResult.cancel(true);
    waitInPredicate.countDown();

    assertThrows(
        CancellationException.class, () -> Uninterruptibles.getUninterruptibly(globResult));

    Thread.interrupted();
    assertThat(executor.isShutdown()).isFalse();
    executor.shutdown();
    assertThat(executor.awaitTermination(TestUtils.WAIT_TIMEOUT_SECONDS, SECONDS)).isTrue();
  }

  @Test
  public void testCheckCannotBeInterrupted() throws Exception {
    final Thread mainThread = Thread.currentThread();
    final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(10);
    final AtomicBoolean sentInterrupt = new AtomicBoolean(false);

    Predicate<Path> interrupterPredicate =
        new Predicate<Path>() {
          @Override
          public boolean test(Path input) {
            if (!sentInterrupt.getAndSet(true)) {
              mainThread.interrupt();
            }
            return true;
          }
        };

    UnixGlobPathDiscriminator interrupterDiscriminator =
        new TestUnixGlobPathDiscriminator(
            /*traversalPredicate=*/ interrupterPredicate, /*resultPredicate=*/ (x, isDir) -> true);

    List<Path> result =
        new UnixGlob.Builder(tmpPath, SyscallCache.NO_CACHE)
            .addPatterns("**", "*")
            .setPathDiscriminator(interrupterDiscriminator)
            .setExecutor(executor)
            .glob();

    // In the non-interruptible case, the interrupt bit should be set, but the
    // glob should return the correct set of full results.
    assertThat(Thread.interrupted()).isTrue();
    assertThat(result)
        .containsExactlyElementsIn(
            resolvePaths(
                ".",
                "foo",
                "foo/bar",
                "foo/bar/wiz",
                "foo/bar/wiz/file",
                "foo/barnacle",
                "foo/barnacle/wiz",
                "food",
                "food/barnacle",
                "food/barnacle/wiz",
                "fool",
                "fool/barnacle",
                "fool/barnacle/wiz"));

    assertThat(executor.isShutdown()).isFalse();
    executor.shutdown();
    assertThat(executor.awaitTermination(TestUtils.WAIT_TIMEOUT_SECONDS, SECONDS)).isTrue();
  }

  private static Collection<String> removeExcludes(ImmutableList<String> paths, String... excludes)
      throws UnixGlob.BadPattern {
    HashSet<String> pathSet = new HashSet<>(paths);
    UnixGlob.removeExcludes(pathSet, ImmutableList.copyOf(excludes));
    return pathSet;
  }

  @Test
  public void testExcludeFiltering() throws UnixGlob.BadPattern {
    ImmutableList<String> paths = ImmutableList.of("a/A.java", "a/B.java", "a/b/C.java", "c.cc");
    assertThat(removeExcludes(paths, "**/*.java")).containsExactly("c.cc");
    assertThat(removeExcludes(paths, "a/**/*.java")).containsExactly("c.cc");
    assertThat(removeExcludes(paths, "**/nomatch.*")).containsAtLeastElementsIn(paths);
    assertThat(removeExcludes(paths, "a/A.java")).containsExactly("a/B.java", "a/b/C.java", "c.cc");
    assertThat(removeExcludes(paths, "a/?.java")).containsExactly("a/b/C.java", "c.cc");
    assertThat(removeExcludes(paths, "a/*/C.java")).containsExactly("a/A.java", "a/B.java", "c.cc");
    assertThat(removeExcludes(paths, "**")).isEmpty();
    assertThat(removeExcludes(paths, "**/**")).isEmpty();

    // Test filenames that look like code patterns.
    paths = ImmutableList.of("a/A.java", "a/B.java", "a/b/*.java", "a/b/C.java", "c.cc");
    assertThat(removeExcludes(paths, "**/*.java")).containsExactly("c.cc");
    assertThat(removeExcludes(paths, "**/A.java", "**/B.java", "**/C.java"))
        .containsExactly("a/b/*.java", "c.cc");
  }
}
