// Copyright 2014 Google Inc. All rights reserved.
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
package com.google.devtools.build.lib.standalone;

import com.google.common.eventbus.Subscribe;
import com.google.devtools.build.lib.actions.ActionContextConsumer;
import com.google.devtools.build.lib.actions.ActionContextProvider;
import com.google.devtools.build.lib.blaze.BlazeModule;
import com.google.devtools.build.lib.blaze.BlazeRuntime;
import com.google.devtools.build.lib.blaze.Command;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.buildevent.BuildStartingEvent;

/**
 * StandaloneModule provides pluggable functionality for blaze.
 */
public class StandaloneModule extends BlazeModule {
  private final ActionContextConsumer actionContextConsumer = new StandaloneContextConsumer();
  private BuildRequest buildRequest;
  private BlazeRuntime runtime;

  /**
   * Returns the action context provider the module contributes to Blaze, if any.
   */
  @Override
  public ActionContextProvider getActionContextProvider() {
    return new StandaloneContextProvider(runtime, buildRequest);
  }

  /**
   * Returns the action context consumer the module contributes to Blaze, if any.
   */
  @Override
  public ActionContextConsumer getActionContextConsumer() {
    return actionContextConsumer;
  }

  @Override
  public void beforeCommand(BlazeRuntime runtime, Command command) {
    this.runtime = runtime;
    runtime.getEventBus().register(this);
  }

  @Subscribe
  public void buildStarting(BuildStartingEvent event) {
    buildRequest = event.getRequest();
  }
}
