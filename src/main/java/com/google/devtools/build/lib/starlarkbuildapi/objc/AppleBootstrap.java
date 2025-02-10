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

package com.google.devtools.build.lib.starlarkbuildapi.objc;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.starlarkbuildapi.core.Bootstrap;
import com.google.devtools.build.lib.starlarkbuildapi.core.ContextAndFlagGuardedValue;
import net.starlark.java.eval.Starlark;

/** {@link Bootstrap} for Starlark objects related to apple rules. */
public class AppleBootstrap implements Bootstrap {

  private static final ImmutableSet<PackageIdentifier> allowedRepositories =
      ImmutableSet.of(
          PackageIdentifier.createUnchecked("_builtins", ""),
          PackageIdentifier.createUnchecked("apple_support", ""),
          PackageIdentifier.createUnchecked("bazel_tools", ""),
          PackageIdentifier.createUnchecked("build_bazel_rules_apple", ""), // alias for rules_apple
          PackageIdentifier.createUnchecked("build_bazel_rules_swift", ""), // alias for rules_swift
          PackageIdentifier.createUnchecked("io_bazel_rules_go", ""), // alias for rules_go
          PackageIdentifier.createUnchecked("local_config_cc", ""),
          PackageIdentifier.createUnchecked("rules_apple", ""),
          PackageIdentifier.createUnchecked("rules_cc", ""),
          PackageIdentifier.createUnchecked("rules_go", ""),
          PackageIdentifier.createUnchecked("rules_ios", ""),
          PackageIdentifier.createUnchecked("rules_swift", ""),
          PackageIdentifier.createUnchecked("stardoc", ""),
          PackageIdentifier.createUnchecked("tulsi", ""),
          PackageIdentifier.createUnchecked("", "test_starlark"),
          PackageIdentifier.createUnchecked("", "tools/osx"));

  @Override
  public void addBindingsToBuilder(ImmutableMap.Builder<String, Object> builder) {
    builder.put(
        "apple_common",
        ContextAndFlagGuardedValue.onlyInAllowedReposOrWhenIncompatibleFlagIsFalse(
            BuildLanguageOptions.INCOMPATIBLE_STOP_EXPORTING_LANGUAGE_MODULES,
            Starlark.NONE,
            allowedRepositories));
  }
}
