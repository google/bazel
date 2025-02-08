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

package com.google.devtools.common.options;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter;
import com.google.devtools.common.options.OptionPriority.PriorityCategory;
import com.google.devtools.common.options.OptionsParser.ArgAndFallbackData;
import com.google.devtools.common.options.OptionsParser.ConstructionException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests {@link OptionsParser}. */
@RunWith(JUnit4.class)
public final class OptionsParserTest {

  /** Dummy comment (linter suppression) */
  public static class BadOptions extends OptionsBase {
    @Option(
      name = "foo",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "false"
    )
    public boolean foo1;

    @Option(
      name = "foo",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "false"
    )
    public boolean foo2;
  }

  @Test
  public void errorsDuringConstructionAreWrapped() {
    OptionsParser.ConstructionException e =
        assertThrows(
            OptionsParser.ConstructionException.class,
            () -> OptionsParser.builder().optionsClasses(BadOptions.class).build());
    assertThat(e).hasCauseThat().isInstanceOf(DuplicateOptionDeclarationException.class);
  }

  public enum TestEnum {
    DEFAULT,
    EXPLICIT;
  }

  public static class TestEnumConverter extends EnumConverter<TestEnum> {
    public TestEnumConverter() {
      super(TestEnum.class, "test enum");
    }
  }

  public static class ChoosyConverter implements Converter<String> {
    @Override
    public String convert(String input, @Nullable Object conversionContext)
        throws OptionsParsingException {
      switch (input) {
        case "default":
          return "default";
        case " explicit":
          return "explicit";
        default:
          throw new OptionsParsingException("illegal");
      }
    }

    @Override
    public String getTypeDescription() {
      return "choosy";
    }
  }

  public static class ChoosyOptions extends OptionsBase {
    @Option(
        name = "choosy",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "default",
        converter = ChoosyConverter.class)
    public String choosy;
  }

  public static class ExampleFoo extends OptionsBase {

    @Option(
      name = "foo",
      category = "one",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "defaultFoo"
    )
    public String foo;

    @Option(
      name = "bar",
      category = "two",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "42"
    )
    public int bar;

    @Option(
        name = "bing",
        category = "one",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "null",
        allowMultiple = true)
    public List<String> bing;

    @Option(
        name = "bang",
        category = "one",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "null",
        converter = StringConverter.class,
        allowMultiple = true)
    public List<String> bang;

    @Option(
        name = "nodoc",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "")
    public String nodoc;
  }

  public static class ExampleBaz extends OptionsBase {

    @Option(
      name = "baz",
      category = "one",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "defaultBaz"
    )
    public String baz;
  }

  /** Subclass of an options class. */
  public static class ExampleBazSubclass extends ExampleBaz {

    @Option(
      name = "baz_subclass",
      category = "one",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "defaultBazSubclass"
    )
    public String bazSubclass;
  }

  /**
   * Example with empty to null string converter
   */
  public static class ExampleBoom extends OptionsBase {
    @Option(
      name = "boom",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "defaultBoom",
      converter = EmptyToNullStringConverter.class
    )
    public String boom;
  }

  /**
   * Example with internal options
   */
  public static class ExampleInternalOptions extends OptionsBase {
    @Option(
      name = "internal_boolean",
      metadataTags = {OptionMetadataTag.INTERNAL},
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "true"
    )
    public boolean privateBoolean;

    @Option(
        name = "internal_string",
        metadataTags = {OptionMetadataTag.INTERNAL},
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "super secret")
    public String privateString;
  }

  public static class ExampleEquivalentWithFoo extends OptionsBase {

    @Option(
        name = "foo",
        category = "one",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "differentDefault")
    public String foo;

    @Option(
        name = "bar",
        category = "one",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "differentDefault")
    public String bar;

    @Option(
        name = "ignored_with_value",
        category = "one",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "differentDefault")
    public String ignoredWithValue;

    @Option(
        name = "ignored_without_value",
        category = "one",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "false")
    public boolean ignoredWithoutValue;
  }

  public static class ExampleIncompatibleWithFoo extends OptionsBase {

    @Option(
        name = "foo",
        category = "one",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "true")
    public boolean foo;
  }

  public static class StringConverter extends Converter.Contextless<String> {

    @Override
    public String convert(String input) {
      return input;
    }

    @Override
    public String getTypeDescription() {
      return "a string";
    }
  }

  /**
   * A converter that defaults to null if the input is the empty string
   */
  public static class EmptyToNullStringConverter extends StringConverter {
    @Override
    public String convert(String input) {
      return input.isEmpty() ? null : input;
    }
  }

  @Test
  public void defaultValueOfBadOptionRemains() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(ChoosyOptions.class).build();

    assertThrows(OptionsParsingException.class, () -> parser.parse("--choosy=wat"));
    assertThat(parser.getOptions(ChoosyOptions.class).choosy).isEqualTo("default");
  }

  @Test
  public void parseWithMultipleOptionsInterfaces() throws OptionsParsingException {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ExampleFoo.class, ExampleBaz.class).build();
    parser.parse("--baz=oops", "--bar", "17");
    ExampleFoo foo = parser.getOptions(ExampleFoo.class);
    assertThat(foo.foo).isEqualTo("defaultFoo");
    assertThat(foo.bar).isEqualTo(17);
    ExampleBaz baz = parser.getOptions(ExampleBaz.class);
    assertThat(baz.baz).isEqualTo("oops");
  }

  @Test
  public void parseWithSourceFunctionThrowsExceptionIfResidueIsNotAllowed() {
    OptionsParser parser =
        OptionsParser.builder()
            .optionsClasses(ExampleFoo.class, ExampleBaz.class)
            .allowResidue(false)
            .build();
    Function<OptionDefinition, String> sourceFunction = option -> "command line";

    OptionsParsingException e =
        assertThrows(
            OptionsParsingException.class,
            () ->
                parser.parseWithSourceFunction(
                    PriorityCategory.COMMAND_LINE,
                    sourceFunction,
                    ImmutableList.of("residue", "not", "allowed", "in", "parseWithSource"),
                    /* fallbackData= */ null));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo("Unrecognized arguments: residue not allowed in parseWithSource");
    assertThat(parser.getResidue())
        .containsExactly("residue", "not", "allowed", "in", "parseWithSource");
  }

  @Test
  public void parseWithSourceFunctionDoesntThrowExceptionIfResidueIsAllowed() throws Exception {
    OptionsParser parser =
        OptionsParser.builder()
            .optionsClasses(ExampleFoo.class, ExampleBaz.class)
            .allowResidue(true)
            .build();
    Function<OptionDefinition, String> sourceFunction = option -> "command line";

    parser.parseWithSourceFunction(
        PriorityCategory.COMMAND_LINE,
        sourceFunction,
        ImmutableList.of("residue", "is", "allowed", "in", "parseWithSource"),
        /* fallbackData= */ null);
    assertThat(parser.getResidue())
        .containsExactly("residue", "is", "allowed", "in", "parseWithSource");
  }

  @Test
  public void parseArgsAsExpansionOfOptionThrowsExceptionIfResidueIsNotAllowed() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ExpansionOptions.class).allowResidue(false).build();
    parser.parse(OptionPriority.PriorityCategory.COMMAND_LINE, null, ImmutableList.of("--expands"));
    OptionValueDescription expansionDescription = parser.getOptionValueDescription("expands");
    assertThat(expansionDescription).isNotNull();

    OptionValueDescription optionValue = parser.getOptionValueDescription("underlying");
    assertThat(optionValue).isNotNull();

    ParsedOptionDescription optionToExpand = optionValue.getCanonicalInstances().get(0);

    OptionsParsingException e =
        assertThrows(
            OptionsParsingException.class,
            () ->
                parser.parseArgsAsExpansionOfOption(
                    optionToExpand,
                    "source",
                    ArgAndFallbackData.wrapWithFallbackData(
                        ImmutableList.of("--underlying=direct_value", "residue", "in", "expansion"),
                        /* fallbackData= */ null)));
    assertThat(parser.getResidue()).isNotEmpty();
    assertThat(e).hasMessageThat().isEqualTo("Unrecognized arguments: residue in expansion");
  }

  @Test
  public void parseWithOptionsInheritance() throws OptionsParsingException {
    OptionsParser parser = OptionsParser.builder().optionsClasses(ExampleBazSubclass.class).build();
    parser.parse("--baz_subclass=cat", "--baz=dog");
    ExampleBazSubclass subclassOptions = parser.getOptions(ExampleBazSubclass.class);
    assertThat(subclassOptions.bazSubclass).isEqualTo("cat");
    assertThat(subclassOptions.baz).isEqualTo("dog");
    ExampleBaz options = parser.getOptions(ExampleBaz.class);
    // This is a test showcasing the lack of functionality for retrieving parsed options at a
    // superclass type class type. If there's a need for this functionality, we can add it later.
    assertThat(options).isNull();
  }

  @Test
  public void parserWithUnknownOption() {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ExampleFoo.class, ExampleBaz.class).build();
    OptionsParsingException e =
        assertThrows(OptionsParsingException.class, () -> parser.parse("--unknown", "option"));
    assertThat(e.getInvalidArgument()).isEqualTo("--unknown");
    assertThat(e).hasMessageThat().isEqualTo("Unrecognized option: --unknown");
    assertThat(parser.getResidue()).isEmpty();
  }

  @Test
  public void parserWithSingleDashOption_notAllowed() throws OptionsParsingException {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ExampleFoo.class, ExampleBaz.class).build();
    assertThrows(OptionsParsingException.class, () -> parser.parse("-baz=oops", "-bar", "17"));
  }

  @Test
  public void parsingFailsWithUnknownOptions() {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ExampleFoo.class, ExampleBaz.class).build();
    List<String> unknownOpts = ImmutableList.of("--unknown", "option", "--more_unknowns");
    OptionsParsingException e =
        assertThrows(OptionsParsingException.class, () -> parser.parse(unknownOpts));
    assertThat(e.getInvalidArgument()).isEqualTo("--unknown");
      assertThat(e).hasMessageThat().isEqualTo("Unrecognized option: --unknown");
      assertThat(parser.getOptions(ExampleFoo.class)).isNotNull();
    assertThat(parser.getOptions(ExampleBaz.class)).isNotNull();
  }

  @Test
  public void parsingFailsWithInternalBooleanOptionAsIfUnknown() {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ExampleInternalOptions.class).build();
    List<String> internalOpts = ImmutableList.of("--internal_boolean");
    OptionsParsingException e =
        assertThrows(OptionsParsingException.class, () -> parser.parse(internalOpts));
    assertThat(e.getInvalidArgument()).isEqualTo("--internal_boolean");
      assertThat(e).hasMessageThat().isEqualTo("Unrecognized option: --internal_boolean");
    assertThat(parser.getOptions(ExampleInternalOptions.class)).isNotNull();
  }

  @Test
  public void parsingFailsWithNegatedInternalBooleanOptionAsIfUnknown() {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ExampleInternalOptions.class).build();
    List<String> internalOpts = ImmutableList.of("--nointernal_boolean");
    OptionsParsingException e =
        assertThrows(OptionsParsingException.class, () -> parser.parse(internalOpts));
    assertThat(e.getInvalidArgument()).isEqualTo("--nointernal_boolean");
      assertThat(e).hasMessageThat().isEqualTo("Unrecognized option: --nointernal_boolean");
    assertThat(parser.getOptions(ExampleInternalOptions.class)).isNotNull();
  }

  @Test
  public void parsingFailsForInternalOptionWithValueInSameArgAsIfUnknown() {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ExampleInternalOptions.class).build();
    List<String> internalOpts = ImmutableList.of("--internal_string=any_value");
    OptionsParsingException e =
        assertThrows(
            "parsing should have failed for including a private option",
            OptionsParsingException.class,
            () -> parser.parse(internalOpts));
    assertThat(e.getInvalidArgument()).isEqualTo("--internal_string=any_value");
      assertThat(e).hasMessageThat().isEqualTo("Unrecognized option: --internal_string=any_value");
    assertThat(parser.getOptions(ExampleInternalOptions.class)).isNotNull();
  }

  @Test
  public void parsingFailsForInternalOptionWithValueInSeparateArgAsIfUnknown() {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ExampleInternalOptions.class).build();
    List<String> internalOpts = ImmutableList.of("--internal_string", "any_value");
    OptionsParsingException e =
        assertThrows(
            "parsing should have failed for including a private option",
            OptionsParsingException.class,
            () -> parser.parse(internalOpts));
    assertThat(e.getInvalidArgument()).isEqualTo("--internal_string");
      assertThat(e).hasMessageThat().isEqualTo("Unrecognized option: --internal_string");
    assertThat(parser.getOptions(ExampleInternalOptions.class)).isNotNull();
  }

  @Test
  public void parseKnownAndUnknownOptions() {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ExampleFoo.class, ExampleBaz.class).build();
    List<String> opts = ImmutableList.of("--bar", "17", "--unknown", "option");
    OptionsParsingException e =
        assertThrows(OptionsParsingException.class, () -> parser.parse(opts));
    assertThat(e.getInvalidArgument()).isEqualTo("--unknown");
      assertThat(e).hasMessageThat().isEqualTo("Unrecognized option: --unknown");
      assertThat(parser.getOptions(ExampleFoo.class)).isNotNull();
    assertThat(parser.getOptions(ExampleBaz.class)).isNotNull();
  }

  @Test
  public void parseAndOverrideWithEmptyStringToObtainNullValueInOption()
      throws OptionsParsingException {
    OptionsParser parser = OptionsParser.builder().optionsClasses(ExampleBoom.class).build();
    // Override --boom value to the empty string
    parser.parse("--boom=");
    ExampleBoom boom = parser.getOptions(ExampleBoom.class);
    // The converted value is intentionally null since boom uses the EmptyToNullStringConverter
    assertThat(boom.boom).isNull();
  }

  public static class CategoryTest extends OptionsBase {
    @Option(
      name = "swiss_bank_account_number",
      documentationCategory =
          OptionDocumentationCategory.UNDOCUMENTED, // Not printed in usage messages!
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "123456789"
    )
    public int swissBankAccountNumber;

    @Option(
      name = "student_bank_account_number",
      category = "one",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "987654321"
    )
    public int studentBankAccountNumber;
  }

  @Test
  public void getOptionsAndGetResidueWithNoCallToParse() {
    // With no call to parse(), all options are at default values, and there's
    // no reside.
    assertThat(
            OptionsParser.builder()
                .optionsClasses(ExampleFoo.class)
                .build()
                .getOptions(ExampleFoo.class)
                .foo)
        .isEqualTo("defaultFoo");
    assertThat(OptionsParser.builder().optionsClasses(ExampleFoo.class).build().getResidue())
        .isEmpty();
  }

  @Test
  public void parserCanBeCalledRepeatedly() throws OptionsParsingException {
    OptionsParser parser = OptionsParser.builder().optionsClasses(ExampleFoo.class).build();
    parser.parse("--foo", "foo1");
    assertThat(parser.getOptions(ExampleFoo.class).foo).isEqualTo("foo1");
    parser.parse();
    assertThat(parser.getOptions(ExampleFoo.class).foo).isEqualTo("foo1"); // no change
    parser.parse("--foo", "foo2");
    assertThat(parser.getOptions(ExampleFoo.class).foo).isEqualTo("foo2"); // updated
  }

  @Test
  public void multipleOccurringOption() throws OptionsParsingException {
    OptionsParser parser = OptionsParser.builder().optionsClasses(ExampleFoo.class).build();
    parser.parse("--bing", "abcdef", "--foo", "foo1", "--bing", "123456" );
    assertThat(parser.getOptions(ExampleFoo.class).bing).containsExactly("abcdef", "123456");
  }

  @Test
  public void multipleOccurringOptionWithConverter() throws OptionsParsingException {
    // --bang is the same as --bing except that it has a "converter" specified.
    // This test also tests option values with embedded commas and spaces.
    OptionsParser parser = OptionsParser.builder().optionsClasses(ExampleFoo.class).build();
    parser.parse("--bang", "abc,def ghi", "--foo", "foo1", "--bang", "123456" );
    assertThat(parser.getOptions(ExampleFoo.class).bang).containsExactly("abc,def ghi", "123456");
  }

  @Test
  public void parserIgnoresOptionsAfterMinusMinus()
      throws OptionsParsingException {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ExampleFoo.class, ExampleBaz.class).build();
    parser.parse("--foo", "well", "--baz", "here", "--", "--bar", "ignore");
    ExampleFoo foo = parser.getOptions(ExampleFoo.class);
    ExampleBaz baz = parser.getOptions(ExampleBaz.class);
    assertThat(foo.foo).isEqualTo("well");
    assertThat(baz.baz).isEqualTo("here");
    assertThat(foo.bar).isEqualTo(42); // the default!
    assertThat(parser.getResidue()).containsExactly("--bar", "ignore").inOrder();
  }

  @Test
  public void parserThrowsExceptionIfResidueIsNotAllowed() {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ExampleFoo.class).allowResidue(false).build();
    OptionsParsingException e =
        assertThrows(
            OptionsParsingException.class, () -> parser.parse("residue", "is", "not", "OK"));
    assertThat(e).hasMessageThat().isEqualTo("Unrecognized arguments: residue is not OK");
  }

  @Test
  public void multipleCallsToParse() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ExampleFoo.class).allowResidue(true).build();
    parser.parse("--foo", "one", "--bar", "43", "unknown1");
    parser.parse("--foo", "two", "unknown2");
    ExampleFoo foo = parser.getOptions(ExampleFoo.class);
    assertThat(foo.foo).isEqualTo("two"); // second call takes precedence
    assertThat(foo.bar).isEqualTo(43);
    assertThat(parser.getResidue()).containsExactly("unknown1", "unknown2").inOrder();
  }

  // Regression test for a subtle bug!  The toString of each options interface
  // instance was printing out key=value pairs for all flags in the
  // OptionsParser, not just those belonging to the specific interface type.
  @Test
  public void toStringDoesntIncludeFlagsForOtherOptionsInParserInstance()
      throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ExampleFoo.class, ExampleBaz.class).build();
    parser.parse("--foo", "foo", "--bar", "43", "--baz", "baz");

    String fooString = parser.getOptions(ExampleFoo.class).toString();
    if (!fooString.contains("foo=foo") ||
        !fooString.contains("bar=43") ||
        !fooString.contains("ExampleFoo") ||
        fooString.contains("baz=baz")) {
      fail("ExampleFoo.toString() is incorrect: " + fooString);
    }

    String bazString = parser.getOptions(ExampleBaz.class).toString();
    if (!bazString.contains("baz=baz") ||
        !bazString.contains("ExampleBaz") ||
        bazString.contains("foo=foo") ||
        bazString.contains("bar=43")) {
      fail("ExampleBaz.toString() is incorrect: " + bazString);
    }
  }

  // Regression test for another subtle bug!  The toString was printing all the
  // explicitly-specified options, even if they were at their default values,
  // causing toString equivalence to diverge from equals().
  @Test
  public void toStringIsIndependentOfExplicitCommandLineOptions() throws Exception {
    ExampleFoo foo1 = Options.parse(ExampleFoo.class).getOptions();
    ExampleFoo foo2 = Options.parse(ExampleFoo.class, "--bar", "42").getOptions();
    assertThat(foo2).isEqualTo(foo1);
    assertThat(foo2.toString()).isEqualTo(foo1.toString());

    Map<String, Object> expectedMap =
        new ImmutableMap.Builder<String, Object>()
            .put("bing", Collections.emptyList())
            .put("bar", 42)
            .put("nodoc", "")
            .put("bang", Collections.emptyList())
            .put("foo", "defaultFoo")
            .buildOrThrow();

    assertThat(foo1.asMap()).isEqualTo(expectedMap);
    assertThat(foo2.asMap()).isEqualTo(expectedMap);
  }

  // Regression test for yet another subtle bug!  The inherited options weren't
  // being printed by toString.  One day, a real rain will come and wash all
  // this scummy code off the streets.
  public static class DerivedBaz extends ExampleBaz {
    @Option(
      name = "derived",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "defaultDerived"
    )
    public String derived;
  }

  @Test
  public void toStringPrintsInheritedOptionsToo_Duh() throws Exception {
    DerivedBaz derivedBaz = Options.parse(DerivedBaz.class).getOptions();
    String derivedBazString = derivedBaz.toString();
    if (!derivedBazString.contains("derived=defaultDerived") ||
        !derivedBazString.contains("baz=defaultBaz")) {
      fail("DerivedBaz.toString() is incorrect: " + derivedBazString);
    }
  }

  // Tests for new default value override mechanism
  public static class CustomOptions extends OptionsBase {
    @Option(
      name = "simple",
      category = "custom",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "simple default"
    )
    public String simple;

    @Option(
      name = "multipart_name",
      category = "custom",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "multipart default"
    )
    public String multipartName;
  }

  @Test
  public void assertDefaultStringsForCustomOptions() throws OptionsParsingException {
    CustomOptions options = Options.parse(CustomOptions.class).getOptions();
    assertThat(options.simple).isEqualTo("simple default");
    assertThat(options.multipartName).isEqualTo("multipart default");
  }

  public static class NullTestOptions extends OptionsBase {
    @Option(
      name = "simple",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null"
    )
    public String simple;
  }

  @Test
  public void defaultNullStringGivesNull() throws Exception {
    NullTestOptions options = Options.parse(NullTestOptions.class).getOptions();
    assertThat(options.simple).isNull();
  }

  public static class ConverterWithContextTestOptions extends OptionsBase {
    @Option(
        name = "foo",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        converter = ConverterWithContext.class,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "bar")
    public String foo;

    public static class ConverterWithContext implements Converter<String> {

      @Override
      public String convert(String input, @Nullable Object conversionContext)
          throws OptionsParsingException {
        if (conversionContext != null) {
          return conversionContext + input;
        }
        return input;
      }

      @Override
      public String getTypeDescription() {
        return "a funky string";
      }
    }
  }

  @Test
  public void convertWithContext() throws Exception {
    OptionsParser parser =
        OptionsParser.builder()
            .optionsClasses(ConverterWithContextTestOptions.class)
            .withConversionContext("bleh ")
            .build();
    parser.parse("--foo", "quux");
    ConverterWithContextTestOptions options =
        parser.getOptions(ConverterWithContextTestOptions.class);
    assertThat(options.foo).isEqualTo("bleh quux");
  }

  public static class ImplicitDependencyOptions extends OptionsBase {
    @Option(
      name = "first",
      implicitRequirements = "--second=second",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null"
    )
    public String first;

    @Option(
      name = "second",
      implicitRequirements = "--third=third",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null"
    )
    public String second;

    @Option(
      name = "third",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null"
    )
    public String third;
  }

  @Test
  public void implicitDependencyHasImplicitDependency() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ImplicitDependencyOptions.class).build();
    parser.parse(
        OptionPriority.PriorityCategory.COMMAND_LINE, null, ImmutableList.of("--first=first"));
    assertThat(parser.getOptions(ImplicitDependencyOptions.class).first).isEqualTo("first");
    assertThat(parser.getOptions(ImplicitDependencyOptions.class).second).isEqualTo("second");
    assertThat(parser.getOptions(ImplicitDependencyOptions.class).third).isEqualTo("third");
    assertThat(parser.getWarnings()).isEmpty();
  }

  public static class BadImplicitDependencyOptions extends OptionsBase {
    @Option(
      name = "first",
      implicitRequirements = "xxx",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null"
    )
    public String first;
  }

  @Test
  public void badImplicitDependency() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(BadImplicitDependencyOptions.class).build();
    try {
      parser.parse(
          OptionPriority.PriorityCategory.COMMAND_LINE, null, ImmutableList.of("--first=first"));
    } catch (AssertionError e) {
      /* Expected error. */
      return;
    }
    fail();
  }

  public static class BadExpansionOptions extends OptionsBase {
    @Option(
      name = "first",
      expansion = {"xxx"},
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null"
    )
    public Void first;
  }

  @Test
  public void badExpansionOptions() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(BadExpansionOptions.class).build();
    try {
      parser.parse(OptionPriority.PriorityCategory.COMMAND_LINE, null, ImmutableList.of("--first"));
    } catch (AssertionError e) {
      /* Expected error. */
      return;
    }
    fail();
  }

  /** ExpansionOptions */
  public static class ExpansionOptions extends OptionsBase {
    @Option(
      name = "underlying",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null"
    )
    public String underlying;

    @Option(
      name = "expands",
      expansion = {"--underlying=from_expansion"},
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null"
    )
    public Void expands;
  }

  @Test
  public void describeOptionsWithExpansion() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(ExpansionOptions.class).build();
    String usage =
        parser.describeOptionsWithDeprecatedCategories(
            ImmutableMap.of(), OptionsParser.HelpVerbosity.LONG);
    assertThat(usage).contains("  --expands\n      Expands to: --underlying=from_expansion");
  }

  @Test
  public void overrideExpansionWithExplicit() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(ExpansionOptions.class).build();
    parser.parse(
        OptionPriority.PriorityCategory.COMMAND_LINE,
        null,
        ImmutableList.of("--expands", "--underlying=direct_value"));
    ExpansionOptions options = parser.getOptions(ExpansionOptions.class);
    assertThat(options.underlying).isEqualTo("direct_value");
    assertThat(parser.getWarnings()).isEmpty();
  }

  @Test
  public void testExpansionOriginIsPropagatedToOption() throws OptionsParsingException {
    OptionsParser parser = OptionsParser.builder().optionsClasses(ExpansionOptions.class).build();
    parser.parse(OptionPriority.PriorityCategory.COMMAND_LINE, null, ImmutableList.of("--expands"));
    OptionValueDescription expansionDescription = parser.getOptionValueDescription("expands");
    assertThat(expansionDescription).isNotNull();

    // In order to have access to the ParsedOptionDescription tracked by the value of 'underlying'
    // we have to know that this option is a "single valued" option.
    OptionValueDescription optionValue = parser.getOptionValueDescription("underlying");
    assertThat(optionValue).isNotNull();
    assertThat(optionValue.getSourceString()).matches("expanded from option '--expands'");
    assertThat(optionValue.getCanonicalInstances()).isNotNull();
    assertThat(optionValue.getCanonicalInstances()).hasSize(1);

    ParsedOptionDescription effectiveInstance = optionValue.getCanonicalInstances().get(0);
    assertThat(effectiveInstance.getExpandedFrom().getOptionDefinition())
        .isSameInstanceAs(expansionDescription.getOptionDefinition());
    assertThat(effectiveInstance.getImplicitDependent()).isNull();

    assertThat(parser.getWarnings()).isEmpty();
  }

  @Test
  public void overrideExplicitWithExpansion() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(ExpansionOptions.class).build();
    parser.parse(
        OptionPriority.PriorityCategory.COMMAND_LINE,
        null,
        ImmutableList.of("--underlying=direct_value", "--expands"));
    ExpansionOptions options = parser.getOptions(ExpansionOptions.class);
    assertThat(options.underlying).isEqualTo("from_expansion");
    assertThat(parser.getWarnings())
        .containsExactly(
            "option '--expands' was expanded and now overrides the explicit option "
                + "--underlying=direct_value with --underlying=from_expansion");
  }

  @Test
  public void noWarningsWhenOverrideExplicitWithExpansion() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(ExpansionOptions.class).build();
    parser.parse(
        OptionPriority.PriorityCategory.RC_FILE,
        null,
        ImmutableList.of("--underlying=direct_value", "--expands"));
    ExpansionOptions options = parser.getOptions(ExpansionOptions.class);
    assertThat(options.underlying).isEqualTo("from_expansion");
    assertThat(parser.getWarnings())
        .doesNotContain(
            "option '--expands' was expanded and now overrides the explicit option "
                + "--underlying=direct_value with --underlying=from_expansion");
  }

  @Test
  public void noWarningsWhenValueNotChanged() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(ExpansionOptions.class).build();
    parser.parse(
        OptionPriority.PriorityCategory.COMMAND_LINE,
        null,
        ImmutableList.of("--underlying=from_expansion", "--expands"));
    ExpansionOptions options = parser.getOptions(ExpansionOptions.class);
    assertThat(options.underlying).isEqualTo("from_expansion");
    // The expansion option overrides the explicit option, but it is the same value, so expect
    // no warning.
    assertThat(parser.getWarnings()).isEmpty();
  }

  /** ExpansionOptions to allow-multiple values. */
  public static class ExpansionOptionsToMultiple extends OptionsBase {
    @Option(
        name = "underlying",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "null",
        allowMultiple = true)
    public List<String> underlying;

    @Option(
        name = "expands",
        expansion = {"--underlying=from_expansion"},
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "null")
    public Void expands;
  }

  /**
   * Makes sure the expansion options are expanded in the right order if they affect flags that
   * allow multiples.
   */
  @Test
  public void multipleExpansionOptionsWithValue() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ExpansionOptionsToMultiple.class).build();
    parser.parse(
        OptionPriority.PriorityCategory.COMMAND_LINE,
        null,
        ImmutableList.of("--expands", "--underlying=direct_value", "--expands"));
    ExpansionOptionsToMultiple options = parser.getOptions(ExpansionOptionsToMultiple.class);
    assertThat(options.underlying)
        .containsExactly("from_expansion", "direct_value", "from_expansion")
        .inOrder();
    assertThat(parser.getWarnings()).isEmpty();
  }

  @Test
  public void checkExpansionValueWarning() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(ExpansionOptions.class).build();
    parser.parse(
        OptionPriority.PriorityCategory.COMMAND_LINE, null, ImmutableList.of("--expands=no"));
    ExpansionOptions options = parser.getOptions(ExpansionOptions.class);
    assertThat(options.underlying).isEqualTo("from_expansion");
    assertThat(parser.getWarnings())
        .containsExactly(
            "option '--expands' is an expansion option. It does not accept values, "
                + "and does not change its expansion based on the value provided. "
                + "Value 'no' will be ignored.");
  }

  @Test
  public void overrideWithHigherPriority() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(NullTestOptions.class).build();
    parser.parse(OptionPriority.PriorityCategory.RC_FILE, null, ImmutableList.of("--simple=a"));
    assertThat(parser.getOptions(NullTestOptions.class).simple).isEqualTo("a");
    parser.parse(
        OptionPriority.PriorityCategory.COMMAND_LINE, null, ImmutableList.of("--simple=b"));
    assertThat(parser.getOptions(NullTestOptions.class).simple).isEqualTo("b");
  }

  @Test
  public void overrideWithLowerPriority() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(NullTestOptions.class).build();
    parser.parse(
        OptionPriority.PriorityCategory.COMMAND_LINE, null, ImmutableList.of("--simple=a"));
    assertThat(parser.getOptions(NullTestOptions.class).simple).isEqualTo("a");
    parser.parse(OptionPriority.PriorityCategory.RC_FILE, null, ImmutableList.of("--simple=b"));
    assertThat(parser.getOptions(NullTestOptions.class).simple).isEqualTo("a");
  }

  @Test
  public void getOptionValueDescriptionWithNonExistingOption() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(NullTestOptions.class).build();
    assertThrows(
        IllegalArgumentException.class, () -> parser.getOptionValueDescription("notexisting"));
  }

  @Test
  public void getOptionValueDescriptionWithoutValue() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(NullTestOptions.class).build();
    assertThat(parser.getOptionValueDescription("simple")).isNull();
  }

  @Test
  public void getOptionValueDescriptionWithValue() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(NullTestOptions.class).build();
    parser.parse(
        OptionPriority.PriorityCategory.COMMAND_LINE,
        "my description",
        ImmutableList.of("--simple=abc"));
    OptionValueDescription result = parser.getOptionValueDescription("simple");
    assertThat(result).isNotNull();
    assertThat(result.getOptionDefinition().getOptionName()).isEqualTo("simple");
    assertThat(result.getValue()).isEqualTo("abc");
    assertThat(result.getSourceString()).isEqualTo("my description");
    assertThat(result.getCanonicalInstances()).isNotNull();
    assertThat(result.getCanonicalInstances()).hasSize(1);

    ParsedOptionDescription singleOptionInstance = result.getCanonicalInstances().get(0);
    assertThat(singleOptionInstance.getPriority().getPriorityCategory())
        .isEqualTo(OptionPriority.PriorityCategory.COMMAND_LINE);
    assertThat(singleOptionInstance.getOptionDefinition().isExpansionOption()).isFalse();
    assertThat(singleOptionInstance.getImplicitDependent()).isNull();
    assertThat(singleOptionInstance.getExpandedFrom()).isNull();
  }

  public static class ImplicitDependencyWarningOptions extends OptionsBase {
    @Option(
      name = "first",
      implicitRequirements = "--second=requiredByFirst",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "false"
    )
    public boolean first;

    @Option(
      name = "second",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null"
    )
    public String second;

    @Option(
      name = "third",
      implicitRequirements = "--second=requiredByThird",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null"
    )
    public String third;
  }

  @Test
  public void warningForImplicitOverridingExplicitOption() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ImplicitDependencyWarningOptions.class).build();
    parser.parse("--second=second", "--first");
    assertThat(parser.getWarnings())
        .containsExactly(
            "option '--second' is implicitly defined by option '--first'; the implicitly set value "
                + "overrides the previous one");
  }

  @Test
  public void warningForExplicitOverridingImplicitOption() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ImplicitDependencyWarningOptions.class).build();
    parser.parse("--first");
    assertThat(parser.getWarnings()).isEmpty();
    parser.parse("--second=second");
    assertThat(parser.getWarnings())
        .containsExactly(
            "A new value for option '--second' overrides a previous implicit setting of that "
                + "option by option '--first'");
  }

  @Test
  public void warningForExplicitOverridingImplicitOptionInSameCall() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ImplicitDependencyWarningOptions.class).build();
    parser.parse("--first", "--second=second");
    assertThat(parser.getWarnings())
        .containsExactly(
            "A new value for option '--second' overrides a previous implicit setting of that "
                + "option by option '--first'");
  }

  @Test
  public void warningForImplicitOverridingImplicitOption() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ImplicitDependencyWarningOptions.class).build();
    parser.parse("--first");
    assertThat(parser.getWarnings()).isEmpty();
    parser.parse("--third=third");
    assertThat(parser.getWarnings())
        .containsExactly(
            "option '--second' is implicitly defined by both option '--first' and "
                + "option '--third=third'");
  }

  @Test
  public void noWarningsForNonConflictingOverrides() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ImplicitDependencyWarningOptions.class).build();
    parser.parse("--first", "--second=requiredByFirst");
    ImplicitDependencyWarningOptions options =
        parser.getOptions(ImplicitDependencyWarningOptions.class);
    assertThat(options.first).isTrue();
    assertThat(options.second).isEqualTo("requiredByFirst");
    assertThat(parser.getWarnings()).isEmpty();
  }

  @Test
  public void warningForImplicitRequirementsExpandedForDefaultValue() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ImplicitDependencyWarningOptions.class).build();
    parser.parse("--nofirst");
    ImplicitDependencyWarningOptions options =
        parser.getOptions(ImplicitDependencyWarningOptions.class);
    assertThat(options.first).isFalse();
    assertThat(options.second).isEqualTo("requiredByFirst");
    assertThat(parser.getWarnings())
        .containsExactly(
            "--nofirst sets option '--first' to its default value. Since this option has implicit "
                + "requirements that are set whenever the option is explicitly provided, "
                + "regardless of the value, this will behave differently than letting a default "
                + "be a default. Specifically, this options expands to "
                + "{--second=requiredByFirst}.");
  }

  @Test
  public void testDependentOriginIsPropagatedToOption() throws OptionsParsingException {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ImplicitDependencyWarningOptions.class).build();
    parser.parse(OptionPriority.PriorityCategory.COMMAND_LINE, null, ImmutableList.of("--first"));
    OptionValueDescription first = parser.getOptionValueDescription("first");
    assertThat(first).isNotNull();
    assertThat(first.getCanonicalInstances()).hasSize(1);

    OptionValueDescription second = parser.getOptionValueDescription("second");
    assertThat(second).isNotNull();
    assertThat(second.getSourceString()).matches("implicit requirement of option '--first'");
    // Implicit requirements don't get listed as canonical. Check that this claims to be empty,
    // which tells us that the option instance is correctly tracking that is originated as an
    // implicit requirement.
    assertThat(second.getCanonicalInstances()).isNotNull();
    assertThat(second.getCanonicalInstances()).hasSize(0);
    assertThat(parser.getWarnings()).isEmpty();
  }

  /**
   * Options for testing the behavior of canonicalization when an option implicitly requires a
   * repeatable option.
   */
  public static class ImplicitDependencyOnAllowMultipleOptions extends OptionsBase {
    @Option(
      name = "first",
      implicitRequirements = "--second=requiredByFirst",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "false"
    )
    public boolean first;

    @Option(
      name = "second",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null",
      allowMultiple = true
    )
    public List<String> second;

    @Option(
      name = "third",
      implicitRequirements = "--second=requiredByThird",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null"
    )
    public String third;
  }

  @Test
  public void testCanonicalizeExcludesImplicitDependencyOnRepeatableOption()
      throws OptionsParsingException {
    OptionsParser parser =
        OptionsParser.builder()
            .optionsClasses(ImplicitDependencyOnAllowMultipleOptions.class)
            .build();
    parser.parse(
        OptionPriority.PriorityCategory.COMMAND_LINE,
        null,
        ImmutableList.of("--first", "--second=explicitValue"));
    OptionValueDescription first = parser.getOptionValueDescription("first");
    assertThat(first).isNotNull();
    assertThat(first.getCanonicalInstances()).hasSize(1);

    OptionValueDescription second = parser.getOptionValueDescription("second");
    assertThat(second).isNotNull();
    assertThat(second.getSourceString()).matches("implicit requirement of option '--first', null");
    // Implicit requirements don't get listed as canonical. Check that this excludes the implicit
    // value, but still tracks the explicit one.
    assertThat(second.getCanonicalInstances()).isNotNull();
    assertThat(second.getCanonicalInstances()).hasSize(1);
    assertThat(parser.canonicalize()).containsExactly("--first=1", "--second=explicitValue");

    ImplicitDependencyOnAllowMultipleOptions options =
        parser.getOptions(ImplicitDependencyOnAllowMultipleOptions.class);
    assertThat(options.first).isTrue();
    assertThat(options.second).containsExactly("explicitValue", "requiredByFirst");
    assertThat(parser.getWarnings()).isEmpty();
  }

  @Test
  public void testCanonicalizeExcludesImplicitDependencyForOtherwiseUnmentionedRepeatableOption()
      throws OptionsParsingException {
    OptionsParser parser =
        OptionsParser.builder()
            .optionsClasses(ImplicitDependencyOnAllowMultipleOptions.class)
            .build();
    parser.parse(OptionPriority.PriorityCategory.COMMAND_LINE, null, ImmutableList.of("--first"));
    OptionValueDescription first = parser.getOptionValueDescription("first");
    assertThat(first).isNotNull();
    assertThat(first.getCanonicalInstances()).hasSize(1);

    OptionValueDescription second = parser.getOptionValueDescription("second");
    assertThat(second).isNotNull();
    assertThat(second.getSourceString()).matches("implicit requirement of option '--first'");
    // Implicit requirements don't get listed as canonical. Check that this excludes the implicit
    // value, leaving behind no mention of second.
    assertThat(second.getCanonicalInstances()).isNotNull();
    assertThat(second.getCanonicalInstances()).isEmpty();
    assertThat(parser.canonicalize()).containsExactly("--first=1");

    ImplicitDependencyOnAllowMultipleOptions options =
        parser.getOptions(ImplicitDependencyOnAllowMultipleOptions.class);
    assertThat(options.first).isTrue();
    assertThat(options.second).containsExactly("requiredByFirst");
    assertThat(parser.getWarnings()).isEmpty();
  }

  public static class WarningOptions extends OptionsBase {
    @Deprecated
    @Option(
      name = "first",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null"
    )
    public Void first;

    @Deprecated
    @Option(
      name = "second",
      allowMultiple = true,
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null"
    )
    public List<String> second;

    @Deprecated
    @Option(
      name = "third",
      expansion = "--fourth=true",
      abbrev = 't',
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null"
    )
    public Void third;

    @Option(
      name = "fourth",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "false"
    )
    public boolean fourth;
  }

  @Test
  public void deprecationWarning() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(WarningOptions.class).build();
    parser.parse(OptionPriority.PriorityCategory.COMMAND_LINE, null, ImmutableList.of("--first"));
    assertThat(parser.getWarnings()).containsExactly("Option 'first' is deprecated");
  }

  @Test
  public void deprecationWarningForListOption() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(WarningOptions.class).build();
    parser.parse(
        OptionPriority.PriorityCategory.COMMAND_LINE, null, ImmutableList.of("--second=a"));
    assertThat(parser.getWarnings()).isEqualTo(ImmutableList.of("Option 'second' is deprecated"));
  }

  @Test
  public void deprecationWarningForExpansionOption() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(WarningOptions.class).build();
    parser.parse(OptionPriority.PriorityCategory.COMMAND_LINE, null, ImmutableList.of("--third"));
    assertThat(parser.getWarnings()).isEqualTo(ImmutableList.of("Option 'third' is deprecated"));
    assertThat(parser.getOptions(WarningOptions.class).fourth).isTrue();
  }

  @Test
  public void deprecationWarningForAbbreviatedExpansionOption() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(WarningOptions.class).build();
    parser.parse(OptionPriority.PriorityCategory.COMMAND_LINE, null, ImmutableList.of("-t"));
    assertThat(parser.getWarnings()).isEqualTo(ImmutableList.of("Option 'third' is deprecated"));
    assertThat(parser.getOptions(WarningOptions.class).fourth).isTrue();
  }

  public static class NewWarningOptions extends OptionsBase {
    @Option(
      name = "first",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null",
      deprecationWarning = "it's gone"
    )
    public Void first;

    @Option(
      name = "second",
      allowMultiple = true,
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null",
      deprecationWarning = "sorry, no replacement"
    )
    public List<String> second;

    @Option(
      name = "third",
      expansion = "--fourth=true",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null",
      deprecationWarning = "use --forth instead"
    )
    public Void third;

    @Option(
      name = "fourth",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "false"
    )
    public boolean fourth;
  }

  @Test
  public void newDeprecationWarning() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(NewWarningOptions.class).build();
    parser.parse(OptionPriority.PriorityCategory.COMMAND_LINE, null, ImmutableList.of("--first"));
    assertThat(parser.getWarnings())
        .isEqualTo(ImmutableList.of("Option 'first' is deprecated: it's gone"));
  }

  @Test
  public void newDeprecationWarningForListOption() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(NewWarningOptions.class).build();
    parser.parse(
        OptionPriority.PriorityCategory.COMMAND_LINE, null, ImmutableList.of("--second=a"));
    assertThat(parser.getWarnings())
        .isEqualTo(ImmutableList.of("Option 'second' is deprecated: sorry, no replacement"));
  }

  @Test
  public void newDeprecationWarningForExpansionOption() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(NewWarningOptions.class).build();
    parser.parse(OptionPriority.PriorityCategory.COMMAND_LINE, null, ImmutableList.of("--third"));
    assertThat(parser.getWarnings())
        .isEqualTo(ImmutableList.of("Option 'third' is deprecated: use --forth instead"));
    assertThat(parser.getOptions(NewWarningOptions.class).fourth).isTrue();
  }

  public static class ExpansionWarningOptions extends OptionsBase {
    @Option(
      name = "first",
      expansion = "--underlying=expandedFromFirst",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null"
    )
    public Void first;

    @Option(
      name = "second",
      expansion = "--underlying=expandedFromSecond",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null"
    )
    public Void second;

    @Option(
      name = "underlying",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null"
    )
    public String underlying;
  }

  @Test
  public void warningForExpansionOverridingExplicitOption() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ExpansionWarningOptions.class).build();
    parser.parse("--underlying=underlying", "--first");
    assertThat(parser.getWarnings())
        .containsExactly(
            "option '--first' was expanded and now overrides the explicit option "
                + "--underlying=underlying with --underlying=expandedFromFirst");
  }

  @Test
  public void warningForTwoConflictingExpansionOptions() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ExpansionWarningOptions.class).build();
    parser.parse("--first", "--second");
    assertThat(parser.getWarnings())
        .contains(
            "option '--underlying' was expanded from both option '--first' and option "
                + "'--second'");
  }

  @Test
  public void noWarningForTwoConflictingExpansionOptionsFromRcFile() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ExpansionWarningOptions.class).build();
    parser.parse(
        OptionPriority.PriorityCategory.RC_FILE, null, ImmutableList.of("--first", "--second"));
    assertThat(parser.getWarnings())
        .doesNotContain(
            "option '--underlying' was expanded from both option '--first' and option "
                + "'--second'");
  }

  // This test is here to make sure that nobody accidentally changes the
  // order of the enum values and breaks the implicit assumptions elsewhere
  // in the code.
  @Test
  public void optionPrioritiesAreCorrectlyOrdered() throws Exception {
    assertThat(OptionPriority.PriorityCategory.values()).hasLength(6);
    assertThat(OptionPriority.PriorityCategory.DEFAULT)
        .isLessThan(OptionPriority.PriorityCategory.COMPUTED_DEFAULT);
    assertThat(OptionPriority.PriorityCategory.COMPUTED_DEFAULT)
        .isLessThan(OptionPriority.PriorityCategory.RC_FILE);
    assertThat(OptionPriority.PriorityCategory.RC_FILE)
        .isLessThan(OptionPriority.PriorityCategory.COMMAND_LINE);
    assertThat(OptionPriority.PriorityCategory.COMMAND_LINE)
        .isLessThan(OptionPriority.PriorityCategory.INVOCATION_POLICY);
    assertThat(OptionPriority.PriorityCategory.INVOCATION_POLICY)
        .isLessThan(OptionPriority.PriorityCategory.SOFTWARE_REQUIREMENT);
  }

  public static class IntrospectionExample extends OptionsBase {
    @Option(
      name = "alpha",
      category = "one",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "alphaDefaultValue"
    )
    public String alpha;

    @Option(
      name = "beta",
      category = "one",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "betaDefaultValue"
    )
    public String beta;

    @Option(
      name = "gamma",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "gammaDefaultValue"
    )
    public String gamma;

    @Option(
      name = "delta",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "deltaDefaultValue"
    )
    public String delta;

    @Option(
      name = "echo",
      metadataTags = {OptionMetadataTag.HIDDEN},
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "echoDefaultValue"
    )
    public String echo;
  }

  @Test
  public void asListOfUnparsedOptions() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(IntrospectionExample.class).build();
    parser.parse(
        OptionPriority.PriorityCategory.COMMAND_LINE,
        "source",
        ImmutableList.of("--alpha=one", "--gamma=two", "--echo=three"));
    List<ParsedOptionDescription> result = parser.asCompleteListOfParsedOptions();
    assertThat(result).isNotNull();
    assertThat(result).hasSize(3);

    assertThat(result.get(0).getOptionDefinition().getOptionName()).isEqualTo("alpha");
    assertThat(result.get(0).isDocumented()).isTrue();
    assertThat(result.get(0).isHidden()).isFalse();
    assertThat(result.get(0).getUnconvertedValue()).isEqualTo("one");
    assertThat(result.get(0).getSource()).isEqualTo("source");
    assertThat(result.get(0).getPriority().getPriorityCategory())
        .isEqualTo(OptionPriority.PriorityCategory.COMMAND_LINE);

    assertThat(result.get(1).getOptionDefinition().getOptionName()).isEqualTo("gamma");
    assertThat(result.get(1).isDocumented()).isFalse();
    assertThat(result.get(1).isHidden()).isFalse();
    assertThat(result.get(1).getUnconvertedValue()).isEqualTo("two");
    assertThat(result.get(1).getSource()).isEqualTo("source");
    assertThat(result.get(1).getPriority().getPriorityCategory())
        .isEqualTo(OptionPriority.PriorityCategory.COMMAND_LINE);

    assertThat(result.get(2).getOptionDefinition().getOptionName()).isEqualTo("echo");
    assertThat(result.get(2).isDocumented()).isFalse();
    assertThat(result.get(2).isHidden()).isTrue();
    assertThat(result.get(2).getUnconvertedValue()).isEqualTo("three");
    assertThat(result.get(2).getSource()).isEqualTo("source");
    assertThat(result.get(2).getPriority().getPriorityCategory())
        .isEqualTo(OptionPriority.PriorityCategory.COMMAND_LINE);

    assertThat(parser.getWarnings()).isEmpty();
  }

  @Test
  public void asListOfExplicitOptions() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(IntrospectionExample.class).build();
    parser.parse(
        OptionPriority.PriorityCategory.COMMAND_LINE,
        "source",
        ImmutableList.of("--alpha=one", "--gamma=two"));
    List<ParsedOptionDescription> result = parser.asListOfExplicitOptions();
    assertThat(result).isNotNull();
    assertThat(result).hasSize(2);

    assertThat(result.get(0).getOptionDefinition().getOptionName()).isEqualTo("alpha");
    assertThat(result.get(0).isDocumented()).isTrue();
    assertThat(result.get(0).getUnconvertedValue()).isEqualTo("one");
    assertThat(result.get(0).getSource()).isEqualTo("source");
    assertThat(result.get(0).getPriority().getPriorityCategory())
        .isEqualTo(OptionPriority.PriorityCategory.COMMAND_LINE);

    assertThat(result.get(1).getOptionDefinition().getOptionName()).isEqualTo("gamma");
    assertThat(result.get(1).isDocumented()).isFalse();
    assertThat(result.get(1).getUnconvertedValue()).isEqualTo("two");
    assertThat(result.get(1).getSource()).isEqualTo("source");
    assertThat(result.get(1).getPriority().getPriorityCategory())
        .isEqualTo(OptionPriority.PriorityCategory.COMMAND_LINE);

    assertThat(parser.getWarnings()).isEmpty();
  }

  private static void assertOptionValue(
      String expectedName, Object expectedValue, OptionValueDescription actual) {
    assertThat(actual).isNotNull();
    assertThat(actual.getOptionDefinition().getOptionName()).isEqualTo(expectedName);
    assertThat(actual.getValue()).isEqualTo(expectedValue);
  }

  private static void assertOptionValue(
      String expectedName,
      Object expectedValue,
      OptionPriority.PriorityCategory expectedPriority,
      String expectedSource,
      OptionValueDescription actual) {
    assertOptionValue(expectedName, expectedValue, actual);
    assertThat(actual.getSourceString()).isEqualTo(expectedSource);
    assertThat(actual.getCanonicalInstances()).isNotEmpty();
    assertThat(actual.getCanonicalInstances().get(0).getPriority().getPriorityCategory())
        .isEqualTo(expectedPriority);
  }

  @Test
  public void asListOfEffectiveOptions() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(IntrospectionExample.class).build();
    parser.parse(
        OptionPriority.PriorityCategory.COMMAND_LINE,
        "command line source",
        ImmutableList.of(
            "--alpha=alphaValueSetOnCommandLine", "--gamma=gammaValueSetOnCommandLine"));
    List<OptionValueDescription> result = parser.asListOfOptionValues();
    assertThat(result).isNotNull();
    assertThat(result).hasSize(5);
    HashMap<String, OptionValueDescription> map = new HashMap<>();
    for (OptionValueDescription description : result) {
      map.put(description.getOptionDefinition().getOptionName(), description);
    }

    // All options in IntrospectionExample are single-valued options, and so have a 1:1 relationship
    // with the --flag=value option instance they came from (if any).
    assertOptionValue(
        "alpha",
        "alphaValueSetOnCommandLine",
        OptionPriority.PriorityCategory.COMMAND_LINE,
        "command line source",
        map.get("alpha"));
    assertOptionValue(
        "gamma",
        "gammaValueSetOnCommandLine",
        OptionPriority.PriorityCategory.COMMAND_LINE,
        "command line source",
        map.get("gamma"));
    assertOptionValue("beta", "betaDefaultValue", map.get("beta"));
    assertOptionValue("delta", "deltaDefaultValue", map.get("delta"));
    assertOptionValue("echo", "echoDefaultValue", map.get("echo"));
    assertThat(parser.getWarnings()).isEmpty();
  }

  public static class ListExample extends OptionsBase {
    @Option(
      name = "alpha",
      converter = StringConverter.class,
      allowMultiple = true,
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null"
    )
    public List<String> alpha;
  }

  // Regression tests for bug:
  // "--option from blazerc unexpectedly overrides --option from command line"
  @Test
  public void overrideListOptions() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(ListExample.class).build();
    parser.parse(
        OptionPriority.PriorityCategory.COMMAND_LINE,
        "command line source",
        ImmutableList.of("--alpha=cli"));
    parser.parse(
        OptionPriority.PriorityCategory.RC_FILE,
        "rc file origin",
        ImmutableList.of("--alpha=rc1", "--alpha=rc2"));
    assertThat(parser.getOptions(ListExample.class).alpha)
        .isEqualTo(ImmutableList.of("rc1", "rc2", "cli"));
  }

  @Test
  public void testDashDash() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(ExampleFoo.class).build();

    parser.parse(
        PriorityCategory.COMMAND_LINE,
        "command line source",
        ImmutableList.of("--foo=woohoo", "residue", "--", "--bar=42"));

    assertThat(parser.getResidue()).hasSize(2);
    assertThat(parser.getResidue()).containsExactly("residue", "--bar=42");
    assertThat(parser.getPreDoubleDashResidue()).hasSize(1);
    assertThat(parser.getPreDoubleDashResidue()).containsExactly("residue");
  }

  @Test
  public void listOptionsHaveCorrectPriorities() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(ListExample.class).build();
    parser.parse(
        PriorityCategory.COMMAND_LINE,
        "command line source, part 1",
        ImmutableList.of("--alpha=cli1", "--alpha=cli2"));
    parser.parse(
        PriorityCategory.COMMAND_LINE,
        "command line source, part 2",
        ImmutableList.of("--alpha=cli3", "--alpha=cli4"));
    parser.parse(
        PriorityCategory.RC_FILE, "rc file origin", ImmutableList.of("--alpha=rc1", "--alpha=rc2"));

    OptionValueDescription alphaValue = parser.getOptionValueDescription("alpha");

    List<ParsedOptionDescription> parsedOptions = alphaValue.getCanonicalInstances();
    System.out.println("parsedOptions:\n" + parsedOptions);

    assertThat(parsedOptions).hasSize(6);
    assertThat(parsedOptions.get(0).getSource()).matches("rc file origin");
    assertThat(parsedOptions.get(0).getUnconvertedValue()).matches("rc1");
    assertThat(parsedOptions.get(1).getSource()).matches("rc file origin");
    assertThat(parsedOptions.get(1).getUnconvertedValue()).matches("rc2");
    assertThat(parsedOptions.get(2).getSource()).matches("command line source, part 1");
    assertThat(parsedOptions.get(2).getUnconvertedValue()).matches("cli1");
    assertThat(parsedOptions.get(3).getSource()).matches("command line source, part 1");
    assertThat(parsedOptions.get(3).getUnconvertedValue()).matches("cli2");
    assertThat(parsedOptions.get(4).getSource()).matches("command line source, part 2");
    assertThat(parsedOptions.get(4).getUnconvertedValue()).matches("cli3");
    assertThat(parsedOptions.get(5).getSource()).matches("command line source, part 2");
    assertThat(parsedOptions.get(5).getUnconvertedValue()).matches("cli4");
    assertThat(parser.getWarnings()).isEmpty();
  }

  public static class CommaSeparatedOptionsExample extends OptionsBase {
    @Option(
      name = "alpha",
      converter = CommaSeparatedOptionListConverter.class,
      allowMultiple = true,
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null"
    )
    public List<String> alpha;
  }

  @Test
  public void commaSeparatedOptionsWithAllowMultiple() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(CommaSeparatedOptionsExample.class).build();
    parser.parse(
        OptionPriority.PriorityCategory.COMMAND_LINE,
        "command line source",
        ImmutableList.of("--alpha=one", "--alpha=two,three"));
    parser.parse(
        OptionPriority.PriorityCategory.RC_FILE,
        "rc file origin",
        ImmutableList.of("--alpha=rc1,rc2"));
    assertThat(parser.getOptions(CommaSeparatedOptionsExample.class).alpha)
        .isEqualTo(ImmutableList.of("rc1", "rc2", "one", "two", "three"));
    assertThat(parser.getWarnings()).isEmpty();
  }

  @Test
  public void commaSeparatedListOptionsHaveCorrectPriorities() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(CommaSeparatedOptionsExample.class).build();
    parser.parse(
        OptionPriority.PriorityCategory.COMMAND_LINE,
        "command line source",
        ImmutableList.of("--alpha=one", "--alpha=two,three"));
    parser.parse(
        OptionPriority.PriorityCategory.RC_FILE,
        "rc file origin",
        ImmutableList.of("--alpha=rc1,rc2,rc3"));

    OptionValueDescription alphaValue = parser.getOptionValueDescription("alpha");
    List<ParsedOptionDescription> parsedOptions = alphaValue.getCanonicalInstances();

    assertThat(parsedOptions).hasSize(3);
    assertThat(parsedOptions.get(0).getSource()).matches("rc file origin");
    assertThat(parsedOptions.get(0).getUnconvertedValue()).matches("rc1,rc2,rc3");
    assertThat(parsedOptions.get(1).getSource()).matches("command line source");
    assertThat(parsedOptions.get(1).getUnconvertedValue()).matches("one");
    assertThat(parsedOptions.get(2).getSource()).matches("command line source");
    assertThat(parsedOptions.get(2).getUnconvertedValue()).matches("two,three");
    assertThat(parser.getWarnings()).isEmpty();
  }

  public static class Yesterday extends OptionsBase {

    @Option(
      name = "a",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "a"
    )
    public String a;

    @Option(
      name = "b",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "b"
    )
    public String b;

    @Option(
      name = "c",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null",
      expansion = {"--a=cExpansion"}
    )
    public Void c;

    @Option(
      name = "d",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null",
      allowMultiple = true
    )
    public List<String> d;

    @Option(
      name = "e",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null",
      implicitRequirements = {"--a=eRequirement"}
    )
    public String e;

    @Option(
      name = "f",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null",
      implicitRequirements = {"--b=fRequirement"}
    )
    public String f;

    @Option(
      name = "g",
      abbrev = 'h',
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "false"
    )
    public boolean g;
  }

  public static List<String> canonicalize(Class<? extends OptionsBase> optionsClass, String... args)
      throws OptionsParsingException {

    OptionsParser parser =
        OptionsParser.builder().optionsClasses(optionsClass).allowResidue(false).build();
    parser.parse(args);
    return parser.canonicalize();
  }

  @Test
  public void canonicalizeEasy() throws Exception {
    assertThat(canonicalize(Yesterday.class, "--a=x")).containsExactly("--a=x");
  }

  @Test
  public void canonicalizeSkipDuplicate() throws Exception {
    assertThat(canonicalize(Yesterday.class, "--a=y", "--a=x")).containsExactly("--a=x");
  }

  @Test
  public void canonicalizeExpands() throws Exception {
    assertThat(canonicalize(Yesterday.class, "--c")).containsExactly("--a=cExpansion");
  }

  @Test
  public void canonicalizeExpansionOverridesExplicit() throws Exception {
    assertThat(canonicalize(Yesterday.class, "--a=x", "--c")).containsExactly("--a=cExpansion");
  }

  @Test
  public void canonicalizeExplicitOverridesExpansion() throws Exception {
    assertThat(canonicalize(Yesterday.class, "--c", "--a=x")).containsExactly("--a=x");
  }

  @Test
  public void canonicalizeDoesNotReorder() throws Exception {
    assertThat(canonicalize(Yesterday.class, "--b=y", "--d=x", "--a=z"))
        .containsExactly("--b=y", "--d=x", "--a=z")
        .inOrder();
  }

  @Test
  public void canonicalizeImplicitDepsNotListed() throws Exception {
    // e's requirement overrides the explicit "a" here, so the "a" value is not in the canonical
    // form - the effective value is implied and the overridden value is lost.
    assertThat(canonicalize(Yesterday.class, "--a=x", "--e=y")).containsExactly("--e=y");
  }

  @Test
  public void canonicalizeSkipsDuplicateAndStillOmitsImplicitDeps() throws Exception {
    assertThat(canonicalize(Yesterday.class, "--e=x", "--e=y")).containsExactly("--e=y");
  }

  @Test
  public void implicitDepsAreNotInTheCanonicalOrderWhenTheyAreOverridden() throws Exception {
    assertThat(canonicalize(Yesterday.class, "--e=y", "--a=x"))
        .containsExactly("--e=y", "--a=x")
        .inOrder();
  }

  @Test
  public void implicitDepsAreNotInTheCanonicalOrder() throws Exception {
    // f requires a value of b, that is absent because it is implied.
    assertThat(canonicalize(Yesterday.class, "--f=z", "--a=x"))
        .containsExactly("--f=z", "--a=x")
        .inOrder();
  }

  @Test
  public void canonicalizeDoesNotSkipAllowMultiple() throws Exception {
    assertThat(canonicalize(Yesterday.class, "--d=a", "--d=b"))
        .containsExactly("--d=a", "--d=b").inOrder();
  }

  @Test
  public void canonicalizeReplacesAbbrevWithName() throws Exception {
    assertThat(canonicalize(Yesterday.class, "-h")).containsExactly("--g=1");
  }

  /**
   * Check that all forms of boolean flags are canonicalizes to the same form.
   *
   * The list of accepted values is from
   * {@link com.google.devtools.common.options.Converters.BooleanConverter}, and the value-less
   * --[no] form is controlled by {@link OptionsParserImpl#identifyOptionAndPossibleArgument}.
   */
  @Test
  public void canonicalizeNormalizesBooleanFlags() throws Exception {
    assertThat(canonicalize(Yesterday.class, "--g")).containsExactly("--g=1");
    assertThat(canonicalize(Yesterday.class, "--g=1")).containsExactly("--g=1");
    assertThat(canonicalize(Yesterday.class, "--g=true")).containsExactly("--g=1");
    assertThat(canonicalize(Yesterday.class, "--g=t")).containsExactly("--g=1");
    assertThat(canonicalize(Yesterday.class, "--g=yes")).containsExactly("--g=1");
    assertThat(canonicalize(Yesterday.class, "--g=y")).containsExactly("--g=1");

    assertThat(canonicalize(Yesterday.class, "--nog")).containsExactly("--g=0");
    assertThat(canonicalize(Yesterday.class, "--g=0")).containsExactly("--g=0");
    assertThat(canonicalize(Yesterday.class, "--g=false")).containsExactly("--g=0");
    assertThat(canonicalize(Yesterday.class, "--g=f")).containsExactly("--g=0");
    assertThat(canonicalize(Yesterday.class, "--g=no")).containsExactly("--g=0");
    assertThat(canonicalize(Yesterday.class, "--g=n")).containsExactly("--g=0");
  }

  public static class LongValueExample extends OptionsBase {
    @Option(
      name = "longval",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "2147483648"
    )
    public long longval;

    @Option(
      name = "intval",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "2147483647"
    )
    public int intval;
  }

  @Test
  public void parseLong() throws OptionsParsingException {
    OptionsParser parser = OptionsParser.builder().optionsClasses(LongValueExample.class).build();
    parser.parse("");
    LongValueExample result = parser.getOptions(LongValueExample.class);
    assertThat(result.longval).isEqualTo(2147483648L);
    assertThat(result.intval).isEqualTo(2147483647);

    parser.parse("--longval", Long.toString(Long.MIN_VALUE));
    result = parser.getOptions(LongValueExample.class);
    assertThat(result.longval).isEqualTo(Long.MIN_VALUE);

    parser.parse("--longval", "100");
    result = parser.getOptions(LongValueExample.class);
    assertThat(result.longval).isEqualTo(100);
  }

  @Test
  public void intOutOfBounds() {
    OptionsParser parser = OptionsParser.builder().optionsClasses(LongValueExample.class).build();
    OptionsParsingException e =
        assertThrows(OptionsParsingException.class, () -> parser.parse("--intval=2147483648"));
    assertThat(e).hasMessageThat().contains("'2147483648' is not an int");
  }

  public static class OldNameExample extends OptionsBase {
    @Option(
      name = "new_name",
      oldName = "old_name",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "defaultValue"
    )
    public String flag;

    @Option(
        name = "new_boolean_name",
        oldName = "old_boolean_name",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "false")
    public boolean booleanFlag;
  }

  @Test
  public void testOldName() throws OptionsParsingException {
    OptionsParser parser = OptionsParser.builder().optionsClasses(OldNameExample.class).build();
    parser.parse("--old_name=foo");
    OldNameExample result = parser.getOptions(OldNameExample.class);
    assertThat(result.flag).isEqualTo("foo");
    // Using old option name should cause a warning
    assertThat(parser.getWarnings())
        .contains("Option 'old_name' is deprecated: Use --new_name instead");
    assertThat(parser.getWarnings()).containsNoDuplicates();

    // Should also work by its new name.
    parser = OptionsParser.builder().optionsClasses(OldNameExample.class).build();
    parser.parse("--new_name=foo");
    result = parser.getOptions(OldNameExample.class);
    assertThat(result.flag).isEqualTo("foo");
    // Should be no warnings if the new name is used.
    assertThat(parser.getWarnings()).isEmpty();
  }

  @Test
  public void testOldName_repeatedFlag() throws OptionsParsingException {
    OptionsParser parser = OptionsParser.builder().optionsClasses(OldNameExample.class).build();
    parser.parse("--old_name=foo", "--old_name=bar");
    OldNameExample result = parser.getOptions(OldNameExample.class);
    assertThat(result.flag).isEqualTo("bar");
    // Using old option name should cause a warning
    assertThat(parser.getWarnings())
        .contains("Option 'old_name' is deprecated: Use --new_name instead");
    assertThat(parser.getWarnings()).containsNoDuplicates();
  }

  @Test
  public void testOldNameCanonicalization() throws Exception {
    assertThat(canonicalize(OldNameExample.class, "--old_name=foo"))
        .containsExactly("--new_name=foo");
  }

  @Test
  public void testOldName_booleanTrue() throws OptionsParsingException {
    OptionsParser parser = OptionsParser.builder().optionsClasses(OldNameExample.class).build();
    parser.parse("--old_boolean_name=true");
    OldNameExample result = parser.getOptions(OldNameExample.class);
    assertThat(result.booleanFlag).isTrue();
    // Using old option name should cause a warning.
    assertThat(parser.getWarnings())
        .contains("Option 'old_boolean_name' is deprecated: Use --new_boolean_name instead");
    assertThat(parser.getWarnings()).containsNoDuplicates();

    parser = OptionsParser.builder().optionsClasses(OldNameExample.class).build();
    parser.parse("--new_boolean_name=true");
    result = parser.getOptions(OldNameExample.class);
    assertThat(result.booleanFlag).isTrue();
    // Should be no warnings if the new name is used.
    assertThat(parser.getWarnings()).isEmpty();
  }

  @Test
  public void testOldName_booleanFalse() throws OptionsParsingException {
    OptionsParser parser = OptionsParser.builder().optionsClasses(OldNameExample.class).build();
    parser.parse("--old_boolean_name=false");
    OldNameExample result = parser.getOptions(OldNameExample.class);
    assertThat(result.booleanFlag).isFalse();
    // Using old option name should cause a warning.
    assertThat(parser.getWarnings())
        .contains("Option 'old_boolean_name' is deprecated: Use --new_boolean_name instead");
    assertThat(parser.getWarnings()).containsNoDuplicates();

    parser = OptionsParser.builder().optionsClasses(OldNameExample.class).build();
    parser.parse("--new_boolean_name=false");
    result = parser.getOptions(OldNameExample.class);
    assertThat(result.booleanFlag).isFalse();
    // Should be no warnings if the new name is used.
    assertThat(parser.getWarnings()).isEmpty();
  }

  @Test
  public void testOldName_specialBooleanSyntax() throws OptionsParsingException {
    OptionsParser parser = OptionsParser.builder().optionsClasses(OldNameExample.class).build();
    parser.parse("--old_boolean_name");
    OldNameExample result = parser.getOptions(OldNameExample.class);
    assertThat(result.booleanFlag).isTrue();
    // Using old option name should cause a warning.
    assertThat(parser.getWarnings())
        .contains("Option 'old_boolean_name' is deprecated: Use --new_boolean_name instead");
    assertThat(parser.getWarnings()).containsNoDuplicates();

    parser = OptionsParser.builder().optionsClasses(OldNameExample.class).build();
    parser.parse("--new_boolean_name");
    result = parser.getOptions(OldNameExample.class);
    assertThat(result.booleanFlag).isTrue();
    // Should be no warnings if the new name is used.
    assertThat(parser.getWarnings()).isEmpty();
  }

  @Test
  public void testOldName_negatedSpecialBooleanSyntax() throws OptionsParsingException {
    OptionsParser parser = OptionsParser.builder().optionsClasses(OldNameExample.class).build();
    parser.parse("--noold_boolean_name");
    OldNameExample result = parser.getOptions(OldNameExample.class);
    assertThat(result.booleanFlag).isFalse();
    // Using old option name should cause a warning.
    assertThat(parser.getWarnings())
        .contains("Option 'old_boolean_name' is deprecated: Use --new_boolean_name instead");
    assertThat(parser.getWarnings()).containsNoDuplicates();

    parser = OptionsParser.builder().optionsClasses(OldNameExample.class).build();
    parser.parse("--nonew_boolean_name");
    result = parser.getOptions(OldNameExample.class);
    assertThat(result.booleanFlag).isFalse();
    // Should be no warnings if the new name is used.
    assertThat(parser.getWarnings()).isEmpty();
  }

  @Test
  public void testOldName_repeatedBooleanFlag() throws OptionsParsingException {
    OptionsParser parser = OptionsParser.builder().optionsClasses(OldNameExample.class).build();
    parser.parse("--old_boolean_name=false", "--old_boolean_name");
    OldNameExample result = parser.getOptions(OldNameExample.class);
    assertThat(result.booleanFlag).isTrue();
    // Using old option name should cause a single warning even if the old name was specified
    // multiple times.
    assertThat(parser.getWarnings())
        .contains("Option 'old_boolean_name' is deprecated: Use --new_boolean_name instead");
    assertThat(parser.getWarnings()).containsNoDuplicates();
  }

  @Test
  public void testOldName_overriddenByNewName() throws OptionsParsingException {
    OptionsParser parser = OptionsParser.builder().optionsClasses(OldNameExample.class).build();
    parser.parse("--old_boolean_name=false", "--new_boolean_name");
    OldNameExample result = parser.getOptions(OldNameExample.class);
    assertThat(result.booleanFlag).isTrue();
    // Using old option name should cause a warning even when overridden by new name.
    assertThat(parser.getWarnings())
        .contains("Option 'old_boolean_name' is deprecated: Use --new_boolean_name instead");
    assertThat(parser.getWarnings()).containsNoDuplicates();
  }

  public static class OldNameNoWarningExample extends OptionsBase {
    @Option(
        name = "new_name",
        oldName = "old_name",
        oldNameWarning = false,
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "defaultValue")
    public String flag;
  }

  @Test
  public void testOldName_noWarning() throws OptionsParsingException {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(OldNameNoWarningExample.class).build();
    parser.parse("--old_name=foo");
    OldNameNoWarningExample result = parser.getOptions(OldNameNoWarningExample.class);
    assertThat(result.flag).isEqualTo("foo");
    // Using old option name should not cause a warning
    assertThat(parser.getWarnings()).isEmpty();
  }

  public static class ExampleBooleanFooOptions extends OptionsBase {
    @Option(
      name = "foo",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "false"
    )
    public boolean foo;
  }

  @Test
  public void testBooleanUnderscorePrefixError() {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ExampleBooleanFooOptions.class).build();
    OptionsParsingException e =
        assertThrows(
            "--no_foo should fail to parse.",
            OptionsParsingException.class,
            () -> parser.parse("--no_foo"));
    assertThat(e).hasMessageThat().contains("Unrecognized option: --no_foo");
  }

  /** Dummy options that declares it uses only core types. */
  @UsesOnlyCoreTypes
  public static class CoreTypesOptions extends OptionsBase {
    @Option(
      name = "foo",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "false"
    )
    public boolean foo;

    @Option(
      name = "bar",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "abc"
    )
    public String bar;
  }

  /** Dummy options that does not declare using only core types. */
  public static class NonCoreTypesOptions extends OptionsBase {
    @Option(
      name = "foo",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "false"
    )
    public boolean foo;
  }

  /** Dummy options that incorrectly claims to use only core types. */
  @UsesOnlyCoreTypes
  public static class BadCoreTypesOptions extends OptionsBase {
    /** Dummy unsafe type. */
    public static class Foo {
      public int i = 0;
    }

    /** Converter for Foo. */
    public static class FooConverter extends Converter.Contextless<Foo> {
      @Override
      public Foo convert(String input) throws OptionsParsingException {
        Foo foo = new Foo();
        foo.i = Integer.parseInt(input);
        return foo;
      }

      @Override
      public String getTypeDescription() {
        return "a foo";
      }
    }

    @Option(
      name = "foo",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null",
      converter = FooConverter.class
    )
    public Foo foo;
  }

  /** Dummy options that is unsafe for @UsesOnlyCoreTypes but doesn't use the annotation. */
  public static class SuperBadCoreTypesOptions extends OptionsBase {
    @Option(
      name = "foo",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null",
      converter = BadCoreTypesOptions.FooConverter.class
    )
    public BadCoreTypesOptions.Foo foo;
  }

  /**
   * Dummy options that illegally advertises @UsesOnlyCoreTypes, when its direct fields are fine but
   * its inherited fields are not.
   */
  @UsesOnlyCoreTypes
  public static class InheritedBadCoreTypesOptions extends SuperBadCoreTypesOptions {
    @Option(
      name = "bar",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "false"
    )
    public boolean bar;
  }

  @Test
  public void testUsesOnlyCoreTypes() {
    assertThat(OptionsParser.getUsesOnlyCoreTypes(CoreTypesOptions.class)).isTrue();
    assertThat(OptionsParser.getUsesOnlyCoreTypes(NonCoreTypesOptions.class)).isFalse();
  }

  @Test
  public void testValidationOfUsesOnlyCoreTypes() {
    OptionsParser.ConstructionException expected =
        assertThrows(
            "Should have detected illegal use of @UsesOnlyCoreTypes",
            OptionsParser.ConstructionException.class,
            () -> OptionsParser.getUsesOnlyCoreTypes(BadCoreTypesOptions.class));
    assertThat(expected)
        .hasMessageThat()
        .matches(
            "Options class '.*BadCoreTypesOptions' is marked as @UsesOnlyCoreTypes, but field "
                + "'foo' has type '.*Foo'");
  }

  @Test
  public void testValidationOfUsesOnlyCoreTypes_Inherited() {
    OptionsParser.ConstructionException expected =
        assertThrows(
            "Should have detected illegal use of @UsesOnlyCoreTypes",
            OptionsParser.ConstructionException.class,
            () -> OptionsParser.getUsesOnlyCoreTypes(InheritedBadCoreTypesOptions.class));
    assertThat(expected)
        .hasMessageThat()
        .matches(
            "Options class '.*InheritedBadCoreTypesOptions' is marked as @UsesOnlyCoreTypes, but "
                + "field 'foo' has type '.*Foo'");
  }

  /** Dummy options for testing getHelpCompletion() and visitOptions(). */
  public static class CompletionOptions extends OptionsBase {
    @Option(
      name = "secret",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "false"
    )
    public boolean secret;

    @Option(
        name = "b",
        documentationCategory = OptionDocumentationCategory.LOGGING,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "false"
      )
      public boolean b;

    @Option(
      name = "a",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "false"
    )
    public boolean a;
  }

  @Test
  public void getOptionsCompletionShouldFilterUndocumentedOptions() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(CompletionOptions.class).build();
    assertThat(parser.getOptionsCompletion().split("\n"))
        .isEqualTo(new String[] {"--a", "--noa", "--b", "--nob"});
  }

  @Test
  public void visitOptionsShouldFailWithoutPredicate() {
    checkThatVisitOptionsThrowsNullPointerException(null, option -> {}, "Missing predicate.");
  }

  @Test
  public void visitOptionsShouldFailWithoutVisitor() {
    checkThatVisitOptionsThrowsNullPointerException(option -> true, null, "Missing visitor.");
  }

  private static void checkThatVisitOptionsThrowsNullPointerException(
      Predicate<OptionDefinition> predicate,
      Consumer<OptionDefinition> visitor,
      String expectedMessage) {
    NullPointerException ex =
        assertThrows(
            NullPointerException.class,
            () ->
                OptionsParser.builder()
                    .optionsClasses(CompletionOptions.class)
                    .build()
                    .visitOptions(predicate, visitor));
    assertThat(ex).hasMessageThat().isEqualTo(expectedMessage);
  }

  @Test
  public void visitOptionsShouldReturnAllOptionsInOrder() throws Exception {
    assertThat(visitOptionsToCollectTheirNames(option -> true)).containsExactly("a", "b", "secret");
  }

  @Test
  public void visitOptionsShouldObeyPredicate() {
    assertThat(visitOptionsToCollectTheirNames(option -> false)).isEmpty();
    assertThat(visitOptionsToCollectTheirNames(option -> option.getOptionName().length() > 1))
        .containsExactly("secret");
  }

  private static List<String> visitOptionsToCollectTheirNames(
      Predicate<OptionDefinition> predicate) {
    List<String> names = new ArrayList<>();
    Consumer<OptionDefinition> visitor = option -> names.add(option.getOptionName());

    OptionsParser parser = OptionsParser.builder().optionsClasses(CompletionOptions.class).build();
    parser.visitOptions(predicate, visitor);

    return names;
  }

  @Test
  public void setOptionValueAtSpecificPriorityWithoutExpansion_setsOptionAndAddsParsedValue()
      throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(ExampleFoo.class).build();
    OptionInstanceOrigin origin =
        new OptionInstanceOrigin(
            OptionPriority.lowestOptionPriorityAtCategory(PriorityCategory.INVOCATION_POLICY),
            "invocation policy",
            /*implicitDependent=*/ null,
            /*expandedFrom=*/ null);
    OptionDefinition optionDefinition =
        FieldOptionDefinition.extractOptionDefinition(ExampleFoo.class.getField("foo"));

    parser.setOptionValueAtSpecificPriorityWithoutExpansion(origin, optionDefinition, "hello");

    assertThat(parser.getOptions(ExampleFoo.class).foo).isEqualTo("hello");
    assertThat(
            parser.asCompleteListOfParsedOptions().stream()
                .map(ParsedOptionDescription::getCommandLineForm))
        .containsExactly("--foo=hello");
  }

  @Test
  public void setOptionValueAtSpecificPriorityWithoutExpansion_addsFlagAlias() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().withAliasFlag("foo").optionsClasses(ExampleFoo.class).build();
    OptionInstanceOrigin origin =
        new OptionInstanceOrigin(
            OptionPriority.lowestOptionPriorityAtCategory(PriorityCategory.INVOCATION_POLICY),
            "invocation policy",
            /*implicitDependent=*/ null,
            /*expandedFrom=*/ null);
    OptionDefinition optionDefinition =
        FieldOptionDefinition.extractOptionDefinition(ExampleFoo.class.getField("foo"));

    parser.setOptionValueAtSpecificPriorityWithoutExpansion(origin, optionDefinition, "hi=bar");
    parser.parse("--hi=123");

    assertThat(parser.getOptions(ExampleFoo.class).foo).isEqualTo("hi=bar");
    assertThat(parser.getOptions(ExampleFoo.class).bar).isEqualTo(123);
    assertThat(
            parser.asCompleteListOfParsedOptions().stream()
                .map(ParsedOptionDescription::getCommandLineForm))
        .containsExactly("--bar=123", "--foo=hi=bar")
        .inOrder();
  }

  @Test
  public void setOptionValueAtSpecificPriorityWithoutExpansion_implicitReqs_setsTopFlagOnly()
      throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ImplicitDependencyOptions.class).build();
    OptionInstanceOrigin origin = createInvocationPolicyOrigin();
    OptionDefinition optionDefinition =
        FieldOptionDefinition.extractOptionDefinition(
            ImplicitDependencyOptions.class.getField("first"));

    parser.setOptionValueAtSpecificPriorityWithoutExpansion(origin, optionDefinition, "hello");

    ImplicitDependencyOptions options = parser.getOptions(ImplicitDependencyOptions.class);
    assertThat(options.first).isEqualTo("hello");
    assertThat(options.second).isNull();
    assertThat(options.third).isNull();
    assertThat(
            parser.asCompleteListOfParsedOptions().stream()
                .map(ParsedOptionDescription::getCommandLineForm))
        .containsExactly("--first=hello");
  }

  @Test
  public void setOptionValueAtSpecificPriorityWithoutExpansion_impliedFlag_setsValueSkipsParsed()
      throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ImplicitDependencyOptions.class).build();
    ParsedOptionDescription first =
        ParsedOptionDescription.newDummyInstance(
            FieldOptionDefinition.extractOptionDefinition(
                ImplicitDependencyOptions.class.getField("first")),
            createInvocationPolicyOrigin(),
            /* conversionContext= */ null);
    OptionInstanceOrigin origin =
        createInvocationPolicyOrigin(/*implicitDependent=*/ first, /*expandedFrom=*/ null);

    OptionDefinition optionDefinition =
        FieldOptionDefinition.extractOptionDefinition(
            ImplicitDependencyOptions.class.getField("second"));

    parser.setOptionValueAtSpecificPriorityWithoutExpansion(origin, optionDefinition, "hello");

    ImplicitDependencyOptions options = parser.getOptions(ImplicitDependencyOptions.class);
    assertThat(options.second).isEqualTo("hello");
    assertThat(options.third).isNull();
    assertThat(
            parser.asCompleteListOfParsedOptions().stream()
                .map(ParsedOptionDescription::getCommandLineForm))
        .isEmpty();
  }

  @Test
  public void setOptionValueAtSpecificPriorityWithoutExpansion_expandedFlag_setsValueAndParsed()
      throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ImplicitDependencyOptions.class).build();
    ParsedOptionDescription first =
        ParsedOptionDescription.newDummyInstance(
            FieldOptionDefinition.extractOptionDefinition(
                ImplicitDependencyOptions.class.getField("first")),
            createInvocationPolicyOrigin(),
            /* conversionContext= */ null);
    OptionInstanceOrigin origin =
        createInvocationPolicyOrigin(/*implicitDependent=*/ null, /*expandedFrom=*/ first);

    OptionDefinition optionDefinition =
        FieldOptionDefinition.extractOptionDefinition(
            ImplicitDependencyOptions.class.getField("second"));

    parser.setOptionValueAtSpecificPriorityWithoutExpansion(origin, optionDefinition, "hello");

    ImplicitDependencyOptions options = parser.getOptions(ImplicitDependencyOptions.class);
    assertThat(options.second).isEqualTo("hello");
    assertThat(options.third).isNull();
    assertThat(
            parser.asCompleteListOfParsedOptions().stream()
                .map(ParsedOptionDescription::getCommandLineForm))
        .containsExactly("--second=hello");
  }

  @Test
  public void negativeTargetPatternsInOptions_failsDistinctively() {
    OptionsParser parser = OptionsParser.builder().optionsClasses(ExampleFoo.class).build();
    OptionsParsingException e =
        assertThrows(OptionsParsingException.class, () -> parser.parse("//foo", "-//bar", "//baz"));
    assertThat(e).hasMessageThat().contains("-//bar");
    assertThat(e)
        .hasMessageThat()
        .contains("Negative target patterns can only appear after the end of options marker");
    assertThat(e)
        .hasMessageThat()
        .contains("Flags corresponding to Starlark-defined build settings always start with '--'");
  }

  @Test
  public void negativeExternalTargetPatternsInOptions_failsDistinctively() {
    OptionsParser parser = OptionsParser.builder().optionsClasses(ExampleFoo.class).build();
    OptionsParsingException e =
        assertThrows(
            OptionsParsingException.class, () -> parser.parse("//foo", "-@repo//bar", "//baz"));
    assertThat(e).hasMessageThat().contains("-@repo//bar");
    assertThat(e)
        .hasMessageThat()
        .contains("Negative target patterns can only appear after the end of options marker");
    assertThat(e)
        .hasMessageThat()
        .contains("Flags corresponding to Starlark-defined build settings always start with '--'");
  }

  @Test
  public void fallbackOptions_optionsParsingEquivalently() throws OptionsParsingException {
    OpaqueOptionsData fallbackData =
        OptionsParser.getFallbackOptionsData(
            ImmutableList.of(ExampleFoo.class, ExampleEquivalentWithFoo.class));
    OptionsParser parser = OptionsParser.builder().optionsClasses(ExampleFoo.class).build();
    parser.parseWithSourceFunction(
        PriorityCategory.RC_FILE,
        o -> ".bazelrc",
        ImmutableList.of(
            "--ignored_with_value", "--foo", "--foo=bar", "--ignored_without_value", "--bar", "1"),
        fallbackData);

    assertThat(parser.getOptions(ExampleFoo.class)).isNotNull();
    assertThat(parser.getOptions(ExampleFoo.class).foo).isEqualTo("bar");
    assertThat(parser.getOptions(ExampleFoo.class).bar).isEqualTo(1);

    assertThat(parser.getOptions(ExampleEquivalentWithFoo.class)).isNull();
  }

  @Test
  public void fallbackOptions_optionsParsingDifferently() {
    Exception e =
        assertThrows(
            ConstructionException.class,
            () ->
                OptionsParser.getFallbackOptionsData(
                    ImmutableList.of(ExampleFoo.class, ExampleIncompatibleWithFoo.class)));
    assertThat(e).hasCauseThat().isInstanceOf(DuplicateOptionDeclarationException.class);
  }

  public static class ExpandingOptions extends OptionsBase {
    @Option(
        name = "foo",
        category = "one",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        expansion = {"--nobar"},
        defaultValue = "null")
    public Void foo;
  }

  public static class ExpandingOptionsFallback extends OptionsBase {
    @Option(
        name = "bar",
        category = "one",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "true")
    public boolean bar;
  }

  @Test
  public void fallbackOptions_expansionToNegativeBooleanFlag() throws OptionsParsingException {
    OpaqueOptionsData fallbackData =
        OptionsParser.getFallbackOptionsData(
            ImmutableList.of(ExpandingOptions.class, ExpandingOptionsFallback.class));
    OptionsParser parser = OptionsParser.builder().optionsClasses(ExpandingOptions.class).build();
    parser.parseWithSourceFunction(
        PriorityCategory.RC_FILE, o -> ".bazelrc", ImmutableList.of("--foo"), fallbackData);

    assertThat(parser.getOptions(ExpandingOptions.class)).isNotNull();
    assertThat(parser.getOptions(ExpandingOptionsFallback.class)).isNull();
  }

  @Test
  public void testOptionsParser_getUserOptions_excludesClientOptions() throws Exception {
    OptionsParser parser =
        OptionsParser.builder()
            .optionsClasses(ExpandingOptions.class, ExpandingOptionsFallback.class)
            .build();
    parser.parseWithSourceFunction(
        PriorityCategory.RC_FILE, o -> "client", ImmutableList.of("--foo"), null);
    assertThat(parser.getUserOptions()).isEmpty();

    parser.parseWithSourceFunction(
        PriorityCategory.RC_FILE, o -> ".bazelrc", ImmutableList.of("--foo"), null);

    assertThat(parser.getUserOptions().keySet()).containsExactly("--foo", "--nobar");
  }

  @Test
  public void testOptionsParser_explicitOptions_excludesFlagsetOptions() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(ExampleFoo.class).build();
    parser.parse(
        PriorityCategory.RC_FILE, "//test:PROJECT.scl", ImmutableList.of("--foo=set_by_flagset"));
    assertThat(parser.asListOfExplicitOptions()).isEmpty();
    assertThat(parser.canonicalize()).contains("--foo=set_by_flagset");
  }

  @Test
  public void testOptionsParser_getUserOptions_excludesInvocationPolicy() throws Exception {
    OptionsParser parser =
        OptionsParser.builder()
            .optionsClasses(ExpandingOptions.class, ExpandingOptionsFallback.class)
            .build();
    parser.parseWithSourceFunction(
        PriorityCategory.RC_FILE, o -> "Invocation policy", ImmutableList.of("--foo"), null);
    assertThat(parser.getUserOptions()).isEmpty();

    parser.parseWithSourceFunction(
        PriorityCategory.RC_FILE, o -> ".bazelrc", ImmutableList.of("--foo"), null);

    assertThat(parser.getUserOptions().keySet()).containsExactly("--foo", "--nobar");
  }

  private static OptionInstanceOrigin createInvocationPolicyOrigin() {
    return createInvocationPolicyOrigin(/*implicitDependent=*/ null, /*expandedFrom=*/ null);
  }

  private static OptionInstanceOrigin createInvocationPolicyOrigin(
      ParsedOptionDescription implicitDependent, ParsedOptionDescription expandedFrom) {
    return new OptionInstanceOrigin(
        OptionPriority.lowestOptionPriorityAtCategory(PriorityCategory.INVOCATION_POLICY),
        "invocation policy",
        implicitDependent,
        expandedFrom);
  }
}
