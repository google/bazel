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
package com.google.devtools.build.lib.buildtool;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.ActionGraphVisitor;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.extra.ExtraActionInfo;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ExtraActionArtifactsProvider;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.extra.ExtraActionSpec;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.includescanning.IncludeScanningModule;
import com.google.devtools.build.lib.rules.java.JavaInfo;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.common.options.OptionsParsingException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests the action_listener/extra_action feature. (--experimental_action_listener blaze option) */
@RunWith(JUnit4.class)
public final class ActionListenerIntegrationTest extends BuildIntegrationTestCase {

  private final ActionKeyContext actionKeyContext = new ActionKeyContext();

  @Override
  protected BlazeRuntime.Builder getRuntimeBuilder() throws Exception {
    return super.getRuntimeBuilder().addBlazeModule(new IncludeScanningModule());
  }

  private Map<ConfiguredTarget, Iterable<Artifact.DerivedArtifact>> getExtraArtifactMap() {
    Map<ConfiguredTarget, Iterable<Artifact.DerivedArtifact>> result = new LinkedHashMap<>();
    for (ConfiguredTarget configuredTarget : getAllConfiguredTargets()) {
      ExtraActionArtifactsProvider provider = configuredTarget.getProvider(
          ExtraActionArtifactsProvider.class);
      if (provider != null && !provider.getExtraActionArtifacts().isEmpty()) {
        result.put(configuredTarget, provider.getExtraActionArtifacts().toList());
      }
    }
    return result;
  }

  private NestedSet<Artifact.DerivedArtifact> getExtraActionArtifacts(ConfiguredTarget target) {
    return target.getProvider(ExtraActionArtifactsProvider.class).getExtraActionArtifacts();
  }

  private void assertExtraActionOutputForJavaLibraryRule(String rule, String extraAction,
      boolean shouldBePresent, boolean shouldDependOnOutput) throws Exception {
    final String extraActionPath = extraAction.substring(2).replace(':', '/');
    final String rulePackage = rule.substring(2, rule.indexOf(':'));
    final String extraActionOutputRoot =
        "extra_actions/" + extraActionPath + "/" + rulePackage + "/";

    final ConfiguredTarget javalib = getConfiguredTarget(rule);

    assertThat(javalib).isNotNull();

    NestedSet<Artifact.DerivedArtifact> extraArtifacts = getExtraActionArtifacts(javalib);
    assertThat(extraArtifacts).isNotNull();

    final Set<ActionAnalysisMetadata> actions = new HashSet<>();

    class JavacFinderVisitor extends ActionGraphVisitor {
      public JavacFinderVisitor(ActionGraph actionGraph) {
        super(actionGraph);
      }

      @Override
      protected boolean shouldVisit(ActionAnalysisMetadata action) {
        return action.getOwner().getLabel().equals(javalib.getLabel());
      }

      @Override
      protected void visitAction(ActionAnalysisMetadata action) {
        if (action.getMnemonic().equals("Javac")) {
          actions.add(action);
        }
      }
    }

    JavacFinderVisitor visitor = new JavacFinderVisitor(getActionGraph());

    Set<Artifact> outputs =
        Sets.newHashSet(
            Iterables.concat(
                JavaInfo.getProvider(JavaRuleOutputJarsProvider.class, javalib)
                    .getAllSrcOutputJars(),
                JavaInfo.getProvider(JavaRuleOutputJarsProvider.class, javalib)
                    .getAllClassOutputJars()));
    outputs.addAll(getFilesToBuild(javalib).toList());
    visitor.visitWhiteNodes(outputs);

    assertThat(actions).isNotEmpty();

    assertThat(extraArtifacts.toList()).hasSize(2 * actions.size());

    for (ActionAnalysisMetadata action : actions) {
      boolean hasProtoArtifact = false;
      boolean hasTestArtifact = false;

      String ownerDigest =
          new Fingerprint().addString(action.getOwner().getLabel().toString()).hexDigestAndReset();

      String actionId =
          ExtraActionSpec.getActionId(actionKeyContext, action.getOwner(), (Action) action);
      String testArtifactPath = extraActionOutputRoot + ownerDigest + "_" + actionId + ".tst";
      String protoArtifactPath = extraActionOutputRoot + actionId + ".xa";

      for (Artifact extraArtifact : extraArtifacts.toList()) {
        Path path = extraArtifact.getPath();
        if (path.toString().endsWith(protoArtifactPath)) {
          hasProtoArtifact = true;
          if (shouldBePresent) {
            ExtraActionInfo.Builder builder = ExtraActionInfo.newBuilder();
            InputStream inputStream = path.getInputStream();
            builder.mergeFrom(inputStream);
            ExtraActionInfo info = builder.build();
            assertThat(info.getOwner()).isEqualTo(rule);
          }
          continue;
        }
        ActionAnalysisMetadata artifactOwningExtraAction = getActionGraph()
            .getGeneratingAction(extraArtifact);

        assertThat(artifactOwningExtraAction).isNotNull();

        Set<Artifact> extraActionInputs = artifactOwningExtraAction.getInputs().toSet();
        Set<Artifact> actionOutputs = Sets.newHashSet(action.getOutputs());
        if (shouldDependOnOutput) {
          // If the extra_action has require_action_output set, all of the outputs of the
          // shadowed action should be part of the extra_action's inputs.
          assertThat(extraActionInputs).containsAtLeastElementsIn(actionOutputs);
        } else {
          // If the extra_action doesn't have requires_action_output set, none of the outputs of the
          // shadowed action should be part of the extra_action's inputs.
          assertThat(Sets.intersection(extraActionInputs, actionOutputs)).isEmpty();
        }

        assertThat(path.exists()).isEqualTo(shouldBePresent);

        if (path.toString().endsWith(testArtifactPath)) {
          hasTestArtifact = true;
          if (shouldBePresent) {
            String contents = readToString(extraArtifact);
            String[] lines = contents.split("\n");
            assertThat(lines).isNotEmpty();
            String firstLine = lines[0];

            assertThat(firstLine).endsWith(protoArtifactPath);
          }
        }
      }
      assertThat(hasProtoArtifact).isTrue();
      assertThat(hasTestArtifact).isTrue();
    }
  }

  @Test
  public void testBasicActionListener() throws Exception {
    write(
        "nobuild/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name= 'javalib',",
        "             srcs=[])",
        "extra_action(name = 'baz',",
        "             out_templates = ['$(OWNER_LABEL_DIGEST)_$(ACTION_ID).tst'],",
        "             cmd = "
            + "                 'echo $(EXTRA_ACTION_FILE)>$(output $(OWNER_LABEL_DIGEST)"
            + "_$(ACTION_ID).tst)')",
        "action_listener(name = 'bar',",
        "                mnemonics = ['Javac'],",
        "                extra_actions = [':baz'])");

    addOptions("--experimental_action_listener=//nobuild:bar");

    buildTarget("//nobuild:javalib");

    Map<ConfiguredTarget, Iterable<Artifact.DerivedArtifact>> extraArtifactsMap =
        getExtraArtifactMap();
    assertThat(extraArtifactsMap).hasSize(1);

    assertExtraActionOutputForJavaLibraryRule("//nobuild:javalib", "//nobuild:baz", true, false);
  }

   @Test
   public void testActionListenerThatRequiresActionOutputs() throws Exception {
    write(
        "nobuild/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name= 'javalib',",
        "             srcs=[])",
        "extra_action(name = 'baz',",
        "             out_templates = ['$(OWNER_LABEL_DIGEST)_$(ACTION_ID).tst'],",
        "             requires_action_output = 1,",
        "             cmd = "
            + "                 'echo $(EXTRA_ACTION_FILE)>$(output $(OWNER_LABEL_DIGEST)"
            + "_$(ACTION_ID).tst)')",
        "action_listener(name = 'bar',",
        "                mnemonics = ['Javac'],",
        "                extra_actions = [':baz'])");

    addOptions("--experimental_action_listener=//nobuild:bar");

    buildTarget("//nobuild:javalib");

    Map<ConfiguredTarget, Iterable<Artifact.DerivedArtifact>> extraArtifactsMap =
        getExtraArtifactMap();
    assertThat(extraArtifactsMap).hasSize(1);

    assertExtraActionOutputForJavaLibraryRule("//nobuild:javalib", "//nobuild:baz", true, true);
  }

  @Test
  public void testFilteredActionListener() throws Exception {
    write(
        "filtered/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name= 'a',",
        "             srcs=[])",
        "java_library(name= 'b',",
        "             exports=[':a'])",
        "extra_action(name = 'baz',",
        "             out_templates = ['$(OWNER_LABEL_DIGEST)_$(ACTION_ID).tst'],",
        "             cmd = "
            + "                 'echo $(EXTRA_ACTION_FILE)>$(output $(OWNER_LABEL_DIGEST)"
            + "_$(ACTION_ID).tst)')",
        "action_listener(name = 'bar',",
        "                mnemonics = ['Javac'],",
        "                extra_actions = [':baz'])");

    addOptions("--experimental_action_listener=//filtered:bar",
        "--experimental_extra_action_filter=.*\\:a");

    buildTarget("//filtered:b");

    Map<ConfiguredTarget, Iterable<Artifact.DerivedArtifact>> extraArtifactsMap =
        getExtraArtifactMap();
    assertThat(extraArtifactsMap).hasSize(2);

    assertExtraActionOutputForJavaLibraryRule("//filtered:a", "//filtered:baz", true, false);
    assertExtraActionOutputForJavaLibraryRule("//filtered:b", "//filtered:baz", false, false);
  }

  @Test
  public void testTopLevelOnlyActionListener() throws Exception {
    write(
        "filtered/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name= 'a',",
        "             srcs=[])",
        "java_library(name= 'b',",
        "             exports=[':a'])",
        "extra_action(name = 'baz',",
        "             out_templates = ['$(OWNER_LABEL_DIGEST)_$(ACTION_ID).tst'],",
        "             cmd = "
            + "                 'echo $(EXTRA_ACTION_FILE)>$(output $(OWNER_LABEL_DIGEST)"
            + "_$(ACTION_ID).tst)')",
        "action_listener(name = 'bar',",
        "                mnemonics = ['Javac'],",
        "                extra_actions = [':baz'])");

    addOptions("--experimental_action_listener=//filtered:bar",
        "--experimental_extra_action_top_level_only");

    buildTarget("//filtered:b");

    Map<ConfiguredTarget, Iterable<Artifact.DerivedArtifact>> extraArtifactsMap =
        getExtraArtifactMap();
    assertThat(extraArtifactsMap).hasSize(2);

    assertExtraActionOutputForJavaLibraryRule("//filtered:a", "//filtered:baz", false, false);
    assertExtraActionOutputForJavaLibraryRule("//filtered:b", "//filtered:baz", true, false);
  }

  @Test
  public void testCcTestActionListener() throws Exception {
    write("nobuild/main.cc",
        "int  main() { return 0; }");
    write("nobuild/BUILD",
        "cc_test(name= 'cctest',",
        "             srcs=['main.cc'])",
        "extra_action(name = 'baz',",
        "             out_templates = ['$(ACTION_ID).tst'],",
        "             cmd = " +
        "                 'echo $(EXTRA_ACTION_FILE)>$(output $(ACTION_ID).tst)')",
        "action_listener(name = 'bar',",
        "                mnemonics = ['CppCompile'],",
        "                extra_actions = [':baz'])");

    addOptions("--experimental_action_listener=//nobuild:bar");

    buildTarget("//nobuild:cctest");
    final ConfiguredTarget cctest = getConfiguredTarget("//nobuild:cctest");

    assertThat(cctest).isNotNull();

    NestedSet<Artifact.DerivedArtifact> extraArtifacts = getExtraActionArtifacts(cctest);
    assertThat(extraArtifacts).isNotNull();

    final Set<ActionAnalysisMetadata> actions = new HashSet<>();
    class CppCompileActionFinder extends ActionGraphVisitor {

      public CppCompileActionFinder(ActionGraph actionGraph) {
        super(actionGraph);
      }

      @Override
      protected boolean shouldVisit(ActionAnalysisMetadata action) {
        return action.getOwner().getLabel().equals(cctest.getLabel());
      }

      @Override
      protected void visitAction(ActionAnalysisMetadata action) {
        if (action.getMnemonic().equals("CppCompile")) {
          actions.add(action);
        }
      }
    }

    CppCompileActionFinder visitor = new CppCompileActionFinder(getActionGraph());

    Set<Artifact> outputs = new HashSet<>();
    outputs.addAll(getFilesToBuild(cctest).toList());
    visitor.visitWhiteNodes(outputs);

    assertThat(actions).isNotEmpty();
    assertThat(extraArtifacts.toList()).hasSize(2 * actions.size());

    for (ActionAnalysisMetadata action : actions) {
      boolean hasProtoArtifact = false;
      boolean hasTestArtifact = false;
      String actionId =
          ExtraActionSpec.getActionId(actionKeyContext, action.getOwner(), (Action) action);

      String testArtifactPath = "extra_actions/nobuild/baz/nobuild/" + actionId + ".tst";
      String protoArtifactPath = "extra_actions/nobuild/baz/nobuild/" + actionId + ".xa";

      for (Artifact extraArtifact : extraArtifacts.toList()) {
        Path path = extraArtifact.getPath();
        assertThat(path.exists()).isTrue();

        if (path.toString().endsWith(testArtifactPath)) {
          hasTestArtifact = true;

          String contents = readToString(extraArtifact);
          String[] lines = contents.split("\n");
          assertThat(lines).isNotEmpty();
          String firstLine = lines[0];

          assertThat(firstLine).endsWith(protoArtifactPath);
        }
        if (path.toString().endsWith(protoArtifactPath)) {
          hasProtoArtifact = true;

          ExtraActionInfo.Builder builder = ExtraActionInfo.newBuilder();
          InputStream inputStream = path.getInputStream();
          builder.mergeFrom(inputStream);
          ExtraActionInfo info = builder.build();
          assertThat(info.getOwner()).isEqualTo("//nobuild:cctest");
        }
      }
      assertThat(hasProtoArtifact).isTrue();
      assertThat(hasTestArtifact).isTrue();
    }
  }


  @Test
  public void testActionListenerNotEnabled() throws Exception {
    write(
        "nobuild/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name= 'javalib',",
        "             srcs=[])",
        "extra_action(name = 'baz',",
        "             out_templates = ['$(ACTION_ID).tst'],",
        "             cmd = " + "'echo $(EXTRA_ACTION_FILE)>$(output $(ACTION_ID).tst)')",
        "action_listener(name = 'bar',",
        "                mnemonics = ['Javac'],",
        "                extra_actions = [':baz'])");

    buildTarget("//nobuild:javalib");
    ConfiguredTarget javalib = getConfiguredTarget("//nobuild:javalib");

    assertThat(javalib).isNotNull();

    Map<ConfiguredTarget, Iterable<Artifact.DerivedArtifact>> extraArtifactsMap =
        getExtraArtifactMap();
    assertThat(extraArtifactsMap).isEmpty();
  }

  @Test
  public void testBuildActionListener() throws Exception {
    write(
        "nobuild/BUILD",
        """
        extra_action(
            name = "action",
            cmd = "",
        )

        action_listener(
            name = "listener",
            extra_actions = [":action"],
            mnemonics = ["Foo"],
        )
        """);
    buildTarget("//nobuild:listener");
    // Confirm target exists.
    getExistingConfiguredTarget("//nobuild:listener");
  }

  @Test
  public void testNotActionListenerLabel() throws Exception {
    write(
        "nobuild/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "javalib1",
            srcs = [],
        )

        java_library(
            name = "javalib2",
            srcs = [],
        )
        """);
    addOptions("--experimental_action_listener=//nobuild:javalib1");
    try {
      buildTarget("//nobuild:javalib2");
      Assert.fail("expected failure");
    } catch (ViewCreationFailedException expected) {
      assertThat(expected)
          .hasMessageThat()
          .contains(
              String.format("Analysis of target '%s' failed; build aborted", "//nobuild:javalib2"));
    }
  }

  @Test
  public void testInvalidActionListenerLabel() throws Exception {
    write(
        "nobuild/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "javalib",
            srcs = [],
        )
        """);
    addOptions("--experimental_action_listener='this is \\not\\ a valid label'");
    OptionsParsingException expected =
        assertThrows(OptionsParsingException.class, () -> buildTarget("//nobuild:javalib"));
    assertThat(expected)
        .hasMessageThat()
        .isEqualTo(
            String.format(
                "While parsing option %s='%s': invalid package name ''%s'': "
                    + "package names may contain "
                    + "A-Z, a-z, 0-9, or any of ' !\"#$%%&'()*+,-./;<=>?[]^_`{|}~' "
                    + "(most 7-bit ascii characters except 0-31, 127, ':', or '\\')",
                "--experimental_action_listener",
                "this is \\not\\ a valid label",
                "this is \\not\\ a valid label"));
  }

  /**
   * Ensure outputs checked for uniqueness.
   */
  @Test
  public void testNonUniqueOutputs() throws Exception {
    write(
        "nobuild/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "javalib",
            srcs = [],
        )

        extra_action(
            name = "baz",
            cmd = "echo $(output test.tst)",
            out_templates = ["test.tst"],
        )

        action_listener(
            name = "bar",
            extra_actions = [":baz"],
            mnemonics = [
                "Javac",
                "JavaSourceJar",
            ],
        )
        """);

    addOptions("--experimental_action_listener=//nobuild:bar");

    try {
      buildTarget("//nobuild:javalib");
      Assert.fail("expected failure");
    } catch (ViewCreationFailedException vcfe) {
      assertThat(vcfe)
          .hasMessageThat()
          .contains(
              "file 'extra_actions/nobuild/baz/nobuild/test.tst' is generated by these conflicting"
                  + " actions:");
    }
  }

  /**
   * Regression test for b/236308456.
   *
   * <p>Actions for {@code :shared1} and {@code :shared2} both produce {@code foo/shared.h}. {@code
   * :mid} propagates a dependency on the header via {@code :shared1_lib}, while {@code :top}
   * depends on the header via {@code :shared2_lib}. This leads to the extra action for {@code :top}
   * discovering two inputs with the same exec path but different owners.
   */
  @Test
  public void extraActionDiscoversBothSharedArtifacts() throws Exception {
    write(
        "foo/defs.bzl",
        """
        def _shared_header_impl(ctx):
            header = ctx.actions.declare_file("shared.h")
            ctx.actions.write(header, "")
            return DefaultInfo(files = depset([header]))

        shared_header = rule(implementation = _shared_header_impl)
        """);
    write(
        "foo/BUILD",
        """
        load(":defs.bzl", "shared_header")

        shared_header(name = "shared1")

        shared_header(name = "shared2")

        cc_library(
            name = "shared1_lib",
            hdrs = [":shared1"],
        )

        cc_library(
            name = "shared2_lib",
            hdrs = [":shared2"],
        )

        cc_library(
            name = "mid",
            hdrs = ["mid.h"],
            deps = [":shared1_lib"],
        )

        # Order of top's deps matters to reproduce the crash.
        cc_library(
            name = "top",
            hdrs = ["top.h"],
            deps = [
                ":mid",
                ":shared2_lib",
            ],
        )

        extra_action(
            name = "extra",
            cmd = "touch $(output $(ACTION_ID).out)",
            out_templates = ["$(ACTION_ID).out"],
        )

        action_listener(
            name = "listener",
            extra_actions = [":extra"],
            mnemonics = ["CppCompileHeader"],
        )
        """);
    write("foo/mid.h", "#include \"foo/shared.h\"");
    write(
        "foo/top.h",
        // A system include (<string>) is necessary to reproduce the crash, since otherwise the
        // shared header would be last in the ActionExecutionFunction#addDiscoveredInputs loop.
        "#include <string>",
        "#include \"foo/mid.h\"",
        "#include \"foo/shared.h\"");
    addOptions(
        "--cc_dotd_files",
        "--features=-use_header_modules",
        "--features=parse_headers",
        "--features=cc_include_scanning",
        "--incompatible_use_cpp_compile_header_mnemonic",
        "--experimental_action_listener=//foo:listener");
    buildTarget("//foo:top");
  }
}
