// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.producers;

import static com.google.devtools.build.lib.analysis.AspectResolutionHelpers.computePropagatingAspects;
import static com.google.devtools.build.lib.analysis.producers.DependencyError.isSecondErrorMoreImportant;
import static java.util.Arrays.asList;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.analysis.DependencyKind;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionCollector;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.ImmutableSortedKeyListMultimap;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.MaterializingDefault;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Type.LabelClass;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import com.google.devtools.build.skyframe.state.StateMachine;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;

/**
 * Computes the full multimap of prerequisite values from a multimap of labels.
 *
 * <p>This class creates a child {@link DependencyProducer} for each ({@link DependencyKind}, {@link
 * Label}) multimap entry and collects the results. It outputs a multimap with the same entries,
 * replacing {@link Label} values with the corresponding computed {@link ConfiguredTargetAndData}
 * dependency values.
 */
public final class DependencyMapProducer implements StateMachine, DependencyProducer.ResultSink {
  /** Receiver for output of {@link DependencyMapProducer}. */
  public interface ResultSink extends TransitionCollector {
    void acceptDependencyMap(OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> value);

    void acceptDependencyMapError(DependencyError error);

    void acceptDependencyMapError(MissingEdgeError error);
  }

  // -------------------- Input --------------------
  private final PrerequisiteParameters parameters;
  private final OrderedSetMultimap<DependencyKind, Label> dependencyLabels;

  // -------------------- Output --------------------
  private final ResultSink sink;

  // -------------------- Internal State --------------------
  /**
   * This buffer receives results from child {@link DependencyProducer}s.
   *
   * <p>The indices break down the result by the following.
   *
   * <ol>
   *   <li>The entries of {@link #dependencyLabels}.
   *   <li>The configurations for that entry (more than one if there is a split transition).
   * </ol>
   *
   * <p>It would not be straightforward to replace this with a {@link OrderedSetMultimap} because
   * the child {@link DependencyProducer}s complete in an arbitrary order and the ordering of {@link
   * #dependencyLabels} must be preserved. Additionally, this is a fairly hot codepath and the
   * additional overhead of maps would consume significant resources.
   */
  private final ConfiguredTargetAndData[][] results;

  private DependencyError lastError;

  public DependencyMapProducer(
      PrerequisiteParameters parameters,
      OrderedSetMultimap<DependencyKind, Label> dependencyLabels,
      ResultSink sink) {
    this.parameters = parameters;
    this.dependencyLabels = dependencyLabels;
    this.sink = sink;
    this.results = new ConfiguredTargetAndData[dependencyLabels.size()][];
  }

  private static boolean isForDependencyResolution(DependencyKind dependencyKind) {
    if (dependencyKind.getAttribute() == null) {
      return false;
    }

    return dependencyKind.getAttribute().isForDependencyResolution();
  }

  private ImmutableMap<String, Object> computePrerequisitesForMaterializer(
      Rule rule, ImmutableSortedKeyListMultimap<String, ConfiguredTargetAndData> dependencyMap) {
    Map<String, Object> result = new TreeMap<>();

    for (Attribute attribute : rule.getAttributes()) {
      if (attribute.getType().getLabelClass() != LabelClass.DEPENDENCY
          || !attribute.isForDependencyResolution()) {
        continue;
      }

      result.put(
          attribute.getName(),
          Lists.transform(
              dependencyMap.get(attribute.getName()),
              ConfiguredTargetAndData::getConfiguredTarget));
    }

    return ImmutableMap.copyOf(result);
  }

  /** An exception thrown if a materializer cannot be evaluated. */
  public static class MaterializerException extends Exception {
    public MaterializerException(
        Attribute attribute, Label label, String message, Exception cause) {
      super(
          String.format(
              "Error while evaluating materializer on attribute '%s' or rule '%s': %s",
              attribute.getPublicName(), label, message),
          cause);
    }
  }

  @SuppressWarnings("rawtypes")
  @Nullable
  private ImmutableList<Label> getMaterializationResultMaybe(DependencyKind kind)
      throws InterruptedException {
    if (kind.getAttribute() == null) {
      return null;
    }

    if (!kind.getAttribute().isMaterializing()) {
      return null;
    }

    // By this point, we know the attribute is a materializingDefault. Compute the attributes
    // available to
    // it...
    ImmutableSortedKeyListMultimap<String, ConfiguredTargetAndData> attrs = createMaterializerMap();
    ImmutableMap<String, Object> prerequisitesForMaterializer =
        computePrerequisitesForMaterializer(parameters.associatedRule(), attrs);

    // ...then invoke the function,
    MaterializingDefault materializingDefault = kind.getAttribute().getMaterializer();
    Object materializerResult;
    try {
      materializerResult =
          materializingDefault.resolve(
              parameters.associatedRule(),
              parameters.attributeMap(),
              prerequisitesForMaterializer,
              parameters.eventHandler());
    } catch (EvalException e) {
      parameters.eventHandler().handle(Event.error(parameters.location(), e.getMessageWithStack()));
      acceptDependencyError(
          DependencyError.of(
              new MaterializerException(
                  kind.getAttribute(), parameters.label(), e.getMessage(), e)));
      return null;
    }

    // ...then return its return value as the value of the attribute.
    if (kind.getAttribute().getType() == BuildType.LABEL) {
      return materializerResult == null
          ? ImmutableList.of()
          : ImmutableList.of(BuildType.LABEL.cast(materializerResult));
    } else if (kind.getAttribute().getType() == BuildType.LABEL_LIST) {
      return ImmutableList.copyOf(BuildType.LABEL_LIST.cast(materializerResult));
    } else {
      throw new IllegalStateException("bad value returned from materializingDefault");
    }
  }

  private class MaterializedDependencySink implements DependencyProducer.ResultSink {
    private int remaining;
    private final int resultsIndex;
    // The outer array is for the individual labels the materializer returns, the inner array is for
    // the different configurations in case the attribute has a split transition
    private final ConfiguredTargetAndData[][] materializationResults;

    private MaterializedDependencySink(int resultsIndex, int labelCount) {
      this.resultsIndex = resultsIndex;
      this.remaining = labelCount;
      this.materializationResults = new ConfiguredTargetAndData[labelCount][];
    }

    @Override
    public void acceptTransition(
        DependencyKind kind, Label label, ConfigurationTransition transition) {
      DependencyMapProducer.this.acceptTransition(kind, label, transition);
    }

    @Override
    public void acceptDependencyValues(int index, ConfiguredTargetAndData[] values) {
      materializationResults[index] = values;
      if (--remaining > 0) {
        // More dependencies to come
        return;
      }

      // "results" is an array of arrays: for each (dependency kind, label) pair, it contains an
      // array with a dependency for each configuration in a split transition. Materializers abuse
      // this mechanism by putting all configured targets returned by a materializer into the second
      // array because it cannot be known how many of them there are before "results" is created.
      // This means that if a materializer has a split configuration, we need to do a level of
      // flattening here.
      results[resultsIndex] =
          Arrays.stream(materializationResults)
              .flatMap(Arrays::stream)
              .toArray(ConfiguredTargetAndData[]::new);
    }

    @Override
    public void acceptDependencyError(DependencyError error) {
      DependencyMapProducer.this.acceptDependencyError(error);
    }

    @Override
    public void acceptDependencyError(MissingEdgeError error) {
      DependencyMapProducer.this.acceptDependencyError(error);
    }
  }

  private StateMachine attributeResolutionStep(
      Tasks tasks, boolean forMaterializers, StateMachine next) throws InterruptedException {
    int index = 0;
    for (Map.Entry<DependencyKind, Collection<Label>> entry : dependencyLabels.asMap().entrySet()) {
      var kind = entry.getKey();
      boolean forDependencyResolution = isForDependencyResolution(kind);
      boolean skip = forMaterializers != forDependencyResolution;

      // Only call materializer when materialization results are ready
      ImmutableList<Label> materializationResults =
          forMaterializers ? null : getMaterializationResultMaybe(kind);

      // The list of aspects is evaluated here to be done once per attribute, rather than once per
      // dependency.
      ImmutableList<Aspect> aspects =
          skip
              ? null
              : computePropagatingAspects(
                  kind,
                  parameters.aspects(),
                  parameters.associatedRule(),
                  parameters.baseTargetToolchainContexts());
      for (var label : entry.getValue()) {
        int currentIndex = index++;
        if (skip) {
          continue;
        }

        if (materializationResults != null) {
          // DependencyResolver should have left this as null
          Preconditions.checkState(label == null);

          if (materializationResults.isEmpty()) {
            results[currentIndex] = new ConfiguredTargetAndData[] {};
          } else {
            MaterializedDependencySink sink =
                new MaterializedDependencySink(currentIndex, materializationResults.size());
            for (int i = 0; i < materializationResults.size(); i++) {
              tasks.enqueue(
                  new DependencyProducer(
                      parameters, kind, materializationResults.get(i), aspects, sink, i));
            }
          }
        } else if (label != null) {
          tasks.enqueue(
              new DependencyProducer(
                  parameters,
                  kind,
                  label,
                  aspects,
                  (DependencyProducer.ResultSink) this,
                  currentIndex));
        }
      }
    }

    return next;
  }

  @Override
  public StateMachine step(Tasks tasks) throws InterruptedException {
    return attributeResolutionStep(tasks, true, this::evaluateMaterializersIfNeeded);
  }

  private StateMachine evaluateMaterializersIfNeeded(Tasks tasks) throws InterruptedException {
    return attributeResolutionStep(tasks, false, this::buildAndEmitResult);
  }

  @Override
  public void acceptDependencyValues(int index, ConfiguredTargetAndData[] values) {
    results[index] = values;
  }

  @Override
  public void acceptDependencyError(DependencyError error) {
    emitErrorIfMostImportant(error);
  }

  @Override
  public void acceptDependencyError(MissingEdgeError error) {
    sink.acceptDependencyMapError(error);
  }

  @Override
  public void acceptTransition(
      DependencyKind kind, Label label, ConfigurationTransition transition) {
    sink.acceptTransition(kind, label, transition);
  }

  @SuppressWarnings("MultimapKeys")
  private ImmutableSortedKeyListMultimap<String, ConfiguredTargetAndData> createMaterializerMap() {
    var result = ImmutableSortedKeyListMultimap.<String, ConfiguredTargetAndData>builder();
    int i = 0;
    // It's correct to call .keys() here: it's called once for every entry in the map (not just for
    // every key), which is what's needed to keep in sync with the array in 'results'.
    for (DependencyKind kind : dependencyLabels.keys()) {
      ConfiguredTargetAndData[] deps = results[i++];
      if (deps == null) {
        continue;
      }

      Attribute attribute = kind.getAttribute();
      if (attribute == null) {
        continue;
      }

      // An empty `result` means the entry is skipped due to a missing exec group.
      if (deps.length > 0) {
        result.putAll(attribute.getName(), asList(deps));
      }
    }

    return result.build();
  }

  @SuppressWarnings("MultimapKeys")
  private StateMachine buildAndEmitResult(Tasks tasks) {
    if (lastError != null || parameters.transitiveState().hasRootCause()) {
      return DONE; // There was an error.
    }

    var output = new OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData>();
    int i = 0;
    // It's correct to call .keys() here: it's called once for every entry in the map (not just for
    // every key), which is what's needed to keep in sync with the array in 'results'.
    for (DependencyKind kind : dependencyLabels.keys()) {
      ConfiguredTargetAndData[] result = results[i++];
      if (result == null) {
        return DONE; // There was an error.
      }
      // An empty `result` means the entry is skipped due to a missing exec group.
      if (result.length > 0) {
        output.putAll(kind, asList(result));
      }
    }

    sink.acceptDependencyMap(output);
    return DONE;
  }

  private void emitErrorIfMostImportant(DependencyError error) {
    if (lastError == null || isSecondErrorMoreImportant(lastError, error)) {
      lastError = error;
      sink.acceptDependencyMapError(error);
    }
  }
}
