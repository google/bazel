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
// Zip / Unzip file using ijar zip implementation.
//
// Note that this Zip implementation intentionally don't compute CRC-32
// because it is useless computation for jar because Java doesn't care.
// CRC-32 of all files in the zip file will be set to 0.
//

#include <errno.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <memory>
#include <set>
#include <string>

#include "third_party/ijar/platform_utils.h"
#include "third_party/ijar/zip.h"

namespace devtools_ijar {

//
// A ZipExtractorProcessor that extract files in the ZIP file.
//
class UnzipProcessor : public ZipExtractorProcessor {
 public:
  // Create a processor who will extract the given files (or all files if NULL)
  // into output_root if "extract" is set to true and will print the list of
  // files and their unix modes if "verbose" is set to true.
  UnzipProcessor(const char *output_root, char **files, bool verbose,
                 bool extract, bool flatten)
      : output_root_(output_root),
        verbose_(verbose),
        extract_(extract),
        flatten_(flatten) {
    if (files != NULL) {
      for (int i = 0; files[i] != NULL; i++) {
        file_names.insert(std::string(files[i]));
      }
    }
  }

  virtual ~UnzipProcessor() {}

  virtual void Process(const char *filename, u4 attr, const u1 *data,
                       size_t size);
  virtual bool Accept(const char* filename, const u4 attr) {
    // All entry files are accepted by default.
    if (file_names.empty()) {
      return true;
    } else {
      // If users have specified file entries, only accept those files.
      return file_names.count(std::string(filename)) == 1;
    }
  }

 private:
  const char *output_root_;
  const bool verbose_;
  const bool extract_;
  const bool flatten_;
  std::set<std::string> file_names;
};

// Concatene 2 path, path1 and path2, using / as a directory separator and
// putting the result in "out". "size" specify the size of the output buffer. If
// the result would overflow the output buffer, print an error message and
// return false.
bool concat_path(char *out, const size_t size, const char *path1,
                 const char *path2) {
  int len1 = strlen(path1);
  size_t l = len1;
  strncpy(out, path1, size - 1);
  out[size - 1] = 0;
  if (l < size - 1 && path1[len1] != '/' && path2[0] != '/') {
    out[l] = '/';
    l++;
    out[l] = 0;
  }
  if (l >= size - 1) {
    fprintf(stderr, "paths too long to concat: %s + %s", path1, path2);
    return false;
  }
  strncat(out, path2, size - 1 - l);
  return true;
}

void UnzipProcessor::Process(const char* filename, const u4 attr,
                             const u1* data, const size_t size) {
  mode_t perm = zipattr_to_perm(attr);
  bool isdir = zipattr_is_dir(attr);
  const char *output_file_name = filename;
  if (attr == 0) {
    // Fallback when the external attribute is not set.
    isdir = filename[strlen(filename)-1] == '/';
    perm = 0777;
  }

  if (flatten_) {
    if (isdir) {
      return;
    }
    const char *p = strrchr(filename, '/');
    if (p != NULL) {
      output_file_name = p + 1;
    }
  }

  if (verbose_) {
    printf("%c %o %s\n", isdir ? 'd' : 'f', perm, output_file_name);
  }
  if (extract_) {
    char path[PATH_MAX];
    if (!concat_path(path, sizeof(path), output_root_, output_file_name) ||
        !make_dirs(path, perm) ||
        (!isdir && !write_file(path, perm, data, size))) {
      abort();
    }
  }
}

// Get the basename of path and store it in output. output_size
// is the size of the output buffer.
void basename(const char *path, char *output, size_t output_size) {
  const char *pointer = strrchr(path, '/');
  if (pointer == NULL) {
    pointer = path;
  } else {
    pointer++;  // Skip the leading slash.
  }
  strncpy(output, pointer, output_size - 1);
  output[output_size - 1] = 0;
}

// Execute the extraction (or just listing if just v is provided)
int extract(char *zipfile, char *exdir, char **files, bool verbose,
            bool extract, bool flatten) {
  std::string cwd = get_cwd();
  if (cwd.empty()) {
    return -1;
  }

  char output_root[PATH_MAX + 1];
  if (exdir != NULL) {
    if (!concat_path(output_root, sizeof(output_root), cwd.c_str(), exdir)) {
      return -1;
    }
  } else if (cwd.length() >= sizeof(output_root)) {
    fprintf(stderr, "current working directory path too long");
    return -1;
  } else {
    memcpy(output_root, cwd.c_str(), cwd.length() + 1);
  }

  UnzipProcessor processor(output_root, files, verbose, extract, flatten);
  std::unique_ptr<ZipExtractor> extractor(ZipExtractor::Create(zipfile,
                                                               &processor));
  if (extractor == NULL) {
    fprintf(stderr, "Unable to open zip file %s: %s.\n", zipfile,
            strerror(errno));
    return -1;
  }

  if (extractor->ProcessAll() < 0) {
    fprintf(stderr, "%s.\n", extractor->GetError());
    return -1;
  }
  return 0;
}

// add a file to the zip
int add_file(std::unique_ptr<ZipBuilder> const &builder, char *file,
             char *zip_path, bool flatten, bool verbose, bool compress) {
  Stat file_stat = {0, 0666, false};
  if (file != NULL) {
    if (!stat_file(file, &file_stat)) {
      fprintf(stderr, "Cannot stat file %s: %s\n", file, strerror(errno));
      return -1;
    }
  }
  char *final_path = zip_path != NULL ? zip_path : file;

  bool isdir = file_stat.is_directory;

  if (flatten && isdir) {
    return 0;
  }

  // Compute the path, flattening it if requested
  char path[PATH_MAX];
  size_t len = strlen(final_path);
  if (len > PATH_MAX) {
    fprintf(stderr, "Path too long: %s.\n", final_path);
    return -1;
  }
  if (flatten) {
    basename(final_path, path, PATH_MAX);
  } else {
    strncpy(path, final_path, PATH_MAX);
    path[PATH_MAX - 1] = 0;
    if (isdir && len < PATH_MAX - 1) {
      // Add the trailing slash for folders
      path[len] = '/';
      path[len + 1] = 0;
    }
  }

  if (verbose) {
    mode_t perm = file_stat.file_mode & 0777;
    printf("%c %o %s\n", isdir ? 'd' : 'f', perm, path);
  }

  u1 *buffer = builder->NewFile(path, stat_to_zipattr(file_stat));
  if (isdir || file_stat.total_size == 0) {
    builder->FinishFile(0);
  } else {
    if (!read_file(file, buffer, file_stat.total_size)) {
      return -1;
    }
    builder->FinishFile(file_stat.total_size, compress, true);
  }
  return 0;
}

// Read a list of files separated by newlines. The resulting array can be
// freed using the free method.
char **read_filelist(char *filename) {
  Stat file_stat;
  if (!stat_file(filename, &file_stat)) {
    fprintf(stderr, "Cannot stat file %s: %s\n", filename, strerror(errno));
    return NULL;
  }

  char *data = static_cast<char *>(malloc(file_stat.total_size));
  if (!read_file(filename, data, file_stat.total_size)) {
    return NULL;
  }

  int nb_entries = 1;
  for (u8 i = 0; i < file_stat.total_size; i++) {
    if (data[i] == '\n') {
      nb_entries++;
    }
    if (nb_entries == INT_MAX) {
      fprintf(stderr, "too many input files");
      return NULL;
    }
  }

  size_t sizeof_array = sizeof(char *) * (nb_entries + 1);
  void *result = malloc(sizeof_array + file_stat.total_size + 1);
  // copy the content
  char **filelist = static_cast<char **>(result);
  char *content = static_cast<char *>(result) + sizeof_array;
  memcpy(content, data, file_stat.total_size);
  content[file_stat.total_size] = '\0';
  free(data);
  // Create the corresponding array
  int j = 1;
  filelist[0] = content;
  for (u8 i = 0; i < file_stat.total_size; i++) {
    if (content[i] == '\n') {
      content[i] = 0;
      if (i + 1 < file_stat.total_size) {
        filelist[j] = content + i + 1;
        j++;
      }
    }
  }
  filelist[j] = NULL;
  return filelist;
}

// return real paths of the files
char **parse_filelist(char *zipfile, char **file_entries, int nb_entries,
                      bool flatten) {
  // no need to free since the path lists should live until the end of the
  // program
  char **files = static_cast<char **>(malloc(sizeof(char *) * nb_entries));
  char **zip_paths = file_entries;
  for (int i = 0; i < nb_entries; i++) {
    char *p_eq = strchr(file_entries[i], '=');
    if (p_eq != NULL) {
      if (flatten) {
        fprintf(stderr, "Unable to create zip file %s: %s.\n", zipfile,
                "= can't be used with flatten");
        free(files);
        return NULL;
      }
      if (p_eq == file_entries[i]) {
        fprintf(stderr, "Unable to create zip file %s: %s.\n", zipfile,
                "A zip path should be given before =");
        free(files);
        return NULL;
      }
      *p_eq = '\0';
      files[i] = p_eq + 1;
      if (files[i][0] == '\0') {
        files[i] = NULL;
      }
    } else {
      files[i] = file_entries[i];
      zip_paths[i] = NULL;
    }
  }
  return files;
}

// Execute the create operation
int create(char *zipfile, char **file_entries, bool flatten, bool verbose,
           bool compress) {
  int nb_entries = 0;
  while (file_entries[nb_entries] != NULL) {
    nb_entries++;
  }
  char **zip_paths = file_entries;
  char **files = parse_filelist(zipfile, file_entries, nb_entries, flatten);
  if (files == NULL) {
    return -1;
  }

  u8 size = ZipBuilder::EstimateSize(files, zip_paths, nb_entries);
  if (size == 0) {
    return -1;
  }
  std::unique_ptr<ZipBuilder> builder(ZipBuilder::Create(zipfile, size));
  if (builder == NULL) {
    fprintf(stderr, "Unable to create zip file %s: %s.\n",
            zipfile, strerror(errno));
    return -1;
  }

  for (int i = 0; i < nb_entries; i++) {
    if (add_file(builder, files[i], zip_paths[i], flatten, verbose, compress) <
        0) {
      return -1;
    }
  }
  if (builder->Finish() < 0) {
    fprintf(stderr, "%s\n", builder->GetError());
    return -1;
  }
  return 0;
}

}  // namespace devtools_ijar

//
// main method
//
static void usage(char *progname) {
  fprintf(stderr,
          "Usage: %s [vxc[fC]] x.zip [-d exdir] [[zip_path1=]file1 ... "
          "[zip_pathn=]filen]\n",
          progname);
  fprintf(stderr, "  v verbose - list all file in x.zip\n");
  fprintf(stderr,
          "  x extract - extract files in x.zip to current directory, or "
          "    an optional directory relative to the current directory "
          "    specified through -d option\n");
  fprintf(stderr, "  c create  - add files to x.zip\n");
  fprintf(stderr,
          "  f flatten - flatten files to use with create or "
          "extract operation\n");
  fprintf(stderr,
          "  C compress - compress files when using the create operation\n");
  fprintf(stderr, "x and c cannot be used in the same command-line.\n");
  fprintf(stderr,
          "\nFor every file, a path in the zip can be specified. Examples:\n");
  fprintf(stderr,
          "  zipper c x.zip a/b/__init__.py= # Add an empty file at "
          "a/b/__init__.py\n");
  fprintf(stderr,
          "  zipper c x.zip a/b/main.py=foo/bar/bin.py # Add file "
          "foo/bar/bin.py at a/b/main.py\n");
  fprintf(stderr,
          "\nIf the zip path is not specified, it is assumed to be the file "
          "path.\n");
  exit(1);
}

int main(int argc, char **argv) {
  bool extract = false;
  bool verbose = false;
  bool create = false;
  bool compress = false;
  bool flatten = false;

  if (argc < 3) {
    usage(argv[0]);
  }

  for (int i = 0; argv[1][i] != 0; i++) {
    switch (argv[1][i]) {
    case 'x':
      extract = true;
      break;
    case 'v':
      verbose = true;
      break;
    case 'c':
      create = true;
      break;
    case 'f':
      flatten = true;
      break;
    case 'C':
      compress = true;
      break;
    default:
      usage(argv[0]);
    }
  }

  // x and c cannot be used in the same command-line.
  if (create && extract) {
    usage(argv[0]);
  }

  // Calculate the argument index of the first entry file.
  int filelist_start_index;
  if (argc > 3 && strcmp(argv[3], "-d") == 0) {
    filelist_start_index = 5;
  } else {
    filelist_start_index = 3;
  }

  char** filelist = NULL;

  // We have one option file. Read and extract the content.
  if (argc == filelist_start_index + 1 &&
      argv[filelist_start_index][0] == '@') {
    char* filelist_name = argv[filelist_start_index];
    filelist = devtools_ijar::read_filelist(filelist_name + 1);
    if (filelist == NULL) {
      fprintf(stderr, "Can't read file list %s: %s.\n", filelist_name,
              strerror(errno));
      return -1;
    }
    // We have more than one files. Assume that they are all file entries.
  } else if (argc >= filelist_start_index + 1) {
    filelist = argv + filelist_start_index;
  } else {
    // There are no entry files specified. This is forbidden if we are creating
    // a zip file.
    if (create) {
      fprintf(stderr, "Can't create zip without input files specified.");
      return -1;
    }
  }

  if (create) {
    // Create a zip
    return devtools_ijar::create(argv[2], filelist, flatten, verbose, compress);
  } else {
    char* exdir = NULL;
    if (argc > 3 && strcmp(argv[3], "-d") == 0) {
      exdir = argv[4];
    }

    // Extraction / list mode
    return devtools_ijar::extract(argv[2], exdir, filelist, verbose, extract,
                                  flatten);
  }
}
