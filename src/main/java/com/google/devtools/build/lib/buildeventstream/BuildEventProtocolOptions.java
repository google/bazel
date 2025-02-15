// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.buildeventstream;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.buildeventstream.BuildEventContext.OutputGroupFileMode;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.Converters.AssignmentConverter;
import com.google.devtools.common.options.EnumConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParsingException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/** Options used to configure the build event protocol. */
public class BuildEventProtocolOptions extends OptionsBase {

  @Option(
      name = "legacy_important_outputs",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Use this to suppress generation of the legacy important_outputs field in the "
              + "TargetComplete event. important_outputs are required for Bazel to ResultStore/BTX "
              + "integration.")
  public boolean legacyImportantOutputs;

  @Option(
      name = "experimental_build_event_upload_strategy",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Selects how to upload artifacts referenced in the build event protocol. In Bazel "
              + "the valid options include 'local' and 'remote'. The default value is 'local'.")
  public String buildEventUploadStrategy;

  @Option(
      name = "build_event_upload_max_retries",
      oldName = "experimental_build_event_upload_max_retries",
      defaultValue = "4",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help = "The maximum number of times Bazel should retry uploading a build event.")
  public int besUploadMaxRetries;

  @Option(
      name = "experimental_build_event_upload_retry_minimum_delay",
      defaultValue = "1s",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help =
          "Initial, minimum delay for exponential backoff retries when BEP upload fails. (exponent:"
              + " 1.6)")
  public Duration besUploadRetryInitialDelay;

  @Option(
      name = "experimental_stream_log_file_uploads",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Stream log file uploads directly to the remote storage rather than writing them to"
              + " disk.")
  public boolean streamingLogFileUploads;

  @Option(
      name = "experimental_build_event_expand_filesets",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help = "If true, expand Filesets in the BEP when presenting output files.")
  public boolean expandFilesets;

  @Option(
      name = "experimental_build_event_fully_resolve_fileset_symlinks",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "If true, fully resolve relative Fileset symlinks in the BEP when presenting output"
              + " files. Requires --experimental_build_event_expand_filesets.")
  public boolean fullyResolveFilesetSymlinks;

  @Option(
      name = "experimental_bep_target_summary",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "Whether to publish TargetSummary events.")
  public boolean publishTargetSummary;

  @Option(
      name = "experimental_run_bep_event_include_residue",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Whether to include the command-line residue in run build events which could contain the"
              + " residue. By default, the residue is not included in run command build events that"
              + " could contain the residue.")
  public boolean includeResidueInRunBepEvent;

  /** Simple String to {@link OutputGroupFileMode} Converter. */
  static final class OutputGroupFileModeConverter extends EnumConverter<OutputGroupFileMode> {
    public OutputGroupFileModeConverter() {
      super(OutputGroupFileMode.class, "Output group file reporting mode");
    }
  }

  /**
   * Options converter that parses the assignment of an {@link OutputGroupFileMode} for an output
   * group by name, e.g. {@code default=fileset} or {@code baseline.lcov=inline}.
   */
  static final class BuildEventOutputGroupModeConverter
      extends Converter.Contextless<Map.Entry<String, OutputGroupFileMode>> {
    private final AssignmentConverter assignmentConverter = new AssignmentConverter();
    private final OutputGroupFileModeConverter modeConverter = new OutputGroupFileModeConverter();

    @Override
    public String getTypeDescription() {
      return "an output group name followed by an OutputGroupFileMode, e.g. default=both";
    }

    @Override
    public Map.Entry<String, OutputGroupFileMode> convert(String input)
        throws OptionsParsingException {
      Entry<String, String> entry = assignmentConverter.convert(input);
      OutputGroupFileMode mode = modeConverter.convert(entry.getValue());
      return Maps.immutableEntry(entry.getKey(), mode);
    }
  }

  /**
   * A mapping from output group name to the {@link OutputGroupFileMode} to use for that output
   * group.
   */
  @FunctionalInterface
  public interface OutputGroupFileModes {
    OutputGroupFileMode getMode(String outputGroup);

    OutputGroupFileModes DEFAULT = (outputGroup) -> OutputGroupFileMode.NAMED_SET_OF_FILES_ONLY;
  }

  /**
   * Collects the values in {@link #outputGroupFileModes} into a map and returns a {@link
   * OutputGroupFileModes} backed by that map and defaulting to {@link
   * OutputGroupFileMode.NAMED_SET_OF_FILES_ONLY} for out groups not in that map.
   *
   * <p>This also implements the default value of the {@code
   * --experimental_build_event_output_group_mode} option, which as an {@code allowMultiple} option
   * cannot specify a default value. The default value sets the mode for coverage artifacts to BOTH:
   * {@code --experimental_build_event_output_group_mode=baseline.lcov=both}.
   */
  public OutputGroupFileModes getOutputGroupFileModesMapping() {
    var modeMap =
        ImmutableMap.<String, OutputGroupFileMode>builder()
            .putAll(outputGroupFileModes)
            .buildKeepingLast();
    return (outputGroup) ->
        modeMap.getOrDefault(outputGroup, OutputGroupFileMode.NAMED_SET_OF_FILES_ONLY);
  }

  @Option(
      name = "experimental_build_event_output_group_mode",
      defaultValue = "null",
      converter = BuildEventOutputGroupModeConverter.class,
      allowMultiple = true,
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Specify how an output group's files will be represented in TargetComplete/AspectComplete"
              + " BEP events. Values are an assignment of an output group name to one of"
              + " 'NAMED_SET_OF_FILES_ONLY', 'INLINE_ONLY', or 'BOTH'. The default value is"
              + " 'NAMED_SET_OF_FILES_ONLY'. If an output group is repeated, the final value to"
              + " appear is used. The default value sets the mode for coverage artifacts to BOTH: "
              + "--experimental_build_event_output_group_mode=baseline.lcov=both")
  public List<Map.Entry<String, OutputGroupFileMode>> outputGroupFileModes;
}
