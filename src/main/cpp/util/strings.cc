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

#if defined(_WIN32) || defined(__CYGWIN__)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#endif  // defined(_WIN32) || defined(__CYGWIN__)

#include "src/main/cpp/util/strings.h"

#if defined(_WIN32) || defined(__CYGWIN__)
#include <windows.h>
#endif  // defined(_WIN32) || defined(__CYGWIN__)

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>

#include <cassert>
#include <memory>

#include "src/main/cpp/util/exit_code.h"

namespace blaze_util {

using std::string;
using std::unique_ptr;
using std::vector;
using std::wstring;

static const char kSeparator[] = " \n\t\r";

bool starts_with(const string &haystack, const string &needle) {
  return (haystack.length() >= needle.length()) &&
         (memcmp(haystack.c_str(), needle.c_str(), needle.length()) == 0);
}

template <typename char_type>
static bool ends_with_impl(const std::basic_string<char_type> &haystack,
                           const std::basic_string<char_type> &needle) {
  return (haystack.length() >= needle.length()) &&
         std::equal(haystack.cend() - needle.length(), haystack.cend(),
                    needle.cbegin());
}

bool ends_with(const string &haystack, const string &needle) {
  return ends_with_impl(haystack, needle);
}

bool ends_with(const wstring &haystack, const wstring &needle) {
  return ends_with_impl(haystack, needle);
}

void JoinStrings(const vector<string> &pieces, const char delimeter,
                 string *output) {
  bool first = true;
  for (const auto &piece : pieces) {
    if (first) {
      *output = piece;
      first = false;
    } else {
      *output += delimeter + piece;
    }
  }
}

vector<string> Split(const string &contents, const char delimeter) {
  vector<string> result;
  SplitStringUsing(contents, delimeter, &result);
  return result;
}

void SplitStringUsing(const string &contents, const char delimeter,
                      vector<string> *result) {
  assert(result);

  size_t start = 0;
  while (start < contents.length() && contents[start] == delimeter) {
    ++start;
  }

  size_t newline = contents.find(delimeter, start);
  while (newline != string::npos) {
    result->push_back(string(contents, start, newline - start));
    start = newline;
    while (start < contents.length() && contents[start] == delimeter) {
      ++start;
    }
    newline = contents.find(delimeter, start);
  }

  // If there is a trailing line, add that.
  if (start != newline && start != contents.size()) {
    result->push_back(string(contents, start));
  }
}

size_t SplitQuotedStringUsing(const string &contents, const char delimeter,
                              std::vector<string> *output) {
  size_t len = contents.length();
  size_t start = 0;
  size_t quote = string::npos;  // quote position
  size_t num_segments = 0;

  for (size_t pos = 0; pos < len; ++pos) {
    if (start == pos && contents[start] == delimeter) {
      ++start;
    } else if (contents[pos] == '\\') {
      ++pos;
    } else if (quote != string::npos && contents[pos] == contents[quote]) {
      quote = string::npos;
    } else if (quote == string::npos &&
               (contents[pos] == '"' || contents[pos] == '\'')) {
      quote = pos;
    } else if (quote == string::npos && contents[pos] == delimeter) {
      output->push_back(string(contents, start, pos - start));
      start = pos + 1;
      num_segments++;
    }
  }

  // A trailing element
  if (start < len) {
    output->push_back(string(contents, start));
    num_segments++;
  }
  return num_segments;
}

void Replace(const string &oldsub, const string &newsub, string *str) {
  size_t start = 0;
  // This is O(n^2) (the complexity of erase() is actually unspecified, but
  // usually linear).
  while ((start = str->find(oldsub, start)) != string::npos) {
    str->erase(start, oldsub.length());
    str->insert(start, newsub);
    start += newsub.length();
  }
}

void StripWhitespace(string *str) {
  int str_length = str->length();

  // Strip off leading whitespace.
  int first = 0;
  while (first < str_length && ascii_isspace(str->at(first))) {
    ++first;
  }
  // If entire string is white space.
  if (first == str_length) {
    str->clear();
    return;
  }
  if (first > 0) {
    str->erase(0, first);
    str_length -= first;
  }

  // Strip off trailing whitespace.
  int last = str_length - 1;
  while (last >= 0 && ascii_isspace(str->at(last))) {
    --last;
  }
  if (last != (str_length - 1) && last >= 0) {
    str->erase(last + 1, string::npos);
  }
}

static void GetNextToken(const string &str, const char &comment,
                         string::const_iterator *iter, vector<string> *words) {
  string output;
  auto last = *iter;
  char quote = '\0';
  // While not a delimiter.
  while (last != str.end() && (quote || strchr(kSeparator, *last) == nullptr)) {
    // Absorb escapes.
    if (*last == '\\') {
      ++last;
      if (last == str.end()) {
        break;
      }
      output += *last++;
      continue;
    }

    if (quote) {
      if (*last == quote) {
        // Absorb closing quote.
        quote = '\0';
        ++last;
      } else {
        output += *last++;
      }
    } else {
      if (*last == comment) {
        last = str.end();
        break;
      }
      if (*last == '\'' || *last == '"') {
        // Absorb opening quote.
        quote = *last++;
      } else {
        output += *last++;
      }
    }
  }

  if (!output.empty()) {
    words->push_back(output);
  }

  *iter = last;
}

void Tokenize(const string &str, const char &comment, vector<string> *words) {
  assert(words);
  words->clear();

  string::const_iterator i = str.begin();
  while (i != str.end()) {
    // Skip whitespace.
    while (i != str.end() && strchr(kSeparator, *i) != nullptr) {
      i++;
    }
    if (i != str.end() && *i == comment) {
      break;
    }
    GetNextToken(str, comment, &i, words);
  }
}

// Evaluate a format string and store the result in 'str'.
void StringPrintf(string *str, const char *format, ...) {
  assert(str);

  // Determine the required buffer size. vsnpritnf won't account for the
  // terminating '\0'.
  va_list args;
  va_start(args, format);
  int output_size = vsnprintf(nullptr, 0, format, args);
  if (output_size < 0) {
    fprintf(stderr, "Fatal error formatting string: %d", output_size);
    exit(blaze_exit_code::INTERNAL_ERROR);
  }
  va_end(args);

  // Allocate a buffer and format the input.
  int buffer_size = output_size + sizeof '\0';
  char *buf = new char[buffer_size];
  va_start(args, format);
  int print_result = vsnprintf(buf, buffer_size, format, args);
  if (print_result < 0) {
    fprintf(stderr, "Fatal error formatting string: %d", print_result);
    exit(blaze_exit_code::INTERNAL_ERROR);
  }
  va_end(args);

  *str = buf;
  delete[] buf;
}

string ToLower(const string &str) {
  if (str.empty()) {
    return "";
  }
  unique_ptr<char[]> result(new char[str.size() + 1]);
  char *result_ptr = result.get();
  for (const auto &ch : str) {
    *result_ptr++ = tolower(ch);
  }
  result.get()[str.size()] = 0;
  return string(result.get());
}

#if defined(_WIN32) || defined(__CYGWIN__)

template <typename U, typename V>
static bool UStrToVStr(const std::basic_string<U> &input,
                       std::basic_string<V> *output, const bool use_utf8,
                       int (*Convert)(const bool _utf8,
                                      const std::basic_string<U> &_in, V *_out,
                                      const size_t _size),
                       uint32_t *win32_error) {
  int buf_size = input.size() + 1;
  std::unique_ptr<V[]> buf(new V[buf_size]);
  // Attempt to convert, optimistically using the estimated output buffer size.
  int res = Convert(use_utf8, input, buf.get(), buf_size);
  if (res > 0) {
    *output = buf.get();
    return true;
  }

  DWORD err = GetLastError();
  if (err != ERROR_INSUFFICIENT_BUFFER) {
    if (win32_error) {
      *win32_error = static_cast<uint32_t>(err);
    }
    return false;
  }

  // The output buffer was too small. Get required buffer size.
  res = Convert(use_utf8, input, nullptr, 0);
  if (res > 0) {
    buf_size = res;
    buf.reset(new V[buf_size]);
    res = Convert(use_utf8, input, buf.get(), buf_size);
    if (res > 0) {
      *output = buf.get();
      return true;
    }
  }
  if (win32_error) {
    *win32_error = static_cast<uint32_t>(GetLastError());
  }
  return false;
}

static int ConvertWcsToMbs(const bool use_utf8, const std::wstring &input,
                           char *output, const size_t output_size) {
  return WideCharToMultiByte(use_utf8 ? CP_UTF8 : CP_ACP, 0, input.c_str(), -1,
                             output, output_size, nullptr, nullptr);
}

static int ConvertMbsToWcs(const bool /* unused */, const std::string &input,
                           wchar_t *output, const size_t output_size) {
  return MultiByteToWideChar(CP_UTF8, 0, input.c_str(), -1, output,
                             output_size);
}

bool WcsToAcp(const std::wstring &input, std::string *output,
              uint32_t *win32_error) {
  return UStrToVStr(input, output, false, ConvertWcsToMbs, win32_error);
}

bool WcsToUtf8(const std::wstring &input, std::string *output,
               uint32_t *win32_error) {
  return UStrToVStr(input, output, true, ConvertWcsToMbs, win32_error);
}

bool Utf8ToWcs(const std::string &input, std::wstring *output,
               uint32_t *win32_error) {
  return UStrToVStr(input, output, /* unused */ true, ConvertMbsToWcs,
                    win32_error);
}

std::string WstringToCstring(const std::wstring &input) {
  std::string result;
  uint32_t err;
  if (!WcsToUtf8(input, &result, &err)) {
    fprintf(stderr,
            "WstringToCstring: failed with error %d (0x%08x), "
            "invalid input \"%ls\"\n",
            err, err, input.c_str());
    exit(blaze_exit_code::INTERNAL_ERROR);
  }
  return result;
}

std::wstring CstringToWstring(const std::string &input) {
  std::wstring result;
  uint32_t err;
  if (!Utf8ToWcs(input, &result, &err)) {
    fprintf(stderr,
            "CstringToWstring: failed with error %d (0x%08x), "
            "invalid input \"%s\"\n",
            err, err, input.c_str());
    exit(blaze_exit_code::INTERNAL_ERROR);
  }
  return result;
}

#endif  // defined(_WIN32) || defined(__CYGWIN__)

}  // namespace blaze_util
