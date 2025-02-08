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
package com.google.devtools.build.lib.analysis.util;

import static java.util.Objects.requireNonNull;

import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.CoreOptionConverters.EmptyToNullLabelConverter;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.RequiresOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter;
import com.google.devtools.common.options.Converters.OptionalAssignmentConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.List;
import java.util.Map;

/**
 * Expose a set of options that can be added to {@link BuildViewTestCase} and friends in order to
 * force configuration changes without materially affecting the build.
 *
 * <p>Previously, supposed 'no-op' options like --test_arg were used; however, this interferes with
 * --trim_test_configuration.
 *
 * <p>Note that, for {@link BuildViewTestCase}, these can be 'enables' by overriding {@link
 * BuildViewTestCase.createRuleClassProvider} and using {@link
 * ConfiguredRuleClassProvider.Builder.addConfigurationFragment} for DummyTestFragment.class.
 */
@RequiresOptions(options = {DummyTestFragment.DummyTestOptions.class})
public final class DummyTestFragment extends Fragment {
  public DummyTestFragment(BuildOptions buildOptions) {}

  /** Flags that exhibit a variety of flag behaviors. */
  public static class DummyTestOptions extends FragmentOptions {
    @Option(
        name = "nullable_option",
        converter = EmptyToNullLabelConverter.class,
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "An option that is sometimes set to null.")
    public Label nullable;

    @Option(
        name = "foo",
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "A regular string-typed option")
    public String foo;

    @Option(
        name = "internal foo",
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        metadataTags = {OptionMetadataTag.INTERNAL},
        help = "A string-typed option that cannot be set on the commandline")
    public String internalFoo;

    @Option(
        name = "bar",
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "A regular string-typed option")
    public String bar;

    @Option(
        name = "bazes",
        defaultValue = "null",
        allowMultiple = true,
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "A regular string-typed option")
    public List<String> bazes;

    @Option(
        name = "bool",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "A regular bool-typed option")
    public boolean bool;

    @Option(
        name = "unreadable_by_starlark",
        defaultValue = "anything",
        converter = UnreadableStringBoxConverter.class,
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "This cannot be used as an input to a Starlark transition")
    public UnreadableStringBox unreadableByStarlark;

    @Option(
        name = "allow_multiple_with_optional_assignment_converter",
        defaultValue = "null",
        allowMultiple = true,
        converter = OptionalAssignmentConverter.class,
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "allowMultiple flag with OptionalAssignmentConverter")
    public List<Map.Entry<String, String>> allowMultipleWithOptionalAssignmentConverter;

    @Option(
        name = "allow_multiple_with_list_converter",
        defaultValue = "null",
        allowMultiple = true,
        converter = CommaSeparatedOptionListConverter.class,
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "allowMultiple flag where the converter returns a list")
    public List<String> allowMultipleWithListConverter;

    @AutoCodec
    public record UnreadableStringBox(String value) {
      public UnreadableStringBox {
        requireNonNull(value, "value");
      }

      public static UnreadableStringBox create(String value) {
        return new UnreadableStringBox(value);
      }
    }

    public static class UnreadableStringBoxConverter implements Converter<UnreadableStringBox> {
      @Override
      public UnreadableStringBox convert(String input, Object conversionContext)
          throws OptionsParsingException {
        return UnreadableStringBox.create(input);
      }

      @Override
      public String getTypeDescription() {
        return "a string that is not readable by Starlark";
      }
    }
  }
}
