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

package com.google.devtools.build.lib.cmdline;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import javax.annotation.Nullable;
import net.starlark.java.spelling.SpellChecker;

/**
 * Stores the mapping from apparent repo name to canonical repo name, from the viewpoint of an
 * "owner repo".
 *
 * <p>For repositories from the WORKSPACE file, if the requested repo doesn't exist in the mapping,
 * we fallback to the requested name. For repositories from Bzlmod, we return null to let the caller
 * decide what to do.
 *
 * <p>This class must not implement {@link net.starlark.java.eval.StarlarkValue} since instances of
 * this class are used as markers by {@link
 * com.google.devtools.build.lib.analysis.starlark.StarlarkCustomCommandLine}.
 */
public class RepositoryMapping {
  /* A repo mapping that always falls back to using the apparent name as the canonical name. */
  public static final RepositoryMapping ALWAYS_FALLBACK = createAllowingFallback(ImmutableMap.of());

  private final ImmutableMap<String, RepositoryName> entries;
  @Nullable private final RepositoryName ownerRepo;

  private RepositoryMapping(
      Map<String, RepositoryName> entries, @Nullable RepositoryName ownerRepo) {
    this.entries = ImmutableMap.copyOf(entries);
    this.ownerRepo = ownerRepo;
  }

  /** Returns all the entries in this repo mapping. */
  public final ImmutableMap<String, RepositoryName> entries() {
    return entries;
  }

  /**
   * The owner repo of this repository mapping. It is for providing useful debug information when
   * repository mapping fails due to enforcing strict dependency, therefore it's only recorded when
   * we don't fall back to the requested repo name.
   */
  @Nullable
  public final RepositoryName ownerRepo() {
    return ownerRepo;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RepositoryMapping)) {
      return false;
    }
    RepositoryMapping that = (RepositoryMapping) o;
    return Objects.equal(entries, that.entries) && Objects.equal(ownerRepo, that.ownerRepo);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(entries, ownerRepo);
  }

  @Override
  public String toString() {
    return String.format("RepositoryMapping{entries=%s, ownerRepo=%s}", entries, ownerRepo);
  }

  public static RepositoryMapping create(
      ImmutableMap<String, RepositoryName> entries, RepositoryName ownerRepo) {
    return new RepositoryMapping(
        Preconditions.checkNotNull(entries), Preconditions.checkNotNull(ownerRepo));
  }

  public static RepositoryMapping createAllowingFallback(
      ImmutableMap<String, RepositoryName> entries) {
    return new RepositoryMapping(Preconditions.checkNotNull(entries), null);
  }

  /**
   * Create a new {@link RepositoryMapping} instance based on existing repo mappings and given
   * additional mappings. If there are conflicts, existing mappings will take precedence.
   */
  public RepositoryMapping withAdditionalMappings(
      ImmutableMap<String, RepositoryName> additionalMappings) {
    return new RepositoryMapping(
        ImmutableMap.<String, RepositoryName>builderWithExpectedSize(
                entries().size() + additionalMappings.size())
            .putAll(additionalMappings)
            .putAll(entries())
            .buildKeepingLast(),
        ownerRepo());
  }

  /**
   * Create a new {@link RepositoryMapping} instance based on existing repo mappings and given
   * additional mappings. If there are conflicts, existing mappings will take precedence. The owner
   * repo of the given additional mappings is ignored.
   */
  public RepositoryMapping withAdditionalMappings(RepositoryMapping additionalMappings) {
    return withAdditionalMappings(
        (additionalMappings == null) ? ImmutableMap.of() : additionalMappings.entries());
  }

  /**
   * Returns the canonical repository name associated with the given apparent repo name. The
   * provided apparent repo name is assumed to be valid.
   */
  public RepositoryName get(String preMappingName) {
    RepositoryName canonicalRepoName = entries().get(preMappingName);
    if (canonicalRepoName != null) {
      return canonicalRepoName;
    }
    // If the owner repo is not present, that means we should fall back to the requested repo name.
    if (ownerRepo() == null) {
      return RepositoryName.createUnvalidated(preMappingName);
    } else {
      return RepositoryName.createUnvalidated(preMappingName)
          .toNonVisible(ownerRepo(), SpellChecker.didYouMean(preMappingName, entries().keySet()));
    }
  }

  /**
   * Whether the repo with this mapping is subject to strict deps; when strict deps is off, unknown
   * apparent names are silently treated as canonical names.
   */
  public boolean usesStrictDeps() {
    return ownerRepo() != null;
  }

  /**
   * Returns the first apparent name in this mapping that maps to the given canonical name, if any.
   */
  public Optional<String> getInverse(RepositoryName postMappingName) {
    return entries().entrySet().stream()
        .filter(e -> e.getValue().equals(postMappingName))
        .map(Entry::getKey)
        .findFirst();
  }

  /**
   * Creates a new {@link RepositoryMapping} instance that is the equivalent of composing this
   * {@link RepositoryMapping} with another one. That is, {@code a.composeWith(b).get(name) ===
   * b.get(a.get(name))} (treating {@code b} as allowing fallback).
   *
   * <p>Since we're treating the result of {@code a.get(name)} as an apparent repo name instead of a
   * canonical repo name, this only really makes sense when {@code a} does not use strict deps (i.e.
   * allows fallback).
   */
  public RepositoryMapping composeWith(RepositoryMapping other) {
    Preconditions.checkArgument(
        !usesStrictDeps(), "only an allow-fallback mapping can be composed with other mappings");
    HashMap<String, RepositoryName> entries = new HashMap<>(other.entries());
    for (Map.Entry<String, RepositoryName> entry : entries().entrySet()) {
      RepositoryName mappedName = other.get(entry.getValue().getName());
      entries.put(entry.getKey(), mappedName.isVisible() ? mappedName : entry.getValue());
    }
    return new RepositoryMapping(entries, null);
  }

  /**
   * Returns a new {@link RepositoryMapping} instance with identical contents, except that the
   * inverse mapping is cached, causing {@link #getInverse} to be much more efficient. This is
   * particularly important for the main repo mapping, as it's often used to generate display-form
   * labels ({@link Label#getDisplayForm}).
   */
  public RepositoryMapping withCachedInverseMap() {
    var inverse = Maps.<RepositoryName, String>newHashMapWithExpectedSize(entries.size());
    for (Map.Entry<String, RepositoryName> entry : entries.entrySet()) {
      inverse.putIfAbsent(entry.getValue(), entry.getKey());
    }
    var inverseCopy = ImmutableMap.copyOf(inverse);
    return new RepositoryMapping(entries, ownerRepo) {
      @Override
      public Optional<String> getInverse(RepositoryName postMappingName) {
        return Optional.ofNullable(inverseCopy.get(postMappingName));
      }
    };
  }
}
