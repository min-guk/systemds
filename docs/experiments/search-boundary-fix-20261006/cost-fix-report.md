# L2SVM branch-carrier cost fix

## Root cause and change

Branch-exit normalization can give one logical matrix several mutually exclusive reaching writers. `ExactPhysicalCostModel` previously kept the original `MatrixObject` creation lifetime only for a single reaching writer. With multiple writers it fell back to the downstream `TRead` loop profile, so a reusable GET was charged at nested-loop frequency.

The fix in `ExactPhysicalCostModel.java` separates two facts:

- the cache-owning object's creation lifetime;
- each transparent carrier's branch reachability guards.

Download observations now retain every guarded `(creation, activation)` pair instead of overwriting observations that share one origin. Independent non-repeated guards multiply their probabilities. Mutually exclusive arms union to one event. Opposite arms of a repeated decision remain possible across iterations and therefore are not treated as contradictory. Multi-origin alias costing is enabled only when every source has complete branch reachability evidence, preserving the legacy fallback for unresolved loop-phi sources.

The focused regression suite is `TransparentCarrierCostAuthorityTest.java`. It covers empty-else pass-through, two pass-through arms with one origin, two fresh values, nested and sequential guards, creation inside a branch lifetime, and opposite arms across repeated iterations.

## Verification

- Isolated current-source JUnit run: `TransparentCarrierCostAuthorityTest`, `ExactCompiledMaterializationScopeTest`, `ExactAliasGetCoalescingTest`, and `ExactActivationMaterializationCostTest` -> `OK (24 tests)`.
- Final frozen engine Docker L2SVM W3 run -> PASS, objective `7289.332817889578`, selected `LOCAL=6`, `REFED=1`.
- Exact 191-historical-state conditional completion after the fix -> `7289.34813708752`, `hardCost=0`, exact lower=upper. Before the fix the same completion cost `27412.4503941931`; the erroneous line-109 and line-124 loop-multiplied GET charges are gone.

Evidence:

- normalized audit: `/home/mchoi/fedplanner-search-boundary-fix-20261006/l2-audit/results/A/l2svm_w3.log`
- same-engine no-normalizer audit: `/home/mchoi/fedplanner-search-boundary-fix-20261006/l2-oldgraph-audit/results/A/l2svm_w3.log`
- detailed audit: `/home/mchoi/fedplanner-plan-increase-20261006/l2svm/REPORT.md`

## Remaining 91 ms is a real action

The fixed normalized plan remains about `91.03944` ms above the no-normalizer cost (`7198.293381563138`). This is one post-join reusable GET, not the old loop overcharge:

- the normalized graph joins the true-arm value from line 76 and the false-arm pass-through from line 75;
- their branch weights are `0.5 + 0.5`, so the GET executes once;
- the modeled unit GET cost is `91.05476137082363` ms;
- the old compiled runtime listing contains `CP prefetch Y ... _mVar37` immediately after the branch and before the line-91 transpose (`l2-oldgraph-audit/results/A/l2svm_w3.log:109`).

The old no-normalizer cost graph omitted that physical post-join prefetch because it had no explicit join authorities. Other changed branch contributions cancel to within `0.015317249127292598` ms. Removing the remaining GET charge would recreate an undercount for an instruction that the runtime actually emits.
