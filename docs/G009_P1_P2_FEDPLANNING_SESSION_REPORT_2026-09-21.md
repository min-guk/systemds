# G009 P1/P2 FedPlanning 세션 보고서

- 작성일: 2026-09-21
- 저장소: `/home/mchoi/systemds-g009-integration`
- 기준 브랜치: `integration/g009-baseline-20260919`
- 기준 커밋: `656cca49f3` (`origin/main`, `Adopt baseline-first G009 owner indexing`)
- 대상 workload: ML training P1/P2 SliceLine, `worker=4`
- 실행 모드: `-exec singlenode -noFedRuntimeConversion` planning-only
- 최종 상태:
  - **P1: heuristic, DP-global, DP-local 모두 수정 및 1회 최종 검증 PASS**
  - **P2: synthetic 공개-domain dual opt-in 적용, exact candidate 탐색 병목 수정, 세 planner 1회 최종 검증 PASS**
- 관련 선행 문서:
  - [G009 기준선 우선 전체 최적화 결과](G009_BASELINE_FIRST_FULL_OPTIMIZATION_RESULTS_2026-09-20.md)
  - [G009 개정 60초 최적화 최종 보고서](G009_REVISED_60S_OPTIMIZATION_FINAL_REPORT_2026-09-20.md)

## 1. 이번 세션의 범위

이번 세션은 기존 G009 GLM 최적화 결과를 다시 측정하는 작업이 아니라, 다음 별도 요청을 처리한 작업이다.

1. ML training P1/P2 SliceLine workload를 4 workers 조건에서 FedPlanner로 compile한다.
2. FedAll은 제외하고 heuristic, DP-global, DP-local을 각각 한 번 실행한다.
3. 실제 학습 runtime은 실행하지 않고 **planning 완료 여부와 planning 시간**만 확인한다.
4. P1/P2의 공통 실패가 planner 버그인지, privacy-safe한 선택지가 실제로 없는 것인지 구분한다.
5. P1은 planner correctness를 수정한다.
6. P2는 공개 synthetic domain과 실제 민감 domain을 구분한 뒤, dual opt-in을 구현·실험·검증한다.

따라서 이 문서의 시간은 실제 모델 학습 시간이 아니다. 모든 최종 실행에서 `Total execution time: 0.000 sec`를 확인했다.

## 2. 요약 결론

### 2.1 P1

P1에는 실행 가능한 privacy-safe placement가 존재했다. 초기 실패는 데이터의 privacy 제약 때문에 선택지가 없어서가 아니라, 다음 planner authority 모델링 결함들이 결합된 결과였다.

- `split` 출력이 nested DML function formal input으로 넘어갈 때 `ROW/FOUT` 및 worker-pool 연속성이 publication 단계에서 유실됨
- `transpose`가 생성하는 실제 `FederationMap.transpose()` 범위를 exact native layout으로 표현하지 못함
- `t(X) %*% X`가 요구하는 `COL_T × ROW` 정렬을 단순 동일-FType worker-pool 비교로만 판정함
- exact DIRECT binding을 relocation demand로 잘못 해석함
- `DIRECT_FOUT`에 연결된 relocation action을 “비활성임을 증명하는 witness”가 아니라 실제 선택된 relocation으로 certificate에 기록함

이를 수정한 후 세 planner가 모두 planning-only로 완료됐다.

### 2.2 P2

P2의 원래 실패는 정책상 올바른 차단이었다. `transformencode`의 metadata `M`은 category/recode dictionary를 포함하므로 명시적 공개 계약 없이 coordinator로 내보내면 안 된다.

현재 synthetic P2 generator는 첫 번째 열의 domain을 공개된 `1..50`으로 정의한다. 이에 benchmark template과 생성 DML에 **명시적 public recode metadata dual opt-in**을 적용했다. JVM property가 켜진 경우에만 공개가 허용되며, property가 없으면 최종 JAR도 `PRIVATE_AGGREGATE`에서 fail closed한다.

정책을 적용한 뒤 드러난 별도 planner 성능 결함도 수정했다. container hostname을 매 비교마다 DNS resolve하던 문제와, 1,720억 조합의 exact candidate component를 canonical 순서로 완전 열거하던 문제를 제거했다. 최종 P2는 세 planner 모두 planning-only로 약 3.4--4.5초 wall time에 끝난다.

## 3. 고정한 실행 경계

### 3.1 P1 workload와 설정

```text
DML:
/home/mchoi/ml-p1p2-sliceline-fedplanning-w4-once-20260921/
  tmp/p1-valid/gen_P1_FULL_P1_4.dml

working directory:
/home/mchoi/ml-p1p2-sliceline-fedplanning-w4-once-20260921/experiments
```

Planner 설정:

| planner | config |
|---|---|
| heuristic | `../tmp/p1-valid/compile_conf/mkl-heuristic-first.xml` |
| DP-global | `../tmp/p1-valid/compile_conf/mkl-exact.xml` |
| DP-local | `../tmp/p1-valid/compile_conf/mkl-cost.xml` |

공통 명령 형식:

```bash
env SYSTEMDS_STANDALONE_OPTS='\
-Dsysds.fedplanner.trace=false \
-Dsysds.fedplanner.stdout=false \
-Dsysds.fedplanner.transread.debug=false \
-Dsysds.compile.log_lop_mapping=false' \
/home/mchoi/systemds-g009-integration/bin/systemds \
  -exec singlenode \
  -noFedRuntimeConversion \
  -f ../tmp/p1-valid/gen_P1_FULL_P1_4.dml \
  -stats 100 \
  -config <planner-config>
```

SystemDS CLI가 DML 오류에도 process exit code 0을 반환할 수 있으므로, 성공 여부는 exit code만으로 판정하지 않았다. 각 log에서 다음을 함께 확인했다.

1. `An Error Occurred`가 없어야 한다.
2. `Compile Phase FedPlanner`가 존재해야 한다.
3. `Compile Phase FedPlanner CandidateE2E Total`이 존재해야 한다.
4. `Total execution time: 0.000 sec`여야 한다.

## 4. 진단 과정

### 4.1 초기 현상

초기 P1은 heuristic과 exact 계열 모두에서 feasible placement를 만들지 못했다. heuristic 경로의 graph closure 문제를 먼저 고친 뒤에는 heuristic이 성공했지만 DP-global은 다음 상태로 남았다.

```text
EXACT_VE_NO_FEASIBLE_ASSIGNMENT
```

exact solver의 arc-consistency 진단에서 최초로 소거되는 핵심 component는 PCA와 GMM 내부의 다음 패턴이었다.

```dml
t(X) %*% X
```

소거 관계는 대체로 다음 순서였다.

1. transpose `t(X)`의 `FED/FOUT/COL` 대안
2. aggregate binary의 `FED/LOUT/COL` 대안
3. formal `TRead X`의 `FED/FOUT/ROW` 대안
4. 해당 값을 사용하는 후속 aggregate와 function boundary

이는 protected input에 안전한 row-federated 경로가 없는 것이 아니라, runtime이 허용하는 `COL_T × ROW` 직접 소비를 exact model이 relocation 필요 상태로 해석하고 있음을 의미했다.

### 4.2 heuristic과 exact의 의미 차이 확인

heuristic이 성공한 뒤 DP-global만 실패했으므로 privacy closure 자체보다 exact authority factor와 certificate projection을 우선 조사했다.

확인된 두 단계의 exact 전용 결함은 다음과 같다.

1. **factor 단계:** transpose 결과의 COL residency와 원 ROW anchor가 runtime의 `COL_T` 조건으로 정렬되어도 `samePhysicalWorkerEndpoints`가 FType 불일치 때문에 false를 반환했다.
2. **selection 단계:** factor를 통과한 뒤 `DIRECT_FOUT` authority가 들고 있는 action까지 실제 relocation 선택으로 기록해 `EXACT_PHYSICAL_RELOCATION_AUTHORITY_LOST`가 발생했다.

두 번째 오류에서 action은 relocation 실행 선택이 아니라 “이 정확한 action이 현재 exact source map 때문에 비활성이다”라는 증명용 identity였다.

### 4.3 임시 계측 처리

원인 규명을 위해 exact factor/domain 소거 위치와 direct-input 거부 원인을 일시적으로 기록했다. 원인이 확정된 뒤 다음 임시 계측은 production diff에서 전부 제거했다.

- `Exact-ArcInfeasible`
- `Exact-ArcEmptyDomains`
- `Exact-LogicalBoundaryInfeasible`
- `Exact-InputInfeasible`
- `Exact-DirectFoutRejected`
- `Exact-DirectInputRejected`

최종 source에는 위 marker나 임시 solver instrumentation이 남아 있지 않다.

## 5. P1 구현 변경

### 5.1 function/transient/native placement 연속성

수정 파일:

- `LogicalBoundaryRealizations.java`
- `NativePlacementContinuity.java`
- `NeutralPlacementGraphBuilder.java`

주요 변경:

1. formal `TRead`처럼 물리 Hop input이 없는 function input도 source argument의 selected/present placement에서 worker-pool witness를 이어받도록 했다.
2. function boundary에서 exact layout과 dynamic endpoint-only layout을 구분했다.
3. CFG publication 뒤 logical function input closure를 다시 수행하고, 그 결과가 바뀌면 physical candidate와 worker-pool materialization closure를 fixed point로 재실행했다.
4. 재계산된 boundary alternative에 더 이상 해당하지 않는 stale exclusion을 제거했다.
5. dynamic range를 다시 만드는 연산은 동일 range라고 과도하게 주장하지 않고 endpoint residency만 사용하도록 제한했다.

### 5.2 transpose의 실제 runtime geometry 보존

수정 파일:

- `NeutralPlacementGraphBuilder.java`
- `PlacementIdentity.java`

`ROW → COL` 또는 `COL → ROW` transpose는 runtime에서 `FederationMap.transpose()`를 게시한다. 따라서 2차원 partition마다 begin/end 좌표를 서로 교환해 exact output anchor를 생성하도록 했다.

또한 aggregate-binary의 runtime 조건과 같은 비교를 추가했다.

```text
COL partition interval [begin[1], end[1]]
  == ROW partition interval [begin[0], end[0]]
```

worker address도 canonical endpoint로 비교한다. endpoint가 같다는 사실만으로는 허용하지 않고 partition interval까지 같아야 한다.

### 5.3 exact DIRECT와 RELOCATION 의미 분리

수정 파일:

- `CandidateSelections.java`
- `NeutralPlacementGraph.java`
- `ExactPhysicalSelection.java`

주요 변경:

1. exact support clause에 binding이 있으면 `RELOCATION` binding만 relocation demand로 취급한다.
2. `DIRECT`와 `LOGICAL_TRANSIENT` binding의 relocation subset에 `allMatch`를 적용해 빈 집합을 true로 만들던 오류를 제거했다.
3. exact DIRECT source가 명시된 경우 multi-input candidate도 direct reachability를 인정한다. 기존의 “single present input” fast path에만 의존하지 않는다.
4. transpose COL residency와 ROW durable anchor는 exact `COL_T` 정렬이 증명된 경우에만 같은 direct residency로 인정한다.
5. `DIRECT_FOUT` authority가 action identity를 가지고 있어도 certificate에서는 실제 movement로 bind하지 않는다. `InputAuthorityKind.RELOCATION`만 선택된 relocation으로 기록한다.

### 5.4 derived FOUT와 aggregate direct inputs

수정 파일:

- `NeutralPlacementGraphBuilder.java`

주요 변경:

1. unary FED/LOUT와 `COL × ROW` aggregate-binary가 exact source receipt를 유지하면서 direct input을 소비할 수 있도록 했다.
2. derived FOUT는 기존 durable output action proof를 유지하면서 source computation의 exact direct/relocated input assignment를 결합한다.
3. output materialization authority와 input placement authority를 하나로 덮어쓰지 않는다.

## 6. 회귀 테스트

새 테스트:

```text
src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/
  P1FourWorkerFunctionInputContinuityTest.java
```

이 테스트는 다음 구조를 축소 fixture로 재현한다.

```text
4-worker ROW federated input
  → removeEmpty
  → scale
  → t(Xscaled) %*% Xscaled
  → split
  → nested e_step(Xtrain)
  → covariance t(diff * resp[,k]) %*% diff
```

검증 내용:

1. nested `e_step` formal `X`가 `PRIVATE_AGGREGATE`를 유지한다.
2. split output의 `ROW/FOUT` authority가 function boundary를 통과한다.
3. heuristic selection이 존재한다.
4. exact physical model과 cost surface가 생성된다.
5. global exact optimizer가 해를 만든다.
6. exact certificate가 모든 physical decision을 포함한다.

실행한 targeted suite:

```bash
mvn -q -DskipTests=false \
  -Dtest=P1FourWorkerFunctionInputContinuityTest,LogicalBoundaryRealizationsTest,\
NativePlacementContinuityTest,FunctionReturnSinglePartitionFactsTest,\
TransientPlacementAlternativesTest,NeutralPlacementFixedPointCompositionTest,\
PrivacyMovementCertificationTest,DynamicNativeLayoutCompositionTest,\
NativeLineagePlanSpaceCompletenessTest,RelocationSelectionsTest,\
CandidateReceiptAssignmentCompletenessTest test
```

결과: **PASS**. 이후 `mvn -q -DskipTests -Dmaven.test.skip=true package`도 PASS했다.

이 결과는 targeted regression 범위의 증거다. project 전체 test suite가 모두 clean하다는 주장으로 확대하지 않는다.

## 7. P1 최종 1회 실행 결과

최종 증거 디렉터리:

```text
/tmp/p1-final-three-planners-1789955500
```

각 planner는 최종 source/JAR 조건에서 한 번씩 실행했다.

| planner | wall | max RSS | `FedPlanner` | `CandidateE2E Total` | runtime | 판정 |
|---|---:|---:|---:|---:|---:|---:|
| heuristic | `38.40 s` | `1,760,924 KiB` | `27.070946 s` | `36.217092 s` | `0.000 s` | **PASS** |
| DP-global | `38.19 s` | `1,494,784 KiB` | `26.242320 s` | `36.054297 s` | `0.000 s` | **PASS** |
| DP-local | `40.38 s` | `1,711,560 KiB` | `29.026555 s` | `38.223053 s` | `0.000 s` | **PASS** |

summary SHA-256:

| file | SHA-256 |
|---|---|
| `heuristic.summary` | `8d25462e83be3df39d64eece9636271503bbb7b7e638ded115a9c87fe554d3f2` |
| `dp-global.summary` | `da3b9cdcaf22ff81c76fc2adb66b1c0f53ea686a128e58269ceda782802fa765` |
| `dp-local.summary` | `63f65c212ac9af8e3a5014c238a15addf4b55a9a0d9565e8c23d6cd3b5ba3ace` |

### 7.1 해석

- 세 planner 모두 60초 이내다.
- 1회 관측에서 DP-global의 named planning metrics가 가장 짧았다.
- 표본이 각 1회이므로 planner 간 일반적인 성능 우열이나 통계적 유의성을 주장하지 않는다.
- 수정 전에는 P1이 planning 실패했으므로 이번 결과의 핵심은 “몇 % 빨라졌다”가 아니라 **privacy-safe한 실제 계획을 끝까지 생성하게 됐다**는 것이다.

## 8. P2 정책 구현

P2 preprocessing source와 최종 생성 DML을 다음처럼 변경했다.

```text
/home/mchoi/ml-p1p2-sliceline-fedplanning-w4-once-20260921/
  experiments/code/exp/P2_PREP.dml
  tmp/p2/gen_P2_PREP_P2_4.dml
```

변경 전:

```dml
jspec = "{ ids:true, dummycode:[1] }";
[X0, M] = transformencode(target=Fall, spec=jspec);
```

변경 후:

```dml
[X0, M] = transformencode(
  target=Fall,
  spec="{ids:true,dummycode:[1],cofeePublicRecodeMetadata:true}"
);
```

승인 실행에는 다음 deployment property를 함께 사용했다.

```text
-Dsysds.privacy.allowPublicRecodeMetadata=true
```

두 조건은 독립적인 fail-closed gate다.

1. DML inline literal의 `cofeePublicRecodeMetadata:true`
2. JVM의 `sysds.privacy.allowPublicRecodeMetadata=true`

inline literal을 사용한 이유는 compile-time privacy analysis가 정확한 spec을 증명해야 하기 때문이다. primary encoded matrix `X0`의 privacy는 완화하지 않으며, 승인 범위는 metadata output `M`뿐이다.

이 변경은 synthetic benchmark의 공개 domain 계약에만 적용된다. 실제 민감 category에는 공개 dictionary를 입력과 독립적으로 만들고 worker에 배포한 뒤 `transformapply`를 사용하는 원래 권고를 유지한다.

## 9. P2 성능 병목 진단

### 9.1 hostname canonicalization

정책 opt-in 후 최초 heuristic 실행은 962.02초까지 끝나지 않아 interrupt했다. 이 실행은 PASS가 아니다.

`jstack`의 hot path는 다음이었다.

```text
InetAddress$CachedAddresses.get
  -> UnknownHostException.fillInStackTrace
  -> FederationUtils.parseAddress
  -> FederationUtils.canonicalFederatedWorkerAddress
  -> PlacementIdentity.physicalWorkerEndpoints
```

`worker1` 같은 container-only hostname을 `new InetSocketAddress(host, port)`로 만들면서 exact 비교마다 DNS lookup과 `UnknownHostException` 생성이 반복됐다. placement identity는 네트워크 접속이 아니라 lexical canonical identity이므로 다음으로 변경했다.

```java
InetSocketAddress.createUnresolved(host, port)
```

새 회귀 테스트 `FederationUtilsCanonicalWorkerAddressTest`는 `.invalid` hostname을 100,000번 canonicalize해 DNS 비의존성과 lexical endpoint 보존을 확인한다.

microbenchmark:

| 구현 | 호출 | 시간 | 호출당 상대값 |
|---|---:|---:|---:|
| 수정 전 | 100,000 | `387.596 ms` | `1.00x` |
| 수정 후 | 1,000,000 | `334.223 ms` | 약 `0.086x` |

호출당 약 11.6배 빨라졌지만, 이것만으로 P2 전체는 끝나지 않았다.

### 9.2 exact candidate Cartesian product

DNS 수정 후에도 heuristic은 163.58초에 완료되지 않아 interrupt했다. 새 stack은 다음 exact receipt search에 있었다.

```text
NeutralPlacementGraph.isRelocationActive
  -> RelocationSelections.ExactEmissionScorer.minimumPhysicalEmissionCount
  -> CandidateSelections.Search.ComponentSearch.solve
```

trace로 확인한 최초 search 공간:

```text
consumers=141
full product=198,135,565,516,800
interaction components=[171,992,678,400, 144, 8]
```

가장 큰 component의 20개 변수는 최우선 두 목적함수 값이 모든 row에서 동일했다. 기존 canonical DFS는 relocation 제약을 늦게 드러냈고, 비용 0 optimum을 얻은 뒤에도 거대한 Cartesian product를 열거할 수 있었다.

## 10. exact 탐색 개선

최종 구현은 근사 선택으로 바꾸지 않고 다음 exact 절차를 사용한다.

1. receipt의 입력 선호도, anchor 정렬 선호도, required support를 한 번만 index한다.
2. exact interaction token, local materialization factor, FOUT factor, realization/support 관계로 factor graph를 만든다.
3. maximum-cardinality 순서로 제약이 강하게 연결된 변수를 먼저 선택해 첫 feasible incumbent를 찾는다.
4. suffix preference upper bound와 relocation/FOUT/local materialization lower bound로 incumbent보다 나아질 수 없는 subtree만 제거한다.
5. 물리 방출 0과 preference 상한을 함께 달성하면 비용 최적성은 증명된다.
6. factor 순서의 첫 해를 canonical 해라고 가정하지 않는다. canonical 변수마다 더 낮은 rank가 동일 optimum으로 완성 가능한지 target feasibility search로 검사하는 lexicographic self-reduction을 수행한다.
7. `RELOCATION` binding은 named action을 강제로 active로 만들고 derived FOUT도 물리 방출을 소유하므로, 0-emission target에서 이런 prefix는 즉시 제거한다.
8. 일반적인 non-zero optimum에는 admissible bounds를 사용하는 exact fallback을 유지한다.

따라서 최종 선택은 다음 순서를 그대로 보존한다.

```text
maximize input preference
  -> maximize anchor-aligned preference
  -> minimize physical emissions
  -> canonical receipt rank
```

P2 trace에서 1번 component는 `171,992,678,400`개 조합 대신 325개 leaf를 검사해 preference 상한과 physical emission 0을 달성했다. 이후 self-reduction이 canonical tie-break를 증명했다.

trace 증거:

```text
/tmp/p2-zero-target-prune-1789961632
```

trace 실행 결과:

```text
wall=3.76 s
CandidateE2E Total=2.246275 s
runtime=0.000 s
```

## 11. P2 최종 3-planner 1회 결과

최종 JAR SHA-256:

```text
555bd5bb92a2f4f26aed9de5f05b6c524d5e9d9bc468cdb1650aec8981715f4c
```

최종 증거 디렉터리:

```text
/tmp/p2-final-clean-three-1789962043
```

| planner | wall | max RSS | `FedPlanner` | `CandidateE2E Total` | runtime | 판정 |
|---|---:|---:|---:|---:|---:|---:|
| heuristic | `3.83 s` | `524,444 KiB` | `0.500474 s` | `2.282446 s` | `0.000 s` | **PASS** |
| DP-global | `4.37 s` | `475,184 KiB` | `0.492458 s` | `2.523848 s` | `0.000 s` | **PASS** |
| DP-local | `4.10 s` | `496,648 KiB` | `0.410988 s` | `2.158057 s` | `0.000 s` | **PASS** |

summary SHA-256:

| file | SHA-256 |
|---|---|
| `heuristic.summary` | `9263d9f837b17cd881d5b8b3a65ec12a83fa85a5922fcc4845152802ac197b29` |
| `dp-global.summary` | `b0e61729f585ff8d406a016c60fac48059107dbd5e2b07d55230631bed190569` |
| `dp-local.summary` | `e99160c5d9d54bc4b3f94b0e702fa6171e4c5987630707e8df5aa6ef961686cf` |

각 값은 사용자 요청에 따라 최종 조건에서 한 번만 측정했다. planner 간 통계적 우열은 주장하지 않는다.

## 12. P2 negative/privacy 검증

최종 JAR에서 JVM property를 제거하고 동일 DML을 실행했다.

증거:

```text
/tmp/p2-final-clean-negative-1789962086
```

결과:

```text
No privacy-safe physical placement ... FunOut M:M (privacy=PRIVATE_AGGREGATE)
PROPERTY_OFF_FAIL_CLOSED=PASS
```

SystemDS CLI는 DML 오류를 출력해도 process exit code 0을 반환할 수 있으므로, negative 판정은 다음 세 조건으로 했다.

1. `No privacy-safe physical placement` 존재
2. `PRIVATE_AGGREGATE` 존재
3. 성공 marker `CandidateE2E Total` 부재

non-literal spec의 fail-closed는 `TransformEncodeMetadataPrivacyTest.unresolvedDeclaredMetadataCannotBeReleased`로 검증했다. 별도의 실제 non-literal workload 실행은 이전에 150초를 넘겨 중단했으므로 성공/거부 증거로 사용하지 않는다.

## 13. 회귀 테스트와 검증 한계

최종 P2/P1 관련 focused tests는 통과했다.

```text
CandidateReceiptAssignmentCompletenessTest
GlobalReceiptPlanSpaceCompletenessTest
RelocationSelectionsTest
RelocationActionPlanSpaceCompletenessTest
LocalMaterializationSelectionsTest
PlacementEmissionTransactionRedTest
P1FourWorkerFunctionInputContinuityTest
P2EncodedClippingAuthorityTest
TransformEncodeMetadataPrivacyTest
SharedPrivacyPlacementAnalysisContractTest
FederationUtilsCanonicalWorkerAddressTest
```

`mvn -q -DskipTests package`도 PASS했다.

추가로 넓힌 suite에서 `HeuristicLocalContinuationTest` 2건은 candidate search 진입 전 graph-authority validation에서 실패했다. 기준 커밋 `656cca49f3`의 별도 worktree에서도 동일한 2건이 실패했으므로 이번 P1/P2 변경의 신규 회귀로 분류하지 않는다(증거: `/tmp/g009-base-heuristic-local.log`). 또한 `NeutralPlacementGraphUploadRelocationRedTest` 한 항목은 `NeutralPlacementGraphBuilder.bindDirectNativeCandidateRealizations`의 canonical realization merge에서 2분 이상 GC-heavy 상태가 되어 중단했다. 둘 다 P2 exact-search hot path와는 분리된 기존 P1/builder 영역이지만, project-wide 전체 suite가 clean하다고 주장하지 않는다.

## 14. 최종 변경 파일

SystemDS production 파일:

```text
src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/
  ExactPhysicalSelection.java

src/main/java/org/apache/sysds/hops/fedplanner/placement/
  CandidateSelections.java
  LocalMaterializationSelections.java
  LogicalBoundaryRealizations.java
  NativePlacementContinuity.java
  NeutralPlacementGraph.java
  NeutralPlacementGraphBuilder.java
  PlacementIdentity.java
  RelocationSelections.java

src/main/java/org/apache/sysds/runtime/controlprogram/federated/
  FederationUtils.java
```

새 회귀 테스트:

```text
src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/
  P1FourWorkerFunctionInputContinuityTest.java

src/test/java/org/apache/sysds/runtime/controlprogram/federated/
  FederationUtilsCanonicalWorkerAddressTest.java
```

외부 experiment workspace 변경:

```text
experiments/code/exp/P2_PREP.dml
tmp/p2/gen_P2_PREP_P2_4.dml
```

외부 experiment workspace는 git repository가 아니므로 해당 두 파일은 SystemDS git diff에 포함되지 않는다.

## 15. 현재 working-tree 상태와 최종 판정

변경은 아직 commit/push하지 않았다. 기준 commit은 `656cca49f3`이다.

| 항목 | 판정 |
|---|---|
| P1 privacy-safe 선택지 | **존재** |
| P1 초기 실패 | **planner correctness bug** |
| P1 세 planner planning-only | **PASS** |
| P2 원래 metadata 거부 | **명시적 공개 계약 없이는 올바른 fail-closed** |
| P2 synthetic dual opt-in | **구현 완료** |
| P2 property-off gate | **PASS** |
| P2 exact/canonical 선택 | **비용·선호도·canonical 순서 보존** |
| P2 heuristic planning-only | **PASS, wall 3.83 s** |
| P2 DP-global planning-only | **PASS, wall 4.37 s** |
| P2 DP-local planning-only | **PASS, wall 4.10 s** |
| P2 runtime 실행 여부 | **미실행; 모두 runtime 0.000 s** |
| focused regression/package | **PASS** |
| project-wide 전체 suite | **미검증; 별도 P1/builder 이슈 명시** |

P2는 이제 공개 synthetic-domain 계약이 있을 때만 metadata release를 허용하고, 계약이 없으면 차단한다. 승인된 경우에는 privacy-safe candidate를 exact하게 선택하면서 수십 분 이상 걸리던 탐색을 수 초로 줄였고, 비용 최적성과 canonical tie-break를 모두 유지한다.
