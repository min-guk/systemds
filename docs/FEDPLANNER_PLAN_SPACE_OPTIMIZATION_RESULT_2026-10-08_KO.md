# Plan space 후보 생성 최적화 구현 결과

상태: 아래 적용 범위의 구현과 최종 통합 검증 완료. 기준은 main `276f958efc91477e3d0e8a2a492929455dd77227`이며, 아래 구현은 아직 commit/push하지 않은 작업 트리 변경이다.

## 무엇을 바꿨는가

업로드 목적 배치를 원본 파일명·producer 출처에서 분리했다. 실제 worker endpoint, FType, 업로드할 값의 크기로 계산한 worker별 구간이 같으면 목적 배치를 공유한다. 값의 source, value version, 실행 scope, action 및 proof는 계속 구분한다. 기존 `DurableAnchorKey`라는 API 이름은 유지했다. Worker pool 발견은 기존 입력/배치 메타데이터에서 시작한다. 별도의 전역 worker registry를 도입하거나 모든 anchor 관련 API를 삭제한 구현은 아니다.

따라서 모든 ROW 배치를 하나로 합치지는 않는다. 같은 worker라도 `[0,3),[3,8)`과 `[0,4),[4,8)`은 다른 배치다. Source A와 B가 같은 배치에 있다고 해서 두 값을 합치지도 않는다.

| 단계 | 이번 구현 | 적용 경계 |
|---|---|---|
| 1. 원인 분석 | 선택적으로 켜는 중복 병합 계측과 route/revision 추적 | 기존 925건을 보존한 기준 실행에서 분류; 하나의 product 내부 중복까지 전부 식별하는 계측은 아님 |
| 2. 입력 정규화 | 실제 materialized output geometry로 relocation 목적지를 canonicalize | 알려진 실제 geometry 기준; 알 수 없는 shape는 구간을 추측하지 않음 |
| 3. 조기 pruning | 같은 source owner가 다시 나타나는 미래 입력 domain의 충돌을 prefix에서 검사 | 합법적인 source 선택으로 완성할 수 없는 분기만 제외; 다른 pool도 relocation으로 연결될 수 있음 |
| 4. 생성 중 deduplication | canonical 입력 선택·product descriptor를 재사용하고 이미 생성한 영역을 중복 생성하지 않음 | source-owner/action 객체 authority를 보존; hash만으로 동일성 판정하지 않음 |
| 5. 증분 처리 | 기존 선택의 교집합은 유지하고, 새 선택이 처음 등장하는 입력 위치별로 delta 생성; 삭제 선택은 철회 | relocation product 경로에 적용; closure 전체를 새 엔진으로 교체한 것은 아님 |
| 6. 표현 압축 | 독립 축과 동일 proof 집합의 Cartesian support를 입력별 목록으로 저장; hash/동일성/일부 union과 delta가 압축을 유지 | 혼합 action/proof나 source 공동 제약은 기존 명시적 표현 유지; 모든 selector 소비자의 전개를 제거한 것은 아님 |

## 기존 925건은 무엇이었는가

계측만 추가한 기준 Docker 실행은 rule 29개, realization 48개, 최종 support 184개, 누적 중복 병합 925건을 그대로 재현했다.

| 중복 유형 | 누적 건수 |
|---|---:|
| 같은 clause 객체를 다시 병합 | 37 |
| 같은 route·revision에서 내용이 같은 별도 객체를 병합 | 695 |
| 같은 revision의 서로 다른 route에서 병합 | 79 |
| 서로 다른 revision에서 같은 내용을 다시 병합 | 114 |
| 합계 | 925 |

미분류와 계측 overflow는 0이었다. 같은 route·revision은 하나의 product 호출보다 넓으므로, 695건을 전부 단일 Cartesian product 내부 중복이라고 해석하면 안 된다. 또 원본 A/B anchor 경로처럼 기존 exact key가 서로 달랐던 물리적 중복은 이 925건에 포함되지 않는다.

[원본 trace](../experiments/plan-space-implementation-20261008/baseline-duplicates/trace.json), [분류의 범위와 재현 명령](../experiments/plan-space-implementation-20261008/baseline-duplicates/CLASSIFICATION.md).

## 압축과 delta가 곱셈을 줄이는 방식

입력별 선택 수가 `b₁,…,bₖ`이면 명시적 조합은 최악에 `P=∏bᵢ`개다. 각 clause가 k개 입력을 저장하므로 생성 시간과 저장량에 `P×k` 항이 생긴다. 뒤 연산이 이전 support 목록을 무조건 다시 곱하는 것은 아니지만, 직접 입력의 선택 수와 closure 재생성이 이 비용을 키운다.

이번 delta는 다음처럼 새 조합을 겹치지 않게 나눈다.

```text
이전: A={a,b}, B={x}                 → 2개
현재: A={a,b,c}, B={x,y}             → 6개
재사용: {a,b} × {x}                 → 2개
새 영역 1: {c} × {x,y}              → 2개
새 영역 2: {a,b} × {y}              → 2개
```

새로 생성할 조합은 6개가 아니라 4개다. 삭제된 선택이나 교체된 action은 기존 조합에서도 제거한다. 일반 명시적 관계에서는 이 제거 검사가 기존 support 수에 비례할 수 있다.

독립 축이며 proof가 동일한 관계는 `P`개 clause 대신 `Σbᵢ`개 선택을 저장한다. 실제 production relocation 생성 메서드를 호출한 테스트에서 `100×100=10,000`개의 논리적 support는 입력 선택 200개와 descriptor로 보관했고 clause 객체는 0개였다. 한 선택을 삭제한 `99×100=9,900`, 양쪽에 선택을 추가한 `101×101=10,201`도 delta·병합 후 개별 clause를 만들지 않았다.

후보 receipt 순위도 요청한 clause의 길이별 조합 수를 계산해 구한다. 100×100 relation에서 서로 다른 두 후보의 순위를 조회해도 요청한 clause 두 개만 생성하며, 길이가 다른 2×3 예제의 모든 순위가 기존 명시적 정렬과 일치했다. 길이별 개수와 binding key는 group별로 한 번 계산해 재사용한다. 동일한 구조적 group key가 충돌하는 경우에는 정확한 tie 순서를 보존하기 위해 기존 경로를 유지한다.

이 결과는 **압축 경로의 생성·변경·비교와 일부 소비자를 검증한 것**이다. 기존 API를 통해 모든 clause를 순회하는 소비자는 결국 전체를 전개하고, 실제로 요청한 clause는 정확한 객체 소유권을 유지하려고 캐시한다. 전체 planner의 최악 복잡도나 모든 workload의 메모리가 선형으로 바뀌었다는 뜻은 아니다.

## 검증 중 발견한 정확성 문제

물리 endpoint·구간이 같다는 조건만으로 relocation을 없애면 CFG closure의 source 증명 경로까지 사라질 수 있었다. Early/late privacy pruning의 관계를 전수 비교하는 기존 테스트가 이 회귀를 잡았다. 목적 배치 정규화와 정확한 source authority 보존을 분리해 수정한다.

또한 runtime의 FOUT 캐시가 같은 shape의 불균등 ROW/COL 업로드도 균등 구간 signature로 저장하던 문제를 발견했다. 실제로 기존 구간을 보존하는 업로드는 그 exact layout signature를 사용하도록 수정했다. 같은 endpoint·크기라도 다른 partition을 캐시에서 잘못 재사용하지 않도록 하는 변경이며 runtime fallback을 추가하지 않는다.

## 최종 검증 및 실행 결과

동일한 DML, PRIVATE_AGGREGATE, 고정 Docker 이미지로 실행했다. 최종 JAR SHA256은 `d006cc1bcede08197fd1c1db97421aea3174b3ccfb1bc5d83f28cfe3c45d4b0c`다.

| 집계 예제 항목 | 기준 | 변경 후 |
|---|---:|---:|
| Rule | 29 | 29 |
| Realization | 48 | 45 |
| 최종 support | 184 | **74** |
| C의 support | 151 | **37** |
| C의 대표 BROADCAST 공급 product | 5×5 = 25 | **2×2 = 4** |
| 누적 relocation leaf | 214 | 32 |
| 누적 중복 병합 | 925 | 295 |

중간 빌드의 70개 support는 source authority 보존 회귀 수정 전 수치다. **최종 수치는 74개**이며 같은 물리적 배치라도 필요한 proof 경로는 유지했다.

반면 집계 전 예제는 realization 25→27, support 32→42로 늘었다. Canonical 목적 layout과 원래 source authority를 구분해 보존하면서 직접 경로와 동일 물리 배치의 relocation 증명이 함께 남기 때문이다. 모든 예제에서 표현 수가 단조롭게 감소한 최적화는 아니다.

두 예제 모두 rule/state/출력 layout/source binding/실제 전송으로 투영한 관계는 기준과 일치했다. 집계 예제는 양쪽 70개, 집계 전 예제는 양쪽 24개다. 이 투영 검사는 opaque proof signature를 비교하지 않으므로, 그것만으로 전체 합법성을 증명했다고 주장하지 않는다. Exact authority·privacy·CFG·증분 관계 회귀 테스트를 함께 실행했다. [집계 관계 비교](../experiments/plan-space-implementation-20261008/aggregated-relation-validation.json), [집계 전 관계 비교](../experiments/plan-space-implementation-20261008/private-relation-validation.json).

최종 집계 trace의 support는 모두 명시적 표현이다. 이 예제의 주요 개선은 목적 layout 정규화와 중복 작업 감소이며, factorization의 100×100 효과를 이 작은 DML에서 측정한 것처럼 섞지 않는다.

### 시간 측정

같은 probe와 Docker CPU 2개·메모리 4GB 조건에서 기준/변경본을 번갈아 각각 세 번 실행했다. 측정 구간은 parser/rewrite 이후 `buildAnalysis()` 호출이며 수치 연산 실행 시간은 아니다.

| 분석 시간 | 기준 | 변경 후 |
|---|---:|---:|
| 세 번의 중앙값 | 1,244.4 ms | 1,143.8 ms |
| 관측 범위 | 1,051.4–1,419.8 ms | 1,021.9–1,188.2 ms |

중앙값은 약 8.1% 줄었지만, 세 번째 비교에서는 변경본이 느렸다. 표본이 작고 범위가 겹치므로 안정적인 속도 개선이나 큰 LogReg workload의 해결을 주장할 근거로 충분하지 않다. 후보 표현과 반복 작업 감소를 확인한 결과로 해석해야 한다. [원본 시간 기록](../experiments/plan-space-implementation-20261008/paired-timings.json), [집계](../experiments/plan-space-implementation-20261008/timing-summary.json).

### 정확성 검증

- 변경 Java 소스·테스트를 컴파일하고 관련 **228개 JUnit 테스트 통과**, 기존 ignore 3개 유지.
- Early/late privacy pruning 네 fixture의 완전한 snapshot 비교 통과. 기존 대비 최상위 node/AVAILABLE/action domain 수를 보존한 뒤 정규화에 따른 golden 변경을 반영했다. [Snapshot 원본](../experiments/plan-space-implementation-20261008/privacy-snapshot-audit/sha256.json).
- 실제 DP worker 실행 `joint_branch_upload`, `joint_correlated_aa`, `joint_function_calls` **3/3 통과**. 각각 CP 기준과 SUM·NORM2·shape 및 함수 결과가 일치했다. Runtime conversion 위반은 없고, 업로드 수행 증거와 model proof도 통과했다.
- Python/Bash 구문, 문서 링크, `git diff --check` 통과.

[최종 JUnit 로그](../experiments/plan-space-implementation-20261008/tests.log), [빌드·소스 hash 검증](../experiments/plan-space-implementation-20261008/validation.json), [Docker 수치 검증](../experiments/plan-space-implementation-20261008/runtime/result.json).

이는 전체 Maven 테스트 결과가 아니다. 기준 main의 동결 class/dependency에 변경 소스를 컴파일해 검증했다. 별도 runtime helper 전체 테스트 시도에서는 host의 `localhost:15000` worker 연결이 필요한 한 테스트가 연결 거부로 실패했으며, 이 시도를 전체 통과로 계산하지 않았다. 실제 workload 검증은 repository 규칙대로 Docker dispatcher로 수행했다.

## 재현과 변경 파일

```bash
scripts/fedplanner/run_LAN_docker.sh --plan-space-example \
  --script scripts/fedplanner/examples/plan_space_aggregated.dml \
  --engine-target /home/mchoi/plan-space-implementation-20261008/final-engine \
  --output-dir /home/mchoi/plan-space-implementation-20261008/reproduce-new
```

`--output-dir`는 아직 존재하지 않는 경로를 사용한다. 같은 명령에 `--merge-diagnostics 2048`을 추가하면 최종 엔진의 상세 중복 계측을 켤 수 있다. 기준 925건의 분류는 계측만 추가한 별도의 기준 엔진을 사용한 기록이다.

주요 production 변경은 다음과 같다.

- `PlacementIdentity.java`, `PlacementCostSemantics.java`: 실제 목적 layout identity.
- `PlacementRelationClosure.java`: 목적지 정규화, 알려진 불가능 배치 제외, source 충돌 prefix, product 재사용·delta·factorized 생성.
- `FactorizedSupportClauses.java`, `PlacementAnalysis.java`: 압축 relation, authority 보존 병합, lazy receipt 순위.
- `SearchSpaceMetrics.java`: 선택적 중복 원인 계측과 상수 시간 bulk counter.
- `FEDFoutInstruction.java`: 실제 보존 구간과 일치하는 upload cache signature.

진단 runner/probe와 회귀 테스트도 함께 저장했다. 정확한 컴파일 명령과 소스별 SHA는 experiments의 `commands.json`, `source-sha256.json`에 있다. 전체 Docker 로그·동결 class/source는 `/grid/3/cofee-lm-sweep-mchoi-20260914/plan-space-implementation-20261008/runtime/final-dp/`에 보존했다.

## 남아 있는 확장 범위

전역 worker registry와 anchor API의 전면 교체, 서로 다른 proof/action 조합까지 압축하는 관계 표현, 모든 closure/selector 소비자의 전개 제거, 대규모 workload 성능 검증은 이번 검증 범위 밖이다. Global table에서 비용만 보고 후보를 버리는 dominance 정책도 변경하지 않았다. Source·공유·scope·실행 조건이 다른 선택은 계속 구분한다.
