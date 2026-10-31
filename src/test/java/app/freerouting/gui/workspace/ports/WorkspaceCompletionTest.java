package app.freerouting.gui.workspace.ports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.core.RoutingJob;
import app.freerouting.gui.workspace.GuiBoardManager;
import app.freerouting.gui.workspace.progress.RouteCompletion;
import app.freerouting.gui.workspace.progress.ScreenMessages;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceCompletionTest {

  @TempDir Path directory;

  private static GuiBoardManager manager() throws Exception {
    GuiBoardManager manager = mock(GuiBoardManager.class);
    // A mock does not run the constructor that supplies this presentation collaborator.
    var field = GuiBoardManager.class.getField("screenMessages");
    field.setAccessible(true);
    field.set(manager, mock(ScreenMessages.class));
    return manager;
  }

  private static RoutingJob job() {
    RoutingJob job = new RoutingJob();
    job.routerSettings.maxThreads = 1;
    return job;
  }

  private static void save(Path file, String value) {
    try {
      Files.writeString(file, value);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @Test
  void newLoadSuppressesTheOldRunsQueuedExport() throws Exception {
    List<Runnable> queued = new ArrayList<>();
    GuiBoardManager manager = manager();
    WorkspacePortAdapter port = new WorkspacePortAdapter(() -> manager, () -> null, queued::add);
    Path output = directory.resolve("old-output.ses");
    save(output, "keep-existing-output");
    var generation = port.beginRoute(job());
    port.finishRoute(
        new RouteCompletion(
            generation, false, null, 0, true, false, () -> save(output, "wrong-new-board")));

    port.beginBoardLoad();
    queued.forEach(Runnable::run);

    assertEquals("keep-existing-output", Files.readString(output));
  }

  @Test
  void newerRunSuppressesTheOldRunsQueuedExport() throws Exception {
    List<Runnable> queued = new ArrayList<>();
    GuiBoardManager manager = manager();
    WorkspacePortAdapter port = new WorkspacePortAdapter(() -> manager, () -> null, queued::add);
    Path output = directory.resolve("old-output.ses");
    save(output, "keep-existing-output");
    var generation = port.beginRoute(job());
    port.finishRoute(
        new RouteCompletion(
            generation, true, null, 0, true, false, () -> save(output, "stale-partial-result")));

    port.beginRoute(job());
    queued.forEach(Runnable::run);

    assertEquals("keep-existing-output", Files.readString(output));
  }

  @Test
  void currentCompletionExportsAfterBoardAdoptionAndOnlyOnce() throws Exception {
    List<Runnable> queued = new ArrayList<>();
    GuiBoardManager manager = manager();
    RoutingBoard selected = mock(RoutingBoard.class);
    AtomicReference<RoutingBoard> current = new AtomicReference<>();
    when(manager.getRoutingBoard()).thenAnswer(invocation -> current.get());
    doAnswer(
            invocation -> {
              current.set(invocation.getArgument(0));
              return null;
            })
        .when(manager)
        .replaceRoutingBoard(any());
    WorkspacePortAdapter port = new WorkspacePortAdapter(() -> manager, () -> null, queued::add);
    Path output = directory.resolve("selected.ses");
    var generation = port.beginRoute(job());
    port.replaceBoard(new BoardReplacement(generation, selected));
    List<RoutingBoard> exported = new ArrayList<>();
    RouteCompletion completion =
        new RouteCompletion(
            generation,
            false,
            null,
            0,
            true,
            false,
            () -> {
              exported.add(port.routingBoard());
              save(output, "selected-result");
            });
    port.finishRoute(completion);
    port.finishRoute(completion);

    queued.forEach(Runnable::run);

    assertEquals(1, exported.size());
    assertSame(selected, exported.getFirst());
    assertEquals("selected-result", Files.readString(output));
  }
}
