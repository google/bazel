Project: /_project.yaml
Book: /_book.yaml

# Release Model

{% dynamic setvar source_file "site/en/release/index.md" %}
{% include "_buttons.html" %}

As announced in [the original blog
post](https://blog.bazel.build/2020/11/10/long-term-support-release.html), Bazel
4.0 and higher versions provides support for two release tracks: rolling
releases and long term support (LTS) releases. This page covers the latest
information about Bazel's release model.

## Support matrix {:#support-matrix}

| LTS release | Support stage | Latest version | End of support |
| ----------- | ------------- | -------------- | -------------- |
| Bazel 9 | Rolling| [Check rolling release page](/release/rolling) | N/A |
| Bazel 8 | Active| [8.0.1](https://github.com/bazelbuild/bazel/releases/tag/8.0.1){: .external} | December 2027 |
| Bazel 7 | Maintenance| [7.5.0](https://github.com/bazelbuild/bazel/releases/tag/7.5.0){: .external} | Dec 2026 |
| Bazel 6 | Maintenance | [6.5.0](https://github.com/bazelbuild/bazel/releases/tag/6.5.0){: .external} | Dec 2025 |
| Bazel 5 | Maintenance | [5.4.1](https://github.com/bazelbuild/bazel/releases/tag/5.4.1){: .external} | Jan 2025 |
| Bazel 4 | Deprecated | [4.2.4](https://github.com/bazelbuild/bazel/releases/tag/4.2.4){: .external} | Jan 2024 |

All Bazel LTS releases can be found on the [release
page](https://github.com/bazelbuild/bazel/releases){: .external} on GitHub.

Note: Bazel version older than Bazel 5 are no longer supported, Bazel users are
recommended to upgrade to the latest LTS release or use rolling releases if you
want to keep up with the latest changes at HEAD.

## Release versioning {:#bazel-versioning}

Bazel uses a _major.minor.patch_ [Semantic
Versioning](https://semver.org/){: .external} scheme.

*   A _major release_ contains features that are not backward compatible with
    the previous release. Each major Bazel version is an LTS release.
*   A _minor release_ contains backward-compatible bug fixes and features
    back-ported from the main branch.
*   A _patch release_ contains critical bug fixes.

Additionally, pre-release versions are indicated by appending a hyphen and a
date suffix to the next major version number.

For example, a new release of each type would result in these version numbers:

*   Major: 6.0.0
*   Minor: 6.1.0
*   Patch: 6.1.2
*   Pre-release: 7.0.0-pre.20230502.1

## Support stages {:#support-stages}

For each major Bazel version, there are four support stages:

*   **Rolling**: This major version is still in pre-release, the Bazel team
    publishes rolling releases from HEAD.
*   **Active**: This major version is the current active LTS release. The Bazel
  team backports important features and bug fixes into its minor releases.
*   **Maintenance**: This major version is an old LTS release in maintenance
    mode. The Bazel team only promises to backport critical bug fixes for
    security issues and OS-compatibility issues into this LTS release.
*   **Deprecated**: The Bazel team no longer provides support for this major
    version, all users should migrate to newer Bazel LTS releases.

## Release cadence {:#release-cadence}

Bazel regularly publish releases for two release tracks.

### Rolling releases {:#rolling-releases}

*   Rolling releases are coordinated with Google Blaze release and are released
  from HEAD around every two weeks. It is a preview of the next Bazel LTS
    release.
*   Rolling releases can ship incompatible changes. Incompatible flags are
    recommended for major breaking changes, rolling out incompatible changes
    should follow our [backward compatibility
    policy](/release/backward-compatibility).

### LTS releases {:#lts-releases}

*   _Major release_: A new LTS release is expected to be cut from HEAD roughly
    every
    12 months. Once a new LTS release is out, it immediately enters the Active
    stage, and the previous LTS release enters the Maintenance stage.
*   _Minor release_: New minor verions on the Active LTS track are expected to
    be released once every 2 months.
*   _Patch release_: New patch versions for LTS releases in Active and
    Maintenance stages are expected to be released on demand for critical bug
    fixes.
*   A Bazel LTS release enters the Deprecated stage after being in ​​the
    Maintenance stage for 2 years.

For planned releases, please check our [release
issues](https://github.com/bazelbuild/bazel/issues?q=is%3Aopen+is%3Aissue+label%3Arelease){: .external}
on Github.

## Release procedure & policies {:#release-procedure-policies}

For rolling releases, the process is straightforward: about every two weeks, a
new release is created, aligning with the same baseline as the Google internal
Blaze release. Due to the rapid release schedule, we don't backport any changes
to rolling releases.

For LTS releases, the procedure and policies below are followed:

1.  Determine a baseline commit for the release.
    *   For a new major LTS release, the baseline commit is the HEAD of the main
        branch.
    *   For a minor or patch release, the baseline commit is the HEAD of the
        current latest version of the same LTS release.
1.  Create a release branch in the name of `release-<version>` from the baseline
    commit.
1.  Backport changes via PRs to the release branch.
    *   The community can suggest certain commits to be back-ported by replying
   "`@bazel-io flag`" on relevant GitHub issues or PRs to mark them as potential
        release blockers, the Bazel team triages them and decide whether to
        back-port the commits.
    *   Only backward-compatible commits on the main branch can be back-ported,
   additional minor changes to resolve merge conflicts are acceptable.
1.  Backport changes using Cherry-Pick Request Issue for Bazel maintainers.
    *   Bazel maintainers can request to cherry-pick specific commit(s)
        to a release branch. This process is initiated by creating a
        cherry-pick request on GitHub. Here's how to do it.
        1.  Open the [cherry-pick request](https://github.com/bazelbuild/bazel/issues/new?assignees=&labels=&projects=&template=cherry_pick_request.yml){: .external}
        2.  Fill in the request details
            *   Title: Provide a concise and descriptive title for the request.
            *   Commit ID(s): Enter the ID(s) of the commit(s) you want to
                cherry-pick. If there are multiple commits, then separate
                them with commas.
            *   Category: Specify the category of the request.
            *   Reviewer(s): For multiple reviewers, separate their GitHub
                ID's with commas.
        3.  Set the milestone
            *   Find the "Milestone" section and click the setting.
            *   Select the appropriate X.Y.Z release blockers. This action
                triggers the cherry-pick bot to process your request
                for the "release-X.Y.Z" branch.
        4.  Submit the Issue
            *   Once all details are filled in and the miestone is set,
                submit the issue.

    *   The cherry-pick bot will process the request and notify
        if the commit(s) are eligible for cherry-picking. If
        the commits are cherry-pickable, which means there's no
        merge conflict while cherry-picking the commit, then
        the bot will create a new pull request. When the pull
        request is approved by a member of the Bazel team, the
        commits are cherry-picked and merged to the release branch.
        For a visual example of a completed cherry-pick request,
        refer to this
        [example](https://github.com/bazelbuild/bazel/issues/20230){: .external}
        .

1.  Identify release blockers and fix issues found on the release branch.
    *   The release branch is tested with the same test suite in
        [postsubmit](https://buildkite.com/bazel/bazel-bazel){: .external} and
        [downstream test pipeline]
        (https://buildkite.com/bazel/bazel-at-head-plus-downstream){: .external}
        on Bazel CI. The Bazel team monitors testing results of the release
        branch and fixes any regressions found.
1.  Create a new release candidate from the release branch when all known
    release blockers are resolved.
    *   The release candidate is announced on
        [bazel-discuss](https://groups.google.com/g/bazel-discuss){: .external},
        the Bazel team monitors community bug reports for the candidate.
    *   If new release blockers are identified, go back to the last step and
        create a new release candidate after resolving all the issues.
    *   New features are not allowed to be added to the release branch after the
        first release candidate is created; cherry-picks are limited to critical
        fixes only. If a cherry-pick is needed, the requester must answer the
        following questions: Why is this change critical, and what benefits does
        it provide? What is the likelihood of this change introducing a
        regression?
1.  Push the release candidate as the official release if no further release
    blockers are found
    *   For patch releases, push the release at least two business days after
        the last release candidate is out.
    *   For major and minor releases, push the release two business days after
        the last release candidate is out, but not earlier than one week after
        the first release candidate is out.
    *   The release is only pushed on a day where the next day is a business
        day.
    *   The release is announced on
        [bazel-discuss](https://groups.google.com/g/bazel-discuss){: .external},
        the Bazel team monitors and addresses community bug reports for the new
     release.

## Report regressions {:#report-regressions}

If a user finds a regression in a new Bazel release, release candidate or even
Bazel at HEAD, please file a bug on
[GitHub](https://github.com/bazelbuild/bazel/issues){: .external}. You can use
Bazelisk to bisect the culprit commit and include this information in the bug
report.

For example, if your build succeeds with Bazel 6.1.0 but fails with the second
release candidate of 6.2.0, you can do bisect via

```bash
bazelisk --bisect=6.1.0..release-6.2.0rc2 build //foo:bar
```

You can set `BAZELISK_SHUTDOWN` or `BAZELISK_CLEAN` environment variable to run
corresponding bazel commands to reset the build state if it's needed to
reproduce the issue. For more details, check out documentation about Bazelisk
[bisect feature] (https://github.com/bazelbuild/bazelisk#--bisect){: .external}.

Remember to upgrade Bazelisk to the latest version to use the bisect
feature.

## Rule compatibility {:#rule-compatibility}

If you are a rule authors and want to maintain compatibility with different
Bazel versions, please check out the [Rule
Compatibility](/release/rule-compatibility) page.
