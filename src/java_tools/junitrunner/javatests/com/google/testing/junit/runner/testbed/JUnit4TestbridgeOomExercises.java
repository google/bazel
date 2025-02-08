// Copyright 2024 The Bazel Authors. All Rights Reserved.
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

package com.google.testing.junit.runner.testbed;

import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** A JUnit4-style test meant to be invoked by junit4_testbridge_tests.sh. */
@RunWith(JUnit4.class)
public class JUnit4TestbridgeOomExercises {
  @Test
  public void testPass() {}

  @Test
  public void testFailAssertion() {
    fail();
  }

  @Test
  public void testFailWithOom() {
    throw new OutOfMemoryError("testing");
  }
}
