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

import com.github.benmanes.caffeine.cache.Cache;
import com.google.auth.Credentials;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Implementation of {@link Credentials} which fetches credentials by invoking a {@code credential
 * helper} as subprocess, falling back to another {@link Credentials} if no suitable helper exists.
 */
public class CredentialHelperCredentials extends Credentials {
  private final CredentialHelperProvider credentialHelperProvider;
  private final CredentialHelperEnvironment credentialHelperEnvironment;
  private final Cache<URI, GetCredentialsResponse> credentialCache;
  private final Optional<Credentials> fallbackCredentials;

  /** Wraps around an {@link IOException} so we can smuggle it through {@link Cache#get}. */
  public static final class WrappedIOException extends RuntimeException {
    private final IOException wrapped;

    WrappedIOException(IOException e) {
      super(e);
      this.wrapped = e;
    }

    IOException getWrapped() {
      return wrapped;
    }
  }

  public CredentialHelperCredentials(
      CredentialHelperProvider credentialHelperProvider,
      CredentialHelperEnvironment credentialHelperEnvironment,
      Cache<URI, GetCredentialsResponse> credentialCache,
      Optional<Credentials> fallbackCredentials) {
    this.credentialHelperProvider = Preconditions.checkNotNull(credentialHelperProvider);
    this.credentialHelperEnvironment = Preconditions.checkNotNull(credentialHelperEnvironment);
    this.credentialCache = Preconditions.checkNotNull(credentialCache);
    this.fallbackCredentials = Preconditions.checkNotNull(fallbackCredentials);
  }

  @Override
  public String getAuthenticationType() {
    if (fallbackCredentials.isPresent()) {
      return "credential-helper-with-fallback-" + fallbackCredentials.get().getAuthenticationType();
    }

    return "credential-helper";
  }

  @Override
  @SuppressWarnings("unchecked") // Map<String, ImmutableList<String>> to Map<String<List<String>>
  public Map<String, List<String>> getRequestMetadata(URI uri) throws IOException {
    Preconditions.checkNotNull(uri);

    GetCredentialsResponse response;
    try {
      response = credentialCache.get(uri, this::getCredentialsFromHelper);
    } catch (WrappedIOException e) {
      throw e.getWrapped();
    }
    if (response != null) {
      return (Map) response.headers();
    }

    if (fallbackCredentials.isPresent()) {
      return fallbackCredentials.get().getRequestMetadata(uri);
    }

    return ImmutableMap.of();
  }

  @Nullable
  private GetCredentialsResponse getCredentialsFromHelper(URI uri) {
    Preconditions.checkNotNull(uri);

    Optional<CredentialHelper> maybeCredentialHelper =
        credentialHelperProvider.findCredentialHelper(uri);
    if (maybeCredentialHelper.isEmpty()) {
      return null;
    }
    CredentialHelper credentialHelper = maybeCredentialHelper.get();

    GetCredentialsResponse response;
    try {
      response = credentialHelper.getCredentials(credentialHelperEnvironment, uri);
    } catch (IOException e) {
      throw new WrappedIOException(e);
    }
    if (response == null) {
      return null;
    }

    return response;
  }

  @Override
  public boolean hasRequestMetadata() {
    return true;
  }

  @Override
  public boolean hasRequestMetadataOnly() {
    return false;
  }

  @Override
  public void refresh() throws IOException {
    if (fallbackCredentials.isPresent()) {
      fallbackCredentials.get().refresh();
    }

    credentialCache.invalidateAll();
  }
}
