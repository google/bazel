// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.outputfilter;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.analysis.platform.PlatformConstants;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.OutputFilter;
import com.google.devtools.build.lib.events.OutputFilter.RegexOutputFilter;
import com.google.devtools.common.options.EnumConverter;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.regex.Pattern;

/**
 * Implementation of various settings for the {@code --auto_output_filter}
 * option.
 *
 * <p>Note that any actions owned by the system owner (typically just the build info
 * action) are treated specially: They are not filtered out by these auto filters.
 */
public enum AutoOutputFilter {
  /**
   * Generates an empty output filter (i.e. one that matches everything).
   */
  NONE {
    @Override
    public OutputFilter getFilter(Iterable<Label> targets) {
      return OutputFilter.OUTPUT_EVERYTHING;
    }
  },

  /**
   * Generates an output filter that matches nothing.
   */
  ALL {
    @Override
    public OutputFilter getFilter(Iterable<Label> targets) {
      return OutputFilter.OUTPUT_NOTHING;
    }
  },

  /**
   * Generates an output filter that matches all targets that are in the same
   * package as a target on the command line. {@code //java/foo} and
   * {@code //javatests/foo} are treated like a single package.
   */
  PACKAGES {
    @Override
    public OutputFilter getFilter(Iterable<Label> targets) {
      Pattern pattern = Pattern.compile(SYSTEM_ACTION_REGEX + "|"
          + "^//" + getPkgRegex(getPackages(targets)) + ":");
      return RegexOutputFilter.forPattern(pattern);
    }
  },

  /**
   * Generates an output filter that matches all targets that are in the same
   * package or in a subpackage of a target on the command line.
   * {@code //java/foo} and {@code //javatests/foo} are treated like a single
   * package.
   */
  SUBPACKAGES {
    @Override
    public OutputFilter getFilter(Iterable<Label> targets) {
      List<String> packages = new ArrayList<>();

      String previous = null;
      for (String pkg : getPackages(targets)) {
        if (previous != null && pkg.startsWith(previous + "/")) {
          // We already have a super-package in the list, so this package does
          // not need to be added.
          continue;
        }
        packages.add(pkg);
        previous = pkg;
      }

      Pattern pattern = Pattern.compile(SYSTEM_ACTION_REGEX + "|"
          + "^//" + getPkgRegex(packages) + "[/:]");
      return RegexOutputFilter.forPattern(pattern);
    }
  };

  private static final String SYSTEM_ACTION_REGEX =
      "(unknown)|" + PlatformConstants.INTERNAL_PLATFORM;

  /** Returns an output filter regex for a set of requested targets. */
  public abstract OutputFilter getFilter(Iterable<Label> targets);

  /**
   * Returns the package names of a some targets as an alphabetically sorted list. If there are
   * targets under {@code //java/...} or {@code //javatests/...}, a string starting with
   * "java(tests)?" is added instead.
   */
  protected SortedSet<String> getPackages(Iterable<Label> targets) {
    ImmutableSortedSet.Builder<String> packages = ImmutableSortedSet.naturalOrder();
    for (Label label : targets) {
      String name = label.getPackageName();
      // Treat //java/foo and //javatests/foo as one package
      if (name.startsWith("java/") || name.startsWith("javatests/")) {
        name = "java(tests)?" + name.substring(name.indexOf('/'));
      }
      packages.add(name);
    }
    return packages.build();
  }

  /**
   * Builds a regular expression that matches one of several strings. Characters
   * that have a special meaning in regular expressions will not be escaped.
   */
  protected String getPkgRegex(Iterable<String> strings) {
    return "(" + Joiner.on('|').join(strings) + ")";
  }

  /**
   * A converter for the {@code --auto_output_filter} option.
   */
  public static class Converter extends EnumConverter<AutoOutputFilter> {
    public Converter() {
      super(AutoOutputFilter.class, "automatic output filter");
    }
  }
}
