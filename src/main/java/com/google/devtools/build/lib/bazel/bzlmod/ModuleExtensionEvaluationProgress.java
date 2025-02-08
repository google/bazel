// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.devtools.build.lib.events.ExtendedEventHandler.FetchProgress;

/** Reports the progress of the evaluation of a module extension. */
public class ModuleExtensionEvaluationProgress implements FetchProgress {

  private final ModuleExtensionId extensionId;
  private final boolean finished;
  private final String message;

  /** Returns the unique identifying string for a module extension evaluation event. */
  public static String moduleExtensionEvaluationContextString(ModuleExtensionId extensionId) {
    return "module extension " + extensionId;
  }

  public static ModuleExtensionEvaluationProgress ongoing(
      ModuleExtensionId extensionId, String message) {
    return new ModuleExtensionEvaluationProgress(extensionId, /* finished= */ false, message);
  }

  public static ModuleExtensionEvaluationProgress finished(ModuleExtensionId extensionId) {
    return new ModuleExtensionEvaluationProgress(extensionId, /* finished= */ true, "finished.");
  }

  private ModuleExtensionEvaluationProgress(
      ModuleExtensionId extensionId, boolean finished, String message) {
    this.extensionId = extensionId;
    this.finished = finished;
    this.message = message;
  }

  @Override
  public String getResourceIdentifier() {
    return moduleExtensionEvaluationContextString(extensionId);
  }

  @Override
  public String getProgress() {
    return message;
  }

  @Override
  public boolean isFinished() {
    return finished;
  }
}
