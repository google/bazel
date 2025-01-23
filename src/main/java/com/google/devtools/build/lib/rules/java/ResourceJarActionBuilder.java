// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.java;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.ParamFileInfo;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Map;

/** Builds the action to package the resources for a Java rule into a jar. */
public class ResourceJarActionBuilder {
  public static final String MNEMONIC = "JavaResourceJar";

  private static final ParamFileInfo PARAM_FILE_INFO =
      ParamFileInfo.builder(ParameterFileType.SHELL_QUOTED).build();
  private static final ImmutableMap<String, String> EXECUTION_INFO =
      ImmutableMap.of(ExecutionRequirements.SUPPORTS_PATH_MAPPING, "1");

  private Artifact outputJar;
  private Map<PathFragment, Artifact> resources = ImmutableMap.of();
  private NestedSet<Artifact> resourceJars = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  private ImmutableList<Artifact> classpathResources = ImmutableList.of();
  private ImmutableList<Artifact> messages = ImmutableList.of();
  private JavaToolchainProvider javaToolchain;
  private NestedSet<Artifact> additionalInputs = NestedSetBuilder.emptySet(Order.STABLE_ORDER);

  @CanIgnoreReturnValue
  public ResourceJarActionBuilder setOutputJar(Artifact outputJar) {
    this.outputJar = outputJar;
    return this;
  }

  @CanIgnoreReturnValue
  public ResourceJarActionBuilder setAdditionalInputs(NestedSet<Artifact> additionalInputs) {
    this.additionalInputs = additionalInputs;
    return this;
  }

  @CanIgnoreReturnValue
  public ResourceJarActionBuilder setClasspathResources(
      ImmutableList<Artifact> classpathResources) {
    this.classpathResources = classpathResources;
    return this;
  }

  @CanIgnoreReturnValue
  public ResourceJarActionBuilder setResources(Map<PathFragment, Artifact> resources) {
    this.resources = resources;
    return this;
  }

  @CanIgnoreReturnValue
  public ResourceJarActionBuilder setResourceJars(NestedSet<Artifact> resourceJars) {
    this.resourceJars = resourceJars;
    return this;
  }

  @CanIgnoreReturnValue
  public ResourceJarActionBuilder setTranslations(ImmutableList<Artifact> translations) {
    this.messages = translations;
    return this;
  }

  @CanIgnoreReturnValue
  public ResourceJarActionBuilder setJavaToolchain(JavaToolchainProvider javaToolchain) {
    this.javaToolchain = javaToolchain;
    return this;
  }

  public void build(JavaSemantics semantics, RuleContext ruleContext, String execGroup)
      throws RuleErrorException {
    checkNotNull(outputJar, "outputJar must not be null");
    checkNotNull(javaToolchain, "javaToolchain must not be null");
    checkNotNull(javaToolchain.getJavaRuntime(), "javabase must not be null");

    SpawnAction.Builder builder = new SpawnAction.Builder();
    CustomCommandLine.Builder command =
        CustomCommandLine.builder()
            .add("--normalize")
            .add("--dont_change_compression")
            .add("--exclude_build_data")
            .addExecPath("--output", outputJar);
    if (!resourceJars.isEmpty()) {
      command.addExecPaths("--sources", resourceJars);
    }
    addResources(command, semantics);
    if (!classpathResources.isEmpty()) {
      command.addExecPaths("--classpath_resources", classpathResources);
    }

    ImmutableMap.Builder<String, String> executionInfo = ImmutableMap.builder();
    executionInfo.putAll(EXECUTION_INFO);
    executionInfo.putAll(
        TargetUtils.getExecutionInfo(ruleContext.getRule(), ruleContext.isAllowTagsPropagation()));

    ruleContext.registerAction(
        builder
            .setExecutable(javaToolchain.getSingleJar())
            .useDefaultShellEnvironment()
            .addOutput(outputJar)
            .addInputs(messages)
            .addInputs(resources.values())
            .addTransitiveInputs(resourceJars)
            .addTransitiveInputs(additionalInputs)
            .addInputs(classpathResources)
            .addCommandLine(command.build(), PARAM_FILE_INFO)
            .setProgressMessage("Building Java resource jar")
            .setMnemonic(MNEMONIC)
            .setExecutionInfo(executionInfo.build())
            .setExecGroup(execGroup)
            .build(ruleContext));
  }

  private void addResources(CustomCommandLine.Builder command, JavaSemantics semantics) {
    if (resources.isEmpty() && messages.isEmpty()) {
      return;
    }

    command.add("--resources");
    ImmutableList<Artifact> resourcesWithDefaultPath;

    // When all resources use the default path (common case), save memory by throwing away those
    // path fragments. The artifacts can be lazily converted to default-prefixed strings.
    if (resources.entrySet().stream()
        .allMatch(e -> e.getKey().equals(defaultResourcePath(e.getValue(), semantics)))) {
      resourcesWithDefaultPath =
          ImmutableList.<Artifact>builderWithExpectedSize(resources.size() + messages.size())
              .addAll(resources.values())
              .addAll(messages)
              .build();
    } else {
      command.addObject(
          Lists.transform(
              ImmutableList.copyOf(resources.entrySet()),
              e -> resourcePrefixedExecPath(e.getKey(), e.getValue())));
      resourcesWithDefaultPath = messages;
    }

    if (!resourcesWithDefaultPath.isEmpty()) {
      command.addObject(
          Lists.transform(
              resourcesWithDefaultPath,
              artifact ->
                  resourcePrefixedExecPath(defaultResourcePath(artifact, semantics), artifact)));
    }
  }

  private static PathFragment defaultResourcePath(Artifact artifact, JavaSemantics semantics) {
    return semantics.getDefaultJavaResourcePath(artifact.getRootRelativePath());
  }

  private static String resourcePrefixedExecPath(PathFragment resourcePath, Artifact artifact) {
    PathFragment execPath = artifact.getExecPath();
    return execPath.equals(resourcePath) ? execPath.getPathString() : execPath + ":" + resourcePath;
  }
}
