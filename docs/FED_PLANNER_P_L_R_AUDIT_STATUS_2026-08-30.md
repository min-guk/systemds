# FED Planner Candidate/Legal/Runtime Space Audit — Progress Report

**Date:** 2026-08-30  
**Local worktree:** `/home/mchoi/g014-fed-runtime-space-audit-0d769014-20260830`  
**Branch:** `g014/fed-runtime-space-audit-20260830`  
**Audited base commit:** `0d769014d18ffb6a915b186c9bc05596710a3e24`  
**Primary immutable audit artifact:** `audit-results/so006-full-isolated-20260830T194247Z`  
**Latest checkpoint:** 2026-08-31 21:01 CEST
**Latest Korean operational report:** `docs/FED_PLANNER_P_L_R_AUDIT_CHECKPOINT_2026-08-31_2101_KO.md`

## 1. Objective and decision rule

For a compiled occurrence `o`, ordered input-residency signature `i`, and physical placement state `s`, this audit distinguishes:

- `P(o,i)`: states exposed by the shared placement candidate builder to every selector;
- `L(o,i)`: states that remain legal after shared privacy closure and whole-program consistency constraints;
- `R(o,i)`: states that survive HOP-to-LOP lowering and successfully execute as concrete FED runtime instructions.

The target classifications are:

```text
Missing(o,i)  = (R(o,i) intersection L(o,i)) - P(o,i)
Spurious(o,i) = P(o,i) - (R(o,i) intersection L(o,i))
```

The implementation deliberately uses conservative evidence rules:

1. A physical instruction is admitted to `R` only after successful runtime execution.
2. A published state without a successful execution witness is `UNTESTED`, not automatically `Spurious`.
3. A failed execution is recorded separately until its root cause is proven to be physical infeasibility rather than bad test input, network failure, or an unrelated runtime defect.
4. `Missing` is confirmed only when the runtime instruction can be joined to the exact planner occurrence and exact ordered input signature.

This means the current audit can already prove individual defects, but a global “no Missing/Spurious states” claim is valid only after every published state is forced and successfully or unsuccessfully classified.

## 2. Implemented audit infrastructure

### 2.1 Candidate and privacy-domain capture (`P` and shared legality evidence)

Added:

- `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlannerCandidateSpaceAudit.java`
- hook in `NeutralPlacementGraphBuilder.buildAnalysis(...)`

For every `CandidateRuleKey`, the JSONL capture records:

- exact compiled occurrence identity and stable hash;
- ordered input presence/FType signature;
- HOP opcode/class and concrete/abstract shape facts;
- inferred privacy class;
- pre-privacy node states and candidate emissions;
- post-privacy exclusions and the exact `publishedStatesP` consumed by all selectors.

The hook is off by default and is enabled only by:

```text
-Dsysds.fedplanner.space.audit=true
```

### 2.2 Lowering identity and selected-input capture

Extended:

- `PlannerRuntimePlacementAudit.java`
- `Dag.java`
- `DMLTranslator.java`

The lowering/runtime audit now retains:

- exact occurrence hashes fused into an instruction;
- the selector-chosen ordered input signature per occurrence;
- HOP/Lop/instruction identity and recompile signature;
- actual runtime input residency reconstructed in serialized instruction operand order.

### 2.3 Positive runtime-capability capture (`R`)

Added:

- `PlannerRuntimeCapabilityAudit.java`
- execution hook in `ProgramBlock.executeSingleInstruction(...)`

Each FED instruction produces either:

- `SUCCESS`: recorded only after `processInstruction` and post-processing return; or
- `FAILURE`: recorded separately with exception class/message.

The recorder includes instruction class, FED subtype, opcode, requested output mode, runtime input layout, output residency/FType, and exact planner occurrence identity when available.

An instrumentation defect discovered during coverage expansion was fixed: `VariableFEDInstruction` legitimately has a null `FEDType` and sometimes a null federated-output field. The audit now serializes these as null instead of throwing an audit-only `NullPointerException`.

### 2.4 Differential comparator and remote runner

Added:

- `scripts/fedplanner/compare_candidate_runtime_space.py`
- `scripts/fedplanner/run_full_fed_space_audit.sh`
- `PlannerSpaceAuditTest.java`

The comparator emits:

- `missing.csv`;
- `failed_published_attempts.csv`;
- `selected_input_signature_divergence.csv`;
- `generalized_missing_hypotheses.csv`;
- `unwitnessed_published.csv`;
- `runtime_capability_matrix.csv`;
- machine-readable `summary.json` and a Markdown report.

### 2.5 Coherent forced-state constraint

Added:

- `ExactPhysicalForcedStateAudit.java`;
- a hard-factor hook in `ExactPhysicalOptimizer`;
- `ExactPhysicalForcedStateAuditTest.java`;
- `scripts/fedplanner/build_forced_state_manifest.py`.

The hook is disabled unless all four exact target properties are present:

```text
-Dsysds.fedplanner.space.audit.force.analysis=<analysis fingerprint>
-Dsysds.fedplanner.space.audit.force.occurrence=<occurrence hash>
-Dsysds.fedplanner.space.audit.force.input=<ordered input signature>
-Dsysds.fedplanner.space.audit.force.state=<EXEC/OUTPUT/FTYPE>
```

It does not mutate a HOP directly. It adds one unary hard factor to the same
Exact physical model used in production, permitting only captured alternatives
whose exact occurrence, ordered input signature, and physical state match the
target. All privacy exclusions, whole-program consistency factors, and the
canonical cost surface remain active, so a successful solve is a coherent
whole-program completion. A matched occurrence whose requested tuple is not in
the published domain fails with `FED_SPACE_AUDIT_FORCE_TARGET_NOT_EXPOSED`.
Three unit tests cover a feasible non-baseline force, rejection of an unexposed
tuple, and no-op behavior for other DML programs in the same test class.

The manifest builder collapses duplicate native/derived provenance to one
physical runtime target. The original full capture yielded 9,585 targets, but
that number is historical: it came from a snapshot without replay contexts and
cannot itself drive exhaustive execution.

A replay target now carries two identities:

- the production `CompiledHopKey`/analysis fingerprint, retained unchanged for
  planner correctness and diagnostics; and
- an audit-only stable replay occurrence hash, which preserves structural
  occurrence fields while normalizing dynamic loopback worker ports and omitting
  the discovery program fingerprint.

The separation is required because a JUnit replay allocates fresh worker ports.
Raw production fingerprints therefore change even when the same DML occurrence
is reconstructed. The hard factor matches the stable occurrence, exact ordered
input signature, and exact physical state; it still solves against the newly
constructed production analysis and does not weaken privacy or whole-program
constraints.

### 2.6 Replayable test context

Candidate rows include `auditContext`. The test harness propagates this context
into the `TestRunner_main` thread used by `AutomatedTestBase`; without that
propagation, component-time candidate rows were replayable but actual DML/runtime
rows had a null context. Stack inspection selects the annotated JUnit entry
method rather than a private helper, and a regression assertion verifies the
exact `Class#testMethod` value without adding JUnit to main-code dependencies.

The corrected n-ary discovery is the first end-to-end proof:

```text
/tmp/fed-nary-context3-1788125581
candidate rows:       55
manifest targets:     57
null replay contexts: 0
replayable targets:   57
```

A forced `m(min)` target with state `FED/FOUT/ROW` then replayed with fresh
worker ports and completed successfully:

```text
/tmp/fed-forced-smoke-stable-1788125598
outcome:              SUCCESS
constraintSatisfied:  true
JUnit run/failures:    1 / 0
```

The discovery and replay analysis fingerprints differ, as expected, while the
stable structural occurrence matches. This proves the forced-state mechanism
can cross the real DML -> HOP -> selector -> LOP -> FED instruction -> runtime
path; it does not yet prove all published states.

### 2.7 Test-outcome-qualified replay and truthful phase receipts

The first full-run shell used `maven.test.failure.ignore=true` so discovery
could continue past negative fixtures. Consequently, Maven return code zero
meant only that the phase infrastructure completed; it did **not** mean every
JUnit case passed. The old script also copied the entire persistent
`target/surefire-reports` directory after each phase, mixing stale, component,
and runtime reports. This made an unqualified `component_rc=0` or
`runtime_rc=0` unsafe as a correctness claim.

The runner has been repaired for subsequent snapshots. It now copies only the
selected classes whose XML was written after the phase-start marker and writes
separate Maven status, test counts, failures, errors, skipped cases, missing
reports, and a `PASS`/`FAIL`/`INCOMPLETE` test status. The manifest builder can
parse these isolated Surefire XML files and, under
`--require-passing-context`, emits force targets only from runtime JUnit methods
whose every parameterized instance passed. A synthetic regression verifies
that a passing method is retained while a failing method and a parameterized
method with one failing instance are both excluded.

This qualification is essential for sound `Spurious` classification: a
candidate forced inside an already failing workload must not be called
physically infeasible merely because the unrelated baseline assertion also
fails.

### 2.8 Mandatory-instrumentation shard runner

Added `scripts/fedplanner/run_forced_state_campaign.sh` so distributed forcing
cannot omit candidate, placement, or runtime-capability recording. Each shard
records its source/manifest identity, expected and observed target IDs,
duplicate or missing rows, satisfied constraints, result outcomes, capability
outcomes, target directories with capability evidence, Maven/summary status,
and relative-path SHA-256 checksums. Output reuse is rejected.

A one-target end-to-end smoke test completed with infrastructure `PASS`,
classification `ALL_SUCCESS`, one satisfied constraint, five successful
runtime-capability rows, and a passing checksum verification.

## 3. Remote audit status and immutable evidence

### 3.1 Completed full one-fork audit (`so006`)

The immutable replacement full run completed all phases:

```text
Remote: so006:/home/mchoi/fed-space-audit-0d769014-20260830/
          audit/full-full-isolated-20260830T194247Z/
Local:  /home/mchoi/g014-fed-runtime-space-audit-0d769014-20260830/
          audit-results/so006-full-isolated-20260830T194247Z/
```

`RUN_MANIFEST.txt` records the historical orchestration receipts
`component_rc=0`, `runtime_rc=0`, and `compare_rc=0`. Because that runner used
failure-ignore and non-isolated Surefire copies, the first two values are not
test-pass evidence. They certify that both discovery commands ran to completion
and that the comparator completed. The local copy is 210 MiB, and all nine
differential outputs pass `sha256sum -c DIFFERENTIAL_SHA256SUMS.txt`.

| Metric | Result |
|---|---:|
| Candidate rows | 11,008 |
| Unique published `P` states | 9,642 |
| Runtime witness rows | 8,048 |
| Successful runtime rows | 8,013 |
| Failed runtime rows | 35 |
| Successfully exercised concrete FED classes | 35 / 36 |
| Confirmed `Missing` with exact identity | 0 |
| Failed published attempts | 0 |
| Selected/runtime input-signature divergences | 0 |
| Witnessed published `P` states | 242 |
| Unwitnessed published `P` states | 9,400 |
| Generalized runtime witnesses not exposed | 644 |
| Runtime rows lacking an exact candidate join | 7,804 |

The 35 failure rows have no planner occurrence identity and originate from
negative/error-path fixtures (27 aggregate-unary, 4 covariance, 3 indexing,
1 append). They are not evidence of `Spurious` published states. The only class
not positively witnessed in this immutable snapshot is
`CumulativeOffsetFEDInstruction`; newer targeted evidence in Section 5 covers
it. Thus the run closes broad class coverage but remains far from state-space
coverage completeness.

### 3.2 Completed targeted replacement and privacy-none repair (`so003`)

The sequential targeted replacement is preserved locally at:

```text
Remote: so003:/home/mchoi/fed-space-audit-targeted-clean-20260830T2042Z/
          audit/targeted-latest-20260830T211430Z/
Local:  audit-results/so003-targeted-latest-20260830T211430Z/
```

Component, cumulative, multiply, n-ary, and PCA phases passed. The run contains
1,550 candidate rows and 208 runtime rows, all `SUCCESS`, across 17 instruction
classes. In particular, the previously failing multiplication suite now passes,
remotely confirming the stale-authority and global-Spark fixes.

The one failed phase was the privacy-none L2 case. Raw evidence showed a valid
worker metadata file without a `privacy` field returning a successful response
with a null payload. The coordinator correctly treats an unexplained null
payload as unresolved, so planning aborted. The defect was at the worker
boundary: SystemDS semantics treat an absent privacy constraint in valid
metadata as unrestricted/public, but the worker failed to encode that fact.

`FederatedData.GetPrivacyConstraints` now returns explicit `"public"` only after
successfully opening and parsing an existing metadata file whose privacy field
is absent. Missing files, malformed metadata, error responses, null responses,
and genuinely malformed null-payload responses remain failures. Validation:

```text
CampaignBG011PrivacyResolverOwnerContractTest: PASS (12 tests)
FederatedL2SVMPlanningTest:                      PASS
so003 isolated L2 rerun:                         rc=0, 64.04 s
so003 L2 audit rows:                             1,580 candidate, 95/95 runtime SUCCESS
Local artifact: audit-results/fed-space-l2-privacy-fix-20260830T214344Z/
```

### 3.3 Corrected context-bearing discovery (`so004`)

An earlier `so004` discovery was stopped because runtime DML execution occurred
on `TestRunner_main` without inherited audit context. Its rows are not used.
After adding explicit test-thread propagation and stable replay identity, the
corrected diagnostic run completed at:

```text
so004:/home/mchoi/fed-space-audit-context2-20260830T213409Z/
  audit/full-context2-20260830T213409Z/
Started: 2026-08-30 23:34 CEST
Finished: 2026-08-31 01:13:08 CEST
```

Its `RUN_MANIFEST.txt` records all 63 component classes and all 101 runtime
classes, `compare_rc=0`, and a final `finished` receipt. The launch wrapper did
not write a separate `driver.rc`; completion is established from the final
phase/comparator receipts rather than inferred from a missing wrapper file.

The historical Maven return codes are failure-ignored orchestration receipts,
not declarations that every JUnit test passed. Direct parsing of the
phase-isolated XML reports preserves failures and excludes their contexts from
the pass-qualified replay domain. These outcomes include synthetic privacy
fixtures that cannot answer a real worker privacy query under audit mode,
source-architecture guards that scan audit-only code or require unavailable Git
history in the copied remote tree, and mutation-sentinel failures that also
reproduce with all audit flags disabled. They are not silently treated as
passing replay contexts.

This snapshot predates the final explicit-public worker fix and exact CP/FOUT
repair. It is retained only as a diagnostic/context-discovery snapshot and is
not mixed into the current-source final candidate domain. The authoritative
forcing input comes from the current-source `so006` discovery in Section 3.5.

### 3.4 Historical proxy-host artifacts (`so001`; excluded)

`so001` is a proxy server and is prohibited for audit execution. This was
confirmed after the historical runs below had completed. Their files are retained
only as diagnostic development history; **none of their candidate, legality,
runtime, timing, or success counts is admitted to the final P/L/R aggregation**.
No new command, replay, source staging, or campaign is sent to `so001`.

The first complete replay of the corrected 57-target n-ary manifest finished at:

```text
Remote source: so001:/home/mchoi/fed-space-forced-nary-20260830T214326Z/
Remote output: campaign/
Local copy:    audit-results/so001-forced-nary-20260830T214326Z/
```

All 57 targets reached and satisfied their requested exact hard constraint.
The result distribution was 55 `SUCCESS` and 2
`FAILURE_REQUIRES_TRIAGE`; there were no target-not-exposed,
whole-program-infeasible, target-not-reached, runner, or classified runtime
failures. The local artifact is 16 MiB and includes SHA-256 receipts for 175
files.

The two failures were independently reproduced and classified:

1. `m(min) CP/LOUT/-` executed numerically correctly, but the workload test
   unconditionally required a `fed_nmin` heavy hitter. That assertion describes
   the normal FedAll baseline, not a deliberately forced alternative state. It
   is now retained for ordinary runs and skipped only while the exact-state
   audit hook is active.
2. `m(min) CP/FOUT/ROW` satisfied the selected state but failed during lowering
   because a CP Lop carrying the selected post-materialization FOUT marker was
   misclassified as an already-federated runtime value. Section 4.12 describes
   the fix.

A local two-target replay after both repairs completed with 2/2 `SUCCESS`, both
constraints satisfied, and zero JUnit failures. The CP/FOUT case executed a
real `fed_fout` instruction before the consumer. The unforced normal n-ary test
also still passes and still enforces the expected `fed_nmin` kernel.

The complete 57-target repaired campaign finished independently at:

```text
so001:/home/mchoi/fed-space-forced-nary-20260830T214326Z/
  campaign-fixed-20260831T000106/
Local copy:
  audit-results/so001-forced-nary-fixed-20260831T000106/
```

It recorded 57/57 `SUCCESS`, 57/57 satisfied hard constraints, and zero JUnit
failures. The selected-state distribution was 53 `CP/LOUT/-`, one
`CP/FOUT/ROW`, and three `FED/FOUT/ROW`. A relative-path SHA-256 manifest covers
all 62 copied files and passes `sha256sum -c`.

This first repaired rerun accidentally omitted the three audit-enable booleans.
It therefore proves coherent exact-state selection, lowering, numerical test
success, and the two defect repairs, but it did not emit per-target
runtime-capability JSONL and is not counted as the final formal `R` receipt.
The identical manifest was then replayed with candidate, placement, and
post-execution capability recording enabled at:

```text
so001:/home/mchoi/fed-space-forced-nary-20260830T214326Z/
  campaign-fixed-capability-20260830T220927Z/
PID: 2458646
Local copy:
  audit-results/so001-fixed-capability-20260830T220927Z/
```

This corrected replay also finished with `rc=0`, 57/57 `SUCCESS`, 57/57
satisfied constraints, and the same state distribution. It emitted 3,135
candidate rows and 284 post-execution capability rows, all `SUCCESS`, across
all 57 target directories. The exact forced-state event plus successful JUnit
workload proves that the requested state remained selected through lowering;
the capability rows additionally prove that the workload's concrete FED
instructions completed successfully. The copied artifact is 16 MiB. Its
relative-path SHA-256 manifest covers 177 primary files and passes locally.

The correction is kept in a new immutable output directory; the successful
57/57 non-capability artifact is not overwritten or retroactively reclassified.

### 3.5 Current-source discovery, harness repair, and proxy-free final forcing

The authoritative current-source discovery completed on `so006`:

```text
Source:   so006:/home/mchoi/fed-space-audit-current-20260830T222317Z/
Output:   audit/full-current-20260830T222317Z/
Started:  2026-08-31 00:23:22 CEST
Finished: 2026-08-31 06:14:25 CEST
Driver rc: 0
```

The truthful discovery receipts remain:

```text
component: 619 tests, 50 failures, 37 errors, 3 skipped, 63/63 reports
runtime:  1,139 tests, 152 failures, 22 errors, 357 skipped, 101/101 reports
compare_rc=0
manifest_rc=0
```

Maven rc zero means failure-ignored discovery completed; only JUnit methods whose
isolated Surefire reports passed are admitted to forcing. Before relaunching the
full campaign, three replay-harness defects were proven with isolated targets:

1. **Shared-JVM contamination.** A target classified `TARGET_NOT_REACHED` after
   hundreds of prior replays became `SUCCESS` in an isolated JVM. Static worker,
   port, optimizer, and test configuration can therefore leak across a long
   in-process target sequence.
2. **Unstable audit replay identity.** Statement-block construction ordinals
   changed the same source occurrence from `main/5/branch-if/0` during discovery
   to `main/2/branch-if/0` during replay. Production identity remains exact, but
   the audit-only replay hash now replaces only all-numeric control-path segments
   with `*`, while retaining namespace, semantic control tokens, recompile
   context, emitted-root identity, canonical source origin, and normalized
   loopback ports. Exact forcing still rejects a normalized hash that matches
   multiple decision domains.
3. **Parameterized-method ambiguity.** Discovery records a JUnit method, whereas
   the old runner replayed every parameter leaf together. The runner now
   enumerates leaves independently and stops only when a leaf records
   `CONSTRAINT_APPLIED`; failures from leaves that never saw the target are not
   attributed to that target.

The campaign runner now creates deterministic chunks and starts a fresh
Maven/Surefire JVM per chunk. The primary pass uses 10 targets/JVM; every
non-success classification will be replayed again with 1 target/JVM before it is
used as feasibility evidence. The forced solver also records an explicit
`WHOLE_PROGRAM_INFEASIBLE` event when a published local candidate cannot be
extended through the unchanged whole-program hard factors.

The discovery JSONL was not rerun. Its full serialized `CompiledHopKey` values
were decoded and rehashed by the repaired manifest builder. This produced the
current authoritative input:

```text
Manifest: /home/mchoi/fed-space-audit-current-20260830T222317Z/
            final-input/forced-state-manifest-normalized-v2.jsonl
Targets:  5,408
Rows recomputed from full occurrence identity: 14,024
Fallback rows: 0
Manifest SHA-256:
  f3f31f509290ed19d90671c2fb0b26e2b450f41e43f86862cf4b82c3fd2df7e1
Exact staged-source receipt SHA-256:
  cca27c6a51a0c6fac1a61e645a5de7a5ce1920309d2cc87234a1e8fdd655c233
```

The one-target decrease from the earlier 5,409-row manifest is a deterministic
merge of the same parameterized P2FFN literal occurrence after removing an
unstable numeric control ordinal. Provenance keeps both discovery replay hashes;
forcing still fails closed on any within-analysis decision-domain collision.

Representative repaired replays on `so006` established:

```text
shared-JVM contamination target: SUCCESS, constraint satisfied
normalized L2SVM TWrite target:   WHOLE_PROGRAM_INFEASIBLE (explicit solver event)
parameterized Var target:         exact leaf selected and constraint satisfied;
                                  workload assertion still requires fed_uavar,
                                  so retained as FAILURE_REQUIRES_TRIAGE
```

The last result is no longer sibling pollution: the forced CP alternative runs,
but the workload contains a planner-specific heavy-hitter assertion that is
expected to reject that deliberate alternative. It is not silently promoted to
runtime success or physical infeasibility.

#### Host policy and retired campaign

`so001` is a proxy and is permanently excluded. Its partial shard was killed and
archived under:

```text
/home/mchoi/fed-space-forced-current-20260830T222317Z/
  proxy-excluded-so001-shard-0-20260831T071100/
```

The old conditional supervisor was stopped. The still-running old `so004` and
`so006` shards were also stopped after their counts were preserved and marked
`HARNESS_OBSOLETE.txt`; they are diagnostics only because they used the pre-fix
single-JVM runner and old replay hash. No old or proxy-host row is aggregated.

The allowed host range is `so002`--`so006`. The active campaign uses
`so003`, `so004`, and `so006`: `so002` is allowed but not selected because its
root filesystem is 98% full and another SliceLine campaign is active. The
`so005` host key was explicitly registered and that host is used only to
compile and test the separate v3 source snapshot, not as a primary shard.

#### Active corrected campaign

```text
Campaign: fed-space-forced-normalized-v2-20260831T053209Z
Manifest: f3f31f509290ed19d90671c2fb0b26e2b450f41e43f86862cf4b82c3fd2df7e1
Source:   cca27c6a51a0c6fac1a61e645a5de7a5ce1920309d2cc87234a1e8fdd655c233
Chunking: 10 targets per fresh Maven/Surefire JVM

so003: shard 0/3, PID 3464368, 181 chunks, running
so004: shard 1/3, PID 4081699, 181 chunks, running
so006: shard 2/3, PID 2633716, 181 chunks, running
```

At 09:40:55 CEST, all three supervisors and both follow-up layers were alive.
The primary stage had emitted 4,854 results (89.76% of 5,408): 4,100
`SUCCESS`, 405 `TARGET_NOT_REACHED`, 49 `WHOLE_PROGRAM_INFEASIBLE`, and
300 `FAILURE_REQUIRES_TRIAGE`. All 300 provisional triage targets have runtime
capability evidence; all 584 observed instruction outcomes are `SUCCESS`, with
zero successful-constraint violations. The evidence therefore points to workload
plan assertions rather than known physical failures. Primary non-success remains
provisional until one-target JVM re-entry completes.

The machine-readable status, all three watcher scripts, corrected Surefire
reports, and verified checksums for this checkpoint are fixed at:

```text
audit-results/fed-space-forced-normalized-v2-20260831T053209Z-progress-20260831T095447+0200/
SHA256SUMS.txt SHA-256:
  136ee7028f37befc22162827a665bdecac9ae7a00a8a86e2fc0b60808e011776
```

Each host also runs a proxy-free follow-up watcher. After its primary shard exits
with infrastructure `PASS`, the watcher extracts every non-`SUCCESS` target by
exact target ID, verifies a one-to-one join to the authoritative manifest, and
launches a separate retry campaign with 1 target/JVM. Watcher script SHA-256 is
`a10948b10e8ffcb844169befe992c3e089e475598f077de1b602734d5aa500c1`;
the active watcher PIDs are 3479872 (`so003`), 4096087 (`so004`), and 2650749
(`so006`).

An early four-target isolated sample tested whether the 126 primary
`TARGET_NOT_REACHED` rows are stable. Three heuristic-function targets with
different opcodes/states converted to `SUCCESS`; one DP target remained
unreached. The stable DP sample is a dynamic loop-recompile occurrence at DML
line 108 that was captured during discovery but was not reconstructed during
the isolated replay. It is therefore an `UNREPLAYABLE_DYNAMIC_OCCURRENCE`
candidate, not evidence of physical infeasibility. The immutable progress
snapshot is:

```text
audit-results/fed-space-forced-normalized-v2-20260831T053209Z-progress-20260831T060348Z/
```

All 11 DP-context `TARGET_NOT_REACHED` targets from that snapshot were then
replayed with one target per fresh JVM. All 11 remained unreached, but every
JUnit workload completed and all 33 observed FED runtime instructions recorded
`SUCCESS`. The forced constraint was never applied, so these are replay
reconstruction failures rather than lowering/runtime infeasibility evidence.

Joining the discovery occurrence to clean-replay candidates by canonical source
origin, opcode, and ordered input signature yields three distinct causes:

| Cause | Targets | Evidence |
|---|---:|---|
| emitted root ordinal drift | 3 | the same semantic occurrence exists and only `root-N` changes |
| emitted input-path drift | 3 | the same semantic occurrence exists, but dynamic rewriting changes its root/input path |
| source occurrence not emitted | 5 | the discovery compile/recompile HOP is pruned or represented differently in the clean replay |

This proves that the v2 replay key remains too strict for a subset of clean
replays because it retains the emitted-root/input path. Simply deleting that
field would be unsafe: one source expression may have multiple live compiled
occurrences. The follow-up design is therefore a secondary semantic replay key
that retains control context and canonical source identity and is accepted only
when it resolves to exactly one decision domain in the current analysis. Zero
matches remain unreplayable; multiple matches fail closed as ambiguous.

The complete 11-target campaign, relevant discovery rows, machine-readable
classification, and independently verified campaign/artifact checksums are at:

```text
audit-results/fed-space-dp-tnr-isolated-all-20260831T081822Z/
```

The audit replay mechanism now has a second, explicitly non-production key for
this case. The structural replay key remains authoritative whenever it matches.
Only when it has zero matches does the runner consult a semantic key composed of
the normalized function/control/recompile context and canonical source origin,
without the rewrite-sensitive emitted root/input path. The semantic key is
accepted only when it identifies exactly one current decision domain. Multiple
domains emit `REPLAY_IDENTITY_AMBIGUOUS` and fail closed; zero domains remain
unreached and carry no feasibility conclusion.

Re-running the same 11 DP targets with this v3 logic produced five `SUCCESS`
rows with satisfied constraints, one explicit `REPLAY_IDENTITY_AMBIGUOUS`, and
five `TARGET_NOT_REACHED` rows whose source HOP is not emitted in the clean
replay. All five successful receipts record `matchMode=SEMANTIC_UNIQUE`. The run
recorded 32/32 successful FED runtime-capability observations. Recomputing all
2,508 Java-recorded semantic hashes with the Python manifest implementation
produced zero mismatches. Focused Java tests, test compilation, Python
`py_compile`, checksum verification, and `git diff --check` pass.

```text
audit-results/fed-space-dp-tnr-v3-final-20260831T084951Z/
```

The active remote v2 campaign has not been interrupted or modified. After its
primary and one-target watcher passes finish, only persistent unreached targets
will be joined to a v3 manifest and retried; already classified targets will not
be rerun.

The full v3 manifest is already prepared from all 14,024 authoritative
discovery candidate rows:

```text
Remote: so006:/home/mchoi/fed-space-audit-v3-prepared-20260831T085300Z/
Local:  audit-results/fed-space-v3-manifest-prepared-20260831T085300Z/
Targets: 5,408
Manifest SHA-256:
  ca5f8a5a80af817493f397728ce76ec03cc3d59e5b112e826ef2d884f8a1f37a
Builder SHA-256:
  a73cd00dc2ff491672c202b6a1b9bf00de1013abc1d9777b1f017061a850ed2e
```

Its target-ID set is identical to v2, all non-semantic fields are identical,
and no semantic key is empty. Among 3,742 context/semantic groups, 104 contain
two structural discovery occurrences (maximum collision size two). These rows
are deliberately not merged: the current replay analysis must resolve the
semantic key to one domain or emit the explicit ambiguity classification.

The v3 continuation is now staged without changing the active v2 source tree:

```text
Source snapshot on so003/so004/so005/so006:
  /home/mchoi/fed-space-audit-semantic-v3-20260831T090250Z
V3 source receipt SHA-256:
  d35f1c5777b3742e0931e323ea5b30b9e73f88c7fa1df6ae58300adc099d1e1a
V3 manifest SHA-256:
  ca5f8a5a80af817493f397728ce76ec03cc3d59e5b112e826ef2d884f8a1f37a
Semantic watcher SHA-256:
  19c11cde1868a6d92b620ec5b2efb1c2fd17fcf06fea8c0352b275def377c85c
```

On a clean staging tree at `so005`, `test-compile` and the corrected focused
test invocation passed: 8/8 `PlannerSpaceAuditTest`, 5/5
`ExactPhysicalForcedStateAuditTest`, and 1/1
`FederatedForcedStateAuditRunnerSelectionTest`. An earlier remote invocation
used two incorrect package prefixes and lacked remote `set -e`; it is explicitly
discarded rather than counted as validation. Only the corrected FQCN run under
`set -euo pipefail` is evidence.

An end-to-end smoke then selected one DP target that the structural replay had
not reached. From the staged source and full v3 manifest it completed with
`SUCCESS`, `constraintSatisfied=true`, 3/3 successful runtime-capability rows,
and a forced receipt with `matchMode=SEMANTIC_UNIQUE`. Its run manifest names
`SOURCE_SHA256SUMS_V3.txt` and the expected v3 source-receipt hash.

```text
audit-results/fed-space-semantic-v3-smoke-20260831T091100Z/
ARTIFACT_SHA256SUMS.txt SHA-256:
  1d107ba5ad4cb74da177b519e8e5ba65b66f7036b5ec2d5629614f556696b62e
```

One semantic watcher is attached to each existing isolated watcher. It waits
for the v2 isolated campaign, selects only persistent `TARGET_NOT_REACHED`
rows, verifies a one-to-one target-ID join and a nonempty semantic key, and
replays them at one target per JVM from the separate v3 snapshot. The active
semantic watcher PIDs are 3778801 (`so003`, waiting for 3479872), 193806
(`so004`, waiting for 4096087), and 2972640 (`so006`, waiting for 2650749).
All are alive; no v3 output is created before the v2 isolated stage completes.

## 4. Confirmed defects found by the audit

### 4.1 Control-expression planner scope was lost during lowering

**Symptom.** If/while/for predicate and loop-bound Lop DAGs called `Dag.getJobs(null, config)`. The null statement-block argument also served as planner-registry scope, so exact selected `REFED`/materialization decisions for control expressions could disappear during lowering.

**Fix.** Added `Dag.getJobsForControlExpression(plannerScope, config, logicalHopRoots)`. It keeps instruction liveness cleanup disabled for the small control expression while resolving planner registries against the owning control statement block. `DMLTranslator` now uses this path for if/while predicates and for-loop from/to/increment expressions.

**Evidence.** Targeted LogReg run:

```text
audit-results/targeted-logreg-control-scope-fix2-20260830T185632Z/
```

- 6 executable tests passed; 3 expected tests remained skipped;
- 144 successful runtime witnesses;
- 0 runtime failures;
- 0 confirmed/potential Missing cases;
- 0 selected/runtime input divergences;
- exact `fed_refed` lowering and execution matched the selected plan.

### 4.2 Federated reblock published an output after an invalid asynchronous worker instruction

**Symptom.** `ReblockFEDInstruction` converted Spark `rblk` into worker-side `CP rblk`. CP has no `rblk` parser. Because the request path did not consume response errors before publishing the new federated ID, the coordinator could expose a nonexistent result mapping and the capability audit could incorrectly count the parent instruction as successful.

**Fix.** A federated worker partition is already stored as a CP `MatrixBlock`/`FrameBlock`; changing Spark block size is metadata-only for the federated logical object. The implementation now:

1. creates a distinct worker variable with typed CP `cpvar`;
2. executes synchronously;
3. consumes the responses through `FederationUtils.sumNonZeros(...)`, propagating worker errors;
4. publishes the new mapping only after success;
5. updates coordinator block-size and nonzero metadata.

**Evidence.** Targeted n-ary plan/run:

```text
/tmp/fed-nary-audit-1788118076/
```

The test successfully executed:

- 2 `InitFEDInstruction` instances;
- 2 corrected `ReblockFEDInstruction` instances;
- 1 `BuiltinNaryFEDInstruction` (`nmin`).

Exact occurrence, selected input signature, runtime input signature, and output state matched. The targeted comparator found 0 confirmed Missing, 0 potential Missing, 0 divergences, and 0 failures.

### 4.3 Common `wait=true` barrier did not reject worker `ERROR` responses

**Symptom.** `FederationMap.execute(..., true, ...)` delegates to
`FederationUtils.waitFor(...)`. A transport future completes normally even when
the returned `FederatedResponse` has status `ERROR`; the old barrier called only
`Future.get()` and never inspected that status. Consequently, any FED instruction
that waited and then published an output mapping could still expose a nonexistent
remote value.

**Fix.** `FederationUtils.waitFor(...)` now checks every completed response and
rethrows worker-side errors before returning. Added a component regression test
that supplies a completed future containing an `ERROR` response and requires a
`DMLRuntimeException`.

**Evidence.** The following targeted suite passes after the change:

```text
FederationUtilsRefedReuseLayoutTest
PlannerSpaceAuditTest
```

This closes the false-positive success hole for all paths that use the common
`wait=true` barrier. Truly fire-and-forget calls that use `wait=false` still
require separate inspection.

### 4.4 Legacy Spark `mapmm` sent an invalid worker CP instruction

**Symptom.** `MMFEDInstruction` changed only the execution-type token of
`SPARK mapmm/cpmm/rmm`; the resulting `CP mapmm` retained a Spark-only opcode and
Spark-specific operands. Worker parsing failed, while FOUT branches could publish
the requested ID before observing that error.

**Fix.** The coordinator now constructs the semantically equivalent worker-local
`CP ba+*` instruction from typed operands. Every FOUT/partial-output branch
consumes the worker responses before installing its federation map, and regular
ROW-by-COL tests carry an explicit `public` privacy contract because the legacy
path legally collects and rebroadcasts the right input.

**Evidence.** Both parameterized Spark cases were executed in separate JVMs:

```text
/tmp/fed-mm-isolated-1788120019/0
/tmp/fed-mm-isolated-1788120019/1
```

Both passed; the capability capture contains 2 successful `MMFEDInstruction`
witnesses and 0 failure rows. The full eight-case legacy class is sensitive to
cross-case worker/planner lifecycle state when reused in one JVM, so the runtime
capability claim here is intentionally based on the isolated positive cases.

### 4.5 Forced-FOUT TSMM could publish a dangling broadcast result

**Symptom.** The forced-federated branch of `TsmmFEDInstruction` dispatched a
broadcast and worker TSMM with the default asynchronous `execute(...)` overload,
discarded every returned future, and immediately installed the requested output
ID as a `BROADCAST` federation map. A worker-side parse or execution error could
therefore become a dangling coordinator-side mapping.

**Fix.** This branch now uses the common `wait=true` completion barrier before
`setOutputFederated(...)`. Together with the corrected barrier in Section 4.3,
the mapping is installed only after every worker response is successful.

**Evidence.** The source-wide dispatch scan described in Section 6.2 identifies
this as the only implicit-asynchronous `FederationMap.execute(...)` result that
was discarded before an output mapping was published. The ordinary TSMM runtime
path also passes in isolation and records a positive class witness:

```text
/tmp/fed-tsmm-audit-1788120506
FederatedPCATest#federatedPCASinglenode[0]: 1 test, 0 failures/errors
TsmmFEDInstruction: SUCCESS, input ROW, output local 10x10
```

The existing FedAll L2SVM test passes after the change, but its selected physical
plan lowers the relevant TSMM occurrences to local CP instructions; it is not
claimed as a direct forced-FOUT TSMM witness.

### 4.6 Dynamic recompilation applied compiled-plan invariants in runtime-planner mode

**Symptom.** `Recompiler` unconditionally reconstructed `REFED`, forced-FOUT,
and local-materialization registries from recompiled HOPs. With the default
`RUNTIME` planner there is no selector-owned placement transaction to restore,
yet transient read/write validation was applied as though one existed. The
temporarily enabled Spark cumulative tests therefore failed before runtime with:

```text
TRead/TWrite permits only <CP,LOUT> or <FED,FOUT>
```

`Compile Phase FedPlanner` was zero in this execution, confirming that the
recompiler had introduced a compiled-planner constraint without a compiled
planner invocation.

**Fix.** Recompiler-side placement-registry reconstruction now runs only when
`FEDERATED_COMPILATION` is enabled or the configured planner is a compiled
planner. In `NONE`/`RUNTIME` mode, stale lowering registries are cleared instead
of being reinterpreted as current selector authority.

**Evidence.** Before the fix, the cumulative instruction was unreachable:

```text
/tmp/fed-cumulative-audit-1788118189
```

After the fix, the same test reaches the concrete
`CumulativeOffsetFEDInstruction`; this exposed the independent worker-format
defect in Section 4.7 instead of the false TRead/TWrite rejection.

### 4.7 Spark cumulative-offset format was not a valid worker CP instruction

**Symptom.** `CumulativeOffsetFEDInstruction` attempted to convert Spark
`bcumoff*` and Spark aggregate instructions by changing only their execution
type. Examples included `CP uack+ ... SINGLE_BLOCK` and `CP bcumoff* ...`; the CP
parsers accept neither Spark's `SINGLE_BLOCK` operand nor a `bcumoff*` opcode.
This class was the last of the original 36 concrete FED classes without a
positive runtime witness.

**Fix.** A worker owns a complete CP matrix partition, not Spark blocks. The
implementation now translates `bcumoff*` back to its semantic unary cumulative
opcode (`ucumk+`, `ucum*`, `ucumk+*`, `ucummin`, or `ucummax`) and reuses
`UnaryMatrixFEDInstruction`. That implementation already computes and applies
cross-partition offsets for row partitions. The derived Spark offset input and
all Spark-only operands are intentionally omitted. Dynamic conversion is
admitted only when the data input itself is federated; `bcumoff+*` remains
physically row-only because each worker must see both input columns.

**Evidence.** With SUM/MIN/MAX Spark tests temporarily enabled, the complete
16-case cumulative class passed:

```text
/tmp/fed-cumulative-full-1788121540
Tests run: 16, failures: 0, errors: 0, skipped: 0
```

The final combined local regression is:

```text
/tmp/fed-runtime-audit-regression-1788122218
FederationUtilsRefedReuseLayoutTest: 6 passed
PlannerSpaceAuditTest:               5 passed
FederatedFullCumulativeTest:        19 passed, 1 intentionally skipped
Runtime capability rows:           68 SUCCESS, 0 FAILURE
```

The capability log contains nine successful
`CumulativeOffsetFEDInstruction` witnesses: `bcumoffk+`, `bcumoff*`,
`bcumoffmin`, and `bcumoffmax` over both ROW and COL inputs, plus the physically
row-only `bcumoff+*` over ROW. The formerly ignored SUM/MIN/MAX tests and the new
PROD/row-only SUMPROD cases are permanent regression coverage. The single skip
is the explicitly infeasible COL cumsumprod combination; it is not exposed as a
federated state by the shared candidate rule.

### 4.8 SPARK-origin worker instructions leaked planner output flags into CP parsing

**Symptom.** Compiled FED instruction strings may carry the coordinator-only
final operand `FOUT`, `LOUT`, or `NONE`. The common array/single
`FederationUtils.callInstruction` overloads removed that operand only when the
original execution token was `FED`. A dynamically converted `SPARK rightIndex
... NONE` was consequently serialized as `CP rightIndex ... NONE`, which has
one operand too many for `IndexingCPInstruction`.

**Fix.** The worker-call boundary now removes a planner output flag whenever the
target execution type is CP, independent of whether the coordinator instruction
originated as `FED` or `SPARK`. A component test covers both single- and
array-instruction overloads.

**Evidence.** Before the fix, row-partitioned Spark cumsumprod failed in
`IndexingFEDInstruction` with `Invalid number of operands`. After the fix the
same `rightIndex` succeeds and the downstream row-feasible `bcumoff+*` also
executes successfully:

```text
/tmp/fed-cumulative-sumprod-fixed-1788122004/
  testSumProdDenseMatrixSP_0_
```

### 4.9 Planner lowering authority leaked into a later unplanned program

**Symptom.** A JUnit method first compiled a federated-planner program and then
an ordinary CP/Spark reference program in the same JVM. The second compilation
had no compiled selector, but `PlannerRuntimePlacementAudit` and the relocation
registries still held the prior program's authority. Valid reference
instructions were rejected as `LOWERING_UNPLANNED`.

**Fix.** At the final-HOP boundary, a compilation with no compiled planner now
clears placement-audit authority, runtime actions, and REFED/FOUT/local-
materialization registries before returning. Recompiler's non-compiled path
performs the same registry cleanup. A unit test installs a prior plan, starts an
unplanned compilation, and verifies that an unrelated instruction is no longer
judged against stale authority.

**Evidence.** After this change, the full audited multiply class no longer fails
on ordinary reference-program `rblk` or `append` instructions. The only two
remaining failures moved to the independent Spark overwrite described next.

### 4.10 Global Spark mode overwrote selector-owned CP/FED placement

**Symptom.** `Hop.checkAndSetForcedPlatform()` preserved a forced FED choice in
`SINGLE_NODE`, but `-exec spark` unconditionally replaced `_etypeForced` with
`SPARK`. Thus a selector-owned `FED/FOUT` matrix multiply lowered as
`SPARK mapmm ... NONE`; after fixing that occurrence, the selector-owned
`CP/LOUT` write was likewise emitted as a Spark write. This was not an audit
allow-list gap: the compiled physical plan was genuinely not being realized.

**Fix.** Global Spark forcing now applies only to ordinary, non-planner-selected
HOPs and still preserves legacy explicitly forced FED operations. Once a HOP is
marked `plannerPlacementSelected`, its concrete CP/FED execution choice is not
overwritten by the global platform mode.

**Evidence.** New unit coverage proves all three cases: selected FED remains
FED, selected CP remains CP, and an ordinary CP candidate is still forced to
Spark. The complete audited `FederatedMultiplyTest` now passes all 8 variants:

```text
/tmp/fed-multiply-audit-selected-platform-1788124104
Tests: 8, failures/errors: 0
Runtime capability: 36 SUCCESS, 0 FAILURE
```

This run includes Spark and single-node compilation paths and validates that the
selected aggregate-binary placement, physical reblocks, relocations, and write
placement all survive lowering and execution.

### 4.11 Valid privacy metadata without a privacy field became an unresolved worker response

**Symptom.** `GetPrivacyConstraints` successfully parsed a worker metadata file
but returned a null payload when no privacy field existed. The common
pre-selector resolver intentionally rejects unexplained null payloads, so a
privacy-none L2 workload aborted before any selector ran.

**Fix.** The worker now encodes this one authoritative case as explicit
`public`. It does not default transport, I/O, parse, missing-file, null-response,
or malformed-response failures to public. Consequently, all selectors still
consume the same conservative privacy-filtered placement domain, while ordinary
unannotated SystemDS data retains its established unrestricted semantics.

**Evidence.** The new worker-boundary contract test and the complete
`FederatedL2SVMPlanningTest` pass locally. An isolated audited rerun on `so003`
passes with 95 successful and zero failed runtime-capability rows.

### 4.12 Exact CP/FOUT lowering confused selected residency with current residency

**Symptom.** A forced, privacy-legal `m(min) CP/FOUT/ROW` state was present in
`P`, admitted by the exact whole-program constraints, and selected by the
forced-state hard factor. Lowering then rejected it with:

```text
selected exact FOUT source is already FED/FOUT
source=Nary/.../CP/FOUT
```

The underlying predicate mixed two different meanings of the Lop FOUT marker.
For an actual FED Lop, FOUT describes the value already resident on workers.
For a selected CP/FOUT producer, the CP Lop still computes a local value and
FOUT describes its required residency *after* the planned `fed_fout`
materialization. Treating the latter as already federated made a deliberately
published CP/FOUT state physically unreachable.

**Fix.** `Dag.insertFoutMaterializeLops` now recognizes an exact, non-concrete
CP/FOUT source as the local producer that must be uploaded. It continues to
reject concrete federated sources, anchored transient reads, FED execution, and
already inserted `Federated`, `FederatedRefed`, or
`FederatedFoutMaterialize` values. The change is restricted to planner-owned
exact FOUT authority; legacy best-effort lowering behavior is unchanged.

**Evidence.** A new Lop-level regression constructs an exact CP/FOUT n-ary
producer and verifies that the selected consumer is rewired through
`FederatedFoutMaterialize`. End-to-end replay then proves both formerly failing
targets:

```text
/tmp/fed-forced-two-fixes-1788127157
CP/FOUT/ROW: SUCCESS, constraintSatisfied=true, fed_fout executed
CP/LOUT/-:   SUCCESS, constraintSatisfied=true
```

Additional validation passes:

```text
FederatedDagLocalMaterializeTest:                 5 tests
CampaignBG014CpFoutMaterializationAuthorityRedTest: 1 test
FederatedPlannerFallbackIntegrationTest:        130 tests
FederatedNaryBuiltinPlanningTest (normal mode):    1 test
ExactPhysicalForcedStateAuditTest:                 3 tests
```

## 5. Coverage closed since the first full run

The following formerly uncovered runtime classes now have positive local witnesses:

| Class | Evidence | Status |
|---|---|---|
| `VariableFEDInstruction` | cast-to-frame, cast-to-matrix, writer tests under `/tmp/fed-variable-audit-1788117523` | successful |
| `TernaryFrameScalarFEDInstruction` | frame-map test under `/tmp/fed-frame-map-audit-1788117573` | successful |
| `BuiltinNaryFEDInstruction` | new planner/runtime n-ary test under `/tmp/fed-nary-audit-1788118076` | successful after reblock fix |
| `MMFEDInstruction` | isolated Spark mapmm cases under `/tmp/fed-mm-isolated-1788120019` | successful after worker-kernel and response-barrier fixes |
| `CumulativeOffsetFEDInstruction` | cumulative Spark cases under `/tmp/fed-cumulative-full-1788121540` plus isolated PROD/SUMPROD artifacts | successful after recompiler and typed unary translation fixes |

All five initially uncovered concrete classes now have positive runtime
witnesses. This closes instruction-class coverage, but not state-space coverage:
a class witness does not prove every `(occurrence,input signature,state)` tuple.

## 6. Remaining investigation

### 6.1 `CumulativeOffsetFEDInstruction`

This classification is now resolved. The instruction is a reachable runtime
conversion surface, not dead legacy code. Its original implementation was
physically invalid because it mixed Spark instruction format with worker CP
execution. Sections 4.6--4.8 describe the three independent boundaries that
had to be repaired before it became a positive `R` witness.

The physical domain is intentionally not Cartesian: `bcumoff+*` is infeasible
for a COL partition because each worker holds only one of the two columns
required by cumsumprod. The shared candidate rule already encodes this row-only
condition. Runtime conversion now applies the same condition rather than
constructing an illegal federated plan. COL cumsumprod therefore remains a
local/materialized alternative, not a missing federated state.

### 6.2 Asynchronous response correctness

The common `wait=true` barrier now propagates worker errors. A static scan of all
43 Java files in `runtime/instructions/fed` (127 textual `execute` call sites,
including worker-UDF methods and non-federation operators) then inspected every
coordinator-side `FederationMap.execute(...)` invocation that omitted the explicit
wait flag. All result-bearing calls retain the returned futures and consume them
through `getData`, `getResults`, aggregation, binding, dimension computation, or
an equivalent explicit `Future.get()`. The single discarded asynchronous result
that installed a physical output was forced-FOUT TSMM, fixed in Section 4.5.

This closes the known fire-and-forget false-success path in the FED instruction
package. It does not turn an unexecuted candidate into an `R` witness: runtime
success still has to be observed for each forced state.

### 6.3 Exhaustive state coverage versus workload observation

Ordinary suites execute selected plans, not every state in `P(o,i)`. The
immutable full run witnessed only 242 of 9,642 published states. Moreover, most
runtime instructions cannot yet be joined to an exact occurrence because they
come from runtime-planner paths, fixtures without compiled-placement authority,
or downstream instructions whose production identity was not retained.
Consequently:

- an exact successful `R` witness outside `P` can prove `Missing`;
- an exact forced published state that reaches a rooted physical failure can
  prove an individual `Spurious` state;
- an unwitnessed published state is only `UNTESTED`;
- the 644 generalized witnesses outside the exposed set are hypotheses, not
  confirmed `Missing`, because they lack the exact `(o,i)` join.

The stable replay identity and successful n-ary force remove the previous
mechanical blocker. Coverage completeness now depends on executing the
context-bearing manifest and classifying each result, not on adding more
instruction-class smoke tests.

## 7. Immediate continuation plan

1. Monitor the three active, proxy-free shards and require each final campaign
   summary to contain every expected target exactly once, zero unexpected IDs,
   zero duplicate rows, and a complete checksum receipt.
2. Extract every result other than `SUCCESS` and replay it with exactly one
   target per Maven/Surefire JVM. The isolated result, not the 10-target primary
   chunk, is authoritative for final classification.
3. Separate `WHOLE_PROGRAM_INFEASIBLE` (a state in local published `P` with no
   legal global completion) from `TARGET_NOT_EXPOSED`, runtime failure, lowering
   failure, workload-specific plan assertions, and infrastructure failure.
4. Aggregate only `so003`, `so004`, and `so006` corrected-campaign rows. Reject
   all `so001` proxy artifacts and every output bearing `HARNESS_OBSOLETE.txt`.
5. Regenerate Missing, Spurious, infeasible, and UNTESTED tables, then run
   compile, focused regression, source-manifest, campaign-checksum, and diff
   validation before making a global planning-space claim.

## 8. Current conclusion

The P/L/R audit now has the required mechanics: shared candidate/privacy-domain
capture, exact lowering identity, post-execution runtime witnesses, coherent
whole-program forced placement, parameter-leaf replay, normalized audit-only
occurrence identity, explicit whole-program-infeasible receipts, and bounded
fresh-JVM execution.

The audit has found and repaired correctness defects outside selector search,
and it has also identified audit-only failure modes that must not be confused
with planner defects. In particular, a selector-visible local state may be
excluded by whole-program constraints, and a semantically executed forced plan
may intentionally violate a workload's baseline heavy-hitter assertion. Both
are retained as separate evidence categories rather than being labeled
Spurious automatically.

The broad immutable discovery still reports zero confirmed exact-identity
Missing states and closes concrete FED instruction-class coverage, but it does
not establish state-space completeness. The authoritative 5,408-target forcing
campaign is now active exclusively on allowed execution hosts. The global
Missing/Spurious conclusion remains open until those shards finish and every
non-success receives isolated re-entry.


## 9. Post-fix supplement: all eight pre-runtime anomalies now execute

The immutable authoritative artifact originally classified eight published
states as `TRIAGE_NO_RUNTIME_CAPABILITY_EVIDENCE`. Their original logs and
classifications remain preserved, but three implementation defects have now
been fixed:

1. TSMM/MM-chain/transpose fusion no longer crosses incompatible
   selector-owned execution/output placements.
2. Exact local materialization accepts federated frame producers, and planner
   prefetch creates a genuinely local frame without stale federation metadata.
3. Federated frame collection reconciles worker-observed schemas before copying
   row- or column-partitioned data.

The clean one-target-per-JVM supplement completed all eight targets with eight
satisfied constraints, eight `SUCCESS` results, and 38/38 successful runtime
capability records:

```text
audit-results/fed-space-eight-lowering-verified-20260831T125835+0200/
SHA256SUMS.txt SHA-256:
  f4936b152ef63a8a37e369b6e2f7d3a222bc79c9761e246f865773345b5dd73f
```

Focused validation passed 87 tests with zero failures/errors, plus a clean
compile. The immutable aggregate is not rewritten. A supplemental post-fix
view moves these eight target IDs from no-capability triage to positive
execution witnesses (`PUBLISHED_LEGAL_EXECUTED: 4,823 -> 4,831`; triage:
`8 -> 0`). This does not prove global candidate-space completeness; exact
context, occurrence, and ordered-input joins are still required to establish
`Missing=(R∩L)-P`.
# Latest checkpoint

The current detailed checkpoint is:

```text
docs/FED_PLANNER_P_L_R_AUDIT_CHECKPOINT_2026-08-31_2019_KO.md
```

It includes the attempt-local provenance fixes and adds a runtime conversion
frontier that separates direct FED lowering, dynamic CP/SP-to-FED conversion,
and federated-input non-conversion. Global `Missing=(R∩L)-P` remains open because
observed runtime success is not an exhaustive enumeration of `R`.
