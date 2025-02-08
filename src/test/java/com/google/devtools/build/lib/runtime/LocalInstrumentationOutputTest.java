// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile;
import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile.LocalFileType;
import com.google.devtools.build.lib.buildeventstream.BuildToolLogs.LogFileEntry;
import com.google.devtools.build.lib.buildtool.BuildResult.BuildToolLogCollection;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class LocalInstrumentationOutputTest {
  private LocalInstrumentationOutput.Builder localInstrumentationOutputBuilder;

  @Before
  public void setup() {
    localInstrumentationOutputBuilder = new LocalInstrumentationOutput.Builder();
  }

  @Test
  public void testLocalInstrumentationOutputBuilder_failToBuildWhenMissingName() {
    Throwable throwable =
        assertThrows(
            NullPointerException.class,
            localInstrumentationOutputBuilder.setPath(
                    new InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/file"))
                ::build);
    assertThat(throwable)
        .hasMessageThat()
        .isEqualTo("Cannot create LocalInstrumentationOutputBuilder without name");
  }

  @Test
  public void testLocalInstrumentationOutputBuilder_failToBuildWhenMissingPath() {
    Throwable throwable =
        assertThrows(
            NullPointerException.class, localInstrumentationOutputBuilder.setName("local")::build);
    assertThat(throwable)
        .hasMessageThat()
        .isEqualTo("Cannot create LocalInstrumentationOutputBuilder without path");
  }

  @Test
  public void testLocalInstrumentation_publishNameAndPath() {
    FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    Path path = fs.getPath("/file");
    InstrumentationOutput localInstrumentationOutput =
        localInstrumentationOutputBuilder.setName("local").setPath(path).build();

    assertThat(localInstrumentationOutput).isInstanceOf(LocalInstrumentationOutput.class);
    BuildToolLogCollection buildToolLogCollection = new BuildToolLogCollection();
    localInstrumentationOutput.publish(buildToolLogCollection);
    buildToolLogCollection.freeze();

    assertThat(buildToolLogCollection.getLocalFiles())
        .containsExactly(
            new LogFileEntry(
                "local", new LocalFile(path, LocalFileType.LOG, /* artifactMetadata= */ null)));
  }

  @Test
  public void testLocalInstrumentation_recursiveCreateParentDirectory(
      @TestParameter boolean enableRecursiveCreateDirectory) throws IOException {
    FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    Path path = fs.getPath("/subdir1/subdir2/file");
    assertThat(path.exists()).isFalse();

    localInstrumentationOutputBuilder.setName("recursive-dir-output").setPath(path);
    if (enableRecursiveCreateDirectory) {
      InstrumentationOutput localInstrumentationOutput =
          localInstrumentationOutputBuilder.setCreateParent(/* createParent= */ true).build();
      var unused = localInstrumentationOutput.createOutputStream();
      assertThat(path.exists()).isTrue();
    } else {
      InstrumentationOutput localInstrumentationOutput = localInstrumentationOutputBuilder.build();
      assertThrows(
          "No such file or directory",
          IOException.class,
          localInstrumentationOutput::createOutputStream);
    }
  }
}
