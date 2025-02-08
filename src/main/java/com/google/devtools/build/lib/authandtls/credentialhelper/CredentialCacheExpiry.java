// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.github.benmanes.caffeine.cache.Expiry;
import com.google.common.base.Preconditions;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;

final class CredentialCacheExpiry implements Expiry<URI, GetCredentialsResponse> {
  private Duration defaultCacheDuration = Duration.ZERO;

  /**
   * Sets the default cache duration for {@link GetCredentialsResponse}s that don't set {@code
   * expiry}.
   */
  public void setDefaultCacheDuration(Duration duration) {
    this.defaultCacheDuration = Preconditions.checkNotNull(duration);
  }

  private Duration getExpirationTime(GetCredentialsResponse response, Instant currentTime) {
    Preconditions.checkNotNull(response);

    var expires = response.expires();
    if (expires.isEmpty()) {
      return defaultCacheDuration;
    }

    return Duration.between(currentTime, expires.get());
  }

  @Override
  public long expireAfterCreate(URI uri, GetCredentialsResponse response, long currentTime) {
    Preconditions.checkNotNull(uri);
    Preconditions.checkNotNull(response);

    return getExpirationTime(response, Instant.ofEpochMilli(nanoToMilli(currentTime))).toNanos();
  }

  @Override
  public long expireAfterUpdate(
      URI uri, GetCredentialsResponse response, long currentTime, long currentDuration) {
    Preconditions.checkNotNull(uri);
    Preconditions.checkNotNull(response);

    return getExpirationTime(response, Instant.ofEpochMilli(nanoToMilli(currentTime))).toNanos();
  }

  @CanIgnoreReturnValue
  @Override
  public long expireAfterRead(
      URI uri, GetCredentialsResponse response, long currentTime, long currentDuration) {
    Preconditions.checkNotNull(uri);
    Preconditions.checkNotNull(response);

    // We don't extend the duration on access.
    return currentDuration;
  }

  private static final long nanoToMilli(long nano) {
    return Duration.ofNanos(nano).toMillis();
  }
}
