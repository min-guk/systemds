# FedPlanner Memo C26: 전체 workload W1/3/5/7 planning 결과

## 결론

**56조건 중4조건만성공했다. GNMF는W1/W3/W5/W7 모두OPTIMAL이며,나머지workload는현재C26에서완료된플랜을확인하지못했다. 전체workload가planning가능하다고보고할수없다.**

Production은GitHubmain에게시된C26 `802cd1381f` 그대로다. 어댑터 `1a8c478775`,sealed라이브러리CWD환경수정 `762b8af64e`만추가했으며Java planner/Oracle/privacy/cost semantics는변경하지않았다.

## 조건별 결과

성공행의시간은**Memo5단계합**이다. 괄호의wall은실패까지Docker/JVM시작을포함한외부시간이며planning완료시간이아니다. T는coordinator300초제한이고실제Memo총시간은측정완료되지않았다.

| Workload | W1 | W3 | W5 | W7 |
|---|---:|---:|---:|---:|
| ml:pca | S (2.094s wall) | S (2.184s wall) | S (2.259s wall) | S (1.961s wall) |
| ml:als | S (2.766s wall) | A (2.765s wall) | A (2.711s wall) | A (3.115s wall) |
| ml:kmeans | P (2.324s wall) | P (2.068s wall) | P (2.379s wall) | P (2.168s wall) |
| ml:lm | U (2.309s wall) | U (2.313s wall) | U (2.236s wall) | U (2.367s wall) |
| ml:logreg | T 300s 제한 | T 300s 제한 | T 300s 제한 | T 300s 제한 |
| ml:l2svm | I (2.806s wall) | I (2.720s wall) | I (2.898s wall) | I (3.078s wall) |
| ml:steplm | C (2.999s wall) | C (2.921s wall) | C (3.071s wall) | C (3.766s wall) |
| ml:glm | T 300s 제한 | T 300s 제한 | T 300s 제한 | T 300s 제한 |
| ml:gnmf | 성공 0.570s | 성공 0.849s | 성공 0.727s | 성공 0.735s |
| ml:gmm | N (1.997s wall) | N (2.337s wall) | N (2.144s wall) | N (2.473s wall) |
| p1:P1_FULL | P (2.163s wall) | P (2.559s wall) | P (2.489s wall) | P (2.243s wall) |
| p2:P2_PREP | S (2.120s wall) | Q (1.899s wall) | S (2.044s wall) | Q (2.051s wall) |
| sliceline:ADULT | P (2.198s wall) | P (2.188s wall) | P (2.280s wall) | P (2.019s wall) |
| sliceline:COVTYPE | P (2.259s wall) | P (2.568s wall) | P (2.516s wall) | P (2.430s wall) |

- P:PARFOR미지원; S:정확한shape/cost정보부족; A:실행layout모호성; Q:canonical ingress위반; U:UNRESOLVED; I:보고된INFEASIBLE; C:unresolvedcostcontext; N:위치미확정NPE; T:timeout(불가능증명아님).
- 실패행에서Memo/fullcompile시간은null이며wall이나부분phase합으로대체하지않았다.

## 성공한GNMF의시간 구분

| Workers | Memo planning | 전체 initial planning | 전체 compile | 외부 wall |
|---|---:|---:|---:|---:|
| 1 | 0.570333045s | 1.237062982s | 1.243021s | 2.247933s |
| 3 | 0.848987902s | 1.628944574s | 1.636963s | 2.733817s |
| 5 | 0.726946754s | 1.380324808s | 1.385830s | 2.474763s |
| 7 | 0.735119537s | 1.403586412s | 1.409474s | 2.391723s |

## 실패 원인 (로그에서 관측)

- **ml:pca**: EIGEN multi-return output가 UNKNOWN -1×-1이어서 정확한 비용을 만들지 못함. [W1 원로그](evidence/memo-w1357-planning-2026-10-14/initial56/attempts/ml-pca-w1/coordinator.log).
- **ml:als**: W1은V/TRead -1×-1로 UNKNOWN_SIZE. W3/W5/W7은wdivmm의[ROW,LOCAL,COL,LOCAL] FED/LOCAL 실행layout을하나로증명하지못해 AMBIGUOUS_FED_EXECUTION_LAYOUT. [W1 원로그](evidence/memo-w1357-planning-2026-10-14/initial56/attempts/ml-als-w1/coordinator.log).
- **ml:kmeans**: UNSUPPORTED_PARFOR; 현재Memo는sequential loop만지원. [W1 원로그](evidence/memo-w1357-planning-2026-10-14/initial56/attempts/ml-kmeans-w1/coordinator.log).
- **ml:lm**: UNRESOLVED; solve의NATIVE_CATALOG_NOT_IMPLEMENTED/COVERAGE_GAP. privacy-pruned진단도함께출력. [W1 원로그](evidence/memo-w1357-planning-2026-10-14/initial56/attempts/ml-lm-w1/coordinator.log).
- **ml:logreg**: facts/rules/model완료후model.optimize()에서coordinator300초deadline. 완료된optimizer/Memo총시간없음. [W1 원로그](evidence/memo-w1357-planning-2026-10-14/initial56/attempts/ml-logreg-w1/coordinator.log).
- **ml:l2svm**: 보고된INFEASIBLE; scalarINT64↔FP64 LOOP_CARRIED_SHAPE_MISMATCH와 MOVEMENT_FACTOR_WIDTH width11. 실제workload의수학적불가능을의미한다고단정하지않음. [W1 원로그](evidence/memo-w1357-planning-2026-10-14/initial56/attempts/ml-l2svm-w1/coordinator.log).
- **ml:steplm**: 중첩call/loopphase의UNRESOLVED_COST_CONTEXT. [W1 원로그](evidence/memo-w1357-planning-2026-10-14/initial56/attempts/ml-steplm-w1/coordinator.log).
- **ml:glm**: facts/rules/model완료후model.optimize()에서coordinator300초deadline. 완료된optimizer/Memo총시간없음. [W1 원로그](evidence/memo-w1357-planning-2026-10-14/initial56/attempts/ml-glm-w1/coordinator.log).
- **ml:gnmf**: OPTIMAL+RuntimeProgram구성/compile완료,worker수일치,executionSeconds=0. [W1 원로그](evidence/memo-w1357-planning-2026-10-14/initial56/attempts/ml-gnmf-w1/coordinator.log).
- **ml:gmm**: NullPointerException: Cannot invoke java.lang.Double.doubleValue(). 완료된phase/stack이없어컴파일러와Memo중정확한발생위치는미확정. [W1 원로그](evidence/memo-w1357-planning-2026-10-14/initial56/attempts/ml-gmm-w1/coordinator.log).
- **p1:P1_FULL**: relative source환경복구후 Memo facts UNSUPPORTED_PARFOR(region54). [W1 원로그](evidence/memo-w1357-planning-2026-10-14/dependency12/attempts/p1-P1_FULL-w1/coordinator.log).
- **p2:P2_PREP**: W1/W5는nrow의X/TRead -1×-1로 UNKNOWN_SIZE. W3/W7은canonical ingress 위반(각workerindex1/5). 원P2public-recode메타데이터opt-in은유지했고ranges/workerbinding을변경하지않음. [W1 원로그](evidence/memo-w1357-planning-2026-10-14/initial56/attempts/p2-P2_PREP-w1/coordinator.log).
- **sliceline:ADULT**: relative source환경복구후 UNSUPPORTED_PARFOR(region8). [W1 원로그](evidence/memo-w1357-planning-2026-10-14/dependency12/attempts/sliceline-ADULT-w1/coordinator.log).
- **sliceline:COVTYPE**: relative source환경복구후 UNSUPPORTED_PARFOR(region8). [W1 원로그](evidence/memo-w1357-planning-2026-10-14/dependency12/attempts/sliceline-COVTYPE-w1/coordinator.log).

## 실행 범위와 정확성 한계

- ML10+P1/P2+SliceLineADULT/COVTYPE,worker1/3/5/7의봉인된DML/ranges/metadata/privacy/seed를사용했다. ML은50000×128이며앞선192×8 tinyE2E와다르다. 따라서기존LogReg3.028초/GLM5.072초와동일입력성능비교가아니다.
- pinnedDockerimage sha256:2816d74…,4CPU/8GiB,coordinatorheap3GiB/Active4,nativeBLASnone/codegenfalse,C26productiondefault비용(추가envoverride없음). 과거dynamicMKL/LANcalibration비교로주장하지않는다.
- compile-only는RuntimeProgram구성까지이며training은실행하지않았다. GNMF의성공은planning/compile성공이지학습결과정확성검증이아니다. 실제binarydata디렉터리/블록은봉인stage에존재하지만runtimepayload읽기나workerJVM/RPC는수행하지않았다.
- 성공4건은각1개의OPTIMAL Memo/Searchreceipt,요청worker수일치,전체compile/RuntimeProgramreceipt,executionSeconds0을확인했다. timeout8건은facts/rules/model완료후optimizer가끝나지않았고정확한optimizer소요시간은없다.
- DMLScript가일부오류를출력하고exit0으로끝내므로exitcode만으로성공판정하지않았다. 원rawcategory evidence_validation_failure36건도실제오류로그와완료receipt부재로실패다. raw결과는그대로보존하고observedOutcome을별도추가했다.
- GMM예외의정확한발생위치는stack없이확정하지않았다. L2SVM의INFEASIBLE도현재모델보고상태이며실제알고리즘의수학적실행불가능을뜻한다고단정하지않았다.

## 환경 복구와 실행 횟수 (숨기지 않음)

1. /grid직접bind의첫56환경시도는SnapDocker에서script를찾지못해JVM시작0회였다. 원artifact는보존했다.
2. /home실행56조건중P1/SliceLine12는relative source라이브러리CWD가잘못돼ParseException으로Memo에진입하지못했다. 이원본도보존했다.
3. sealed원실험CWD1행만수정해그12조건을실행했다. 44나머지조건은재실행하지않았다. 최종표는44원조건+12환경수정조건으로구성했다. 동일조건에서완료된Memo성능측정의재시도는없다.
4. 원DML/config/ranges/privacy/Java/자원은같고,수정된것은상대라이브러리를찾는CWD뿐이다. 원결과를덮어쓰지않았다.

## 검증·산출물

- productionJava는C26동결12707파일과동일,하네스unit12/12/syntax/diffcheckPASS,독립입력/결과reviewPASS.
- 352주소·worker/range위치,187distinctmetadata파일,1721실제binaryblock이봉인receipt와일치했다.
- [통합JSON](evidence/memo-w1357-planning-2026-10-14/evaluation.json), [CSV](evidence/memo-w1357-planning-2026-10-14/results.csv), [수집/비교스크립트](evidence/memo-w1357-planning-2026-10-14/collect_matrix.py).
- `initial56`과`dependency12`에원command/DML/config/receipt/log/rc를분리보존한다. `environment-attempts.json`은사전환경실패를기록한다.

## 후속 우선순위 (이번 작업에서는수정하지않음)

1. 실제fullLogReg/GLM optimizertimeout을작은E2E와분리해프로파일링한다.
2. PARFOR facts/support범위,KMeans/P1/SliceLine을지원할지명시적으로설계한다.
3. PCA/EIGEN multi-return,ALS/P2의shape/크기추론,StepLMcostcontext와LMsolvecatalog공백을닫는다.
4. ALS의FEDexecutionlayout,P2의worker/rangecanonicalingress,L2SVMscalarlooptype/넓은movementfactor와GMMNPE를별도회귀fixture로재현·분석한다.

이번검증을통과시키기위해UNKNOWN/DEFER,privacy,feasibility,workerbinding을생략하거나runtimefallback을넣지않았다.
