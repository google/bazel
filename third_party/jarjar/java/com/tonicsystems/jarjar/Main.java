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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.tonicsystems.jarjar.util.IoUtil;
import com.tonicsystems.jarjar.util.StandaloneJarProcessor;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Main class for Jarjar CLI. */
public class Main {

  public static void main(String[] argv) throws Exception {
    List<String> args = Arrays.asList(argv);
    if (args.isEmpty()) {
      help();
      return;
    }

    List<String> commandArgs = args.subList(1, args.size());
    switch (args.get(0)) {
      case "strings":
        strings(commandArgs);
        return;
      case "find":
        find(commandArgs);
        return;
      case "process":
        process(commandArgs);
        return;
      default:
        help();
        return;
    }
  }

  private static void help() throws IOException {
    try (InputStream helpStream = Main.class.getResourceAsStream("help.txt")) {
      String helpText =
          new String(helpStream.readAllBytes(), UTF_8).replace("\n", System.lineSeparator());
      System.err.print(helpText);
    }
  }

  private static void strings(List<String> args) throws Exception {
    if (args.isEmpty()) {
      throw new IllegalArgumentException("cp is required");
    }
    String cp = args.get(0);

    PrintWriter stdout = IoUtil.bufferedPrintWriter(System.out, UTF_8);
    new StringDumper().run(cp, stdout);
    stdout.flush();
  }

  private static void find(List<String> args) throws IOException {
    if (args.size() < 3) {
      throw new IllegalArgumentException("level and cp1 are required");
    }
    DepHandler.Level level = DepHandler.Level.valueOf(args.get(0).toUpperCase(Locale.ROOT));
    String cp1 = args.get(1);
    String cp2 = (args.size() == 2) ? cp1 : args.get(2);

    PrintWriter stdout = IoUtil.bufferedPrintWriter(System.out, UTF_8);
    DepHandler handler = new TextDepHandler(stdout, level);
    new DepFind().run(cp1, cp2, handler);
    stdout.flush();
  }

  private static void process(List<String> args) throws IOException {
    if (args.size() < 3) {
      throw new IllegalArgumentException("rulesFile, inJar, and outJar are required");
    }
    File rulesFile = new File(args.get(0));
    File inJar = new File(args.get(1));
    File outJar = new File(args.get(2));

    List<PatternElement> rules = RulesFileParser.parse(rulesFile);
    boolean verbose = Boolean.getBoolean("verbose");
    boolean skipManifest = Boolean.getBoolean("skipManifest");
    MainProcessor proc = new MainProcessor(rules, verbose, skipManifest);
    StandaloneJarProcessor.run(inJar, outJar, proc);
    proc.strip(outJar);
  }
}
