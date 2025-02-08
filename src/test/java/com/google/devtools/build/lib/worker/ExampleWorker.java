// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.worker;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat;
import com.google.devtools.build.lib.worker.ExampleWorkerOptions.ExampleWorkOptions;
import com.google.devtools.build.lib.worker.WorkRequestHandler.WorkerMessageProcessor;
import com.google.devtools.build.lib.worker.WorkerProtocol.Input;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.common.options.OptionsParser;
import com.google.gson.stream.JsonReader;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** An example implementation of a worker process that is used for integration tests. */
public final class ExampleWorker {

  static final Pattern FLAG_FILE_PATTERN = Pattern.compile("(?:@|--?flagfile=)(.+)");

  // A UUID that uniquely identifies this running worker process.
  static final UUID WORKER_UUID = UUID.randomUUID();

  // A counter that increases with each work unit processed.
  static int workUnitCounter = 1;

  // If true, returns corrupt responses instead of correct protobufs.
  static boolean poisoned = false;

  static final LinkedHashMap<String, String> inputs = new LinkedHashMap<>();

  // Contains the request currently being worked on.
  private static WorkRequest currentRequest;

  // The options passed to this worker on a per-worker-lifetime basis.
  static ExampleWorkerOptions workerOptions;
  private static WorkerMessageProcessor messageProcessor;

  private static class InterruptableWorkRequestHandler extends WorkRequestHandler {

    InterruptableWorkRequestHandler(
        BiFunction<List<String>, PrintWriter, Integer> callback,
        PrintStream stderr,
        WorkerMessageProcessor messageProcessor) {
      super(callback, stderr, messageProcessor);
    }

    @Override
    @SuppressWarnings("SystemExitOutsideMain")
    public void processRequests() throws IOException {
      ByteArrayOutputStream captured = new ByteArrayOutputStream();
      WorkerIO workerIO = new WorkerIO(System.in, System.out, System.err, captured, captured);

      while (true) {
        WorkRequest request = messageProcessor.readWorkRequest();
        if (request == null) {
          break;
        }

        currentRequest = request;
        inputs.clear();
        for (Input input : request.getInputsList()) {
          inputs.put(input.getPath(), input.getDigest().toStringUtf8());
        }
        if (poisoned && workerOptions.hardPoison) {
          throw new IllegalStateException("I'm a very poisoned worker and will just crash.");
        }
        if (request.getCancel()) {
          respondToCancelRequest(request);
        } else {
          startResponseThread(workerIO, request);
        }
        if (workerOptions.exitAfter > 0 && workUnitCounter > workerOptions.exitAfter) {
          System.exit(0);
        }
      }

      try {
        // Unwrap the system streams placing the original streams back
        workerIO.close();
      } catch (Exception e) {
        workerIO.getOriginalErrorStream().println(e.getMessage());
      }
    }
  }

  public static void main(String[] args) throws Exception {
    if (ImmutableSet.copyOf(args).contains("--persistent_worker")) {
      System.err.printf("Worker args: %s\n", String.join(" ", args));
      OptionsParser parser =
          OptionsParser.builder()
              .optionsClasses(ExampleWorkerOptions.class)
              .allowResidue(false)
              .build();
      parser.parse(args);
      workerOptions = parser.getOptions(ExampleWorkerOptions.class);
      WorkerProtocolFormat protocolFormat = workerOptions.workerProtocol;
      messageProcessor = null;
      switch (protocolFormat) {
        case JSON:
          messageProcessor =
              new JsonWorkerMessageProcessor(
                  new JsonReader(new BufferedReader(new InputStreamReader(System.in, UTF_8))),
                  new BufferedWriter(new OutputStreamWriter(System.out, UTF_8)));
          break;
        case PROTO:
          messageProcessor = new ProtoWorkerMessageProcessor(System.in, System.out);
          break;
      }
      Preconditions.checkNotNull(messageProcessor);
      WorkRequestHandler workRequestHandler =
          new InterruptableWorkRequestHandler(ExampleWorker::doWork, System.err, messageProcessor);
      workRequestHandler.processRequests();

    } else {
      // This is a single invocation of the example that exits after it processed the request.
      parseOptionsAndLog(ImmutableList.copyOf(args));
    }
  }

  private static int doWork(List<String> args, PrintWriter err) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    PrintStream originalStdOut = System.out;
    PrintStream originalStdErr = System.err;

    if (workerOptions.waitForCancel) {
      try {
        WorkRequest workRequest = messageProcessor.readWorkRequest();
        if (workRequest.getRequestId() != currentRequest.getRequestId()) {
          System.err.format(
              "Got cancel request for %d while expecting cancel request for %d%n",
              workRequest.getRequestId(), currentRequest.getRequestId());
          return 1;
        }
        if (!workRequest.getCancel()) {
          System.err.format(
              "Got non-cancel request for %d while expecting cancel request%n",
              workRequest.getRequestId());
          return 1;
        }
      } catch (IOException e) {
        throw new RuntimeException("Exception while waiting for cancel request", e);
      }
    }
    try (PrintStream ps = new PrintStream(baos)) {
      System.setOut(ps);
      System.setErr(ps);
      if (poisoned) {
        System.out.println("I'm a poisoned worker and this is not a protobuf.");
        System.out.println("Here's a fake stack trace for you:");
        System.out.println("    at com.example.Something(Something.java:83)");
        System.out.println("    at java.lang.Thread.run(Thread.java:745)");
        System.out.print("And now, 8k of random bytes: ");
        byte[] b = new byte[8192];
        new Random().nextBytes(b);
        try {
          System.out.write(b);
        } catch (IOException e) {
          e.printStackTrace();
          return 1;
        }
      } else {
        try {
          if (currentRequest.getVerbosity() > 0) {
            originalStdErr.println("VERBOSE: Pretending to do work.");
            originalStdErr.println("VERBOSE: Running in " + new File(".").getAbsolutePath());
          }
          parseOptionsAndLog(args);
        } catch (Exception e) {
          e.printStackTrace();
          return 1;
        }
      }
    } finally {
      System.setOut(originalStdOut);
      System.setErr(originalStdErr);
      currentRequest = null;
    }

    if (workerOptions.exitDuring > 0 && workUnitCounter > workerOptions.exitDuring) {
      System.exit(0);
    }

    if (poisoned) {
      try {
        baos.writeTo(System.out);
        System.out.flush();
        System.exit(1);
      } catch (IOException e) {
        e.printStackTrace();
        System.exit(1);
      }
    }
    if (workerOptions.poisonAfter > 0 && workUnitCounter > workerOptions.poisonAfter) {
      poisoned = true;
    }
    return 0;
  }

  private static void parseOptionsAndLog(List<String> args) throws Exception {
    ImmutableList.Builder<String> expandedArgs = ImmutableList.builder();
    for (String arg : args) {
      Matcher flagFileMatcher = FLAG_FILE_PATTERN.matcher(arg);
      if (flagFileMatcher.matches()) {
        expandedArgs.addAll(Files.readAllLines(Paths.get(flagFileMatcher.group(1)), UTF_8));
      } else {
        expandedArgs.add(arg);
      }
    }

    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ExampleWorkOptions.class).allowResidue(true).build();
    parser.parse(expandedArgs.build());
    ExampleWorkOptions options = parser.getOptions(ExampleWorkOptions.class);

    List<String> outputs = new ArrayList<>();

    if (options.writeUUID) {
      outputs.add("UUID " + WORKER_UUID);
    }

    if (options.writeCounter) {
      outputs.add("COUNTER " + workUnitCounter++);
    }

    String residueStr = Joiner.on(' ').join(parser.getResidue());
    if (options.uppercase) {
      residueStr = Ascii.toUpperCase(residueStr);
    }
    outputs.add(residueStr);

    if (options.printInputs) {
      for (Map.Entry<String, String> input : inputs.entrySet()) {
        outputs.add("INPUT " + input.getKey() + " " + input.getValue());
      }
    }

    if (!options.printDirListing.isEmpty()) {
      Path rootDir = Path.of(options.printDirListing);
      try (Stream<Path> paths = Files.walk(rootDir, Integer.MAX_VALUE)) {
        for (Path path : paths.collect(toImmutableList())) {
          outputs.add(String.format("DIRENT %s %s", rootDir.relativize(path), getInode(path)));
        }
      }
    }

    if (options.printRequests) {
      outputs.add("REQUEST: " + currentRequest);
    }

    if (options.printEnv) {
      for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
        outputs.add(entry.getKey() + "=" + entry.getValue());
      }
    }

    if (options.workTime != null) {
      try {
        Thread.sleep(options.workTime.toMillis());
      } catch (InterruptedException e) {
        System.err.printf(
            "Interrupted while pretending to work for %d millis%n", options.workTime.toMillis());
      }
    }

    String outputStr = Joiner.on('\n').join(outputs);
    if (options.outputFile.isEmpty()) {
      System.out.println(outputStr);
    } else {
      try (PrintStream outputFile = new PrintStream(options.outputFile)) {
        outputFile.println(outputStr);
      }
    }
  }

  private static long getInode(Path path) throws IOException {
    return (long) Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS);
  }

  private ExampleWorker() {}
}
