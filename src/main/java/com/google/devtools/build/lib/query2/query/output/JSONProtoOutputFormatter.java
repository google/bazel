// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.query.output;

import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.query2.engine.OutputFormatterCallback;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import com.google.protobuf.util.JsonFormat;

/**
 * An output formatter that outputs a protocol buffer json representation of a query result and outputs
 * the json to the output print stream. 
 */
public class JSONProtoOutputFormatter extends ProtoOutputFormatter {
    @Override
    public String getName() {
      return "jsonproto";
    }
    private final JsonFormat.Printer jsonPrinter = JsonFormat.printer();
  
    @Override
    public OutputFormatterCallback<Target> createPostFactoStreamCallback(
        final OutputStream out, final QueryOptions options, RepositoryMapping mainRepoMapping) {
      return new OutputFormatterCallback<Target>() {
        @Override
        public void processOutput(Iterable<Target> partialResult)
            throws IOException, InterruptedException {
          for (Target target : partialResult) {
            out.write(jsonPrinter.print(toTargetProtoBuffer(target)).getBytes(StandardCharsets.UTF_8));
          }
        }
      };
    }
}
