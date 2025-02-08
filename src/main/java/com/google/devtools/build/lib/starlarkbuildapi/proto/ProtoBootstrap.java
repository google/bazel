// Copyright 2019 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.starlarkbuildapi.proto;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.starlarkbuildapi.core.Bootstrap;
import com.google.devtools.build.lib.starlarkbuildapi.core.ContextAndFlagGuardedValue;
import com.google.devtools.build.lib.starlarkbuildapi.stubs.ProviderStub;

/** A {@link Bootstrap} for Starlark objects related to protocol buffers. */
public class ProtoBootstrap implements Bootstrap {
  private static final ImmutableSet<PackageIdentifier> allowedRepositories =
      ImmutableSet.of(
          PackageIdentifier.createUnchecked("_builtins", ""),
          PackageIdentifier.createUnchecked("rules_proto", ""),
          PackageIdentifier.createUnchecked("", "tools/build_defs/proto"));

  /** The name of the proto info provider in Starlark. */
  public static final String PROTO_INFO_STARLARK_NAME = "ProtoInfo";

  /** The name of the proto namespace in Starlark. */
  public static final String PROTO_COMMON_NAME = "proto_common";

  public static final String PROTO_COMMON_SECOND_NAME = "proto_common_do_not_use";

  private final Object protoCommon;

  public ProtoBootstrap(Object protoCommon) {
    this.protoCommon = protoCommon;
  }

  @Override
  public void addBindingsToBuilder(ImmutableMap.Builder<String, Object> builder) {
    builder.put(
        PROTO_INFO_STARLARK_NAME,
        ContextAndFlagGuardedValue.onlyInAllowedReposOrWhenIncompatibleFlagIsFalse(
            BuildLanguageOptions.INCOMPATIBLE_STOP_EXPORTING_LANGUAGE_MODULES,
            new ProviderStub(),
            allowedRepositories));
    builder.put(
        PROTO_COMMON_NAME,
        ContextAndFlagGuardedValue.onlyInAllowedReposOrWhenIncompatibleFlagIsFalse(
            BuildLanguageOptions.INCOMPATIBLE_STOP_EXPORTING_LANGUAGE_MODULES,
            protoCommon,
            allowedRepositories));
    builder.put(
        PROTO_COMMON_SECOND_NAME,
        ContextAndFlagGuardedValue.onlyInAllowedReposOrWhenIncompatibleFlagIsFalse(
            BuildLanguageOptions.INCOMPATIBLE_STOP_EXPORTING_LANGUAGE_MODULES,
            protoCommon,
            allowedRepositories));
  }
}
