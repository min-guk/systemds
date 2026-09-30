# 전체 planning 30초 목표 실행 기록

## 범위와 합격 계약
- 기준 계획: `FEDPLANNER_REFACTOR_PERFORMANCE_RECOVERY_PLAN_2026-09-29_KO.md` v2.
- 최신 사용자 지시: runtime 실행은 제외. 14 workloads × W1/3/5/7 × 4 networks × 4 planners = 896개의 **각 full compile**이 30초 이내. frontend, 공통 analysis, solver 준비/실행, 선택/변환/적용, runtime-program construction까지 포함한다.
- 60초 watchdog은 그대로 둔다. timeout, network-invalid, cleanup 미완료, 진단 JFR run을 성공으로 집계하지 않는다. JVM wall time은 compile 시간과 분리 보고한다.
- 동일 새 엔진의 896개 유효 성공 및 각 `compileNanos <= 30_000_000_000`을 확보하기 전 목표 달성을 주장하지 않는다. 작은 exhaustive legality/cost/bound 회귀와 완료 control incumbent 품질 gate는 별도로 통과해야 한다.
- evidence root: `/grid/3/cofee-lm-sweep-mchoi-20260914/planning-30s-recovery-20260929`.

## 변경 전 cleanup / 구현 계획
1. R0: 기준 JAR/source/probe를 별도 경로에 동결한다. 종료된 run06의 정확한 lifecycle/label/ID로만 잔여 container를 정리하며 타 실행은 건드리지 않는다. 실패 SliceLine의 same-analysis witness 매핑과 최초 hard-factor를 조사한다.
2. R1: authority factor의 cell별 Map/List 생성 및 반복 검색을 준비된 lookup으로 대체한다. 기존 hard truth/순서 보존 회귀를 먼저 고정한다. privacy-illegal RELOCATION 제거와 allocation 제거를 분리하며 inactive DIRECT_FOUT는 유지한다.
3. R3a: 한 immutable build/snapshot 내 Oracle OpSig/shape/profile 계산 재사용. 간접 read-set 미검증 cross-revision cache와 provisional bottom의 조기 삭제는 하지 않는다.
4. R2: 실제 authority 의존성만 solver factor에 남길 수 있는지 exhaustive 검증한다. encoded-root 변화는 동작 보존과 구별하고 legal relation, canonical raw cost, bound/tie, seed/repair/stop/quality를 검증한다.
5. 남은 병목은 측정 이후에만 고친다. selected-only validation은 complete witness가 검증될 때 기존 API를 재사용한다. cap·timer 이동, beam/top-K, fallback, privacy/anchor 완화, 테스트 기대값 완화 금지.
6. targeted tests → build/static checks → 같은 Docker에서 baseline/new 교차 3회와 최소 실패 조건 → 896 compile-only matrix → 30초 evaluator 순으로 검증한다.

## 역할 / 검토 분리
- Main: 통합, baseline/evaluator, SliceLine 진단, 최종 측정·검증·문서.
- 독립 구현: DP authority factor / 공통 Oracle 준비 재사용 (소유 파일 분리).
- 구현 완료 후 별도 read-only reviewer가 변경 계약·회귀를 검토한다. 작성자 self-check를 독립 승인으로 간주하지 않는다.

## 초기 관측
- baseline HEAD `5a3ef7d759bf30ee2d848f7123fd963c4d5bbd57`, JAR `100628da2bb3a489a43247d7d887d24aca81f41faa18ba98394e072a246d4dd2`.
- run06 driver는 종료되었으나 마지막 attempt의 container가 남아 있었다. frozen lifecycle과 spec owner를 확인하는 기존 strict cleanup API만 사용한다.
- SliceLine W1 원래 stack은 **전체 root arcConsistency**에서 실패한다. 단순한 지역 repair 실패나 공통 search-space empty로 단정하지 않는다.
- fallback 조사: 기존 shared-root resource fallback은 좁은 exact conditional 경로로 돌아가는 compatibility 경계다. 이를 삭제/확장하거나 infeasibility를 성공으로 삼키지 않는다.

## 상태
진행 중. 아직 30초 목표/전체 896 성공을 검증하지 않았다.

### 현재 엔진 R0 진단에 따른 비용표 상수비용 개선
- frozen baseline logreg W1 60초 JFR: analysis 28.7585초, MODEL_SETUP 27.1821초, seed 0.2542초. root 준비 도중 timeout.
- main-thread 3172 samples 중 cost-surface 관련 1093, fingerprint hex append 462 top-frame, freeze 관련 418 inclusive. 과거 root-freeze 병목 가설만으로 현재 원인을 대체하지 않는다.
- 개선 계획: 동일 fingerprint byte stream을 큰 digest buffer로 전달하고 동일 raw bits 반복의 hex 변환을 재사용한다. freeze callback의 lexicographic 순서·즉시 validation·cap preflight는 유지하며 cell별 division을 mixed-radix increment로 바꾼다.
- 테스트 먼저: `ExactPhysicalCostFingerprintStreamingTest`의 raw bits/NaN/-0/order/flush 경계와 `ExactFactorFreezeOrderTest`의 singleton/constant/오류 시점.

### R2 native-local 비용표의 exact source 관측 분해 계획
- candidate-r1 Docker logreg W1/W3, GLM W1은 여전히 60초 timeout. 작은 상수비용 개선으로 목표 달성을 주장하지 않는다.
- native-local input 비용의 source 축은 전체 candidate/receipt가 아니라 `LOUT` 또는 `FOUT/FType`만 읽는다. 원래 source×target canonical factor와 local policy scope는 유지하고, solver에는 source ordinal→관측 class의 유일한 deterministic auxiliary와 class×target 비용표를 사용한다.
- 합법 source 값을 삭제/병합하지 않는다. 모든 원래 assignment마다 auxiliary completion이 하나이고 canonical raw cost가 같아야 한다. fingerprint는 source mapping, class 순서, encoded scope, 압축된 모든 비용 raw bits를 포함한다.
- 전체 encoded 구조 cap preflight 전에 비용 callback을 실행하지 않는다. descriptor용 압축 비용표도 기존 ordinary freeze 순서에서만 평가한다. 원래 논리 cells와 실제 encoded cells를 별도 기록하며 cap/10초 timer 경계는 유지한다.
- exhaustive projection/cost/조건부 optimum·tie와 preflight 오류순서 테스트를 먼저 추가한다. encoded graph 변경으로 fixed-budget 선택이 달라질 수 있으므로 기존 완료 control의 품질 gate는 별도로 필요하다.
- broad 회귀의 certificate 1건(`cells=71606548`, cap=60000000)은 frozen baseline JAR에서도 동일 재현했다. cap/기대값은 완화하지 않았고 기존 실패로 별도 기록한다.

### 동결 R2와 첫 Docker 결과 — 목표 미달
- R2 JAR SHA256: `aebb10483063ac495c78356eb686c2a7d63313532d5f75c2aa7d7f03a2801326`. 측정 source는 evidence root의 `candidate-r2-source`이며 이후 main 편집과 분리했다.
- 실제 logreg native-local source projection 회귀: 25개 factor의 모든 원래 source×target 비용 raw bits를 비교했다. 논리 97,837,473 cells → encoded 301,190 cells. 실제 surface auxiliary 연결도 검증했다.
- realization-support hard encoding: 전체 hard 198,580,938 → 101,687,207 cells. 삭제한 합법 original value는 없으며 original canonical legality/cost/policy view는 별도로 유지한다. expensive input-authority 약 9,900만 cells는 아직 남아 있다.
- 같은 60초 watchdog, signal-free Docker **DP-local/logreg/W3/LAN: 52.039075초**, cleanup/network 유효, workload runtime 실행 0. search-space 12.967522초, model 1.750941초, cost surface 2.538676초, optimizer 31.578432초, selection 0.592070초, conversion 1.249543초다. 이 결과는 timeout 해소이지 **30초 달성이 아니다**.
- 다음 작업은 같은 R2의 JFR로 남은 optimizer/공통 준비를 분해한 뒤, 측정된 경로만 추가 개선하는 것이다. 이미 동결한 측정 엔진은 덮어쓰지 않는다.
- evaluator는 행의 SHA 문자열뿐 아니라 frozen `overlay/SystemDS.jar`의 실제 SHA256을 확인하도록 강화했다. 누락/변조/malformed SHA 회귀는 2 RED → 8/8 PASS이며 독립 reviewer의 scoped 승인을 받았다.

### R2 측정 후 exact arithmetic의 제한된 zero 경로 계획
- R2 W3 JFR: root freeze 10.397초, quotient 2.231초, rebuild 0.383초. 지역 solve의 `preciseSum`이 main-thread 3,741 samples 중 1,357 top-frame으로 새 최대 병목이다.
- finite 누적값의 low residue가 0이고 다음 factor의 high/low가 모두 ±0일 때만 double-double 정규화를 생략한다. 결과 high의 -0→+0 및 low의 +0 정규화, tie `addExact`/overflow, factor 순서, infinity/error 우선순위는 그대로 유지한다. residue가 남는 일반 경로는 변경하지 않는다.
- 변경 전 기존 arithmetic/error/solve golden에 zero factor 삽입·signed-zero·unnormalized residue·tie overflow 회귀를 추가해 baseline JAR로 고정한다. cap/순서/점수/10초 budget은 변경하지 않는다.

### R3/R4 측정과 R5 exact 반복·문자열 개선 (진행 중)
- R3 SHA `ed0ed14b24de48d4ef6f2401803596d0a552e21d5dc6d3cdcf319efd229ddcc0`: DP-local logreg W3/LAN **38.106475초**, GLM W3/LAN **46.650858초**. 60초 timeout은 해소했지만 30초 목표는 미달이다.
- 같은 R3의 DP-global logreg W3는 60초 timeout. root 준비는 2.3715초인데 선택된 exact elimination plan은 **860,364,478,712** candidate assignments와 **859,105,890** materialized cells를 요구한다. order compile 0.408초의 미세 최적화는 이 병목의 해결책이 아니다.
- R4 SHA `e1171795e82034eb420980b316282266773d9e1f1eda004840d6a61827d4a431`: exact owner/dependency identity-order가 같은 SCC 결과만 재사용하고, candidate fingerprint의 중간 거대 문자열을 streaming한다. authority/query context는 매번 새로 만든다. 독립 review 승인 및 focused 회귀 통과 후 동결했다.
- R4 Docker DP-local W3/LAN은 logreg **36.808468초**, GLM **50.237844초**. GLM common analysis는25.8166초이며 R3보다 느리다. 단일 비교로 개선 배율/성능 보존을 주장하지 않는다. 두 결과 모두 유효 compile/cleanup 완료, runtime 실행0이다.
- R5 계획: (a) GLM final89,734 alternatives의 signature가 총1,574,124,633 UTF-16 chars라는 실제 allocation 근거에 따라 기존 canonical text 표현을 공유하고 `signature()`만 lazy materialize한다. 비용 fingerprint와 지역 seed state key도 flatten하지 않게 연결한다. 기존 문자열 내용/정렬/hash/동등성/first-duplicate/record-style toString을 잠근다. (b) built-in zero-tie solver만 첫 bucket factor의 +∞ 값 bitmap을 이용해 확실히 불가능한 값의 반복을 피한다. 뒤쪽 factor의 ∞로 앞선 overflow를 숨기지 않으며 기존 dense cap/statistics/sum-order/choice를 유지한다.
- R5 finite-row: frozen R4 기준 동작6 tests PASS, 구현후 bitmap 직접 검증을 포함한7 + arithmetic9 =16 tests PASS, 독립 architect `CLEAR`. 큰 separator를 여전히 dense하게 방문하는 한계는 남아 있다. 이것만으로 DP-global30초를 보장하지 않는다.

### R5 확정 관측 / R6 진행
- R5 SHA `f207cfe2c62cbc4a3198e35fd40b6be3d43786b8920b6f596ede2bc96300332a`, 통합150 tests PASS. Docker local logregW3 38.903318초, GLMW3 41.796855초. global logregW3 60초 timeout. 새로운 동일 엔진896≤30초 성공은 여전히 미확보다.
- R6는 기존 logical cap/order/statistics/정밀 산술을 유지하는 sparse exact intermediate/join을 구현하고 독립 검토한다. 후속∞를 먼저 검사해도 overflow를 숨기지 않는 충분조건을 적용한다(`exact-sparse/ARCHITECTURE.md`). 모든 finite tuple을 lossless join하며 rounded tie는 원래 최소 domain index로 고정한다.
- 회귀 테스트는 최종 objective뿐 아니라 매 중간 high/low/choice raw bits를 dense reference와 비교한다. 메모리 reviewer가 near-dense/partial-index OOM을 발견했으므로 저장 형식을 수정하고 해당 probe를 재검증한 뒤 동결한다. 성능을 이유로 OOM을 기존 문제로 숨기지 않는다.

### R6 동결 / 첫 30초 통과 subset
- R6 SHA `6dab4194a6088e36ddb05269b6925c92bbb65072018cedc10066330029ab855b`, 최신 통합17 classes/167 tests 모두PASS, 모든2,170 src/main/POM hash와 JAR 동결 확인. 메모리 reviewer 재현도 통과했다.
- Docker DP-local logreg/W3/LAN은 **24.226584초** 유효 compile 성공/cleanup 완료/runtime0. common13.14659초, adapter8.41429초, 그중 optimizer3.88461초. R5 optimizer15.82232초와 구분한다. 이것은 한 condition의 결과이지 전체30초 완료가 아니다.
- R6 GLM local 및 logreg global 측정은 계속한다. R7은 측정된 common realization-group deep hashing과 model scope membership hashing을 분리해 개선/검토한다. R6 source/JAR는 수정하지 않는다.

### R6 잔여 결과 / R7 검증 중
- R6 DP-local GLM/W3/LAN full compile **46.741396초** 유효 성공이나30초 미달. global logreg/W3/LAN 진단은60초 timeout. 아직 전체 목표 미달이다.
- R7은 canonical clause 병합, immutable text의 정확한 합성 hash, legacy equality 보존 scope membership, 호출 범위 worker-count memo를 통합한다. 새 후보를 지우거나 solver cap/timer를 바꾸지 않는다.
- 독립 joint-space fixture는 원래64 placement×2 receipt-layout×4 action-subset universe와 실제 production relation decode를 비교하며 legal all-writer witness 삭제를 검출한다. PRIVATE/PRIVATE_AGGREGATE3 tests PASS; 제한된 fixture의 completeness gate이며 모든 DML의 전역 증명이 아니다.
- 최신 통합/독립 review 뒤 새 source/JAR로 재측정한다. R6 broad certificate의72,182,723>60,000,000 오류는 baseline과 동일 testcase이나 숫자는 달라 downstream 검증 공백을 유지한다.
- 독립 reviewer는R7 production 변경을 승인했으나 새 joint-space test의 over-generation filtering/tuple collapse를MEDIUM으로 지적했다. 이 gate는 아직 미완료이며 lossless decode/extra mutation 수정 후 재검토한다.

### R7/R8 완료 범위와 R9 진행
- R7 SHA `207fb589fb3759c2b4ec2382d7a8bd25b4cf3f3340347ba8258bec73d0cfef0a`: 통합222 PASS; localW3 logreg23.234482초/GLM42.662154초.
- 독립complete-space gate는 실제47 domains/108hardfactors의64 rawassignments에서8EMITTED/56REJECTED/0UNKNOWN을강제하고production `ExactPhysicalSelection.create`로decode한다. 초기tautology/UNKNOWN결함을고쳤으며최종APPROVE. 작은PRIVATE/P_Afixture범위를넘어선증명은아니다.
- R8 SHA `67a51182dfc8bf7558d9e69937f9701667c59468ebd14c9f13ee66cd06769d1b`: exact rawvalue-response profile의 commonrefinement로메시지를저장한다. 원래logicalscope/cap/order/costbits/choice를유지하며2건heap회귀를수정했다. 통합248 PASS/독립APPROVE.
- R8 signal-freeDocker global logregW3/LAN **29.791289초**, localGLMW3/LAN **43.809625초**. globalGLMW3는중간factorlogicalsizeoverflow로실패. 아직전체목표미달.
- R9는완전한live-Hopreadset을갖는build-localdirectcontinuation과기존canonicaltext를이용한fingerprintstreaming을별도검토한다. globalGLM은lossless표현/순서개선조사중이며cap을바꾸지않는다.

### R9 동결 및 남은 전역 제약
- R9 SHA `bc9a92ee4e012662b1a60ced2822881b045cfdf161bfa245400337fc96fe7f31`: 최종 29 classes/371 tests, 실패·오류 0/기존 ignore 1. fingerprint streaming, per-domain unary projection 재사용, guard 검증 direct continuation을 포함한다. 리뷰에서 발견한 nullable metadata와 frontier/guard 시점 결함은 regression으로 잠그고 수정했다.
- R9 GLM W3 Docker 진단으로 continuation의 실제 hit/dirty 비율을 측정한다. 회귀 통과만으로 성능 개선을 주장하지 않는다.
- R8 확대 subset은 global logreg W7 35.325474초, GLM W1 local/global timeout, global logreg W1 factor overflow다. evaluator: 896개 중 관측 7/30초 통과 1/미측정 889. 여전히 목표 미완료다.
- 전역 GLM의 authority-product 분해 가설은 반증됐다. 가장 큰 23,956개 alternative가 전부 서로 다른 execution/support base라 authority 축만 분리해도 차원이 줄지 않는다. 일부 base는 Cartesian product도 아니므로 없는 조합을 채우지 않는다. witness-preserving support relation의 제한된 구조 조사만 계속하며 cap/후보/정확성 완화는 하지 않는다.

### R9 보수적 제외 / R10 통합 진행
- R9 GLM W3 signal-free 42.586168초, R9 비용 변경만 남긴 동결 ablation 36.833656초. 단일 비교로 배율을 주장하지 않지만 continuation의 순수 이득이 확인되지 않아 해당 변경과 전용 test seam을 main에서 제외했다. closure는 검증된 R8와 byte-identical이며 R9 비용 streaming/unary 재사용은 유지한다.
- R10은 immutable required-support 목록의 기존 weak-identity cache 재사용과 build-local relocation-obligation index다. 후보·privacy·authority·비용·순서·cap은 그대로다. null FType miss와 live action/logical/writer invalidation을 회귀로 잠갔다. 최신 통합/독립 검토/동결 Docker는 진행 중이며 전체 30초 목표는 미완료다.

## 최신 판정: R10 부분 개선, 전체 30초 미달

최신 동일 엔진: `8b3d13375085af1e07c99e145f031dca9400fe547dd922a0eeb4f1605c52f5e8`.

| 조건 (LAN) | compile | 30초 판정 |
|---|---:|---|
| logreg W3 / DP-local | 23.715556초 | 통과 |
| logreg W3 / DP-global | 28.294900초 | 통과 |
| l2svm W3 / DP-local | 8.434776초 | 통과 |
| P1_FULL W3 / DP-local | 16.806118초 | 통과 |
| GLM W3 / DP-local | 40.404205초 | 실패 |
| GLM W1 / DP-local | 56.110871초 | 실패 (기존 60초 timeout 해소) |
| GLM W3 / DP-global | `EXACT_VE_FACTOR_CELL_OVERFLOW` | compile 실패 |

- evaluator: expected 896 / observed 7 / target 통과 4 / missing 889 / 전체 false. 성공 행렬은 아직 없다. 7개 완료 attempt 모두 cleanup resolved. runtime phase는 실행하지 않았다.
- 최신 Java 30 classes/370 tests: 실패·오류 0/기존 ignore 1. Python evaluator 8 PASS, source/POM 2,171 hashes와 JAR는 독립 검증에서 일치했다. quiet build/Maven의 root-observed exit 0은 별도 사후 provenance로 명시했다.
- 공통 GLM W1 준비만 29.888139초이며 이후 model/cost/optimizer도 필요하다. 남은 문제는 몇 개 cache만 추가하면 풀린다고 단정할 수 없다.
- E-only lossless support projection은 작은 관계의 복원에는 성공했지만 전체 graph는 최소 관측 최대 intermediate 22,538,226,233,952 cells로 unchanged cap을 크게 넘었다. 독립 architect는 이 후보의 production 통합을 BLOCK했다. production에는 반영하지 않았다.
- 다음 설계 경계는 common/model/solver에 걸친 factor-family별 support-witness 표현이다. 원래 witness/정밀 산술/tie/cap 보존을 증명하기 전 구현 후보로 승인하지 않는다. 현재 목표는 **미완료**이며, 제한 완화나 후보 삭제로 완료 처리하지 않았다.
- 남은 검증: 전체 896, 3회 교차 control, 일부 plan-named gate, broad certificate downstream 및 full Python attestation 공백. 상세 증거와 변경 파일별 위험은 `SESSION_ISSUES_2026-09-29.md` 참조.
- W1/LAN DP-local control 진단 1회씩에서 l2svm seed/final과 P1_FULL seed/final은 baseline과 raw objective bits까지 일치했다. 이는 품질의 bounded 확인이지 3회 signal-free 시간 gate가 아니다. 두 진단도 runtime 0/cleanup 완료다.
- 검증된 R10 JAR를 main `target/SystemDS.jar`에 그대로 반영했고 SHA 일치를 확인했다. 설치된 JAR로 추가 unit 23 tests가 통과했다. 이전 main 산출물은 evidence root에 백업했으며 측정 중인 frozen artifact는 변경하지 않았다.
