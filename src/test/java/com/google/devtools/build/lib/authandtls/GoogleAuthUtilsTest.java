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

package com.google.devtools.build.lib.authandtls;

import static com.google.common.truth.Truth.assertThat;

import com.google.auth.Credentials;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelperEnvironment;
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelperProvider;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.runtime.CommandLinePathFactory;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.OptionsParsingException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GoogleAuthUtilsTest {
  @Test
  public void testNetrc_emptyEnv_shouldIgnore() throws Exception {
    ImmutableMap<String, String> clientEnv = ImmutableMap.of();
    FileSystem fileSystem = new InMemoryFileSystem(DigestHashFunction.SHA256);

    assertThat(GoogleAuthUtils.newCredentialsFromNetrc(clientEnv, fileSystem)).isEmpty();
  }

  @Test
  public void testNetrc_netrcNotExist_shouldIgnore() throws Exception {
    String home = "/home/foo";
    ImmutableMap<String, String> clientEnv = ImmutableMap.of("HOME", home);
    FileSystem fileSystem = new InMemoryFileSystem(DigestHashFunction.SHA256);

    assertThat(GoogleAuthUtils.newCredentialsFromNetrc(clientEnv, fileSystem)).isEmpty();
  }

  @Test
  public void testNetrc_netrcExist_shouldUse() throws Exception {
    String home = "/home/foo";
    ImmutableMap<String, String> clientEnv = ImmutableMap.of("HOME", home);
    FileSystem fileSystem = new InMemoryFileSystem(DigestHashFunction.SHA256);
    Scratch scratch = new Scratch(fileSystem);
    scratch.file(home + "/.netrc", "machine foo.example.org login foouser password foopass");

    Optional<Credentials> credentials =
        GoogleAuthUtils.newCredentialsFromNetrc(clientEnv, fileSystem);

    assertThat(credentials).isPresent();
    assertRequestMetadata(
        credentials.get().getRequestMetadata(URI.create("https://foo.example.org")),
        "foouser",
        "foopass");
  }

  @Test
  public void testNetrc_netrcFromNetrcEnvExist_shouldUse() throws Exception {
    String home = "/home/foo";
    String netrc = "/.netrc";
    ImmutableMap<String, String> clientEnv = ImmutableMap.of("HOME", home, "NETRC", netrc);
    FileSystem fileSystem = new InMemoryFileSystem(DigestHashFunction.SHA256);
    Scratch scratch = new Scratch(fileSystem);
    scratch.file(home + "/.netrc", "machine foo.example.org login foouser password foopass");
    scratch.file(netrc, "machine foo.example.org login baruser password barpass");

    Optional<Credentials> credentials =
        GoogleAuthUtils.newCredentialsFromNetrc(clientEnv, fileSystem);

    assertThat(credentials).isPresent();
    assertRequestMetadata(
        credentials.get().getRequestMetadata(URI.create("https://foo.example.org")),
        "baruser",
        "barpass");
  }

  @Test
  public void testNetrc_netrcFromNetrcEnvNotExist_shouldIgnore() throws Exception {
    String home = "/home/foo";
    String netrc = "/.netrc";
    ImmutableMap<String, String> clientEnv = ImmutableMap.of("HOME", home, "NETRC", netrc);
    FileSystem fileSystem = new InMemoryFileSystem(DigestHashFunction.SHA256);
    Scratch scratch = new Scratch(fileSystem);
    scratch.file(home + "/.netrc", "machine foo.example.org login foouser password foopass");

    assertThat(GoogleAuthUtils.newCredentialsFromNetrc(clientEnv, fileSystem)).isEmpty();
  }

  @Test
  public void testCredentialHelperProvider() throws Exception {
    FileSystem fileSystem = new InMemoryFileSystem(DigestHashFunction.SHA256);

    Path workspace = fileSystem.getPath("/workspace");
    Path pathValue = fileSystem.getPath("/usr/local/bin");
    pathValue.createDirectoryAndParents();

    CredentialHelperEnvironment credentialHelperEnvironment =
        CredentialHelperEnvironment.newBuilder()
            .setEventReporter(new Reporter(new EventBus()))
            .setWorkspacePath(workspace)
            .setClientEnvironment(ImmutableMap.of("PATH", pathValue.getPathString()))
            .setHelperExecutionTimeout(Duration.ZERO)
            .build();
    CommandLinePathFactory commandLinePathFactory =
        new CommandLinePathFactory(fileSystem, ImmutableMap.of("workspace", workspace));

    Path unusedHelper = createExecutable(fileSystem, "/unused/helper");

    Path defaultHelper = createExecutable(fileSystem, "/default/helper");
    Path exampleComHelper = createExecutable(fileSystem, "/example/com/helper");
    Path fooExampleComHelper = createExecutable(fileSystem, "/foo/example/com/helper");
    Path exampleComWildcardHelper = createExecutable(fileSystem, "/example/com/wildcard/helper");

    Path exampleOrgHelper = createExecutable(workspace.getRelative("helpers/example-org"));

    // No helpers.
    CredentialHelperProvider credentialHelperProvider1 =
        newCredentialHelperProvider(
            credentialHelperEnvironment, commandLinePathFactory, ImmutableList.of());
    assertThat(credentialHelperProvider1.findCredentialHelper(URI.create("https://example.com")))
        .isEmpty();
    assertThat(
            credentialHelperProvider1.findCredentialHelper(URI.create("https://foo.example.com")))
        .isEmpty();

    // Default helper only.
    CredentialHelperProvider credentialHelperProvider2 =
        newCredentialHelperProvider(
            credentialHelperEnvironment,
            commandLinePathFactory,
            ImmutableList.of(defaultHelper.getPathString()));
    assertThat(
            credentialHelperProvider2
                .findCredentialHelper(URI.create("https://example.com"))
                .get()
                .getPath())
        .isEqualTo(defaultHelper);
    assertThat(
            credentialHelperProvider2
                .findCredentialHelper(URI.create("https://foo.example.com"))
                .get()
                .getPath())
        .isEqualTo(defaultHelper);

    // Default and exact match.
    CredentialHelperProvider credentialHelperProvider3 =
        newCredentialHelperProvider(
            credentialHelperEnvironment,
            commandLinePathFactory,
            ImmutableList.of(
                defaultHelper.getPathString(), "example.com=" + exampleComHelper.getPathString()));
    assertThat(
            credentialHelperProvider3
                .findCredentialHelper(URI.create("https://example.com"))
                .get()
                .getPath())
        .isEqualTo(exampleComHelper);
    assertThat(
            credentialHelperProvider3
                .findCredentialHelper(URI.create("https://foo.example.com"))
                .get()
                .getPath())
        .isEqualTo(defaultHelper);

    // Exact match without default.
    CredentialHelperProvider credentialHelperProvider4 =
        newCredentialHelperProvider(
            credentialHelperEnvironment,
            commandLinePathFactory,
            ImmutableList.of("example.com=" + exampleComHelper.getPathString()));
    assertThat(
            credentialHelperProvider4
                .findCredentialHelper(URI.create("https://example.com"))
                .get()
                .getPath())
        .isEqualTo(exampleComHelper);
    assertThat(
            credentialHelperProvider4.findCredentialHelper(URI.create("https://foo.example.com")))
        .isEmpty();

    // Multiple scoped helpers with default.
    CredentialHelperProvider credentialHelperProvider5 =
        newCredentialHelperProvider(
            credentialHelperEnvironment,
            commandLinePathFactory,
            ImmutableList.of(
                defaultHelper.getPathString(),
                "example.com=" + exampleComHelper.getPathString(),
                "*.foo.example.com=" + fooExampleComHelper.getPathString(),
                "*.example.com=" + exampleComWildcardHelper.getPathString(),
                "example.org=%workspace%/helpers/example-org"));
    assertThat(
            credentialHelperProvider5
                .findCredentialHelper(URI.create("https://anotherdomain.com"))
                .get()
                .getPath())
        .isEqualTo(defaultHelper);
    assertThat(
            credentialHelperProvider5
                .findCredentialHelper(URI.create("https://example.com"))
                .get()
                .getPath())
        .isEqualTo(exampleComHelper);
    assertThat(
            credentialHelperProvider5
                .findCredentialHelper(URI.create("https://foo.example.com"))
                .get()
                .getPath())
        .isEqualTo(fooExampleComHelper);
    assertThat(
            credentialHelperProvider5
                .findCredentialHelper(URI.create("https://abc.foo.example.com"))
                .get()
                .getPath())
        .isEqualTo(fooExampleComHelper);
    assertThat(
            credentialHelperProvider5
                .findCredentialHelper(URI.create("https://bar.example.com"))
                .get()
                .getPath())
        .isEqualTo(exampleComWildcardHelper);
    assertThat(
            credentialHelperProvider5
                .findCredentialHelper(URI.create("https://abc.bar.example.com"))
                .get()
                .getPath())
        .isEqualTo(exampleComWildcardHelper);
    assertThat(
            credentialHelperProvider5
                .findCredentialHelper(URI.create("https://example.org"))
                .get()
                .getPath())
        .isEqualTo(exampleOrgHelper);

    // Helpers override.
    CredentialHelperProvider credentialHelperProvider6 =
        newCredentialHelperProvider(
            credentialHelperEnvironment,
            commandLinePathFactory,
            ImmutableList.of(
                // <system .bazelrc>
                unusedHelper.getPathString(),

                // <user .bazelrc>
                defaultHelper.getPathString(),
                "example.com=" + unusedHelper.getPathString(),
                "*.example.com=" + unusedHelper.getPathString(),
                "example.org=" + unusedHelper.getPathString(),
                "*.example.org=" + exampleOrgHelper.getPathString(),

                // <workspace .bazelrc>
                "*.example.com=" + exampleComWildcardHelper.getPathString(),
                "example.org=" + exampleOrgHelper.getPathString(),
                "*.foo.example.com=" + unusedHelper.getPathString(),

                // <command-line>
                "example.com=" + exampleComHelper.getPathString(),
                "*.foo.example.com=" + fooExampleComHelper.getPathString()));
    assertThat(
            credentialHelperProvider6
                .findCredentialHelper(URI.create("https://anotherdomain.com"))
                .get()
                .getPath())
        .isEqualTo(defaultHelper);
    assertThat(
            credentialHelperProvider6
                .findCredentialHelper(URI.create("https://example.com"))
                .get()
                .getPath())
        .isEqualTo(exampleComHelper);
    assertThat(
            credentialHelperProvider6
                .findCredentialHelper(URI.create("https://foo.example.com"))
                .get()
                .getPath())
        .isEqualTo(fooExampleComHelper);
    assertThat(
            credentialHelperProvider6
                .findCredentialHelper(URI.create("https://bar.example.com"))
                .get()
                .getPath())
        .isEqualTo(exampleComWildcardHelper);
    assertThat(
            credentialHelperProvider6
                .findCredentialHelper(URI.create("https://example.org"))
                .get()
                .getPath())
        .isEqualTo(exampleOrgHelper);
    assertThat(
            credentialHelperProvider6
                .findCredentialHelper(URI.create("https://foo.example.org"))
                .get()
                .getPath())
        .isEqualTo(exampleOrgHelper);
  }

  private static Path createExecutable(FileSystem fileSystem, String path) throws IOException {
    Preconditions.checkNotNull(fileSystem);
    Preconditions.checkNotNull(path);

    return createExecutable(fileSystem.getPath(path));
  }

  private static Path createExecutable(Path path) throws IOException {
    Preconditions.checkNotNull(path);

    path.getParentDirectory().createDirectoryAndParents();
    try (OutputStream unused = path.getOutputStream()) {
      // Nothing to do.
    }
    path.setExecutable(true);

    return path;
  }

  private static void assertRequestMetadata(
      Map<String, List<String>> requestMetadata, String username, String password) {
    assertThat(requestMetadata.keySet()).containsExactly("Authorization");
    assertThat(Iterables.getOnlyElement(requestMetadata.values()))
        .containsExactly(BasicHttpAuthenticationEncoder.encode(username, password));
  }

  private static CredentialHelperProvider newCredentialHelperProvider(
      CredentialHelperEnvironment credentialHelperEnvironment,
      CommandLinePathFactory commandLinePathFactory,
      ImmutableList<String> inputs)
      throws Exception {
    Preconditions.checkNotNull(credentialHelperEnvironment);
    Preconditions.checkNotNull(commandLinePathFactory);
    Preconditions.checkNotNull(inputs);

    return GoogleAuthUtils.newCredentialHelperProvider(
        credentialHelperEnvironment,
        commandLinePathFactory,
        ImmutableList.copyOf(Iterables.transform(inputs, s -> createCredentialHelperOption(s))));
  }

  private static AuthAndTLSOptions.CredentialHelperOption createCredentialHelperOption(
      String input) {
    Preconditions.checkNotNull(input);

    try {
      return AuthAndTLSOptions.CredentialHelperOptionConverter.INSTANCE.convert(input);
    } catch (OptionsParsingException e) {
      throw new IllegalStateException(e);
    }
  }
}
