# Optimizer 반복 분석 제거 구현 계획

기준 production commit: 25662ee7b4. 승인된 설계: docs/FEDPLANNER_OPTIMIZER_PROFILE_AND_IMPROVEMENT_DESIGN_2026-10-14_KO.md.

1. 기존 frozen engine을 유지하고 Docker LogReg/GLM before 각1회 실행한다. after는 최종 검증한 엔진으로 각1회만 실행한다. 측정은 동일 non-JFR 조건이며 기존 JFR은 병목 근거다.
2. 생산 변경 전 기존 reference scan/proof behavior를 테스트로 잠근다. A: frozen guard-phase index, canonical guard decision 재사용. B: full-context 완료 shape proof 재사용. 별도 파일 소유권으로 병렬 구현하고 writer/reviewer를 분리한다.
3. C: 기존 DIRECT/IMPOSSIBLE gate 뒤의 비용 projection 및 legality 입력 구조 재사용을 검토·구현한다. 이미 B에서 제거되는 중복은 중복 abstraction으로 다시 감싸지 않는다. Full context identity/unknown/diagnostic/primitive order는 보존한다.
4. D: 작은 cache lookup probe 등 동일 semantic key 유지로 비용을 줄이는 최소 변경을 적용한다. E: 정규화 전략 교체는 낮은 측정 비중과 branch-order 위험 때문에 이번에 도입하지 않는다.
5. targeted regression, full existing Memo suite(알려진 stale inventory 제외를 명시), compile/harness regression, 독립 정확성 review를 통과시킨다. 모든 cache는 bounded/model-local, 실패/UNKNOWN 의미와 원본 selected-cost audit를 보존한다.
6. before/after exact objective, selected supply/runtime/numeric parity, rules/nodes/check/cost counts, 전체 planning/optimizer 시간을 비교한다. GLM timeout은 완료/최적성 증거로 표시하지 않는다. 결과와 잔여 병목을 문서화한다.

## A. frozen facts lookup

- Production 소유: `MemoProgramFacts.java`. 테스트 소유: 신규 `MemoProgramFactsIndexTest.java`.
- 먼저 frozen baseline classpath에서 선형 `guardPhases`/`guardValue`, canonical alias·inversion·frame,
  `occurrenceContexts` 재조회 결과를 reference regression으로 고정한다.
- `MemoProgramFacts` 생성자의 `List.copyOf` 이후에만 등록 순서를 보존하는 loop/guard-phase index를
  만들고, exact syntactic `(guardId, callFrame)`의 canonical decision을 model-local cache에서 재사용한다.
- `occurrenceContexts` early computation은 추가하지 않고, 유한 occurrence id를 key로 하는 immutable lazy result만 재사용한다.
