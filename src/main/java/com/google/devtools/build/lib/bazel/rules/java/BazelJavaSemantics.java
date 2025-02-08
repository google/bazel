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

package com.google.devtools.build.lib.bazel.rules.java;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.constraints.ConstraintConstants;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.rules.java.JavaSemantics;
import com.google.devtools.build.lib.rules.java.JavaUtil;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.List;

/** Semantics for Bazel Java rules */
public class BazelJavaSemantics implements JavaSemantics {

  /**
   * {@code C.UTF-8} is now the universally accepted standard UTF-8 locale, to the point where some
   * minimal Linux distributions no longer ship with {@code en_US.UTF-8}. macOS doesn't have it
   * though.
   */
  private static final ImmutableMap<String, String> DEFAULT_UTF8_ENVIRONMENT =
      ImmutableMap.of("LC_CTYPE", "C.UTF-8");

  /** macOS doesn't have {@code C.UTF-8}, so we use {@code en_US.UTF-8} instead. */
  private static final ImmutableMap<String, String> MACOS_UTF8_ENVIRONMENT =
      ImmutableMap.of("LC_CTYPE", "en_US.UTF-8");

  @SerializationConstant public static final BazelJavaSemantics INSTANCE = new BazelJavaSemantics();

  private BazelJavaSemantics() {}

  private static final String JAVA_TOOLCHAIN_TYPE =
      Label.parseCanonicalUnchecked("@bazel_tools//tools/jdk:toolchain_type").toString();
  private static final Label JAVA_RUNITME_TOOLCHAIN_TYPE =
      Label.parseCanonicalUnchecked("@bazel_tools//tools/jdk:runtime_toolchain_type");

  @Override
  public String getJavaToolchainType() {
    return JAVA_TOOLCHAIN_TYPE;
  }

  @Override
  public Label getJavaRuntimeToolchainType() {
    return JAVA_RUNITME_TOOLCHAIN_TYPE;
  }

  @Override
  public PathFragment getDefaultJavaResourcePath(PathFragment path) {
    // Look for src/.../resources to match Maven repository structure.
    List<String> segments = path.splitToListOfSegments();
    for (int i = 0; i < segments.size() - 2; ++i) {
      if (segments.get(i).equals("src") && segments.get(i + 2).equals("resources")) {
        return path.subFragment(i + 3);
      }
    }
    PathFragment javaPath = JavaUtil.getJavaPath(path);
    return javaPath == null ? path : javaPath;
  }

  @Override
  public ImmutableMap<String, String> utf8Environment(PlatformInfo executionPlatform) {
    return ConstraintConstants.getOsFromConstraints(executionPlatform.constraints()) == OS.DARWIN
        ? MACOS_UTF8_ENVIRONMENT
        : DEFAULT_UTF8_ENVIRONMENT;
  }
}
