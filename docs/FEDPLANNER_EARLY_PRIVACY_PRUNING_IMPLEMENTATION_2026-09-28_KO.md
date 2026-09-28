# 공통 후보 생성의 조기 privacy pruning 구현·검증 보고서

작성: 2026-09-28. 대상: DP-local / FedFirst / AggLocal / DP-global의 공통 후보 생성.
계획: [조기 pruning 계획](FEDPLANNER_EARLY_PRIVACY_PRUNING_PLAN_2026-09-28_KO.md).

## 1. 구현 내용

P0–P3를 구현했다. 플래너별 후보 공간을 나누거나, 비용/선호에 따라 합법 후보를 잘라내지 않는다.
목표는 **같은 합법 후보·계획을 더 적은 불법 중간 객체/조합 생성으로 얻는 것**이다.

| 단계 | 변경 | 안전 경계 |
|---|---|---|
| P0 | privacy 완료 상태를 명시적으로 관리; 생성·Oracle·삭제 작업량 계측 | `PRIVACY_EXCLUDED` 행 존재를 stage 완료로 오인하지 않음 |
| P1 | Oracle의 실제 capability/profile을 얻은 뒤 불법 emission/action 할당 전에 기존 privacy kernel 적용 | derived FOUT은 같은 row의 합법 native source 필요 |
| P2 | 확정된 protected payload operand의 `ABSENT_LOCAL`을 Cartesian product 전에 제외 | metadata/함수 handle 및 UNKNOWN은 유지; consumer-local mask |
| P2 증거 | 원래 domain revision, canonical privacy/value authority와 compact rejection certificate 게시 | 생성 tuple과 certified rejection의 완전한 분할; 가짜 Oracle fact 없음 |
| P3 | 같은 owner에 대한 상충하는 exact source binding을 prefix에서 거절 | 서로 다른 owner의 geometry를 임의로 통일하지 않음 |
| P3 | action rebind 이후 immutable deletion epoch의 reverse-support worklist | OR sibling, 모든 reaching writer, duplicate-reference live count 보존 |

핵심 파일:

- `PlacementCandidateGenerator.java`, `PlacementRelationClosure.java`: generation gate, seed/full authority,
  privacy dependency worklist, product mask, binding prefix rejection.
- `CandidatePrivacyInputPruning.java`, `PlacementAnalysis.java`, `PlacementCandidateRuleResolver.java`:
  compact 증거, canonical identity/coverage 검사, 증거가 있는 요청만 typed `PRIVACY_EXCLUDED` 반환.
- `PlacementSupportRelations.java`: 삭제 전용 support fixed point.
- `SearchSpaceMetrics.java`, `PlannerCandidateSpaceAudit.java`: 작업량 counter와 별도 compact audit sidecar.
- `ExecPlacementPolicy.java`: 기존 정책을 객체 생성 전에도 사용할 수 있도록 동일 판정 kernel 재사용.

### 가장 이른 안전한 적용 지점

1. 일반 same-block DAG는 모든 선행 seed가 확정된 노드부터 source-first로 privacy를 계산하고 pruning한다.
2. CFG/value/function 구조를 얻으면 전역 privacy 값 분석을 실행하고 seed의 의미·identity를 검증한다.
3. **값 privacy가 확정됐다고 물리적 loop/function 후보가 닫힌 것은 아니다.** 실제 privacy closure 전에는
   seed만 사용하며, temporary bottom 또는 미완성 reaching writer를 영구 부재로 처리하지 않는다.
4. 실제 closure 이후 replay에는 전역 authority를 사용한다. 마지막 authoritative privacy/실행 가능성
   검증은 그대로 남는다.

가설적인 consumer profile은 정확한 source/revision authority가 없으면 pruning하지 않는다.
기존 profile matrix domain은 이미 `ABSENT_LOCAL`을 포함하지 않는 경로가 있으므로, 실제 후보 mask를
무조건 복사해 Oracle profile 공간을 잘라내지 않았다.

## 2. 복잡도와 기대 효과

- protected 입력 domain이 `d_i`개라면 방문 tuple은 기존 `∏d_i`에서 mask 이후 `∏d'_i`로 감소한다.
  거절 suffix는 계측/감사를 위해 다시 전개하지 않는다. 원래 domain과 금지 predicate를 압축 보관한다.
- 불법 emission은 realization/support/action을 만들기 전에 거절한다.
- privacy는 전체 graph 반복 scan 대신 유한 lattice의 변화가 영향을 주는 dependency만 다시 처리한다.
- support 삭제는 epoch당 reverse index를 만들고, 삭제된 reference가 영향을 주는 clause/requirement만
  전파한다. 살아 있는 OR 대안이 있으면 realization을 유지한다.
- 최종 합법 조합 자체가 많으면 그 product는 여전히 크다. 전체 알고리즘이 엄밀한 single-pass가 된 것은
  아니며, loop fixed point·replay·DP/Exact factor/solver 비용은 남는다.
- 선택적 P4(dense factor 이전 hard-support/표현 개선)는 이번 P0–P3와 분리하여 보류했다.
  FedFirst/AggLocal에 DP/Exact 비용 최적화 전처리를 추가하지 않았다.

## 3. 현재 확인된 검증

### 결정적인 작업량 감소 — 작은 protected DAG fixture

| 지표 | early off 기준 | early on |
|---|---:|---:|
| 방문한 input tuple | 26 | 24 |
| 실제 Oracle 호출 | 26 | 24 |
| certificate가 계측한 미생성 tuple | 0 | 2 |
| privacy로 피한 emission 할당 | 0 | 8 |

이 수치는 `A → B=A+1 → C=B*2 → sum(C)` fixture의 실제 counter다.
약7.7% Oracle/tuple 감소이며 **ML 전체 wall-clock 속도 개선을 의미하지 않는다**.
metrics on/off의 합법 관계도 동일하다.

### 의미 보존

- 기존 엔진에서 고정한 protected aggregate / metadata·handle / control flow / unknown shape
  네 corpus의 AVAILABLE row, emission, realization, OR support, binding/action, 합법 상태 SHA 불변.
- compact certificate의 원래 Cartesian domain = 생성 tuple ⊎ 거절 tuple을 독립 전수 검사.
  stale/foreign authority, 누락 protected operand, uncertified missing lookup은 거절한다.
- support worklist는 기존 full-pass 고정점과 cascade/cycle/OR/action/all-writer/duplicate-reference 및
  32 seed ×24 row 무작위 graph에서 differential 검증했다.
- binding prefix fixture는 같은 owner의 모순만 제거하여 leaves8→4, prefix15→11이며,
  서로 다른 owner geometry는 보존했다.
- 독립 literal **8개 전체 합법 계획** 보존, source-layout/consumer-geometry mutation은 각각
  정확히4개 계획의 누락을 탐지했다. 기존1344 support multiplicity를208로 재고정하지 않았다.
- native-local 비용 fixture의 factor 구조 SHA와 모든 raw double bits SHA는 기존 엔진과 동일하다.
  illegal ABSENT_LOCAL row 미생성으로 optimization receipt hash만 바뀐다. 비용 golden은 유지하고
  receipt 기대값만 변경하며, 해당 누락 row의 certificate/typed lookup을 직접 검사한다.

### 회귀에서 발견·수정한 문제

StepLm에서 초기 전역 privacy projection이 loop writer를 너무 빨리 제거했다. seed/full authority
경계를 분리하고 canonical seed map을 재연결해 수정했다. 해당 StepLm 포함32개 focused test 통과.
확장61개 class395개 회귀 재실행은 **384 통과 / 1 GLM 오류 / 10 기존 skip**이었다.
처음 발견한 비용 fingerprint 및 StepLm 회귀는 해소됐다.

GLM은 baseline JAR에서도 실패하는 기존 문제이며 현재는 private-aggregate binomial cbind에 대한
물리 후보 증거를 추가 분석 중이다. 이를 숨기기 위한 ignore나 privacy/runtime 완화는 하지 않았다.
따라서 **전체 회귀 green 또는 모든 workload 성공은 아직 아니다**.

matrix harness Python83개 통과, shell/Python syntax 및 `git diff --check` 통과.
최종 package 및 새 엔진 Docker smoke/전체 matrix 결과는 아래 후속 기록에 추가한다.

## 4. 증거·실험 경계

증거 루트:
`/grid/3/cofee-lm-sweep-mchoi-20260914/early-privacy-pruning-evidence/`

주요 파일: `regression.log`, `regression-seedfix.log`, `regression-seedfix-xml/`,
`seed-scope-repair-xml/`, `cost-probe/`, `steplm-seedscope-probe/`,
`decoded-support-probe/`, `python-matrix-83.log`.

기존 run05는 **59 성공 / 16 자연60초 timeout / 1 전환 중단 / 820 대기**에서 안전하게 종료했다.
exact-owned container 부재와 stage lease 해제를 확인했으며 이 결과를 새 엔진에 합산하지 않는다.

새 실험은 새 root와 새 JAR로 실행한다. 고정 조건:

- ML10 + P1/P2 + SliceLine ADULT/COVTYPE, workers1/3/5/7, LAN/WAN-light/mid/heavy, 4 planners =896조건.
- compile/runtime workload timeout **60초**, Docker 전용 `run_LAN_docker.sh`, timed cell 동시 실행1개.
- timeout을 성공시간60초 또는 infeasible로 취급하지 않는다.
- 동일 새 엔진896 compile 조건이 모두 통과하기 전 runtime을 시작하지 않는다.
- runtime gate 통과 시 logreg → l2svm → 나머지 순서. runtime fallback 없음.

## 5. 최종 검증·새 실행 후속 기록

- 마지막 fixture-local receipt/certificate 및 전체계획 검사:2개 class8개 통과.
- 독립 최종 scoped review: APPROVE, findings0.
- `mvn -q -DskipTests package` 성공. 위 회귀 실패1건을 감추는 전체 green 주장이 아니다.
- 새 JAR SHA: `100628da2bb3a489a43247d7d887d24aca81f41faa18ba98394e072a246d4dd2`.
- GLM: shared 함수 formal로 유입되는 UNKNOWN endpoint/cardinality 증거의 원인을 조사 중.
- 다음: commit/push 및 새 엔진 Docker60초 compile 검증. runtime은 아직 시작하지 않았다.

### 새 엔진 Docker smoke — 통과 (18:24 UTC 기록)

- 코드 commit/push: `a9b1ca711ed5cedb30644abba0d6a9c90a010fe1`.
- 새 root: `/grid/3/cofee-lm-sweep-mchoi-20260914/w1357-policy-matrix-20260928-run06`.
- DP-local / l2svm / worker1 / LAN, compile-only, timeout60초 통과.
- runtime-program 생성 완료, runtime audit mismatch0, 실제 workload 실행0.
  기존 엔진과 selected plan hash 동일. 컨테이너 cleanup 완료 및8개 host lease 해제 확인.

| 시간(초) | 기존 run05 | 새 run06 |
|---|---:|---:|
| 전체 compile | 11.722002 | 11.133612 |
| search-space 생성 | 6.494226 | 6.777722 |
| selection adapter planning | 3.973990 | 3.153528 |
| analysis 이후 전체 planning | 4.184812 | 3.375472 |

**각 엔진1회 관측이며 반복·교차 실행이 아니다.** 전체 compile은 낮았지만 search-space 시간은
이 표본에서 오히려 증가했다. 따라서 단위 fixture의 작업량 감소를 ML wall-clock 개선으로
일반화하지 않는다.896조건 결과와 추가 원인 분석이 필요하다.

현재1 통과/0 실패/895 대기, runtime0. 같은 frozen 엔진으로 전체 compile survey를 계속한다.
증거: evidence root의 `run06-smoke-verification.json`, run06 `compile-comparison.csv`/`summary.json`.

### GLM 별도 원인 분석 결과 — 미해결

기존 JAR와 현재 엔진 모두 GLM worker1의 binomial `cbind`에서 fail-closed한다.
`FULL` append를 허용하려면 exact single-partition 증명이 필요한데, shared 함수
`glm_log_likelihood_part.linear_terms`로 들어오는3개 actual 중 pre-loop actual 하나가
UNKNOWN이다. 이 상태가 `replace/exp`와 binomial formal로 전파되어 FED 후보가 생성되지 않는다.
privacy에 의한 CP/LOUT 제거 자체는 올바르다.

문제 경계는 `function/.builtinNS::m_glm/body/4/branch-if/1/branch-if/0:root-0/input-0`의
compiled-input/cardinality 의존성 부재다. 이 occurrence의 producer subtype과 누락 이유가 아직
확정되지 않아 추측성 패치를 하지 않았다. `deriveCompiledInputEdges`/`SinglePartitionFacts`
경계를 후속 조사한다. global worker1 가정, cbind 특례, privacy 완화는 해결책으로 채택하지 않는다.

증거: `early-privacy-pruning-evidence/glm-baseline-diagnosis/`.

### 전체 compile survey 재개 및 logreg60초 확인 (2026-09-28T18:29:25.571733+00:00)

동일 frozen engine으로 `--phase all --keep-going --compile-timeout 60 --runtime-timeout 60`을
재개했다. driver PID3151697/session69733, 로그는 evidence root의 `run06-full-driver.log`다.
현재 snapshot: compile **1 성공 / 2 실패 / 893 대기**, runtime0, compile gate=false.

DP-local/logreg/W1/LAN은 이번에도60초 timeout(rc124), cleanup 완료였다. 성공 compile/search-space/
planning 시간은 공란으로 유지한다. phase marker에서는 analysis 종료까지28.027129초와
planner 진입을 확인했으나 planner 완료는 없다. 기존 run05 동일 조건도60초 timeout이며
analysis phase30.188883초였다. 이 부분 marker를 성공 compile 시간으로 바꾸어 집계하지 않는다.

**이번 P0–P3 구현으로 모든60초 timeout이 해소된 것은 아니다.** DP 선택 단계 성능과 기존 GLM
cardinality 문제가 남는다. 전체 compile gate가 거짓이므로 runtime은 실행하지 않는다.
증거: `run06-continuation-verification.json`, `run06-logreg-phase-comparison.json`.
