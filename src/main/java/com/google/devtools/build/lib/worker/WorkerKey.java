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
package com.google.devtools.build.lib.worker;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat;
import com.google.devtools.build.lib.util.CommandDescriptionForm;
import com.google.devtools.build.lib.util.CommandFailureUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Objects;
import java.util.SortedMap;

/**
 * Data container that uniquely identifies a kind of worker process and is used as the key for the
 * {@link WorkerPoolImpl}.
 *
 * <p>We expect a small number of WorkerKeys per mnemonic. Unbounded creation of WorkerKeys will
 * break various things as well as render the workers less useful.
 */
public final class WorkerKey {
  /** Build options. */
  private final ImmutableList<String> args;

  /** Environment variables. */
  private final ImmutableMap<String, String> env;

  /** Execution root of Bazel process. */
  private final Path execRoot;

  /** Mnemonic of the worker. */
  private final String mnemonic;

  /**
   * These are used during validation whether a worker is still usable. They are not used to
   * uniquely identify a kind of worker, thus it is not to be used by the .equals() / .hashCode()
   * methods.
   */
  private final HashCode workerFilesCombinedHash;

  /** Worker files with the corresponding digest. */
  private final SortedMap<PathFragment, byte[]> workerFilesWithDigests;

  /** If true, the workers run inside a sandbox. */
  private final boolean sandboxed;

  /** If true, the sandbox contents are tracked in memory to speed up cleanup. */
  private final boolean useInMemoryTracking;

  /** A WorkerProxy will be instantiated if true, instantiate a regular Worker if false. */
  private final boolean multiplex;

  /** If true, the workers for this key are able to cancel work requests. */
  private final boolean cancellable;

  /**
   * Cached value for the hash of this key, because the value is expensive to calculate
   * (ImmutableMap and ImmutableList do not cache their hashcodes.
   */
  private final int hash;

  /** The format of the worker protocol sent to and read from the worker. */
  private final WorkerProtocolFormat protocolFormat;

  public WorkerKey(
      ImmutableList<String> args,
      ImmutableMap<String, String> env,
      Path execRoot,
      String mnemonic,
      HashCode workerFilesCombinedHash,
      SortedMap<PathFragment, byte[]> workerFilesWithDigests,
      boolean sandboxed,
      boolean useInMemoryTracking,
      boolean multiplex,
      boolean cancellable,
      WorkerProtocolFormat protocolFormat) {
    this.args = Preconditions.checkNotNull(args);
    this.env = Preconditions.checkNotNull(env);
    this.execRoot = Preconditions.checkNotNull(execRoot);
    this.mnemonic = Preconditions.checkNotNull(mnemonic);
    this.workerFilesCombinedHash = Preconditions.checkNotNull(workerFilesCombinedHash);
    this.workerFilesWithDigests = Preconditions.checkNotNull(workerFilesWithDigests);
    this.sandboxed = sandboxed;
    this.useInMemoryTracking = useInMemoryTracking;
    this.multiplex = multiplex;
    this.cancellable = cancellable;
    this.protocolFormat = protocolFormat;
    hash = calculateHashCode();
  }

  public ImmutableList<String> getArgs() {
    return args;
  }

  public ImmutableMap<String, String> getEnv() {
    return env;
  }

  public Path getExecRoot() {
    return execRoot;
  }

  public String getMnemonic() {
    return mnemonic;
  }

  public HashCode getWorkerFilesCombinedHash() {
    return workerFilesCombinedHash;
  }

  public SortedMap<PathFragment, byte[]> getWorkerFilesWithDigests() {
    return workerFilesWithDigests;
  }

  /** Returns true if workers are sandboxed. */
  public boolean isSandboxed() {
    return sandboxed;
  }

  public boolean useInMemoryTracking() {
    return useInMemoryTracking;
  }

  public boolean isMultiplex() {
    return multiplex;
  }

  /** Returns the format of the worker protocol. */
  public WorkerProtocolFormat getProtocolFormat() {
    return protocolFormat;
  }

  /** Returns a user-friendly name for this worker type. */
  public static String makeWorkerTypeName(boolean proxied, boolean mustBeSandboxed) {
    if (proxied && !mustBeSandboxed) {
      return "multiplex-worker";
    } else {
      return "worker";
    }
  }

  /** Returns a user-friendly name for this worker type. */
  public String getWorkerTypeName() {
    // Current implementation does not support sandboxing with multiplex workers, so keys
    // will only be proxied if they are not forced to be sandboxed due to dynamic execution.
    return makeWorkerTypeName(multiplex, false);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    WorkerKey workerKey = (WorkerKey) o;
    if (this.hash != workerKey.hash) {
      return false;
    }
    if (!args.equals(workerKey.args)) {
      return false;
    }
    if (multiplex != workerKey.multiplex) {
      return false;
    }
    if (cancellable != workerKey.cancellable) {
      return false;
    }
    if (sandboxed != workerKey.sandboxed) {
      return false;
    }
    if (useInMemoryTracking != workerKey.useInMemoryTracking) {
      return false;
    }
    if (!env.equals(workerKey.env)) {
      return false;
    }
    if (!execRoot.equals(workerKey.execRoot)) {
      return false;
    }
    if (!this.protocolFormat.equals(workerKey.protocolFormat)) {
      return false;
    }
    return mnemonic.equals(workerKey.mnemonic);
  }

  /** Since all fields involved in the {@code hashCode} are final, we cache the result. */
  @Override
  public int hashCode() {
    return hash;
  }

  private int calculateHashCode() {
    // Use the string representation of the protocolFormat because the hash of the same enum value
    // can vary across instances.
    return Objects.hash(
        args,
        env,
        execRoot,
        mnemonic,
        multiplex,
        cancellable,
        sandboxed,
        useInMemoryTracking,
        protocolFormat.toString());
  }

  @Override
  public String toString() {
    // We print this command out in such a way that it can safely be
    // copied+pasted as a Bourne shell command.  This is extremely valuable for
    // debugging.
    return CommandFailureUtils.describeCommand(
        CommandDescriptionForm.COMPLETE,
        /* prettyPrintArgs= */ false,
        args,
        env,
        /* environmentVariablesToClear= */ null,
        execRoot.getPathString(),
        /* configurationChecksum= */ null,
        /* executionPlatformLabel= */ null,
        /* spawnRunner= */ getWorkerTypeName());
  }
}
