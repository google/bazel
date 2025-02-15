// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.github.luben.zstd.ZstdInputStreamNoFinalizer;
import com.google.devtools.build.lib.bazel.repository.DecompressorValue.Decompressor;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Creates a repository by unarchiving a zstandard-compressed .tar file. */
final class TarZstFunction extends CompressedTarFunction {
  static final Decompressor INSTANCE = new TarZstFunction();

  private TarZstFunction() {}

  @Override
  protected InputStream getDecompressorStream(BufferedInputStream compressedInputStream)
      throws IOException {
    return new ZstdInputStreamNoFinalizer(compressedInputStream);
  }
}
