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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.cache.CompactPersistentActionCache;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.testutil.BlazeTestUtils;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.UnixGlob;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * These tests are similar to {@link TimestampBuilderTest}, but uses {@link
 * CompactPersistentActionCache} in the builder.
 */
@RunWith(JUnit4.class)
public class TimestampBuilderWithCompactPersistentActionCacheTest extends TimestampBuilderTestCase {
  private final StoredEventHandler storedEventHandler = new StoredEventHandler();
  private Path cacheRoot;
  private CompactPersistentActionCache cache;

  @Before
  public final void setCache() throws Exception  {
    // BlazeRuntime.setupLogging(Level.FINEST);  // Uncomment this for debugging.

    cacheRoot = scratch.dir("cacheRoot");
    cache = createCache();
  }

  private CompactPersistentActionCache createCache() throws IOException {
    return CompactPersistentActionCache.create(cacheRoot, clock, storedEventHandler);
  }

  private static NestedSet<Artifact> asNestedSet(Artifact... artifacts) {
    return NestedSetBuilder.create(Order.STABLE_ORDER, artifacts);
  }

  /**
   * Creates and returns a new caching builder based on a given {@code cache}.
   */
  private Builder persistentBuilder(CompactPersistentActionCache cache) throws Exception {
    return createBuilder(cache);
  }

  // TODO(blaze-team): (2009) :
  // - test timestamp monotonicity is not required (i.e. set mtime backwards)
  // - test change of key causes rebuild

  @Test
  public void testUnneededInputs() throws Exception {
    Artifact hello = createSourceArtifact("hello");
    hello.getPath().getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(hello.getPath(), "content1");
    Artifact optional = createSourceArtifact("hello.optional");
    Artifact goodbye = createDerivedArtifact("goodbye");
    Button button = createActionButton(asNestedSet(hello, optional), ImmutableSet.of(goodbye));

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button.pressed).isTrue(); // built

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button.pressed).isFalse(); // not rebuilt

    // Creating a new persistent cache does not cause a rebuild
    cache.save();
    cache = createCache();
    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), hello);
    assertThat(button.pressed).isFalse(); // not rebuilt

    BlazeTestUtils.makeEmptyFile(optional.getPath());
    FileSystemUtils.writeContent(hello.getPath(), "content2");

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button.pressed).isTrue(); // built

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button.pressed).isFalse(); // not rebuilt

    optional.getPath().delete();
    FileSystemUtils.writeContent(hello.getPath(), "content3");

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button.pressed).isTrue(); // built

    // Creating a new persistent cache does not cause a rebuild
    cache.save();
    cache = createCache();
    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), hello);
    assertThat(button.pressed).isFalse(); // not rebuilt
  }

  @Test
  public void testPersistentCache_modifyingInputCausesActionReexecution() throws Exception {
    // /hello -> [action] -> /goodbye
    Artifact hello = createSourceArtifact("hello");
    BlazeTestUtils.makeEmptyFile(hello.getPath());
    Artifact goodbye = createDerivedArtifact("goodbye");
    Button button = createActionButton(asNestedSet(hello), ImmutableSet.of(goodbye));

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button.pressed).isTrue(); // built

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button.pressed).isFalse(); // not rebuilt

    hello.getPath().setWritable(true);
    FileSystemUtils.writeContent(hello.getPath(), "new content");

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button.pressed).isTrue(); // rebuilt

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button.pressed).isFalse(); // not rebuilt

    // Creating a new persistent cache does not cause a rebuild
    cache.save();
    buildArtifacts(persistentBuilder(createCache()), goodbye);
    assertThat(button.pressed).isFalse(); // not rebuilt
  }

  @Test
  public void testModifyingInputCausesActionReexecution() throws Exception {
    // /hello -> [action] -> /goodbye
    Artifact hello = createSourceArtifact("hello");
    hello.getPath().getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(hello.getPath(), "content1");
    Artifact goodbye = createDerivedArtifact("goodbye");
    Button button = createActionButton(asNestedSet(hello), ImmutableSet.of(goodbye));

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button.pressed).isTrue(); // built

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button.pressed).isFalse(); // not rebuilt

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button.pressed).isFalse(); // still not rebuilt

    FileSystemUtils.writeContent(hello.getPath(), "content2");

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button.pressed).isTrue(); // rebuilt

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button.pressed).isFalse(); // not rebuilt

    // Creating a new persistent cache does not cause a rebuild
    cache.save();
    buildArtifacts(persistentBuilder(createCache()), goodbye);
    assertThat(button.pressed).isFalse(); // not rebuilt
  }

  @Test
  public void testArtifactOrderingDoesNotMatter() throws Exception {
    // (/hello,/there) -> [action] -> /goodbye

    Artifact hello = createSourceArtifact("hello");
    Artifact there = createSourceArtifact("there");
    hello.getPath().getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(hello.getPath(), "hello");
    FileSystemUtils.writeContent(there.getPath(), "there");
    Artifact goodbye = createDerivedArtifact("goodbye");
    Button button = createActionButton(asNestedSet(hello, there), ImmutableSet.of(goodbye));

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button.pressed).isTrue(); // built

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button.pressed).isFalse(); // not rebuilt

    // Now create duplicate graph, with swapped order.
    clearActions();
    Artifact goodbye2 = createDerivedArtifact("goodbye");
    Button button2 = createActionButton(asNestedSet(there, hello), ImmutableSet.of(goodbye2));

    button2.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button2.pressed).isFalse(); // still not rebuilt
  }

  @Test
  public void testOldCacheKeysAreCleanedUp() throws Exception {
    // [action1] -> (/goodbye), cache key will be /goodbye
    Artifact goodbye = createDerivedArtifact("goodbye");
    goodbye.getPath().getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(goodbye.getPath(), "test");
    Button button = createActionButton(emptyNestedSet, ImmutableSet.of(goodbye));

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button.pressed).isTrue(); // built

    // action1 is cached using the cache key /goodbye.
    assertThat(cache.get(goodbye.getExecPathString())).isNotNull();

    // [action2] -> (/hello,/goodbye), cache key will be /hello
    clearActions();
    Artifact hello = createDerivedArtifact("hello");
    Artifact goodbye2 = createDerivedArtifact("goodbye");
    Button button2 = createActionButton(emptyNestedSet, ImmutableSet.of(hello, goodbye2));

    button2.pressed = false;
    buildArtifacts(persistentBuilder(cache), hello, goodbye2);
    assertThat(button2.pressed).isTrue(); // rebuilt

    // action2 is cached using the cache key /hello.
    assertThat(cache.get(hello.getExecPathString())).isNotNull();

    // Now, action1 should no longer be in the cache.
    assertThat(cache.get(goodbye.getExecPathString())).isNull();
  }

  @Test
  public void testArtifactNamesMatter() throws Exception {
    // /hello -> [action] -> /goodbye

    Artifact hello = createSourceArtifact("hello");
    hello.getPath().getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(hello.getPath(), "hello");
    Artifact goodbye = createDerivedArtifact("goodbye");
    Button button = createActionButton(asNestedSet(hello), ImmutableSet.of(goodbye));

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button.pressed).isTrue(); // built

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button.pressed).isFalse(); // not rebuilt

    // Now create duplicate graph, replacing "hello" with "hi".
    clearActions();
    Artifact hi = createSourceArtifact("hi");
    hi.getPath().getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(hi.getPath(), "hello");
    Artifact goodbye2 = createDerivedArtifact("goodbye");
    Button button2 = createActionButton(asNestedSet(hi), ImmutableSet.of(goodbye2));

    button2.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye2);
    assertThat(button2.pressed).isTrue(); // name changed. must rebuild.
  }

  /**
   * Tests that changing timestamp of the input file without changing it content
   * does not cause action reexecution when metadata cache uses file digests in
   * addition to the timestamp.
   */
  @Test
  public void testModifyingTimestampOnlyDoesNotCauseActionReexecution() throws Exception {
    // /hello -> [action] -> /goodbye
    Artifact hello = createSourceArtifact("hello");
    hello.getPath().getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(hello.getPath(), "content1");
    Artifact goodbye = createDerivedArtifact("goodbye");
    Button button = createActionButton(asNestedSet(hello), ImmutableSet.of(goodbye));

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button.pressed).isTrue(); // built

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), goodbye);
    assertThat(button.pressed).isFalse(); // not rebuilt

    // Creating a new persistent caches, including metadata cache does not cause
    // a rebuild
    cache.save();
    Builder builder = persistentBuilder(createCache());
    buildArtifacts(builder, goodbye);
    assertThat(button.pressed).isFalse(); // not rebuilt
  }

  @Test
  public void testPersistentCache_modifyingOutputCausesActionReexecution() throws Exception {
    // [action] -> /hello
    Artifact hello = createDerivedArtifact("hello");
    Button button = createActionButton(emptyNestedSet, ImmutableSet.of(hello));

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), hello);
    assertThat(button.pressed).isTrue(); // built

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), hello);
    assertThat(button.pressed).isFalse(); // not rebuilt

    hello.getPath().setWritable(true);
    FileSystemUtils.writeContent(hello.getPath(), "new content");

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), hello);
    assertThat(button.pressed).isTrue(); // rebuilt

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), hello);
    assertThat(button.pressed).isFalse(); // not rebuilt

    // Creating a new persistent cache does not cause a rebuild
    cache.save();
    buildArtifacts(persistentBuilder(createCache()), hello);
    assertThat(button.pressed).isFalse(); // not rebuilt
  }

  @Test
  public void testPersistentCache_missingFilenameIndexCausesActionReexecution() throws Exception {
    // [action] -> /hello
    Artifact hello = createDerivedArtifact("hello");
    Button button = createActionButton(emptyNestedSet, ImmutableSet.of(hello));

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), hello);
    assertThat(button.pressed).isTrue(); // built

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), hello);
    assertThat(button.pressed).isFalse(); // not rebuilt

    hello.getPath().setWritable(true);
    FileSystemUtils.writeContent(hello.getPath(), "new content");

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), hello);
    assertThat(button.pressed).isTrue(); // rebuilt

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), hello);
    assertThat(button.pressed).isFalse(); // not rebuilt

    // Creating a new persistent cache does not cause a rebuild
    cache.save();

    // Remove filename index file.
    assertThat(
            Iterables.getOnlyElement(
                    new UnixGlob.Builder(cacheRoot, SyscallCache.NO_CACHE)
                        .addPattern("filename_index*")
                        .globInterruptible())
                .delete())
        .isTrue();

    // Now first cache creation attempt should cause IOException while renaming corrupted files.
    // Second attempt will initialize empty cache, causing rebuild.
    assertThat(storedEventHandler.getEvents()).isEmpty();
    buildArtifacts(persistentBuilder(createCache()), hello);
    assertThat(storedEventHandler.getEvents()).hasSize(1);
    assertThat(storedEventHandler.getEvents().get(0).getMessage())
        .contains("Failed action cache referential integrity check");

    assertThat(button.pressed).isTrue(); // rebuilt due to the missing filename index
  }

  @Test
  public void testPersistentCache_failedIntegrityCheckCausesActionReexecution() throws Exception {
    // [action] -> /hello
    Artifact hello = createDerivedArtifact("hello");
    Button button = createActionButton(emptyNestedSet, ImmutableSet.of(hello));

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), hello);
    assertThat(button.pressed).isTrue(); // built

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), hello);
    assertThat(button.pressed).isFalse(); // not rebuilt

    hello.getPath().setWritable(true);
    FileSystemUtils.writeContent(hello.getPath(), "new content");

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), hello);
    assertThat(button.pressed).isTrue(); // rebuilt

    button.pressed = false;
    buildArtifacts(persistentBuilder(cache), hello);
    assertThat(button.pressed).isFalse(); // not rebuilt

    cache.save();

    // Get filename index path and store a copy of it.
    Path indexPath =
        Iterables.getOnlyElement(
            new UnixGlob.Builder(cacheRoot, SyscallCache.NO_CACHE)
                .addPattern("filename_index*")
                .globInterruptible());
    Path indexCopy = scratch.resolve("index_copy");
    FileSystemUtils.copyFile(indexPath, indexCopy);

    // Add extra records to the action cache and indexer.
    Artifact helloExtra = createDerivedArtifact("hello_extra");
    Button buttonExtra = createActionButton(emptyNestedSet, ImmutableSet.of(helloExtra));
    buildArtifacts(persistentBuilder(cache), helloExtra);
    assertThat(buttonExtra.pressed).isTrue(); // built

    cache.save();
    assertThat(indexPath.getFileSize()).isGreaterThan(indexCopy.getFileSize());

    // Validate current cache.
    buildArtifacts(persistentBuilder(createCache()), hello);
    assertThat(button.pressed).isFalse(); // not rebuilt

    // Restore outdated file index.
    FileSystemUtils.copyFile(indexCopy, indexPath);

    // Now first cache creation attempt should cause IOException while renaming corrupted files.
    // Second attempt will initialize empty cache, causing rebuild.
    assertThat(storedEventHandler.getEvents()).isEmpty();
    buildArtifacts(persistentBuilder(createCache()), hello);
    assertThat(storedEventHandler.getEvents()).hasSize(1);
    assertThat(storedEventHandler.getEvents().get(0).getMessage())
        .contains("Failed action cache referential integrity check");

    assertThat(button.pressed).isTrue(); // rebuilt due to the out-of-date index
  }
}
