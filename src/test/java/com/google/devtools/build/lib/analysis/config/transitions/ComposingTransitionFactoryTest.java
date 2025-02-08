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
package com.google.devtools.build.lib.analysis.config.transitions;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptionsView;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.rules.cpp.CppOptions;
import com.google.devtools.build.lib.rules.java.JavaOptions;
import java.util.Collection;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ComposingTransitionFactory}. */
@RunWith(JUnit4.class)
public class ComposingTransitionFactoryTest {
  // Use starlark flags for the test since they are easy to set and check.
  private static final Label FLAG_1 = Label.parseCanonicalUnchecked("//flag1");
  private static final Label FLAG_2 = Label.parseCanonicalUnchecked("//flag2");
  private EventHandler eventHandler;

  @Before
  public void init() {
    eventHandler = new StoredEventHandler();
  }

  @Test
  public void compose_patch_patch() throws Exception {
    // Same flag, will overwrite.
    TransitionFactory<StubData> composed =
        ComposingTransitionFactory.of(
            new StubPatchFactory(FLAG_1, "value1"), new StubPatchFactory(FLAG_1, "value2"));

    assertThat(composed).isNotNull();
    assertThat(composed.isSplit()).isFalse();
    ConfigurationTransition transition = composed.create(new StubData());
    Collection<BuildOptions> results =
        transition
            .apply(
                TransitionUtil.restrict(transition, BuildOptions.builder().build()), eventHandler)
            .values();
    assertThat(results).isNotNull();
    assertThat(results).hasSize(1);
    BuildOptions result = Iterables.getOnlyElement(results);
    assertThat(result).isNotNull();
    assertThat(result.getStarlarkOptions()).containsEntry(FLAG_1, "value2");
  }

  @Test
  public void compose_patch_split() throws Exception {
    // Different flags, will combine.
    TransitionFactory<StubData> composed =
        ComposingTransitionFactory.of(
            new StubPatchFactory(FLAG_1, "value1"),
            new StubSplitFactory(FLAG_2, "value2a", "value2b"));

    assertThat(composed).isNotNull();
    assertThat(composed.isSplit()).isTrue();
    ConfigurationTransition transition = composed.create(new StubData());
    Map<String, BuildOptions> results =
        transition.apply(
            TransitionUtil.restrict(transition, BuildOptions.builder().build()), eventHandler);
    assertThat(results).isNotNull();
    assertThat(results).hasSize(2);

    BuildOptions result0 = results.get("stub_split0");
    assertThat(result0).isNotNull();
    assertThat(result0.getStarlarkOptions()).containsEntry(FLAG_1, "value1");
    assertThat(result0.getStarlarkOptions()).containsEntry(FLAG_2, "value2a");

    BuildOptions result1 = results.get("stub_split1");
    assertThat(result1).isNotNull();
    assertThat(result1.getStarlarkOptions()).containsEntry(FLAG_1, "value1");
    assertThat(result1.getStarlarkOptions()).containsEntry(FLAG_2, "value2b");
  }

  @Test
  public void compose_split_patch() throws Exception {
    // Different flags, will combine.
    TransitionFactory<StubData> composed =
        ComposingTransitionFactory.of(
            new StubSplitFactory(FLAG_1, "value1a", "value1b"),
            new StubPatchFactory(FLAG_2, "value2"));

    assertThat(composed).isNotNull();
    assertThat(composed.isSplit()).isTrue();
    ConfigurationTransition transition = composed.create(new StubData());
    Map<String, BuildOptions> results =
        transition.apply(
            TransitionUtil.restrict(transition, BuildOptions.builder().build()), eventHandler);
    assertThat(results).isNotNull();
    assertThat(results).hasSize(2);

    BuildOptions result0 = results.get("stub_split0");
    assertThat(result0).isNotNull();
    assertThat(result0.getStarlarkOptions()).containsEntry(FLAG_1, "value1a");
    assertThat(result0.getStarlarkOptions()).containsEntry(FLAG_2, "value2");

    BuildOptions result1 = results.get("stub_split1");
    assertThat(result1).isNotNull();
    assertThat(result1.getStarlarkOptions()).containsEntry(FLAG_1, "value1b");
    assertThat(result1.getStarlarkOptions()).containsEntry(FLAG_2, "value2");
  }

  @Test
  public void compose_split_split() {
    // Combining two split transition factories is not allowed.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ComposingTransitionFactory.of(
                new StubSplitFactory(FLAG_1, "value1a", "value1b"),
                new StubSplitFactory(FLAG_2, "value2a", "value2b")));
  }

  @Test
  public void compose_noTrans_first() {
    TransitionFactory<StubData> patch = new StubPatchFactory(FLAG_1, "value");
    TransitionFactory<StubData> composed =
        ComposingTransitionFactory.of(NoTransition.getFactory(), patch);

    assertThat(composed).isNotNull();
    assertThat(composed).isEqualTo(patch);
  }

  @Test
  public void compose_noTrans_second() {
    TransitionFactory<StubData> patch = new StubPatchFactory(FLAG_1, "value");
    TransitionFactory<StubData> composed =
        ComposingTransitionFactory.of(patch, NoTransition.getFactory());

    assertThat(composed).isNotNull();
    assertThat(composed).isEqualTo(patch);
  }

  private static final class StubData implements TransitionFactory.Data {}

  // Helper methods and classes for the tests.
  private static BuildOptions updateOptions(BuildOptions source, Label flag, String value) {
    return source.clone().toBuilder().addStarlarkOption(flag, value).build();
  }

  private static final class StubPatchFactory implements TransitionFactory<StubData> {
    private final Label flagLabel;
    private final String flagValue;

    StubPatchFactory(Label flagLabel, String flagValue) {
      this.flagLabel = flagLabel;
      this.flagValue = flagValue;
    }

    @Override
    public ConfigurationTransition create(StubData data) {
      return (PatchTransition)
          (options, eventHandler) -> updateOptions(options.underlying(), flagLabel, flagValue);
    }

    @Override
    public TransitionType transitionType() {
      return TransitionType.ANY;
    }
  }

  private static final class StubSplitFactory implements TransitionFactory<StubData> {
    private final Label flagLabel;
    private final ImmutableList<String> flagValues;

    StubSplitFactory(Label flagLabel, String... flagValues) {
      this.flagLabel = flagLabel;
      this.flagValues = ImmutableList.copyOf(flagValues);
    }

    @Override
    public ConfigurationTransition create(StubData data) {
      return (SplitTransition)
          (options, eventHandler) ->
              IntStream.range(0, flagValues.size())
                  .boxed()
                  .collect(
                      toImmutableMap(
                          i -> "stub_split" + i,
                          i -> updateOptions(options.underlying(), flagLabel, flagValues.get(i))));
    }

    @Override
    public TransitionType transitionType() {
      return TransitionType.ANY;
    }

    @Override
    public boolean isSplit() {
      return true;
    }
  }

  /** Custom fragment for use in tests. */
  private static final class TransitionFactoryWithCustomFragments
      implements TransitionFactory<StubData> {
    private final ImmutableSet<Class<? extends FragmentOptions>> fragments;

    TransitionFactoryWithCustomFragments(ImmutableSet<Class<? extends FragmentOptions>> fragments) {
      this.fragments = fragments;
    }

    @Override
    public ConfigurationTransition create(StubData data) {
      return new PatchTransition() {
        @Override
        public ImmutableSet<Class<? extends FragmentOptions>> requiresOptionFragments() {
          return fragments;
        }

        @Override
        public BuildOptions patch(BuildOptionsView options, EventHandler eventHandler) {
          return options.underlying();
        }
      };
    }

    @Override
    public TransitionType transitionType() {
      return TransitionType.ANY;
    }
  }

  @Test
  public void composed_required_fragments() throws Exception {
    TransitionFactory<StubData> composed =
        ComposingTransitionFactory.of(
            new TransitionFactoryWithCustomFragments(ImmutableSet.of(CppOptions.class)),
            new TransitionFactoryWithCustomFragments(ImmutableSet.of(JavaOptions.class)));
    RequiredConfigFragmentsProvider.Builder requiredFragments =
        RequiredConfigFragmentsProvider.builder();
    ConfigurationTransition transition = composed.create(new StubData());
    transition.addRequiredFragments(requiredFragments, null);
    assertThat(requiredFragments.build().optionsClasses())
        .containsExactly(CppOptions.class, JavaOptions.class);
  }
}
