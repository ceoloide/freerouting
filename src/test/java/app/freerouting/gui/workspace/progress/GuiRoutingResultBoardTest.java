package app.freerouting.gui.workspace.progress;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.freerouting.Freerouting;
import app.freerouting.autoroute.pipeline.BatchAutorouter;
import app.freerouting.autoroute.pipeline.RoutingPipeline;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.structure.Unit;
import app.freerouting.core.BoardFileDetails;
import app.freerouting.core.RoutingJob;
import app.freerouting.gui.workspace.ports.WorkspacePort;
import app.freerouting.gui.workspace.session.RunGeneration;
import app.freerouting.io.FileFormat;
import app.freerouting.io.specctra.DsnTestFixtures;
import app.freerouting.management.jobs.ThreadActionListener;
import app.freerouting.settings.GlobalSettings;
import app.freerouting.settings.sources.DefaultSettings;
import java.util.Locale;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class GuiRoutingResultBoardTest {

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void publishesSelectedBoardBeforeGenerationCheckedCompletion(boolean cancelled) throws Exception {
    Freerouting.globalSettings = new GlobalSettings();
    RoutingBoard incumbent = DsnTestFixtures.loadBoard("scoring-perfect-two-pin.dsn");
    final RoutingBoard selected = incumbent.deepCopy();
    RoutingJob job = new RoutingJob();
    job.routerSettings = new DefaultSettings().getSettings();
    job.routerSettings.optimizer.enabled = false;
    job.routerSettings.maxThreads = 1;
    job.output = new BoardFileDetails();
    job.output.format = FileFormat.SES;
    WorkspacePort port = mock(WorkspacePort.class);
    when(port.routingBoard()).thenReturn(incumbent);
    when(port.locale()).thenReturn(Locale.ENGLISH);
    when(port.displayUnit()).thenReturn(Unit.MM);
    RoutingPipeline pipeline = mock(RoutingPipeline.class);
    when(pipeline.getAutorouter()).thenReturn(mock(BatchAutorouter.class));
    doAnswer(
            invocation -> {
              job.board = selected;
              if (cancelled) {
                job.thread.requestStop();
              }
              return null;
            })
        .when(pipeline)
        .run();

    ThreadActionListener listener = mock(ThreadActionListener.class);
    ThreadActionListener generalListener = mock(ThreadActionListener.class);
    try (var factory = mockStatic(RoutingPipeline.class)) {
      factory.when(() -> RoutingPipeline.createForGui(job)).thenReturn(pipeline);
      GuiRoutingJobWorker worker = new GuiRoutingJobWorker(port, new RunGeneration(1), job);
      worker.addSessionListener(listener);
      worker.addListener(generalListener);
      worker.run();
    }

    verify(listener, never()).autorouterFinished();
    verify(listener, never()).autorouterAborted();
    if (cancelled) {
      verify(generalListener).autorouterAborted();
    } else {
      verify(generalListener).autorouterFinished();
    }
    var ordered = inOrder(port, generalListener);
    ordered.verify(port).replaceBoard(argThat(event -> event.board() == selected));
    if (cancelled) {
      ordered.verify(generalListener).autorouterAborted();
    } else {
      ordered.verify(generalListener).autorouterFinished();
    }
    ordered
        .verify(port)
        .finishRoute(argThat(event -> event.generation().equals(new RunGeneration(1))));
    var completed = ArgumentCaptor.forClass(RouteCompletion.class);
    verify(port).finishRoute(completed.capture());
    completed.getValue().completionAction().run();
    if (cancelled) {
      verify(listener).autorouterAborted();
    } else {
      verify(listener).autorouterFinished();
    }
  }
}
