# 세션 이슈 — 2026-09-22

## StepLM 수리의 일반화 보장 한계와 GLM planning 회귀 분석

- **상태:** 기존 소스·테스트·로그 분석과 [답변/후속 제안 문서](G009_GENERALIZED_CORRECTNESS_AND_GLM_REGRESSION_RESPONSE_2026-09-22.md) 작성 완료. 이번 요청에서 production 수정, 새 benchmark, 학습 runtime 실행은 하지 않았다.
- **환경/증상:** 보호된 P2P2D, worker4, 공식 Docker planning-only. 직전 `805095…` 대비 `f143c281…`의 GLM 전체 초기 planning이 네 planner 모두 증가. DP-local 45.141704019→53.786597195초(+19.15%), DP-global 44.118993136→57.674614112초(+30.73%)의 단회 관측이다.
- **확인 원인 범위:** DP-local CandidateE2E 증가 9.325500970초 중 공통 analysis 증가가 8.678304808초(93.06%). selection 증가는 0.010744985초. 비용이 늘어난 단계는 확인했지만 nested privacy/CFG closure, producer seed 확대, SCC proof 상태 증가 각각의 기여율은 미측정이다. JVM/설정은 같지만 host 경합·JIT 변동 및 JAR 사이 개별 patch 인과는 분리되지 않았다.
- **보장 한계:** 56/56 planning-only 성공은 모든 HOP/DML의 합법 후보 완전성이나 runtime 정확성 증명이 아니다. 함수 negation 테스트의 nonempty graph assertion, bounds helper-only 검사, 같은 production 의미론을 공유하는 incremental/full 비교의 한계를 구체적으로 기록했다. 전체 독립 공간 인증은 UNKNOWN이며 기존 single-worker 사례도 별도 잔여다.
- **해결 제안:** 공통 축 보존·모든 reaching definition·SCC grounding·권한 철회·정확한 고정점 계약을 작은 독립 oracle와 음성/mutation 사례로 강화한다. 이후 no-change closure 재사용, 영향 SCC/owner만 갱신, authority-safe proof/index 재사용을 한 묶음씩 검증한다. 공통 합법 후보 삭제·privacy 검사 제거로 시간을 단축하지 않는다.
- **수정 파일/검증:** 위 답변 문서와 이 기록만 작성. 기존 coordinator receipt의 phase 나노초·조건을 대조하고 상대 문서 링크와 whitespace를 확인했다. 신규 성능 결과 또는 수정 완료 주장은 없다.
- **잠재 회귀 위험 및 감지:** 불완전한 cache key/부재 의존성으로 stale authority를 재사용할 위험은 source 추가·삭제·교체·privacy 철회 mutation과 독립/full 재계산 대조로 검사한다. policy preference를 legality로 잘못 취급하는 위험은 공통 공간 불변성 및 local 선호/차선 선택 계약으로 검사한다.
- **의사결정 근거:** 이전 GLM 성공본은 성능 참고 기준, 수정본은 정확성 보존 기준으로 구분한다. 60초 단회 통과와 baseline 대비 회귀 해소를 혼동하지 않으며, 개발 중 작은 테스트+GLM DP-local 단회 후 최종 JAR에만 전체 회귀를 적용하도록 제안했다.

## 기존 oracle 재사용 우선으로 계획 명확화

- **상태/문제:** 계획 수정 완료. 앞 문서의 “작은 oracle 작성”이 기존 체계를 다시 만드는 작업으로 읽힐 수 있었다.
- **변경:** 답변 문서 §5·§9에 기존 enumerator/domain/legality checker/builder·selector oracle/independence test/adapter 재사용 표, 부족한 계약 분류, 완료 기준을 추가했다. 이미 지원하는 계약은 fixture/assertion만 보강하고, 미지원 의미론만 기존 모델 안에서 최소 확장한다.
- **검증/수정 파일:** 답변 문서와 이 기록만 수정. 기존 `JointPlanLegalityChecker`의 `S-CYCLE`, `S-COVERAGE` 경계를 읽고 문서 링크·whitespace를 확인했다. production/test 구현 및 새 실험은 하지 않았다.
- **잔여/회귀 위험:** 기존 oracle도 모든 동적 순환을 판정하지 못한다. fixture가 추가됐다는 이유로 UNKNOWN을 PASS로 바꾸거나 production의 rule을 복사해 독립성을 잃지 않도록 명시했다. 변경한 계약은 관련 gate 통과가 필요하지만, 무관한 전체 인증을 GLM 최적화의 선행 작업으로 확대하지 않는다.

## P/E·역사 비교 후속 완료 순서

- **상태:** 후속 계획 작성. 이번 턴은 코드 수정·workload 재실행 없이 현재 구현과 실행 보고서를 확인했다.
- **문제/원인:** 현재 capture 결과에는 재사용 가능한 전체 native 모델 대신 hash·규모 위주 정보가 남고, 전체 script는 미완료 분모를 발견하면 종료한다. 대형 relation 경로와 legacy 물리 decoder가 없어 실행 시간을 늘리는 것만으로 전체 비교를 끝낼 수 없다. legacy audit 정보 부족을 옛 원본 모델의 본질적 표현 불능으로 단정해서도 안 된다.
- **해결 제안:** [후속 계획](../.omx/plans/g009-pe-history-closure-next-20260922.md)에 실제 한 셀의 capture→relation→물리 비교→저장 증거 재검사→재개를 먼저 완주하고, legacy bridge와 기존 독립 oracle 보강을 병행한 뒤 전체 분모로 확대하도록 정리했다. 전체 모델 저장·증명 좌표의 정확 projection·artifact-only checker를 다음 구현 대상으로 명시했다.
- **수정 파일/검증:** 위 계획과 이 기록. source 경로·주요 호출 순서·문서 whitespace 검사. 새 테스트/성능/전체 PASS 주장은 없다.
- **잔여 이슈:** 실제 DML E adapter, 전체 native artifact 계약, 큰 관계의 정확 비교, 과거 물리 좌표 추출, 모든 셀/버전 적용 여부 확정, 독립 runtime 의미론 R 의무가 남는다.
- **잠재 회귀 위험/감지:** raw proof 비교를 physical plan 비교로 오인하거나 relation compression이 대안을 지우는 위험은 no-prune 대조·native witness·독립 coverage verifier로 검사한다. source가 다른 세션의 planning 성공은 hash·조건이 일치할 때만 재사용한다.
- **의사결정 근거:** 568자리 raw product는 accepted plan 수나 정확 relation 크기의 증거가 아니다. 모델 표현과 재사용 경로의 실제 pilot 통과 후 계산 병렬도를 늘리며, runtime 후보·privacy 제약을 완화하지 않는다.

## 일반화 정확성·GLM 회귀 대응 실행

- **상태:** [실행 보고서](G009_GENERALIZED_CORRECTNESS_AND_GLM_REGRESSION_EXECUTION_REPORT_2026-09-22.md) 작성, 고정 JAR 공식 Docker planning-only **56/56 정상 receipt**. 후보·privacy guard를 삭제하지 않았다.
- **변경:** 기존 `MatrixCapabilityOracle`에 full-row dynamic-column의 조건부 ROW 행축 계약을 추가하고 StepLM 함수 negation/loop 및 publication 권한 assertion을 보강. builder는 동일 emission fact를 재사용하고 privacy 무변경 시 중복 CFG/direct/physical 재-grounding을 생략. WDivMM 전용 매처가 일반 HOP마다 전체 node index를 만들지 않도록 필수 source shape 사전 판별을 추가. 이득이 없던 closure 전체 no-op 시도는 되돌렸다. 별개 shadow 테스트의 중복 로컬 변수명만 수정해 컴파일을 복구했다.
- **성능 증거:** 개발 DP-local pilot 53.786597→46.946435초였으나 최종 고정 JAR 1회는 **50.414815초**였다. 최종 GLM FedFirst 45.108064, AggLocal 48.258923, DP-local 50.414815, DP-global 47.127119초. 모두 60초 미만이지만 이전 성공 `805095…`의 45.114049/46.476850/45.141704/44.118993초보다 세 planner는 느리다. 같은 production class bytes의 DP-local 두 실행 차이 3.468381초를 숨기지 않는다.
- **검증:** 표적 테스트 112 통과, 기존 skip 1, 해당 묶음 실패 0. `mvn -DskipTests package`, `git diff --check`, 최종 SHA·manifest·planner별 config·56개 receipt 일치 확인. FED indexing full-row slice 테스트의 실제 worker 실행/값 비교와 `fed_rightIndex` 관측은 있었으나 기존 `fed_leftIndex` 강제 assertion은 현 single-pass 정책에서 실패했다. 원본 fixture의 `compile_fed_all` 설정은 현 enum과 맞지 않으며 임시 테스트 설정 변경은 되돌렸다.
- **잔여:** 전체 독립 oracle의 `S-CYCLE/S-COVERAGE`는 UNKNOWN. 임의 HOP/DML 완전성, worker runtime 전체 계약, 기존 single-worker ALS 실패, GLM 이전 baseline의 확정적 회복은 미완료다. 다음 캐시/증분 최적화는 authority·absence 의존성의 음성/mutation 반례를 통과하기 전 채택하지 않는다.

## 실제 gnmf P/E 물리 집합 반례와 투영·E 대안 수정

- **상태:** gnmf/w3/lan 한 셀에서 해결. 전체 corpus 및 runtime R 인증은 진행 중.
- **환경/재현:** `cell_f25f9235b810daa7339e`, 고정 DML/metadata/network, post-rewrite P/E 모델. `scripts/fedplanner/run_current_pe_cell.py run --build-root <isolated-build> --catalog <frozen-catalog> --evaluation-root <frozen-evaluation> --cell cell_f25f9235b810daa7339e --artifact-root <artifact-root> --jobs 4 --compile`을 실행한다.
- **증상:** 처음에는 P 물리 key 288개, E 496개로 E-only 208개였다. E의 `RELOCATION_SOURCE`는 실제 execution rule과 입력 증명을 갖고 있어도 투영이 `SYNTHETIC_BOUNDARY`로 표기했다. 해당 대안의 relocation action도 실제 input authority가 선택하지 않은 경우 물리 행에 출력했다.
- **원인/해결:** E 투영에 execution rule/emission과 ordered input을 반영하고, 실제 선택된 input relocation만 action으로 출력했다. 별도로 E native 대안은 FED/LOUT 실행 emission을 FED/FOUT 선택 state에 연결할 수 있었다. 이는 `ExactPhysicalSelection.create`의 emission-state identity 검사에서 거부되는 tuple이므로, 동일 state인 대안만 생성하도록 했다. 이 제약은 비용 선호에 의한 후보 삭제가 아니라 최종 선택의 필수 합법성 불변식이다.
- **수정 파일:** `ExactPhysicalModel.java`, `ExactPhysicalComparisonRow.java`, `ExactPlanningModelCapture.java`, `ExactPhysicalModelCertificateTest.java`, `verify_e_factor_artifact.py`, `run_current_pe_cell.py` 및 검사 테스트.
- **검증:** 수정 후 P/E 각각 288개 물리 key, 양방향 차집합 0. P 원시 공간 `2620738870207778608045844368195584` = accepted 2784 + rejected `2620738870207778608045844368192800`; E 266112 = accepted 5664 + rejected 260448, 양쪽 unknown 0. 저장 E factor truth table을 별도 Python 프로세스가 재계산했다. fixture projector 및 E alternative identity 표적 테스트 통과. 증거: `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-pe-runner/cell_f25f9235b810daa7339e/`.
- **잔여:** P acceptance predicate 자체는 Java에 남아 있어 저장 artifact만으로 의미론을 독립 재실행하지 못한다. 이 P/E 일치가 두 모델의 공유 누락이나 실제 runtime 가능성 R를 증명하지는 않는다.
- **잠재 회귀 위험/감지:** action witness를 선택 action으로 오인하거나 FED/LOUT 실행을 FED/FOUT로 잘못 투영하면 가짜 차집합 또는 불법 plan이 재발한다. fixture 테스트, 저장 artifact 차집합 및 E factor mutation 검사를 함께 실행한다.
- **의사결정 근거:** 최종 physical selection의 동일-state 계약을 E native 모델에 반영했다. runtime이 지원하는 합법 후보를 opcode 가드로 임의 삭제하지 않았다.

## 실제 capture 분모와 거대 관계의 미완료

- **상태:** 진행 중. LAN planning 56개 입력 중 42개 COMPLETE, 14개 ERROR, TIMEOUT 0. 각 COMPLETE artifact는 별도 Python 구조 검사로 확인했다.
- **환경/증상:** 동결 worker 1/3/5/7 × 14 DML. P2의 원래 privacy closure 오류는 protocol에 고정된 `-Dsysds.privacy.allowPublicRecodeMetadata=true`가 capture JVM에서 누락된 것이 원인이었다. 고정 옵션을 전달하면 P2 w1은 두 별도 JVM에서 COMPLETE 및 동일 모델 hash다. GLM w1은 정상 완료까지 221.214초·최대 RSS 2,482,740 KiB가 필요했다. 14개 오류는 ALS/logreg/StepLM privacy closure 및 일부 SliceLine transient closure로 남아 있으며 빈 공간으로 분류하지 않는다.
- **해결/수정 파일:** `PlanningNativeModelCapture.java`가 protocol의 workload별 JVM 속성을 fail-closed로 확인한다. `capture_current_plan_matrix.py`는 고정 입력·환경을 검사하고 제한시간/병렬도/원자적 receipt/재개를 제공한다. `verify_p_model_artifact.py`는 도메인·graph hash·raw product를 compiler 없이 재검사한다.
- **검증:** LAN 56개 receipt의 cell ID가 모두 달랐고 COMPLETE 42개 모델은 구조 오류 0. 새로운 capture runner의 gnmf 단일 셀 시범 실행 COMPLETE. P1 w1 placement 조합은 약 `1.09×10^42`; binary graph constraint를 모두 적용해도 약 `9.90×10^29` placement가 남는다.
- **잔여:** WAN 168개 입력 capture가 실행 중이다. 추가 59개 셀은 source catalog에 조건/입력 정보가 `UNRESOLVED`로 남아 있어 캡처 분모가 고정되지 않았다. P1 등 거대 셀에는 현재의 state별 반복 대신 lossless factor/DAG 관계와 독립 coverage 증명이 필요하다. 1,698개 역사 pair의 적용 여부도 아직 미확정이다.
- **잠재 회귀 위험/감지:** timeout/exception을 infeasible로 오분류하거나 서로 다른 worker·network 조건을 하나의 cache로 합칠 수 있다. cell별 condition/program/source/class hash 및 명시적 ERROR/TIMEOUT 상태를 receipt에 묶고 재개 검사로 감지한다.
- **의사결정 근거:** 고정 protocol 옵션을 재현하여 compiler 경계를 맞췄으며 privacy 규칙은 완화하지 않았다. 거대한 raw product를 일부 샘플이나 count 일치만으로 PASS로 승격하지 않는다.

## P/E·역사 비교 closure 실행의 미완료 경계

- **상태:** [실행 보고서](G009_PE_HISTORY_CLOSURE_EXECUTION_REPORT_2026-09-22.md)를 작성했다. 현재 전체 판정은 `INCOMPLETE`이며, 여섯 실제 LAN 셀만 캡처된 native 후보의 P/E 물리 집합 일치와 artifact-only 재검사를 통과했다.
- **환경/재현:** `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/`의 frozen evaluation tree, 격리 current P/E build와 P matrix build를 사용했다. P/E 셀별 `offline-recheck.json`, 전체 `current-p-matrix-hardened/{matrix.json,verification.json}`, `closure-gate-recheck-20260922/preflight.json`을 보존했다.
- **증상/범위:** planning 224셀의 P 구조 캡처는 COMPLETE 212, ERROR 12, TIMEOUT 0이다. SliceLine w1 adult·covtype 8셀과 logreg w7 4셀의 exact transient identity 불변식 오류는 빈 관계로 분류하지 않는다. 나머지 59행은 실행 조건이 고정된 셀이 아니라 discovery placeholder다. registry 확장 시 microbench 28, base 208, ml10 120, archived planning 32조건이므로 현재 283 분모를 최종 전체 분모로 확정할 수 없다. P1 등 거대 exact relation, B0/B1 실제 DML과 1,698 historical pair, 독립 runtime 의미론 R도 남는다. 한 명령 gate는 `campaignManifestPresent=false`, `uncapturedCurrentCells=283`, `unclassifiedHistoricalPairs=1698`로 exit 2다.
- **해결/검증:** 전체 모델·E factor·P 구조 artifact 검증, 작은 실제 셀의 물리 집합 비교, 독립 offline certificate와 재개 검사를 연결했다. 여섯 LAN 셀 양방향 차집합 0, P/E accepted coverage balance와 E factor truth table 재검사 성공. B0/B1 B-01 self-equality와 B1 B-11 profile self-equality 성공; B0 B-11은 realization/layout key 부재로 `UNRESOLVED_B0_REALIZATION` 유지. B0/B1 실제 LM DML 원본 parser prefix 네 건은 SHA 고정 `PARTIAL`로 기록했다. 한 명령 gate는 저장 P matrix를 별도로 가져와 212개 구조 검증·12개 오류와 미해결 조건 59개를 보고하되 전체 P/E 미캡처 283을 그대로 유지한다. Oracle PHI/authority/AND/ordered-slot mutation focused 9개 통과, Python/evaluation 테스트는 최종 변경 후 재검사 예정이다.
- **잠재 회귀 위험/감지:** SliceLine/logreg용 exact replay 뒤 privacy filtering 재적용이 빠지면 제외한 emission이 되살아날 수 있다. 진단본에서 이 경로를 보완하고 독립 리뷰 후 본 소스에 통합했다. 대표 오류 두 셀 COMPLETE·보호 테스트 24개 실패 0·LM/3 전체 P 물리 행 diff 0이지만, 구성적 action 고정점의 상한과 반복 whole-graph 비용은 matrix 전체로 검증 중이다. 보호된 relocation 테스트 한 건의 실패는 수정 전 빌드에서도 재현됐으므로 그 실패 자체를 새 회귀로 단정하지 않는다.
- **의사결정 근거:** P/E equality는 공유 누락과 실제 runtime R를 증명하지 않으며, P acceptance의 offline 검사는 아직 producer receipt에 의존한다. 거대 raw product를 accepted count로 취급하지 않고 budget 초과를 `INCOMPLETE`로 보존한다.

## P1 graph-only 압축 관계 인증의 범위

- **상태:** `verify_p_graph_relation.py`의 생성·별도 재검사와 변조 테스트 16개 통과. 수정 후 모델에 묶인 P1/w1·w3 인증서와 CP/FOUT logreg smoke가 독립 Cartesian component 재열거를 통과했다. 전체 P acceptance는 `OPEN`이다.
- **원인/해결:** P1 원시 조합의 568자리 수를 반복 열거할 수 없어 저장 native graph의 binary constraint를 component 관계로 분해했다. 첫 구현의 CP/FOUT 거부, 모델 재읽기 TOCTOU, node/domain 불일치, Java forbid-pair 경계 차이를 독립 코드 리뷰에서 찾아 수정했다. 인증서는 정확히 읽은 모델 바이트 SHA에 묶이고, 누락 tuple/constraint 및 재해시 모델 변조를 거부한다.
- **증거:** `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/p-graph-certificate/`. P1/w1 graph-valid `990184780655091412432507109376`, P1/w3 `139314069504`; scope `GRAPH_CONSTRAINT_PLACEMENT_ONLY_ACCEPTANCE_OPEN`. verifier는 Java planner 없이 local component Cartesian product를 전수 대조한다. creator와 verifier는 Python predicate를 공유하므로 Java-derived truth-table mutation으로 의미론 일치를 별도 검사했다.
- **잔여/성능 위험:** candidate AND/OR support, 함수 경계, relocation 및 선택 action authority와 privacy는 관계에 들어있지 않다. 모든 workload의 graph 인증서로 넓히면 component valid tuple 전체를 메모리에 보유하는 현 creator를 chunked 저장으로 바꿔야 한다.

## publication replay가 되살린 불법 함수 호출 배치

- **상태:** 수정 후 빌드의 `gnmf`/worker 3/LAN P/E가 `FAIL`하여 생산 수정과 전수 재검사가 진행 중이다. 수정 전 여섯 LAN 및 18 WAN 셀의 PASS를 새 source hash로 승격하지 않는다.
- **재현/원인:** 새 publication replay 뒤 executable candidate projection이 다시 실행되지 않아, DML 함수 호출 `FUNCTION_CALL` 좌표 83의 배치가 CP/LOUT 하나에서 FED/FOUT/ROW까지 늘었다. 해당 candidate domain과 E는 CP 전용이고 runtime 함수 호출 명령도 CP/LOUT이다. P accepted 2,784→5,568, P physical 288→576, E physical 288, P-only 288, E-only 0이다. 추가 행은 `SYNTHETIC_BOUNDARY` authority를 가졌다.
- **범위/검출:** 수정 후 224셀 구조 matrix는 COMPLETE 220, TIMEOUT 4였으나 56셀·116개 함수 호출 좌표에 불법 FED/FOUT 상태가 있었다. 독립 Python P 모델 verifier에 함수 호출 CP/LOUT 불변식을 추가하자 COMPLETE 중 56개를 `MODEL_VERIFICATION_ERROR`로 거부하고 164개만 검증했다. 기존 matrix의 함수 호출 1,172개는 전부 CP/LOUT이었다. 증거: `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-p-matrix-publication-fixed/verification-function-call-guard.json`.
- **수정 검증 경계:** 마지막 CFG/privacy/action replay 후 projection, 변경된 compiled 좌표의 physical/CFG closure, action/realization 재결합을 하나의 종료 불변식으로 확인해야 한다. 단순 함수 호출 domain 회복만으로 소비자와 action의 합법성을 입증하지 않는다. 수정 빌드에서 `gnmf` P/E 288/288과 224셀 재캡처·독립 검증을 다시 수행한다.

## 최종 publication 빌드의 224셀 P 구조 재검증

- **상태:** 동결 planning 224셀 모두 같은 source/class hash에서 `COMPLETE`; 별도 artifact-only verifier가 `verifiedComplete=224`, 실패 0, `PASS`로 확인했다. 전체 P/E 및 runtime R 인증은 계속 `INCOMPLETE`다.
- **원인 해결:** 마지막 action replay 뒤 executable projection과 변경된 consumer의 physical/CFG/privacy closure를 다시 적용한다. 두 번째 projection이 새로운 compiled dependency를 만들면 fail-closed하고, action 집합 변경은 다음 outer pass로 보낸다. 안정 상태에서 relocation binding의 게시 action·obligation·live source 및 graph-only action support를 mutation 없이 확인한다. 첫 수정 빌드의 함수 호출 `FED/FOUT` 116좌표와 `gnmf` P-only 288개가 최종 빌드에서 제거됐다.
- **증거:** `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-p-matrix-publication-final-v1/{matrix.json,verification.json,resume-verification.json}`. Manifest SHA `13babb159f6a6dd10c35de481da68d99298d1268d26a6ed8026f7aea4e4fd1c7`, verification SHA `440ea0c299db9a29989f9bb35fb4cb1d8db6bbb4aef58fd682597f2ea425c6c9`; 224 receipt의 bytes·mtime이 resume 후 동일했다. 동결 LM/3·gnmf/3 LAN 최종 P/E 물리 집합은 각각 60/60·288/288, 양방향 차집합 0/0, offline recheck PASS다. 독립 함수 호출 배치 스캔은 1,232개 전부 CP/LOUT이다.
- **gate:** `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/gate-matrix-import-publication-final-v1/preflight.json`에서 `pMatrixStatus=PASS`, `verifiedPModelCells=224`, `pMatrixFailures=[]`이나 전체 exit 2/`INCOMPLETE`다. 전체 P/E 입력 캡처 283행, 실제 조건 미해결 placeholder 59행, 역사 비교 미분류 1,698 pair, campaign manifest 부재를 분리해 유지한다.
- **성능/잔여:** 최종 캡처 중앙값 11.60초, p95 208.17초, 최대 logreg w1/WAN light 831.37초로 1,800초 상한 안에 완료됐다. P acceptance predicate의 독립 재검사, 모든 workload의 양방향 exact P/E 물리 관계, historical realization/layout decoder, runtime R은 미완료다.

## 최종 모델 graph 관계와 조건 후보 감사

- **상태:** 최종 source/class hash에 묶인 P1 worker1·worker3와 CP/FOUT logreg graph-only 인증서를 생성하고 별도 프로세스에서 전수 component 검증했다. 세 모델 SHA가 최종 224셀 matrix receipt의 artifact SHA와 각각 일치한다. accepted P/E나 runtime R 판정은 아니다.
- **증거:** `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/p-graph-certificate-final-v1/summary.json` SHA `05c30ef2865e8e5c00d0d0b62891210edbfe6041e46c91f9a714b7b37879a8d9`. 범위 `GRAPH_CONSTRAINT_PLACEMENT_ONLY_ACCEPTANCE_OPEN`.
- **분모 감사:** `audit_condition_expansion.py`가 59개 미해결 placeholder의 source hash와 원본 registry 상수·discovery ID를 대조했다. 224개 동결 행 + 388개 미확정 축 전개 후보 = 중복 제거·입력 검증 전 612개 후보 행을 보존한다. `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/condition-candidate-audit-v1.json` SHA `8f0dfbfaabdf2482702462d167cd9f54e1eed55998956ec5c99332c1566dd196`; 독립 `--check`와 새 단위 테스트 1개 통과. `REGISTRY_AXIS_INVENTORY_ONLY`이므로 확정 분모의 하한 또는 유효 native 입력 수로 승격하지 않는다.

## 최종 빌드 bounded P/E 36셀 실행과 재검사

- **상태:** 최종 source/class hash에 속한 planning 셀 중 P state ≤1,000인 36셀을 선택했다. LM·gnmf worker 3/5/7 × 네 network 24셀은 모두 P/E 물리 identity 차집합 0/0, 별도 프로세스 offline 재검사 `PASS`였다. Pca worker 3/5/7 × 네 network 12셀은 E raw `2,057,529,600` > 예산 1,000,000으로 `INCOMPLETE`; 불가능한 plan으로 분류하지 않았다.
- **증거:** `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/bounded-pe-matrix-publication-final-v1/{selection.json,summary.json,offline-summary.json}`. 각각 SHA `0fa2f2c2c9b3f5c6f4d2c1e609a0cabbab56b2760f8da641867a1467d5b5742c`, `4a010013b23c26faa2812f728979e814a036dd3ee9d825e1d278e99d07fd7915`, `52b5e5395b9da644ed12061d7d667b62eb937b529584f2e10969f240826a207b`. 24개 certificate는 모두 최종 source/class hash와 결속되고 독립 verifier의 핵심 수치가 producer output과 일치한다.
- **재사용 경로:** `run_bounded_pe_matrix.py`는 224 P 구조 matrix를 검증한 뒤 bounded 셀을 선택하고 병렬 P/E 실행과 별도 offline verifier를 한 명령으로 연결한다. 최종 matrix dry-run은 36개를 선택했고 LM/3 단일 셀 end-to-end 및 warm `--resume` smoke는 모두 `PASS_BOUNDED`였다. warm 실행 후 14개 native 파일의 bytes·mtime과 summary hash가 유지됐다 (`bounded-pe-runner-smoke/resume-verification.json` SHA `85ba00770cdf9bd60f61a4bfe28b3fb8223109cf752198242f690380c6f7c459`). 새 Python 단위 테스트 2개는 선택 범위·재개 시 offline 재검사를 확인한다. 전체 36셀의 신규 배치기 재실행은 아직 하지 않았으며, 기존 병렬 셀 runner와 별도 verifier에서 나온 위 36셀 증거를 보존한다.
- **실행기 복구:** 최초 일회성 offline 감시 코드가 예약 상태 파일을 결과 파일로 읽어 중단됐고, 이미 큐에 든 중복 재검사가 계속됐다. 수정된 감시에서 남은 셀을 완료하고 중복 프로세스를 종료했다. 전체 24개 offline `PASS` 영수증의 SHA가 집계 파일과 일치함을 재확인했으며, 중단된 작업의 임시 정렬 디렉터리 두 개만 정리했다. 재사용 배치 명령은 별도 소스·테스트로 고정했다.
- **잔여:** planning 200셀의 P/E exact relation, placeholder 59개의 조건/입력 확정, B0/B1 1,698 pair, runtime 의미론 R는 계속 미완료다. 전체 gate는 exit 2를 유지한다.
