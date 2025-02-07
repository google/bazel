// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.concurrent.ThreadSafety;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.AspectAnalyzedEvent;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.AspectBuiltEvent;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TestAnalyzedEvent;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TopLevelTargetAnalyzedEvent;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TopLevelTargetBuiltEvent;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TopLevelTargetSkippedEvent;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens to the various status events of the top level targets/aspects.
 *
 * <p>WARNING: For consistency, the getter methods should only be used after the execution phase is
 * finished.
 */
@ThreadSafety.ThreadSafe
public class BuildResultListener {
  // Also includes test targets.
  private final Set<ConfiguredTarget> analyzedTargets = ConcurrentHashMap.newKeySet();
  private final Set<ConfiguredTarget> analyzedTests = ConcurrentHashMap.newKeySet();
  private final Map<AspectKey, ConfiguredAspect> analyzedAspects = Maps.newConcurrentMap();
  // Also includes test targets.
  private final Set<ConfiguredTarget> skippedTargets = ConcurrentHashMap.newKeySet();
  // Also includes test targets.
  private final Set<ConfiguredTargetKey> builtTargets = ConcurrentHashMap.newKeySet();
  private final Set<AspectKey> builtAspects = ConcurrentHashMap.newKeySet();

  @Subscribe
  @AllowConcurrentEvents
  public void addAnalyzedTarget(TopLevelTargetAnalyzedEvent event) {
    analyzedTargets.add(event.configuredTarget());
  }

  @Subscribe
  @AllowConcurrentEvents
  public void addAnalyzedTest(TestAnalyzedEvent event) {
    analyzedTests.add(event.configuredTarget());
  }

  @Subscribe
  @AllowConcurrentEvents
  public void addAnalyzedAspect(AspectAnalyzedEvent event) {
    analyzedAspects.put(event.aspectKey(), event.configuredAspect());
  }

  @Subscribe
  @AllowConcurrentEvents
  public void addSkippedTarget(TopLevelTargetSkippedEvent event) {
    skippedTargets.add(event.configuredTarget());
  }

  @Subscribe
  @AllowConcurrentEvents
  public void addBuiltTarget(TopLevelTargetBuiltEvent event) {
    builtTargets.add(event.configuredTargetKey());
  }

  @Subscribe
  @AllowConcurrentEvents
  public void addBuiltAspect(AspectBuiltEvent event) {
    builtAspects.add(event.aspectKey());
  }

  public ImmutableSet<ConfiguredTarget> getAnalyzedTargets() {
    return ImmutableSortedSet.copyOf(
        Comparator.comparing(ConfiguredTarget::getLabel), analyzedTargets);
  }

  public ImmutableSet<ConfiguredTarget> getAnalyzedTests() {
    return ImmutableSortedSet.copyOf(
        Comparator.comparing(ConfiguredTarget::getLabel), analyzedTests);
  }

  public ImmutableMap<AspectKey, ConfiguredAspect> getAnalyzedAspects() {
    return ImmutableSortedMap.copyOf(analyzedAspects);
  }

  public ImmutableSet<ConfiguredTarget> getSkippedTargets() {
    return ImmutableSet.copyOf(skippedTargets);
  }

  public ImmutableSet<ConfiguredTargetKey> getBuiltTargets() {
    return ImmutableSortedSet.copyOf(
        Comparator.nullsLast(Comparator.comparing(ConfiguredTargetKey::getLabel), builtTargets);
  }

  public ImmutableSet<AspectKey> getBuiltAspects() {
    return ImmutableSet.copyOf(builtAspects);
  }
}
