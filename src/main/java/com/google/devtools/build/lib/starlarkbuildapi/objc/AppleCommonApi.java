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
import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkRuleContextApi;
import com.google.devtools.build.lib.starlarkbuildapi.apple.DottedVersionApi;
import com.google.devtools.build.lib.starlarkbuildapi.core.ProviderApi;
import com.google.devtools.build.lib.starlarkbuildapi.core.StructApi;
import com.google.devtools.build.lib.starlarkbuildapi.cpp.CcInfoApi;
import com.google.devtools.build.lib.starlarkbuildapi.platform.ConstraintValueInfoApi;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkValue;

/** Interface for a module with useful functions for creating apple-related rule implementations. */
@StarlarkBuiltin(
    name = "apple_common",
    category = DocCategory.TOP_LEVEL_MODULE,
    doc = "Functions for Starlark to access internals of the apple rule implementations.")
public interface AppleCommonApi<
        ConstraintValueT extends ConstraintValueInfoApi,
        StarlarkRuleContextT extends StarlarkRuleContextApi<ConstraintValueT>,
        CcInfoApiT extends CcInfoApi<?>>
    extends StarlarkValue {

  @StarlarkMethod(
      name = "apple_toolchain",
      doc = "Utilities for resolving items from the apple toolchain.")
  default Object getAppleToolchain() {
    throw new UnsupportedOperationException(); // just for docs
  }

  @StarlarkMethod(
      name = "platform_type",
      doc =
          "An enum-like struct that contains the following fields corresponding to Apple platform "
              + "types:<br><ul>" //
              + "<li><code>ios</code></li>" //
              + "<li><code>macos</code></li>" //
              + "<li><code>tvos</code></li>" //
              + "<li><code>visionos</code></li>" //
              + "<li><code>watchos</code></li>" //
              + "</ul><p>" //
              + "These values can be passed to methods that expect a platform type, like the"
              + " 'apple' configuration fragment's <a"
              + " href='../fragments/apple.html#multi_arch_platform'>multi_arch_platform</a>"
              + " method.<p>Example:<p><pre class='language-python'>\n"
              + "ctx.fragments.apple.multi_arch_platform(apple_common.platform_type.ios)\n"
              + "</pre>",
      structField = true)
  default StructApi getPlatformTypeStruct() {
    throw new UnsupportedOperationException(); // just for docs
  }

  @StarlarkMethod(
      name = "platform",
      doc =
          "An enum-like struct that contains the following fields corresponding to Apple "
              + "platforms:<br><ul>" //
              + "<li><code>ios_device</code></li>" //
              + "<li><code>ios_simulator</code></li>" //
              + "<li><code>macos</code></li>" //
              + "<li><code>tvos_device</code></li>" //
              + "<li><code>tvos_simulator</code></li>" //
              + "<li><code>visionos_device</code></li>" //
              + "<li><code>visionos_simulator</code></li>" //
              + "<li><code>watchos_device</code></li>" //
              + "<li><code>watchos_simulator</code></li>" //
              + "</ul><p>" //
              + "These values can be passed to methods that expect a platform, like "
              + "<a href='../providers/XcodeVersionConfig.html#sdk_version_for_platform'>"
              + "XcodeVersionConfig.sdk_version_for_platform</a>.",
      structField = true)
  StructApi getPlatformStruct();

  @StarlarkMethod(
      name = "XcodeProperties",
      doc =
          "The constructor/key for the <code>XcodeVersionProperties</code> provider.<p>"
              + "If a target propagates the <code>XcodeVersionProperties</code> provider,"
              + " use this as the key with which to retrieve it. Example:<br>"
              + "<pre class='language-python'>\n"
              + "dep = ctx.attr.deps[0]\n"
              + "p = dep[apple_common.XcodeVersionProperties]\n"
              + "</pre>",
      structField = true)
  default ProviderApi getXcodeVersionPropertiesConstructor() {
    throw new UnsupportedOperationException(); // just for docs
  }

  @StarlarkMethod(
      name = "XcodeVersionConfig",
      doc = "The constructor/key for the <code>XcodeVersionConfig</code> provider.",
      structField = true)
  default ProviderApi getXcodeVersionConfigConstructor() {
    throw new UnsupportedOperationException(); // just for docs
  }

  @StarlarkMethod(
      name = "apple_host_system_env",
      doc =
          "Returns a <a href='../core/dict.html'>dict</a> of environment variables that should be"
              + " set for actions that need to run build tools on an Apple host system, such as the"
              + "  version of Xcode that should be used. The keys are variable names and the values"
              + "  are their corresponding values.",
      parameters = {
        @Param(
            name = "xcode_config",
            positional = true,
            named = false,
            doc = "A provider containing information about the Xcode configuration."),
      })
  default ImmutableMap<String, String> getAppleHostSystemEnv(Object xcodeConfig) {
    throw new UnsupportedOperationException(); // just for docs
  }

  @StarlarkMethod(
      name = "target_apple_env",
      doc =
          "Returns a <code>dict</code> of environment variables that should be set for actions "
              + "that build targets of the given Apple platform type. For example, this dictionary "
              + "contains variables that denote the platform name and SDK version with which to "
              + "build. The keys are variable names and the values are their corresponding values.",
      parameters = {
        @Param(
            name = "xcode_config",
            positional = true,
            named = false,
            doc = "A provider containing information about the Xcode configuration."),
        @Param(name = "platform", positional = true, named = false, doc = "The apple platform."),
      })
  default ImmutableMap<String, String> getTargetAppleEnvironment(
      Object xcodeConfig, Object platform) {
    throw new UnsupportedOperationException(); // just for docs
  }

  @StarlarkMethod(
      name = "dotted_version",
      doc = "Creates a new <a href=\"../builtins/DottedVersion.html\">DottedVersion</a> instance.",
      parameters = {
        @Param(name = "version", doc = "The string representation of the DottedVersion.")
      })
  DottedVersionApi<?> dottedVersion(String version) throws EvalException;
}
