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
package com.google.devtools.build.lib.skyframe;

import com.google.devtools.build.lib.events.ExtendedEventHandler;

/** This event is fired at the beginning of the configuration phase. */
public final class ConfigurationPhaseStartedEvent implements ExtendedEventHandler.Postable {

  final AnalysisProgressReceiver analysisProgress;

  /**
   * Construct the event.
   *
   * @param analysisProgress a receiver that gets updated about the progress of target and aspect
   *     configuration.
   */
  public ConfigurationPhaseStartedEvent(AnalysisProgressReceiver analysisProgress) {
    this.analysisProgress = analysisProgress;
  }

  public AnalysisProgressReceiver getAnalysisProgressReceiver() {
    return analysisProgress;
  }
}
