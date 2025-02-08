// Copyright 2022 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.authandtls.credentialhelper;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.util.FileSystems;
import com.google.devtools.build.runfiles.Runfiles;
import java.net.URI;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CredentialHelperTest {
  private static final PathFragment TEST_WORKSPACE_PATH =
      PathFragment.create(System.getenv("TEST_TMPDIR"));
  private static final PathFragment TEST_CREDENTIAL_HELPER_PATH =
      PathFragment.create(
          "io_bazel/src/test/java/com/google/devtools/build/lib/authandtls/credentialhelper/test_credential_helper"
              + (OS.getCurrent() == OS.WINDOWS ? ".exe" : ""));

  private static final Reporter reporter = new Reporter(new EventBus());

  private GetCredentialsResponse getCredentialsFromHelper(
      String credHelperPath, String uri, ImmutableMap<String, String> env) throws Exception {
    Preconditions.checkNotNull(credHelperPath);
    Preconditions.checkNotNull(uri);
    Preconditions.checkNotNull(env);

    FileSystem fs = FileSystems.getNativeFileSystem();

    CredentialHelper credentialHelper = new CredentialHelper(fs.getPath(credHelperPath));
    return credentialHelper.getCredentials(
        CredentialHelperEnvironment.newBuilder()
            .setEventReporter(reporter)
            .setWorkspacePath(fs.getPath(TEST_WORKSPACE_PATH))
            .setClientEnvironment(env)
            .setHelperExecutionTimeout(Duration.ofSeconds(5))
            .build(),
        URI.create(uri));
  }

  private GetCredentialsResponse getCredentialsFromHelper(
      String uri, ImmutableMap<String, String> env) throws Exception {
    String credHelperPath =
        Runfiles.create().rlocation(TEST_CREDENTIAL_HELPER_PATH.getPathString());

    return getCredentialsFromHelper(credHelperPath, uri, env);
  }

  private GetCredentialsResponse getCredentialsFromHelper(String uri) throws Exception {
    Preconditions.checkNotNull(uri);

    return getCredentialsFromHelper(uri, ImmutableMap.of());
  }

  @Test
  public void knownUriWithSingleHeader() throws Exception {
    GetCredentialsResponse response = getCredentialsFromHelper("https://singleheader.example.com");
    assertThat(response.headers()).containsExactly("header1", ImmutableList.of("value1"));
  }

  @Test
  public void knownUriWithMultipleHeaders() throws Exception {
    GetCredentialsResponse response =
        getCredentialsFromHelper("https://multipleheaders.example.com");
    assertThat(response.headers())
        .containsExactly(
            "header1",
            ImmutableList.of("value1"),
            "header2",
            ImmutableList.of("value1", "value2"),
            "header3",
            ImmutableList.of("value1", "value2", "value3"));
  }

  @Test
  public void unknownUri() {
    CredentialHelperException e =
        assertThrows(
            CredentialHelperException.class,
            () -> getCredentialsFromHelper("https://unknown.example.com"));
    assertThat(e).hasMessageThat().contains("Failed to get credentials");
    assertThat(e).hasMessageThat().contains("Unknown uri 'https://unknown.example.com'");
  }

  @Test
  public void credentialHelperOutputsNothing() throws Exception {
    CredentialHelperException e =
        assertThrows(
            CredentialHelperException.class,
            () -> getCredentialsFromHelper("https://printnothing.example.com"));
    assertThat(e).hasMessageThat().contains("Failed to get credentials");
    assertThat(e).hasMessageThat().contains("exited without output");
  }

  @Test
  public void credentialHelperOutputsExtraFields() throws Exception {
    GetCredentialsResponse response = getCredentialsFromHelper("https://extrafields.example.com");
    assertThat(response.headers()).containsExactly("header1", ImmutableList.of("value1"));
  }

  @Test
  public void helperRunsInWorkspace() throws Exception {
    GetCredentialsResponse response = getCredentialsFromHelper("https://cwd.example.com");
    ImmutableMap<String, ImmutableList<String>> headers = response.headers();
    assertThat(PathFragment.create(headers.get("cwd").get(0))).isEqualTo(TEST_WORKSPACE_PATH);
  }

  @Test
  public void helperGetEnvironment() throws Exception {
    GetCredentialsResponse response =
        getCredentialsFromHelper(
            "https://env.example.com", ImmutableMap.of("FOO", "BAR!", "BAR", "123"));
    assertThat(response.headers())
        .containsExactly(
            "foo", ImmutableList.of("BAR!"),
            "bar", ImmutableList.of("123"));
  }

  @Test
  public void helperTimeout() throws Exception {
    CredentialHelperException e =
        assertThrows(
            CredentialHelperException.class,
            () -> getCredentialsFromHelper("https://timeout.example.com"));
    assertThat(e).hasMessageThat().contains("Failed to get credentials");
    assertThat(e).hasMessageThat().contains("process timed out");
  }

  @Test
  public void nonExistentHelper() throws Exception {
    CredentialHelperException e =
        assertThrows(
            CredentialHelperException.class,
            () ->
                getCredentialsFromHelper(
                    OS.getCurrent() == OS.WINDOWS ? "C:/no/such/file" : "/no/such/file",
                    "https://timeout.example.com",
                    ImmutableMap.of()));
    assertThat(e).hasMessageThat().contains("Failed to get credentials");
    assertThat(e)
        .hasMessageThat()
        .contains(
            OS.getCurrent().equals(OS.WINDOWS)
                ? "cannot find the file specified"
                : "Cannot run program");
  }

  @Test
  public void hugePayload() throws Exception {
    // Bazel reads the credential helper stdout/stderr from a pipe, and doesn't start reading
    // until the process terminates. Therefore, a response larger than the pipe buffer causes
    // a deadlock and timeout. This verifies that the pipe is sufficiently large.
    // See https://github.com/bazelbuild/bazel/issues/21287.
    GetCredentialsResponse response = getCredentialsFromHelper("https://hugepayload.example.com");
    assertThat(response.headers()).containsExactly("huge", ImmutableList.of("x".repeat(63 * 1024)));
  }
}
