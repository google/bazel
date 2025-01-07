// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildeventservice;

import com.google.common.base.MoreObjects;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.buildeventstream.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.PathConverter;
import com.google.devtools.build.v1.PublishBuildToolEventStreamRequest;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import javax.annotation.concurrent.Immutable;

/** Commands that can be used in the event loop implemented by {@link BuildEventServiceUploader}. */
public class BuildEventServiceUploaderCommands {
  /** A command that may be added to the {@code BuildEventServiceUploader#eventQueue}. */
  interface EventLoopCommand {
    enum Type {
      /** Tells the event loop to open a new BES stream */
      OPEN_STREAM,
      /** Tells the event loop to send the build event */
      SEND_REGULAR_BUILD_EVENT,
      /** Tells the event loop that an ACK was received */
      ACK_RECEIVED,
      /** Tells the event loop that this is the last event of the stream */
      SEND_LAST_BUILD_EVENT,
      /** Tells the event loop to send an ack retransmit request */
      SEND_REQUEST_BUILD_EVENT,
      /** Tells the event loop that the streaming RPC completed */
      STREAM_COMPLETE
    }

    Type type();
  }

  /** Implementation of {@link EventLoopCommand.Type#OPEN_STREAM}. */
  @Immutable
  static final class OpenStreamCommand implements EventLoopCommand {
    @Override
    public Type type() {
      return Type.OPEN_STREAM;
    }
  }

  /** Implementation of {@link EventLoopCommand.Type#STREAM_COMPLETE}. */
  @Immutable
  static final class StreamCompleteCommand implements EventLoopCommand {
    private final Status status;

    StreamCompleteCommand(Status status) {
      this.status = status;
    }

    public Status status() {
      return status;
    }

    @Override
    public Type type() {
      return Type.STREAM_COMPLETE;
    }
  }

  /** Implementation of {@link EventLoopCommand.Type#ACK_RECEIVED}. */
  @Immutable
  static final class AckReceivedCommand implements EventLoopCommand {
    private final long sequenceNumber;

    AckReceivedCommand(long sequenceNumber) {
      this.sequenceNumber = sequenceNumber;
    }

    public long getSequenceNumber() {
      return sequenceNumber;
    }

    @Override
    public Type type() {
      return Type.ACK_RECEIVED;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("seq_num", getSequenceNumber()).toString();
    }
  }

  /** Common interface for {@link EventLoopCommand}s related to builds. */
  abstract static class SendBuildEventCommand implements EventLoopCommand {
    abstract long getSequenceNumber();

    abstract Timestamp getCreationTime();

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("seq_num", getSequenceNumber()).toString();
    }
  }

  /** Implementation of {@link EventLoopCommand.Type#SEND_REQUEST_BUILD_EVENT}. */
  static final class SendRequestBuildEventCommand extends SendBuildEventCommand {
    private final PublishBuildToolEventStreamRequest request;

    SendRequestBuildEventCommand(PublishBuildToolEventStreamRequest request) {
      this.request = request;
    }

    PublishBuildToolEventStreamRequest getRequest() {
      return request;
    }

    @Override
    public long getSequenceNumber() {
      return request.getOrderedBuildEvent().getSequenceNumber();
    }

    @Override
    Timestamp getCreationTime() {
      return request.getOrderedBuildEvent().getEvent().getEventTime();
    }

    @Override
    public Type type() {
      return Type.SEND_REQUEST_BUILD_EVENT;
    }
  }

  /** Implementation of {@link EventLoopCommand.Type#SEND_REGULAR_BUILD_EVENT}. */
  static final class SendRegularBuildEventCommand extends SendBuildEventCommand {
    private final BuildEvent event;
    private final ListenableFuture<PathConverter> localFileUpload;
    private final long sequenceNumber;
    private final Timestamp creationTime;

    SendRegularBuildEventCommand(
        BuildEvent event,
        ListenableFuture<PathConverter> localFileUpload,
        long sequenceNumber,
        Timestamp creationTime) {
      this.event = event;
      this.localFileUpload = localFileUpload;
      this.sequenceNumber = sequenceNumber;
      this.creationTime = creationTime;
    }

    BuildEvent getEvent() {
      return event;
    }

    ListenableFuture<PathConverter> localFileUploadProgress() {
      return localFileUpload;
    }

    @Override
    public long getSequenceNumber() {
      return sequenceNumber;
    }

    @Override
    Timestamp getCreationTime() {
      return creationTime;
    }

    @Override
    public Type type() {
      return Type.SEND_REGULAR_BUILD_EVENT;
    }

    @Override
    public String toString() {
      return super.toString() + " - [" + event + "]";
    }
  }

  /** Implementation of {@link EventLoopCommand.Type#SEND_LAST_BUILD_EVENT}. */
  @Immutable
  static final class SendLastBuildEventCommand extends SendBuildEventCommand {
    private final long sequenceNumber;
    private final Timestamp creationTime;

    SendLastBuildEventCommand(long sequenceNumber, Timestamp creationTime) {
      this.sequenceNumber = sequenceNumber;
      this.creationTime = creationTime;
    }

    @Override
    Timestamp getCreationTime() {
      return creationTime;
    }

    @Override
    public Type type() {
      return Type.SEND_LAST_BUILD_EVENT;
    }

    @Override
    public long getSequenceNumber() {
      return sequenceNumber;
    }
  }

}
