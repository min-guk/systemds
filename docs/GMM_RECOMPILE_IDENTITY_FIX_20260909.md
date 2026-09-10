# GMM dynamic-recompile identity projection repair

## Failure

The DP runtime for GMM failed while re-projecting a planner-selected LOCAL
materialization in `compute_log_det_cholesky`. Two distinct return-write
occurrences had different Hop IDs but the same class, opcode, and source
position. Runtime recompilation preserved both exact numeric IDs, while both
Hops shared an earlier planner-origin ID. The runtime index therefore found a
two-member recompile-signature group and rejected it before considering the
unique exact-ID member.

This was an identity-projection defect, not an infeasible planner selection:
both occurrences had the same selected `CP/LOUT` state and were exact
consumers of the same planner-owned LOCAL materialization.

## Repair

`FederatedRefedPolicy.resolveRuntimeHop` now resolves a repeated-signature
group only when `RuntimeHopIndex.byId` contains a unique Hop for the selected
planner ID and that exact object is a member of the signature group. Object
identity is used deliberately so Hop equality cannot conflate occurrences.

All other collisions remain fail-closed:

- no member with the selected ID;
- a same-ID Hop with a different current signature; or
- duplicate runtime Hops carrying the selected numeric ID.

The repair does not choose the first signature match, make equal placements
interchangeable, expand the placement domain, relax privacy or feasibility,
or introduce runtime fallback/repair.

## Regression contract

`FederatedRefedPolicyTest` covers:

1. two same-signature, distinct-ID GMM-style return roots resolving to their
   respective exact objects;
2. restoration of both exact consumer-input obligations for one shared LOCAL
   materialization; and
3. the same authority projection through first- and second-generation
   runtime-recompile deep copies; and
4. fail-closed behavior for missing, wrong-signature, and duplicate exact IDs.

The focused test was first run against the pre-fix implementation and failed
with `exact planner action has ambiguous recompile signature`; the three
negative controls passed. Build, green tests, and the four-planner Docker GMM
campaign are performed by the experiment owner after this source-only patch.

The initial exact-ID repair exposed a second loss point in integration: a
deep-copy clone retained only the older shared planner origin (`1669`) rather
than the distinct action occurrence (`2131` or `2136`). The multi-generation
regression above is therefore required before the repair is considered
complete; merely accepting an exact ID in the pre-copy DAG is insufficient.
The identity binding belongs only to the private runtime-recompile copy path:
ordinary `deepCopyHopsDag` calls retain their existing ancestry semantics.
Unregistered roots, registered roots whose current signature differs from the
registered source signature, lowering auxiliaries, and rewrite replacements
must likewise retain their prior/owner ancestry rather than manufacturing a
new exact action identity.
