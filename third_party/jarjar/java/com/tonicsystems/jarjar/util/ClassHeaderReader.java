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

package com.tonicsystems.jarjar.util;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;

public class ClassHeaderReader {
  private int access;
  private String thisClass;
  private String superClass;
  private String[] interfaces;

  private InputStream in;
  private byte[] b = new byte[0x2000];
  private int[] items = new int[1000];
  private int bsize = 0;
  private final MyByteArrayInputStream bin = new MyByteArrayInputStream();
  private final DataInputStream data = new DataInputStream(bin);

  public int getAccess() {
    return access;
  }

  public String getClassName() {
    return thisClass;
  }

  public String getSuperName() {
    return superClass;
  }

  public String[] getInterfaces() {
    return interfaces;
  }

  public void read(InputStream in) throws IOException {
    try {
      this.in = in;
      bsize = 0;
      access = 0;
      thisClass = superClass = null;
      interfaces = null;

      try {
        buffer(4);
      } catch (IOException e) {
        // ignore
      }
      if (b[0] != (byte) 0xCA
          || b[1] != (byte) 0xFE
          || b[2] != (byte) 0xBA
          || b[3] != (byte) 0xBE) {
        throw new ClassFormatError("Bad magic number");
      }

      buffer(6);
      readUnsignedShort(4); // minorVersion
      readUnsignedShort(6); // majorVersion
      // TODO: check version
      int constantPoolCount = readUnsignedShort(8);
      items = (int[]) resizeArray(items, constantPoolCount);

      int index = 10;
      for (int i = 1; i < constantPoolCount; i++) {
        int size;
        buffer(index + 3); // TODO: reduce calls to buffer
        int tag = b[index];
        items[i] = index + 1;
        switch (tag) {
          case 9: // Fieldref
          case 10: // Methodref
          case 11: // InterfaceMethodref
          case 3: // Integer
          case 4: // Float
          case 12: // NameAndType
            size = 4;
            break;
          case 5: // Long
          case 6: // Double
            size = 8;
            i++;
            break;
          case 1: // Utf8
            size = 2 + readUnsignedShort(index + 1);
            break;
          case 7: // Class
          case 8: // String
            size = 2;
            break;
          default:
            throw new IllegalStateException("Unknown constant pool tag " + tag);
        }
        index += size + 1;
      }
      buffer(index + 8);
      access = readUnsignedShort(index);
      thisClass = readClass(index + 2);
      superClass = readClass(index + 4);
      int interfacesCount = readUnsignedShort(index + 6);

      index += 8;
      buffer(index + interfacesCount * 2);
      interfaces = new String[interfacesCount];
      for (int i = 0; i < interfacesCount; i++) {
        interfaces[i] = readClass(index);
        index += 2;
      }
    } finally {
      in.close();
    }
  }

  private static int read(InputStream in, byte[] b, int off, int len) throws IOException {
    int total = 0;
    while (total < len) {
      int result = in.read(b, off + total, len - total);
      if (result == -1) {
        break;
      }
      total += result;
    }
    return total;
  }

  private String readClass(int index) throws IOException {
    index = readUnsignedShort(index);
    if (index == 0) {
      return null;
    }
    index = readUnsignedShort(items[index]);
    bin.readFrom(b, items[index]);
    return data.readUTF();
  }

  private int readUnsignedShort(int index) {
    byte[] b = this.b;
    return ((b[index] & 0xFF) << 8) | (b[index + 1] & 0xFF);
  }

  private static final int CHUNK = 2048;

  private void buffer(int amount) throws IOException {
    if (amount > b.length) {
      b = (byte[]) resizeArray(b, b.length * 2);
    }
    if (amount > bsize) {
      int rounded = (int) (CHUNK * Math.ceil((float) amount / CHUNK));
      bsize += read(in, b, bsize, rounded - bsize);
      if (amount > bsize) {
        throw new EOFException();
      }
    }
  }

  private static Object resizeArray(Object array, int length) {
    if (Array.getLength(array) < length) {
      Object newArray = Array.newInstance(array.getClass().getComponentType(), length);
      System.arraycopy(array, 0, newArray, 0, Array.getLength(array));
      return newArray;
    } else {
      return array;
    }
  }

  private static class MyByteArrayInputStream extends ByteArrayInputStream {
    public MyByteArrayInputStream() {
      super(new byte[0]);
    }

    public void readFrom(byte[] buf, int pos) {
      this.buf = buf;
      this.pos = pos;
      count = buf.length;
    }
  }
}
