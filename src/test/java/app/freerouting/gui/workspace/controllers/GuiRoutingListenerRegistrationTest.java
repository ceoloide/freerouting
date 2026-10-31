package app.freerouting.gui.workspace.controllers;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.core.RoutingJob;
import app.freerouting.gui.workspace.GuiBoardManager;
import app.freerouting.gui.workspace.ports.WorkspacePort;
import app.freerouting.gui.workspace.progress.RouterSettingsSnapshot;
import app.freerouting.gui.workspace.session.InteractiveActionThread;
import app.freerouting.gui.workspace.session.RunGeneration;
import app.freerouting.management.jobs.ThreadActionListener;
import app.freerouting.settings.sources.DefaultSettings;
import org.junit.jupiter.api.Test;

class GuiRoutingListenerRegistrationTest {

  @Test
  void automaticExportListenerIsRegisteredBeforeTheWorkerStarts() {
    GuiBoardManager manager = mock(GuiBoardManager.class);
    when(manager.getRoutingBoard()).thenReturn(mock(RoutingBoard.class));
    WorkspacePort port = mock(WorkspacePort.class);
    when(manager.getSessionPort()).thenReturn(port);
    when(port.settingsSnapshot())
        .thenReturn(new RouterSettingsSnapshot(new DefaultSettings().getSettings()));
    RoutingJob job = new RoutingJob();
    RunGeneration generation = new RunGeneration(1);
    when(port.beginRoute(job)).thenReturn(generation);
    manager.autorouterListener = mock(ThreadActionListener.class);
    InteractiveActionThread worker = mock(InteractiveActionThread.class);
    try (var factory = mockStatic(InteractiveActionThread.class)) {
      factory
          .when(
              () ->
                  InteractiveActionThread.getAutorouterAndRouteOptimizerInstance(
                      port, generation, job))
          .thenReturn(worker);
      new GuiBoardRoutingActions(manager).start(job);
    }
    var ordered = inOrder(worker);
    ordered.verify(worker).addSessionListener(manager.autorouterListener);
    ordered.verify(worker).start();
  }
}
