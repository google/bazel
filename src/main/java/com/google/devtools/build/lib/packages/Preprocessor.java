// Copyright 2014 Google Inc. All rights reserved.
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
package com.google.devtools.build.lib.packages;

import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.ParserInputSource;
import com.google.devtools.build.lib.vfs.Path;

import java.io.IOException;
import java.util.Set;

import javax.annotation.Nullable;

/** A Preprocessor is an interface to implement generic text-based preprocessing of BUILD files. */
public interface Preprocessor {
  /** Factory for {@link Preprocessor} instances. */
  interface Factory {
    /** Supplier for {@link Factory} instances. */
    interface Supplier {
      /**
       * Returns a Preprocessor factory to use for getting Preprocessor instances.
       *
       * <p>The CachingPackageLocator is provided so the constructed preprocessors can look up
       * other BUILD files.
       */
      Factory getFactory(CachingPackageLocator loc);

      /** Supplier that always returns {@code NullFactory.INSTANCE}. */
      static class NullSupplier implements Supplier {

        public static final NullSupplier INSTANCE = new NullSupplier();

        private NullSupplier() {
        }

        @Override
        public Factory getFactory(CachingPackageLocator loc) {
          return NullFactory.INSTANCE;
        }
      }
    }

    /**
     * Returns whether this {@link Factory} is still suitable for providing {@link Preprocessor}s.
     * If not, all previous preprocessing results should be assumed to be invalid and a new
     * {@link Factory} should be created via {@link Supplier#getFactory}.
     */
    boolean isStillValid();

    /**
     * Returns a Preprocessor instance capable of preprocessing a BUILD file independently (e.g. it
     * ought to be fine to call {@link #getPreprocessor} for each BUILD file).
     */
    @Nullable
    Preprocessor getPreprocessor();

    /** Factory that always returns {@code null} {@link Preprocessor}s. */
    static class NullFactory implements Factory {
      public static final NullFactory INSTANCE = new NullFactory();

      private NullFactory() {
      }

      @Override
      public boolean isStillValid() {
        return true;
      }

      @Override
      public Preprocessor getPreprocessor() {
        return null;
      }
    }
  }

  /**
   * A (result, success) tuple indicating the outcome of preprocessing.
   */
  static class Result {
    public final ParserInputSource result;
    public final boolean preprocessed;
    public final boolean containsTransientErrors;

    public Result(ParserInputSource result,
        boolean preprocessed, boolean containsTransientErrors) {
      this.result = result;
      this.preprocessed = preprocessed;
      this.containsTransientErrors = containsTransientErrors;
    }

    public static Result success(ParserInputSource result, boolean preprocessed) {
      return new Result(result, preprocessed, false);
    }

    // This error is used only if the BUILD file is not in Python syntax.
    public static Result preprocessingError(Path buildFile) {
      return new Result(ParserInputSource.create("", buildFile), true, false);
    }

    // Signals some other preprocessing error.
    public static Result transientError(Path buildFile) {
      return new Result(ParserInputSource.create("", buildFile), false, true);
    }
  }

  /**
   * Returns a Result resulting from applying Python preprocessing to the contents of "in". If
   * errors happen, they must be reported both as an event on eventHandler and in the function
   * return value.
   *
   * @param in the BUILD file to be preprocessed.
   * @param packageName the BUILD file's package.
   * @param globCache
   * @param eventHandler a eventHandler on which to report warnings/errors.
   * @param globalEnv the GLOBALS Python environment.
   * @param ruleNames the set of names of all rules in the build language.
   * @throws IOException if there was an I/O problem during preprocessing.
   * @return a pair of the ParserInputSource and a map of subincludes seen during the evaluation
   */
  Result preprocess(
      ParserInputSource in,
      String packageName,
      GlobCache globCache,
      EventHandler eventHandler,
      Environment globalEnv,
      Set<String> ruleNames)
    throws IOException, InterruptedException;
}
