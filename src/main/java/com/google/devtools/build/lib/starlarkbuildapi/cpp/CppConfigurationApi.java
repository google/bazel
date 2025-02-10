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

package com.google.devtools.build.lib.starlarkbuildapi.cpp;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.cmdline.Label;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;

/** The C++ configuration fragment. */
@StarlarkBuiltin(
    name = "cpp",
    doc = "A configuration fragment for C++.",
    category = DocCategory.CONFIGURATION_FRAGMENT)
public interface CppConfigurationApi<InvalidConfigurationExceptionT extends Exception>
    extends StarlarkValue {

  @StarlarkMethod(
      name = "experimental_link_static_libraries_once",
      documented = false,
      useStarlarkThread = true)
  boolean getExperimentalLinkStaticLibrariesOnce(StarlarkThread thread) throws EvalException;
  
  @StarlarkMethod(
      name = "experimental_platform_cc_test",
      documented = false,
      useStarlarkThread = true)
  boolean getExperimentalPlatformCcTest(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "copts",
      structField = true,
      doc =
          "The flags passed to Bazel by <a href=\"${link user-manual#flag--copt}\">"
              + "<code>--copt</code></a> option.")
  ImmutableList<String> getCopts() throws EvalException;

  @StarlarkMethod(
      name = "cxxopts",
      structField = true,
      doc =
          "The flags passed to Bazel by <a href=\"${link user-manual#flag--cxxopt}\">"
              + "<code>--cxxopt</code></a> option.")
  ImmutableList<String> getCxxopts() throws EvalException;

  @StarlarkMethod(
      name = "conlyopts",
      structField = true,
      doc =
          "The flags passed to Bazel by <a href=\"${link user-manual#flag--conlyopt}\">"
              + "<code>--conlyopt</code></a> option.")
  ImmutableList<String> getConlyopts() throws EvalException;

  @StarlarkMethod(
      name = "objccopts",
      structField = true,
      doc =
          "The flags passed to Bazel by <a href=\"${link user-manual#flag--objccopt}\">"
              + "<code>--objccopt</code></a> option.")
  ImmutableList<String> getObjcopts() throws EvalException;

  @StarlarkMethod(
      name = "linkopts",
      structField = true,
      doc =
          "The flags passed to Bazel by <a href=\"${link user-manual#flag--linkopt}\">"
              + "<code>--linkopt</code></a> option.")
  ImmutableList<String> getLinkopts() throws EvalException;

  @StarlarkMethod(
      name = "custom_malloc",
      allowReturnNones = true,
      structField = true,
      doc =
          "Returns label pointed to by <a href=\"${link user-manual#flag--custom_malloc}\">"
              + "<code>--custom_malloc</code></a> option. Can be accessed with"
              + " <a href=\"../globals/bzl.html#configuration_field\"><code>configuration_field"
              + "</code></a>:<br/>"
              + "<pre>attr.label(<br/>"
              + "    default = configuration_field(<br/>"
              + "        fragment = \"cpp\",<br/>"
              + "        name = \"custom_malloc\"<br/>"
              + "    )<br/>"
              + ")</pre>")
  @Nullable
  Label customMalloc();

  @StarlarkMethod(
      name = "do_not_use_macos_set_install_name",
      structField = true,
      // Only for migration purposes. Intentionally not documented.
      documented = false,
      doc = "Accessor for <code>--incompatible_macos_set_install_name</code>.")
  boolean macosSetInstallName();

  @StarlarkMethod(name = "force_pic", documented = false, useStarlarkThread = true)
  boolean forcePicStarlark(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(name = "generate_llvm_lcov", documented = false, useStarlarkThread = true)
  boolean generateLlvmLcovStarlark(StarlarkThread thread) throws EvalException;

  @Nullable
  @StarlarkMethod(
      name = "fdo_instrument",
      documented = false,
      useStarlarkThread = true,
      allowReturnNones = true)
  String fdoInstrumentStarlark(StarlarkThread thread) throws EvalException;

  @Nullable
  @StarlarkMethod(
      name = "cs_fdo_instrument",
      documented = false,
      useStarlarkThread = true,
      allowReturnNones = true)
  String csFdoInstrumentStarlark(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "process_headers_in_dependencies",
      documented = false,
      useStarlarkThread = true)
  boolean processHeadersInDependenciesStarlark(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(name = "save_feature_state", documented = false, useStarlarkThread = true)
  boolean saveFeatureStateStarlark(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "fission_active_for_current_compilation_mode",
      documented = false,
      useStarlarkThread = true)
  boolean fissionActiveForCurrentCompilationModeStarlark(StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "apple_generate_dsym",
      doc = "Whether to generate Apple debug symbol(.dSYM) artifacts.",
      structField = true)
  boolean appleGenerateDsym();

  @StarlarkMethod(
      name = "objc_generate_linkmap",
      doc = "(Apple-only) Whether to generate linkmap artifacts.",
      structField = true)
  boolean objcGenerateLinkmap();

  @StarlarkMethod(
      name = "objc_should_strip_binary",
      structField = true,
      doc = "(Apple-only) whether to perform symbol and dead-code strippings on linked binaries.")
  boolean objcShouldStripBinary();

  @StarlarkMethod(name = "strip_opts", documented = false, useStarlarkThread = true)
  Sequence<String> getStripOptsStarlark(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(name = "should_strip_binaries", useStarlarkThread = true, documented = false)
  boolean shouldStripBinariesForStarlark(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(name = "build_test_dwp", documented = false, useStarlarkThread = true)
  boolean buildTestDwpIsActivatedStarlark(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "grte_top",
      documented = false,
      useStarlarkThread = true,
      allowReturnNones = true)
  @Nullable
  Label getLibcTopLabelStarlark(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(name = "share_native_deps", documented = false, useStarlarkThread = true)
  boolean shareNativeDepsStarlark(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(name = "disable_nocopts", documented = false, useStarlarkThread = true)
  boolean disableNocoptsStarlark(StarlarkThread thread) throws EvalException;
}
