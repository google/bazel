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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.Scope;
import com.google.devtools.build.lib.analysis.config.Scope.ScopeType;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.skyframe.EvaluationResult;
import java.util.Optional;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link BuildOptionsScopeFunction}. */
@RunWith(JUnit4.class)
public final class BuildOptionsScopeFunctionTest extends BuildViewTestCase {

  @Before
  public void doBeforeEachTest() {
    // inject Precomputed.BASELINE_CONFIGURATION
    AnalysisMock analysisMock = AnalysisMock.get();
    ConfiguredRuleClassProvider ruleClassProvider = analysisMock.createRuleClassProvider();
    ImmutableSortedSet<Class<? extends FragmentOptions>> buildOptionClasses =
        ruleClassProvider.getFragmentRegistry().getOptionsClasses();

    SequencedSkyframeExecutor skyframeExecutor = getSkyframeExecutor();
    BuildOptions defaultBuildOptions =
        BuildOptions.getDefaultBuildOptionsForFragments(buildOptionClasses).clone();
    skyframeExecutor.injectExtraPrecomputedValues(
        new ImmutableList.Builder<PrecomputedValue.Injected>()
            .add(
                PrecomputedValue.injected(
                    PrecomputedValue.BASELINE_CONFIGURATION, defaultBuildOptions))
            .addAll(analysisMock.getPrecomputedValues())
            .build());
  }

  @Test
  @Ignore("TODO(b/359622692): turns this back on in a follow up CL")
  public void buildOptionsScopesFunction_returnsCorrectScope() throws Exception {
    scratch.file(
        "test_flags/build_setting.bzl",
        """
        bool_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.bool(flag = True),
            attrs = {
                "scope": attr.string(default = "universal"),
            },
        )
        """);
    scratch.file(
        "test_flags/BUILD",
        """
        load("//test_flags:build_setting.bzl", "bool_flag")
        bool_flag(
            name = "foo",
            build_setting_default = False,
            scope = "project",
        )
        bool_flag(
            name = "bar",
            build_setting_default = False,
        )
        """);

    scratch.file(
        "test_flags/PROJECT.scl",
        """
        active_directories = {
          "default": [
              "//my_project/"
          ]
        }
        """);
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");
    BuildOptions buildOptions =
        createBuildOptions("--//test_flags:foo=True", "--//test_flags:bar=True");

    // purposely removing the scope for //test_flags:bar to simulate the case where the scope is
    // not yet resolved for a flag.
    BuildOptions inputBuildOptionsWithIncompleteScopeTypeMap =
        buildOptions.toBuilder().removeScope(Label.parseCanonical("//test_flags:bar")).build();

    ImmutableList<Label> scopedFlags = ImmutableList.of(Label.parseCanonical("//test_flags:bar"));
    BuildOptionsScopeValue.Key key =
        BuildOptionsScopeValue.Key.create(inputBuildOptionsWithIncompleteScopeTypeMap, scopedFlags);

    // verify that the scope type is not yet resolved for //test_flags:bar
    assertThat(key.getBuildOptions().getScopeTypeMap()).hasSize(1);

    BuildOptionsScopeValue buildOptionsScopeValue = executeFunction(key);

    // verify that the Scope is fully resolved for //test_flags:foo and //test_flags:bar
    var unused =
        assertThat(
            buildOptionsScopeValue
                .getFullyResolvedScopes()
                .equals(
                    ImmutableMap.of(
                        Label.parseCanonical("//test_flags:foo"),
                        new Scope(
                            Scope.ScopeType.PROJECT,
                            new Scope.ScopeDefinition(ImmutableSet.of("//my_project/"))),
                        Label.parseCanonical("//test_flags:bar"),
                        new Scope(ScopeType.UNIVERSAL, null))));

    // verify that the BuildOptionsScopeValue.getResolvedBuildOptionsWithScopeTypes() has the
    // correct ScopeType map for all flags.
    assertThat(buildOptionsScopeValue.getResolvedBuildOptionsWithScopeTypes().getScopeTypeMap())
        .containsExactly(
            Label.parseCanonical("//test_flags:foo"),
            Scope.ScopeType.PROJECT,
            Label.parseCanonical("//test_flags:bar"),
            Scope.ScopeType.UNIVERSAL);
  }

  @Test
  public void buildOptionsScopesFunction_doesNotErrorOut_whenNoProjectFile() throws Exception {
    scratch.file(
        "test_flags/build_setting.bzl",
        """
        bool_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.bool(flag = True),
            attrs = {
                "scope": attr.string(default = "universal"),
            },
        )
        """);
    scratch.file(
        "test_flags/BUILD",
        """
        load("//test_flags:build_setting.bzl", "bool_flag")
        bool_flag(
            name = "foo",
            build_setting_default = False,
            scope = "project",
        )
        """);

    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");
    BuildOptions buildOptionsWithoutScopes = createBuildOptions("--//test_flags:foo=True");
    ImmutableList<Label> scopedFlags = ImmutableList.of(Label.parseCanonical("//test_flags:foo"));
    BuildOptionsScopeValue.Key key =
        BuildOptionsScopeValue.Key.create(buildOptionsWithoutScopes, scopedFlags);

    BuildOptionsScopeValue buildOptionsScopeValue = executeFunction(key);
    var unused =
        assertThat(
            buildOptionsScopeValue
                .getFullyResolvedScopes()
                .equals(
                    ImmutableMap.of(
                        Label.parseCanonical("//test_flags:foo"),
                        new Scope(Scope.ScopeType.PROJECT, null))));
  }

  private BuildOptionsScopeValue executeFunction(BuildOptionsScopeValue.Key key) throws Exception {
    SkyframeExecutor skyframeExecutor = getSkyframeExecutor();
    EvaluationResult<BuildOptionsScopeValue> result =
        SkyframeExecutorTestUtils.evaluate(skyframeExecutor, key, /* keepGoing= */ false, reporter);
    skyframeExecutor.injectExtraPrecomputedValues(
        ImmutableList.of(
            PrecomputedValue.injected(
                RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE, Optional.empty())));
    if (result.hasError()) {
      throw result.getError(key).getException();
    }
    return result.get(key);
  }
}
