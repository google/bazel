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

package com.google.devtools.build.lib.rules.genrule;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLines;
import com.google.devtools.build.lib.analysis.CommandConstructor;
import com.google.devtools.build.lib.analysis.CommandHelper;
import com.google.devtools.build.lib.analysis.ConfigurationMakeVariableContext;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.MakeVariableSupplier;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.ShToolchain;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.stringtemplate.ExpansionException;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesCollector;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesCollector.InstrumentationSpec;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.packages.TriState;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.util.OnDemandString;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.ForOverride;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A base implementation of genrule, to be used by specific implementing rules which can change the
 * semantics of {@link #collectSources}.
 */
public abstract class GenRuleBase implements RuleConfiguredTargetFactory {

  @Override
  @Nullable
  public final ConfiguredTarget create(RuleContext ruleContext)
      throws InterruptedException, RuleErrorException, ActionConflictException {
    NestedSet<Artifact> filesToBuild =
        NestedSetBuilder.wrap(Order.STABLE_ORDER, ruleContext.getOutputArtifacts());

    if (filesToBuild.isEmpty()) {
      ruleContext.attributeError("outs", "Genrules without outputs don't make sense");
    }
    if (ruleContext.attributes().get("executable", Type.BOOLEAN)
        && !filesToBuild.isEmpty()
        && !filesToBuild.isSingleton()) {
      ruleContext.attributeError(
          "executable",
          "if genrules produce executables, they are allowed only one output. "
              + "If you need the executable=1 argument, then you should split this genrule into "
              + "genrules producing single outputs");
    }

    Pair<CommandType, String> cmdTypeAndAttr = determineCommandTypeAndAttribute(ruleContext);

    ImmutableMap<Label, NestedSet<Artifact>> labelMap =
        collectSources(ruleContext.getPrerequisites("srcs"));
    NestedSetBuilder<Artifact> resolvedSrcsBuilder = NestedSetBuilder.stableOrder();
    labelMap.values().forEach(resolvedSrcsBuilder::addTransitive);
    NestedSet<Artifact> resolvedSrcs = resolvedSrcsBuilder.build();

    ImmutableList<ConfiguredTarget> toolchainPrerequisites =
        ruleContext.getToolchainContext().prerequisiteTargets().stream()
            .map(ConfiguredTargetAndData::getConfiguredTarget)
            .collect(toImmutableList());
    // The CommandHelper class makes an explicit copy of this in the constructor, so flattening
    // here should be benign.
    CommandHelper commandHelper =
        CommandHelper.builder(ruleContext)
            .addToolDependencies("tools")
            .addToolDependencies("toolchains")
            .addToolDependencies(toolchainPrerequisites)
            .addLabelMap(
                labelMap.entrySet().stream()
                    .collect(toImmutableMap(Map.Entry::getKey, e -> e.getValue().toList())))
            .build();

    if (ruleContext.hasErrors()) {
      return null;
    }

    CommandType cmdType = cmdTypeAndAttr.first;
    String cmdAttr = cmdTypeAndAttr.second;
    boolean expandToWindowsPath = cmdType == CommandType.WINDOWS_BATCH;

    String baseCommand = ruleContext.attributes().get(cmdAttr, Type.STRING);

    // Expand template variables and functions.
    CommandResolverContext commandResolverContext =
        new CommandResolverContext(
            ruleContext,
            resolvedSrcs,
            filesToBuild,
            /* makeVariableSuppliers= */ ImmutableList.of(),
            expandToWindowsPath);
    String command =
        ruleContext
            .getExpander(commandResolverContext)
            .withExecLocationsNoSrcs(commandHelper.getLabelMap(), expandToWindowsPath)
            .expand(cmdAttr, baseCommand);

    // Heuristically expand things that look like labels.
    if (ruleContext.attributes().get("heuristic_label_expansion", Type.BOOLEAN)) {
      command = commandHelper.expandLabelsHeuristically(command);
    }

    if (cmdType == CommandType.BASH) {
      // Add the genrule environment setup script before the actual shell command.
      command =
          String.format(
              "source %s; %s",
              ruleContext.getPrerequisiteArtifact("$genrule_setup").getExecPath(), command);
    }

    String messageAttr = ruleContext.attributes().get("message", Type.STRING);
    String message = messageAttr.isEmpty() ? "Executing genrule" : messageAttr;
    Label label = ruleContext.getLabel();
    OnDemandString progressMessage =
        new OnDemandString() {
          @Override
          public String toString() {
            return message + " " + label;
          }
        };

    Map<String, String> executionInfo = Maps.newLinkedHashMap();
    executionInfo.putAll(TargetUtils.getExecutionInfo(ruleContext.getRule()));

    if (ruleContext.attributes().get("local", Type.BOOLEAN)) {
      executionInfo.put("local", "");
    }

    ruleContext.getConfiguration().modifyExecutionInfo(executionInfo, GenRuleAction.MNEMONIC);

    NestedSetBuilder<Artifact> inputs = NestedSetBuilder.stableOrder();
    inputs.addTransitive(resolvedSrcs);
    inputs.addTransitive(commandHelper.getResolvedTools());
    if (cmdType == CommandType.BASH) {
      FileProvider genruleSetup = ruleContext.getPrerequisite("$genrule_setup", FileProvider.class);
      inputs.addTransitive(genruleSetup.getFilesToBuild());
    }
    if (ruleContext.hasErrors()) {
      return null;
    }

    CommandConstructor constructor;
    switch (cmdType) {
      case WINDOWS_BATCH:
        constructor = CommandHelper.buildWindowsBatchCommandConstructor(".genrule_script.bat");
        break;
      case WINDOWS_POWERSHELL:
        constructor = CommandHelper.buildWindowsPowershellCommandConstructor(".genrule_script.ps1");
        break;
      case BASH:
      default:
        // TODO(b/234923262): Take exec_group into consideration when selecting sh tools
        PathFragment shExecutable =
            ShToolchain.getPathForPlatform(
                ruleContext.getConfiguration(), ruleContext.getExecutionPlatform());
        constructor =
            CommandHelper.buildBashCommandConstructor(
                executionInfo, shExecutable, ".genrule_script.sh");
    }
    ImmutableList<String> argv = commandHelper.buildCommandLine(command, inputs, constructor);

    if (isStampingEnabled(ruleContext)) {
      inputs.add(ruleContext.getAnalysisEnvironment().getStableWorkspaceStatusArtifact());
      inputs.add(ruleContext.getAnalysisEnvironment().getVolatileWorkspaceStatusArtifact());
    }

    ruleContext.registerAction(
        new GenRuleAction(
            ruleContext.getActionOwner(),
            commandHelper.getResolvedTools(),
            inputs.build(),
            filesToBuild.toSet(),
            CommandLines.of(argv),
            ruleContext.getConfiguration().getActionEnvironment(),
            ImmutableMap.copyOf(executionInfo),
            progressMessage));

    RunfilesProvider runfilesProvider =
        RunfilesProvider.withData(
            // No runfiles provided if not a data dependency.
            Runfiles.EMPTY,
            // We only need to consider the outputs of a genrule. No need to visit the dependencies
            // of a genrule. They cross from the target into the exec configuration, because the
            // dependencies of a genrule are always built for the exec configuration.
            new Runfiles.Builder(
                    ruleContext.getWorkspaceName(),
                    ruleContext.getConfiguration().legacyExternalRunfiles())
                .addTransitiveArtifacts(filesToBuild)
                .build());

    return new RuleConfiguredTargetBuilder(ruleContext)
        .setFilesToBuild(filesToBuild)
        .setRunfilesSupport(null, getExecutable(ruleContext, filesToBuild))
        .addProvider(RunfilesProvider.class, runfilesProvider)
        .addNativeDeclaredProvider(
            InstrumentedFilesCollector.collect(
                ruleContext,
                new InstrumentationSpec(FileTypeSet.ANY_FILE).withSourceAttributes("srcs")))
        .build();
  }

  /** Collects sources from src attribute. */
  @ForOverride
  protected abstract ImmutableMap<Label, NestedSet<Artifact>> collectSources(
      List<? extends TransitiveInfoCollection> srcs) throws RuleErrorException;

  private static boolean isStampingEnabled(RuleContext ruleContext) {
    // This intentionally does not call AnalysisUtils.isStampingEnabled(). That method returns false
    // in the exec configuration (regardless of the attribute value), which is the behavior for
    // binaries, but not genrules.
    TriState stamp = ruleContext.attributes().get("stamp", BuildType.TRISTATE);
    return stamp == TriState.YES
        || (stamp == TriState.AUTO && ruleContext.getConfiguration().stampBinaries());
  }

  private enum CommandType {
    BASH,
    WINDOWS_BATCH,
    WINDOWS_POWERSHELL,
  }

  @Nullable
  private static Pair<CommandType, String> determineCommandTypeAndAttribute(
      RuleContext ruleContext) {
    AttributeMap attributeMap = ruleContext.attributes();
    if (ruleContext.isExecutedOnWindows()) {
      if (attributeMap.isAttributeValueExplicitlySpecified("cmd_ps")) {
        return Pair.of(CommandType.WINDOWS_POWERSHELL, "cmd_ps");
      }
      if (attributeMap.isAttributeValueExplicitlySpecified("cmd_bat")) {
        return Pair.of(CommandType.WINDOWS_BATCH, "cmd_bat");
      }
    }
    if (attributeMap.isAttributeValueExplicitlySpecified("cmd_bash")) {
      return Pair.of(CommandType.BASH, "cmd_bash");
    }
    if (attributeMap.isAttributeValueExplicitlySpecified("cmd")) {
      return Pair.of(CommandType.BASH, "cmd");
    }
    ruleContext.attributeError(
        "cmd",
        "missing value for `cmd` attribute, you can also set `cmd_ps` or `cmd_bat` on"
            + " Windows and `cmd_bash` on other platforms.");
    return null;
  }

  /**
   * Returns the executable artifact, if the rule is marked as executable and there is only one
   * artifact.
   */
  @Nullable
  private static Artifact getExecutable(RuleContext ruleContext, NestedSet<Artifact> filesToBuild) {
    if (!ruleContext.attributes().get("executable", Type.BOOLEAN)) {
      return null;
    }
    return filesToBuild.isSingleton() ? filesToBuild.getSingleton() : null;
  }

  /**
   * Implementation of {@link ConfigurationMakeVariableContext} used to expand variables in a
   * genrule command string.
   */
  private static final class CommandResolverContext extends ConfigurationMakeVariableContext {

    private final RuleContext ruleContext;
    private final NestedSet<Artifact> resolvedSrcs;
    private final NestedSet<Artifact> filesToBuild;
    private final boolean windowsPath;

    CommandResolverContext(
        RuleContext ruleContext,
        NestedSet<Artifact> resolvedSrcs,
        NestedSet<Artifact> filesToBuild,
        Iterable<? extends MakeVariableSupplier> makeVariableSuppliers,
        boolean windowsPath) {
      super(
          ruleContext.getRule().getPackage(),
          ruleContext.getConfiguration(),
          ruleContext.getDefaultTemplateVariableProviders(),
          makeVariableSuppliers);
      this.ruleContext = ruleContext;
      this.resolvedSrcs = resolvedSrcs;
      this.filesToBuild = filesToBuild;
      this.windowsPath = windowsPath;
    }

    @Override
    public String lookupVariable(String variableName) throws ExpansionException {
      String val = lookupVariableImpl(variableName);
      if (windowsPath) {
        return val.replace('/', '\\');
      }
      return val;
    }

    private String lookupVariableImpl(String variableName) throws ExpansionException {
      if (variableName.equals("SRCS")) {
        return Artifact.joinExecPaths(" ", resolvedSrcs.toList());
      }

      if (variableName.equals("<")) {
        return expandSingletonArtifact(resolvedSrcs, "$<", "input file");
      }

      if (variableName.equals("OUTS")) {
        return Artifact.joinExecPaths(" ", filesToBuild.toList());
      }

      if (variableName.equals("@")) {
        return expandSingletonArtifact(filesToBuild, "$@", "output file");
      }

      PathFragment ruleDirPackagePath = ruleContext.getPackageDirectory();
      PathFragment ruleDirExecPath =
          ruleContext.getBinOrGenfilesDirectory().getExecPath().getRelative(ruleDirPackagePath);

      if (variableName.equals("RULEDIR")) {
        // The output root directory. This variable expands to the package's root directory
        // in the genfiles tree.
        return ruleDirExecPath.getPathString();
      }

      if (variableName.equals("@D")) {
        // The output directory. If there is only one filename in outs,
        // this expands to the directory containing that file. If there are
        // multiple filenames, this variable instead expands to the
        // package's root directory in the genfiles tree, even if all the
        // generated files belong to the same subdirectory!
        if (filesToBuild.isSingleton()) {
          Artifact outputFile = filesToBuild.getSingleton();
          PathFragment relativeOutputFile = outputFile.getExecPath();
          if (!relativeOutputFile.isMultiSegment()) {
            // This should never happen, since the path should contain at
            // least a package name and a file name.
            throw new IllegalStateException(
                "$(@D) for genrule " + ruleContext.getLabel() + " has less than one segment");
          }
          return relativeOutputFile.getParentDirectory().getPathString();
        } else {
          return ruleDirExecPath.getPathString();
        }
      }

      return super.lookupVariable(variableName);
    }

    /**
     * Returns the path of the sole element "artifacts", generating an exception with an informative
     * error message iff the set is not a singleton. Used to expand "$<", "$@".
     */
    private static String expandSingletonArtifact(
        NestedSet<Artifact> artifacts, String variable, String artifactName)
        throws ExpansionException {
      if (artifacts.isEmpty()) {
        throw new ExpansionException("variable '" + variable + "' : no " + artifactName);
      } else if (!artifacts.isSingleton()) {
        throw new ExpansionException("variable '" + variable + "' : more than one " + artifactName);
      }
      return artifacts.getSingleton().getExecPathString();
    }
  }
}
