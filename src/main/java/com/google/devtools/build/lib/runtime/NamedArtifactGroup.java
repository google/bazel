// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.runtime;

import static com.google.devtools.build.lib.analysis.TargetCompleteEvent.newFileFromArtifact;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CompletionContext;
import com.google.devtools.build.lib.actions.CompletionContext.ArtifactReceiver;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.buildeventstream.ArtifactGroupNamer;
import com.google.devtools.build.lib.buildeventstream.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile.LocalFileType;
import com.google.devtools.build.lib.buildeventstream.BuildEventContext;
import com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.GenericBuildEvent;
import com.google.devtools.build.lib.buildeventstream.PathConverter;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Collection;
import javax.annotation.Nullable;

/**
 * A {@link BuildEvent} introducing a set of artifacts to be referred to later by its name. Those
 * events are generated by the {@link BuildEventStreamer} upon seeing an {@link
 * com.google.devtools.build.lib.actions.EventReportingArtifacts}, if necessary.
 */
class NamedArtifactGroup implements BuildEvent {
  private final String name;
  private final CompletionContext completionContext;
  private final NestedSet<?> set; // of Artifact or ExpandedArtifact

  /**
   * Create a {@link NamedArtifactGroup}. Although the set may contain a mixture of Artifacts and
   * ExpandedArtifacts, all its leaf successors ("direct elements") are ExpandedArtifacts.
   */
  NamedArtifactGroup(String name, CompletionContext completionContext, NestedSet<?> set) {
    this.name = name;
    this.completionContext = completionContext;
    this.set = set;
  }

  @Override
  public BuildEventId getEventId() {
    return BuildEventIdUtil.fromArtifactGroupName(name);
  }

  @Override
  public Collection<BuildEventId> getChildrenEvents() {
    return ImmutableSet.of();
  }

  @Override
  public Collection<LocalFile> referencedLocalFiles() {
    ImmutableList.Builder<LocalFile> artifacts = ImmutableList.builder();
    for (Object elem : set.getLeaves()) {
      ExpandedArtifact expandedArtifact = (ExpandedArtifact) elem;
      if (expandedArtifact.relPath == null) {
        FileArtifactValue metadata =
            completionContext.getFileArtifactValue(expandedArtifact.artifact);
        artifacts.add(
            new LocalFile(
                completionContext.pathResolver().toPath(expandedArtifact.artifact),
                LocalFileType.forArtifact(expandedArtifact.artifact, metadata),
                metadata == null ? null : expandedArtifact.artifact,
                metadata));
      } else {
        // TODO(b/199940216): Can fileset metadata be properly handled here?
        artifacts.add(
            new LocalFile(
                completionContext.pathResolver().convertPath(expandedArtifact.target),
                LocalFileType.OUTPUT,
                /*artifact=*/ null,
                /*artifactMetadata=*/ null));
      }
    }
    return artifacts.build();
  }

  @Override
  public BuildEventStreamProtos.BuildEvent asStreamProto(BuildEventContext converters) {
    PathConverter pathConverter = converters.pathConverter();
    ArtifactGroupNamer namer = converters.artifactGroupNamer();

    BuildEventStreamProtos.NamedSetOfFiles.Builder builder =
        BuildEventStreamProtos.NamedSetOfFiles.newBuilder();
    for (Object elem : set.getLeaves()) {
      ExpandedArtifact expandedArtifact = (ExpandedArtifact) elem;
      if (expandedArtifact.relPath == null) {
        String uri =
            pathConverter.apply(completionContext.pathResolver().toPath(expandedArtifact.artifact));
        BuildEventStreamProtos.File file =
            newFileFromArtifact(
                /* name= */ null, expandedArtifact.artifact, completionContext, uri);
        // Omit files with unknown contents (e.g. if uploading failed).
        if (file.getFileCase() != BuildEventStreamProtos.File.FileCase.FILE_NOT_SET) {
          builder.addFiles(file);
        }
      } else {
        String uri =
            pathConverter.apply(
                completionContext.pathResolver().convertPath(expandedArtifact.target));
        BuildEventStreamProtos.File file =
            newFileFromArtifact(
                /* name= */ null,
                expandedArtifact.artifact,
                expandedArtifact.relPath,
                completionContext,
                uri);
        // Omit files with unknown contents (e.g. if uploading failed).
        if (file.getFileCase() != BuildEventStreamProtos.File.FileCase.FILE_NOT_SET) {
          builder.addFiles(file);
        }
      }
    }

    for (NestedSet<?> succ : set.getNonLeaves()) {
      builder.addFileSets(namer.apply(succ.toNode()));
    }
    return GenericBuildEvent.protoChaining(this).setNamedSetOfFiles(builder.build()).build();
  }

  /**
   * Given a set whose leaf successors are {@link Artifact} and {@link ExpandedArtifact}, returns a
   * new NestedSet whose leaf successors are all ExpandedArtifact. Non-leaf successors are
   * unaltered.
   */
  static NestedSet<?> expandSet(CompletionContext ctx, NestedSet<?> artifacts) {
    NestedSetBuilder<Object> res = new NestedSetBuilder<>(Order.STABLE_ORDER);
    for (Object artifact : artifacts.getLeaves()) {
      if (artifact instanceof ExpandedArtifact) {
        res.add(artifact);
      } else if (artifact instanceof Artifact) {
        ctx.visitArtifacts(
            ImmutableList.of((Artifact) artifact),
            new ArtifactReceiver() {
              @Override
              public void accept(Artifact artifact) {
                res.add(new ExpandedArtifact(artifact, null, null));
              }

              @Override
              public void acceptFilesetMapping(
                  Artifact fileset, PathFragment relName, Path targetFile) {
                res.add(new ExpandedArtifact(fileset, relName, targetFile));
              }
            });
      } else {
        throw new IllegalStateException("Unexpected type in artifact set:  " + artifact);
      }
    }
    boolean noDirects = res.isEmpty();

    ImmutableList<? extends NestedSet<?>> nonLeaves = artifacts.getNonLeaves();
    for (NestedSet<?> succ : nonLeaves) {
      res.addTransitive(succ);
    }

    NestedSet<?> result = res.build();

    // If we have executed one call to res.addTransitive(x)
    // and none to res.add, then res.build() simply returns x
    // ("inlining"), which may violate our postcondition on elements.
    // (This can happen if 'artifacts' contains one non-leaf successor
    // and one or more leaf successors for which visitArtifacts is a no-op.)
    //
    // In that case we need to recursively apply the expansion. Ugh.
    if (noDirects && nonLeaves.size() == 1) {
      return expandSet(ctx, result);
    }

    return result;
  }

  private static final class ExpandedArtifact {
    final Artifact artifact;
    // These fields are used only for Fileset links.
    @Nullable final PathFragment relPath;
    @Nullable final Path target;

    ExpandedArtifact(Artifact artifact, PathFragment relPath, Path target) {
      this.artifact = artifact;
      this.relPath = relPath;
      this.target = target;
    }

    // TODO(adonovan): define equals/hashCode. Consider:
    // //foo generates bar/ (a tree artifact), with a single child, bar/baz, with this child an
    // explicitly named artifact (see b/70354083). Both of these artifacts are in its "files to
    // build". Then bar/ will be expanded to bar/baz, and we will have two identical (but not equal)
    // ExpandedArtifact objects, leading to a duplicate emission of bar/baz in BEP.
  }
}
