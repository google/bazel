// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.buildeventservice;

import static java.util.Objects.requireNonNull;

import com.google.auth.Credentials;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions;
import com.google.devtools.build.lib.authandtls.GoogleAuthUtils;
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelperEnvironment;
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialModule;
import com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceClient;
import com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceGrpcClient;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.WorkspaceBuilder;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/** Bazel's BES module. */
public class BazelBuildEventServiceModule
    extends BuildEventServiceModule<BuildEventServiceOptions> {

  record BackendConfig(
      String besBackend,
      @Nullable String besProxy,
      ImmutableList<Map.Entry<String, String>> besHeaders,
      AuthAndTLSOptions authAndTLSOptions) {
    BackendConfig {
      requireNonNull(besBackend, "besBackend");
      requireNonNull(besHeaders, "besHeaders");
      requireNonNull(authAndTLSOptions, "authAndTLSOptions");
    }

    static BackendConfig create(
        BuildEventServiceOptions besOptions, AuthAndTLSOptions authAndTLSOptions) {
      return new BackendConfig(
          besOptions.besBackend,
          besOptions.besProxy,
          ImmutableMap.<String, String>builder()
              .putAll(besOptions.besHeaders)
              .buildKeepingLast()
              .entrySet()
              .asList(),
          authAndTLSOptions);
    }
  }

  private BuildEventServiceClient client;
  private BackendConfig config;

  private CredentialModule credentialModule;

  @Override
  public void workspaceInit(
      BlazeRuntime runtime, BlazeDirectories directories, WorkspaceBuilder builder) {
    Preconditions.checkState(credentialModule == null, "credentialModule must be null");
    credentialModule = Preconditions.checkNotNull(runtime.getBlazeModule(CredentialModule.class));
  }

  @Override
  protected Class<BuildEventServiceOptions> optionsClass() {
    return BuildEventServiceOptions.class;
  }

  @Override
  protected BuildEventServiceClient getBesClient(
      CommandEnvironment env,
      BuildEventServiceOptions besOptions,
      AuthAndTLSOptions authAndTLSOptions)
      throws IOException {
    BackendConfig newConfig = BackendConfig.create(besOptions, authAndTLSOptions);
    if (client == null || !Objects.equals(config, newConfig)) {
      clearBesClient();
      Preconditions.checkState(config == null);
      Preconditions.checkState(client == null);

      Credentials credentials =
          GoogleAuthUtils.newCredentials(
              CredentialHelperEnvironment.newBuilder()
                  .setEventReporter(env.getReporter())
                  .setWorkspacePath(env.getWorkspace())
                  .setClientEnvironment(env.getClientEnv())
                  .setHelperExecutionTimeout(authAndTLSOptions.credentialHelperTimeout)
                  .build(),
              credentialModule.getCredentialCache(),
              env.getCommandLinePathFactory(),
              env.getRuntime().getFileSystem(),
              newConfig.authAndTLSOptions());

      config = newConfig;
      client =
          new BuildEventServiceGrpcClient(
              newGrpcChannel(config),
              credentials != null ? MoreCallCredentials.from(credentials) : null,
              makeGrpcInterceptor(config),
              env.getBuildRequestId(),
              env.getCommandId());
    }
    return client;
  }

  @Nullable
  private static ClientInterceptor makeGrpcInterceptor(BackendConfig config) {
    if (config.besHeaders().isEmpty()) {
      return null;
    }
    return MetadataUtils.newAttachHeadersInterceptor(makeGrpcMetadata(config));
  }

  @VisibleForTesting
  static Metadata makeGrpcMetadata(BackendConfig config) {
    Metadata extraHeaders = new Metadata();
    for (Entry<String, String> header : config.besHeaders()) {
      extraHeaders.put(
          Metadata.Key.of(header.getKey(), Metadata.ASCII_STRING_MARSHALLER), header.getValue());
    }
    return extraHeaders;
  }

  // newGrpcChannel is only defined so it can be overridden in tests to not use a real network link.
  @VisibleForTesting
  protected ManagedChannel newGrpcChannel(BackendConfig config) throws IOException {
    return GoogleAuthUtils.newChannel(
        /*executor=*/ null,
        config.besBackend(),
        config.besProxy(),
        config.authAndTLSOptions(),
        /* interceptors= */ null);
  }

  @Override
  protected void clearBesClient() {
    if (client != null) {
      client.shutdown();
    }
    this.client = null;
    this.config = null;
  }

  private static final ImmutableSet<String> ALLOWED_COMMANDS =
      ImmutableSet.of(
          "fetch",
          "build",
          "test",
          "run",
          "query",
          "aquery",
          "cquery",
          "coverage",
          "mobile-install");

  @Override
  protected Set<String> allowedCommands(BuildEventServiceOptions besOptions) {
    return ALLOWED_COMMANDS;
  }

  @Override
  protected String getInvocationIdPrefix() {
    if (Strings.isNullOrEmpty(besOptions.besResultsUrl)) {
      return "";
    }
    return besOptions.besResultsUrl.endsWith("/")
        ? besOptions.besResultsUrl
        : besOptions.besResultsUrl + "/";
  }

  @Override
  protected String getBuildRequestIdPrefix() {
    return "";
  }
}
