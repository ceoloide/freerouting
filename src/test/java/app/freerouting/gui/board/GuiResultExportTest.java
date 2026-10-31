package app.freerouting.gui.board;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.geometry.planar.Polyline;
import app.freerouting.gui.workspace.GuiBoardManager;
import app.freerouting.io.specctra.DsnTestFixtures;
import app.freerouting.io.specctra.SesWriter;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuiResultExportTest {

  @TempDir Path temporaryDirectory;

  @Test
  void automaticExportWaitsForEarlierBoardReplacementOnEdt() throws Exception {
    RoutingBoard incumbent = DsnTestFixtures.loadBoard("scoring-perfect-two-pin.dsn");
    RoutingBoard selected = incumbent.deepCopy();
    var pins = new ArrayList<Pin>(selected.getPins());
    int net = pins.getFirst().getNetNumber(0);
    var rules = selected.rules.nets.get(net).getNetClass();
    selected.insertTraceWithoutCleaning(
        new Polyline(pins.getFirst().getCenter(), pins.getLast().getCenter()),
        0,
        rules.getTraceHalfWidth(0),
        new int[] {net},
        rules.getTraceClearanceClass(),
        FixedState.UNFIXED);
    AtomicReference<RoutingBoard> sessionBoard = new AtomicReference<>(incumbent);
    AtomicBoolean savedOnEdt = new AtomicBoolean();
    GuiBoardManager manager = mock(GuiBoardManager.class);
    doAnswer(
            invocation -> {
              savedOnEdt.set(SwingUtilities.isEventDispatchThread());
              SesWriter.write(
                  sessionBoard.get(),
                  (OutputStream) invocation.getArgument(0),
                  invocation.getArgument(1));
              return true;
            })
        .when(manager)
        .saveHeadlessSpecctraSessionSes(any(), anyString());
    BoardFrame frame = mock(BoardFrame.class);
    frame.boardPanel = mock(BoardPanel.class);
    frame.boardPanel.boardHandling = manager;
    Path output = temporaryDirectory.resolve("result.ses");
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    SwingUtilities.invokeLater(
        () -> {
          entered.countDown();
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
        });
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      SwingUtilities.invokeLater(() -> sessionBoard.set(selected));
      GuiManager.exportBoardToFile(frame, output.toString(), false);
    } finally {
      release.countDown();
      SwingUtilities.invokeAndWait(() -> {});
    }

    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    SesWriter.write(selected, expected, "result");
    assertArrayEquals(expected.toByteArray(), Files.readAllBytes(output));
    assertTrue(savedOnEdt.get());
  }
}
