# Copyright 2024 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Factory class for generating artifacts which are used as intermediate output."""

load(":common/cc/cc_common.bzl", "cc_common")
load(":common/paths.bzl", "paths")

objc_internal = _builtins.internal.objc_internal

def _declare_file_with_extension(ctx, extension):
    return ctx.actions.declare_file(ctx.label.name + extension)

def _create_combined_architecture_archive(ctx):
    return _declare_file_with_extension(ctx, "_lipo.a")

def _create_archive(ctx, enforce_always_link, archive_file_name_suffix):
    extension = ".lo" if enforce_always_link else (
        ".lo" if ctx.fragments.objc.target_should_alwayslink(ctx) else ".a"
    )
    return ctx.actions.declare_file(
        "lib" +
        paths.basename(ctx.label.name) +
        archive_file_name_suffix +
        extension,
    )

def _get_module_name(ctx):
    if hasattr(ctx.attr, "module_name") and ctx.attr.module_name != "":
        return ctx.attr.module_name
    return (
        str(ctx.label)
            .replace("//", "")
            .replace("@", "")
            .replace("-", "_")
            .replace("/", "_")
            .replace(":", "_")
    )

def _swift_module_map(ctx, generate_umbrella_header):
    module_name = _get_module_name(ctx)
    custom_module_map = getattr(ctx.attr, "module_map", None)
    return cc_common.create_module_map(
        file = custom_module_map if custom_module_map else _declare_file_with_extension(
            ctx,
            ".modulemaps/module.modulemap",
        ),
        umbrella_header = _declare_file_with_extension(
            ctx,
            ".modulemaps/umbrella.h",
        ) if generate_umbrella_header else None,
        name = module_name,
    )

def _internal_module_map(ctx):
    return cc_common.create_module_map(
        file = _declare_file_with_extension(ctx, ".internal.cppmap"),
        name = str(ctx.label),
    )

def _create_closure_struct(ctx, archive_file_name_suffix, generate_umbrella_header, enforce_always_link):
    return struct(
        archive_file_name_suffix = archive_file_name_suffix,
        # TODO(b/331163027): Consider renaming publicly to "create_combined_architecture_archive".
        # Alteratively, consider deleting this method as it is not used anywhere in the repo.
        combined_architecture_archive = lambda: _create_combined_architecture_archive(ctx),
        swift_module_map = lambda: _swift_module_map(ctx, generate_umbrella_header),
        internal_module_map = lambda: _internal_module_map(ctx),
        # TODO(b/331163027): Consider renaming publicly to "create_archive".
        archive = lambda: _create_archive(ctx, enforce_always_link, archive_file_name_suffix),
    )

def create_intermediate_artifacts(ctx):
    return _create_closure_struct(
        ctx = ctx,
        archive_file_name_suffix = "",
        generate_umbrella_header = False,
        enforce_always_link = False,
    )
