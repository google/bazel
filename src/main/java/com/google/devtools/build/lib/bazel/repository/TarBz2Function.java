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

import com.google.devtools.build.lib.bazel.repository.DecompressorValue.Decompressor;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

/**
 * Creates a repository by unarchiving a .tar.bz2 file.
 */
public class TarBz2Function extends CompressedTarFunction {
  public static final Decompressor INSTANCE = new TarBz2Function();

  private TarBz2Function() {}

  @Override
  protected InputStream getDecompressorStream(BufferedInputStream compressedInputStream)
      throws IOException {
    return new BZip2CompressorInputStream(compressedInputStream, true);
  }
}
