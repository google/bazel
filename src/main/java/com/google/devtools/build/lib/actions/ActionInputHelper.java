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

package com.google.devtools.build.lib.actions;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Helper utility to create ActionInput instances. */
public final class ActionInputHelper {
  private ActionInputHelper() {}

  /**
   * Most ActionInputs are created and never used again. On the off chance that one is, however, we
   * implement equality via path comparison. Since file caches are keyed by ActionInput, equality
   * checking does come up.
   */
  public abstract static class BasicActionInput implements ActionInput {

    // TODO(lberki): Plumb this flag from InputTree.build() somehow.
    @Override
    public boolean isSymlink() {
      return false;
    }

    @Override
    public boolean isDirectory() {
      return false;
    }

    @Override
    public int hashCode() {
      return getExecPathString().hashCode();
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof BasicActionInput)) {
        return false;
      }
      return getExecPathString().equals(((BasicActionInput) other).getExecPathString());
    }

    @Override
    public String toString() {
      return "BasicActionInput: " + getExecPathString();
    }
  }

  /**
   * Creates an ActionInput with just the given relative path and no digest.
   *
   * @param path the relative path of the input.
   * @return a ActionInput.
   */
  public static ActionInput fromPath(String path) {
    return new BasicActionInput() {
      @Override
      public String getExecPathString() {
        return path;
      }

      @Override
      public PathFragment getExecPath() {
        return PathFragment.create(path);
      }
    };
  }

  /**
   * Creates an ActionInput with just the given relative path and no digest.
   *
   * @param path the relative path of the input.
   * @return a ActionInput.
   */
  public static ActionInput fromPath(PathFragment path) {
    return new BasicActionInput() {
      @Override
      public String getExecPathString() {
        return path.getPathString();
      }

      @Override
      public PathFragment getExecPath() {
        return path;
      }
    };
  }

  /**
   * Expands runfiles trees and tree artifacts in a sequence of {@link ActionInput}s.
   *
   * <p>If {@code keepEmptyTreeArtifacts} is true, a tree artifact will be included in the
   * constructed list when it expands into zero file artifacts. Otherwise, only the file artifacts
   * the tree artifact expands into will be included.
   *
   * <p>Runfiles tree artifacts will be returned if {@code keepRunfilesTrees} is set.
   *
   * <p>Non-runfiles, non-tree artifacts are returned untouched.
   */
  public static List<ActionInput> expandArtifacts(
      NestedSet<? extends ActionInput> inputs,
      ArtifactExpander artifactExpander,
      boolean keepEmptyTreeArtifacts,
      boolean keepRunfilesTrees) {
    List<ActionInput> result = new ArrayList<>();
    Set<Artifact> emptyTreeArtifacts = new TreeSet<>();
    Set<Artifact> treeFileArtifactParents = new HashSet<>();
    for (ActionInput input : inputs.toList()) {
      if (!(input instanceof Artifact artifact)) {
        result.add(input);
      } else if (artifact.isRunfilesTree()) {
        if (keepRunfilesTrees) {
          result.add(artifact);
        }
      } else if (artifact.isTreeArtifact()) {
        ImmutableSortedSet<TreeFileArtifact> children =
            artifactExpander.tryExpandTreeArtifact(artifact);
        if (children.isEmpty()) {
          emptyTreeArtifacts.add(artifact);
        } else {
          result.addAll(children);
        }
      } else {
        result.add(artifact);
        if (artifact.isChildOfDeclaredDirectory()) {
          treeFileArtifactParents.add(artifact.getParent());
        }
      }
    }

    if (keepEmptyTreeArtifacts) {
      emptyTreeArtifacts.removeAll(treeFileArtifactParents);
      result.addAll(emptyTreeArtifacts);
    }
    return result;
  }

  public static Iterable<String> toExecPaths(Iterable<? extends ActionInput> artifacts) {
    return Iterables.transform(artifacts, ActionInput::getExecPathString);
  }

  /** Returns the {@link Path} for an {@link ActionInput}. */
  public static Path toInputPath(ActionInput input, Path execRoot) {
    Preconditions.checkNotNull(input, "input");
    Preconditions.checkNotNull(execRoot, "execRoot");

    return input instanceof Artifact artifact
        ? artifact.getPath()
        : execRoot.getRelative(input.getExecPath());
  }
}
