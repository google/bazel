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

#include "src/tools/singlejar/combiners.h"
#include "src/tools/singlejar/diag.h"
#include "src/tools/singlejar/log4j2_plugin_dat_combiner.h"
#include "src/tools/singlejar/options.h"
#include "src/tools/singlejar/output_jar.h"

int main(int argc, char *argv[]) {
  Options options;
  options.ParseCommandLine(argc - 1, argv + 1);
  OutputJar output_jar;
  // TODO(b/67733424): support desugar deps checking in Bazel
  if (options.check_desugar_deps) {
    diag_errx(1, "%s:%d: Desugar checking not currently supported in Bazel.",
                 __FILE__, __LINE__);
  } else {
    output_jar.ExtraCombiner("META-INF/desugar_deps", new NullCombiner());
  }
  output_jar.ExtraCombiner(
      "META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat",
      new Log4J2PluginDatCombiner("META-INF/org/apache/logging/log4j/core/"
                                  "config/plugins/Log4j2Plugins.dat",
                                  options.no_duplicates));
  output_jar.ExtraCombiner("reference.conf",
                           new Concatenator("reference.conf"));
  return output_jar.Doit(&options);
}
