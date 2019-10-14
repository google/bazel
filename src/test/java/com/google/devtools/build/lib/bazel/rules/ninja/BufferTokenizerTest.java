// Copyright 2019 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.rules.ninja;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.bazel.rules.ninja.file.BufferTokenizer;
import com.google.devtools.build.lib.bazel.rules.ninja.file.ByteBufferFragment;
import com.google.devtools.build.lib.bazel.rules.ninja.file.NinjaSeparatorPredicate;
import com.google.devtools.build.lib.bazel.rules.ninja.file.TokenConsumer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link BufferTokenizer}
 */
@RunWith(JUnit4.class)
public class BufferTokenizerTest {
  @Test
  public void testTokenizeSimple() throws Exception {
    List<String> list = ImmutableList.of("one", "two", "three");

    List<String> result = Lists.newArrayList();
    TokenConsumer consumer = fragment -> result.add(fragment.toString());

    byte[] chars = String.join("\n", list).getBytes(StandardCharsets.ISO_8859_1);
    BufferTokenizer tokenizer = new BufferTokenizer(
        ByteBuffer.wrap(chars), consumer, NinjaSeparatorPredicate.INSTANCE, 0, 0, chars.length);
    tokenizer.call();
    assertThat(result).containsExactly("two\n");
    assertThat(tokenizer.getFragments().stream()
        .map(ByteBufferFragment::toString).collect(Collectors.toList()))
        .containsExactly("one\n", "three").inOrder();
  }

  @Test
  public void testTokenizeWithDetails() throws Exception {
    List<String> list = ImmutableList.of("one", " one-detail", "two", "\ttwo-detail",
        "three", " three-detail");
    byte[] chars = String.join("\n", list).getBytes(StandardCharsets.ISO_8859_1);

    List<String> result = Lists.newArrayList();
    TokenConsumer consumer = fragment -> result.add(fragment.toString());

    BufferTokenizer tokenizer = new BufferTokenizer(
        ByteBuffer.wrap(chars), consumer, NinjaSeparatorPredicate.INSTANCE, 0, 0, chars.length);
    tokenizer.call();
    assertThat(result).containsExactly("two\n\ttwo-detail\n");
    assertThat(tokenizer.getFragments().stream()
      .map(ByteBufferFragment::toString).collect(Collectors.toList()))
        .containsExactly("one\n one-detail\n", "three\n three-detail").inOrder();
  }
}
