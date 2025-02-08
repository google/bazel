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

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.net.URI;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link GetCredentialsRequest}. */
@RunWith(JUnit4.class)
public class GetCredentialsRequestTest {
  private static final Gson GSON = new Gson();

  @Test
  public void parseValid() {
    assertThat(
            GSON.fromJson("{\"uri\": \"http://example.com\"}", GetCredentialsRequest.class).uri())
        .isEqualTo(URI.create("http://example.com"));
    assertThat(
            GSON.fromJson("{\"uri\": \"https://example.com\"}", GetCredentialsRequest.class).uri())
        .isEqualTo(URI.create("https://example.com"));
    assertThat(
            GSON.fromJson("{\"uri\": \"grpc://example.com\"}", GetCredentialsRequest.class).uri())
        .isEqualTo(URI.create("grpc://example.com"));
    assertThat(
            GSON.fromJson("{\"uri\": \"grpcs://example.com\"}", GetCredentialsRequest.class).uri())
        .isEqualTo(URI.create("grpcs://example.com"));

    assertThat(
            GSON.fromJson("{\"uri\": \"uri-without-protocol\"}", GetCredentialsRequest.class).uri())
        .isEqualTo(URI.create("uri-without-protocol"));
  }

  @Test
  public void parseMissingUri() {
    assertThrows(JsonSyntaxException.class, () -> GSON.fromJson("{}", GetCredentialsRequest.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"foo\": 1}", GetCredentialsRequest.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"foo\": 1, \"bar\": 2}", GetCredentialsRequest.class));
  }

  @Test
  public void parseNonStringUri() {
    assertThrows(JsonSyntaxException.class, () -> GSON.fromJson("[]", GetCredentialsRequest.class));
    assertThrows(
        JsonSyntaxException.class, () -> GSON.fromJson("\"foo\"", GetCredentialsRequest.class));
    assertThrows(JsonSyntaxException.class, () -> GSON.fromJson("1", GetCredentialsRequest.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"uri\": 1}", GetCredentialsRequest.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"uri\": {}}", GetCredentialsRequest.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"uri\": []}", GetCredentialsRequest.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"uri\": [\"https://example.com\"]}", GetCredentialsRequest.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"uri\": null}", GetCredentialsRequest.class));
  }

  @Test
  public void parseWithExtraFields() {
    assertThat(
            GSON.fromJson(
                    "{\"uri\": \"http://example.com\", \"foo\": 1}", GetCredentialsRequest.class)
                .uri())
        .isEqualTo(URI.create("http://example.com"));
    assertThat(
            GSON.fromJson(
                    "{\"foo\": 1, \"uri\": \"http://example.com\"}", GetCredentialsRequest.class)
                .uri())
        .isEqualTo(URI.create("http://example.com"));
    assertThat(
            GSON.fromJson(
                    "{\"uri\": \"http://example.com\", \"foo\": 1, \"bar\": {}}",
                    GetCredentialsRequest.class)
                .uri())
        .isEqualTo(URI.create("http://example.com"));
    assertThat(
            GSON.fromJson(
                    "{\"foo\": 1, \"uri\": \"http://example.com\", \"bar\": []}",
                    GetCredentialsRequest.class)
                .uri())
        .isEqualTo(URI.create("http://example.com"));
  }
}
