# 2026-10-13 세션 이슈

## FedPlanner 단계별 동작과 병목 설명 문서화 — 작성 완료

- **증상/요청**: 사용자가 조합 전개, 중복 처리, 여러 HOP에 걸친 의존성 확인 중 무엇이 실제 병목인지 이해할 수 있도록 설명 문서를 요청했다.
- **코드/측정 기준**: 코드 `33384c0054becbfefba6a4e51a5de7aefe745988`; 성능은 v41 COFEE 50K×128 W1 정상 실행. 최신 v43 실측과 혼동하지 않는다.
- **원인 분석**: Oracle execution region의 candidate별 재열거, 지원 family의 제한, native proof DFS와 gate 재사용 제외, Closure worklist의 후보 재생성, Physical Model의 입력·clause 재전개가 코드에 존재한다. 정상 지표에서 많은 누적 proof 소비·객체 생성과 PlacementAnalysis 시간이 확인된다.
- **해결/변경 요약**: 단계별 설명, 예시, 코드 링크, 복잡도의 조건, 실측표, 확인 사실과 미확인 범위를 별도 보고서로 작성했다. 알고리즘·oracle·runtime 동작을 수정하지 않았다.
- **수정 파일**: `docs/FEDPLANNER_ALGORITHM_AND_BOTTLENECKS_2026-10-13_KO.md`, 이 세션 기록.
- **검증 방법/결과**: 기준 커밋과 비교 artifact SHA-256, 두 workload 표의 수치를 대조했다. Repository 상대 링크 23개와 해당 줄 번호의 존재, Markdown fence 짝을 확인했다. 첫 공백 검사에서 Markdown 줄바꿈용 trailing space 2건을 발견해 빈 줄로 바꿨다. 문서만 추가하므로 실행 테스트를 새로 수행하지 않았다. 문서 검증은 성능 목표 달성 증거가 아니다.
- **잔여 이슈**: 최초 문서 작성 당시 v43 측정은 미완료였다. 아래 후속 측정으로 갱신했다. Native gate 개선, end-to-end relation 유지와 20초 목표는 별도로 검증해야 한다. 모든 방문을 불필요한 중복으로 분류할 수는 없다.
- **잠재 회귀 위험/감지**: 코드 동작 회귀는 없으며, 누적 작업량을 고유 합법 후보 수로 오해하거나 예시 복잡도를 실제 알고리즘의 보편적 복잡도로 오해할 수 있다. 문서에 각 수치와 예시의 범위를 명시했다.
- **의사결정 근거**: source/action/proof 및 공유 비용 의미를 유지해야 한다는 기존 계약을 바탕으로 설명했다. 합법성 완화나 후보 임의 삭제를 해결책으로 제시하지 않았다.

## v43 실제 COFEE 측정 — 정확성 통과, 성능 목표 실패

- **기준**: 커밋 `33384c0054`, frozen JAR SHA-256 `9862117114b2746f579c5b504f441a3a46e8370f14907662cf47a80573e29eb8`. 기존 Docker harness와 COFEE 50K×128 W1 조건 유지.
- **결과**: 전체 초기 플래닝 LogReg 388.771218709초, GLM 121.376056740초. v41 대비 각각 +21.305282667초, −2.734416632초. 각 1회 관측이며 인과관계·일관된 개선을 주장하지 않는다.
- **동등성**: numeric comparator PASS, audit mismatch 0, 원문 선택·section·objective raw bits·certificate·Cost Surface fingerprint 일치. 모든 assignment의 비용 전수 동등성은 미입증이다.
- **작업량**: LogReg proof 소비·graph 수·rule fact 생성 불변. Skeleton 재사용 약 371만 회와 clause 객체 1,536개 감소만으로 전체 작업량 문제를 해결하지 못했다.
- **후속 조치**: 기존 성능 evaluator에 FAIL checkpoint 기록. 변경 없는 physical consumer 재생성과 root에 독립적인 native gate의 반복 생성·방문을 줄이는 변경을 별도로 구현·검증 중이다.
- **증거**: `evidence/cofee-50k128-v43-validation/v41-v43-comparison.json`, SHA-256 `e8070a6df3e19ed0fd5f018d4141ff8c307a47d4ab6ecf1c1b3a7c07e430003a`.

## Native gate 공유와 physical no-change frontier — 병합 전 검증 통과

- **원인**: 질의 root가 달라도 동일 relation의 입력 gate와 의존성을 다시 만들었다. Physical closure는 동일 snapshot의 변하지 않은 consumer를 여러 dirty input edge에서 반복 재생성했다.
- **변경**: 정확한 fact/realization/relation identity 및 witness/pivot/axis가 같은 gate를 기존 bounded component summary와 함께 보관·퇴거한다. 전체 source 및 숨은 metadata footprint가 현재 root에 의존하면 summary를 재사용하지 않는다. Revision 간 gate 이동은 금지한다. Physical frontier는 호출 내부에서만 유지하며 모든 authority commit에서 초기화한다.
- **실제 생성량 검증**: 두 dirty input의 동일 consumer에서 keys 4→2, facts 28→14, clauses 20→10. 폭 256 gate의 후속 질의에서 생성 alternative 512→256, 의존성 방문 1,028→516. 논리적 후보는 동일하다. 이 작은 fixture의 수치를 COFEE 성능으로 일반화하지 않는다.
- **반례**: 중간 source 변경 후 frontier 초기화를 제거한 복사 빌드는 FULL/ROW가 되어야 할 consumer에 FULL/FULL을 남겨 실패했다. 초기화가 있는 버전은 전체 nodes/keys/facts/source/proof 비교를 통과했다.
- **검증**: 선택된 FedPlanner 회귀 1,453 PASS / 186.537초. 실행 중 class/resource 변경 0, source mismatch 0. 별도 gate 반례 복사 빌드 156 PASS / 기존 ignore 1: 값은 같지만 identity가 다른 fact/realization/relation의 gate 차용 금지, revision 후 gate 미이월 및 cold 결과 일치. Metrics on/off parity 및 독립 source review CLEAR.
- **미완료**: 병합 전 결과다. 새 origin/main의 CP family closure 수정과 통합한 뒤 재검증하고 실제 COFEE를 실행한다. 전체 candidate/Physical Model/DP가 완전히 relation-native가 됐다는 주장은 하지 않는다.
