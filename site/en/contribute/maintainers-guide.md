Project: /_project.yaml
Book: /_book.yaml

# Guide for Bazel Maintainers

{% include "_buttons.html" %}

This is a guide for the maintainers of the Bazel open source project.

If you are looking to contribute to Bazel, please read [Contributing to
Bazel](/contribute) instead.

The objectives of this page are to:

1. Serve as the maintainers' source of truth for the project’s contribution
   process.
1. Set expectations between the community contributors and the project
   maintainers.

Bazel's [core group of contributors](/contribute/policy) has dedicated
subteams to manage aspects of the open source project. These are:

* **Release Process**: Manage Bazel's release process.
* **Green Team**: Grow a healthy ecosystem of rules and tools.
* **Developer Experience Gardeners**: Encourage external contributions, review
  issues and pull requests, and make our development workflow more open.

## Releases {:#releases}

* [Release Playbook](https://github.com/bazelbuild/continuous-integration/blob/master/docs/release-playbook.md){: .external}
* [Testing local changes with downstream projects](https://github.com/bazelbuild/continuous-integration/blob/master/docs/downstream-testing.md){: .external}

## Continuous Integration {:#integration}

Read the Green team's guide to Bazel's CI infrastructure on the
[bazelbuild/continuous-integration](https://github.com/bazelbuild/continuous-integration/blob/master/buildkite/README.md){: .external}
repository.

## Lifecycle of an Issue {:#lifecycle-issue}

1. A user creates an issue by choosing one of the
[issue templates](https://github.com/bazelbuild/bazel/issues/new/choose){: .external}
   and it enters the pool of [unreviewed open
   issues](https://github.com/bazelbuild/bazel/issues?utf8=%E2%9C%93&q=is%3Aissue+is%3Aopen+-label%3Auntriaged+-label%3Ap2+-label%3Ap1+-label%3Ap3+-label%3Ap4+-label%3Ateam-Starlark+-label%3Ateam-Rules-CPP+-label%3Ateam-Rules-Java+-label%3Ateam-XProduct+-label%3Ateam-Android+-label%3Ateam-Apple+-label%3Ateam-Configurability++-label%3Ateam-Performance+-label%3Ateam-Rules-Server+-label%3Ateam-Core+-label%3Ateam-Rules-Python+-label%3Ateam-Remote-Exec+-label%3Ateam-Local-Exec+-label%3Ateam-Bazel){: .external}.
1. A member on the Developer Experience (DevEx) subteam rotation reviews the
   issue.
   1. If the issue is **not a bug** or a **feature request**, the DevEx member
      will usually close the issue and redirect the user to
      [StackOverflow](https://stackoverflow.com/questions/tagged/bazel){: .external} and
      [bazel-discuss](https://groups.google.com/forum/#!forum/bazel-discuss){: .external} for
      higher visibility on the question.
   1. If the issue belongs in one of the rules repositories owned by the
      community, like [rules_apple](https://github.com.bazelbuild/rules_apple){: .external},
      the DevEx member will [transfer this issue](https://docs.github.com/en/free-pro-team@latest/github/managing-your-work-on-github/transferring-an-issue-to-another-repository){: .external}
      to the correct repository.
   1. If the issue is vague or has missing information, the DevEx member will
      assign the issue back to the user to request for more information before
      continuing. This usually occurs when the user does not choose the right
      [issue template](https://github.com/bazelbuild/bazel/issues/new/choose)
      {: .external} or provides incomplete information.
1. After reviewing the issue, the DevEx member decides if the issue requires
   immediate attention. If it does, they will assign the **P0**
   [priority](#priority) label and an owner from the list of team leads.
1. The DevEx member assigns the `untriaged` label and exactly one [team
   label](#team-labels) for routing.
1. The DevEx member also assigns exactly one `type:` label, such as `type: bug`
   or `type: feature request`, according to the type of the issue.
1. For platform-specific issues, the DevEx member assigns one `platform:` label,
   such as `platform:apple` for Mac-specific issues.
1. If the issue is low priority and can be worked on by a new community
   contributor, the DevEx member assigns the `good first issue` label.
At this stage, the issue enters the pool of [untriaged open
issues](https://github.com/bazelbuild/bazel/issues?q=is%3Aissue+is%3Aopen+label%3Auntriaged).

Each Bazel subteam will triage all issues under labels they own, preferably on a
weekly basis. The subteam will review and evaluate the issue and provide a
resolution, if possible. If you are an owner of a team label, see [this section
](#label-own) for more information.

When an issue is resolved, it can be closed.

## Lifecycle of a Pull Request {:#lifecycle-pull-request}

1. A user creates a pull request.
1. If you a member of a Bazel team and sending a PR against your own area,
   you are responsible for assigning your team label and finding the best
   reviewer.
1. Otherwise, during daily triage, a DevEx member assigns one
   [team label](#team-labels) and the team's technical lead (TL) for routing.
   1. The TL may optionally assign someone else to review the PR.
1. The assigned reviewer reviews the PR and works with the author until it is
   approved or dropped.
1. If approved, the reviewer **imports** the PR's commit(s) into Google's
   internal version control system for further tests. As Bazel is the same build
   system used internally at Google, we need to test all PR commits against the
   internal test suite. This is the reason why we do not merge PRs directly.
1. If the imported commit passes all internal tests, the commit will be squashed
   and exported back out to GitHub.
1. When the commit merges into master, GitHub automatically closes the PR.


## My team owns a label. What should I do? {:#label-own}

Subteams need to triage all issues in the [labels they own](#team-labels),
preferably on a weekly basis.

### Issues {:#issues}

1. Filter the list of issues by your team label **and** the `untriaged` label.
1. Review the issue.
1. Identify a [priority level](#priority) and assign the label.
  1. The issue may have already been prioritized by the DevEx subteam if it's a
     P0. Re-prioritize if needed.
  1. Each issue needs to have exactly one [priority label](#priority). If an
     issue is either P0 or P1 we assume that is actively worked on.
1. Remove the `untriaged` label.

Note that you need to be in the [bazelbuild
organization](https://github.com/bazelbuild){: .external} to be able to add or remove labels.

### Pull Requests {:#pull-requests}

1. Filter the list of pull requests by your team label.
1. Review open pull requests.
  1. **Optional**: If you are assigned for the review but is not the right fit
  for it, re-assign the appropriate reviewer to perform a code review.
1. Work with the pull request creator to complete a code review.
1. Approve the PR.
1. Ensure that all tests pass.
1. Import the patch to the internal version control system and run the internal
   presubmits.
1. Submit the internal patch. If the patch submits and exports successfully, the
   PR will be closed automatically by GitHub.

## Priority {:#priority}

The following definitions for priority will be used by the maintainers to triage
issues.

* [**P0**](https://github.com/bazelbuild/bazel/labels/P0){: .external} - Major broken
  functionality that causes a Bazel release (minus release candidates) to be
  unusable, or a downed service that severely impacts development of the Bazel
  project. This includes regressions introduced in a new release that blocks a
  significant number of users, or an incompatible breaking change that was not
  compliant to the [Breaking
  Change](https://docs.google.com/document/d/1q5GGRxKrF_mnwtaPKI487P8OdDRh2nN7jX6U-FXnHL0/edit?pli=1#heading=h.ceof6vpkb3ik){: .external}
  policy. No practical workaround exists.
* [**P1**](https://github.com/bazelbuild/bazel/labels/P1){: .external} - Critical defect or
  feature which should be addressed in the next release, or a serious issue that
  impacts many users (including the development of the Bazel project), but a
  practical workaround exists. Typically does not require immediate action. In
  high demand and planned in the current quarter's roadmap.
* [**P2**](https://github.com/bazelbuild/bazel/labels/P2){: .external} - Defect or feature
  that should be addressed but we don't currently work on. Moderate live issue
  in a released Bazel version that is inconvenient for a user that needs to be
  addressed in an future release and/or an easy workaround exists.
* [**P3**](https://github.com/bazelbuild/bazel/labels/P3){: .external} - Desirable minor bug
  fix or enhancement with small impact. Not prioritized into Bazel roadmaps or
  any imminent release, however community contributions are encouraged.
* [**P4**](https://github.com/bazelbuild/bazel/labels/P4){: .external} - Low priority defect
  or feature request that is unlikely to get closed. Can also be kept open for a
  potential re-prioritization if more users are impacted.
* [**ice-box**](https://github.com/bazelbuild/bazel/issues?q=label%3Aice-box+is%3Aclosed){: .external}
  - Issues that we currently don't have time to deal with nor the
  time to accept contributions. We will close these issues to indicate that
  nobody is working on them, but will continue to monitor their validity over
  time and revive them if enough people are impacted and if we happen to have
  resources to deal with them. As always, feel free to comment or add reactions
  to these issues even when closed.

## Team labels {:#team-labels}

*   [`team-Android`](https://github.com/bazelbuild/bazel/labels/team-Android){: .external}: Issues for Android team
    *   Contact: [ahumesky](https://github.com/ahumesky){: .external}
*   [`team-Bazel`](https://github.com/bazelbuild/bazel/labels/team-Bazel){: .external}: General Bazel product/strategy issues
    * Contact: [meisterT](https://github.com/meisterT){: .external}
*   [`team-CLI`](https://github.com/bazelbuild/bazel/labels/team-CLI){: .external}: Console UI
    * Contact: [meisterT](https://github.com/meisterT){: .external}
*   [`team-Configurability`](https://github.com/bazelbuild/bazel/labels/team-Configurability){: .external}: Issues for Configurability team. Includes: Core build configuration and transition system. Does *not* include: Changes to new or existing flags
    * Contact: [gregestren](https://github.com/gregestren){: .external}
*   [`team-Core`](https://github.com/bazelbuild/bazel/labels/team-Core){: .external}: Skyframe, bazel query, BEP, options parsing, bazelrc
    * Contact: [haxorz](https://github.com/haxorz){: .external}
*   [`team-Documentation`](https://github.com/bazelbuild/bazel/labels/team-Documentation){: .external}: Issues for Documentation team
*   [`team-ExternalDeps`](https://github.com/bazelbuild/bazel/labels/team-ExternalDeps){: .external}: External dependency handling, Bzlmod, remote repositories, WORKSPACE file
    * Contact: [meteorcloudy](https://github.com/meteorcloudy){: .external}
*   [`team-Loading-API`](https://github.com/bazelbuild/bazel/labels/team-Loading-API){: .external}: BUILD file and macro processing: labels, package(), visibility, glob
    * Contact: [brandjon](https://github.com/brandjon){: .external}
*   [`team-Local-Exec`](https://github.com/bazelbuild/bazel/labels/team-Local-Exec){: .external}: Issues for Execution (Local) team
    * Contact: [meisterT](https://github.com/meisterT){: .external}
*   [`team-OSS`](https://github.com/bazelbuild/bazel/labels/team-OSS){: .external}: Issues for Bazel OSS team: installation, release process, Bazel packaging, website, docs infrastructure
    * Contact: [meteorcloudy](https://github.com/meteorcloudy){: .external}
*   [`team-Performance`](https://github.com/bazelbuild/bazel/labels/team-Performance){: .external}: Issues for Bazel Performance team
    * Contact: [meisterT](https://github.com/meisterT){: .external}
*   [`team-Remote-Exec`](https://github.com/bazelbuild/bazel/labels/team-Remote-Exec){: .external}: Issues for Execution (Remote) team
    * Contact: [coeuvre](https://github.com/coeuvre){: .external}
*   [`team-Rules-API`](https://github.com/bazelbuild/bazel/labels/team-Rules-API){: .external}: API for writing rules/aspects: providers, runfiles, actions, artifacts
    * Contact: [comius](https://github.com/comius){: .external}
*   [`team-Rules-CPP`](https://github.com/bazelbuild/bazel/labels/team-Rules-CPP){: .external} / [`team-Rules-ObjC`](https://github.com/bazelbuild/bazel/labels/team-Rules-ObjC){: .external}: Issues for C++/Objective-C rules, including native Apple rule logic
    * Contact: [pzembrod](https://github.com/pzembrod){: .external}
*   [`team-Rules-Java`](https://github.com/bazelbuild/bazel/labels/team-Rules-Java){: .external}: Issues for Java rules
    * Contact: [hvadehra](https://github.com/hvadehra){: .external}
*   [`team-Rules-Python`](https://github.com/bazelbuild/bazel/labels/team-Rules-Python){: .external}: Issues for the native Python rules
    * Contact: [rickeylev](https://github.com/rickeylev){: .external}
*   [`team-Rules-Server`](https://github.com/bazelbuild/bazel/labels/team-Rules-Server){: .external}: Issues for server-side rules included with Bazel
    * Contact: [comius](https://github.com/comius){: .external}
*   [`team-Starlark-Integration`](https://github.com/bazelbuild/bazel/labels/team-Starlark-Integration){: .external}: Non-API Bazel + Starlark integration. Includes: how Bazel triggers the Starlark interpreter, Stardoc, builtins injection, character encoding.  Does *not* include: BUILD or .bzl language issues.
    * Contact: [brandjon](https://github.com/brandjon){: .external}
*   [`team-Starlark-Interpreter`](https://github.com/bazelbuild/bazel/labels/team-Starlark-Interpreter){: .external}: Issues for the Starlark interpreter (anything in [java.net.starlark](https://github.com/bazelbuild/bazel/tree/master/src/main/java/net/starlark/java)). BUILD and .bzl API issues (which represent Bazel's *integration* with Starlark) go in `team-Build-Language`.
    * Contact: [brandjon](https://github.com/brandjon){: .external}

For new issues, we deprecated the `category: *` labels in favor of the team
labels.

See the full list of labels [here](https://github.com/bazelbuild/bazel/labels){: .external}.
