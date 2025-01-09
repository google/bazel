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
package com.google.devtools.build.lib.skyframe.actiongraph.v2;

import static com.google.devtools.build.lib.query2.aquery.AqueryUtils.getActionInputs;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandAction;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2;
import com.google.devtools.build.lib.analysis.AspectValue;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.actions.AbstractFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.ParameterFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.Substitution;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionAction;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionException;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.starlark.UnresolvedSymlinkAction;
import com.google.devtools.build.lib.buildeventstream.BuildEvent;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.query2.aquery.AqueryActionFilter;
import com.google.devtools.build.lib.query2.aquery.AqueryUtils;
import com.google.devtools.build.lib.skyframe.RuleConfiguredTargetValue;
import com.google.devtools.build.lib.util.Pair;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;

/**
 * Encapsulates necessary functionality to dump the current skyframe state of the action graph to
 * proto format.
 */
public class ActionGraphDump {
  private final ActionKeyContext actionKeyContext = new ActionKeyContext();
  private final Set<String> actionGraphTargets;
  private final KnownArtifacts knownArtifacts;
  private final KnownConfigurations knownConfigurations;
  private final KnownNestedSets knownNestedSets;
  private final KnownAspectDescriptors knownAspectDescriptors;
  private final KnownTargets knownTargets;
  @Nullable private final AqueryActionFilter actionFilters;
  private final boolean includeActionCmdLine;
  private final boolean includeArtifacts;
  private final boolean includePrunedInputs;
  private final boolean includeParamFiles;
  private final boolean includeFileWriteContents;
  private final AqueryOutputHandler aqueryOutputHandler;
  private final ExtendedEventHandler eventHandler;

  private Map<String, Iterable<String>> paramFileNameToContentMap;

  public ActionGraphDump(
      boolean includeActionCmdLine,
      boolean includeArtifacts,
      boolean includePrunedInputs,
      AqueryActionFilter actionFilters,
      boolean includeParamFiles,
      boolean includeFileWriteContents,
      AqueryOutputHandler aqueryOutputHandler,
      ExtendedEventHandler eventHandler) {
    this(
        /* actionGraphTargets= */ ImmutableList.of("..."),
        includeActionCmdLine,
        includeArtifacts,
        includePrunedInputs,
        actionFilters,
        includeParamFiles,
        includeFileWriteContents,
        aqueryOutputHandler,
        eventHandler);
  }

  public ActionGraphDump(
      List<String> actionGraphTargets,
      boolean includeActionCmdLine,
      boolean includeArtifacts,
      boolean includePrunedInputs,
      AqueryActionFilter actionFilters,
      boolean includeParamFiles,
      boolean includeFileWriteContents,
      AqueryOutputHandler aqueryOutputHandler,
      ExtendedEventHandler eventHandler) {
    this.actionGraphTargets = ImmutableSet.copyOf(actionGraphTargets);
    this.includeActionCmdLine = includeActionCmdLine;
    this.includeArtifacts = includeArtifacts;
    this.includePrunedInputs = includePrunedInputs;
    this.actionFilters = actionFilters;
    this.includeParamFiles = includeParamFiles;
    this.includeFileWriteContents = includeFileWriteContents;
    this.aqueryOutputHandler = aqueryOutputHandler;
    this.eventHandler = eventHandler;

    KnownRuleClassStrings knownRuleClassStrings = new KnownRuleClassStrings(aqueryOutputHandler);
    knownArtifacts = new KnownArtifacts(aqueryOutputHandler);
    knownConfigurations = new KnownConfigurations(aqueryOutputHandler);
    knownNestedSets = new KnownNestedSets(aqueryOutputHandler, knownArtifacts);
    knownAspectDescriptors = new KnownAspectDescriptors(aqueryOutputHandler);
    knownTargets = new KnownTargets(aqueryOutputHandler, knownRuleClassStrings);
  }

  public ActionKeyContext getActionKeyContext() {
    return actionKeyContext;
  }

  private boolean includeInActionGraph(String labelString) {
    if (actionGraphTargets.size() == 1
        && Iterables.getOnlyElement(actionGraphTargets).equals("...")) {
      return true;
    }
    return actionGraphTargets.contains(labelString);
  }

  private void dumpSingleAction(ConfiguredTarget configuredTarget, ActionAnalysisMetadata action)
      throws CommandLineExpansionException, InterruptedException, IOException,
          TemplateExpansionException {

    // Store the content of param files.
    if (includeParamFiles
        && (action instanceof ParameterFileWriteAction parameterFileWriteAction)) {

      Iterable<String> fileContent = parameterFileWriteAction.getArguments();
      String paramFileExecPath = action.getPrimaryOutput().getExecPathString();
      getParamFileNameToContentMap().put(paramFileExecPath, fileContent);
    }

    if (actionFilters != null
        && !AqueryUtils.matchesAqueryFilters(action, actionFilters, includePrunedInputs)) {
      return;
    }

    // Dereference any aliases that might be present.
    configuredTarget = configuredTarget.getActual();

    Preconditions.checkState(configuredTarget instanceof RuleConfiguredTarget);
    Pair<String, String> targetIdentifier =
        new Pair<>(
            configuredTarget.getLabel().toString(),
            ((RuleConfiguredTarget) configuredTarget).getRuleClassString());
    AnalysisProtosV2.Action.Builder actionBuilder =
        AnalysisProtosV2.Action.newBuilder()
            .setMnemonic(action.getMnemonic())
            .setTargetId(knownTargets.dataToIdAndStreamOutputProto(targetIdentifier));

    if (action instanceof ActionExecutionMetadata actionExecutionMetadata) {
      actionBuilder
          .setActionKey(
              actionExecutionMetadata.getKey(getActionKeyContext(), /*artifactExpander=*/ null))
          .setDiscoversInputs(actionExecutionMetadata.discoversInputs());
    }

    // store environment
    if (action instanceof AbstractAction spawnAction && action instanceof CommandAction) {
      // Some actions (e.g. CppCompileAction) don't override getEnvironment, but only
      // getEffectiveEnvironment. Since calling the latter with an empty client env returns the
      // fixed part of the full ActionEnvironment with the default implementations provided by
      // AbstractAction, we can call getEffectiveEnvironment here to handle these actions as well.
      // TODO(twerth): This handles the fixed environment. We probably want to output the inherited
      // environment as well.
      ImmutableMap<String, String> fixedEnvironment =
          spawnAction.getEffectiveEnvironment(ImmutableMap.of());
      for (Map.Entry<String, String> environmentVariable : fixedEnvironment.entrySet()) {
        actionBuilder.addEnvironmentVariables(
            AnalysisProtosV2.KeyValuePair.newBuilder()
                .setKey(environmentVariable.getKey())
                .setValue(environmentVariable.getValue())
                .build());
      }
    }

    if (includeActionCmdLine && action instanceof CommandAction commandAction) {
      actionBuilder.addAllArguments(commandAction.getArguments());
    }

    if (action instanceof AbstractFileWriteAction.FileContentsProvider) {
      actionBuilder.setIsExecutable(
          ((AbstractFileWriteAction.FileContentsProvider) action).makeExecutable());
      if (includeFileWriteContents) {
        String contents =
            ((AbstractFileWriteAction.FileContentsProvider) action).getFileContents(eventHandler);
        actionBuilder.setFileContents(contents);
      }
    }


    if (action instanceof UnresolvedSymlinkAction) {
      actionBuilder.setUnresolvedSymlinkTarget(((UnresolvedSymlinkAction) action).getTarget());
    }

    // Include the content of param files in output.
    if (includeParamFiles) {
      // Assumption: if an Action takes a params file as an input, it will be used
      // to provide params to the command.
      for (Artifact input : getActionInputs(action, includePrunedInputs).toList()) {
        String inputFileExecPath = input.getExecPathString();
        if (getParamFileNameToContentMap().containsKey(inputFileExecPath)) {
          AnalysisProtosV2.ParamFile paramFile =
              AnalysisProtosV2.ParamFile.newBuilder()
                  .setExecPath(inputFileExecPath)
                  .addAllArguments(getParamFileNameToContentMap().get(inputFileExecPath))
                  .build();
          actionBuilder.addParamFiles(paramFile);
        }
      }
    }
    Map<String, String> executionInfo = action.getExecutionInfo();
    for (Map.Entry<String, String> info : executionInfo.entrySet()) {
      actionBuilder.addExecutionInfo(
          AnalysisProtosV2.KeyValuePair.newBuilder()
              .setKey(info.getKey())
              .setValue(info.getValue()));
    }

    ActionOwner actionOwner = action.getOwner();
    if (actionOwner != null) {
      BuildEvent event = actionOwner.getBuildConfigurationEvent();
      actionBuilder.setConfigurationId(knownConfigurations.dataToIdAndStreamOutputProto(event));
      if (actionOwner.getExecutionPlatform() != null) {
        actionBuilder.setExecutionPlatform(actionOwner.getExecutionPlatform().label().toString());
      }

      // Store aspects.
      // Iterate through the aspect path and dump the aspect descriptors.
      // In the case of aspect-on-aspect, AspectDescriptors are listed in topological order
      // of the configured target graph.
      // e.g. [A, B] would imply that aspect A is applied on top of aspect B.
      for (AspectDescriptor aspectDescriptor : actionOwner.getAspectDescriptors().reverse()) {
        actionBuilder.addAspectDescriptorIds(
            knownAspectDescriptors.dataToIdAndStreamOutputProto(aspectDescriptor));
      }
    }

    if (includeArtifacts) {
      // Store inputs.
      NestedSet<Artifact> inputs = getActionInputs(action, includePrunedInputs);
      if (!inputs.isEmpty()) {
        actionBuilder.addInputDepSetIds(knownNestedSets.dataToIdAndStreamOutputProto(inputs));
      }

      // Store outputs.
      for (Artifact artifact : action.getOutputs()) {
        actionBuilder.addOutputIds(knownArtifacts.dataToIdAndStreamOutputProto(artifact));
      }

      actionBuilder.setPrimaryOutputId(
          knownArtifacts.dataToIdAndStreamOutputProto(action.getPrimaryOutput()));
    }

    if (action instanceof TemplateExpansionAction templateExpansionAction) {
      actionBuilder.setTemplateContent(AqueryUtils.getTemplateContent(templateExpansionAction));

      for (Substitution substitution : templateExpansionAction.getSubstitutions()) {
        try {
          actionBuilder.addSubstitutions(
              AnalysisProtosV2.KeyValuePair.newBuilder()
                  .setKey(substitution.getKey())
                  .setValue(substitution.getValue()));
        } catch (EvalException e) {
          throw new TemplateExpansionException("Failed to expand template", e);
        }
      }
    }

    aqueryOutputHandler.outputAction(actionBuilder.build());
  }

  public void dumpAspect(
      @Nullable AspectValue aspectValue, ConfiguredTargetValue configuredTargetValue)
      throws CommandLineExpansionException,
          InterruptedException,
          IOException,
          TemplateExpansionException {
    // It's possible for a value from a previous build on the same server to be missing
    // e.g. after having cleared the analysis cache.
    if (aspectValue == null) {
      return;
    }

    ConfiguredTarget configuredTarget = configuredTargetValue.getConfiguredTarget();
    if (!includeInActionGraph(configuredTarget.getLabel().toString())) {
      return;
    }
    for (ActionAnalysisMetadata action : aspectValue.getActions()) {
      dumpSingleAction(configuredTarget, action);
    }
  }

  public void dumpConfiguredTarget(RuleConfiguredTargetValue configuredTargetValue)
      throws CommandLineExpansionException, InterruptedException, IOException,
          TemplateExpansionException {
    ConfiguredTarget configuredTarget = configuredTargetValue.getConfiguredTarget();
    if (!includeInActionGraph(configuredTarget.getLabel().toString())) {
      return;
    }
    for (ActionAnalysisMetadata action : configuredTargetValue.getActions()) {
      dumpSingleAction(configuredTarget, action);
    }
  }

  /** Lazy initialization of paramFileNameToContentMap. */
  private Map<String, Iterable<String>> getParamFileNameToContentMap() {
    if (paramFileNameToContentMap == null) {
      paramFileNameToContentMap = new HashMap<>();
    }
    return paramFileNameToContentMap;
  }
}
