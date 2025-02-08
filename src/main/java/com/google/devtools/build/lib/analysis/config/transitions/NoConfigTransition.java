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
package com.google.devtools.build.lib.analysis.config.transitions;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptionsView;
import com.google.devtools.build.lib.analysis.config.CommonOptions;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.starlarkbuildapi.config.ConfigurationTransitionApi;

/**
 * Transitions to a stable, empty configuration for rules that don't rely on configuration.
 *
 * <p>This prevents unnecessary configured target forking, which prevents unnecessary build graph
 * bloat. That in turn reduces build time and memory use.
 *
 * <p>For example, imagine {@code cc_library //:foo} in config A depends on config-independent
 * target {@code //:noconfig} and {@code cc_library //:bar} in config B also depends on {@code
 * //:noconfig}. Without transitions, {@code //:noconfig} will be configured and analyzed twice: for
 * configs A and B. This is completely wasteful if {@code //:noconfig} does the same thing
 * regardless of configuration. Instead, apply this transition to {@code //:noconfig}.
 *
 * <p>This is safest for rules that don't produce actions and don't have dependencies. Remember that
 * even if a rule doesn't read configuration, if any of its transitive dependencies read
 * configuration or if the rule has a {@code select()}, its output may still be
 * configuration-dependent. So use with careful discretion.
 */
public class NoConfigTransition implements PatchTransition {

  @SerializationConstant public static final NoConfigTransition INSTANCE = new NoConfigTransition();
  private static final TransitionFactory<? extends TransitionFactory.Data> FACTORY_INSTANCE =
      new Factory<>();

  /**
   * Returns {@code true} if the given {@link TransitionFactory} is an instance of the no
   * transition.
   */
  public static <T extends TransitionFactory.Data> boolean isInstance(
      TransitionFactory<T> instance) {
    return instance instanceof Factory;
  }

  private NoConfigTransition() {}

  @Override
  public ImmutableSet<Class<? extends FragmentOptions>> requiresOptionFragments() {
    return ImmutableSet.of(CoreOptions.class);
  }

  @Override
  public BuildOptions patch(BuildOptionsView options, EventHandler eventHandler) {
    return CommonOptions.EMPTY_OPTIONS;
  }

  /** Returns a {@link TransitionFactory} instance that generates the transition. */
  public static <T extends TransitionFactory.Data> TransitionFactory<T> getFactory() {
    @SuppressWarnings("unchecked")
    TransitionFactory<T> castFactory = (TransitionFactory<T>) FACTORY_INSTANCE;
    return castFactory;
  }

  /** A {@link TransitionFactory} implementation that generates the transition. */
  record Factory<T extends TransitionFactory.Data>()
      implements TransitionFactory<T>, ConfigurationTransitionApi {
    @Override
    public PatchTransition create(T unused) {
      return INSTANCE;
    }

    @Override
    public TransitionType transitionType() {
      return TransitionType.ANY;
    }

    @Override
    public boolean isTool() {
      return true;
    }
  }
}
