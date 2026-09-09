# Session Issues — 2026-08-31

## MapMM/SPARK planner placement was overwritten or obscured by Spark lowering

- **Status**: complete
- **Applied principle**: compiler platform selection may choose a default for an unplanned Hop, but it must not overwrite the exact CP/FED and LOUT/FOUT state already selected by the federated planner. Logical opcode-family equivalence does not imply placement equivalence.
- **Observed symptom**: the 12 MapMM/SPARK published states could not be used as strict forced-state evidence because global Spark could replace planner-selected execution placement and attach a checkpoint; the existing small fixture also normally lowered to generic `AggregateBinaryFEDInstruction` instead of exercising specialized `MMFEDInstruction`.
- **Root cause**: the global Spark platform override ran after planner placement selection, while runtime audit recognized only `tsmm` as an aggregate-binary specialized opcode. This mixed two independent questions: whether a logical matrix multiply has a specialized physical opcode, and whether the selected physical placement was preserved.
- **Resolution**: preserve planner-selected CP/FED choices across global Spark platform selection; suppress a Spark checkpoint for planner-selected FOUT/FederationMap-backed results; admit the closed aggregate-binary opcode family (`tsmm`, `mapmm`, `cpmm`, `rmm`) only after exact placement checks; add a sparse/large runtime-planner fixture that independently forces `fed_mapmm`.
- **Verification**: placement authority tests 5/5 PASS; runtime audit tests 72/72 PASS; sparse/large MMFED fixture PASS with `fed_mapmm`; clean-freshness two-host forced campaign 12/12 SUCCESS with exact constraints satisfied, zero checkpoint mentions, and aggregate validation PASS.
- **Artifacts**: `audit-results/mmfed-clean-20260831T220202Z`; manifest SHA-256 `43f3f8d5c4d761350243e1486ebe124c247114181a2dbf6718b15c3127c97f50`; aggregate summary SHA-256 `48172b751b585bc2b1d7d4c95061d48109b536dde03a297ed5c19b985b4f4b71`.
- **Evidence boundary**: the campaign proves soundness of the 12 published MapMM states, and the large sparse fixture proves one representative specialized MMFED runtime path. It does not exhaustively enumerate every runtime-supported shape/size combination; `coverageComplete=false` and global Missing=0 is not claimed.
