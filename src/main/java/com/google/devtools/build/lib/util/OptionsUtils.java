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

package com.google.devtools.build.lib.util;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.StandardSystemProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;
import com.google.devtools.common.options.ParsedOptionDescription;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** Blaze-specific option utilities. */
public final class OptionsUtils {

  /**
   * List of environment variables that are allowed to propagate to the BEP.
   *
   * <p>It is tempting (and difficult) to make this list customizable via a command-line flag.
   * However, if we allowed customization, we would not be able to trust that a given Bazel
   * version does the right thing because we'd be subject to the contents of the bazelrc or
   * the flags that a user passes.</p>
   */
  private final static ImmutableSet<String> allowedEnvVars = ImmutableSet.of(
      "BAZEL_SRCDIR",
      "BAZEL_TEST",
      "HOME",
      "LD_LIBRARY_PATH",
      "PATH",
      "PWD",
      "SHLVL",
      "TEST_BINARY",
      "TEST_SRCDIR",
      "TEST_TARGET",
      "TEST_TIMEOUT",
      "TEST_TMPDIR",
      "TEST_WORKSPACE",
      "TMPDIR",
      "TZ",
      "USER",
      "WORKSPACE_DIR"
  );

  /**
   * List of environment variable name prefixes that are allowed to propagate to the BEP.
   *
   * <p>This list must only contain prefixes for clients we control as otherwise we could be
   * allowing unknown variables by mistake.</p>
   *
   * <p>See {@link #allowedEnvVars} for more details.</p>
   */
  private final static ImmutableSet<String> allowedEnvVarPrefixes = ImmutableSet.of(
      "BESD_",
      "CI_SNOWCI_",
      "IJWB_"
  );

  /** List of commands that have sensitive residual arguments. */
  public final static ImmutableSet<String> sensitiveResidualCommands = ImmutableSet.of("run");

  /** Tag for the sensitivity of an option's value. */
  public enum OptionSensitivity {
    /** The option has a value that is not sensitive, or it has no value. */
    NONE,

    /** The option has a value of the form <tt>name=val</tt> and only <tt>val</tt> is sensitive. */
    PARTIAL,

    /** The option has a value and the whole value is sensitive. */
    FULL,
  }

  /** Predicate that returns true if the given string corresponds to an option that carries a
   * fully sensitive value. */
  private final static Predicate<String> isFullySensitiveOption =
      Pattern.compile("_arg([ =].*|)$").asPredicate();

  /** Predicate that returns true if the given string corresponds to an option that carries a
   * partially sensitive value. */
  private final static Predicate<String> isPartiallySensitiveOption =
      Pattern.compile("_(env|header)([ =].*|)$").asPredicate();

  /** Returns true if the given environment variable can be propagated to the BEP. */
  private static boolean isEnvVarAllowed(String name) {
    if (allowedEnvVars.contains(name)) {
      return true;
    }

    for (String prefix : allowedEnvVarPrefixes) {
      if (name.startsWith(prefix)) {
        return true;
      }
    }

    return false;
  }

  /** Determines the sensitivity of the given option name, where the provided string may include the
   * option's value as well. */
  public static OptionSensitivity getOptionSensitivity(String raw) {
    if (isFullySensitiveOption.test(raw)) {
      return OptionSensitivity.FULL;
    } else if (isPartiallySensitiveOption.test(raw)) {
      return OptionSensitivity.PARTIAL;
    } else {
      return OptionSensitivity.NONE;
    }
  }

  /** Determines if the given string contains an option name/value pair. */
  private static boolean containsOptionValue(String raw) {
    return raw.contains(" ") || raw.contains("=");
  }

  /**
   * Given a name/value pair, rewrites the string to redact the value unless the name is in the
   * {@link #allowedEnvVars} list.
   *
   * @param raw a string of the form <tt>name=value</tt>
   * @return the rewritten string
   */
  public static String maybeScrubAssignment(OptionSensitivity sensitivity, String raw) {
    switch (sensitivity) {
      case NONE:
        return raw;

      case PARTIAL:
        String[] parts = raw.split("=", 2);
        if (parts.length < 2) {
          // If we find a lone value, we could redact it with the rationale being that the user
          // might have made a mistake and typed `--test_env=secret` instead of
          // `--test_env=VAR=secret`. However, redacting lone values would render the telemetry for
          // all flags nearly useless and a determined user would pass these checks anyway.
          return raw;
        }
        assert parts.length == 2;

        String name = parts[0];
        if (isEnvVarAllowed(name)) {
          return raw;
        }
        // We do not add the word REDACTED here because we want the equal sign to be explicitly
        // followed by a space, which simplifies detecting that there really is no value attached
        // to the variable.
        return String.format("%s= ", name);

      default:
        assert sensitivity.equals(OptionSensitivity.FULL);  // Cope with non-exhaustive switches.
        return "REDACTED";
    }
  }

  /**
   * Given a literal argument with name/value pairs that may contain a secret, rewrites the string
   * to redact the value unless the name is in the {@link #allowedEnvVars} or
   * {@link #allowedEnvVarPrefixes} lists.
   *
   * @param raw a string of the form <tt>option=name=value</tt> or <tt>option name=value</tt>. The
   *     string may or may not be prefixed by <tt>--</tt>.
   * @return the rewritten string
   */
  public static String maybeScrubCombinedForm(OptionSensitivity sensitivity, String raw) {
    int pos = 0;
    int[] chars = raw.chars().toArray();
    for (int ch : chars) {
      if (ch == ' ' || ch == '=') {
        break;
      }
      pos += 1;
    }
    if (pos == chars.length) {
      if (sensitivity == OptionSensitivity.NONE) {
        return raw;
      } else {
        // This should never happen, but better propagate a placeholder string rather than crash
        // due to the complexity of option parsing and the fact that we might have missed some
        // corner case.
        return "INVALID-OPTION-VALUE";
      }
    }
    pos += 1;
    return String.format(
        "%s%s", raw.substring(0, pos), maybeScrubAssignment(sensitivity, raw.substring(pos)));
  }

  /**
   * Scrubs secrets from a list of arguments.
   *
   * <p>The semantics for scrubbing arguments are the same as those defined by
   * {@link #maybeScrubCombinedForm}. However, this must take care of options that were split
   * across two arguments (one with the option's name and one with the value) as well as all
   * residual arguments.</p>
   *
   * @param args the arguments to scrub
   * @return the scrubbed arguments list
   */
  public static ImmutableList<String> scrubArgs(ImmutableList<String> args) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    Iterator<String> iter = args.iterator();

    // Handle Bazel's own arguments first.
    OptionSensitivity previousSensitivity = OptionSensitivity.NONE;
    while (iter.hasNext()) {
      String canonicalForm = iter.next();
      if (canonicalForm.equals("--")) {
        builder.add(canonicalForm);
        break;
      }

      if (previousSensitivity != OptionSensitivity.NONE) {
        canonicalForm = maybeScrubAssignment(previousSensitivity, canonicalForm);
        previousSensitivity = OptionSensitivity.NONE;
      } else {
        OptionSensitivity sensitivity = getOptionSensitivity(canonicalForm);
        if (sensitivity != OptionSensitivity.NONE) {
          if (containsOptionValue(canonicalForm)) {
            canonicalForm = maybeScrubCombinedForm(sensitivity, canonicalForm);
          } else {
            previousSensitivity = sensitivity;
          }
        }
      }
      builder.add(canonicalForm);
    }

    // Handle residual arguments.
    while (iter.hasNext()) {
      iter.next();
      builder.add("REDACTED");
    }

    return builder.build();
  }

  /**
   * Returns a string representation of the non-hidden specified options; option values are
   * shell-escaped.
   */
  public static String asShellEscapedString(Iterable<ParsedOptionDescription> optionsList) {
    StringBuilder result = new StringBuilder();
    for (ParsedOptionDescription option : optionsList) {
      if (option.isHidden()) {
        continue;
      }
      if (result.length() != 0) {
        result.append(' ');
      }
      OptionSensitivity sensitivity =
          getOptionSensitivity(option.getOptionDefinition().getOptionName());
      result.append(option.getCanonicalFormWithValueEscaper(
          (unescaped) ->
              ShellEscaper.escapeString(maybeScrubAssignment(sensitivity, unescaped))));
    }
    return result.toString();
  }

  /**
   * Returns a string representation of the non-hidden explicitly or implicitly specified options;
   * option values are shell-escaped.
   */
  public static String asShellEscapedString(OptionsParsingResult options) {
    return asShellEscapedString(options.asCompleteListOfParsedOptions());
  }

  /**
   * Return a representation of the non-hidden specified options, as a list of string. No escaping
   * is done.
   */
  public static List<String> asArgumentList(Iterable<ParsedOptionDescription> optionsList) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    for (ParsedOptionDescription option : optionsList) {
      if (option.isHidden()) {
        continue;
      }
      builder.add(option.getCanonicalForm());
    }
    return scrubArgs(builder.build());
  }

  /**
   * Return a representation of the non-hidden specified options, as a list of string. No escaping
   * is done.
   */
  public static List<String> asArgumentList(OptionsParsingResult options) {
    return asArgumentList(options.asCompleteListOfParsedOptions());
  }

  /**
   * Returns a string representation of the non-hidden explicitly or implicitly specified options,
   * filtering out any sensitive options; option values are shell-escaped.
   */
  public static String asFilteredShellEscapedString(
      OptionsParsingResult options, Iterable<ParsedOptionDescription> optionsList) {
    return asShellEscapedString(optionsList);
  }

  /**
   * Returns a string representation of the non-hidden explicitly or implicitly specified options,
   * filtering out any sensitive options; option values are shell-escaped.
   */
  public static String asFilteredShellEscapedString(OptionsParsingResult options) {
    return asFilteredShellEscapedString(options, options.asCompleteListOfParsedOptions());
  }

  /** Converter from String to PathFragment. */
  public static class PathFragmentConverter extends Converter.Contextless<PathFragment> {

    @Override
    public PathFragment convert(String input) {
      return convertOptionsPathFragment(checkNotNull(input));
    }

    @Override
    public String getTypeDescription() {
      return "a path";
    }
  }

  /** Converter from String to PathFragment. If the input is empty returns {@code null} instead. */
  public static class EmptyToNullPathFragmentConverter extends Converter.Contextless<PathFragment> {

    @Override
    @Nullable
    public PathFragment convert(String input) throws OptionsParsingException {
      if (input.isEmpty()) {
        return null;
      }
      return convertOptionsPathFragment(input);
    }

    @Override
    public String getTypeDescription() {
      return "a path";
    }
  }

  /** Converter from String to PathFragment requiring the provided path to be absolute. */
  public static class AbsolutePathFragmentConverter extends Converter.Contextless<PathFragment> {

    @Override
    public PathFragment convert(String input) throws OptionsParsingException {
      PathFragment parsed = convertOptionsPathFragment(checkNotNull(input));
      if (!parsed.isAbsolute()) {
        throw new OptionsParsingException(String.format("Not an absolute path: '%s'", input));
      }
      return parsed;
    }

    @Override
    public String getTypeDescription() {
      return "an absolute path";
    }
  }

  /**
   * Converter from String to PathFragment requiring the provided path to be relative. If the input
   * is empty returns {@code null} instead.
   */
  public static class EmptyToNullRelativePathFragmentConverter
      extends Converter.Contextless<PathFragment> {

    @Override
    @Nullable
    public PathFragment convert(String input) throws OptionsParsingException {
      if (input.isEmpty()) {
        return null;
      }

      PathFragment pathFragment = convertOptionsPathFragment(input);

      if (pathFragment.isAbsolute()) {
        throw new OptionsParsingException("Expected relative path but got '" + input + "'.");
      }

      return pathFragment;
    }

    @Override
    public String getTypeDescription() {
      return "a relative path";
    }
  }

  /** Converts from a colon-separated list of strings into a list of PathFragment instances. */
  public static class PathFragmentListConverter
      extends Converter.Contextless<ImmutableList<PathFragment>> {

    @Override
    public ImmutableList<PathFragment> convert(String input) {
      ImmutableList.Builder<PathFragment> result = ImmutableList.builder();
      for (String piece : input.split(":")) {
        if (!piece.isEmpty()) {
          result.add(convertOptionsPathFragment(piece));
        }
      }
      return result.build();
    }

    @Override
    public String getTypeDescription() {
      return "a colon-separated list of paths";
    }
  }

  private static PathFragment convertOptionsPathFragment(String path) {
    if (!path.isEmpty() && path.startsWith("~/")) {
      path = path.replace("~", StandardSystemProperty.USER_HOME.value());
    }
    return PathFragment.create(path);
  }
}
