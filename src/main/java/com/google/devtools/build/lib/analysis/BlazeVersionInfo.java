// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.flogger.GoogleLogger;
import java.util.Date;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Determines the version information of the current process.
 *
 * <p>The version information is a dictionary mapping from string keys to string values.  For
 * build stamping, it should have the key "Build label", which contains among others a
 * XXXXXXX-YYYY.MM.DD string to indicate the version of the release.  If no data is available
 * (eg. when running non-released version), {@link #isAvailable()} returns false.
 */
public class BlazeVersionInfo {
  public static final String BUILD_LABEL = "Build label";
  /** Key for the release timestamp is seconds. */
  public static final String BUILD_TIMESTAMP = "Build timestamp as int";

  // If the current version is a development version, this environment variable can be used to
  // override the version string (e.g. to deal with version-based feature detection during a
  // bisect).
  public static final String BAZEL_DEV_VERSION_OVERRIDE_ENV_VAR = "BAZEL_DEV_VERSION_OVERRIDE";

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static BlazeVersionInfo instance = null;

  private final Map<String, String> buildData = Maps.newTreeMap();

  public BlazeVersionInfo(Map<String, String> info) {
    buildData.putAll(info);
  }

  /**
   * Accessor method for BlazeVersionInfo singleton.
   *
   * <p>If setBuildInfo was not called, returns an empty BlazeVersionInfo instance, which should
   * not be persisted.
   */
  public static synchronized BlazeVersionInfo instance() {
    if (instance == null) {
      return new BlazeVersionInfo(ImmutableMap.<String, String>of());
    }
    return instance;
  }

  private static void logVersionInfo(BlazeVersionInfo info) {
    if (info.getSummary() == null) {
      logger.atWarning().log("Bazel release version information not available");
    } else {
      logger.atInfo().log("Bazel version info: %s", info.getSummary());
    }
  }

  /**
   * Sets build info.
   *
   * <p>This should be called once in the program execution, as early soon as possible, so we
   * can have the version information even before modules are initialized.
   */
  public static synchronized void setBuildInfo(Map<String, String> info) {
    if (instance != null) {
      throw new IllegalStateException("setBuildInfo called twice.");
    }
    instance = new BlazeVersionInfo(info);
    logVersionInfo(instance);
  }

  /**
   * Indicates whether version information is available.
   */
  public boolean isAvailable() {
    return !buildData.isEmpty();
  }

  /**
   * Returns the summary which gets displayed in the 'version' command. The summary is a list of
   * formatted key / value pairs.
   */
  @Nullable
  public String getSummary() {
    if (buildData.isEmpty()) {
      return null;
    }
    return buildData.entrySet().stream()
        .map(e -> e.getKey() + ": " + e.getValue())
        .collect(Collectors.joining("\n"));
  }

  /**
   * Returns true iff this binary is released--that is, a
   * binary built with a release label.
   */
  public boolean isReleasedBlaze() {
    String buildLabel = buildData.get(BUILD_LABEL);
    return buildLabel != null && buildLabel.length() > 0;
  }

  /**
   * Returns the release label, if any, or "development version".
   */
  public String getReleaseName() {
    String buildLabel = buildData.get(BUILD_LABEL);
    return (buildLabel != null && buildLabel.length() > 0)
        ? "release " + buildLabel
        : "development version";
  }

  /**
   * Returns the version, if any, or {@code ""}. The returned version number is easier to process
   * than the version returned by #getReleaseName().
   */
  public String getVersion() {
    String buildLabel = buildData.get(BUILD_LABEL);
    if (buildLabel != null) {
      return buildLabel;
    }
    String override = System.getenv(BAZEL_DEV_VERSION_OVERRIDE_ENV_VAR);
    if (override != null) {
      return override;
    }
    return "";
  }

  /**
   * Returns the release timestamp in seconds.
   */
  public long getTimestamp() {
    String timestamp = buildData.get(BUILD_TIMESTAMP);
    if (timestamp == null || timestamp.equals("0")) {
      return new Date().getTime();
    }
    return Long.parseLong(timestamp);
  }

  @VisibleForTesting
  public Map<String, String> getBuildData() {
    return buildData;
  }
}
