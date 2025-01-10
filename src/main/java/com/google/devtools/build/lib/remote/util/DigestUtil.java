// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote.util;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.nio.charset.StandardCharsets.UTF_8;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.DigestFunction;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.io.BaseEncoding;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient.ActionKey;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.DigestUtils;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.XattrProvider;
import com.google.protobuf.Message;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/** Utility methods to work with {@link Digest}. */
public class DigestUtil {
  private final XattrProvider xattrProvider;
  private final DigestHashFunction hashFn;
  private final DigestFunction.Value digestFunction;

  public DigestUtil(XattrProvider xattrProvider, DigestHashFunction hashFn) {
    this.xattrProvider = xattrProvider;
    this.hashFn = hashFn;
    this.digestFunction = getDigestFunctionFromHashFunction(hashFn);
  }

  private static final ImmutableSet<String> DIGEST_FUNCTION_NAMES =
      Arrays.stream(DigestFunction.Value.values()).map(Enum::name).collect(toImmutableSet());

  private static DigestFunction.Value getDigestFunctionFromHashFunction(DigestHashFunction hashFn) {
    for (String name : hashFn.getNames()) {
      if (DIGEST_FUNCTION_NAMES.contains(name)) {
        return DigestFunction.Value.valueOf(name);
      }
    }
    return DigestFunction.Value.UNKNOWN;
  }

  /** Returns the currently used digest function. */
  public DigestFunction.Value getDigestFunction() {
    return digestFunction;
  }

  public Digest compute(byte[] blob) {
    return buildDigest(hashFn.getHashFunction().hashBytes(blob).toString(), blob.length);
  }

  /**
   * Computes a digest for a file.
   *
   * <p>Prefer calling {@link #compute(Path, FileStatus)} when a recently obtained {@link
   * FileStatus} is available.
   *
   * @param path the file path
   */
  public Digest compute(Path path) throws IOException {
    return compute(path, path.stat());
  }

  /**
   * Computes a digest for a file.
   *
   * @param path the file path
   * @param status a recently obtained file status, if available
   */
  public Digest compute(Path path, FileStatus status) throws IOException {
    return buildDigest(
        DigestUtils.getDigestWithManualFallback(path, xattrProvider, status), status.getSize());
  }

  public static Digest compute(VirtualActionInput input, HashFunction hashFunction)
      throws IOException {
    // Stream the virtual action input as parameter files, which can be very large, are lazily
    // computed from the in-memory CommandLine object. This avoids allocating large byte arrays.
    try (DigestOutputStream digestOutputStream =
        new DigestOutputStream(hashFunction, OutputStream.nullOutputStream())) {
      input.writeTo(digestOutputStream);
      return digestOutputStream.digest();
    }
  }

  public Digest compute(VirtualActionInput input) throws IOException {
    return compute(input, hashFn.getHashFunction());
  }

  /**
   * Computes a digest of the given proto message. Currently, we simply rely on message output as
   * bytes, but this implementation relies on the stability of the proto encoding, in particular
   * between different platforms and languages. TODO(olaola): upgrade to a better implementation!
   */
  public Digest compute(Message message) {
    return compute(message.toByteArray());
  }

  public Digest computeAsUtf8(String str) {
    return compute(str.getBytes(UTF_8));
  }

  public ActionKey computeActionKey(Action action) {
    return new ActionKey(compute(action));
  }

  /**
   * Assumes that the given Digest is a valid digest of an Action, and creates an ActionKey wrapper.
   * This should not be called on the client side!
   */
  public ActionKey asActionKey(Digest digest) {
    return new ActionKey(digest);
  }

  public com.google.devtools.build.lib.exec.Protos.Digest asSpawnLogProto(ActionKey actionKey) {
    return com.google.devtools.build.lib.exec.Protos.Digest.newBuilder()
        .setHash(actionKey.getDigest().getHash())
        .setSizeBytes(actionKey.getDigest().getSizeBytes())
        .setHashFunctionName(getDigestFunction().toString())
        .build();
  }

  /** Returns the hash of {@code data} in binary. */
  public byte[] hash(byte[] data) {
    return hashFn.getHashFunction().hashBytes(data).asBytes();
  }

  public static Digest buildDigest(byte[] hash, long size) {
    return buildDigest(HashCode.fromBytes(hash).toString(), size);
  }

  public static Digest buildDigest(String hexHash, long size) {
    return Digest.newBuilder().setHash(hexHash).setSizeBytes(size).build();
  }

  public static String hashCodeToString(HashCode hash) {
    return BaseEncoding.base16().lowerCase().encode(hash.asBytes());
  }

  public DigestOutputStream newDigestOutputStream(OutputStream out) {
    return new DigestOutputStream(hashFn.getHashFunction(), out);
  }

  public static String toString(Digest digest) {
    return digest.getHash() + "/" + digest.getSizeBytes();
  }

  public static byte[] toBinaryDigest(Digest digest) {
    return HashCode.fromString(digest.getHash()).asBytes();
  }

  public static boolean isOldStyleDigestFunction(DigestFunction.Value digestFunction) {
    // Old-style digest functions (SHA256, etc) are distinguishable by the length
    // of their hash alone and do not require extra specification, but newer
    // digest functions (which may have the same length hashes as the older
    // functions!) must be explicitly specified in the upload resource name.
    return digestFunction.getNumber() <= 7;
  }
}
