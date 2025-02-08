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
package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.io.InconsistentFilesystemException;
import com.google.devtools.build.lib.io.ProcessPackageDirectoryException;
import com.google.devtools.build.lib.pkgcache.ParsingFailedEvent;
import com.google.devtools.build.lib.skyframe.PrepareDepsOfPatternValue.PrepareDepsOfPatternSkyKeyException;
import com.google.devtools.build.lib.skyframe.PrepareDepsOfPatternValue.PrepareDepsOfPatternSkyKeyValue;
import com.google.devtools.build.lib.skyframe.PrepareDepsOfPatternValue.PrepareDepsOfPatternSkyKeysAndExceptions;
import com.google.devtools.build.lib.skyframe.PrepareDepsOfPatternsValue.TargetPatternSequence;
import com.google.devtools.build.lib.skyframe.TargetPatternValue.TargetPatternKey;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import javax.annotation.Nullable;

/**
 * PrepareDepsOfPatternsFunction ensures the graph loads targets matching the pattern sequence and
 * their transitive dependencies.
 */
public class PrepareDepsOfPatternsFunction implements SkyFunction {

  public static ImmutableList<SkyKey> getSkyKeys(
      SkyKey skyKey, ExtendedEventHandler eventHandler, RepositoryMapping mainRepoMapping) {
    TargetPatternSequence targetPatternSequence = (TargetPatternSequence) skyKey.argument();
    TargetPattern.Parser mainRepoTargetParser =
        new TargetPattern.Parser(
            targetPatternSequence.getOffset(), RepositoryName.MAIN, mainRepoMapping);
    PrepareDepsOfPatternSkyKeysAndExceptions prepareDepsOfPatternSkyKeysAndExceptions =
        PrepareDepsOfPatternValue.keys(targetPatternSequence.getPatterns(), mainRepoTargetParser);

    ImmutableList.Builder<SkyKey> skyKeyBuilder = ImmutableList.builder();
    for (PrepareDepsOfPatternSkyKeyValue skyKeyValue :
        prepareDepsOfPatternSkyKeysAndExceptions.getValues()) {
      skyKeyBuilder.add(skyKeyValue.getSkyKey());
    }
    for (PrepareDepsOfPatternSkyKeyException skyKeyException :
        prepareDepsOfPatternSkyKeysAndExceptions.getExceptions()) {
      TargetParsingException e = skyKeyException.getException();
      // We post an event here rather than in handleTargetParsingException because the
      // TargetPatternFunction already posts an event unless the pattern cannot be parsed, in
      // which case the caller (i.e., us) needs to post an event.
      eventHandler.post(
          new ParsingFailedEvent(skyKeyException.getOriginalPattern(), e.getMessage()));
      handleTargetParsingException(eventHandler, skyKeyException.getOriginalPattern(), e);
    }

    return skyKeyBuilder.build();
  }

  private static final Function<SkyKey, TargetPatternKey> SKY_TO_TARGET_PATTERN =
      new Function<SkyKey, TargetPatternKey>() {
        @Nullable
        @Override
        public TargetPatternKey apply(SkyKey skyKey) {
          return (TargetPatternKey) skyKey.argument();
        }
      };

  public static ImmutableList<TargetPatternKey> getTargetPatternKeys(
      ImmutableList<SkyKey> skyKeys) {
    return ImmutableList.copyOf(Iterables.transform(skyKeys, SKY_TO_TARGET_PATTERN));
  }

  /**
   * Given a {@link SkyKey} that contains a sequence of target patterns, when this function returns
   * {@link PrepareDepsOfPatternsValue}, then all targets matching that sequence, and those targets'
   * transitive dependencies, have been loaded.
   */
  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
    ExtendedEventHandler eventHandler = env.getListener();

    RepositoryMappingValue repositoryMappingValue =
        (RepositoryMappingValue) env.getValue(RepositoryMappingValue.key(RepositoryName.MAIN));
    if (repositoryMappingValue == null) {
      return null;
    }
    RepositoryMapping mainRepoMapping = repositoryMappingValue.repositoryMapping();
    ImmutableList<SkyKey> skyKeys = getSkyKeys(skyKey, eventHandler, mainRepoMapping);

    SkyframeLookupResult tokensByKey = env.getValuesAndExceptions(skyKeys);
    if (env.valuesMissing()) {
      return null;
    }

    for (SkyKey key : skyKeys) {
      try {
        SkyValue value =
            tokensByKey.getOrThrow(
                key,
                TargetParsingException.class,
                ProcessPackageDirectoryException.class,
                InconsistentFilesystemException.class);
        if (value == null) {
          BugReport.sendNonFatalBugReport(
              new IllegalStateException(
                  "SkyValue " + key + " was missing, this should never happen"));
          return null;
        }
      } catch (TargetParsingException e) {
        // If a target pattern can't be evaluated, notify the user of the problem and keep going.
        handleTargetParsingException(eventHandler, key, e);
      } catch (ProcessPackageDirectoryException | InconsistentFilesystemException e) {
        // ProcessPackageDirectoryException indicates a catastrophic
        // InconsistentFilesystemException, which will be handled later by a caller.
        return null;
      }
    }

    return new PrepareDepsOfPatternsValue(getTargetPatternKeys(skyKeys));
  }

  private static void handleTargetParsingException(
      ExtendedEventHandler eventHandler, SkyKey key, TargetParsingException e) {
    TargetPatternKey patternKey = (TargetPatternKey) key.argument();
    String rawPattern = patternKey.getPattern();
    handleTargetParsingException(eventHandler, rawPattern, e);
  }

  private static void handleTargetParsingException(
      ExtendedEventHandler eventHandler, String rawPattern, TargetParsingException e) {
    String errorMessage = e.getMessage();
    eventHandler.handle(Event.error("Skipping '" + rawPattern + "': " + errorMessage));
  }
}
