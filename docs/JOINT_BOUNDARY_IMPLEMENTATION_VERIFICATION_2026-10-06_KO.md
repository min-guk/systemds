# TW/Binding/TR 보존, 분기 이동과 공동 입력 관계 구현·검증

기준 저장소: `/home/mchoi/w1357-paper-aligned-refactor`, HEAD `0b51cc3ef883b9edb284cd4ae0f2b0358a38c592` 및 작업 트리 변경. 설계 기준은 [구현 방안](TW_BINDING_TR_CONVERSION_AND_JV_IMPLEMENTATION_2026-10-06_KO.md)이다.

**현재 상태: `origin/main` `d57bca99d9`까지 통합 및 검증 완료.** 최종 결과는 Java **379 PASS / 기존 제외 4개 / 실패·오류 0**, Python **18/18**, Docker **12/12 PASS**다. 작업 위치는 `/home/mchoi/w1357-joint-main-20261006`이다. 1∼7절의 Java 306개·Docker 12개 결과 및 병합 전 충돌 분석은 이전 빌드의 기록이며 최신 결과는 8절에 구분했다. 기존 테스트 제외와 지원 한계는 아래에 명시했다. 상세 문제 기록은 [세션 기록](SESSION_ISSUES_2026-10-06.md)에 남긴다.

**병합 전 origin/main 점검 기록:** 최초 결과는 HEAD와 당시 작업 트리의 검증이었다. 당시 전체 dirty snapshot의 격리 병합 검사에서 24개 파일의 충돌이 확인됐고, 최신 main의 비용 회귀를 이전 빌드에 적용하면 6개가 실패했다. 이 문제를 해결하는 실제 통합은 뒤의 8절에 기록한다.

## 1. 구현된 동작

순수 전달 구간의 TW/Binding/TR은 계속 `<CP,LOUT>` 또는 `<FED,FOUT>`를 보존한다. 서로 다른 배치가 필요하면 이동 결과를 전달한다. 일반 계산 연산의 실행 위치까지 전달 노드와 같게 강제하지 않는다.

| 구간 | 구현 |
|---|---|
| if/else 합류 | 살아 있는 행렬 변수의 분기 출구에 identity 가능한 `_PLACEMENT` 입력 경계를 만든다. else가 없는 경우 기존 값을 전달하는 경로도 포함한다. |
| 이동 후보 | 경계 입력에서 identity, 허용된 LOCAL, upload/재배치를 기존 후보·비용·선택 절차로 검토한다. 선택된 이동만 lowering한다. |
| 함수 인자 | 필요한 LOCAL 변환을 호출 인자 경계 앞에 명시적으로 수행한다. 실제 인자가 같더라도 call-site binding identity는 구분한다. |
| 함수 반환 | 해당 호출의 반환 정의와 caller 결과를 연결하고 실제 값·배치를 보존한다. |
| 공동 입력 | `PlacementJointInputAnalysis`가 함께 도달하는 정의 tuple을 기록한다. 같은 분기의 AA/BB는 보존하고 존재하지 않는 교차 조합을 만들지 않는다. |
| 실행별 map | `VALUE_MAP` 물리 realization으로 실행 경로에 따라 달라지는 실제 FederationMap을 표현한다. 고정된 공통 worker를 만들어내지 않는다. |
| 물리 합법성 | 선택한 producer·reader·이동이 각 도달 tuple에서 연산의 입력 조건을 만족하는지 hard factor로 검사한다. 불법 조합은 무한대 비용에 해당한다. |
| 반복문·중첩 | 정적 정의를 사용하는 유한 관계 고정점으로 entry/backedge와 중첩 분기를 연결한다. 반복 횟수만큼 연산 노드를 복제하지 않는다. |

`J_v`는 실행되는 새 연산 노드가 아니다. 그래프 분석의 관계와 물리 선택 제약으로 표현한다. 각 연산의 static plan은 하나이며, 그 계획이 실제 가능한 모든 입력 tuple에 대해 합법이어야 한다. 따라서 경로별로 임의의 서로 다른 연산 계획을 실행하는 기능까지 추가한 것은 아니다.

엄밀히는 구현 관계를 `J_hat_v`로 보아야 한다. 구조적 분기 안의 정의 묶음은 보존하지만 서로 다른 조건식 사이의 논리 관계까지 풀지는 않는다. 예를 들어 별도 if 두 개가 같은 predicate를 검사한다는 사실만으로 교차 행을 없애지 않는다. 설계 문서의 범위대로 실제 실행을 포함하는 과대근사이며, 논문상의 정확한 `J_v`와 항상 같거나 모든 의미적으로 합법적인 계획을 보존한다고 주장하지 않는다.

## 2. L2SVM에서 의미하는 변화

공통 reader를 local로 선택하면 다음 조합을 표현할 수 있다.

```text
true:  local 정규화 결과 ── identity ────┐
false: 기존 FOUT 값 ── 명시적 LOCAL ────┤→ TW/Binding/TR: CP,LOUT
```

함수 진입 전에 인자를 LOCAL로 만들고 이후 재사용하는 후보도 함께 비교한다. 검증에서 선택된 L2SVM 계획은 호출 인자의 명시적 LOCAL 공급을 사용했다. 따라서 현재 실행 증거는 local Y 보존과 명시적 이동을 확인하지만, 모든 조건에서 false 경로 전용 GET이 더 저렴하여 선택된다는 주장은 하지 않는다.

보호된 입력을 임의로 다운로드하거나 서로 다른 worker로 옮길 수는 없다. 이동 불가 시 해당 조합을 거절하며, runtime에서 계획을 보정하지 않는다.

## 3. 비용과 구현 규모

- 분기 activation 정보가 경로를 구분할 때 해당 실행 빈도를 비용에 반영한다. 캐시 및 명시적 GET 공유는 기존 materialization 선택과 연결한다.
- Loop나 여러 호출의 관계가 겹쳐 정확한 빈도 분할을 증명할 수 없는 경우 보수적인 최대 단가를 사용한다. 특히 동적 map GET의 일부 경우는 실제 선택 경로보다 비싸게 평가할 수 있다. 이는 합법 계획의 삭제와는 다르지만 최적 계획 순위에는 영향을 줄 수 있다.
- 모든 관련 layout의 단가가 같음을 증명하면 비용 factor를 줄인다. Hard factor는 실제로 검사하는 입력·map 정보만 관측하도록 표현을 줄이고, 최종 canonical 검사도 유지한다.
- 상관된 source 선택은 실제 존재하는 tuple별 내부 표현으로 분리한다. AA/BB를 AB/BA까지 확장하거나 합법 tuple을 임의로 자르지 않는다.
- 후보 감사 출력은 행 단위로 기록한다. 후보 수를 줄이지 않고 L2SVM의 전체 로그 문자열 할당에 의한 OOM을 제거했다. 로그 자체의 크기는 줄이지 않았다.

## 4. 검증 기준과 현재 증거

Runtime 검증은 `scripts/fedplanner/run_LAN_docker.sh`를 통해서만 수행한다. Frozen class의 host/container SHA256이 모두 일치한 뒤 실행하고, `-noFedRuntimeConversion` 상태에서 CP/FED 결과의 SUM·NORM2·행·열과 계획/실행 이동 audit를 비교한다.

최종 검증은 아래 12개 사례를 같은 소스와 class 빌드로 실행했고 **12/12 PASS**했다. 성공 사례 9개와 계획 단계 거절을 기대한 negative 3개를 포함한다.

| 사례 | 확인할 내용 |
|---|---|
| L2SVM true / false | Y 정규화 양쪽 경로의 수치, local 결과 보존, 명시적 인자 이동 |
| 상관된 AA / BB | 실행별 worker가 달라도 합법 계획을 보존하며 불필요한 이동 없음 |
| 독립 분기 AB / BA | 보호된 입력은 FED에 유지하고 public 입력의 명시적 이동으로 연산 |
| 모두 보호된 독립 AB/BA | privacy상 이동할 수 없는 조합을 계획 단계에서 거절 |
| loop toggle | entry/backedge 정의와 실제 반복 실행의 값·배치 보존 |
| 함수 두 호출 | 같은 actual 인자 재사용, 호출별 분기·반환 연결 |
| 보호된 함수 결과 혼합 | 서로 다른 호출의 불법 raw 결합 거절 |
| branch upload | local 분기 값을 명시적 FOUT으로 변환하고 후속 보호 연산 실행 |
| 보호된 Y의 L2SVM negative | 허용되지 않은 LOCAL 공급 거절 |

전체 최신 소스를 다시 컴파일한 Maven 검증은 **29개 클래스, 306개 통과, 실패·오류 0개, 기존 4개 제외**로 끝났다. 공동 입력·중첩/loop/function, FULL 증명 고정점, 함수 입력 authority, 비용·공유 encoding, privacy, emission/runtime audit와 federation ID 0 회귀를 포함한다. 명령과 개별 XML 결과는 아래 artifact에 보존했다.

- 재실행: `/home/mchoi/joint-boundary-20261006-r4nxqn4z/build/run-final-regressions.sh`
- 집계: `/home/mchoi/joint-boundary-20261006-r4nxqn4z/build/final-maven-results.json`
- 로그: `/home/mchoi/joint-boundary-20261006-r4nxqn4z/build/final-maven-verified.log`
- 개별 결과: `/home/mchoi/joint-boundary-20261006-r4nxqn4z/build/final-surefire-reports/`

Docker 최종 실행에서 class digest 6/6, physical model proof, 각 사례의 수치·privacy 거절·action audit 검사가 통과했다. Runtime conversion 위반은 0개다. L2SVM 양쪽은 SUM=-0.051876443681957596, NORM2=0.6933312394625315, 3×1로 CP/FED가 일치했다. Branch-upload는 SUM=30, NORM2=56, 8×3이며 명시적 FOUT lowering과 실행이 일치했다.

같은 frozen build에서 함수 사례를 추가 실행해 **CALL_C=54, CALL_D=132**도 각각 비교했다. 전체 SUM=186, NORM2=20340, 2×1까지 CP/FED가 일치했다. 이 추가 검사는 두 호출의 결과가 뒤바뀌어도 전체 SUM/NORM2가 같을 수 있는 검증 빈틈을 막는다. Harness 단위 테스트 **15/15**, Python compile, Bash 문법 및 `git diff --check`도 통과했다.

- [최종 12개 결과](/grid/3/cofee-lm-sweep-mchoi-20260914/joint-boundary-e2e-20261006/joint-boundary-e2e-20261006-final12/result.json)
- [사례별 결과 표](/grid/3/cofee-lm-sweep-mchoi-20260914/joint-boundary-e2e-20261006/joint-boundary-e2e-20261006-final12/compact-result-table.md)
- [함수 호출별 추가 검증](/grid/3/cofee-lm-sweep-mchoi-20260914/joint-boundary-e2e-20261006/joint-boundary-e2e-20261006-final12-function-call-markers/result.json)
- Frozen build: `/home/mchoi/joint-boundary-e2e-frozen/final12-20261006T151400Z`
- 소스/클래스 증거: 같은 디렉터리의 `main-sources.json`, `artifact-inventories.json`, `freeze-summary.json`. 소스 manifest SHA256은 `c0e75f02592c080411cfabf87fa740ec9d5b8551930731a7ab0bb8f06b42d3b8`이다.

이 결과는 기능·합법성 검증이다. 동일 규모의 이전 구현과 실행시간을 비교한 성능 실험 결과는 아니다.

## 5. 범위와 남는 한계

- 재귀 함수는 구현 범위에 포함하지 않았다. 중첩 if/loop와 비재귀 함수의 관계를 검증한다.
- 기존 runtime/operator 지원과 privacy 제약은 유지된다. 이 변경으로 모든 연산의 모든 물리 배치가 자동으로 지원되는 것은 아니다.
- 추가 탐색에서 서로 다른 worker를 받는 공유 `rmempty` 함수의 보호된 반환값은 여전히 물리 공급 증명이 부족하여 거절됐다. 최종 Maven class로도 재확인했다(`build/final-protected-rmempty-probe.log`). 기존 public cardinality 단위 테스트는 실행별 한 파티션과 공통 worker 부재를 검사하지만, 보호된 `rmempty` 반환의 E2E 성공을 증명하지 않는다. 이 경우를 통과시키기 위해 privacy를 완화하지 않았다.
- 큰 Exact 최적화 문제의 일반적인 factor 폭 문제까지 해결한 것은 아니다. 이번 작은 사례의 통과를 대규모 workload 전체의 성능 개선이나 전역 최적성 보장으로 해석하지 않는다.
- 공동 CFG 환경이 16,384개를 넘으면 분석은 명시적인 resource-limit 오류로 종료한다. 관계를 임의로 잘라내거나 Cartesian 관계로 바꾸어 성공 처리하지 않는다.
- 단일 파티션 수와 동일한 worker·range 정렬은 별도의 증명이다. 전자가 후자의 검사나 privacy를 대체하지 않는다.

## 6. 주요 수정 위치

| 책임 | 파일 |
|---|---|
| CFG 공동 정의 관계 | `placement/PlacementJointInputAnalysis.java`, `PlacementProgramFacts.java`, `PlacementAnalysis.java` |
| 분기 입력 경계 | `placement/BranchPlacementNormalization.java`, `DMLTranslator.java`, `UnaryOp.java`, `lops/PlacementAlias.java` |
| 물리 map과 경계 증명 | `placement/JointValueMapRelations.java`, `LogicalBoundaryRealizations.java`, `PlacementRelationClosure.java`, `SinglePartitionFacts.java` |
| 합법성·비용·최적화 표현 | `fedExact/ExactPhysicalModel.java`, `ExactPhysicalCostModel.java`, `JointPhysicalCostRows.java`, `ExactPhysicalSharedSourceEncoding.java` |
| 선택된 이동과 runtime 확인 | `placement/FunctionInputTransfer.java`, `PlacementEmissionTransaction.java`, `PlannerRuntimePlacementAudit.java`, `ExecutionContext.java` |
| 감사·재현 | `placement/PlannerCandidateSpaceAudit.java`, `scripts/fedplanner/run_joint_boundary_e2e.py`, 관련 Java/Python 회귀 테스트 |

Java의 `placement/` 및 `fedExact/`는 각각 `src/main/java/org/apache/sysds/hops/fedplanner/placement/`와 `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/` 아래를 가리킨다. 작업 트리에 있던 다른 변경은 그대로 보존했다.

## 7. 최신 origin/main 병합 점검 — 2026-10-06

### 실제 저장소 상태와 격리 검사

`git fetch origin main`을 완료한 뒤 비교 대상을 `58145e73667e0a4f43589e9d37199fc90d293ee8`로 고정했다. 현재 HEAD는 여전히 `0b51cc3ef883b9edb284cd4ae0f2b0358a38c592`이고, 커밋 계보상 main만 8개 앞선다. `MERGE_HEAD`가 없고 `git ls-files -u`도 비어 있다. 따라서 이 작업 트리가 이미 최신 main과 병합되다가 충돌 상태로 남은 것은 아니다.

현재 미커밋 변경에는 main과 같은 내용도 있다. main에서 변경한 178개 경로 중 82개는 현재 작업 트리와 이미 일치한다. 커밋 계보만 보고 main의 모든 기능이 없다고 판단해서는 안 된다.

HEAD만 비교하면 fast-forward처럼 보이므로, 현재 tracked/untracked 파일과 삭제를 모두 임시 index에 담고 별도 object database에서 snapshot commit을 만들었다. 그 snapshot과 고정한 main을 `git merge-tree --write-tree --messages`로 비교했다. 원본 index·branch·운영 소스·테스트 소스는 수정하지 않았다.

| 충돌 범위 | 파일 수 |
|---|---:|
| 운영 Java 코드 | 7 |
| Java 테스트 | 7 |
| 실행 스크립트 및 Python 테스트 | 4 |
| 문서 | 6 |
| 합계 | 24 |

충돌 구간은 총 78개다. 운영 코드의 충돌 파일은 `FederatedCostModel.java`, `ExactPhysicalCostModel.java`, `LogicalBoundaryRealizations.java`, `PlacementAnalysis.java`, `PlacementCostSemantics.java`, `PlacementRelationClosure.java`, `adapter/SyntheticBoundaryProjection.java`다. 실제 dirty 작업 트리에서 즉시 merge가 완료된다는 뜻이 아니라, 현재 변경을 보존한 snapshot을 통합할 때 수동으로 해결해야 하는 충돌이다.

최근 `58145e7366`의 local boundary pruning 변경 때문에 충돌 파일 수가 추가로 늘지는 않았다. 직전 `10e14bc791`과 비교할 때도 24개였으며, 최신 pruning 기능 자체는 현재 checkout에 아직 없다. 이 사실만으로 pruning과 공동 입력의 통합 동작이 검증됐다는 뜻은 아니다.

### 다시 실행한 검사와 발견된 비용 문제

최종 frozen build와 비교한 핵심 main source 51개, main class 4,471개, test class 2,735개는 변경·누락이 없었다. 소스 manifest는 전체 main Java 1,639개 중 51개만 포함하므로 모든 소스 파일의 동일성을 증명하지는 않는다. 소스·테스트·스크립트·설정 7,331개를 검사해 실제 충돌 마커는 없었고, `git diff --check`도 통과했다.

같은 frozen main/test build로 현재 `JointBoundaryPhysicalModelProofTest` 10개와 `FunctionBoundaryRuntimeAliasContractTest` 2개를 재실행해 **12/12 PASS**했다. 별도 임시 디렉터리에서 최신 main의 `ExactFunctionAliasGetCostTest`와 `ExactNativeResultBatchCostTest`를 원문 그대로 컴파일하고 현재 frozen main에 적용한 결과는 **11개 중 5개 PASS, 6개 FAIL**이다. 병합본 테스트가 아니라 현재 빌드에 upstream 회귀를 적용한 비교 검사다.

| 실패한 최신 main 회귀 | 기대 모델 비용 | 현재 모델 비용 |
|---|---:|---:|
| pass-through 함수 반환의 GET 공유 | 10.000823974609375 | 20.00164794921875 |
| 중첩 pass-through 반환의 GET 공유 | 10.000823974609375 | 20.00164794921875 |
| 반복 pass-through 반환의 GET 공유 | 10.000823974609375 | 30.002471923828125 |
| 함수가 새로 만든 값의 내부/외부 GET 공유 | 10.000823974609375 | 20.00164794921875 |
| 반복 호출에서 새로 만든 값의 호출별 GET 공유 | 20.00164794921875 | 40.0032958984375 |
| mixed covariance의 같은 응답 batch | 0.000020345052083925452 | 10.000020345052084 |

위 값은 C2W=3ms, W2C=7ms를 설정한 **비용 모델 검증 값(ms)**이며 실행시간 측정이 아니다. 앞의 다섯 사례는 같은 runtime MatrixObject의 GET을 중복 계산하고, 마지막 사례는 이미 계산 응답에 포함된 결과에 10ms RTT를 추가한다. 최신 main의 `10e14bc791`이 수정한 반환 alias 추적과 native result batch 구분이 현재 비용 코드에는 없다. 이는 merge 후 새로 생긴 오류로 입증된 것이 아니라 **main의 수정이 아직 통합되지 않은 현재 결함**이다. 합법성 실패와 구분해야 하지만 계획의 비용 순위에는 영향을 줄 수 있다.

### 충돌을 해결할 때 함께 보존해야 하는 계약

1. **함수의 alias와 명시적 이동:** main의 `SAME_VALUE_PLACEMENT`는 암묵적인 배치 변경을 막는다. 현재 `FUNCTION_INPUT_TRANSFER`는 별도로 증명·과금·lowering한 LOCAL을 허용한다. 순수 TW/Binding/TR의 보존 조건은 유지하면서 `FunctionInputTransfer.isArgumentConstraint` 및 전체 call-site fact identity를 남겨야 한다. main의 alias 전용 조건으로 일괄 교체하면 명시적 LOCAL 공급 후보나 다중 호출의 정확한 연결을 잃을 수 있다.
2. **loop 진입 이동과 VALUE_MAP 고정점:** main의 `3d0d683c1b`에는 실제 entry anchor를 사용하는 한 번의 upload, 모든 비재귀 entry seed의 보존, derived upload의 authority 검사가 있다. 현재 branch-upload 지원은 이것을 대체하지 않는다. `closeLoopEntryMaterializationCandidates`, `captureLoopEntryAnchors`, 다중 seed 처리를 현재 공동 관계와 FULL 증명 고정점에 결합해야 한다. 현재 branch/loop 테스트 통과만으로 이 별도 main 기능의 지원을 주장하지 않는다.
3. **반환 GET·RTT 수정과 공동 입력 비용:** 최신 main의 반환값 생성 문맥 추적 및 batch 비용 수정을 현재 branch activation, joint cost rows, 명시적 이동 비용과 함께 유지해야 한다. 어느 쪽 `ExactPhysicalCostModel.java`를 통째로 채택해도 다른 쪽 변경을 잃는다.

후속 통합 검증에는 현재 306개 Java 회귀와 Docker 12개 사례 외에도 main의 `LoopEntryMaterializationTest`, `LoopEntryCompletePlacementSpaceTest`, 함수 반환 GET·native result batch 회귀, pruning 회귀가 필요하다. 충돌이 남은 격리 snapshot의 compile/E2E는 실행하지 않았다. 이번 점검에서는 병합 및 운영 코드 수정 없이 충돌·누락·실패를 확인했다.

### 재현 근거

모든 점검 artifact는 `/home/mchoi/joint-boundary-merge-audit-20261006-riuc6jhm`에 있다.

- [점검 집계](/home/mchoi/joint-boundary-merge-audit-20261006-riuc6jhm/audit-summary.json): pinned SHA, 범위 및 충돌 분류.
- [격리 snapshot 정보](/home/mchoi/joint-boundary-merge-audit-20261006-riuc6jhm/snapshot.json): 원본 상태, 임시 index/object database 및 tree/commit.
- [전체 병합 결과](/home/mchoi/joint-boundary-merge-audit-20261006-riuc6jhm/merge-tree.txt), [충돌 구간](/home/mchoi/joint-boundary-merge-audit-20261006-riuc6jhm/conflicts.json), [커밋별 비교](/home/mchoi/joint-boundary-merge-audit-20261006-riuc6jhm/per-commit-merges.json).
- [재실행 결과와 명령](/home/mchoi/joint-boundary-merge-audit-20261006-riuc6jhm/probe-results.json): 각 JUnit 실행의 명령, exit code, 개수, 시간 및 원문 로그 경로. `upstream-tests/`에는 pinned main의 테스트 원문이 있다.

기존에 기록한 보호된 공유 `rmempty` 반환의 제한은 이번에 생긴 merge 회귀로 분류하지 않는다.

## 8. 실제 main 통합과 회귀 수정 — 2026-10-06

사용자의 병합·수정·push 요청에 따라 `58145e73667e0a4f43589e9d37199fc90d293ee8` 위의 별도 작업 트리 `/home/mchoi/w1357-joint-main-20261006`에서 통합했다. 원본 dirty 작업 트리의 다른 실험 변경은 보존했다. Joint 구현 시작 시점의 source baseline과 현재 구현의 차이를 main에 3-way로 적용했으며, 새 기능과 관계없는 캠페인·physical snapshot 변경을 함께 밀어 넣지 않았다. 따라서 7절의 전체 dirty snapshot 충돌 수와 이번 기능 단위 통합의 충돌 수는 다르다.

운영 코드의 수동 충돌은 `PlacementAnalysis`, `PlacementRelationClosure` 두 파일에 있었다. Main의 one-time loop-entry와 action-aware identity를 유지하면서 joint VALUE_MAP/FULL 고정점을 결합했다. 테스트에서는 순수 함수 alias 제약과 명시적 LOCAL transfer의 별도 권한을 함께 남겼다. 반환 GET의 생성 문맥·호출별 수명 및 응답 batch RTT 수정도 보존했다.

### 발견한 통합 문제와 처리

| 문제 | 원인과 처리 |
|---|---|
| 병합 테스트의 compile 실패 | `LocalMaterializationActionKey` import를 복원했다. 전체 main/test compile 통과. |
| 같은 FType의 서로 다른 upload pool 누락 | 상태만 비교하던 중복 검사를 full action selection signature로 바꿨다. 실제 nested branch의 서로 다른 pool 후보가 모두 남는 회귀를 추가했다. |
| loop-entry 전체 공간 검사 실패 | Joint 관계가 보존하는 합법적 동일 layout normalization을 기존 oracle이 금지했다. 직접 전달과 명시적 normalization을 구별하고, 더 높은 이동 비용 및 Exact의 무이동 선택까지 검사했다. |
| 큰 logreg 그래프의 증명 계산 지연 | Exact reference의 구조적 lookup과 같은 fact revision의 증명 재사용으로 수정했다. 모든 owner fact commit에서 cache를 무효화하고 동기식 고정점은 유지한다. 격리 회귀 8개가 약 98초에 통과했다. |
| 기존 harness의 다른 작업 트리 의존 | 현재 repository의 전체 소스·class·의존성을 복사하고 SHA256을 고정하여 Docker에 읽기 전용으로 제공하도록 변경했다. |

Loop 공간 검사는 raw 32,928행에서 합법 292행, 물리 요약 10개를 검사한다. ROW/COL/BROADCAST별 직접 전달과 명시적 normalization이 모두 합법이며 후자가 더 비싸다. Exact가 무이동 계획을 선택한다. 이 결과는 불필요한 이동 후보를 남겨서 잘못 선택한다는 뜻이 아니다.

반환 GET·native result batch 비용 회귀는 C2W=3ms/W2C=7ms에서 **24/24 PASS**했다. 이를 통해 7절에서 발견한 여섯 비용 오류를 해결했다. 다중 branch source를 하나의 runtime cache identity로 합치는 증명 없는 추가 최적화는 적용하지 않았다. 별도로 탐색한 branch→pass-through 반환 probe는 병합 전 final12와 통합 r2에서 같은 지점에 실패하고 로그 SHA256도 같았다. 이를 새 병합 회귀나 지원 완료 사례로 기록하지 않는다.

### 최종 검증 상태

최종 운영 코드의 재빌드, 집중 회귀·비용 회귀 및 **12개 Docker 시나리오가 모두 통과했다**. 첫 실행의 loop oracle 실패와 대규모 증명 지연을 수정한 뒤 새로 얻은 결과다. 이전 빌드의 성공 기록을 통합 빌드의 성공으로 재사용하지 않았다.

| 검사 | 통합 빌드 결과 |
|---|---|
| 전체 main/test compile | PASS, 40.729초 |
| 집중 Java 회귀 43개 class | 351 PASS, 기존 제외 4개, 실패·오류 0 |
| 반환 GET·응답 batch 비용 3개 class | 24/24 PASS, C2W=3ms/W2C=7ms |
| 후속 main golden 1개 class | 4/4 PASS, 추가 전체 compile도 PASS |
| 합계 | 47개 class, 379 PASS, 기존 제외 4개 |
| Python harness / Bash 문법 / diff whitespace | 18/18 PASS / PASS / PASS |
| Docker class SHA256 / 물리 모델 proof | 6/6 일치 / 10/10 PASS |
| Docker E2E | 12/12 PASS, runtime conversion 위반 0 |

기존 제외 4개는 `PrivacyMovementCertificationTest`의 기존 public fixture다. 새 검사를 꺼서 통과시키지 않았다. 최종 `ExactInputAuthorityOptimizationTest` 8개는 전체 회귀 안에서 122.713초에 종료했으며 전체 43개 class의 wall time은 128.241초였다. 격리 실행과 병렬 suite의 시간은 서로 다른 조건이므로 동일 조건 성능 비교로 주장하지 않는다.

Docker 성공 실행은 `joint-main-20261006-final-stagefix`다. Snap Docker daemon의 `/tmp` mount에서 실행 script가 보이지 않았던 첫 시도는 DML 실행 전 실패로 보존했다. Harness의 기본 staging을 저장소 `target/joint-boundary-e2e-runtime`으로 수정하고 `--stage-root`도 제공한다. 이전 stage는 재사용하지 않으며 전체 source/class/dependency snapshot과 읽기 전용 code mount를 유지한다.

L2SVM 양쪽 결과는 CP/FED 모두 SUM=-0.051876443681957596, NORM2=0.6933312394625315, 3×1이다. 함수 두 호출은 CALL_C=54, CALL_D=132 및 전체 SUM=186, NORM2=20340을 확인했다. Branch upload는 SUM=30, NORM2=56, 8×3으로 일치했고 선택된 이동의 lowering·실행 audit도 통과했다. 상관된 AA/BB는 이동 없이 실행했고, 보호된 불법 결합 세 사례는 예상한 계획 거절을 확인했다.

- [최종 Docker 결과](/grid/3/cofee-lm-sweep-mchoi-20260914/joint-boundary-e2e-20261006/joint-main-20261006-final-stagefix/result.json)
- [Docker 빌드·입력 manifest](/grid/3/cofee-lm-sweep-mchoi-20260914/joint-boundary-e2e-20261006/joint-main-20261006-final-stagefix/manifest.json)
- [집중 Java 회귀](/home/mchoi/joint-main-integration-20261006/final-regressions.json), [비용 회귀](/home/mchoi/joint-main-integration-20261006/final-cost-regressions.json), [빌드](/home/mchoi/joint-main-integration-20261006/final-build.json)

위 세 Maven 실행의 전체 Java source manifest SHA256은 `e29c937a479ac13d4273326e9e9af1b777abb75e583c701134026a12ac5a7cdf`다. Docker의 전체 main class inventory digest는 `f33e5e0e1c3fea28d613415f90fb6a7e0937621d951b29f472079ed73d27a4ed`, 전체 main source inventory digest는 `c5005bbebd61d07abc41a9cc0db88090405d3ff98a0a24575bba4a27f5a0be64`다. 두 종류 manifest는 상대 경로 기준이 달라 서로 같은 digest일 필요는 없다.

검증 중 origin/main에 `d57bca99d9`가 추가되어 기능 checkpoint `36faf7fdc2`와 실제 merge로 통합했다. 이 커밋은 운영 코드 변경 없이 unknown-width materialization golden 검사와 세션 기록을 강화한다. 문서의 양쪽 추가 기록을 보존했다. 강화된 exact action identity, worker/range, consumer reference, early-pruning parity 검사와 unknown-width golden은 원문 그대로 통과했다.

Metadata/control-flow golden 두 개는 joint exact/VALUE_MAP 연결 identity에 맞춰 갱신했다. 변경 전후 NODE privacy/placement 목록(각 36개·54개), AVAILABLE owner/input key(34개·52개), outer emission shell, relocation 수(4개·8개)가 동일함을 먼저 확인했다. 내부 support의 정확한 TWrite·VALUE_MAP 참조와 canonical worker endpoint 직렬화가 달라진 것이다. Digest에서 identity나 support를 빼지 않았으며 protected aggregate 및 unknown-width golden은 변경하지 않았다. 이 inventory 감사는 모든 DML의 전역 계획 공간이 동일하다는 증명은 아니다.

Merge 이후 다시 전체 main/test compile을 수행하고 최신 golden class **4/4 PASS**를 확인했다. 해당 빌드와 테스트의 Java source manifest SHA256은 `d004b300ad456570da1625eae2c0a58e84ea2528cf82d3b1c791389ad1b4cc12`다. 앞선 Docker 성공 빌드와 **운영 Java class 3,742개, 전체 main source, 의존성, model-proof class 및 나머지 runtime resource가 동일**함을 비교했다. 추가 compile로 바뀐 것은 위 golden test class와 Maven이 복사한 Python harness/test/cache resource 4개뿐이다. 실제 실행한 host harness의 hash는 Docker 성공 실행과 동일하다. 따라서 `target/classes` 디렉터리 전체가 byte-identical하다고 주장하지 않는다.

- [최신 main golden 감사](/home/mchoi/joint-main-integration-20261006/latest-main-golden/result.json)
- [Merge 후 compile](/home/mchoi/joint-main-integration-20261006/merged-main-build.json), [Merge 후 golden 회귀](/home/mchoi/joint-main-integration-20261006/merged-main-regressions.json)
- [최종 운영 빌드와 Docker 빌드 비교](/home/mchoi/joint-main-integration-20261006/final-docker-build-parity.json)

재현 artifact root: `/home/mchoi/joint-main-integration-20261006`. `state.json`에 source/test 통합 범위가 있으며 `merge-inputs/`에는 base/ours/main 원문을 보관했다. `loop-verification/`, `cost-verification/`, `optimization-audit/`는 각 수정의 진단과 격리 검증 근거다.
