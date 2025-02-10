// Copyright 2010 The Bazel Authors. All Rights Reserved.
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

package com.google.testing.junit.runner.junit4;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Properties;
import javax.annotation.Nullable;

/**
 * Configuration for the JUnit4 test runner.
 */
class JUnit4Config {
  // VisibleForTesting
  static final String JUNIT_API_VERSION_PROPERTY = "com.google.testing.junit.runner.apiVersion";

  private final boolean testRunnerFailFast;
  private final String testIncludeFilterRegexp;
  private final String testExcludeFilterRegexp;
  @Nullable private final Path xmlOutputPath;
  private final String junitApiVersion;

  private static final String XML_OUTPUT_FILE_ENV_VAR = "XML_OUTPUT_FILE";

  public JUnit4Config(
      String testIncludeFilterRegexp,
      String testExcludeFilterRegexp,
      @Nullable Path outputXmlFilePath) {
    this(
        false,
        testIncludeFilterRegexp,
        testExcludeFilterRegexp,
        outputXmlFilePath,
        System.getProperties());
  }

  public JUnit4Config(
      boolean testRunnerFailFast, String testIncludeFilterRegexp, String testExcludeFilterRegexp) {
    this(
        testRunnerFailFast,
        testIncludeFilterRegexp,
        testExcludeFilterRegexp,
        null,
        System.getProperties());
  }

  // VisibleForTesting
  JUnit4Config(
      String testIncludeFilterRegexp,
      String testExcludeFilterRegexp,
      @Nullable Path xmlOutputPath,
      Properties systemProperties) {
    this(false, testIncludeFilterRegexp, testExcludeFilterRegexp, xmlOutputPath, systemProperties);
  }

  private JUnit4Config(
      boolean testRunnerFailFast,
      String testIncludeFilterRegexp,
      String testExcludeFilterRegexp,
      @Nullable Path xmlOutputPath,
      Properties systemProperties) {
    this.testRunnerFailFast = testRunnerFailFast;
    this.testIncludeFilterRegexp = testIncludeFilterRegexp;
    this.testExcludeFilterRegexp = testExcludeFilterRegexp;
    this.xmlOutputPath = xmlOutputPath;
    junitApiVersion = systemProperties.getProperty(JUNIT_API_VERSION_PROPERTY, "1").trim();
  }

  /**
   * Returns the XML output path, or null if not specified.
   */
  @Nullable
  public Path getXmlOutputPath() {
    if (xmlOutputPath == null) {
      String envXmlOutputPath = System.getenv(XML_OUTPUT_FILE_ENV_VAR);
      return
          envXmlOutputPath == null ? null : FileSystems.getDefault().getPath(envXmlOutputPath);
    }
    return xmlOutputPath;
  }

  /**
   * Gets the version of the JUnit Runner that the test is expecting.
   * Some features may be enabled or disabled based on this value.
   *
   * @return api version
   * @throws IllegalStateException if the API version is unsupported.
   */
  public int getJUnitRunnerApiVersion() {
    int apiVersion = 0;
    try {
      apiVersion = Integer.parseInt(junitApiVersion);
    } catch (NumberFormatException e) {
      // ignore; handled below
    }

    if (apiVersion != 1) {
      throw new IllegalStateException(
          "Unsupported JUnit Runner API version " + JUNIT_API_VERSION_PROPERTY + "="
          + junitApiVersion + " (must be \\\"1\\\")");
    }
    return apiVersion;
  }

  /**
   * Returns the value of the {@code test_runner_fail_fast} option, or <code>false<code/> if
   * it was not specified.
   */
  boolean getTestRunnerFailFast() {
    return testRunnerFailFast;
  }

  /**
   * Returns a regular expression representing an inclusive filter.
   * Only test descriptions that match this regular expression should be run.
   */
  public String getTestIncludeFilterRegexp() {
    return testIncludeFilterRegexp;
  }

  /**
   * Returns a regular expression representing an exclusive filter.
   * Test descriptions that match this regular expression should not be run.
   */
  public String getTestExcludeFilterRegexp() {
    return testExcludeFilterRegexp;
  }
}
