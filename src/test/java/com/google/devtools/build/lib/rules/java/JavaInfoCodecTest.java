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

package com.google.devtools.build.lib.rules.java;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.analysis.config.BuildOptions.MapBackedChecksumCache;
import com.google.devtools.build.lib.analysis.config.BuildOptions.OptionsChecksumCache;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.skyframe.serialization.testutils.Dumper;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationDepsUtils;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Root;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class JavaInfoCodecTest extends BuildViewTestCase {

  @Test
  public void emptyJavaInfo_canBeSerializedAndDeserialized() throws Exception {
    new SerializationTester(JavaInfo.EMPTY_JAVA_INFO_FOR_TESTING)
        .makeMemoizingAndAllowFutureBlocking(/* allowFutureBlocking= */ true)
        .setVerificationFunction((in, out) -> assertThat(in).isEqualTo(out))
        .runTests();
  }

  @Test
  public void javaInfo_canBeSerializedAndDeserialized() throws Exception {
    scratch.file(
        "java/com/google/test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "a",
            srcs = ["a.java"],
            deps = [":b", ":c"],
        )
        java_library(
            name = "b",
            srcs = ["b.java"],
            deps = [":d"],
        )
        java_library(
            name = "c",
            srcs = ["c.java"],
            deps = [":d"],
        )
        java_library(
            name = "d",
            srcs = ["d.java"],
        )
        """);

    new SerializationTester(JavaInfo.getJavaInfo(getConfiguredTarget("//java/com/google/test:a")))
        .makeMemoizingAndAllowFutureBlocking(/* allowFutureBlocking= */ true)
        .addDependency(FileSystem.class, scratch.getFileSystem())
        .addDependency(OptionsChecksumCache.class, new MapBackedChecksumCache())
        .addDependency(
            Root.RootCodecDependencies.class,
            new Root.RootCodecDependencies(Root.absoluteRoot(scratch.getFileSystem())))
        .addDependencies(SerializationDepsUtils.SERIALIZATION_DEPS_FOR_TEST)
        .setVerificationFunction(
            (in, out) -> {
              JavaInfo inInfo = (JavaInfo) in;
              JavaInfo outInfo = (JavaInfo) out;
              assertThat(inInfo.getCreationLocation()).isEqualTo(outInfo.getCreationLocation());
              assertThat(inInfo.getDirectRuntimeJars()).isNotEmpty();
              assertThat(inInfo.getDirectRuntimeJars()).isEqualTo(outInfo.getDirectRuntimeJars());

              JavaCompilationArgsProvider inProvider =
                  inInfo.getProvider(JavaCompilationArgsProvider.class);
              JavaCompilationArgsProvider outProvider =
                  outInfo.getProvider(JavaCompilationArgsProvider.class);
              assertThat(inProvider.runtimeJars().toList()).hasSize(4);
              assertThat(Dumper.dumpStructureWithEquivalenceReduction(inProvider.runtimeJars()))
                  .isEqualTo(
                      Dumper.dumpStructureWithEquivalenceReduction(outProvider.runtimeJars()));
            })
        .runTests();
  }
}
