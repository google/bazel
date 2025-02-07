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
package com.google.devtools.build.lib.rules.python;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.SequencedSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Various utility methods for Python support. */
public final class PythonUtils {
  public static final PathFragment INIT_PY = PathFragment.create("__init__.py");
  public static final PathFragment INIT_PYC = PathFragment.create("__init__.pyc");
  public static final PathFragment PYCACHE = PathFragment.create("__pycache__");

  private static final FileType REQUIRES_INIT_PY = FileType.of(".py", ".so", ".pyc");

  /**
   * Used to get the set of empty __init__.py files to be added to a given set of files to allow the
   * Python runtime to import subdirectories potentially containing Python code to be imported as
   * packages. Ideally this feature goes away with --incompatible_default_to_explicit_init_py as the
   * long term default behavior.
   */
  public static class GetInitPyFiles implements Runfiles.EmptyFilesSupplier {
    private final Predicate<PathFragment> isPackageInit;
    private final UUID guid;

    /**
     * The Predicate isPackageInit's .test(source) should be true when a given source is known to be
     * a valid __init__.py file equivalent, meaning no empty __init__.py file need be created.
     * Useful for custom Python runtimes that may have non-standard Python package import logic.
     *
     * @param guid a UUID that uniquely identifies the particular isPackageInit predicate for the
     *     purpose of fingerprinting this {@link Runfiles.EmptyFilesSupplier} instance
     */
    public GetInitPyFiles(Predicate<PathFragment> isPackageInit, UUID guid) {
      this.isPackageInit = isPackageInit;
      this.guid = guid;
    }

    @Override
    public Set<PathFragment> getExtraPaths(Set<PathFragment> manifestPaths) {
      return getInitPyFiles(manifestPaths);
    }

    @Override
    public void fingerprint(Fingerprint fp) {
      fp.addUUID(guid);
    }

    /**
     * Returns the set of empty __init__.py(c) files to be added to a given set of files to allow
     * the Python runtime to find the <code>.py</code> and <code>.so</code> files present in the
     * tree.
     */
    private ImmutableSet<PathFragment> getInitPyFiles(Set<PathFragment> manifestFiles) {
      SequencedSet<PathFragment> result = new LinkedHashSet<>();
      // A set of directories that already have package init files.
      Set<PathFragment> hasPackageInitDirs = new HashSet<>(); // For b/142135992.

      // Find directories containing Python package init files based on a caller supplied test in
      // order to support non-standard Python package init naming schemes.
      // This loop is done prior to the one below as we assume no order in the set and that we may
      // find inits in parent directories listed after subdirectories which the nested loop below
      // would need to know of.
      for (PathFragment source : manifestFiles) {
        if (isPackageInit.test(source)) {
          hasPackageInitDirs.add(source.getParentDirectory());
        }
      }

      for (PathFragment source : manifestFiles) {
        // If we have a python or .so file at this level...
        if (REQUIRES_INIT_PY.matches(source)) {
          // ...then record that we need an __init__.py in this and all parents directories...
          while (source.isMultiSegment()) {
            source = source.getParentDirectory();
            // ...unless it's a Python .pyc cache or we already have __init__ there.
            if (!source.endsWith(PYCACHE) && !hasPackageInitDirs.contains(source)) {
              PathFragment initpy = source.getRelative(INIT_PY);
              PathFragment initpyc = source.getRelative(INIT_PYC);

              if (!manifestFiles.contains(initpy) && !manifestFiles.contains(initpyc)) {
                result.add(initpy);
              }
            }
          }
        }
      }

      return ImmutableSet.copyOf(result);
    }
  } // class GetInitPyFiles

  private PythonUtils() {
    // This is a utility class, not to be instantiated.
  }
}
