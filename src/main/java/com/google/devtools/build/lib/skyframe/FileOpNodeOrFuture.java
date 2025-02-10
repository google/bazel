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
package com.google.devtools.build.lib.skyframe;

import com.google.devtools.build.lib.concurrent.SettableFutureKeyedValue;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.function.BiConsumer;

/**
 * A possibly empty nested set of file system operations.
 *
 * <p>This value represents the set of file system operation dependencies of a given Skyframe entry,
 * computed by Skyframe graph traversal.
 */
@SuppressWarnings("InterfaceWithOnlyStatics") // sealed hierarchy root
public sealed interface FileOpNodeOrFuture
    permits FileOpNodeOrFuture.FileOpNodeOrEmpty, FileOpNodeOrFuture.FutureFileOpNode {

  /** A possibly empty set of file system dependencies. */
  sealed interface FileOpNodeOrEmpty extends FileOpNodeOrFuture
      permits EmptyFileOpNode, FileOpNode {}

  /** A non-empty set of filesystem operations. */
  sealed interface FileOpNode extends FileOpNodeOrEmpty
      permits FileKey, DirectoryListingKey, AbstractNestedFileOpNodes {}

  /** Empty set of filesystem dependencies. */
  enum EmptyFileOpNode implements FileOpNodeOrEmpty {
    EMPTY_FILE_OP_NODE;
  }

  /** The in-flight computation of a {@link FileOpNodeOrEmpty}. */
  static final class FutureFileOpNode
      extends SettableFutureKeyedValue<FutureFileOpNode, SkyKey, FileOpNodeOrEmpty>
      implements FileOpNodeOrFuture {
    public FutureFileOpNode(SkyKey key, BiConsumer<SkyKey, FileOpNodeOrEmpty> consumer) {
      super(key, consumer);
    }
  }
}
