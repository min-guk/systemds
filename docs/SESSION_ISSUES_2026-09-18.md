# Codex/OMX 작업 현황과 인수인계 — 2026-09-18

## 문서 목적과 증거 범위

서버에서 진행했던 일곱 작업이 각각 무엇인지, 어디까지 끝났고 무엇이 남았는지를 정리한다. 이번 작업은 **기록 확인과 문서 작성만** 수행했다. 소스 수정, 테스트 재실행, Docker 실험, 설치 복구, commit/push는 수행하지 않았다.

- 대상 서버/계정: `dams-so002`, `/home/mchoi`
- 개발 저장소: `/home/mchoi/systemds-lm-worker-count-fix`
- 확인한 HEAD: `d8fbd30b54` (`Fix federated placement realization and AggLocal re-entry`)
- 작업 트리: 추가 production/test 수정 및 미추적 테스트·문서가 남아 있다. 커밋만으로 현재 구현 전체를 복원할 수 없으므로 기존 변경을 보존해야 한다.
- 개발 근거: Git 커밋과 아래 연결한 세션 문서. 통과 수치·성능 수치는 **당시 기록**이며 현재 소스를 새로 인증한 결과가 아니다.
- 도구 복구 근거: 9월 18일 Codex 대화 기록과 이번 조회의 명령 실행 성공.
- 기록 우선순위: 9월 15일 문서에 추가된 **9월 16일 최신 소스 재인증**이 9월 14일 handoff보다 최신이다. 오래된 실패·차단 조건을 그대로 현재 상태로 취급하지 않는다.

## 한눈에 보는 목록

| 번호 | 작업 | 목적 | 상태와 완료 경계 |
|---|---|---|---|
| 1 | 물리적 worker 수 계산 수정 | 데이터 파일 수와 실제 worker 수를 구분 | `ffb7be5bd8` 반영. 전체 workload 성능 개선까지 증명한 것은 아님 |
| 2 | Transient placement / AggLocal 재진입 수정 | 블록·함수·분기 경계에서 합법적인 배치와 선택 근거 보존 | `d8fbd30b54` 반영 및 후속 미커밋 변경 존재. 통합 runtime 검증 미완료 |
| 3 | 공유 placement 분석 성능 최적화 | 계획 후보를 버리지 않고 공통 분석 비용 감소 | 기록상 최적화·대조 측정 완료. 전체 plan-space 완전성 감사는 미완료 |
| 4 | Authority·receipt·privacy 회귀 검증 | 선택 계획의 근거와 개인정보 제약이 일치하는지 확인 | 기록상 17개 클래스/120개 테스트 통과. 이번 세션 재실행 아님 |
| 5 | ML10 + P1/P2 + SliceLine Docker 검증 | 실제 분산 실행의 결과·환경·산출물 검증 | 미완료. 최신 JAR와 새 immutable stage 인증 필요 |
| 6 | Codex 실행기 누락 복구 | 셸·파일 도구를 다시 실행할 수 있게 복구 | 오전 재개 실패 기록 존재. 현재 실행 가능하나 정확한 복구 변경 경로는 미확인 |
| 7 | OMX 설치·초기화 오류 수정 | OMX 실행과 tmux 초기화 복구 | 직전 세션 완료 보고, 당시 doctor 21 passed |

1~5는 하나의 SystemDS 개발 흐름에 속하고, 6~7은 이를 수행하는 도구 환경의 문제다. 일곱 개의 독립적인 실행 세션을 뜻하지 않는다.

## 용어

- **Placement**: 연산과 결과를 coordinator 또는 federated worker 어디에 둘지에 대한 배치.
- **CP / FED**: coordinator에서 계산 / federated worker에서 계산.
- **LOUT / FOUT**: coordinator-local 결과 / federated 결과.
- **TWrite / TRead**: 중간 변수 값을 저장하고 이후 읽는 연산. 블록·함수·분기 경계에서 값과 배치가 이어져야 한다.
- **Realization / support clause**: 후보의 구체적인 물리 배치와 그것이 실행 가능하다는 의존 조건. 같은 FType이어도 worker pool이나 map이 다르면 같은 후보라고 볼 수 없다.
- **Authority / receipt**: 배치를 허용하는 근거 / 실제 선택한 배치와 근거를 담은 기록. 여기서 authority는 운영체제 접근 권한이 아니다.
- **Plan-space completeness**: 실행 가능한 모든 합법적 계획을 표현하고 보존한다는 성질. 일부 테스트 통과나 동일 최적 비용만으로 증명되지 않는다.

## 1. SystemDS 물리적 worker 수 계산 수정

**상태: 커밋 반영 및 관련 회귀 기록 존재.**

### 문제 정의·원인

동일 worker의 서로 다른 데이터 경로를 별도 worker로 세었다. 예를 들어 세 worker에 X와 y 파일이 각각 있으면 실제 세 worker가 여섯 개로 계산될 수 있었다. `workerId` 전체 문자열에 파일 경로가 포함되는데 이를 그대로 중복 제거한 것이 원인이다.

### 해결 방법·수정 파일

기존 `FederationUtils.canonicalFederatedWorkerAddress(...)`로 주소를 정규화한 뒤 고유 물리 endpoint를 센다. 같은 `host:port`의 다른 파일은 하나로 세고, 포트가 다르면 별도 worker로 유지한다.

- 커밋: `ffb7be5bd85367156ed9ea86dacbaff4be0f035d`
- production: `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java`
- test: `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalWorkerCountTest.java`
- 후속 D4 감사에서는 선택된 durable/native pool의 worker cardinality와 partition geometry/request multiplicity를 구분했다.

**의사결정 근거:** 비용 모델에 들어가는 식별 정보를 바로잡는 수정이다. 후보를 임의로 제거하거나 비용 계수를 조정하는 작업이 아니다.

### 검증·남은 일·회귀 위험

- 기록상 `ExactPhysicalWorkerCountTest` 2/2, `ExactNativeLocalAnchorFanoutCostTest` 6/6 통과.
- 이 수정만으로 모든 workload에서 선택 계획이나 runtime이 개선된다고 결론 내릴 수 없다.
- 잘못된 endpoint 정규화나 worker 수와 partition 수의 혼동이 재발할 위험이 있다. 동일 endpoint/다른 path 및 동일 host/다른 port 회귀로 감지한다.

근거: [9월 14일 이슈](SESSION_ISSUES_2026-09-14.md), [9월 15일 문서의 D4 감사](SESSION_ISSUES_2026-09-15.md).

## 2. Transient placement 및 AggLocal 재진입 수정

**상태: 주요 구현 커밋 반영, 후속 미커밋 수정 존재, runtime qualification 미완료.**

### 문제 정의·원인

서로 관련되지만 구분해야 하는 두 문제가 있었다.

1. **합법적인 분산 후보 소실:** LM의 `pred = X %*% p` 결과가 이후 TRead로 넘어갈 때 실행 가능한 FED 배치가 유지되지 않아 후속 집계가 CP-only로 제한되는 현상이다. 단일 배치만 전제로 한 replay와 구체적 realization/호환성 근거의 표현이 주요 조사 대상이었다.
2. **AggLocal의 불필요한 FED 재진입:** 분기의 양쪽에서 이미 local인 `z`를 만든 경우에도 branch-join 읽기가 local 연속 구간으로 인정되지 않아 재업로드·원격 연산이 생겼다. 물리 node kind를 문자 그대로 TRead/TWrite와 비교해 실제 compiled transient의 `BRANCH_JOIN` 분류를 놓친 경로가 있었다.

### 해결 방법·수정 범위

- shared analysis에 map별 realization과 선택을 뒷받침하는 AND/OR support를 보존한다.
- writer→reader, 함수 인자·결과, reaching definition 사이의 호환성을 선택과 receipt 검증까지 전달한다.
- AggLocal은 공유 compiled-transient 판정을 사용해 acyclic branch join을 인정하되 cyclic `LOOP_PHI`까지 무조건 허용하지 않는다.
- privacy, 문맥, 실제 worker/map 근거를 유지한다. TRead/TWrite는 `CP/LOUT` 또는 `FED/FOUT`만 허용하며 runtime fallback을 추가하지 않는다.

주요 커밋은 `d8fbd30b54`이다. 주요 파일은 placement 아래의 `PlacementAnalysis.java`, `PlacementIdentity.java`, `NeutralPlacementGraphBuilder.java`, `NativePlacementContinuity.java`, `CandidateSelections.java`, `LogicalBoundaryRealizations.java`, `RelocationSelections.java` 및 fedExact의 model/selection/cost 관련 파일이다. 이후 수정과 새 회귀 테스트 일부는 아직 커밋되지 않았다.

**의사결정 근거:** 선택 정책으로 증상을 숨기지 않고 공통 후보 표현·호환성 증명 및 AggLocal의 compiled-transient 인식 경계를 수정한다.

### 검증·남은 일·회귀 위험

- 아래 4번의 최신 통합 회귀가 관련 근거다. 전체 physical plan 보존과 실제 Docker workload 성공까지 완료한 것은 아니다.
- 후보 조합 증가, closure 비수렴, 오래된 authority 유지, 서로 다른 map을 잘못 합치는 위험이 있다.
- branch/loop/function, privacy, selected receipt, exact map 및 순서 독립성 회귀를 함께 확인해야 한다.
- 오래된 handoff의 함수 publication 실패를 현재도 실패한다고 단정하지 않는다. 이후 최신 suite에 해당 계약 테스트가 포함되어 통과했다고 기록되어 있다.

근거: [구현 계획](../.omx/plans/2026-09-14-transient-placement-alternatives.md), [초기 handoff](HANDOFF_TRANSIENT_PLACEMENT_2026-09-14.md), [최신 이슈](SESSION_ISSUES_2026-09-15.md).

## 3. 공유 placement 분석 성능 최적화

**상태: 기록상 표현 최적화·회귀·before/after 대조 완료. 전역 완전성 감사는 미완료.**

### 문제 정의·원인

모든 planner가 사용하는 공통 분석에서 signature 생성·비교·정규화가 반복되어 계획 수립 전처리가 느렸다. compiler-only multiLogReg fixture의 JFR에서는 signature 관련 경로가 주요 sampled execution 비용으로 관측됐다. 이는 실제 workload 실행 시간이 아니라 compiler 분석 비용이다.

### 해결 방법·수정 범위

- immutable support clause/realization의 canonical serialization을 기존 compiler-thread-local weak-key cache에서 재사용한다.
- realization이 하나인 emission 병합은 기존 객체를 보존해 중복 병합을 피한다.
- incoming realization support 의존성을 양방향 participant 집합에 포함시켜 부적절한 consumer별 분리 축약을 막는다.
- 주요 관련 파일: `PlacementIdentity.java`, `PlacementAnalysis.java`, `CandidateSelections.java` 및 canonicalization/incoming-support 회귀 테스트.

**의사결정 근거:** 후보 수 제한, top-k, 비용 기반 공통 후보 삭제가 아니라 표현·반복 계산 비용을 줄인다. 특정 planner 정책에 의존하는 축약은 공통 universe에 강제하지 않는다.

### 검증·남은 일·회귀 위험

- 저장된 동일 fixture 대조에서 builder 시간이 `59.623106302초 → 11.929883048초`로 줄었다. 모든 workload에 대한 일반적 개선율이나 runtime 가속으로 해석하지 않는다.
- 전체 categorical assignment와 objective raw bits가 같았으며, graph/fingerprint/rule facts/receipts/transient/function relation의 6개 canonical snapshot SHA도 보존됐다고 기록되어 있다.
- 최종 크기는 531 rules, 565 emissions, 597 realizations, 771 clauses로 동일했다.
- 전체 valid physical plan-space 독립 증명은 남아 있다. 다음 성능 분석은 무조건 cache를 늘리기보다 expansion별 attempted/retained/deduplicated 크기와 closure 변화량을 계측하는 방향이다.
- 캐시가 ownership/equality를 대신하거나 잘못된 분리 축약이 합법적 조합을 지울 위험이 있다. byte-identical signature, 객체 authority 보존, 독립 full-set oracle 및 snapshot 대조로 감지한다.

근거: [9월 15일 성능·완전성 감사](SESSION_ISSUES_2026-09-15.md). 당시 증거 디렉터리: `/grid/3/cofee-lm-sweep-mchoi-20260914/incoming-support-completeness-20260915-230734`.

## 4. Planner authority·receipt·privacy 회귀 검증

**상태: 9월 16일 기록상 17개 클래스 / 120 tests / 0 failures / 0 errors / 0 skipped. 이번 문서 작성에서는 재실행하지 않음.**

### 무엇을 검증하는가

후보가 존재한다는 사실뿐 아니라 실제 선택된 upstream realization, input binding, relocation action, worker pool, 함수·transient 관계와 receipt가 서로 일치하는지를 확인한다. privacy를 완화하거나 미지원 배치를 만들어 테스트를 통과시키는 것은 허용하지 않는다.

### 검증 범위

최신 기록의 suite는 다음 17개 클래스다.

```text
PrivacyMovementCertificationTest
CandidateIncomingSupportCompletenessTest
CandidateInputBottomDomainTest
ExecutableProjectionAuthorityTest
CandidateRealizationCanonicalizationTest
CandidateReceiptAssignmentCompletenessTest
LogicalBoundaryRealizationsTest
TransientPlacementAlternativesTest
NativePlacementContinuityTest
HeuristicNativeContinuationContractTest
SharedPrivacyPlacementAnalysisContractTest
PublicationSupportClosureTest
SharedPlannerFunctionPlanPropagationRedTest
ExactPhysicalModelCertificateTest
ExactNativeLocalAnchorFanoutCostTest
ExactPhysicalWorkerCountTest
PlacementRealizationAuthorityTest
```

당시 Maven은 `EXIT 0`이고 surefire XML 시간은 2026-09-16 01:55:25~01:56:44 +0200으로 기록되어 있다. `git diff --check`도 당시 clean이었다.

### 남은 일·회귀 위험

- 이 결과는 위 계약과 bounded fixture의 회귀 증거이지 모든 프로그램의 plan-space 완전성 또는 Docker runtime 성공 증거가 아니다.
- 오래된 target/classes나 surefire XML을 현재 소스 결과로 오인할 위험이 있다. 재개 시 현재 소스와 빌드 대응을 확인하고 Maven을 순차 실행해 새 로그·종료 코드·XML을 보존해야 한다.
- **의사결정 근거:** 동일 optimum보다 강한 selected-assignment/authority 검증을 사용하되 검증한 범위를 넘어 완료를 주장하지 않는다.

근거: [9월 15일 문서의 「2026-09-16 최신 소스 재인증 및 runtime 진입 조건」](SESSION_ISSUES_2026-09-15.md).

## 5. ML10 + P1/P2 + SliceLine Docker 실행 검증

**상태: 전체 runtime campaign 미완료.**

### 목적과 대상

단위·compiler 테스트를 넘어서 수정된 planner가 실제 분산 실행에서도 올바른 수치 결과를 내고, privacy·네트워크 조건·환경 동일성·산출물 추적 계약을 지키는지 검증하는 작업이다.

- ML10: `pca, als, kmeans, lm, logreg, l2svm, steplm, glm, gnmf, gmm`
- P1/P2: `P1_FULL`, `P2_PREP`
- SliceLine: `ADULT, COVTYPE, KDD98, USCENSUS`
- 관련 driver: `/home/mchoi/cofee-evaluation/campaign/run_ml10_campaign.py`, `/home/mchoi/cofee-evaluation/driver/run_multihost_campaign_network_quality_v2.py`
- LM 참고 실행: `/home/mchoi/lm-cg-followup-20260914/REPORT.md`, `stage-fixed`

### 준비 상황과 남은 절차

1. 현재 수정 소스를 fresh correctness suite로 확인하고 새 JAR를 빌드한다.
2. 기존 frozen stage의 데이터/reference/wrapper 구조를 보존하면서 새 immutable stage를 만든다.
3. 새 JAR와 `STAGE_CONTENT.sha256`, build identity를 연결하고 모든 execution host의 stage content 동일성을 인증한다.
4. 기존 Docker·network guard·runtime lane lock 절차를 유지해 실행한다. 저장소 지침의 planner 순서는 DP → FedAll → Heuristic → Exact이다.
5. CP/reference 수치 비교, tolerant-semantic output hash, 실행 전후 네트워크 검사와 cell별 receipt를 보존한다. 물리 호스트 직접 실행 결과로 Docker 비교를 대체하지 않는다.

**중요한 최신 정정:** 이전 handoff에는 so007의 Docker tag digest 불일치가 blocker로 적혀 있지만, 최신 기록에서는 기존 ML10 stage에 대해 so007 및 so002~so006의 동일 image **content identity 인증이 통과**했다. 따라서 오래된 tag 차이 자체를 현재 blocker라고 쓰면 안 된다. 다만 이 인증은 옛 stage의 결과이며 **새 planner JAR/stage의 인증을 대신하지 않는다.**

**잠재 위험과 감지:** 오래된 JAR, 서로 다른 image/stage, 잘못된 네트워크 조건으로 얻은 결과를 섞으면 비교가 무효다. content hash·lifecycle certification·reference 비교로 확인하며 guard를 완화하지 않는다.

**의사결정 근거:** compiler correctness와 실제 runtime qualification은 별도 승인 조건이다. 본 문서 작성에서는 빌드·배포·캠페인을 시작하지 않았다.

## 6. Codex 실행기 누락 복구

**상태: 과거 실행 차단 확인, 현재 실행 가능. 정확한 복구 변경 내역은 미확인.**

### 문제 정의·원인

9월 18일 오전 사용자가 저장소 작업 재개를 요청했지만 다음 오류로 셸 실행 이전에 차단됐다.

```text
failed to spawn code-mode host /home/mchoi/.local/bin/codex-code-mode-host:
No such file or directory (os error 2)
```

당시 주 세션과 하위 에이전트 모두 같은 도구 bootstrap 오류를 보고했으며 저장소 명령이나 파일 수정은 수행하지 못했다고 기록되어 있다. 실행 파일 자체의 부재인지 링크/loader 문제인지까지는 그 시점의 증거로 확정하지 못했다.

### 현재 확인·남은 경계

- 현재 조회에서는 설치된 Codex 패키지 아래의 code-mode host 프로세스가 보였고 실제 셸·파일 조회가 성공했다.
- 따라서 현재 도구가 작동한다는 점은 확인했다. 어떤 명령이 최초 원인을 고쳤는지, 7번 OMX 복구만으로 해결됐는지는 확정하지 않는다.
- 특정 변경 파일 목록은 확인되지 않았다. 이번 문서 작업에서 실행기 설정을 변경하지 않았다.
- 재발 시 configured host path와 설치된 binary/loader의 대응을 먼저 확인한다. 대상 확인 없이 임의 symlink를 만들면 잘못된 버전을 실행할 위험이 있다.
- **의사결정 근거:** 저장소 코드 오류가 아니라 코드 실행 도구의 bootstrap 실패로 분리한다.

근거: `/home/mchoi/codex-reset-backup-20260918T184333Z/state/.codex/sessions/2026/09/18/`의 오전 재개·복구 시도 기록.

## 7. OMX 설치·초기화 오류 수정

**상태: 직전 세션에서 복구 완료 보고. 당시 doctor 21 passed / 0 warnings / 0 failed.**

### 문제 정의

`omx` 실행 시 설치 소유 패키지 관리자를 판별하지 못하고, 이어 instance 디렉터리 생성에 실패했다.

```text
[omx] Unable to determine whether this global install is owned by npm or Bun.
Error: ENOENT: no such file or directory,
mkdir '/home/mchoi/.omx/instances/terminal-primary/.omx'
```

### 기록된 해결 방법·대상 경로

- 중복된 `~/.local` OMX 설치를 정리하고 NVM npm 전역 설치 `oh-my-codex v0.21.5`로 통일했다.
- 기존 셸 호환용 `~/.local/bin/omx` 링크를 다시 연결했다.
- 누락된 `~/.omx/instances/terminal-primary`를 생성했다.
- `~/.codex/hooks.json`과 OMX 설정을 갱신했다.
- 실제 초기화에서 소유권 경고와 `mkdir ENOENT`가 재발하지 않는 것을 확인했다고 보고했다.

### 검증·남은 일·회귀 위험

- 직전 완료 보고에 `omx doctor --verbose`: 21 passed, 0 warnings, 0 failed가 남아 있다. 이번 문서 작성에서는 doctor를 재실행하지 않았다.
- 현재 목록 조회 당시 tmux에 현재 OMX 대화 세션과 HUD가 존재했다.
- 이는 도구 실행 복구이며 SystemDS 미완료 작업을 완료한 것은 아니다.
- 향후 중복 전역 설치나 오래된 PATH/link가 재발하면 다른 버전이 실행될 수 있다. executable 경로, package manager 소유 설치 및 doctor 결과를 함께 확인한다.
- **의사결정 근거:** 개발 소스가 아니라 설치 경로·실행 링크·instance 초기화 환경을 복구한다.

근거: `/home/mchoi/.codex/sessions/2026/09/18/rollout-2026-09-18T20-57-18-01a0b5e1-535e-7321-b153-f24ce8489a95.jsonl`의 완료 보고.

## 재개 시 우선순위

1. 미커밋 변경과 미추적 테스트를 보존하고 현재 소스·HEAD·빌드 대응을 다시 확인한다.
2. 최신 17-class 회귀를 순차 재검증한다. 과거 실패 로그보다 새 실행 결과를 우선한다.
3. 전체 plan-space 완전성 감사와 추가 cleanup 필요 여부를 현재 소스 기준으로 구분한다. bounded oracle 통과를 전역 완료로 확대하지 않는다.
4. 최신 JAR와 새 immutable stage를 인증한 뒤 Docker runtime matrix를 수행한다.
5. 실행·검증 증거와 잔여 이슈를 갱신한 다음에만 최종 완료 여부를 판단한다.

조회 당시 별도 Maven/Java 테스트·캠페인 프로세스는 발견되지 않았다. 여러 snapshot에 남은 7월 `in_progress` OMX 파일은 오래된 복제 기록이므로 현재 작업이 실행 중이라는 근거로 사용하지 않았다. 모든 상태는 위 조회 시점 기준이다.

## 8. PUBLIC privacy 테스트 제외 정책 정렬

**상태: 해결. PUBLIC-only 12개 메서드를 제외했고 protected/privacy-recompile 회귀는 실행 상태로 유지했다.**

### 환경·증상·원인

- 대상은 현재 Transient/FED placement 회귀의 `PrivacyMovementCertificationTest`, `TransientPlacementAlternativesTest`, `NativePlacementContinuityTest`, `NativeLineagePlanSpaceCompletenessTest`이다.
- 과거 17-class 결과는 `0 skipped`였지만 현재 `AGENTS.md`는 privacy constraint가 PUBLIC인 테스트를 ignore하도록 요구한다. 기존 상태는 이 실행 정책과 맞지 않았다.
- 클래스 전체를 제외하면 같은 클래스의 `PRIVATE`, `PRIVATE_AGGREGATE`, privacy narrowing 및 recompile 금지 계약도 사라지므로 메서드 단위 분류가 필요했다.

### 해결 요약·수정 파일

`org.junit.Ignore`를 최소 import하고 PUBLIC-only 메서드에만 `@Ignore("PUBLIC-only ... repository test policy")`를 부여했다. 혼합 테스트에서 PUBLIC 분석을 protected 비교 기준으로 사용하는 경우에는 최종 protected 계약이 계속 실행되도록 ignore하지 않았다. PUBLIC-only NativeLineage 오라클과 recompile baseline에는 각각 활성 `PRIVATE_AGGREGATE` 대체 회귀를 유지했다.

- `src/test/java/org/apache/sysds/hops/fedplanner/placement/PrivacyMovementCertificationTest.java`: 4개 제외.
- `src/test/java/org/apache/sysds/hops/fedplanner/placement/TransientPlacementAlternativesTest.java`: 5개 제외.
- `src/test/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuityTest.java`: 1개 제외.
- `src/test/java/org/apache/sysds/hops/fedplanner/placement/NativeLineagePlanSpaceCompletenessTest.java`: 2개 제외; protected native-lineage 및 protected recompile 검사는 활성 유지.

정확한 12개 메서드 목록과 활성 protected coverage 분류는 durable policy manifest
`build/plan-space-audit-20260918/privacy-policy/README.md`에 보존했다.

**의사결정 근거:** 테스트 실행 정책만 정렬했다. planner/oracle/runtime capability, privacy 판정, 후보 공간 및 TRead/TWrite/recompile 배치 규칙은 변경하지 않았다.

### 검증

다른 Maven/Surefire 실행이 없는 상태에서 다음 명령을 순차 실행했다.

```bash
mvn -q -DskipRat -Dmaven.compiler.useIncrementalCompilation=false \
  -Dtest-forkCount=1 \
  -Dtest=PrivacyMovementCertificationTest,TransientPlacementAlternativesTest,\
NativePlacementContinuityTest,NativeLineagePlanSpaceCompletenessTest test
```

- exit 0, 총 50 discovered / 38 executed-pass / 0 failures / 0 errors / 12 skipped.
- `PrivacyMovementCertificationTest`: 11 tests, 4 skipped.
- `TransientPlacementAlternativesTest`: 7 tests, 5 skipped.
- `NativePlacementContinuityTest`: 28 tests, 1 skipped.
- `NativeLineagePlanSpaceCompletenessTest`: 4 tests, 2 skipped.
- 최종 실행 전후 네 테스트 소스 SHA-256이 동일했고 `git diff --check`도 통과했다.
- G002의 분류 근거, 실행 명령 및 클래스별 결과 요약:
  `build/plan-space-audit-20260918/privacy-policy/README.md`.
- `clean test` 전에 보존한 G002 raw evidence:
  `build/plan-space-audit-20260918/pre-fresh-reports/omx-evidence/G002-public-ignore-20260918/`.
  이 디렉터리에는 `REPORT.md`, `ignored-methods.txt`, `final-stable-maven.log`,
  `final-stable-maven.exit`, `final-stable-before.sha256`, `final-stable-after.sha256` 및
  `surefire-reports/`의 네 클래스 XML/TXT가 실제로 남아 있다.

활성 protected 범위에는 protected FOUT/local projection, relocation privacy, loop bootstrap, 서로 다른 worker pool 거부, protected transient continuation, privacy narrowing rebuild 안정성, protected broadcast relocation, protected native lineage 및 protected recompile의 CP/FOUT 제외가 포함된다.

### 잔여 이슈·잠재 회귀 위험

- PUBLIC 회귀 12개는 정책상 실행되지 않으므로 해당 public-only 동작은 이 suite의 통과 증거가 아니다. 정책이 바뀌면 별도 프로필이나 명시 실행으로 다시 인증해야 한다.
- 새 PUBLIC fixture가 추가되면서 `@Ignore`가 누락되거나, 반대로 혼합/protected 메서드 전체를 제외할 위험이 있다. Surefire의 정확한 skipped 수와 활성 protected 메서드 목록을 함께 확인해 감지한다.
- 이번 검증은 네 정책 관련 클래스에 한정되며 전체 placement correctness 재인증은 후속 단계다.

## 9. Transient/FED placement correctness 최신 소스 재인증

**상태: 해결. 현재 소스의 17-class 회귀와 추가 NativeLineage bounded 회귀가 순차 통과했다.**

### 환경·문제 정의

- HEAD: `d8fbd30b5476a1ceef460c9f3886381a369ac619`.
- 대상은 4번에 기록한 17개 placement/authority/privacy/exact 회귀 클래스와 현재 미추적
  `NativeLineagePlanSpaceCompletenessTest`다.
- 9월 16일의 120 tests / 0 skipped 기록은 현재 소스와 G002의 PUBLIC ignore 정책을 반영한
  결과가 아니므로 fresh XML로 다시 인증해야 했다.
- Maven/Surefire를 겹쳐 실행하지 않았고 각 canonical invocation 전에 활성 Java launcher가
  없음을 확인했다.

### 재현 절차와 결과

문서의 17개 클래스만 다음 옵션으로 한 Maven invocation에서 실행했다.

```bash
mvn -DskipRat -Dmaven.compiler.useIncrementalCompilation=false \
  -Dtest-forkCount=1 \
  -Dtest=PrivacyMovementCertificationTest,CandidateIncomingSupportCompletenessTest,\
CandidateInputBottomDomainTest,ExecutableProjectionAuthorityTest,\
CandidateRealizationCanonicalizationTest,CandidateReceiptAssignmentCompletenessTest,\
LogicalBoundaryRealizationsTest,TransientPlacementAlternativesTest,\
NativePlacementContinuityTest,HeuristicNativeContinuationContractTest,\
SharedPrivacyPlacementAnalysisContractTest,PublicationSupportClosureTest,\
SharedPlannerFunctionPlanPropagationRedTest,ExactPhysicalModelCertificateTest,\
ExactNativeLocalAnchorFanoutCostTest,ExactPhysicalWorkerCountTest,\
PlacementRealizationAuthorityTest test
```

- 시작/종료: 2026-09-18 21:48:18~21:51:29 +0200.
- Maven exit 0.
- **17 classes / 124 discovered / 114 executed-pass / 0 failures / 0 errors / 10 skipped.**
- 10 skips는 G002에서 분류한 PUBLIC-only 메서드 중 17개 클래스에 속한 항목과 정확히
  일치한다.

현재 class인 `NativeLineagePlanSpaceCompletenessTest`는 17-class 수치와 섞지 않고 별도
invocation으로 실행했다.

```bash
mvn -DskipRat -Dmaven.compiler.useIncrementalCompilation=false \
  -Dtest-forkCount=1 \
  -Dtest=NativeLineagePlanSpaceCompletenessTest test
```

- 시작/종료: 2026-09-18 21:51:44~21:52:06 +0200.
- Maven exit 0.
- **1 class / 4 tests / 0 failures / 0 errors / 2 skipped.**
- 두 skip은 G002의 PUBLIC-only NativeLineage/recompile baseline이고, 활성
  `PRIVATE_AGGREGATE` native-lineage 및 protected recompile CP/FOUT 제외 검사는 통과했다.

이에 앞서 동일 소스를 `clean test`로 fresh compile한 18개 결합 실행도 exit 0,
128 discovered / 116 executed-pass / 0 failures / 0 errors / 12 skipped로 통과했다. 결합 실행은 fresh compile 보조
증거이며 위의 분리 실행이 canonical G003 결과다.

### 소스 identity·수정 파일·증거

- fresh clean 실행 전과 두 canonical 실행 후의 전체 source/test 7,469개 파일 SHA-256
  manifest가 byte-identical하다. manifest SHA-256은 양쪽 모두
  `1319d1e75e939b73794c8c37605253c1ddb6effcb364ba53c7898ffa77034a8a`다.
- `git diff --check` exit 0.
- G003에서 production/test 코드는 수정하지 않았다. 이 문서와 검증 evidence만 추가했다.
- 명령, 시작/종료 시각, exit code, 로그, source hash, fresh Surefire XML과 클래스별 집계:
  `target/omx-evidence/G003-placement-recertification-20260918/`.

**의사결정 근거:** 후보를 닫거나 privacy/TRead-TWrite/recompile 규칙을 완화하지 않고 현재
공통 placement 표현과 선택 receipt의 기존 계약을 그대로 실행해 인증했다.

### 잔여 이슈·잠재 회귀 위험

- `NativeLineagePlanSpaceCompletenessTest`는 현재 production AVAILABLE facts와 선택된 state에
  의존하는 bounded 회귀다. 통과 결과를 전체 valid physical plan-space의 독립 증명으로
  확대하지 않는다. 전역 transformation inventory와 독립 full-set oracle은 G004/G005 범위다.
- PUBLIC-only 12개 메서드는 저장소 정책상 skip이므로 해당 PUBLIC 동작은 이번 통과 증거가
  아니다. skipped 수가 바뀌면 G002 manifest와 대조한다.
- 이 결과는 compiler/placement correctness 회귀다. 새 JAR/stage의 Docker runtime 성공이나
  전체 ML10/P1/P2/SliceLine qualification을 증명하지 않는다.

## 10. G005 bounded dual independent oracle 및 production relation decoder

**상태: bounded fixture 검증 완료. 전역 plan-space 등가 증명은 계속 진행 중이다.**

### 문제 정의와 결정 근거

G004 transformation inventory는 `Decode(S(P)) = LegalPhysicalPlans(P)`를 전역 명제로
확정하지 않았고, G005에 두 종류의 실행 가능한 비교를 요구했다. 첫째는 production 후보
생성 결과를 사용하지 않는 명시적 유한 환경·관계 오라클이고, 둘째는 실제 production
`PlacementAnalysis` receipt 관계를 모든 선택 조합에 대해 복호화하는 bounded 비교다.

테스트 통과 수나 선택된 optimum만 비교하면 후보 누락과 불법 후보 추가를 동시에 찾을 수
없으므로, normalized full-set의 `missing`과 `extra`를 모두 계산하고 구조적 mutation이 해당
차이를 실제로 발생시키는지 검증했다. 후보를 닫는 production guard, privacy/TR-TW/recompile
완화, runtime fallback은 추가하지 않았다.

### 수정·검증 대상

- `GlobalReceiptPlanSpaceCompletenessTest.java`: 독립 선언 domain에서 placement assignment,
  complete receipt 선택 및 action subset을 순회한다. exact range geometry, repeated producer,
  higher-arity AND, grounded SCC, function/transient 결합 및 late publication을 포함한다. 합법 OR
  삭제, 서로 다른 map 병합, higher-arity dependency 삭제, seedless SCC 허용, protected/recompile
  CP/FOUT 허용의 다섯 구조 mutation과 일반 missing/extra sentinel을 포함한다.
- `IndependentPlanSpaceGenerationCompletenessTest.java`: protected transient의 compile/recompile
  상태 집합과 protected exact ROW worker/path/range geometry, origin-resident 입력의 relocation
  미발행을 literal contract와 비교한다. worker identity 기대값은 `/A1`, `/A2`, `/B1`, `/B2`
  경로까지 보존한다.
- `ProductionDecodedPlanSpaceCompletenessTest.java`: 실제 production AVAILABLE receipt를 읽어
  `recompileOnce`가 설정된 compiled-function fixture의 X/Y/U/V/D 관계를 복호화한다. 이는
  recompile-context occurrence의 receipt coverage 증거가 아니다. 해당 occurrence 생성 범위는
  `IndependentPlanSpaceGenerationCompletenessTest`가 별도 bounded 증거를 제공한다. raw physical
  domain cardinality `1344`를 assertion으로 고정한 뒤 declared binding projection을 8개 literal
  plan과 비교한다. `missing = extra = empty`는 그 projection 내부에만 적용되며 제외된 receipt와
  domain 밖 receipt는 미분류 상태다.

### 최종 재현 절차와 결과

다른 Maven/Surefire 및 순차 broad wrapper가 모두 종료되고 세 소스 해시가 안정된 뒤 다음
명령을 한 번 실행했다.

```bash
mvn -Dtest-forkCount=1 \
  -Dtest=org.apache.sysds.hops.fedplanner.placement.GlobalReceiptPlanSpaceCompletenessTest,\
org.apache.sysds.hops.fedplanner.placement.IndependentPlanSpaceGenerationCompletenessTest,\
org.apache.sysds.hops.fedplanner.placement.ProductionDecodedPlanSpaceCompletenessTest test
```

- 시작 marker: `2026-09-18T23:13:04,349429638+02:00`.
- Maven 종료: `2026-09-18T23:14:32+02:00`, exit `0`, `BUILD SUCCESS`.
- 전체: **14 discovered / 13 executed-pass / 0 failures / 0 errors / 1 skipped**.
- GlobalReceipt: 10 tests / 0 failures / 0 errors / 0 skipped.
- IndependentGeneration: 3 tests / 0 failures / 0 errors / 1 skipped.
- ProductionDecoded: 1 test / 0 failures / 0 errors / 0 skipped.
- 유일한 skip은 PUBLIC source를 요구하는
  `mixedPrivacyExactRowPoolsGenerateEveryAndOnlyContractRelocation`이며, fresh XML의 사유는
  `Requires one PUBLIC source; excluded by repository privacy-test policy`다.
- ProductionDecoded의 성공은 최종 안정 소스에서 raw physical-domain cardinality `1344`를
  확인하고, declared binding projection의 8-plan 집합에 대해 `missing = extra = empty`를 확인한
  결과다. 1344개 전체가 8개 합법 계획으로 분류됐다는 주장은 아니며 projection 밖 receipt는
  미분류다.

실행 전후 세 소스 SHA-256은 byte-identical하다.

```text
8a45f5c39e432a633208a989be0df1bb5721eafe985ec983f0e5fdea75c83702  GlobalReceiptPlanSpaceCompletenessTest.java
9127c4f45a46fc541490e532d00a06d24d8b78c0334ee7f729cf4aaadfc5a61a  IndependentPlanSpaceGenerationCompletenessTest.java
d8924c87b157f1466818c7e0e2a347bb3086b2c09f765454915a4c98f3d0e148  ProductionDecodedPlanSpaceCompletenessTest.java
```

전체 명령, 로그, exit code, 시작·종료 시각, 전후 source manifest, fresh Surefire XML, XML hash와
검증 manifest는 다음 디렉터리에 보존했다.

`build/plan-space-audit-20260918/G005-final-dual-oracle-20260918T231255+0200/`

### 잔여 이슈와 잠재 회귀 위험

- 이 결과는 명시적으로 제한된 protected fixture의 bounded 증거다. 모든 지원 프로그램과
  opcode, runtime capability row에 대한 전역 `Decode(S(P)) = LegalPhysicalPlans(P)` 증명은
  아니다.
- PUBLIC mixed-pool positive relocation은 저장소 정책상 skip이므로 활성 G005 증거에 포함되지
  않는다. 정책 변경 시 별도 활성 프로필에서 다시 인증해야 한다.
- raw physical-domain cardinality 1344와 declared projection의 8-plan equality는 source/binding
  correlation을 강하게 검사하지만, projection 밖 receipt를 분류하거나 전체 transformation의
  compositional preservation proof를 대신하지 않는다.
- Docker runtime, 새 JAR/stage identity 및 ML10/P1/P2/SliceLine 분산 실행 검증은 별도 미완료
  작업이다.

**의사결정 근거:** 독립 literal 계약과 production relation decoder의 full-set 비교를 함께
사용하고, bounded 결과를 전역 theorem이나 runtime qualification으로 확대하지 않는다.

## 11. 모든 possible plan 보존 감사 G001–G008 결과

**상태: 추가 감사 필요. 재현된 aligned-binary 결함은 수정됐지만 전역 보존 정리는 아직
증명되지 않았다.**

### 실행 결과

- G001은 작업 시작 HEAD, tracked diff, untracked source/test/docs, toolchain, 동시 process 및
  hash를 `build/plan-space-audit-20260918/baseline/`에 보존했다. reset/stash는 사용하지 않았다.
- G002는 PUBLIC-only privacy 테스트 12개를 분류했다. protected/mixed 메서드는 활성 상태로
  유지했으며 근거는 `privacy-policy/README.md`다.
- G003 fresh 결합 실행은 **128 discovered / 116 executed-pass / 0 failures / 0 errors /
  12 PUBLIC-only skips**였다. canonical 분리 실행은 17 classes의 124 discovered/114 executed-pass/
  10 skips와 NativeLineage 4 discovered/2 executed-pass/2 skips다.
- G004는 후보 생성·closure·action·representation·policy의 **56개 transformation**, 아직 열린
  **21개 proof obligation**, universal parity가 열려 있는 **48개 rule family**를 기계 판독
  inventory로 고정했다.
- G005 bounded oracle은 protected A/U/V/C fixture에서 실제 missing plan을 발견했다. aligned
  ROW/ROW 또는 COL/COL binary가 합법인데 native continuity가 resident input을 정확히 하나만
  요구해 후보를 제거했다. `NativePlacementContinuity.java:769-779`는 적어도 하나의 resident
  input과 모든 PRESENT input의 동일 witness type을 요구하도록 수정됐다. positive aligned와
  negative mixed-pool/type/ungrounded 회귀가 이를 잠근다.
- 위 반례 수정은 재현된 결함의 종료 증거다. 48개 opcode/layout/runtime family 전체나 모든
  placement/action 조합의 정리를 증명한 것은 아니다.
- policy isolation fixture에서는 protected Exact domain의 두 receipt가 유지된 채 FedAll과
  Heuristic가 각각 한 policy row를 선택했다. 두 호출 순서 모두 shared analysis와 Exact domain을
  변경하지 않았다. 현재 call path의 bounded non-reentry 증거이며 architecture 전체 증명은 아니다.
- 최신 protected `CandidateReceiptAssignmentCompletenessTest`는 5개 테스트를 모두 통과했다.
  protected domain이 비어 있지 않고 relocation binding이 0임을 확인하면서 독립 raw/legal/production
  동등성과 enumeration/action 방문 순서 불변성을 유지한다. aligned-binary positive coverage는 이
  fixture가 아니라 Global/Native 테스트의 별도 bounded 증거다.
- protected compiled-function production decoder는 `recompileOnce`가 설정된 X/Y/U/V/D 각각 두
  distinct declared relation identity와 **8개 projected literal plan**을 보존한다. recompile-context
  receipt coverage는 주장하지 않으며, 별도 IndependentGeneration 테스트가 bounded occurrence
  증거를 제공한다. canonical witness duplicate와 선언 binding domain 밖 receipt는 미분류로
  별도 보고한다.
- G008 focused sequential 실행은 **72 discovered / 70 executed-pass / 0 failures / 0 errors /
  2 PUBLIC-only skips**였다. 이어진 broad sequential 실행은 22개 class를 겹치지 않게 실행해
  **145 discovered / 132 executed-pass / 0 failures / 0 errors / 13 PUBLIC-only skips**로 통과했다.
  다만 broad report 뒤 ProductionDecoded 소스와
  CandidateReceipt privacy fixture가 변경됐으므로 두 테스트에는 pre-final 증거다. 최종
  ProductionDecoded를 포함한 세 oracle 소스는 위 G005 final dual-oracle의 14 discovered /
  13 executed-pass / 0 failures / 0 errors / 1 PUBLIC skip과 전후 동일 source hash로 보충 인증했다. 이 보충 실행은
  CandidateReceipt를 포함하지 않는다. 별도의 current-source protected CandidateReceipt 실행은
  5 tests / 0 failures / 0 errors / 0 skips로 통과했다.
- authoritative final current-source gate는 6개 class를 순차 실행해 **50 discovered /
  48 executed-pass / 0 failures / 0 errors / 2 PUBLIC-only skips**로 통과했고 CandidateReceipt의 protected 5 tests가 모두
  활성 상태였다. 최초 PolicyQuotientIsolation 실행은 잘못된 package FQCN을 사용한 harness
  시도라 결과에서 제외했으며, 올바른 FQCN으로 다시 실행한 최종 invocation은 통과했다.
- 최종 transformation 판정은 **2 PROVED / 20 BOUNDED / 34 CONDITIONAL /
  0 current COUNTEREXAMPLE**다. `PROVED`는 local identity-preserving operation만 뜻한다.
- G006 Docker/runtime qualification은 pending이며 위 보존 감사의 통과 근거에 포함하지 않았다.

### 현재 판정 경계

재현된 aligned-binary counterexample가 고쳐졌다는 사실과 전역 정리가 증명됐다는 주장은
서로 다르다. 현재 활성 fixture에서 미해결 counterexample는 없지만 다음 항목이 남아 있다.

1. 21개 proof obligation이 열려 있다.
2. 48개 rule family의 opcode, shape, input/output FType, pool/range, privacy, recompile 및 runtime
   entry parity가 universal하게 닫히지 않았다.
3. SCC/higher-arity/transient/recompile/action mutation 중 일부는 synthetic sentinel이며 production
   전수 mutation이 아니다.
4. function decoder가 별도 보고하는 선언 domain 밖 receipt의 일반 합법성과 완전성은 미판정이다.
5. closure order/idempotence/cache, multi-anchor/multi-row action, witness multiplicity, action resolution의
   reversible correspondence가 미증명이다.
6. PUBLIC-only 동작은 정책상 skip됐으며 pass로 계산하지 않는다.

따라서 최종 상태는 **OPEN / additional audit required**다. bounded set equality와 145-test broad
회귀를 `Decode(S(P)) = LegalPhysicalPlans(P)`의 전역 증명으로 확대하지 않는다.

### 재현 가능한 evidence index

아래 경로는 모두 `build/plan-space-audit-20260918/` 기준이다.

| 경로 | 증거 |
|---|---|
| `baseline/README.md` | G001 시작 상태·diff·hash·toolchain·process 보존 |
| `privacy-policy/README.md` | G002 PUBLIC-only 12개 분류와 protected 활성 범위 |
| `G003-placement-recertification-20260918/REPORT.md` | canonical 124+4 테스트 분리 실행 |
| `fresh-regression/REPORT.md` | fresh 128 discovered, 116 executed-pass, 12 intended PUBLIC skips |
| `G004_TRANSFORMATION_INVENTORY.md`, `inventory/*.json` | 56 transforms, 21 obligations, 48 families |
| `oracle-verification-v2/REPORT.md` | aligned-binary missing-plan 반례 |
| `aligned-binary-fix/native-placement-continuity-test.log` | 수정 후 focused native 회귀 |
| `oracle-verification-v3/REPORT.md` | 수정 후 bounded oracle 43 discovered, 41 executed-pass, 2 PUBLIC skips |
| `policy-quotient-isolation/REPORT.md` | shared/Exact 두 receipt의 policy 비재유입 bounded 증거 |
| `g008-focused-verification/REPORT.md` | 72-test 결과; 이후 변경된 CandidateReceipt protected fixture에는 pre-final |
| `g008-focused-verification/same-pool-fixed.log` | 과거 CandidateReceipt method 실행; 최신 판정에는 사용하지 않음 |
| `production-decoded-function/maven.log` | pre-final compiled-function projection 실행; 현재 소스는 14-test supplement가 인증 |
| `g008-broad-sequential/REPORT.md` | ProductionDecoded와 CandidateReceipt privacy fixture 모두 pre-final인 145-test 결과 |
| `G005-final-dual-oracle-20260918T231255+0200/REPORT.md` | 최종 세 oracle 소스 14-test 보충 검증; CandidateReceipt는 미포함 |
| `candidate-receipt-protected/REPORT.md` | current-source protected CandidateReceipt 5-test 통과; 비공백 독립 동등성 및 relocation 0 |
| `final-current-source-gate/REPORT.md` | authoritative current-source 6-class/50-test gate; 잘못된 FQCN 시도 제외 후 수정 invocation 통과 |
| `G008_TRANSFORMATION_PROOFS.md` | 56개 transformation별 논증·전제·판정 |
| `g008/transformation-proof-summary.json` | 2 proved, 20 bounded, 34 conditional machine summary |

문서 완료 조건은 위 evidence path가 존재하고 JSON/ID/reference 검증 및 `git diff --check`가
통과하는 것이다. 이는 G007 기록 lane의 완료 조건이며, 전역 proof 완료 조건을 낮추지 않는다.

## 12. 2026-09-19 Transient/FED placement 독립 리뷰 후속

**상태: 리뷰에서 재현된 세 결함은 수정했고 focused 회귀는 통과했다. 현재 변경은 미커밋이며,
G009 전역 보존 증명과 최종 current-source 재인증은 계속 열려 있다.**

실시간 진행 스냅샷과 다음 실행 순서는
`TRANSIENT_FED_PLACEMENT_PROGRESS_2026-09-19.md`에 정리했다.

### 재현된 결함과 수정

독립 architecture/code review는 기존 bounded fixture가 찾지 못한 세 문제를 재현했다.

1. protected ROW 입력의 `A -> rev(A) -> exp(...) -> sum(...)` 구성에서 `rev`의 runtime map은
   동적 `NATIVE_LINEAGE`인데, 다음 `exp`가 원본 A의 range를 가진 `DURABLE_MAP`으로 다시
   승격될 수 있었다. `NativePlacementContinuity`와 `NeutralPlacementGraphBuilder`는 exact range와
   endpoint-residency 증명을 분리하고, 동적 DIRECT predecessor가 있으면 stale durable map을
   발행하지 않도록 수정했다. FED/LOUT aggregate는 동일 endpoint의 선택된 동적 결과를 직접
   소비할 수 있다.
2. ROW `REV` runtime 경로가 입력 `FederationMap`에 `reverseFedMap()`을 직접 적용했다.
   `ReorgFEDInstruction`은 새 output ID의 map 복사본을 만든 뒤 복사본만 reverse하도록 변경했다.
3. ROW `ROLL`은 runtime이 split/non-split range를 지원하지만 compiler continuity 후보에서
   제거됐다. ROLL은 ROW endpoint residency를 보존하고 range는 runtime shift에 따라 다시
   계산되는 동적 native layout으로 모델링했다.

새 회귀는 `DynamicNativeLayoutCompositionTest`, `NativePlacementContinuityTest`,
`ReorgFEDInstructionFullTest`에 있다. 수정 직후 focused 실행은 **37 discovered /
36 executed-pass / 0 failures / 0 errors / 1 PUBLIC-only skip**으로 통과했다. 유일한 skip은 기존
PUBLIC-only 정책 항목이며 pass로 계산하지 않는다.

### 증거 경계와 남은 작업

- `build/plan-space-audit-20260919/final-dynamic-layout/`의 첫 broad 결합 실행은 acceptance
  evidence가 아니다. 공유 작업 트리에서 다른 G009 Maven/소스 변경과 겹쳐 class replacement,
  `NoClassDefFoundError`, fork/OOM 및 stale branch manifest가 발생했고 전후 source hash도 달랐다.
- G009 action oracle은 선언된 2-row/2-anchor/2-demand selection 범위에서 27 discovered /
  27 executed-pass를 기록했지만 builder 전수 생성을 증명하지 않는다. 관련 production-builder
  method는 격리된 180초 제한에서 반복 timeout됐고 stack은
  `NativePlacementContinuity.candidateProofAlternatives`의 proof-state 탐색을 가리킨다.
- 이 시점에는 candidate-affecting branch manifest를 최종 안정 소스에서 다시 생성·분류해야 했다.
  해당 재생성과 checker 통과는 아래 13절에서 완료됐다. G009의 21 proof obligation, 48 rule-family parity row, unfiltered universe,
  closure/order/idempotence, action/anchor 및 policy non-reentry 완료 조건은 낮추지 않는다.
- 변경은 의도적으로 미커밋 상태다. reset/stash와 후보 suppression, runtime fallback,
  privacy/TRead-TWrite/recompile 규칙 완화는 사용하지 않았다.

## 13. 2026-09-19 최종 current-source 후속

후속 독립 리뷰가 추가로 재현한 `REV -> ROLL -> EXP`, `FULL ROLL -> EXP`, `COL ROLL -> EXP`
누락을 수정했다. range를 다시 계산하는 단일 placement-input 연산은 predecessor의 exact range가
아니라 endpoint residency를 소비한다. 다중 placement-input 연산은 exact predecessor 요구를
유지한다. dynamic FULL은 map-preserving 소비 경로에만 전달하며 matrix-matrix 등 단일 partition
FULL 전제 연산으로 확대하지 않는다.

ROLL runtime map 회귀도 다음과 같이 보강했다.

- ROW split/non-split range와 입력 map 불변성;
- 실제 단일 worker FULL map의 non-split/split 결과;
- COL map의 row-range split과 FType 보존.

최종 안정 소스에서 13개 class 결합 gate는 **74 discovered / 70 executed-pass /
4 PUBLIC-only skips / 0 failures / 0 errors**로 통과했다. 실행 전후 전체 production/test source
manifest는 byte-identical이며 `NativePlacementContinuity.java` SHA-256은
`26d782dcf07a05733eba008d1b71a3276056dbfae89b338586b28adeef225aac`다. 최신 branch inventory는
**5,437 sites / 503 methods / 5,173 CONDITIONAL / 264 BOUNDED**이며 direct 및 Maven checker가
통과했다. Architecture 재검토는 이 bounded 구현 범위에 `CLEAR`, code review는 `APPROVE`를
부여했다. Code review의 별도 current-tree focused gate도 43 discovered / 42 executed-pass /
1 PUBLIC-only skip / 0 failures / 0 errors로 통과했다.

그 뒤 `DynamicNativeLayoutCompositionTest.java`의 현재 버전을 포함한 23개 class current-source
integration gate를 다시 실행했다. 결과는 **132 discovered / 128 executed-pass /
4 PUBLIC-only skips / 0 failures / 0 errors**이며 Maven exit `0`, Surefire XML 23개 재집계,
실행 전후 production/test source hash 및 branch hash 동일, `git diff --check` 전후 통과를
확인했다. 따라서 위 74-test 결과는 선행 안정 스냅샷이고 132-test 결과가 현재 테스트 소스의
최신 통합 증거다. production 기준 SHA-256과 5,437-row branch inventory는 변하지 않았다.

이후 transient replay가 동일 source realization/seed의 복수 grounded native-continuity proof 중
첫 대안만 보존하는 후속 반례를 추가로 수정했다. `exactTransientReplay`는 이제 정렬·중복 제거된
`proveCandidateAlternatives()` 전체를 전달하며, 동일 worker geometry의 서로 다른 durable producer
identity 두 개가 모두 replay proof로 남는 회귀가 통과한다. 후보 cap, suppression, fallback은
추가하지 않았다.

이 변경 뒤 branch manifest를 **5,438 sites / 504 methods / 5,174 CONDITIONAL /
264 BOUNDED**로 재생성했고 checker가 통과했다. 최신 23-class current-source integration gate는
**133 discovered / 129 executed-pass / 4 PUBLIC-only skips / 0 failures / 0 errors**다. Maven exit는
`0`, Surefire XML은 23개이며 실행 전후 전체 production/test source 및 branch manifest hash가
동일했다. 증거는 `build/plan-space-audit-20260919/g009-final-integration-post-multiproof/`에 있다.

이 결과는 G009 전역 정리를 닫지 않는다. 21 proof obligation, 48 rule-family parity row,
unfiltered expected universe, 기존 GLM builder timeout과 Docker runtime qualification은 계속
**OPEN**이다. 상세 진행·증거 경계는 `TRANSIENT_FED_PLACEMENT_PROGRESS_2026-09-19.md`와
`PLAN_SPACE_G009_PROGRESS_2026-09-19.md`를 따른다.

## 14. 2026-09-19 pre-push current-source 재인증

최종 정리 중 두 후속 반례를 추가로 닫았다. dynamic ROW REV authority는 TWrite/TRead replay를
거쳐도 typed endpoint witness와 DIRECT downstream binding을 유지한다. 또한 post-CFG exact replay와
materialization closure가 합법 receipt row를 덮어써 ProductionDecoded의 U/V domain을 각각 8에서
5로 줄이던 문제를 수정해 물리 Cartesian cardinality 1,344를 복구했다.

현재 branch manifest는 **5,469 sites / 507 methods / 5,203 CONDITIONAL / 266 BOUNDED**이며 checker가
통과했다. 최신 23-class gate는 **134 discovered / 130 executed-pass / 4 PUBLIC-only skips /
0 failures / 0 errors**다. Maven exit `0`, 실행 전후 source/manifest hash 동일이며 PUBLIC skip은
pass에 포함하지 않는다. G009 universal theorem과 timeout/Docker 경계는 계속 OPEN이다.
