# FED Oracle relation-native 구현 계약

기준선은 이번 요청 시작 시점의 미커밋 상태다. HEAD e468797556에 기존 129개 변경/신규 파일을 더한 snapshot을 /grid/3/cofee-lm-sweep-mchoi-20260914/fed-oracle-native-20261008/baseline에 보존했다. 기존 변경을 삭제하거나 commit/push하지 않는다. 구현 에이전트는 weighted, unary_matrix, validation의 독립 worktree/branch에서 작업하며 root가 API와 소비자를 통합한다. 동시 실행 한도에 맞춰 inventory, family 구현, validation, 독립 review를 교대로 배치한다.

## 공통 계약

- Oracle는 입력 ordinal의 disjoint region과 정확한 capability/shape proof를 반환한다. 정방향 규칙을 재사용하며 reason/detail/notes도 의미에 포함한다.
- 기존 ShapeIndependentDecision은 유지한다. Shape-sensitive DecisionDependencies는 determinantPositions, shapeSelectorPositions, allowedShapeFacts를 갖는다. 두 position 집합의 합집합을 평가하고, 정확한 tuple에 맞는 fresh ShapeHint 공급자를 사용한다. FULL 단일 partition 조건을 연산 전체의 상수로 가정하지 않는다.
- Candidate profile 의존성은 caps 의존성과 별도로 보존한다. ABSENT_LOCAL matrix의 profile domain이 전체 FType으로 확장되는 기존 의미를 유지한다.
- Unclosed header와 closed authority를 구분한다. CandidateRuleRelation.MemberEmissions는 immutable Closure snapshot의 input-indexed binding/action/proof table만 읽는다. resolve(inputs), normalizedSignature(), storedChoiceCount()가 계약이다.
- ConditionalRegion.emissionsFor(inputs)는 정확한 member의 closed emission을 반환한다. requireExact는 이 emission을 사용해 선택된 fact를 복원한다. 초기 LOCAL empty support로 필요한 DIRECT/RELOCATION authority를 대체하지 않는다. 기존 exact 경로가 인정하는 no-action LOCAL clause는 보존한다.
- ABSENT_LOCAL은 no-binding 선택이다. PRESENT source 및 동일 owner의 support 일치, action·pool·layout·privacy 제약은 유지한다.
- 서로 다른 physical authority/cost를 대표 tuple 하나로 대체하지 않는다. 관계별 compact support는 Physical Model이 직접 소비하고, 의미가 달라 분리해야 하는 Alternative 또는 FOUT exact-source 경계는 정확한 fallback을 유지한다.

## 소유권

Root: CandidateRuleRelation, PlacementAnalysis의 공통 소비, Physical Model/Cost/DP 연결, 통합·보고. Weighted lane: PlacementCandidateGenerator, PlacementRelationClosure 및 weighted/aggregate adapter·full explicit reference seam. Unary/matrix lane: RulesApi/Core/OracleFacade 및 비weighted Rulesets 선언·Oracle parity. Validation lane: 독립 reference, Docker paired/JFR/memory peak harness. Shared interface는 위 계약대로 한 owner만 수정한다.

## 검증

Frozen explicit 기준선과 생성 단계부터 비교한다. 새 relation을 사후 전개한 것을 독립 기준선으로 삼지 않는다. Small exhaustive/fixed-seed random legality·caps·proof·privacy, source/action binding, cost raw bits, Local/Global 최적값·receipt를 확인한다. 객체 생성과 논리 cardinality를 별도 계측한다. 실제 LogReg/GLM은 동일 Docker 조건에서 3 paired run, B/C 순서 교차, 단계별 시간 및 cgroup memory.peak를 비교한다. 성능 악화는 JFR allocation profile로 조사하고 한계로 보고한다.
