/*
 * Copyright 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tonicsystems.jarjar.util;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.zip.ZipOutputStream;

/** Utils for IO. */
public final class IoUtil {

  /**
   * Create a ZipOutputStream with buffering.
   *
   * <p>Buffering is critical to performance because the ZIP format has a lot of small header
   * segments, which each trigger an OS write call otherwise.
   */
  public static ZipOutputStream bufferedZipOutput(File file) throws IOException {
    return new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(file)));
  }

  public static PrintWriter bufferedPrintWriter(OutputStream stream, Charset charset) {
    return new PrintWriter(new OutputStreamWriter(new BufferedOutputStream(stream), charset));
  }

  private IoUtil() {}
}
