# Privacy pruning과 factorized downstream 구현 결과

요청한 두 변경을 구현했다. 입력 조합을 생성하는 단계에서 privacy로 불법인 가지를 oracle 호출 전에 제거하고, 독립적인 relocation support는 cost model과 DP까지 입력별 선택으로 유지한다. 최종 선택한 조합만 기존 authority를 가진 support clause로 복원한다.

**100×100 검증에서는 논리적 support 10,000개를 유지하면서 해당 product의 consumer 후보는 1개였다.** 해당 consumer의 다른 후보까지 포함한 domain 크기는 2였다. 분석 생성 직후 clause 객체는 0개, model·cost·DP 처리 후에는 대표 clause 최대 1개, 마지막 입력 조합을 선택하고 receipt를 검증한 뒤에는 총 2개였다.

이 결과는 모든 종류의 support에 대한 전면 압축을 뜻하지 않는다. 현재 적용 범위는 고정된 source owner·relocation action을 가진 독립 product다. 상관 관계나 비용 불변성을 증명할 수 없는 경우에는 기존 명시적 표현을 유지한다.

이는 현재 구현의 적용 범위다. 상관 support나 결합 비용도 입력별 인덱스와 공유 제약·비용 factor로 표현할 수 있다. 전체 boolean 표와 제약 기반 압축의 차이, 확장 방향은 [입력 인덱스와 합법성 제약 설계](FEDPLANNER_INDEXED_CONSTRAINT_RELATION_DESIGN_2026-10-08_KO.md)에 정리했다.

## 1. Oracle와 privacy를 함께 적용하는 방식

처리 순서는 다음과 같다.

```text
입력별 선택지를 한 개씩 선택
  → authoritative privacy가 금지한 protected ABSENT_LOCAL이면 그 가지 전체를 제외
  → 살아남은 조합에만 CandidateRuleKey 생성 및 runtime oracle 호출
  → oracle 결과에 기존 출력 privacy 정책 적용
  → 합법적인 emission만 생성
```

입력 privacy는 `GenerationPrivacy.protectedPayloadPositions`가 증명한 위치에만 적용한다. 출력 privacy나 집계 연산의 예외를 입력 단계에서 임의로 추측하지 않는다. Privacy 정보가 없으면 이 새로운 입력 gate로 조합을 제거하지 않는다. 출력에는 기존 `allowsEmission` 정책을 적용하므로 PRIVATE·PRIVATE_AGGREGATE·집계 공개 결과에 대한 의미를 유지한다.

기존 closure에도 입력 domain 마스킹과 출력 emission pruning이 있었다. 이번 변경은 **generator 자체도 그 계약을 보장하도록 한 것**이다. 따라서 이미 마스킹된 정상 호출에서 같은 pruning 효과를 새 성능 개선으로 중복 계산하지 않는다.

직접 generator enumeration 검증에서 마스킹되지 않은 4개 조합 중 protected 입력이 로컬인 3개를 제거하고 1개만 후보 생성 callback에 전달했다. 새 `generatorCombinationsRejected` counter는 기존 domain-mask counter와 별개로 기록한다. 실제 analysis의 early/late 비교에서는 합법 관계가 같았고 oracle/입력 leaf는 29→26, emission 할당 회피는 11이었다. 이 수치는 early pruning 활성/비활성 비교이며, 이번 guard만의 추가 개선량은 아니다.

관련 구현: [PlacementCandidateGenerator](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java), [SearchSpaceMetrics](../src/main/java/org/apache/sysds/hops/fedplanner/placement/SearchSpaceMetrics.java).

## 2. Downstream에서 무엇을 바꿨나

종전에는 저장 단계에서 factorized relation을 만들어도 `ExactPhysicalModel`이 clause를 전부 순회해 각각을 DP alternative로 만들었다. 분석 객체의 authority 검증, semantic fingerprint, 최종 receipt 검증에도 전체 전개 경로가 있었다.

예를 들어 입력 A와 B에 source 선택지가 각각 100개라면 종전 consumer 표현은 다음과 같다.

```text
C 후보: (A0,B0), (A0,B1), …, (A99,B99)  → 10,000개
```

이제 다음과 같이 표현한다.

```text
A의 기존 producer 변수: A0 … A99
B의 기존 producer 변수: B0 … B99
C의 consumer header: 실행·출력·공통 proof·입력별 전송 방법

제약 1: 선택된 A producer가 C의 A축 허용 집합에 속해야 한다.
제약 2: 선택된 B producer가 C의 B축 허용 집합에 속해야 한다.
```

C가 A와 B의 조합을 자신의 후보 번호에 다시 복사하지 않는다. Producer 자체의 support 제약은 producer의 factor가 검증한다. 최종 assignment가 A17/B23이면 원래 factorized relation의 그 위치에서 정확한 clause를 얻는다. 임의의 새 clause를 만들어 authority 검증을 우회하지 않는다.

| 경로 | 변경 내용 |
|---|---|
| 분석 생성 | 공통 witness와 각 입력 축의 source/action authority를 검증한다. 전체 product를 순회하지 않는다. |
| 모델 생성 | 독립 product의 header alternative와 producer별 membership factor를 만든다. |
| 비용 계산 | 고정 relocation action의 목적 partition/worker 수와 공통 witness를 사용한다. Source별 비용은 기존 producer 변수와 transfer factor가 부담한다. |
| Native supply | 아직 선택되지 않은 정확한 source reference는 deferred로 표현한다. 대표 source가 선택됐다고 취급하지 않는다. |
| Fingerprint | 공통 proof, witness, 축과 각 선택지를 직접 해시한다. 스키마는 `physical-semantic-dag-v3`이다. |
| 최종 선택 | 선택된 producer support key로 clause를 복원하고 selected-only validator로 소유권·도달 가능성·공동 제약을 확인한다. |
| 기존 shared-source 재인코딩 | Compact membership 모델에는 기존 변환을 적용하지 않고 이미 압축된 정확한 모델을 사용한다. |

주요 파일: [PlacementAnalysis](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java), [FactorizedSupportClauses](../src/main/java/org/apache/sysds/hops/fedplanner/placement/FactorizedSupportClauses.java), [ExactPhysicalModel](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java), [ExactPhysicalCostModel](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java), [PhysicalSemanticDagFingerprint](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/PhysicalSemanticDagFingerprint.java), [ExactPhysicalSelection](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalSelection.java), [CandidateSelections](../src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java).

## 3. 실제 검증 수치

### 100×100 경로

보호된 DML 입력에서 얻은 분석에 같은 producer owner의 서로 다른 source realization을 추가한 통제된 Java fixture다. 10,000개 선택을 실제 작은 DML이 자연적으로 생성했다는 의미는 아니다. 실제 production analysis/model/cost/Local optimizer/selection 코드를 호출한다.

| 항목 | 결과 |
|---|---:|
| 입력 축 선택지 | 100 + 100 |
| 논리적 support 조합 | 10,000 |
| 해당 product를 나타내는 consumer alternative | 1 |
| 해당 consumer 전체 alternative | 2 |
| 분석 생성 직후 clause 객체 | 0 |
| Model·cost·Local DP 이후 clause 객체 | 최대 1 |
| 비대표 조합 선택과 최종 receipt 검증 후 clause 객체 | 2 |

Local optimizer가 반환한 원래 최적 결과도 `ExactPhysicalSelection.create`로 검증했다. 별도로 마지막×마지막 입력 조합을 지정하고 모든 hard factor가 합법임을 확인한 뒤, 대표 clause와 다른 선택을 정확히 복원하는지 검사했다. 후자의 지정 assignment를 optimizer가 선택한 최적 결과라고 간주하지 않았다.

Fingerprint의 변경 전 회귀 검사는 같은 100×100 저장 구조를 해시할 때 clause 10,000개가 생성되어 실패했다. 변경 후 fingerprint만 계산하는 검사에서는 clause 생성이 0개다.

### 정확성·비용 비교

2×3의 6개 조합을 각각 명시적 모델과 압축 모델에서 비교했다. 모든 hard factor의 합법성, canonical 비용의 raw bits, 정확한 source/relocation receipt가 일치했다. 두 표현의 실제 최적 비용도 같았고 작은 fixture에서는 Local과 Exact의 최적 비용도 일치했다.

Source 가격 차이가 사라지는 오류를 검사하기 위해, 같은 입력 축에 실제 LOCAL 출력과 FED 출력 선택을 함께 넣고 전송 action은 고정했다. 두 선택의 비용은 서로 달랐으며 압축 후에도 그대로 유지됐다.

| 선택 | 모델 비용의 raw bits |
|---|---:|
| LOCAL source를 쓰는 검사 assignment | 4619578633137411850 |
| FED source를 쓰는 검사 assignment | 4620698875363191685 |
| 전체 모델의 최적 비용 | 4611695455012105748 |

위는 비용 모델 값의 비트 표현이며 실행 시간 측정값이 아니다. 테스트 코드는 [ExactFactorizedSupportPipelineTest](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactFactorizedSupportPipelineTest.java)에 있다.

### Docker runtime

`scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e`를 사용했다. Planner는 `local` / `compile_cost_based`다.

| 사례 | 결과 |
|---|---|
| `joint_branch_upload` | DP 실행 성공, CP 수치 fingerprint 일치, 업로드 실행 확인 |
| `joint_correlated_aa` | DP 실행 성공, CP 수치 fingerprint 일치 |
| `joint_function_calls` | DP 실행 성공, 두 함수 호출의 CP 수치 fingerprint 일치 |
| `joint_independent_private_ab_negative` | Privacy 위반 조합을 계획 단계에서 거부 |

4/4 통과했고 runtime conversion 위반은 없었다. Model proof와 14개 class hash preflight도 통과했다. 이 실행은 runtime 정확성 검사이며 대형 workload 성능 비교는 아니다.

## 4. 복잡도와 적용 한계

입력 축 선택지 수가 `n₁,…,nₖ`이면 명시적 support의 크기는 `P = ∏nᵢ`다. 이번 경로에서는 축 저장·binding 검증·factorized fingerprint의 기본 작업량이 선택지 합 `S = Σnᵢ`에 비례하고, product당 consumer header는 상수 개다. Canonical 정렬·키 해시·서명 처리 비용은 별도로 든다. 선택된 clause의 복원은 현재 축 안의 선택지를 검색하므로 `O(S)` 범위다.

**DP 전체가 다항 시간이 된 것은 아니다.** Producer domain과 다른 소비자의 공동 제약, 공유 전송, 그래프의 연결 구조에 따라 solver의 탐색·factor table은 여전히 커질 수 있다. 이번 변경은 조합 전체를 consumer 후보와 clause 객체로 먼저 복제하던 비용을 제거한다.

압축은 각 축의 source owner와 relocation action 객체 identity가 고정되고, 축끼리 owner를 공유하지 않으며, support key가 binding을 유일하게 정하는 경우에 적용한다. Input-authority 선택도 하나로 고정돼야 한다. 혼합 DIRECT/RELOCATION, action 변경, 모호한 support key, VALUE_MAP, joint/logical-boundary와 그 의존 경로, derived-output의 별도 선택 등은 기존 표현을 유지한다. 해당 경로의 합법 후보를 닫는 가드는 추가하지 않았다.

## 5. 검증 기록과 재현

최종 관련 Java 회귀 검사는 중복을 제외해 **375개 통과**, 기존 ignore 3개다. 첫 통합 실행의 371개 중 2개는 새 record 필드·private factory overload에 맞지 않는 테스트 adapter 때문에 실패했으며, adapter 수정 후 해당 suite 9개가 모두 통과했다. 추가 pipeline 검사 4개도 통과했다. 집계와 각 실행 로그는 아래 증거 디렉터리에 기록했다. 전체 Maven build/test를 수행한 결과는 아니다. 변경 전 baseline에서도 재현되는 privacy certificate 6건, PCA certificate 1건, 기존 hard-factor/native-local structure golden 불일치 2건은 별도 기존 실패로 기록하며 통과 수에 넣지 않는다.

- [실행 계획](FEDPLANNER_PRIVACY_FACTORIZED_DOWNSTREAM_PLAN_2026-10-08_KO.md)
- [검증 집계](../experiments/privacy-factorized-downstream-20261008/validation.json)
- [Pipeline 검사 로그](../experiments/privacy-factorized-downstream-20261008/pipeline-tests.log)
- [Fingerprint 변경 전 실패](../experiments/privacy-factorized-downstream-20261008/fingerprint-red.log)
- [Docker 결과](../experiments/privacy-factorized-downstream-20261008/runtime-result.json)
- [소스 SHA256](../experiments/privacy-factorized-downstream-20261008/source-sha256.json)
- [세션 이슈 기록](SESSION_ISSUES_2026-10-08.md)

전체 빌드와 runtime 산출물은 `/grid/3/cofee-lm-sweep-mchoi-20260914/privacy-factorized-downstream-20261008/`에 있다. `final/*command.json`은 실제 컴파일·JUnit 명령을 보관한다. Docker 실행 명령은 증거 디렉터리의 `runtime-command.json`에 보관한다. Commit/push는 수행하지 않았다.
