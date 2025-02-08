#!/bin/bash -eu
#
# Copyright 2015 The Bazel Authors. All rights reserved.
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

# TODO(bazel-team) test that modifying the source in a non-interface
#   changing way results in the same -interface.jar.

# --- begin runfiles.bash initialization v3 ---
# Copy-pasted from the Bazel Bash runfiles library v3.
set -uo pipefail; set +e; f=bazel_tools/tools/bash/runfiles/runfiles.bash
source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
# shellcheck disable=SC1090
  source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
  source "$0.runfiles/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
# --- end runfiles.bash initialization v3 ---

DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

## Inputs
JAVABASE="$1"
if [[ "$TEST_WORKSPACE" == "_main" ]]; then
  # For Bazel
  RUNFILES_JAVABASE=${JAVABASE#external/}
else
  # For Blaze
  RUNFILES_JAVABASE=${TEST_WORKSPACE}/${JAVABASE}
fi
shift
IJAR=$1
shift
UNZIP=$1
shift
ZIP=$1
shift
ZIP_COUNT=$1
shift

## Test framework
source ${DIR}/testenv.sh || { echo "testenv.sh not found!" >&2; exit 1; }

function cleanup() {
  rm -fr "${TEST_TMPDIR:-sentinel}"/*
}

trap cleanup EXIT

JAVAC=$(rlocation "$RUNFILES_JAVABASE/bin/javac")
JAVA=$(rlocation "$RUNFILES_JAVABASE/bin/java")
JAR=$(rlocation "$RUNFILES_JAVABASE/bin/jar")
JAVAP=$(rlocation "$RUNFILES_JAVABASE/bin/javap")

## Tools
# Ensure that tooling path is absolute if not in PATH.
[[ "$JAVAC" =~ ^(/|[^/]+$) ]] || JAVAC="$PWD/$JAVAC"
[[ "$JAR" =~ ^(/|[^/]+$) ]] || JAR="$PWD/$JAR"
[[ "$IJAR" =~ ^(/|[^/]+$) ]] || IJAR="$PWD/$IJAR"
[[ "$UNZIP" =~ ^(/|[^/]+$) ]] || UNZIP="$PWD/$UNZIP"
[[ "$ZIP" =~ ^(/|[^/]+$) ]] || ZIP="$PWD/$ZIP"
[[ "$JAVAP" =~ ^(/|[^/]+$) ]] || JAVAP="$PWD/$JAVAP"
[[ "$ZIP_COUNT" =~ ^(/|[^/]+$) ]] || ZIP_COUNT="$PWD/$ZIP_COUNT"

IJAR_SRCDIR=$(dirname ${IJAR})
A_JAR=$TEST_TMPDIR/A.jar
A_INTERFACE_JAR=$TEST_TMPDIR/A-interface.jar
A_ZIP_JAR=$TEST_TMPDIR/A_zip.jar
A_ZIP_INTERFACE_JAR=$TEST_TMPDIR/A_zip-interface.jar
W_JAR=$TEST_TMPDIR/W.jar
BOTTLES_JAR=$TEST_TMPDIR/bottles.jar
JAR_WRONG_CENTRAL_DIR=$IJAR_SRCDIR/test/libwrongcentraldir.jar
IJAR_WRONG_CENTRAL_DIR=$TEST_TMPDIR/wrongcentraldir_interface.jar
OBJECT_JAVA=$IJAR_SRCDIR/test/Object.java
OBJECT_JAR=$TEST_TMPDIR/object.jar
OBJECT_IJAR=$TEST_TMPDIR/object_interface.jar
TYPEANN2_JAR=$IJAR_SRCDIR/test/libtypeannotations2.jar
TYPEANN2_IJAR=$TEST_TMPDIR/typeannotations2_interface.jar
TYPEANN2_JAVA=$IJAR_SRCDIR/test/TypeAnnotationTest2.java
INVOKEDYNAMIC_JAR=$IJAR_SRCDIR/test/libinvokedynamic.jar
INVOKEDYNAMIC_IJAR=$TEST_TMPDIR/invokedynamic_interface.jar
METHODPARAM_JAR=$IJAR_SRCDIR/test/libmethodparameters.jar
METHODPARAM_IJAR=$TEST_TMPDIR/methodparameters_interface.jar
NESTMATES_JAR=$IJAR_SRCDIR/test/nestmates/nestmates.jar
NESTMATES_IJAR=$TEST_TMPDIR/nestmates_interface.jar
RECORDS_JAR=$IJAR_SRCDIR/test/records/records.jar
RECORDS_IJAR=$TEST_TMPDIR/records_interface.jar
SEALED_JAR=$IJAR_SRCDIR/test/sealed/sealed.jar
SEALED_IJAR=$TEST_TMPDIR/sealed_interface.jar
SOURCEDEBUGEXT_JAR=$IJAR_SRCDIR/test/source_debug_extension.jar
SOURCEDEBUGEXT_IJAR=$TEST_TMPDIR/source_debug_extension.jar
DUPLICATEDUNSUPPORTEDATTRIBUTE_JAR=$IJAR_SRCDIR/test/duplicated_unsupported_attribute.jar
DUPLICATEDUNSUPPORTEDATTRIBUTE_IJAR=$TEST_TMPDIR/duplicated_unsupported_attribute.jar
CENTRAL_DIR_LARGEST_REGULAR=$IJAR_SRCDIR/test/largest_regular.jar
CENTRAL_DIR_SMALLEST_ZIP64=$IJAR_SRCDIR/test/smallest_zip64.jar
CENTRAL_DIR_ZIP64=$IJAR_SRCDIR/test/definitely_zip64.jar
KEEP_FOR_COMPILE=$IJAR_SRCDIR/test/keep_for_compile_lib.jar
DYNAMICCONSTANT_JAR=$IJAR_SRCDIR/test/dynamic_constant.jar
DYNAMICCONSTANT_IJAR=$TEST_TMPDIR/dynamic_constant_interface.jar

#### Setup

# set_file_length FILE SIZE
#
# Sets the file size for FILE, truncating if necessary, creating a
# sparse file if possible, preserving original contents if they fit.
function set_file_length() {
  perl -e 'open(FH, ">>$ARGV[0]") && truncate(FH, $ARGV[1]) or fail $!' "$@" &&
  [[ "$(statfmt $1)" == "$2" ]] ||
  fail "set_file_length failed"
}

# check_consistent_file_contents FILE
#
# Checks that all files created with the given filename have identical contents.
expected_output=""
function check_consistent_file_contents() {
  local actual="$(cat $1 | ${MD5SUM} | awk '{ print $1; }')"
  local filename="$(echo $1 | ${MD5SUM} | awk '{ print $1; }')"
  local expected="$actual"
  if (grep -q "^${filename} " <<<"${expected_output}"); then
    grep -q "^${filename} ${actual}$" <<<"${expected_output}" || {
      ls -l "$1"
      fail "output file contents differ"
    }
  else
    expected_output="$expected_output$filename $actual
"
  fi
}

function set_up() {
  mkdir -p $TEST_TMPDIR/classes
}

function tear_down() {
  rm -fr $TEST_TMPDIR/classes
}

#### Tests
function test_output_bigger_than_input() {
  # Tests that ijar does not crash when output ijar is bigger than the input jar
  $JAVAC -g -d $TEST_TMPDIR/classes \
    $IJAR_SRCDIR/test/WellCompressed*.java ||
    fail "javac failed"
  $JAR cf $W_JAR -C $TEST_TMPDIR/classes . || fail "jar failed"

  W_INTERFACE_JAR=$TEST_TMPDIR/W-interface.jar
  $IJAR $W_JAR $W_INTERFACE_JAR || fail "ijar failed"
  # Check that the interface jar is bigger than the original jar.
  W_JAR_SIZE=$(statfmt $W_JAR)
  W_INTERFACE_JAR_SIZE=$(statfmt $W_INTERFACE_JAR)
  [[ $W_INTERFACE_JAR_SIZE -gt $W_JAR_SIZE ]] || fail "interface jar should be bigger"
}

function test_class_more_64k() {
  # Tests that ijar can handle class bodies longer than 64K
  # First, generate the input file
  BOTTLES_JAVA=$TEST_TMPDIR/BottlesOnTheWall.java
  echo "public class BottlesOnTheWall {" > $BOTTLES_JAVA
  for i in $(seq 1 16384); do
    echo "  public int getBottleOnTheWall${i}() { return ${i}; }" >> $BOTTLES_JAVA
  done

  echo "}" >> $BOTTLES_JAVA

  $JAVAC -g -d $TEST_TMPDIR/classes $BOTTLES_JAVA || fail "javac failed"
  BOTTLES_INTERFACE_JAR=$TEST_TMPDIR/bottles-interface.jar

  # Test ijar calls
  for flag0 in '' '0'; do
    $JAR c"${flag0}"f $BOTTLES_JAR -C $TEST_TMPDIR/classes . || fail "jar failed"
    $IJAR $BOTTLES_JAR $BOTTLES_INTERFACE_JAR || fail "ijar failed"
    check_consistent_file_contents $BOTTLES_INTERFACE_JAR
  done

  # Check that ijar can produce class files with a body longer than 64K by
  # calling ijar itself on the output file to make sure that it is valid
  BOTTLES_INTERFACE_INTERFACE_JAR=$TEST_TMPDIR/bottles-interface-interface.jar
  $IJAR $BOTTLES_INTERFACE_JAR $BOTTLES_INTERFACE_INTERFACE_JAR ||
    fail "ijar cannot produce class files with body longer than 64K"
}

function test_ijar_output() {
  # Numerous check on the output created by ijar.

  # Compiles A.java, builds A.jar and A-interface.jar
  $JAVAC -g -d $TEST_TMPDIR/classes $IJAR_SRCDIR/test/A.java ||
    fail "javac failed"
  $JAR cf $A_JAR -C $TEST_TMPDIR/classes . || fail "jar failed"
  $IJAR $A_JAR $A_INTERFACE_JAR || fail "ijar failed."

  # Check that the number of entries is 5:
  #  A, A.PrivateInner, A.PublicInner, A.MyAnnotation,
  #  A.RuntimeAnnotation
  # (Note: even private inner classes are retained, so we don't need to change
  # the types of members.)
  local expected=5
  local lines
  lines=$($JAR tvf $A_INTERFACE_JAR | wc -l)
  check_eq $expected $lines "Interface jar should have $expected entries!"

  # Check that no private class members are found:
  lines=$($JAVAP -private -classpath $A_JAR A | grep -c priv || true)
  check_eq 2 $lines "Input jar should have 2 private members!"
  lines=$($JAVAP -private -classpath $A_INTERFACE_JAR A | grep -c priv || true)
  check_eq 0 $lines "Interface jar should have no private members!"
  lines=$($JAVAP -private -classpath $A_INTERFACE_JAR A | grep -c clinit || true)
  check_eq 0 $lines "Interface jar should have no class initializers!"


  # Check that no code is found:
  lines=$($JAVAP -c -private -classpath $A_JAR A | grep -c Code: || true)
  check_eq 5 $lines "Input jar should have 5 method bodies!"
  lines=$($JAVAP -c -private -classpath $A_INTERFACE_JAR A | grep -c Code: || true)
  check_eq 0 $lines "Interface jar should have no method bodies!"

  # Check that constants from code are no longer present:
  $JAVAP -c -private -classpath $A_JAR A | grep -sq foofoofoofoo ||
    fail "Input jar should have code constants!"
  $JAVAP -c -private -classpath $A_INTERFACE_JAR A | grep -sq foofoofoofoo &&
    fail "Interface jar should have no code constants!"


  # Check (important, this!) that the interface jar is still sufficient
  # for compiling:
  $JAVAC -Xlint -classpath $A_INTERFACE_JAR -g -d $TEST_TMPDIR/classes \
    $IJAR_SRCDIR/test/B.java 2>$TEST_log || fail "Can't compile B!"

  # Test compilation of B yielded deprecation message:
  expect_log 'deprecatedMethod.*in A has been deprecated' \
    "ijar has lost @Deprecated annotation!"

  # Run the dynamic checks in B.main().
  $JAVA -classpath $TEST_TMPDIR/classes B || exit 1

  # Check idempotence of ijar transformation:
  A_INTERFACE_INTERFACE_JAR=$TEST_TMPDIR/A-interface-interface.jar
  $IJAR $A_INTERFACE_JAR $A_INTERFACE_INTERFACE_JAR || fail "ijar failed."
  cmp $A_INTERFACE_JAR $A_INTERFACE_INTERFACE_JAR ||
    fail "ijar transformation is not idempotent"


  # Check that -interface.jar contains nothing but .class files:
  check_eq 0 $($JAR tf $A_INTERFACE_JAR | grep -cv \\.class$ || true) \
    "Interface jar should contain only .class files!"


  # Check that -interface.jar timestamps are normalized:
  check_eq 0 $(TZ=UTC $JAR tvf $A_INTERFACE_JAR |
               grep -cv 'Fri Jan 01 00:00:00 UTC 2010' || true) \
   "Interface jar contained non-zero timestamps!"


  # Check that compile-time constants in A are still annotated as such in ijar:
  $JAVAP -classpath $TEST_TMPDIR/classes -c B | grep -sq 'ldc2_w.*123' ||
    fail "ConstantValue not propagated to class B!"


  # Check that a jar compressed with zip results in the same interface jar as a
  # jar compressed with jar
  rm -fr $TEST_TMPDIR/classes
  mkdir -p $TEST_TMPDIR/classes || fail "mkdir $TEST_TMPDIR/classes failed"
  $JAVAC -g -d $TEST_TMPDIR/classes $IJAR_SRCDIR/test/A.java ||
    fail "javac failed"
  $JAR cf $A_JAR $TEST_TMPDIR/classes/A.class || fail "jar failed"
  $ZIP $A_ZIP_JAR $TEST_TMPDIR/classes/A.class || fail "zip failed"

  $IJAR $A_JAR $A_INTERFACE_JAR || fail "ijar failed"
  $IJAR $A_ZIP_JAR $A_ZIP_INTERFACE_JAR || fail "ijar failed"
  cmp $A_INTERFACE_JAR $A_ZIP_INTERFACE_JAR || \
    fail "ijars from jar and zip are different"
}

function do_test_large_file() {
  # Compiles A.java, builds A.jar and A-interface.jar
  $JAVAC -g -d $TEST_TMPDIR/classes $IJAR_SRCDIR/test/A.java ||
    fail "javac failed"

  # First a check without large file to have something to compare to.
  for flag0 in '' '0'; do
    $JAR c"${flag0}"f $A_JAR -C $TEST_TMPDIR/classes . || fail "jar failed"
    $IJAR $A_JAR $A_INTERFACE_JAR || fail "ijar failed."
    check_consistent_file_contents $A_INTERFACE_JAR
  done

  # Then create larges files
  extra_args=""
  if [[ -n "${1-}" ]]; then
    for file in $(find $TEST_TMPDIR/classes -name '*.class'); do
      set_file_length "$file" "$1"
    done
  fi
  if [[ -n "${2-}" ]]; then
    set_file_length $TEST_TMPDIR/zeroes.data "$2"
    extra_args="-C $TEST_TMPDIR zeroes.data"
  fi

  for flag0 in '' '0'; do
    $JAR c"${flag0}"f $A_JAR $extra_args -C $TEST_TMPDIR/classes . || fail "jar failed"
    $IJAR $A_JAR $A_INTERFACE_JAR || fail "ijar failed."
    check_consistent_file_contents $A_INTERFACE_JAR
  done
}

function test_large_files() {
  # Ensure input files larger than INITIAL_BUFFER_SIZE work.
  # TODO(martinrb): remove maximum .class file size limit (MAX_BUFFER_SIZE)
  for size in $((1024*1024)) $((15*1024*1024)); do
      do_test_large_file $size
  done
}

# Create a huge (~2.2Gb) input jar to test "large file" correctness
function test_z_2gb_plus_data_file() {
  # This is slow because only writing a 2.2Gb file on a SSD drive is >10s and
  # jaring it takes >16s.
  # The z letter in the function name is to ensure that method is last in the
  # method list so it has more chance to be alone on a shard.
  do_test_large_file '' $((22*102*1024*1024))
}

# Create an output jar with upper bound on size > 2GB
function test_upper_bound_up_2gb() {
  DIR=$TEST_TMPDIR/ManyLargeClasses
  mkdir -p $DIR/classes
  for i in $(seq 200); do
    printf "class C${i} {}\n" > $DIR/C${i}.java
  done
  (cd $DIR && $JAVAC -d classes *.java)
  for i in $(seq 200); do
    set_file_length $DIR/classes/C${i}.class $((15*1024*1024))
  done
  $JAR cf $DIR/ManyLargeClasses.jar -C $DIR/classes . || fail "jar failed"
  $IJAR $DIR/ManyLargeClasses.jar $DIR/ManyLargeClasses.ijar || fail "ijar failed."
}

function test_empty_jar() {
  # Regression test for jar file without classes (javac doesn't like an empty ijar).
  >$TEST_TMPDIR/empty
  $ZIP $TEST_TMPDIR/noclasses.jar $TEST_TMPDIR/empty >/dev/null 2>&1
  $IJAR $TEST_TMPDIR/noclasses.jar || fail "ijar failed"
  $UNZIP -qql $TEST_TMPDIR/noclasses-interface.jar 2>/dev/null | grep -q . ||
    fail "noclasses-interface.jar is completely empty!"
  $JAVAC -classpath $TEST_TMPDIR/noclasses-interface.jar \
    -d $TEST_TMPDIR/classes \
    $IJAR_SRCDIR/test/A.java ||
    fail "javac noclasses-interface.jar failed"
  rm $TEST_TMPDIR/{empty,noclasses.jar,noclasses-interface.jar}
}

function test_wrong_centraldir() {
  # Check that a JAR file can be parsed even if the central directory file count
  # is wrong
  $IJAR $JAR_WRONG_CENTRAL_DIR $IJAR_WRONG_CENTRAL_DIR || fail "ijar failed"
  IJAR_FILES=$($UNZIP -qql $IJAR_WRONG_CENTRAL_DIR | wc -l | xargs echo)
  if [[ $IJAR_FILES != 2 ]]; then
    fail "ijar removed files"
  fi
}

function test_type_annotation() {
  # Check that constant pool references used by JSR308 type annotations are
  # preserved
  $IJAR $TYPEANN2_JAR $TYPEANN2_IJAR || fail "ijar failed"
  $JAVAP -classpath $TYPEANN2_IJAR -v Util >& $TEST_log || fail "javap failed"
  expect_log "RuntimeVisibleTypeAnnotations" "RuntimeVisibleTypeAnnotations not preserved!"
  cp $TYPEANN2_JAVA $TEST_TMPDIR/TypeAnnotationTest2.java
  $JAVAC $TEST_TMPDIR/TypeAnnotationTest2.java -cp $TYPEANN2_IJAR ||
    fail "javac failed"
}

function test_invokedynamic() {
  # Check that ijar works on classes with invokedynamic
  $IJAR $INVOKEDYNAMIC_JAR $INVOKEDYNAMIC_IJAR || fail "ijar failed"
  lines=$($JAVAP -c -private -classpath $INVOKEDYNAMIC_JAR ClassWithLambda | grep -c Code: || true)
  check_eq 4 $lines "Input jar should have 4 method bodies!"
  lines=$($JAVAP -c -private -classpath $INVOKEDYNAMIC_IJAR ClassWithLambda | grep -c Code: || true)
  check_eq 0 $lines "Interface jar should have no method bodies!"
}

function test_object_class() {
  # Check that Object.class can be processed
  mkdir -p $TEST_TMPDIR/java/lang
  cp $OBJECT_JAVA $TEST_TMPDIR/java/lang/.
  $JAVAC -source 8 -target 8 $TEST_TMPDIR/java/lang/Object.java || fail "javac failed"
  $JAR cf $OBJECT_JAR -C $TEST_TMPDIR java/lang/Object.class || fail "jar failed"

  $IJAR $OBJECT_JAR $OBJECT_IJAR || fail "ijar failed"
}

function test_corrupted_end_of_centraldir() {
  # Check that the tool detects and reports a corrupted end of central directory
  # record condition
  CORRUPTED_JAR=$TEST_TMPDIR/corrupted.jar

  # First make the jar one byte longer
  cp $JAR_WRONG_CENTRAL_DIR $CORRUPTED_JAR
  chmod +w $CORRUPTED_JAR
  echo >> $CORRUPTED_JAR
  echo "Abort trap is expected"  # Show on the log that we expect failure.
  $IJAR $CORRUPTED_JAR 2> $TEST_log && fail "ijar should have failed" || status=$?
  check_ne 0 $status
  expect_log "missing end of central directory record"

  # Then make the jar one byte shorter than the original one
  let "NEW_SIZE = `statfmt $CORRUPTED_JAR` - 2"
  set_file_length $CORRUPTED_JAR $NEW_SIZE
  $IJAR $CORRUPTED_JAR 2> $TEST_log && fail "ijar should have failed" || status=$?
  check_ne 0 $status
  expect_log "missing end of central directory record"
}

function test_inner_class_argument() {
  cd $TEST_TMPDIR

  mkdir -p a b c
  cat > a/A.java <<EOF
package a;

public class A {
  public static class A2 {
    public int n;
  }
}
EOF

  cat > b/B.java <<EOF
package b;
import a.A;

public class B {
  public static void b(A.A2 arg) {
    System.out.println(arg.n);
  }
}
EOF

  cat > c/C.java <<EOF
package c;
import b.B;

public class C {
  public static void c() {
    B.b(null);
  }
}
EOF

  $JAVAC a/A.java b/B.java
  $JAR cf lib.jar {a,b}/*.class
  $JAVAC -cp lib.jar c/C.java

}

function test_inner_class_pruning() {
  cd $TEST_TMPDIR

  mkdir -p lib/l {one,two,three}/a

  cat > lib/l/L.java <<EOF
package l;

public class L {
  public static class I {
    public static class J {
      public static int number() {
        return 3;
      }
    }
    public static int number() {
      return 2;
    }
  }
}
EOF

  cat > one/a/A.java <<EOF
package a;

public class A {
  public static void message() {
    System.out.println("hello " + 1);
  }
}
EOF

  cat > two/a/A.java <<EOF
package a;

import l.L;

public class A {
  public static void message() {
    System.out.println("hello " + L.I.number());
  }
}
EOF

  cat > three/a/A.java <<EOF
package a;

import l.L;

public class A {
  public static void message() {
    System.out.println("hello " + L.I.J.number());
  }
}
EOF

  $JAVAC lib/l/L.java
  (cd lib; $JAR cf lib.jar l/*.class)
  $JAVAC one/a/A.java
  (cd one; $JAR cf one.jar a/*.class)
  $JAVAC two/a/A.java -classpath lib/lib.jar
  (cd two; $JAR cf two.jar a/*.class)
  $JAVAC three/a/A.java -classpath lib/lib.jar
  (cd three; $JAR cf three.jar a/*.class)

  $IJAR one/one.jar one/one-ijar.jar
  $IJAR one/one.jar two/two-ijar.jar
  $IJAR one/one.jar three/three-ijar.jar

  cmp one/one-ijar.jar two/two-ijar.jar
  cmp one/one-ijar.jar three/three-ijar.jar
}

function test_method_parameters_attribute() {
  # Check that Java 8 MethodParameters attributes are preserved
  $IJAR $METHODPARAM_JAR $METHODPARAM_IJAR || fail "ijar failed"
  $JAVAP -classpath $METHODPARAM_IJAR -v methodparameters.Test >& $TEST_log \
    || fail "javap failed"
  expect_log "MethodParameters" "MethodParameters not preserved!"
}

function test_dynamic_constant() {
  $IJAR $DYNAMICCONSTANT_JAR $DYNAMICCONSTANT_IJAR || fail "ijar failed"

  lines=$($JAVAP -c -private -classpath $DYNAMICCONSTANT_IJAR dynamicconstant.Test | grep -c Code: || true)
  check_eq 0 $lines "Interface jar should have no method bodies!"
}

function test_nestmates_attribute() {
  # Check that Java 11 NestMates attributes are preserved
  $IJAR $NESTMATES_JAR $NESTMATES_IJAR || fail "ijar failed"

  $JAVAP -classpath $NESTMATES_IJAR -v NestTest >& $TEST_log \
    || fail "javap failed"
  expect_log "NestMembers" "NestMembers not preserved!"

  $JAVAP -classpath $NESTMATES_IJAR -v 'NestTest$P' >& $TEST_log \
    || fail "javap failed"
  expect_log "NestHost" "NestHost not preserved!"
}

function test_records_attribute() {
  ls $IJAR $RECORDS_JAR

  # Check that Java 16 Records attributes are preserved
  $IJAR $RECORDS_JAR $RECORDS_IJAR || fail "ijar failed"

  $JAVAP -classpath $RECORDS_IJAR -v RecordTest >& $TEST_log \
    || fail "javap failed"
  expect_log "Record" "Records not preserved!"
}

function test_sealed_attribute() {
  ls $IJAR $SEALED_JAR

  # Check that Java 16 PermittedSubclasses attributes are preserved
  $IJAR $SEALED_JAR $SEALED_IJAR || fail "ijar failed"

  $JAVAP -classpath $SEALED_IJAR -v SealedTest >& $TEST_log \
    || fail "javap failed"
  expect_log "PermittedSubclasses" "PermittedSubclasses not preserved!"
}

function test_source_debug_extension_attribute() {
  # Check that SourceDebugExtension attributes are dropped without a warning
  $IJAR $SOURCEDEBUGEXT_JAR $SOURCEDEBUGEXT_IJAR >& $TEST_log || fail "ijar failed"
  expect_not_log "skipping unknown attribute"
  $JAVAP -classpath $SOURCEDEBUGEXT_IJAR -v sourcedebugextension.Test >& $TEST_log \
    || fail "javap failed"
  expect_not_log "SourceDebugExtension" "SourceDebugExtension preserved!"
}

function test_duplicated_unsupported_attribute() {
  # Check that unsupported attribute warnings are only emitted once
  $IJAR $DUPLICATEDUNSUPPORTEDATTRIBUTE_JAR $DUPLICATEDUNSUPPORTEDATTRIBUTE_IJAR >& $TEST_log || fail "ijar failed"
  expect_log_once 'skipping unknown attribute: "some_unknown_type"'
}

function test_keep_for_compile() {
  $IJAR --strip_jar $KEEP_FOR_COMPILE $TEST_TMPDIR/keep.jar \
    || fail "ijar failed"
  lines=$($JAVAP -classpath $TEST_TMPDIR/keep.jar -c -p \
    functions.car.CarInlineUtilsKt |
    grep -c "// Method kotlin/jvm/internal/Intrinsics.checkParameterIsNotNull" ||
    true)
  check_eq 2 $lines "Output jar should have kept method body"
}

function test_central_dir_largest_regular() {
  $IJAR $CENTRAL_DIR_LARGEST_REGULAR $TEST_TMPDIR/ijar.jar || fail "ijar failed"
  $ZIP_COUNT $TEST_TMPDIR/ijar.jar 65535 || fail
}

function test_central_dir_smallest_zip64() {
  $IJAR $CENTRAL_DIR_SMALLEST_ZIP64 $TEST_TMPDIR/ijar.jar || fail "ijar failed"
  $ZIP_COUNT $TEST_TMPDIR/ijar.jar 65536 || fail
}

function test_central_dir_zip64() {
  $IJAR $CENTRAL_DIR_ZIP64 $TEST_TMPDIR/ijar.jar || fail "ijar failed"
  $ZIP_COUNT $TEST_TMPDIR/ijar.jar 70000 || fail
}

run_suite "ijar tests"
