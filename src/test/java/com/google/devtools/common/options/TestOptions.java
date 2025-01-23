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

import com.google.devtools.common.options.InvocationPolicyEnforcerTestBase.ToListConverter;
import java.util.List;

/** Options for testing. */
public class TestOptions extends OptionsBase {

  /*
   * Basic types
   */

  public static final String TEST_STRING_DEFAULT = "test string default";

  @Option(
    name = "test_string",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.NO_OP},
    defaultValue = TEST_STRING_DEFAULT,
    help = "a string-valued option to test simple option operations"
  )
  public String testString;

  @Option(
    name = "test_string_null_by_default",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.NO_OP},
    defaultValue = "null",
    help = "a string-valued option that has the special string 'null' as its default."
  )
  public String testStringNullByDefault;

  /*
   * Repeated flags
   */

  @Option(
      name = "test_multiple_string",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null",
      allowMultiple = true,
      help = "a repeatable string-valued flag with its own unhelpful help text")
  public List<String> testMultipleString;

  /*
   * Flags with converters that return lists
   */

  @Option(
      name = "test_list_converters",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null",
      allowMultiple = true,
      converter = ToListConverter.class,
      help =
          "a repeatable flag that accepts lists, but doesn't want to have lists of lists "
              + "as a final type")
  public List<String> testListConverters;

  /*
   * Expansion flags
   */

  public static final boolean EXPANDED_A_TEST_EXPANSION = false;
  public static final boolean EXPANDED_B_TEST_EXPANSION = false;
  public static final int EXPANDED_C_TEST_EXPANSION = 42;
  public static final String EXPANDED_D_TEST_EXPANSION = "bar";

  @Option(
    name = "test_expansion",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.NO_OP},
    defaultValue = "null",
    expansion = {
      "--noexpanded_a",
      "--expanded_b=false",
      "--expanded_c",
      "42",
      "--expanded_d",
      "bar"
    },
    help = "this expands to an alphabet soup."
  )
  public Void testExpansion;

  public static final boolean EXPANDED_A_TEST_RECURSIVE_EXPANSION = false;
  public static final boolean EXPANDED_B_TEST_RECURSIVE_EXPANSION = false;
  public static final int EXPANDED_C_TEST_RECURSIVE_EXPANSION = 56;
  public static final String EXPANDED_D_TEST_RECURSIVE_EXPANSION = "baz";

  @Option(
    name = "test_recursive_expansion_top_level",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.NO_OP},
    defaultValue = "null",
    expansion = {
      "--test_recursive_expansion_middle1",
      "--test_recursive_expansion_middle2",
    },
    help = "Lets the children do all the work."
  )
  public Void testRecursiveExpansionTopLevel;

  @Option(
    name = "test_recursive_expansion_middle1",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.NO_OP},
    defaultValue = "null",
    expansion = {
      "--expanded_a=false",
      "--expanded_c=56",
    }
  )
  public Void testRecursiveExpansionMiddle1;

  @Option(
    name = "test_recursive_expansion_middle2",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.NO_OP},
    defaultValue = "null",
    expansion = {
      "--expanded_b=false",
      "--expanded_d=baz",
    }
  )
  public Void testRecursiveExpansionMiddle2;

  public static final boolean EXPANDED_A_DEFAULT = true;

  @Option(
    name = "expanded_a",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.UNKNOWN},
    defaultValue = "true",
    help = "A boolean flag with unknown effect to test tagless usage text."
  )
  public boolean expandedA;

  public static final boolean EXPANDED_B_DEFAULT = true;

  @Option(
    name = "expanded_b",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.NO_OP},
    defaultValue = "true"
  )
  public boolean expandedB;

  public static final int EXPANDED_C_DEFAULT = 12;

  @Option(
    name = "expanded_c",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.NO_OP},
    defaultValue = "12",
    help = "an int-value'd flag used to test expansion logic"
  )
  public int expandedC;

  public static final String EXPANDED_D_DEFAULT = "foo";

  @Option(
    name = "expanded_d",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.NO_OP},
    defaultValue = "foo"
  )
  public String expandedD;

  /*
   * Expansion into repeatable flags.
   */

  public static final String EXPANDED_MULTIPLE_1 = "expandedFirstValue";
  public static final String EXPANDED_MULTIPLE_2 = "expandedSecondValue";

  @Option(
    name = "test_expansion_to_repeatable",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.NO_OP},
    defaultValue = "null",
    expansion = {
      "--test_multiple_string=expandedFirstValue",
      "--test_multiple_string=expandedSecondValue"
    },
    help = "Go forth and multiply, they said."
  )
  public Void testExpansionToRepeatable;

  /*
   * Implicit requirement flags
   */

  public static final String TEST_IMPLICIT_REQUIREMENT_DEFAULT = "direct implicit";
  public static final String IMPLICIT_REQUIREMENT_A_REQUIRED = "implicit requirement, required";

  @Option(
    name = "test_implicit_requirement",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.NO_OP},
    defaultValue = TEST_IMPLICIT_REQUIREMENT_DEFAULT,
    implicitRequirements = {"--implicit_requirement_a=" + IMPLICIT_REQUIREMENT_A_REQUIRED},
    help = "this option really needs that other one, isolation of purpose has failed."
  )
  public String testImplicitRequirement;

  public static final String IMPLICIT_REQUIREMENT_A_DEFAULT = "implicit requirement, unrequired";

  @Option(
    name = "implicit_requirement_a",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.NO_OP},
    defaultValue = IMPLICIT_REQUIREMENT_A_DEFAULT
  )
  public String implicitRequirementA;

  public static final String TEST_RECURSIVE_IMPLICIT_REQUIREMENT_DEFAULT = "recursive implicit";
  public static final String TEST_IMPLICIT_REQUIREMENT_REQUIRED = "intermediate, required";

  @Option(
    name = "test_recursive_implicit_requirement",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.NO_OP},
    defaultValue = TEST_RECURSIVE_IMPLICIT_REQUIREMENT_DEFAULT,
    implicitRequirements = {"--test_implicit_requirement=" + TEST_IMPLICIT_REQUIREMENT_REQUIRED}
  )
  public String testRecursiveImplicitRequirement;

  @Option(
      name = "test_deprecated",
      defaultValue = "default",
      deprecationWarning = "Flag for testing deprecation behavior.",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP})
  public String testDeprecated;

  /*
   * Flags with potential secrets.
   */

  @Option(
      name = "stub_env",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null",
      allowMultiple = true,
      help = "a repeatable string-valued flag with its own unhelpful help text")
  public List<String> testEnvString;

  @Option(
      name = "stub_inherit_env",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.NO_OP},
      defaultValue = "null",
      allowMultiple = true,
      help = "a repeatable string-valued flag with its own unhelpful help text")
  public List<String> testInheritEnvString;
}
