# Derived supply sharing: origin/main 게시 검증

## 기준과 범위

- Workspace: `/home/mchoi/w1357-derived-supply-sharing-20261006`.
- 최초 기준: `eb64f9c939708735940f2ae095c5c8bd526decf7`.
- 구현 커밋: `6b9ee37485e6b42cf20513e7536d28f6e7ad2f91`.
- 통합한 origin/main: `0146f043e0` → `e8e42e93fa` → **`79c26b40cebae479a191f9e406586b5b74193d17`**.
- 기존 workspace와 실행 중인 실험은 수정하지 않았다. 각 검증은 새 Docker run 디렉터리를 사용했다.

Native/supply 표현 분리와 source/version 기반 공유의 설계·후보 수 비교는
[구현 결과](DERIVED_SUPPLY_SHARING_RESULT_2026-10-06_KO.md), invariant FOUT의 비용·런타임
수정은 [FOUT staging 보고서](INVARIANT_FOUT_SHARING_2026-10-06_KO.md)에 기록했다.
이 문서는 최신 main과의 통합 및 최종 게시 검증을 정리한다.

## 병합에서 보존한 의미

`ExactPhysicalCostModel`의 충돌은 upstream의 branch guard를 가진 여러 alias origin과
원본 source/version의 creation profile, `crossExecutionReuse`, staged REFED upload를
함께 보존하여 해결했다. 선택된 explicit movement는 supply 측이 소유한다.
FED 연산 내부 auxiliary 통신과 per-invocation preparation은 operator 측이 소유한다.
CTABLE의 cold collection은 기존 GET collector가 담당하며, native-local PUT이 이미
operator 준비 비용에 있으면 같은 PUT을 다시 과금하지 않는다.

Native 출력 geometry, 동적 realization identity, branch normalization,
재컴파일 instruction 보존, REV 출력 flag, DP resource guard 변경도 유지했다.
후속 shared reduction repair는 immutable root가 증명한 지원값을 원래 값 번호로
전달하고, 경계가 풀릴 때 조건부 축소를 다시 준비한다. 초기 seed의 전체 domain,
sharing factor의 원래 assignment projection 및 canonical recost는 유지된다.

추가로 `ExactPhysicalNativeSupplyRepresentation.nativeCandidate`가 non-null witness만
보고 `DURABLE_MAP`으로 바꾸던 표현을 고쳤다. 동적 native witness는 endpoint residency만
증명할 수 있으므로 선택된 realization의 layout kind와 lineage를 보존한다.
`rev(A) → exp(T)` 회귀는 witness가 있어도 `NATIVE_LINEAGE`와
`exactWorkerPool=false`가 유지되는지 검사한다.
Candidate/witness relation, legality/privacy, TW/TR 또는 function boundary 제약을
완화하지 않았다. 문서 충돌은 양쪽 세션 기록을 모두 보존했다.

## 최종 검증: 79c26b40ce 통합본

| 검증 | 결과 |
|---|---|
| 관련 Java 회귀 79 classes | **708 cases: 703 PASS, 기존 skip 5, 실패/오류 0** |
| Global/Local canonical 비용 및 sharing | production surface의 canonical recost, 선택 lifetime, receipt 검증 PASS |
| retirement/eviction | 최종 `OwnedRefedReuseTest` 30/30 PASS. 같은 owner/version/layout도 삭제된 copy는 재생성 |
| Maven `test-compile`, shell syntax, diff whitespace | PASS |
| 변경 없는 Python joint-boundary harness | 20/20 PASS |
| Docker model/runtime sharing proof | **12/12 PASS**, worker 횟수·수치 결과·alias cleanup 검증 |
| Docker DML E2E | **13/13 기대 결과 PASS**, CP/FED 수치·dynamic reverse·branch upload·privacy rejection 검증. 계획 밖 runtime conversion 0 |

| 실제 worker에서 3회 실행 | source GET_VAR | target PUT_VAR | 예측 upload cost, ms |
|---|---:|---:|---:|
| invariant FOUT | **1** | **1** | 1.002685546875 |
| 3개의 updated FOUT versions | **3** | **3** | 3.008056640625 |

비용은 production surface의 예측값이며 wall time이 아니다. Docker proof는 compiler가
도출한 sharing group을 실제 staged REFED Lop/instruction에 전달하고 모든 원소의 수치와
매 실행 후 output alias 삭제를 확인한다. 특정 DML이 이 공급을 전역 최적안으로
선택했다는 주장은 하지 않는다.

최종 main/test Java source 및 모든 class bytes는 새 Docker snapshot과 모두 일치한다.
E2E run은 `invariant-fout-sharing-main-r2`이며, 이전 `main-r1`도 수정하지 않고 보존한다.
두 run의 source 및 결과를 구분한다.

## 이전 확대 회귀에서 확인한 기존 GLM 결함

`0146` 통합본의 확대 회귀는 63 classes / 573 cases에서 567 PASS, 기존 skip 5,
오류 1이었다. 유일한 오류는
`OccurrenceExecutionFrequencyFactsConstantBranchTest.actualBuiltinGlmDeadStraightenXIsZeroAndCgRemainsPositive`의
`Derived FOUT anchor owner has no exact graph-owned worker-pool authority`다.

이번 변경이 없는 `0146f043e0`의 frozen baseline에서 같은 method만 실행하여 오류 메시지
전체가 동일함을 확인했다. Baseline의 tracked Java source 3,549개가 Git blob과 일치하고
누락/추가/불일치는 0이다. 발생 지점은 `NeutralPlacementGraph.validateReferences →
PlacementRelationClosure`이며 physical cost/model 생성 전이다. Baseline wall time은
239.33초였다. 이 기존 결함을 수정하거나 테스트에 ignore를 추가하지 않았다.
최종 79-class 회귀에는 이 전체 GLM 검사를 포함하지 않았으며, 최신 전체 저장소가
all-green이라고 주장하지 않는다. 앞선 실패/귀속 증거도 게시 기록에 그대로 보존했다.

## 증거와 남은 범위

최종 명령·결과·source/class 대조는
[`docs/experiments/derived-supply-main-20261006/latest79/`](experiments/derived-supply-main-20261006/latest79/),
이전 통합 검증과 GLM 재현은 그 상위 디렉터리에 있다.
원본 로그는 `.omx/invariant-fout-sharing/publication/`에 보존했다.
Docker root는 `/grid/3/cofee-lm-sweep-mchoi-20260914/invariant-fout-sharing-20261006/`이다.
모든 runtime 검증은 `scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e`로 실행했다.

공유 copy의 해제는 원본 value invalidation/removal 또는 기존 cache retirement/eviction에
따른다. Exact last-consumer 해제, residency/memory budget을 포함한 비용 최적화, 외부 코드의
같은 remote-ID 덮어쓰기 감지는 추가하지 않았다. 별도로 기록된 large-factor/메모리/함수
authority 문제 전체가 해결됐다는 의미도 아니다.
