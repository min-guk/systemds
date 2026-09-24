# Session issues — 2026-09-23

## 전체 workload의 현재 P/E 동등성 인증

- **상태:** 실행 중. 전체 EQUAL 인증은 아직 성립하지 않는다.
- **문제:** planning 224조건과 registry에서 전개한 388개 후보를 포함한 전체 조건에서 P와 E의 모든 물리 plan을 정확히 비교해야 한다. planning 외 조건은 기존 placeholder만으로는 컴파일 입력이 확정되지 않았고, 큰 조건의 raw 공간은 직접 열거할 수 없다.
- **분모 증거:** planning 224조건은 원래 동결된 입력을 유지한다. 추가 후보 388개 중 base/ML10 296개, generated microbench 28개, KDD98/USCENSUS SliceLine 64개를 `FROZEN_COHORT_DERIVED_ARGV_612`에 결합했다. 612개 입력 준비·0개 미해결, 생성기 재생성 검사가 통과했다. 초기 derived-v2는 296개 compiler argv 누락으로, v3는 P2 campaign 16개 필수 JVM 옵션 누락으로 최종 실행 근거에서 제외했다. 현재 v4 catalog SHA는 `7d55c9f469077038f6d1b5a3a6a4ef5887bc55089712c2d74c1738b4812376ac`다. SliceLine 64개는 source-backed 파생 compile-model 조건이므로 historical `FULL_CURRENT` 관측치로 승격하지 않는다.
- **구현·검증:** compact physical dictionary/reference와 독립 verifier, 저장 E factor의 정확한 accepted count, 전체 campaign을 누락 없이 다루고 미완료를 실패로 보고하는 matrix runner를 추가했다. 동결·모델 수집·artifact 재검사를 묶는 한 명령 게이트와 source/class snapshot 도구도 추가했다. 물리 비교는 검증된 P/E 모델 matrix를 재사용하며 두 사본의 해시를 재검사한다. 최신 fedplanner Python 단위·mutation 테스트 220개가 통과했다. Pca 한 조건의 P compact proof 377,856개와 E compact proof 479,232개는 각각 물리 600개, 차집합 0이고, 기존 전체 JSON과 compact를 P/E 9/9 shard에서 byte 수준으로 비교했다. 이 Pca 결과는 이전 source binding의 캡처 집합 비교이며 P acceptance 전체 인증은 아니다. planning LM w3/LAN의 물리 60개 일치 결과도 뒤늦게 확인한 loop-seed 결함이 있는 v3 빌드의 진단 자료로만 보존한다.
- **P acceptance 위험:** 그래프 구조만으로 Java의 모든 acceptance를 증명하지 못한다. candidate support/입력/relocation의 원시 DTO와 Python 재구성기가 실제 동결 P1 artifact의 candidate receipt 2,118개·realization reference 1,669개 및 relocation 관련 원시 사실을 검증했고 원시 source owner 변조도 거부했다. assignment별 feasible variant·worker pool을 포함한 opaque predicate가 남아 있어 runner는 equality를 `CAPTURED_EQUAL` 이하로 제한한다. 새 증거가 적용된 모델과 과거 artifact도 분리한다.
- **P1/P2 위험:** P1의 E capture에서 함수 출력 call ordinal과 CFG 경로 정규화를 수정해 대표 조건을 캡처했다. GMM/GLM의 인라인 함수 입력 provenance를 compiler-owned 사실로 옮겼으며, 최적화로 사라진 입력과 원본 누락을 구분하는 최종 nullable 패치는 실제 GLM/GMM 재검증 중이다. P2 campaign의 JVM 옵션 누락은 원본 protocol에서 condition hash까지 전달하도록 고쳤고, 교체 셀 w3/wan_heavy P/E 캡처와 저장 artifact 재검사가 모두 통과했다. 저장 E factor 관계는 P1/P2에서 재생되지만, native 모델에 검증된 compositional 물리 projection과 physicalLogicalInputs가 없어 canonical 물리 plan 집합을 재구성할 수 없다. 좌표 표 이름/크기만으로 `COMPLETE`가 되던 잠재 경로를 차단했다.
- **다음 검증:** 과거 v2/v3/v4-v8 진단 matrix의 capture 오류를 분석하고 P direct realization 2-cycle의 실제 실패 셀 canary를 수정·재검사했다. source/class SHA를 동결한 `current-pe-final-build-v9`에서 612개 P/E 모델 matrix를 각각 6개 작업·전역 최대 12 JVM으로 다시 캡처 중이다. 초기 v4 GLM P의 raw DTO heap OOM은 bounded serialization으로 수정했다. P acceptance의 남은 assignment 의미론, E와 P의 canonical 물리 projection 및 양방향 차집합을 해결해야 전체 gate를 열 수 있다.
- **역사·runtime 범위:** 과거 B0/B1 비교와 독립 runtime R 인증은 이번 P/E equality와 별도 미완료로 유지한다.

## P `multiLogReg` direct realization closure의 정확한 2-cycle

- **상태:** direct closure 2-cycle 수정과 실제 실패 셀 canary·독립 코드 검토 완료. 전체 P acceptance와 612개 재캡처는 진행 중이다.
- **환경/조건:** `FROZEN_COHORT_DERIVED_ARGV_612`의 `cell_2371107ddd5d6a7508e3`, P native-model capture. 최종 v8 후보 빌드의 원본 캡처는 약 33분 뒤 `Candidate-specific direct realization closure did not converge`로 실패했다.
- **재현 절차:** 동결 catalog/evaluation과 동일한 compiler network 환경에서 `PlanningNativeModelCapture`로 해당 셀을 캡처한다. 계측 로그는 `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/p-nonconverge-diagnosis/direct-trace-6/stderr.log`에 보존했다.
- **관측 증상:** 계측 `call=14 outer=0`의 direct pass 3과 5가 동일한 전체 fact list로 돌아왔다(`repeatedPass=3`). pass 4는 다른 fact list였다. 세 pass 모두 logical boundary 변경은 0이다. `multiLogReg.dml:197:24`의 한 `AggBinaryOp` compiler-temp 행에서 realization reference와 support clause가 큰 집합과 작은 집합 사이를 왕복한다.
- **원인 분석:** 이전에 발행한 executable realization을 다음 direct proof query의 전제로 재사용해 source-support가 정확한 2-cycle을 만들었다. 여러 top-level 호출을 단일 호출의 반복으로 해석한 초기 가설은 `call` 식별자 trace로 기각했다. fact 개수 기반 반복 상한만 늘려서는 해결되지 않는다.
- **해결 방법/의사결정 근거:** 첫 proof-only template 실험은 실제 셀을 통과했으나 문자열 lineage 충돌 위험이 있었다. published root를 유지한 mode 분리 실험은 같은 비수렴을 재현했다. 최종 수정은 내부 typed proof-only root, mode별 상태·memo 격리, 자기 참조 receipt 억제를 적용했다. 실제 실패 셀이 22분 44초에 `COMPLETE`였고 offline verifier는 `STRUCTURE_VERIFIED`였다. 이는 acceptance 전체 인증이 아니라 P 모델 수집 오류의 해결 증거다.
- **수정 파일·검증:** `NativePlacementContinuity.java`, `NeutralPlacementGraphBuilder.java`와 대응 테스트 2파일을 공유 소스에 반영했다. 격리·공유 build의 clean `test-compile` 및 dependency copy가 통과했고 집중 Java 54건 중 53건 통과·정책상 skip 1건, 별도 코드 검토 blocker 0건이었다. 실제 실패 셀의 gzip은 약 20.1 MiB, 최대 RSS 약 4.89 GiB였다. P1은 placement domain·reference identity 삭제 없이 12개 좌표에서 support receipt 22개가 늘었다. 그 support의 전체 합법성은 아직 인증되지 않았고 verifier는 `complete=false`를 유지한다. 수정된 source/class의 v9 전체 matrix에서도 동일 실패 셀이 1,595.783초에 `COMPLETE`였고 gzip SHA-256 `617c37387797a458b0105e937ef8e6c5f94a27af095b998b96432b57e553177d`가 격리 canary와 동일했다. 재현 evidence는 `p-nonconverge-diagnosis/primitive-query-final-v2`에 있다.
- **잔여 버그·잠재 회귀 위험:** 순환을 끊기 위해 staging 또는 proof를 임의로 제거하면 실제 합법 plan이 누락될 수 있다. 반대로 근거 없는 support 합집합은 불법 plan을 추가할 수 있다. 수정 전후 후보·support identity 및 작은 exhaustive fixture의 양방향 차집합으로 감지한다.

## E packed hard-factor 단일 크기 상한으로 인한 v9 capture 오류

- **상태:** v9의 E 모델 4개 `ERROR`를 같은 단일 상한 문제로 확인했고 단일 상한 조정 빌드의 대표 두 canary를 실행 중이다. 새 캡처 완료 전까지 오류를 닫지 않는다.
- **조건:** 대표 `cell_01a983362f94d59ac6f9`, `cell_03e6e6fcbb64b97e54d9`의 동결 E capture는 각각 약 953초·1,017초 뒤 저장 모델의 `acceptance=PARTLY_OPAQUE_JAVA_PREDICATES` 때문에 matrix verifier가 `E model has no complete materialized hard factors`를 반환했다. 같은 오류가 `cell_35a543db6352b7d3fd4d`, `cell_3cccd2fe8c08dc88ad92`에서도 확인됐다.
- **원인:** 첫 두 모델의 4개 hard factor는 19,264,216 또는 21,156,588칸으로 단일 packed factor 12,000,000칸 상한을 초과했다. 기존 packed 44,274,931/44,274,943칸과 네 factor의 80,841,608칸을 합쳐도 전역 140,000,000칸 상한 아래다. 뒤 두 모델은 factor 17,973,304/19,737,864칸 각 2개, packed 42,107,246칸으로 총 117,779,582칸이다. OOM·timeout·실제 E infeasibility가 아니다.
- **조치·검증:** `ExactPlanningModelCapture.java`의 단일 상한만 24,000,000칸으로 늘리고 21,156,588칸 packed truth 및 초과 거부 Java 회귀를 추가했다. 집중 Java 4/4와 clean `test-compile`, dependency copy가 통과했다. `current-pe-e-packed-build-v10` source/class SHA는 각각 `70efd038b7e5d6aeb6591d264b052206704858a845080a9484c943b4bbe28356`, `ca1490dc8705e406452ccf904b4c96e92b37e92862fcd7672ff0a8dcaf4ca9d5`이다. 두 셀을 각기 새 artifact root에서 재캡처 중이다.
- **잔여 위험:** 새 factor의 UNKNOWN status·실행 시간·저장 검증을 실제 결과에서 확인해야 한다. v9 전체 matrix에는 오류가 남고 v10 source binding과 혼합해 PASS로 승격할 수 없다.

## E compositional 물리 image의 Pca 자원 한계

- **상태:** 자원 병목과 독립 코드 검토는 진단 atom image에 한해 해결. canonical Pca 물리 집합·Java 의미 결합은 진행 중이다.
- **환경/조건:** `planning-w1:pca` 셀 `cell_36e09d4c86e4c6acbec2`의 v8 E 모델, 89 native domain·195 hard factor·488 typed 물리 atom·7,720 atom condition term.
- **재현 절차:** `python3 scripts/fedplanner/compositional_e_image.py --model /grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-pe-campaign-v8/e-models/cell_36e09d4c86e4c6acbec2/e-model.json.gz --artifact <출력 경로> --max-nodes 4000000 --max-apply-pairs 16000000`.
- **관측 증상:** 기본 250,000 node/1,000,000 apply 예산은 약 15초·180 MiB에서, 4,000,000 node/16,000,000 apply 예산은 104.81초·최대 RSS 2.07 GiB에서 모두 `DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT`을 반환했다. 후자의 저장 결과는 `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-pe-compositional-e-budget-profile-v3/cell_36e09d4c86e4c6acbec2/result.json.gz`에 있다.
- **원인 분석:** 초기 native-first/atom-last MDD 구성은 실제 Pca w1에서 `ATOM_CONDITION_EQUIVALENCE` 단계의 nonterminal node 250,000개 상한을 소진했다. 4,000,000 node 상한까지 늘려도 중단했다. 사용이 끝난 native 변수의 조기 존재 양화와 reachable-root compaction이 없어서 죽은 중간 node/cache도 계속 예산을 차지했다.
- **해결 방법/의사결정 근거:** E 합법 후보를 자르지 않고, factor와 atom 조건의 전체 의존성을 보존하는 partitioned MDD 순서를 사용해 native 좌표를 마지막 사용 직후 제거한다. root에 닿지 않는 node/cache를 회수한다. 이 계산은 provenance atom image에 대한 진단이며 생산 projector와 독립 의미 연결이 없어서 top-level `BLOCKED`로 유지한다.
- **수정 파일·검증:** `scripts/fedplanner/boolean_mdd_relation.py`, `compositional_e_image.py`와 대응 테스트를 수정했다. 작은 모델의 monolithic MDD와 완전 truth table을 대조했고, tautology 검사의 cell×DNF literal 작업 상한 및 native 변수의 image root 완전 제거 회귀를 추가해 fedplanner Python 전체 201개가 통과했다. 실제 Pca w1은 488 atom 중 220개를 상수화해 동적 268개, provenance atom 집합 17,621,581,824개를 37.80초·최대 RSS 110,284 KiB, peak live node 129,687개에 계산했다. 저장 artifact SHA-256 `91942e4478999fbfe41d3cdfa91b40a3d5ad39e2082adb849837f1272882b73c`의 모델 결속 재검사는 최신 코드에서도 `PASS`였고 큰 모델의 exhaustive replay는 `SKIPPED_EXHAUSTIVE_REPLAY_BUDGET`이다. 별도 검토는 소형 2,000관계·compaction 500건 differential에서 정확성 결함을 발견하지 않았고, 발견한 작업 예산 문제는 수정·회귀했다.
- **잔여 버그·잠재 회귀 위험:** atom 상관관계 소실, source authority 오연결, provenance 중복의 물리 집합 오해가 주요 위험이다. 작은 assignment 전수 projection 비교와 mutation, 고정 SHA를 요구하는 저장 artifact 재검사를 추가했으며 생산 의미 연결과 대형 조건의 압축 성능은 여전히 미해결이다.

## E canonical quotient의 위조 source-root false completion

- **상태:** source binding false completion 결함 수정·회귀 완료. E의 Java 의미 결합과 P/E 비교는 여전히 미완료다.
- **환경/조건:** `compositional_e_image.py`의 저장 atom relation을 `canonical_e_quotient.py`가 action·geometry·presence·precedence 좌표로 양화하는 진단 경로.
- **관측 증상:** 초기 quotient는 source MDD 구조만 검증해, 도달 가능한 root에 native 변수가 남은 위조 source에서도 `DIAGNOSTIC_COMPLETE`를 냈다. 동일 물리 계획 4개인 소형 반례를 6개로 잘못 셀 수 있었다.
- **원인·수정:** source root commitment, 정적/동적 atom dictionary partition, atom token과 변수 사전, count, 도달 가능한 root support를 결속하지 않았다. 이 계약을 모두 fail-closed로 검증하고 좌표 쌍·occurrence 비교 작업에 별도 예산을 적용했다. 위조 native root는 거부되며 소형 중복 fixture는 provenance 4개를 canonical 2개로 양화한다.
- **검증·증거:** 집중 테스트 5/5, fedplanner Python 211/211 통과. Pca w1에서 최신 quotient가 21.00초·최대 RSS 56,660 KiB로 canonical 좌표 집합 17,621,581,824개와 248변수·7,141 node를 계산했다. 저장 artifact `current-pe-canonical-e-v2/cell_36e09d4c86e4c6acbec2/result.json.gz` SHA-256은 `7d14238e7353cd5ce9a565406d4dcd3eca8e282c83ecb3b406b691cdac20dfb0`이고 외부 SHA를 지정한 저장 재검사가 통과했다.
- **잔여 위험:** 이 quotient의 입력인 typed E atom 자체가 Java projector에 독립 결속되지 않았다. 따라서 계산값을 P/E 집합 동등성이나 feasible plan 인증에 사용하지 않고 top-level `BLOCKED`를 유지한다.

## P candidate 행 단위·국소 factor 실험 폐기

- **상태:** 미인증 실험 코드와 테스트를 제거했다. P acceptance의 opaque predicate 7개와 `complete=false`는 그대로다.
- **반례:** P1 원시 DTO의 1,511개 candidate 좌표에서 국소 허용 2,303행을 계산했지만, 독립 검토는 Java 생성 계약과 다른 receipt·placement·proof·binding·canonical 순서의 위조 DTO가 양성 행으로 승격되는 사례를 반복 재현했다. 특히 `candidateDomains` receipt 순서와 대응 candidate index를 함께 바꾼 입력도 통과했다.
- **조치:** 실험용 `p_candidate_feasibility.py`와 전용 테스트를 제거했고 국소 factor digest와 양성 행은 인증 증거에서 폐기했다. 어느 결과도 생산 planner나 P/E gate에 연결하지 않았다. 저장 P1 원시 모델은 `current-pe-s2-activation-v1/p1/cell_38844b94fdcf53069c7c/p-model.json.gz`에 보존한다.
- **남은 의무:** Java DTO 전체 canonical 계약, 여러 행의 동시 선택, relocation·privacy·worker pool·최종 validator 및 예외 경계를 독립적으로 재구성하고 작은 전체 native assignment에서 Java 판정과 전수 대조해야 양성 P factor를 다시 발행할 수 있다.

후속 독립 진단에서 `candidateRuleFactInventory`가 immutable realization의 key만 기록하고 canonical support-clause 전체 목록과 순서를 기록하지 않는다는 점을 확인했다. 반면 `candidateDomains`와 semantic 행은 같은 receipt enumeration에서 나온다. 따라서 receipt·clause·순서를 함께 위조하면 내부 digest가 일치해도 원래 규칙 사실에서 파생됐음을 증명할 수 없다. `latentWdivmmBoundary`, `specialRuntimeAuthority`, `supportAuthorityIndex`, 함수 `callInputPosition`도 생산자가 전달한 의미 입력이다. 저장 P1에는 receipt 2,118개, 0이 아닌 support index 449개, 여러 support를 가진 reference 88개(최대 41개), call/logical position이 다른 함수 authority 59개가 있어 이 문제를 무시할 수 없다. P1의 WDIVMM-special 행은 0개이므로 해당 분기는 다른 fixture가 필요하다. 다음 capture 계약은 immutable realization별 canonical support clauses를 receipt와 독립된 경로로 저장하고, 함수 호출 및 WDIVMM authority를 pre-builder graph 사실에서 재도출한 뒤 Python이 정렬된 candidate domain과 assignment 관계를 재구성해야 한다. 이 전에는 일곱 predicate를 승격하지 않는다. 기존 네 producer 필드 변조 hostile 테스트는 구조 검증만 통과하며 `producerAssessmentFieldsTrusted=false`를 유지한다.

## v11 두 workload의 privacy-filtered support closure 순환

- **상태:** `cell_capture_b9bc7e684fc746b25701`, `cell_capture_5d3251ba160526b829f8`에서 P/E 모두 동일한 `Privacy-filtered candidate proof closure cycled`로 실패한다. v11 matrix는 각 610 `COMPLETE`·2 `ERROR`다. 오류를 infeasible plan으로 해석하지 않는다.
- **재현·근거:** 동결 catalog/evaluation으로 두 셀을 v11/current에서 각각 재현했다. 진단은 `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/privacy-cycle-repro-v11-b9`, `privacy-cycle-repro-v11-5d`, `privacy-cycle-support-subset-b9`에 있다. `NeutralPlacementGraphBuilder.java:930-955`의 privacy outer loop에서 node/domain/logical은 동일하지만 `RUQ1`의 실행 가능한 OR support clause가 2개↔4개로 교대한다. 2개 집합은 4개 집합의 진부분집합이다.
- **반증된 국소 수정:** 실행 불가능한 direct-template staging 행을 비교에서 제외하거나, 완료된 loop-seed exit를 self-memo로 흡수하거나, self-memo key를 entry normalization과 일치시켜도 두 실셀에서 순환했다. 실험 코드는 모두 제거했고 production builder diff는 0이다.
- **필요한 설계:** 후보 proof clause를 AND 의존성·OR 선택의 유한 관계로 정의하고, privacy와 physical transfer의 비단조 부분을 분리해야 한다. 추가 2개 clause를 단순 합집합하면 순환 자기증명이 합법으로 승격될 수 있고 교집합만 취하면 실제 가능한 대안이 사라질 수 있다. 원시 clause의 완전한 universe, base witness, 부정/absence 의존성과 privacy 제거의 층위, least/greatest fixed point 선택을 먼저 명시하고 작은 전수 oracle과 두 실셀의 양방향 support identity 비교로 검증한다.

후속 계측에서 같은 `CandidateReplay`가 반복될 때 append-only `LoopSeedLedger.completedTransfers`의 크기는 첫 셀 `18→20`, 둘째 셀 `27→30`으로 증가했다. 즉 기존 순환 검출은 전이 상태의 일부인 완료 loop-seed memo를 제외해 진행 중인 계산을 순환으로 오판했다. 두 셀의 반복 상태가 같은 시점의 ledger를 포함하도록 판정하는 수정은 P/E capture와 artifact-only 모델 검증을 통과했다. 기존 정상 `cell_01a53b68e59a2fd577ad`도 완료됐고 그 E hard-factor 진리표는 v11과 일치했다. 두 오류 셀의 E 모델은 별도 조기 가지치기 진단과 gzip byte까지 같으며, P 모델의 의미 필드도 같고 `candidatePrivacyClosurePasses` 진단 trace만 2pass 늘었다. 전체 612셀 재실행과 독립 검토 전에는 최종 수정으로 판정하지 않는다.

반면 privacy 반복마다 `removeUngroundedStagingRealizations`를 조기에 적용하는 진단은 두 오류 셀을 통과했지만, v11에서 `COMPLETE`였던 네 셀 `cell_01a53b68e59a2fd577ad`, `cell_0b7dd75a059850834fcd`, `cell_0941416111c19d4009e2`, `cell_017a38c283acc8688ef0`에 새 순환 오류를 만들었다. 해당 코드는 즉시 제거했고 `current-pe-campaign-v12-candidate`의 P/E 각 6 `COMPLETE`·4 `ERROR` 부분 receipt는 실패 증거로만 보존한다. 현재 수정은 clause 집합을 합치거나 삭제하지 않고 순환 상태의 누락된 memo 좌표만 보강한다. memo 증가가 끝난 뒤에도 진짜 순환이 발견되면 위 AND/OR proof-support 설계 의무가 다시 적용된다.
- **최종 재캡처:** production builder가 수정되면 source/class binding이 바뀐다. 이전 610 receipt는 v11 진단으로 보존하되 최종 동일-build 612 matrix에 혼합하지 않는다.
