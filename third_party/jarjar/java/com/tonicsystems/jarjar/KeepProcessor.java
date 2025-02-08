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

import com.tonicsystems.jarjar.util.EntryStruct;
import com.tonicsystems.jarjar.util.JarProcessor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

// TODO: this can probably be refactored into JarClassVisitor, etc.
class KeepProcessor extends Remapper implements JarProcessor {
  private final ClassVisitor cv = new ClassRemapper(new EmptyClassVisitor(), this);
  private final List<Wildcard> wildcards;
  private final List<String> roots = new ArrayList<>();
  private final Map<String, Set<String>> depend = new HashMap<>();

  public KeepProcessor(List<Keep> patterns) {
    wildcards = PatternElement.createWildcards(patterns);
  }

  public boolean isEnabled() {
    return !wildcards.isEmpty();
  }

  public Set<String> getExcludes() {
    Set<String> closure = new HashSet<>();
    closureHelper(closure, roots);
    Set<String> removable = new HashSet<>(depend.keySet());
    removable.removeAll(closure);
    return removable;
  }

  private void closureHelper(Set<String> closure, Collection<String> process) {
    if (process == null) {
      return;
    }
    for (String name : process) {
      if (closure.add(name)) {
        closureHelper(closure, depend.get(name));
      }
    }
  }

  private Set<String> curSet;

  @Override
  public boolean process(EntryStruct struct) throws IOException {
    try {
      if (struct.isClass()) {
        String name = struct.name.substring(0, struct.name.length() - 6);
        for (Wildcard wildcard : wildcards) {
          if (wildcard.matches(name)) {
            roots.add(name);
          }
        }
        depend.put(name, curSet = new HashSet<>());
        new ClassReader(new ByteArrayInputStream(struct.data))
            .accept(cv, ClassReader.EXPAND_FRAMES);
        curSet.remove(name);
      }
    } catch (Exception e) {
      System.err.println("Error reading " + struct.name + ": " + e.getMessage());
    }
    return true;
  }

  @Override
  public String map(String key) {
    if (key.startsWith("java/") || key.startsWith("javax/")) {
      return null;
    }
    curSet.add(key);
    return null;
  }

  @Override
  public Object mapValue(Object value) {
    if (value instanceof String) {
      String s = (String) value;
      if (PackageRemapper.isArrayForName(s)) {
        mapDesc(s.replace('.', '/'));
      } else if (isForName(s)) {
        map(s.replace('.', '/'));
      }
      return value;
    } else {
      return super.mapValue(value);
    }
  }

  // TODO: use this for package remapping too?
  private static boolean isForName(String value) {
    if (value.isEmpty()) {
      return false;
    }
    for (int i = 0, len = value.length(); i < len; i++) {
      char c = value.charAt(i);
      if (c != '.' && !Character.isJavaIdentifierPart(c)) {
        return false;
      }
    }
    return true;
  }
}
