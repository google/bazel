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

package com.tonicsystems.jarjar.util;

import java.io.IOException;

public interface JarProcessor {
  /**
   * Process the entry (p.ex. rename the file)
   *
   * <p>Returns <code>true</code> if the processor has has changed the entry. In this case, the
   * entry can be removed from the jar file in a future time. Return <code>false</code> for the
   * entries which do not have been changed and there fore are not to be deleted
   *
   * @param struct the struct
   * @return <code>true</code> if he process chain can continue after this process
   */
  boolean process(EntryStruct struct) throws IOException;
}
