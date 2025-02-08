// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Spawn.Code;
import com.google.protobuf.ByteString;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Testing common SpawnResult features
 */
@RunWith(JUnit4.class)
public final class SpawnResultTest {

  @Test
  public void getTimeoutMessage() {
    SpawnResult r =
        new SpawnResult.Builder()
            .setStatus(SpawnResult.Status.TIMEOUT)
            .setWallTimeInMs(5 * 1000)
            .setExitCode(SpawnResult.POSIX_TIMEOUT_EXIT_CODE)
            .setFailureDetail(
                FailureDetail.newBuilder()
                    .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.TIMEOUT))
                    .build())
            .setRunnerName("test")
            .build();
    assertThat(r.getDetailMessage("", false, false))
        .contains("(failed due to timeout after 5.00 seconds.)");
  }

  @Test
  public void getTimeoutMessageNoTime() {
    SpawnResult r =
        new SpawnResult.Builder()
            .setStatus(SpawnResult.Status.TIMEOUT)
            .setExitCode(SpawnResult.POSIX_TIMEOUT_EXIT_CODE)
            .setFailureDetail(
                FailureDetail.newBuilder()
                    .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.TIMEOUT))
                    .build())
            .setRunnerName("test")
            .build();
    assertThat(r.getDetailMessage("", false, false)).contains("(failed due to timeout.)");
  }

  @Test
  public void inMemoryContents() {
    ActionInput output = ActionInputHelper.fromPath("/foo/bar");
    ByteString contents = ByteString.copyFromUtf8("hello world");

    SpawnResult r =
        new SpawnResult.Builder()
            .setStatus(Status.SUCCESS)
            .setExitCode(0)
            .setRunnerName("test")
            .setInMemoryOutput(output, contents)
            .build();

    assertThat(r.getInMemoryOutput(output)).isEqualTo(contents);
    assertThat(r.getInMemoryOutput(null)).isEqualTo(null);
    assertThat(r.getInMemoryOutput(ActionInputHelper.fromPath("/does/not/exist"))).isEqualTo(null);
  }
}
