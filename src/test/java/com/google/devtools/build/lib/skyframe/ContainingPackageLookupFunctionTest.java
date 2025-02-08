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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.testing.EqualsTester;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodRepoRuleValue;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.io.FileSymlinkCycleUniquenessFunction;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.WorkspaceFileValue;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.rules.repository.LocalRepositoryFunction;
import com.google.devtools.build.lib.rules.repository.LocalRepositoryRule;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction;
import com.google.devtools.build.lib.skyframe.ContainingPackageLookupValue.ContainingPackage;
import com.google.devtools.build.lib.skyframe.ContainingPackageLookupValue.NoContainingPackage;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.skyframe.PackageLookupValue.ErrorReason;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.FileStateKey;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequencedRecordingDifferencer;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.starlark.java.eval.StarlarkSemantics;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ContainingPackageLookupFunction}. */
@RunWith(JUnit4.class)
public class ContainingPackageLookupFunctionTest extends FoundationTestCase {

  private AtomicReference<ImmutableSet<PackageIdentifier>> deletedPackages;
  private MemoizingEvaluator evaluator;

  @Before
  public final void setUp() throws Exception {
    AnalysisMock analysisMock = AnalysisMock.get();

    AtomicReference<PathPackageLocator> pkgLocator =
        new AtomicReference<>(
            new PathPackageLocator(
                outputBase,
                ImmutableList.of(Root.fromPath(rootDirectory)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY));
    deletedPackages = new AtomicReference<>(ImmutableSet.of());
    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(rootDirectory, outputBase, outputBase),
            rootDirectory,
            /* defaultSystemJavabase= */ null,
            analysisMock.getProductName());
    ExternalFilesHelper externalFilesHelper =
        ExternalFilesHelper.createForTesting(
            pkgLocator,
            ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
            directories);

    Map<SkyFunctionName, SkyFunction> skyFunctions = new HashMap<>();
    skyFunctions.put(SkyFunctions.CONTAINING_PACKAGE_LOOKUP, new ContainingPackageLookupFunction());

    skyFunctions.put(
        SkyFunctions.PACKAGE_LOOKUP,
        new PackageLookupFunction(
            deletedPackages,
            CrossRepositoryLabelViolationStrategy.ERROR,
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY,
            BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER));
    skyFunctions.put(SkyFunctions.PACKAGE, PackageFunction.newBuilder().build());
    skyFunctions.put(SkyFunctions.IGNORED_SUBDIRECTORIES, IgnoredSubdirectoriesFunction.NOOP);
    skyFunctions.put(
        FileStateKey.FILE_STATE,
        new FileStateFunction(
            Suppliers.ofInstance(new TimestampGranularityMonitor(BlazeClock.instance())),
            SyscallCache.NO_CACHE,
            externalFilesHelper));
    skyFunctions.put(SkyFunctions.FILE, new FileFunction(pkgLocator, directories));
    skyFunctions.put(SkyFunctions.DIRECTORY_LISTING, new DirectoryListingFunction());
    skyFunctions.put(
        SkyFunctions.DIRECTORY_LISTING_STATE,
        new DirectoryListingStateFunction(externalFilesHelper, SyscallCache.NO_CACHE));
    RuleClassProvider ruleClassProvider = analysisMock.createRuleClassProvider();
    skyFunctions.put(
        WorkspaceFileValue.WORKSPACE_FILE,
        new WorkspaceFileFunction(
            ruleClassProvider,
            analysisMock
                .getPackageFactoryBuilderForTesting(directories)
                .build(ruleClassProvider, fileSystem),
            directories,
            /*bzlLoadFunctionForInlining=*/ null));
    skyFunctions.put(
        SkyFunctions.EXTERNAL_PACKAGE,
        new ExternalPackageFunction(BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER));
    skyFunctions.put(
        SkyFunctions.LOCAL_REPOSITORY_LOOKUP,
        new LocalRepositoryLookupFunction(BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER));
    skyFunctions.put(
        FileSymlinkCycleUniquenessFunction.NAME, new FileSymlinkCycleUniquenessFunction());
    ImmutableMap<String, RepositoryFunction> repositoryHandlers =
        ImmutableMap.of(LocalRepositoryRule.NAME, new LocalRepositoryFunction());
    skyFunctions.put(
        SkyFunctions.REPOSITORY_DIRECTORY,
        new RepositoryDelegatorFunction(
            repositoryHandlers,
            null,
            new AtomicBoolean(true),
            ImmutableMap::of,
            directories,
            BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER));
    skyFunctions.put(
        SkyFunctions.REPOSITORY_MAPPING,
        new SkyFunction() {
          @Override
          public SkyValue compute(SkyKey skyKey, Environment env) {
            return RepositoryMappingValue.VALUE_FOR_ROOT_MODULE_WITHOUT_REPOS;
          }
        });
    skyFunctions.put(
        BzlmodRepoRuleValue.BZLMOD_REPO_RULE,
        new SkyFunction() {
          @Override
          public SkyValue compute(SkyKey skyKey, Environment env) {
            return BzlmodRepoRuleValue.REPO_RULE_NOT_FOUND_VALUE;
          }
        });

    RecordingDifferencer differencer = new SequencedRecordingDifferencer();
    evaluator = new InMemoryMemoizingEvaluator(skyFunctions, differencer);
    PrecomputedValue.BUILD_ID.set(differencer, UUID.randomUUID());
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get());
    PrecomputedValue.STARLARK_SEMANTICS.set(differencer, StarlarkSemantics.DEFAULT);
    RepositoryMappingFunction.REPOSITORY_OVERRIDES.set(differencer, ImmutableMap.of());
    RepositoryDelegatorFunction.FORCE_FETCH.set(
        differencer, RepositoryDelegatorFunction.FORCE_FETCH_DISABLED);
    RepositoryDelegatorFunction.DISABLE_NATIVE_REPO_RULES.set(differencer, false);
    RepositoryDelegatorFunction.VENDOR_DIRECTORY.set(differencer, Optional.empty());

    RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE.set(
        differencer, Optional.empty());
  }

  private ContainingPackageLookupValue lookupContainingPackage(String packageName)
      throws InterruptedException {
    return lookupContainingPackage(PackageIdentifier.createInMainRepo(packageName));
  }

  private ContainingPackageLookupValue lookupContainingPackage(PackageIdentifier packageIdentifier)
      throws InterruptedException {
    SkyKey key = ContainingPackageLookupValue.key(packageIdentifier);
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setParallelism(SkyframeExecutor.DEFAULT_THREAD_COUNT)
            .setEventHandler(NullEventHandler.INSTANCE)
            .build();
    return evaluator
        .<ContainingPackageLookupValue>evaluate(ImmutableList.of(key), evaluationContext)
        .get(key);
  }

  private PackageLookupValue lookupPackage(PackageIdentifier packageIdentifier)
      throws InterruptedException {
    SkyKey key = PackageLookupValue.key(packageIdentifier);
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setParallelism(SkyframeExecutor.DEFAULT_THREAD_COUNT)
            .setEventHandler(NullEventHandler.INSTANCE)
            .build();
    return evaluator
        .<PackageLookupValue>evaluate(ImmutableList.of(key), evaluationContext)
        .get(key);
  }

  @Test
  public void testNoContainingPackage() throws Exception {
    ContainingPackageLookupValue value = lookupContainingPackage("a/b");
    assertThat(value.hasContainingPackage()).isFalse();
  }

  @Test
  public void testContainingPackageIsParent() throws Exception {
    scratch.file("a/BUILD");
    ContainingPackageLookupValue value = lookupContainingPackage("a/b");
    assertThat(value.hasContainingPackage()).isTrue();
    assertThat(value.getContainingPackageName()).isEqualTo(PackageIdentifier.createInMainRepo("a"));
    assertThat(value.getContainingPackageRoot()).isEqualTo(Root.fromPath(rootDirectory));
  }

  @Test
  public void testContainingPackageIsSelf() throws Exception {
    scratch.file("a/b/BUILD");
    ContainingPackageLookupValue value = lookupContainingPackage("a/b");
    assertThat(value.hasContainingPackage()).isTrue();
    assertThat(value.getContainingPackageName())
        .isEqualTo(PackageIdentifier.createInMainRepo("a/b"));
    assertThat(value.getContainingPackageRoot()).isEqualTo(Root.fromPath(rootDirectory));
  }

  @Test
  public void testEqualsAndHashCodeContract() {
    ContainingPackageLookupValue valueA1 = ContainingPackageLookupValue.NONE;
    ContainingPackageLookupValue valueA2 = ContainingPackageLookupValue.NONE;
    ContainingPackageLookupValue valueB1 =
        ContainingPackageLookupValue.withContainingPackage(
            PackageIdentifier.createInMainRepo("b"), Root.fromPath(rootDirectory));
    ContainingPackageLookupValue valueB2 =
        ContainingPackageLookupValue.withContainingPackage(
            PackageIdentifier.createInMainRepo("b"), Root.fromPath(rootDirectory));
    PackageIdentifier cFrag = PackageIdentifier.createInMainRepo("c");
    ContainingPackageLookupValue valueC1 =
        ContainingPackageLookupValue.withContainingPackage(cFrag, Root.fromPath(rootDirectory));
    ContainingPackageLookupValue valueC2 =
        ContainingPackageLookupValue.withContainingPackage(cFrag, Root.fromPath(rootDirectory));
    ContainingPackageLookupValue valueCOther =
        ContainingPackageLookupValue.withContainingPackage(
            cFrag, Root.fromPath(rootDirectory.getRelative("other_root")));
    new EqualsTester()
        .addEqualityGroup(valueA1, valueA2)
        .addEqualityGroup(valueB1, valueB2)
        .addEqualityGroup(valueC1, valueC2)
        .addEqualityGroup(valueCOther)
        .testEquals();
  }

  @Test
  public void testNonExistentExternalRepositoryErrorReason() throws Exception {
    PackageIdentifier identifier =
        PackageIdentifier.create("some_repo", PathFragment.create(":atarget"));
    ContainingPackageLookupValue value = lookupContainingPackage(identifier);
    assertThat(value.hasContainingPackage()).isFalse();
    assertThat(value.getClass()).isEqualTo(NoContainingPackage.class);
    assertThat(value.getReasonForNoContainingPackage())
        .isEqualTo(
            "The repository '@@some_repo' could not be resolved: Repository '@@some_repo' is not"
                + " defined");
  }

  @Test
  public void testInvalidPackageLabelErrorReason() throws Exception {
    ContainingPackageLookupValue value = lookupContainingPackage("invalidpackagename:42/BUILD");
    assertThat(value.hasContainingPackage()).isFalse();
    assertThat(value.getClass()).isEqualTo(NoContainingPackage.class);
    // As for invalid package name we continue to climb up the parent packages,
    // we will find the top-level package with the path "" - empty string.
    assertThat(value.getReasonForNoContainingPackage()).isNull();
  }

  @Test
  public void testDeletedPackageErrorReason() throws Exception {
    PackageIdentifier identifier = PackageIdentifier.createInMainRepo("deletedpackage");
    deletedPackages.set(ImmutableSet.of(identifier));
    scratch.file("BUILD");

    PackageLookupValue packageLookupValue = lookupPackage(identifier);
    assertThat(packageLookupValue.packageExists()).isFalse();
    assertThat(packageLookupValue.getErrorReason()).isEqualTo(ErrorReason.DELETED_PACKAGE);
    assertThat(packageLookupValue.getErrorMsg())
        .isEqualTo("Package is considered deleted due to --deleted_packages");

    ContainingPackageLookupValue value = lookupContainingPackage(identifier);
    assertThat(value.hasContainingPackage()).isTrue();
    assertThat(value.getContainingPackageName().toString()).isEmpty();
    assertThat(value.getClass()).isEqualTo(ContainingPackage.class);
  }

  @Test
  public void testNoBuildFileErrorReason() throws Exception {
    ContainingPackageLookupValue value = lookupContainingPackage("abc");
    assertThat(value.hasContainingPackage()).isFalse();
    assertThat(value.getClass()).isEqualTo(NoContainingPackage.class);
    assertThat(value.getReasonForNoContainingPackage()).isNull();
  }
}
