# Session issues — 2026-09-20

## G009 현재 알고리즘 설명 및 성능 경계 정정

- **상태**: 설명 문서 작성 완료. 최적화 구현·공식 성능 qualification은 이 문서 작업의 범위 밖이며 완료 처리하지 않음.
- **환경/조건**: `systemds-g009-integration`, HEAD `e29f4fc30d19cc228b013e786d76424cb7954d7e` + 기존 미커밋 P5 변경. 마지막 완료 host 진단은 P5a r8.
- **문제 정의/증상**: P1–P5 요약만으로 실제 자료구조·고정점·proof·DP 소비를 판단하기 어려우며, owner-level delta와 tuple-delta, analysis factorization과 DP 전체 factorization, build-only와 production E2E가 혼동될 수 있음.
- **원인 분석**: 여러 closure의 합성, 물리 재구축 후 native binding 재증명, 구조 비교 실패 시 flat export, DP model의 supportClauses 소비 경계가 간략 보고에서 생략됨. metrics JSON은 snapshot export 뒤 채집되므로 일부 누적 카운터를 build-only 작업량으로 볼 수 없음.
- **해결/변경 요약**: `G009_CURRENT_ALGORITHM_EXPLAINED_2026-09-20.md`에 입력→oracle→closure→proof→factorized relation→DP→receipt/application을 문서화. 소스 사실·측정·추론·미확정 구분. P4의 DP 소비까지 완료했다는 표현과 P3 단독 speedup 해석을 정정.
- **수정 파일**: 위 신규 설명서와 이 신규 세션 기록. 이번 작업에서 production/test 파일은 수정하지 않음; 이미 있던 P5 변경은 유지.
- **검증 방법/결과**: 현재 소스/기존 diff, P4/P5a metrics 및 timing 대조. proof/cache와 consumer/metrics의 분리된 읽기 전용 검토 후 direct-evaluation 완료 조건, topology key, receipt-group eager 구성 표현을 수정. 상대 링크 존재·fence pairing·문서 whitespace와 diff 검증. 신규 Java 테스트나 성능 실험은 실행하지 않음.
- **재현/근거 경로**: `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-p5-closure-diagnostic-20260920-r8/metrics.json`; P3/P4 경로 및 관련 소스/메서드는 설명서 §15.
- **잔여 이슈**: 최신 미커밋 hash 변경은 마지막 245.047초 측정과 구분해야 함. production E2E baseline·60초 달성 여부, equality fallback의 시간 비중, DP model의 실제 비용은 본 진단으로 확정 불가. `nextRevision`의 포괄적 Javadoc과 completedSupportMemo 조건부 이전 코드 사이의 표현 불일치는 문서에서 설명했으며 코드는 수정하지 않음.
- **잠재 회귀 위험/감지**: 문서 수식이 일반 Boolean LFP 또는 항상 독립적인 Cartesian 곱으로 오해되면 후보 추가/삭제를 초래할 수 있음. cyclic grounding·correlated products·FULL/DELTA/SHADOW·snapshot 회귀를 확인해야 함. 소스 행 번호는 이후 변경되므로 메서드명으로 다시 대조.
- **의사결정 근거/원칙**: 사용자 판단을 위한 현재 알고리즘 설명이 목적. 후보 축소·runtime fallback·privacy/TR/TW 완화 없음. host build 진단을 공식 동일-Docker E2E 성능 인증으로 확대하지 않음.

## G009 60초 개선 계획 개정

- **상태**: 계획 작성 완료; 이번 요청에서 production 구현·새 성능 실험 없음.
- **문제 정의/증상**: 기존 개선 순서는 coarse phase 이름 중심이었고 relation equality의 flat export, physical rebuild에 의한 binding 손실, DP consumer의 full expansion 및 ordinal/rank 계약을 충분히 반영하지 못함.
- **원인/근거**: 현재 알고리즘 문서와 소스 E1–E9. iterator/lazy만 도입해도 local optimizer가 전체 state domain을 방문하며, raw ordinal과 legacy Alternative ordinal이 다름. time-bounded optimizer는 표현/속도 변경만으로 탐색 결과가 달라질 수 있음.
- **해결 요약**: R0 경계/기존 diff 구분 → R1 owner/index 반복 제거 → R2 relation 유지·정확한 변경 전달 → R3 no-change physical authority 보존 → 조건부 R4 topology lifetime/R5 factor-aware consumer → R6 고정 Docker 검증으로 개정. 60초 absolute와 기존 10배 ratio·memory gate를 분리.
- **수정 파일**: `docs/G009_REVISED_60S_OPTIMIZATION_PLAN_2026-09-20.md`, `.omx/plans/g009-revised-60s-optimization-20260920.md`, 이 기록.
- **검증**: 기존 계획/측정/소스 대조 및 consumer/harness의 읽기 전용 경계 검토. 상대 링크·문서 fence/whitespace·수식 재계산. 새 test/benchmark 실행 없음.
- **잔여 이슈**: 현재 revision에 맞는 GLM immutable stage/실제 Docker 실행 검증, 최신 hash의 성능, production phase 시간 미확정. timed optimizer의 실제 선택 parity와 더 많은 탐색의 충돌은 자동 계약 완화 없이 별도 게이트로 남김.
- **잠재 회귀/감지**: structural mismatch를 semantic inequality로 오판, stale authority/negative footprint, raw ordinal 치환, double 합산 순서 변화. finite full/SHADOW, exact decoder 양방향 포함, canonical objective bits, seed/final selection parity로 검증하도록 계획.
- **의사결정 근거/원칙**: 계측 인프라 확장보다 확인된 중복 제거를 우선. 기존 harness 재사용; 추가 후보 축소·runtime fallback·정책/자원 완화 없음. durable goal 상태는 변경하지 않음.
