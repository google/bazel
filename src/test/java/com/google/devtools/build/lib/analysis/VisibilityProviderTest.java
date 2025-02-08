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

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link VisibilityProvider}. */
@RunWith(JUnit4.class)
public final class VisibilityProviderTest extends BuildViewTestCase {

  @Before
  public void setUp() throws Exception {
    setBuildLanguageOptions(
        // Let's test the case where input files have proper visibilities by default.
        "--incompatible_no_implicit_file_export");

    // NB: BuildViewTestCase sets the default_visibility to public unless we opt out. Since we're
    // only testing the visibility provider, and not doing actual visibility checking, we don't opt
    // out. This helps keep our test cases a little more readable.
  }

  /** Returns the visibility provider of the configured target with the given label. */
  private VisibilityProvider getVisibility(String label) throws Exception {
    ConfiguredTarget target = getConfiguredTarget(label);
    Preconditions.checkNotNull(target, "Configured target for %s was unexpectedly null", label);
    VisibilityProvider provider = target.getProvider(VisibilityProvider.class);
    Preconditions.checkNotNull(provider, "Visibility provider for %s was unexpectedly null", label);
    return provider;
  }

  /**
   * Returns a list of packages identified by the given visibility provider, as reported by {@link
   * PackageGroupContents#packageStrings} (formatted with the double slash).
   */
  private static List<String> getVisibilityStrings(VisibilityProvider provider) {
    return provider.getVisibility().toList().stream()
        .flatMap(pgc -> pgc.packageStrings(/* includeDoubleSlash= */ true).stream())
        .collect(toImmutableList());
  }

  /**
   * Creates definition of {@code //rules:simple_rule.bzl%simple_rule}, a rule that has a label
   * attribute {@code dep} and implicit output {@code <NAME>.bin}.
   */
  private void defineSimpleRule() throws Exception {
    scratch.file("rules/BUILD");
    scratch.file(
        "rules/simple_rule.bzl",
        """
        def _impl(ctx):
            ctx.actions.write(ctx.outputs.out, "")

        simple_rule = rule(
            implementation = _impl,
            attrs = {"dep": attr.label(mandatory=False, allow_files=True)},
            outputs = {"out": "%{name}.bin"},
        )
        """);
  }

  @Test
  public void providerValueForTargetsInBuildFile() throws Exception {
    defineSimpleRule();
    scratch.file(
        "pkg/BUILD",
        """
        load("//rules:simple_rule.bzl", "simple_rule")

        package(default_visibility=["//default:__pkg__"])

        simple_rule(
            name = "rule_target",
            dep = "implicit_input.cc",
            visibility = ["//client:__pkg__"],
        )

        package_group(
            name = "pkg_group",
        )

        exports_files(["explicit_input.txt"])
        exports_files(["explicit_input_with_vis.txt"], visibility=["//client:__pkg__"])
        """);

    VisibilityProvider ruleTargetVisibility = getVisibility("//pkg:rule_target");
    // The declaration location //pkg is not appended to visibility, but the visibility check will
    // treat it as if it were there. Same below.
    assertThat(getVisibilityStrings(ruleTargetVisibility)).containsExactly("//client");
    assertThat(ruleTargetVisibility.isCreatedInSymbolicMacro()).isFalse();

    VisibilityProvider pkgGroupVisibility = getVisibility("//pkg:pkg_group");
    assertThat(getVisibilityStrings(pkgGroupVisibility)).containsExactly("public");
    assertThat(pkgGroupVisibility.isCreatedInSymbolicMacro()).isFalse();

    VisibilityProvider explicitInputVisibility = getVisibility("//pkg:explicit_input.txt");
    assertThat(getVisibilityStrings(explicitInputVisibility)).containsExactly("public");
    assertThat(explicitInputVisibility.isCreatedInSymbolicMacro()).isFalse();

    VisibilityProvider explicitInputWithVisVisibility =
        getVisibility("//pkg:explicit_input_with_vis.txt");
    assertThat(getVisibilityStrings(explicitInputWithVisVisibility)).containsExactly("//client");
    assertThat(explicitInputWithVisVisibility.isCreatedInSymbolicMacro()).isFalse();

    VisibilityProvider implicitInputVisibility = getVisibility("//pkg:implicit_input.cc");
    // Private (not public, not default_visibility), due to --incompatible_no_implicit_file_export.
    assertThat(getVisibilityStrings(implicitInputVisibility)).isEmpty();
    assertThat(implicitInputVisibility.isCreatedInSymbolicMacro()).isFalse();

    VisibilityProvider outputVisibility = getVisibility("//pkg:rule_target.bin");
    assertThat(getVisibilityStrings(outputVisibility)).containsExactly("//client");
    assertThat(outputVisibility.isCreatedInSymbolicMacro()).isFalse();
  }

  @Test
  public void providerValueForTargetsInMacro() throws Exception {
    defineSimpleRule();
    scratch.file("lib/BUILD");
    scratch.file(
        "lib/macro.bzl",
        """
        load("//rules:simple_rule.bzl", "simple_rule")

        def _impl(name, visibility):
            simple_rule(
                name = name + "_rule_target",
                # No implicit input file, because they can only be created outside a symbolic
                # macro, and anyway that would be redundant with the above test case.
                visibility = ["//client:__pkg__"],
            )
            native.package_group(
                name = name + "_pkg_group",
            )
            native.exports_files([name + "_explicit_input.txt"])
            native.exports_files(
                [name + "_explicit_input_with_vis.txt"],
                visibility=["//client:__pkg__"])

        my_macro = macro(implementation = _impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load("//lib:macro.bzl", "my_macro")

        package(default_visibility=["//default:__pkg__"])

        my_macro(name = "foo")
        """);

    VisibilityProvider ruleTargetVisibility = getVisibility("//pkg:foo_rule_target");
    // The declaration location //lib comes from the visibility attribute (after it has been
    // processed in RuleFactory). Same below.
    assertThat(getVisibilityStrings(ruleTargetVisibility)).containsExactly("//client", "//lib");
    assertThat(ruleTargetVisibility.isCreatedInSymbolicMacro()).isTrue();

    VisibilityProvider pkgGroupVisibility = getVisibility("//pkg:foo_pkg_group");
    assertThat(getVisibilityStrings(pkgGroupVisibility)).containsExactly("public");
    // This is actually incorrect, but we don't care because package groups are always public.
    // (Storing the correct value would require a bool, so we don't bother.)
    assertThat(pkgGroupVisibility.isCreatedInSymbolicMacro()).isFalse();

    VisibilityProvider explicitInputVisibility = getVisibility("//pkg:foo_explicit_input.txt");
    assertThat(getVisibilityStrings(explicitInputVisibility)).containsExactly("public");
    assertThat(explicitInputVisibility.isCreatedInSymbolicMacro()).isTrue();

    VisibilityProvider explicitInputWithVisVisibility =
        getVisibility("//pkg:foo_explicit_input_with_vis.txt");
    assertThat(getVisibilityStrings(explicitInputWithVisVisibility))
        .containsExactly("//client", "//lib");
    assertThat(explicitInputWithVisVisibility.isCreatedInSymbolicMacro()).isTrue();

    VisibilityProvider outputVisibility = getVisibility("//pkg:foo_rule_target.bin");
    assertThat(getVisibilityStrings(outputVisibility)).containsExactly("//client", "//lib");
    assertThat(outputVisibility.isCreatedInSymbolicMacro()).isTrue();
  }

  @Test
  public void providerValueForAlias() throws Exception {
    // Check the provider of an alias target declared in a BUILD file referencing an actual target
    // in a macro, and vice versa.
    defineSimpleRule();
    scratch.file(
        // Put the .bzl in //pkg so we don't have to declare //pkg:__pkg__ in visibility.
        "pkg/macro.bzl",
        """
        load("//rules:simple_rule.bzl", "simple_rule")

        def _impl(name, visibility):
            simple_rule(
                name = name + "_actual",
                visibility = ["//actual_client:__pkg__"])
            native.alias(
                name = name + "_alias",
                actual = "//pkg:actual",
                visibility = ["//alias_client:__pkg__"],
            )

        my_macro = macro(implementation = _impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load("//rules:simple_rule.bzl", "simple_rule")
        load("//pkg:macro.bzl", "my_macro")

        my_macro(name = "foo")

        simple_rule(
            name = "actual",
            visibility = ["//actual_client:__pkg__"],
        )

        alias(
            name = "alias",
            actual = ":foo_actual",
            visibility = ["//alias_client:__pkg__"],
        )
        """);

    VisibilityProvider buildFileAliasVisibility = getVisibility("//pkg:alias");
    assertThat(getVisibilityStrings(buildFileAliasVisibility)).containsExactly("//alias_client");
    assertThat(buildFileAliasVisibility.isCreatedInSymbolicMacro()).isFalse();

    VisibilityProvider macroAliasVisibility = getVisibility("//pkg:foo_alias");
    assertThat(getVisibilityStrings(macroAliasVisibility))
        .containsExactly("//alias_client", "//pkg");
    assertThat(macroAliasVisibility.isCreatedInSymbolicMacro()).isTrue();
  }
}
