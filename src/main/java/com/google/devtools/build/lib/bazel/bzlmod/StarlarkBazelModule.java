// Copyright 2021 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.packages.LabelConverter;
import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.eval.Structure;
import net.starlark.java.spelling.SpellChecker;

/** A Starlark object representing a Bazel module in the external dependency graph. */
@StarlarkBuiltin(
    name = "bazel_module",
    category = DocCategory.BUILTIN,
    doc = "Represents a Bazel module in the external dependency graph.")
public class StarlarkBazelModule implements StarlarkValue {
  private final String name;
  private final String version;
  private final Tags tags;
  private final boolean isRootModule;

  @StarlarkBuiltin(
      name = "bazel_module_tags",
      category = DocCategory.BUILTIN,
      doc =
          "Contains the tags in a module for the module extension currently being processed. This"
              + " object has a field for each tag class of the extension, and the value of the"
              + " field is a list containing an object for each tag instance. This \"tag instance\""
              + " object in turn has a field for each attribute of the tag class.\n\n"
              + "When passed as positional arguments to <code>print()</code> or <code>fail()"
              + "</code>, tag instance objects turn into a meaningful string representation of the"
              + " form \"'install' tag at /home/user/workspace/MODULE.bazel:3:4\". This can be used"
              + " to construct error messages that point to the location of the tag in the module"
              + " file, e.g. <code>fail(\"Conflict between\", tag1, \"and\", tag2)</code>.")
  static class Tags implements Structure {
    private final ImmutableMap<String, StarlarkList<TypeCheckedTag>> typeCheckedTags;

    private Tags(Map<String, StarlarkList<TypeCheckedTag>> typeCheckedTags) {
      this.typeCheckedTags = ImmutableMap.copyOf(typeCheckedTags);
    }

    @Override
    public boolean isImmutable() {
      return true;
    }

    @Nullable
    @Override
    public Object getValue(String name) throws EvalException {
      return typeCheckedTags.get(name);
    }

    @Override
    public ImmutableCollection<String> getFieldNames() {
      return typeCheckedTags.keySet();
    }

    @Nullable
    @Override
    public String getErrorMessageForUnknownField(String field) {
      return "unknown tag class " + field;
    }
  }

  private StarlarkBazelModule(String name, String version, Tags tags, boolean isRootModule) {
    this.name = name;
    this.version = version;
    this.tags = tags;
    this.isRootModule = isRootModule;
  }

  /**
   * Creates a new {@link StarlarkBazelModule} object representing the given {@link AbridgedModule},
   * with its scope limited to the given {@link ModuleExtension}. It'll be populated with the tags
   * present in the given {@link ModuleExtensionUsage}. Any labels present in tags will be converted
   * using the given {@link RepositoryMapping}.
   */
  public static StarlarkBazelModule create(
      AbridgedModule module,
      ModuleExtension extension,
      RepositoryMapping repoMapping,
      @Nullable ModuleExtensionUsage usage,
      Label.RepoMappingRecorder repoMappingRecorder)
      throws ExternalDepsException {
    LabelConverter labelConverter =
        new LabelConverter(
            PackageIdentifier.create(repoMapping.ownerRepo(), PathFragment.EMPTY_FRAGMENT),
            repoMapping,
            repoMappingRecorder);
    ImmutableList<Tag> tags = usage == null ? ImmutableList.of() : usage.getTags();
    HashMap<String, ArrayList<TypeCheckedTag>> typeCheckedTags = new HashMap<>();
    for (String tagClassName : extension.getTagClasses().keySet()) {
      typeCheckedTags.put(tagClassName, new ArrayList<>());
    }
    for (Tag tag : tags) {
      TagClass tagClass = extension.getTagClasses().get(tag.getTagName());
      if (tagClass == null) {
        throw ExternalDepsException.withMessage(
            Code.BAD_MODULE,
            "The module extension defined at %s does not have a tag class named %s, but its use is"
                + " attempted at %s%s",
            extension.getLocation(),
            tag.getTagName(),
            tag.getLocation(),
            SpellChecker.didYouMean(tag.getTagName(), extension.getTagClasses().keySet()));
      }

      // Now we need to type-check the attribute values and convert them into "build language types"
      // (for example, String to Label).
      typeCheckedTags
          .get(tag.getTagName())
          .add(
              TypeCheckedTag.create(
                  tagClass, tag, labelConverter, module.getKey().toDisplayString()));
    }
    return new StarlarkBazelModule(
        module.getName(),
        module.getVersion().getNormalized(),
        new Tags(Maps.transformValues(typeCheckedTags, StarlarkList::immutableCopyOf)),
        module.getKey().equals(ModuleKey.ROOT));
  }

  @Override
  public boolean isImmutable() {
    return true;
  }

  @StarlarkMethod(name = "name", structField = true, doc = "The name of the module.")
  public String getName() {
    return name;
  }

  @StarlarkMethod(name = "version", structField = true, doc = "The version of the module.")
  public String getVersion() {
    return version;
  }

  @StarlarkMethod(
      name = "tags",
      structField = true,
      doc = "The tags in the module related to the module extension currently being processed.")
  public Tags getTags() {
    return tags;
  }

  @StarlarkMethod(
      name = "is_root",
      structField = true,
      doc = "Whether this module is the root module.")
  public boolean isRoot() {
    return isRootModule;
  }
}
