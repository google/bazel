// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.authandtls;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Base64;

/**
 * Encoder for Basic Http Authentication.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7617">The 'Basic' HTTP Authentication Scheme</a>
 */
public final class BasicHttpAuthenticationEncoder {

  private BasicHttpAuthenticationEncoder() {}

  /** Encode username and password into a token, encoded using UTF-8. */
  public static String encode(String username, String password) {
    return "Basic "
        + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(UTF_8));
  }
}
