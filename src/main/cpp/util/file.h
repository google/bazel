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
#ifndef BAZEL_SRC_MAIN_CPP_UTIL_FILE_H_
#define BAZEL_SRC_MAIN_CPP_UTIL_FILE_H_

#include <string>
#include <vector>

#include "src/main/cpp/util/file_platform.h"

namespace blaze_util {

class IPipe {
 public:
  // Error modes of the pipe.
  //
  // This is a platform-independent abstraction of `errno`. If you need to
  // handle an errno value, add an entry here and update the platform-specific
  // pipe implementations accordingly.
  enum Errors {
    SUCCESS = 0,
    OTHER_ERROR = 1,
    INTERRUPTED = 2,  // EINTR
  };

  virtual ~IPipe() {}

  // Sends `size` bytes from `buffer` through the pipe.
  // Returns true if `size` is not negative and could send all the data.
  virtual bool Send(const void *buffer, int size) = 0;

  // Receives at most `size` bytes into `buffer` from the pipe.
  // Returns the number of bytes received.
  // If `size` is negative or if there's an error, then returns -1, and if
  // `error` isn't NULL then sets its value to one of the `Errors`.
  virtual int Receive(void *buffer, int size, int *error) = 0;
};

// Replaces 'content' with data read from a source using `ReadFromHandle`.
// If `max_size` is positive, the method reads at most that many bytes;
// otherwise the method reads everything.
// Returns false on error. Can be called from a signal handler.
bool ReadFrom(file_handle_type handle, std::string *content, int max_size = 0);

// Reads up to `size` bytes using `ReadFromHandle` into `data`.
// There must be enough memory allocated at `data`.
// Returns true on success, false on error.
bool ReadFrom(file_handle_type handle, void *data, size_t size);

// Writes `content` into file `filename`, and chmods it to `perm`.
// Returns false on failure.
bool WriteFile(const std::string &content, const std::string &filename,
               unsigned int perm = 0644);

bool WriteFile(const std::string &content, const Path &path,
               unsigned int perm = 0644);

// Lists all files in `path` and all of its subdirectories.
//
// Does not follow symlinks / junctions.
//
// Populates `result` with the full paths of the files. Every entry will have
// `path` as its prefix. If `path` is a file, `result` contains just this
// file.
void GetAllFilesUnder(const Path &path, std::vector<Path> *result);

}  // namespace blaze_util

#endif  // BAZEL_SRC_MAIN_CPP_UTIL_FILE_H_
