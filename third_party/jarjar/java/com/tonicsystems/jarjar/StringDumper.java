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

import com.tonicsystems.jarjar.util.ClassPathEntry;
import com.tonicsystems.jarjar.util.ClassPathIterator;
import com.tonicsystems.jarjar.util.RuntimeIOException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import org.objectweb.asm.ClassReader;

class StringDumper {
  public StringDumper() {}

  public void run(String classPath, PrintWriter pw) throws IOException {
    StringReader stringReader = new DumpStringReader(pw);
    ClassPathIterator cp = new ClassPathIterator(classPath);
    try {
      while (cp.hasNext()) {
        ClassPathEntry entry = cp.next();
        InputStream in = entry.openStream();
        try {
          new ClassReader(in).accept(stringReader, 0);
        } catch (Exception e) {
          System.err.println("Error reading " + entry.getName() + ": " + e.getMessage());
        } finally {
          in.close();
        }
        pw.flush();
      }
    } catch (RuntimeIOException e) {
      throw (IOException) e.getCause();
    } finally {
      cp.close();
    }
  }

  private static class DumpStringReader extends StringReader {
    private final PrintWriter pw;
    private String className;

    public DumpStringReader(PrintWriter pw) {
      this.pw = pw;
    }

    @Override
    public void visitString(String className, String value, int line) {
      if (value.length() > 0) {
        if (!className.equals(this.className)) {
          this.className = className;
          pw.println(className.replace('/', '.'));
        }
        pw.print("\t");
        if (line >= 0) {
          pw.print(line + ": ");
        }
        pw.print(escapeStringLiteral(value));
        pw.println();
      }
    }
  }

  private static String escapeStringLiteral(String value) {
    StringBuilder sb = new StringBuilder();
    sb.append("\"");
    char[] chars = value.toCharArray();
    for (int i = 0, size = chars.length; i < size; i++) {
      char ch = chars[i];
      switch (ch) {
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\b':
          sb.append("\\b");
          break;
        case '\f':
          sb.append("\\f");
          break;
        case '\t':
          sb.append("\\t");
          break;
        case '\"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        default:
          sb.append(ch);
      }
    }
    sb.append("\"");
    return sb.toString();
  }
}
