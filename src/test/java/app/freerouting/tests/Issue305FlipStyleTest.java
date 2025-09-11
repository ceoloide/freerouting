package app.freerouting.tests;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.BoardObservers;
import app.freerouting.core.RoutingJob;
import app.freerouting.datastructures.IdentificationNumberGenerator;
import app.freerouting.designforms.specctra.DsnFile;
import app.freerouting.interactive.HeadlessBoardManager;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Issue305FlipStyleTest {
  /**
   * Tests reading of the (place_control (flip_style rotate_first)) scope in dsn-files.
   */
  @Test
  public void testFlipStyle() throws Exception {
    // Find the test file
    Path testDirectory = Path.of(".").toAbsolutePath();
    File testFile = null;
    while (testDirectory != null) {
      testFile = Path.of(testDirectory.toString(), "tests", "Issue305-FlipStyle.dsn").toFile();
      if (testFile.exists()) {
        break;
      }
      testDirectory = testDirectory.getParent();
    }

    assertNotNull(testFile, "Test file 'Issue305-FlipStyle.dsn' not found.");
    assertTrue(testFile.exists(), "Test file 'Issue305-FlipStyle.dsn' does not exist.");

    // Prepare to read the DSN file
    RoutingJob routingJob = new RoutingJob();
    HeadlessBoardManager boardManager = new HeadlessBoardManager(Locale.getDefault(), routingJob);
    // BoardObservers is abstract, but for this test, we don't need an observer.
    BoardObservers boardObservers = null;
    // IdentificationNumberGenerator is an interface.
    IdentificationNumberGenerator idNoGenerator = new IdentificationNumberGenerator() {
        private int nextId = 1;
        @Override
        public int new_no() {
            return nextId++;
        }
        @Override
        public int max_generated_no() {
            return nextId - 1;
        }
    };

    InputStream inputStream = new FileInputStream(testFile);

    // Read the DSN file using the board manager's helper method
    DsnFile.ReadResult readResult = boardManager.loadFromSpecctraDsn(inputStream, boardObservers, idNoGenerator);

    // Get the board
    BasicBoard board = boardManager.get_routing_board();

    // Assert that the board was created and the flip style was set correctly
    assertNotNull(board, "Board should not be null after parsing.");
    assertTrue(board.components.get_flip_style_rotate_first(), "Flip style should be rotate_first");
  }
}
