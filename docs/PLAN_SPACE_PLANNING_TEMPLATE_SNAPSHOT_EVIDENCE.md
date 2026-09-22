# Frozen planning-template compiler and pre-builder snapshot smoke

`PlanningTemplateSnapshotSmokeTest` exercises the exact 14 cases listed by each
frozen `context-w{1,3,5,7}.json` (56 DML files). These include `P1_FULL` and
`P2_PREP`; `sliceline-kdd98` and `sliceline-uscensus` are outside those
contexts. It is an integration
test against the optional sibling tree
`../cofee-evaluation/planning_study/native/input_templates`. Set
`-Dplan.space.planning.templates=/absolute/path/to/input_templates` to use a
different staged copy. The test skips when that tree is absent; a certification
run must check the JUnit skipped count and treat a skip as **UNKNOWN**.

For each case, the test reads its program path, SHA-256, worker count, and
source privacy from that worker's context. It verifies the case-name set and
checks the DML bytes against both the context digest and `SHA256SUMS`, resolves
any `source()` import into the staged `common/` directory, and checks the
imported bytes against the same hash list. Only that import path literal is
changed in memory so the parser can resolve it from any working directory.
It then parses the DML, runs live-variable analysis, parse-tree validation,
and HOP construction. Before invoking any placement builder, the test gives
`PrebuilderSnapshot.capture` explicit origin, privacy, and row-partition facts
for the literal federated sources, then reconciles the snapshot against the raw
parser/HOP graph. The test checks that every literal source maps to exactly one
federated HOP.

The privacy facts come from each case's `metadata` entry: `X` and the
`P2_PREP` source `Xraw` map to the protected X entry; `Y`, `y`, and SliceLine
error input `e` map to the Y entry. The test
does not load data, parse staged `.mtd` files, or verify worker federation
maps. The template tree's own `SHA256SUMS` is an internal consistency check,
not a separate trusted anchor. The result proves only that the selected
planning DML reaches a test-owned pre-builder structural snapshot under those
explicit assumptions. It does not prove plan legality, complete search-space
coverage, runtime execution, or reproducibility of an absent sibling tree.

Run the focused check from the SystemDS repository with
`mvn -q -Dtest=PlanningTemplateSnapshotSmokeTest test`. Record the exact
number of passed and skipped tests and the hashes of the two repositories in
the campaign evidence; a green skipped test is not evidence of template
coverage.

On 2026-09-21 with the staged sibling tree present, the corrected focused Maven run
reported **56/56 captured**, with JUnit `tests=1, failures=0, errors=0,
skipped=0`. In 24 templates, the compiler's `FunctionOp`
provided a null input-name or output-name array; there were 28 such calls in
total. The snapshot and raw-graph
verifier preserve this as unknown metadata; the structural check passes, but
these missing names cannot support a complete call-boundary legality claim.
