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

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.util.StringUtil.joinEnglishList;
import static com.google.devtools.build.lib.util.StringUtil.joinEnglishListSingleQuoted;

import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** A test for {@link StringUtil}. */
@RunWith(JUnit4.class)
public class StringUtilTest {

  @Test
  public void testJoinEnglishList() throws Exception {
    assertThat(joinEnglishList(ImmutableList.of())).isEqualTo("nothing");
    assertThat(joinEnglishList(Arrays.asList("one"))).isEqualTo("one");
    assertThat(joinEnglishList(Arrays.asList("one", "two"))).isEqualTo("one or two");
    assertThat(joinEnglishList(Arrays.asList("one", "two"), "and")).isEqualTo("one and two");
    assertThat(joinEnglishList(Arrays.asList("one", "two", "three")))
        .isEqualTo("one, two, or three");
    assertThat(joinEnglishList(Arrays.asList("one", "two", "three"), "and"))
        .isEqualTo("one, two, and three");
    assertThat(joinEnglishList(Arrays.asList("one", "two", "three"), "or even", "\"", true))
        .isEqualTo("\"one\", \"two\", or even \"three\"");
    assertThat(joinEnglishList(Arrays.asList("one", "two", "three"), "then", "'", false))
        .isEqualTo("'one', 'two' then 'three'");
  }

  @Test
  public void testJoinEnglishListSingleQuoted() throws Exception {
    assertThat(joinEnglishListSingleQuoted(ImmutableList.of())).isEqualTo("nothing");
    assertThat(joinEnglishListSingleQuoted(Arrays.asList("one"))).isEqualTo("'one'");
    assertThat(joinEnglishListSingleQuoted(Arrays.asList("one", "two")))
        .isEqualTo("'one' or 'two'");
    assertThat(joinEnglishListSingleQuoted(Arrays.asList("one", "two", "three")))
        .isEqualTo("'one', 'two', or 'three'");
  }

  @Test
  public void listItemsWithLimit() throws Exception {
    assertThat(
            StringUtil.listItemsWithLimit(
                    new StringBuilder("begin/"), 3, ImmutableList.of("a", "b", "c"))
                .append("/end")
                .toString())
        .isEqualTo("begin/a, b, c/end");

    assertThat(
            StringUtil.listItemsWithLimit(
                    new StringBuilder("begin/"), 3, ImmutableList.of("a", "b", "c", "d", "e"))
                .append("/end")
                .toString())
        .isEqualTo("begin/a, b, c ...(omitting 2 more item(s))/end");
  }
}
