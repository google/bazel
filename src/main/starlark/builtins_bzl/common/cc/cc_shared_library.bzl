# Copyright 2021 The Bazel Authors. All rights reserved.
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

"""Implementation of cc_shared_library"""

load(":common/cc/cc_common.bzl", "cc_common")
load(":common/cc/cc_helper.bzl", "cc_helper")
load(":common/cc/cc_info.bzl", "CcInfo")
load(":common/cc/cc_shared_library_hint_info.bzl", "CcSharedLibraryHintInfo")
load(":common/cc/semantics.bzl", "semantics")
load(":common/paths.bzl", "paths")
load(":common/proto/proto_info.bzl", _BuiltinProtoInfo = "ProtoInfo")

_external_proto_infos = [[k] for k in _builtins.toplevel.proto_common_do_not_use.external_proto_infos()]

# TODO(#5200): Add export_define to library_to_link and cc_library

# Add this as a tag to any target that can be linked by more than one
# cc_shared_library because it doesn't have static initializers or anything
# else that may cause issues when being linked more than once. This should be
# used sparingly after making sure it's safe to use.
LINKABLE_MORE_THAN_ONCE = "LINKABLE_MORE_THAN_ONCE"

GraphNodeInfo = provider(
    "Nodes in the graph of shared libraries.",
    fields = {
        "children": "Other GraphNodeInfo from dependencies of this target",
        "owners": "Owners of the linker inputs in the targets visited",
        "linkable_more_than_once": "Linkable into more than a single cc_shared_library",
    },
)
CcSharedLibraryInfo = provider(
    "Information about a cc shared library.",
    fields = {
        "dynamic_deps": "All shared libraries depended on transitively",
        "exports": "cc_libraries that are linked statically and exported",
        "link_once_static_libs": "All libraries linked statically into this library that should " +
                                 "only be linked once, e.g. because they have static " +
                                 "initializers. If we try to link them more than once, " +
                                 "we will throw an error",
        "linker_input": "the resulting linker input artifact for the shared library",
    },
)

def _programmatic_error(message = ""):
    fail("Your build has triggered a programmatic error in the cc_shared_library rule. " +
         "Please file an issue in https://github.com/bazelbuild/bazel : {}".format(message))

def _sort_linker_inputs(topologically_sorted_labels, label_to_linker_inputs, linker_inputs_count):
    # len(label_to_linker_inputs) might not match the topologically_sorted_labels
    # size. The latter is looking at nodes in the graph but a node may not
    # necessarily contribute any linker_inputs. For example a cc_library with
    # no sources and only deps. Every linker_input.owner must be in
    # topologically_sorted_labels otherwise there is an error in the rule
    # implementation of the target providing that linker_input, e.g. it's
    # missing a CcSharedLibraryHintInfo if it had custom owner names for linker
    # inputs.
    sorted_linker_inputs = []
    for label in topologically_sorted_labels:
        if label not in label_to_linker_inputs:
            # This is ok. It can happen if no linker_inputs
            # were added by a node in the graph.
            continue
        sorted_linker_inputs.extend(label_to_linker_inputs[label])

    if len(sorted_linker_inputs) != linker_inputs_count:
        owners = []
        for sorted_linker_input in sorted_linker_inputs:
            owners.append(str(sorted_linker_input.owner))
        _programmatic_error("{} vs {}".format(",".join(owners), linker_inputs_count))

    return sorted_linker_inputs

# For each target, find out whether it should be linked statically or
# dynamically. The transitive_dynamic_dep_labels parameter is only needed for
# binaries because they link all dynamic_deps (cc_binary|cc_test).
def _separate_static_and_dynamic_link_libraries(
        dynamic_deps,
        deps_graph_nodes,
        can_be_linked_dynamically):
    (
        transitive_dynamic_dep_labels,
        all_dynamic_dep_linker_inputs,
    ) = _build_map_direct_dynamic_dep_to_transitive_dynamic_deps(dynamic_deps)

    node = None
    deps_graph_nodes_to_process = reversed(deps_graph_nodes)
    targets_to_be_linked_statically_map = {}
    targets_to_be_linked_dynamically_set = {}
    seen_labels = {}

    # The cc_shared_library graph is parallel to the cc_library graph.
    # Propagation of linker inputs between cc_libraries happens via the CcInfo
    # provider. Parallel to this we have cc_shared_libraries which may decide
    # different partitions of the cc_library graph.
    #
    # In a previous implementation of cc_shared_library we relied on the
    # topological sort given by flattening
    # cc_info.linking_context.linker_inputs.to_list(), however this was wrong
    # because the dependencies of a shared library (i.e. a pruned node here)
    # influenced the final order.
    #
    # In order to fix this, the pruning below was changed from breadth-first
    # traversal to depth-first traversal. While doing this we also recreate a
    # depset with topological order that takes into account the pruned nodes
    # and which will later be used to order the libraries in the linking
    # command line. This will be in topological order and will respect the
    # order of the deps as listed on the BUILD file as much as possible.
    #
    # Here we say "first_owner" because each node (see GraphNodeInfo) may have
    # more than one linker_input (each potentially with a different owner) but
    # using only the first owner as a key is enough.
    first_owner_to_depset = {}

    # Horrible I know. Perhaps Starlark team gives me a way to prune a tree.
    for i in range(2147483647):
        if not len(deps_graph_nodes_to_process):
            break

        node = deps_graph_nodes_to_process[-1]

        must_add_children = False

        # The *_seen variables are used to track a programmatic error and fail
        # if it happens.  Every value in node.owners presumably corresponds to
        # a linker_input in the same exact target. Therefore if we have seen
        # any of the owners already, then we must have also seen all the other
        # owners in the same node. Viceversa when we haven't seen them yet. If
        # both of these values are non-zero after the loop, the most likely
        # reason would be a bug in the implementation. It could potentially be
        # triggered by users if they use owner labels that do not keep most of
        # the ctx.label.package and ctx.label.name which then clash with other
        # target's owners (unlikely). For now though if the error is
        # triggered, it's reasonable to require manual revision by
        # the cc_shared_library implementation owners.
        has_owners_seen = False
        has_owners_not_seen = False
        linked_dynamically = False
        linked_statically = False
        for owner in node.owners:
            # TODO(bazel-team): Do not convert Labels to string to save on
            # garbage string allocations.
            owner_str = str(owner)

            if owner_str in seen_labels:
                has_owners_seen = True
                continue

            has_owners_not_seen = True
            seen_labels[owner_str] = True

            if owner_str in can_be_linked_dynamically:
                targets_to_be_linked_dynamically_set[owner_str] = True
                linked_dynamically = True
            else:
                targets_to_be_linked_statically_map[owner_str] = node.linkable_more_than_once
                must_add_children = True
                linked_statically = True

        if has_owners_seen and has_owners_not_seen:
            _programmatic_error()

        if linked_dynamically and linked_statically:
            error_owners_list = [str(owner) for owner in node.owners]

            # Our granularity is target level. Unless there is a different
            # unsupported custom implementation of this rule it should be
            # impossible for two linker_inputs from the same target to be
            # linked differently, one statically and the other dynamically.
            _programmatic_error(
                message = "Nodes with linker_inputs linked statically and dynamically:" +
                          "\n{}".format("\n".join(error_owners_list)),
            )

        if must_add_children:
            # The order in which we process the children matter. all_children
            # is being used as a stack, we will process first the nodes at the
            # top of the stack (last in the list). The children are the
            # dependencies of the current node, in order to respect the order
            # in which dependencies were listed in the deps attribute in the
            # BUILD file we must reverse the list so that the first one listed
            # in the BUILD file is processed first.
            deps_graph_nodes_to_process.extend(reversed(node.children))
        else:
            if node.owners[0] not in first_owner_to_depset:
                # We have 3 cases in this branch:
                #   1. Node has no children
                #   2. The children have been pruned because the node is linked dynamically
                #   3. Node has children that have been processed
                # For case 3 we add the children's depsets. For case 2 we add the dynamic
                # dep labels for transitive dynamic deps.
                transitive = []
                if str(node.owners[0]) in targets_to_be_linked_statically_map:
                    for child in node.children:
                        transitive.append(first_owner_to_depset[child.owners[0]])
                elif str(node.owners[0]) in transitive_dynamic_dep_labels:
                    transitive.append(transitive_dynamic_dep_labels[str(node.owners[0])])

                first_owner_to_depset[node.owners[0]] = depset(direct = node.owners, transitive = transitive, order = "topological")
            deps_graph_nodes_to_process.pop()

    topologically_sorted_labels = []
    if deps_graph_nodes:
        transitive = []
        for child in deps_graph_nodes:
            transitive.append(first_owner_to_depset[child.owners[0]])
        topologically_sorted_labels = depset(transitive = transitive, order = "topological").to_list()

    return (targets_to_be_linked_statically_map, targets_to_be_linked_dynamically_set, topologically_sorted_labels, all_dynamic_dep_linker_inputs)

def _create_linker_context(ctx, linker_inputs):
    return cc_common.create_linking_context(
        linker_inputs = depset(linker_inputs, order = "topological"),
    )

def _merge_cc_shared_library_infos(ctx):
    dynamic_deps = []
    transitive_dynamic_deps = []
    for dep in ctx.attr.dynamic_deps:
        dynamic_dep_entry = struct(
            exports = dep[CcSharedLibraryInfo].exports,
            linker_input = dep[CcSharedLibraryInfo].linker_input,
            link_once_static_libs = dep[CcSharedLibraryInfo].link_once_static_libs,
        )
        dynamic_deps.append(dynamic_dep_entry)
        transitive_dynamic_deps.append(dep[CcSharedLibraryInfo].dynamic_deps)

    return depset(direct = dynamic_deps, transitive = transitive_dynamic_deps, order = "topological")

def _build_exports_map_from_only_dynamic_deps(merged_cc_shared_library_infos_list):
    exports_map = {}
    for entry in merged_cc_shared_library_infos_list:
        exports = entry.exports
        linker_input = entry.linker_input
        for export in exports:
            if export in exports_map:
                fail("Two shared libraries in dependencies export the same symbols. Both " +
                     exports_map[export].libraries[0].dynamic_library.short_path +
                     " and " + linker_input.libraries[0].dynamic_library.short_path +
                     " export " + export)
            exports_map[export] = linker_input
    return exports_map

# The map points from the target that can only be linked once to the
# cc_shared_library target that already links it.
def _build_link_once_static_libs_map(merged_cc_shared_library_infos_list):
    link_once_static_libs_map = {}
    for entry in merged_cc_shared_library_infos_list:
        link_once_static_libs = entry.link_once_static_libs
        linker_input = entry.linker_input
        for static_lib in link_once_static_libs:
            if static_lib in link_once_static_libs_map:
                fail("Two shared libraries in dependencies link the same " +
                     " library statically. Both " + link_once_static_libs_map[static_lib] +
                     " and " + str(linker_input.owner) +
                     " link statically " + static_lib)
            link_once_static_libs_map[static_lib] = str(linker_input.owner)
    return link_once_static_libs_map

def _is_dynamic_only(library_to_link):
    if (library_to_link.static_library == None and
        library_to_link.pic_static_library == None and
        (library_to_link.objects == None or len(library_to_link.objects) == 0) and
        (library_to_link.pic_objects == None or len(library_to_link.pic_objects) == 0)):
        return True
    return False

def _wrap_static_library_with_alwayslink(ctx, feature_configuration, cc_toolchain, linker_input):
    new_libraries_to_link = []
    for old_library_to_link in linker_input.libraries:
        if _is_dynamic_only(old_library_to_link):
            new_libraries_to_link.append(old_library_to_link)
            continue
        new_library_to_link = cc_common.create_library_to_link(
            actions = ctx.actions,
            feature_configuration = feature_configuration,
            cc_toolchain = cc_toolchain,
            static_library = old_library_to_link.static_library,
            objects = old_library_to_link.objects,
            pic_static_library = old_library_to_link.pic_static_library,
            pic_objects = old_library_to_link.pic_objects,
            alwayslink = True,
        )
        new_libraries_to_link.append(new_library_to_link)

    return cc_common.create_linker_input(
        owner = linker_input.owner,
        libraries = depset(direct = new_libraries_to_link),
        user_link_flags = depset(direct = linker_input.user_link_flags),
        additional_inputs = depset(direct = linker_input.additional_inputs),
    )

def _check_if_target_under_path(value, pattern):
    if pattern.workspace_name != value.workspace_name:
        return False
    if pattern.name == "__pkg__":
        return pattern.package == value.package
    if pattern.name == "__subpackages__":
        return _same_package_or_above(pattern, value)

    return pattern.package == value.package and pattern.name == value.name

def _check_if_target_should_be_exported_with_filter(target, current_label, exports_filter):
    for export_filter in exports_filter:
        export_filter_label = current_label.relative(export_filter)
        if _check_if_target_under_path(target, export_filter_label):
            return True

    return False

# Checks if the linker_input has code to link statically, i.e. either
# archives or object files, ignores library.dynamic_library.
def _contains_code_to_link(linker_input):
    for library in linker_input.libraries:
        if (library.static_library != None or
            library.pic_static_library != None or
            len(library.objects) or len(library.pic_objects)):
            return True

    return False

def _find_top_level_linker_input_labels(
        deps_graph_nodes,
        linker_inputs_to_be_linked_statically_map,
        targets_to_be_linked_dynamically_set):
    top_level_linker_input_labels_set = {}
    nodes_to_check = list(deps_graph_nodes)

    seen_nodes_set = {}
    for i in range(2147483647):
        if i == len(nodes_to_check):
            break

        node = nodes_to_check[i]
        must_add_children = False
        node_str_owners = []
        for owner in node.owners:
            owner_str = str(owner)
            node_str_owners.append(owner_str)
            if owner_str in linker_inputs_to_be_linked_statically_map:
                must_add_children = True
                for linker_input in linker_inputs_to_be_linked_statically_map[owner_str]:
                    if _contains_code_to_link(linker_input):
                        top_level_linker_input_labels_set[owner_str] = True
                        must_add_children = False
                        break
            elif owner_str not in targets_to_be_linked_dynamically_set:
                # This can happen when there was a target in the graph that exported other libraries'
                # linker_inputs but didn't contribute any linker_input of its own.
                must_add_children = True

        node_key = "".join(node_str_owners)
        if must_add_children and node_key not in seen_nodes_set:
            nodes_to_check.extend(node.children)
            seen_nodes_set[node_key] = True

    return top_level_linker_input_labels_set

def _filter_inputs(
        ctx,
        feature_configuration,
        cc_toolchain,
        deps,
        transitive_exports,
        link_once_static_libs_map):
    curr_link_once_static_libs_set = {}

    deps_root_tree_nodes = []
    dependency_linker_inputs_sets = []
    direct_deps_set = {}
    for dep in deps:
        direct_deps_set[str(dep.label)] = True
        dependency_linker_inputs_sets.append(dep[CcInfo].linking_context.linker_inputs)
        deps_root_tree_nodes.append(dep[GraphNodeInfo])

    dependency_linker_inputs = depset(transitive = dependency_linker_inputs_sets, order = "topological").to_list()

    can_be_linked_dynamically = {}
    for linker_input in dependency_linker_inputs:
        owner = str(linker_input.owner)
        if owner in transitive_exports:
            can_be_linked_dynamically[owner] = True

    # The targets_to_be_linked_statically_map points to whether the target to
    # be linked statically can be linked more than once.
    # Entries in unused_dynamic_linker_inputs will be marked None if they are
    # used
    (
        targets_to_be_linked_statically_map,
        targets_to_be_linked_dynamically_set,
        topologically_sorted_labels,
        unused_dynamic_linker_inputs,
    ) = _separate_static_and_dynamic_link_libraries(
        ctx.attr.dynamic_deps,
        deps_root_tree_nodes,
        can_be_linked_dynamically,
    )

    linker_inputs_to_be_linked_statically_map = {}
    for linker_input in dependency_linker_inputs:
        owner = str(linker_input.owner)
        if owner in targets_to_be_linked_statically_map:
            linker_inputs_to_be_linked_statically_map.setdefault(owner, []).append(linker_input)

    top_level_linker_input_labels_set = _find_top_level_linker_input_labels(
        deps_root_tree_nodes,
        linker_inputs_to_be_linked_statically_map,
        targets_to_be_linked_dynamically_set,
    )

    # We keep track of precompiled_only_dynamic_libraries, so that we can add
    # them to runfiles.
    precompiled_only_dynamic_libraries = []
    exports = {}
    linker_inputs_seen = {}
    linker_inputs_count = 0
    label_to_linker_inputs = {}
    experimental_remove_before_7_0_linker_inputs = []

    def _add_linker_input_to_dict(owner, linker_input):
        experimental_remove_before_7_0_linker_inputs.append(linker_input)
        label_to_linker_inputs.setdefault(owner, []).append(linker_input)

    # We use this dictionary to give an error if a target containing only
    # precompiled dynamic libraries is placed directly in roots. If such a
    # precompiled dynamic library is needed it would be because a target in the
    # parallel cc_library graph actually needs it. Therefore the precompiled
    # dynamic library should be made a dependency of that cc_library instead.
    dynamic_only_roots = {}
    linked_statically_but_not_exported = {}
    for linker_input in dependency_linker_inputs:
        stringified_linker_input = cc_helper.stringify_linker_input(linker_input)
        if stringified_linker_input in linker_inputs_seen:
            continue
        linker_inputs_seen[stringified_linker_input] = True
        owner = str(linker_input.owner)
        if semantics.is_bazel and not linker_input.libraries:
            # Linker inputs that only provide flags, no code, are considered
            # safe to link statically multiple times.
            # TODO(bazel-team): semantics.should_create_empty_archive() should be
            # cleaned up and return False in every case. cc_libraries shouldn't
            # produce empty archives. For now issue #19920 is only fixed in Bazel.
            _add_linker_input_to_dict(linker_input.owner, linker_input)
            linker_inputs_count += 1
        elif owner in targets_to_be_linked_dynamically_set:
            unused_dynamic_linker_inputs[transitive_exports[owner].owner] = None

            # Link the library in this iteration dynamically,
            # transitive_exports contains the artifacts produced by a
            # cc_shared_library
            _add_linker_input_to_dict(linker_input.owner, transitive_exports[owner])
            linker_inputs_count += 1
        elif owner in targets_to_be_linked_statically_map:
            if owner in link_once_static_libs_map:
                # We are building a dictionary that will allow us to give
                # proper errors for libraries that have been linked multiple
                # times elsewhere but haven't been exported. The values in the
                # link_once_static_libs_map dictionary are the
                # cc_shared_library targets. In this iteration we know of at
                # least one target (i.e. owner) which is being linked
                # statically by the cc_shared_library
                # link_once_static_libs_map[owner] but is not being exported
                linked_statically_but_not_exported.setdefault(link_once_static_libs_map[owner], []).append(owner)

            dynamic_only_libraries = []
            static_libraries = []
            for library in linker_input.libraries:
                if _is_dynamic_only(library):
                    dynamic_only_libraries.append(library)
                else:
                    static_libraries.append(library)

            if len(dynamic_only_libraries):
                precompiled_only_dynamic_libraries.extend(dynamic_only_libraries)
                if not len(static_libraries):
                    if owner in direct_deps_set:
                        dynamic_only_roots[owner] = True
                    _add_linker_input_to_dict(linker_input.owner, linker_input)
                    linker_inputs_count += 1
                    continue
            if len(static_libraries) and owner in dynamic_only_roots:
                dynamic_only_roots.pop(owner)

            linker_input_to_be_linked_statically = linker_input
            if owner in top_level_linker_input_labels_set:
                linker_input_to_be_linked_statically = _wrap_static_library_with_alwayslink(
                    ctx,
                    feature_configuration,
                    cc_toolchain,
                    linker_input,
                )
            if _check_if_target_should_be_exported_with_filter(
                linker_input.owner,
                ctx.label,
                ctx.attr.exports_filter,
            ):
                exports[owner] = True

            _add_linker_input_to_dict(linker_input.owner, linker_input_to_be_linked_statically)
            linker_inputs_count += 1

            if not targets_to_be_linked_statically_map[owner]:
                curr_link_once_static_libs_set[owner] = True

    if dynamic_only_roots:
        message = ("Do not place libraries which only contain a " +
                   "precompiled dynamic library in roots. The following " +
                   "libraries only have precompiled dynamic libraries:\n")
        for dynamic_only_root in dynamic_only_roots:
            message += dynamic_only_root + "\n"
        fail(message)

    linker_inputs_count += _add_unused_dynamic_deps(ctx, unused_dynamic_linker_inputs, _add_linker_input_to_dict, topologically_sorted_labels, link_indirect_deps = False)

    linker_inputs = _sort_linker_inputs(
        topologically_sorted_labels,
        label_to_linker_inputs,
        linker_inputs_count,
    )

    _throw_linked_but_not_exported_errors(linked_statically_but_not_exported)
    return (exports, linker_inputs, curr_link_once_static_libs_set.keys(), precompiled_only_dynamic_libraries)

def _throw_linked_but_not_exported_errors(error_libs_dict):
    if not error_libs_dict:
        return

    error_builder = ["The following libraries were linked statically by different cc_shared_libraries but not exported:\n"]
    for cc_shared_library_target, error_libs in error_libs_dict.items():
        error_builder.append("cc_shared_library %s:\n" % str(cc_shared_library_target))
        for error_lib in error_libs:
            error_builder.append("  \"%s\",\n" % str(error_lib))

    error_builder.append("If you are sure that the previous libraries are exported by the cc_shared_libraries because:\n")
    error_builder.append("  1. You have visibility declarations in the source code\n")
    error_builder.append("  2. Or you are passing a visibility script to the linker to export symbols from them\n")
    error_builder.append("then add those libraries to roots or exports_filter for each cc_shared_library.\n")

    fail("".join(error_builder))

def _same_package_or_above(label_a, label_b):
    if label_a.workspace_name != label_b.workspace_name:
        return False
    package_a_tokenized = label_a.package.split("/")
    package_b_tokenized = label_b.package.split("/")
    if len(package_b_tokenized) < len(package_a_tokenized):
        return False

    if package_a_tokenized[0] != "":
        for i in range(len(package_a_tokenized)):
            if package_a_tokenized[i] != package_b_tokenized[i]:
                return False

    return True

def _get_deps(ctx):
    if len(ctx.attr.deps) and len(ctx.attr.roots):
        fail(
            "You are using the attribute 'roots' and 'deps'. 'deps' is the " +
            "new name for the attribute 'roots'. The attribute 'roots' will be" +
            "removed in the future",
            attr = "roots",
        )

    deps = ctx.attr.deps
    if not len(deps):
        deps = ctx.attr.roots

    if len(deps) == 0:
        fail(
            "'cc_shared_library' must have at least one dependency in 'deps' (or 'roots')",
            attr = "deps",
        )

    return deps

def _build_map_direct_dynamic_dep_to_transitive_dynamic_deps(direct_dynamic_deps):
    all_dynamic_dep_linker_inputs = {}
    direct_dynamic_dep_to_transitive_dynamic_deps = {}
    for dep in direct_dynamic_deps:
        owner = dep[CcSharedLibraryInfo].linker_input.owner
        all_dynamic_dep_linker_inputs[owner] = dep[CcSharedLibraryInfo].linker_input
        transitive_dynamic_dep_labels = []
        for dynamic_dep in dep[CcSharedLibraryInfo].dynamic_deps.to_list():
            all_dynamic_dep_linker_inputs[dynamic_dep.linker_input.owner] = dynamic_dep.linker_input
            transitive_dynamic_dep_labels.append(dynamic_dep.linker_input.owner)
        transitive_dynamic_dep_labels_set = depset(transitive_dynamic_dep_labels, order = "topological")
        for export in dep[CcSharedLibraryInfo].exports:
            direct_dynamic_dep_to_transitive_dynamic_deps[export] = transitive_dynamic_dep_labels_set

    return direct_dynamic_dep_to_transitive_dynamic_deps, all_dynamic_dep_linker_inputs

def _add_unused_dynamic_deps(ctx, unused_dynamic_linker_inputs, add_linker_inputs_lambda, topologically_sorted_labels, link_indirect_deps):
    linker_inputs_count = 0
    direct_dynamic_dep_labels = {dep[CcSharedLibraryInfo].linker_input.owner: True for dep in ctx.attr.dynamic_deps}
    topologically_sorted_labels_set = {label: True for label in topologically_sorted_labels}
    for dynamic_linker_input_owner, unused_linker_input in unused_dynamic_linker_inputs.items():
        should_link_input = (unused_linker_input and
                             (link_indirect_deps or dynamic_linker_input_owner in direct_dynamic_dep_labels))
        if should_link_input:
            add_linker_inputs_lambda(
                dynamic_linker_input_owner,
                unused_dynamic_linker_inputs[dynamic_linker_input_owner],
            )
            linker_inputs_count += 1
            if dynamic_linker_input_owner not in topologically_sorted_labels_set:
                topologically_sorted_labels.append(dynamic_linker_input_owner)
    return linker_inputs_count

def _cc_shared_library_impl(ctx):
    if not cc_common.check_experimental_cc_shared_library():
        if len(ctx.attr.static_deps):
            fail(
                "This attribute is a no-op and its usage" +
                " is forbidden after cc_shared_library is no longer experimental. " +
                "Remove it from every cc_shared_library target",
                attr = "static_deps",
            )
        if len(ctx.attr.roots):
            fail(
                "This attribute has been renamed to 'deps'. Simply rename the" +
                " attribute on the target.",
                attr = "roots",
            )

    deps = _get_deps(ctx)

    cc_toolchain = cc_helper.find_cpp_toolchain(ctx)
    feature_configuration = cc_common.configure_features(
        ctx = ctx,
        cc_toolchain = cc_toolchain,
        requested_features = ctx.features + ["force_no_whole_archive"],
        unsupported_features = ctx.disabled_features,
    )

    merged_cc_shared_library_infos = _merge_cc_shared_library_infos(ctx)

    # Small performance tweak to avoid flattening merged_cc_shared_library_infos twice:
    merged_cc_shared_library_infos_list = merged_cc_shared_library_infos.to_list()
    exports_map = _build_exports_map_from_only_dynamic_deps(merged_cc_shared_library_infos_list)
    for export in deps:
        # Do not check for overlap between targets matched by the current
        # rule's exports_filter and what is in exports_map. A library in roots
        # will have to be linked in statically into the current rule with 100%
        # guarantee and it will also have to be exported. Therefore, we must
        # check it's not already exported by a different shared library. On the
        # other hand, a library in the transitive closure of the current rule
        # may be matched by the exports_filter but if it's already exported by
        # a dynamic_dep then it won't be linked statically (therefore not give
        # an error either) in the current target. The rule will intentionally
        # not throw an error in these cases.
        if str(export.label) in exports_map:
            fail("Trying to export a library already exported by a different shared library: " +
                 str(export.label))

    link_once_static_libs_map = _build_link_once_static_libs_map(merged_cc_shared_library_infos_list)

    (exports, linker_inputs, curr_link_once_static_libs_set, precompiled_only_dynamic_libraries) = _filter_inputs(
        ctx,
        feature_configuration,
        cc_toolchain,
        deps,
        exports_map,
        link_once_static_libs_map,
    )

    linking_context = _create_linker_context(ctx, linker_inputs)

    user_link_flags = []
    for user_link_flag in ctx.attr.user_link_flags:
        user_link_flags.append(ctx.expand_location(user_link_flag, targets = ctx.attr.additional_linker_inputs))

    main_output = None
    if ctx.attr.shared_lib_name:
        main_output = ctx.actions.declare_file(ctx.attr.shared_lib_name)

    additional_inputs = []
    additional_outputs = []
    link_variables = {}
    additional_output_groups = {}

    pdb_file = None
    if cc_common.is_enabled(feature_configuration = feature_configuration, feature_name = "generate_pdb_file"):
        if ctx.attr.shared_lib_name:
            pdb_file = ctx.actions.declare_file(paths.replace_extension(ctx.attr.shared_lib_name, ".pdb"))
        else:
            pdb_file = ctx.actions.declare_file(ctx.label.name + ".pdb")
        additional_outputs.append(pdb_file)
        additional_output_groups["pdb_file"] = depset([pdb_file])

    if cc_common.is_enabled(feature_configuration = feature_configuration, feature_name = "targets_windows"):
        object_files = []
        for linker_input in linking_context.linker_inputs.to_list():
            for library in linker_input.libraries:
                if library.pic_static_library != None:
                    if library.pic_objects != None:
                        object_files.extend(library.pic_objects)
                elif library.static_library != None:
                    if library.objects != None:
                        object_files.extend(library.objects)

        def_parser = ctx.file._def_parser

        generated_def_file = None
        if def_parser != None:
            generated_def_file = cc_helper.generate_def_file(ctx, def_parser, object_files, ctx.label.name)
            additional_output_groups["def_file"] = depset([generated_def_file])
        custom_win_def_file = ctx.file.win_def_file
        win_def_file = cc_helper.get_windows_def_file_for_linking(ctx, custom_win_def_file, generated_def_file, feature_configuration)
        link_variables["def_file_path"] = win_def_file.path
        additional_inputs.append(win_def_file)

    additional_inputs.extend(ctx.files.additional_linker_inputs)
    linking_outputs = cc_common.link(
        actions = ctx.actions,
        feature_configuration = feature_configuration,
        cc_toolchain = cc_toolchain,
        linking_contexts = [linking_context],
        user_link_flags = user_link_flags,
        additional_inputs = additional_inputs,
        name = ctx.label.name,
        output_type = "dynamic_library",
        main_output = main_output,
        variables_extension = link_variables,
        additional_outputs = additional_outputs,
    )

    runfiles_files = []
    if linking_outputs.library_to_link.resolved_symlink_dynamic_library != None:
        runfiles_files.append(linking_outputs.library_to_link.resolved_symlink_dynamic_library)

    # This is different to cc_binary(linkshared=1). Bazel never handles the
    # linking implicitly for a cc_binary(linkshared=1) but it does so for a cc_shared_library,
    # for which it will use the symlink in the solib directory. If we don't add it, a dependent
    # linked against it would fail.
    runfiles_files.append(linking_outputs.library_to_link.dynamic_library)
    runfiles = ctx.runfiles(
        files = runfiles_files,
    )
    for dep in ctx.attr.dynamic_deps:
        runfiles = runfiles.merge(dep[DefaultInfo].data_runfiles)

    precompiled_only_dynamic_libraries_runfiles = []
    for precompiled_dynamic_library in precompiled_only_dynamic_libraries:
        # precompiled_dynamic_library.dynamic_library could be None if the library to link just contains
        # an interface library which is valid if the actual library is obtained from the system.
        if precompiled_dynamic_library.dynamic_library != None:
            precompiled_only_dynamic_libraries_runfiles.append(precompiled_dynamic_library.dynamic_library)

    runfiles = runfiles.merge(ctx.runfiles(files = precompiled_only_dynamic_libraries_runfiles))

    for export in deps:
        exports[str(export.label)] = True

    if not semantics.get_experimental_link_static_libraries_once(ctx):
        curr_link_once_static_libs_set = {}

    library = []
    if linking_outputs.library_to_link.resolved_symlink_dynamic_library != None:
        library.append(linking_outputs.library_to_link.resolved_symlink_dynamic_library)
    else:
        library.append(linking_outputs.library_to_link.dynamic_library)

    interface_library = []
    if linking_outputs.library_to_link.resolved_symlink_interface_library != None:
        interface_library.append(linking_outputs.library_to_link.resolved_symlink_interface_library)
    elif linking_outputs.library_to_link.interface_library != None:
        interface_library.append(linking_outputs.library_to_link.interface_library)
    else:
        interface_library = library

    return [
        DefaultInfo(
            files = depset(library),
            runfiles = runfiles,
        ),
        OutputGroupInfo(
            main_shared_library_output = depset(library),
            interface_library = depset(interface_library),
            **additional_output_groups
        ),
        CcSharedLibraryInfo(
            dynamic_deps = merged_cc_shared_library_infos,
            exports = exports.keys(),
            link_once_static_libs = curr_link_once_static_libs_set,
            linker_input = cc_common.create_linker_input(
                owner = ctx.label,
                libraries = depset([linking_outputs.library_to_link] + precompiled_only_dynamic_libraries),
            ),
        ),
    ]

def _graph_structure_aspect_impl(target, ctx):
    children = []

    attributes = dir(ctx.rule.attr)
    owners = [ctx.label]
    if CcSharedLibraryHintInfo in target:
        attributes = getattr(target[CcSharedLibraryHintInfo], "attributes", dir(ctx.rule.attr))
        owners = getattr(target[CcSharedLibraryHintInfo], "owners", [ctx.label])

    # Collect graph structure info from any possible deplike attribute. The aspect
    # itself applies across every deplike attribute (attr_aspects is *), so enumerate
    # over all attributes and consume GraphNodeInfo if available.
    for fieldname in attributes:
        deps = getattr(ctx.rule.attr, fieldname, None)
        if type(deps) == "list":
            for dep in deps:
                if type(dep) == "Target" and GraphNodeInfo in dep:
                    children.append(dep[GraphNodeInfo])
        elif type(deps) == "Target" and GraphNodeInfo in deps:
            children.append(deps[GraphNodeInfo])

    # TODO(bazel-team): Add flag to Bazel that can toggle the initialization of
    # linkable_more_than_once.
    linkable_more_than_once = False
    if hasattr(ctx.rule.attr, "tags"):
        for tag in ctx.rule.attr.tags:
            if tag == LINKABLE_MORE_THAN_ONCE:
                linkable_more_than_once = True
    return [GraphNodeInfo(
        owners = owners,
        children = children,
        linkable_more_than_once = linkable_more_than_once,
    )]

graph_structure_aspect = aspect(
    attr_aspects = ["*"],
    required_providers = [[CcInfo], [_BuiltinProtoInfo], [CcSharedLibraryHintInfo]] + _external_proto_infos,
    required_aspect_providers = [[CcInfo], [CcSharedLibraryHintInfo]],
    implementation = _graph_structure_aspect_impl,
)

def _cc_shared_library_initializer(**kwargs):
    """Converts labels in exports_filter into canonical form relative to the current repository.

    This conversion can only be done in a macro as it requires access to the repository mapping of
    the repository containing the cc_shared_library target. This mapping is automatically
    applied to label attributes, but exports_filter is a list of strings attribute.
    """
    if "exports_filter" not in kwargs:
        return kwargs

    raw_exports_filter = kwargs["exports_filter"]
    if type(raw_exports_filter) != type([]):
        # TODO: Also canonicalize labels in selects once macros can operate on them.
        # https://github.com/bazelbuild/bazel/issues/14157
        return kwargs

    canonical_exports_filter = [
        str(_builtins.native.package_relative_label(s))
        for s in raw_exports_filter
    ]
    return kwargs | {"exports_filter": canonical_exports_filter}

cc_shared_library = rule(
    implementation = _cc_shared_library_impl,
    initializer = _cc_shared_library_initializer,
    doc = """
<p>It produces a shared library.</p>

<h4 id="cc_shard_library_examples">Example</h4>

<pre class="code">
cc_shared_library(
    name = "foo_shared",
    deps = [
        ":foo",
    ],
    dynamic_deps = [
        ":bar_shared",
    ],
    additional_linker_inputs = [
        ":foo.lds",
    ],
    user_link_flags = [
        "-Wl,--version-script=$(location :foo.lds)",
    ],
)
cc_library(
    name = "foo",
    srcs = ["foo.cc"],
    hdrs = ["foo.h"],
    deps = [
        ":bar",
        ":baz",
    ],
)
cc_shared_library(
    name = "bar_shared",
    shared_lib_name = "bar.so",
    deps = [":bar"],
)
cc_library(
    name = "bar",
    srcs = ["bar.cc"],
    hdrs = ["bar.h"],
)
cc_library(
    name = "baz",
    srcs = ["baz.cc"],
    hdrs = ["baz.h"],
)
</pre>

<p>In the example <code>foo_shared</code> statically links <code>foo</code>
and <code>baz</code>, the latter being a transitive dependency. It doesn't
link <code>bar</code> because it is already provided dynamically by the
<code>dynamic_dep</code> <code>bar_shared</code>.</p>

<p><code>foo_shared</code> uses a linker script *.lds file to control which
symbols should be exported. The <code>cc_shared_library</code> rule logic does
not control which symbols get exported, it only uses what is assumed to be
exported to give errors during analysis phase if two shared libraries export the
same targets.</p>

<p>Every direct dependency of <code>cc_shared_library</code> is assumed to be
exported. Therefore, Bazel assumes during analysis that <code>foo</code> is being
exported by <code>foo_shared</code>. <code>baz</code> is not assumed to be exported
by <code>foo_shared</code>. Every target matched by the <code>exports_filter</code>
is also assumed to be exported.</p>

<p>Every single <code>cc_library</code> in the example should appear at most in one
<code>cc_shared_library</code>. If we wanted to link <code>baz</code> also into
<code>bar_shared</code> we would need to add
<code>tags = ["LINKABLE_MORE_THAN_ONCE"]</code> to <code>baz</code>.</p>

<p>Due to the <code>shared_lib_name</code> attribute, the file produced by
<code>bar_shared</code> will have the name <code>bar.so</code> as opposed
to the name <code>libbar.so</code> that it would have by default on Linux.</p>

<h4 id="cc_shard_library_examples">Errors</h4>
<h5><code>Two shared libraries in dependencies export the same symbols.</code></h5>

<p>This will happen whenever you are creating a target with two different
<code>cc_shared_library</code> dependencies that export the same target. To fix this
you need to stop the libraries from being exported in one of the
<code>cc_shared_library</code> dependencies.</p>

<h5><code>Two shared libraries in dependencies link the same library statically</code></h5>

<p>This will happen whenever you are creating a new <code>cc_shared_library</code> with two
different <code>cc_shared_library</code> dependencies that link the same target statically.
Similar to the error with exports.</p>

<p>One way to fix this is to stop linking the library into one of the
<code>cc_shared_library</code> dependencies. At the same time, the one that still links it
needs to export the library so that the one not linking it keeps visibility to
the symbols. Another way is to pull out a third library that exports the target.
A third way is to tag the culprit <code>cc_library</code> with <code>LINKABLE_MORE_THAN_ONCE</code>
but this fix should be rare and you should absolutely make sure that the
<code>cc_library</code> is indeed safe to link more than once.</p>

<h5><code>'//foo:foo' is already linked statically in '//bar:bar' but not exported`</code></h5>

<p>This means that a library in the transitive closure of your <code>deps</code> is reachable
without going through one of the <code>cc_shared_library</code> dependencies but is already
linked into a different <code>cc_shared_library</code> in <code>dynamic_deps</code> and is not
exported.</p>

<p>The solution is to export it from the <code>cc_shared_library</code> dependency or pull out
a third <code>cc_shared_library</code> that exports it.</p>

<h5><code>Do not place libraries which only contain a precompiled dynamic library in deps.
</code></h5>

<p>If you have a precompiled dynamic library, this doesn't need to and cannot be
linked statically into the current <code>cc_shared_library</code> target that you are
currently creating. Therefore, it doesn't belong in <code>deps</code> of the
<code>cc_shared_library</code>. If this precompiled dynamic library is a dependency of one
of your <code>cc_libraries</code>, then the <code>cc_library</code> needs to depend on it
directly.</p>

<h5><code>Trying to export a library already exported by a different shared library</code></h5>

<p>You will see this error if on the current rule you are claiming to export a
target that is already being exported by one of your dynamic dependencies.</p>

<p>To fix this, remove the target from <code>deps</code> and just rely on it from the dynamic
dependency or make sure that the <code>exports_filter</code> doesn't catch this target.</p>""",
    attrs = {
        "additional_linker_inputs": attr.label_list(allow_files = True, doc = """
Any additional files that you may want to pass to the linker, for example, linker scripts.
You have to separately pass any linker flags that the linker needs in order to be aware
of this file. You can do so via the <code>user_link_flags</code> attribute."""),
        "shared_lib_name": attr.string(doc = """
By default cc_shared_library will use a name for the shared library output file based on
the target's name and the platform. This includes an extension and sometimes a prefix.
Sometimes you may not want the default name, for example, when loading C++ shared libraries
for Python the default lib* prefix is often not desired, in which case you can use this
attribute to choose a custom name."""),
        "dynamic_deps": attr.label_list(providers = [CcSharedLibraryInfo], doc = """
These are other <code>cc_shared_library</code> dependencies the current target depends on.

<p>
The <code>cc_shared_library</code> implementation will use the list of
<code>dynamic_deps</code> (transitively, i.e. also the <code>dynamic_deps</code> of the
current target's <code>dynamic_deps</code>) to decide which <code>cc_libraries</code> in
the transitive <code>deps</code> should not be linked in because they are already provided
by a different <code>cc_shared_library</code>.
</p>"""),
        "exports_filter": attr.string_list(doc = """
This attribute contains a list of targets that are claimed to be exported by the current
shared library.

<p>
Any target <code>deps</code> is already understood to be exported by the shared library.
This attribute should be used to list any targets that are exported by the shared library
but are transitive dependencies of <code>deps</code>.
</p>

<p>
Note that this attribute is not actually adding a dependency edge to those targets, the
dependency edge should instead be created by <code>deps</code>.The entries in this
attribute are just strings. Keep in mind that when placing a target in this attribute,
this is considered a claim that the shared library exports the symbols from that target.
The <code>cc_shared_library</code> logic  doesn't actually handle telling the linker which
symbols should be exported.
</p>

<p>The following syntax is allowed:</p>
<p><code>//foo:__pkg__</code> to account for any target in foo/BUILD</p>
<p><code>//foo:__subpackages__</code> to account for any target in foo/BUILD or any other
package below foo/ like foo/bar/BUILD</p>"""),
        "win_def_file": attr.label(allow_single_file = [".def"], doc = """
The Windows DEF file to be passed to linker.
<p>This attribute should only be used when Windows is the target platform.
It can be used to <a href="https://msdn.microsoft.com/en-us/library/d91k01sh.aspx">
export symbols</a> during linking a shared library.</p>"""),
        "roots": attr.label_list(providers = [CcInfo], aspects = [graph_structure_aspect]),
        "deps": attr.label_list(providers = [CcInfo], aspects = [graph_structure_aspect], doc = """
Top level libraries that will unconditionally be statically linked into the shared library
after being whole-archived.

<p>
Any transitive library dependency of these direct deps will be linked into this shared
library as long as they have not already been linked by a <code>cc_shared_library</code>
in <code>dynamic_deps</code>.</p>

<p>
During analysis, the rule implementation will consider any target listed in
<code>deps</code> as being exported by the shared library in order to give errors when
multiple <code>cc_shared_libraries</code> export the same targets. The rule implementation
does not take care of informing the linker about which symbols should be exported by the
shared object. The user should take care of this via linker scripts or visibility
declarations in the source code.</p>

<p>
The implementation will also trigger errors whenever the same library is linked statically
into more than one <code>cc_shared_library</code>. This can be avoided by adding
<code>"LINKABLE_MORE_THAN_ONCE"</code> to the <code>cc_library.tags</code> or by listing
the `cc_library` as an export of one of the shared libraries so that one can be made a
<code>dynamic_dep</code> of the other.
</p>"""),
        "static_deps": attr.string_list(),
        "user_link_flags": attr.string_list(doc = """
Any additional flags that you may want to pass to the linker. For example, to make the
linker aware of a linker script passed via additional_linker_inputs you can use the
following:

<pre><code class="lang-starlark">
 cc_shared_library(
    name = "foo_shared",
    additional_linker_inputs = select({
      "//src/conditions:linux": [
        ":foo.lds",
        ":additional_script.txt",
      ],
      "//conditions:default": []}),
    user_link_flags = select({
      "//src/conditions:linux": [
        "-Wl,-rpath,kittens",
        "-Wl,--version-script=$(location :foo.lds)",
        "-Wl,--script=$(location :additional_script.txt)",
      ],
      "//conditions:default": []}),
      ...
 )
</code></pre>"""),
        "_def_parser": semantics.get_def_parser(),
    },
    toolchains = cc_helper.use_cpp_toolchain(),
    fragments = ["cpp"] + semantics.additional_fragments(),
)

def dynamic_deps_initializer(**kwargs):
    """Initializes dynamic_deps_attrs"""
    if "dynamic_deps" in kwargs and cc_helper.is_non_empty_list_or_select(kwargs["dynamic_deps"], "dynamic_deps"):
        # Propagate an aspect if dynamic_deps attribute is specified.
        # Use += for lists rather than extend or append to allow for the case where deps
        # is a select.
        all_deps = []
        if "deps" in kwargs:
            all_deps += kwargs["deps"]

        if "linkshared" not in kwargs or not kwargs["linkshared"]:
            # The += [...] pattern below doesn't work if malloc or link_extra_lib are
            # themselves selects, but as of March 2024, there is no way to combine mixed
            # selects and these attributes usually point to label flags anyway.
            if "link_extra_lib" in kwargs:
                all_deps += [kwargs["link_extra_lib"]]
            if "malloc" in kwargs:
                all_deps += [kwargs["malloc"]]

        return kwargs | {"_deps_analyzed_by_graph_structure_aspect": all_deps}
    return kwargs

dynamic_deps_attrs = {
    "dynamic_deps": attr.label_list(
        allow_files = False,
        providers = [CcSharedLibraryInfo],
        doc = """
These are other <code>cc_shared_library</code> dependencies the current target depends on.

<p>
The <code>cc_shared_library</code> implementation will use the list of
<code>dynamic_deps</code> (transitively, i.e. also the <code>dynamic_deps</code> of the
current target's <code>dynamic_deps</code>) to decide which <code>cc_libraries</code> in
the transitive <code>deps</code> should not be linked in because they are already provided
by a different <code>cc_shared_library</code>.
        """,
    ),
    "_deps_analyzed_by_graph_structure_aspect": attr.label_list(
        providers = [CcInfo],
        aspects = [graph_structure_aspect],
    ),
}

for_testing_dont_use_check_if_target_under_path = _check_if_target_under_path
merge_cc_shared_library_infos = _merge_cc_shared_library_infos
build_link_once_static_libs_map = _build_link_once_static_libs_map
build_exports_map_from_only_dynamic_deps = _build_exports_map_from_only_dynamic_deps
throw_linked_but_not_exported_errors = _throw_linked_but_not_exported_errors
separate_static_and_dynamic_link_libraries = _separate_static_and_dynamic_link_libraries
sort_linker_inputs = _sort_linker_inputs
add_unused_dynamic_deps = _add_unused_dynamic_deps
