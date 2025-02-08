// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime;

import static com.google.common.base.StandardSystemProperty.JAVA_IO_TMPDIR;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.runtime.InstrumentationOutputFactory.DestinationRelativeTo;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class InstrumentationOutputFactoryTest extends BuildIntegrationTestCase {
  @Test
  public void testInstrumentationOutputFactory_cannotCreateFactoryIfLocalSupplierUnset() {
    InstrumentationOutputFactory.Builder factoryBuilder =
        new InstrumentationOutputFactory.Builder();
    factoryBuilder.setBuildEventArtifactInstrumentationOutputBuilderSupplier(
        BuildEventArtifactInstrumentationOutput.Builder::new);

    assertThrows(
        "Cannot create InstrumentationOutputFactory without localOutputBuilderSupplier",
        NullPointerException.class,
        factoryBuilder::build);
  }

  @Test
  public void testInstrumentationOutputFactory_cannotCreateFactorIfBepSupplierUnset() {
    InstrumentationOutputFactory.Builder factoryBuilder =
        new InstrumentationOutputFactory.Builder();
    factoryBuilder.setLocalInstrumentationOutputBuilderSupplier(
        LocalInstrumentationOutput.Builder::new);

    assertThrows(
        "Cannot create InstrumentationOutputFactory without bepOutputBuilderSupplier",
        NullPointerException.class,
        factoryBuilder::build);
  }

  private static InstrumentationOutputFactory createInstrumentationOutputFactory(
      boolean setLocalTempLoggingDir) {
    InstrumentationOutputFactory.Builder factoryBuilder =
        new InstrumentationOutputFactory.Builder();
    factoryBuilder.setLocalInstrumentationOutputBuilderSupplier(
        LocalInstrumentationOutput.Builder::new);
    factoryBuilder.setBuildEventArtifactInstrumentationOutputBuilderSupplier(
        BuildEventArtifactInstrumentationOutput.Builder::new);
    if (setLocalTempLoggingDir) {
      factoryBuilder.setLocalTempLoggingDirPathStr("/tmp");
    }
    return factoryBuilder.build();
  }

  @Test
  public void testInstrumentationOutputFactory_successfullyCreateLocalOutputWithConvenientLink()
      throws Exception {
    InstrumentationOutputFactory outputFactory =
        createInstrumentationOutputFactory(/* setLocalTempLoggingDir= */ false);

    CommandEnvironment env = runtimeWrapper.newCommand();
    InstrumentationOutput output =
        outputFactory.createLocalOutputWithConvenientName(
            /* name= */ "output",
            env.getWorkspace().getRelative("output-file"),
            /* convenienceName= */ "link-to-output");
    assertThat(output).isInstanceOf(LocalInstrumentationOutput.class);

    ((LocalInstrumentationOutput) output).makeConvenienceLink();
    assertThat(env.getWorkspace().getRelative("link-to-output").isSymbolicLink()).isTrue();
  }

  @Test
  public void testInstrumentationOutputFactory_localRelativeToOutputBase() throws Exception {
    InstrumentationOutputFactory outputFactory =
        createInstrumentationOutputFactory(/* setLocalTempLoggingDir= */ false);

    CommandEnvironment env = runtimeWrapper.newCommand();
    InstrumentationOutput output =
        outputFactory.createInstrumentationOutput(
            /* name= */ "output-baseoutput",
            PathFragment.create("output-baseoutput"),
            DestinationRelativeTo.OUTPUT_BASE,
            env,
            mock(EventHandler.class),
            /* append= */ null,
            /* internal= */ null);

    assertThat(output).isInstanceOf(LocalInstrumentationOutput.class);
    assertThat(output.getPathString())
        .isEqualTo(env.getOutputBase().getRelative("output-baseoutput").getPathString());
  }

  @Test
  public void testInstrumentationOutputFactory_localAbsolutePath() throws Exception {
    InstrumentationOutputFactory outputFactory =
        createInstrumentationOutputFactory(/* setLocalTempLoggingDir= */ false);

    CommandEnvironment env = runtimeWrapper.newCommand();
    InstrumentationOutput output =
        outputFactory.createInstrumentationOutput(
            /* name= */ "output-absolute",
            PathFragment.create("/tmp/absolute-path-output"),
            DestinationRelativeTo.WORKSPACE_OR_HOME,
            env,
            mock(EventHandler.class),
            /* append= */ null,
            /* internal= */ null);

    assertThat(output).isInstanceOf(LocalInstrumentationOutput.class);
    assertThat(output.getPathString())
        .isEqualTo(
            env.getRuntime().getFileSystem().getPath("/tmp/absolute-path-output").getPathString());
  }

  @Test
  public void testInstrumentationOutputFactory_localRelativePath(
      @TestParameter({"WORKSPACE_OR_HOME", "WORKING_DIRECTORY_OR_HOME"})
          DestinationRelativeTo relativeTo)
      throws Exception {
    InstrumentationOutputFactory outputFactory =
        createInstrumentationOutputFactory(/* setLocalTempLoggingDir= */ false);

    CommandEnvironment env = runtimeWrapper.newCommand();
    InstrumentationOutput output =
        outputFactory.createInstrumentationOutput(
            /* name= */ "output-relative",
            PathFragment.create("relative-output"),
            relativeTo,
            env,
            mock(EventHandler.class),
            /* append= */ null,
            /* internal= */ null);

    assertThat(output).isInstanceOf(LocalInstrumentationOutput.class);
    assertThat(output.getPathString())
        .isEqualTo(
            (relativeTo.equals(DestinationRelativeTo.WORKSPACE_OR_HOME)
                    ? env.getWorkspace()
                    : env.getWorkingDirectory())
                .getRelative("relative-output")
                .getPathString());
  }

  @Test
  public void testInstrumentationOutputFactory_localRelativeToTempLogging(
      @TestParameter boolean setLocalTempLoggingDir) throws Exception {
    InstrumentationOutputFactory outputFactory =
        createInstrumentationOutputFactory(setLocalTempLoggingDir);

    CommandEnvironment env = runtimeWrapper.newCommand();
    InstrumentationOutput output =
        outputFactory.createInstrumentationOutput(
            /* name= */ "output-relative",
            PathFragment.create("relative-output"),
            DestinationRelativeTo.TEMP_LOGGING_DIRECTORY,
            env,
            mock(EventHandler.class),
            /* append= */ null,
            /* internal= */ null);

    assertThat(output).isInstanceOf(LocalInstrumentationOutput.class);
    String expectedOutputBaseDir = setLocalTempLoggingDir ? "/tmp" : JAVA_IO_TMPDIR.value();
    assertThat(output.getPathString()).isEqualTo(expectedOutputBaseDir + "/relative-output");
  }

  @Test
  public void testInstrumentationOutputFactory_successfulFactoryCreation(
      @TestParameter boolean injectRedirectOutputBuilderSupplier,
      @TestParameter boolean createRedirectOutput)
      throws Exception {
    if (createRedirectOutput) {
      runtimeWrapper.addOptions("--redirect_local_instrumentation_output_writes");
    }
    CommandEnvironment env = runtimeWrapper.newCommand();

    InstrumentationOutputFactory.Builder factoryBuilder =
        new InstrumentationOutputFactory.Builder();
    factoryBuilder.setLocalInstrumentationOutputBuilderSupplier(
        LocalInstrumentationOutput.Builder::new);
    factoryBuilder.setBuildEventArtifactInstrumentationOutputBuilderSupplier(
        BuildEventArtifactInstrumentationOutput.Builder::new);

    InstrumentationOutput fakeRedirectInstrumentationOutput = mock(InstrumentationOutput.class);
    if (injectRedirectOutputBuilderSupplier) {
      InstrumentationOutputBuilder fakeRedirectInstrumentationBuilder =
          new InstrumentationOutputBuilder() {
            @Override
            @CanIgnoreReturnValue
            public InstrumentationOutputBuilder setName(String name) {
              return this;
            }

            @Override
            @CanIgnoreReturnValue
            public InstrumentationOutputBuilder setCreateParent(boolean createParent) {
              return this;
            }

            @Override
            public InstrumentationOutput build() {
              return fakeRedirectInstrumentationOutput;
            }
          };

      factoryBuilder.setRedirectInstrumentationOutputBuilderSupplier(
          () -> fakeRedirectInstrumentationBuilder);
    }

    List<Event> warningEvents = new ArrayList<>();
    ExtendedEventHandler eventHandler =
        new ExtendedEventHandler() {
          @Override
          public void post(Postable obj) {}

          @Override
          public void handle(Event event) {
            warningEvents.add(event);
          }
        };

    InstrumentationOutputFactory outputFactory = factoryBuilder.build();
    var instrumentationOutput =
        outputFactory.createInstrumentationOutput(
            /* name= */ "local",
            /* destination= */ PathFragment.create("/file"),
            DestinationRelativeTo.WORKSPACE_OR_HOME,
            env,
            eventHandler,
            /* append= */ null,
            /* internal= */ null);

    // Only when redirectOutputBuilderSupplier is provided to the factory, and we intend to create a
    // RedirectOutputBuilder object, we expect a non-LocalInstrumentationOutput to be created. In
    // all other scenarios, a LocalInstrumentationOutput is returned.
    if (createRedirectOutput && injectRedirectOutputBuilderSupplier) {
      assertThat(instrumentationOutput).isEqualTo(fakeRedirectInstrumentationOutput);
    } else {
      assertThat(instrumentationOutput).isInstanceOf(LocalInstrumentationOutput.class);
    }

    // When user wants to create a redirectOutputBuilder object but its builder supplier is not
    // provided, eventHandler should post a warning event.
    if (createRedirectOutput && !injectRedirectOutputBuilderSupplier) {
      assertThat(warningEvents)
          .containsExactly(
              Event.of(
                  EventKind.WARNING,
                  "Redirecting to write Instrumentation Output on a different machine is not"
                      + " supported. Defaulting to writing output locally."));
    } else {
      assertThat(warningEvents).isEmpty();
    }
    assertThat(
            outputFactory.createBuildEventArtifactInstrumentationOutput(
                /* name= */ "bep", mock(BuildEventArtifactUploader.class)))
        .isNotNull();
  }
}
