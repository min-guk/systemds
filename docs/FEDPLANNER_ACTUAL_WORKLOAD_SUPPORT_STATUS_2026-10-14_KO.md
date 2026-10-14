# 실제 workload 지원 현황 — 미완료 중간 보고

## 2026-10-14 main 체크포인트 갱신

아래 candidate06 결과는 **과거 측정 기록**이다. 이번 게시 대상에는 이후 정적 shape/단일 반복 PARFOR 수정과 opt-in P2 geometry·encoder·request-local proof·Docker 실행 도구까지 포함한다. 최신 통합 검증과 미완료 항목은 [main 체크포인트 보고서](FEDPLANNER_CURRENT_MAIN_CHECKPOINT_2026-10-14_KO.md)를 따른다. 이전의 “미커밋/수집기 없음/미컴파일/새 push 없음”은 해당 조사 시점의 상태이며 이번 체크포인트의 상태가 아니다. **이후 실제 workload 전체 재측정은 하지 않았으므로, 기존 성공 시간 또는 5/14 결과를 새 코드의 결과로 승격하지 않는다.**

## 판정 기준과 기준 버전 — candidate06 조사 당시

- 공개 main은 `b95989bdddd7`: 실제 LogReg/GLM × workers1/3/5/7의 전체 compile 10초 이내 검증 완료 버전이다.
- 추가 workload 지원 수정은 **미커밋 후보**다. 기존 main의 결과를 후보의 결과로 인용하지 않는다.
- 성공은 `OPTIMAL` + 전체 RuntimeProgram 생성 + compile-only execution0 receipt를 요구한다. rc0나 optimizer 종료만으로 성공 처리하지 않는다.
- 실제 sealed DML/입력/범위/privacy/cost 및 Docker 4CPU/8GiB/heap3GiB 유지. 각 후보·조건 1회. 작은 fixture는 정확성 회귀용이지 성능 근거가 아니다.

## Candidate06: W3 실제 전체14개

Raw: `/home/mchoi/actual-support-candidate06-w3-20261014`. 게시용 보존: [summary](evidence/main-checkpoint-2026-10-14/candidate06/summary.json), [manifest](evidence/main-checkpoint-2026-10-14/candidate06/manifest.json), [실행 명령](evidence/main-checkpoint-2026-10-14/candidate06/candidate06-w3-command.sh). 전체 raw 디렉터리를 게시한 것은 아니다.
재현: `experiments/actual-workload-support-20261014/candidate06-w3-command.sh`.
전체 compile 시간은 JVM 내 공식 fullCompileSeconds이며 Docker wall과 구분한다.

| Workload | 전체 compile | 상태 / 남은 원인 |
|---|---:|---|
| LogReg | 3.951203초 | PASS |
| GLM | 10.920863초 | compile PASS, **under10 미달** |
| PCA | — | CTABLE 이후 downstream MM shape128×-1 |
| ALS | — | native-effect 오류 해소 후 optimizer300초 timeout |
| KMeans | — | CTABLE 이후 SEQ shape50×-1 |
| LM | 1.306245초 | PASS |
| L2SVM | 2.684724초 | PASS |
| StepLM | — | 반복 phase의 unresolved cost context |
| GNMF | 1.275093초 | PASS |
| GMM | — | Binary<= shape-1×128 |
| P1_FULL | — | 내부 KMeans runs10 일반 PARFOR 미지원 |
| P2_PREP | — | 실제 input range와 canonical ingress 불일치; exact domain 및 dummycode shape/cost 지원도 추가 과제 |
| SliceLine ADULT | — | 일반 PARFOR 미지원; 중간 candidate shape도 별도 과제 |
| SliceLine COVTYPE | — | 일반 PARFOR 미지원; 중간 candidate shape도 별도 과제 |

**5/14 compile 완료. 새 후보 전체 workers1/3/5/7 검증은 아직 수행하지 않았다.**
Java test-compile 및 108classes745tests PASS (`candidate06-regression-result.json`). Python 전체/학습 runtime 결과 동등성은 이 후보에서 재검증하지 않았다.

## GLM 회귀의 구분

Candidate05는 optimizer OPTIMAL 이후 beta runtime-loop contract에서 compile 실패했다. runtime contract에 필요 없는 NNZ 차이까지 Shape 전체 equality로 비교하던 조건을 physical dtype/valueType/rows/cols equality로 고쳤다. 정확한 dimension/type 충돌과 UNKNOWN은 계속 거절하며 비용 NNZ는 보존한다. Candidate06은 fullcompile 성공하고 published W3와 objective/node/check가 동일하다.

하지만 더 완전한 dependency closure로 false EXACT shape를 방지하는 수정은 phaseShape requests389,624→1,204,219 및 cache entries225,123→788,142를 늘렸다. W3 rules2.636→4.297s, optimize2.284→3.380s다. 정확성을 되돌리는 shortcut은 사용하지 않는다.

별도 startup JFR 진단1회(`/home/mchoi/actual-support-candidate06-glm-profile-20261014`)는 9.545022초였지만 **공식10.920863초 대신 합격 근거로 선택하지 않는다**. 368 main CPU samples 중 recursive shape/resolver stack139개와 rule-building63개가 확인됐다. 전자는 깊은 stack 때문에 phase미분류이며 inclusive count는 서로 겹친다. same-frame EvaluationContext 재생성 경로의 약171.8MB allocation weight는 표본 추정량으로 실제 retained memory/메서드시간이 아니다. 불변 context 재사용 수정은 집중24tests를 통과했으며 실제 성능 효과는 아직 측정하지 않았다.

## Methodology 결정이 필요한 경계

[원본 SliceLine 조사 및 metadata 계약](FEDPLANNER_SLICELINE_REPRODUCE_METADATA_CONTRACT_2026-10-14_KO.md)을 참조한다. 원본 입력 준비를 따르되 본 알고리즘/pruning을 숨겨 미리 실행하지 않는다. worker exact metadata 수집은 privacy authorization과 cold 비용 포함이 필요하며 **수집기 구현은 아직 없다**.

P1은 n_components1이어도 내부 초기화 KMeans의 runs는10이다. 1회 proof를 확대해 일반 PARFOR를 가장하지 않는다. SliceLine의 데이터 의존적 중간 shape는 입력 통계만으로 보장할 수 없다. 실행 전 하나의 concrete exact 전역 계획을 유지할지, 명시적인 단계별 실행·재계획 모델로 주장 범위를 바꿀지는 구분해서 결정해야 한다.

P2는 별도의 [근본 원인 보고서](FEDPLANNER_P2_PLANNING_BLOCKERS_ROOT_CAUSE_2026-10-14_KO.md)에 정리했다. W3/W7 range 거절은 ADR01/02와 일치하며, 현재 비용 코드도 dummycode를 명시적으로 거절하므로 metadata 수집만으로 완료할 수 없다. 과거 C26 W1/W5의 UNKNOWN_SIZE 관측과 최신 candidate06 W3 결과를 구분한다.

## 현재 후속 수정

- PCA의 이미 증명한 CTABLE shape를 global occurrence/binding fixed point에도 전파: 집중59tests PASS, actual 재검증 전.
- KMeans SEQ의 row50은 parent CTABLE high-probability heuristic이다. 실제 runs1에서 `seq(i,i,50)`는 길이1이다. intrinsic col1만 채워 heuristic50을 EXACT로 승격시키지 않는 좁은 구조적 증명을 편집했으나, 보고서 요청으로 중단하여 **미컴파일·미검증** 상태다. candidate06의745tests PASS는 이 변경의 검증이 아니다.
- 같은 call frame에 대한 immutable EvaluationContext 재사용: 집중24tests PASS, actual 효과 미측정.

전체 지원 완료 또는 새 후보 under10 성공으로 아직 보고하지 않는다. 새로운 push도 하지 않았다.

## 최신 요청: 문제 보고서 작성

[SliceLine planning 근본 원인 보고서](FEDPLANNER_SLICELINE_PLANNING_BLOCKERS_ROOT_CAUSE_2026-10-14_KO.md)에 실제 첫 실패, 후속 정보 부족의 소스 근거, metadata 불충분성 반례와 대안별 보장 차이를 정리했다. 보고서 요청 이후 새 production 수정/추가 workload 평가를 시작하지 않았다. candidate07 실제 평가는 없다. 이미 실행 중이던 ALS60초 JFR 진단은 회수했지만 기존300초 timeout을 대체하는 acceptance 결과로 쓰지 않는다.
