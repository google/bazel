// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.repository.downloader;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.Assert.fail;

import com.google.auth.Credentials;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.authandtls.BasicHttpAuthenticationEncoder;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URL;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import net.starlark.java.syntax.Location;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link UrlRewriter} */
@RunWith(JUnit4.class)
public class UrlRewriterTest {

  @Test
  public void byDefaultTheUrlRewriterDoesNothing() throws Exception {
    UrlRewriter munger = new UrlRewriter(str -> {}, "/dev/null", new StringReader(""));

    List<URL> urls = ImmutableList.of(new URL("http://example.com"));
    ImmutableList<URL> amended =
        munger.amend(urls).stream().map(url -> url.url()).collect(toImmutableList());

    assertThat(amended).isEqualTo(urls);
  }

  @Test
  public void shouldBeAbleToBlockParticularHostsRegardlessOfScheme() throws Exception {
    String config = "block example.com";
    UrlRewriter munger = new UrlRewriter(str -> {}, "/dev/null", new StringReader(config));

    List<URL> urls =
        ImmutableList.of(
            new URL("http://example.com"),
            new URL("https://example.com"),
            new URL("http://localhost"));
    ImmutableList<URL> amended =
        munger.amend(urls).stream().map(url -> url.url()).collect(toImmutableList());

    assertThat(amended).containsExactly(new URL("http://localhost"));
  }

  @Test
  public void shouldAllowAUrlToBeRewritten() throws Exception {
    String config = "rewrite example.com/foo/(.*) mycorp.com/$1/foo";
    UrlRewriter munger = new UrlRewriter(str -> {}, "/dev/null", new StringReader(config));

    List<URL> urls = ImmutableList.of(new URL("https://example.com/foo/bar"));
    ImmutableList<URL> amended =
        munger.amend(urls).stream().map(url -> url.url()).collect(toImmutableList());

    assertThat(amended).containsExactly(new URL("https://mycorp.com/bar/foo"));
  }

  @Test
  public void rewritesCanExpandToMoreThanOneUrl() throws Exception {
    String config =
        "rewrite example.com/foo/(.*) mycorp.com/$1/somewhere\n"
            + "rewrite example.com/foo/(.*) mycorp.com/$1/elsewhere";
    UrlRewriter munger = new UrlRewriter(str -> {}, "/dev/null", new StringReader(config));

    List<URL> urls = ImmutableList.of(new URL("https://example.com/foo/bar"));
    ImmutableList<URL> amended =
        munger.amend(urls).stream().map(url -> url.url()).collect(toImmutableList());

    // There's no guarantee about the ordering of the rewrites
    assertThat(amended).contains(new URL("https://mycorp.com/bar/somewhere"));
    assertThat(amended).contains(new URL("https://mycorp.com/bar/elsewhere"));
  }

  @Test
  public void shouldBlockAllUrlsOtherThanSpecificOnes() throws Exception {
    String config = "" + "block *\n" + "allow example.com";

    UrlRewriter munger = new UrlRewriter(str -> {}, "/dev/null", new StringReader(config));

    List<URL> urls =
        ImmutableList.of(
            new URL("https://foo.com"),
            new URL("https://example.com/foo/bar"),
            new URL("https://subdomain.example.com/qux"));
    ImmutableList<URL> amended =
        munger.amend(urls).stream().map(url -> url.url()).collect(toImmutableList());

    assertThat(amended)
        .containsExactly(
            new URL("https://example.com/foo/bar"), new URL("https://subdomain.example.com/qux"));
  }

  @Test
  public void commentsArePrecededByTheHashCharacter() throws Exception {
    String config =
        ""
            + "# Block everything\n"
            + "block *\n"
            + "# But allow example.com\n"
            + "allow example.com";

    UrlRewriter munger = new UrlRewriter(str -> {}, "/dev/null", new StringReader(config));

    List<URL> urls = ImmutableList.of(new URL("https://foo.com"), new URL("https://example.com"));
    ImmutableList<URL> amended =
        munger.amend(urls).stream().map(url -> url.url()).collect(toImmutableList());

    assertThat(amended).containsExactly(new URL("https://example.com"));
  }

  @Test
  public void allowListAppliesToSubdomainsToo() throws Exception {
    String config = "" + "block *\n" + "allow example.com";

    UrlRewriter munger = new UrlRewriter(str -> {}, "/dev/null", new StringReader(config));

    ImmutableList<URL> amended =
        munger.amend(ImmutableList.of(new URL("https://subdomain.example.com"))).stream()
            .map(url -> url.url())
            .collect(toImmutableList());

    assertThat(amended).containsExactly(new URL("https://subdomain.example.com"));
  }

  @Test
  public void blockListAppliesToSubdomainsToo() throws Exception {
    String config = "block example.com";

    UrlRewriter munger = new UrlRewriter(str -> {}, "/dev/null", new StringReader(config));

    ImmutableList<URL> amended =
        munger.amend(ImmutableList.of(new URL("https://subdomain.example.com"))).stream()
            .map(url -> url.url())
            .collect(toImmutableList());

    assertThat(amended).isEmpty();
  }

  @Test
  public void emptyLinesAreFine() throws Exception {
    String config = "" + "\n" + "   \n" + "block *\n" + "\t  \n" + "allow example.com";

    UrlRewriter munger = new UrlRewriter(str -> {}, "/dev/null", new StringReader(config));

    ImmutableList<URL> amended =
        munger.amend(ImmutableList.of(new URL("https://subdomain.example.com"))).stream()
            .map(url -> url.url())
            .collect(toImmutableList());

    assertThat(amended).containsExactly(new URL("https://subdomain.example.com"));
  }

  @Test
  public void rewritingUrlsIsAppliedBeforeBlocking() throws Exception {
    String config = "" + "block bad.com\n" + "rewrite bad.com/foo/(.*) mycorp.com/$1";

    UrlRewriter munger = new UrlRewriter(str -> {}, "/dev/null", new StringReader(config));

    List<URL> amended =
        munger
            .amend(
                ImmutableList.of(
                    new URL("https://www.bad.com"), new URL("https://bad.com/foo/bar")))
            .stream()
            .map(url -> url.url())
            .collect(toImmutableList());

    assertThat(amended).containsExactly(new URL("https://mycorp.com/bar"));
  }

  @Test
  public void rewritingUrlsIsAppliedBeforeAllowing() throws Exception {
    String config =
        "" + "block *\n" + "allow mycorp.com\n" + "rewrite bad.com/foo/(.*) mycorp.com/$1";

    UrlRewriter munger = new UrlRewriter(str -> {}, "/dev/null", new StringReader(config));

    List<URL> amended =
        munger
            .amend(
                ImmutableList.of(
                    new URL("https://www.bad.com"), new URL("https://bad.com/foo/bar")))
            .stream()
            .map(url -> url.url())
            .collect(toImmutableList());

    assertThat(amended).containsExactly(new URL("https://mycorp.com/bar"));
  }

  @Test
  public void parseError() throws Exception {
    String config = "#comment\nhello";
    try {
      new UrlRewriterConfig("/some/file", new StringReader(config));
      fail();
    } catch (UrlRewriterParseException e) {
      assertThat(e.getLocation()).isEqualTo(Location.fromFileLineColumn("/some/file", 2, 0));
    }
  }

  @Test
  public void noAllBlockedMessage() throws Exception {
    String config = "";
    UrlRewriterConfig munger = new UrlRewriterConfig("/some/file", new StringReader(config));
    assertThat(munger.getAllBlockedMessage()).isNull();
  }

  @Test
  public void singleAllBlockedMessage() throws Exception {
    String config =
        "all_blocked_message I'm sorry Dave, I'm afraid I can't do that.\n" + "allow *\n";
    UrlRewriterConfig munger = new UrlRewriterConfig("/some/file", new StringReader(config));
    assertThat(munger.getAllBlockedMessage())
        .isEqualTo("I'm sorry Dave, I'm afraid I can't do that.");
  }

  @Test
  public void multipleAllBlockedMessage() throws Exception {
    String config = "all_blocked_message one\n" + "block *\n" + "all_blocked_message two\n";
    try {
      new UrlRewriterConfig("/some/file", new StringReader(config));
      fail();
    } catch (UrlRewriterParseException e) {
      assertThat(e.getLocation()).isEqualTo(Location.fromFileLineColumn("/some/file", 3, 0));
    }
  }

  @Test
  public void rewritingUrlsAllowsProtocolRewrite() throws Exception {
    String config =
        ""
            + "block *\n"
            + "allow mycorp.com\n"
            + "allow othercorp.com\n"
            + "rewrite bad.com/foo/(.*) http://mycorp.com/$1\n"
            + "rewrite bad.com/bar/(.*) https://othercorp.com/bar/$1\n";

    UrlRewriter munger = new UrlRewriter(str -> {}, "/dev/null", new StringReader(config));

    List<URL> amended =
        munger
            .amend(
                ImmutableList.of(
                    new URL("https://www.bad.com"),
                    new URL("https://bad.com/foo/bar"),
                    new URL("http://bad.com/bar/xyz")))
            .stream()
            .map(url -> url.url())
            .collect(toImmutableList());

    assertThat(amended)
        .containsExactly(
            new URL("http://mycorp.com/bar"), new URL("https://othercorp.com/bar/xyz"));
  }

  @Test
  public void rewritingUrlsWithAuthHeaders() throws Exception {
    String creds = "user:password";
    String firstNetrcCreds = "netrc_user_0:netrc_pw_0";
    String secondNetrcCreds = "netrc_user_1:netrc_pw_1";
    Credentials netrc =
        parseNetrc(
            "machine mycorp.com login netrc_user_0 password netrc_pw_0\n"
                + "machine myothercorp.com login netrc_user_1 password netrc_pw_1\n"
                + "machine no-override.com login netrc_user_2 password netrc_pw_2\n");
    String config =
        ""
            + "rewrite my.example.com/foo/(.*) "
            + creds
            + "@mycorp.com/foo/$1\n" // this cred should from download config file
            + "rewrite my.example.com/from_netrc/(.*) mycorp.com/from_netrc/$1\n" // this cred
            // should come
            // from netrc
            + "rewrite"
            + " my.example.com/from_other_netrc_entry/(.*)"
            + " myothercorp.com/from_netrc/$1\n" // this cred should come from netrc
            + "rewrite my.example.com/no_creds/(.*) myopencorp.com/no_creds/$1\n"; // should be
    // re-written,
    // but no auth
    // headers added

    UrlRewriter munger = new UrlRewriter(str -> {}, "/dev/null", new StringReader(config));

    ImmutableList<UrlRewriter.RewrittenURL> amended =
        munger.amend(
            ImmutableList.of(
                new URL("https://my.example.com/foo/bar"),
                new URL("https://my.example.com/from_netrc/bar"),
                new URL("https://my.example.com/from_other_netrc_entry/bar"),
                new URL("https://my.example.com/no_creds/bar"),
                new URL("https://my.example.com/no_creds/with_auth"),
                new URL("https://should-not-be-overridden.com/")));

    Map<String, List<String>> customAuthHeaders
        = ImmutableMap.of("Authorization", ImmutableList.of("MyToken"));
    Map<URI, Map<String, List<String>>> originalAuthHeaders 
        = ImmutableMap.of(new URI("https://my.example.com/no_creds/with_auth"), customAuthHeaders);
    Map<URI, Map<String, List<String>>> updatedAuthHeaders =
        munger.updateAuthHeaders(amended, originalAuthHeaders, netrc);

    String expectedToken =
        "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(ISO_8859_1));
    String expectedFirstNetrcToken =
        "Basic " + Base64.getEncoder().encodeToString(firstNetrcCreds.getBytes(ISO_8859_1));
    String expectedSecondNetrcToken =
        "Basic " + Base64.getEncoder().encodeToString(secondNetrcCreds.getBytes(ISO_8859_1));
    // The headers should be updated as following:
    // 1. Original auth headers remain
    // 2. The URL that was rewritten and had the entry in originalAuthHeaders should have a corresponding, 
    //    rewritten url entry.
    // 3. The urls that have user or netrc should have the corresponding entry in the updatedAuthHeaders.
    assertThat(updatedAuthHeaders)
        .containsExactly(
            new URI("https://my.example.com/no_creds/with_auth"), 
            customAuthHeaders,
            new URI("https://myopencorp.com/no_creds/with_auth"),
            customAuthHeaders,
            new URI("https://user:password@mycorp.com/foo/bar"),
            ImmutableMap.of("Authorization", ImmutableList.of(expectedToken)),
            new URI("https://mycorp.com/from_netrc/bar"),
            ImmutableMap.of("Authorization", ImmutableList.of(expectedFirstNetrcToken)),
            new URI("https://myothercorp.com/from_netrc/bar"),
            ImmutableMap.of("Authorization", ImmutableList.of(expectedSecondNetrcToken)));
    assertThat(amended)
        .containsExactly(
            UrlRewriter.RewrittenURL.create(
                new URL("https://my.example.com/foo/bar"),
                new URL("https://user:password@mycorp.com/foo/bar"), true),
            UrlRewriter.RewrittenURL.create(
                new URL("https://my.example.com/from_netrc/bar"),
                new URL("https://mycorp.com/from_netrc/bar"), true),
            UrlRewriter.RewrittenURL.create(
                new URL("https://my.example.com/from_other_netrc_entry/bar"),
                new URL("https://myothercorp.com/from_netrc/bar"), true),
            UrlRewriter.RewrittenURL.create(
                new URL("https://my.example.com/no_creds/bar"),
                new URL("https://myopencorp.com/no_creds/bar"), true),
            UrlRewriter.RewrittenURL.create(
                new URL("https://my.example.com/no_creds/with_auth"),
                new URL("https://myopencorp.com/no_creds/with_auth"), true),
            UrlRewriter.RewrittenURL.create(
                new URL("https://should-not-be-overridden.com/"),
                new URL("https://should-not-be-overridden.com/"), false));
  }

  @Test
  public void testNetrc_emptyEnv_shouldIgnore() throws Exception {
    ImmutableMap<String, String> clientEnv = ImmutableMap.of();
    Path workingDir = new InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/workdir");

    Credentials credentials = UrlRewriter.newCredentialsFromNetrc(clientEnv, workingDir);

    assertThat(credentials).isNull();
  }

  @Test
  public void testNetrc_netrcNotExist_shouldIgnore() throws Exception {
    String home = "/home/foo";
    ImmutableMap<String, String> clientEnv = ImmutableMap.of("HOME", home, "USERPROFILE", home);
    Path workingDir = new InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/workdir");

    Credentials credentials = UrlRewriter.newCredentialsFromNetrc(clientEnv, workingDir);

    assertThat(credentials).isNull();
  }

  @Test
  public void testNetrc_relativeNetrc_shouldUse() throws Exception {
    FileSystem fileSystem = new InMemoryFileSystem(DigestHashFunction.SHA256);
    Path workingDir = fileSystem.getPath("/workdir");
    Scratch scratch = new Scratch(fileSystem);
    scratch.file("/workdir/foo/.netrc", "machine foo.example.org login foouser password foopass");
    ImmutableMap<String, String> clientEnv = ImmutableMap.of("NETRC", "./foo/.netrc");

    Credentials credentials = UrlRewriter.newCredentialsFromNetrc(clientEnv, workingDir);

    assertRequestMetadata(
        credentials.getRequestMetadata(URI.create("https://foo.example.org")),
        "foouser",
        "foopass");
  }

  @Test
  public void testNetrc_relativeNetrc_shouldIgnoreWhenNotExist() throws Exception {
    FileSystem fileSystem = new InMemoryFileSystem(DigestHashFunction.SHA256);
    Path workingDir = fileSystem.getPath("/workdir");
    ImmutableMap<String, String> clientEnv = ImmutableMap.of("NETRC", "./foo/.netrc");

    Credentials credentials = UrlRewriter.newCredentialsFromNetrc(clientEnv, workingDir);

    assertThat(credentials).isNull();
  }

  @Test
  public void testNetrc_netrcExist_shouldUse() throws Exception {
    Credentials credentials = parseNetrc("machine foo.example.org login foouser password foopass");

    assertThat(credentials).isNotNull();
    assertRequestMetadata(
        credentials.getRequestMetadata(URI.create("https://foo.example.org")),
        "foouser",
        "foopass");
  }

  @Test
  public void testNetrc_netrcExist_cant_parse() throws Exception {
    String home = "/home/foo";
    ImmutableMap<String, String> clientEnv = ImmutableMap.of("HOME", home, "USERPROFILE", home);
    FileSystem fileSystem = new InMemoryFileSystem(DigestHashFunction.SHA256);
    Scratch scratch = new Scratch(fileSystem);
    scratch.file(home + "/.netrc", "mach foo.example.org log foouser password foopass");

    try {
      UrlRewriter.newCredentialsFromNetrc(clientEnv, fileSystem.getPath("/workdir"));
      fail();
    } catch (UrlRewriterParseException e) {
      assertThat(e.getLocation()).isEqualTo(Location.fromFileLineColumn("/home/foo/.netrc", 0, 0));
    }
  }

  private static Credentials parseNetrc(String content)
      throws IOException, UrlRewriterParseException {
    String home = "/home/foo";
    ImmutableMap<String, String> clientEnv = ImmutableMap.of("HOME", home, "USERPROFILE", home);
    FileSystem fileSystem = new InMemoryFileSystem(DigestHashFunction.SHA256);
    Path workingDir = fileSystem.getPath("/workdir");
    Scratch scratch = new Scratch(fileSystem);
    scratch.file(home + "/.netrc", content);

    return UrlRewriter.newCredentialsFromNetrc(clientEnv, workingDir);
  }

  private static void assertRequestMetadata(
      Map<String, List<String>> requestMetadata, String username, String password) {
    assertThat(requestMetadata.keySet()).containsExactly("Authorization");
    assertThat(Iterables.getOnlyElement(requestMetadata.values()))
        .containsExactly(BasicHttpAuthenticationEncoder.encode(username, password));
  }
}
