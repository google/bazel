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

package com.google.devtools.build.lib.bazel.repository;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.platform.ConstraintSettingInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformProviderUtils;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link LocalConfigPlatformFunction}. */
@RunWith(JUnit4.class)
public class LocalConfigPlatformFunctionTest extends BuildViewTestCase {
  private static final ConstraintSettingInfo CPU_CONSTRAINT =
      ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("@platforms//cpu:cpu"));
  private static final ConstraintSettingInfo OS_CONSTRAINT =
      ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("@platforms//os:os"));

  @Test
  public void generateConfigRepository() throws Exception {
    // Verify the package was created as expected.
    ConfiguredTarget hostPlatform = getConfiguredTarget("@local_config_platform_test//:host");
    assertThat(hostPlatform).isNotNull();

    PlatformInfo hostPlatformProvider = PlatformProviderUtils.platform(hostPlatform);
    assertThat(hostPlatformProvider).isNotNull();

    // Verify the OS and CPU constraints exist.
    assertThat(hostPlatformProvider.constraints().has(CPU_CONSTRAINT)).isTrue();
    assertThat(hostPlatformProvider.constraints().has(OS_CONSTRAINT)).isTrue();
  }

  @Test
  public void testHostConstraints() throws Exception {
    scratch.file(
        "test/platform/my_platform.bzl",
        "def _impl(ctx):",
        "  constraints = [val[platform_common.ConstraintValueInfo] "
            + "for val in ctx.attr.constraints]",
        "  platform = platform_common.PlatformInfo(",
        "      label = ctx.label, constraint_values = constraints)",
        "  return [platform]",
        "my_platform = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'constraints': attr.label_list(providers = [platform_common.ConstraintValueInfo])",
        "  }",
        ")");
    scratch.file(
        "test/platform/BUILD",
        """
        load("@local_config_platform_test//:constraints.bzl", "HOST_CONSTRAINTS")
        load("//test/platform:my_platform.bzl", "my_platform")

        my_platform(
            name = "custom",
            constraints = HOST_CONSTRAINTS,
        )
        """);

    setBuildLanguageOptions("--experimental_platforms_api");
    ConfiguredTarget platform = getConfiguredTarget("//test/platform:custom");
    assertThat(platform).isNotNull();

    PlatformInfo provider = PlatformProviderUtils.platform(platform);
    assertThat(provider.constraints()).isNotNull();
  }
}
