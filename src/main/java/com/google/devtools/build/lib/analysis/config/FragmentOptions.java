// Copyright 2014 The Bazel Authors. All rights reserved.
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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.Options;
import com.google.devtools.common.options.OptionsBase;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Command-line build options for a Blaze module. */
public abstract class FragmentOptions extends OptionsBase implements Cloneable {

  @Override
  public FragmentOptions clone() {
    try {
      return (FragmentOptions) super.clone();
    } catch (CloneNotSupportedException e) {
      // This can't happen.
      throw new IllegalStateException(e);
    }
  }

  /**
   * Creates a new instance of this {@code FragmentOptions} with all flags set to their default
   * values.
   */
  public FragmentOptions getDefault() {
    return Options.getDefaults(getClass());
  }

  /**
   * Returns an instance of {@code FragmentOptions} with all flags adjusted to be suitable for
   * forming configurations.
   *
   * <p>If this instance is already suitable, it will be returned without creating a new instance.
   *
   * <p>Motivation: Sometimes a fragment's physical option values, as set by the options parser, do
   * not correspond to their logical interpretation. For example, an option may need custom code to
   * determine its logical default value at runtime, but it's limited to a single hard-coded
   * physical default value in the {@link Option#defaultValue} annotation field. If two instances of
   * the fragment have the same logical value but different physical values, a redundant
   * configuration can be created, which results in an action conflict (particularly for unshareable
   * actions; see #7808).
   *
   * <p>To solve this, we can distinguish between "normalized" and "non-normalized" instances of a
   * fragment type, and preserve the invariant that configured targets only ever see normalized
   * instances. This requires that 1) the top-level configuration is normalized, and 2) all
   * transitions preserve normalization. Step 1) is ensured by {@link BuildOptions} calling this
   * method. Step 2) is the responsibility of each transition implementation.
   */
  public FragmentOptions getNormalized() {
    return this;
  }

  /**
   * Helper method for subclasses to normalize set valued options. In addition to removing
   * duplicates, it picks a deterministic ordering. The fact that the deterministic ordering is
   * based on sorting is an accident and should NOT be relied upon.
   */
  protected static ImmutableList<String> dedupAndSort(@Nullable List<String> values) {
    if (values == null || values.isEmpty()) {
      return ImmutableList.of();
    }

    ImmutableList<String> result =
        values.stream()
            // Use the natural String ordering.
            .sorted()
            .distinct()
            .collect(toImmutableList());

    // If the value is already deduped and sorted return the exact same instance we got.
    return result.equals(values) ? ImmutableList.copyOf(values) : result;
  }

  /**
   * Helper method for subclasses to normalize list of map entries by keeping only the last entry
   * for each key. The order of the entries is preserved.
   */
  protected static List<Map.Entry<String, String>> normalizeEntries(
      List<Map.Entry<String, String>> entries) {
    LinkedHashMap<String, String> normalizedEntries = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : entries) {
      normalizedEntries.put(entry.getKey(), entry.getValue());
    }
    // If we made no changes, return the same instance we got to reduce churn.
    if (normalizedEntries.size() == entries.size()) {
      return entries;
    }
    return normalizedEntries.entrySet().stream()
        .map(AbstractMap.SimpleEntry::new)
        .collect(toImmutableList());
  }

  /** Tracks limitations on referring to an option in a {@code config_setting}. */
  // TODO(bazel-team): There will likely also be a need to customize whether or not an option is
  // visible to users for setting on the command line (or perhaps even in a test of a Starlark
  // rule). This class may be a good place to add this functionality.
  public static final class SelectRestriction {

    private final boolean visibleWithinToolsPackage;

    @Nullable private final String errorMessage;

    public SelectRestriction(boolean visibleWithinToolsPackage, @Nullable String errorMessage) {
      this.visibleWithinToolsPackage = visibleWithinToolsPackage;
      this.errorMessage = errorMessage;
    }

    /**
     * Whether the option can still be seen by {@code config_setting}s that are defined by packages
     * underneath the tools repository's "tools" package, e.g. {@code @bazel_tools//tools/...}.
     */
    public boolean isVisibleWithinToolsPackage() {
      return visibleWithinToolsPackage;
    }

    /**
     * An additional explanation to append to the generic error message when a user attempts to use
     * this option. Should explain why this option is unavailable.
     *
     * <p>If null, no content will be appended to the generic error message.
     */
    @Nullable
    public String getErrorMessage() {
      return errorMessage;
    }
  }
}
