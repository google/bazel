/*
 * Copyright 2024 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tonicsystems.jarjar;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.tonicsystems.jarjar.util.EntryStruct;
import com.tonicsystems.jarjar.util.JarProcessor;
import java.io.IOException;
import java.util.stream.Collectors;

class ServiceProcessor implements JarProcessor {
  private final PackageRemapper pr;

  public ServiceProcessor(PackageRemapper pr) {
    this.pr = pr;
  }

  private static final String SERVICES_PREFIX = "META-INF/services/";

  @Override
  public boolean process(EntryStruct struct) throws IOException {
    if (struct.name.startsWith(SERVICES_PREFIX)) {
      String serviceName = struct.name.substring(SERVICES_PREFIX.length());
      struct.name = SERVICES_PREFIX + mapString(serviceName);

      struct.data =
          new String(struct.data, UTF_8)
              .lines()
              .map(this::mapString)
              .collect(Collectors.joining("\n", "", "\n"))
              .getBytes(UTF_8);
    }
    return true;
  }

  private String mapString(String s) {
    return (String) pr.mapValue(s);
  }
}
