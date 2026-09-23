# Current P/E campaign gate

`run_current_pe_campaign.py` captures the entire frozen-ready catalog, rechecks
the stored P and E native models, and starts physical comparison only when both
model matrices pass their artifact checks. It does not turn a captured-model match into an
independently certified P/E equality. Exit code 0 requires every required cell
to be `EQUAL` under a `FULL_CURRENT` campaign. Exit code 2 means incomplete;
exit code 1 means an error or a demonstrated difference.

For the 612-cell derived-argv cohort, set `ROOT` to the frozen input directory,
`BUILD` to an immutable, hash-checked source/class snapshot, and `ARTIFACTS`
to a new campaign artifact directory. Create the build snapshot after the Java tests pass:

```bash
python3 scripts/fedplanner/freeze_current_pe_build.py \
  --source-root /home/mchoi/systemds-g009-integration --build-root "$BUILD"
```

The run and verify commands use the same artifact directory:

```bash
python3 scripts/fedplanner/run_current_pe_campaign.py run \
  --campaign "$ROOT/campaign.json" --catalog "$ROOT/catalog.json" \
  --evaluation-root "$ROOT/evaluation" --build-root "$BUILD" \
  --verification-root /home/mchoi/cofee-evaluation \
  --artifact-root "$ARTIFACTS" \
  --p-jobs 3 --e-jobs 3 --max-jvms 8 --timeout 3600 \
  --p-job-disk-gib 2 --e-job-disk-gib 8

python3 scripts/fedplanner/run_current_pe_campaign.py verify \
  --campaign "$ROOT/campaign.json" --catalog "$ROOT/catalog.json" \
  --evaluation-root "$ROOT/evaluation" \
  --verification-root /home/mchoi/cofee-evaluation \
  --artifact-root "$ARTIFACTS"
```

`verify` reads saved artifacts and runs no Java compiler or planner. The runner
stores per-cell receipts under `p-models`, `e-models`, and, once enabled,
`physical-artifacts`/`physical-results`. `suite.json` records the run;
`suite-verification.json` records an independent offline replay. Capture logs
are kept beside them. A second `run` resumes completed, verified cells and
retries matching-binding producer failures. Source/class, catalog, runner, and
verifier bindings prevent reusing receipts from another snapshot. Unreadable,
stale, or corrupt P/E evidence produces a canonical `ERROR` receipt and moves
the prior exact bytes to a unique `prior-capture-*` directory. An explicit
`--recapture-invalid` on either matrix runner or the campaign `run` command
permits recapture of those cells; ordinary resume leaves them as errors.

Physical comparison imports the already verified P/E model matrices. A cell
whose exact E raw product exceeds `--e-raw-budget` is recorded as `INCOMPLETE`
before loading its large model a second time. Raising that budget does not
replace the required exact projection for Pca w1 or P1/P2.

The frozen 612-cell scope is `FROZEN_COHORT_DERIVED_ARGV_612`, not historical
`FULL_CURRENT`. Its 64 SliceLine conditions are source-backed compile-model
derivations. Even a fully matching 612-cell run must keep this scope label.
The remaining P acceptance and large-space physical projection obligations
are recorded in the execution report, and any such gap keeps the gate closed.
