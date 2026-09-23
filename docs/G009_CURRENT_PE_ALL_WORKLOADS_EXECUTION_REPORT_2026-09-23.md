# G009 현재 P/E 전체 workload 검증 실행 보고서

작성일: 2026-09-23
대상 계획: `.omx/plans/g009-current-pe-all-workloads-20260923.md`
판정: **INCOMPLETE — 전체 workload의 P == E를 아직 인증할 수 없음**

## 검증 명제와 판정 경계

각 동결 조건에서 P와 E가 수용하는 모든 native assignment를 공통 물리 identity로 projection한 **집합**이 같은지 검사한다. accepted proof 수의 일치, 한두 계획의 존재, graph-only 관계 또는 모델 생산자의 영수증은 전체 집합 동등성의 증거가 아니다. 또한 P == E 자체는 독립 runtime 의미론 R에 대한 feasible-plan 완전성 증명이 아니다.

이번 실행은 source-backed 612조건의 입력 분모와 모델 수집 경로를 확정했지만, historical snapshot과 파생 조건의 구분, P/E acceptance, 큰 공간의 정확한 물리 관계 비교가 열려 있다. 아래 제한 범위를 전체 `EQUAL`이나 `PASS`로 승격하지 않았다.

## 분모와 동결 입력

원래 registry의 planning 224조건은 source·input·network를 동결한 P 모델 matrix가 있으며, 저장 artifact만으로 224/224 구조 캡처를 재검사했다. registry 축 전개 후 추가 후보는 중복 제거 기준 388개다. 이 중 324개는 기존 placeholder를 덮어쓰지 않고 별도 catalog row로 승격했다. 나머지 SliceLine 64개는 sealed metadata·topology·parent manifest에서 현재 compile-model 조건을 별도로 파생했다. 최종 검증 분모는 `FROZEN_COHORT_DERIVED_ARGV_612`의 준비된 612개, 미해결 입력 0개다. 이 조건은 원래의 historical snapshot 관측치가 아니므로 campaign은 `FULL_CURRENT`라고 주장하지 않는다.

| 범위 | 조건 | 현재 입력 상태 | 비교 상태 |
|---|---:|---|---|
| planning 기존 cohort | 224 | 동결·캡처 가능 | 전체 P/E 미완료 |
| base/ML10 | 296 | DML, metadata, privacy, partition, network, 정확한 compiler argv 정적 동결 | native capture adapter 연결; 대표 ML10 LM 완료 |
| generated microbench | 28 | 생성 DML과 compile-model 가정 정적 동결 | native capture adapter 연결; 대표 linear-k1 완료 |
| base SliceLine | 32 | sealed stage metadata·topology로 파생한 compile-model 입력 | P/E 대표 캡처 완료, 전체 비교 대기 |
| planning-parent SliceLine | 32 | parent manifest·effective privacy override로 파생한 compile-model 입력 | P/E 대표 캡처 완료, 전체 비교 대기 |
| **합계** | **612** | **612 정적 준비, 0 미해결** | **derived cohort 범위; 전체 P/E 미완료** |

정적 준비는 runtime 데이터나 worker 접속을 검증했다는 뜻이 아니다. 특히 microbench 28조건은 스크립트에 없는 4-worker·4096×64·1 ms·5 Gbit compile-model 가정을 명시해 고정한 범위이다. base SliceLine과 planning remainder의 입력을 임의로 만들거나 대상에서 삭제하지 않았다.

현재 동결 근거: `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/frozen-capture-cohort-derived-argv-v4/catalog.json` 및 같은 디렉터리의 `campaign.json`. Catalog SHA-256은 `7d55c9f469077038f6d1b5a3a6a4ef5887bc55089712c2d74c1738b4812376ac`, campaign SHA-256은 `bfa0ee35e68cdbac1fb31bf1c8e962f92ddbc1633291944d3e26ddca728b5fc2`이다. 세 단계 생성기의 `--check` 재생성이 통과했고, 296개 campaign compiler binding과 64개 SliceLine 파생 binding의 원본 대비 동일성을 검사했다. 이전 `frozen-capture-cohort-v1`·`derived-v2`는 compiler argv가 없었고, `derived-argv-v3`은 P2 campaign 16조건의 필수 JVM 옵션이 누락돼 최종 실행 입력으로 사용하지 않는다. v3→v4의 ID 변경은 그 16조건으로 한정되며 나머지 catalog ID 819개는 유지됐다.

새 한 명령 runner의 고정 frontier·자원 preflight를 실제 v4 입력에 재적용했다. scope는 `FROZEN_COHORT_DERIVED_ARGV_612`, 셀 612개, 미해결 0개였다. 검사 시점 호스트는 48 CPU, 사용 가능 RAM 약 116.7 GiB, artifact 파일 시스템 여유 약 1,030.6 GiB로 P 4개/E 4개 JVM과 P 작업당 2 GiB·E 작업당 8 GiB·기본 20 GiB의 합산 디스크 예약을 통과했다. 이는 실행 전 자원 확인이며 612개 capture 완료 증거는 아니다.
별도 정적 preflight에서 `check_frozen_inputs`를 준비된 612개 모든 셀에 재적용해 612/612 통과했다(13.86초). 이는 DML·metadata·설정의 저장 입력 계약 검사이며 native planner capture나 runtime 실행 결과가 아니다.

## 구현된 검증 경로

- P/E accepted proof별 전체 JSON 대신 물리 dictionary와 proof ordinal/reference를 gzip으로 저장한다. 독립 verifier가 dictionary row의 canonical identity, 모든 reference와 순서·중복, accepted/rejected/raw 합계, E의 저장 factor와 accepted ordinal을 다시 확인한다.
- P acceptance를 규칙별로 조사하고 저장 artifact의 coverage를 명시한다. candidate privacy closure는 67 pass·104,021 rule record를 저장해 Python이 원시 사실에서 출력 필터링을 재생하고, 실제 artifact의 출력 변조를 재계산된 digest와 함께 주입해 검출했다. 원시 사실의 독립 provenance와 다른 6개 predicate는 열려 있어 `INDEPENDENT_FULL_ACCEPTANCE_VERIFIED` 판정은 금지한다.
- 추가한 candidate/relocation 원시 DTO는 candidate 좌표별 realization reference, support clause, compiled/logical input source link와 relocation demand·action identity를 보존한다. Python 검증기는 이 연결을 Java의 판정 영수증 대신 원시 필드에서 재구성한다. 실제 동결 P1 artifact에서 candidate receipt 2,118개, realization reference 1,669개, relocation demand 192개·choice receipt 204개·graph action 188개를 재검사했고 unsupported source link는 0개였다. assignment별 feasible variant·worker pool 판정이 남아 있으므로 전체 acceptance 주장은 계속 금지한다.

이 P1 raw DTO artifact는 `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-pe-s2-raw-dto-v1/p-model.json.gz`에 영구 복사했다. gzip SHA-256 `f138565e6388d58525a68ca25cfd3097fc1b667ad1b57f9cc807189669d16c70`, 해독 JSON SHA-256 `bd57a3f0f8d61235cbd060c4f5cbdfb3edd0ab8bd37b24929d3ff725f913e9d7`이며 같은 폴더의 `verification.json`을 artifact-only 재생성했다. 결과는 `STRUCTURE_VERIFIED`, acceptance `complete=false`, opaque predicate 6개다. 실제 source-owner 변조는 독립 검증기가 거부했다. 작은 direct-source assignment 전수 helper는 복잡 함수·derived·worker-pool을 지원하지 않을 때 명시적으로 미지원으로 남기며 assessed acceptance로 승격하지 않는다.
- `exact_e_factor_count.py`는 E의 저장 hard factor에서 정수 semiring 변수 제거로 ACCEPT/REJECT/UNKNOWN을 정확히 계수한다. 이 계산은 물리 projection이나 P/E equality를 판정하지 않는다.
- `run_current_pe_matrix.py`는 campaign과 P/E matrix의 전체 셀 binding, 자원 상한, 병렬 실행·재개, 별도 offline 재검사, 결과별 `EQUAL`/`CAPTURED_EQUAL`/`DIFFERENT`/`INCOMPLETE`/`ERROR`를 지원한다. 이미 검증한 P/E 모델을 물리 비교에서 다시 Java로 캡처하지 않고 원본·실행 사본의 해시를 재검사해 재사용한다. E raw 제품이 정확 열거 예산을 넘는 셀은 대형 모델을 다시 메모리에 로드하기 전에 `INCOMPLETE`로 기록한다. 필수 미완료 범위가 있으면 exit 0을 내지 않는다.
- P matrix capture/verify에는 정확한 frozen-ready scope를 추가했다. 최종 612조건 파생 cohort를 모두 선택하고, 그 scope를 historical `FULL_CURRENT` 인증과 구분한다.
- 기존 `run_plan_space_comparison.sh`는 P matrix import와 unresolved 조건을 구분하도록 보강했다.
- 한 명령 게이트의 별도 코드 검토에서 현재 물리 실행이 실패했는데 과거 `DIFFERENT` 요약을 읽는 경로와 P artifact verifier 실패를 `DIFFERENT` 분기에서 가리는 경로를 발견해 고쳤다. 물리 단계가 이번 호출의 최종 status/counts와 exit code를 함께 남겨야 요약을 수용하고, P 저장 모델의 재검사 성공을 `EQUAL`·`DIFFERENT` 모두의 필수 조건으로 둔다. runner 코드 변경 중 영수증 발행도 거부한다. 동시 디스크 예약에는 P 작업량도 포함했다.
- P/E 모델 matrix의 재개 경로를 별도 검토·수정했다. 읽을 수 없는 영수증, 오래된 binding, 손상된 `COMPLETE`, 잘못된 schema/status는 기존 bytes를 고유 `prior-capture-*`에 보존하고 canonical `ERROR`를 발행한다. 보통 재개는 이를 다시 캡처하지 않으며 명시적 `--recapture-invalid`에서만 격리 후 재시도한다. 기존 producer 실패만 자동 재시도한다. 손상 영수증의 matrix 요약과 artifact verifier가 함께 `INCOMPLETE`가 되는 테스트를 추가했다. 독립 재검토에서 HIGH/MEDIUM 잔여 문제 0건이었고, v7 P 완료 영수증 70개의 새 필드 계약 호환성을 확인했다.

## 실제 실행 결과

| 검증 | 결과 | 증거의 한계 |
|---|---|---|
| Python 단위·mutation 회귀 | 최신 통합 160개 통과 | 모델 전체 적용을 대신하지 않음 |
| planning P matrix 저장 artifact 재검사 | 224/224 PASS | native P acceptance 전체 인증 아님 |
| LM 한 조건의 기존 JSON ↔ compact | P/E 양방향 물리 집합 일치 | pilot 한 조건 |
| Pca w5/wan_heavy compact pilot | P accepted proof 377,856 → 물리 identity 600; E accepted proof 479,232 → 물리 identity 600; **P-only 0, E-only 0** | 해당 captured model 비교이며 P acceptance 전체 인증 아님 |
| v5 Pca w5/wan_heavy 별도 artifact-only 재검사 | `PASS`, P/E 물리 identity 각 600·양방향 차집합 0; 생산 실행 4,356.49초, 별도 재검사 274.29초 | `CAPTURED_NATIVE_PHYSICAL_SET_EQUALITY`; P acceptance `PRODUCER_RECEIPT_ONLY` |
| 같은 Pca의 기존 전체 JSON ↔ compact P/E | P 8 shard의 377,856행과 E 479,232행 모두 양방향 row 집합 일치; P/E compact dictionary 각각 600 | 같은 물리 serializer의 byte-for-byte 비교; 최종 source binding 재캡처 아님 |
| 폐기된 v3 빌드 planning LM w3/LAN compact pilot | P accepted 72→물리 60, E accepted 96→물리 60, 양방향 차집합 0; 별도 offline 재검사 PASS | loop seed 완전성 결함이 발견된 빌드이므로 진단 자료. P acceptance는 `PRODUCER_RECEIPT_ONLY` |
| 최대 logreg packed E factor 스트레스 | 753 packed factor·129,593,346칸, 39분 52초에 `COMPLETE`, opaque 0; 독립 Python 해독·해시·상태 계수 통과 | ALLOW 63,837,183·REJECT 65,756,163·UNKNOWN 0; artifact 1.31GB, 별도 최종 빌드 아님 |
| Pca 16조건 E factor capture/count | 16/16 모델 COMPLETE, exact count run/verify 통과 | accepted cardinality만 확인 |
| P2 w3/LAN E factor count | accepted 10,926,161,920; unknown 0; 재검사 통과 | 물리 집합 비교 없음 |
| P1 w3/LAN E capture/count | 1,519 domains, 3,420 factors, accepted 514,946,561,978,906,198,409,216,000; unknown 0 | 물리 집합 비교 없음 |
| P candidate privacy artifact replay | 67 pass·104,021 rule record, 실제 artifact 재생·출력 변조 검출 | primitive provenance와 나머지 6개 acceptance predicate 미완료 |
| 첫 frozen-ready 대표 native capture | base Pca w1, ML10 LM w1, microbench linear-k1의 P/E 모델 COMPLETE; E opaque 0 | base/ML10의 동결 compiler argv 미적용이 뒤늦게 발견돼 해당 두 결과는 유효한 동결 조건 증거에서 제외; microbench도 전체 548개를 대표하지 않음 |
| E factorized relation | Pca pilot·P1·P2의 저장 native acceptance 관계를 artifact-only 재생·계수 | compositional physical projection 부재로 물리 집합 비교 불가 |
| planning E model 광범위 프로파일(수정 전) | 232행 중 COMPLETE 144, ERROR 88 | 이후 48개 대표 오류 경로 수정; 새 612조건 전체 실행으로 정확한 잔여 수 집계 중 |

Pca w3·w5·w7의 12조건은 각각 E raw 2,057,529,600, accepted 479,232이다. Pca w1의 4조건은 각각 E raw 136,784,460,355,574,169,600,000, accepted 19,688,231,634,739,200이며 P placement 조합은 1,327,104개다. w1과 P1/P2는 native leaf 전체 열거를 피하는 상관 관계 보존형 물리 projection이 필요하다. Pca 모델/계수 근거는 `current-pe-pca-factor-profile-v1` 아래 셀별 `e-model.json.gz`와 `e-factor-count.json`이다.

Pca pilot의 차집합·factor 재검사 receipt는 `current-pe-pca-compact-v1/cell_82438f671fc60acb9bb7/pilot-differential.json`에 있다. 집합 계산은 각 P shard와 E dictionary/reference를 따로 읽고, E 저장 factor의 accepted ordinal 전체와 reference를 다시 대조했다. 이 결과의 source/class binding은 기존 compact build에 속한다. 최종 코드 변경분에 대해서는 재캡처가 필요하다.

v5 Pca pilot은 변경된 compact build `4cd139e044a49674be2b096205ec9869a5a2846fc0d4273810557965f8b21741`에서 다시 실행했다. 증거는 `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-pe-pca-v5-pilot/cell_82438f671fc60acb9bb7/56d5767ab634c04914d2`에 있고 certificate SHA-256은 `a61c8188d9e9d08dd75848c5197cc8ba918652a7c1b97ab347926a816e7fb492`, 별도 검증 summary SHA-256은 `4e703c38a8461ea609b01a56c09b9c3d95418ba004ad5df6257464a9a0ca8341`이다. P raw `79488872607309676165173368304030449664000`에서 377,856 accepted proof와 물리 600개, E raw 2,057,529,600에서 479,232 accepted proof와 물리 600개를 확인했다. 양쪽 `UNKNOWN=0`이고 별도 프로세스의 artifact-only `verify`도 exit 0이었다. 이는 저장된 두 native capture의 물리 집합 일치이며 P acceptance 규칙의 독립 완전성까지 증명하지는 않는다.

기존 Pca P 전체 JSON 8 shard 및 E 전체 JSON 1 shard와 compact dictionary/reference를 `compare_legacy_compact_shards.py`로 재대조했다. 저장 receipt의 원시 입력·count·gzip SHA를 맞춘 뒤 compact reference를 별도 verifier로 재검사하고, P 377,856행과 E 479,232행의 완전한 canonical wire byte를 각 dictionary 600행과 양방향 비교했다. 9/9 shard와 요약이 `EQUAL`이며, resume용 receipt는 `current-pe-pca-shard-differential-fast-v1`에 있다. 이 조건의 전체 JSON 디렉터리는 9.5 GiB, compact 디렉터리는 20 MiB다(`du -sh` 기준). 이 검사는 저장 형식 변환의 동일성을 확인하며 P acceptance 전체를 인증하지 않는다.

E의 선택적 connected-component 열거 경로도 Pca 조건에서 독립 재검사했다. 기존 경로와 raw 2,057,529,600, accepted 479,232, 물리 600개, ordinal SHA-256 `77c8e8542262e68ffe2192a0822088ca03ee2edbf0a0a493b7e0daa33e93395f`가 일치했지만 2,082초가 걸려 실질적 속도 개선은 없었다. 기본 경로로 승격하지 않았다. 증거는 `current-pe-pca-component-e-v1/cell_414a04f2666bce0cbe8e`에 있다.

`exact_e_physical_relation.py`는 Pca pilot·P1·P2 저장 모델의 E hard factor와 alternative payload, cross-variable decode scope를 압축 관계로 기록하고 저장 artifact를 독립 재생한다. 초기 모델에서는 compositional decoder가 없어 `BLOCKED_NONCOMPOSITIONAL`이었다. 이후 typed fragment가 추가되어 실제 P2·GMM 모델은 `DECODER_STRUCTURAL_ONLY`까지 도달했으나, Java projector의 모든 물리 좌표와 fragment의 독립 의미 연결 및 정확한 물리 image 존재 양화가 없어 top-level `BLOCKED`를 유지한다. `UNKNOWN` native assignment도 `INCOMPLETE_UNKNOWN`으로 차단한다. 이전 relation artifact의 `DECODER_VERIFIED`/`COMPLETE` 주장은 최신 검증기로 재계산하면 거부된다.

첫 전체 재실행용 `current-pe-final-build-v1`은 검토 중 296조건 compiler argv 누락이 발견돼 폐기했다. 이 snapshot의 548조건 P matrix 실행을 중단했고, 여기서 생성된 promoted 결과는 유효한 조건 증거로 사용하지 않는다. Java/Python compiler 적용·동결 입력 검사를 보강해 `derived-argv-v3` catalog와 `current-pe-final-build-v2`를 만들었으나, 전체 진단 중 P2 campaign 16조건의 필수 `-Dsysds.privacy.allowPublicRecodeMetadata=true`가 freeze 단계에서 빠진 것이 확인됐다. v2 빌드·v3 catalog의 P/E matrix를 각각 264개·284개 영수증 생성 후 중단했고 이 증거는 오류 진단용으로만 사용한다. 옵션을 원본 protocol에서 condition hash까지 전달한 v4에서 P2 w3/wan_heavy 교체 셀 `cell_capture_8bd2d3b6853662c2410e`의 P/E 캡처와 별도 P/E 저장 artifact 재검사가 모두 통과했다. 최종 612조건 전체 matrix는 수정된 새 source/class snapshot에서 재실행해야 한다.

그 뒤 `current-pe-final-build-v3`에서 P/E matrix를 시작해 P 64개 COMPLETE, E 57개 COMPLETE·7개 provenance ERROR까지 수집했으나 독립 검토에서 loop seed 재시도 누락을 발견해 즉시 중단했다. 같은 direct source revision의 첫 exact replay가 실패하면 나중에 transitive candidate 사실이 충족되어도 `seen` 기록 때문에 재시도가 억제되어 합법 placement가 빠질 수 있다. 따라서 v3의 matrix와 LM 물리 집합 pilot은 성능·오류 진단 자료일 뿐 최종 완전성 인증 증거가 아니다. 완전 proof-state revision을 쓰는 첫 후속 수정은 reuse-k4/Pca/P1 실제 P 캡처를 통과했지만 L2SVM에서 75개 relocation action의 obligation이 왕복했다. 이를 진입 revision A에서 완료 replay B로의 검증된 전이를 메모하고, 현재 action authority의 정확한 key identity로 재결합하도록 고쳤다. L2SVM 단독 캡처 27.22초 `PASS`, 영구 L2SVM 회귀 1/1 및 기존 loop/fixed-point 13/13이 통과했고 독립 정적 검토도 soundness blocker를 찾지 못했다. 최종 빌드 동결은 뒤따르는 인라인 입력 증거 보강과 전체 통합 검증 후에 수행한다.

그 보강을 합친 clean Maven `test-compile` 및 dependency copy가 통과했다. 관련 Java 테스트 14개 클래스의 76개 test case는 실패·오류 0개였고 조건부 skip 6개를 기록했다. `current-pe-final-build-v4`를 불변 복사로 동결했으며 source SHA-256 `5972a153d29583bff78691c6b246088b4aa7e57e47e3b051566b6a1e532e9ebc`, classpath SHA-256 `fff7bfc29d41f7bb992d1bbb9e52e28d790bf1e20ffdb9cfebfcd1cef6ca3341`이다. 이 빌드로 612개 P/E 모델 matrix의 진단 실행을 시작했다. S4 typed 물리 projection이 아직 미구현이므로 v4를 전체 equality 인증용 최종 빌드로 간주하지 않는다.

v4 초기 수집에서 동결 GLM `cell_0334bb17c81dd2ab34e5`의 P 캡처가 약 305초 후 `NO_CAPTURE`로 끝났다. 새 raw DTO의 `candidateSemanticRow`가 receipt마다 전체 nested realization 서명을 문자열로 반복 구성하며 4 GiB Java heap을 소진했다. 이는 feasible plan이 없다는 결과가 아니라 캡처 직렬화 실패이며, 해당 셀과 v4 실행은 전체 P/E 동등성 증거가 아니다. 오류 양상을 수집한 뒤 v4 진단을 중단했다. 중단 전 고정 영수증은 P `COMPLETE` 49·`NO_CAPTURE` 12, E `COMPLETE` 57이며 `current-pe-campaign-v4/diagnostic-v4-interruption.json`에 셀별 상태를 저장했다. 12개 P 실패의 Java stderr에는 모두 `OutOfMemoryError`가 포함된다. 따라서 이 중단은 정확한 물리 차이를 뜻하지 않는다.

P raw DTO를 bounded realization 사실로 바꾸고 큰 모델의 해시·gzip 출력을 streaming으로 전환한 분리 빌드에서 같은 GLM 셀의 P 모델이 404.793초에 `COMPLETE`로 끝났다. gzip artifact는 8,689,110바이트, gzip SHA-256은 `faf6ea696c3cf7e7e413bf6433243bc434d32f6941d02efb99da8b0e72cdec41`이다. 저장 artifact만으로 다시 실행한 `verify_p_model_artifact.py`는 `STRUCTURE_VERIFIED`를 반환했다. 새 스키마의 P1 대표 셀도 24.366초에 `COMPLETE`, Python·Java offline `STRUCTURE_VERIFIED`였다. 두 결과의 acceptance `complete=false`와 opaque 6개는 유지했다. 이 결과도 P acceptance 전체 독립 검증이나 P/E equality는 아니다.

P/E 통합 소스에서 clean Maven `test-compile`·dependency copy가 통과했다. 관련 Java 15클래스의 82 test case는 실패·오류 0, 조건부 skip 6이었다. Python 회귀 132개와 `py_compile`, `git diff --check`도 통과했다. 이후 `current-pe-final-build-v5`를 불변 동결했다. source SHA-256은 `4cd139e044a49674be2b096205ec9869a5a2846fc0d4273810557965f8b21741`, classpath SHA-256은 `9f2fd1662e5a1dc006f210e3f3509e361be92b64fe52b14555645c074e80d05b`이다. 이 빌드의 612개 P/E 모델 matrix 수집을 `current-pe-campaign-v5`에서 시작했다. 현재 코드는 E matrix 독립 검증 영수증을 정확한 manifest·catalog·evaluation·runner digest에 결속하고, P/E import가 현재 verifier/runner code digest를 요구한다. 영수증·manifest 변조 회귀와 Python 집중 23개가 통과했다.

v5 첫 전체 진단은 E typed projection에서 `E native layout lacks structural worker authority`가 5개 셀에 반복돼 중단했다. 중단 전 P `COMPLETE` 10, E `COMPLETE` 5·`ERROR` 5의 셀별 영수증을 `current-pe-campaign-v5/diagnostic-v5-interruption.json`에 보존했다. 이는 E native plan이 없거나 P/E가 다른 경우라는 판정이 아니라 새 typed projection의 캡처 오류다. 오류를 단일 실제 셀로 재현·수정한 다음 새 source/class snapshot에서 다시 시작해야 한다.

오류 원인은 합법적인 dynamic `NATIVE_LINEAGE` 대안이 exact partition range 없이 endpoint/FType의 worker residency witness를 갖는데, typed projector가 모든 대안에 exact anchor를 요구한 것이었다. 수정은 exact anchor와 range가 있으면 그대로 사용하고, dynamic native이면 `workerResidency={ftype,endpoints,layoutExact:false}`를 내보내며 range를 만들지 않는다. residency 자체가 없으면 계속 오류로 닫는다. 동결 실패 셀 `cell_00d1aa1ca27bce14d826`의 E 캡처는 3.326초에 `COMPLETE`, offline matrix 검증 `PASS`였고, artifact-only relation 재검사는 dynamic residency 28개, native `UNKNOWN=0`을 확인했다. 이 relation의 물리 image는 여전히 `BLOCKED_EXHAUSTIVE_LIMIT_NO_SYMBOLIC_IMAGE`다.

수정 통합 후 clean Maven 빌드·dependency copy가 통과했고 Java 15클래스 83 test case는 실패·오류 0, skip 6이었다. 당시 Python 전체 140개와 `py_compile`, `git diff --check`도 통과했다. `current-pe-final-build-v6`의 source SHA-256은 `895795f38ae23c4ef11604270e9fd085f37d4fc705ad1e1764b061d0388a4aed`, classpath SHA-256은 `557e518a91a91142cb6baf2bf81795f63990d42b1b9414fc2c5d8a8b7314c877`이다. `current-pe-campaign-v6`는 독립 검토에서 P/E 동적 native 물리 identity 불일치와 E relation의 잘못된 완료 승격 경로를 확인한 즉시 중단했다. 중단 전 P 14개·E 14개 `COMPLETE`, 캡처 오류 0이었으며 셀별 상태는 `current-pe-campaign-v6/diagnostic-v6-interruption.json`에 보존했다. v6는 전체 인증 결과가 아니다.

v7에서는 동적 `NATIVE_LINEAGE`의 exact anchor가 없는 합법적인 경우 P도 E와 같은 canonical `workerResidency={ftype,endpoints,layoutExact:false}`를 발행한다. 작은 동적 native fixture의 P/E 물리 집합 전체가 일치했고, endpoint 정규화·범위 미발명·witness 제거 시 거부를 검사했다. 관련 Java 17클래스 88개 test case가 실패·오류 0, skip 6으로 통과했고 Python 143개 회귀 및 `git diff --check`도 통과했다. 불변 `current-pe-final-build-v7`의 source SHA-256은 `b36dcb48614ee20227c0818e24711e19afa0bbe37522ae62254953dc66d24bbd`, classpath SHA-256은 `37798b38a77e1a31674d87dfd0b3b7b3c4350f0475c1e29ae91f8533f215b3de`다. 이 빌드의 P 3개/E 3개 병렬 612조건 모델 campaign은 오류를 진단한 뒤 중단했다. 완료 영수증은 P 70개·E 61개, 오류는 P 1개·E 3개이며 셀별 결과와 중단 당시 작업 6개를 `current-pe-campaign-v7/diagnostic-v7-interruption.json`에 보존했다. E 3건은 logreg CFG input authority 중복, P 1건은 `Candidate-specific direct realization closure did not converge`이다. 이는 전체 612개 또는 물리 집합 비교의 판정이 아니다.

E 오류의 직접 원인은 `cfg-definition`의 ValueVersionKey를 TRead alias와 실제 TWrite가 공유하는데 projector가 둘 다 정의자로 취급한 것이다. 실제 TWrite/function-output DataOp만 정의자로 인정하는 수정에서 실패 셀 `cell_01a983362f94d59ac6f9`의 E 캡처가 `COMPLETE`로 끝났고, 저장된 855,500,079바이트 gzip 모델을 별도 Python 파서로 재해독해 domain 518개·factor 1,506개·원문 SHA-256 `dd9533d808215dd873148628350b24f1866d9f4491ac4c796be13768de53e381`을 확인했다. 이는 수정 경로의 단일 canary이며 새 불변 빌드에서의 전체 재실행 및 native accepted 집합 계수는 아직 아니다. 같은 중복 가능성이 있는 P 물리 projector도 필터를 맞췄고 기존 loop fixture를 포함한 focused Java 테스트가 통과했다. 그 작은 fixture 자체는 실제 TRead/TWrite alias를 만들지 않았으므로 이 경로의 실물 회귀 근거로 과장하지 않는다.

P 비수렴 실패 셀 `cell_2371107ddd5d6a7508e3`의 추가 조사에서 direct-source index가 boxed `Integer`의 참조 동일성으로 count를 비교해 128번 중복된 executable fact의 완전 삭제 후에도 count 0인 key를 남기는 결함을 재현했다. 값 비교로 고치고 128-count 삭제 회귀 3/3을 통과했다. 이 수정이 실제 셀의 비수렴도 제거하는지는 격리 canary를 계속 실행 중이므로 아직 단정하지 않는다. Java 집중 4개 클래스 27 tests는 최초 새 fixture의 alias 존재 가정이 틀려 2개가 실패했고, 그 가정을 제거한 후 재실행에서 실패·오류 0이었다. CFG 실제 alias의 재현 근거는 위 동결 logreg canary다.

이 Java 변경과 P activation DTO를 포함한 후보 불변 빌드 `current-pe-candidate-build-v8`을 동결했다. source SHA-256 `4e6556fb14dcbbaa3a625dbce527fa3c80b9a88e51babc360ed62b8e39bae3c4`, class SHA-256 `29cf4d9a8de6e617b6c404021ee35a4d99ac943ccf8da2723d559696c2ed8c22`이다. 다른 logreg E 실패 셀의 matrix capture·offline 검증을 이 빌드로 다시 수행했다. 가용 RAM 약 112 GiB·디스크 약 947 GiB를 확인하고 같은 빌드의 612조건 `current-pe-campaign-v8`을 P 6개/E 6개·전역 최대 12 JVM으로 시작했다. P 실제 canary 결과에 따라 최종 실행 빌드 여부를 결정하며, 현재 후보를 전체 인증 결과로 취급하지 않는다.

해당 v8 logreg E canary `cell_03e6e6fcbb64b97e54d9`는 `COMPLETE`로 끝났고 분리된 matrix artifact-only 검증도 1/1 `PASS`였다. 영수증과 모델은 `e-logreg-candidate-v8-canary/cell_03e6e6fcbb64b97e54d9`에 있으며 모델 gzip SHA-256은 `4ff84f3e5363e7358f38eb3da21d546cd77235569b3bd10314814f5a08c8c26b`이다. 이는 이전 alias 관련 E 실패의 한 동결 셀 재검증이지 전체 E 완료 판정은 아니다. 같은 시점 v8 전체 campaign 영수증은 P 44개·E 42개 `COMPLETE`, 오류 0개로 진행 중이었다.

새 P candidate activation DTO를 담은 P1 단독 저장 artifact `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-pe-s2-activation-v1/p1/cell_38844b94fdcf53069c7c/p-model.json.gz`는 gzip SHA-256 `9d45a76776e71f41c148ec922680906b5f50c9ee6a82ff84f8b2669fdc04287d`, 원문 SHA-256 `3d536f627316a129ff288f1695900a25ad4c117fcf5b88b09f3dbe2ebd3ff68f`이다. Java/Python 저장 artifact 검사는 둘 다 `STRUCTURE_VERIFIED`였다. 이 DTO에는 candidate activation 2,118행과 함수/임시 변수 경계 authority index가 있으나 Python이 아직 전체 assignment별 acceptance를 재구성하지 못해 `complete=false`, opaque predicate 6개를 유지한다. 이 artifact는 후보 빌드에 포함된 capture 구현과 해당 파일 bytes가 같지만 전체 v8 tree freeze 전에 생성됐으므로 v8의 전체 tree binding으로 주장하지 않는다.

후속 Python verifier는 같은 P1 artifact의 activation 2,118행, support authority 1,669행, logical authority 343행, relocation worker pool 188행의 원시 reference·입력·FOUT·action·pool 연결을 독립 점검했다. 생산자의 verdict·interpretation·unsupported-reason 필드는 신뢰하지 않으며 ref/count/FOUT/action/pool 변조 테스트를 추가했다. 실제 저장물 재검사와 전체 fedplanner Python 159개 테스트가 통과했지만 assignment별 feasible variant/relocation/validator 판정은 아직 재구성되지 않아 `complete=false`·opaque 6개가 그대로다.

기존 E 비교 어댑터의 인라인 함수 출처 추정 때문에 실패하던 동결 GLM과 GMM 대표 셀은 compiler-owned 입력 constraint를 이용하는 수정 후 분리 빌드에서 모두 E 캡처 `COMPLETE`와 offline 저장 artifact 검증 `PASS`를 얻었다. 한편 최대 logreg 셀의 packed E factor 저장은 실제 1.31GB여서 전체 병렬 실행기에는 동시 작업 수에 비례한 디스크 예약과 `RESOURCE_LIMIT` 상태를 추가했다. `run_current_pe_campaign.py`는 612개 고정 분모에서 P/E 모델 캡처를 병렬 실행하고, 모두 완료됐을 때만 전체 물리 비교를 시작하며, 별도 artifact 재검사 명령을 제공한다. 독립 acceptance/projection 의무는 아직 남아 있다.

최종 nullable provenance 패치는 모든 함수 입력 boundary에 명시적 `Optional` 결과를 요구하고, 컴파일러가 제거한 입력만 empty로 처리하며 증거가 빠진 잔존 입력은 오류로 닫는다. 같은 source hash의 격리 빌드에서 GMM `cell_037f9be6beb760f76d7d`는 9.446초, GLM `cell_0334bb17c81dd2ab34e5`는 341.462초에 각각 E 모델 `COMPLETE`와 offline `PASS`였다. GLM의 긴 실행은 privacy evidence canonicalization에서 CPU를 사용한 뒤 native support 단계로 전진했으며 대기 교착이나 반복 예외가 아니었다. 최종 통합 v4 빌드에서도 이 셀들을 612개 matrix 안에서 다시 수집한다.

P1 capture에서 CFG 함수 출력의 call ordinal 혼동과 IPA가 지운 인라인 함수 정의를 잔존 compiler boundary로 재결합하는 오류를 수정했다. 남는 함수 정의가 여럿이면 계속 실패로 처리한다. 실제 동결 P1 E 모델 캡처는 완료됐다.

v2 진단에서는 `FROZEN_FEDERATED` literal 뒤의 정상적인 세미콜론을 입력 검사 정규식이 거부하는 결함과 `reuse_update-k4`의 loop seed가 매 closure 호출마다 초기화되어 133↔135 후보 행을 반복하는 결함도 발견했다. 전자는 세미콜론을 허용하되 원본 범위·주소 변조 거부 테스트를 유지했고, 후자는 정확한 reaching-source revision별로 seed를 한 번만 허용하도록 수정했다. 함수 경계가 나중에 넓어지는 ALS 회귀를 포함한 Java 테스트 10개와 실제 `reuse-k4` P/E 캡처·E 저장 artifact 재검사가 통과했다. E 모델의 대형 JSON을 전체 `byte[]`로 한 번 더 복제하며 발생한 OOM은 압축 전 SHA·크기를 계산하는 스트리밍 직렬화로 고쳤고, 원자적 교체·해시·크기 테스트 2개와 실제 E 캡처가 통과했다. 이 세 수정은 새 source/class snapshot에 포함되어야 한다.

P privacy 증거의 영구 보관 복사본은 `current-pe-acceptance-evidence-v1/p-model.json.gz`이다. 후보 상태/privacy 결과의 독립 재생은 통과했으나, `effectivePrivacy`·source/capability 사실 자체를 별도 HOP 그래프에서 재구성하는 증거는 아니다. 새 P1 원시 DTO 재검사는 candidate support/source 연결과 relocation action/obligation의 구조적 신원을 닫았지만 assignment별 가능성 전체는 아직 닫지 않았다. 남은 opaque predicate는 candidate feasible variants/source reachability, candidate selection/realization compatibility, relocation active demand, relocation selection/worker pool, candidate-relocation alignment, Java validator exception classification의 6개다. 이 6개를 제거하는 것만으로도 부족하다. candidate·relocation·non-decision-owner coordinate universe 3종의 완전한 assignment 판정과 P1 non-decision-endpoint graph constraint 7개의 범위 판정도 필요하다. 독립 verifier는 `complete=false`를 유지한다.

별도 설계 검토에서 기존 P artifact에는 전체 candidate realization/support 관계, compiled·logical input edge, relocation의 `directSourcePlacements`, privacy owner와 operation facts가 없음을 확인했다. 이 중 candidate·relocation의 구조적 원시 사실은 새 DTO로 채워 실제 P1 재검사까지 통과했다. 생산자가 계산한 허용 truth table을 저장하는 방식은 독립성을 제공하지 않는다. 완전한 P acceptance에는 Python이 candidate·relocation·boundary universe와 각 assignment 판정을 독립 재구성한 뒤 accepted ordinal 전체 집합을 양방향 대조해야 한다. 일부 non-decision constraint는 함수 boundary를 만드는 기록이므로 무조건 binary 제약으로 적용하면 오히려 틀린다. 남은 assignment 의미론과 다른 opaque 규칙 때문에 현재 보유한 artifact만으로 `INDEPENDENT_FULL_ACCEPTANCE_VERIFIED`를 만들 수 없다.

Java acceptance 경로의 별도 정적 감사에서는 특히 미선택 owner의 `AVAILABLE` realization reference가 선택된 support에 존재적으로 기여할 수 있음을 확인했다. 현재 Python의 작은 direct-source helper는 이를 일반적으로 재현하지 못하므로 assessed predicate가 아니다. 함수 boundary에는 source/target ID만 아니라 가능한 state·pool·exact-layout 옵션이, relocation privacy에는 source value-version에 속한 compiled occurrence 전체의 effective privacy가 필요하다. WDIVMM·formal chain·DIRECT 특수 경로와 worker pool의 partition axis/endpoint 비교도 별도 원시 사실이 필요하다. Java가 검사에서 건너뛰는 non-decision graph constraint를 Python이 일괄 강제하면 오히려 P 집합을 부당하게 줄인다.

E의 압축 물리 projection도 producer가 추가 좌표 표만 내보내는 것으로는 닫히지 않는다. 필요한 최소 계약은 occurrence/value-version·ordered input·logical input의 구조화된 정적 catalog, domain alternative별 typed fragment, consumer·producer 선택에 의존하는 binding table, PHI arm별 관계, 공유 action/geometry의 OR 조립, accepted assignment에서 decoder totality를 검사하는 독립 verifier다. 그 다음 두 측의 정확한 존재 양화 물리 관계를 만들고 `P∧¬E`, `E∧¬P`의 emptiness와 witness를 재검사해야 한다. P compact 경로 역시 accepted proof를 하나씩 projection하므로 P1/P2에는 P 측 symbolic projection이 별도로 필요하다.

S4의 첫 구현은 E alternative의 typed fragment·binding template와 Python의 deterministic decoder까지 도달했다. 작은 B-01/B-02/B-21의 모든 accepted assignment에서 기존 Java projector와 대조했고 실제 GMM·GLM typed E 모델도 캡처했다. GLM E 모델은 367.994초에 `COMPLETE`, offline matrix 검증 `PASS`였다. 큰 모델에서 native 변수를 존재 양화한 **물리 image relation과 P/E 차집합은 아직 없다**. 초기 relation serializer가 decoder 성공을 `CANONICAL_PHYSICAL_PLAN_RELATION/COMPLETE`로 표시한 과장 경로를 발견해 수정했다. 후속 독립 검토는 typed fragment의 opcode나 source lineage가 원본 의미와 달라도 완료로 승격될 수 있는 경로를 찾아, 원본 operation/state/source identity 검사를 추가하고 전체 의미 연결 전까지 항상 `BLOCKED`로 유지하도록 보강했다. 실제 GMM·P2 모델의 최신 결과는 `nativeRelationStatus=COMPLETE`, `physicalProjectionStatus=DECODER_STRUCTURAL_ONLY`, `physicalImageStatus=BLOCKED_EXHAUSTIVE_LIMIT_AND_SEMANTIC_BINDING`이다. GLM의 native factor elimination은 1M·5M bag 제한을 모두 초과해 명시적으로 중단했고 완성 relation artifact를 발행하지 않았다. 작은 raw 공간의 bounded factor replay만으로도 전체 물리 의미 검증은 성립하지 않는다.

별도 E symbolic 진단은 동결 셀 `cell_0b7e2829a1e84335c6ae`에서 저장 hard factor 기준 raw native 71,680개를 검사해 definite accepted 96개, 그 typed decoder의 unique output 60개, UNKNOWN 0개를 기록했다. artifact `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-pe-symbolic-e-diagnostic-v1/cell_0b7e2829a1e84335c6ae/result.json`의 gzip SHA-256은 `b4e15d5383c6beb943574b05eb26940a3e151b92750ff9404ed904fd7bcbbbda`이며, 실행 당시 스크립트 사본으로 별도 artifact-only 검증이 `PASS`였다(봉인 envelope SHA-256 `76c98e7c5f980fbac83ac71a513d02917f56725dd41e14056ece165348757620`). 이는 약 702초·최대 RSS 1.64 GiB의 **진단** 결과다. 출력의 top-level `status=BLOCKED`, `semanticBindingStatus=BLOCKED_INDEPENDENT_SEMANTIC_BINDING`을 그대로 유지하며 Java projector에 대한 독립 의미 연결이나 P/E 동등성을 주장하지 않는다.

후속 prefix pruning·accepted-only projection 구현은 같은 모델에서 raw 71,680·definite accepted 96·unique output 60·UNKNOWN 0을 유지하며 방문 prefix 8,507개와 가지치기 706개를 기록했다. 생산 진단은 2.14초·최대 RSS 약 103 MiB였다. 저장물은 `current-pe-symbolic-e-prefix-v2/cell_0b7e2829a1e84335c6ae/result.json.gz`(gzip SHA-256 `05d8d37852c31f8040f4d37fe55f6f34f21cac8783f9284285ae00c57042c343`, envelope SHA-256 `4f9a729f2118cd2b8902e7a26c8a1d6a5144c82eb55dc212c5adb0b42062b29b`)이다. 별도 검토는 prefix pruning을 무작위 1,000개 factor 모델의 전수 열거와 대조해 일치시켰다. 검토 중 재서명된 relation root·semantic status 변조를 통과시키던 verifier 결함을 발견해, 고정 envelope SHA를 필수로 요구하고 저장 budget으로 accepted projection/image/차집합을 독립 재생하도록 수정했다. 변조 회귀를 포함한 전체 fedplanner Python 160개 테스트가 통과했고, 실제 저장 artifact도 새 verifier에서 고정 envelope SHA를 지정해 3.26초에 `PASS`였다. 이는 bounded diagnostic의 재생 증거이며 typed projection의 Java 의미 연결은 없으므로 최종 `BLOCKED` 판정은 그대로다.

P symbolic 측의 독립 설계 검토는 현재 `physicalDecode=UNRESOLVED`와 Java acceptance 미직렬화 때문에 Pca w1/P1/P2의 물리 image를 기존 artifact만으로 인증할 수 없다고 판정했다. 필요한 공통 출력은 occurrence별 node/authority, input slot별 binding, action·geometry presence와 정적 logical input/context다. P의 node는 placement+candidate, binding은 consumer·producer·relocation, 공유 action은 여러 demand의 OR에 의존한다. 현재 graph-only component 분리는 이 상관관계를 보존하지 못한다. 정확한 관계에는 양측 native acceptance와 typed projection을 결합해 native 변수를 Boolean 의미론으로 존재 양화하고, 동일 dictionary/order에서 양방향 차집합을 실제 계산해야 한다. 중복 proof를 합산하는 정수 계수기는 이 목적으로 사용하지 않는다. 이를 위한 독립 Boolean MDD core를 구현해 2변수의 가능한 관계 16개와 관계 쌍 256개에 대한 AND/OR/양방향 차집합, 존재 양화, witness, 변조 검사의 집중 테스트 11개를 통과했다. 생산 모델의 acceptance·projection 의미론이 미인증이므로 아직 P/E gate에 연결하지 않았고, core만으로 전체 집합을 만들었다고 주장하지 않는다.

MDD core에 대한 별도 코드 검토는 초기 버전에서 다른 manager의 정수 node handle 충돌, 재서명된 의미 변경 artifact의 부분 replay 통과, 깊은 변수 사전의 재귀 한계, 건너뛴 변수의 범위 외 값을 허용하는 문제를 재현했다. 수정 후 1,100변수 관계를 포함한 집중 테스트 18개가 통과했다. root commitment에는 `expected_variables`를 필수로 결속하고, canonical 직렬화를 streaming으로 전환했으며 node/apply-pair 예산 초과는 fail-closed로 처리한다. 그러나 P/E native acceptance·물리 projection 의미론이 이 core에 연결되거나 인증된 것은 아니다. 생산 gate에는 사용하지 않는다.

## 완료되지 않은 의무

1. 정적 입력은 612조건 전부 결합됐지만 결함이 발견된 v3 실행을 중단했고, 수정된 최종 source/class snapshot의 P/E native capture가 필요하다. SliceLine 64조건은 새 source-backed 파생 compile-model 입력이며 historical snapshot 관측치가 아니므로 이 범위를 `FULL_CURRENT`로 바꾸려면 별도의 기준·coverage 합의와 증명이 필요하다.
2. P acceptance의 남은 opaque predicate를 저장 규칙과 별도 interpreter로 재검사해야 한다. Java 생산자의 accepted proof만 확인한 셀은 `CAPTURED_EQUAL` 이상으로 올리지 않는다.
3. Pca 12개 열거 가능 조건의 전체 양방향 물리 집합을 비교하고, w1·P1·P2를 위한 정확한 압축 관계·projection·차집합 및 독립 재검사를 구현해야 한다.
4. 변경된 최종 source/class/input hash로 612조건의 P/E 모델 matrix를 끝내고 저장 artifact를 독립 재검사해야 한다. 이후 전체 물리 집합의 one-command gate와 cold/warm/중단·재개를 실제 완료해야 한다.
5. 역사 버전 B0/B1 비교와 독립 runtime R 인증은 이 보고서의 P/E 명제와 별도 후속 의무로 유지한다.

현재 확보된 증거는 큰 공간의 E factor 계수와 작은/중간 공간의 저장 형식 정확성까지다. 위 필수 의무가 남아 있으므로 계획의 최종 체크리스트는 통과하지 않았다.
