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

#ifndef THIRD_PARTY_BAZEL_SRC_TOOLS_SINGLEJAR_OPTIONS_H_
#define THIRD_PARTY_BAZEL_SRC_TOOLS_SINGLEJAR_OPTIONS_H_

#include <string>
#include <vector>

#include "src/tools/singlejar/token_stream.h"

/* Command line options. */
class Options {
 public:
  Options()
      : output_jar_creator("singlejar"),
        build_target(""),
        exclude_build_data(false),
        force_compression(false),
        normalize_timestamps(false),
        add_missing_directories(false),
        no_duplicates(false),
        no_duplicate_classes(false),
        preserve_compression(false),
        verbose(false),
        warn_duplicate_resources(false),
        check_desugar_deps(false),
        multi_release(false),
        no_strip_module_info(false) {}

  virtual ~Options() {}

  // Parses command line arguments into the fields of this instance.
  void ParseCommandLine(int argc, const char *const argv[]);

  std::string output_jar;
  std::string output_jar_creator;
  std::string build_target;
  std::string main_class;
  std::string java_launcher;
  std::string cds_archive;
  std::string jdk_lib_modules;
  std::vector<std::string> manifest_lines;
  std::vector<std::pair<std::string, std::string> > input_jars;
  std::vector<std::string> resources;
  std::vector<std::string> classpath_resources;
  std::vector<std::string> build_info_files;
  std::vector<std::string> build_info_lines;
  std::vector<std::string> include_prefixes;
  std::vector<std::string> nocompress_suffixes;
  bool exclude_build_data;
  bool force_compression;
  bool normalize_timestamps;
  bool add_missing_directories;
  bool no_duplicates;
  bool no_duplicate_classes;
  bool preserve_compression;
  bool verbose;
  bool warn_duplicate_resources;
  bool check_desugar_deps;
  bool multi_release;
  bool no_strip_module_info;
  std::string hermetic_java_home;
  std::vector<std::string> add_exports;
  std::vector<std::string> add_opens;

 protected:
  /*
   * Given the token stream, consume one notional flag from the input stream and
   * return true if the flag was recognized and fully consumed. This notional
   * flag may result in many tokens being consumed, as flags like --inputs ends
   * up consuming many future tokens: --inputs a b c d e --some_other_flag
   */
  virtual bool ParseToken(ArgTokenStream *tokens);

  /*
   * After all of the command line options are consumed, validate that the
   * options make sense. This function will exit(1) if invalid combinations of
   * flags are passed (e.g.: is missing --output_jar)
   */
  virtual void PostValidateOptions();
};

#endif  // THIRD_PARTY_BAZEL_SRC_TOOLS_SINGLEJAR_OPTIONS_H_
