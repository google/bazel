// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.testing.common;

import com.google.common.collect.ImmutableClassToInstanceMap;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.common.options.Options;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsProvider;
import com.google.devtools.common.options.ParsedOptionDescription;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Map;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Fake options class, allowing to easily create an {@link OptionsProvider} with injected options.
 *
 * <p>The alternative to {@link FakeOptions} would be creating an {@link
 * com.google.devtools.common.options.OptionsParser} and parsing arguments.
 */
public final class FakeOptions implements OptionsProvider {
  private final ImmutableClassToInstanceMap<OptionsBase> options;

  private FakeOptions(ImmutableClassToInstanceMap<OptionsBase> options) {
    this.options = options;
  }

  /** Creates an {@link OptionsProvider} with a provided options value for its class. */
  public static <O extends OptionsBase> OptionsProvider of(O options) {
    return builder().put(options).build();
  }

  /**
   * Creates an {@link OptionsProvider} which has defaults for all provided {@linkplain OptionsBase
   * option} classes.
   */
  @SafeVarargs
  public static OptionsProvider ofDefaults(Class<? extends OptionsBase>... optionsClasses) {
    return builder().putDefaults(optionsClasses).build();
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link FakeOptions}. */
  public static final class Builder {
    private final ImmutableMap.Builder<Class<? extends OptionsBase>, OptionsBase> options =
        ImmutableMap.builder();

    private Builder() {}

    /**
     * Adds a specified option for the {@linkplain OptionsBase options} class.
     *
     * <p>Please note that {@link build} will fail if this method is called twice with options of
     * the same class.
     */
    @CanIgnoreReturnValue
    public <O extends OptionsBase> Builder put(O options) {
      this.options.put(options.getClass(), options);
      return this;
    }

    /**
     * Puts defaults for each of the provided {@linkplain OptionsBase option classes}.
     *
     * <p>Please note that {@link build} will fail if we overwrite an already specified {@linkplain
     * OptionsBase options} class.
     */
    @CanIgnoreReturnValue
    @SafeVarargs
    public final Builder putDefaults(Class<? extends OptionsBase>... optionsClasses) {
      for (Class<? extends OptionsBase> optionsClass : optionsClasses) {
        put(Options.getDefaults(optionsClass));
      }
      return this;
    }

    public OptionsProvider build() {
      ImmutableMap<Class<? extends OptionsBase>, OptionsBase> optionsMap = options.build();
      if (optionsMap.isEmpty()) {
        return OptionsProvider.EMPTY;
      }
      return new FakeOptions(ImmutableClassToInstanceMap.copyOf(optionsMap));
    }
  }

  @Override
  @Nullable
  public <O extends OptionsBase> O getOptions(Class<O> optionsClass) {
    return options.getInstance(optionsClass);
  }

  @Override
  public ImmutableMap<String, Object> getStarlarkOptions() {
    return ImmutableMap.of();
  }

  @Override
  public ImmutableMap<String, String> getScopesAttributes() {
    return ImmutableMap.of();
  }

  @Override
  public Map<String, Object> getExplicitStarlarkOptions(
      Predicate<? super ParsedOptionDescription> filter) {
    return ImmutableMap.of();
  }

  @Override
  public ImmutableMap<String, String> getUserOptions() {
    return ImmutableMap.of();
  }
}
