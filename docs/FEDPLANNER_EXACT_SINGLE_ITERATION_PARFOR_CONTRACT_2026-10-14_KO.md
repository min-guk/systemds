# Memo PARFOR 0/1회 증명 지원 — 기존 순차 loop 계약의 제한된 확장

**상태:** 구현·집중 회귀 완료, 통합/실제 workload 검증 진행중. 일반 PARFOR 지원 완료가 아니다.

## 변경 목적과 기존 spec과의 관계

기존 sequential-loop-only 계약에서 모든 PARFOR를 일괄 거절해 실제 KMeans/P1의 `num_runs=1`도 실패했다. 기존 `ParForProgramBlock.execute`는 이미 실제 반복 수가 1이면 일반 `ForProgramBlock` 경로를 실행한다. 이 기존 실행 의미를 정적으로 증명할 수 있는 경우만 Memo의 기존 loop 계약으로 지원한다. DML을 FOR로 바꾸거나 PARFOR optimizer를 끄는 방식이 아니다.

## 정적·런타임 불변조건

- 모든 도달 가능한 호출의 실제 scalar argument를 따라 **동일한 정확한 0회 또는 1회**임을 증명한다. 서로 다른 0/1, 2회 이상, 미상 bound, 순환/가변 bound는 지원 증명이 아니다.
- LOOP_HEAD 등의 multi-child는 entry 하나만 보지 않는다. 모든 대안이 동일한 exact scalar인 경우만 사용한다. 분기/loop 상관관계를 모르는 경우 UNKNOWN을 유지한다.
- integer 증명에서 Double 정밀도 손실/정수 overflow로 허위 상수를 만들지 않는다. 현재 구현은 exact integer ±2^53 범위의 literal/cast/arithmetic만 증명하며, 초과 값은 지원 미증명으로 남긴다.
- runtime과 같은 scalar cast, increment-zero, 기본 방향 및 `UtilFunctions.getSeqLength` 의미를 적용한다.
- 선택된 프로그램 owner의 `MemoPlanRegistry.Plan`에 SBID별 count를 고정한다. runtime이 평가한 count가 다르면 optimizer/task 생성·zero/one 실행 경로 전에 fail-close한다.
- 0회는 본문을 실행하지 않지만 planned exit instructions는 실행한다. 1회는 원래 runtime의 일반 FOR 실행 경로를 이용한다.
- 미호출 함수의 PARFOR는 selected runtime contract에 게시하지 않는다. UNKNOWN으로 도달 가능한 함수/loop는 미호출과 다르다.
- body occurrence의 `occurrenceContexts`가 **정확히 공집합**인 경우에만 selected alias layout 조회를 생략한다. UNKNOWN cardinality는 body phase가 남으므로 생략 대상이 아니다.

## 유지되는 경계

다회 PARFOR의 worker-local task scopes, result merge, planned supply retention, federated result placement 및 task-parallel 비용은 이 변경에 포함하지 않는다. 특히 SliceLine decodeTopK 등의 다회 PARFOR는 이 변경으로 지원되지 않는다. physical federation worker 수와 local PARFOR task 수를 같은 것으로 취급하지 않는다.

## 검증

- `MemoProgramFactsTest`: literal/call-bound 0/1, mixed/many/unknown, outer-loop bound 변화, scalar cast/default stride/negative stride, 큰 INT64 손실 반례, 미호출 함수.
- `MemoParForRuntimeContractTest`: selected1 registry와 runtime2 drift 거절, selected0 registry와 exit 경로 1회 실행, 미호출 함수 미게시.
- `MemoProgramModelTest`: 기존 loop 및 L2 entry/repeat type parity와 함께 검증.
- 집중 결과: Facts+runtime **40 tests PASS**, 증거 `experiments/actual-workload-support-20261014/parfor-overlay/int64-combined-junit.log`. 선택 ZERO alias 수정의 별도20tests PASS 증거는 같은 디렉터리 `zero-model-runtime-junit.log`.
- 실제 KMeans/P1 W1/3/5/7 결과는 아직 미완료다. 집중 테스트 통과만으로 해당 workload 성공을 주장하지 않는다.

## 잠재 회귀와 탐지

- 정적 evaluator와 runtime 의미 불일치: 큰 정수/overflow/cast/stride 반례 및 runtime proof drift guard.
- 미도달로 잘못 판정한 alias: unknown/mixed loop와 호출별 reachable context 부정 테스트.
- zero exit 누락: selected0 runtime regression.
- 기존 LogReg/GLM overhead: PARFOR가 없는 프로그램은 추가 호출별 숫자 증명 pass를 실행하지 않는다. 실제 under10 회귀 gate는 별도 재측정한다.
