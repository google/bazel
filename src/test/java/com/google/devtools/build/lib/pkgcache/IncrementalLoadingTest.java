// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.pkgcache;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileFunction;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleVisibility;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.packages.util.LoadingMock;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.runtime.QuiescingExecutorsImpl;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.skyframe.BrokenDiffAwarenessException;
import com.google.devtools.build.lib.skyframe.DiffAwareness;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingFunction;
import com.google.devtools.build.lib.skyframe.SequencedSkyframeExecutor;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.testutil.SkyframeExecutorTestHelper;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.Options;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsProvider;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for incremental loading; these cover both normal operation and diff awareness, for which a
 * list of modified / added / removed files is available.
 */
@RunWith(JUnit4.class)
public class IncrementalLoadingTest {
  protected PackageLoadingTester tester;

  private Path throwOnReaddir = null;

  @Before
  public final void createTester() throws Exception {
    ManualClock clock = new ManualClock();
    FileSystem fs =
        new InMemoryFileSystem(clock, DigestHashFunction.SHA256) {
          @Override
          public Collection<Dirent> readdir(PathFragment path, boolean followSymlinks)
              throws IOException {
            if (throwOnReaddir != null && throwOnReaddir.asFragment().equals(path)) {
              throw new FileNotFoundException(path.getPathString());
            }
            return super.readdir(path, followSymlinks);
          }
        };
    tester = createTester(fs, clock);
  }

  protected static PackageLoadingTester createTester(FileSystem fs, ManualClock clock)
      throws Exception {
    return new PackageLoadingTester(fs, clock);
  }

  @Test
  public void testNoChange() throws Exception {
    tester.addFile("base/BUILD",
        "filegroup(name = 'hello', srcs = ['foo.txt'])");
    tester.sync();
    Target oldTarget = tester.getTarget("//base:hello");
    assertThat(oldTarget).isNotNull();

    tester.sync();
    Target newTarget = tester.getTarget("//base:hello");
    assertThat(newTarget).isSameInstanceAs(oldTarget);
  }

  @Test
  public void testModifyBuildFile() throws Exception {
    tester.addFile("base/BUILD", "filegroup(name = 'hello', srcs = ['foo.txt'])");
    tester.sync();
    Target oldTarget = tester.getTarget("//base:hello");

    tester.modifyFile("base/BUILD", "filegroup(name = 'hello', srcs = ['bar.txt'])");
    tester.sync();
    Target newTarget = tester.getTarget("//base:hello");
    assertThat(newTarget).isNotSameInstanceAs(oldTarget);
  }

  @Test
  public void testModifyNonBuildFile() throws Exception {
    tester.addFile("base/BUILD", "filegroup(name = 'hello', srcs = ['foo.txt'])");
    tester.addFile("base/foo.txt", "nothing");
    tester.sync();
    Target oldTarget = tester.getTarget("//base:hello");

    tester.modifyFile("base/foo.txt", "other");
    tester.sync();
    Target newTarget = tester.getTarget("//base:hello");
    assertThat(newTarget).isSameInstanceAs(oldTarget);
  }

  @Test
  public void testRemoveNonBuildFile() throws Exception {
    tester.addFile("base/BUILD", "filegroup(name = 'hello', srcs = ['foo.txt'])");
    tester.addFile("base/foo.txt", "nothing");
    tester.sync();
    Target oldTarget = tester.getTarget("//base:hello");

    tester.removeFile("base/foo.txt");
    tester.sync();
    Target newTarget = tester.getTarget("//base:hello");
    assertThat(newTarget).isSameInstanceAs(oldTarget);
  }

  @Test
  public void testModifySymlinkedFileSamePackage() throws Exception {
    tester.addSymlink("base/BUILD", "mybuild");
    tester.addFile("base/mybuild", "filegroup(name = 'hello', srcs = ['foo.txt'])");
    tester.sync();
    Target oldTarget = tester.getTarget("//base:hello");
    tester.modifyFile("base/mybuild", "filegroup(name = 'hello', srcs = ['bar.txt'])");
    tester.sync();
    Target newTarget = tester.getTarget("//base:hello");
    assertThat(newTarget).isNotSameInstanceAs(oldTarget);
  }

  @Test
  public void testModifySymlinkedFileDifferentPackage() throws Exception {
    tester.addSymlink("base/BUILD", "../other/BUILD");
    tester.addFile("other/BUILD", "filegroup(name = 'hello', srcs = ['foo.txt'])");
    tester.sync();
    Target oldTarget = tester.getTarget("//base:hello");

    tester.modifyFile("other/BUILD", "filegroup(name = 'hello', srcs = ['bar.txt'])");
    tester.sync();
    Target newTarget = tester.getTarget("//base:hello");
    assertThat(newTarget).isNotSameInstanceAs(oldTarget);
  }

  @Test
  public void testBUILDSymlinkModifiedThenChanges() throws Exception {
    // We need to ensure that the timestamps of "one" and "two" are different, because Blaze
    // currently does not recognize changes to symlinks if the timestamps of the old and the new
    // file pointed to by the symlink are the same.
    tester.addFile("one", "filegroup(name='a', srcs=['1'])");
    tester.sync();

    tester.addFile("two", "filegroup(name='a', srcs=['2'])");
    tester.addSymlink("oldlink", "one");
    tester.addSymlink("newlink", "one");
    tester.addSymlink("a/BUILD", "../oldlink");
    tester.sync();
    Target a1 = tester.getTarget("//a:a");

    tester.modifySymlink("a/BUILD", "../newlink");
    tester.sync();

    tester.getTarget("//a:a");

    tester.modifySymlink("newlink", "two");
    tester.sync();

    Target a3 = tester.getTarget("//a:a");
    assertThat(a3).isNotSameInstanceAs(a1);
  }

  @Test
  public void testBUILDFileIsExternalSymlinkAndChanges() throws Exception {
    tester.addFile("/nonroot/file", "filegroup(name='a', srcs=['file'])");
    tester.addSymlink("a/BUILD", "/nonroot/file");
    tester.sync();

    Target a1 = tester.getTarget("//a:a");
    tester.modifyFile("/nonroot/file", "filegroup(name='a', srcs=['file2'])");
    tester.sync();

    Target a2 = tester.getTarget("//a:a");
    tester.sync();

    assertThat(a2).isNotSameInstanceAs(a1);
  }

  @Test
  public void testLabelWithTwoSegmentsAndTotalInvalidation() throws Exception {
    tester.addFile("a/BUILD", "filegroup(name='fg', srcs=['b/c'])");
    tester.addFile("a/b/BUILD");
    tester.sync();

    Target fg1 = tester.getTarget("//a:fg");
    tester.everythingModified();
    tester.sync();

    Target fg2 = tester.getTarget("//a:fg");
    assertThat(fg2).isSameInstanceAs(fg1);
  }

  @Test
  public void testAddGlobFile() throws Exception {
    tester.addFile("base/BUILD", "filegroup(name = 'hello', srcs = glob(['*.txt']))");
    tester.addFile("base/foo.txt", "nothing");
    tester.sync();
    Target oldTarget = tester.getTarget("//base:hello");

    tester.addFile("base/bar.txt", "also nothing");
    tester.sync();
    Target newTarget = tester.getTarget("//base:hello");
    assertThat(newTarget).isNotSameInstanceAs(oldTarget);
  }

  @Test
  public void testRemoveGlobFile() throws Exception {
    tester.addFile("base/BUILD", "filegroup(name = 'hello', srcs = glob(['*.txt']))");
    tester.addFile("base/foo.txt", "nothing");
    tester.addFile("base/bar.txt", "also nothing");
    tester.sync();
    Target oldTarget = tester.getTarget("//base:hello");

    tester.removeFile("base/bar.txt");
    tester.sync();
    Target newTarget = tester.getTarget("//base:hello");
    assertThat(newTarget).isNotSameInstanceAs(oldTarget);
  }

  @Test
  public void testPackageNotInLastBuildReplaced() throws Exception {
    tester.addFile("a/BUILD", "filegroup(name='a', srcs=['bad.sh'])");
    tester.sync();
    Target a1 = tester.getTarget("//a:a");

    tester.addFile("b/BUILD", "filegroup(name='b', srcs=['b.sh'])");
    tester.modifyFile("a/BUILD", "filegroup(name='a', srcs=['good.sh'])");
    tester.sync();
    tester.getTarget("//b:b");

    tester.sync();
    Target a2 = tester.getTarget("//a:a");
    assertThat(a2).isNotSameInstanceAs(a1);
  }

  @Test
  public void testBrokenSymlinkAddedThenFixed() throws Exception {
    tester.addFile("a/BUILD", "filegroup(name='a', srcs=glob(['**']))");
    tester.sync();
    Target a1 = tester.getTarget("//a:a");

    tester.addSymlink("a/b", "../c");
    tester.sync();
    tester.getTarget("//a:a");

    tester.addFile("c");
    tester.sync();
    Target a3 = tester.getTarget("//a:a");
    assertThat(a3).isNotSameInstanceAs(a1);
  }

  @Test
  public void testBuildFileWithSyntaxError() throws Exception {
    tester.addFile(
        "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(xyz='a')");
    tester.sync();
    assertThrows(NoSuchThingException.class, () -> tester.getTarget("//a:a"));

    tester.modifyFile(
        "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name='a')");
    tester.sync();
    tester.getTarget("//a:a");
  }

  @Test
  public void testSymlinkedBuildFileWithSyntaxError() throws Exception {
    tester.addFile(
        "a/BUILD.real",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(xyz='a')");
    tester.addSymlink("a/BUILD", "BUILD.real");
    tester.sync();
    assertThrows(NoSuchThingException.class, () -> tester.getTarget("//a:a"));
    tester.modifyFile(
        "a/BUILD.real",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name='a')");
    tester.sync();
    tester.getTarget("//a:a");
  }

  @Test
  public void testTransientErrorsInGlobbing() throws Exception {
    Path buildFile =
        tester.addFile(
            "e/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'e', srcs = glob(['*.txt']))");
    Path parentDir = buildFile.getParentDirectory();
    tester.addFile("e/data.txt");
    throwOnReaddir = parentDir;
    tester.sync();
    assertThrows(NoSuchPackageException.class, () -> tester.getTarget("//e:e"));
    throwOnReaddir = null;
    tester.sync();
    Target target = tester.getTarget("//e:e");
    assertThat(((Rule) target).containsErrors()).isFalse();
    List<?> globList = (List<?>) ((Rule) target).getAttr("srcs");
    assertThat(globList).containsExactly(Label.parseCanonical("//e:data.txt"));
  }

  @Test
  public void testIrrelevantFileInSubdirDoesntReloadPackage() throws Exception {
    tester.addFile(
        "pkg/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'pkg', srcs = glob(['**/*.sh']))");
    tester.addFile("pkg/pkg.sh", "#!/bin/bash");
    tester.addFile("pkg/bar/bar.sh", "#!/bin/bash");
    Package pkg = tester.getTarget("//pkg:pkg").getPackage();

    // Write file in directory to force reload of top-level glob.
    tester.addFile("pkg/irrelevant_file");
    tester.addFile("pkg/bar/irrelevant_file"); // Subglob is also reloaded.
    assertThat(tester.getTarget("//pkg:pkg").getPackage()).isSameInstanceAs(pkg);
  }

  @Test
  public void testMissingPackages() throws Exception {
    tester.sync();

    assertThrows(NoSuchThingException.class, () -> tester.getTarget("//a:a"));

    tester.addFile(
        "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name='a')");
    tester.sync();
    tester.getTarget("//a:a");
  }

  @Test
  public void testChangedExternalFile() throws Exception {
    tester.addFile(
        "a/BUILD",
        """
        load("//a:b.bzl", "b")

        b()
        """);

    tester.addFile(
        "/b.bzl",
        """
        def b():
            pass
        """);
    tester.addSymlink("a/b.bzl", "/b.bzl");
    tester.sync();
    tester.getTarget("//a:BUILD");
    tester.modifyFile("/b.bzl", "ERROR ERROR");
    tester.sync();

    assertThrows(NoSuchThingException.class, () -> tester.getTarget("//a:BUILD"));
  }

  static class PackageLoadingTester {
    private class ManualDiffAwareness implements DiffAwareness {
      private View lastView;
      private View currentView;

      @Override
      public View getCurrentView(OptionsProvider options) {
        lastView = currentView;
        currentView = new View() {};
        return currentView;
      }

      @Override
      public ModifiedFileSet getDiff(View oldView, View newView) {
        if (oldView == lastView && newView == currentView) {
          return Preconditions.checkNotNull(modifiedFileSet);
        } else {
          return ModifiedFileSet.EVERYTHING_MODIFIED;
        }
      }

      @Override
      public ModifiedFileSet getDiffFromEvaluatingVersion(OptionsProvider options, FileSystem fs)
          throws BrokenDiffAwarenessException {
        throw new UnsupportedOperationException("not implemented");
      }

      @Override
      public String name() {
        return "PackageLoadingTester.DiffAwareness";
      }

      @Override
      public void close() {
      }
    }

    private class ManualDiffAwarenessFactory implements DiffAwareness.Factory {
      @Nullable
      @Override
      public DiffAwareness maybeCreate(
          Root pathEntry, IgnoredSubdirectories ignoredPaths, OptionsProvider optionsProvider) {
        return pathEntry.asPath().equals(workspace) ? new ManualDiffAwareness() : null;
      }
    }

    private final ManualClock clock;
    private final Path workspace;
    private final Path outputBase;
    private final Reporter reporter = new Reporter(new EventBus());
    private final SkyframeExecutor skyframeExecutor;
    private final List<Path> changes = new ArrayList<>();
    private boolean everythingModified = false;
    private ModifiedFileSet modifiedFileSet;

    PackageLoadingTester(FileSystem fs, ManualClock clock)
        throws IOException, OptionsParsingException {
      this.clock = clock;
      workspace = fs.getPath("/workspace");
      workspace.createDirectory();
      addFile("test_defs/BUILD");
      addFile(
          "test_defs/foo_library.bzl",
          """
          def _impl(ctx):
            pass
          foo_library = rule(
            implementation = _impl,
            attrs = {
              "srcs": attr.label_list(allow_files=True),
              "deps": attr.label_list(),
            },
          )
          """);
      outputBase = fs.getPath("/output_base");
      outputBase.createDirectory();
      addFile("WORKSPACE");

      LoadingMock loadingMock = LoadingMock.get();
      BlazeDirectories directories =
          new BlazeDirectories(
              new ServerDirectories(
                  fs.getPath("/install"), fs.getPath("/output"), fs.getPath("/userRoot")),
              workspace,
              /* defaultSystemJavabase= */ null,
              loadingMock.getProductName());
      ConfiguredRuleClassProvider ruleClassProvider = loadingMock.createRuleClassProvider();
      PackageFactory pkgFactory =
          loadingMock
              .getPackageFactoryBuilderForTesting(directories)
              .setExtraSkyFunctions(
                  ImmutableMap.of(
                      SkyFunctions.MODULE_FILE,
                      new ModuleFileFunction(
                          ruleClassProvider.getBazelStarlarkEnvironment(),
                          directories.getWorkspace(),
                          ImmutableMap.of())))
              .build(ruleClassProvider, fs);
      skyframeExecutor =
          BazelSkyframeExecutorConstants.newBazelSkyframeExecutorBuilder()
              .setPkgFactory(pkgFactory)
              .setFileSystem(fs)
              .setDirectories(directories)
              .setActionKeyContext(new ActionKeyContext())
              .setDiffAwarenessFactories(ImmutableList.of(new ManualDiffAwarenessFactory()))
              .setSyscallCache(SyscallCache.NO_CACHE)
              .build();
      SkyframeExecutorTestHelper.process(skyframeExecutor);
      PackageOptions packageOptions = Options.getDefaults(PackageOptions.class);
      packageOptions.defaultVisibility = RuleVisibility.PUBLIC;
      packageOptions.showLoadingProgress = true;
      packageOptions.globbingThreads = 7;
      skyframeExecutor.injectExtraPrecomputedValues(
          ImmutableList.of(
              PrecomputedValue.injected(
                  RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE, Optional.empty()),
              PrecomputedValue.injected(
                  RepositoryDelegatorFunction.VENDOR_DIRECTORY, Optional.empty()),
              PrecomputedValue.injected(
                  RepositoryMappingFunction.REPOSITORY_OVERRIDES, ImmutableMap.of())));
      BuildLanguageOptions buildLanguageOptions = Options.getDefaults(BuildLanguageOptions.class);
      buildLanguageOptions.incompatibleAutoloadExternally = ImmutableList.of();
      skyframeExecutor.preparePackageLoading(
          new PathPackageLocator(
              outputBase,
              ImmutableList.of(Root.fromPath(workspace)),
              BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY),
          packageOptions,
          buildLanguageOptions,
          UUID.randomUUID(),
          ImmutableMap.of(),
          QuiescingExecutorsImpl.forTesting(),
          new TimestampGranularityMonitor(BlazeClock.instance()));
      skyframeExecutor.setActionEnv(ImmutableMap.of());
    }

    Path addFile(String fileName, String... content) throws IOException {
      Path buildFile = workspace.getRelative(fileName);
      Preconditions.checkState(!buildFile.exists());
      Path currentPath = buildFile;

      // Add the new file and all the directories that will be created by
      // createDirectoryAndParents()
      while (!currentPath.exists()) {
        changes.add(currentPath);
        currentPath = currentPath.getParentDirectory();
      }

      buildFile.getParentDirectory().createDirectoryAndParents();
      FileSystemUtils.writeContentAsLatin1(buildFile, Joiner.on('\n').join(content));
      return buildFile;
    }

    void addSymlink(String fileName, String target) throws IOException {
      Path path = workspace.getRelative(fileName);
      Preconditions.checkState(!path.exists());
      path.getParentDirectory().createDirectoryAndParents();
      path.createSymbolicLink(PathFragment.create(target));
      changes.add(path);
    }

    void removeFile(String fileName) throws IOException {
      Path path = workspace.getRelative(fileName);
      Preconditions.checkState(path.delete());
      changes.add(path);
    }

    void modifyFile(String fileName, String... content) throws IOException {
      Path path = workspace.getRelative(fileName);
      Preconditions.checkState(path.exists());
      Preconditions.checkState(path.delete());
      FileSystemUtils.writeContentAsLatin1(path, Joiner.on('\n').join(content));
      changes.add(path);
    }

    void modifySymlink(String fileName, String newTarget) throws IOException {
      Path symlink = workspace.getRelative(fileName);
      Preconditions.checkState(symlink.exists());
      symlink.delete();
      symlink.createSymbolicLink(PathFragment.create(newTarget));
      changes.add(symlink);
    }

    void everythingModified() {
      everythingModified = true;
    }

    private ModifiedFileSet getModifiedFileSet() {
      if (everythingModified) {
        everythingModified = false;
        return ModifiedFileSet.EVERYTHING_MODIFIED;
      }

      ModifiedFileSet.Builder builder = ModifiedFileSet.builder();
      for (Path path : changes) {
        if (!path.startsWith(workspace)) {
          continue;
        }

        PathFragment workspacePath = path.relativeTo(workspace);
        builder.modify(workspacePath);
      }
      return builder.build();
    }

    void sync() throws InterruptedException, AbruptExitException {
      clock.advanceMillis(1);

      modifiedFileSet = getModifiedFileSet();
      PackageOptions packageOptions = Options.getDefaults(PackageOptions.class);
      packageOptions.defaultVisibility = RuleVisibility.PUBLIC;
      packageOptions.showLoadingProgress = true;
      packageOptions.globbingThreads = 7;
      BuildLanguageOptions buildLanguageOptions = Options.getDefaults(BuildLanguageOptions.class);
      buildLanguageOptions.incompatibleAutoloadExternally = ImmutableList.of();
      skyframeExecutor.preparePackageLoading(
          new PathPackageLocator(
              outputBase,
              ImmutableList.of(Root.fromPath(workspace)),
              BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY),
          packageOptions,
          buildLanguageOptions,
          UUID.randomUUID(),
          ImmutableMap.of(),
          QuiescingExecutorsImpl.forTesting(),
          new TimestampGranularityMonitor(BlazeClock.instance()));
      skyframeExecutor.setActionEnv(ImmutableMap.of());
      skyframeExecutor.invalidateFilesUnderPathForTesting(
          new Reporter(new EventBus()), modifiedFileSet, Root.fromPath(workspace));
      ((SequencedSkyframeExecutor) skyframeExecutor)
          .handleDiffsForTesting(new Reporter(new EventBus()));

      changes.clear();
    }

    Target getTarget(String targetName)
        throws NoSuchPackageException, NoSuchTargetException, InterruptedException {
      Label label = Label.parseCanonicalUnchecked(targetName);
      return skyframeExecutor.getPackageManager().getTarget(reporter, label);
    }
  }
}
