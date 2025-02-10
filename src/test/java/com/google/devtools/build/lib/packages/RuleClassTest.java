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
package com.google.devtools.build.lib.packages;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.devtools.build.lib.analysis.testing.ExecGroupSubject.assertThat;
import static com.google.devtools.build.lib.analysis.testing.RuleClassSubject.assertThat;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static com.google.devtools.build.lib.packages.BuildType.NODEP_LABEL;
import static com.google.devtools.build.lib.packages.BuildType.OUTPUT_LIST;
import static com.google.devtools.build.lib.packages.ImplicitOutputsFunction.substitutePlaceholderIntoTemplate;
import static com.google.devtools.build.lib.packages.RuleClass.Builder.STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME;
import static com.google.devtools.build.lib.packages.Type.BOOLEAN;
import static com.google.devtools.build.lib.packages.Type.INTEGER;
import static com.google.devtools.build.lib.packages.Type.STRING;
import static com.google.devtools.build.lib.packages.Types.STRING_LIST;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventCollector;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.packages.Attribute.StarlarkComputedDefaultTemplate.CannotPrecomputeDefaultsException;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.SafeImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.RuleClass.AutoExecGroupsMode;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory;
import com.google.devtools.build.lib.packages.RuleClass.ToolchainResolutionMode;
import com.google.devtools.build.lib.packages.RuleFactory.BuildLangTypedAttributeValuesMap;
import com.google.devtools.build.lib.packages.util.PackageLoadingTestCase;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.Location;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RuleClass}. */
@RunWith(JUnit4.class)
public final class RuleClassTest extends PackageLoadingTestCase {
  private static final RuleClass.ConfiguredTargetFactory<Object, Object, Exception>
      DUMMY_CONFIGURED_TARGET_FACTORY =
          ruleContext -> {
            throw new IllegalStateException();
          };

  private static final ImmutableList<StarlarkThread.CallStackEntry> DUMMY_STACK =
      ImmutableList.of(
          StarlarkThread.callStackEntry(
              StarlarkThread.TOP_LEVEL, Location.fromFileLineColumn("BUILD", 10, 1)),
          StarlarkThread.callStackEntry("bar", Location.fromFileLineColumn("bar.bzl", 42, 1)),
          StarlarkThread.callStackEntry("rule", Location.BUILTIN));

  private static final class DummyFragment extends Fragment {}

  private static RuleClass createRuleClassA() throws LabelSyntaxException {
    return newRuleClass(
        "ruleA",
        false,
        false,
        false,
        false,
        false,
        false,
        SafeImplicitOutputsFunction.NONE,
        null,
        DUMMY_CONFIGURED_TARGET_FACTORY,
        AdvertisedProviderSet.EMPTY,
        null,
        ImmutableSet.of(),
        true,
        attr("my-string-attr", STRING).mandatory().build(),
        attr("my-label-attr", LABEL)
            .mandatory()
            .legacyAllowAnyFileType()
            .value(Label.parseCanonical("//default:label"))
            .build(),
        attr("my-labellist-attr", LABEL_LIST).mandatory().legacyAllowAnyFileType().build(),
        attr("my-integer-attr", INTEGER).value(StarlarkInt.of(42)).build(),
        attr("my-string-attr2", STRING).mandatory().value((String) null).build(),
        attr("my-stringlist-attr", STRING_LIST).build(),
        attr("my-sorted-stringlist-attr", STRING_LIST).orderIndependent().build());
  }

  private static RuleClass createRuleClassB(RuleClass ruleClassA) {
    // emulates attribute inheritance
    List<Attribute> attributes = new ArrayList<>(ruleClassA.getAttributes());
    attributes.add(attr("another-string-attr", STRING).mandatory().build());
    return newRuleClass(
        "ruleB",
        false,
        false,
        false,
        false,
        false,
        false,
        SafeImplicitOutputsFunction.NONE,
        null,
        DUMMY_CONFIGURED_TARGET_FACTORY,
        AdvertisedProviderSet.EMPTY,
        null,
        ImmutableSet.of(),
        true,
        attributes.toArray(new Attribute[0]));
  }

  // Helper method to paper over how some test cases don't supply the mandatory name attribute for
  // historic reasons.
  private static ImmutableMap<String, Object> ensureNameAttrValuePresent(
      Map<String, Object> attrValues) {
    if (attrValues.containsKey("name")) {
      return ImmutableMap.copyOf(attrValues);
    } else {
      return ImmutableMap.<String, Object>builder()
          .putAll(attrValues)
          .put("name", "my-name")
          .buildOrThrow();
    }
  }

  @Test
  public void testRuleClassBasics() throws Exception {
    RuleClass ruleClassA = createRuleClassA();

    assertThat(ruleClassA.getName()).isEqualTo("ruleA");
    assertThat(ruleClassA.getAttributeCount()).isEqualTo(8);

    assertThat(ruleClassA.getAttributeIndex("name")).isEqualTo(0);
    assertThat(ruleClassA.getAttributeIndex("my-string-attr")).isEqualTo(1);
    assertThat(ruleClassA.getAttributeIndex("my-label-attr")).isEqualTo(2);
    assertThat(ruleClassA.getAttributeIndex("my-labellist-attr")).isEqualTo(3);
    assertThat(ruleClassA.getAttributeIndex("my-integer-attr")).isEqualTo(4);
    assertThat(ruleClassA.getAttributeIndex("my-string-attr2")).isEqualTo(5);
    assertThat(ruleClassA.getAttributeIndex("my-stringlist-attr")).isEqualTo(6);
    assertThat(ruleClassA.getAttributeIndex("my-sorted-stringlist-attr")).isEqualTo(7);

    assertThat(ruleClassA.getAttributeByName("name")).isEqualTo(ruleClassA.getAttribute(0));
    assertThat(ruleClassA.getAttributeByName("my-string-attr"))
        .isEqualTo(ruleClassA.getAttribute(1));
    assertThat(ruleClassA.getAttributeByName("my-label-attr"))
        .isEqualTo(ruleClassA.getAttribute(2));
    assertThat(ruleClassA.getAttributeByName("my-labellist-attr"))
        .isEqualTo(ruleClassA.getAttribute(3));
    assertThat(ruleClassA.getAttributeByName("my-integer-attr"))
        .isEqualTo(ruleClassA.getAttribute(4));
    assertThat(ruleClassA.getAttributeByName("my-string-attr2"))
        .isEqualTo(ruleClassA.getAttribute(5));
    assertThat(ruleClassA.getAttributeByName("my-stringlist-attr"))
        .isEqualTo(ruleClassA.getAttribute(6));
    assertThat(ruleClassA.getAttributeByName("my-sorted-stringlist-attr"))
        .isEqualTo(ruleClassA.getAttribute(7));

    // default based on type
    assertThat(ruleClassA.getAttribute(0).getDefaultValue(null)).isEqualTo("");
    assertThat(ruleClassA.getAttribute(1).getDefaultValue(null)).isEqualTo("");
    assertThat(ruleClassA.getAttribute(2).getDefaultValue(null))
        .isEqualTo(Label.parseCanonical("//default:label"));
    assertThat(ruleClassA.getAttribute(3).getDefaultValue(null)).isEqualTo(ImmutableList.of());
    assertThat(ruleClassA.getAttribute(4).getDefaultValue(null)).isEqualTo(StarlarkInt.of(42));
    // default explicitly specified
    assertThat(ruleClassA.getAttribute(5).getDefaultValue(null)).isNull();
    assertThat(ruleClassA.getAttribute(6).getDefaultValue(null)).isEqualTo(ImmutableList.of());
    assertThat(ruleClassA.getAttribute(7).getDefaultValue(null)).isEqualTo(ImmutableList.of());
  }

  @Test
  public void testRuleClassInheritance() throws Exception {
    RuleClass ruleClassA = createRuleClassA();
    RuleClass ruleClassB = createRuleClassB(ruleClassA);

    assertThat(ruleClassB.getName()).isEqualTo("ruleB");
    assertThat(ruleClassB.getAttributeCount()).isEqualTo(9);

    assertThat(ruleClassB.getAttributeIndex("name")).isEqualTo(0);
    assertThat(ruleClassB.getAttributeIndex("my-string-attr")).isEqualTo(1);
    assertThat(ruleClassB.getAttributeIndex("my-label-attr")).isEqualTo(2);
    assertThat(ruleClassB.getAttributeIndex("my-labellist-attr")).isEqualTo(3);
    assertThat(ruleClassB.getAttributeIndex("my-integer-attr")).isEqualTo(4);
    assertThat(ruleClassB.getAttributeIndex("my-string-attr2")).isEqualTo(5);
    assertThat(ruleClassB.getAttributeIndex("my-stringlist-attr")).isEqualTo(6);
    assertThat(ruleClassB.getAttributeIndex("my-sorted-stringlist-attr")).isEqualTo(7);
    assertThat(ruleClassB.getAttributeIndex("another-string-attr")).isEqualTo(8);

    assertThat(ruleClassB.getAttributeByName("name")).isEqualTo(ruleClassB.getAttribute(0));
    assertThat(ruleClassB.getAttributeByName("my-string-attr"))
        .isEqualTo(ruleClassB.getAttribute(1));
    assertThat(ruleClassB.getAttributeByName("my-label-attr"))
        .isEqualTo(ruleClassB.getAttribute(2));
    assertThat(ruleClassB.getAttributeByName("my-labellist-attr"))
        .isEqualTo(ruleClassB.getAttribute(3));
    assertThat(ruleClassB.getAttributeByName("my-integer-attr"))
        .isEqualTo(ruleClassB.getAttribute(4));
    assertThat(ruleClassB.getAttributeByName("my-string-attr2"))
        .isEqualTo(ruleClassB.getAttribute(5));
    assertThat(ruleClassB.getAttributeByName("my-stringlist-attr"))
        .isEqualTo(ruleClassB.getAttribute(6));
    assertThat(ruleClassB.getAttributeByName("my-sorted-stringlist-attr"))
        .isEqualTo(ruleClassB.getAttribute(7));
    assertThat(ruleClassB.getAttributeByName("another-string-attr"))
        .isEqualTo(ruleClassB.getAttribute(8));
  }

  private static final String TEST_PACKAGE_NAME = "testpackage";

  private static final String TEST_RULE_NAME = "my-rule-A";

  private static final int TEST_RULE_DEFINED_AT_LINE = 42;

  private static final String TEST_RULE_LABEL = "@//" + TEST_PACKAGE_NAME + ":" + TEST_RULE_NAME;

  private Path testBuildfilePath;
  private Location testRuleLocation;

  @Before
  public void setRuleLocation() {
    testBuildfilePath = root.getRelative("testpackage/BUILD");
    testRuleLocation =
        Location.fromFileLineColumn(testBuildfilePath.toString(), TEST_RULE_DEFINED_AT_LINE, 0);
  }

  private Package.Builder createDummyPackageBuilder() {
    return packageFactory.newPackageBuilder(
        PackageIdentifier.createInMainRepo(TEST_PACKAGE_NAME),
        RootedPath.toRootedPath(root, testBuildfilePath),
        "TESTING",
        Optional.empty(),
        Optional.empty(),
        StarlarkSemantics.DEFAULT,
        /* repositoryMapping= */ RepositoryMapping.ALWAYS_FALLBACK,
        /* mainRepositoryMapping= */ null,
        /* cpuBoundSemaphore= */ null,
        /* generatorMap= */ null,
        /* configSettingVisibilityPolicy= */ null,
        /* globber= */ null);
  }

  @Test
  public void testDuplicatedDeps() throws Exception {
    RuleClass depsRuleClass =
        newRuleClass(
            "ruleDeps",
            false,
            false,
            false,
            false,
            false,
            false,
            SafeImplicitOutputsFunction.NONE,
            null,
            DUMMY_CONFIGURED_TARGET_FACTORY,
            AdvertisedProviderSet.EMPTY,
            null,
            ImmutableSet.of(),
            true,
            attr("list1", LABEL_LIST).mandatory().legacyAllowAnyFileType().build(),
            attr("list2", LABEL_LIST).mandatory().legacyAllowAnyFileType().build(),
            attr("list3", LABEL_LIST).mandatory().legacyAllowAnyFileType().build());

    // LinkedHashMap -> predictable iteration order for testing
    Map<String, Object> attributeValues = new LinkedHashMap<>();
    attributeValues.put("list1", Lists.newArrayList("//testpackage:dup1", ":dup1", ":nodup"));
    attributeValues.put("list2", Lists.newArrayList(":nodup1", ":nodup2"));
    attributeValues.put("list3", Lists.newArrayList(":dup1", ":dup1", ":dup2", ":dup2"));

    reporter.removeHandler(failFastHandler);
    createRule(depsRuleClass, "depsRule", attributeValues);

    assertThat(eventCollector.count()).isSameInstanceAs(3);
    assertDupError("//testpackage:dup1", "list1", "depsRule");
    assertDupError("//testpackage:dup1", "list3", "depsRule");
    assertDupError("//testpackage:dup2", "list3", "depsRule");
  }

  private void assertDupError(String label, String attrName, String ruleName) {
    assertContainsEvent(String.format("Label '%s' is duplicated in the '%s' attribute of rule '%s'",
        label, attrName, ruleName));
  }

  @Test
  public void testDuplicatedDepsWithinSingleSelectConditionError() throws Exception {
    RuleClass depsRuleClass =
        newRuleClass(
            "ruleDeps",
            false,
            false,
            false,
            false,
            false,
            false,
            SafeImplicitOutputsFunction.NONE,
            null,
            DUMMY_CONFIGURED_TARGET_FACTORY,
            AdvertisedProviderSet.EMPTY,
            null,
            ImmutableSet.of(),
            true,
            attr("list1", LABEL_LIST).mandatory().legacyAllowAnyFileType().build());

    SelectorList selectorList1 =
        SelectorList.of(
            new SelectorValue(
                ImmutableMap.of("//conditions:a", ImmutableList.of(":dup1", ":dup1")), ""));

    // expect errors
    reporter.removeHandler(failFastHandler);

    Map<String, Object> attributeValues = new HashMap<>();
    attributeValues.put("list1", selectorList1);
    createRule(depsRuleClass, "depsRule", attributeValues);

    assertThat(eventCollector.count()).isSameInstanceAs(1);
    assertDupError("//testpackage:dup1", "list1", "depsRule");
  }

  @Test
  public void testDuplicatedDepsWithinConditionMultipleSelectsErrors() throws Exception {
    RuleClass depsRuleClass =
        newRuleClass(
            "ruleDeps",
            false,
            false,
            false,
            false,
            false,
            false,
            SafeImplicitOutputsFunction.NONE,
            null,
            DUMMY_CONFIGURED_TARGET_FACTORY,
            AdvertisedProviderSet.EMPTY,
            null,
            ImmutableSet.of(),
            true,
            attr("list1", LABEL_LIST).mandatory().legacyAllowAnyFileType().build());

    SelectorList selectorList1a =
        SelectorList.of(
            new SelectorValue(
                ImmutableMap.of(
                    "//conditions:a", ImmutableList.of(":dup1", "dup1"),
                    "//conditions:b", ImmutableList.of(":nodup1")),
                ""));
    SelectorList selectorList1b =
        SelectorList.of(
            new SelectorValue(
                ImmutableMap.of(
                    "//conditions:c", ImmutableList.of(":dup2", "dup2"),
                    "//conditions:d", ImmutableList.of(":nodup1")),
                ""));
    SelectorList selectorList1 = SelectorList.concat(selectorList1a, selectorList1b);

    // expect errors
    reporter.removeHandler(failFastHandler);

    Map<String, Object> attributeValues = new HashMap<>();
    attributeValues.put("list1", selectorList1);
    createRule(depsRuleClass, "depsRule", attributeValues);

    assertThat(eventCollector.count()).isSameInstanceAs(2);
    assertDupError("//testpackage:dup1", "list1", "depsRule");
    assertDupError("//testpackage:dup2", "list1", "depsRule");
  }

  @Test
  public void testSameDepAcrossMultipleSelectsNoDuplicateNoError() throws Exception {
    RuleClass depsRuleClass =
        newRuleClass(
            "ruleDeps",
            false,
            false,
            false,
            false,
            false,
            false,
            SafeImplicitOutputsFunction.NONE,
            null,
            DUMMY_CONFIGURED_TARGET_FACTORY,
            AdvertisedProviderSet.EMPTY,
            null,
            ImmutableSet.of(),
            true,
            attr("list1", LABEL_LIST).mandatory().legacyAllowAnyFileType().build());

    // ignore duplicatess across selects where values appear duplicated but are not
    SelectorList selectorList1a =
        SelectorList.of(
            new SelectorValue(
                ImmutableMap.of(
                    "//conditions:a", ImmutableList.of(":nodup1"),
                    "//conditions:b", ImmutableList.of(":nodup2")),
                ""));
    SelectorList selectorList1b =
        SelectorList.of(
            new SelectorValue(
                ImmutableMap.of(
                    "//conditions:a", ImmutableList.of(":nodup2"),
                    "//conditions:b", ImmutableList.of(":nodup1")),
                ""));
    SelectorList selectorList1 = SelectorList.concat(selectorList1a, selectorList1b);

    Map<String, Object> attributeValues = new HashMap<>();
    attributeValues.put("list1", selectorList1);
    createRule(depsRuleClass, "depsRule", attributeValues);
  }

  @Test
  public void testSameDepAcrossMultipleSelectsIsDuplicateNoError() throws Exception {
    RuleClass depsRuleClass =
        newRuleClass(
            "ruleDeps",
            false,
            false,
            false,
            false,
            false,
            false,
            SafeImplicitOutputsFunction.NONE,
            null,
            DUMMY_CONFIGURED_TARGET_FACTORY,
            AdvertisedProviderSet.EMPTY,
            null,
            ImmutableSet.of(),
            true,
            attr("list1", LABEL_LIST).mandatory().legacyAllowAnyFileType().build());

    // repetition of dup1 is identified at analysis time, not loading time
    SelectorList selectorList1a =
        SelectorList.of(
            new SelectorValue(
                ImmutableMap.of(
                    "//conditions:a", ImmutableList.of(":dup1"),
                    "//conditions:b", ImmutableList.of(":nodup1")),
                ""));
    SelectorList selectorList1b =
        SelectorList.of(
            new SelectorValue(
                ImmutableMap.of(
                    "//conditions:a", ImmutableList.of(":dup1"),
                    "//conditions:b", ImmutableList.of(":nodup2")),
                ""));
    SelectorList selectorList1 = SelectorList.concat(selectorList1a, selectorList1b);

    Map<String, Object> attributeValues = new HashMap<>();
    attributeValues.put("list1", selectorList1);
    createRule(depsRuleClass, "depsRule", attributeValues);
  }

  @Test
  public void testSameDepAcrossConditionsInSelectNoError() throws Exception {
    RuleClass depsRuleClass =
        newRuleClass(
            "ruleDeps",
            false,
            false,
            false,
            false,
            false,
            false,
            SafeImplicitOutputsFunction.NONE,
            null,
            DUMMY_CONFIGURED_TARGET_FACTORY,
            AdvertisedProviderSet.EMPTY,
            null,
            ImmutableSet.of(),
            true,
            attr("list1", LABEL_LIST).mandatory().legacyAllowAnyFileType().build());

    SelectorList selectorList1 =
        SelectorList.of(
            new SelectorValue(
                ImmutableMap.of(
                    "//conditions:a", ImmutableList.of(":nodup1"),
                    "//conditions:b", ImmutableList.of(":nodup1")),
                ""));

    Map<String, Object> attributeValues = new HashMap<>();
    attributeValues.put("list1", selectorList1);

    createRule(depsRuleClass, "depsRule", attributeValues);
  }

  @Test
  public void testCreateRule() throws Exception {
    RuleClass ruleClassA = createRuleClassA();

    // LinkedHashMap -> predictable iteration order for testing
    Map<String, Object> attributeValues = new LinkedHashMap<>();
    attributeValues.put("my-labellist-attr", "foobar"); // wrong type
    attributeValues.put("bogus-attr", "foobar"); // no such attr
    attributeValues.put("my-stringlist-attr", Arrays.asList("foo", "bar"));

    reporter.removeHandler(failFastHandler);
    EventCollector collector = new EventCollector(EventKind.ERRORS);
    reporter.addHandler(collector);

    Rule rule = createRule(ruleClassA, TEST_RULE_NAME, attributeValues);

    // TODO(blaze-team): (2009) refactor to use assertContainsEvent
    Iterator<String> expectedMessages =
        Arrays.asList(
                """
                expected value of type 'list(label)' for attribute 'my-labellist-attr' \
                of 'ruleA', but got \"foobar\" (string)""",
                "no such attribute 'bogus-attr' in 'ruleA' rule",
                "missing value for mandatory attribute 'my-string-attr' in 'ruleA' rule",
                "missing value for mandatory attribute 'my-label-attr' in 'ruleA' rule",
                "missing value for mandatory attribute 'my-labellist-attr' in 'ruleA' rule",
                "missing value for mandatory attribute 'my-string-attr2' in 'ruleA' rule")
            .iterator();

    for (Event event : collector) {
      assertThat(event.getLocation().line()).isEqualTo(TEST_RULE_DEFINED_AT_LINE);
      assertThat(event.getLocation().file()).isEqualTo(testBuildfilePath.toString());
      assertThat(event.getMessage())
          .isEqualTo(TEST_RULE_LABEL.substring(1) + ": " + expectedMessages.next());
    }

    // Test basic rule properties:
    assertThat(rule.getRuleClass()).isEqualTo("ruleA");
    assertThat(rule.getName()).isEqualTo(TEST_RULE_NAME);
    assertThat(rule.getLabel().toString()).isEqualTo(TEST_RULE_LABEL.substring(1));

    // Test attribute access:
    AttributeMap attributes = RawAttributeMapper.of(rule);
    assertThat(attributes.get("my-label-attr", BuildType.LABEL).toString())
        .isEqualTo("//default:label");
    assertThat(attributes.get("my-integer-attr", Type.INTEGER).toIntUnchecked()).isEqualTo(42);
    // missing attribute -> default chosen based on type
    assertThat(attributes.get("my-string-attr", Type.STRING)).isEmpty();
    assertThat(attributes.get("my-labellist-attr", BuildType.LABEL_LIST)).isEmpty();
    assertThat(attributes.get("my-stringlist-attr", Types.STRING_LIST))
        .isEqualTo(Arrays.asList("foo", "bar"));
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class, () -> attributes.get("my-labellist-attr", Type.STRING));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "Attribute my-labellist-attr is of type list(label) "
                + "and not of type string in ruleA rule //testpackage:my-rule-A");
  }

  @Test
  public void testImplicitOutputs() throws Exception {
    RuleClass ruleClassC =
        newRuleClass(
            "ruleC",
            false,
            false,
            false,
            false,
            false,
            false,
            ImplicitOutputsFunction.fromTemplates(
                "foo-%{name}.bar", "lib%{name}-wazoo-%{name}.mumble", "stuff-%{outs}-bar"),
            null,
            DUMMY_CONFIGURED_TARGET_FACTORY,
            AdvertisedProviderSet.EMPTY,
            null,
            ImmutableSet.of(),
            true,
            attr("outs", OUTPUT_LIST).build());

    Map<String, Object> attributeValues = new HashMap<>();
    attributeValues.put("outs", Collections.singletonList("explicit_out"));
    attributeValues.put("name", "myrule");

    Rule rule = createRule(ruleClassC, "myrule", attributeValues);

    Set<String> set = new HashSet<>();
    for (OutputFile outputFile : rule.getOutputFiles()) {
      set.add(outputFile.getName());
      assertThat(outputFile.getGeneratingRule()).isSameInstanceAs(rule);
    }
    assertThat(set).containsExactly("foo-myrule.bar", "libmyrule-wazoo-myrule.mumble",
        "stuff-explicit_out-bar", "explicit_out");
  }

  @Test
  public void testImplicitOutsWithBasenameDirname() throws Exception {
    RuleClass ruleClass =
        newRuleClass(
            "ruleClass",
            false,
            false,
            false,
            false,
            false,
            false,
            ImplicitOutputsFunction.fromTemplates("%{dirname}lib%{basename}.bar"),
            null,
            DUMMY_CONFIGURED_TARGET_FACTORY,
            AdvertisedProviderSet.EMPTY,
            null,
            ImmutableSet.of(),
            true);

    Rule rule = createRule(ruleClass, "myRule", ImmutableMap.of());
    assertThat(Iterables.getOnlyElement(rule.getOutputFiles()).getName())
        .isEqualTo("libmyRule.bar");

    Rule ruleWithSlash = createRule(ruleClass, "myRule/with/slash", ImmutableMap.of());
    assertThat(Iterables.getOnlyElement(ruleWithSlash.getOutputFiles()).getName())
        .isEqualTo("myRule/with/libslash.bar");
  }

  /**
   * Helper routine that instantiates a rule class with the given computed default and supporting
   * attributes for the default to reference.
   */
  private static RuleClass getRuleClassWithComputedDefault(Attribute computedDefault) {
    return newRuleClass(
        "ruleClass",
        false,
        false,
        false,
        false,
        false,
        false,
        ImplicitOutputsFunction.fromTemplates("empty"),
        null,
        DUMMY_CONFIGURED_TARGET_FACTORY,
        AdvertisedProviderSet.EMPTY,
        null,
        ImmutableSet.of(),
        true,
        attr("condition", BOOLEAN).value(false).build(),
        attr("declared1", BOOLEAN).value(false).build(),
        attr("declared2", BOOLEAN).value(false).build(),
        attr("nonconfigurable", BOOLEAN).nonconfigurable("test").value(false).build(),
        computedDefault);
  }

  /**
   * Helper routine that checks that a computed default is valid and bound to the expected value.
   */
  private void checkValidComputedDefault(Object expectedValue, Attribute computedDefault,
      ImmutableMap<String, Object> attrValueMap) throws Exception {
    assertThat(computedDefault.getDefaultValueUnchecked())
        .isInstanceOf(Attribute.ComputedDefault.class);
    Rule rule =
        createRule(getRuleClassWithComputedDefault(computedDefault), "myRule", attrValueMap);
    AttributeMap attributes = RawAttributeMapper.of(rule);
    assertThat(attributes.get(computedDefault.getName(), computedDefault.getType()))
        .isEqualTo(expectedValue);
  }

  /**
   * Helper routine that checks that a computed default is invalid due to declared dependency issues
   * and fails with the expected message.
   */
  private void checkInvalidComputedDefault(Attribute computedDefault, String expectedMessage) {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                createRule(
                    getRuleClassWithComputedDefault(computedDefault), "myRule", ImmutableMap.of()));
    assertThat(e).hasMessageThat().isEqualTo(expectedMessage);
  }

  /** Tests computed default values are computed as expected. */
  @Test
  public void testComputedDefault() throws Exception {
    Attribute computedDefault =
        attr("$result", BOOLEAN)
            .value(
                new Attribute.ComputedDefault("condition") {
                  @Override
                  public Object getDefault(AttributeMap rule) {
                    return rule.get("condition", Type.BOOLEAN);
                  }
                })
            .build();

    checkValidComputedDefault(
        Boolean.FALSE, computedDefault, ImmutableMap.of("condition", Boolean.FALSE));
    checkValidComputedDefault(
        Boolean.TRUE, computedDefault, ImmutableMap.of("condition", Boolean.TRUE));
  }

  /**
   * Tests that computed defaults can only read attribute values for configurable attributes that
   * have been explicitly declared.
   */
  @Test
  public void testComputedDefaultDeclarations() throws Exception {
    checkValidComputedDefault(
        Boolean.FALSE,
        attr("$good_default_no_declares", BOOLEAN)
            .value(
                new Attribute.ComputedDefault() {
                  @Override
                  public Object getDefault(AttributeMap rule) {
                    // OK: not a value check:
                    return rule.isAttributeValueExplicitlySpecified("undeclared");
                  }
                })
            .build(),
        ImmutableMap.of());

    checkValidComputedDefault(
        Boolean.FALSE,
        attr("$good_default_one_declare", BOOLEAN)
            .value(
                new Attribute.ComputedDefault("declared1") {
                  @Override
                  public Object getDefault(AttributeMap rule) {
                    return rule.get("declared1", Type.BOOLEAN);
                  }
                })
            .build(),
        ImmutableMap.of());

    checkValidComputedDefault(
        Boolean.FALSE,
        attr("$good_default_two_declares", BOOLEAN)
            .value(
                new Attribute.ComputedDefault("declared1", "declared2") {
                  @Override
                  public Object getDefault(AttributeMap rule) {
                    return rule.get("declared1", Type.BOOLEAN)
                        && rule.get("declared2", Type.BOOLEAN);
                  }
                })
            .build(),
        ImmutableMap.of());

    checkInvalidComputedDefault(
        attr("$bad_default_no_declares", BOOLEAN).value(
            new Attribute.ComputedDefault() {
              @Override public Object getDefault(AttributeMap rule) {
                return rule.get("declared1", Type.BOOLEAN);
              }
        }).build(),
        "attribute \"declared1\" isn't available in this computed default context");

    checkInvalidComputedDefault(
        attr("$bad_default_one_declare", BOOLEAN).value(
            new Attribute.ComputedDefault("declared1") {
              @Override public Object getDefault(AttributeMap rule) {
                return rule.get("declared1", Type.BOOLEAN) || rule.get("declared2", Type.BOOLEAN);
              }
        }).build(),
        "attribute \"declared2\" isn't available in this computed default context");

    checkInvalidComputedDefault(
        attr("$bad_default_two_declares", BOOLEAN).value(
            new Attribute.ComputedDefault("declared1", "declared2") {
              @Override public Object getDefault(AttributeMap rule) {
                return rule.get("condition", Type.BOOLEAN);
              }
        }).build(),
        "attribute \"condition\" isn't available in this computed default context");
  }

  /**
   * Tests that computed defaults *can* read attribute values for non-configurable attributes
   * without needing to explicitly declare them.
   */
  @Test
  public void testComputedDefaultWithNonConfigurableAttributes() throws Exception {
    checkValidComputedDefault(
        Boolean.FALSE,
        attr("$good_default_reading_undeclared_nonconfigurable_attribute", BOOLEAN)
            .value(
                new Attribute.ComputedDefault() {
                  @Override
                  public Object getDefault(AttributeMap rule) {
                    return rule.get("nonconfigurable", Type.BOOLEAN);
                  }
                })
            .build(),
        ImmutableMap.of());
  }

  @Test
  public void testOutputsAreOrdered() throws Exception {
    RuleClass ruleClassC =
        newRuleClass(
            "ruleC",
            false,
            false,
            false,
            false,
            false,
            false,
            ImplicitOutputsFunction.fromTemplates("first-%{name}", "second-%{name}", "out-%{outs}"),
            null,
            DUMMY_CONFIGURED_TARGET_FACTORY,
            AdvertisedProviderSet.EMPTY,
            null,
            ImmutableSet.of(),
            true,
            attr("outs", OUTPUT_LIST).build());

    Map<String, Object> attributeValues = new HashMap<>();
    attributeValues.put("outs", ImmutableList.of("third", "fourth"));
    attributeValues.put("name", "myrule");

    Rule rule = createRule(ruleClassC, "myrule", attributeValues);

    List<String> actual = new ArrayList<>();
    for (OutputFile outputFile : rule.getOutputFiles()) {
      actual.add(outputFile.getName());
      assertThat(outputFile.getGeneratingRule()).isSameInstanceAs(rule);
    }
    assertWithMessage("unexpected output set").that(actual).containsExactly("first-myrule",
        "second-myrule", "out-third", "out-fourth", "third", "fourth");
    assertWithMessage("invalid output ordering").that(actual).containsExactly("first-myrule",
        "second-myrule", "out-third", "out-fourth", "third", "fourth").inOrder();
  }

  @Test
  public void testSubstitutePlaceholderIntoTemplate() throws Exception {
    RuleClass ruleClass =
        newRuleClass(
            "ruleA",
            false,
            false,
            false,
            false,
            false,
            false,
            SafeImplicitOutputsFunction.NONE,
            null,
            DUMMY_CONFIGURED_TARGET_FACTORY,
            AdvertisedProviderSet.EMPTY,
            null,
            ImmutableSet.of(),
            true,
            attr("a", STRING_LIST).mandatory().build(),
            attr("b", STRING_LIST).mandatory().build(),
            attr("c", STRING_LIST).mandatory().build(),
            attr("baz", STRING_LIST).mandatory().build(),
            attr("empty", STRING_LIST).build());

    Map<String, Object> attributeValues = new LinkedHashMap<>();
    attributeValues.put("a", ImmutableList.of("a", "A"));
    attributeValues.put("b", ImmutableList.of("b", "B"));
    attributeValues.put("c", ImmutableList.of("c", "C"));
    attributeValues.put("baz", ImmutableList.of("baz", "BAZ"));
    attributeValues.put("empty", ImmutableList.<String>of());

    AttributeMap rule = RawAttributeMapper.of(createRule(ruleClass, "testrule", attributeValues));

    assertThat(substitutePlaceholderIntoTemplate("foo", rule)).containsExactly("foo");
    assertThat(substitutePlaceholderIntoTemplate("foo-%{baz}-bar", rule)).containsExactly(
        "foo-baz-bar", "foo-BAZ-bar").inOrder();
    assertThat(substitutePlaceholderIntoTemplate("%{a}-%{b}-%{c}", rule)).containsExactly("a-b-c",
        "a-b-C", "a-B-c", "a-B-C", "A-b-c", "A-b-C", "A-B-c", "A-B-C").inOrder();
    assertThat(substitutePlaceholderIntoTemplate("%{a", rule)).containsExactly("%{a");
    assertThat(substitutePlaceholderIntoTemplate("%{a}}", rule)).containsExactly("a}", "A}")
        .inOrder();
    assertThat(substitutePlaceholderIntoTemplate("x%{a}y%{empty}", rule)).isEmpty();
  }

  @Test
  public void testOrderIndependentAttribute() throws Exception {
    RuleClass ruleClassA = createRuleClassA();

    List<String> list = Arrays.asList("foo", "bar", "baz");
    Map<String, Object> attributeValues = new LinkedHashMap<>();
    // mandatory values
    attributeValues.put("my-string-attr", "");
    attributeValues.put("my-label-attr", "//project");
    attributeValues.put("my-string-attr2", "");
    attributeValues.put("my-labellist-attr", Collections.emptyList());
    // to compare the effect of .orderIndependent()
    attributeValues.put("my-stringlist-attr", list);
    attributeValues.put("my-sorted-stringlist-attr", list);

    Rule rule = createRule(ruleClassA, "testrule", attributeValues);
    AttributeMap attributes = RawAttributeMapper.of(rule);

    assertThat(attributes.get("my-stringlist-attr", Types.STRING_LIST)).isEqualTo(list);
    assertThat(attributes.get("my-sorted-stringlist-attr", Types.STRING_LIST))
        .isEqualTo(Arrays.asList("bar", "baz", "foo"));
  }

  @CanIgnoreReturnValue
  private Rule createRule(RuleClass ruleClass, String name, Map<String, Object> attributeValues)
      throws LabelSyntaxException, InterruptedException, CannotPrecomputeDefaultsException {
    Package.Builder pkgBuilder = createDummyPackageBuilder();
    Label ruleLabel;
    try {
      ruleLabel = pkgBuilder.createLabel(name);
    } catch (LabelSyntaxException e) {
      throw new IllegalArgumentException("Rule has illegal label", e);
    }
    attributeValues = ensureNameAttrValuePresent(attributeValues);
    Rule rule =
        ruleClass.createRule(
            pkgBuilder,
            ruleLabel,
            new BuildLangTypedAttributeValuesMap(attributeValues),
            true,
            ImmutableList.of(
                StarlarkThread.callStackEntry(StarlarkThread.TOP_LEVEL, testRuleLocation)));
    pkgBuilder.getLocalEventHandler().replayOn(reporter);
    return rule;
  }

  @Test
  public void testOverrideWithWrongType() {
    RuleClass parentRuleClass = createParentRuleClass();

    RuleClass.Builder childRuleClassBuilder =
        new RuleClass.Builder("child_rule", RuleClassType.NORMAL, false, parentRuleClass);
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> childRuleClassBuilder.override(attr("attr", INTEGER)));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "The type of the new attribute 'int' is different from "
                + "the original one 'string'.");
  }

  @Test
  public void testOverrideWithRightType() {
    RuleClass parentRuleClass = createParentRuleClass();

    RuleClass.Builder childRuleClassBuilder = new RuleClass.Builder(
      "child_rule", RuleClassType.NORMAL, false, parentRuleClass);
      childRuleClassBuilder.override(attr("attr", STRING));
  }

  @Test
  public void testCopyAndOverrideAttribute() throws Exception {
    RuleClass parentRuleClass = createParentRuleClass();
    RuleClass childRuleClass = createChildRuleClass(parentRuleClass);

    Map<String, Object> parentValues = new LinkedHashMap<>();
    Map<String, Object> childValues = new LinkedHashMap<>();
    childValues.put("attr", "somevalue");
    createRule(parentRuleClass, "parent_rule", parentValues);
    createRule(childRuleClass, "child_rule", childValues);
  }

  @Test
  public void testCopyAndOverrideAttributeMandatoryMissing() throws Exception {
    RuleClass parentRuleClass = createParentRuleClass();
    RuleClass childRuleClass = createChildRuleClass(parentRuleClass);

    Map<String, Object> childValues = new LinkedHashMap<>();
    reporter.removeHandler(failFastHandler);
    createRule(childRuleClass, "child_rule", childValues);

    assertThat(eventCollector.count()).isSameInstanceAs(1);
    assertContainsEvent("//testpackage:child_rule: missing value for mandatory "
        + "attribute 'attr' in 'child_rule' rule");
  }

  @Test
  public void testRequiredFragmentInheritance() {
    RuleClass parentRuleClass = createParentRuleClass();
    RuleClass childRuleClass = createChildRuleClass(parentRuleClass);
    assertThat(parentRuleClass.getConfigurationFragmentPolicy().getRequiredConfigurationFragments())
        .containsExactly(DummyFragment.class);
    assertThat(childRuleClass.getConfigurationFragmentPolicy().getRequiredConfigurationFragments())
        .containsExactly(DummyFragment.class);
  }

  private static RuleClass newRuleClass(
      String name,
      boolean starlarkExecutable,
      boolean documented,
      boolean binaryOutput,
      boolean workspaceOnly,
      boolean outputsDefaultExecutable,
      boolean isAnalysisTest,
      ImplicitOutputsFunction implicitOutputsFunction,
      TransitionFactory<RuleTransitionData> transitionFactory,
      ConfiguredTargetFactory<?, ?, ?> configuredTargetFactory,
      AdvertisedProviderSet advertisedProviders,
      @Nullable StarlarkFunction configuredTargetFunction,
      Set<Class<? extends Fragment>> allowedConfigurationFragments,
      boolean supportsConstraintChecking,
      Attribute... attributes) {
    return new RuleClass(
        name,
        DUMMY_STACK,
        /* key= */ name,
        RuleClassType.NORMAL,
        /* starlarkParent= */ null,
        /* initializer= */ null,
        /* labelConverterForInitializer= */ null,
        /* isStarlark= */ starlarkExecutable,
        /* starlarkExtensionLabel= */ null,
        /* starlarkDocumentation= */ null,
        /* extendable= */ false,
        /* extendableAllowlist= */ null,
        /* starlarkTestable= */ false,
        documented,
        binaryOutput,
        workspaceOnly,
        /* dependencyResolutionRule= */ false,
        outputsDefaultExecutable,
        isAnalysisTest,
        /* hasAnalysisTestTransition= */ false,
        /* allowlistCheckers= */ ImmutableList.of(),
        /* ignoreLicenses= */ false,
        implicitOutputsFunction,
        transitionFactory,
        configuredTargetFactory,
        advertisedProviders,
        configuredTargetFunction,
        /* optionReferenceFunction= */ RuleClass.NO_OPTION_REFERENCE,
        /* ruleDefinitionEnvironmentLabel= */ null,
        /* ruleDefinitionEnvironmentDigest= */ null,
        /* ruleDefinitionEnvironmentRepoMappingEntries= */ null,
        new ConfigurationFragmentPolicy.Builder()
            .requiresConfigurationFragments(allowedConfigurationFragments)
            .build(),
        supportsConstraintChecking,
        /* toolchainTypes= */ ImmutableSet.of(),
        /* toolchainResolutionMode= */ ToolchainResolutionMode.ENABLED,
        /* executionPlatformConstraints= */ ImmutableSet.of(),
        /* execGroups= */ ImmutableMap.of(),
        AutoExecGroupsMode.DYNAMIC,
        OutputFile.Kind.FILE,
        attributes.length > 0 && attributes[0].equals(RuleClass.NAME_ATTRIBUTE)
            ? ImmutableList.copyOf(attributes)
            : ImmutableList.<Attribute>builder()
                .add(RuleClass.NAME_ATTRIBUTE)
                .add(attributes)
                .build(),
        /* buildSetting= */ null,
        /* subrules= */ ImmutableList.of());
  }

  private static RuleClass createParentRuleClass() {
    return newRuleClass(
        "parent_rule",
        false,
        false,
        false,
        false,
        false,
        false,
        SafeImplicitOutputsFunction.NONE,
        null,
        DUMMY_CONFIGURED_TARGET_FACTORY,
        AdvertisedProviderSet.EMPTY,
        null,
        ImmutableSet.of(DummyFragment.class),
        true,
        attr("attr", STRING).build());
  }

  private static RuleClass createChildRuleClass(RuleClass parentRuleClass) {
    RuleClass.Builder childRuleClassBuilder = new RuleClass.Builder(
        "child_rule", RuleClassType.NORMAL, false, parentRuleClass);
    return childRuleClassBuilder.override(
        childRuleClassBuilder
          .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
          .copy("attr").mandatory())
          .add(attr("tags", STRING_LIST))
          .build();
  }

  @Test
  public void testBadRuleClassNames() {
    expectError(RuleClassType.NORMAL, "8abc");
    expectError(RuleClassType.NORMAL, "!abc");
    expectError(RuleClassType.NORMAL, "a b");
  }

  private static void expectError(RuleClassType type, String name) {
    assertThrows(IllegalArgumentException.class, () -> type.checkName(name));
  }

  @Test
  public void testToolchainTypes() throws Exception {
    RuleClass.Builder ruleClassBuilder =
        new RuleClass.Builder("ruleClass", RuleClassType.NORMAL, false)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .add(attr("tags", STRING_LIST));

    ruleClassBuilder.addToolchainTypes(
        ToolchainTypeRequirement.create(Label.parseCanonical("//toolchain:tc1")),
        ToolchainTypeRequirement.create(Label.parseCanonical("//toolchain:tc2")));

    RuleClass ruleClass = ruleClassBuilder.build();

    assertThat(ruleClass).hasToolchainType("//toolchain:tc1");
    assertThat(ruleClass).hasToolchainType("//toolchain:tc2");
  }

  @Test
  public void testExecutionPlatformConstraints() throws Exception {
    RuleClass.Builder ruleClassBuilder =
        new RuleClass.Builder("ruleClass", RuleClassType.NORMAL, false)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .add(attr("tags", STRING_LIST));

    ruleClassBuilder.addExecutionPlatformConstraints(
        Label.parseCanonical("//constraints:cv1"), Label.parseCanonical("//constraints:cv2"));

    RuleClass ruleClass = ruleClassBuilder.build();

    assertThat(ruleClass.getExecutionPlatformConstraints())
        .containsExactly(
            Label.parseCanonical("//constraints:cv1"), Label.parseCanonical("//constraints:cv2"));
  }

  @Test
  public void testExecutionPlatformConstraints_inheritConstraintsFromParent() throws Exception {
    RuleClass parentRuleClass =
        new RuleClass.Builder("$parentRuleClass", RuleClassType.ABSTRACT, false)
            .add(attr("tags", STRING_LIST))
            .addExecutionPlatformConstraints(
                Label.parseCanonical("//constraints:cv1"),
                Label.parseCanonical("//constraints:cv2"))
            .build();

    RuleClass childRuleClass =
        new RuleClass.Builder("childRuleClass", RuleClassType.NORMAL, false, parentRuleClass)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .build();

    assertThat(childRuleClass.getExecutionPlatformConstraints())
        .containsExactly(
            Label.parseCanonical("//constraints:cv1"), Label.parseCanonical("//constraints:cv2"));
  }

  @Test
  public void testExecutionPlatformConstraints_inheritAndAddConstraints() throws Exception {
    RuleClass parentRuleClass =
        new RuleClass.Builder("$parentRuleClass", RuleClassType.ABSTRACT, false)
            .add(attr("tags", STRING_LIST))
            .build();

    RuleClass.Builder childRuleClassBuilder =
        new RuleClass.Builder("childRuleClass", RuleClassType.NORMAL, false, parentRuleClass)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .addExecutionPlatformConstraints(
                Label.parseCanonical("//constraints:cv1"),
                Label.parseCanonical("//constraints:cv2"));

    RuleClass childRuleClass = childRuleClassBuilder.build();

    assertThat(childRuleClass.getExecutionPlatformConstraints())
        .containsExactly(
            Label.parseCanonical("//constraints:cv1"), Label.parseCanonical("//constraints:cv2"));
  }

  @Test
  public void testExecGroups() {
    RuleClass.Builder ruleClassBuilder =
        new RuleClass.Builder("ruleClass", RuleClassType.NORMAL, false)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .add(attr("tags", STRING_LIST));

    Label toolchain = Label.parseCanonicalUnchecked("//toolchain");
    Label constraint = Label.parseCanonicalUnchecked("//constraint");

    // TODO(https://github.com/bazelbuild/bazel/issues/14726): Add tests of optional toolchains.
    ruleClassBuilder.addExecGroups(
        ImmutableMap.of(
            "cherry",
            ExecGroup.builder()
                .addToolchainType(ToolchainTypeRequirement.create(toolchain))
                .execCompatibleWith(ImmutableSet.of(constraint))
                .build()));

    RuleClass ruleClass = ruleClassBuilder.build();

    assertThat(ruleClass.getExecGroups()).hasSize(1);
    assertThat(ruleClass.getExecGroups().get("cherry")).hasToolchainType(toolchain);
    assertThat(ruleClass.getExecGroups().get("cherry")).toolchainType(toolchain).isMandatory();
    assertThat(ruleClass.getExecGroups().get("cherry")).hasExecCompatibleWith(constraint);
  }

  @Test
  public void testBuildSetting_createsDefaultAttribute() {
    RuleClass labelFlag =
        new RuleClass.Builder("label_flag", RuleClassType.NORMAL, false)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .add(attr("tags", STRING_LIST))
            .setBuildSetting(BuildSetting.create(true, NODEP_LABEL))
            .build();
    RuleClass stringSetting =
        new RuleClass.Builder("string_setting", RuleClassType.NORMAL, false)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .add(attr("tags", STRING_LIST))
            .setBuildSetting(BuildSetting.create(false, STRING))
            .build();

    assertThat(labelFlag.hasAttr(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME, NODEP_LABEL)).isTrue();
    assertThat(stringSetting.hasAttr(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME, STRING)).isTrue();
  }

  @Test
  public void testBuildSetting_doesNotCreateDefaultAttributeIfNotBuildSetting() {
    RuleClass stringSetting =
        new RuleClass.Builder("non_build_setting", RuleClassType.NORMAL, false)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .add(attr("tags", STRING_LIST))
            .build();

    assertThat(stringSetting.hasAttr(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME, LABEL)).isFalse();
  }

  @Test
  public void testBuildTooManyAttributesRejected() {
    RuleClass.Builder builder =
        new RuleClass.Builder("myclass", RuleClassType.NORMAL, /*starlark=*/ false)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .add(attr("tags", STRING_LIST));
    for (int i = 0; i < 200; i++) {
      builder.add(attr("attr" + i, STRING));
    }

    IllegalArgumentException expected =
        assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(expected)
        .hasMessageThat()
        .isEqualTo("Rule class myclass declared too many attributes (202 > 200)");
  }

  @Test
  public void testBuildTooLongAttributeNameRejected() {
    IllegalArgumentException expected =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new RuleClass.Builder("myclass", RuleClassType.NORMAL, /*starlark=*/ false)
                    .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                    .add(attr("tags", STRING_LIST))
                    .add(attr("x".repeat(150), STRING))
                    .build());

    assertThat(expected)
        .hasMessageThat()
        .matches("Attribute myclass\\.x{150}'s name is too long \\(150 > 128\\)");
  }

  @Test
  public void testPackageMetadataAlternateName() throws Exception {
    RuleClass noopClass =
        new RuleClass.Builder("noop", RuleClassType.NORMAL, false)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .add(attr("tags", STRING_LIST))
            .add(attr(RuleClass.APPLICABLE_METADATA_ATTR, LABEL_LIST).legacyAllowAnyFileType())
            .build();
    Map<String, Object> attributeValues = new LinkedHashMap<>();
    attributeValues.put("applicable_licenses", Lists.newArrayList(":info"));
    Rule noopRule = createRule(noopClass, "noop", attributeValues);

    assertThat(noopRule.getAttr(RuleClass.APPLICABLE_METADATA_ATTR, LABEL_LIST))
        .isEqualTo(Lists.newArrayList(Label.parseCanonical("//testpackage:info")));
  }
}
