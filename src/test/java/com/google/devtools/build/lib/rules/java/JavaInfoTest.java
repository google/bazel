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

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link JavaInfo}. */
@RunWith(JUnit4.class)
public class JavaInfoTest {

  @Test
  public void getTransitiveRuntimeJars_noJavaCompilationArgsProvider() {
    assertThat(JavaInfo.EMPTY_JAVA_INFO_FOR_TESTING.getTransitiveRuntimeJars().isEmpty()).isTrue();
  }

  @Test
  public void getTransitiveCompileTimeJarsJars_noJavaCompilationArgsProvider() {
    assertThat(JavaInfo.EMPTY_JAVA_INFO_FOR_TESTING.getTransitiveCompileTimeJars().isEmpty())
        .isTrue();
  }

  @Test
  public void getCompileTimeJarsJars_noJavaCompilationArgsProvider() {
    assertThat(JavaInfo.EMPTY_JAVA_INFO_FOR_TESTING.getCompileTimeJars().isEmpty()).isTrue();
  }

  @Test
  public void getFullCompileTimeJarsJars_noJavaCompilationArgsProvider() {
    assertThat(JavaInfo.EMPTY_JAVA_INFO_FOR_TESTING.getFullCompileTimeJars().isEmpty()).isTrue();
  }

  @Test
  public void getSourceJars_noJavaSourceJarsProvider() {
    assertThat(JavaInfo.EMPTY_JAVA_INFO_FOR_TESTING.getSourceJars()).isEmpty();
  }
}
