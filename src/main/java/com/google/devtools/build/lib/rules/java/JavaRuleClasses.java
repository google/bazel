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

package com.google.devtools.build.lib.rules.java;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;

import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.analysis.platform.ToolchainInfo;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;

/** Common rule class definitions for Java rules. */
public class JavaRuleClasses {

  private static final String JAVA_TOOLCHAIN_ATTRIBUTE_NAME = "$java_toolchain";

  public static final String JAVA_TOOLCHAIN_TYPE_ATTRIBUTE_NAME = "$java_toolchain_type";
  private static final String TOOLCHAIN_TYPE_LABEL = "//tools/jdk:toolchain_type";
  private static final String RUNTIME_TOOLCHAIN_TYPE_LABEL = "//tools/jdk:runtime_toolchain_type";

  public static ToolchainTypeRequirement javaToolchainTypeRequirement(
      RuleDefinitionEnvironment env) {
    return ToolchainTypeRequirement.builder(env.getToolsLabel(TOOLCHAIN_TYPE_LABEL))
        .mandatory(true)
        .build();
  }

  public static ToolchainTypeRequirement javaRuntimeToolchainTypeRequirement(
      RuleDefinitionEnvironment env) {
    return ToolchainTypeRequirement.builder(env.getToolsLabel(RUNTIME_TOOLCHAIN_TYPE_LABEL))
        .mandatory(true)
        .build();
  }

  /** Common attributes for rules that use the Java toolchain. */
  public static final class JavaToolchainBaseRule implements RuleDefinition {
    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .addToolchainTypes(javaToolchainTypeRequirement(env))
          .add(
              attr(JAVA_TOOLCHAIN_TYPE_ATTRIBUTE_NAME, LABEL)
                  .value(javaToolchainTypeRequirement(env).toolchainType()))
          .add( // TODO(b/245144242): Used by IDE integration, remove when toolchains are used
              attr(JAVA_TOOLCHAIN_ATTRIBUTE_NAME, LABEL)
                  .useOutputLicenses()
                  .mandatoryProviders(ToolchainInfo.PROVIDER.id())
                  .value(JavaSemantics.javaToolchainAttribute(env)))
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$java_toolchain_base_rule")
          .type(RuleClassType.ABSTRACT)
          .build();
    }
  }

  /** Common attributes for rules that use the Java runtime. */
  public static final class JavaRuntimeBaseRule implements RuleDefinition {
    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .addToolchainTypes(javaRuntimeToolchainTypeRequirement(env))
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$java_runtime_toolchain_base_rule")
          .type(RuleClassType.ABSTRACT)
          .build();
    }
  }
}
