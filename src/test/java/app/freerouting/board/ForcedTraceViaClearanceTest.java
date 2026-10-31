package app.freerouting.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.Freerouting;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.Polyline;
import app.freerouting.io.BoardReadResult;
import app.freerouting.io.specctra.DsnReader;
import app.freerouting.settings.GlobalSettings;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ForcedTraceViaClearanceTest {

  private static RoutingBoard board(double rightLeg) {
    Freerouting.globalSettings = new GlobalSettings();
    String dsn =
        """
        (pcb pending-via-shove (resolution um 10)
          (structure (layer top (type signal)) (layer bottom (type signal))
            (boundary (rect pcb 0 0 20000 10000))
            (via V) (rule (width 250) (clearance 250)))
          (placement (component P (place A 5000 7000 front 0)
            (place B %s 7000 front 0)))
          (library (image P (pin S 1 0 0)) (padstack S (shape (circle top 100)))
            (padstack V (shape (circle top 600)) (shape (circle bottom 600)) (attach off)))
          (network (net N (pins A-1 B-1)) (net M)))
        """
            .formatted(Double.toString(rightLeg));
    BoardReadResult result =
        DsnReader.readBoard(
            new ByteArrayInputStream(dsn.getBytes(StandardCharsets.UTF_8)), null, null);
    assertInstanceOf(BoardReadResult.Success.class, result);
    return (RoutingBoard) ((BoardReadResult.Success) result).board();
  }

  private static IntPoint at(RoutingBoard board, double x, double y) {
    return board.communication.coordinateTransform.dsnToBoard(new double[] {x, y}).round();
  }

  private static Point insert(RoutingBoard board, double rightLeg, boolean tidy) {
    return insert(board, rightLeg, tidy, 1);
  }

  private static Point insert(
      RoutingBoard board, double rightLeg, boolean tidy, int clearanceClass) {
    Polyline line =
        new Polyline(
            new Point[] {
              at(board, 5000, 3000), at(board, 5000, 7000),
              at(board, rightLeg, 7000), at(board, rightLeg, 3000)
            });
    return board.insertForcedTracePolyline(
        line,
        (int) board.communication.coordinateTransform.dsnToBoard(125),
        0,
        new int[] {1},
        clearanceClass,
        10,
        10,
        10,
        tidy ? Integer.MAX_VALUE : 0,
        500,
        true,
        null);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsViaReentryIntoEarlierPendingLeg(boolean tidy) {
    // The narrow U cannot fit the via between both pending legs at the required clearance.
    // Before the guard, the last leg moved the via 343.5 um and left a 152.8 um gap to the first.
    RoutingBoard board = board(6254.6);
    board.insertVia(
        board.library.getViaPadstack("V"),
        at(board, 5921.3, 5000),
        new int[] {2},
        1,
        FixedState.UNFIXED,
        false);
    DesignRulesChecker checker = new DesignRulesChecker(board, null);
    assertTrue(checker.getAllClearanceViolations().isEmpty());

    Point reached = insert(board, 6254.6, tidy);

    assertNull(
        reached, "The pending U must not be committed after the via re-enters its first leg");
    assertTrue(checker.getAllClearanceViolations().isEmpty());
    assertTrue(board.getTraces().isEmpty(), "No unsafe pending copper may be inserted");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void acceptsWhenTheShovedViaStillClearsEveryPendingLeg(boolean tidy) {
    RoutingBoard board = board(6600);
    var via =
        board.insertVia(
            board.library.getViaPadstack("V"),
            at(board, 6400, 5000),
            new int[] {2},
            1,
            FixedState.UNFIXED,
            false);
    Point before = via.getCenter();
    assertTrue(new DesignRulesChecker(board, null).getAllClearanceViolations().isEmpty());

    Point reached = insert(board, 6600, tidy);

    assertEquals(at(board, 6600, 3000), reached);
    assertTrue(before.toFloat().distance(via.getCenter().toFloat()) > 0);
    assertTrue(new DesignRulesChecker(board, null).getAllClearanceViolations().isEmpty());
  }

  private static void nonAdditiveClasses(RoutingBoard board) {
    var matrix = board.rules.clearanceMatrix;
    matrix.appendClass("trace");
    matrix.appendClass("via");
    int small = (int) board.communication.coordinateTransform.dsnToBoard(100);
    for (int index : new int[] {2, 3}) {
      matrix.setValue(index, 1, small);
      matrix.setValue(1, index, small);
    }
    board.searchTreeManager.setClearanceCompensationUsed(true);
  }

  @Test
  void rejectsViaReentryWithNonAdditiveClassClearances() {
    RoutingBoard board = board(6254.6);
    nonAdditiveClasses(board);
    board.insertVia(
        board.library.getViaPadstack("V"),
        at(board, 5921.3, 5000),
        new int[] {2},
        3,
        FixedState.UNFIXED,
        false);
    assertNull(insert(board, 6254.6, true, 2));
    assertTrue(new DesignRulesChecker(board, null).getAllClearanceViolations().isEmpty());
  }

  @Test
  void acceptsLegalShoveWithNonAdditiveClassClearances() {
    RoutingBoard board = board(6600);
    nonAdditiveClasses(board);
    board.insertVia(
        board.library.getViaPadstack("V"),
        at(board, 6400, 5000),
        new int[] {2},
        3,
        FixedState.UNFIXED,
        false);
    assertEquals(at(board, 6600, 3000), insert(board, 6600, true, 2));
    assertTrue(new DesignRulesChecker(board, null).getAllClearanceViolations().isEmpty());
  }

  @Test
  void permitsSameNetViaContact() {
    RoutingBoard board = board(6254.6);
    board.insertVia(
        board.library.getViaPadstack("V"),
        at(board, 5921.3, 5000),
        new int[] {1},
        1,
        FixedState.UNFIXED,
        false);
    assertEquals(at(board, 6254.6, 3000), insert(board, 6254.6, true));
    assertTrue(new DesignRulesChecker(board, null).getAllClearanceViolations().isEmpty());
  }
}
