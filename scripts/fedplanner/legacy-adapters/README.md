# Historical native plan-space observers

`run_legacy_bridge.py` installs a test-only bridge into an isolated detached
worktree pinned to B0 (`ffb7be5bd85367156ed9ea86dacbaff4be0f035d`) or B1
(`d8fbd30b5476a1ceef460c9f3886381a369ac619`). It refuses a changed
production tree or POM. Only the bridge is copied into `src/test/java`, in the
same package as the historical package-private `ExactPhysicalModel`.
Both bridges use their version's fixture hook to register
`PRIVATE_AGGREGATE` for federated sources before analysis, matching the current
protected P/E fixture input. Each row and receipt records this privacy input;
the wrapper rejects a mismatch.

The bridge observes each version's original native domain and predicates:
P enumerates placement, candidate receipt, and relocation receipt choices and
uses that version's own production validators. E enumerates model alternatives
and tests all that version's hard factors. The B0 bridge uses the singular
`canonicalCandidateReceipt` API and does not call B1's realization validator.
The B1 bridge includes realization and support fields and calls its own
realization validator. Neither bridge imports the current checkout's planner
implementation.

Example (after creating the pinned worktree on a volume with enough space):

```bash
python3 scripts/fedplanner/legacy-adapters/run_legacy_bridge.py \
  --version B0 --worktree /grid/3/.../B0 --fixture B-01 --source E \
  --start 0 --stop 1 --rows /grid/3/.../B0-E-B01.jsonl \
  --receipt /grid/3/.../B0-E-B01.receipt.json
```

For a frozen real DML cell, use an identifier outside the `B-*` shadow corpus
and pass its absolute source path:

```bash
python3 scripts/fedplanner/legacy-adapters/run_legacy_bridge.py \
  --version B0 --worktree /grid/3/.../B0 --fixture lm-w3-lan \
  --dml /grid/3/.../input_templates/w3/programs/lm.dml \
  --source E --start 0 --stop 1 --rows /grid/3/.../B0-E-lm-w3.jsonl \
  --receipt /grid/3/.../B0-E-lm-w3.receipt.json
```

The pinned revision's own parser and translator compile the DML with the same
live-variable, validation, HOP-construction, and rewrite sequence as its
shadow fixture factory. Its historical builder and validators still determine
the native domain. The wrapper hashes the DML before and after capture; Java
checks that hash before compilation and records its path and hash in the source
catalog and receipt. The external DML's dependency bytes (for example builtin
functions) are supplied by the pinned worktree; the DML hash alone is not a
whole-program source hash. The receipt pins the historical commit and bridge
template hash. Compilation is an original-version IR observation, so a changed
set of compiled HOPs across revisions is a result to analyze, not a mapping
that this bridge silently normalizes.

Real LM w3 LAN compiles on both pinned revisions, but the row stream does not
prove self-equality. Its native raw E products are 7,864,320 (B0) and
45,121,536 (B1), while P products exceed 2.4 × 10^45 (B0) and
5.4 × 10^47 (B1). Both source catalogs have 127 compiled nodes and 45
ordered physical inputs, but B0 exposes 4 logical inputs and 188 graph
constraints versus B1's 25 and 209. Prefix receipts
remain `PARTIAL`, and `physicalDecode=LEGACY_REPRESENTATION_LIMIT`: the
source-catalog coordinates and native rows have no proven lossless physical
decoder for the full real-DML graph. In particular, ordinal-zero rejection
does not imply absence of feasible plans. Use an exact relation representation
and a proven native-to-physical map before claiming P/E self-equality or
cross-version addition/deletion for this cell.

Every row carries exact ordinal, native `EMITTED`/`REJECTED`/`ERROR` status,
raw product size, and nested structural records for choices, actions,
authorities, ordered inputs, and receipts. The wrapper verifies contiguous
ordinals, provenance, and output checksum. An empty range cannot prove the
model size and stays `PARTIAL`. The receipt's `physicalDecode` is deliberately
`LEGACY_REPRESENTATION_LIMIT`: the shared source-based physical identity and
native-to-physical bijection have not been established by these observers.
Each invocation now also saves a `*.receipt.json.catalog.json` source catalog
from that revision's original `PlacementAnalysis`, before either native row
projection. It records compiled occurrences, value versions, original HOP
locations and operations, ordered physical input edges, logical transient and
function inputs, placement alternatives, anchors, and graph constraints. The
wrapper checks catalog provenance, its checksum, unique occurrence owners,
resolved input endpoints, and row-to-catalog occurrence coverage. Its path and
SHA-256 are pinned in the receipt. This captures source coordinates that the
old row stream alone could not reconstruct.

`verify_b01_local.py` is a deliberately narrow physical decoder for B-01 only.
It requires every accepted choice to be CP/LOUT with absent local inputs, no
anchor, relocation, derived FOUT or logical input, and complete P candidate
receipts. It computes a physical set from the version's source catalog and
checks E-minus-P and P-minus-E. It fails if a nonlocal coordinate appears.
On the pinned B0 and B1 B-01 fixture, each E raw space has 1/1 accepted and
each P space has 1/256 accepted, with one common physical key and zero
bidirectional difference. These results do not establish the general historical
physical decoder or an actual planning-DML comparison.
Use a `.jsonl.gz` rows path for streaming gzip output on larger shards.
`nativeCoverage=COMPLETE` means only that the specified native raw product was
fully visited without native errors; it must not be used as full physical
comparison success.

Do not edit the baseline `src/main` files to make a comparison pass. If an old
compiler or native validator fails, preserve the native diagnostic and keep
the corresponding comparison non-PASS. Large products require the separate
exact relation engine; this bridge's row stream is the no-prune reference and
supports finite half-open shards.
