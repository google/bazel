// Copyright 2021 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.view.java;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.prettyArtifactNames;
import static com.google.devtools.build.lib.rules.java.JavaCompileActionTestHelper.getProcessorpath;

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.configuredtargets.FileConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.rules.java.JavaCompileAction;
import com.google.devtools.build.lib.rules.java.JavaPluginInfo;
import com.google.devtools.build.lib.rules.java.ProguardSpecProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for java_plugin rule. */
@RunWith(JUnit4.class)
public class JavaPluginConfiguredTargetTest extends BuildViewTestCase {

  @Test
  public void testNoConstraintsAttribute() throws Exception {
    checkError(
        "java/plugin",
        "plugin",
        "no such attribute 'constraints' in 'java_plugin'",
        "load('@rules_java//java:defs.bzl', 'java_plugin')",
        "java_plugin(name = 'plugin',",
        "            srcs = ['A.java'],",
        "            processor_class = 'xx',",
        "            constraints = ['this_shouldnt_exist'])");
  }

  private void setupEmptyProcessorClass() throws Exception {
    scratch.file(
        "java/com/google/test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library", "java_plugin")
        java_library(
            name = "deps",
            srcs = ["Deps.java"],
        )

        java_plugin(
            name = "processor",
            srcs = ["Processor.java"],
            processor_class = "com.google.test.Processor",
            deps = [":deps"],
        )

        java_plugin(
            name = "bugchecker",
            srcs = ["BugChecker.java"],
            deps = [":deps"],
        )

        java_library(
            name = "empty",
            plugins = [":bugchecker"],
        )
        """);
  }

  @Test
  public void testNotEmptyProcessorClass() throws Exception {
    setupEmptyProcessorClass();

    ConfiguredTarget processorTarget = getConfiguredTarget("//java/com/google/test:processor");
    assertThat(JavaPluginInfo.get(processorTarget).plugins().processorClasses().toList())
        .containsExactly("com.google.test.Processor");
    assertThat(
            prettyArtifactNames(JavaPluginInfo.get(processorTarget).plugins().processorClasspath()))
        .containsExactly(
            "java/com/google/test/libprocessor.jar", "java/com/google/test/libdeps.jar");
  }

  @Test
  public void testEmptyProcessorClass() throws Exception {
    setupEmptyProcessorClass();

    ConfiguredTarget bugcheckerTarget = getConfiguredTarget("//java/com/google/test:bugchecker");
    assertThat(JavaPluginInfo.get(bugcheckerTarget).plugins().processorClasses().toList())
        .isEmpty();

    assertThat(
            prettyArtifactNames(
                JavaPluginInfo.get(bugcheckerTarget).plugins().processorClasspath()))
        .containsExactly(
            "java/com/google/test/libbugchecker.jar", "java/com/google/test/libdeps.jar");
  }

  @Test
  public void testEmptyProcessorClassTarget() throws Exception {
    setupEmptyProcessorClass();
    ConfiguredTarget bugcheckerTarget = getConfiguredTarget("//java/com/google/test:bugchecker");
    FileConfiguredTarget emptyOutput =
        getFileConfiguredTarget("//java/com/google/test:libempty.jar");
    JavaCompileAction javacAction =
        (JavaCompileAction) getGeneratingAction(emptyOutput.getArtifact());
    assertThat(
            Artifact.toRootRelativePaths(
                JavaPluginInfo.get(bugcheckerTarget).plugins().processorClasspath().toList()))
        .containsExactlyElementsIn(
            Artifact.toRootRelativePaths(getInputs(javacAction, getProcessorpath(javacAction))));
  }

  @Test
  public void testJavaPluginExportsTransitiveProguardSpecs() throws Exception {
    if (analysisMock.isThisBazel()) {
      return;
    }
    scratch.file(
        "java/com/google/android/hello/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library", "java_plugin")
        java_plugin(
            name = "plugin",
            srcs = ["Plugin.java"],
            proguard_specs = ["plugin.pro"],
        )

        java_library(
            name = "dep",
            srcs = ["Dep.java"],
            proguard_specs = ["dep.pro"],
        )

        java_plugin(
            name = "top",
            srcs = ["Top.java"],
            plugins = [":plugin"],
            proguard_specs = ["top.pro"],
            deps = [":dep"],
        )
        """);
    NestedSet<Artifact> providedSpecs =
        getConfiguredTarget("//java/com/google/android/hello:top")
            .get(ProguardSpecProvider.PROVIDER)
            .getTransitiveProguardSpecs();
    assertThat(ActionsTestUtil.baseArtifactNames(providedSpecs))
        .containsAtLeast("top.pro_valid", "dep.pro_valid");
    assertThat(ActionsTestUtil.baseArtifactNames(providedSpecs)).doesNotContain("plugin.pro_valid");
  }

  @Test
  public void testJavaPluginValidatesProguardSpecs() throws Exception {
    scratch.file(
        "java/com/google/android/hello/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_plugin")
        java_plugin(
            name = "plugin",
            srcs = ["Plugin.java"],
            proguard_specs = ["plugin.pro"],
        )
        """);
    SpawnAction action =
        (SpawnAction)
            actionsTestUtil()
                .getActionForArtifactEndingWith(
                    getOutputGroup(
                        getConfiguredTarget("//java/com/google/android/hello:plugin"),
                        OutputGroupInfo.HIDDEN_TOP_LEVEL),
                    "plugin.pro_valid");
    assertWithMessage("Proguard validate action").that(action).isNotNull();
    assertWithMessage("Proguard validate action input")
        .that(prettyArtifactNames(action.getInputs()))
        .contains("java/com/google/android/hello/plugin.pro");
  }

  @Test
  public void testJavaPluginValidatesTransitiveProguardSpecs() throws Exception {
    scratch.file(
        "java/com/google/android/hello/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library", "java_plugin")
        java_library(
            name = "transitive",
            srcs = ["Transitive.java"],
            proguard_specs = ["transitive.pro"],
        )

        java_plugin(
            name = "plugin",
            srcs = ["Plugin.java"],
            deps = [":transitive"],
        )
        """);
    SpawnAction action =
        (SpawnAction)
            actionsTestUtil()
                .getActionForArtifactEndingWith(
                    getOutputGroup(
                        getConfiguredTarget("//java/com/google/android/hello:plugin"),
                        OutputGroupInfo.HIDDEN_TOP_LEVEL),
                    "transitive.pro_valid");
    assertWithMessage("Proguard validate action").that(action).isNotNull();
    assertWithMessage("Proguard validate action input")
        .that(prettyArtifactNames(action.getInputs()))
        .contains("java/com/google/android/hello/transitive.pro");
  }

  @Test
  public void generatesApi() throws Exception {
    scratch.file(
        "java/com/google/test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_plugin")
        java_plugin(
            name = "api_generating",
            srcs = ["ApiGeneratingPlugin.java"],
            generates_api = True,
            processor_class = "ApiGeneratingPlugin",
        )
        """);

    JavaPluginInfo plugin =
        JavaPluginInfo.get(getConfiguredTarget("//java/com/google/test:api_generating"));
    assertThat(plugin.plugins().processorClasses().toList()).containsExactly("ApiGeneratingPlugin");
    assertThat(plugin.apiGeneratingPlugins().processorClasses().toList())
        .containsExactly("ApiGeneratingPlugin");
    assertThat(ActionsTestUtil.baseArtifactNames(plugin.plugins().processorClasspath()))
        .containsExactly("libapi_generating.jar");
    assertThat(
            ActionsTestUtil.baseArtifactNames(plugin.apiGeneratingPlugins().processorClasspath()))
        .containsExactly("libapi_generating.jar");
  }

  @Test
  public void generatesImplementation() throws Exception {
    scratch.file(
        "java/com/google/test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_plugin")
        java_plugin(
            name = "impl_generating",
            srcs = ["ImplGeneratingPlugin.java"],
            generates_api = False,
            processor_class = "ImplGeneratingPlugin",
        )
        """);

    JavaPluginInfo plugin =
        JavaPluginInfo.get(getConfiguredTarget("//java/com/google/test:impl_generating"));
    assertThat(plugin.plugins().processorClasses().toList())
        .containsExactly("ImplGeneratingPlugin");
    assertThat(plugin.apiGeneratingPlugins().processorClasses().toList()).isEmpty();
    assertThat(ActionsTestUtil.baseArtifactNames(plugin.plugins().processorClasspath()))
        .containsExactly("libimpl_generating.jar");
    assertThat(
            ActionsTestUtil.baseArtifactNames(plugin.apiGeneratingPlugins().processorClasspath()))
        .isEmpty();
  }

  @Test
  public void pluginData() throws Exception {
    scratch.file(
        "java/com/google/test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library", "java_plugin")
        java_plugin(
            name = "impl_generating",
            srcs = ["ImplGeneratingPlugin.java"],
            data = ["data.txt"],
            generates_api = False,
            processor_class = "ImplGeneratingPlugin",
        )

        java_library(
            name = "lib",
            plugins = [":impl_generating"],
        )
        """);

    JavaPluginInfo plugin =
        JavaPluginInfo.get(getConfiguredTarget("//java/com/google/test:impl_generating"));
    assertThat(prettyArtifactNames(plugin.plugins().data()))
        .containsExactly("java/com/google/test/data.txt");
    FileConfiguredTarget libJar = getFileConfiguredTarget("//java/com/google/test:liblib.jar");
    JavaCompileAction javacAction = (JavaCompileAction) getGeneratingAction(libJar.getArtifact());
    assertThat(prettyArtifactNames(javacAction.getInputs()))
        .contains("java/com/google/test/data.txt");
  }
}
