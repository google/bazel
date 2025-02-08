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
package com.google.devtools.build.lib.analysis.config.transitions;

import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptionsView;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.starlarkbuildapi.config.ConfigurationTransitionApi;

/** No-op configuration transition. */
public final class NoTransition implements PatchTransition {

  @SerializationConstant public static final NoTransition INSTANCE = new NoTransition();
  private static final TransitionFactory<? extends TransitionFactory.Data> FACTORY_INSTANCE =
      new Factory<>();

  private NoTransition() {}

  @Override
  public BuildOptions patch(BuildOptionsView options, EventHandler eventHandler) {
    return options.underlying();
  }

  /** Returns a {@link TransitionFactory} instance that generates the no transition. */
  public static <T extends TransitionFactory.Data> TransitionFactory<T> getFactory() {
    @SuppressWarnings("unchecked")
    TransitionFactory<T> castFactory = (TransitionFactory<T>) FACTORY_INSTANCE;
    return castFactory;
  }

  /**
   * Returns {@code true} if the given {@link TransitionFactory} is an instance of the no
   * transition.
   */
  public static <T extends TransitionFactory.Data> boolean isInstance(
      TransitionFactory<T> instance) {
    return instance instanceof Factory;
  }

  /**
   * Returns {@code true} if the given {@link ConfigurationTransition} is an instance of the no
   * transition.
   */
  public static boolean isInstance(ConfigurationTransition transition) {
    return transition instanceof NoTransition;
  }

  /** A {@link TransitionFactory} implementation that generates the no transition. */
  record Factory<T extends TransitionFactory.Data>()
      implements TransitionFactory<T>, ConfigurationTransitionApi {
    @Override
    public ConfigurationTransition create(T unused) {
      return INSTANCE;
    }

    @Override
    public TransitionType transitionType() {
      return TransitionType.ANY;
    }
  }
}
