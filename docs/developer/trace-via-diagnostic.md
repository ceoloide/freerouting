# Trace/via clearance diagnostic

This diagnostic identifies the first autorouter checkpoint containing a new foreign-net
trace/via clearance violation. It is disabled by default. It observes the existing routing
operations; it does not reject connections, change settings, repair copper, or stop routing.

Build the executable JAR with `./gradlew executableJar`. Enable the diagnostic with a JVM
property **before** `-jar`, retaining the original input and routing settings:

```sh
java -Dfreerouting.diagnostic.trace_via_clearance=true \
  -jar build/libs/freerouting-current-executable.jar \
  -de original.dsn -do diagnostic.ses \
  --router.automatic_neckdown=false \
  --router.fanout.enabled=false \
  --router.optimizer.enabled=false \
  --router.autorouter.max_passes=1
```

The property does not require detailed TRACE logging. Lines containing
`[trace_via_diagnostic]` appear at INFO level. The initial `event=baseline` line confirms
activation and counts pre-existing foreign-net trace/via pairs. An `event=first_new_pair`
line gives:

- The first checkpoint observing the new pair, pass number, per-pass connection index, and
  total connection index. Connections count attempted item/net combinations, including skips
  and failures, rather than successful routes.
- The active net number and each pair item's net numbers, IDs, clearance-class indices, and
  zero-based layer index. A shove can affect copper on nets other than the active net.
- Expected and measured clearance and trace width in millimeters.

The diagnostic omits net names and coordinates. Review any other log lines before sharing,
since ordinary routing logs can contain design names. `active_net=0` identifies a pass boundary
or cleanup checkpoint, where there is no active connection.

Checkpoints include `after_insertion`, `after_local_pull_tight`, necked-retry equivalents,
`after_connection_bookkeeping`, and `before_pass_cleanup`/`after_pass_cleanup`. Insertion
includes the engine's internal shoving and normalization. The diagnostic checks unsuccessful
insertion attempts too. A finding identifies a checkpoint interval, not the individual
geometric operation responsible. It reports one deterministic pair if several appear at the
same checkpoint and stops scanning after that first finding; routing continues normally.
`event=no_new_pair` confirms a pass completed without observing a new pair. An
`event=diagnostic_disabled` line indicates a diagnostic error, not a clean result.

Each checkpoint runs `DesignRulesChecker.getAllClearanceViolations()` across the whole board,
then filters foreign-net trace/via pairs. Initial pair identity uses sorted item IDs and layer;
splitting or replacing an initially violating item can therefore appear as a new pair. The
diagnostic restores the per-item `smallestClearance` cache after each scan.

Full DRC after each operation can substantially slow routing. Use the same settings as the
reproduction and allow enough job time for the scans. A timeout can stop the diagnostic run
before the offending connection, so compare the final result and stopping log with the
original run. The diagnostic does not change timeout-dependent routing policies or disable
existing limits. Local pull-tight retains its existing wall-clock limit too, so extra machine
load can affect how much work completes within that limit. Confirm that the reported pair
matches the original failure. Validate the final SES with the original rules:

```sh
java -jar build/libs/freerouting-current-executable.jar \
  -de original.dsn diagnostic.ses -drc diagnostic-drc.json
```

Share the baseline and first-pair diagnostic lines, the stopping log, revision, and exact
command. Preserve numeric relationships between the active and pair nets when anonymizing.
