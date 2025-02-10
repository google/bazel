/*
 * Copyright 2007 Google Inc.
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

package com.tonicsystems.jarjar;

import com.tonicsystems.jarjar.util.ClassHeaderReader;
import com.tonicsystems.jarjar.util.ClassPathEntry;
import com.tonicsystems.jarjar.util.ClassPathIterator;
import com.tonicsystems.jarjar.util.RuntimeIOException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;

public class DepFind {
  private File curDir = new File(System.getProperty("user.dir"));

  public void setCurrentDirectory(File curDir) {
    this.curDir = curDir;
  }

  public void run(String from, String to, DepHandler handler) throws IOException {
    try {
      ClassHeaderReader header = new ClassHeaderReader();
      Map<String, String> classes = new HashMap<>();
      ClassPathIterator cp = new ClassPathIterator(curDir, to, null);
      try {
        while (cp.hasNext()) {
          ClassPathEntry entry = cp.next();
          InputStream in = entry.openStream();
          try {
            header.read(in);
            classes.put(header.getClassName(), entry.getSource());
          } catch (Exception e) {
            System.err.println("Error reading " + entry.getName() + ": " + e.getMessage());
          } finally {
            in.close();
          }
        }
      } finally {
        cp.close();
      }

      handler.handleStart();
      cp = new ClassPathIterator(curDir, from, null);
      try {
        while (cp.hasNext()) {
          ClassPathEntry entry = cp.next();
          InputStream in = entry.openStream();
          try {
            new ClassReader(in)
                .accept(
                    new DepFindVisitor(classes, entry.getSource(), handler),
                    ClassReader.SKIP_DEBUG);
          } catch (Exception e) {
            System.err.println("Error reading " + entry.getName() + ": " + e.getMessage());
          } finally {
            in.close();
          }
        }
      } finally {
        cp.close();
      }
      handler.handleEnd();
    } catch (RuntimeIOException e) {
      throw (IOException) e.getCause();
    }
  }
}
