// Copyright 2018 The Bazel Authors. All rights reserved.
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

// Deprecated forwarder for the rules_cc runfiles library.
//
// Depend on @rules_cc//cc/runfiles instead and refer to its header file:
// https://github.com/bazelbuild/rules_cc/blob/main/cc/runfiles/runfiles.h

#ifndef TOOLS_CPP_RUNFILES_RUNFILES_H_
#define TOOLS_CPP_RUNFILES_RUNFILES_H_ 1

#include "rules_cc/cc/runfiles/runfiles.h"

namespace bazel {
namespace tools {
namespace cpp {
namespace runfiles {

typedef ::rules_cc::cc::runfiles::Runfiles Runfiles;

}  // namespace runfiles
}  // namespace cpp
}  // namespace tools
}  // namespace bazel

#endif  // TOOLS_CPP_RUNFILES_RUNFILES_H_
