// Copyright 2018 The Bazel Authors. All rights reserved.
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.prettyArtifactNames;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.rules.cpp.LibraryToLink;
import com.google.devtools.build.lib.rules.java.JavaPluginInfo.JavaPluginData;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider.JavaOutput;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.vfs.Path;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.util.Map;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests JavaInfo API for Starlark. */
@RunWith(JUnit4.class)
public class JavaInfoStarlarkApiTest extends BuildViewTestCase {

  @Test
  public void buildHelperCreateJavaInfoWithOutputJarOnly() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load(":extension.bzl", "my_rule")

        my_rule(
            name = "my_starlark_rule",
            output_jar = "my_starlark_rule_lib.jar",
            source_jars = ["my_starlark_rule_src.jar"],
        )
        """);
    assertNoEvents();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        fetchJavaInfo().getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.directCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.directFullCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.runtimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.transitiveCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithOutputJarAndUseIJar() throws Exception {

    ruleBuilder().withIJar().build();

    scratch.file(
        "foo/BUILD",
        """
        load(":extension.bzl", "my_rule")

        my_rule(
            name = "my_starlark_rule",
            output_jar = "my_starlark_rule_lib.jar",
            source_jars = ["my_starlark_rule_src.jar"],
        )
        """);
    assertNoEvents();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        fetchJavaInfo().getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.directCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib-ijar.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.directFullCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.runtimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.transitiveCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib-ijar.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoJavaRuleOutputJarsProviderSourceJarOutputJarAndUseIJar()
      throws Exception {
    ruleBuilder().withIJar().build();

    scratch.file(
        "foo/BUILD",
        """
        load(":extension.bzl", "my_rule")

        my_rule(
            name = "my_starlark_rule",
            output_jar = "my_starlark_rule_lib.jar",
            source_jars = ["my_starlark_rule_src.jar"],
        )
        """);
    assertNoEvents();

    JavaRuleOutputJarsProvider javaRuleOutputJarsProvider =
        fetchJavaInfo().getProvider(JavaRuleOutputJarsProvider.class);

    assertThat(prettyArtifactNames(javaRuleOutputJarsProvider.getAllSrcOutputJars()))
        .containsExactly("foo/my_starlark_rule_src.jar");

    assertThat(prettyArtifactNames(javaRuleOutputJarsProvider.getAllClassOutputJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");

    assertThat(javaRuleOutputJarsProvider.javaOutputs()).hasSize(1);
    JavaOutput javaOutput = javaRuleOutputJarsProvider.javaOutputs().get(0);

    assertThat(javaOutput.compileJar().prettyPrint())
        .isEqualTo("foo/my_starlark_rule_lib-ijar.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithDeps() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load(":extension.bzl", "my_rule")

        java_library(
            name = "my_java_lib_direct",
            srcs = ["java/A.java"],
        )

        my_rule(
            name = "my_starlark_rule",
            dep = [":my_java_lib_direct"],
            output_jar = "my_starlark_rule_lib.jar",
            source_jars = ["my_starlark_rule_src.jar"],
        )
        """);
    assertNoEvents();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        fetchJavaInfo().getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.directCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.directFullCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.runtimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar", "foo/libmy_java_lib_direct.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.transitiveCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar", "foo/libmy_java_lib_direct-hjar.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithRunTimeDeps() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load(":extension.bzl", "my_rule")

        java_library(
            name = "my_java_lib_direct",
            srcs = ["java/A.java"],
        )

        my_rule(
            name = "my_starlark_rule",
            dep_runtime = [":my_java_lib_direct"],
            output_jar = "my_starlark_rule_lib.jar",
            source_jars = ["my_starlark_rule_src.jar"],
        )
        """);
    assertNoEvents();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        fetchJavaInfo().getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.directCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.directFullCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.runtimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar", "foo/libmy_java_lib_direct.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.transitiveCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
  }

  /** Tests that JavaInfo can be constructed with CC native libraries as dependencies. */
  @Test
  public void javaInfo_setNativeLibraries() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load(":extension.bzl", "my_rule")

        cc_library(
            name = "my_cc_lib_direct",
            srcs = ["cc/a.cc"],
        )

        my_rule(
            name = "my_starlark_rule",
            cc_dep = [":my_cc_lib_direct"],
            output_jar = "my_starlark_rule_lib.jar",
            source_jars = ["my_starlark_rule_src.jar"],
        )
        """);
    assertNoEvents();

    JavaInfo javaInfoProvider = fetchJavaInfo();

    NestedSet<LibraryToLink> librariesForTopTarget =
        javaInfoProvider.getTransitiveNativeLibraries();
    assertThat(librariesForTopTarget.toList().stream().map(LibraryToLink::getLibraryIdentifier))
        .contains("foo/libmy_cc_lib_direct");
  }

  @Test
  public void buildHelperCreateJavaInfoWithDepsAndNeverLink() throws Exception {
    ruleBuilder().withNeverLink().build();

    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load(":extension.bzl", "my_rule")

        java_library(
            name = "my_java_lib_direct",
            srcs = ["java/A.java"],
        )

        my_rule(
            name = "my_starlark_rule",
            dep = [":my_java_lib_direct"],
            output_jar = "my_starlark_rule_lib.jar",
            source_jars = ["my_starlark_rule_src.jar"],
        )
        """);
    assertNoEvents();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        fetchJavaInfo().getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.directCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.directFullCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.runtimeJars())).isEmpty();
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.transitiveCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar", "foo/libmy_java_lib_direct-hjar.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoSourceJarsProviderWithSourceJars() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load(":extension.bzl", "my_rule")

        my_rule(
            name = "my_starlark_rule",
            output_jar = "my_starlark_rule_lib.jar",
            source_jars = ["my_starlark_rule_src.jar"],
        )
        """);
    assertNoEvents();

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.sourceJars()))
        .containsExactly("foo/my_starlark_rule_src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.transitiveSourceJars()))
        .containsExactly("foo/my_starlark_rule_src.jar");
  }

  @Test
  public void buildHelperPackSources_repackSingleJar() throws Exception {
    ruleBuilder().withSourceFiles().build();
    scratch.file(
        "foo/BUILD",
        """
        load(":extension.bzl", "my_rule")

        my_rule(
            name = "my_starlark_rule",
            output_jar = "my_starlark_rule_lib.jar",
            source_jars = ["my_starlark_rule_src.jar"],
        )
        """);
    assertNoEvents();

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.sourceJars()))
        .containsExactly("foo/my_starlark_rule_lib-src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.transitiveSourceJars()))
        .containsExactly("foo/my_starlark_rule_lib-src.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithSourcesFiles() throws Exception {
    ruleBuilder().withSourceFiles().build();

    scratch.file(
        "foo/BUILD",
        """
        load(":extension.bzl", "my_rule")

        my_rule(
            name = "my_starlark_rule",
            output_jar = "my_starlark_rule_lib.jar",
            sources = [
                "ClassA.java",
                "ClassB.java",
                "ClassC.java",
                "ClassD.java",
            ],
        )
        """);
    assertNoEvents();

    JavaRuleOutputJarsProvider javaRuleOutputJarsProvider =
        fetchJavaInfo().getProvider(JavaRuleOutputJarsProvider.class);

    assertThat(prettyArtifactNames(javaRuleOutputJarsProvider.getAllSrcOutputJars()))
        .containsExactly("foo/my_starlark_rule_lib-src.jar");

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.sourceJars()))
        .containsExactly("foo/my_starlark_rule_lib-src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.transitiveSourceJars()))
        .containsExactly("foo/my_starlark_rule_lib-src.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithSourcesFilesAndSourcesJars() throws Exception {
    ruleBuilder().withSourceFiles().build();

    scratch.file(
        "foo/BUILD",
        """
        load(":extension.bzl", "my_rule")

        my_rule(
            name = "my_starlark_rule",
            output_jar = "my_starlark_rule_lib.jar",
            source_jars = ["my_starlark_rule_src-A.jar"],
            sources = [
                "ClassA.java",
                "ClassB.java",
                "ClassC.java",
                "ClassD.java",
            ],
        )
        """);
    assertNoEvents();

    JavaRuleOutputJarsProvider javaRuleOutputJarsProvider =
        fetchJavaInfo().getProvider(JavaRuleOutputJarsProvider.class);

    assertThat(prettyArtifactNames(javaRuleOutputJarsProvider.getAllSrcOutputJars()))
        .containsExactly("foo/my_starlark_rule_lib-src.jar");

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.sourceJars()))
        .containsExactly("foo/my_starlark_rule_lib-src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.transitiveSourceJars()))
        .containsExactly("foo/my_starlark_rule_lib-src.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoSourceJarsProviderWithDeps() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load(":extension.bzl", "my_rule")

        java_library(
            name = "my_java_lib_direct",
            srcs = ["java/A.java"],
        )

        my_rule(
            name = "my_starlark_rule",
            dep = [":my_java_lib_direct"],
            output_jar = "my_starlark_rule_lib.jar",
            source_jars = ["my_starlark_rule_src.jar"],
        )
        """);
    assertNoEvents();

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.sourceJars()))
        .containsExactly("foo/my_starlark_rule_src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.transitiveSourceJars()))
        .containsExactly("foo/my_starlark_rule_src.jar", "foo/libmy_java_lib_direct-src.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoJavaSourceJarsProviderAndRuntimeDeps() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load(":extension.bzl", "my_rule")

        java_library(
            name = "my_java_lib_direct",
            srcs = ["java/A.java"],
        )

        my_rule(
            name = "my_starlark_rule",
            dep_runtime = [":my_java_lib_direct"],
            output_jar = "my_starlark_rule_lib.jar",
            source_jars = ["my_starlark_rule_src.jar"],
        )
        """);
    assertNoEvents();

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.sourceJars()))
        .containsExactly("foo/my_starlark_rule_src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.transitiveSourceJars()))
        .containsExactly("foo/my_starlark_rule_src.jar", "foo/libmy_java_lib_direct-src.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoJavaSourceJarsProviderAndTransitiveDeps() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load(":extension.bzl", "my_rule")

        java_library(
            name = "my_java_lib_transitive",
            srcs = ["java/B.java"],
        )

        java_library(
            name = "my_java_lib_direct",
            srcs = ["java/A.java"],
            deps = [":my_java_lib_transitive"],
        )

        my_rule(
            name = "my_starlark_rule",
            dep = [":my_java_lib_direct"],
            output_jar = "my_starlark_rule_lib.jar",
            source_jars = ["my_starlark_rule_src.jar"],
        )
        """);
    assertNoEvents();

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.sourceJars()))
        .containsExactly("foo/my_starlark_rule_src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.transitiveSourceJars()))
        .containsExactly(
            "foo/my_starlark_rule_src.jar",
            "foo/libmy_java_lib_direct-src.jar",
            "foo/libmy_java_lib_transitive-src.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoJavaSourceJarsProviderAndTransitiveRuntimeDeps()
      throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load(":extension.bzl", "my_rule")

        java_library(
            name = "my_java_lib_transitive",
            srcs = ["java/B.java"],
        )

        java_library(
            name = "my_java_lib_direct",
            srcs = ["java/A.java"],
            deps = [":my_java_lib_transitive"],
        )

        my_rule(
            name = "my_starlark_rule",
            dep = [":my_java_lib_direct"],
            output_jar = "my_starlark_rule_lib.jar",
            source_jars = ["my_starlark_rule_src.jar"],
        )
        """);
    assertNoEvents();

    JavaSourceJarsProvider sourceJarsProvider =
        fetchJavaInfo().getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(sourceJarsProvider.sourceJars()))
        .containsExactly("foo/my_starlark_rule_src.jar");

    assertThat(prettyArtifactNames(sourceJarsProvider.transitiveSourceJars()))
        .containsExactly(
            "foo/my_starlark_rule_src.jar",
            "foo/libmy_java_lib_direct-src.jar",
            "foo/libmy_java_lib_transitive-src.jar");
  }

  /** Test exports adds dependencies to JavaCompilationArgsProvider. */
  @Test
  public void buildHelperCreateJavaInfoExportProviderExportsDepsAdded() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load(":extension.bzl", "my_rule")

        java_library(
            name = "my_java_lib_exports",
            srcs = ["java/A.java"],
        )

        my_rule(
            name = "my_starlark_rule",
            dep_exports = [":my_java_lib_exports"],
            output_jar = "my_starlark_rule_lib.jar",
        )
        """);
    assertNoEvents();

    JavaInfo javaInfo = fetchJavaInfo();

    JavaSourceJarsProvider javaSourceJarsProvider =
        javaInfo.getProvider(JavaSourceJarsProvider.class);

    assertThat(javaSourceJarsProvider.sourceJars()).isEmpty();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        javaInfo.getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.directCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar", "foo/libmy_java_lib_exports-hjar.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.directFullCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar", "foo/libmy_java_lib_exports.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.runtimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar", "foo/libmy_java_lib_exports.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.transitiveCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar", "foo/libmy_java_lib_exports-hjar.jar");
  }

  /** Test exports adds itself and recursive dependencies to JavaCompilationArgsProvider. */
  @Test
  public void buildHelperCreateJavaInfoExportProvider() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load(":extension.bzl", "my_rule")

        java_library(
            name = "my_java_lib_c",
            srcs = ["java/C.java"],
        )

        java_library(
            name = "my_java_lib_b",
            srcs = ["java/B.java"],
        )

        java_library(
            name = "my_java_lib_a",
            srcs = ["java/A.java"],
            exports = [":my_java_lib_b"],
            deps = [
                ":my_java_lib_b",
                ":my_java_lib_c",
            ],
        )

        my_rule(
            name = "my_starlark_rule",
            dep_exports = [":my_java_lib_a"],
            output_jar = "my_starlark_rule_lib.jar",
        )
        """);
    assertNoEvents();

    JavaInfo javaInfo = fetchJavaInfo();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        javaInfo.getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.directCompileTimeJars()))
        .containsExactly(
            "foo/my_starlark_rule_lib.jar",
            "foo/libmy_java_lib_a-hjar.jar",
            "foo/libmy_java_lib_b-hjar.jar");

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.directFullCompileTimeJars()))
        .containsExactly(
            "foo/my_starlark_rule_lib.jar", "foo/libmy_java_lib_a.jar", "foo/libmy_java_lib_b.jar");

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.runtimeJars()))
        .containsExactly(
            "foo/my_starlark_rule_lib.jar",
            "foo/libmy_java_lib_a.jar",
            "foo/libmy_java_lib_b.jar",
            "foo/libmy_java_lib_c.jar");

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.transitiveCompileTimeJars()))
        .containsExactly(
            "foo/my_starlark_rule_lib.jar",
            "foo/libmy_java_lib_a-hjar.jar",
            "foo/libmy_java_lib_b-hjar.jar",
            "foo/libmy_java_lib_c-hjar.jar");
  }

  /**
   * Tests case: my_lib // \ a c // \\ b d
   *
   * <p>where single line is normal dependency and double is exports dependency.
   */
  @Test
  public void buildHelperCreateJavaInfoExportProvider001() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load(":extension.bzl", "my_rule")

        java_library(
            name = "my_java_lib_b",
            srcs = ["java/B.java"],
        )

        java_library(
            name = "my_java_lib_a",
            srcs = ["java/A.java"],
            exports = [":my_java_lib_b"],
            deps = [":my_java_lib_b"],
        )

        java_library(
            name = "my_java_lib_d",
            srcs = ["java/D.java"],
        )

        java_library(
            name = "my_java_lib_c",
            srcs = ["java/C.java"],
            exports = [":my_java_lib_d"],
            deps = [":my_java_lib_d"],
        )

        my_rule(
            name = "my_starlark_rule",
            dep = [
                ":my_java_lib_a",
                ":my_java_lib_c",
            ],
            dep_exports = [":my_java_lib_a"],
            output_jar = "my_starlark_rule_lib.jar",
        )
        """);
    assertNoEvents();

    JavaInfo javaInfo = fetchJavaInfo();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        javaInfo.getProvider(JavaCompilationArgsProvider.class);

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.directCompileTimeJars()))
        .containsExactly(
            "foo/my_starlark_rule_lib.jar",
            "foo/libmy_java_lib_a-hjar.jar",
            "foo/libmy_java_lib_b-hjar.jar");

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.directFullCompileTimeJars()))
        .containsExactly(
            "foo/my_starlark_rule_lib.jar", "foo/libmy_java_lib_a.jar", "foo/libmy_java_lib_b.jar");

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.runtimeJars()))
        .containsExactly(
            "foo/my_starlark_rule_lib.jar",
            "foo/libmy_java_lib_a.jar",
            "foo/libmy_java_lib_b.jar",
            "foo/libmy_java_lib_c.jar",
            "foo/libmy_java_lib_d.jar");

    assertThat(prettyArtifactNames(javaCompilationArgsProvider.transitiveCompileTimeJars()))
        .containsExactly(
            "foo/my_starlark_rule_lib.jar",
            "foo/libmy_java_lib_a-hjar.jar",
            "foo/libmy_java_lib_b-hjar.jar",
            "foo/libmy_java_lib_c-hjar.jar",
            "foo/libmy_java_lib_d-hjar.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoPluginsFromExports() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library", "java_plugin")
        load(":extension.bzl", "my_rule")

        java_library(
            name = "plugin_dep",
            srcs = ["ProcessorDep.java"],
        )

        java_plugin(
            name = "plugin",
            srcs = ["AnnotationProcessor.java"],
            processor_class = "com.google.process.stuff",
            deps = [":plugin_dep"],
        )

        java_library(
            name = "export",
            exported_plugins = [":plugin"],
        )

        my_rule(
            name = "my_starlark_rule",
            dep_exports = [":export"],
            output_jar = "my_starlark_rule_lib.jar",
        )
        """);
    assertNoEvents();

    assertThat(fetchJavaInfo().getJavaPluginInfo().plugins().processorClasses().toList())
        .containsExactly("com.google.process.stuff");
  }

  @Test
  public void buildHelperCreateJavaInfoWithPlugins() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library", "java_plugin")
        load(":extension.bzl", "my_rule")

        java_library(
            name = "plugin_dep",
            srcs = ["ProcessorDep.java"],
        )

        java_plugin(
            name = "plugin",
            srcs = ["AnnotationProcessor.java"],
            processor_class = "com.google.process.stuff",
            deps = [":plugin_dep"],
        )

        my_rule(
            name = "my_starlark_rule",
            dep_exported_plugins = [":plugin"],
            output_jar = "my_starlark_rule_lib.jar",
        )
        """);
    assertNoEvents();

    assertThat(fetchJavaInfo().getJavaPluginInfo().plugins().processorClasses().toList())
        .containsExactly("com.google.process.stuff");
  }

  @Test
  public void buildHelperCreateJavaInfoWithOutputJarAndStampJar() throws Exception {
    ruleBuilder().withStampJar().build();

    scratch.file(
        "foo/BUILD",
        """
        load(":extension.bzl", "my_rule")

        my_rule(
            name = "my_starlark_rule",
            output_jar = "my_starlark_rule_lib.jar",
            source_jars = ["my_starlark_rule_src.jar"],
        )
        """);
    assertNoEvents();

    JavaCompilationArgsProvider javaCompilationArgsProvider =
        fetchJavaInfo().getProvider(JavaCompilationArgsProvider.class);
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.directFullCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.directCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib-stamped.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.runtimeJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(javaCompilationArgsProvider.transitiveCompileTimeJars()))
        .containsExactly("foo/my_starlark_rule_lib-stamped.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithJdeps_javaRuleOutputJarsProvider() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load(":extension.bzl", "my_rule")

        java_library(
            name = "my_java_lib_direct",
            srcs = ["java/A.java"],
        )

        my_rule(
            name = "my_starlark_rule",
            dep = [":my_java_lib_direct"],
            jdeps = "my_jdeps.pb",
            output_jar = "my_starlark_rule_lib.jar",
            source_jars = ["my_starlark_rule_src.jar"],
        )
        """);
    assertNoEvents();

    JavaRuleOutputJarsProvider ruleOutputs =
        fetchJavaInfo().getProvider(JavaRuleOutputJarsProvider.class);

    assertThat(prettyArtifactNames(ruleOutputs.getAllClassOutputJars()))
        .containsExactly("foo/my_starlark_rule_lib.jar");
    assertThat(prettyArtifactNames(ruleOutputs.getAllSrcOutputJars()))
        .containsExactly("foo/my_starlark_rule_src.jar");
    assertThat(
            prettyArtifactNames(
                ruleOutputs.javaOutputs().stream()
                    .map(JavaOutput::jdeps)
                    .collect(toImmutableList())))
        .containsExactly("foo/my_jdeps.pb");
  }

  @Test
  public void buildHelperCreateJavaInfoWithGeneratedJars_javaRuleOutputJarsProvider()
      throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load(":extension.bzl", "my_rule")

        java_library(
            name = "my_java_lib_direct",
            srcs = ["java/A.java"],
        )

        my_rule(
            name = "my_starlark_rule",
            dep = [":my_java_lib_direct"],
            generated_class_jar = "generated_class.jar",
            generated_source_jar = "generated_srcs.jar",
            output_jar = "my_starlark_rule_lib.jar",
            source_jars = ["my_starlark_rule_src.jar"],
        )
        """);
    assertNoEvents();

    JavaRuleOutputJarsProvider ruleOutputs =
        fetchJavaInfo().getProvider(JavaRuleOutputJarsProvider.class);

    assertThat(
            prettyArtifactNames(
                ruleOutputs.javaOutputs().stream()
                    .map(JavaOutput::generatedClassJar)
                    .collect(toImmutableList())))
        .containsExactly("foo/generated_class.jar");
    assertThat(
            prettyArtifactNames(
                ruleOutputs.javaOutputs().stream()
                    .map(JavaOutput::generatedSourceJar)
                    .collect(toImmutableList())))
        .containsExactly("foo/generated_srcs.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithGeneratedJars_javaGenJarsProvider() throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load(":extension.bzl", "my_rule")

        java_library(
            name = "my_java_lib_direct",
            srcs = ["java/A.java"],
        )

        my_rule(
            name = "my_starlark_rule",
            dep = [":my_java_lib_direct"],
            generated_class_jar = "generated_class.jar",
            generated_source_jar = "generated_srcs.jar",
            output_jar = "my_starlark_rule_lib.jar",
            source_jars = ["my_starlark_rule_src.jar"],
        )
        """);
    assertNoEvents();

    JavaGenJarsProvider ruleOutputs = fetchJavaInfo().getProvider(JavaGenJarsProvider.class);

    assertThat(ruleOutputs.getGenClassJar().prettyPrint()).isEqualTo("foo/generated_class.jar");
    assertThat(ruleOutputs.getGenSourceJar().prettyPrint()).isEqualTo("foo/generated_srcs.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithCompileJdeps_javaRuleOutputJarsProvider()
      throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load(":extension.bzl", "my_rule")

        java_library(
            name = "my_java_lib_direct",
            srcs = ["java/A.java"],
        )

        my_rule(
            name = "my_starlark_rule",
            compile_jdeps = "compile.deps",
            dep = [":my_java_lib_direct"],
            output_jar = "my_starlark_rule_lib.jar",
            source_jars = ["my_starlark_rule_src.jar"],
        )
        """);
    assertNoEvents();

    JavaRuleOutputJarsProvider ruleOutputs =
        fetchJavaInfo().getProvider(JavaRuleOutputJarsProvider.class);

    assertThat(
            prettyArtifactNames(
                ruleOutputs.javaOutputs().stream()
                    .map(JavaOutput::compileJdeps)
                    .collect(toImmutableList())))
        .containsExactly("foo/compile.deps");
  }

  @Test
  public void buildHelperCreateJavaInfoWithNativeHeaders_javaRuleOutputJarsProvider()
      throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load(":extension.bzl", "my_rule")

        java_library(
            name = "my_java_lib_direct",
            srcs = ["java/A.java"],
        )

        my_rule(
            name = "my_starlark_rule",
            dep = [":my_java_lib_direct"],
            native_headers_jar = "nativeheaders.jar",
            output_jar = "my_starlark_rule_lib.jar",
            source_jars = ["my_starlark_rule_src.jar"],
        )
        """);
    assertNoEvents();

    JavaRuleOutputJarsProvider ruleOutputs =
        fetchJavaInfo().getProvider(JavaRuleOutputJarsProvider.class);

    assertThat(
            prettyArtifactNames(
                ruleOutputs.javaOutputs().stream()
                    .map(JavaOutput::nativeHeadersJar)
                    .collect(toImmutableList())))
        .containsExactly("foo/nativeheaders.jar");
  }

  @Test
  public void buildHelperCreateJavaInfoWithManifestProto_javaRuleOutputJarsProvider()
      throws Exception {
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load(":extension.bzl", "my_rule")

        java_library(
            name = "my_java_lib_direct",
            srcs = ["java/A.java"],
        )

        my_rule(
            name = "my_starlark_rule",
            dep = [":my_java_lib_direct"],
            manifest_proto = "manifest.proto",
            output_jar = "my_starlark_rule_lib.jar",
            source_jars = ["my_starlark_rule_src.jar"],
        )
        """);
    assertNoEvents();

    JavaRuleOutputJarsProvider ruleOutputs =
        fetchJavaInfo().getProvider(JavaRuleOutputJarsProvider.class);

    assertThat(
            prettyArtifactNames(
                ruleOutputs.javaOutputs().stream()
                    .map(JavaOutput::manifestProto)
                    .collect(toImmutableList())))
        .containsExactly("foo/manifest.proto");
  }

  @Test
  public void buildHelperCreateJavaInfoWithModuleFlags() throws Exception {
    setBuildLanguageOptions("--noincompatible_java_info_merge_runtime_module_flags");
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load(":extension.bzl", "my_rule")

        java_library(
            name = "my_java_lib_direct",
            srcs = ["java/A.java"],
            add_exports = ["java.base/java.lang"],
            add_opens = ["java.base/java.lang"],
        )

        java_library(
            name = "my_java_lib_runtime",
            srcs = ["java/A.java"],
            add_opens = ["java.base/java.util"],
        )

        java_library(
            name = "my_java_lib_exports",
            srcs = ["java/A.java"],
            add_opens = ["java.base/java.math"],
        )

        my_rule(
            name = "my_starlark_rule",
            add_exports = ["java.base/java.lang.invoke"],
            dep = [":my_java_lib_direct"],
            dep_exports = [":my_java_lib_exports"],
            dep_runtime = [":my_java_lib_runtime"],
            output_jar = "my_starlark_rule_lib.jar",
        )
        """);
    assertNoEvents();

    JavaModuleFlagsProvider ruleOutputs =
        fetchJavaInfo().getProvider(JavaModuleFlagsProvider.class);

    if (analysisMock.isThisBazel()) {
      assertThat(ruleOutputs.toFlags())
          .containsExactly(
              "--add-exports=java.base/java.lang=ALL-UNNAMED",
              "--add-exports=java.base/java.lang.invoke=ALL-UNNAMED",
              // NB: no java.base/java.util as the JavaInfo constructor doesn't
              // look at runtime_deps for module flags.
              "--add-opens=java.base/java.lang=ALL-UNNAMED",
              "--add-opens=java.base/java.math=ALL-UNNAMED")
          .inOrder();
    } else {
      // add_exports/add_opens ignored in JavaInfo constructor in #newJavaInfo below
      assertThat(ruleOutputs.toFlags())
          .containsExactly(
              "--add-exports=java.base/java.lang=ALL-UNNAMED",
              // NB: no java.base/java.util as the JavaInfo constructor doesn't
              // look at runtime_deps for module flags.
              "--add-opens=java.base/java.lang=ALL-UNNAMED",
              "--add-opens=java.base/java.math=ALL-UNNAMED")
          .inOrder();
    }
  }

  @Test
  public void buildHelperCreateJavaInfoWithModuleFlagsIncompatibleMergeRuntime() throws Exception {
    setBuildLanguageOptions("--incompatible_java_info_merge_runtime_module_flags");
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load(":extension.bzl", "my_rule")

        java_library(
            name = "my_java_lib_direct",
            srcs = ["java/A.java"],
            add_exports = ["java.base/java.lang"],
            add_opens = ["java.base/java.lang"],
        )

        java_library(
            name = "my_java_lib_runtime",
            srcs = ["java/A.java"],
            add_opens = ["java.base/java.util"],
        )

        java_library(
            name = "my_java_lib_exports",
            srcs = ["java/A.java"],
            add_opens = ["java.base/java.math"],
        )

        my_rule(
            name = "my_starlark_rule",
            add_exports = ["java.base/java.lang.invoke"],
            dep = [":my_java_lib_direct"],
            dep_exports = [":my_java_lib_exports"],
            dep_runtime = [":my_java_lib_runtime"],
            output_jar = "my_starlark_rule_lib.jar",
        )
        """);
    assertNoEvents();

    JavaModuleFlagsProvider ruleOutputs =
        fetchJavaInfo().getProvider(JavaModuleFlagsProvider.class);

    if (analysisMock.isThisBazel()) {
      assertThat(ruleOutputs.toFlags())
          .containsExactly(
              "--add-exports=java.base/java.lang=ALL-UNNAMED",
              "--add-exports=java.base/java.lang.invoke=ALL-UNNAMED",
              "--add-opens=java.base/java.util=ALL-UNNAMED",
              "--add-opens=java.base/java.math=ALL-UNNAMED",
              "--add-opens=java.base/java.lang=ALL-UNNAMED")
          .inOrder();
    } else {
      // add_exports/add_opens ignored in JavaInfo constructor in #newJavaInfo below
      assertThat(ruleOutputs.toFlags())
          .containsExactly(
              "--add-exports=java.base/java.lang=ALL-UNNAMED",
              "--add-opens=java.base/java.util=ALL-UNNAMED",
              "--add-opens=java.base/java.math=ALL-UNNAMED",
              "--add-opens=java.base/java.lang=ALL-UNNAMED")
          .inOrder();
    }
  }

  @Test
  public void starlarkJavaOutputsCanBeAddedToJavaPluginInfo() throws Exception {
    Artifact classJar = createArtifact("foo.jar");
    StarlarkInfo starlarkJavaOutput =
        makeStruct(ImmutableMap.of("source_jars", Starlark.NONE, "class_jar", classJar));
    StarlarkInfo starlarkPluginInfo =
        makeStruct(
            ImmutableMap.of(
                "java_outputs", StarlarkList.immutableOf(starlarkJavaOutput),
                "plugins", JavaPluginData.empty(),
                "api_generating_plugins", JavaPluginData.empty()));

    JavaPluginInfo pluginInfo = JavaPluginInfo.wrap(starlarkPluginInfo);

    assertThat(pluginInfo).isNotNull();
    assertThat(pluginInfo.getJavaOutputs()).hasSize(1);
    assertThat(pluginInfo.getJavaOutputs().get(0).classJar()).isEqualTo(classJar);
  }

  @Test
  public void javaOutputSourceJarsReturnsDepsetWithIncompatibleFlagEnabled() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        """
        load("@rules_java//java/common:java_info.bzl", "JavaInfo")
        MyInfo = provider()

        def _impl(ctx):
            return MyInfo(source_jars = ctx.attr.dep[JavaInfo].java_outputs[0].source_jars)

        my_rule = rule(
            implementation = _impl,
            attrs = {"dep": attr.label()},
        )
        """);
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load(":extension.bzl", "my_rule")

        java_library(name = "lib")

        my_rule(
            name = "my_starlark_rule",
            dep = ":lib",
        )
        """);

    ConfiguredTarget target = getConfiguredTarget("//foo:my_starlark_rule");

    StarlarkInfo info =
        (StarlarkInfo)
            target.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonical("//foo:extension.bzl")), "MyInfo"));
    assertThat(info).isNotNull();
    assertThat(info.getValue("source_jars")).isInstanceOf(Depset.class);
  }

  @Test
  public void nativeAndStarlarkJavaOutputsCanBeAddedToADepset() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        """
        load("@rules_java//java/common:java_info.bzl", "JavaInfo")
        def _impl(ctx):
            f = ctx.actions.declare_file(ctx.label.name + ".jar")
            ctx.actions.write(f, "")
            return [JavaInfo(output_jar = f, compile_jar = None)]

        my_rule = rule(implementation = _impl)
        """);
    scratch.file(
        "foo/BUILD",
        """
        load(":extension.bzl", "my_rule")

        my_rule(name = "my_starlark_rule")
        """);
    JavaOutput nativeOutput =
        JavaOutput.builder().setClassJar(createArtifact("native.jar")).build();
    StarlarkList<?> starlarkOutputs =
        JavaInfo.getJavaInfo(getConfiguredTarget("//foo:my_starlark_rule"))
            .getValue("java_outputs", StarlarkList.class);

    Depset depset =
        Depset.fromDirectAndTransitive(
            Order.STABLE_ORDER,
            /* direct= */ ImmutableList.builder().add(nativeOutput).addAll(starlarkOutputs).build(),
            /* transitive= */ ImmutableList.of(),
            /* strict= */ true);

    assertThat(depset).isNotNull();
    assertThat(depset.toList()).hasSize(2);
  }

  @Test
  public void testNeverlinkIsStoredAsABoolean() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        """
        load("@rules_java//java/common:java_info.bzl", "JavaInfo")
        def _impl(ctx):
            f = ctx.actions.declare_file(ctx.label.name + ".jar")
            ctx.actions.write(f, "")
            return [JavaInfo(output_jar = f, compile_jar = None, neverlink = 1)]

        my_rule = rule(implementation = _impl)
        """);
    scratch.file(
        "foo/BUILD",
        """
        load(":extension.bzl", "my_rule")

        my_rule(name = "my_starlark_rule")
        """);

    JavaInfo javaInfo = JavaInfo.getJavaInfo(getConfiguredTarget("//foo:my_starlark_rule"));

    assertThat(javaInfo).isNotNull();
    assertThat(javaInfo.isNeverlink()).isTrue();
  }

  @Test
  public void translateStarlarkJavaInfo_minimal() throws Exception {
    ImmutableMap<String, Object> fields = getBuilderWithMandataryFields().buildOrThrow();
    StarlarkInfo starlarkInfo = makeStruct(fields);

    JavaInfo javaInfo = JavaInfo.wrap(starlarkInfo);

    assertThat(javaInfo).isNotNull();
    assertThat(javaInfo.getProvider(JavaCompilationArgsProvider.class)).isNotNull();
    assertThat(javaInfo.getCompilationInfoProvider()).isNull();
    assertThat(javaInfo.getJavaModuleFlagsInfo()).isEqualTo(JavaModuleFlagsProvider.EMPTY);
    assertThat(javaInfo.getJavaPluginInfo())
        .isEqualTo(JavaPluginInfo.empty(JavaPluginInfo.PROVIDER));
  }

  @Test
  public void translateStarlarkJavaInfo_binariesDoNotContainCompilationArgs() throws Exception {
    ImmutableMap<String, Object> fields =
        getBuilderWithMandataryFields().put("_is_binary", true).buildOrThrow();
    StarlarkInfo starlarkInfo = makeStruct(fields);

    JavaInfo javaInfo = JavaInfo.wrap(starlarkInfo);

    assertThat(javaInfo).isNotNull();
    assertThat(javaInfo.getProvider(JavaCompilationArgsProvider.class)).isNull();
  }

  @Test
  public void translateStarlarkJavaInfo_compilationInfo() throws Exception {
    ImmutableMap<String, Object> fields =
        getBuilderWithMandataryFields()
            .put(
                "compilation_info",
                makeStruct(
                    ImmutableMap.of(
                        "javac_options",
                            Depset.of(
                                String.class,
                                NestedSetBuilder.create(Order.NAIVE_LINK_ORDER, "opt1", "opt2")),
                        "boot_classpath", StarlarkList.immutableOf(createArtifact("cp.jar")))))
            .buildOrThrow();
    StarlarkInfo starlarkInfo = makeStruct(fields);

    JavaInfo javaInfo = JavaInfo.wrap(starlarkInfo);

    assertThat(javaInfo).isNotNull();
    assertThat(javaInfo.getCompilationInfoProvider()).isNotNull();
    assertThat(javaInfo.getCompilationInfoProvider().getJavacOptsList())
        .containsExactly("opt1", "opt2");
    assertThat(javaInfo.getCompilationInfoProvider().getBootClasspathList()).hasSize(1);
    assertThat(prettyArtifactNames(javaInfo.getCompilationInfoProvider().getBootClasspathList()))
        .containsExactly("cp.jar");
  }

  @Test
  public void translatedStarlarkCompilationInfoEqualsNativeInstance() throws Exception {
    Artifact bootClasspathArtifact = createArtifact("boot.jar");
    NestedSet<Artifact> compilationClasspath =
        NestedSetBuilder.create(Order.NAIVE_LINK_ORDER, createArtifact("compile.jar"));
    NestedSet<Artifact> runtimeClasspath =
        NestedSetBuilder.create(Order.NAIVE_LINK_ORDER, createArtifact("runtime.jar"));
    StarlarkInfo starlarkInfo =
        makeStruct(
            ImmutableMap.of(
                "compilation_classpath", Depset.of(Artifact.class, compilationClasspath),
                "runtime_classpath", Depset.of(Artifact.class, runtimeClasspath),
                "javac_options",
                    Depset.of(
                        String.class,
                        NestedSetBuilder.create(Order.NAIVE_LINK_ORDER, "opt1", "opt2")),
                "boot_classpath", StarlarkList.immutableOf(bootClasspathArtifact)));
    JavaCompilationInfoProvider nativeCompilationInfo =
        new JavaCompilationInfoProvider.Builder()
            .setCompilationClasspath(compilationClasspath)
            .setRuntimeClasspath(runtimeClasspath)
            .setJavacOpts(NestedSetBuilder.create(Order.NAIVE_LINK_ORDER, "opt1", "opt2"))
            .setBootClasspath(
                NestedSetBuilder.create(Order.NAIVE_LINK_ORDER, bootClasspathArtifact))
            .build();

    JavaCompilationInfoProvider starlarkCompilationInfo =
        JavaCompilationInfoProvider.fromStarlarkCompilationInfo(starlarkInfo);

    assertThat(starlarkCompilationInfo).isNotNull();
    assertThat(starlarkCompilationInfo).isEqualTo(nativeCompilationInfo);
  }

  @Test
  public void translateStarlarkJavaInfo_moduleFlagsInfo() throws Exception {
    ImmutableMap<String, Object> fields =
        getBuilderWithMandataryFields()
            .put(
                "module_flags_info",
                makeStruct(
                    ImmutableMap.of(
                        "add_exports", makeDepset(String.class, "export1", "export2"),
                        "add_opens", makeDepset(String.class, "open1", "open2"))))
            .buildOrThrow();
    StarlarkInfo starlarkInfo = makeStruct(fields);

    JavaInfo javaInfo = JavaInfo.wrap(starlarkInfo);

    assertThat(javaInfo).isNotNull();
    assertThat(javaInfo.getJavaModuleFlagsInfo()).isNotNull();
    assertThat(javaInfo.getJavaModuleFlagsInfo().getAddExports().toList())
        .containsExactly("export1", "export2");
    assertThat(javaInfo.getJavaModuleFlagsInfo().getAddOpens().toList())
        .containsExactly("open1", "open2");
  }

  @Test
  public void translateStarlarkJavaInfo_pluginInfo() throws Exception {
    ImmutableMap<String, Object> fields =
        getBuilderWithMandataryFields()
            .put(
                "plugins",
                JavaPluginData.create(
                    NestedSetBuilder.create(Order.STABLE_ORDER, "c1", "c2", "c3"),
                    NestedSetBuilder.create(Order.STABLE_ORDER, createArtifact("f1")),
                    NestedSetBuilder.emptySet(Order.STABLE_ORDER)))
            .buildKeepingLast();
    StarlarkInfo starlarkInfo = makeStruct(fields);

    JavaInfo javaInfo = JavaInfo.wrap(starlarkInfo);

    assertThat(javaInfo).isNotNull();
    assertThat(javaInfo.plugins()).isNotNull();
    assertThat(javaInfo.plugins().processorClasses().toList()).containsExactly("c1", "c2", "c3");
    assertThat(prettyArtifactNames(javaInfo.plugins().processorClasspath())).containsExactly("f1");
  }

  private static ImmutableMap.Builder<String, Object> getBuilderWithMandataryFields() {
    Depset emptyDepset = Depset.of(Artifact.class, NestedSetBuilder.create(Order.STABLE_ORDER));
    return ImmutableMap.<String, Object>builder()
        .put("transitive_native_libraries", emptyDepset)
        .put("compile_jars", emptyDepset)
        .put("full_compile_jars", emptyDepset)
        .put("transitive_compile_time_jars", emptyDepset)
        .put("transitive_runtime_jars", emptyDepset)
        .put("_transitive_full_compile_time_jars", emptyDepset)
        .put("_compile_time_java_dependencies", emptyDepset)
        .put("plugins", JavaPluginData.empty())
        .put("api_generating_plugins", JavaPluginData.empty())
        .put("java_outputs", StarlarkList.empty())
        .put("transitive_source_jars", emptyDepset)
        .put("source_jars", StarlarkList.empty())
        .put("runtime_output_jars", StarlarkList.empty());
  }

  private Artifact createArtifact(String path) throws IOException {
    Path execRoot = scratch.dir("/");
    ArtifactRoot root = ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "fake-root");
    return ActionsTestUtil.createArtifact(root, path);
  }

  private static <T> Depset makeDepset(Class<T> clazz, T... elems) {
    return Depset.of(clazz, NestedSetBuilder.create(Order.STABLE_ORDER, elems));
  }

  private static StarlarkInfo makeStruct(Map<String, Object> struct) {
    return StructProvider.STRUCT.create(struct, "");
  }

  private RuleBuilder ruleBuilder() {
    return new RuleBuilder();
  }

  private class RuleBuilder {
    private boolean useIJar = false;
    private boolean stampJar;
    private boolean neverLink = false;
    private boolean sourceFiles = false;

    @CanIgnoreReturnValue
    private RuleBuilder withIJar() {
      useIJar = true;
      return this;
    }

    @CanIgnoreReturnValue
    private RuleBuilder withStampJar() {
      stampJar = true;
      return this;
    }

    @CanIgnoreReturnValue
    private RuleBuilder withNeverLink() {
      neverLink = true;
      return this;
    }

    @CanIgnoreReturnValue
    private RuleBuilder withSourceFiles() {
      sourceFiles = true;
      return this;
    }

    private String[] newJavaInfo() {
      assertThat(useIJar && stampJar).isFalse();
      ImmutableList.Builder<String> lines = ImmutableList.builder();
      lines.add(
          "load('@rules_java//java:defs.bzl', 'java_common', 'JavaInfo',"
              + " 'JavaPluginInfo')",
          "result = provider()",
          "def _impl(ctx):",
          "  ctx.actions.write(ctx.outputs.output_jar, 'JavaInfo API Test', is_executable=False) ",
          "  dp = [dep[java_common.provider] for dep in ctx.attr.dep]",
          "  dp_runtime = [dep[java_common.provider] for dep in ctx.attr.dep_runtime]",
          "  dp_exports = [dep[java_common.provider] for dep in ctx.attr.dep_exports]",
          "  dp_exported_plugins = [dep[JavaPluginInfo] for dep in ctx.attr.dep_exported_plugins]",
          "  dp_libs = [dep[CcInfo] for dep in ctx.attr.cc_dep]");

      if (useIJar) {
        lines.add(
            "  compile_jar = java_common.run_ijar(",
            "    ctx.actions,",
            "    jar = ctx.outputs.output_jar,",
            "    java_toolchain = ctx.attr._toolchain[java_common.JavaToolchainInfo],",
            "  )");
      } else if (stampJar) {
        lines.add(
            "  compile_jar = java_common.stamp_jar(",
            "    ctx.actions,",
            "    jar = ctx.outputs.output_jar,",
            "    target_label = ctx.label,",
            "    java_toolchain = ctx.attr._toolchain[java_common.JavaToolchainInfo],",
            "  )");
      } else {
        lines.add("  compile_jar = ctx.outputs.output_jar");
      }
      if (sourceFiles) {
        lines.add(
            "  source_jar = java_common.pack_sources(",
            "    ctx.actions,",
            "    output_source_jar = ",
            "      ctx.actions.declare_file(ctx.outputs.output_jar.basename[:-4] + '-src.jar'),",
            "    sources = ctx.files.sources,",
            "    source_jars = ctx.files.source_jars,",
            "    java_toolchain = ctx.attr._toolchain[java_common.JavaToolchainInfo],",
            ")");
      } else {
        lines.add(
            "  if ctx.files.source_jars:",
            "    source_jar = list(ctx.files.source_jars)[0]",
            "  else:",
            "    source_jar = None");
      }
      lines.add(
          "  javaInfo = JavaInfo(",
          "    output_jar = ctx.outputs.output_jar,",
          "    compile_jar = compile_jar,",
          "    source_jar = source_jar,",
          neverLink ? "    neverlink = True," : "",
          "    deps = dp,",
          "    runtime_deps = dp_runtime,",
          "    exports = dp_exports,",
          "    exported_plugins = dp_exported_plugins,",
          "    jdeps = ctx.file.jdeps,",
          "    compile_jdeps = ctx.file.compile_jdeps,",
          "    generated_class_jar = ctx.file.generated_class_jar,",
          "    generated_source_jar = ctx.file.generated_source_jar,",
          "    native_headers_jar = ctx.file.native_headers_jar,",
          "    manifest_proto = ctx.file.manifest_proto,",
          "    native_libraries = dp_libs,");
      if (analysisMock.isThisBazel()) {
        lines.add(
            "    add_exports = ctx.attr.add_exports,", //
            "    add_opens = ctx.attr.add_opens,");
      }
      lines.add(
          "  )", //
          "  return [result(property = javaInfo)]");
      return lines.build().toArray(new String[] {});
    }

    private void build() throws Exception {
      if (useIJar || stampJar || sourceFiles) {
        JavaTestUtil.writeBuildFileForJavaToolchain(scratch);
      }

      ImmutableList.Builder<String> lines = ImmutableList.builder();
      lines.add(newJavaInfo());
      lines.add(
          "my_rule = rule(",
          "  implementation = _impl,",
          "  toolchains = ['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
          "  attrs = {",
          "    'dep' : attr.label_list(),",
          "    'cc_dep' : attr.label_list(),",
          "    'dep_runtime' : attr.label_list(),",
          "    'dep_exports' : attr.label_list(),",
          "    'dep_exported_plugins' : attr.label_list(),",
          "    'output_jar' : attr.output(mandatory=True),",
          "    'source_jars' : attr.label_list(allow_files=['.jar']),",
          "    'sources' : attr.label_list(allow_files=['.java']),",
          "    'jdeps' : attr.label(allow_single_file=True),",
          "    'compile_jdeps' : attr.label(allow_single_file=True),",
          "    'generated_class_jar' : attr.label(allow_single_file=True),",
          "    'generated_source_jar' : attr.label(allow_single_file=True),",
          "    'native_headers_jar' : attr.label(allow_single_file=True),",
          "    'manifest_proto' : attr.label(allow_single_file=True),",
          "    'add_exports' : attr.string_list(),",
          "    'add_opens' : attr.string_list(),",
          useIJar || stampJar || sourceFiles
              ? "    '_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),"
              : "",
          "  }",
          ")");

      scratch.file("foo/extension.bzl", lines.build().toArray(new String[] {}));
    }
  }

  private JavaInfo fetchJavaInfo() throws Exception {
    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:my_starlark_rule");
    StructImpl info =
        (StructImpl)
            myRuleTarget.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonical("//foo:extension.bzl")), "result"));

    return JavaInfo.wrap(info.getValue("property", Info.class));
  }
}
