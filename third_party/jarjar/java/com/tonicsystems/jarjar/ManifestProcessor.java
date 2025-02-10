/*
 * Copyright 2007 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tonicsystems.jarjar;

import com.tonicsystems.jarjar.util.EntryStruct;
import com.tonicsystems.jarjar.util.JarProcessor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.jar.Manifest;

final class ManifestProcessor implements JarProcessor {
  private static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";

  private final PackageRemapper pr;
  private final boolean skipManifest;

  public ManifestProcessor(PackageRemapper pr, boolean skipManifest) {
    this.pr = pr;
    this.skipManifest = skipManifest;
  }

  @Override
  public boolean process(EntryStruct struct) throws IOException {
    if (!struct.name.equalsIgnoreCase(MANIFEST_PATH)) {
      return true; // Ignore all other files
    }

    if (this.skipManifest) {
      return false; // Remove the manifest from the JAR
    }

    Manifest manifest = new Manifest(new ByteArrayInputStream(struct.data));
    remapAttributeValue(manifest, "Main-Class");
    struct.data = serializeManifest(manifest);

    return true;
  }

  private void remapAttributeValue(Manifest manifest, String name) {
    String value = manifest.getMainAttributes().getValue(name);
    if (value != null) {
      manifest.getMainAttributes().putValue(name, (String) pr.mapValue(value));
    }
  }

  private byte[] serializeManifest(Manifest manifest) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    manifest.write(baos);
    return baos.toByteArray();
  }
}
