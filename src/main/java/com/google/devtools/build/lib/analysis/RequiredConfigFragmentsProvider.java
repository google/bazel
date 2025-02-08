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

package com.google.devtools.build.lib.analysis;

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Interner;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.util.ClassName;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Provides a user-friendly list of the {@link Fragment}s and {@link
 * com.google.devtools.build.lib.analysis.config.FragmentOptions} required by this target and its
 * transitive dependencies.
 *
 * <p>See {@link com.google.devtools.build.lib.analysis.config.RequiredFragmentsUtil} for details.
 */
@Immutable
@AutoCodec
public record RequiredConfigFragmentsProvider(
    ImmutableSet<Class<? extends FragmentOptions>> optionsClasses,
    ImmutableSet<Class<? extends Fragment>> fragmentClasses,
    ImmutableSet<String> defines,
    ImmutableSet<Label> starlarkOptions)
    implements TransitiveInfoProvider {
  public RequiredConfigFragmentsProvider {
    requireNonNull(optionsClasses, "optionsClasses");
    requireNonNull(fragmentClasses, "fragmentClasses");
    requireNonNull(defines, "defines");
    requireNonNull(starlarkOptions, "starlarkOptions");
  }

  private static final Interner<RequiredConfigFragmentsProvider> interner =
      BlazeInterners.newWeakInterner();

  @SerializationConstant
  public static final RequiredConfigFragmentsProvider EMPTY =
      new RequiredConfigFragmentsProvider(
          ImmutableSet.of(), ImmutableSet.of(), ImmutableSet.of(), ImmutableSet.of());

  @Override
  public final String toString() {
    return MoreObjects.toStringHelper(RequiredConfigFragmentsProvider.class)
        .add(
            "optionsClasses",
            Collections2.transform(optionsClasses(), ClassName::getSimpleNameWithOuter))
        .add(
            "fragmentClasses",
            Collections2.transform(fragmentClasses(), ClassName::getSimpleNameWithOuter))
        .add("defines", defines())
        .add("starlarkOptions", starlarkOptions())
        .toString();
  }

  /** Merges the values of one or more {@link RequiredConfigFragmentsProvider} instances. */
  public static RequiredConfigFragmentsProvider merge(
      List<RequiredConfigFragmentsProvider> providers) {
    if (providers.isEmpty()) {
      return EMPTY;
    }
    RequiredConfigFragmentsProvider.Builder merged = null;
    RequiredConfigFragmentsProvider candidate = EMPTY;
    for (RequiredConfigFragmentsProvider provider : providers) {
      if (provider == EMPTY) {
        continue;
      }
      if (merged != null) {
        merged.merge(provider);
      } else if (candidate == EMPTY) {
        candidate = provider;
      } else {
        merged = builder().merge(candidate).merge(provider);
      }
    }
    return merged == null ? candidate : merged.build();
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder for required config fragments.
   *
   * <p>The builder uses a merging strategy that favors reuse of {@link ImmutableSet} instances and
   * avoids copying data if possible (i.e. when adding elements that are already present). For this
   * reason, adding transitively required fragments <em>before</em> directly required fragments is
   * likely to result in better performance, as it promotes reuse of existing sets from
   * dependencies.
   */
  public static final class Builder {
    private Set<Class<? extends FragmentOptions>> optionsClasses = ImmutableSet.of();
    private Set<Class<? extends Fragment>> fragmentClasses = ImmutableSet.of();
    private Set<String> defines = ImmutableSet.of();
    private Set<Label> starlarkOptions = ImmutableSet.of();

    private Builder() {}

    @CanIgnoreReturnValue
    public Builder addOptionsClass(Class<? extends FragmentOptions> optionsClass) {
      optionsClasses = append(optionsClasses, optionsClass);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addOptionsClasses(Collection<Class<? extends FragmentOptions>> optionsClasses) {
      this.optionsClasses = appendAll(this.optionsClasses, optionsClasses);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addFragmentClasses(Collection<Class<? extends Fragment>> fragmentClasses) {
      this.fragmentClasses = appendAll(this.fragmentClasses, fragmentClasses);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addDefine(String define) {
      defines = append(defines, define);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addDefines(Collection<String> defines) {
      this.defines = appendAll(this.defines, defines);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addStarlarkOption(Label starlarkOption) {
      starlarkOptions = append(starlarkOptions, starlarkOption);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addStarlarkOptions(Collection<Label> starlarkOptions) {
      this.starlarkOptions = appendAll(this.starlarkOptions, starlarkOptions);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder merge(RequiredConfigFragmentsProvider provider) {
      if (provider != null) {
        optionsClasses = appendAll(optionsClasses, provider.optionsClasses());
        fragmentClasses = appendAll(fragmentClasses, provider.fragmentClasses());
        defines = appendAll(defines, provider.defines());
        starlarkOptions = appendAll(starlarkOptions, provider.starlarkOptions());
      }
      return this;
    }

    private static <T> Set<T> append(Set<T> set, T t) {
      if (set instanceof ImmutableSet) {
        if (set.contains(t)) {
          return set;
        }
        set = new HashSet<>(set);
      }
      set.add(t);
      return set;
    }

    private static <T> Set<T> appendAll(Set<T> set, Collection<T> ts) {
      if (ts instanceof Set) {
        return appendAll(set, (Set<T>) ts);
      }
      if (set instanceof ImmutableSet) {
        if (set.containsAll(ts)) {
          return set;
        }
        set = new HashSet<>(set);
      }
      set.addAll(ts);
      return set;
    }

    private static <T> Set<T> appendAll(Set<T> set, Set<T> ts) {
      if (set.size() > ts.size()) {
        if (set instanceof ImmutableSet && set.containsAll(ts)) {
          return set;
        }
      } else if (ts.size() > set.size()) {
        if (ts instanceof ImmutableSet && ts.containsAll(set)) {
          return ts;
        }
      } else { // Sizes equal.
        if (set instanceof ImmutableSet) {
          if (set.equals(ts)) {
            return set;
          }
        } else if (ts instanceof ImmutableSet && ts.equals(set)) {
          return ts;
        }
      }
      if (set instanceof ImmutableSet) {
        set = new HashSet<>(set);
      }
      set.addAll(ts);
      return set;
    }

    public RequiredConfigFragmentsProvider build() {
      if (optionsClasses.isEmpty()
          && fragmentClasses.isEmpty()
          && defines.isEmpty()
          && starlarkOptions.isEmpty()) {
        return EMPTY;
      }
      return interner.intern(
          new RequiredConfigFragmentsProvider(
              ImmutableSet.copyOf(optionsClasses),
              ImmutableSet.copyOf(fragmentClasses),
              ImmutableSet.copyOf(defines),
              ImmutableSet.copyOf(starlarkOptions)));
    }
  }
}
