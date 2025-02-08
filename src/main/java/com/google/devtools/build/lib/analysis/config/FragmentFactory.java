// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config;

import static com.google.common.base.Throwables.throwIfInstanceOf;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static java.util.Objects.requireNonNull;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletionException;
import javax.annotation.Nullable;

/** Handles construction of {@link Fragment} from a {@link BuildOptions}. */
public final class FragmentFactory {

  /**
   * Creates the requested {@link Fragment} using a given {@link BuildOptions}.
   *
   * <p>Returns null if the fragment could not be built (e.g. the supplied BuildOptions does not
   * contain the required {@link FragmentOption}s).
   */
  @Nullable
  public Fragment createFragment(BuildOptions buildOptions, Class<? extends Fragment> fragmentClass)
      throws InvalidConfigurationException {
    BuildOptions trimmedOptions = trimToRequiredOptions(buildOptions, fragmentClass);
    Fragment fragment;
    FragmentKey fragmentKey = FragmentKey.create(trimmedOptions, fragmentClass);
    try {
      fragment = fragmentCache.get(fragmentKey);
    } catch (CompletionException e) {
      throwIfInstanceOf(e.getCause(), InvalidConfigurationException.class);
      throwIfUnchecked(e.getCause());
      throw e;
    }
    if (fragment != NULL_MARKER) {
      return fragment;
    } else {
      // NULL_MARKER is never GC'ed, so this entry will stay in cache forever unless we delete it
      // ourselves. Since it's a cheap computation we don't care about recomputing it.
      fragmentCache.invalidate(fragmentKey);
      return null;
    }
  }

  /** Cache and associated infrastructure* */
  // Cache with weak values can't have null values.
  // TODO(blaze-configurability-team): At the moment, the only time shouldInclude is false is when
  //   TestFragment is constructed without TestOptions, which is already being registered as a
  //   required option of TestFragment. Should just abort fragment construction early when a
  //   required option is missing rather than use this NULL_MARKER infra.
  private static final Fragment NULL_MARKER = new Fragment() {};

  private final LoadingCache<FragmentKey, Fragment> fragmentCache =
      Caffeine.newBuilder().weakValues().build(FragmentFactory::makeFragment);

  private static BuildOptions trimToRequiredOptions(
      BuildOptions original, Class<? extends Fragment> fragment) {
    BuildOptions.Builder trimmed = BuildOptions.builder();
    ImmutableSet<Class<? extends FragmentOptions>> requiredOptions =
        Fragment.requiredOptions(fragment);
    for (FragmentOptions options : original.getNativeOptions()) {
      // CoreOptions is implicitly required by all fragments.
      if (options instanceof CoreOptions || requiredOptions.contains(options.getClass())) {
        trimmed.addFragmentOptions(options);
      }
    }
    if (Fragment.requiresStarlarkOptions(fragment)) {
      trimmed.addStarlarkOptions(original.getStarlarkOptions());
    }
    return trimmed.build();
  }

  /**
   * A fragment key.
   *
   * @param buildOptions These BuildOptions should be already-trimmed to maximize cache efficacy
   */
  record FragmentKey(BuildOptions buildOptions, Class<? extends Fragment> fragmentClass) {
    FragmentKey {
      requireNonNull(buildOptions, "buildOptions");
      requireNonNull(fragmentClass, "fragmentClass");
    }

    private static FragmentKey create(
        BuildOptions buildOptions, Class<? extends Fragment> fragmentClass) {
      return new FragmentKey(buildOptions, fragmentClass);
    }
  }

  private static Fragment makeFragment(FragmentKey fragmentKey)
      throws InvalidConfigurationException {
    BuildOptions buildOptions = fragmentKey.buildOptions();
    Class<? extends Fragment> fragmentClass = fragmentKey.fragmentClass();
    String noConstructorPattern = "%s lacks constructor(BuildOptions)";
    try {
      Fragment fragment =
          fragmentClass.getConstructor(BuildOptions.class).newInstance(buildOptions);
      return fragment.shouldInclude() ? fragment : NULL_MARKER;
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof InvalidConfigurationException) {
        throw (InvalidConfigurationException) e.getCause();
      }
      throw new IllegalStateException(String.format(noConstructorPattern, fragmentClass), e);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(String.format(noConstructorPattern, fragmentClass), e);
    }
  }
}
