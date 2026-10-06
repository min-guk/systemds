# L2SVM W3 plan-cost increase audit

## Conclusion

The observed increase follows the branch-exit normalization changing the current
hard-factor graph, rather than a changed cost environment. Under the same current
cost model and Docker image, the normalized graph selects four internal `Y`
occurrences as `CP/LOUT` and costs `9014.97937972805` ms. Removing only the
normalizer call restores the 239-node graph, selects those occurrences as
`FED/FOUT/ROW`, and costs `7198.293375716696` ms. The increase is
`1816.6860040113552` ms (`25.2377%`).

The no-normalizer result differs from the historical objective
`7198.293365972626` ms by only `0.00000974407` ms, attributable to relocated
fixture paths/scaffolding. Its graph size, state counts, and the four Y states
match the historical result exactly.

## Current-model feasibility test

The four changed occurrences are L2SVM source locations `76:4` (TWrite),
`91:19`, `109:38`, and `124:28` (TRead). Their current domains expose
`FED/FOUT/ROW`; this state was not removed from the individual domains.

Holding all other current selections fixed, the audit enumerated every
FED/ROW alternative combination for these four domains: `2 * 7 * 2 * 2 = 56`.
None satisfies all current hard factors. This proves only that changing these
four domains while leaving every other current choice fixed is insufficient.
The rejecting factors identify the missing coupling directly. The most frequent
violations connect the new line-75 carrier TWrite to the Y reads at lines 91,
109, and 124 (336 incidences each), followed by the line-76 branch write to the
line-75 carrier read (168 incidences). Reverse carrier/read constraints account
for another 112 incidences per downstream read. These are carrier alias/state
constraints, rather than privacy exclusions or removed FED alternatives.

The stronger audit maps and pins all 191 semantically matching coarse states
from the no-normalizer plan. The four new carrier domains and all auxiliary
alias/physical variables remain free. This exact conditional solve succeeds:
the old coarse plan is extendable, and all four new carriers select
`FED/FOUT/ROW`. Its optimum under those 191 pins is
`27412.4503941931` ms (`hardCost=0`, lower=upper, stop=`EXACT`). It evaluates
49,207 assignments and retains 54,894 slots.

The exact old-coarse completion is `18397.47101446505` ms above the current
selected plan and `20214.157018476406` ms above the no-normalizer plan. Its
largest contribution changes versus the current selected plan are:

- `+18210.952274164727` ms at the line-109 Y/mult/`1-*` group.
- `+1821.0952274164724` ms at the line-124 Y/multiply/`1-*` group.
- `-1816.4701616620732` ms at the line-120 max/matrix-multiply group.
- `+806.4773516620733` and `-806.4773516620733` ms in paired line-120/124
  realization factors.
- `+182.10952274164725` ms at the line-91 Y/transpose group.

This is not the GMM unknown-shape failure mode. The affected Y and carrier
states are shape-independent and the source geometry remains `50000 x 1`.
The dominant new charge is a generic physical-realization contribution around
line 109's `sum(out * Y * Xd)` inside the nested `30 x 20` iteration structure;
the next charge is around line 124's `out * Y` in the outer loop. The new
carrier equality constraints force all four carrier/read/write states to FED in
the old-coarse completion, which changes these repeatedly weighted operator
realizations. Carrier nodes themselves do not account for the `18210.952` ms
term as standalone execution nodes.

An earlier four-domain-only constrained heuristic found a different feasible
completion at `27250.667661286137` ms but stopped at a resource bound. It is not
used as an optimum or lower bound; the 191-pin exact solve above is the
authoritative extension result.

The production current search starts from `13255.506659655119` ms, makes one
improvement, and returns `9014.97937972805` ms. Its final checkpoint is
`RESOURCE`, with 2,121 merges, 9,693,308 assignments, 7,954,842 retained slots,
and lower bound `3358.0472706272108`. Thus this audit does not claim global
optimality. It does establish that the old cheap placement is not a complete
physical realization of the new normalized graph: preserving every matched old
coarse state forces an exact minimum of `27412.4503941931` ms after the four
carriers and their coupled physical choices are added. Because the unrestricted
current search retains a lower bound below the historical objective, this audit
does not prove that every current feasible plan must exceed `7198.293375716696`
ms; a remaining unrestricted search miss is not ruled out.

## Evidence

- Current normalized run: `l2svm/results/A/l2svm_w3.json` and `.log`.
- No-normalizer current-model run:
  `l2svm-oldgraph/results/A/l2svm_w3.json` and `.log`.
- Exact Docker invocations: the adjacent `.command.json` files.
- Isolated source overlay: `l2svm/src/.../LocalPhysicalOptimizer.java` and
  `RegionalSearchProblem.java`.
- No-normalizer overlay: `l2svm-oldgraph/noop-src/.../DMLTranslator.java`.

Every experiment used `scripts/fedplanner/run_LAN_docker.sh
--function-boundary-compare` and the pinned image. No production source, test
source, shared Maven output, or planner jar was modified.
