// Copyright 2023 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.docgen;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.devtools.build.docgen.BuildDocCollector.DocumentationOrigin;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.AttributeInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.AttributeType;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.ModuleInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.OriginKey;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.RuleInfo;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for BuildDocCollector. */
@RunWith(JUnit4.class)
public final class BuildDocCollectorTest {

  // The following are initialized by setUpCollectorState to simplify boilerplate.
  Map<String, DocumentationOrigin> ruleDocOrigin;
  Map<String, RuleDocumentation> ruleDocEntries;
  ListMultimap<String, RuleDocumentationAttribute> attributeDocEntries;
  SourceUrlMapper urlMapper =
      new SourceUrlMapper(
          /* sourceUrlRoot= */ "https://example.com/",
          /* inputRoot= */ "/tmp/io_bazel/",
          ImmutableMap.of(
              "@//", "https://main-repo.com/",
              "@_builtins//", "https://example.com/src/main/starlark/builtins_bzl/"));

  @Before
  public void setUpCollectorState() {
    ruleDocOrigin = new HashMap<>();
    ruleDocEntries = new HashMap<>();
    attributeDocEntries = LinkedListMultimap.create();
  }

  private int collectModuleInfoDocs(ModuleInfo moduleInfo) throws Exception {
    return BuildDocCollector.collectModuleInfoDocs(
        ruleDocOrigin,
        ruleDocEntries,
        ImmutableSet.of(),
        attributeDocEntries,
        moduleInfo,
        urlMapper);
  }

  private int collectModuleInfoDocsWithDenyList(ModuleInfo moduleInfo, Set<String> denyList)
      throws Exception {
    return BuildDocCollector.collectModuleInfoDocs(
        ruleDocOrigin, ruleDocEntries, denyList, attributeDocEntries, moduleInfo, urlMapper);
  }

  @Nullable
  private RuleDocumentationAttribute getAttribute(
      Set<RuleDocumentationAttribute> attributes, String name) {
    for (RuleDocumentationAttribute attribute : attributes) {
      if (attribute.getAttributeName().equals(name)) {
        return attribute;
      }
    }
    return null;
  }

  @Test
  public void collectModuleInfoDocs_basicFunctionality() throws Exception {
    ModuleInfo moduleInfo =
        ModuleInfo.newBuilder()
            .setModuleDocstring("My Language")
            .setFile("//:test.bzl")
            .addRuleInfo(
                RuleInfo.newBuilder()
                    .setRuleName("binary_rules.my_binary")
                    .setDocString("My language binary")
                    .setExecutable(true)
                    .setOriginKey(
                        OriginKey.newBuilder()
                            .setName("_my_binary")
                            .setFile("@_builtins//:my_lang/my_binary.bzl"))
                    .addAttribute(
                        // starlark_doc_extract always injects the implicit "name" attribute
                        AttributeInfo.newBuilder()
                            .setName("name")
                            .setType(AttributeType.NAME)
                            .setMandatory(true)
                            .setDocString("A unique name for this target.")
                            .build())
                    .addAttribute(
                        AttributeInfo.newBuilder()
                            .setName("srcs")
                            .setDocString("My sources")
                            .setType(AttributeType.LABEL_LIST)
                            .setMandatory(true))
                    .addAttribute(
                        AttributeInfo.newBuilder()
                            .setName("deps")
                            .setDocString("My deps")
                            .setType(AttributeType.LABEL_LIST)
                            .setDefaultValue("[]"))
                    .addAttribute(
                        AttributeInfo.newBuilder()
                            .setName("old")
                            .setDocString("Deprecated: do not use")
                            .setType(AttributeType.STRING)
                            .setDefaultValue("\"???\"")))
            .build();

    assertThat(collectModuleInfoDocs(moduleInfo)).isEqualTo(1);

    assertThat(ruleDocEntries.keySet()).containsExactly("my_binary");

    RuleDocumentation ruleDoc = ruleDocEntries.get("my_binary");
    assertThat(ruleDoc.getRuleName()).isEqualTo("my_binary");
    assertThat(ruleDoc.getRuleType()).isEqualTo(DocgenConsts.RuleType.BINARY);
    assertThat(ruleDoc.getRuleFamily()).isEqualTo("My Language");
    assertThat(ruleDoc.getFamilySummary()).isEmpty();
    assertThat(ruleDoc.getSourceUrl())
        .isEqualTo("https://example.com/src/main/starlark/builtins_bzl/my_lang/my_binary.bzl");
    assertThat(ruleDoc.getHtmlDocumentation()).isEqualTo("My language binary");
    Set<RuleDocumentationAttribute> attributes = ruleDoc.getAttributes();
    assertThat(attributes)
        .containsAtLeastElementsIn(PredefinedAttributes.COMMON_ATTRIBUTES.values());
    assertThat(attributes)
        .containsAtLeastElementsIn(PredefinedAttributes.BINARY_ATTRIBUTES.values());
    assertThat(
            attributes.stream()
                .map(RuleDocumentationAttribute::getAttributeName)
                .filter(
                    attr ->
                        !(PredefinedAttributes.COMMON_ATTRIBUTES.containsKey(attr)
                            || PredefinedAttributes.BINARY_ATTRIBUTES.containsKey(attr))))
        // We do not want the implicit "name" attribute - we inject "name" at template level
        .containsExactly("srcs", "deps", "old");

    RuleDocumentationAttribute srcsAttribute = getAttribute(attributes, "srcs");
    assertThat(srcsAttribute.getAttributeName()).isEqualTo("srcs");
    assertThat(srcsAttribute.getHtmlDocumentation()).isEqualTo("My sources");
    assertThat(srcsAttribute.getSynopsis())
        .isEqualTo("List of <a href=\"${link build-ref#labels}\">labels</a>; required");

    RuleDocumentationAttribute depsAttribute = getAttribute(attributes, "deps");
    assertThat(depsAttribute.getAttributeName()).isEqualTo("deps");
    assertThat(depsAttribute.getHtmlDocumentation()).isEqualTo("My deps");
    assertThat(depsAttribute.getSynopsis())
        .isEqualTo(
            "List of <a href=\"${link build-ref#labels}\">labels</a>; default is <code>[]</code>");

    RuleDocumentationAttribute oldAttribute = getAttribute(attributes, "old");
    assertThat(oldAttribute.getAttributeName()).isEqualTo("old");
    assertThat(oldAttribute.isDeprecated()).isTrue();
    assertThat(oldAttribute.getSynopsis()).isEqualTo("String; default is <code>\"???\"</code>");

    assertThat(ruleDocOrigin)
        .containsExactly(
            "my_binary", DocumentationOrigin.create("//:test.bzl", "binary_rules.my_binary"));
  }

  @Test
  public void collectModuleInfoDocs_respectsDenyList() throws Exception {
    ModuleInfo moduleInfo =
        ModuleInfo.newBuilder()
            .setModuleDocstring("My Language")
            .setFile("//:test.bzl")
            .addRuleInfo(
                RuleInfo.newBuilder()
                    .setRuleName("library_rules.my_library")
                    .setDocString("My language library")
                    .setOriginKey(
                        OriginKey.newBuilder().setName("_my_library").setFile("//:my_library.bzl")))
            .addRuleInfo(
                RuleInfo.newBuilder()
                    .setRuleName("library_rules.my_plugin")
                    .setDocString("My language plugin")
                    .setOriginKey(
                        OriginKey.newBuilder().setName("_my_plugin").setFile("//:my_plugin.bzl")))
            .addRuleInfo(
                RuleInfo.newBuilder()
                    .setRuleName("library_rules.my_import")
                    .setDocString("My language import")
                    .setOriginKey(
                        OriginKey.newBuilder().setName("_my_import").setFile("//:my_import.bzl")))
            .build();

    assertThat(collectModuleInfoDocsWithDenyList(moduleInfo, ImmutableSet.of("my_library")))
        .isEqualTo(2);

    assertThat(ruleDocEntries.keySet()).containsExactly("my_plugin", "my_import");
  }

  @Test
  public void collectModuleInfoDocs_expectsNonemptyModuleDocstring() throws Exception {
    BuildEncyclopediaDocException e =
        assertThrows(
            BuildEncyclopediaDocException.class,
            () -> collectModuleInfoDocs(ModuleInfo.newBuilder().setModuleDocstring("").build()));
    assertThat(e)
        .hasMessageThat()
        .contains("expected to be a single line representing a rule family name");
  }

  @Test
  public void collectModuleInfoDocs_multilineModuleDocstring() throws Exception {
    ModuleInfo moduleInfo =
        ModuleInfo.newBuilder()
            .setModuleDocstring("My Language\n\nBlah blah blah")
            .setFile("//:test.bzl")
            .addRuleInfo(
                RuleInfo.newBuilder()
                    .setRuleName("library_rules.my_library")
                    .setDocString("My language library")
                    .setOriginKey(
                        OriginKey.newBuilder().setName("_my_library").setFile("//:my_library.bzl")))
            .addRuleInfo(
                RuleInfo.newBuilder()
                    .setRuleName("library_rules.my_plugin")
                    .setDocString("My language plugin")
                    .setOriginKey(
                        OriginKey.newBuilder().setName("_my_plugin").setFile("//:my_plugin.bzl")))
            .addRuleInfo(
                RuleInfo.newBuilder()
                    .setRuleName("test_rules.my_test")
                    .setDocString("My language test")
                    .setOriginKey(
                        OriginKey.newBuilder().setName("_my_test").setFile("//:my_test.bzl")))
            .build();

    assertThat(collectModuleInfoDocs(moduleInfo)).isEqualTo(3);

    // We expect family summary to be set only on the first rule, to avoid duplication in final
    // rendered output.
    assertThat(ruleDocEntries.get("my_library").getRuleFamily()).isEqualTo("My Language");
    assertThat(ruleDocEntries.get("my_library").getFamilySummary()).isEqualTo("Blah blah blah");
    assertThat(ruleDocEntries.get("my_plugin").getRuleFamily()).isEqualTo("My Language");
    assertThat(ruleDocEntries.get("my_plugin").getFamilySummary()).isEmpty();
    assertThat(ruleDocEntries.get("my_test").getRuleFamily()).isEqualTo("My Language");
    assertThat(ruleDocEntries.get("my_test").getFamilySummary()).isEmpty();
  }

  @Test
  public void collectModuleInfoDocs_linksCommonAttrsWithEmptyDocstringToCommonType()
      throws Exception {
    ModuleInfo moduleInfo =
        ModuleInfo.newBuilder()
            .setModuleDocstring("My Language")
            .setFile("//:test.bzl")
            .addRuleInfo(
                RuleInfo.newBuilder()
                    .setRuleName("library_rules.my_library")
                    .setDocString("My language library")
                    .setOriginKey(
                        OriginKey.newBuilder().setName("_my_library").setFile("//:my_library.bzl"))
                    .addAttribute(
                        AttributeInfo.newBuilder()
                            // Empty docstring
                            .setName("srcs")
                            .setType(AttributeType.LABEL_LIST)
                            .setDefaultValue("[]"))
                    .addAttribute(
                        AttributeInfo.newBuilder()
                            // Empty docstring
                            .setName("deps")
                            .setType(AttributeType.LABEL_LIST)
                            .setDefaultValue("[]"))
                    .addAttribute(
                        AttributeInfo.newBuilder()
                            // Empty docstring
                            .setName("uncommonly_named_attr")
                            .setType(AttributeType.LABEL_LIST)
                            .setDefaultValue("[]")))
            .build();

    assertThat(collectModuleInfoDocs(moduleInfo)).isEqualTo(1);

    Set<RuleDocumentationAttribute> attributes = ruleDocEntries.get("my_library").getAttributes();
    assertThat(getAttribute(attributes, "srcs").isCommonType()).isTrue();
    assertThat(getAttribute(attributes, "srcs").getGeneratedInRule("my_library"))
        .isEqualTo(DocgenConsts.TYPICAL_ATTRIBUTES);
    assertThat(getAttribute(attributes, "deps").isCommonType()).isTrue();
    assertThat(getAttribute(attributes, "deps").getGeneratedInRule("my_library"))
        .isEqualTo(DocgenConsts.TYPICAL_ATTRIBUTES);
    assertThat(getAttribute(attributes, "uncommonly_named_attr").isCommonType()).isFalse();
    assertThat(getAttribute(attributes, "uncommonly_named_attr").getGeneratedInRule("my_library"))
        .isEqualTo("my_library");
  }

  @Test
  public void collectModuleInfoDocs_genericRulesFlaggedAsGeneric() throws Exception {
    ModuleInfo moduleInfo =
        ModuleInfo.newBuilder()
            .setModuleDocstring("Family")
            .setFile("//:test.bzl")
            .addRuleInfo(
                RuleInfo.newBuilder()
                    .setRuleName("generic_rules.my_rule")
                    .setDocString("My rule")
                    .setOriginKey(
                        OriginKey.newBuilder().setName("my_rule").setFile("//:my_rule.bzl")))
            .build();

    assertThat(collectModuleInfoDocs(moduleInfo)).isEqualTo(1);
    assertThat(ruleDocEntries.get("my_rule").isLanguageSpecific()).isFalse();
    assertThat(ruleDocEntries.get("my_rule").getRuleFamily()).isEqualTo("Family");
  }
}
