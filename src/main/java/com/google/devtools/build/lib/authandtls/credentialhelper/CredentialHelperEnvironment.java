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
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.vfs.Path;
import java.time.Duration;
import javax.annotation.Nullable;

/**
 * Environment for running {@link CredentialHelper}s in.
 *
 * @param eventReporter Returns the reporter for reporting events related to {@link
 *     CredentialHelper}s.
 * @param workspacePath Returns the absolute path to the workspace, or null if Bazel was invoked
 *     outside a workspace.
 *     <p>If available, it will be used as the working directory when invoking the helper
 *     subprocess. Otherwise, the working directory is inherited from the Bazel server process.
 * @param clientEnvironment Returns the environment from the Bazel client.
 *     <p>Passed as environment variables to the subprocess.
 * @param helperExecutionTimeout Returns the execution timeout for the helper subprocess.
 */
public record CredentialHelperEnvironment(
    Reporter eventReporter,
    @Nullable Path workspacePath,
    ImmutableMap<String, String> clientEnvironment,
    Duration helperExecutionTimeout) {
  public CredentialHelperEnvironment {
    requireNonNull(eventReporter, "eventReporter");
    requireNonNull(clientEnvironment, "clientEnvironment");
    requireNonNull(helperExecutionTimeout, "helperExecutionTimeout");
  }

  /** Returns a new builder for {@link CredentialHelperEnvironment}. */
  public static CredentialHelperEnvironment.Builder newBuilder() {
    return new AutoBuilder_CredentialHelperEnvironment_Builder();
  }

  /** Builder for {@link CredentialHelperEnvironment}. */
  @AutoBuilder
  public abstract static class Builder {
    /** Sets the reporter for reporting events related to {@link CredentialHelper}s. */
    public abstract Builder setEventReporter(Reporter reporter);

    /**
     * Sets the absolute path to the workspace, or null if Bazel was invoked outside a workspace.
     */
    public abstract Builder setWorkspacePath(@Nullable Path path);

    /**
     * Sets the environment from the Bazel client to pass as environment variables to the
     * subprocess.
     */
    public abstract Builder setClientEnvironment(ImmutableMap<String, String> environment);

    /** Sets the execution timeout for the helper subprocess. */
    public abstract Builder setHelperExecutionTimeout(Duration timeout);

    /** Returns the newly constructed {@link CredentialHelperEnvironment}. */
    public abstract CredentialHelperEnvironment build();
  }
}
