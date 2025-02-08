// Copyright 2024 The Bazel Authors. All rights reserved.
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

#ifndef SRC_TOOLS_SINGLEJAR_LOG4J2_PLUGIN_DAT_COMBINER_H_
#define SRC_TOOLS_SINGLEJAR_LOG4J2_PLUGIN_DAT_COMBINER_H_ 1

#include <map>
#include <memory>
#include <string>

#include "src/tools/singlejar/combiners.h"

class PluginEntry {
 public:
  PluginEntry(const std::string &key, const std::string &className,
              const std::string &name, bool printable, bool defer,
              const std::string &category)
      : key(key),
        className(className),
        name(name),
        printable(printable),
        defer(defer),
        category(category) {}

  std::string key;
  std::string className;
  std::string name;
  bool printable;
  bool defer;
  std::string category;
};

class Log4J2PluginDatCombiner : public Combiner {
 public:
  Log4J2PluginDatCombiner(const std::string &filename, const bool no_duplicates)
      : filename_(filename), no_duplicates_(no_duplicates) {
    concatenator_.reset(new Concatenator(filename_, false));
  }
  ~Log4J2PluginDatCombiner() override;
  bool Merge(const CDH *cdh, const LH *lh) override;
  void *OutputEntry(bool compress) override;

 private:
  std::unique_ptr<Concatenator> concatenator_;
  const std::string filename_;
  const bool no_duplicates_;
  std::unique_ptr<Inflater> inflater_;
  std::map<std::string, std::map<std::string, PluginEntry>> categories_;
};

#endif  // SRC_TOOLS_SINGLEJAR_LOG4J2_PLUGIN_DAT_COMBINER_H_
