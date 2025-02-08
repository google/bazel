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
package com.google.devtools.build.lib.runtime.commands;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.server.CommandProtos.PathToReplace;
import com.google.protobuf.ByteString;

/** Helpers for constructing {@link ExecRequest}s. */
public class PathToReplaceUtils {

  /** Returns the common required {@link PathToReplace} list. */
  public static ImmutableList<PathToReplace> getPathsToReplace(CommandEnvironment env) {
    return ImmutableList.of(
        PathToReplace.newBuilder()
            .setType(PathToReplace.Type.OUTPUT_BASE)
            .setValue(bytes(env.getOutputBase().getPathString()))
            .build(),
        PathToReplace.newBuilder()
            .setType(PathToReplace.Type.BUILD_WORKING_DIRECTORY)
            .setValue(bytes(env.getWorkingDirectory().getPathString()))
            .build(),
        PathToReplace.newBuilder()
            .setType(PathToReplace.Type.BUILD_WORKSPACE_DIRECTORY)
            .setValue(bytes(env.getWorkspace().getPathString()))
            .build());
  }

  /** Converts a string to bytes for use in {@link ExecRequest} bytes fields. */
  public static ByteString bytes(String string) {
    return ByteString.copyFrom(string, ISO_8859_1);
  }

  private PathToReplaceUtils() {}
}
