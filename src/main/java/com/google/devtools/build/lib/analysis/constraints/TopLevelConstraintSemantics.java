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

package com.google.devtools.build.lib.analysis.constraints;

import static java.util.stream.Collectors.joining;

import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.IncompatiblePlatformProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.configuredtargets.OutputFileConfiguredTarget;
import com.google.devtools.build.lib.analysis.constraints.SupportedEnvironmentsProvider.RemovedEnvironmentCulprit;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.EnvironmentLabels;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.PackageManager;
import com.google.devtools.build.lib.server.FailureDetails.Analysis;
import com.google.devtools.build.lib.server.FailureDetails.Analysis.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.AbstractSaneAnalysisException;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.util.DetailedExitCode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Constraint semantics that apply to top-level targets.
 *
 * <p>Top-level targets are "special" because they have no parents that can assert expected
 * environment compatibility. So these expectations have to be declared by other means.
 *
 * <p>For all other targets see {@link ConstraintSemantics}.
 */
public class TopLevelConstraintSemantics {
  private final RuleContextConstraintSemantics constraintSemantics;
  private final PackageManager packageManager;
  private final Function<BuildConfigurationKey, BuildConfigurationValue> configurationProvider;
  private final ExtendedEventHandler eventHandler;
  private static final String TARGET_INCOMPATIBLE_ERROR_TEMPLATE =
      "Target %s is incompatible and cannot be built, but was explicitly requested.%s";

  /**
   * Constructor with helper classes for loading targets.
   *
   * @param constraintSemantics core constraints implementation logic
   * @param packageManager object for retrieving loaded targets
   * @param configurationProvider gets configurations from {@link ConfiguredTarget}s
   * @param eventHandler the build's event handler
   */
  public TopLevelConstraintSemantics(
      RuleContextConstraintSemantics constraintSemantics,
      PackageManager packageManager,
      Function<BuildConfigurationKey, BuildConfigurationValue> configurationProvider,
      ExtendedEventHandler eventHandler) {
    this.constraintSemantics = constraintSemantics;
    this.packageManager = packageManager;
    this.configurationProvider = configurationProvider;
    this.eventHandler = eventHandler;
  }

  static class MissingEnvironment {
    private final Label environment;
    @Nullable
    // If null, the top-level target just didn't declare a required environment. If not null, that
    // means the declaration got "refined" away due to some select() somewhere in its deps. See
    // ConstraintSemantics's documentation for an explanation of refinement.
    private final RemovedEnvironmentCulprit culprit;
    private MissingEnvironment(Label environment, RemovedEnvironmentCulprit culprit) {
      this.environment = environment;
      this.culprit = culprit;
    }
  }

  /**
   * Returns the compatibility of a ConfiguredTarget with the platform.
   *
   * <p>See {@link #checkPlatformRestrictions}.
   */
  public static PlatformCompatibility compatibilityWithPlatformRestrictions(
      ConfiguredTarget configuredTarget,
      ExtendedEventHandler eventHandler,
      boolean eagerlyThrowError,
      boolean explicitlyRequested,
      boolean skipIncompatibleExplicitTargets)
      throws TargetCompatibilityCheckException {

    RuleContextConstraintSemantics.IncompatibleCheckResult incompatibleCheckResult =
        RuleContextConstraintSemantics.checkForIncompatibility(configuredTarget);
    if (!incompatibleCheckResult.isIncompatible()) {
      return PlatformCompatibility.COMPATIBLE;
    }

    // We need the label in unambiguous form here. I.e. with the "@" prefix for targets in the
    // main repository. explicitTargetPatterns is also already in the unambiguous form to make
    // comparison succeed regardless of the provided form.
    if (!skipIncompatibleExplicitTargets && explicitlyRequested) {
      if (eagerlyThrowError) {
        // Use the slightly simpler form for printing error messages. I.e. no "@" prefix for
        // targets in the main repository.
        throw getExceptionForExplicitlyRequestedIncompatibleTarget(
            configuredTarget, incompatibleCheckResult.underlyingTarget());
      }
      eventHandler.handle(
          Event.warn(
              String.format(TARGET_INCOMPATIBLE_ERROR_TEMPLATE, configuredTarget.getLabel(), "")));
      return PlatformCompatibility.INCOMPATIBLE_EXPLICIT;
    }
    // We can safely skip this target if it wasn't explicitly requested or we've been instructed
    // to skip explicitly requested targets.
    return PlatformCompatibility.INCOMPATIBLE_IMPLICIT;
  }

  private static TargetCompatibilityCheckException
      getExceptionForExplicitlyRequestedIncompatibleTarget(
          ConfiguredTarget configuredTarget, ConfiguredTarget underlyingTarget) {
    String targetIncompatibleMessage =
        String.format(
            TARGET_INCOMPATIBLE_ERROR_TEMPLATE,
            configuredTarget.getOriginalLabel(),
            // We need access to the provider so we pass in the underlying target here that is
            // responsible for the incompatibility.
            reportOnIncompatibility(underlyingTarget));
    return new TargetCompatibilityCheckException(
        targetIncompatibleMessage,
        FailureDetail.newBuilder()
            .setMessage(targetIncompatibleMessage)
            .setAnalysis(Analysis.newBuilder().setCode(Code.INCOMPATIBLE_TARGET_REQUESTED))
            .build());
  }

  /**
   * Returns the compatibility with the target environment.
   *
   * <p>See {@link #checkTargetEnvironmentRestrictions}.
   *
   * @return null if the {@code targetLookup} performs a Skyframe lookup and the value is missing.
   */
  @Nullable
  public static EnvironmentCompatibility compatibilityWithTargetEnvironment(
      ConfiguredTarget configuredTarget,
      @Nullable BuildConfigurationValue buildConfigurationValue,
      TargetLookup targetLookup,
      ExtendedEventHandler eventHandler)
      throws InterruptedException, TargetCompatibilityCheckException {
    Target target;
    try {
      target = Preconditions.checkNotNull(targetLookup.getTarget(configuredTarget.getLabel()));
    } catch (NoSuchPackageException | NoSuchTargetException e) {
      eventHandler.handle(
          Event.error(
              "Unable to get target from package when checking environment restrictions. " + e));
      return EnvironmentCompatibility.compatible();
    }
    // TODO(bazel-team): support file targets (they should apply package-default constraints.
    if (buildConfigurationValue == null
        || !buildConfigurationValue
            .enforceConstraints() // Constraint checking is disabled for all targets.
        || target.getAssociatedRule() == null
        || !target
            .getAssociatedRule()
            .getRuleClassObject()
            .supportsConstraintChecking() // This target doesn't participate in constraints.
    ) {
      return EnvironmentCompatibility.compatible();
    }

    // Check explicitly expected environments.
    ImmutableSet<MissingEnvironment> severeMissingEnvironments =
        getMissingEnvironments(
            configuredTarget, buildConfigurationValue.getTargetEnvironments(), targetLookup);
    // Missing value.
    if (severeMissingEnvironments == null) {
      return null;
    }

    if (!severeMissingEnvironments.isEmpty()) {
      return EnvironmentCompatibility.severeIncompatible(severeMissingEnvironments);
    }

    return EnvironmentCompatibility.compatible();
  }

  /**
   * Checks that the all top-level targets are compatible with the target platform.
   *
   * <p>If any target doesn't support the target platform it will be either marked as "to be
   * skipped" or marked as "errored".
   *
   * <p>Targets that are incompatible with the target platform and are not explicitly requested on
   * the command line should be skipped.
   *
   * <p>Targets that are incompatible with the target platform and *are* explicitly requested on the
   * command line are errored unless --skip_incompatible_explicit_targets is enabled. Having one or
   * more errored targets will cause the entire build to fail with an error message.
   *
   * @param topLevelTargets the build's top-level targets
   * @param explicitTargetPatterns the set of explicit target patterns specified by the user on the
   *     command line. Every target must be in the unambiguous canonical form (i.e., with the "@"
   *     prefix for all targets including in the main repository).
   * @return the set of to-be-skipped and errored top-level targets.
   * @throws ViewCreationFailedException if any top-level target was explicitly requested on the
   *     command line.
   */
  public PlatformRestrictionsResult checkPlatformRestrictions(
      ImmutableSet<ConfiguredTarget> topLevelTargets,
      ImmutableSet<Label> explicitTargetPatterns,
      boolean keepGoing,
      boolean skipIncompatibleExplicitTargets)
      throws ViewCreationFailedException {
    ImmutableSet.Builder<ConfiguredTarget> incompatibleTargets = ImmutableSet.builder();
    ImmutableSet.Builder<ConfiguredTarget> incompatibleButRequestedTargets = ImmutableSet.builder();

    try {
      for (ConfiguredTarget target : topLevelTargets) {
        PlatformCompatibility platformCompatibility =
            compatibilityWithPlatformRestrictions(
                target,
                eventHandler,
                /* eagerlyThrowError= */ !keepGoing,
                explicitTargetPatterns.contains(target.getOriginalLabel()),
                skipIncompatibleExplicitTargets);
        if (PlatformCompatibility.INCOMPATIBLE_EXPLICIT.equals(platformCompatibility)) {
          incompatibleButRequestedTargets.add(target);
        } else if (PlatformCompatibility.INCOMPATIBLE_IMPLICIT.equals(platformCompatibility)) {
          incompatibleTargets.add(target);
        }
      }
    } catch (TargetCompatibilityCheckException e) {
      throw new ViewCreationFailedException(e.getFailureDetail(), /*cause=*/ e);
    }

    return PlatformRestrictionsResult.builder()
        .targetsToSkip(ImmutableSet.copyOf(incompatibleTargets.build()))
        .targetsWithErrors(ImmutableSet.copyOf(incompatibleButRequestedTargets.build()))
        .build();
  }

  /**
   * Assembles the explanation for a platform incompatibility.
   *
   * <p>This is useful when trying to explain to the user why an explicitly requested target on the
   * command line is considered incompatible. The goal is to print out the dependency chain and the
   * constraint that wasn't satisfied so that the user can immediately figure out what happened.
   *
   * @param target the incompatible target that was explicitly requested on the command line.
   * @return the verbose error message to show to the user.
   */
  private static String reportOnIncompatibility(ConfiguredTarget target) {
    Preconditions.checkNotNull(target);

    String message = "\nDependency chain:";
    IncompatiblePlatformProvider provider = null;

    // TODO(austinschuh): While the first error is helpful, reporting all the errors at once would
    // save the user bazel round trips.
    while (target != null) {
      message +=
          String.format(
              "\n    %s (%s)",
              target.getLabel(), target.getConfigurationChecksum().substring(0, 6));
      provider = target.get(IncompatiblePlatformProvider.PROVIDER);
      ImmutableList<ConfiguredTarget> targetList = provider.targetsResponsibleForIncompatibility();
      if (targetList == null) {
        target = null;
      } else {
        target = targetList.get(0);
      }
    }

    message +=
        String.format(
            "   <-- target platform (%s) didn't satisfy constraint", provider.targetPlatform());
    if (provider.constraintsResponsibleForIncompatibility().size() == 1) {
      message += " " + provider.constraintsResponsibleForIncompatibility().get(0).label();
      return message;
    }

    message += "s [";

    boolean first = true;
    for (ConstraintValueInfo constraintValueInfo :
        provider.constraintsResponsibleForIncompatibility()) {
      if (first) {
        first = false;
      } else {
        message += ", ";
      }
      message += constraintValueInfo.label();
    }

    message += "]";

    return message;
  }

  /**
   * Checks that if this is an environment-restricted build, all top-level targets support expected
   * top-level environments. Expected top-level environments can be declared explicitly through
   * {@code --target_environment} or implicitly through {@code --auto_cpu_environment_group}. For
   * the latter, top-level targets must be compatible with the build's target configuration CPU.
   *
   * <p>If any target doesn't support an explicitly expected environment declared through {@link
   * CoreOptions#targetEnvironments}, the entire build fails with an error.
   *
   * <p>If any target doesn't support an implicitly expected environment declared through {@link
   * CoreOptions#autoCpuEnvironmentGroup}, the target is skipped during execution while remaining
   * targets execute as normal.
   *
   * @param topLevelTargets the build's top-level targets
   * @return the set of bad top-level targets.
   * @throws ViewCreationFailedException if any target doesn't support an explicitly expected
   *     environment declared through {@link CoreOptions#targetEnvironments}
   */
  public Set<ConfiguredTarget> checkTargetEnvironmentRestrictions(
      ImmutableSet<ConfiguredTarget> topLevelTargets)
      throws ViewCreationFailedException, InterruptedException {
    ImmutableSet.Builder<ConfiguredTarget> badTargets = ImmutableSet.builder();
    // Maps targets that are missing *explicitly* required environments to the set of environments
    // they're missing. These targets trigger a ViewCreationFailedException, which halts the build.
    // Targets with missing *implicitly* required environments don't belong here, since the build
    // continues while skipping them.
    Multimap<ConfiguredTarget, MissingEnvironment> exceptionInducingTargets =
        ArrayListMultimap.create();
    try {
      for (ConfiguredTarget topLevelTarget : topLevelTargets) {
        EnvironmentCompatibility compatibility =
            Preconditions.checkNotNull(
                compatibilityWithTargetEnvironment(
                    topLevelTarget,
                    configurationProvider.apply(topLevelTarget.getConfigurationKey()),
                    label -> packageManager.getTarget(eventHandler, label),
                    eventHandler));
        if (compatibility.isCompatible()) {
          continue;
        }
        if (compatibility.severeMissingEnvironments() != null) {
          exceptionInducingTargets.putAll(
              topLevelTarget, compatibility.severeMissingEnvironments());
        }
        badTargets.add(topLevelTarget);
      }
    } catch (TargetCompatibilityCheckException e) {
      throw new ViewCreationFailedException(e.getMessage(), e.getFailureDetail(), e);
    }

    if (!exceptionInducingTargets.isEmpty()) {
      String badTargetsUserMessage =
          getBadTargetsUserMessage(constraintSemantics, exceptionInducingTargets);
      throw new ViewCreationFailedException(
          badTargetsUserMessage,
          FailureDetail.newBuilder()
              .setMessage(badTargetsUserMessage)
              .setAnalysis(Analysis.newBuilder().setCode(Code.TARGETS_MISSING_ENVIRONMENTS))
              .build());
    }
    return badTargets.build();
  }

  /**
   * Returns the expected environments that the given top-level target doesn't support.
   *
   * @param topLevelTarget the top-level target to check
   * @param expectedEnvironmentLabels the environments this target is expected to support
   * @param targetLookup a function that is used to look up a Target given its Label.
   * @throws InterruptedException if environment target resolution fails
   * @throws TargetCompatibilityCheckException if an expected environment isn't a valid target
   */
  @Nullable
  private static ImmutableSet<MissingEnvironment> getMissingEnvironments(
      ConfiguredTarget topLevelTarget,
      @Nullable Collection<Label> expectedEnvironmentLabels,
      TargetLookup targetLookup)
      throws InterruptedException, TargetCompatibilityCheckException {
    // Missing value.
    if (expectedEnvironmentLabels == null) {
      return null;
    }
    if (expectedEnvironmentLabels.isEmpty()) {
      return ImmutableSet.of();
    }

    // Convert expected environment labels to actual environments.
    EnvironmentCollection.Builder expectedEnvironmentsBuilder = new EnvironmentCollection.Builder();
    for (Label envLabel : expectedEnvironmentLabels) {
      try {
        Target env = targetLookup.getTarget(envLabel);
        // Missing value.
        if (env == null) {
          return null;
        }
        expectedEnvironmentsBuilder.put(
            ConstraintSemantics.getEnvironmentGroup(env).getEnvironmentLabels(), envLabel);
      } catch (NoSuchPackageException
          | NoSuchTargetException
          | ConstraintSemantics.EnvironmentLookupException e) {
        throw new TargetCompatibilityCheckException(
            "invalid target environment: " + e.getMessage(),
            e.getDetailedExitCode().getFailureDetail(),
            e);
      }
    }
    EnvironmentCollection expectedEnvironments = expectedEnvironmentsBuilder.build();

    // Dereference any aliases that might be present.
    topLevelTarget = topLevelTarget.getActual();
    // Now check the target against expected environments.
    TransitiveInfoCollection asProvider;
    if (topLevelTarget instanceof OutputFileConfiguredTarget outputFileConfiguredTarget) {
      asProvider = outputFileConfiguredTarget.getGeneratingRule();
    } else {
      asProvider = topLevelTarget;
    }
    SupportedEnvironmentsProvider provider =
        Verify.verifyNotNull(asProvider.getProvider(SupportedEnvironmentsProvider.class));
    ImmutableSet.Builder<MissingEnvironment> ans = ImmutableSet.builder();
    for (Label unsupportedEnv :
        RuleContextConstraintSemantics.getUnsupportedEnvironments(
            provider.getRefinedEnvironments(), expectedEnvironments)) {
      // We apply this filter because the target might also not support default environments in
      // other environment groups. We don't care about those. We only care about the environments
      // explicitly referenced.
      if (!expectedEnvironmentLabels.contains(unsupportedEnv)) {
        continue;
      }

      List<Label> envAndFulfillers = new ArrayList<>();
      envAndFulfillers.add(unsupportedEnv);
      for (EnvironmentLabels envGroup : provider.getStaticEnvironments().getGroups()) {
        envAndFulfillers.addAll(envGroup.getFulfillers(unsupportedEnv).toList());
      }
      RemovedEnvironmentCulprit culprit = null;
      for (int i = 0; i < envAndFulfillers.size() && culprit == null; i++) {
        culprit = provider.getRemovedEnvironmentCulprit(envAndFulfillers.get(i));
      }
      // culprit could still be null here. See MissingEnvironment class comments for implications.
      ans.add(new MissingEnvironment(unsupportedEnv, culprit));
    }
    return ans.build();
  }

  /**
   * Prepares a user-friendly error message for a list of targets missing support for required
   * environments.
   */
  private static String getBadTargetsUserMessage(
      RuleContextConstraintSemantics constraintSemantics,
      Multimap<ConfiguredTarget, MissingEnvironment> badTargets) {
    StringJoiner msg = new StringJoiner("\n");
    msg.add("This is a restricted-environment build.");
    for (Map.Entry<ConfiguredTarget, Collection<MissingEnvironment>> entry :
        badTargets.asMap().entrySet()) {
      msg.add(getErrorMessageForTarget(constraintSemantics, entry.getKey(), entry.getValue()));
    }
    return msg.add(" ").toString();
  }

  public static String getErrorMessageForTarget(
      RuleContextConstraintSemantics constraintSemantics,
      ConfiguredTarget configuredTarget,
      Collection<MissingEnvironment> missingEnvironments) {
    StringJoiner msg = new StringJoiner("\n");
    ConfiguredTarget targetWithProvider = configuredTarget.getActual();
    if (targetWithProvider instanceof OutputFileConfiguredTarget outputFileConfiguredTarget) {
      targetWithProvider = outputFileConfiguredTarget.getGeneratingRule();
    }
    SupportedEnvironmentsProvider supportedEnvironments =
        targetWithProvider.getProvider(SupportedEnvironmentsProvider.class);
    String declaredEnvs =
        supportedEnvironments.getStaticEnvironments().getEnvironments().stream()
            .map(Label::toString)
            .collect(joining(", "));
    ;
    msg.add(" ")
        .add(configuredTarget.getLabel() + " declares compatibility with:")
        .add("  [" + declaredEnvs + "]")
        .add("but does not support:");
    boolean isFirst = true;
    boolean lastEntryWasMultiline = false;
    for (MissingEnvironment missingEnvironment : missingEnvironments) {
      if (missingEnvironment.culprit == null) {
        // The target didn't declare support for this environment.
        if (lastEntryWasMultiline) {
          // Pretty-format: if the last environment message was multi-line, make it clear this
          // one is a different entry. But we don't want to do that if all entries are single-line
          // because that would be pointlessly long.
          msg.add(" ");
        }
        msg.add("  " + missingEnvironment.environment);
        lastEntryWasMultiline = false;
      } else {
        // The target declared support, but it was refined out by a select() somewhere in its
        // transitive deps.
        if (!isFirst) {
          msg.add(" "); // Pretty-format for clarity.
        }
        msg.add(
            constraintSemantics.getMissingEnvironmentCulpritMessage(
                configuredTarget.getLabel(),
                missingEnvironment.environment,
                missingEnvironment.culprit));
        lastEntryWasMultiline = true;
      }
      isFirst = false;
    }
    return msg.toString();
  }

  /** Tells the compatibility of a ConfiguredTarget with the target environment. */
  public record EnvironmentCompatibility(
      boolean isCompatible, @Nullable ImmutableSet<MissingEnvironment> severeMissingEnvironments) {

    public static EnvironmentCompatibility compatible() {
      return new EnvironmentCompatibility(
          /* isCompatible= */ true, /* severeMissingEnvironments= */ null);
    }

    public static EnvironmentCompatibility nonSevereIncompatible() {
      return new EnvironmentCompatibility(
          /* isCompatible= */ false, /* severeMissingEnvironments= */ null);
    }

    public static EnvironmentCompatibility severeIncompatible(
        ImmutableSet<MissingEnvironment> severeMissingEnvironments) {
      return new EnvironmentCompatibility(/* isCompatible= */ false, severeMissingEnvironments);
    }
  }

  /** Tells the compatibility of a ConfiguredTarget with the platform. */
  public enum PlatformCompatibility {
    COMPATIBLE,
    INCOMPATIBLE_IMPLICIT,
    INCOMPATIBLE_EXPLICIT
  }

  /** For Exceptions that arise during the compatibility checking of a target. */
  public static class TargetCompatibilityCheckException extends AbstractSaneAnalysisException {
    private final FailureDetail failureDetail;

    public TargetCompatibilityCheckException(String message, FailureDetail failureDetail) {
      super(message);
      this.failureDetail = failureDetail;
    }

    public TargetCompatibilityCheckException(
        String message, FailureDetail failureDetail, Throwable cause) {
      super(message, cause);
      this.failureDetail = failureDetail;
    }

    public FailureDetail getFailureDetail() {
      return failureDetail;
    }

    @Override
    public DetailedExitCode getDetailedExitCode() {
      return DetailedExitCode.of(failureDetail);
    }
  }

  /** Provides a method to look up a Target, given its Label. */
  @FunctionalInterface
  public interface TargetLookup {
    // Returns null if the implementation involves a Skyframe lookup and the value is missing.
    @Nullable
    Target getTarget(Label label)
        throws NoSuchPackageException, NoSuchTargetException, InterruptedException;
  }
}
