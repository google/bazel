// Copyright 2020 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.runtime.commands.info;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.StarlarkComputedDefaultTemplate;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.ProtoUtils;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClassUtils;
import com.google.devtools.build.lib.packages.TriState;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.query2.proto.proto2api.Build.AllowedRuleClassInfo;
import com.google.devtools.build.lib.query2.proto.proto2api.Build.AttributeDefinition;
import com.google.devtools.build.lib.query2.proto.proto2api.Build.AttributeValue;
import com.google.devtools.build.lib.query2.proto.proto2api.Build.BuildLanguage;
import com.google.devtools.build.lib.query2.proto.proto2api.Build.RuleDefinition;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.InfoItem;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.skyframe.StarlarkBuiltinsValue;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.starlark.java.eval.StarlarkInt;

/**
 * Info item for the build language. It is deprecated, it still works, when explicitly requested,
 * but are not shown by default. It prints multi-line messages and thus don't play well with grep.
 * We don't print them unless explicitly requested.
 */
@Deprecated
public final class BuildLanguageInfoItem extends InfoItem {

  public BuildLanguageInfoItem() {
    super("build-language", "A protobuffer with the build language structure", true);
  }

  @Override
  public boolean needsSyncPackageLoading() {
    // Requires CommandEnvironment.syncPackageLoading to be called in order to initialize the
    // skyframe executor.
    return true;
  }

  @Override
  public byte[] get(Supplier<BuildConfigurationValue> configurationSupplier, CommandEnvironment env)
      throws AbruptExitException {
    checkNotNull(env);
    StarlarkBuiltinsValue builtins = loadStarlarkBuiltins(env);
    return print(
        getBuildLanguageDefinition(
            RuleClassUtils.getBuiltinRuleClasses(
                builtins,
                env.getRuntime().getRuleClassProvider(),
                /* includeMacroWrappedRules= */ true)));
  }

  private StarlarkBuiltinsValue loadStarlarkBuiltins(CommandEnvironment env)
      throws AbruptExitException {
    EvaluationResult<SkyValue> result =
        env.getSkyframeExecutor()
            .evaluateSkyKeys(
                env.getReporter(),
                ImmutableList.of(StarlarkBuiltinsValue.key()),
                /* keepGoing= */ false);
    if (result.hasError()) {
      throw new AbruptExitException(
          DetailedExitCode.of(
              FailureDetails.FailureDetail.newBuilder()
                  .setMessage("Failed to load Starlark builtins")
                  .setInfoCommand(FailureDetails.InfoCommand.getDefaultInstance())
                  .build()));
    }
    return (StarlarkBuiltinsValue) result.get(StarlarkBuiltinsValue.key());
  }

  /**
   * Returns a byte array containing a proto-buffer describing the build language.
   *
   * @param ruleClasses a sorted list of rule classes
   */
  private static byte[] getBuildLanguageDefinition(ImmutableList<RuleClass> ruleClasses) {
    BuildLanguage.Builder resultPb = BuildLanguage.newBuilder();
    for (RuleClass ruleClass : ruleClasses) {
      if (isAbstractRule(ruleClass)) {
        continue;
      }

      RuleDefinition.Builder rulePb = RuleDefinition.newBuilder();
      rulePb.setName(ruleClass.getName());

      ImmutableList<Attribute> sortedAttributeDefinitions =
          ImmutableList.sortedCopyOf(
              Comparator.comparing(Attribute::getName), ruleClass.getAttributes());
      for (Attribute attr : sortedAttributeDefinitions) {
        Type<?> t = attr.getType();
        AttributeDefinition.Builder attrPb = AttributeDefinition.newBuilder();
        attrPb.setName(attr.getName());
        attrPb.setType(ProtoUtils.getDiscriminatorFromType(t));
        attrPb.setMandatory(attr.isMandatory());
        attrPb.setAllowEmpty(!attr.isNonEmpty());
        attrPb.setAllowSingleFile(attr.isSingleArtifact());
        attrPb.setConfigurable(attr.isConfigurable());

        // Encode default value, if simple.
        Object v = attr.getDefaultValueUnchecked();
        if (!(v == null
            || v instanceof Attribute.ComputedDefault
            || v instanceof StarlarkComputedDefaultTemplate
            || v instanceof Attribute.LateBoundDefault
            || v == t.getDefaultValue())) {
          attrPb.setDefault(convertAttrValue(t, v));
        }
        attrPb.setExecutable(attr.isExecutable());
        if (BuildType.isLabelType(t)) {
          attrPb.setAllowedRuleClasses(getAllowedRuleClasses(ruleClasses, attr));
          attrPb.setNodep(t.getLabelClass() == Type.LabelClass.NONDEP_REFERENCE);
        }
        rulePb.addAttribute(attrPb);
      }

      resultPb.addRule(rulePb);
    }

    return resultPb.build().toByteArray();
  }

  // convertAttrValue converts attribute value v of type to t an AttributeValue message.
  private static AttributeValue convertAttrValue(Type<?> t, Object v) {
    AttributeValue.Builder b = AttributeValue.newBuilder();
    if (v instanceof Map) {
      Type.DictType<?, ?> dictType = (Type.DictType<?, ?>) t;
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) v).entrySet()) {
        b.addDictBuilder()
            .setKey(entry.getKey().toString())
            .setValue(convertAttrValue(dictType.getValueType(), entry.getValue()));
      }
    } else if (v instanceof List) {
      for (Object elem : (List<?>) v) {
        b.addList(convertAttrValue(t.getListElementType(), elem));
      }
    } else if (t == BuildType.LICENSE) {
      // TODO(adonovan): need dual function of parseLicense.
      // Treat as empty list for now.
    } else if (t == BuildType.DISTRIBUTIONS) {
      // TODO(adonovan): need dual function of parseDistributions.
      // Treat as empty list for now.
    } else if (t == Type.STRING) {
      b.setString((String) v);
    } else if (t == Type.INTEGER) {
      b.setInt(((StarlarkInt) v).toIntUnchecked());
    } else if (t == Type.BOOLEAN) {
      b.setBool((Boolean) v);
    } else if (t == BuildType.TRISTATE) {
      b.setInt(((TriState) v).toInt());
    } else if (BuildType.isLabelType(t)) { // case order matters!
      b.setString(v.toString());
    } else {
      // No native rule attribute of this type (FilesetEntry?) has a default value.
      throw new IllegalStateException("unexpected type of attribute default value: " + t);
    }
    return b.build();
  }

  private static AllowedRuleClassInfo getAllowedRuleClasses(
      Collection<RuleClass> ruleClasses, Attribute attr) {
    AllowedRuleClassInfo.Builder info = AllowedRuleClassInfo.newBuilder();
    info.setPolicy(AllowedRuleClassInfo.AllowedRuleClasses.ANY);

    Predicate<RuleClass> filter;
    if (attr.isStrictLabelCheckingEnabled()
        && (filter = attr.getAllowedRuleClassObjectPredicate())
            != Predicates.<RuleClass>alwaysTrue()) {
      info.setPolicy(AllowedRuleClassInfo.AllowedRuleClasses.SPECIFIED);
      for (RuleClass otherClass : Iterables.filter(ruleClasses, filter)) {
        if (!isAbstractRule(otherClass)) {
          info.addAllowedRuleClass(otherClass.getName());
        }
      }
    }

    return info.build();
  }

  private static boolean isAbstractRule(RuleClass c) {
    return c.getName().startsWith("$");
  }
}
