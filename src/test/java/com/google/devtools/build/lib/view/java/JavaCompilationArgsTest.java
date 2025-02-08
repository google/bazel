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

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.rules.java.JavaCompilationArgsProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link JavaCompilationArgsProvider}. */
@RunWith(JUnit4.class)
public final class JavaCompilationArgsTest extends BuildViewTestCase {

  @Test
  public void testSimple() throws Exception {
    JavaCompilationArgsProvider args = createArgs();
    assertThat(getOnlyJarName(args.transitiveCompileTimeJars())).isEqualTo("compiletime.jar");
    assertThat(getOnlyJarName(args.runtimeJars())).isEqualTo("runtime.jar");
  }

  private JavaCompilationArgsProvider createArgs() throws Exception {
    Artifact compileTimeJar = getSourceArtifact("compiletime.jar");
    Artifact runtimeJar = getSourceArtifact("runtime.jar");

    return JavaCompilationArgsProvider.builder()
        .addDirectCompileTimeJar(/* interfaceJar= */ compileTimeJar, /* fullJar= */ compileTimeJar)
        .addRuntimeJar(runtimeJar)
        .build();
  }

  private static String getOnlyJarName(NestedSet<Artifact> artifacts) {
    return artifacts.getSingleton().getExecPathString();
  }
}
