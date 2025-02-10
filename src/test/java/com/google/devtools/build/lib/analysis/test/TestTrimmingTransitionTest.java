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
// limitations under the License.
package com.google.devtools.build.lib.analysis.test;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.PlatformOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptionsView;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.config.ExecutionTransitionFactory;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.AttributeTransitionData;
import com.google.devtools.build.lib.testutil.FakeAttributeMapper;
import com.google.devtools.common.options.OptionsParsingException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link TestTrimmingTransitionFactory.TestTrimmingTransition}. */
@RunWith(JUnit4.class)
public class TestTrimmingTransitionTest extends BuildViewTestCase {
  private static final PatchTransition TRIM_TRANSITION =
      TestTrimmingTransitionFactory.TestTrimmingTransition.INSTANCE;

  @Test
  public void removesTestOptionsWhenSet() throws OptionsParsingException, InterruptedException {
    BuildOptions options =
        BuildOptions.of(
            ImmutableList.of(CoreOptions.class, TestOptions.class), "--trim_test_configuration");

    BuildOptions result =
        TRIM_TRANSITION.patch(
            new BuildOptionsView(options, TRIM_TRANSITION.requiresOptionFragments()),
            new StoredEventHandler());

    // Verify the transitions actually applied.
    assertThat(result).isNotNull();
    assertThat(result).isNotEqualTo(options);
    assertThat(result.contains(TestOptions.class)).isFalse();
  }

  @Test
  public void isNOPWhenUnset() throws OptionsParsingException, InterruptedException {
    BuildOptions options =
        BuildOptions.of(
            ImmutableList.of(CoreOptions.class, TestOptions.class), "--notrim_test_configuration");

    BuildOptions result =
        TRIM_TRANSITION.patch(
            new BuildOptionsView(options, TRIM_TRANSITION.requiresOptionFragments()),
            new StoredEventHandler());

    // Verify the transitions actually applied.
    assertThat(result).isNotNull();
    assertThat(result).isEqualTo(options);
  }

  @Test
  public void retainsStarlarkOptions() throws OptionsParsingException, InterruptedException {
    Label starlarkOptionKey = Label.parseCanonicalUnchecked("//options:foo");
    String starlarkOptionValue = "bar";

    BuildOptions options =
        BuildOptions.of(
                ImmutableList.of(CoreOptions.class, TestOptions.class), "--trim_test_configuration")
            .toBuilder()
            .addStarlarkOption(starlarkOptionKey, starlarkOptionValue)
            .build();

    BuildOptions result =
        TRIM_TRANSITION.patch(
            new BuildOptionsView(options, TRIM_TRANSITION.requiresOptionFragments()),
            new StoredEventHandler());

    // Verify the transitions actually applied.
    assertThat(result).isNotNull();
    assertThat(result).isNotEqualTo(options);
    assertThat(result.getStarlarkOptions().get(starlarkOptionKey)).isEqualTo(starlarkOptionValue);
  }

  @Test
  public void composeCommutativelyWithExecutionTransition() throws Exception {
    Label executionPlatform = Label.parseCanonicalUnchecked("//platform:exec");

    PatchTransition execTransition =
        ExecutionTransitionFactory.createFactory()
            .create(
                AttributeTransitionData.builder()
                    .attributes(FakeAttributeMapper.empty())
                    .analysisData(
                        getSkyframeExecutor()
                            .getStarlarkExecTransition(targetConfig.getOptions(), reporter))
                    .executionPlatform(executionPlatform)
                    .build());
    assertThat(execTransition).isNotNull();

    // Apply the transition.
    BuildOptions options =
        BuildOptions.of(
            targetConfig.getOptions().getFragmentClasses(),
            "--platforms=//platform:target",
            "--trim_test_configuration",
            "--experimental_exec_configuration_distinguisher=off");

    EventHandler handler = new StoredEventHandler();

    BuildOptions execTransitionOptions =
        execTransition.patch(
            new BuildOptionsView(options, execTransition.requiresOptionFragments()), handler);
    BuildOptions execThenTrim =
        TRIM_TRANSITION.patch(
            new BuildOptionsView(execTransitionOptions, TRIM_TRANSITION.requiresOptionFragments()),
            handler);

    BuildOptions trimTransitionOptions =
        TRIM_TRANSITION.patch(
            new BuildOptionsView(options, TRIM_TRANSITION.requiresOptionFragments()), handler);
    BuildOptions trimThenExec =
        execTransition.patch(
            new BuildOptionsView(trimTransitionOptions, execTransition.requiresOptionFragments()),
            handler);

    assertThat(execThenTrim).isEqualTo(trimThenExec);

    // Verify the transitions actually applied.
    assertThat(execThenTrim).isNotNull();
    assertThat(execThenTrim).isNotEqualTo(options);

    assertThat(execThenTrim.get(PlatformOptions.class).platforms)
        .containsExactly(executionPlatform);
    assertThat(execThenTrim.contains(TestOptions.class)).isFalse();
  }
}
