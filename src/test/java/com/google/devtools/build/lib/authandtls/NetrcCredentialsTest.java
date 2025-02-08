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
package com.google.devtools.build.lib.authandtls;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.authandtls.Netrc.Credential;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link NetrcCredentials}. */
@RunWith(JUnit4.class)
public class NetrcCredentialsTest {

  private static final String FOO_MACHINE = "foo.example.org";
  private static final Credential FOO_CREDENTIAL =
      Credential.builder(FOO_MACHINE).setLogin("foouser").setPassword("foopass").build();
  private static final String BAR_MACHINE = "bar.example.org";
  private static final Credential DEFAULT_CREDENTIAL =
      Credential.builder("default").setLogin("defaultuser").setPassword("defaultpass").build();

  @Test
  public void getRequestMetadata_emptyNetrc_returnEmpty() throws IOException {
    Netrc netrc = Netrc.create(null, ImmutableMap.of());
    NetrcCredentials netrcCredentials = new NetrcCredentials(netrc);

    Map<String, List<String>> requestMetadata =
        netrcCredentials.getRequestMetadata(URI.create("https://example.org"));

    assertThat(requestMetadata).isEmpty();
  }

  @Test
  public void getRequestMetadata_matchedMachine_returnMatchedOne() throws IOException {
    Netrc netrc = Netrc.create(null, ImmutableMap.of(FOO_MACHINE, FOO_CREDENTIAL));
    NetrcCredentials netrcCredentials = new NetrcCredentials(netrc);

    Map<String, List<String>> fooRequestMetadata =
        netrcCredentials.getRequestMetadata(URI.create("https://" + FOO_MACHINE));

    assertRequestMetadata(fooRequestMetadata, FOO_CREDENTIAL.login(), FOO_CREDENTIAL.password());
  }

  @Test
  public void getRequestMetadata_notMatchedMachine_returnEmpty() throws IOException {
    Netrc netrc = Netrc.create(null, ImmutableMap.of(FOO_MACHINE, FOO_CREDENTIAL));
    NetrcCredentials netrcCredentials = new NetrcCredentials(netrc);

    Map<String, List<String>> barRequestMetadata =
        netrcCredentials.getRequestMetadata(URI.create("https://" + BAR_MACHINE));

    assertThat(barRequestMetadata).isEmpty();
  }

  @Test
  public void getRequestMetadata_notMatchedMachine_returnDefault() throws IOException {
    Netrc netrc = Netrc.create(DEFAULT_CREDENTIAL, ImmutableMap.of(FOO_MACHINE, FOO_CREDENTIAL));
    NetrcCredentials netrcCredentials = new NetrcCredentials(netrc);

    Map<String, List<String>> barRequestMetadata =
        netrcCredentials.getRequestMetadata(URI.create("https://" + BAR_MACHINE));

    assertRequestMetadata(
        barRequestMetadata, DEFAULT_CREDENTIAL.login(), DEFAULT_CREDENTIAL.password());
  }

  @Test
  public void getRequestMetadata_emptyLogin() throws IOException {
    Netrc netrc =
        Netrc.create(
            null,
            ImmutableMap.of(
                FOO_MACHINE,
                Credential.builder(FOO_MACHINE).setPassword(FOO_CREDENTIAL.password()).build()));
    NetrcCredentials netrcCredentials = new NetrcCredentials(netrc);

    Map<String, List<String>> fooRequestMetadata =
        netrcCredentials.getRequestMetadata(URI.create("https://" + FOO_MACHINE));

    assertRequestMetadata(fooRequestMetadata, "", FOO_CREDENTIAL.password());
  }

  @Test
  public void getRequestMetadata_emptyPassword() throws IOException {
    Netrc netrc =
        Netrc.create(
            null,
            ImmutableMap.of(
                FOO_MACHINE,
                Credential.builder(FOO_MACHINE).setLogin(FOO_CREDENTIAL.login()).build()));
    NetrcCredentials netrcCredentials = new NetrcCredentials(netrc);

    Map<String, List<String>> fooRequestMetadata =
        netrcCredentials.getRequestMetadata(URI.create("https://" + FOO_MACHINE));

    assertRequestMetadata(fooRequestMetadata, FOO_CREDENTIAL.login(), "");
  }

  @Test
  public void getRequestMetadata_emptyLoginAndPassword() throws IOException {
    Netrc netrc =
        Netrc.create(null, ImmutableMap.of(FOO_MACHINE, Credential.builder(FOO_MACHINE).build()));
    NetrcCredentials netrcCredentials = new NetrcCredentials(netrc);

    Map<String, List<String>> fooRequestMetadata =
        netrcCredentials.getRequestMetadata(URI.create("https://" + FOO_MACHINE));

    assertRequestMetadata(fooRequestMetadata, "", "");
  }

  private static void assertRequestMetadata(
      Map<String, List<String>> requestMetadata, String username, String password) {
    assertThat(requestMetadata.keySet()).containsExactly("Authorization");
    assertThat(Iterables.getOnlyElement(requestMetadata.values()))
        .containsExactly(BasicHttpAuthenticationEncoder.encode(username, password));
  }
}
