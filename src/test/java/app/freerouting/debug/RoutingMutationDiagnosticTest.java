package app.freerouting.debug;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutingMutationDiagnosticTest {

  @Test
  void nestedScopesRestoreThePreviousObserverAndDoNotLeakToAnotherThread() throws Exception {
    List<String> outer = new ArrayList<>();
    List<String> inner = new ArrayList<>();
    try (var first = RoutingMutationDiagnostic.observe(outer::add)) {
      RoutingMutationDiagnostic.checkpoint("outer");
      try (var second = RoutingMutationDiagnostic.observe(inner::add)) {
        RoutingMutationDiagnostic.checkpoint("inner");
        Thread other = new Thread(() -> RoutingMutationDiagnostic.checkpoint("other_thread"));
        other.start();
        other.join();
      }
      RoutingMutationDiagnostic.checkpoint("restored");
    }
    RoutingMutationDiagnostic.checkpoint("closed");
    assertEquals(List.of("outer", "restored"), outer);
    assertEquals(List.of("inner"), inner);
  }

  @Test
  void observerFailureDoesNotRejectOrInterruptRouting() {
    try (var scope =
        RoutingMutationDiagnostic.observe(
            stage -> {
              throw new IllegalStateException();
            })) {
      assertDoesNotThrow(() -> RoutingMutationDiagnostic.checkpoint("failure"));
      assertDoesNotThrow(() -> RoutingMutationDiagnostic.checkpoint("disabled"));
    }
  }
}
