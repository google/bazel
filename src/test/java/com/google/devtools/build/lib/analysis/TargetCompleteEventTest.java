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

package com.google.devtools.build.lib.analysis;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.CompletionContext.FAILED_COMPLETION_CTX;
import static com.google.devtools.build.lib.analysis.TargetCompleteEvent.newFileFromArtifact;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionInputMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.CompletionContext;
import com.google.devtools.build.lib.actions.EventReportingArtifacts.ReportedArtifacts;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.analysis.TopLevelArtifactHelper.ArtifactsToBuild;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.analysis.util.AnalysisTestCase;
import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile;
import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile.LocalFileType;
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions.OutputGroupFileModes;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.File;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link TargetCompleteEvent}. */
@RunWith(JUnit4.class)
public class TargetCompleteEventTest extends AnalysisTestCase {

  @Test
  public void testReferencedSourceFile() throws Exception {
    scratch.file("BUILD", "filegroup(name = 'files', srcs = ['file'])");
    scratch.file("file", "content does not matter");
    ConfiguredTargetAndData ctAndData = getCtAndData("//:files");
    ArtifactsToBuild artifactsToBuild = getArtifactsToBuild(ctAndData);
    Artifact artifact = Iterables.getOnlyElement(artifactsToBuild.getAllArtifacts().toList());
    FileArtifactValue metadata =
        FileArtifactValue.createForNormalFile(new byte[] {1, 2, 3}, null, 10);
    CompletionContext completionContext =
        getCompletionContext(ImmutableMap.of(artifact, metadata), ImmutableMap.of());

    TargetCompleteEvent event =
        TargetCompleteEvent.successfulBuild(
            ctAndData,
            completionContext,
            artifactsToBuild.getAllArtifactsByOutputGroup(),
            /* announceTargetSummary= */ false);

    assertThat(event.referencedLocalFiles())
        .containsExactly(new LocalFile(artifact.getPath(), LocalFileType.OUTPUT_FILE, metadata));
  }

  @Test
  public void testReferencedSourceDirectory() throws Exception {
    scratch.file("BUILD", "filegroup(name = 'files', srcs = ['dir'])");
    scratch.file("dir/file", "content does not matter");
    ConfiguredTargetAndData ctAndData = getCtAndData("//:files");
    ArtifactsToBuild artifactsToBuild = getArtifactsToBuild(ctAndData);
    Artifact artifact = Iterables.getOnlyElement(artifactsToBuild.getAllArtifacts().toList());
    FileArtifactValue metadata = FileArtifactValue.createForDirectoryWithMtime(0);
    CompletionContext completionContext =
        getCompletionContext(ImmutableMap.of(artifact, metadata), ImmutableMap.of());

    TargetCompleteEvent event =
        TargetCompleteEvent.successfulBuild(
            ctAndData,
            completionContext,
            artifactsToBuild.getAllArtifactsByOutputGroup(),
            /* announceTargetSummary= */ false);

    assertThat(event.referencedLocalFiles())
        .containsExactly(
            new LocalFile(artifact.getPath(), LocalFileType.OUTPUT_DIRECTORY, metadata));
  }

  @Test
  public void testReferencedTreeArtifact() throws Exception {
    scratch.file(
        "defs.bzl",
        """
        def _impl(ctx):
            d = ctx.actions.declare_directory(ctx.label.name)
            ctx.actions.run_shell(outputs = [d], command = "does not matter")
            return DefaultInfo(files = depset([d]))

        dir = rule(_impl)
        """);
    scratch.file(
        "BUILD",
        "load(':defs.bzl', 'dir')",
        "dir(name = 'dir')",
        "filegroup(name = 'files', srcs = ['dir'])");
    ConfiguredTargetAndData ctAndData = getCtAndData("//:files");
    ArtifactsToBuild artifactsToBuild = getArtifactsToBuild(ctAndData);
    SpecialArtifact tree =
        (SpecialArtifact) Iterables.getOnlyElement(artifactsToBuild.getAllArtifacts().toList());
    TreeFileArtifact fileChild =
        TreeFileArtifact.createTreeOutput(tree, PathFragment.create("dir/file.txt"));
    FileArtifactValue fileMetadata =
        FileArtifactValue.createForNormalFile(new byte[] {1, 2, 3}, null, 10);
    // A TreeFileArtifact can be a directory, when materialized by a symlink.
    // See https://github.com/bazelbuild/bazel/issues/20418.
    TreeFileArtifact dirChild = TreeFileArtifact.createTreeOutput(tree, PathFragment.create("sym"));
    FileArtifactValue dirMetadata = FileArtifactValue.createForDirectoryWithMtime(123456789);
    TreeArtifactValue metadata =
        TreeArtifactValue.newBuilder(tree)
            .putChild(fileChild, fileMetadata)
            .putChild(dirChild, dirMetadata)
            .build();
    CompletionContext completionContext =
        getCompletionContext(ImmutableMap.of(), ImmutableMap.of(tree, metadata));

    TargetCompleteEvent event =
        TargetCompleteEvent.successfulBuild(
            ctAndData,
            completionContext,
            artifactsToBuild.getAllArtifactsByOutputGroup(),
            /* announceTargetSummary= */ false);

    assertThat(event.referencedLocalFiles())
        .containsExactly(
            new LocalFile(fileChild.getPath(), LocalFileType.OUTPUT_FILE, fileMetadata),
            new LocalFile(dirChild.getPath(), LocalFileType.OUTPUT_DIRECTORY, dirMetadata));
  }

  @Test
  public void testReferencedUnresolvedSymlink() throws Exception {
    scratch.file(
        "defs.bzl",
        """
        def _impl(ctx):
            s = ctx.actions.declare_symlink(ctx.label.name)
            ctx.actions.symlink(output = s, target_path = "does not matter")
            return DefaultInfo(files = depset([s]))

        sym = rule(_impl)
        """);
    scratch.file(
        "BUILD",
        "load(':defs.bzl', 'sym')",
        "sym(name = 'sym')",
        "filegroup(name = 'files', srcs = ['sym'])");
    ConfiguredTargetAndData ctAndData = getCtAndData("//:files");
    ArtifactsToBuild artifactsToBuild = getArtifactsToBuild(ctAndData);
    Artifact artifact = Iterables.getOnlyElement(artifactsToBuild.getAllArtifacts().toList());
    artifact.getPath().getParentDirectory().createDirectoryAndParents();
    artifact.getPath().createSymbolicLink(fileSystem.getPath("/some/path"));
    FileArtifactValue metadata = FileArtifactValue.createForUnresolvedSymlink(artifact.getPath());
    CompletionContext completionContext =
        getCompletionContext(ImmutableMap.of(artifact, metadata), ImmutableMap.of());

    TargetCompleteEvent event =
        TargetCompleteEvent.successfulBuild(
            ctAndData,
            completionContext,
            artifactsToBuild.getAllArtifactsByOutputGroup(),
            /* announceTargetSummary= */ false);

    assertThat(event.referencedLocalFiles())
        .containsExactly(new LocalFile(artifact.getPath(), LocalFileType.OUTPUT_SYMLINK, metadata));
  }

  /** Regression test for b/165671166. */
  @Test
  public void testFileProtoFromArtifactReencodesAsUtf8() throws Exception {
    if (OS.getCurrent() == OS.WINDOWS) {
      // Windows filesystems return paths with wide characters and don't suffer from the current
      // workaround where arbitrary bytes are represented to Java as Latin-1.
      return;
    }
    scratch.file("sh/BUILD", "filegroup(name = 'globby', srcs = glob(['dir/*']))");
    // Bytes are UTF-8 encoding of: sh/dir/圖片
    byte[] filenameBytes = {
      0x73, 0x68, 0x2f, 0x64, 0x69, 0x72, 0x2f, -27, -100, -106, -25, -119, -121
    };
    String utf8InLatin1FileName = new String(filenameBytes, ISO_8859_1);
    scratch.file(utf8InLatin1FileName, "content does not matter");
    ConfiguredTargetAndData ctAndData = getCtAndData("//sh:globby");
    ArtifactsToBuild artifactsToBuild = getArtifactsToBuild(ctAndData);

    TargetCompleteEvent event =
        TargetCompleteEvent.successfulBuild(
            ctAndData,
            FAILED_COMPLETION_CTX,
            artifactsToBuild.getAllArtifactsByOutputGroup(),
            /*announceTargetSummary=*/ false);

    ArrayList<File> fileProtos = new ArrayList<>();
    ReportedArtifacts reportedArtifacts = event.reportedArtifacts(OutputGroupFileModes.DEFAULT);
    for (NestedSet<Artifact> artifactSet : reportedArtifacts.artifacts) {
      for (Artifact a : artifactSet.toListInterruptibly()) {
        fileProtos.add(
            newFileFromArtifact(
                /* name= */ null,
                a,
                PathFragment.EMPTY_FRAGMENT,
                FAILED_COMPLETION_CTX,
                /* uri= */ null));
      }
    }
    // Bytes are the same but the encoding is actually UTF-8 as required of a protobuf string.
    String utf8FileName = new String(filenameBytes, UTF_8);
    assertThat(fileProtos).hasSize(1);
    assertThat(fileProtos.get(0).getName()).isEqualTo(utf8FileName);
  }

  private ConfiguredTargetAndData getCtAndData(String target) throws Exception {
    AnalysisResult result = update(target);
    ConfiguredTarget ct = Iterables.getOnlyElement(result.getTargetsToBuild());
    TargetAndConfiguration tac = Iterables.getOnlyElement(result.getTopLevelTargetsWithConfigs());
    var configuredTargetConfiguration =
        (BuildConfigurationValue)
            skyframeExecutor.getEvaluator().getExistingValue(ct.getConfigurationKey());
    return new ConfiguredTargetAndData(ct, tac.getTarget(), configuredTargetConfiguration, null);
  }

  private static ArtifactsToBuild getArtifactsToBuild(ConfiguredTargetAndData ctAndData) {
    TopLevelArtifactContext context =
        new TopLevelArtifactContext(false, false, false, OutputGroupInfo.DEFAULT_GROUPS);
    return TopLevelArtifactHelper.getAllArtifactsToBuild(ctAndData.getConfiguredTarget(), context);
  }

  private CompletionContext getCompletionContext(
      Map<Artifact, FileArtifactValue> metadata,
      Map<SpecialArtifact, TreeArtifactValue> treeMetadata) {
    ActionInputMap inputMap = new ActionInputMap(0);

    for (Map.Entry<Artifact, FileArtifactValue> entry : metadata.entrySet()) {
      inputMap.put(entry.getKey(), entry.getValue(), /* depOwner= */ null);
    }

    for (Map.Entry<SpecialArtifact, TreeArtifactValue> entry : treeMetadata.entrySet()) {
      inputMap.putTreeArtifact(entry.getKey(), entry.getValue(), /* depOwner= */ null);
    }

    return new CompletionContext(
        directories.getExecRoot(TestConstants.WORKSPACE_NAME),
        ImmutableMap.copyOf(treeMetadata),
        /* filesets= */ ImmutableMap.of(),
        ArtifactPathResolver.IDENTITY,
        inputMap,
        /* expandFilesets= */ false,
        /* fullyResolveFilesetLinks= */ false);
  }
}
