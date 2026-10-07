# StepLM joint 배치 정렬의 작은 scope 인코딩

## 결과와 작업 기준

작업 시작 시 fetch한 `origin/main`은 `2faff2a2a53ed1f608f2e1f6b2a13ca427d71f52`다. 새 worktree는 `/grid/3/cofee-lm-sweep-mchoi-20260914/steplm-joint-compact-20261007`, 브랜치는 `fix/steplm-joint-compact-20261007`이다. 이전 workspace는 수정하지 않았다. 이전 작업에서 검증한 항상 참인 joint factor의 조기 생략도 이 작업에 포함했다.

원래 20×5 StepLM의 `lmCG.dml:129`에서 발생하던 `EXACT_VE_FACTOR_CELL_OVERFLOW`를 작은 범위의 인코딩으로 제거했다. 기존 canonical predicate는 그대로 두고, solver에는 공급별 배치 증명과 최종 정렬을 분리한 동치 제약을 전달한다. 비용 모델 준비와 DP 플랜 선택·canonical 선택 검증을 통과했고, 실제 full-rank 20×5 학습도 완료했다. 학습 검증에서 추가로 드러난 action/native 지원의 주기2도 기존 CFG 고정점 안에서 갱신 순서를 바로잡아 해결했다. 최종 Java132건·Python31건·Docker10건이 모두 통과했다. StepLM의 계수5개와 선택 순서 `[3,1,5]`는 CP 결과와 정확히 같다. 이번 Docker 측정은 컴파일120.695초, 실행0.903초로, 계획 탐색 지연은 여전히 남는다.

## 무엇이 커졌고 어떻게 나눴는가

기존 factor는 두 입력의 worker pool을 비교하기 위해 재귀 공급 결정 전체를 scope에 모았다. source의 실제 공동 도달 관계는 5×2의 10개 행이지만, 공급자의 realization 및 clause 선택까지 합쳐 21축이 됐다. 압축 후에도 후보 수의 곱이 `1,362,513,693,259,249,090,560,000`이었다. 실제로 이 테이블을 만들기 전에 크기 검사가 실패했다.

변경 후에는 다음 작은 제약들을 사용한다.

1. **공급별 배치 결과**: 각 `(realization reference, supplier, value origin)` 질의는 `INACTIVE` 또는 worker-pool 동치 범주를 가진다. 선택한 공급이 고정 배치를 증명하면 그 범주를 사용한다.
2. **필요한 공급 관계만 활성화**: 선택한 clause와 활성 질의가 요구하는 edge에만 배치 일치와 정확한 child realization-reference 검사를 건다. 불변 배치 증명은 기존 evaluator처럼 child 선택을 다시 요구하지 않는다.
3. **순환 방지**: 질의 그래프의 순환 component에만 rank를 둔다. 활성 edge의 rank가 감소해야 하므로 공급자가 서로를 근거로 삼는 순환 증명을 허용하지 않는다. StepLM에서는 rank 변수 20개가 필요했다.
4. **최종 정렬**: 실제 도달 가능한 각 행에서 DIRECT_FOUT의 도출된 배치와 relocation의 도착 배치만 비교한다. LOCAL 입력은 기존 입력 authority가 처리한다. VALUE_MAP이 비활성인 경우, CP/LOUT, 물리 입력 하나 이하의 기존 우회 의미를 보존한다.

새로 생성하는 회로의 각 factor는 최대 3개 변수만 관찰한다. 이미 canonical scope가 3 이하인 joint는 기존 observation encoding을 유지한다. 같은 reader를 두 번 사용하는 작은 joint까지 보조 변수를 늘리지 않기 위한 선택이다. 검사 결과가 항상 참인 consumer는 앞선 조기 생략 단계에서 질의 그래프도 만들지 않는다. 직접 FED 입력으로 요구될 가능성이 없는 위치의 공급 경로는 탐색하지 않으며, receipt도 도달한 owner에 대해서만 생성한다.

이 변경은 실제 입력 관계를 독립 Cartesian product로 넓히지 않는다. 입력 상관관계가 없는 사례에서도 필요한 물리 정렬은 같은 작은 제약으로 처리한다. 후보 삭제, privacy/TR-TW 완화, runtime fallback, solver 상한 증가, reuse OR-chain 변경은 없다.

## 동치의 범위와 근거

canonical recursive proof가 성공하면 각 활성 질의에 실제 배치를 붙이고, 순환 없는 활성 공급 그래프에 감소 rank를 붙여 새 인코딩을 만족시킬 수 있다. 반대로 새 인코딩의 활성 질의는 정확한 공급 참조, 일치하는 배치, 고정 배치 leaf 및 감소 rank를 가지므로 유한한 canonical proof를 복원할 수 있다. 비활성 질의는 추가 공급 의무를 만들지 않는다.

기존 realization-support factor는 DURABLE_MAP에서 일부 rule identity를 합치므로, 그것만으로 모든 정확한 reference 검사를 대신하지 않았다. 작은 조건부 reference 제약을 보존했다. Alias projection에서는 제거한 alias를 결정 변수로 되살리지 않고 원래 full reference를 가진 가상 질의로 유지한다. Projection 이후 canonical scope 밖의 receipt를 요구하는 transition은 기존 evaluator의 missing-receipt 실패와 동일하게 INVALID다.

기존 거대 canonical factor는 선택된 assignment 평가 및 회귀 oracle로 남는다. 일반 DP/Global cost preparation은 작은 encoded factors를 사용한다. 새 회로는 기존 observation-star의 degree-two 가정을 사용하지 않으며, 해당 star에만 유효한 truth-table quotient 경로를 건너뛴다. 비용 fingerprint에는 새 encoding의 실제 transition·edge·pool·activation 의미를 포함한다.

## 20×5 계측

같은 canonical analysis에서 비교했다. 아래 legacy는 앞선 조기 생략만 적용하고, 남은 joint는 기존 observation-star로 표현한 reference다. 두 모델의 원래 결정·대안 및 canonical hard predicate는 동일하다.

| 항목 | legacy | compact |
|---|---:|---:|
| 원래 결정 변수 | 556 | 556 |
| canonical hard factors | 1,559 | 1,559 |
| 전체 hard 보조 변수 | 183 | 365 |
| 전체 encoded hard factors | 1,742 | 2,072 |
| 전체 encoded hard 최대 scope | 21 | 3 |
| 남은 joint 보조 변수 | 21 | 203 |
| 남은 joint encoded factors | 22 | 352 |
| 남은 joint 총 encoded cells | long 범위 초과 | 46,832 |
| 전체 encoded hard 최대 table cells | 약 1.36×10²⁴ | 45,325 |
| 전체 encoded hard 총 table cells | 약 1.36×10²⁴ | 625,066 |
| 비용 모델 preflight | overflow | 통과 |

깨끗한 origin/main은 조기 생략 전이므로 canonical hard 1,561개, hard 보조 변수 201개, encoded hard 1,762개다. 이 전체 변경은 그중 항상 참인 factor 2개를 제거한 뒤, 남는 joint factor의 encoding을 분해한다. 보조 변수와 factor **개수는 늘지만**, 모든 공급 결정을 곱한 거대 테이블이 사라지는 교환이다.

전체 hard+monetary encoded graph에 동일한 production symbolic order portfolio를 적용한 진단:

| 항목 | legacy | compact |
|---|---:|---:|
| encoded variables | 2,568 | 2,750 |
| encoded factors | 6,149 | 6,479 |
| 선택된 symbolic order의 induced width / 최대 separator 변수 수 | 21 | 16 |
| 최대 separator의 개념적 cells | 973,963,649,323,716,638,146,560,000 | 27,539,398,656 |
| separator들의 개념적 cells 합 | 1,008,019,734,536,441,613,827,555,185 | 85,389,307,019 |

이 진단은 input-size gate 앞에서 **순서와 scope만 계산**한 것이며, 테이블 할당 또는 solver 상한 변경이 아니다. 축소 전 전체 graph의 단일 전역 VE 테이블은 compact에서도 크다. 위 숫자는 실제 retained memory가 아니며, DP의 축소·regional 메시지 처리 이후 실제 보관량과 구분한다. 모든 가능한 elimination order에 대한 최적 treewidth나 모든 workload의 induced-width 비악화를 증명한 것도 아니다. 확인한 비악화 수치는 이 StepLM graph의 동일 order portfolio 비교다.

최초 host compile-only 진단에서 compact 비용 모델 준비 3.428초, DP optimization 105.710초, canonical objective `37019.63574473899` ms(`4675320753577875922` bits)를 얻었다. 선택된 계획은 기존 canonical hard predicate와 최종 physical selection 검증을 통과했다. 이후 최종 코드의 계측도 동일한 scope/cell 수와 비용 contribution 2,578개를 확인했다. 같은 JVM의 준비 시간이나 host 수치를 training speedup으로 해석하지 않는다. 기존 인코딩이 compile에 실패하므로 성공한 학습끼리의 A/B 속도 비율도 제시하지 않는다.

## 최종 검증 기록

- 최종 소스 중앙 회귀: **25개 Java 클래스132/132 PASS**, 실패·오류·skip0. joint/cost/shared-source 및 원래 StepLM 전체 compile92건, publication/CFG/memo/scheduling 및 소형 loop40건이다. 전체 compile 회귀는142.804초다. host 단일 회귀 시간을 성능 A/B로 사용하지 않는다.
- 작은 공간에서 canonical assignment를 전수 열거하고 보조 변수를 존재 소거한 합법성이 같은지 검사한다. Monetary contribution의 ID/scope/raw bits와 가능한 사례의 Global 선택·비용도 비교한다. Correlated AA/BB, 독립 AB/BA, alias loop, 반복 function call 및 실제 SCC cycle을 포함한다.
- Cycle 회귀는 실제 rank 변수12개를 만들며, rank 제약을 제거할 때만 거짓 자기 증명이 허용되는 witness를 확인한다. 반복 reader 회귀는 실제 VALUE_MAP·복수 물리 입력이 있는 canonical scope2에서 legacy/default의 factor·auxiliary·scope·descriptor·비용·선택이 같은지 검사한다.
- Publication 회귀는 정확한 lmCG q occurrence와 현재 생산자의 DURABLE_MAP 참조를 확인한다. 만료 source/action·전체 reaching writer·유효한 sibling 보존 회귀, acyclic scheduling과 반복 동시 closure의 동치 및 SCC 동시 transfer 의미도 통과했다.
- Python harness31/31 PASS, 실제 CP/FED Parser/HOP 검사 PASS. `mvn -q -Dmaven.test.skip=true package`, `git diff --check`, 신규 소스의 고립 javac 검사 PASS. 검증 대상 소스3,603개가 중앙 회귀·빌드 중 변경되지 않았고, 최종 소스와 class/resource4,374개가 Docker 전후에도 같음을 hash로 확인했다.
- 별도 architectural review는 core circuit·snapshot memo·publication 수정 모두 CLEAR다. Canonical 의미, cycle/alias 및 scope 밖 receipt, privacy/전체 reaching-definition/철회 검증 경계와 dirty revision을 확인했다.
- 최종 `publication-final-shape.log`에서 위 factor/cell/induced-width 수치를 모두 재확인했다. Analysis29.459초·cost surface3.335초는 단위 회귀와 병행한 compile-only 진단이며 성능 비교가 아니다.
- 초기 cycle fixture에는 invariant shortcut 때문에 SCC가 없어 실패했다. 실제 순환 fixture로 보완한 원본 로그를 보존한다. 처음 package의 잘못된 skip property로 실행된 Python gateway StartupTest의 log-count1건 실패는 baseline7건 및 최종 단독7건 모두 통과했고 관련 코드는 바꾸지 않았다. 대형 metadata 테스트 선택 오류는 아래에 별도로 기록한다.

## 실제 Docker 입력과 추가 비교 최적화

첫 Docker run `steplm-joint-compact-01`에서는 joint micro case 6건이 모두 통과했다. 상관 AA/BB, 독립 AB의 합법 경로, 보호된 AB의 계획 거부, loop, 반복 function call을 검사했으며 성공 실행의 CP/FED 값이 같고 runtime audit 위반이 없다.

원격 CSV 기반 `ml_steplm`은 joint 생성 전 common analysis에서 `Executable realization/action composition did not converge`를 출력했고, 종료를 기다리던 process는 600초 timeout으로 rc124가 됐다. 모델 출력과 planner checkpoint가 없으므로 학습 성공이나 audit 검증으로 집계하지 않는다. 처음에는 CSV 경로로 분류했지만, 아래 후속 실행에서 파일 입력 없는 동일 값의 StepLM도 실패해 공통 publication 문제로 정정했다.

분리한 compile-only 진단에서 원래 `local_matrix` 입력에 Docker와 동일한 selection 출력·fingerprint roots를 붙이면 27.256초/pass4에 수렴했다. 한 pass 안의 relocation support 8→6→8 변동은 pass 경계에서 같아 오류의 근거가 아니다. 원격 입력의 host compile-only는 privacy metadata 조회가 필요해 중단했으며 학습 실행은 하지 않았다. 원래 source 의미를 실제 학습으로 검증하기 위해 기존 full-rank20×5 데이터를 DML literal로 생성하는 opt-in `ml_steplm_local_matrix`를 추가했다. 기존 원격 case의 입력과 검증 조건은 유지한다.

별도로 수집한 stack에서 `LoopSeedRevision`이 같은 immutable proof snapshot 쌍을 reader마다 반복 비교했다. `LoopSeedProofSnapshot`에 정확한 비교 결과 한 항목을 memo하여 중복 구조 비교를 없앴다. Hash/key/전달 규칙은 같고 mutable 일반 List는 cache하지 않는다. 수정 전 반복 비교 회귀3건 실패, 수정 후4건 모두 통과했다. 중앙 memo/인접 CFG 회귀도 통과했으며 원래 전체 compile은 재실행에서150.405초에 통과했다. 이 단일 host 시간 차이를 성능 개선으로 해석하지 않는다.

중앙 회귀 클래스 전체 선택 중 기존50,000×2,100 metadata-only 분석1건이 포함된 선택 오류가 있었다. 대형 학습을 실행한 것은 아니지만 대형 제외 지시에는 맞지 않았다. 최종 재검증은 소형5개 메서드를 명시했다. 이후 compact9건·전체 compile1건과 함께15건 통과, Java source3,588개 변경0을 확인했다. 원본 실패·선택 오류 기록은 보존한다.

두 번째 Docker run `steplm-joint-compact-02`에서는 joint 6건 및 작은 LogReg/L2SVM/lmCG 3건이 통과했다. 실제 학습 계수 16/8/8개의 CP 대비 최대 절대 차이는 각각 `2.22e-16`, `8.41e-17`, `1.22e-15`이며 runtime audit 위반은 0이다. 컴파일 시간은 35.910/10.947/5.337초, 실행 시간은 2.846/0.950/0.475초다. 동일 입력의 반복 성능 실험은 아니므로 속도 개선율로 해석하지 않는다.

파일 입력 없는 full-rank 20×5 StepLM은 이 실행에서도 common analysis가 실패했다. DMLScript가 오류를 출력한 뒤 rc0으로 끝났지만, harness는 모델·변수 선택·planner trace가 없음을 검사하여 실패로 판정했다. CP의 변수 선택은 `[3,1,5]`다. 따라서 이 run의 전체 결과는 **9/10 통과, FAILED**이며, 실제 StepLM 학습 성공을 아직 주장하지 않는다.

같은 입력의 bounded compile-only 진단에서 `lmCG.dml:129`의 안쪽 matmul과 바깥 `q` 두 owner만 FULL/BROADCAST 지원을 번갈아 잃는 주기2를 확인했다. 소비자의 DIRECT/RELOCATION 지원을 `source-prune`에서 삭제한 뒤 생산자의 action-backed 물리 사본 정보가 `final-action-bind`에서 복원된다. 전체 owner를 오래된 단일 snapshot으로 binding하므로 소비자에게 갱신이 한 단계 늦게 전달되는 문제다.

최종 수정은 기존 CFG 고정점 안에서 action binding과 direct 전파를 함께 완료한다. 새 action receipt를 아직 처리하지 않은 direct closure의 revision을 정확히 남겨 다음 dirty work에서 소비하도록 했다. Relocation binder는 compiled-input SCC를 생산자 순서로 처리하며, SCC 내부는 한 snapshot을 읽고 동시에 갱신한다. owner별 lazy source inventory와 fact index를 사용해 SCC마다 전체 graph를 다시 순회하지 않는다. 전체 CFG를 바깥에서 추가 반복하는 초안은 채택하지 않았다. 기존 expired source/action 삭제, privacy·전체 reaching-definition 검증 및 최종 고정점 판정은 유지한다.

고립 컴파일 검증에서는 실제 값·selection 출력·네 fingerprint root를 포함한 StepLM이28.272초에 수렴했다. 정확한 lmCG q occurrence의 두 DIRECT route가 현재 생산자의 DURABLE_MAP에 연결됨을 확인한다. 기존 인접32건과 새 scheduling2건도 통과했다. Scheduling 회귀는 단순 종료 대신 acyclic 한 번의 binding과 기존 반복 동시 closure의 동치, fact 순서 독립성, cyclic SCC의 동시 transfer 의미를 확인한다. 이 수정의 독립 최종 리뷰는 CLEAR이며, 위 중앙132건과 아래 실제 Docker 검증까지 통과했다.

## 최종 Docker 결과와 남은 성능 한계

`run_LAN_docker.sh --joint-boundary-e2e`의 `steplm-joint-compact-03` 결과는 **10/10 PASS**다. 동일 이미지 `sha256:2816d74b…`, container4CPU/8GB, coordinator3GB, worker3개×1GB 조건이며 대형 학습은 실행하지 않았다. Class/overlay preflight, 물리 모델 proof10건, 전체 모델·선택·planner trace와 runtime audit가 모두 통과했다. Runtime conversion/audit 위반은0이다.

| 실제 작은 workload | 컴파일(초) | 실행(초) | CP 대비 전체 계수 최대 절대 차이 |
|---|---:|---:|---:|
| StepLM20×5 | 120.694863 | 0.903 | 0 (5개 모두 동일) |
| LogReg | 32.664358 | 3.038 | 2.22e-16 (16개) |
| L2SVM | 12.047498 | 0.781 | 8.41e-17 (8개) |
| lmCG | 5.131495 | 0.513 | 1.22e-15 (8개) |

StepLM의 선택 순서는 CP/FED 모두 `[3,1,5]`다. 이전 run02에서는 같은 입력이 common analysis에서 실패했다. 따라서 이번 결과는 **실패하던 경로가 실제 학습까지 성공했다는 증거**이며, 성공한 baseline 학습 대비 속도 개선율은 계산할 수 없다. 나머지 모델도 단일 전후 실행이므로 유의한 속도 향상으로 일반화하지 않는다.

StepLM 계획 탐색 자체는97.003초가 걸렸다. 기존 `RESOURCE` 종료 조건에서 검증된 합법 계획을 반환했고, canonical upper cost는37040.115993804146ms, lower bound는19529.714826506737ms다. 전역 최적성을 증명한 결과가 아니며 약89.66%의 상대 gap과 추가 계획 탐색 비용이 남는다. 상한을 높이거나 runtime fallback을 추가하지 않았다.

실제 regional planner 원본 로그의 checkpoint2,000개에서 관측한 `retainedSlots` 최대값은380,667,752이고 종료 시0이다. 이는 해당 planner의 table-slot 계수이며 JVM peak heap 측정이 아니다. Input hard table의625,066셀, symbolic separator cells 및 이 실행 중 계수를 혼동하지 않는다. 기존 인코딩은 preflight에서 실패하므로 같은 실제 학습의 retained-slot 전후 비교는 없다.

원격 CSV StepLM은 이번 최종 수정 후 다시 실행하지 않았다. CSV 출력 문제와 대형/전역 Exact 최적화는 이 완료 범위에 포함하지 않는다. 이번 수정으로 해소한 것은 joint input-table overflow, 무의미한 joint 검사 생성, 반복 snapshot 비교, 실제20×5의 action/native 지원 주기2다.

## 수정 파일과 증거

- `ExactJointAlignmentEncoding.java`: 최대 scope 3의 공급 배치/행 정렬 회로.
- `JointValueMapRelations.java`: 기존 evaluator를 바꾸지 않고 local proof transition을 노출.
- `ExactPhysicalModel.java`: 기존 canonical predicate와 새 solver encoding 연결, 항상 참인 joint의 조기 생략, legacy test oracle.
- `ExactHardFactorObservationDecomposition.java`, `ExactPhysicalSharedSourceEncoding.java`: 일반 회로와 observation-star를 구분.
- `JointCompactAlignmentTest.java`, `JointEarlyPruningTest.java`: 동치·조기 생략 회귀. 기존 alias/partial-truth 테스트는 각 oracle의 범위를 명확히 했다.
- `PlacementRelationClosure.java`: immutable snapshot 비교 memo와 action/native 지원의 기존 CFG 고정점 통합, owner별 relocation binding.
- `LoopSeedSnapshotEqualityMemoTest.java`, `StepLmPublicationReceiptClosureTest.java`, `RelocationBindingScheduleTest.java`: memo 경계, 실제20×5 지원 완전성, scheduling 동치 회귀.
- `run_joint_boundary_e2e.py` 및 해당 Python 테스트: 기존20×5 데이터를 파일 입력 없이 만드는 opt-in case와 동일 모델·변수 선택 검증.

원시 로그와 compile-only 진단 소스는 이 worktree의 `target/joint-compact-evidence/`에 있다. 기준 실패는 `baseline-steplm.log`, 최초 완료는 `compact-steplm-01.log`, 최종 scope/width는 `publication-final-shape.log`, 132건 회귀는 `publication-final-common/`과 `publication-final-core/`다. 공통 publication 진단은 `publication-probes/`에 있다. 실제 실행 원본은 `/grid/3/cofee-lm-sweep-mchoi-20260914/steplm-joint-compact-validation-20261007/`에 보존한다. 대형 학습과 별도 CSV 출력 오류 수정은 범위에서 제외한다.
