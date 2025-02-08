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
package com.google.devtools.build.lib.actions.cache;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.ArchivedTreeArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.cache.ActionCache.Entry.SerializableTreeArtifactValue;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.OutputPermissions;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test for the CompactPersistentActionCache class. */
@RunWith(JUnit4.class)
public class CompactPersistentActionCacheTest {

  private final Scratch scratch = new Scratch();
  private Path execRoot;
  private Path dataRoot;
  private Path mapFile;
  private Path journalFile;
  private final ManualClock clock = new ManualClock();
  private CompactPersistentActionCache cache;
  private ArtifactRoot artifactRoot;

  @Before
  public final void createFiles() throws Exception  {
    execRoot = scratch.resolve("/output");
    dataRoot = scratch.resolve("/cache/test.dat");
    cache = CompactPersistentActionCache.create(dataRoot, clock, NullEventHandler.INSTANCE);
    mapFile = CompactPersistentActionCache.cacheFile(dataRoot);
    journalFile = CompactPersistentActionCache.journalFile(dataRoot);
    artifactRoot = ArtifactRoot.asDerivedRoot(execRoot, ArtifactRoot.RootType.Output, "bin");
  }

  @Test
  public void testGetInvalidKey() {
    assertThat(cache.get("key")).isNull();
  }

  @Test
  public void testPutAndGet() {
    String key = "key";
    putKey(key);
    ActionCache.Entry readentry = cache.get(key);
    assertThat(readentry).isNotNull();
    assertThat(readentry.toString()).isEqualTo(cache.get(key).toString());
    assertThat(mapFile.exists()).isFalse();
  }

  @Test
  public void testPutAndRemove() {
    String key = "key";
    putKey(key);
    cache.remove(key);
    assertThat(cache.get(key)).isNull();
    assertThat(mapFile.exists()).isFalse();
  }

  @Test
  public void testGetSize() {
    // initial state.
    assertThat(cache.size()).isEqualTo(0);

    String key = "key";
    putKey(key);
    // the inserted key, and the validation key
    assertThat(cache.size()).isEqualTo(2);

    cache.remove(key);
    // the validation key
    assertThat(cache.size()).isEqualTo(1);

    cache.clear();
    assertThat(cache.size()).isEqualTo(0);
  }

  @Test
  public void testSaveDiscoverInputs() throws Exception {
    assertSave(true);
  }

  @Test
  public void testSaveNoDiscoverInputs() throws Exception {
    assertSave(false);
  }

  private void assertSave(boolean discoverInputs) throws Exception {
    String key = "key";
    putKey(key, discoverInputs);
    cache.save();
    assertThat(mapFile.exists()).isTrue();
    assertThat(journalFile.exists()).isFalse();

    CompactPersistentActionCache newcache =
        CompactPersistentActionCache.create(dataRoot, clock, NullEventHandler.INSTANCE);
    ActionCache.Entry readentry = newcache.get(key);
    assertThat(readentry).isNotNull();
    assertThat(readentry.toString()).isEqualTo(cache.get(key).toString());
  }

  @Test
  public void testIncrementalSave() throws IOException {
    for (int i = 0; i < 300; i++) {
      putKey(Integer.toString(i));
    }
    assertFullSave();

    // Add 2 entries to 300. Might as well just leave them in the journal.
    putKey("abc");
    putKey("123");
    assertIncrementalSave(cache);

    // Make sure we have all the entries, including those in the journal,
    // after deserializing into a new cache.
    CompactPersistentActionCache newcache =
        CompactPersistentActionCache.create(dataRoot, clock, NullEventHandler.INSTANCE);
    for (int i = 0; i < 100; i++) {
      assertKeyEquals(cache, newcache, Integer.toString(i));
    }
    assertKeyEquals(cache, newcache, "abc");
    assertKeyEquals(cache, newcache, "123");
    putKey("xyz", newcache, true);
    assertIncrementalSave(newcache);

    // Make sure we can see previous journal values after a second incremental save.
    CompactPersistentActionCache newerCache =
        CompactPersistentActionCache.create(dataRoot, clock, NullEventHandler.INSTANCE);
    for (int i = 0; i < 100; i++) {
      assertKeyEquals(cache, newerCache, Integer.toString(i));
    }
    assertKeyEquals(cache, newerCache, "abc");
    assertKeyEquals(cache, newerCache, "123");
    assertThat(newerCache.get("xyz")).isNotNull();
    assertThat(newerCache.get("not_a_key")).isNull();

    // Add another 10 entries. This should not be incremental.
    for (int i = 300; i < 310; i++) {
      putKey(Integer.toString(i));
    }
    assertFullSave();
  }

  @Test
  public void testRemoveIf() throws IOException {
    // Add 100 entries, 5 of which discover inputs, and do a full save.
    for (int i = 0; i < 100; i++) {
      putKey(Integer.toString(i), i % 20 == 0);
    }
    assertFullSave();

    // Remove entries that discover inputs and flush the journal.
    cache.removeIf(e -> e.discoversInputs());
    assertIncrementalSave(cache);

    // Check that the entries that discover inputs are gone, and the rest are still there.
    for (int i = 0; i < 100; i++) {
      ActionCache.Entry entry = cache.get(Integer.toString(i));
      if (i % 20 == 0) {
        assertThat(entry).isNull();
      } else {
        assertThat(entry).isNotNull();
      }
    }

    // Make sure we get the same result after deserializing into a new cache.
    CompactPersistentActionCache newerCache =
        CompactPersistentActionCache.create(dataRoot, clock, NullEventHandler.INSTANCE);
    for (int i = 0; i < 100; i++) {
      ActionCache.Entry entry = newerCache.get(Integer.toString(i));
      if (i % 20 == 0) {
        assertThat(entry).isNull();
      } else {
        assertThat(entry).isNotNull();
      }
    }
  }

  // Regression test to check that CompactActionCacheEntry.toString does not mutate the object.
  // Mutations may result in IllegalStateException.
  @SuppressWarnings("ReturnValueIgnored")
  @Test
  public void testEntryToStringIsIdempotent() {
    ActionCache.Entry entry =
        new ActionCache.Entry("actionKey", ImmutableMap.of(), false, OutputPermissions.READONLY);
    entry.toString();
    entry.addInputFile(
        PathFragment.create("foo/bar"), FileArtifactValue.createForDirectoryWithMtime(1234));
    entry.toString();
    entry.getFileDigest();
    entry.toString();
  }

  private void assertToStringIsntTooBig(int numRecords) {
    for (int i = 0; i < numRecords; i++) {
      putKey(Integer.toString(i));
    }
    String val = cache.toString();
    assertThat(val).startsWith("Action cache (" + numRecords + " records):\n");
    assertWithMessage(val).that(val.length()).isAtMost(2000);
    // Cache was too big to print out fully.
    if (numRecords > 10) {
      assertThat(val).endsWith("...");
    }
  }

  @Test
  public void testToStringIsntTooBig() {
    assertToStringIsntTooBig(3);
    assertToStringIsntTooBig(3000);
  }

  private FileArtifactValue createLocalMetadata(Artifact artifact, String content)
      throws IOException {
    artifact.getPath().getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContentAsLatin1(artifact.getPath(), content);
    return FileArtifactValue.createForTesting(artifact.getPath());
  }

  private FileArtifactValue createRemoteMetadata(
      Artifact artifact,
      String content,
      @Nullable Instant expirationTime,
      @Nullable PathFragment resolvedPath) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    byte[] digest =
        artifact
            .getPath()
            .getFileSystem()
            .getDigestFunction()
            .getHashFunction()
            .hashBytes(bytes)
            .asBytes();
    FileArtifactValue metadata =
        FileArtifactValue.createForRemoteFileWithMaterializationData(
            digest, bytes.length, 1, expirationTime);
    if (resolvedPath != null) {
      metadata = FileArtifactValue.createFromExistingWithResolvedPath(metadata, resolvedPath);
    }
    return metadata;
  }

  private FileArtifactValue createRemoteMetadata(
      Artifact artifact, String content, @Nullable PathFragment resolvedPath) {
    return createRemoteMetadata(artifact, content, /* expirationTime= */ null, resolvedPath);
  }

  private FileArtifactValue createRemoteMetadata(Artifact artifact, String content) {
    return createRemoteMetadata(artifact, content, /* resolvedPath= */ null);
  }

  private TreeArtifactValue createTreeMetadata(
      SpecialArtifact parent,
      ImmutableMap<String, FileArtifactValue> children,
      Optional<FileArtifactValue> archivedArtifactValue,
      Optional<PathFragment> resolvedPath) {
    TreeArtifactValue.Builder builder = TreeArtifactValue.newBuilder(parent);
    for (Map.Entry<String, FileArtifactValue> entry : children.entrySet()) {
      builder.putChild(
          Artifact.TreeFileArtifact.createTreeOutput(parent, entry.getKey()), entry.getValue());
    }
    archivedArtifactValue.ifPresent(
        metadata -> {
          ArchivedTreeArtifact artifact = ArchivedTreeArtifact.createForTree(parent);
          builder.setArchivedRepresentation(
              TreeArtifactValue.ArchivedRepresentation.create(artifact, metadata));
        });
    if (resolvedPath.isPresent()) {
      builder.setResolvedPath(resolvedPath.get());
    }
    return builder.build();
  }

  @Test
  public void putAndGet_savesRemoteFileMetadata() {
    String key = "key";
    ActionCache.Entry entry =
        new ActionCache.Entry(key, ImmutableMap.of(), false, OutputPermissions.READONLY);
    Artifact artifact = ActionsTestUtil.DUMMY_ARTIFACT;
    FileArtifactValue metadata = createRemoteMetadata(artifact, "content");
    entry.addOutputFile(artifact, metadata, /*saveFileMetadata=*/ true);

    cache.put(key, entry);
    entry = cache.get(key);

    assertThat(entry.getOutputFile(artifact)).isEqualTo(metadata);
  }

  @Test
  public void putAndGet_savesRemoteFileMetadata_withExpirationTime() {
    String key = "key";
    ActionCache.Entry entry =
        new ActionCache.Entry(key, ImmutableMap.of(), false, OutputPermissions.READONLY);
    Artifact artifact = ActionsTestUtil.DUMMY_ARTIFACT;
    Instant expirationTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    FileArtifactValue metadata =
        createRemoteMetadata(artifact, "content", expirationTime, /* resolvedPath= */ null);
    entry.addOutputFile(artifact, metadata, /* saveFileMetadata= */ true);

    cache.put(key, entry);
    entry = cache.get(key);

    assertThat(entry.getOutputFile(artifact).getExpirationTime()).isEqualTo(expirationTime);
  }

  @Test
  public void putAndGet_savesRemoteFileMetadata_withResolvedPath() {
    String key = "key";
    ActionCache.Entry entry =
        new ActionCache.Entry(key, ImmutableMap.of(), false, OutputPermissions.READONLY);
    Artifact artifact = ActionsTestUtil.DUMMY_ARTIFACT;
    FileArtifactValue metadata =
        createRemoteMetadata(artifact, "content", execRoot.getRelative("some/path").asFragment());
    entry.addOutputFile(artifact, metadata, /*saveFileMetadata=*/ true);

    cache.put(key, entry);
    entry = cache.get(key);

    assertThat(entry.getOutputFile(artifact)).isEqualTo(metadata);
  }

  @Test
  public void putAndGet_ignoresLocalFileMetadata() throws IOException {
    String key = "key";
    ActionCache.Entry entry =
        new ActionCache.Entry(key, ImmutableMap.of(), false, OutputPermissions.READONLY);
    Artifact artifact = ActionsTestUtil.DUMMY_ARTIFACT;
    FileArtifactValue metadata = createLocalMetadata(artifact, "content");
    entry.addOutputFile(artifact, metadata, /*saveFileMetadata=*/ true);

    cache.put(key, entry);
    entry = cache.get(key);

    assertThat(entry.getOutputFile(artifact)).isNull();
  }

  @Test
  public void putAndGet_treeMetadata_onlySavesRemoteFileMetadata() throws IOException {
    String key = "key";
    ActionCache.Entry entry =
        new ActionCache.Entry(key, ImmutableMap.of(), false, OutputPermissions.READONLY);
    SpecialArtifact artifact =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(
            artifactRoot, PathFragment.create("bin/dummy"));
    TreeArtifactValue metadata =
        createTreeMetadata(
            artifact,
            ImmutableMap.of(
                "file1",
                    createRemoteMetadata(
                        Artifact.TreeFileArtifact.createTreeOutput(
                            artifact, PathFragment.create("file1")),
                        "content1"),
                "file2",
                    createLocalMetadata(
                        Artifact.TreeFileArtifact.createTreeOutput(
                            artifact, PathFragment.create("file2")),
                        "content2")),
            /* archivedArtifactValue= */ Optional.empty(),
            /* resolvedPath= */ Optional.empty());
    entry.addOutputTree(artifact, metadata, /* saveTreeMetadata= */ true);

    cache.put(key, entry);
    entry = cache.get(key);

    assertThat(entry.getOutputTree(artifact))
        .isEqualTo(
            SerializableTreeArtifactValue.create(
                ImmutableMap.of(
                    "file1",
                    createRemoteMetadata(
                        Artifact.TreeFileArtifact.createTreeOutput(
                            artifact, PathFragment.create("file1")),
                        "content1")),
                /* archivedFileValue= */ Optional.empty(),
                /* resolvedPath= */ Optional.empty()));
  }

  @Test
  public void putAndGet_treeMetadata_savesRemoteArchivedArtifact() {
    String key = "key";
    ActionCache.Entry entry =
        new ActionCache.Entry(key, ImmutableMap.of(), false, OutputPermissions.READONLY);
    SpecialArtifact artifact =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(
            artifactRoot, PathFragment.create("bin/dummy"));
    TreeArtifactValue metadata =
        createTreeMetadata(
            artifact,
            ImmutableMap.of(),
            Optional.of(createRemoteMetadata(artifact, "content")),
            /* resolvedPath= */ Optional.empty());
    entry.addOutputTree(artifact, metadata, /* saveTreeMetadata= */ true);

    cache.put(key, entry);
    entry = cache.get(key);

    assertThat(entry.getOutputTree(artifact))
        .isEqualTo(
            SerializableTreeArtifactValue.create(
                ImmutableMap.of(),
                Optional.of(createRemoteMetadata(artifact, "content")),
                Optional.empty()));
  }

  @Test
  public void putAndGet_treeMetadata_ignoresLocalArchivedArtifact() throws IOException {
    String key = "key";
    ActionCache.Entry entry =
        new ActionCache.Entry(key, ImmutableMap.of(), false, OutputPermissions.READONLY);
    SpecialArtifact artifact =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(
            artifactRoot, PathFragment.create("bin/dummy"));
    TreeArtifactValue metadata =
        createTreeMetadata(
            artifact,
            ImmutableMap.of(),
            Optional.of(
                createLocalMetadata(
                    ActionsTestUtil.createArtifact(artifactRoot, "bin/archive"), "content")),
            /* resolvedPath= */ Optional.empty());
    entry.addOutputTree(artifact, metadata, /* saveTreeMetadata= */ true);

    cache.put(key, entry);
    entry = cache.get(key);

    assertThat(entry.getOutputTree(artifact)).isNull();
  }

  @Test
  public void putAndGet_treeMetadata_savesResolvedPath() {
    String key = "key";
    PathFragment resolvedPath = execRoot.getRelative("some/path").asFragment();
    ActionCache.Entry entry =
        new ActionCache.Entry(key, ImmutableMap.of(), false, OutputPermissions.READONLY);
    SpecialArtifact artifact =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(
            artifactRoot, PathFragment.create("bin/dummy"));
    TreeArtifactValue metadata =
        createTreeMetadata(
            artifact,
            ImmutableMap.of(),
            /* archivedArtifactValue= */ Optional.empty(),
            Optional.of(resolvedPath));
    entry.addOutputTree(artifact, metadata, /* saveTreeMetadata= */ true);

    cache.put(key, entry);
    entry = cache.get(key);

    assertThat(entry.getOutputTree(artifact))
        .isEqualTo(
            SerializableTreeArtifactValue.create(
                ImmutableMap.of(),
                /* archivedFileValue= */ Optional.empty(),
                Optional.of(resolvedPath)));
  }

  private static void assertKeyEquals(ActionCache cache1, ActionCache cache2, String key) {
    Object entry = cache1.get(key);
    assertThat(entry).isNotNull();
    assertThat(cache2.get(key).toString()).isEqualTo(entry.toString());
  }

  private void assertFullSave() throws IOException {
    cache.save();
    assertThat(mapFile.exists()).isTrue();
    assertThat(journalFile.exists()).isFalse();
  }

  private void assertIncrementalSave(ActionCache ac) throws IOException {
    ac.save();
    assertThat(mapFile.exists()).isTrue();
    assertThat(journalFile.exists()).isTrue();
  }

  private void putKey(String key) {
    putKey(key, cache, false);
  }

  private void putKey(String key, boolean discoversInputs) {
    putKey(key, cache, discoversInputs);
  }

  private void putKey(String key, ActionCache ac, boolean discoversInputs) {
    ActionCache.Entry entry =
        new ActionCache.Entry(
            key, ImmutableMap.of("k", "v"), discoversInputs, OutputPermissions.READONLY);
    entry.getFileDigest();
    ac.put(key, entry);
  }
}
