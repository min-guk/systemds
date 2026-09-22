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
