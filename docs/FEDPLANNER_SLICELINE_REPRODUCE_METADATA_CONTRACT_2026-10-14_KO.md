# SliceLine reproduce 기준과 worker metadata 수집의 실험 계약

## 결론 / 목적

목적은 **실제 workload를 바꾸지 않고, 공개가 허용된 입력 정보로 정의된 비용 모델을 정확히 평가하여 실행 가능한 계획을 선택한 뒤 RuntimeProgram까지 컴파일하는 것**이다. 숫자를 낮추기 위해 workload 일부를 숨겨서 먼저 실행하는 것이 아니다. 기존 목표인 feasibility/privacy/UNKNOWN·DEFER/worker binding/exact cost 및 최적해 보존은 유지한다. 최적해는 명시된 비용 모델·입력 metadata·지원된 plan space에 대한 의미이며, 실제 wall-clock runtime 전역 최적성을 뜻하지 않는다.

원본 SliceLine처럼 **입력 준비와 본 workload의 경계를 명시**한다. worker metadata 수집은 허용 범위·정확성·비용·동일 입력 제공을 입증한 경우 공통 준비 단계로 도입할 수 있다. 그러나 원본 reproduce는 “모든 중간 shape를 미리 알아낸 뒤 무료로 planner에 주는 방식”의 근거가 아니다. 리뷰어가 수용한다고 보장할 수는 없지만, 아래 계약으로 실험의 주장과 실제 측정 대상을 일치시킬 수 있다.

현재 상태: **원본 조사 완료, 아래는 제안한 실험 계약이며 metadata 수집 production 구현 및 실측은 아직 없음.** 기존 no-RPC/봉인 metadata-only 결과를 새로운 metadata-assisted 결과로 덮어쓰지 않는다.

후속 [P2 전용 근본 원인 보고서](FEDPLANNER_P2_PLANNING_BLOCKERS_ROOT_CAUSE_2026-10-14_KO.md)는 worker별 실제 오류와 ADR01/02의 range 계약을 구체화한다. 특히 현재 `MemoCostModel.multiReturnBuiltinCost()`는 dummycode를 명시적으로 거절하므로, 아래 metadata 수집 설계만 구현하거나 출력 폭만 채우는 것으로는 충분하지 않다. exact proof를 수용하는 shape/cost 지원 검증도 필요하다.

## 1. 원본 reproduce에서 실제로 한 일

원본: `/home/mchoi/reproducibility/sigmod2021-sliceline-p218`, 저장소 revision `17e6099c04666e9463e6f8b4adcbb2793219a71a`. 해당 subtree의 작업 트리 변경 없음. 원본 스크립트를 실행하지 않고 읽었다. 확인한 파일의 복사본·SHA-256은 [evidence manifest](evidence/sliceline-metadata-methodology-2026-10-14/manifest.json)에 보존했다.

| 단계 | 원본 소스 근거 | 확인한 사실 |
|---|---|---|
| 입력 준비 | `run4PrepareLocalData.sh:3–17` | feature transformation과 모델 학습으로 SliceLine 입력 `*_X`, `*_e` 생성. `run5LocalExperiments.sh`와 별도 단계 |
| Adult 준비 | `exp/dataprep/dataprepAdult.dml:7–49` | recode/bin transformencode, 모델용 one-hot 생성, multiLogReg 학습/예측; SliceLine에는 recoded X와 오류 벡터 e 저장 |
| Covtype 준비 | `exp/dataprep/dataprepCovtype.dml:8–40` | 같은 형태로 recoded X와 오류 벡터 e 저장 |
| 본 workload | `exp/explocal/SlicingExp2.dml:1–11` | 준비한 X/e를 읽고 slicefinder 실행 후 결과 기록 |
| 실험 시간 | `exp/explocal/runExperiment2.sh:6–16` | X/e를 입력받는 Java 프로세스 외부 시간을 측정하고 1,500ms를 차감. config별 3회 수행 |
| 본 알고리즘 내부 계산 | `exp/explocal/SlicingExp1_3.dml:6–25,36–88,239–245` | 함수 진입 후 도메인/one-hot 생성, pruning·candidate 평가 및 내부 ID recode가 남아 있음. 준비 단계로 모두 제거하지 않음 |

주의: `SlicingExp1_3.dml`은 원본에 포함된 구체적 알고리즘 variant의 직접 소스 근거다. 별도 `SlicingExp2.dml`이 호출한 jar builtin의 bytecode와 완전히 동일하다고 검증한 것은 아니다. 외부 프로세스 측정과 내부 `time()` 로그도 서로 다른 지표다.

**그대로 따를 부분:** 준비된 입력을 별도 명시하고 본 연산은 유지하는 실험 경계.

**그대로 복사하지 않을 부분:** JVM 비용의 고정 1.5초 차감, 원본 3회 반복, 원본 중앙집중식 준비를 private federated metadata 공개의 권한으로 간주하는 것. 현재 요청대로 각 조건은 1회이며, 실제 시간을 차감 없이 보고한다. 원본 실험은 FedPlanner compile-only/no-RPC 성능 증거도 아니다.

## 2. 현재 SliceLine과 P2는 다른 workload다

### SliceLine ADULT/COVTYPE

현재 `sliceline_fed.dml`은 준비된 numeric X/e를 federated 입력으로 받아 `slicefinderX`를 실행한다. 이 **준비된 입력 경계**는 원본 reproduce와 부합한다. 그러나 전체 실험이 원본과 완전히 같다는 뜻은 아니다. 현재 ADULT의 maxL=3, ltype="for" 등 adaptation parameter와 federation/privacy는 별도 공개해야 한다. 원본 `SlicingExp2.dml`은 Adult에 maxL=0을 전달한다.

현재 `slicefinder_core.dml:13–18`의 colMaxs·one-hot 생성, `33–45`의 removeEmpty·candidate 생성, `226–228`의 ID recode/table은 본 workload다. 입력 column domain 통계를 알아도 pruning 후 행 수, candidate 중복 제거 수, 후속 level shape가 모두 결정되는 것은 아니다. `ltype="for"`도 decodeTopK 등의 모든 PARFOR를 제거하지 않는다.

### P2_PREP

`P2_PREP.dml:8–31`은 raw federated frame에서 dummycode transformencode, 통계/이상치 처리, scale, split을 수행하는 **전처리 자체의 workload**다. SliceLine 입력 준비와 혼동하여 이 전체를 측정 밖으로 옮기면 P2 workload가 달라진다. DML 및 계산 경계는 유지한다.

P2의 열 수는 recode의 **전역 distinct domain**에 의존한다. `ColumnEncoderDummycode.updateDomainSizes()/initMetaData()`는 distinct 수를 `max(1, distinct)`로 사용한다. shard별 distinct 수의 합은 중복 domain을 중복 집계하므로 정확한 전역 폭이 아니다. null/NA, value conversion, recode equality도 runtime encoder와 같아야 한다.

현재 봉인 `.mtd`에는 rows/cols/nnz/type/format/privacy는 있지만 이 domain 정보가 없다. `cofeePublicRecodeMetadata` + deployment opt-in은 공개 정책이지 distinct 값의 증거가 아니다. W3/W7의 기존 ceil-tail range와 현재 canonical ordered-remainder geometry 불일치도 별도 문제이며, metadata 수집이 해결하지 않는다.

## 3. metadata-assisted 실험 계약

1. **준비 성격 분리:** (A) workload/spec 선택 전에 데이터 제공자가 공표·봉인한 workload-independent dataset 통계와 (B) 특정 transform spec 때문에 새로 수집하는 통계를 구분한다. P2의 exact global recode dictionary 생성은 transformencode의 의미적 build 단계이므로 (B) **spec-specific preprocessing**이다. dataset registration이라고 이름 붙이거나 DML에 transformencode가 남아 있다는 이유로 공짜 입력으로 간주하지 않는다. (B)는 전체 cold 시간에 반드시 포함한다. 원본 SliceLine의 입력 준비는 이 작업을 무료로 제외할 근거가 아니다. 학습/후보 탐색 결과나 정답 plan은 통계에 넣지 않는다.
2. **원본 값은 worker에 유지:** 집계 결과 공개 허용과 원시 categorical 값/사전 공개 허용을 구분한다. 전역 distinct를 얻기 위해 dictionary union을 쓴다면 그 dictionary 공개까지 별도 허용되어야 한다. “집계 metadata니까 무조건 안전”이라고 간주하지 않는다. 현행 `TransformEncodeMetadataPrivacy` 두 겹 opt-in·spec 검증을 우회하지 않는다.
3. **exact 계산:** 기존 encoder의 recode 규칙을 재사용한다. HLL·sampling 등 근사 distinct, shard count 합산, fixture 상수/실행 결과에서 역추정한 차원은 금지한다. 공개 권한 또는 exact 획득 경로가 없으면 UNKNOWN/DEFER로 남긴다.
4. **입력 identity 검증:** artifact 생성 시각·hash·bytes·collector version·생성 목적과 treatment 전 봉인 여부를 기록한다. 실패한 planning/선행 runtime/결과 출력에서 차원을 역추정해 artifact를 만들지 않는다. per-workload/per-run artifact는 preprocessing으로 분류하며 재사용 허용 조건과 횟수를 공개한다. dataset snapshot/version, transform spec, column schema, worker 순서·주소·실제 range, encoder semantics version을 artifact에 묶는다. 하나라도 바뀌면 재수집한다. 단순히 동일 파일명이라는 이유로 재사용하지 않는다. 검증 불가/stale/불완전 수집은 fail-close한다.
5. **workload 유지:** P2 transformencode 및 후속 계산은 실제 DML에 남긴다. 수집 산출물을 runtime encoder에도 재사용하려면 동일 값/사전 identity와 동등성을 별도 검증하고, 원본 경로 대비 전환을 보고한다. 수집과 runtime에서 중복 계산하면 그 비용도 숨기지 않는다.
6. **정보 공정성:** (i) 기존 native/no-new-metadata 비교와 (ii) common-information metadata-assisted 비교를 분리한다. 후자는 비교 planner들이 실제로 같은 의미의 metadata를 소비하는지 확인한다. 단지 artifact를 제공했지만 baseline이 읽을 수 없다면 정보 비대칭을 명시하고, 그 속도 차이를 planner 알고리즘 개선만의 효과로 주장하지 않는다.
7. **범위 제한:** 입력 통계만으로 모르는 중간 결과를 얻기 위해 workload prefix를 먼저 실행하지 않는다. 그런 기능은 metadata 조회가 아니라 실행/재계획이며, 별도 execution model과 실험 계약이 필요하다.

간단한 구현 방향은 새 범용 캐시 계층이 아니라 **기존 source metadata acquisition에 검증된 optional exact-statistics artifact를 연결**하는 것이다. planning 중 임의 RPC를 숨겨 넣지 않고 collector와 planner의 경계를 분리한다. 통계가 없을 때 기존 no-RPC 경로를 유지한다.

## 4. 평가 지표 — 10초를 무엇으로 주장할 것인가

| 지표 | 포함 범위 | 허용되는 주장 |
|---|---|---|
| `T_metadata` | worker 수집·전역 결합·검증·필요 통신 | 준비 비용과 bytes/RPC/worker CPU 별도 공개 |
| `T_plan_compile` | 검증된 metadata 이후 planning 시작부터 완전한 RuntimeProgram 생성 | metadata가 이미 유효한 **warm metadata** 조건의 지연 |
| `T_cold_to_ready` | metadata가 없는 최초 요청부터 수집·검증·planning·compile 완료까지 동일 외부 타이머 | “처음부터 runtime 직전까지 10초”의 주 지표 |
| `T_program_execution` | 실제 본 workload 실행 | compile-only와 섞지 않는 결과 동등성·runtime 지표 |

`T_cold_to_ready`는 직접 측정한다. 단계를 직렬로 측정했다면 합계와 대조하되 겹친 시간/누락을 숨기지 않는다. 공통 JVM/container 시작·초기화 포함 여부도 각각 기록하고 모든 planner에 동일하게 적용한다. `T_metadata`를 빼고 cold under10이라고 말하지 않는다. 재사용 횟수별 amortized 결과가 필요하면 원시 비용과 가정도 함께 제시한다.

현행 network-none compile-only harness에는 live worker collector를 몰래 끼워 넣을 수 없다. 실제 collector 측정은 허용된 Docker entrypoint를 통해 명시적 worker 접근을 갖는 별도 준비 단계가 필요하다. 이후 compile-only 단계는 봉인된 artifact로 실행할 수 있으나, 두 단계 전체 latency도 함께 측정해야 한다.

## 5. 구현 위치와 검증 gate

| 구현 지점 | 최소 변경 | 필수 검증 / 위험 |
|---|---|---|
| worker transform encoder / 기존 metadata 획득 경로 | 승인된 exact domain metadata 생성; 기존 encoder equality/NA 의미 재사용 | shard domain 중복/불일치/빈 입력/권한 없는 공개 거절; 값 유출 방지 |
| `MemoSources` 및 source metadata 연결 | snapshot/spec/pool/range identity 검사 후 statistics 전달 | stale/부분 실패/worker 순서·range 변화 거절; 기존 ingress 검사 유지 |
| `MemoShapes` transformencode transfer | proof가 있는 경우에만 exact width 반영 | CP/FED runtime 출력 shape 비교; unknown은 exact로 승격하지 않음 |
| `MemoNativeRules` canonical identity | capability를 결정하는 새로운 metadata가 있다면 기존 key authority 한 곳에 포함 | RuleBuilder 별도 key 복제 금지; 서로 다른 spec/domain 입력 병합 금지 |
| `MemoCostModel` | 정확한 primitive 입력 차원에 연결 | 원본 비용 및 optimum parity; output width만으로 cost에 필요한 모든 metadata가 충족된다고 가정하지 않음 |
| Docker campaign / evidence | 준비 단계 receipt·시간·통신과 compile-only receipt 분리 | 동일 artifact 모든 planner 제공, cold/warm 구별, actual W1/3/5/7 각1회 |

순서: 정책 및 전역 domain 획득 경로 검증 → collector RED/GREEN → immutable metadata identity/shape 회귀 → 기존 soundness → actual P2 W1/3/5/7와 원본 runtime encoder 동등성. 통계 수집 구현을 하기 전에 원본/수정 경계와 oracle-key 영향 범위를 별도 review한다.

### 정확성·완료 증거 gate

성공에는 solver `OPTIMAL`뿐 아니라 capability/coverage 분석 완료, 선택 계획의 모든 관련 feasibility/privacy 검사 확정 합법(미해결 UNKNOWN/DEFER 없음), exact worker/range binding, 동결 cost primitive로 목적함수 재계산 일치, 전체 RuntimeProgram 생성 receipt가 필요하다. UNKNOWN/DEFER 후보를 조용히 버려 축소한 공간에서 OPTIMAL을 얻는 것은 합격이 아니다. metadata-assisted 전환은 원본 경로와 선택 계획/compiled program fingerprint를 비교하고, metadata 재사용으로 instruction이 합법적으로 달라지면 차이를 공개하고 실제 결과·privacy audit 동등성을 별도 검증한다. compile receipt만으로 학습 실행 결과 동등성을 주장하지 않는다.

## 6. 이 계약으로 해결되지 않는 지원 공백

- 일반 PARFOR의 task-local scope, result merge, planned supply/retention, 비용 계약.
- SliceLine의 데이터 의존적 candidate/pruning 중간 크기와 반복 횟수.
- P2의 기존 실제 input range와 canonical partition 정책 불일치.
- 어떤 workload의 본 연산을 수행하지 않고는 알 수 없는 정확한 중간 통계.

현재의 “runtime 전에 단일 concrete exact-cost 전역 계획을 완성” 요구와 일반적인 데이터 의존 프로그램 지원은 자동으로 양립하지 않는다. 필요한 값을 입력 metadata에서 증명하지 못하면 더 큰 정적 증명, 명시적 symbolic/guarded 계획, 또는 계획된 실행 단계 경계라는 설계 확장이 필요하다. runtime fallback이나 UNKNOWN 무시로 성공 처리하지 않는다. 후자의 실행 경계를 도입하면 기존 compile-before-execution 목표 변경임을 먼저 명시해야 한다.

## 7. 실제 encoder 소스 추가 검토 — 구현 전 경계 확정

- `MultiReturnParameterizedBuiltinFEDInstruction.java:159–223`: policy 검증/flag 제거 후 worker `CreateFrameEncoder` 호출, 실제 logical range 순서로 정렬·merge, global M 생성. `224`의 `encodeFederatedFrames()`부터 X0 materialization이 시작된다.
- 같은 파일 `360–389`: worker는 local FrameBlock을 읽고 `encoder.build(fb,1)`을 수행한다. X0는 만들지 않지만 **반환 payload는 count 하나가 아니라 built MultiColumnEncoder와 column names**다.
- `ColumnEncoderRecode.java:264–282,288–317,340–363`: exact dictionary union, global M의 token/code·numDistinct, partial dictionary 직렬화 경로. 현재 release 정책은 최종 global dictionary M을 허용한다. 그러나 worker별 partial dictionary와 shard membership 노출을 별도 재사용 산출물로 공개하는 권한까지 명시한 것은 아니다. 이것을 “원시 category는 절대 coordinator에 오지 않는 count-only 집계”로 설명하면 사실과 다르다.
- 최소 재사용 경계는 기존 build/merge/M 생성 부분을 **build-only helper 한 곳**으로 추출하여 기존 instruction과 preprocessor가 공유하는 것이다. helper는 apply 직전에 멈춘다. 원본 전체 encoder를 별도로 재구현하지 않는다.
- planner에는 exact transform occurrence·정규화 spec·dataset content/sidecar manifest·actual map/pool·policy state에 묶인 좁은 immutable domain fact를 전달한다. `MemoSources.Source`는 현재 owner/partition/privacy/rows/cols만 보유하므로 dictionary fact를 이 기존 record에 무근거로 얹지 않는다. `MemoShapes.multiReturnOutputShape`가 dummycode width proof를 소비하는 경계다.
- `FederatedPlannerUtils.deriveFedInitSignature`의 endpoint/range identity만으로는 dataset content revision을 구분할 수 없다. 파일 경로뿐 아니라 봉인된 content/sidecar digest도 검증해야 한다.

이는 **소스 검토에 따른 구현 설계**다. live worker RPC나 collector 실행을 하지 않았으며 새 metadata 기반 planning 성공/latency/privacy runtime 검증을 주장하지 않는다. 현재 render-only receipt를 live execution 권한으로 해석하지 않는다. 허용된 Docker 경로의 별도 준비 단계와 평가 receipt를 만들어야 한다.
