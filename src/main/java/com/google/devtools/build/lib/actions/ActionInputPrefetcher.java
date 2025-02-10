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
package com.google.devtools.build.lib.actions;

import static com.google.common.util.concurrent.Futures.immediateVoidFuture;

import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;

/** Prefetches files to local disk. */
public interface ActionInputPrefetcher {
  public static final ActionInputPrefetcher NONE =
      (action, inputs, metadataProvider, priority, reason) ->
          // Do nothing.
          immediateVoidFuture();

  /** Priority for the staging task. */
  public enum Priority {
    /**
     * Critical priority tasks are tasks that are critical to the execution time e.g. staging files
     * for in-process actions.
     */
    CRITICAL,
    /**
     * High priority tasks are tasks that may have impact on the execution time e.g. staging outputs
     * that are inputs to local actions which will be executed later.
     */
    HIGH,
    /**
     * Medium priority tasks are tasks that may or may not have the impact on the execution time
     * e.g. staging inputs for local branch of dynamically scheduled actions.
     */
    MEDIUM,
    /**
     * Low priority tasks are tasks that don't have impact on the execution time e.g. staging
     * outputs of toplevel targets/aspects.
     */
    LOW,
  }

  /** The reason for prefetching. */
  enum Reason {
    /** The requested files are needed as inputs to the given action. */
    INPUTS,

    /** The requested files are requested as outputs of the given action. */
    OUTPUTS,
  }

  /**
   * Initiates best-effort prefetching of all given inputs.
   *
   * <p>For any path not under this prefetcher's control, the call should be a no-op.
   *
   * @return future success if prefetch is finished or {@link IOException}.
   */
  ListenableFuture<Void> prefetchFiles(
      ActionExecutionMetadata action,
      Iterable<? extends ActionInput> inputs,
      InputMetadataProvider metadataProvider,
      Priority priority,
      Reason reason);
}
