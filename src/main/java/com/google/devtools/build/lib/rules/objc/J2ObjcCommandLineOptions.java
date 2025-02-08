// Copyright 2015 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.objc;

import com.google.devtools.build.lib.analysis.config.CoreOptionConverters.LabelConverter;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import java.util.List;

/**
 * Command-line options for J2ObjC translation of Java source code to ObjC. These command line
 * options are used by Java rules that can be transpiled (specifically, J2ObjCAspects thereof).
 */
public class J2ObjcCommandLineOptions extends FragmentOptions {
  @Option(
      name = "j2objc_translation_flags",
      converter = Converters.CommaSeparatedOptionListConverter.class,
      allowMultiple = true,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "Additional options to pass to the J2ObjC tool.")
  public List<String> translationFlags;

  @Option(
    name = "j2objc_dead_code_report",
    defaultValue = "null",
    converter = LabelConverter.class,
    documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
    effectTags = {OptionEffectTag.UNKNOWN},
    help =
        "Allows J2ObjC to strip dead code reported by ProGuard. Takes a label that can "
            + "generate a dead code report as argument."
  )
  public Label deadCodeReport;

}
