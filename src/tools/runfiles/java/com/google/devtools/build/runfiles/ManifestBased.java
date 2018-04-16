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

package com.google.devtools.build.runfiles;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** {@link Runfiles} implementation that parses a runfiles-manifest file to look up runfiles. */
final class ManifestBased extends Runfiles {
  private final Map<String, String> runfiles;
  private final String manifestPath;

  ManifestBased(String manifestPath) throws IOException {
    Util.checkArgument(manifestPath != null);
    Util.checkArgument(!manifestPath.isEmpty());
    this.manifestPath = manifestPath;
    this.runfiles = loadRunfiles(manifestPath);
  }

  private static Map<String, String> loadRunfiles(String path) throws IOException {
    HashMap<String, String> result = new HashMap<>();
    try (BufferedReader r =
        new BufferedReader(
            new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
      String line = null;
      while ((line = r.readLine()) != null) {
        int index = line.indexOf(' ');
        String runfile = (index == -1) ? line : line.substring(0, index);
        String realPath = (index == -1) ? line : line.substring(index + 1);
        result.put(runfile, realPath);
      }
    }
    return Collections.unmodifiableMap(result);
  }

  private static String findRunfilesDir(String manifest) {
    if (manifest.endsWith("/MANIFEST") || manifest.endsWith("\\MANIFEST")
        || manifest.endsWith(".runfiles_manifest")) {
      String path = manifest.substring(0, manifest.length() - 9);
      if (new File(path).isDirectory()) {
        return path;
      }
    }
    return "";
  }

  @Override
  public String rlocationChecked(String path) {
    return runfiles.get(path);
  }

  @Override
  public Map<String, String> getEnvVars() {
    HashMap<String, String> result = new HashMap<>(4);
    result.put("RUNFILES_MANIFEST_ONLY", "1");
    result.put("RUNFILES_MANIFEST_FILE", manifestPath);
    String runfilesDir = findRunfilesDir(manifestPath);
    result.put("RUNFILES_DIR", runfilesDir);
    // TODO(laszlocsomor): remove JAVA_RUNFILES once the Java launcher can pick up RUNFILES_DIR.
    result.put("JAVA_RUNFILES", runfilesDir);
    return result;
  }
}
