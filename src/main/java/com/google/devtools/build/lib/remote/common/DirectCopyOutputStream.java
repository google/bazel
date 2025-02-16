// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.common;

import com.google.devtools.build.lib.vfs.Path;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;

/**
 * use direct copy or to optimize io performance. this class only use to pass output file path.
 */
public class DirectCopyOutputStream extends OutputStream {

  public final Path path;
  private OutputStream out;
  private boolean directCopyed;
  
  public DirectCopyOutputStream(OutputStream out, Path path) {
    this.path = path;
    this.out = out;
  }

  public void setDirectCopyed(boolean directCopyed){
    this.directCopyed = directCopyed;
  }

  public boolean getDirectCopyed(){
    return this.directCopyed;
  }

  @Override
  public void write(byte[] b) throws IOException {
    if (directCopyed == false) {
      out.write(b);
    }
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    if (directCopyed == false) {
      out.write(b, off, len);
    }
  }

  @Override
  public void write(int b) throws IOException {
    if (directCopyed == false) {
      out.write(b);
    }
  }

  @Override
  public void flush() throws IOException {
    if (directCopyed == false) {
      out.flush();
    }
  }

  @Override
  public void close() throws IOException {
    if (directCopyed == false) {
      out.close();
    }
  }
}
