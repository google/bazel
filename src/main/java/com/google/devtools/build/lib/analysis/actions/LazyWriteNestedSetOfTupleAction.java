// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis.actions;


import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.unsafe.StringUnsafe;
import com.google.devtools.build.lib.util.Fingerprint;
import javax.annotation.Nullable;
import net.starlark.java.eval.Tuple;

/**
 * Lazily writes the content of a nested set of tuplesToWrite to an output file.
 *
 * <p>Writes delimiter separated Tuple elements to the output file.
 */
public final class LazyWriteNestedSetOfTupleAction extends AbstractFileWriteAction {

  private final NestedSet<Tuple> tuplesToWrite;
  private String fileContents;
  private final String delimiter;

  public LazyWriteNestedSetOfTupleAction(
      ActionOwner owner, Artifact output, NestedSet<Tuple> tuplesToWrite, String delimiter) {
    super(owner, NestedSetBuilder.emptySet(Order.STABLE_ORDER), output);
    this.tuplesToWrite = tuplesToWrite;
    this.delimiter = delimiter;
  }

  @Override
  public DeterministicWriter newDeterministicWriter(ActionExecutionContext ctx) {
    return out ->
        out.write(StringUnsafe.getInstance().getInternalStringBytes(getContents(delimiter)));
  }

  /** Computes the Action key for this action by computing the fingerprint for the file contents. */
  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      Fingerprint fp)
      throws CommandLineExpansionException, InterruptedException {
    actionKeyContext.addNestedSetToFingerprint(fp, tuplesToWrite);
  }

  private String getContents(String delimiter) {
    if (fileContents == null) {
      StringBuilder stringBuilder = new StringBuilder();
      for (Tuple tuple : tuplesToWrite.toList()) {
        if (tuple.isEmpty()) {
          continue;
        }
        stringBuilder.append(tuple.get(0));
        for (int i = 1; i < tuple.size(); i++) {
          stringBuilder.append(delimiter).append(tuple.get(i));
        }
        stringBuilder.append(System.lineSeparator());
      }
      fileContents = stringBuilder.toString();
    }
    return fileContents;
  }
}
