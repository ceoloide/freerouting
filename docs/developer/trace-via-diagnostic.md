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

## Targeted insertion checkpoints

The outer `after_insertion` checkpoint includes recursive shoving, trace combination,
normalization, endpoint attachment, and the pull-tight performed inside forced insertion.
It precedes the pipeline's separate `optChangedArea()` call. To distinguish these operations,
enable detailed observation of a known pass and connection:

```sh
java -Dfreerouting.diagnostic.trace_via_clearance=true \
  -Dfreerouting.diagnostic.trace_via_clearance.pass=1 \
  -Dfreerouting.diagnostic.trace_via_clearance.connection=784 \
  -Dfreerouting.diagnostic.trace_via_clearance.watch_via_id=12513 \
  -jar build/libs/freerouting-current-executable.jar \
  -de original.dsn -do diagnostic.ses \
  --router.automatic_neckdown=false \
  --router.fanout.enabled=false \
  --router.optimizer.enabled=false \
  --router.autorouter.max_passes=1
```

Use the connection index and via ID from the original diagnostic on the same input and settings.
The connection property defaults to zero, leaving detailed observation off; the pass defaults
to one. A zero or omitted `watch_via_id` includes all foreign-net trace/via pairs in that
connection. Otherwise, only pairs involving that via are reported, regardless of trace ID or
net. Normalization can replace trace objects.

The detailed observer runs only on the calling routing thread, for the selected connection.
It brackets rip-up, forced trace shoving, raw insertion, combination, normalization,
keep-point splitting, insertion-internal pull-tight, planned vias/traces, endpoint attachment,
stub cleanup, and final connection normalization. A checkpoint following a failed operation
can report a partial mutation too. It does not change the operation's return value.

Look for `event=detail_begin`, followed by `pair_appeared`, `pair_changed`, or `pair_resolved`
events and a final `event=detail_end`. `checkpoint` is the observation sequence within the
connection. A resolved event describes the pair at its previous observation. New pair lines
also report whether each item existed before the connection and the via's displacement from
its initial center in millimeters. If `via_existed_before_connection=false`, displacement is
unknown and the printed zero is a placeholder. Names and absolute coordinates remain omitted.

These detailed scans deliberately continue through the end of the selected connection even
after the outer diagnostic reports its first pair. An insertion can temporarily violate a
clearance and then repair it. Report the appearance/resolution sequence and the pairs remaining
at `detail_end`, alongside the outer `first_new_pair` and final SES DRC result. A
`detail_disabled` event indicates an observation failure. Full DRC at these boundaries adds
overhead; the same timing and final-result caveats apply.
