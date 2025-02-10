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
package com.google.devtools.build.lib.skyframe.serialization.analysis;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.devtools.build.lib.skyframe.serialization.proto.FileInvalidationData;
import com.google.devtools.build.lib.versioning.LongVersionGetter;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Base64;

/** Constants and methods supporting {@link FileInvalidationData} keys. */
final class FileDependencyKeySupport {
  static final int MAX_KEY_LENGTH = 250;

  private static final byte[] EMPTY_BYTES = new byte[0];

  /**
   * Neither {@link #FILE_KEY_DELIMITER} nor {@link #DIRECTORY_KEY_DELIMITER} are used in Base64,
   * making them good delimiters for the Base64-encoded version numbers.
   *
   * <p>See comment at {@link FileInvalidationData} for more details.
   */
  static final byte FILE_KEY_DELIMITER = (byte) ':';

  static final byte DIRECTORY_KEY_DELIMITER = (byte) ';';

  private static final Base64.Encoder ENCODER = Base64.getEncoder().withoutPadding();

  static byte[] encodeMtsv(long mtsv) {
    if (mtsv < 0) {
      checkArgument(mtsv == LongVersionGetter.MINIMAL, mtsv);
      return EMPTY_BYTES; // BigInteger.toByteArray is never empty so this is unique.
    }
    // Uses a BigInteger to trim leading 0 bytes.
    return ENCODER.encode(BigInteger.valueOf(mtsv).toByteArray());
  }

  static String computeCacheKey(PathFragment path, long mtsv, byte delimiter) {
    return computeCacheKey(path.getPathString(), mtsv, delimiter);
  }

  static String computeCacheKey(String path, long mtsv, byte delimiter) {
    byte[] encodedMtsv = encodeMtsv(mtsv);
    byte[] pathBytes = path.getBytes(UTF_8);

    byte[] keyBytes = Arrays.copyOf(encodedMtsv, encodedMtsv.length + 1 + pathBytes.length);
    keyBytes[encodedMtsv.length] = delimiter;
    System.arraycopy(pathBytes, 0, keyBytes, encodedMtsv.length + 1, pathBytes.length);

    return new String(keyBytes, UTF_8);
  }

  private FileDependencyKeySupport() {}
}
