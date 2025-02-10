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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Collection;
import java.util.Objects;

/** A SkyValue representing the parsed definitions from a PROJECT.scl file. */
public final class ProjectValue implements SkyValue {
  private final Label actualProjectFile;

  private final ImmutableMap<String, Object> project;

  private final ImmutableMap<String, Collection<String>> activeDirectories;

  public ProjectValue(
      Label actualProjectFile,
      ImmutableMap<String, Object> project,
      ImmutableMap<String, Collection<String>> activeDirectories) {
    this.actualProjectFile = actualProjectFile;
    this.project = project;
    this.activeDirectories = activeDirectories;
  }

  /**
   * Return the default active directory. If there are zero active directories, return the empty
   * set.
   */
  public ImmutableSet<String> getDefaultActiveDirectory() {
    if (activeDirectories.isEmpty()) {
      return ImmutableSet.of();
    }
    Preconditions.checkArgument(
        activeDirectories.containsKey("default"),
        "active_directories must contain the 'default' key");
    return ImmutableSet.copyOf(activeDirectories.get("default"));
  }

  /**
   * If a project file has the content
   *
   * {@snippet :
   *   project = {
   *     "actual": "//other:PROJECT.scl"
   *   }
   * }
   *
   * <p>then this is the same project defined canonically in {@code //other:PROJECT.scl} and this
   * method returns {@code //other:PROJECT.scl}. Else returns the {@link ProjectValue.Key} label
   * that produces this value.
   *
   * <p>Files that define "actual" cannot define any other content. That's considered a parsing
   * error.
   */
  public Label getActualProjectFile() {
    return actualProjectFile;
  }

  /**
   * Returns the top-level project definition. Entries are currently self-typed: it's up to the
   * entry's consumer to validate and correctly read it.
   *
   * <p>If {@code "project"} is not defined in the file, returns an empty map.
   */
  public ImmutableMap<String, Object> getProject() {
    return project;
  }

  /**
   * Returns the map of named active directories in the project. If the map is not defined in the
   * file, returns an empty map.
   */
  public ImmutableMap<String, Collection<String>> getActiveDirectories() {
    return activeDirectories;
  }

  /** The SkyKey. Uses the label of the project file as the input. */
  public static final class Key implements SkyKey {
    private final Label projectFile;

    public Key(Label projectFile) {
      this.projectFile = Preconditions.checkNotNull(projectFile);
    }

    public Label getProjectFile() {
      return projectFile;
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.PROJECT;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      ProjectValue.Key key = (ProjectValue.Key) o;
      return Objects.equals(projectFile, key.projectFile);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(projectFile);
    }
  }
}
