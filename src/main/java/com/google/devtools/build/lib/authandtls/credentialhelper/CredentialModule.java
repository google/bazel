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
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.common.options.OptionsBase;
import java.net.URI;
import java.time.Duration;

/** A module whose sole purpose is to hold the credential cache which is shared by other modules. */
public class CredentialModule extends BlazeModule {
  private final CredentialCacheExpiry cacheExpiry = new CredentialCacheExpiry();
  private final Cache<URI, GetCredentialsResponse> credentialCache;
  private Duration lastDefaultCacheDuration = Duration.ZERO;

  public CredentialModule() {
    this(Ticker.systemTicker());
  }

  @VisibleForTesting
  CredentialModule(Ticker ticker) {
    this.credentialCache = Caffeine.newBuilder().ticker(ticker).expireAfter(cacheExpiry).build();
  }

  /** Returns the credential cache. */
  public Cache<URI, GetCredentialsResponse> getCredentialCache() {
    return credentialCache;
  }

  @Override
  public Iterable<Class<? extends OptionsBase>> getCommonCommandOptions() {
    return ImmutableList.of(AuthAndTLSOptions.class);
  }

  @Override
  public void beforeCommand(CommandEnvironment env) {
    Duration defaultCacheDuration =
        env.getOptions().getOptions(AuthAndTLSOptions.class).credentialHelperCacheTimeout;

    boolean defaultCacheDurationChanged = !defaultCacheDuration.equals(lastDefaultCacheDuration);
    lastDefaultCacheDuration = defaultCacheDuration;

    // Clear the cache on clean or when the default cache duration changes.
    if (env.getCommandName().equals("clean") || defaultCacheDurationChanged) {
      credentialCache.invalidateAll();
      credentialCache.cleanUp();
    }

    // Update the expiration policy for future entries.
    cacheExpiry.setDefaultCacheDuration(defaultCacheDuration);
  }
}
