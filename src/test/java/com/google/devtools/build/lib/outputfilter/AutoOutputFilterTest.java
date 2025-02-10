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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.devtools.build.lib.outputfilter.AutoOutputFilter.ALL;
import static com.google.devtools.build.lib.outputfilter.AutoOutputFilter.NONE;
import static com.google.devtools.build.lib.outputfilter.AutoOutputFilter.PACKAGES;
import static com.google.devtools.build.lib.outputfilter.AutoOutputFilter.SUBPACKAGES;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;
import com.google.devtools.build.lib.analysis.platform.PlatformConstants;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.OutputFilter;
import java.util.List;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/** Tests for the {@link AutoOutputFilter} class. */
@RunWith(Enclosed.class)
public class AutoOutputFilterTest {

  @RunWith(Parameterized.class)
  public static class NoneTest {
    @Parameters(name = "{0}")
    public static ImmutableList<Object[]> filters() {
      return ImmutableList.of(
          new Object[] {targets(), OutputFilter.OUTPUT_EVERYTHING},
          new Object[] {targets("//a"), OutputFilter.OUTPUT_EVERYTHING},
          new Object[] {targets("//a", "//b"), OutputFilter.OUTPUT_EVERYTHING});
    }

    @Parameter(0)
    public List<Label> targets;

    @Parameter(1)
    public OutputFilter expectedOutputFilter;

    @Test
    public void testFilter() {
      assertThat(NONE.getFilter(this.targets)).isEqualTo(this.expectedOutputFilter);
    }
  }

  @RunWith(Parameterized.class)
  public static class AllTest {
    @Parameters(name = "{0}")
    public static ImmutableList<Object[]> filters() {
      return ImmutableList.of(
          new Object[] {targets(), OutputFilter.OUTPUT_NOTHING},
          new Object[] {targets("//a"), OutputFilter.OUTPUT_NOTHING},
          new Object[] {targets("//a", "//b"), OutputFilter.OUTPUT_NOTHING});
    }

    @Parameter(0)
    public List<Label> targets;

    @Parameter(1)
    public OutputFilter expectedOutputFilter;

    @Test
    public void testFilter() {
      assertThat(ALL.getFilter(this.targets)).isEqualTo(this.expectedOutputFilter);
    }
  }

  @RunWith(Parameterized.class)
  public static class PackagesTest {
    @Parameters(name = "{0}-{1}")
    public static ImmutableList<Object[]> filters() {
      return ImmutableList.of(
          new Object[] {"^//():", targets()},
          new Object[] {"^//(a):", targets("//a:b")},
          new Object[] {"^//(a):", targets("//a:b", "//a:c")},
          new Object[] {"^//(a|b):", targets("//a:a", "//a:b", "//b:c")},
          new Object[] {"^//(a|b):", targets("//a:a", "//b:c", "//a:b")},
          new Object[] {"^//(a/b|a/b/c):", targets("//a/b:b", "//a/b/c:c")},
          new Object[] {"^//(java(tests)?/a):", targets("//java/a")},
          new Object[] {"^//(java(tests)?/a):", targets("//javatests/a")},
          new Object[] {"^//(java(tests)?/a):", targets("//java/a", "//javatests/a")},
          new Object[] {
            "^//(java(tests)?/a|java(tests)?/b):", targets("//java/a", "//javatests/b")
          },
          new Object[] {"^//(a/b|a/b/c):", targets("//a/b:b", "//a/b/c:c")},
          new Object[] {"^//(a|a/b|a/b/c|b):", targets("//a", "//a/b", "//a/b/c", "//b")},
          new Object[] {"^//(a|a/b/c|b|b/c/d):", targets("//a", "//a/b/c", "//b", "//b/c/d")},
          new Object[] {
            "^//(java(tests)?/a|java(tests)?/a/b):", targets("//java/a", "//javatests/a/b")
          },
          new Object[] {
            "^//(java(tests)?/a|java(tests)?/a/b/c):", targets("//javatests/a", "//java/a/b/c")
          });
    }

    @Parameter(0)
    public String expectedRegex;

    @Parameter(1)
    public ImmutableList<Label> targetLabels;

    @Test
    public void testFilter() {
      assertFilter(this.expectedRegex, PACKAGES, this.targetLabels);
    }
  }

  @RunWith(Parameterized.class)
  public static class SubpackagesTest {
    @Parameters(name = "{0}-{1}")
    public static ImmutableList<Object[]> filters() {
      return ImmutableList.of(
          new Object[] {"^//()[/:]", targets()},
          new Object[] {"^//(a)[/:]", targets("//a:b")},
          new Object[] {"^//(a)[/:]", targets("//a:b", "//a:c")},
          new Object[] {"^//(a|b)[/:]", targets("//a:a", "//a:b", "//b:c")},
          new Object[] {"^//(a|b)[/:]", targets("//a:a", "//b:c", "//a:b")},
          new Object[] {"^//(java(tests)?/a)[/:]", targets("//java/a")},
          new Object[] {"^//(java(tests)?/a)[/:]", targets("//javatests/a")},
          new Object[] {"^//(java(tests)?/a)[/:]", targets("//java/a", "//javatests/a")},
          new Object[] {
            "^//(java(tests)?/a|java(tests)?/b)[/:]", targets("//java/a", "//javatests/b")
          },
          new Object[] {"^//(a/b)[/:]", targets("//a/b:b", "//a/b/c:c")},
          new Object[] {"^//(a|b)[/:]", targets("//a", "//a/b", "//a/b/c", "//b")},
          new Object[] {"^//(a|b)[/:]", targets("//a", "//a/b/c", "//b", "//b/c/d")},
          new Object[] {"^//(java(tests)?/a)[/:]", targets("//java/a", "//javatests/a/b")},
          new Object[] {"^//(java(tests)?/a)[/:]", targets("//javatests/a", "//java/a/b/c")});
    }

    @Parameter(0)
    public String expectedRegex;

    @Parameter(1)
    public ImmutableList<Label> targetLabels;

    @Test
    public void testFilter() {
      assertFilter(this.expectedRegex, SUBPACKAGES, this.targetLabels);
    }
  }

  private static void assertFilter(
      String extractedRegex, AutoOutputFilter autoFilter, List<Label> targetLabels) {
    OutputFilter filter = autoFilter.getFilter(targetLabels);
    String extraRegex =
        (autoFilter == AutoOutputFilter.NONE)
            ? ""
            : "(unknown)|" + PlatformConstants.INTERNAL_PLATFORM + "|";
    assertWithMessage("output filter " + autoFilter + " returned wrong filter:")
        .that(filter.toString())
        .isEqualTo(extraRegex + extractedRegex);
  }

  private static ImmutableList<Label> targets(String... targetLabels) {
    // Sort targets by package
    ListMultimap<String, String> targetsPerPackage = ArrayListMultimap.create();
    for (String targetName : targetLabels) {
      Label label = Label.parseCanonicalUnchecked(targetName);
      targetsPerPackage.put(label.getPackageName(), label.getName());
    }

    // Collect targets
    ImmutableList.Builder<Label> targets = ImmutableList.builder();
    for (String targetName : targetLabels) {
      targets.add(Label.parseCanonicalUnchecked(targetName));
    }
    return targets.build();
  }
}
