# Oracle 비용 투영 후속 평가와 문맥 중복 검사 단순화

## 변경 기준

사용자의 후속 지시에 따라 source-only cost projection, 원본 binary64 primitive와 exact 합, UNKNOWN 보존 및 soundness 검사를 유지한다. Oracle guided native generation의 실제 효과를 재평가하고, context-sensitive 검사의 중복만 줄인다. Optimizer는 별도 읽기 분석으로 다룬다.

측정에 사용한 구현은 commit `2aff96c87f`에 보존했다. 이전 각1회 Docker 결과는 그 commit의 결과이며, 이 후속 변경의 성능으로 재사용하지 않는다. 원본 workspace를 수정하거나 LogReg/GLM workload를 추가 실행하지 않는다.

## 코드 수정 전 단순화 계획

대상은 `MemoRuleBuilder`의 owner rule 교집합 및 source-grounded privacy 전제 검사다. `MemoShapes`, `MemoProgramModel`, `MemoReachableContexts`, Oracle family 계약, optimizer는 변경하지 않는다.

1. 기존 owner intersection/caller/phase/UNKNOWN 및 source-grounded privacy 회귀를 먼저 실행한다. 중복 context 이후 UNKNOWN이 등장하는 순서도 검증한다.
2. Owner 전체에 하나의 교집합 상태를 둔다. 기존 full operator/worker/shape key 캐시가 돌려준 **동일 Result 객체**에 한해서 rule 집합의 중복 교집합을 생략한다. Shape만 같거나 비용이 같은 다른 Result를 합치지 않는다. Activation·Oracle 요청·coverage·진단은 계속 처리한다.
3. 교집합은 순서를 보존하는 set으로 유지하고, 서로 다른 결과와의 교집합에서 membership도 set으로 검사한다. 규칙 출력 순서를 바꾸거나 합법 variant를 제거하지 않는다.
4. Rule에 의존하지 않는 source-grounded privacy 전제는 occurrence당 한 번 계산한다. 실제 privacy legality는 기존처럼 rule별·occurrence별로 검사하며 UNKNOWN/DEFER 동작을 유지한다.
5. 독립 reviewer가 계획과 최종 diff를 검토한다. 변경 후 관련 회귀·Maven compile·diff 검사를 실행한다. 새 workload 측정 없이 구조적으로 제거한 중복과 아직 미측정인 효과를 구분해 기록한다.

### Fail-safe 경로 점검

- `commonRules == null`에서 owner의 일반 shape view로 Oracle을 조회하는 경로는 기존 동작이다. 앞서 발견한 unresolved coverage를 `complete &= ...`로 보존하므로 성공을 가장하는 fallback으로 분류하지 않는다. 이번 범위에서 변경하지 않는다.
- `sourceGroundedPrivacy == false`는 privacy 허용 판정이 아니다. 아직 source privacy가 고정되지 않은 문맥의 조기 pruning을 보류하고 모델의 문맥별 검사에 맡기는 경로다. 조건을 넓히거나 DEFER를 LEGAL로 바꾸지 않는다.
- Missing occurrence/activation/shape의 UNRESOLVED 및 Oracle diagnostic은 생략 대상이 아니다.

## 검증·평가 결과

코드 수정 전에 기존6 classes/49 tests를 통과했다. 신규 회귀를 포함한 owner/federated model16 tests도 변경 전 구현에서 통과해 보존할 동작을 고정했다. 이들은 model/unit regression이며 실제 LogReg/GLM workload 재실행이나 성능 표본이 아니다.

`MemoRuleBuilder`만 production 수정했다. 같은 owner의 동일 Oracle Result 교집합은 한 번만 계산한다. 첫 rule 목록 순서가 ordinal/tie 의미를 갖기 때문에 `LinkedHashSet`으로 유지한다. 서로 다른 결과와의 membership 검사는 hash set으로 바꿨다. Source-grounded privacy 전제는 최종 input roles를 얻은 뒤 occurrence별 한 번 계산한다.

이 변경은 Oracle cache miss나 활성 문맥 수를 줄이지 않는다. 같은 원본 결과의 반복 교집합과 rule에 무관한 전제 순회를 제거한 것이다. 시간 단축률은 새로 측정하지 않았다. 단위 비용·확률·source identity·privacy legality·UNKNOWN 및 Oracle의 full key를 변경하지 않았다.

계획 작성자/구현자와 별도의 reviewer가 계획 및 최종 diff를 읽었고 soundness 차단 문제 없음으로 판단했다. Identity 생략의 근거는 교집합 단조성이다. 한 번 A와 교집합을 구한 후 상태 I는 항상 A의 부분집합이므로, B 등을 처리한 뒤 다시 A가 와도 I∩A=I다. 이 논리는 coverage/diagnostic 생략을 정당화하지 않으므로 해당 검사는 생략 분기 바깥에 남겼다.

최종 검증 결과는 다음과 같다. 중간 suite와 최종 suite는 겹치므로 통과 수를 합산하지 않는다.

| 검증 | 결과 |
|---|---|
| Maven main/test compile | PASS,40.261초. 이후 reviewer의 필드 타입·fixture assertion 보강도 `javac --release17`로 재검증. |
| 최종 Memo/Oracle/공급·runtime 인접 회귀 | 43 classes/407 tests PASS. [명령](../experiments/oracle-cost-followup-20261014/final-unit-command.txt), [로그](../experiments/oracle-cost-followup-20261014/final-unit.log). |
| Source-only scaling | 실제 owner2/11/101/1,001개에서 해당 연산의 context1/primitive1/binding visit0 유지. |
| UNKNOWN/순서/privacy 추가 회귀 | 중복 known/unknown context의 순서, unresolved occurrence별 진단, rule ordinal 순서, ungrounded→grounded privacy 문맥 통과. |
| 정적 검토 및 형식 | 독립 soundness/diff 검토, `git diff --check` PASS. 별도 Java lint/static-analysis plugin은 pom에 없어 실행하지 않음. |
| 새 workload 성능/수치 검증 | 미실행. 이전 각1회 제한 유지. 이번 단순화의 시간 단축은 미측정. |

기존 suite44 classes/410 tests에서 known golden drift가 있던 `TrainingWorkloadOperationCoverageTest`5개를 제외하고 새 회귀2개를 더해407개를 실행했다. 제외한 기존 실패는 해결하거나 통과시킨 것으로 표시하지 않는다. Python harness는 수정하지 않아 이번에 반복 실행하지 않았다. 원본 primitive·optimizer·source projection production 코드가 보존됐는지도 diff로 확인했다.

아래 분석은 이미 완료한 각1회 Docker evidence와 소스 읽기만 사용했다.

## Oracle guided native generation 재평가

**근거:** LogReg native generation의 `determinantRegions=12,457`, `oracleEvaluations=12,457`, `prunedPrefixes=4,663`, `visitedNativePatterns=7,794`, `evidenceReuses=0`, `prefixRequests=0`이다. 12,457=4,663+7,794이므로 각 determinant 영역에 한 번 exact Oracle 질의를 했고, 그중 알려진 non-FED 영역4,663개는 callback 전에 제거했다. 이 실행에서는 Nary의 partial-prefix pruning이 사용되지 않았다.

`evidenceReuses=0`은 하나의 Oracle 증거로 여러 FED leaf를 발행한 사례가 없었다는 뜻이다. 제거된 non-FED 영역 하나가 생략된 축의 여러 concrete completion을 포함할 수도 있다. 현재 counter에는 그 배수가 없으므로 determinant 투영의 이익이 전혀 없었다고 단정할 수 없다.

| 기존1회 측정 | 기준 | 변경 |
|---|---:|---:|
| Catalog 요청(hit+miss) | 188 | 8,880 |
| Cache miss | 113 | 1,057 |
| Cache hit | 75 | 7,823 |
| Rules 단계(초) | 0.280817334 | 1.888837577 |

**추론:** 기존 shape-only 중복 제거를 없애고 정확한 activation/shape/call/phase 문맥을 검사하면서 요청량이 크게 늘었다. 이 변화는 rules 단계 증가와 직접 연결되는 조사 대상이다. 전체 시간/miss의 단순 비율만으로 Oracle 알고리즘 자체가 빨라졌거나 느려졌다고 판단할 수는 없다. 문맥 구성과 요청 분포도 달라졌기 때문이다.

**결정:** 지원 영역 stream 생성과 coverage를 보존하는 prefix 계약은 유지한다. 고차 Nary 회귀에서 의미 있는 pruning을 검증했고, LogReg 한 workload에서 prefix 사용0이라는 이유로 제거하면 이 이점까지 잃는다. 이전의 shape-only dedup을 복원하지 않는다. 이번에는 exact cache Result 재사용 뒤의 중복 교집합을 먼저 줄인다.

**미확정/후속:** Oracle 호출 전 exact-key 중복 제거가 필요하다면 `MemoNativeRules`의 단일 key 생성 경계를 재사용해야 한다. `operatorKey`를 RuleBuilder에 복사하지 않는다. 먼저 `prunedPrefixes`를 partial-prefix/non-FED-region으로 분리하고, 생략된 concrete completion 수 및 owner별 재사용 횟수를 측정해야 한다. 이번 변경은 이 새 계측이나 workload 실행을 추가하지 않는다.

근거 코드: [MemoNativeRules 캐시](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoNativeRules.java#L148), [Oracle 지원 영역 방문](../src/main/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacade.java#L691), [Nary 계약](../src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java#L3958). 측정값은 [이전 비교 JSON](../experiments/oracle-cost-projection-20261013/oracle-projection-docker-comparison.json)과 그 안의 raw result 경로를 따른다.

## Optimizer 별도 병목 분석

질문은 “동일한 LogReg 탐색 node 수에서 optimization25.20→39.08초가 된 이유와 GLM timeout 위치를 어디까지 알 수 있는가”다. 원인 확정에 필요한 profile은 수집되지 않았다.

| 우선순위 | 후보 | 신뢰 수준과 경계 |
|---|---|---|
| 1 | Movement cost callback 내부의 binding cost-context 순회와 객체 생성 | 중간~높음. 이번 변경으로 늘어난 구체적 경로지만 시간 기여율은 미측정. |
| 2 | Grouped cost factor 내부의 원본 primitive 호출·BigDecimal 변환·배열 복사 | 중간. 외부 평가 counter가 내부 반복을 숨기는 것은 확인됨. 기준 primitive 수가 없어 증가량은 미확정. |
| 3 | Context check 내부 순회와 cache-hit 전 key 할당 | 중간~낮음. 실제 반복 경로지만 solver 구현은 기준과 같아 단독 회귀 원인으로 볼 수 없음. |

**근거:** `MemoOptimizer.java`는 기준 `abb5a4822a`와 내용이 같다. LogReg의 nodes2,933,891, worklistVisits13,616,123, removedRules21,182,609, conditionalBoundQueries11,135,047, costEvaluations4,828, costCacheHits3,407, boundPrunes2,466,239와 exact objective도 같다. 이 수치는 탐색 공간이 늘었다는 설명을 지지하지 않는다.

**근거:** [movementGroupCost](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java#L643)는 cost tuple을 평가할 때 `visitActive`와 `visitBindingCostContexts`를 호출하고, 내부 leaf마다 문맥·activation 객체를 만든다. [CostTerm.evaluateExact](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoOptimizer.java#L59)는 binary64 primitive를 BigDecimal로 정확히 변환한다. [foldAndGroupCosts](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoOptimizer.java#L310)는 외부 factor 한 번 안에서 여러 primitive evaluator와 배열 복사를 수행한다. 따라서 `costEvaluations=4,828`이 primitive 호출도4,828회라는 뜻은 아니다.

**근거:** [MemoReachableContexts](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoReachableContexts.java#L60)는 check 호출 안에서 region guard·phase·caller activation을 해석한다. 외부 check 횟수60,690은 그 내부 문맥 수를 세지 않는다. Solver의 TupleKey/배열 생성도 cache hit 전에 실행된다.

**추론:** 같은 outer counter에서도 callback 내부 순회·할당이 늘면 시간이 증가할 수 있다. 단일 실행의 GC/JIT/CPU 변동도 배제할 수 없다. 정확한 primitive를 부정확한 합산으로 바꾸거나 correctness 검사를 제거할 근거는 없다.

**GLM의 한계:** cost projection까지 끝난 뒤 `model.optimize()` 구간에서300초 timeout이 발생했다. 이 구간에는 optimizer 생성, 초기 propagation, normalization/folding, tree/leaf 처리, residual search가 포함된다. 반환 후에만 통계를 출력하므로 현재 로그로 어느 하위 단계인지 분리할 수 없다. “GLM search 조합 폭발이 원인”이라고 확정하지 않는다. GLM primitive values49,892개는 LogReg66,132개보다 적어 그 수만으로 timeout을 설명할 수도 없다.

다음 원인 판별에 필요한 최소 계측은 optimizer 하위 단계 시간, raw factor applications, movement callback의 binding-context leaf 수, check 내부 environment 방문 수다. 별도 후속 측정에서는 JFR allocation sampling으로 BigDecimal/int[]/TupleKey/문맥 객체의 할당 기여를 확인할 수 있다. 이는 제안이며 이번에 실행하지 않았다.
