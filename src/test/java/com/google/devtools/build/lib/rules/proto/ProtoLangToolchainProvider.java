// Copyright 2016 The Bazel Authors. All rights reserved.
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

import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuiltins;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkList;

// Note: AutoValue v1.4-rc1 has AutoValue.CopyAnnotations which makes it work with Starlark. No need
// to un-AutoValue this class to expose it to Starlark.
/**
 * Specifies how to generate language-specific code from .proto files. Used by LANG_proto_library
 * rules.
 */
@AutoValue
public abstract class ProtoLangToolchainProvider {
  private static final String PROVIDER_NAME = "ProtoLangToolchainInfo";
  private static final StarlarkProvider.Key builtinProtoLangToolchainKey =
      new StarlarkProvider.Key(
          keyForBuiltins(
              Label.parseCanonicalUnchecked("@_builtins//:common/proto/proto_common.bzl")),
          PROVIDER_NAME);

  public static final StarlarkProvider.Key protobufProtoLangToolchainKey =
      new StarlarkProvider.Key(ProtoConstants.PROTO_LANG_TOOLCHAIN_INFO, PROVIDER_NAME);

  // Format string used when passing output to the plugin used by proto compiler.
  public abstract String outReplacementFormatFlag();

  // Format string used when passing plugin to proto compiler.
  @Nullable
  public abstract String pluginFormatFlag();

  // Proto compiler plugin.
  @Nullable
  public abstract FilesToRunProvider pluginExecutable();

  @Nullable
  public abstract TransitiveInfoCollection runtime();

  // Proto compiler.
  public abstract FilesToRunProvider protoc();

  public abstract ImmutableList<String> protocOpts();

  // Progress message to set on the proto compiler action.
  public abstract String progressMessage();

  // Mnemonic to set on the proto compiler action.
  public abstract String mnemonic();

  public static ProtoLangToolchainProvider get(TransitiveInfoCollection prerequisite) {
    StarlarkInfo provider = (StarlarkInfo) prerequisite.get(builtinProtoLangToolchainKey);
    if (provider == null) {
      provider = (StarlarkInfo) prerequisite.get(protobufProtoLangToolchainKey);
    }
    return wrapStarlarkProviderWithNativeProvider(provider);
  }

  @Nullable
  @SuppressWarnings("unchecked")
  static ProtoLangToolchainProvider wrapStarlarkProviderWithNativeProvider(StarlarkInfo provider) {
    if (provider != null) {
      try {
        return new AutoValue_ProtoLangToolchainProvider(
            provider.getValue("out_replacement_format_flag", String.class),
            provider.getNoneableValue("plugin_format_flag", String.class),
            provider.getNoneableValue("plugin", FilesToRunProvider.class),
            provider.getNoneableValue("runtime", TransitiveInfoCollection.class),
            provider.getValue("proto_compiler", FilesToRunProvider.class),
            ImmutableList.copyOf((StarlarkList<String>) provider.getValue("protoc_opts")),
            provider.getValue("progress_message", String.class),
            provider.getValue("mnemonic", String.class));
      } catch (EvalException e) {
        return null;
      }
    }
    return null;
  }
}
