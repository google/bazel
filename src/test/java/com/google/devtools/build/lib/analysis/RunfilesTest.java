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
package com.google.devtools.build.lib.analysis;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.Runfiles.ConflictChecker;
import com.google.devtools.build.lib.analysis.Runfiles.ConflictType;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.common.options.Options;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test for {@link Runfiles}.
 */
@RunWith(JUnit4.class)
public class RunfilesTest extends FoundationTestCase {

  private void checkWarning() {
    assertContainsEvent("obscured by a -> x");
    assertWithMessage("Runfiles.filterListForObscuringSymlinks should have warned once")
        .that(eventCollector.count())
        .isEqualTo(1);
    assertThat(Iterables.getOnlyElement(eventCollector).getKind()).isEqualTo(EventKind.WARNING);
  }

  private static StarlarkThread newStarlarkThread(String... options)
      throws OptionsParsingException {
    return StarlarkThread.createTransient(
        Mutability.create("test"),
        Options.parse(BuildLanguageOptions.class, options).getOptions().toStarlarkSemantics());
  }

  @Test
  public void testFilterListForObscuringSymlinksCatchesBadObscurer() {
    Map<PathFragment, Artifact> obscuringMap = new HashMap<>();
    PathFragment pathA = PathFragment.create("a");
    ArtifactRoot root = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")));
    Artifact artifactA = ActionsTestUtil.createArtifact(root, "x");
    obscuringMap.put(pathA, artifactA);
    obscuringMap.put(PathFragment.create("a/b"), ActionsTestUtil.createArtifact(root, "c/b"));
    assertThat(
            Runfiles.filterListForObscuringSymlinks(
                    true,
                    message -> reporter.handle(Event.of(EventKind.WARNING, message)),
                    obscuringMap)
                .entrySet())
        .containsExactly(Maps.immutableEntry(pathA, artifactA))
        .inOrder();
    checkWarning();
  }

  @Test
  public void testFilterListForObscuringSymlinksCatchesBadGrandParentObscurer() {
    Map<PathFragment, Artifact> obscuringMap = new HashMap<>();
    PathFragment pathA = PathFragment.create("a");
    ArtifactRoot root = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")));
    Artifact artifactA = ActionsTestUtil.createArtifact(root, "x");
    obscuringMap.put(pathA, artifactA);
    obscuringMap.put(PathFragment.create("a/b/c"), ActionsTestUtil.createArtifact(root, "b/c"));
    assertThat(
            Runfiles.filterListForObscuringSymlinks(
                    true,
                    message -> reporter.handle(Event.of(EventKind.WARNING, message)),
                    obscuringMap)
                .entrySet())
        .containsExactly(Maps.immutableEntry(pathA, artifactA))
        .inOrder();
    checkWarning();
  }

  @Test
  public void testFilterListForObscuringSymlinksCatchesBadObscurerNoListener() {
    Map<PathFragment, Artifact> obscuringMap = new HashMap<>();
    PathFragment pathA = PathFragment.create("a");
    ArtifactRoot root = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")));
    Artifact artifactA = ActionsTestUtil.createArtifact(root, "a");
    obscuringMap.put(pathA, artifactA);
    obscuringMap.put(PathFragment.create("a/b"), ActionsTestUtil.createArtifact(root, "c/b"));
    assertThat(
            Runfiles.filterListForObscuringSymlinks(
                    true,
                    message -> reporter.handle(Event.of(EventKind.WARNING, message)),
                    obscuringMap)
                .entrySet())
        .containsExactly(Maps.immutableEntry(pathA, artifactA))
        .inOrder();
  }

  @Test
  public void testFilterListForObscuringSymlinksIgnoresOkObscurer() {
    Map<PathFragment, Artifact> obscuringMap = new HashMap<>();
    PathFragment pathA = PathFragment.create("a");
    ArtifactRoot root = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")));
    Artifact artifactA = ActionsTestUtil.createArtifact(root, "a");
    obscuringMap.put(pathA, artifactA);
    obscuringMap.put(PathFragment.create("a/b"), ActionsTestUtil.createArtifact(root, "a/b"));

    assertThat(
            Runfiles.filterListForObscuringSymlinks(
                    true,
                    message -> reporter.handle(Event.of(EventKind.WARNING, message)),
                    obscuringMap)
                .entrySet())
        .containsExactly(Maps.immutableEntry(pathA, artifactA))
        .inOrder();
    assertNoEvents();
  }

  @Test
  public void testFilterListForObscuringSymlinksNoObscurers() {
    Map<PathFragment, Artifact> obscuringMap = new HashMap<>();
    PathFragment pathA = PathFragment.create("a");
    ArtifactRoot root = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")));
    Artifact artifactA = ActionsTestUtil.createArtifact(root, "a");
    obscuringMap.put(pathA, artifactA);
    PathFragment pathBC = PathFragment.create("b/c");
    Artifact artifactBC = ActionsTestUtil.createArtifact(root, "a/b");
    obscuringMap.put(pathBC, artifactBC);
    assertThat(
            Runfiles.filterListForObscuringSymlinks(
                    true,
                    message -> reporter.handle(Event.of(EventKind.WARNING, message)),
                    obscuringMap)
                .entrySet())
        .containsExactly(
            Maps.immutableEntry(pathA, artifactA), Maps.immutableEntry(pathBC, artifactBC));
    assertNoEvents();
  }

  private static final class SimpleActionLookupKey implements ActionLookupKey {
    private final String name;

    SimpleActionLookupKey(String name) {
      this.name = name;
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctionName.createHermetic(name);
    }

    @Nullable
    @Override
    public Label getLabel() {
      return null;
    }

    @Nullable
    @Override
    public BuildConfigurationKey getConfigurationKey() {
      return null;
    }
  }

  @Test
  public void testPutDerivedArtifactWithDifferentOwner() throws Exception {
    ArtifactRoot root =
        ArtifactRoot.asDerivedRoot(scratch.dir("/workspace"), RootType.Output, "out");
    PathFragment path = PathFragment.create("src/foo.cc");

    SimpleActionLookupKey owner1 = new SimpleActionLookupKey("//owner1");
    SimpleActionLookupKey owner2 = new SimpleActionLookupKey("//owner2");
    Artifact artifact1 = DerivedArtifact.create(root, root.getExecPath().getRelative(path), owner1);
    Artifact artifact2 = DerivedArtifact.create(root, root.getExecPath().getRelative(path), owner2);

    Map<PathFragment, Artifact> map = new LinkedHashMap<>();

    Runfiles.ConflictChecker checker = eventConflictChecker(Runfiles.ConflictPolicy.WARN);
    checker.put(map, path, artifact1);
    assertThat(map.entrySet()).containsExactly(Maps.immutableEntry(path, artifact1));
    checker.put(map, path, artifact2);
    assertThat(map.entrySet()).containsExactly(Maps.immutableEntry(path, artifact2));
    assertNoEvents();
  }

  private BiConsumer<ConflictType, String> eventConflictReceiver(EventKind eventKind) {
    return (conflictType, message) -> reporter.handle(Event.of(eventKind, message));
  }

  private Runfiles.ConflictChecker eventConflictChecker(Runfiles.ConflictPolicy conflictPolicy) {
    return new ConflictChecker(
        eventConflictReceiver(
            conflictPolicy == Runfiles.ConflictPolicy.ERROR ? EventKind.ERROR : EventKind.WARNING),
        conflictPolicy == Runfiles.ConflictPolicy.IGNORE
            ? EnumSet.of(
                ConflictType.NESTED_RUNFILES_TREE,
                ConflictType.NESTED_RUNFILES_TREE,
                ConflictType.PREFIX_CONFLICT)
            : EnumSet.allOf(ConflictType.class));
  }
  ;

  @Test
  public void testPutNoConflicts() {
    ArtifactRoot root = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")));
    PathFragment pathA = PathFragment.create("a");
    PathFragment pathB = PathFragment.create("b");
    PathFragment pathC = PathFragment.create("c");
    Artifact artifactA = ActionsTestUtil.createArtifact(root, "a");
    Artifact artifactB = ActionsTestUtil.createArtifact(root, "b");
    Map<PathFragment, Artifact> map = new LinkedHashMap<>();

    Runfiles.ConflictChecker checker = eventConflictChecker(Runfiles.ConflictPolicy.WARN);
    checker.put(map, pathA, artifactA);
    // Add different artifact under different path
    checker.put(map, pathB, artifactB);
    // Add artifact again under different path
    checker.put(map, pathC, artifactA);
    assertThat(map.entrySet())
        .containsExactly(
            Maps.immutableEntry(pathA, artifactA),
            Maps.immutableEntry(pathB, artifactB),
            Maps.immutableEntry(pathC, artifactA))
        .inOrder();
    assertNoEvents();
  }

  @Test
  public void testBuilderMergeConflictPolicyDefault() {
    Runfiles r1 = new Runfiles.Builder("TESTING").build();
    Runfiles r2 = new Runfiles.Builder("TESTING").merge(r1).build();
    assertThat(r2.getConflictPolicy()).isEqualTo(Runfiles.ConflictPolicy.IGNORE);
  }

  @Test
  public void testBuilderMergeConflictPolicyInherit() {
    Runfiles r1 = new Runfiles.Builder("TESTING").build()
        .setConflictPolicy(Runfiles.ConflictPolicy.WARN);
    Runfiles r2 = new Runfiles.Builder("TESTING").merge(r1).build();
    assertThat(r2.getConflictPolicy()).isEqualTo(Runfiles.ConflictPolicy.WARN);
  }

  @Test
  public void testBuilderMergeConflictPolicyInheritStrictest() {
    Runfiles r1 = new Runfiles.Builder("TESTING").build()
        .setConflictPolicy(Runfiles.ConflictPolicy.WARN);
    Runfiles r2 = new Runfiles.Builder("TESTING").build()
        .setConflictPolicy(Runfiles.ConflictPolicy.ERROR);
    Runfiles r3 = new Runfiles.Builder("TESTING").merge(r1).merge(r2).build();
    assertThat(r3.getConflictPolicy()).isEqualTo(Runfiles.ConflictPolicy.ERROR);
    // Swap ordering
    Runfiles r4 = new Runfiles.Builder("TESTING").merge(r2).merge(r1).build();
    assertThat(r4.getConflictPolicy()).isEqualTo(Runfiles.ConflictPolicy.ERROR);
  }

  @Test
  public void testLegacyRunfilesStructure() {
    ArtifactRoot root = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")));
    PathFragment workspaceName = PathFragment.create("wsname");
    PathFragment pathB = PathFragment.create("repo/b");
    PathFragment legacyPathB = LabelConstants.EXTERNAL_PATH_PREFIX.getRelative(pathB);
    PathFragment runfilesPathB = LabelConstants.EXTERNAL_RUNFILES_PATH_PREFIX.getRelative(pathB);
    Artifact artifactB = ActionsTestUtil.createArtifactWithRootRelativePath(root, legacyPathB);

    Runfiles.ManifestBuilder builder = new Runfiles.ManifestBuilder(workspaceName, true);

    Map<PathFragment, Artifact> inputManifest = Maps.newHashMap();
    inputManifest.put(runfilesPathB, artifactB);
    Runfiles.ConflictChecker checker = eventConflictChecker(Runfiles.ConflictPolicy.WARN);
    builder.addUnderWorkspace(inputManifest, checker);

    assertThat(builder.build().entrySet())
        .containsExactly(
            Maps.immutableEntry(workspaceName.getRelative(legacyPathB), artifactB),
            Maps.immutableEntry(PathFragment.create("repo/b"), artifactB));
    assertNoEvents();
  }

  @Test
  public void testRunfileAdded() {
    ArtifactRoot root = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")));
    PathFragment workspaceName = PathFragment.create("wsname");
    PathFragment pathB = PathFragment.create("repo/b");
    PathFragment legacyPathB = LabelConstants.EXTERNAL_PATH_PREFIX.getRelative(pathB);
    PathFragment runfilesPathB = LabelConstants.EXTERNAL_RUNFILES_PATH_PREFIX.getRelative(pathB);
    Artifact artifactB = ActionsTestUtil.createArtifactWithRootRelativePath(root, legacyPathB);

    Runfiles.ManifestBuilder builder = new Runfiles.ManifestBuilder(workspaceName, false);

    Map<PathFragment, Artifact> inputManifest = ImmutableMap.of(runfilesPathB, artifactB);
    Runfiles.ConflictChecker checker = eventConflictChecker(Runfiles.ConflictPolicy.WARN);
    builder.addUnderWorkspace(inputManifest, checker);

    assertThat(builder.build().entrySet()).containsExactly(
        Maps.immutableEntry(workspaceName.getRelative(".runfile"), null),
        Maps.immutableEntry(PathFragment.create("repo/b"), artifactB));
    assertNoEvents();
  }

  @Test
  public void testMergeWithSymlinks() throws Exception {
    ArtifactRoot root = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")));
    Artifact artifactA = ActionsTestUtil.createArtifact(root, "a/target");
    Artifact artifactB = ActionsTestUtil.createArtifact(root, "b/target");
    PathFragment sympathA = PathFragment.create("a/symlink");
    PathFragment sympathB = PathFragment.create("b/symlink");
    Runfiles runfilesA = new Runfiles.Builder("TESTING")
        .addSymlink(sympathA, artifactA)
        .build();
    Runfiles runfilesB = new Runfiles.Builder("TESTING")
        .addSymlink(sympathB, artifactB)
        .build();
    StarlarkThread thread = newStarlarkThread();

    Runfiles runfilesC = runfilesA.merge(runfilesB, thread);
    assertThat(runfilesC.getSymlinksAsMap(ConflictChecker.IGNORE_CHECKER).get(sympathA))
        .isEqualTo(artifactA);
    assertThat(runfilesC.getSymlinksAsMap(ConflictChecker.IGNORE_CHECKER).get(sympathB))
        .isEqualTo(artifactB);
  }

  @Test
  public void mergeAll_symlinks() throws Exception {
    ArtifactRoot root = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")));
    Artifact artifactA = ActionsTestUtil.createArtifact(root, "a/target");
    Artifact artifactB = ActionsTestUtil.createArtifact(root, "b/target");
    Artifact artifactC = ActionsTestUtil.createArtifact(root, "c/target");
    PathFragment sympathA = PathFragment.create("a/symlink");
    PathFragment sympathB = PathFragment.create("b/symlink");
    PathFragment sympathC = PathFragment.create("c/symlink");
    Runfiles runfilesA = new Runfiles.Builder("TESTING").addSymlink(sympathA, artifactA).build();
    Runfiles runfilesB = new Runfiles.Builder("TESTING").addSymlink(sympathB, artifactB).build();
    Runfiles runfilesC = new Runfiles.Builder("TESTING").addSymlink(sympathC, artifactC).build();
    StarlarkThread thread = newStarlarkThread();

    Runfiles runfilesMerged =
        runfilesA.mergeAll(StarlarkList.immutableOf(runfilesB, runfilesC), thread);
    assertThat(runfilesMerged.getSymlinksAsMap(ConflictChecker.IGNORE_CHECKER))
        .containsExactly(sympathA, artifactA, sympathB, artifactB, sympathC, artifactC);
  }

  @Test
  public void testMergeEmptyWithNonEmpty() throws Exception {
    ArtifactRoot root = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")));
    Artifact artifactA = ActionsTestUtil.createArtifact(root, "a/target");
    Runfiles runfilesB = new Runfiles.Builder("TESTING").addArtifact(artifactA).build();
    StarlarkThread thread = newStarlarkThread();

    assertThat(Runfiles.EMPTY.merge(runfilesB, thread)).isSameInstanceAs(runfilesB);
    assertThat(runfilesB.merge(Runfiles.EMPTY, thread)).isSameInstanceAs(runfilesB);
  }

  @Test
  public void mergeAll_emptyWithNonEmpty() throws Exception {
    ArtifactRoot root = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")));
    Artifact artifact = ActionsTestUtil.createArtifact(root, "target");
    Runfiles nonEmpty = new Runfiles.Builder("TESTING").addArtifact(artifact).build();
    StarlarkThread thread = newStarlarkThread();

    assertThat(Runfiles.EMPTY.mergeAll(StarlarkList.immutableOf(nonEmpty), thread))
        .isSameInstanceAs(nonEmpty);
    assertThat(
            Runfiles.EMPTY.mergeAll(
                StarlarkList.immutableOf(Runfiles.EMPTY, nonEmpty, Runfiles.EMPTY), thread))
        .isSameInstanceAs(nonEmpty);
    assertThat(nonEmpty.mergeAll(StarlarkList.immutableOf(Runfiles.EMPTY, Runfiles.EMPTY), thread))
        .isSameInstanceAs(nonEmpty);
    assertThat(nonEmpty.mergeAll(StarlarkList.immutableOf(), thread)).isSameInstanceAs(nonEmpty);
  }

  @Test
  public void mergeAll_emptyWithEmpty() throws Exception {
    StarlarkThread thread = newStarlarkThread();
    assertThat(Runfiles.EMPTY.mergeAll(StarlarkList.immutableOf(), thread))
        .isSameInstanceAs(Runfiles.EMPTY);
    assertThat(
            Runfiles.EMPTY.mergeAll(
                StarlarkList.immutableOf(Runfiles.EMPTY, Runfiles.EMPTY), thread))
        .isSameInstanceAs(Runfiles.EMPTY);
  }

  @Test
  public void merge_exceedsDepthLimit_throwsException() throws Exception {
    ArtifactRoot root = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")));
    Artifact artifactA = ActionsTestUtil.createArtifact(root, "a/target");
    Artifact artifactB = ActionsTestUtil.createArtifact(root, "b/target");
    Artifact artifactC = ActionsTestUtil.createArtifact(root, "c/target");
    Runfiles runfilesA = new Runfiles.Builder("TESTING").addArtifact(artifactA).build();
    Runfiles runfilesB = new Runfiles.Builder("TESTING").addArtifact(artifactB).build();
    Runfiles runfilesC = new Runfiles.Builder("TESTING").addArtifact(artifactC).build();
    StarlarkThread thread = newStarlarkThread("--nested_set_depth_limit=2");

    Runfiles mergeAB = runfilesA.merge(runfilesB, thread);
    EvalException expected =
        assertThrows(EvalException.class, () -> mergeAB.merge(runfilesC, thread));
    assertThat(expected).hasMessageThat().contains("artifacts depset depth 3 exceeds limit (2)");
  }

  @Test
  public void mergeAll_exceedsDepthLimit_throwsException() throws Exception {
    ArtifactRoot root = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")));
    Artifact artifactA = ActionsTestUtil.createArtifact(root, "a/target");
    Artifact artifactB = ActionsTestUtil.createArtifact(root, "b/target");
    Artifact artifactC = ActionsTestUtil.createArtifact(root, "c/target");
    PathFragment sympathA = PathFragment.create("a/symlink");
    PathFragment sympathB = PathFragment.create("b/symlink");
    PathFragment sympathC = PathFragment.create("c/symlink");
    Runfiles runfilesA = new Runfiles.Builder("TESTING").addSymlink(sympathA, artifactA).build();
    Runfiles runfilesB = new Runfiles.Builder("TESTING").addSymlink(sympathB, artifactB).build();
    Runfiles runfilesC = new Runfiles.Builder("TESTING").addSymlink(sympathC, artifactC).build();
    StarlarkThread thread = newStarlarkThread("--nested_set_depth_limit=2");

    Runfiles mergeAllAB = runfilesA.mergeAll(StarlarkList.immutableOf(runfilesB), thread);
    EvalException expected =
        assertThrows(
            EvalException.class,
            () -> mergeAllAB.mergeAll(StarlarkList.immutableOf(runfilesC), thread));
    assertThat(expected).hasMessageThat().contains("symlinks depset depth 3 exceeds limit (2)");
  }

  @Test
  public void testGetEmptyFilenames() {
    ArtifactRoot root = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")));
    Artifact artifact = ActionsTestUtil.createArtifact(root, "my-artifact");
    Runfiles runfiles =
        new Runfiles.Builder("TESTING")
            .addArtifact(artifact)
            .addSymlink(PathFragment.create("my-symlink"), artifact)
            .addRootSymlink(PathFragment.create("my-root-symlink"), artifact)
            .setEmptyFilesSupplier(
                new Runfiles.EmptyFilesSupplier() {
                  @Override
                  public ImmutableList<PathFragment> getExtraPaths(
                      Set<PathFragment> manifestPaths) {
                    return manifestPaths.stream()
                        .map((f) -> f.replaceName(f.getBaseName() + "-empty"))
                        .collect(ImmutableList.toImmutableList());
                  }

                  @Override
                  public void fingerprint(Fingerprint fingerprint) {}
                })
            .build();
    assertThat(runfiles.getEmptyFilenames())
        .containsExactly(
            PathFragment.create("my-artifact-empty"), PathFragment.create("my-symlink-empty"));
  }
}
