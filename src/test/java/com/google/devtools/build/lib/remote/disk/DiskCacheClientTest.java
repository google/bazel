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
package com.google.devtools.build.lib.remote.disk;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeNotNull;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.OutputDirectory;
import build.bazel.remote.execution.v2.OutputFile;
import build.bazel.remote.execution.v2.Tree;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.remote.Store;
import com.google.devtools.build.lib.remote.common.CacheNotFoundException;
import com.google.devtools.build.lib.remote.common.OutputDigestMismatchException;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient.ActionKey;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.bazel.BazelHashFunctions;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link DiskCacheClient}. */
@RunWith(JUnit4.class)
public class DiskCacheClientTest {
  private static final DigestUtil DIGEST_UTIL =
      new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256);
  private static final ExecutorService executorService =
      MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(1));

  private final FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
  private final Path root = fs.getPath("/disk_cache");
  private DiskCacheClient client;

  @Before
  public void setUp() throws Exception {
    client = new DiskCacheClient(root, DIGEST_UTIL, executorService, /* verifyDownloads= */ true);
  }

  @Test
  public void findMissingDigests_returnsAllDigests() throws Exception {
    ImmutableList<Digest> digests =
        ImmutableList.of(getDigest("foo"), getDigest("bar"), getDigest("baz"));
    assertThat(getFromFuture(client.findMissingDigests(digests)))
        .containsExactlyElementsIn(digests);
  }

  @Test
  public void toPath_forCas_forOldStyleHashFunction() throws Exception {
    Digest digest = Digest.newBuilder().setHash("0123456789abcdef").setSizeBytes(42).build();

    Path path = client.toPath(digest, Store.CAS);

    assertThat(path).isEqualTo(root.getRelative("cas/01/0123456789abcdef"));
  }

  @Test
  public void toPath_forAc_forOldStyleHashFunction() throws Exception {
    Digest digest = Digest.newBuilder().setHash("0123456789abcdef").setSizeBytes(42).build();

    Path path = client.toPath(digest, Store.AC);

    assertThat(path).isEqualTo(root.getRelative("ac/01/0123456789abcdef"));
  }

  @Test
  public void toPath_forCas_forNewStyleHashFunction() throws Exception {
    assumeNotNull(BazelHashFunctions.BLAKE3); // BLAKE3 not available in Blaze.

    DiskCacheClient client =
        new DiskCacheClient(
            root,
            new DigestUtil(SyscallCache.NO_CACHE, BazelHashFunctions.BLAKE3),
            executorService,
            /* verifyDownloads= */ true);
    Digest digest = Digest.newBuilder().setHash("0123456789abcdef").setSizeBytes(42).build();
    Path path = client.toPath(digest, Store.CAS);

    assertThat(path).isEqualTo(root.getRelative("blake3/cas/01/0123456789abcdef"));
  }

  @Test
  public void toPath_forAc_forNewStyleHashFunction() throws Exception {
    assumeNotNull(BazelHashFunctions.BLAKE3); // BLAKE3 not available in Blaze.

    DiskCacheClient client =
        new DiskCacheClient(
            root,
            new DigestUtil(SyscallCache.NO_CACHE, BazelHashFunctions.BLAKE3),
            executorService,
            /* verifyDownloads= */ true);
    Digest digest = Digest.newBuilder().setHash("0123456789abcdef").setSizeBytes(42).build();
    Path path = client.toPath(digest, Store.AC);

    assertThat(path).isEqualTo(root.getRelative("blake3/ac/01/0123456789abcdef"));
  }

  @Test
  public void uploadFile_whenMissing_populatesCas() throws Exception {
    assertThat(root.exists()).isTrue();
    Path file = fs.getPath("/file");
    FileSystemUtils.writeContent(file, "contents");
    Digest digest = getDigest("contents");

    var unused = getFromFuture(client.uploadFile(digest, file));

    assertThat(FileSystemUtils.readContentToString(getCasPath(digest))).isEqualTo("contents");
  }

  @Test
  public void uploadFile_whenPresent_updatesMtime() throws Exception {
    Path file = fs.getPath("/file");
    FileSystemUtils.writeContent(file, "contents");
    Digest digest = getDigest("contents");

    // The contents would match under normal operation. This serves to check that we don't
    // unnecessarily overwrite the file.
    Path path = populateCas(digest, "existing contents");

    var unused = getFromFuture(client.uploadFile(digest, file));

    assertThat(FileSystemUtils.readContentToString(path)).isEqualTo("existing contents");
    assertThat(path.getLastModifiedTime()).isNotEqualTo(0);
  }

  @Test
  public void uploadBlob_whenMissing_populatesCas() throws Exception {
    ByteString blob = ByteString.copyFromUtf8("contents");
    Digest digest = getDigest("contents");

    var unused = getFromFuture(client.uploadBlob(digest, blob));

    assertThat(FileSystemUtils.readContentToString(getCasPath(digest))).isEqualTo("contents");
  }

  @Test
  public void uploadBlob_whenPresent_updatesMtime() throws Exception {
    ByteString blob = ByteString.copyFromUtf8("contents");
    Digest digest = getDigest("contents");

    // The contents would match under normal operation. This serves to check that we don't
    // unnecessarily overwrite the file.
    Path path = populateCas(digest, "existing contents");

    var unused = getFromFuture(client.uploadBlob(digest, blob));

    assertThat(FileSystemUtils.readContentToString(path)).isEqualTo("existing contents");
    assertThat(path.getLastModifiedTime()).isNotEqualTo(0);
  }

  @Test
  public void uploadActionResult_whenMissing_populatesAc() throws Exception {
    ActionKey actionKey = new ActionKey(getDigest("key"));
    ActionResult actionResult = ActionResult.newBuilder().setExitCode(42).build();
    Path path = getAcPath(actionKey);

    var unused = getFromFuture(client.uploadActionResult(actionKey, actionResult));

    assertThat(FileSystemUtils.readContent(path)).isEqualTo(actionResult.toByteArray());
  }

  @Test
  public void uploadActionResult_whenPresent_updatesMtime() throws Exception {
    ActionKey actionKey = new ActionKey(getDigest("key"));
    ActionResult actionResult = ActionResult.newBuilder().setExitCode(42).build();

    Path path = populateAc(actionKey, actionResult);

    // The contents would match under normal operation. This serves to check that we don't
    // unnecessarily overwrite the file.
    var unused =
        getFromFuture(client.uploadActionResult(actionKey, ActionResult.getDefaultInstance()));

    assertThat(FileSystemUtils.readContent(path)).isEqualTo(actionResult.toByteArray());
    assertThat(path.getLastModifiedTime()).isNotEqualTo(0);
  }

  @Test
  public void downloadBlob_whenPresent_returnsContents() throws Exception {
    Digest digest = getDigest("contents");
    populateCas(digest, "contents");
    Path out = fs.getPath("/out");

    var unused = getFromFuture(client.downloadBlob(digest, out.getOutputStream()));

    assertThat(FileSystemUtils.readContentToString(out)).isEqualTo("contents");
  }

  @Test
  public void downloadBlob_whenMissing_throwsCacheNotFoundException() throws Exception {
    Path out = fs.getPath("/out");

    assertThrows(
        CacheNotFoundException.class,
        () -> getFromFuture(client.downloadBlob(getDigest("contents"), out.getOutputStream())));
  }

  @Test
  public void downloadBlob_whenCorrupted_throwsOutputDigestMismatchException() throws Exception {
    Digest digest = getDigest("contents");
    populateCas(digest, "corrupted contents");
    Path out = fs.getPath("/out");

    assertThrows(
        OutputDigestMismatchException.class,
        () -> getFromFuture(client.downloadBlob(digest, out.getOutputStream())));
  }

  @Test
  public void downloadActionResult_whenPresent_returnsCachedActionResult() throws Exception {
    ActionKey actionKey = new ActionKey(getDigest("key"));
    ActionResult actionResult = ActionResult.newBuilder().setExitCode(42).build();

    Path path = populateAc(actionKey, actionResult);

    var result = getFromFuture(client.downloadActionResult(actionKey));

    assertThat(result).isEqualTo(actionResult);
    assertThat(path.getLastModifiedTime()).isNotEqualTo(0);
  }

  @Test
  public void downloadActionResult_whenMissing_returnsNull() throws Exception {
    ActionKey actionKey = new ActionKey(getDigest("key"));

    var result = getFromFuture(client.downloadActionResult(actionKey));

    assertThat(result).isNull();
  }

  @Test
  public void downloadActionResult_withReferencedBlobsPresent_updatesMtimeOnBlobs()
      throws Exception {
    Digest stdoutDigest = getDigest("stdout contents");
    Digest stderrDigest = getDigest("stderr contents");
    Digest fileDigest = getDigest("file contents");
    Digest treeFileDigest = getDigest("tree file contents");
    Tree tree = getTreeWithFile(treeFileDigest);
    Digest treeDigest = getDigest(tree);
    ActionKey actionKey = new ActionKey(getDigest("key"));
    ActionResult actionResult =
        ActionResult.newBuilder()
            .setStdoutDigest(stdoutDigest)
            .setStderrDigest(stderrDigest)
            .addOutputFiles(OutputFile.newBuilder().setDigest(fileDigest).build())
            .addOutputDirectories(OutputDirectory.newBuilder().setTreeDigest(getDigest(tree)))
            .build();

    Path acPath = populateAc(actionKey, actionResult);
    Path stdoutCasPath = populateCas(stdoutDigest, "stdout contents");
    Path stderrCasPath = populateCas(stderrDigest, "stderr contents");
    Path fileCasPath = populateCas(fileDigest, "file contents");
    Path treeCasPath = populateCas(treeDigest, tree);
    Path treeFileCasPath = populateCas(treeFileDigest, "tree file contents");

    var result = getFromFuture(client.downloadActionResult(actionKey));

    assertThat(result).isEqualTo(actionResult);
    assertThat(acPath.getLastModifiedTime()).isNotEqualTo(0);
    assertThat(stdoutCasPath.getLastModifiedTime()).isNotEqualTo(0);
    assertThat(stderrCasPath.getLastModifiedTime()).isNotEqualTo(0);
    assertThat(fileCasPath.getLastModifiedTime()).isNotEqualTo(0);
    assertThat(treeCasPath.getLastModifiedTime()).isNotEqualTo(0);
    assertThat(treeFileCasPath.getLastModifiedTime()).isNotEqualTo(0);
  }

  @Test
  public void downloadActionResult_withReferencedFileMissing_returnsNull() throws Exception {
    Digest fileDigest = getDigest("contents");
    ActionKey actionKey = new ActionKey(getDigest("key"));
    ActionResult actionResult =
        ActionResult.newBuilder()
            .addOutputFiles(OutputFile.newBuilder().setDigest(fileDigest).build())
            .build();

    populateAc(actionKey, actionResult);

    var result = getFromFuture(client.downloadActionResult(actionKey));

    assertThat(result).isNull();
  }

  @Test
  public void downloadActionResult_withReferencedTreeMissing_returnsNull() throws Exception {
    Digest fileDigest = getDigest("contents");
    Tree tree = getTreeWithFile(fileDigest);
    Digest treeDigest = getDigest(tree);
    ActionKey actionKey = new ActionKey(getDigest("key"));
    ActionResult actionResult =
        ActionResult.newBuilder()
            .addOutputDirectories(OutputDirectory.newBuilder().setTreeDigest(treeDigest))
            .build();

    populateAc(actionKey, actionResult);
    populateCas(fileDigest, "contents");

    var result = getFromFuture(client.downloadActionResult(actionKey));

    assertThat(result).isNull();
  }

  @Test
  public void downloadActionResult_withReferencedTreeFileMissing_returnsNull() throws Exception {
    Digest fileDigest = getDigest("contents");
    Tree tree = getTreeWithFile(fileDigest);
    Digest treeDigest = getDigest(tree);
    ActionKey actionKey = new ActionKey(getDigest("key"));
    ActionResult actionResult =
        ActionResult.newBuilder()
            .addOutputDirectories(OutputDirectory.newBuilder().setTreeDigest(treeDigest))
            .build();

    populateAc(actionKey, actionResult);
    populateCas(treeDigest, tree);

    var result = getFromFuture(client.downloadActionResult(actionKey));

    assertThat(result).isNull();
  }

  private Tree getTreeWithFile(Digest fileDigest) {
    return Tree.newBuilder()
        .addChildren(Directory.newBuilder().addFiles(FileNode.newBuilder().setDigest(fileDigest)))
        .build();
  }

  private Path getCasPath(Digest digest) {
    return client.toPath(digest, Store.CAS);
  }

  @CanIgnoreReturnValue
  private Path populateCas(Digest digest, String contents) throws IOException {
    return populateCas(digest, contents.getBytes(UTF_8));
  }

  @CanIgnoreReturnValue
  private Path populateCas(Digest digest, Message m) throws IOException {
    return populateCas(digest, m.toByteArray());
  }

  private Path populateCas(Digest digest, byte[] contents) throws IOException {
    Path path = getCasPath(digest);
    path.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(path, contents);
    path.setLastModifiedTime(0);
    return path;
  }

  private Path getAcPath(ActionKey actionKey) {
    return client.toPath(actionKey.getDigest(), Store.AC);
  }

  @CanIgnoreReturnValue
  private Path populateAc(ActionKey actionKey, ActionResult actionResult) throws IOException {
    Path path = getAcPath(actionKey);
    path.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(path, actionResult.toByteArray());
    path.setLastModifiedTime(0);
    return path;
  }

  private Digest getDigest(String contents) {
    return DIGEST_UTIL.computeAsUtf8(contents);
  }

  private Digest getDigest(Message m) {
    return DIGEST_UTIL.compute(m.toByteArray());
  }
}
