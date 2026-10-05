# R61 — AggLocal 반복 경로 분석 경량화

## 결과

**V5 정책·공통 전체 후보 생성·privacy/runtime 합법성을 유지하면서 중복 분석을 줄였다.**
production 변경은 `PlacementRelationClosure.java`, `HeuristicPlacementAdapter.java` 두 파일이다.
전체 compiler가 single-pass가 되었다거나 workload runtime이 개선되었다는 주장은 하지 않는다.

## 변경

### 1. CFG 경로 전체 재추적 → 증분 전파

- compiled input, consumer별 candidate, source value-version별 relocation, CFG writer 관계를 한 번 인덱싱한다.
- 일반 edge와 nested-demotion edge의 exact 판정을 별도로 캐시한다. seed 철회 후 nested 선호를 잘못 재사용하지 않는다.
- 각 seed의 local prefix·사용 edge·native continuation·reentry 증거는 따로 유지한다.
- 다중 reaching definition은 **모든 writer에 local 증명이 있을 때만** 활성화한다.
- 새 CFG edge가 활성화되면 그 writer까지 도달한 경로만 이어서 처리한다. 이미 처리한 경로 전체를 다시 순회하지 않는다.
- seed가 없으면 경로 인덱스와 전파를 생략한다.

### 2. Adapter의 반복 전체 조회 제거

- marker마다 graph 전체를 검색하지 않고 value-version 인덱스를 한 번 구성한다.
- assignment마다 occurrence 전체를 검색하지 않고 occurrence→Hop 인덱스를 한 번 구성한다.
- 이전과 동일하게 key의 `equals`와 Hop의 identity를 구분한다. unknown/ambiguous marker 및 owned-state 검사는 유지한다.

### 유지한 경계

- 공통 eager candidate universe, Oracle, greedy 정책 순위, candidate/전송 선택과 최종 witness 검증은 변경하지 않았다.
- runtime fallback·DP 재시도·opcode 특례·새 의존성을 추가하지 않았다.
- **seed 집합에서 항목을 철회하면 새 최소 고정점으로 재전파한다.** immutable index/cache만 재사용하고 이전 local proof는 버린다. 순환 phi가 철회된 증명을 자기 지지하지 못하게 하는 경계이며, 완전한 deletion-aware 증분 알고리즘은 아니다.
- metadata의 lazy 계산은 적용하지 않았다. `PlacementAnalysis` 생성자가 경로 소유권과 합법성을 검증하므로, 생성·실패 시점까지 바꾸는 변경은 이번 동등성 리팩터링에서 제외했다.

## 검증

### 동작 잠금 및 최종 회귀

- 수정 전 기존 focused suite **19 PASS**.
- 최종 기존 suite + 신규 경로/adapter 테스트 **25 PASS**. 중복 실행 수를 합산한 숫자가 아니다.
- 신규 검증: 두 seed와 2단계 phi, 일부 writer만 local인 join, 공유 protected formal로 인한 seed 철회, nested seed 철회, seed 없는 경로, marker의 정상/미존재/모호성.
- 기존 검증에는 공개 vector sibling 수집·공유, 보호 sibling 비수집, 큰 matrix의 FED 유지, 함수·루프 경계 및 L2SVM W1/3/5/7 선택 계약이 포함된다.
- 변경 production/test 파일의 `javac -Xlint:all,-path,-processing` 결과 **경고·오류 0**.
- 정적 diff/whitespace 검사 PASS. UI·외부 보안 경계 변경 없음. fallback-like 로직 추가 없음.

### 수정 전 알고리즘과 직접 비교

변경 전 `PlacementRelationClosure`를 외부 evidence에 고정한 뒤 클래스명과 계측 counter만 바꿔 함께 컴파일했다. **동일한 graph·candidate·shape·CFG 객체**에 기존/신규 경로 분석을 적용하여 `HeuristicPolicyFacts` 전체 record equality를 비교했다.

| 사례 | 경로 증거 | 기존 전체 trace | 신규 seed-set epoch | 기존 간선 방문 | 신규 간선 방문 |
|---|---|---:|---:|---:|---:|
| 두 seed + 연속 phi | 동일 | 3 | 1 | 104 | 44 |
| 일부 writer만 local | 동일 | 1 | 1 | 5 | 5 |
| shared protected formal, seed 철회 | 동일 | 2 | 2 | 3 | 3 |
| seed 없음 | 동일 | 1 | 0 | 0 | 0 |
| nested seed 철회 | 동일 | 2 | 2 | 5 | 5 |

첫 사례의 exact edge classification은 신규26회다. **이 수치는 분석 작업량이지 실행시간 또는 workload runtime 개선율이 아니다.** 서로 다른 seed의 증거를 출력해야 하므로 seed×도달 경로 크기 자체는 여전히 남을 수 있다.

## 검증 중 발견한 문제

1. 최초 mixed-writer fixture에서 Y도 보호값으로 설정하여 시작 seed 자체가 철회됐다. 해당 테스트의 의도대로 **X는 PRIVATE_AGGREGATE, Y만 PUBLIC**으로 수정했다. production privacy 규칙은 바꾸지 않았다. 기존 R51 공개 sibling 회귀와 같은 제한된 조건이다.
2. adapter 초안이 key identity를 비교해 기존 `equals` 의미를 강화하는 것을 리뷰에서 발견했다. equality 기반 index + Hop identity 검사로 수정했다.
3. 추가 합성 `loop update → shared function formal` fixture 하나는 경로 분석 이전의 공통 closure에서 `Function input boundary has no exact source state legal at its formal read`로 실패했다. **수정 전 R59 JAR에서도 같은 실패를 재현했다.** 이 기존 경계 문제는 이번 범위에서 우회하거나 수정하지 않았다. 이 실패를 PASS 개수에 포함하지 않는다.

## 산출물과 제한

근거 디렉터리:
`/grid/3/cofee-lm-sweep-mchoi-20260914/agglocal-incremental-r61-20261005/evidence`

- `baseline.json`, `baseline-focused.log`, `baseline-jar.sha256`
- `final-production-javac.log`, `final-test-javac.log`, `final-focused.log`
- `differential.log`, `reference-source/`, `reference-classes/`
- `unsupported-function-loop-baseline.log`, `differential-unsupported-function-loop.log`
- `completion-receipt.json`

검증은 기존 R59 JAR·의존성에 변경 class overlay를 먼저 둔 독립 출력 디렉터리에서 수행했다. main의 `target` symlink와 기존 실험 JAR는 유지했다. 위 구현 검증 당시에는 새 원격 profiling·runtime 캠페인·전체 Maven build·배포·commit/push를 실행하지 않았다. 이후 게시 검증은 아래에 구분한다. 선택/적용 단계의 실제 시간 단축과 전체 planning 목표 충족 여부는 미측정이다.

## 다음 범위

현재 범위는 동작 동등성과 중복 제거가 확인되어 종료한다. 전체 공통 후보 생성 비용, metadata 지연 생성, seed 철회의 완전한 증분 유지, 위 합성 함수/루프 경계 실패는 별도 작업이다. 기존의 전체 후보 공유 계약을 heuristic 전용 lazy 생성기로 바꾸지 않는다.

## origin/main 게시 범위 및 호환성 검증

- 게시 기준점: `ff203f4cc2` (R51). 두 production 파일이 R61의 변경 전 preimage와 정확히 일치함을 확인하고, 별도 worktree에 R61 diff만 적용했다.
- 게시 범위: production 2개, 신규 테스트 2개, 본 보고서와 R61 세션 기록. 작업공간에 남은 R52–R60 비용 모델·자동 profiling 변경은 이 커밋에 포함하지 않는다.
- R59 overlay 검증과 별개로 **R51 JAR 위에서 게시 소스를 새로 컴파일하여 focused 25 PASS**, production/test javac lint 경고·오류 0, diff whitespace 검사 PASS를 확인했다. 테스트 및 fixture helper도 게시 worktree의 소스로 다시 컴파일했다.
- 재현 명령: `bash /grid/3/cofee-lm-sweep-mchoi-20260914/agglocal-incremental-r61-20261005/evidence/publish-verify.sh`. 근거: `publish-base-sha.txt`, `publish-base-jar.sha256`, `publish-production-javac.log`, `publish-test-javac.log`, `publish-focused.log`.
- 원본 작업공간의 branch/HEAD·index·target 및 무관한 dirty 변경은 그대로 유지한다. 원격에는 일반 fast-forward push만 사용하며, runtime 성능 개선을 주장하지 않는다.
