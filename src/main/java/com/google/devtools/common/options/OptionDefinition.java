// Copyright 2024 The Bazel Authors. All rights reserved.
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

import static java.util.Comparator.comparing;

import java.lang.reflect.Type;
import java.util.Comparator;
import javax.annotation.Nullable;

/** Everything the {@link OptionsParser} needs to know about how an option is defined. */
public abstract class OptionDefinition implements Comparable<OptionDefinition> {

  /** An ordering relation for options that orders by the option name. */
  public static final Comparator<OptionDefinition> BY_OPTION_NAME =
      Comparator.comparing(OptionDefinition::getOptionName);

  /**
   * An ordering relation for options that first groups together options of the same category, then
   * sorts by name within the category.
   */
  public static final Comparator<OptionDefinition> BY_CATEGORY =
      comparing(OptionDefinition::getOptionCategory).thenComparing(BY_OPTION_NAME);

  /** Returns the declaring {@link OptionsBase} class that owns this option. */
  public abstract <C extends OptionsBase> Class<? extends C> getDeclaringClass(Class<C> baseClass);

  /**
   * Returns the raw value of the option. Use {@link #getValue} if possible to correctly handle
   * default values.
   */
  public abstract Object getRawValue(OptionsBase optionsBase);

  /** Returns the value of this option, taking default values into account. */
  public abstract Object getValue(OptionsBase optionsBase);

  /**
   * Returns the value of this option as a boolean. If the option is not boolean-typed, throws an
   * IllegalStateException.
   */
  public boolean getBooleanValue(OptionsBase optionsBase) {
    // Check for primitive boolean first, as it's more common.
    if (!getType().isAssignableFrom(Boolean.TYPE) && !getType().isAssignableFrom(Boolean.class)) {
      throw new IllegalStateException(
          "Option "
              + getOptionName()
              + " is not a boolean, has type "
              + getType().getCanonicalName());
    }
    return getValue(optionsBase).equals(Boolean.TRUE);
  }

  /** Sets the value for this option. */
  public abstract void setValue(OptionsBase optionsBase, Object value);

  /** Returns whether this option is deprecated. */
  public abstract boolean isDeprecated();

  /** Returns the name of this option. */
  public abstract String getOptionName();

  /** Returns a one-character abbreviation for this option, if any. */
  public abstract char getAbbreviation();

  /** Returns the help test for this option. */
  public abstract String getHelpText();

  /** Returns a short description of the expected type of this option. */
  public abstract String getValueTypeHelpText();

  /**
   * Returns the default value of this option, with no conversion performed. Should only be used by
   * the parser.
   */
  public abstract String getUnparsedDefaultValue();

  /**
   * Returns the deprecated option category.
   *
   * @deprecated Use {@link #getDocumentationCategory} instead
   */
  @Deprecated
  public abstract String getOptionCategory();

  /** Returns the option category. */
  public abstract OptionDocumentationCategory getDocumentationCategory();

  /** Returns data about the intended effects of this option. */
  public abstract OptionEffectTag[] getOptionEffectTags();

  /** Returns metadata about this option. */
  public abstract OptionMetadataTag[] getOptionMetadataTags();

  /** Returns a converter to use for this option. */
  @SuppressWarnings({"rawtypes"})
  public abstract Class<? extends Converter> getProvidedConverter();

  /** Returns whether this option allows multiple instances to be combined into a list. */
  public abstract boolean allowsMultiple();

  /** Returns any options which are added if this option is present. */
  public abstract String[] getOptionExpansion();

  /** Returns aditional options that need to be implicitly added for this option. */
  public abstract String[] getImplicitRequirements();

  /** Returns a deprecation warning for this option, if one is present. */
  public abstract String getDeprecationWarning();

  /** Returns the old name for this option, if one is present. */
  public abstract String getOldOptionName();

  /** Returns a warning to use with this option if the old name is specified. */
  public abstract boolean getOldNameWarning();

  /** Returns whether an option --foo has a negative equivalent --nofoo. */
  public boolean hasNegativeOption() {
    return getType().equals(boolean.class) || HasNegativeFlag.class.isAssignableFrom(getType());
  }

  /** The type of the optionDefinition. */
  public abstract Class<?> getType();

  /** Whether this field has type Void. */
  public boolean isVoidField() {
    return getType().equals(Void.class);
  }

  // TODO: blaze-configurability - try to remove special handling for defaults
  public abstract boolean isSpecialNullDefault();

  /** Returns whether the arg is an expansion option. */
  public boolean isExpansionOption() {
    return getOptionExpansion().length > 0;
  }

  /** Returns whether the arg is an expansion option. */
  public boolean hasImplicitRequirements() {
    return (getImplicitRequirements().length > 0);
  }

  /**
   * For an option that does not use {@link Option#allowMultiple}, returns its type. For an option
   * that does use it, asserts that the type is a {@code List<T>} and returns its element type
   * {@code T}.
   */
  public abstract Type getFieldSingularType();

  /** Returns the {@link Converter} that will be used for this option. */
  public abstract Converter<?> getConverter();

  /**
   * Returns whether a field should be considered as boolean.
   *
   * <p>Can be used for usage help and controlling whether the "no" prefix is allowed.
   */
  public boolean usesBooleanValueSyntax() {
    return getType().equals(boolean.class)
        || getType().equals(TriState.class)
        || getConverter() instanceof BoolOrEnumConverter;
  }

  /**
   * Returns whether an option requires a value when instantiated, or instead can be present without
   * an explicit value.
   */
  public boolean requiresValue() {
    return !isVoidField() && !usesBooleanValueSyntax();
  }

  /** Returns the evaluated default value for this option. */
  @Nullable
  public abstract Object getDefaultValue(@Nullable Object conversionContext);
}
