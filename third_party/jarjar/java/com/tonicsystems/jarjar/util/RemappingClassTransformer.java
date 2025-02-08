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

import com.tonicsystems.jarjar.EmptyClassVisitor;
import java.util.Objects;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

public class RemappingClassTransformer extends ClassRemapper {
  public RemappingClassTransformer(Remapper pr) {
    super(new EmptyClassVisitor(), new RemapperTracker(pr));
  }

  public void setTarget(ClassVisitor target) {
    ((RemapperTracker) remapper).didRemap = false;
    cv = target;
  }

  public boolean didRemap() {
    return ((RemapperTracker) remapper).didRemap;
  }

  public static class RemapperTracker extends Remapper {

    private final Remapper delegate;
    public boolean didRemap;

    RemapperTracker(Remapper delegate) {
      this.delegate = delegate;
      this.didRemap = false;
    }

    @Override
    public String mapDesc(String desc) {
      String output = delegate.mapDesc(desc);
      didRemap = didRemap || !Objects.equals(output, desc);
      return output;
    }

    @Override
    public String mapType(String type) {
      String output = delegate.mapType(type);
      didRemap = didRemap || !Objects.equals(output, type);
      return output;
    }

    @Override
    public String[] mapTypes(String[] types) {
      String[] localTypes = types.clone();
      String[] output = delegate.mapTypes(types);
      didRemap = didRemap || !Objects.deepEquals(output, localTypes);
      return output;
    }

    @Override
    public String mapMethodDesc(String desc) {
      String output = delegate.mapMethodDesc(desc);
      didRemap = didRemap || !Objects.equals(output, desc);
      return output;
    }

    @Override
    public Object mapValue(Object value) {
      Object output = delegate.mapValue(value);
      didRemap = didRemap || !Objects.equals(output, value);
      return output;
    }

    @Override
    public String mapSignature(String signature, boolean typeSignature) {
      String output = delegate.mapSignature(signature, typeSignature);
      didRemap = didRemap || !Objects.equals(output, signature);
      return output;
    }

    @Override
    public String mapMethodName(String owner, String name, String desc) {
      String output = delegate.mapMethodName(owner, name, desc);
      didRemap = didRemap || !Objects.equals(output, name);
      return output;
    }

    @Override
    public String mapInvokeDynamicMethodName(String name, String desc) {
      String output = delegate.mapInvokeDynamicMethodName(name, desc);
      didRemap = didRemap || !Objects.equals(output, name);
      return output;
    }

    @Override
    public String mapFieldName(String owner, String name, String desc) {
      String output = delegate.mapFieldName(owner, name, desc);
      didRemap = didRemap || !Objects.equals(output, name);
      return output;
    }

    @Override
    public String map(String typeName) {
      String output = delegate.map(typeName);
      didRemap = didRemap || !Objects.equals(output, typeName);
      return output;
    }
  }
}
