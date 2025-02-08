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

package com.google.devtools.build.lib.bazel.repository;

import static com.google.devtools.build.lib.bazel.repository.StripPrefixedPath.maybeDeprefixSymlink;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.auto.service.AutoService;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.bazel.repository.DecompressorValue.Decompressor;
import com.google.devtools.build.lib.util.StringEncoding;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.spi.CharsetProvider;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Common code for unarchiving a compressed TAR file.
 *
 * <p>TAR file entries commonly use one of two formats: PAX, which uses UTF-8 encoding for all
 * strings, and USTAR, which does not specify an encoding. This class interprets USTAR headers as
 * latin-1, thus preserving the original bytes of the header without enforcing any particular
 * encoding. Internally, for file system operations, all strings are converted into Bazel's internal
 * representation of raw bytes stored as latin-1 strings.
 */
public abstract class CompressedTarFunction implements Decompressor {
  protected abstract InputStream getDecompressorStream(DecompressorDescriptor descriptor)
      throws IOException;

  @Override
  public Path decompress(DecompressorDescriptor descriptor)
      throws InterruptedException, IOException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
    Optional<String> prefix = descriptor.prefix();
    Map<String, String> renameFiles = descriptor.renameFiles();
    boolean foundPrefix = false;
    Set<String> availablePrefixes = new HashSet<>();
    // Store link, target info of symlinks, we create them after regular files are extracted.
    Map<Path, PathFragment> symlinks = new HashMap<>();

    try (InputStream decompressorStream = getDecompressorStream(descriptor)) {
      // USTAR tar headers use an unspecified encoding whereas PAX tar headers always use UTF-8.
      // We can specify the encoding to use for USTAR headers, but the Charset used for PAX headers
      // is fixed to UTF-8. We thus specify a custom Charset for the former so that we can
      // distinguish between the two.
      TarArchiveInputStream tarStream =
          new TarArchiveInputStream(decompressorStream, MarkedIso88591Charset.NAME);
      TarArchiveEntry entry;
      while ((entry = tarStream.getNextTarEntry()) != null) {
        String entryName = toRawBytesString(entry.getName());
        entryName = renameFiles.getOrDefault(entryName, entryName);
        StripPrefixedPath entryPath =
            StripPrefixedPath.maybeDeprefix(entryName.getBytes(ISO_8859_1), prefix);
        foundPrefix = foundPrefix || entryPath.foundPrefix();

        if (prefix.isPresent() && !foundPrefix) {
          CouldNotFindPrefixException.maybeMakePrefixSuggestion(entryPath.getPathFragment())
              .ifPresent(availablePrefixes::add);
        }

        if (entryPath.skip()) {
          continue;
        }

        Path filePath = descriptor.destinationPath().getRelative(entryPath.getPathFragment());
        filePath.getParentDirectory().createDirectoryAndParents();
        if (entry.isDirectory()) {
          filePath.createDirectoryAndParents();
        } else {
          if (entry.isSymbolicLink() || entry.isLink()) {
            PathFragment targetName =
                maybeDeprefixSymlink(
                    toRawBytesString(entry.getLinkName()).getBytes(ISO_8859_1),
                    prefix,
                    descriptor.destinationPath());
            if (entry.isSymbolicLink()) {
              symlinks.put(filePath, targetName);
            } else {
              Path targetPath = descriptor.destinationPath().getRelative(targetName);
              if (filePath.equals(targetPath)) {
                // The behavior here is semantically different, depending on whether the underlying
                // filesystem is case-sensitive or case-insensitive. However, it is effectively the
                // same: we drop the link entry.
                // * On a case-sensitive filesystem, this is a hardlink to itself, such as GNU tar
                //   creates when given repeated files. We do nothing since the link already exists.
                // * On a case-insensitive filesystem, we may be extracting a differently-cased
                //   hardlink to the same file (such as when extracting an archive created on a
                //   case-sensitive filesystem). GNU tar, for example, will drop the new link entry.
                //   BSD tar on MacOS X (by default case-insensitive) errors and aborts extraction.
              } else {
                if (filePath.exists()) {
                  filePath.delete();
                }
                FileSystemUtils.createHardLink(filePath, targetPath);
              }
            }
          } else {
            try (OutputStream out = filePath.getOutputStream()) {
              ByteStreams.copy(tarStream, out);
            }
            filePath.chmod(entry.getMode());

            // This can only be done on real files, not links, or it will skip the reader to
            // the next "real" file to try to find the mod time info.
            Date lastModified = entry.getLastModifiedDate();
            filePath.setLastModifiedTime(lastModified.getTime());
          }
        }
        if (Thread.interrupted()) {
          throw new InterruptedException();
        }
      }

      for (Map.Entry<Path, PathFragment> symlink : symlinks.entrySet()) {
        Path linkPath = symlink.getKey();
        if (linkPath.exists()) {
          linkPath.delete();
        }
        FileSystemUtils.ensureSymbolicLink(linkPath, symlink.getValue());
      }

      if (prefix.isPresent() && !foundPrefix) {
        throw new CouldNotFindPrefixException(prefix.get(), availablePrefixes);
      }
    }

    return descriptor.destinationPath();
  }

  /**
   * Returns a string that contains the raw bytes of the given string encoded in ISO-8859-1,
   * assuming that the given string was encoded with either UTF-8 or the special {@link
   * MarkedIso88591Charset}.
   */
  private static String toRawBytesString(String name) {
    // Marked strings are already encoded in ISO-8859-1. Other strings originate from PAX headers
    // and are thus Unicode.
    return MarkedIso88591Charset.getRawBytesStringIfMarked(name)
        .orElseGet(() -> StringEncoding.unicodeToInternal(name));
  }

  /** A provider of {@link MarkedIso88591Charset}s. */
  @AutoService(CharsetProvider.class)
  public static class MarkedIso88591CharsetProvider extends CharsetProvider {
    private static final Charset CHARSET = new MarkedIso88591Charset();

    @Override
    public Iterator<Charset> charsets() {
      // This charset is only meant for internal use within CompressedTarFunction and thus should
      // not be discoverable.
      return Collections.emptyIterator();
    }

    @Override
    public Charset charsetForName(String charsetName) {
      return MarkedIso88591Charset.NAME.equals(charsetName) ? CHARSET : null;
    }
  }

  /**
   * A charset that decodes ISO-8859-1, i.e., produces a String that contains the raw decoded bytes,
   * and appends a marker to the end of the string to indicate that it was decoded with this
   * charset.
   */
  private static class MarkedIso88591Charset extends Charset {
    // The name
    // * must not collide with the name of any other charset.
    // * must not appear in archive entry names by chance.
    // * is internal to CompressedTarFunction.
    // This is best served by a cryptographically random UUID, generated at startup.
    private static final String NAME = UUID.randomUUID().toString();

    private MarkedIso88591Charset() {
      super(NAME, new String[0]);
    }

    public static Optional<String> getRawBytesStringIfMarked(String s) {
      // Check for the marker in all positions as TarArchiveInputStream manipulates the raw name in
      // certain cases (for example, appending a '/' to directory names).
      if (s.contains(NAME)) {
        return Optional.of(s.replaceAll(NAME, ""));
      }
      return Optional.empty();
    }

    @Override
    public CharsetDecoder newDecoder() {
      return new CharsetDecoder(this, 1, 1) {
        @Override
        protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) {
          // A simple unoptimized ISO-8859-1 decoder.
          while (in.hasRemaining()) {
            if (!out.hasRemaining()) {
              return CoderResult.OVERFLOW;
            }
            out.put((char) (in.get() & 0xFF));
          }
          return CoderResult.UNDERFLOW;
        }

        @Override
        protected CoderResult implFlush(CharBuffer out) {
          // Append the marker to the end of the buffer to indicate that it was decoded with this
          // charset.
          if (out.remaining() < NAME.length()) {
            return CoderResult.OVERFLOW;
          }
          out.put(NAME);
          return CoderResult.UNDERFLOW;
        }
      };
    }

    @Override
    public CharsetEncoder newEncoder() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean contains(Charset cs) {
      return false;
    }
  }
}
