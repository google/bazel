// Copyright 2023 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.devtools.build.lib.bazel.bzlmod.DelegateTypeAdapterFactory.DICT;
import static com.google.devtools.build.lib.bazel.bzlmod.DelegateTypeAdapterFactory.IMMUTABLE_BIMAP;
import static com.google.devtools.build.lib.bazel.bzlmod.DelegateTypeAdapterFactory.IMMUTABLE_LIST;
import static com.google.devtools.build.lib.bazel.bzlmod.DelegateTypeAdapterFactory.IMMUTABLE_MAP;
import static com.google.devtools.build.lib.bazel.bzlmod.DelegateTypeAdapterFactory.IMMUTABLE_SET;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Table;
import com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException;
import com.google.devtools.build.lib.bazel.repository.cache.RepositoryCache;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.ryanharter.auto.value.gson.GenerateTypeAdapter;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Base64;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Utility class to hold type adapters and helper methods to get gson registered with type adapters
 */
public final class GsonTypeAdapterUtil {

  public static final TypeAdapter<Version> VERSION_TYPE_ADAPTER =
      new TypeAdapter<>() {
        @Override
        public void write(JsonWriter jsonWriter, Version version) throws IOException {
          jsonWriter.value(version.toString());
        }

        @Override
        public Version read(JsonReader jsonReader) throws IOException {
          Version version;
          String versionString = jsonReader.nextString();
          try {
            version = Version.parse(versionString);
          } catch (ParseException e) {
            throw new JsonParseException(
                String.format("Unable to parse Version %s from the lockfile", versionString), e);
          }
          return version;
        }
      };

  public static final TypeAdapter<ModuleKey> MODULE_KEY_TYPE_ADAPTER =
      new TypeAdapter<>() {
        @Override
        public void write(JsonWriter jsonWriter, ModuleKey moduleKey) throws IOException {
          jsonWriter.value(moduleKey.toString());
        }

        @Override
        public ModuleKey read(JsonReader jsonReader) throws IOException {
          String jsonString = jsonReader.nextString();
          try {
            return ModuleKey.fromString(jsonString);
          } catch (ParseException e) {
            throw new JsonParseException(
                String.format("Unable to parse ModuleKey %s version from the lockfile", jsonString),
                e);
          }
        }
      };

  public static final TypeAdapter<Label> LABEL_TYPE_ADAPTER =
      new TypeAdapter<>() {
        @Override
        public void write(JsonWriter jsonWriter, Label label) throws IOException {
          jsonWriter.value(label.getUnambiguousCanonicalForm());
        }

        @Override
        public Label read(JsonReader jsonReader) throws IOException {
          return Label.parseCanonicalUnchecked(jsonReader.nextString());
        }
      };

  public static final TypeAdapter<RepoRuleId> REPO_RULE_ID_TYPE_ADAPTER =
      new TypeAdapter<>() {
        @Override
        public void write(JsonWriter jsonWriter, RepoRuleId repoRuleId) throws IOException {
          jsonWriter.value(repoRuleId.toString());
        }

        @Override
        public RepoRuleId read(JsonReader jsonReader) throws IOException {
          String s = jsonReader.nextString();
          int percent = s.indexOf('%');
          if (percent == -1) {
            return new RepoRuleId(null, s);
          }
          return new RepoRuleId(
              Label.parseCanonicalUnchecked(s.substring(0, percent)), s.substring(percent + 1));
        }
      };

  public static final TypeAdapter<RepositoryName> REPOSITORY_NAME_TYPE_ADAPTER =
      new TypeAdapter<>() {
        @Override
        public void write(JsonWriter jsonWriter, RepositoryName repoName) throws IOException {
          jsonWriter.value(repoName.getName());
        }

        @Override
        public RepositoryName read(JsonReader jsonReader) throws IOException {
          return RepositoryName.createUnvalidated(jsonReader.nextString());
        }
      };

  public static final TypeAdapter<ModuleExtensionId> MODULE_EXTENSION_ID_TYPE_ADAPTER =
      new TypeAdapter<>() {
        @Override
        public void write(JsonWriter jsonWriter, ModuleExtensionId moduleExtId) throws IOException {
          String isolationKeyPart = moduleExtId.isolationKey().map(key -> "%" + key).orElse("");
          jsonWriter.value(
              moduleExtId.bzlFileLabel() + "%" + moduleExtId.extensionName() + isolationKeyPart);
        }

        @Override
        public ModuleExtensionId read(JsonReader jsonReader) throws IOException {
          String jsonString = jsonReader.nextString();
          var extIdParts = Splitter.on('%').splitToList(jsonString);
          Optional<ModuleExtensionId.IsolationKey> isolationKey;
          if (extIdParts.size() > 2) {
            try {
              isolationKey =
                  Optional.of(ModuleExtensionId.IsolationKey.fromString(extIdParts.get(2)));
            } catch (ParseException e) {
              throw new JsonParseException(
                  String.format(
                      "Unable to parse ModuleExtensionID isolation key: '%s' from the lockfile",
                      extIdParts.get(2)),
                  e);
            }
          } else {
            isolationKey = Optional.empty();
          }
          try {
            return ModuleExtensionId.create(
                Label.parseCanonical(extIdParts.get(0)), extIdParts.get(1), isolationKey);
          } catch (LabelSyntaxException e) {
            throw new JsonParseException(
                String.format(
                    "Unable to parse ModuleExtensionID bzl file label: '%s' from the lockfile",
                    extIdParts.get(0)),
                e);
          }
        }
      };

  public static final TypeAdapter<ModuleExtensionEvalFactors>
      MODULE_EXTENSION_FACTORS_TYPE_ADAPTER =
          new TypeAdapter<>() {

            @Override
            public void write(JsonWriter jsonWriter, ModuleExtensionEvalFactors extFactors)
                throws IOException {
              jsonWriter.value(extFactors.toString());
            }

            @Override
            public ModuleExtensionEvalFactors read(JsonReader jsonReader) throws IOException {
              return ModuleExtensionEvalFactors.parse(jsonReader.nextString());
            }
          };

  public static final TypeAdapter<ModuleExtensionId.IsolationKey> ISOLATION_KEY_TYPE_ADAPTER =
      new TypeAdapter<>() {
        @Override
        public void write(JsonWriter jsonWriter, ModuleExtensionId.IsolationKey isolationKey)
            throws IOException {
          jsonWriter.value(isolationKey.toString());
        }

        @Override
        public ModuleExtensionId.IsolationKey read(JsonReader jsonReader) throws IOException {
          String jsonString = jsonReader.nextString();
          try {
            return ModuleExtensionId.IsolationKey.fromString(jsonString);
          } catch (ParseException e) {
            throw new JsonParseException(
                String.format("Unable to parse isolation key: '%s' from the lockfile", jsonString),
                e);
          }
        }
      };

  public static final TypeAdapter<byte[]> BYTE_ARRAY_TYPE_ADAPTER =
      new TypeAdapter<>() {
        @Override
        public void write(JsonWriter jsonWriter, byte[] value) throws IOException {
          jsonWriter.value(Base64.getEncoder().encodeToString(value));
        }

        @Override
        public byte[] read(JsonReader jsonReader) throws IOException {
          return Base64.getDecoder().decode(jsonReader.nextString());
        }
      };

  public static final TypeAdapterFactory OPTIONAL =
      new TypeAdapterFactory() {
        @Nullable
        @Override
        @SuppressWarnings("unchecked")
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken) {
          if (typeToken.getRawType() != Optional.class) {
            return null;
          }
          Type type = typeToken.getType();
          if (!(type instanceof ParameterizedType)) {
            return null;
          }
          Type elementType = ((ParameterizedType) typeToken.getType()).getActualTypeArguments()[0];
          var elementTypeAdapter = gson.getAdapter(TypeToken.get(elementType));
          if (elementTypeAdapter == null) {
            return null;
          }
          // Explicit nulls for Optional.empty are required for env variable tracking, but are too
          // noisy and unnecessary for other types.
          return (TypeAdapter<T>)
              new OptionalTypeAdapter<>(
                  elementTypeAdapter, /* serializeNulls= */ elementType.equals(String.class));
        }
      };

  private static final class OptionalTypeAdapter<T> extends TypeAdapter<Optional<T>> {
    private final TypeAdapter<T> elementTypeAdapter;
    private final boolean serializeNulls;

    public OptionalTypeAdapter(TypeAdapter<T> elementTypeAdapter, boolean serializeNulls) {
      this.elementTypeAdapter = elementTypeAdapter;
      this.serializeNulls = serializeNulls;
    }

    @Override
    public void write(JsonWriter jsonWriter, Optional<T> t) throws IOException {
      Preconditions.checkNotNull(t);
      if (t.isEmpty()) {
        boolean oldSerializeNulls = jsonWriter.getSerializeNulls();
        jsonWriter.setSerializeNulls(serializeNulls);
        try {
          jsonWriter.nullValue();
        } finally {
          jsonWriter.setSerializeNulls(oldSerializeNulls);
        }
      } else {
        elementTypeAdapter.write(jsonWriter, t.get());
      }
    }

    @Override
    public Optional<T> read(JsonReader jsonReader) throws IOException {
      if (jsonReader.peek() == JsonToken.NULL) {
        jsonReader.nextNull();
        return Optional.empty();
      } else {
        return Optional.of(elementTypeAdapter.read(jsonReader));
      }
    }
  }

  /**
   * Converts Guava tables into a JSON array of 3-tuples (one per cell). Each 3-tuple is a JSON
   * array itself (rowKey, columnKey, value). For example, a JSON snippet could be: {@code [
   * ["row1", "col1", "value1"], ["row2", "col2", "value2"], ... ]}
   */
  public static final TypeAdapterFactory IMMUTABLE_TABLE =
      new TypeAdapterFactory() {
        @Nullable
        @Override
        @SuppressWarnings("unchecked")
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken) {
          if (typeToken.getRawType() != ImmutableTable.class) {
            return null;
          }
          Type type = typeToken.getType();
          if (!(type instanceof ParameterizedType)) {
            return null;
          }
          Type[] typeArgs = ((ParameterizedType) typeToken.getType()).getActualTypeArguments();
          if (typeArgs.length != 3) {
            return null;
          }
          var rowTypeAdapter = (TypeAdapter<Object>) gson.getAdapter(TypeToken.get(typeArgs[0]));
          var colTypeAdapter = (TypeAdapter<Object>) gson.getAdapter(TypeToken.get(typeArgs[1]));
          var valTypeAdapter = (TypeAdapter<Object>) gson.getAdapter(TypeToken.get(typeArgs[2]));
          if (rowTypeAdapter == null || colTypeAdapter == null || valTypeAdapter == null) {
            return null;
          }
          return (TypeAdapter<T>)
              new TypeAdapter<ImmutableTable<Object, Object, Object>>() {
                @Override
                public void write(JsonWriter jsonWriter, ImmutableTable<Object, Object, Object> t)
                    throws IOException {
                  jsonWriter.beginArray();
                  for (Table.Cell<Object, Object, Object> cell : t.cellSet()) {
                    jsonWriter.beginArray();
                    rowTypeAdapter.write(jsonWriter, cell.getRowKey());
                    colTypeAdapter.write(jsonWriter, cell.getColumnKey());
                    valTypeAdapter.write(jsonWriter, cell.getValue());
                    jsonWriter.endArray();
                  }
                  jsonWriter.endArray();
                }

                @Override
                public ImmutableTable<Object, Object, Object> read(JsonReader jsonReader)
                    throws IOException {
                  var builder = ImmutableTable.builder();
                  jsonReader.beginArray();
                  while (jsonReader.peek() != JsonToken.END_ARRAY) {
                    jsonReader.beginArray();
                    builder.put(
                        rowTypeAdapter.read(jsonReader),
                        colTypeAdapter.read(jsonReader),
                        valTypeAdapter.read(jsonReader));
                    jsonReader.endArray();
                  }
                  jsonReader.endArray();
                  return builder.buildOrThrow();
                }
              };
        }
      };

  private static final TypeAdapter<RepoRecordedInput.File> REPO_RECORDED_INPUT_FILE_TYPE_ADAPTER =
      new TypeAdapter<>() {
        @Override
        public void write(JsonWriter jsonWriter, RepoRecordedInput.File value) throws IOException {
          jsonWriter.value(value.toStringInternal());
        }

        @Override
        public RepoRecordedInput.File read(JsonReader jsonReader) throws IOException {
          return (RepoRecordedInput.File)
              RepoRecordedInput.File.PARSER.parse(jsonReader.nextString());
        }
      };

  private static final TypeAdapter<RepoRecordedInput.Dirents>
      REPO_RECORDED_INPUT_DIRENTS_TYPE_ADAPTER =
          new TypeAdapter<>() {
            @Override
            public void write(JsonWriter jsonWriter, RepoRecordedInput.Dirents value)
                throws IOException {
              jsonWriter.value(value.toStringInternal());
            }

            @Override
            public RepoRecordedInput.Dirents read(JsonReader jsonReader) throws IOException {
              return (RepoRecordedInput.Dirents)
                  RepoRecordedInput.Dirents.PARSER.parse(jsonReader.nextString());
            }
          };

  private static final TypeAdapter<RepoRecordedInput.EnvVar>
      REPO_RECORDED_INPUT_ENV_VAR_TYPE_ADAPTER =
          new TypeAdapter<>() {
            @Override
            public void write(JsonWriter jsonWriter, RepoRecordedInput.EnvVar value)
                throws IOException {
              jsonWriter.value(value.toStringInternal());
            }

            @Override
            public RepoRecordedInput.EnvVar read(JsonReader jsonReader) throws IOException {
              return (RepoRecordedInput.EnvVar)
                  RepoRecordedInput.EnvVar.PARSER.parse(jsonReader.nextString());
            }
          };

  // This can't reuse the existing type adapter factory for Optional as we need to explicitly
  // serialize null values but don't want to rely on GSON's serializeNulls.
  private static final class OptionalChecksumTypeAdapterFactory implements TypeAdapterFactory {

    @Nullable
    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken) {
      if (typeToken.getRawType() != Optional.class) {
        return null;
      }
      Type type = typeToken.getType();
      if (!(type instanceof ParameterizedType)) {
        return null;
      }
      Type elementType = ((ParameterizedType) type).getActualTypeArguments()[0];
      if (elementType != Checksum.class) {
        return null;
      }
      @SuppressWarnings("unchecked")
      TypeAdapter<T> typeAdapter = (TypeAdapter<T>) new OptionalChecksumTypeAdapter();
      return typeAdapter;
    }

    private static class OptionalChecksumTypeAdapter extends TypeAdapter<Optional<Checksum>> {
      // This value must not be a valid checksum string.
      private static final String NOT_FOUND_MARKER = "not found";

      @Override
      public void write(JsonWriter jsonWriter, Optional<Checksum> checksum) throws IOException {
        if (checksum.isPresent()) {
          jsonWriter.value(checksum.get().toString());
        } else {
          jsonWriter.value(NOT_FOUND_MARKER);
        }
      }

      @Override
      public Optional<Checksum> read(JsonReader jsonReader) throws IOException {
        String checksumString = jsonReader.nextString();
        if (checksumString.equals(NOT_FOUND_MARKER)) {
          return Optional.empty();
        }
        try {
          return Optional.of(Checksum.fromString(RepositoryCache.KeyType.SHA256, checksumString));
        } catch (Checksum.InvalidChecksumException e) {
          throw new JsonParseException(String.format("Invalid checksum: %s", checksumString), e);
        }
      }
    }
  }

  public static final Gson LOCKFILE_GSON =
      newGsonBuilder()
          .setPrettyPrinting()
          .registerTypeAdapterFactory(new OptionalChecksumTypeAdapterFactory())
          .create();

  public static final Gson SINGLE_EXTENSION_USAGES_VALUE_GSON = newGsonBuilder().create();

  private static GsonBuilder newGsonBuilder() {
    return new GsonBuilder()
        .disableHtmlEscaping()
        .enableComplexMapKeySerialization()
        .registerTypeAdapterFactory(GenerateTypeAdapter.FACTORY)
        .registerTypeAdapterFactory(DICT)
        .registerTypeAdapterFactory(IMMUTABLE_MAP)
        .registerTypeAdapterFactory(IMMUTABLE_LIST)
        .registerTypeAdapterFactory(IMMUTABLE_BIMAP)
        .registerTypeAdapterFactory(IMMUTABLE_SET)
        .registerTypeAdapterFactory(OPTIONAL)
        .registerTypeAdapterFactory(IMMUTABLE_TABLE)
        .registerTypeAdapter(Label.class, LABEL_TYPE_ADAPTER)
        .registerTypeAdapter(RepoRuleId.class, REPO_RULE_ID_TYPE_ADAPTER)
        .registerTypeAdapter(RepositoryName.class, REPOSITORY_NAME_TYPE_ADAPTER)
        .registerTypeAdapter(Version.class, VERSION_TYPE_ADAPTER)
        .registerTypeAdapter(ModuleKey.class, MODULE_KEY_TYPE_ADAPTER)
        .registerTypeAdapter(ModuleExtensionId.class, MODULE_EXTENSION_ID_TYPE_ADAPTER)
        .registerTypeAdapter(
            ModuleExtensionEvalFactors.class, MODULE_EXTENSION_FACTORS_TYPE_ADAPTER)
        .registerTypeAdapter(ModuleExtensionId.IsolationKey.class, ISOLATION_KEY_TYPE_ADAPTER)
        .registerTypeAdapter(AttributeValues.class, new AttributeValuesAdapter())
        .registerTypeAdapter(byte[].class, BYTE_ARRAY_TYPE_ADAPTER)
        .registerTypeAdapter(RepoRecordedInput.File.class, REPO_RECORDED_INPUT_FILE_TYPE_ADAPTER)
        .registerTypeAdapter(
            RepoRecordedInput.Dirents.class, REPO_RECORDED_INPUT_DIRENTS_TYPE_ADAPTER)
        .registerTypeAdapter(
            RepoRecordedInput.EnvVar.class, REPO_RECORDED_INPUT_ENV_VAR_TYPE_ADAPTER);
  }

  private GsonTypeAdapterUtil() {}
}
