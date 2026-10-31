package app.freerouting.autoroute.pipeline;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Trace;
import app.freerouting.board.model.items.Via;
import app.freerouting.board.model.structure.Unit;
import app.freerouting.drc.ClearanceViolation;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.logger.FRLogger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Read-only, opt-in checkpoints for the first routing-generated foreign-net trace/via violation.
 */
final class TraceViaClearanceDiagnostic {

  static final String PROPERTY = "freerouting.diagnostic.trace_via_clearance";
  private final Consumer<String> log;
  private final Set<Pair> initialPairs = new HashSet<>();
  private boolean enabled;
  private boolean initialized;
  private boolean reported;
  private int pass;
  private int connection;
  private int totalConnection;
  private int activeNet;

  TraceViaClearanceDiagnostic() {
    this(Boolean.getBoolean(PROPERTY), FRLogger::info);
  }

  TraceViaClearanceDiagnostic(boolean enabled, Consumer<String> log) {
    this.enabled = enabled;
    this.log = log;
  }

  void beginPass(RoutingBoard board, int pass) {
    this.pass = pass;
    this.connection = 0;
    this.activeNet = 0;
    checkpoint(board, "pass_start");
  }

  void beginConnection(RoutingBoard board, int connection, int totalConnection, int activeNet) {
    this.connection = connection;
    this.totalConnection = totalConnection;
    this.activeNet = activeNet;
    checkpoint(board, "before_connection");
  }

  void cleanup(RoutingBoard board, String stage) {
    this.activeNet = 0;
    checkpoint(board, stage);
  }

  void checkpoint(RoutingBoard board, String stage) {
    if (!enabled || reported) {
      return;
    }
    try {
      List<ClearanceViolation> pairs = traceViaViolations(board);
      if (!initialized) {
        for (ClearanceViolation violation : pairs) {
          initialPairs.add(Pair.of(violation));
        }
        initialized = true;
        log.accept(
            prefix(stage)
                + " event=baseline initial_trace_via_pairs="
                + initialPairs.size()
                + " scans=full_drc net_names=omitted");
        return;
      }
      for (ClearanceViolation violation : pairs) {
        if (!initialPairs.contains(Pair.of(violation))) {
          reported = true;
          Trace trace =
              (Trace)
                  (violation.firstItem instanceof Trace
                      ? violation.firstItem
                      : violation.secondItem);
          Via via =
              (Via)
                  (violation.firstItem instanceof Via ? violation.firstItem : violation.secondItem);
          log.accept(
              prefix(stage)
                  + String.format(
                      Locale.ROOT,
                      " event=first_new_pair layer=%d trace_id=%d trace_nets=%s trace_class=%d"
                          + " via_id=%d via_nets=%s via_class=%d expected_mm=%.6f actual_mm=%.6f"
                          + " trace_width_mm=%.6f scans_stopped=true",
                      violation.layer,
                      trace.getId(),
                      nets(trace),
                      trace.clearanceClassIndex(),
                      via.getId(),
                      nets(via),
                      via.clearanceClassIndex(),
                      mm(board, violation.expectedClearance),
                      mm(board, violation.actualClearance),
                      mm(board, 2.0 * trace.getHalfWidth())));
          return;
        }
      }
      if ("after_pass_cleanup".equals(stage)) {
        log.accept(prefix(stage) + " event=no_new_pair");
      }
    } catch (RuntimeException exception) {
      // A diagnostic failure must neither reject a connection nor roll back the board.
      enabled = false;
      log.accept(
          prefix(stage)
              + " event=diagnostic_disabled error="
              + exception.getClass().getSimpleName());
    }
  }

  private String prefix(String stage) {
    return "[trace_via_diagnostic] stage="
        + stage
        + " pass="
        + pass
        + " connection="
        + connection
        + " total_connection="
        + totalConnection
        + " active_net="
        + activeNet;
  }

  private static List<ClearanceViolation> traceViaViolations(RoutingBoard board) {
    Collection<Item> items = board.getItems();
    List<Item> savedItems = new ArrayList<>(items);
    double[] savedClearances = new double[savedItems.size()];
    for (int i = 0; i < savedItems.size(); i++) {
      savedClearances[i] = savedItems.get(i).smallestClearance;
    }
    try {
      List<ClearanceViolation> result = new ArrayList<>();
      for (ClearanceViolation violation :
          new DesignRulesChecker(board, null).getAllClearanceViolations()) {
        boolean traceVia =
            (violation.firstItem instanceof Trace && violation.secondItem instanceof Via)
                || (violation.firstItem instanceof Via && violation.secondItem instanceof Trace);
        if (traceVia
            && !violation.holeToHole
            && !violation.firstItem.sharesNet(violation.secondItem)) {
          result.add(violation);
        }
      }
      result.sort(
          Comparator.comparingInt((ClearanceViolation v) -> Pair.of(v).first())
              .thenComparingInt(v -> Pair.of(v).second())
              .thenComparingInt(v -> v.layer));
      return result;
    } finally {
      // Comprehensive DRC updates this presentation cache; keep checkpoints observational.
      for (int i = 0; i < savedItems.size(); i++) {
        savedItems.get(i).smallestClearance = savedClearances[i];
      }
    }
  }

  private static String nets(Item item) {
    List<Integer> numbers = new ArrayList<>();
    for (int i = 0; i < item.netCount(); i++) {
      numbers.add(item.getNetNumber(i));
    }
    return numbers.toString().replace(" ", "");
  }

  private static double mm(RoutingBoard board, double value) {
    return Unit.scale(
        board.communication.coordinateTransform.boardToDsn(value),
        board.communication.unit,
        Unit.MM);
  }

  private record Pair(int first, int second, int layer) {
    static Pair of(ClearanceViolation violation) {
      return new Pair(
          Math.min(violation.firstItem.getId(), violation.secondItem.getId()),
          Math.max(violation.firstItem.getId(), violation.secondItem.getId()),
          violation.layer);
    }
  }
}
