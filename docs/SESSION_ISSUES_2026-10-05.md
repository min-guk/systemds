# 세션 이슈 — 2026-10-05

## R61: AggLocal 반복 경로 분석 경량화 — 구현·집중 검증 완료

- **증상/원인**: CFG local-phi edge가 추가될 때마다 모든 seed 경로를 다시 추적하고, 같은 edge에서 candidate/relocation 전체 목록을 반복 검색했다. adapter에도 marker×node 및 assignment×occurrence 전체 스캔이 있었다.
- **해결/변경**: seed별 증거를 보존하는 공유 event worklist와 all-writer phi subscriber, consumer/source별 index, nested/ordinary 분리 cache를 적용했다. adapter는 equality-key/Hop-identity 의미를 보존하는 일회성 index로 대체했다.
- **의사결정 근거**: V5 정책과 전체 공통 후보/Oracle/runtime/privacy/TR-TW 계약은 유지하고 정책 metadata 계산만 경량화한다. seed 철회 후에는 cyclic proof가 남지 않도록 fresh 최소 고정점이 필요하므로 해당 재시작은 유지한다. constructor authority와 결합된 metadata lazy 생성은 별도로 남겼다.
- **수정 파일**: `PlacementRelationClosure.java`, `HeuristicPlacementAdapter.java`, 신규 `HeuristicPathIncrementalTest.java`, `HeuristicPlacementAdapterIdentityIndexTest.java`. 상세 보고서 `AGGLOCAL_INCREMENTAL_ANALYSIS_2026-10-05_KO.md`.
- **검증**: 수정 전19 PASS → 최종25 PASS, 변경 production/test javac lint 경고0. 고정 preimage의 기존 알고리즘과 동일 입력의5사례 경로 record가 모두 일치한다. 연속 phi 사례 trace3→1, 간선 방문104→44. runtime 개선율/전체 single-pass 주장은 하지 않는다.
- **검증 중 수정**: mixed-phi fixture의 Y privacy를 의도된 PUBLIC 조건으로 조정(X는 보호 유지); adapter 초안의 key identity 강화 오류를 기존 equals 의미로 복구했다. 두 문제를 production legality 완화로 해결하지 않았다.
- **잔여 이슈**: 추가 합성 loop-update/shared-formal 조건에서 경로 분석 전 공통 closure 실패가 발견됐고, 변경 전 R59 JAR에서도 동일 메시지를 재현했다. 별도 기존 버그로 남기며 PASS에 포함하지 않는다. seed-set 철회 재폐쇄, 공통 후보 생성 비용, metadata eager 계산도 남아 있다.
- **잠재 회귀/탐지**: phi의 일부 writer만 인정하거나 철회된 nested seed의 LOUT cache를 재사용하면 잘못된 local 증명이 된다. all-writer/nested-withdrawal 회귀와 differential evidence로 감지한다.
- **근거/범위**: `/grid/3/cofee-lm-sweep-mchoi-20260914/agglocal-incremental-r61-20261005/evidence/`. main target 및 기존 실험 JAR 보존. 원격 profiling/runtime 실험, 전체 빌드·배포·commit/push 없음. 충분한 focused 검증 뒤 종료한다.

### R61 origin/main 게시 검증

- **문제/해결**: 원본 HEAD는 R33이고 다른 미커밋 작업이 함께 남아 있으므로 전체 working tree를 커밋하지 않았다. 최신 `origin/main` R51 (`ff203f4cc2`)에서 별도 worktree를 만들고 R61 변경만 적용했다.
- **범위**: production 2개·신규 테스트 2개·보고서·본 R61 기록만 포함한다. 기존 R52–R60 비용/자동 profile 작업, 원본 index/HEAD/target은 보존한다.
- **검증**: R51 JAR에 게시 production 소스를 overlay하고 helper와 테스트를 새로 컴파일하여 **25 PASS**. production/test lint 경고·오류 0, whitespace PASS. 이전 R59 기반 검증을 게시 기준점의 검증으로 혼용하지 않는다.
- **잔여/회귀 위험**: 위 기존 함수/루프 경계 문제와 미측정 runtime 성능은 그대로 남는다. broad validation·새 원격 실험은 추가하지 않는다.
