# Federated Planner P/L/R Audit Checkpoint — 2026-08-31 21:01 CEST

## 1. 현재 판정

병렬 agent와 격리된 Maven target, `so003`--`so006` 원격 shard를 함께 사용하도록 전환한 결과, 이번 구간에서 WCeMM/WUMM 10개와 AggregateBinary/AggregateUnary 36개 published target을 추가로 검증했다. 기존 94개와 합치면 현재 **140개의 fixture-specific forced target이 모두 성공**했다.

이 수치는 전체 runtime state 공간의 완전 열거가 아니라, exact occurrence/input signature로 구성한 published candidate target의 강제 실행 누계다. 따라서 `Spurious=0`은 해당 target 집합에 대해 확정되지만, `Missing=0`은 관측된 runtime join 범위에서만 주장한다.

## 2. 이번에 완료한 작업

### 2.1 WCeMM/WUMM — 4개 서버 병렬 검증

Authoritative artifact:

`audit-results/fed-runtime-wcemm-wumm-layout-e2e-v2-20260831T184439Z/forced-v4-authoritative-20260831T185339Z`

결과:

* fresh unique remote root: yes
* hosts: `so003`, `so004`, `so005`, `so006`
* `so001`: 미사용
* source receipt: `fde2f492fa951863f1eabfdda287c22c17a72f00a12cb2b1062bbe17cde438bd` (7,555 files)
* remote source equality: 4/4 pass
* manifest hash: `ec79d67f323e0b36cb4c4920824deb411ac41a4b443825a3b9ebf576ddf13f2b`
* targets: 10/10 `SUCCESS`
* `PUBLISHED_LEGAL_EXECUTED`: 10
* unresolved/missing-result/unexpected/duplicate: 0
* published Spurious: 0/10
* observed confirmed Missing: 0, exhaustive coverage: false

초기 reused-root attempt는 Maven generated-resource 누락과 source mismatch 때문에 폐기했다. authoritative v4는 새 remote root, clean package, target-per-JVM=1, non-empty source receipt를 모두 gate로 묶었다.

### 2.2 AggregateBinary/AggregateUnary — 4-way isolated program 실행

보고서:

`docs/FED_PLANNER_AGGREGATE_P_L_R_AUDIT_2026-08-31_KO.md`

Artifacts:

* discovery: `audit-results/fed-runtime-aggregate-layout-e2e-v1-20260831T204737Z`
* campaign: `audit-results/fed-runtime-aggregate-campaign-20260831T205051Z`
* validation: `audit-results/fed-runtime-aggregate-validation-20260831T205614Z`

직접 P는 `ba(+*)` 15, `ua(+R)` 8, `ua(+C)` 8, `ua(+RC)` 5로 총 36개다. `so003`에서 동일 source snapshot을 네 개의 고유 stage/target tree로 복제하고 9-target shard 네 개를 동시에 실행했다.

* 36/36 `SUCCESS`
* `constraintSatisfied=36`
* runtime capability success rows: 208
* unresolved/missing-result/unexpected/duplicate: 0
* published Spurious: 0/36
* observed confirmed Missing: 0, exhaustive coverage: false
* focused Java: 7/7 pass
* family-specific main-source bug: 발견되지 않음

### 2.3 공통 privacy failure 관측성

보고서:

`docs/FED_PLANNER_PRIVACY_FAILURE_AUDIT_FIX_2026-08-31_KO.md`

privacy closure가 emitted occurrence의 모든 candidate를 제거할 때 기존 legality는 올바르게 fail-closed했지만, 완성된 `PlacementAnalysis`가 없어서 audit row가 남지 않았다. selector/legality를 바꾸지 않는 off-by-default `recordPrivacyFailure` hook을 예외 직전에 추가했다.

* 별도 schema: `fedplanner-candidate-space-privacy-failure-v1`
* pre-/post-privacy states/rules, exclusion, occurrence identity, failure reason 기록
* focused regression: 28/28 pass
* artifact: `audit-results/fed-planner-privacy-failure-audit-20260831T203831Z`

## 3. 독립 병합 검증

Root가 agent 산출물과 독립적으로 remote summaries/results를 읽어 target set과 hash를 재검증했다.

Artifact:

`audit-results/parallel-campaign-independent-verification-20260831T185536Z`

| Family | expected | actual unique | SUCCESS | missing | unexpected | duplicate | hash gate |
|---|---:|---:|---:|---:|---:|---:|---|
| WCeMM/WUMM | 10 | 10 | 10 | 0 | 0 | 0 | pass |
| AggregateBinary/Unary | 36 | 36 | 36 | 0 | 0 | 0 | pass |

WCeMM/WUMM source hash는 네 서버에서 동일했고, aggregate 네 shard도 동일한 non-empty source/manifest hash를 사용했다.

## 4. 새로 발견하여 수정 중인 실제 runtime 문제

Reorg direct fixture가 `diag(matrix)`의 COL input에서 reference와 6개 값이 다른 오류를 재현했다. 원인은 각 worker의 diagonal slice 계산이 아니라 `ReorgFEDInstruction.updateFedRanges`가 COL input의 원래 좌표를 사용하여 여러 vector 결과를 row 0에 겹치게 놓고 column 방향으로 배치한 데 있었다.

현재 수정은 다음 의미를 구현한다.

* ROW/COL axis input의 M2V 결과: row-offset을 누적한 ROW vector mapping
* FULL single input: FULL 유지
* BROADCAST duplicate input: 동일 full range/BROADCAST 유지
* equal broadcast range를 잃지 않도록 identity-keyed dimension facts 유지
* shared rule의 `rdiag(COL)` output도 `FED/FOUT/ROW`로 정정

transpose 4개와 rdiag M2V 4개 end-to-end 결과는 현재 모두 reference와 일치한다. 동일 helper가 V2M에도 쓰이므로 vector-to-matrix regression을 추가 검증한 뒤 forced campaign으로 확정할 예정이다.

## 5. 현재 병렬 실행 lane

1. **Reorg lane / so004**: transpose, rdiag M2V/V2M, ROW/COL/FULL/BROADCAST P/L/R 및 runtime range fix 검증.
2. **Ternary/Nary/Quantile lane / so005**: direct fixture, candidate P, forced R, Missing/Spurious 감사.
3. **MM/TSMM/MMChain lane / so006**: topology/layout별 direct fixture와 forced R 감사.
4. **Root**: target-set/hash 독립 검증, integrated regression, 보고서 병합.

모든 lane은 `so001`을 제외하고, 서로 다른 remote root/Maven target/result directory를 사용한다. 동일 JSONL 동시 append는 허용하지 않는다.

## 6. 다음 종료 조건

* Reorg runtime fix가 M2V와 V2M 회귀를 모두 통과한다.
* Reorg, ternary/nary/quantile, matrix-kernel direct P target을 isolated forced replay로 분류한다.
* source/manifest hash, exact target set, runtime capability, checksums를 독립 재검증한다.
* 최종 shared-worktree targeted/full regression 및 `git diff --check`를 통과한다.

