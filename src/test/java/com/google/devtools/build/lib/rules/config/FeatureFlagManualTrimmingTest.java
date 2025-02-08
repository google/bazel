// Copyright 2018 The Bazel Authors. All rights reserved.
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
// limitations under the License

package com.google.devtools.build.lib.rules.config;

import static com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptionsView;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleTransitionData;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for manual trimming of feature flags with the transitive_configs attribute. */
@RunWith(JUnit4.class)
public final class FeatureFlagManualTrimmingTest extends BuildViewTestCase {

  @Before
  public void enableManualTrimming() throws Exception {
    enableManualTrimmingAnd();
  }

  private void enableManualTrimmingAnd(String... otherFlags) throws Exception {
    ImmutableList<String> flags = new ImmutableList.Builder<String>()
        .add("--enforce_transitive_configs_for_config_feature_flag")
        .add(otherFlags)
        .build();
    useConfiguration(flags.toArray(new String[0]));
  }

  @Override
  protected ConfiguredRuleClassProvider createRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder =
        new ConfiguredRuleClassProvider.Builder().addRuleDefinition(new FeatureFlagSetterRule());
    TestRuleClassProvider.addStandardRules(builder);
    return builder.build();
  }

  @Before
  public void setUpFlagReadingRule() throws Exception {
    scratch.file(
        "test/read_flags.bzl",
        """
        _FFI = config_common.FeatureFlagInfo

        def _read_flags_impl(ctx):
            result = ""
            for dep in ctx.attr.flags:
                if result:
                    result += "\\n"
                result += str(dep.label) + ":::"
                if dep[_FFI].error == None:
                    result += dep[_FFI].value
                elif ctx.attr.skip_if_error:
                    result += "[unresolvable]"
                else:
                    fail(dep[_FFI].error)
            ctx.actions.write(ctx.outputs.flagdict, result)
            return [DefaultInfo(files = depset([ctx.outputs.flagdict]))]

        read_flags = rule(
            implementation = _read_flags_impl,
            attrs = {
                "flags": attr.label_list(),
                "skip_if_error": attr.bool(default = False),
            },
            outputs = {"flagdict": "%{name}.flags"},
        )
        """);
  }

  @Before
  public void setUpHostTransitionRule() throws Exception {
    scratch.file(
        "test/host_transition.bzl",
        """
        def _host_transition_impl(ctx):
            files = depset(transitive = [src[DefaultInfo].files for src in ctx.attr.srcs])
            return [DefaultInfo(files = files)]

        host_transition = rule(
            implementation = _host_transition_impl,
            attrs = {"srcs": attr.label_list(cfg = "exec")},
        )
        """);
  }

  private ImmutableSortedMap<Label, String> getFlagValuesFromOutputFile(Artifact flagDict) {
    String fileContents =
        ((FileWriteAction) getActionGraph().getGeneratingAction(flagDict)).getFileContents();
    return Splitter.on('\n').withKeyValueSeparator(":::").split(fileContents).entrySet().stream()
        .collect(
            toImmutableSortedMap(
                Ordering.natural(),
                (entry) -> Label.parseCanonicalUnchecked(entry.getKey()),
                Map.Entry::getValue));
  }

  @Test
  public void duplicateTargetsCreatedWithTrimmingDisabled() throws Exception {
    useConfiguration("--noenforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        load(":read_flags.bzl", "read_flags")

        feature_flag_setter(
            name = "left",
            flag_values = {
                ":different_flag": "left",
                ":common_flag": "configured",
            },
            transitive_configs = [":common_flag"],
            deps = [":common"],
        )

        feature_flag_setter(
            name = "right",
            flag_values = {
                ":different_flag": "right",
                ":common_flag": "configured",
            },
            transitive_configs = [":common_flag"],
            deps = [":common"],
        )

        read_flags(
            name = "common",
            flags = [":common_flag"],
            transitive_configs = [":common_flag"],
        )

        config_feature_flag(
            name = "different_flag",
            allowed_values = [
                "default",
                "left",
                "right",
            ],
            default_value = "default",
        )

        config_feature_flag(
            name = "common_flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        """);

    Artifact leftFlags =
        Iterables.getOnlyElement(getFilesToBuild(getConfiguredTarget("//test:left")).toList());
    Artifact rightFlags =
        Iterables.getOnlyElement(getFilesToBuild(getConfiguredTarget("//test:right")).toList());

    assertThat(leftFlags).isNotEqualTo(rightFlags);
  }

  @Test
  public void featureFlagSetAndInTransitiveConfigs_getsSetValue() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":read_flags.bzl", "read_flags")

        feature_flag_setter(
            name = "target",
            flag_values = {
                ":trimmed_flag": "left",
                ":used_flag": "configured",
            },
            transitive_configs = [":used_flag"],
            deps = [":reader"],
        )

        read_flags(
            name = "reader",
            flags = [":used_flag"],
            transitive_configs = [":used_flag"],
        )

        config_feature_flag(
            name = "trimmed_flag",
            allowed_values = [
                "default",
                "left",
                "right",
            ],
            default_value = "default",
        )

        config_feature_flag(
            name = "used_flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        """);

    Artifact targetFlags =
        Iterables.getOnlyElement(getFilesToBuild(getConfiguredTarget("//test:target")).toList());

    Label usedFlag = Label.parseCanonical("//test:used_flag");
    assertThat(getFlagValuesFromOutputFile(targetFlags)).containsEntry(usedFlag, "configured");
  }

  @Test
  public void featureFlagSetButNotInTransitiveConfigs_isTrimmedOutAndCollapsesDuplicates()
      throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":read_flags.bzl", "read_flags")

        feature_flag_setter(
            name = "left",
            flag_values = {
                ":different_flag": "left",
                ":common_flag": "configured",
            },
            transitive_configs = [":common_flag"],
            deps = [":common"],
        )

        feature_flag_setter(
            name = "right",
            flag_values = {
                ":different_flag": "right",
                ":common_flag": "configured",
            },
            transitive_configs = [":common_flag"],
            deps = [":common"],
        )

        read_flags(
            name = "common",
            flags = [":common_flag"],
            transitive_configs = [":common_flag"],
        )

        config_feature_flag(
            name = "different_flag",
            allowed_values = [
                "default",
                "left",
                "right",
            ],
            default_value = "default",
        )

        config_feature_flag(
            name = "common_flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        """);

    Artifact leftFlags =
        Iterables.getOnlyElement(getFilesToBuild(getConfiguredTarget("//test:left")).toList());
    Artifact rightFlags =
        Iterables.getOnlyElement(getFilesToBuild(getConfiguredTarget("//test:right")).toList());

    assertThat(leftFlags).isEqualTo(rightFlags);
    assertThat(leftFlags.getArtifactOwner()).isEqualTo(rightFlags.getArtifactOwner());
  }

  @Test
  public void featureFlagInTransitiveConfigsButNotSet_getsDefaultValue() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":read_flags.bzl", "read_flags")

        feature_flag_setter(
            name = "target",
            flag_values = {
                ":trimmed_flag": "left",
            },
            transitive_configs = [":used_flag"],
            deps = [":reader"],
        )

        read_flags(
            name = "reader",
            flags = [":used_flag"],
            transitive_configs = [":used_flag"],
        )

        config_feature_flag(
            name = "trimmed_flag",
            allowed_values = [
                "default",
                "left",
                "right",
            ],
            default_value = "default",
        )

        config_feature_flag(
            name = "used_flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        """);

    Artifact targetFlags =
        Iterables.getOnlyElement(getFilesToBuild(getConfiguredTarget("//test:target")).toList());

    Label usedFlag = Label.parseCanonical("//test:used_flag");
    assertThat(getFlagValuesFromOutputFile(targetFlags)).containsEntry(usedFlag, "default");
  }

  @Test
  public void featureFlagInTransitiveConfigsButNotInTransitiveClosure_isWastefulButDoesNotError()
      throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":read_flags.bzl", "read_flags")

        feature_flag_setter(
            name = "left",
            flag_values = {
                ":different_flag": "left",
                ":common_flag": "configured",
            },
            transitive_configs = [
                ":different_flag",
                ":common_flag",
            ],
            deps = [":common"],
        )

        feature_flag_setter(
            name = "right",
            flag_values = {
                ":different_flag": "right",
                ":common_flag": "configured",
            },
            transitive_configs = [
                ":different_flag",
                ":common_flag",
            ],
            deps = [":common"],
        )

        read_flags(
            name = "common",
            flags = [":common_flag"],
            transitive_configs = [
                ":different_flag",
                ":common_flag",
            ],
        )

        config_feature_flag(
            name = "different_flag",
            allowed_values = [
                "default",
                "left",
                "right",
            ],
            default_value = "default",
        )

        config_feature_flag(
            name = "common_flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        """);

    Artifact leftFlags =
        Iterables.getOnlyElement(getFilesToBuild(getConfiguredTarget("//test:left")).toList());
    Artifact rightFlags =
        Iterables.getOnlyElement(getFilesToBuild(getConfiguredTarget("//test:right")).toList());

    assertThat(leftFlags).isNotEqualTo(rightFlags);
    assertThat(leftFlags.getArtifactOwner()).isNotEqualTo(rightFlags.getArtifactOwner());
  }

  @Test
  public void emptyTransitiveConfigs_equivalentRegardlessOfFeatureFlags() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":read_flags.bzl", "read_flags")

        feature_flag_setter(
            name = "left",
            flag_values = {
                ":used_flag": "left",
            },
            transitive_configs = [":used_flag"],
            deps = [":reader"],
        )

        feature_flag_setter(
            name = "right",
            flag_values = {
                ":used_flag": "right",
            },
            transitive_configs = [":used_flag"],
            deps = [":reader"],
        )

        read_flags(
            name = "reader",
            transitive_configs = [],
        )

        config_feature_flag(
            name = "used_flag",
            allowed_values = [
                "default",
                "left",
                "right",
            ],
            default_value = "default",
        )
        """);

    Artifact leftFlags =
        Iterables.getOnlyElement(getFilesToBuild(getConfiguredTarget("//test:left")).toList());
    Artifact rightFlags =
        Iterables.getOnlyElement(getFilesToBuild(getConfiguredTarget("//test:right")).toList());
    Artifact directFlags =
        Iterables.getOnlyElement(getFilesToBuild(getConfiguredTarget("//test:reader")).toList());

    assertThat(leftFlags).isEqualTo(rightFlags);
    assertThat(leftFlags).isEqualTo(directFlags);
  }

  @Test
  public void absentTransitiveConfigs_equivalentRegardlessOfFeatureFlags() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":read_flags.bzl", "read_flags")

        feature_flag_setter(
            name = "left",
            flag_values = {
                ":used_flag": "left",
            },
            transitive_configs = [":used_flag"],
            deps = [":reader"],
        )

        feature_flag_setter(
            name = "right",
            flag_values = {
                ":used_flag": "right",
            },
            transitive_configs = [":used_flag"],
            deps = [":reader"],
        )

        read_flags(
            name = "reader",
            # no transitive_configs = equivalent to []
        )

        config_feature_flag(
            name = "used_flag",
            allowed_values = [
                "default",
                "left",
                "right",
            ],
            default_value = "default",
        )
        """);

    Artifact leftFlags =
        Iterables.getOnlyElement(getFilesToBuild(getConfiguredTarget("//test:left")).toList());
    Artifact rightFlags =
        Iterables.getOnlyElement(getFilesToBuild(getConfiguredTarget("//test:right")).toList());
    Artifact directFlags =
        Iterables.getOnlyElement(getFilesToBuild(getConfiguredTarget("//test:reader")).toList());

    assertThat(leftFlags).isEqualTo(rightFlags);
    assertThat(leftFlags).isEqualTo(directFlags);
  }

  @Test
  public void magicLabelInTransitiveConfigs_doesNotError() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":read_flags.bzl", "read_flags")

        feature_flag_setter(
            name = "target",
            flag_values = {
                ":trimmed_flag": "left",
            },
            transitive_configs = ["//command_line_option/fragment:test"],
            deps = [":reader"],
        )

        read_flags(
            name = "reader",
            transitive_configs = ["//command_line_option/fragment:test"],
        )

        config_feature_flag(
            name = "trimmed_flag",
            allowed_values = [
                "default",
                "left",
                "right",
            ],
            default_value = "default",
        )
        """);

    getConfiguredTarget("//test:target");
    assertNoEvents();
  }

  @Test
  public void flagSetBySetterButNotInTransitiveConfigs_canBeUsedByDeps() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":read_flags.bzl", "read_flags")

        feature_flag_setter(
            name = "target",
            flag_values = {
                ":not_actually_trimmed_flag": "left",
            },
            transitive_configs = [],
            deps = [":reader"],
        )

        read_flags(
            name = "reader",
            flags = [":not_actually_trimmed_flag"],
            transitive_configs = [":not_actually_trimmed_flag"],
        )

        config_feature_flag(
            name = "not_actually_trimmed_flag",
            allowed_values = [
                "default",
                "left",
                "right",
            ],
            default_value = "default",
        )
        """);

    getConfiguredTarget("//test:target");
    assertNoEvents();
  }

  @Test
  public void featureFlagInUnusedSelectBranchButNotInTransitiveConfigs_doesNotError()
      throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":read_flags.bzl", "read_flags")

        feature_flag_setter(
            name = "target",
            flag_values = {
                ":trimmed_flag": "left",
            },
            transitive_configs = [":used_flag"],
            deps = [":reader"],
        )

        read_flags(
            name = "reader",
            flags = select({
                ":used_flag@other": [":trimmed_flag"],
                "//conditions:default": [],
            }),
            transitive_configs = [":used_flag"],
        )

        config_setting(
            name = "used_flag@other",
            flag_values = {":used_flag": "other"},
            transitive_configs = [":used_flag"],
        )

        config_feature_flag(
            name = "trimmed_flag",
            allowed_values = [
                "default",
                "left",
                "right",
            ],
            default_value = "default",
        )

        config_feature_flag(
            name = "used_flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        """);

    getConfiguredTarget("//test:target");
    assertNoEvents();
  }

  @Test
  public void featureFlagTarget_isTrimmedToOnlyItself() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":read_flags.bzl", "read_flags")

        feature_flag_setter(
            name = "target",
            exports_flag = ":read_flag",
            flag_values = {
                ":trimmed_flag": "left",
                ":read_flag": "configured",
            },
            transitive_configs = [
                ":trimmed_flag",
                ":read_flag",
            ],
        )

        config_feature_flag(
            name = "trimmed_flag",
            allowed_values = [
                "default",
                "left",
                "right",
            ],
            default_value = "default",
        )

        config_feature_flag(
            name = "read_flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        """);

    ConfiguredTarget target = getConfiguredTarget("//test:target");
    RuleContext ruleContext = getRuleContext(target);
    BuildConfigurationValue childConfiguration =
        Iterables.getOnlyElement(ruleContext.getPrerequisiteConfiguredTargets("exports_flag"))
            .getConfiguration();

    Label childLabel = Label.parseCanonicalUnchecked("//test:read_flag");
    assertThat(childConfiguration.getOptions().getStarlarkOptions().keySet())
        .containsExactly(childLabel);
  }

  @Test
  public void featureFlagReferencedByPathWithMissingLabel_producesNoImmediateError()
      throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":read_flags.bzl", "read_flags")

        feature_flag_setter(
            name = "target",
            flag_values = {
                ":used_flag": "configured",
            },
            transitive_configs = [":used_flag"],
            deps = [":broken"],
        )

        filegroup(
            name = "broken",
            srcs = [":reader"],
            transitive_configs = [],
        )

        read_flags(
            name = "reader",
            flags = [":used_flag"],
            skip_if_error = True,
            transitive_configs = [":used_flag"],
        )

        config_feature_flag(
            name = "used_flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        """);

    getConfiguredTarget("//test:target");
    assertNoEvents();
  }

  @Test
  public void featureFlagAccessedByPathWithMissingLabel_producesImmediateError() throws Exception {
    reporter.removeHandler(failFastHandler); // expecting an error
    scratch.file(
        "test/BUILD",
        """
        load(":read_flags.bzl", "read_flags")

        feature_flag_setter(
            name = "target",
            flag_values = {
                ":used_flag": "configured",
            },
            transitive_configs = [":used_flag"],
            deps = [":broken"],
        )

        filegroup(
            name = "broken",
            srcs = [":reader"],
            transitive_configs = [],
        )

        read_flags(
            name = "reader",
            flags = [":used_flag"],
            skip_if_error = False,
            transitive_configs = [":used_flag"],
        )

        config_feature_flag(
            name = "used_flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        """);

    assertThat(getConfiguredTarget("//test:target")).isNull();
    assertContainsEvent(
        "Feature flag //test:used_flag was accessed in a configuration it is not present in. All "
            + "targets which depend on //test:used_flag directly or indirectly must name it in "
            + "their transitive_configs attribute.");
  }

  @Test
  public void featureFlagAccessedByPathWithMissingLabelAndSelect_producesError() throws Exception {
    reporter.removeHandler(failFastHandler); // expecting an error
    scratch.file(
        "test/BUILD",
        """
        feature_flag_setter(
            name = "target",
            flag_values = {
                ":used_flag": "configured",
            },
            transitive_configs = [":used_flag"],
            deps = [":broken"],
        )

        filegroup(
            name = "broken",
            srcs = [":reader"],
            transitive_configs = [],
        )

        filegroup(
            name = "reader",
            srcs = select({
                ":used_flag@configured": ["a.txt"],
                "//conditions:default": ["b.txt"],
            }),
            transitive_configs = [":used_flag"],
        )

        config_setting(
            name = "used_flag@configured",
            flag_values = {":used_flag": "configured"},
            transitive_configs = [":used_flag"],
        )

        config_feature_flag(
            name = "used_flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        """);

    assertThat(getConfiguredTarget("//test:target")).isNull();
    assertContainsEvent(
        "Feature flag //test:used_flag was accessed in a configuration it is not present in. All "
            + "targets which depend on //test:used_flag directly or indirectly must name it in "
            + "their transitive_configs attribute.");
  }

  @Test
  public void featureFlagAccessedByPathWithMissingTransitiveConfigs_producesError()
      throws Exception {
    reporter.removeHandler(failFastHandler); // expecting an error
    scratch.file(
        "test/BUILD",
        """
        load(":read_flags.bzl", "read_flags")

        feature_flag_setter(
            name = "target",
            flag_values = {
                ":used_flag": "configured",
            },
            transitive_configs = [":used_flag"],
            deps = [":broken"],
        )

        filegroup(
            name = "broken",
            srcs = [":reader"],
            # no transitive_configs = equivalent to []
        )

        filegroup(
            name = "reader",
            srcs = select({
                ":used_flag@configured": ["a.txt"],
                "//conditions:default": ["b.txt"],
            }),
            transitive_configs = [":used_flag"],
        )

        config_setting(
            name = "used_flag@configured",
            flag_values = {":used_flag": "configured"},
            transitive_configs = [":used_flag"],
        )

        config_feature_flag(
            name = "used_flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        """);

    assertThat(getConfiguredTarget("//test:target")).isNull();
    assertContainsEvent(
        "Feature flag //test:used_flag was accessed in a configuration it is not present in. All "
            + "targets which depend on //test:used_flag directly or indirectly must name it in "
            + "their transitive_configs attribute.");
  }

  @Test
  public void featureFlagInExecConfiguration_hasDefaultValue() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":host_transition.bzl", "host_transition")
        load(":read_flags.bzl", "read_flags")

        feature_flag_setter(
            name = "target",
            flag_values = {
                ":used_flag": "configured",
            },
            transitive_configs = [":used_flag"],
            deps = [":host"],
        )

        host_transition(
            name = "host",
            srcs = [":reader"],
            transitive_configs = [":used_flag"],
        )

        read_flags(
            name = "reader",
            flags = [":used_flag"],
            transitive_configs = [":used_flag"],
        )

        config_feature_flag(
            name = "used_flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        """);

    Artifact targetFlags =
        Iterables.getOnlyElement(getFilesToBuild(getConfiguredTarget("//test:target")).toList());

    Label usedFlag = Label.parseCanonical("//test:used_flag");
    assertThat(getFlagValuesFromOutputFile(targetFlags)).containsEntry(usedFlag, "default");
  }

  @Test
  public void featureFlagInExecConfiguration_hasNoTransitiveConfigEnforcement() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":host_transition.bzl", "host_transition")
        load(":read_flags.bzl", "read_flags")

        feature_flag_setter(
            name = "target",
            flag_values = {
                ":used_flag": "configured",
            },
            deps = [":host"],
            # no transitive_configs
        )

        host_transition(
            name = "host",
            srcs = [":reader"],
            # no transitive_configs
        )

        read_flags(
            name = "reader",
            flags = [":used_flag"],
            # no transitive_configs
        )

        config_feature_flag(
            name = "used_flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        """);

    getConfiguredTarget("//test:target");
    assertNoEvents();
  }

  @Test
  public void featureFlagAccessedDirectly_returnsDefaultValue() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        config_feature_flag(
            name = "used_flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        """);

    assertThat(
            ConfigFeatureFlagProvider.fromTarget(getConfiguredTarget("//test:used_flag"))
                .getFlagValue())
        .isEqualTo("default");
  }

  @Test
  public void featureFlagAccessedViaTopLevelLibraryTarget_returnsDefaultValue() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":read_flags.bzl", "read_flags")

        read_flags(
            name = "reader",
            flags = [":used_flag"],
            transitive_configs = [":used_flag"],
        )

        config_feature_flag(
            name = "used_flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        """);
    Artifact targetFlags =
        Iterables.getOnlyElement(getFilesToBuild(getConfiguredTarget("//test:reader")).toList());

    Label usedFlag = Label.parseCanonical("//test:used_flag");
    assertThat(getFlagValuesFromOutputFile(targetFlags)).containsEntry(usedFlag, "default");
  }

  @Test
  public void featureFlagSettingRules_overrideFlagsFromReverseTransitiveClosure() throws Exception {
    // In other words: if you have a dependency which sets feature flags itself, you don't need to
    // name any of the feature flags used by that target or its transitive closure, as it sets
    // feature flags itself.
    // This is because the feature flag setting transition (which calls replaceFlagValues) runs
    // before the trimming transition and completely replaces the feature flag set. Thus, when
    // the trimming transition (which calls trimFlagValues) runs, its requests are always satisfied.

    scratch.file(
        "test/BUILD",
        """
        load(":read_flags.bzl", "read_flags")

        filegroup(
            name = "toplevel",
            srcs = [":target"],
            # no transitive_configs
        )

        feature_flag_setter(
            name = "target",
            flag_values = {
                ":trimmed_flag": "left",
                ":used_flag": "configured",
            },
            transitive_configs = [":used_flag"],
            deps = [":reader"],
        )

        read_flags(
            name = "reader",
            flags = [":used_flag"],
            transitive_configs = [":used_flag"],
        )

        config_feature_flag(
            name = "trimmed_flag",
            allowed_values = [
                "default",
                "left",
                "right",
            ],
            default_value = "default",
        )

        config_feature_flag(
            name = "used_flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        """);

    Artifact targetFlags =
        Iterables.getOnlyElement(getFilesToBuild(getConfiguredTarget("//test:toplevel")).toList());

    Label usedFlag = Label.parseCanonical("//test:used_flag");
    assertThat(getFlagValuesFromOutputFile(targetFlags)).containsEntry(usedFlag, "configured");
  }

  @Test
  public void trimmingTransitionReturnsOriginalOptionsWhenNothingIsTrimmed() throws Exception {
    // This is a performance regression test. The trimming transition applies over every configured
    // target in a build. Since BuildOptions.hashCode is expensive, if that produced a unique
    // BuildOptions instance for every configured target
    scratch.file(
        "test/BUILD",
        """
        load(":read_flags.bzl", "read_flags")

        feature_flag_setter(
            name = "toplevel_target",
            flag_values = {
                ":used_flag": "configured",
            },
            transitive_configs = [":used_flag"],
            deps = [":dep"],
        )

        read_flags(
            name = "dep",
            flags = [":used_flag"],
            transitive_configs = [":used_flag"],
        )

        config_feature_flag(
            name = "used_flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        """);

    BuildOptions topLevelOptions =
        getConfiguration(getConfiguredTarget("//test:toplevel_target")).getOptions();
    PatchTransition transition =
        new ConfigFeatureFlagTaggedTrimmingTransitionFactory(BaseRuleClasses.TAGGED_TRIMMING_ATTR)
            .create(RuleTransitionData.create((Rule) getTarget("//test:dep"), null, ""));
    BuildOptions depOptions =
        transition.patch(
            new BuildOptionsView(topLevelOptions, transition.requiresOptionFragments()),
            eventCollector);
    assertThat(depOptions).isSameInstanceAs(topLevelOptions);
  }

  @Test
  public void featureFlagSetAndInTransitiveConfigs_getsSetValueWhenTrimTest() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":read_flags.bzl", "read_flags")

        feature_flag_setter(
            name = "target",
            flag_values = {
                ":trimmed_flag": "left",
                ":used_flag": "configured",
            },
            transitive_configs = [":used_flag"],
            deps = [":reader"],
        )

        read_flags(
            name = "reader",
            flags = [":used_flag"],
            transitive_configs = [":used_flag"],
        )

        config_feature_flag(
            name = "trimmed_flag",
            allowed_values = [
                "default",
                "left",
                "right",
            ],
            default_value = "default",
        )

        config_feature_flag(
            name = "used_flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        """);
    enableManualTrimmingAnd("--trim_test_configuration");

    Artifact targetFlags =
        Iterables.getOnlyElement(getFilesToBuild(getConfiguredTarget("//test:target")).toList());

    Label usedFlag = Label.parseCanonical("//test:used_flag");
    assertThat(getFlagValuesFromOutputFile(targetFlags)).containsEntry(usedFlag, "configured");
  }
}
