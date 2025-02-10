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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodRepoRuleValue;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.io.FileSymlinkCycleUniquenessFunction;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.WorkspaceFileValue;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.FileStateKey;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
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
import java.util.concurrent.atomic.AtomicReference;
import net.starlark.java.eval.StarlarkSemantics;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link LocalRepositoryLookupFunction}. */
@RunWith(JUnit4.class)
public class LocalRepositoryLookupFunctionTest extends FoundationTestCase {

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
    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(rootDirectory, outputBase, rootDirectory),
            rootDirectory,
            /* defaultSystemJavabase= */ null,
            analysisMock.getProductName());
    ExternalFilesHelper externalFilesHelper =
        ExternalFilesHelper.createForTesting(
            pkgLocator,
            ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
            directories);

    Map<SkyFunctionName, SkyFunction> skyFunctions = new HashMap<>();
    skyFunctions.put(
        SkyFunctions.PACKAGE_LOOKUP,
        new PackageLookupFunction(
            new AtomicReference<>(),
            CrossRepositoryLabelViolationStrategy.ERROR,
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY,
            BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER));
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
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get());
    PrecomputedValue.STARLARK_SEMANTICS.set(differencer, StarlarkSemantics.DEFAULT);
    RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE.set(
        differencer, Optional.empty());
  }

  private static SkyKey createKey(RootedPath directory) {
    return LocalRepositoryLookupValue.key(directory);
  }

  private LocalRepositoryLookupValue lookupDirectory(RootedPath directory)
      throws InterruptedException {
    SkyKey key = createKey(directory);
    return lookupDirectory(key).get(key);
  }

  private EvaluationResult<LocalRepositoryLookupValue> lookupDirectory(SkyKey directoryKey)
      throws InterruptedException {
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setParallelism(SkyframeExecutor.DEFAULT_THREAD_COUNT)
            .setEventHandler(NullEventHandler.INSTANCE)
            .build();
    return evaluator.evaluate(ImmutableList.of(directoryKey), evaluationContext);
  }

  @Test
  public void testNoPath() throws Exception {
    LocalRepositoryLookupValue repositoryLookupValue =
        lookupDirectory(
            RootedPath.toRootedPath(Root.fromPath(rootDirectory), PathFragment.EMPTY_FRAGMENT));
    assertThat(repositoryLookupValue).isNotNull();
    assertThat(repositoryLookupValue.getRepository()).isEqualTo(RepositoryName.MAIN);
    assertThat(repositoryLookupValue.getPath()).isEqualTo(PathFragment.EMPTY_FRAGMENT);
  }

  @Test
  public void testActualPackage() throws Exception {
    scratch.file("some/path/BUILD");

    LocalRepositoryLookupValue repositoryLookupValue =
        lookupDirectory(
            RootedPath.toRootedPath(
                Root.fromPath(rootDirectory), PathFragment.create("some/path")));
    assertThat(repositoryLookupValue).isNotNull();
    assertThat(repositoryLookupValue.getRepository()).isEqualTo(RepositoryName.MAIN);
    assertThat(repositoryLookupValue.getPath()).isEqualTo(PathFragment.EMPTY_FRAGMENT);
  }
}
