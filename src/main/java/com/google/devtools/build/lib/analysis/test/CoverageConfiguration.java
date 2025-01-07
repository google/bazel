// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.CoreOptionConverters.LabelConverter;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.RequiresOptions;
import com.google.devtools.build.lib.analysis.starlark.annotations.StarlarkConfigurationField;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.starlarkbuildapi.test.CoverageConfigurationApi;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import javax.annotation.Nullable;

/** The coverage configuration fragment. */
@Immutable
@RequiresOptions(options = {CoreOptions.class, CoverageConfiguration.CoverageOptions.class})
public class CoverageConfiguration extends Fragment implements CoverageConfigurationApi {

  /** Command-line options. */
  public static class CoverageOptions extends FragmentOptions {

    @Option(
        name = "coverage_output_generator",
        converter = LabelConverter.class,
        defaultValue = "@bazel_tools//tools/test:lcov_merger",
        documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
        effectTags = {
          OptionEffectTag.CHANGES_INPUTS,
          OptionEffectTag.AFFECTS_OUTPUTS,
          OptionEffectTag.LOADING_AND_ANALYSIS
        },
        help =
            "Location of the binary that is used to postprocess raw coverage reports. This must "
                + "be a binary target. Defaults to '//tools/test:lcov_merger'.")
    public Label coverageOutputGenerator;

    @Option(
        name = "coverage_report_generator",
        converter = LabelConverter.class,
        defaultValue = "@bazel_tools//tools/test:coverage_report_generator",
        documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
        effectTags = {
          OptionEffectTag.CHANGES_INPUTS,
          OptionEffectTag.AFFECTS_OUTPUTS,
          OptionEffectTag.LOADING_AND_ANALYSIS
        },
        help =
            "Location of the binary that is used to generate coverage reports. This must "
                + "be a binary target. Defaults to '//tools/test:coverage_report_generator'.")
    public Label coverageReportGenerator;
  }

  private final CoverageOptions coverageOptions;

  public CoverageConfiguration(BuildOptions buildOptions) {
    if (!buildOptions.get(CoreOptions.class).collectCodeCoverage) {
      this.coverageOptions = null;
      return;
    }
    this.coverageOptions = buildOptions.get(CoverageOptions.class);
  }

  @Override
  @StarlarkConfigurationField(
      name = "output_generator",
      doc = "Label for the coverage output generator.")
  @Nullable
  public Label outputGenerator() {
    if (coverageOptions == null) {
      return null;
    }
    return coverageOptions.coverageOutputGenerator;
  }

  @Nullable
  public Label reportGenerator() {
    if (coverageOptions == null) {
      return null;
    }
    return coverageOptions.coverageReportGenerator;
  }
}
