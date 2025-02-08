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
package com.google.devtools.build.lib.runtime.commands.info;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.util.io.RecordingOutErr;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class InfoItemHandlerTest {
  @Test
  public void testStdOutputItem_addOneItemWithoutPrintingKey() throws Exception {
    RecordingOutErr outErr = new RecordingOutErr();
    CommandEnvironment mockEnv = mock(CommandEnvironment.class);
    when(mockEnv.getReporterOutErr()).thenReturn(outErr);
    try (StdoutInfoItemHandler stdoutInfoItemHandler =
        (StdoutInfoItemHandler) InfoItemHandler.create(mockEnv)) {
      stdoutInfoItemHandler.addInfoItem(
          "info-1", "value-1\n".getBytes(UTF_8), /* printKey= */ false);
    }

    assertThat(outErr.outAsLatin1()).isEqualTo("value-1\n");
  }

  @Test
  public void testStdOutputItem_addTwoItemWithPrintingKey() throws Exception {
    RecordingOutErr outErr = new RecordingOutErr();
    CommandEnvironment mockEnv = mock(CommandEnvironment.class);
    when(mockEnv.getReporterOutErr()).thenReturn(outErr);
    try (StdoutInfoItemHandler stdoutInfoItemHandler =
        (StdoutInfoItemHandler) InfoItemHandler.create(mockEnv)) {
      stdoutInfoItemHandler.addInfoItem("foo", "value-foo\n".getBytes(UTF_8), /* printKey= */ true);
      stdoutInfoItemHandler.addInfoItem("bar", "value-bar\n".getBytes(UTF_8), /* printKey= */ true);
    }

    assertThat(outErr.outAsLatin1()).isEqualTo("foo: value-foo\nbar: value-bar\n");
  }
}
