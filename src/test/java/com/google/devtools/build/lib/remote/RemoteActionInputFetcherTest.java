// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import build.bazel.remote.execution.v2.Digest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.eventbus.EventBus;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher.Priority;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher.Reason;
import com.google.devtools.build.lib.actions.ActionOutputDirectoryHelper;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.remote.common.BulkTransferException;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.options.RemoteOutputsMode;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.InMemoryCacheClient;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.OutputPermissions;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.common.options.Options;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RemoteActionInputFetcher}. */
@RunWith(JUnit4.class)
public class RemoteActionInputFetcherTest extends ActionInputPrefetcherTestBase {
  private static final RemoteOutputChecker DUMMY_REMOTE_OUTPUT_CHECKER =
      new RemoteOutputChecker(
          new JavaClock(), "build", RemoteOutputsMode.MINIMAL, ImmutableList.of());

  private RemoteOptions options;
  private DigestUtil digestUtil;

  @Override
  public void setUp() throws IOException {
    super.setUp();
    Path dev = fs.getPath("/dev");
    dev.createDirectory();
    dev.setWritable(false);
    options = Options.getDefaults(RemoteOptions.class);
    digestUtil = new DigestUtil(SyscallCache.NO_CACHE, HASH_FUNCTION);
  }

  @Override
  protected AbstractActionInputPrefetcher createPrefetcher(Map<HashCode, byte[]> cas) {
    CombinedCache combinedCache = newCombinedCache(options, digestUtil, cas);
    return new RemoteActionInputFetcher(
        new Reporter(eventBus),
        "none",
        "none",
        combinedCache,
        execRoot,
        tempPathGenerator,
        DUMMY_REMOTE_OUTPUT_CHECKER,
        ActionOutputDirectoryHelper.createForTesting(),
        OutputPermissions.READONLY);
  }

  @Test
  public void testStagingVirtualActionInput() throws Exception {
    // arrange
    CombinedCache combinedCache = newCombinedCache(options, digestUtil, new HashMap<>());
    RemoteActionInputFetcher actionInputFetcher =
        new RemoteActionInputFetcher(
            new Reporter(new EventBus()),
            "none",
            "none",
            combinedCache,
            execRoot,
            tempPathGenerator,
            DUMMY_REMOTE_OUTPUT_CHECKER,
            ActionOutputDirectoryHelper.createForTesting(),
            OutputPermissions.READONLY);
    VirtualActionInput a = ActionsTestUtil.createVirtualActionInput("file1", "hello world");

    // act
    wait(
        actionInputFetcher.prefetchFilesInterruptibly(
            action,
            ImmutableList.of(a),
            (ActionInput unused) -> null,
            Priority.MEDIUM,
            Reason.INPUTS));

    // assert
    Path p = execRoot.getRelative(a.getExecPath());
    assertThat(FileSystemUtils.readContent(p, StandardCharsets.UTF_8)).isEqualTo("hello world");
    assertThat(p.isExecutable()).isTrue();
    assertThat(actionInputFetcher.downloadedFiles()).isEmpty();
    assertThat(actionInputFetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void testStagingEmptyVirtualActionInput() throws Exception {
    // arrange
    CombinedCache combinedCache = newCombinedCache(options, digestUtil, new HashMap<>());
    RemoteActionInputFetcher actionInputFetcher =
        new RemoteActionInputFetcher(
            new Reporter(new EventBus()),
            "none",
            "none",
            combinedCache,
            execRoot,
            tempPathGenerator,
            DUMMY_REMOTE_OUTPUT_CHECKER,
            ActionOutputDirectoryHelper.createForTesting(),
            OutputPermissions.READONLY);

    // act
    wait(
        actionInputFetcher.prefetchFilesInterruptibly(
            action,
            ImmutableList.of(VirtualActionInput.EMPTY_MARKER),
            (ActionInput unused) -> null,
            Priority.MEDIUM,
            Reason.INPUTS));

    // assert that nothing happened
    assertThat(actionInputFetcher.downloadedFiles()).isEmpty();
    assertThat(actionInputFetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void prefetchFiles_missingFiles_failsWithSpecificMessage() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Artifact a = createRemoteArtifact("file1", "hello world", metadata, /* cas= */ new HashMap<>());
    AbstractActionInputPrefetcher prefetcher = createPrefetcher(new HashMap<>());

    var error =
        assertThrows(
            BulkTransferException.class,
            () ->
                wait(
                    prefetcher.prefetchFilesInterruptibly(
                        action,
                        ImmutableList.of(a),
                        metadata::get,
                        Priority.MEDIUM,
                        Reason.INPUTS)));

    assertThat(prefetcher.downloadedFiles()).isEmpty();
    assertThat(prefetcher.downloadsInProgress()).isEmpty();
    var m = metadata.get(a);
    var digest = DigestUtil.buildDigest(m.getDigest(), m.getSize());
    assertThat(error)
        .hasMessageThat()
        .contains(String.format("%s/%s", digest.getHash(), digest.getSizeBytes()));
  }

  private CombinedCache newCombinedCache(
      RemoteOptions options, DigestUtil digestUtil, Map<HashCode, byte[]> cas) {
    Map<Digest, byte[]> cacheEntries = Maps.newHashMapWithExpectedSize(cas.size());
    for (Map.Entry<HashCode, byte[]> entry : cas.entrySet()) {
      cacheEntries.put(
          DigestUtil.buildDigest(entry.getKey().asBytes(), entry.getValue().length),
          entry.getValue());
    }
    return new CombinedCache(
        new InMemoryCacheClient(cacheEntries), /* diskCacheClient= */ null, options, digestUtil);
  }
}
