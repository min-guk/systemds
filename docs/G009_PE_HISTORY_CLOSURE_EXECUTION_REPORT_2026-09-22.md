# G009 P/E·과거 Git plan space 닫기 실행 보고서

작성일: 2026-09-22, 최종 갱신: 2026-09-23. 기준 계획: [g009-pe-history-closure-next-20260922.md](../.omx/plans/g009-pe-history-closure-next-20260922.md).

## 판정

**전체 판정: INCOMPLETE.** 최종 수정 빌드에서 동결 planning 조건 224셀의 P 모델 구조 캡처와 저장 artifact 독립 재검사가 모두 통과했다. 현재 bounded 열거가 가능한 LM·gnmf의 worker 3/5/7 × 네 network, 총 **24개 실제 DML 셀의 P/E 물리 집합 비교와 별도 프로세스 재검사도 모두 PASS**였다. 같은 선택의 Pca 12셀은 E 원시 상태가 예산을 넘어 명시적 `INCOMPLETE`다. 첫 publication closure 빌드의 `gnmf` 반례였던 불법 함수 호출 `FED/FOUT` 배치는 최종 빌드에서 제거돼 물리 identity 288/288·차집합 0/0으로 복구됐다. 그러나 전체 workload의 P/E 집합 비교, B0/B1 역사 비교 1,698 pair, 독립 runtime 의미론 R의 완료 조건은 아직 충족되지 않았다. `ERROR`, 예산 초과, 표현 정보 부족을 빈 집합 또는 합법 후보 부재로 해석하지 않았다. 이 보고서의 셀별 PASS는 **해당 source/class hash에서 캡처된 native 후보의 물리 identity 집합 일치**에만 적용된다. P acceptance 자체의 독립 의미론 재검사는 아직 producer receipt에 의존한다.

## 고정 입력과 검증 범위

- 재현 입력: `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/planning-capture/snapshots/8dfdda32e41308198c8f62cecd038c1da9b663f3808f1d89ff829881d9cf649d/trees/evaluation`.
- 최초 P/E 격리 빌드: `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/isolated-current-build`; 최초 224셀 P 구조 캡처 격리 빌드: `.../planning-capture/current-isolated-20260922/repo`; 최종 publication 수정 빌드: `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-publication-final-build-v1`. 최종 builder source SHA-256은 `570352d2f6276a687b0d4a1d683ddef171da555231fe927c29ef42f56e75a1b9`이며 Maven test-compile을 통과했다. 서로 다른 snapshot 결과를 섞어 단일 source 판정으로 승격하지 않는다.
- 현재 catalog: 447셀 중 `IN_SCOPE` 283, `UNSUPPORTED` 148, `HISTORICAL` 16. 계획 입력 중 224셀은 56개 planning DML × 네 network 조건이다. 나머지 59행은 **59개의 완전히 지정된 실행 조건이 아니라 미해결 discovery placeholder**다. `audit_condition_expansion.py`는 각 placeholder의 source hash를 확인하고 원본 registry의 조건 축을 정적으로 읽어 generated-microbench 28, base-campaign 208, ml10-campaign 120, KDD98/USCENSUS planning 32개의 후보를 생성했다. 기존 224와 합친 **중복 제거·입력 검증 전 후보 행은 612개**다. 이는 확정 분모의 하한이나 완료된 native 입력 수가 아니다. `REGISTRY_AXIS_INVENTORY_ONLY` 결과 `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/condition-candidate-audit-v1.json`은 `--check` 재검사를 통과했고 SHA-256은 `8f0dfbfaabdf2482702462d167cd9f54e1eed55998956ec5c99332c1566dd196`이다. 따라서 현재의 `283`을 최종 전체 workload 분모로 확정하면 누락을 숨긴다.
- P/E 비교의 물리 identity는 proof/native index가 아닌 ordered binding·placement·실제 선택된 authority/action의 공통 좌표를 사용한다. E의 factor truth table은 저장 artifact에서 독립 Python verifier가 재계산한다. P의 구조·hash·radix·raw product는 별도 verifier가 검사하지만 Java acceptance predicate를 독립적으로 직렬화·재실행하지는 못한다.

## 완료된 실제 비교

최종 source/class hash에서 P placement state가 1,000개 이하인 36셀을 선택해 실행했다. LM 12셀과 gnmf 12셀은 각각 P/E 물리 identity 60/60·288/288, 양방향 차집합 0/0으로 `PASS`였고, 24셀 전부 별도 Python 프로세스의 저장 artifact 재검사에서도 `PASS`였다. Pca 12셀은 P state 128개이나 E raw가 각 `2,057,529,600`개로 1,000,000 예산을 초과해 `INCOMPLETE`로 남겼다. 선택·실행·오프라인 영수증을 묶은 `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/bounded-pe-matrix-publication-final-v1/offline-summary.json` SHA-256은 `52b5e5395b9da644ed12061d7d667b62eb937b529584f2e10969f240826a207b`이다. 36개 실행 summary SHA-256은 `4a010013b23c26faa2812f728979e814a036dd3ee9d825e1d278e99d07fd7915`, selection SHA-256은 `0fa2f2c2c9b3f5c6f4d2c1e609a0cabbab56b2760f8da641867a1467d5b5742c`다. 24개 PASS certificate의 source/class hash는 모두 위 최종 빌드와 같았고, 최대 셀 실행 시간은 527.179초였다. 이 부분 결과를 나머지 188개 planning 셀 또는 Pca의 feasible 판정으로 확대하지 않는다.

아래 표의 여섯 셀은 **최종 publication 수정 전 격리 빌드**의 결과다. 최종 빌드의 LM·gnmf 24셀 결과는 바로 위 집계에 기록했다.

| DML / worker | cell | P/E accepted native | P/E physical identity | 양방향 차집합 |
| --- | --- | ---: | ---: | ---: |
| LM / 3 | `cell_f3d1644be8e4ae677150` | 72 / 96 | 60 / 60 | 0 / 0 |
| LM / 5 | `cell_dddbae1f4a7b24bd6047` | 72 / 96 | 60 / 60 | 0 / 0 |
| LM / 7 | `cell_999ea0fa5c824a25b08c` | 72 / 96 | 60 / 60 | 0 / 0 |
| gnmf / 3 | `cell_f25f9235b810daa7339e` | 2784 / 5664 | 288 / 288 | 0 / 0 |
| gnmf / 5 | `cell_d07f3673c9514b8272ca` | 2784 / 5664 | 288 / 288 | 0 / 0 |
| gnmf / 7 | `cell_13c41522803782835c00` | 2784 / 5664 | 288 / 288 | 0 / 0 |

증거 디렉터리: `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-pe-runner/`. 각 셀의 `offline-recheck.json`은 독립 프로세스로 생성됐으며 모두 `PASS`, `factorStatus=INDEPENDENT_FACTOR_TABLE_VERIFIED`, `pStructureStatus=STRUCTURE_VERIFIED`, `pAcceptanceVerification=PRODUCER_RECEIPT_ONLY`, `runtimeSemanticCoverage=NOT_ASSESSED_BY_THIS_CONTRACT`를 기록한다. 재검사 집계 `pilot-offline-rechecks.json`의 SHA-256은 `9a53f032c5690e7012d9a94ca86fb504b7ba625f9352bfe2785ec8fc21c89b83`이다. LM/3에 대해 `--resume`을 두 번 반복했고 실행 디렉터리와 다섯 native receipt의 bytes·mtime이 유지됐다 (`resume-verification.json`).

같은 **수정 전 격리 빌드**의 WAN 세 조건 × worker 3/5/7 × LM·gnmf, 총 18셀도 P/E native 물리 identity의 양방향 차집합 0/0이었다. LM 9셀은 각각 P/E accepted 72/96·물리 60/60, gnmf 9셀은 각각 2,784/5,664·물리 288/288이다. 18개 저장 결과 모두 별도 Python 프로세스의 artifact-only 재검사에서 `PASS`였고, 압축 행·E factor·certificate를 확인했다. 집계 `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-pe-runner/wan-batch-20260922/wan-offline-all.json`의 SHA-256은 `5af62b533cf1a4f645648e44062a7980e5274e5bf6fc798ce28fe8bffc4434b2`이다. 이 WAN 집계의 source/class hash는 `5f43fb766e180ed58b7e3ab6597ad707ab115093bd8e0ea9466a618d1fff0f29` / `9f28f738a6ba0d34e1091fbb0bd81f6c46eb8e9a388e9de1974eb9039f3488b9`로 고정되며 새 publication 빌드의 증거로 전용하지 않는다. 저장 certificate가 verifier 구현 hash를 고정하므로, verifier에 새 함수 호출 guard를 추가한 후에는 그 구현으로 예전 certificate를 재검사할 수 없었다. 동일 hash의 동결 verifier snapshot으로 남은 WAN을 재검사했고, 별도로 새 guard는 구 P 모델을 수락하고 문제 빌드 모델을 거부함을 확인했다.

P2/3 `cell_00d1aa1ca27bce14d826`은 설정 예산 P state count 4096 > 1000, E raw 196378274278932480000 > 1,000,000으로 `INCOMPLETE`를 정상 반환했다. P1/3 `cell_9a27d412abfc065d0762`의 P 구조 검사는 통과했지만 placement state count 35664401793024와 479자리 raw product는 **accepted plan 수나 exact relation 크기가 아니다**. P1/1의 graph-valid placement도 약 `9.90×10^29`여서 단순 state 열거로 전체 완료를 약속할 수 없다.

이 큰 공간의 첫 정확한 부분관계로 `verify_p_graph_relation.py`가 저장 P 모델의 **graph constraint를 만족하는 placement 집합만** component tuple 관계로 보존한다. 별도 verifier는 각 component의 local Cartesian product를 pruning 없이 다시 열거한다. **최종 publication 빌드의 224셀 matrix**에서 가져온 P1/1은 1,511좌표·1,037 component 중 선택지가 있는 82개·graph-valid `990184780655091412432507109376`개, P1/3은 1,519좌표·1,045 component 중 선택지가 있는 31개·graph-valid `139314069504`개로 재검사 통과했다. `CP/FOUT`가 포함된 logreg 셀도 graph-valid `5615859363936731136`개로 통과했다. 세 압축 인증서는 `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/p-graph-certificate-final-v1/{P1_w1,P1_w3,CP_FOUT_smoke}.json.gz`이며 집계 `summary.json` SHA-256은 `05c30ef2865e8e5c00d0d0b62891210edbfe6041e46c91f9a714b7b37879a8d9`이다. 각각 최종 모델 SHA와 인증서 SHA에 묶인다. **scope는 `GRAPH_CONSTRAINT_PLACEMENT_ONLY_ACCEPTANCE_OPEN`**이며 후보·relocation·privacy·최종 P 수락과 runtime 가능성은 이 수치에 포함되지 않는다. Creator와 verifier는 Java planner와 별도 구현이지만 같은 Python 제약 판정 함수를 공유하므로 이 둘의 일치만으로 predicate 자체의 완전성을 증명하지는 못한다. 고정 Java truth table 및 constraint mutation 테스트는 그 공통 오류 위험을 부분적으로 낮춘다. 앞선 구현의 독립 리뷰는 이 좁은 범위와 함께 creator가 valid tuple을 메모리에 모두 쌓으므로 전체 workload 확대 전 chunked 저장이 필요하다고 지적했다. 최종 수정본의 별도 독립 리뷰는 완료되지 않았다.

## 전체 구조 캡처와 오류

동결된 224 planning 셀을 여섯 작업자·셀당 900초 상한·원자적 receipt·재개 방식으로 캡처했다. 최종 `matrix.json`은 **COMPLETE 212, ERROR 12, TIMEOUT 0, 판정 INCOMPLETE**이며 SHA-256은 `82f1b609d8110c6d17e35a425c8f2544124e446141360e1f5c6231e0cb75e4e1`이다. 별도 `verify_current_p_matrix.py`는 224개 필수 condition을 다시 대조해 COMPLETE 212개를 구조 검증했고 실패는 정확히 ERROR 12개다. 증거: `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-p-matrix-hardened/{matrix.json,verification.json}`; verification SHA-256 `10d3ba9f65dfd21ac71e37d15f4754e0d6d8ca05a151ed2d10771b574d69266a`.

12개는 SliceLine worker1의 adult·covtype × 네 조건(8개)과 logreg worker7 × 네 조건(4개)이다. SliceLine은 logical transient 후보와 실제 realization 관계가 어긋나고, logreg는 참조된 transient compatibility realization이 누락/모호하다. 두 오류는 별개 불변식에서 검출되며 `INFEASIBLE` 판정이 아니다. 진단본에서 final publication의 exact reader/realization 관계와 relocation action을 다시 닫고, 매 replay 뒤 privacy domain을 재적용했다. 이 첫 수정 빌드의 224셀 구조 캡처는 COMPLETE 220, ERROR 0, TIMEOUT 4였지만 `gnmf`/3/LAN에서 P accepted 5,568, P physical 576, P-only 288로 **FAIL**했다. 추가 288개는 마지막 replay가 executable projection 뒤에 DML 함수 호출의 불법 `FED/FOUT` 상태를 되살린 결과다. 전체 첫 수정 matrix에서 56셀·116개 함수 호출 좌표가 같은 패턴이었고, 저장 P 모델 검증기의 함수 호출 `CP/LOUT` guard가 이 56셀을 거부했다. 증거: `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-p-matrix-publication-fixed/{matrix.json,verification-function-call-guard.json}`, 후자 SHA-256 `81f522f42cabf5cbb02677fadc585124fdfeb672b342008432149da25d424624`.

최종 수정은 action replay 뒤 executable projection과 변경 consumer의 physical/CFG/privacy closure를 다시 수행하고, 두 번째 projection이 새 dependency를 만들면 fail-closed한다. action 집합이 다시 바뀌면 다음 outer pass로 넘기며, 안정 상태에서 relocation binding→게시 action·obligation·live source와 graph-only action→candidate support를 mutation 없이 검사한다. 강화된 동결 `gnmf` 함수 호출 회귀, 동결 SliceLine 회귀, 관련 closure/relocation/privacy 12개 클래스가 통과했다. 최종 빌드의 `gnmf`/3/LAN은 P/E accepted 2,784/5,664, 물리 288/288, 차집합 0/0으로 복구됐고 LM/3도 72/96, 물리 60/60, 차집합 0/0이다. 두 셀의 별도 프로세스 offline recheck도 `PASS`이며 각각 SHA-256은 `ecee7528fbd4e2bf2470b193053b6819c6bbf93c34e520b2ddabc8d50f168126`와 `a3874ba2114be03b78b16c5b261f9ff2710aabde661302ee0557cc9fb6619bf6`이다. 증거: `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-pe-publication-final-v1/`. 보호된 relocation 테스트 한 건의 실패는 수정 전 빌드에서도 동일 재현됐다.

같은 최종 source/class hash `c9e48e7f32641d0e915529f8d67c75d0986cad7f42e5c932c0c53b04750d6c56` / `1d679a6e2c8eca0fdb9a09faba720938e2da5f535e23a37888f0c6d801e7aa43`에서 224 planning 셀 모두 `COMPLETE`였다. `matrix.json` SHA-256은 `13babb159f6a6dd10c35de481da68d99298d1268d26a6ed8026f7aea4e4fd1c7`이다. 저장 artifact만 읽는 독립 `verify_current_p_matrix.py`도 필수 네 network 조건의 224셀 모두를 검증해 `PASS`, `verifiedComplete=224`, 실패 0을 기록했다. `verification.json` SHA-256은 `440ea0c299db9a29989f9bb35fb4cb1d8db6bbb4aef58fd682597f2ea425c6c9`이다. 이 판정 범위는 `P_MODEL_STRUCTURAL_CAPTURE_COVERAGE`; Java P acceptance 및 최종 feasible 집합은 포함되지 않는다. 증거 디렉터리: `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-p-matrix-publication-final-v1/`.

같은 입력으로 `--resume`을 재실행한 뒤에도 224개 셀 receipt의 bytes·mtime이 모두 유지되고 matrix manifest hash가 같았다 (`resume-verification.json`, SHA-256 `ad76d6f4d00a8a7f6d23ab64fca75c009d4bc275bcf844b4395372e8ba2c8d2f`). 캡처 시간의 중앙값은 11.60초, 95백분위는 208.17초, 최장은 logreg worker 1/WAN light의 831.37초였다. 따라서 이 동결 범위는 셀당 1,800초 상한 안에서 완료됐지만, 종전 900초 상한은 이 workload에 충분하지 않았다. 224개 모델의 DML 함수 호출 배치 1,232개를 별도 스캔한 결과 모두 `CP/LOUT`이었고 불법 `FED/FOUT`는 0이었다.

## 과거 Git과 독립 oracle

- B0 `ffb7be5bd85367156ed9ea86dacbaff4be0f035d`, B1 `d8fbd30b5476a1ceef460c9f3886381a369ac619`의 B-01 local-only 자기 비교는 각각 P/E 물리 identity 1개와 양방향 차집합 0으로 통과했다.
- B1 B-11 direct-FOUT profile은 P/E accepted native 2개씩이 동일 물리 identity 1개로 모였고 차집합 0이다. B0 B-11은 P accepted 1, E accepted 2이며 selected realization/layout key가 없어서 `UNRESOLVED_B0_REALIZATION`이다. E의 `DURABLE_ANCHOR` 대안을 근거 없이 P와 같다고 투영하지 않았다. 증거: `.../legacy-source/{B0,B1}-B{01,11}-*-recheck-20260922.json`.
- B0/B1 원본 compiler가 같은 동결 LM/3 LAN DML을 컴파일해 P/E 네 native prefix 영수증을 기록하도록 test-only bridge를 확장했다. DML 경로·SHA와 원본 버전, row/catalog hash가 각각 고정된다. B0 P raw `2411200018117739439485856863308617734191841280`, E raw `7864320`; B1 P raw `544466545757784340619319397441380843051496243200`, E raw `45121536`이다. 네 건 모두 첫 행 `0..1`만 처리한 `PARTIAL`이며 B0/B1 실제 DML의 물리 자기 비교나 추가·삭제 판정이 아니다. 증거: `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/legacy-real-dml/`.
- 과거 **실제 DML**의 전체 B0/B1 물리 비교는 아직 없다. 1,698 pair의 적용 여부는 미분류다.
- 기존 `IndependentLogicalInputFixtureOracleTest`에 PHI 삭제/삽입, authority 대체, prebuilder AND operand 누락, ordered slot 교환 mutation을 추가했고 focused JUnit 9개가 통과했다. 이 bounded fixture 증거는 독립 runtime R 전체 인증이 아니다.

## 재사용 gate와 실제 종료 상태

단일 명령 게이트는 저장된 P 구조 matrix를 별도 verifier에 통과시키도록 확장했다. 동결 catalog·evaluation root·필수 condition 네 개를 독립 인자로 전달하며, 손상/범위 불일치를 거부한다. 최종 matrix import는 exit 2 / 전체 `INCOMPLETE`이지만 **P matrix 부분은 `PASS`, `verifiedPModelCells=224`, P matrix 실패 0**이다. 남은 계수는 `unresolvedCurrentConditions=59`, `uncapturedCurrentCells=283`, `unclassifiedHistoricalPairs=1698`, `campaignManifestPresent=false`다. 증거: `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/gate-matrix-import-publication-final-v1/preflight.json`, SHA-256 `c45ce4262cff2c67f20894793f0a1132d87e4547cd8a8ac4383e28ca786bc17b`. 283은 실제 P 구조 캡처 0개라는 뜻이 아니라 **gate가 인정한 전체 current P/E native 입력 캡처 0개**라는 뜻이다. P 구조 224개 검증과 전체 P/E 인증을 혼동해 전체 gate를 PASS로 재해석하지 않는다.

`run_bounded_pe_matrix.py`는 검증된 P matrix와 source/class hash를 먼저 맞추고, 각 P/E 캡처의 P 모델 SHA가 matrix 모델 SHA와 같은지 확인한다. 예산 안의 셀을 병렬 실행한 뒤 **각 PASS를 별도 프로세스에서 다시 검증**하며 원자적 셀 영수증과 summary를 저장한다. 재개 binding은 배치 실행기·셀 runner·verifier 코드 hash와 필수 condition 집합을 포함한다. `--resume`는 같은 binding의 저장 PASS를 Java 캡처 없이 offline verifier에 다시 넣는다. 최종 matrix에 대한 dry-run은 36셀을 선택했고 selection SHA-256은 `2faafc9f8d0222995dfabe94c429bb0a0090326dfee47d508b6625396415c280`다. LM/3 단일 셀 end-to-end smoke와 이어진 warm `--resume`가 모두 `PASS_BOUNDED`였고 summary SHA-256은 동일한 `b90fb541067522f9332a67f2614505748cce4689e9bc5b68cdad016a9e53f83e`다. 14개 native 파일의 bytes·mtime은 warm 실행 전후 모두 유지됐다 (`bounded-pe-runner-smoke/resume-verification.json`, SHA-256 `85ba00770cdf9bd60f61a4bfe28b3fb8223109cf752198242f690380c6f7c459`). 이 신규 배치기의 **전체 36셀 실행은 아직 별도로 반복하지 않았다**. 위 36셀 결과는 같은 셀 runner를 병렬 호출하고 별도 프로세스 offline verifier로 재검사한 기존 실행의 증거다. bounded 명령이 성공하더라도 범위 밖 셀이나 runtime R를 인증하는 전체 gate는 아니다.

저장된 증거는 다음 명령으로 compiler 재실행 없이 다시 확인할 수 있다. P/E `verify`는 저장 물리 행을 다시 정렬·비교하므로 큰 셀에서는 수 분 걸린다.

```bash
cd /home/mchoi/systemds-g009-integration
BASE=/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921
CATALOG=src/test/resources/fedplanner/plan-space/closed-comparison-cases.json
EVAL="$BASE/planning-capture/snapshots/8dfdda32e41308198c8f62cecd038c1da9b663f3808f1d89ff829881d9cf649d/trees/evaluation"
python3 scripts/fedplanner/verify_current_p_matrix.py --matrix-dir "$BASE/current-p-matrix-publication-final-v1" --catalog "$CATALOG" --evaluation-root "$EVAL" --expected-conditions lan,wan_light,wan_mid,wan_heavy
python3 scripts/fedplanner/audit_condition_expansion.py --catalog "$CATALOG" --evaluation-root /home/mchoi/cofee-evaluation --output "$BASE/condition-candidate-audit-v1.json" --check
python3 scripts/fedplanner/run_current_pe_cell.py verify --evaluation-root "$EVAL" --verification-root /home/mchoi/cofee-evaluation --run-dir "$BASE/current-pe-publication-final-v1/cell_f25f9235b810daa7339e/465ad66618c7d5c37fd4"
python3 scripts/fedplanner/run_bounded_pe_matrix.py --build-root "$BASE/current-publication-final-build-v1" --catalog "$CATALOG" --evaluation-root "$EVAL" --verification-root /home/mchoi/cofee-evaluation --p-matrix-dir "$BASE/current-p-matrix-publication-final-v1" --artifact-root "$BASE/current-pe-publication-final-v1" --result-dir "$BASE/bounded-pe-replay" --expected-conditions lan,wan_light,wan_mid,wan_heavy --jobs 4 --cell-timeout 1800 --resume
```

마지막 bounded 명령은 Pca 12셀의 현재 예산 초과 때문에 종료 코드 2 / `INCOMPLETE`가 예상된다. 필수 전체 workload의 성공 판정으로 사용하지 않는다.

이번 구현은 전체 native 모델 artifact와 구조 verifier, current P/E runner의 factor·물리 집합·독립 offline certificate 검증, P matrix capture/verification, bounded 배치 재실행기, graph-only 압축 관계 인증, 조건 후보 감사, 원본 Git DML prefix bridge, 작은 관계의 enumerator 및 반례/fixture 테스트를 추가·보강했다. 최종 Python `scripts/fedplanner` 테스트 45개와 legacy-adapters 3개, evaluation 비교·verifier 테스트 44개가 통과했다. 최종 builder 파일과 동일 SHA를 복사한 격리 빌드에서 동결 gnmf 함수 호출 회귀 1개, 동결 SliceLine 회귀 1개, 관련 fixed-point·candidate·relocation·privacy 테스트 12개 클래스가 통과했다. 최종 immutable 빌드의 Maven test-compile, `git diff --check`, shell syntax check, Python compile check도 통과했다. 기존 protected relocation 테스트 한 건은 수정 전 빌드에서도 같은 실패를 보였으며 별도 의무로 남긴다.

## 남은 완료 조건과 우선순위

1. 동결 planning 224셀의 P 구조 검증은 끝났다. 같은 source/class hash에서 P/E **최종 accepted 물리 집합**의 양방향 차집합을 나머지 planning 200셀까지 확인해야 한다. LM·gnmf 24셀의 성공과 224개 구조 모델의 성공만으로 나머지 P acceptance를 판정하지 않는다. 특히 Pca 12셀은 압축된 정확 관계와 물리 projection이 필요하다. 구성적 action pass의 상한과 반복 whole-graph 비용도 운영 예산에 맞게 평가한다.
2. 59개 `UNRESOLVED` placeholder를 실제 condition/input 행으로 확장하고 최종 분모를 다시 고정한다. base-campaign 13개 workload는 registry상 4 network × 4 worker의 208조건, ml10-campaign 10개는 4 network × 3 worker의 120조건이다. KDD98/USCENSUS의 w3/w5/w7 template에는 worker1 주소만 남고 partition manifest에도 두 dataset이 없어, 기존 context를 복사하는 방식으로 승격할 수 없다. 현재 gate가 외부 캡처를 검증된 manifest로 가져오거나 직접 실행하도록 연결한다.
3. P acceptance의 producer receipt 의존성을 줄이는 lossless relation/DAG 및 독립 coverage 검증을 완성한다. P1처럼 거대한 공간은 조합 state 반복 대신 증명 가능한 factor/frontier 관계가 필요하다. 모든 셀에서 P/E 양방향 physical set 차집합과 원본 native witness를 검사한다.
4. B0/B1의 원본 기록 시점에 realization/layout/ordered binding/authority 좌표를 보강해 대표 실제 DML의 self-equality와 현재 대비 추가·삭제 witness를 얻고, 1,698 pair 적용 여부를 채운다.
5. parser/prebuilder·실행의 독립 의미론 R를 별도로 닫는다. 전체 한 명령·artifact-only verifier가 미처리 0, 오류 0, coverage 일치로 성공하기 전에는 포괄적 feasible/illegal plan 인증을 주장하지 않는다.

## 검증 범위와 위험

이번 비교는 양쪽의 **공유 누락**을 검출하지 못한다. 독립 fixture가 검출한 B-21 catalog 누락처럼 P/E equality는 전체 feasible completeness 증거가 아니다. P accepted 수와 매우 큰 raw product도 각각 제한된 의미를 갖는다. 저장된 구조·factor verifier가 검증하지 않는 Java legality predicate, runtime privacy/data 이동, historical 원본 IR 차이는 명시적 미완료 의무로 유지한다.
