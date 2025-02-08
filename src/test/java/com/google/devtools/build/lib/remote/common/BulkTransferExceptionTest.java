// Copyright 2024 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.remote.common;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class BulkTransferExceptionTest {

  @Test
  public void shouldProvideGenericMessageIfNoAddedException() {
    BulkTransferException bulkTransferException = new BulkTransferException();
    assertThat(bulkTransferException.getMessage()).isEqualTo("Unknown error during bulk transfer");
  }

  @Test
  public void shouldPreserveMessageAsIsFromSingleException() {
    BulkTransferException bulkTransferException = new BulkTransferException();
    bulkTransferException.add(new IOException("Failure Type A"));
    assertThat(bulkTransferException.getMessage()).isEqualTo("Failure Type A");
  }

  @Test
  public void shouldSortAndRemoveDuplicatesWhenAggregatingMessages() {
    BulkTransferException bulkTransferException = new BulkTransferException();
    bulkTransferException.add(new IOException("Failure Type B"));
    bulkTransferException.add(new IOException("Failure Type A"));
    bulkTransferException.add(new IOException("Failure Type B"));
    assertThat(bulkTransferException.getMessage())
        .isEqualTo(
            "Multiple errors during bulk transfer:\n" + "Failure Type A\n" + "Failure Type B");
  }

  @Test
  public void shouldProvideGenericMessageIfOnlyNullMessages() {
    BulkTransferException bulkTransferException = new BulkTransferException();
    bulkTransferException.add(new IOException());
    assertThat(bulkTransferException.getMessage()).isEqualTo("Unknown error during bulk transfer");
  }

  @Test
  public void shouldIgnoreNullMessagesWhenGettingMessage() {
    BulkTransferException bulkTransferException = new BulkTransferException();
    bulkTransferException.add(new IOException("Failure Type A"));
    bulkTransferException.add(new IOException());
    assertThat(bulkTransferException.getMessage()).isEqualTo("Failure Type A");
  }
}
