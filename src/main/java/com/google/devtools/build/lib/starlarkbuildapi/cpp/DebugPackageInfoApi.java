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

package com.google.devtools.build.lib.starlarkbuildapi.cpp;

import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.docgen.annot.StarlarkConstructor;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.starlarkbuildapi.FileApi;
import com.google.devtools.build.lib.starlarkbuildapi.core.ProviderApi;
import com.google.devtools.build.lib.starlarkbuildapi.core.StructApi;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.NoneType;

/** Wrapper for the native DebugPackageProvider. */
@StarlarkBuiltin(
    name = "DebugPackageInfo",
    category = DocCategory.PROVIDER,
    doc =
        "A provider for the binary file and its associated .dwp files, if fission is enabled."
            + "If Fission ({@url https://gcc.gnu.org/wiki/DebugFission}) is not enabled, the dwp "
            + "file will be null.")
public interface DebugPackageInfoApi<FileT extends FileApi> extends StructApi {
  String NAME = "DebugPackageInfo";

  @StarlarkMethod(
      name = "target_label",
      doc = "Returns the label for the *_binary target",
      structField = true)
  Label getTargetLabel();

  @StarlarkMethod(
      name = "stripped_file",
      doc = "Returns the stripped file (the explicit \".stripped\" target).",
      allowReturnNones = true,
      structField = true)
  @Nullable
  FileT getStrippedArtifact();

  @StarlarkMethod(
      name = "unstripped_file",
      doc = "Returns the unstripped file (the default executable target)",
      structField = true)
  FileT getUnstrippedArtifact();

  @StarlarkMethod(
      name = "dwp_file",
      doc = "Returns the .dwp file (for fission builds) or null if --fission=no.",
      structField = true,
      allowReturnNones = true)
  @Nullable
  FileT getDwpArtifact();

  @StarlarkMethod(
      name = "dwo_files",
      doc = "Returns the .dwo files (for fission builds) or empty if --fission=no.",
      structField = true,
      allowReturnNones = true)
  @Nullable
  Depset getDwoFiles();

  /** The provider implementing this can construct DebugPackageInfo objects. */
  @StarlarkBuiltin(name = "Provider", doc = "", documented = false)
  interface Provider<FileT extends FileApi> extends ProviderApi {

    @StarlarkMethod(
        name = NAME,
        doc = "The <code>DebugPackageInfo</code> constructor.",
        parameters = {
          @Param(
              name = "target_label",
              doc = "The label for the *_binary target",
              positional = false,
              named = true),
          @Param(
              name = "stripped_file",
              doc = "The stripped file (the explicit \".stripped\" target)",
              positional = false,
              named = true,
              defaultValue = "None",
              allowedTypes = {@ParamType(type = FileApi.class), @ParamType(type = NoneType.class)}),
          @Param(
              name = "unstripped_file",
              doc = "The unstripped file (the default executable target).",
              positional = false,
              named = true),
          @Param(
              name = "dwp_file",
              doc = "The .dwp file (for fission builds) or null if --fission=no.",
              positional = false,
              named = true,
              defaultValue = "None",
              allowedTypes = {@ParamType(type = FileApi.class), @ParamType(type = NoneType.class)}),
          @Param(
              name = "dwo_files",
              doc = "The depset of .dwo files (for fission builds) or null if --fission=no.",
              positional = false,
              named = true,
              defaultValue = "None",
              allowedTypes = {@ParamType(type = Depset.class), @ParamType(type = NoneType.class)})
        },
        selfCall = true)
    @StarlarkConstructor
    DebugPackageInfoApi<FileT> createDebugPackageInfo(
        Label targetLabel, Object strippedFile, FileT unstrippedFile, Object dwpFile, Object dwoFiles)
        throws EvalException;
  }
}
