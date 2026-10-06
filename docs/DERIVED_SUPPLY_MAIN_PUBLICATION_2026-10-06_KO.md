# Derived supply sharing: origin/main 게시 검증

## 기준과 범위

- 작업 workspace: `/home/mchoi/w1357-derived-supply-sharing-20261006`.
- 최초 기준: `eb64f9c939708735940f2ae095c5c8bd526decf7`.
- 구현 커밋: `6b9ee37485e6b42cf20513e7536d28f6e7ad2f91`.
- 1차 통합 origin/main: `0146f043e07ca445d9084257759aa78fe14ddf55` (병합 커밋 `8f7dc9dda1`).
- 게시 전 추가 통합 origin/main: `e8e42e93fa6fca0406fab4fcddcad22d4ee92f6f`. 이후 3개 커밋은 문서와 retirement/eviction 테스트만 추가하며 production 변경은 없다.
- 기존 workspace와 실행 중인 실험은 수정하지 않았다. 새 검증도 독립된 Docker run 디렉터리를 사용한다.

Native/supply 표현 분리와 source/version 기반 공유 구현, invariant FOUT staging 수정은
[구현 결과](DERIVED_SUPPLY_SHARING_RESULT_2026-10-06_KO.md)와
[FOUT staging 보고서](INVARIANT_FOUT_SHARING_2026-10-06_KO.md)에 설명되어 있다.
이 문서는 그 변경을 최신 main에 합친 뒤의 검증을 기록한다.

## 병합에서 보존한 의미

`ExactPhysicalCostModel` 충돌은 upstream의 branch guard를 가진 여러 alias origin과
auxiliary FED 통신 비용을 유지하면서 해결했다. 원본 source/version의 creation profile,
`crossExecutionReuse`, staged REFED upload의 비용 소유권을 함께 보존한다.
Explicit movement는 supply 측에서, FED 연산 내부의 auxiliary 통신은 operator 측에서
계산한다. Download의 guarded activation을 upload retention 선택으로 오해하지 않는다.

최신 main의 native 출력 geometry, 동적 realization identity, branch normalization,
재컴파일 instruction 보존, REV 출력 flag, DP resource guard 수정도 포함했다.
세션 기록의 충돌은 양쪽 독립 항목을 모두 유지했다.

추가로 `ExactPhysicalNativeSupplyRepresentation.nativeCandidate`에서 non-null witness만
보고 `DURABLE_MAP`으로 바꾸던 표현을 고쳤다. 동적 native witness는 endpoint residency만
증명할 수 있으므로, 선택된 realization의 layout kind와 lineage를 보존한다.
`rev(A) → exp(T)` 분기 fixture의 회귀 테스트는 witness가 있어도 `NATIVE_LINEAGE`와
`exactWorkerPool=false`가 유지되는지 확인한다. 후보, witness relation, legality/privacy,
TW/TR 또는 function boundary 제약을 변경하지 않는다.

## 통합 검증

| 검증 | 결과 |
|---|---|
| 최초 통합본의 기능 회귀 38 classes | 328 cases: 324 PASS, 기존 skip 4, 실패/오류 0 |
| 최초 통합본의 확대 회귀 63 classes | 573 cases: 567 PASS, 기존 skip 5, GLM 오류 1 |
| 추가 main 변경을 반영한 `OwnedRefedReuseTest` | 30/30 PASS. retirement/eviction 뒤 동일 owner/version/layout도 새 copy를 생성함을 검증 |
| Python joint-boundary harness | 20/20 PASS |
| Maven `test-compile`, shell syntax, diff whitespace | PASS |
| Docker model/runtime sharing proof | 12/12 PASS. 아래 worker 횟수와 수치/alias cleanup 확인 |
| Docker DML E2E | 13/13 기대 결과 PASS. CP/FED 수치 및 dynamic reverse, branch upload, privacy rejection 검증. 계획 밖 runtime conversion 0 |

확대 회귀를 전체 통과로 표시하지 않는다. 유일한 오류는
`OccurrenceExecutionFrequencyFactsConstantBranchTest.actualBuiltinGlmDeadStraightenXIsZeroAndCgRemainsPositive`의
`Derived FOUT anchor owner has no exact graph-owned worker-pool authority`다.
`0146f043e0`의 frozen baseline에서 같은 method만 실행해 같은 오류를 재현했다.
Baseline의 전체 tracked Java source 3,549개가 Git blob과 일치하고 누락/추가/불일치는 0이다.
발생 지점은 `NeutralPlacementGraph.validateReferences → PlacementRelationClosure`이며,
이번 physical cost/model 생성 전이다. Baseline 단일 실행 wall time은 239.33초다.
후속 `e8e42e93fa`까지 production source가 같으므로 최신 main에도 해당하는 기존 결함이다.
테스트를 skip하거나 candidate/boundary 검사를 완화하지 않았다.

| 실제 worker에서 3회 실행 | source GET_VAR | target PUT_VAR | 예측 upload cost, ms |
|---|---:|---:|---:|
| invariant FOUT | 1 | 1 | 1.002685546875 |
| 3개의 updated FOUT versions | 3 | 3 | 3.008056640625 |

비용은 production surface의 예측값이며 wall time이 아니다. Docker proof는 compiler가
도출한 sharing group을 실제 staged REFED Lop/instruction에 전달하고 모든 수치 결과와
매 실행 후 output alias 삭제를 검사했다. 특정 DML의 전역 최적안 선택을 주장하지 않는다.

최종 production Java source와 모든 main class는 Docker frozen artifact와 일치한다.
Docker snapshot 이후 차이는 새 upstream 테스트가 추가된 `OwnedRefedReuseTest.java/.class`
각 1개뿐이며, 이 최종 class는 별도 Maven 30/30 실행으로 검증했다.
Docker model proof 및 나머지 test classes는 동일하다.

증거는 `.omx/invariant-fout-sharing/publication/`에 보존한다. 최종 재현 명령과 요약은
`docs/experiments/derived-supply-main-20261006/`에도 기록한다.
Docker run은 `/grid/3/cofee-lm-sweep-mchoi-20260914/invariant-fout-sharing-20261006/invariant-fout-sharing-main-r1/`이다.

## 남은 범위

공유 copy의 해제는 원본 value invalidation/removal에 따른다. Exact last-consumer 해제,
shared-memory budget 최적화 또는 외부 코드의 같은 remote-ID 덮어쓰기 감지는 추가하지 않았다.
이 검증은 관련 회귀와 격리 Docker 실행의 결과이며, 별도로 기록된 모든 workload의
large-factor/메모리/함수 authority 이슈가 해결됐다는 의미는 아니다.
