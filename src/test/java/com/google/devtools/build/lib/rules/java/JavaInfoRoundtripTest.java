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

package com.google.devtools.build.lib.rules.java;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Predicate;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.Structure;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests if JavaInfo identical to one returned by Java rules can be constructed. */
@RunWith(JUnit4.class)
public class JavaInfoRoundtripTest extends BuildViewTestCase {
  /** A rule to convert JavaInfo to a structure having only string values. */
  @Before
  public void javaInfoToDict() throws Exception {
    mockToolsConfig.create("tools/build_defs/inspect/BUILD");
    mockToolsConfig.copyTool(
        TestConstants.BAZEL_REPO_SCRATCH + "tools/build_defs/inspect/struct_to_dict.bzl",
        "tools/build_defs/inspect/struct_to_dict.bzl");

    scratch.file(
        "javainfo/javainfo_to_dict.bzl",
        """
        load("@rules_java//java/common:java_info.bzl", "JavaInfo")
        load("//tools/build_defs/inspect:struct_to_dict.bzl", "struct_to_dict")
        Info = provider()
        def _impl(ctx):
            return Info(result = struct_to_dict(ctx.attr.dep[JavaInfo], 10))

        javainfo_to_dict = rule(_impl, attrs = {"dep": attr.label()})
        """);
  }

  /** A simple rule that calls JavaInfo constructor using identical attribute as java_library. */
  @Before
  public void constructJavaInfo() throws Exception {
    if (!getAnalysisMock().isThisBazel()) {
      setBuildLanguageOptions("--experimental_google_legacy_api");
    }
    scratch.file(
        "foo/construct_javainfo.bzl",
        """
        load("@rules_java//java:defs.bzl", "JavaInfo")
        def _impl(ctx):
            OUTS = {
                "lib": "lib%s.jar",
                "hjar": "lib%s-hjar.jar",
                "src": "lib%s-src.jar",
                "compile_jdeps": "lib%s-hjar.jdeps",
                "genclass": "lib%s-gen.jar",
                "gensource": "lib%s-gensrc.jar",
                "jdeps": "lib%s.jdeps",
                "manifest": "lib%s.jar_manifest_proto",
                "headers": "lib%s-native-header.jar",
            }
            for file, name in OUTS.items():
                OUTS[file] = ctx.actions.declare_file(name % ctx.label.name)
                ctx.actions.write(OUTS[file], "")

            java_info = JavaInfo(
                output_jar = OUTS["lib"],
                compile_jar = OUTS["hjar"],
                source_jar = OUTS["src"],
                compile_jdeps = OUTS["compile_jdeps"],
                generated_class_jar = ctx.attr.plugins and OUTS["genclass"] or None,
                generated_source_jar = ctx.attr.plugins and OUTS["gensource"] or None,
                manifest_proto = OUTS["manifest"],
                native_headers_jar = OUTS["headers"],
                deps = [d[JavaInfo] for d in ctx.attr.deps],
                runtime_deps = [d[JavaInfo] for d in ctx.attr.runtime_deps],
                exports = [d[JavaInfo] for d in ctx.attr.exports],
                jdeps = OUTS["jdeps"],
            )
            return [java_info]

        construct_javainfo = rule(
            implementation = _impl,
            attrs = {
                "srcs": attr.label_list(allow_files = True),
                "deps": attr.label_list(),
                "runtime_deps": attr.label_list(),
                "exports": attr.label_list(),
                "plugins": attr.bool(default = False),
            },
            fragments = ["java"],
        )
        """);
  }

  /** For a given target providing JavaInfo returns a Starlark Dict with String values */
  private Dict<Object, Object> getDictFromJavaInfo(String packageName, String javaInfoTarget)
      throws Exception {
    // Because we're overwriting files to have identical names, we need to invalidate them.
    skyframeExecutor.invalidateFilesUnderPathForTesting(
        reporter,
        new ModifiedFileSet.Builder().modify(PathFragment.create(packageName + "/BUILD")).build(),
        Root.fromPath(rootDirectory));

    scratch.deleteFile("javainfo/BUILD");
    ConfiguredTarget dictTarget =
        scratchConfiguredTarget(
            "javainfo",
            "javainfo",
            "load(':javainfo_to_dict.bzl', 'javainfo_to_dict')",
            "javainfo_to_dict(",
            "  name = 'javainfo',",
            "  dep = '//" + packageName + ':' + javaInfoTarget + "',",
            ")");
    StarlarkInfo dictInfo = getStarlarkProvider(dictTarget, "Info");
    @SuppressWarnings("unchecked") // deserialization
    Dict<Object, Object> javaInfo = (Dict<Object, Object>) dictInfo.getValue("result");
    return deepStripAttributes(javaInfo, attr -> attr.startsWith("_"));
  }

  @SuppressWarnings("unchecked")
  private static <T> T deepStripAttributes(T obj, Predicate<String> shouldRemove)
      throws EvalException {
    if (obj == null) {
      return null;
    } else if (obj instanceof StarlarkList) {
      ImmutableList.Builder<Object> builder = ImmutableList.builder();
      for (Object item : (StarlarkList<Object>) obj) {
        builder.add(deepStripAttributes(item, shouldRemove));
      }
      return (T) StarlarkList.immutableCopyOf(builder.build());
    } else if (obj instanceof Structure structure) {
      for (String fieldName : structure.getFieldNames()) {
        Dict.Builder<String, Object> builder = Dict.builder();
        if (!shouldRemove.test(fieldName)) {
          builder.put(
              fieldName, deepStripAttributes(((Structure) obj).getValue(fieldName), shouldRemove));
        }
        return (T) StructProvider.STRUCT.create(builder.buildImmutable(), "");
      }
    } else if (obj instanceof Dict) {
      Dict.Builder<Object, Object> builder = Dict.builder();
      for (Entry<Object, Object> e :
          Dict.cast(obj, Object.class, Object.class, "dict").entrySet()) {
        if (!(e.getKey() instanceof String && shouldRemove.test((String) e.getKey()))) {
          builder.put(e.getKey(), deepStripAttributes(e.getValue(), shouldRemove));
        }
      }
      return (T) builder.buildImmutable();
    }
    return obj;
  }

  private Dict<Object, Object> removeCompilationInfo(Dict<Object, Object> javaInfo) {
    return Dict.builder().putAll(javaInfo).put("compilation_info", Starlark.NONE).buildImmutable();
  }

  private Dict<Object, Object> removeAnnotationClasses(Dict<Object, Object> javaInfo) {
    @SuppressWarnings("unchecked") // safe by specification
    Dict<Object, Object> annotationProcessing =
        (Dict<Object, Object>) javaInfo.get("annotation_processing");

    annotationProcessing =
        Dict.builder()
            .putAll(annotationProcessing)
            .put("enabled", false)
            .put("processor_classnames", StarlarkList.immutableOf())
            .put("processor_classpath", StarlarkList.immutableOf())
            .buildImmutable();
    return Dict.builder()
        .putAll(javaInfo)
        .put("annotation_processing", annotationProcessing)
        .buildImmutable();
  }

  @Test
  public void dictFromJavaInfo_nonEmpty() throws Exception {
    scratch.overwriteFile(
        "foo/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'java_lib', srcs = ['A.java'])");

    Dict<Object, Object> javaInfo = getDictFromJavaInfo("foo", "java_lib");

    assertThat((Map<?, ?>) javaInfo).isNotEmpty();
  }

  @Test
  public void dictFromJavaInfo_detectsDifference() throws Exception {

    scratch.overwriteFile(
        "foo/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'java_lib', srcs = ['A.java'])");
    Dict<Object, Object> javaInfoA = getDictFromJavaInfo("foo", "java_lib");

    scratch.overwriteFile(
        "foo/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'java_lib2', srcs = ['A.java'])");
    Dict<Object, Object> javaInfoB = getDictFromJavaInfo("foo", "java_lib2");

    assertThat((Map<?, ?>) javaInfoA).isNotEqualTo(javaInfoB);
  }

  @Test
  public void roundtripJavainfo_srcs() throws Exception {

    scratch.overwriteFile(
        "foo/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'java_lib', srcs = ['A.java'])");
    Dict<Object, Object> javaInfoA = getDictFromJavaInfo("foo", "java_lib");
    scratch.overwriteFile(
        "foo/BUILD",
        """
        load("//foo:construct_javainfo.bzl", "construct_javainfo")

        construct_javainfo(
            name = "java_lib",
            srcs = ["A.java"],
        )
        """);
    Dict<Object, Object> javaInfoB = getDictFromJavaInfo("foo", "java_lib");

    javaInfoA = removeCompilationInfo(javaInfoA);
    assertThat((Map<?, ?>) javaInfoB).isEqualTo(javaInfoA);
  }

  @Test
  public void roundtripJavaInfo_deps() throws Exception {
    scratch.file(
        "bar/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'javalib', srcs = ['A.java'])");

    scratch.overwriteFile(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "java_lib",
            srcs = ["A.java"],
            deps = ["//bar:javalib"],
        )
        """);
    Dict<Object, Object> javaInfoA = getDictFromJavaInfo("foo", "java_lib");
    scratch.overwriteFile(
        "foo/BUILD",
        """
        load("//foo:construct_javainfo.bzl", "construct_javainfo")

        construct_javainfo(
            name = "java_lib",
            srcs = ["A.java"],
            deps = ["//bar:javalib"],
        )
        """);
    Dict<Object, Object> javaInfoB = getDictFromJavaInfo("foo", "java_lib");

    javaInfoA = removeCompilationInfo(javaInfoA);
    assertThat((Map<?, ?>) javaInfoB).isEqualTo(javaInfoA);
  }

  @Test
  public void roundtipJavaInfo_runtimeDeps() throws Exception {
    scratch.file(
        "bar/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'deplib', srcs = ['A.java'])");

    scratch.overwriteFile(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "java_lib",
            srcs = ["A.java"],
            runtime_deps = ["//bar:deplib"],
        )
        """);
    Dict<Object, Object> javaInfoA = getDictFromJavaInfo("foo", "java_lib");
    scratch.overwriteFile(
        "foo/BUILD",
        """
        load("//foo:construct_javainfo.bzl", "construct_javainfo")

        construct_javainfo(
            name = "java_lib",
            srcs = ["A.java"],
            runtime_deps = ["//bar:deplib"],
        )
        """);
    Dict<Object, Object> javaInfoB = getDictFromJavaInfo("foo", "java_lib");

    javaInfoA = removeCompilationInfo(javaInfoA);
    assertThat((Map<?, ?>) javaInfoB).isEqualTo(javaInfoA);
  }

  @Test
  public void roundtipJavaInfo_exports() throws Exception {
    scratch.file(
        "bar/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'exportlib', srcs = ['A.java'])");

    scratch.overwriteFile(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "java_lib",
            srcs = ["A.java"],
            exports = ["//bar:exportlib"],
        )
        """);
    Dict<Object, Object> javaInfoA = getDictFromJavaInfo("foo", "java_lib");
    scratch.overwriteFile(
        "foo/BUILD",
        """
        load("//foo:construct_javainfo.bzl", "construct_javainfo")

        construct_javainfo(
            name = "java_lib",
            srcs = ["A.java"],
            exports = ["//bar:exportlib"],
        )
        """);
    Dict<Object, Object> javaInfoB = getDictFromJavaInfo("foo", "java_lib");

    javaInfoA = removeCompilationInfo(javaInfoA);
    assertThat((Map<?, ?>) javaInfoB).isEqualTo(javaInfoA);
  }

  @Test
  public void roundtipJavaInfo_plugin() throws Exception {
    scratch.file(
        "bar/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_plugin')",
        "java_plugin(name = 'plugin', srcs = ['A.java'], processor_class = 'bar.Main')");

    scratch.overwriteFile(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "java_lib",
            srcs = ["A.java"],
            plugins = ["//bar:plugin"],
        )
        """);
    Dict<Object, Object> javaInfoA = getDictFromJavaInfo("foo", "java_lib");
    scratch.overwriteFile(
        "foo/BUILD",
        """
        load("//foo:construct_javainfo.bzl", "construct_javainfo")

        construct_javainfo(
            name = "java_lib",
            srcs = ["A.java"],
            plugins = True,
        )
        """);
    Dict<Object, Object> javaInfoB = getDictFromJavaInfo("foo", "java_lib");

    javaInfoA = removeCompilationInfo(javaInfoA);
    javaInfoA = removeAnnotationClasses(javaInfoA);
    assertThat((Map<?, ?>) javaInfoB).isEqualTo(javaInfoA);
  }
}
