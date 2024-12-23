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
package com.google.devtools.build.lib.vfs;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link DigestUtils}. */
@RunWith(JUnit4.class)
public final class DigestUtilsTest {

  @After
  public void tearDown() {
    DigestUtils.configureCache(/*maximumSize=*/ 0);
  }

  @Test
  public void testCache() throws Exception {
    AtomicInteger getFastDigestCounter = new AtomicInteger(0);
    AtomicInteger getDigestCounter = new AtomicInteger(0);

    FileSystem tracingFileSystem =
        new InMemoryFileSystem(DigestHashFunction.SHA256) {
          @Override
          protected byte[] getFastDigest(PathFragment path) {
            getFastDigestCounter.incrementAndGet();
            return null;
          }

          @Override
          protected byte[] getDigest(PathFragment path) throws IOException {
            getDigestCounter.incrementAndGet();
            return super.getDigest(path);
          }
        };

    DigestUtils.configureCache(/*maximumSize=*/ 100);

    Path file = tracingFileSystem.getPath("/file.txt");
    FileSystemUtils.writeContent(file, "some contents");

    byte[] digest = DigestUtils.getDigestWithManualFallback(file, SyscallCache.NO_CACHE);
    assertThat(getFastDigestCounter.get()).isEqualTo(1);
    assertThat(getDigestCounter.get()).isEqualTo(1);

    assertThat(DigestUtils.getDigestWithManualFallback(file, SyscallCache.NO_CACHE))
        .isEqualTo(digest);
    assertThat(getFastDigestCounter.get()).isEqualTo(2);
    assertThat(getDigestCounter.get()).isEqualTo(1); // Cached.

    DigestUtils.clearCache();

    assertThat(DigestUtils.getDigestWithManualFallback(file, SyscallCache.NO_CACHE))
        .isEqualTo(digest);
    assertThat(getFastDigestCounter.get()).isEqualTo(3);
    assertThat(getDigestCounter.get()).isEqualTo(2); // Not cached.
  }

  @Test
  public void manuallyComputeDigest() throws Exception {
    byte[] digest = {1, 2, 3};
    FileSystem noDigestFileSystem =
        new InMemoryFileSystem(DigestHashFunction.SHA256) {
          @Override
          protected byte[] getFastDigest(PathFragment path) {
            throw new AssertionError("Unexpected call to getFastDigest");
          }

          @Override
          protected byte[] getDigest(PathFragment path) {
            return digest;
          }
        };
    Path file = noDigestFileSystem.getPath("/f.txt");
    FileSystemUtils.writeContent(file, "contents");

    assertThat(DigestUtils.manuallyComputeDigest(file)).isEqualTo(digest);
  }

  @Test
  public void combineUnordered_commutative() {
    byte[] a = {1, 2, 3};
    byte[] b = {4, 5, 6};
    assertThat(DigestUtils.combineUnordered(a.clone(), b.clone()))
        .isEqualTo(DigestUtils.combineUnordered(b.clone(), a.clone()));
  }

  @Test
  public void combineUnordered_noCancellation() {
    byte[] a = {1, 2, 3};
    assertThat(DigestUtils.combineUnordered(a.clone(), a.clone()))
        .isNotEqualTo(new byte[] {0, 0, 0});
  }
}
