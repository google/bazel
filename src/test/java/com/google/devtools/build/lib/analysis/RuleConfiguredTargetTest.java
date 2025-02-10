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
package com.google.devtools.build.lib.analysis;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.rules.python.PythonTestUtils.getPyLoad;
import static com.google.devtools.build.lib.skyframe.serialization.SerializationRegistrySetupHelpers.createAnalysisCodecRegistrySupplier;
import static com.google.devtools.build.lib.skyframe.serialization.SerializationRegistrySetupHelpers.makeReferenceConstants;
import static com.google.devtools.build.lib.skyframe.serialization.testutils.Dumper.dumpStructureWithEquivalenceReduction;
import static com.google.devtools.build.lib.skyframe.serialization.testutils.RoundTripping.roundTripWithSkyframe;

import com.google.common.collect.ImmutableClassToInstanceMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.ArtifactSerializationContext;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.analysis.util.DummyTestFragment;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.skyframe.PrerequisitePackageFunction;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecs;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.vfs.Root;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * A test for rule ConfiguredTargets.
 */
@RunWith(JUnit4.class)
public final class RuleConfiguredTargetTest extends BuildViewTestCase {

  private ConfiguredTarget configure(String ruleLabel) throws Exception {
    return getConfiguredTarget(ruleLabel);
  }

  @Override
  protected ConfiguredRuleClassProvider createRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder.addConfigurationFragment(DummyTestFragment.class);
    return builder
        .addRuleDefinition(new TestRuleClassProvider.LiarRuleWithNativeProvider())
        .addRuleDefinition(new TestRuleClassProvider.LiarRuleWithStarlarkProvider())
        .build();
  }

  @Test
  public void smokeNonexistentFailure() throws Exception {
    scratch.file("a/BUILD", "");
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//a:a");
    assertContainsEvent("target 'a' not declared in package 'a'");
  }

  @Test
  public void testFeatureEnabledOnCommandLine() throws Exception {
    useConfiguration("--features=feature");
    scratch.file("a/BUILD",
        "cc_library(name = 'a')");
    ImmutableSet<String> features = getRuleContext(configure("//a")).getFeatures();
    assertThat(features).contains("feature");
    assertThat(features).doesNotContain("other");
  }

  @Test
  public void testTargetIgnoresHostFeatures() throws Exception {
    useConfiguration("--features=feature", "--host_features=host_feature");
    scratch.file("a/BUILD", "cc_library(name = 'a')");
    ImmutableSet<String> features = getRuleContext(configure("//a")).getFeatures();
    assertThat(features).contains("feature");
    assertThat(features).doesNotContain("host_feature");
  }

  @Test
  public void testHostFeatures() throws Exception {
    useConfiguration("--features=feature", "--host_features=host_feature");
    scratch.file("a/BUILD", "cc_library(name = 'a')");
    ImmutableSet<String> features =
        getRuleContext(getConfiguredTarget("//a", getExecConfiguration())).getFeatures();
    assertThat(features).contains("host_feature");
    assertThat(features).doesNotContain("feature");
  }
  @Test
  public void testFeatureDisabledOnCommandLine() throws Exception {
    useConfiguration("--features=-feature");
    scratch.file("a/BUILD", "cc_library(name = 'a')");
    ImmutableSet<String> disabledFeatures = getRuleContext(configure("//a")).getDisabledFeatures();
    assertThat(disabledFeatures).contains("feature");
    assertThat(disabledFeatures).doesNotContain("other");
  }

  @Test
  public void testFeatureEnabledInPackage() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        package(features = ["feature"])

        cc_library(name = "a")
        """);
    ImmutableSet<String> features = getRuleContext(configure("//a")).getFeatures();
    assertThat(features).contains("feature");
    assertThat(features).doesNotContain("other");
  }

  @Test
  public void testFeatureDisableddInPackage() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        package(features = ["-feature"])

        cc_library(name = "a")
        """);
    ImmutableSet<String> disabledFeatures = getRuleContext(configure("//a")).getDisabledFeatures();
    assertThat(disabledFeatures).contains("feature");
    assertThat(disabledFeatures).doesNotContain("other");
  }

  @Test
  public void testFeatureEnabledInRule() throws Exception {
    scratch.file("a/BUILD",
        "cc_library(name = 'a', features = ['feature'])");
    ImmutableSet<String> features = getRuleContext(configure("//a")).getFeatures();
    assertThat(features).contains("feature");
    assertThat(features).doesNotContain("other");
  }

  @Test
  public void testFeatureDisabledInRule() throws Exception {
    scratch.file("a/BUILD", "cc_library(name = 'a', features = ['-feature'])");
    ImmutableSet<String> disabledFeatures = getRuleContext(configure("//a")).getDisabledFeatures();
    assertThat(disabledFeatures).contains("feature");
    assertThat(disabledFeatures).doesNotContain("other");
  }

  @Test
  public void testFeaturesInPackageOverrideFeaturesFromCommandLine() throws Exception {
    useConfiguration("--features=feature");
    scratch.file(
        "a/BUILD",
        """
        package(features = ["-feature"])

        cc_library(name = "a")
        """);
    RuleContext ruleContext = getRuleContext(configure("//a"));
    ImmutableSet<String> features = ruleContext.getFeatures();
    ImmutableSet<String> disabledFeatures = ruleContext.getDisabledFeatures();
    assertThat(features).doesNotContain("feature");
    assertThat(disabledFeatures).contains("feature");
  }

  @Test
  public void testFeaturesInRuleOverrideFeaturesFromCommandLine() throws Exception {
    useConfiguration("--features=feature");
    scratch.file("a/BUILD", "cc_library(name = 'a', features = ['-feature'])");
    RuleContext ruleContext = getRuleContext(configure("//a"));
    ImmutableSet<String> features = ruleContext.getFeatures();
    ImmutableSet<String> disabledFeatures = ruleContext.getDisabledFeatures();
    assertThat(features).doesNotContain("feature");
    assertThat(disabledFeatures).contains("feature");
  }

  @Test
  public void testFeaturesInRuleOverrideFeaturesFromPackage() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        package(features = [
            "a",
            "-b",
            "c",
        ])

        cc_library(
            name = "a",
            features = [
                "b",
                "-c",
                "d",
            ],
        )
        """);
    RuleContext ruleContext = getRuleContext(configure("//a"));
    ImmutableSet<String> features = ruleContext.getFeatures();
    ImmutableSet<String> disabledFeatures = ruleContext.getDisabledFeatures();
    assertThat(features).containsAtLeast("a", "b", "d");
    assertThat(disabledFeatures).contains("c");
  }

  @Test
  public void testFeaturesDisabledFromCommandLineOverrideAll() throws Exception {
    useConfiguration("--features=-package_feature", "--features=-rule_feature");
    scratch.file(
        "a/BUILD",
        """
        package(features = ["package_feature"])

        cc_library(
            name = "a",
            features = ["rule_feature"],
        )
        """);
    RuleContext ruleContext = getRuleContext(configure("//a"));
    ImmutableSet<String> features = ruleContext.getFeatures();
    ImmutableSet<String> disabledFeatures = ruleContext.getDisabledFeatures();
    assertThat(features).doesNotContain("package_feature");
    assertThat(features).doesNotContain("rule_feature");
    assertThat(disabledFeatures).contains("package_feature");
    assertThat(disabledFeatures).contains("rule_feature");
  }

  @Test
  public void testExperimentalDependenciesOnThirdPartyExperimentalAllowed() throws Exception {
    scratch.file(
        "third_party/experimental/p1/BUILD",
        """
        licenses(["unencumbered"])

        exports_files(["p1.cc"])

        cc_library(name = "p1")
        """);
    scratch.file(
        "experimental/p2/BUILD",
        """
        exports_files(["p2.cc"])

        cc_library(
            name = "p2",
            deps = ["//third_party/experimental/p1"],
        )
        """);

    getConfiguredTarget("//experimental/p2:p2"); // No errors.
  }

  @Test
  public void testThirdPartyExperimentalDependenciesOnExperimentalAllowed() throws Exception {
    scratch.file(
        "experimental/p1/BUILD",
        """
        exports_files(["p1.cc"])

        cc_library(name = "p1")
        """);
    scratch.file(
        "third_party/experimental/p2/BUILD",
        """
        licenses(["unencumbered"])

        exports_files(["p2.cc"])

        cc_library(
            name = "p2",
            deps = ["//experimental/p1"],
        )
        """);

    getConfiguredTarget("//third_party/experimental/p2:p2"); // No errors.
  }

  @Test
  public void testDependencyOnTestOnlyAllowed() throws Exception {
    scratch.file(
        "testonly/BUILD",
        """
        cc_library(
            name = "testutil",
            testonly = 1,
            srcs = ["testutil.cc"],
        )
        """);

    scratch.file(
        "util/BUILD",
        """
        cc_library(
            name = "util",
            srcs = ["util.cc"],
        )
        """);

    scratch.file(
        "cc/common/BUILD",
        """
        # testonly=1 -> testonly=1
        cc_library(
            name = "lib1",
            testonly = 1,
            srcs = ["foo1.cc"],
            deps = ["//testonly:testutil"],
        )

        # testonly=0 -> testonly=0
        cc_library(
            name = "lib2",
            testonly = 0,
            srcs = ["foo2.cc"],
            deps = ["//util"],
        )

        # testonly=1 -> testonly=0
        cc_library(
            name = "lib3",
            testonly = 1,
            srcs = ["foo3.cc"],
            deps = [":lib2"],
        )
        """);
    getConfiguredTarget("//cc/common:lib1"); // No errors.
    getConfiguredTarget("//cc/common:lib2"); // No errors.
    getConfiguredTarget("//cc/common:lib3"); // No errors.
  }

  @Test
  public void testDependsOnTestOnlyDisallowed() throws Exception {
    scratch.file(
        "testonly/BUILD",
        """
        cc_library(
            name = "testutil",
            testonly = 1,
            srcs = ["testutil.cc"],
        )
        """);
    checkError("cc/error", "cclib",
        // error:
        "non-test target '//cc/error:cclib' depends on testonly target '//testonly:testutil' and "
        + "doesn't have testonly attribute set",
        // build file: testonly=0 -> testonly=1
        "cc_library(name = 'cclib',",
        "           srcs  = ['foo.cc'],",
        "           deps = ['//testonly:testutil'],",
        "           testonly = 0)");
  }

  @Test
  public void testDependsOnTestOnlyOutputFileDisallowed() throws Exception {
    useConfiguration("--incompatible_check_testonly_for_output_files");
    scratch.file(
        "testonly/BUILD",
        """
        genrule(
            name = "testutil",
            testonly = 1,
            srcs = [],
            outs = ["testutil.cc"],
            cmd = "touch testutil.cc",
        )
        """);
    checkError(
        "cc/error",
        "cclib",
        // error:
        "non-test target '//cc/error:cclib' depends on the output file target"
            + " '//testonly:testutil.cc' of a testonly rule //testonly:testutil and doesn't have"
            + " testonly attribute set",
        // build file: testonly=0 -> testonly=1
        "cc_library(name = 'cclib',",
        "           srcs  = ['//testonly:testutil.cc'],",
        "           testonly = 0)");
  }

  @Test
  public void testDependenceOnDeprecatedRule() throws Exception {
    scratch.file("p/BUILD",
                "cc_library(name='p', deps=['//q'])");
    scratch.file("q/BUILD",
                "cc_library(name='q', deprecation='Obsolete!')");

    reporter.removeHandler(failFastHandler); // expect errors
    ConfiguredTarget p = getConfiguredTarget("//p");
    assertThat(view.hasErrors(p)).isFalse();
    assertContainsEvent("target '//p:p' depends on deprecated target '//q:q':"
                        + " Obsolete!");
    assertThat(eventCollector.count()).isEqualTo(1);
  }

  @Test
  public void testDependenceOnDeprecatedRuleEmptyExplanation() throws Exception {
    scratch.file("p/BUILD",
                "cc_library(name='p', deps=['//q'])");
    scratch.file("q/BUILD",
                "cc_library(name='q', deprecation='')");  // explicitly specified; still counts!

    reporter.removeHandler(failFastHandler); // expect errors
    ConfiguredTarget p = getConfiguredTarget("//p");
    assertThat(view.hasErrors(p)).isFalse();
    assertContainsEvent("target '//p:p' depends on deprecated target '//q:q'");
    assertThat(eventCollector.count()).isEqualTo(1);
  }

  @Test
  public void testNoWarningWhenDeprecatedDependsOnDeprecatedRule() throws Exception {
    scratch.file(
        "foo/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name='foo', srcs=['foo.java'], deps=['//bar:bar'])");
    scratch.file(
        "bar/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name='bar', srcs=['bar.java'], deps=['//baz:baz'], deprecation='BAR')");
    scratch.file(
        "baz/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name='baz', srcs=['baz.java'], deprecation='BAZ')");

    reporter.removeHandler(failFastHandler); // expect errors
    getConfiguredTarget("//foo");
    assertContainsEvent("target '//foo:foo' depends on deprecated "
        + "target '//bar:bar': BAR");
    assertDoesNotContainEvent("target '//bar:bar' depends on deprecated "
        + "target '//baz:baz': BAZ");
    assertThat(eventCollector.count()).isEqualTo(1);
  }

  @Test
  public void testAttributeErrorContainsLocationOfRule() throws Exception {
    Event e =
        checkError(
            "x",
            "x",
            // error:
            getErrorNonExistingTarget("srcs", "java_library", "//x:x", "//x:a.cc"),
            // build file:
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "# blank line",
            "java_library(name = 'x',",
            "           srcs = ['a.cc'])");
    assertThat(e.getLocation().toString()).isEqualTo("/workspace/x/BUILD:3:13");
  }

  @Test
  public void testJavatestsIsTestonly() throws Exception {
    scratch.file(
        "java/x/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name='x', exports=['//javatests/y'])");
    scratch.file(
        "javatests/y/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name='y')");
    reporter.removeHandler(failFastHandler); // expect warning
    ConfiguredTarget target = getConfiguredTarget("//java/x");
    assertContainsEvent("non-test target '//java/x:x' depends on testonly target"
        + " '//javatests/y:y' and doesn't have testonly attribute set");
    assertThat(view.hasErrors(target)).isTrue();
  }

  @Test
  public void testDependenceOfJavaProductionCodeOnTestPackageGroups() throws Exception {
    scratch.file(
        "java/banana/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "banana",
            visibility = ["//javatests/plantain:chips"],
        )
        """);
    scratch.file(
        "javatests/plantain/BUILD",
        """
        package_group(
            name = "chips",
            packages = ["//javatests/plantain"],
        )
        """);

    getConfiguredTarget("//java/banana");
    assertNoEvents();
  }

  @Test
  public void testUnexpectedSourceFileInDeps() throws Exception {
    scratch.file("x/y.java", "foo");
    checkError(
        "x",
        "x",
        getErrorMsgMisplacedFiles("deps", "java_library", "//x:x", "//x:y.java"),
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name='x', srcs=['x.java'], deps=['y.java'])");
  }

  @Test
  public void testUnexpectedButExistingSourceFileDependency() throws Exception {
    scratch.file("x/y.java");
    checkError(
        "x",
        "x",
        getErrorMsgMisplacedFiles("deps", "java_library", "//x:x", "//x:y.java"),
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name='x', srcs=['x.java'], deps=['y.java'])");
  }

  @Test
  public void testGetArtifactForImplicitOutput() throws Exception {
    scratch.file(
        "java/x/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_binary')",
        "java_binary(name='x', srcs=['x.java'])");

    ConfiguredTarget javaBinary = getConfiguredTarget("//java/x:x");
    Artifact classJarArtifact = getFileConfiguredTarget("//java/x:x.jar").getArtifact();
    // Checks if the deploy jar is generated
    getFileConfiguredTarget("//java/x:x_deploy.jar").getArtifact();

    assertThat(getOutputGroup(javaBinary, OutputGroupInfo.FILES_TO_COMPILE).toList())
        .containsExactly(classJarArtifact);
  }

  @Test
  public void testSelfEdgeInRule() throws Exception {
    scratch.file("x/BUILD",

        "genrule(name='x', srcs=['x'], outs=['out'], cmd=':')");
    reporter.removeHandler(failFastHandler); // expect errors
    getConfiguredTarget("//x");
    assertContainsSelfEdgeEvent("//x:x");
  }

  @Test
  public void testNegativeShardCount() throws Exception {
    checkError(
        "foo",
        "bar",
        "Must not be negative.",
        "load('//test_defs:foo_test.bzl', 'foo_test')",
        "foo_test(name='bar', srcs=['mockingbird.sh'], shard_count=-1)");
  }

  @Test
  public void testExcessiveShardCount() throws Exception {
    checkError(
        "foo",
        "bar",
        "indicative of poor test organization",
        "load('//test_defs:foo_test.bzl', 'foo_test')",
        "foo_test(name='bar', srcs=['mockingbird.sh'], shard_count=51)");
  }

  @Test
  public void testNonexistingTargetErrorMsg() throws Exception {
    checkError("foo", "foo", getErrorNonExistingTarget(
        "deps", "cc_binary", "//foo:foo", "//foo:nonesuch"),
        "cc_binary(name = 'foo',",
        "srcs = ['foo.cc'],",
        "deps = [':nonesuch'])");
  }

  @Test
  public void testRulesDontProvideRequiredFragmentsByDefault() throws Exception {
    scratch.file(
        "a/BUILD",
        String.format(
            """
            %s
            config_setting(
                name = "config",
                values = {"start_end_lib": "1"},
            )

            py_library(
                name = "pylib",
                srcs = ["pylib.py"],
            )

            cc_library(
                name = "a",
                srcs = ["A.cc"],
                data = [":pylib"],
            )
            """,
            getPyLoad("py_library")));
    assertThat(getConfiguredTarget("//a:a").getProvider(RequiredConfigFragmentsProvider.class))
        .isNull();
    assertThat(getConfiguredTarget("//a:config").getProvider(RequiredConfigFragmentsProvider.class))
        .isNull();
  }

  @Test
  public void findArtifactByOutputLabel_twoOutputsWithSameBasename() throws Exception {
    scratch.file(
        "foo/BUILD", "genrule(name = 'gen', outs = ['sub/out', 'out'], cmd = 'touch $(OUTS)')");
    RuleConfiguredTarget foo = (RuleConfiguredTarget) getConfiguredTarget("//foo:gen");
    assertThat(
            foo.findArtifactByOutputLabel(Label.parseCanonical("//foo:sub/out"))
                .getRepositoryRelativePath()
                .getPathString())
        .isEqualTo("foo/sub/out");
    assertThat(
            foo.findArtifactByOutputLabel(Label.parseCanonical("//foo:out"))
                .getRepositoryRelativePath()
                .getPathString())
        .isEqualTo("foo/out");
  }

  @Test
  public void testNativeRuleAttrSetToNoneFails() throws Exception {
    setBuildLanguageOptions("--incompatible_fail_on_unknown_attributes");
    scratch.file(
        "p/BUILD", //
        "genrule(name = 'genrule', srcs = ['a.java'], outs = ['b'], cmd = '', bat = None)");

    reporter.removeHandler(failFastHandler);
    getTarget("//p:genrule");

    assertContainsEvent("no such attribute 'bat' in 'genrule' rule");
  }

  @Test
  public void testNativeRuleAttrSetToNoneDoesntFails() throws Exception {
    setBuildLanguageOptions("--noincompatible_fail_on_unknown_attributes");
    scratch.file(
        "p/BUILD", //
        "genrule(name = 'genrule', srcs = ['a.java'], outs = ['b'], cmd = '', bat = None)");

    getTarget("//p:genrule");
  }

  @Test
  public void testStarlarkRuleAttrSetToNoneFails() throws Exception {
    setBuildLanguageOptions("--incompatible_fail_on_unknown_attributes");
    scratch.file(
        "p/rule.bzl",
        """
        def _impl(ctx):
            pass

        my_rule = rule(_impl)
        """);
    scratch.file(
        "p/BUILD",
        """
        load(":rule.bzl", "my_rule")

        my_rule(
            name = "my_target",
            bat = None,
        )
        """);

    reporter.removeHandler(failFastHandler);
    getTarget("//p:my_target");

    assertContainsEvent("no such attribute 'bat' in 'my_rule' rule");
  }

  @Test
  public void testStarlarkRuleAttrSetToNoneDoesntFail() throws Exception {
    setBuildLanguageOptions("--noincompatible_fail_on_unknown_attributes");
    scratch.file(
        "p/rule.bzl",
        """
        def _impl(ctx):
            pass

        my_rule = rule(_impl)
        """);
    scratch.file(
        "p/BUILD",
        """
        load(":rule.bzl", "my_rule")

        my_rule(
            name = "my_target",
            bat = None,
        )
        """);

    getTarget("//p:my_target");
  }

  @Test
  public void testNativeRuleNotReturnNativeAdvertisedProviderFail() throws Exception {
    scratch.file(
        "p/BUILD",
        """
        liar_rule_with_native_provider(
            name = "my_target",
          )
        """);

    reporter.removeHandler(failFastHandler);
    var unused = configure("//p:my_target");

    assertContainsEvent(
        "in liar_rule_with_native_provider rule //p:my_target: rule advertised the 'FooProvider'"
            + " provider, but this provider was not among those returned");
  }

  @Test
  public void testNativeRuleNotReturnStarlarkAdvertisedProviderFail() throws Exception {
    scratch.file(
        "p/BUILD",
        """
        liar_rule_with_starlark_provider(
            name = "my_target",
          )
        """);

    reporter.removeHandler(failFastHandler);
    var unused = configure("//p:my_target");

    assertContainsEvent(
        "in liar_rule_with_starlark_provider rule //p:my_target: rule advertised the 'STARLARK_P1'"
            + " provider, but this provider was not among those returned");
  }

  @Test
  public void testCodec() throws Exception {
    scratch.file(
        "foo/BUILD", "genrule(name = 'gen', outs = ['sub/out', 'out'], cmd = 'touch $(OUTS)')");
    var original = (RuleConfiguredTarget) getConfiguredTarget("//foo:gen");

    // TODO: b/364831651 - consider factoring out the ObjectCodecs setup to a common location.
    var deserialized =
        roundTripWithSkyframe(
            new ObjectCodecs(
                createAnalysisCodecRegistrySupplier(
                        getRuleClassProvider(),
                        makeReferenceConstants(
                            directories,
                            getRuleClassProvider(),
                            directories.getWorkspace().getBaseName()))
                    .get(),
                ImmutableClassToInstanceMap.builder()
                    .put(
                        ArtifactSerializationContext.class,
                        getSkyframeExecutor().getSkyframeBuildView().getArtifactFactory()
                            ::getSourceArtifact)
                    .put(RuleClassProvider.class, getRuleClassProvider())
                    // We need a RootCodecDependencies but don't care about the likely roots.
                    .put(Root.RootCodecDependencies.class, new Root.RootCodecDependencies())
                    // This is needed to determine TargetData for a ConfiguredTarget during
                    // serialization.
                    .put(
                        PrerequisitePackageFunction.class,
                        getSkyframeExecutor()::getExistingPackage)
                    .build()),
            FingerprintValueService.createForTesting(),
            key -> {
              try {
                return getSkyframeExecutor().getEvaluator().getExistingValue(key);
              } catch (InterruptedException e) {
                throw new IllegalStateException(e);
              }
            },
            original);
    assertThat(dumpStructureWithEquivalenceReduction(original))
        .isEqualTo(dumpStructureWithEquivalenceReduction(deserialized));
  }
}
