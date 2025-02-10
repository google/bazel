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
package com.google.devtools.build.lib.skyframe.config;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.analysis.util.DummyTestFragment;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.runtime.ConfigFlagDefinitions;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.skyframe.EvaluationResult;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link FlagSetFunction}. */
@RunWith(JUnit4.class)
public final class FlagSetsFunctionTest extends BuildViewTestCase {

  @Override
  protected ConfiguredRuleClassProvider createRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder.addConfigurationFragment(DummyTestFragment.class);
    return builder.build();
  }

  /**
   * Asserts a {@link FlagSetValue} contains a given kind of event with a given message that
   * occurred a given number of times.
   *
   * <p>Only applies to messages that are expected to persistently display, even on Skyframe cache
   * hits: see {@link FlagSetValue#getPersistentMessages}.
   */
  private void assertContainsPersistentMessage(
      FlagSetValue value, EventKind kind, int frequency, String message) {
    int count = 0;
    for (Event event : value.getPersistentMessages()) {
      if (event.getKind() != kind) {
        continue;
      }
      count++;
      assertThat(event.getMessage()).contains(message);
    }
    assertThat(count).isEqualTo(frequency);
  }

  /**
   * Given "//foo:myflag" and "default_value", creates the BUILD and .bzl files to realize a
   * string_flag with that label and default value.
   */
  private void createStringFlag(String labelName, String defaultValue) throws Exception {
    String flagDir = labelName.substring(2, labelName.indexOf(":"));
    String flagName = labelName.substring(labelName.indexOf(":") + 1);
    scratch.file(
        flagDir + "/build_settings.bzl",
        """
        string_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.string(flag = True),
            attrs = {
                "scope": attr.string(default = "universal"),
            },
        )
        """);
    scratch.file(
        flagDir + "/BUILD",
        """
        load(":build_settings.bzl", "string_flag")
        string_flag(
            name = "%s",
            build_setting_default = "%s",
        )
        """
            .formatted(flagName, defaultValue));
  }

  @Test
  public void flagSetsFunction_returns_modified_buildOptions() throws Exception {
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": ['--platforms=//buildenv/platforms/android:x86'],
          }
        }
        """);
    scratch.file("test/BUILD");
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");
    // given original BuildOptions and a valid key
    BuildOptions buildOptions =
        BuildOptions.getDefaultBuildOptionsForFragments(
            ruleClassProvider.getFragmentRegistry().getOptionsClasses());
    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "test_config",
            buildOptions,
            /* userOptions= */ ImmutableMap.of(),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);
    FlagSetValue flagSetsValue = executeFunction(key);
    // expects the modified BuildOptions
    assertThat(flagSetsValue.getOptionsFromFlagset())
        .containsExactly("--platforms=//buildenv/platforms/android:x86");
  }

  @Test
  public void given_unknown_sclConfig_flagSetsFunction_returns_original_buildOptions()
      throws Exception {
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": ['--platforms=//buildenv/platforms/android:x86'],
          },
        }
        """);
    scratch.file("test/BUILD");
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");
    // given valid project file but a nonexistent scl config
    BuildOptions buildOptions =
        BuildOptions.getDefaultBuildOptionsForFragments(
            ruleClassProvider.getFragmentRegistry().getOptionsClasses());
    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "unknown_config",
            buildOptions,
            /* userOptions= */ ImmutableMap.of(),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ false);
    FlagSetValue flagSetsValue = executeFunction(key);

    // expects the original BuildOptions
    assertThat(flagSetsValue.getOptionsFromFlagset()).isEmpty();
  }

  @Test
  public void flagSetsFunction_returns_original_buildOptions() throws Exception {
    // given original BuildOptions and an empty scl config name
    BuildOptions buildOptions =
        BuildOptions.getDefaultBuildOptionsForFragments(
            ruleClassProvider.getFragmentRegistry().getOptionsClasses());
    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "",
            buildOptions,
            /* userOptions= */ ImmutableMap.of(),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ false);
    FlagSetValue flagSetsValue = executeFunction(key);

    // expects the original BuildOptions
    assertThat(flagSetsValue.getOptionsFromFlagset()).isEmpty();
  }

  @Test
  public void invalidEnforcementPolicy_fails() throws Exception {
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": ['--platforms=//buildenv/platforms/android:x86'],
          },
          "enforcement_policy": "INVALID",
        }
        """);
    scratch.file("test/BUILD");
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");
    // given original BuildOptions and a valid key
    BuildOptions buildOptions =
        BuildOptions.getDefaultBuildOptionsForFragments(
            ruleClassProvider.getFragmentRegistry().getOptionsClasses());
    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "test_config",
            buildOptions,
            /* userOptions= */ ImmutableMap.of(),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);
    var thrown = assertThrows(Exception.class, () -> executeFunction(key));
    assertThat(thrown)
        .hasMessageThat()
        .contains("invalid enforcement_policy 'INVALID' in //test:PROJECT.scl");
  }

  @Test
  public void noEnforceCanonicalConfigs_noConfigsIsNoop() throws Exception {
    scratch.file("test/PROJECT.scl", "");
    scratch.file("test/BUILD");
    BuildOptions buildOptions =
        BuildOptions.getDefaultBuildOptionsForFragments(
            ruleClassProvider.getFragmentRegistry().getOptionsClasses());
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");
    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "random_config_name",
            buildOptions,
            /* userOptions= */ ImmutableMap.of(),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ false);
    FlagSetValue flagSetsValue = executeFunction(key);

    // Without enforced configs, unknown configs are a no-op.
    assertThat(flagSetsValue.getOptionsFromFlagset()).isEmpty();
    assertContainsEvent("Ignoring --scl_config=random_config_name");
  }

  @Test
  public void noEnforceCanonicalConfigs_sclConfigWarns() throws Exception {
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": ["--define=bar=bar"],
          }
        }
        """);
    scratch.file("test/BUILD");
    BuildOptions buildOptions =
        BuildOptions.getDefaultBuildOptionsForFragments(
            ruleClassProvider.getFragmentRegistry().getOptionsClasses());
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");
    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "test_config",
            buildOptions,
            /* userOptions= */ ImmutableMap.of(),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ false);

    FlagSetValue flagSetsValue = executeFunction(key);

    assertThat(flagSetsValue.getOptionsFromFlagset()).isEmpty();
    assertContainsEvent(
        "Ignoring --scl_config=test_config because --enforce_project_configs is not set");
  }

  @Test
  public void enforceCanonicalConfigs_noConfigsIsNoop() throws Exception {
    scratch.file("test/PROJECT.scl", "");
    scratch.file("test/BUILD");
    BuildOptions buildOptions =
        BuildOptions.getDefaultBuildOptionsForFragments(
            ruleClassProvider.getFragmentRegistry().getOptionsClasses());
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");
    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "fake_config",
            buildOptions,
            /* userOptions= */ ImmutableMap.of(),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ false);
    FlagSetValue flagSetsValue = executeFunction(key);

    assertThat(flagSetsValue.getOptionsFromFlagset()).isEmpty();
  }

  @Test
  public void enforceCanonicalConfigsValidConfig() throws Exception {
    createStringFlag("//test:myflag", /* defaultValue= */ "default");
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": ['--//test:myflag=test_config_value'],
            "other_config": ['--//test:myflag=other_config_value'],
          },
          "default_config": "test_config",
        }
        """);
    BuildOptions buildOptions =
        BuildOptions.getDefaultBuildOptionsForFragments(
            ruleClassProvider.getFragmentRegistry().getOptionsClasses());
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "other_config",
            buildOptions,
            /* userOptions= */ ImmutableMap.of(),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);
    FlagSetValue flagSetsValue = executeFunction(key);

    assertThat(flagSetsValue.getOptionsFromFlagset())
        .contains("--//test:myflag=other_config_value");
    assertContainsPersistentMessage(
        flagSetsValue,
        EventKind.INFO,
        /* frequency= */ 1,
        "Applying flags from the config 'other_config'");
  }

  @Test
  public void enforceCanonicalConfigsExtraNativeFlag_withSclConfig_fails() throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
string_flag = rule(implementation = lambda ctx: [], build_setting = config.string(flag = True))
""");
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")
        string_flag(
            name = "myflag",
            build_setting_default = "default",
        )
        """);
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": ['--//test:myflag=test_config_value'],
            "other_config": ['--//test:myflag=other_config_value'],
          },
          "enforcement_policy": "strict",
        }
        """);
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");
    BuildOptions buildOptions = createBuildOptions("--define=foo=bar");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "test_config",
            buildOptions,
            /* userOptions= */ ImmutableMap.of("--define=foo=bar", ""),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);

    var thrown = assertThrows(Exception.class, () -> executeFunction(key));
    assertThat(thrown).hasMessageThat().contains("Found ['--define=foo=bar']");
  }

  @Test
  public void enforceCanonicalConfigsFlag_warnPolicy_passes() throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
string_flag = rule(implementation = lambda ctx: [], build_setting = config.string(flag = True))
""");
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")
        string_flag(
            name = "myflag",
            build_setting_default = "default",
        )
        """);
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": ['--//test:myflag=test_config_value'],
            "other_config": ['--//test:myflag=other_config_value'],
          },
          "enforcement_policy": "warn",
        }
        """);
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");
    BuildOptions buildOptions = createBuildOptions("--define=foo=bar");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "test_config",
            buildOptions,
            /* userOptions= */ ImmutableMap.of("--define=foo=bar", ""),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);

    assertContainsPersistentMessage(
        executeFunction(key),
        EventKind.WARNING,
        /* frequency= */ 1,
        "also sets output-affecting flags in the command line or user bazelrc:"
            + " ['--define=foo=bar']");
  }

  @Test
  public void enforceCanonicalConfigsFlag_compatiblePolicy_unrelatedFlag_warns() throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
string_flag = rule(implementation = lambda ctx: [], build_setting = config.string(flag = True))
""");
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")
        string_flag(
            name = "myflag",
            build_setting_default = "default",
        )
        """);
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": ['--//test:myflag=test_config_value'],
            "other_config": ['--//test:myflag=other_config_value'],
          },
          "enforcement_policy": "compatible",
        }
        """);
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");
    BuildOptions buildOptions = createBuildOptions("--define=foo=bar");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "test_config",
            buildOptions,
            /* userOptions= */ ImmutableMap.of("--define=foo=bar", ""),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);

    assertContainsPersistentMessage(
        executeFunction(key),
        EventKind.WARNING,
        /* frequency= */ 1,
        "also sets output-affecting flags in the command line or user bazelrc:"
            + " ['--define=foo=bar']");
  }

  @Test
  public void enforceCanonicalConfigs_compatiblePolicy_onlyDifferentValue_fails() throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
string_flag = rule(implementation = lambda ctx: [], build_setting = config.string(flag = True))
""");
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")
        string_flag(
            name = "myflag",
            build_setting_default = "default",
        )
        string_flag(
            name = "other_flag",
            build_setting_default = "default",
        )
        """);
    scratch.file(
        "test/PROJECT.scl",
        """
project = {
  "configs": {
    "test_config": ['--//test:myflag=test_config_value', '--//test:other_flag=test_config_value'],
  },
  "enforcement_policy": "compatible",
}
""");
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");
    BuildOptions buildOptions =
        createBuildOptions("--//test:myflag=other_value", "--//test:other_flag=test_config_value");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "test_config",
            buildOptions,
            /* userOptions= */ ImmutableMap.of(
                "--//test:myflag=other_value", "", "--//test:other_flag=test_config_value", ""),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);

    var thrown = assertThrows(Exception.class, () -> executeFunction(key));
    assertThat(thrown).hasMessageThat().contains("Found ['--//test:myflag=other_value']");
    assertThat(thrown).hasMessageThat().doesNotContain("['--//test:other_flag=test_config_value']");
  }

  @Test
  public void enforceCanonicalConfigs_wrongConfigsType() throws Exception {
    scratch.file("test/BUILD");
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": 1,
        }
        """);
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");
    BuildOptions buildOptions = createBuildOptions("--define=foo=bar");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "test_config",
            buildOptions,
            /* userOptions= */ ImmutableMap.of(),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);

    var thrown = assertThrows(Exception.class, () -> executeFunction(key));
    assertThat(thrown)
        .hasMessageThat()
        .contains("configs variable must be a map of strings to lists of strings");
  }

  @Test
  public void enforceCanonicalConfigs_wrongConfigsKeyType() throws Exception {
    scratch.file("test/BUILD");
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            123: ["--compilation_mode=opt"],
          },
        }
        """);
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");
    BuildOptions buildOptions = createBuildOptions("--define=foo=bar");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "test_config",
            buildOptions,
            /* userOptions= */ ImmutableMap.of(),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);

    var thrown = assertThrows(Exception.class, () -> executeFunction(key));
    assertThat(thrown)
        .hasMessageThat()
        .contains("configs variable must be a map of strings to lists of strings");
  }

  @Test
  public void enforceCanonicalConfigs_wrongConfigsValueType() throws Exception {
    scratch.file("test/BUILD");
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": 123,
          },
        }
        """);
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");
    BuildOptions buildOptions = createBuildOptions("--define=foo=bar");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "test_config",
            buildOptions,
            /* userOptions= */ ImmutableMap.of(),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);

    var thrown = assertThrows(Exception.class, () -> executeFunction(key));
    assertThat(thrown)
        .hasMessageThat()
        .contains("configs variable must be a map of strings to lists of strings");
  }

  @Test
  public void enforceCanonicalConfigsExtraFakeExpandedFlag_withSclConfig_fails() throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
        string_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.string(flag = True),
            attrs = {
                "scope": attr.string(default = "universal"),
            },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")
        string_flag(
            name = "myflag",
            build_setting_default = "default",
        )
        """);
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": ['--//test:myflag=test_config_value'],
            "other_config": ['--//test:myflag=other_config_value'],
          },
          "enforcement_policy": "strict",
        }
        """);
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "test_config",
            createBuildOptions(), // this is a fake flag so don't add it here.
            /* userOptions= */ ImmutableMap.of("--bar", "--config=foo"),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);

    var thrown = assertThrows(Exception.class, () -> executeFunction(key));
    assertThat(thrown).hasMessageThat().contains("Found ['--config=foo']");
  }

  @Test
  public void enforceCanonicalConfigs_extraFlagThatIsAlsoInConfig_differentValue_fails()
      throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
        string_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.string(flag = True),
            attrs = {
                "scope": attr.string(default = "universal"),
            },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")
        string_flag(
            name = "myflag",
            build_setting_default = "default",
        )
        """);
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": ['--//test:myflag=test_config_value'],
          },
          "enforcement_policy": "strict",
        }
        """);
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");
    BuildOptions buildOptions = createBuildOptions("--//test:myflag=other_value");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "test_config",
            buildOptions,
            /* userOptions= */ ImmutableMap.of("--//test:myflag=other_value", ""),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);

    var thrown = assertThrows(Exception.class, () -> executeFunction(key));
    assertThat(thrown).hasMessageThat().contains("Found ['--//test:myflag=other_value']");
  }

  @Test
  public void enforceCanonicalConfigs_passedFlagThatIsInConfig_passes() throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
        string_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.string(flag = True),
            attrs = {
                "scope": attr.string(default = "universal"),
            },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")
        string_flag(
            name = "myflag",
            build_setting_default = "default",
        )
        """);
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": ['--//test:myflag=test_config_value'],
          },
        }
        """);
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");
    BuildOptions buildOptions = createBuildOptions("--//test:myflag=test_config_value");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "test_config",
            buildOptions,
            /* userOptions= */ ImmutableMap.of("--//test:myflag=test_config_value", ""),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);

    var unused = executeFunction(key);
    assertDoesNotContainEvent("--scl_config must be the only configuration-affecting flag");
  }

  @Test
  public void enforceCanonicalConfigsExtraStarlarkFlag_fails() throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
        string_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.string(flag = True),
            attrs = {
                "scope": attr.string(default = "universal"),
            },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")
        string_flag(
            name = "myflag",
            build_setting_default = "default",
        )
        string_flag(
            name = "starlark_flags_always_affect_configuration",
            build_setting_default = "default",
        )
        """);
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": ['--//test:myflag=test_config_value'],
            "other_config": ['--//test:myflag=other_config_value'],
          },
          "enforcement_policy": "strict",
        }
        """);
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");
    BuildOptions buildOptions =
        createBuildOptions("--//test:starlark_flags_always_affect_configuration=yes_they_do");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "test_config",
            buildOptions,
            /* userOptions= */ ImmutableMap.of(
                "--//test:starlark_flags_always_affect_configuration=yes_they_do", ""),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);

    var thrown = assertThrows(Exception.class, () -> executeFunction(key));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Found ['--//test:starlark_flags_always_affect_configuration=yes_they_do']");
  }

  @Test
  public void enforceCanonicalConfigsExtraTestFlag_passes() throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
        string_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.string(flag = True),
            attrs = {
                "scope": attr.string(default = "universal"),
            },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")
        string_flag(
            name = "myflag",
            build_setting_default = "default",
        )
        string_flag(
            name = "starlark_flags_always_affect_configuration",
            build_setting_default = "default",
        )
        """);
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": ['--//test:myflag=test_config_value'],
            "other_config": ['--//test:myflag=other_config_value'],
          },
          "supported_configs": {
            "test_config": "User documentation for what this config means",
          },
        }
        """);
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");
    BuildOptions buildOptions =
        createBuildOptions("--test_filter=foo", "--cache_test_results=true", "--test_arg=blah");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "test_config",
            buildOptions,
            /* userOptions= */ ImmutableMap.of(
                "--test_filter=foo", "", "--cache_test_results=true", "", "--test_arg=blah", ""),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);

    var unused = executeFunction(key);
    assertDoesNotContainEvent("--scl_config must be the only configuration-affecting flag");
  }

  @Test
  public void noEnforceCanonicalConfigs_noSclConfig_extraFlag_passes() throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
string_flag = rule(implementation = lambda ctx: [], build_setting = config.string(flag = True))
""");
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")
        string_flag(
            name = "myflag",
            build_setting_default = "default",
        )
        """);
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": ['--//test:myflag=test_config_value'],
            "other_config": ['--//test:myflag=other_config_value'],
          },
        }
        """);
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");
    BuildOptions buildOptions = createBuildOptions("--define=foo=bar");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "test_config",
            buildOptions,
            /* userOptions= */ ImmutableMap.of("--define=foo=bar", ""),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ false);

    var unused = executeFunction(key);
    assertDoesNotContainEvent("--scl_config must be the only configuration-affecting flag");
  }

  @Test
  public void enforceCanonicalConfigsNonExistentConfig_fails() throws Exception {
    createStringFlag("//test:myflag", /* defaultValue= */ "default");
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": ['--//test:myflag=test_config_value'],
          },
        }
        """);
    BuildOptions buildOptions =
        BuildOptions.getDefaultBuildOptionsForFragments(
            ruleClassProvider.getFragmentRegistry().getOptionsClasses());
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            "non_existent_config",
            buildOptions,
            /* userOptions= */ ImmutableMap.of(),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);

    var thrown = assertThrows(Exception.class, () -> executeFunction(key));
    assertThat(thrown)
        .hasMessageThat()
        .contains("--scl_config=non_existent_config is not a valid configuration for this project");
  }

  @Test
  public void enforceCanonicalConfigsNoSclConfigFlagNoDefaultConfig() throws Exception {
    createStringFlag("//test:myflag", /* defaultValue= */ "default");
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": ['--//test:myflag=test_config_value'],
            "other_config": ['--//test:myflag=other_config_value'],
          },
        }
        """);
    BuildOptions buildOptions =
        BuildOptions.getDefaultBuildOptionsForFragments(
            ruleClassProvider.getFragmentRegistry().getOptionsClasses());
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            /* sclConfig= */ "",
            buildOptions,
            /* userOptions= */ ImmutableMap.of(),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);

    var thrown = assertThrows(Exception.class, () -> executeFunction(key));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "This project's builds must set --scl_config because no default_config is defined");
  }

  @Test
  public void enforceCanonicalConfigsNoSclConfigFlagNonexistentDefaultConfig() throws Exception {
    createStringFlag("//test:myflag", /* defaultValue= */ "default");
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": ['--//test:myflag=test_config_value'],
            "other_config": ['--//test:myflag=other_config_value'],
          },
            "default_config": "nonexistent_config",
        }
        """);
    BuildOptions buildOptions =
        BuildOptions.getDefaultBuildOptionsForFragments(
            ruleClassProvider.getFragmentRegistry().getOptionsClasses());
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            /* sclConfig= */ "",
            buildOptions,
            /* userOptions= */ ImmutableMap.of(),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);

    var thrown = assertThrows(Exception.class, () -> executeFunction(key));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "This project's builds must set --scl_config because default_config refers to a"
                + " nonexistent config: nonexistent_config");
  }

  @Test
  public void enforceCanonicalConfigs_wrongDefaultConfigType() throws Exception {
    createStringFlag("//test:myflag", /* defaultValue= */ "default");
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": ['--//test:myflag=test_config_value'],
            "other_config": ['--//test:myflag=other_config_value'],
          },
          "default_config": ["test_config"],
        }
        """);
    BuildOptions buildOptions =
        BuildOptions.getDefaultBuildOptionsForFragments(
            ruleClassProvider.getFragmentRegistry().getOptionsClasses());
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            /* sclConfig= */ "",
            buildOptions,
            /* userOptions= */ ImmutableMap.of(),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);

    var thrown = assertThrows(Exception.class, () -> executeFunction(key));
    assertThat(thrown)
        .hasMessageThat()
        .contains("default_config must be a string matching a configs variable definition");
  }

  @Test
  public void enforceCanonicalConfigsNoSclConfigFlagValidDefaultConfig() throws Exception {
    createStringFlag("//test:myflag", /* defaultValue= */ "default");
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": ['--//test:myflag=test_config_value'],
          },
          "default_config": "test_config",
        }
        """);
    BuildOptions buildOptions =
        BuildOptions.getDefaultBuildOptionsForFragments(
            ruleClassProvider.getFragmentRegistry().getOptionsClasses());
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            /* sclConfig= */ "",
            buildOptions,
            /* userOptions= */ ImmutableMap.of(),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);
    FlagSetValue flagSetsValue = executeFunction(key);

    assertThat(flagSetsValue.getOptionsFromFlagset())
        .containsExactly("--//test:myflag=test_config_value");
    assertContainsPersistentMessage(
        flagSetsValue,
        EventKind.INFO,
        /* frequency= */ 1,
        "Applying flags from the config 'test_config'");
  }

  @Test
  public void nonBuildOptions_areIgnored() throws Exception {
    createStringFlag("//test:myflag", /* defaultValue= */ "default");
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": ['--bazelrc=foo'],
          },
          "default_config": "test_config",
        }
        """);
    BuildOptions buildOptions =
        BuildOptions.getDefaultBuildOptionsForFragments(
            ruleClassProvider.getFragmentRegistry().getOptionsClasses());
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            /* sclConfig= */ "",
            buildOptions,
            /* userOptions= */ ImmutableMap.of(),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);
    FlagSetValue flagSetsValue = executeFunction(key);

    assertThat(flagSetsValue.getOptionsFromFlagset()).isEmpty();
    assertDoesNotContainEvent("Applying flags from the config 'test_config'");
  }

  @Test
  public void basicFlagsetFunctionalityWithTopLevelProjectSchema() throws Exception {
    createStringFlag("//test:myflag", /* defaultValue= */ "default");
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "test_config": ['--//test:myflag=test_config_value'],
          },
          "default_config": "test_config",
        }
        """);
    BuildOptions buildOptions =
        BuildOptions.getDefaultBuildOptionsForFragments(
            ruleClassProvider.getFragmentRegistry().getOptionsClasses());
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            /* sclConfig= */ "",
            buildOptions,
            /* userOptions= */ ImmutableMap.of(),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);
    FlagSetValue flagSetsValue = executeFunction(key);

    assertThat(flagSetsValue.getOptionsFromFlagset())
        .containsExactly("--//test:myflag=test_config_value");
    assertContainsPersistentMessage(
        flagSetsValue,
        EventKind.INFO,
        /* frequency= */ 1,
        "Applying flags from the config 'test_config'");
  }

  @Test
  public void clearUserDocumentationOfValidConfigs() throws Exception {
    createStringFlag("//test:myflag", /* defaultValue= */ "default");
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "configs": {
            "debug": ['--//test:myflag=debug_value'],
            "release": ['--//test:myflag=debug_value'],
          },
        }
        """);
    BuildOptions buildOptions =
        BuildOptions.getDefaultBuildOptionsForFragments(
            ruleClassProvider.getFragmentRegistry().getOptionsClasses());
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");

    FlagSetValue.Key key =
        FlagSetValue.Key.create(
            Label.parseCanonical("//test:PROJECT.scl"),
            /* sclConfig= */ null,
            buildOptions,
            /* userOptions= */ ImmutableMap.of(),
            /* configFlagDefinitions= */ ConfigFlagDefinitions.NONE,
            /* enforceCanonical= */ true);

    var thrown = assertThrows(Exception.class, () -> executeFunction(key));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            """
This project's builds must set --scl_config because no default_config is defined.

This project supports:
  --scl_config=debug: ["--//test:myflag=debug_value"]
  --scl_config=release: ["--//test:myflag=debug_value"]

This policy is defined in test/PROJECT.scl.\
""");
  }

  private FlagSetValue executeFunction(FlagSetValue.Key key) throws Exception {
    SkyframeExecutor skyframeExecutor = getSkyframeExecutor();
    EvaluationResult<FlagSetValue> result =
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
