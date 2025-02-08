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
package com.google.devtools.build.lib.analysis.config;

import com.google.common.base.Strings;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.BuildConfiguration.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.DetailedException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import javax.annotation.Nullable;

/**
 * Thrown if the configuration options lead to an invalid configuration, or if any of the
 * configuration labels cannot be loaded.
 */
public class InvalidConfigurationException extends Exception implements DetailedException {

  @Nullable private final DetailedExitCode detailedExitCode;

  public InvalidConfigurationException(String message) {
    super(message);
    this.detailedExitCode = null;
  }

  public InvalidConfigurationException(String message, Code code) {
    super(message);
    this.detailedExitCode = createDetailedExitCode(message, code);
  }

  public InvalidConfigurationException(String message, Throwable cause) {
    super(message, cause);
    this.detailedExitCode = null;
  }

  public InvalidConfigurationException(String message, Code code, Throwable cause) {
    super(message, cause);
    this.detailedExitCode = createDetailedExitCode(message, code);
  }

  public InvalidConfigurationException(Throwable cause) {
    super(cause.getMessage(), cause);
    this.detailedExitCode = null;
  }

  public InvalidConfigurationException(Code code, Throwable cause) {
    super(cause.getMessage(), cause);
    this.detailedExitCode = createDetailedExitCode(cause.getMessage(), code);
  }

  public InvalidConfigurationException(DetailedExitCode detailedExitCode, Throwable cause) {
    super(cause.getMessage(), cause);
    this.detailedExitCode = detailedExitCode;
  }

  @Override
  public DetailedExitCode getDetailedExitCode() {
    return detailedExitCode != null
        ? detailedExitCode
        : createDetailedExitCode(getMessage(), Code.INVALID_CONFIGURATION);
  }

  private static DetailedExitCode createDetailedExitCode(@Nullable String message, Code code) {
    return DetailedExitCode.of(
        FailureDetail.newBuilder()
            .setMessage(Strings.nullToEmpty(message))
            .setBuildConfiguration(FailureDetails.BuildConfiguration.newBuilder().setCode(code))
            .build());
  }
}
