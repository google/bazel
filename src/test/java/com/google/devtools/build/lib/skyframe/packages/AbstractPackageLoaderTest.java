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
package com.google.devtools.build.lib.skyframe.packages;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.server.FailureDetails.StarlarkLoading.Code.COMPILE_ERROR;
import static com.google.devtools.build.lib.testutil.MoreAsserts.assertContainsEvent;
import static com.google.devtools.build.lib.testutil.MoreAsserts.assertDoesNotContainEvents;
import static com.google.devtools.build.lib.testutil.MoreAsserts.assertNoEvents;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.skyframe.BzlLoadFailedException;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.packages.PackageLoader.LoadingContext;
import com.google.devtools.build.lib.skyframe.packages.PackageLoader.StarlarkModuleLoadingException;
import com.google.devtools.build.lib.util.ValueOrException;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.util.concurrent.ForkJoinPool;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.StarlarkSemantics;
import org.junit.Before;
import org.junit.Test;

/** Abstract base class of a unit test for a {@link AbstractPackageLoader} implementation. */
public abstract class AbstractPackageLoaderTest {
  protected Path workspaceDir;
  protected StoredEventHandler handler;
  protected FileSystem fs;
  protected Root root;
  private Reporter reporter;

  @Before
  public final void init() throws Exception {
    fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    workspaceDir = fs.getPath("/workspace/");
    workspaceDir.createDirectoryAndParents();
    root = Root.fromPath(workspaceDir);
    reporter = new Reporter(new EventBus());
    handler = new StoredEventHandler();
    reporter.addHandler(handler);
  }

  protected abstract AbstractPackageLoader.Builder newPackageLoaderBuilder(Root workspaceDir);

  protected AbstractPackageLoader.Builder newPackageLoaderBuilder() {
    return newPackageLoaderBuilder(root)
        .setStarlarkSemantics(
            StarlarkSemantics.builder()
                .set(BuildLanguageOptions.INCOMPATIBLE_AUTOLOAD_EXTERNALLY, ImmutableList.of())
                .build())
        .setCommonReporter(reporter);
  }

  protected abstract ForkJoinPool extractLegacyGlobbingForkJoinPool(PackageLoader packageLoader);

  protected PackageLoader newPackageLoader() {
    return newPackageLoaderBuilder().build();
  }

  @Test
  public void simpleNoPackage() {
    PackageIdentifier pkgId = PackageIdentifier.createInMainRepo(PathFragment.create("nope"));
    NoSuchPackageException expected;
    try (PackageLoader pkgLoader = newPackageLoader()) {
      expected = assertThrows(NoSuchPackageException.class, () -> pkgLoader.loadPackage(pkgId));
    }
    assertThat(expected)
        .hasMessageThat()
        .startsWith("no such package 'nope': BUILD file not found");
    assertNoEvents(handler.getEvents());
  }

  @Test
  public void simpleBadPackage() throws Exception {
    file("bad/BUILD", "invalidBUILDsyntax");
    PackageIdentifier pkgId = PackageIdentifier.createInMainRepo(PathFragment.create("bad"));
    Package badPkg;
    try (PackageLoader pkgLoader = newPackageLoader()) {
      badPkg = pkgLoader.loadPackage(pkgId);
    }
    assertThat(badPkg.containsErrors()).isTrue();
    assertContainsEvent(handler.getEvents(), "invalidBUILDsyntax");
  }

  @Test
  public void simpleGoodPackage() throws Exception {
    file("good/BUILD", "filegroup(name = 'good')");
    PackageIdentifier pkgId = PackageIdentifier.createInMainRepo(PathFragment.create("good"));
    Package goodPkg;
    try (PackageLoader pkgLoader = newPackageLoader()) {
      goodPkg = pkgLoader.loadPackage(pkgId);
    }
    assertThat(goodPkg.containsErrors()).isFalse();
    assertThat(goodPkg.getTarget("good").getAssociatedRule().getRuleClass()).isEqualTo("filegroup");
    assertNoEvents(handler.getEvents());
  }

  @Test
  public void simpleMultipleGoodPackage() throws Exception {
    file("good1/BUILD", "filegroup(name = 'good1')");
    file("good2/BUILD", "filegroup(name = 'good2')");
    PackageIdentifier pkgId1 = PackageIdentifier.createInMainRepo(PathFragment.create("good1"));
    PackageIdentifier pkgId2 = PackageIdentifier.createInMainRepo(PathFragment.create("good2"));
    ImmutableMap<PackageIdentifier, ValueOrException<Package, NoSuchPackageException>> pkgs;
    ImmutableList<Event> events;
    try (PackageLoader pkgLoader = newPackageLoader()) {
      PackageLoader.Result<PackageIdentifier, Package, NoSuchPackageException> result =
          pkgLoader.makeLoadingContext().loadPackages(ImmutableList.of(pkgId1, pkgId2));
      pkgs = result.getLoadedValues();
      events = result.getEvents();
    }

    assertThat(pkgs.get(pkgId1).get().containsErrors()).isFalse();
    assertThat(pkgs.get(pkgId2).get().containsErrors()).isFalse();
    assertThat(pkgs.get(pkgId1).get().getTarget("good1").getAssociatedRule().getRuleClass())
        .isEqualTo("filegroup");
    assertThat(pkgs.get(pkgId2).get().getTarget("good2").getAssociatedRule().getRuleClass())
        .isEqualTo("filegroup");

    assertNoEvents(events);
    assertNoEvents(handler.getEvents());
  }

  @Test
  public void testGoodAndBadAndMissingPackages() throws Exception {
    file("bad/BUILD", "invalidBUILDsyntax");
    PackageIdentifier badPkgId = PackageIdentifier.createInMainRepo(PathFragment.create("bad"));

    file("good/BUILD", "filegroup(name = 'good')");
    PackageIdentifier goodPkgId = PackageIdentifier.createInMainRepo(PathFragment.create("good"));

    PackageIdentifier missingPkgId = PackageIdentifier.createInMainRepo("missing");

    ImmutableMap<PackageIdentifier, ValueOrException<Package, NoSuchPackageException>> pkgs;
    ImmutableList<Event> events;
    try (PackageLoader pkgLoader = newPackageLoader()) {
      PackageLoader.Result<PackageIdentifier, Package, NoSuchPackageException> result =
          pkgLoader
              .makeLoadingContext()
              .loadPackages(ImmutableList.of(badPkgId, goodPkgId, missingPkgId));
      pkgs = result.getLoadedValues();
      events = result.getEvents();
    }

    Package goodPkg = pkgs.get(goodPkgId).get();
    assertThat(goodPkg.containsErrors()).isFalse();

    Package badPkg = pkgs.get(badPkgId).get();
    assertThat(badPkg.containsErrors()).isTrue();

    assertThrows(NoSuchPackageException.class, () -> pkgs.get(missingPkgId).get());

    assertContainsEvent(events, "invalidBUILDsyntax");
    assertContainsEvent(handler.getEvents(), "invalidBUILDsyntax");
  }

  @Test
  public void loadPackagesToleratesDuplicates() throws Exception {
    file("good1/BUILD", "filegroup(name = 'good1')");
    PackageIdentifier pkgId = PackageIdentifier.createInMainRepo(PathFragment.create("good1"));
    ImmutableMap<PackageIdentifier, ValueOrException<Package, NoSuchPackageException>> pkgs;
    ImmutableList<Event> events;
    try (PackageLoader pkgLoader = newPackageLoader()) {
      PackageLoader.Result<PackageIdentifier, Package, NoSuchPackageException> result =
          pkgLoader.makeLoadingContext().loadPackages(ImmutableList.of(pkgId, pkgId));
      pkgs = result.getLoadedValues();
      events = result.getEvents();
    }
    assertThat(pkgs.get(pkgId).get().containsErrors()).isFalse();
    assertThat(pkgs.get(pkgId).get().getTarget("good1").getAssociatedRule().getRuleClass())
        .isEqualTo("filegroup");
    assertNoEvents(events);
    assertNoEvents(handler.getEvents());
  }

  @Test
  public void simpleGoodPackage_Starlark() throws Exception {
    file(
        "good/good.bzl",
        """
        def f(x):
            native.filegroup(name = x)
        """);
    file(
        "good/BUILD",
        """
        load("//good:good.bzl", "f")

        f("good")
        """);
    PackageIdentifier pkgId = PackageIdentifier.createInMainRepo(PathFragment.create("good"));
    Package goodPkg;
    try (PackageLoader pkgLoader = newPackageLoader()) {
      goodPkg = pkgLoader.loadPackage(pkgId);
    }
    assertThat(goodPkg.containsErrors()).isFalse();
    assertThat(goodPkg.getTarget("good").getAssociatedRule().getRuleClass()).isEqualTo("filegroup");
    assertNoEvents(handler.getEvents());
  }

  @Test
  public void externalFile_SupportedByDefault() throws Exception {
    Path externalPath = file(absolutePath("/external/BUILD"), "filegroup(name = 'foo')");
    symlink("foo/BUILD", externalPath);
    PackageIdentifier pkgId = PackageIdentifier.createInMainRepo(PathFragment.create("foo"));
    Package fooPkg;
    try (PackageLoader pkgLoader = newPackageLoader()) {
      fooPkg = pkgLoader.loadPackage(pkgId);
    }
    assertThat(fooPkg.containsErrors()).isFalse();
    assertThat(fooPkg.getTarget("foo").getTargetKind()).isEqualTo("filegroup rule");
    assertNoEvents(handler.getEvents());
  }

  @Test
  public void externalFile_AssumeNonExistentAndImmutable() throws Exception {
    Path externalPath = file(absolutePath("/external/BUILD"), "filegroup(name = 'foo')");
    symlink("foo/BUILD", externalPath);
    PackageIdentifier pkgId = PackageIdentifier.createInMainRepo(PathFragment.create("foo"));
    NoSuchPackageException expected;
    try (PackageLoader pkgLoader =
        newPackageLoaderBuilder()
            .setExternalFileAction(
                ExternalFileAction.ASSUME_NON_EXISTENT_AND_IMMUTABLE_FOR_EXTERNAL_PATHS)
            .build()) {
      expected = assertThrows(NoSuchPackageException.class, () -> pkgLoader.loadPackage(pkgId));
    }
    assertThat(expected).hasMessageThat().contains("no such package 'foo': BUILD file not found");
  }

  @Test
  public void testNonPackageEventsReported() throws Exception {
    path("foo").createDirectoryAndParents();
    symlink("foo/infinitesymlinkpkg", path("foo/infinitesymlinkpkg/subdir"));
    PackageIdentifier pkgId = PackageIdentifier.createInMainRepo("foo/infinitesymlinkpkg");
    ImmutableMap<PackageIdentifier, ValueOrException<Package, NoSuchPackageException>> pkgs;
    ImmutableList<Event> events;
    try (PackageLoader pkgLoader = newPackageLoader()) {
      PackageLoader.Result<PackageIdentifier, Package, NoSuchPackageException> result =
          pkgLoader.makeLoadingContext().loadPackages(ImmutableList.of(pkgId));
      pkgs = result.getLoadedValues();
      events = result.getEvents();
    }
    assertThrows(NoSuchPackageException.class, () -> pkgs.get(pkgId).get());
    assertContainsEvent(events, "infinite symlink expansion detected");
  }

  @Test
  public void testClosesForkJoinPool() throws Exception {
    PackageLoader pkgLoader = newPackageLoader();
    ForkJoinPool forkJoinPool = extractLegacyGlobbingForkJoinPool(pkgLoader);
    assertThat(forkJoinPool.isShutdown()).isFalse();
    pkgLoader.close();
    assertThat(forkJoinPool.isShutdown()).isTrue();
  }

  @Test
  public void loadingContext_loadModules_basicFunctionality() throws Exception {
    file("x/BUILD");
    file(
        "x/foo.bzl",
        """
        '''Module foo'''

        load('//y:bar.bzl', 'bar')

        def foo(): bar()
        """);
    file("y/BUILD");
    file(
        "y/bar.bzl",
        """
        '''Module bar'''

        def bar(): pass
        """);
    Label fooLabel = Label.parseCanonicalUnchecked("//x:foo.bzl");
    Label barLabel = Label.parseCanonicalUnchecked("//y:bar.bzl");
    ImmutableMap<Label, ValueOrException<Module, StarlarkModuleLoadingException>> modules;
    ImmutableList<Event> events;
    try (PackageLoader pkgLoader = newPackageLoader()) {
      PackageLoader.Result<Label, Module, StarlarkModuleLoadingException> result =
          pkgLoader.makeLoadingContext().loadModules(ImmutableList.of(fooLabel, barLabel));
      modules = result.getLoadedValues();
      events = result.getEvents();
    }
    assertThat(modules.keySet()).containsExactly(fooLabel, barLabel);

    assertThat(modules.get(fooLabel).isPresent()).isTrue();
    assertThat(modules.get(fooLabel).get().getDocumentation()).isEqualTo("Module foo");
    assertThat(modules.get(fooLabel).get()).isSameInstanceAs(modules.get(fooLabel).getUnchecked());

    assertThat(modules.get(barLabel).isPresent()).isTrue();
    assertThat(modules.get(barLabel).get().getDocumentation()).isEqualTo("Module bar");
    assertThat(modules.get(barLabel).get()).isSameInstanceAs(modules.get(barLabel).getUnchecked());

    assertNoEvents(events);
  }

  @Test
  public void loadingContext_loadModules_failsOnBrokenModule() throws Exception {
    file("x/BUILD");
    file("x/foo.bzl", "syntax error");
    Label fooLabel = Label.parseCanonicalUnchecked("//x:foo.bzl");
    ImmutableMap<Label, ValueOrException<Module, StarlarkModuleLoadingException>> modules;
    ImmutableList<Event> events;
    try (PackageLoader pkgLoader = newPackageLoader()) {
      PackageLoader.Result<Label, Module, StarlarkModuleLoadingException> result =
          pkgLoader.makeLoadingContext().loadModules(ImmutableList.of(fooLabel));
      modules = result.getLoadedValues();
      events = result.getEvents();
    }

    assertThat(modules.keySet()).containsExactly(fooLabel);

    ValueOrException<Module, StarlarkModuleLoadingException> valueOrException =
        modules.get(fooLabel);
    assertThat(valueOrException.isPresent()).isFalse();
    StarlarkModuleLoadingException exception =
        assertThrows(StarlarkModuleLoadingException.class, valueOrException::get);
    assertThat(exception).hasMessageThat().contains("compilation of module 'x/foo.bzl' failed");
    assertThat(exception).hasCauseThat().isInstanceOf(BzlLoadFailedException.class);
    assertThat(exception.getFailureDetail().get().getStarlarkLoading().getCode())
        .isEqualTo(COMPILE_ERROR);
    IllegalStateException uncheckedException =
        assertThrows(IllegalStateException.class, valueOrException::getUnchecked);
    assertThat(uncheckedException).hasCauseThat().isEqualTo(exception);

    assertThat(handler.getEvents()).containsExactlyElementsIn(events);
    assertContainsEvent(events, "syntax error");
  }

  @Test
  public void loadingContext_loadModules_failsOnCycle() throws Exception {
    file("x/BUILD");
    file(
        "x/foo.bzl",
        """
        load("//y:bar.bzl", "bar")

        def foo(): return bar
        """);

    file("y/BUILD");
    file(
        "y/bar.bzl",
        """
        load("//x:foo.bzl", "foo")

        def bar(): return foo
        """);
    Label fooLabel = Label.parseCanonicalUnchecked("//x:foo.bzl");
    ImmutableMap<Label, ValueOrException<Module, StarlarkModuleLoadingException>> modules;
    ImmutableList<Event> events;
    try (PackageLoader pkgLoader = newPackageLoader()) {
      PackageLoader.Result<Label, Module, StarlarkModuleLoadingException> result =
          pkgLoader.makeLoadingContext().loadModules(ImmutableList.of(fooLabel));
      modules = result.getLoadedValues();
      events = result.getEvents();
    }

    assertThat(modules.keySet()).containsExactly(fooLabel);

    ValueOrException<Module, StarlarkModuleLoadingException> valueOrException =
        modules.get(fooLabel);
    assertThat(valueOrException.isPresent()).isFalse();
    StarlarkModuleLoadingException exception =
        assertThrows(StarlarkModuleLoadingException.class, valueOrException::get);
    assertThat(exception).hasMessageThat().contains("Cycle encountered while loading //x:foo.bzl");
    assertThat(exception).hasCauseThat().isNull();
    assertThat(exception.getFailureDetail())
        .isEmpty(); // TODO(b/331221948): we ought to define a failure detail for load() cycles
    IllegalStateException uncheckedException =
        assertThrows(IllegalStateException.class, valueOrException::getUnchecked);
    assertThat(uncheckedException).hasCauseThat().isEqualTo(exception);

    assertThat(events).isEmpty();
  }

  @Test
  public void loadingContext_resetsLoadedEvents() throws Exception {
    file("x/BUILD", "invalidSyntax_pkg_x");
    file("x/foo.bzl", "invalidSyntax_foo_bzl");
    file("y/BUILD", "invalidSyntax_pkg_y");
    file("y/bar.bzl", "invalidSyntax_bar_bzl");
    Label fooLabel = Label.parseCanonicalUnchecked("//x:foo.bzl");
    Label barLabel = Label.parseCanonicalUnchecked("//y:bar.bzl");
    ImmutableList<Event> eventsAfterLoadingFooBzl;
    ImmutableList<Event> eventsAfterLoadingPkgX;
    ImmutableList<Event> eventsAfterLoadingBarBzl;
    ImmutableList<Event> eventsAfterLoadingPkgY;
    try (PackageLoader pkgLoader = newPackageLoader()) {
      LoadingContext loadingContext = pkgLoader.makeLoadingContext();
      eventsAfterLoadingFooBzl = loadingContext.loadModules(ImmutableList.of(fooLabel)).getEvents();
      eventsAfterLoadingPkgX =
          loadingContext
              .loadPackages(
                  ImmutableList.of(PackageIdentifier.createInMainRepo(PathFragment.create("x"))))
              .getEvents();
      eventsAfterLoadingPkgY =
          loadingContext
              .loadPackages(
                  ImmutableList.of(PackageIdentifier.createInMainRepo(PathFragment.create("y"))))
              .getEvents();
      eventsAfterLoadingBarBzl = loadingContext.loadModules(ImmutableList.of(barLabel)).getEvents();
    }
    assertContainsEvent(eventsAfterLoadingFooBzl, "invalidSyntax_foo_bzl");
    assertDoesNotContainEvents(
        eventsAfterLoadingFooBzl,
        "invalidSyntax_pkg_x",
        "invalidSyntax_pkg_y",
        "invalidSyntax_bar_bzl");

    assertContainsEvent(eventsAfterLoadingPkgX, "invalidSyntax_pkg_x");
    assertDoesNotContainEvents(
        eventsAfterLoadingPkgX,
        "invalidSyntax_pkg_y",
        "invalidSyntax_foo_bzl",
        "invalidSyntax_bar_bzl");

    assertContainsEvent(eventsAfterLoadingPkgY, "invalidSyntax_pkg_y");
    assertDoesNotContainEvents(
        eventsAfterLoadingPkgY,
        "invalidSyntax_pkg_x",
        "invalidSyntax_foo_bzl",
        "invalidSyntax_bar_bzl");

    assertContainsEvent(eventsAfterLoadingBarBzl, "invalidSyntax_bar_bzl");
    assertDoesNotContainEvents(
        eventsAfterLoadingBarBzl,
        "invalidSyntax_pkg_x",
        "invalidSyntax_pkg_y",
        "invalidSyntax_foo_bzl");
  }

  @Test
  public void loadingContext_getRepositoryMapping_basicFunctionality() throws Exception {
    RepositoryMapping repositoryMapping;
    try (PackageLoader pkgLoader = newPackageLoader()) {
      LoadingContext loadingContext = pkgLoader.makeLoadingContext();
      repositoryMapping = loadingContext.getRepositoryMapping();
    }
    assertThat(repositoryMapping.get("")).isEqualTo(RepositoryName.MAIN);
    assertNoEvents(handler.getEvents());
  }

  protected Path path(String rootRelativePath) {
    return workspaceDir.getRelative(PathFragment.create(rootRelativePath));
  }

  protected Path absolutePath(String absolutePath) {
    return fs.getPath(absolutePath);
  }

  protected Path file(String fileName, String... contents) throws Exception {
    return file(path(fileName), contents);
  }

  protected Path file(Path path, String... contents) throws Exception {
    path.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(path, Joiner.on("\n").join(contents));
    return path;
  }

  protected Path symlink(String linkPathString, Path linkTargetPath) throws Exception {
    Path path = path(linkPathString);
    FileSystemUtils.ensureSymbolicLink(path, linkTargetPath);
    return path;
  }
}
