// Copyright 2016 The Bazel Authors. All Rights Reserved.
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

package com.google.devtools.build.buildjar.instrumentation;

import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import com.google.devtools.build.buildjar.InvalidCommandLineException;
import com.google.devtools.build.buildjar.JavaLibraryBuildRequest;
import com.google.devtools.build.buildjar.jarhelper.JarCreator;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator;

import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;

/** Instruments compiled java classes using Jacoco instrumentation library. */
public final class JacocoInstrumentationProcessor {

  public static JacocoInstrumentationProcessor create(List<String> args)
      throws InvalidCommandLineException {
    if (args.size() < 1) {
      throw new InvalidCommandLineException(
          "Number of arguments for Jacoco instrumentation should be 1+ (given "
              + args.size()
              + ": metadataOutput [filters*].");
    }

    // ignoring filters, they weren't used in the previous implementation
    // TODO(bazel-team): filters should be correctly handled
    return new JacocoInstrumentationProcessor(args.get(0));
  }

  private Path instrumentedClassesDirectory;
  private final String coverageInformation;

  private JacocoInstrumentationProcessor(String coverageInfo) {
    this.coverageInformation = coverageInfo;
  }

  /**
   * Instruments classes using Jacoco and keeps copies of uninstrumented class files in
   * jacocoMetadataDir, to be zipped up in the output file jacocoMetadataOutput.
   */
  public void processRequest(JavaLibraryBuildRequest build, JarCreator jar) throws IOException {
    // Use a directory for coverage metadata  that is unique to each built jar. Avoids
    // multiple threads performing read/write/delete actions on the instrumented classes directory.
    instrumentedClassesDirectory = getMetadataDirRelativeToJar(build.getOutputJar());
    Files.createDirectories(instrumentedClassesDirectory);
    jar.setNormalize(true);
    jar.setCompression(build.compressJar());
    Instrumenter instr = new Instrumenter(new OfflineInstrumentationAccessGenerator());
    instrumentRecursively(instr, build.getClassDir());
    jar.addDirectory(instrumentedClassesDirectory);
    jar.addEntry(coverageInformation, coverageInformation);
  }

  public void cleanup() throws IOException {
    if (Files.exists(instrumentedClassesDirectory)) {
      MoreFiles.deleteRecursively(
          instrumentedClassesDirectory, RecursiveDeleteOption.ALLOW_INSECURE);
    }
  }

  // Return the path of the coverage metadata directory relative to the output jar path.
  private static Path getMetadataDirRelativeToJar(Path outputJar) {
    return outputJar.resolveSibling(outputJar + "-coverage-metadata");
  }

  /**
   * Runs Jacoco instrumentation processor over all .class files recursively, starting with root.
   */
  private void instrumentRecursively(Instrumenter instr, Path root) throws IOException {
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (!file.getFileName().toString().endsWith(".class")) {
              return FileVisitResult.CONTINUE;
            }
            // TODO(bazel-team): filter with coverage_instrumentation_filter?
            // It's not clear whether there is any advantage in not instrumenting *Test classes,
            // apart from lowering the covered percentage in the aggregate statistics.

            // We first copy the original .class file to our metadata directory, then instrument it
            // and rewrite the instrumented version back into the regular classes output directory.

            // Not moving or unlinking the source .class file is essential to guarantee visiting
            // it only once during recursive directory traversal while also mutating the directory.
            Path instrumentedCopy = file;
            Path absoluteUninstrumentedCopy = Path.of(file + ".uninstrumented");
            Path uninstrumentedCopy =
                instrumentedClassesDirectory.resolve(root.relativize(absoluteUninstrumentedCopy));
            Files.createDirectories(uninstrumentedCopy.getParent());
            Files.copy(file, uninstrumentedCopy);
            try (InputStream input =
                    new BufferedInputStream(Files.newInputStream(uninstrumentedCopy));
                OutputStream output =
                    new BufferedOutputStream(Files.newOutputStream(instrumentedCopy, TRUNCATE_EXISTING))) {
              instr.instrument(input, output, file.toString());
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
