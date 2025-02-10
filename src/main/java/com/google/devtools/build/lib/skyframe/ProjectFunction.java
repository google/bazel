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

import static com.google.devtools.build.skyframe.SkyFunctionException.Transience.PERSISTENT;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Collection;
import java.util.Map.Entry;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;

/** A {@link SkyFunction} that loads metadata from a PROJECT.scl file. */
public class ProjectFunction implements SkyFunction {

  /** The top level reserved globals in the PROJECT.scl file. */
  private enum ReservedGlobals {
    /**
     * Forward-facing PROJECT.scl structure: a single top-level "project" variable that contains all
     * project data in nested data structures.
     */
    PROJECT("project");

    private final String key;

    ReservedGlobals(String key) {
      this.key = key;
    }

    String getKey() {
      return key;
    }
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws ProjectFunctionException, InterruptedException {
    ProjectValue.Key key = (ProjectValue.Key) skyKey.argument();

    BzlLoadValue bzlLoadValue;
    try {
      bzlLoadValue =
          (BzlLoadValue)
              env.getValueOrThrow(
                  BzlLoadValue.keyForBuild(key.getProjectFile()), BzlLoadFailedException.class);
    } catch (BzlLoadFailedException e) {
      throw new ProjectFunctionException(e, PERSISTENT);
    }
    if (bzlLoadValue == null) {
      return null;
    }

    Object projectRaw = bzlLoadValue.getModule().getGlobal(ReservedGlobals.PROJECT.getKey());
    ImmutableMap<String, Object> project =
        switch (projectRaw) {
          case null -> {
            throw new ProjectFunctionException(
                new TypecheckFailureException(
                    "Project files must define exactly one top-level variable called \"project\""));
          }
          case Dict<?, ?> dict -> {
            ImmutableMap.Builder<String, Object> projectBuilder = ImmutableMap.builder();
            for (Object k : dict.keySet()) {
              if (!(k instanceof String stringKey)) {
                throw new ProjectFunctionException(
                    new TypecheckFailureException(
                        String.format(
                            "%s variable: expected string key, got element of %s",
                            ReservedGlobals.PROJECT.getKey(), k.getClass())));
              }
              projectBuilder.put(stringKey, dict.get(stringKey));
            }
            yield projectBuilder.buildOrThrow();
          }
          default ->
              throw new ProjectFunctionException(
                  new TypecheckFailureException(
                      String.format(
                          "%s variable: expected a map of string to objects, got %s",
                          ReservedGlobals.PROJECT.getKey(), projectRaw.getClass())));
        };

    Label actualProjectFile = maybeResolveAlias(key.getProjectFile(), project, bzlLoadValue);
    if (!actualProjectFile.equals(key.getProjectFile())) {
      // This is an alias for another project file. Delegate there.
      // TODO: b/382265245 - handle cycles, including self references.
      return env.getValueOrThrow(
          new ProjectValue.Key(actualProjectFile), ProjectFunctionException.class);
    }

    Object activeDirectoriesRaw = project.get("active_directories");
    // Crude typechecking to prevent server crashes.
    // TODO: all of these typechecking should probably be handled by a proto spec.
    @SuppressWarnings("unchecked")
    ImmutableMap<String, Collection<String>> activeDirectories =
        switch (activeDirectoriesRaw) {
          case null -> ImmutableMap.of();
          case Dict<?, ?> dict -> {
            ImmutableMap.Builder<String, Collection<String>> builder = ImmutableMap.builder();
            for (Entry<?, ?> entry : dict.entrySet()) {
              Object k = entry.getKey();

              if (!(k instanceof String activeDirectoriesKey)) {
                throw new ProjectFunctionException(
                    new TypecheckFailureException(
                        "expected string, got element of " + k.getClass()));
              }

              Object values = entry.getValue();
              if (!(values instanceof Collection<?> activeDirectoriesValues)) {
                throw new ProjectFunctionException(
                    new TypecheckFailureException(
                        "expected list, got element of " + values.getClass()));
              }

              for (Object activeDirectory : activeDirectoriesValues) {
                if (!(activeDirectory instanceof String)) {
                  throw new ProjectFunctionException(
                      new TypecheckFailureException(
                          "expected a list of strings, got element of "
                              + activeDirectory.getClass()));
                }
              }

              builder.put(activeDirectoriesKey, (Collection<String>) values);
            }

            yield builder.buildOrThrow();
          }
          default ->
              throw new ProjectFunctionException(
                  new TypecheckFailureException(
                      "expected a map of string to list of strings, got "
                          + activeDirectoriesRaw.getClass()));
        };

    if (!activeDirectories.isEmpty() && activeDirectories.get("default") == null) {
      throw new ProjectFunctionException(
          new ActiveDirectoriesException(
              "non-empty active_directories must contain the 'default' key"));
    }

    return new ProjectValue(actualProjectFile, project, activeDirectories);
  }

  /**
   * If this is an alias for another project file, returns its label. Else returns the original
   * key's label.
   *
   * <p>See {@link ProjectValue#maybeResolveAlias} for schema details.
   *
   * @throws ProjectFunctionException if the alias schema isn't valid or the actual reference isn't
   *     a valid label.
   */
  private static Label maybeResolveAlias(
      Label originalProjectFile, ImmutableMap<String, Object> project, BzlLoadValue bzlLoadValue)
      throws ProjectFunctionException {
    if (!project.containsKey("actual")) {
      return originalProjectFile;
    } else if (!(project.get("actual") instanceof String)) {
      throw new ProjectFunctionException(
          new TypecheckFailureException(
              String.format(
                  "project[\"actual\"]: expected string, got %s", project.get("actual"))));
    } else if (project.keySet().size() > 1) {
      throw new ProjectFunctionException(
          new TypecheckFailureException(
              String.format(
                  "project[\"actual\"] is present, but other keys are present as well: %s",
                  project.keySet())));
    } else if (bzlLoadValue.getModule().getGlobals().keySet().size() > 1) {
      throw new ProjectFunctionException(
          new TypecheckFailureException(
              String.format(
                  "project global variable is present, but other globals are present as well: %s",
                  bzlLoadValue.getModule().getGlobals().keySet())));
    }
    try {
      return Label.parseCanonical((String) project.get("actual"));
    } catch (LabelSyntaxException e) {
      throw new ProjectFunctionException(e);
    }
  }

  private static final class TypecheckFailureException extends Exception {
    TypecheckFailureException(String msg) {
      super(msg);
    }
  }

  private static final class ActiveDirectoriesException extends Exception {
    ActiveDirectoriesException(String msg) {
      super(msg);
    }
  }

  /** Exception thrown by {@link ProjectFunction}. */
  public static final class ProjectFunctionException extends SkyFunctionException {
    ProjectFunctionException(TypecheckFailureException cause) {
      super(cause, PERSISTENT);
    }

    ProjectFunctionException(ActiveDirectoriesException cause) {
      super(cause, PERSISTENT);
    }

    ProjectFunctionException(BzlLoadFailedException e, Transience transience) {
      super(e, transience);
    }

    ProjectFunctionException(LabelSyntaxException cause) {
      super(cause, PERSISTENT);
    }
  }
}
