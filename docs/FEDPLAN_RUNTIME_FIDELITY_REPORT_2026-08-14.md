# FED planner 계획→runtime 전달 충실성 감사 보고서

- **작성일**: 2026-08-14 (Europe/Berlin)
- **현재 source**: `/home/mchoi/g014-planning-audit-source-20260810-v1`
- **범위**: 공통 placement 분석, DP/FedAll/Heuristic/MinST 선택, Hop 변이, Lop/instruction lowering, dynamic recompile, coordinator/worker runtime 실행
- **핵심 제약**: runtime fallback/repair 금지, TRead/TWrite는 `<CP,LOUT>` 또는 `<FED,FOUT>`만 허용, recompile에서 `<CP,FOUT>` 금지
- **현재 판정**: 함수 formal exit→caller TRead, qualified namespace, recursive output alias, graph-only exact relocation까지 포함한 공통 분석·선택의 source-level closure와 네 플래너의 최신 순차 회귀 및 28-case production certificate는 통과했다. 최신 candidate는 clean snapshot commit `cd23d21db46dae0227f6f4d948d78b8e394143d0`, JAR `f073be1…`, immutable stage `ba7a584d…`로 새로 봉인됐고 stage-local replay도 통과했다. 이 authority의 fresh 336-cell Docker runtime 전수 검증은 현재 실행 중이며 아직 완료되지 않았다.

## 1. 결론: 하나의 버그가 아니라 “물리 계획 authority가 단계 사이에서 분할된 구조”가 원인이었다

이전에는 플래너가 선택한 `exec/output/FType` 계획이 하나의 불변 authority로 런타임까지 전달되지 않았다. 다음의 네 가지 실패 모드가 섞여 있었다.

1. **선택 전 분석 불일치**: 각 플래너가 다른 occurrence, function/CFG, anchor, shape 사실을 보거나 분석 결과를 재파생했다.
2. **선택→emission identity 유실**: Hop ID, 변수명, placement tuple만으로 일치시켜 raw/recompile clone, 함수 호출별 occurrence, native/derived FOUT을 구분하지 못했다.
3. **emission→lowering 경계 소실**: MMChain, ternary aggregate, double-transpose 같은 Lop 최적화가 planner-selected FOUT/REFED/LOCAL 경계를 합치거나 삭제했다.
4. **lowering→runtime authority 수명 유실**: statement-block compilation, dynamic recompile, `rmvar`에서 mutable registry나 살아있는 anchor 변수에 의존한 정보가 사라졌고, runtime이 다시 추론하려 했다.

즉 “planner log에는 있지만 runtime에는 없는” 경우와, “planner 내부에서부터 잘못 연결된” 경우, “runtime은 다르게 실행했는데 기존 감사가 못 잡은” 경우를 구분해야 한다.

## 2. 원인별 근거와 해결

| 원인 | 구체적 증상 | 해결 방식 | 주요 코드/문서 |
|---|---|---|---|
| 플래너별 분석 universe 분리 | 동일 workload에서 planner별 occurrence/함수 경계/shape 해석이 달라짐 | 최종 Hop boundary에서 `NeutralPlacementGraphBuilder` 하나가 `PlacementAnalysis`를 생성하고 네 플래너에 **동일 객체 identity**로 공급 | `DMLTranslator.java:340-427`, `NeutralPlacementGraphBuilder.java:197-451` |
| Hop ID/값 기반 대조 | raw/recompile이 같은 Hop ID를 공유하거나 동일 placement tuple의 native/derived FOUT이 혼동 | `CompiledHopKey`, `ValueVersionKey`, exact object identity, candidate/action key, recompile signature를 결합 | `PlacementIdentity.java`, `PlacementAnalysis.java`; `docs/SESSION_ISSUES_2026-08-13.md` #13, #15, #16 |
| CFG/함수 전역 제약 누락 | multi-write TRead가 FED/FOUT symbol을 CP/LOUT로 읽거나 actual/formal의 전송 비용·연산이 누락 | TRead/TWrite는 exact `SAME_PLACEMENT`, function actual/formal은 명시적 local materialization/placement 경계로 모델링 | `NeutralPlacementGraphBuilder.java:2250-2268`, `NeutralPlacementGraph.java:462-490`; `docs/SESSION_ISSUES_2026-08-13.md` #4 |
| DML 함수 output identity 누락 | PCA의 `Xout`/`Mout` 등 positional output이 aggregate `FunctionOp` 하나의 placement를 복제하고 caller TRead는 과거 CP-only 후보를 유지 | 각 formal exit의 exact reaching value를 `SAME_VALUE_PLACEMENT`로 alias하고, synthetic output→caller TRead 후보·anchor·descendant action을 bounded fixed point로 재전파 | `NeutralPlacementGraphBuilder.java`, `NeutralPlacementGraph.java`; `docs/SESSION_ISSUES_2026-08-13.md` #33, #34 |
| function namespace/recursive alias 누락 | `a::f`와 `b::f` exit가 충돌하고 recursive output boundary가 topological expansion에서 정지 | qualified CFG key를 사용하고 unresolved recursive set을 유한 call-carrier domain으로 동시에 seed한 뒤 exact value equation 고정점으로 교집합 | `NeutralPlacementGraphBuilder.java`; `docs/SESSION_ISSUES_2026-08-13.md` #38 |
| rowless synthetic graph의 authority 혼동 | candidate row가 없는 exact fixture에서 FedAll/Heuristic relocation demand가 빈 집합으로 투영 | candidate row가 있을 때만 row-aware projection을 사용하고, 없으면 graph-owned relocation obligation을 canonical authority로 유지 | `ExactPlacementSelector.java`; `docs/SESSION_ISSUES_2026-08-13.md` #37 |
| 후보 행과 output effect 혼동 | 같은 `FED/FOUT/FType`이라도 native FOUT과 `FED/LOUT→FOUT`의 물리 효과가 다름 | exact candidate row에 `PlacementEmissionState`, `derivedFoutAction`, ordered input authority를 붙여 보존 | `NeutralPlacementGraphBuilder.java`, `CandidateSelections.java`, `MinStExactPhysicalModel.java` |
| 선택된 경계를 fusion이 삭제 | LM inner FOUT이 MMChain으로 합쳐짐, L2SVM LOCAL input 경계가 ternary fusion으로 소실, double transpose에서 FOUT action 소실 | planner-selected direct FOUT/REFED/FOUT-materialize/LOCAL action을 **fusion 금지 물리 경계**로 취급 | `AggBinaryOp.java`, `ReorgOp.java`, `Dag.java`; `docs/SESSION_ISSUES_2026-08-10.md` #12, #17; `docs/SESSION_ISSUES_2026-08-13.md` #1 |
| registry/anchor 수명이 컴파일 구간보다 짧음 | KMeans runtime recompile이 exact REFED를 지우고 `VAR:` anchor를 재추론, `rmvar`로 anchor 변수 제거 | 선택된 REFED/FOUT/LOCAL action을 immutable `PlannerRuntimeActionRegistry.Snapshot`에 commit; live variable가 아닌 durable placement key/FederationMap metadata 사용 | `PlannerRuntimeActionRegistry.java`, `PlacementEmissionTransaction.java:125-179`, `Dag.java`; `docs/SESSION_ISSUES_2026-08-10.md` #22 |
| MinST projector가 선택 범위를 확장 | 하나의 selected relocation을 selected consumer가 아닌 compatible consumer 전체에 투영 | selected alternative의 ordered input authority에만 exact projection | `MinStExactPhysicalSelection.java`; `docs/SESSION_ISSUES_2026-08-10.md` #19 |
| DP occurrence/child carrier 충돌 | 자신–자식 recurrence는 올바르지만 raw/recompile 또는 disconnected root 결합에서 다른 occurrence arm을 선택 | DP의 local-cost 철학은 유지하고 exact occurrence/family 합법성 closure만 보강 | `FederatedPlannerDpFedCostBased.java`; `docs/SESSION_ISSUES_2026-08-13.md` #13, #14 |
| 감사가 fused/missing operation을 성공으로 인증 | ancestor instruction 하나가 placement만 같으면 planner-selected physical Hop이 없어도 통과 | materialization boundary는 `requiresOwnInstruction`; exact Hop/action token이 lowering·execution에 없으면 fail closed | `PlannerRuntimePlacementAudit.java:304-425,543-1080,1191-1320`; `docs/SESSION_ISSUES_2026-08-13.md` #5 |

## 3. 현재의 단일 authority 파이프라인

```text
final normalized Hop program
  └─ NeutralPlacementGraphBuilder.buildAnalysis(program)
       ├─ exact occurrence/value-version/function/CFG/recompile identity
       ├─ positional function formal-exit value alias + caller TRead fixed point
       ├─ legal placement states + exclusions + runtime capability
       ├─ ordered-input candidate rows
       ├─ relocation / derived-FOUT / local-materialization actions
       └─ immutable analysis fingerprint
             ↓ same object
       DP | FedAll | Heuristic | MinST
             ↓
       NormalizedPlannerResult
             ├─ total selected state map
             ├─ exact candidate receipts
             ├─ exact relocation/local/FOUT action keys
             └─ canonical plan fingerprint
             ↓ atomic prevalidation/commit
       PlacementEmissionTransaction
             ├─ Hop exec/output/FType writes
             ├─ lowering registries
             ├─ durable runtime action snapshot
             └─ runtime audit authority
             ↓
       Hop→Lop→Instruction lowering
             ↓
       coordinator preprocess/execution
             ↓ authority attached to requests
       federated worker fragment/UDF execution
```

`PlacementEmissionTransaction` 자체가 다음을 보장한다.

- 선택 authority와 plan hash를 mutation 전에 검증한다.
- Hop write, REFED/FOUT/LOCAL registry write, runtime action snapshot을 한 transaction으로 적용한다.
- 중간에 예외가 나면 Hop/registry/receipt를 전부 rollback한다.
- 동일 program에 다른 plan을 조용히 두 번 적용하지 않는다.

## 4. 검증 방법: log 문자열 비교가 아니라 단계별 exact proof

### 4.1 공통 분석 감사

- 동일 `(profile, workload, workers)`의 4개 planner가 동일 `analysisFingerprint`를 사용하는지 확인한다.
- graph node와 concrete Hop occurrence가 1:1 exact identity로 연결되는지 확인한다.
- candidate가 oracle의 exact ordered input row에 속하는지, 선택 state가 node-owned legal state인지 확인한다.
- TRead/TWrite, function boundary, recompile, anchor/FType 제약을 graph 단계에서 검증한다.

### 4.2 선택·emission 감사

- 모든 decision node에 정확히 하나의 selected emission state가 있어야 한다.
- candidate/relocation/local-materialization이 selected state와 exact object/structural authority로 일치해야 한다.
- `Emission-Summary` 내 `selectedFED`, `selectedFOUT`, `selectedDerivedFOUT`, relocation 및 registry write 수가 normalized result에서 재계산된 값과 같아야 한다.
- fallback/repair counter는 0이어야 한다.

### 4.3 lowering 감사

`PlannerRuntimePlacementAudit.verifyLowering(...)`가 planner occurrence/action을 실제 instruction에 붙인다.

- 일반 physical Hop: opcode, exec, output, FType, recompile signature를 대조한다.
- selected materialization boundary: 자신의 instruction이 없으면 `MISSING`으로 실패한다.
- synthetic action: `REFED`, `REFED_LOCAL`, `FOUT`, `LOCAL` 각각의 exact action token이 남아야 한다.
- 허용된 fusion/rewrite는 opcode 별 exact replacement rule로만 인정한다.

### 4.4 runtime 감사

- `ProgramBlock` 실행 직전: preprocess 후 instruction의 exec/output/opcode/action token을 대조한다.
- 실행 성공 후: 실제 output object의 FederationMap/FType까지 확인한다.
- FED request dispatch: 각 request가 이미 검증된 coordinator parent authority를 가져야 한다.
- worker fragment/UDF: request의 parent plan hash, opcode, physical placement를 worker에서 다시 검증한다.
- 성공 완료한 instruction만 execution count에 추가한다.

### 4.5 Docker cell 감사

각 runtime cell은 다음을 모두 만족해야 성능 결과에 포함한다.

1. `run_LAN_docker.sh`로만 실행
2. source/JAR/stage/data/reference digest 고정
3. planner receipt·emission·runtime audit summary 존재
4. `missingPhysicalHops=0`, `missingSynthetic=0`, `mismatches=0`
5. unplanned runtime instruction/request/worker fragment 0
6. runtime fallback/repair 0
7. semantic oracle 통과
8. cold/warm plan 및 output identity 통과

## 5. 현재 검증 증거와 한계

### 5.1 통과한 증거

- 이전 immutable planning-only stage에서 112/112 cell, audit error 0을 확인했다. 상세: `docs/PLANNING_ONLY_PROGRESS_REPORT_2026-08-11.md`.
- LM inner direct-FOUT 수정 후 Docker WAN-Light workers=2 FedAll cold/warm에서:
  - runtime-plan hash 동일
  - output hash 동일
  - `fed_ba+*` 91회, `fed_fed_fout` 1회, `fed_mmchain` 0회
  - semantic oracle 통과
  - 상세: `docs/SESSION_ISSUES_2026-08-13.md` #1.
- 현재 candidate의 ordered source regression:
  - DP 36 classes 통과: `/tmp/g014-dp-ordered-regression-v153.log`
  - FedAll 7 classes 통과: `/tmp/g014-fedall-ordered-regression-v154.log`
  - Heuristic 2 classes 통과: `/tmp/g014-heuristic-ordered-regression-fixed-v158.log`
  - FedAll LogReg exact selector가 수정 전 약 137.6초에서 수정 후 65.3–73.3초로 감소: `/tmp/g014-fedall-logreg-incremental-all-scorers-v151.log`, `/tmp/g014-exact-selector-lowering-bundle-v152.log`.
- MinST의 stale transient fixture는 현재 전역 규칙인 exact `SAME_PLACEMENT`와 일치하도록 수정했고, variable-elimination은 min-fill 하나가 아니라 separator cell/assignment/domain/degree를 함께 보는 deterministic exact portfolio로 교체했다. exact categorical/model 회귀가 통과했다.
- FedAll exact selector의 최근 의미 보존 최적화는 다음 focused 증거를 갖는다.
  - indexed candidate physical-effect가 canonical 구현과 512개 seeded complete assignment에서 일치: `/tmp/g014-candidate-effect-index-parity-v174.log`
  - exact selector 12 tests 통과: `/tmp/g014-exact-selector-effect-index-v175.log`
  - winning assignment에 대해서만 canonical receipt를 복원하도록 변경 후 통과: `/tmp/g014-exact-selector-deferred-canonical-v178.log`
  - relocation demand canonical rank index 후 통과: `/tmp/g014-exact-selector-demand-rank-v180.log`
  - candidate state-row 및 lower-bound action join index 후 통과: `/tmp/g014-exact-selector-bound-row-index-v184.log`
- 동일 legal state/objective/pruning을 유지하고 첫 incumbent 전의 동률 대안 방문 순서만 admissible physical-emission lower bound 순으로 바꾼 뒤, LogReg workers=1 exact search는 22.702초/376,216 prefix/1,467 leaf로 끝났고 filtered production certificate는 76.39초, RC=0이었다: `/tmp/g014-isolated-fedall-logreg-w1-v204.log`, `/tmp/g014-minst-tractability-logreg-w1-v205.log`.
- 함수-output 수정 전 candidate의 **7 workload × workers 1–4, 총 28-case exact production certificate**가 440.45초, RC=0으로 통과했다: `/tmp/g014-minst-tractability-28cases-v218.log`. 동일 common analysis에서 다음을 함께 검증한다.
  - MinST exact physical model/optimum
  - DP/FedAll/Heuristic baseline의 graph legality
  - MinST modeled cost가 세 baseline보다 크지 않음
  - normalized projection과 production emission receipt
  - source-reachable candidate/action coverage
- 최신 순차 planner regression은 모두 RC=0이다.
  - DP 36 classes: `/tmp/g014-ordered-dp-regression-v219.log`
  - FedAll 7 classes: `/tmp/g014-ordered-fedall-regression-v220.log`
  - Heuristic 2 classes: `/tmp/g014-ordered-heuristic-regression-v221.log`
  - MinST 21 classes 및 production-focused methods: `/tmp/g014-ordered-minst-regression-v222.log`, `/tmp/g014-minst-exact-candidate-reachability-v226.log`, `/tmp/g014-ordered-minst-production-focused-v227.log`
- 공통 placement/runtime-audit 26개 top-level test와 selector/lowering focused bundle도 RC=0이다: `/tmp/g014-common-placement-runtime-audit-regression-v228.log`, `/tmp/g014-selector-lowering-regression-v229.log`.
- 함수 output semantic gap을 고친 최신 candidate의 fresh source 회귀도 통과했다.
  - exact formal-exit output alias 및 caller TRead fixed point: `/tmp/g014-function-output-boundary-focused-v251.log`, 156 tests, RC=0
  - shared placement/core 전체: `/tmp/g014-placement-core-function-output-tread-v261.log`, 33 classes/200 tests, RC=0
  - DP/FedAll/Heuristic/MinST-short 순차 회귀: `/tmp/g014-ordered-dp-function-output-tread-v262.log`(119), `/tmp/g014-ordered-fedall-function-output-tread-v263.log`(7), `/tmp/g014-ordered-heuristic-function-output-tread-v264.log`(2), `/tmp/g014-ordered-minst-function-output-tread-v265.log`(80), 전부 RC=0
  - PCA 두 positional output의 exact formal-exit alias와 네 플래너 projection parity: `/tmp/g014-pca-function-output-all-planners-v257.log`, RC=0
  - 첫 전체 재실행 `/tmp/g014-minst-production-tractability-function-output-v266.log`는 KMeans에서 caller TRead exact-domain 부분집합 처리와 DP latent function-output legality repair 누락을 각각 드러냈다. 두 경계를 수정한 뒤 focused `/tmp/g014-function-output-tread-exact-domain-v267.log`, `/tmp/g014-kmeans-dp-output-boundary-repair-v268.log`, `/tmp/g014-kmeans-w2-production-certificate-v269.log`가 모두 RC=0이다.
  - DP fixed synthetic incident, rowless exact graph, qualified namespace 및 recursive alias 수정까지 포함한 최신 전체 production certificate는 `/tmp/g014-minst-production-synthetic-current-v298.log`에서 **8 JUnit methods 내부의 7 workload × workers 1–4 전수 28 case, failures=0/errors=0/skipped=0, 512.072 test-sec/520 wall-sec, RC=0**으로 통과했다. 로그 SHA-256은 `35bba66a9d6f8bd40729255e1e8c38c4d67eeb8df3314770cfa09478be91504d`이다.
  - 같은 최종 source의 순차 회귀는 shared placement/core 33 classes/200 tests(`/tmp/g014-placement-core-current-v296.log`), DP 36 classes/119 tests(`/tmp/g014-ordered-dp-current-v292.log`), FedAll 7(`/tmp/g014-ordered-fedall-current-v293.log`), Heuristic 2(`/tmp/g014-ordered-heuristic-current-v294.log`), MinST-short 21 classes/80 tests(`/tmp/g014-ordered-minst-short-current-v295.log`)가 모두 RC=0이다. DP의 skipped 4건은 지침대로 비활성화된 public-privacy 케이스이며 failure/error는 0이다.
  - 실제 lowering/runtime 전달 계약은 `FederatedPlannerFallbackIntegrationTest` 145 tests와 `FederatedDagExactRefedInputProjectionTest` 13 tests가 `/tmp/g014-function-output-runtime-lowering-current-v297.log`에서 failures/errors/skips 0, RC=0으로 통과했다.
  - 현재 변경에서만 발생했던 graph-only/namespace/recursive 17개 회귀는 `/tmp/g014-current-only-regressions-fixed-v290.log`에서 모두 통과했다. 광범위 guard의 남은 RED는 clean HEAD와 정확히 같은 228 tests/12 failures/26 errors/13 skipped이며 current-only bad class가 0임을 `/tmp/g014-broad-guard-baseline-comparison-v291.json`(SHA-256 `2a4ca00451bb510f1f77301ea90d51db84cb217d66752ce1e968298f5d06372c`)으로 분리했다.
- 위 closure 과정에서 발견한 두 DP 전달 결함도 후보/비용/DP local recurrence를 바꾸지 않고 수정했다.
  - synthetic function boundary에 미결 incident가 남았는데 과거 endpoint 선택이 memo key에서 사라지던 문제: `docs/SESSION_ISSUES_2026-08-13.md` #27, `/tmp/g014-dp-l2svm-w2-synthetic-memo-fix-v210.log`
  - foreign component의 동일한 local value boundary인 `CP/LOUT`과 `FED/LOUT`을 producer ExecType 차이로 거부하던 문제: 동 문서 #28, `/tmp/g014-dp-logreg-w4-foreign-local-boundary-fix-v214.log`
- Docker 실행 authority도 최종 source 위에서 새로 봉인했다.
  - active candidate 전체 12,764개 tracked/non-ignored 경로의 byte parity를 가진 clean snapshot commit: `cd23d21db46dae0227f6f4d948d78b8e394143d0`, tree `ae0ddbad33a7d3c329e24d8d4e3c49beed9c50fd`
  - parity receipt: `/tmp/g014-current-snapshot-parity-v300.json`, mismatch 0, SHA-256 `74790059fa1e43881312f8f7bbc9f7d9cbc4046d523a241ebc5e0bf12883a9d8`
  - package: `/tmp/g014-snapshot-package-current-v301.log`, RC=0, SHA-256 `952707e80a69151c2b19e4766f0a95100e6158e14c856876cfa405b457cd4e73`
  - JAR SHA-256: `f073be1ed7ae33b127699bf179a97db98c163787cd0a4c3af13ff2ffb93d4114`
  - immutable stage: `/home/mchoi/g014-planning-audit-stage-cd23d21-d712daf-20260814-v1/g007-stage-ba7a584dc7203909205434a70a1d57e59c662e8f40620d93975ce4ffb1310e3c`
  - harness commit: `d712daf82d3023f8f136bb8c348cc04521b72335`
  - stage descriptor SHA-256: `05c42455971d3ccffc881b42197a1a5cdf2355e4b6dc93669bebe6dc08496b1b`
  - stage create/replay byte identity 및 staged JAR hash 일치: `/tmp/g014-stage-create-current-v302.json`, `/tmp/g014-stage-replay-current-v303.json`, RC=0
- 새 authority의 첫 완결 block인 `KMeans/workers=1/WAN-Light` 네 planner가 모두 성공했다. 이는 아래 7절의 predecessor 수치를 재사용한 것이 아니라 output `/home/mchoi/g014-full-results-cd23d21-d712daf-20260814-v1`에서 fresh 실행한 row다.

  | planner | warm / cold runtime | FedPlanner | selected FED/FOUT | dispatch / worker fragments | runtime-plan SHA 앞 12자리 |
  |---|---:|---:|---:|---:|---|
  | DP | 85.403 / 91.368 s | 13.668200 s | 33 / 29 | 45 / 62 | `5073caa87b82` |
  | FedAll | 59.228 / 64.856 s | 3.326557 s | 50 / 46 | 74 / 94 | `76f97131fbbb` |
  | Heuristic | 94.019 / 98.221 s | 3.450128 s | 46 / 42 | 64 / 82 | `db5057a88ba3` |
  | MinST | 46.178 / 50.510 s | 1.568093 s | 24 / 19 | 38 / 50 | `68812ba049b8` |

  네 row 모두 semantic oracle, fallback=false, runtime scan, cold/warm identity, teardown-zero를 통과했다. 두 coordinator의 planned/lowered physical Hop은 140/140이고 mismatch/missing physical/missing synthetic은 모두 0이다. emission placement SHA와 normalized runtime-plan SHA도 네 planner가 모두 달라, 이 block에서는 planning 차이가 runtime에서 하나로 붕괴하지 않았다.

### 5.2 아직 완료되지 않은 증거

- 이전 `506368f…` JAR/stage는 이후 발견한 함수 output authority 결함을 포함하지 않으므로 모든 row를 역사적 진단 자료로 격리했다. 새 campaign은 predecessor row를 재사용하지 않고 `cd23d21…` authority에서 336개를 fresh 실행한다.
- 즉 source-level exact plan-space, 선택, normalized projection, emission/lowering 계약은 최신 candidate에서 닫혔지만, 실제 Docker coordinator/worker에서 다음 동적 사실은 아직 336/336으로 증명되지 않았다.
  - recompile generation별 plan hash와 runtime action snapshot 보존
  - coordinator instruction과 worker fragment의 exact occurrence/action receipt
  - 실제 output `FederationMap/FType`
  - semantic oracle, cold/warm identity, fallback/repair 0
- 따라서 현재 보고서는 구조적 원인·해결·source-level 검증과 새 실행 authority를 확정하지만, 최종 runtime 충실성 336/336은 아직 주장하지 않는다. `/home/mchoi/g014-full-results-cd23d21-d712daf-20260814-v1`의 첫 실행은 4/336 뒤 row 5의 실제 runtime 계약 위반을 검출해 fail-closed했다. 수정 authority에서는 이 4개 성공 row를 authenticated predecessor로만 건너뛰고 WAN-Light → WAN-Mid → LAN 순서를 계속한다.

## 6. 완료 판정 기준

다음을 모두 만족할 때만 “네 플래너의 계획이 의도대로 runtime에 전달되었다”고 판정한다.

- ordered regression: DP → FedAll → Heuristic → MinST 전부 통과
- immutable source/JAR/stage 생성
- WAN-Light → WAN-Mid → LAN, 336/336 Docker cell 성공
- 모든 cell의 exact planner→emission→lowering→coordinator→worker audit mismatch 0
- semantic oracle/fallback scan/cold-warm identity 통과
- 성공 cell만으로 runtime/compile graph 생성
- planner 철학에 따른 plan 차이와 runtime instruction/전송/실행시간 차이를 workload별로 설명

## 7. 격리된 이전 immutable Docker 전수감사 증거

이 절의 수치와 plan→runtime audit은 당시 stage 내부에서는 유효한 역사적 증거다. 그러나 stage가 함수 formal-output/caller-TRead 수정 이전 source이므로 현재 candidate의 최종 성능 근거로 합치지 않는다. 새 stage에서 planning hash가 동일한 KMeans cell만 predecessor로 재사용할 수 있고, PCA는 재사용하지 않는다.

### 7.1 이전 실행 authority

- clean source snapshot: 506368fee1c7da4b2c5dc94ce3db16ba91431daf
- source tree: d198e20f39b793b3da1b5435a7c91e51dd61ae9d
- harness commit: d712daf82d3023f8f136bb8c348cc04521b72335
- JAR SHA-256: 6d65034a9e40a2f21c36a03070da127a03cb2cac3517211457169a457c7a720f
- immutable stage: /home/mchoi/g014-planning-audit-stage-506368f-d712daf-20260814-v1/g007-stage-4b9bb12cf7030686407de0b4b0144301c547a2cc16970976f0a8786a0cead4f5
- stage replay: /tmp/g014-stage-replay-v237.json, RC=0
- harness one-pass/runtime-audit test: /tmp/g014-harness-audit-onepass-v238.log, 27/27 PASS

### 7.2 첫 runtime cell: DP / KMeans / worker=1 / WAN-Light

인증된 결과는 /home/mchoi/g014-full-results-506368f-d712daf-20260814-v1의 첫 row다.

| 항목 | cold | warm |
|---|---:|---:|
| SystemDS execution time | 91.862 s | 84.868 s |
| semantic oracle | ARI=1.0, SSE relative error=0 | ARI=1.0, SSE relative error=0 |
| fallback/error/timeout/resource-invalid | 모두 false | 모두 false |
| normalized runtime-plan SHA-256 | 5073caa87b823e9ade436c6dd004b7f160f7374e06d5b49638a8416782d86700 | 동일 |
| executed instruction kinds | 284 | 284 |
| FED dispatch kinds | 45 | 45 |

planner emission은 327 decisions, selectedFED=35, selectedFOUT=31, physical compiled Hop 317개를 기록했다. coordinator audit는 두 JVM에서 각각 다음을 증명했다.

- plannedPhysicalHops=140
- loweredPhysicalHops=140
- missingPhysicalHops=0
- plannedSynthetic=5
- missingSynthetic=0
- mismatches=0
- execution_lines=284
- dispatch_lines=45

worker audit도 worker log 하나에서 cold/warm 두 summary block과 최종 worker fragment kind 62개를 인증했다. 따라서 이 cell에서는 planner 결과가 emission에서 유실되거나 lowering에서 fusion으로 사라지거나 runtime에서 암묵적으로 보정된 증거가 없다.

두 fresh coordinator의 generation-bound plan authority hash는 각각 737511...와 a52f91...로 다르다. 이를 runtime plan 불일치로 숨기지 않고 audit row를 비교했다. 차이는 phase별 출력 경로를 갖는 PWrite 1개와 literal identity 4개뿐이며, FED dispatch multiset은 완전히 같고 normalized runtime plan 및 instruction fingerprint도 동일하다. 즉 각 JVM의 exact compile occurrence authority는 별도로 인증하되, 두 실행의 물리 plan 동치는 run-local 출력 identity를 제거한 runtime-plan SHA로 검증한다.

### 7.3 이전 캠페인 종료 상태

첫 continuation은 사용자 systemd manager가 현재 로그인 셸의 docker supplementary group을 보유하지 않아 두 번째 셀을 Docker create 전에 resource_invalid로 중단했다. 이 실패는 planner/runtime 실패가 아니며 결과에 포함하지 않는다. 실패 campaign은 /home/mchoi/g014-full-results-506368f-d712daf-20260814-v1, 실패 cell은 FedAll/KMeans/worker=1/WAN-Light이다.

그 continuation은 함수 output semantic gap이 발견된 시점에 중단했고 Docker orphan도 제거했다. 두 output에 합쳐 21/336 row(KMeans 16 + PCA 5)가 있으나 이후 source 의미가 바뀌었으므로 전부 역사적 진단 자료로 격리한다. 현재 실행 process는 이 predecessor가 아니라 `cd23d21…`/`ba7a584d…` authority의 fresh campaign이다.

### 7.4 두 번째 runtime cell: FedAll / KMeans / worker=1 / WAN-Light

predecessor continuation의 첫 실제 실행이 성공했다. 결과는 /home/mchoi/g014-full-results-506368f-d712daf-20260814-v2의 첫 row이며 combined progress는 2/336이다.

- warm execution: 59.737초 (동일 조건 DP 84.868초)
- cold execution: 66.888초
- FedPlanner compile phase: 3.113008초
- planner emission: 327 decisions, selectedFED=52, selectedFOUT=48, selectedDerivedFOUT=2
- FedAll policy: explicit relocation 0, local materialization 2, derived-FOUT materialization 2
- runtime: fed_fed_fout 51회, FED dispatch kinds 74, worker fragment kinds 94
- coordinator cold/warm: planned/lowered physical Hop 140/140, missing physical/synthetic 0, mismatch 0
- semantic oracle PASS, fallback=false, runtime scan clean, cold/warm normalized runtime plan 동일

DP는 selectedFED=35/FOUT=31, fed_fed_fout 0, FED dispatch kinds 45, worker fragment kinds 62였다. 따라서 이 workload에서 두 planner의 placement 차이는 실제 FED instruction·dispatch 차이로 전달됐고 실행시간도 달라졌다. 단 두 개 cell만으로 전체 workload 정렬을 일반화하지는 않는다.

### 7.5 KMeans / worker=1 / WAN-Light 네 플래너 교차검증

동일 immutable JAR, 데이터, network profile, worker 수에서 네 플래너가 모두 완료됐다. 아래 실행시간은 각 cell의 fresh coordinator JVM warm phase이며, compile 시간은 같은 warm coordinator 로그의 `Compile Phase FedPlanner`이다.

| planner | warm runtime | cold runtime | FedPlanner | selected FED/FOUT | runtime `fed_fout` 반복 | FED dispatch kinds | runtime-plan SHA 앞 12자리 |
|---|---:|---:|---:|---:|---:|---:|---|
| DP | 84.868 s | 91.862 s | 19.211210 s | 35 / 31 | 0 | 45 | `5073caa87b82` |
| FedAll | 59.737 s | 66.888 s | 3.113008 s | 52 / 48 | 51 | 74 | `76f97131fbbb` |
| Heuristic | 95.024 s | 100.232 s | 3.534370 s | 48 / 44 | 1 | 64 | `db5057a88ba3` |
| MinST | 46.028 s | 49.071 s | 1.948997 s | 24 / 19 | 0 | 38 | `68812ba049b8` |

네 row 모두 다음 불변식을 만족했다.

- semantic oracle PASS, fallback=false, runtime scan clean, teardown 후 Docker resource 0
- cold/warm normalized runtime-plan SHA와 FED instruction fingerprint 동일
- 각 coordinator에서 planned physical Hop 140, lowered physical Hop 140, missing physical 0, missing synthetic 0, mismatches 0
- 네 planner의 emission placement SHA와 normalized runtime-plan SHA가 모두 서로 다름

따라서 이 configuration에서는 planner 결과가 runtime에서 하나의 공통 plan으로 붕괴한 것이 아니다. Heuristic은 demotion marker 1개를 실제 `fed_fout` 반복 1회로 제한했고, FedAll은 두 개의 normalized `fed_fout` 경계를 loop에서 51회 실행했으며, MinST는 더 적은 FED/FOUT과 dispatch로 가장 짧은 runtime을 만들었다. worker instruction row 수도 DP 2,810, FedAll 5,734, Heuristic 4,634, MinST 2,102로 달라 실제 worker workload까지 분리됐다.

이 한 점의 관측 순서는 MinST 46.028 < FedAll 59.737 < DP 84.868 < Heuristic 95.024초다. 이것은 전체 정렬의 증명이 아니다. 특히 DP는 hop과 직접 자식의 local recurrence이므로 MinST의 전역 결합해보다 나쁠 수 있고, FedAll은 비용 최적화가 아니라 maximal-FED 목적이라 우연히 DP보다 빠를 수 있다. worker=2–4와 다른 workload를 보기 전에는 버그 또는 일반 성능 우열로 확대하지 않는다.
### 7.6 KMeans / worker=2: 동일 runtime의 원인까지 plan log로 판별

worker=2의 네 planner도 모두 exact audit를 통과했다.

| planner | warm runtime | FedPlanner | selected FED/FOUT | runtime `fed_fout` 반복 | dispatch | runtime-plan SHA 앞 12자리 |
|---|---:|---:|---:|---:|---:|---|
| DP | 34.511 s | 16.241708 s | 27 / 22 | 0 | 38 | `ac4c36684975` |
| FedAll | 37.225 s | 3.570938 s | 35 / 33 | 136 | 52 | `1f2e275e0022` |
| Heuristic | 37.146 s | 3.679594 s | 35 / 33 | 136 | 52 | `1f2e275e0022` |
| MinST | 33.008 s | 1.364859 s | 24 / 18 | 0 | 38 | `9afd6c6ba633` |

FedAll과 Heuristic은 runtime뿐 아니라 emission placement SHA도 `44e2d2b21b9e...`로 동일하다. 이는 lowering이 서로 다른 계획을 우연히 합친 것이 아니다. Heuristic policy receipt가 `markerCount=0, localPrefixCount=0, frontierEdgeCount=0`을 명시하므로, “FedAll base에서 marker가 가리킨 경계만 demote하고 나머지는 FedAll과 동일”이라는 정책상 이 cell에서는 동일 plan이 정답이다. 반대로 worker=1에는 marker 1개가 있어 두 plan과 runtime instruction이 달랐다.

DP와 MinST는 dispatch 수만 38로 같을 뿐 emission SHA와 normalized runtime-plan SHA가 서로 다르다. 네 row 모두 planned/lowered physical Hop 142/142, missing physical/synthetic 0, mismatch 0, semantic oracle PASS, fallback=false다. 따라서 실행시간이 비슷한 경우도 plan receipt → lowering → runtime exact identity로 정상 동치인지 계획 유실인지 구분하고 있다.

### 7.7 최신 row 5가 드러낸 planner 전달 이후의 runtime 계약 위반

다음 cell인 `FedAll/KMeans/workers=2/WAN-Light`는 cold phase에서 정확히 중단됐다. planner와 lowering은 hop 668의 scalar-binary `+`를 derived `FED/FOUT/BROADCAST`의 물리 base인 `FED/LOUT/ROW`로 합의했고 instruction 끝에도 `LOUT`이 직렬화됐다. 그러나 실행 직후 coordinator의 실제 output은 `FED/FOUT/ROW`였다.

이 mismatch는 계획 유실이나 audit 시점 오류가 아니었다. `BinaryMatrixScalarFEDInstruction.processInstruction`이 `_fedOut`을 읽지 않고 모든 output에 입력 mapping의 새 ID를 붙였기 때문이다. 같은 binary family의 matrix-matrix 구현은 이미 `LOUT`에서 worker 결과를 `GET_VAR`로 회수해 ROW/COL 방향으로 결합한다. 따라서 합법 후보를 닫거나 audit를 완화하지 않고 matrix-scalar runtime이 명시된 output 계약을 이행하도록 수정했다.

최소 회귀는 기존 코드에서 output mapping 잔존으로 실패했고(`/tmp/g014-binary-scalar-lout-red-v309.log`), 수정 뒤 3/3 통과했다(`/tmp/g014-binary-scalar-lout-green-v310.log`). 관련 runtime/lowering 46 tests도 RC=0(`/tmp/g014-binary-scalar-runtime-contract-v311.log`), package도 RC=0(`/tmp/g014-active-package-runtimefix-v312.log`)이다. 최종 증명은 새 immutable JAR의 row 5에서 planned physical `FED/LOUT/ROW`가 실제 local output으로 관측되고 이어지는 derived BROADCAST materialization까지 mismatch 0으로 완료되는 것이다.

새 snapshot `087f346d3ee589181e85671a4a91676780dc6274`와 stage `c50e9a15c9c871a8b01ddd40d15b347ae0dc61085a51a0f83d0049286d430310`에서 그 최종 증명도 완료했다. continuation output `/home/mchoi/g014-full-results-087f346-d712daf-20260814-v2`는 기존 성공 prefix 4개를 인증해 건너뛰고 row 5만 새로 실행했다. cold/warm 양쪽에서 planned/lowered physical Hop 142/142, missing physical/synthetic 0, mismatch 0이며 semantic oracle PASS, fallback=false, runtime scan clean, teardown zero다. normalized runtime-plan SHA는 양쪽 모두 `6c478dddef7206e9ed6df3873a2ca4e5748ad4dd7aa8ab0b8edd213bdd2cdd8b`이고 실행시간은 cold 39.869초, warm 37.513초다. 따라서 해당 `LOUT` 계약 위반은 source test뿐 아니라 원래 실패 configuration의 Docker 실행에서도 해결됐다.

### 7.8 수정 stage의 KMeans/workers=2 네 플래너 block

continuation은 이어서 Heuristic, DP, MinST까지 완료해 combined 8/336이 됐다. 네 row의 warm runtime은 MinST 32.846초, DP 33.845초, Heuristic 36.819초, FedAll 37.513초다. 모든 cold/warm coordinator에서 planned/lowered physical Hop은 142/142, missing physical/synthetic과 mismatch는 0이며 oracle/scan/teardown을 통과하고 fallback은 없다.

FedAll과 Heuristic의 emission `plan` hash는 planner identity 때문에 다르지만 placement SHA는 모두 `8e4378b77b7872a35ba7a7c956f64825648673f310bfd4f22bfd76b4c7f18c2f`, normalized runtime-plan SHA는 `6c478dddef7206e9ed6df3873a2ca4e5748ad4dd7aa8ab0b8edd213bdd2cdd8b`로 같다. 이 configuration의 Heuristic marker가 0이므로 의도된 동치다. DP는 FED/FOUT 25/20과 runtime-plan `ac4c36684975...`, MinST는 24/18과 `9afd6c6ba633...`으로 서로 다른 계획을 실제 실행했다. 즉 수정 뒤에도 비용 기반 플래너 차이가 runtime에서 소실되지 않았고, 이 block에서는 허용된 오차 범위의 목표 순서 `MinST <= DP <= Heuristic, FedAll`도 관측됐다.
