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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.collect.Comparators.lexicographical;
import static com.google.common.primitives.Booleans.falseFirst;
import static com.google.common.primitives.Booleans.trueFirst;
import static java.util.Comparator.comparing;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import java.util.Comparator;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Represents a version in the Bazel module system. The version format we support is {@code
 * RELEASE[-PRERELEASE][+BUILD]}, where {@code RELEASE}, {@code PRERELEASE}, and {@code BUILD} are
 * each a sequence of "identifiers" (defined as a non-empty sequence of ASCII alphanumerical
 * characters and hyphens) separated by dots. The {@code RELEASE} part may not contain hyphens.
 *
 * <p>Otherwise, this format is identical to SemVer, especially in terms of the <a
 * href="https://semver.org/#spec-item-11">comparison algorithm</a>. In other words, this format is
 * intentionally looser than SemVer; in particular:
 *
 * <ul>
 *   <li>the "release" part isn't limited to exactly 3 segments (major, minor, patch), but can be
 *       fewer or more;
 *   <li>each segment in the "release" part can be identifiers instead of just numbers (so letters
 *       are also allowed -- although hyphens are not).
 * </ul>
 *
 * <p>Any valid SemVer version is a valid Bazel module version. Additionally, two SemVer versions
 * {@code a} and {@code b} compare {@code a < b} iff the same holds when they're compared as Bazel
 * module versions.
 *
 * <p>Versions with a "build" part are generally accepted as input, but they're treated as if the
 * "build" part is completely absent. That is, when Bazel outputs version strings, it never outputs
 * the "build" part (in fact, it doesn't even store it); similarly, when Bazel accesses registries
 * to request versions, the "build" part is never included. This gives us the nice property of
 * "consistent with equals" natural ordering (see {@link Comparable}); that is, {@code
 * a.compareTo(b) == 0} iff {@code a.equals(b)}.
 *
 * <p>The special "empty string" version can also be used, and compares higher than everything else.
 * It signifies that there is a {@link NonRegistryOverride} for a module.
 */
@AutoCodec
public record Version(
    ImmutableList<Identifier> release, ImmutableList<Identifier> prerelease, String normalized)
    implements Comparable<Version> {

  // We don't care about the "build" part at all so don't capture it.
  private static final Pattern PATTERN =
      Pattern.compile(
          "(?<release>[a-zA-Z0-9.]+)(?:-(?<prerelease>[a-zA-Z0-9.-]+))?(?:\\+[a-zA-Z0-9.-]+)?");

  private static final Splitter DOT_SPLITTER = Splitter.on('.');

  /**
   * Represents the special "empty string" version, which compares higher than everything else and
   * signifies that there is a {@link NonRegistryOverride} for the module.
   */
  @SuppressWarnings("deprecation") // private usage of constructor
  public static final Version EMPTY = new Version(ImmutableList.of(), ImmutableList.of(), "");

  /**
   * @deprecated Use {@link Version#parse(String)} instead.
   */
  @Deprecated
  public Version {}

  /**
   * Represents an "identifier", a dot-separated segment in the version string. An identifier is
   * compared differently based on whether it's digits-only or not.
   */
  @AutoCodec
  record Identifier(boolean isDigitsOnly, long asNumber, String asString)
      implements Comparable<Identifier> {
    static Identifier from(String string) throws ParseException {
      if (Strings.isNullOrEmpty(string)) {
        throw new ParseException("identifier is empty");
      }
      if (string.chars().allMatch(Character::isDigit)) {
        try {
          return new Identifier(true, Long.parseUnsignedLong(string), string);
        } catch (NumberFormatException e) {
          throw new ParseException("numeric version segment is too large: " + string, e);
        }
      } else {
        return new Identifier(false, 0, string);
      }
    }

    private static final Comparator<Identifier> COMPARATOR =
        comparing(Identifier::isDigitsOnly, trueFirst())
            .thenComparing((a, b) -> Long.compareUnsigned(a.asNumber, b.asNumber))
            .thenComparing(Identifier::asString);

    @Override
    public int compareTo(Identifier o) {
      return Objects.compare(this, o, COMPARATOR);
    }
  }

  /** Returns the normalized version string (that is, with any "build" part stripped). */
  public String getNormalized() {
    return normalized;
  }

  /**
   * Whether this is just the "empty string" version, which signifies a non-registry override for
   * the module.
   */
  boolean isEmpty() {
    return getNormalized().isEmpty();
  }

  /**
   * Whether this is a prerelease version (i.e. the prerelease part of the version string is
   * non-empty). A prerelease version compares lower than the same version without the prerelease
   * part.
   */
  boolean isPrerelease() {
    return !prerelease.isEmpty();
  }

  /** Parses a version string into a {@link Version} object. */
  public static Version parse(String version) throws ParseException {
    if (version.isEmpty()) {
      return Version.EMPTY;
    }
    Matcher matcher = PATTERN.matcher(version);
    if (!matcher.matches()) {
      throw new ParseException("bad version (does not match regex): " + version);
    }
    String release = matcher.group("release");
    @Nullable String prerelease = matcher.group("prerelease");

    ImmutableList.Builder<Identifier> releaseSplit = new ImmutableList.Builder<>();
    for (String ident : DOT_SPLITTER.split(release)) {
      try {
        releaseSplit.add(Identifier.from(ident));
      } catch (ParseException e) {
        throw new ParseException("error parsing version: " + version, e);
      }
    }

    ImmutableList.Builder<Identifier> prereleaseSplit = new ImmutableList.Builder<>();
    if (!Strings.isNullOrEmpty(prerelease)) {
      for (String ident : DOT_SPLITTER.split(prerelease)) {
        try {
          prereleaseSplit.add(Identifier.from(ident));
        } catch (ParseException e) {
          throw new ParseException("error parsing version: " + version, e);
        }
      }
    }

    String normalized = Strings.isNullOrEmpty(prerelease) ? release : release + '-' + prerelease;
    @SuppressWarnings("deprecation") // private usage of constructor
    Version result = new Version(releaseSplit.build(), prereleaseSplit.build(), normalized);
    return result;
  }

  private static final Comparator<Version> COMPARATOR =
      comparing(Version::isEmpty, falseFirst())
          .thenComparing(Version::release, lexicographical(Identifier.COMPARATOR))
          .thenComparing(Version::isPrerelease, trueFirst())
          .thenComparing(Version::prerelease, lexicographical(Identifier.COMPARATOR));

  @Override
  public int compareTo(Version o) {
    return Objects.compare(this, o, COMPARATOR);
  }

  @Override
  public String toString() {
    return getNormalized();
  }

  @Override
  public boolean equals(Object o) {
    return this == o || (o instanceof Version v && v.getNormalized().equals(getNormalized()));
  }

  @Override
  public int hashCode() {
    return Objects.hash("version", getNormalized().hashCode());
  }

  /** An exception encountered while trying to {@link Version#parse parse} a version. */
  public static class ParseException extends Exception {
    public ParseException(String message) {
      super(message);
    }

    public ParseException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
