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

package com.google.devtools.build.lib.rules.java;

import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.platform.ToolchainInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkInfoWithSchema;
import com.google.devtools.build.lib.packages.StarlarkProviderWrapper;
import com.google.devtools.build.lib.rules.cpp.CcInfo;
import com.google.devtools.build.lib.rules.cpp.LibraryToLink;
import com.google.devtools.build.lib.skyframe.BzlLoadValue;
import com.google.devtools.build.lib.vfs.PathFragment;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkInt;

/** Information about the Java runtime used by the <code>java_*</code> rules. */
@Immutable
public final class JavaRuntimeInfo extends StarlarkInfoWrapper {

  public static final StarlarkProviderWrapper<JavaRuntimeInfo> RULES_JAVA_PROVIDER =
      new RulesJavaProvider();
  public static final StarlarkProviderWrapper<JavaRuntimeInfo> WORKSPACE_PROVIDER =
      new WorkspaceProvider();
  public static final StarlarkProviderWrapper<JavaRuntimeInfo> PROVIDER = new Provider();

  // Helper methods to access an instance of JavaRuntimeInfo.

  public static JavaRuntimeInfo forHost(RuleContext ruleContext) throws RuleErrorException {
    return JavaToolchainProvider.from(ruleContext).getJavaRuntime();
  }

  public static JavaRuntimeInfo from(RuleContext ruleContext, Label javaRuntimeToolchainType) {
    ToolchainInfo toolchainInfo = ruleContext.getToolchainInfo(javaRuntimeToolchainType);
    return from(ruleContext, toolchainInfo);
  }

  @Nullable
  public static JavaRuntimeInfo from(RuleContext ruleContext, String attributeName) {
    if (!ruleContext.attributes().has(attributeName, LABEL)) {
      return null;
    }
    TransitiveInfoCollection prerequisite = ruleContext.getPrerequisite(attributeName);
    if (prerequisite == null) {
      return null;
    }

    ToolchainInfo toolchainInfo = prerequisite.get(ToolchainInfo.PROVIDER);
    return from(ruleContext, toolchainInfo);
  }

  @Nullable
  private static JavaRuntimeInfo from(RuleContext ruleContext, ToolchainInfo toolchainInfo) {
    if (toolchainInfo != null) {
      try {
        JavaRuntimeInfo result = wrap(toolchainInfo.getValue("java_runtime", Info.class));
        if (result != null) {
          return result;
        }
      } catch (EvalException | RuleErrorException e) {
        ruleContext.ruleError(String.format("There was an error reading the Java runtime: %s", e));
        return null;
      }
    }
    ruleContext.ruleError("The selected Java runtime is not a JavaRuntimeInfo");
    return null;
  }

  private JavaRuntimeInfo(StarlarkInfo underlying) {
    super(underlying);
  }

  public static JavaRuntimeInfo wrap(Info info) throws RuleErrorException {
    com.google.devtools.build.lib.packages.Provider.Key key = info.getProvider().getKey();
    if (key.equals(PROVIDER.getKey())) {
      return PROVIDER.wrap(info);
    } else if (key.equals(RULES_JAVA_PROVIDER.getKey())) {
      return RULES_JAVA_PROVIDER.wrap(info);
    } else if (key.equals(WORKSPACE_PROVIDER.getKey())) {
      return WORKSPACE_PROVIDER.wrap(info);
    } else {
      throw new RuleErrorException("expected JavaRuntimeInfo, got: " + key);
    }
  }

  /** All input artifacts in the javabase. */
  public NestedSet<Artifact> javaBaseInputs() throws RuleErrorException {
    return getUnderlyingNestedSet("files", Artifact.class);
  }

  /** The root directory of the Java installation. */
  public String javaHome() throws RuleErrorException {
    return getUnderlyingValue("java_home", String.class);
  }

  public PathFragment javaBinaryExecPathFragment() throws RuleErrorException {
    return PathFragment.create(getUnderlyingValue("java_executable_exec_path", String.class));
  }

  public PathFragment javaBinaryRunfilesPathFragment() throws RuleErrorException {
    return PathFragment.create(getUnderlyingValue("java_executable_runfiles_path", String.class));
  }

  public ImmutableList<CcInfo> hermeticStaticLibs() throws RuleErrorException {
    return getUnderlyingSequence("hermetic_static_libs", CcInfo.class).getImmutableList();
  }

  @VisibleForTesting
  NestedSet<LibraryToLink> collectHermeticStaticLibrariesToLink() throws RuleErrorException {
    NestedSetBuilder<LibraryToLink> result = NestedSetBuilder.stableOrder();
    for (CcInfo lib : hermeticStaticLibs()) {
      result.addTransitive(lib.getCcLinkingContext().getLibraries());
    }
    return result.build();
  }

  public int version() throws RuleErrorException {
    return getUnderlyingValue("version", StarlarkInt.class).toIntUnchecked();
  }

  private static class RulesJavaProvider extends Provider {
    private RulesJavaProvider() {
      super(keyForBuild(Label.parseCanonicalUnchecked("//java/common/rules:java_runtime.bzl")));
    }
  }

  private static class WorkspaceProvider extends Provider {
    private WorkspaceProvider() {
      super(
          keyForBuild(
              Label.parseCanonicalUnchecked("@@rules_java//java/common/rules:java_runtime.bzl")));
    }
  }

  private static class Provider extends StarlarkProviderWrapper<JavaRuntimeInfo> {

    private Provider() {
      this(
          keyForBuild(
              Label.parseCanonicalUnchecked(
                  JavaSemantics.RULES_JAVA_PROVIDER_LABELS_PREFIX
                      + "java/common/rules:java_runtime.bzl")));
    }

    private Provider(BzlLoadValue.Key key) {
      super(key, "JavaRuntimeInfo");
    }

    @Override
    public JavaRuntimeInfo wrap(Info value) throws RuleErrorException {
      if (value instanceof StarlarkInfoWithSchema
          && value.getProvider().getKey().equals(getKey())) {
        return new JavaRuntimeInfo((StarlarkInfo) value);
      } else {
        throw new RuleErrorException(
            "got value of type '" + Starlark.type(value) + "', want 'JavaRuntimeInfo'");
      }
    }
  }
}
