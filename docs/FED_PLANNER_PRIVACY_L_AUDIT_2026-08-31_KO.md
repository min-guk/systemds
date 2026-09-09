# Federated Planner 공통 Privacy Legality Domain 감사

**작성일:** 2026-08-31  
**대상 worktree:** `/home/mchoi/g014-fed-runtime-space-audit-0d769014-20260830`  
**범위:** source privacy 획득, whole-program 전파, candidate exclusion, 네 planner의 공통 domain 소비

## 1. 결론

현재 구현은 planner selector가 실행되기 전에 한 번의 `PlacementAnalysis`를 생성하고, 그 안에서 privacy를 occurrence/value-version 단위로 획득·전파한 뒤 불법 candidate를 제거한다. FedAll, Heuristic, DP, Exact는 서로 privacy를 다시 추론하는 것이 아니라 이 동일한 immutable analysis를 입력으로 받는다.

감사 fixture와 회귀 테스트에서는 다음을 확인했다.

* `PRIVATE` 연산은 `FED/FOUT`만 남고 `CP/FOUT`, `CP/LOUT`, `FED/LOUT`가 제거되었다.
* `PRIVATE` federated value를 직접 `print`하려는 프로그램은 selector 호출 전 `No privacy-safe physical placement`로 fail-closed했다.
* `PUBLIC`의 동일 프로그램은 legal placement를 유지했다.
* `PRIVATE_AGGREGATE`는 현재 정책상 coordinator의 **private materialization**인 `CP/LOUT`를 허용한다. 따라서 이 label이 모든 local placement를 제거한다고 서술하면 틀리다.
* 이번 감사에서 privacy 때문에 공개된 domain과 맞지 않는 planner 선택, 즉 확인된 `Missing`/`Spurious` legality 버그는 없었다.

다만 감사 관측성에는 한 가지 공백이 있다. privacy closure로 한 occurrence의 domain이 완전히 비면 분석 생성 중 예외가 발생하므로, 성공 후에만 호출되는 candidate-space audit에는 그 실패 occurrence가 기록되지 않는다. 이는 planner legality 오류가 아니라 **failure-side audit 누락**이다.

## 2. 공통 pre-selector 경계

최종 HOP rewrite와 memory-estimate refresh 후 `DMLTranslator`는 selector 종류를 결정하기 전에 canonical placement analysis를 bind한다. 이후 선택된 planner에 그 객체 자체를 전달하고 receipt가 동일 identity를 보존하는지도 확인한다.

* final-HOP boundary와 analysis bind: `src/main/java/org/apache/sysds/parser/DMLTranslator.java:358-396`
* atomic canonical authority 생성: `src/main/java/org/apache/sysds/parser/DMLProgram.java:57-79`
* FedAll 소비: `src/main/java/org/apache/sysds/hops/fedplanner/fedAll/FederatedPlannerFedAll.java:77-94`
* Heuristic 소비: `src/main/java/org/apache/sysds/hops/fedplanner/fedHeuristic/FederatedPlannerFedHeuristic.java:90-110`
* DP 소비: `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java:1667-1692`
* Exact 소비: `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FederatedPlanExact.java:41-68`

따라서 논문에서 주장할 정확한 경계는 다음과 같다.

> COFEE의 첫 번째 기여축은 selector 자체뿐 아니라 occurrence-aware whole-program placement authority를 selector 전에 구축하는 공통 전처리이다. 네 selector는 이 authority가 공개한 동일한 privacy-filtered feasible domain을 서로 다른 정책/탐색 알고리즘으로 소비한다.

이는 네 planner가 동일한 선택 알고리즘을 사용한다는 뜻은 아니다.

## 3. Privacy 획득

`resolveFederatedSourceMetadata`는 literal federated source마다 worker/range 정보를 만들고 privacy를 해결하는 공통 경계이다.

* planner registry를 변경하지 않는 immutable metadata: `FederatedPlannerUtils.java:315-343`
* literal addresses/ranges와 symbolic worker metadata 구성: `FederatedPlannerUtils.java:360-418`
* hermetic test override: `FederatedPlannerUtils.java:419-422`
* `local_matrix`는 local `.mtd`에서 읽고 없으면 `PUBLIC`: `FederatedPlannerUtils.java:424-435`
* remote source는 각 worker의 constraint를 조회: `FederatedPlannerUtils.java:437-463`
* 하나라도 유효한 constraint를 주지 못하면 planning abort: `FederatedPlannerUtils.java:464-470`
* 문자열 privacy parsing: `FederatedPlannerUtils.java:1461-1485`
* 여러 worker/input constraint의 lattice join: `FederatedPlannerUtils.java:1437-1453`

remote source에 대해서는 metadata 획득 실패를 `PUBLIC`으로 가정하지 않고 fail-closed한다.

## 4. Whole-program privacy 전파

candidate/function/materialization closure가 끝난 뒤 privacy closure가 실행되고, 그 다음에야 `PlacementAnalysis`가 생성된다.

* privacy 이전 snapshot과 closure 호출: `NeutralPlacementGraphBuilder.java:499-507`
* data/function/CFG constraint 기반 predecessor graph: `NeutralPlacementGraphBuilder.java:576-603`
* source metadata resolution: `NeutralPlacementGraphBuilder.java:605-623`
* occurrence identity에 대한 fixed point: `NeutralPlacementGraphBuilder.java:626-655`
* candidate emission filtering: `NeutralPlacementGraphBuilder.java:657-691`
* node state filtering과 `ReasonCode.PRIVACY`: `NeutralPlacementGraphBuilder.java:693-716`
* exact occurrence/value-version privacy facts 생성: `NeutralPlacementGraphBuilder.java:719-725`

전달 함수는 다음과 같다 (`FederatedPlannerUtils.java:1381-1435`).

| 입력/연산 | 파생 privacy |
|---|---|
| 입력 중 하나라도 `PRIVATE` | `PRIVATE` |
| `PRIVATE_AGGREGATE` + public-safe full aggregate | `PUBLIC` |
| `PRIVATE_AGGREGATE` + 기타 aggregate binary/quaternary 및 선택된 aggregate 연산 | `PRIVATE_AGGREGATE_TO_PUBLIC` |
| `nrow`, `ncol`, `length` | `PUBLIC` |
| 그 밖의 `PRIVATE_AGGREGATE` 파생 연산 | `PRIVATE_AGGREGATE` |
| private 입력 없음 | `PUBLIC` |

`PlacementPrivacyFacts`는 graph node와 fact의 occurrence/value-version identity 및 정확한 coverage를 생성 시 검증한다 (`PlacementPrivacyFacts.java:32-109`). `PlacementAnalysis`는 이 authority를 저장하고 exact-key lookup을 제공한다 (`PlacementAnalysis.java:1017`, `1777-1790`).

## 5. Placement policy와 candidate exclusion

privacy gate는 candidate가 공개되기 전에 `ExecPlacementPolicy.allowsCandidateEmission`으로 평가된다 (`ExecPlacementPolicy.java:115-139`). 현재 구현의 의미는 다음과 같다 (`ExecPlacementPolicy.java:247-369`).

| Privacy | 허용 의미 |
|---|---|
| federated source | 원래의 `FED/FOUT` anchor |
| `PRIVATE` | native `FED/FOUT`만 허용 |
| `PRIVATE_AGGREGATE` | private coordinator materialization인 `CP/LOUT`, native FED state, realizable한 `CP/FOUT`, 지원되는 forced-local FED path |
| `PRIVATE_AGGREGATE_TO_PUBLIC` | local/public competitor `CP/LOUT`, native FED state, realizable한 `CP/FOUT` |
| `PUBLIC` | runtime/shape feasibility가 허용하는 정상 physical alternatives |

추가로 node-level gate는 `PRIVATE`에 대해 `FED/FOUT` 이외를 독립적으로 제거한다 (`NeutralPlacementGraphBuilder.java:703-705`). emitted work의 legal state가 모두 사라지면 selector에 빈 domain을 넘기지 않고 즉시 예외를 발생시킨다 (`NeutralPlacementGraphBuilder.java:712-714`).

Exact도 이 domain 밖의 state를 만들 수 없다. 모델 생성 시 모든 alternative가 `node.legalAlternatives()`에 속하는지 identity로 검증하며 (`ExactPhysicalModel.java:98-106`), 실제 domain도 `AVAILABLE` rule의 emission 중 legal node state인 것만 포함한다 (`ExactPhysicalModel.java:221-291`). DP memo 또한 exact owner membership을 검증하며, enumeration은 analysis의 exact privacy fact를 읽는다 (`FederatedPlannerDpMemoTable.java:474-487`, `FederatedPlannerDpCostEnumerator.java:1043`).

## 6. JSON 감사 결과

정본 디렉터리:

`audit-results/fed-planner-privacy-l-audit-v2-20260831T202258Z`

| 항목 | 결과 |
|---|---:|
| 전체 candidate rule rows | 179 |
| `PUBLIC` rows | 121 |
| `PRIVATE_AGGREGATE` rows | 54 |
| `PRIVATE` rows | 4 |
| privacy 전후 변화 rows | 3 |

변화한 세 row는 모두 `PRIVATE`였다.

* `b(+)`: node states가 `CP/FOUT`, `CP/LOUT`, `FED/FOUT`, `FED/LOUT`에서 `FED/FOUT` 하나로 축소; emissions 4→1.
* `b(*)`: 동일 node 축소. 한 input signature는 emissions 1→0이 되어 `AVAILABLE`→`PRIVACY_EXCLUDED`; 다른 signature는 4→1로 유지.
* 제거 횟수: `CP/FOUT` 3, `CP/LOUT` 3, `FED/LOUT` 3.
* 이 fixture의 `PUBLIC` 121 rows와 `PRIVATE_AGGREGATE` 54 rows는 전후 state 변화가 없었다. 이것은 해당 연산들에서 policy가 합법 후보를 유지했다는 증거이지, privacy propagation이 실행되지 않았다는 뜻이 아니다.

무결성 hash:

* candidate JSONL: `18375c1aff0ffd643fff68b578b355ee80e241aa4bd0ff63bc2a838cef74b3ab`
* summary JSON: `34dd1d3349e2095cecd84e7252dd9bbe80e02d824aff77b7e21facedbf007ab9`

## 7. 회귀 검증

정본 디렉터리:

`audit-results/fed-planner-privacy-l-regression-20260831T202603Z`

실행 결과는 총 12 tests, failure/error/skip 0, exit code 0이다.

| Test class | tests | 결과 |
|---|---:|---|
| `SharedPrivacyPlacementAnalysisContractTest` | 8 | pass |
| `SharedPrivacyMovementLegalityAuditTest` | 2 | pass |
| `FederatedPlanLocalCostPrivacyConstraintTest` | 2 | pass |

새 regression probe는 다음을 직접 고정한다.

* strict `PRIVATE` federated input의 `print(A)`가 selector 전에 실패
* 동일 `PUBLIC` input의 `print(A)`는 분석 성공

파일: `src/test/java/org/apache/sysds/hops/fedplanner/placement/SharedPrivacyMovementLegalityAuditTest.java:39-55`

## 8. 발견한 관측성 공백과 최소 수정안

### 증상

strict `PRIVATE` direct-print 테스트는 의도대로 fail-closed하지만, 그 실패 occurrence는 candidate JSONL에 남지 않는다.

### 근본 원인

`PlannerCandidateSpaceAudit.record(...)`는 완성된 `PlacementAnalysis`를 요구하고 성공한 build의 끝에서만 호출된다 (`NeutralPlacementGraphBuilder.java:556-560`, `PlannerCandidateSpaceAudit.java:75-130`). 반면 privacy로 node domain이 비면 그보다 먼저 `NeutralPlacementGraphBuilder.java:712-714`에서 예외가 발생한다.

### 최소 수정 제안

기존 selector/planning 의미는 변경하지 않고, 예외 직전에 off-by-default인 failure-side audit hook을 호출한다.

예시 API:

```java
PlannerCandidateSpaceAudit.recordPrivacyFailure(
    node.key(), privacy, node.legalAlternatives(), legal,
    exclusions.values(), "NO_PRIVACY_SAFE_PHYSICAL_PLACEMENT");
```

기록 필드는 occurrence, effective privacy, pre-privacy states/rules, retained states/rules, failure reason이면 충분하다. 이 변경은 관측만 추가하며 candidate를 추가하거나 selector 동작을 바꾸지 않아야 한다.

## 9. 남은 범위와 병렬화 가능한 후속 작업

이번 결과는 공통 privacy-L 경계가 존재하고 대표 fixture에서 올바르게 작동함을 증명하지만, 모든 FED instruction/opcode/FType 조합에 대한 완전성 증명은 아니다. 기존 광범위 audit corpus는 `PRIVATE`가 거의 없어 strict legality coverage가 부족하다.

가장 효과적인 병렬화는 동일 coordinator/run을 여러 agent가 건드리는 것이 아니라, 독립적인 opcode family를 so002–so006에 고정 배정하는 것이다. so001은 사용하지 않는다.

| 서버 | 독립 campaign |
|---|---|
| so002 | binary/unary/aggregate |
| so003 | indexing/reorg/append |
| so004 | matmul/quaternary/parameterized builtin |
| so005 | transient/function/branch/loop |
| so006 | multi-return/transformencode/sink 및 failure-side audit |

각 campaign은 `(occurrence, input signature, privacy)`별로 다음 세 집합을 기록해야 한다.

* `P`: candidate builder가 selector에 공개한 states
* `L`: privacy 및 whole-program consistency를 만족하는 states
* `R`: HOP→LOP→instruction→runtime이 실제 실행 가능한 states

판정은 `Missing=(R∩L)-P`, `Spurious=P-(R∩L)`로 통일한다. 서버별 JSONL을 immutable artifact로 수집한 뒤 occurrence/replay hash로 합쳐야 하며, 같은 결과 디렉터리에 동시 append해서는 안 된다.

## 10. 최종 판정

* **확인된 shared privacy-domain 구현:** 있음.
* **네 planner의 동일 authority 소비:** 확인.
* **대표 strict PRIVATE candidate exclusion:** 확인.
* **대표 strict PRIVATE sink fail-closed:** 확인.
* **현재 확인된 privacy Missing/Spurious bug:** 없음.
* **확인된 결함:** 실패 occurrence가 candidate audit에 기록되지 않는 관측성 공백.
* **다음 병목 해소 방법:** failure-side audit 추가 후 opcode-family별 strict privacy campaign을 so002–so006에 병렬 분산.
