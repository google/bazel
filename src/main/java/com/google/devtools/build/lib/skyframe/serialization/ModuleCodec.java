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
package com.google.devtools.build.lib.skyframe.serialization;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.bzlLoadKeyCodec;

import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.skyframe.BzlLoadValue;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import net.starlark.java.eval.Module;

/**
 * Codec for {@link Module}.
 *
 * <p>Serializes using the module's associated {@link BzlLoadValue.Key}.
 */
public final class ModuleCodec extends DeferredObjectCodec<Module> {
  private static final ModuleCodec INSTANCE = new ModuleCodec();

  public static ModuleCodec moduleCodec() {
    return INSTANCE;
  }

  @Override
  public Class<Module> getEncodedClass() {
    return Module.class;
  }

  @Override
  public boolean autoRegister() {
    // Unit tests that bypass Skyframe for Module loading cannot use this codec.
    return false;
  }

  @Override
  public void serialize(SerializationContext context, Module obj, CodedOutputStream codedOut)
      throws IOException, SerializationException {
    var moduleContext = checkNotNull(BazelModuleContext.of(obj), "module %s missing context", obj);
    context.serializeLeaf((BzlLoadValue.Key) moduleContext.key(), bzlLoadKeyCodec(), codedOut);
  }

  @Override
  public DeferredValue<Module> deserializeDeferred(
      AsyncDeserializationContext context, CodedInputStream codedIn)
      throws IOException, SerializationException {
    BzlLoadValue.Key bzlLoadKey = context.deserializeLeaf(codedIn, bzlLoadKeyCodec());
    var builder = new DeserializationBuilder();
    context.getSkyValue(bzlLoadKey, builder, DeserializationBuilder::setBzlLoadValue);
    return builder;
  }

  private static final class DeserializationBuilder implements DeferredValue<Module> {
    private BzlLoadValue loadValue;

    @Override
    public Module call() {
      return checkNotNull(loadValue, "Skyframe lookup value not set").getModule();
    }

    private static void setBzlLoadValue(DeserializationBuilder builder, Object value) {
      builder.loadValue = (BzlLoadValue) value;
    }
  }

  private ModuleCodec() {}
}
