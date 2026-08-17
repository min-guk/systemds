# Session Issues — 2026-08-16

## ALS DP가 합법한 local-to-FOUT FED arm을 열거하지 못함

- **상태**: 소스/회귀 테스트 해결, 수정 소스의 Docker canary는 진행 중인 immutable 캠페인과 분리해 추후 검증 필요
- **환경/조건**:
  - planner: `DP`와 비교용 `MinST`
  - privacy: 기존 private 계열 campaign 설정
  - profile/workers/workload: `wan_light`, `4`, `als`
  - 실행 근거: exact final-boundary placement analysis와 Docker campaign의 동일 비용 profile
- **적용 원칙/제약**: runtime이 지원하는 후보를 편의상 닫지 않는다. TRead/TWrite는 `CP/LOUT` 또는 `FED/FOUT` 일관성을 유지하며, runtime fallback 없이 planner-owned exact relocation만 허용한다.
- **의사결정 근거**: runtime이나 TRead/TWrite oracle을 완화하지 않고, neutral graph에 이미 증명된 relocation 후보를 DP 열거기가 실제 arm으로 소비하도록 planner 후보 표현을 수정했다.

### 재현 절차

```bash
mvn -q \
  -Dtest=org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.CampaignBG014AlsPartitionedComputeCostRedTest \
  test
```

상세 plan-tree 증거:

```text
/tmp/g014-als-dp-plan-tree-20260816.log
```

### 관측 증상

ALS line 130의 latent WDivMM transpose owner는 common placement graph에서
`FED/LOUT/ROW`를 합법한 상태로 갖고 있었다. 그러나 TRead/TWrite 일관성에 의해 선택된
`TRead W=CP/LOUT`을 FED parent가 소비할 때 필요한 다음 경로가 DP memo에서 사라졌다.

```text
W CP/LOUT
  -> exact graph-owned CP-to-FOUT/ROW relocation
  -> FED b(*)
  -> FED/LOUT/ROW transpose owner
```

따라서 수정 전 결과만으로는 DP가 local optimum을 선택했다고 말할 수 없었다. 합법한 비교 arm
자체가 열거되지 않은 후보공간 버그였다.

### 원인 분석

`DpPlacementAdapter`의 raw candidate 해석은 선택된 child의 literal placement row를 중심으로
동작했다. native `FED/FOUT` child는 PRESENT federated input으로 해석됐지만, 선택된
`CP/LOUT` child를 neutral graph가 소유한 exact relocation으로 materialize해 도달할 수 있는
PRESENT row는 별도 후보로 만들어지지 않았다.

이 때문에 candidate oracle과 relocation graph에는 합법성 증거가 있는데 DP enumeration에는
그 물리 대안이 없는 불일치가 발생했다.

### 해결 요약

- `PlacementAnalysis.CandidateRuleFacts`에 parent identity별 canonical candidate row index를 추가했다.
- `DpPlacementAdapter.normalizeCandidateInputAlternatives`가 다음 두 종류를 순서대로 반환한다.
  1. 기존 literal selected-child row
  2. immutable candidate-rule domain에 이미 존재하며, 바뀐 각 physical input에 exact graph-owned relocation obligation이 있는 materialized row
- DP enumerator가 각 normalized row를 별도 variant로 평가하도록 했다.
- materialized variant는 FED emission과 exact relocation에만 사용하고, CP arm을 중복 생성하지 않는다.
- anchor나 `CP/FOUT` TRead/TWrite 상태를 새로 만들지 않는다.

### 수정 파일

- `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/DpPlacementAdapter.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
- `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/CampaignBG014AlsPartitionedComputeCostRedTest.java`

### 검증

`CampaignBG014AlsPartitionedComputeCostRedTest` 5개 테스트가 통과했다. 새 회귀는 다음을 직접
검증한다.

- DP memo에 CP owner arm과 `FED/LOUT/ROW` owner arm이 모두 존재한다.
- owner와 내부 `b(*)`의 occurrence-exclusive self cost는 둘 다 FED가 더 싸다.
- CP/FED `b(*)` arm은 동일한 exact `TRead W` plan 객체를 공유한다.
- `TWrite W` parent 수는 2이고 `TRead W cumulative = TWrite W cumulative / 2`다.
- local boundary는 0이고 FED boundary `51,216.952609`는 선택된 relocation action receipt 합과 동일하다.
- 해당 selected action의 compatible consumer 수는 1이라 이 exact arm에는 cross-parent upload reuse가 없다.
- DP의 exact W는 `CP/LOUT`, MinST의 동일 exact W는 `FED/FOUT/ROW`다.

관련 묶음 27개 테스트도 한 번의 Maven invocation에서 모두 통과했다.

```text
CampaignBG014AlsPartitionedComputeCostRedTest                 5/5
CampaignBG014DpLogRegTransientForwardRedTest                 2/2
CampaignBG014DpKMeansSingleWorkerMixedRefedRedTest            1/1
CampaignBG014AbsentLocalMaterializationLoweringRedTest        1/1
MinStExactPhysicalPlanSpaceOracleTest                         9/9
CampaignBHeuristicProvenanceContractTest                      6/6
CampaignBG014PlacementCandidateRuleFactsSliceATest            3/3
failures=0, errors=0
```

### 잔여 이슈

- 진행 중인 336-cell Docker campaign은 이전 immutable source stage다. 그 결과를 이번 dirty-source 수정의 Docker 증거로 합치지 않는다.
- 현재 campaign과 자원 충돌이 없는 시점에 새 clean snapshot/JAR/stage를 만들어 ALS WAN-Light DP canary를 실행해야 한다.
- 후보가 열린 뒤에도 DP가 CP를 선택하는 현상은 아래 별도 이슈의 local-state 한계다.

### 잠재 회귀 위험

- **위험**: 증명되지 않은 synthetic FOUT row가 열거될 수 있음.
  - **감지/차단**: immutable candidate-rule domain membership과 각 변경 input의 exact relocation obligation을 모두 요구한다.
- **위험**: literal/materialized row가 같은 CP arm을 중복 생성할 수 있음.
  - **감지/차단**: CP emission은 literal row에서만 허용하고 exact variant ordinal로 receipt identity를 분리한다.

## ALS DP/MinST 차이가 HOP 비용 또는 multi-parent 비용 분배 오류인지 분석

- **상태**: 이번 ALS occurrence에 대한 원인 분해 완료; 일반 unequal-demand multi-parent 검증은 잔여 과제
- **환경/조건**: `wan_light`, workers=4, ALS line 130 WDivMM transpose owner, DP와 MinST의 동일 immutable `PlacementAnalysis`
- **적용 원칙/제약**: 이상 선택을 이유로 candidate space를 닫기 전에 compute cost, size estimate, boundary cost를 각각 검증한다.
- **의사결정 근거**: planner 철학으로 단정하지 않고 self cost, child recurrence, parent sharing, relocation receipt, final global state를 독립적으로 비교했다.

### 재현 절차

위 ALS 회귀의 다음 테스트를 실행한다.

```text
wanLightAlsDpRetainsDerivedFoutAlternativeWithoutPretendingToBeGlobal
```

### 관측 증상

DP는 ALS owner를 `CP/LOUT`, MinST는 `FED/LOUT/ROW`로 선택한다. 가능한 원인은 다음 세 가지였다.

1. 개별 owner 또는 내부 `b(*)` compute cost가 잘못 측정됨
2. 공유 `TWrite W`의 누적비용 `/2` 분배가 CP/FED 순서를 왜곡함
3. 네트워크 relocation 비용을 누락하거나 중복/오분배함

### 원인 분석

실측 exact recurrence는 세 가설을 이번 occurrence에서 기각한다.

| occurrence | CP exclusive | FED exclusive | 판정 |
|---|---:|---:|---|
| line 130 transpose owner | 9,778.919 | 2,605.689 | FED self cost가 더 쌈 |
| line 130 `b(*)` | 920.435 | 330.109 | FED self cost가 더 쌈 |

CP/FED arm은 동일한 exact `TRead W` plan 객체를 공유하므로 `TWrite W / 2` 항은 양쪽에서
완전히 상쇄된다. 반면 FED `b(*)` arm에는 exact `W CP/LOUT -> FED/FOUT/ROW` relocation
`51,216.952609`가 한 번 부과된다. 이 값은 두 arm cumulative 차이 `50,667.557192`보다 크다.
해당 selected relocation action의 compatible consumer 수도 1이므로, 이번 arm의 차이는 공유 가능한
동일 업로드를 여러 parent에 중복 과금해서 생긴 것이 아니다.

즉, DP는 이미 선택된 local W를 downstream에서 다시 FED로 올리는 국소 arm을 정확히 비싸다고
판단한다. MinST는 같은 업로드 arm을 싸게 계산하는 것이 아니라 upstream W 자체를
`FED/FOUT/ROW`로 공동 재선택한다. 후보 누락을 고친 뒤 남는 차이는 DP의 parent-child recurrence가
downstream 이익을 위해 upstream exact value state를 공동 재선택하지 못하는 상태 표현 한계다.

일반 DP compute-share는 `cumulative / numParents`다. 부모별 dynamic multiplicity가 서로 다른
DAG에서는 이것이 근사일 수 있다. 네트워크 boundary share는 parent demand 정보가 있으면
demand-weighted 공식을 사용한다. 이번 ALS에서는 같은 `/2` 항이 두 arm에 공통이라 원인이 아니지만,
일반 문제까지 없다고 결론내리지는 않는다.

### 해결 요약

- self/exclusive, embedded child recurrence, physical child boundary를 분리해 관측할 수 있는 기존 exact receipt를 회귀에서 사용했다.
- 합법 arm 누락만 수정했다. DP를 MinST처럼 전역 optimizer로 바꾸거나 local/global 차이를 임의 penalty로 숨기지 않았다.
- 동일 exact occurrence에 대해 DP와 MinST가 선택한 upstream W placement를 직접 비교했다.

### 수정 파일

- `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/CampaignBG014AlsPartitionedComputeCostRedTest.java`
- 상세 보고서: `docs/ALS_DP_AND_COMPILE_TIME_ANALYSIS_2026-08-16.md`

### 검증

- ALS 회귀 5/5 통과
- exact relocation receipt 합과 plan physical boundary가 동일
- local/FED arm이 동일 `TRead W` 객체를 공유함을 identity assertion으로 확인
- DP/MinST가 동일 `CompiledHopKey`의 W에 각각 CP/FED 상태를 선택함을 확인

### 잔여 이슈

- 부모별 실행 multiplicity가 다른 synthetic multi-parent DAG에서 uniform compute share의 오차를 별도 회귀로 만들어야 한다.
- MinST의 최적성은 인코딩된 exact objective 기준이며 실제 runtime 최적성은 Docker runtime ordering으로 별도 확인해야 한다.

### 잠재 회귀 위험

- **위험**: 향후 child plan 복제로 CP/FED arm이 서로 다른 W recurrence를 가리켜 `/2` 상쇄 가정이 깨질 수 있음.
  - **감지/차단**: 회귀 테스트의 `Assert.assertSame(localW, fedW)`와 exact occurrence state assertion이 탐지한다.
- **위험**: network cost가 HOP self cost에 흡수되어 원인 분해가 다시 불가능해질 수 있음.
  - **감지/차단**: physical boundary와 direct relocation-action 합의 equality assertion이 탐지한다.

## DP materialized alternative가 동일 exact occurrence에 상충 상태를 부여함

- **상태**: 해결 — 관련 DP LogReg 2/2 및 전체 관련 묶음 통과
- **환경/조건**: DP LogReg transient-forward final-boundary regression
- **적용 원칙/제약**: 동일 immutable occurrence/value에 동시에 두 placement를 부여하는 arm은 전역 합법성이 없지만, 다른 runtime-supported arm은 계속 열거한다.
- **의사결정 근거**: opcode/capability guard를 추가하지 않고, 해당 local closure의 모순만 명시적 global legality failure로 분류했다.

### 재현 절차

```bash
mvn -q \
  -Dtest=org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.CampaignBG014DpLogRegTransientForwardRedTest \
  test
```

### 관측 증상

ALS 후보 확장 후 LogReg에서 같은 exact `TRead Y` occurrence closure가 이전에는
`FED/FOUT`, 새 materialized local arm에서는 `CP/LOUT`을 동시에 요구해 enumeration 전체가
예외로 종료됐다.

### 원인 분석

새 후보 row 자체는 candidate domain과 relocation obligation 기준으로 합법했지만, 한 locally
assembled closure가 이미 선택한 exact child occurrence state와 다른 state를 같은 assignment에
넣었다. 이는 runtime 미지원이 아니라 해당 arm의 내부 assignment 모순이다.

### 해결 요약

- `ExactPlanClosureConflict`로 동일 occurrence/value state 충돌을 명시했다.
- FED emission의 exact relocation closure를 만드는 동안 이 충돌이 나면 해당 arm만 제외하고 다음 emission/variant를 계속 평가한다.
- 다른 runtime-supported opcode/state/candidate row는 닫지 않는다.

### 수정 파일

- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`

### 검증

- `CampaignBG014DpLogRegTransientForwardRedTest`: 2/2 통과
- 전체 관련 묶음 27/27 통과

### 잔여 이슈

- DP는 해당 local arm을 global upstream re-selection으로 복구하지 않는다. 이는 현재 DP local-state 범위이며 MinST 비교로 관측한다.

### 잠재 회귀 위험

- **위험**: 진짜 구현 오류까지 conflict로 조용히 버릴 수 있음.
  - **감지/차단**: 예외 타입은 오직 동일 immutable occurrence/value에 서로 다른 exact state가 들어가는 세 지점에서만 생성되며, 후보공간 회귀와 MinST oracle 테스트를 함께 실행한다.

## FUNCTION_BODY_NON_EMITTED trace fact를 runtime emission으로 잘못 재바인딩함

- **상태**: 해결 — MinST oracle 9/9, heuristic provenance 6/6, candidate fact slice 3/3 통과
- **환경/조건**: function boundary가 포함된 exact physical plan-space oracle tests
- **적용 원칙/제약**: structural trace fact는 후보 사실 universe에 보존하되, runtime instruction을 내지 않는 node에 executable emission identity를 강제하지 않는다.
- **의사결정 근거**: candidate space를 삭제하지 않고 structural/non-emitted 사실과 runtime-emitted decision의 책임 경계를 수정했다.

### 재현 절차

```bash
mvn -q \
  -Dtest=org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactPhysicalPlanSpaceOracleTest,\
org.apache.sysds.test.component.federated.placement.guard.CampaignBHeuristicProvenanceContractTest,\
org.apache.sysds.hops.fedplanner.placement.CampaignBG014PlacementCandidateRuleFactsSliceATest \
  test
```

### 관측 증상

final emission rebinding이 `FUNCTION_BODY_NON_EMITTED` node의 structural candidate fact도 실행 가능한
node state로 재바인딩하려 했고, 해당 node는 의도적으로 `legalAlternatives=[]`이므로 네 개의 MinST
oracle 테스트가 실패했다.

### 원인 분석

function body trace node는 candidate provenance와 closure를 설명하기 위해 facts/domain에는 남아 있지만
그 자체가 runtime work를 emit하지 않는다. 새 exact-emission binder가 이 구분 없이 모든 fact에
runtime-owned emission/action identity를 요구했다.

### 해결 요약

- `bindExactCandidateEmissionStates`가 `FUNCTION_BODY_NON_EMITTED` fact는 원래 structural fact로 유지한다.
- node가 emitted work/legal state를 갖지 않음을 검증한 뒤 runtime identity 재바인딩만 생략한다.
- `bindExactDerivedFoutAuthorities`도 동일 node의 structural authority는 유지하고 runtime action binding만 생략한다.
- candidate fact/domain 자체는 삭제하거나 축소하지 않는다.

### 수정 파일

- `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java`

### 검증

- `MinStExactPhysicalPlanSpaceOracleTest`: 9/9
- `CampaignBHeuristicProvenanceContractTest`: 6/6
- `CampaignBG014PlacementCandidateRuleFactsSliceATest`: 3/3

### 잔여 이슈

- 새로운 non-emitted node kind가 추가되면 동일 책임 구분이 필요한지 검토해야 한다.

### 잠재 회귀 위험

- **위험**: 실제 emitted function node까지 잘못 skip할 수 있음.
  - **감지/차단**: 정확히 `FUNCTION_BODY_NON_EMITTED` kind만 분기하며 node가 emitted work/legal alternative를 갖지 않는지 fail-closed 검증한다.

## Docker compile phase 분해 및 DP/MinST planner 시간 역전 분석

- **상태**: WAN-Light 112개 전체와 WAN-Mid 성공 prefix를 분석 완료; 336-cell campaign 진행 중
- **환경/조건**:
  - Docker-only one-pass campaign
  - 결과: `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1`
  - 분석 스냅샷: 성공 warm row 142개 (`wan_light=112`, `wan_mid=30`)
  - runtime-plan audit mismatch 합: 0
- **적용 원칙/제약**: 물리 호스트 결과를 사용하지 않고, 같은 Docker lifecycle의 latest successful warm phase만 비교한다.
- **의사결정 근거**: `FedPlanner`가 `LopsBuild`에 포함되는 nested timer임을 반영해 직접 planner 시간과 주변 compile 시간을 분리했다.

### 재현 절차

```bash
python3 scripts/federated_campaign/compile_breakdown.py \
  /home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1
```

### 관측 증상

- 이론적 최악 복잡도만 보면 MinST가 더 느릴 것으로 예상했지만 현재 workload에서는 DP direct planner가 더 느리다.
- WAN-Light 일부 DP cell은 compile 시간이 runtime보다 길다.
- 대형 runtime-audit log를 통째로 읽는 단순 parser는 메모리 부담이 크다.
- direct `FedPlanner` timer가 trace formatting/stdout까지 포함하므로 pure solver time과 audit I/O를 현재 로그만으로 분리할 수 없다.

### 원인 분석

`DMLScript`의 compile 단계는 Parse, HopsBuild, HopsRewrite, LopsBuild, LopsRewrite,
RuntimeProgram이다. FED planner는 `constructLops` 시작에서 호출되므로 `FedPlanner`는
`LopsBuild` 안에 포함된다.

WAN-Light planner 평균:

| planner | total compile | LopsBuild | FedPlanner | LopsBuild-FedPlanner | runtime |
|---|---:|---:|---:|---:|---:|
| DP | 10.148 | 9.090 | 7.690 | 1.400 | 37.257 |
| FedAll | 5.124 | 4.095 | 2.734 | 1.362 | 121.695 |
| Heuristic | 4.185 | 3.139 | 1.748 | 1.392 | 111.440 |
| MinST | 3.604 | 2.566 | 1.174 | 1.392 | 31.791 |

주변 `LopsBuild-FedPlanner`는 네 planner가 거의 같으므로 현재 compile 차이는 direct planner가
지배한다. DP는 단순 tree DP가 아니라 각 HOP의 `2^b` input row, materialized alternatives,
relocation selection, TRead/TWrite/function/loop coherence 및 fixed-point closure를 반복한다.
MinST도 최악에는 induced width/domain 곱에 지수적이지만, 현재 factor graph의 실제 separator가
작아 dense factor materialization이 DP 반복 작업보다 작다. Big-O는 이 workload의 wall-clock
순서를 보장하지 않는다.

구조량을 planning receipt와 planner trace에서 추가 추출했다.

- DP: candidate/boundary/output-decision/decision-map/parent-variant/component/closure count
- MinST: variables, factors, transfers, induced width, factor cells, elimination assignments
- FedAll/Heuristic: exact-search calls/prefixes/explored/pruned와 candidate search count
- 공통: emitted FED/FOUT/derived-FOUT, relocation/local-materialization, runtime FED instruction count

WAN-Light workload 평균에서 DP time은 HOP count와 `r=0.934`, MinST time은 elimination
assignments와 `r=0.916`, FedAll time은 exact-search prefixes와 `r=0.979`의 설명적 상관을
보였다(`n=7`, 인과 추론 금지). LogReg FedAll workers=1은 약 96k max prefix와 `34.456s`인
outlier이며 workers=2..4는 약 `2.8s`다.

또한 audit trace는 direct timer 내부에서 `System.out.println`을 수행한다. WAN-Light planner별
평균 trace record는 DP `49,378`, FedAll `1,854`, Heuristic `1,816`, MinST `1,722`다. 따라서
관측된 DP/MinST 비율을 trace-disabled 순수 algorithm 비율로 그대로 주장하지 않는다.

### 해결 요약

- mmap prefix search 기반 parser `compile_breakdown.py`를 추가했다.
- latest successful canonical cell의 `warm-fresh-coordinator-jvm`만 선택한다.
- `LopsBuild-FedPlanner`, residual compile, planner/compile ratio, compile/runtime ratio와 audit mismatch를 CSV로 기록한다.
- planning receipt/coordinator trace에서 planner별 구조량과 emitted/runtime-plan 결과를 추출한다.
- `FedPlanner`와 `LopsBuild`를 더하지 않는다.

### 수정 파일

- `scripts/federated_campaign/compile_breakdown.py`
- `docs/ALS_DP_AND_COMPILE_TIME_ANALYSIS_2026-08-16.md`
- `docs/FED_PLANNER_COMPILE_COMPLEXITY_REPORT_2026-08-16.md`

### 검증

- `python3 -m py_compile scripts/federated_campaign/compile_breakdown.py`: 통과
- 최신 parser 실행: 142 successful warm rows 생성
- profile count: `wan_light=112`, `wan_mid=30`
- planning receipt 누락: 0
- canonical cell 중복: 0
- audit mismatch 합: 0
- 모든 row에서 `LopsBuild-FedPlanner >= 0`

산출물:

- `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1/analysis/compile_breakdown_latest.csv`
- `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1/analysis/compile_breakdown_summary_latest.csv`
- `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1/analysis/compile_exceeds_runtime_latest.csv`
- `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1/analysis/planner_complexity_summary_latest.csv`

### 잔여 이슈

- physical normalization, memory refresh, FunctionCallGraph/PlacementAnalysis binding, registry, HOP-to-LOP lowering의 개별 sub-timer는 아직 없다. 현재는 모두 `LopsBuild-FedPlanner`에 합쳐져 있다.
- DP enumeration/closure, MinST factor production/elimination, exact selector, emission, trace I/O의 개별 sub-timer는 아직 없다. 현재 구조 count는 elapsed time이 아니라 proxy다.
- 캠페인은 분석 추출 시점 `142/336` 성공 prefix이며 `failed=false`다.
- 캠페인은 이전 immutable source이므로 이번 ALS 수정의 Docker 검증과 섞지 않는다.

### 잠재 회귀 위험

- **위험**: 같은 cell의 실패/재시도 로그가 집계에 중복될 수 있음.
  - **감지/차단**: canonical cell별 latest successful response와 지정 warm phase만 선택한다.
- **위험**: audit log 크기로 parser가 메모리를 과도하게 사용할 수 있음.
  - **감지/차단**: 파일 전체 decode 대신 mmap prefix scan을 사용한다.

## 진행 중 immutable Docker campaign 최신 그래프 스냅샷 갱신

- **상태**: 해결 — authenticated successful warm cell 184/336 기준 runtime/compile 그래프 생성 및 검증 완료
- **환경/조건**:
  - 결과: `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1`
  - stage: `a66e804c28332c9f42984e39c4b5e9bfdfaee637289b3dcc879bbd8861a8566c`
  - progress: `wan_light=112`, `wan_mid=72`, `lan=0`, `failed=false`
  - 실행 중 campaign PID `3153029`는 중단/재시작하지 않음
- **적용 원칙/제약**: 같은 immutable Docker stage의 성공한 warm-fresh-coordinator 결과만 사용하고, 미실행 셀은 0이나 보간값으로 표시하지 않는다.
- **의사결정 근거**: 성능 그래프의 비교 가능성을 위해 행은 `LAN → WAN-Light → WAN-Mid`, workload별 y축은 세 환경에서 동일하고 0부터 시작하도록 고정했다.

### 재현 절차

```bash
python3 scripts/federated_campaign/plot_fixed_stage_latest.py \
  /home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1
```

### 관측 증상

기존 `latest` 그래프는 118/336 성공 prefix를 가리켜, 현재 184/336까지 완료된 WAN-Mid 결과를
반영하지 못했다. 또한 실행 중인 campaign에서 행 파일과 progress를 서로 다른 순간에 읽으면
부분 append를 최신 완료 셀로 오인할 수 있다.

### 원인 분석

그래프는 자동 갱신 산출물이 아니며, 기존 one-off plotter는 현재 repo에 재현 가능한 형태로
존재하지 않았다. 최신 상태를 안전하게 그리려면 `progress.json` 전후 값과 `rows.jsonl` 행 수가
동시에 같을 때만 snapshot을 확정해야 한다.

### 해결 요약

- current immutable stage 전용 재현 가능한 Python/R plotter를 추가했다.
- manifest/canonical cell, oracle, fallback, runtime scan, teardown, cold/warm plan 및 instruction
  fingerprint, planner trace completion, warm log SHA-256을 fail-closed 검증한다.
- 동일 warm raw coordinator log에서 `Total compilation time`을 추출한다.
- 열은 모든 네 planner가 존재하는 동일 profile/worker 블록의 runtime `max-min` 중 최대값을
  기준으로 작은 차이부터 큰 차이 순으로 정렬한다.
- 행은 `LAN → WAN-Light → WAN-Mid`, x축은 workers 1–4, y축은 0부터 시작하며 workload 열별
  최대값을 세 행이 공유한다.
- 미실행 셀은 `not yet executed`, 부분 셀은 `partial n/16`으로 표시한다.

### 수정 파일

- `scripts/federated_campaign/plot_fixed_stage_latest.py`
- `scripts/federated_campaign/plot_fixed_stage_latest.R`
- `docs/SESSION_ISSUES_2026-08-16.md`

### 검증

- `python3 -m py_compile scripts/federated_campaign/plot_fixed_stage_latest.py`: 통과
- R source parse: 통과
- snapshot: 184행, canonical configuration 184개로 중복 0
- planner count: DP/FedAll/Heuristic/MinST 각 46개
- profile count: WAN-Light 112, WAN-Mid 72, LAN 0
- latest alias와 versioned PNG/SVG: 각각 byte-identical
- PNG: runtime/compile 모두 `4200×1800`, RGB
- campaign: 그래프 생성 후에도 PID `3153029` 생존, `failed=false`

산출물:

- `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1/plots/runtime_grid_3x7_fixed_stage_184of336.png`
- `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1/plots/compile_time_grid_3x7_fixed_stage_184of336.png`
- `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1/plots/fixed_stage_snapshot_184of336.csv`
- `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1/plots/latest_fixed_stage_plot_receipt.json`

### 잔여 이슈

- 캠페인은 184/336 시점에 계속 실행 중이므로 LAN은 아직 미실행이며 WAN-Mid LogReg는 8/16만 완료됐다.
- 이후 cell이 완료되면 `latest` alias는 이 명령을 다시 실행해야 새 prefix를 반영한다.

### 잠재 회귀 위험

- **위험**: 실행 중 append와 동시에 읽어 progress보다 많거나 적은 행을 포함할 수 있음.
  - **감지/차단**: 읽기 전후 progress가 동일하고 `completed == rows.jsonl 행 수 == unique validated rows`일 때만 렌더링한다.
- **위험**: 부분적인 4-planner 블록이 workload 정렬을 왜곡할 수 있음.
  - **감지/차단**: 네 planner가 모두 완료된 profile/worker 블록만 runtime spread 계산에 사용한다.

## 208/336 campaign 상태 점검 및 root filesystem 용량 위험

- **상태**: 진행중 — 현재 셀은 정상 실행 중이나 전체 336-cell 완료 전 저장 공간 고갈 가능성이 높음
- **환경/조건**:
  - 확인 시각: `2026-08-16T18:39:43+02:00`
  - progress: 208/336, `failed=false`
  - current cell: `workers=2|planner=FedAll|workload=steplm|profile=wan_mid`
  - runner PID: `3153029`
- **적용 원칙/제약**: 진행 중 Docker campaign의 프로세스·컨테이너·파일을 중단/변경하지 않고 read-only 증거로 상태를 판단한다.
- **의사결정 근거**: 현재 셀의 로그가 계속 증가하고 CPU를 사용하므로 장시간 실행을 hang으로 오판해 재실행하지 않는다.

### 관측 증상

- WAN-Light 112개와 WAN-Mid의 KMeans/PCA/LM/L2SVM/LogReg/ALS 96개가 완료됐다.
- 현재 WAN-Mid StepLM 첫 셀(209번)이 실행 중이다.
- cold runtime은 `1759.614s`로 성공했고 warm coordinator JVM이 실행 중이다.
- warm log는 8초 동안 `1,040,220 bytes` 증가했고 coordinator Java는 약 8.7% CPU를 사용했다.
- 세 Docker container와 worker/coordinator JVM이 모두 살아 있다.
- root filesystem은 413G 중 376G 사용, 약 16GiB만 남아 있다.

### 원인 분석

현재 campaign은 phase bundle과 stage runtime/audit log를 서로 다른 inode로 각각 보존한다.
완료 prefix 기준 active result root가 8.6G, stage `results`가 9.2G이다. 특히 완료된 WAN-Light
StepLM cell은 result bundle만 cell당 평균 약 420.7MB이며 stage에도 raw/runtime 증거가 별도로
남는다. 동일 분포를 단순 외삽하면 남은 WAN-Mid StepLM과 전체 LAN에 result+stage 합계 약
29GB가 추가될 수 있어 현재 16GiB free space보다 크다. 이는 용량 계획용 추정이며 정확한 최종
크기는 planner/runtime log 크기에 따라 달라진다.

### 검증

- `progress.json`: 208/336, `failed=false`
- `rows.jsonl`: 208행
- last three rows: oracle/runtime audit 모두 통과
- current warm log 8초 growth: 1,040,220 bytes
- current coordinator JVM: alive, warm phase
- `/dev/sda2`: 96% 사용, 17,158,684,672 bytes available

### 잔여 이슈

- 현재 셀은 hang이 아니므로 중단하지 않았다.
- 전체 campaign 완료를 위해서는 실행 결과의 경로/해시 계약을 유지하는 방식으로 별도 filesystem에
  증거를 offload하거나 root에서 campaign과 무관한 데이터를 정리해야 한다.
- 실행 중 측정 조건을 오염시키지 않도록 현재 셀 동안 대규모 복사/압축은 수행하지 않았다.

### 잠재 회귀 위험

- **위험**: 디스크가 가득 차 response/row/audit 파일이 부분 기록되어 campaign이 실패할 수 있음.
  - **감지/차단**: 각 cell 경계에서 free bytes와 예상 다음 cell 크기를 확인하고, 결과 계약을 보존하는 offload 절차를 적용해야 한다.
- **위험**: stage log만 임의 삭제하면 기존 row의 audit path 및 재검증 가능성을 잃을 수 있음.
  - **감지/차단**: 삭제 대신 대용량 별도 filesystem으로 이동 후 원래 absolute path를 보존하는 검증된 방식만 사용한다.
