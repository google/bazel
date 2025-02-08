// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.devtools.build.lib.profiler.MemoryProfiler.MemoryProfileStableHeapParameters;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.runtime.CommandLineEvent.ToolCommandLineEvent;
import com.google.devtools.build.lib.util.OptionsUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.Converters.AssignmentConverter;
import com.google.devtools.common.options.Converters.DurationConverter;
import com.google.devtools.common.options.EnumConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.TriState;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nullable;

/** Options common to all commands. */
public class CommonCommandOptions extends OptionsBase {

  // It's by design that this field is unused: this command line option takes effect by reading its
  // value during options parsing based on its (string) name.
  @Option(
      name = "enable_platform_specific_config",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "If true, Bazel picks up host-OS-specific config lines from bazelrc files. For example, "
              + "if the host OS is Linux and you run bazel build, Bazel picks up lines starting "
              + "with build:linux. Supported OS identifiers are linux, macos, windows, freebsd, "
              + "and openbsd. Enabling this flag is equivalent to using --config=linux on Linux, "
              + "--config=windows on Windows, etc.")
  public boolean enablePlatformSpecificConfig;

  @Option(
      name = "config",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      allowMultiple = true,
      help =
          "Selects additional config sections from the rc files; for every <command>, it "
              + "also pulls in the options from <command>:<config> if such a section exists; "
              + "if this section doesn't exist in any .rc file, Blaze fails with an error. "
              + "The config sections and flag combinations they are equivalent to are "
              + "located in the tools/*.blazerc config files.")
  public List<String> configs;

  @Option(
      name = "logging",
      defaultValue = "3", // Level.INFO
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      converter = Converters.LogLevelConverter.class,
      help = "The logging level.")
  public Level verbosity;

  @Option(
      name = "client_cwd",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      metadataTags = {OptionMetadataTag.HIDDEN},
      effectTags = {OptionEffectTag.CHANGES_INPUTS},
      converter = OptionsUtils.PathFragmentConverter.class,
      help = "A system-generated parameter which specifies the client's working directory")
  public PathFragment clientCwd;

  @Option(
      name = "announce_rc",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help = "Whether to announce rc options.")
  public boolean announceRcOptions;

  @Option(
      name = "always_profile_slow_operations",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help = "Whether profiling slow operations is always turned on")
  public boolean alwaysProfileSlowOperations;

  @Option(
      name = "experimental_install_base_gc_max_age",
      defaultValue = "30d",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS},
      converter = DurationConverter.class,
      help =
          "How long an install base must go unused before it's eligible for garbage collection."
              + " If nonzero, the server will attempt to garbage collect other install bases when"
              + " idle.")
  public Duration installBaseGcMaxAge;

  /** Converter for UUID. Accepts values as specified by {@link UUID#fromString(String)}. */
  public static class UUIDConverter extends Converter.Contextless<UUID> {

    @Override
    @Nullable
    public UUID convert(String input) throws OptionsParsingException {
      if (isNullOrEmpty(input)) {
        return null;
      }
      try {
        return UUID.fromString(input);
      } catch (IllegalArgumentException e) {
        throw new OptionsParsingException(
            String.format("Value '%s' is not a value UUID.", input), e);
      }
    }

    @Override
    public String getTypeDescription() {
      return "a UUID";
    }
  }

  /**
   * Converter for options (--build_request_id) that accept prefixed UUIDs. Since we do not care
   * about the structure of this value after validation, we store it as a string.
   */
  public static class PrefixedUUIDConverter extends Converter.Contextless<String> {

    @Override
    @Nullable
    public String convert(String input) throws OptionsParsingException {
      if (isNullOrEmpty(input)) {
        return null;
      }
      // UUIDs that are accepted by UUID#fromString have 36 characters. Interpret the last 36
      // characters as an UUID and the rest as a prefix. We do not check anything about the contents
      // of the prefix.
      try {
        int uuidStartIndex = input.length() - 36;
        UUID.fromString(input.substring(uuidStartIndex));
      } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
        throw new OptionsParsingException(
            String.format("Value '%s' does not end in a valid UUID.", input), e);
      }
      return input;
    }

    @Override
    public String getTypeDescription() {
      return "An optionally prefixed UUID. The last 36 characters will be verified as a UUID.";
    }
  }

  // Command ID and build request ID can be set either by flag or environment variable. In most
  // cases, the internally generated ids should be sufficient, but we allow these to be set
  // externally if required. Option wins over environment variable, if both are set.
  // TODO(b/67895628) Stop reading ids from the environment after the compatibility window has
  // passed.
  @Option(
      name = "invocation_id",
      defaultValue = "",
      converter = UUIDConverter.class,
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.BAZEL_MONITORING, OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help =
          "Unique identifier, in UUID format, for the command being run. If explicitly specified"
              + " uniqueness must be ensured by the caller. The UUID is printed to stderr, the BEP"
              + " and remote execution protocol.")
  public UUID invocationId;

  @Option(
      name = "build_request_id",
      defaultValue = "",
      converter = PrefixedUUIDConverter.class,
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BAZEL_MONITORING, OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      metadataTags = {OptionMetadataTag.HIDDEN},
      help = "Unique string identifier for the build being run.")
  public String buildRequestId;

  @Option(
      name = "build_metadata",
      converter = AssignmentConverter.class,
      defaultValue = "null",
      allowMultiple = true,
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help = "Custom key-value string pairs to supply in a build event.")
  public List<Map.Entry<String, String>> buildMetadata;

  @Option(
      name = "oom_message",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BAZEL_MONITORING, OptionEffectTag.TERMINAL_OUTPUT},
      metadataTags = {OptionMetadataTag.HIDDEN},
      help = "Custom message to be emitted on an out of memory failure.")
  public String oomMessage;

  @Option(
      name = "generate_json_trace_profile",
      oldName = "experimental_generate_json_trace_profile",
      defaultValue = "auto",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      help =
          "If enabled, Bazel profiles the build and writes a JSON-format profile into a file in"
              + " the output base. View profile by loading into chrome://tracing. By default Bazel"
              + " writes the profile for all build-like commands and query.")
  public TriState enableTracer;

  @Option(
      name = "experimental_profile_additional_tasks",
      converter = ProfilerTaskConverter.class,
      defaultValue = "null",
      allowMultiple = true,
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      help = "Specifies additional profile tasks to be included in the profile.")
  public List<ProfilerTask> additionalProfileTasks;

  @Option(
      name = "slim_profile",
      oldName = "experimental_slim_json_profile",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      help =
          "Slims down the size of the JSON profile by merging events if the profile gets "
              + " too large.")
  public boolean slimProfile;

  @Option(
      name = "experimental_profile_include_primary_output",
      oldName = "experimental_include_primary_output",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      help =
          "Includes the extra \"out\" attribute in action events that contains the exec path "
              + "to the action's primary output.")
  public boolean includePrimaryOutput;

  @Option(
      name = "experimental_profile_include_target_label",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      help = "Includes target label in action events' JSON profile data.")
  public boolean profileIncludeTargetLabel;

  @Option(
      name = "experimental_profile_include_target_configuration",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      help = "Includes target configuration hash in action events' JSON profile data.")
  public boolean profileIncludeTargetConfiguration;

  @Option(
      name = "profiles_to_retain",
      defaultValue = "5",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      help =
          "Number of profiles to retain in the output base. If there are more than this number of"
              + " profiles in the output base, the oldest are deleted until the total is under the"
              + " limit.")
  public int profilesToRetain;

  @Option(
      name = "profile",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      converter = OptionsUtils.PathFragmentConverter.class,
      help =
          "If set, profile Bazel and write data to the specified file. See"
              + " https://bazel.build/advanced/performance/json-trace-profile for more"
              + " information.")
  public PathFragment profilePath;

  @Option(
      name = "starlark_cpu_profile",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      help = "Writes into the specified file a pprof profile of CPU usage by all Starlark threads.")
  public String starlarkCpuProfile;

  @Option(
      name = "record_full_profiler_data",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      help =
          "By default, Bazel profiler will record only aggregated data for fast but numerous "
              + "events (such as statting the file). If this option is enabled, profiler will "
              + "record each event - resulting in more precise profiling data but LARGE "
              + "performance hit. Option only has effect if --profile used as well.")
  public boolean recordFullProfilerData;

  @Option(
      name = "experimental_collect_worker_data_in_profiler",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      help = "If enabled, the profiler collects worker's aggregated resource data.")
  public boolean collectWorkerDataInProfiler;

  @Option(
      name = "experimental_collect_load_average_in_profiler",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      help = "If enabled, the profiler collects the system's overall load average.")
  public boolean collectLoadAverageInProfiler;

  @Option(
      name = "experimental_collect_system_network_usage",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      help = "If enabled, the profiler collects the system's network usage.")
  public boolean collectSystemNetworkUsage;

  @Option(
      name = "experimental_collect_resource_estimation",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      help = "If enabled, the profiler collects CPU and memory usage estimation for local actions.")
  public boolean collectResourceEstimation;

  @Option(
      name = "experimental_collect_pressure_stall_indicators",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      help = "If enabled, the profiler collects the Linux PSI data.")
  public boolean collectPressureStallIndicators;

  @Option(
      name = "experimental_collect_skyframe_counts_in_profiler",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      help =
          "If enabled, the profiler collects SkyFunction counts in the Skyframe graph over time for"
              + " key function types, like configured targets and action executions. May have a"
              + " performance hit as this visits the ENTIRE Skyframe graph at every profiling time"
              + " unit. Do not use this flag with performance-critical measurements.")
  public boolean collectSkyframeCounts;

  @Option(
      name = "memory_profile",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      converter = OptionsUtils.PathFragmentConverter.class,
      help =
          "If set, write memory usage data to the specified file at phase ends and stable heap to"
              + " master log at end of build.")
  public PathFragment memoryProfilePath;

  @Option(
      name = "memory_profile_stable_heap_parameters",
      defaultValue = "1,0",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      converter = MemoryProfileStableHeapParameters.Converter.class,
      help =
          "Tune memory profile's computation of stable heap at end of build. Should be and even"
              + " number of  integers separated by commas. In each pair the first integer is the"
              + " number of GCs to perform. The second integer in each pair is the number of"
              + " seconds to wait between GCs. Ex: 2,4,4,0 would 2 GCs with a 4sec pause, followed"
              + " by 4 GCs with zero second pause")
  public MemoryProfileStableHeapParameters memoryProfileStableHeapParameters;

  @Option(
      name = "heap_dump_on_oom",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      help =
          "Whether to manually output a heap dump if an OOM is thrown (including manual OOMs due to"
              + " reaching --gc_thrashing_limits). The dump will be written to"
              + " <output_base>/<invocation_id>.heapdump.hprof. This option effectively replaces"
              + " -XX:+HeapDumpOnOutOfMemoryError, which has no effect for manual OOMs.")
  public boolean heapDumpOnOom;

  @Option(
      name = "startup_time",
      defaultValue = "0",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.BAZEL_MONITORING},
      metadataTags = {OptionMetadataTag.HIDDEN},
      help = "The time in ms the launcher spends before sending the request to the bazel server.")
  public long startupTime;

  @Option(
      name = "extract_data_time",
      defaultValue = "0",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.BAZEL_MONITORING},
      metadataTags = {OptionMetadataTag.HIDDEN},
      help = "The time in ms spent on extracting the new bazel version.")
  public long extractDataTime;

  @Option(
      name = "command_wait_time",
      defaultValue = "0",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.BAZEL_MONITORING},
      metadataTags = {OptionMetadataTag.HIDDEN},
      help = "The time in ms a command had to wait on a busy Bazel server process.")
  public long waitTime;

  @Option(
      name = "tool_tag",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.BAZEL_MONITORING},
      help = "A tool name to attribute this Bazel invocation to.")
  public String toolTag;

  @Option(
      name = "restart_reason",
      defaultValue = "no_restart",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.BAZEL_MONITORING},
      metadataTags = {OptionMetadataTag.HIDDEN},
      help = "The reason for the server restart.")
  public String restartReason;

  @Option(
      name = "binary_path",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.BAZEL_MONITORING},
      metadataTags = {OptionMetadataTag.HIDDEN},
      help = "The absolute path of the bazel binary.")
  public String binaryPath;

  @Option(
      name = "experimental_allow_project_files",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.CHANGES_INPUTS},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL, OptionMetadataTag.HIDDEN},
      help = "Enable processing of +<file> parameters.")
  public boolean allowProjectFiles;

  // We could accept multiple of these, in the event where there's a chain of tools that led to a
  // Bazel invocation. We would not want to expect anything from the order of these, and would need
  // to guarantee that the "label" for each command line is unique. Unless a need is demonstrated,
  // though, logs are a better place to track this information than flags, so let's try to avoid it.
  @Option(
      // In May 2018, this feature will have been out for 6 months. If the format we accept has not
      // changed in that time, we can remove the "experimental" prefix and tag.
      name = "experimental_tool_command_line",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      // Keep this flag HIDDEN so that it is not listed with our reported command lines, it being
      // reported separately.
      metadataTags = {OptionMetadataTag.EXPERIMENTAL, OptionMetadataTag.HIDDEN},
      converter = ToolCommandLineEvent.Converter.class,
      help =
          "An extra command line to report with this invocation's command line. Useful for tools "
              + "that invoke Bazel and want the original information that the tool received to be "
              + "logged with the rest of the Bazel invocation.")
  public ToolCommandLineEvent toolCommandLine;

  @Option(
      name = "unconditional_warning",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      allowMultiple = true,
      help =
          "A warning that will unconditionally get printed with build warnings and errors. This is"
              + " useful to deprecate bazelrc files or --config definitions. If the intent is to"
              + " effectively deprecate some flag or combination of flags, this is NOT sufficient."
              + " The flag or flags should use the deprecationWarning field in the option"
              + " definition, or the bad combination should be checked for programmatically.")
  public List<String> deprecationWarnings;

  @Option(
      name = "track_incremental_state",
      oldName = "keep_incrementality_data",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
      effectTags = {OptionEffectTag.LOSES_INCREMENTAL_STATE},
      help =
          "If false, Blaze will not persist data that allows for invalidation and re-evaluation "
              + "on incremental builds in order to save memory on this build. Subsequent builds "
              + "will not have any incrementality with respect to this one. Usually you will want "
              + "to specify --batch when setting this to false.")
  public boolean trackIncrementalState;

  @Option(
      name = "keep_state_after_build",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
      effectTags = {OptionEffectTag.LOSES_INCREMENTAL_STATE},
      help =
          "If false, Blaze will discard the inmemory state from this build when the build "
              + "finishes. Subsequent builds will not have any incrementality with respect to this "
              + "one.")
  public boolean keepStateAfterBuild;

  @Option(
      name = "repo_env",
      converter = Converters.OptionalAssignmentConverter.class,
      allowMultiple = true,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES},
      help =
          "Specifies additional environment variables to be available only for repository rules."
              + " Note that repository rules see the full environment anyway, but in this way"
              + " configuration information can be passed to repositories through options without"
              + " invalidating the action graph.")
  public List<Map.Entry<String, String>> repositoryEnvironment;

  @Option(
      name = "heuristically_drop_nodes",
      oldName = "experimental_heuristically_drop_nodes",
      oldNameWarning = false,
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
      effectTags = {OptionEffectTag.LOSES_INCREMENTAL_STATE},
      help =
          "If true, Blaze will remove FileState and DirectoryListingState nodes after related File"
              + " and DirectoryListing node is done to save memory. We expect that it is less"
              + " likely that these nodes will be needed again. If so, the program will re-evaluate"
              + " them.")
  public boolean heuristicallyDropNodes;

  @Option(
      name = "http_timeout_scaling",
      defaultValue = "1.0",
      documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help = "Scale all timeouts related to http downloads by the given factor")
  public double httpTimeoutScaling;

  @Option(
      name = "http_connector_attempts",
      defaultValue = "8",
      documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help = "The maximum number of attempts for http downloads.")
  public int httpConnectorAttempts;

  @Option(
      name = "http_connector_retry_max_timeout",
      defaultValue = "0s",
      documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help =
          "The maximum timeout for http download retries. With a value of 0, no timeout maximum is"
              + " defined.")
  public Duration httpConnectorRetryMaxTimeout;

  @Option(
      name = "http_max_parallel_downloads",
      defaultValue = "8",
      documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help = "The maximum number parallel http downloads.")
  public int httpMaxParallelDownloads;

  /** The option converter to check that the user can only specify legal profiler tasks. */
  public static class ProfilerTaskConverter extends EnumConverter<ProfilerTask> {
    public ProfilerTaskConverter() {
      super(ProfilerTask.class, "profiler task");
    }
  }

  @Option(
      name = "redirect_local_instrumentation_output_writes",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_MONITORING},
      help =
          "If true and supported, instrumentation output is redirected to be written locally on a"
              + " different machine than where bazel is running on.")
  public boolean redirectLocalInstrumentationOutputWrites;
}
