# External dependencies for the java_* rules.
load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive", "http_file")
load("@bazel_tools//tools/build_defs/repo:utils.bzl", "maybe")

new_local_repository(
    name = "local_jdk",
    build_file = __embedded_dir__ + "/jdk.BUILD",
    path = DEFAULT_SYSTEM_JAVABASE,
)

bind(
    name = "bootclasspath",
    actual = "@local_jdk//:bootclasspath",
)

# TODO(cushon): migrate to extclasspath and delete
bind(
    name = "extdir",
    actual = "@local_jdk//:extdir",
)

bind(
    name = "extclasspath",
    actual = "@local_jdk//:extdir",
)

bind(
    name = "jni_header",
    actual = "@local_jdk//:jni_header",
)

bind(
    name = "jni_md_header-darwin",
    actual = "@local_jdk//:jni_md_header-darwin",
)

bind(
    name = "jni_md_header-linux",
    actual = "@local_jdk//:jni_md_header-linux",
)

bind(
    name = "jni_md_header-freebsd",
    actual = "@local_jdk//:jni_md_header-freebsd",
)

bind(
    name = "java",
    actual = "@local_jdk//:java",
)

bind(
    name = "jar",
    actual = "@local_jdk//:jar",
)

bind(
    name = "javac",
    actual = "@local_jdk//:javac",
)

bind(
    name = "jre",
    actual = "@local_jdk//:jre",
)

bind(
    name = "jdk",
    actual = "@local_jdk//:jdk",
)

# TODO: Remove these two rules after we've migrated. In order to properly look
# up Jdks/Jres for cross-platform builds, the lookup needs to happen in the Jdk
# repository. For now, use an alias rule that redirects to //external:{jre,jdk}.
bind(
    name = "jre-default",
    actual = "@local_jdk//:jre",
)

bind(
    name = "jdk-default",
    actual = "@local_jdk//:jdk",
)

# OpenJDK distributions that should only be downloaded on demand (e.g. when
# building a java_library or a genrule that uses java make variables).
# This will allow us to stop bundling the full JDK with Bazel.
# Note that while these are currently the same as the openjdk_* rules in
# Bazel's WORKSPACE file, but they don't have to be the same.
http_archive(
    name = "remotejdk_linux",
    build_file = "@local_jdk//:BUILD.bazel",
    sha256 = "f27cb933de4f9e7fe9a703486cf44c84bc8e9f138be0c270c9e5716a32367e87",
    strip_prefix = "zulu9.0.7.1-jdk9.0.7-linux_x64-allmodules",
    urls = [
        "https://mirror.bazel.build/openjdk/azul-zulu-9.0.7.1-jdk9.0.7/zulu9.0.7.1-jdk9.0.7-linux_x64-allmodules.tar.gz",
    ],
)

http_archive(
    name = "remotejdk_macos",
    build_file = "@local_jdk//:BUILD.bazel",
    sha256 = "404e7058ff91f956612f47705efbee8e175a38b505fb1b52d8c1ea98718683de",
    strip_prefix = "zulu9.0.7.1-jdk9.0.7-macosx_x64-allmodules",
    urls = [
        "https://mirror.bazel.build/openjdk/azul-zulu-9.0.7.1-jdk9.0.7/zulu9.0.7.1-jdk9.0.7-macosx_x64-allmodules.tar.gz",
    ],
)

http_archive(
    name = "remotejdk_win",
    build_file = "@local_jdk//:BUILD.bazel",
    sha256 = "e738829017f107e7a7cd5069db979398ec3c3f03ef56122f89ba38e7374f63ed",
    strip_prefix = "zulu9.0.7.1-jdk9.0.7-win_x64-allmodules",
    urls = [
        "https://mirror.bazel.build/openjdk/azul-zulu-9.0.7.1-jdk9.0.7/zulu9.0.7.1-jdk9.0.7-win_x64-allmodules.zip",
    ],
)

# The source-code for this OpenJDK can be found at:
# https://openjdk.linaro.org/releases/jdk9-src-1708.tar.xz
http_archive(
    name = "remotejdk_linux_aarch64",
    build_file = "@local_jdk//:BUILD.bazel",
    sha256 = "72e7843902b0395e2d30e1e9ad2a5f05f36a4bc62529828bcbc698d54aec6022",
    strip_prefix = "jdk9-server-release-1708",
    urls = [
        # When you update this, also update the link to the source-code above.
        "https://mirror.bazel.build/openjdk.linaro.org/releases/jdk9-server-release-1708.tar.xz",
        "http://openjdk.linaro.org/releases/jdk9-server-release-1708.tar.xz",
    ],
)

http_archive(
    name = "remotejdk10_linux",
    build_file = "@local_jdk//:BUILD.bazel",
    sha256 = "57fad3602e74c79587901d6966d3b54ef32cb811829a2552163185d5064fe9b5",
    strip_prefix = "zulu10.2+3-jdk10.0.1-linux_x64-allmodules",
    urls = [
        "https://mirror.bazel.build/openjdk/azul-zulu10.2+3-jdk10.0.1/zulu10.2+3-jdk10.0.1-linux_x64-allmodules.tar.gz",
    ],
)

http_archive(
    name = "remotejdk10_macos",
    build_file = "@local_jdk//:BUILD.bazel",
    sha256 = "e669c9a897413d855b550b4e39d79614392e6fb96f494e8ef99a34297d9d85d3",
    strip_prefix = "zulu10.2+3-jdk10.0.1-macosx_x64-allmodules",
    urls = [
        "https://mirror.bazel.build/openjdk/azul-zulu10.2+3-jdk10.0.1/zulu10.2+3-jdk10.0.1-macosx_x64-allmodules.tar.gz",
    ],
)

http_archive(
    name = "remotejdk10_win",
    build_file = "@local_jdk//:BUILD.bazel",
    sha256 = "c39e7700a8d41794d60985df5a20352435196e78ecbc6a2b30df7be8637bffd5",
    strip_prefix = "zulu10.2+3-jdk10.0.1-win_x64-allmodules",
    urls = [
        "https://mirror.bazel.build/openjdk/azul-zulu10.2+3-jdk10.0.1/zulu10.2+3-jdk10.0.1-win_x64-allmodules.zip",
    ],
)

# The source-code for this OpenJDK can be found at:
# https://openjdk.linaro.org/releases/jdk10-src-1804.tar.xz
http_archive(
    name = "remotejdk10_linux_aarch64",
    build_file = "@local_jdk//:BUILD.bazel",
    sha256 = "b7098b7aaf6ee1ffd4a2d0371a0be26c5a5c87f6aebbe46fe9a92c90583a84be",
    strip_prefix = "jdk10-server-release-1804",
    urls = [
        # When you update this, also update the link to the source-code above.
        "https://mirror.bazel.build/openjdk.linaro.org/releases/jdk10-server-release-1804.tar.xz",
        "http://openjdk.linaro.org/releases/jdk10-server-release-1804.tar.xz",
    ],
)

http_archive(
    name = "remotejdk11_linux",
    build_file = "@local_jdk//:BUILD.bazel",
    sha256 = "232b1c3511f0d26e92582b7c3cc363be7ac633e371854ca2f2e9f2b50eb72a75",
    strip_prefix = "zulu11.2.3-jdk11.0.1-linux_x64",
    urls = [
        "https://mirror.bazel.build/openjdk/azul-zulu11.2.3-jdk11.0.1/zulu11.2.3-jdk11.0.1-linux_x64.tar.gz",
    ],
)

http_archive(
    name = "remotejdk11_linux_aarch64",
    build_file = "@local_jdk//:BUILD.bazel",
    sha256 = "3b0d91611b1bdc4d409afcf9eab4f0e7f4ae09f88fc01bd9f2b48954882ae69b",
    strip_prefix = "zulu11.31.15-ca-jdk11.0.3-linux_aarch64",
    urls = [
        "https://mirror.bazel.build/openjdk/azul-zulu11.31.15-ca-jdk11.0.3/zulu11.31.15-ca-jdk11.0.3-linux_aarch64.tar.gz",
    ],
)

http_archive(
    name = "remotejdk11_macos",
    build_file = "@local_jdk//:BUILD.bazel",
    sha256 = "1edf366ee821e5db8e348152fcb337b28dfd6bf0f97943c270dcc6747cedb6cb",
    strip_prefix = "zulu11.2.3-jdk11.0.1-macosx_x64",
    urls = [
        "https://mirror.bazel.build/openjdk/azul-zulu11.2.3-jdk11.0.1/zulu11.2.3-jdk11.0.1-macosx_x64.tar.gz",
    ],
)

http_archive(
    name = "remotejdk11_win",
    build_file = "@local_jdk//:BUILD.bazel",
    sha256 = "8e1e2b8347de6746f3fd1538840dd643201533ab113abc4ed93678e342d28aa3",
    strip_prefix = "zulu11.2.3-jdk11.0.1-win_x64",
    urls = [
        "https://mirror.bazel.build/openjdk/azul-zulu11.2.3-jdk11.0.1/zulu11.2.3-jdk11.0.1-win_x64.zip",
    ],
)

http_archive(
    name = "remote_java_tools_linux",
    sha256 = "96e223094a12c842a66db0bb7bb6866e88e26e678f045842911f9bd6b47161f5",
    urls = [
        "https://mirror.bazel.build/bazel_java_tools/releases/javac11/v4.0/java_tools_javac11_linux-v4.0.zip",
    ],
)

http_archive(
    name = "remote_java_tools_windows",
    sha256 = "a1de51447b2ba2eab923d589ba6c72c289c16e6091e6a3bb3e67a05ef4ad200c",
    urls = [
        "https://mirror.bazel.build/bazel_java_tools/releases/javac11/v4.0/java_tools_javac11_windows-v4.0.zip",
    ],
)

http_archive(
    name = "remote_java_tools_darwin",
    sha256 = "fbf5bf22e9aab9c622e4c8c59314a1eef5ea09eafc5672b4f3250dc0b971bbcc",
    urls = [
        "https://mirror.bazel.build/bazel_java_tools/releases/javac11/v4.0/java_tools_javac11_darwin-v4.0.zip",
    ],
)

maybe(
    http_archive,
    "rules_java",
    #sha256 = "89794de7ea4146ac2a7b8341606ec74b1b185356b04c4f7f5ded0af57fd0e301",
    strip_prefix = "rules_java-d6c2f58d05a19c6043488e79e8c384fed7260578",
    urls = [
        "https://github.com/bazelbuild/rules_java/archive/1da23726bf21067c26b92d3619e95ce88d1db125.zip"
    ],
)
load("@rules_java//java:repositories.bzl", "rules_java_dependencies", "rules_java_toolchains")
rules_java_dependencies()
rules_java_toolchains()

# Needed only because of java_tools.
maybe(
    http_archive,
    "rules_cc",
    sha256 = "36fa66d4d49debd71d05fba55c1353b522e8caef4a20f8080a3d17cdda001d89",
    urls = [
        "https://mirror.bazel.build/github.com/bazelbuild/rules_cc/archive/0d5f3f2768c6ca2faca0079a997a97ce22997a0c.zip",
        "https://github.com/bazelbuild/rules_cc/archive/0d5f3f2768c6ca2faca0079a997a97ce22997a0c.zip",
    ],
    strip_prefix = "rules_cc-0d5f3f2768c6ca2faca0079a997a97ce22997a0c",
)
