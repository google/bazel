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

package com.google.devtools.build.lib.analysis;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Provides shared functionality for parameterized command-line launching.
 * Also used by {@link com.google.devtools.build.lib.rules.extra.ExtraActionFactory}.
 *
 * Two largely independent separate sets of functionality are provided:
 * 1- string interpolation for {@code $(location[s] ...)} and {@code $(MakeVariable)}
 * 2- a utility to build potentially large command lines (presumably made of multiple commands),
 *  that if presumed too large for the kernel's taste can be dumped into a shell script
 *  that will contain the same commands,
 *  at which point the shell script is added to the list of inputs.
 */
public final class CommandHelper {

  /**
   * Returns a new {@link Builder} to create a {@link CommandHelper} based on the given {@link
   * RuleContext}.
   */
  public static Builder builder(RuleContext ruleContext) {
    return new Builder(ruleContext);
  }

  /**
   * Builder class to assist with creating an instance of {@link CommandHelper}. The Builder can
   * optionally add additional tools as dependencies, and a map of labels to be resolved.
   */
  public static final class Builder {
    private final RuleContext ruleContext;
    private final ImmutableList.Builder<Iterable<? extends TransitiveInfoCollection>>
        toolDependencies = ImmutableList.builder();
    private final ImmutableMap.Builder<Label, Iterable<Artifact>> labelMap = ImmutableMap.builder();

    private Builder(RuleContext ruleContext) {
      this.ruleContext = ruleContext;
    }

    /**
     * Adds tools, as a set of executable binaries, by fetching them from the given attribute on the
     * {@code ruleContext}. Populates manifests, remoteRunfiles and label map where required.
     */
    public Builder addToolDependencies(String toolAttributeName) {
      List<? extends TransitiveInfoCollection> dependencies =
          ruleContext.getPrerequisites(toolAttributeName);
      return addToolDependencies(dependencies);
    }

    /**
     * Adds tools, as a set of executable binaries. Populates manifests, remoteRunfiles and label
     * map where required.
     */
    @CanIgnoreReturnValue
    public Builder addToolDependencies(
        Iterable<? extends TransitiveInfoCollection> toolDependencies) {
      this.toolDependencies.add(toolDependencies);
      return this;
    }

    /** Adds files to set of known files of label. Used for resolving $(location) variables. */
    @CanIgnoreReturnValue
    public Builder addLabelMap(Map<Label, ? extends Iterable<Artifact>> labelMap) {
      this.labelMap.putAll(labelMap);
      return this;
    }

    /** Returns the built {@link CommandHelper}. */
    public CommandHelper build() {
      return new CommandHelper(ruleContext, toolDependencies.build(), labelMap.buildOrThrow());
    }
  }

  /**
   * Maximum total command-line length, in bytes, not counting "/bin/bash -c ".
   * If the command is very long, then we write the command to a script file,
   * to avoid overflowing any limits on command-line length.
   * For short commands, we just use /bin/bash -c command.
   *
   * Maximum command line length on Windows is 32767[1], but for cmd.exe it is 8192[2].
   * [1] https://msdn.microsoft.com/en-us/library/ms682425(VS.85).aspx
   * [2] https://support.microsoft.com/en-us/kb/830473.
   */
  @VisibleForTesting
  public static int maxCommandLength = OS.getCurrent() == OS.WINDOWS ? 8000 : 64000;

  /**
   * Use labelMap for heuristically expanding labels (does not include "outs")
   * This is similar to heuristic location expansion in LocationExpander
   * and should be kept in sync.
   */
  private final ImmutableMap<Label, ImmutableCollection<Artifact>> labelMap;

  /**
   * The ruleContext this helper works on
   */
  private final RuleContext ruleContext;

  /**
   * Output executable files from the 'tools' attribute.
   */
  private final NestedSet<Artifact> resolvedTools;

  /**
   * Creates a {@link CommandHelper}.
   *
   * @param toolsList resolves sets of tools into set of executable binaries. Populates manifests,
   *     remoteRunfiles and label map where required.
   * @param labelMap adds files to set of known files of label. Used for resolving $(location)
   *     variables.
   */
  private CommandHelper(
      RuleContext ruleContext,
      ImmutableList<Iterable<? extends TransitiveInfoCollection>> toolsList,
      ImmutableMap<Label, ? extends Iterable<Artifact>> labelMap) {

    this.ruleContext = ruleContext;

    NestedSetBuilder<Artifact> resolvedToolsBuilder = NestedSetBuilder.stableOrder();
    Map<Label, Collection<Artifact>> tempLabelMap = new HashMap<>();

    for (Map.Entry<Label, ? extends Iterable<Artifact>> entry : labelMap.entrySet()) {
      Iterables.addAll(mapGet(tempLabelMap, entry.getKey()), entry.getValue());
    }

    for (Iterable<? extends TransitiveInfoCollection> tools : toolsList) {
      for (TransitiveInfoCollection dep : tools) { // (Note: exec configuration)

        FilesToRunProvider tool = dep.getProvider(FilesToRunProvider.class);
        if (tool == null) {
          continue;
        }

        NestedSet<Artifact> filesToBuild = dep.getProvider(FileProvider.class).getFilesToBuild();
        resolvedToolsBuilder.addTransitive(filesToBuild);

        Artifact executableArtifact = tool.getExecutable();
        Label label = AliasProvider.getDependencyLabel(dep);

        // If the label has an executable artifact add that to the multimaps.
        if (executableArtifact != null) {
          mapGet(tempLabelMap, label).add(executableArtifact);
          // Also send the runfiles if needed.
          RunfilesSupport runfilesSupport = tool.getRunfilesSupport();
          if (runfilesSupport != null) {
            resolvedToolsBuilder.add(runfilesSupport.getRunfilesTreeArtifact());
            // It's possible that getExecutable() returns an artifact that is not in
            // getFilesToBuild(). It is not nice, but it happens
            // (see test_executable_without_default_files)
            resolvedToolsBuilder.add(tool.getRunfilesSupport().getExecutable());
          }
        } else {
          // Map all depArtifacts to the respective label using the multimaps.
          mapGet(tempLabelMap, label).addAll(filesToBuild.toList());
        }
      }
    }

    this.resolvedTools = resolvedToolsBuilder.build();
    ImmutableMap.Builder<Label, ImmutableCollection<Artifact>> labelMapBuilder =
        ImmutableMap.builder();
    for (Map.Entry<Label, Collection<Artifact>> entry : tempLabelMap.entrySet()) {
      labelMapBuilder.put(entry.getKey(), ImmutableList.copyOf(entry.getValue()));
    }
    this.labelMap = labelMapBuilder.buildOrThrow();
  }

  public NestedSet<Artifact> getResolvedTools() {
    return resolvedTools;
  }

  public ImmutableMap<Label, ImmutableCollection<Artifact>> getLabelMap() {
    return labelMap;
  }

  // Returns the value in the specified corresponding to 'key', creating and
  // inserting an empty container if absent.  We use Map not Multimap because
  // we need to distinguish the cases of "empty value" and "absent key".
  private static Collection<Artifact> mapGet(Map<Label, Collection<Artifact>> map, Label key) {
    Collection<Artifact> values = map.get(key);
    if (values == null) {
      // We use sets not lists, because it's conceivable that the same artifact
      // could appear twice, e.g. in "srcs" and "deps".
      values = Sets.newHashSet();
      map.put(key, values);
    }
    return values;
  }

  /** Resolves a command, and expands known locations for $(location) variables. */
  @Deprecated // Only exists to support a legacy Starlark API.
  public String resolveCommandAndExpandLabels(
      String command, @Nullable String attribute, boolean allowDataInLabel) {
    LocationExpander expander;
    if (allowDataInLabel) {
      expander = LocationExpander.withExecPathsAndData(ruleContext, labelMap);
    } else {
      expander = LocationExpander.withExecPaths(ruleContext, labelMap);
    }
    if (attribute != null) {
      command = expander.expandAttribute(attribute, command);
    } else {
      command = expander.expand(command);
    }
    return command;
  }

  /**
   * Expands labels occurring in the string "expr" in the rule 'cmd'.
   * Each label must be valid, be a declared prerequisite, and expand to a
   * unique path.
   *
   * <p>If the expansion fails, an attribute error is reported and the original
   * expression is returned.
   */
  public String expandLabelsHeuristically(String expr) {
    try {
      return LabelExpander.expand(expr, labelMap, ruleContext.getLabel());
    } catch (LabelExpander.NotUniqueExpansionException nuee) {
      ruleContext.attributeError("cmd", nuee.getMessage());
      return expr;
    }
  }

  private static Pair<ImmutableList<String>, Artifact> buildCommandLineMaybeWithScriptFile(
      RuleContext ruleContext, String command, CommandConstructor constructor) {
    ImmutableList<String> argv;
    Artifact scriptFileArtifact = null;
    if (command.length() <= maxCommandLength) {
      argv = constructor.asExecArgv(command);
    } else {
      // Use script file.
      scriptFileArtifact = constructor.commandAsScript(ruleContext, command);
      argv = constructor.asExecArgv(scriptFileArtifact);
    }
    return Pair.of(argv, scriptFileArtifact);
  }

  /**
   * If {@code command} is too long, creates a helper shell script that runs that command.
   *
   * <p>Returns the {@link Artifact} corresponding to that script.
   *
   * <p>Otherwise, when {@code command} is shorter than the platform's shell's command length limit,
   * this method does nothing and returns null.
   */
  @Nullable
  public static Artifact commandHelperScriptMaybe(
      RuleContext ruleCtx, String command, CommandConstructor constructor) {
    if (command.length() <= maxCommandLength) {
      return null;
    } else {
      return constructor.commandAsScript(ruleCtx, command);
    }
  }

  /**
   * Builds the set of command-line arguments using the specified shell path. Creates a bash script
   * if the command line is longer than the allowed maximum {@link #maxCommandLength}. Fixes up the
   * input artifact list with the created bash script when required.
   */
  public ImmutableList<String> buildCommandLine(
      String command, NestedSetBuilder<Artifact> inputs, CommandConstructor constructor) {
    Pair<ImmutableList<String>, Artifact> argvAndScriptFile =
        buildCommandLineMaybeWithScriptFile(ruleContext, command, constructor);
    if (argvAndScriptFile.second != null) {
      inputs.add(argvAndScriptFile.second);
    }
    return argvAndScriptFile.first;
  }

  /**
   * Builds the set of command-line arguments. Creates a bash script if the command line is longer
   * than the allowed maximum {@link #maxCommandLength}. Fixes up the input artifact list with the
   * created bash script when required.
   */
  public List<String> buildCommandLine(
      String command, List<Artifact> inputs, CommandConstructor constructor) {
    Pair<ImmutableList<String>, Artifact> argvAndScriptFile =
        buildCommandLineMaybeWithScriptFile(ruleContext, command, constructor);
    if (argvAndScriptFile.second != null) {
      inputs.add(argvAndScriptFile.second);
    }
    return argvAndScriptFile.first;
  }

  /** Returns the path to the shell for an action with the given execution requirements. */
  private static PathFragment shellPath(
      Map<String, String> executionInfo, PathFragment shExecutable) {
    // Use vanilla /bin/bash for actions running on mac machines.
    return executionInfo.containsKey(ExecutionRequirements.REQUIRES_DARWIN)
        ? PathFragment.create("/bin/bash")
        : shExecutable;
  }

  public static BashCommandConstructor buildBashCommandConstructor(
      Map<String, String> executionInfo, PathFragment shExecutable, String scriptPostFix) {
    return new BashCommandConstructor(shellPath(executionInfo, shExecutable), scriptPostFix);
  }

  public static WindowsBatchCommandConstructor buildWindowsBatchCommandConstructor(
      String scriptPostFix) {
    return new WindowsBatchCommandConstructor(scriptPostFix);
  }

  public static WindowsPowershellCommandConstructor buildWindowsPowershellCommandConstructor(
      String scriptPostFix) {
    return new WindowsPowershellCommandConstructor(scriptPostFix);
  }
}
