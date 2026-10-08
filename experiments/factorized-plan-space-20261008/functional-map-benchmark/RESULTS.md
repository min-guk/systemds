# Recorded result

Recorded with `DOMAIN=4000 WARMUPS=5 REPEATS=9` on 2026-10-08. Both engines loaded the benchmark class whose digest is stored in `results/benchmark-class.sha256`.

| Metric | OLD | NEW |
| --- | ---: | ---: |
| Logical relation rows | 16,000,000 | 16,000,000 |
| Legal rows | 3 | 3 |
| Planner-reported boundary retained rows | 16,000,000 | 3 |
| Planner-reported solver materialized rows | 16,004,001 | 4,004 |
| Planner-reported maximum factor rows | 16,000,000 | 4,000 |
| Median measured wall time | 1.621 ms | 1.783 ms |
| Median current-thread allocation | 130,048 bytes | 130,672 bytes |
| Peak process RSS | 53,336 KiB | 51,732 KiB |
| Objective raw bits | 0 | 0 |
| Assignment | `(7,11)` | `(7,11)` |

The row counts are planner telemetry, not physical-memory measurements. OLD already stores this relation with a specialized functional marker despite reporting the logical Cartesian row count; its low allocation and RSS demonstrate that it does not allocate a 16-million-row table. NEW corrects that telemetry while retaining the specialized execution path: wall time, current-thread allocation, and RSS remain close to OLD in this run. This is synthetic and does not represent end-to-end FedPlanner compile time.
