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
package com.google.devtools.build.lib.analysis.actions;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.NULL_ACTION_OWNER;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext.LostInputsCheck;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.DiscoveredModulesPruner;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.ThreadStateReceiver;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.exec.BinTools;
import com.google.devtools.build.lib.exec.util.TestExecutorBuilder;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.StringEncoding;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.starlark.java.eval.EvalException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests {@link TemplateExpansionAction}.
 */
@RunWith(JUnit4.class)
public class TemplateExpansionActionTest extends FoundationTestCase {

  private static final String TEMPLATE = Joiner.on('\n').join("key=%key%", "value=%value%");
  private static final String SPECIAL_CHARS = "Š©±½_strøget";

  private ArtifactRoot outputRoot;
  private Artifact inputArtifact;
  private Artifact outputArtifact;
  private Path output;
  private List<Substitution> substitutions;
  private BlazeDirectories directories;
  private BinTools binTools;
  private final ActionKeyContext actionKeyContext = new ActionKeyContext();

  @Before
  public final void createDirectoriesAndTools() throws Exception {
    createArtifacts(TEMPLATE);

    substitutions = Lists.newArrayList();
    substitutions.add(Substitution.of("%key%", "foo"));
    substitutions.add(Substitution.of("%value%", "bar"));
    directories =
        new BlazeDirectories(
            new ServerDirectories(
                scratch.resolve("/install"),
                scratch.resolve("/base"),
                scratch.resolve("/userRoot")),
            scratch.resolve("/workspace"),
            /* defaultSystemJavabase= */ null,
            "mock-product-name");
    binTools = BinTools.empty(directories);
  }

  private void createArtifacts(String template) throws Exception {
    ArtifactRoot workspace = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/workspace")));
    scratch.dir("/workspace/out");
    outputRoot = ArtifactRoot.asDerivedRoot(scratch.dir("/workspace"), RootType.Output, "out");
    Path input = scratch.overwriteFile("/workspace/input.txt", StandardCharsets.UTF_8, template);
    inputArtifact = ActionsTestUtil.createArtifact(workspace, input);
    output = scratch.resolve("/workspace/out/destination.txt");
    outputArtifact = ActionsTestUtil.createArtifact(outputRoot, output);
  }

  private TemplateExpansionAction create() {
    TemplateExpansionAction result = new TemplateExpansionAction(NULL_ACTION_OWNER,
         outputArtifact, Template.forString(TEMPLATE), substitutions, false);
    return result;
  }

  @Test
  public void testInputsIsEmpty() {
    assertThat(create().getInputs().toList()).isEmpty();
  }

  @Test
  public void testDestinationArtifactIsOutput() {
    assertThat(create().getOutputs()).containsExactly(outputArtifact);
  }

  @Test
  public void testExpansion() throws Exception {
    Executor executor = new TestExecutorBuilder(fileSystem, directories, binTools).build();
    ActionResult unused = create().execute(createContext(executor));
    String content = new String(FileSystemUtils.readContentAsLatin1(output));
    String expected = Joiner.on('\n').join("key=foo", "value=bar");
    assertThat(content).isEqualTo(expected);
  }

  @Test
  public void testKeySameIfSame() throws Exception {
    Artifact outputArtifact2 =
        ActionsTestUtil.createArtifact(
            outputRoot, scratch.resolve("/workspace/out/destination.txt"));
    TemplateExpansionAction a = new TemplateExpansionAction(NULL_ACTION_OWNER,
         outputArtifact, Template.forString(TEMPLATE),
         ImmutableList.of(Substitution.of("%key%", "foo")), false);
    TemplateExpansionAction b = new TemplateExpansionAction(NULL_ACTION_OWNER,
         outputArtifact2, Template.forString(TEMPLATE),
         ImmutableList.of(Substitution.of("%key%", "foo")), false);

    assertThat(computeKey(a)).isEqualTo(computeKey(b));
  }

  @Test
  public void testKeyDiffersForSubstitution() throws Exception {
    Artifact outputArtifact2 =
        ActionsTestUtil.createArtifact(
            outputRoot, scratch.resolve("/workspace/out/destination.txt"));
    TemplateExpansionAction a = new TemplateExpansionAction(NULL_ACTION_OWNER,
         outputArtifact, Template.forString(TEMPLATE),
         ImmutableList.of(Substitution.of("%key%", "foo")), false);
    TemplateExpansionAction b = new TemplateExpansionAction(NULL_ACTION_OWNER,
         outputArtifact2, Template.forString(TEMPLATE),
         ImmutableList.of(Substitution.of("%key%", "foo2")), false);

    assertThat(computeKey(a)).isNotEqualTo(computeKey(b));
  }

  @Test
  public void testKeyDiffersForExecutable() throws Exception {
    Artifact outputArtifact2 =
        ActionsTestUtil.createArtifact(
            outputRoot, scratch.resolve("/workspace/out/destination.txt"));
    TemplateExpansionAction a = new TemplateExpansionAction(NULL_ACTION_OWNER,
         outputArtifact, Template.forString(TEMPLATE),
         ImmutableList.of(Substitution.of("%key%", "foo")), false);
    TemplateExpansionAction b = new TemplateExpansionAction(NULL_ACTION_OWNER,
         outputArtifact2, Template.forString(TEMPLATE),
         ImmutableList.of(Substitution.of("%key%", "foo")), true);

    assertThat(computeKey(a)).isNotEqualTo(computeKey(b));
  }

  @Test
  public void testKeyDiffersForTemplates() throws Exception {
    Artifact outputArtifact2 =
        ActionsTestUtil.createArtifact(
            outputRoot, scratch.resolve("/workspace/out/destination.txt"));
    TemplateExpansionAction a = new TemplateExpansionAction(NULL_ACTION_OWNER,
         outputArtifact, Template.forString(TEMPLATE),
         ImmutableList.of(Substitution.of("%key%", "foo")), false);
    TemplateExpansionAction b = new TemplateExpansionAction(NULL_ACTION_OWNER,
         outputArtifact2, Template.forString(TEMPLATE + " "),
         ImmutableList.of(Substitution.of("%key%", "foo")), false);

    assertThat(computeKey(a)).isNotEqualTo(computeKey(b));
  }

  private TemplateExpansionAction createWithArtifact() {
    return createWithArtifact(substitutions);
  }

  private TemplateExpansionAction createWithArtifact(List<Substitution> substitutions) {
    TemplateExpansionAction result = new TemplateExpansionAction(
        NULL_ACTION_OWNER, inputArtifact, outputArtifact, substitutions, false);
    return result;
  }

  private ActionExecutionContext createContext(Executor executor) {
    return new ActionExecutionContext(
        executor,
        /* actionInputFileCache= */ null,
        ActionInputPrefetcher.NONE,
        actionKeyContext,
        /* outputMetadataStore= */ null,
        /* rewindingEnabled= */ false,
        LostInputsCheck.NONE,
        new FileOutErr(),
        new StoredEventHandler(),
        /* clientEnv= */ ImmutableMap.of(),
        /* topLevelFilesets= */ ImmutableMap.of(),
        /* artifactExpander= */ null,
        /* actionFileSystem= */ null,
        /* skyframeDepsResult= */ null,
        DiscoveredModulesPruner.DEFAULT,
        SyscallCache.NO_CACHE,
        ThreadStateReceiver.NULL_INSTANCE);
  }

  private void executeTemplateExpansion(String expected) throws Exception {
    executeTemplateExpansion(expected, substitutions);
  }

  private void executeTemplateExpansion(String expected, List<Substitution> substitutions)
      throws Exception {
    Executor executor = new TestExecutorBuilder(fileSystem, directories, binTools).build();
    ActionResult unused = createWithArtifact(substitutions).execute(createContext(executor));
    String actual = FileSystemUtils.readContent(output, StandardCharsets.UTF_8);
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  public void testArtifactTemplateHasInput() {
    assertThat(createWithArtifact().getInputs().toList()).containsExactly(inputArtifact);
  }

  @Test
  public void testArtifactTemplateHasOutput() {
    assertThat(createWithArtifact().getOutputs()).containsExactly(outputArtifact);
  }

  @Test
  public void testArtifactTemplateExpansion() throws Exception {
    // The trailing "" is needed because scratch.overwriteFile implicitly appends "\n".
    String expected = Joiner.on('\n').join("key=foo", "value=bar", "");
    executeTemplateExpansion(expected);
  }

  @Test
  public void testWithSpecialCharacters() throws Exception {
    // We have to overwrite the artifacts since we need our template in "inputs"
    createArtifacts(SPECIAL_CHARS + "%key%");

    // scratch.overwriteFile appends a newline, so we need an additional \n here
    String expected = String.format("%s%s\n", SPECIAL_CHARS, SPECIAL_CHARS);

    executeTemplateExpansion(
        expected,
        ImmutableList.of(
            Substitution.of("%key%", StringEncoding.unicodeToInternal(SPECIAL_CHARS))));
  }

  private String computeKey(TemplateExpansionAction action) throws EvalException {
    Fingerprint fp = new Fingerprint();
    action.computeKey(actionKeyContext, /*artifactExpander=*/ null, fp);
    return fp.hexDigestAndReset();
  }
}
