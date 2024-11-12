// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.Maps;
import java.util.LinkedHashMap;
import net.starlark.java.annot.StarlarkBuiltin;

/**
 * A FrozenDict is a Dict that is frozen upon initialization.
 */
@StarlarkBuiltin(
    name = "frozendict",
    category = "core",
    doc =
        "frozendict is a <code>dict</code> that is frozen upon initializatiion."
            + " Because it is frozen upon initialization, it cannot be self-referential, and is"
            + " thus suitable for hashing and use in a depset.")
public class FrozenDict extends Dict<Object, Object> {
  private FrozenDict(Mutability mutability, LinkedHashMap<Object, Object> contents) {
    super(mutability, contents);
  }

  @Override
  public void checkHashable() throws EvalException {
    // A dict that has been frozen from the very start cannot be self-referential, and thus can be
    // hashable (assuming the elements are).

    // Up for debate: Maybe this should be in the FrozenDict.of method.
    // Does it ever make sense to allow frozendict(a={"b": "c"}), for example.
    // Also up for debate: should we cache the result of this.
    for (Object value : this.values()) {
      Starlark.checkHashable(value);
    }
  }

  // Although this is a departure from how Dict works, there is a TODO stating that the only thing
  // isImmutable is used for is checking whether it's a valid depset element, and that they want to
  // replace the isImmutable check with checkHashable in the future.
  @Override
  public boolean isImmutable() {
    try {
      checkHashable();
      return true;
    } catch (EvalException e) {
      return false;
    }
  }

  public static FrozenDict of(Object pairs, Dict<String, Object> kwargs) throws EvalException {
    FrozenDict dict = new FrozenDict(
        Mutability.createAllowingShallowFreeze(),
        Maps.newLinkedHashMapWithExpectedSize(1)
    );
    Dict.update("dict", dict, pairs, kwargs);
    dict.unsafeShallowFreeze();
    return dict;
  }
}
