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

package com.google.devtools.common.options;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * The representation of a parsed option instance.
 *
 * <p>An option instance is distinct from the final value of an option, as multiple instances
 * provide values may be overridden or combined in some way.
 */
public final class ParsedOptionDescription {

  private final OptionDefinition optionDefinition;
  @Nullable private final String commandLineForm;
  @Nullable private final String unconvertedValue;
  private final OptionInstanceOrigin origin;
  @Nullable private final Object conversionContext;
  private final boolean oldNameUsed;

  private ParsedOptionDescription(
      OptionDefinition optionDefinition,
      @Nullable String commandLineForm,
      @Nullable String unconvertedValue,
      OptionInstanceOrigin origin,
      @Nullable Object conversionContext,
      boolean oldNameUsed) {
    this.optionDefinition = Preconditions.checkNotNull(optionDefinition);
    this.commandLineForm = commandLineForm;
    this.unconvertedValue = unconvertedValue;
    this.origin = Preconditions.checkNotNull(origin);
    this.conversionContext = conversionContext;
    this.oldNameUsed = oldNameUsed;
  }

  static ParsedOptionDescription newParsedOptionDescription(
      OptionDefinition optionDefinition,
      String commandLineForm,
      @Nullable String unconvertedValue,
      OptionInstanceOrigin origin,
      @Nullable Object conversionContext) {
    // An actual ParsedOptionDescription should always have a form in which it was parsed, but some
    // options, such as expansion options, legitimately have no value.
    return new ParsedOptionDescription(
        optionDefinition,
        Preconditions.checkNotNull(commandLineForm),
        unconvertedValue,
        origin,
        conversionContext,
        false);
  }

  static ParsedOptionDescription newParsedOptionDescription(
      OptionDefinition optionDefinition,
      String commandLineForm,
      @Nullable String unconvertedValue,
      OptionInstanceOrigin origin,
      @Nullable Object conversionContext,
      boolean oldNameUsed) {
    // An actual ParsedOptionDescription should always have a form in which it was parsed, but some
    // options, such as expansion options, legitimately have no value.
    return new ParsedOptionDescription(
        optionDefinition,
        Preconditions.checkNotNull(commandLineForm),
        unconvertedValue,
        origin,
        conversionContext,
        oldNameUsed);
  }

  /**
   * This factory should be used when there is no actual parsed option, since in those cases we do
   * not have an original value or form that the option took.
   */
  static ParsedOptionDescription newDummyInstance(
      OptionDefinition optionDefinition,
      OptionInstanceOrigin origin,
      @Nullable Object conversionContext) {
    return new ParsedOptionDescription(
        optionDefinition, null, null, origin, conversionContext, false);
  }

  public OptionDefinition getOptionDefinition() {
    return optionDefinition;
  }

  @Nullable
  public String getCommandLineForm() {
    return commandLineForm;
  }

  public String getCanonicalForm() {
    return getCanonicalFormWithValueEscaper(s -> s);
  }

  public String getCanonicalFormWithValueEscaper(Function<String, String> escapingFunction) {
    // For boolean flags (note that here we do not check for TriState flags, only flags with actual
    // boolean values, so that we know the return type of getConvertedValue), use the --[no]flag
    // form for the canonical value.
    if (optionDefinition.getType().equals(boolean.class)) {
      try {
        return ((boolean) getConvertedValue() ? "--" : "--no") + optionDefinition.getOptionName();
      } catch (OptionsParsingException e) {
        throw new RuntimeException("Unexpected parsing exception", e);
      }
    } else {
      String optionString = "--" + optionDefinition.getOptionName();
      if (unconvertedValue != null) { // Can be null for Void options.
        optionString += "=" + escapingFunction.apply(unconvertedValue);
      }
      return optionString;
    }
  }

  @Deprecated
  // TODO(b/65646296) Once external dependencies are cleaned up, use getCanonicalForm()
  String getDeprecatedCanonicalForm() {
    String value = unconvertedValue;
    // For boolean flags (note that here we do not check for TriState flags, only flags with actual
    // boolean values, so that we know the return type of getConvertedValue), set them all to 1 or
    // 0, instead of keeping the wide variety of values we accept in their original form.
    if (optionDefinition.getType().equals(boolean.class)) {
      try {
        value = (boolean) getConvertedValue() ? "1" : "0";
      } catch (OptionsParsingException e) {
        throw new RuntimeException("Unexpected parsing exception", e);
      }
    }
    return String.format("--%s=%s", optionDefinition.getOptionName(), value);
  }

  private OptionDocumentationCategory documentationCategory() {
    return optionDefinition.getDocumentationCategory();
  }

  private ImmutableList<OptionMetadataTag> metadataTags() {
    return ImmutableList.copyOf(optionDefinition.getOptionMetadataTags());
  }

  public boolean isDocumented() {
    return documentationCategory() != OptionDocumentationCategory.UNDOCUMENTED && !isHidden();
  }

  public boolean isHidden() {
    ImmutableList<OptionMetadataTag> tags = metadataTags();
    return tags.contains(OptionMetadataTag.HIDDEN) || tags.contains(OptionMetadataTag.INTERNAL);
  }

  @Nullable
  public String getUnconvertedValue() {
    return unconvertedValue;
  }

  public OptionInstanceOrigin getOrigin() {
    return origin;
  }

  public OptionPriority getPriority() {
    return origin.getPriority();
  }

  public boolean isOldNameUsed() {
    return oldNameUsed;
  }

  @Nullable
  public String getSource() {
    return origin.getSource();
  }

  @Nullable
  ParsedOptionDescription getImplicitDependent() {
    return origin.getImplicitDependent();
  }

  @Nullable
  ParsedOptionDescription getExpandedFrom() {
    return origin.getExpandedFrom();
  }

  public boolean isExplicit() {
    return origin.getExpandedFrom() == null
        && origin.getImplicitDependent() == null
        // Exclude options from PROJECT.scl files, which are not considered explicit.
        && !(origin.getSource() != null && origin.getSource().endsWith("PROJECT.scl"));
  }

  public Object getConvertedValue() throws OptionsParsingException {
    Converter<?> converter = optionDefinition.getConverter();
    try {
      return converter.convert(unconvertedValue, conversionContext);
    } catch (OptionsParsingException e) {
      // The converter doesn't know the option name, so we supply it here by re-throwing:
      throw new OptionsParsingException(
          String.format("While parsing option %s: %s", commandLineForm, e.getMessage()), e);
    }
  }

  @Override
  public String toString() {
    // Check that a dummy value-less option instance does not output all the default information.
    if (commandLineForm == null) {
      return optionDefinition.toString();
    }
    String source = origin.getSource();
    return String.format(
        "option '%s'%s",
        commandLineForm, source == null ? "" : String.format(" (source %s)", source));
  }
}
