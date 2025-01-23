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
package com.google.devtools.build.lib.util;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.util.OptionsUtils.OptionSensitivity;
import com.google.devtools.build.lib.util.OptionsUtils.PathFragmentConverter;
import com.google.devtools.build.lib.util.OptionsUtils.PathFragmentListConverter;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionPriority.PriorityCategory;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test for {@link OptionsUtils}. */
@RunWith(TestParameterInjector.class)
public class OptionsUtilsTest {

  public static class IntrospectionExample extends OptionsBase {
    @Option(
        name = "alpha",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "alpha")
    public String alpha;

    @Option(
        name = "beta",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "beta")
    public String beta;

    @Option(
        name = "gamma",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "gamma")
    public String gamma;

    @Option(
        name = "delta",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "delta")
    public String delta;

    @Option(
        name = "echo",
        metadataTags = {OptionMetadataTag.HIDDEN},
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "echo")
    public String echo;
  }

  @Test
  public void asStringOfExplicitOptions() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(IntrospectionExample.class).build();
    parser.parse("--alpha=no", "--gamma=no", "--echo=no");
    assertThat(OptionsUtils.asShellEscapedString(parser)).isEqualTo("--alpha=no --gamma=no");
    assertThat(OptionsUtils.asArgumentList(parser))
        .containsExactly("--alpha=no", "--gamma=no")
        .inOrder();
  }

  @Test
  public void asStringOfExplicitOptionsCorrectSortingByPriority() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(IntrospectionExample.class).build();
    parser.parse(PriorityCategory.COMMAND_LINE, null, Arrays.asList("--alpha=no"));
    parser.parse(PriorityCategory.COMPUTED_DEFAULT, null, Arrays.asList("--beta=no"));
    assertThat(OptionsUtils.asShellEscapedString(parser)).isEqualTo("--beta=no --alpha=no");
    assertThat(OptionsUtils.asArgumentList(parser))
        .containsExactly("--beta=no", "--alpha=no")
        .inOrder();
  }

  public static class BooleanOpts extends OptionsBase {
    @Option(
        name = "b_one",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "true")
    public boolean bOne;

    @Option(
        name = "b_two",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "false")
    public boolean bTwo;
  }

  @Test
  public void asStringOfExplicitOptionsWithBooleans() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(BooleanOpts.class).build();
    parser.parse(PriorityCategory.COMMAND_LINE, null, Arrays.asList("--b_one", "--nob_two"));
    assertThat(OptionsUtils.asShellEscapedString(parser)).isEqualTo("--b_one --nob_two");
    assertThat(OptionsUtils.asArgumentList(parser))
        .containsExactly("--b_one", "--nob_two")
        .inOrder();

    parser = OptionsParser.builder().optionsClasses(BooleanOpts.class).build();
    parser.parse(PriorityCategory.COMMAND_LINE, null, Arrays.asList("--b_one=true", "--b_two=0"));
    assertThat(parser.getOptions(BooleanOpts.class).bOne).isTrue();
    assertThat(parser.getOptions(BooleanOpts.class).bTwo).isFalse();
    assertThat(OptionsUtils.asShellEscapedString(parser)).isEqualTo("--b_one --nob_two");
    assertThat(OptionsUtils.asArgumentList(parser))
        .containsExactly("--b_one", "--nob_two")
        .inOrder();
  }

  @Test
  public void asStringOfExplicitOptionsMultipleOptionsAreMultipleTimes() throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(IntrospectionExample.class).build();
    parser.parse(PriorityCategory.COMMAND_LINE, null, Arrays.asList("--alpha=one"));
    parser.parse(PriorityCategory.COMMAND_LINE, null, Arrays.asList("--alpha=two"));
    assertThat(OptionsUtils.asShellEscapedString(parser)).isEqualTo("--alpha=one --alpha=two");
    assertThat(OptionsUtils.asArgumentList(parser))
        .containsExactly("--alpha=one", "--alpha=two")
        .inOrder();
  }

  private PathFragment fragment(String string) {
    return PathFragment.create(string);
  }

  private List<PathFragment> convert(String input) throws Exception {
    return new PathFragmentListConverter().convert(input);
  }

  private PathFragment convertOne(String input) throws Exception {
    return new PathFragmentConverter().convert(input);
  }

  @Test
  public void emptyStringYieldsEmptyList() throws Exception {
    assertThat(convert("")).isEmpty();
  }

  @Test
  public void lonelyDotYieldsLonelyDot() throws Exception {
    assertThat(convert(".")).containsExactly(fragment("."));
  }

  @Test
  public void converterSkipsEmptyStrings() throws Exception {
    assertThat(convert("foo::bar:")).containsExactly(fragment("foo"), fragment("bar")).inOrder();
  }

  @Test
  public void multiplePaths() throws Exception {
    assertThat(convert("~/foo:foo:/bar/baz:.:/tmp/bang"))
        .containsExactly(
            fragment(System.getProperty("user.home") + "/foo"),
            fragment("foo"),
            fragment("/bar/baz"),
            fragment("."),
            fragment("/tmp/bang"))
        .inOrder();
  }

  @Test
  public void singlePath() throws Exception {
    assertThat(convertOne("foo")).isEqualTo(fragment("foo"));
    assertThat(convertOne("foo/bar/baz")).isEqualTo(fragment("foo/bar/baz"));
    assertThat(convertOne("~/foo")).isEqualTo(fragment(System.getProperty("user.home") + "/foo"));
  }

  @Test
  public void emptyPathFragmentToNull() throws Exception {
    assertThat(new OptionsUtils.EmptyToNullRelativePathFragmentConverter().convert("")).isNull();
  }

  @Test
  public void absolutePathFragmentThrows() throws Exception {
    OptionsParsingException exception =
        assertThrows(
            OptionsParsingException.class,
            () -> new OptionsUtils.EmptyToNullRelativePathFragmentConverter().convert("/abs"));

    assertThat(exception).hasMessageThat().contains("/abs");
  }

  @Test
  public void relativePathFragment() throws Exception {
    assertThat(new OptionsUtils.EmptyToNullRelativePathFragmentConverter().convert("path/to/me"))
        .isEqualTo(PathFragment.create("path/to/me"));
  }

  @Test
  public void absolutePathFragmentConverter_convertsAbsolutePath(
      @TestParameter({"/", "/dir/file"}) String path) throws Exception {
    OptionsUtils.AbsolutePathFragmentConverter converter =
        new OptionsUtils.AbsolutePathFragmentConverter();
    assertThat(converter.convert(path)).isEqualTo(PathFragment.create(path));
  }

  @Test
  public void absolutePathFragmentConverter_failsForRelativePath() {
    OptionsUtils.AbsolutePathFragmentConverter converter =
        new OptionsUtils.AbsolutePathFragmentConverter();

    OptionsParsingException e =
        assertThrows(OptionsParsingException.class, () -> converter.convert("relative/path"));

    assertThat(e).hasMessageThat().isEqualTo("Not an absolute path: 'relative/path'");
  }

  @Test
  public void getOptionSensitivity_None() {
    assertThat(OptionsUtils.getOptionSensitivity("x")).isEqualTo(OptionSensitivity.NONE);
    assertThat(OptionsUtils.getOptionSensitivity("x_arg_ok")).isEqualTo(OptionSensitivity.NONE);
    assertThat(OptionsUtils.getOptionSensitivity("x_env_ok")).isEqualTo(OptionSensitivity.NONE);
    assertThat(OptionsUtils.getOptionSensitivity("x_header_ok")).isEqualTo(OptionSensitivity.NONE);
  }

  @Test
  public void getOptionSensitivity_Partial() {
    assertThat(OptionsUtils.getOptionSensitivity("x_env")).isEqualTo(OptionSensitivity.PARTIAL);
    assertThat(OptionsUtils.getOptionSensitivity("x_env=a")).isEqualTo(OptionSensitivity.PARTIAL);
    assertThat(OptionsUtils.getOptionSensitivity("x_env a")).isEqualTo(OptionSensitivity.PARTIAL);

    assertThat(OptionsUtils.getOptionSensitivity("x_header")).isEqualTo(OptionSensitivity.PARTIAL);
    assertThat(
        OptionsUtils.getOptionSensitivity("x_header=a")).isEqualTo(OptionSensitivity.PARTIAL);
    assertThat(
        OptionsUtils.getOptionSensitivity("x_header a")).isEqualTo(OptionSensitivity.PARTIAL);
  }

  @Test
  public void getOptionSensitivity_Full() {
    assertThat(OptionsUtils.getOptionSensitivity("x_arg")).isEqualTo(OptionSensitivity.FULL);
    assertThat(OptionsUtils.getOptionSensitivity("x_arg=a")).isEqualTo(OptionSensitivity.FULL);
    assertThat(OptionsUtils.getOptionSensitivity("x_arg a")).isEqualTo(OptionSensitivity.FULL);
  }

  @Test
  public void maybeScrubAssignment_None() {
    assertThat(OptionsUtils.maybeScrubAssignment(OptionSensitivity.NONE, "A=B")).isEqualTo("A=B");
  }

  @Test
  public void maybeScrubAssignment_Partial_OnlyName() {
    assertThat(OptionsUtils.maybeScrubAssignment(OptionSensitivity.PARTIAL, "AB")).isEqualTo("AB");
  }

  @Test
  public void maybeScrubAssignment_Partial_NameAndValue() {
    assertThat(
        OptionsUtils.maybeScrubAssignment(OptionSensitivity.PARTIAL, "A=B"))
        .isEqualTo("A= ");
    assertThat(
        OptionsUtils.maybeScrubAssignment(OptionSensitivity.PARTIAL, "A=B=C"))
        .isEqualTo("A= ");
  }

  @Test
  public void maybeScrubAssignment_Full() {
    assertThat(OptionsUtils.maybeScrubAssignment(OptionSensitivity.FULL, "")).isEqualTo("REDACTED");
    assertThat(
        OptionsUtils.maybeScrubAssignment(OptionSensitivity.FULL, "x")).isEqualTo("REDACTED");
  }

  @Test
  public void maybeScrubCombinedForm_None() {
    assertThat(OptionsUtils.maybeScrubCombinedForm(OptionSensitivity.NONE, "")).isEqualTo("");
    assertThat(
        OptionsUtils.maybeScrubCombinedForm(OptionSensitivity.NONE, "--a_env x"))
        .isEqualTo("--a_env x");
    assertThat(
        OptionsUtils.maybeScrubCombinedForm(OptionSensitivity.NONE, "--a_env=x"))
        .isEqualTo("--a_env=x");
  }

  @Test
  public void maybeScrubCombinedForm_Partial() {
    assertThat(
        OptionsUtils.maybeScrubCombinedForm(OptionSensitivity.PARTIAL, ""))
        .isEqualTo("INVALID-OPTION-VALUE");
    assertThat(
        OptionsUtils.maybeScrubCombinedForm(OptionSensitivity.PARTIAL, "--a"))
        .isEqualTo("INVALID-OPTION-VALUE");
    assertThat(
        OptionsUtils.maybeScrubCombinedForm(OptionSensitivity.PARTIAL, "--a_env x"))
        .isEqualTo("--a_env x");
    assertThat(
        OptionsUtils.maybeScrubCombinedForm(OptionSensitivity.PARTIAL, "--a_env x=y"))
        .isEqualTo("--a_env x= ");
    assertThat(
        OptionsUtils.maybeScrubCombinedForm(OptionSensitivity.PARTIAL, "--a_env=x"))
        .isEqualTo("--a_env=x");
    assertThat(
        OptionsUtils.maybeScrubCombinedForm(OptionSensitivity.PARTIAL, "--a_env=x=y"))
        .isEqualTo("--a_env=x= ");
  }

  @Test
  public void maybeScrubCombinedForm_Full() {
    assertThat(
        OptionsUtils.maybeScrubCombinedForm(OptionSensitivity.FULL, ""))
        .isEqualTo("INVALID-OPTION-VALUE");
    assertThat(
        OptionsUtils.maybeScrubCombinedForm(OptionSensitivity.FULL, "--a"))
        .isEqualTo("INVALID-OPTION-VALUE");
    assertThat(
        OptionsUtils.maybeScrubCombinedForm(OptionSensitivity.FULL, "--a_env x"))
        .isEqualTo("--a_env REDACTED");
    assertThat(
        OptionsUtils.maybeScrubCombinedForm(OptionSensitivity.FULL, "--a_env x=y"))
        .isEqualTo("--a_env REDACTED");
    assertThat(
        OptionsUtils.maybeScrubCombinedForm(OptionSensitivity.FULL, "--a_env=x"))
        .isEqualTo("--a_env=REDACTED");
    assertThat(
        OptionsUtils.maybeScrubCombinedForm(OptionSensitivity.FULL, "--a_env=x=y"))
        .isEqualTo("--a_env=REDACTED");
  }

  @Test
  public void scrubArgs_None() {
    ImmutableList<String> args = ImmutableList.of();
    ImmutableList<String> expArgs = ImmutableList.of();
    assertThat(OptionsUtils.scrubArgs(args)).isEqualTo(expArgs);
  }

  @Test
  public void scrubArgs_Partial_SameArg() {
    ImmutableList<String> args =
        ImmutableList.of("foo", "--test_env=UNKNOWN=1234", "--test_env=HOME=dir");
    ImmutableList<String> expArgs =
        ImmutableList.of("foo", "--test_env=UNKNOWN= ", "--test_env=HOME=dir");
    assertThat(OptionsUtils.scrubArgs(args)).isEqualTo(expArgs);
  }

  @Test
  public void scrubArgs_Partial_SeparateArg() {
    ImmutableList<String> args =
        ImmutableList.of("foo", "--test_env", "UNKNOWN=1234", "--test_env=HOME=dir");
    ImmutableList<String> expArgs =
        ImmutableList.of("foo", "--test_env", "UNKNOWN= ", "--test_env=HOME=dir");
    assertThat(OptionsUtils.scrubArgs(args)).isEqualTo(expArgs);
  }

  @Test
  public void scrubArgs_Full_SameArg() {
    ImmutableList<String> args =
        ImmutableList.of("foo", "--test_arg=UNKNOWN=1234", "--test_arg=HOME=dir");
    ImmutableList<String> expArgs =
        ImmutableList.of("foo", "--test_arg=REDACTED", "--test_arg=REDACTED");
    assertThat(OptionsUtils.scrubArgs(args)).isEqualTo(expArgs);
  }

  @Test
  public void scrubArgs_Full_SeparateArg() {
    ImmutableList<String> args =
        ImmutableList.of("foo", "--test_arg", "UNKNOWN=1234", "--test_arg=HOME=dir");
    ImmutableList<String> expArgs =
        ImmutableList.of("foo", "--test_arg", "REDACTED", "--test_arg=REDACTED");
    assertThat(OptionsUtils.scrubArgs(args)).isEqualTo(expArgs);
  }

  @Test
  public void scrubArgs_Residue_NotSensitive() {
    ImmutableList<String> args =
        ImmutableList.of("test", "--", "abc", "--def");
    ImmutableList<String> expArgs =
        ImmutableList.of("test", "--", "REDACTED", "REDACTED");
    assertThat(OptionsUtils.scrubArgs(args)).isEqualTo(expArgs);
  }

  @Test
  public void scrubArgs_Residue_Sensitive() {
    ImmutableList<String> args =
        ImmutableList.of("run", "--", "abc", "--def");
    ImmutableList<String> expArgs =
        ImmutableList.of("run", "--", "REDACTED", "REDACTED");
    assertThat(OptionsUtils.scrubArgs(args)).isEqualTo(expArgs);
  }
}
