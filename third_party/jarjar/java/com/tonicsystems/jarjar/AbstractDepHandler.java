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

package com.tonicsystems.jarjar;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;

/** AbstractDepHandler. */
public abstract class AbstractDepHandler implements DepHandler {
  protected final DepHandler.Level level;
  private final HashSet<List<String>> seenPairs = new HashSet<>();

  protected AbstractDepHandler(DepHandler.Level level) {
    this.level = level;
  }

  @Override
  @SuppressWarnings("JdkImmutableCollections")
  public void handle(PathClass from, PathClass to) throws IOException {
    List<String> pair = List.of(stringForLevel(from), stringForLevel(to));
    if (seenPairs.add(pair)) {
      handle(pair.get(0), pair.get(1));
    }
  }

  protected abstract void handle(String from, String to) throws IOException;

  @Override
  public void handleStart() throws IOException {}

  @Override
  public void handleEnd() throws IOException {}

  private String stringForLevel(PathClass clazz) {
    switch (level) {
      case JAR:
        return clazz.getClassPath();
      case CLASS:
        return clazz.getClassName();
    }
    throw new AssertionError();
  }
}
