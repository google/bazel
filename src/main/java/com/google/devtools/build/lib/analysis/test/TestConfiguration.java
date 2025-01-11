// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.analysis.OptionsDiffPredicate;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.CoreOptionConverters.LabelConverter;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.PerLabelOptions;
import com.google.devtools.build.lib.analysis.config.RequiresOptions;
import com.google.devtools.build.lib.analysis.config.RunUnder;
import com.google.devtools.build.lib.analysis.test.CoverageConfiguration.CoverageOptions;
import com.google.devtools.build.lib.analysis.test.TestShardingStrategy.ShardingStrategyConverter;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.TestSize;
import com.google.devtools.build.lib.packages.TestTimeout;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.RegexFilter;
import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDefinition;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.TriState;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Test-related options. */
@RequiresOptions(options = {TestConfiguration.TestOptions.class})
public class TestConfiguration extends Fragment {
  public static final OptionsDiffPredicate SHOULD_INVALIDATE_FOR_OPTION_DIFF =
      (options, changedOption, oldValue, newValue) -> {
        if (TestOptions.ALWAYS_INVALIDATE_WHEN_CHANGED.contains(changedOption)) {
          // changes in --trim_test_configuration itself or related flags always prompt invalidation
          return true;
        }
        // LINT.IfChange
        Class<? extends FragmentOptions> affectedOptionsClass =
            changedOption.getDeclaringClass(FragmentOptions.class);
        if (!affectedOptionsClass.equals(TestOptions.class)
            && !affectedOptionsClass.equals(CoverageOptions.class)) {
          // options outside of TestOptions always prompt invalidation, except for --run_under.
          if (affectedOptionsClass.equals(CoreOptions.class)
              && changedOption.getOptionName().equals("run_under")) {
            return !Objects.equals(
                RunUnder.trimForNonTestConfiguration((RunUnder) oldValue),
                RunUnder.trimForNonTestConfiguration((RunUnder) newValue));
          }
          return true;
        }
        // LINT.ThenChange(TestTrimmingLogic.java)
        // other options in TestOptions require invalidation when --trim_test_configuration is off
        return !options.get(TestOptions.class).trimTestConfiguration;
      };

  /** Command-line options. */
  public static class TestOptions extends FragmentOptions {
    private static final ImmutableSet<OptionDefinition> ALWAYS_INVALIDATE_WHEN_CHANGED =
        ImmutableSet.of(
            OptionsParser.getOptionDefinitionByName(TestOptions.class, "trim_test_configuration"),
            OptionsParser.getOptionDefinitionByName(
                TestOptions.class, "experimental_retain_test_configuration_across_testonly"));

    @Option(
        name = "test_env",
        converter = Converters.OptionalAssignmentConverter.class,
        allowMultiple = true,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.TESTING,
        effectTags = {OptionEffectTag.TEST_RUNNER},
        help =
            "Specifies additional environment variables to be injected into the test runner "
                + "environment. Variables can be either specified by name, in which case its value "
                + "will be read from the Bazel client environment, or by the name=value pair. "
                + "This option can be used multiple times to specify several variables. "
                + "Used only by the 'bazel test' command.")
    public List<Map.Entry<String, String>> testEnvironment;

    @Option(
        name = "test_timeout",
        defaultValue = "-1",
        converter = TestTimeout.TestTimeoutConverter.class,
        documentationCategory = OptionDocumentationCategory.TESTING,
        effectTags = {OptionEffectTag.UNKNOWN},
        help =
            "Override the default test timeout values for test timeouts (in secs). If a single "
                + "positive integer value is specified it will override all categories.  If 4 "
                + "comma-separated integers are specified, they will override the timeouts for "
                + "short, moderate, long and eternal (in that order). In either form, a value of "
                + "-1 tells blaze to use its default timeouts for that category.")
    public Map<TestTimeout, Duration> testTimeout;

    @Option(
        name = "default_test_resources",
        defaultValue = "null",
        converter = TestResourcesConverter.class,
        allowMultiple = true,
        documentationCategory = OptionDocumentationCategory.TESTING,
        effectTags = {OptionEffectTag.UNKNOWN},
        help =
            "Override the default resources amount for tests. The expected format is"
                + " <resource>=<value>. If a single positive number is specified as <value>"
                + " it will override the default resources for all test sizes. If 4"
                + " comma-separated numbers are specified, they will override the resource"
                + " amount for respectively the small, medium, large, enormous test sizes."
                + " Values can also be HOST_RAM/HOST_CPU, optionally followed"
                + " by [-|*]<float> (eg. memory=HOST_RAM*.1,HOST_RAM*.2,HOST_RAM*.3,HOST_RAM*.4)."
                + " The default test resources specified by this flag are overridden by explicit"
                + " resources specified in tags.")
    // We need to store these as Pair(s) instead of Map.Entry(s) so that they are serializable.
    public List<Pair<String, Map<TestSize, Double>>> testResources;

    @Option(
        name = "test_filter",
        allowMultiple = false,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help =
            "Specifies a filter to forward to the test framework.  Used to limit "
                + "the tests run. Note that this does not affect which targets are built.")
    public String testFilter;

    @Option(
        name = "test_runner_fail_fast",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help =
            "Forwards fail fast option to the test runner. The test runner should stop execution"
                + " upon first failure.")
    public boolean testRunnerFailFast;

    @Option(
        name = "cache_test_results",
        defaultValue = "auto",
        abbrev = 't', // it's useful to toggle this on/off quickly
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help =
            "If set to 'auto', Bazel reruns a test if and only if: "
                + "(1) Bazel detects changes in the test or its dependencies, "
                + "(2) the test is marked as external, "
                + "(3) multiple test runs were requested with --runs_per_test, or"
                + "(4) the test previously failed. "
                + "If set to 'yes', Bazel caches all test results except for tests marked as "
                + "external. If set to 'no', Bazel does not cache any test results.")
    public TriState cacheTestResults;

    @Deprecated
    @Option(
        name = "test_result_expiration",
        defaultValue = "-1", // No expiration by default.
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help = "This option is deprecated and has no effect.")
    public int testResultExpiration;

    @Option(
        name = "trim_test_configuration",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        effectTags = {
          OptionEffectTag.LOADING_AND_ANALYSIS,
          OptionEffectTag.LOSES_INCREMENTAL_STATE,
        },
        help =
            "When enabled, test-related options will be cleared below the top level of the build."
                + " When this flag is active, tests cannot be built as dependencies of non-test"
                + " rules, but changes to test-related options will not cause non-test rules to be"
                + " re-analyzed.")
    public boolean trimTestConfiguration;

    @Option(
        name = "experimental_retain_test_configuration_across_testonly",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        effectTags = {
          OptionEffectTag.LOADING_AND_ANALYSIS,
          OptionEffectTag.LOSES_INCREMENTAL_STATE,
        },
        metadataTags = {OptionMetadataTag.EXPERIMENTAL},
        help =
            "When enabled, --trim_test_configuration will not trim the test configuration for rules"
                + " marked testonly=1. This is meant to reduce action conflict issues when non-test"
                + " rules depend on cc_test rules. No effect if --trim_test_configuration is"
                + " false.")
    public boolean experimentalRetainTestConfigurationAcrossTestonly;

    @Option(
        name = "test_arg",
        allowMultiple = true,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help =
            "Specifies additional options and arguments that should be passed to the test "
                + "executable. Can be used multiple times to specify several arguments. "
                + "If multiple tests are executed, each of them will receive identical arguments. "
                + "Used only by the 'bazel test' command.")
    public List<String> testArguments;

    @Option(
        name = "test_sharding_strategy",
        defaultValue = "explicit",
        converter = ShardingStrategyConverter.class,
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help =
            "Specify strategy for test sharding: "
                + "'explicit' to only use sharding if the 'shard_count' BUILD attribute is "
                + "present. 'disabled' to never use test sharding. 'forced=k' to enforce 'k' "
                + "shards for testing regardless of the 'shard_count' BUILD attribute.")
    public TestShardingStrategy testShardingStrategy;

    @Option(
        name = "runs_per_test",
        allowMultiple = true,
        defaultValue = "1",
        converter = RunsPerTestConverter.class,
        documentationCategory = OptionDocumentationCategory.TESTING,
        effectTags = {OptionEffectTag.UNKNOWN},
        help =
            "Specifies number of times to run each test. If any of those attempts fail for any"
                + " reason, the whole test is considered failed. Normally the value specified is"
                + " just an integer. Example: --runs_per_test=3 will run all tests 3 times."
                + " Alternate syntax: regex_filter@runs_per_test. Where runs_per_test stands for"
                + " an integer value and regex_filter stands for a list of include and exclude"
                + " regular expression patterns (Also see --instrumentation_filter). Example:"
                + " --runs_per_test=//foo/.*,-//foo/bar/.*@3 runs all tests in //foo/ except those"
                + " under foo/bar three times. This option can be passed multiple times. The most"
                + " recently passed argument that matches takes precedence. If nothing matches,"
                + " the test is only run once.")
    public List<PerLabelOptions> runsPerTest;

    @Option(
        name = "runs_per_test_detects_flakes",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help =
            "If true, any shard in which at least one run/attempt passes and at least one "
                + "run/attempt fails gets a FLAKY status.")
    public boolean runsPerTestDetectsFlakes;

    @Option(
        name = "experimental_cancel_concurrent_tests",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.LOADING_AND_ANALYSIS},
        metadataTags = {OptionMetadataTag.EXPERIMENTAL},
        help =
            "If true, then Blaze will cancel concurrently running tests on the first successful "
                + "run. This is only useful in combination with --runs_per_test_detects_flakes.")
    public boolean cancelConcurrentTests;

    @Option(
        name = "coverage_support",
        converter = LabelConverter.class,
        defaultValue = "@bazel_tools//tools/test:coverage_support",
        documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
        effectTags = {
          OptionEffectTag.CHANGES_INPUTS,
          OptionEffectTag.AFFECTS_OUTPUTS,
          OptionEffectTag.LOADING_AND_ANALYSIS
        },
        help =
            "Location of support files that are required on the inputs of every test action "
                + "that collects code coverage. Defaults to '//tools/test:coverage_support'.")
    public Label coverageSupport;

    @Option(
        name = "experimental_fetch_all_coverage_outputs",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.LOADING_AND_ANALYSIS},
        metadataTags = {OptionMetadataTag.EXPERIMENTAL},
        help =
            "If true, then Bazel fetches the entire coverage data directory for each test during a "
                + "coverage run.")
    public boolean fetchAllCoverageOutputs;

    @Option(
        name = "incompatible_exclusive_test_sandboxed",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help =
            "If true, exclusive tests will run with sandboxed strategy. Add 'local' tag to force "
                + "an exclusive test run locally")
    public boolean incompatibleExclusiveTestSandboxed;

    @Option(
        name = "experimental_split_coverage_postprocessing",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = {OptionEffectTag.EXECUTION},
        metadataTags = {OptionMetadataTag.EXPERIMENTAL},
        help = "If true, then Bazel will run coverage postprocessing for test in a new spawn.")
    public boolean splitCoveragePostProcessing;

    @Option(
        name = "zip_undeclared_test_outputs",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.TESTING,
        effectTags = {OptionEffectTag.TEST_RUNNER},
        help = "If true, undeclared test outputs will be archived in a zip file.")
    public boolean zipUndeclaredTestOutputs;

    @Option(
        name = "use_target_platform_for_tests",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = {OptionEffectTag.EXECUTION},
        help =
            "If true, then Bazel will use the target platform for running tests rather than "
                + "the test exec group.")
    public boolean useTargetPlatformForTests;

    @Option(
        name = "incompatible_check_sharding_support",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        effectTags = {OptionEffectTag.UNKNOWN},
        help =
            "If true, Bazel will fail a sharded test if the test runner does not indicate that it "
                + "supports sharding by touching the file at the path in TEST_SHARD_STATUS_FILE. "
                + "If false, a test runner that does not support sharding will lead to all tests "
                + "running in each shard.")
    public boolean checkShardingSupport;

    @Option(
        name = "allow_local_tests",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.EXECUTION},
        help = "If true, Bazel will allow local tests to run.")
    public boolean allowLocalTests;

    @Override
    public TestOptions getNormalized() {
      TestOptions result = (TestOptions) clone();
      result.testEnvironment = normalizeEntries(testEnvironment);
      return result;
    }
  }

  private final TestOptions options;
  private final ImmutableMap<TestTimeout, Duration> testTimeout;
  private final boolean shouldInclude;
  private final ImmutableMap<TestSize, ImmutableMap<String, Double>> testResources;

  public TestConfiguration(BuildOptions buildOptions) {
    this.options = buildOptions.get(TestOptions.class);
    if (options != null) {
      this.shouldInclude = true;
      this.testTimeout = ImmutableMap.copyOf(options.testTimeout);
      ImmutableMap.Builder<TestSize, ImmutableMap<String, Double>> testResources =
          ImmutableMap.builderWithExpectedSize(TestSize.values().length);
      for (TestSize size : TestSize.values()) {
        ImmutableMap.Builder<String, Double> resources = ImmutableMap.builder();
        for (Pair<String, Map<TestSize, Double>> resource : options.testResources) {
          resources.put(resource.getFirst(), resource.getSecond().get(size));
        }
        testResources.put(size, resources.buildKeepingLast());
      }
      this.testResources = testResources.buildOrThrow();
    } else {
      this.shouldInclude = false;
      this.testTimeout = null;
      this.testResources = ImmutableMap.of();
    }
  }

  @Override
  public boolean shouldInclude() {
    return shouldInclude;
  }

  /** Returns test timeout mapping as set by --test_timeout options. */
  public ImmutableMap<TestTimeout, Duration> getTestTimeout() {
    return testTimeout;
  }

  /** Returns test resource mapping as set by --default_test_resources options. */
  public ImmutableMap<String, Double> getTestResources(TestSize size) {
    return testResources.getOrDefault(size, ImmutableMap.of());
  }

  public String getTestFilter() {
    return options.testFilter;
  }

  public boolean getTestRunnerFailFast() {
    return options.testRunnerFailFast;
  }

  public TriState cacheTestResults() {
    return options.cacheTestResults;
  }

  public List<String> getTestArguments() {
    return options.testArguments;
  }

  public TestShardingStrategy testShardingStrategy() {
    return options.testShardingStrategy;
  }

  public Label getCoverageSupport() {
    return options.coverageSupport;
  }

  /**
   * @return number of times the given test should run. If the test doesn't match any of the
   *     filters, runs it once.
   */
  public int getRunsPerTestForLabel(Label label) {
    for (PerLabelOptions perLabelRuns : Lists.reverse(options.runsPerTest)) {
      if (perLabelRuns.isIncluded(label)) {
        return Integer.parseInt(Iterables.getOnlyElement(perLabelRuns.getOptions()));
      }
    }
    return 1;
  }

  public boolean runsPerTestDetectsFlakes() {
    return options.runsPerTestDetectsFlakes;
  }

  public boolean cancelConcurrentTests() {
    return options.cancelConcurrentTests;
  }

  public boolean fetchAllCoverageOutputs() {
    return options.fetchAllCoverageOutputs;
  }

  public boolean incompatibleExclusiveTestSandboxed() {
    return options.incompatibleExclusiveTestSandboxed;
  }

  public boolean splitCoveragePostProcessing() {
    return options.splitCoveragePostProcessing;
  }

  public boolean getZipUndeclaredTestOutputs() {
    return options.zipUndeclaredTestOutputs;
  }

  public boolean useTargetPlatformForTests() {
    return options.useTargetPlatformForTests;
  }

  public boolean checkShardingSupport() {
    return options.checkShardingSupport;
  }

  public boolean allowLocalTests() {
    return options.allowLocalTests;
  }

  /**
   * Option converter that han handle two styles of value for "--runs_per_test":
   *
   * <ul>
   *   <li>--runs_per_test=NUMBER: Run each test NUMBER times.
   *   <li>--runs_per_test=test_regex@NUMBER: Run each test that matches test_regex NUMBER times.
   *       This form can be repeated with multiple regexes.
   * </ul>
   */
  public static class RunsPerTestConverter extends PerLabelOptions.PerLabelOptionsConverter {
    @Override
    public PerLabelOptions convert(String input) throws OptionsParsingException {
      try {
        return parseAsInteger(input);
      } catch (NumberFormatException ignored) {
        return parseAsRegex(input);
      }
    }

    private PerLabelOptions parseAsInteger(String input)
        throws NumberFormatException, OptionsParsingException {
      int numericValue = Integer.parseInt(input);
      if (numericValue <= 0) {
        throw new OptionsParsingException("'" + input + "' should be >= 1");
      } else {
        RegexFilter catchAll =
            new RegexFilter(Collections.singletonList(".*"), Collections.<String>emptyList());
        return new PerLabelOptions(catchAll, Collections.singletonList(input));
      }
    }

    private PerLabelOptions parseAsRegex(String input) throws OptionsParsingException {
      PerLabelOptions testRegexps = super.convert(input);
      if (testRegexps.getOptions().size() != 1) {
        throw new OptionsParsingException("'" + input + "' has multiple runs for a single pattern");
      }
      String runsPerTest = Iterables.getOnlyElement(testRegexps.getOptions());
      try {
        int numericRunsPerTest = Integer.parseInt(runsPerTest);
        if (numericRunsPerTest <= 0) {
          throw new OptionsParsingException("'" + input + "' has a value < 1");
        }
      } catch (NumberFormatException e) {
        throw new OptionsParsingException("'" + input + "' has a non-numeric value", e);
      }
      return testRegexps;
    }

    @Override
    public String getTypeDescription() {
      return "a positive integer or test_regex@runs. This flag may be passed more than once";
    }
  }
}
