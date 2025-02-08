// Copyright 2015 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.exec;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ParameterFile;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ParameterFile}. */
@RunWith(JUnit4.class)
public class ParameterFileTest extends FoundationTestCase {

  @Test
  public void testDerive() {
    assertThat(ParameterFile.derivePath(PathFragment.create("a/b")))
        .isEqualTo(PathFragment.create("a/b-2.params"));
    assertThat(ParameterFile.derivePath(PathFragment.create("b")))
        .isEqualTo(PathFragment.create("b-2.params"));
  }

  @Test
  public void testWriteAscii() throws Exception {
    assertThat(writeContent(StandardCharsets.ISO_8859_1, ImmutableList.of("--foo", "--bar")))
        .containsExactly("--foo", "--bar");
    assertThat(writeContent(StandardCharsets.UTF_8, ImmutableList.of("--foo", "--bar")))
        .containsExactly("--foo", "--bar");
  }

  @Test
  public void testWriteLatin1() throws Exception {
    assertThat(writeContent(StandardCharsets.ISO_8859_1, ImmutableList.of("--füü")))
        .containsExactly("--füü");
    assertThat(writeContent(StandardCharsets.UTF_8, ImmutableList.of("--füü")))
        .containsExactly("--füü");
  }

  @Test
  public void testWriteUtf8() throws Exception {
    assertThat(writeContent(StandardCharsets.ISO_8859_1, ImmutableList.of("--lambda=λ")))
        .containsExactly("--lambda=?");
    assertThat(writeContent(StandardCharsets.UTF_8, ImmutableList.of("--lambda=λ")))
        .containsExactly("--lambda=λ");
  }

  private static final ImmutableList<String> MIXED_ARGS =
      ImmutableList.of("a", "--b", "--c=d", "e");

  @Test
  public void flagsOnly() throws Exception {
    assertThat(ParameterFile.flagsOnly(MIXED_ARGS)).containsExactly("--b", "--c=d").inOrder();
  }

  @Test
  public void nonFlags() throws Exception {
    assertThat(ParameterFile.nonFlags(MIXED_ARGS)).containsExactly("a", "e").inOrder();
  }

  private static ImmutableList<String> writeContent(Charset charset, Iterable<String> content)
      throws Exception {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    ParameterFile.writeParameterFile(
        outputStream,
        Iterables.transform(
            // Bazel internally represents all strings as raw bytes in ISO-8859-1.
            content, s -> new String(s.getBytes(charset), StandardCharsets.ISO_8859_1)),
        ParameterFileType.UNQUOTED);
    return ImmutableList.<String>builder().add(outputStream.toString(charset).split("\n")).build();
  }
}
