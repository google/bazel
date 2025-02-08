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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ClassPathIterator implements Iterator<ClassPathEntry> {
  private static final FileFilter CLASS_FILTER =
      new FileFilter() {
        @Override
        public boolean accept(File file) {
          return file.isDirectory() || isClass(file.getName());
        }
      };

  private static final FileFilter JAR_FILTER =
      new FileFilter() {
        @Override
        public boolean accept(File file) {
          return hasExtension(file.getName(), ".jar");
        }
      };

  private final Iterator<File> files;
  private Iterator<ClassPathEntry> entries = Collections.emptyIterator();
  private ClassPathEntry next;
  private final List<ZipFile> zips = new ArrayList<>();

  public ClassPathIterator(String classPath) throws IOException {
    this(new File(System.getProperty("user.dir")), classPath, null);
  }

  public ClassPathIterator(File parent, String classPath, String delim) throws IOException {
    if (delim == null) {
      delim = System.getProperty("path.separator");
    }
    StringTokenizer st = new StringTokenizer(classPath, delim);
    List<File> fileList = new ArrayList<>();
    while (st.hasMoreTokens()) {
      String part = (String) st.nextElement();
      boolean wildcard = false;
      if (part.endsWith("/*")) {
        part = part.substring(0, part.length() - 1);
        if (part.indexOf('*') >= 0) {
          throw new IllegalArgumentException("Multiple wildcards are not allowed: " + part);
        }
        wildcard = true;
      } else if (part.indexOf('*') >= 0) {
        throw new IllegalArgumentException("Incorrect wildcard usage: " + part);
      }

      File file = new File(part);
      if (!file.isAbsolute()) {
        file = new File(parent, part);
      }
      if (!file.exists()) {
        throw new IllegalArgumentException("File " + file + " does not exist");
      }

      if (wildcard) {
        if (!file.isDirectory()) {
          throw new IllegalArgumentException("File " + file + " + is not a directory");
        }
        fileList.addAll(findFiles(file, JAR_FILTER, false, new ArrayList<File>()));
      } else {
        fileList.add(file);
      }
    }
    this.files = fileList.iterator();
    advance();
  }

  @Override
  public boolean hasNext() {
    return next != null;
  }

  /** Closes all zip files opened by this iterator. */
  public void close() throws IOException {
    next = null;
    for (ZipFile zip : zips) {
      zip.close();
    }
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ClassPathEntry next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    ClassPathEntry result = next;
    try {
      advance();
    } catch (IOException e) {
      throw new RuntimeIOException(e);
    }
    return result;
  }

  private void advance() throws IOException {
    if (!entries.hasNext()) {
      if (!files.hasNext()) {
        next = null;
        return;
      }
      File file = files.next();
      if (hasExtension(file.getName(), ".jar")) {
        ZipFile zip = new JarFile(file);
        zips.add(zip);
        entries = new ZipIterator(zip);
      } else if (hasExtension(file.getName(), ".zip")) {
        ZipFile zip = new ZipFile(file);
        zips.add(zip);
        entries = new ZipIterator(zip);
      } else if (file.isDirectory()) {
        entries = new FileIterator(file);
      } else {
        throw new IllegalArgumentException("Do not know how to handle " + file);
      }
    }

    boolean foundClass = false;
    while (!foundClass && entries.hasNext()) {
      next = entries.next();
      foundClass = isClass(next.getName());
    }
    if (!foundClass) {
      advance();
    }
  }

  private static class ZipIterator implements Iterator<ClassPathEntry> {
    private final ZipFile zip;
    private final Enumeration<? extends ZipEntry> entries;

    ZipIterator(ZipFile zip) {
      this.zip = zip;
      this.entries = zip.entries();
    }

    @Override
    public boolean hasNext() {
      return entries.hasMoreElements();
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ClassPathEntry next() {
      final ZipEntry entry = entries.nextElement();
      return new ClassPathEntry() {
        @Override
        public String getSource() {
          return zip.getName();
        }

        @Override
        public String getName() {
          return entry.getName();
        }

        @Override
        public InputStream openStream() throws IOException {
          return zip.getInputStream(entry);
        }
      };
    }
  }

  private static class FileIterator implements Iterator<ClassPathEntry> {
    private final File dir;
    private final Iterator<File> entries;

    FileIterator(File dir) {
      this.dir = dir;
      this.entries = findFiles(dir, CLASS_FILTER, true, new ArrayList<File>()).iterator();
    }

    @Override
    public boolean hasNext() {
      return entries.hasNext();
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ClassPathEntry next() {
      final File file = entries.next();
      return new ClassPathEntry() {
        @Override
        public String getSource() throws IOException {
          return dir.getCanonicalPath();
        }

        @Override
        public String getName() {
          return file.getName();
        }

        @Override
        public InputStream openStream() throws IOException {
          return new BufferedInputStream(new FileInputStream(file));
        }
      };
    }
  }

  private static List<File> findFiles(
      File dir, FileFilter filter, boolean recurse, List<File> collect) {
    for (File file : dir.listFiles(filter)) {
      if (recurse && file.isDirectory()) {
        findFiles(file, filter, recurse, collect);
      } else {
        collect.add(file);
      }
    }
    return collect;
  }

  private static boolean isClass(String name) {
    return hasExtension(name, ".class");
  }

  private static boolean hasExtension(String name, String ext) {
    if (name.length() < ext.length()) {
      return false;
    }
    String actual = name.substring(name.length() - ext.length());
    return actual.equals(ext) || actual.equals(ext.toUpperCase());
  }
}
