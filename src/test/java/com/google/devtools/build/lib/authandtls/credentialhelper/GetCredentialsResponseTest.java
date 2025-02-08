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

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link GetCredentialsResponse}. */
@RunWith(JUnit4.class)
public class GetCredentialsResponseTest {
  private static final Gson GSON = new Gson();

  @Test
  public void parseValid() {
    assertThat(GSON.fromJson("{}", GetCredentialsResponse.class).headers()).isEmpty();
    assertThat(GSON.fromJson("{\"headers\": {}}", GetCredentialsResponse.class).headers())
        .isEmpty();

    GetCredentialsResponse.Builder expectedResponseBuilder = GetCredentialsResponse.newBuilder();
    expectedResponseBuilder.headersBuilder().put("a", ImmutableList.of());
    expectedResponseBuilder.headersBuilder().put("b", ImmutableList.of("b"));
    expectedResponseBuilder.headersBuilder().put("c", ImmutableList.of("c", "c"));
    GetCredentialsResponse expectedResponse = expectedResponseBuilder.build();

    assertThat(
            GSON.fromJson(
                "{\"headers\": {\"c\": [\"c\", \"c\"], \"a\": [], \"b\": [\"b\"]}}",
                GetCredentialsResponse.class))
        .isEqualTo(expectedResponse);
  }

  @Test
  public void parseWithExtraFields() {
    assertThat(GSON.fromJson("{\"foo\": 123}", GetCredentialsResponse.class).headers()).isEmpty();
    assertThat(GSON.fromJson("{\"foo\": 123, \"bar\": []}", GetCredentialsResponse.class).headers())
        .isEmpty();

    GetCredentialsResponse.Builder expectedResponseBuilder = GetCredentialsResponse.newBuilder();
    expectedResponseBuilder.headersBuilder().put("a", ImmutableList.of());
    expectedResponseBuilder.headersBuilder().put("b", ImmutableList.of("b"));
    expectedResponseBuilder.headersBuilder().put("c", ImmutableList.of("c", "c"));
    GetCredentialsResponse expectedResponse = expectedResponseBuilder.build();

    assertThat(
            GSON.fromJson(
                "{\"foo\": 123, \"headers\": {\"c\": [\"c\", \"c\"], \"a\": [], \"b\": [\"b\"]},"
                    + " \"bar\": 123}",
                GetCredentialsResponse.class))
        .isEqualTo(expectedResponse);
  }

  @Test
  public void parseInvalid() {
    assertThrows(
        JsonSyntaxException.class, () -> GSON.fromJson("[]", GetCredentialsResponse.class));
    assertThrows(
        JsonSyntaxException.class, () -> GSON.fromJson("\"foo\"", GetCredentialsResponse.class));
    assertThrows(JsonSyntaxException.class, () -> GSON.fromJson("1", GetCredentialsResponse.class));
  }

  @Test
  public void parseInvalidHeadersEnvelope() {
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"headers\": null}", GetCredentialsResponse.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"headers\": \"foo\"}", GetCredentialsResponse.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"headers\": []}", GetCredentialsResponse.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"headers\": 1}", GetCredentialsResponse.class));
  }

  @Test
  public void parseInvalidHeadersValue() {
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"headers\": {\"a\": null}}", GetCredentialsResponse.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"headers\": {\"a\": 1}}", GetCredentialsResponse.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"headers\": {\"a\": {}}}", GetCredentialsResponse.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"headers\": {\"a\": \"a\"}}", GetCredentialsResponse.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"headers\": {\"a\": [null]}}", GetCredentialsResponse.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"headers\": {\"a\": [\"a\", null]}}", GetCredentialsResponse.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"headers\": {\"a\": [null, \"a\"]}}", GetCredentialsResponse.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"headers\": {\"a\": [\"a\", 1]}}", GetCredentialsResponse.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"headers\": {\"a\": [1, \"a\"]}}", GetCredentialsResponse.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"headers\": {\"a\": [\"a\", []]}}", GetCredentialsResponse.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"headers\": {\"a\": [[], \"a\"]}}", GetCredentialsResponse.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"headers\": {\"a\": [\"a\", {}]}}", GetCredentialsResponse.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"headers\": {\"a\": [{}, \"a\"]}}", GetCredentialsResponse.class));
  }

  @Test
  public void parseExpires() {
    assertThat(
            GSON.fromJson("{\"expires\": \"1970-09-29T11:46:29Z\"}", GetCredentialsResponse.class)
                .expires())
        .hasValue(Instant.ofEpochSecond(23456789));
    assertThat(
            GSON.fromJson(
                    "{\"expires\": \"1970-09-29T11:46:29+00:00\"}", GetCredentialsResponse.class)
                .expires())
        .hasValue(Instant.ofEpochSecond(23456789));
    assertThat(
            GSON.fromJson(
                    "{\"expires\": \"1970-09-29T13:46:29+02:00\"}", GetCredentialsResponse.class)
                .expires())
        .hasValue(Instant.ofEpochSecond(23456789));
    assertThat(
            GSON.fromJson(
                    "{\"expires\": \"1970-09-28T23:46:29-12:00\"}", GetCredentialsResponse.class)
                .expires())
        .hasValue(Instant.ofEpochSecond(23456789));
  }

  @Test
  public void parseInvalidExpires() {
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"expires\": null}", GetCredentialsResponse.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"expires\": \"foo\"}", GetCredentialsResponse.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"expires\": []}", GetCredentialsResponse.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"expires\": 1}", GetCredentialsResponse.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"expires\": {}}", GetCredentialsResponse.class));
  }

  @Test
  public void serializeEmptyHeaders() {
    GetCredentialsResponse expectedResponse = GetCredentialsResponse.newBuilder().build();
    assertThat(GSON.toJson(expectedResponse)).isEqualTo("{}");
  }

  @Test
  public void roundTrip() {
    GetCredentialsResponse.Builder expectedResponseBuilder =
        GetCredentialsResponse.newBuilder().setExpires(Instant.ofEpochSecond(123456789));
    expectedResponseBuilder.headersBuilder().put("a", ImmutableList.of());
    expectedResponseBuilder.headersBuilder().put("b", ImmutableList.of("b"));
    expectedResponseBuilder.headersBuilder().put("c", ImmutableList.of("c", "c"));
    GetCredentialsResponse expectedResponse = expectedResponseBuilder.build();

    assertThat(GSON.fromJson(GSON.toJson(expectedResponse), GetCredentialsResponse.class))
        .isEqualTo(expectedResponse);
  }
}
