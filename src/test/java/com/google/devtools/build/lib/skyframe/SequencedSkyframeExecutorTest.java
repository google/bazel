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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.devtools.build.lib.actions.util.ActionCacheTestHelper.AMNESIAC_CACHE;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.NULL_ACTION_OWNER;
import static com.google.devtools.build.lib.rules.python.PythonTestUtils.getPyLoad;
import static com.google.devtools.build.lib.testutil.MoreAsserts.assertEventCount;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.hash.HashCode;
import com.google.common.testing.GcFinalization;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionCacheChecker;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionExecutionStatusReporter;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.ActionOutputDirectoryHelper;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.ActionTemplate;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.BasicActionLookupValue;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.actions.OutputChecker;
import com.google.devtools.build.lib.actions.ResourceManager;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.actions.util.DummyExecutor;
import com.google.devtools.build.lib.actions.util.InjectedActionLookupKey;
import com.google.devtools.build.lib.actions.util.TestAction;
import com.google.devtools.build.lib.actions.util.TestAction.DummyAction;
import com.google.devtools.build.lib.analysis.AnalysisOptions;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.buildtool.BuildRequestOptions;
import com.google.devtools.build.lib.buildtool.SkyframeBuilder;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventCollector;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.pkgcache.LoadedPackageProvider;
import com.google.devtools.build.lib.pkgcache.PackageManager;
import com.google.devtools.build.lib.pkgcache.PackageOptions;
import com.google.devtools.build.lib.query2.common.QueryTransitivePackagePreloader;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.runtime.QuiescingExecutorsImpl;
import com.google.devtools.build.lib.server.FailureDetails.Crash;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Spawn;
import com.google.devtools.build.lib.server.FailureDetails.Spawn.Code;
import com.google.devtools.build.lib.skyframe.FilesystemValueChecker.XattrProviderOverrider;
import com.google.devtools.build.lib.skyframe.SkyframeActionExecutor.ActionCompletedReceiver;
import com.google.devtools.build.lib.skyframe.SkyframeActionExecutor.ProgressSupplier;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TopLevelTargetBuiltEvent;
import com.google.devtools.build.lib.skyframe.serialization.DeserializationContext;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.testutil.MoreAsserts;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.CrashFailureDetails;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.LocalOutputService;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.DeterministicHelper;
import com.google.devtools.build.skyframe.Differencer.Diff;
import com.google.devtools.build.skyframe.Differencer.DiffWithDelta.Delta;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.GraphTester;
import com.google.devtools.build.skyframe.NotifyingHelper;
import com.google.devtools.build.skyframe.NotifyingHelper.EventType;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.TrackingAwaiter;
import com.google.devtools.build.skyframe.ValueWithMetadata;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsProvider;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.IOException;
import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link SequencedSkyframeExecutor}. */
@RunWith(TestParameterInjector.class)
public final class SequencedSkyframeExecutorTest extends BuildViewTestCase {

  private static final DetailedExitCode USER_DETAILED_EXIT_CODE =
      DetailedExitCode.of(
          FailureDetail.newBuilder()
              .setSpawn(Spawn.newBuilder().setCode(Code.NON_ZERO_EXIT))
              .build());
  private static final DetailedExitCode INFRA_DETAILED_EXIT_CODE =
      DetailedExitCode.of(
          FailureDetail.newBuilder()
              .setCrash(Crash.newBuilder().setCode(Crash.Code.CRASH_UNKNOWN))
              .build());

  private final OptionsParser options =
      OptionsParser.builder()
          .optionsClasses(
              AnalysisOptions.class,
              BuildLanguageOptions.class,
              BuildRequestOptions.class,
              CoreOptions.class,
              KeepGoingOption.class,
              PackageOptions.class)
          .build();
  private final Map<SkyFunctionName, SkyFunction> extraSkyFunctions = new HashMap<>();
  private QueryTransitivePackagePreloader visitor;

  @Before
  public void createVisitorAndParseOptions() throws Exception {
    visitor = skyframeExecutor.getQueryTransitivePackagePreloader();
    options.parse("--jobs=20");
  }

  @Before
  public void setOutputService() {
    skyframeExecutor.setOutputService(new LocalOutputService(directories));
  }

  @Override
  protected AnalysisMock getAnalysisMock() {
    AnalysisMock delegate = super.getAnalysisMock();
    return new AnalysisMock.Delegate(delegate) {
      @Override
      public ImmutableMap<SkyFunctionName, SkyFunction> getSkyFunctions(
          BlazeDirectories directories) {
        return ImmutableMap.<SkyFunctionName, SkyFunction>builder()
            .putAll(delegate.getSkyFunctions(directories))
            .putAll(extraSkyFunctions)
            .buildOrThrow();
      }
    };
  }

  private static class TopLevelTargetBuiltEventCollector {
    private final Set<TopLevelTargetBuiltEvent> collectedEvents = new HashSet<>();

    @Subscribe
    void collect(TopLevelTargetBuiltEvent e) {
      collectedEvents.add(e);
    }

    private ImmutableSet<TopLevelTargetBuiltEvent> getCollectedEvents() {
      return ImmutableSet.copyOf(collectedEvents);
    }
  }

  @Test
  public void testChangeFile() throws Exception {
    analysisMock.pySupport().setup(mockToolsConfig);
    skyframeExecutor.invalidateFilesUnderPathForTesting(
        reporter, ModifiedFileSet.EVERYTHING_MODIFIED, Root.fromPath(rootDirectory));

    String pathString = rootDirectory + "/python/hello/BUILD";
    scratch.file(
        pathString, getPyLoad("py_binary"), "py_binary(name = 'hello', srcs = ['hello.py'])");

    // A dummy file that is never changed.
    scratch.file(rootDirectory + "/misc/BUILD", "filegroup(name = 'misc', srcs = ['hello.sh'])");

    sync("//python/hello:hello", "//misc:misc");

    // No changes yet.
    assertThat(dirtyValues()).isEmpty();

    // Make a change.
    scratch.overwriteFile(
        pathString,
        getPyLoad("py_binary"),
        "py_binary(name = 'hello', srcs = ['something_else.py'])");
    assertThat(dirtyValues())
        .containsExactly(
            FileStateValue.key(
                RootedPath.toRootedPath(
                    Root.fromPath(rootDirectory), PathFragment.create("python/hello/BUILD"))));

    // The method will continue returning the value until we invalidate it and re-evaluate.
    assertThat(dirtyValues()).hasSize(1);
    skyframeExecutor.invalidateFilesUnderPathForTesting(
        reporter,
        ModifiedFileSet.builder().modify(PathFragment.create("python/hello/BUILD")).build(),
        Root.fromPath(rootDirectory));
    sync("//python/hello:hello");
    assertThat(dirtyValues()).isEmpty();
  }

  // Regression for b/13328517. clearAnalysisCache() method is call when --discard_analysis_cache
  // is used. This saves about 10% of the memory during execution.
  @Test
  public void testClearAnalysisCache() throws Exception {
    skyframeExecutor.setEventBus(new EventBus());
    scratch.file(
        rootDirectory + "/discard/BUILD",
        "genrule(name='x', srcs=['input'], outs=['out'], cmd='false')");
    scratch.file(rootDirectory + "/discard/input", "foo");

    ConfiguredTarget ct =
        skyframeExecutor.getConfiguredTargetForTesting(
            reporter, Label.parseCanonical("@//discard:x"), getTargetConfiguration());
    assertThat(ct).isNotNull();
    WeakReference<ConfiguredTarget> ref = new WeakReference<>(ct);
    ct = null;
    // Allow all values to be cleared by passing in empty set of top-level values, since we're not
    // actually building.
    skyframeExecutor.clearAnalysisCache(ImmutableSet.of(), ImmutableSet.of());
    GcFinalization.awaitClear(ref);
  }

  @Test
  public void testChangeDirectory() throws Exception {
    analysisMock.pySupport().setup(mockToolsConfig);
    skyframeExecutor.invalidateFilesUnderPathForTesting(
        reporter, ModifiedFileSet.EVERYTHING_MODIFIED, Root.fromPath(rootDirectory));

    scratch.file(
        "python/hello/BUILD",
        getPyLoad("py_binary"),
        "py_binary(name = 'hello', srcs = ['hello.py'], data = glob(['*.txt']))");
    scratch.file("python/hello/foo.txt", "foo");

    // A dummy directory that is not changed.
    scratch.file(
        "misc/BUILD",
        getPyLoad("py_binary"),
        "py_binary(name = 'misc', srcs = ['other.py'], data = glob(['*.txt'], allow_empty ="
            + " True))");

    sync("//python/hello:hello", "//misc:misc");

    // No changes yet.
    assertThat(dirtyValues()).isEmpty();

    // Make a change.
    scratch.file("python/hello/bar.txt", "bar");
    assertThat(dirtyValues())
        .containsExactly(
            DirectoryListingStateValue.key(
                RootedPath.toRootedPath(
                    Root.fromPath(rootDirectory), PathFragment.create("python/hello"))));

    // The method will continue returning the value until we invalidate it and re-evaluate.
    assertThat(dirtyValues()).hasSize(1);
    skyframeExecutor.invalidateFilesUnderPathForTesting(
        reporter,
        ModifiedFileSet.builder().modify(PathFragment.create("python/hello/bar.txt")).build(),
        Root.fromPath(rootDirectory));
    sync("//python/hello:hello");
    assertThat(dirtyValues()).isEmpty();
  }

  @Test
  public void sync_onlyExternalFileChanged_reportsAffectedFile() throws Exception {
    Root externalRoot = Root.fromPath(scratch.dir("/external"));
    RootedPath file = RootedPath.toRootedPath(externalRoot, scratch.file("/external/file"));
    initializeSkyframeExecutor(
        /* doPackageLoadingChecks= */ true, ImmutableList.of(nothingChangedDiffAwarenessFactory()));
    skyframeExecutor
        .injectable()
        .inject(
            file,
            Delta.justNew(FileStateValue.create(file, SyscallCache.NO_CACHE, /* tsgm= */ null)));
    skyframeExecutor.externalFilesHelper.getAndNoteFileType(file);
    // Initial sync to establish the baseline DiffAwareness.View.
    skyframeExecutor.handleDiffsForTesting(NullEventHandler.INSTANCE);
    scratch.overwriteFile("/external/file", "new content");

    syncSkyframeExecutor();

    Diff diff = getRecordedDiff();
    assertThat(diff.changedKeysWithNewValues()).containsKey(file);
  }

  @Test
  public void sync_nothingChangedWithExternalFile_reportsNoExternalKeysInDiff() throws Exception {
    Root externalRoot = Root.fromPath(scratch.dir("/external"));
    RootedPath file = RootedPath.toRootedPath(externalRoot, scratch.file("/external/file"));
    initializeSkyframeExecutor(
        /* doPackageLoadingChecks= */ true, ImmutableList.of(nothingChangedDiffAwarenessFactory()));
    skyframeExecutor
        .injectable()
        .inject(
            file,
            Delta.justNew(FileStateValue.create(file, SyscallCache.NO_CACHE, /* tsgm= */ null)));
    skyframeExecutor.externalFilesHelper.getAndNoteFileType(file);
    // Initial sync to establish the baseline DiffAwareness.View.
    skyframeExecutor.handleDiffsForTesting(NullEventHandler.INSTANCE);

    syncSkyframeExecutor();

    Diff diff = getRecordedDiff();
    assertThat(diff.changedKeysWithoutNewValues()).doesNotContain(file);
    assertThat(diff.changedKeysWithNewValues()).doesNotContainKey(file);
  }

  @Test
  public void sync_onlyExternalListingChanged_reportsAffectedListing() throws Exception {
    Root externalRoot = Root.fromPath(scratch.dir("/external"));
    RootedPath dir = RootedPath.toRootedPath(externalRoot, scratch.dir("/external/foo"));
    DirectoryListingStateValue value =
        DirectoryListingStateValue.create(dir.asPath().readdir(Symlinks.NOFOLLOW));
    DirectoryListingStateValue.Key dirListingKey = DirectoryListingStateValue.key(dir);
    initializeSkyframeExecutor(
        /* doPackageLoadingChecks= */ true, ImmutableList.of(nothingChangedDiffAwarenessFactory()));
    skyframeExecutor
        .injectable()
        .inject(
            ImmutableMap.of(
                dir,
                Delta.justNew(FileStateValue.create(dir, SyscallCache.NO_CACHE, /* tsgm= */ null)),
                dirListingKey,
                Delta.justNew(value)));
    skyframeExecutor.externalFilesHelper.getAndNoteFileType(dir);
    // Initial sync to establish the baseline DiffAwareness.View.
    skyframeExecutor.handleDiffsForTesting(NullEventHandler.INSTANCE);
    scratch.file("/external/foo/new_file");

    syncSkyframeExecutor();

    Diff diff = getRecordedDiff();
    assertThat(diff.changedKeysWithoutNewValues()).containsNoneOf(dir, dirListingKey);
    assertThat(diff.changedKeysWithNewValues()).doesNotContainKey(dir);
    assertThat(diff.changedKeysWithNewValues()).containsKey(dirListingKey);
  }

  @Test
  public void sync_nothingChangedWithExternalListing_reportsNoExternalKeysInDiff()
      throws Exception {
    Root externalRoot = Root.fromPath(scratch.dir("/external"));
    RootedPath dir = RootedPath.toRootedPath(externalRoot, scratch.dir("/external/foo"));
    DirectoryListingStateValue value =
        DirectoryListingStateValue.create(dir.asPath().readdir(Symlinks.NOFOLLOW));
    DirectoryListingStateValue.Key dirListingKey = DirectoryListingStateValue.key(dir);
    initializeSkyframeExecutor(
        /* doPackageLoadingChecks= */ true, ImmutableList.of(nothingChangedDiffAwarenessFactory()));
    skyframeExecutor
        .injectable()
        .inject(
            ImmutableMap.of(
                dir,
                Delta.justNew(FileStateValue.create(dir, SyscallCache.NO_CACHE, /* tsgm= */ null)),
                dirListingKey,
                Delta.justNew(value)));
    skyframeExecutor.externalFilesHelper.getAndNoteFileType(dir);
    // Initial sync to establish the baseline DiffAwareness.View.
    skyframeExecutor.handleDiffsForTesting(NullEventHandler.INSTANCE);

    syncSkyframeExecutor();

    Diff diff = getRecordedDiff();
    assertThat(diff.changedKeysWithoutNewValues()).containsNoneOf(dir, dirListingKey);
    assertThat(diff.changedKeysWithNewValues()).doesNotContainKey(dir);
    assertThat(diff.changedKeysWithNewValues()).doesNotContainKey(dirListingKey);
  }

  private static DiffAwareness.Factory nothingChangedDiffAwarenessFactory() {
    return (pathEntry, ignoredPaths, optionsProvider) ->
        new DiffAwareness() {
          @Override
          public View getCurrentView(OptionsProvider options) {
            return new View() {};
          }

          @Override
          public ModifiedFileSet getDiff(View oldView, View newView) {
            return ModifiedFileSet.NOTHING_MODIFIED;
          }

          @Override
          public ModifiedFileSet getDiffFromEvaluatingVersion(
              OptionsProvider options, FileSystem fs) throws BrokenDiffAwarenessException {
            throw new UnsupportedOperationException("not implemented");
          }

          @Override
          public String name() {
            return null;
          }

          @Override
          public void close() {}
        };
  }

  private Diff getRecordedDiff() {
    return skyframeExecutor
        .getDifferencerForTesting()
        .getDiff(/* fromGraph= */ null, ignored -> false, ignored -> false);
  }

  @Test
  public void testSetDeletedPackages() throws Exception {
    ExtendedEventHandler eventHandler = NullEventHandler.INSTANCE;
    scratch.file("foo/bar/BUILD", "cc_library(name = 'bar', hdrs = ['bar.h'])");
    scratch.file("foo/baz/BUILD", "cc_library(name = 'baz', hdrs = ['baz.h'])");

    assertThat(
            skyframeExecutor
                .getPackageManager()
                .isPackage(eventHandler, PackageIdentifier.createInMainRepo("foo/bar")))
        .isTrue();
    assertThat(
            skyframeExecutor
                .getPackageManager()
                .getBuildFileForPackage(PackageIdentifier.createInMainRepo("foo/bar")))
        .isNotNull();
    assertThat(
            skyframeExecutor
                .getPackageManager()
                .isPackage(eventHandler, PackageIdentifier.createInMainRepo("foo/baz")))
        .isTrue();
    assertThat(
            skyframeExecutor
                .getPackageManager()
                .getBuildFileForPackage(PackageIdentifier.createInMainRepo("foo/baz")))
        .isNotNull();
    assertThat(
            skyframeExecutor
                .getPackageManager()
                .isPackage(eventHandler, PackageIdentifier.createInMainRepo("not/a/package")))
        .isFalse();
    assertThat(
            skyframeExecutor
                .getPackageManager()
                .getBuildFileForPackage(PackageIdentifier.createInMainRepo("not/a/package")))
        .isNull();

    skyframeExecutor
        .getPackageManager()
        .getPackage(eventHandler, PackageIdentifier.createInMainRepo("foo/bar"));
    skyframeExecutor
        .getPackageManager()
        .getPackage(eventHandler, PackageIdentifier.createInMainRepo("foo/baz"));

    assertThrows(
        "non-existent package was incorrectly thought to exist",
        NoSuchPackageException.class,
        () ->
            skyframeExecutor
                .getPackageManager()
                .getPackage(eventHandler, PackageIdentifier.createInMainRepo("not/a/package")));

    ImmutableSet<PackageIdentifier> deletedPackages =
        ImmutableSet.of(PackageIdentifier.createInMainRepo("foo/bar"));
    skyframeExecutor.setDeletedPackages(deletedPackages);

    assertThat(
            skyframeExecutor
                .getPackageManager()
                .isPackage(eventHandler, PackageIdentifier.createInMainRepo("foo/bar")))
        .isFalse();
    assertThat(
            skyframeExecutor
                .getPackageManager()
                .getBuildFileForPackage(PackageIdentifier.createInMainRepo("foo/bar")))
        .isNull();
    assertThrows(
        "deleted package was incorrectly thought to exist",
        NoSuchPackageException.class,
        () ->
            skyframeExecutor
                .getPackageManager()
                .getPackage(eventHandler, PackageIdentifier.createInMainRepo("foo/bar")));
    assertThat(
            skyframeExecutor
                .getPackageManager()
                .isPackage(eventHandler, PackageIdentifier.createInMainRepo("foo/baz")))
        .isTrue();
  }

  // Directly tests that PackageFunction adds a dependency on the PackageLookupValue for
  // (potential) subpackages. This is tested indirectly in several places (e.g.
  // LabelVisitorTest#testSubpackageBoundaryAdd and
  // PackageDeletionTest#testUnsuccessfulBuildAfterDeletion) but those tests are also indirectly
  // testing the behavior of TargetFunction when the target has a '/'.
  @Test
  public void testDependencyOnPotentialSubpackages() throws Exception {
    ExtendedEventHandler eventHandler = NullEventHandler.INSTANCE;
    scratch.file(
        "x/BUILD",
        """
        filegroup(
            name = "x",
            srcs = ["//x:y/z"],
        )

        filegroup(name = "y/z")
        """);

    Package pkgBefore =
        skyframeExecutor
            .getPackageManager()
            .getPackage(eventHandler, PackageIdentifier.createInMainRepo("x"));
    assertThat(pkgBefore.containsErrors()).isFalse();

    scratch.file("x/y/BUILD", "filegroup(name = 'z')");
    ModifiedFileSet modifiedFiles =
        ModifiedFileSet.builder()
            .modify(PathFragment.create("x"))
            .modify(PathFragment.create("x/y"))
            .modify(PathFragment.create("x/y/BUILD"))
            .build();
    skyframeExecutor.invalidateFilesUnderPathForTesting(
        reporter, modifiedFiles, Root.fromPath(rootDirectory));

    // The package lookup for "x" should now fail because it's invalid.
    reporter.removeHandler(failFastHandler); // expect errors
    assertThat(
            skyframeExecutor
                .getPackageManager()
                .getPackage(eventHandler, PackageIdentifier.createInMainRepo("x"))
                .containsErrors())
        .isTrue();

    scratch.deleteFile("x/y/BUILD");
    skyframeExecutor.invalidateFilesUnderPathForTesting(
        reporter, modifiedFiles, Root.fromPath(rootDirectory));

    // The package lookup for "x" should now succeed again.
    reporter.addHandler(failFastHandler); // no longer expect errors
    Package pkgAfter =
        skyframeExecutor
            .getPackageManager()
            .getPackage(eventHandler, PackageIdentifier.createInMainRepo("x"));
    assertThat(pkgAfter).isNotSameInstanceAs(pkgBefore);
  }

  @Test
  public void testSkyframePackageManagerGetBuildFileForPackage() throws Exception {
    PackageManager skyframePackageManager = skyframeExecutor.getPackageManager();

    scratch.file("nobuildfile/foo.txt");
    scratch.file("deletedpackage/BUILD");
    skyframeExecutor.setDeletedPackages(
        ImmutableList.of(PackageIdentifier.createInMainRepo("deletedpackage")));
    scratch.file("invalidpackagename.42/BUILD");
    Path everythingGoodBuildFilePath = scratch.file("everythinggood/BUILD");

    assertThat(
            skyframePackageManager.getBuildFileForPackage(
                PackageIdentifier.createInMainRepo("nobuildfile")))
        .isNull();
    assertThat(
            skyframePackageManager.getBuildFileForPackage(
                PackageIdentifier.createInMainRepo("deletedpackage")))
        .isNull();
    assertThat(
            skyframePackageManager.getBuildFileForPackage(
                PackageIdentifier.createInMainRepo("everythinggood")))
        .isEqualTo(everythingGoodBuildFilePath);
  }

  /**
   * Indirect regression test for b/12543229: "The Skyframe error propagation model is problematic".
   */
  @Test
  public void testPackageFunctionHandlesExceptionFromDependencies() throws Exception {
    reporter.removeHandler(failFastHandler);
    Path badDirPath = scratch.dir("bad/dir");
    // This will cause an IOException when trying to compute the glob, which is required to load
    // the package.
    badDirPath.setReadable(false);
    scratch.file("bad/BUILD", "filegroup(name='fg', srcs=glob(['**']))");
    assertThrows(
        NoSuchPackageException.class,
        () ->
            skyframeExecutor
                .getPackageManager()
                .getPackage(reporter, PackageIdentifier.createInMainRepo("bad")));
  }

  private ImmutableList<SkyKey> dirtyValues() throws InterruptedException {
    Diff diff =
        new FilesystemValueChecker(
                new TimestampGranularityMonitor(BlazeClock.instance()),
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                /* numThreads= */ 20)
            .getDirtyKeys(
                skyframeExecutor.getEvaluator().getValues(),
                DirtinessCheckerUtils.createBasicFilesystemDirtinessChecker());
    return ImmutableList.<SkyKey>builder()
        .addAll(diff.changedKeysWithoutNewValues())
        .addAll(diff.changedKeysWithNewValues().keySet())
        .build();
  }

  private void sync(String... labelStrings) throws Exception {
    Set<Label> labels = new HashSet<>();
    for (String labelString : labelStrings) {
      labels.add(Label.parseCanonical(labelString));
    }
    visitor.preloadTransitiveTargets(
        reporter,
        labels,
        /* keepGoing= */ false,
        /* parallelThreads= */ 200,
        /* callerForError= */ null);
  }

  @Test
  public void testInterruptLoadedTarget() throws Exception {
    analysisMock.pySupport().setup(mockToolsConfig);
    scratch.file(
        "python/hello/BUILD",
        getPyLoad("py_binary"),
        "py_binary(name = 'hello', srcs = ['hello.py'], data = glob(['*.txt'], allow_empty ="
            + " True))");
    Thread.currentThread().interrupt();
    LoadedPackageProvider packageProvider =
        new LoadedPackageProvider(skyframeExecutor.getPackageManager(), reporter);
    assertThrows(
        InterruptedException.class,
        () ->
            packageProvider.getLoadedTarget(Label.parseCanonicalUnchecked("//python/hello:hello")));
    Target target =
        packageProvider.getLoadedTarget(Label.parseCanonicalUnchecked("//python/hello:hello"));
    assertThat(target).isNotNull();
  }

  /**
   * Generating the same output from two targets is ok if we build them on successive builds and
   * invalidate the first target before we build the second target. This test is basically copied
   * here from {@code AnalysisCachingTest} because here we can control the number of Skyframe update
   * calls that we make. This prevents an intermediate update call from clearing the action and
   * hiding the bug.
   */
  @Test
  public void testNoActionConflictWithInvalidatedTarget() throws Exception {
    scratch.file(
        "conflict/BUILD",
        """
        cc_library(
            name = "x",
            srcs = ["foo.cc"],
        )

        cc_binary(
            name = "_objs/x/foo.o",
            srcs = ["bar.cc"],
        )
        """);
    ConfiguredTargetAndData conflict =
        skyframeExecutor.getConfiguredTargetAndDataForTesting(
            reporter, Label.parseCanonical("@//conflict:x"), getTargetConfiguration());
    assertThat(conflict).isNotNull();
    ArtifactRoot root =
        getTargetConfiguration()
            .getBinDirectory(conflict.getConfiguredTarget().getLabel().getRepository());

    Action oldAction =
        getGeneratingAction(
            getDerivedArtifact(
                PathFragment.create("conflict/_objs/x/foo.o"),
                root,
                ConfiguredTargetKey.fromConfiguredTarget(conflict.getConfiguredTarget())));
    assertThat(oldAction.getOwner().getLabel().toString()).isEqualTo("//conflict:x");
    skyframeExecutor.handleAnalysisInvalidatingChange();
    ConfiguredTargetAndData objsConflict =
        skyframeExecutor.getConfiguredTargetAndDataForTesting(
            reporter, Label.parseCanonical("@//conflict:_objs/x/foo.o"), getTargetConfiguration());
    assertThat(objsConflict).isNotNull();
    Action newAction =
        getGeneratingAction(
            getDerivedArtifact(
                PathFragment.create("conflict/_objs/x/foo.o"),
                root,
                ConfiguredTargetKey.fromConfiguredTarget(objsConflict.getConfiguredTarget())));
    assertThat(newAction.getOwner().getLabel().toString()).isEqualTo("//conflict:_objs/x/foo.o");
  }

  @Test
  public void testGetPackageUsesListener() throws Exception {
    scratch.file("pkg/BUILD", "thisisanerror");
    EventCollector customEventCollector = new EventCollector(EventKind.ERRORS);
    Package pkg =
        skyframeExecutor
            .getPackageManager()
            .getPackage(
                new Reporter(new EventBus(), customEventCollector),
                PackageIdentifier.createInMainRepo("pkg"));
    assertThat(pkg.containsErrors()).isTrue();
    MoreAsserts.assertContainsEvent(customEventCollector, "name 'thisisanerror' is not defined");
  }

  /** Dummy action that does not create its lone output file. */
  private static class MissingOutputAction extends DummyAction {
    MissingOutputAction(NestedSet<Artifact> inputs, Artifact output) {
      super(inputs, output);
    }

    @Override
    public ActionResult execute(ActionExecutionContext actionExecutionContext)
        throws ActionExecutionException, InterruptedException {
      ActionResult actionResult = super.execute(actionExecutionContext);
      try {
        getPrimaryOutput().getPath().deleteTree();
      } catch (IOException e) {
        throw new AssertionError(e);
      }
      return actionResult;
    }
  }

  private static final ActionCacheChecker NULL_CHECKER =
      new ActionCacheChecker(
          AMNESIAC_CACHE,
          new ActionsTestUtil.FakeArtifactResolverBase(),
          new ActionKeyContext(),
          Predicates.alwaysTrue(),
          /* cacheConfig= */ null);

  private static final ProgressSupplier EMPTY_PROGRESS_SUPPLIER =
      new ProgressSupplier() {
        @Override
        public String getProgressString() {
          return "";
        }
      };

  private static final ActionCompletedReceiver EMPTY_COMPLETION_RECEIVER = ald -> {};

  private <T extends SkyValue> EvaluationResult<T> evaluate(Iterable<? extends SkyKey> roots)
      throws InterruptedException {
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setParallelism(SequencedSkyframeExecutor.DEFAULT_THREAD_COUNT)
            .setEventHandler(reporter)
            .build();
    return evaluateWithEvaluationContext(roots, evaluationContext);
  }

  @CanIgnoreReturnValue
  private <T extends SkyValue> EvaluationResult<T> evaluateWithEvaluationContext(
      Iterable<? extends SkyKey> roots, EvaluationContext context) throws InterruptedException {
    return skyframeExecutor.getEvaluator().evaluate(roots, context);
  }

  /**
   * Make sure that if a shared action fails to create an output file, the other action doesn't
   * complain about it too.
   */
  @Test
  public void testSharedActionsNoOutputs() throws Exception {
    Path root = getExecRoot();
    PathFragment execPath = PathFragment.create("out").getRelative("missing");
    // We create two "configured targets" and two copies of the same artifact, each generated by
    // an action from its respective configured target.
    ActionLookupKey lc1 = new InjectedActionLookupKey("lc1");
    Artifact output1 =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"), execPath, lc1);
    Action action1 =
        new MissingOutputAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), output1);
    ActionLookupValue ctValue1 = createActionLookupValue(action1, lc1);
    ActionLookupKey lc2 = new InjectedActionLookupKey("lc2");
    Artifact output2 =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"), execPath, lc2);
    Action action2 =
        new MissingOutputAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), output2);
    ActionLookupValue ctValue2 = createActionLookupValue(action2, lc2);
    skyframeExecutor.configureActionExecutor(/* fileCache= */ null, ActionInputPrefetcher.NONE);
    // Inject the "configured targets" into the graph.
    skyframeExecutor
        .getDifferencerForTesting()
        .inject(ImmutableMap.of(lc1, Delta.justNew(ctValue1), lc2, Delta.justNew(ctValue2)));
    // Do a null build, so that the skyframe executor initializes the action executor properly.
    skyframeExecutor.setActionOutputRoot(getOutputPath());
    skyframeExecutor.setActionExecutionProgressReportingObjects(
        EMPTY_PROGRESS_SUPPLIER,
        EMPTY_COMPLETION_RECEIVER,
        ActionExecutionStatusReporter.create(reporter));
    var unused =
        skyframeExecutor.buildArtifacts(
            reporter,
            ResourceManager.instanceForTestingOnly(),
            new DummyExecutor(fileSystem, rootDirectory),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            options,
            NULL_CHECKER,
            ActionOutputDirectoryHelper.createForTesting(),
            null,
            null);

    reporter.removeHandler(failFastHandler); // Expect errors.
    skyframeExecutor.prepareBuildingForTestingOnly(
        reporter,
        new DummyExecutor(fileSystem, rootDirectory),
        options,
        NULL_CHECKER,
        ActionOutputDirectoryHelper.createForTesting());
    EvaluationResult<FileArtifactValue> result = evaluate(ImmutableList.of(output1, output2));
    assertWithMessage(result.toString()).that(result.keyNames()).isEmpty();
    assertThat(result.hasError()).isTrue();
    MoreAsserts.assertContainsEvent(
        eventCollector, "output '" + output1.prettyPrint() + "' was not created");
    MoreAsserts.assertContainsEvent(eventCollector, "not all outputs were created or valid");
    assertEventCount(2, eventCollector);
  }

  /** Shared actions can race and both check the action cache and try to execute. */
  @Test
  public void testSharedActionsRacing() throws Exception {
    Path root = getExecRoot();
    PathFragment execPath = PathFragment.create("out").getRelative("file");
    Path sourcePath = rootDirectory.getRelative("foo/src");
    sourcePath.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.createEmptyFile(sourcePath);

    // We create two "configured targets" and two copies of the same artifact, each generated by
    // an action from its respective configured target. Both actions will consume the input file
    // "out/input" so we can synchronize their execution.
    ActionLookupKey inputKey = new InjectedActionLookupKey("input");
    Artifact input =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            PathFragment.create("out").getRelative("input"),
            inputKey);
    Action baseAction = new DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), input);
    ActionLookupValue ctBase = createActionLookupValue(baseAction, inputKey);
    ActionLookupKey lc1 = new InjectedActionLookupKey("lc1");
    Artifact output1 =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"), execPath, lc1);
    Action action1 = new DummyAction(NestedSetBuilder.create(Order.STABLE_ORDER, input), output1);
    ActionLookupValue ctValue1 = createActionLookupValue(action1, lc1);
    ActionLookupKey lc2 = new InjectedActionLookupKey("lc2");
    Artifact output2 =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"), execPath, lc2);
    Action action2 = new DummyAction(NestedSetBuilder.create(Order.STABLE_ORDER, input), output2);
    ActionLookupValue ctValue2 = createActionLookupValue(action2, lc2);

    // Stall both actions during the "checking inputs" phase so that neither will enter
    // SkyframeActionExecutor before both have asked SkyframeActionExecutor if another shared action
    // is running. This way, both actions will check the action cache beforehand and try to update
    // the action cache post-build.
    final CountDownLatch inputsRequested = new CountDownLatch(2);
    skyframeExecutor.configureActionExecutor(/* fileCache= */ null, ActionInputPrefetcher.NONE);
    skyframeExecutor
        .getEvaluator()
        .injectGraphTransformerForTesting(
            NotifyingHelper.makeNotifyingTransformer(
                (key, type, order, context) -> {
                  if (type == EventType.GET_VALUE_WITH_METADATA
                      && key.functionName().equals(Artifact.ARTIFACT)
                      && input.equals(key)) {
                    inputsRequested.countDown();
                    try {
                      assertThat(
                              inputsRequested.await(
                                  TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                          .isTrue();
                    } catch (InterruptedException e) {
                      throw new IllegalStateException(e);
                    }
                  }
                }));

    // Inject the "configured targets" and artifact into the graph.
    skyframeExecutor
        .getDifferencerForTesting()
        .inject(
            ImmutableMap.of(
                lc1,
                Delta.justNew(ctValue1),
                lc2,
                Delta.justNew(ctValue2),
                inputKey,
                Delta.justNew(ctBase)));
    // Do a null build, so that the skyframe executor initializes the action executor properly.
    skyframeExecutor.setActionOutputRoot(getOutputPath());
    skyframeExecutor.setActionExecutionProgressReportingObjects(
        EMPTY_PROGRESS_SUPPLIER,
        EMPTY_COMPLETION_RECEIVER,
        ActionExecutionStatusReporter.create(reporter));
    var unused =
        skyframeExecutor.buildArtifacts(
            reporter,
            ResourceManager.instanceForTestingOnly(),
            new DummyExecutor(fileSystem, rootDirectory),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            options,
            NULL_CHECKER,
            ActionOutputDirectoryHelper.createForTesting(),
            null,
            null);

    skyframeExecutor.prepareBuildingForTestingOnly(
        reporter,
        new DummyExecutor(fileSystem, rootDirectory),
        options,
        NULL_CHECKER,
        ActionOutputDirectoryHelper.createForTesting());
    EvaluationResult<FileArtifactValue> result =
        evaluate(Artifact.keys(ImmutableList.of(output1, output2)));
    assertThat(result.hasError()).isFalse();
    TrackingAwaiter.INSTANCE.assertNoErrors();
  }

  /**
   * Tests a subtle situation when three shared actions race and are interrupted. Action A starts
   * executing. Actions B and C start executing. Action B notices action A is already executing and
   * sets completionFuture. It then exits, returning control to
   * AbstractParallelEvaluator$Evaluate#run code. The build is interrupted. When B's code tries to
   * register the future with AbstractQueueVisitor, the future is canceled (or if the interrupt
   * races with B registering the future, shortly thereafter). Action C then starts running. It too
   * notices Action A is already executing. The future's state should be consistent. A cannot finish
   * until C runs, since otherwise C would see that A was done.
   */
  @Test
  public void testThreeSharedActionsRacing() throws Exception {
    Path root = getExecRoot();
    PathFragment out = PathFragment.create("out");
    PathFragment execPath = out.getRelative("file");
    // We create three "configured targets" and three copies of the same artifact, each generated by
    // an action from its respective configured target. The actions wouldn't actually do the same
    // thing if they executed, but they look the same to our execution engine.
    ActionLookupKey lcA = new InjectedActionLookupKey("lcA");
    Artifact outputA =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"), execPath, lcA);
    CountDownLatch actionAStartedSoOthersCanProceed = new CountDownLatch(1);
    CountDownLatch actionCFinishedSoACanFinish = new CountDownLatch(1);
    Action actionA =
        new TestAction(
            (Serializable & Callable<Void>)
                () -> {
                  actionAStartedSoOthersCanProceed.countDown();
                  try {
                    Thread.sleep(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
                  } catch (InterruptedException e) {
                    TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                        actionCFinishedSoACanFinish, "third didn't finish");
                    throw e;
                  }
                  throw new IllegalStateException("Should have been interrupted");
                },
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            ImmutableSet.of(outputA));
    ActionLookupValue ctA = createActionLookupValue(actionA, lcA);

    // Shared actions: they look the same from the point of view of Blaze data.
    ActionLookupKey lcB = new InjectedActionLookupKey("lcB");
    Artifact outputB =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"), execPath, lcB);
    Action actionB = new DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), outputB);
    ActionLookupValue ctB = createActionLookupValue(actionB, lcB);
    ActionLookupKey lcC = new InjectedActionLookupKey("lcC");
    Artifact outputC =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"), execPath, lcC);
    Action actionC = new DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), outputC);
    ActionLookupValue ctC = createActionLookupValue(actionC, lcC);

    // Both shared actions wait for A to start executing. We do that by stalling their dep requests
    // on their configured targets. We then let B proceed. Once B finishes its SkyFunction run, it
    // interrupts the main thread. C just waits until it has been interrupted, and then another
    // little bit, to give B time to attempt to add the future and try to cancel it. It not waiting
    // long enough can lead to a flaky pass.

    Thread mainThread = Thread.currentThread();
    CountDownLatch cStarted = new CountDownLatch(1);
    skyframeExecutor.configureActionExecutor(/* fileCache= */ null, ActionInputPrefetcher.NONE);
    skyframeExecutor
        .getEvaluator()
        .injectGraphTransformerForTesting(
            NotifyingHelper.makeNotifyingTransformer(
                (key, type, order, context) -> {
                  if (type == EventType.GET_VALUE_WITH_METADATA
                      && (key.equals(lcB) || key.equals(lcC))) {
                    // One of the shared actions is requesting its configured target dep.
                    TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                        actionAStartedSoOthersCanProceed, "primary didn't start");
                    if (key.equals(lcC)) {
                      cStarted.countDown();
                      // Wait until interrupted.
                      try {
                        Thread.sleep(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
                        throw new IllegalStateException("Should have been interrupted");
                      } catch (InterruptedException e) {
                        // Because ActionExecutionFunction doesn't check for interrupts, this
                        // interrupted state will persist until the ADD_REVERSE_DEP code below. If
                        // it does not, this test will start to fail, which is good, since it would
                        // be strange to check for interrupts in that stretch of hot code.
                        Thread.currentThread().interrupt();
                      }
                      // Wait for B thread to cancel its future. It's hard to know exactly when that
                      // will be, so give it time. No flakes in 2k runs with this sleep.
                      Uninterruptibles.sleepUninterruptibly(100, TimeUnit.MILLISECONDS);
                    }
                  } else if (type == EventType.ADD_REVERSE_DEP
                      && key.equals(lcB)
                      && order == NotifyingHelper.Order.BEFORE
                      && context != null) {
                    TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(cStarted, "c missing");
                    // B thread has finished its run. Interrupt build!
                    mainThread.interrupt();
                  } else if (type == EventType.ADD_REVERSE_DEP
                      && key.equals(lcC)
                      && order == NotifyingHelper.Order.BEFORE
                      && context != null) {
                    // Test is almost over: let action A finish now that C observed future.
                    actionCFinishedSoACanFinish.countDown();
                  }
                }));

    // Inject the "configured targets" and artifacts into the graph.
    skyframeExecutor
        .getDifferencerForTesting()
        .inject(
            ImmutableMap.of(
                lcA, Delta.justNew(ctA), lcB, Delta.justNew(ctB), lcC, Delta.justNew(ctC)));
    // Do a null build, so that the skyframe executor initializes the action executor properly.
    skyframeExecutor.setActionOutputRoot(getOutputPath());
    skyframeExecutor.setActionExecutionProgressReportingObjects(
        EMPTY_PROGRESS_SUPPLIER,
        EMPTY_COMPLETION_RECEIVER,
        ActionExecutionStatusReporter.create(reporter));
    var unused =
        skyframeExecutor.buildArtifacts(
            reporter,
            ResourceManager.instanceForTestingOnly(),
            new DummyExecutor(fileSystem, rootDirectory),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            options,
            NULL_CHECKER,
            ActionOutputDirectoryHelper.createForTesting(),
            null,
            null);

    skyframeExecutor.prepareBuildingForTestingOnly(
        reporter,
        new DummyExecutor(fileSystem, rootDirectory),
        options,
        NULL_CHECKER,
        ActionOutputDirectoryHelper.createForTesting());
    reporter.removeHandler(failFastHandler);
    try {
      evaluate(Artifact.keys(ImmutableList.of(outputA, outputB, outputC)));
      fail();
    } catch (InterruptedException e) {
      // Expected.
    }
    TrackingAwaiter.INSTANCE.assertNoErrors();
  }

  /** Dummy codec for serialization. Doesn't actually serialize {@link CountDownLatch}! */
  @SuppressWarnings("unused")
  private static class CountDownLatchCodec implements ObjectCodec<CountDownLatch> {
    private static final CountDownLatch RETURNED = new CountDownLatch(0);

    @Override
    public Class<? extends CountDownLatch> getEncodedClass() {
      return CountDownLatch.class;
    }

    @Override
    public void serialize(
        SerializationContext context, CountDownLatch obj, CodedOutputStream codedOut) {}

    @Override
    public CountDownLatch deserialize(DeserializationContext context, CodedInputStream codedIn) {
      return RETURNED;
    }
  }

  /** Regression test for ##5396: successfully build shared actions with tree artifacts. */
  @Test
  public void sharedActionsWithTree() throws Exception {
    Path root = getExecRoot();
    PathFragment execPath = PathFragment.create("out").getRelative("trees");
    // We create two "configured targets" and two copies of the same artifact, each generated by
    // an action from its respective configured target.
    ActionLookupKey lc1 = new InjectedActionLookupKey("lc1");
    SpecialArtifact output1 =
        SpecialArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath,
            lc1,
            Artifact.SpecialArtifactType.TREE);
    ImmutableList<PathFragment> children = ImmutableList.of(PathFragment.create("child"));
    Action action1 =
        new TreeArtifactAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), output1, children);
    ActionLookupValue ctValue1 = createActionLookupValue(action1, lc1);
    ActionLookupKey lc2 = new InjectedActionLookupKey("lc2");
    SpecialArtifact output2 =
        SpecialArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath,
            lc2,
            Artifact.SpecialArtifactType.TREE);
    Action action2 =
        new TreeArtifactAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), output2, children);
    ActionLookupValue ctValue2 = createActionLookupValue(action2, lc2);
    skyframeExecutor.configureActionExecutor(/* fileCache= */ null, ActionInputPrefetcher.NONE);
    // Inject the "configured targets" into the graph.
    skyframeExecutor
        .getDifferencerForTesting()
        .inject(ImmutableMap.of(lc1, Delta.justNew(ctValue1), lc2, Delta.justNew(ctValue2)));
    // Do a null build, so that the skyframe executor initializes the action executor properly.
    skyframeExecutor.setActionOutputRoot(getOutputPath());
    skyframeExecutor.setActionExecutionProgressReportingObjects(
        EMPTY_PROGRESS_SUPPLIER,
        EMPTY_COMPLETION_RECEIVER,
        ActionExecutionStatusReporter.create(reporter));
    var unused =
        skyframeExecutor.buildArtifacts(
            reporter,
            ResourceManager.instanceForTestingOnly(),
            new DummyExecutor(fileSystem, rootDirectory),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            options,
            NULL_CHECKER,
            ActionOutputDirectoryHelper.createForTesting(),
            null,
            null);

    skyframeExecutor.prepareBuildingForTestingOnly(
        reporter,
        new DummyExecutor(fileSystem, rootDirectory),
        options,
        NULL_CHECKER,
        ActionOutputDirectoryHelper.createForTesting());

    EvaluationResult<TreeArtifactValue> result = evaluate(ImmutableList.of(output1, output2));

    TreeFileArtifact tree1Child = Iterables.getOnlyElement(result.get(output1).getChildren());
    TreeFileArtifact tree2Child = Iterables.getOnlyElement(result.get(output2).getChildren());
    assertThat(tree1Child).isEqualTo(TreeFileArtifact.createTreeOutput(output1, "child"));
    assertThat(tree2Child).isEqualTo(TreeFileArtifact.createTreeOutput(output2, "child"));
  }

  /** Dummy action that creates a tree output. */
  // AutoCodec because the superclass has a WrappedRunnable inside it.
  @AutoCodec
  @VisibleForSerialization
  static class TreeArtifactAction extends TestAction {
    @SuppressWarnings("unused") // Only needed for serialization.
    private final SpecialArtifact output;

    @SuppressWarnings("unused") // Only needed for serialization.
    private final Iterable<PathFragment> children;

    TreeArtifactAction(
        NestedSet<Artifact> inputs, SpecialArtifact output, Iterable<PathFragment> children) {
      super(() -> createDirectoryAndFiles(output, children), inputs, ImmutableSet.of(output));
      Preconditions.checkState(output.isTreeArtifact(), output);
      this.output = output;
      this.children = children;
    }

    private static void createDirectoryAndFiles(
        SpecialArtifact output, Iterable<PathFragment> children) {
      Path directory = output.getPath();
      try {
        directory.createDirectoryAndParents();
        for (PathFragment child : children) {
          FileSystemUtils.createEmptyFile(directory.getRelative(child));
        }
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** Regression test for ##5396: successfully build shared actions with tree artifacts. */
  @Test
  public void sharedActionTemplate() throws Exception {
    Path root = getExecRoot();
    PathFragment execPath = PathFragment.create("out").getRelative("trees");
    // We create two "configured targets" and two copies of the same artifact, each generated by
    // an action from its respective configured target.
    ActionLookupKey baseKey = new InjectedActionLookupKey("base");
    SpecialArtifact baseOutput =
        SpecialArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath,
            baseKey,
            Artifact.SpecialArtifactType.TREE);
    ImmutableList<PathFragment> children = ImmutableList.of(PathFragment.create("child"));
    Action action1 =
        new TreeArtifactAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), baseOutput, children);
    ActionLookupValue baseCt = createActionLookupValue(action1, baseKey);
    ActionLookupKey shared1 = new InjectedActionLookupKey("shared1");
    PathFragment execPath2 = PathFragment.create("out").getRelative("treesShared");
    SpecialArtifact sharedOutput1 =
        SpecialArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath2,
            shared1,
            Artifact.SpecialArtifactType.TREE);
    ActionTemplate<DummyAction> template1 =
        new DummyActionTemplate(baseOutput, sharedOutput1, ActionOwner.SYSTEM_ACTION_OWNER);
    ActionLookupValue shared1Ct = createActionLookupValue(template1, shared1);
    ActionLookupKey shared2 = new InjectedActionLookupKey("shared2");
    SpecialArtifact sharedOutput2 =
        SpecialArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath2,
            shared2,
            Artifact.SpecialArtifactType.TREE);
    ActionTemplate<DummyAction> template2 =
        new DummyActionTemplate(baseOutput, sharedOutput2, ActionOwner.SYSTEM_ACTION_OWNER);
    ActionLookupValue shared2Ct = createActionLookupValue(template2, shared2);
    skyframeExecutor.configureActionExecutor(/* fileCache= */ null, ActionInputPrefetcher.NONE);
    // Inject the "configured targets" into the graph.
    skyframeExecutor
        .getDifferencerForTesting()
        .inject(
            ImmutableMap.of(
                baseKey,
                Delta.justNew(baseCt),
                shared1,
                Delta.justNew(shared1Ct),
                shared2,
                Delta.justNew(shared2Ct)));
    // Do a null build, so that the skyframe executor initializes the action executor properly.
    skyframeExecutor.setActionOutputRoot(getOutputPath());
    skyframeExecutor.setActionExecutionProgressReportingObjects(
        EMPTY_PROGRESS_SUPPLIER,
        EMPTY_COMPLETION_RECEIVER,
        ActionExecutionStatusReporter.create(reporter));
    var unused =
        skyframeExecutor.buildArtifacts(
            reporter,
            ResourceManager.instanceForTestingOnly(),
            new DummyExecutor(fileSystem, rootDirectory),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            options,
            NULL_CHECKER,
            ActionOutputDirectoryHelper.createForTesting(),
            null,
            null);

    skyframeExecutor.prepareBuildingForTestingOnly(
        reporter,
        new DummyExecutor(fileSystem, rootDirectory),
        options,
        NULL_CHECKER,
        ActionOutputDirectoryHelper.createForTesting());
    evaluate(ImmutableList.of(sharedOutput1, sharedOutput2));
  }

  private static final class DummyActionTemplate implements ActionTemplate<DummyAction> {
    private final SpecialArtifact inputArtifact;
    private final SpecialArtifact outputArtifact;
    private final ActionOwner actionOwner;

    private DummyActionTemplate(
        SpecialArtifact inputArtifact, SpecialArtifact outputArtifact, ActionOwner actionOwner) {
      this.inputArtifact = inputArtifact;
      this.outputArtifact = outputArtifact;
      this.actionOwner = actionOwner;
    }

    @Override
    public boolean isShareable() {
      return true;
    }

    @Override
    public ImmutableList<DummyAction> generateActionsForInputArtifacts(
        ImmutableSet<TreeFileArtifact> inputTreeFileArtifacts, ActionLookupKey artifactOwner) {
      return inputTreeFileArtifacts.stream()
          .map(
              input -> {
                TreeFileArtifact output =
                    TreeFileArtifact.createTemplateExpansionOutput(
                        outputArtifact, input.getParentRelativePath(), artifactOwner);
                return new DummyAction(input, output);
              })
          .collect(toImmutableList());
    }

    @Override
    public String getKey(
        ActionKeyContext actionKeyContext, @Nullable ArtifactExpander artifactExpander) {
      Fingerprint fp = new Fingerprint();
      fp.addPath(inputArtifact.getPath());
      fp.addPath(outputArtifact.getPath());
      return fp.hexDigestAndReset();
    }

    @Override
    public SpecialArtifact getInputTreeArtifact() {
      return inputArtifact;
    }

    @Override
    public SpecialArtifact getOutputTreeArtifact() {
      return outputArtifact;
    }

    @Override
    public ActionOwner getOwner() {
      return actionOwner;
    }

    @Override
    public String getMnemonic() {
      return "DummyTemplate";
    }

    @Override
    public String prettyPrint() {
      return describe();
    }

    @Override
    public String describe() {
      return "DummyTemplate";
    }

    @Override
    public NestedSet<Artifact> getTools() {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }

    @Override
    public NestedSet<Artifact> getInputs() {
      return NestedSetBuilder.create(Order.STABLE_ORDER, inputArtifact);
    }

    @Override
    public NestedSet<Artifact> getOriginalInputs() {
      return getInputs();
    }

    @Override
    public NestedSet<Artifact> getSchedulingDependencies() {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }

    @Override
    public ImmutableList<String> getClientEnvironmentVariables() {
      return ImmutableList.of();
    }

    @Override
    public NestedSet<Artifact> getInputFilesForExtraAction(
        ActionExecutionContext actionExecutionContext) {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }

    @Override
    public ImmutableSet<Artifact> getMandatoryOutputs() {
      return ImmutableSet.of();
    }

    @Override
    public NestedSet<Artifact> getMandatoryInputs() {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }

  }

  /**
   * b/150153544: demonstration of how shared actions do not work on incremental builds when action
   * cache is disabled. In practice, this test usually throws an exception and deadlocks, because
   * the "top" action notices that its input is missing even before the callable specified here
   * executes and throws an exception, so shared action2 never gets the signal to finish. However,
   * even if "top" is delayed to wait for the shared action2 to run, the assertion that the artifact
   * exists will fail, since action2's "prepare" step deleted it.
   */
  @Ignore("b/150153544")
  @Test
  public void incrementalSharedActions() throws Exception {
    Path root = getExecRoot();
    PathFragment relativeOut = PathFragment.create("out");
    PathFragment execPath = relativeOut.getRelative("file");
    Path sourcePath = rootDirectory.getRelative("foo/src");
    sourcePath.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.createEmptyFile(sourcePath);

    // We create two "configured targets" and two copies of the same artifact, each generated by
    // an action from its respective configured target.
    ActionLookupKey lc1 = new InjectedActionLookupKey("lc1");
    Artifact output1 =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"), execPath, lc1);
    Action action1 = new DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), output1);
    ActionLookupValue ctValue1 = createActionLookupValue(action1, lc1);
    ActionLookupKey lc2 = new InjectedActionLookupKey("lc2");
    Artifact output2 =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"), execPath, lc2);
    CountDownLatch action2Running = new CountDownLatch(1);
    CountDownLatch topActionTestedOutput = new CountDownLatch(1);
    Action action2 =
        new TestAction(
            (Callable<Void> & Serializable)
                () -> {
                  action2Running.countDown();
                  TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                      topActionTestedOutput, "top ran");
                  return null;
                },
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            ImmutableSet.of(output2));
    ActionLookupValue ctValue2 = createActionLookupValue(action2, lc2);

    ActionLookupKey topLc = new InjectedActionLookupKey("top");
    Artifact top =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            relativeOut.getChild("top"),
            topLc);
    Action topAction =
        new TestAction(
            (Callable<Void> & Serializable)
                () -> {
                  TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                      action2Running, "action 2 running");
                  try {
                    assertThat(output1.getPath().exists()).isTrue();
                  } finally {
                    topActionTestedOutput.countDown();
                  }
                  return null;
                },
            NestedSetBuilder.create(Order.STABLE_ORDER, output1),
            ImmutableSet.of(top));
    ActionLookupValue ctTop = createActionLookupValue(topAction, topLc);

    // Inject the "configured targets" and artifact into the graph.
    skyframeExecutor
        .getDifferencerForTesting()
        .inject(
            ImmutableMap.of(
                lc1,
                Delta.justNew(ctValue1),
                lc2,
                Delta.justNew(ctValue2),
                topLc,
                Delta.justNew(ctTop)));
    // Do a null build, so that the skyframe executor initializes the action executor properly.
    skyframeExecutor.setActionOutputRoot(getOutputPath());
    skyframeExecutor.setActionExecutionProgressReportingObjects(
        EMPTY_PROGRESS_SUPPLIER,
        EMPTY_COMPLETION_RECEIVER,
        ActionExecutionStatusReporter.create(reporter));
    var unused =
        skyframeExecutor.buildArtifacts(
            reporter,
            ResourceManager.instanceForTestingOnly(),
            new DummyExecutor(fileSystem, rootDirectory),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            options,
            NULL_CHECKER,
            ActionOutputDirectoryHelper.createForTesting(),
            null,
            null);

    // NULL_CHECKER here means action cache, which would be our savior, is not in play.
    skyframeExecutor.prepareBuildingForTestingOnly(
        reporter,
        new DummyExecutor(fileSystem, rootDirectory),
        options,
        NULL_CHECKER,
        ActionOutputDirectoryHelper.createForTesting());
    EvaluationResult<FileArtifactValue> result = evaluate(Artifact.keys(ImmutableList.of(output1)));
    assertThat(result.hasError()).isFalse();
    skyframeExecutor.prepareBuildingForTestingOnly(
        reporter,
        new DummyExecutor(fileSystem, rootDirectory),
        options,
        NULL_CHECKER,
        ActionOutputDirectoryHelper.createForTesting());
    EvaluationResult<FileArtifactValue> result2 =
        evaluate(Artifact.keys(ImmutableList.of(top, output2)));
    assertThat(result2.hasError()).isFalse();
    TrackingAwaiter.INSTANCE.assertNoErrors();
  }

  @Test
  public void interruptDoesntSuppressErrorOutput() throws Exception {
    Path root = getExecRoot();
    PathFragment execPath = PathFragment.create("out").getRelative("dir");
    PathFragment cyclesourceFragment = PathFragment.create("cyclesource");
    Artifact.SourceArtifact cycleArtifact =
        new Artifact.SourceArtifact(
            ArtifactRoot.asSourceRoot(Root.fromPath(rootDirectory)),
            cyclesourceFragment,
            ArtifactOwner.NULL_OWNER);
    rootDirectory.getRelative(cyclesourceFragment).createSymbolicLink(cyclesourceFragment);
    ActionLookupKey lc1 = new InjectedActionLookupKey("lc1");
    Artifact output =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath.getRelative("cycleOutput"),
            lc1);
    Action action1 = new DummyAction(cycleArtifact, output);
    SkyValue ctValue1 =
        ValueWithMetadata.normal(
            createActionLookupValue(action1, lc1),
            null,
            NestedSetBuilder.emptySet(Order.STABLE_ORDER));
    ActionLookupKey lc2 = new InjectedActionLookupKey("lc2");
    Artifact output2 =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath.getRelative("bar"),
            lc2);
    CountDownLatch startedSleep = new CountDownLatch(1);
    @SuppressWarnings("ThreadSleepMillis")
    Action slowAction =
        new TestAction(
            (Callable<Void> & Serializable)
                () -> {
                  startedSleep.countDown();
                  Thread.sleep(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
                  throw new IllegalStateException("Should have been interrupted");
                },
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            ImmutableSet.of(output2));
    SkyValue ctValue2 =
        ValueWithMetadata.normal(
            createActionLookupValue(slowAction, lc2),
            null,
            NestedSetBuilder.emptySet(Order.STABLE_ORDER));
    skyframeExecutor.configureActionExecutor(/* fileCache= */ null, ActionInputPrefetcher.NONE);
    skyframeExecutor
        .getEvaluator()
        .injectGraphTransformerForTesting(
            NotifyingHelper.makeNotifyingTransformer(
                (key, type, order, context) -> {
                  if (EventType.IS_READY.equals(type)
                      && key instanceof ActionLookupData actionLookupData
                      && lc1.equals(actionLookupData.getActionLookupKey())) {
                    TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(startedSleep, "No sleep");
                  }
                }));
    skyframeExecutor
        .getDifferencerForTesting()
        .inject(ImmutableMap.of(lc1, Delta.justNew(ctValue1), lc2, Delta.justNew(ctValue2)));
    // Do a null build, so that the skyframe executor initializes the action executor properly.
    skyframeExecutor.setActionOutputRoot(getOutputPath());
    skyframeExecutor.setActionExecutionProgressReportingObjects(
        EMPTY_PROGRESS_SUPPLIER,
        EMPTY_COMPLETION_RECEIVER,
        ActionExecutionStatusReporter.create(reporter));
    var unused =
        skyframeExecutor.buildArtifacts(
            reporter,
            ResourceManager.instanceForTestingOnly(),
            new DummyExecutor(fileSystem, rootDirectory),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            options,
            NULL_CHECKER,
            ActionOutputDirectoryHelper.createForTesting(),
            null,
            null);

    skyframeExecutor.prepareBuildingForTestingOnly(
        reporter,
        new DummyExecutor(fileSystem, rootDirectory),
        options,
        NULL_CHECKER,
        ActionOutputDirectoryHelper.createForTesting());
    reporter.removeHandler(failFastHandler); // Expect errors.
    evaluate(Artifact.keys(ImmutableList.of(output, output2)));
    assertContainsEvent(
        "Test dir/cycleOutput failed: error reading file 'cyclesource': Symlink cycle");
    assertContainsEvent("Test dir/cycleOutput failed: 1 input file(s) are in error");
  }

  @Test
  public void noEventStorageForNonIncrementalBuild() throws Exception {
    SkyKey skyKey = GraphTester.skyKey("key");
    extraSkyFunctions.put(
        skyKey.functionName(),
        (key, env) -> {
          env.getListener().handle(Event.warn("warning"));
          env.getListener()
              .post(
                  new Postable() {
                    @Override
                    public boolean storeForReplay() {
                      return true;
                    }
                  });
          return new SkyValue() {};
        });
    initializeSkyframeExecutor();
    skyframeExecutor.setActive(false);
    skyframeExecutor.decideKeepIncrementalState(
        /* batch= */ false,
        /* keepStateAfterBuild= */ true,
        /* shouldTrackIncrementalState= */ false,
        /* heuristicallyDropNodes= */ false,
        /* discardAnalysisCache= */ false,
        reporter);
    skyframeExecutor.setActive(true);
    syncSkyframeExecutor();

    EvaluationResult<?> result = evaluate(ImmutableList.of(skyKey));
    assertThat(result.hasError()).isFalse();
    assertContainsEvent("warning");

    SkyValue valueWithMetadata =
        skyframeExecutor
            .getEvaluator()
            .getExistingEntryAtCurrentlyEvaluatingVersion(skyKey)
            .getValueMaybeWithMetadata();
    assertThat(ValueWithMetadata.getEvents(valueWithMetadata).toList()).isEmpty();
  }

  /**
   * Tests that events from action lookup keys (i.e., analysis events) are not stored in execution.
   * This test is actually more extreme than Blaze is, since it skips the analysis phase and so
   * <i>never</i> emits the analysis events, while in reality Blaze will always emit the analysis
   * events, during the analysis phase.
   *
   * <p>Also incidentally tests that events coming from action execution are actually not stored at
   * all.
   *
   * <p>The boolean TestParameter skymeld is to ensure that this behavior is consistent even for
   * skymeld mode.
   */
  @Test
  public void analysisEventsNotStoredInExecution(@TestParameter boolean skymeld) throws Exception {
    Path root = getExecRoot();
    PathFragment execPath = PathFragment.create("out").getRelative("dir");
    ActionLookupKey lc1 = new InjectedActionLookupKey("lc1");
    Artifact output =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath.getRelative("foo"),
            lc1);
    Action action1 = new WarningAction(ImmutableList.of(), output, "action 1");
    SkyValue ctValue1 =
        ValueWithMetadata.normal(
            createActionLookupValue(action1, lc1),
            null,
            NestedSetBuilder.create(Order.STABLE_ORDER, Event.warn("analysis warning 1")));
    ActionLookupKey lc2 = new InjectedActionLookupKey("lc2");
    Artifact output2 =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath.getRelative("bar"),
            lc2);
    Action action2 = new WarningAction(ImmutableList.of(output), output2, "action 2");
    SkyValue ctValue2 =
        ValueWithMetadata.normal(
            createActionLookupValue(action2, lc2),
            null,
            NestedSetBuilder.create(Order.STABLE_ORDER, Event.warn("analysis warning 2")));
    skyframeExecutor.configureActionExecutor(/* fileCache= */ null, ActionInputPrefetcher.NONE);
    skyframeExecutor
        .getDifferencerForTesting()
        .inject(ImmutableMap.of(lc1, Delta.justNew(ctValue1), lc2, Delta.justNew(ctValue2)));
    // Do a null build, so that the skyframe executor initializes the action executor properly.
    skyframeExecutor.setActionOutputRoot(getOutputPath());
    skyframeExecutor.setActionExecutionProgressReportingObjects(
        EMPTY_PROGRESS_SUPPLIER,
        EMPTY_COMPLETION_RECEIVER,
        ActionExecutionStatusReporter.create(reporter));
    var unused =
        skyframeExecutor.buildArtifacts(
            reporter,
            ResourceManager.instanceForTestingOnly(),
            new DummyExecutor(fileSystem, rootDirectory),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            options,
            NULL_CHECKER,
            ActionOutputDirectoryHelper.createForTesting(),
            null,
            null);

    skyframeExecutor.prepareBuildingForTestingOnly(
        reporter,
        new DummyExecutor(fileSystem, rootDirectory),
        options,
        NULL_CHECKER,
        ActionOutputDirectoryHelper.createForTesting());

    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setParallelism(SequencedSkyframeExecutor.DEFAULT_THREAD_COUNT)
            .setEventHandler(reporter)
            .setMergingSkyframeAnalysisExecutionPhases(skymeld)
            .build();
    evaluateWithEvaluationContext(ImmutableList.of(Artifact.key(output2)), evaluationContext);
    assertContainsEvent("action 1");
    assertContainsEvent("action 2");
    assertDoesNotContainEvent("analysis warning 1");
    assertDoesNotContainEvent("analysis warning 2");

    // Action's warnings are not stored, and configured target warnings never seen.
    assertThat(
            ValueWithMetadata.getEvents(
                    skyframeExecutor
                        .getEvaluator()
                        .getExistingEntryAtCurrentlyEvaluatingVersion(
                            ActionLookupData.create(lc1, 0))
                        .getValueMaybeWithMetadata())
                .toList())
        .isEmpty();
    assertThat(
            ValueWithMetadata.getEvents(
                    skyframeExecutor
                        .getEvaluator()
                        .getExistingEntryAtCurrentlyEvaluatingVersion(
                            ActionLookupData.create(lc2, 0))
                        .getValueMaybeWithMetadata())
                .toList())
        .isEmpty();
  }

  private static class WarningAction extends AbstractAction {
    private final String warningText;

    private WarningAction(ImmutableList<Artifact> inputs, Artifact output, String warningText) {
      super(
          NULL_ACTION_OWNER,
          NestedSetBuilder.<Artifact>stableOrder().addAll(inputs).build(),
          ImmutableSet.of(output));
      this.warningText = warningText;
    }

    @Override
    public String getMnemonic() {
      return "warning action";
    }

    @Override
    protected void computeKey(
        ActionKeyContext actionKeyContext,
        @Nullable ArtifactExpander artifactExpander,
        Fingerprint fp) {
      fp.addString(warningText);
      fp.addPath(getPrimaryOutput().getExecPath());
    }

    @Override
    public ActionResult execute(ActionExecutionContext actionExecutionContext)
        throws ActionExecutionException {
      actionExecutionContext.getEventHandler().handle(Event.warn(warningText));
      try {
        FileSystemUtils.createEmptyFile(actionExecutionContext.getInputPath(getPrimaryOutput()));
      } catch (IOException e) {
        throw new ActionExecutionException(
            e, this, false, CrashFailureDetails.detailedExitCodeForThrowable(e));
      }
      return ActionResult.EMPTY;
    }
  }

  /** Dummy action that throws a catastrophic error when it runs. */
  private static class CatastrophicAction extends DummyAction {
    public static final DetailedExitCode expectedDetailedExitCode =
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setCrash(Crash.newBuilder().setCode(Crash.Code.CRASH_UNKNOWN))
                .build());

    CatastrophicAction(Artifact output) {
      super(NestedSetBuilder.emptySet(Order.STABLE_ORDER), output);
    }

    @Override
    public ActionResult execute(ActionExecutionContext actionExecutionContext)
        throws ActionExecutionException {
      throw new ActionExecutionException(
          "message",
          new Exception("just cause"),
          this,
          /* catastrophe= */ true,
          expectedDetailedExitCode);
    }
  }

  /** Dummy action that flips a boolean when it runs. */
  private static class MarkerAction extends DummyAction {
    private final AtomicBoolean executed;

    MarkerAction(Artifact output, AtomicBoolean executed) {
      super(NestedSetBuilder.emptySet(Order.STABLE_ORDER), output);
      this.executed = executed;
      assertThat(executed.get()).isFalse();
    }

    @Override
    public ActionResult execute(ActionExecutionContext actionExecutionContext)
        throws ActionExecutionException, InterruptedException {
      ActionResult actionResult = super.execute(actionExecutionContext);
      assertThat(executed.getAndSet(true)).isFalse();
      return actionResult;
    }
  }

  private void setupEmbeddedArtifacts() throws IOException {
    List<String> embeddedTools = analysisMock.getEmbeddedTools();
    directories.getEmbeddedBinariesRoot().createDirectoryAndParents();
    for (String embeddedToolName : embeddedTools) {
      Path toolPath = directories.getEmbeddedBinariesRoot().getRelative(embeddedToolName);
      FileSystemUtils.touchFile(toolPath);
    }
  }

  /** Test appropriate behavior when an action halts the build with a catastrophic failure. */
  private void runCatastropheHaltsBuild() throws Exception {
    Path root = getExecRoot();
    PathFragment execPath = PathFragment.create("out").getRelative("dir");
    ActionLookupKey lc1 = new InjectedActionLookupKey("lc1");
    Artifact output =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath.getRelative("foo"),
            lc1);
    Action action1 = new CatastrophicAction(output);
    ActionLookupValue ctValue1 = createActionLookupValue(action1, lc1);
    ActionLookupKey lc2 = new InjectedActionLookupKey("lc2");
    Artifact output2 =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath.getRelative("bar"),
            lc2);
    AtomicBoolean markerRan = new AtomicBoolean(false);
    Action action2 = new MarkerAction(output2, markerRan);
    ActionLookupValue ctValue2 = createActionLookupValue(action2, lc2);

    // Perform testing-related setup.
    skyframeExecutor
        .getDifferencerForTesting()
        .inject(ImmutableMap.of(lc1, Delta.justNew(ctValue1), lc2, Delta.justNew(ctValue2)));
    TopLevelTargetBuiltEventCollector collector = new TopLevelTargetBuiltEventCollector();
    skyframeExecutor.setEventBus(new EventBus());
    skyframeExecutor.getEventBus().register(collector);
    setupEmbeddedArtifacts();
    skyframeExecutor.setActionOutputRoot(getOutputPath());
    skyframeExecutor.setActionExecutionProgressReportingObjects(
        EMPTY_PROGRESS_SUPPLIER,
        EMPTY_COMPLETION_RECEIVER,
        ActionExecutionStatusReporter.create(reporter));

    reporter.removeHandler(failFastHandler); // Expect errors.
    Builder builder =
        new SkyframeBuilder(
            skyframeExecutor,
            ResourceManager.instanceForTestingOnly(),
            NULL_CHECKER,
            ModifiedFileSet.EVERYTHING_MODIFIED,
            /* fileCache= */ null,
            ActionInputPrefetcher.NONE,
            ActionOutputDirectoryHelper.createForTesting(),
            BugReporter.defaultInstance());
    // Note that since ImmutableSet iterates through its elements in the order they are passed in
    // here, we are guaranteed that output will be built before output2, throwing an exception and
    // shutting down the build before output2 is requested.
    Set<Artifact> normalArtifacts = ImmutableSet.of(output, output2);
    try {
      BuildFailedException e =
          assertThrows(
              BuildFailedException.class,
              () ->
                  builder.buildArtifacts(
                      reporter,
                      normalArtifacts,
                      ImmutableSet.of(),
                      ImmutableSet.of(),
                      ImmutableSet.of(),
                      ImmutableSet.of(),
                      ImmutableSet.of(),
                      new DummyExecutor(fileSystem, rootDirectory),
                      options,
                      null,
                      null,
                      OutputChecker.TRUST_LOCAL_ONLY));
      // The catastrophic exception should be propagated into the BuildFailedException whether or
      // not --keep_going is set.
      assertThat(e.getDetailedExitCode()).isEqualTo(CatastrophicAction.expectedDetailedExitCode);
      assertThat(collector.getCollectedEvents()).isEmpty();
      assertThat(markerRan.get()).isFalse();
    } finally {
      skyframeExecutor.getEventBus().unregister(collector);
    }
  }

  private static ActionLookupValue createActionLookupValue(
      ActionAnalysisMetadata generatingAction, ActionLookupKey actionLookupKey)
      throws ActionConflictException,
          InterruptedException,
          Actions.ArtifactGeneratedByOtherRuleException {
    ImmutableList<ActionAnalysisMetadata> actions = ImmutableList.of(generatingAction);
    Actions.assignOwnersAndThrowIfConflict(new ActionKeyContext(), actions, actionLookupKey);
    return new BasicActionLookupValue(actions);
  }

  @Test
  public void testCatastropheInNoKeepGoing() throws Exception {
    options.parse("--nokeep_going", "--jobs=1");
    runCatastropheHaltsBuild();
  }

  @Test
  public void testCatastrophicBuild() throws Exception {
    options.parse("--keep_going", "--jobs=1");
    runCatastropheHaltsBuild();
  }

  /**
   * Test appropriate behavior when an action halts the build with a transitive catastrophic
   * failure.
   */
  @Test
  public void testTransitiveCatastropheHaltsBuild() throws Exception {
    options.parse("--keep_going", "--jobs=5");

    Path root = getExecRoot();
    PathFragment execPath = PathFragment.create("out").getRelative("dir");
    ActionLookupKey catastropheCTK = new InjectedActionLookupKey("catastrophe");
    Artifact catastropheArtifact =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath.getRelative("zcatas"),
            catastropheCTK);
    CountDownLatch failureHappened = new CountDownLatch(1);
    Action catastrophicAction =
        new CatastrophicAction(catastropheArtifact) {
          @Override
          public ActionResult execute(ActionExecutionContext actionExecutionContext)
              throws ActionExecutionException {
            TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                failureHappened, "didn't count failure");
            return super.execute(actionExecutionContext);
          }
        };
    ActionLookupValue catastropheALV = createActionLookupValue(catastrophicAction, catastropheCTK);
    ActionLookupKey failureCTK = new InjectedActionLookupKey("failure");
    Artifact failureArtifact =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath.getRelative("fail"),
            failureCTK);
    Action failureAction = new FailedExecAction(failureArtifact, USER_DETAILED_EXIT_CODE);
    ActionLookupValue failureALV = createActionLookupValue(failureAction, failureCTK);
    ActionLookupKey topCTK = new InjectedActionLookupKey("top");
    Artifact topArtifact =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath.getRelative("top"),
            topCTK);
    Action topAction =
        new DummyAction(
            NestedSetBuilder.create(Order.STABLE_ORDER, failureArtifact, catastropheArtifact),
            topArtifact);
    ActionLookupValue topALV = createActionLookupValue(topAction, topCTK);
    // Perform testing-related setup.
    skyframeExecutor
        .getDifferencerForTesting()
        .inject(
            ImmutableMap.of(
                catastropheCTK, Delta.justNew(catastropheALV),
                failureCTK, Delta.justNew(failureALV),
                topCTK, Delta.justNew(topALV)));
    skyframeExecutor
        .getEvaluator()
        .injectGraphTransformerForTesting(
            DeterministicHelper.makeTransformer(
                (key, type, order, context) -> {
                  if (key.equals(Artifact.key(failureArtifact)) && type == EventType.SET_VALUE) {
                    failureHappened.countDown();
                  }
                },
                /* deterministic= */ true));
    TopLevelTargetBuiltEventCollector collector = new TopLevelTargetBuiltEventCollector();
    skyframeExecutor.setEventBus(new EventBus());
    skyframeExecutor.getEventBus().register(collector);
    setupEmbeddedArtifacts();
    skyframeExecutor.setActionOutputRoot(getOutputPath());
    skyframeExecutor.setActionExecutionProgressReportingObjects(
        EMPTY_PROGRESS_SUPPLIER,
        EMPTY_COMPLETION_RECEIVER,
        ActionExecutionStatusReporter.create(reporter));

    reporter.removeHandler(failFastHandler); // Expect errors.
    Builder builder =
        new SkyframeBuilder(
            skyframeExecutor,
            ResourceManager.instanceForTestingOnly(),
            NULL_CHECKER,
            ModifiedFileSet.EVERYTHING_MODIFIED,
            /* fileCache= */ null,
            ActionInputPrefetcher.NONE,
            ActionOutputDirectoryHelper.createForTesting(),
            BugReporter.defaultInstance());
    Set<Artifact> normalArtifacts = ImmutableSet.of(topArtifact);
    try {
      BuildFailedException e =
          assertThrows(
              BuildFailedException.class,
              () ->
                  builder.buildArtifacts(
                      reporter,
                      normalArtifacts,
                      ImmutableSet.of(),
                      ImmutableSet.of(),
                      ImmutableSet.of(),
                      ImmutableSet.of(),
                      ImmutableSet.of(),
                      new DummyExecutor(fileSystem, rootDirectory),
                      options,
                      null,
                      null,
                      OutputChecker.TRUST_LOCAL_ONLY));
      // The catastrophic exception should be propagated into the BuildFailedException whether or
      // not --keep_going is set.
      assertThat(e.getDetailedExitCode()).isEqualTo(CatastrophicAction.expectedDetailedExitCode);
      assertThat(collector.getCollectedEvents()).isEmpty();
    } finally {
      skyframeExecutor.getEventBus().unregister(collector);
    }
  }

  /**
   * Test appropriate behavior when an action halts the build with a transitive catastrophic
   * failure.
   */
  @Test
  public void testCatastropheAndNonCatastropheInCompletion() throws Exception {
    options.parse("--keep_going", "--jobs=5");

    Path root = getExecRoot();
    PathFragment execPath = PathFragment.create("out").getRelative("dir");
    ActionLookupKey configuredTargetKey = new InjectedActionLookupKey("key");
    Artifact catastropheArtifact =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath.getRelative("catas"),
            configuredTargetKey);
    int failedSize = 100;
    CountDownLatch failureHappened = new CountDownLatch(failedSize);
    Action catastrophicAction =
        new CatastrophicAction(catastropheArtifact) {
          @Override
          public ActionResult execute(ActionExecutionContext actionExecutionContext)
              throws ActionExecutionException {
            TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                failureHappened, "didn't count failure");
            return super.execute(actionExecutionContext);
          }
        };
    // Because of random map ordering when getting values back in CompletionFunction, we just
    // sprinkle our failure nodes randomly about the alphabet, trusting that at least one will come
    // before "catas".
    List<Action> failedActions = new ArrayList<>(failedSize);
    LinkedHashSet<Artifact> failedArtifacts = new LinkedHashSet<>();
    for (int i = 0; i < failedSize; i++) {
      String failString = HashCode.fromBytes(("fail" + i).getBytes(UTF_8)).toString();
      Artifact failureArtifact =
          DerivedArtifact.create(
              ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
              execPath.getRelative(failString),
              configuredTargetKey);
      failedArtifacts.add(failureArtifact);
      failedActions.add(new FailedExecAction(failureArtifact, USER_DETAILED_EXIT_CODE));
    }
    var actions =
        ImmutableList.<ActionAnalysisMetadata>builder()
            .add(catastrophicAction)
            .addAll(failedActions)
            .build();
    Actions.assignOwnersAndThrowIfConflictToleratingSharedActions(
        new ActionKeyContext(), actions, configuredTargetKey);
    ActionLookupValue nonRuleActionLookupValue = new BasicActionLookupValue(actions);
    HashSet<ActionLookupData> failedActionKeys = new HashSet<>();
    for (Action failedAction : failedActions) {
      failedActionKeys.add(
          ((Artifact.DerivedArtifact) failedAction.getPrimaryOutput()).getGeneratingActionKey());
    }

    // Perform testing-related setup.
    skyframeExecutor
        .getDifferencerForTesting()
        .inject(ImmutableMap.of(configuredTargetKey, Delta.justNew(nonRuleActionLookupValue)));
    skyframeExecutor
        .getEvaluator()
        .injectGraphTransformerForTesting(
            DeterministicHelper.makeTransformer(
                (key, type, order, context) -> {
                  if ((key instanceof ActionLookupData)
                      && failedActionKeys.contains(key)
                      && type == EventType.SET_VALUE) {
                    failureHappened.countDown();
                  }
                },
                // Determinism actually doesn't help here because the internal maps are still
                // effectively unordered.
                /* deterministic= */ true));
    TopLevelTargetBuiltEventCollector collector = new TopLevelTargetBuiltEventCollector();
    skyframeExecutor.setEventBus(new EventBus());
    skyframeExecutor.getEventBus().register(collector);
    setupEmbeddedArtifacts();
    skyframeExecutor.setActionOutputRoot(getOutputPath());
    skyframeExecutor.setActionExecutionProgressReportingObjects(
        EMPTY_PROGRESS_SUPPLIER,
        EMPTY_COMPLETION_RECEIVER,
        ActionExecutionStatusReporter.create(reporter));

    reporter.removeHandler(failFastHandler); // Expect errors.
    Builder builder =
        new SkyframeBuilder(
            skyframeExecutor,
            ResourceManager.instanceForTestingOnly(),
            NULL_CHECKER,
            ModifiedFileSet.EVERYTHING_MODIFIED,
            /* fileCache= */ null,
            ActionInputPrefetcher.NONE,
            ActionOutputDirectoryHelper.createForTesting(),
            BugReporter.defaultInstance());
    try {
      BuildFailedException e =
          assertThrows(
              BuildFailedException.class,
              () ->
                  builder.buildArtifacts(
                      reporter,
                      ImmutableSet.<Artifact>builder()
                          .addAll(failedArtifacts)
                          .add(catastropheArtifact)
                          .build(),
                      ImmutableSet.of(),
                      ImmutableSet.of(),
                      ImmutableSet.of(),
                      ImmutableSet.of(),
                      ImmutableSet.of(),
                      new DummyExecutor(fileSystem, rootDirectory),
                      options,
                      null,
                      new TopLevelArtifactContext(
                          /* runTestsExclusively= */ false,
                          false,
                          false,
                          OutputGroupInfo.determineOutputGroups(
                              ImmutableList.of(),
                              OutputGroupInfo.ValidationMode.OUTPUT_GROUP,
                              /* shouldRunTests= */ false)),
                      OutputChecker.TRUST_LOCAL_ONLY));
      // The catastrophic exception should be propagated into the BuildFailedException whether or
      // not --keep_going is set.
      assertThat(e.getDetailedExitCode()).isEqualTo(CatastrophicAction.expectedDetailedExitCode);
      assertThat(collector.getCollectedEvents()).isEmpty();
    } finally {
      skyframeExecutor.getEventBus().unregister(collector);
    }
  }

  @Test
  public void testCatastrophicBuildWithoutEdges() throws Exception {
    options.parse("--keep_going", "--jobs=1", "--discard_analysis_cache");
    skyframeExecutor.setActive(false);
    skyframeExecutor.decideKeepIncrementalState(
        /* batch= */ true,
        /* keepStateAfterBuild= */ true,
        /* shouldTrackIncrementalState= */ true,
        /* heuristicallyDropNodes= */ false,
        /* discardAnalysisCache= */ true,
        reporter);
    skyframeExecutor.setActive(true);
    runCatastropheHaltsBuild();
  }

  @Test
  public void testCatastropheReportingWithError() throws Exception {
    options.parse("--keep_going", "--jobs=1");
    Path root = getExecRoot();
    PathFragment execPath = PathFragment.create("out").getRelative("dir");
    // When we have an action that throws a (non-catastrophic) exception when it is executed,
    ActionLookupKey failedKey = new InjectedActionLookupKey("failed");
    Artifact failedOutput =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath.getRelative("failed"),
            failedKey);
    AtomicReference<Action> failedActionReference = new AtomicReference<>();
    Action failedAction =
        new TestAction(
            new Callable<Void>() {
              @Override
              public Void call() throws ActionExecutionException {
                throw new ActionExecutionException(
                    "typical non-catastrophic user failure",
                    failedActionReference.get(),
                    /* catastrophe= */ false,
                    USER_DETAILED_EXIT_CODE);
              }
            },
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            ImmutableSet.of(failedOutput));
    failedActionReference.set(failedAction);
    ActionLookupValue failedTarget = createActionLookupValue(failedAction, failedKey);

    // And an action that throws a catastrophic exception when it is executed,
    ActionLookupKey catastrophicKey = new InjectedActionLookupKey("catastrophic");
    Artifact catastrophicOutput =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath.getRelative("catastrophic"),
            catastrophicKey);
    Action catastrophicAction = new CatastrophicAction(catastrophicOutput);
    ActionLookupValue catastrophicTarget =
        createActionLookupValue(catastrophicAction, catastrophicKey);

    // And the relevant configured targets have been injected into the graph,
    skyframeExecutor
        .getDifferencerForTesting()
        .inject(
            ImmutableMap.of(
                failedKey, Delta.justNew(failedTarget),
                catastrophicKey, Delta.justNew(catastrophicTarget)));
    TopLevelTargetBuiltEventCollector collector = new TopLevelTargetBuiltEventCollector();
    skyframeExecutor.setEventBus(new EventBus());
    skyframeExecutor.getEventBus().register(collector);
    setupEmbeddedArtifacts();
    skyframeExecutor.setActionOutputRoot(getOutputPath());
    skyframeExecutor.setActionExecutionProgressReportingObjects(
        EMPTY_PROGRESS_SUPPLIER,
        EMPTY_COMPLETION_RECEIVER,
        ActionExecutionStatusReporter.create(reporter));

    // And the two artifacts are requested,
    reporter.removeHandler(failFastHandler); // Expect errors.
    Builder builder =
        new SkyframeBuilder(
            skyframeExecutor,
            ResourceManager.instanceForTestingOnly(),
            NULL_CHECKER,
            ModifiedFileSet.EVERYTHING_MODIFIED,
            /* fileCache= */ null,
            ActionInputPrefetcher.NONE,
            ActionOutputDirectoryHelper.createForTesting(),
            BugReporter.defaultInstance());
    // Note that since ImmutableSet iterates through its elements in the order they are passed in
    // here, we are guaranteed that failedOutput will be built before catastrophicOutput is
    // requested, putting a top-level failure into the build result.
    Set<Artifact> normalArtifacts = ImmutableSet.of(failedOutput, catastrophicOutput);
    try {
      BuildFailedException e =
          assertThrows(
              BuildFailedException.class,
              () ->
                  builder.buildArtifacts(
                      reporter,
                      normalArtifacts,
                      ImmutableSet.of(),
                      ImmutableSet.of(),
                      ImmutableSet.of(),
                      ImmutableSet.of(),
                      ImmutableSet.of(),
                      new DummyExecutor(fileSystem, rootDirectory),
                      options,
                      null,
                      null,
                      OutputChecker.TRUST_LOCAL_ONLY));
      // The catastrophic exception should be propagated into the BuildFailedException whether or
      // not --keep_going is set.
      assertThat(e.getDetailedExitCode()).isEqualTo(CatastrophicAction.expectedDetailedExitCode);
      assertThat(collector.getCollectedEvents()).isEmpty();
    } finally {
      skyframeExecutor.getEventBus().unregister(collector);
    }
  }

  /** Dummy action that throws a ActionExecution error when it runs. */
  private static class FailedExecAction extends DummyAction {
    private final DetailedExitCode detailedExitCode;

    FailedExecAction(Artifact output, DetailedExitCode detailedExitCode) {
      super(NestedSetBuilder.emptySet(Order.STABLE_ORDER), output);
      this.detailedExitCode = detailedExitCode;
    }

    @Override
    public ActionResult execute(ActionExecutionContext actionExecutionContext)
        throws ActionExecutionException {
      throw new ActionExecutionException(
          "foo", new Exception("bar"), this, /* catastrophe= */ false, detailedExitCode);
    }
  }

  /**
   * Verify SkyframeBuilder returns correct user error code as global error code when:
   *
   * <ol>
   *   <li>keepGoing mode is true.
   *   <li>user error code exists.
   *   <li>no infrastructure error code exists.
   * </ol>
   */
  @Test
  public void testKeepGoingExitCodeWithUserError() throws Exception {
    options.parse("--keep_going", "--jobs=1");
    Path root = getExecRoot();
    PathFragment execPath = PathFragment.create("out").getRelative("dir");

    ActionLookupKey succeededKey = new InjectedActionLookupKey("succeeded");
    Artifact succeededOutput =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath.getRelative("succeeded"),
            succeededKey);

    ActionLookupKey failedKey = new InjectedActionLookupKey("failed");
    Artifact failedOutput =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath.getRelative("failed"),
            failedKey);

    // Create 1 succeeded key and 1 failed key with user error
    Action succeededAction =
        new DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), succeededOutput);
    ActionLookupValue succeededTarget = createActionLookupValue(succeededAction, succeededKey);
    Action failedAction = new FailedExecAction(failedOutput, USER_DETAILED_EXIT_CODE);
    ActionLookupValue failedTarget = createActionLookupValue(failedAction, failedKey);

    // Inject the targets into the graph,
    skyframeExecutor
        .getDifferencerForTesting()
        .inject(
            ImmutableMap.of(
                succeededKey, Delta.justNew(succeededTarget),
                failedKey, Delta.justNew(failedTarget)));
    skyframeExecutor.setEventBus(new EventBus());
    setupEmbeddedArtifacts();
    skyframeExecutor.setActionOutputRoot(getOutputPath());
    skyframeExecutor.setActionExecutionProgressReportingObjects(
        EMPTY_PROGRESS_SUPPLIER,
        EMPTY_COMPLETION_RECEIVER,
        ActionExecutionStatusReporter.create(reporter));

    // And the two artifacts are requested,
    reporter.removeHandler(failFastHandler); // Expect errors.
    Builder builder =
        new SkyframeBuilder(
            skyframeExecutor,
            ResourceManager.instanceForTestingOnly(),
            NULL_CHECKER,
            ModifiedFileSet.EVERYTHING_MODIFIED,
            /* fileCache= */ null,
            ActionInputPrefetcher.NONE,
            ActionOutputDirectoryHelper.createForTesting(),
            BugReporter.defaultInstance());
    Set<Artifact> normalArtifacts = ImmutableSet.of(succeededOutput, failedOutput);
    BuildFailedException e =
        assertThrows(
            BuildFailedException.class,
            () ->
                builder.buildArtifacts(
                    reporter,
                    normalArtifacts,
                    ImmutableSet.of(),
                    ImmutableSet.of(),
                    ImmutableSet.of(),
                    ImmutableSet.of(),
                    ImmutableSet.of(),
                    new DummyExecutor(fileSystem, rootDirectory),
                    options,
                    null,
                    null,
                    OutputChecker.TRUST_LOCAL_ONLY));
    // The exit code should be propagated into the BuildFailedException whether or not --keep_going
    // is set.
    assertThat(e.getDetailedExitCode()).isEqualTo(USER_DETAILED_EXIT_CODE);
  }

  /**
   * Verify SkyframeBuilder returns correct infrastructure error code as global error code when:
   *
   * <ol>
   *   <li>keepGoing mode is true.
   *   <li>infrastructure error code exists.
   * </ol>
   */
  @Test
  public void testKeepGoingExitCodeWithUserAndInfrastructureError() throws Exception {
    options.parse("--keep_going", "--jobs=1");
    Path root = getExecRoot();
    PathFragment execPath = PathFragment.create("out").getRelative("dir");

    ActionLookupKey succeededKey = new InjectedActionLookupKey("succeeded");
    Artifact succeededOutput =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath.getRelative("succeeded"),
            succeededKey);

    ActionLookupKey failedKey1 = new InjectedActionLookupKey("failed1");
    Artifact failedOutput1 =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath.getRelative("failed1"),
            failedKey1);

    ActionLookupKey failedKey2 = new InjectedActionLookupKey("failed2");
    Artifact failedOutput2 =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath.getRelative("failed2"),
            failedKey2);

    // Create 1 succeeded key, 1 failed key with infrastructure error and another failed key with
    // user error.

    Action succeededAction =
        new DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), succeededOutput);
    ActionLookupValue succeededTarget = createActionLookupValue(succeededAction, succeededKey);
    Action failedAction1 = new FailedExecAction(failedOutput1, USER_DETAILED_EXIT_CODE);
    ActionLookupValue failedTarget1 = createActionLookupValue(failedAction1, failedKey1);
    Action failedAction2 = new FailedExecAction(failedOutput2, INFRA_DETAILED_EXIT_CODE);
    ActionLookupValue failedTarget2 = createActionLookupValue(failedAction2, failedKey2);

    // Inject the targets into the graph,
    skyframeExecutor
        .getDifferencerForTesting()
        .inject(
            ImmutableMap.of(
                succeededKey, Delta.justNew(succeededTarget),
                failedKey1, Delta.justNew(failedTarget1),
                failedKey2, Delta.justNew(failedTarget2)));
    skyframeExecutor.setEventBus(new EventBus());
    setupEmbeddedArtifacts();
    skyframeExecutor.setActionOutputRoot(getOutputPath());
    skyframeExecutor.setActionExecutionProgressReportingObjects(
        EMPTY_PROGRESS_SUPPLIER,
        EMPTY_COMPLETION_RECEIVER,
        ActionExecutionStatusReporter.create(reporter));

    // And the two artifacts are requested,
    reporter.removeHandler(failFastHandler); // Expect errors.
    Builder builder =
        new SkyframeBuilder(
            skyframeExecutor,
            ResourceManager.instanceForTestingOnly(),
            NULL_CHECKER,
            ModifiedFileSet.EVERYTHING_MODIFIED,
            /* fileCache= */ null,
            ActionInputPrefetcher.NONE,
            ActionOutputDirectoryHelper.createForTesting(),
            BugReporter.defaultInstance());
    Set<Artifact> normalArtifacts = ImmutableSet.of(failedOutput1, failedOutput2);
    BuildFailedException e =
        assertThrows(
            BuildFailedException.class,
            () ->
                builder.buildArtifacts(
                    reporter,
                    normalArtifacts,
                    ImmutableSet.of(),
                    ImmutableSet.of(),
                    ImmutableSet.of(),
                    ImmutableSet.of(),
                    ImmutableSet.of(),
                    new DummyExecutor(fileSystem, rootDirectory),
                    options,
                    null,
                    null,
                    OutputChecker.TRUST_LOCAL_ONLY));
    // The exit code should be propagated into the BuildFailedException whether or not --keep_going
    // is set.
    assertThat(e.getDetailedExitCode()).isEqualTo(INFRA_DETAILED_EXIT_CODE);
  }

  /**
   * Tests that when an input-discovering action terminates input discovery with missing inputs, its
   * progress message goes away. We create an input-discovering action that declares a new input.
   * When that new input is declared, which comes after the scanning is completed, we trigger a
   * progress message, and assert that the message does not contain the "Scanning" message.
   *
   * <p>To guard against the output format changing, we also trigger a progress message during the
   * scan, and assert that the message there is as expected.
   */
  @Test
  public void inputDiscoveryMessageDoesntLinger() throws Exception {
    Path root = getExecRoot();
    PathFragment execPath = PathFragment.create("out").getRelative("dir");

    ActionLookupKey topKey = new InjectedActionLookupKey("top");
    Artifact topOutput =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
            execPath.getRelative("top"),
            topKey);

    Artifact sourceInput =
        new Artifact.SourceArtifact(
            ArtifactRoot.asSourceRoot(Root.fromPath(rootDirectory)),
            PathFragment.create("source.optional"),
            ArtifactOwner.NULL_OWNER);
    FileSystemUtils.createEmptyFile(sourceInput.getPath());

    Action inputDiscoveringAction =
        new DummyAction(NestedSetBuilder.create(Order.STABLE_ORDER, sourceInput), topOutput) {
          @Override
          public NestedSet<Artifact> discoverInputs(ActionExecutionContext actionExecutionContext) {
            skyframeExecutor
                .getActionExecutionStatusReporterForTesting()
                .showCurrentlyExecutingActions("during scanning ");
            return super.discoverInputs(actionExecutionContext);
          }
        };

    ActionLookupValue topTarget = createActionLookupValue(inputDiscoveringAction, topKey);
    skyframeExecutor
        .getDifferencerForTesting()
        .inject(ImmutableMap.of(topKey, Delta.justNew(topTarget)));
    // Collect all events.
    eventCollector = new EventCollector();
    reporter = new Reporter(eventBus, eventCollector);
    skyframeExecutor.setEventBus(eventBus);
    skyframeExecutor.setActionOutputRoot(getOutputPath());

    Builder builder =
        new SkyframeBuilder(
            skyframeExecutor,
            ResourceManager.instanceForTestingOnly(),
            NULL_CHECKER,
            ModifiedFileSet.EVERYTHING_MODIFIED,
            /* fileCache= */ null,
            ActionInputPrefetcher.NONE,
            ActionOutputDirectoryHelper.createForTesting(),
            BugReporter.defaultInstance());
    builder.buildArtifacts(
        reporter,
        ImmutableSet.of(topOutput),
        ImmutableSet.of(),
        ImmutableSet.of(),
        ImmutableSet.of(),
        ImmutableSet.of(),
        ImmutableSet.of(),
        new DummyExecutor(fileSystem, rootDirectory),
        options,
        null,
        null,
        OutputChecker.TRUST_LOCAL_ONLY);
    MoreAsserts.assertContainsEvent(
        eventCollector, Pattern.compile(".*during scanning.*\n.*Scanning.*\n.*Test dir/top.*"));
    MoreAsserts.assertNotContainsEvent(
        eventCollector, Pattern.compile(".*after scanning.*\n.*Scanning.*\n.*Test dir/top.*"));
  }

  @Test
  public void rewindingPrerequisites(@TestParameter boolean trackIncrementalState)
      throws Exception {
    initializeSkyframeExecutor();
    options.parse("--rewind_lost_inputs");

    skyframeExecutor.setActive(false);
    skyframeExecutor.decideKeepIncrementalState(
        /* batch= */ false,
        /* keepStateAfterBuild= */ true,
        trackIncrementalState,
        /* heuristicallyDropNodes= */ false,
        /* discardAnalysisCache= */ false,
        reporter);
    skyframeExecutor.setActive(true);

    syncSkyframeExecutor(); // Permitted.
  }

  private void syncSkyframeExecutor() throws InterruptedException, AbruptExitException {
    var unused =
        skyframeExecutor.sync(
            reporter,
            skyframeExecutor.getPackageLocator().get(),
            UUID.randomUUID(),
            /* clientEnv= */ ImmutableMap.of(),
            /* repoEnvOption= */ ImmutableMap.of(),
            tsgm,
            QuiescingExecutorsImpl.forTesting(),
            options,
            /* commandName= */ "build");
  }
}
