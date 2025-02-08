// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include <CoreServices/CoreServices.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>

#include <list>
#include <string>

namespace {

// A structure to pass around the FSEvents info and the list of paths.
struct JNIEventsDiffAwareness {
  // FSEvents run loop (thread)
  CFRunLoopRef runLoop;

  // FSEvents stream reference (reference to the listened stream)
  FSEventStreamRef stream;

  // If true, fsevents dropped events so we don't know what changed exactly.
  bool everything_changed;

  // List of paths that have been changed since last polling.
  std::list<std::string> paths;

  // Mutex to protect concurrent accesses to paths and everything_changed.
  pthread_mutex_t mutex;

  JNIEventsDiffAwareness() : everything_changed(false) {
    pthread_mutex_init(&mutex, nullptr);
  }

  ~JNIEventsDiffAwareness() { pthread_mutex_destroy(&mutex); }
};

// Callback called when an event is reported by the FSEvents API
void FsEventsDiffAwarenessCallback(ConstFSEventStreamRef streamRef,
                                   void *clientCallBackInfo, size_t numEvents,
                                   void *eventPaths,
                                   const FSEventStreamEventFlags eventFlags[],
                                   const FSEventStreamEventId eventIds[]) {
  char **paths = static_cast<char **>(eventPaths);

  JNIEventsDiffAwareness *info =
      static_cast<JNIEventsDiffAwareness *>(clientCallBackInfo);
  pthread_mutex_lock(&(info->mutex));
  for (size_t i = 0; i < numEvents; i++) {
    if ((eventFlags[i] & kFSEventStreamEventFlagMustScanSubDirs) != 0) {
      // Either we lost events or they were coalesced. Assume everything changed
      // and give up, which matches the fsevents documentation in that the
      // caller is expected to rescan the directory contents on its own.
      info->everything_changed = true;
      break;
    } else if ((eventFlags[i] & kFSEventStreamEventFlagItemIsDir) != 0 &&
        (eventFlags[i] & kFSEventStreamEventFlagItemRenamed) != 0) {
      // A directory was renamed. When this happens, fsevents may or may not
      // give us individual events about which files changed underneath, which
      // means we have to rescan the directories in order to know what changed.
      //
      // The problem is that we cannot rescan the source of the move to discover
      // which files "disappeared"... so we have no choice but to rescan
      // everything. Well, in theory, we could try to track directory inodes and
      // using those to guess which files within them moved... but that'd be way
      // too much complexity for this rather-uncommon use case.
      info->everything_changed = true;
      break;
    } else {
      info->paths.push_back(std::string(paths[i]));
    }
  }
  pthread_mutex_unlock(&(info->mutex));
}

extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_skyframe_MacOSXFsEventsDiffAwareness_create(
    JNIEnv *env, jobject fsEventsDiffAwareness, jobjectArray paths,
    jdouble latency) {
  // Create a FSEventStreamContext to pass around (env, fsEventsDiffAwareness)
  JNIEventsDiffAwareness *info = new JNIEventsDiffAwareness();

  FSEventStreamContext context;
  context.version = 0;
  context.info = static_cast<void *>(info);
  context.retain = nullptr;
  context.release = nullptr;
  context.copyDescription = nullptr;

  // Create a CFArrayRef of CFStringRef from the Java array of UTF-8 byte
  // strings.
  jsize length = env->GetArrayLength(paths);
  CFStringRef *pathsArray = new CFStringRef[length];
  for (int i = 0; i < length; i++) {
    jbyteArray path = (jbyteArray)env->GetObjectArrayElement(paths, i);
    jbyte *pathBytes = env->GetByteArrayElements(path, nullptr);
    jsize pathLength = env->GetArrayLength(path);
    pathsArray[i] = CFStringCreateWithBytes(
        nullptr, (UInt8 *)pathBytes, pathLength, kCFStringEncodingUTF8, false);
    env->ReleaseByteArrayElements(path, pathBytes, JNI_ABORT);
  }
  CFArrayRef pathsToWatch =
      CFArrayCreate(nullptr, (const void **)pathsArray, length, nullptr);
  delete[] pathsArray;
  info->stream = FSEventStreamCreate(
      nullptr, &FsEventsDiffAwarenessCallback, &context, pathsToWatch,
      kFSEventStreamEventIdSinceNow, static_cast<CFAbsoluteTime>(latency),
      kFSEventStreamCreateFlagNoDefer | kFSEventStreamCreateFlagFileEvents);

  // Save the info pointer to FSEventsDiffAwareness#nativePointer
  jbyteArray array = env->NewByteArray(sizeof(info));
  env->SetByteArrayRegion(array, 0, sizeof(info),
                          reinterpret_cast<const jbyte *>(&info));
  jclass clazz = env->GetObjectClass(fsEventsDiffAwareness);
  jfieldID fid = env->GetFieldID(clazz, "nativePointer", "J");
  env->SetLongField(fsEventsDiffAwareness, fid, reinterpret_cast<jlong>(info));
}

JNIEventsDiffAwareness *GetInfo(JNIEnv *env, jobject fsEventsDiffAwareness) {
  jclass clazz = env->GetObjectClass(fsEventsDiffAwareness);
  jfieldID fid = env->GetFieldID(clazz, "nativePointer", "J");
  jlong field = env->GetLongField(fsEventsDiffAwareness, fid);
  return reinterpret_cast<JNIEventsDiffAwareness *>(field);
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_skyframe_MacOSXFsEventsDiffAwareness_run(
    JNIEnv *env, jobject fsEventsDiffAwareness, jobject listening) {
  JNIEventsDiffAwareness *info = GetInfo(env, fsEventsDiffAwareness);
  info->runLoop = CFRunLoopGetCurrent();
  FSEventStreamScheduleWithRunLoop(info->stream, info->runLoop,
                                   kCFRunLoopDefaultMode);
  FSEventStreamStart(info->stream);

  jclass countDownLatchClass = env->GetObjectClass(listening);
  jmethodID countDownMethod =
      env->GetMethodID(countDownLatchClass, "countDown", "()V");
  env->CallVoidMethod(listening, countDownMethod);
  CFRunLoopRun();
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_google_devtools_build_lib_skyframe_MacOSXFsEventsDiffAwareness_poll(
    JNIEnv *env, jobject fsEventsDiffAwareness) {
  JNIEventsDiffAwareness *info = GetInfo(env, fsEventsDiffAwareness);
  pthread_mutex_lock(&(info->mutex));

  jobjectArray result;
  if (info->everything_changed) {
    result = nullptr;
  } else {
    jclass classByteArray = env->FindClass("[B");
    result = env->NewObjectArray(info->paths.size(), classByteArray, nullptr);
    int i = 0;
    for (auto it = info->paths.begin(); it != info->paths.end(); it++, i++) {
      jbyteArray path = env->NewByteArray(it->size());
      env->SetByteArrayRegion(path, 0, it->size(),
                              reinterpret_cast<const jbyte *>(it->c_str()));
      env->SetObjectArrayElement(result, i, path);
      env->DeleteLocalRef(path);
    }
  }

  info->everything_changed = false;
  info->paths.clear();

  pthread_mutex_unlock(&(info->mutex));
  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_skyframe_MacOSXFsEventsDiffAwareness_doClose(
    JNIEnv *env, jobject fsEventsDiffAwareness) {
  JNIEventsDiffAwareness *info = GetInfo(env, fsEventsDiffAwareness);
  CFRunLoopStop(info->runLoop);
  FSEventStreamStop(info->stream);
  FSEventStreamUnscheduleFromRunLoop(info->stream, info->runLoop,
                                     kCFRunLoopDefaultMode);
  FSEventStreamInvalidate(info->stream);
  FSEventStreamRelease(info->stream);
  delete info;
}
