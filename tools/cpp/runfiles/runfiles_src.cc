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

// The "srcs_for_embedded_tools" rule in the same package sets the line below to
// include runfiles.h from the correct path. Do not modify the line below.
#include "tools/cpp/runfiles/runfiles_src.h"

#ifdef _WIN32
#include <windows.h>
#else  // not _WIN32
#include <stdlib.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#endif  // _WIN32

#include <algorithm>
#include <fstream>
#include <functional>
#include <map>
#include <sstream>
#include <vector>

#ifdef _WIN32
#include <memory>
#endif  // _WIN32

namespace bazel {
namespace tools {
namespace cpp {
namespace runfiles {

using std::function;
using std::map;
using std::pair;
using std::string;
using std::vector;

namespace {

bool starts_with(const string& s, const char* prefix) {
  if (!prefix || !*prefix) {
    return true;
  }
  if (s.empty()) {
    return false;
  }
  return s.find(prefix) == 0;
}

bool contains(const string& s, const char* substr) {
  if (!substr || !*substr) {
    return true;
  }
  if (s.empty()) {
    return false;
  }
  return s.find(substr) != string::npos;
}

bool ends_with(const string& s, const string& suffix) {
  if (suffix.empty()) {
    return true;
  }
  if (s.empty()) {
    return false;
  }
  return s.rfind(suffix) == s.size() - suffix.size();
}

bool IsReadableFile(const string& path) {
  return std::ifstream(path).is_open();
}

bool IsDirectory(const string& path) {
#ifdef _WIN32
  DWORD attrs = GetFileAttributesA(path.c_str());
  return (attrs != INVALID_FILE_ATTRIBUTES) &&
         (attrs & FILE_ATTRIBUTE_DIRECTORY);
#else
  struct stat buf;
  return stat(path.c_str(), &buf) == 0 && S_ISDIR(buf.st_mode);
#endif
}

bool PathsFrom(const std::string& argv0, std::string runfiles_manifest_file,
               std::string runfiles_dir, std::string* out_manifest,
               std::string* out_directory);

bool PathsFrom(const std::string& argv0, std::string runfiles_manifest_file,
               std::string runfiles_dir,
               std::function<bool(const std::string&)> is_runfiles_manifest,
               std::function<bool(const std::string&)> is_runfiles_directory,
               std::string* out_manifest, std::string* out_directory);

bool ParseManifest(const string& path, map<string, string>* result,
                   string* error);
bool ParseRepoMapping(const string& path,
                      map<pair<string, string>, string>* result, string* error);

}  // namespace

Runfiles* Runfiles::Create(const string& argv0,
                           const string& runfiles_manifest_file,
                           const string& runfiles_dir,
                           const string& source_repository, string* error) {
  string manifest, directory;
  if (!PathsFrom(argv0, runfiles_manifest_file, runfiles_dir, &manifest,
                 &directory)) {
    if (error) {
      std::ostringstream err;
      err << "ERROR: " << __FILE__ << "(" << __LINE__
          << "): cannot find runfiles (argv0=\"" << argv0 << "\")";
      *error = err.str();
    }
    return nullptr;
  }

  vector<pair<string, string> > envvars = {
      {"RUNFILES_MANIFEST_FILE", manifest},
      {"RUNFILES_DIR", directory},
      // TODO(laszlocsomor): remove JAVA_RUNFILES once the Java launcher can
      // pick up RUNFILES_DIR.
      {"JAVA_RUNFILES", directory}};

  map<string, string> runfiles;
  if (!manifest.empty()) {
    if (!ParseManifest(manifest, &runfiles, error)) {
      return nullptr;
    }
  }

  map<pair<string, string>, string> mapping;
  if (!ParseRepoMapping(
          RlocationUnchecked("_repo_mapping", runfiles, directory), &mapping,
          error)) {
    return nullptr;
  }

  return new Runfiles(std::move(runfiles), std::move(directory),
                      std::move(mapping), std::move(envvars),
                      string(source_repository));
}

bool IsAbsolute(const string& path) {
  if (path.empty()) {
    return false;
  }
  char c = path.front();
  return (c == '/' && (path.size() < 2 || path[1] != '/')) ||
         (path.size() >= 3 &&
          ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) &&
          path[1] == ':' && (path[2] == '\\' || path[2] == '/'));
}

string GetEnv(const string& key) {
#ifdef _WIN32
  DWORD size = ::GetEnvironmentVariableA(key.c_str(), nullptr, 0);
  if (size == 0) {
    return string();  // unset or empty envvar
  }
  std::unique_ptr<char[]> value(new char[size]);
  ::GetEnvironmentVariableA(key.c_str(), value.get(), size);
  return value.get();
#else
  char* result = getenv(key.c_str());
  return (result == nullptr) ? string() : string(result);
#endif
}

// Replaces \s, \n, and \b with their respective characters.
string Unescape(const string& path) {
  string result;
  result.reserve(path.size());
  for (size_t i = 0; i < path.size(); ++i) {
    if (path[i] == '\\' && i + 1 < path.size()) {
      switch (path[i + 1]) {
        case 's': {
          result.push_back(' ');
          break;
        }
        case 'n': {
          result.push_back('\n');
          break;
        }
        case 'b': {
          result.push_back('\\');
          break;
        }
        default: {
          result.push_back(path[i]);
          result.push_back(path[i + 1]);
          break;
        }
      }
      ++i;
    } else {
      result.push_back(path[i]);
    }
  }
  return result;
}

string Runfiles::Rlocation(const string& path) const {
  return Rlocation(path, source_repository_);
}

string Runfiles::Rlocation(const string& path,
                           const string& source_repo) const {
  if (path.empty() || starts_with(path, "../") || contains(path, "/../") ||
      ends_with(path, "/..") || starts_with(path, "./") ||
      contains(path, "/./") || ends_with(path, "/.") || contains(path, "//")) {
    return string();
  }
  if (IsAbsolute(path)) {
    return path;
  }

  string::size_type first_slash = path.find_first_of('/');
  if (first_slash == string::npos) {
    return RlocationUnchecked(path, runfiles_map_, directory_);
  }
  string target_apparent = path.substr(0, first_slash);
  auto target =
      repo_mapping_.find(std::make_pair(source_repo, target_apparent));
  if (target == repo_mapping_.cend()) {
    return RlocationUnchecked(path, runfiles_map_, directory_);
  }
  return RlocationUnchecked(target->second + path.substr(first_slash),
                            runfiles_map_, directory_);
}

string Runfiles::RlocationUnchecked(const string& path,
                                    const map<string, string>& runfiles_map,
                                    const string& directory) {
  const auto exact_match = runfiles_map.find(path);
  if (exact_match != runfiles_map.end()) {
    return exact_match->second;
  }
  if (!runfiles_map.empty()) {
    // If path references a runfile that lies under a directory that itself is a
    // runfile, then only the directory is listed in the manifest. Look up all
    // prefixes of path in the manifest and append the relative path from the
    // prefix to the looked up path.
    std::size_t prefix_end = path.size();
    while ((prefix_end = path.find_last_of('/', prefix_end - 1)) !=
           string::npos) {
      const string prefix = path.substr(0, prefix_end);
      const auto prefix_match = runfiles_map.find(prefix);
      if (prefix_match != runfiles_map.end()) {
        return prefix_match->second + "/" + path.substr(prefix_end + 1);
      }
    }
  }
  if (!directory.empty()) {
    return directory + "/" + path;
  }
  return "";
}

namespace {

bool ParseManifest(const string& path, map<string, string>* result,
                   string* error) {
  std::ifstream stm(path);
  if (!stm.is_open()) {
    if (error) {
      std::ostringstream err;
      err << "ERROR: " << __FILE__ << "(" << __LINE__
          << "): cannot open runfiles manifest \"" << path << "\"";
      *error = err.str();
    }
    return false;
  }
  string line;
  std::getline(stm, line);
  size_t line_count = 1;
  while (!line.empty()) {
    std::string source;
    std::string target;
    if (line[0] == ' ') {
      // The link path contains escape sequences for spaces and backslashes.
      string::size_type idx = line.find(' ', 1);
      if (idx == string::npos) {
        if (error) {
          std::ostringstream err;
          err << "ERROR: " << __FILE__ << "(" << __LINE__
              << "): bad runfiles manifest entry in \"" << path << "\" line #"
              << line_count << ": \"" << line << "\"";
          *error = err.str();
        }
        return false;
      }
      source = Unescape(line.substr(1, idx - 1));
      target = Unescape(line.substr(idx + 1));
    } else {
      string::size_type idx = line.find(' ');
      if (idx == string::npos) {
        if (error) {
          std::ostringstream err;
          err << "ERROR: " << __FILE__ << "(" << __LINE__
              << "): bad runfiles manifest entry in \"" << path << "\" line #"
              << line_count << ": \"" << line << "\"";
          *error = err.str();
        }
        return false;
      }
      source = line.substr(0, idx);
      target = line.substr(idx + 1);
    }
    (*result)[source] = target;
    std::getline(stm, line);
    ++line_count;
  }
  return true;
}

bool ParseRepoMapping(const string& path,
                      map<pair<string, string>, string>* result,
                      string* error) {
  std::ifstream stm(path);
  if (!stm.is_open()) {
    return true;
  }
  string line;
  std::getline(stm, line);
  size_t line_count = 1;
  while (!line.empty()) {
    string::size_type first_comma = line.find_first_of(',');
    if (first_comma == string::npos) {
      if (error) {
        std::ostringstream err;
        err << "ERROR: " << __FILE__ << "(" << __LINE__
            << "): bad repository mapping entry in \"" << path << "\" line #"
            << line_count << ": \"" << line << "\"";
        *error = err.str();
      }
      return false;
    }
    string::size_type second_comma = line.find_first_of(',', first_comma + 1);
    if (second_comma == string::npos) {
      if (error) {
        std::ostringstream err;
        err << "ERROR: " << __FILE__ << "(" << __LINE__
            << "): bad repository mapping entry in \"" << path << "\" line #"
            << line_count << ": \"" << line << "\"";
        *error = err.str();
      }
      return false;
    }

    string source = line.substr(0, first_comma);
    string target_apparent =
        line.substr(first_comma + 1, second_comma - (first_comma + 1));
    string target = line.substr(second_comma + 1);

    (*result)[std::make_pair(source, target_apparent)] = target;
    std::getline(stm, line);
    ++line_count;
  }
  return true;
}

}  // namespace

namespace testing {

bool TestOnly_PathsFrom(const string& argv0, string mf, string dir,
                        function<bool(const string&)> is_runfiles_manifest,
                        function<bool(const string&)> is_runfiles_directory,
                        string* out_manifest, string* out_directory) {
  return PathsFrom(argv0, mf, dir, is_runfiles_manifest, is_runfiles_directory,
                   out_manifest, out_directory);
}

bool TestOnly_IsAbsolute(const string& path) { return IsAbsolute(path); }

}  // namespace testing

Runfiles* Runfiles::Create(const std::string& argv0,
                           const std::string& runfiles_manifest_file,
                           const std::string& runfiles_dir,
                           std::string* error) {
  return Runfiles::Create(argv0, runfiles_manifest_file, runfiles_dir, "",
                          error);
}

Runfiles* Runfiles::Create(const string& argv0, const string& source_repository,
                           string* error) {
  return Runfiles::Create(argv0, GetEnv("RUNFILES_MANIFEST_FILE"),
                          GetEnv("RUNFILES_DIR"), source_repository, error);
}

Runfiles* Runfiles::Create(const string& argv0, string* error) {
  return Runfiles::Create(argv0, "", error);
}

Runfiles* Runfiles::CreateForTest(const string& source_repository,
                                  std::string* error) {
  return Runfiles::Create(std::string(), GetEnv("RUNFILES_MANIFEST_FILE"),
                          GetEnv("TEST_SRCDIR"), source_repository, error);
}

Runfiles* Runfiles::CreateForTest(std::string* error) {
  return Runfiles::CreateForTest("", error);
}

namespace {

bool PathsFrom(const string& argv0, string mf, string dir, string* out_manifest,
               string* out_directory) {
  return PathsFrom(
      argv0, mf, dir, [](const string& path) { return IsReadableFile(path); },
      [](const string& path) { return IsDirectory(path); }, out_manifest,
      out_directory);
}

bool PathsFrom(const string& argv0, string mf, string dir,
               function<bool(const string&)> is_runfiles_manifest,
               function<bool(const string&)> is_runfiles_directory,
               string* out_manifest, string* out_directory) {
  out_manifest->clear();
  out_directory->clear();

  bool mfValid = is_runfiles_manifest(mf);
  bool dirValid = is_runfiles_directory(dir);

  if (!argv0.empty() && !mfValid && !dirValid) {
    mf = argv0 + ".runfiles/MANIFEST";
    dir = argv0 + ".runfiles";
    mfValid = is_runfiles_manifest(mf);
    dirValid = is_runfiles_directory(dir);
    if (!mfValid) {
      mf = argv0 + ".runfiles_manifest";
      mfValid = is_runfiles_manifest(mf);
    }
  }

  if (!mfValid && !dirValid) {
    return false;
  }

  if (!mfValid) {
    mf = dir + "/MANIFEST";
    mfValid = is_runfiles_manifest(mf);
    if (!mfValid) {
      mf = dir + "_manifest";
      mfValid = is_runfiles_manifest(mf);
    }
  }

  if (!dirValid &&
      (ends_with(mf, ".runfiles_manifest") || ends_with(mf, "/MANIFEST"))) {
    static const size_t kSubstrLen = 9;  // "_manifest" or "/MANIFEST"
    dir = mf.substr(0, mf.size() - kSubstrLen);
    dirValid = is_runfiles_directory(dir);
  }

  if (mfValid) {
    *out_manifest = mf;
  }

  if (dirValid) {
    *out_directory = dir;
  }

  return true;
}

}  // namespace

}  // namespace runfiles
}  // namespace cpp
}  // namespace tools
}  // namespace bazel
