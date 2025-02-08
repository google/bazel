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

package com.google.devtools.build.lib.authandtls;

import com.github.benmanes.caffeine.cache.Cache;
import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelperCredentials;
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelperEnvironment;
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelperProvider;
import com.google.devtools.build.lib.authandtls.credentialhelper.GetCredentialsResponse;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.runtime.CommandLinePathFactory;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import io.grpc.CallCredentials;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueDomainSocketChannel;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/** Utility methods for using {@link AuthAndTLSOptions} with Google Cloud. */
public final class GoogleAuthUtils {

  /**
   * Create a new gRPC {@link ManagedChannel}.
   *
   * @throws IOException in case the channel can't be constructed.
   */
  public static ManagedChannel newChannel(
      @Nullable Executor executor,
      String target,
      String proxy,
      AuthAndTLSOptions options,
      @Nullable List<ClientInterceptor> interceptors)
      throws IOException {
    Preconditions.checkNotNull(target);
    Preconditions.checkNotNull(options);

    SslContext sslContext =
        isTlsEnabled(target)
            ? createSSlContext(
                options.tlsCertificate, options.tlsClientCertificate, options.tlsClientKey)
            : null;

    String targetUrl = convertTargetScheme(target);
    try {
      NettyChannelBuilder builder =
          newNettyChannelBuilder(targetUrl, proxy)
              .executor(executor)
              .negotiationType(
                  isTlsEnabled(target) ? NegotiationType.TLS : NegotiationType.PLAINTEXT);
      if (options.grpcKeepaliveTime != null) {
        builder.keepAliveTime(options.grpcKeepaliveTime.toSeconds(), TimeUnit.SECONDS);
        builder.keepAliveTimeout(options.grpcKeepaliveTimeout.toSeconds(), TimeUnit.SECONDS);
      }
      if (interceptors != null) {
        builder.intercept(interceptors);
      }
      if (sslContext != null) {
        builder.sslContext(sslContext);
        if (options.tlsAuthorityOverride != null) {
          builder.overrideAuthority(options.tlsAuthorityOverride);
        }
      }
      return builder.build();
    } catch (RuntimeException e) {
      // gRPC might throw all kinds of RuntimeExceptions: StatusRuntimeException,
      // IllegalStateException, NullPointerException, ...
      String message = "Failed to connect to '%s': %s";
      throw new IOException(String.format(message, targetUrl, e.getMessage()));
    }
  }

  /**
   * Converts 'grpc(s)' into an empty protocol, because 'grpc(s)' is not a widely supported scheme
   * and is interpreted as 'dns' under the hood.
   *
   * @return target URL with converted scheme
   */
  private static String convertTargetScheme(String target) {
    return target.replace("grpcs://", "").replace("grpc://", "");
  }

  private static boolean isTlsEnabled(String target) {
    // 'grpcs://' or empty prefix => TLS-enabled
    // when no schema prefix is provided in URL, bazel will treat it as a gRPC request with TLS
    // enabled
    return !target.startsWith("grpc://") && !target.startsWith("unix:");
  }

  private static SslContext createSSlContext(
      @Nullable String rootCert, @Nullable String clientCert, @Nullable String clientKey)
      throws IOException {
    SslContextBuilder sslContextBuilder;
    try {
      sslContextBuilder = GrpcSslContexts.forClient();
    } catch (Exception e) {
      String message = "Failed to init TLS infrastructure: " + e.getMessage();
      throw new IOException(message, e);
    }
    if (rootCert != null) {
      try {
        sslContextBuilder.trustManager(new File(rootCert));
      } catch (Exception e) {
        String message = "Failed to init TLS infrastructure using '%s' as root certificate: %s";
        message = String.format(message, rootCert, e.getMessage());
        throw new IOException(message, e);
      }
    }
    if (clientCert != null && clientKey != null) {
      try {
        sslContextBuilder.keyManager(new File(clientCert), new File(clientKey));
      } catch (Exception e) {
        String message = "Failed to init TLS infrastructure using '%s' as client certificate: %s";
        message = String.format(message, clientCert, e.getMessage());
        throw new IOException(message, e);
      }
    }
    try {
      return sslContextBuilder.build();
    } catch (Exception e) {
      String message = "Failed to init TLS infrastructure: " + e.getMessage();
      throw new IOException(message, e);
    }
  }

  private static EventLoopGroup currentEventLoopGroup = null;

  private static synchronized EventLoopGroup getEventLoopGroup() throws IOException {
    if (currentEventLoopGroup == null) {
      if (KQueue.isAvailable()) {
        currentEventLoopGroup = new KQueueEventLoopGroup();
      } else if (Epoll.isAvailable()) {
        currentEventLoopGroup = new EpollEventLoopGroup();
      } else {
        throw new IOException("Creating event loop groups is unsupported on this platform");
      }
    }
    return currentEventLoopGroup;
  }

  private static NettyChannelBuilder newUnixNettyChannelBuilder(String target) throws IOException {
    DomainSocketAddress address = new DomainSocketAddress(target.replaceFirst("^unix:", ""));
    NettyChannelBuilder builder =
        NettyChannelBuilder.forAddress(address).eventLoopGroup(getEventLoopGroup());
    if (KQueue.isAvailable()) {
      return builder.channelType(KQueueDomainSocketChannel.class);
    }
    if (Epoll.isAvailable()) {
      return builder.channelType(EpollDomainSocketChannel.class);
    }

    throw new IOException("Unix domain sockets are unsupported on this platform");
  }

  private static NettyChannelBuilder newNettyChannelBuilder(String targetUrl, String proxy)
      throws IOException {
    if (targetUrl.startsWith("unix:")) {
      return newUnixNettyChannelBuilder(targetUrl);
    }

    if (Strings.isNullOrEmpty(proxy)) {
      return NettyChannelBuilder.forTarget(targetUrl).defaultLoadBalancingPolicy("round_robin");
    }

    if (!proxy.startsWith("unix:")) {
      throw new IOException("Remote proxy unsupported: " + proxy);
    }

    return newUnixNettyChannelBuilder(proxy).overrideAuthority(targetUrl);
  }

  /**
   * Create a new {@link CallCredentials} object from the authentication flags, or null if no flags
   * are set.
   *
   * @throws IOException in case the credentials can't be constructed.
   */
  @Nullable
  public static CallCredentials newGoogleCallCredentials(AuthAndTLSOptions options)
      throws IOException {
    Optional<Credentials> creds = newGoogleCredentials(options);
    if (creds.isPresent()) {
      return MoreCallCredentials.from(creds.get());
    }
    return null;
  }

  /**
   * Create a new {@link CallCredentialsProvider} object from {@link Credentials} or return {@link
   * CallCredentialsProvider#NO_CREDENTIALS} if it is {@code null}.
   */
  public static CallCredentialsProvider newCallCredentialsProvider(@Nullable Credentials creds) {
    if (creds != null) {
      return new GoogleAuthCallCredentialsProvider(creds);
    }
    return CallCredentialsProvider.NO_CREDENTIALS;
  }

  /**
   * Create a new {@link Credentials} retrieving call credentials in the following order:
   *
   * <ol>
   *   <li>If a Credential Helper is configured for the scope, use the credentials provided by the
   *       helper.
   *   <li>If (Google) authentication is enabled by flags, use it to create credentials.
   *   <li>Use {@code .netrc} to provide credentials if exists.
   * </ol>
   *
   * @throws IOException in case the credentials can't be constructed.
   */
  public static Credentials newCredentials(
      CredentialHelperEnvironment credentialHelperEnvironment,
      Cache<URI, GetCredentialsResponse> credentialCache,
      CommandLinePathFactory commandLinePathFactory,
      FileSystem fileSystem,
      AuthAndTLSOptions authAndTlsOptions)
      throws IOException {
    Preconditions.checkNotNull(credentialHelperEnvironment);
    Preconditions.checkNotNull(commandLinePathFactory);
    Preconditions.checkNotNull(fileSystem);
    Preconditions.checkNotNull(authAndTlsOptions);

    Optional<Credentials> fallbackCredentials = newGoogleCredentials(authAndTlsOptions);

    if (fallbackCredentials.isEmpty()) {
      // Fallback to .netrc if it exists.
      try {
        fallbackCredentials =
            newCredentialsFromNetrc(credentialHelperEnvironment.clientEnvironment(), fileSystem);
      } catch (IOException e) {
        // TODO(yannic): Make this fail the build.
        credentialHelperEnvironment.eventReporter().handle(Event.warn(e.getMessage()));
      }
    }

    return new CredentialHelperCredentials(
        newCredentialHelperProvider(
            credentialHelperEnvironment,
            commandLinePathFactory,
            authAndTlsOptions.credentialHelpers),
        credentialHelperEnvironment,
        credentialCache,
        fallbackCredentials);
  }

  /**
   * Create a new {@link Credentials} object from the authentication flags, or null if no flags are
   * set.
   *
   * @throws IOException in case the credentials can't be constructed.
   */
  private static Optional<Credentials> newGoogleCredentials(AuthAndTLSOptions options)
      throws IOException {
    Preconditions.checkNotNull(options);
    if (options.googleCredentials != null) {
      // Credentials from file
      try (InputStream authFile = new FileInputStream(options.googleCredentials)) {
        return Optional.of(newGoogleCredentialsFromFile(authFile, options.googleAuthScopes));
      } catch (FileNotFoundException e) {
        String message =
            String.format(
                "Could not open auth credentials file '%s': %s",
                options.googleCredentials, e.getMessage());
        throw new IOException(message, e);
      }
    } else if (options.useGoogleDefaultCredentials) {
      return Optional.of(
          newGoogleCredentialsFromFile(
              null /* Google Application Default Credentials */, options.googleAuthScopes));
    }
    return Optional.empty();
  }

  /**
   * Create a new {@link Credentials} object from credential file and given authentication scopes.
   *
   * @throws IOException in case the credentials can't be constructed.
   */
  @VisibleForTesting
  public static Credentials newGoogleCredentialsFromFile(
      @Nullable InputStream credentialsFile, List<String> authScopes) throws IOException {
    try {
      GoogleCredentials creds =
          credentialsFile == null
              ? GoogleCredentials.getApplicationDefault()
              : GoogleCredentials.fromStream(credentialsFile);
      if (!authScopes.isEmpty()) {
        creds = creds.createScoped(authScopes);
      }
      return creds;
    } catch (Exception e) {
      String message = "Failed to init auth credentials: " + e.getMessage();
      throw new IOException(message, e);
    }
  }

  /**
   * Create a new {@link Credentials} object by parsing the .netrc file with following order to
   * search it:
   *
   * <ol>
   *   <li>If environment variable $NETRC exists, use it as the path to the .netrc file
   *   <li>Fallback to $HOME/.netrc
   * </ol>
   *
   * @return the {@link Credentials} object or {@code null} if there is no .netrc file.
   * @throws IOException in case the credentials can't be constructed.
   */
  @VisibleForTesting
  static Optional<Credentials> newCredentialsFromNetrc(
      Map<String, String> clientEnv, FileSystem fileSystem) throws IOException {
    Optional<String> netrcFileString =
        Optional.ofNullable(clientEnv.get("NETRC"))
            .or(() -> Optional.ofNullable(clientEnv.get("HOME")).map(home -> home + "/.netrc"));
    if (netrcFileString.isEmpty()) {
      return Optional.empty();
    }

    Path netrcFile = fileSystem.getPath(netrcFileString.get());
    if (!netrcFile.exists()) {
      return Optional.empty();
    }

    try {
      Netrc netrc = NetrcParser.parseAndClose(netrcFile.getInputStream());
      return Optional.of(new NetrcCredentials(netrc));
    } catch (IOException e) {
      throw new IOException(
          "Failed to parse " + netrcFile.getPathString() + ": " + e.getMessage(), e);
    }
  }

  public static CredentialHelperProvider newCredentialHelperProvider(
      CredentialHelperEnvironment environment,
      CommandLinePathFactory pathFactory,
      List<AuthAndTLSOptions.CredentialHelperOption> helpers)
      throws IOException {
    Preconditions.checkNotNull(environment);
    Preconditions.checkNotNull(pathFactory);
    Preconditions.checkNotNull(helpers);

    CredentialHelperProvider.Builder builder = CredentialHelperProvider.builder();
    for (AuthAndTLSOptions.CredentialHelperOption helper : helpers) {
      Optional<String> scope = helper.scope();
      Path path = pathFactory.create(environment.clientEnvironment(), helper.path());
      if (scope.isPresent()) {
        builder.add(scope.get(), path);
      } else {
        builder.add(path);
      }
    }
    return builder.build();
  }
}
