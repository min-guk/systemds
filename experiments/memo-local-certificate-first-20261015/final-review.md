# MEMO_LOCAL certificate-first 최종 독립 리뷰

검토 시점은 2026-10-15이며, 최종 `engine-v8`이 동결된 뒤 수행한 읽기 전용 리뷰다. 소스나 테스트를 이 리뷰에서 다시 수정하지 않았고 Maven도 다시 실행하지 않았다.

## Code Review Summary

**Files Reviewed:** 19
**Total Issues:** 0

### By Severity

- CRITICAL: 0
- HIGH: 0
- MEDIUM: 0
- LOW: 0

### Recommendation

**APPROVE**

현재 확인한 범위에서 인증되지 않은 결과를 성공으로 바꾸거나, 원래 비용·합법성 문제를 축소하거나, Global exact 경로를 Local의 fallback으로 재사용하는 결함은 찾지 못했다. 최신 producer-origin 수정도 실패를 숨기는 우회 경로가 아니라 런타임 marker 계약의 원인을 직접 고쳤으며, 여전히 지원하지 못하는 물리 매핑은 lowering 단계에서 명시적으로 실패한다.

## 검토 범위

주요 구현은 다음 파일을 검토했다.

- `MemoLocalOptimizer.java`: certificate-first 순서, 전역 cover, LB/UB 갱신, 상태 결정, 메모리 admission, 계측
- `MemoLocalMessages.java`: 선택적 message 계산, 완전한 relaxation bound, 원래 factor 보존
- `MemoFeasibleSeed.java`: 첫 feasible witness와 제한된 UB 개선
- `MemoOptimizer.java`: legality-only propagation API와 보수적 workspace 추정
- `MemoDualModeOptimizer.java`: Global/Local 독립 route와 결과·통계 계약
- `MemoProgramModel.java`: 원래 후보·비용·제약의 연결 및 supply schedule provenance
- `MemoSupplyLowering.java`: physical producer marker와 함수 반환 remap 검증
- benchmark driver와 result auditor: compile/runtime 증거 구분, Local action/summary 검증

직접 대응 테스트로 `MemoLocalOptimizerTest`, `MemoLocalMessagesTest`, `MemoLocalSeedTest`, `MemoLocalPropagationTest`, `MemoBindingResolverTest`, `MemoMultiOutputBuiltinTest`, `MemoSharedSupplyRuntimeTest`, `MemoSupplyLoweringTest` 및 benchmark/auditor Python 테스트를 검토했다.

## 핵심 판정

### 1. Local은 처음부터 certificate-first로 실행된다

Local은 원래 전체 domain의 coarse LB를 먼저 게시하고 첫 feasible seed로 UB를 만든 뒤 즉시 5% 인증을 검사한다. 그 다음에만 seed 개선, optional legality propagation, 선택적 message, proof-cover search 순서로 정밀화한다. Local 결과 통계의 `directAttempts`와 `directExactSolves`는 0으로 고정되어 Global exact-first 경로를 호출하지 않는다.

종료 판정은 `LB == UB`일 때만 `OPTIMAL`, `LB > 0`이면서 `UB <= 1.05 * LB`일 때만 `EPSILON_OPTIMAL`이다. UNKNOWN 영역과 ε 때문에 보류한 영역은 전역 lower cover에 계속 포함된다. 메모리 admission이나 명시적 진단 node cap 때문에 증명이 끝나지 않으면 `RESOURCE_EXHAUSTED` 또는 `UNRESOLVED`로 끝나며 인증 성공으로 바뀌지 않는다.

관련 소스 기준점:

- `MemoLocalOptimizer.java:85-165`: seed-first, certificate-first, propagation/message의 선택적 실행
- `MemoLocalOptimizer.java:168-209`: 원래 domain을 덮는 양분 proof cover
- `MemoLocalOptimizer.java:325-338`: 전역 LB와 정확한 상태 판정
- `MemoLocalOptimizer.java:376-384`: 성공 상태에서만 certification time 게시

### 2. propagation은 exact solve가 아니며 제거 방향이 안전하다

`propagateOnly`는 원래 ordinal domain에 legality support filtering만 수행한다. `Truth.NO`로 증명된 support만 제거하고 UNKNOWN을 feasible witness나 infeasibility 증명으로 사용하지 않는다. 비용 최적화, tree solve, elimination, search를 호출하지 않는다.

Local은 initial LB/UB와 seed 인증 기회를 먼저 검사한 뒤, 보수적으로 추정한 추가 메모리가 admission을 통과한 경우에만 propagation을 수행한다. 추정에는 ordinal별 exact numeric payload와 trail, owner/check 구조, wide-domain key snapshot, truth/witness/exact/binary cache의 독립 entry가 포함된다. admission 거절은 기존 root domain과 bounds를 그대로 보존한다.

관련 소스 기준점:

- `MemoOptimizer.java:334-350`: legality-only API와 UNKNOWN-safe 결과 의미
- `MemoOptimizer.java:353-380`: propagation workspace 상계
- `MemoLocalOptimizer.java:117-133`: seed 인증 이후의 optional 호출

### 3. physical producer origin 수정은 런타임 provenance 계약과 일치한다

한 물리 Lop producer가 여러 논리 occurrence로 관찰될 때 같은 statement block 안의 동일 Hop occurrence만 하나의 native origin으로 정규화한다. 이 제한은 한 block-local producer event의 alias만 합치고 다른 statement block의 실행 epoch는 합치지 않는다.

정규화된 native origin은 definition marker와 직접 action이 공유한다. 함수 경계를 넘으면 첫 return remap이 native origin을 caller-visible target으로 내보내며, 그 뒤의 export chain은 각 단계의 source/target을 유지한다. multi-output builtin은 `DEFINITION(referenceId, outputSlot)`별 binding을 사용하므로 Q/R 같은 별도 output slot을 합치지 않는다.

lowering은 여전히 하나의 물리 producer에 서로 다른 marker가 남으면 예외를 발생시킨다. 오류에는 충돌한 definition site가 포함된다. 따라서 아직 모델링하지 못한 physical sharing을 조용히 허용하거나 잘못된 origin을 임의 선택하지 않는다.

관련 소스 기준점:

- `MemoProgramModel.java:1871-1893`: marker, action, return remap의 연결
- `MemoProgramModel.java:1901-1932`: same-Hop + same-statement-block 정규화와 exact output-slot binding
- `MemoSupplyLowering.java:124-132`: 물리 producer의 복수 marker fail-closed 검증

## 검증 증거

- 보존된 `test-summary.json`은 Memo 관련 767개 테스트에서 failure/error/skip이 모두 0이라고 기록한다.
- 최신 origin focused log인 `final-origin-tests.txt`는 최종 수정 뒤 생성되었고 오류 없이 끝났다. 이에 대응하는 테스트 소스는 shared compiled function body의 caller-visible return 3개, 단일 physical definition, 단일 upload action, 수치 결과 `-72`, materialization 1회, reuse 1회, 종료 시 live lease/pinned source 0을 검사한다.
- `MemoMultiOutputBuiltinTest`는 실제 binding resolver가 만든 Q/R identity를 사용해 output slot별 canonical origin이 서로 다름을 검사한다.
- `pilot-v7/new-pilot/summary.json`은 LogReg, GLM, GNMF와 `EXACT`, `MEMO_GLOBAL`, `MEMO_LOCAL`의 9개 **full-compile** 행이 모두 `passed`, `compile.verified=true`라고 기록한다. 이전 LogReg의 multiple-definition-origin 실패가 사라졌다는 통합 증거다.
- 작은 수치 runtime test는 linear/logistic 두 workload에 대해 5개 알고리즘, 총 10개 결과를 실행했다. MEMO_LOCAL은 두 경우 모두 `EPSILON_OPTIMAL`이며 출력 weight와 비용이 비교 solver와 같았다.
- `git diff --check`는 오류 없이 통과했다.

## 증거의 한계와 명시적 지원 경계

다음 항목은 승인 차단 결함은 아니지만 결과를 해석할 때 유지해야 하는 경계다.

1. `pilot-v7`의 9개 workload 행은 full compile과 runtime-program 생성 검증이다. `executionSeconds`가 0이고 `runtimeRows`가 비어 있으므로 실제 ML 학습 수치 실행 결과로 인용하면 안 된다. 특히 GNMF는 이 증거에서 실제 training runtime을 실행하지 않았다.
2. 실제 수치 training runtime 증거는 작은 linear/logistic fixture에 한정된다. 대표 real-workload compile 성공과 작은 수치 runtime 성공은 서로 다른 검증 층이다.
3. `MemoSharedSupplyRuntimeTest`의 materialization/reuse/lease counter는 세 함수 호출을 가진 한 federated fixture의 동작을 증명한다. 모든 실제 workload의 alias counter나 모든 함수/branch/loop 조합을 포괄한다고 주장할 수 없다.
4. same-statement-block 제한 자체를 두 block에 동일 Hop 객체를 의도적으로 배치해 검사하는 전용 단위 테스트는 없다. 현재 소스의 명시적 block-id filter와 fail-closed lowering, focused 통합 테스트가 이 위험을 제한한다.
5. 서로 다른 Hop 객체가 lowering에서 우연히 동일 Lop으로 합쳐지는 추가 형태는 현재 canonicalization 대상이 아니다. 그런 경우 복수 marker 검증이 명시적으로 실패하므로 잘못된 실행 대신 unsupported compile failure로 보고된다.
6. 최종 `engine-v8` 비교 실험과 7회 반복 성능 집계는 이 리뷰 작성 시 진행 중이다. 따라서 이 문서는 v8의 최종 속도, 메모리 우위, 모든 workload의 5% 인증 성공률을 주장하지 않는다. 그 결론은 완성된 receipt, summary, certificate audit를 기준으로 별도 보고해야 한다.
7. message의 `touchedVariables`, `touchedFactors`, `completedMessages`, `untouchedBuckets`는 message kernel 작업량이다. seed 생성, feasible-plan validation, propagation, proof-cover search가 읽은 전체 factor 수로 해석하면 안 된다.

## Root-cause guard 판정

multiple-origin 오류를 해결하기 위해 예외를 삼키거나 supply action을 제거하지 않았다. 원래 원인은 하나의 물리 producer에 여러 논리 binding을 marker로 발행한 것이었고, 수정은 block-local producer identity를 canonical origin으로 만든 뒤 함수 반환에서 명시적으로 caller binding에 remap한다. lowerer의 단일-marker 검증은 유지되고 진단 정보는 오히려 강화되었다.

따라서 이 변경은 기존 오류를 우회하는 fallback이 아니며, 검증되지 않은 runtime plan을 허용하지 않는다.
