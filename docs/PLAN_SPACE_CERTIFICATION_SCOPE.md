# Plan-space certification scope and current status

For the current verdict, including production-source drift after the frozen attempt, read the [single final report](G009_INDEPENDENT_PLAN_SPACE_CERTIFICATION_FINAL_REPORT_2026-09-21.md). The certificate and bundle named below belong to the earlier `UNKNOWN` attempt.

The machine-readable discovery inventory is
[`src/test/resources/fedplanner/plan-space/workloads.json`](../src/test/resources/fedplanner/plan-space/workloads.json).
Regenerate/check it with:

```
python3 scripts/fedplanner/build_plan_space_inventory.py
python3 scripts/fedplanner/build_plan_space_inventory.py --check
python3 scripts/fedplanner/build_plan_space_rule_ledger.py --check
python3 scripts/fedplanner/build_plan_space_exclusion_ledger.py --check
scripts/fedplanner/run_plan_space_certification.sh tiny
PLAN_SPACE_ARTIFACT_ROOT=/grid/3/cofee-lm-sweep-mchoi-20260914/plan-space-certification-20260921 PLAN_SPACE_JOBS=4 scripts/fedplanner/run_plan_space_certification.sh full
```

The `full` command exits nonzero when the certificate is `UNKNOWN` or `FAIL`;
do not treat a completed process or written certificate as a PASS. Recheck a
certificate from the evaluation repository with:

```
python3 calibration/plan_space_verify.py check-certificate --certificate CERTIFICATE --expected-discovery-sha SHA256_OF_workloads.json
```

The 2026-09-21
certificate is at
`/grid/3/cofee-lm-sweep-mchoi-20260914/plan-space-certification-20260921/5325fa7ba44ade685ed44724cc16b5773ca1468c3b36b5422f51589f50579141/certificate-full.json`
and its independently checked status is `UNKNOWN`.

Available source/input bytes can be frozen and checked independently with:

```
python3 scripts/fedplanner/freeze_plan_space_bundle.py --output BUNDLE
python3 scripts/fedplanner/freeze_plan_space_bundle.py --output BUNDLE --check --check-current
```

Reuse of the same output directory with different inputs
fails rather than rewriting its index. The current `input-bundle-context-pinned` under
the artifact directory has 757 file identities and 613 SHA-256 objects; the
index SHA-256 is
`083e1a86a93346f7d58943c91e3b795fd05638b6bfa04f970cfa588a7cc5b0fa`.
The bundle reports `planCoverage=UNKNOWN` because it does not contain all
worker data or live compiler/runtime context.

The inventory records 13 base-campaign registrations, 10 ML10 registrations,
64 worker-qualified frozen planning templates, 28 generated microbenchmark
programs, 69 optional or common templates, 16 archived template revisions,
and 79 further unclassified DML source revisions. The 279 source-qualified
entries include hashes for all 206 DML files currently discovered in the
evaluation and legacy workload trees. They preserve both `gmm` and `gmm-vvi`,
and keep same-named common and worker-qualified programs separate when their
bytes or conditions differ. These are **source
discoveries**, not a claim that each is an executable verification cell. In
particular, each entry marked `IN_SCOPE` still has `verificationStatus=NOT_RUN`.
The four frozen planning contexts expose 224 profile-qualified planned
conditions (14 workloads × 4 worker counts × 4 network profiles); these are
snapshot signatures, not complete input/privacy/compiler cells. The other
59 in-scope entries have no frozen condition list, and all 115 still lack
complete condition certification. `HISTORICAL` and
`UNSUPPORTED` entries remain visible until their relationship to the current
supported workload contract is reviewed. Do not treat those statuses as a
successful exclusion from the requested "all workloads" certification.

The source registry combines the base network-quality campaign, ML10, the
frozen planning snapshot, the generated microbench families, the common DML
template bundle and the older archived code, plus an unclassified row for any
remaining DML file. A workload name is not a cell
identity: input DML plus imports, shape, metadata, actual FederationMap,
privacy/release policy, worker/topology, compiler options, call/recompile
context and source/build hashes define a cell. For example, ML10 `gmm` and
planning `gmm-vvi` remain separate, and `pca` with different input shapes is
not deduplicated by name. The current inventory hashes the registries and
template files; a future executable manifest must bind **every** cell input.

The separate [`rule-ledger.json`](../src/test/resources/fedplanner/plan-space/rule-ledger.json)
freezes the 48 registered rule families and 56 previously audited
transformations. It also hashes 138 current federated planner/FED runtime Java
files, so a changed or newly added file makes `--check` fail until reviewed.
Every family still has `runtimeTupleCoverage=OPEN` and no claimed
positive/negative fixture mapping. This ledger detects drift; it does not
discharge any runtime or transformation proof obligation or prove that the old
56-row list covers a newly introduced control-flow branch.

The generic shard runner is in
`/home/mchoi/cofee-evaluation/calibration/plan_space_verify.py`. Its execution
manifest is distinct from the discovery inventory: each `IN_SCOPE` discovery
identity needs one or more frozen execution cells, and each listed planned
condition needs a matching cell ID, condition hash, and snapshot payload.
`/home/mchoi/cofee-evaluation/calibration/build_plan_space_cells.py` generates
`plan-space-cells.json` with all 279 discoveries and 224 planned profiles
accounted for in 447 cells. Planning-snapshot cells hash literal transitive DML
imports and referenced worker-data `.mtd` sidecars. All 224 planned profile
cells statically validate literal worker order, row ranges and sidecar/global
shape/privacy against the frozen context. The copied input and worker-partition
manifests match the context SHA-256 digests, and their profiled partition
ranges match the DML/sidecar geometry; eight templates without profile cases
remain unresolved. Missing or disagreeing dependencies fail generation. These
are still an execution backlog: no in-scope cell has actual
data-byte/compiler/runtime attestation or all three exporters. Each source adapter needs
its **own** complete finite domain, input/build/spec hashes and an exact
assignment audit, and must output the independent reference, production
candidate or existing exhaustive plan set. The runner must report
`INCOMPLETE` or `UNKNOWN` until
those adapters and all rule/shape cases are covered. A captured census receipt
from `WorkloadSpaceCensus` is not a complete plan set and must not be used as
an adapter success.

Current completion boundary:

| Requirement | Status |
|---|---|
| Cross-repository workload source discovery | Recorded; role/condition review open |
| Frozen executable cells with actual input maps/privacy/context | 447-cell backlog; planning imports/sidecars pinned, actual data and compiler capture open |
| Runtime-backed independent rule matrix for every encountered tuple | Open |
| Independent full primitive domain and legality over every cell | Open |
| Production decoded full joint plan export and selector-entry parity | Raw pre-policy placement/receipt/action stream; action-universe proof and full adapter open |
| Existing exhaustive complete joint plan export | Raw encoded assignment and model-alternative identity stream; domain proof/full adapter open |
| Full-workload three-way exact comparison | Full-mode backlog gate run and rechecked as UNKNOWN; no three-way workload comparison |
| Docker runtime replay of representative plans | Not run |

The packaged evaluation bundle lacks
`harness/sigmod2021-exdra-p523/experiments/run_LAN_docker.sh`, but an external
stage at `/home/mchoi/ml-p1p2-sliceline-fedplanning-w4-once-20260921/experiments/run_LAN_docker.sh`
has the official `--planning-validation` profile. Its documented scope is
protected **14 workloads, worker=4, LAN**. It does not yet cover this broader
source-qualified inventory, the 28 generated microbenchmarks, or all worker/network
conditions; its workload generator/validator paths also require a current
preflight. The frozen native planning snapshot does not package its JAR and
input values. The root filesystem had about 0.28 GB free at the latest check.
A user-owned writable directory under `/grid/3` passed a small write preflight
and stores the UNKNOWN certificate and bounded fixture artifacts; its filesystem
had about 1.1 TB free, but larger-run quota is unverified. Before
large execution, bind the correct official Docker launcher, current
runtime/inputs and an isolated artifact directory that passes a capacity
preflight; preserve existing artifacts.

Repository `AGENTS.md` requires protected privacy cases and the Docker
launcher for workload experiments. The generated
[`ignored-tests.json`](../src/test/resources/fedplanner/plan-space/ignored-tests.json)
records 14 `@Ignore` PUBLIC-source planner/placement tests with method, reason
and source hash; the gate detects drift. These are explicit untested cases, and no
protected result may be generalized to
those cases. Execution success, bounded test success and a full search-space
certificate are separate statuses.
