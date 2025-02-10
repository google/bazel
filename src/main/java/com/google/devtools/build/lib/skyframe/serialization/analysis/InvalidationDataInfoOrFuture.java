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
package com.google.devtools.build.lib.skyframe.serialization.analysis;

import com.google.devtools.build.lib.concurrent.SettableFutureKeyedValue;
import com.google.devtools.build.lib.skyframe.AbstractNestedFileOpNodes;
import com.google.devtools.build.lib.skyframe.DirectoryListingKey;
import com.google.devtools.build.lib.skyframe.FileKey;
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.WriteStatus;
import com.google.devtools.build.lib.vfs.RootedPath;
import java.util.function.BiConsumer;

/**
 * Information about remotely stored invalidation data.
 *
 * <p>There are 3 distinct type families, associated with files, directory listings and nodes
 * (nested sets of files and directory listings).
 *
 * <p>Each family has 3 types, a constant type (no persisted data), information about stored
 * invalidation data or a future. Information about stored invalidation data always includes a cache
 * key and a write status.
 *
 * <p>In the case of {@link FileInvalidationData}, a Max Transitive Source Version (MTSV) and fully
 * resolved path is also included. These fields are used for ancestor resolution.
 */
@SuppressWarnings("InterfaceWithOnlyStatics") // sealed hierarchy root
sealed interface InvalidationDataInfoOrFuture
    permits InvalidationDataInfoOrFuture.InvalidationDataInfo,
        InvalidationDataInfoOrFuture.FileDataInfoOrFuture,
        InvalidationDataInfoOrFuture.ListingDataInfoOrFuture,
        InvalidationDataInfoOrFuture.NodeDataInfoOrFuture {

  /** Non-future, immediate value sub-types of {@link InvalidationDataInfoOrFuture}. */
  sealed interface InvalidationDataInfo extends InvalidationDataInfoOrFuture
      permits FileDataInfo, ListingDataInfo, NodeDataInfo {}

  /** Base implementation of a {@link InvalidationDataInfoOrFuture} value. */
  abstract static sealed class BaseInvalidationDataInfo<T> {
    private final T cacheKey;
    private final WriteStatus writeStatus;

    BaseInvalidationDataInfo(T cacheKey, WriteStatus writeStatus) {
      this.cacheKey = cacheKey;
      this.writeStatus = writeStatus;
    }

    /** Key for {@link com.google.devtools.build.lib.serialization.FingerprintValueService}. */
    final T cacheKey() {
      return cacheKey;
    }

    /** Transitively inclusive status of writing this data to the cache. */
    final WriteStatus writeStatus() {
      return writeStatus;
    }
  }

  sealed interface FileDataInfoOrFuture extends InvalidationDataInfoOrFuture
      permits FileDataInfo, FutureFileDataInfo {}

  sealed interface FileDataInfo extends FileDataInfoOrFuture, InvalidationDataInfo
      permits ConstantFileData, FileInvalidationDataInfo {}

  /** The file doesn't change and isn't associated with any invalidation data. */
  enum ConstantFileData implements FileDataInfo {
    CONSTANT_FILE;
  }

  /** Information about transitive upload of invalidation data for a certain {@link FileKey}. */
  static final class FileInvalidationDataInfo extends BaseInvalidationDataInfo<String>
      implements FileDataInfo {
    private final boolean exists;
    private final long mtsv;
    private final RootedPath realPath;

    FileInvalidationDataInfo(
        String cacheKey, WriteStatus writeStatus, boolean exists, long mtsv, RootedPath realPath) {
      super(cacheKey, writeStatus);
      this.exists = exists;
      this.mtsv = mtsv;
      this.realPath = realPath;
    }

    /** True if the file exists. */
    boolean exists() {
      return exists;
    }

    /**
     * The MTSV.
     *
     * <p>Used by dependents to create parent references. This information is already incorporated
     * into the {@link #cacheKey} value.
     */
    long mtsv() {
      return mtsv;
    }

    /**
     * The resolved real path.
     *
     * <p>Used for symlink resolution.
     */
    RootedPath realPath() {
      return realPath;
    }
  }

  static final class FutureFileDataInfo
      extends SettableFutureKeyedValue<FutureFileDataInfo, FileKey, FileDataInfo>
      implements FileDataInfoOrFuture {
    FutureFileDataInfo(FileKey key, BiConsumer<FileKey, FileDataInfo> consumer) {
      super(key, consumer);
    }
  }

  sealed interface ListingDataInfoOrFuture extends InvalidationDataInfoOrFuture
      permits ListingDataInfo, FutureListingDataInfo {}

  sealed interface ListingDataInfo extends ListingDataInfoOrFuture, InvalidationDataInfo
      permits ConstantListingData, ListingInvalidationDataInfo {}

  /** This listing doesn't change and isn't associated with invalidation data. */
  enum ConstantListingData implements ListingDataInfo {
    CONSTANT_LISTING;
  }

  /**
   * Information about transitive upload of invalidation data for a certain {@link
   * DirectoryListingKey}.
   */
  static final class ListingInvalidationDataInfo extends BaseInvalidationDataInfo<String>
      implements ListingDataInfo {
    ListingInvalidationDataInfo(String cacheKey, WriteStatus writeStatus) {
      super(cacheKey, writeStatus);
    }
  }

  static final class FutureListingDataInfo
      extends SettableFutureKeyedValue<FutureListingDataInfo, DirectoryListingKey, ListingDataInfo>
      implements ListingDataInfoOrFuture {
    FutureListingDataInfo(
        DirectoryListingKey key, BiConsumer<DirectoryListingKey, ListingDataInfo> consumer) {
      super(key, consumer);
    }
  }

  sealed interface NodeDataInfoOrFuture extends InvalidationDataInfoOrFuture
      permits NodeDataInfo, FutureNodeDataInfo {}

  sealed interface NodeDataInfo extends NodeDataInfoOrFuture, InvalidationDataInfo
      permits ConstantNodeData, NodeInvalidationDataInfo {}

  enum ConstantNodeData implements NodeDataInfo {
    CONSTANT_NODE;
  }

  /** Information about remotely persisted {@link AbstractNestedFileOpNodes}. */
  static final class NodeInvalidationDataInfo extends BaseInvalidationDataInfo<PackedFingerprint>
      implements NodeDataInfo {
    NodeInvalidationDataInfo(PackedFingerprint key, WriteStatus writeStatus) {
      super(key, writeStatus);
    }
  }

  static final class FutureNodeDataInfo
      extends SettableFutureKeyedValue<FutureNodeDataInfo, AbstractNestedFileOpNodes, NodeDataInfo>
      implements NodeDataInfoOrFuture {
    FutureNodeDataInfo(AbstractNestedFileOpNodes key) {
      super(key, FutureNodeDataInfo::setNodeDataInfo);
    }

    private static void setNodeDataInfo(AbstractNestedFileOpNodes key, NodeDataInfo value) {
      key.setSerializationScratch(value);
    }
  }
}
