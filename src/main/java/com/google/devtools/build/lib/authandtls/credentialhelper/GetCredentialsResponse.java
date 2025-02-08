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

import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoBuilder;
import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.Immutable;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Response from the {@code get} command of the <a
 * href="https://github.com/bazelbuild/proposals/blob/main/designs/2022-06-07-bazel-credential-helpers.md#proposal">Credential
 * Helper Protocol</a>.
 *
 * <p>See the <a
 * href="https://github.com/EngFlow/credential-helper-spec/blob/main/schemas/get-credentials-response.schema.json">specification</a>.
 *
 * @param headers Returns the headers to attach to the request.
 * @param expires Returns the time the credentials expire and must be revalidated.
 */
@AutoValue.CopyAnnotations
@Immutable
@JsonAdapter(GetCredentialsResponse.GsonTypeAdapter.class)
public record GetCredentialsResponse(
    ImmutableMap<String, ImmutableList<String>> headers, Optional<Instant> expires) {
  public GetCredentialsResponse {
    requireNonNull(headers, "headers");
    requireNonNull(expires, "expires");
  }

  public static final DateTimeFormatter RFC_3339_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
          .withZone(ZoneId.from(ZoneOffset.UTC))
          .withResolverStyle(ResolverStyle.LENIENT);

  /** Returns a new builder for {@link GetCredentialsRequest}. */
  public static Builder newBuilder() {
    return new AutoBuilder_GetCredentialsResponse_Builder();
  }

  /** Builder for {@link GetCredentialsResponse}. */
  @AutoBuilder
  public abstract static class Builder {
    public abstract ImmutableMap.Builder<String, ImmutableList<String>> headersBuilder();

    public abstract Builder setExpires(Instant instant);

    /** Returns the newly constructed {@link GetCredentialsResponse}. */
    public abstract GetCredentialsResponse build();
  }

  /** GSON adapter for GetCredentialsResponse. */
  public static final class GsonTypeAdapter extends TypeAdapter<GetCredentialsResponse> {
    @Override
    public void write(JsonWriter writer, GetCredentialsResponse response) throws IOException {
      Preconditions.checkNotNull(writer);
      Preconditions.checkNotNull(response);

      writer.beginObject();

      ImmutableMap<String, ImmutableList<String>> headers = response.headers();
      if (!headers.isEmpty()) {
        writer.name("headers");
        writer.beginObject();
        for (Map.Entry<String, ImmutableList<String>> entry : headers.entrySet()) {
          writer.name(entry.getKey());

          writer.beginArray();
          for (String value : entry.getValue()) {
            writer.value(value);
          }
          writer.endArray();
        }
        writer.endObject();
      }

      var expires = response.expires();
      if (expires.isPresent()) {
        writer.name("expires");
        writer.value(RFC_3339_FORMATTER.format(expires.get()));
      }

      writer.endObject();
    }

    @Override
    public GetCredentialsResponse read(JsonReader reader) throws IOException {
      Preconditions.checkNotNull(reader);

      GetCredentialsResponse.Builder response = newBuilder();

      if (reader.peek() != JsonToken.BEGIN_OBJECT) {
        throw new JsonSyntaxException(
            String.format(Locale.US, "Expected object, got %s", reader.peek()));
      }
      reader.beginObject();

      while (reader.hasNext()) {
        String name = reader.nextName();
        switch (name) {
          case "headers" -> {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
              throw new JsonSyntaxException(
                  String.format(
                      Locale.US,
                      "Expected value of 'headers' to be an object, got %s",
                      reader.peek()));
            }
            reader.beginObject();

            while (reader.hasNext()) {
              String headerName = reader.nextName();
              ImmutableList.Builder<String> headerValues = ImmutableList.builder();

              if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                throw new JsonSyntaxException(
                    String.format(
                        Locale.US,
                        "Expected value of '%s' header to be an array of strings, got %s",
                        headerName,
                        reader.peek()));
              }
              reader.beginArray();
              for (int i = 0; reader.hasNext(); i++) {
                if (reader.peek() != JsonToken.STRING) {
                  throw new JsonSyntaxException(
                      String.format(
                          Locale.US,
                          "Expected value %s of '%s' header to be a string, got %s",
                          i,
                          headerName,
                          reader.peek()));
                }
                headerValues.add(reader.nextString());
              }
              reader.endArray();

              response.headersBuilder().put(headerName, headerValues.build());
            }

            reader.endObject();
          }
          case "expires" -> {
            if (reader.peek() != JsonToken.STRING) {
              throw new JsonSyntaxException(
                  String.format(
                      Locale.US,
                      "Expected value of 'expires' to be a string, got %s",
                      reader.peek()));
            }
            try {
              response.setExpires(Instant.from(RFC_3339_FORMATTER.parse(reader.nextString())));
            } catch (DateTimeException e) {
              throw new JsonSyntaxException(
                  String.format(
                      Locale.US,
                      "Expected value of 'expires' to be a RFC 3339 formatted timestamp: %s",
                      e.getMessage()));
            }
          }
          default ->
              // We intentionally ignore unknown keys to achieve forward compatibility with
              // responses
              // coming from newer tools.
              reader.skipValue();
        }
      }
      reader.endObject();
      return response.build();
    }
  }
}
