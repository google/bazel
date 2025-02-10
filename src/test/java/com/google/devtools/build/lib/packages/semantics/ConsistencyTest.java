// Copyright 2019 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.packages.semantics;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.skyframe.serialization.DynamicCodec;
import com.google.devtools.build.lib.skyframe.serialization.ImmutableDeserializationContext;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecs;
import com.google.devtools.build.lib.skyframe.serialization.testutils.RoundTripping;
import com.google.devtools.common.options.Options;
import com.google.devtools.common.options.OptionsParser;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import net.starlark.java.eval.StarlarkSemantics;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

// TODO(b/173631499): We really should just delete this test entirely.

/**
 * Tests for the flow of flags from {@link BuildLanguageOptions} to {@link StarlarkSemantics}, and
 * to and from {@code StarlarkSemantics}' serialized representation.
 *
 * <p>When adding a new option, it is easy to make a transposition error or a copy/paste error.
 * These tests guard against such errors. The following possible bugs are considered:
 *
 * <ul>
 *   <li>If a new semantics option is stored in {@code StarlarkSemantics} by {@link
 *       BuildLanguageOptions#toStarlarkSemantics} but has no associated command-line flag in
 *       BuildLanguageOptions, or vice versa, then the programmer will either be unable to implement
 *       its behavior, or unable to test it from the command line and add user documentation. We
 *       hope that the programmer notices this on their own.
 *   <li>To catch a copy/paste error where the wrong field's data is threaded through {@code
 *       toStarlarkSemantics()} or {@code deserialize(...)}, we repeatedly generate matching random
 *       instances of the input and expected output objects.
 *   <li>The {@link #checkDefaultsMatch} test ensures that there is no divergence between the
 *       default values of the two classes.
 *   <li>There is no test coverage for failing to update the non-generated webpage documentation. So
 *       don't forget that!
 * </ul>
 */
@RunWith(JUnit4.class)
public class ConsistencyTest {

  private static final int NUM_RANDOM_TRIALS = 10;

  /**
   * Checks that a randomly generated {@link BuildLanguageOptions} object can be converted to a
   * {@link StarlarkSemantics} object with the same field values.
   */
  @Test
  public void optionsToSemantics() throws Exception {
    for (int i = 0; i < NUM_RANDOM_TRIALS; i++) {
      long seed = i;
      BuildLanguageOptions options = buildRandomOptions(new Random(seed));
      StarlarkSemantics semantics = buildRandomSemantics(new Random(seed));
      StarlarkSemantics semanticsFromOptions = options.toStarlarkSemantics();
      assertThat(semanticsFromOptions).isEqualTo(semantics);
    }
  }

  /**
   * Checks that a randomly generated {@link StarlarkSemantics} object can be serialized and
   * deserialized to an equivalent object.
   */
  @Test
  public void serializationRoundTrip() throws Exception {
    DynamicCodec codec = new DynamicCodec(buildRandomSemantics(new Random(2)).getClass());
    for (int i = 0; i < NUM_RANDOM_TRIALS; i++) {
      StarlarkSemantics semantics = buildRandomSemantics(new Random(i));
      StarlarkSemantics deserialized =
          (StarlarkSemantics)
              RoundTripping.fromBytes(
                  new ImmutableDeserializationContext(),
                  codec,
                  RoundTripping.toBytes(
                      new ObjectCodecs().getSerializationContextForTesting(), codec, semantics));
      assertThat(deserialized).isEqualTo(semantics);
    }
  }

  @Test
  public void checkDefaultsMatch() {
    BuildLanguageOptions defaultOptions = Options.getDefaults(BuildLanguageOptions.class);
    StarlarkSemantics defaultSemantics = StarlarkSemantics.DEFAULT;
    StarlarkSemantics semanticsFromOptions = defaultOptions.toStarlarkSemantics();
    assertThat(semanticsFromOptions).isEqualTo(defaultSemantics);
  }

  @Test
  public void canGetBuilderFromInstance() {
    StarlarkSemantics original = StarlarkSemantics.DEFAULT;
    String flag = "-test";
    assertThat(original.getBool(flag)).isFalse();
    StarlarkSemantics modified = original.toBuilder().setBool(flag, true).build();
    assertThat(modified.getBool(flag)).isTrue();
  }

  /**
   * Constructs a {@link BuildLanguageOptions} object with random fields. Must access {@code rand}
   * using the same sequence of operations (for the same fields) as {@link #buildRandomSemantics}.
   */
  private static BuildLanguageOptions buildRandomOptions(Random rand) throws Exception {
    return parseOptions(
        // <== Add new options here in alphabetic order ==>
        "--experimental_disable_external_package=" + rand.nextBoolean(),
        "--experimental_sibling_repository_layout=" + rand.nextBoolean(),
        "--experimental_builtins_bzl_path=" + rand.nextDouble(),
        "--experimental_builtins_dummy=" + rand.nextBoolean(),
        "--experimental_bzl_visibility=" + rand.nextBoolean(),
        "--experimental_enable_android_migration_apis=" + rand.nextBoolean(),
        "--experimental_single_package_toolchain_binding=" + rand.nextBoolean(),
        "--experimental_isolated_extension_usages=" + rand.nextBoolean(),
        "--incompatible_no_implicit_watch_label=" + rand.nextBoolean(),
        "--experimental_google_legacy_api=" + rand.nextBoolean(),
        "--experimental_platforms_api=" + rand.nextBoolean(),
        "--incompatible_allow_tags_propagation=" + rand.nextBoolean(), // flag, Java names differ
        "--experimental_cc_shared_library=" + rand.nextBoolean(),
        "--experimental_repo_remote_exec=" + rand.nextBoolean(),
        "--experimental_dormant_deps=" + rand.nextBoolean(),
        "--incompatible_always_check_depset_elements=" + rand.nextBoolean(),
        "--incompatible_depset_for_libraries_to_link_getter=" + rand.nextBoolean(),
        "--incompatible_disable_target_provider_fields=" + rand.nextBoolean(),
        "--incompatible_disallow_empty_glob=" + rand.nextBoolean(),
        "--incompatible_disallow_struct_provider_syntax=" + rand.nextBoolean(),
        "--incompatible_do_not_split_linking_cmdline=" + rand.nextBoolean(),
        "--incompatible_enable_deprecated_label_apis=" + rand.nextBoolean(),
        "--incompatible_enforce_starlark_utf8="
            + BuildLanguageOptions.Utf8EnforcementMode.values()[
                rand.nextInt(BuildLanguageOptions.Utf8EnforcementMode.values().length)]
                .toString()
                .toLowerCase(Locale.ROOT),
        "--incompatible_locations_prefers_executable=" + rand.nextBoolean(),
        "--incompatible_merge_fixed_and_default_shell_env=" + rand.nextBoolean(),
        "--incompatible_no_attr_license=" + rand.nextBoolean(),
        "--incompatible_no_implicit_file_export=" + rand.nextBoolean(),
        "--incompatible_no_rule_outputs_param=" + rand.nextBoolean(),
        "--incompatible_run_shell_command_string=" + rand.nextBoolean(),
        "--incompatible_require_linker_input_cc_api=" + rand.nextBoolean(),
        "--incompatible_use_cc_configure_from_rules_cc=" + rand.nextBoolean(),
        "--incompatible_unambiguous_label_stringification=" + rand.nextBoolean(),
        "--internal_starlark_flag_test_canary=" + rand.nextBoolean(),
        "--max_computation_steps=" + rand.nextLong());
  }

  /**
   * Constructs a {@link StarlarkSemantics} object with random fields. Must access {@code rand}
   * using the same sequence of operations (for the same fields) as {@link #buildRandomOptions}.
   */
  private static StarlarkSemantics buildRandomSemantics(Random rand) {
    return StarlarkSemantics.builder()
        // <== Add new options here in alphabetic order ==>
        .setBool(BuildLanguageOptions.EXPERIMENTAL_DISABLE_EXTERNAL_PACKAGE, rand.nextBoolean())
        .setBool(BuildLanguageOptions.EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT, rand.nextBoolean())
        .set(BuildLanguageOptions.EXPERIMENTAL_BUILTINS_BZL_PATH, String.valueOf(rand.nextDouble()))
        .setBool(BuildLanguageOptions.EXPERIMENTAL_BUILTINS_DUMMY, rand.nextBoolean())
        .setBool(BuildLanguageOptions.EXPERIMENTAL_BZL_VISIBILITY, rand.nextBoolean())
        .setBool(
            BuildLanguageOptions.EXPERIMENTAL_ENABLE_ANDROID_MIGRATION_APIS, rand.nextBoolean())
        .setBool(
            BuildLanguageOptions.EXPERIMENTAL_SINGLE_PACKAGE_TOOLCHAIN_BINDING, rand.nextBoolean())
        .setBool(BuildLanguageOptions.EXPERIMENTAL_ISOLATED_EXTENSION_USAGES, rand.nextBoolean())
        .setBool(BuildLanguageOptions.INCOMPATIBLE_NO_IMPLICIT_WATCH_LABEL, rand.nextBoolean())
        .setBool(BuildLanguageOptions.EXPERIMENTAL_GOOGLE_LEGACY_API, rand.nextBoolean())
        .setBool(BuildLanguageOptions.EXPERIMENTAL_PLATFORMS_API, rand.nextBoolean())
        .setBool(BuildLanguageOptions.INCOMPATIBLE_ALLOW_TAGS_PROPAGATION, rand.nextBoolean())
        .setBool(BuildLanguageOptions.EXPERIMENTAL_CC_SHARED_LIBRARY, rand.nextBoolean())
        .setBool(BuildLanguageOptions.EXPERIMENTAL_REPO_REMOTE_EXEC, rand.nextBoolean())
        .setBool(BuildLanguageOptions.EXPERIMENTAL_DORMANT_DEPS, rand.nextBoolean())
        .setBool(BuildLanguageOptions.INCOMPATIBLE_ALWAYS_CHECK_DEPSET_ELEMENTS, rand.nextBoolean())
        .setBool(
            BuildLanguageOptions.INCOMPATIBLE_DEPSET_FOR_LIBRARIES_TO_LINK_GETTER,
            rand.nextBoolean())
        .setBool(
            BuildLanguageOptions.INCOMPATIBLE_DISABLE_TARGET_PROVIDER_FIELDS, rand.nextBoolean())
        .setBool(BuildLanguageOptions.INCOMPATIBLE_DISALLOW_EMPTY_GLOB, rand.nextBoolean())
        .setBool(
            BuildLanguageOptions.INCOMPATIBLE_DISALLOW_STRUCT_PROVIDER_SYNTAX, rand.nextBoolean())
        .setBool(BuildLanguageOptions.INCOMPATIBLE_DO_NOT_SPLIT_LINKING_CMDLINE, rand.nextBoolean())
        .setBool(BuildLanguageOptions.INCOMPATIBLE_ENABLE_DEPRECATED_LABEL_APIS, rand.nextBoolean())
        .set(
            BuildLanguageOptions.INCOMPATIBLE_ENFORCE_STARLARK_UTF8,
            BuildLanguageOptions.Utf8EnforcementMode.values()[
                rand.nextInt(BuildLanguageOptions.Utf8EnforcementMode.values().length)])
        .setBool(BuildLanguageOptions.INCOMPATIBLE_LOCATIONS_PREFERS_EXECUTABLE, rand.nextBoolean())
        .setBool(
            BuildLanguageOptions.INCOMPATIBLE_MERGE_FIXED_AND_DEFAULT_SHELL_ENV, rand.nextBoolean())
        .setBool(BuildLanguageOptions.INCOMPATIBLE_NO_ATTR_LICENSE, rand.nextBoolean())
        .setBool(BuildLanguageOptions.INCOMPATIBLE_NO_IMPLICIT_FILE_EXPORT, rand.nextBoolean())
        .setBool(BuildLanguageOptions.INCOMPATIBLE_NO_RULE_OUTPUTS_PARAM, rand.nextBoolean())
        .setBool(BuildLanguageOptions.INCOMPATIBLE_RUN_SHELL_COMMAND_STRING, rand.nextBoolean())
        .setBool(BuildLanguageOptions.INCOMPATIBLE_REQUIRE_LINKER_INPUT_CC_API, rand.nextBoolean())
        .setBool(
            BuildLanguageOptions.INCOMPATIBLE_USE_CC_CONFIGURE_FROM_RULES_CC, rand.nextBoolean())
        .setBool(
            BuildLanguageOptions.INCOMPATIBLE_UNAMBIGUOUS_LABEL_STRINGIFICATION, rand.nextBoolean())
        .setBool(StarlarkSemantics.PRINT_TEST_MARKER, rand.nextBoolean())
        .set(BuildLanguageOptions.MAX_COMPUTATION_STEPS, rand.nextLong())
        .build();
  }

  private static BuildLanguageOptions parseOptions(String... args) throws Exception {
    OptionsParser parser =
        OptionsParser.builder()
            .optionsClasses(BuildLanguageOptions.class)
            .allowResidue(false)
            .build();
    parser.parse(Arrays.asList(args));
    return parser.getOptions(BuildLanguageOptions.class);
  }
}
