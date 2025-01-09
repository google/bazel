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
"""Utilities for creating cc debug package information outputs"""

load(":common/cc/cc_helper.bzl", "cc_helper", "linker_mode")

def create_debug_packager_actions(
        ctx,
        cc_toolchain,
        dwp_output,
        *,
        cc_compilation_outputs,
        cc_debug_context,
        linking_mode,
        use_pic = True,
        lto_artifacts = []):
    """Creates intermediate and final dwp creation action(s)

    Args:
        ctx: (RuleContext) the rule context
        cc_toolchain: (CcToolchainInfo) the cc toolchain
        dwp_output: (File) the output of the final dwp action
        cc_compilation_outputs: (CcCompilationOutputs)
        cc_debug_context: (DebugContext)
        linking_mode: (str) See cc_helper.bzl%linker_mode
        use_pic: (bool)
        lto_artifacts: ([CcLtoBackendArtifacts])
    """
    dwo_files = _collect_transitive_dwo_artifacts(
        cc_compilation_outputs,
        cc_debug_context,
        linking_mode,
        use_pic,
        lto_artifacts,
    )

    # No inputs? Just generate a trivially empty .dwp.
    #
    # Note this condition automatically triggers for any build where fission is disabled.
    # Because rules referencing .dwp targets may be invoked with or without fission, we need
    # to support .dwp generation even when fission is disabled. Since no actual functionality
    # is expected then, an empty file is appropriate.
    dwo_files_list = dwo_files.to_list()
    if len(dwo_files_list) == 0:
        ctx.actions.write(dwp_output, "", False)
        return

    # We apply a hierarchical action structure to limit the maximum number of inputs to any
    # single action.
    #
    # While the dwp tool consumes .dwo files, it can also consume intermediate .dwp files,
    # allowing us to split a large input set into smaller batches of arbitrary size and order.
    # Aside from the parallelism performance benefits this offers, this also reduces input
    # size requirements: if a.dwo, b.dwo, c.dwo, and e.dwo are each 1 KB files, we can apply
    # two intermediate actions DWP(a.dwo, b.dwo) --> i1.dwp and DWP(c.dwo, e.dwo) --> i2.dwp.
    # When we then apply the final action DWP(i1.dwp, i2.dwp) --> finalOutput.dwp, the inputs
    # to this action will usually total far less than 4 KB.
    #
    # The actions form an n-ary tree with n == MAX_INPUTS_PER_DWP_ACTION. The tree is fuller
    # at the leaves than the root, but that both increases parallelism and reduces the final
    # action's input size.
    packager = _create_intermediate_dwp_packagers(ctx, dwp_output, cc_toolchain, cc_toolchain._dwp_files, dwo_files_list, 1)
    packager["outputs"].append(dwp_output)
    packager["arguments"].add("-o", dwp_output)
    ctx.actions.run(
        mnemonic = "CcGenerateDwp",
        tools = packager["tools"],
        executable = packager["executable"],
        toolchain = cc_helper.CPP_TOOLCHAIN_TYPE,
        arguments = [packager["arguments"]],
        inputs = packager["inputs"],
        outputs = packager["outputs"],
    )
    return dwo_files

def _collect_transitive_dwo_artifacts(cc_compilation_outputs, cc_debug_context, linking_mode, use_pic, lto_backend_artifacts):
    dwo_files = []
    transitive_dwo_files = depset()
    if use_pic:
        dwo_files.extend(cc_compilation_outputs.pic_dwo_files())
    else:
        dwo_files.extend(cc_compilation_outputs.dwo_files())

    if lto_backend_artifacts != None:
        for lto_backend_artifact in lto_backend_artifacts:
            if lto_backend_artifact.dwo_file() != None:
                dwo_files.append(lto_backend_artifact.dwo_file())

    if linking_mode != linker_mode.LINKING_DYNAMIC:
        if use_pic:
            transitive_dwo_files = cc_debug_context.pic_files
        else:
            transitive_dwo_files = cc_debug_context.files
    return depset(dwo_files, transitive = [transitive_dwo_files])

def _get_intermediate_dwp_file(ctx, dwp_output, order_number):
    output_path = dwp_output.short_path

    # Since it is a dwp_output we can assume that it always
    # ends with .dwp suffix, because it is declared so in outputs
    # attribute.
    extension_stripped_output_path = output_path[0:len(output_path) - 4]
    intermediate_path = extension_stripped_output_path + "-" + str(order_number) + ".dwp"

    return ctx.actions.declare_file("_dwps/" + intermediate_path)

def _create_intermediate_dwp_packagers(ctx, dwp_output, cc_toolchain, dwp_files, dwo_files, intermediate_dwp_count):
    intermediate_outputs = dwo_files

    # This long loop is a substitution for recursion, which is not currently supported in Starlark.
    for _ in range(2147483647):
        packagers = []
        current_packager = _new_dwp_action(ctx, cc_toolchain, dwp_files)
        inputs_for_current_packager = 0

        # Step 1: generate our batches. We currently break into arbitrary batches of fixed maximum
        # input counts, but we can always apply more intelligent heuristics if the need arises.
        for dwo_file in intermediate_outputs:
            if inputs_for_current_packager == 100:
                packagers.append(current_packager)
                current_packager = _new_dwp_action(ctx, cc_toolchain, dwp_files)
                inputs_for_current_packager = 0
            current_packager["inputs"].append(dwo_file)

            # add_all expands all directories to their contained files, see
            # https://bazel.build/rules/lib/builtins/Args#add_all. add doesn't
            # do that, so using add_all on the one-item list here allows us to
            # find dwo files in directories.
            current_packager["arguments"].add_all([dwo_file])
            inputs_for_current_packager += 1

        packagers.append(current_packager)

        # Step 2: given the batches, create the actions.
        if len(packagers) > 1:
            # If we have multiple batches, make them all intermediate actions, then pipe their outputs
            # into an additional level.
            intermediate_outputs = []
            for packager in packagers:
                intermediate_output = _get_intermediate_dwp_file(ctx, dwp_output, intermediate_dwp_count)
                intermediate_dwp_count += 1
                packager["outputs"].append(intermediate_output)
                packager["arguments"].add("-o", intermediate_output)
                ctx.actions.run(
                    mnemonic = "CcGenerateIntermediateDwp",
                    tools = packager["tools"],
                    executable = packager["executable"],
                    toolchain = cc_helper.CPP_TOOLCHAIN_TYPE,
                    arguments = [packager["arguments"]],
                    inputs = packager["inputs"],
                    outputs = packager["outputs"],
                )
                intermediate_outputs.append(intermediate_output)
        else:
            return packagers[0]

    # This is to fix buildifier errors, even though we should never reach this part of the code.
    return None

def _new_dwp_action(ctx, cc_toolchain, dwp_tools):
    return {
        "tools": dwp_tools,
        "executable": cc_toolchain._tool_paths.get("dwp", None),
        "arguments": ctx.actions.args(),
        "inputs": [],
        "outputs": [],
    }
