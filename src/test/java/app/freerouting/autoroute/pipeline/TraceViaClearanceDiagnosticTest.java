package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Via;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.debug.RoutingMutationDiagnostic;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.IntVector;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.Polyline;
import app.freerouting.io.BoardReadResult;
import app.freerouting.io.specctra.DsnReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TraceViaClearanceDiagnosticTest {

  private RoutingBoard board;
  private final List<String> lines = new ArrayList<>();

  @BeforeEach
  void loadBoard() {
    String dsn =
        """
        (pcb diagnostic (resolution um 10)
          (structure (layer top (type signal)) (layer bottom (type signal))
            (boundary (rect pcb 0 0 20000 10000))
            (via V) (rule (width 250) (clearance 250)))
          (placement)
          (library (padstack V (shape (circle top 600))
            (shape (circle bottom 600)) (attach off)))
          (network (net PRIVATE_GND) (net PRIVATE_OTHER) (net PRIVATE_ACTIVE)))
        """;
    BoardReadResult result =
        DsnReader.readBoard(
            new ByteArrayInputStream(dsn.getBytes(StandardCharsets.UTF_8)), null, null);
    assertInstanceOf(BoardReadResult.Success.class, result);
    board = (RoutingBoard) ((BoardReadResult.Success) result).board();
  }

  private IntPoint at(double x, double y) {
    return board.communication.coordinateTransform.dsnToBoard(new double[] {x, y}).round();
  }

  private int net(String name) {
    return board.rules.nets.get(name, 1).netNumber;
  }

  private Via via(int net, double x) {
    return board.insertVia(
        board.library.getViaPadstack("V"),
        at(x, 5000),
        new int[] {net},
        1,
        FixedState.UNFIXED,
        false);
  }

  private PolylineTrace trace() {
    return (PolylineTrace)
        board.insertTraceWithoutCleaning(
            new Polyline(new Point[] {at(5000, 4000), at(5000, 6000)}),
            0,
            (int) board.communication.coordinateTransform.dsnToBoard(125),
            new int[] {net("PRIVATE_GND")},
            1,
            FixedState.UNFIXED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"after_insertion", "after_local_pull_tight", "after_pass_cleanup"})
  void identifiesTheFirstNewPairAtEachBoundary(String stage) {
    TraceViaClearanceDiagnostic diagnostic = new TraceViaClearanceDiagnostic(true, lines::add);
    diagnostic.beginPass(board, 1);
    diagnostic.beginConnection(board, 7, 7, net("PRIVATE_ACTIVE"));
    trace();
    via(net("PRIVATE_OTHER"), 5577.8);

    if ("after_pass_cleanup".equals(stage)) {
      diagnostic.cleanup(board, stage);
    } else {
      diagnostic.checkpoint(board, stage);
    }
    diagnostic.checkpoint(board, "later_checkpoint");

    assertEquals(2, lines.size());
    String event = lines.get(1);
    assertTrue(event.contains("stage=" + stage));
    int activeNet = "after_pass_cleanup".equals(stage) ? 0 : 3;
    assertTrue(event.contains("pass=1 connection=7 total_connection=7 active_net=" + activeNet));
    assertTrue(event.contains("trace_nets=[1]"));
    assertTrue(event.contains("via_nets=[2]"));
    assertTrue(event.contains("expected_mm=0.250000 actual_mm=0.152800"), event);
    assertTrue(event.contains("trace_width_mm=0.250000"));
    assertTrue(lines.stream().noneMatch(line -> line.contains("PRIVATE_")));
  }

  @Test
  void excludesInitialPairsAndSameNetPairs() {
    trace();
    via(net("PRIVATE_OTHER"), 5577.8);
    TraceViaClearanceDiagnostic diagnostic = new TraceViaClearanceDiagnostic(true, lines::add);
    diagnostic.beginPass(board, 1);
    via(net("PRIVATE_GND"), 4500);
    diagnostic.cleanup(board, "after_pass_cleanup");
    diagnostic.beginPass(board, 2);
    diagnostic.cleanup(board, "after_pass_cleanup");

    assertTrue(lines.getFirst().contains("initial_trace_via_pairs=1"));
    assertTrue(lines.stream().noneMatch(line -> line.contains("event=first_new_pair")));
    assertTrue(lines.getLast().contains("event=no_new_pair"));
  }

  @Test
  void detectsMovementOfExistingCopperOnNetsOtherThanTheActiveNet() {
    PolylineTrace trace = trace();
    Via via = via(net("PRIVATE_OTHER"), 6500);
    trace.smallestClearance = 123;
    via.smallestClearance = 456;
    TraceViaClearanceDiagnostic diagnostic = new TraceViaClearanceDiagnostic(true, lines::add);
    diagnostic.beginPass(board, 1);
    diagnostic.beginConnection(board, 2, 2, net("PRIVATE_ACTIVE"));
    int dx = at(5577.8, 5000).x - via.getCenter().toIntegerExact().x;
    via.moveBy(new IntVector(dx, 0));

    diagnostic.checkpoint(board, "after_insertion");

    assertTrue(lines.getLast().contains("event=first_new_pair"));
    assertEquals(123, trace.smallestClearance);
    assertEquals(456, via.smallestClearance);
  }

  @Test
  void disabledDiagnosticDoesNotReadTheBoardOrLog() {
    TraceViaClearanceDiagnostic diagnostic = new TraceViaClearanceDiagnostic(false, lines::add);
    diagnostic.beginPass(null, 1);
    diagnostic.beginConnection(null, 1, 1, 1);
    diagnostic.cleanup(null, "after_pass_cleanup");
    assertTrue(lines.isEmpty());
  }

  @Test
  void detailedScopeTracksTransientPairsAndViaMovementThroughTheConnection() {
    PolylineTrace initialTrace = trace();
    Via existingVia = via(net("PRIVATE_OTHER"), 6500);
    TraceViaClearanceDiagnostic diagnostic =
        new TraceViaClearanceDiagnostic(true, lines::add, 1, 7, existingVia.getId());
    diagnostic.beginPass(board, 1);
    diagnostic.beginConnection(board, 7, 7, net("PRIVATE_ACTIVE"));
    try (var scope = diagnostic.observeInsertion(board)) {
      existingVia.moveBy(
          new IntVector(at(5577.8, 5000).x - existingVia.getCenter().toIntegerExact().x, 0));
      RoutingMutationDiagnostic.checkpoint("after_raw_trace_insert");
      diagnostic.checkpoint(board, "after_insertion");
      board.removeItem(initialTrace);
      RoutingMutationDiagnostic.checkpoint("after_trace_normalize");
      trace();
      RoutingMutationDiagnostic.checkpoint("after_insertion_pull_tight");
    }
    int finishedCount = lines.size();
    RoutingMutationDiagnostic.checkpoint("outside_scope");
    assertEquals(finishedCount, lines.size());
    assertTrue(
        lines.stream()
            .anyMatch(
                line ->
                    line.contains("stage=after_raw_trace_insert")
                        && line.contains("event=pair_appeared")
                        && line.contains("via_moved_mm=0.922200")
                        && line.contains("trace_existed_before_connection=true")));
    assertTrue(
        lines.stream()
            .anyMatch(
                line ->
                    line.contains("stage=after_trace_normalize")
                        && line.contains("event=pair_resolved")));
    assertTrue(
        lines.stream()
            .anyMatch(
                line ->
                    line.contains("stage=after_insertion_pull_tight")
                        && line.contains("event=pair_appeared")
                        && line.contains("trace_existed_before_connection=false")));
    assertTrue(lines.getLast().contains("event=detail_end"));
    assertTrue(lines.getLast().contains("pairs_remaining=1"));
  }

  @Test
  void detailedScopeOnlyArmsForTheSelectedPassAndConnection() {
    TraceViaClearanceDiagnostic diagnostic =
        new TraceViaClearanceDiagnostic(true, lines::add, 1, 7, 0);
    diagnostic.beginPass(board, 1);
    diagnostic.beginConnection(board, 6, 6, 1);
    try (var scope = diagnostic.observeInsertion(board)) {
      RoutingMutationDiagnostic.checkpoint("wrong_connection");
    }
    diagnostic.beginPass(board, 2);
    diagnostic.beginConnection(board, 7, 7, 1);
    try (var scope = diagnostic.observeInsertion(board)) {
      RoutingMutationDiagnostic.checkpoint("wrong_pass");
    }
    assertTrue(lines.stream().noneMatch(line -> line.contains("event=detail_begin")));
  }

  @Test
  void forcedInsertionPublishesNormalizationAndInternalPullTightBoundariesInOrder() {
    List<String> stages = new ArrayList<>();
    try (var scope = RoutingMutationDiagnostic.observe(stages::add)) {
      Point end = at(8000, 4000);
      Point inserted =
          board.insertForcedTracePolyline(
              new Polyline(new Point[] {at(5000, 4000), end}),
              (int) board.communication.coordinateTransform.dsnToBoard(125),
              0,
              new int[] {net("PRIVATE_GND")},
              1,
              10,
              10,
              10,
              Integer.MAX_VALUE,
              500,
              true,
              null);
      assertEquals(end, inserted);
    }
    assertTrue(stages.indexOf("after_raw_trace_insert") >= 0);
    assertTrue(stages.indexOf("after_trace_combine") > stages.indexOf("after_raw_trace_insert"));
    assertTrue(stages.indexOf("after_trace_normalize") > stages.indexOf("after_trace_combine"));
    assertTrue(
        stages.indexOf("after_insertion_pull_tight") > stages.indexOf("after_trace_normalize"));
  }
}
