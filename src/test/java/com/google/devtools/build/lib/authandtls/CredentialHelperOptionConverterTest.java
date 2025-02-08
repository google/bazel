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
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions.CredentialHelperOption;
import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions.CredentialHelperOptionConverter;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test for {@link CredentialHelperOptionConverter}. */
@RunWith(TestParameterInjector.class)
public class CredentialHelperOptionConverterTest {

  @Test
  public void exactScope() throws Exception {
    CredentialHelperOption helper1 =
        CredentialHelperOptionConverter.INSTANCE.convert("example.com=foo");
    assertThat(helper1.scope()).hasValue("example.com");
    assertThat(helper1.path()).isEqualTo("foo");
  }

  @Test
  public void wildcardScope() throws Exception {
    CredentialHelperOption helper1 =
        CredentialHelperOptionConverter.INSTANCE.convert("*.example.com=foo");
    assertThat(helper1.scope()).hasValue("*.example.com");
    assertThat(helper1.path()).isEqualTo("foo");
  }

  @Test
  public void punycodeScope() throws Exception {
    CredentialHelperOption helper1 =
        CredentialHelperOptionConverter.INSTANCE.convert("münchen.de=foo");
    assertThat(helper1.scope()).hasValue("xn--mnchen-3ya.de");
    assertThat(helper1.path()).isEqualTo("foo");

    CredentialHelperOption helper2 =
        CredentialHelperOptionConverter.INSTANCE.convert("*.köln.de=foo");
    assertThat(helper2.scope()).hasValue("*.xn--kln-sna.de");
    assertThat(helper2.path()).isEqualTo("foo");
  }

  @Test
  public void absolutePath() throws Exception {
    CredentialHelperOption helper1 =
        CredentialHelperOptionConverter.INSTANCE.convert("/absolute/path");
    assertThat(helper1.scope()).isEmpty();
    assertThat(helper1.path()).isEqualTo("/absolute/path");
  }

  @Test
  public void rootRelativePath() throws Exception {
    CredentialHelperOption helper1 =
        CredentialHelperOptionConverter.INSTANCE.convert("%workspace%/path");
    assertThat(helper1.scope()).isEmpty();
    assertThat(helper1.path()).isEqualTo("%workspace%/path");
  }

  @Test
  public void pathLookup() throws Exception {
    CredentialHelperOption helper1 = CredentialHelperOptionConverter.INSTANCE.convert("foo");
    assertThat(helper1.scope()).isEmpty();
    assertThat(helper1.path()).isEqualTo("foo");
  }

  @Test
  public void emptyOption() {
    Throwable t =
        assertThrows(
            OptionsParsingException.class,
            () -> CredentialHelperOptionConverter.INSTANCE.convert(""));
    assertThat(t).hasMessageThat().contains("Credential helper path must not be empty");
  }

  @Test
  public void emptyScope() throws Exception {
    Throwable t =
        assertThrows(
            OptionsParsingException.class,
            () -> CredentialHelperOptionConverter.INSTANCE.convert("=/foo"));
    assertThat(t).hasMessageThat().contains("Credential helper scope must not be empty");
  }

  @Test
  public void emptyPath() {
    Throwable t =
        assertThrows(
            OptionsParsingException.class,
            () -> CredentialHelperOptionConverter.INSTANCE.convert("foo="));
    assertThat(t).hasMessageThat().contains("Credential helper path must not be empty");
  }

  @Test
  public void emptyScopeAndPath() throws Exception {
    Throwable t =
        assertThrows(
            OptionsParsingException.class,
            () -> CredentialHelperOptionConverter.INSTANCE.convert("="));
    assertThat(t).hasMessageThat().contains("Credential helper scope must not be empty");
  }

  @Test
  public void invalidScope(
      @TestParameter({
            "-example.com",
            "example-.com",
            "example!.com",
            "*.",
            "example.*",
            "*.example.*",
            "foo.*.example.com",
            "*.foo.*.example.com",
            "*-foo.example.com",
            ".*.example.com",
            "foo.*.münchen.de",
            ".*.münchen.de",
            "*-foo.münchen.de"
          })
          String scope)
      throws Exception {
    Throwable t =
        assertThrows(
            OptionsParsingException.class,
            () -> CredentialHelperOptionConverter.INSTANCE.convert(scope + "=foo"));
    assertThat(t)
        .hasMessageThat()
        .contains(
            "Credential helper scope '"
                + scope
                + "' must be a valid domain name with an optional leading '*.' wildcard");
  }
}
