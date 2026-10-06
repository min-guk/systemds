# LogReg single-partition proof worklist 검증

## 범위와 원인

사용자 요청에 따라 LogReg에 집중한다. StepLM·GLM 진단 및 후보 패치는 별도 인계했고 이 변경의 학습 통과로 집계하지 않는다. 실제 `multiLogReg` builtin의 동일 학습을 CP/FED에서 실행한다.

이전 `57933f328c`의 FED JFR 2,873개 execution sample 중 common analysis 2,024개, single-partition 증명 경로 901개였다. 해당 분석 표본의 44.52%다. 분석 호출마다 realization 전체를 복사하고 모든 transfer를 다시 훑는 synchronous fixed point가 반복됐다. 이는 표본 기반 진단이며 정확한 wall time 점유율은 아니다.

## 채택 후보

`PlacementRelationClosure.exactSinglePartitionRealizationProofs`에서 불변 realization inventory의 역방향 의존 그래프를 만든다. 처음에는 모든 realization을 처리하고, 세 possibility bit가 바뀐 source의 dependent만 다시 처리한다. constant native-pool/relocation, source를 읽지 않는 clause, inventory에 없는 reference는 불필요한 edge를 만들지 않는다.

초기 상태는 `(false,false,false)`이며 exact/nonSingle/unknown bit는 단조 증가한다. 각 source는 최대 세 번 상태가 바뀌므로 transfer 호출은 초기 V개와 변경 edge 알림에 의해 제한된다. 한 transfer 안의 clause scan과 invocation마다 그래프를 만드는 비용은 남는다. self-edge, 완전한 세 bit 비교, duplicate reference의 기존 overwrite 동작을 유지한다. 분류 우선순위도 `NON_SINGLE > UNKNOWN > EXACT > UNAVAILABLE`로 같다. 다른 inventory/commit의 결과를 재사용하지 않는다.

원래 support predicate·합법 후보·cost factor·DP 탐색 정책은 변경하지 않는다. row 분해, runtime fallback, privacy 완화, 후보 수 cap은 추가하지 않는다.

## 검증 방법

- 최종 기준: origin/main `93706bbaa9`; candidate code는 이를 통합한 `6bc25f3300`. main의 다른 합법성/publication 수정은 두 비교군 모두에 들어간다.
- 같은 build의 전체 source/classes를 동결한 뒤 baseline의 해당 Java 파일만 origin/main 버전으로 복원한다. 두 군 모두 해당 파일을 같은 javac 옵션으로 다시 컴파일한다.
- JFR 없는 순차 `baseline → candidate → candidate → baseline` 실행. 각 실행은 새 coordinator/worker JVM을 사용하는 동일 pinned Docker image, 4 CPU·8 GiB다. 공유 호스트의 다른 작업은 완전히 통제하지 못하므로 모든 반복 수치를 공개한다.
- 데이터: 192×8 X, PRIVATE_AGGREGATE, 3 ROW workers, public local labels, numclasses3, maxi10/maxii5. CP/FED 전체 계수 16개 비교 및 runtime audit/conversion gate를 적용한다.
- 독립 synchronous oracle: 250개의 고정 seed 작은 inventory와 ungrounded/grounded cycle, 긴 cycle, multi-partition backedge, unknown, missing/동등 reference, 다중 source conjunction을 확인한다. baseline 및 candidate 모두 9건 통과했다.
- 최신 통합본 Java 55개 클래스 401건, failure/error/skip 0 및 Maven package 성공. build 중 source 변경 0. full checkstyle/RAT는 기존 targeted 명령처럼 skip했으며 별도 전체 정적 분석으로 주장하지 않는다.

## 결과

**채택.** 네 실행 모두 CP/FED 전체 계수 16개가 일치했고 최대 절대 오차는 `2.22e-16`이다. runtime audit mismatch·missing 및 conversion 위반은 0이다.

| 실행 | 공통 분석(s) | 전체 planner(s) | 컴파일(s) | 실제 학습(s) |
|---|---:|---:|---:|---:|
| baseline 1 | 31.434 | 10.395 | 42.919 | 3.546 |
| candidate 1 | 27.339 | 7.971 | 36.415 | 3.044 |
| candidate 2 | 30.659 | 9.311 | 41.120 | 3.444 |
| baseline 2 | 36.255 | 10.215 | 47.713 | 3.345 |
| **baseline 평균** | **33.845** | **10.305** | **45.316** | **3.446** |
| **candidate 평균** | **28.999** | **8.641** | **38.768** | **3.244** |

평균 공통 분석 **14.32%**, 컴파일 **14.45%** 감소다. 직접 변경한 구간은 common analysis다. planner 시간 변동을 별도 solver 알고리즘 개선으로 해석하지 않는다. 각 군 두 번의 공유 호스트 실행이므로 신뢰구간이나 대형 데이터 일반화 효과를 주장하지 않는다.

모든 실행의 analysis fingerprint가 같고 시간 필드를 뺀 **1,224개 DP 체크포인트 전체**가 같다. 최종 upper `122.26631334184357`, lower `120.54269578813249`, gap `1.4298813731032978%`, assignments `17,336,646`, merges `4,566`이 모두 동일하다. 전역 최적해 증명은 아니다.

후보 수나 합법 공간을 줄여 얻은 효과가 아니다. 네 실행의 입력·fixture·image·runner hash가 일치하고, baseline/candidate의 main source 차이는 해당 파일 하나다. candidate Docker의 main source 1,651개 및 class/resource 4,361개가 최종 Maven build와 전부 hash 일치한다.

단일 증명 invocation 안의 전체 sweep 중복을 제거했지만 commit별 inventory 재구축, 나머지 common analysis, dense factor 비용은 남는다. GLM용 environment 변경은 이 결과에 포함하지 않았다.

[검증 JSON](experiments/logreg-partition-worklist-20261007/validation.json)과 [정확한 실행 argv](experiments/logreg-partition-worklist-20261007/commands.json)에 수치·해시·raw 경로를 보존한다. 같은 명령을 재실행할 때는 새 run ID를 사용한다.

## 재현 및 인계

실행은 `scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e`만 사용한다. `--profile-jfr`는 선택한 FED coordinator에만 적용하는 별도 진단 옵션이다. CP/worker에는 적용하지 않는다.

- raw root: `/grid/3/cofee-lm-sweep-mchoi-20260914/three-workload-ablation-20261007`
- 명령·build snapshot·JFR 요약: `target/three-workload-evidence/`
- GLM 환경 후보: `target/three-workload-evidence/handoff-glm-environment.patch`
- StepLM/GLM harness 확장: `target/three-workload-evidence/handoff-steplm-glm-only.patch`
- StepLM 진단과 미해결 후속: `SESSION_ISSUES_2026-10-07.md`의 LogReg 집중 범위 변경 기록.
