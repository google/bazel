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
package com.google.devtools.build.lib.buildtool;

import com.github.benmanes.caffeine.cache.CaffeineSpec;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.util.OptionsUtils;
import com.google.devtools.build.lib.util.ResourceConverter;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.BoolOrEnumConverter;
import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.Converters.CaffeineSpecConverter;
import com.google.devtools.common.options.Converters.PercentageConverter;
import com.google.devtools.common.options.Converters.RangeConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.RegexPatternOption;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Options interface for {@link BuildRequest}: can be used to parse command-line arguments.
 *
 * <p>See also {@code ExecutionOptions}; from the user's point of view, there's no qualitative
 * difference between these two sets of options.
 */
public class BuildRequestOptions extends OptionsBase {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private static final int JOBS_TOO_HIGH_WARNING = 2500;
  @VisibleForTesting public static final int MAX_JOBS = 5000;

  /* "Execution": options related to the execution of a build: */

  @Option(
      name = "jobs",
      abbrev = 'j',
      defaultValue = "auto",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS, OptionEffectTag.EXECUTION},
      converter = JobsConverter.class,
      help =
          "The number of concurrent jobs to run. Takes "
              + ResourceConverter.FLAG_SYNTAX
              + ". Values must be between 1 and "
              + MAX_JOBS
              + ". Values above "
              + JOBS_TOO_HIGH_WARNING
              + " may cause memory issues. \"auto\" calculates a reasonable default based on"
              + " host resources.")
  public int jobs;

  @Option(
      name = "experimental_use_semaphore_for_jobs",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS, OptionEffectTag.EXECUTION},
      help = "If set to true, additionally use semaphore to limit number of concurrent jobs.")
  public boolean useSemaphoreForJobs;

  @Option(
      name = "experimental_async_execution",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      metadataTags = OptionMetadataTag.INCOMPATIBLE_CHANGE,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help =
          "If set to true, Bazel is allowed to run action in a virtual thread. The number of"
              + " actions in flight is still capped with --jobs.")
  public boolean useAsyncExecution;

  @Option(
      name = "progress_report_interval",
      defaultValue = "0",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      converter = ProgressReportIntervalConverter.class,
      help =
          "The number of seconds to wait between reports on still running jobs. The "
              + "default value 0 means the first report will be printed after 10 "
              + "seconds, then 30 seconds and after that progress is reported once every minute. "
              + "When --curses is enabled, progress is reported every second.")
  public int progressReportInterval;

  @Option(
      name = "explain",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      converter = OptionsUtils.PathFragmentConverter.class,
      help =
          "Causes the build system to explain each executed step of the "
              + "build. The explanation is written to the specified log file.")
  public PathFragment explanationPath;

  @Option(
      name = "verbose_explanations",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Increases the verbosity of the explanations issued if --explain is enabled. "
              + "Has no effect if --explain is not enabled.")
  public boolean verboseExplanations;

  @Option(
      name = "output_filter",
      converter = Converters.RegexPatternConverter.class,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Only shows warnings and action outputs for rules with a name matching the provided "
              + "regular expression.")
  @Nullable
  public RegexPatternOption outputFilter;

  @Option(
      name = "analyze",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Execute the loading/analysis phase; this is the usual behaviour. Specifying --noanalyze"
              + "causes the build to stop before starting the loading/analysis phase, just doing "
              + "target pattern parsing and returning zero iff that completed successfully; this "
              + "mode is useful for testing.")
  public boolean performAnalysisPhase;

  @Option(
      name = "build",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
      effectTags = {OptionEffectTag.EXECUTION, OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Execute the build; this is the usual behaviour. "
              + "Specifying --nobuild causes the build to stop before executing the build "
              + "actions, returning zero iff the package loading and analysis phases completed "
              + "successfully; this mode is useful for testing those phases.")
  public boolean performExecutionPhase;

  @Option(
      name = "output_groups",
      converter = Converters.CommaSeparatedOptionListConverter.class,
      allowMultiple = true,
      documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
      effectTags = {OptionEffectTag.EXECUTION, OptionEffectTag.AFFECTS_OUTPUTS},
      defaultValue = "null",
      help =
          "A list of comma-separated output group names, each of which optionally prefixed by a +"
              + " or a -. A group prefixed by + is added to the default set of output groups,"
              + " while a group prefixed by - is removed from the default set. If at least one"
              + " group is not prefixed, the default set of output groups is omitted. For example,"
              + " --output_groups=+foo,+bar builds the union of the default set, foo, and bar,"
              + " while --output_groups=foo,bar overrides the default set such that only foo and"
              + " bar are built.")
  public List<String> outputGroups;

  @Option(
      name = "run_validations",
      oldName = "experimental_run_validations",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
      effectTags = {OptionEffectTag.EXECUTION, OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Whether to run validation actions as part of the build. See"
              + " https://bazel.build/extending/rules#validation_actions")
  public boolean runValidationActions;

  @Option(
      name = "experimental_use_validation_aspect",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
      effectTags = {OptionEffectTag.EXECUTION, OptionEffectTag.AFFECTS_OUTPUTS},
      help = "Whether to run validation actions using aspect (for parallelism with tests).")
  public boolean useValidationAspect;

  @Option(
      name = "show_result",
      defaultValue = "1",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Show the results of the build.  For each target, state whether or not it was brought"
              + " up-to-date, and if so, a list of output files that were built.  The printed files"
              + " are convenient strings for copy+pasting to the shell, to execute them.\n"
              + "This option requires an integer argument, which is the threshold number of targets"
              + " above which result information is not printed. Thus zero causes suppression of"
              + " the message and MAX_INT causes printing of the result to occur always. The"
              + " default is one.\n"
              + "If nothing was built for a target its results may be omitted to keep the output"
              + " under the threshold.")
  public int maxResultTargets;

  @Option(
      name = "hide_aspect_results",
      converter = Converters.CommaSeparatedOptionListConverter.class,
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "Comma-separated list of aspect names to not display in results (see --show_result). "
              + "Useful for keeping aspects added by wrappers which are typically not interesting "
              + "to end users out of console output.")
  public List<String> hideAspectResults;

  @Option(
      name = "symlink_prefix",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "The prefix that is prepended to any of the convenience symlinks that are created "
              + "after a build. If omitted, the default value is the name of the build tool "
              + "followed by a hyphen. If '/' is passed, then no symlinks are created and no "
              + "warning is emitted. Warning: the special functionality for '/' will be deprecated "
              + "soon; use --experimental_convenience_symlinks=ignore instead.")
  @Nullable
  public String symlinkPrefix;

  @Option(
      name = "experimental_convenience_symlinks",
      converter = ConvenienceSymlinksConverter.class,
      defaultValue = "normal",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "This flag controls how the convenience symlinks (the symlinks that appear in the "
              + "workspace after the build) will be managed. Possible values:\n"
              + "  normal (default): Each kind of convenience symlink will be created or deleted, "
              + "as determined by the build.\n"
              + "  clean: All symlinks will be unconditionally deleted.\n"
              + "  ignore: Symlinks will not be created or cleaned up.\n"
              + "  log_only: Generate log messages as if 'normal' were passed, but don't actually "
              + "perform any filesystem operations (useful for tools).\n"
              + "Note that only symlinks whose names are generated by the current value of "
              + "--symlink_prefix can be affected; if the prefix changes, any pre-existing "
              + "symlinks will be left alone.")
  @Nullable
  public ConvenienceSymlinksMode experimentalConvenienceSymlinks;

  @Option(
      name = "experimental_convenience_symlinks_bep_event",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "This flag controls whether or not we will post the build event"
              + "ConvenienceSymlinksIdentified to the BuildEventProtocol. If the value is true, "
              + "the BuildEventProtocol will have an entry for convenienceSymlinksIdentified, "
              + "listing all of the convenience symlinks created in your workspace. If false, then "
              + "the convenienceSymlinksIdentified entry in the BuildEventProtocol will be empty.")
  public boolean experimentalConvenienceSymlinksBepEvent;

  @Option(
      name = "output_tree_tracking",
      oldName = "experimental_output_tree_tracking",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help =
          "If set, tell the output service (if any) to track when files in the output "
              + "tree have been modified externally (not by the build system). "
              + "This should improve incremental build speed when an appropriate output service "
              + "is enabled.")
  public boolean finalizeActions;

  @Option(
      name = "directory_creation_cache",
      defaultValue = "maximumSize=100000",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.EXECUTION},
      converter = CaffeineSpecConverter.class,
      help =
          "Describes the cache used to store known regular directories as they're created. Parent"
              + " directories of output files are created on-demand during action execution.")
  public CaffeineSpec directoryCreationCacheSpec;

  @Option(
      name = "aspects",
      converter = Converters.CommaSeparatedOptionListConverter.class,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.UNKNOWN},
      allowMultiple = true,
      help =
          "Comma-separated list of aspects to be applied to top-level targets. In the list, if"
              + " aspect some_aspect specifies required aspect providers via"
              + " required_aspect_providers, some_aspect will run after"
              + " every aspect that was mentioned before it in the aspects list whose advertised"
              + " providers satisfy some_aspect required aspect providers. Moreover,"
              + " some_aspect will run after all its required aspects specified by"
              + " requires attribute."
              + " some_aspect will then have access to the values of those aspects'"
              + " providers."
              + " <bzl-file-label>%<aspect_name>, for example '//tools:my_def.bzl%my_aspect', where"
              + " 'my_aspect' is a top-level value from a file tools/my_def.bzl")
  public List<String> aspects;

  @Option(
      name = "aspects_parameters",
      converter = Converters.AssignmentConverter.class,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.GENERIC_INPUTS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      allowMultiple = true,
      help =
          "Specifies the values of the command-line aspects parameters. Each parameter value is"
              + " specified via <param_name>=<param_value>, for example 'my_param=my_val' where"
              + " 'my_param' is a parameter of some aspect in --aspects list or required by an"
              + " aspect in the list. This option can be used multiple times. However, it is not"
              + " allowed to assign values to the same parameter more than once.")
  public List<Map.Entry<String, String>> aspectsParameters;

  public BuildRequestOptions() throws OptionsParsingException {}

  public String getSymlinkPrefix(String productName) {
    return symlinkPrefix == null ? productName + "-" : symlinkPrefix;
  }

  @Option(
      name = "use_action_cache",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {
        OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION,
        OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS
      },
      help = "Whether to use the action cache")
  public boolean useActionCache;

  @Option(
      name = "rewind_lost_inputs",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.EXECUTION},
      help = "Whether to use action rewinding to recover from lost inputs.")
  public boolean rewindLostInputs;

  @Option(
      name = "incompatible_skip_genfiles_symlink",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      effectTags = {OptionEffectTag.LOSES_INCREMENTAL_STATE},
      help =
          "If set to true, the genfiles symlink will not be created. For more information, see "
              + "https://github.com/bazelbuild/bazel/issues/8651")
  public boolean incompatibleSkipGenfilesSymlink;

  @Option(
      name = "target_pattern_file",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.GENERIC_INPUTS,
      effectTags = {OptionEffectTag.CHANGES_INPUTS},
      help =
          "If set, build will read patterns from the file named here, rather than on the command "
              + "line. It is an error to specify a file here as well as command-line patterns.")
  public String targetPatternFile;

  /**
   * Do not use directly. Instead use {@link
   * com.google.devtools.build.lib.runtime.CommandEnvironment#withMergedAnalysisAndExecutionSourceOfTruth()}.
   */
  @Option(
      name = "experimental_merged_skyframe_analysis_execution",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      metadataTags = OptionMetadataTag.EXPERIMENTAL,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.EXECUTION},
      help = "If this flag is set, the analysis and execution phases of Skyframe are merged.")
  public boolean mergedSkyframeAnalysisExecutionDoNotUseDirectly;

  @Option(
      name = "experimental_skymeld_analysis_overlap_percentage",
      defaultValue = "100",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      metadataTags = OptionMetadataTag.EXPERIMENTAL,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.EXECUTION},
      converter = PercentageConverter.class,
      help =
          "The value represents the % of the analysis phase which will be overlapped with the"
              + " execution phase. A value of x means Skyframe will queue up execution tasks and"
              + " wait until there's x% of the top level target left to be analyzed before allowing"
              + " them to launch. When the value is 0%, we'd wait for all analysis to finish before"
              + " executing (no overlap). When it's 100%, the phases are free to overlap as much as"
              + " they can.")
  public int skymeldAnalysisOverlapPercentage;

  /** Converter for filesystem value checker threads. */
  public static class ThreadConverter extends ResourceConverter.IntegerConverter {
    public ThreadConverter() {
      super(/* auto= */ HOST_CPUS_SUPPLIER, /* minValue= */ 1, /* maxValue= */ Integer.MAX_VALUE);
    }
  }

  @Option(
      name = "experimental_fsvc_threads",
      defaultValue = "200",
      converter = ThreadConverter.class,
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      metadataTags = OptionMetadataTag.EXPERIMENTAL,
      effectTags = {OptionEffectTag.EXECUTION},
      help = "The number of threads that are used by the FileSystemValueChecker.")
  public int fsvcThreads;

  @Option(
      name = "experimental_skyframe_memory_dump",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Dump the memory use of individual nodes in the Skyframe graph after the build. This"
              + " option takes a number of flags separated by commas: 'json' (no-op, that's the"
              + " only format), 'notransient' (don't traverse transient fields), 'noconfig' (ignore"
              + " objects related to configurations), 'noprecomputed' (ignore precomputed values)"
              + " and 'noworkspacestatus' (ignore objects related to the workspace status"
              + " machinery)")
  public String skyframeMemoryDump;

  @Option(
      name = "enforce_project_configs",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.AFFECTS_OUTPUTS},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      // TODO: b/341931019 - link to user documentation when available.
      help =
          "If true, interactive builds may only pass the --scl_config build flag; they may not use"
              + " any other build flags. --scl_config must be set to an officially suported project"
              + " configuration. Supported configurations are defined in the target's PROJECT.scl,"
              + " which can be found by walking up the target's packagge path. See b/324126745.")
  public boolean enforceProjectConfigs;

  @Option(
      name = "experimental_aquery_dump_after_build_format",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Writes the state of Skyframe (which includes previous invocations on this blaze instance"
              + " as well) after a build. Output is streamed remotely unless local output is"
              + " requested with --experimental_aquery_dump_after_build_output_file.  Does not"
              + " honor aquery flags for --include_*, but uses the same defaults, except for"
              + " --include_commandline=false. Possible output formats:"
              + " proto|streamed_proto|textproto|jsonproto. Using this will disable Skymeld.")
  @Nullable
  public String aqueryDumpAfterBuildFormat;

  @Option(
      name = "experimental_aquery_dump_after_build_output_file",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      converter = OptionsUtils.PathFragmentConverter.class,
      // NOTE: This produces a very very large amount of data on large builds,
      // very useful for debugging, but may not be used regularly. When removing
      // unused flags based on usage this may not be a good candidate for
      // removal.
      help =
          "Specify the output file for the aquery dump after a build. Use in conjunction with"
              + " --experimental_aquery_dump_after_build_format. The path provided is relative to"
              + " Bazel's output base, unless it's an absolute path. Using this will disable"
              + " Skymeld.")
  @Nullable
  public PathFragment aqueryDumpAfterBuildOutputFile;

  /**
   * Converter for jobs: Takes keyword ({@value #FLAG_SYNTAX}). Values must be between 1 and
   * MAX_JOBS.
   */
  public static class JobsConverter extends ResourceConverter.IntegerConverter {
    public JobsConverter() {
      super(/* auto= */ HOST_CPUS_SUPPLIER, /* minValue= */ 1, /* maxValue= */ MAX_JOBS);
    }

    @Override
    public Integer checkAndLimit(Integer value) throws OptionsParsingException {
      if (value.doubleValue() < minValue) {
        throw new OptionsParsingException(
            String.format("Value '(%d)' must be at least %d.", value, minValue));
      }
      if (value.doubleValue() > maxValue) {
        logger.atWarning().log(
            "Flag remoteWorker \"jobs\" ('%d') was set too high. "
                + "This is a result of passing large values to --local_resources or --jobs. "
                + "Using '%d' jobs",
            value, maxValue);
        return maxValue;
      }
      return value;
    }
  }

  /** Converter for progress_report_interval: [0, 3600]. */
  public static class ProgressReportIntervalConverter extends RangeConverter {
    public ProgressReportIntervalConverter() {
      super(0, 3600);
    }
  }

  /**
   * The {@link BoolOrEnumConverter} for the {@link ConvenienceSymlinksMode} where NORMAL is true
   * and IGNORE is false.
   */
  public static class ConvenienceSymlinksConverter
      extends BoolOrEnumConverter<ConvenienceSymlinksMode> {
    public ConvenienceSymlinksConverter() {
      super(
          ConvenienceSymlinksMode.class,
          "convenience symlinks mode",
          ConvenienceSymlinksMode.NORMAL,
          ConvenienceSymlinksMode.IGNORE);
    }
  }

  /** Determines how the convenience symlinks are presented to the user */
  enum ConvenienceSymlinksMode {
    /** Will manage symlinks based on the symlink prefix. */
    NORMAL,
    /** Will clean up any existing symlinks. */
    CLEAN,
    /** Will not create or clean up any symlinks. */
    IGNORE,
    /** Will not create or clean up any symlinks, but will record the symlinks. */
    LOG_ONLY
  }
}
