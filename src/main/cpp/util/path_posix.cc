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

#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <vector>

#include "src/main/cpp/util/exit_code.h"
#include "src/main/cpp/util/file_platform.h"
#include "src/main/cpp/util/logging.h"
#include "src/main/cpp/util/path.h"
#include "src/main/cpp/util/path_platform.h"

namespace blaze_util {

std::string ConvertPath(const std::string &path) { return path; }

std::string PathAsJvmFlag(const std::string &path) { return path; }

bool ArePathsEquivalent(const blaze_util::Path &a, const blaze_util::Path &b) {
  return a == b;
}

std::pair<std::string, std::string> SplitPath(const std::string &path) {
  size_t pos = path.rfind('/');

  // Handle the case with no '/' in 'path'.
  if (pos == std::string::npos) return std::make_pair("", path);

  // Handle the case with a single leading '/' in 'path'.
  if (pos == 0)
    return std::make_pair(std::string(path, 0, 1), std::string(path, 1));

  return std::make_pair(std::string(path, 0, pos), std::string(path, pos + 1));
}

bool IsDevNull(const char *path) {
  return path != nullptr && *path != 0 && strncmp("/dev/null\0", path, 10) == 0;
}

bool IsRootDirectory(const std::string &path) {
  return path.size() == 1 && path[0] == '/';
}

bool IsRootDirectory(const Path &path) {
  return IsRootDirectory(path.AsNativePath());
}

bool IsAbsolute(const std::string &path) {
  return !path.empty() && path[0] == '/';
}

std::string MakeAbsolute(const std::string &path) {
  if (blaze_util::IsAbsolute(path) || path.empty()) {
    return path;
  }

  return JoinPath(blaze_util::GetCwd(), path);
}

std::string ResolveEnvvars(const std::string &path) {
  std::string result = path;
  size_t start = 0;
  while ((start = result.find("${", start)) != std::string::npos) {
    // Just match to the next }
    size_t end = result.find('}', start + 1);
    if (end == std::string::npos) {
      BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
          << "ResolveEnvvars(" << path << "): incomplete variable at position "
          << start;
    }
    // Extract the variable name
    const std::string name = result.substr(start + 2, end - start - 2);
    // Get the value from the environment
    const char *c_value = getenv(name.c_str());
    const std::string value = std::string(c_value ? c_value : "");
    result.replace(start, end - start + 1, value);
    start += value.length();
  }
  return result;
}

std::string MakeAbsoluteAndResolveEnvvars(const std::string &path) {
  return MakeAbsolute(ResolveEnvvars(path));
}

static std::string NormalizeAbsPath(const std::string &p) {
  if (p.empty() || p[0] != '/') {
    return "";
  }
  typedef std::string::size_type index;
  std::vector<std::pair<index, index> > segments;
  for (index s = 0; s < p.size();) {
    index e = p.find_first_of('/', s);
    if (e == std::string::npos) {
      e = p.size();
    }
    if (e > s) {
      if (p.compare(s, e - s, "..") == 0) {
        if (!segments.empty()) {
          segments.pop_back();
        }
      } else if (p.compare(s, e - s, ".") != 0) {
        segments.push_back(std::make_pair(s, e - s));
      }
    }
    s = e + 1;
  }
  if (segments.empty()) {
    return "/";
  } else {
    std::stringstream r;
    for (const auto &s : segments) {
      r << "/" << p.substr(s.first, s.second);
    }
    if (p[p.size() - 1] == '/') {
      r << "/";
    }
    return r.str();
  }
}

std::string TestOnly_NormalizeAbsPath(const std::string &s) {
  return NormalizeAbsPath(s);
}

Path::Path(const std::string &path)
    : path_(NormalizeAbsPath(MakeAbsolute(path))) {}

Path::Path(const std::string &path, std::string *errorText)
    : path_(NormalizeAbsPath(MakeAbsolute(path))) {}

bool Path::IsNull() const { return path_ == "/dev/null"; }

bool Path::Contains(const char c) const {
  return path_.find_first_of(c) != std::string::npos;
}

bool Path::Contains(const std::string &s) const {
  return path_.find(s) != std::string::npos;
}

Path Path::GetRelative(const std::string &r) const {
  return Path(JoinPath(path_, r));
}

Path Path::Canonicalize() const { return Path(MakeCanonical(path_.c_str())); }

std::string Path::GetBaseName() const { return SplitPath(path_).second; }

Path Path::GetParent() const { return Path(SplitPath(path_).first); }

std::string Path::AsPrintablePath() const { return path_; }

std::string Path::AsJvmArgument() const { return path_; }

std::string Path::AsCommandLineArgument() const { return path_; }

}  // namespace blaze_util
