// Copyright 2015 The Bazel Authors. All rights reserved.
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

package net.starlark.java.eval;

/**
 * A Starlark value that support indexed access ({@code object[key]}) and membership tests ({@code
 * key in object}).
 *
 * <p>Implementations of this interface come in three flavors: map-like, sequence-like, and
 * string-like.
 *
 * <ul>
 *   <li>For map-like objects, 'x in y' should return True when 'y[x]' is valid; otherwise, it
 *       should either be False or a failure. Examples: dict.
 *   <li>For sequence-like objects, 'x in y' should return True when 'x == y[i]' for some integer
 *       'i'; otherwise, it should either be False or a failure. Examples: list, tuple, and string
 *       (which, notably, is not a {@link Sequence}).
 *   <li>For string-like objects, 'x in y' should return True when 'x' is a substring of 'y', i.e.
 *       'x[i] == y[i + n]' for some 'n' and all i in [0, len(x)). Examples: string.
 * </ul>
 */
public interface StarlarkIndexable extends StarlarkMembershipTestable {

  /** Returns the value associated with the given key. */
  Object getIndex(StarlarkSemantics semantics, Object key) throws EvalException;

  /**
   * A variant of {@link StarlarkIndexable} that also provides a StarlarkThread instance on method
   * calls.
   */
  // TODO(brandjon): Consider replacing this subinterface by changing StarlarkIndexable's methods'
  // signatures to take StarlarkThread in place of StarlarkSemantics.
  interface Threaded {
    /** {@see StarlarkIndexable.getIndex} */
    Object getIndex(StarlarkThread starlarkThread, StarlarkSemantics semantics, Object key)
        throws EvalException;

    /** {@see StarlarkIndexable.containsKey} */
    boolean containsKey(StarlarkThread starlarkThread, StarlarkSemantics semantics, Object key)
        throws EvalException;
  }
}
