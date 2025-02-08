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

package com.google.devtools.build.lib.analysis.starlark;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.devtools.build.lib.analysis.BaseRuleClasses.RUN_UNDER_EXEC_CONFIG;
import static com.google.devtools.build.lib.analysis.BaseRuleClasses.RUN_UNDER_TARGET_CONFIG;
import static com.google.devtools.build.lib.analysis.BaseRuleClasses.TIMEOUT_DEFAULT;
import static com.google.devtools.build.lib.analysis.BaseRuleClasses.getTestRuntimeLabelList;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static com.google.devtools.build.lib.packages.BuildType.LICENSE;
import static com.google.devtools.build.lib.packages.BuiltinRestriction.allowlistEntry;
import static com.google.devtools.build.lib.packages.RuleClass.DEFAULT_TEST_RUNNER_EXEC_GROUP;
import static com.google.devtools.build.lib.packages.RuleClass.DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME;
import static com.google.devtools.build.lib.packages.Type.BOOLEAN;
import static com.google.devtools.build.lib.packages.Type.INTEGER;
import static com.google.devtools.build.lib.packages.Type.STRING;
import static com.google.devtools.build.lib.packages.Types.STRING_LIST;
import static java.util.Objects.requireNonNull;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.analysis.Allowlist;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.DormantDependency;
import com.google.devtools.build.lib.analysis.PackageSpecificationProvider;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.TemplateVariableInfo;
import com.google.devtools.build.lib.analysis.config.ExecutionTransitionFactory;
import com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.analysis.config.transitions.ComposingTransitionFactory;
import com.google.devtools.build.lib.analysis.config.transitions.NoTransition;
import com.google.devtools.build.lib.analysis.config.transitions.StarlarkExposedRuleTransitionFactory;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.TransitionType;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.Visitor;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor;
import com.google.devtools.build.lib.analysis.test.TestConfiguration;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.cmdline.StarlarkThreadContext;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.AllowlistChecker;
import com.google.devtools.build.lib.packages.AllowlistChecker.LocationCheck;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.StarlarkComputedDefaultTemplate;
import com.google.devtools.build.lib.packages.AttributeTransitionData;
import com.google.devtools.build.lib.packages.AttributeValueSource;
import com.google.devtools.build.lib.packages.BuildSetting;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.BuiltinRestriction;
import com.google.devtools.build.lib.packages.BuiltinRestriction.AllowlistEntry;
import com.google.devtools.build.lib.packages.BzlInitThreadContext;
import com.google.devtools.build.lib.packages.ConfigurationFragmentPolicy.MissingFragmentPolicy;
import com.google.devtools.build.lib.packages.ExecGroup;
import com.google.devtools.build.lib.packages.FunctionSplitTransitionAllowlist;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.StarlarkImplicitOutputsFunctionWithCallback;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.StarlarkImplicitOutputsFunctionWithMap;
import com.google.devtools.build.lib.packages.LabelConverter;
import com.google.devtools.build.lib.packages.MacroClass;
import com.google.devtools.build.lib.packages.MacroInstance;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.PredicateWithMessage;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.packages.RuleFactory;
import com.google.devtools.build.lib.packages.RuleFactory.BuildLangTypedAttributeValuesMap;
import com.google.devtools.build.lib.packages.RuleFactory.InvalidRuleException;
import com.google.devtools.build.lib.packages.RuleFunction;
import com.google.devtools.build.lib.packages.RuleTransitionData;
import com.google.devtools.build.lib.packages.StarlarkAspect;
import com.google.devtools.build.lib.packages.StarlarkCallbackHelper;
import com.google.devtools.build.lib.packages.StarlarkDefinedAspect;
import com.google.devtools.build.lib.packages.StarlarkExportable;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StarlarkProviderIdentifier;
import com.google.devtools.build.lib.packages.TargetRecorder.NameConflictException;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.Type.LabelClass;
import com.google.devtools.build.lib.packages.Types;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.skyframe.BzlLoadValue;
import com.google.devtools.build.lib.skyframe.serialization.AbstractExportedStarlarkSymbolCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.starlarkbuildapi.MacroFunctionApi;
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkRuleFunctionsApi;
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkSubruleApi;
import com.google.devtools.build.lib.starlarkbuildapi.config.ConfigurationTransitionApi;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.util.Pair;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.Keep;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.SymbolGenerator.GlobalSymbol;
import net.starlark.java.eval.SymbolGenerator.Symbol;
import net.starlark.java.eval.Tuple;
import net.starlark.java.syntax.Identifier;
import net.starlark.java.syntax.Location;

/** A helper class to provide an easier API for Starlark rule definitions. */
public class StarlarkRuleClassFunctions implements StarlarkRuleFunctionsApi {

  // A cache for base rule classes (especially tests).
  private static final LoadingCache<String, Label> labelCache =
      Caffeine.newBuilder().build(Label::parseCanonical);

  // TODO(bazel-team): Remove the code duplication (BaseRuleClasses and this class).
  /** Parent rule class for non-executable non-test Starlark rules. */
  public static final RuleClass baseRule =
      BaseRuleClasses.commonCoreAndStarlarkAttributes(
              new RuleClass.Builder("$base_rule", RuleClassType.ABSTRACT, true)
                  .add(attr("expect_failure", STRING)))
          // TODO(skylark-team): Allow Starlark rules to extend native rules and remove duplication.
          .add(
              attr("toolchains", LABEL_LIST)
                  .allowedFileTypes(FileTypeSet.NO_FILE)
                  .mandatoryProviders(ImmutableList.of(TemplateVariableInfo.PROVIDER.id()))
                  .dontCheckConstraints())
          .add(attr(RuleClass.EXEC_PROPERTIES_ATTR, Types.STRING_DICT).value(ImmutableMap.of()))
          .add(
              attr(RuleClass.EXEC_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST)
                  .allowedFileTypes()
                  .nonconfigurable("Used in toolchain resolution")
                  .tool(
                      "exec_compatible_with exists for constraint checking, not to create an"
                          + " actual dependency")
                  .value(ImmutableList.of()))
          .add(
              attr(RuleClass.EXEC_GROUP_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST_DICT)
                  .allowedFileTypes()
                  .nonconfigurable("Used in toolchain resolution")
                  .tool(
                      "exec_group_compatible_with exists for constraint checking, not to create an"
                          + " actual dependency")
                  .value(ImmutableMap.of()))
          .add(
              attr(RuleClass.TARGET_COMPATIBLE_WITH_ATTR, LABEL_LIST)
                  .mandatoryProviders(ConstraintValueInfo.PROVIDER.id())
                  // This should be configurable to allow for complex types of restrictions.
                  .tool(
                      "target_compatible_with exists for constraint checking, not to create an"
                          + " actual dependency")
                  .allowedFileTypes(FileTypeSet.NO_FILE))
          .build();

  public static final RuleClass dependencyResolutionBaseRule =
      new RuleClass.Builder(
              "$dependency_resolution_base_rule", RuleClassType.ABSTRACT, true, baseRule)
          .setDependencyResolutionRule()
          .removeAttribute(":action_listener")
          .removeAttribute("aspect_hints")
          .removeAttribute("toolchains")
          .removeAttribute(RuleClass.EXEC_COMPATIBLE_WITH_ATTR)
          .removeAttribute(RuleClass.EXEC_GROUP_COMPATIBLE_WITH_ATTR)
          .removeAttribute(RuleClass.TARGET_COMPATIBLE_WITH_ATTR)
          .removeAttribute("compatible_with")
          .removeAttribute("restricted_to")
          .removeAttribute("$config_dependencies")
          .removeAttribute("package_metadata")
          .build();

  /** Parent rule class for executable non-test Starlark rules. */
  private static final RuleClass binaryBaseRule =
      new RuleClass.Builder("$binary_base_rule", RuleClassType.ABSTRACT, true, baseRule)
          .add(attr("args", STRING_LIST))
          .add(attr("output_licenses", LICENSE))
          .addAttribute(
              attr(Rule.IS_EXECUTABLE_ATTRIBUTE_NAME, BOOLEAN)
                  .value(true)
                  .nonconfigurable("Called from RunCommand.isExecutable, which takes a Target")
                  .build())
          .build();

  public static final ImmutableSet<AllowlistEntry> ALLOWLIST_RULE_EXTENSION_API =
      ImmutableSet.of(
          allowlistEntry("", "initializer_testing"),
          allowlistEntry("", "extend_rule_testing"),
          allowlistEntry("", "subrule_testing"));

  public static final ImmutableSet<AllowlistEntry> ALLOWLIST_RULE_EXTENSION_API_EXPERIMENTAL =
      ImmutableSet.of(allowlistEntry("", "initializer_testing/builtins"));

  private static final String COMMON_ATTRIBUTES_NAME = "common";

  /** Parent rule class for test Starlark rules. */
  public static RuleClass getTestBaseRule(RuleDefinitionEnvironment env) {
    RepositoryName toolsRepository = env.getToolsRepository();
    RuleClass.Builder builder =
        new RuleClass.Builder("$test_base_rule", RuleClassType.ABSTRACT, true, baseRule)
            .requiresConfigurationFragments(TestConfiguration.class)
            // TestConfiguration only needed to create TestAction and TestProvider
            // Only necessary at top-level and can be skipped if trimmed.
            .setMissingFragmentPolicy(TestConfiguration.class, MissingFragmentPolicy.IGNORE)
            .add(
                attr("size", STRING)
                    .value("medium")
                    .taggable()
                    .nonconfigurable("used in loading phase rule validation logic"))
            .add(
                attr("timeout", STRING)
                    .taggable()
                    .nonconfigurable("policy decision: should be consistent across configurations")
                    .value(TIMEOUT_DEFAULT))
            .add(
                attr("flaky", BOOLEAN)
                    .value(false)
                    .taggable()
                    .nonconfigurable("taggable - called in Rule.getRuleTags"))
            .add(attr("shard_count", INTEGER).value(StarlarkInt.of(-1)))
            .add(
                attr("local", BOOLEAN)
                    .value(false)
                    .taggable()
                    .nonconfigurable(
                        "policy decision: this should be consistent across configurations"))
            .add(attr("args", STRING_LIST))
            // Input files for every test action
            .add(
                attr("$test_wrapper", LABEL)
                    .cfg(ExecutionTransitionFactory.createFactory())
                    .singleArtifact()
                    .value(labelCache.get(toolsRepository + "//tools/test:test_wrapper")))
            .add(
                attr("$xml_writer", LABEL)
                    .cfg(ExecutionTransitionFactory.createFactory())
                    .singleArtifact()
                    .value(labelCache.get(toolsRepository + "//tools/test:xml_writer")))
            .add(
                attr("$test_runtime", LABEL_LIST)
                    .cfg(ExecutionTransitionFactory.createFactory())
                    // Getting this default value through the getTestRuntimeLabelList helper ensures
                    // we reuse the same ImmutableList<Label> instance for each $test_runtime attr.
                    .value(getTestRuntimeLabelList(env)))
            .add(
                attr("$test_setup_script", LABEL)
                    .cfg(ExecutionTransitionFactory.createFactory())
                    .singleArtifact()
                    .value(labelCache.get(toolsRepository + "//tools/test:test_setup")))
            .add(
                attr("$xml_generator_script", LABEL)
                    .cfg(ExecutionTransitionFactory.createFactory())
                    .singleArtifact()
                    .value(labelCache.get(toolsRepository + "//tools/test:test_xml_generator")))
            .add(
                attr("$collect_coverage_script", LABEL)
                    .cfg(ExecutionTransitionFactory.createFactory())
                    .singleArtifact()
                    .value(labelCache.get(toolsRepository + "//tools/test:collect_coverage")))
            // Input files for test actions collecting code coverage
            .add(
                attr(":coverage_support", LABEL)
                    .cfg(ExecutionTransitionFactory.createFactory())
                    .value(
                        BaseRuleClasses.coverageSupportAttribute(
                            labelCache.get(
                                toolsRepository + BaseRuleClasses.DEFAULT_COVERAGE_SUPPORT_VALUE))))
            // Used in the one-per-build coverage report generation action.
            .add(
                attr(":coverage_report_generator", LABEL)
                    .cfg(ExecutionTransitionFactory.createFactory())
                    .value(
                        BaseRuleClasses.coverageReportGeneratorAttribute(
                            labelCache.get(
                                toolsRepository
                                    + BaseRuleClasses.DEFAULT_COVERAGE_REPORT_GENERATOR_VALUE))))
            // See similar definitions in BaseRuleClasses for context.
            .add(
                attr(":run_under_exec_config", LABEL)
                    .cfg(ExecutionTransitionFactory.createFactory("test"))
                    .value(RUN_UNDER_EXEC_CONFIG)
                    .skipPrereqValidatorCheck())
            .add(
                attr(":run_under_target_config", LABEL)
                    .value(RUN_UNDER_TARGET_CONFIG)
                    .skipPrereqValidatorCheck())
            .addAttribute(
                attr(Rule.IS_EXECUTABLE_ATTRIBUTE_NAME, BOOLEAN)
                    .value(true)
                    .nonconfigurable("Called from RunCommand.isExecutable, which takes a Target")
                    .build());

    env.getNetworkAllowlistForTests()
        .ifPresent(
            label ->
                builder.add(
                    Allowlist.getAttributeFromAllowlistName("external_network").value(label)));

    return builder.build();
  }

  @Override
  public Object provider(Object doc, Object fields, Object init, StarlarkThread thread)
      throws EvalException {
    StarlarkProvider.Builder builder = StarlarkProvider.builder(thread.getCallerLocation());
    Starlark.toJavaOptional(doc, String.class)
        .map(Starlark::trimDocString)
        .ifPresent(builder::setDocumentation);
    if (fields instanceof Sequence) {
      builder.setSchema(Sequence.cast(fields, String.class, "fields"));
    } else if (fields instanceof Dict) {
      builder.setSchema(
          Maps.transformValues(
              Dict.cast(fields, String.class, String.class, "fields"), Starlark::trimDocString));
    }
    if (init == Starlark.NONE) {
      return builder.buildWithIdentityToken(thread.getNextIdentityToken());
    }
    if (init instanceof StarlarkCallable callable) {
      builder.setInit(callable);
    } else {
      throw Starlark.errorf("got %s for init, want callable value", Starlark.type(init));
    }
    StarlarkProvider provider = builder.buildWithIdentityToken(thread.getNextIdentityToken());
    return Tuple.of(provider, provider.createRawConstructor());
  }

  @FormatMethod
  private static void failIf(boolean condition, String message, Object... args)
      throws EvalException {
    if (condition) {
      throw Starlark.errorf(message, args);
    }
  }

  @Override
  public MacroFunctionApi macro(
      StarlarkFunction implementation,
      Dict<?, ?> attrs,
      Object inheritAttrs,
      boolean finalizer,
      Object doc,
      StarlarkThread thread)
      throws EvalException {
    // Ordinarily we would use StarlarkMethod#enableOnlyWithFlag, but this doesn't work for
    // top-level symbols (due to StarlarkGlobalsImpl relying on the Starlark#addMethods overload
    // that uses default StarlarkSemantics), so enforce it here instead.
    if (!thread
        .getSemantics()
        .getBool(BuildLanguageOptions.EXPERIMENTAL_ENABLE_FIRST_CLASS_MACROS)) {
      throw Starlark.errorf("Use of `macro()` requires --experimental_enable_first_class_macros");
    }

    MacroClass.Builder builder = new MacroClass.Builder(implementation);
    for (Map.Entry<?, ?> uncheckedEntry : attrs.entrySet()) {
      String attrName;
      @Nullable Descriptor descriptor;
      try {
        // Dict.cast() does not support none-able values - so we type-check manually, and translate
        // Starlark None to Java null.
        attrName = (String) uncheckedEntry.getKey();
        checkAttributeName(attrName);
        descriptor =
            uncheckedEntry.getValue() != Starlark.NONE
                ? (Descriptor) uncheckedEntry.getValue()
                : null;
      } catch (
          @SuppressWarnings("UnusedException")
          ClassCastException e) {
        throw Starlark.errorf(
            "got dict<%s, %s> for 'attrs', want dict<string, Attribute|None>",
            Starlark.type(uncheckedEntry.getKey()), Starlark.type(uncheckedEntry.getValue()));
      }

      // "name" and "visibility" attributes are added automatically by the builder.
      if (MacroClass.RESERVED_MACRO_ATTR_NAMES.contains(attrName)) {
        throw Starlark.errorf("Cannot declare a macro attribute named '%s'", attrName);
      }

      if (descriptor == null) {
        // a None descriptor should ignored.
        continue;
      }

      if (!descriptor.getValueSource().equals(AttributeValueSource.DIRECT)) {
        // Note that inherited native attributes may have a computed default, e.g. testonly.
        throw Starlark.errorf(
            "In macro attribute '%s': Macros do not support computed defaults or late-bound"
                + " defaults",
            attrName);
      }

      Attribute attr = descriptor.build(attrName);
      builder.addAttribute(attr);
    }
    for (Attribute attr : getAttrsOf(inheritAttrs)) {
      String attrName = attr.getName();
      if (attr.isPublic()
          // isDocumented() is false only for generator_* magic attrs (for which isPublic() is true)
          && attr.isDocumented()
          && !MacroClass.RESERVED_MACRO_ATTR_NAMES.contains(attrName)
          && !attrs.containsKey(attrName)) {
        // Force the default value of optional inherited attributes to None.
        if (!attr.isMandatory()
            && attr.getDefaultValueUnchecked() != null
            && attr.getDefaultValueUnchecked() != Starlark.NONE) {
          attr = attr.cloneBuilder().defaultValueNone().build();
        }
        builder.addAttribute(attr);
      }
    }
    if (inheritAttrs != Starlark.NONE && !implementation.hasKwargs()) {
      throw Starlark.errorf(
          "If inherit_attrs is set, implementation function must have a **kwargs parameter");
    }

    if (finalizer) {
      builder.setIsFinalizer();
    }

    return new MacroFunction(
        builder,
        Starlark.toJavaOptional(doc, String.class).map(Starlark::trimDocString),
        getBzlKeyToken(thread, "Macros"));
  }

  private static ImmutableList<Attribute> getAttrsOf(Object inheritAttrsArg) throws EvalException {
    if (inheritAttrsArg == Starlark.NONE) {
      return ImmutableList.of();
    } else if (inheritAttrsArg instanceof RuleFunction ruleFunction) {
      verifyInheritAttrsArgExportedIfExportable(ruleFunction);
      return ruleFunction.getRuleClass().getAttributes();
    } else if (inheritAttrsArg instanceof MacroFunction macroFunction) {
      verifyInheritAttrsArgExportedIfExportable(macroFunction);
      return macroFunction.getMacroClass().getAttributes().values().asList();
    } else if (inheritAttrsArg.equals(COMMON_ATTRIBUTES_NAME)) {
      return baseRule.getAttributes();
    }
    throw Starlark.errorf(
        "Invalid 'inherit_attrs' value %s; expected a rule, a macro, or \"common\"",
        Starlark.repr(inheritAttrsArg));
  }

  private static void verifyInheritAttrsArgExportedIfExportable(Object inheritAttrsArg)
      throws EvalException {
    // Note that the value of 'inherit_attrs' can be non-exportable (e.g. native rule).
    if (inheritAttrsArg instanceof StarlarkExportable exportable && !exportable.isExported()) {
      throw Starlark.errorf(
          "Invalid 'inherit_attrs' value: a rule or macro callable must be assigned to a global"
              + " variable in a .bzl file before it can be inherited from");
    }
  }

  private static Symbol<BzlLoadValue.Key> getBzlKeyToken(StarlarkThread thread, String onBehalfOf) {
    Symbol<?> untypedToken = thread.getNextIdentityToken();
    checkState(
        untypedToken.getOwner() instanceof BzlLoadValue.Key,
        "%s may only be owned by .bzl files (owner=%s)",
        onBehalfOf,
        untypedToken);
    @SuppressWarnings("unchecked")
    var typedToken = (Symbol<BzlLoadValue.Key>) untypedToken;
    return typedToken;
  }

  // TODO(bazel-team): implement attribute copy and other rule properties
  @Override
  public StarlarkRuleFunction rule(
      StarlarkFunction implementation,
      Object testUnchecked,
      Dict<?, ?> attrs,
      Object implicitOutputs,
      Object executableUnchecked,
      boolean outputToGenfiles,
      Sequence<?> fragments,
      Sequence<?> hostFragments,
      boolean starlarkTestable,
      Sequence<?> toolchains,
      boolean useToolchainTransition,
      Object doc,
      Sequence<?> providesArg,
      boolean dependencyResolutionRule,
      Sequence<?> execCompatibleWith,
      boolean analysisTest,
      Object buildSetting,
      Object cfg,
      Object execGroups,
      Object initializer,
      Object parentUnchecked,
      Object extendableUnchecked,
      Sequence<?> subrules,
      StarlarkThread thread)
      throws EvalException {
    // Ensure we're initializing a .bzl file, which also means we have a RuleDefinitionEnvironment.
    BzlInitThreadContext bazelContext = BzlInitThreadContext.fromOrFail(thread, "rule()");

    final RuleClass parent;
    final boolean executable;
    final boolean test;

    if (parentUnchecked == Starlark.NONE) {
      parent = null;
      executable = executableUnchecked == Starlark.UNBOUND ? false : (Boolean) executableUnchecked;
      test = testUnchecked == Starlark.UNBOUND ? false : (Boolean) testUnchecked;
    } else {
      failIf(
          !(parentUnchecked instanceof StarlarkRuleFunction),
          "Parent needs to be a Starlark rule, was %s",
          Starlark.type(parentUnchecked));
      // Assuming parent is already exported.
      failIf(
          ((StarlarkRuleFunction) parentUnchecked).ruleClass == null,
          "Please export the parent rule before extending it.");

      parent = ((StarlarkRuleFunction) parentUnchecked).ruleClass;
      executable = parent.isExecutableStarlark();
      test = parent.getRuleClassType() == RuleClassType.TEST;

      failIf(
          !parent.isExtendable(),
          "The rule '%s' is not extendable. Only Starlark rules not using deprecated features (like"
              + " implicit outputs, output to genfiles) may be extended. Special rules like"
              + " analysis tests or rules using build_settings cannot be extended.",
          parent.getName());

      failIf(
          executableUnchecked != Starlark.UNBOUND,
          "Omit executable parameter when extending rules.");
      failIf(testUnchecked != Starlark.UNBOUND, "Omit test parameter when extending rules.");
      failIf(
          implicitOutputs != Starlark.NONE,
          "implicit_outputs is not supported when extending rules (deprecated).");
      failIf(
          !hostFragments.isEmpty(),
          "host_fragments are not supported when extending rules (deprecated).");
      failIf(
          outputToGenfiles,
          "output_to_genfiles are not supported when extending rules (deprecated).");
      failIf(starlarkTestable, "_skylark_testable is not supported when extending rules.");
      failIf(analysisTest, "analysis_test is not supported when extending rules.");
      failIf(buildSetting != Starlark.NONE, "build_setting is not supported when extending rules.");
    }

    LabelConverter labelConverter = LabelConverter.forBzlEvaluatingThread(thread);

    return createRule(
        // Contextual parameters.
        bazelContext,
        thread,
        bazelContext.getBzlFile(),
        bazelContext.getTransitiveDigest(),
        labelConverter,
        // rule() parameters
        parent,
        extendableUnchecked,
        implementation,
        initializer == Starlark.NONE ? null : (StarlarkFunction) initializer,
        test,
        attrs,
        implicitOutputs,
        executable,
        outputToGenfiles,
        fragments,
        starlarkTestable,
        toolchains,
        doc,
        providesArg,
        dependencyResolutionRule,
        execCompatibleWith,
        analysisTest,
        buildSetting,
        cfg,
        execGroups,
        subrules);
  }

  /**
   * Returns a new callable representing a Starlark-defined rule.
   *
   * <p>This is public for the benefit of {@link
   * com.google.devtools.build.lib.rules.test.StarlarkTestingModule}, which has the unusual use case
   * of creating new rule types to house analysis-time test assertions ({@code analysis_test}). It's
   * probably not a good idea to add new callers of this method.
   *
   * <p>Note that the bzlFile and transitiveDigest params correspond to the outermost .bzl file
   * being evaluated, not the one in which rule() is called.
   */
  public static StarlarkRuleFunction createRule(
      // Contextual parameters.
      RuleDefinitionEnvironment ruleDefinitionEnvironment,
      StarlarkThread thread,
      Label bzlFile,
      byte[] transitiveDigest,
      LabelConverter labelConverter,
      // Parameters that come from rule().
      @Nullable RuleClass parent,
      @Nullable Object extendableUnchecked,
      StarlarkFunction implementation,
      @Nullable StarlarkFunction initializer,
      boolean test,
      Dict<?, ?> attrs,
      Object implicitOutputs,
      boolean executable,
      boolean outputToGenfiles,
      Sequence<?> fragments,
      boolean starlarkTestable,
      Sequence<?> toolchains,
      Object doc,
      Sequence<?> providesArg,
      boolean dependencyResolutionRule,
      Sequence<?> execCompatibleWith,
      Object analysisTest,
      Object buildSetting,
      Object cfg,
      Object execGroups,
      Sequence<?> subrulesUnchecked)
      throws EvalException {

    // analysis_test=true implies test=true.
    test |= Boolean.TRUE.equals(analysisTest);

    RuleClassType type = test ? RuleClassType.TEST : RuleClassType.NORMAL;

    final RuleClass.Builder builder;
    if (dependencyResolutionRule) {
      if (parent != null) {
        throw Starlark.errorf("rules used in dependency resolution cannot have a parent");
      }

      builder = new RuleClass.Builder("", type, true, dependencyResolutionBaseRule);
    } else if (parent != null) {
      if (parent.isDependencyResolutionRule()) {
        throw Starlark.errorf("dependency resolution rules cannot be parents");
      }

      // We'll set the name later, pass the empty string for now.
      builder = new RuleClass.Builder("", type, true, parent);
    } else {
      // We'll set the name later, pass the empty string for now.
      RuleClass baseParent =
          test
              ? getTestBaseRule(ruleDefinitionEnvironment)
              : (executable ? binaryBaseRule : baseRule);
      builder = new RuleClass.Builder("", type, true, baseParent);
    }

    builder.initializer(initializer, labelConverter);

    builder.setDefaultExtendableAllowlist(
        ruleDefinitionEnvironment.getToolsLabel("//tools/allowlists/extend_rule_allowlist"));
    if (extendableUnchecked instanceof Boolean) {
      builder.setExtendable((Boolean) extendableUnchecked);
    } else if (extendableUnchecked instanceof String) {
      try {
        builder.setExtendableByAllowlist(labelConverter.convert((String) extendableUnchecked));
      } catch (LabelSyntaxException e) {
        throw Starlark.errorf(
            "Unable to parse label '%s': %s", extendableUnchecked, e.getMessage());
      }
    } else if (extendableUnchecked instanceof Label) {
      builder.setExtendableByAllowlist((Label) extendableUnchecked);
    } else {
      failIf(
          !(extendableUnchecked == Starlark.NONE || extendableUnchecked == null),
          "parameter 'extendable': expected bool, str or Label, but got '%s'",
          Starlark.type(extendableUnchecked));
    }

    // Verify the child against parent's allowlist
    if (parent != null
        && parent.getExtendableAllowlist() != null
        && !bzlFile.getRepository().getName().equals("_builtins")) {
      builder.addAllowlistChecker(EXTEND_RULE_ALLOWLIST_CHECKER);
      Attribute.Builder<Label> allowlistAttr =
          attr("$allowlist_extend_rule", LABEL)
              .cfg(ExecutionTransitionFactory.createFactory())
              .mandatoryBuiltinProviders(ImmutableList.of(PackageSpecificationProvider.class))
              .value(parent.getExtendableAllowlist());
      if (builder.contains("$allowlist_extend_rule")) {
        // the allowlist already exist if this is the second extension of the rule
        // in this case we need to override the allowlist with the one in the direct parent
        builder.override(allowlistAttr);
      } else {
        builder.add(allowlistAttr);
      }
    }

    if (parent != null
        && !thread.getSemantics().getBool(BuildLanguageOptions.EXPERIMENTAL_RULE_EXTENSION_API)
        && !bzlFile.getRepository().getName().equals("_builtins")) {
      builder.addAllowlistChecker(EXTEND_RULE_API_ALLOWLIST_CHECKER);
      if (!builder.contains("$allowlist_extend_rule_api")) {
        Attribute.Builder<Label> allowlistAttr =
            attr("$allowlist_extend_rule_api", LABEL)
                .cfg(ExecutionTransitionFactory.createFactory())
                .mandatoryBuiltinProviders(ImmutableList.of(PackageSpecificationProvider.class))
                .value(
                    ruleDefinitionEnvironment.getToolsLabel(
                        "//tools/allowlists/extend_rule_allowlist:extend_rule_api_allowlist"));
        builder.add(allowlistAttr);
      }
    }

    if (initializer != null) {
      if (!thread.getSemantics().getBool(BuildLanguageOptions.EXPERIMENTAL_RULE_EXTENSION_API)
          && !bzlFile.getRepository().getName().equals("_builtins")) {
        builder.addAllowlistChecker(INITIALIZER_ALLOWLIST_CHECKER);
        if (!builder.contains("$allowlist_initializer")) {
          // the allowlist already exist if this is an extended rule
          Attribute.Builder<Label> allowlistAttr =
              attr("$allowlist_initializer", LABEL)
                  .cfg(ExecutionTransitionFactory.createFactory())
                  .mandatoryBuiltinProviders(ImmutableList.of(PackageSpecificationProvider.class))
                  .value(
                      ruleDefinitionEnvironment.getToolsLabel(
                          "//tools/allowlists/initializer_allowlist"));
          builder.add(allowlistAttr);
        }
      }
    }

    if (!subrulesUnchecked.isEmpty()) {
      if (!thread.getSemantics().getBool(BuildLanguageOptions.EXPERIMENTAL_RULE_EXTENSION_API)
          && !bzlFile.getRepository().getName().equals("_builtins")) {
        builder.addAllowlistChecker(SUBRULES_ALLOWLIST_CHECKER);
        if (!builder.contains("$allowlist_subrules")) {
          // the allowlist already exist if this is an extended rule
          Attribute.Builder<Label> allowlistAttr =
              attr("$allowlist_subrules", LABEL)
                  .cfg(ExecutionTransitionFactory.createFactory())
                  .mandatoryBuiltinProviders(ImmutableList.of(PackageSpecificationProvider.class))
                  .value(
                      ruleDefinitionEnvironment.getToolsLabel(
                          "//tools/allowlists/subrules_allowlist"));
          builder.add(allowlistAttr);
        }
      }
    }

    if (executable || test) {
      builder.setExecutableStarlark();
    }

    // Get the callstack, sans the last entry, which is the builtin 'rule' callable itself.
    ImmutableList<StarlarkThread.CallStackEntry> callStack = thread.getCallStack();
    callStack = callStack.subList(0, callStack.size() - 1);
    builder.setCallStack(callStack);

    ImmutableList<Pair<String, StarlarkAttrModule.Descriptor>> attributes =
        attrObjectToAttributesList(attrs);

    if (starlarkTestable) {
      builder.setStarlarkTestable();
    }
    if (Boolean.TRUE.equals(analysisTest)) {
      builder.setIsAnalysisTest();
    }

    boolean hasStarlarkDefinedTransition = false;
    boolean propagatesAspects = false;
    boolean hasMaterializers = false;
    List<String> dormantAttributes = new ArrayList<>();

    for (Pair<String, StarlarkAttrModule.Descriptor> attribute : attributes) {
      String name = attribute.getFirst();
      StarlarkAttrModule.Descriptor descriptor = attribute.getSecond();

      Attribute attr = descriptor.build(name);
      boolean isDependency = attr.getType().getLabelClass() == LabelClass.DEPENDENCY;

      if (dependencyResolutionRule && attr.isMaterializing()) {
        throw Starlark.errorf(
            "attribute '%s' has a materializer which is not allowed on rules for dependency"
                + " resolution",
            name);
      }

      if (dependencyResolutionRule && isDependency) {
        if (!attr.isForDependencyResolution() && attr.forDependencyResolutionExplicitlySet()) {
          throw Starlark.errorf(
              "attribute '%s' is explicitly marked as not for dependency"
                  + " resolution, which is disallowed on rules for dependency resolution",
              name);
        }

        attr =
            attr.cloneBuilder()
                .setPropertyFlag("FOR_DEPENDENCY_RESOLUTION")
                .nonconfigurable("On a rule used in dependency resolution")
                .build();
      }

      // "configurable" may only be user-set for symbolic macros, not rules.
      if (attr.configurableAttrWasUserSet()) {
        throw Starlark.errorf(
            "attribute '%s' has the 'configurable' argument set, which is not allowed in rule"
                + " definitions",
            name);
      }
      if (attr.skipValidations()) {
        // This is mitigation for internal Blaze builds, and not planned to be a Bazel feature,
        // and therefore has no extendable allowlists.
        if (!builder.contains("$allowlist_skip_validations")) {
          Attribute.Builder<Label> allowlistAttr =
              attr("$allowlist_skip_validations", LABEL)
                  .cfg(ExecutionTransitionFactory.createFactory())
                  .mandatoryBuiltinProviders(ImmutableList.of(PackageSpecificationProvider.class))
                  .value(
                      Label.parseCanonicalUnchecked(
                          "//tools/allowlists/skip_validations_allowlist"));
          builder.add(allowlistAttr);
          builder.addAllowlistChecker(SKIP_VALIDATIONS_ALLOWLIST_CHECKER);
        }
      }

      if (attr.getAspectsList().hasAspects()) {
        propagatesAspects = true;
      }

      hasStarlarkDefinedTransition |= attr.hasStarlarkDefinedTransition();
      if (attr.hasAnalysisTestTransition()) {
        if (!builder.isAnalysisTest()) {
          throw Starlark.errorf(
              "Only rule definitions with analysis_test=True may have attributes with"
                  + " analysis_test_transition transitions");
        }
        builder.setHasAnalysisTestTransition();
      }

      if (attr.getType() == BuildType.DORMANT_LABEL
          || attr.getType() == BuildType.DORMANT_LABEL_LIST) {
        dormantAttributes.add(name);
      }

      if (attr.isMaterializing()) {
        hasMaterializers = true;
      }

      try {
        if (builder.contains(attr.getName())) {
          builder.override(attr);
        } else {
          builder.addAttribute(attr);
        }
      } catch (IllegalStateException ex) {
        // TODO(bazel-team): stop using unchecked exceptions in this way.
        throw Starlark.errorf("cannot add attribute: %s", ex.getMessage());
      }
    }

    // the set of subrules is stored in the rule class, primarily for validating that a rule class
    // declared the subrule when using it.
    ImmutableList<StarlarkSubrule> subrules =
        Sequence.cast(subrulesUnchecked, StarlarkSubrule.class, "subrules").getImmutableList();
    builder.addToolchainTypes(StarlarkSubrule.discoverToolchains(subrules));
    builder.setSubrules(subrules);

    if (implicitOutputs != Starlark.NONE) {
      if (implicitOutputs instanceof StarlarkFunction) {
        StarlarkCallbackHelper callback =
            new StarlarkCallbackHelper((StarlarkFunction) implicitOutputs, thread.getSemantics());
        builder.setImplicitOutputsFunction(
            new StarlarkImplicitOutputsFunctionWithCallback(callback));
      } else {
        builder.setImplicitOutputsFunction(
            new StarlarkImplicitOutputsFunctionWithMap(
                ImmutableMap.copyOf(
                    Dict.cast(
                        implicitOutputs,
                        String.class,
                        String.class,
                        "implicit outputs of the rule class"))));
      }
    }

    if (outputToGenfiles) {
      builder.setOutputToGenfiles();
    }

    builder.requiresConfigurationFragmentsByStarlarkModuleName(
        Sequence.cast(fragments, String.class, "fragments"));
    builder.setConfiguredTargetFunction(implementation);

    // The rule definition's label and transitive digest typically come from the context of the .bzl
    // file being initialized.
    //
    // Note that if rule() was called via a helper function (a meta-macro), the label and digest of
    // the .bzl file of the innermost stack frame might not be the same as that of the outermost
    // frame. In this case we really do want the outermost, in order to ensure that the digest
    // includes the code that determines the helper function's argument values.
    builder.setRuleDefinitionEnvironmentLabelAndDigest(bzlFile, transitiveDigest);

    builder.addToolchainTypes(parseToolchainTypes(toolchains, labelConverter));

    if (execGroups != Starlark.NONE) {
      Map<String, ExecGroup> execGroupDict =
          Dict.cast(execGroups, String.class, ExecGroup.class, "exec_group");
      for (String group : execGroupDict.keySet()) {
        // TODO(b/151742236): document this in the param documentation.
        if (!StarlarkExecGroupCollection.isValidGroupName(group)) {
          throw Starlark.errorf("Exec group name '%s' is not a valid name.", group);
        }
      }
      builder.addExecGroups(execGroupDict);
    }
    if (test && !builder.hasExecGroup(DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME)) {
      builder.addExecGroups(
          ImmutableMap.of(DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME, DEFAULT_TEST_RUNNER_EXEC_GROUP));
    }

    if (!buildSetting.equals(Starlark.NONE) && !cfg.equals(Starlark.NONE)) {
      throw Starlark.errorf(
          "Build setting rules cannot use the `cfg` param to apply transitions to themselves.");
    }
    if (!buildSetting.equals(Starlark.NONE)) {
      builder.setBuildSetting((BuildSetting) buildSetting);
    }

    TransitionFactory<RuleTransitionData> transitionFactory = convertConfig(cfg);
    // Check if the rule definition needs to be updated.
    transitionFactory.visit(
        factory -> {
          if (factory instanceof StarlarkExposedRuleTransitionFactory exposed) {
            // only used for native Android transitions (platforms and feature flags)
            exposed.addToRuleFromStarlark(ruleDefinitionEnvironment, builder);
          }
        });
    if (parent != null) {
      transitionFactory =
          ComposingTransitionFactory.of(transitionFactory, parent.getTransitionFactory());
    }
    // Check if the transition has any Starlark code.
    StarlarkTransitionCheckingVisitor visitor = new StarlarkTransitionCheckingVisitor();
    transitionFactory.visit(visitor);
    hasStarlarkDefinedTransition |= visitor.hasStarlarkDefinedTransition;
    builder.cfg(transitionFactory);

    checkAndAddAllowlistIfNecessary(
        builder,
        ruleDefinitionEnvironment,
        dependencyResolutionRule || hasMaterializers,
        bzlFile,
        DORMANT_DEPENDENCY_ALLOWLIST_CHECKER,
        "dormant dependency",
        StarlarkRuleClassFunctions::createDormantDependencyAllowlistAttribute,
        DormantDependency.ALLOWLIST_ATTRIBUTE_NAME,
        DormantDependency.ALLOWLIST_LABEL);

    checkAndAddAllowlistIfNecessary(
        builder,
        ruleDefinitionEnvironment,
        hasStarlarkDefinedTransition,
        bzlFile,
        FUNCTION_TRANSITION_ALLOWLIST_CHECKER,
        "function-based split transition",
        StarlarkRuleClassFunctions::createStarlarkFunctionTransitionAllowlistAttribute,
        FunctionSplitTransitionAllowlist.ATTRIBUTE_NAME,
        FunctionSplitTransitionAllowlist.LABEL);

    for (Object o : providesArg) {
      if (!StarlarkAttrModule.isProvider(o)) {
        throw Starlark.errorf(
            "Illegal argument: element in 'provides' is of unexpected type. "
                + "Should be list of providers, but got item of type %s.",
            Starlark.type(o));
      }
    }

    if (dependencyResolutionRule) {
      if (!subrules.isEmpty()) {
        throw Starlark.errorf("Rules that can be required for materializers cannot have subrules");
      }

      if (!toolchains.isEmpty()) {
        throw Starlark.errorf(
            "Rules that can be required for materializers cannot depend on toolchains");
      }

      if (propagatesAspects) {
        throw Starlark.errorf(
            "Rules that can be required for materializes cannot propagate aspects");
      }
    }

    if (!dormantAttributes.isEmpty() && !dependencyResolutionRule) {
      throw Starlark.errorf(
          "Has dormant attributes (%s) but is not marked as allowed in materializers",
          dormantAttributes.stream().map(n -> "'" + n + "'").collect(Collectors.joining(", ")));
    }

    for (StarlarkProviderIdentifier starlarkProvider :
        StarlarkAttrModule.getStarlarkProviderIdentifiers(providesArg)) {
      builder.advertiseStarlarkProvider(starlarkProvider);
    }

    if (!execCompatibleWith.isEmpty()) {
      builder.addExecutionPlatformConstraints(
          parseLabels(execCompatibleWith, labelConverter, "exec_compatible_with"));
    }

    Starlark.toJavaOptional(doc, String.class)
        .map(Starlark::trimDocString)
        .ifPresent(builder::setStarlarkDocumentation);

    return new StarlarkRuleFunction(
        builder, thread.getCallerLocation(), thread.getNextIdentityToken());
  }

  private static Attribute.Builder<Label> createStarlarkFunctionTransitionAllowlistAttribute(
      RuleDefinitionEnvironment env) {
    return attr(FunctionSplitTransitionAllowlist.ATTRIBUTE_NAME, LABEL)
        .cfg(ExecutionTransitionFactory.createFactory())
        .mandatoryBuiltinProviders(ImmutableList.of(PackageSpecificationProvider.class))
        .value(env.getToolsLabel(FunctionSplitTransitionAllowlist.LABEL_STR));
  }

  private static Attribute.Builder<Label> createDormantDependencyAllowlistAttribute(
      RuleDefinitionEnvironment env) {
    try {
      return attr(DormantDependency.ALLOWLIST_ATTRIBUTE_NAME, LABEL)
          .cfg(ExecutionTransitionFactory.createFactory())
          .mandatoryBuiltinProviders(ImmutableList.of(PackageSpecificationProvider.class))
          .setPropertyFlag("FOR_DEPENDENCY_RESOLUTION")
          .setPropertyFlag("FOR_DEPENDENCY_RESOLUTION_EXPLICITLY_SET")
          .value(env.getToolsLabel(DormantDependency.ALLOWLIST_LABEL_STR));
    } catch (EvalException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void checkAndAddAllowlistIfNecessary(
      RuleClass.Builder builder,
      RuleDefinitionEnvironment ruleDefinitionEnvironment,
      boolean usesFunctionality,
      Label bzlFileLabel,
      AllowlistChecker allowlistChecker,
      String description,
      Function<RuleDefinitionEnvironment, Attribute.Builder<Label>> attributeFactory,
      String attributeName,
      Label label)
      throws EvalException {
    boolean hasAllowlist = false;
    // Check for existence of the allowlist attribute.
    if (builder.contains(attributeName)) {
      Attribute attr = builder.getAttribute(attributeName);
      if (!BuildType.isLabelType(attr.getType())) {
        throw Starlark.errorf(
            "%s attribute must be a label type", Attribute.getStarlarkName(attributeName));
      }
      if (attr.getDefaultValueUnchecked() == null) {
        throw Starlark.errorf(
            "%s attribute must have a default value", Attribute.getStarlarkName(attributeName));
      }
      Label defaultLabel = (Label) attr.getDefaultValueUnchecked();
      // Check the label value for package and target name, to make sure this works properly
      // in Bazel where it is expected to be found under @bazel_tools.
      if (!(defaultLabel.getPackageName().equals(label.getPackageName())
          && defaultLabel.getName().equals(label.getName()))) {
        throw Starlark.errorf(
            "%s attribute (%s) does not have the expected value %s",
            Attribute.getStarlarkName(attributeName), defaultLabel, label);
      }
      hasAllowlist = true;
    }
    if (usesFunctionality) {
      if (!bzlFileLabel.getRepository().getName().equals("_builtins")) {
        if (!hasAllowlist) {
          // add the allowlist automatically
          builder.add(attributeFactory.apply(ruleDefinitionEnvironment));
        }
        builder.addAllowlistChecker(allowlistChecker);
      }
    } else {
      if (hasAllowlist) {
        throw Starlark.errorf(
            "Unused %s allowlist: %s %s",
            description, builder.getRuleDefinitionEnvironmentLabel(), builder.getType());
      }
    }
  }

  private static TransitionFactory<RuleTransitionData> convertConfig(@Nullable Object cfg)
      throws EvalException {
    if (cfg.equals(Starlark.NONE)) {
      return NoTransition.getFactory();
    }
    if (cfg instanceof StarlarkDefinedConfigTransition starlarkDefinedConfigTransition) {
      // defined in Starlark via, cfg = transition
      return new StarlarkRuleTransitionProvider(starlarkDefinedConfigTransition);
    }
    if (cfg instanceof ConfigurationTransitionApi cta) {
      // Every ConfigurationTransitionApi must be a TransitionFactory instance to be usable.
      if (cta instanceof TransitionFactory<?> tf) {
        if (tf.transitionType().isCompatibleWith(TransitionType.RULE)) {
          @SuppressWarnings("unchecked")
          TransitionFactory<RuleTransitionData> ruleTransition =
              (TransitionFactory<RuleTransitionData>) tf;
          return ruleTransition;
        }
      } else {
        throw new IllegalStateException(
            "Every ConfigurationTransitionApi must be a TransitionFactory instance");
      }
    }
    throw Starlark.errorf(
        "`cfg` must be set to a transition object initialized by the transition() function.");
  }

  private static void checkAttributeName(String name) throws EvalException {
    if (!Identifier.isValid(name)) {
      throw Starlark.errorf("attribute name `%s` is not a valid identifier.", name);
    }
  }

  private static ImmutableList<Pair<String, StarlarkAttrModule.Descriptor>>
      attrObjectToAttributesList(Dict<?, ?> attrs) throws EvalException {
    ImmutableList.Builder<Pair<String, StarlarkAttrModule.Descriptor>> attributes =
        ImmutableList.builder();

    for (Map.Entry<String, Descriptor> attr :
        Dict.cast(attrs, String.class, Descriptor.class, "attrs").entrySet()) {
      Descriptor attrDescriptor = attr.getValue();
      AttributeValueSource source = attrDescriptor.getValueSource();
      checkAttributeName(attr.getKey());
      String attrName = source.convertToNativeName(attr.getKey());
      attributes.add(Pair.of(attrName, attrDescriptor));
    }
    return attributes.build();
  }

  private static ImmutableSet<Label> parseLabels(
      Sequence<?> inputs, LabelConverter labelConverter, String attributeName)
      throws EvalException {
    if (inputs.isEmpty()) {
      return ImmutableSet.of();
    }
    ImmutableSet.Builder<Label> parsedLabels = new ImmutableSet.Builder<>();
    for (String input : Sequence.cast(inputs, String.class, attributeName)) {
      try {
        Label label = labelConverter.convert(input);
        parsedLabels.add(label);
      } catch (LabelSyntaxException e) {
        throw Starlark.errorf(
            "Unable to parse label '%s' in attribute '%s': %s",
            input, attributeName, e.getMessage());
      }
    }
    return parsedLabels.build();
  }

  @Override
  public StarlarkAspect aspect(
      StarlarkFunction implementation,
      Sequence<?> attributeAspects,
      Sequence<?> rawToolchainsAspects,
      Dict<?, ?> attrs,
      Sequence<?> requiredProvidersArg,
      Sequence<?> requiredAspectProvidersArg,
      Sequence<?> providesArg,
      Sequence<?> requiredAspects,
      Sequence<?> fragments,
      Sequence<?> hostFragments,
      Sequence<?> toolchains,
      boolean useToolchainTransition,
      Object doc,
      Boolean applyToGeneratingRules,
      Sequence<?> rawExecCompatibleWith,
      Object rawExecGroups,
      Sequence<?> subrulesUnchecked,
      StarlarkThread thread)
      throws EvalException {
    LabelConverter labelConverter = LabelConverter.forBzlEvaluatingThread(thread);

    ImmutableList.Builder<String> attrAspects = ImmutableList.builder();
    for (Object attributeAspect : attributeAspects) {
      String attrName = STRING.convert(attributeAspect, "attr_aspects");

      if (attrName.equals("*") && attributeAspects.size() != 1) {
        throw new EvalException("'*' must be the only string in 'attr_aspects' list");
      }

      if (!attrName.startsWith("_")) {
        attrAspects.add(attrName);
      } else {
        // Implicit attribute names mean either implicit or late-bound attributes
        // (``$attr`` or ``:attr``). Depend on both.
        attrAspects.add(AttributeValueSource.COMPUTED_DEFAULT.convertToNativeName(attrName));
        attrAspects.add(AttributeValueSource.LATE_BOUND.convertToNativeName(attrName));
      }
    }

    ImmutableList<Pair<String, StarlarkAttrModule.Descriptor>> descriptors =
        attrObjectToAttributesList(attrs);

    if (!subrulesUnchecked.isEmpty()) {
      if (!thread.getSemantics().getBool(BuildLanguageOptions.EXPERIMENTAL_RULE_EXTENSION_API)) {
        BuiltinRestriction.failIfCalledOutsideAllowlist(thread, ALLOWLIST_RULE_EXTENSION_API);
      }
    }
    ImmutableList<StarlarkSubrule> subrules =
        Sequence.cast(subrulesUnchecked, StarlarkSubrule.class, "subrules").getImmutableList();
    ImmutableList<Pair<String, Descriptor>> subruleAttributes =
        StarlarkSubrule.discoverAttributes(subrules);
    if (!subruleAttributes.isEmpty()) {
      descriptors =
          ImmutableList.<Pair<String, Descriptor>>builder()
              .addAll(descriptors)
              .addAll(subruleAttributes)
              .build();
    }

    ImmutableList.Builder<Attribute> attributes = ImmutableList.builder();
    ImmutableSet.Builder<String> requiredParams = ImmutableSet.builder();
    for (Pair<String, Descriptor> nameDescriptorPair : descriptors) {
      String nativeName = nameDescriptorPair.first;
      boolean hasDefault = nameDescriptorPair.second.hasDefault();
      Attribute attribute = nameDescriptorPair.second.build(nameDescriptorPair.first);

      // "configurable" may only be user-set for symbolic macros, not aspects.
      if (attribute.configurableAttrWasUserSet()) {
        throw Starlark.errorf(
            "attribute '%s' has the 'configurable' argument set, which is not allowed in aspect"
                + " definitions",
            nativeName);
      }

      if (attribute.isMaterializing()) {
        throw Starlark.errorf(
            "attribute '%s' has a materializer, which is not allowed on aspects", nativeName);
      }

      if (attribute.getType() == BuildType.DORMANT_LABEL
          || attribute.getType() == BuildType.DORMANT_LABEL_LIST) {
        throw Starlark.errorf(
            "attribute '%s' has a dormant label type, which is not allowed on aspects",
            attribute.getPublicName());
      }

      if (!Attribute.isImplicit(nativeName) && !Attribute.isAnalysisDependent(nativeName)) {
        if (attribute.getType() == Type.STRING) {
          // isValueSet() is always true for attr.string as default value is "" by default.
          hasDefault = !Objects.equals(attribute.getDefaultValue(null), "");
        } else if (attribute.getType() == Type.INTEGER) {
          // isValueSet() is always true for attr.int as default value is 0 by default.
          hasDefault = !Objects.equals(attribute.getDefaultValue(null), StarlarkInt.of(0));
        } else if (attribute.getType() == Type.BOOLEAN) {
          hasDefault = !Objects.equals(attribute.getDefaultValue(null), false);
        } else {
          throw Starlark.errorf(
              "Aspect parameter attribute '%s' must have type 'bool', 'int' or 'string'.",
              nativeName);
        }

        if (hasDefault && attribute.checkAllowedValues()) {
          PredicateWithMessage<Object> allowed = attribute.getAllowedValues();
          Object defaultVal = attribute.getDefaultValue(null);
          if (!allowed.apply(defaultVal)) {
            throw Starlark.errorf(
                "Aspect parameter attribute '%s' has a bad default value: %s",
                nativeName, allowed.getErrorReason(defaultVal));
          }
        }
        if (!hasDefault || attribute.isMandatory()) {
          requiredParams.add(nativeName);
        }
      } else if (!hasDefault) { // Implicit or late bound attribute
        String starlarkName = "_" + nativeName.substring(1);
        if (attribute.isLateBound()
            && !(attribute.getLateBoundDefault() instanceof StarlarkLateBoundDefault)) {
          // Code elsewhere assumes that a late-bound attribute of a Starlark-defined aspects can
          // exist in Java-land only as a StarlarkLateBoundDefault.
          throw Starlark.errorf(
              "Starlark aspect attribute '%s' is late-bound but somehow is not defined in Starlark."
                  + " This violates an invariant inside of Bazel. Please file a bug with"
                  + " instructions for reproducing this. Thanks!",
              starlarkName);
        }
        throw Starlark.errorf("Aspect attribute '%s' has no default value.", starlarkName);
      }
      if (attribute.getDefaultValueUnchecked() instanceof StarlarkComputedDefaultTemplate) {
        // Attributes specifying dependencies using computed value are currently not supported.
        // The limitation is in place because:
        //  - blaze query requires that all possible values are knowable without BuildConguration
        //  - aspects can attach to any rule
        // Current logic in StarlarkComputedDefault is not enough,
        // however {Conservative,Precise}AspectResolver can probably be improved to make that work.
        String starlarkName = "_" + nativeName.substring(1);
        throw Starlark.errorf(
            "Aspect attribute '%s' (%s) with computed default value is unsupported.",
            starlarkName, attribute.getType());
      }
      attributes.add(attribute);
    }

    for (Object o : providesArg) {
      if (!StarlarkAttrModule.isProvider(o)) {
        throw Starlark.errorf(
            "Illegal argument: element in 'provides' is of unexpected type. "
                + "Should be list of providers, but got item of type %s. ",
            Starlark.type(o));
      }
    }

    if (applyToGeneratingRules && !requiredProvidersArg.isEmpty()) {
      throw Starlark.errorf(
          "An aspect cannot simultaneously have required providers and apply to generating rules.");
    }

    ImmutableSet<Label> execCompatibleWith =
        parseLabels(rawExecCompatibleWith, labelConverter, "exec_compatible_with");

    ImmutableSet<Label> toolchainsAspects =
        parseLabels(rawToolchainsAspects, labelConverter, "toolchains_aspects");

    ImmutableMap<String, ExecGroup> execGroups = ImmutableMap.of();
    if (rawExecGroups != Starlark.NONE) {
      execGroups =
          ImmutableMap.copyOf(
              Dict.cast(rawExecGroups, String.class, ExecGroup.class, "exec_group"));
      for (String group : execGroups.keySet()) {
        // TODO(b/151742236): document this in the param documentation.
        if (!StarlarkExecGroupCollection.isValidGroupName(group)) {
          throw Starlark.errorf("Exec group name '%s' is not a valid name.", group);
        }
      }
    }

    ImmutableSet<ToolchainTypeRequirement> toolchainTypes =
        ImmutableSet.<ToolchainTypeRequirement>builder()
            .addAll(parseToolchainTypes(toolchains, labelConverter))
            .addAll(StarlarkSubrule.discoverToolchains(subrules))
            .build();

    return new StarlarkDefinedAspect(
        implementation,
        Starlark.toJavaOptional(doc, String.class).map(Starlark::trimDocString),
        attrAspects.build(),
        toolchainsAspects,
        attributes.build(),
        StarlarkAttrModule.buildProviderPredicate(requiredProvidersArg, "required_providers"),
        StarlarkAttrModule.buildProviderPredicate(
            requiredAspectProvidersArg, "required_aspect_providers"),
        StarlarkAttrModule.getStarlarkProviderIdentifiers(providesArg),
        requiredParams.build(),
        ImmutableSet.copyOf(Sequence.cast(requiredAspects, StarlarkAspect.class, "requires")),
        ImmutableSet.copyOf(Sequence.cast(fragments, String.class, "fragments")),
        toolchainTypes,
        applyToGeneratingRules,
        execCompatibleWith,
        execGroups,
        ImmutableSet.copyOf(subrules),
        getBzlKeyToken(thread, "Aspects"));
  }

  private static ImmutableSet<String> getLegacyAnyTypeAttrs(RuleClass ruleClass) {
    Attribute attr = ruleClass.getAttributeByNameMaybe("$legacy_any_type_attrs");
    if (attr == null
        || attr.getType() != STRING_LIST
        || !(attr.getDefaultValueUnchecked() instanceof List<?>)) {
      return ImmutableSet.of();
    }
    return ImmutableSet.copyOf(STRING_LIST.cast(attr.getDefaultValueUnchecked()));
  }

  /**
   * A callable Starlark object representing a symbolic macro, which may be invoked during package
   * construction time to instantiate the macro.
   *
   * <p>Instantiating the macro does not necessarily imply that the macro's implementation function
   * will run synchronously with the call to this object. Just like a rule, a macro's implementation
   * function is evaluated in its own context separate from the caller.
   *
   * <p>This object is not usable until it has been {@link #export exported}. Calling an unexported
   * macro function results in an {@link EvalException}.
   */
  // Ideally, we'd want to merge this with {@link MacroFunctionApi}, but that would cause a circular
  // dependency between packages and starlarkbuildapi.
  public static final class MacroFunction implements StarlarkExportable, MacroFunctionApi {

    // Initially non-null, then null once exported.
    @Nullable private MacroClass.Builder builder;

    // Initially null, then non-null once exported.
    @Nullable private MacroClass macroClass = null;

    /** A token used for equality that may be mutated by {@link #export}. */
    private Symbol<BzlLoadValue.Key> identityToken;

    @Nullable private final String documentation;

    public MacroFunction(
        MacroClass.Builder builder,
        Optional<String> documentation,
        Symbol<BzlLoadValue.Key> identityToken) {
      this.builder = builder;
      this.documentation = documentation.orElse(null);
      this.identityToken = identityToken;
    }

    @Override
    public String getName() {
      return macroClass != null ? macroClass.getName() : "unexported macro";
    }

    /**
     * Returns the value of the doc parameter passed to {@code macro()} in Starlark, or an empty
     * Optional if a doc string was not provided.
     */
    public Optional<String> getDocumentation() {
      return Optional.ofNullable(documentation);
    }

    /**
     * Returns the label of the .bzl module where macro() was called, or null if the rule has not
     * been exported yet.
     */
    @Nullable
    public Label getExtensionLabel() {
      if (identityToken.isGlobal()) {
        return identityToken.getOwner().getLabel();
      }
      return null;
    }

    @Override
    public Object call(StarlarkThread thread, Tuple args, Dict<String, Object> kwargs)
        throws EvalException, InterruptedException {
      Package.Builder pkgBuilder = Package.Builder.fromOrNull(thread);
      if (pkgBuilder == null) {
        throw Starlark.errorf(
            // TODO: #19922 - Clarify error. Maybe we weren't called during .bzl loading but at some
            // other bad time. Also, it's ambiguous to the user whether, strictly speaking,
            // evaluating a symbolic macro happens while evaluating a BUILD file.
            "Cannot instantiate a macro when loading a .bzl file. "
                + "Macros may only be instantiated while evaluating a BUILD file.");
      }

      if (macroClass == null) {
        throw Starlark.errorf(
            "Cannot instantiate a macro that has not been exported (assign it to a global variable"
                + " in the .bzl where it's defined)");
      }

      if (macroClass.isFinalizer() && pkgBuilder.currentlyInNonFinalizerMacro()) {
        throw Starlark.errorf(
            "Cannot instantiate a rule finalizer within a non-finalizer symbolic macro. Rule"
                + " finalizers may only be instantiated while evaluating a BUILD file, a legacy"
                + " macro called from a BUILD file, or another rule finalizer.");
      }

      if (!args.isEmpty()) {
        throw Starlark.errorf("unexpected positional arguments");
      }

      MacroInstance macroInstance = macroClass.instantiateAndAddMacro(pkgBuilder, kwargs);

      // Evaluate the macro now, if it's not a finalizer. Finalizer evaluation will be deferred to
      // the end of the BUILD file evaluation.
      //
      // Non-finalizers must be evaluated synchronously with the call to instantiate the macro,
      // because their side-effects must be visible to native.existing_rules() calls in legacy
      // macros.
      //
      // TODO: #19922 - Once compatibility with native.existing_rules() in legacy macros is no
      // longer a concern, we can make all symbolic macros use deferred evaluation rather than
      // expanding them here. And when we have lazy evaluation, they won't even be expanded at the
      // end of BUILD file evaluation, but rather at the end of package evaluation (which at that
      // time would be a distinct skyfunction).
      if (!macroClass.isFinalizer()) {
        // TODO: #19922 - At some point we should maybe impose a check that the macro stack depth
        // isn't too big. Maybe this is unnecessary since we don't permit recursion. But in theory,
        // a big stack can crash under eager evaluation (where evaluation is on the Java call stack)
        // but not deferred evaluation, leading to a semantic difference.
        MacroClass.executeMacroImplementation(macroInstance, pkgBuilder, thread.getSemantics());
      }

      return Starlark.NONE;
    }

    /** Export a MacroFunction from a Starlark file with a given name. */
    @Override
    public void export(EventHandler handler, Label starlarkLabel, String exportedName) {
      checkState(builder != null && macroClass == null);
      builder.setName(exportedName);
      builder.setDefiningBzlLabel(starlarkLabel);
      this.macroClass = builder.build();
      this.builder = null;
      checkArgument(
          identityToken.getOwner().getLabel().equals(starlarkLabel),
          "created by %s, exporting as %s:%s",
          identityToken.getOwner(),
          starlarkLabel,
          exportedName);
      this.identityToken = identityToken.exportAs(exportedName);
    }

    /**
     * Returns an exported macro's MacroClass (representing its schema and implementation function),
     * or null if the macro has not been exported yet.
     */
    @Nullable
    public MacroClass getMacroClass() {
      return macroClass;
    }

    @Override
    public boolean isExported() {
      return macroClass != null;
    }

    @Override
    public void repr(Printer printer) {
      if (isExported()) {
        printer.append("<macro ").append(macroClass.getName()).append(">");
      } else {
        printer.append("<macro>");
      }
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof MacroFunction that) {
        return identityToken.equals(that.identityToken);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return identityToken.hashCode();
    }

    @Override
    public String toString() {
      return "macro(...)";
    }

    @Override
    public boolean isImmutable() {
      // TODO(bazel-team): This seems technically wrong, analogous to
      // StarlarkRuleFunction#isImmutable.
      return true;
    }
  }

  /**
   * A callable Starlark object representing a Starlark-defined rule, which may be invoked during
   * package construction time to instantiate the rule.
   *
   * <p>This is the object returned by calling {@code rule()}, e.g. the value that is bound in
   * {@code my_rule = rule(...)}}.
   */
  public static final class StarlarkRuleFunction implements StarlarkExportable, RuleFunction {
    // Initially non-null, then null once exported.
    @Nullable private RuleClass.Builder builder;

    // Initially null, then non-null once exported.
    @Nullable private RuleClass ruleClass;

    private final Location definitionLocation;

    /**
     * A token representing the identity of this function.
     *
     * <p>This can be either a {@link Symbol} or a {@link AnalysisTestKey}. It's a {@link Symbol} if
     * it's unexported or a normal rule and a {@link AnalysisTestKey} if it's an exported
     * analysis_test. See comments at {@link AnalysisTestKey} for more details about the special
     * case.
     *
     * <p>Mutated by {@link #export}.
     */
    private Object identityToken;

    // TODO(adonovan): merge {Starlark,Builtin}RuleFunction and RuleClass,
    // making the latter a callable, StarlarkExportable value.
    // (Making RuleClasses first-class values will help us to build a
    // rich query output mode that includes values from loaded .bzl files.)
    // [Note from brandjon: Even if we merge RuleFunction and RuleClass, it may still be useful to
    // carry a distinction between loading-time vs analysis-time information about a rule type,
    // particularly when it comes to the possibility of lazy .bzl loading. For example, you can in
    // principle evaluate a BUILD file without loading and digesting .bzls that are only used by the
    // implementation function.]
    public StarlarkRuleFunction(
        RuleClass.Builder builder, Location definitionLocation, Symbol<?> identityToken) {
      this.builder = builder;
      this.definitionLocation = definitionLocation;
      this.identityToken = identityToken;
    }

    @Override
    public String getName() {
      return ruleClass != null ? ruleClass.getName() : "unexported rule";
    }

    @Override
    public Object call(StarlarkThread thread, Tuple args, Dict<String, Object> kwargs)
        throws EvalException, InterruptedException {
      if (!args.isEmpty()) {
        throw new EvalException("Unexpected positional arguments");
      }
      if (ruleClass == null) {
        throw new EvalException("Invalid rule class hasn't been exported by a bzl file");
      }
      Package.Builder pkgBuilder = Package.Builder.fromOrNull(thread);
      if (pkgBuilder == null) {
        throw new EvalException(
            // TODO: #19922 - Clarify message. See analogous TODO for macros, above.
            "Cannot instantiate a rule when loading a .bzl file. "
                + "Rules may be instantiated only in a BUILD thread.");
      }

      validateRulePropagatedAspects(ruleClass);

      ImmutableSet<String> legacyAnyTypeAttrs = getLegacyAnyTypeAttrs(ruleClass);

      try {
        // Temporarily remove `pkgBuilder` from the thread to prevent calls to load time functions.
        // Mutating values in initializers is mostly not a problem, because the attribute values are
        // copied before calling the initializers (<-TODO) and before they are set on the target.
        // Exception is a legacy case allowing arbitrary type of parameter values. In that case the
        // values may be mutated by the initializer, but they are still copied when set on the
        // target.
        thread.setThreadLocal(StarlarkThreadContext.class, null);
        // Allow access to the LabelConverter to support native.package_relative_label() in an
        // initializer.
        thread.setThreadLocal(LabelConverter.class, pkgBuilder.getLabelConverter());
        thread.setUncheckedExceptionContext(() -> "an initializer");

        // We call all the initializers of the rule and its ancestor rules, proceeding from child to
        // ancestor, so each initializer can transform the attributes it knows about in turn.
        for (RuleClass currentRuleClass = ruleClass;
            currentRuleClass != null;
            currentRuleClass = currentRuleClass.getStarlarkParent()) {
          if (currentRuleClass.getInitializer() == null) {
            continue;
          }

          // You might feel tempted to inspect the signature of the initializer function. The
          // temptation might come from handling default values, making them work for better for the
          // users.
          // The less magic the better. Do not give in those temptations!
          Dict.Builder<String, Object> initializerKwargs = Dict.builder();
          for (var attr : currentRuleClass.getAttributes()) {
            if ((attr.isPublic() && attr.starlarkDefined()) || attr.getName().equals("name")) {
              if (kwargs.containsKey(attr.getName())) {
                Object value = kwargs.get(attr.getName());
                if (value == Starlark.NONE) {
                  continue;
                }
                Object reifiedValue =
                    legacyAnyTypeAttrs.contains(attr.getName())
                        ? value
                        : BuildType.copyAndLiftStarlarkValue(
                            currentRuleClass.getName(),
                            attr,
                            value,
                            pkgBuilder.getLabelConverter());
                initializerKwargs.put(attr.getName(), reifiedValue);
              }
            }
          }
          Object ret =
              Starlark.call(
                  thread,
                  currentRuleClass.getInitializer(),
                  Tuple.of(),
                  initializerKwargs.build(thread.mutability()));
          Dict<String, Object> newKwargs =
              ret == Starlark.NONE
                  ? Dict.empty()
                  : Dict.cast(ret, String.class, Object.class, "rule's initializer return value");

          for (var arg : newKwargs.keySet()) {
            if (arg.equals("name")) {
              if (!kwargs.get("name").equals(newKwargs.get("name"))) {
                throw Starlark.errorf("Initializer can't change the name of the target");
              }
              continue;
            }
            checkAttributeName(arg);
            if (arg.startsWith("_")) {
              // allow setting private attributes from initializers in builtins
              Label definitionLabel = currentRuleClass.getRuleDefinitionEnvironmentLabel();
              BuiltinRestriction.failIfLabelOutsideAllowlist(
                  definitionLabel,
                  RepositoryMapping.ALWAYS_FALLBACK,
                  ALLOWLIST_RULE_EXTENSION_API_EXPERIMENTAL);
            }
            String nativeName = arg.startsWith("_") ? "$" + arg.substring(1) : arg;
            Attribute attr = currentRuleClass.getAttributeByNameMaybe(nativeName);
            if (attr != null && !attr.starlarkDefined()) {
              throw Starlark.errorf(
                  "Initializer can only set Starlark defined attributes, not '%s'", arg);
            }
            Object value = newKwargs.get(arg);
            Object reifiedValue =
                attr == null
                        || value == Starlark.NONE
                        || legacyAnyTypeAttrs.contains(attr.getName())
                    ? value
                    : BuildType.copyAndLiftStarlarkValue(
                        currentRuleClass.getName(),
                        attr,
                        value,
                        // Reify to the location of the initializer definition (except for outputs)
                        attr.getType() == BuildType.OUTPUT
                                || attr.getType() == BuildType.OUTPUT_LIST
                            ? pkgBuilder.getLabelConverter()
                            : currentRuleClass.getLabelConverterForInitializer());
            kwargs.putEntry(nativeName, reifiedValue);
          }
        }
      } finally {
        thread.setThreadLocal(LabelConverter.class, null);
        pkgBuilder.storeInThread(thread);
      }

      BuildLangTypedAttributeValuesMap attributeValues =
          new BuildLangTypedAttributeValuesMap(kwargs);
      try {
        RuleFactory.createAndAddRule(
            pkgBuilder,
            ruleClass,
            attributeValues,
            thread
                .getSemantics()
                .getBool(BuildLanguageOptions.INCOMPATIBLE_FAIL_ON_UNKNOWN_ATTRIBUTES),
            thread.getCallStack());
      } catch (InvalidRuleException | NameConflictException e) {
        throw new EvalException(e);
      }
      return Starlark.NONE;
    }

    private static void validateRulePropagatedAspects(RuleClass ruleClass) throws EvalException {
      for (Attribute attribute : ruleClass.getAttributes()) {
        attribute.validateRulePropagatedAspectsParameters(ruleClass);
      }
    }

    /** Export a RuleFunction from a Starlark file with a given name. */
    @Override
    public void export(EventHandler handler, Label starlarkLabel, String ruleClassName) {
      checkState(ruleClass == null && builder != null);
      var symbolToken = (Symbol<?>) identityToken; // always a Symbol before export
      this.identityToken =
          switch (symbolToken.getOwner()) {
            case BzlLoadValue.Key bzlKey -> {
              checkArgument(
                  bzlKey.getLabel().equals(starlarkLabel),
                  "Exporting rule as (%s, %s) but doesn't match owner %s",
                  starlarkLabel,
                  ruleClassName,
                  bzlKey);
              yield symbolToken.exportAs(ruleClassName);
            }
            default -> AnalysisTestKey.create(starlarkLabel, ruleClassName);
          };
      if (builder.getType() == RuleClassType.TEST != TargetUtils.isTestRuleName(ruleClassName)) {
        errorf(
            handler,
            "Invalid rule class name '%s', test rule class names must end with '_test' and other"
                + " rule classes must not",
            ruleClassName);
        return;
      }

      // lift the subrule attributes to the rule class as if they were declared there, this lets us
      // exploit dependency resolution for "free"
      ImmutableList<Pair<String, Descriptor>> subruleAttributes;
      try {
        var parentSubrules = builder.getParentSubrules();
        ImmutableList<StarlarkSubruleApi> subrulesNotInParents =
            builder.getSubrules().stream()
                .filter(subrule -> !parentSubrules.contains(subrule))
                .collect(toImmutableList());
        subruleAttributes = StarlarkSubrule.discoverAttributes(subrulesNotInParents);
      } catch (EvalException e) {
        errorf(handler, "%s", e.getMessage());
        return;
      }
      for (Pair<String, StarlarkAttrModule.Descriptor> attribute : subruleAttributes) {
        String name = attribute.getFirst();
        StarlarkAttrModule.Descriptor descriptor = attribute.getSecond();

        Attribute attr = descriptor.build(name);

        try {
          builder.addAttribute(attr);
        } catch (IllegalStateException ex) {
          // TODO(bazel-team): stop using unchecked exceptions in this way.
          errorf(handler, "cannot add attribute: %s", ex.getMessage());
        }
      }

      try {
        this.ruleClass = builder.buildStarlark(ruleClassName, starlarkLabel);
      } catch (IllegalArgumentException | IllegalStateException ex) {
        // TODO(adonovan): this catch statement is an abuse of exceptions. Be more specific.
        String msg = ex.getMessage();
        errorf(handler, "%s", msg != null ? msg : ex.toString());
      }

      this.builder = null;
    }

    @FormatMethod
    private void errorf(EventHandler handler, String format, Object... args) {
      handler.handle(Event.error(definitionLocation, String.format(format, args)));
    }

    @Override
    public RuleClass getRuleClass() {
      checkState(ruleClass != null && builder == null);
      return ruleClass;
    }

    @Override
    public boolean isExported() {
      if (identityToken instanceof Symbol<?> symbol) {
        return symbol.isGlobal();
      }
      return true; // it's an AnalysisTestKey
    }

    @Override
    public void repr(Printer printer) {
      if (isExported()) {
        printer.append("<rule ").append(getRuleClass().getName()).append(">");
      } else {
        printer.append("<rule>");
      }
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof StarlarkRuleFunction that) {
        return identityToken.equals(that.identityToken);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return identityToken.hashCode();
    }

    @Override
    public String toString() {
      return "rule(...)";
    }

    @Override
    public boolean isImmutable() {
      // TODO(bazel-team): It shouldn't be immutable until it's exported, no?
      return true;
    }
  }

  /**
   * Special case exported {@link StarlarkRuleFunction#identityToken} for analysis_test.
   *
   * <p>{@link com.google.devtools.build.lib.rules.test.StarlarkTestingModule#analysisTest} is a
   * special case where a rule is instantiated in a BUILD file instead of a .bzl file.
   *
   * @param label Label of the BUILD file exporting the analysis_test.
   */
  record AnalysisTestKey(Label label, String name) {
    AnalysisTestKey {
      requireNonNull(label, "label");
      requireNonNull(name, "name");
    }

    private static AnalysisTestKey create(Label label, String name) {
      return new AnalysisTestKey(label, name);
    }
  }

  @SerializationConstant
  static final AllowlistChecker FUNCTION_TRANSITION_ALLOWLIST_CHECKER =
      AllowlistChecker.builder()
          .setAllowlistAttr(FunctionSplitTransitionAllowlist.NAME)
          .setErrorMessage("Non-allowlisted use of Starlark transition")
          .setLocationCheck(AllowlistChecker.LocationCheck.INSTANCE_OR_DEFINITION)
          .build();

  @SerializationConstant
  static final AllowlistChecker DORMANT_DEPENDENCY_ALLOWLIST_CHECKER =
      AllowlistChecker.builder()
          .setAllowlistAttr(DormantDependency.NAME)
          .setErrorMessage("Non-allowlisted use of dormant dependencies")
          .setLocationCheck(AllowlistChecker.LocationCheck.DEFINITION)
          .build();

  @SerializationConstant
  static final AllowlistChecker EXTEND_RULE_ALLOWLIST_CHECKER =
      AllowlistChecker.builder()
          .setAllowlistAttr("extend_rule")
          .setErrorMessage("Non-allowlisted attempt to extend a rule.")
          .setLocationCheck(AllowlistChecker.LocationCheck.DEFINITION)
          .build();

  @SerializationConstant
  static final AllowlistChecker EXTEND_RULE_API_ALLOWLIST_CHECKER =
      AllowlistChecker.builder()
          .setAllowlistAttr("extend_rule_api")
          .setErrorMessage("Non-allowlisted attempt to use extend rule APIs.")
          .setLocationCheck(AllowlistChecker.LocationCheck.DEFINITION)
          .build();

  @SerializationConstant
  static final AllowlistChecker INITIALIZER_ALLOWLIST_CHECKER =
      AllowlistChecker.builder()
          .setAllowlistAttr("initializer")
          .setErrorMessage("Non-allowlisted attempt to use initializer.")
          .setLocationCheck(AllowlistChecker.LocationCheck.DEFINITION)
          .build();

  @SerializationConstant
  static final AllowlistChecker SUBRULES_ALLOWLIST_CHECKER =
      AllowlistChecker.builder()
          .setAllowlistAttr("subrules")
          .setErrorMessage("Non-allowlisted attempt to use subrules.")
          .setLocationCheck(AllowlistChecker.LocationCheck.DEFINITION)
          .build();

  @SerializationConstant
  static final AllowlistChecker SKIP_VALIDATIONS_ALLOWLIST_CHECKER =
      AllowlistChecker.builder()
          .setAllowlistAttr("skip_validations")
          .setErrorMessage("Non-allowlisted use of skip_validations")
          .setLocationCheck(LocationCheck.DEFINITION)
          .build();

  @Override
  public Label label(Object input, StarlarkThread thread) throws EvalException {
    if (input instanceof Label) {
      return (Label) input;
    }
    // The label string is interpreted with respect to the .bzl module containing the call to
    // `Label()`. An alternative to this approach that avoids stack inspection is to have each .bzl
    // module define its own copy of the `Label()` builtin embedding the module's own name. This
    // would lead to peculiarities like foo.bzl being able to call bar.bzl's `Label()` symbol to
    // resolve strings as if it were bar.bzl. It also would prevent sharing the same builtins
    // environment across .bzl files. Hence, we opt for stack inspection.
    BazelModuleContext moduleContext = BazelModuleContext.ofInnermostBzlOrFail(thread, "Label()");
    try {
      return Label.parseWithPackageContext(
          (String) input,
          moduleContext.packageContext(),
          thread.getThreadLocal(Label.RepoMappingRecorder.class));
    } catch (LabelSyntaxException e) {
      throw Starlark.errorf("invalid label in Label(): %s", e.getMessage());
    }
  }

  @Override
  public ExecGroup execGroup(
      Sequence<?> toolchains, Sequence<?> execCompatibleWith, StarlarkThread thread)
      throws EvalException {
    LabelConverter labelConverter = LabelConverter.forBzlEvaluatingThread(thread);
    ImmutableSet<ToolchainTypeRequirement> toolchainTypes =
        parseToolchainTypes(toolchains, labelConverter);
    ImmutableSet<Label> constraints =
        parseLabels(execCompatibleWith, labelConverter, "exec_compatible_with");
    return ExecGroup.builder()
        .toolchainTypes(toolchainTypes)
        .execCompatibleWith(constraints)
        .build();
  }

  @Override
  public StarlarkSubruleApi subrule(
      StarlarkFunction implementation,
      Dict<?, ?> attrsUnchecked,
      Sequence<?> toolchainsUnchecked,
      Sequence<?> fragmentsUnchecked,
      Sequence<?> subrulesUnchecked,
      StarlarkThread thread)
      throws EvalException {
    ImmutableMap<String, Descriptor> attrs =
        ImmutableMap.copyOf(Dict.cast(attrsUnchecked, String.class, Descriptor.class, "attrs"));
    ImmutableList<String> fragments =
        Sequence.noneableCast(fragmentsUnchecked, String.class, "fragments").getImmutableList();
    for (Entry<String, Descriptor> attr : attrs.entrySet()) {
      String attrName = attr.getKey();
      Descriptor descriptor = attr.getValue();
      TransitionFactory<AttributeTransitionData> transitionFactory =
          descriptor.getTransitionFactory();
      if (!NoTransition.isInstance(transitionFactory) && !transitionFactory.isTool()) {
        throw Starlark.errorf(
            "bad cfg for attribute '%s': subrules may only have target/exec attributes.", attrName);
      }
      checkAttributeName(attrName);
      Type<?> type = descriptor.getType();
      if (!attrName.startsWith("_")) {
        throw Starlark.errorf(
            "illegal attribute name '%s': subrules may only define private attributes (whose names"
                + " begin with '_').",
            attrName);
      } else if (descriptor.getValueSource() == AttributeValueSource.COMPUTED_DEFAULT) {
        throw Starlark.errorf(
            "illegal default value for attribute '%s': subrules cannot define computed defaults.",
            attrName);
      } else if (!descriptor.hasDefault()) {
        throw Starlark.errorf("for attribute '%s': no default value specified", attrName);
      } else if (type != LABEL && type != LABEL_LIST) {
        throw Starlark.errorf(
            "bad type for attribute '%s': subrule attributes may only be label or lists of labels.",
            attrName);
      }
    }
    ImmutableSet<ToolchainTypeRequirement> toolchains =
        parseToolchainTypes(toolchainsUnchecked, LabelConverter.forBzlEvaluatingThread(thread));
    if (toolchains.size() > 1) {
      throw Starlark.errorf("subrules may require at most 1 toolchain, got: %s", toolchains);
    }
    return new StarlarkSubrule(
        implementation,
        attrs,
        toolchains,
        ImmutableSet.copyOf(fragments),
        ImmutableSet.copyOf(Sequence.cast(subrulesUnchecked, StarlarkSubrule.class, "subrules")));
  }

  private static ImmutableSet<ToolchainTypeRequirement> parseToolchainTypes(
      Sequence<?> rawToolchains, LabelConverter labelConverter) throws EvalException {
    Map<Label, ToolchainTypeRequirement> toolchainTypes = new LinkedHashMap<>();

    for (Object rawToolchain : rawToolchains) {
      ToolchainTypeRequirement toolchainType = parseToolchainType(rawToolchain, labelConverter);
      Label typeLabel = toolchainType.toolchainType();
      ToolchainTypeRequirement previous = toolchainTypes.get(typeLabel);
      if (previous != null) {
        // Keep the one with the strictest requirements.
        toolchainType = ToolchainTypeRequirement.strictest(previous, toolchainType);
      }
      toolchainTypes.put(typeLabel, toolchainType);
    }

    return ImmutableSet.copyOf(toolchainTypes.values());
  }

  private static ToolchainTypeRequirement parseToolchainType(
      Object rawToolchain, LabelConverter labelConverter) throws EvalException {
    // Handle actual ToolchainTypeRequirement objects.
    if (rawToolchain instanceof ToolchainTypeRequirement) {
      return (ToolchainTypeRequirement) rawToolchain;
    }

    // Handle Label-like objects.
    Label toolchainLabel = null;
    if (rawToolchain instanceof Label) {
      toolchainLabel = (Label) rawToolchain;
    } else if (rawToolchain instanceof String) {
      try {
        toolchainLabel = labelConverter.convert((String) rawToolchain);
      } catch (LabelSyntaxException e) {
        throw Starlark.errorf(
            "Unable to parse toolchain_type label '%s': %s", rawToolchain, e.getMessage());
      }
    }

    if (toolchainLabel != null) {
      return ToolchainTypeRequirement.builder(toolchainLabel).mandatory(true).build();
    }

    // It's not a valid type.
    throw Starlark.errorf(
        "'toolchains' takes a toolchain_type, Label, or String, but instead got a %s",
        rawToolchain.getClass().getSimpleName());
  }

  /** Visitor to check whether a transition has any Starlark components. */
  private static class StarlarkTransitionCheckingVisitor implements Visitor<RuleTransitionData> {

    private boolean hasStarlarkDefinedTransition = false;

    @Override
    public void visit(TransitionFactory<RuleTransitionData> factory) {
      this.hasStarlarkDefinedTransition |= factory instanceof StarlarkRuleTransitionProvider;
    }
  }

  @Keep // used reflectively
  private static class Codec extends AbstractExportedStarlarkSymbolCodec<StarlarkRuleFunction> {

    @Override
    public Class<StarlarkRuleFunction> getEncodedClass() {
      return StarlarkRuleFunction.class;
    }

    @Override
    protected BzlLoadValue.Key getBzlLoadKey(StarlarkRuleFunction obj) {
      // TODO: b/326588519 - this does not support AnalysisTestKey but that type does not seem to
      // appear in action lookup values. Make this more robust if necessary.
      var symbol = (GlobalSymbol<?>) obj.identityToken;
      return (BzlLoadValue.Key) symbol.getOwner();
    }

    @Override
    protected String getExportedName(StarlarkRuleFunction obj) {
      return ((GlobalSymbol<?>) obj.identityToken).getName();
    }
  }
}
