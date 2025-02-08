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

package com.google.devtools.build.lib.rules.proto;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.packages.BuiltinRestriction;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.starlarkbuildapi.proto.ProtoCommonApi;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.Location;

/** Protocol buffers support for Starlark. */
public class BazelProtoCommon implements ProtoCommonApi {
  public static final BazelProtoCommon INSTANCE = new BazelProtoCommon();

  protected BazelProtoCommon() {}

  @StarlarkMethod(
      name = "incompatible_enable_proto_toolchain_resolution",
      useStarlarkThread = true,
      documented = false)
  public boolean getDefineProtoToolchains(StarlarkThread thread) throws EvalException {
    BuiltinRestriction.failIfCalledOutsideAllowlist(thread, ImmutableSet.of());
    return thread
        .getSemantics()
        .getBool(BuildLanguageOptions.INCOMPATIBLE_ENABLE_PROTO_TOOLCHAIN_RESOLUTION);
  }

  @StarlarkMethod(name = "external_proto_infos", useStarlarkThread = true, documented = false)
  public StarlarkList<StarlarkProvider> getExternalProtoInfos(StarlarkThread thread)
      throws EvalException {
    BuiltinRestriction.failIfCalledOutsideAllowlist(thread, ImmutableSet.of());
    return externalProtoInfos;
  }

  private static final StarlarkList<StarlarkProvider> externalProtoInfos =
      StarlarkList.immutableCopyOf(
          ProtoConstants.EXTERNAL_PROTO_INFO_KEYS.stream()
              .map(
                  key ->
                      StarlarkProvider.builder(Location.BUILTIN)
                          .buildExported(new StarlarkProvider.Key(key, "ProtoInfo")))
              .collect(toImmutableList()));
}
