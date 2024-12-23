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
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.truth.TruthJUnit.assume;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.Subscribe;
import com.google.devtools.build.lib.actions.ChangedFilesMessage;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.buildtool.util.SkyframeIntegrationTestBase;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.WorkspaceBuilder;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.DelegateFileSystem;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.NotifyingHelper;
import com.google.devtools.common.options.OptionsBase;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for local diff awareness. A good place for general tests of Bazel's interactions with
 * "smart" filesystems, so that open-source changes don't break Google-internal features around
 * smart filesystems.
 */
@RunWith(JUnit4.class)
public class LocalDiffAwarenessIntegrationTest extends SkyframeIntegrationTestBase {
  private final Map<PathFragment, IOException> throwOnNextStatIfFound = new HashMap<>();

  @Override
  protected BlazeRuntime.Builder getRuntimeBuilder() throws Exception {
    return super.getRuntimeBuilder()
        .addBlazeModule(
            new BlazeModule() {
              @Override
              public void workspaceInit(
                  BlazeRuntime runtime, BlazeDirectories directories, WorkspaceBuilder builder) {
                builder.addDiffAwarenessFactory(new LocalDiffAwareness.Factory(ImmutableList.of()));
              }

              @Override
              public Iterable<Class<? extends OptionsBase>> getCommandOptions(Command command) {
                return ImmutableList.of(LocalDiffAwareness.Options.class);
              }
            });
  }

  @Override
  public FileSystem createFileSystem() throws Exception {
    return new DelegateFileSystem(super.createFileSystem()) {
      @Override
      protected FileStatus statIfFound(PathFragment path, boolean followSymlinks)
          throws IOException {
        IOException e = throwOnNextStatIfFound.remove(path);
        if (e != null) {
          throw e;
        }
        return super.statIfFound(path, followSymlinks);
      }
    };
  }

  @Before
  public void addOptions() {
    addOptions("--watchfs", "--experimental_windows_watchfs");
  }

  @After
  public void checkExceptionsThrown() {
    assertWithMessage("Injected exception(s) not thrown").that(throwOnNextStatIfFound).isEmpty();
  }

  @Test
  public void changedFile_detectsChange() throws Exception {
    // TODO(b/238606809): Understand why these tests are flaky on Mac. Probably real watchfs bug?
    assume().that(OS.getCurrent()).isNotEqualTo(OS.DARWIN);
    write("foo/BUILD", "genrule(name='foo', outs=['out'], cmd='echo hello > $@')");
    buildTarget("//foo");
    assertContents("hello", "//foo");
    write("foo/BUILD", "genrule(name='foo', outs=['out'], cmd='echo there > $@')");

    buildTargetWithRetryUntilSeesChange("//foo", "foo/BUILD");

    assertContents("there", "//foo");
  }

  @Test
  public void changedIgnoredFile_ignoresChange() throws Exception {
    // MacOSXFsEventsDiffAwareness doesn't currently support not registering
    // watches for ignored paths.
    assume().that(OS.getCurrent()).isNotEqualTo(OS.DARWIN);

    String notIgnoredFilePath = "foo/BUILD";
    String ignoredFilePath = "foo/ignored-dir/BUILD";

    write(".bazelignore", "foo/ignored-dir");

    write(ignoredFilePath, "");
    write(notIgnoredFilePath, "genrule(name='foo', outs=['out'], cmd='echo hello > $@')");
    buildTarget("//foo");
    assertContents("hello", "//foo");

    write(notIgnoredFilePath, "genrule(name='foo', outs=['out'], cmd='echo there > $@')");
    write(ignoredFilePath, "A = 1");

    AtomicBoolean ignoredFileChanged = new AtomicBoolean();
    AtomicBoolean notIgnoredFileChanged = new AtomicBoolean();
    runtimeWrapper.registerSubscriber(
        new Object() {
          @Subscribe
          private void onChangedFiles(ChangedFilesMessage changedFiles) {
            ignoredFileChanged.compareAndSet(
                false, changedFiles.changedFiles().contains(PathFragment.create(ignoredFilePath)));
            notIgnoredFileChanged.compareAndSet(
                false,
                changedFiles.changedFiles().contains(PathFragment.create(notIgnoredFilePath)));
          }
        });

    // Work around the inherent raciness of LocalDiffAwareness where the FS events are
    // delivered asynchronously and fast running test can trigger an incremental build
    // before the change is observed.
    for (int attempt = 0; attempt < 10; ++attempt) {
      buildTarget("//foo");
      if (notIgnoredFileChanged.get() && !ignoredFileChanged.get()) {
        assertContents("there", "//foo");
        return;
      }
    }

    if (!notIgnoredFileChanged.get()) {
      fail("Didn't observe file change within allowed number of retries");
    }
    if (ignoredFileChanged.get()) {
      fail("Observed ignored file change");
    }
  }

  @Test
  public void changedFile_statFails_throwsError() throws Exception {
    // TODO(b/238606809): Understand why these tests are flaky on Mac. Probably real watchfs bug?
    assume().that(OS.getCurrent()).isNotEqualTo(OS.DARWIN);
    write("foo/BUILD", "genrule(name='foo', outs=['out'], cmd='echo hello > $@')");
    buildTarget("//foo");
    assertContents("hello", "//foo");
    Path buildFile = write("foo/BUILD", "genrule(name='foo', outs=['out'], cmd='echo there > $@')");
    IOException injectedException = new IOException("oh no!");
    throwOnNextStatIfFound.put(buildFile.asFragment(), injectedException);

    AbruptExitException e =
        assertThrows(
            AbruptExitException.class,
            () -> buildTargetWithRetryUntilSeesChange("//foo", "foo/BUILD"));

    assertThat(e).hasCauseThat().hasCauseThat().hasCauseThat().isInstanceOf(IOException.class);
  }

  /**
   * Runs {@link #buildTarget(String...)} repeatedly until we observe a change for the given path.
   *
   * <p>This allows to work around the inherent raciness of {@code LocalDiffAwareness} where the FS
   * events are delivered asynchronously and fast running test can trigger an incremental build
   * before the change is observed.
   */
  private void buildTargetWithRetryUntilSeesChange(String target, String path) throws Exception {
    AtomicBoolean changed = new AtomicBoolean();
    runtimeWrapper.registerSubscriber(
        new Object() {
          @Subscribe
          private void onChangedFiles(ChangedFilesMessage changedFiles) {
            changed.compareAndSet(
                false, changedFiles.changedFiles().contains(PathFragment.create(path)));
          }
        });
    for (int attempt = 0; attempt < 10; ++attempt) {
      buildTarget(target);
      if (changed.get()) {
        return;
      }
    }
    fail("Didn't observe file change within allowed number of retries");
  }

  // This test doesn't use --watchfs functionality, but if the source filesystem doesn't offer diffs
  // Bazel must scan the full Skyframe graph anyway, so a bug in checking output files wouldn't be
  // detected without --watchfs.
  @Test
  public void ignoreOutputFilesThenCheckAgainDoesCheck() throws Exception {
    if ("bazel".equals(this.getRuntime().getProductName())) {
      // Repository options only in Bazel.
      addOptions("--noexperimental_check_external_repository_files");
    }
    Path buildFile =
        write(
            "foo/BUILD",
            "genrule(name = 'foo', outs = ['out'], cmd = 'cp $< $@', srcs = ['link'])");
    Path outputFile = directories.getOutputBase().getChild("linkTarget");
    FileSystemUtils.writeContent(outputFile, "one");
    buildFile.getParentDirectory().getChild("link").createSymbolicLink(outputFile.asFragment());

    buildTarget("//foo:foo");

    assertContents("one", "//foo:foo");

    addOptions("--noexperimental_check_output_files");
    FileSystemUtils.writeContent(outputFile, "two");

    buildTarget("//foo:foo");

    assertContents("one", "//foo:foo");

    addOptions("--experimental_check_output_files");

    buildTarget("//foo:foo");

    assertContents("two", "//foo:foo");
  }

  @Test
  public void externalSymlink_doesNotTriggerFullGraphTraversal() throws Exception {
    addOptions("--symlink_prefix=/");
    if ("bazel".equals(this.getRuntime().getProductName())) {
      // Repository options only in Bazel.
      addOptions("--noexperimental_check_external_repository_files");
    }
    AtomicInteger calledGetValues = new AtomicInteger(0);
    skyframeExecutor()
        .getEvaluator()
        .injectGraphTransformerForTesting(
            NotifyingHelper.makeNotifyingTransformer(
                (key, type, order, context) -> {
                  if (type == NotifyingHelper.EventType.GET_VALUES) {
                    calledGetValues.incrementAndGet();
                  }
                }));
    write(
        "hello/BUILD",
        "genrule(name='target', srcs = ['external'], outs=['out'], cmd='/bin/cat $(SRCS) > $@')");
    String externalLink = System.getenv("TEST_TMPDIR") + "/target";
    write(externalLink, "one");
    createSymlink(externalLink, "hello/external");

    // Trivial build: external symlink is not seen, so normal diff awareness is in play.
    buildTarget("//hello:BUILD");
    // New package path on first build triggers full-graph work.
    calledGetValues.set(0);
    // getValuesAndExceptions() called during output file checking (although if an output service is
    // able to report modified files in practice there is no iteration).

    buildTarget("//hello:BUILD");
    assertThat(calledGetValues.getAndSet(0)).isEqualTo(1);

    // Now bring the external symlink into Bazel's awareness.
    buildTarget("//hello:target");
    assertContents("one", "//hello:target");
    assertThat(calledGetValues.getAndSet(0)).isEqualTo(1);

    // Builds that follow a build containing an external file don't trigger a traversal.
    buildTarget("//hello:target");
    assertContents("one", "//hello:target");
    assertThat(calledGetValues.getAndSet(0)).isEqualTo(1);

    write(externalLink, "two");

    buildTarget("//hello:target");
    // External file changes are tracked.
    assertContents("two", "//hello:target");
    assertThat(calledGetValues.getAndSet(0)).isEqualTo(1);
  }
}
