# G009 P0 fixed candidate-E2E baseline protocol

## Metric contract

`CandidateE2EReceipt schema=candidate-e2e-v1` is the machine-readable production
receipt. Its wall-clock boundary begins immediately before final physical
normalization and ends after final boundary verification, registry updates, and a
successful return from the planner receipt consumer. Failed invocations emit no
successful receipt. Trace output is neither required nor subtracted.

The fourteen named phases are exclusive and must sum exactly to `totalNanos`.
`model`, `costSurface`, `optimizer`, and `selection` are attributed only by the DP
and Exact planners; `exactPhaseCalls=0` means those zero values are N/A and the
planner decision time is reported as `otherPlanning` instead. P0 does not claim a
precise `TlateCandidate`: optimizer, selection, conversion, and application remain
separate exclusive coarse phases until nested candidate-only work is instrumented.

## Reproducible artifact

```bash
mvn -DskipTests package
scripts/fedplanner/run_g009_p0_baseline.sh prepare
```

The generated directory contains the complete source/config snapshot and hashes,
the dirty-tree patch/status, the exact JAR and embedded-class hashes for the full
placement and Exact class trees plus DMLTranslator and Statistics (including nested
classes), and command
templates. The output path must not already exist or be a symlink, and embedded JAR
classes must match `target/classes`. Host execution is diagnostic only.

## Docker qualification

`docker-smoke` validates the receipt parser but never marks a result official.
`qualify-glm` is the only official path. It accepts only `G009_STAGE_DESCRIPTOR` and
`G009_RUN_TOKEN`, derives the stage-owned `run_g009_glm_p0.sh`, validator, and
allowlist, supplies no arbitrary harness arguments, and rejects a dirty local
worktree. Runner stdout must contain
exactly one `COMMAND_RECEIPT`, `RUN_STDOUT`, and `PLANNING_RECEIPT` path. The latter's
`coordinator_log` path and hash must match the actual log. Timing is parsed only from
that coordinator log.
Afterward `validate-stage-run` must produce a valid `g009-glm-p0-proof-v1` whose
canonical payload digest verifies. Before official promotion, its SystemDS commit
and tree must equal the clean local HEAD/write-tree, its executed JAR hash must equal
the packaged local JAR, and all descriptor, runner, validator, allowlist, command,
planning receipt, and coordinator-log paths and hashes are independently matched.
Those proof sections and artifact digests are retained in the manifest. A
self-authored identity file is archived only
and never grants official status. Both paths reject
non-`run_LAN_docker.sh` launchers, missing/duplicate/negative timing receipts,
phase-sum mismatch, non-positive totals, non-zero launcher status, source changes,
and fatal/OOM/DML/ERROR/TIMEOUT output anywhere in the log. Official GLM additionally
requires exactly one invocation and one Exact-phase-attributed invocation; smoke
results never set the official flag.

The frozen Docker harnesses available on 2026-09-19 bind older source/JARs and
reject `glm`; they cannot qualify this revision. No GLM Docker result is claimed by
P0 until a reviewed immutable stage extends that allowlist and supplies the required
identity manifest.
