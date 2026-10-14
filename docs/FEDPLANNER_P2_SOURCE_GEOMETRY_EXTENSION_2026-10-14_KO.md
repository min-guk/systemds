# P2 source geometry 지원 계약 확장 — 구현·검증 진행 중

## 변경 근거와 범위

사용자가 P2 문제 보고서에 따른 진행을 요청했다. ADR01/02의 기존 floor+remainder-front 유일 geometry는 실제 sealed P2 W3/W7 ceil-tail source를 거절한다. 거절 검사 삭제 또는 입력 재표시는 금지한다. 기존 runtime에는 Memo의 계획된 FED→FED reshard action이 없으며 DIRECT/COLLECT/UPLOAD만 있다. protected source를 coordinator에 collect하여 normalize하는 대체 경로를 도입하지 않는다.

따라서 **session별 불변 axis-length geometry profile**로 유일 map 전제를 보존하는 좁은 확장을 구현한다. 이것은 기존 ADR01/02와 같은 계약이라고 가장하지 않는 명시적 확장이다. `FEDPLANNER_P2_PLANNING_BLOCKERS_ROOT_CAUSE_2026-10-14_KO.md`의 actual-map 지원 방향을 제한된 형태로 채택한다. arbitrary per-value maps, reshard, runtime fallback은 도입하지 않는다.

## 새 계약

1. ordered worker endpoints는 종전과 같다. source마다 worker 수/순서/type/full orthogonal extent와 strict positive contiguous 전체 coverage를 검증한다. gap/overlap/빈 shard/worker subset을 허용하지 않는다.
2. 모든 source를 rules 생성 전에 수집한다. 분할 축 길이 L마다 exact cuts[W+1] 하나만 허용한다. **ROW와 COL은 같은 length key를 공유**하여 transpose 등 축 교환의 동일성 증명을 유지한다. 같은 길이에 다른 cuts가 요구되면 scope를 거절한다.
3. profile에 없는 길이는 기존 canonical allocator를 사용한다. 기존 canonical source만 있는 session의 geometry는 변하지 않는다. W1 FULL/REP 및 replication equality 계약도 그대로다.
4. source를 전부 검증한 뒤 profile을 freeze한다. 규칙 생성·cost 구성·compile/runtime 중 profile mutation은 없다. `(profile, worker order, partition, rows, cols)`는 유일한 physical map을 결정한다.
5. profile을 versioned pool descriptor에 포함하고 parse/encode roundtrip을 검증한다. POOLv1 endpoints-only는 기존 의미로 유지한다. runtime의 upload/supply/loop validation까지 같은 descriptor를 사용한다.
6. `MemoNativeRules` 인스턴스는 한 geometry에 불변 결합한다. 다른 pool/profile에 재사용하면 거절한다. 기존 Oracle canonical key authority는 유지하며 RuleBuilder에 별도 key 생성 규칙을 복제하지 않는다.
7. native output-map proof의 allocator 계산은 pool authority 한 곳을 사용한다. 특히 indexing은 입력 실제 cuts와 출력 profile cuts를 비교한다. profile을 무시한 독자 floor arithmetic을 남기지 않는다.
8. metadata/cost/selected-plan의 worker/range identity를 생략하지 않는다. 기존 declared cost primitive를 임의로 바꾸지 않고 실제 map 효과에 따른 준비/통신 비용과 ownership의 유실 여부를 검증한다.

## 회귀 및 완료 조건

- 실제 P2 N100000/W3·W7 exact ranges, W1/W5 기존 geometry 보존.
- descriptor v1/v2 roundtrip·정규화·변형/잘못된 cuts·endpoint 순서·불변성 검사.
- 동일 length의 상충하는 ROW/ROW 및 ROW/COL cuts 거절, source 수집 순서에 무관한 descriptor.
- transpose/WDIVMM/diag 등 기존 축 증명 및 source DIRECT, upload, supply/loop runtime 범위 검증.
- indexing 반례: W3 입력 N10 cuts[0,4,8,10], rows2:9는 output N8의[0,3,6,8]과 다르므로 잘못된 DIRECT/native canonical 승인을 금지.
- 한 Oracle 인스턴스의 다른 geometry 재사용 거절. context activation/privacy/coverage/UNKNOWN·DEFER 보존.
- 기존 soundness 전체회귀와 실제 P2 W1/3/5/7 fullcompile 검증. metadata 및 dummycode 비용 연결은 별도 필수 작업이다.

**현재 상태:** 계약 선택과 구현 지침이며, 이 문서만으로 구현 완료·P2 지원·정확성 증명 완료를 주장하지 않는다.
