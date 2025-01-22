# Description:
#   JaCoCo is a free code coverage library for Java, created by the EclEmma team.
#
# https://search.maven.org/remotecontent?filepath=org/jacoco/jacoco/0.8.11/jacoco-0.8.11.zip

load("//tools/distributions:distribution_rules.bzl", "distrib_jar_filegroup", "distrib_java_import")
load("//src:release_archive.bzl", "release_archive")

licenses(["reciprocal"])  # EPL 1.0 (Eclipse Public License)

exports_files(["LICENSE"])

package(default_visibility = ["//visibility:public"])

LASTVERSION = "0.8.11"

VERSIONS = ["0.8.11"]

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
)

release_archive(
    name = "jacoco_jars_zip",
    srcs = glob(["*.jar"]) + ["LICENSE"] + [
        "//third_party:asm",
        "//third_party:asm-commons",
        "//third_party:asm-tree",
    ],
    package_dir = "java_tools/third_party/java/jacoco",
    visibility = ["//visibility:public"],
)

filegroup(
    name = "transitive_sources",
    srcs = glob(
        ["*-sources.jar"],
        allow_empty = True,
    ) + ["LICENSE"] + [
        "//third_party:asm/asm-9.7.1-sources.jar",
        "//third_party:asm/asm-analysis-9.7.1-sources.jar",
        "//third_party:asm/asm-commons-9.7.1-sources.jar",
    ],
)

distrib_java_import(
    name = "agent",
    enable_distributions = ["debian"],
    jars = ["org.jacoco.agent-%s.jar" % LASTVERSION],
    srcjar = "org.jacoco.agent-%s-sources.jar" % LASTVERSION,
)

[
    distrib_java_import(
        name = "agent-%s" % VERSION,
        enable_distributions = ["debian"],
        jars = ["org.jacoco.agent-%s.jar" % VERSION],
        srcjar = "org.jacoco.agent-%s-sources.jar" % VERSION,
    )
    for VERSION in VERSIONS
]

distrib_java_import(
    name = "core",
    enable_distributions = ["debian"],
    jars = ["org.jacoco.core-%s.jar" % LASTVERSION],
    srcjar = "org.jacoco.core-%s-sources.jar" % LASTVERSION,
    exports = [
        "//third_party:asm",
        "//third_party:asm-commons",
        "//third_party:asm-tree",
    ],
)

[
    distrib_java_import(
        name = "core-%s" % VERSION,
        enable_distributions = ["debian"],
        jars = ["org.jacoco.core-%s.jar" % LASTVERSION],
        srcjar = "org.jacoco.core-%s-sources.jar" % LASTVERSION,
        exports = [
            "//third_party:asm",
            "//third_party:asm-commons",
            "//third_party:asm-tree",
        ],
    )
    for VERSION in VERSIONS
]

distrib_jar_filegroup(
    name = "core-jars",
    srcs = ["org.jacoco.core-%s.jar" % LASTVERSION],
    enable_distributions = ["debian"],
)

[
    distrib_jar_filegroup(
        name = "core-jars-%s" % VERSION,
        srcs = ["org.jacoco.core-%s.jar" % VERSION],
        enable_distributions = ["debian"],
    )
    for VERSION in VERSIONS
]

distrib_java_import(
    name = "report",
    enable_distributions = ["debian"],
    jars = ["org.jacoco.report-%s.jar" % LASTVERSION],
    srcjar = "org.jacoco.report-%s-sources.jar" % LASTVERSION,
    exports = [
        ":core",
        "//third_party:asm",
    ],
)

[
    distrib_java_import(
        name = "report-%s" % VERSION,
        enable_distributions = ["debian"],
        jars = ["org.jacoco.report-%s.jar" % VERSION],
        srcjar = "org.jacoco.report-%s-sources.jar" % VERSION,
        exports = [
            ":core-%s" % VERSION,
            "//third_party:asm",
        ],
    )
    for VERSION in VERSIONS
]

distrib_java_import(
    name = "blaze-agent",
    enable_distributions = ["debian"],
    jars = ["jacocoagent-%s.jar" % LASTVERSION],
)

[
    distrib_java_import(
        name = "blaze-agent-%s" % VERSION,
        enable_distributions = ["debian"],
        jars = ["jacocoagent-%s.jar" % VERSION],
    )
    for VERSION in VERSIONS
]
