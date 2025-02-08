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
package com.google.devtools.build.lib.starlarkdocextract;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.Types.STRING_LIST;

import com.google.devtools.build.lib.packages.BuildTypeTestHelper;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.util.PackageLoadingTestCase;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.AttributeInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.AttributeType;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.OriginKey;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.RuleInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class RuleInfoExtractorTest extends PackageLoadingTestCase {

  private static final RuleClass.ConfiguredTargetFactory<Object, Object, Exception>
      DUMMY_CONFIGURED_TARGET_FACTORY =
          new RuleClass.ConfiguredTargetFactory<Object, Object, Exception>() {
            @Override
            public Object create(Object ruleContext) {
              throw new IllegalStateException();
            }
          };

  @Test
  public void basicFunctionality() throws Exception {
    RuleClass ruleClass =
        new RuleClass.Builder("test_rule", RuleClass.Builder.RuleClassType.NORMAL, false)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .add(attr("tags", STRING_LIST))
            .build();
    ExtractorContext extractorContext =
        ExtractorContext.builder()
            .labelRenderer(LabelRenderer.DEFAULT)
            .extractNativelyDefinedAttrs(true)
            .build();
    RuleInfo ruleInfo =
        RuleInfoExtractor.buildRuleInfo(extractorContext, "namespace.test_rule", ruleClass);
    assertThat(ruleInfo)
        .isEqualTo(
            RuleInfo.newBuilder()
                .setRuleName("namespace.test_rule")
                .setOriginKey(OriginKey.newBuilder().setName("test_rule").setFile("<native>"))
                .addAllAttribute(RuleInfoExtractor.IMPLICIT_RULE_ATTRIBUTES.values())
                .addAttribute(
                    AttributeInfo.newBuilder()
                        .setName("tags")
                        .setType(AttributeType.STRING_LIST)
                        .setDefaultValue("[]")
                        .setMandatory(false)
                        .setNativelyDefined(true)
                        .build())
                .build());
  }

  @Test
  public void allBuiltinAttributeTypesSupported() throws Exception {
    ExtractorContext context =
        ExtractorContext.builder().labelRenderer(LabelRenderer.DEFAULT).build();
    for (Type<?> type : BuildTypeTestHelper.getAllBuildTypes(/* publicOnly= */ true)) {
      assertWithMessage("attr type '%s'", type)
          .that(AttributeInfoExtractor.getAttributeType(context, type, "test_attr"))
          .isNotEqualTo(AttributeType.UNKNOWN);
    }
  }

  @Test
  public void allNativeRulesAreSupported() throws Exception {
    ExtractorContext extractorContext =
        ExtractorContext.builder()
            .labelRenderer(LabelRenderer.DEFAULT)
            .extractNativelyDefinedAttrs(true)
            .build();
    for (RuleClass ruleClass : ruleClassProvider.getRuleClassMap().values()) {
      RuleInfo ruleInfo =
          RuleInfoExtractor.buildRuleInfo(extractorContext, ruleClass.getName(), ruleClass);
      assertThat(ruleInfo.getRuleName()).isEqualTo(ruleClass.getName());
      assertThat(ruleInfo.getOriginKey().getName()).isEqualTo(ruleClass.getName());
      assertWithMessage("rule '%s'", ruleClass.getName())
          .that(ruleInfo.getOriginKey().getFile())
          .isEqualTo("<native>");
      assertWithMessage("rule '%s'", ruleClass.getName())
          .that(ruleInfo.getAttributeList().getFirst())
          .isEqualTo(RuleInfoExtractor.IMPLICIT_RULE_ATTRIBUTES.get("name"));
      assertWithMessage("rule '%s'", ruleClass.getName())
          .that(ruleInfo.getAttributeList().stream().map(AttributeInfo::getName))
          .containsNoDuplicates();
      assertWithMessage("rule '%s'", ruleClass.getName())
          .that(ruleInfo.getAttributeList().stream().map(AttributeInfo::getDefaultValue))
          .doesNotContain(AttributeInfoExtractor.UNREPRESENTABLE_VALUE);
    }
  }
}
