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

/*
 * The implementation of the OutputJar methods.
 */
#include "src/tools/singlejar/output_jar.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <time.h>

#include <cstring>

#ifndef _WIN32
#include <unistd.h>
#else

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif  // WIN32_LEAN_AND_MEAN
#include <windows.h>

#endif  // _WIN32

#include "src/main/cpp/util/path_platform.h"
#include "src/tools/singlejar/combiners.h"
#include "src/tools/singlejar/diag.h"
#include "src/tools/singlejar/input_jar.h"
#include "src/tools/singlejar/mapped_file.h"
#include "src/tools/singlejar/options.h"
#include "src/tools/singlejar/zip_headers.h"

#include <zlib.h>

#define TODO(cond, msg)                                              \
  if (!(cond)) {                                                     \
    diag_errx(2, "%s:%d: TODO(asmundak): " msg, __FILE__, __LINE__); \
  }

OutputJar::OutputJar()
    : options_(nullptr),
      file_(nullptr),
      outpos_(0),
      buffer_(nullptr),
      entries_(0),
      duplicate_entries_(0),
      cen_(nullptr),
      cen_size_(0),
      cen_capacity_(0),
      spring_handlers_("META-INF/spring.handlers"),
      spring_schemas_("META-INF/spring.schemas"),
      protobuf_meta_handler_("protobuf.meta", false),
      manifest_("META-INF/MANIFEST.MF"),
      build_properties_("build-data.properties") {
  known_members_.emplace(spring_handlers_.filename(),
                         EntryInfo{&spring_handlers_});
  known_members_.emplace(spring_schemas_.filename(),
                         EntryInfo{&spring_schemas_});
  known_members_.emplace(manifest_.filename(), EntryInfo{&manifest_});
  known_members_.emplace(protobuf_meta_handler_.filename(),
                         EntryInfo{&protobuf_meta_handler_});
}

static std::string Basename(const std::string &path) {
  size_t pos = path.rfind('/');
  if (pos == std::string::npos) {
    return path;
  } else {
    return std::string(path, pos + 1);
  }
}

int OutputJar::Doit(Options *options) {
  if (nullptr != options_) {
    diag_errx(1, "%s:%d: Doit() can be called only once.", __FILE__, __LINE__);
  }
  options_ = options;

  // Register the handler for the build-data.properties file unless
  // --exclude_build_data is present. Otherwise we do not generate this file,
  // and it will be copied from the first source archive containing it.
  if (!options_->exclude_build_data) {
    known_members_.emplace(build_properties_.filename(),
                           EntryInfo{&build_properties_});
  }

  // Populate the manifest file.
  manifest_.AppendLine("Manifest-Version: 1.0");
  manifest_.AppendLine("Created-By: " + options_->output_jar_creator);

  // TODO(b/28294322): remove fallback to output_jar
  build_properties_.AddProperty("build.target",
                                !options_->build_target.empty()
                                    ? options_->build_target.c_str()
                                    : options_->output_jar.c_str());
  if (options_->verbose) {
    fprintf(stderr, "combined_file_name=%s\n", options_->output_jar.c_str());
    if (!options_->main_class.empty()) {
      fprintf(stderr, "main_class=%s\n", options_->main_class.c_str());
    }
    if (!options_->java_launcher.empty()) {
      fprintf(stderr, "java_launcher_file=%s\n",
              options_->java_launcher.c_str());
    }
    fprintf(stderr, "%zu source files\n", options_->input_jars.size());
    fprintf(stderr, "%zu manifest lines\n", options_->manifest_lines.size());
  }

  if (!Open()) {
    exit(1);
  }

  // Copy launcher if it is set.
  if (!options_->java_launcher.empty()) {
    AppendFile(options_, options_->java_launcher.c_str());
  }

  if (!options_->main_class.empty()) {
    build_properties_.AddProperty("main.class", options_->main_class);
    manifest_.AppendLine("Main-Class: " + options_->main_class);
  }

  // Copy CDS archive file (.jsa) if it is set. Page aligned start offset
  // is required.
  if (!options_->cds_archive.empty()) {
    AppendPageAlignedFile(options->cds_archive,
                          "Jsa-Offset",
                          std::string(),
                          "cds.archive");
  }

  // Copy JDK lib/modules if set. Page aligned start offset is required for
  // the file.
  if (!options_->jdk_lib_modules.empty()) {
    AppendPageAlignedFile(options_->jdk_lib_modules,
                          "JDK-Lib-Modules-Offset",
                          "JDK-Lib-Modules-Size",
                          std::string());
  }

  if (options_->multi_release) {
    manifest_.EnableMultiRelease();
  }
  manifest_.AddExports(options_->add_exports);
  manifest_.AddOpens(options_->add_opens);
  if (!options_->hermetic_java_home.empty()) {
    manifest_.AppendLine("Hermetic-Java-Home: " + options_->hermetic_java_home);
  }
  for (auto &manifest_line : options_->manifest_lines) {
    if (!manifest_line.empty()) {
      manifest_.AppendLine(manifest_line);
    }
  }

  for (auto &build_info_line : options_->build_info_lines) {
    build_properties_.Append(build_info_line);
    build_properties_.Append("\n");
  }

  for (auto &build_info_file : options_->build_info_files) {
    MappedFile mapped_file;
    if (!mapped_file.Open(build_info_file)) {
      diag_err(1, "%s:%d: Bad build info file %s", __FILE__, __LINE__,
               build_info_file.c_str());
    }
    const char *data = reinterpret_cast<const char *>(mapped_file.start());
    const char *data_end = reinterpret_cast<const char *>(mapped_file.end());
    // TODO(asmundak): this isn't right, we should parse properties file.
    while (data < data_end) {
      const char *next_data = strchr(static_cast<const char *>(data), '\n');
      if (next_data) {
        ++next_data;
      } else {
        next_data = data_end;
      }
      build_properties_.Append(data, next_data - data);
      data = next_data;
    }
    mapped_file.Close();
  }

  for (auto &rpath : options_->classpath_resources) {
    ClasspathResource(Basename(rpath), rpath);
  }

  for (auto &rdesc : options_->resources) {
    // A resource description is either NAME or PATH:NAME
    // Find the last ':' instead of the first because Windows uses ':' as volume
    // separator in absolute path.
    std::size_t colon = rdesc.find_last_of(':');
    if (0 == colon) {
      diag_errx(1, "%s:%d: Bad resource description %s", __FILE__, __LINE__,
                rdesc.c_str());
    }
    bool shouldSplit = colon != std::string::npos;
#ifdef _WIN32
    // If colon points to volume separator, don't split.
    if (colon == 1 && blaze_util::IsAbsolute(rdesc)) {
      shouldSplit = false;
    }
#endif
    if (shouldSplit) {
      ClasspathResource(rdesc.substr(colon + 1), rdesc.substr(0, colon));
    } else {
      ClasspathResource(rdesc, rdesc);
    }
  }

  // Ready to write zip entries. Decide whether created entries should be
  // compressed.
  bool compress = options_->force_compression || options_->preserve_compression;
  // First, write a directory entry for the META-INF, followed by the manifest
  // file, followed by the build properties file.
  WriteMetaInf();
  WriteEntry(manifest_.OutputEntry(compress));
  if (!options_->exclude_build_data) {
    WriteEntry(build_properties_.OutputEntry(compress));
  }

  // Then classpath resources.
  for (auto &classpath_resource : classpath_resources_) {
    bool do_compress = compress;
    if (do_compress && !options_->nocompress_suffixes.empty()) {
      for (auto &suffix : options_->nocompress_suffixes) {
        auto entry_name = classpath_resource->filename();
        if (entry_name.length() >= suffix.size() &&
            !entry_name.compare(entry_name.length() - suffix.size(),
                                suffix.size(), suffix)) {
          do_compress = false;
          break;
        }
      }
    }

    // Add parent directory entries.
    size_t pos = classpath_resource->filename().find('/');
    while (pos != std::string::npos) {
      std::string dir(classpath_resource->filename(), 0, pos + 1);
      if (NewEntry(dir)) {
        WriteDirEntry(dir, nullptr, 0);
      }
      pos = classpath_resource->filename().find('/', pos + 1);
    }

    WriteEntry(classpath_resource->OutputEntry(do_compress));
  }

  // Then copy source files' contents.
  for (size_t ix = 0; ix < options_->input_jars.size(); ++ix) {
    if (!AddJar(ix)) {
      exit(1);
    }
  }

  // All entries written, write Central Directory and close.
  Close();
  return 0;
}

OutputJar::~OutputJar() {
  if (file_) {
    diag_warnx("%s:%d: Close() should be called first", __FILE__, __LINE__);
  }
}

// Try to perform I/O in units of this size.
// (128KB is the default max request size for fuse filesystems.)
static constexpr size_t kBufferSize = 128 << 10;

bool OutputJar::Open() {
  if (file_) {
    diag_errx(1, "%s:%d: Cannot open output archive twice", __FILE__, __LINE__);
  }

  int mode = O_CREAT | O_WRONLY | O_TRUNC;

#ifdef _WIN32
  std::wstring wpath;
  std::string error;
  if (!blaze_util::AsAbsoluteWindowsPath(path(), &wpath, &error)) {
    diag_warn("%s:%d: AsAbsoluteWindowsPath failed: %s", __FILE__, __LINE__,
              error.c_str());
    return false;
  }

  HANDLE hFile =
      CreateFileW(wpath.c_str(), GENERIC_READ | GENERIC_WRITE,
                  // Must share for reading, otherwise
                  // symlink-following file existence checks (e.g.
                  // java.nio.file.Files.exists()) fail.
                  FILE_SHARE_READ, nullptr, CREATE_ALWAYS, 0, nullptr);
  if (hFile == INVALID_HANDLE_VALUE) {
    diag_warn("%s:%d: CreateFileW failed for %S", __FILE__, __LINE__,
              wpath.c_str());
    return false;
  }

  // Make sure output file is in binary mode, or \r\n will be converted to \n.
  mode |= _O_BINARY;
  int fd = _open_osfhandle(reinterpret_cast<intptr_t>(hFile), mode);
#else
  // Set execute bits since we may produce an executable output file.
  int fd = open(path(), mode, 0777);
#endif

  if (fd < 0) {
    diag_warn("%s:%d: %s", __FILE__, __LINE__, path());
    return false;
  }
  file_ = fdopen(fd, "w");
  if (file_ == nullptr) {
    diag_warn("%s:%d: fdopen of %s", __FILE__, __LINE__, path());
    close(fd);
    return false;
  }
  outpos_ = 0;
  buffer_.reset(new char[kBufferSize]);
  setvbuf(file_, buffer_.get(), _IOFBF, kBufferSize);
  if (options_->verbose) {
    fprintf(stderr, "Writing to %s\n", path());
  }
  return true;
}

// January 1, 2010 as a DOS date
static const uint16_t kDefaultDate = 30 << 9 | 1 << 5 | 1;

bool OutputJar::AddJar(int jar_path_index) {
  const std::string &input_jar_path =
      options_->input_jars[jar_path_index].first;
  const std::string &input_jar_aux_label =
      options_->input_jars[jar_path_index].second;

  InputJar input_jar;
  if (!input_jar.Open(input_jar_path)) {
    return false;
  }
  const CDH *jar_entry;
  const LH *lh;
  while ((jar_entry = input_jar.NextEntry(&lh))) {
    const char *file_name = jar_entry->file_name();
    auto file_name_length = jar_entry->file_name_length();
    if (!file_name_length) {
      diag_errx(
          1, "%s:%d: Bad central directory record in %s at offset 0x%" PRIx64,
          __FILE__, __LINE__, input_jar_path.c_str(),
          input_jar.CentralDirectoryRecordOffset(jar_entry));
    }
    // Special files that cannot be handled by looking up known_members_ map:
    // * ignore *.SF, *.RSA, *.DSA
    //   (TODO(asmundak): should this be done only in META-INF?
    //
    if (ends_with(file_name, file_name_length, ".SF") ||
        ends_with(file_name, file_name_length, ".RSA") ||
        ends_with(file_name, file_name_length, ".DSA")) {
      continue;
    }

    // Skip module-info.class files
    // Deploy jars are not modularized jars, and including module-infos from
    // modularized dependencies doesn't work. See also b/204112761.
    if (!options_->no_strip_module_info &&
        (!strncmp(file_name, "module-info.class", file_name_length) ||
         (begins_with(file_name, file_name_length, "META-INF/versions/") &&
          ends_with(file_name, file_name_length, "/module-info.class")))) {
      continue;
    }

    bool include_entry = true;
    if (!options_->include_prefixes.empty()) {
      for (auto &prefix : options_->include_prefixes) {
        if ((include_entry =
                 (prefix.size() <= file_name_length &&
                  0 == strncmp(file_name, prefix.c_str(), prefix.size())))) {
          break;
        }
      }
    }
    if (!include_entry) {
      continue;
    }

    bool is_file = (file_name[file_name_length - 1] != '/');
    if (is_file &&
        begins_with(file_name, file_name_length, "META-INF/services/")) {
      // The contents of the META-INF/services/<SERVICE> on the output is the
      // concatenation of the META-INF/services/<SERVICE> files from all inputs.
      std::string service_path(file_name, file_name_length);
      if (NewEntry(service_path)) {
        // Create a concatenator and add it to the known_members_ map.
        // The call to Merge() below will then take care of the rest.
        Concatenator *service_handler = new Concatenator(service_path);
        service_handlers_.emplace_back(service_handler);
        known_members_.emplace(service_path, EntryInfo{service_handler});
      }
    } else {
      ExtraHandler(input_jar_path, jar_entry, &input_jar_aux_label);
    }

    if (options_->check_desugar_deps &&
        begins_with(file_name, file_name_length, "j$/")) {
      diag_errx(1, "%s:%d: desugar_jdk_libs file %.*s unexpectedly found in %s",
                __FILE__, __LINE__, file_name_length, file_name,
                input_jar_path.c_str());
    }

    // Install a new entry unless it is already present. All the plain (non-dir)
    // entries that require a combiner have been already installed, so the call
    // will add either a directory entry whose handler will ignore subsequent
    // duplicates, or an ordinary plain entry, for which we save the index of
    // the first input jar (in order to provide diagnostics on duplicate).
    auto got =
        known_members_.emplace(std::string(file_name, file_name_length),
                               EntryInfo{is_file ? nullptr : &null_combiner_,
                                         is_file ? jar_path_index : -1});
    if (!got.second) {
      auto &entry_info = got.first->second;
      // Handle special entries (the ones that have a combiner).
      if (entry_info.combiner_ != nullptr) {
        // TODO(kmb,asmundak): Should be checking Merge() return value but fails
        // for build-data.properties when merging deploy jars into deploy jars.
        entry_info.combiner_->Merge(jar_entry, lh);
        continue;
      }

      // Plain file entry. If duplicates are not allowed, bail out. Otherwise
      // just ignore this entry.
      if (options_->no_duplicates ||
          (options_->no_duplicate_classes &&
           ends_with(file_name, file_name_length, ".class"))) {
        diag_errx(
            1, "%s:%d: %.*s is present both in %s and %s", __FILE__, __LINE__,
            file_name_length, file_name,
            options_->input_jars[entry_info.input_jar_index_].first.c_str(),
            input_jar_path.c_str());
      } else {
        duplicate_entries_++;
        continue;
      }
    }

    // Add any missing parent directory entries (first) if requested.
    if (options_->add_missing_directories) {
      // Ignore very last character in case this entry is a directory itself.
      for (size_t pos = 0; pos < static_cast<size_t>(file_name_length - 1);
           ++pos) {
        if (file_name[pos] == '/') {
          std::string dir(file_name, 0, pos + 1);
          if (NewEntry(dir)) {
            WriteDirEntry(dir, nullptr, 0);
          }
        }
      }
    }

    // For the file entries, decide whether output should be compressed.
    if (is_file) {
      bool input_compressed =
          jar_entry->compression_method() != Z_NO_COMPRESSION;
      bool output_compressed =
          options_->force_compression ||
          (options_->preserve_compression && input_compressed);
      if (output_compressed && !options_->nocompress_suffixes.empty()) {
        for (auto &suffix : options_->nocompress_suffixes) {
          if (file_name_length >= suffix.size() &&
              !strncmp(file_name + file_name_length - suffix.size(),
                       suffix.c_str(), suffix.size())) {
            output_compressed = false;
            break;
          }
        }
      }
      if (input_compressed != output_compressed) {
        Concatenator combiner(jar_entry->file_name_string());
        if (!combiner.Merge(jar_entry, lh)) {
          diag_err(1, "%s:%d: cannot add %.*s", __FILE__, __LINE__,
                   jar_entry->file_name_length(), jar_entry->file_name());
        }
        WriteEntry(combiner.OutputEntry(output_compressed));
        continue;
      }
    }

    // Now we have to copy:
    //  local header
    //  file data
    //  data descriptor, if present.
    off64_t copy_from = jar_entry->local_header_offset();
    size_t num_bytes = lh->size();
    if (jar_entry->no_size_in_local_header()) {
      const DDR *ddr = reinterpret_cast<const DDR *>(
          lh->data() + jar_entry->compressed_file_size());
      num_bytes +=
          jar_entry->compressed_file_size() +
          ddr->size(
              ziph::zfield_has_ext64(jar_entry->compressed_file_size32()),
              ziph::zfield_has_ext64(jar_entry->uncompressed_file_size32()));
    } else {
      num_bytes += lh->compressed_file_size();
    }
    off64_t local_header_offset = Position();

    // When normalize_timestamps is set, entry's timestamp is to be set to
    // 01/01/2010 00:00:00 (or to 01/01/2010 00:00:02, if an entry is a .class
    // file). This is somewhat expensive because we have to copy the local
    // header to memory as input jar is memory mapped as read-only. Try to copy
    // as little as possible.
    uint16_t normalized_time = 0;
    const UnixTimeExtraField *lh_field_to_remove = nullptr;
    bool fix_timestamp = false;
    if (options_->normalize_timestamps) {
      if (ends_with(file_name, file_name_length, ".class")) {
        normalized_time = 1;
      }
      lh_field_to_remove = lh->unix_time_extra_field();
      fix_timestamp = jar_entry->last_mod_file_date() != kDefaultDate ||
                      jar_entry->last_mod_file_time() != normalized_time ||
                      lh_field_to_remove != nullptr;
    }
    if (fix_timestamp) {
      uint8_t lh_buffer[512];
      size_t lh_size = lh->size();
      LH *lh_new = lh_size > sizeof(lh_buffer)
                       ? reinterpret_cast<LH *>(malloc(lh_size))
                       : reinterpret_cast<LH *>(lh_buffer);
      // Remove Unix timestamp field.
      if (lh_field_to_remove != nullptr) {
        auto from_end = ziph::byte_ptr(lh) + lh->size();
        size_t removed_size = lh_field_to_remove->size();
        size_t chunk1_size =
            ziph::byte_ptr(lh_field_to_remove) - ziph::byte_ptr(lh);
        size_t chunk2_size = lh->size() - (chunk1_size + removed_size);
        memcpy(lh_new, lh, chunk1_size);
        if (chunk2_size) {
          memcpy(reinterpret_cast<uint8_t *>(lh_new) + chunk1_size,
                 from_end - chunk2_size, chunk2_size);
        }
        lh_new->extra_fields(lh_new->extra_fields(),
                             lh->extra_fields_length() - removed_size);
      } else {
        memcpy(lh_new, lh, lh_size);
      }
      lh_new->last_mod_file_date(kDefaultDate);
      lh_new->last_mod_file_time(normalized_time);
      // Now write these few bytes and adjust read/write positions accordingly.
      if (!WriteBytes(lh_new, lh_new->size())) {
        diag_err(1, "%s:%d: Cannot copy modified local header for %.*s",
                 __FILE__, __LINE__, file_name_length, file_name);
      }
      copy_from += lh_size;
      num_bytes -= lh_size;
      if (reinterpret_cast<uint8_t *>(lh_new) != lh_buffer) {
        free(lh_new);
      }
    }

    // Do the actual copy.
    if (!WriteBytes(input_jar.mapped_start() + copy_from, num_bytes)) {
      diag_err(1, "%s:%d: Cannot write %zu bytes of %.*s from %s", __FILE__,
               __LINE__, num_bytes, file_name_length, file_name,
               input_jar_path.c_str());
    }

    AppendToDirectoryBuffer(jar_entry, local_header_offset, normalized_time,
                            fix_timestamp);
    ++entries_;
  }
  return input_jar.Close();
}

off64_t OutputJar::Position() {
  if (file_ == nullptr) {
    diag_err(1, "%s:%d: output file is not open", __FILE__, __LINE__);
  }
  // You'd think this could be "return ftell(file_);", but that
  // generates a needless call to lseek.  So instead we cache our
  // current position in the output.
  return outpos_;
}

// Writes an entry. The argument is the pointer to the contiguous block of
// memory containing Local Header for the entry, immediately followed by
// the data. The memory is freed after the data has been written.
void OutputJar::WriteEntry(void *buffer) {
  if (buffer == nullptr) {
    return;
  }
  LH *entry = reinterpret_cast<LH *>(buffer);
  if (options_->verbose) {
    fprintf(stderr, "%-.*s combiner has %zu bytes, %s to %zu\n",
            entry->file_name_length(), entry->file_name(),
            entry->uncompressed_file_size(),
            entry->compression_method() == Z_NO_COMPRESSION ? "copied"
                                                            : "compressed",
            entry->compressed_file_size());
  }

  // Set this entry's timestamp.
  // MSDOS file timestamp format that Zip uses is described here:
  // https://msdn.microsoft.com/en-us/library/9kkf9tah.aspx
  // ("32-Bit Windows Time/Date Formats")
  if (options_->normalize_timestamps) {
    // Regular "normalized" timestamp is 01/01/2010 00:00:00, while for the
    // .class file it is 01/01/2010 00:00:02
    entry->last_mod_file_date(kDefaultDate);
    entry->last_mod_file_time(
        ends_with(entry->file_name(), entry->file_name_length(), ".class") ? 1
                                                                           : 0);
  } else {
    struct tm tm;
    // Time has 2-second resolution, so round up:
    time_t t_adjusted = (time(nullptr) + 1) & ~1;
    localtime_r(&t_adjusted, &tm);
    uint16_t dos_date =
        ((tm.tm_year - 80) << 9) | ((tm.tm_mon + 1) << 5) | tm.tm_mday;
    uint16_t dos_time =
        (tm.tm_hour << 11) | (tm.tm_min << 5) | (tm.tm_sec >> 1);
    entry->last_mod_file_time(dos_time);
    entry->last_mod_file_date(dos_date);
  }

  uint8_t *data = reinterpret_cast<uint8_t *>(entry);
  off64_t output_position = Position();
  if (!WriteBytes(data, entry->data() + entry->in_zip_size() - data)) {
    diag_err(1, "%s:%d: write", __FILE__, __LINE__);
  }
  // Data written, allocate CDH space and populate CDH.
  // Space needed for the CDH varies depending on whether output position field
  // fits into 32 bits (we do not handle compressed/uncompressed entry sizes
  // exceeding 32 bits at the moment).
  uint16_t zip64_size = ziph::zfield_needs_ext64(output_position)
                            ? Zip64ExtraField::space_needed(1)
                            : 0;
  CDH *cdh = reinterpret_cast<CDH *>(
      ReserveCdh(sizeof(CDH) + entry->file_name_length() +
                 entry->extra_fields_length() + zip64_size));
  cdh->signature();
  // Note: do not set the version to Unix 3.0 spec, otherwise
  // unzip will think that 'external_attributes' field contains access mode
  cdh->version(20);
  cdh->version_to_extract(20);  // 2.0
  cdh->bit_flag(0x0);
  cdh->compression_method(entry->compression_method());
  cdh->last_mod_file_time(entry->last_mod_file_time());
  cdh->last_mod_file_date(entry->last_mod_file_date());
  cdh->crc32(entry->crc32());
  TODO(entry->compressed_file_size32() != 0xFFFFFFFF, "Handle Zip64");
  cdh->compressed_file_size32(entry->compressed_file_size32());
  TODO(entry->uncompressed_file_size32() != 0xFFFFFFFF, "Handle Zip64");
  cdh->uncompressed_file_size32(entry->uncompressed_file_size32());
  cdh->file_name(entry->file_name(), entry->file_name_length());
  cdh->extra_fields(entry->extra_fields(), entry->extra_fields_length());
  if (zip64_size > 0) {
    Zip64ExtraField *zip64_ef = reinterpret_cast<Zip64ExtraField *>(
        cdh->extra_fields() + cdh->extra_fields_length());
    zip64_ef->signature();
    zip64_ef->attr_count(1);
    zip64_ef->attr64(0, output_position);
    cdh->local_header_offset32(0xFFFFFFFF);
    // Field address argument points to the already existing field,
    // so the call just updates the length.
    cdh->extra_fields(cdh->extra_fields(),
                      cdh->extra_fields_length() + zip64_size);
  } else {
    cdh->local_header_offset32(output_position);
  }
  cdh->comment_length(0);
  cdh->start_disk_nr(0);
  cdh->internal_attributes(0);
  cdh->external_attributes(0);
  ++entries_;
  free(reinterpret_cast<void *>(entry));
}

void OutputJar::WriteMetaInf() {
  std::string path("META-INF/");

  // META_INF/ is always the first entry, and as such it should have an extra
  // field with the tag 0xCAFE and zero bytes of data. This is not the part of
  // the jar file spec, but Unix 'file' utility relies on it to distiguish jar
  // file from zip file. See https://bugs.openjdk.java.net/browse/JDK-6808540
  const uint8_t extra_fields[] = {0xFE, 0xCA, 0, 0};
  const uint16_t n_extra_fields =
      sizeof(extra_fields) / sizeof(extra_fields[0]);
  WriteDirEntry(path, extra_fields, n_extra_fields);
}

// Writes a directory entry with the given name and extra fields.
void OutputJar::WriteDirEntry(const std::string &name,
                              const uint8_t *extra_fields,
                              const uint16_t n_extra_fields) {
  size_t lh_size = sizeof(LH) + name.size() + n_extra_fields;
  LH *lh = reinterpret_cast<LH *>(malloc(lh_size));
  lh->signature();
  lh->version(20);  // 2.0
  lh->bit_flag(0);  // TODO(asmundak): should I set UTF8 flag?
  lh->compression_method(Z_NO_COMPRESSION);
  lh->crc32(0);
  lh->compressed_file_size32(0);
  lh->uncompressed_file_size32(0);
  lh->file_name(name.c_str(), name.size());
  lh->extra_fields(extra_fields, n_extra_fields);
  known_members_.emplace(name, EntryInfo{&null_combiner_});
  WriteEntry(lh);
}

// Create output Central Directory entry for the input jar entry.
void OutputJar::AppendToDirectoryBuffer(const CDH *cdh, off64_t lh_pos,
                                        uint16_t normalized_time,
                                        bool fix_timestamp) {
  // While copying from the input CDH pointed to by 'cdh', we may need to drop
  // Unix timestamp extra field, and we might need to change the number of
  // attributes of the Zip64 extra field, or create it, or destroy it if entry's
  // position relative to 4G boundary changes.
  // The rest of the input CDH is copied.

  // 1. Decide if we need to drop UnixTime.
  size_t removed_unix_time_field_size = 0;
  if (fix_timestamp) {
    auto unix_time_field = cdh->unix_time_extra_field();
    if (unix_time_field != nullptr) {
      removed_unix_time_field_size = unix_time_field->size();
    }
  }

  // 2. Figure out how many attributes input entry has and how many
  // the output entry is going to have.
  const Zip64ExtraField *zip64_ef = cdh->zip64_extra_field();
  const int zip64_attr_count = zip64_ef == nullptr ? 0 : zip64_ef->attr_count();
  const bool lh_pos_needs64 = ziph::zfield_needs_ext64(lh_pos);
  int out_zip64_attr_count;
  if (zip64_attr_count > 0) {
    out_zip64_attr_count = zip64_attr_count;
    // The number of attributes may remain the same, or it may increase or
    // decrease by 1, depending on local_header_offset value.
    if (ziph::zfield_has_ext64(cdh->local_header_offset32()) !=
        lh_pos_needs64) {
      if (lh_pos_needs64) {
        out_zip64_attr_count += 1;
      } else {
        out_zip64_attr_count -= 1;
      }
    }
  } else {
    out_zip64_attr_count = lh_pos_needs64 ? 1 : 0;
  }
  const uint16_t zip64_size = Zip64ExtraField::space_needed(zip64_attr_count);
  const uint16_t out_zip64_size =
      Zip64ExtraField::space_needed(out_zip64_attr_count);

  // Allocate output CDH and copy everything but extra fields.
  const uint16_t ef_size = cdh->extra_fields_length();
  const uint16_t out_ef_size =
      (ef_size + out_zip64_size) - (removed_unix_time_field_size + zip64_size);

  const size_t out_cdh_size = cdh->size() + out_ef_size - ef_size;
  CDH *out_cdh = reinterpret_cast<CDH *>(ReserveCdr(out_cdh_size));

  // Calculate ExtraFields boundaries in the input and output entries.
  auto ef_begin = reinterpret_cast<const ExtraField *>(cdh->extra_fields());
  auto ef_end =
      reinterpret_cast<const ExtraField *>(ziph::byte_ptr(ef_begin) + ef_size);
  // Copy [cdh..ef_begin) -> [out_cdh..out_ef_begin)
  memcpy(out_cdh, cdh, ziph::byte_ptr(ef_begin) - ziph::byte_ptr(cdh));

  auto out_ef_begin = reinterpret_cast<ExtraField *>(
      const_cast<uint8_t *>(out_cdh->extra_fields()));
  auto out_ef_end = reinterpret_cast<ExtraField *>(
      reinterpret_cast<uint8_t *>(out_ef_begin) + out_ef_size);

  // Copy [ef_end..cdh_end) -> [out_ef_end..out_cdh_end)
  memcpy(out_ef_end, ef_end,
         ziph::byte_ptr(cdh) + cdh->size() - ziph::byte_ptr(ef_end));

  // Copy extra fields, dropping Zip64 and possibly UnixTime fields.
  ExtraField *out_ef = out_ef_begin;
  for (const ExtraField *ef = ef_begin; ef < ef_end; ef = ef->next()) {
    if ((fix_timestamp && ef->is_unix_time()) || ef->is_zip64()) {
      // Skip this one.
    } else {
      memcpy(out_ef, ef, ef->size());
      out_ef = reinterpret_cast<ExtraField *>(
          reinterpret_cast<uint8_t *>(out_ef) + ef->size());
    }
  }

  // Set up Zip64 extra field if necessary.
  if (out_zip64_size > 0) {
    Zip64ExtraField *out_zip64_ef = reinterpret_cast<Zip64ExtraField *>(out_ef);
    out_zip64_ef->signature();
    out_zip64_ef->attr_count(out_zip64_attr_count);
    int copy_count = out_zip64_attr_count < zip64_attr_count
                         ? out_zip64_attr_count
                         : zip64_attr_count;
    if (copy_count > 0) {
      out_zip64_ef->attr64(0, zip64_ef->attr64(0));
      if (copy_count > 1) {
        out_zip64_ef->attr64(1, zip64_ef->attr64(1));
      }
    }
    // Set 64-bit local_header_offset if necessary. It's always the last
    // attribute.
    if (lh_pos_needs64) {
      out_zip64_ef->attr64(out_zip64_attr_count - 1, lh_pos);
    }
  }
  out_cdh->extra_fields(ziph::byte_ptr(out_ef_begin), out_ef_size);
  out_cdh->local_header_offset32(lh_pos_needs64 ? 0xFFFFFFFF : lh_pos);
  if (fix_timestamp) {
    out_cdh->last_mod_file_time(normalized_time);
    out_cdh->last_mod_file_date(kDefaultDate);
  }
}

uint8_t *OutputJar::ReserveCdr(size_t chunk_size) {
  if (cen_size_ + chunk_size > cen_capacity_) {
    cen_capacity_ += 1000000;
    cen_ = reinterpret_cast<uint8_t *>(realloc(cen_, cen_capacity_));
    if (!cen_) {
      diag_errx(1, "%s:%d: Cannot allocate %zu bytes for the directory",
                __FILE__, __LINE__, cen_capacity_);
    }
  }
  uint8_t *entry = cen_ + cen_size_;
  cen_size_ += chunk_size;
  return entry;
}

uint8_t *OutputJar::ReserveCdh(size_t size) {
  return static_cast<uint8_t *>(memset(ReserveCdr(size), 0, size));
}

// Write out combined jar.
bool OutputJar::Close() {
  if (file_ == nullptr) {
    return true;
  }

  for (auto &service_handler : service_handlers_) {
    WriteEntry(service_handler->OutputEntry(options_->force_compression));
  }
  for (auto &extra_combiner : extra_combiners_) {
    WriteEntry(extra_combiner->OutputEntry(options_->force_compression));
  }
  WriteEntry(spring_handlers_.OutputEntry(options_->force_compression));
  WriteEntry(spring_schemas_.OutputEntry(options_->force_compression));
  WriteEntry(protobuf_meta_handler_.OutputEntry(options_->force_compression));
  // TODO(asmundak): handle manifest;
  off64_t output_position = Position();
  bool write_zip64_ecd = output_position >= 0xFFFFFFFF || entries_ >= 0xFFFF ||
                         cen_size_ >= 0xFFFFFFFF;

  size_t cen_size = cen_size_;  // Save it before ReserveCdh updates it.
  if (write_zip64_ecd) {
    {
      ECD64 *ecd64 = reinterpret_cast<ECD64 *>(ReserveCdh(sizeof(ECD64)));
      ecd64->signature();
      ecd64->remaining_size(sizeof(ECD64) - 12);
      ecd64->version(0x031E);         // Unix, version 3.0
      ecd64->version_to_extract(45);  // 4.5 (Zip64 support)
      ecd64->this_disk_entries(entries_);
      ecd64->total_entries(entries_);
      ecd64->cen_size(cen_size);
      ecd64->cen_offset(output_position);
    }
    {
      ECD64Locator *ecd64_locator =
          reinterpret_cast<ECD64Locator *>(ReserveCdh(sizeof(ECD64Locator)));
      ecd64_locator->signature();
      ecd64_locator->ecd64_offset(output_position + cen_size);
      ecd64_locator->total_disks(1);
    }
    {
      ECD *ecd = reinterpret_cast<ECD *>(ReserveCdh(sizeof(ECD)));
      ecd->signature();
      ecd->this_disk_entries16(0xFFFF);
      ecd->total_entries16(0xFFFF);
      ecd->cen_size32(0xFFFFFFFF);
      ecd->cen_offset32(0xFFFFFFFF);
    }
  } else {
    ECD *ecd = reinterpret_cast<ECD *>(ReserveCdh(sizeof(ECD)));
    ecd->signature();
    ecd->this_disk_entries16((uint16_t)entries_);
    ecd->total_entries16((uint16_t)entries_);
    ecd->cen_size32(cen_size);
    ecd->cen_offset32(output_position);
  }

  // Save Central Directory and wrap up.
  if (!WriteBytes(cen_, cen_size_)) {
    diag_err(1, "%s:%d: Cannot write central directory", __FILE__, __LINE__);
  }
  free(cen_);

  if (fclose(file_)) {
    diag_err(1, "%s:%d: %s", __FILE__, __LINE__, path());
  }
  file_ = nullptr;
  // Free the buffer only after fclose(); stdio may flush data from the
  // buffer on close.
  buffer_.reset();

  if (options_->verbose) {
    fprintf(stderr, "Wrote %s with %d entries", path(), entries_);
    if (duplicate_entries_) {
      fprintf(stderr, ", skipped %d entries", duplicate_entries_);
    }
    fprintf(stderr, "\n");
  }
  return true;
}

bool IsDir(const std::string &path) {
  struct stat st;
  if (stat(path.c_str(), &st)) {
    diag_warn("%s:%d: stat %s:", __FILE__, __LINE__, path.c_str());
    return false;
  }
  return (st.st_mode & S_IFDIR) == S_IFDIR;
}

void OutputJar::ClasspathResource(const std::string &resource_name,
                                  const std::string &resource_path) {
  if (known_members_.count(resource_name)) {
    if (options_->warn_duplicate_resources) {
      diag_warnx(
          "%s:%d: Duplicate resource name %s in the --classpath_resource or "
          "--resource option",
          __FILE__, __LINE__, resource_name.c_str());
      // TODO(asmundak): this mimics old behaviour. Confirm that unless
      // we run with --warn_duplicate_resources, the output zip file contains
      // the concatenated contents of the all the resources with the same name.
      return;
    }
  }
  MappedFile mapped_file;
  if (mapped_file.Open(resource_path)) {
    Concatenator *classpath_resource = new Concatenator(resource_name);
    classpath_resource->Append(
        reinterpret_cast<const char *>(mapped_file.start()),
        mapped_file.size());
    classpath_resources_.emplace_back(classpath_resource);
    known_members_.emplace(resource_name, EntryInfo{classpath_resource});
  } else if (IsDir(resource_path)) {
    // add an empty entry for the directory so its path ends up in the
    // manifest
    classpath_resources_.emplace_back(new Concatenator(resource_name + "/"));
    known_members_.emplace(resource_name, EntryInfo{&null_combiner_});
  } else {
    diag_err(1, "%s:%d: %s", __FILE__, __LINE__, resource_path.c_str());
  }
}

ssize_t OutputJar::CopyAppendData(int in_fd, off64_t offset, size_t count) {
  if (count == 0) {
    return 0;
  }
  std::unique_ptr<void, decltype(free) *> buffer(malloc(kBufferSize), free);
  if (buffer == nullptr) {
    diag_err(1, "%s:%d: malloc", __FILE__, __LINE__);
  }
  ssize_t total_written = 0;

#ifdef _WIN32
  HANDLE hFile = reinterpret_cast<HANDLE>(_get_osfhandle(in_fd));
  while (static_cast<size_t>(total_written) < count) {
    ssize_t len = std::min(kBufferSize, count - total_written);
    DWORD n_read;
    if (!::ReadFile(hFile, buffer.get(), len, &n_read, nullptr)) {
      return -1;
    }
    if (n_read == 0) {
      break;
    }
    if (!WriteBytes(buffer.get(), n_read)) {
      return -1;
    }
    total_written += n_read;
  }
#else
  while (static_cast<size_t>(total_written) < count) {
    size_t len = std::min(kBufferSize, count - total_written);
    ssize_t n_read = pread(in_fd, buffer.get(), len, offset + total_written);
    if (n_read > 0) {
      if (!WriteBytes(buffer.get(), n_read)) {
        return -1;
      }
      total_written += n_read;
    } else if (n_read == 0) {
      break;
    } else {
      return -1;
    }
  }
#endif  // _WIN32

  return total_written;
}

size_t OutputJar::AppendFile(Options *options, const char *const file_path) {
  int in_fd = open(file_path, O_RDONLY);
  struct stat statbuf;
  if (fstat(in_fd, &statbuf)) {
    diag_err(1, "%s", file_path);
  }
  // TODO(asmundak):  Consider going back to sendfile() or reflink
  // (BTRFS_IOC_CLONE/XFS_IOC_CLONE) here.  The launcher preamble can
  // be very large for targets with many native deps.
  ssize_t byte_count = CopyAppendData(in_fd, 0, statbuf.st_size);
  if (byte_count < 0) {
    diag_err(1, "%s:%d: Cannot copy %s to %s", __FILE__, __LINE__,
             file_path, options->output_jar.c_str());
  } else if (byte_count != statbuf.st_size) {
    diag_err(1, "%s:%d: Copied only %zu bytes out of %" PRIu64 " from %s",
             __FILE__, __LINE__, byte_count, statbuf.st_size, file_path);
  }
  close(in_fd);
  if (options->verbose) {
    fprintf(stderr, "Prepended %s (%" PRIu64 " bytes)\n", file_path,
            statbuf.st_size);
  }
  return statbuf.st_size;
}

off64_t OutputJar::PageAlignedAppendFile(const std::string &file_path,
                                         size_t *file_size) {
  // Align the file start offset at page boundary.
  off64_t cur_offset = Position();
  size_t pagesize;
#ifdef _WIN32
  SYSTEM_INFO si;
  GetSystemInfo(&si);
  pagesize = si.dwPageSize;
#else
  pagesize = sysconf(_SC_PAGESIZE);
#endif
  off64_t aligned_offset = (cur_offset + (pagesize - 1)) & ~(pagesize - 1);
  size_t gap = aligned_offset - cur_offset;
  size_t written;
  if (gap > 0) {
    char *zeros = (char *)malloc(gap);
    if (zeros == nullptr) {
      diag_err(1, "%s:%d: malloc", __FILE__, __LINE__);
    }
    memset(zeros, 0, gap);
    written = fwrite(zeros, 1, gap, file_);
    outpos_ += written;
    free(zeros);
  }

  // Copy file
  *file_size = AppendFile(options_, file_path.c_str());

  return aligned_offset;
}

void OutputJar::AppendPageAlignedFile(
    const std::string &file,
    const std::string &offset_manifest_attr_name,
    const std::string &size_manifest_attr_name,
    const std::string &property_name) {
  // Align the shared archive start offset at page alignment, which is
  // required by mmap.
  size_t file_size;
  off64_t aligned_offset = OutputJar::PageAlignedAppendFile(file, &file_size);

  // Write the start offset of the copied content as a manifest attribute.
  char offset_manifest_attr[50];
  snprintf(offset_manifest_attr, sizeof(offset_manifest_attr),
    "%s: %ld", offset_manifest_attr_name.c_str(),
    (long)aligned_offset); // NOLINT(runtime/int,
                           // google-runtime-int)
  manifest_.AppendLine(offset_manifest_attr);

  // Write the size of the copied content as a manifest attribute if the
  // size_manifest_attr_name is not NULL.
  if (!size_manifest_attr_name.empty()) {
    char size_manifest_attr[50];
    snprintf(size_manifest_attr, sizeof(size_manifest_attr),
      "%s: %ld", size_manifest_attr_name.c_str(),
      (long)file_size); // NOLINT(runtime/int,
                        // google-runtime-int)
    manifest_.AppendLine(size_manifest_attr);
  }

  if (!property_name.empty()) {
    // Add to build_properties.
    build_properties_.AddProperty(property_name.c_str(), file.c_str());
  }
}

void OutputJar::ExtraCombiner(const std::string &entry_name,
                              Combiner *combiner) {
  extra_combiners_.emplace_back(combiner);
  known_members_.emplace(entry_name, EntryInfo{combiner});
}

bool OutputJar::WriteBytes(const void *buffer, size_t count) {
  size_t written = fwrite(buffer, 1, count, file_);
  outpos_ += written;
  return written == count;
}

void OutputJar::ExtraHandler(const std::string &input_jar_path, const CDH *,
                             const std::string *) {}
