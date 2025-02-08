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
package com.google.devtools.build.lib.bazel.commands;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.devtools.build.lib.runtime.Command.BuildPhase.LOADS;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.NoBuildEvent;
import com.google.devtools.build.lib.analysis.NoBuildRequestFinishedEvent;
import com.google.devtools.build.lib.bazel.ResolvedEvent;
import com.google.devtools.build.lib.bazel.repository.RepositoryOrderEvent;
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkRepositoryFunction;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.WorkspaceFileValue;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.pkgcache.PackageOptions;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue;
import com.google.devtools.build.lib.rules.repository.ResolvedFileValue;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.runtime.LoadingPhaseThreadsOption;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.SyncCommand.Code;
import com.google.devtools.build.lib.skyframe.PackageLookupValue;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.InterruptedFailureDetails;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.XattrProvider;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.common.options.OptionsParsingResult;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.starlark.java.eval.Starlark;

/** Syncs all repositories specified in the workspace file */
@Command(
    name = SyncCommand.NAME,
    buildPhase = LOADS,
    options = {
      PackageOptions.class,
      KeepGoingOption.class,
      LoadingPhaseThreadsOption.class,
      SyncOptions.class
    },
    help = "resource:sync.txt",
    shortDescription = "Syncs all repositories specified in the workspace file",
    allowResidue = false)
public final class SyncCommand implements BlazeCommand {
  public static final String NAME = "sync";

  static final ImmutableSet<String> WHITELISTED_NATIVE_RULES =
      ImmutableSet.of("local_repository", "new_local_repository", "local_config_platform");

  private static void reportError(CommandEnvironment env, EvaluationResult<SkyValue> value) {
    if (value.getError().getException() != null) {
      env.getReporter().handle(Event.error(value.getError().getException().getMessage()));
    } else {
      env.getReporter().handle(Event.error(value.getError().toString()));
    }
  }

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    if (BuildLanguageOptions.ENABLE_BZLMOD.startsWith("+")) {
      // TODO: remove dead code.
      String errorMessage = "The sync command has been removed; use `bazel fetch --all` instead.";
      env.getReporter().handle(Event.error(errorMessage));
      return blazeCommandResultWithNoBuildReport(
          env, ExitCode.ANALYSIS_FAILURE, Code.REPOSITORY_FETCH_ERRORS, errorMessage);
    }

    try {
      env.getReporter()
          .post(
              new NoBuildEvent(
                  env.getCommandName(),
                  env.getCommandStartTime(),
                  true,
                  true,
                  env.getCommandId().toString()));
      env.syncPackageLoading(options);
      SkyframeExecutor skyframeExecutor = env.getSkyframeExecutor();

      SyncOptions syncOptions = options.getOptions(SyncOptions.class);
      if (syncOptions.configure) {
        skyframeExecutor.injectExtraPrecomputedValues(
            ImmutableList.of(
                PrecomputedValue.injected(
                    RepositoryDelegatorFunction.FORCE_FETCH_CONFIGURE,
                    env.getCommandId().toString())));
      } else {
        skyframeExecutor.injectExtraPrecomputedValues(
            ImmutableList.of(
                PrecomputedValue.injected(
                    RepositoryDelegatorFunction.FORCE_FETCH, env.getCommandId().toString())));
      }

      // Obtain the key for the top-level WORKSPACE file
      SkyKey packageLookupKey = PackageLookupValue.key(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER);
      LoadingPhaseThreadsOption threadsOption = options.getOptions(LoadingPhaseThreadsOption.class);
      EvaluationContext evaluationContext =
          EvaluationContext.newBuilder()
              .setParallelism(threadsOption.threads)
              .setEventHandler(env.getReporter())
              .build();
      EvaluationResult<SkyValue> packageLookupValue =
          skyframeExecutor.prepareAndGet(ImmutableSet.of(packageLookupKey), evaluationContext);
      if (packageLookupValue.hasError()) {
        reportError(env, packageLookupValue);
        return blazeCommandResultWithNoBuildReport(
            env,
            ExitCode.ANALYSIS_FAILURE,
            Code.PACKAGE_LOOKUP_ERROR,
            packageLookupValue.getError(packageLookupKey).toString());
      }
      RootedPath workspacePath =
          ((PackageLookupValue) packageLookupValue.get(packageLookupKey))
              .getRootedPath(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER);
      SkyKey workspace = WorkspaceFileValue.key(workspacePath);

      // read and evaluate the WORKSPACE file to its end
      ImmutableList.Builder<String> repositoryOrder = new ImmutableList.Builder<>();
      Set<String> namesSeen = new HashSet<>();
      WorkspaceFileValue fileValue = null;
      while (workspace != null) {
        EvaluationResult<SkyValue> value =
            skyframeExecutor.prepareAndGet(ImmutableSet.of(workspace), evaluationContext);
        if (value.hasError()) {
          reportError(env, value);
          return blazeCommandResultWithNoBuildReport(
              env,
              ExitCode.ANALYSIS_FAILURE,
              Code.WORKSPACE_EVALUATION_ERROR,
              value.getError(workspace).toString());
        }
        fileValue = (WorkspaceFileValue) value.get(workspace);
        for (Rule rule : fileValue.getPackage().getTargets(Rule.class)) {
          String name = rule.getName();
          if (!namesSeen.contains(name)) {
            repositoryOrder.add(name);
            namesSeen.add(name);
          }
        }
        workspace = fileValue.next();
      }
      env.getReporter()
          .post(
              genericArgsCall(
                  "register_toolchains",
                  fileValue.getPackage().getRegisteredToolchains().stream()
                      .map(TargetPattern::getOriginalPattern)
                      .collect(toImmutableList())));
      env.getReporter()
          .post(
              genericArgsCall(
                  "register_execution_platforms",
                  fileValue.getPackage().getRegisteredExecutionPlatforms().stream()
                      .map(TargetPattern::getOriginalPattern)
                      .collect(toImmutableList())));
      env.getReporter().post(new RepositoryOrderEvent(repositoryOrder.build()));

      // take all Starlark workspace rules and get their values
      ImmutableSet.Builder<SkyKey> repositoriesToFetch = new ImmutableSet.Builder<>();
      for (Rule rule : fileValue.getPackage().getTargets(Rule.class)) {
        if (rule.getRuleClass().equals("bind")) {
          // The bind rule is special in that the name is not that of an external repository.
          // Moreover, it is not affected by the invalidation mechanism as there is nothing to
          // fetch anyway. So the only task remaining is to record the use of "bind" for whoever
          // collects resolved information.
          env.getReporter().post(resolveBind(rule));
        } else if (shouldSync(rule, syncOptions)) {
          // TODO(aehlig): avoid the detour of serializing and then parsing the repository name
          try {
            repositoriesToFetch.add(
                RepositoryDirectoryValue.key(RepositoryName.create(rule.getName())));
          } catch (LabelSyntaxException e) {
            String errorMessage =
                String.format(
                    "Internal error queuing %s to fetch: %s", rule.getName(), e.getMessage());
            env.getReporter().handle(Event.error(errorMessage));
            return blazeCommandResultWithNoBuildReport(
                env, ExitCode.BLAZE_INTERNAL_ERROR, Code.REPOSITORY_NAME_INVALID, errorMessage);
          }
        }
      }
      EvaluationResult<SkyValue> fetchValue;
      fetchValue = skyframeExecutor.prepareAndGet(repositoriesToFetch.build(), evaluationContext);
      if (fetchValue.hasError()) {
        reportError(env, fetchValue);
        return blazeCommandResultWithNoBuildReport(
            env,
            ExitCode.ANALYSIS_FAILURE,
            Code.REPOSITORY_FETCH_ERRORS,
            "Repository fetch failure.");
      }
    } catch (InterruptedException e) {
      String errorMessage = "Sync interrupted: " + e.getMessage();
      env.getReporter().handle(Event.error(errorMessage));
      reportNoBuildRequestFinished(env, ExitCode.INTERRUPTED);
      return BlazeCommandResult.detailedExitCode(
          InterruptedFailureDetails.detailedExitCode(errorMessage));
    } catch (AbruptExitException e) {
      env.getReporter().handle(Event.error("Unknown error: " + e.getMessage()));
      reportNoBuildRequestFinished(env, ExitCode.LOCAL_ENVIRONMENTAL_ERROR);
      return BlazeCommandResult.detailedExitCode(e.getDetailedExitCode());
    }
    reportNoBuildRequestFinished(env, ExitCode.SUCCESS);
    return BlazeCommandResult.success();
  }

  private static boolean shouldSync(Rule rule, SyncOptions options) {
    if (!rule.getRuleClassObject().getWorkspaceOnly()) {
      // We should only sync workspace rules
      return false;
    }
    if (options.only != null && !options.only.isEmpty() && !options.only.contains(rule.getName())) {
      // There is a whitelist of what to sync, but the rule is not in this white list
      return false;
    }
    if (options.configure) {
      // If this is only a configure run, only sync Starlark rules that
      // declare themselves as configure-like.
      return StarlarkRepositoryFunction.isConfigureRule(rule);
    }
    if (rule.getRuleClassObject().isStarlark()) {
      // Starlark rules are all whitelisted
      return true;
    }
    return WHITELISTED_NATIVE_RULES.contains(rule.getRuleClassObject().getName());
  }

  private static ResolvedEvent resolveBind(Rule rule) {
    String name = rule.getName();
    Label actual = (Label) rule.getAttr("actual");
    String nativeCommand =
        String.format(
            "bind(name = %s, actual = %s)",
            Starlark.repr(name), Starlark.repr(actual.getCanonicalForm()));

    return new ResolvedEvent() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public Object getResolvedInformation(XattrProvider xattrProvider) {
        return ImmutableMap.<String, Object>builder()
            .put(ResolvedFileValue.ORIGINAL_RULE_CLASS, "bind")
            .put(
                ResolvedFileValue.ORIGINAL_ATTRIBUTES,
                ImmutableMap.<String, Object>of("name", name, "actual", actual))
            .put(ResolvedFileValue.NATIVE, nativeCommand)
            .buildOrThrow();
      }
    };
  }

  private static ResolvedEvent genericArgsCall(String ruleName, List<String> args) {
    // For the name attribute we are in a slightly tricky situation, as the ResolvedEvents are
    // designed for external repositories and hence are indexted by their unique
    // names. Technically, however, things like the list of toolchains are not associated with any
    // external repository (but still a workspace command); so we take a name that syntactially can
    // never be the name of a repository, as it starts with a '//'.
    String name = "//external/" + ruleName;
    StringBuilder nativeCommandBuilder = new StringBuilder().append(ruleName).append("(");
    nativeCommandBuilder.append(
        args.stream().map(Starlark::repr).collect(Collectors.joining(", ")));
    nativeCommandBuilder.append(")");
    String nativeCommand = nativeCommandBuilder.toString();

    return new ResolvedEvent() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public Object getResolvedInformation(XattrProvider xattrProvider) {
        return ImmutableMap.<String, Object>builder()
            .put(ResolvedFileValue.ORIGINAL_RULE_CLASS, ruleName)
            .put(
                ResolvedFileValue.ORIGINAL_ATTRIBUTES,
                // The original attributes are a bit of a problem, as the arguments to
                // the rule do not at all look like those of a repository rule:
                // they're all positional, and, in particular, there is no keyword argument
                // called "name". A lot of uses of the resolved file, however, blindly assume
                // that "name" is always part of the original arguments; so we provide our
                // fake name here as well, and the actual arguments under the keyword "*args",
                // which hopefully reminds everyone inspecting the file of the actual syntax of
                // that rule. Note that the original arguments are always ignored when bazel uses
                // a resolved file instead of a workspace file.
                ImmutableMap.<String, Object>of("name", name, "*args", args))
            .put(ResolvedFileValue.NATIVE, nativeCommand)
            .build();
      }
    };
  }

  private static BlazeCommandResult blazeCommandResultWithNoBuildReport(
      CommandEnvironment env, ExitCode exitCode, Code syncCommandCode, String message) {
    reportNoBuildRequestFinished(env, exitCode);
    return createFailedBlazeCommandResult(syncCommandCode, message);
  }

  private static void reportNoBuildRequestFinished(CommandEnvironment env, ExitCode exitCode) {
    long finishTimeMillis = env.getRuntime().getClock().currentTimeMillis();
    env.getReporter().post(new NoBuildRequestFinishedEvent(exitCode, finishTimeMillis));
  }

  private static BlazeCommandResult createFailedBlazeCommandResult(
      Code syncCommandCode, String message) {
    return BlazeCommandResult.detailedExitCode(
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setSyncCommand(
                    FailureDetails.SyncCommand.newBuilder().setCode(syncCommandCode).build())
                .build()));
  }
}
