// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.buildjar.jarhelper;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * A class for creating Jar files. Allows normalization of Jar entries by setting their timestamp to
 * the DOS epoch. All Jar entries are sorted alphabetically.
 */
public class JarCreator extends JarHelper {

  // Map from Jar entry names to files. Use TreeMap so we can establish a canonical order for the
  // entries regardless in what order they get added.
  private final TreeMap<String, Path> jarEntries = new TreeMap<>();
  private String manifestFile;
  private String mainClass;
  private String targetLabel;
  private String injectingRuleKind;

  /** @deprecated use {@link JarCreator(Path)} instead */
  @Deprecated
  public JarCreator(String fileName) {
    this(Paths.get(fileName));
  }

  public JarCreator(Path path) {
    super(path);
  }

  /**
   * Adds an entry to the Jar file, normalizing the name.
   *
   * @param entryName the name of the entry in the Jar file
   * @param path the path of the input for the entry
   * @return true iff a new entry was added
   */
  public boolean addEntry(String entryName, Path path) {
    if (entryName.startsWith("/")) {
      entryName = entryName.substring(1);
    } else if (entryName.length() >= 3
        && Character.isLetter(entryName.charAt(0))
        && entryName.charAt(1) == ':'
        && (entryName.charAt(2) == '\\' || entryName.charAt(2) == '/')) {
      // Windows absolute path, e.g. "D:\foo" or "e:/blah".
      // Windows paths are case-insensitive, and support both backslashes and forward slashes.
      entryName = entryName.substring(3);
    } else if (entryName.startsWith("./")) {
      entryName = entryName.substring(2);
    }
    return jarEntries.put(entryName, path) == null;
  }

  /**
   * Adds an entry to the Jar file, normalizing the name.
   *
   * @param entryName the name of the entry in the Jar file
   * @param fileName the name of the input file for the entry
   * @return true iff a new entry was added
   */
  public boolean addEntry(String entryName, String fileName) {
    return addEntry(entryName, Paths.get(fileName));
  }

  /** @deprecated prefer {@link #addDirectory(Path)} */
  @Deprecated
  public void addDirectory(String directory) {
    addDirectory(Paths.get(directory));
  }

  /**
   * Adds the contents of a directory to the Jar file. All files below this directory will be added
   * to the Jar file using the name relative to the directory as the name for the Jar entry.
   *
   * @param directory the directory to add to the jar
   */
  public void addDirectory(Path directory) {
    if (!Files.exists(directory)) {
      throw new IllegalArgumentException("directory does not exist: " + directory);
    }
    try {
      Files.walkFileTree(
          directory,
          new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs)
                throws IOException {
              if (!path.equals(directory)) {
                // For consistency with legacy behaviour, include entries for directories except for
                // the root.
                addEntry(path, /* isDirectory= */ true);
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
                throws IOException {
              addEntry(path, /* isDirectory= */ false);
              return FileVisitResult.CONTINUE;
            }

            void addEntry(Path path, boolean isDirectory) {
              StringBuilder sb = new StringBuilder();
              boolean first = true;
              for (Path entry : directory.relativize(path)) {
                if (!first) {
                  // use `/` as the directory separator for jar paths, even on Windows
                  sb.append('/');
                }
                sb.append(entry.getFileName());
                first = false;
              }
              if (isDirectory) {
                sb.append('/');
              }
              jarEntries.put(sb.toString(), path);
            }
          });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Sets the main.class entry for the manifest. A value of <code>null</code> (the default) will
   * omit the entry.
   *
   * @param mainClass the fully qualified name of the main class
   */
  public void setMainClass(String mainClass) {
    this.mainClass = mainClass;
  }

  public void setJarOwner(String targetLabel, String injectingRuleKind) {
    this.targetLabel = targetLabel;
    this.injectingRuleKind = injectingRuleKind;
  }

  /**
   * Sets filename for the manifest content. If this is set the manifest will be read from this file
   * otherwise the manifest content will get generated on the fly.
   *
   * @param manifestFile the filename of the manifest file.
   */
  public void setManifestFile(String manifestFile) {
    this.manifestFile = manifestFile;
  }

  private byte[] manifestContent() throws IOException {
    if (manifestFile != null) {
      try (FileInputStream in = new FileInputStream(manifestFile)) {
        return manifestContentImpl(new Manifest(in));
      }
    } else {
      return manifestContentImpl(new Manifest());
    }
  }

  private byte[] manifestContentImpl(Manifest manifest) throws IOException {
    Attributes attributes = manifest.getMainAttributes();
    attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    Attributes.Name createdBy = new Attributes.Name("Created-By");
    if (attributes.getValue(createdBy) == null) {
      attributes.put(createdBy, "bazel");
    }
    if (mainClass != null) {
      attributes.put(Attributes.Name.MAIN_CLASS, mainClass);
    }
    if (targetLabel != null) {
      attributes.put(JarHelper.TARGET_LABEL, targetLabel);
    }
    if (injectingRuleKind != null) {
      attributes.put(JarHelper.INJECTING_RULE_KIND, injectingRuleKind);
    }
    if (multiRelease) {
      attributes.put(JarHelper.MULTI_RELEASE, "true");
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    manifest.write(out);
    return out.toByteArray();
  }

  /**
   * Executes the creation of the Jar file.
   *
   * @throws IOException if the Jar cannot be written or any of the entries cannot be read.
   */
  public void execute() throws IOException {
    try (OutputStream os = Files.newOutputStream(jarPath);
        BufferedOutputStream bos = new BufferedOutputStream(os);
        JarOutputStream out = new JarOutputStream(bos)) {

      // Create the manifest entry in the Jar file
      writeManifestEntry(out, manifestContent());

      for (Map.Entry<String, Path> entry : jarEntries.entrySet()) {
        copyEntry(out, entry.getKey(), entry.getValue());
      }
    }
  }

  /** A simple way to create Jar file using the JarCreator class. */
  public static void main(String[] args) {
    if (args.length < 1) {
      System.err.println("usage: CreateJar output [root directories]");
      System.exit(1);
    }
    String output = args[0];
    JarCreator createJar = new JarCreator(output);
    for (int i = 1; i < args.length; i++) {
      createJar.addDirectory(args[i]);
    }
    createJar.setCompression(true);
    createJar.setNormalize(true);
    createJar.setVerbose(true);
    long start = System.currentTimeMillis();
    try {
      createJar.execute();
    } catch (IOException e) {
      e.printStackTrace();
      System.exit(1);
    }
    long stop = System.currentTimeMillis();
    System.err.println((stop - start) + "ms.");
  }
}
