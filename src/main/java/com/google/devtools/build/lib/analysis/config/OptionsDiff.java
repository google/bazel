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
package com.google.devtools.build.lib.analysis.config;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import com.google.devtools.common.options.OptionDefinition;
import com.google.devtools.common.options.OptionsParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.Set;

/**
 * A diff class for BuildOptions. Fields are meant to be populated and returned by {@link
 * OptionsDiff#diff}.
 */
public final class OptionsDiff {
  /** Returns the difference between two BuildOptions in a new {@link OptionsDiff}. */
  public static OptionsDiff diff(BuildOptions first, BuildOptions second) {
    return diff(new OptionsDiff(), first, second);
  }

  /**
   * Returns the difference between two BuildOptions in a pre-existing {@link OptionsDiff}.
   *
   * <p>In a single pass through this method, the method can only compare a single "first" {@link
   * BuildOptions} and single "second" BuildOptions; but an OptionsDiff instance can store the diff
   * between a single "first" BuildOptions and multiple "second" BuildOptions. Being able to
   * maintain a single OptionsDiff over multiple calls to diff is useful for, for example,
   * aggregating the difference between a single BuildOptions and the results of applying a {@link
   * com.google.devtools.build.lib.analysis.config.transitions.SplitTransition}) to it.
   */
  @SuppressWarnings("ReferenceEquality") // See comment above == comparison.
  public static OptionsDiff diff(OptionsDiff diff, BuildOptions first, BuildOptions second) {
    checkArgument(
        !diff.hasStarlarkOptions,
        "OptionsDiff cannot handle multiple 'second' BuildOptions with Starlark options and is"
            + " trying to diff against %s",
        diff);
    checkNotNull(first);
    checkNotNull(second);
    if (first.equals(second)) {
      return diff;
    }

    // Check and report if either class has been trimmed of an options class that exists in the
    // other.
    ImmutableSet<Class<? extends FragmentOptions>> firstOptionClasses =
        first.getNativeOptions().stream()
            .map(FragmentOptions::getClass)
            .collect(ImmutableSet.toImmutableSet());
    ImmutableSet<Class<? extends FragmentOptions>> secondOptionClasses =
        second.getNativeOptions().stream()
            .map(FragmentOptions::getClass)
            .collect(ImmutableSet.toImmutableSet());
    Sets.difference(firstOptionClasses, secondOptionClasses).forEach(diff::addExtraFirstFragment);
    Sets.difference(secondOptionClasses, firstOptionClasses).stream()
        .map(second::get)
        .forEach(diff::addExtraSecondFragment);

    // For fragments in common, report differences.
    for (Class<? extends FragmentOptions> clazz :
        Sets.intersection(firstOptionClasses, secondOptionClasses)) {
      FragmentOptions firstOptions = first.get(clazz);
      FragmentOptions secondOptions = second.get(clazz);
      // We avoid calling #equals because we are going to do a field-by-field comparison anyway.
      if (firstOptions == secondOptions) {
        continue;
      }
      for (OptionDefinition definition : OptionsParser.getOptionDefinitions(clazz)) {
        Object firstValue = firstOptions.getValueFromDefinition(definition);
        Object secondValue = secondOptions.getValueFromDefinition(definition);
        if (!Objects.equals(firstValue, secondValue)) {
          diff.addDiff(clazz, definition, firstValue, secondValue);
        }
      }
    }

    // Compare Starlark options for the two classes.
    ImmutableMap<Label, Object> starlarkFirst = first.getStarlarkOptions();
    ImmutableMap<Label, Object> starlarkSecond = second.getStarlarkOptions();
    for (Label buildSetting : Sets.union(starlarkFirst.keySet(), starlarkSecond.keySet())) {
      if (starlarkFirst.get(buildSetting) == null) {
        diff.addExtraSecondStarlarkOption(buildSetting, starlarkSecond.get(buildSetting));
      } else if (starlarkSecond.get(buildSetting) == null) {
        diff.addExtraFirstStarlarkOption(buildSetting);
      } else if (!starlarkFirst.get(buildSetting).equals(starlarkSecond.get(buildSetting))) {
        diff.putStarlarkDiff(
            buildSetting, starlarkFirst.get(buildSetting), starlarkSecond.get(buildSetting));
      }
    }
    return diff;
  }

  private final ListMultimap<Class<? extends FragmentOptions>, OptionDefinition> differingOptions =
      ArrayListMultimap.create();
  // The keyset for the {@link first} and {@link second} maps are identical and indicate which
  // specific options differ between the first and second built options.
  private final Map<OptionDefinition, Object> first = new LinkedHashMap<>();
  // Since this class can be used to track the result of transitions, {@link second} is a multimap
  // to be able to handle {@link SplitTransition}s.
  private final SetMultimap<OptionDefinition, Object> second = OrderedSetMultimap.create();
  // List of "extra" fragments for each BuildOption aka fragments that were trimmed off one
  // BuildOption but not the other.
  private final Set<Class<? extends FragmentOptions>> extraFirstFragments = new HashSet<>();
  private final Set<FragmentOptions> extraSecondFragments = new HashSet<>();

  private final Map<Label, Object> starlarkFirst = new LinkedHashMap<>();
  // TODO(b/112041323): This should also be multimap but we don't diff multiple times with
  // Starlark options anywhere yet so add that feature when necessary.
  private final Map<Label, Object> starlarkSecond = new LinkedHashMap<>();

  private final List<Label> extraStarlarkOptionsFirst = new ArrayList<>();
  private final SequencedMap<Label, Object> extraStarlarkOptionsSecond = new LinkedHashMap<>();

  private boolean hasStarlarkOptions = false;

  @VisibleForTesting
  Set<Class<? extends FragmentOptions>> getExtraFirstFragmentClassesForTesting() {
    return extraFirstFragments;
  }

  @VisibleForTesting
  Set<FragmentOptions> getExtraSecondFragmentsForTesting() {
    return extraSecondFragments;
  }

  public Map<OptionDefinition, Object> getFirst() {
    return first;
  }

  public Multimap<OptionDefinition, Object> getSecond() {
    return second;
  }

  private void addDiff(
      Class<? extends FragmentOptions> fragmentOptionsClass,
      OptionDefinition option,
      Object firstValue,
      Object secondValue) {
    differingOptions.put(fragmentOptionsClass, option);
    first.put(option, firstValue);
    second.put(option, secondValue);
  }

  private void addExtraFirstFragment(Class<? extends FragmentOptions> options) {
    extraFirstFragments.add(options);
  }

  private void addExtraSecondFragment(FragmentOptions options) {
    extraSecondFragments.add(options);
  }

  private void putStarlarkDiff(Label buildSetting, Object firstValue, Object secondValue) {
    starlarkFirst.put(buildSetting, firstValue);
    starlarkSecond.put(buildSetting, secondValue);
    hasStarlarkOptions = true;
  }

  private void addExtraFirstStarlarkOption(Label buildSetting) {
    extraStarlarkOptionsFirst.add(buildSetting);
    hasStarlarkOptions = true;
  }

  private void addExtraSecondStarlarkOption(Label buildSetting, Object value) {
    extraStarlarkOptionsSecond.put(buildSetting, value);
    hasStarlarkOptions = true;
  }

  /**
   * Returns the labels of all starlark options that caused a difference between the first and
   * second options set.
   */
  public ImmutableSet<Label> getChangedStarlarkOptions() {
    return ImmutableSet.<Label>builder()
        .addAll(starlarkFirst.keySet())
        .addAll(starlarkSecond.keySet())
        .addAll(extraStarlarkOptionsFirst)
        .addAll(extraStarlarkOptionsSecond.keySet())
        .build();
  }

  @VisibleForTesting
  Map<Label, Object> getStarlarkFirstForTesting() {
    return starlarkFirst;
  }

  @VisibleForTesting
  Map<Label, Object> getStarlarkSecondForTesting() {
    return starlarkSecond;
  }

  @VisibleForTesting
  List<Label> getExtraStarlarkOptionsFirstForTesting() {
    return extraStarlarkOptionsFirst;
  }

  @VisibleForTesting
  Map<Label, Object> getExtraStarlarkOptionsSecondForTesting() {
    return extraStarlarkOptionsSecond;
  }

  /**
   * Note: it's not enough for first and second to be empty, with trimming, they must also contain
   * the same options classes.
   */
  boolean areSame() {
    return first.isEmpty()
        && second.isEmpty()
        && extraSecondFragments.isEmpty()
        && extraFirstFragments.isEmpty()
        && differingOptions.isEmpty()
        && starlarkFirst.isEmpty()
        && starlarkSecond.isEmpty()
        && extraStarlarkOptionsFirst.isEmpty()
        && extraStarlarkOptionsSecond.isEmpty();
  }

  public String prettyPrint() {
    StringBuilder toReturn = new StringBuilder();
    for (String diff : getPrettyPrintList()) {
      toReturn.append(diff).append(System.lineSeparator());
    }
    return toReturn.toString();
  }

  public List<String> getPrettyPrintList() {
    List<String> toReturn = new ArrayList<>();
    first.forEach(
        (option, value) ->
            toReturn.add(option.getOptionName() + ":" + value + " -> " + second.get(option)));
    starlarkFirst.forEach(
        (option, value) -> toReturn.add(option + ":" + value + starlarkSecond.get(option)));
    return toReturn;
  }
}
