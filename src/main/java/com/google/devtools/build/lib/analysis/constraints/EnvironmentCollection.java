// Copyright 2015 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis.constraints;

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Interner;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.EnvironmentLabels;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Contains a set of {@link Environment} labels and their associated groups. */
@Immutable
public final class EnvironmentCollection {

  /** An empty {@link EnvironmentCollection}. */
  @SerializationConstant
  static final EnvironmentCollection EMPTY = new EnvironmentCollection(ImmutableListMultimap.of());

  private static final Interner<EnvironmentCollection> interner = BlazeInterners.newWeakInterner();

  private final ImmutableListMultimap<EnvironmentLabels, Label> map;

  private EnvironmentCollection(ImmutableListMultimap<EnvironmentLabels, Label> map) {
    this.map = map;
  }

  /** Stores an environment's build label along with the group it belongs to. */
  record EnvironmentWithGroup(Label environment, EnvironmentLabels group) {
    EnvironmentWithGroup {
      requireNonNull(environment, "environment");
      requireNonNull(group, "group");
    }

    static EnvironmentWithGroup create(Label environment, EnvironmentLabels group) {
      return new EnvironmentWithGroup(environment, group);
    }
  }

  /**
   * Returns the build labels of each environment in this collection, ordered by their insertion
   * order in {@link Builder}.
   */
  public ImmutableCollection<Label> getEnvironments() {
    return map.values();
  }

  /**
   * Returns the environments in this collection that belong to the given group, ordered by their
   * insertion order in {@link Builder}. If no environments belong to the given group, returns an
   * empty collection.
   */
  ImmutableList<Label> getEnvironments(EnvironmentLabels group) {
    return map.get(group);
  }

  /**
   * Returns the set of groups the environments in this collection belong to, ordered by their
   * insertion order in {@link Builder}
   */
  ImmutableSet<EnvironmentLabels> getGroups() {
    return map.keySet();
  }

  /**
   * Returns the build labels of each environment in this collection paired with the group each
   * environment belongs to, ordered by their insertion order in {@link Builder}.
   */
  ImmutableSet<EnvironmentWithGroup> getGroupedEnvironments() {
    var builder = ImmutableSet.<EnvironmentWithGroup>builderWithExpectedSize(map.asMap().size());
    map.forEach((group, env) -> builder.add(EnvironmentWithGroup.create(env, group)));
    return builder.build();
  }

  boolean isEmpty() {
    return map.isEmpty();
  }

  @Override
  public int hashCode() {
    return 31 * map.hashCode() + map.keySet().asList().hashCode(); // Consider order of keys.
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof EnvironmentCollection that)) {
      return false;
    }
    // ImmutableListMultimap equality considers the order of each value list but not the order of
    // keys. Additionally check equality of the keys as a list to reflect ordering.
    return map.equals(that.map) && map.keySet().asList().equals(that.map.keySet().asList());
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("size", map.size())
        .add("hashCode", map.hashCode())
        .add("map", map)
        .toString();
  }

  /** Builder for {@link EnvironmentCollection}. */
  public static class Builder {
    // ImmutableListMultimap.Builder allows duplicate values, which we don't want.
    private final Set<Label> addedLabels = new HashSet<>();
    private final ImmutableListMultimap.Builder<EnvironmentLabels, Label> mapBuilder =
        ImmutableListMultimap.builder();

    /** Inserts the given environment / owning group pair. */
    @CanIgnoreReturnValue
    public Builder put(EnvironmentLabels group, Label environment) {
      if (addedLabels.add(environment)) {
        mapBuilder.put(group, environment);
      }
      return this;
    }

    /** Inserts the given set of environments, all belonging to the specified group. */
    @CanIgnoreReturnValue
    public Builder putAll(EnvironmentLabels group, Iterable<Label> environments) {
      for (Label env : environments) {
        if (addedLabels.add(env)) {
          mapBuilder.put(group, env);
        }
      }
      return this;
    }

    /** Inserts the contents of another {@link EnvironmentCollection} into this one. */
    @CanIgnoreReturnValue
    public Builder putAll(EnvironmentCollection other) {
      for (Map.Entry<EnvironmentLabels, Label> entry : other.map.entries()) {
        if (addedLabels.add(entry.getValue())) {
          mapBuilder.put(entry);
        }
      }
      return this;
    }

    public EnvironmentCollection build() {
      var map = mapBuilder.build();
      return map.isEmpty() ? EMPTY : interner.intern(new EnvironmentCollection(map));
    }
  }
}
