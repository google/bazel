// Copyright 2016 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static java.lang.Math.min;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob;
import com.google.devtools.build.lib.remote.zstd.ZstdCompressingInputStream;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.ByteString;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Splits a data source into one or more {@link Chunk}s of at most {@code chunkSize} bytes.
 *
 * <p>After a data source has been fully consumed, that is until {@link #hasNext()} returns {@code
 * false}, the chunker closes the underlying data source (i.e. file) itself. However, in case of
 * error or when a data source does not get fully consumed, a user must call {@link #reset()}
 * manually.
 *
 * <p>This class should not be extended - it's only non-final for testing.
 */
public class Chunker implements AutoCloseable {

  private static int defaultChunkSize = 1024 * 16;

  /** This method must only be called in tests! */
  @VisibleForTesting
  static void setDefaultChunkSizeForTesting(int value) {
    defaultChunkSize = value;
  }

  static int getDefaultChunkSize() {
    return defaultChunkSize;
  }

  public boolean isCompressed() {
    return compressed;
  }

  /** A piece of a byte[] blob. */
  public static final class Chunk {

    private final long offset;
    private final ByteString data;

    private Chunk(ByteString data, long offset) {
      this.data = data;
      this.offset = offset;
    }

    public long getOffset() {
      return offset;
    }

    public ByteString getData() {
      return data;
    }

    @Override
    public boolean equals(Object o) {
      if (o == this) {
        return true;
      }
      if (!(o instanceof Chunk other)) {
        return false;
      }
      return other.offset == offset && other.data.equals(data);
    }

    @Override
    public int hashCode() {
      return Objects.hash(offset, data);
    }
  }

  private final Blob blob;
  private final long uncompressedSize;
  private final int chunkSize;
  private final Chunk emptyChunk;

  @VisibleForTesting protected ChunkerInputStream data;
  private long offset;
  private byte[] chunkCache;

  private final boolean compressed;

  // Set to true on the first call to next(). This is so that the Chunker can open its data source
  // lazily on the first call to next(), as opposed to opening it in the constructor or on reset().
  private boolean initialized;

  Chunker(Blob blob, long uncompressedSize, int chunkSize, boolean compressed) {
    this.blob = checkNotNull(blob);
    this.uncompressedSize = uncompressedSize;
    this.chunkSize = chunkSize;
    this.emptyChunk = new Chunk(ByteString.EMPTY, 0);
    this.compressed = compressed;
  }

  public long getOffset() {
    return offset;
  }

  public long getUncompressedSize() {
    return uncompressedSize;
  }

  /**
   * Reset the {@link Chunker} state to when it was newly constructed.
   *
   * <p>Closes any open resources (file handles, ...).
   */
  public void reset() throws IOException {
    closeInput();
    offset = 0;
    initialized = false;
  }

  /**
   * Seek to an offset in the source stream.
   *
   * <p>May close and reopen resources in order to seek to an earlier offset.
   *
   * @param toOffset the offset from beginning of the source stream. If the source stream is
   *     compressed, it refers to the offset in the uncompressed form to align with `write_offset`
   *     in REAPI.
   */
  public void seek(long toOffset) throws IOException {
    // For compressed stream, we need to reinitialize the stream since the offset refers to the
    // uncompressed form.
    if (initialized && uncompressedSize > 0 && toOffset >= offset && !compressed) {
      ByteStreams.skipFully(data, toOffset - offset);
      offset = toOffset;
    } else {
      reset();
      initialize(toOffset);
    }
    if (uncompressedSize > 0 && data.finished()) {
      closeInput();
    }
  }

  /**
   * Returns {@code true} if a subsequent call to {@link #next()} returns a {@link Chunk} object;
   */
  public boolean hasNext() {
    return data != null || !initialized;
  }

  /** Closes the input stream and reset chunk cache */
  private void closeInput() throws IOException {
    if (data != null) {
      data.close();
      data = null;
    }
    chunkCache = null;
  }

  @Override
  public void close() throws IOException {
    reset();
    blob.close();
  }

  /** Attempts reading at most a full chunk and stores it in the chunkCache buffer */
  private int read() throws IOException {
    int count = 0;
    while (count < chunkCache.length) {
      int c = data.read(chunkCache, count, chunkCache.length - count);
      if (c < 0) {
        break;
      }
      count += c;
    }
    return count;
  }
  /**
   * Returns the next {@link Chunk} or throws a {@link NoSuchElementException} if no data is left.
   *
   * <p>Always call {@link #hasNext()} before calling this method.
   *
   * <p>Zero byte inputs are treated special. Instead of throwing a {@link NoSuchElementException}
   * on the first call to {@link #next()}, a {@link Chunk} with an empty {@link ByteString} is
   * returned.
   */
  public Chunk next() throws IOException {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }

    maybeInitialize();

    if (uncompressedSize == 0) {
      closeInput();
      return emptyChunk;
    }

    if (data.finished()) {
      chunkCache = null;
      data = null;
      throw new NoSuchElementException();
    }

    if (chunkCache == null) {
      // If the output is compressed we can't know how many bytes there are yet to read,
      // so we allocate the whole chunkSize, otherwise we try to compute the smallest possible value
      // The cast to int is safe, because the return value is capped at chunkSize.
      int cacheSize = compressed ? chunkSize : (int) min(uncompressedSize - getOffset(), chunkSize);
      // Lazily allocate it in order to save memory on small data.
      // 1) bytesToRead < chunkSize: There will only ever be one next() call.
      // 2) bytesToRead == chunkSize: chunkCache will be set to its biggest possible value.
      // 3) bytestoRead > chunkSize: Not possible, due to Math.min above.
      chunkCache = new byte[cacheSize];
    }

    long offsetBefore = offset;

    int bytesRead = read();

    ByteString blob = ByteString.copyFrom(chunkCache, 0, bytesRead);

    // This has to happen after actualSize has been updated
    // or the guard in getActualSize won't work.
    offset += bytesRead;
    if (data.finished()) {
      closeInput();
    }

    return new Chunk(blob, offsetBefore);
  }

  private void maybeInitialize() throws IOException {
    if (initialized) {
      return;
    }
    initialize(0);
  }

  private void initialize(long srcPos) throws IOException {
    checkState(!initialized);
    checkState(data == null);
    checkState(offset == 0);
    checkState(chunkCache == null);
    try {
      var src = blob.get();
      ByteStreams.skipFully(src, srcPos);
      data =
          compressed
              ? new ChunkerInputStream(new ZstdCompressingInputStream(src))
              : new ChunkerInputStream(src);
    } catch (RuntimeException e) {
      if (e.getCause() != null) {
        throwIfInstanceOf(e.getCause(), IOException.class);
        throwIfUnchecked(e.getCause());
      }
      throw e;
    }
    offset = srcPos;
    initialized = true;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Builder class for the Chunker */
  public static class Builder {
    private int chunkSize = getDefaultChunkSize();
    protected long size;
    private boolean compressed;
    protected Blob inputStream;

    @CanIgnoreReturnValue
    public Builder setInput(long size, Blob in) {
      checkState(inputStream == null);
      checkNotNull(in);
      this.size = size;
      inputStream = in;
      return this;
    }

    @CanIgnoreReturnValue
    @VisibleForTesting
    public Builder setInput(byte[] data) {
      checkState(inputStream == null);
      size = data.length;
      this.inputStream = () -> new ByteArrayInputStream(data);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setCompressed(boolean compressed) {
      this.compressed = compressed;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setChunkSize(int chunkSize) {
      this.chunkSize = chunkSize;
      return this;
    }

    public Chunker build() {
      checkNotNull(inputStream);
      return new Chunker(inputStream, size, chunkSize, compressed);
    }
  }

  static class ChunkerInputStream extends PushbackInputStream {
    ChunkerInputStream(InputStream in) {
      super(in);
    }

    public boolean finished() throws IOException {
      int c = super.read();
      if (c == -1) {
        return true;
      }
      super.unread(c);
      return false;
    }
  }
}
