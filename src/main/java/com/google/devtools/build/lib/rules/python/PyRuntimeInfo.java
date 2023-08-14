// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.python;

import static net.starlark.java.eval.Starlark.NONE;

import com.google.common.annotations.VisibleForTesting;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkProviderWrapper;
import com.google.devtools.build.lib.starlarkbuildapi.python.PyRuntimeInfoApi;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;

/**
 * Instance of the provider type that describes Python runtimes.
 *
 * <p>Invariant: Exactly one of {@link #interpreterPath} and {@link #interpreter} is non-null. The
 * former corresponds to a platform runtime, and the latter to an in-build runtime; these two cases
 * are mutually exclusive. In addition, {@link #files} is non-null if and only if {@link
 * #interpreter} is non-null; in other words files are only used for in-build runtimes. These
 * invariants mirror the user-visible API on {@link PyRuntimeInfoApi} except that {@code None} is
 * replaced by null.
 */
@VisibleForTesting
public final class PyRuntimeInfo {
  /** The singular {@code PyRuntimeInfo} provider type object. */
  public static final PyRuntimeInfoProvider PROVIDER = new PyRuntimeInfoProvider();

  // Only present so PyRuntimeRule can reference it as a default.
  static final String DEFAULT_STUB_SHEBANG = "#!/usr/bin/env python3";

  // Only present so PyRuntimeRule can reference it as a default.
  // Must call getToolsLabel() when using this.
  static final String DEFAULT_BOOTSTRAP_TEMPLATE = "//tools/python:python_bootstrap_template.txt";

  private final StarlarkInfo info;

  private PyRuntimeInfo(StarlarkInfo info) {
    this.info = info;
  }

  @Nullable
  public String getInterpreterPathString() {
    Object value = info.getValue("interpreter_path");
    return value == Starlark.NONE ? null : (String) value;
  }

  @Nullable
  public Artifact getInterpreter() {
    Object value = info.getValue("interpreter");
    return value == Starlark.NONE ? null : (Artifact) value;
  }

  public String getStubShebang() throws EvalException {
    return info.getValue("stub_shebang", String.class);
  }

  @Nullable
  public Artifact getBootstrapTemplate() {
    Object value = info.getValue("bootstrap_template");
    return value == Starlark.NONE ? null : (Artifact) value;
  }

  @Nullable
  public NestedSet<Artifact> getFiles() throws EvalException {
    Object value = info.getValue("files");
    if (value == NONE) {
      return null;
    } else {
      return Depset.cast(value, Artifact.class, "files");
    }
  }

  public PythonVersion getPythonVersion() throws EvalException {
    return PythonVersion.parseTargetValue(info.getValue("python_version", String.class));
  }

  /** The class of the {@code PyRuntimeInfo} provider type. */
  public static class PyRuntimeInfoProvider extends StarlarkProviderWrapper<PyRuntimeInfo> {

    private PyRuntimeInfoProvider() {
      super(
          Label.parseCanonicalUnchecked("@_builtins//:common/python/providers.bzl"),
          "PyRuntimeInfo");
    }

    @Override
    public PyRuntimeInfo wrap(Info value) {
      return new PyRuntimeInfo((StarlarkInfo) value);
    }
  }
}
