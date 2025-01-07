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

package com.google.devtools.build.lib.starlarkbuildapi.apple;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleContext;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;

/** A configuration fragment for Objective C. */
@StarlarkBuiltin(
    name = "objc",
    category = DocCategory.CONFIGURATION_FRAGMENT,
    doc = "A configuration fragment for Objective-C.")
public interface ObjcConfigurationApi extends StarlarkValue {

  @StarlarkMethod(
      name = "ios_simulator_device",
      structField = true,
      allowReturnNones = true,
      doc = "The type of device (e.g. 'iPhone 6') to use when running on the simulator.")
  @Nullable
  String getIosSimulatorDevice();

  @StarlarkMethod(
      name = "ios_simulator_version",
      structField = true,
      allowReturnNones = true,
      doc = "The SDK version of the iOS simulator to use when running on the simulator.")
  @Nullable
  DottedVersionApi<?> getIosSimulatorVersion();

  @StarlarkMethod(
      name = "ios_device",
      structField = true,
      allowReturnNones = true,
      doc = "The device identifier to use when running an iOS application.")
  @Nullable
  String getIosDevice();

  @StarlarkMethod(
      name = "run_memleaks",
      structField = true,
      doc = "Returns a boolean indicating whether memleaks should be run during tests or not.")
  boolean runMemleaks();

  @StarlarkMethod(
      name = "copts_for_current_compilation_mode",
      structField = true,
      doc =
          "Returns a list of default options to use for compiling Objective-C in the current "
              + "mode.")
  ImmutableList<String> getCoptsForCompilationMode();

  @StarlarkMethod(
      name = "signing_certificate_name",
      structField = true,
      allowReturnNones = true,
      doc =
          "Returns the flag-supplied certificate name to be used in signing, or None if no such "
              + "certificate was specified.")
  @Nullable
  String getSigningCertName();

  @StarlarkMethod(
      name = "uses_device_debug_entitlements",
      structField = true,
      doc =
          "Returns whether device debug entitlements should be included when signing an "
              + "application.")
  boolean useDeviceDebugEntitlements();

  @StarlarkMethod(
      name = "disallow_sdk_frameworks_attributes",
      structField = true,
      doc = "Returns whether sdk_frameworks and weak_sdk_frameworks are disallowed attributes.")
  boolean disallowSdkFrameworksAttributes();

  @StarlarkMethod(
      name = "alwayslink_by_default",
      structField = true,
      doc = "Returns whether objc_library and objc_import should default to alwayslink=True.")
  boolean alwayslinkByDefault();

  @StarlarkMethod(
      name = "target_should_alwayslink",
      documented = false,
      parameters = {@Param(name = "ctx")},
      useStarlarkThread = true)
  boolean targetShouldAlwayslink(StarlarkRuleContext ruleContext, StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "strip_executable_safely",
      structField = true,
      doc =
          "Returns whether executable strip action should use flag -x, which does not break "
              + "dynamic symbol resolution.")
  boolean stripExecutableSafely();
}
