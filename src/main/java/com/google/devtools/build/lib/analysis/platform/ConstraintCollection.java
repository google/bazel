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

package com.google.devtools.build.lib.analysis.platform;

import static com.google.common.collect.ImmutableListMultimap.flatteningToImmutableListMultimap;
import static com.google.common.collect.ImmutableListMultimap.toImmutableListMultimap;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.stream.Collectors.joining;

import com.google.auto.value.AutoValue;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Streams;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.starlarkbuildapi.platform.ConstraintCollectionApi;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkSemantics;

/** A collection of constraint values. */
@Immutable
@AutoValue
public abstract class ConstraintCollection
    implements ConstraintCollectionApi<ConstraintSettingInfo, ConstraintValueInfo> {

  /** A builder class to help create instances of {@link ConstraintCollection}. */
  public static final class Builder {
    @Nullable private ConstraintCollection parent;
    private ImmutableList.Builder<ConstraintValueInfo> constraintValues =
        new ImmutableList.Builder<>();

    private Builder() {}

    /** Sets the parent {@link ConstraintCollection} of this instance. */
    @CanIgnoreReturnValue
    public Builder parent(@Nullable ConstraintCollection parent) {
      this.parent = parent;
      return this;
    }

    /** Adds the given constraints to the current collection. */
    public Builder addConstraints(ConstraintValueInfo... constraints) {
      return addConstraints(ImmutableList.copyOf(constraints));
    }

    /** Adds the given constraints to the current collection. */
    @CanIgnoreReturnValue
    public Builder addConstraints(Iterable<ConstraintValueInfo> constraints) {
      constraintValues.addAll(constraints);
      return this;
    }

    /** Returns the completed {@link ConstraintCollection} instance. */
    public ConstraintCollection build() throws DuplicateConstraintException {
      ImmutableList<ConstraintValueInfo> constraintValues = this.constraintValues.build();
      validateConstraints(constraintValues);
      return new AutoValue_ConstraintCollection(
          this.parent,
          constraintValues.stream()
              .collect(toImmutableMap(ConstraintValueInfo::constraint, Function.identity())));
    }
  }

  /** Returns a new {@link Builder} suitable for creating {@link ConstraintCollection} instances. */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public boolean isImmutable() {
    return true; // immutable and Starlark-hashable
  }

  /**
   * Returns the parent {@link ConstraintCollection} for this instance, or {@code null} if none
   * exists.
   */
  @Nullable
  abstract ConstraintCollection parent();

  /** Returns the constraints supplied by this collection. */
  abstract ImmutableMap<ConstraintSettingInfo, ConstraintValueInfo> constraints();

  /**
   * Returns the transitive closure of {@link ConstraintSettingInfo}s this {@link ConstraintCollection} has constraints on.
   */
  public final ImmutableSet<ConstraintSettingInfo> transitiveConstraintSettings() {
    var constraints = ImmutableSet.<ConstraintSettingInfo>builder();
    if (parent() != null) {
      constraints.addAll(parent().transitiveConstraintSettings());
    }
    return constraints.build();
  }

  /**
   * Returns {@code true} if this {@link ConstraintCollection} contains every {@link
   * ConstraintValueInfo} in {@code expected}, or if the expected constraint value is the default
   * for its setting.
   */
  public boolean containsAll(Iterable<ConstraintValueInfo> expected) {
    return findMissing(expected).isEmpty();
  }

  /**
   * Returns the set of {@link ConstraintValueInfo constraints} from {@code expected} that are not
   * present in this {@link ConstraintCollection}, either directly, or by being the default for
   * their {@link ConstraintSettingInfo}.
   */
  public ImmutableList<ConstraintValueInfo> findMissing(Iterable<ConstraintValueInfo> expected) {
    ImmutableList.Builder<ConstraintValueInfo> missing = new ImmutableList.Builder<>();
    // For every constraint check if it is (1) non-null and (2) set correctly.
    for (ConstraintValueInfo constraint : expected) {
      ConstraintSettingInfo setting = constraint.constraint();
      ConstraintValueInfo targetValue = get(setting);
      if (targetValue == null || !constraint.equals(targetValue)) {
        missing.add(constraint);
      }
    }
    return missing.build();
  }

  /**
   * Returns the set of {@link ConstraintSettingInfo settings} where this {@link
   * ConstraintCollection} and {@code other} have different {@link ConstraintValueInfo values}.
   */
  public ImmutableSet<ConstraintSettingInfo> diff(ConstraintCollection other) {
    ImmutableSet<ConstraintSettingInfo> constraintsToCheck =
        new ImmutableSet.Builder<ConstraintSettingInfo>()
            .addAll(this.constraintSettings())
            .addAll(other.constraintSettings())
            .build();
    ImmutableSet.Builder<ConstraintSettingInfo> mismatchSettings = new ImmutableSet.Builder<>();
    for (ConstraintSettingInfo constraintSetting : constraintsToCheck) {
      ConstraintValueInfo thisConstraint = this.get(constraintSetting);
      ConstraintValueInfo otherConstraint = other.get(constraintSetting);

      if (thisConstraint != null && !thisConstraint.equals(otherConstraint)) {
        mismatchSettings.add(constraintSetting);
      }
    }

    return mismatchSettings.build();
  }

  private static ConstraintSettingInfo convertKey(Object key) throws EvalException {
    if (!(key instanceof ConstraintSettingInfo)) {
      throw Starlark.errorf(
          "Constraint names must be platform_common.ConstraintSettingInfo, got %s instead",
          Starlark.type(key));
    }

    return (ConstraintSettingInfo) key;
  }

  @Override
  public boolean has(ConstraintSettingInfo constraint) {
    // First, check locally.
    if (constraints().containsKey(constraint)) {
      return true;
    }

    // Then, check the parent.
    if (parent() != null) {
      return parent().has(constraint);
    }

    return constraint.hasDefaultConstraintValue();
  }

  public boolean hasWithoutDefault(ConstraintSettingInfo constraint) {
    // First, check locally.
    if (constraints().containsKey(constraint)) {
      return true;
    }

    // Then, check the parent, directly to ignore defaults.
    if (parent() != null) {
      return parent().hasWithoutDefault(constraint);
    }

    return false;
  }

  @Override
  public boolean hasConstraintValue(ConstraintValueInfo constraintValue) {
    ConstraintValueInfo discoveredConstraintValue = this.get(constraintValue.constraint());
    return Objects.equals(constraintValue, discoveredConstraintValue);
  }

  /**
   * Returns the {@link ConstraintValueInfo} for the given {@link ConstraintSettingInfo}, or {@code
   * null} if none exists.
   */
  @Nullable
  @Override
  public ConstraintValueInfo get(ConstraintSettingInfo constraint) {
    // First, check locally.
    if (constraints().containsKey(constraint)) {
      return constraints().get(constraint);
    }

    // Then, check the parent, directly to ignore defaults.
    if (parent() != null) {
      return parent().get(constraint);
    }

    // Finally, Since this constraint isn't set, fall back to the default.
    return constraint.defaultConstraintValue();
  }

  @Override
  public Sequence<ConstraintSettingInfo> constraintSettings() {
    return StarlarkList.immutableCopyOf(constraints().keySet());
  }

  @Override
  public Object getIndex(StarlarkSemantics semantics, Object key) throws EvalException {
    ConstraintSettingInfo constraintSettingInfo = convertKey(key);
    Object result = get(constraintSettingInfo);
    if (result == null) {
      return Starlark.NONE;
    }
    return result;
  }

  @Override
  public boolean containsKey(StarlarkSemantics semantics, Object key) throws EvalException {
    return has(convertKey(key));
  }

  // It's easier to use the Starlark repr as a string form, not what AutoValue produces.
  @Override
  public final String toString() {
    return Starlark.repr(this);
  }

  @Override
  public void repr(Printer printer) {
    printer.append("<");
    if (parent() != null) {
      printer.append("parent: ");
      parent().repr(printer);
      printer.append(", ");
    }
    printer.append("[");
    printer.append(
        constraints().values().stream()
            .map(ConstraintValueInfo::label)
            .map(Functions.toStringFunction())
            .collect(joining(", ")));
    printer.append("]");
    printer.append(">");
  }

  /**
   * Adds information to the {@link Fingerprint} to uniquely identify this collection of
   * constraints.
   */
  public void addToFingerprint(Fingerprint fp) {
    // Encode whether there is a parent.
    fp.addBoolean(parent() != null);
    // Add the parent.
    if (parent() != null) {
      parent().addToFingerprint(fp);
    }
    // Add the actual constraints.
    fp.addInt(constraints().size());
    constraints().values().forEach(constraintValue -> constraintValue.addTo(fp));
  }

  public static void validateConstraints(Iterable<ConstraintValueInfo> constraintValues)
      throws DuplicateConstraintException {
    // Collect the constraints by the settings.
    ImmutableListMultimap<ConstraintSettingInfo, ConstraintValueInfo> constraints =
        Streams.stream(constraintValues)
            .collect(
                toImmutableListMultimap(ConstraintValueInfo::constraint, Functions.identity()));

    // Find settings with duplicate values.
    ImmutableListMultimap<ConstraintSettingInfo, ConstraintValueInfo> duplicates =
        constraints.asMap().entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .collect(
                flatteningToImmutableListMultimap(Map.Entry::getKey, e -> e.getValue().stream()));

    if (!duplicates.isEmpty()) {
      throw new DuplicateConstraintException(duplicates);
    }
  }

  /**
   * Exception class used when more than one {@link ConstraintValueInfo} for the same {@link
   * ConstraintSettingInfo} is added to a {@link Builder}.
   */
  public static class DuplicateConstraintException extends Exception {
    private final ImmutableListMultimap<ConstraintSettingInfo, ConstraintValueInfo>
        duplicateConstraints;

    DuplicateConstraintException(
        ListMultimap<ConstraintSettingInfo, ConstraintValueInfo> duplicateConstraints) {
      super(formatError(duplicateConstraints));
      this.duplicateConstraints = ImmutableListMultimap.copyOf(duplicateConstraints);
    }

    public ImmutableListMultimap<ConstraintSettingInfo, ConstraintValueInfo>
        duplicateConstraints() {
      return duplicateConstraints;
    }

    public static String formatError(
        ListMultimap<ConstraintSettingInfo, ConstraintValueInfo> duplicateConstraints) {
      return String.format(
          "Duplicate constraint values detected: %s",
          duplicateConstraints.asMap().entrySet().stream()
              .map(DuplicateConstraintException::describeSingleDuplicateConstraintSetting)
              .collect(joining(", ")));
    }

    private static String describeSingleDuplicateConstraintSetting(
        Map.Entry<ConstraintSettingInfo, Collection<ConstraintValueInfo>> duplicate) {
      return String.format(
          "constraint_setting %s has [%s]",
          duplicate.getKey().label(),
          duplicate.getValue().stream()
              .map(ConstraintValueInfo::label)
              .map(Label::toString)
              .collect(joining(", ")));
    }
  }
}
