# GLM MOVEMENT_FACTOR_WIDTH: 원인 분리 및 정상 반환 shape 분석

- 일자: 2026-10-14. Workspace: `/home/mchoi/fedplanner-memo-20261013`.
- 기준: `codex/fedplanner-memo-20261013`, HEAD `4314a345c16c20752421ce6251bbc2f366c85825` **및 현재 미커밋 구현**. 아래 Memo 파일/라인은 작업 트리 기준이다.
- 최초 분석 범위(§1–7): 소스/기존 로그/테스트 assertion 조사, 기존 compiled component의 in-memory probe. 이 분석 시점에는 production/test/DML 변경 및 build/runtime benchmark를 수행하지 않았다. **후속 사용자 요청으로 구현·검증한 최신 상태는 §8**이며, 앞선 원본 증거는 보존했다.
- 결론: **196과 10은 비교할 두 width가 아니다. Width 10은 경고일 뿐이다. 실제 즉시 중단 원인은 정상 함수 반환 조건을 잃은 shape 분석으로 인한 native-rule coverage 미완료다.**

## 1. 오류 메시지와 실제 제어 흐름 — 확인한 사실

원본: `experiments/memo-migration-20261013/analysis-reuse-remaining-20261014/docker/remaining-fp-20261014-home-stage/cases/ml_glm/fed.log:11`.

```text
UNRESOLVED [...
 owner=974 occurrence=1303 ... op=r(r')
 COVERAGE_GAP CANONICAL_EFFECT_NOT_AUDITED pattern=[ROW]
 COVERAGE_GAP CANONICAL_EFFECT_NOT_AUDITED pattern=[COL]
 MOVEMENT_FACTOR_WIDTH origin=196 width=10]
```

`MemoProgramModel.java:88,479–514`:

- `origin=196`: `OriginIdentity.visibleBindingId()`이며 **binding ID**다. Width, 조합 수, worker 수가 아니다.
- `width=10`: lazy movement cost term의 `scope.length`, 즉 서로 다른 선택 변수 수다.
- 상수 8은 `MOVEMENT_FACTOR_WIDTH_DIAGNOSTIC`이다. 경고를 추가한 다음 **CostTerm을 정상 등록한다**. 폭에 따른 rejection/coverage 변경은 없다.
- `FederatedPlanMemo.java:88–90`은 non-OPTIMAL status에 **모든 diagnostics를 붙여** 예외를 만든다. 마지막 diagnostic이 원인이라는 해석은 틀렸다.

동일 GLM fixture를 기존 Java component로 재구성한 결과:

```text
incomplete native owner: 974 (only one)
status=UNRESOLVED
nodes=0, checkEvaluations=0, costEvaluations=0,
worklistVisits=0, conditionalBoundQueries=0, wideBoundRelaxations=0
```

`MemoRuleBuilder.java:143–146` → `RuleMemo.complete(owner)` → `MemoProgramModel.java:143–146` → `MemoOptimizer.java:228`로 이어진다. **이 probe에서는 movement 비용 평가/검색을 시작하지 않았다.** 이전 privacy full-scan Docker 대조의 동일 오류는 비회귀 증거이지 width 인과관계의 증거가 아니다.

## 2. 실제 width 10의 구성 — 확인한 사실

Group identity:

```text
kind=COMPILED_OWNER, visibleBindingId=196,
nativeDefinitionOccurrenceId=199, compiledOwnerId=154,
boundaryPhase=ORDINARY, callFrame=[16,7], exportChain=[]
```

Binding196은 `get_CG_Steihaug_point` 내부 `tmp` native definition이다. 두 호출 경로에 붙은 label/HOP 위치와 compiled owner를 혼동하면 안 된다.

| 선택 변수 | 종류 | 초기 domain 크기 | HOP/역할 |
|---|---|---:|---|
| 111 | consumer | 6 | transpose `z` |
| 120 | consumer | 13 | matrix multiply `p_CG` |
| 126 | consumer | 5 | matrix multiply `z` |
| 154 | producer | 7 | ternary `tmp`, origin196 |
| 182 | consumer | 7 | ternary `tmp` |
| 193 | consumer | 6 | transpose `z` |
| 202 | consumer | 13 | matrix multiply `p_CG` |
| 209 | consumer | 5 | matrix multiply `z` |
| 237 | consumer | 7 | ternary `tmp` |
| 989 | boundary | 4 | loop0/slot1 `z` head |

**8 consumers + 1 producer + 1 loop-head = 10.** `scripts/builtin/glm.dml:965–1008`의 CG body/trust-boundary paths에 대응한다. Group의 use/input 위치는 `(101,0),(113,1),(123,1),(184,0),(191,0),(203,1),(217,1),(265,0)`다. 모든 frame은 `[16,7]`이므로 이 사례는 서로 다른 callsite frame의 Cartesian product 때문에 넓어진 것이 아니다.

`MemoProgramModel.scope():1131–1140`은 consumer owners와 immediate source owners/loop heads를 합친다. Binding274 `SELECT([268,103],guard2,z)`의 다른 arm인 head103도 포함한다. `movementGroupCost():601–605`는 group identity 필터 **이전에** `layout(origin,selected)`를 읽는다. 따라서 현재 evaluator를 그대로 두고 head 변수를 scope에서 삭제하면 안 된다.

**해석:** shared movement activation은 각 consumer의 target partition과 정확한 source 선택에 의존하므로 fanout 결합은 정상이다. 다른 SELECT arm의 head는 origin별 dependency를 더 정밀하게 만들 여지가 있지만, scope 축소의 안전성은 별도로 증명해야 한다. Head 하나를 줄이더라도 폭은 9다. Threshold를 10으로 올리거나 edge별로 cost를 나누는 것은 GLM 원인 해결이 아니며, 후자는 동일 upload 중복 과금으로 최적해를 바꿀 수 있다.

## 3. 실제 중단 경로: 정상 반환을 잃은 shape — 확인한 사실

```text
caller transpose owner974 / occurrence1303
  inputBinding1609 CALL_RETURN(call16, .builtinNS::m_glm)
    binding1545 SELECT(guard21)
      true: binding1489 SELECT(guard22)
        true: binding1337 betas#1 -> matrix 8×1 EXACT
        false: binding1488 UNBOUND
      false: binding1544 UNBOUND
```

DML의 guard21은 `is_supported == 1` (`glm.dml:258`), guard22는 `isNaN == 0` (`:266`)이다. `betas`는 정상 body의 `:416`에서 정의된다. 두 else에는 `stop(...)`가 있다(`:450–451`). **실패 분기가 프로그램 전체에서 불가능하다는 뜻이 아니다. 그 분기에서 caller transpose에 도달할 수 없다는 뜻이다.** DML을 다시 바꿀 필요는 이 조사에서 발견하지 못했다.

기존 `MemoBindingResolver.visitJoint([completion1610], empty)` 결과:

| Guard21 | Guard22 | 결과 |
|---|---|---|
| false | 미평가 | `TERMINATED_AT_RETURN` |
| true | false | `TERMINATED_AT_RETURN` |
| true | true | `NORMAL_CONTINUATION` |

`VisitStatus.COMPLETE`, 3 environments다. `resolveCallReturn()/resolveCallCompletion():717–766`이 이미 필요한 구분을 한다. 현재 termination representation은 caller continuation 차단을 나타내며, 위 DML에서 발생하는 실제 오류/stop 자체는 그대로 보존해야 한다.

반면 `MemoShapes.resolvedBindingShape():614–629,665–671`은 제공된 guard가 없으면 두 SELECT arm을 합치고, CALL_RETURN에서 completion 조건 없이 child를 따라간다. `MemoRuleBuilder.build():124–140`은 native 호출에 call frame/loop phase를 전달하지만 이 정상 반환 guard를 전달하지 않는다.

| 기존 API에 전달한 context | Transpose input | Transpose output | Oracle |
|---|---|---|---|
| builder의 empty guards | UNKNOWN type, -1×-1 | MATRIX UNKNOWN, -1×-1 | coverage incomplete |
| 기존 resolver가 확인한 normal guards21/22=true | MATRIX 8×1 EXACT | MATRIX 1×8 EXACT | **complete=true, gaps=[]** |

정상 context에서 `MemoNativeRules.rules(viewAt(1303,context),3,hintAt(...))`는 FED `ROW→COL` native transpose를 반환한다. 이는 **Oracle/runtime transpose family 지원을 추가해야 하는 사례가 아니다.** Native transpose의 축 교환은 금지한 movement RESHARD와도 다르다.

`MemoNativeRules.ordinaryCanonicalEffect():550–561`은 transpose 지원을 이미 가지고 있지만, output axis가 unknown이면 먼저 UNKNOWN을 반환한다. 포괄적인 `CANONICAL_EFFECT_NOT_AUDITED` 진단만 보고 새 opcode 지원 누락으로 단정하면 안 된다. 정확한 shape/context로 이미 지원되는 path임을 구별해야 한다.

### 별도 확인된 Shape 표현 결함

`MemoShapes.commonShape():1544–1558`은 두 datatype이 UNKNOWN일 때도 "MATRIX/FRAME이 아님"으로 판정하여 `Knowledge.EXACT`를 만들 수 있다. 실제 binding1545는 `dataType=UNKNOWN, valueType=UNKNOWN, rows=-1, columns=-1, knowledge=EXACT`다.

이는 별도로 수정해야 할 표현 불변식 위반이다. 그러나 EXACT flag를 UNKNOWN으로 고치는 것만으로 8×1 shape가 복구되지는 않는다. 정상 반환 조건 전달이 주 수정 대상이며, 추측한 compiler dimension으로 덮어쓰는 해결은 안전하지 않다.

## 4. 최소 수정 방향 — 최초 제안 (후속 구현 결과는 §8)

1. **Native shape context에서 기존 normal activation/completion 재사용.** 해당 occurrence의 activation 및 실제 input def-use에 필요한 completion만 조회한다. 기존 guard/call/phase/cardinality correlation을 유지하며 정상 continuation에서의 output/input shape를 구한다. 모든 전역 guard/source 조합을 사전 열거하거나 새 analysis graph를 만들지 않는다.
2. **정상 경로가 여러 개라면 전부 검사.** 서로 다른 정상 shape를 임의 하나로 선택하지 않는다. 기존 동일 compiled owner rule intersection/coverage 계약을 유지한다. 미해결 completion은 미해결로 남기며 UNKNOWN, UNBOUND를 삭제하지 않는다. 실패 전 실행되는 함수 내부 HOP까지 제거하면 안 된다.
3. **Shape knowledge 계약 정정.** UNKNOWN datatype을 exact로 승격시키지 않는다. Scalar exactness와 partial matrix dimensions를 구분한다. Cache key에는 결과에 영향을 주는 exact call/guard/phase context를 유지한다.
4. **진단의 원인/경고 분리.** coverage owner와 실제 unresolved gate를 원인으로 보고하고 width는 부수 성능 warning으로 표시한다. 경고 threshold 변경은 원인 수정에 포함하지 않는다.
5. **Movement scope 정밀화는 후속 독립 작업.** exact origin별 identity 필터와 evaluator dependency를 함께 정리한 뒤 original shared-cost parity로 검증한다. 지금 폭10을 이유로 합법적인 rule/domain을 제거하지 않는다.

**가장 작은 구현 위치는 `MemoShapes.resolvedBindingShape()`의 CALL_RETURN 처리다.** 기존 `resolver.visitJoint([해당 binding], context, visitor)`는 completion을 이미 확인하므로 이를 lazy하게 방문하고, terminal origin은 caller shape join에서 제외하며 material origin의 `originShape(..., withEnvironment(...))`만 합치는 방안을 우선 검증한다. `VisitStatus.UNRESOLVED` 또는 정상 material origin 부재는 UNKNOWN으로 유지한다. `MemoRuleBuilder`에 전역 guard 열거를 추가하는 방안은 우선하지 않는다. Global worklist의 `callReturn():1360–1365`까지 같은 의미로 변경할지는 contextual repair/캐시 회귀를 확인한 다음 결정한다.

이는 수정 방향의 근거가 확보됐다는 뜻이며, shape repair만으로 GLM 전체 OPTIMAL/emission이 성공한다는 증명은 아니다.

## 5. 비용·복잡도 영향

- 현재 10변수의 초기 domain 곱은 `6·13·5·7·7·6·13·5·7·4 = 208,681,200`이다. **실제로 생성한 tuple 수나 동시에 feasible한 assignment 수가 아니다.** 전체 dense table은 만들지 않는다.
- `MemoOptimizer.computeConditionalCostBound():1048–1068`은 domain 곱이 support budget(기본 256)을 넘으면 certified minimum(이 movement term은 0)으로 완화한다. 후보를 폐기하거나 UNRESOLVED로 바꾸지 않는다. Exact selected cost는 이후 그대로 계산한다.
- 폭이 크면 lower bound가 약해져 search가 늘어날 위험은 남는다. 이번 GLM은 이 경로에 도달하지 않았으므로 폭의 실제 planning 영향이나 개선율을 측정했다고 주장할 수 없다.
- 정상 반환 shape 보강은 관련 completion/guard 환경 방문 비용을 추가한다. 최악 branch correlation 조합성은 남는다. Exact context/shape signature별 request-local 재사용과 관련 dependency 제한이 필요하다. 이 GLM 반환 경계는 3환경 중 정상 1환경이며 전체 HOP assignment product가 필요하지 않다.

## 6. 검증 자산과 다음 acceptance criteria

이번에는 아래 테스트 **소스/assertion을 조사했으며 재실행하지 않았다**.

| 기존 테스트 | 유지할 의미 / 추가 검증 |
|---|---|
| `MemoFunctionTerminationTest.missingMatrixReturnTerminatesBeforeLaterCallerWork` | missing bound return 이후 caller `AFTER` 미실행, 기존 실패 유지 |
| `MemoGlmFailureTest.unsupportedDistributionStopsBeforeCallerContinuation`, `invalidLogLinkResponseStopsBeforeCallerContinuation`, `supportedGaussianIdentityReturnsAndContinuesCaller` | 현재 DML의 명시적 stop 및 정상 반환 semantics |
| `MemoGuardProvenanceTest.actualAndFormalGuardAreOneDecisionAndDoNotAdmitMissingReturn` | actual/formal guard identity와 호출 문맥 |
| `MemoBindingResolverTest.callCompletionChecksEveryBoundReturnAndOrdersIndependentContinuation` | requested output 외 필요한 반환/completion 누락 방지 |
| `MemoNativeRulesTest.canonicalEffectsCoverTransposeAggregateAndBindAtTwoWorkers` | 이미 지원되는 native ROW→COL transpose |
| `MemoProgramModelTest.sharedMovementChargesTheUnionOfActiveDemands`, `movementActivationKeepsDefinitionPhasesDistinct` | 중복/분기 activation union 및 phase별 materialization 분리 |
| `MemoSharedSupplyRuntimeTest.sharedUploadMatchesOriginalCostScheduleExecutionAndCleanup` | 공유 upload 비용·실행·lifetime 일치 |

새 최소 fixture의 의미:

```dml
f = function(boolean q, matrix[double] X) return(matrix[double] Y) {
    if (q) { Y = X; } else { stop("invalid input"); }
}
A = f(q, X);
B = t(A);
```

- shape가 알려진 X의 **정상 호출 후 B**는 transpose capability를 잃지 않는다.
- q=false는 종료 semantics를 유지한다. 정상 경로로 간주하거나 PUBLIC/임의 matrix를 만들지 않는다.
- 두 정상 branch가 서로 다른 shape를 반환하는 반례도 함께 검사한다. 같은 함수 여러 callsite와 secondary missing return을 추가한다.
- UNKNOWN+UNKNOWN shape가 EXACT가 되지 않는 회귀가 필요하다.
- width>8 진단을 가진 작은 exact factor가 **OPTIMAL**에 도달하는 fixture를 추가하여 warning/unsupported를 구분한다.
- 변경 후 작은 fixture exhaustive feasible set/exact objective, original cost와 selected emitted schedule 일치, 기존 component suite를 검사한다.
- 마지막에만 기존 Docker harness로 실제 GLM의 full OPTIMAL/emission/numeric parity 및 LogReg 비회귀를 검증한다. 기존 raw logs/results는 보존한다.

## 7. Probe 재현 범위 및 남은 미확인 사항

기존 target/classes와 `/tmp/memo-maven-cp` classpath로 JShell을 사용했다. Docker의 `fed.dml`/config/CSV metadata를 읽고 경로를 메모리에서 치환했다. `constructHops`/`rewriteHopsDAG` 후 Facts/Shapes/Rules/Model만 구성했다. `MemoSources.acquire`의 기존 metadata provider overload에 총192×8, ROW 3×64×8, PRIVATE_AGGREGATE를 주입했으며 worker에 접속하지 않았다. 결과 파일을 쓰거나 DML runtime을 실행하지 않았다. 탐색 budget1에서도 coverage gate가 앞서 있어 search0이라는 사실을 확인했다.

핵심 판별 호출:

```java
resolver.visitJoint(List.of(1610), EvaluationContext.empty(), visitor);
// -> false: terminal; true/false: terminal; true/true: normal
var normal = new EvaluationContext(normalGuards, Map.of(), NO_SELECTION, 100, List.of());
shapes.inputShape(1303, 0, normal);  // 8×1 EXACT
shapes.shapeAt(1303, normal);       // 1×8 EXACT
oracle.rules(shapes.viewAt(1303, normal), 3, shapes.hintAt(1303, normal));
// -> complete=true, coverageGaps=[], FED ROW→COL exists
```

숫자 ID는 이 fixture/epoch에만 유효하다. 임의 ID 하드코딩은 수정안이 아니다. 전체 탐색은 실행하지 않았으므로 추가 feasibility, cost, lifetime, emission blocker 유무는 미확인이다. 현재 오류의 직접 원인 분석과 전체 GLM 구현 완료를 구분한다.

## 8. 정상 반환 shape repair 구현 및 검증 — 2026-10-14 후속 결과

**판정:** 정상 반환 shape/coverage 차단 수정은 구현하고 회귀 검증했다. **GLM 전체 완료는 아니다.** Docker GLM은 이전 coverage gate를 지나 최적화에 들어갔으나 case timeout 180초(rc124)로 끝났다. 제한된 component search에서는 coverage complete 및 feasible incumbent를 확인했지만 OPTIMAL/emission/runtime parity는 확인하지 못했다.

### 8.1 실제 변경과 보존한 계약

Production 변경은 `MemoShapes.java` 하나다. `MemoNativeRules`, `MemoRuleBuilder`, `MemoProgramModel`, `MemoOptimizer`, DML, runtime은 이번 수정에서 변경하지 않았다.

| 위치 (현재 작업 트리) | 변경 / correctness 계약 |
|---|---|
| `MemoShapes.resolvedBindingShape():650`, `returnedShape():688–718` | CALL_RETURN에서 기존 completion binding과 resolver를 재사용. `TERMINATED_AT_RETURN` 환경만 caller shape join에서 제외하고 모든 정상 환경을 join한다. 미해결 방문 또는 정상 반환 부재는 UNKNOWN이다. |
| `returnedPayloadShape():730–738` | 기존 inlined concrete-child와 shared-summary call-frame 연결을 유지한다. 전체 source origin을 다시 탐색하는 대신 completion만 확인한 후 기존 payload/loop induction 경로를 사용한다. |
| `returnedShape():696–697` | completion이 무조건 available이면 기존 payload fast path. 불필요한 재해석으로 이미 grounded인 loop backedge를 다시 열지 않는다. |
| `hasNormalContinuation():720–728`, `occurrenceShape():822–834` | 입력이 UNKNOWN이고 caller activation이 정상임을 확인할 수 없으면, 다른 정상 경로에서 얻은 frozen occurrence shape로 부활시키지 않는다. 일반 loop 입력 UNKNOWN 자체를 무조건 거절하는 가드는 넣지 않는다. |
| `ShapeContextKey/shapeContextKey():468–491` | payload만 보던 projected key를 제거. 전체 guard/loop/iteration/cardinality/call-frame/maxDepth와 head-bound 상태 snapshot을 사용한다. 다른 bound return의 completion 의존성을 잃지 않는다. |
| `commonShape():1581–1583` | UNKNOWN datatype/value type은 EXACT가 될 수 없다. |

새 graph, 전역 guard/source Cartesian product, 임의 round cap, width threshold 상향, UNKNOWN→PUBLIC, UNBOUND 삭제 또는 runtime fallback은 없다. 선택 rule/layout은 logical shape key에 넣지 않되, resolver가 실제 읽는 `headIsBound`는 private BitSet으로 snapshot한다. Mutable SelectionLookup 자체를 key로 보관하지 않는다. 이전 cache key/hash는 lookup 변경 후에도 불변이다.

**Cache 비용:** carried head 수 H, 이미 방문한 semantic context 수 Q에 대해 head snapshot은 key 생성당 O(H), 비트 저장은 O(H/word)다. Context map/list는 기존 immutable 객체를 재사용하지만 hashing/equality 비용은 context 크기에 비례한다. 전체 context를 남겨 projected key보다 Q가 커질 수 있다. 정확한 completion 의존 closure를 증명하지 않은 채 다시 projection하지 않는다. 이 변경이 peak memory를 줄인다고 주장하지 않는다. 무조건 contextual cache를 끄는 중간안은 반복 재귀 계산을 크게 늘려 폐기했다.

### 8.2 새 테스트와 검증 결과

`src/test/java/org/apache/sysds/hops/fedplanner/memo/MemoReturnShapeTest.java`의 9개:

1. 정상/stop 반환의 shape 및 기존 FED ROW→COL transpose capability (`:37`).
2. 명시적 terminal 환경에서 임의 shape를 만들지 않음 (`:60`).
3. resolver depth 미해결 시 normal shape를 빌려오지 않음 (`:80`).
4. 서로 다른 두 정상 반환 shape를 하나로 단정하지 않음 (`:98`).
5. shared 함수의 서로 다른 callsite shape 분리 (`:112`).
6. 다른 bound return이 missing이면 caller가 종료됨; normal/terminal query 순서 격리 (`:134`).
7. UNKNOWN+UNKNOWN join이 EXACT가 되지 않음 (`:162`).
8. conditional 함수 내부 loop의 FIRST/MIDDLE/LAST, ZERO/ONE/MANY 경계 (`:177`).
9. mutable selection의 head-bound 변경 snapshot 및 rule/layout-only cache 재사용 (`:216`).

기존 `MemoOptimizerTest.wideLazyCostRetainsTheExactOptimum():367`에는 10변수 lazy factor를 추가했다. Cache 0/4096에서 모두 exact optimum=1, 동일 tie-break assignment이며 wide bound relaxation도 발생한다. **이는 width>8이 unsupported가 아니라는 회귀이지, GLM의 넓은 factor가 빠르다는 증명이 아니다.**

근거 디렉터리: `experiments/memo-migration-20261013/normal-return-shape-20261014/`.

| 검증 | 결과 / 파일 |
|---|---|
| 변경 전 새 핵심 fixture | 6개 중 3개 기대 실패 확인: `baseline-regression-tests.log` |
| 최종 focused | 9개 PASS: `final-cache-regression-tests-v2.log` |
| 최종 Memo component (30클래스) | **266개 PASS**, `final-component-tests-v3.log` |
| 컴파일 | `mvn -DskipTests test-compile`, `final-build-v2.log` |
| Tiny exhaustive | 5 fixture, **2,604 assignments**; before/after feasible 수 및 exact 최적 비용 동일: `exhaustive-parity.json` |
| Static whitespace | `git diff --check` PASS; untracked 변경 파일은 별도 before snapshot diff도 보존 |
| 독립 코드 검토 | production 변경 APPROVE. 추가 cache snapshot 회귀까지 PASS. 전체 GLM 완료 판정과는 별개. |

초기 테스트 실패/잘못된 중간 가드/fixture 교정 로그도 보존했다. 특히 일반 UNKNOWN 입력까지 차단했던 중간안은 기존 nested-loop entry collect 테스트를 깨뜨려 위 activation 한정 검사로 수정했다. 전체 SystemDS 테스트 스위트를 실행했다는 뜻은 아니다.

### 8.3 Docker 실제 실행과 남은 blocker

```bash
scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e \
  --planner memo --case ml_logreg --case ml_glm \
  --case-timeout-seconds 180 --timeout-seconds 480 \
  --stage-root /home/mchoi/fedplanner-memo-docker-stage-20261013 \
  --output-root "$PWD/experiments/memo-migration-20261013/normal-return-shape-20261014/docker" \
  --run-id normal-return-shape-20261014
```

`docker/normal-return-shape-20261014/result.json`: class preflight 및 model proof(7 tests) PASS. 전체 harness는 **FAILED**이며 GLM 실패를 숨기지 않는다. Frozen production source hash는 `28397d4519a85b3b74ebdb14e4d45032416beb6e5c265c1ed7b39bf78eeaeaa3`로 최종 수정과 같다. Docker freeze 이후 추가한 9번째 test-only cache 회귀는 host component에서 검증했고 production은 바뀌지 않았다.

| Workload | 관측 결과 |
|---|---|
| LogReg | OPTIMAL, native FED 실행/lowering 확인. Planning **23.250950165초**(optimization **21.325129019초**), compilation 24.329887초, execution 2.630초. Nodes 2,933,891. CP/FED 8×2 model 최대 차이 **1.6653345369377348e-16**. |
| GLM | CP 8×1 model 생성. FED facts 0.053507376초, rules 0.397432298초, model 9.438471098초 이후 optimizer에서 **180초 case timeout**, rc124. 선택/최적화 완료 trace 및 FED model 없음. 180초를 순수 optimization 시간으로 간주하지 않는다. |

같은 GLM script/config/source metadata로 수행한 **bounded component probe** (`GlmCoverageProbe.java`, `glm-coverage-probe.log`, runtime/worker 실행 아님):

```text
COVERAGE_COMPLETE=true
STATUS=RESOURCE_EXHAUSTED INCUMBENT=true
nodes=10000, checkEvaluations=42181, costEvaluations=3988,
worklistVisits=51593, boundPrunes=7458, wideBoundRelaxations=77
```

`MOVEMENT_FACTOR_WIDTH origin=196 width=10` 경고는 그대로 남아 있다. 그 상태에서도 native coverage를 통과하고 feasible assignment를 찾았다. **현재 남은 문제는 최적성 증명을 제한 시간 안에 끝내지 못한 것**이다. Incumbent를 OPTIMAL로 바꾸거나 runtime에 내보내지 않았다. 이 진단만으로 width10이 탐색 지연의 단독 원인이라고 결론내릴 수 없다. 남은 branch/shared shape warnings도 원본 로그에 유지했다.

후속 작업은 동일 Docker에서 search/lower-bound/cache 작업량과 시간을 분리해 실제 병목을 확인하는 것이다. 이후 exact origin별 dependency 축소 또는 certified shared-cost lower bound 개선을 작은 exhaustive fixture로 입증하고 적용한다. Rule 임의 제거, 공유 비용의 무근거 edge 분할, 예산 소진 incumbent의 최적해 간주는 허용하지 않는다. 단일 실행으로 speedup 비율/peak memory 개선을 주장하지 않는다.

### 8.4 별도 발견한 기존 shape 수렴 공백

Conditional-return 함수를 **loop body에서 호출하는** 작은 fixture가 `MemoShapes.drain` eager 분석에서 끝나지 않았다. 이번 contextual repair 이전 source를 별도 classpath에 올린 동일 fixture도 25초 timeout으로 재현했다. `preexisting-loop-shape-convergence.md`, `baseline-loop-regression.log`, `regression-v2-stack.txt`에 최소 DML과 stack을 보존했다.

현재 8번째 테스트는 **conditional 함수 내부의 loop**를 검사한다. 이는 callback-in-loop fixture의 해결 증거가 아니다. 해당 eager shape 수렴 공백은 이번 수정으로 해결되지 않았으며, 임의 fixed-point 횟수 제한이나 fixture 삭제로 숨기지 않는다. GLM 현재 timeout은 eager drain이 아니라 model 생성 이후 optimizer이므로 두 문제도 구분한다.
