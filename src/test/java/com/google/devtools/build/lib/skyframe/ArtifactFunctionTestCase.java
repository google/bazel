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

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.util.InjectedActionLookupKey;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.packages.WorkspaceFileValue;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingDependenciesProvider.DisabledDependenciesProvider;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestPackageFactoryBuilderFactory;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileStateKey;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequencedRecordingDifferencer;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;

abstract class ArtifactFunctionTestCase {
  static final ActionLookupKey ALL_OWNER = new InjectedActionLookupKey("all_owner");

  protected LinkedHashSet<ActionAnalysisMetadata> actions;
  protected boolean fastDigest = false;
  protected RecordingDifferencer differencer = new SequencedRecordingDifferencer();
  protected MemoizingEvaluator evaluator;
  protected Path root;
  protected final ActionKeyContext actionKeyContext = new ActionKeyContext();

  /**
   * The test action execution function. The Skyframe evaluator's action execution function
   * delegates to this one.
   */
  protected SkyFunction delegateActionExecutionFunction;

  @Before
  public void baseSetUp() throws Exception {
    CustomInMemoryFs fs = new CustomInMemoryFs();
    setupRoot(fs);
    AtomicReference<PathPackageLocator> pkgLocator =
        new AtomicReference<>(
            new PathPackageLocator(
                root.getFileSystem().getPath("/outputbase"),
                ImmutableList.of(Root.fromPath(root)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY));
    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(root, root, root),
            root,
            /* defaultSystemJavabase= */ null,
            TestConstants.PRODUCT_NAME);
    ExternalFilesHelper externalFilesHelper =
        ExternalFilesHelper.createForTesting(
            pkgLocator,
            ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
            directories);
    differencer = new SequencedRecordingDifferencer();
    evaluator =
        new InMemoryMemoizingEvaluator(
            ImmutableMap.<SkyFunctionName, SkyFunction>builder()
                .put(
                    FileStateKey.FILE_STATE,
                    new FileStateFunction(
                        Suppliers.ofInstance(
                            new TimestampGranularityMonitor(BlazeClock.instance())),
                        SyscallCache.NO_CACHE,
                        externalFilesHelper))
                .put(SkyFunctions.FILE, new FileFunction(pkgLocator, directories))
                .put(
                    Artifact.ARTIFACT,
                    new ArtifactFunction(
                        () -> true,
                        MetadataConsumerForMetrics.NO_OP,
                        SyscallCache.NO_CACHE,
                        () -> DisabledDependenciesProvider.INSTANCE))
                .put(SkyFunctions.ACTION_EXECUTION, new SimpleActionExecutionFunction())
                .put(SkyFunctions.PACKAGE, PackageFunction.newBuilder().build())
                .put(
                    SkyFunctions.PACKAGE_LOOKUP,
                    new PackageLookupFunction(
                        null,
                        CrossRepositoryLabelViolationStrategy.ERROR,
                        BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY,
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(
                    WorkspaceFileValue.WORKSPACE_FILE,
                    new WorkspaceFileFunction(
                        TestRuleClassProvider.getRuleClassProvider(),
                        TestPackageFactoryBuilderFactory.getInstance()
                            .builder(directories)
                            .build(TestRuleClassProvider.getRuleClassProvider(), fs),
                        directories,
                        /* bzlLoadFunctionForInlining= */ null))
                .put(
                    SkyFunctions.EXTERNAL_PACKAGE,
                    new ExternalPackageFunction(
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(
                    SkyFunctions.ACTION_TEMPLATE_EXPANSION,
                    new ActionTemplateExpansionFunction(actionKeyContext))
                .build(),
            differencer);
    PrecomputedValue.BUILD_ID.set(differencer, UUID.randomUUID());
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get());
    actions = new LinkedHashSet<>();
  }

  protected void setupRoot(CustomInMemoryFs fs) throws IOException {
    Path tmpDir = fs.getPath(TestUtils.tmpDir());
    root = tmpDir.getChild("root");
    root.createDirectoryAndParents();
    FileSystemUtils.createEmptyFile(root.getRelative("WORKSPACE"));
  }

  protected static void writeFile(Path path, String contents) throws IOException {
    path.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContentAsLatin1(path, contents);
  }

  /** ActionExecutionFunction that delegates to our delegate. */
  private class SimpleActionExecutionFunction implements SkyFunction {
    @Override
    public SkyValue compute(SkyKey skyKey, Environment env)
        throws SkyFunctionException, InterruptedException {
      return delegateActionExecutionFunction.compute(skyKey, env);
    }

    @Override
    public String extractTag(SkyKey skyKey) {
      return delegateActionExecutionFunction.extractTag(skyKey);
    }
  }

  /** InMemoryFileSystem that can pretend to do a fast digest. */
  protected class CustomInMemoryFs extends InMemoryFileSystem {
    CustomInMemoryFs() {
      super(DigestHashFunction.SHA256);
    }

    @Override
    @SuppressWarnings("UnsynchronizedOverridesSynchronized")
    protected byte[] getFastDigest(PathFragment path) throws IOException {
      return fastDigest ? getDigest(path) : null;
    }
  }
}
