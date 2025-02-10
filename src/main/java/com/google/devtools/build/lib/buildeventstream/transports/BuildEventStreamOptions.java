// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.buildeventstream.transports;

import com.google.devtools.build.lib.buildeventservice.BuildEventServiceOptions.BesUploadMode;
import com.google.devtools.build.lib.buildeventservice.BuildEventServiceOptions.BesUploadModeConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsBase;

/** Options used to configure BuildEventStreamer and its BuildEventTransports. */
public class BuildEventStreamOptions extends OptionsBase {

  @Option(
      name = "build_event_text_file",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "If non-empty, write a textual representation of the build event protocol to that file")
  public String buildEventTextFile;

  @Option(
      name = "keep_backend_build_event_connections_alive",
      defaultValue = "true",
      metadataTags = {OptionMetadataTag.HIDDEN},
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "If enabled, keep connections to build event backend connections alive across builds.")
  public boolean keepBackendConnections;

  @Option(
      name = "build_event_binary_file",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "If non-empty, write a varint delimited binary representation of representation of the"
              + " build event protocol to that file. This option implies"
              + " --bes_upload_mode=wait_for_upload_complete.")
  public String buildEventBinaryFile;

  @Option(
      name = "build_event_json_file",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "If non-empty, write a JSON serialisation of the build event protocol to that file."
              + " This option implies --bes_upload_mode=wait_for_upload_complete.")
  public String buildEventJsonFile;

  @Option(
      name = "build_event_text_file_upload_mode",
      defaultValue = "wait_for_upload_complete",
      converter = BesUploadModeConverter.class,
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.EAGERNESS_TO_EXIT},
      help =
          "Specifies whether the Build Event Service upload for --build_event_text_file should"
              + " block the build completion or should end the invocation immediately and finish"
              + " the upload in the background. Either 'wait_for_upload_complete' (default),"
              + " 'nowait_for_upload_complete', or 'fully_async'.")
  public BesUploadMode buildEventTextFileUploadMode;

  @Option(
      name = "build_event_binary_file_upload_mode",
      defaultValue = "wait_for_upload_complete",
      converter = BesUploadModeConverter.class,
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.EAGERNESS_TO_EXIT},
      help =
          "Specifies whether the Build Event Service upload for --build_event_binary_file should"
              + " block the build completion or should end the invocation immediately and finish"
              + " the upload in the background. Either 'wait_for_upload_complete' (default),"
              + " 'nowait_for_upload_complete', or 'fully_async'.")
  public BesUploadMode buildEventBinaryFileUploadMode;

  @Option(
      name = "build_event_json_file_upload_mode",
      defaultValue = "wait_for_upload_complete",
      converter = BesUploadModeConverter.class,
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.EAGERNESS_TO_EXIT},
      help =
          "Specifies whether the Build Event Service upload for --build_event_json_file should"
              + " block the build completion or should end the invocation immediately and finish"
              + " the upload in the background. Either 'wait_for_upload_complete' (default),"
              + " 'nowait_for_upload_complete', or 'fully_async'.")
  public BesUploadMode buildEventJsonFileUploadMode;

  @Option(
      name = "build_event_text_file_path_conversion",
      oldName = "experimental_build_event_text_file_path_conversion",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Convert paths in the text file representation of the build event protocol to more "
              + "globally valid URIs whenever possible; if disabled, the file:// uri scheme will "
              + "always be used")
  public boolean buildEventTextFilePathConversion;

  @Option(
      name = "build_event_binary_file_path_conversion",
      oldName = "experimental_build_event_binary_file_path_conversion",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Convert paths in the binary file representation of the build event protocol to more "
              + "globally valid URIs whenever possible; if disabled, the file:// uri scheme will "
              + "always be used")
  public boolean buildEventBinaryFilePathConversion;

  @Option(
      name = "build_event_json_file_path_conversion",
      oldName = "experimental_build_event_json_file_path_conversion",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Convert paths in the json file representation of the build event protocol to more "
              + "globally valid URIs whenever possible; if disabled, the file:// uri scheme will "
              + "always be used")
  public boolean buildEventJsonFilePathConversion;

  @Option(
      name = "build_event_publish_all_actions",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help = "Whether all actions should be published.")
  public boolean publishAllActions;

  @Option(
      name = "build_event_max_named_set_of_file_entries",
      defaultValue = "5000",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "The maximum number of entries for a single named_set_of_files event; values smaller "
              + "than 2 are ignored and no event splitting is performed. This is intended for "
              + "limiting the maximum event size in the build event protocol, although it does not "
              + "directly control event size. The total event size is a function of the structure "
              + "of the set as well as the file and uri lengths, which may in turn depend on the "
              + "hash function.")
  public int maxNamedSetEntries;
}
