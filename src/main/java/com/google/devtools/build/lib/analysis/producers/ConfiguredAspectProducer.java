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

import static com.google.devtools.build.lib.analysis.AspectCollection.buildAspectKey;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.AspectCollection;
import com.google.devtools.build.lib.analysis.AspectCollection.AspectDeps;
import com.google.devtools.build.lib.analysis.AspectValue;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.TransitiveDependencyState;
import com.google.devtools.build.lib.analysis.configuredtargets.MergedConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.MergedConfiguredTarget.MergingException;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.skyframe.AspectCreationException;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.state.StateMachine;
import java.util.ArrayList;
import java.util.HashMap;
import javax.annotation.Nullable;

/** Computes {@link ConfiguredAspect}s and merges them into a prerequisite. */
final class ConfiguredAspectProducer
    implements StateMachine, StateMachine.ValueOrExceptionSink<AspectCreationException> {
  interface ResultSink {
    void acceptConfiguredAspectMergedTarget(int outputIndex, ConfiguredTargetAndData mergedTarget);

    void acceptConfiguredAspectError(AspectCreationException error);

    void acceptConfiguredAspectError(MergingException error);
  }

  // -------------------- Input --------------------
  private final AspectCollection aspects;
  private final ConfiguredTargetAndData prerequisite;

  // -------------------- Output --------------------
  private final TransitiveDependencyState transitiveState;
  private final ResultSink sink;
  private final int outputIndex;

  // -------------------- Internal State --------------------
  private final HashMap<AspectDescriptor, AspectValue> aspectValues = new HashMap<>();

  ConfiguredAspectProducer(
      AspectCollection aspects,
      ConfiguredTargetAndData prerequisite,
      ResultSink sink,
      int outputIndex,
      TransitiveDependencyState transitiveState) {
    this.aspects = aspects;
    this.prerequisite = prerequisite;
    this.sink = sink;
    this.outputIndex = outputIndex;
    this.transitiveState = transitiveState;
  }

  @Override
  public StateMachine step(Tasks tasks) {
    var baseKey = ConfiguredTargetKey.fromConfiguredTarget(prerequisite.getConfiguredTarget());
    var memoTable = new HashMap<AspectDescriptor, AspectKey>();
    for (AspectDeps deps : aspects.getUsedAspects()) {
      tasks.lookUp(
          buildAspectKey(deps, memoTable, baseKey),
          AspectCreationException.class,
          (ValueOrExceptionSink<AspectCreationException>) this);
    }
    return this::processResult;
  }

  @Override
  public void acceptValueOrException(
      @Nullable SkyValue untypedValue, @Nullable AspectCreationException error) {
    if (untypedValue != null) {
      var value = (AspectValue) untypedValue;
      aspectValues.put(value.getAspect().getDescriptor(), value);
      return;
    }
    sink.acceptConfiguredAspectError(error);
  }

  private StateMachine processResult(Tasks tasks) {
    ImmutableSet<AspectDeps> usedAspects = aspects.getUsedAspects();
    if (aspectValues.size() < usedAspects.size()) {
      return DONE; // There was an error.
    }

    var configuredAspects = new ArrayList<ConfiguredAspect>(usedAspects.size());
    for (AspectCollection.AspectDeps depAspect : usedAspects) {
      var value = aspectValues.get(depAspect.aspect());
      if (value == ConfiguredAspect.NonApplicableAspect.INSTANCE) {
        continue;
      }
      configuredAspects.add(value);
      if (transitiveState.storeTransitivePackages()) {
        transitiveState.updateTransitivePackages(
            value.getKeyForTransitivePackageTracking(), value.getTransitivePackages());
      }
    }
    try {
      sink.acceptConfiguredAspectMergedTarget(
          outputIndex,
          prerequisite.fromConfiguredTarget(
              MergedConfiguredTarget.of(prerequisite.getConfiguredTarget(), configuredAspects)));
    } catch (MergingException e) {
      sink.acceptConfiguredAspectError(e);
    }
    return DONE;
  }
}
