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
package com.google.devtools.build.lib.remote.options;

import com.google.devtools.build.lib.actions.FileArtifactValue.RemoteFileArtifactValue;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.Converters.RegexPatternConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.RegexPatternOption;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/** Options for remote execution and distributed caching that shared between Bazel and Blaze. */
public class CommonRemoteOptions extends OptionsBase {
  @Option(
      name = "remote_download_regex",
      oldName = "experimental_remote_download_regex",
      defaultValue = "null",
      allowMultiple = true,
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      converter = RegexPatternConverter.class,
      help =
          "Force remote build outputs whose path matches this pattern to be downloaded,"
              + " irrespective of --remote_download_outputs. Multiple patterns may be specified by"
              + " repeating this flag.")
  public List<RegexPatternOption> remoteDownloadRegex;

  private static final String SERVER_KEYWORD = "server";

  @Option(
      name = "experimental_remote_cache_ttl",
      defaultValue = "3h",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.EXECUTION},
      converter = RemoteDurationConverter.class,
      help =
          "The guaranteed minimal TTL of blobs in the remote cache after their digests are recently"
              + " referenced e.g. by an ActionResult or FindMissingBlobs. Bazel does several"
              + " optimizations based on the blobs' TTL e.g. doesn't repeatedly call"
              + " GetActionResult in an incremental build. The value should be set slightly less"
              + " than the real TTL since there is a gap between when the server returns the"
              + " digests and when Bazel receives them. The special value \"" + SERVER_KEYWORD + "\" allows entries"
              + " to remain in the remote cache for the lifetime of the Bazel server, but no longer.")
  public Duration remoteCacheTtl;

  /** Returns the specified duration. Assumes seconds if unitless. */
  public static class RemoteDurationConverter extends Converter.Contextless<Duration> {

    private static final Pattern UNITLESS_REGEX = Pattern.compile("^[0-9]+$");

    @Override
    public Duration convert(String input) throws OptionsParsingException {
      /* We recognize the magic value SERVER_KEYWORD as a way to specify server lifetime ttl. */
      if (input.equals(SERVER_KEYWORD)) {
        return Duration.ofSeconds(RemoteFileArtifactValue.SERVER_EXPIRATION_SENTINEL);
      }
      if (UNITLESS_REGEX.matcher(input).matches()) {
        input += "s";
      }
      return new Converters.DurationConverter().convert(input, /* conversionContext= */ null);
    }

    @Override
    public String getTypeDescription() {
      return "An immutable length of time.";
    }
  }
}
