// Copyright 2020 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.runtime.commands.info;

import com.google.common.base.Supplier;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.InfoItem;
import com.google.devtools.build.lib.util.StringEncoding;
import com.google.devtools.build.lib.vfs.Path;

/** Info item for the location of the Java runtime. */
public final class JavaHomeInfoItem extends InfoItem {
  public JavaHomeInfoItem() {
    super("java-home", "Location of the current Java runtime.", false);
  }

  @Override
  public byte[] get(
      Supplier<BuildConfigurationValue> configurationSupplier, CommandEnvironment env) {
    String javaHome = StringEncoding.platformToInternal(System.getProperty("java.home"));
    if (javaHome == null) {
      return print("unknown");
    }
    // Tunnel through a Path object in order to normalize the representation of the path.
    Path javaHomePath = env.getRuntime().getFileSystem().getPath(javaHome);
    return print(javaHomePath.getPathString());
  }
}
