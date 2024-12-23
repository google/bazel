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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DependencySetTest {

  private Scratch scratch = new Scratch();
  private FileSystem fileSystem = scratch.getFileSystem();
  private Path root = scratch.resolve("/");

  private DependencySet newDependencySet() {
    return new DependencySet(root);
  }

  @Test
  public void dotDParser_simple() throws Exception {
    Path file1 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc");
    Path file2 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h");
    String filename = "hello.o";
    Path dotd = scratch.file("/tmp/foo.d",
        filename + ": \\",
        " " + file1 + " \\",
        " " + file2 + " ");
    DependencySet depset = newDependencySet().read(dotd);
    assertThat(depset.getDependencies()).containsExactlyElementsIn(Sets.newHashSet(file1, file2));
    assertThat(filename).isEqualTo(depset.getOutputFileName());
  }

  @Test
  public void dotDParser_simple_crlf() throws Exception {
    Path file1 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc");
    Path file2 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h");
    String filename = "hello.o";
    Path dotd = scratch.file("/tmp/foo.d",
        filename + ": \\\r",
        " " + file1 + " \\\r",
        " " + file2 + " ");
    DependencySet depset = newDependencySet().read(dotd);
    assertThat(depset.getDependencies()).containsExactlyElementsIn(Sets.newHashSet(file1, file2));
    assertThat(filename).isEqualTo(depset.getOutputFileName());
  }

  @Test
  public void dotDParser_simple_cr() throws Exception {
    Path file1 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc");
    Path file2 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h");
    String filename = "hello.o";
    Path dotd =
        scratch.file("/tmp/foo.d", filename + ": \\\r " + file1 + " \\\r " + file2 + " ");
    DependencySet depset = newDependencySet().read(dotd);
    assertThat(depset.getDependencies()).containsExactlyElementsIn(Sets.newHashSet(file1, file2));
    assertThat(filename).isEqualTo(depset.getOutputFileName());
  }

  @Test
  public void dotDParser_leading_crlf() throws Exception {
    Path file1 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc");
    Path file2 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h");
    String filename = "hello.o";
    Path dotd =
        scratch.file(
            "/tmp/foo.d",
            "\r\n" + filename + ": \\\r\n " + file1 + " \\\r\n " + file2 + " ");
    DependencySet depset = newDependencySet().read(dotd);
    assertThat(depset.getDependencies()).containsExactlyElementsIn(Sets.newHashSet(file1, file2));
    assertThat(filename).isEqualTo(depset.getOutputFileName());
  }

  @Test
  public void dotDParser_oddFormatting() throws Exception {
    Path file1 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc");
    Path file2 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h");
    Path file3 = fileSystem.getPath("/usr/local/blah/blah/genhello/other.h");
    Path file4 = fileSystem.getPath("/usr/local/blah/blah/genhello/onemore.h");
    String filename = "hello.o";
    Path dotd = scratch.file("/tmp/foo.d",
        filename + ": " + file1 + " \\",
        " " + file2 + "\\",
        " " + file3 + " " + file4);
    DependencySet depset = newDependencySet().read(dotd);
    assertThat(depset.getDependencies())
        .containsExactlyElementsIn(Sets.newHashSet(file1, file2, file3, file4));
    assertThat(filename).isEqualTo(depset.getOutputFileName());
  }

  @Test
  public void dotDParser_relativeFilenames() throws Exception {
    Path file1 = root.getRelative("hello.cc");
    Path file2 = root.getRelative("hello.h");
    String filename = "hello.o";
    Path dotd = scratch.file("/tmp/foo.d",
        filename + ": \\",
        " " + file1.relativeTo(root) + " \\",
        " " + file2.relativeTo(root) + " ");
    DependencySet depset = newDependencySet().read(dotd);
    assertThat(depset.getDependencies()).containsExactlyElementsIn(Sets.newHashSet(file1, file2));
    assertThat(filename).isEqualTo(depset.getOutputFileName());
  }

  @Test
  public void dotDParser_escapeDollar() throws Exception {
    Path dotd =
        scratch.file(
            "/tmp/foo.d",
            "hello.o: \\",
            " /usr/local/blah/$$blah/$$hello.cc \\",
            " /usr/local/blah/blah/hel$$$$lo.h \\",
            " /usr/local/blah/$$blah/hello.h");

    Set<Path> expected =
        Sets.newHashSet(
            fileSystem.getPath("/usr/local/blah/$blah/$hello.cc"),
            fileSystem.getPath("/usr/local/blah/blah/hel$$lo.h"),
            fileSystem.getPath("/usr/local/blah/$blah/hello.h"));

    assertThat(newDependencySet().read(dotd).getDependencies()).containsExactlyElementsIn(expected);
  }

  @Test
  public void dotDParser_emptyFile() throws Exception {
    Path dotd = scratch.file("/tmp/empty.d");
    DependencySet depset = newDependencySet().read(dotd);
    Collection<Path> headers = depset.getDependencies();
    if (!headers.isEmpty()) {
      fail("Not empty: " + headers.size() + " " + headers);
    }
    assertThat(depset.getOutputFileName()).isNull();
  }

  @Test
  public void dotDParser_multipleTargets() throws Exception {
    Path file1 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc");
    Path file2 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h");
    Path dotd = scratch.file("/tmp/foo.d",
        "hello.o: \\",
        " " + file1,
        "hello2.o: \\",
        " " + file2);
    assertThat(newDependencySet().read(dotd).getDependencies())
        .containsExactlyElementsIn(Sets.newHashSet(file1, file2));
  }

  /*
   * Regression test: if gcc fails to execute remotely, and we retry locally, then the behavior
   * of gcc's DEPENDENCIES_OUTPUT option is to append, not overwrite, the .d file. As a result,
   * during retry, a second stanza is written to the file.
   *
   * We handle this by merging all of the stanzas.
   */
  @Test
  public void dotDParser_duplicateStanza() throws Exception {
    Path file1 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc");
    Path file2 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h");
    Path file3 = fileSystem.getPath("/usr/local/blah/blah/genhello/other.h");
    Path dotd = scratch.file("/tmp/foo.d",
        "hello.o: \\",
        " " + file1 + " \\",
        " " + file2 + " ",
        "hello.o: \\",
        " " + file1 + " \\",
        " " + file3 + " ");
    assertThat(newDependencySet().read(dotd).getDependencies())
        .containsExactly(file1, file1, file2, file3);
  }

  @Test
  public void dotDParser_errorOnNoTrailingNewline() throws Exception {
    Path file1 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc");
    Path dotd = scratch.file("/tmp/foo.d");
    FileSystemUtils.writeContent(
        dotd, ("hello.o: \\\n " + file1).getBytes(Charset.forName("UTF-8")));
    IOException e = assertThrows(IOException.class, () -> newDependencySet().read(dotd));
    assertThat(e).hasMessageThat().contains("File does not end in a newline");
  }

  /*
   * Test compatibility with --config=nvcc, which writes an extra space before the colon.
   */
  @Test
  public void dotDParser_spaceBeforeColon() throws Exception {
    Path file1 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc");
    Path file2 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h");
    String filename = "hello.o";
    Path dotd = scratch.file("/tmp/foo.d",
        filename + " : \\",
        " " + file1 + " \\",
        " " + file2 + " ");
    DependencySet depset = newDependencySet().read(dotd);
    assertThat(depset.getDependencies()).containsExactlyElementsIn(Sets.newHashSet(file1, file2));
    assertThat(filename).isEqualTo(depset.getOutputFileName());
  }

  /*
   * Bug-for-bug compatibility with --config=msvc, which writes malformed .d files.
   */
  @Test
  public void dotDParser_missingBackslash() throws Exception {
    Path file1 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc");
    Path file2 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h");
    String filename = "hello.o";
    Path dotd = scratch.file("/tmp/foo.d",
        filename + ": ",
        " " + file1 + " \\",
        " " + file2 + " ");
    DependencySet depset = newDependencySet().read(dotd);
    assertThat(depset.getDependencies()).isEmpty();
  }

  @Test
  public void writeSet() throws Exception {
    Path file1 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc");
    Path file2 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h");
    Path file3 = fileSystem.getPath("/usr/local/blah/blah/genhello/other.h");
    String filename = "/usr/local/blah/blah/genhello/hello.o";

    DependencySet depSet1 = newDependencySet();
    depSet1.addDependencies(ImmutableList.of(file1, file2, file3));
    depSet1.setOutputFileName(filename);

    Path outfile = scratch.resolve(filename);
    Path dotd = scratch.resolve("/usr/local/blah/blah/genhello/hello.d");
    dotd.getParentDirectory().createDirectoryAndParents();
    depSet1.write(outfile, ".d");

    String dotdContents = FileSystemUtils.readContentToString(dotd);
    String expected =
        "usr/local/blah/blah/genhello/hello.o:  \\\n"
            + "  /usr/local/blah/blah/genhello/hello.cc \\\n"
            + "  /usr/local/blah/blah/genhello/hello.h \\\n"
            + "  /usr/local/blah/blah/genhello/other.h\n";
    assertThat(dotdContents).isEqualTo(expected);
    assertThat(depSet1.getOutputFileName()).isEqualTo(filename);
  }

  @Test
  public void writeReadSet() throws Exception {
    String filename = "/usr/local/blah/blah/genhello/hello.d";
    Path file1 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc");
    Path file2 = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h");
    Path file3 = fileSystem.getPath("/usr/local/blah/blah/genhello/other.h");
    DependencySet depSet1 = newDependencySet();
    depSet1.addDependencies(ImmutableList.of(file1, file2, file3));
    depSet1.setOutputFileName(filename);

    Path dotd = scratch.resolve(filename);
    dotd.getParentDirectory().createDirectoryAndParents();
    depSet1.write(dotd, ".d");

    DependencySet depSet2 = newDependencySet().read(dotd);
    assertThat(depSet2).isEqualTo(depSet1);
    // due to how pic.d files are written, absolute paths are changed into relatives
    assertThat("/" + depSet2.getOutputFileName()).isEqualTo(depSet1.getOutputFileName());
  }

}
