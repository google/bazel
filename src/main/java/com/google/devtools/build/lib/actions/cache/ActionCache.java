// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.actions.cache;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.BaseEncoding;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.cache.Protos.ActionCacheStatistics;
import com.google.devtools.build.lib.actions.cache.Protos.ActionCacheStatistics.MissReason;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.DigestUtils;
import com.google.devtools.build.lib.vfs.OutputPermissions;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * An interface defining a cache of already-executed Actions.
 *
 * <p>This class' naming is misleading; it doesn't cache the actual actions, but it stores a
 * fingerprint of the action state (ie. a hash of the input and output files on disk), so
 * we can tell if we need to rerun an action given the state of the file system.
 *
 * <p>Each action entry uses one of its output paths as a key (after conversion
 * to the string).
 */
@ThreadCompatible
public interface ActionCache {

  /**
   * Updates the cache entry for the specified key.
   */
  void put(String key, ActionCache.Entry entry);

  /**
   * Returns the corresponding cache entry for the specified key, if any, or
   * null if not found.
   */
  ActionCache.Entry get(String key);

  /**
   * Removes entry from cache
   */
  void remove(String key);

  /** Removes entry from cache that matches the predicate. */
  void removeIf(Predicate<ActionCache.Entry> predicate);

  /**
   * An entry in the ActionCache that contains all action input and output
   * artifact paths and their metadata plus action key itself.
   *
   * Cache entry operates under assumption that once it is fully initialized
   * and getFileDigest() method is called, it becomes logically immutable (all methods
   * will continue to return same result regardless of internal data transformations).
   */
  final class Entry {
    private static final byte[] EMPTY_CLIENT_ENV_DIGEST = new byte[0];

    /** Unique instance to represent a corrupted cache entry. */
    public static final ActionCache.Entry CORRUPTED =
        new ActionCache.Entry(null, ImmutableMap.of(), false, OutputPermissions.READONLY);

    private final String actionKey;
    @Nullable
    // Null iff the corresponding action does not do input discovery.
    private final List<String> files;
    // If null, digest is non-null and the entry is immutable.
    private Map<String, FileArtifactValue> mdMap;
    private byte[] digest;
    private final byte[] actionPropertiesDigest;
    private final Map<String, FileArtifactValue> outputFileMetadata;
    private final Map<String, SerializableTreeArtifactValue> outputTreeMetadata;

    /**
     * The metadata for output tree that can be serialized.
     *
     * <p>We can't serialize {@link TreeArtifactValue} directly as it contains some objects that we
     * don't want to serialize, e.g. {@link SpecialArtifact}.
     *
     * @param childValues A map from parentRelativePath to the file metadata
     */
    public record SerializableTreeArtifactValue(
        ImmutableMap<String, FileArtifactValue> childValues,
        Optional<FileArtifactValue> archivedFileValue,
        Optional<PathFragment> resolvedPath) {
      public SerializableTreeArtifactValue {
        requireNonNull(childValues, "childValues");
        requireNonNull(archivedFileValue, "archivedFileValue");
        requireNonNull(resolvedPath, "resolvedPath");
      }

      public static SerializableTreeArtifactValue create(
          ImmutableMap<String, FileArtifactValue> childValues,
          Optional<FileArtifactValue> archivedFileValue,
          Optional<PathFragment> resolvedPath) {
        return new SerializableTreeArtifactValue(childValues, archivedFileValue, resolvedPath);
      }

      /**
       * Creates {@link SerializableTreeArtifactValue} from {@link TreeArtifactValue} by collecting
       * children and archived artifact which are remote.
       *
       * <p>If no remote value, {@link Optional#empty} is returned.
       */
      public static Optional<SerializableTreeArtifactValue> createSerializable(
          TreeArtifactValue treeMetadata) {
        ImmutableMap<String, FileArtifactValue> childValues =
            treeMetadata.getChildValues().entrySet().stream()
                // Only save remote tree file
                .filter(e -> e.getValue().isRemote())
                .collect(
                    toImmutableMap(e -> e.getKey().getTreeRelativePathString(), e -> e.getValue()));

        // Only save remote archived artifact
        Optional<FileArtifactValue> archivedFileValue =
            treeMetadata
                .getArchivedRepresentation()
                .filter(ar -> ar.archivedFileValue().isRemote())
                .map(ar -> ar.archivedFileValue());

        Optional<PathFragment> resolvedPath = treeMetadata.getResolvedPath();

        if (childValues.isEmpty() && archivedFileValue.isEmpty() && resolvedPath.isEmpty()) {
          return Optional.empty();
        }

        return Optional.of(
            SerializableTreeArtifactValue.create(childValues, archivedFileValue, resolvedPath));
      }
    }

    public Entry(
        String key,
        Map<String, String> usedClientEnv,
        boolean discoversInputs,
        OutputPermissions outputPermissions) {
      actionKey = key;
      this.actionPropertiesDigest = digestActionProperties(usedClientEnv, outputPermissions);
      files = discoversInputs ? new ArrayList<>() : null;
      mdMap = new HashMap<>();
      outputFileMetadata = new HashMap<>();
      outputTreeMetadata = new HashMap<>();
    }

    public Entry(
        String key,
        byte[] actionPropertiesDigest,
        @Nullable List<String> files,
        byte[] digest,
        Map<String, FileArtifactValue> outputFileMetadata,
        Map<String, SerializableTreeArtifactValue> outputTreeMetadata) {
      actionKey = key;
      this.actionPropertiesDigest = actionPropertiesDigest;
      this.files = files;
      this.digest = digest;
      mdMap = null;
      this.outputFileMetadata = outputFileMetadata;
      this.outputTreeMetadata = outputTreeMetadata;
    }

    /**
     * Computes an order-independent digest of action properties. This includes a map of client
     * environment variables and the non-default permissions for output artifacts of the action.
     */
    private static byte[] digestActionProperties(
        Map<String, String> clientEnv, OutputPermissions outputPermissions) {
      byte[] result = EMPTY_CLIENT_ENV_DIGEST;
      Fingerprint fp = new Fingerprint();
      for (Map.Entry<String, String> entry : clientEnv.entrySet()) {
        fp.addString(entry.getKey());
        fp.addString(entry.getValue());
        result = DigestUtils.combineUnordered(result, fp.digestAndReset());
      }
      // Add the permissions mode to the digest if it differs from the default.
      // This is a bit of a hack to save memory on entries which have the default permissions mode
      // and no client env.
      if (outputPermissions != OutputPermissions.READONLY) {
        fp.addInt(outputPermissions.getPermissionsMode());
        result = DigestUtils.combineUnordered(result, fp.digestAndReset());
      }
      return result;
    }

    /** Adds metadata of an output file */
    public void addOutputFile(Artifact output, FileArtifactValue value, boolean saveFileMetadata) {
      checkArgument(
          !output.isTreeArtifact() && !output.isChildOfDeclaredDirectory(),
          "Must use addOutputTree to save tree artifacts and their children: %s",
          output);
      checkState(mdMap != null);
      checkState(!isCorrupted());
      checkState(digest == null);

      String execPath = output.getExecPathString();
      // Only save remote file metadata
      if (saveFileMetadata && value.isRemote()) {
        outputFileMetadata.put(execPath, value);
      }
      mdMap.put(execPath, value);
    }

    /** Gets metadata of an output file */
    @Nullable
    public FileArtifactValue getOutputFile(Artifact output) {
      checkState(!isCorrupted());
      return outputFileMetadata.get(output.getExecPathString());
    }

    /** Gets metadata of all output files */
    public Map<String, FileArtifactValue> getOutputFiles() {
      return outputFileMetadata;
    }

    /** Adds metadata of an output tree */
    public void addOutputTree(
        SpecialArtifact output, TreeArtifactValue metadata, boolean saveTreeMetadata) {
      checkArgument(output.isTreeArtifact(), "artifact must be a tree artifact: %s", output);
      checkState(mdMap != null);
      checkState(!isCorrupted());
      checkState(digest == null);

      String execPath = output.getExecPathString();
      if (saveTreeMetadata) {
        SerializableTreeArtifactValue.createSerializable(metadata)
            .ifPresent(value -> outputTreeMetadata.put(execPath, value));
      }
      mdMap.put(execPath, metadata.getMetadata());
    }

    /** Gets metadata of an output tree */
    @Nullable
    public SerializableTreeArtifactValue getOutputTree(SpecialArtifact output) {
      checkState(!isCorrupted());
      return outputTreeMetadata.get(output.getExecPathString());
    }

    /** Gets metadata of all output trees */
    public Map<String, SerializableTreeArtifactValue> getOutputTrees() {
      return outputTreeMetadata;
    }

    /** Adds metadata of an input file */
    public void addInputFile(
        PathFragment relativePath, FileArtifactValue md, boolean saveExecPath) {
      checkState(mdMap != null);
      checkState(!isCorrupted());
      checkState(digest == null);

      String execPath = relativePath.getPathString();
      if (discoversInputs() && saveExecPath) {
        files.add(execPath);
      }
      mdMap.put(execPath, md);
    }

    public void addInputFile(PathFragment relativePath, FileArtifactValue md) {
      addInputFile(relativePath, md, /*saveExecPath=*/ true);
    }

    /**
     * @return action key string.
     */
    public String getActionKey() {
      return actionKey;
    }

    /** Returns the effectively used client environment. */
    public byte[] getActionPropertiesDigest() {
      return actionPropertiesDigest;
    }

    /** Determines whether this entry has the same action properties as the one given. */
    public boolean sameActionProperties(
        Map<String, String> clientEnv, OutputPermissions outputPermissions) {
      return Arrays.equals(
          digestActionProperties(clientEnv, outputPermissions), actionPropertiesDigest);
    }

    /**
     * Returns the combined digest of the action's inputs and outputs.
     *
     * <p>This may compress the data into a more compact representation, and makes the object
     * immutable.
     */
    public byte[] getFileDigest() {
      if (digest == null) {
        digest = MetadataDigestUtils.fromMetadata(mdMap);
        mdMap = null;
      }
      return digest;
    }

    /**
     * Returns true if this cache entry is corrupted and should be ignored.
     */
    public boolean isCorrupted() {
      return this == CORRUPTED;
    }

    /**
     * @return stored path strings, or null if the corresponding action does not discover inputs.
     */
    public Collection<String> getPaths() {
      return discoversInputs() ? files : ImmutableList.of();
    }

    /**
     * @return whether the corresponding action discovers input files dynamically.
     */
    public boolean discoversInputs() {
      return files != null;
    }

    private static String formatDigest(byte[] digest) {
      return BaseEncoding.base16().lowerCase().encode(digest);
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append("      actionKey = ").append(actionKey).append("\n");
      builder
          .append("      usedClientEnvKey = ")
          .append(formatDigest(actionPropertiesDigest))
          .append("\n");
      builder.append("      digestKey = ");
      if (digest == null) {
        builder
            .append(formatDigest(MetadataDigestUtils.fromMetadata(mdMap)))
            .append(" (from mdMap)\n");
      } else {
        builder.append(formatDigest(digest)).append("\n");
      }

      if (discoversInputs()) {
        List<String> fileInfo = Lists.newArrayListWithCapacity(files.size());
        fileInfo.addAll(files);
        Collections.sort(fileInfo);
        for (String info : fileInfo) {
          builder.append("      ").append(info).append("\n");
        }
      }

      for (Map.Entry<String, FileArtifactValue> entry : outputFileMetadata.entrySet()) {
        builder
            .append("      ")
            .append(entry.getKey())
            .append(" = ")
            .append(entry.getValue())
            .append("\n");
      }

      for (Map.Entry<String, SerializableTreeArtifactValue> entry : outputTreeMetadata.entrySet()) {
        SerializableTreeArtifactValue metadata = entry.getValue();
        builder.append("      ").append(entry.getKey()).append(" = ").append(metadata).append("\n");
      }

      return builder.toString();
    }
  }

  /**
   * Give persistent cache implementations a notification to write to disk.
   * @return size in bytes of the serialized cache.
   */
  long save() throws IOException;

  /** Clear the action cache, closing all opened file handle. */
  void clear();

  /**
   * Dumps action cache content into the given PrintStream.
   */
  void dump(PrintStream out);

  /** The number of entries in the cache. */
  int size();

  /** Accounts one cache hit. */
  void accountHit();

  /** Accounts one cache miss for the given reason. */
  void accountMiss(MissReason reason);

  /**
   * Populates the given builder with statistics.
   *
   * <p>The extracted values are not guaranteed to be a consistent snapshot of the metrics tracked
   * by the action cache. Therefore, even if it is safe to call this function at any point in time,
   * this should only be called once there are no actions running.
   */
  void mergeIntoActionCacheStatistics(ActionCacheStatistics.Builder builder);

  /** Resets the current statistics to zero. */
  void resetStatistics();

  /** Duration it took to load the action cache. Might be null if not loaded in this invocation. */
  @Nullable
  default Duration getLoadTime() {
    return null;
  }
}
