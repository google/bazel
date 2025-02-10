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
package com.google.devtools.build.lib.buildtool;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialModule;
import com.google.devtools.build.lib.buildeventservice.BazelBuildEventServiceModule;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId.IdCase;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TestStatus;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.runtime.BlazeCommandDispatcher;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.NoSpawnCacheModule;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Integration test verifying behavior of {@code
 * com.google.devtools.build.lib.runtime.TargetSummaryEvent} event.
 */
@RunWith(JUnit4.class)
public class TargetSummaryEventTest extends BuildIntegrationTestCase {

  @Rule public final TemporaryFolder tmpFolder = new TemporaryFolder();

  @Before
  public void stageEmbeddedTools() throws Exception {
    AnalysisMock.get().setupMockToolsRepository(mockToolsConfig);
  }

  @Override
  protected BlazeRuntime.Builder getRuntimeBuilder() throws Exception {
    return super.getRuntimeBuilder()
        .addBlazeModule(new NoSpawnCacheModule())
        .addBlazeModule(new CredentialModule())
        .addBlazeModule(new BazelBuildEventServiceModule());
  }

  private void afterBuildCommand() throws Exception {
    runtimeWrapper.newCommand();
  }

  @Test
  public void plainTarget_buildSuccess() throws Exception {
    write("foo/BUILD", "genrule(name = 'foobin', outs = ['out.txt'], cmd = 'echo -n Hello > $@')");

    File bep = buildTargetAndCaptureBuildEventProtocol("//foo:foobin");

    BuildEventStreamProtos.TargetSummary summary = findTargetSummaryEventInBuildEventStream(bep);
    assertThat(summary.getOverallBuildSuccess()).isTrue();
    assertThat(summary.getOverallTestStatus()).isEqualTo(TestStatus.NO_STATUS);
  }

  @Test
  public void plainTarget_buildFails() throws Exception {
    write("foo/BUILD", "genrule(name = 'foobin', outs = ['out.txt'], cmd = 'false')");

    File bep = buildFailingTargetAndCaptureBuildEventProtocol("//foo:foobin");

    BuildEventStreamProtos.TargetSummary summary = findTargetSummaryEventInBuildEventStream(bep);
    assertThat(summary.getOverallBuildSuccess()).isFalse();
    assertThat(summary.getOverallTestStatus()).isEqualTo(TestStatus.NO_STATUS);
  }

  @Test
  public void test_buildSucceeds_testSucceeds() throws Exception {
    write("foo/good_test.sh", "#!/bin/bash", "true").setExecutable(true);
    write(
        "foo/BUILD",
        "load('//test_defs:foo_test.bzl', 'foo_test')",
        "foo_test(name = 'good_test', srcs = ['good_test.sh'])");

    File bep = testTargetAndCaptureBuildEventProtocol("//foo:good_test");

    BuildEventStreamProtos.TargetSummary summary = findTargetSummaryEventInBuildEventStream(bep);
    assertThat(summary.getOverallBuildSuccess()).isTrue();
    assertThat(summary.getOverallTestStatus()).isEqualTo(TestStatus.PASSED);
  }

  @Test
  public void test_buildSucceeds_testFails() throws Exception {
    write("foo/bad_test.sh", "#!/bin/bash", "false").setExecutable(true);
    write(
        "foo/BUILD",
        "load('//test_defs:foo_test.bzl', 'foo_test')",
        "foo_test(name = 'bad_test', srcs = ['bad_test.sh'])");

    File bep = testTargetAndCaptureBuildEventProtocol("//foo:bad_test");

    BuildEventStreamProtos.TargetSummary summary = findTargetSummaryEventInBuildEventStream(bep);
    assertThat(summary.getOverallBuildSuccess()).isTrue();
    assertThat(summary.getOverallTestStatus()).isEqualTo(TestStatus.FAILED);
  }

  @Test
  public void test_buildSucceeds_testRuntimeFailsToBuild() throws Exception {
    write("foo/good_test.sh", "#!/bin/bash", "true").setExecutable(true);
    write(
        "foo/BUILD",
        "load('//test_defs:foo_test.bzl', 'foo_test')",
        "foo_test(name = 'good_test', srcs = ['good_test.sh'])");

    // Hack: the path to the tools/test/BUILD file is prefixed in the Bazel tests.
    String pathToToolsTestBuildPrefix = AnalysisMock.get().isThisBazel() ? "embedded_tools/" : "";
    Path toolsTestBuildPath =
        mockToolsConfig.getPath(pathToToolsTestBuildPrefix + "tools/test/BUILD");
    // Delete the test-setup.sh file and introduce a broken genrule to create test-setup.sh.
    mockToolsConfig.getPath(pathToToolsTestBuildPrefix + "tools/test/test-setup.sh").delete();
    String bogusTestSetupGenrule =
        """
        genrule(
            name = 'bogus-make-test-setup',
            outs = ['test-setup.sh'],
            cmd = 'false',
        )
        """;
    FileSystemUtils.appendIsoLatin1(toolsTestBuildPath, bogusTestSetupGenrule);

    File bep = testTargetAndCaptureBuildEventProtocol("//foo:good_test");

    BuildEventStreamProtos.TargetSummary summary = findTargetSummaryEventInBuildEventStream(bep);
    assertThat(summary.getOverallBuildSuccess()).isTrue();
    assertThat(summary.getOverallTestStatus()).isEqualTo(TestStatus.FAILED_TO_BUILD);
  }

  private File buildTargetAndCaptureBuildEventProtocol(String target) throws Exception {
    File bep = tmpFolder.newFile();
    // We use WAIT_FOR_UPLOAD_COMPLETE because it's the easiest way to force the BES module to
    // wait until the BEP binary file has been written.
    addOptions(
        "--keep_going",
        "--experimental_bep_target_summary",
        "--build_event_binary_file=" + bep.getAbsolutePath(),
        "--bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE");
    buildTarget(target);
    // We need to wait for all events to be written to the file, which is done in #afterCommand()
    // if --bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE.
    afterBuildCommand();
    return bep;
  }

  private File buildFailingTargetAndCaptureBuildEventProtocol(String target) throws Exception {
    File bep = tmpFolder.newFile();
    // We use WAIT_FOR_UPLOAD_COMPLETE because it's the easiest way to force the BES module to
    // wait until the BEP binary file has been written.
    addOptions(
        "--keep_going",
        "--experimental_bep_target_summary",
        "--build_event_binary_file=" + bep.getAbsolutePath(),
        "--bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE");
    assertThrows(BuildFailedException.class, () -> buildTarget(target));
    // We need to wait for all events to be written to the file, which is done in #afterCommand()
    // if --bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE.
    afterBuildCommand();
    return bep;
  }

  private File testTargetAndCaptureBuildEventProtocol(String target) throws Exception {
    File bep = tmpFolder.newFile();
    BlazeCommandDispatcher dispatcher = new BlazeCommandDispatcher(getRuntime());
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("test", target);
    args.addAll(runtimeWrapper.getOptions());
    // We use WAIT_FOR_UPLOAD_COMPLETE because it's the easiest way to force the BES module to
    // wait until the BEP binary file has been written.
    args.add(
        "--default_visibility=public",
        "--test_output=all",
        "--keep_going",
        "--client_env=PATH=/bin:/usr/bin:/usr/sbin:/sbin",
        "--experimental_bep_target_summary",
        "--build_event_binary_file=" + bep.getAbsolutePath(),
        "--bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE");
    dispatcher.exec(args.build(), /* clientDescription= */ "test", outErr);
    return bep;
  }

  private static ImmutableList<BuildEvent> parseBuildEventsFromBuildEventStream(File bep)
      throws IOException {
    ImmutableList.Builder<BuildEvent> buildEvents = ImmutableList.builder();
    try (InputStream in = new FileInputStream(bep)) {
      BuildEvent ev;
      while ((ev = BuildEvent.parseDelimitedFrom(in)) != null) {
        buildEvents.add(ev);
      }
    }
    return buildEvents.build();
  }

  @Nullable
  private static BuildEventStreamProtos.TargetSummary findTargetSummaryEventInBuildEventStream(
      File bep) throws IOException {
    for (BuildEvent buildEvent : parseBuildEventsFromBuildEventStream(bep)) {
      if (buildEvent.getId().getIdCase() == IdCase.TARGET_SUMMARY) {
        return buildEvent.getTargetSummary();
      }
    }
    return null;
  }
}
