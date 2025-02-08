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

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.starlark.StarlarkActionFactory;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.rules.cpp.CcCommon.Language;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;

/** Pluggable C++ compilation semantics. */
public interface CppSemantics extends StarlarkValue {

  /** Returns cpp toolchain type. */
  String getCppToolchainType();

  /** What language to treat the headers. */
  Language language();

  /**
   * Called before a C++ compile action is built.
   *
   * <p>Gives the semantics implementation the opportunity to change compile actions at the last
   * minute.
   */
  void finalizeCompileActionBuilder(
      BuildConfigurationValue configuration,
      FeatureConfiguration featureConfiguration,
      CppCompileActionBuilder actionBuilder)
      throws EvalException;

  /**
   * Called before a C++ link action is built.
   *
   * <p>Gives the semantics implementation the opportunity to change link actions at the last
   * minute.
   */
  void finalizeLinkActionBuilder(CppConfiguration configuration, CppLinkActionBuilder actionBuilder)
      throws EvalException;

  /**
   * Returns if include scanning is allowed.
   *
   * <p>If false, {@link CppCompileActionBuilder#setShouldScanIncludes(boolean)} has no effect.
   */
  boolean allowIncludeScanning();

  /** Returns true iff this build should perform .d input pruning. */
  boolean needsDotdInputPruning(BuildConfigurationValue configuration);

  /** Returns true iff this build requires include validation. */
  boolean needsIncludeValidation();

  /** No-op in Bazel */
  void validateLayeringCheckFeatures(
      RuleContext ruleContext,
      AspectDescriptor aspectDescriptor,
      CcToolchainProvider ccToolchain,
      ImmutableSet<String> unsupportedFeatures)
      throws EvalException;

  void validateStarlarkCompileApiCall(
      StarlarkActionFactory actionFactory,
      StarlarkThread thread,
      String includePrefix,
      String stripIncludePrefix,
      Sequence<?> additionalIncludeScanningRoots)
      throws EvalException;
}
