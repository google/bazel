// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Utf8;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions.Utf8EnforcementMode;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.ParserInput;

/** Helper functions for Bazel's use of Starlark. */
public final class StarlarkUtil {

  public static final String INVALID_UTF_8_MESSAGE =
      "not a valid UTF-8 encoded file; this can lead to inconsistent behavior and"
          + " will be disallowed in a future version of Bazel";

  /**
   * Produces a {@link ParserInput} from the raw bytes of a file while optionally enforcing that the
   * contents are valid UTF-8.
   *
   * <p><b>Warnings and errors are reported to the {@link EventHandler}.</b>
   *
   * @throws InvalidUtf8Exception if the bytes are not valid UTF-8 and the enforcement mode is
   *     {@link Utf8EnforcementMode#ERROR}.
   */
  // This method is the only one that is supposed to use the deprecated ParserInput.fromLatin1
  // method.
  @SuppressWarnings("deprecation") // See https://github.com/bazelbuild/bazel/issues/374
  public static ParserInput createParserInput(
      byte[] bytes, String file, Utf8EnforcementMode utf8EnforcementMode, EventHandler reporter)
      throws InvalidUtf8Exception {
    switch (utf8EnforcementMode) {
      case OFF -> {}
      case WARNING -> {
        if (!Utf8.isWellFormed(bytes)) {
          reporter.handle(Event.warn(Location.fromFile(file), INVALID_UTF_8_MESSAGE));
        }
      }
      case ERROR -> {
        if (!Utf8.isWellFormed(bytes)) {
          reporter.handle(
              Event.error(
                  Location.fromFile(file),
                  String.format(
                      "%s. For a temporary workaround, see the --%s flag.",
                      INVALID_UTF_8_MESSAGE,
                      BuildLanguageOptions.INCOMPATIBLE_ENFORCE_STARLARK_UTF8)));
          throw new InvalidUtf8Exception(file + ": " + INVALID_UTF_8_MESSAGE);
        }
      }
    }
    return ParserInput.fromLatin1(bytes, file);
  }

  /** Exception thrown when a Starlark file is not valid UTF-8. */
  public static final class InvalidUtf8Exception extends Exception {
    public InvalidUtf8Exception(String message) {
      super(message);
    }
  }

  private StarlarkUtil() {}
}
