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
package com.google.devtools.build.lib.rules.config;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.RequiresOptions;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.License.LicenseType;
import com.google.devtools.build.lib.packages.RawAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link ConfigSetting}. */
@RunWith(TestParameterInjector.class)
public class ConfigSettingTest extends BuildViewTestCase {

  /** Extra options for this test. */
  public static class DummyTestOptions extends FragmentOptions {
    public DummyTestOptions() {}

    @Option(
        name = "internal_option",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "super secret",
        metadataTags = {OptionMetadataTag.INTERNAL})
    public String internalOption;

    @Option(
        name = "allow_multiple_option",
        defaultValue = "null",
        converter = CommaSeparatedOptionListConverter.class,
        allowMultiple = true,
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP})
    public List<String> allowMultipleOption;
  }

  /** Test fragment. */
  @RequiresOptions(options = {DummyTestOptions.class})
  public static final class DummyTestOptionsFragment extends Fragment {
    private final BuildOptions buildOptions;

    public DummyTestOptionsFragment(BuildOptions buildOptions) {
      this.buildOptions = buildOptions;
    }
    // Getter required to satisfy AutoCodec.
    public BuildOptions getBuildOptions() {
      return buildOptions;
    }
  }

  @Override
  protected ConfiguredRuleClassProvider createRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder.addRuleDefinition(new FeatureFlagSetterRule());
    builder.addConfigurationFragment(DummyTestOptionsFragment.class);
    return builder.build();
  }

  private void writeSimpleExample() throws Exception {
    scratch.file(
        "pkg/BUILD",
        """
        config_setting(
            name = "foo",
            values = {
                "compilation_mode": "dbg",
                "stamp": "1",
            },
        )
        """);
  }

  private ConfigMatchingProvider getConfigMatchingProvider(String label) throws Exception {
    return getConfiguredTarget(label).getProvider(ConfigMatchingProvider.class);
  }

  private boolean forceConvertMatchResult(ConfigMatchingProvider.MatchResult result)
      throws Exception {
    if (result.equals(ConfigMatchingProvider.MatchResult.MATCH)) {
      return true;
    } else if (result.equals(ConfigMatchingProvider.MatchResult.NOMATCH)) {
      return false;
    }
    throw new IllegalStateException("Unexpected MatchResult: " + result);
  }

  private boolean getConfigMatchingProviderResultAsBoolean(String label) throws Exception {
    return forceConvertMatchResult(getConfigMatchingProvider(label).result());
  }

  /** Checks the behavior of {@link ConfigSetting#isUnderToolsPackage}. */
  @Test
  public void isUnderToolsPackage() throws Exception {
    RepositoryName toolsRepo = RepositoryName.create("tools");
    // Subpackage of the tools package.
    assertThat(
            ConfigSetting.isUnderToolsPackage(
                Label.parseCanonicalUnchecked("@tools//tools/subpkg:foo"), toolsRepo))
        .isTrue();
    // The tools package itself.
    assertThat(
            ConfigSetting.isUnderToolsPackage(
                Label.parseCanonicalUnchecked("@tools//tools:foo"), toolsRepo))
        .isTrue();
    // The tools repo, but wrong package.
    assertThat(
            ConfigSetting.isUnderToolsPackage(
                Label.parseCanonicalUnchecked("@tools//nottools:foo"), toolsRepo))
        .isFalse();
    // Not even the tools repo.
    assertThat(
            ConfigSetting.isUnderToolsPackage(
                Label.parseCanonicalUnchecked("@nottools//nottools:foo"), toolsRepo))
        .isFalse();
    // A tools package but in the wrong repo.
    assertThat(
            ConfigSetting.isUnderToolsPackage(
                Label.parseCanonicalUnchecked("@nottools//tools:foo"), toolsRepo))
        .isFalse();
  }

  /**
   * Tests that a config_setting only matches build configurations where *all* of
   * its flag specifications match.
   */
  @Test
  public void matchingCriteria() throws Exception {
    writeSimpleExample();

    // First flag mismatches:
    useConfiguration("-c", "opt", "--stamp");
    assertThat(getConfigMatchingProviderResultAsBoolean("//pkg:foo")).isFalse();

    // Second flag mismatches:
    useConfiguration("-c", "dbg", "--nostamp");
    assertThat(getConfigMatchingProviderResultAsBoolean("//pkg:foo")).isFalse();

    // Both flags mismatch:
    useConfiguration("-c", "opt", "--nostamp");
    assertThat(getConfigMatchingProviderResultAsBoolean("//pkg:foo")).isFalse();

    // Both flags match:
    useConfiguration("-c", "dbg", "--stamp");
    assertThat(getConfigMatchingProviderResultAsBoolean("//pkg:foo")).isTrue();
  }

  /**
   * Tests that {@link ConfigMatchingProvider#label} is correct.
   */
  @Test
  public void labelGetter() throws Exception {
    writeSimpleExample();
    assertThat(getConfigMatchingProvider("//pkg:foo").label())
        .isEqualTo(Label.parseCanonical("//pkg:foo"));
  }

  /**
   * Tests that rule analysis fails on unknown options.
   */
  @Test
  public void unknownOption() throws Exception {
    checkError("foo", "badoption",
        "unknown option: 'not_an_option'",
        "config_setting(",
        "    name = 'badoption',",
        "    values = {'not_an_option': 'bar'})");
  }

  /**
   * Tests that rule analysis fails on internal options.
   */
  @Test
  public void internalOption() throws Exception {
    checkError("foo", "badoption",
        "unknown option: 'internal_option'",
        "config_setting(",
        "    name = 'badoption',",
        "    values = {'internal_option': 'bar'})");
  }

  /**
   * Tests that rule analysis fails on invalid option values.
   */
  @Test
  public void invalidOptionValue() throws Exception {
    checkError("foo", "badvalue",
        "Not a valid compilation mode: 'baz'",
        "config_setting(",
        "    name = 'badvalue',",
        "    values = {'compilation_mode': 'baz'})");
  }

  /**
   * Tests that when the first option is valid but the config_setting doesn't match,
   * remaining options are still validity-checked.
   */
  @Test
  public void invalidOptionFartherDown() throws Exception {
    checkError("foo", "badoption",
        "unknown option: 'not_an_option'",
        "config_setting(",
        "    name = 'badoption',",
        "    values = {",
        "        'compilation_mode': 'opt',",
        "        'not_an_option': 'bar',",
        "    })");
  }

  /** Tests that None is not specifiable for a key's value. */
  @Test
  public void noneValueInSetting() throws Exception {
    checkError(
        "foo",
        "none",
        "ERROR /workspace/foo/BUILD:1:15: //foo:none: "
            + "expected value of type 'string' for dict value element, but got None (NoneType)",
        "config_setting(",
        "    name = 'none',",
        "    values = {\"none_value\": None})");
  }

  /**
   * Tests that *some* settings (values or flag_values) must be specified.
   */
  @Test
  public void emptySettings() throws Exception {
    checkError(
        "foo",
        "empty",
        "in config_setting rule //foo:empty: "
            + "Either values, flag_values or constraint_values must be specified and non-empty",
        "config_setting(",
        "    name = 'empty',",
        "    values = {})");
  }

  /**
   * Tests matching on multi-value attributes with key=value entries (e.g. --define).
   */
  @Test
  public void multiValueDict() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            values = {
                "define": "foo=bar",
            },
        )
        """);

    useConfiguration("");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("--define", "foo=bar");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
    useConfiguration("--define", "foo=baz");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("--define", "foo=bar", "--define", "bar=baz");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
    useConfiguration("--define", "foo=bar", "--define", "bar=baz", "--define", "foo=nope");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("--define", "foo=nope", "--define", "bar=baz", "--define", "foo=bar");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
  }

  @Test
  public void invalidDefineProducesError() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            values = {
                "define": "foo",  # Value should be "foo=<something>".
            },
        )
        """);

    checkError(
        "//test:match", "Variable definitions must be in the form of a 'name=value' assignment");
  }

  @Test
  public void multipleDefines() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            define_values = {
                "foo1": "bar",
                "foo2": "baz",
            },
        )
        """);

    useConfiguration("");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("--define", "foo1=bar");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("--define", "foo2=baz");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("--define", "foo1=bar", "--define", "foo2=baz");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
  }

  /**
   * Tests that for a multi-value dictionary, <code>values = { 'key': 'value' }</code> always refers
   * to a single map entry. Fancy syntax like <code>values = { 'key': 'value=1,key2=value2' }</code>
   * doesn't get around that.
   *
   * <p>This just verifies existing behavior, not explicitly desired behavior. We could enhance
   * options parsing to support multi-value settings if anyone ever wanted that.
   */
  @Test
  public void multiValueDictSettingAlwaysSingleEntry() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            values = {
                "define": "foo=bar,baz=bat",
            },
        )
        """);

    useConfiguration("");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("--define", "foo=bar");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("--define", "foo=bar", "--define", "baz=bat");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("--define", "foo=bar,baz=bat");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
    useConfiguration("--define", "makethis=a_superset", "--define", "foo=bar,baz=bat");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
  }

  @Test
  public void definesCrossAttributes() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            define_values = {
                "b": "d",
            },
            values = {
                "define": "a=c",
            },
        )
        """);

    useConfiguration("");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("--define", "a=c");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("--define", "b=d");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("--define", "a=c", "--define", "b=d");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
  }

  /**
   * Tests matching on multi-value attributes against single expected values: the actual list must
   * contain the expected value.
   */
  @Test
  public void multiValueListSingleExpectedValue() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            values = {
                "copt": "-Dfoo",
            },
        )
        """);

    useConfiguration("");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("--copt", "-Dfoo");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
    useConfiguration("--copt", "-Dbar");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("--copt", "-Dfoo", "--copt", "-Dbar");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
    useConfiguration("--copt", "-Dbar", "--copt", "-Dfoo");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
  }

  /**
   * Tests matching on multi-value flags against multiple expected values: the actual list must
   * contain all expected values (and possibly more).
   *
   * <p>This only works for flags that can parse multiple values in the same entry. Not all flags do
   * this: this varies according to each flag's definition. For example "--copt=a,b" produces a
   * single entry ["a,b"], while "--extra_platforms=a,b" produces ["a", "b"].
   */
  @Test
  @TestParameters({
    "{flags: [''], matchExpected: false}", // No flag set
    "{flags: ['--allow_multiple_option', 'one'], matchExpected: false}",
    "{flags: ['--allow_multiple_option', 'two'], matchExpected: false}",
    "{flags: ['--allow_multiple_option', 'one', '--allow_multiple_option', 'two'], "
        + "matchExpected: true}",
    "{flags: ['--allow_multiple_option', 'two', '--allow_multiple_option', 'one'], "
        + "matchExpected: true}",
    "{flags: ['--allow_multiple_option', 'one,two'], matchExpected: true}",
    "{flags: ['--allow_multiple_option', 'two,one'], matchExpected: true}",
    "{flags: ['--allow_multiple_option', 'ten', '--allow_multiple_option', 'two', "
        + "'--allow_multiple_option', 'three', '--allow_multiple_option', 'one'], "
        + "matchExpected: true}"
  })
  public void multiValueListMultipleExpectedValues(List<String> flags, boolean matchExpected)
      throws Exception {
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            values = {
                "allow_multiple_option": "one,two",  # This produces ["one", "two"]
            },
        )
        """);

    useConfiguration(flags.toArray(new String[flags.size()]));
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isEqualTo(matchExpected);
  }

  /**
   * Tests multi-value flags that don't support multiple values <b></b>in the same instance<b>. See
   * comments on {@link #multiValueListMultipleExpectedValues()} for details.
   */
  @Test
  public void multiValueListSingleValueThatLooksLikeMultiple() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            values = {
                "copt": "one,two",  # This produces ["one,two"]
            },
        )
        """);

    useConfiguration("");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("--copt", "one");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("--copt", "two");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("--copt", "one", "--copt", "two");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("--copt", "one,two", "--copt", "one");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
    useConfiguration("--copt", "two,one", "--copt", "one");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
  }

  @Test
  public void selectForDefaultGrteTop() throws Exception {
    scratchConfiguredTarget(
        "a",
        "a",
        "config_setting(name='cs', values={'grte_top': 'default'})",
        "filegroup(name='a', srcs=select({':cs': []}))");
  }

  @Test
  public void requiredConfigFragmentMatcher() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            values = {
                "copt": "-Dfoo",
                "javacopt": "-Dbar",
            },
        )
        """);

    Rule target = (Rule) getTarget("//test:match");
    assertThat(target.getRuleClassObject().getOptionReferenceFunction().apply(target))
        .containsExactly("copt", "javacopt");
  }

  @Test
  public void matchesIfFlagValuesAndValuesBothMatch() throws Exception {
    useConfiguration("--copt=-Dright", "--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
            values = {
                "copt": "-Dright",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = ["right"],
            default_value = "right",
        )
        """);
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
  }

  @Test
  public void matchesIfFlagValuesMatchAndValuesAreEmpty() throws Exception {
    useConfiguration("--copt=-Dright", "--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
            values = {},
        )

        config_feature_flag(
            name = "flag",
            allowed_values = ["right"],
            default_value = "right",
        )
        """);
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
  }

  @Test
  public void matchesIfValuesMatchAndFlagValuesAreEmpty() throws Exception {
    useConfiguration("--copt=-Dright");
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            flag_values = {},
            values = {
                "copt": "-Dright",
            },
        )
        """);
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
  }

  @Test
  public void doesNotMatchIfNeitherFlagValuesNorValuesMatches() throws Exception {
    useConfiguration("--copt=-Dright", "--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            flag_values = {
                ":flag": "wrong",
            },
            transitive_configs = [":flag"],
            values = {
                "copt": "-Dwrong",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )
        """);
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
  }

  @Test
  public void doesNotMatchIfFlagValuesDoNotMatchAndValuesAreEmpty() throws Exception {
    useConfiguration("--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            flag_values = {
                ":flag": "wrong",
            },
            transitive_configs = [":flag"],
            values = {},
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )
        """);
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
  }

  @Test
  public void doesNotMatchIfFlagValuesDoNotMatchButValuesDo() throws Exception {
    useConfiguration("--copt=-Dright", "--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            flag_values = {
                ":flag": "wrong",
            },
            transitive_configs = [":flag"],
            values = {
                "copt": "-Dright",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )
        """);
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
  }

  @Test
  public void doesNotMatchIfValuesDoNotMatchAndFlagValuesAreEmpty() throws Exception {
    useConfiguration("--copt=-Dright");
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            flag_values = {},
            values = {
                "copt": "-Dwrong",
            },
        )
        """);
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
  }

  @Test
  public void doesNotMatchIfValuesDoNotMatchButFlagValuesDo() throws Exception {
    useConfiguration("--copt=-Dright", "--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
            values = {
                "copt": "-Dwrong",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )
        """);
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
  }

  @Test
  public void doesNotMatchIfEvenOneFlagValueDoesNotMatch() throws Exception {
    useConfiguration("--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            flag_values = {
                ":flag": "right",
                ":flag2": "bad",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {},
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        """);
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
  }

  @Test
  public void matchesIfNonDefaultIsSpecifiedAndFlagValueIsThatValue() throws Exception {
    useConfiguration("--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        feature_flag_setter(
            name = "setter",
            exports_setting = ":match",
            flag_values = {":flag": "actual"},
            transitive_configs = [":flag"],
        )

        config_setting(
            name = "match",
            flag_values = {
                ":flag": "actual",
            },
            transitive_configs = [":flag"],
            values = {},
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "default",
                "actual",
            ],
            default_value = "default",
        )
        """);
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:setter")).isTrue();
  }

  @Test
  public void doesNotMatchIfDefaultIsSpecifiedAndFlagValueIsNotDefault() throws Exception {
    useConfiguration("--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        feature_flag_setter(
            name = "setter",
            exports_setting = ":match",
            flag_values = {":flag": "actual"},
            transitive_configs = [":flag"],
        )

        config_setting(
            name = "match",
            flag_values = {
                ":flag": "default",
            },
            transitive_configs = [":flag"],
            values = {},
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "default",
                "actual",
            ],
            default_value = "default",
        )
        """);
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:setter")).isFalse();
  }

  @Test
  public void doesNotRefineSettingWithSameValuesAndSameFlagValuesAndSameConstraintValues()
      throws Exception {
    useConfiguration(
        "--copt=-Dright",
        "--javacopt=-Dgood",
        "--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "copt": "-Dright",
                "javacopt": "-Dgood",
            },
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "copt": "-Dright",
                "javacopt": "-Dgood",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        """);
    assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other")))
        .isFalse();
  }

  @Test
  public void doesNotRefineSettingWithDifferentValuesAndSameFlagValuesAndSameConstraintValues()
      throws Exception {
    useConfiguration(
        "--copt=-Dright",
        "--javacopt=-Dgood",
        "--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "javacopt": "-Dgood",
            },
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "copt": "-Dright",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        """);
    assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other")))
        .isFalse();
  }

  @Test
  public void doesNotRefineSettingWithSameValuesAndSameConstraintValuesAndDifferentFlagValues()
      throws Exception {
    useConfiguration(
        "--copt=-Dright",
        "--javacopt=-Dgood",
        "--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
            values = {
                "copt": "-Dright",
                "javacopt": "-Dgood",
            },
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag2": "good",
            },
            transitive_configs = [":flag2"],
            values = {
                "copt": "-Dright",
                "javacopt": "-Dgood",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        """);
    assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other")))
        .isFalse();
  }

  @Test
  public void
      doesNotRefineSettingWithDifferentValuesAndDifferentFlagValuesAndDifferentConstraintValues()
          throws Exception {
    useConfiguration(
        "--copt=-Dright",
        "--javacopt=-Dgood",
        "--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        constraint_setting(name = "setting_b")

        constraint_value(
            name = "value_b",
            constraint_setting = "setting_b",
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
            values = {
                "copt": "-Dright",
            },
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_b",
            ],
            flag_values = {
                ":flag2": "good",
            },
            transitive_configs = [":flag2"],
            values = {
                "javacopt": "-Dgood",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        """);
    assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other")))
        .isFalse();
  }

  @Test
  public void doesNotRefineSettingWithDifferentValuesAndSubsetFlagValuesAndSubsetConstraintValues()
      throws Exception {
    useConfiguration(
        "--copt=-Dright",
        "--javacopt=-Dgood",
        "--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        constraint_setting(name = "setting_b")

        constraint_value(
            name = "value_b",
            constraint_setting = "setting_b",
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
                ":value_b",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "copt": "-Dright",
            },
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
            values = {
                "javacopt": "-Dgood",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        """);
    assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other")))
        .isFalse();
  }

  @Test
  public void doesNotRefineSettingWithSubsetValuesAndSubsetFlagValuesAndDifferentConstraintValues()
      throws Exception {
    useConfiguration(
        "--copt=-Dright",
        "--javacopt=-Dgood",
        "--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        constraint_setting(name = "setting_b")

        constraint_value(
            name = "value_b",
            constraint_setting = "setting_b",
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "copt": "-Dright",
                "javacopt": "-Dgood",
            },
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_b",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "copt": "-Dright",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        """);
    assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other")))
        .isFalse();
  }

  @Test
  public void doesNotRefineSettingWithSubsetValuesAndSubsetConstraintValuesAndDifferentFlagValues()
      throws Exception {
    useConfiguration(
        "--copt=-Dright",
        "--javacopt=-Dgood",
        "--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        constraint_setting(name = "setting_b")

        constraint_value(
            name = "value_b",
            constraint_setting = "setting_b",
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
                ":value_b",
            ],
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
            values = {
                "copt": "-Dright",
                "javacopt": "-Dgood",
            },
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag2": "good",
            },
            transitive_configs = [":flag2"],
            values = {
                "copt": "-Dright",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        """);
    assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other")))
        .isFalse();
  }

  @Test
  public void doesNotRefineSettingIfThereIsNoOverlap() throws Exception {
    useConfiguration(
        "--copt=-Dright",
        "--javacopt=-Dgood",
        "--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        config_setting(
            name = "configA",
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
        )

        config_setting(
            name = "configB",
            constraint_values = [
                ":value_a",
            ],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )
        """);
    assertThat(
            getConfigMatchingProvider("//test:configA")
                .refines(getConfigMatchingProvider("//test:configB")))
        .isFalse();
    assertThat(
            getConfigMatchingProvider("//test:configB")
                .refines(getConfigMatchingProvider("//test:configA")))
        .isFalse();
  }

  @Test
  public void refinesSettingWithSubsetValuesAndSubsetConstraintValuesAndSameFlagValues()
      throws Exception {
    useConfiguration(
        "--copt=-Dright",
        "--javacopt=-Dgood",
        "--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        constraint_setting(name = "setting_b")

        constraint_value(
            name = "value_b",
            constraint_setting = "setting_b",
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
                ":value_b",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "copt": "-Dright",
                "javacopt": "-Dgood",
            },
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_b",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "copt": "-Dright",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        """);
    assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other")))
        .isTrue();
  }

  @Test
  public void refinesSettingWithSameValuesAndSubsetFlagValuesAndSubsetConstraintValues()
      throws Exception {
    useConfiguration(
        "--copt=-Dright",
        "--javacopt=-Dgood",
        "--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        constraint_setting(name = "setting_b")

        constraint_value(
            name = "value_b",
            constraint_setting = "setting_b",
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
                ":value_b",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "copt": "-Dright",
                "javacopt": "-Dgood",
            },
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
            values = {
                "copt": "-Dright",
                "javacopt": "-Dgood",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        """);
    assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other")))
        .isTrue();
  }

  @Test
  public void refinesSettingWithSubsetValuesAndSubsetFlagValuesAndConstraintValues()
      throws Exception {
    useConfiguration(
        "--copt=-Dright",
        "--javacopt=-Dgood",
        "--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        constraint_setting(name = "setting_b")

        constraint_value(
            name = "value_b",
            constraint_setting = "setting_b",
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
                ":value_b",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "copt": "-Dright",
                "javacopt": "-Dgood",
            },
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
            values = {
                "copt": "-Dright",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        """);
    assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other")))
        .isTrue();
  }

  @Test
  public void refinesSettingWithSubsetConstraintValues() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        constraint_setting(name = "setting_b")

        constraint_value(
            name = "value_b",
            constraint_setting = "setting_b",
        )

        constraint_setting(name = "setting_c")

        constraint_value(
            name = "value_c",
            constraint_setting = "setting_c",
        )

        platform(
            name = "refined_platform",
            constraint_values = [
                ":value_a",
                ":value_b",
                ":value_c",
            ],
        )

        platform(
            name = "other_platform",
            constraint_values = [
                ":value_a",
                ":value_b",
            ],
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
                ":value_b",
                ":value_c",
            ],
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_a",
                ":value_b",
            ],
        )
        """);
    useConfiguration("--platforms=//test:refined_platform");
    assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other")))
        .isTrue();
  }

  @Test
  public void matchesAliasedFlagsInFlagValues() throws Exception {
    useConfiguration("--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "alias_matcher",
            flag_values = {
                ":alias": "right",
            },
            transitive_configs = [":flag"],
        )

        alias(
            name = "alias",
            actual = "flag",
            transitive_configs = [":flag"],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )
        """);
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:alias_matcher")).isTrue();
  }

  @Test
  public void aliasedFlagsAreCountedInRefining() throws Exception {
    useConfiguration("--enforce_transitive_configs_for_config_feature_flag");
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "refined",
            flag_values = {
                ":alias": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
        )

        config_setting(
            name = "other",
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
        )

        alias(
            name = "alias",
            actual = "flag",
            transitive_configs = [":flag"],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        """);
    assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other")))
        .isTrue();
  }

  @Test
  public void referencingSameFlagViaMultipleAliasesFails() throws Exception {
    useConfiguration("--enforce_transitive_configs_for_config_feature_flag");
    checkError(
        "test",
        "multialias",
        "in flag_values attribute of config_setting rule //test:multialias: "
            + "flag '//test:direct' referenced multiple times as ['//test:alias', '//test:direct']",
        "config_setting(",
        "    name = 'multialias',",
        "    flag_values = {",
        "        ':alias': 'right',",
        "        ':direct': 'right',",
        "    },",
        "    transitive_configs = [':direct'],",
        ")",
        "alias(",
        "    name = 'alias',",
        "    actual = 'direct',",
        "    transitive_configs = [':direct'],",
        ")",
        "config_feature_flag(",
        "    name = 'direct',",
        "    allowed_values = ['right', 'wrong'],",
        "    default_value = 'right',",
        ")");
  }

  @Test
  public void requiresValidValueForFlagValues() throws Exception {
    useConfiguration("--enforce_transitive_configs_for_config_feature_flag");
    checkError(
        "test",
        "invalid_flag",
        "in flag_values attribute of config_setting rule //test:invalid_flag: "
            + "error while parsing user-defined configuration values: "
            + "'invalid' is not a valid value for '//test:flag'",
        "config_setting(",
        "    name = 'invalid_flag',",
        "    flag_values = {",
        "        ':flag': 'invalid',",
        "    },",
        "    transitive_configs = [':flag'])",
        "config_feature_flag(",
        "    name = 'flag',",
        "    allowed_values = ['right', 'valid'],",
        "    default_value = 'valid',",
        ")");
  }

  @Test
  public void usesAliasLabelWhenReportingErrorInFlagValues() throws Exception {
    useConfiguration("--enforce_transitive_configs_for_config_feature_flag");
    checkError(
        "test",
        "invalid_flag",
        "in flag_values attribute of config_setting rule //test:invalid_flag: "
            + "error while parsing user-defined configuration values: "
            + "'invalid' is not a valid value for '//test:alias'",
        "config_setting(",
        "    name = 'invalid_flag',",
        "    flag_values = {",
        "        ':alias': 'invalid',",
        "    },",
        "    transitive_configs = [':flag'])",
        "alias(",
        "    name = 'alias',",
        "    actual = ':flag',",
        "    transitive_configs = [':flag'],",
        ")",
        "config_feature_flag(",
        "    name = 'flag',",
        "    allowed_values = ['right', 'valid'],",
        "    default_value = 'valid',",
        ")");
  }

  @Test
  public void buildsettings_matchesFromDefault() throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
        def _impl(ctx):
            return []

        string_flag = rule(implementation = _impl, build_setting = config.string(flag = True))
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")

        config_setting(
            name = "match",
            flag_values = {
                ":cheese": "parmesan",
            },
        )

        string_flag(
            name = "cheese",
            build_setting_default = "parmesan",
        )
        """);
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
  }

  @Test
  public void buildsettings_matchesFromCommandLine() throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
        def _impl(ctx):
            return []

        string_flag = rule(implementation = _impl, build_setting = config.string(flag = True))
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")

        config_setting(
            name = "match",
            flag_values = {
                ":cheese": "gouda",
            },
        )

        string_flag(
            name = "cheese",
            build_setting_default = "parmesan",
        )
        """);

    useConfiguration("--//test:cheese=gouda");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
  }

  /**
   * Regression test to ensure that non-String typed build setting values are being properly
   * converted from Strings to their real type.
   */
  @Test
  public void buildsettings_convertedType() throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
        def _impl(ctx):
            return []

        bool_flag = rule(implementation = _impl, build_setting = config.bool(flag = True))
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "bool_flag")

        config_setting(
            name = "match",
            flag_values = {
                ":cheese": "True",
            },
        )

        bool_flag(
            name = "cheese",
            build_setting_default = True,
        )
        """);
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
  }

  @Test
  public void buildsettings_doesntMatch() throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
        def _impl(ctx):
            return []

        string_flag = rule(implementation = _impl, build_setting = config.string(flag = True))
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")

        config_setting(
            name = "match",
            flag_values = {
                ":cheese": "parmesan",
            },
        )

        string_flag(
            name = "cheese",
            build_setting_default = "parmesan",
        )
        """);

    useConfiguration("--//test:cheese=gouda");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
  }

  @Test
  public void buildsettings_badType() throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
        def _impl(ctx):
            return []

        int_flag = rule(implementation = _impl, build_setting = config.int(flag = True))
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "int_flag")

        config_setting(
            name = "match",
            flag_values = {
                ":wishes": "gouda",
            },
        )

        int_flag(
            name = "wishes",
            build_setting_default = 3,
        )
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:match");
    assertContainsEvent("'gouda' cannot be converted to //test:wishes type int");
  }

  @Test
  public void buildsettings_allowMultipleWorks() throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
        def _impl(ctx):
            return []

        string_flag = rule(
            implementation = _impl,
            build_setting = config.string(flag = True, allow_multiple = True),
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")

        config_setting(
            name = "match",
            flag_values = {
                ":cheese": "pepperjack",
            },
        )

        string_flag(
            name = "cheese",
            build_setting_default = "gouda",
        )
        """);
    useConfiguration("--//test:cheese=pepperjack", "--//test:cheese=brie");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
  }

  @Test
  public void buildsettings_repeatableWorks() throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
        def _impl(ctx):
            return []

        string_list_flag = rule(
            implementation = _impl,
            build_setting = config.string_list(flag = True, repeatable = True),
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_list_flag")

        config_setting(
            name = "match",
            flag_values = {
                ":cheese": "pepperjack",
            },
        )

        string_list_flag(
            name = "cheese",
            build_setting_default = ["gouda"],
        )
        """);

    useConfiguration("--//test:cheese=pepperjack", "--//test:cheese=pepperjack=brie");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
  }

  @Test
  public void buildsettings_repeatableWithoutFlagErrors() throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
        def _impl(ctx):
            return []

        string_list_setting = rule(
            implementation = _impl,
            build_setting = config.string_list(repeatable = True),
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_list_setting")

        string_list_setting(
            name = "cheese",
            build_setting_default = ["gouda"],
        )
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:cheese");
    assertContainsEvent("'repeatable' can only be set for a setting with 'flag = True'");
  }

  @Test
  public void notBuildSettingOrFeatureFlag() throws Exception {
    scratch.file(
        "test/rules.bzl",
        """
        def _impl(ctx):
            return DefaultInfo()

        default_info_rule = rule(implementation = _impl)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "default_info_rule")

        config_setting(
            name = "match",
            flag_values = {
                ":cheese": "gouda",
            },
        )

        default_info_rule(name = "cheese")
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:match");
    assertContainsEvent(
        "flag_values keys must be build settings or feature flags and //test:cheese is not");
  }

  @Test
  public void buildsettingsMatch_featureFlagsMatch() throws Exception {
    useConfiguration("--enforce_transitive_configs_for_config_feature_flag");

    scratch.file(
        "test/build_settings.bzl",
        """
        def _impl(ctx):
            return []

        string_flag = rule(implementation = _impl, build_setting = config.string(flag = True))
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")

        config_setting(
            name = "match",
            flag_values = {
                ":cheese": "parmesan",
                ":flag": "right",
            },
            transitive_configs = [":flag"],
        )

        string_flag(
            name = "cheese",
            build_setting_default = "parmesan",
        )

        config_feature_flag(
            name = "flag",
            allowed_values = ["right"],
            default_value = "right",
        )
        """);
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
  }

  @Test
  public void buildsettingsMatch_featureFlagsDontMatch() throws Exception {
    useConfiguration("--enforce_transitive_configs_for_config_feature_flag");

    scratch.file(
        "test/build_settings.bzl",
        """
        def _impl(ctx):
            return []

        string_flag = rule(implementation = _impl, build_setting = config.string(flag = True))
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")

        config_setting(
            name = "match",
            flag_values = {
                ":cheese": "parmesan",
                ":flag": "wrong",
            },
            transitive_configs = [":flag"],
        )

        string_flag(
            name = "cheese",
            build_setting_default = "parmesan",
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )
        """);
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
  }

  @Test
  public void buildsettingsDontMatch_featureFlagsMatch() throws Exception {
    useConfiguration("--enforce_transitive_configs_for_config_feature_flag");

    scratch.file(
        "test/build_settings.bzl",
        """
        def _impl(ctx):
            return []

        string_flag = rule(implementation = _impl, build_setting = config.string(flag = True))
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")

        config_setting(
            name = "match",
            flag_values = {
                ":cheese": "gouda",
                ":flag": "right",
            },
            transitive_configs = [":flag"],
        )

        string_flag(
            name = "cheese",
            build_setting_default = "parmesan",
        )

        config_feature_flag(
            name = "flag",
            allowed_values = ["right"],
            default_value = "right",
        )
        """);
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
  }

  @Test
  public void constraintValue() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        constraint_setting(name = "notable_building")

        constraint_value(
            name = "empire_state",
            constraint_setting = "notable_building",
        )

        constraint_value(
            name = "space_needle",
            constraint_setting = "notable_building",
        )

        platform(
            name = "new_york_platform",
            constraint_values = [":empire_state"],
        )

        platform(
            name = "seattle_platform",
            constraint_values = [":space_needle"],
        )

        config_setting(
            name = "match",
            constraint_values = [":empire_state"],
        )
        """);

    useConfiguration("--experimental_platforms=//test:new_york_platform");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
    useConfiguration("--experimental_platforms=//test:seattle_platform");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
  }

  @Test
  public void multipleConstraintValues() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        constraint_setting(name = "notable_building")

        constraint_value(
            name = "empire_state",
            constraint_setting = "notable_building",
        )

        constraint_setting(name = "museum")

        constraint_value(
            name = "cloisters",
            constraint_setting = "museum",
        )

        constraint_setting(name = "theme_park")

        constraint_value(
            name = "coney_island",
            constraint_setting = "theme_park",
        )

        platform(
            name = "manhattan_platform",
            constraint_values = [
                ":empire_state",
                ":cloisters",
            ],
        )

        platform(
            name = "museum_platform",
            constraint_values = [":cloisters"],
        )

        platform(
            name = "new_york_platform",
            constraint_values = [
                ":empire_state",
                ":cloisters",
                ":coney_island",
            ],
        )

        config_setting(
            name = "match",
            constraint_values = [
                ":empire_state",
                ":cloisters",
            ],
        )
        """);
    useConfiguration("--experimental_platforms=//test:manhattan_platform");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
    useConfiguration("--experimental_platforms=//test:museum_platform");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("--experimental_platforms=//test:new_york_platform");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
  }

  @Test
  public void definesAndConstraints() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        constraint_setting(name = "notable_building")

        constraint_value(
            name = "empire_state",
            constraint_setting = "notable_building",
        )

        constraint_value(
            name = "space_needle",
            constraint_setting = "notable_building",
        )

        platform(
            name = "new_york_platform",
            constraint_values = [":empire_state"],
        )

        platform(
            name = "seattle_platform",
            constraint_values = [":space_needle"],
        )

        config_setting(
            name = "match",
            constraint_values = [":empire_state"],
            define_values = {
                "b": "d",
            },
            values = {
                "define": "a=c",
            },
        )
        """);

    useConfiguration(
        "--experimental_platforms=//test:new_york_platform", "--define", "a=c", "--define", "b=d");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue();
    useConfiguration("--experimental_platforms=//test:new_york_platform");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("--define", "a=c");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
    useConfiguration("--define", "a=c", "--experimental_platforms=//test:new_york_platform");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse();
  }

  /**
   * Tests that a config_setting doesn't allow a constraint_values list with more than one
   * constraint value per constraint setting.
   */
  @Test
  public void multipleValuesPerSetting() throws Exception {
    checkError(
        "foo",
        "bad",
        "in config_setting rule //foo:bad: "
            + "Duplicate constraint values detected: "
            + "constraint_setting //foo:notable_building has "
            + "[//foo:empire_state, //foo:space_needle], "
            + "constraint_setting //foo:museum has "
            + "[//foo:moma, //foo:sam]",
        "constraint_setting(name = 'notable_building')",
        "constraint_value(name = 'empire_state', constraint_setting = 'notable_building')",
        "constraint_value(name = 'space_needle', constraint_setting = 'notable_building')",
        "constraint_value(name = 'peace_arch', constraint_setting = 'notable_building')",
        "constraint_setting(name = 'museum')",
        "constraint_value(name = 'moma', constraint_setting = 'museum')",
        "constraint_value(name = 'sam', constraint_setting = 'museum')",
        "config_setting(",
        "    name = 'bad',",
        "    constraint_values = [",
        "        ':empire_state',",
        "        ':space_needle',",
        "        ':moma',",
        "        ':sam',",
        "    ],",
        ");");
  }

  @Test
  public void notAConstraintValue() throws Exception {
    checkError(
        "test",
        "match",
        "//test:what_am_i is not a constraint_value",
        "genrule(",
        "    name = 'what_am_i',",
        "    srcs = [],",
        "    outs = ['the_answer'],",
        "    cmd = 'echo an eternal enigma > $@')",
        "config_setting(",
        "    name = 'match',",
        "    constraint_values = [':what_am_i'],",
        ")");
  }

  private Set<LicenseType> getLicenses(String label) throws Exception {
    Rule rule = (Rule) getTarget(label);
    // There are two interfaces for retrieving a rule's license: from the Rule object and by
    // directly reading the "licenses" attribute. For config_setting both of these should always
    // be NONE. This method checks consistency between them.
    Set<LicenseType> fromRule = rule.getLicense().getLicenseTypes();
    Set<LicenseType> fromAttribute =
        RawAttributeMapper.of(rule).get("licenses", BuildType.LICENSE).getLicenseTypes();
    assertThat(fromRule).containsExactlyElementsIn(fromAttribute);
    return fromRule;
  }

  /** Tests that default license behavior is unaffected. */
  @Test
  public void licensesDefault() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            values = {
                "copt": "-Dfoo",
            },
        )
        """);

    useConfiguration("--copt", "-Dfoo");
    assertThat(getLicenses("//test:match")).containsExactly(LicenseType.NONE);
  }

  /** Tests that third-party doesn't require a license from config_setting. */
  @Test
  public void thirdPartyLicenseRequirement() throws Exception {
    scratch.file(
        "third_party/test/BUILD",
        """
        config_setting(
            name = "match",
            values = {
                "copt": "-Dfoo",
            },
        )
        """);

    useConfiguration("--copt", "-Dfoo");
    assertThat(getLicenses("//third_party/test:match")).containsExactly(LicenseType.NONE);
  }

  /** Tests that package-wide licenses are ignored by config_setting. */
  @Test
  public void packageLicensesIgnored() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        licenses(["restricted"])

        config_setting(
            name = "match",
            values = {
                "copt": "-Dfoo",
            },
        )
        """);

    useConfiguration("--copt", "-Dfoo");
    assertThat(getLicenses("//test:match")).containsExactly(LicenseType.NONE);
  }

  /** Tests that rule-specific licenses are ignored by config_setting. */
  @Test
  public void ruleLicensesUsed() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            licenses = ["restricted"],
            values = {
                "copt": "-Dfoo",
            },
        )
        """);

    useConfiguration("--copt", "-Dfoo");
    assertThat(getLicenses("//test:match")).containsExactly(LicenseType.NONE);
  }

  @Test
  public void aliasedStarlarkFlag() throws Exception {
    scratch.file(
        "test/flagdef.bzl",
        """
        def _impl(ctx):
            return []

        my_flag = rule(
            implementation = _impl,
            build_setting = config.string(flag = True),
        )
        """);

    scratch.file(
        "test/BUILD",
        """
        load("//test:flagdef.bzl", "my_flag")

        my_flag(
            name = "flag",
            build_setting_default = "default",
        )

        alias(
            name = "alias",
            actual = ":flag",
        )

        config_setting(
            name = "alias_setting",
            flag_values = {":alias": "specified"},
        )
        """);

    // Expect config_setting on an alias to pass completely through the alias to the underlying
    // flag it references. This means aliases model which flags trigger config_setting matches. This
    // keeps config_seting in sync with actual builds: if someone builds with --//foo:alias=1,
    // both the user and config_setting interpret it the same way even when the underlying flag
    // changes.
    useConfiguration("--//test:flag=specified");
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:alias_setting")).isTrue();
  }

  @Test
  public void labelStarlarkFlag() throws Exception {
    scratch.file(
        "test/BUILD",
        """

        label_flag(
            name = "my_flag",
            build_setting_default = "other_target",
        )

        genrule(
            name = "other_target",
            srcs = [],
            outs = ["other_target"],
            cmd = "echo other_target",
        )

        config_setting(
            name = "my_setting",
            flag_values = {":my_flag": "//test:other_target"},
        )
        """);

    // While label_flag is technically an alias, we can't treat it the same way as a normal alias:
    // label_flag is by definition a flag, and therefore a valid config_setting input. But the
    // target it refers to isn't necessarily a flag (and for most practical uses won't be a flag).
    // So it doesn't make sense to treat it like an alias(), where config_setting setting matches
    // against the reference's value. So config_setting treats a label_flag's value like any
    // normal flag value and compares against it directly.
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:my_setting")).isTrue();
  }

  @Test
  public void simpleStarlarkFlag() throws Exception {
    scratch.file(
        "test/flagdef.bzl",
        """
        def _impl(ctx):
            return []

        my_flag = rule(
            implementation = _impl,
            build_setting = config.string(flag = True),
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:flagdef.bzl", "my_flag")

        my_flag(
            name = "flag",
            build_setting_default = "actual_flag_value",
        )

        config_setting(
            name = "matches",
            flag_values = {
                ":flag": "actual_flag_value",
            },
        )

        config_setting(
            name = "doesntmatch",
            flag_values = {
                ":flag": "other_flag_value",
            },
        )
        """);
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:matches")).isTrue();
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:doesntmatch")).isFalse();
  }

  @Test
  public void starlarkListFlagSingleValue() throws Exception {
    // When a list-typed Starlark flag has value ["foo"], the config_setting's expected value "foo"
    // must match exactly.
    scratch.file(
        "test/flagdef.bzl",
        """
        def _impl(ctx):
            return []

        my_flag = rule(
            implementation = _impl,
            build_setting = config.string_list(flag = True),
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:flagdef.bzl", "my_flag")

        my_flag(
            name = "one_value_flag",
            build_setting_default = ["one"],
        )

        config_setting(
            name = "matches",
            flag_values = {
                ":one_value_flag": "one",
            },
        )

        config_setting(
            name = "doesntmatch",
            flag_values = {
                ":one_value_flag": "other",
            },
        )
        """);
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:matches")).isTrue();
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:doesntmatch")).isFalse();
  }

  @Test
  public void starlarkListFlagMultiValue() throws Exception {
    // When a list-typed Starlark flag has value ["foo", "bar"], the config_setting's expected
    // value "foo" must match *any* entry in the list.
    scratch.file(
        "test/flagdef.bzl",
        """
        def _impl(ctx):
            return []

        my_flag = rule(
            implementation = _impl,
            build_setting = config.string_list(flag = True),
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:flagdef.bzl", "my_flag")

        my_flag(
            name = "two_value_flag",
            build_setting_default = [
                "one",
                "two",
            ],
        )

        config_setting(
            name = "matches_one",
            flag_values = {
                ":two_value_flag": "one",
            },
        )

        config_setting(
            name = "matches_two",
            flag_values = {
                ":two_value_flag": "two",
            },
        )

        config_setting(
            name = "doesntmatch",
            flag_values = {
                ":two_value_flag": "other",
            },
        )
        """);
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:matches_one")).isTrue();
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:matches_two")).isTrue();
    assertThat(getConfigMatchingProviderResultAsBoolean("//test:doesntmatch")).isFalse();
  }

  @Test
  public void canOnlyMatchSingleValueInMultiValueFlags() throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
        def _impl(ctx):
            return []

        string_list_flag = rule(
            implementation = _impl,
            build_setting = config.string_list(flag = True),
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_list_flag")

        string_list_flag(
            name = "gouda",
            build_setting_default = ["smoked"],
        )

        config_setting(
            name = "match",
            flag_values = {
                ":gouda": "smoked,fresh",
            },
        )

        filegroup(
            name = "fg",
            srcs = select({
                ":match": [],
            }),
        )
        """);
    reporter.removeHandler(failFastHandler); // expect errors
    assertThat(getConfiguredTarget("//test:fg")).isNull();
    assertContainsEvent(
        "\"smoked,fresh\" not a valid value for flag //test:gouda. "
            + "Only single, exact values are allowed");
  }

  @Test
  public void singleValueThatLooksLikeMultiValueIsOkay() throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
        def _impl(ctx):
            return []

        string_flag = rule(
            implementation = _impl,
            build_setting = config.string(flag = True),
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")

        string_flag(
            name = "gouda",
            build_setting_default = "smoked,fresh",
        )

        config_setting(
            name = "match",
            flag_values = {
                ":gouda": "smoked,fresh",
            },
        )

        filegroup(
            name = "fg",
            srcs = select({
                ":match": [],
            }),
        )
        """);
    assertThat(getConfiguredTarget("//test:fg")).isNotNull();
    assertNoEvents();
  }

  @Test
  public void labelInValuesError() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            values = {"//foo:bar": "value"},
        )
        """);
    reporter.removeHandler(failFastHandler); // expect errors
    assertThat(getConfiguredTarget("//test:match")).isNull();
    assertContainsEvent(
        "in values attribute of config_setting rule //test:match: '//foo:bar' is"
            + " not a valid setting name, but appears to be a label. Did you mean to place it in"
            + " flag_values instead?");
  }

  @Test
  @TestParameters({"{flag: cpu}", "{flag: host_cpu}", "{flag: crosstool_top}"})
  public void selectOnDeprecatedFlagEmitsWarning(String flag) throws Exception {
    scratch.file(
        "test/BUILD",
        """
        config_setting(
            name = "match",
            values = {
              "%s": "//foo",
            },
        )
        """
            .formatted(flag));
    assertThat(getConfiguredTarget("//test:match")).isNotNull();
    assertContainsEvent(
        "select() on %s is deprecated. Use platform constraints instead".formatted(flag));
  }
}
