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
//
// ijar.cpp -- .jar -> _interface.jar tool.
//

#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <memory>

#include "third_party/ijar/zip.h"

namespace devtools_ijar {

bool verbose = false;

// Reads a JVM class from classdata_in (of the specified length), and
// writes out a simplified class to classdata_out, advancing the
// pointer. Returns true if the class should be kept.
bool StripClass(u1 *&classdata_out, const u1 *classdata_in, size_t in_length);

const char *CLASS_EXTENSION = ".class";
const size_t CLASS_EXTENSION_LENGTH = strlen(CLASS_EXTENSION);
const char *TRANSITIVE_PREFIX = "META-INF/TRANSITIVE/";
const size_t TRANSITIVE_PREFIX_LENGTH = strlen(TRANSITIVE_PREFIX);
const char *KOTLIN_BUILTINS_EXTENSION = ".kotlin_builtins";
const size_t KOTLIN_BUILTINS_EXTENSION_LENGTH =
    strlen(KOTLIN_BUILTINS_EXTENSION);
const char *KOTLIN_MODULE_EXTENSION = ".kotlin_module";
const size_t KOTLIN_MODULE_EXTENSION_LENGTH = strlen(KOTLIN_MODULE_EXTENSION);
const char *SCALA_TASTY_EXTENSION = ".tasty";
const size_t SCALA_TASTY_EXTENSION_LENGTH = strlen(SCALA_TASTY_EXTENSION);

const char *KOTLIN_PKG_PATH = "kotlin/";
const size_t KOTLIN_PKG_PATH_LENGTH = strlen(KOTLIN_PKG_PATH);
const char *MANIFEST_DIR_PATH = "META-INF/";
const size_t MANIFEST_DIR_PATH_LENGTH = strlen(MANIFEST_DIR_PATH);
const char *MANIFEST_PATH = "META-INF/MANIFEST.MF";
const size_t MANIFEST_PATH_LENGTH = strlen(MANIFEST_PATH);
const char *MANIFEST_HEADER =
    "Manifest-Version: 1.0\r\n"
    "Created-By: bazel\r\n";
const size_t MANIFEST_HEADER_LENGTH = strlen(MANIFEST_HEADER);
// These attributes are used by JavaBuilder, Turbine, and ijar.
// They must all be kept in sync.
const char *TARGET_LABEL_KEY = "Target-Label: ";
const size_t TARGET_LABEL_KEY_LENGTH = strlen(TARGET_LABEL_KEY);
const char *INJECTING_RULE_KIND_KEY = "Injecting-Rule-Kind: ";
const size_t INJECTING_RULE_KIND_KEY_LENGTH = strlen(INJECTING_RULE_KIND_KEY);
const char *DUMMY_FILE = "dummy";
const size_t DUMMY_PATH_LENGTH = strlen(DUMMY_FILE);
// The size of an output jar containing only an empty dummy file:
const size_t JAR_WITH_DUMMY_FILE_SIZE = 98ull + 2 * DUMMY_PATH_LENGTH;

class JarExtractorProcessor : public ZipExtractorProcessor {
 public:
  // Set the ZipBuilder to add the ijar class to the output zip file.
  // This pointer should not be deleted while this class is still in use and
  // it should be set before any call to the Process() method.
  void SetZipBuilder(ZipBuilder *builder) { this->builder_ = builder; }
  virtual void WriteManifest(const char *target_label,
                             const char *injecting_rule_kind) = 0;

 protected:
  // Not owned by JarStripperProcessor, see SetZipBuilder().
  ZipBuilder *builder_;
};

// ZipExtractorProcessor that select only .class file and use
// StripClass to generate an interface class, storing as a new file
// in the specified ZipBuilder.
class JarStripperProcessor : public JarExtractorProcessor {
 public:
  JarStripperProcessor() {}
  virtual ~JarStripperProcessor() {}

  virtual void Process(const char *filename, u4 attr, const u1 *data,
                       size_t size);
  virtual bool Accept(const char *filename, u4 attr);

  virtual void WriteManifest(const char *target_label,
                             const char *injecting_rule_kind);
};

static bool StartsWith(const char *str, const size_t str_len,
                       const char *prefix, const size_t prefix_len) {
  return str_len >= prefix_len && strncmp(str, prefix, prefix_len) == 0;
}

static bool EndsWith(const char *str, const size_t str_len, const char *suffix,
                     const size_t suffix_len) {
  return str_len >= suffix_len &&
         strcmp(str + str_len - suffix_len, suffix) == 0;
}

// Returns true for .kotlin_module and the similar .kotlin_builtins files.
static bool IsKotlinModule(const char *filename, const size_t filename_len) {
  return (StartsWith(filename, filename_len, MANIFEST_DIR_PATH,
                     MANIFEST_DIR_PATH_LENGTH) &&
          EndsWith(filename, filename_len, KOTLIN_MODULE_EXTENSION,
                   KOTLIN_MODULE_EXTENSION_LENGTH)) ||
         (StartsWith(filename, filename_len, KOTLIN_PKG_PATH,
                     KOTLIN_PKG_PATH_LENGTH) &&
          EndsWith(filename, filename_len, KOTLIN_BUILTINS_EXTENSION,
                   KOTLIN_BUILTINS_EXTENSION_LENGTH));
}

static bool IsScalaTasty(const char *filename, const size_t filename_len) {
  return EndsWith(filename, filename_len, SCALA_TASTY_EXTENSION,
                  SCALA_TASTY_EXTENSION_LENGTH);
}

bool JarStripperProcessor::Accept(const char *filename, const u4 /*attr*/) {
  const size_t filename_len = strlen(filename);
  if (IsKotlinModule(filename, filename_len) ||
      IsScalaTasty(filename, filename_len)) {
    return true;
  }
  if (filename_len < CLASS_EXTENSION_LENGTH ||
      strcmp(filename + filename_len - CLASS_EXTENSION_LENGTH,
             CLASS_EXTENSION) != 0) {
    return false;
  }
  if (StartsWith(filename, filename_len, TRANSITIVE_PREFIX,
                 TRANSITIVE_PREFIX_LENGTH)) {
    return false;
  }
  return true;
}

static bool IsModuleInfo(const char *filename) {
  const char *slash = strrchr(filename, '/');
  if (slash == NULL) {
    slash = filename;
  } else {
    slash++;
  }
  return strcmp(slash, "module-info.class") == 0;
}

void JarStripperProcessor::Process(const char *filename, const u4 /*attr*/,
                                   const u1 *data, const size_t size) {
  if (verbose) {
    fprintf(stderr, "INFO: StripClass: %s\n", filename);
  }
  if (IsModuleInfo(filename) || IsKotlinModule(filename, strlen(filename)) ||
      IsScalaTasty(filename, strlen(filename))) {
    u1 *q = builder_->NewFile(filename, 0);
    memcpy(q, data, size);
    builder_->FinishFile(size, /* compress: */ false, /* compute_crc: */ true);
  } else {
    u1 *buf = reinterpret_cast<u1 *>(malloc(size));
    u1 *classdata_out = buf;
    if (!StripClass(buf, data, size)) {
      free(classdata_out);
      return;
    }
    u1 *q = builder_->NewFile(filename, 0);
    size_t out_length = buf - classdata_out;
    memcpy(q, classdata_out, out_length);
    builder_->FinishFile(out_length, /* compress: */ false,
                         /* compute_crc: */ true);
    free(classdata_out);
  }
}

// Copies the string into the buffer without the null terminator, returns
// updated buffer pointer
static u1 *WriteStr(u1 *buf, const char *str) {
  size_t len = strlen(str);
  memcpy(buf, str, len);
  return buf + len;
}

// Writes a manifest attribute including a "\r\n" line break, returns updated
// buffer pointer.
static u1 *WriteManifestAttr(u1 *buf, const char *key, const char *val) {
  buf = WriteStr(buf, key);
  buf = WriteStr(buf, val);
  *buf++ = '\r';
  *buf++ = '\n';
  return buf;
}

void JarStripperProcessor::WriteManifest(const char *target_label,
                                         const char *injecting_rule_kind) {
  if (target_label == nullptr) {
    return;
  }
  builder_->WriteEmptyFile(MANIFEST_DIR_PATH);
  u1 *start = builder_->NewFile(MANIFEST_PATH, 0);
  u1 *buf = start;
  buf = WriteStr(buf, MANIFEST_HEADER);
  buf = WriteManifestAttr(buf, TARGET_LABEL_KEY, target_label);
  if (injecting_rule_kind) {
    buf = WriteManifestAttr(buf, INJECTING_RULE_KIND_KEY, injecting_rule_kind);
  }
  size_t total_len = buf - start;
  builder_->FinishFile(total_len, /* compress: */ false,
                       /* compute_crc: */ true);
}

class JarCopierProcessor : public JarExtractorProcessor {
 public:
  JarCopierProcessor(const char *jar) : jar_(jar) {}
  virtual ~JarCopierProcessor() {}

  virtual void Process(const char *filename, u4 /*attr*/, const u1 *data,
                       size_t size);
  virtual bool Accept(const char *filename, u4 /*attr*/);

  virtual void WriteManifest(const char *target_label,
                             const char *injecting_rule_kind);

 private:
  class ManifestLocator : public ZipExtractorProcessor {
   public:
    ManifestLocator() : manifest_buf_(nullptr), manifest_size_(0) {}
    virtual ~ManifestLocator() { free(manifest_buf_); }

    u1 *manifest_buf_;
    size_t manifest_size_;

    virtual bool Accept(const char *filename, const u4 /*attr*/) {
      return strcmp(filename, MANIFEST_PATH) == 0;
    }

    virtual void Process(const char * /*filename*/, const u4 /*attr*/,
                         const u1 *data, const size_t size) {
      manifest_buf_ = (u1 *)malloc(size);
      memmove(manifest_buf_, data, size);
      manifest_size_ = size;
    }
  };

  const char *jar_;

  u1 *AppendTargetLabelToManifest(u1 *buf, const u1 *manifest_data, size_t size,
                                  const char *target_label,
                                  const char *injecting_rule_kind);
};

void JarCopierProcessor::Process(const char *filename, const u4 /*attr*/,
                                 const u1 *data, const size_t size) {
  if (verbose) {
    fprintf(stderr, "INFO: CopyFile: %s\n", filename);
  }
  // We already handled the manifest in WriteManifest
  if (strcmp(filename, MANIFEST_DIR_PATH) == 0 ||
      strcmp(filename, MANIFEST_PATH) == 0) {
    return;
  }
  u1 *q = builder_->NewFile(filename, 0);
  memcpy(q, data, size);
  builder_->FinishFile(size, /* compress: */ false, /* compute_crc: */ true);
}

bool JarCopierProcessor::Accept(const char * /*filename*/, const u4 /*attr*/) {
  return true;
}

void JarCopierProcessor::WriteManifest(const char *target_label,
                                       const char *injecting_rule_kind) {
  ManifestLocator manifest_locator;
  std::unique_ptr<ZipExtractor> in(
      ZipExtractor::Create(jar_, &manifest_locator));
  in->ProcessAll();

  bool wants_manifest =
      manifest_locator.manifest_buf_ != nullptr || target_label != nullptr;
  if (wants_manifest) {
    builder_->WriteEmptyFile(MANIFEST_DIR_PATH);
    u1 *start = builder_->NewFile(MANIFEST_PATH, 0);
    u1 *buf = start;
    // Three cases:
    // 1. We need to merge the target label into a pre-existing manifest
    // 2. Write a manifest from scratch with a target label
    // 3. Copy existing manifest without adding target label
    if (manifest_locator.manifest_buf_ != nullptr && target_label != nullptr) {
      buf = AppendTargetLabelToManifest(buf, manifest_locator.manifest_buf_,
                                        manifest_locator.manifest_size_,
                                        target_label, injecting_rule_kind);
    } else if (target_label != nullptr) {
      buf = WriteStr(buf, MANIFEST_HEADER);
      buf = WriteManifestAttr(buf, TARGET_LABEL_KEY, target_label);
      if (injecting_rule_kind) {
        buf = WriteManifestAttr(buf, INJECTING_RULE_KIND_KEY,
                                injecting_rule_kind);
      }
    } else {
      memcpy(buf, manifest_locator.manifest_buf_,
             manifest_locator.manifest_size_);
      buf += manifest_locator.manifest_size_;
    }

    size_t total_len = buf - start;
    builder_->FinishFile(total_len, /* compress: */ false,
                         /* compute_crc: */ true);
  }
}

u1 *JarCopierProcessor::AppendTargetLabelToManifest(
    u1 *buf, const u1 *manifest_data, const size_t size,
    const char *target_label, const char *injecting_rule_kind) {
  const char *line_start = (const char *)manifest_data;
  const char *data_end = (const char *)manifest_data + size;

  // Write main attributes part
  while (line_start < data_end && line_start[0] != '\r' &&
         line_start[0] != '\n') {
    const char *line_end = strchr(line_start, '\n');
    // Go past return char to point to next line, or to end of data buffer
    line_end = line_end != nullptr ? line_end + 1 : data_end;

    // Copy line unless it's Target-Label/Injecting-Rule-Kind and we're writing
    // that ourselves
    if (strncmp(line_start, TARGET_LABEL_KEY, TARGET_LABEL_KEY_LENGTH) != 0 &&
        strncmp(line_start, INJECTING_RULE_KIND_KEY,
                INJECTING_RULE_KIND_KEY_LENGTH) != 0) {
      size_t len = line_end - line_start;
      memcpy(buf, line_start, len);
      buf += len;
    }
    line_start = line_end;
  }

  // Append target label and, if given, rule kind
  buf = WriteManifestAttr(buf, TARGET_LABEL_KEY, target_label);
  if (injecting_rule_kind != nullptr) {
    buf = WriteManifestAttr(buf, INJECTING_RULE_KIND_KEY, injecting_rule_kind);
  }

  // Write the rest of the manifest file
  size_t sections_len = data_end - line_start;
  if (sections_len > 0) {
    memcpy(buf, line_start, sections_len);
    buf += sections_len;
  }
  return buf;
}

// WriteManifest, including zip file format overhead.
static size_t EstimateManifestOutputSize(const char *target_label,
                                         const char *injecting_rule_kind) {
  if (target_label == nullptr) {
    return 0;
  }
  // local headers
  size_t length = 30 * 2 + MANIFEST_DIR_PATH_LENGTH + MANIFEST_PATH_LENGTH;
  // central directory
  length += 46 * 2 + MANIFEST_DIR_PATH_LENGTH + MANIFEST_PATH_LENGTH;
  // zip64 EOCD entries
  length += 56 * 2;

  // manifest content
  length += MANIFEST_HEADER_LENGTH;
  // target label manifest entry, including newline
  length += TARGET_LABEL_KEY_LENGTH + strlen(target_label) + 2;
  if (injecting_rule_kind) {
    // injecting rule kind manifest entry, including newline
    length += INJECTING_RULE_KIND_KEY_LENGTH + strlen(injecting_rule_kind) + 2;
  }
  return length;
}

// Opens "file_in" (a .jar file) for reading, and writes an interface
// .jar to "file_out".
static void OpenFilesAndProcessJar(const char *file_out, const char *file_in,
                                   bool strip_jar, const char *target_label,
                                   const char *injecting_rule_kind) {
  std::unique_ptr<JarExtractorProcessor> processor;
  if (strip_jar) {
    processor =
        std::unique_ptr<JarExtractorProcessor>(new JarStripperProcessor());
  } else {
    processor =
        std::unique_ptr<JarExtractorProcessor>(new JarCopierProcessor(file_in));
  }
  std::unique_ptr<ZipExtractor> in(
      ZipExtractor::Create(file_in, processor.get()));
  if (in == NULL) {
    fprintf(stderr, "Unable to open Zip file %s: %s\n", file_in,
            strerror(errno));
    abort();
  }
  u8 output_length = in->CalculateOutputLength();
  if (output_length < JAR_WITH_DUMMY_FILE_SIZE) {
    output_length = JAR_WITH_DUMMY_FILE_SIZE;
  }
  output_length +=
      EstimateManifestOutputSize(target_label, injecting_rule_kind);

  std::unique_ptr<ZipBuilder> out(ZipBuilder::Create(file_out, output_length));
  if (out == NULL) {
    fprintf(stderr, "Unable to open output file %s: %s\n", file_out,
            strerror(errno));
    abort();
  }
  processor->SetZipBuilder(out.get());
  processor->WriteManifest(target_label, injecting_rule_kind);

  // Process all files in the zip
  if (in->ProcessAll() < 0) {
    fprintf(stderr, "%s\n", in->GetError());
    abort();
  }

  // Add dummy file, since javac doesn't like truly empty jars.
  if (out->GetNumberFiles() == 0) {
    out->WriteEmptyFile(DUMMY_FILE);
  }
  // Finish writing the output file
  if (out->Finish() < 0) {
    fprintf(stderr, "%s\n", out->GetError());
    abort();
  }
  // Get all file size
  size_t in_length = in->GetSize();
  size_t out_length = out->GetSize();
  if (verbose) {
    fprintf(stderr, "INFO: produced interface jar: %s -> %s (%d%%).\n", file_in,
            file_out, static_cast<int>(100.0 * out_length / in_length));
  }
}
}  // namespace devtools_ijar

//
// main method
//
static void usage() {
  fprintf(stderr,
          "Usage: ijar "
          "[-v] [--[no]strip_jar] "
          "[--target label label] [--injecting_rule_kind kind] "
          "x.jar [x_interface.jar>]\n");
  fprintf(stderr, "Creates an interface jar from the specified jar file.\n");
  exit(1);
}

int main(int argc, char **argv) {
  bool strip_jar = true;
  const char *target_label = NULL;
  const char *injecting_rule_kind = NULL;
  const char *filename_in = NULL;
  const char *filename_out = NULL;

  for (int ii = 1; ii < argc; ++ii) {
    if (strcmp(argv[ii], "-v") == 0) {
      devtools_ijar::verbose = true;
    } else if (strcmp(argv[ii], "--strip_jar") == 0) {
      strip_jar = true;
    } else if (strcmp(argv[ii], "--nostrip_jar") == 0) {
      strip_jar = false;
    } else if (strcmp(argv[ii], "--target_label") == 0) {
      if (++ii >= argc) {
        usage();
      }
      target_label = argv[ii];
    } else if (strcmp(argv[ii], "--injecting_rule_kind") == 0) {
      if (++ii >= argc) {
        usage();
      }
      injecting_rule_kind = argv[ii];
    } else if (filename_in == NULL) {
      filename_in = argv[ii];
    } else if (filename_out == NULL) {
      filename_out = argv[ii];
    } else {
      usage();
    }
  }

  if (filename_in == NULL) {
    usage();
  }

  // Guess output filename from input:
  char filename_out_buf[PATH_MAX];
  if (filename_out == NULL) {
    size_t len = strlen(filename_in);
    if (len > 4 && strncmp(filename_in + len - 4, ".jar", 4) == 0) {
      strcpy(filename_out_buf, filename_in);
      strcpy(filename_out_buf + len - 4, "-interface.jar");
      filename_out = filename_out_buf;
    } else {
      fprintf(stderr,
              "Can't determine output filename since input filename "
              "doesn't end with '.jar'.\n");
      return 1;
    }
  }

  if (devtools_ijar::verbose) {
    fprintf(stderr, "INFO: writing to '%s'.\n", filename_out);
  }

  devtools_ijar::OpenFilesAndProcessJar(filename_out, filename_in, strip_jar,
                                        target_label, injecting_rule_kind);
  return 0;
}
