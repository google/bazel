// Copyright 2024 The Bazel Authors. All rights reserved.
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.test.AnalysisFailure;
import com.google.devtools.build.lib.analysis.test.AnalysisFailureInfo;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.MacroInstance;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.Types;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests the execution of symbolic macro implementations. */
@RunWith(TestParameterInjector.class)
public final class SymbolicMacroTest extends BuildViewTestCase {

  /**
   * Returns a package by the given name (no leading "//"), or null upon {@link
   * NoSuchPackageException}.
   */
  @CanIgnoreReturnValue
  @Nullable
  private Package getPackage(String pkgName) throws InterruptedException {
    try {
      return getPackageManager().getPackage(reporter, PackageIdentifier.createInMainRepo(pkgName));
    } catch (NoSuchPackageException unused) {
      return null;
    }
  }

  private void assertPackageNotInError(@Nullable Package pkg) {
    assertThat(pkg).isNotNull();
    assertThat(pkg.containsErrors()).isFalse();
  }

  /** Retrieves the macro with the given id, which must exist in the package. */
  private static MacroInstance getMacroById(Package pkg, String id) {
    MacroInstance macro = pkg.getMacrosById().get(id);
    assertThat(macro).isNotNull();
    return macro;
  }

  /** Maps a list of labels to a more convenient list of strings. */
  private static ImmutableList<String> asStringList(List<Label> labelList) {
    return labelList.stream().map(Label::getCanonicalForm).collect(toImmutableList());
  }

  /**
   * Retrieves the visibility labels of the target with the given name, which must exist in the
   * package.
   */
  private static ImmutableList<String> getTargetVisibility(Package pkg, String name)
      throws Exception {
    Target target = pkg.getTarget(name);
    assertThat(target).isNotNull();
    return asStringList(target.getActualVisibility().getDeclaredLabels());
  }

  /**
   * Retrieves the visibility labels of the macro with the given id, which must exist in the
   * package.
   */
  private static ImmutableList<String> getMacroVisibility(Package pkg, String id) throws Exception {
    return asStringList(getMacroById(pkg, id).getActualVisibility());
  }

  /**
   * Convenience method for asserting that a package evaluates in error and produces an event
   * containing the given substring.
   *
   * <p>Note that this is not suitable for errors that occur during top-level .bzl evaluation (i.e.,
   * triggered by load() rather than during BUILD evaluation), since our test framework fails to
   * produce a result in that case (b/26382502).
   */
  private void assertGetPackageFailsWithEvent(String pkgName, String msg) throws Exception {
    reporter.removeHandler(failFastHandler);
    Package pkg = getPackage(pkgName);
    assertThat(pkg).isNotNull();
    assertThat(pkg.containsErrors()).isTrue();
    assertContainsEvent(msg);
  }

  /**
   * Convenience method for asserting that a package evaluates without error, but that the given
   * target cannot be configured due to violating macro naming rules.
   */
  private void assertPackageLoadsButGetConfiguredTargetFailsMacroNamingCheck(
      String pkgName, String macroName, String targetName) throws Exception {
    Package pkg = getPackage(pkgName);
    assertPackageNotInError(pkg);
    assertThat(pkg.getTargets()).containsKey(targetName);

    String labelString = String.format("//%s:%s", pkgName, targetName);
    AssertionError error =
        Assert.assertThrows(AssertionError.class, () -> getConfiguredTarget(labelString));
    assertThat(error)
        .hasMessageThat()
        .contains(
            String.format(
                "Target %s declared in symbolic macro '%s' violates macro naming rules",
                labelString, macroName));
  }

  @Test
  public void implementationIsInvokedWithNameParam() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility):
            print("my_macro called with name = %s" % name)
        my_macro = macro(implementation=_impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertContainsEvent("called with name = abc");
  }

  @Test
  public void implementationFailsDueToBadSignature() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl():
            pass
        my_macro = macro(implementation=_impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        """);

    assertGetPackageFailsWithEvent(
        "pkg", "_impl() got unexpected keyword arguments: name, visibility");
  }

  @Test
  public void implementationMustNotReturnAValue() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility):
            return True
        my_macro = macro(implementation=_impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        """);

    assertGetPackageFailsWithEvent("pkg", "macro 'abc' may not return a non-None value (got True)");
  }

  /**
   * Writes source files for package with a given name such that there is a macro by the given name
   * declaring a target by the given name.
   */
  private void setupForMacroWithSingleTarget(String pkgName, String macroName, String targetName)
      throws Exception {
    scratch.file(
        String.format("%s/foo.bzl", pkgName),
        String.format(
            """
            def _impl(name, visibility):
                native.cc_library(name="%s")
            my_macro = macro(implementation=_impl)
            """,
            targetName));
    scratch.file(
        String.format("%s/BUILD", pkgName),
        String.format(
            """
            load(":foo.bzl", "my_macro")
            my_macro(name="%s")
            """,
            macroName));
  }

  @Test
  @TestParameters({"{separator: '_'}", "{separator: '-'}", "{separator: '.'}"})
  public void macroTargetName_canBeNamePlusSeparatorPlusSomething(String separator)
      throws Exception {
    String targetName = String.format("abc%slib", separator);
    setupForMacroWithSingleTarget("pkg", "abc", targetName);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertThat(pkg.getTargets()).containsKey(targetName);
    assertThat(getConfiguredTarget(String.format("//pkg:%s", targetName))).isNotNull();
  }

  @Test
  public void macroTargetName_canBeJustNameForMainTarget() throws Exception {
    setupForMacroWithSingleTarget("pkg", "abc", "abc");

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertThat(pkg.getTargets()).containsKey("abc");
    assertThat(getConfiguredTarget("//pkg:abc")).isNotNull();
    assertThat(pkg.getMacrosById()).containsKey("abc:1");
  }

  @Test
  public void macroTargetName_cannotBeNonSuffixOfName() throws Exception {
    setupForMacroWithSingleTarget("pkg", "abc", "xyz");

    assertPackageLoadsButGetConfiguredTargetFailsMacroNamingCheck("pkg", "abc", "xyz");
  }

  @Test
  public void macroTargetName_cannotBeLibPrefixOfName() throws Exception {
    setupForMacroWithSingleTarget("pkg", "abc", "libabc.so");

    assertPackageLoadsButGetConfiguredTargetFailsMacroNamingCheck("pkg", "abc", "libabc.so");
  }

  @Test
  @TestParameters({"{separator: ''}", "{separator: '@'}"})
  public void macroTargetName_cannotBeInvalidSeparatorPlusSomething(String separator)
      throws Exception {
    String targetName = String.format("abc%sxyz", separator);
    setupForMacroWithSingleTarget("pkg", "abc", targetName);

    assertPackageLoadsButGetConfiguredTargetFailsMacroNamingCheck("pkg", "abc", targetName);
  }

  @Test
  @TestParameters({"{separator: '_'}", "{separator: '-'}", "{separator: '.'}"})
  public void macroTargetName_cannotBeNamePlusSeparatorPlusNothing(String separator)
      throws Exception {
    String targetName = "abc" + separator;
    setupForMacroWithSingleTarget("pkg", "abc", targetName);

    assertPackageLoadsButGetConfiguredTargetFailsMacroNamingCheck("pkg", "abc", targetName);
  }

  @Test
  public void illegallyNamedExportsFilesDoNotBreakPackageLoadingButCannotBeConfigured()
      throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility):
            # valid names
            native.exports_files(srcs=["abc_txt"])
            native.exports_files(srcs=["abc-txt"])
            native.exports_files(srcs=["abc.txt"])
            # allowed during package loading, but cannot be configured
            native.exports_files(srcs=["xyz.txt"])
        my_macro = macro(implementation=_impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        """);

    assertPackageLoadsButGetConfiguredTargetFailsMacroNamingCheck("pkg", "abc", "xyz.txt");
    assertThat(getConfiguredTarget("//pkg:abc_txt")).isNotNull();
    assertThat(getConfiguredTarget("//pkg:abc-txt")).isNotNull();
    assertThat(getConfiguredTarget("//pkg:abc.txt")).isNotNull();
  }

  @Test
  public void illegallyNamedOutputsDoNotBreakPackageLoadingButCannotBeConfigured()
      throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _my_rule_impl(ctx):
            ctx.actions.write(ctx.outputs.out1, "")
            ctx.actions.write(ctx.outputs.out2, "")
            ctx.actions.write(ctx.outputs.out3, "")
            ctx.actions.write(ctx.outputs.out4, "")
            return []
        my_rule = rule(
            implementation = _my_rule_impl,
            outputs = {
              # valid names
              "out1": "%{name}_out1",
              "out2": "%{name}-out2",
              "out3": "%{name}.out3",
              # allowed during package loading, but cannot be configured
              "out4": "lib%{name}.so",
            },
        )
        def _my_macro_impl(name, visibility):
            my_rule(name=name)
        my_macro = macro(implementation=_my_macro_impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        """);

    assertPackageLoadsButGetConfiguredTargetFailsMacroNamingCheck("pkg", "abc", "libabc.so");
    assertThat(getConfiguredTarget("//pkg:abc")).isNotNull();
    assertThat(getConfiguredTarget("//pkg:abc_out1")).isNotNull();
    assertThat(getConfiguredTarget("//pkg:abc-out2")).isNotNull();
    assertThat(getConfiguredTarget("//pkg:abc.out3")).isNotNull();
  }

  @Test
  public void illegallyNamedTargetsProvideAnalysisFailureInfo() throws Exception {
    useConfiguration("--allow_analysis_failures=true");
    setupForMacroWithSingleTarget("pkg", "abc", "libabc.so");
    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);

    ConfiguredTarget target = getConfiguredTarget("//pkg:libabc.so");
    AnalysisFailureInfo info =
        (AnalysisFailureInfo) target.get(AnalysisFailureInfo.STARLARK_CONSTRUCTOR.getKey());
    AnalysisFailure failure = info.getCauses().getSet(AnalysisFailure.class).toList().get(0);
    assertThat(failure.getMessage())
        .contains(
            "Target //pkg:libabc.so declared in symbolic macro 'abc' violates macro naming rules"
                + " and cannot be built.");
  }

  @Test
  public void targetOutsideMacroMayInvadeMacroNamespace() throws Exception {
    // Targets outside a macro may have names that would be valid for targets inside the macro.
    // This is not an error so long as no actual target inside the macro clashes on that name.

    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility):
            native.cc_library(name = name + "_inside_macro")
        my_macro = macro(implementation=_impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        cc_library(name = "abc_outside_macro")
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertThat(pkg.getTargets().keySet()).containsAtLeast("abc_inside_macro", "abc_outside_macro");
    assertThat(pkg.getMacrosById()).containsKey("abc:1");
  }

  @Test
  public void targetOutsideMacroMayNotClashWithTargetInsideMacro() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility):
            native.cc_library(name = name + "_target")
        my_macro = macro(implementation=_impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        cc_library(name = "abc_target")
        """);

    assertGetPackageFailsWithEvent(
        "pkg", "cc_library rule 'abc_target' conflicts with existing cc_library rule");
  }

  @Test
  public void macroCanReferToInputFile() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility):
            native.cc_library(
                name = name,
                srcs = [
                    "explicit_input.cc",
                    # This usage does not cause implicit_input.cc to be created since we're inside
                    # a symbolic macro. We force the input's creation by referring to it from bar
                    # in the BUILD file.
                    "implicit_input.cc",
                ],
            )
        my_macro = macro(implementation=_impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        exports_files(["explicit_input.cc"])
        cc_library(name = "bar", srcs = ["implicit_input.cc"])
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertThat(pkg.getTargets()).containsKey("abc");
    assertThat(pkg.getTargets()).containsKey("implicit_input.cc");
    assertThat(pkg.getTargets()).containsKey("explicit_input.cc");
  }

  @Test
  public void macroCannotForceCreationOfImplicitInputFileOnItsOwn() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _sub_impl(name, visibility):
            native.cc_library(
                name = name + "_target",
                srcs = ["implicit_input.cc"],
            )

        my_submacro = macro(implementation=_sub_impl)

        def _impl(name, visibility):
            native.cc_library(
                name = name + "_target",
                srcs = ["implicit_input.cc"],
            )
            my_submacro(name = name + "_submacro")
        my_macro = macro(implementation=_impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        """);

    Package pkg = getPackage("pkg");
    // Confirm that implicit_input.cc is not a target of the package, despite being used inside a
    // symbolic macro (and not by anything at the top level) for both a target and a submacro.
    //
    // It'd be an execution time error to attempt to build the declared rule targets, but the
    // package still loads just fine.
    assertPackageNotInError(pkg);
    assertThat(pkg.getTargets()).containsKey("abc_target");
    assertThat(pkg.getTargets()).containsKey("abc_submacro_target");
    assertThat(pkg.getTargets()).doesNotContainKey("implicit_input.cc");
  }

  @Test
  public void macroCanDeclareSubmacros() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _inner_impl(name, visibility):
            native.cc_library(name = name + "_lib")
        inner_macro = macro(implementation=_inner_impl)
        def _impl(name, visibility):
            inner_macro(name = name + "_inner")
        my_macro = macro(implementation=_impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertThat(pkg.getTargets()).containsKey("abc_inner_lib");
  }

  @Test
  public void submacroNameMustFollowPrefixNamingConvention() throws Exception {
    scratch.file("pkg/BUILD");
    scratch.file(
        "pkg/foo.bzl",
        """
        def _inner_impl(name, visibility):
            pass
        inner_macro = macro(implementation=_inner_impl)
        def _impl(name, visibility, sep):
            inner_macro(name = name + sep + "inner")
        my_macro = macro(implementation=_impl, attrs={"sep": attr.string(configurable=False)})
        """);
    scratch.file(
        "good/BUILD",
        """
        load("//pkg:foo.bzl", "my_macro")
        my_macro(name="abc", sep = "_")  # ok
        my_macro(name="def", sep = "-")  # ok
        my_macro(name="ghi", sep = ".")  # ok
        """);
    scratch.file(
        "bad/BUILD",
        """
        load("//pkg:foo.bzl", "my_macro")
        my_macro(name="jkl", sep = "$")  # bad
        """);

    Package good = getPackage("good");
    assertPackageNotInError(good);
    assertGetPackageFailsWithEvent("bad", "macro 'jkl' cannot declare submacro named 'jkl$inner'");
  }

  @Test
  public void submacroMayHaveSameNameAsAncestorMacros() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _inner_impl(name, visibility):
            native.cc_library(name = name)
        inner_macro = macro(implementation=_inner_impl)

        def _middle_impl(name, visibility):
            inner_macro(name = name)
        middle_macro = macro(implementation=_middle_impl)

        def _outer_impl(name, visibility):
            middle_macro(name = name)
        outer_macro = macro(implementation = _outer_impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "outer_macro")
        outer_macro(name="abc")
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertThat(pkg.getTargets()).containsKey("abc");
    assertThat(pkg.getMacrosById()).containsKey("abc:1");
    assertThat(pkg.getMacrosById()).containsKey("abc:2");
    assertThat(pkg.getMacrosById()).containsKey("abc:3");
  }

  @Test
  public void cannotHaveTwoMainTargets() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility):
            native.cc_library(name = name)
            native.cc_library(name = name)
        my_macro = macro(implementation=_impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        """);

    assertGetPackageFailsWithEvent(
        "pkg", "cc_library rule 'abc' conflicts with existing cc_library rule");
  }

  @Test
  public void cannotHaveTwoMainSubmacros() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _inner_impl(name, visibility):
            pass
        inner_macro = macro(implementation=_inner_impl)

        def _impl(name, visibility):
            inner_macro(name = name)
            inner_macro(name = name)
        my_macro = macro(implementation=_impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        """);

    assertGetPackageFailsWithEvent(
        "pkg", "macro 'abc' conflicts with an existing macro (and was not created by it)");
  }

  @Test
  public void cannotHaveBothMainTargetAndMainSubmacro_submacroDeclaredFirst() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _inner_impl(name, visibility):
            # Don't define a main target; we don't want to trigger a name conflict between this and
            # the outer target.
            pass
        inner_macro = macro(implementation=_inner_impl)

        def _impl(name, visibility):
            inner_macro(name = name)
            native.cc_library(name = name)
        my_macro = macro(implementation=_impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        """);

    assertGetPackageFailsWithEvent(
        "pkg", "target 'abc' conflicts with an existing macro (and was not created by it)");
  }

  @Test
  public void cannotHaveBothMainTargetAndMainSubmacro_targetDeclaredFirst() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _inner_impl(name, visibility):
            # Don't define a main target; we don't want to trigger a name conflict between this and
            # the outer target.
            pass
        inner_macro = macro(implementation=_inner_impl)

        def _impl(name, visibility):
            native.cc_library(name = name)
            inner_macro(name = name)
        my_macro = macro(implementation=_impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        """);

    assertGetPackageFailsWithEvent("pkg", "macro 'abc' conflicts with an existing target");
  }

  /**
   * Implementation of a test that ensures a given API cannot be called from inside a symbolic
   * macro.
   */
  private void doCannotCallApiTest(String apiName, String usageLine) throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        String.format(
            """
            def _impl(name, visibility):
                %s
            my_macro = macro(implementation=_impl)
            """,
            usageLine));
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        """);

    assertGetPackageFailsWithEvent(
        "pkg",
        String.format(
            // The error also has one of the following suffixes:
            //   - " or a symbolic macro"
            //   - ", a symbolic macro, or a WORKSPACE file"
            "%s can only be used while evaluating a BUILD file (or legacy macro)", apiName));
  }

  @Test
  public void macroCannotCallPackage() throws Exception {
    doCannotCallApiTest(
        "package()", "native.package(default_visibility = ['//visibility:public'])");
  }

  @Test
  public void macroCannotCallGlob() throws Exception {
    doCannotCallApiTest("glob()", "native.glob(['foo*'])");
  }

  @Test
  public void macroCannotCallSubpackages() throws Exception {
    doCannotCallApiTest("subpackages()", "native.subpackages(include = ['*'])");
  }

  @Test
  public void macroCannotCallExistingRule() throws Exception {
    doCannotCallApiTest("existing_rule()", "native.existing_rule('foo')");
  }

  @Test
  public void macroCannotCallExistingRules() throws Exception {
    doCannotCallApiTest("existing_rules()", "native.existing_rules()");
  }

  @Test
  public void macroCannotCallEnvironmentRuleFunction() throws Exception {
    doCannotCallApiTest("environment rule", "native.environment(name = 'foo')");
  }

  // There are other symbols that must not be called from within symbolic macros, but we don't test
  // them because they can't be obtained from a symbolic macro implementation anyway, since they are
  // not under `native` (at least, for BUILD-loaded .bzl files) and because symbolic macros can't
  // take arbitrary parameter types from their caller. These untested symbols include:
  //
  //  - For BUILD threads: licenses(), environment_group()
  //  - For WORKSPACE threads: workspace(), register_toolchains(), register_execution_platforms(),
  //    bind(), and repository rules.
  //
  // Starlark-defined repository rules might technically be callable but we skip over that edge
  // case here.

  @Test
  public void existingRules_canSeeTargetsCreatedByOrdinaryMacros() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility):
            native.cc_binary(name = name + "_lib")
        my_macro = macro(implementation=_impl)
        def query():
            print("existing_rules() keys: %s" % native.existing_rules().keys())
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro", "query")
        cc_library(name = "outer_target")
        my_macro(name="abc")
        query()
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertContainsEvent("existing_rules() keys: [\"outer_target\", \"abc_lib\"]");
  }

  @Test
  public void existingRules_cannotSeeTargetsCreatedByFinalizers() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility):
            native.cc_binary(name = name + "_lib")
        my_macro = macro(implementation=_impl, finalizer=True)
        def query():
            print("existing_rules() keys: %s" % native.existing_rules().keys())
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro", "query")
        cc_library(name = "outer_target")
        my_macro(name="abc")
        query()
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertContainsEvent("existing_rules() keys: [\"outer_target\"]");
  }

  @Test
  public void hardcodedDefaultAttrValue_isUsedWhenNotOverriddenAndAttrHasNoUserSpecifiedDefault()
      throws Exception {

    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility, dep_nonconfigurable, dep_configurable, xyz_configurable):
            print("dep_nonconfigurable is %s" % dep_nonconfigurable)
            print("dep_configurable is %s" % dep_configurable)
            print("xyz_configurable is %s" % xyz_configurable)
        my_macro = macro(
            implementation = _impl,
            attrs = {
              # Test label type, since LabelType#getDefaultValue returns null.
              "dep_nonconfigurable": attr.label(configurable=False),
              # Try it again, this time configurable. Select()-promotion doesn't apply to None.
              "dep_configurable": attr.label(),
              # Now do it for a value besides None. Select()-promotion applies.
              "xyz_configurable": attr.string(),
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertContainsEvent("dep_nonconfigurable is None");
    assertContainsEvent("dep_configurable is None");
    assertContainsEvent("xyz_configurable is select({\"//conditions:default\": \"\"})");
  }

  @Test
  public void defaultAttrValue_isUsedWhenNotOverridden() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility, xyz):
            print("xyz is %s" % xyz)
        my_macro = macro(
            implementation = _impl,
            attrs = {
              "xyz": attr.string(default="DEFAULT", configurable=False)
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertContainsEvent("xyz is DEFAULT");
  }

  @Test
  public void defaultAttrValue_canBeOverridden() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility, xyz):
            print("xyz is %s" % xyz)
        my_macro = macro(
            implementation = _impl,
            attrs = {
              "xyz": attr.string(default="DEFAULT", configurable=False)
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            xyz = "OVERRIDDEN",
        )
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertContainsEvent("xyz is OVERRIDDEN");
  }

  @Test
  public void defaultAttrValue_isUsed_whenAttrIsImplicit() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility, _xyz):
            print("xyz is %s" % _xyz)
        my_macro = macro(
            implementation = _impl,
            attrs = {
              "_xyz": attr.string(default="IMPLICIT", configurable=False)
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertContainsEvent("xyz is IMPLICIT");
  }

  @Test
  public void defaultAttrValue_wrappingMacroTakesPrecedenceOverWrappedRule() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _rule_impl(ctx):
            pass

        my_rule = rule(
            implementation = _rule_impl,
            attrs = {"dep": attr.label(default="//common:rule_default")},
        )

        def _macro_impl(name, visibility, dep):
            my_rule(name = name, dep = dep)

        my_macro = macro(
            implementation = _macro_impl,
            attrs = {"dep": attr.label(default="//common:macro_default")},
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    Rule rule = pkg.getRule("abc");
    assertThat(rule).isNotNull();
    assertThat(rule.getAttr("dep"))
        .isEqualTo(Label.parseCanonicalUnchecked("//common:macro_default"));
  }

  @Test
  public void noneAttrValue_doesNotOverrideDefault() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility, xyz):
            print("xyz is %s" % xyz)
        my_macro = macro(
            implementation = _impl,
            attrs = {
              "xyz": attr.string(default="DEFAULT", configurable=False)
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            xyz = None,
        )
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertContainsEvent("xyz is DEFAULT");
  }

  @Test
  public void noneAttrValue_doesNotSatisfyMandatoryRequirement() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility):
            pass
        my_macro = macro(
            implementation = _impl,
            attrs = {
                "xyz": attr.string(mandatory=True),
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            xyz = None,
        )
        """);

    assertGetPackageFailsWithEvent(
        "pkg", "missing value for mandatory attribute 'xyz' in 'my_macro' macro");
  }

  @Test
  public void noneAttrValue_disallowedWhenAttrDoesNotExist() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility):
            pass
        my_macro = macro(
            implementation = _impl,
            attrs = {
                "xzz": attr.string(doc="This attr is public"),
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            xyz = None,
        )
        """);

    assertGetPackageFailsWithEvent(
        "pkg", "no such attribute 'xyz' in 'my_macro' macro (did you mean 'xzz'?)");
  }

  @Test
  public void stringAttrsAreConvertedToLabelsAndInRightContext() throws Exception {
    scratch.file("lib/BUILD");
    scratch.file(
        "lib/foo.bzl",
        """
        def _impl(name, visibility, xyz, _xyz):
            print("xyz is %s" % xyz)
            print("_xyz is %s" % _xyz)
        my_macro = macro(
            implementation = _impl,
            attrs = {
              "xyz": attr.label(configurable = False),
              "_xyz": attr.label(default=":BUILD", configurable=False)
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load("//lib:foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            xyz = ":BUILD",  # Should be parsed relative to //pkg, not //lib
        )
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertContainsEvent("xyz is @@//pkg:BUILD");
    assertContainsEvent("_xyz is @@//lib:BUILD");
  }

  @Test
  public void cannotMutateAttrValues() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility, xyz):
            xyz.append(4)
        my_macro = macro(
            implementation = _impl,
            attrs = {
              "xyz": attr.int_list(configurable=False),
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            xyz = [1, 2, 3],
        )
        """);

    assertGetPackageFailsWithEvent("pkg", "Error in append: trying to mutate a frozen list value");
  }

  @Test
  public void attrsAllowSelectsByDefault() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility, xyz):
            print("xyz is %s" % xyz)
        my_macro = macro(
            implementation = _impl,
            attrs = {
              "xyz": attr.string(),
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            xyz = select({"//some:condition": ":target1", "//some:other_condition": ":target2"}),
        )
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertContainsEvent(
        "xyz is select({Label(\"//some:condition\"): \":target1\","
            + " Label(\"//some:other_condition\"): \":target2\"})");
  }

  @Test
  public void noneAttrValue_canAppearInSelects() throws Exception {
    // None can appear as a value in a select() entry, and its meaning is "in this case, use
    // whatever default would've been chosen if this attribute weren't specified" -- i.e. the same
    // behavior as when None is used as the whole attribute value.
    //
    // The three cases for using a default value are 1) the attribute schema specifies a default, or
    // else 2) the attribute type specifies a hardcoded default that is a valid value for the type
    // (e.g. the empty string for StringType), or 3) the attribute type uses null as its hardcoded
    // default, which we represent in Starlark as None at rule analysis time. We exercise all three
    // cases here.
    scratch.file(
        "pkg/foo.bzl",
        """
def _impl(name, visibility, attr_using_schema_default, attr_using_hardcoded_nonnull_default,
          attr_using_hardcoded_null_default):
    print("attr_using_schema_default is %s" % attr_using_schema_default)
    print("attr_using_hardcoded_nonnull_default is %s"
              % attr_using_hardcoded_nonnull_default)
    print("attr_using_hardcoded_null_default is %s" % attr_using_hardcoded_null_default)
my_macro = macro(
    implementation = _impl,
    attrs = {
      "attr_using_schema_default": attr.string(default="some_default"),
      "attr_using_hardcoded_nonnull_default": attr.string(),
      "attr_using_hardcoded_null_default": attr.label(),
    },
)
""");
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            attr_using_schema_default = select({
                "//common:some_configsetting": None,
                "//conditions:default": None,
            }),
            attr_using_hardcoded_nonnull_default = select({
                "//common:some_configsetting": None,
                "//conditions:default": None,
            }),
            attr_using_hardcoded_null_default = select({
                "//common:some_configsetting": None,
                "//conditions:default": None,
            }),
        )
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    // From the macro implementation's point of view, the select() entries are still None,
    // regardless of how they are represented and transformed internally.
    assertContainsEvent(
        """
attr_using_schema_default is select({Label("//common:some_configsetting"): None, \
Label("//conditions:default"): None})\
""");
    assertContainsEvent(
        """
attr_using_hardcoded_nonnull_default is select({Label("//common:some_configsetting"): None, \
Label("//conditions:default"): None})\
""");
    assertContainsEvent(
        """
attr_using_hardcoded_null_default is select({Label("//common:some_configsetting"): None, \
Label("//conditions:default"): None})\
""");
  }

  @Test
  public void configurableAttrValuesArePromotedToSelects() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility,
                  configurable_xyz, nonconfigurable_xyz, configurable_default_xyz):
            print("configurable_xyz is '%s' (type %s)" %
                (str(configurable_xyz), type(configurable_xyz)))
            print("nonconfigurable_xyz is '%s' (type %s)" %
                    (str(nonconfigurable_xyz), type(nonconfigurable_xyz)))
            print("configurable_default_xyz is '%s' (type %s)" %
                (str(configurable_default_xyz), type(configurable_default_xyz)))

        my_macro = macro(
            implementation = _impl,
            attrs = {
              "configurable_xyz": attr.string(),
              "nonconfigurable_xyz": attr.string(configurable=False),
              "configurable_default_xyz": attr.string(default = "xyz"),
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            configurable_xyz = "configurable",
            nonconfigurable_xyz = "nonconfigurable",
            # configurable_default_xyz not set
        )
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertContainsEvent(
        "configurable_xyz is 'select({\"//conditions:default\": \"configurable\"})' (type select)");
    assertContainsEvent("nonconfigurable_xyz is 'nonconfigurable' (type string)");
    assertContainsEvent(
        "configurable_default_xyz is 'select({\"//conditions:default\": \"xyz\"})' (type select)");
  }

  @Test
  public void nonconfigurableAttrValuesProhibitSelects() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility, xyz):
            print("xyz is %s" % xyz)
        my_macro = macro(
            implementation = _impl,
            attrs = {
              "xyz": attr.string(configurable=False),
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            xyz = select({"//some:condition": ":target1", "//some:other_condition": ":target2"}),
        )
        """);

    assertGetPackageFailsWithEvent("pkg", "attribute \"xyz\" is not configurable");
  }

  // TODO(b/331193690): Prevent selects from being evaluated as bools
  @Test
  public void selectableAttrCanBeEvaluatedAsBool() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility, xyz):
            # Allowed for now when xyz is a select().
            # In the future, we'll ban implicit conversion and only allow
            # if there's an explicit bool(xyz).
            if xyz:
              print("xyz evaluates to True")
            else:
              print("xyz evaluates to False")

        my_macro = macro(
            implementation = _impl,
            attrs = {
              "xyz": attr.string(),
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            xyz = select({"//conditions:default" :"False"}),
        )
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertContainsEvent("xyz evaluates to True");
    assertDoesNotContainEvent("xyz evaluates to False");
  }

  @Test
  public void labelVisitation() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility, **kwargs):
            pass
        my_macro = macro(
            implementation = _impl,
            attrs = {
              "singular": attr.label(configurable=False),
              "list": attr.label_list(configurable=False),
              "not_a_label": attr.string_list(configurable=False),
              "output": attr.output(),  # (always nonconfigurable)
              "configurable": attr.label(),
              "configurable_withdefault": attr.label(default="//common:configurable_withdefault"),
              # These are not passed in below.
              "omitted": attr.label(configurable=False),
              "_implicit_default": attr.label(default="//common:implicit_default"),
              "explicit_default": attr.label(default="//common:explicit_default"),
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            singular = "//A:A",
            list = ["//A:A", "//B:B"], # duplicate with previous attr
            not_a_label = ["qwerty"],
            output = "out.txt",
            configurable = select({
                "//Q:cond1": "//C:C",
                "//Q:cond2": None,
                "//conditions:default": "//D:D",
            }),
            configurable_withdefault = select({"//Q:cond": "//E:E", "//conditions:default": None}),
            visibility = ["//common:my_package_group"],
        )
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    MacroInstance macroInstance = getMacroById(pkg, "abc:1");
    ArrayList<Label> labels = new ArrayList<>();
    macroInstance.visitExplicitAttributeLabels(labels::add);
    // Order is the same as the attribute definition order.
    assertThat(asStringList(labels))
        .containsExactly(
            "//A:A",
            "//A:A", // duplicate not pruned
            "//B:B",
            // `not_a_label` and `output` are skipped
            "//C:C",
            // //Q:cond2 maps to default, which doesn't exist for that attr
            "//D:D",
            "//E:E",
            "//common:configurable_withdefault", // from attr default
            // `omitted` ignored, it has no default
            // `_implicit_default` ignored because it's implicit
            "//common:explicit_default" // from attr default
            // `visibility` ignored, it's a NODEP label list
            )
        .inOrder();
  }

  @Test
  public void macrosThreadVisibilityAttrThroughWithCallsiteLocationAdded() throws Exception {
    // Don't use test machinery's convenience setup that makes everything public by default.
    setPackageOptions("--default_visibility=private");

    // Submacro defines two targets, one exported (visibility = visibility) and one internal
    // (private to the submacro's package).
    scratch.file("lib1/BUILD");
    scratch.file(
        "lib1/macro.bzl",
        """
        def _impl(name, visibility):
            native.cc_library(
                name = name + "_exported",
                visibility = visibility)
            native.cc_library(name = name + "_internal")

        submacro = macro(implementation=_impl)
        """);
    // Outer macro also defines two targets, but in addition calls the submacro twice, as an
    // exported submacro and an internal one. So a total of six targets across three macro
    // instances.
    scratch.file("lib2/BUILD");
    scratch.file(
        "lib2/macro.bzl",
        """
        load("//lib1:macro.bzl", "submacro")

        def _impl(name, visibility):
            native.cc_library(
                name = name + "_exported",
                visibility = visibility)
            native.cc_library(name = name + "_internal")
            submacro(name=name + "_subexported", visibility = visibility)
            submacro(name=name + "_subinternal")


        outer_macro = macro(implementation=_impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load("//lib2:macro.bzl", "outer_macro")
        outer_macro(name="abc")
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);

    // Outer macro visible to BUILD file.
    assertThat(getMacroVisibility(pkg, "abc:1")).containsExactly("//pkg:__pkg__");

    // The outer macro's exported target and the exported submacro are visible to both the BUILD
    // file and the outer macro.
    assertThat(getTargetVisibility(pkg, "abc_exported"))
        .containsExactly("//pkg:__pkg__", "//lib2:__pkg__");
    assertThat(getMacroVisibility(pkg, "abc_subexported:1"))
        .containsExactly("//pkg:__pkg__", "//lib2:__pkg__");

    // The outer macro's internal target and the internal submacro are visible only to the outer
    // macro.
    assertThat(getTargetVisibility(pkg, "abc_internal")).containsExactly("//lib2:__pkg__");
    assertThat(getMacroVisibility(pkg, "abc_subinternal:1")).containsExactly("//lib2:__pkg__");

    // The exported submacro's exported target is visible to everything (exports all the way down).
    assertThat(getTargetVisibility(pkg, "abc_subexported_exported"))
        .containsExactly("//pkg:__pkg__", "//lib2:__pkg__", "//lib1:__pkg__");

    // The internal submacro's exported target is visible to the outer macro and submacro.
    assertThat(getTargetVisibility(pkg, "abc_subinternal_exported"))
        .containsExactly("//lib2:__pkg__", "//lib1:__pkg__");

    // Finally, the internal targets of both the exported and internal submacros are visible only to
    // the submacro.
    assertThat(getTargetVisibility(pkg, "abc_subexported_internal"))
        .containsExactly("//lib1:__pkg__");
    assertThat(getTargetVisibility(pkg, "abc_subinternal_internal"))
        .containsExactly("//lib1:__pkg__");
  }

  @Test
  public void defaultVisibilityAppliesOnlyToTopLevelMacros() throws Exception {
    scratch.file("lib/BUILD");
    scratch.file(
        "lib/macro.bzl",
        """
        def _sub_impl(name, visibility):
            pass

        submacro = macro(implementation=_sub_impl)

        def _impl(name, visibility):
            submacro(name=name + "_sub")

        outer_macro = macro(implementation=_impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load("//lib:macro.bzl", "outer_macro")

        package(default_visibility=["//defaulted:__pkg__"])

        outer_macro(
            name = "macro_with_explicit_vis",
            visibility = ["//explicit:__pkg__"],
        )
        outer_macro(name="macro_without_explicit_vis")
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);

    // Default visibility only applies when visibility is not specified.
    assertThat(getMacroVisibility(pkg, "macro_with_explicit_vis:1"))
        .containsExactly("//explicit:__pkg__", "//pkg:__pkg__");
    // When it does apply, we still append the callsite location.
    assertThat(getMacroVisibility(pkg, "macro_without_explicit_vis:1"))
        .containsExactly("//defaulted:__pkg__", "//pkg:__pkg__");
    // Default visibility never applies inside a symbolic macro (i.e. to submacros).
    assertThat(getMacroVisibility(pkg, "macro_with_explicit_vis_sub:1"))
        .containsExactly("//lib:__pkg__");
    assertThat(getMacroVisibility(pkg, "macro_without_explicit_vis_sub:1"))
        .containsExactly("//lib:__pkg__");
  }

  @Test
  public void wrongKeyTypeInAttrsDict_detected() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility, **kwargs):
            pass

        my_macro = macro(
            implementation = _impl,
            attrs = {123: attr.string()},
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name = "abc")
        """);
    reporter.removeHandler(failFastHandler);
    assertThat(getPackage("pkg")).isNull();
    assertContainsEvent("got dict<int, Attribute> for 'attrs', want dict<string, Attribute|None>");
  }

  @Test
  public void wrongKeyValueInAttrsDict_detected() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility, **kwargs):
            pass

        my_macro = macro(
            implementation = _impl,
            attrs = {"bad attr": None},
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name = "abc")
        """);
    reporter.removeHandler(failFastHandler);
    assertThat(getPackage("pkg")).isNull();
    assertContainsEvent("attribute name `bad attr` is not a valid identifier");
  }

  @Test
  public void wrongValueTypeInAttrsDict_detected() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility, **kwargs):
            pass

        my_macro = macro(
            implementation = _impl,
            attrs = {"bad": 123},
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name = "abc")
        """);
    reporter.removeHandler(failFastHandler);
    assertThat(getPackage("pkg")).isNull();
    assertContainsEvent("got dict<string, int> for 'attrs', want dict<string, Attribute|None>");
  }

  @Test
  public void noneValueInAttrsDict_ignored() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility, **kwargs):
            pass

        my_macro = macro(
            implementation = _impl,
            attrs = {"disabled_attr": None},
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name = "abc")
        """);
    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertThat(getMacroById(pkg, "abc:1").getMacroClass().getAttributes())
        .doesNotContainKey("disabled_attr");
  }

  @Test
  public void inheritAttrs_fromInvalidSource_fails() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _my_macro_impl(name, visibility, **kwargs):
            pass

        my_macro = macro(
            implementation = _my_macro_impl,
            inherit_attrs = "???",
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        """);
    reporter.removeHandler(failFastHandler);
    assertThat(getPackage("pkg")).isNull();
    assertContainsEvent(
        "Invalid 'inherit_attrs' value \"???\"; expected a rule, a macro, or \"common\"");
  }

  @Test
  public void inheritAttrs_withoutKwargsInImplementation_fails() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _my_macro_impl(name, visibility, tags):
            pass

        my_macro = macro(
            implementation = _my_macro_impl,
            inherit_attrs = "common"
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        """);
    reporter.removeHandler(failFastHandler);
    assertThat(getPackage("pkg")).isNull();
    assertContainsEvent(
        "If inherit_attrs is set, implementation function must have a **kwargs parameter");
  }

  @Test
  public void inheritAttrs_fromCommon_withOverrides() throws Exception {
    scratch.file(
        "pkg/my_macro.bzl",
        """
        def _my_macro_impl(name, visibility, **kwargs):
            pass

        my_macro = macro(
            implementation = _my_macro_impl,
            attrs = {
                # add a new attr
                "new_attr": attr.string(),
                # override an inherited attr
                "tags": attr.string_list(default = ["foo"]),
                # remove an inherited attr
                "features": None,
            },
            inherit_attrs = "common",
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "abc")
        """);
    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    // inherited attrs
    assertThat(getMacroById(pkg, "abc:1").getMacroClass().getAttributes().keySet())
        .containsAtLeast("compatible_with", "testonly", "toolchains");
    // overridden attr
    assertThat(
            getMacroById(pkg, "abc:1")
                .getMacroClass()
                .getAttributes()
                .get("tags")
                .getDefaultValueUnchecked())
        .isEqualTo(ImmutableList.of("foo"));
    // non-inherited attr
    assertThat(getMacroById(pkg, "abc:1").getMacroClass().getAttributes())
        .doesNotContainKey("features");
    // internal public attrs which macro machinery must avoid inheriting
    assertThat(getMacroById(pkg, "abc:1").getMacroClass().getAttributes().keySet())
        .containsNoneOf("generator_name", "generator_location", "generator_function");
  }

  @Test
  public void inheritAttrs_fromAnyNativeRule() throws Exception {
    // Ensure that a symbolic macro can inherit attributes from (and thus, can conveniently wrap)
    // any native rule. Native rules may use attribute definitions which are unavailable to Starlark
    // rules, so to verify that we handle the native attribute corner cases, we exhaustively test
    // wrapping of all builtin rule classes which are accessible from Starlark. We do not test rule
    // classes which are exposed to Starlark via macro wrappers in @_builtins, because Starlark code
    // typically cannot get at the wrapped native rule's rule class symbol from which to inherit
    // attributes. We also do not test rule target instantiation (and thus, do not test whether such
    // a target would pass analysis) because declaring arbitrary native rule targets is difficult to
    // automate.
    //
    // This test is expected to fail if:
    // * a native rule adds a mandatory attribute of a type which is not supported by this test's
    //   fakeMandatoryArgs mechanism below (to fix, add support for it to fakeMandatoryArgs); or
    // * a new AttributeValueSource or a new attribute type is introduced, and symbolic macros
    //   cannot inherit an attribute with a default with this source or of such a type (to fix, add
    //   a check for it in MacroClass#forceDefaultToNone).
    for (RuleClass ruleClass : getBuiltinRuleClasses(false)) {
      if (ruleClass.getAttributes().isEmpty()) {
        continue;
      }
      if (!(ruleClass.getRuleClassType().equals(RuleClass.Builder.RuleClassType.NORMAL)
          || ruleClass.getRuleClassType().equals(RuleClass.Builder.RuleClassType.TEST))) {
        continue;
      }
      String pkgName = "pkg_" + ruleClass.getName();
      String macroName = "my_" + ruleClass.getName();
      // Provide fake values for mandatory attributes in macro invocation
      StringBuilder fakeMandatoryArgs = new StringBuilder();
      for (Attribute attr : ruleClass.getAttributes()) {
        String fakeValue = null;
        if (attr.isPublic() && attr.isMandatory() && !attr.getName().equals("name")) {
          Type<?> type = attr.getType();
          if (type.equals(Type.STRING)
              || type.equals(BuildType.OUTPUT)
              || type.equals(BuildType.LABEL)
              || type.equals(BuildType.NODEP_LABEL)
              || type.equals(BuildType.DORMANT_LABEL)
              || type.equals(BuildType.GENQUERY_SCOPE_TYPE)) {
            fakeValue = "\":fake\"";
          } else if (type.equals(Types.STRING_LIST)
              || type.equals(BuildType.OUTPUT_LIST)
              || type.equals(BuildType.LABEL_LIST)
              || type.equals(BuildType.NODEP_LABEL_LIST)
              || type.equals(BuildType.DORMANT_LABEL_LIST)
              || type.equals(BuildType.GENQUERY_SCOPE_TYPE_LIST)) {
            fakeValue = "[\":fake\"]";
          } else if (type.equals(BuildType.LABEL_DICT_UNARY)
              || type.equals(BuildType.LABEL_KEYED_STRING_DICT)) {
            fakeValue = "{\":fake\": \":fake\"}";
          }
        }
        if (fakeValue != null) {
          fakeMandatoryArgs.append(", ").append(attr.getName()).append(" = ").append(fakeValue);
        }
      }

      scratch.file(
          pkgName + "/macro.bzl",
          String.format(
              """
              def _impl(name, visibility, **kwargs):
                  pass

              %s = macro(
                  implementation = _impl,
                  inherit_attrs = native.%s,
              )
              """,
              macroName, ruleClass.getName()));
      scratch.file(
          pkgName + "/BUILD",
          String.format(
              """
              load(":macro.bzl", "%s")
              %s(name = "abc"%s)
              """,
              macroName, macroName, fakeMandatoryArgs));
      Package pkg = getPackage(pkgName);
      assertPackageNotInError(pkg);
      assertWithMessage("rule '%s'", ruleClass.getName())
          .that(getMacroById(pkg, "abc:1").getMacroClass().getAttributes().keySet())
          .containsAtLeastElementsIn(
              ruleClass.getAttributes().stream()
                  .filter(a -> a.isPublic() && a.isDocumented())
                  .map(Attribute::getName)
                  .collect(toImmutableList()));
      assertThat(getMacroById(pkg, "abc:1").getMacroClass().getAttributes().keySet())
          .containsNoneOf("generator_name", "generator_location", "generator_function");
    }
  }

  @Test
  public void inheritAttrs_fromGenrule_producesTargetThatPassesAnalysis() throws Exception {
    // inheritAttrs_fromAnyNativeRule() above is loading-phase only; by contrast, this test verifies
    // that we can wrap a native rule (in this case, genrule) in a macro with inherit_attrs, and
    // that the macro-wrapped rule target passes analysis.
    scratch.file(
        "pkg/my_genrule.bzl",
        """
def _my_genrule_impl(name, visibility, tags, **kwargs):
    print("my_genrule: tags = %s" % tags)
    for k in kwargs:
        print("my_genrule: kwarg %s = %s" % (k, kwargs[k]))
    native.genrule(name = name + "_wrapped_genrule", visibility = visibility, **kwargs)

my_genrule = macro(
    implementation = _my_genrule_impl,
    inherit_attrs = native.genrule,
)
""");
    scratch.file(
        "pkg/BUILD",
        """
        load(":my_genrule.bzl", "my_genrule")
        my_genrule(
            name = "abc",
            outs = ["out.txt"],
            cmd = "touch $@",
        )
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertThat(getConfiguredTarget("//pkg:abc_wrapped_genrule")).isNotNull();
    assertContainsEvent("my_genrule: tags = None"); // Not []
    assertContainsEvent(
        "my_genrule: kwarg srcs = None"); // Not select({"//conditions:default": []})
    assertContainsEvent(
        "my_genrule: kwarg testonly = None"); // Not select({"//conditions:default": False})
  }

  @Test
  public void inheritAttrs_fromExportedStarlarkRule() throws Exception {
    scratch.file(
        "pkg/my_macro.bzl",
        """
        def _my_rule_impl(ctx):
            pass

        _my_rule = rule(
            implementation = _my_rule_impl,
            attrs = {
                "srcs": attr.label_list(),
            },
        )

        def _my_macro_impl(name, visibility, **kwargs):
            _my_rule(name = name + "_my_rule", visibility = visibility, **kwargs)

        my_macro = macro(
            implementation = _my_macro_impl,
            inherit_attrs = _my_rule,
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "abc")
        """);
    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertThat(getMacroById(pkg, "abc:1").getMacroClass().getAttributes().keySet())
        .containsAtLeast("srcs", "tags");
    assertThat(getMacroById(pkg, "abc:1").getMacroClass().getAttributes().keySet())
        .containsNoneOf("generator_name", "generator_location", "generator_function");
  }

  @Test
  public void inheritAttrs_fromUnexportedStarlarkRule_fails() throws Exception {
    scratch.file(
        "pkg/my_macro.bzl",
        """
        def _my_rule_impl(ctx):
            pass

        _unexported = struct(
            rule = rule(
                implementation = _my_rule_impl,
                attrs = {
                    "srcs": attr.label_list(),
                },
            ),
        )

        def _my_macro_impl(name, visibility, **kwargs):
            pass

        my_macro = macro(
            implementation = _my_macro_impl,
            inherit_attrs = _unexported.rule,
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "abc")
        """);
    reporter.removeHandler(failFastHandler);
    assertThat(getPackage("pkg")).isNull();
    assertContainsEvent(
        "a rule or macro callable must be assigned to a global variable in a .bzl file before it"
            + " can be inherited from");
  }

  @Test
  public void inheritAttrs_fromExportedMacro() throws Exception {
    scratch.file(
        "pkg/my_macro.bzl",
        """
def _other_macro_impl(name, visibility, **kwargs):
    pass

_other_macro = macro(
    implementation = _other_macro_impl,
    attrs = {
        "srcs": attr.label_list(),
        "tags": attr.string_list(configurable = False),
    },
)

def _my_macro_impl(name, visibility, tags, **kwargs):
    print("my_macro: tags = %s" % tags)
    for k in kwargs:
        print("my_macro: kwarg %s = %s" % (k, kwargs[k]))
    _other_macro(name = name + "_other_macro", visibility = visibility, tags = tags, **kwargs)

my_macro = macro(
    implementation = _my_macro_impl,
    inherit_attrs = _other_macro,
)
""");
    scratch.file(
        "pkg/BUILD",
        """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "abc")
        """);
    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertThat(getMacroById(pkg, "abc:1").getMacroClass().getAttributes().keySet())
        .containsExactly("name", "visibility", "srcs", "tags");
    assertContainsEvent("my_macro: tags = None"); // Not []
    assertContainsEvent("my_macro: kwarg srcs = None"); // Not select({"//conditions:default": []})
  }

  @Test
  public void inheritAttrs_fromUnexportedMacro_fails() throws Exception {
    scratch.file(
        "pkg/my_macro.bzl",
        """
        def _other_macro_impl(name, visibility, **kwargs):
            pass

        _unexported = struct(
            macro = macro(
                implementation = _other_macro_impl,
                attrs = {
                    "srcs": attr.label_list(),
                },
            ),
        )

        def _my_macro_impl(name, visibility, **kwargs):
            pass

        my_macro = macro(
            implementation = _my_macro_impl,
            inherit_attrs = _unexported.macro,
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "abc")
        """);
    reporter.removeHandler(failFastHandler);
    assertThat(getPackage("pkg")).isNull();
    assertContainsEvent(
        "a rule or macro callable must be assigned to a global variable in a .bzl file before it"
            + " can be inherited from");
  }
}
