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
package com.google.devtools.build.lib.runtime;

import com.google.devtools.build.lib.buildtool.BuildResult.BuildToolLogCollection;
import java.io.IOException;
import java.io.OutputStream;
import javax.annotation.Nullable;

/** Stores and publishes the instrumentation output information. */
public interface InstrumentationOutput {

  /** Creates the {@link OutputStream} for instrumentation output writes. */
  OutputStream createOutputStream() throws IOException;

  /** Publishes instrumentation output information to the {@link BuildToolLogCollection}. */
  void publish(BuildToolLogCollection buildToolLogCollection);

  /** Returns the string of output path. */
  @Nullable
  default String getPathString() {
    return null;
  }
}
