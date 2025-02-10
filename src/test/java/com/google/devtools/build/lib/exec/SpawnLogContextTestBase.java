// Copyright 2023 The Bazel Authors. All Rights Reserved.
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
package com.google.devtools.build.lib.exec;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.exec.SpawnLogContext.millisToProto;
import static com.google.devtools.build.lib.testutil.TestConstants.PRODUCT_NAME;
import static com.google.devtools.build.lib.testutil.TestConstants.WORKSPACE_NAME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Comparator.comparing;

import com.google.common.base.Utf8;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifactType;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.CommandLines.ParamFileActionInput;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FilesetOutputTree;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.actions.RunfilesTree;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnMetrics;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesSupport;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.config.FragmentFactory;
import com.google.devtools.build.lib.analysis.config.FragmentRegistry;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.bazel.rules.python.BazelPyBuiltins;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.exec.Protos.Digest;
import com.google.devtools.build.lib.exec.Protos.EnvironmentVariable;
import com.google.devtools.build.lib.exec.Protos.File;
import com.google.devtools.build.lib.exec.Protos.Platform;
import com.google.devtools.build.lib.exec.Protos.SpawnExec;
import com.google.devtools.build.lib.exec.util.FakeActionInputFileCache;
import com.google.devtools.build.lib.exec.util.SpawnBuilder;
import com.google.devtools.build.lib.server.FailureDetails.Crash;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.vfs.DelegateFileSystem;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.protobuf.util.Timestamps;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Base class for {@link SpawnLogContext} tests. */
@RunWith(TestParameterInjector.class)
public abstract class SpawnLogContextTestBase {
  protected final DigestHashFunction digestHashFunction = DigestHashFunction.SHA256;
  protected final FileSystem fs = new InMemoryFileSystem(digestHashFunction);
  protected final Path outputBase = fs.getPath("/home/user/bazel/output_base");
  protected final Path externalRoot =
      outputBase.getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION);
  protected final RepositoryName externalRepo = RepositoryName.createUnvalidated("some_repo");

  protected ArtifactRoot outputDir;
  protected Path execRoot;
  protected ArtifactRoot rootDir;
  protected ArtifactRoot externalSourceRoot;
  protected ArtifactRoot externalOutputDir;
  protected BuildConfigurationValue configuration;

  @TestParameter public boolean siblingRepositoryLayout;

  @Before
  public void setup() throws InvalidConfigurationException, OptionsParsingException {
    BuildOptions defaultBuildOptions = BuildOptions.of(ImmutableList.of(CoreOptions.class));
    configuration =
        BuildConfigurationValue.createForTesting(
            defaultBuildOptions,
            "k8-fastbuild",
            WORKSPACE_NAME,
            siblingRepositoryLayout,
            new BlazeDirectories(
                new ServerDirectories(outputBase, outputBase, outputBase),
                /* workspace= */ null,
                /* defaultSystemJavabase= */ null,
                TestConstants.PRODUCT_NAME),
            new BuildConfigurationValue.GlobalStateProvider() {
              @Override
              public ActionEnvironment getActionEnvironment(BuildOptions buildOptions) {
                return ActionEnvironment.EMPTY;
              }

              @Override
              public FragmentRegistry getFragmentRegistry() {
                return FragmentRegistry.create(
                    ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
              }

              @Override
              public ImmutableSet<String> getReservedActionMnemonics() {
                return ImmutableSet.of();
              }
            },
            new FragmentFactory());
    outputDir = configuration.getBinDirectory(RepositoryName.MAIN);
    execRoot = configuration.getDirectories().getExecRoot(WORKSPACE_NAME);
    rootDir = ArtifactRoot.asSourceRoot(Root.fromPath(execRoot));

    externalSourceRoot =
        ArtifactRoot.asExternalSourceRoot(
            Root.fromPath(externalRoot.getChild(externalRepo.getName())));
    externalOutputDir = configuration.getBinDirectory(externalRepo);
  }

  // A fake action filesystem that provides a fast digest, but refuses to compute it from the
  // file contents (which won't be available when building without the bytes).
  protected static final class FakeActionFileSystem extends DelegateFileSystem {
    FakeActionFileSystem(FileSystem delegateFs) {
      super(delegateFs);
    }

    @Override
    protected byte[] getFastDigest(PathFragment path) throws IOException {
      return super.getDigest(path);
    }

    @Override
    protected byte[] getDigest(PathFragment path) throws IOException {
      throw new UnsupportedOperationException();
    }
  }

  /** Test parameter determining whether the spawn inputs are also tool inputs. */
  protected enum InputsMode {
    TOOLS,
    NON_TOOLS;

    boolean isTool() {
      return this == TOOLS;
    }
  }

  /** Test parameter determining whether to emulate building with or without the bytes. */
  protected enum OutputsMode {
    WITH_BYTES,
    WITHOUT_BYTES;

    FileSystem getActionFileSystem(FileSystem fs) {
      return this == WITHOUT_BYTES ? new FakeActionFileSystem(fs) : fs;
    }
  }

  /** Test parameter determining whether an input/output directory should be empty. */
  enum DirContents {
    EMPTY,
    NON_EMPTY;

    boolean isEmpty() {
      return this == EMPTY;
    }
  }

  /** Test parameter determining whether an output is indirected through a symlink. */
  enum OutputIndirection {
    DIRECT,
    INDIRECT;

    boolean viaSymlink() {
      return this == INDIRECT;
    }
  }

  @Test
  public void testFileInput(@TestParameter InputsMode inputsMode) throws Exception {
    Artifact fileInput = ActionsTestUtil.createArtifact(rootDir, "file");

    writeFile(fileInput, "abc");

    SpawnBuilder spawn = defaultSpawnBuilder().withInputs(fileInput);
    if (inputsMode.isTool()) {
      spawn.withTools(fileInput);
    }

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        createInputMetadataProvider(fileInput),
        createInputMap(fileInput),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addInputs(
                File.newBuilder()
                    .setPath("file")
                    .setDigest(getDigest("abc"))
                    .setIsTool(inputsMode.isTool()))
            .build());
  }

  @Test
  public void testFileInputWithDirectoryContents(
      @TestParameter InputsMode inputsMode, @TestParameter DirContents dirContents)
      throws Exception {
    Artifact fileInput = ActionsTestUtil.createArtifact(rootDir, "file");

    fileInput.getPath().createDirectoryAndParents();
    if (!dirContents.isEmpty()) {
      writeFile(fileInput.getPath().getChild("file"), "abc");
    }

    SpawnBuilder spawn = defaultSpawnBuilder().withInputs(fileInput);
    if (inputsMode.isTool()) {
      spawn.withTools(fileInput);
    }

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        createInputMetadataProvider(fileInput),
        createInputMap(fileInput),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addAllInputs(
                dirContents.isEmpty()
                    ? ImmutableList.of()
                    : ImmutableList.of(
                        File.newBuilder()
                            .setPath("file/file")
                            .setDigest(getDigest("abc"))
                            .setIsTool(inputsMode.isTool())
                            .build()))
            .build());
  }

  @Test
  public void testDirectoryInput(
      @TestParameter InputsMode inputsMode, @TestParameter DirContents dirContents)
      throws Exception {
    Artifact dirInput = ActionsTestUtil.createArtifact(rootDir, "dir");

    dirInput.getPath().createDirectoryAndParents();
    if (!dirContents.isEmpty()) {
      writeFile(dirInput.getPath().getChild("file"), "abc");
    }

    SpawnBuilder spawn = defaultSpawnBuilder().withInputs(dirInput);
    if (inputsMode.equals(InputsMode.TOOLS)) {
      spawn.withTools(dirInput);
    }

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        createInputMetadataProvider(dirInput),
        createInputMap(dirInput),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addAllInputs(
                dirContents.isEmpty()
                    ? ImmutableList.of()
                    : ImmutableList.of(
                        File.newBuilder()
                            .setPath("dir/file")
                            .setDigest(getDigest("abc"))
                            .setIsTool(inputsMode.isTool())
                            .build()))
            .build());
  }

  @Test
  public void testTreeInput(
      @TestParameter InputsMode inputsMode, @TestParameter DirContents dirContents)
      throws Exception {
    SpecialArtifact treeInput =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputDir, "tree");

    treeInput.getPath().createDirectoryAndParents();
    if (!dirContents.isEmpty()) {
      writeFile(treeInput.getPath().getChild("child"), "abc");
    }

    SpawnBuilder spawn = defaultSpawnBuilder().withInputs(treeInput);
    if (inputsMode.isTool()) {
      spawn.withTools(treeInput);
    }

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        createInputMetadataProvider(treeInput),
        createInputMap(treeInput),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addAllInputs(
                dirContents.isEmpty()
                    ? ImmutableList.of()
                    : ImmutableList.of(
                        File.newBuilder()
                            .setPath(PRODUCT_NAME + "-out/k8-fastbuild/bin/tree/child")
                            .setDigest(getDigest("abc"))
                            .setIsTool(inputsMode.isTool())
                            .build()))
            .build());
  }

  @Test
  public void testUnresolvedSymlinkInput(@TestParameter InputsMode inputsMode) throws Exception {
    Artifact symlinkInput = ActionsTestUtil.createUnresolvedSymlinkArtifact(outputDir, "symlink");

    symlinkInput.getPath().getParentDirectory().createDirectoryAndParents();
    symlinkInput.getPath().createSymbolicLink(PathFragment.create("/some/path"));

    SpawnBuilder spawn = defaultSpawnBuilder().withInputs(symlinkInput);
    if (inputsMode.isTool()) {
      spawn.withTools(symlinkInput);
    }

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        createInputMetadataProvider(symlinkInput),
        createInputMap(symlinkInput),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addInputs(
                File.newBuilder()
                    .setPath(PRODUCT_NAME + "-out/k8-fastbuild/bin/symlink")
                    .setSymlinkTargetPath("/some/path")
                    .setIsTool(inputsMode.isTool()))
            .build());
  }

  @Test
  public void testRunfilesFileInput(@TestParameter InputsMode inputsMode) throws Exception {
    Artifact runfilesInput = ActionsTestUtil.createArtifact(rootDir, "data.txt");
    Artifact runfilesArtifact = ActionsTestUtil.createRunfilesArtifact(outputDir, "foo.runfiles");

    writeFile(runfilesInput, "abc");

    PathFragment runfilesRoot = outputDir.getExecPath().getRelative("foo.runfiles");
    RunfilesTree runfilesTree = createRunfilesTree(runfilesRoot, runfilesInput);

    SpawnBuilder spawnBuilder = defaultSpawnBuilder().withInput(runfilesArtifact);
    if (inputsMode.isTool()) {
      spawnBuilder.withTool(runfilesArtifact);
    }

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawnBuilder.build(),
        createInputMetadataProvider(runfilesTree, runfilesArtifact, runfilesInput),
        createInputMap(runfilesTree),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/foo.runfiles/"
                            + WORKSPACE_NAME
                            + "/data.txt")
                    .setDigest(getDigest("abc"))
                    .setIsTool(inputsMode.isTool()))
            .build());
  }

  @Test
  public void testToolInRunfilesTree() throws Exception {
    Artifact runfilesTreeArtifact = ActionsTestUtil.createRunfilesArtifact(outputDir, "runfiles");
    Artifact runfilesInput = ActionsTestUtil.createArtifact(rootDir, "data.txt");
    writeFile(runfilesInput, "abc");
    Artifact toolFile1 = ActionsTestUtil.createArtifact(rootDir, "tool1");
    writeFile(toolFile1, "def");
    Artifact toolFile2 = ActionsTestUtil.createArtifact(rootDir, "tool2");
    writeFile(toolFile2, "ghi");

    PathFragment runfilesRoot = outputDir.getExecPath().getRelative("foo.runfiles");
    RunfilesTree runfilesTree = createRunfilesTree(runfilesRoot, runfilesInput);

    NestedSet<ActionInput> tools =
        NestedSetBuilder.<ActionInput>stableOrder()
            .add(toolFile1)
            .addTransitive(
                NestedSetBuilder.<ActionInput>stableOrder()
                    .add(runfilesTreeArtifact)
                    .add(toolFile2)
                    .build())
            .build();
    Spawn spawn = defaultSpawnBuilder().withInputs(tools).withTools(tools).build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(
            runfilesTree, runfilesTreeArtifact, runfilesInput, toolFile1, toolFile2),
        createInputMap(runfilesTree, toolFile1, toolFile2),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/foo.runfiles/"
                            + WORKSPACE_NAME
                            + "/data.txt")
                    .setDigest(getDigest("abc"))
                    .setIsTool(true))
            .addInputs(
                File.newBuilder().setPath("tool1").setDigest(getDigest("def")).setIsTool(true))
            .addInputs(
                File.newBuilder().setPath("tool2").setDigest(getDigest("ghi")).setIsTool(true))
            .build());
  }

  @Test
  public void testRunfilesDirectoryInput(
      @TestParameter DirContents dirContents, @TestParameter InputsMode inputsMode)
      throws Exception {
    Artifact runfilesArtifact = ActionsTestUtil.createRunfilesArtifact(outputDir, "runfiles");
    Artifact runfilesInput = ActionsTestUtil.createArtifact(rootDir, "dir");

    runfilesInput.getPath().createDirectoryAndParents();
    if (!dirContents.isEmpty()) {
      writeFile(runfilesInput.getPath().getChild("data.txt"), "abc");
    }

    PathFragment runfilesRoot = outputDir.getExecPath().getRelative("foo.runfiles");
    RunfilesTree runfilesTree = createRunfilesTree(runfilesRoot, runfilesInput);

    SpawnBuilder spawnBuilder = defaultSpawnBuilder().withInput(runfilesArtifact);
    if (inputsMode.isTool()) {
      spawnBuilder.withTool(runfilesArtifact);
    }

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawnBuilder.build(),
        createInputMetadataProvider(runfilesTree, runfilesArtifact, runfilesInput),
        createInputMap(runfilesTree),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addAllInputs(
                dirContents.isEmpty()
                    ? ImmutableList.of()
                    : ImmutableList.of(
                        File.newBuilder()
                            .setPath(
                                PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/foo.runfiles/"
                                    + WORKSPACE_NAME
                                    + "/dir/data.txt")
                            .setDigest(getDigest("abc"))
                            .setIsTool(inputsMode.isTool())
                            .build()))
            .build());
  }

  @Test
  public void testRunfilesEmptyInput(@TestParameter InputsMode inputsMode) throws Exception {
    Artifact runfilesArtifact = ActionsTestUtil.createRunfilesArtifact(outputDir, "runfiles");

    Artifact runfilesInput = ActionsTestUtil.createArtifact(rootDir, "sub/dir/script.py");
    writeFile(runfilesInput, "abc");
    PackageIdentifier someRepoPkg =
        PackageIdentifier.create(externalRepo, PathFragment.create("pkg"));
    Artifact externalSourceArtifact =
        ActionsTestUtil.createArtifact(
            externalSourceRoot,
            someRepoPkg.getExecPath(siblingRepositoryLayout).getChild("lib.py").getPathString());
    writeFile(externalSourceArtifact, "external_source");
    PackageIdentifier someRepoOtherPkg =
        PackageIdentifier.create(externalRepo, PathFragment.create("other/pkg"));
    Artifact externalGenArtifact =
        ActionsTestUtil.createArtifact(
            externalOutputDir,
            someRepoOtherPkg
                .getPackagePath(siblingRepositoryLayout)
                .getChild("gen.py")
                .getPathString());
    writeFile(externalGenArtifact, "external_gen");

    PathFragment runfilesRoot = outputDir.getExecPath().getRelative("foo.runfiles");
    RunfilesTree runfilesTree =
        createRunfilesTree(
            runfilesRoot, runfilesInput, externalGenArtifact, externalSourceArtifact);

    SpawnBuilder spawnBuilder = defaultSpawnBuilder().withInput(runfilesArtifact);
    if (inputsMode.isTool()) {
      spawnBuilder.withTool(runfilesArtifact);
    }

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawnBuilder.build(),
        createInputMetadataProvider(
            runfilesTree,
            runfilesArtifact,
            runfilesInput,
            externalGenArtifact,
            externalSourceArtifact),
        createInputMap(runfilesTree),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addInputs(
                File.newBuilder()
                    .setPath(PRODUCT_NAME + "-out/k8-fastbuild/bin/foo.runfiles/__init__.py")
                    .setIsTool(inputsMode.isTool()))
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/foo.runfiles/"
                            + WORKSPACE_NAME
                            + "/sub/__init__.py")
                    .setIsTool(inputsMode.isTool()))
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/foo.runfiles/"
                            + WORKSPACE_NAME
                            + "/sub/dir/__init__.py")
                    .setIsTool(inputsMode.isTool()))
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/foo.runfiles/"
                            + WORKSPACE_NAME
                            + "/sub/dir/script.py")
                    .setDigest(getDigest("abc"))
                    .setIsTool(inputsMode.isTool()))
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME + "-out/k8-fastbuild/bin/foo.runfiles/some_repo/__init__.py")
                    .setIsTool(inputsMode.isTool()))
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/foo.runfiles/some_repo/other/__init__.py")
                    .setIsTool(inputsMode.isTool()))
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/foo.runfiles/some_repo/other/pkg/__init__.py")
                    .setIsTool(inputsMode.isTool()))
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/foo.runfiles/some_repo/other/pkg/gen.py")
                    .setDigest(getDigest("external_gen"))
                    .setIsTool(inputsMode.isTool()))
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/foo.runfiles/some_repo/pkg/__init__.py")
                    .setIsTool(inputsMode.isTool()))
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME + "-out/k8-fastbuild/bin/foo.runfiles/some_repo/pkg/lib.py")
                    .setDigest(getDigest("external_source"))
                    .setIsTool(inputsMode.isTool()))
            .build());
  }

  @Test
  public void testRunfilesMixedRoots(@TestParameter boolean legacyExternalRunfiles)
      throws Exception {
    Artifact sourceArtifact = ActionsTestUtil.createArtifact(rootDir, "pkg/source.txt");
    writeFile(sourceArtifact, "source");
    Artifact genArtifact = ActionsTestUtil.createArtifact(outputDir, "other/pkg/gen.txt");
    writeFile(genArtifact, "gen");
    PackageIdentifier someRepoPkg =
        PackageIdentifier.create(externalRepo, PathFragment.create("pkg"));
    Artifact externalSourceArtifact =
        ActionsTestUtil.createArtifact(
            externalSourceRoot,
            someRepoPkg
                .getExecPath(siblingRepositoryLayout)
                .getChild("source.txt")
                .getPathString());
    writeFile(externalSourceArtifact, "external_source");
    PackageIdentifier someRepoOtherPkg =
        PackageIdentifier.create(externalRepo, PathFragment.create("other/pkg"));
    Artifact externalGenArtifact =
        ActionsTestUtil.createArtifact(
            externalOutputDir,
            someRepoOtherPkg
                .getPackagePath(siblingRepositoryLayout)
                .getChild("gen.txt")
                .getPathString());
    writeFile(externalGenArtifact, "external_gen");

    Artifact symlinkSourceTarget = ActionsTestUtil.createArtifact(rootDir, "pkg/target.txt");
    writeFile(symlinkSourceTarget, "symlink_source");
    Artifact symlinkGenTarget = ActionsTestUtil.createArtifact(outputDir, "pkg/target.txt");
    writeFile(symlinkGenTarget, "symlink_gen");

    Artifact rootSymlinkSourceTarget =
        ActionsTestUtil.createArtifact(rootDir, "pkg/root_target.txt");
    writeFile(rootSymlinkSourceTarget, "root_symlink_source");
    Artifact rootSymlinkGenTarget =
        ActionsTestUtil.createArtifact(outputDir, "pkg/root_target.txt");
    writeFile(rootSymlinkGenTarget, "root_symlink_gen");

    Artifact runfilesArtifact =
        ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles");
    PathFragment runfilesRoot = outputDir.getExecPath().getRelative("tools/foo.runfiles");
    RunfilesTree runfilesTree =
        createRunfilesTree(
            runfilesRoot,
            ImmutableMap.of(
                "some/symlink", symlinkSourceTarget,
                "other/symlink", symlinkGenTarget),
            ImmutableMap.of(
                "root/symlink", rootSymlinkSourceTarget,
                "root/other/symlink", rootSymlinkGenTarget),
            legacyExternalRunfiles,
            sourceArtifact,
            genArtifact,
            externalSourceArtifact,
            externalGenArtifact);

    Spawn spawn = defaultSpawnBuilder().withInput(runfilesArtifact).build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(
            runfilesTree,
            runfilesArtifact,
            sourceArtifact,
            genArtifact,
            externalSourceArtifact,
            externalGenArtifact,
            symlinkSourceTarget,
            symlinkGenTarget,
            rootSymlinkSourceTarget,
            rootSymlinkGenTarget),
        createInputMap(runfilesTree),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    var builder = defaultSpawnExecBuilder();
    if (legacyExternalRunfiles) {
      builder
          .addInputs(
              File.newBuilder()
                  .setPath(
                      PRODUCT_NAME
                          + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                          + WORKSPACE_NAME
                          + "/external/some_repo/other/pkg/gen.txt")
                  .setDigest(getDigest("external_gen")))
          .addInputs(
              File.newBuilder()
                  .setPath(
                      PRODUCT_NAME
                          + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                          + WORKSPACE_NAME
                          + "/external/some_repo/pkg/source.txt")
                  .setDigest(getDigest("external_source")));
    }
    builder
        .addInputs(
            File.newBuilder()
                .setPath(
                    PRODUCT_NAME
                        + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                        + WORKSPACE_NAME
                        + "/other/pkg/gen.txt")
                .setDigest(getDigest("gen")))
        .addInputs(
            File.newBuilder()
                .setPath(
                    PRODUCT_NAME
                        + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                        + WORKSPACE_NAME
                        + "/other/symlink")
                .setDigest(getDigest("symlink_gen")))
        .addInputs(
            File.newBuilder()
                .setPath(
                    PRODUCT_NAME
                        + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                        + WORKSPACE_NAME
                        + "/pkg/source.txt")
                .setDigest(getDigest("source")))
        .addInputs(
            File.newBuilder()
                .setPath(
                    PRODUCT_NAME
                        + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                        + WORKSPACE_NAME
                        + "/some/symlink")
                .setDigest(getDigest("symlink_source")))
        .addInputs(
            File.newBuilder()
                .setPath(
                    PRODUCT_NAME + "-out/k8-fastbuild/bin/tools/foo.runfiles/root/other/symlink")
                .setDigest(getDigest("root_symlink_gen")))
        .addInputs(
            File.newBuilder()
                .setPath(PRODUCT_NAME + "-out/k8-fastbuild/bin/tools/foo.runfiles/root/symlink")
                .setDigest(getDigest("root_symlink_source")))
        .addInputs(
            File.newBuilder()
                .setPath(
                    PRODUCT_NAME
                        + "-out/k8-fastbuild/bin/tools/foo.runfiles/some_repo/other/pkg/gen.txt")
                .setDigest(getDigest("external_gen")))
        .addInputs(
            File.newBuilder()
                .setPath(
                    PRODUCT_NAME
                        + "-out/k8-fastbuild/bin/tools/foo.runfiles/some_repo/pkg/source.txt")
                .setDigest(getDigest("external_source")));
    closeAndAssertLog(context, builder.build());
  }

  @Test
  public void testRunfilesExternalOnly(
      @TestParameter boolean legacyExternalRunfiles,
      @TestParameter boolean symlinkUnderMain,
      @TestParameter boolean rootSymlinkUnderMain)
      throws Exception {
    PackageIdentifier someRepoPkg =
        PackageIdentifier.create(externalRepo, PathFragment.create("pkg"));
    Artifact externalSourceArtifact =
        ActionsTestUtil.createArtifact(
            externalSourceRoot,
            someRepoPkg
                .getExecPath(siblingRepositoryLayout)
                .getChild("source.txt")
                .getPathString());
    writeFile(externalSourceArtifact, "external_source");
    PackageIdentifier someRepoOtherPkg =
        PackageIdentifier.create(externalRepo, PathFragment.create("other/pkg"));
    Artifact externalGenArtifact =
        ActionsTestUtil.createArtifact(
            externalOutputDir,
            someRepoOtherPkg
                .getPackagePath(siblingRepositoryLayout)
                .getChild("gen.txt")
                .getPathString());
    writeFile(externalGenArtifact, "external_gen");

    Artifact symlinkTarget = ActionsTestUtil.createArtifact(outputDir, "pkg/root_target.txt");
    writeFile(symlinkTarget, "symlink_target");
    Artifact rootSymlinkTarget = ActionsTestUtil.createArtifact(rootDir, "pkg/root_target.txt");
    writeFile(rootSymlinkTarget, "root_symlink_target");

    Artifact runfilesArtifact =
        ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles");

    PathFragment runfilesRoot = outputDir.getExecPath().getRelative("tools/foo.runfiles");
    RunfilesTree runfilesTree =
        createRunfilesTree(
            runfilesRoot,
            ImmutableMap.of((symlinkUnderMain ? "" : "../some_repo/") + "symlink", symlinkTarget),
            ImmutableMap.of(
                (rootSymlinkUnderMain ? WORKSPACE_NAME + "/" : "some_repo/") + "root_symlink",
                rootSymlinkTarget),
            legacyExternalRunfiles,
            externalSourceArtifact,
            externalGenArtifact);

    Spawn spawn = defaultSpawnBuilder().withInput(runfilesArtifact).build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(
            runfilesTree,
            runfilesArtifact,
            externalSourceArtifact,
            externalGenArtifact,
            symlinkTarget,
            rootSymlinkTarget),
        createInputMap(runfilesTree),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    ArrayList<File> files = new ArrayList<>();
    files.add(
        File.newBuilder()
            .setPath(
                PRODUCT_NAME
                    + "-out/k8-fastbuild/bin/tools/foo.runfiles/%s/root_symlink"
                        .formatted(rootSymlinkUnderMain ? WORKSPACE_NAME : "some_repo"))
            .setDigest(getDigest("root_symlink_target"))
            .build());
    files.add(
        File.newBuilder()
            .setPath(
                PRODUCT_NAME
                    + "-out/k8-fastbuild/bin/tools/foo.runfiles/%s/symlink"
                        .formatted(symlinkUnderMain ? WORKSPACE_NAME : "some_repo"))
            .setDigest(getDigest("symlink_target"))
            .build());
    files.add(
        File.newBuilder()
            .setPath(
                PRODUCT_NAME
                    + "-out/k8-fastbuild/bin/tools/foo.runfiles/some_repo/other/pkg/gen.txt")
            .setDigest(getDigest("external_gen"))
            .build());
    files.add(
        File.newBuilder()
            .setPath(
                PRODUCT_NAME + "-out/k8-fastbuild/bin/tools/foo.runfiles/some_repo/pkg/source.txt")
            .setDigest(getDigest("external_source"))
            .build());
    if (legacyExternalRunfiles) {
      files.add(
          File.newBuilder()
              .setPath(
                  PRODUCT_NAME
                      + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                      + WORKSPACE_NAME
                      + "/external/some_repo/other/pkg/gen.txt")
              .setDigest(getDigest("external_gen"))
              .build());
      files.add(
          File.newBuilder()
              .setPath(
                  PRODUCT_NAME
                      + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                      + WORKSPACE_NAME
                      + "/external/some_repo/pkg/source.txt")
              .setDigest(getDigest("external_source"))
              .build());
      if (!symlinkUnderMain) {
        files.add(
            File.newBuilder()
                .setPath(
                    PRODUCT_NAME
                        + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                        + ""
                        + WORKSPACE_NAME
                        + "/external/some_repo/symlink")
                .setDigest(getDigest("symlink_target"))
                .build());
      }
    } else if (!symlinkUnderMain && !rootSymlinkUnderMain) {
      files.add(
          File.newBuilder()
              .setPath(
                  PRODUCT_NAME
                      + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                      + WORKSPACE_NAME
                      + "/.runfile")
              .build());
    }
    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addAllInputs(files.stream().sorted(comparing(File::getPath)).toList())
            .build());
  }

  @Test
  public void testRunfilesFilesCollide(@TestParameter boolean legacyExternalRunfiles)
      throws Exception {
    Artifact sourceArtifact = ActionsTestUtil.createArtifact(rootDir, "pkg/file.txt");
    writeFile(sourceArtifact, "source");
    Artifact genArtifact = ActionsTestUtil.createArtifact(outputDir, "pkg/file.txt");
    writeFile(genArtifact, "gen");
    PackageIdentifier someRepoPkg =
        PackageIdentifier.create(externalRepo, PathFragment.create("pkg"));
    Artifact externalSourceArtifact =
        ActionsTestUtil.createArtifact(
            externalSourceRoot,
            someRepoPkg.getExecPath(siblingRepositoryLayout).getChild("file.txt").getPathString());
    writeFile(externalSourceArtifact, "external_source");
    Artifact externalGenArtifact =
        ActionsTestUtil.createArtifact(
            externalOutputDir,
            someRepoPkg
                .getPackagePath(siblingRepositoryLayout)
                .getChild("file.txt")
                .getPathString());
    writeFile(externalGenArtifact, "external_gen");

    Artifact runfilesArtifact =
        ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles");

    PathFragment runfilesRoot = outputDir.getExecPath().getRelative("tools/foo.runfiles");
    RunfilesTree runfilesTree =
        createRunfilesTree(
            runfilesRoot,
            ImmutableMap.of(),
            ImmutableMap.of(),
            legacyExternalRunfiles,
            sourceArtifact,
            genArtifact,
            externalSourceArtifact,
            externalGenArtifact);

    Spawn spawn = defaultSpawnBuilder().withInput(runfilesArtifact).build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(
            runfilesTree,
            runfilesArtifact,
            sourceArtifact,
            genArtifact,
            externalSourceArtifact,
            externalGenArtifact),
        createInputMap(runfilesTree),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    var builder = defaultSpawnExecBuilder();
    if (legacyExternalRunfiles) {
      builder.addInputs(
          File.newBuilder()
              .setPath(
                  PRODUCT_NAME
                      + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                      + WORKSPACE_NAME
                      + "/external/some_repo/pkg/file.txt")
              .setDigest(getDigest("external_gen")));
    }
    builder
        .addInputs(
            File.newBuilder()
                .setPath(
                    PRODUCT_NAME
                        + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                        + WORKSPACE_NAME
                        + "/pkg/file.txt")
                .setDigest(getDigest("gen")))
        .addInputs(
            File.newBuilder()
                .setPath(
                    PRODUCT_NAME
                        + "-out/k8-fastbuild/bin/tools/foo.runfiles/some_repo/pkg/file.txt")
                .setDigest(getDigest("external_gen")));
    closeAndAssertLog(context, builder.build());
  }

  @Test
  public void testRunfilesFilesAndSymlinksCollide(@TestParameter boolean legacyExternalRunfiles)
      throws Exception {
    Artifact sourceArtifact = ActionsTestUtil.createArtifact(rootDir, "pkg/source.txt");
    writeFile(sourceArtifact, "source");
    Artifact genArtifact = ActionsTestUtil.createArtifact(outputDir, "other/pkg/gen.txt");
    writeFile(genArtifact, "gen");
    PackageIdentifier someRepoPkg =
        PackageIdentifier.create(externalRepo, PathFragment.create("pkg"));
    Artifact externalSourceArtifact =
        ActionsTestUtil.createArtifact(
            externalSourceRoot,
            someRepoPkg
                .getExecPath(siblingRepositoryLayout)
                .getChild("source.txt")
                .getPathString());
    writeFile(externalSourceArtifact, "external_source");
    PackageIdentifier someRepoOtherPkg =
        PackageIdentifier.create(externalRepo, PathFragment.create("other/pkg"));
    Artifact externalGenArtifact =
        ActionsTestUtil.createArtifact(
            externalOutputDir,
            someRepoOtherPkg
                .getPackagePath(siblingRepositoryLayout)
                .getChild("gen.txt")
                .getPathString());
    writeFile(externalGenArtifact, "external_gen");

    Artifact symlinkSourceArtifact = ActionsTestUtil.createArtifact(rootDir, "pkg/not_source.txt");
    writeFile(symlinkSourceArtifact, "symlink_source");
    Artifact symlinkGenArtifact =
        ActionsTestUtil.createArtifact(outputDir, "other/pkg/not_gen.txt");
    writeFile(symlinkGenArtifact, "symlink_gen");
    Artifact symlinkExternalSourceArtifact =
        ActionsTestUtil.createArtifact(externalSourceRoot, "external/some_repo/pkg/not_source.txt");
    writeFile(symlinkExternalSourceArtifact, "symlink_external_source");
    Artifact symlinkExternalGenArtifact =
        ActionsTestUtil.createArtifact(outputDir, "external/some_repo/other/pkg/not_gen.txt");
    writeFile(symlinkExternalGenArtifact, "symlink_external_gen");

    Artifact runfilesArtifact =
        ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles");

    PathFragment runfilesRoot = outputDir.getExecPath().getRelative("tools/foo.runfiles");
    RunfilesTree runfilesTree =
        createRunfilesTree(
            runfilesRoot,
            ImmutableMap.of(
                // Symlinks are always relative to the workspace runfiles directory.
                "pkg/source.txt", symlinkSourceArtifact,
                "other/pkg/gen.txt", symlinkGenArtifact,
                "../some_repo/pkg/source.txt", symlinkExternalSourceArtifact,
                "../some_repo/other/pkg/gen.txt", symlinkExternalGenArtifact),
            ImmutableMap.of(),
            legacyExternalRunfiles,
            sourceArtifact,
            genArtifact,
            externalSourceArtifact,
            externalGenArtifact);

    Spawn spawn = defaultSpawnBuilder().withInput(runfilesArtifact).build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(
            runfilesTree,
            runfilesArtifact,
            sourceArtifact,
            genArtifact,
            externalSourceArtifact,
            externalGenArtifact,
            symlinkSourceArtifact,
            symlinkGenArtifact,
            symlinkExternalSourceArtifact,
            symlinkExternalGenArtifact),
        createInputMap(runfilesTree),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    var builder = defaultSpawnExecBuilder();
    if (legacyExternalRunfiles) {
      builder
          .addInputs(
              File.newBuilder()
                  .setPath(
                      PRODUCT_NAME
                          + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                          + WORKSPACE_NAME
                          + "/external/some_repo/other/pkg/gen.txt")
                  .setDigest(getDigest("external_gen")))
          .addInputs(
              File.newBuilder()
                  .setPath(
                      PRODUCT_NAME
                          + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                          + WORKSPACE_NAME
                          + "/external/some_repo/pkg/source.txt")
                  .setDigest(getDigest("external_source")));
    }
    builder
        .addInputs(
            File.newBuilder()
                .setPath(
                    PRODUCT_NAME
                        + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                        + WORKSPACE_NAME
                        + "/other/pkg/gen.txt")
                .setDigest(getDigest("gen")))
        .addInputs(
            File.newBuilder()
                .setPath(
                    PRODUCT_NAME
                        + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                        + WORKSPACE_NAME
                        + "/pkg/source.txt")
                .setDigest(getDigest("source")))
        .addInputs(
            File.newBuilder()
                .setPath(
                    PRODUCT_NAME
                        + "-out/k8-fastbuild/bin/tools/foo.runfiles/some_repo/other/pkg/gen.txt")
                .setDigest(getDigest("external_gen")))
        .addInputs(
            File.newBuilder()
                .setPath(
                    PRODUCT_NAME
                        + "-out/k8-fastbuild/bin/tools/foo.runfiles/some_repo/pkg/source.txt")
                .setDigest(getDigest("external_source")));
    closeAndAssertLog(context, builder.build());
  }

  @Test
  public void testRunfilesFileAndRootSymlinkCollide() throws Exception {
    Artifact sourceArtifact = ActionsTestUtil.createArtifact(rootDir, "pkg/source.txt");
    writeFile(sourceArtifact, "source");

    Artifact symlinkSourceArtifact = ActionsTestUtil.createArtifact(rootDir, "pkg/not_source.txt");
    writeFile(symlinkSourceArtifact, "symlink_source");

    Artifact runfilesArtifact =
        ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles");

    PathFragment runfilesRoot = outputDir.getExecPath().getRelative("tools/foo.runfiles");
    RunfilesTree runfilesTree =
        createRunfilesTree(
            runfilesRoot,
            ImmutableMap.of(),
            ImmutableMap.of(WORKSPACE_NAME + "/pkg/source.txt", symlinkSourceArtifact),
            /* legacyExternalRunfiles= */ false,
            sourceArtifact);

    Spawn spawn = defaultSpawnBuilder().withInput(runfilesArtifact).build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(
            runfilesTree, runfilesArtifact, sourceArtifact, symlinkSourceArtifact),
        createInputMap(runfilesTree),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                            + WORKSPACE_NAME
                            + "/pkg/source.txt")
                    .setDigest(getDigest("symlink_source")))
            .build());
  }

  @Test
  public void testRunfilesCrossTypeCollision(@TestParameter boolean symlinkFirst) throws Exception {
    Artifact file = ActionsTestUtil.createArtifact(rootDir, "pkg/file.txt");
    writeFile(file, "file");
    Artifact symlink = ActionsTestUtil.createUnresolvedSymlinkArtifact(outputDir, "pkg/file.txt");
    symlink.getPath().getParentDirectory().createDirectoryAndParents();
    symlink.getPath().createSymbolicLink(PathFragment.create("/some/path/other_file.txt"));

    Artifact runfilesArtifact =
        ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles");

    PathFragment runfilesRoot = outputDir.getExecPath().getRelative("tools/foo.runfiles");
    var artifacts =
        symlinkFirst ? ImmutableList.of(symlink, file) : ImmutableList.of(file, symlink);
    RunfilesTree runfilesTree =
        createRunfilesTree(
            runfilesRoot,
            ImmutableMap.of(),
            ImmutableMap.of(),
            /* legacyExternalRunfiles= */ false,
            NestedSetBuilder.wrap(Order.STABLE_ORDER, artifacts));

    Spawn spawn = defaultSpawnBuilder().withInput(runfilesArtifact).build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(runfilesTree, runfilesArtifact, file, symlink),
        createInputMap(runfilesTree),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addInputs(
                symlinkFirst
                    ? File.newBuilder()
                        .setPath(
                            PRODUCT_NAME
                                + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                + WORKSPACE_NAME
                                + "/pkg/file.txt")
                        .setDigest(getDigest("file"))
                    : File.newBuilder()
                        .setPath(
                            PRODUCT_NAME
                                + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                + WORKSPACE_NAME
                                + "/pkg/file.txt")
                        .setSymlinkTargetPath("/some/path/other_file.txt"))
            .build());
  }

  @Test
  public void testRunfilesPostOrderCollision(@TestParameter boolean nestBoth) throws Exception {
    Artifact sourceFile = ActionsTestUtil.createArtifact(rootDir, "pkg/file.txt");
    writeFile(sourceFile, "source");
    Artifact genFile = ActionsTestUtil.createArtifact(outputDir, "pkg/file.txt");
    writeFile(genFile, "gen");
    Artifact otherSourceFile = ActionsTestUtil.createArtifact(rootDir, "pkg/other_file.txt");
    writeFile(otherSourceFile, "other_source");
    Artifact otherGenFile = ActionsTestUtil.createArtifact(outputDir, "pkg/other_file.txt");
    writeFile(otherGenFile, "other_gen");

    Artifact runfilesArtifact =
        ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles");

    PathFragment runfilesRoot = outputDir.getExecPath().getRelative("tools/foo.runfiles");
    var artifactsBuilder =
        NestedSetBuilder.<Artifact>compileOrder()
            .addTransitive(
                NestedSetBuilder.wrap(
                    Order.COMPILE_ORDER, ImmutableList.of(sourceFile, otherGenFile)));
    var remainingArtifacts = ImmutableList.of(genFile, otherSourceFile);
    if (nestBoth) {
      artifactsBuilder.addTransitive(
          NestedSetBuilder.wrap(Order.COMPILE_ORDER, remainingArtifacts));
    } else {
      artifactsBuilder.addAll(remainingArtifacts);
    }
    var artifacts = artifactsBuilder.build();
    assertThat(artifacts.toList())
        .containsExactly(sourceFile, otherGenFile, genFile, otherSourceFile)
        .inOrder();
    if (nestBoth) {
      assertThat(artifacts.getNonLeaves()).hasSize(2);
    }

    RunfilesTree runfilesTree =
        createRunfilesTree(
            runfilesRoot,
            ImmutableMap.of(),
            ImmutableMap.of(),
            /* legacyExternalRunfiles= */ false,
            artifacts);

    Spawn spawn = defaultSpawnBuilder().withInput(runfilesArtifact).build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(
            runfilesTree, runfilesArtifact, sourceFile, genFile, otherSourceFile, otherGenFile),
        createInputMap(runfilesTree),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                            + WORKSPACE_NAME
                            + "/pkg/file.txt")
                    .setDigest(getDigest("gen")))
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                            + WORKSPACE_NAME
                            + "/pkg/other_file.txt")
                    .setDigest(getDigest("other_source")))
            .build());
  }

  @Test
  public void testRunfilesArtifactPostOrderCollisionWithDuplicate() throws Exception {
    Artifact sourceFile = ActionsTestUtil.createArtifact(rootDir, "pkg/file.txt");
    writeFile(sourceFile, "source");
    Artifact genFile = ActionsTestUtil.createArtifact(outputDir, "pkg/file.txt");
    writeFile(genFile, "gen");

    Artifact runfilesTreeArtifact =
        ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles");

    PathFragment runfilesRoot = outputDir.getExecPath().getRelative("tools/foo.runfiles");
    var artifacts =
        NestedSetBuilder.<Artifact>compileOrder()
            .add(sourceFile)
            .addTransitive(
                NestedSetBuilder.wrap(Order.COMPILE_ORDER, ImmutableList.of(sourceFile, genFile)))
            .build();
    assertThat(artifacts.toList()).containsExactly(sourceFile, genFile).inOrder();
    assertThat(artifacts.getLeaves()).hasSize(1);
    assertThat(artifacts.getNonLeaves()).hasSize(1);

    RunfilesTree runfilesTree =
        createRunfilesTree(
            runfilesRoot,
            ImmutableMap.of(),
            ImmutableMap.of(),
            /* legacyExternalRunfiles= */ false,
            artifacts);

    Spawn spawn = defaultSpawnBuilder().withInput(runfilesTreeArtifact).build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(runfilesTree, runfilesTreeArtifact, sourceFile, genFile),
        createInputMap(runfilesTree),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                            + WORKSPACE_NAME
                            + "/pkg/file.txt")
                    .setDigest(getDigest("gen")))
            .build());
  }

  @Test
  public void testRunfilesSymlinkPostOrderCollisionWithSemanticDuplicate(
      @TestParameter boolean rootSymlink) throws Exception {
    Artifact sourceFile = ActionsTestUtil.createArtifact(rootDir, "pkg/file.txt");
    writeFile(sourceFile, "source");
    Artifact genFile = ActionsTestUtil.createArtifact(outputDir, "pkg/file.txt");
    writeFile(genFile, "gen");

    Artifact runfilesTreeArtifact =
        ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles");

    PathFragment runfilesRoot = outputDir.getExecPath().getRelative("tools/foo.runfiles");
    Runfiles.Builder runfiles =
        new Runfiles.Builder(WORKSPACE_NAME, /* legacyExternalRunfiles= */ false);
    if (rootSymlink) {
      Runfiles transitiveRunfiles =
          new Runfiles.Builder(WORKSPACE_NAME, /* legacyExternalRunfiles= */ false)
              .addRootSymlink(PathFragment.create(WORKSPACE_NAME + "/pkg/file.txt"), sourceFile)
              .addRootSymlink(PathFragment.create(WORKSPACE_NAME + "/pkg/file.txt"), genFile)
              .build();
      runfiles.addRootSymlink(PathFragment.create(WORKSPACE_NAME + "/pkg/file.txt"), sourceFile);
      runfiles.addRootSymlinks(transitiveRunfiles.getRootSymlinks());
    } else {
      Runfiles transitiveRunfiles =
          new Runfiles.Builder(WORKSPACE_NAME, /* legacyExternalRunfiles= */ false)
              .addSymlink(PathFragment.create("pkg/file.txt"), sourceFile)
              .addSymlink(PathFragment.create("pkg/file.txt"), genFile)
              .build();
      runfiles.addSymlink(PathFragment.create("pkg/file.txt"), sourceFile);
      runfiles.addSymlinks(transitiveRunfiles.getSymlinks());
    }
    RunfilesTree runfilesTree =
        new RunfilesSupport.RunfilesTreeImpl(runfilesRoot, runfiles.build());

    Spawn spawn = defaultSpawnBuilder().withInput(runfilesTreeArtifact).build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(runfilesTree, runfilesTreeArtifact, sourceFile, genFile),
        createInputMap(runfilesTree),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                            + WORKSPACE_NAME
                            + "/pkg/file.txt")
                    .setDigest(getDigest("source")))
            .build());
  }

  @Test
  public void testRunfilesSymlinkPostOrderCollisionWithEqualDuplicate(
      @TestParameter boolean rootSymlink) throws Exception {
    Artifact sourceFile = ActionsTestUtil.createArtifact(rootDir, "pkg/file.txt");
    writeFile(sourceFile, "source");
    Artifact genFile = ActionsTestUtil.createArtifact(outputDir, "pkg/file.txt");
    writeFile(genFile, "gen");

    Artifact runfilesTreeArtifact =
        ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles");

    PathFragment runfilesRoot = outputDir.getExecPath().getRelative("tools/foo.runfiles");
    Runfiles.Builder runfiles =
        new Runfiles.Builder(WORKSPACE_NAME, /* legacyExternalRunfiles= */ false);
    // Arrange for (reference) equal SymlinkEntry instances to appear twice in the runfiles, both
    // first and last in compile order.
    if (rootSymlink) {
      Runfiles transitiveRunfiles =
          new Runfiles.Builder(WORKSPACE_NAME, /* legacyExternalRunfiles= */ false)
              .addRootSymlink(PathFragment.create(WORKSPACE_NAME + "/pkg/file.txt"), sourceFile)
              .addRootSymlink(PathFragment.create(WORKSPACE_NAME + "/pkg/file.txt"), genFile)
              .build();
      runfiles.addRootSymlinks(
          NestedSetBuilder.wrap(
              Order.STABLE_ORDER, transitiveRunfiles.getRootSymlinks().toList().subList(0, 1)));
      runfiles.addRootSymlinks(transitiveRunfiles.getRootSymlinks());
    } else {
      Runfiles transitiveRunfiles =
          new Runfiles.Builder(WORKSPACE_NAME, /* legacyExternalRunfiles= */ false)
              .addSymlink(PathFragment.create("pkg/file.txt"), sourceFile)
              .addSymlink(PathFragment.create("pkg/file.txt"), genFile)
              .build();
      runfiles.addSymlinks(
          NestedSetBuilder.wrap(
              Order.STABLE_ORDER, transitiveRunfiles.getSymlinks().toList().subList(0, 1)));
      runfiles.addSymlinks(transitiveRunfiles.getSymlinks());
    }
    RunfilesTree runfilesTree =
        new RunfilesSupport.RunfilesTreeImpl(runfilesRoot, runfiles.build());

    Spawn spawn = defaultSpawnBuilder().withInput(runfilesTreeArtifact).build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(runfilesTree, runfilesTreeArtifact, sourceFile, genFile),
        createInputMap(runfilesTree),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    // The compact log can't distinguish between semantically equal and reference equal SymlinkEntry
    // instances and thus the reconstructor can't deduplicate them as NestedSet would. This is a
    // pathological case that can be recreated in Starlark, but it would cause a conflict error
    // unless using --nobuild_runfile_manifests.
    String expectedContent = this instanceof CompactSpawnLogContextTest ? "source" : "gen";
    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                            + WORKSPACE_NAME
                            + "/pkg/file.txt")
                    .setDigest(getDigest(expectedContent)))
            .build());
  }

  @Test
  public void testRunfilesSymlinkTargets(
      @TestParameter boolean rootSymlinks, @TestParameter InputsMode inputsMode) throws Exception {
    Artifact sourceFile = ActionsTestUtil.createArtifact(rootDir, "pkg/file.txt");
    writeFile(sourceFile, "source");
    Artifact sourceDir = ActionsTestUtil.createArtifact(rootDir, "pkg/source_dir");
    sourceDir.getPath().createDirectoryAndParents();
    FileSystemUtils.writeContentAsLatin1(
        sourceDir.getPath().getRelative("some_file"), "source_dir_file");
    Artifact genDir =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputDir, "pkg/gen_dir");
    genDir.getPath().createDirectoryAndParents();
    FileSystemUtils.writeContentAsLatin1(
        genDir.getPath().getRelative("other_file"), "gen_dir_file");
    Artifact symlink = ActionsTestUtil.createUnresolvedSymlinkArtifact(outputDir, "pkg/symlink");
    symlink.getPath().getParentDirectory().createDirectoryAndParents();
    symlink.getPath().createSymbolicLink(PathFragment.create("/some/path"));

    Artifact runfilesArtifact =
        ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles");

    PathFragment runfilesRoot = outputDir.getExecPath().getRelative("tools/foo.runfiles");
    RunfilesTree runfilesTree =
        createRunfilesTree(
            runfilesRoot,
            rootSymlinks
                ? ImmutableMap.of()
                : ImmutableMap.of(
                    "file", sourceFile,
                    "source_dir", sourceDir,
                    "gen_dir", genDir,
                    "symlink", symlink),
            rootSymlinks
                ? ImmutableMap.of(
                    WORKSPACE_NAME + "/file", sourceFile,
                    WORKSPACE_NAME + "/source_dir", sourceDir,
                    WORKSPACE_NAME + "/gen_dir", genDir,
                    WORKSPACE_NAME + "/symlink", symlink)
                : ImmutableMap.of(),
            /* legacyExternalRunfiles= */ false);

    var spawnBuilder = defaultSpawnBuilder().withInput(runfilesArtifact);
    if (inputsMode.isTool()) {
      spawnBuilder.withTool(runfilesArtifact);
    }

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawnBuilder.build(),
        createInputMetadataProvider(
            runfilesTree, runfilesArtifact, sourceFile, sourceDir, genDir, symlink),
        createInputMap(runfilesTree),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                            + WORKSPACE_NAME
                            + "/file")
                    .setDigest(getDigest("source"))
                    .setIsTool(inputsMode.isTool()))
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                            + WORKSPACE_NAME
                            + "/gen_dir/other_file")
                    .setDigest(getDigest("gen_dir_file"))
                    .setIsTool(inputsMode.isTool()))
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                            + WORKSPACE_NAME
                            + "/source_dir/some_file")
                    .setDigest(getDigest("source_dir_file"))
                    .setIsTool(inputsMode.isTool()))
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                            + WORKSPACE_NAME
                            + "/symlink")
                    .setSymlinkTargetPath("/some/path")
                    .setIsTool(inputsMode.isTool()))
            .build());
  }

  @Test
  public void testRunfileSymlinkFileWithDirectoryContents(
      @TestParameter boolean rootSymlink, @TestParameter OutputsMode outputsMode) throws Exception {
    Artifact sourceFile = ActionsTestUtil.createArtifact(rootDir, "pkg/file.txt");
    sourceFile.getPath().createDirectoryAndParents();
    writeFile(sourceFile.getPath().getChild("file"), "abc");

    Artifact runfilesArtifact =
        ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles");

    PathFragment runfilesRoot = outputDir.getExecPath().getRelative("tools/foo.runfiles");
    RunfilesTree runfilesTree =
        createRunfilesTree(
            runfilesRoot,
            rootSymlink ? ImmutableMap.of() : ImmutableMap.of("pkg/symlink", sourceFile),
            rootSymlink
                ? ImmutableMap.of(WORKSPACE_NAME + "/pkg/symlink", sourceFile)
                : ImmutableMap.of(),
            /* legacyExternalRunfiles= */ false);

    Spawn spawn = defaultSpawnBuilder().withInput(runfilesArtifact).build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(runfilesTree, runfilesArtifact, sourceFile),
        createInputMap(runfilesTree),
        outputsMode.getActionFileSystem(fs),
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                            + WORKSPACE_NAME
                            + "/pkg/symlink/file")
                    .setDigest(getDigest("abc")))
            .build());
  }

  @Test
  public void testFilesetInput(@TestParameter DirContents dirContents) throws Exception {
    Artifact filesetInput =
        SpecialArtifact.create(
            outputDir,
            outputDir.getExecPath().getRelative("dir"),
            ActionsTestUtil.NULL_ARTIFACT_OWNER,
            SpecialArtifactType.FILESET);

    filesetInput.getPath().createDirectoryAndParents();
    if (!dirContents.isEmpty()) {
      writeFile(fs.getPath("/file.txt"), "abc");
      filesetInput
          .getPath()
          .getChild("file.txt")
          .createSymbolicLink(PathFragment.create("/file.txt"));
    }

    Spawn spawn =
        defaultSpawnBuilder()
            .withInput(filesetInput)
            // The implementation only relies on the map keys, so the value can be empty.
            .withFilesetMapping(filesetInput, FilesetOutputTree.EMPTY)
            .build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(filesetInput),
        createInputMap(filesetInput),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addAllInputs(
                dirContents.isEmpty()
                    ? ImmutableList.of()
                    : ImmutableList.of(
                        File.newBuilder()
                            .setPath(PRODUCT_NAME + "-out/k8-fastbuild/bin/dir/file.txt")
                            .setDigest(getDigest("abc"))
                            .build()))
            .build());
  }

  @Test
  public void testParamFileInput() throws Exception {
    ParamFileActionInput paramFileInput =
        new ParamFileActionInput(
            PathFragment.create("foo.params"),
            ImmutableList.of("a", "b", "c"),
            ParameterFileType.UNQUOTED);

    // Do not materialize the file on disk, which would be the case when running remotely.
    SpawnBuilder spawn = defaultSpawnBuilder().withInputs(paramFileInput);

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        // ParamFileActionInputs appear in the input map but not in the metadata provider.
        createInputMetadataProvider(),
        createInputMap(paramFileInput),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addInputs(File.newBuilder().setPath("foo.params").setDigest(getDigest("a\nb\nc\n")))
            .build());
  }

  @Test
  public void testFileOutput(
      @TestParameter OutputsMode outputsMode, @TestParameter OutputIndirection indirection)
      throws Exception {
    Artifact fileOutput = ActionsTestUtil.createArtifact(outputDir, "file");

    Path actualPath =
        indirection.viaSymlink()
            ? outputDir.getRoot().asPath().getChild("actual")
            : fileOutput.getPath();

    if (indirection.viaSymlink()) {
      fileOutput.getPath().getParentDirectory().createDirectoryAndParents();
      fileOutput.getPath().createSymbolicLink(actualPath);
    }

    writeFile(actualPath, "abc");

    Spawn spawn = defaultSpawnBuilder().withOutputs(fileOutput).build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(),
        createInputMap(),
        outputsMode.getActionFileSystem(fs),
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addListedOutputs(PRODUCT_NAME + "-out/k8-fastbuild/bin/file")
            .addActualOutputs(
                File.newBuilder()
                    .setPath(PRODUCT_NAME + "-out/k8-fastbuild/bin/file")
                    .setDigest(getDigest("abc")))
            .build());
  }

  @Test
  public void testFileOutputWithInvalidType(@TestParameter OutputsMode outputsMode)
      throws Exception {
    Artifact fileOutput = ActionsTestUtil.createArtifact(outputDir, "file");

    fileOutput.getPath().createDirectoryAndParents();
    writeFile(fileOutput.getPath().getChild("file"), "abc");

    SpawnBuilder spawn = defaultSpawnBuilder().withOutputs(fileOutput);

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        createInputMetadataProvider(),
        createInputMap(),
        outputsMode.getActionFileSystem(fs),
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addListedOutputs(PRODUCT_NAME + "-out/k8-fastbuild/bin/file")
            .build());
  }

  @Test
  public void testTreeOutput(
      @TestParameter OutputsMode outputsMode,
      @TestParameter DirContents dirContents,
      @TestParameter OutputIndirection indirection)
      throws Exception {
    SpecialArtifact treeOutput =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputDir, "tree");

    Path actualPath =
        indirection.viaSymlink()
            ? outputDir.getRoot().asPath().getChild("actual")
            : treeOutput.getPath();

    if (indirection.viaSymlink()) {
      treeOutput.getPath().getParentDirectory().createDirectoryAndParents();
      treeOutput.getPath().createSymbolicLink(actualPath);
    }

    actualPath.createDirectoryAndParents();
    if (!dirContents.isEmpty()) {
      Path firstChildPath = actualPath.getRelative("dir1/file1");
      Path secondChildPath = actualPath.getRelative("dir2/file2");
      firstChildPath.getParentDirectory().createDirectoryAndParents();
      secondChildPath.getParentDirectory().createDirectoryAndParents();
      writeFile(firstChildPath, "abc");
      writeFile(secondChildPath, "def");
      Path emptySubdirPath = actualPath.getRelative("dir3");
      emptySubdirPath.createDirectoryAndParents();
    }

    Spawn spawn = defaultSpawnBuilder().withOutputs(treeOutput).build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(),
        createInputMap(),
        outputsMode.getActionFileSystem(fs),
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addListedOutputs(PRODUCT_NAME + "-out/k8-fastbuild/bin/tree")
            .addAllActualOutputs(
                dirContents.isEmpty()
                    ? ImmutableList.of()
                    : ImmutableList.of(
                        File.newBuilder()
                            .setPath(PRODUCT_NAME + "-out/k8-fastbuild/bin/tree/dir1/file1")
                            .setDigest(getDigest("abc"))
                            .build(),
                        File.newBuilder()
                            .setPath(PRODUCT_NAME + "-out/k8-fastbuild/bin/tree/dir2/file2")
                            .setDigest(getDigest("def"))
                            .build()))
            .build());
  }

  @Test
  public void testTreeOutputWithInvalidType(@TestParameter OutputsMode outputsMode)
      throws Exception {
    Artifact treeOutput = ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputDir, "tree");

    writeFile(treeOutput, "abc");

    SpawnBuilder spawn = defaultSpawnBuilder().withOutputs(treeOutput);

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        createInputMetadataProvider(),
        createInputMap(),
        outputsMode.getActionFileSystem(fs),
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addListedOutputs(PRODUCT_NAME + "-out/k8-fastbuild/bin/tree")
            .build());
  }

  @Test
  public void testUnresolvedSymlinkOutput(@TestParameter OutputsMode outputsMode) throws Exception {
    Artifact symlinkOutput = ActionsTestUtil.createUnresolvedSymlinkArtifact(outputDir, "symlink");

    symlinkOutput.getPath().getParentDirectory().createDirectoryAndParents();
    symlinkOutput.getPath().createSymbolicLink(PathFragment.create("/some/path"));

    SpawnBuilder spawn = defaultSpawnBuilder().withOutputs(symlinkOutput);

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        createInputMetadataProvider(),
        createInputMap(),
        outputsMode.getActionFileSystem(fs),
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addListedOutputs(PRODUCT_NAME + "-out/k8-fastbuild/bin/symlink")
            .addActualOutputs(
                File.newBuilder()
                    .setPath(PRODUCT_NAME + "-out/k8-fastbuild/bin/symlink")
                    .setSymlinkTargetPath("/some/path"))
            .build());
  }

  @Test
  public void testUnresolvedSymlinkOutputWithInvalidType(@TestParameter OutputsMode outputsMode)
      throws Exception {
    Artifact symlinkOutput = ActionsTestUtil.createUnresolvedSymlinkArtifact(outputDir, "symlink");

    writeFile(symlinkOutput, "abc");

    SpawnBuilder spawn = defaultSpawnBuilder().withOutputs(symlinkOutput);

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        createInputMetadataProvider(),
        createInputMap(),
        outputsMode.getActionFileSystem(fs),
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addListedOutputs(PRODUCT_NAME + "-out/k8-fastbuild/bin/symlink")
            .build());
  }

  @Test
  public void testMissingOutput(@TestParameter OutputsMode outputsMode) throws Exception {
    Artifact missingOutput = ActionsTestUtil.createArtifact(outputDir, "missing");

    SpawnBuilder spawn = defaultSpawnBuilder().withOutputs(missingOutput);

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        createInputMetadataProvider(),
        createInputMap(),
        outputsMode.getActionFileSystem(fs),
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addListedOutputs(PRODUCT_NAME + "-out/k8-fastbuild/bin/missing")
            .build());
  }

  @Test
  public void testEnvironment() throws Exception {
    Spawn spawn =
        defaultSpawnBuilder().withEnvironment("SPAM", "eggs").withEnvironment("FOO", "bar").build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(),
        createInputMap(),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addEnvironmentVariables(
                EnvironmentVariable.newBuilder().setName("FOO").setValue("bar"))
            .addEnvironmentVariables(
                EnvironmentVariable.newBuilder().setName("SPAM").setValue("eggs"))
            .build());
  }

  @Test
  public void testDefaultPlatformProperties() throws Exception {
    SpawnLogContext context = createSpawnLogContext(ImmutableMap.of("a", "1", "b", "2"));

    context.logSpawn(
        defaultSpawn(),
        createInputMetadataProvider(),
        createInputMap(),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .setPlatform(
                Platform.newBuilder()
                    .addProperties(Platform.Property.newBuilder().setName("a").setValue("1"))
                    .addProperties(Platform.Property.newBuilder().setName("b").setValue("2"))
                    .build())
            .build());
  }

  @Test
  public void testSpawnPlatformProperties() throws Exception {
    Spawn spawn =
        defaultSpawnBuilder()
            .withCombinedExecProperties(ImmutableMap.of("a", "3", "c", "4"))
            .build();

    SpawnLogContext context = createSpawnLogContext(ImmutableMap.of("a", "1", "b", "2"));

    context.logSpawn(
        spawn,
        createInputMetadataProvider(),
        createInputMap(),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    // The spawn properties should override the default properties.
    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .setPlatform(
                Platform.newBuilder()
                    .addProperties(Platform.Property.newBuilder().setName("a").setValue("3"))
                    .addProperties(Platform.Property.newBuilder().setName("b").setValue("2"))
                    .addProperties(Platform.Property.newBuilder().setName("c").setValue("4"))
                    .build())
            .build());
  }

  @Test
  public void testExecutionInfo(
      @TestParameter({"no-remote", "no-cache", "no-remote-cache"}) String requirement)
      throws Exception {
    Spawn spawn = defaultSpawnBuilder().withExecutionInfo(requirement, "").build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(),
        createInputMap(),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .setRemotable(!requirement.equals("no-remote"))
            .setCacheable(!requirement.equals("no-cache"))
            .setRemoteCacheable(
                !requirement.equals("no-cache")
                    && !requirement.equals("no-remote")
                    && !requirement.equals("no-remote-cache"))
            .build());
  }

  @Test
  public void testCacheHit() throws Exception {
    SpawnLogContext context = createSpawnLogContext();

    SpawnResult result = defaultSpawnResultBuilder().setCacheHit(true).build();

    context.logSpawn(
        defaultSpawn(),
        createInputMetadataProvider(),
        createInputMap(),
        fs,
        defaultTimeout(),
        result);

    closeAndAssertLog(context, defaultSpawnExecBuilder().setCacheHit(true).build());
  }

  @Test
  public void testDigest() throws Exception {
    SpawnLogContext context = createSpawnLogContext();

    Digest digest = getDigest("something");

    SpawnResult result = defaultSpawnResultBuilder().setDigest(digest).build();

    context.logSpawn(
        defaultSpawn(),
        createInputMetadataProvider(),
        createInputMap(),
        fs,
        defaultTimeout(),
        result);

    closeAndAssertLog(context, defaultSpawnExecBuilder().setDigest(digest).build());
  }

  @Test
  public void testTimeout() throws Exception {
    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        defaultSpawn(),
        createInputMetadataProvider(),
        createInputMap(),
        fs,
        /* timeout= */ Duration.ofSeconds(42),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder().setTimeoutMillis(Duration.ofSeconds(42).toMillis()).build());
  }

  @Test
  public void testSpawnMetrics() throws Exception {
    SpawnMetrics metrics = SpawnMetrics.Builder.forLocalExec().setTotalTimeInMs(1).build();

    SpawnLogContext context = createSpawnLogContext();

    Instant now = Instant.now();
    context.logSpawn(
        defaultSpawn(),
        createInputMetadataProvider(),
        createInputMap(),
        fs,
        defaultTimeout(),
        defaultSpawnResultBuilder().setSpawnMetrics(metrics).setStartTime(now).build());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .setMetrics(
                Protos.SpawnMetrics.newBuilder()
                    .setTotalTime(millisToProto(1))
                    .setStartTime(Timestamps.fromDate(Date.from(now))))
            .build());
  }

  @Test
  public void testStatus() throws Exception {
    SpawnLogContext context = createSpawnLogContext();

    // SpawnResult requires a non-zero exit code and non-null failure details when the status isn't
    // successful.
    SpawnResult result =
        defaultSpawnResultBuilder()
            .setStatus(Status.NON_ZERO_EXIT)
            .setExitCode(37)
            .setFailureDetail(
                FailureDetail.newBuilder()
                    .setMessage("oops")
                    .setCrash(Crash.getDefaultInstance())
                    .build())
            .build();

    context.logSpawn(
        defaultSpawn(),
        createInputMetadataProvider(),
        createInputMap(),
        fs,
        defaultTimeout(),
        result);

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .setExitCode(37)
            .setStatus(Status.NON_ZERO_EXIT.toString())
            .build());
  }

  protected static Duration defaultTimeout() {
    return Duration.ZERO;
  }

  protected static SpawnBuilder defaultSpawnBuilder() {
    return new SpawnBuilder("cmd", "--opt");
  }

  protected static Spawn defaultSpawn() {
    return defaultSpawnBuilder().build();
  }

  protected static SpawnResult.Builder defaultSpawnResultBuilder() {
    return new SpawnResult.Builder().setRunnerName("runner").setStatus(Status.SUCCESS);
  }

  protected static SpawnResult defaultSpawnResult() {
    return defaultSpawnResultBuilder().build();
  }

  protected static SpawnExec.Builder defaultSpawnExecBuilder() {
    return SpawnExec.newBuilder()
        .addCommandArgs("cmd")
        .addCommandArgs("--opt")
        .setRunner("runner")
        .setRemotable(true)
        .setCacheable(true)
        .setRemoteCacheable(true)
        .setMnemonic("Mnemonic")
        .setTargetLabel("//dummy:label")
        .setMetrics(Protos.SpawnMetrics.getDefaultInstance());
  }

  protected static RunfilesTree createRunfilesTree(PathFragment root, Artifact... artifacts) {
    return createRunfilesTree(
        root, ImmutableMap.of(), ImmutableMap.of(), /* legacyExternalRunfiles= */ false, artifacts);
  }

  protected static RunfilesTree createRunfilesTree(
      PathFragment root,
      Map<String, Artifact> symlinks,
      Map<String, Artifact> rootSymlinks,
      boolean legacyExternalRunfiles,
      NestedSet<Artifact> artifacts) {
    Runfiles.Builder runfiles = new Runfiles.Builder(WORKSPACE_NAME, legacyExternalRunfiles);
    runfiles.addTransitiveArtifacts(artifacts);
    for (Map.Entry<String, Artifact> entry : symlinks.entrySet()) {
      runfiles.addSymlink(PathFragment.create(entry.getKey()), entry.getValue());
    }
    for (Map.Entry<String, Artifact> entry : rootSymlinks.entrySet()) {
      runfiles.addRootSymlink(PathFragment.create(entry.getKey()), entry.getValue());
    }
    runfiles.setEmptyFilesSupplier(BazelPyBuiltins.GET_INIT_PY_FILES);
    return new RunfilesSupport.RunfilesTreeImpl(root, runfiles.build());
  }

  protected static RunfilesTree createRunfilesTree(
      PathFragment root,
      Map<String, Artifact> symlinks,
      Map<String, Artifact> rootSymlinks,
      boolean legacyExternalRunfiles,
      Artifact... artifacts) {
    return createRunfilesTree(
        root,
        symlinks,
        rootSymlinks,
        legacyExternalRunfiles,
        NestedSetBuilder.wrap(Order.COMPILE_ORDER, Arrays.asList(artifacts)));
  }

  protected static InputMetadataProvider createInputMetadataProvider(Artifact... artifacts)
      throws Exception {
    return createInputMetadataProvider(null, artifacts);
  }

  protected static InputMetadataProvider createInputMetadataProvider(
      RunfilesTree runfilesTree, Artifact... artifacts) throws Exception {
    FakeActionInputFileCache builder = new FakeActionInputFileCache();
    for (Artifact artifact : artifacts) {
      if (artifact.isTreeArtifact()) {
        // Emulate ActionInputMap: add both tree and children.
        TreeArtifactValue treeMetadata = createTreeArtifactValue(artifact);
        builder.put(artifact, treeMetadata.getMetadata());
        for (Map.Entry<TreeFileArtifact, FileArtifactValue> entry :
            treeMetadata.getChildValues().entrySet()) {
          builder.put(entry.getKey(), entry.getValue());
        }
      } else if (artifact.isSymlink()) {
        builder.put(artifact, FileArtifactValue.createForUnresolvedSymlink(artifact));
      } else if (artifact.isRunfilesTree()) {
        builder.putRunfilesTree(artifact, runfilesTree);
      } else {
        builder.put(artifact, FileArtifactValue.createForTesting(artifact));
      }
    }
    return builder;
  }

  protected static SortedMap<PathFragment, ActionInput> createInputMap(ActionInput... actionInputs)
      throws Exception {
    return createInputMap(null, actionInputs);
  }

  protected static SortedMap<PathFragment, ActionInput> createInputMap(
      RunfilesTree runfilesTree, ActionInput... actionInputs) throws Exception {
    TreeMap<PathFragment, ActionInput> builder = new TreeMap<>();

    if (runfilesTree != null) {
      new SpawnInputExpander(/* execRoot= */ null)
          .addSingleRunfilesTreeToInputs(
              runfilesTree,
              builder,
              treeArtifact -> {
                try {
                  return createTreeArtifactValue(treeArtifact).getChildren();
                } catch (Exception e) {
                  throw new ArtifactExpander.MissingExpansionException(e.getMessage());
                }
              },
              PathMapper.NOOP,
              PathFragment.EMPTY_FRAGMENT);
    }

    for (ActionInput actionInput : actionInputs) {
      if (actionInput instanceof Artifact artifact && artifact.isTreeArtifact()) {
        // Emulate SpawnInputExpander: expand to children, preserve if empty.
        TreeArtifactValue treeMetadata = createTreeArtifactValue(artifact);
        if (treeMetadata.getChildren().isEmpty()) {
          builder.put(artifact.getExecPath(), artifact);
        } else {
          for (TreeFileArtifact child : treeMetadata.getChildren()) {
            builder.put(child.getExecPath(), child);
          }
        }
      } else {
        builder.put(actionInput.getExecPath(), actionInput);
      }
    }
    return ImmutableSortedMap.copyOf(builder);
  }

  protected static TreeArtifactValue createTreeArtifactValue(Artifact tree) throws Exception {
    checkState(tree.isTreeArtifact());
    TreeArtifactValue.Builder builder = TreeArtifactValue.newBuilder((SpecialArtifact) tree);
    TreeArtifactValue.visitTree(
        tree.getPath(),
        (parentRelativePath, type, traversedSymlink) -> {
          if (type.equals(Dirent.Type.DIRECTORY)) {
            return;
          }
          TreeFileArtifact child =
              TreeFileArtifact.createTreeOutput((SpecialArtifact) tree, parentRelativePath);
          builder.putChild(child, FileArtifactValue.createForTesting(child));
        });
    return builder.build();
  }

  protected SpawnLogContext createSpawnLogContext() throws IOException, InterruptedException {
    return createSpawnLogContext(ImmutableSortedMap.of());
  }

  protected abstract SpawnLogContext createSpawnLogContext(
      ImmutableMap<String, String> platformProperties) throws IOException, InterruptedException;

  protected Digest getDigest(String content) {
    return Digest.newBuilder()
        .setHash(digestHashFunction.getHashFunction().hashString(content, UTF_8).toString())
        .setSizeBytes(Utf8.encodedLength(content))
        .setHashFunctionName(digestHashFunction.toString())
        .build();
  }

  protected static void writeFile(Artifact artifact, String contents) throws IOException {
    writeFile(artifact.getPath(), contents);
  }

  protected static void writeFile(Path path, String contents) throws IOException {
    path.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(path, UTF_8, contents);
  }

  protected abstract void closeAndAssertLog(SpawnLogContext context, SpawnExec... expected)
      throws IOException, InterruptedException;
}
