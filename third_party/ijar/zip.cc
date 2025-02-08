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
// zip.cc -- .zip (.jar) file reading/writing routines.
//

// See README.txt for details.
//
// See http://www.pkware.com/documents/casestudies/APPNOTE.TXT
// for definition of PKZIP file format.

#define _FILE_OFFSET_BITS 64  // Support zip files larger than 2GB

#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdint.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>
#include <limits>
#include <vector>

#include "third_party/ijar/mapped_file.h"
#include "third_party/ijar/platform_utils.h"
#include "third_party/ijar/zip.h"
#include "third_party/ijar/zlib_client.h"

#define LOCAL_FILE_HEADER_SIGNATURE   0x04034b50
#define CENTRAL_FILE_HEADER_SIGNATURE 0x02014b50
#define UNIX_ZIP_FILE_VERSION 0x0300
#define DIGITAL_SIGNATURE             0x05054b50
#define ZIP64_EOCD_SIGNATURE          0x06064b50
#define ZIP64_EOCD_LOCATOR_SIGNATURE  0x07064b50
#define EOCD_SIGNATURE                0x06054b50
#define DATA_DESCRIPTOR_SIGNATURE     0x08074b50
#define ZIP64_EXTRA_FIELD_TAG 0x0001

#define U2_MAX 0xffff
#define U4_MAX 0xffffffffUL

#define ZIP64_EOCD_LOCATOR_SIZE 20
// zip64 eocd is fixed size in the absence of a zip64 extensible data sector
#define ZIP64_EOCD_FIXED_SIZE 56

// version to extract: 1.0 - default value from APPNOTE.TXT.
// Output JAR files contain no extra ZIP features, so this is enough.
#define ZIP_VERSION_TO_EXTRACT                10
#define COMPRESSION_METHOD_STORED             0   // no compression
#define COMPRESSION_METHOD_DEFLATED           8

#define GENERAL_PURPOSE_BIT_FLAG_COMPRESSED (1 << 3)
#define GENERAL_PURPOSE_BIT_FLAG_UTF8_ENCODED (1 << 11)
#define GENERAL_PURPOSE_BIT_FLAG_COMPRESSION_SPEED ((1 << 2) | (1 << 1))
#define GENERAL_PURPOSE_BIT_FLAG_SUPPORTED \
  (GENERAL_PURPOSE_BIT_FLAG_COMPRESSED \
  | GENERAL_PURPOSE_BIT_FLAG_UTF8_ENCODED \
  | GENERAL_PURPOSE_BIT_FLAG_COMPRESSION_SPEED)

namespace devtools_ijar {
// In the absence of ZIP64 support, zip files are limited to 4GB.
// http://www.info-zip.org/FAQ.html#limits
static const size_t kMaximumOutputSize = std::numeric_limits<uint32_t>::max();

static const u4 kDefaultTimestamp =
    30 << 25 | 1 << 21 | 1 << 16;  // January 1, 2010 in DOS time

//
// A class representing a ZipFile for reading. Its public API is exposed
// using the ZipExtractor abstract class.
//
class InputZipFile : public ZipExtractor {
 public:
  InputZipFile(ZipExtractorProcessor *processor, const char* filename);
  virtual ~InputZipFile();

  virtual const char* GetError() {
    if (errmsg[0] == 0) {
      return NULL;
    }
    return errmsg;
  }

  bool Open();
  virtual bool ProcessNext();
  virtual void Reset();
  virtual size_t GetSize() {
    return input_file_->Length();
  }

  virtual u8 CalculateOutputLength();

  virtual bool ProcessCentralDirEntry(const u1 *&p, u8 *compressed_size,
                                      u8 *uncompressed_size, char *filename,
                                      size_t filename_size, u4 *attr,
                                      u8 *offset);

 private:
  ZipExtractorProcessor *processor;
  const char* filename_;
  MappedInputFile *input_file_;

  // InputZipFile is responsible for maintaining the following
  // pointers. They are allocated by the Create() method before
  // the object is actually created using mmap.
  const u1 * zipdata_in_;   // start of input file mmap
  size_t bytes_unmapped_;         // bytes that have already been unmapped
  const u1 * central_dir_;  // central directory in input file

  size_t in_offset_;  // offset  the input file

  const u1 *p;  // input cursor

  const u1* central_dir_current_;  // central dir input cursor

  // Buffer size is initially INITIAL_BUFFER_SIZE. It doubles in size every
  // time it is found too small, until it reaches MAX_BUFFER_SIZE. If that is
  // not enough, we bail out. We only decompress class files, so they should
  // be smaller than 64K anyway, but we give a little leeway.
  // MAX_BUFFER_SIZE must be bigger than the size of the biggest file in the
  // ZIP. It is set to 2GB here because no one has audited the code for 64-bit
  // cleanliness.
  static constexpr size_t INITIAL_BUFFER_SIZE = 256 * 1024;  // 256K
  static constexpr size_t MAX_BUFFER_SIZE = std::numeric_limits<int32_t>::max();
  static constexpr size_t MAX_MAPPED_REGION = 32 * 1024 * 1024;

  // These metadata fields are the fields of the ZIP header of the file being
  // processed.
  u2 extract_version_;
  u2 general_purpose_bit_flag_;
  u2 compression_method_;
  u8 uncompressed_size_;
  u8 compressed_size_;
  u2 file_name_length_;
  u2 extra_field_length_;
  const u1 *file_name_;
  const u1 *extra_field_;

  // Copy of the last filename entry - Null-terminated.
  char filename[PATH_MAX];
  // The external file attribute field
  u4 attr;

  // last error
  char errmsg[4*PATH_MAX];

  Decompressor *decompressor_;

  int error(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(errmsg, 4*PATH_MAX, fmt, ap);
    va_end(ap);
    return -1;
  }

  // Check that at least n bytes remain in the input file, otherwise
  // abort with an error message.  "state" is the name of the field
  // we're about to read, for diagnostics.
  int EnsureRemaining(size_t n, const char *state) {
    size_t in_offset = p - zipdata_in_;
    size_t remaining = input_file_->Length() - in_offset;
    if (n > remaining) {
      return error("Premature end of file (at offset %zd, state=%s); "
                   "expected %zd more bytes but found %zd.\n",
                   in_offset, state, n, remaining);
    }
    return 0;
  }

  // Read one entry from input zip file
  int ProcessLocalFileEntry(size_t compressed_size, size_t uncompressed_size);

  // Uncompress a file from the archive using zlib. The pointer returned
  // is owned by InputZipFile, so it must not be freed. Advances the input
  // cursor to the first byte after the compressed data.
  u1* UncompressFile();

  // Skip a file
  int SkipFile(bool compressed);

  // Process a file
  int ProcessFile(bool compressed);
};

//
// A class implementing ZipBuilder that represent an open zip file for writing.
//
class OutputZipFile : public ZipBuilder {
 public:
  OutputZipFile(const char *filename, size_t estimated_size)
      : output_file_(NULL),
        filename_(filename),
        estimated_size_(estimated_size),
        finished_(false) {
    errmsg[0] = 0;
  }

  virtual const char* GetError() {
    if (errmsg[0] == 0) {
      return NULL;
    }
    return errmsg;
  }

  virtual ~OutputZipFile() { Finish(); }
  virtual u1 *NewFile(const char *filename, u4 attr);
  virtual int FinishFile(size_t filelength, bool compress = false,
                         bool compute_crc = false);
  virtual int WriteEmptyFile(const char *filename);
  virtual size_t GetSize() {
    return Offset(q);
  }
  virtual int GetNumberFiles() {
    return entries_.size();
  }
  virtual int Finish();
  bool Open();

 private:
  struct LocalFileEntry {
    // Start of the local header (in the output buffer).
    size_t local_header_offset;

    // Sizes of the file entry
    size_t uncompressed_length;
    size_t compressed_length;

    // Compression method
    u2 compression_method;

    // CRC32
    u4 crc32;

    // external attributes field
    u4 external_attr;

    // Start/length of the file_name in the local header.
    u1 *file_name;
    u2 file_name_length;

    // Start/length of the extra_field in the local header.
    const u1 *extra_field;
    u2 extra_field_length;
  };

  MappedOutputFile* output_file_;
  const char* filename_;
  size_t estimated_size_;
  bool finished_;

  // OutputZipFile is responsible for maintaining the following
  // pointers. They are allocated by the Create() method before
  // the object is actually created using mmap.
  u1 *zipdata_out_;        // start of output file mmap
  u1 *q;  // output cursor

  u1 *header_ptr;  // Current pointer to "compression method" entry.

  // List of entries to write the central directory
  std::vector<LocalFileEntry*> entries_;

  // last error
  char errmsg[4*PATH_MAX];

  int error(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(errmsg, 4*PATH_MAX, fmt, ap);
    va_end(ap);
    return -1;
  }

  // Write the ZIP central directory structure for each local file
  // entry in "entries".
  void WriteCentralDirectory();

  // Returns the offset of the pointer relative to the start of the
  // output zip file.
  size_t Offset(const u1 *const x) {
    return x - zipdata_out_;
  }

  // Write ZIP file header in the output. Since the compressed size is not
  // known in advance, it must be recorded later. This method returns a pointer
  // to "compressed size" in the file header that should be passed to
  // WriteFileSizeInLocalFileHeader() later.
  u1 *WriteLocalFileHeader(const char *filename, u4 attr);

  // Fill in the "compressed size" and "uncompressed size" fields in a local
  // file header previously written by WriteLocalFileHeader().
  size_t WriteFileSizeInLocalFileHeader(u1 *header_ptr, size_t out_length,
                                        bool compress = false, u4 crc = 0);
};

//
// Implementation of InputZipFile
//
bool InputZipFile::ProcessNext() {
  // Process the next entry in the central directory. Also make sure that the
  // content pointer is in sync.
  u8 compressed, uncompressed;
  u8 offset;
  if (!ProcessCentralDirEntry(central_dir_current_, &compressed, &uncompressed,
                              filename, PATH_MAX, &attr, &offset)) {
    return false;
  }

  // There might be an offset specified in the central directory that does
  // not match the file offset, so always update our pointer.
  p = zipdata_in_ + in_offset_ + offset;

  if (EnsureRemaining(4, "signature") < 0) {
    return false;
  }
  u4 signature = get_u4le(p);
  if (signature == LOCAL_FILE_HEADER_SIGNATURE) {
    if (ProcessLocalFileEntry(compressed, uncompressed) < 0) {
      return false;
    }
  } else {
    error("local file header signature for file %s not found\n", filename);
    return false;
  }

  return true;
}

int InputZipFile::ProcessLocalFileEntry(
    size_t compressed_size, size_t uncompressed_size) {
  if (EnsureRemaining(26, "extract_version") < 0) {
    return -1;
  }
  extract_version_ = get_u2le(p);
  general_purpose_bit_flag_ = get_u2le(p);

  if ((general_purpose_bit_flag_ & ~GENERAL_PURPOSE_BIT_FLAG_SUPPORTED) != 0) {
    return error("Unsupported value (0x%04x) in general purpose bit flag.\n",
                 general_purpose_bit_flag_);
  }

  compression_method_ = get_u2le(p);

  if (compression_method_ != COMPRESSION_METHOD_DEFLATED &&
      compression_method_ != COMPRESSION_METHOD_STORED) {
    return error("Unsupported compression method (%d).\n",
                 compression_method_);
  }

  // skip over: last_mod_file_time, last_mod_file_date, crc32
  p += 2 + 2 + 4;
  compressed_size_ = get_u4le(p);
  uncompressed_size_ = get_u4le(p);
  file_name_length_ = get_u2le(p);
  extra_field_length_ = get_u2le(p);

  if (EnsureRemaining(file_name_length_, "file_name") < 0) {
    return -1;
  }
  file_name_ = p;
  p += file_name_length_;

  if (EnsureRemaining(extra_field_length_, "extra_field") < 0) {
    return -1;
  }
  extra_field_ = p;
  p += extra_field_length_;

  bool is_compressed = compression_method_ == COMPRESSION_METHOD_DEFLATED;

  // If the zip is compressed, compressed and uncompressed size members are
  // zero in the local file header. If not, check that they are the same as the
  // lengths from the central directory, otherwise, just believe the central
  // directory
  if (compressed_size_ == 0 || compressed_size_ == U4_MAX) {
    compressed_size_ = compressed_size;
  } else {
    if (compressed_size_ != compressed_size) {
      return error("central directory and file header inconsistent\n");
    }
  }

  if (uncompressed_size_ == 0 || uncompressed_size_ == U4_MAX) {
    uncompressed_size_ = uncompressed_size;
  } else {
    if (uncompressed_size_ != uncompressed_size) {
      return error("central directory and file header inconsistent\n");
    }
  }

  if (processor->Accept(filename, attr)) {
    if (ProcessFile(is_compressed) < 0) {
      return -1;
    }
  } else {
    if (SkipFile(is_compressed) < 0) {
      return -1;
    }
  }

  if (general_purpose_bit_flag_ & GENERAL_PURPOSE_BIT_FLAG_COMPRESSED) {
    // Skip the data descriptor. Some implementations do not put the signature
    // here, so check if the next 4 bytes are a signature, and if so, skip the
    // next 12 bytes (for CRC, compressed/uncompressed size), otherwise skip
    // the next 8 bytes (because the value just read was the CRC).
    u4 signature = get_u4le(p);
    if (signature == DATA_DESCRIPTOR_SIGNATURE) {
      p += 4 * 3;
    } else {
      p += 4 * 2;
    }
  }

  size_t bytes_processed = p - zipdata_in_;
  if (bytes_processed > bytes_unmapped_ + MAX_MAPPED_REGION) {
    input_file_->Discard(MAX_MAPPED_REGION);
    bytes_unmapped_ += MAX_MAPPED_REGION;
  }

  return 0;
}

int InputZipFile::SkipFile(const bool compressed) {
  if (!compressed) {
    // In this case, compressed_size_ == uncompressed_size_ (since the file is
    // uncompressed), so we can use either.
    if (compressed_size_ != uncompressed_size_) {
      return error("compressed size != uncompressed size, although the file "
                   "is uncompressed.\n");
    }
  }

  if (EnsureRemaining(compressed_size_, "file_data") < 0) {
    return -1;
  }
  p += compressed_size_;
  return 0;
}

u1* InputZipFile::UncompressFile() {
  size_t in_offset = p - zipdata_in_;
  size_t remaining = input_file_->Length() - in_offset;
  DecompressedFile *decompressed_file =
      decompressor_->UncompressFile(p, remaining);
  if (decompressed_file == NULL) {
    if (decompressor_->GetError() != NULL) {
      error(decompressor_->GetError());
    }
    return NULL;
  } else {
    compressed_size_ = decompressed_file->compressed_size;
    uncompressed_size_ = decompressed_file->uncompressed_size;
    u1 *uncompressed_data = decompressed_file->uncompressed_data;
    free(decompressed_file);
    p += compressed_size_;
    return uncompressed_data;
  }
}

int InputZipFile::ProcessFile(const bool compressed) {
  const u1 *file_data;
  if (compressed) {
    file_data = UncompressFile();
    if (file_data == NULL) {
      return -1;
    }
  } else {
    // In this case, compressed_size_ == uncompressed_size_ (since the file is
    // uncompressed), so we can use either.
    if (compressed_size_ != uncompressed_size_) {
      return error("compressed size != uncompressed size, although the file "
                   "is uncompressed.\n");
    }

    if (EnsureRemaining(compressed_size_, "file_data") < 0) {
      return -1;
    }
    file_data = p;
    p += compressed_size_;
  }
  processor->Process(filename, attr, file_data, uncompressed_size_);
  return 0;
}


// Reads and returns some metadata of the next file from the central directory:
// - compressed size
// - uncompressed size
// - whether the entry is a class file (to be included in the output).
// Precondition: p points to the beginning of an entry in the central dir
// Postcondition: p points to the beginning of the next entry in the central dir
// Returns true if the central directory contains another file and false if not.
// Of course, in the latter case, the size output variables are not changed.
// Note that the central directory is always followed by another data structure
// that has a signature, so parsing it this way is safe.
bool InputZipFile::ProcessCentralDirEntry(const u1 *&p, u8 *compressed_size,
                                          u8 *uncompressed_size, char *filename,
                                          size_t filename_size, u4 *attr,
                                          u8 *offset) {
  u4 signature = get_u4le(p);

  if (signature != CENTRAL_FILE_HEADER_SIGNATURE) {
    if (signature != DIGITAL_SIGNATURE && signature != EOCD_SIGNATURE &&
        signature != ZIP64_EOCD_SIGNATURE) {
      error("invalid central file header signature: 0x%x\n", signature);
    }
    return false;
  }

  p += 16;  // skip to 'compressed size' field
  *compressed_size = get_u4le(p);
  *uncompressed_size = get_u4le(p);
  u2 file_name_length = get_u2le(p);
  u2 extra_field_length = get_u2le(p);
  u2 file_comment_length = get_u2le(p);
  p += 4;  // skip to external file attributes field
  *attr = get_u4le(p);
  *offset = get_u4le(p);
  {
    size_t len = (file_name_length < filename_size)
      ? file_name_length
      : (filename_size - 1);
    memcpy(reinterpret_cast<void*>(filename), p, len);
    filename[len] = 0;
  }
  p += file_name_length;
  const u1 *extra_p = p;
  p += extra_field_length;
  while (extra_p != p) {
    const u2 header_id = get_u2le(extra_p);
    const u2 data_size = get_u2le(extra_p);
    const u1 *extra = extra_p;
    extra_p += data_size;
    if (header_id == ZIP64_EXTRA_FIELD_TAG) {
      if (*uncompressed_size == U4_MAX) {
        *uncompressed_size = get_u8le(extra);
      }
      if (*compressed_size == U4_MAX) {
        *compressed_size = get_u8le(extra);
      }
      if (*offset == U4_MAX) {
        *offset = get_u8le(extra);
      }
    }
  }
  p += file_comment_length;
  return true;
}

// Gives a maximum bound on the size of the interface JAR. Basically, adds
// the difference between the compressed and uncompressed sizes to the size
// of the input file.
u8 InputZipFile::CalculateOutputLength() {
  const u1* current = central_dir_;

  u8 compressed_size = 0;
  u8 uncompressed_size = 0;
  u8 skipped_compressed_size = 0;
  u4 attr;
  u8 offset;
  char filename[PATH_MAX];

  while (true) {
    u8 file_compressed, file_uncompressed;
    if (!ProcessCentralDirEntry(current,
                                &file_compressed, &file_uncompressed,
                                filename, PATH_MAX, &attr, &offset)) {
      break;
    }

    if (processor->Accept(filename, attr)) {
      compressed_size += (u8) file_compressed;
      uncompressed_size += (u8) file_uncompressed;
    } else {
      skipped_compressed_size += file_compressed;
    }
  }

  // The worst case is when the output is simply the input uncompressed. The
  // metadata in the zip file will stay the same, so the file will grow by the
  // difference between the compressed and uncompressed sizes.
  return (u8) input_file_->Length() - skipped_compressed_size
      + (uncompressed_size - compressed_size);
}

// An end of central directory record, sized for optional zip64 contents.
struct EndOfCentralDirectoryRecord {
  u4 number_of_this_disk;
  u4 disk_with_central_dir;
  u8 central_dir_entries_on_this_disk;
  u8 central_dir_entries;
  u8 central_dir_size;
  u8 central_dir_offset;
};

// Checks for a zip64 end of central directory record. If a valid zip64 EOCD is
// found, updates the original EOCD record and returns true.
bool MaybeReadZip64CentralDirectory(const u1 *bytes, size_t /*in_length*/,
                                    const u1 *current,
                                    const u1 **end_of_central_dir,
                                    EndOfCentralDirectoryRecord *cd) {
  if (current < bytes) {
    return false;
  }
  const u1 *candidate = current;
  u4 zip64_directory_signature = get_u4le(current);
  if (zip64_directory_signature != ZIP64_EOCD_SIGNATURE) {
    return false;
  }

  // size of zip64 end of central directory record
  // (fixed size unless there's a zip64 extensible data sector, which
  // we don't need to read)
  get_u8le(current);
  get_u2be(current);  // version made by
  get_u2be(current);  // version needed to extract

  u4 number_of_this_disk = get_u4be(current);
  u4 disk_with_central_dir = get_u4le(current);
  u8 central_dir_entries_on_this_disk = get_u8le(current);
  u8 central_dir_entries = get_u8le(current);
  u8 central_dir_size = get_u8le(current);
  u8 central_dir_offset = get_u8le(current);

  // check for a zip64 EOCD that matches the regular EOCD
  if (number_of_this_disk != cd->number_of_this_disk &&
      cd->number_of_this_disk != U2_MAX) {
    return false;
  }
  if (disk_with_central_dir != cd->disk_with_central_dir &&
      cd->disk_with_central_dir != U2_MAX) {
    return false;
  }
  if (central_dir_entries_on_this_disk !=
          cd->central_dir_entries_on_this_disk &&
      cd->central_dir_entries_on_this_disk != U2_MAX) {
    return false;
  }
  if (central_dir_entries != cd->central_dir_entries &&
      cd->central_dir_entries != U2_MAX) {
    return false;
  }
  if (central_dir_size != cd->central_dir_size &&
      cd->central_dir_size != U4_MAX) {
    return false;
  }
  if (central_dir_offset != cd->central_dir_offset &&
      cd->central_dir_offset != U4_MAX) {
    return false;
  }

  *end_of_central_dir = candidate;
  cd->number_of_this_disk = number_of_this_disk;
  cd->disk_with_central_dir = disk_with_central_dir;
  cd->central_dir_entries_on_this_disk = central_dir_entries_on_this_disk;
  cd->central_dir_entries = central_dir_entries;
  cd->central_dir_size = central_dir_size;
  cd->central_dir_offset = central_dir_offset;
  return true;
}

// Starting from the end of central directory record, attempts to locate a zip64
// end of central directory record. If found, updates the given record and
// offset with the zip64 data. Returns false on error.
bool FindZip64CentralDirectory(const u1 *bytes, size_t in_length,
                               const u1 **end_of_central_dir,
                               EndOfCentralDirectoryRecord *cd) {
  // In the absence of a zip64 extensible data sector, the zip64 EOCD is at a
  // fixed offset from the regular central directory.
  if (MaybeReadZip64CentralDirectory(
          bytes, in_length,
          *end_of_central_dir - ZIP64_EOCD_LOCATOR_SIZE - ZIP64_EOCD_FIXED_SIZE,
          end_of_central_dir, cd)) {
    return true;
  }

  // If we couldn't find a zip64 EOCD at a fixed offset, either it doesn't exist
  // or there was a zip64 extensible data sector, so try going through the
  // locator. This approach doesn't work if data was prepended to the archive
  // without updating the offset in the locator.
  const u1 *zip64_locator = *end_of_central_dir - ZIP64_EOCD_LOCATOR_SIZE;
  if (zip64_locator - ZIP64_EOCD_FIXED_SIZE < bytes) {
    return true;
  }
  u4 zip64_locator_signature = get_u4le(zip64_locator);
  if (zip64_locator_signature != ZIP64_EOCD_LOCATOR_SIGNATURE) {
    return true;
  }
  u4 disk_with_zip64_central_directory = get_u4le(zip64_locator);
  u8 zip64_end_of_central_dir_offset = get_u8le(zip64_locator);
  u4 zip64_total_disks = get_u4le(zip64_locator);
  if (MaybeReadZip64CentralDirectory(bytes, in_length,
                                     bytes + zip64_end_of_central_dir_offset,
                                     end_of_central_dir, cd)) {
    // TODO(b/228519294) Add a test for a valid zip64 file with total disks = 0
    if (disk_with_zip64_central_directory != 0 || zip64_total_disks > 1) {
      fprintf(stderr, "multi-disk JAR files are not supported\n");
      return false;
    }
    return true;
  }
  return true;
}

// Given the data in the zip file, returns the offset of the central directory
// and the number of files contained in it.
bool FindZipCentralDirectory(const u1 *bytes, size_t in_length, u8 *offset,
                             const u1 **central_dir) {
  static const int MAX_COMMENT_LENGTH = 0xffff;
  static const int CENTRAL_DIR_LOCATOR_SIZE = 22;
  // Maximum distance of start of central dir locator from end of file
  static const int MAX_DELTA = MAX_COMMENT_LENGTH + CENTRAL_DIR_LOCATOR_SIZE;
  const u1* last_pos_to_check = in_length < MAX_DELTA
      ? bytes
      : bytes + (in_length - MAX_DELTA);
  const u1* current;
  bool found = false;

  for (current = bytes + in_length - CENTRAL_DIR_LOCATOR_SIZE;
       current >= last_pos_to_check;
       current-- ) {
    const u1* p = current;
    if (get_u4le(p) != EOCD_SIGNATURE) {
      continue;
    }

    p += 16;  // skip to comment length field
    u2 comment_length = get_u2le(p);

    // Does the comment go exactly till the end of the file?
    if (current + comment_length + CENTRAL_DIR_LOCATOR_SIZE
        != bytes + in_length) {
      continue;
    }

    // Hooray, we found it!
    found = true;
    break;
  }

  if (!found) {
    fprintf(stderr, "file is invalid or corrupted (missing end of central "
                    "directory record)\n");
    return false;
  }

  EndOfCentralDirectoryRecord cd;
  const u1* end_of_central_dir = current;
  get_u4le(current);  // central directory locator signature, already checked
  cd.number_of_this_disk = get_u2le(current);
  cd.disk_with_central_dir = get_u2le(current);
  cd.central_dir_entries_on_this_disk = get_u2le(current);
  cd.central_dir_entries = get_u2le(current);
  cd.central_dir_size = get_u4le(current);
  cd.central_dir_offset = get_u4le(current);
  u2 file_comment_length = get_u2le(current);
  current += file_comment_length;  // set current to the end of the central dir

  if (!FindZip64CentralDirectory(bytes, in_length, &end_of_central_dir, &cd)) {
    return false;
  }

  if (cd.number_of_this_disk != 0 || cd.disk_with_central_dir != 0 ||
      cd.central_dir_entries_on_this_disk != cd.central_dir_entries) {
    fprintf(stderr, "multi-disk JAR files are not supported\n");
    return false;
  }

  // Do not change output values before determining that they are OK.
  *offset = cd.central_dir_offset;
  // Central directory start can then be used to determine the actual
  // starts of the zip file (which can be different in case of a non-zip
  // header like for auto-extractable binaries).
  *central_dir = end_of_central_dir - cd.central_dir_size;
  return true;
}

void InputZipFile::Reset() {
  central_dir_current_ = central_dir_;
  bytes_unmapped_ = 0;
  p = zipdata_in_ + in_offset_;
}

int ZipExtractor::ProcessAll() {
  while (ProcessNext()) {}
  if (GetError() != NULL) {
    return -1;
  }
  return 0;
}

ZipExtractor* ZipExtractor::Create(const char* filename,
                                   ZipExtractorProcessor *processor) {
  InputZipFile* result = new InputZipFile(processor, filename);
  if (!result->Open()) {
    fprintf(stderr, "Opening zip \"%s\": %s\n", filename, result->GetError());
    delete result;
    return NULL;
  }

  return result;
}

// zipdata_in_, in_offset_, p, central_dir_current_

InputZipFile::InputZipFile(ZipExtractorProcessor *processor,
                           const char* filename)
    : processor(processor), filename_(filename), input_file_(NULL),
      bytes_unmapped_(0) {
  decompressor_ = new Decompressor();
  errmsg[0] = 0;
}

bool InputZipFile::Open() {
  MappedInputFile* input_file = new MappedInputFile(filename_);
  if (!input_file->Opened()) {
    snprintf(errmsg, sizeof(errmsg), "%s", input_file->Error());
    delete input_file;
    return false;
  }

  void *zipdata_in = input_file->Buffer();
  u8 central_dir_offset;
  const u1 *central_dir = NULL;

  if (!devtools_ijar::FindZipCentralDirectory(
          static_cast<const u1*>(zipdata_in), input_file->Length(),
          &central_dir_offset, &central_dir)) {
    errno = EIO;  // we don't really have a good error number
    error("Cannot find central directory");
    delete input_file;
    return false;
  }
  const u1 *zipdata_start = static_cast<const u1*>(zipdata_in);
  in_offset_ = - static_cast<off_t>(zipdata_start
                                    + central_dir_offset
                                    - central_dir);

  input_file_ = input_file;
  zipdata_in_ = zipdata_start;
  central_dir_ = central_dir;
  central_dir_current_ = central_dir;
  p = zipdata_in_ + in_offset_;
  errmsg[0] = 0;
  return true;
}

InputZipFile::~InputZipFile() {
  delete decompressor_;
  if (input_file_ != NULL) {
    input_file_->Close();
    delete input_file_;
  }
}


//
// Implementation of OutputZipFile
//
int OutputZipFile::WriteEmptyFile(const char *filename) {
  const u1* file_name = (const u1*) filename;
  size_t file_name_length = strlen(filename);

  LocalFileEntry *entry = new LocalFileEntry;
  entry->local_header_offset = Offset(q);
  entry->external_attr = 0;
  entry->crc32 = 0;

  // Output the ZIP local_file_header:
  put_u4le(q, LOCAL_FILE_HEADER_SIGNATURE);
  put_u2le(q, 10);  // extract_version
  put_u2le(q, 0);  // general_purpose_bit_flag
  put_u2le(q, 0);  // compression_method
  put_u4le(q, kDefaultTimestamp);  // last_mod_file date and time
  put_u4le(q, entry->crc32);  // crc32
  put_u4le(q, 0);  // compressed_size
  put_u4le(q, 0);  // uncompressed_size
  put_u2le(q, file_name_length);
  put_u2le(q, 0);  // extra_field_length
  put_n(q, file_name, file_name_length);

  entry->file_name_length = file_name_length;
  entry->extra_field_length = 0;
  entry->compressed_length = 0;
  entry->uncompressed_length = 0;
  entry->compression_method = 0;
  entry->extra_field = (const u1 *)"";
  entry->file_name = (u1*) strdup((const char *) file_name);
  entries_.push_back(entry);

  return 0;
}

void OutputZipFile::WriteCentralDirectory() {
  // central directory:
  const u1 *central_directory_start = q;
  for (size_t ii = 0; ii < entries_.size(); ++ii) {
    LocalFileEntry *entry = entries_[ii];
    put_u4le(q, CENTRAL_FILE_HEADER_SIGNATURE);
    put_u2le(q, UNIX_ZIP_FILE_VERSION);

    put_u2le(q, ZIP_VERSION_TO_EXTRACT);  // version to extract
    put_u2le(q, 0);  // general purpose bit flag
    put_u2le(q, entry->compression_method);  // compression method:
    put_u4le(q, kDefaultTimestamp);          // last_mod_file date and time
    put_u4le(q, entry->crc32);  // crc32
    put_u4le(q, entry->compressed_length);    // compressed_size
    put_u4le(q, entry->uncompressed_length);  // uncompressed_size
    put_u2le(q, entry->file_name_length);
    put_u2le(q, entry->extra_field_length);

    put_u2le(q, 0);  // file comment length
    put_u2le(q, 0);  // disk number start
    put_u2le(q, 0);  // internal file attributes
    put_u4le(q, entry->external_attr);  // external file attributes
    // relative offset of local header:
    put_u4le(q, entry->local_header_offset);

    put_n(q, entry->file_name, entry->file_name_length);
    put_n(q, entry->extra_field, entry->extra_field_length);
  }
  u8 central_directory_size = q - central_directory_start;

  if (entries_.size() > U2_MAX || central_directory_size > U4_MAX ||
      Offset(central_directory_start) > U4_MAX) {
    u1 *zip64_end_of_central_directory_start = q;

    put_u4le(q, ZIP64_EOCD_SIGNATURE);
    // signature and size field doesn't count towards size
    put_u8le(q, ZIP64_EOCD_FIXED_SIZE - 12);
    put_u2le(q, UNIX_ZIP_FILE_VERSION);  // version made by
    put_u2le(q, 0);  // version needed to extract
    put_u4le(q, 0);  // number of this disk
    put_u4le(q, 0);  // # of the disk with the start of the central directory
    put_u8le(q, entries_.size());  // # central dir entries on this disk
    put_u8le(q, entries_.size());  // total # entries in the central directory
    put_u8le(q, central_directory_size);  // size of the central directory
    // offset of start of central directory wrt starting disk
    put_u8le(q, Offset(central_directory_start));

    put_u4le(q, ZIP64_EOCD_LOCATOR_SIGNATURE);
    // number of the disk with the start of the zip64 end of central directory
    put_u4le(q, 0);
    // relative offset of the zip64 end of central directory record
    put_u8le(q, Offset(zip64_end_of_central_directory_start));
    // total number of disks
    put_u4le(q, 1);

    put_u4le(q, EOCD_SIGNATURE);
    put_u2le(q, 0);  // number of this disk
    put_u2le(q, 0);  // # of disk with the start of the central directory
    // # central dir entries on this disk
    put_u2le(q, entries_.size() > 0xffff ? 0xffff : entries_.size());
    // total # entries in the central directory
    put_u2le(q, entries_.size() > 0xffff ? 0xffff : entries_.size());
    // size of the central directory
    put_u4le(q,
             central_directory_size > U4_MAX ? U4_MAX : central_directory_size);
    // offset of start of central
    put_u4le(q, Offset(central_directory_start) > U4_MAX
                    ? U4_MAX
                    : Offset(central_directory_start));
    put_u2le(q, 0);  // .ZIP file comment length

  } else {
    put_u4le(q, EOCD_SIGNATURE);
    put_u2le(q, 0);  // number of this disk
    put_u2le(q, 0);  // # of the disk with the start of the central directory
    put_u2le(q, entries_.size());  // # central dir entries on this disk
    put_u2le(q, entries_.size());  // total # entries in the central directory
    put_u4le(q, central_directory_size);  // size of the central directory
    // offset of start of central directory wrt starting disk
    put_u4le(q, Offset(central_directory_start));
    put_u2le(q, 0);  // .ZIP file comment length
  }
}

u1* OutputZipFile::WriteLocalFileHeader(const char* filename, const u4 attr) {
  off_t file_name_length_ = strlen(filename);
  LocalFileEntry *entry = new LocalFileEntry;
  entry->local_header_offset = Offset(q);
  entry->file_name_length = file_name_length_;
  entry->file_name = new u1[file_name_length_];
  entry->external_attr = attr;
  memcpy(entry->file_name, filename, file_name_length_);
  entry->extra_field_length = 0;
  entry->extra_field = (const u1 *)"";
  entry->crc32 = 0;

  // Output the ZIP local_file_header:
  put_u4le(q, LOCAL_FILE_HEADER_SIGNATURE);
  put_u2le(q, ZIP_VERSION_TO_EXTRACT);     // version to extract
  put_u2le(q, 0);                          // general purpose bit flag
  u1 *header_ptr = q;
  put_u2le(q, COMPRESSION_METHOD_STORED);  // compression method = placeholder
  put_u4le(q, kDefaultTimestamp);          // last_mod_file date and time
  put_u4le(q, entry->crc32);               // crc32
  put_u4le(q, 0);  // compressed_size = placeholder
  put_u4le(q, 0);  // uncompressed_size = placeholder
  put_u2le(q, entry->file_name_length);
  put_u2le(q, entry->extra_field_length);

  put_n(q, entry->file_name, entry->file_name_length);
  put_n(q, entry->extra_field, entry->extra_field_length);
  entries_.push_back(entry);

  return header_ptr;
}

size_t OutputZipFile::WriteFileSizeInLocalFileHeader(u1 *header_ptr,
                                                     size_t out_length,
                                                     bool compress,
                                                     const u4 crc) {
  size_t compressed_size = out_length;
  if (compress) {
    compressed_size = TryDeflate(q, out_length);
  }
  // compression method
  if (compressed_size < out_length) {
    put_u2le(header_ptr, COMPRESSION_METHOD_DEFLATED);
  } else {
    put_u2le(header_ptr, COMPRESSION_METHOD_STORED);
  }
  header_ptr += 4;
  put_u4le(header_ptr, crc);              // crc32
  put_u4le(header_ptr, compressed_size);  // compressed_size
  put_u4le(header_ptr, out_length);       // uncompressed_size
  return compressed_size;
}

int OutputZipFile::Finish() {
  if (finished_) {
    return 0;
  }

  finished_ = true;
  WriteCentralDirectory();
  if (output_file_->Close(GetSize()) < 0) {
    return error("%s", output_file_->Error());
  }
  delete output_file_;
  output_file_ = NULL;
  return 0;
}

u1* OutputZipFile::NewFile(const char* filename, const u4 attr) {
  header_ptr = WriteLocalFileHeader(filename, attr);
  return q;
}

int OutputZipFile::FinishFile(size_t filelength, bool compress,
                              bool compute_crc) {
  u4 crc = 0;
  if (compute_crc) {
    crc = ComputeCrcChecksum(q, filelength);

    if (filelength > 0 && crc == 0) {
      fprintf(stderr, "Error calculating CRC32 checksum.\n");
      return -1;
    }
  }
  size_t compressed_size =
      WriteFileSizeInLocalFileHeader(header_ptr, filelength, compress, crc);

  if (compressed_size == 0 && filelength > 0) {
    fprintf(stderr, "Error compressing files.\n");
    return -1;
  }

  entries_.back()->crc32 = crc;
  entries_.back()->compressed_length = compressed_size;
  entries_.back()->uncompressed_length = filelength;
  if (compressed_size < filelength) {
    entries_.back()->compression_method = COMPRESSION_METHOD_DEFLATED;
  } else {
    entries_.back()->compression_method = COMPRESSION_METHOD_STORED;
  }
  q += compressed_size;
  return 0;
}

bool OutputZipFile::Open() {
  if (estimated_size_ > kMaximumOutputSize) {
    fprintf(stderr,
            "Uncompressed input jar has size %zu, "
            "which exceeds the maximum supported output size %zu.\n"
            "Assuming that ijar will be smaller and hoping for the best.\n",
            estimated_size_, kMaximumOutputSize);
    estimated_size_ = kMaximumOutputSize;
  }

  MappedOutputFile* output_file = new MappedOutputFile(
      filename_, estimated_size_);
  if (!output_file->Opened()) {
    snprintf(errmsg, sizeof(errmsg), "%s", output_file->Error());
    delete output_file;
    return false;
  }

  output_file_ = output_file;
  q = output_file->Buffer();
  zipdata_out_ = output_file->Buffer();
  return true;
}

ZipBuilder *ZipBuilder::Create(const char *zip_file, size_t estimated_size) {
  OutputZipFile* result = new OutputZipFile(zip_file, estimated_size);
  if (!result->Open()) {
    fprintf(stderr, "%s\n", result->GetError());
    delete result;
    return NULL;
  }

  return result;
}

u8 ZipBuilder::EstimateSize(char const* const* files,
                            char const* const* zip_paths,
                            int nb_entries) {
  Stat file_stat;
  // Digital signature field size = 6, End of central directory = 22, Total = 28
  u8 size = 28;
  // Count the size of all the files in the input to estimate the size of the
  // output.
  for (int i = 0; i < nb_entries; i++) {
    file_stat.total_size = 0;
    if (files[i] != NULL && !stat_file(files[i], &file_stat)) {
      fprintf(stderr, "File %s does not seem to exist.", files[i]);
      return 0;
    }
    size += file_stat.total_size;
    // Add sizes of Zip meta data
    // local file header = 30 bytes
    // data descriptor = 12 bytes
    // central directory descriptor = 46 bytes
    //    Total: 88bytes
    size += 88;
    // The filename is stored twice (once in the central directory
    // and once in the local file header).
    size += strlen((zip_paths[i] != NULL) ? zip_paths[i] : files[i]) * 2;
  }
  return size;
}

}  // namespace devtools_ijar
