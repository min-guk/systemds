# Oracle 기반 native 생성·비용 투영 구현 결과

독립 workspace에 구현했다. LogReg를 기준/변경 각각 1회 비교한 결과, 비용 모델 생성은 25.2% 빨라졌지만 전체 planning은 26.85초에서 42.02초로 56.5% 느려졌다. 따라서 전체 성능 개선을 달성했다고 판단하지 않는다. GLM의 최종 Docker 판정은 아래에 별도로 기록한다.

## 기준과 작업 공간

- 요청 설계: [Oracle guided cost projection](FEDPLANNER_ORACLE_GUIDED_COST_PROJECTION_DESIGN_2026-10-13_KO.md).
- 독립 저장소: `/home/mchoi/fedplanner-oracle-cost-20261013`, branch `implement/oracle-cost-projection`.
- 기준 commit: `abb5a4822ab78cfd962073bba794da23fdcd6be6`. 원본의 당시 미커밋 Memo 변경을 포함해 복사·hash 검증한 snapshot이다. 원본 HEAD `4314a345c16c20752421ce6251bbc2f366c85825`만 checkout한 상태와 다르다.
- 원본 `/home/mchoi/fedplanner-memo-20261013`의 소스·빌드·실험·OMX 상태는 이 구현에서 수정하지 않았다.
- [구현 계획](FEDPLANNER_ORACLE_COST_IMPLEMENTATION_PLAN_2026-10-13_KO.md), [누적 세션 기록](SESSION_ISSUES_2026-10-13.md).

v9a 뒤에 이미 반영된 source-only projection 및 privacy 개선은 기준 구현의 성과다. 이번 변경은 남아 있던 layout 목록 생성, inactive shape guard 분할, rule/movement 경로의 문맥 누락을 대상으로 한다.

## 구현과 설계 대응

| 설계 계약 | 구현 |
|---|---|
| 전체 layout product 목록 미생성 | `MemoNativeRules.product/nativePatterns/cpPatterns`를 제거했다. CP metadata는 실제 지원되는 concrete rule을 stream으로 생성한다. |
| Oracle 지원에 따른 native 생성 | `OracleFacade.PreparedDecision.visitSupportedPatterns`가 coverage-safe partial prefix 또는 선언된 determinant 영역을 탐색한다. 알려진 CP rejection은 native pattern callback 전에 생략한다. 지원 영역만 나머지 입력 축으로 확장한다. |
| UNKNOWN 보존 | NOT_IMPLEMENTED, RULE_ERROR, missing required shape, Oracle 예외는 영역별 coverage 의무로 남긴다. 대표가 LOCAL인 경우에도 다른 completion의 FED 의무가 숨겨지지 않는다. |
| Runtime dispatch 보존 | 기존 first-match Oracle 결정을 사용한다. 모든 Ruleset 결과의 union이나 opcode별 임의 상한을 추가하지 않는다. |
| 공유 owner | Activation/caller prerequisite/정상 continuation/loop case를 먼저 해석한 실제 문맥별 rule 교집합을 구한다. 문맥별로 다른 rule을 선택하도록 양화사를 바꾸지 않는다. |
| 비용 primitive 이전 투영 | `MemoShapes.firstUnfixedCostGuard`가 binding DAG에서 실제 선택된 SELECT arm만 방문한다. 비용 통계가 invariant인 source-only subtree는 producer identity를 펼치지 않고 끝낸다. |
| 비용에 필요한 변수 | Shape, NNZ, 정수 shape parameter와 SEQ 실수 parameter, canonical guard, call frame, loop phase/case를 보존한다. |
| 반복문 | FIRST entry와 repeat backedge를 구분한다. Repeat 경계를 무조건 invariant로 생략하지 않고, 관련 guard와 비용 통계 증명을 보존한다. |
| 정확한 비용 합 | Native 의미 문맥별 adapter의 binary64 값을 primitive로 유지한다. Optimizer의 exact 합과 selected-cost audit을 비교한다. |
| Movement identity | Shape 문맥을 보강해도 exact value/version/export identity와 target을 유지한다. 같은 크기의 서로 다른 source를 하나의 공급 action으로 합치지 않는다. |
| 계측 | `[MemoNativeGeneration]`, `[MemoCostProjection]`, `[MemoSearch]`를 단계별로 출력한다. Docker harness가 UNRESOLVED/RESOURCE_EXHAUSTED 결과에서도 남은 counter를 수집한다. |

## 생성 알고리즘의 경계

Nary MULT는 coverage를 보존하는 partial infeasibility 계약을 명시한다. ROW/COL 등이 양립할 수 없는 prefix는 descendant native pattern을 만들기 전에 끝낸다. 다른 family는 선언된 determinant 및 shape selector로 Oracle 증거를 구하고, 지원된 영역에서만 concrete pattern을 발행한다. 선언이 없는 family는 모든 입력을 determinant로 취급한다. 이는 정확히 필요한 capability 질의를 보존하는 보수적 계약이며, family를 임의로 미지원 처리하지 않는다.

결정자 자체가 모두 독립적이거나 모든 concrete rule이 합법이면 지수적 크기가 남는다. 기존 concrete RuleMemo 표현을 유지하므로 이 하한은 이번 변경의 제거 대상이 아니다. Native local support는 전역 합법성의 증명이 아니며, exact binding·privacy·joint compatibility·search는 계속 필요하다.

Projection은 실제 guard 관계를 따라 내려간다. 중첩 `p ? small : (q ? medium : large)`는 네 개의 guard tuple 대신 세 개의 실행 문맥을 만든다. Shared canonical guard가 있는 두 입력은 같은 결정을 사용한다. 비용과 무관한 source-only 축은 native payload origin으로 join하지 않는다.

## 검증 근거

근거 폴더는 [`experiments/oracle-cost-projection-20261013`](../experiments/oracle-cost-projection-20261013/)다. 아래 최종 suite와 중간/lane별 통과 횟수는 겹치므로 합산하지 않는다.

- 기준: clean main/test compile PASS, 기존103 tests PASS. Immutable baseline의 추가 MemoFederatedModelTest8개 PASS.
- 최종 clean build: `mvn -o -B -DskipTests -Djacoco.skip=true clean test-compile`, PASS(40.834초). [빌드 로그](../experiments/oracle-cost-projection-20261013/final-compile.log).
- 최종 Java 회귀: 44 classes, 410 tests 중409 PASS/1 FAIL. [실행 명령](../experiments/oracle-cost-projection-20261013/final-unit-command.txt), [로그](../experiments/oracle-cost-projection-20261013/final-unit.log).
- 실패1개는 `TrainingWorkloadOperationCoverageTest.constructedAndRewrittenOccurrenceInventoryIsExact`다. 정확히 같은 frozen baseline `abb5a4822a`에서도5 tests 중 같은1개가 실패한다. Baseline의 `glm.dml:450-451`은 이미 `stop`인데 golden inventory는 이전 `print`/`termination_code` 노드를 유지한다. 이번 구현의 회귀로 분류하지 않으며, 무관한 golden을 갱신해 전체 통과로 표시하지 않았다. [정확한 baseline 재현 로그](../experiments/oracle-cost-projection-20261013/training-inventory-baseline.log). 다른 프로젝트 baseline으로 먼저 실행한 결과는 `training-inventory-wrong-baseline.log`로 격리했으며 증거로 사용하지 않는다.
- Python Docker harness:44 tests PASS, `py_compile` PASS. [로그](../experiments/oracle-cost-projection-20261013/final-harness.log). `git diff --check` PASS.
- Source-only scaling: A/B 각각2·11·101·1,001개의 실제 도달 owner에서 해당 연산의 비용 문맥1, primitive1, projection binding visit0. 작은11×11 reference만121 source 환경을 검증용으로 열거한다.
- 두 비용 통계 영역: 실제102개 source owner를 두 문맥/두 primitive로 투영한다. Source 수로 확률을 계산하지 않는다.
- 중첩 guard: 세 문맥과 binary64 primitive exact 합을 비교한다. FED fixture는 여러 native 선택 각각의 primitive를 검사하고 optimizer 결과와 selected-cost audit을 비교한다.
- Oracle lane: native/candidate/weighted/multi-output 인접60 tests PASS. 작은 exhaustive reference와 production streaming 결과의 rule 집합을 대조하고, 고차 Nary prefix pruning 및 UNKNOWN 보존을 확인했다.
- 독립 리뷰에서 찾은 repeat head의 NNZ guard 누락, owner의 shape-only dedup, 활성 문맥에 필요한 movement shape 투영을 수정하고 회귀로 고정했다. Loop 검증에는 T=3에서 실제 모델의 NNZ primitive5개와 정확한 가중합을 비교하는 사례가 포함된다.

## Docker 1회 비교

`run_LAN_docker.sh`만 사용했다. 같은 pinned image(`sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434`), W3,4 CPU,8 GiB, network none, 동일 입력/설정을 사용했다. Coordinator heap3 GiB/worker heap1 GiB, case timeout300초, runtime conversion 비활성이다. 별도 warm-up 없이 기준/변경 각각1회다. 단일 관측이므로 반복 실행의 평균이나 안정적인 speedup 추정치가 아니다.

- 기준 run: `baseline-abb5a482-logreg-glm-01`, [명령](../experiments/oracle-cost-projection-20261013/baseline-docker-command.txt), [요약](../experiments/oracle-cost-projection-20261013/baseline-docker-summary.json).
- 변경 run: `oracle-projection-logreg-glm-01`, [명령](../experiments/oracle-cost-projection-20261013/oracle-projection-docker-command.txt), [기준/변경 비교 JSON](../experiments/oracle-cost-projection-20261013/oracle-projection-docker-comparison.json).
- 원본 Docker evidence root: `/grid/3/cofee-lm-sweep-mchoi-20260914/fedplanner-oracle-cost-docker-20261013`.
- 사용자가1회씩으로 제한하기 직전 시작했던 baseline02는 최적화 완료 전에 중단했다. 부분 로그를 보존하되 완료 표본·성능 비교에서 제외했다. 이후 LogReg를 다시 실행하지 않았다.
- Docker의 frozen main/test source와 최종 workspace source는 `diff -qr`로 각각 차이0개를 확인했다. 해당 실행의 컨테이너/프로세스는 종료됐으며 재현에 필요한 manifest·result·FED 로그 SHA256을 비교 JSON에 보존했다.

### LogReg

| 측정 항목 | 기준(초) | 변경(초) | 해석 |
|---|---:|---:|---|
| Facts | 0.051033624 | 0.051285776 | 거의 동일 |
| Rule 생성 | 0.280817334 | 1.888837577 | 증가 |
| 비용/제약 모델 생성 | 1.280704018 | 0.958387339 | 25.2% 감소 |
| Optimizer | 25.200000352 | 39.075807642 | 증가 |
| 최종 plan projection | 0.042188273 | 0.046212504 | 소폭 증가 |
| **전체 planning(위5단계 합)** | **26.854743601** | **42.020530838** | **15.165787237초,56.5% 증가** |
| 전체 compilation | 27.995350 | 43.151268 | planning 포함 |
| 실제 실행 | 2.618 | 2.877 | 0.259초 증가 |
| compilation+실행 | 30.613350 | 46.028268 | 15.414918초 증가 |

두 실행 모두 OPTIMAL, native rules920개, search nodes2,933,891개, FED owners20개, supply action1개로 동일하다. Exact objective도 `1301.1526531142989790221751587629165844428591469750244868919253349304199218750`로 같다. 각각 CP와8×2 출력16개가 일치하고 최대 절대 오차는1.6653345369377348e-16이다.

Rule 생성에는 실제 활성 문맥별 검증이 추가됐다. Oracle cache miss는113→1,057개, hit는75→7,823개로 늘었다. 비용 문맥 투영의 효과와 이 correctness 보강 비용을 구분해야 한다. Optimizer의 탐색 node 수는 동일하지만 시간이 늘었으며, 이번1회 관측만으로 그 증가를 특정 원인에 귀속할 수는 없다. Search 공간 감소나 전체 speedup을 주장할 근거는 없다.

### GLM

기준은 CP만 완료했다. FED는 transpose owner974의 ROW/COL `CANONICAL_EFFECT_NOT_AUDITED`로 UNRESOLVED였으며 출력과 numeric parity가 없다. 함께 출력된 branch/shared shape 및 movement width 진단을 이 조기 실패의 직접 원인으로 오해하지 않는다.

변경 버전은 해당 coverage 단계를 통과해 optimizer에 진입했으나 **case timeout300초, FED returncode124**로 종료했다. Facts0.051333164초, rule 생성6.058418330초, 모델 생성3.324612751초까지 기록됐고 optimizer 완료/최종 projection/실행 기록은 없다. `memoSearch=[]`, `memoPlanning=[]`이므로 OPTIMAL/INFEASIBLE/RESOURCE_EXHAUSTED 중 어느 solver 상태도 관측했다고 주장하지 않는다. 이는 harness timeout이다.

CP는 완료했지만 FED 출력은 생성되지 않아 numeric parity도 미검증이다. GLM 모델 생성3.917575616→3.324612751초만으로 GLM의 전체 개선을 주장하지 않는다. 전체 Docker run은 LogReg PASS/GLM FAIL이므로 FAILED다. Model-only fixture 성공을 Docker 완료로 대신하지 않는다. 사용자 제한에 따라 두 workload 모두 추가 실행하지 않았다.

### 변경 버전 구성 계측

| 항목 | LogReg | GLM |
|---|---:|---:|
| Native determinant regions | 12,457 | 14,188 |
| Exact Oracle evaluations | 12,457 | 14,416 |
| Pruned native prefixes/regions | 4,663 | 5,284 |
| 발행 전 native patterns 방문 | 7,794 | 9,380 |
| Native enumeration published rules | 5,981 | 11,166 |
| Raw coverage obligations | 38 | 51 |
| Cost binding DAG visits | 339,816 | 366,990 |
| Cost relevant guard decisions | 6,598 | 6,152 |
| Cost support prefix checks | 15,966 | 19,867 |
| NO context 생략 | 19,892 | 64,516 |
| Cost UNKNOWN obligations | 0 | 0 |
| Invariant subtree 종료 | 149,854 | 133,751 |
| Projected contexts / primitive vectors | 6,422 | 9,517 |
| Primitive values | 66,132 | 49,892 |
| Primitive value payload(bytes) | 529,056 | 399,136 |

기준 실행에는 이 새 counter가 없어 감소율을 직접 계산할 수 없다. 과거 v9a의35만 이상 term과 현재 primitive 값을 같은 단위·동일 snapshot으로 간주해 감소율을 주장하지 않는다.

## 수치·계측 해석

- `primitiveValueBytes`는 `double` 배열 값의 payload 크기만 센다. JVM header, HOP view, 전체 모델 객체, peak RSS를 포함하지 않는다.
- `publishedRules`는 native enumeration에서 실제로 추가된 CP/FED rule 수다. 이후 owner 문맥 교집합 또는 optimizer propagation의 domain 크기와 구분한다.
- `coverageObligations`는 Oracle의 raw 진단 수다. 이미 무조건 CP rule로 대표되는 all-LOCAL 문맥의 missing-shape 의무도 포함할 수 있다. 최종 owner의 미해결 coverage 수와 같지 않으며, LogReg의 raw38개가 곧38개의 미해결 owner라는 뜻은 아니다. Cost UNKNOWN0 역시 전역 feasibility 완료를 뜻하지 않는다.
- `oracleEvaluations`는 실패한 시도를 포함한 exact Oracle 호출 수다. Cache hit와 determinant evidence 공유는 별도로 해석한다.
- Profile의0 확률은 runtime 경로가 불가능하다는 증거가 아니다. 기존 runtime-dependent loop의 ZERO/ONE feasibility case는 유지하고, typical-MANY 비용 estimator에서0 가중치인 문맥은 비용 primitive를 생략한다.
- 비용에 무관한 분할을 제거하면 과거 `c×0.5+c×0.5`의 마지막 ULP와 달라질 수 있다. ADR25에 따라 새 의미 문맥의 primitive와 exact 합을 기준으로 검증한다.

## 최종 판정

| 완료 기준 | 판정 |
|---|---|
| 독립 workspace, Oracle 지원 영역 기반 생성, primitive 이전 비용 투영 | 구현 완료. 원본 workspace는 수정하지 않음. |
| Source-only scaling과 관련 guard/NNZ/owner/UNKNOWN/movement 의미 회귀 | 최종 통합 회귀에서 검증. 테스트 범위를 넘어 모든 프로그램의 완전성을 증명했다는 뜻은 아님. |
| 빌드와 harness | PASS. Java 전체 suite에는 기준에서도 재현되는 golden inventory 실패1개가 남음. |
| LogReg 전체 계획·실행·numeric parity·exact objective | PASS. 단, 전체 planning은56.5% 느려져 성능 개선 목표는 미달성. |
| GLM 전체 계획·실행·numeric parity | **미달성. 최적화 단계에서300초 timeout.** |
| 전체 성능 목표 | **미달성. 비용 모델 단계의 개선과 전체 완료를 구분함.** |

후속 작업의 첫 대상은 native 비용 source product 재도입이 아니라 다음 두 부분이다.

1. Rule 생성에서 full-context 검증의 중복을 줄인다. Shape만 같은 이유로 문맥을 합치던 이전 dedup은 안전하지 않으므로 되돌리지 않는다. 선택된 Oracle family의 실제 determinant/shape 사실과 coverage 계약까지 포함하는 증거 key로 재사용 범위를 검증해야 한다. 이번 두 workload에서 `evidenceReuses=0`인 점도 함께 조사한다.
2. Optimizer의 conditional bound/support/propagation별 시간과 할당을 분리한다. LogReg node 수는 동일하고 GLM은 비용 모델 생성 뒤에 제한시간을 소진했다. 현 증거는 조사할 단계를 가리키지만 정확한 지배 원인까지 증명하지 않는다. 같은 revision 내 중복 검사와 bound 계산을 줄이되, 합법 rule 삭제·UNKNOWN의 NO 변환·불완전 탐색 결과의 OPTIMAL 표시는 허용하지 않는다.

Peak RSS와 상세 allocation profile은 이번 실행에서 수집하지 않았다. `primitiveValueBytes`만으로 전체 메모리 절감 또는 solver 병목 원인을 판단할 수 없다. 추가 프로파일 실행이나 반복 성능 측정은 수행하지 않았으며, 위1회 결과를 최종 측정값으로 보존했다.
