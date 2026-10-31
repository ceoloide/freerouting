package app.freerouting.debug;

import app.freerouting.logger.FRLogger;
import java.util.function.Consumer;

/** Thread-scoped observation points; routing policy never depends on a diagnostic callback. */
public final class RoutingMutationDiagnostic {

  private static final ThreadLocal<Consumer<String>> OBSERVER = new ThreadLocal<>();

  private RoutingMutationDiagnostic() {}

  /** Installs an observer on the calling routing thread until the returned scope is closed. */
  public static Scope observe(Consumer<String> observer) {
    Consumer<String> previous = OBSERVER.get();
    OBSERVER.set(observer);
    return () -> {
      if (previous == null) {
        OBSERVER.remove();
      } else {
        OBSERVER.set(previous);
      }
    };
  }

  /** Reports a completed mutation boundary when this thread has an active observer. */
  public static void checkpoint(String stage) {
    Consumer<String> observer = OBSERVER.get();
    if (observer == null) {
      return;
    }
    try {
      observer.accept(stage);
    } catch (RuntimeException exception) {
      OBSERVER.remove();
      FRLogger.warn(
          "Routing mutation diagnostic disabled: " + exception.getClass().getSimpleName());
    }
  }

  /** A scope which does not introduce checked exceptions into routing operations. */
  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
