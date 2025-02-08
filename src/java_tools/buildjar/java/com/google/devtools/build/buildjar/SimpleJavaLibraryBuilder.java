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

package com.google.devtools.build.buildjar;

import com.google.common.base.CharMatcher;
import com.google.common.io.ByteStreams;
import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import com.google.devtools.build.buildjar.instrumentation.JacocoInstrumentationProcessor;
import com.google.devtools.build.buildjar.jarhelper.JarCreator;
import com.google.devtools.build.buildjar.javac.BlazeJavacMain;
import com.google.devtools.build.buildjar.javac.BlazeJavacResult;
import com.google.devtools.build.buildjar.javac.BlazeJavacResult.Status;
import com.google.devtools.build.buildjar.javac.JavacRunner;
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics;
import com.google.errorprone.annotations.CheckReturnValue;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.annotation.Nullable;

/** An implementation of the JavaBuilder that uses in-process javac to compile java files. */
public class SimpleJavaLibraryBuilder implements Closeable {

  /** The name of the protobuf meta file. */
  private static final String PROTOBUF_META_NAME = "protobuf.meta";

  BlazeJavacResult compileSources(JavaLibraryBuildRequest build, JavacRunner javacRunner)
      throws IOException {
    BlazeJavacResult result =
        javacRunner.invokeJavac(build.toBlazeJavacArguments(build.getClassPath()));

    BlazeJavacStatistics.Builder stats =
        result.statistics().toBuilder()
            .transitiveClasspathLength(build.getClassPath().size())
            .reducedClasspathLength(build.getClassPath().size())
            .transitiveClasspathFallback(false);
    build.getProcessors().stream()
        .map(p -> p.substring(p.lastIndexOf('.') + 1))
        .forEachOrdered(stats::addProcessor);
    return result.withStatistics(stats.build());
  }

  protected void prepareSourceCompilation(JavaLibraryBuildRequest build) throws IOException {
    cleanupDirectory(build.getClassDir());

    setUpSourceJars(build);
    cleanupDirectory(build.getSourceGenDir());
    cleanupDirectory(build.getNativeHeaderDir());
  }

  // Necessary for local builds in order to discard previous outputs
  private static void cleanupDirectory(@Nullable Path directory) throws IOException {
    if (directory == null) {
      return;
    }

    if (Files.exists(directory)) {
      try {
        MoreFiles.deleteRecursively(directory, RecursiveDeleteOption.ALLOW_INSECURE);
      } catch (IOException e) {
        throw new IOException("Cannot clean '" + directory + "'", e);
      }
    }

    Files.createDirectories(directory);
  }

  public void buildGensrcJar(JavaLibraryBuildRequest build) throws IOException {
    JarCreator jar = new JarCreator(build.getGeneratedSourcesOutputJar());
    try {
      jar.setNormalize(true);
      jar.setCompression(build.compressJar());
      jar.addDirectory(build.getSourceGenDir());
    } finally {
      jar.execute();
    }
  }

  /**
   * Prepares a compilation run and sets everything up so that the source files in the build request
   * can be compiled. Invokes compileSources to do the actual compilation.
   *
   * @param build A JavaLibraryBuildRequest request object describing what to compile
   */
  private BlazeJavacResult compileJavaLibrary(final JavaLibraryBuildRequest build)
      throws Exception {
    prepareSourceCompilation(build);
    if (build.getSourceFiles().isEmpty()) {
      return BlazeJavacResult.ok();
    }
    return compileSources(build, BlazeJavacMain::compile);
  }

  /** Perform the build. */
  @CheckReturnValue
  public BlazeJavacResult run(JavaLibraryBuildRequest build) throws Exception {
    BlazeJavacResult result = BlazeJavacResult.error("");
    try {
      result = compileJavaLibrary(build);
      if (result.isOk()) {
        buildJar(build);
        nativeHeaderOutput(build);
      }
      if (!build.getProcessors().isEmpty()) {
        if (build.getGeneratedSourcesOutputJar() != null) {
          buildGensrcJar(build);
        }
      }
    } finally {
      build
          .getDependencyModule()
          .emitDependencyInformation(
              build.getClassPath(),
              result.isOk(),
              /* requiresFallback= */ result.status() == Status.REQUIRES_FALLBACK);
      build.getProcessingModule().emitManifestProto();
    }
    return result;
  }

  public void buildJar(JavaLibraryBuildRequest build) throws IOException {
    Files.createDirectories(build.getOutputJar().getParent());
    JarCreator jar = new JarCreator(build.getOutputJar());
    JacocoInstrumentationProcessor processor = null;
    try {
      jar.setNormalize(true);
      jar.setCompression(build.compressJar());
      jar.addDirectory(build.getClassDir());
      jar.setJarOwner(build.getTargetLabel(), build.getInjectingRuleKind());
      processor = build.getJacocoInstrumentationProcessor();
      if (processor != null) {
        processor.processRequest(build, jar);
      }
    } finally {
      jar.execute();
      if (processor != null) {
        processor.cleanup();
      }
    }
  }

  public void nativeHeaderOutput(JavaLibraryBuildRequest build) throws IOException {
    if (build.getNativeHeaderOutput() == null) {
      return;
    }
    JarCreator jar = new JarCreator(build.getNativeHeaderOutput());
    try {
      jar.setNormalize(true);
      jar.setCompression(build.compressJar());
      jar.addDirectory(build.getNativeHeaderDir());
    } finally {
      jar.execute();
    }
  }

  /**
   * Extracts the all source jars from the build request into the temporary directory specified in
   * the build request. Empties the temporary directory, if it exists.
   */
  private void setUpSourceJars(JavaLibraryBuildRequest build) throws IOException {
    Path sourcesDir = build.getTempDir();

    cleanupDirectory(sourcesDir);

    if (build.getSourceJars().isEmpty()) {
      return;
    }

    final ByteArrayOutputStream protobufMetadataBuffer = new ByteArrayOutputStream();
    for (Path sourceJar : build.getSourceJars()) {
      try (JarFile jarFile = new JarFile(sourceJar.toFile())) {
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
          JarEntry entry = entries.nextElement();
          String fileName = entry.getName();
          if (fileName.endsWith(".java")) {
            if (fileName.charAt(0) == '/') {
              fileName = CharMatcher.is('/').trimLeadingFrom(fileName);
            }
            Path to = sourcesDir.resolve(fileName);
            int root = 1;
            if (Files.exists(to)) {
              // Make paths unique e.g. if extracting two srcjar entries that differ only in case
              // to a case-insenitive target filesystem (e.g. on Macs).
              do {
                to = sourcesDir.resolve(Integer.toString(root++)).resolve(fileName);
              } while (Files.exists(to));
            }
            Files.createDirectories(to.getParent());
            Files.copy(jarFile.getInputStream(entry), to);
            build.getSourceFiles().add(to);
          } else if (fileName.equals(PROTOBUF_META_NAME)) {
            ByteStreams.copy(jarFile.getInputStream(entry), protobufMetadataBuffer);
          }
        }
      } catch (IOException e) {
        throw new IOException("unable to open " + sourceJar + " as a jar file", e);
      }
    }
    Path output = build.getClassDir().resolve(PROTOBUF_META_NAME);
    if (protobufMetadataBuffer.size() > 0) {
      try (OutputStream outputStream = Files.newOutputStream(output)) {
        protobufMetadataBuffer.writeTo(outputStream);
      }
    } else if (Files.exists(output)) {
      // Delete stalled meta file.
      Files.delete(output);
    }
  }

  @Override
  public void close() throws IOException {}
}
