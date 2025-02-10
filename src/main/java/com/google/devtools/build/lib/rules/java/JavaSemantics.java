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

package com.google.devtools.build.lib.rules.java;


import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Attribute.LabelListLateBoundDefault;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.vfs.PathFragment;

/** Pluggable Java compilation semantics. */
public interface JavaSemantics {

  // transformed by Copybara on export
  String RULES_JAVA_PROVIDER_LABELS_PREFIX = "@@rules_java+//";

  FileType JAVA_SOURCE = FileType.of(".java");
  FileType JAR = FileType.of(".jar");
  FileType PROPERTIES = FileType.of(".properties");
  FileType SOURCE_JAR = FileType.of(".srcjar");

  /** The java_toolchain.compatible_javacopts key for Android javacopts */
  String ANDROID_JAVACOPTS_KEY = "android";

  /** The java_toolchain.compatible_javacopts key for testonly compilations. */
  String TESTONLY_JAVACOPTS_KEY = "testonly";

  /** The java_toolchain.compatible_javacopts key for public visibility. */
  String PUBLIC_VISIBILITY_JAVACOPTS_KEY = "public_visibility";

  static Label javaToolchainAttribute(RuleDefinitionEnvironment environment) {
    return environment.getToolsLabel("//tools/jdk:current_java_toolchain");
  }

  /** Name of the output group used for transitive source jars. */
  String SOURCE_JARS_OUTPUT_GROUP = OutputGroupInfo.HIDDEN_OUTPUT_GROUP_PREFIX + "source_jars";

  /** Name of the output group used for direct source jars. */
  String DIRECT_SOURCE_JARS_OUTPUT_GROUP =
      OutputGroupInfo.HIDDEN_OUTPUT_GROUP_PREFIX + "direct_source_jars";

  public String getJavaToolchainType();

  public Label getJavaRuntimeToolchainType();

  @SerializationConstant
  LabelListLateBoundDefault<JavaConfiguration> JAVA_PLUGINS =
      LabelListLateBoundDefault.fromTargetConfiguration(
          JavaConfiguration.class,
          (rule, attributes, javaConfig) -> ImmutableList.copyOf(javaConfig.getPlugins()));

  /**
   * Takes the path of a Java resource and tries to determine the Java root relative path of the
   * resource.
   *
   * <p>This is only used if the Java rule doesn't have a {@code resource_strip_prefix} attribute.
   *
   * @param path the root relative path of the resource.
   * @return the Java root relative path of the resource of the root relative path of the resource
   *     if no Java root relative path can be determined.
   */
  PathFragment getDefaultJavaResourcePath(PathFragment path);

  /** Environment variable that sets the UTF-8 charset for the given execution platform. */
  ImmutableMap<String, String> utf8Environment(PlatformInfo executionPlatform);
}
