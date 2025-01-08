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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.common.options.Converters.AssignmentConverter;
import com.google.devtools.common.options.Converters.IntegerConverter;
import com.google.devtools.common.options.Converters.StringConverter;
import com.google.devtools.common.options.FieldOptionDefinition.NotAnOptionException;
import com.google.devtools.common.options.OptionsParser.ConstructionException;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

/** Tests for {@link FieldOptionDefinition}. */
@RunWith(JUnit4.class)
public class FieldOptionDefinitionTest {

  /** Dummy options class, to test various expected failures of the OptionDefinition. */
  public static class BrokenOptions extends OptionsBase {
    public String notAnOption;

    @Option(
      name = "assignments",
      defaultValue = "foo is not an assignment",
      converter = AssignmentConverter.class,
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = OptionEffectTag.NO_OP
    )
    public Map.Entry<String, String> assignments;
  }

  @Test
  public void optionConverterCannotParseDefaultValue() throws Exception {
    OptionDefinition optionDef =
        FieldOptionDefinition.extractOptionDefinition(BrokenOptions.class.getField("assignments"));
    ConstructionException e =
        assertThrows(
            "Incorrect default should have caused getDefaultValue to fail.",
            ConstructionException.class,
            () -> optionDef.getDefaultValue(/*conversionContext=*/ null));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "OptionsParsingException while retrieving the default value for assignments: "
                + "Variable definitions must be in the form of a 'name=value' assignment");
  }

  @Test
  public void optionDefinitionRejectsNonOptions() throws Exception {
    NotAnOptionException e =
        assertThrows(
            "notAnOption isn't an Option, and shouldn't be accepted as one.",
            NotAnOptionException.class,
            () ->
                FieldOptionDefinition.extractOptionDefinition(
                    BrokenOptions.class.getField("notAnOption")));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "The field notAnOption does not have the right annotation to be considered an "
                + "option.");
  }

  /**
   * Dummy options class with valid options for testing the memoization of converters and default
   * values.
   */
  public static class ValidOptionUsingDefaultConverterForMocking extends OptionsBase {
    @Option(
      name = "foo",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "42"
    )
    public int foo;

    @Option(
      name = "bar",
      converter = StringConverter.class,
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "strings"
    )
    public String bar;
  }

  /**
   * Test that the converter and option default values are only computed once and then are obtained
   * from the stored values, in the case where a default converter is used.
   */
  @Test
  public void optionDefinitionMemoizesDefaultConverterValue() throws Exception {
    OptionDefinition optionDefinition =
        FieldOptionDefinition.extractOptionDefinition(
            ValidOptionUsingDefaultConverterForMocking.class.getField("foo"));
    OptionDefinition mockOptionDef = Mockito.spy(optionDefinition);

    // Do a bunch of potentially repeat operations on this option that need to know information
    // about the converter and default value. Also verify that the values are as expected.
    boolean isBoolean = mockOptionDef.usesBooleanValueSyntax();
    assertThat(isBoolean).isFalse();

    Converter<?> converter = mockOptionDef.getConverter();
    assertThat(converter).isInstanceOf(IntegerConverter.class);

    int value = (int) mockOptionDef.getDefaultValue(/*conversionContext=*/ null);
    assertThat(value).isEqualTo(42);

    // Expect reference equality, since we didn't recompute the value
    Converter<?> secondConverter = mockOptionDef.getConverter();
    assertThat(secondConverter).isSameInstanceAs(converter);

    mockOptionDef.getDefaultValue(/*conversionContext=*/ null);

    // Verify that we didn't re-calculate the converter from the provided class object.
    verify(mockOptionDef, times(1)).getProvidedConverter();
    // The first call to getDefaultValue checks isSpecialNullDefault, which called
    // getUnparsedValueDefault as well, but expect no more calls to it after the initial call.
    verify(mockOptionDef, times(1)).isSpecialNullDefault();
    verify(mockOptionDef, times(2)).getUnparsedDefaultValue();
  }

  /**
   * Test that the converter and option default values are only computed once and then are obtained
   * from the stored values, in the case where a converter was provided.
   */
  @Test
  public void optionDefinitionMemoizesProvidedConverterValue() throws Exception {
    OptionDefinition optionDefinition =
        FieldOptionDefinition.extractOptionDefinition(
            ValidOptionUsingDefaultConverterForMocking.class.getField("bar"));
    OptionDefinition mockOptionDef = Mockito.spy(optionDefinition);

    // Do a bunch of potentially repeat operations on this option that need to know information
    // about the converter and default value. Also verify that the values are as expected.
    boolean isBoolean = mockOptionDef.usesBooleanValueSyntax();
    assertThat(isBoolean).isFalse();

    Converter<?> converter = mockOptionDef.getConverter();
    assertThat(converter).isInstanceOf(StringConverter.class);

    String value = (String) mockOptionDef.getDefaultValue(/*conversionContext=*/ null);
    assertThat(value).isEqualTo("strings");

    // Expect reference equality, since we didn't recompute the value
    Converter<?> secondConverter = mockOptionDef.getConverter();
    assertThat(secondConverter).isSameInstanceAs(converter);

    mockOptionDef.getDefaultValue(/*conversionContext=*/ null);

    // Verify that we didn't re-calculate the converter from the provided class object.
    verify(mockOptionDef, times(1)).getProvidedConverter();
    // The first call to getDefaultValue checks isSpecialNullDefault, which called
    // getUnparsedValueDefault as well, but expect no more calls to it after the initial call.
    verify(mockOptionDef, times(1)).isSpecialNullDefault();
    verify(mockOptionDef, times(2)).getUnparsedDefaultValue();
  }

  /** Dummy options class, to test defaultValue handling. */
  private static class DefaultValueTestOptions extends OptionsBase {
    @Option(
        name = "null_non_multiple_option",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = OptionEffectTag.NO_OP)
    public String nullNonMultipleOption;

    @Option(
        name = "null_multiple_option",
        allowMultiple = true,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = OptionEffectTag.NO_OP)
    public List<String> nullMultipleOption;

    @Option(
        name = "empty_string_multiple_option",
        allowMultiple = true,
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = OptionEffectTag.NO_OP)
    public List<String> emptyStringMultipleOption;

    @Option(
        name = "non_empty_string_multiple_option",
        allowMultiple = true,
        defaultValue = "text",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = OptionEffectTag.NO_OP)
    public List<String> nonEmptyStringMultipleOption;

    @Option(
        name = "custom_negatable_option",
        allowMultiple = false,
        converter = CustomNegatableOptionConverter.class,
        defaultValue = "yes",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = OptionEffectTag.NO_OP
    )
    public CustomNegatableOption customNegatableOption;
  }

  enum CustomNegatableOption implements HasNegativeFlag {
    YES, NO, PERHAPS
  }

  public static class CustomNegatableOptionConverter extends BoolOrEnumConverter<CustomNegatableOption> {
    public CustomNegatableOptionConverter() {
      super(
              CustomNegatableOption.class, "custom negatable option", CustomNegatableOption.YES, CustomNegatableOption.NO);
    }
  }

  @Test
  public void specialDefaultValueForNonMultipleOptionShouldResultInNull() throws Exception {
    // arrange
    OptionDefinition optionDef =
        FieldOptionDefinition.extractOptionDefinition(
            DefaultValueTestOptions.class.getField("nullNonMultipleOption"));

    // act
    Object result = optionDef.getDefaultValue(/*conversionContext=*/ null);

    // assert
    assertThat(result).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void specialDefaultValueForMultipleOptionShouldResultInEmptyList() throws Exception {
    // arrange
    OptionDefinition optionDef =
        FieldOptionDefinition.extractOptionDefinition(
            DefaultValueTestOptions.class.getField("nullMultipleOption"));

    // act
    List<String> result = (List<String>) optionDef.getDefaultValue(/*conversionContext=*/ null);

    // assert
    assertThat(result).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void emptyStringForMultipleOptionShouldBeConverted() throws Exception {
    // arrange
    OptionDefinition optionDef =
        FieldOptionDefinition.extractOptionDefinition(
            DefaultValueTestOptions.class.getField("emptyStringMultipleOption"));

    // act
    List<String> result = (List<String>) optionDef.getDefaultValue(/*conversionContext=*/ null);

    // assert
    assertThat(result).containsExactly("");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void nonEmptyStringForMultipleOptionShouldBeConverted() throws Exception {
    // arrange
    OptionDefinition optionDef =
        FieldOptionDefinition.extractOptionDefinition(
            DefaultValueTestOptions.class.getField("nonEmptyStringMultipleOption"));

    // act
    List<String> result = (List<String>) optionDef.getDefaultValue(/*conversionContext=*/ null);

    // assert
    assertThat(result).containsExactly("text");
  }

  @Test
  public void customFlagWithNegative() throws Exception {
    // arrange
    OptionDefinition optionDef =
            FieldOptionDefinition.extractOptionDefinition(
                    DefaultValueTestOptions.class.getField("customNegatableOption"));

    // act
    boolean hasNegative = optionDef.hasNegativeOption();

    // assert
    assertThat(hasNegative).isTrue();
  }
}
