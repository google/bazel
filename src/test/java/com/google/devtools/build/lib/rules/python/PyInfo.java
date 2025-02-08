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

package com.google.devtools.build.lib.rules.python;

import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuiltins;

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
import net.starlark.java.eval.EvalException;

/** Instance of the provider type for the Python rules. */
@VisibleForTesting
public final class PyInfo {
  private static final BuiltinPyInfoProvider BUILTIN_PROVIDER = new BuiltinPyInfoProvider();
  private static final RulesPythonPyInfoProvider RULES_PYTHON_PROVIDER =
      new RulesPythonPyInfoProvider();

  private final StarlarkInfo info;

  private PyInfo(StarlarkInfo info) {
    this.info = info;
  }

  public static PyInfo fromTarget(ConfiguredTarget target) throws RuleErrorException {
    PyInfo provider = target.get(RULES_PYTHON_PROVIDER);
    if (provider != null) {
      return provider;
    }
    provider = target.get(BUILTIN_PROVIDER);
    if (provider != null) {
      return provider;
    }
    throw new IllegalStateException(String.format("Unable to find PyInfo provider in %s", target));
  }

  public NestedSet<Artifact> getTransitiveSourcesSet() throws EvalException {
    Object value = info.getValue("transitive_sources");
    return Depset.cast(value, Artifact.class, "transitive_sources");
  }

  public boolean getUsesSharedLibraries() throws EvalException {
    return info.getValue("uses_shared_libraries", Boolean.class);
  }

  public NestedSet<String> getImportsSet() throws EvalException {
    Object value = info.getValue("imports");
    return Depset.cast(value, String.class, "imports");
  }

  public boolean getHasPy2OnlySources() throws EvalException {
    return info.getValue("has_py2_only_sources", Boolean.class);
  }

  public boolean getHasPy3OnlySources() throws EvalException {
    return info.getValue("has_py3_only_sources", Boolean.class);
  }

  private static class BaseProvider extends StarlarkProviderWrapper<PyInfo> {
    private BaseProvider(BzlLoadValue.Key bzlKey) {
      super(bzlKey, "PyInfo");
    }

    @Override
    public PyInfo wrap(Info value) {
      return new PyInfo((StarlarkInfo) value);
    }
  }

  /** The PyInfo provider type object for the builtin provider. */
  public static class BuiltinPyInfoProvider extends BaseProvider {
    private BuiltinPyInfoProvider() {
      super(
          keyForBuiltins(
              Label.parseCanonicalUnchecked("@_builtins//:common/python/providers.bzl")));
    }
  }

  /** The PyInfo provider type object for the rules_python provider. */
  public static class RulesPythonPyInfoProvider extends BaseProvider {
    private RulesPythonPyInfoProvider() {
      super(keyForBuild(Label.parseCanonicalUnchecked(TestConstants.PYINFO_BZL)));
    }
  }
}
