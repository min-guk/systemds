# FedFirst · AggLocal 구현 품질 검토 — 2026-09-28

> 이 문서는 `fe758c413d4d156cec249248f99a744eaa45edb5`의 수정 전 기록입니다. 후속 구현·검증은 [v2 구현 보고서](FEDFIRST_AGGLOCAL_IMPLEMENTATION_2026-09-28.md)를 참고하세요.

## 1. 판정

**RECOMMENDATION: REQUEST CHANGES — 기본 정책과 보호 장치는 확인되지만, 무결함 또는 승인 가능한 상태로 판정하지 않는다.**

- 기준 HEAD: **fe758c413d4d156cec249248f99a744eaa45edb5**
- 질문: 현재 FedFirst/AggLocal 구현이 타당하며 실제 문제가 없는가?
- 검토 범위: 두 planner, adapter, 공통 first-feasible selector와 candidate-row 경로, AggLocal policy fact/완화, 선택·적용 경계 및 관련 회귀.
- 실행 코드 수정: **없음**. 테스트/POM/conf도 변경하지 않았다.
- 앞선 알고리즘 보고서의 APPROVE는 **설명이 코드와 일치한다는 정적 문서 검토**였으며, planner가 무결함이라는 승인이 아니었다.
- 확인된 재현 실패: 공통 selector의 합성 단일 component 4,096개 decision에서 기본 JVM stack으로 StackOverflowError.
- 확인된 긍정 증거: 관련 JUnit **21건 통과, 실패 0, 오류 0, skip 0**.
- 해석 한계: synthetic graph-only selector 경계에서의 규모 실패다. 실제 대규모 DML 프로그램 또는 Docker workload 재현은 수행하지 않았다.

### 독립 검토 상태

- code-reviewer: 17개 파일 검토 보고, **REQUEST CHANGES**.
- 독립 architect: **independent review unavailable**. native agent 생성이 thread limit으로 두 번 실패했다. 다른 역할을 architect로 가장하거나 리더 검토로 대체하지 않았다.
- Architectural approval gate: **BLOCK — 독립 구조 검토 증거 미확보**. 구조적 결함을 별도로 확정했다는 의미는 아니다.
- 최종 승인 불가의 실질적인 코드 근거는 아래 H1이며, 독립 architect 미완료도 별도의 검증 한계다.

## 2. 발견 사항

code-reviewer 분류: **CRITICAL 0 / HIGH 1 / MEDIUM 2 / LOW 3**.
확정 실패, 실제 관측된 지표 차이, 설계 tradeoff 및 테스트 공백을 구분한다.

### H1. 연결된 결정 수가 많으면 재귀 탐색이 스택을 소진함 — 재현됨, 미수정

**근거:** [PolicyFirstFeasiblePlacementSelector.java:475–520](../src/main/java/org/apache/sysds/hops/fedplanner/placement/selector/PolicyFirstFeasiblePlacementSelector.java#L475-L520), 특히 다음 그룹으로 재귀하는 512행.

시험 입력은 다음과 같다.

- 각 decision에는 CP/LOUT 및 FED/FOUT 두 상태가 있다.
- 이웃 decision을 data-input DOMINATES와 표준 directional CONJUNCTIVE 관계로 연결한다.
- CONJUNCTIVE 관계 때문에 모두 한 component에 속한다.
- all-FED 할당이 모든 그래프 제약을 만족한다. 실패하는 조합을 전부 탐색해야 하는 입력이 아니다.
- Oracle/candidate/실제 DML 입력 생성은 생략한 **공개 graph-only selector seam**이다. 따라서 그래프 제약의 합법성과 실제 runtime 물리 계획의 전체 인증을 혼동하지 않는다.

| 입력 | JVM 옵션 | 결과 |
|---|---|---|
| 연결 decision 512개 | -Xmx512m, 기본 stack | PASS |
| 연결 decision 4,096개 | -Xmx512m, 기본 stack | **StackOverflowError** |
| 같은 연결 decision 4,096개 | -Xmx512m -Xss4m | PASS — 원인 확인용 대조 |

환경은 OpenJDK 17.0.20.1이며 조회한 기본 ThreadStackSize는 1024 KiB다.
실패 trace에는 Solver.choose 프레임이 1,021개 기록됐다. Stack trace의 보존 길이 한도가 있으므로 이를 실제 전체 재귀 깊이로 해석하지 않는다.

**영향:** 두 정책이 공유하는 selector가 큰 결합 component에서 결과를 반환하지 못할 수 있다. 이번에 직접 재현한 호출은 기본 FEDERATED_FIRST graph-only selector이며, AggLocal wrapper를 통한 실제 workload 실패를 별도로 재현한 것은 아니다. 데이터의 잘못된 계산이나 privacy 누출을 발견했다는 의미도 아니다.

**최소 수정 방향:** 상태 탐색을 명시적 frame stack을 사용하는 반복형 DFS로 바꾸되, domain trail·singleton assignment·backtracking·완전 witness 검증을 보존한다. 4,096개 이상 component 및 기존 backtracking 회귀로 보호한다.

**스택 옵션 증가는 이번 수정안으로 채택하지 않았다.** -Xss4m 실행은 기본 설정 실패의 원인을 구별하기 위한 대조이며, 기본 실패 결과를 그대로 보존한다.

초기 DOMINATES-only 합성 입력은 512/4,096개 모두 통과했다. 이 관계만으로는 graph-only 경로의 결정들이 하나의 legality component로 묶이지 않기 때문에, 결합 component 실패를 반박하지 않는다. 초기/forbid-pair/표준 관계 probe의 소스와 원시 결과를 모두 보존했다.

### M1. AggLocal 전체 정책 완화의 계약·회귀가 부족함 — 위험, 불법 계획 재현 아님

**근거:** [HeuristicPlacementAdapter.java:65–109](../src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/HeuristicPlacementAdapter.java#L65-L109).

strict policy graph가 지정된 예외로 실패하면 모든 marker/local-prefix 투영 대신 base graph와 MOVEMENT_FIRST를 사용한다. 이는 현재 문서화된 **컴파일 시 정책 완화**이고, 공통 legality와 witness를 다시 확인한다. runtime fallback 금지 위반이나 불법 계획 수락으로 단정하지 않는다.

다만 일부 marker의 충돌 때문에 독립적으로 보존 가능한 다른 로컬 선호까지 해제될 수 있는 all-or-nothing tradeoff다. 실패 분류가 typed result가 아닌 예외 메시지 prefix에 의존한다. 검색한 테스트에서 fallbackUsed=true 또는 V3_RELAXED 전환을 직접 검증하는 전용 assertion은 확인하지 못했다.

**개선 방향:** 전체 완화를 의도된 계약으로 명시하고, strict 실패/완화 성공 및 privacy·witness 보존을 검증하는 positive regression을 추가한다. typed 정책 불가능 결과와 원인 증거를 남기는 것이 바람직하다. 부분 완화가 필요하다면 별도의 정책 변경으로 다뤄야 한다.

### M2. production candidate-row first-feasible 전용 검증이 부족함 — 테스트 공백

**근거:** [CandidateSelections.java:1142–1163](../src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java#L1142-L1163), [row 탐색:2279–2365](../src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java#L2279-L2365).

production 경로는 selectPolicyFirstFeasible이지만, 주요 AggLocal 테스트 일부는 결과를 다른 선택 알고리즘인 selectMaterializationMaximal과 비교한다.
[HeuristicLocalContinuationTest.java:288–297](../src/test/java/org/apache/sysds/hops/fedplanner/placement/HeuristicLocalContinuationTest.java#L288-L297).
또한 FedFirstRemoteInputPreferenceTest는 주로 reachability hint를 확인하며 최종 selector 결과를 직접 확인하는 테스트가 아니다.

이는 production 경로가 전혀 실행되지 않는다는 뜻은 아니다. 다만 첫 선호 row가 이후 source/anchor와 충돌하여 다음 row로 되돌아가야 하는 경우, component 병합 뒤 전체 witness 재탐색, 정책 row 순서 자체를 독립적으로 고정하는 검증이 부족하다.

**개선 방향:** 작은 완전 열거 기준과 first-feasible 정책 순서를 대조하는 전용 테스트 및 row backtracking 사례를 추가한다. 이번에 통과한 CandidateSelectionPruningOracleTest는 다른 maximal-helper 경로도 검사하므로 그것만으로 이 공백을 메웠다고 주장하지 않는다.

### L1. certificate의 component 수가 서로 다른 연결 정의를 사용함 — 값 차이 재현, 의미 계약 불명확

**근거:** [FedAllPlacementAdapter.java:84–95](../src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/FedAllPlacementAdapter.java#L84-L95), [연결 계산:204–260](../src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/FedAllPlacementAdapter.java#L204-L260).

boundComponents는 모든 constraint 연결성을 사용하고, graphComponentCount는 DOMINATES 연결성만 사용한다.
CONJUNCTIVE만으로 연결된 두 노드를 adapter fixture seam에 넣으면 다음 값이 실제로 나온다.

~~~text
decisions=2 graphComponentCount=2 boundComponents=1
~~~

**영향/해석:** 의도적으로 다른 지표일 수 있으나 필드명만으로 연결 정의가 구별되지 않는다. 선택 계획의 계산 오류로 분류하지 않는다.

**개선 방향:** structural component와 proof/constraint component를 명시적으로 구분하거나, 하나의 정의를 사용해야 하는 계약이라면 계산과 검증을 통일한다.

### L2. 정책 완화와 fallbackCount의 계층 의미가 불명확함 — 관측성 위험

**근거:** [FederatedPlannerFedHeuristicSinglePass.java:50–57](../src/main/java/org/apache/sysds/hops/fedplanner/fedHeuristic/FederatedPlannerFedHeuristicSinglePass.java#L50-L57), [counter 생성:94–110](../src/main/java/org/apache/sysds/hops/fedplanner/fedHeuristic/FederatedPlannerFedHeuristicSinglePass.java#L94-L110), [adapter certificate:157–184](../src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/HeuristicPlacementAdapter.java#L157-L184).

adapter의 fallbackUsed는 정책 완화를 기록할 수 있지만 invocation fallbackCount는 0 고정이다. runtime/legacy fallback만 세려는 의도라면 모순이 아니지만, 감사자가 counter만 보고 정책 완화가 없었다고 오해할 수 있다.

**개선 방향:** runtimeFallbackCount와 policyRelaxationCount처럼 계층을 분리하거나 receipt 계약에 의미를 명시한다. 이 항목은 complete relaxed invocation을 이번에 실행해 관측한 결과가 아니라 코드에 나타난 지표 계약의 위험이다.

### L3. candidate view 주석이 실제 구현과 다름 — 확인됨

**근거:** [PolicyCandidateSelectionView.java:26–56](../src/main/java/org/apache/sysds/hops/fedplanner/placement/selector/PolicyCandidateSelectionView.java#L26-L56).

주석은 materialization-maximal quotient 호출자로 설명하지만 실제 호출은 selectPolicyFirstFeasible이다.
**개선 방향:** 현재 first-feasible 정책 계약으로 주석을 정정한다. 실행 결과 자체의 오류는 아니다.

## 3. 긍정적으로 확인한 사항

- 가능한 상태를 고르는 것에서 멈추지 않고 정확한 candidate/realization/relocation witness를 요구한다.
- component별 상태 선택을 합쳐 witness가 없으면 전체 상태 탐색에서 다시 확인한다.
- analysis identity/fingerprint 및 적용 authority 검사가 존재한다.
- 선택과 atomic emission이 분리되어 있고, 적용 실패는 snapshot으로 복원한다.
- 이번 제한된 검사에서 privacy 우회, 불법 TRead/TWrite 선택, 외부 relocation authority 수락 또는 암묵적 runtime fallback을 재현하지 않았다.
- upstream과 정책이 다르다는 사실 자체를 구현 버그로 간주하지 않았다.

## 4. 이번 실행 검증

| 클래스/선택 메서드 | 실행 | 실패/오류 |
|---|---:|---:|
| PolicyFirstFeasiblePlacementSelectorTest | 13 | 0/0 |
| HeuristicProtectedNestedDemotionTest | 3 | 0/0 |
| HeuristicLocalContinuationTest — protectedFederatedVectorDoesNotForceIllegalLocalContinuation, indexedProtectedVectorDomainMatchesCanonicalDomain | 2 | 0/0 |
| RelocationSelectionsFirstFeasibleTest | 1 | 0/0 |
| HeuristicReentryOccurrenceEligibilityTest | 1 | 0/0 |
| CandidateSelectionPruningOracleTest | 1 | 0/0 |
| **합계** | **21** | **0/0** |

- 기존 public privacy 케이스 제외 지침에 따라 public/mixed-public fixture는 이번 실행 선택에서 제외했다. 테스트 파일에 Ignore를 추가하거나 assertion을 바꾸지 않았다.
- skip 0은 **선택한 21건의 결과**다. public 케이스까지 전부 실행했다는 뜻이 아니다.
- Maven incremental test를 수행했다. clean rebuild, 전체 테스트, 별도 lint/static-analysis 도구 실행은 하지 않았다.
- Docker runtime, 네트워크 장애, 수치 결과 대조, 성능 비교는 수행하지 않았다.
- source/test/POM/conf의 전체 tracked-file SHA-256 manifest가 실행 전후 동일하다.
- 기존 73 suites/576건 성공 기록은 별도 역사적 증거이며, 이번 21건과 합산하지 않는다.

## 5. 재현 자료

원시 자료 보존 위치:

**/home/mchoi/w1357-diagnostics/fedpolicy-review-20260928-n_odjzle**

- review-summary.json: 현재 HEAD, JUnit 합계, review 상태, probe 요약.
- command.txt, extra-command.txt 및 maven.log, extra-maven.log: 두 JUnit 실행.
- surefire-reports/: 이번 실행의 6개 XML. Surefire가 CLI reportsDirectory override를 적용하지 않아, 실제 기본 디렉터리에서 test property와 수정 시각을 확인한 뒤 해당 실행 파일만 복사했다.
- source-before.sha256, source-final.sha256: source/test/POM/conf 비변이 증거.
- PolicyProductionRelationDepthProbe.java, production-relation-probe-results.json 및 개별 log: 표준 관계의 규모 재현.
- PolicyCertificateProbe.java, certificate-probe-result.json: 두 component 지표 차이.
- java-flags.txt, probe-classpath.txt: JVM 및 현재 로컬 classpath.
- 초기 단순/결합 probe 소스와 결과도 보존했다.

실패 재현 예시(같은 checkout의 compiled classes와 dependencies가 유지되어 있어야 한다):

~~~bash
E=/home/mchoi/w1357-diagnostics/fedpolicy-review-20260928-n_odjzle
CP="$(cat "$E/probe-classpath.txt")"
javac -cp "$CP" -d "$E" "$E/PolicyProductionRelationDepthProbe.java"
java -Xmx512m -cp "$E:$CP" PolicyProductionRelationDepthProbe 4096
~~~

**최종 결론:** 기본 휴리스틱 방향과 safety 구조에는 근거가 있고 관련 회귀도 통과하지만, 큰 결합 계획의 재귀 스택 실패는 실제로 재현됐다. AggLocal 완화 및 candidate-row 선택의 전용 검증도 보완해야 한다. 현재 상태를 무결함으로 승인하지 않으며, 이번 작업에서는 발견·재현·기록까지만 수행했다.
