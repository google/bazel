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
package com.google.devtools.build.lib.bazel;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import java.util.List;

/** Provides Bazel startup flags. */
public class BazelStartupOptionsModule extends BlazeModule {
  /** Bazelrc file flags. */
  public static final class Options extends OptionsBase {
    @Option(
        name = "bazelrc",
        allowMultiple = true,
        defaultValue = "null", // NOTE: purely decorative, rc files are read by the client.
        documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = {OptionEffectTag.CHANGES_INPUTS},
        valueHelp = "<path>",
        help =
            "The location of the user .bazelrc file containing default values of "
                + "Bazel options. "
                + "/dev/null indicates that all further `--bazelrc`s will be ignored, "
                + "which is useful to disable the search for a user rc file, "
                + "e.g. in release builds.\n"
                + "This option can also be specified multiple times.\n"
                + "E.g. with "
                + "`--bazelrc=x.rc --bazelrc=y.rc --bazelrc=/dev/null --bazelrc=z.rc`,\n"
                + "  1) x.rc and y.rc are read.\n"
                + "  2) z.rc is ignored due to the prior /dev/null.\n"
                + "If unspecified, Bazel uses the first .bazelrc file it finds in "
                + "the following two locations: the workspace directory, then the user's home "
                + "directory.\n"
                + "Note: command line options will always supersede any option in bazelrc.")
    public List<String> blazerc;

    // For the system_rc, it can be /etc/bazel.bazelrc, or a special Windows value, or can be
    // custom-set by the Bazel distributor. We don't list a known path in the help output in order
    // to avoid misdocumentation here.
    @Option(
        name = "system_rc",
        defaultValue = "true", // NOTE: purely decorative, rc files are read by the client.
        documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = {OptionEffectTag.CHANGES_INPUTS},
        help = "Whether or not to look for the system-wide bazelrc.")
    public boolean systemRc;

    @Option(
        name = "workspace_rc",
        defaultValue = "true", // NOTE: purely decorative, rc files are read by the client.
        documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = {OptionEffectTag.CHANGES_INPUTS},
        help = "Whether or not to look for the workspace bazelrc file at $workspace/.bazelrc")
    public boolean workspaceRc;

    @Option(
        name = "home_rc",
        defaultValue = "true", // NOTE: purely decorative, rc files are read by the client.
        documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = {OptionEffectTag.CHANGES_INPUTS},
        help = "Whether or not to look for the home bazelrc file at $HOME/.bazelrc")
    public boolean homeRc;
  }

  @Override
  public Iterable<Class<? extends OptionsBase>> getStartupOptions() {
    return ImmutableList.of(Options.class);
  }
}
