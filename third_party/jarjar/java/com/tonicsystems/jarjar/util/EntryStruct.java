/*
 * Copyright 2007 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tonicsystems.jarjar.util;

/** A mutable representation of a JAR entry. */
public final class EntryStruct {
  public byte[] data;
  public String name;
  public long time;

  /** Returns true if the entry is a class file. */
  public boolean isClass() {
    if (!name.endsWith(".class")) {
      return false;
    }
    if (name.startsWith("META-INF/version")) {
      // TODO(b/69678527): handle multi-release jar files
      return false;
    }
    return true;
  }

  public boolean isDir() {
    return name.endsWith("/");
  }
}
