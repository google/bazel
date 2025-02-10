// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages;

import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.util.Fingerprint;
import javax.annotation.Nullable;
import net.starlark.java.eval.Printer;
import net.starlark.java.syntax.Location;

/**
 * Base class for declared providers {@see Provider} built into Blaze.
 *
 * <p>Every subclass of {@link BuiltinProvider} should have exactly one instance. If multiple
 * instances of the same subclass are instantiated, they are considered equivalent. This design is
 * motivated by the need for serialization. Starlark providers are readily identified by the pair
 * (.bzl file name, sequence number during execution). BuiltinProviders need an analogous
 * serializable identifier, yet JVM classes (notoriously) don't have a predictable initialization
 * order, so we can't use a sequence number. A distinct subclass for each built-in provider acts as
 * that identifier.
 *
 * <p>Implementations of native declared providers should subclass this class, and define a method
 * in the subclass definition to create instances of its corresponding Info object. The method
 * should be annotated with {@link StarlarkMethod} with {@link StarlarkMethod#selfCall} set to true,
 * and with {@link StarlarkConstructor} for the info type it constructs.
 */
@Immutable
public abstract class BuiltinProvider<T extends Info> implements Provider {
  private final Key key;
  private final String name;
  private final Class<T> valueClass;

  protected BuiltinProvider(String name, Class<T> valueClass) {
    this.key = new Key(name, getClass());
    this.name = name;
    this.valueClass = valueClass;
  }

  public Class<T> getValueClass() {
    return valueClass;
  }

  /**
   * Defines the equivalence relation: all BuiltinProviders of the same Java class are equal,
   * regardless of {@code name} or {@code valueClass}.
   */
  @Override
  public final boolean equals(@Nullable Object other) {
    return other != null && this.getClass().equals(other.getClass());
  }

  @Override
  public final int hashCode() {
    return getClass().hashCode();
  }

  @Override
  public void checkHashable() {
    // The hash code is based on the class, so it is hashable.
  }

  @Override
  public boolean isExported() {
    return true;
  }

  @Override
  public Key getKey() {
    return key;
  }

  @Override
  public Location getLocation() {
    return Location.BUILTIN;
  }

  @Override
  public String getPrintableName() {
    return name;
  }

  @Override
  public void repr(Printer printer) {
    // TODO(adonovan): change to '<provider name>'.
    printer.append("<function " + name + ">");
  }

  /** Returns the identifier of this provider. */
  public StarlarkProviderIdentifier id() {
    return StarlarkProviderIdentifier.forKey(key);
  }

  /**
   * Implement this to mark that a built-in provider should be exported with certain name to
   * Starlark. Broken: only works for rules, not for aspects. DO NOT USE FOR NEW CODE!
   *
   * @deprecated Use declared providers mechanism exclusively to expose providers to both native and
   *     Starlark code.
   */
  @Deprecated
  public interface WithLegacyStarlarkName {
    String getStarlarkName();
  }

  /** A serializable reference to a {@link BuiltinProvider}. */
  @Immutable
  public static final class Key extends Provider.Key {
    private final String name;
    private final Class<? extends Provider> providerClass;

    public Key(String name, Class<? extends Provider> providerClass) {
      this.name = name;
      this.providerClass = providerClass;
    }

    public String getName() {
      return name;
    }

    public Class<? extends Provider> getProviderClass() {
      return providerClass;
    }

    @Override
    void fingerprint(Fingerprint fp) {
      // True => native
      fp.addBoolean(true);
      fp.addString(name);
    }

    @Override
    public int hashCode() {
      return providerClass.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof Key && providerClass.equals(((Key) obj).providerClass);
    }

    @Override
    public String toString() {
      return name;
    }
  }
}
