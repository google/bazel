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

package com.google.devtools.build.lib.vfs;

import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Filesystem;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import java.io.IOException;

/**
 * An {@link IOException} that includes {@link DetailedExitCode} and {@link Transience}. Currently
 * only used for {@link Filesystem} exceptions.
 */
public class DetailedIOException extends IOException {

  private final DetailedExitCode detailedExitCode;
  private final Transience transience;

  public DetailedIOException(
      String message, IOException cause, Filesystem.Code filesystemCode, Transience transience) {
    super(message, cause);
    this.detailedExitCode =
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setFilesystem(Filesystem.newBuilder().setCode(filesystemCode))
                .build());
    this.transience = transience;
  }

  public DetailedExitCode getDetailedExitCode() {
    return detailedExitCode;
  }

  public Transience getTransience() {
    return transience;
  }
}
