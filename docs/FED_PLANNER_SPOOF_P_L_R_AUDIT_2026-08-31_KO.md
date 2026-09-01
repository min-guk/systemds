# Spoof fused operator의 planner/runtime 공간 감사

## 결론

Compiled federated planner가 codegen 이전 HOP만 보던 단계 순서 문제를 수정하여, 공통
`PlacementAnalysis`가 fused `SpoofFusedOp`를 직접 분석하도록 만들었다. ROW federated
입력을 사용하는 cellwise fixture에서 공개된 세 상태
`CP/FOUT/ROW`, `CP/LOUT`, `FED/FOUT/ROW`를 독립 JVM으로 강제 실행했으며 모두 성공했다.

Authoritative 결과는 다음과 같다.

- manifest target: 3
- result / unique result: 3 / 3
- duplicate / missing / unexpected: 0 / 0 / 0
- constraint-satisfied: 3 / 3
- result outcome: `SUCCESS` 3 / 3
- runtime-capability outcome: `SUCCESS` 8 / 8
- final classification: `PUBLISHED_LEGAL_EXECUTED` 3 / 3
- unresolved: 0

따라서 이 fixture에서 공개된 P 상태에 대한 spurious witness는 없다. Attempt-local
runtime join에서도 confirmed missing witness와 selected/runtime input divergence는 각각
0이었다. 다만 runtime의 모든 가능한 상태를 P와 독립적으로 열거한 것은 아니므로
`coverageComplete=false`이며, 전역적인 `P = R intersect L`을 주장하지 않는다.

## 발견한 구현 결함과 수정

1. **Codegen phase ordering.** 기존 compiled planner는 runtime codegen보다 먼저 실행되어
   fused Spoof occurrence를 볼 수 없었다. Compiled planner가 활성화된 경우 HOPS codegen을
   최종 PlacementAnalysis/planner 전에 수행하고, 이후 runtime program 및 partial program
   recompiler에서 같은 codegen을 중복 수행하지 않도록 했다.
2. **FED parser dispatch.** `FEDInstructionParser`에 `SpoofFused` dispatch가 없어 fused FED
   instruction parsing이 실패했다. 이를 `SpoofFEDInstruction`으로 연결했다.
3. **Hop/Lop/output contract.** Planner가 선택한 FOUT/LOUT가 `SpoofFused` instruction에
   직렬화되지 않아 실제 물리 출력이 `FED/NONE`으로 보였고 runtime heuristic이 planner
   선택을 무시했다. Hop에서 Lop으로 output contract를 전달하고 FED instruction에 flag를
   직렬화/파싱했으며, runtime이 강제 LOUT/FOUT를 실행하도록 했다.
4. **Opcode identity.** HOP은 `spoof(TMP6)`, coordinator instruction은
   `spoofCellTMP6`, worker request는 `spoof`로 표현한다. 임의 prefix 허용 대신
   `Cell`, `RA`, `MA`, `OP` 네 template family와 동일 generated class identity만 허용하는
   폐쇄형 lowering/worker-fragment 대응을 추가했다.
5. **PRead scratch provenance.** Codegen compiler scratch copy가 원본 PRead provenance를
   물려받는 경우를 별도의 lifecycle auxiliary로 구분했다. configured scratch root 아래의
   exact compiler-copy shape만 허용하며, 실제 lazy PRead descriptor는 여전히 원본 path와
   format을 일치시켜야 한다. 외부 path나 다른 reserved symbol은 계속 fail-closed이다.

## Fixture와 공개 공간

- Java: `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedSpoofPlanningSpaceTest.java`
- DML: 기존 `FederatedCellwiseTmplTest.dml`의 `test_num=4`
- Config: `src/test/scripts/functions/privacy/fedplanning/SystemDS-config-codegen-fedall.xml`
- input signature: `PRESENT:ROW`
- opcode: `spoof(TMP6)`
- published P:
  - `CP/FOUT/ROW`
  - `CP/LOUT`
  - `FED/FOUT/ROW`

일반 실행에서는 `FED/FOUT/ROW`가 선택되며 `fed_spoofCellTMP6`가 실행되고 output federation
map을 유지한다. Forced audit에서는 CP 상태가 의도적으로 선택될 수 있으므로, fixture의
일반 실행용 federated heavy-hitter assertion은 forced audit 동안 적용하지 않는다. 수치
결과 비교와 planner/runtime audit은 모든 강제 상태에서 그대로 수행한다.

## Authoritative 실행 환경과 evidence

세 target을 so003--so005에서 동시에 실행했고 so001은 사용하지 않았다. 각 서버는 서로
다른 source-local physical `target` directory를 사용했다.

| host | target device | target inode |
|---|---:|---:|
| so003 | 2050 | 20060202 |
| so004 | 2050 | 19408655 |
| so005 | 2050 | 8137744 |

- Campaign: `audit-results/spoof-auth-20260831T213123Z`
- Exact union: `audit-results/spoof-auth-20260831T213123Z/EXACT_UNION_VALIDATION.json`
- Aggregate: `audit-results/spoof-auth-20260831T213123Z/authoritative-aggregate/SUMMARY.json`
- Attempt-local P/R join: `audit-results/spoof-auth-20260831T213123Z/attempt-runtime-comparison/summary.json`
- Shards: `audit-results/spoof-auth-20260831T213123Z/shards-v2/{so003,so004,so005}`
- Manifest SHA-256: `9174108df6a05d9536c1cd99388647f0ea04595c52ea36427fe2867dea36f9af`
- Common source receipt SHA-256: `c1b1d2cbaa9d49c1378ce878ffc5ae8f954acbd5d37e9d5684505234707dc3ca`

초기 provisional shard에서 CP 상태 두 개가 triage로 분류된 이유는 runtime 실패가 아니라
fixture가 강제 CP 상태에서도 `fed_spoof` heavy hitter를 요구했기 때문이다. Forced-audit
guard를 추가한 뒤 동일 source receipt로 세 target을 모두 다시 실행한 v2 결과가 위의
authoritative evidence이며, provisional 결과를 대체한다.

## 회귀 검증

- so003 physical target: `PlannerRuntimePlacementAuditTest` -- PASS
- so004 physical target: `FederatedSpoofPlanningSpaceTest` -- PASS
- so005 physical target: `FederatedCellwiseTmplTest` -- PASS
- audited integrated run: planned/lowered/executed Spoof 모두 `FED/FOUT/ROW`, mismatch 0

