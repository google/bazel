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
package com.google.devtools.build.lib.skyframe;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.AbstractSkyKey;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

/** An immutable set of package name prefixes that should be ignored. */
public class IgnoredSubdirectoriesValue implements SkyValue {
  private final IgnoredSubdirectories ignoredSubdirectories;

  @SerializationConstant @VisibleForSerialization
  public static final IgnoredSubdirectoriesValue EMPTY =
      new IgnoredSubdirectoriesValue(IgnoredSubdirectories.EMPTY);

  private IgnoredSubdirectoriesValue(IgnoredSubdirectories ignoredSubdirectories) {
    this.ignoredSubdirectories = ignoredSubdirectories;
  }

  public static IgnoredSubdirectoriesValue of(
      ImmutableSet<PathFragment> prefixes, ImmutableList<String> patterns) {
    return prefixes.isEmpty() && patterns.isEmpty()
        ? EMPTY
        : new IgnoredSubdirectoriesValue(IgnoredSubdirectories.of(prefixes, patterns));
  }

  public static IgnoredSubdirectoriesValue of(IgnoredSubdirectories ignoredSubdirectories) {
    return ignoredSubdirectories.isEmpty()
        ? EMPTY
        : new IgnoredSubdirectoriesValue(ignoredSubdirectories);
  }

  /** Creates a key from the main repository. */
  public static SkyKey key() {
    return Key.create(RepositoryName.MAIN);
  }

  /** Creates a key from the given repository name. */
  public static SkyKey key(RepositoryName repository) {
    return Key.create(repository);
  }

  public IgnoredSubdirectories asIgnoredSubdirectories() {
    return ignoredSubdirectories;
  }

  @Override
  public int hashCode() {
    return ignoredSubdirectories.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof IgnoredSubdirectoriesValue other) {
      return this.ignoredSubdirectories.equals(other.ignoredSubdirectories);
    }
    return false;
  }

  @Override
  public String toString() {
    return ignoredSubdirectories.toString();
  }

  @VisibleForSerialization
  @AutoCodec
  static class Key extends AbstractSkyKey<RepositoryName> {
    private static final SkyKeyInterner<Key> interner = SkyKey.newInterner();

    private Key(RepositoryName arg) {
      super(arg);
    }

    static Key create(RepositoryName arg) {
      return interner.intern(new Key(arg));
    }

    @VisibleForSerialization
    @AutoCodec.Interner
    static Key intern(Key key) {
      return interner.intern(key);
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.IGNORED_SUBDIRECTORIES;
    }

    @Override
    public SkyKeyInterner<Key> getSkyKeyInterner() {
      return interner;
    }
  }

  /** Exception thrown when an ignore path is wrong for some reason. */
  public static final class InvalidIgnorePathException extends Exception {
    public InvalidIgnorePathException(String path, String message) {
      super("Invalid path in " + path + ": " + message);
    }
  }
}
