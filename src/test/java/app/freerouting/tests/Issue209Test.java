package app.freerouting.tests;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class Issue209Test extends TestBasedOnAnIssue {
  @Test
  void testIssue209_split05() {
    var job = GetRoutingJob("Issue209-split05.dsn");
    job = RunRoutingJob(job, job.routerSettings);
    var statsAfter = GetBoardStatistics(job);
    assertEquals(0, statsAfter.connections.incompleteCount, "The incomplete count should be 0");
  }

  @Test
  void testIssue209_split10() {
    var job = GetRoutingJob("Issue209-split10.dsn");
    job = RunRoutingJob(job, job.routerSettings);
    var statsAfter = GetBoardStatistics(job);
    assertEquals(0, statsAfter.connections.incompleteCount, "The incomplete count should be 0");
  }
}
