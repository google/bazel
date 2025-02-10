// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.unsafe;


import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import sun.misc.Unsafe;

/**
 * An accessor for Unsafe.
 *
 * <p>Used for serialization.
 */
public class UnsafeProvider {

  private static final Unsafe UNSAFE = getUnsafe();

  public static Unsafe unsafe() {
    return UNSAFE;
  }

  // TODO: b/386384684 - remove Unsafe usage
  public static long getFieldOffset(Class<?> type, String fieldName) throws NoSuchFieldException {
    return UNSAFE.objectFieldOffset(type.getDeclaredField(fieldName));
  }

  /**
   * Gets a reference to {@link sun.misc.Unsafe} throwing an {@link AssertionError} on failure.
   *
   * <p>Failure is highly unlikely, but possible if the underlying VM stores unsafe in an unexpected
   * location.
   */
  private static Unsafe getUnsafe() {
    try {
      // sun.misc.Unsafe is intentionally difficult to get a hold of - it gives us the power to
      // do things like access raw memory and segfault the JVM.
      return AccessController.doPrivileged(
          new PrivilegedExceptionAction<Unsafe>() {
            @Override
            public Unsafe run() throws Exception {
              Class<Unsafe> unsafeClass = Unsafe.class;
              // Unsafe usually exists in the field 'theUnsafe', however check all fields
              // in case it's somewhere else in this VM's version of Unsafe.
              for (Field f : unsafeClass.getDeclaredFields()) {
                f.setAccessible(true);
                Object fieldValue = f.get(null);
                if (unsafeClass.isInstance(fieldValue)) {
                  return unsafeClass.cast(fieldValue);
                }
              }
              throw new AssertionError("Failed to find sun.misc.Unsafe instance");
            }
          });
    } catch (PrivilegedActionException pae) {
      throw new AssertionError("Unable to get sun.misc.Unsafe", pae);
    }
  }
}
