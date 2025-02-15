Project: /_project.yaml
Book: /_book.yaml

# Integrating Bazel with IDEs

{% include "_buttons.html" %}

This page covers how to integrate Bazel with IDEs, such as IntelliJ, Android
Studio, and CLion (or build your own IDE plugin). It also includes links to
installation and plugin details.

IDEs integrate with Bazel in a variety of ways, from features that allow Bazel
executions from within the IDE, to awareness of Bazel structures such as syntax
highlighting of the `BUILD` files.

If you are interested in developing an editor or IDE plugin for Bazel, please
join the `#ide` channel on the [Bazel Slack](https://slack.bazel.build) or start
a discussion on [GitHub](https://github.com/bazelbuild/bazel/discussions).

## IDEs and editors {:#ides-editors}

### IntelliJ, Android Studio, and CLion {:#intellij-android-clion}

[Official plugin](http://ij.bazel.build) for IntelliJ, Android Studio, and
CLion. The plugin is [open source](https://github.com/bazelbuild/intellij){: .external}.

This is the open source version of the plugin used internally at Google.

Features:

* Interop with language-specific plugins. Supported languages include Java,
  Scala, and Python.
* Import `BUILD` files into the IDE with semantic awareness of Bazel targets.
* Make your IDE aware of Starlark, the language used for Bazel's `BUILD` and
  `.bzl`files
* Build, test, and execute binaries directly from the IDE
* Create configurations for debugging and running binaries.

To install, go to the IDE's plugin browser and search for `Bazel`.

To manually install older versions, download the zip files from JetBrains'
Plugin Repository and install the zip file from the IDE's plugin browser:

*  [Android Studio
   plugin](https://plugins.jetbrains.com/plugin/9185-android-studio-with-bazel){: .external}
*  [IntelliJ
   plugin](https://plugins.jetbrains.com/plugin/8609-intellij-with-bazel){: .external}
*  [CLion plugin](https://plugins.jetbrains.com/plugin/9554-clion-with-bazel){: .external}

### Xcode {:#xcode}

[rules_xcodeproj](https://github.com/buildbuddy-io/rules_xcodeproj){: .external},
[Tulsi](https://tulsi.bazel.build){: .external}, and
[XCHammer](https://github.com/pinterest/xchammer){: .external} generate Xcode
projects from Bazel `BUILD` files.

### Visual Studio Code {:#visual-studio-code}

Official plugin for VS Code.

Features:

* Bazel Build Targets tree
* Starlark debugger for `.bzl` files during a build (set breakpoints, step
  through code, inspect variables, and so on)

Find [the plugin on the Visual Studio
marketplace](https://marketplace.visualstudio.com/items?itemName=BazelBuild.vscode-bazel){: .external}.
The plugin is [open source](https://github.com/bazelbuild/vscode-bazel){: .external}.

See also: [Autocomplete for Source Code](#autocomplete-for-source-code)

### Atom {:#atom}

Find the [`language-bazel` package](https://atom.io/packages/language-bazel){: .external}
on the Atom package manager.

See also: [Autocomplete for Source Code](#autocomplete-for-source-code)

### Vim {:#vim}

See [`bazelbuild/vim-bazel` on GitHub](https://github.com/bazelbuild/vim-bazel){: .external}

See also: [Autocomplete for Source Code](#autocomplete-for-source-code)

### Emacs {:#emacs}

See [`bazelbuild/bazel-emacs-mode` on
GitHub](https://github.com/bazelbuild/emacs-bazel-mode){: .external}

See also: [Autocomplete for Source Code](#autocomplete-for-source-code)

### Visual Studio {:#visual-studio}

[Lavender](https://github.com/tmandry/lavender){: .external} is an experimental project for
generating Visual Studio projects that use Bazel for building.

### Eclipse {:#eclipse}

[Bazel Eclipse Feature](https://github.com/salesforce/bazel-eclipse){: .external}
is a set of plugins for importing Bazel packages into an Eclipse workspace as
Eclipse projects.

## Autocomplete for Source Code {:#autocomplete-for-source-code}

### C Language Family (C++, C, Objective-C, Objective-C++, and CUDA)

[`kiron1/bazel-compile-commands`](https://github.com/kiron1/bazel-compile-commands){: .external}
run `bazel-compile-commands //...` in a Bazel workspace to generate a `compile_commands.json` file.
The `compile_commands.json` file enables tools like `clang-tidy`, `clangd` (LSP) and other IDEs to
provide autocomplete, smart navigation, quick fixes, and more. The tool is written in C++ and
consumes the Protobuf output of Bazel to extract the compile commands.

[`hedronvision/bazel-compile-commands-extractor`](https://github.com/hedronvision/bazel-compile-commands-extractor) enables autocomplete, smart navigation, quick fixes, and more in a wide variety of extensible editors, including VSCode, Vim, Emacs, Atom, and Sublime. It lets language servers, like clangd and ccls, and other types of tooling, draw upon Bazel's understanding of how `cc` and `objc` code will be compiled, including how it configures cross-compilation for other platforms.

### Java

[`georgewfraser/java-language-server`](https://github.com/georgewfraser/java-language-server) - Java Language Server (LSP) with support for Bazel-built projects

## Automatically run build and test on file change {:#bazel-watcher}

[Bazel watcher](https://github.com/bazelbuild/bazel-watcher){: .external} is a
tool for building Bazel targets when source files change.

## Building your own IDE plugin {:#build-own-plugin}

Read the [**IDE support** blog
post](https://blog.bazel.build/2016/06/10/ide-support.html) to learn more about
the Bazel APIs to use when building an IDE plugin.
