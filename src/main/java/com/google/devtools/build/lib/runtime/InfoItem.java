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

package com.google.devtools.build.lib.runtime;


import com.google.common.base.Supplier;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.unsafe.StringUnsafe;
import com.google.devtools.build.lib.util.AbruptExitException;
import java.util.Arrays;

/** An item that is returned by <code>blaze info</code>. */
public abstract class InfoItem {
  protected final String name;
  protected final String description;
  protected final boolean hidden;

  protected InfoItem(String name, String description, boolean hidden) {
    this.name = name;
    this.description = description;
    this.hidden = hidden;
  }

  protected InfoItem(String name, String description) {
    this(name, description, false);
  }

  /** The name of the info key. */
  public String getName() {
    return name;
  }

  /** The help description of the info key. */
  public String getDescription() {
    return description;
  }

  /**
   * Returns true if this info item requires CommandEnvironment.syncPackageLoading to be called,
   * e.g. in order to initialize the skyframe executor.
   *
   * <p>Virtually all info items do not need it.
   */
  public boolean needsSyncPackageLoading() {
    return false;
  }

  /**
   * Whether the key is printed when "blaze info" is invoked without arguments.
   *
   * <p>This is usually true for info keys that take multiple lines, thus, cannot really be included
   * in the output of argumentless "blaze info".
   */
  public boolean isHidden() {
    return hidden;
  }

  /** Returns the value of the info key. The return value is directly printed to stdout. */
  public abstract byte[] get(
      Supplier<BuildConfigurationValue> configurationSupplier, CommandEnvironment env)
      throws AbruptExitException, InterruptedException;

  protected static byte[] print(Object value) {
    if (value instanceof byte[]) {
      return (byte[]) value;
    }
    byte[] bytes = StringUnsafe.getInternalStringBytes(String.valueOf(value));
    bytes = Arrays.copyOf(bytes, bytes.length + 1);
    bytes[bytes.length - 1] = '\n';
    return bytes;
  }
}
