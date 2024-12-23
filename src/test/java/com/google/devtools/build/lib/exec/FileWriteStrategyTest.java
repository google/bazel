// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil.NullAction;
import com.google.devtools.build.lib.actions.util.DummyExecutor;
import com.google.devtools.build.lib.analysis.actions.DeterministicWriter;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.server.FailureDetails.Execution.Code;
import com.google.devtools.build.lib.testing.vfs.SpiedFileSystem;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.IOException;
import java.io.OutputStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link FileWriteStrategy}. */
@RunWith(TestParameterInjector.class)
public final class FileWriteStrategyTest {
  private static final IOException INJECTED_EXCEPTION = new IOException("oh no!");

  private final FileWriteStrategy fileWriteStrategy = new FileWriteStrategy();

  private final SpiedFileSystem fileSystem = SpiedFileSystem.createInMemorySpy();
  private final Scratch scratch = new Scratch(fileSystem);
  private Path execRoot;
  private ArtifactRoot outputRoot;

  @Before
  public void createOutputRoot() throws IOException {
    execRoot = scratch.dir("/execroot");
    outputRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "bazel-out");
    outputRoot.getRoot().asPath().createDirectory();
  }

  @Test
  public void writeOutputToFile_writesCorrectOutput(
      @TestParameter({"", "hello", "hello there"}) String content) throws Exception {
    AbstractAction action = createAction("file");

    var unused =
        fileWriteStrategy.writeOutputToFile(
            action,
            createActionExecutionContext(),
            out -> out.write(content.getBytes(UTF_8)),
            /* makeExecutable= */ false,
            /* isRemotable= */ false);

    assertThat(FileSystemUtils.readContentToString(action.getPrimaryOutput().getPath()))
        .isEqualTo(content);
  }

  private enum FailureMode implements DeterministicWriter {
    OPEN_FAILURE {
      @Override
      void setupFileSystem(SpiedFileSystem fileSystem, PathFragment outputPath) throws IOException {
        when(fileSystem.getOutputStream(outputPath, /* append= */ false, /* internal= */ false))
            .thenThrow(INJECTED_EXCEPTION);
      }
    },
    WRITE_FAILURE {
      @Override
      public void writeOutputFile(OutputStream out) throws IOException {
        throw INJECTED_EXCEPTION;
      }
    },
    CLOSE_FAILURE {
      @Override
      void setupFileSystem(SpiedFileSystem fileSystem, PathFragment outputPath) throws IOException {
        OutputStream outputStream = mock(OutputStream.class);
        doThrow(INJECTED_EXCEPTION).when(outputStream).close();
        when(fileSystem.getOutputStream(outputPath, /* append= */ false, /* internal= */ false))
            .thenReturn(outputStream);
      }
    };

    void setupFileSystem(SpiedFileSystem fileSystem, PathFragment outputPath) throws IOException {}

    @Override
    public void writeOutputFile(OutputStream out) throws IOException {}
  }

  @Test
  public void writeOutputToFile_errorInWriter_returnsFailure(@TestParameter FailureMode failureMode)
      throws Exception {
    AbstractAction action = createAction("file");
    failureMode.setupFileSystem(fileSystem, action.getPrimaryOutput().getPath().asFragment());

    ExecException e =
        assertThrows(
            EnvironmentalExecException.class,
            () -> {
              fileWriteStrategy.writeOutputToFile(
                  action,
                  createActionExecutionContext(),
                  failureMode,
                  /* makeExecutable= */ false,
                  /* isRemotable= */ false);
            });

    assertThat(e).hasCauseThat().isSameInstanceAs(INJECTED_EXCEPTION);
    var detailExitCode = getDetailExitCode(e);
    assertThat(detailExitCode.getExitCode()).isEqualTo(ExitCode.LOCAL_ENVIRONMENTAL_ERROR);
    assertThat(detailExitCode.getFailureDetail().getExecution().getCode())
        .isEqualTo(Code.FILE_WRITE_IO_EXCEPTION);
  }

  private DetailedExitCode getDetailExitCode(ExecException e) {
    return ActionExecutionException.fromExecException(e, new ActionsTestUtil.NullAction())
        .getDetailedExitCode();
  }

  private ActionExecutionContext createActionExecutionContext() {
    return ActionsTestUtil.createContext(
        new DummyExecutor(fileSystem, execRoot), NullEventHandler.INSTANCE);
  }

  private AbstractAction createAction(String outputRelativePath) {
    return new NullAction(
        ActionsTestUtil.createArtifactWithRootRelativePath(
            outputRoot, PathFragment.create(outputRelativePath)));
  }
}
