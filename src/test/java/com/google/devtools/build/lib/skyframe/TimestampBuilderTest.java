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
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.util.TestAction;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.testutil.BlazeTestUtils;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test suite for TimestampBuilder.
 *
 */
@RunWith(JUnit4.class)
public class TimestampBuilderTest extends TimestampBuilderTestCase {
  private static NestedSet<Artifact> asNestedSet(Artifact... artifacts) {
    return NestedSetBuilder.create(Order.STABLE_ORDER, artifacts);
  }

  @Test
  public void testAmnesiacBuilderAlwaysRebuilds() throws Exception {
    // [action] -> hello
    Artifact hello = createDerivedArtifact("hello");
    Button button = createActionButton(emptyNestedSet, ImmutableSet.of(hello));

    button.pressed = false;
    buildArtifacts(amnesiacBuilder(), hello);
    assertThat(button.pressed).isTrue(); // built

    button.pressed = false;
    buildArtifacts(amnesiacBuilder(), hello);
    assertThat(button.pressed).isTrue(); // rebuilt
  }

  // If we re-use the same builder (even an "amnesiac" builder), it remembers
  // which Actions it has already visited, and doesn't revisit them, even if
  // they would otherwise be rebuilt.
  //
  // That is, Builders conflate traversal and dependency analysis, and don't
  // revisit a node (traversal) even if it needs to be rebuilt (dependency
  // analysis).  We might want to separate these aspects.
  @Test
  public void testBuilderDoesntRevisitActions() throws Exception {
    // [action] -> hello
    Artifact hello = createDerivedArtifact("hello");
    Counter counter = createActionCounter(emptyNestedSet, ImmutableSet.of(hello));

    Builder amnesiacBuilder = amnesiacBuilder();

    counter.count = 0;
    buildArtifacts(amnesiacBuilder, hello, hello);
    assertThat(counter.count).isEqualTo(1); // built only once
  }

  @Test
  public void testBuildingExistingSourcefileSuceeds() throws Exception {
    Artifact hello = createSourceArtifact("hello");
    BlazeTestUtils.makeEmptyFile(hello.getPath());
    buildArtifacts(cachingBuilder(), hello);
  }

  @Test
  public void testCachingBuilderCachesUntilReset() throws Exception {
    // [action] -> hello
    Artifact hello = createDerivedArtifact("hello");
    Button button = createActionButton(emptyNestedSet, ImmutableSet.of(hello));

    button.pressed = false;
    buildArtifacts(cachingBuilder(), hello);
    assertThat(button.pressed).isTrue(); // built

    button.pressed = false;
    buildArtifacts(cachingBuilder(), hello);
    assertThat(button.pressed).isFalse(); // not rebuilt

    inMemoryCache.reset();

    button.pressed = false;
    buildArtifacts(cachingBuilder(), hello);
    assertThat(button.pressed).isTrue(); // rebuilt
  }

  @Test
  public void testUnneededInputs() throws Exception {
    Artifact hello = createSourceArtifact("hello");
    hello.getPath().getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(hello.getPath(), "content1");
    Artifact optional = createSourceArtifact("hello.optional");
    Artifact goodbye = createDerivedArtifact("goodbye");
    Button button = createActionButton(asNestedSet(hello, optional), ImmutableSet.of(goodbye));

    button.pressed = false;
    buildArtifacts(cachingBuilder(), goodbye);
    assertThat(button.pressed).isTrue(); // built

    button.pressed = false;
    buildArtifacts(cachingBuilder(), goodbye);
    assertThat(button.pressed).isFalse(); // not rebuilt

    BlazeTestUtils.makeEmptyFile(optional.getPath());
    FileSystemUtils.writeContent(hello.getPath(), "content2");

    button.pressed = false;
    buildArtifacts(cachingBuilder(), goodbye);
    assertThat(button.pressed).isTrue(); // built

    button.pressed = false;
    buildArtifacts(cachingBuilder(), goodbye);
    assertThat(button.pressed).isFalse(); // not rebuilt

    optional.getPath().delete();
    FileSystemUtils.writeContent(hello.getPath(), "content3");

    button.pressed = false;
    buildArtifacts(cachingBuilder(), goodbye);
    assertThat(button.pressed).isTrue(); // built

    button.pressed = false;
    buildArtifacts(cachingBuilder(), goodbye);
    assertThat(button.pressed).isFalse(); // not rebuilt
  }

  @Test
  public void testModifyingInputCausesActionReexecution() throws Exception {
    // hello -> [action] -> goodbye
    Artifact hello = createSourceArtifact("hello");
    BlazeTestUtils.makeEmptyFile(hello.getPath());
    Artifact goodbye = createDerivedArtifact("goodbye");
    Button button = createActionButton(asNestedSet(hello), ImmutableSet.of(goodbye));

    button.pressed = false;
    buildArtifacts(cachingBuilder(), goodbye);
    assertThat(button.pressed).isTrue(); // built

    button.pressed = false;
    buildArtifacts(cachingBuilder(), goodbye);
    assertThat(button.pressed).isFalse(); // not rebuilt

    hello.getPath().setWritable(true);
    FileSystemUtils.writeContent(hello.getPath(), "new content");

    button.pressed = false;
    buildArtifacts(cachingBuilder(), goodbye);
    assertThat(button.pressed).isTrue(); // rebuilt

    button.pressed = false;
    buildArtifacts(cachingBuilder(), goodbye);
    assertThat(button.pressed).isFalse(); // not rebuilt
  }

  @Test
  public void testOnlyModifyingInputContentCausesReexecution() throws Exception {
    // hello -> [action] -> goodbye
    Artifact hello = createSourceArtifact("hello");
    // touch file to create the directory structure
    BlazeTestUtils.makeEmptyFile(hello.getPath());
    FileSystemUtils.writeContent(hello.getPath(), "content1");

    Artifact goodbye = createDerivedArtifact("goodbye");
    Button button = createActionButton(asNestedSet(hello), ImmutableSet.of(goodbye));

    button.pressed = false;
    buildArtifacts(cachingBuilder(), goodbye);
    assertThat(button.pressed).isTrue(); // built

    button.pressed = false;
    buildArtifacts(cachingBuilder(), goodbye);
    assertThat(button.pressed).isFalse(); // not rebuilt

    FileSystemUtils.touchFile(hello.getPath());

    button.pressed = false;
    buildArtifacts(cachingBuilder(), goodbye);
    assertThat(button.pressed).isFalse(); // still not rebuilt

    FileSystemUtils.writeContent(hello.getPath(), "content2");

    button.pressed = false;
    buildArtifacts(cachingBuilder(), goodbye);
    assertThat(button.pressed).isTrue(); // rebuilt

    button.pressed = false;
    buildArtifacts(cachingBuilder(), goodbye);
    assertThat(button.pressed).isFalse(); // not rebuilt
  }

  @Test
  public void testModifyingOutputCausesActionReexecution() throws Exception {
    // [action] -> hello
    Artifact hello = createDerivedArtifact("hello");
    Button button = createActionButton(emptyNestedSet, ImmutableSet.of(hello));

    button.pressed = false;
    buildArtifacts(cachingBuilder(), hello);
    assertThat(button.pressed).isTrue(); // built

    button.pressed = false;
    buildArtifacts(cachingBuilder(), hello);
    assertThat(button.pressed).isFalse(); // not rebuilt

    // Changing the *output* file 'hello' causes 'action' to re-execute, to make things consistent
    // again.
    hello.getPath().setWritable(true);
    FileSystemUtils.writeContent(hello.getPath(), "new content");

    button.pressed = false;
    buildArtifacts(cachingBuilder(), hello);
    assertThat(button.pressed).isTrue(); // rebuilt

    button.pressed = false;
    buildArtifacts(cachingBuilder(), hello);
    assertThat(button.pressed).isFalse(); // not rebuilt
  }

  @Test
  public void testBuildingTransitivePrerequisites() throws Exception {
    // hello -> [action1] -> wazuup -> [action2] -> goodbye
    Artifact hello = createSourceArtifact("hello");
    BlazeTestUtils.makeEmptyFile(hello.getPath());
    Artifact wazuup = createDerivedArtifact("wazuup");
    Button button1 = new Button();
    registerAction(new CopyingAction(button1, hello, wazuup));
    Artifact goodbye = createDerivedArtifact("goodbye");
    Button button2 = createActionButton(asNestedSet(wazuup), ImmutableSet.of(goodbye));

    button1.pressed = button2.pressed = false;
    buildArtifacts(cachingBuilder(), wazuup);
    assertThat(button1.pressed).isTrue(); // built wazuup
    assertThat(button2.pressed).isFalse(); // goodbye not built

    button1.pressed = button2.pressed = false;
    buildArtifacts(cachingBuilder(), wazuup);
    assertThat(button1.pressed).isFalse(); // wazuup not rebuilt
    assertThat(button2.pressed).isFalse(); // goodbye not built

    button1.pressed = button2.pressed = false;
    buildArtifacts(cachingBuilder(), goodbye);
    assertThat(button1.pressed).isFalse(); // wazuup not rebuilt
    assertThat(button2.pressed).isTrue(); // built goodbye

    button1.pressed = button2.pressed = false;
    buildArtifacts(cachingBuilder(), goodbye);
    assertThat(button1.pressed).isFalse(); // wazuup not rebuilt
    assertThat(button2.pressed).isFalse(); // goodbye not rebuilt

    hello.getPath().setWritable(true);
    FileSystemUtils.writeContent(hello.getPath(), "new content");

    button1.pressed = button2.pressed = false;
    buildArtifacts(cachingBuilder(), goodbye);
    assertThat(button1.pressed).isTrue(); // hello rebuilt
    assertThat(button2.pressed).isTrue(); // goodbye rebuilt
  }

  @Test
  public void testWillNotRebuildActionsWithEmptyListOfInputsSpuriously()
      throws Exception {

    Artifact anOutputFile = createDerivedArtifact("anOutputFile");
    Artifact anotherOutputFile = createDerivedArtifact("anotherOutputFile");

    Button aButton = createActionButton(emptyNestedSet, ImmutableSet.of(anOutputFile));
    Button anotherButton = createActionButton(emptyNestedSet, ImmutableSet.of(anotherOutputFile));

    buildArtifacts(cachingBuilder(), anOutputFile, anotherOutputFile);

    assertThat(aButton.pressed).isTrue();
    assertThat(anotherButton.pressed).isTrue();

    aButton.pressed = anotherButton.pressed = false;

    buildArtifacts(cachingBuilder(), anOutputFile, anotherOutputFile);

    assertThat(aButton.pressed).isFalse();
    assertThat(anotherButton.pressed).isFalse();
  }

  @Test
  public void testMissingSourceFileIsAnError() {
    // A missing input to an action must be treated as an error because there's
    // a risk that the action that consumes it will succeed, but with a
    // different behavior (imagine that it globs over the directory, for
    // example).  It's not ok to simply try the action and let the action
    // report "input file not found".
    //
    // (However, there are exceptions to this principle: C++ compilation
    // actions may depend on non-existent headers from stale .d files.  We need
    // to allow the action to proceed to execution in this case.)

    reporter.removeHandler(failFastHandler);
    // doesn't exist
    Artifact in =
        new Artifact.SourceArtifact(
            ArtifactRoot.asSourceRoot(Root.fromPath(fileSystem.getPath("/src"))),
            PathFragment.create("in/in"),
            () -> Label.parseCanonicalUnchecked("//in:in"));
    Artifact out = createDerivedArtifact("out");

    registerAction(new TestAction(TestAction.NO_EFFECT, asNestedSet(in), ImmutableSet.of(out)));

    BuildFailedException e =
        assertThrows(BuildFailedException.class, () -> buildArtifacts(amnesiacBuilder(), out));
    assertThat(e).hasMessageThat().contains("1 input file(s) do not exist");
  }
}
