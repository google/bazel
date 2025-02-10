// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.coverageoutputgenerator;

import static com.google.devtools.coverageoutputgenerator.Constants.GCOV_EXTENSION;
import static com.google.devtools.coverageoutputgenerator.Constants.GCOV_JSON_EXTENSION;
import static com.google.devtools.coverageoutputgenerator.Constants.PROFDATA_EXTENSION;
import static com.google.devtools.coverageoutputgenerator.Constants.TRACEFILE_EXTENSION;
import static java.lang.Math.max;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Command line utility to convert raw coverage files to lcov (text) format. */
public class Main {
  private static final Logger logger = Logger.getLogger(Main.class.getName());

  public static void main(String... args) {
    try {
      int exitCode = runWithArgs(args);
      System.exit(exitCode);
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Unhandled exception on lcov tool: " + e.getMessage(), e);
      System.exit(1);
    }
  }

  static int runWithArgs(String... args) throws ExecutionException, InterruptedException {
    LcovMergerFlags flags = null;
    try {
      flags = LcovMergerFlags.parseFlags(args);
    } catch (IllegalArgumentException e) {
      logger.log(Level.SEVERE, e.getMessage());
      return 1;
    }

    File outputFile = new File(flags.outputFile());

    List<File> filesInCoverageDir =
        flags.coverageDir() != null
            ? getCoverageFilesInDir(flags.coverageDir())
            : ImmutableList.of();
    Coverage coverage =
        Coverage.merge(
            parseFiles(
                getTracefiles(flags, filesInCoverageDir),
                LcovParser::parse,
                flags.parseParallelism()),
            parseFiles(
                getGcovInfoFiles(filesInCoverageDir), GcovParser::parse, flags.parseParallelism()),
            parseFiles(
                getGcovJsonInfoFiles(filesInCoverageDir),
                GcovJsonParser::parse,
                flags.parseParallelism()));

    if (flags.sourcesToReplaceFile() != null) {
      coverage.maybeReplaceSourceFileNames(getMapFromFile(flags.sourcesToReplaceFile()));
    }

    File profdataFile = getProfdataFileOrNull(filesInCoverageDir);
    if (coverage.isEmpty()) {
      int exitStatus = 0;
      if (profdataFile == null) {
        try {
          logger.log(Level.WARNING, "There was no coverage found.");
          if (!Files.exists(outputFile.toPath())) {
            Files.createFile(outputFile.toPath()); // Generate empty declared output
          }
          exitStatus = 0;
        } catch (IOException e) {
          logger.log(
              Level.SEVERE,
              "Could not create empty output file "
                  + outputFile.getName()
                  + " due to: "
                  + e.getMessage());
          exitStatus = 1;
        }
      } else {
        // Bazel doesn't support yet converting profdata files to lcov. We still want to output a
        // coverage report so we copy the content of the profdata file to the output file. This is
        // not ideal but it unblocks some Bazel C++
        // coverage users.
        // TODO(#5881): Add support for profdata files.
        try {
          Files.copy(profdataFile.toPath(), outputFile.toPath(), REPLACE_EXISTING);
        } catch (IOException e) {
          logger.log(
              Level.SEVERE,
              "Could not copy file "
                  + profdataFile.getName()
                  + " to output file "
                  + outputFile.getName()
                  + " due to: "
                  + e.getMessage());
          exitStatus = 1;
        }
      }
      return exitStatus;
    }

    if (!coverage.isEmpty() && profdataFile != null) {
      // If there is one profdata file then there can't be other types of reports because there is
      // no way to merge them.
      // TODO(#5881): Add support for profdata files.
      logger.log(
          Level.WARNING,
          "Bazel doesn't support LLVM profdata coverage amongst other coverage formats.");
      return 0;
    }

    if (!flags.filterSources().isEmpty()) {
      coverage = Coverage.filterOutMatchingSources(coverage, flags.filterSources());
    }

    if (flags.hasSourceFileManifest()) {
      coverage =
          Coverage.getOnlyTheseSources(
              coverage, getSourcesFromSourceFileManifest(flags.sourceFileManifest()));
    }

    if (coverage.isEmpty()) {
      try {
        logger.log(Level.WARNING, "There was no coverage found.");
        if (!Files.exists(outputFile.toPath())) {
          Files.createFile(outputFile.toPath()); // Generate empty declared output
        }
        return 0;
      } catch (IOException e) {
        logger.log(
            Level.SEVERE,
            "Could not create empty output file "
                + outputFile.getName()
                + " due to: "
                + e.getMessage());
        return 1;
      }
    }

    int exitStatus = 0;

    try {
      LcovPrinter.print(new FileOutputStream(outputFile), coverage);
    } catch (IOException e) {
      logger.log(
          Level.SEVERE,
          "Could not write to output file " + outputFile + " due to " + e.getMessage());
      exitStatus = 1;
    }
    return exitStatus;
  }

  /**
   * Returns a set of source file names from the given manifest.
   *
   * <p>The manifest contains file names line by line. Each file can either be a source file (e.g.
   * .java, .cc) or a coverage metadata file (e.g. .gcno, .em).
   *
   * <p>This method only returns the C++ source files, ignoring the other files as they are not
   * necessary when putting together the final coverage report.
   */
  private static Set<String> getSourcesFromSourceFileManifest(String sourceFileManifest) {
    Set<String> sourceFiles = new HashSet<>();
    try (FileInputStream inputStream = new FileInputStream(new File(sourceFileManifest));
        InputStreamReader inputStreamReader = new InputStreamReader(inputStream, UTF_8);
        BufferedReader reader = new BufferedReader(inputStreamReader)) {
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        if (!isMetadataFile(line)) {
          sourceFiles.add(line);
        }
      }
    } catch (IOException e) {
      logger.log(Level.SEVERE, "Error reading file " + sourceFileManifest + ": " + e.getMessage());
    }
    return sourceFiles;
  }

  private static boolean isMetadataFile(String filename) {
    return filename.endsWith(".gcno") || filename.endsWith(".em");
  }

  private static List<File> getGcovInfoFiles(List<File> filesInCoverageDir) {
    List<File> gcovFiles = getFilesWithExtension(filesInCoverageDir, GCOV_EXTENSION);
    if (gcovFiles.isEmpty()) {
      logger.log(Level.FINE, "No gcov info file found.");
    } else {
      logger.log(Level.FINE, "Found " + gcovFiles.size() + " gcov info files.");
    }
    return gcovFiles;
  }

  private static List<File> getGcovJsonInfoFiles(List<File> filesInCoverageDir) {
    List<File> gcovJsonFiles = getFilesWithExtension(filesInCoverageDir, GCOV_JSON_EXTENSION);
    if (gcovJsonFiles.isEmpty()) {
      logger.log(Level.FINE, "No gcov json file found.");
    } else {
      logger.log(Level.FINE, "Found " + gcovJsonFiles.size() + " gcov json files.");
    }
    return gcovJsonFiles;
  }

  /**
   * Returns a .profdata file from the given files or null if none or more profdata files were
   * found.
   */
  private static File getProfdataFileOrNull(List<File> files) {
    List<File> profdataFiles = getFilesWithExtension(files, PROFDATA_EXTENSION);
    if (profdataFiles.isEmpty()) {
      logger.log(Level.FINE, "No .profdata file found.");
      return null;
    }
    if (profdataFiles.size() > 1) {
      logger.log(
          Level.SEVERE,
          "Bazel currently supports only one profdata file per test. "
              + profdataFiles.size()
              + " .profadata files were found instead.");
      return null;
    }
    logger.log(Level.FINE, "Found one .profdata file.");
    return profdataFiles.get(0);
  }

  private static List<File> getTracefiles(LcovMergerFlags flags, List<File> filesInCoverageDir) {
    List<File> lcovTracefiles = new ArrayList<>();
    if (flags.reportsFile() != null) {
      lcovTracefiles = getTracefilesFromFile(flags.reportsFile());
    } else if (flags.coverageDir() != null) {
      lcovTracefiles = getFilesWithExtension(filesInCoverageDir, TRACEFILE_EXTENSION);
    }
    if (lcovTracefiles.isEmpty()) {
      logger.log(Level.FINE, "No lcov file found.");
    } else {
      logger.log(Level.FINE, "Found " + lcovTracefiles.size() + " tracefiles.");
    }
    return lcovTracefiles;
  }

  /**
   * Reads the content of the given file and returns a matching map.
   *
   * <p>It assumes the file contains lines in the form key:value. For each line it creates an entry
   * in the map with the corresponding key and value.
   */
  private static ImmutableMap<String, String> getMapFromFile(String file) {
    ImmutableMap.Builder<String, String> mapBuilder = ImmutableMap.builder();

    try (FileInputStream inputStream = new FileInputStream(file);
        InputStreamReader inputStreamReader = new InputStreamReader(inputStream, UTF_8);
        BufferedReader reader = new BufferedReader(inputStreamReader)) {
      for (String keyToValueLine = reader.readLine();
          keyToValueLine != null;
          keyToValueLine = reader.readLine()) {
        String[] keyAndValue = keyToValueLine.split(":");
        if (keyAndValue.length == 2) {
          mapBuilder.put(keyAndValue[0], keyAndValue[1]);
        }
      }
    } catch (IOException e) {
      logger.log(Level.SEVERE, "Error reading file " + file + ": " + e.getMessage());
    }
    return mapBuilder.buildOrThrow();
  }

  static Coverage parseFiles(List<File> files, Parser parser, int parallelism)
      throws ExecutionException, InterruptedException {
    if (parallelism == 1) {
      return parseFilesSequentially(files, parser);
    } else {
      return parseFilesInParallel(files, parser, parallelism);
    }
  }

  static Coverage parseFilesSequentially(List<File> files, Parser parser) {
    Coverage coverage = new Coverage();
    for (File file : files) {
      try {
        logger.log(Level.FINE, "Parsing file " + file);
        List<SourceFileCoverage> sourceFilesCoverage = parser.parse(new FileInputStream(file));
        for (SourceFileCoverage sourceFileCoverage : sourceFilesCoverage) {
          coverage.add(sourceFileCoverage);
        }
      } catch (IOException e) {
        logger.log(
            Level.SEVERE,
            "File " + file.getAbsolutePath() + " could not be parsed due to: " + e.getMessage(),
            e);
        System.exit(1);
      }
    }
    return coverage;
  }

  static Coverage parseFilesInParallel(List<File> files, Parser parser, int parallelism)
      throws ExecutionException, InterruptedException {
    ForkJoinPool pool = new ForkJoinPool(parallelism);
    int partitionSize = max(1, files.size() / parallelism);
    List<List<File>> partitions = Lists.partition(files, partitionSize);
    return pool.submit(
            () ->
                partitions.parallelStream()
                    .map((p) -> parseFilesSequentially(p, parser))
                    .reduce((c1, c2) -> Coverage.merge(c1, c2))
                    .orElse(Coverage.create()))
        .get();
  }

  /**
   * Returns a list of all the files with the given extension found recursively under the given dir.
   */
  @VisibleForTesting
  static List<File> getCoverageFilesInDir(String dir) {
    List<File> files = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(Paths.get(dir))) {
      files =
          stream
              .filter(
                  p ->
                      p.toString().endsWith(TRACEFILE_EXTENSION)
                          || p.toString().endsWith(GCOV_EXTENSION)
                          || p.toString().endsWith(GCOV_JSON_EXTENSION)
                          || p.toString().endsWith(PROFDATA_EXTENSION))
              .map(path -> path.toFile())
              .collect(Collectors.toList());
    } catch (IOException ex) {
      logger.log(Level.SEVERE, "Error reading folder " + dir + ": " + ex.getMessage());
    }
    return files;
  }

  static List<File> getFilesWithExtension(List<File> files, String extension) {
    return files.stream()
        .filter(file -> file.toString().endsWith(extension))
        .collect(Collectors.toList());
  }

  static List<File> getTracefilesFromFile(String reportsFile) {
    List<File> datFiles = new ArrayList<>();
    try (FileInputStream inputStream = new FileInputStream(reportsFile)) {
      InputStreamReader inputStreamReader = new InputStreamReader(inputStream, UTF_8);
      BufferedReader reader = new BufferedReader(inputStreamReader);
      for (String tracefile = reader.readLine(); tracefile != null; tracefile = reader.readLine()) {
        datFiles.add(new File(tracefile));
      }

    } catch (IOException e) {
      logger.log(Level.SEVERE, "Error reading file " + reportsFile + ": " + e.getMessage());
    }
    return datFiles;
  }
}
