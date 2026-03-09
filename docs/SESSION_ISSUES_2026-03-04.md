# SESSION ISSUES (2026-03-04)

## 이슈 1: FedAll/FedHeuristic Oracle trace에서 TR/TW rewire 링크가 비어 보이는 문제

- **상태**: 해결(관측 계층)
- **환경/조건**: `compile_fed_all`, `compile_fed_heuristic`, `lm/P2P2D`, `wan_mid`, `w=1`, planner trace on
- **재현 절차**:
  - `SYSDS_FED_PLANNER_TRACE=1 RUN_ID=oraclecmp_fedall_rewirealign_lm_wanmid_w1_20260304 ... --conf mkl-fout --salg lm`
  - 로그: `experiments/results/fed1/mkl-fout/lm_dataset-P2P2D_dams-so002_mkl-fout_oraclecmp_fedall_rewirealign_lm_wanmid_w1_20260304.log`
- **관측 증상**:
  - `hop=708(TWrite r),191(TWrite p),265(TRead p),268(TRead r)`에서 `rewireChildIDs=[]`, `rewireParentIDs=[]`로 출력되어 DP 대비 rewire가 없는 것처럼 보임
- **원인 분석**:
  - `FederatedPlannerFedAll.logPlannerDecision(...)`가 `FederatedPlannerLogger.logOracleDecision(..., rewireTable=null)`로 호출되어, 실제 테이블과 무관하게 rewire 컬럼이 빈 값으로 출력됨
- **해결 요약**:
  - FedAll 로그 경로에서 실제 `rewireTable` 전달
  - Oracle input 수집도 `collectOracleInputFTypes(..., rewireTable)` 사용으로 통일
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedAll/FederatedPlannerFedAll.java`
- **검증**:
  - `oraclecmp_fedall_rewirefix_lm_wanmid_w1_20260304`, `oraclecmp_heur_rewirefix_lm_wanmid_w1_20260304`
  - 동일 hop에서 `rewireParentIDs=[265/268]`, `rewireChildIDs=[191/708]` 확인
- **잔여 이슈**:
  - 성능 자체(실행시간) 개선은 아직 확인되지 않음
- **잠재 회귀 위험**:
  - 로그/trace 출력량 증가. 감지는 oracle line count 및 실행시간 비교로 수행
- **의사결정 근거**:
  - **관측 계층(로그 경로) 수정**

---

## 이슈 2: FedAll의 TRead FType 결정이 변수명 기반(`fedVars`)에 과의존하는 문제

- **상태**: 진행중(기능 보강 반영, 추가 검증 필요)
- **환경/조건**: `compile_fed_all`, TR/TW 체인(workload 반복 루프 구간)
- **재현 절차**:
  - 위 LM trace 케이스에서 TR/TW hop 추적
- **관측 증상**:
  - TRead가 연결된 TWrite의 실제 계획 타입 대신 이름 기반 최신 상태만 참조할 여지가 있음
- **원인 분석**:
  - `TRANSIENTREAD` 처리 경로가 `fedVars.get(name)` 중심이며, rewire 연결(write-hop) 정보를 직접 활용하지 않음
- **해결 요약**:
  - `resolveTransientReadFType(...)` 도입
    - rewire로 연결된 `TRANSIENTWRITE`들의 타입을 우선 조회(memo/fTypeMap/input fallback)
    - 일치 타입이면 채택, 충돌 시 local(null)로 안전 복귀
    - rewire 정보가 없으면 기존 `fedVars` fallback 유지
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedAll/FederatedPlannerFedAll.java`
- **검증**:
  - LM `wan_mid/w1` FedAll/Heuristic trace 재실행 완료
  - TR/TW rewire 링크 표시는 정상화
  - 실행시간: FedAll 약 `61.1s -> 61.7s` (동일 수준, 유의미 개선 아님)
- **잔여 이슈**:
  - PCA의 `hop=59 rightIndex reason=MISSING_IN_FTYPE` 및 `hop=75 reason=FOUT_NOT_SUPPORTED_BY_RUNTIME` 경로는 여전히 동일
  - 즉, P1 성능 이슈의 주원인은 TR/TW 관측/결정만으로는 충분히 설명되지 않음
- **잠재 회귀 위험**:
  - 다중 write 연결에서 충돌 처리(null 복귀)로 일부 케이스가 더 보수적으로 될 수 있음. 감지는 reason-code(`NO_FED_INPUT`,`MISSING_IN_FTYPE`) 증감 추적으로 수행
- **의사결정 근거**:
  - **플래너(TR/TW 타입결정) 로직 보강**

---

## 이슈 3: recompile 판정 범위를 DAG 전체로 확장(재귀)한 실험이 성능 대회귀를 유발

- **상태**: 실패 후 롤백 완료
- **환경/조건**: `pca/P2P2D`, `wan_light`, `w=1`, all planners (`mkl-cost,mkl-min-st-cut,mkl-heuristic,mkl-fout`)
- **재현 절차**:
  - 패치: `isRecompileRegion(...)`를 immediate-input 검사에서 DAG 전역 재귀(BFS) 검사로 변경
  - 실행: `oraclecmp_*_pca_wanlight_w1_recompdeepfix_20260304`
- **관측 증상**:
  - 실행시간 급증(대표):
    - DP `58.286s -> 113.914s`
    - MinST `66.767s -> 111.644s`
    - Heuristic `61.076s -> 125.019s`
    - FedAll `62.134s -> 117.812s`
  - 핵심 hop에서 `CP/FOUT` 후보가 광범위하게 사라지고 `CP/LOUT` 체인으로 붕괴
- **원인 분석**:
  - recompile flag 전파를 DAG 전체로 확장하면, 원래는 합법적으로 고려되던 물질화/재배치 후보까지 과도하게 차단됨.
  - 결과적으로 모든 planner에서 동일하게 후보공간이 과축소되어 WAN에서 대량 local 경로가 선택됨.
- **해결 요약**:
  - 해당 패치 즉시 롤백(4개 위치)
  - 기존 immediate-input 기반 `isRecompileRegion(...)` 복구
- **수정 파일(롤백 반영)**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedAll/FederatedPlannerFedAll.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTRewire.java`
- **검증**:
  - 롤백 후 `oraclecmp_fedall_pca_wanlight_w1_postrollback_20260304`에서 기존 패턴 복구
  - hop59/75/76/89/90 의 oracle 패턴이 회귀 전 분포로 복귀
- **잔여 이슈**:
  - MinST/DP의 profile별 상대성능 편차(특히 PCA wan_light)는 별도 원인(선택/후처리 단계) 분석 필요
- **잠재 회귀 위험**:
  - recompile 판정 로직 수정 시 전체 planner 동시 성능 붕괴 가능. 감지는 all-planner targeted quartet(w1, pca, wan_light/mid)로 우선 수행
- **의사결정 근거**:
  - **공통 cost/planner feasibility 계층 수정 실험(실패, 전면 롤백)**

---

## 이슈 4: DP output-decision 동률(LOUT==FOUT)에서 LOUT 고정 선택으로 FED 경로가 불필요하게 꺼지는 문제

- **상태**: 반영/검증 진행중
- **환경/조건**: `pca/P2P2D`, `w=1`, `wan_light` (trace on)
- **관측 증상**:
  - `hop=59 (rix)`에서 `lOutAdditional == fOutAdditional` 동률인데 기존 로직이 `<=`로 LOUT 우선 선택
  - 이후 TR/TW 체인 일부가 local로 눌리며 DP가 baseline(heur/fout) 대비 불안정하게 느려지는 케이스 관측
- **원인 분석**:
  - 동률 시 LOUT 고정은 mixed parent(CP/FED) 상황에서 FED lineage를 끊는 방향으로 편향
- **해결 요약**:
  - DP output-decision에 동률 epsilon 도입 후, 동률 시 parent affinity 기반 tie-break 적용
  - FED parent가 있는 경우 FOUT 우선
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
- **검증**:
  - `hop=59` trace에서 `chosen=FOUT` 확인
- **잔여 이슈**:
  - 단일 run 기준 성능 분산이 커 추가 반복 중앙값 평가가 필요

---

## 이슈 5: DP의 TR/TW 충돌해결에서 동률 시 LOUT 우선으로 XReduced가 CP/LOUT으로 내려가는 문제

- **상태**: 반영/검증 진행중
- **환경/조건**: `pca/P2P2D`, `w=1`, `wan_light`
- **관측 증상**:
  - `resolveTransientWriteConflict(...)` 동률에서 LOUT 선택
  - 재컴파일 로그에서 `XReduced(TWrite)`가 `exec=CP, fedOut=LOUT`로 고정되는 케이스 반복
- **원인 분석**:
  - TR/TW 체인의 표현 통일에서 동률 처리가 local 편향
- **해결 요약**:
  - TR/TW 충돌해결에 동률 epsilon 적용
  - 완전 동률에서는 `FOUT` 우선(연쇄 local materialization 완화)
  - 추가로 `isCompatibleWithChildDecisions`의 CP/FOUT 완화는 **CP parent에서만** 허용
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
- **검증(현재까지)**:
  - `wan_light` 4-cell 반복에서 DP gap 변동폭 감소, 3-run median 기준 `dp_gap<=1s` 충족 확인
  - full-profile(12-cell) 반복 3회 중앙값 기준에서도 `lan/wan_light/wan_mid` 모두 `dp_gap<=1s`, `minst_gap<=1s`
- **주의사항**:
  - 단일 run은 여전히 분산이 큼(±수초~10초대). 세션 전략대로 중앙값 판정 유지 필요

---

## 이슈 6: PCA `eigen(...)`를 순수 DML power-iteration으로 치환했을 때 성능 대회귀 + FedAll 런타임 실패

- **상태**: 실패 후 롤백 완료
- **환경/조건**:
  - 파일: `scripts/builtin/pca.dml`
  - 시도 변경: `eigen(C)` 제거, `m_topk_eigen_power(...)` + `m_vec_l2norm(...)` 추가
  - 데이터/워크로드: `P2P2D`, `pca`, `K=10`
- **재현 절차**:
  - localproc smoke:
    - `pca_powereig_smoke2_20260305_0130` (w1, conf=`mkl-cost,mkl-fout`, lan)
    - `pca_powereig_dpminst_w1_lan_20260305_0142` (w1, conf=`mkl-min-st-cut,mkl-cost`, lan)
  - docker single-case:
    - `RUN_ID=pca_powereig_docker_dp_w1_lan_20260305_0146` + `--conf mkl-cost --alg pca --workers 1 --net-profile lan`
    - `RUN_ID=pca_powereig_docker_minst_w1_lan_20260305_0150` + `--conf mkl-min-st-cut ...`
    - `RUN_ID=pca_powereig_docker_fedall_w1_lan_20260305_0152` + `--conf mkl-fout ...`
- **관측 증상**:
  1. **성능 대회귀(DP)**:
     - (기존) `p1_lm_pca_dpminst_only_trace_20260305_0001`: DP `pca/lan/w1` `Total execution time=55.258s`
     - (치환 후) `pca_powereig_dpminst_w1_lan_20260305_0142`: DP `pca/lan/w1` `155.151s`
     - (docker) DP `pca/lan/w1` `145.381s`, `Fed Put Bytes=9,172,873,424`
  2. **FedAll 런타임 실패**:
     - `FED indexing requires federated input but found local at runtime`
     - failing inst 예: `FED?rightIndex?v...`
     - 로그:
       - localproc: `.../pca_dataset-P2P2D_dams-so002_mkl-fout_pca_powereig_smoke3_...log`
       - docker: `.../pca_dataset-P2P2D_coordinator_mkl-fout_pca_powereig_docker_fedall_w1_lan_20260305_0152_lan.log`
- **원인 분석**:
  - power-iteration 내부 루프에서 벡터 `v/w`의 계획상 FType(연합/로컬)과 런타임 실제 타입이 반복 구간에서 어긋남.
  - 그 결과 `rightIndex`/`tsmm` 같은 명령이 FED로 컴파일되었으나 런타임 입력은 local인 케이스 발생.
  - 또한 반복적 정규화/직교화/deflation 경로가 DP에서 대량 `fed_refed` 및 네트워크 put을 유발.
- **해결 요약**:
  - `pca.dml`의 DML power-iteration 치환을 **전면 롤백**하고 기존 `eigen(C)` 경로 복구.
  - 롤백 후 jar 재빌드(`mvn -DskipTests package`) 완료.
- **수정 파일**:
  - (시도/롤백 대상) `scripts/builtin/pca.dml`
- **검증**:
  - 치환 시도 결과와 rollback 이후 재빌드 완료 확인.
  - 결론: 현 시점에서 PCA `eigen`의 단순 DML 치환은 planner/runtime 정합성과 성능 모두에서 채택 불가.
- **잔여 이슈**:
  - PCA의 근본 병목(`eigen` CP 단계) 해소를 위해서는 스크립트 치환보다 **runtime-level FED instruction/capability 확장** 또는 **planner-safe locality control**이 필요.
- **잠재 회귀 위험**:
  - builtin 스크립트에 반복/인덱싱 기반 치환을 넣을 경우 planner별(FedAll/Heur/DP/MinST) 정합성 붕괴 가능.
  - 감지 방법: planner quartet(w1,pca,lan)에서 즉시 fatal signature(`requires federated input but found local`) 스캔.
- **의사결정 근거**:
  - **스크립트 계층 실험(실패) → 안정성 우선 롤백**
