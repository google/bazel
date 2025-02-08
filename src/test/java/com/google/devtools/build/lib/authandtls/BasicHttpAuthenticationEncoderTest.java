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
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.devtools.build.lib.util.StringEncoding;
import java.util.Base64;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link BasicHttpAuthenticationEncoder}. */
@RunWith(JUnit4.class)
public class BasicHttpAuthenticationEncoderTest {

  private static String[] decode(String message) {
    String base64EncodedMessage = message.substring(6);
    String usernameAndPassword =
        new String(Base64.getDecoder().decode(base64EncodedMessage), UTF_8);
    return usernameAndPassword.split(":", 2);
  }

  @Test
  public void encode_normalUsernamePassword_outputExpected() {
    String message = BasicHttpAuthenticationEncoder.encode("Aladdin", "open sesame");
    assertThat(message).isEqualTo("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==");
  }

  @Test
  public void encode_normalUsernamePassword_canBeDecoded() {
    String message = BasicHttpAuthenticationEncoder.encode("Aladdin", "open sesame");

    String[] usernameAndPassword = decode(message);
    assertThat(usernameAndPassword[0]).isEqualTo("Aladdin");
    assertThat(usernameAndPassword[1]).isEqualTo("open sesame");
  }

  @Test
  public void encode_usernameContainsColon_canBeDecoded() {
    String message = BasicHttpAuthenticationEncoder.encode("foo:user", "foopass");

    String[] usernameAndPassword = decode(message);
    assertThat(usernameAndPassword[0]).isEqualTo("foo");
    assertThat(usernameAndPassword[1]).isEqualTo("user:foopass");
  }

  @Test
  public void encode_emptyUsername_outputExpected() {
    String message = BasicHttpAuthenticationEncoder.encode("", "foopass");
    assertThat(message).isEqualTo("Basic OmZvb3Bhc3M=");
  }

  @Test
  public void encode_emptyPassword_outputExpected() {
    String message = BasicHttpAuthenticationEncoder.encode("foouser", "");
    assertThat(message).isEqualTo("Basic Zm9vdXNlcjo=");
  }

  @Test
  public void encode_emptyUsernamePassword_outputExpected() {
    String message = BasicHttpAuthenticationEncoder.encode("", "");
    assertThat(message).isEqualTo("Basic Og==");
  }

  @Test
  public void encode_specialCharacterUtf8_outputExpected() {
    String message =
        BasicHttpAuthenticationEncoder.encode(
            "test", StringEncoding.unicodeToInternal("123\u00A3"));
    assertThat(message).isEqualTo("Basic dGVzdDoxMjPCow==");
  }
}
