# FrameScalar federated planning-space 감사 보고서

## 결론

`TernaryFrameScalarFEDInstruction`의 frame `_map`은 runtime에서 항상 입력 federation map을 복제해 새 remote frame을 만든다. 그러나 CP/SP instruction conversion은 `FederatedOutput.NONE`을 기록하여 planner의 `FED/FOUT/{ROW,COL}` 선택과 lowering audit의 실제 물리 상태가 불일치했다. conversion과 constructor를 실제 runtime 의미인 `FOUT`으로 정규화한 뒤 ROW/COL discovery 및 CP/FED 강제 실행을 검증했다.

수정 후 direct manifest 4개 상태가 모두 privacy/physical constraint를 만족하고 성공했다. exact union은 4/4이며 duplicate, missing, unexpected target은 모두 0이다.

## 발견한 결함과 최소 수정

- 파일: `src/main/java/org/apache/sysds/runtime/instructions/fed/TernaryFrameScalarFEDInstruction.java`
- 기존 동작: CP/SP conversion이 `FederatedOutput.NONE` 전달
- 실제 동작: `processInstruction`이 `fedMap.copyWithNewID(...)`를 output frame에 설정
- 증상: audit에서 planned `FED/FOUT/ROW` 대비 actual `FED/NONE` lowering mismatch
- 수정: CP/SP conversion은 `FOUT` 전달; constructor는 legacy/null `NONE`도 `FOUT`으로 정규화

수정 후 audited discovery에서 ROW와 COL `_map` 모두 planned physical `FED/FOUT/{ROW,COL}`와 actual `FED/FOUT`가 `MATCH`이며 runtime execution도 정확한 FType으로 일치했다.

## 강제 실행 범위

- fixture: `FederatedFrameScalarLayoutPlanningTest`
- opcode: `t(_map)` / runtime `_map`
- input layouts: `ROW`, `COL`
- states: 각 layout의 `CP/LOUT`, `FED/FOUT`
- targets: 4 unique
- privacy: `PUBLIC`
- `TARGETS_PER_JVM=1`
- servers: so003--so006, 각 1 target
- source receipt SHA-256: `6fb9b7d82d3cc371b91dc28615c4eb99c5610d1e39ef9e24a9258f0e05807502`

## 검증 결과

`authoritative-aggregate/TARGET_UNION_VALIDATION.json`:

- manifest rows / unique: `4 / 4`
- authoritative rows / unique: `4 / 4`
- `SUCCESS`: 4
- `constraintSatisfied=true`: 4
- `PUBLISHED_LEGAL_EXECUTED`: 4
- duplicate / missing / unexpected: `0 / 0 / 0`
- validation: `PASS`

네 source-local target directory는 모두 symlink가 아닌 물리 directory이며 서로 다른 inode를 사용한다. 동일 source receipt를 사용했고 `INFRASTRUCTURE_VALIDATION.json`은 `PASS`이다.

attempt-local `P`/runtime join은 runtime success 20건, exact planned target-in-P 12건, confirmed missing 0건, selected/runtime input divergence 0건이다. 이 비교 역시 관측된 runtime witness만 대상으로 하므로 `coverageComplete=false`이다.

## 증거 위치

- fixed discovery: `audit-results/fed-runtime-frame-scalar-e2e-fixed-20260831T205022Z/`
- forced campaign: `audit-results/frame-scalar-auth-20260831T205400Z/`
- exact union: `authoritative-aggregate/TARGET_UNION_VALIDATION.json`
- authoritative summary: `authoritative-aggregate/SUMMARY.json`
- attempt-local comparison: `attempt-runtime-comparison/summary.json`
- infrastructure: `INFRASTRUCTURE_VALIDATION.json`
- evidence checksums: `EVIDENCE_SHA256SUMS.txt`

## 한계

본 결과는 fixture가 노출한 PUBLIC ROW/COL direct states에 한정된다. `confirmedMissing=0`은 관측된 successful instruction에 대한 진술이며 모든 frame schema, privacy level, map UDF 및 runtime conversion을 완전 열거했다는 의미가 아니다.
