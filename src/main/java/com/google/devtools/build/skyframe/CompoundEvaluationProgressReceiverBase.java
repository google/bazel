// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.skyframe;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.skyframe.NodeEntry.DirtyType;
import javax.annotation.Nullable;

/**
 * Helper class to allow implementing {@link EvaluationProgressReceiver} implementations which
 * delegate to a bunch of other {@link EvaluationProgressReceiver}s.
 */
public class CompoundEvaluationProgressReceiverBase implements EvaluationProgressReceiver {
  protected final ImmutableList<? extends EvaluationProgressReceiver> receivers;

  protected CompoundEvaluationProgressReceiverBase(
      ImmutableList<? extends EvaluationProgressReceiver> receivers) {
    this.receivers = receivers;
  }

  @Override
  public void dirtied(SkyKey skyKey, DirtyType dirtyType) {
    for (EvaluationProgressReceiver receiver : receivers) {
      receiver.dirtied(skyKey, dirtyType);
    }
  }

  @Override
  public void deleted(SkyKey skyKey) {
    for (EvaluationProgressReceiver receiver : receivers) {
      receiver.deleted(skyKey);
    }
  }

  @Override
  public void enqueueing(SkyKey skyKey) {
    for (EvaluationProgressReceiver receiver : receivers) {
      receiver.enqueueing(skyKey);
    }
  }

  @Override
  public void stateStarting(SkyKey skyKey, NodeState state) {
    for (EvaluationProgressReceiver receiver : receivers) {
      receiver.stateStarting(skyKey, state);
    }
  }

  @Override
  public void stateEnding(SkyKey skyKey, NodeState state) {
    for (EvaluationProgressReceiver receiver : receivers) {
      receiver.stateEnding(skyKey, state);
    }
  }

  @Override
  public void evaluated(
      SkyKey skyKey,
      EvaluationState state,
      @Nullable SkyValue newValue,
      @Nullable ErrorInfo newError,
      @Nullable GroupedDeps directDeps) {
    for (EvaluationProgressReceiver receiver : receivers) {
      receiver.evaluated(skyKey, state, newValue, newError, directDeps);
    }
  }

  @Override
  public void changePruned(SkyKey skyKey) {
    for (EvaluationProgressReceiver receiver : receivers) {
      receiver.changePruned(skyKey);
    }
  }
}
