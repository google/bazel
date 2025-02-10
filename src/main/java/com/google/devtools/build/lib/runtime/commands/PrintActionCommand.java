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
package com.google.devtools.build.lib.runtime.commands;

import static com.google.devtools.build.lib.runtime.Command.BuildPhase.ANALYZES;

import com.google.common.base.Predicate;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.extra.DetailedExtraActionInfo;
import com.google.devtools.build.lib.actions.extra.ExtraActionSummary;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.PrintActionVisitor;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.BuildResult;
import com.google.devtools.build.lib.buildtool.BuildTool;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.ConfiguredAttributeMapper;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.LoadingOptions;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.PrintActionCommand.Code;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.InterruptedFailureDetails;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParsingResult;
import com.google.protobuf.TextFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Implements 'blaze print_action' by finding the Configured target[s] for the file[s] listed. */
@Command(
    name = "print_action",
    buildPhase = ANALYZES,
    inheritsOptionsFrom = {BuildCommand.class},
    options = {PrintActionCommand.PrintActionOptions.class},
    help = "resource:print_action.txt",
    shortDescription = "Prints the command line args for compiling a file.",
    completion = "label",
    allowResidue = true)
public final class PrintActionCommand implements BlazeCommand {

  /**
   * Options for print_action, used to parse command-line arguments.
   */
  public static class PrintActionOptions extends OptionsBase {
    @Option(
        name = "print_action_mnemonics",
        allowMultiple = true,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help =
            "Lists which mnemonics to filter print_action data by, no filtering takes place "
                + "when left empty.")
    public List<String> printActionMnemonics = new ArrayList<>();
  }

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    LoadingOptions loadingOptions =
        options.getOptions(LoadingOptions.class);

    PrintActionOptions printActionOptions = options.getOptions(PrintActionOptions.class);
    PrintActionRunner runner = new PrintActionRunner(loadingOptions.compileOneDependency, options,
        env.getReporter().getOutErr(),
        options.getResidue(), Sets.newHashSet(printActionOptions.printActionMnemonics));
    return BlazeCommandResult.detailedExitCode(runner.printActionsForTargets(env));
  }

  /**
   * Contains all the logic to get extra_action information for print actions.
   * Maintains requires state to perform required analyses.
   */
  private class PrintActionRunner {
    private final boolean compileOneDependency;
    private final OptionsParsingResult options;
    private final OutErr outErr;
    private final List<String> requestedTargets;
    private final boolean keepGoing;
    private final ExtraActionSummary.Builder summaryBuilder;
    private final Predicate<ActionAnalysisMetadata> actionMnemonicMatcher;

    public PrintActionRunner(
        boolean compileOneDependency,
        OptionsParsingResult options,
        OutErr outErr,
        List<String> requestedTargets,
        final Set<String> printActionMnemonics) {
      this.compileOneDependency = compileOneDependency;
      this.options = options;
      this.outErr = outErr;
      this.requestedTargets = requestedTargets;
      keepGoing = options.getOptions(KeepGoingOption.class).keepGoing;
      summaryBuilder = ExtraActionSummary.newBuilder();
      actionMnemonicMatcher = new Predicate<ActionAnalysisMetadata>() {
        @Override
        public boolean apply(ActionAnalysisMetadata action) {
          return printActionMnemonics.isEmpty()
              || printActionMnemonics.contains(action.getMnemonic());
        }
      };
    }

    private DetailedExitCode printActionsForTargets(CommandEnvironment env) {
      BuildResult result;
      try {
        result = gatherActionsForTargets(env, requestedTargets);
      } catch (PrintActionException e) {
        return DetailedExitCode.of(e.createFailureDetail());
      } catch (InterruptedException e) {
        String message = "print_action: action gathering interrupted";
        env.getReporter().handle(Event.error(message));
        return InterruptedFailureDetails.detailedExitCode(message);
      }
      if (hasFatalBuildFailure(result)) {
        env.getReporter().handle(Event.error("Build failed when printing actions"));
        return result.getDetailedExitCode();
      }
      String action = TextFormat.printer().printToString(summaryBuilder);
      if (!action.isEmpty()) {
        outErr.printOut(action);
        return result.getDetailedExitCode();
      } else {
        String message = "no actions to print were found";
        env.getReporter().handle(Event.error(message));
        return DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setPrintActionCommand(
                    FailureDetails.PrintActionCommand.newBuilder().setCode(Code.ACTIONS_NOT_FOUND))
                .build());
      }
    }

    private BuildResult gatherActionsForTargets(CommandEnvironment env, List<String> targets)
        throws PrintActionException, InterruptedException {
      BlazeRuntime runtime = env.getRuntime();
      String commandName = PrintActionCommand.this.getClass().getAnnotation(Command.class).name();

      BuildRequest request =
          BuildRequest.builder()
              .setCommandName(commandName)
              .setId(env.getCommandId())
              .setOptions(options)
              .setStartupOptions(runtime.getStartupOptionsProvider())
              .setOutErr(outErr)
              .setTargets(targets)
              .setStartTimeMillis(env.getCommandStartTime())
              .build();
      BuildResult result = new BuildTool(env).processRequest(request, null, options);
      if (hasFatalBuildFailure(result)) {
        return result;
      }

      ActionGraph actionGraph = env.getSkyframeExecutor().getActionGraph(env.getReporter());

      for (ConfiguredTarget configuredTarget : result.getActualTargets()) {
        NestedSet<Artifact> filesToCompile = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
        OutputGroupInfo outputGroupInfo = OutputGroupInfo.get(configuredTarget);
        if (outputGroupInfo != null) {
          filesToCompile =
              outputGroupInfo.getOutputGroup(OutputGroupInfo.FILES_TO_COMPILE);
        }
        if (!filesToCompile.isEmpty()) {
          try {
            if (compileOneDependency) {
              gatherActionsForFiles(
                  configuredTarget,
                  env,
                  actionGraph,
                  env.getSkyframeExecutor().getActionKeyContext(),
                  targets);
            } else {
              Target target;
              try {
                target =
                    env.getPackageManager()
                        .getTarget(env.getReporter(), configuredTarget.getLabel());
              } catch (NoSuchTargetException | NoSuchPackageException e) {
                String message = "Failed to find target to gather actions: " + e.getMessage();
                env.getReporter().handle(Event.error(message));
                throw new PrintActionException(message, Code.TARGET_NOT_FOUND);
              }
              gatherActionsForTarget(
                  configuredTarget,
                  target,
                  actionGraph,
                  env.getSkyframeExecutor().getActionKeyContext());
            }
          } catch (CommandLineExpansionException e) {
            String message = "Error expanding command line: " + e;
            env.getReporter().handle(Event.error(null, message));
            throw new PrintActionException(message, Code.COMMAND_LINE_EXPANSION_FAILURE);
          }
        } else {
          String message = configuredTarget + " is not a supported target kind";
          env.getReporter().handle(Event.error(null, message));
          throw new PrintActionException(message, Code.TARGET_KIND_UNSUPPORTED);
        }
      }
      return result;
    }

    private void gatherActionsForFiles(
        ConfiguredTarget configuredTarget,
        CommandEnvironment env,
        ActionGraph actionGraph,
        ActionKeyContext actionKeyContext,
        List<String> files)
        throws CommandLineExpansionException, InterruptedException {
      Set<String> filesDesired = new LinkedHashSet<>(files);
      ActionFilter filter = new ActionFilter(filesDesired, actionMnemonicMatcher);
      gatherActionsForFile(configuredTarget, filter, env, actionGraph, actionKeyContext);
    }

    private void gatherActionsForTarget(
        ConfiguredTarget configuredTarget,
        Target target,
        ActionGraph actionGraph,
        ActionKeyContext actionKeyContext)
        throws CommandLineExpansionException, InterruptedException {
      if (!(target instanceof Rule)) {
        return;
      }

      PrintActionVisitor visitor = new PrintActionVisitor(actionGraph, configuredTarget,
          actionMnemonicMatcher);

      // TODO(jvg): do we want to support ruleConfiguredTarget.getOutputArtifacts()?
      // We do for extra actions, but as we're past the action graph building phase,
      // we cannot call it without risking to trigger creation of OutputArtifacts post
      // graph building phase (not allowed). Right now we do not need them for our scenarios.
      visitor.visitWhiteNodes(
          configuredTarget.getProvider(FileProvider.class).getFilesToBuild().toList());

      Iterable<ActionAnalysisMetadata> actions = visitor.getActions();
      for (ActionAnalysisMetadata action : actions) {
        if (action instanceof Action) {
          DetailedExtraActionInfo.Builder detail = DetailedExtraActionInfo.newBuilder();
          detail.setAction(((Action) action).getExtraActionInfo(actionKeyContext));
          summaryBuilder.addAction(detail);
        }
      }
    }

    /**
     * Looks for files to compile in the given configured target and outputs the corresponding
     * extra_action if the filter evaluates to {@code true}.
     */
    private void gatherActionsForFile(
        ConfiguredTarget configuredTarget,
        ActionFilter filter,
        CommandEnvironment env,
        ActionGraph actionGraph,
        ActionKeyContext actionKeyContext)
        throws CommandLineExpansionException, InterruptedException {
      NestedSet<Artifact> artifacts = OutputGroupInfo.get(configuredTarget)
          .getOutputGroup(OutputGroupInfo.FILES_TO_COMPILE);

      if (artifacts.isEmpty()) {
        return;
      }

      for (Artifact artifact : artifacts.toList()) {
        ActionAnalysisMetadata action = actionGraph.getGeneratingAction(artifact);
        if (filter.shouldOutput(action, configuredTarget, env)) {
          if (action instanceof Action) {
            DetailedExtraActionInfo.Builder detail = DetailedExtraActionInfo.newBuilder();
            detail.setAction(((Action) action).getExtraActionInfo(actionKeyContext));
            summaryBuilder.addAction(detail);
          }
        }
      }
    }

    private boolean hasFatalBuildFailure(BuildResult result) {
      return result.getActualTargets() == null || (!result.getSuccess() && !keepGoing);
    }
  }

  /**
   * A stateful filter that keeps track of which files have already been covered. This makes it such
   * that blaze only prints out one action protobuf per file. This is important for headers. In
   * addition, this also handles C++ header files, which are not considered to be action inputs by
   * blaze (due to include scanning).
   *
   * <p>As caveats, this only works for files that are given as proper relative paths, rather than
   * using target syntax, and only if the current working directory is the client root.
   */
  private static class ActionFilter {
    private final Set<String> filesDesired;
    private final Predicate<ActionAnalysisMetadata> actionMnemonicMatcher;

    private ActionFilter(
        Set<String> filesDesired, Predicate<ActionAnalysisMetadata> actionMnemonicMatcher) {
      this.filesDesired = filesDesired;
      this.actionMnemonicMatcher = actionMnemonicMatcher;
    }

    public boolean shouldOutput(
        ActionAnalysisMetadata action, ConfiguredTarget configuredTarget, CommandEnvironment env)
        throws InterruptedException {
      if (action == null) {
        return false;
      }
      // Check all the inputs for the configured target against the file we want argv for.
      LinkedHashSet<Artifact> artifacts = Sets.newLinkedHashSet();
      artifacts.addAll(action.getInputs().toList());
      artifacts.addAll(action.getSchedulingDependencies().toList());

      for (Artifact input : artifacts) {
        if (filesDesired.remove(input.getRootRelativePath().getSafePathString())) {
          return actionMnemonicMatcher.apply(action);
        }
      }

      // C++ header files show up in the dependency on the Target, but not the ConfiguredTarget, so
      // we also check the target's header files there.
      Rule rule;
      try {
        rule =
            (Rule)
                env.getPackageManager().getTarget(env.getReporter(), configuredTarget.getLabel());
      } catch (NoSuchTargetException | NoSuchPackageException e) {
        env.getReporter().handle(Event.error("Failed to find target to determine output."));
        return false;
      }
      if (!rule.isAttrDefined("hdrs", BuildType.LABEL_LIST)) {
        return false;
      }

      List<Label> hdrs =
          ConfiguredAttributeMapper.of(
                  rule,
                  configuredTarget.getConfigConditions(),
                  configuredTarget.getConfigurationChecksum(),
                  /*alwaysSucceed=*/ false)
              .get("hdrs", BuildType.LABEL_LIST);
      if (hdrs != null) {
        for (Label hdrLabel : hdrs) {
          if (filesDesired.remove(hdrLabel.toPathFragment().getPathString())) {
            return actionMnemonicMatcher.apply(action);
          }
        }
      }
      return false; // no match
    }
  }

  private static class PrintActionException extends Exception {
    private final FailureDetails.PrintActionCommand.Code detailedCode;

    private PrintActionException(String message, Code detailedCode) {
      super(message);
      this.detailedCode = detailedCode;
    }

    private FailureDetail createFailureDetail() {
      return FailureDetail.newBuilder()
          .setMessage(getMessage())
          .setPrintActionCommand(
              FailureDetails.PrintActionCommand.newBuilder().setCode(detailedCode))
          .build();
    }
  }
}
