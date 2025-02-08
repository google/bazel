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
// limitations under the License

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;

import com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.InterimModuleBuilder;
import com.google.devtools.build.lib.bazel.bzlmod.InterimModule.DepSpec;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link InterimModule}. */
@RunWith(JUnit4.class)
public class InterimModuleTest {

  @Test
  public void withDepSpecsTransformed() throws Exception {
    assertThat(
            InterimModuleBuilder.create("", "")
                .addDep("dep_foo", createModuleKey("foo", "1.0"))
                .addDep("dep_bar", createModuleKey("bar", "2.0"))
                .build()
                .withDepSpecsTransformed(
                    depSpec ->
                        DepSpec.fromModuleKey(
                            createModuleKey(
                                depSpec.name() + "_new",
                                depSpec.version().getNormalized() + ".1"))))
        .isEqualTo(
            InterimModuleBuilder.create("", "")
                .addDep("dep_foo", createModuleKey("foo_new", "1.0.1"))
                .addOriginalDep("dep_foo", createModuleKey("foo", "1.0"))
                .addDep("dep_bar", createModuleKey("bar_new", "2.0.1"))
                .addOriginalDep("dep_bar", createModuleKey("bar", "2.0"))
                .build());
  }
}
