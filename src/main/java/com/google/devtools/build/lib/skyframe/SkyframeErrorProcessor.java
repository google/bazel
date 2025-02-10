// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import static com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil.configurationIdMessage;
import static com.google.devtools.build.lib.skyframe.ActionArtifactCycleReporter.ACTION_OR_ARTIFACT_OR_TRANSITIVE_RDEP;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.InputFileErrorException;
import com.google.devtools.build.lib.actions.TestExecException;
import com.google.devtools.build.lib.actions.TopLevelOutputException;
import com.google.devtools.build.lib.analysis.AnalysisFailureEvent;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.constraints.TopLevelConstraintSemantics.TargetCompatibilityCheckException;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.causes.AnalysisFailedCause;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.causes.LabelCause;
import com.google.devtools.build.lib.causes.LoadingFailedCause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.pkgcache.LoadingFailureEvent;
import com.google.devtools.build.lib.server.FailureDetails.Analysis;
import com.google.devtools.build.lib.server.FailureDetails.Analysis.Code;
import com.google.devtools.build.lib.server.FailureDetails.Execution;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.ArtifactNestedSetFunction.ArtifactNestedSetEvalException;
import com.google.devtools.build.lib.skyframe.AspectCompletionValue.AspectCompletionKey;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectBaseKey;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.TopLevelAspectsKey;
import com.google.devtools.build.lib.skyframe.TargetCompletionValue.TargetCompletionKey;
import com.google.devtools.build.lib.skyframe.TestCompletionValue.TestCompletionKey;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.DetailedExitCode.DetailedExitCodeComparator;
import com.google.devtools.build.skyframe.CycleInfo;
import com.google.devtools.build.skyframe.CyclesReporter;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.WalkableGraph;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.annotation.Nullable;

/** A utility class that provides methods to parse errors from Skyframe EvaluationResults. */
public final class SkyframeErrorProcessor {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private SkyframeErrorProcessor() {}

  /**
   * Indicates if there are errors with the various phases, and an exception to be thrown to halt
   * the build, in case of --nokeep_going.
   *
   * <p>The various attributes will be used later on to construct the FailureDetail in {@link
   * com.google.devtools.build.lib.analysis.BuildView#createAnalysisFailureDetail}.
   *
   * @param hasLoadingError whether there are loading errors.
   * @param hasAnalysisError whether there are analysis errors.
   * @param actionConflicts the action conflicts encountered during analysis.
   * @param executionDetailedExitCode the detailed exit code for execution errors. This is
   *     <ul>
   *       <li>{@code null}, if {@code result} had no errors or the errors were all analysis errors.
   *       <li>{@code e} if result had errors and one of them specified a {@link DetailedExitCode}
   *           value {@code e}
   *       <li>a {@link DetailedExitCode} with {@link Execution.Code#NON_ACTION_EXECUTION_FAILURE}
   *           if result had errors but none specified a {@link DetailedExitCode} value
   *     </ul>
   *
   * @param aspectKeysForConflictReporting the aspect keys for conflict reporting.
   */
  public record ErrorProcessingResult(
      boolean hasLoadingError,
      boolean hasAnalysisError,
      ImmutableMap<ActionAnalysisMetadata, ActionConflictException> actionConflicts,
      @Nullable DetailedExitCode executionDetailedExitCode,
      ImmutableList<ActionLookupKey> aspectKeysForConflictReporting) {
    public ErrorProcessingResult {
      requireNonNull(actionConflicts, "actionConflicts");
      requireNonNull(aspectKeysForConflictReporting, "aspectKeysForConflictReporting");
    }

    static AggregatingBuilder newBuilder() {
      return new AggregatingBuilder();
    }

    static class AggregatingBuilder {
      private boolean hasLoadingError = false;
      private boolean hasAnalysisError = false;
      private final Map<ActionAnalysisMetadata, ActionConflictException> actionConflicts =
          Maps.newHashMap();
      @Nullable private DetailedExitCode executionDetailedExitCode = null;
      private final ImmutableList.Builder<ActionLookupKey> aspectKeysForConflictReporting =
          ImmutableList.builder();

      void aggregateSingleResult(IndividualErrorProcessingResult individualErrorProcessingResult) {
        hasLoadingError = hasLoadingError || individualErrorProcessingResult.isLoadingError();
        hasAnalysisError = hasAnalysisError || individualErrorProcessingResult.isAnalysisError();
        actionConflicts.putAll(individualErrorProcessingResult.actionConflicts());
        executionDetailedExitCode =
            DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
                executionDetailedExitCode,
                individualErrorProcessingResult.executionDetailedExitCode());
        if (individualErrorProcessingResult.aspectKeyForConflictReporting() != null) {
          aspectKeysForConflictReporting.add(
              individualErrorProcessingResult.aspectKeyForConflictReporting());
        }
      }

      // TODO(b/249690006) Only used for the rollout of the refactor. Remove afterwards.
      @CanIgnoreReturnValue
      AggregatingBuilder setExecutionDetailedExitCode(DetailedExitCode executionDetailedExitCode) {
        this.executionDetailedExitCode = executionDetailedExitCode;
        return this;
      }

      ErrorProcessingResult build() {
        return new ErrorProcessingResult(
            hasLoadingError,
            hasAnalysisError,
            ImmutableMap.copyOf(actionConflicts),
            executionDetailedExitCode,
            aspectKeysForConflictReporting.build());
      }
    }
  }

  /**
   * Represents the information around one single error in the build. These are the building blocks
   * for the final {@link ErrorProcessingResult}.
   */
  record IndividualErrorProcessingResult(
      ImmutableMap<ActionAnalysisMetadata, ActionConflictException> actionConflicts,
      @Nullable DetailedExitCode executionDetailedExitCode,
      NestedSet<Cause> analysisRootCauses,
      ImmutableSet<Label> loadingRootCauses,
      @Nullable ActionLookupKey aspectKeyForConflictReporting) {
    IndividualErrorProcessingResult {
      requireNonNull(actionConflicts, "actionConflicts");
      requireNonNull(analysisRootCauses, "analysisRootCauses");
      requireNonNull(loadingRootCauses, "loadingRootCauses");
    }

    boolean isActionConflictError() {
      return !actionConflicts().isEmpty();
    }

    boolean isLoadingError() {
      return !loadingRootCauses().isEmpty();
    }

    /** This is true for all non-execution errors: including loading & action conflict errors. */
    boolean isAnalysisError() {
      return executionDetailedExitCode() == null;
    }

    static IndividualErrorProcessingResult create(
        ImmutableMap<ActionAnalysisMetadata, ActionConflictException> actionConflicts,
        @Nullable DetailedExitCode executionDetailedExitCode,
        NestedSet<Cause> analysisRootCauses,
        ImmutableSet<Label> loadingRootCauses,
        @Nullable ActionLookupKey aspectKeyForConflictReporting) {
      return new IndividualErrorProcessingResult(
          actionConflicts,
          executionDetailedExitCode,
          analysisRootCauses,
          loadingRootCauses,
          aspectKeyForConflictReporting);
    }
  }

  /**
   * Process only loading/analysis errors. Returns a {@link ErrorProcessingResult}.
   *
   * <p>In case of --nokeep_going: immediately throw the exception.
   */
  static ErrorProcessingResult processAnalysisErrors(
      EvaluationResult<? extends SkyValue> result,
      CyclesReporter cyclesReporter,
      ExtendedEventHandler eventHandler,
      boolean keepGoing,
      boolean keepEdges,
      @Nullable EventBus eventBus,
      BugReporter bugReporter)
      throws InterruptedException, ViewCreationFailedException {
    try {
      return processErrors(
          result,
          cyclesReporter,
          eventHandler,
          keepGoing,
          keepEdges,
          eventBus,
          bugReporter,
          /* includeExecutionPhase= */ false);
    } catch (BuildFailedException | TestExecException unexpected) {
      throw new IllegalStateException("Unexpected execution phase exception: ", unexpected);
    }
  }

  /** Process only execution errors. Returns a {@link ErrorProcessingResult}. */
  public static ErrorProcessingResult processExecutionErrors(
      EvaluationResult<? extends SkyValue> result,
      CyclesReporter cyclesReporter,
      ExtendedEventHandler eventHandler,
      boolean keepGoing,
      boolean keepEdges,
      @Nullable EventBus eventBus,
      BugReporter bugReporter,
      boolean skyframeErrorHandlingRefactor)
      throws InterruptedException, BuildFailedException, TestExecException {
    if (skyframeErrorHandlingRefactor) {
      try {
        return processErrors(
            result,
            cyclesReporter,
            eventHandler,
            keepGoing,
            keepEdges,
            eventBus,
            bugReporter,
            /* includeExecutionPhase= */ true);
      } catch (ViewCreationFailedException unexpected) {
        throw new IllegalStateException("Unexpected analysis phase exception: ", unexpected);
      }
    }
    var executionErrorExitCode =
        processResult(eventHandler, result, keepGoing, cyclesReporter, bugReporter);
    return ErrorProcessingResult.newBuilder()
        .setExecutionDetailedExitCode(executionErrorExitCode)
        .build();
  }

  /**
   * Process errors encountered during analysis/execution.
   *
   * <p>This method has different goals depending on --(no)keep_going:
   *
   * <ul>
   *   <li>In case of --keep_going: post the necessary events, then construct an {@link
   *       ErrorProcessingResult}.
   *   <li>In case of --nokeep_going: post the necessary events, then throw an appropriate exception
   *       ASAP, except when the error is caused by an action conflict: we need more downstream
   *       information.
   * </ul>
   *
   * <p>Visible only for use by tests via {@link
   * SkyframeExecutor#getConfiguredTargetMapForTesting(ExtendedEventHandler,
   * BuildConfigurationValue, Iterable)}. When called there, {@code eventBus} must be null to
   * indicate that this is a test, and so there may be additional {@link SkyKey}s in the {@code
   * result} that are not {@link AspectKeyCreator}s or {@link ConfiguredTargetKey}s. Those keys will
   * be ignored.
   *
   * @throws ViewCreationFailedException when the root cause is analysis-related.
   * @throws BuildFailedException when the root cause is execution-related.
   * @throws TestExecException when the root cause is test-related.
   * @return an ErrorProcessingResult (only in --keep_going mode, or action conflict).
   */
  static ErrorProcessingResult processErrors(
      EvaluationResult<? extends SkyValue> result,
      CyclesReporter cyclesReporter,
      ExtendedEventHandler eventHandler,
      boolean keepGoing,
      boolean keepEdges,
      @Nullable EventBus eventBus,
      @Nullable BugReporter bugReporter,
      boolean includeExecutionPhase)
      throws InterruptedException,
          ViewCreationFailedException,
          BuildFailedException,
          TestExecException {
    boolean inBuildViewTest = eventBus == null;
    ViewCreationFailedException noKeepGoingAnalysisExceptionAspect = null;
    ErrorProcessingResult.AggregatingBuilder aggregatingResultBuilder =
        ErrorProcessingResult.newBuilder();

    for (Map.Entry<SkyKey, ErrorInfo> errorEntry : result.errorMap().entrySet()) {
      ErrorInfo errorInfo = errorEntry.getValue();

      // The cycle reporter requires that the path to the cycle starts at the top level key
      // (requested via SkyframeExecutor), hence we need to provide the original top level key here.
      //
      // Why is there a need for "original" vs "effective" error key?
      // 1) The non-skymeld code path deals with ActionLookupKeys as the top level key,
      // 2) We wanted to share the error handling code between skymeld and non skymeld.
      // To do so, we need to "normalize" the top level key in Skymeld mode by getting the effective
      // ActionLookupKey from a BuildDriverKey. The rest of the method can then be easily shared.
      cyclesReporter.reportCycles(
          errorInfo.getCycleInfo(), /*topLevelKey=*/ errorEntry.getKey(), eventHandler);

      SkyKey errorKey = getEffectiveErrorKey(errorEntry);
      if (includeExecutionPhase) {
        assertValidAnalysisOrExecutionException(
            errorInfo, errorKey, result.getWalkableGraph(), keepEdges);
      } else {
        assertValidAnalysisException(errorInfo, errorKey, result.getWalkableGraph(), keepEdges);
      }
      Exception nullableCause = errorInfo.getException();
      Preconditions.checkState(
          nullableCause != null || !errorInfo.getCycleInfo().isEmpty(), errorInfo);

      // TODO(b/249690006): Can we remove this divergence?
      if (inBuildViewTest && !isValidErrorKeyType(errorKey.argument())) {
        // This means that we are in a BuildViewTestCase.
        //
        // Tests don't call target pattern parsing before requesting the analysis of a target.
        // Therefore if the package that contains them cannot be loaded, we get an error key that's
        // not a ConfiguredTargetKey, which cannot happen in production code.
        //
        // If it's an existing target in a nonexistent package, the error is signaled by posting an
        // AnalysisFailureEvent on the event bus, which is null in when running a BuildViewTestCase,
        // so we emit the root cause labels directly to the event handler below.
        eventHandler.handle(Event.error(errorInfo.toString()));
        continue;
      }

      Label label = getLabel(errorKey);
      IndividualErrorProcessingResult individualErrorProcessingResult =
          processIndividualError(result, bugReporter, errorKey, errorInfo);

      // For action conflicts, more downstream operations are required to have all the
      // information. We intentionally don't send out any failure event, throw any exception (even
      // with --nokeep_going) or print a warning message at this point. These will be done elsewhere
      // at a later point.
      if (individualErrorProcessingResult.isActionConflictError()) {
        aggregatingResultBuilder.aggregateSingleResult(individualErrorProcessingResult);
        continue;
      }

      maybePostFailureEventsForNonConflictError(
          eventHandler,
          eventBus,
          inBuildViewTest,
          errorKey,
          label,
          individualErrorProcessingResult);

      boolean isExecutionException = isExecutionException(nullableCause);
      if (keepGoing) {
        aggregatingResultBuilder.aggregateSingleResult(individualErrorProcessingResult);
        logOrPrintWarningsKeepGoing(isExecutionException, label, eventHandler, nullableCause);
      } else {
        noKeepGoingAnalysisExceptionAspect =
            throwOrReturnAspectAnalysisException(
                result,
                nullableCause,
                bugReporter,
                errorKey,
                isExecutionException,
                /* hasExecutionCycle= */ CYCLE_CODE.equals(
                    individualErrorProcessingResult.executionDetailedExitCode()));
      }
    }

    if (noKeepGoingAnalysisExceptionAspect != null) {
      throw noKeepGoingAnalysisExceptionAspect;
    }

    return aggregatingResultBuilder.build();
  }

  /*
   * Post the relevant failure events if we're not in test.
   *
   * <p>There is 1 exception: for aspects, the failures should already have been reported to the
   * event handler, so we do nothing here.
   */
  private static void maybePostFailureEventsForNonConflictError(
      ExtendedEventHandler eventHandler,
      @Nullable EventBus eventBus,
      boolean inBuildViewTest,
      SkyKey errorKey,
      @Nullable Label label,
      IndividualErrorProcessingResult individualErrorProcessingResult) {
    Preconditions.checkState(!individualErrorProcessingResult.isActionConflictError());
    if (inBuildViewTest) {
      // eventBus is null, but tests can still assert on the expected root causes being found.
      eventHandler.handle(
          Event.error(individualErrorProcessingResult.analysisRootCauses().toList().toString()));
      return;
    }

    Preconditions.checkNotNull(eventBus);
    if (!(errorKey instanceof ConfiguredTargetKey)) {
      return;
    }

    ConfiguredTargetKey ctKey = (ConfiguredTargetKey) errorKey.argument();
    // For loading errors, we expect both LoadingFailureEvent and AnalysisFailureEvent.
    if (individualErrorProcessingResult.isLoadingError()) {
      for (Label loadingRootCause : individualErrorProcessingResult.loadingRootCauses()) {
        // This event is only for backwards compatibility with the old event protocol. Remove
        // once we've migrated to the build event protocol.
        eventBus.post(new LoadingFailureEvent(Preconditions.checkNotNull(label), loadingRootCause));
      }
    }

    if (individualErrorProcessingResult.isAnalysisError()) {
      eventBus.post(
          AnalysisFailureEvent.whileAnalyzingTarget(
              ctKey, individualErrorProcessingResult.analysisRootCauses()));
    }
  }

  /**
   * Throw the necessary exceptions based on the error processing result.
   *
   * <p>This method should be called in --nokeep_going mode, unless the error is an action conflict.
   *
   * <p>Special case: if the analysis error belongs to a top-level Aspect, we don't throw the
   * ViewCreationFailedException immediately to make sure that a target analysis error is preferred
   * over an aspect one.
   *
   * @throws ViewCreationFailedException when the root cause is analysis-related.
   * @throws BuildFailedException when the root cause is execution-related.
   * @throws TestExecException when the root cause is test-related.
   * @return a ViewCreationFailedException if the error belongs to a top-level Aspect.
   */
  private static ViewCreationFailedException throwOrReturnAspectAnalysisException(
      EvaluationResult<? extends SkyValue> result,
      @Nullable Exception cause,
      BugReporter bugReporter,
      SkyKey errorKey,
      boolean isExecutionException,
      boolean hasExecutionCycle)
      throws BuildFailedException, TestExecException, ViewCreationFailedException {
    // If the error is execution-related: straightaway rethrow. No further steps required.
    if (isExecutionException) {
      // cause is not null for execution exceptions.
      Preconditions.checkNotNull(cause);
      rethrow(cause, bugReporter, result);
    }
    // If a --nokeep_going build found a cycle, that means there were no other errors thrown
    // during evaluation (otherwise, it wouldn't have bothered to find a cycle). So the best
    // we can do is throw a generic build failure exception, since we've already reported the
    // cycles above. Analysis cycles are handled below.
    if (hasExecutionCycle) {
      throw new BuildFailedException(null, CYCLE_CODE);
    }

    if (errorKey instanceof TopLevelAspectsKey) {
      TopLevelAspectsKey aspectKey = (TopLevelAspectsKey) errorKey.argument();
      String errorMsg =
          String.format(
              "Analysis of aspects '%s' failed; build aborted", aspectKey.getDescription());
      return createViewCreationFailedException(cause, errorMsg);
    }

    Label topLevelLabel = ((ConfiguredTargetKey) errorKey).getLabel();
    throw createViewCreationFailedException(
        cause, String.format("Analysis of target '%s' failed; build aborted", topLevelLabel));
  }

  /**
   * Processes one individual error from the result.
   *
   * <p>No exception should ever be thrown here: this is just to gather the relevant information
   * around 1 single error. {@link #processErrors} will decide what to do with this information.
   */
  private static IndividualErrorProcessingResult processIndividualError(
      EvaluationResult<? extends SkyValue> result,
      BugReporter bugReporter,
      SkyKey errorKey,
      ErrorInfo errorInfo) {
    Exception exception = errorInfo.getException();
    Set<Label> loadingRootCauses = Sets.newHashSet();
    ImmutableMap<ActionAnalysisMetadata, ActionConflictException> actionConflicts =
        ImmutableMap.of();
    DetailedExitCode executionDetailedExitCode = null;
    ActionLookupKey aspectKeyForConflictReporting = null;

    // Legacy: analysis-related failure events for Aspects are sent somewhere else, so we don't have
    // to do any work related to constructing the analysis failure events here, only for the other
    // cases like action conflict or execution-related errors.
    // TODO(b/249690006): Can we simplify things by moving aspects events here?
    if (errorKey.argument() instanceof AspectBaseKey) {
      if (exception instanceof TopLevelConflictException tlce) {
        actionConflicts = tlce.getTransitiveActionConflicts();
      } else if (exception instanceof ActionConflictException ace) {
        actionConflicts = ImmutableMap.of(ace.getAttemptedAction(), ace);
        aspectKeyForConflictReporting = ace.getAspectKey();
      } else if (isExecutionException(exception)) {
        executionDetailedExitCode =
            getExecutionDetailedExitCodeFromCause(result, exception, bugReporter);
      } else if (!errorInfo.getCycleInfo().isEmpty()
          && isExecutionCycle(errorInfo.getCycleInfo())) {
        executionDetailedExitCode = CYCLE_CODE;
      }
      return IndividualErrorProcessingResult.create(
          actionConflicts,
          executionDetailedExitCode,
          /* analysisRootCauses= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER),
          /* loadingRootCauses= */ ImmutableSet.of(),
          aspectKeyForConflictReporting);
    }

    // Only possible with actions generating build-info.txt and build-changelist.txt.
    if (errorKey.argument() instanceof ActionLookupData) {
      return IndividualErrorProcessingResult.create(
          /* actionConflicts= */ ImmutableMap.of(),
          getExecutionDetailedExitCodeFromCause(result, exception, bugReporter),
          /* analysisRootCauses= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER),
          /* loadingRootCauses= */ ImmutableSet.of(),
          /* aspectKeyForConflictReporting= */ null);
    }

    Preconditions.checkState(
        errorKey.argument() instanceof ConfiguredTargetKey,
        "expected '%s' to be a ConfiguredTargetKey",
        errorKey.argument());
    ConfiguredTargetKey ctKey = (ConfiguredTargetKey) errorKey.argument();
    Label topLevelLabel = ctKey.getLabel();
    NestedSet<Cause> analysisRootCauses;

    if (exception instanceof TopLevelConflictException tlce) {
      actionConflicts = tlce.getTransitiveActionConflicts();
      analysisRootCauses = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    } else if (exception instanceof ActionConflictException ace) {
      actionConflicts = ImmutableMap.of(ace.getAttemptedAction(), ace);
      analysisRootCauses = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    } else if (exception instanceof ConfiguredValueCreationException ctCause) {
      // Previously, the nested set was de-duplicating loading root cause labels. Now that we
      // track Cause instances including a message, we get one event per label and message. In
      // order to keep backwards compatibility, we deduplicate root cause labels here.
      // TODO(ulfjack): Remove this code once we've migrated to the BEP.
      for (Cause rootCause : ctCause.getRootCauses().toList()) {
        if (rootCause instanceof LoadingFailedCause) {
          loadingRootCauses.add(rootCause.getLabel());
        }
      }
      analysisRootCauses = ctCause.getRootCauses();
    } else if (!errorInfo.getCycleInfo().isEmpty()) {
      if (isExecutionCycle(errorInfo.getCycleInfo())) {
        // If we have a cycle, cause would be null, so it's guaranteed that this
        // executionDetailedExitCode is final.
        executionDetailedExitCode = CYCLE_CODE;
        analysisRootCauses = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
      } else {
        Label analysisRootCause =
            maybeGetConfiguredTargetCycleCulprit(topLevelLabel, errorInfo.getCycleInfo());
        analysisRootCauses =
            analysisRootCause != null
                ? NestedSetBuilder.create(
                    Order.STABLE_ORDER,
                    new LabelCause(
                        analysisRootCause,
                        DetailedExitCode.of(createFailureDetail("Dependency cycle", Code.CYCLE))))
                // TODO(ulfjack): We need to report the dependency cycle here. How?
                : NestedSetBuilder.emptySet(Order.STABLE_ORDER);
      }
    } else if (exception instanceof NoSuchThingException) {
      // This branch is only taken in --nokeep_going builds. In a --keep_going build, the
      // AnalysisFailedCause is properly reported through the ConfiguredValueCreationException.
      AnalysisFailedCause analysisFailedCause =
          new AnalysisFailedCause(
              topLevelLabel,
              configurationIdMessage(ctKey.getConfigurationKey()),
              ((NoSuchThingException) exception).getDetailedExitCode());
      analysisRootCauses = NestedSetBuilder.create(Order.STABLE_ORDER, analysisFailedCause);
    } else if (exception instanceof TargetCompatibilityCheckException) {
      analysisRootCauses = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    } else if (isExecutionException(exception)) {
      executionDetailedExitCode =
          getExecutionDetailedExitCodeFromCause(result, exception, bugReporter);
      analysisRootCauses =
          exception instanceof ActionExecutionException actionExecutionException
              ? actionExecutionException.getRootCauses()
              : NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    } else {
      BugReport.logUnexpected(
          exception, "Unexpected cause encountered while evaluating: %s", errorKey);
      analysisRootCauses = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }

    return IndividualErrorProcessingResult.create(
        actionConflicts,
        executionDetailedExitCode,
        analysisRootCauses,
        ImmutableSet.copyOf(loadingRootCauses),
        /* aspectKeyForConflictReporting= */ null);
  }

  private static DetailedExitCode getExecutionDetailedExitCodeFromCause(
      EvaluationResult<? extends SkyValue> result, Exception cause, BugReporter bugReporter) {
    DetailedExitCode executionDetailedExitCode = DetailedException.getDetailedExitCode(cause);
    if (executionDetailedExitCode == null) {
      executionDetailedExitCode =
          sendBugReportAndCreateUnknownExecutionDetailedExitCode(result, cause, bugReporter);
    }
    return executionDetailedExitCode;
  }

  private static DetailedExitCode sendBugReportAndCreateUnknownExecutionDetailedExitCode(
      EvaluationResult<? extends SkyValue> result, Throwable cause, BugReporter bugReporter) {
    // An undetailed exception means we may incorrectly attribute responsibility for the failure:
    // we need to fix that.
    bugReporter.sendNonFatalBugReport(
        new IllegalStateException(
            "action terminated with unexpected exception with result " + result, cause));
    String message =
        "Unexpected exception, please file an issue with the Bazel team: " + cause.getMessage();
    return createDetailedExecutionExitCode(message, UNKNOWN_EXECUTION);
  }

  private static void logOrPrintWarningsKeepGoing(
      boolean isExecutionException,
      @Nullable Label topLevelLabel,
      ExtendedEventHandler eventHandler,
      @Nullable Exception cause) {
    // For execution exceptions, we don't print any extra warning.
    if (isExecutionException) {
      if (isExecutionCauseWorthLogging(cause)) {
        logger.atWarning().withCause(cause).log(
            "Non-action-execution/input-error exception while building target %s", topLevelLabel);
      }
      return;
    }
    var message =
        String.format(
            "errors encountered while analyzing target '%s', it will not be built.", topLevelLabel);
    if (cause != null) {
      message += String.format("\n%s", cause.getMessage());
    }
    eventHandler.handle(Event.warn(message));
  }

  private static boolean isExecutionCauseWorthLogging(Throwable cause) {
    return !(cause instanceof ActionExecutionException)
        && !(cause instanceof InputFileErrorException)
        && !(cause instanceof TopLevelOutputException);
  }

  private static boolean isValidErrorKeyType(Object errorKey) {
    return errorKey instanceof ConfiguredTargetKey || errorKey instanceof AspectBaseKey;
  }

  /** Peel away the wrapper layers to get to the ActionLookupKey of the top level target. */
  private static SkyKey getEffectiveErrorKey(Entry<SkyKey, ErrorInfo> errorEntry) {
    if (errorEntry.getKey().argument() instanceof BuildDriverKey) {
      return ((BuildDriverKey) errorEntry.getKey().argument()).getActionLookupKey();
    }
    // For exclusive tests.
    if (errorEntry.getKey().argument() instanceof TestCompletionKey) {
      return ((TestCompletionKey) errorEntry.getKey().argument()).configuredTargetKey();
    }
    // For non-skymeld action executions.
    if (errorEntry.getKey().argument() instanceof TargetCompletionKey) {
      return ((TargetCompletionKey) errorEntry.getKey().argument()).actionLookupKey();
    }
    if (errorEntry.getKey().argument() instanceof AspectCompletionKey) {
      return ((AspectCompletionKey) errorEntry.getKey().argument()).actionLookupKey();
    }
    return errorEntry.getKey();
  }

  @Nullable
  private static Label getLabel(SkyKey errorKey) {
    return errorKey instanceof ActionLookupKey ? ((ActionLookupKey) errorKey).getLabel() : null;
  }

  private static ViewCreationFailedException createViewCreationFailedException(
      @Nullable Exception e, String errorMsg) {
    if (e == null) {
      return new ViewCreationFailedException(
          errorMsg, createFailureDetail(errorMsg + " due to cycle", Code.CYCLE));
    }
    return new ViewCreationFailedException(
        errorMsg, maybeContextualizeFailureDetail(e, errorMsg), e);
  }

  /**
   * Returns a {@link FailureDetail} with message prefixed by {@code errorMsg} derived from the
   * failure detail in {@code e} if it's a {@link DetailedException}, and otherwise returns one with
   * {@code errorMsg} and {@link Code#UNEXPECTED_ANALYSIS_EXCEPTION}.
   */
  private static FailureDetail maybeContextualizeFailureDetail(
      @Nullable Exception e, String errorMsg) {
    DetailedException detailedException = convertToAnalysisException(e);
    if (detailedException == null) {
      return createFailureDetail(errorMsg, Code.UNEXPECTED_ANALYSIS_EXCEPTION);
    }
    FailureDetail originalFailureDetail =
        detailedException.getDetailedExitCode().getFailureDetail();
    return originalFailureDetail.toBuilder()
        .setMessage(errorMsg + ": " + originalFailureDetail.getMessage())
        .build();
  }

  private static FailureDetail createFailureDetail(String errorMessage, Code code) {
    return FailureDetail.newBuilder()
        .setMessage(errorMessage)
        .setAnalysis(Analysis.newBuilder().setCode(code))
        .build();
  }

  @Nullable
  private static Label maybeGetConfiguredTargetCycleCulprit(
      Label labelToLoad, Iterable<CycleInfo> cycleInfos) {
    for (CycleInfo cycleInfo : cycleInfos) {
      SkyKey culprit = Iterables.getFirst(cycleInfo.getCycle(), null);
      if (culprit == null) {
        continue;
      }
      if (culprit.functionName().equals(SkyFunctions.CONFIGURED_TARGET)) {
        return ((ConfiguredTargetKey) culprit.argument()).getLabel();
      } else if (culprit.functionName().equals(TransitiveTargetKey.NAME)) {
        return ((TransitiveTargetKey) culprit).getLabel();
      } else {
        return labelToLoad;
      }
    }
    return null;
  }

  private static void assertValidAnalysisException(
      ErrorInfo errorInfo, SkyKey key, WalkableGraph walkableGraph, boolean keepEdges)
      throws InterruptedException {
    Throwable cause = errorInfo.getException();
    if (cause == null) {
      // Cycle.
      return;
    }

    if (convertToAnalysisException(cause) != null) {
      // Valid exception type.
      return;
    }

    logUnexpectedExceptionOrigin(errorInfo, key, walkableGraph, cause, keepEdges);
  }

  private static void assertValidAnalysisOrExecutionException(
      ErrorInfo errorInfo, SkyKey key, WalkableGraph walkableGraph, boolean keepEdges)
      throws InterruptedException {
    Throwable cause = errorInfo.getException();
    if (cause == null) {
      // Cycle.
      return;
    }

    if (convertToAnalysisException(cause) != null
        || isExecutionException(cause)
        || cause instanceof TopLevelConflictException) {
      // Valid exception type.
      return;
    }

    logUnexpectedExceptionOrigin(errorInfo, key, walkableGraph, cause, keepEdges);
  }

  /**
   * Walk the graph to find a path to the lowest-level node that threw unexpected exception and log
   * it.
   */
  private static void logUnexpectedExceptionOrigin(
      ErrorInfo errorInfo,
      SkyKey key,
      WalkableGraph walkableGraph,
      Throwable cause,
      boolean keepEdges)
      throws InterruptedException {
    if (!keepEdges) {
      // Can't traverse the graph to find the origin.
      logUnexpectedException(key, errorInfo, "direct deps not stored");
      return;
    }
    List<SkyKey> path = new ArrayList<>();
    try {
      SkyKey currentKey = key;
      boolean foundDep;
      do {
        path.add(currentKey);
        foundDep = false;

        Map<SkyKey, Exception> missingMap =
            walkableGraph.getMissingAndExceptions(ImmutableList.of(currentKey));
        if (missingMap.containsKey(currentKey) && missingMap.get(currentKey) == null) {
          // This can happen in a no-keep-going build, where we don't write the bubbled-up error
          // nodes to the graph.
          break;
        }

        for (SkyKey dep : walkableGraph.getDirectDeps(currentKey)) {
          if (cause.equals(walkableGraph.getException(dep))) {
            currentKey = dep;
            foundDep = true;
            break;
          }
        }
      } while (foundDep);
    } finally {
      logUnexpectedException(key, errorInfo, path);
    }
  }

  private static void logUnexpectedException(SkyKey key, ErrorInfo errorInfo, Object extraInfo) {
    BugReport.logUnexpected("Unexpected analysis error: %s -> %s, (%s)", key, errorInfo, extraInfo);
  }

  @Nullable
  private static DetailedException convertToAnalysisException(Throwable cause) {
    // The cause may be NoSuch{Target,Package}Exception if we run the reduced loading phase and then
    // analyze with --nokeep_going.
    if (cause instanceof SaneAnalysisException
        || cause instanceof NoSuchTargetException
        || cause instanceof NoSuchPackageException) {
      return (DetailedException) cause;
    }
    return null;
  }

  private static boolean isExecutionException(@Nullable Throwable cause) {
    return cause instanceof ActionExecutionException
        || cause instanceof InputFileErrorException
        || cause instanceof TestExecException
        // Refer to UnusedInputsFailureIntegrationTest#incrementalFailureOnUnusedInput.
        || cause instanceof ArtifactNestedSetEvalException
        // For top-level outputs errors in CompletionFunction.
        || cause instanceof TopLevelOutputException;
  }

  /**
   * Process an {@link EvaluationResult}, taking into account the keepGoing setting.
   *
   * <p>Returns a nullable {@link DetailedExitCode} value, as follows:
   *
   * <ol>
   *   <li>{@code null}, if {@code result} had no errors
   *   <li>{@code e} if result had errors and one of them specified a {@link DetailedExitCode} value
   *       {@code e}
   *   <li>a {@link DetailedExitCode} with {@link Execution.Code#NON_ACTION_EXECUTION_FAILURE} if
   *       result had errors but none specified a {@link DetailedExitCode} value
   * </ol>
   *
   * <p>Throws on catastrophic failures and, if !keepGoing, on any failure. TODO(b/249690006):
   * Remove this method once the refactor is complete.
   */
  @Nullable
  public static DetailedExitCode processResult(
      ExtendedEventHandler eventHandler,
      EvaluationResult<?> result,
      boolean keepGoing,
      CyclesReporter cyclesReporter,
      @Nullable BugReporter bugReporter)
      throws BuildFailedException, TestExecException {
    if (result.hasError()) {
      for (Map.Entry<SkyKey, ErrorInfo> entry : result.errorMap().entrySet()) {
        ImmutableList<CycleInfo> cycles = entry.getValue().getCycleInfo();
        cyclesReporter.reportCycles(cycles, entry.getKey(), eventHandler);
      }

      if (result.getCatastrophe() != null) {
        rethrow(result.getCatastrophe(), bugReporter, result);
      }
      if (keepGoing) {
        return getDetailedExitCodeKeepGoing(result);
      }
      ErrorInfo errorInfo = Preconditions.checkNotNull(result.getError(), result);
      Exception exception = errorInfo.getException();
      if (exception == null) {
        Preconditions.checkState(!errorInfo.getCycleInfo().isEmpty(), errorInfo);
        // If a keepGoing=false build found a cycle, that means there were no other errors thrown
        // during evaluation (otherwise, it wouldn't have bothered to find a cycle). So the best
        // we can do is throw a generic build failure exception, since we've already reported the
        // cycles above.
        throw new BuildFailedException(null, CYCLE_CODE);
      } else {
        rethrow(exception, bugReporter, result);
      }
    }

    return null;
  }

  private static DetailedExitCode getDetailedExitCodeKeepGoing(EvaluationResult<?> result) {
    // If build fails and keepGoing is true, an exit code is assigned using reported errors
    // in the following order:
    //   1. First infrastructure error with non-null exit code
    //   2. First non-infrastructure error with non-null exit code
    //   3. If the build fails but no interpretable error is specified, BUILD_FAILURE.
    DetailedExitCode detailedExitCode = null;
    Throwable undetailedCause = null;
    for (Map.Entry<SkyKey, ErrorInfo> error : result.errorMap().entrySet()) {
      Throwable cause = error.getValue().getException();
      if (cause instanceof DetailedException) {
        // Update global exit code when current exit code is not null and global exit code has
        // a lower 'reporting' priority.
        detailedExitCode =
            DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
                detailedExitCode, ((DetailedException) cause).getDetailedExitCode());
        if (isExecutionCauseWorthLogging(cause)) {
          logger.atWarning().withCause(cause).log(
              "Non-action-execution/input-error exception for %s", error);
        }
      } else if (!error.getValue().getCycleInfo().isEmpty()) {
        detailedExitCode =
            DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
                detailedExitCode, CYCLE_CODE);
      } else {
        undetailedCause = cause;
      }
    }
    if (detailedExitCode != null) {
      return detailedExitCode;
    }
    return createDetailedExitCodeForUndetailedExecutionCauseKeepGoing(result, undetailedCause);
  }

  /**
   * Figure out why an action's analysis/execution failed and rethrow the right kind of exception.
   */
  @VisibleForTesting
  static void rethrow(
      Throwable cause, BugReporter bugReporter, EvaluationResult<?> resultForDebugging)
      throws BuildFailedException, TestExecException {
    Throwables.throwIfUnchecked(cause);
    Throwable innerCause = cause.getCause();
    if (innerCause instanceof TestExecException testExecException) {
      throw testExecException;
    }
    if (cause instanceof ActionExecutionException actionExecutionCause) {
      String message = cause.getMessage();
      if (actionExecutionCause.getAction() != null) {
        message = actionExecutionCause.getAction().describe() + " failed: " + message;
      }
      // Sometimes ActionExecutionExceptions are caused by Actions with no owner.
      if (actionExecutionCause.getLocation() != null) {
        message = actionExecutionCause.getLocation() + " " + message;
      }
      throw new BuildFailedException(
          message,
          actionExecutionCause.isCatastrophe(),
          /* errorAlreadyShown= */ !actionExecutionCause.showError(),
          actionExecutionCause.getDetailedExitCode());
    }
    if (cause instanceof InputFileErrorException inputFileErrorException) {
      throw inputFileErrorException;
    }
    if (cause instanceof TopLevelOutputException topLevelOutputException) {
      throw topLevelOutputException;
    }

    // We encountered an exception we don't think we should have encountered. This can indicate
    // an exception-processing bug in our code, such as lower level exceptions not being properly
    // handled, or in our expectations in this method.

    if (cause instanceof DetailedException) {
      // The exception escaped Skyframe error bubbling, but its failure detail can still be used.
      bugReporter.logUnexpected(
          (Exception) cause,
          "action terminated with unexpected exception with result %s",
          resultForDebugging);
      throw new BuildFailedException(
          cause.getMessage(), ((DetailedException) cause).getDetailedExitCode());
    }

    DetailedExitCode unknownExitCode =
        sendBugReportAndCreateUnknownExecutionDetailedExitCode(
            resultForDebugging, cause, bugReporter);
    throw new BuildFailedException(
        Preconditions.checkNotNull(unknownExitCode.getFailureDetail()).getMessage(),
        unknownExitCode);
  }

  private static DetailedExitCode createDetailedExitCodeForUndetailedExecutionCauseKeepGoing(
      EvaluationResult<?> result, Throwable undetailedCause) {
    if (undetailedCause == null) {
      BugReport.sendBugReport("No exceptions found despite error in %s", result);
      return createDetailedExecutionExitCode(
          "keep_going execution failed without an action failure",
          Execution.Code.NON_ACTION_EXECUTION_FAILURE);
    }
    BugReport.sendBugReport(
        new IllegalStateException("No detailed exception found in " + result, undetailedCause));
    return createDetailedExecutionExitCode(
        "keep_going execution failed without an action failure: "
            + undetailedCause.getMessage()
            + " ("
            + undetailedCause.getClass().getSimpleName()
            + ")",
        Execution.Code.NON_ACTION_EXECUTION_FAILURE);
  }

  private static final DetailedExitCode CYCLE_CODE =
      createDetailedExecutionExitCode("cycle found during execution", Execution.Code.CYCLE);
  private static final Execution UNKNOWN_EXECUTION =
      Execution.newBuilder().setCode(Execution.Code.UNEXPECTED_EXCEPTION).build();

  private static DetailedExitCode createDetailedExecutionExitCode(
      String message, Execution.Code detailedCode) {
    return createDetailedExecutionExitCode(
        message, Execution.newBuilder().setCode(detailedCode).build());
  }

  private static DetailedExitCode createDetailedExecutionExitCode(
      String message, Execution execution) {
    return DetailedExitCode.of(
        FailureDetail.newBuilder().setMessage(message).setExecution(execution).build());
  }

  private static boolean isExecutionCycle(Iterable<CycleInfo> cycleInfoCollection) {
    for (CycleInfo cycleInfo : cycleInfoCollection) {
      if (cycleInfo.getCycle().stream().allMatch(ACTION_OR_ARTIFACT_OR_TRANSITIVE_RDEP)) {
        // All these cycle info belong to the same top level key. If one of them is
        // execution-related, we consider the error to be execution-related.
        return true;
      }
    }
    return false;
  }
}
