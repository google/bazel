# Copyright 2023 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Rules and macros for collecting LicenseInfo providers."""

load("@rules_license//rules:filtered_rule_kinds.bzl", "aspect_filters")
load(
    "@rules_license//rules:providers.bzl",
    "LicenseInfo",
    "PackageInfo",
)
load(
    "@rules_license//rules/private:gathering_providers.bzl",
    "LicensedTargetInfo",
)
load("@rules_license//rules_gathering:trace.bzl", "TraceInfo")

load(":user_filtered_rule_kinds.bzl", "user_aspect_filters")

TransitivePackageInfo = provider(
    fields = {
        "top_level_target": "Label: The top level target label we are examining.",
        "license_info": "depset(LicenseInfo)",
        "package_info": "depset(PackageInfo)",
        "packages": "depset(label)",

        "target_under_license": "Label: A target which will be associated with some licenses.",
        # "deps": "depset(LicensedTargetInfo): The transitive list of dependencies that have licenses.",
        "traces": "list(string) - diagnostic for tracing a dependency relationship to a target.",
    },
)

# Singleton instance of nothing present. Each level of the aspect must return something,
# so we use one to save space instead of making a lot of empty ones.
NULL_INFO = TransitivePackageInfo(license_info = depset(), package_info = depset(), packages=depset())

def should_traverse(ctx, attr):
    """Checks if the dependent attribute should be traversed.

    Args:
      ctx: The aspect evaluation context.
      attr: The name of the attribute to be checked.

    Returns:
      True iff the attribute should be traversed.
    """
    k = ctx.rule.kind
    for filters in [aspect_filters, user_aspect_filters]:
        always_ignored = filters.get("*", [])
        if k in filters:
            attr_matches = filters[k]
            if (attr in attr_matches or
                "*" in attr_matches or
                ("_*" in attr_matches and attr.startswith("_")) or
                attr in always_ignored):
                return False

            for m in attr_matches:
                if attr == m:
                    return False
    return True


def _get_transitive_metadata(ctx, trans_license_info, trans_package_info, trans_packages, traces, provider, filter_func):
    """Pulls the transitive data up from attributes we care about.

    Collapses all the TransitivePackageInfo providers from my deps into lists
    that we can then turn into a single TPI.
    """
    attrs = [a for a in dir(ctx.rule.attr)]
    for name in attrs:
        if not filter_func(ctx, name):
            continue
        a = getattr(ctx.rule.attr, name)

        # Make anything singleton into a list for convenience.
        if type(a) != type([]):
            a = [a]
        for dep in a:
            # Ignore anything that isn't a target
            if type(dep) != "Target":
                continue

            # Targets can also include things like input files that won't have the
            # aspect, so we additionally check for the aspect rather than assume
            # it's on all targets.  Even some regular targets may be synthetic and
            # not have the aspect. This provides protection against those outlier
            # cases.
            if provider in dep:
                info = dep[provider]
                if info.license_info:
                    trans_license_info.append(info.license_info)
                if info.package_info:
                    trans_package_info.append(info.package_info)
                if info.packages:
                    trans_packages.append(info.packages)
                #if info.traces:
                #    for trace in info.traces:
                #        traces.append("(" + ", ".join([str(ctx.label), ctx.rule.kind, name]) + ") -> " + trace)


def gather_package_common(target, ctx, provider_factory, metadata_providers, filter_func):
    """Collect license and other metadata info from myself and my deps.

    Any single target might directly depend on a license, or depend on
    something that transitively depends on a license, or neither.
    This aspect bundles all those into a single provider. At each level, we add
    in new direct license deps found and forward up the transitive information
    collected so far.

    This is a common abstraction for crawling the dependency graph. It is
    parameterized to allow specifying the provider that is populated with
    results. It is configurable to select only a subset of providers. It
    is also configurable to specify which dependency edges should not
    be traced for the purpose of tracing the graph.

    Args:
      target: The target of the aspect.
      ctx: The aspect evaluation context.
      provider_factory: abstracts the provider returned by this aspect
      metadata_providers: a list of other providers of interest
      filter_func: a function that returns true iff the dep edge should be ignored

    Returns:
      provider of parameterized type
    """

    # A hack until https://github.com/bazelbuild/rules_license/issues/89 is
    # fully resolved. If exec is in the bin_dir path, then the current
    # configuration is probably cfg = exec.
    if "-exec-" in ctx.bin_dir.path:
        return [NULL_INFO]

    # First we gather my direct license attachments
    licenses = []
    package_info = []
    packages = None
    if ctx.rule.kind == "_license":
        # Don't try to gather licenses from the license rule itself. We'll just
        # blunder into the text file of the license and pick up the default
        # attribute of the package, which we don't want.
        pass
    else:
        if hasattr(ctx.rule.attr, "applicable_licenses"):
            for dep in ctx.rule.attr.applicable_licenses:
                if LicenseInfo in dep:
                    licenses.append(dep[LicenseInfo])
                if PackageInfo in dep:
                    package_info.depend(dep[LicenseInfo])

    # Record all the external repos anyway.
    target_name = str(target.label)
    if target_name.startswith('@') and target_name[1] != '/':
        packages = [target.label]
        print(str(target.label))

    # Now gather transitive collection of providers from the targets
    # this target depends upon.
    trans_license_info = []
    trans_package_info = []
    trans_packages = []
    traces = []
    _get_transitive_metadata(ctx, trans_license_info, trans_package_info, trans_packages, traces, provider_factory, filter_func)

    if (not licenses
        and not package_info
        and not trans_license_info
        and not trans_package_info
        and not trans_packages):
        return [NULL_INFO]

    # If this is the target, start the sequence of traces.
    if ctx.attr._trace[TraceInfo].trace and ctx.attr._trace[TraceInfo].trace in str(ctx.label):
        traces = [ctx.attr._trace[TraceInfo].trace]

    # Trim the number of traces accumulated since the output can be quite large.
    # A few representative traces are generally sufficient to identify why a dependency
    # is incorrectly incorporated.
    if len(traces) > 10:
        traces = traces[0:10]

    """
    if licenses:
        # At this point we have a target and a list of directly used licenses.
        # Bundle those together so we can report the exact targets that cause the
        # dependency on each license. Since a list cannot be stored in a
        # depset, even inside a provider, the list is concatenated into a
        # string and will be unconcatenated in the output phase.
        direct_license_uses = [LicensedTargetInfo(
            target_under_license = target.label,
            licenses = ",".join([str(x.label) for x in licenses]),
        )]
    else:
        direct_license_uses = None
    """

    return [provider_factory(
        target_under_license = target.label,
        license_info = depset(direct = licenses, transitive = trans_license_info),
        package_info = depset(direct = package_info, transitive = trans_package_info),
        packages = depset(direct = packages, transitive = trans_packages),
        traces = traces,
    )]



def _gather_package_impl(target, ctx):
    ret = gather_package_common(
        target,
        ctx,
        TransitivePackageInfo,
        # [ExperimentalMetadataInfo, PackageInfo],
        [PackageInfo],
        should_traverse)
    # print(ret)
    return ret

gather_package_info = aspect(
    doc = """Collects License and Package providers into a single TransitivePackageInfo provider.""",
    implementation = _gather_package_impl,
    attr_aspects = ["*"],
    attrs = {
        "_trace": attr.label(default = "@rules_license//rules:trace_target"),
    },
    provides = [TransitivePackageInfo],
    apply_to_generating_rules = True,
)

