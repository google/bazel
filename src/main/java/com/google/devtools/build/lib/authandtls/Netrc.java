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

import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoValue;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;

/** Container for the content of a .netrc file. */
public record Netrc(
    @Nullable Credential defaultCredential, ImmutableMap<String, Credential> credentials) {
  public Netrc {
    requireNonNull(credentials, "credentials");
  }

  public static Netrc fromStream(InputStream inputStream) throws IOException {
    return NetrcParser.parseAndClose(inputStream);
  }
  /**
   * Construct a new {@link Netrc} instance.
   *
   * @param defaultCredential default {@link Credential} for other machines
   * @param credentials map between a machine and it's corresponding {@link Credential}
   */
  public static Netrc create(
      @Nullable Credential defaultCredential, ImmutableMap<String, Credential> credentials) {
    return new Netrc(defaultCredential, credentials);
  }

  /**
   * Return a {@link Credential} for a given machine. If machine is not found and there isn't
   * default credential, return {@code null}.
   */
  @Nullable
  public Credential getCredential(String machine) {
    return credentials().getOrDefault(machine, defaultCredential());
  }

  /** Container for login, password and account of a machine in .netrc */
  @AutoValue
  public abstract static class Credential {

    abstract String machine();

    abstract String login();

    abstract String password();

    abstract String account();

    /**
     * The generated toString method will leak the password. Override and replace the value of
     * password with constant string {@code <password>}.
     */
    @Override
    public final String toString() {
      return MoreObjects.toStringHelper(this)
          .add("machine", machine())
          .add("login", login())
          .add("password", "<password>")
          .add("account", account())
          .toString();
    }

    /** Create a {@link Builder} object for a given machine. */
    public static Builder builder(String machine) {
      return new AutoValue_Netrc_Credential.Builder()
          .setMachine(machine)
          .setLogin("")
          .setPassword("")
          .setAccount("");
    }

    /** {@link Credential}Builder */
    @AutoValue.Builder
    public abstract static class Builder {
      public abstract String machine();

      public abstract Builder setMachine(String machine);

      public abstract String login();

      public abstract Builder setLogin(String value);

      public abstract String password();

      public abstract Builder setPassword(String value);

      public abstract String account();

      public abstract Builder setAccount(String value);

      public abstract Credential build();
    }
  }
}
