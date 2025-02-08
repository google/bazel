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

import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuiltins;
import static net.starlark.java.eval.Starlark.NONE;

import com.google.common.annotations.VisibleForTesting;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkProviderWrapper;
import com.google.devtools.build.lib.skyframe.BzlLoadValue;
import com.google.devtools.build.lib.testutil.TestConstants;
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
  private static final BuiltinProvider BUILTIN_PROVIDER = new BuiltinProvider();
  private static final RulesPythonProvider RULES_PYTHON_PROVIDER = new RulesPythonProvider();

  private final StarlarkInfo info;

  private PyRuntimeInfo(StarlarkInfo info) {
    this.info = info;
  }

  public static PyRuntimeInfo fromTarget(ConfiguredTarget target) throws RuleErrorException {
    var provider = fromTargetNullable(target);
    if (provider == null) {
      throw new IllegalStateException(
          String.format("Unable to find PyRuntimeInfo provider in %s", target));
    } else {
      return provider;
    }
  }

  @Nullable
  public static PyRuntimeInfo fromTargetNullable(ConfiguredTarget target)
      throws RuleErrorException {
    PyRuntimeInfo provider = target.get(RULES_PYTHON_PROVIDER);
    if (provider != null) {
      return provider;
    }
    provider = target.get(BUILTIN_PROVIDER);
    if (provider != null) {
      return provider;
    }
    return null;
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

  private static class BaseProvider extends StarlarkProviderWrapper<PyRuntimeInfo> {
    private BaseProvider(BzlLoadValue.Key bzlKey) {
      super(bzlKey, "PyRuntimeInfo");
    }

    @Override
    public PyRuntimeInfo wrap(Info value) {
      return new PyRuntimeInfo((StarlarkInfo) value);
    }
  }

  /** Provider instance for the builtin PyRuntimeInfo provider. */
  private static class BuiltinProvider extends BaseProvider {

    private BuiltinProvider() {
      super(
          keyForBuiltins(
              Label.parseCanonicalUnchecked("@_builtins//:common/python/providers.bzl")));
    }
  }

  /** Provider instance for the rules_python PyRuntimeInfo provider. */
  private static class RulesPythonProvider extends BaseProvider {

    private RulesPythonProvider() {
      super(keyForBuild(Label.parseCanonicalUnchecked(TestConstants.PYRUNTIMEINFO_BZL)));
    }
  }
}
