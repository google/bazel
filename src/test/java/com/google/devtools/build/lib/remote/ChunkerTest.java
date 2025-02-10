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
package com.google.devtools.build.lib.remote;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.github.luben.zstd.Zstd;
import com.google.devtools.build.lib.remote.Chunker.Chunk;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient;
import com.google.protobuf.ByteString;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

/** Tests for {@link Chunker}. */
@RunWith(JUnit4.class)
public class ChunkerTest {

  @Test
  public void chunkingShouldWork() throws IOException {
    Random rand = new Random();
    byte[] expectedData = new byte[21];
    rand.nextBytes(expectedData);

    Chunker chunker = Chunker.builder().setInput(expectedData).setChunkSize(10).build();

    ByteArrayOutputStream actualData = new ByteArrayOutputStream();

    assertThat(chunker.hasNext()).isTrue();
    Chunk next = chunker.next();
    assertThat(next.getOffset()).isEqualTo(0);
    assertThat(next.getData()).hasSize(10);
    next.getData().writeTo(actualData);

    assertThat(chunker.hasNext()).isTrue();
    next = chunker.next();
    assertThat(next.getOffset()).isEqualTo(10);
    assertThat(next.getData()).hasSize(10);
    next.getData().writeTo(actualData);

    assertThat(chunker.hasNext()).isTrue();
    next = chunker.next();
    assertThat(next.getOffset()).isEqualTo(20);
    assertThat(next.getData()).hasSize(1);
    next.getData().writeTo(actualData);

    assertThat(chunker.hasNext()).isFalse();

    assertThat(actualData.toByteArray()).isEqualTo(expectedData);
  }

  @Test
  public void nextShouldThrowIfNoMoreData() throws IOException {
    byte[] data = new byte[10];
    Chunker chunker = Chunker.builder().setInput(data).setChunkSize(10).build();

    assertThat(chunker.hasNext()).isTrue();
    assertThat(chunker.next()).isNotNull();

    assertThat(chunker.hasNext()).isFalse();

    assertThrows(NoSuchElementException.class, () -> chunker.next());
  }

  @Test
  public void emptyData() throws Exception {
    var inp =
        new ByteArrayInputStream(new byte[0]) {
          private boolean closed;

          @Override
          public void close() throws IOException {
            closed = true;
            super.close();
          }
        };
    Chunker chunker = Chunker.builder().setInput(0, () -> inp).build();

    assertThat(chunker.hasNext()).isTrue();

    Chunk next = chunker.next();

    assertThat(next).isNotNull();
    assertThat(next.getData()).isEmpty();
    assertThat(next.getOffset()).isEqualTo(0);

    assertThat(chunker.hasNext()).isFalse();
    assertThat(inp.closed).isTrue();

    assertThrows(NoSuchElementException.class, () -> chunker.next());
  }

  @Test
  public void reset() throws Exception {
    byte[] data = new byte[] {1, 2, 3};
    Chunker chunker = Chunker.builder().setInput(data).setChunkSize(1).build();

    assertNextEquals(chunker, (byte) 1);
    assertNextEquals(chunker, (byte) 2);

    chunker.reset();

    assertNextEquals(chunker, (byte) 1);
    assertNextEquals(chunker, (byte) 2);
    assertNextEquals(chunker, (byte) 3);

    chunker.reset();

    assertNextEquals(chunker, (byte) 1);
  }

  @Test
  public void resourcesShouldBeReleased() throws IOException {
    // Test that after having consumed all data or after reset() is called (whatever happens first)
    // the underlying InputStream should be closed.

    byte[] data = new byte[] {1, 2};
    final AtomicReference<InputStream> in = new AtomicReference<>();
    RemoteCacheClient.Blob supplier =
        () -> {
          in.set(Mockito.spy(new ByteArrayInputStream(data)));
          return in.get();
        };

    Chunker chunker = new Chunker(supplier, data.length, 1, false);
    assertThat(in.get()).isNull();
    assertNextEquals(chunker, (byte) 1);
    Mockito.verify(in.get(), Mockito.never()).close();
    assertNextEquals(chunker, (byte) 2);
    Mockito.verify(in.get()).close();

    chunker.reset();
    chunker.next();
    chunker.reset();
    Mockito.verify(in.get()).close();
  }

  @Test
  public void seekAfterReset() throws IOException {
    // Test that seek() works on an uninitialized chunker

    byte[] data = new byte[10];
    Chunker chunker = Chunker.builder().setInput(data).setChunkSize(10).build();

    chunker.reset();
    chunker.seek(2);

    Chunk next = chunker.next();
    assertThat(next).isNotNull();
    assertThat(next.getOffset()).isEqualTo(2);
    assertThat(next.getData()).hasSize(8);
  }

  @Test
  public void seekBackwards() throws IOException {
    byte[] data = new byte[10];
    Chunker chunker = Chunker.builder().setInput(data).setChunkSize(10).build();

    chunker.seek(4);
    chunker.seek(2);

    Chunk next = chunker.next();
    assertThat(next).isNotNull();
    assertThat(next.getOffset()).isEqualTo(2);
    assertThat(next.getData()).hasSize(8);
  }

  @Test
  public void seekForwards() throws IOException {
    byte[] data = new byte[10];
    for (byte i = 0; i < data.length; i++) {
      data[i] = i;
    }
    Chunker chunker = Chunker.builder().setInput(data).setChunkSize(2).build();

    var chunk = chunker.next();
    assertThat(chunk.getOffset()).isEqualTo(0);
    assertThat(chunk.getData().toByteArray()).isEqualTo(new byte[] {0, 1});
    chunker.seek(8);
    chunk = chunker.next();
    assertThat(chunk.getOffset()).isEqualTo(8);
    assertThat(chunk.getData().toByteArray()).isEqualTo(new byte[] {8, 9});
    assertThat(chunker.hasNext()).isFalse();
  }

  @Test
  public void seekEmptyData() throws IOException {
    var chunker = Chunker.builder().setInput(new byte[0]).build();
    for (var i = 0; i < 2; i++) {
      chunker.seek(0);
      var next = chunker.next();
      assertThat(next).isNotNull();
      assertThat(next.getData()).isEmpty();
      assertThat(next.getOffset()).isEqualTo(0);

      assertThat(chunker.hasNext()).isFalse();
      assertThrows(NoSuchElementException.class, chunker::next);
    }
  }

  @Test
  public void testSingleChunkCompressed() throws IOException {
    byte[] data = {72, 101, 108, 108, 111, 32, 87, 111, 114, 108, 100, 33};
    Chunker chunker =
        Chunker.builder().setInput(data).setChunkSize(data.length * 2).setCompressed(true).build();
    Chunk next = chunker.next();
    assertThat(chunker.hasNext()).isFalse();
    assertThat(Zstd.decompress(next.getData().toByteArray(), data.length)).isEqualTo(data);
  }

  @Test
  public void testMultiChunkCompressed() throws IOException {
    byte[] data = {72, 101, 108, 108, 111, 32, 87, 111, 114, 108, 100, 33};
    Chunker chunker =
        Chunker.builder().setInput(data).setChunkSize(data.length / 2).setCompressed(true).build();

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    chunker.next().getData().writeTo(baos);
    assertThat(chunker.hasNext()).isTrue();
    while (chunker.hasNext()) {
      chunker.next().getData().writeTo(baos);
    }
    baos.close();

    assertThat(Zstd.decompress(baos.toByteArray(), data.length)).isEqualTo(data);
  }

  @Test
  public void testActualSizeIsCorrectAfterSeek() throws IOException {
    byte[] data = {72, 101, 108, 108, 111, 32, 87, 111, 114, 108, 100, 33};
    int[] expectedSizes = {12, 24};
    for (int expected : expectedSizes) {
      Chunker chunker =
          Chunker.builder()
              .setInput(data)
              .setChunkSize(data.length * 2)
              .setCompressed(expected != data.length)
              .build();
      chunker.seek(5);
      chunker.next();
      assertThat(chunker.hasNext()).isFalse();
      assertThat(chunker.getOffset()).isEqualTo(expected);
    }
  }

  private void assertNextEquals(Chunker chunker, byte... data) throws IOException {
    assertThat(chunker.hasNext()).isTrue();
    ByteString next = chunker.next().getData();
    assertThat(next.toByteArray()).isEqualTo(data);
  }
}
