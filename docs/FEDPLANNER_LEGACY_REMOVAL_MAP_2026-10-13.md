# FedPlanner Legacy Removal Map — 2026-10-13

## 1. 목적과 기준

이 문서는 기존 Anchor/Proof/Closure/Physical Alternative 및 Local/Exact planner를 실제로 제거하기 전의 **삭제 지도**다. 이번 단계에서는 production/test를 삭제하거나 수정하지 않았다.

- workspace: `/home/mchoi/fedplanner-memo-20261013`
- base HEAD: `4314a345c16c20752421ce6251bbc2f366c85825` (`origin/main`에서 만든 작업공간)
- 조사 대상: 현재 미커밋 Memo 구현을 포함한 working tree
- 계수 방법: `find ... -name '*.java'`와 물리 source line 수. 주석/빈 줄을 포함하므로 변경량 추정치이며 논리 LOC가 아니다.

결론은 다음과 같다.

1. `placement/` 76개, 71,409행과 `fedCostBased/fedExact/` 32개, 27,351행은 새 Memo planner가 실행에 사용하는 계층이 아니다. 두 패키지의 **98,760행 전체가 최종 삭제 대상**이다.
2. 지금 즉시 디렉터리를 삭제할 수는 없다. 두 패키지 밖의 production 16개 파일이 old placement/exact 타입을 직접 import하고, old FedAll/Heuristic 두 planner도 같은 계층을 사용한다.
3. Memo가 재사용하는 것은 old graph/anchor/proof가 아니라 세 가지 작은 의미 커널이다: native rule registry, privacy/source utility, physical quantity cost utility. 현재 Memo-facing kernel은 old placement 타입에서 이미 분리됐으며 이 경계를 유지해야 한다.
4. old 두 패키지 아래 테스트 538개, 157,993행은 그대로 유지할 대상이 아니다. semantic contract는 Memo 테스트로 이식하고, Java object/fingerprint/pass-count/ProofKey 형태를 검증하는 테스트는 `OBSOLETE`로 삭제한다.
5. old 패키지 밖에도 legacy package를 직접 import하는 테스트/helper 106개가 있다. 따라서 source 디렉터리 두 개만 지우는 방식은 clean test compilation을 통과하지 못한다.

## 2. Production 삭제 surface

### 2.1 `placement/` 전체

| 묶음 | 클래스 수 | 행 | 최종 조치 | 포함 클래스 |
|---|---:|---:|---|---|
| Program/identity facts | 10 | 14,043 | MemoProgramFacts/MemoBindingResolver/MemoShapes/MemoPrivacy parity 후 삭제 | `PlacementAnalysis`, `PlacementProgramFacts`, `PlacementIdentity`, `PlacementAbstractShapeAnalysis`, `PlacementShapeFacts`, `PlacementPrivacyFacts`, `SinglePartitionFacts`, `OccurrenceExecutionFrequencyFacts`, `PlacementJointInputAnalysis`, `JointValueMapRelations` |
| Candidate/anchor/proof/closure | 20 | 36,738 | 전부 삭제 | `PlacementCandidateGenerator`, `PlacementCandidateRuleResolver`, `PlacementRelationClosure`, `PlacementSupportRelations`, `FactorizedSupportClauses`, `IndexedSupportClauses`, `NativeContinuitySupportClauses`, `NativePlacementContinuity`, `LogicalBoundaryRealizations`, `CandidateRuleRelation`, `CandidateSelections`, `CandidateDomainRefinement`, `CandidateFamilyRefinement`, `CandidatePrivacyClosureEvidence`, `CandidatePrivacyInputPruning`, `CpRuleFamily`, `DerivedFoutAnchorCompatibility`, `AnchorProvenanceObserver`, `AnchorProvenanceObserverFactory`, `NativeDurableProductPairObserver` |
| Neutral graph/selection | 8 | 4,713 | 전부 삭제 | `NeutralPlacementGraph`, `NeutralPlacementGraphBuilder`, `PlacementDependencyComponents`, `PlacementGraphFingerprint`, `PlacementState`, `RelocationSelections`, `LocalMaterializationSelections`, `DirectComponentScheduleCache` |
| Old cost/emission | 9 | 4,759 | 이미 이동된 중립 quantity 함수의 parity를 고정한 뒤 삭제 | `PlacementCostSemantics`, `PlacementCostSizeBounds`, `PlacementEmissionState`, `PlacementEmissionTransaction`, `PlacementPlanApplication`, `PhysicalEmissionTraceFormatter`, `ExactPlacementRegistration`, `FunctionInputTransfer`, `BranchPlacementNormalization` |
| Metrics/shadow/runtime audit | 10 | 6,905 | Memo metrics/registry 검증으로 대체 후 삭제 | `PlacementClosureDiagnostics`, `PlannerCandidateSpaceAudit`, `PlannerPipelineTiming`, `CandidateFormationTiming`, `SearchSpaceMetrics`, `PlannerRuntimeActionRegistry`, `PlannerRuntimeCapabilityAudit`, `PlannerRuntimePlacementAudit`, `PlacementShadowComparator`, `PlacementShadowCoordinator` |
| Adapter/selector | 19 | 4,251 | old planner mode 제거와 함께 삭제 | `adapter/*` 11개, `selector/*` 8개 |

`PlacementRelationClosure` 15,818행, `NativePlacementContinuity` 9,330행, `PlacementAnalysis` 6,565행만 합쳐도 31,713행이다. Memo 경로는 이 객체들을 생성한 뒤 압축하지 않으므로 이 삭제는 실제 allocation/work 제거다.

### 2.2 `fedCostBased/fedExact/` 전체

| 묶음 | 클래스 수 | 행 | 최종 조치 | 포함 클래스 |
|---|---:|---:|---|---|
| Physical model/encodings | 14 | 15,044 | 전부 삭제 | `ExactPhysicalModel`, `ExactPhysicalCostModel`, `ExactPhysicalReducedSolver`, `ExactPhysicalOptimizer`, `ExactPhysicalSelection`, `ExactPhysicalPlacementProjector`, `ExactPhysicalNativeSupplyRepresentation`, `ExactPhysicalSharedSourceEncoding`, `ExactPhysicalForcedStateAudit`, `ExactDerivedFoutAnchorEncoding`, `ExactJointAlignmentEncoding`, `ExactMaterializationActivation`, `JointPhysicalCostRows`, `PhysicalSemanticDagFingerprint` |
| Old solver/local-regional machinery | 16 | 11,953 | MemoOptimizer exhaustive parity 후 전부 삭제 | `ExactCategoricalSolver`, `LocalCategoricalOptimizer`, `LocalPhysicalOptimizer`, `IncrementalRegionalOptimizer`, `IncrementalRegionalSeed`, `SharedRegionalPreparation`, `RegionalSearchProblem`, `ExactActivationClassFactorDecomposition`, `ExactCompensatedCostSum`, `ExactDyadicCosts`, `ExactEliminationOrderPolicy`, `ExactFactorValueClasses`, `ExactFiniteSupportJoin`, `ExactHardFactorObservationDecomposition`, `PlannerResourceGuard`, `PruningAblation` |
| Planner entry points | 2 | 354 | config/factory 전환 후 삭제 | `FederatedPlanExact`, `FederatedPlanLocalCost` |

새 `MemoOptimizer`는 old dense physical model이나 categorical solver를 호출하지 않는다. old solver를 “generic utility”라는 이유로 남기면 Physical Alternative/Proof domain의 타입과 테스트가 다시 살아 있으므로 유지하지 않는다.

## 3. 삭제 전에 분리해 유지할 primitive

### 3.1 그대로 유지

| 대상 | 근거 | 조치 |
|---|---|---|
| `fedplanner/rules/{RulesApi,RulesCore,Rulesets,bridge/OracleFacade}` (4개, 8,157행) | MemoNativeRules가 `OpCaps`, `ShapeHint`, registry/facade를 직접 사용한다. placement/fedExact import가 없다. | 유지. 이름 이동도 필수 아님. Oracle semantic tests도 유지한다. |
| `FederatedCostModel` | MemoCostModel이 op/FED compute/upload/collect 물리량을 직접 호출한다 (`MemoCostModel.java:36,92-174,244-270`). | 유지. `PlacementCostSemantics` 의존은 이미 `FederatedCostSemantics`로 분리됐다. |
| `FederatedPlannerUtils.resolveFederatedSourceMetadata` | MemoSources의 source authority 취득 경로다 (`MemoSources.java:30-53`). | 유지 또는 작은 source utility로 이동. 동작 복제 금지. |
| `FederatedPlannerUtils.derivePrivacyConstraint` | MemoPrivacy의 operator privacy propagation 경로다 (`MemoPrivacy.java:36,314`). | 유지 또는 작은 privacy utility로 이동. 기존 privacy semantics 그대로 사용. |

### 3.2 old placement 타입에서 먼저 추출

| 현재 의존 | 실제 필요한 의미 | 권장 위치/결정 |
|---|---|---|
| forced-local capability predicate | WD​IVMM, non-sequence CTABLE, indexing의 검증된 forced-local capability. 현재 Memo는 `OracleFacade.supportsForcedLocalFederatedOutput`을 직접 호출한다 (`MemoNativeRules.java:209`). | **분리 완료.** `ExecPlacementPolicy`의 동명 메서드는 old caller용 delegate일 뿐이다. Memo/rules에 `ExecPlacementPolicy` 의존을 다시 만들지 말고 old planner와 함께 delegate를 삭제한다. |
| cost semantic enums/helpers | `FederatedCostModel`의 CTABLE/WDIVMM preparation, multi-return output, worker response summary; `FederatedPlannerUtils`의 forwarding weight/multi-return 판정. | **분리 완료.** 현재 두 클래스는 placement 타입 대신 `fedCostBased/commons/FederatedCostSemantics`를 사용한다. `PlacementCostSemantics`는 legacy planner/tests와 함께 삭제하고 중립 utility의 numeric parity tests를 유지한다. |
| `PlannerRuntimeCapabilityAudit` | 성공한 FED instruction의 관찰 자료는 Oracle coverage 검증에 유용하다. | 유지가 필요하면 `placement` 계획 key/hash 없이 독립 audit로 재작성. `PlannerRuntimePlacementAudit` 호출을 남긴 채 클래스만 이동하면 안 된다. 기본 삭제 결정이며, coverage 수집을 계속 쓸 때만 별도 batch로 re-home. |
| old `PlannerRuntimePlacementAudit` | old `CandidateSelectionReceipt`, `CompiledHopKey`, `NeutralPlacementGraph.NodeKind`의 end-to-end authority 검증. | 구현 그대로 유지하지 않는다. MemoPlanRegistry/PlannedSupplyStore/FEDSupply/MemoDefinition 검증으로 대체하고 삭제. |

`FederatedPlannerUtils` 자체는 source metadata, privacy derivation, Oracle fed-init metadata와 recompilation authority에 사용된다. 현재 이 클래스에는 placement package import가 없으므로 legacy 삭제 1차 범위에서 통째로 지우지 않는다. 장기적으로 source/privacy/recompile 책임을 나누는 것은 별도 정리이며 legacy 삭제의 선행조건으로 만들지 않는다.

### 3.3 별도로 삭제하거나 유지할 주변 클래스

| 대상 | 현재 참조 근거 | 결정과 시점 |
|---|---|---|
| `FederatedTypePropagator` | production/test에서 자기 자신 외 참조가 없다. | clean compile gate를 둔 독립 batch에서 즉시 삭제 가능. Memo parity와 무관하다. |
| `FederatedWorkerUtils` | `PlacementRelationClosure`만 호출한다. | closure 삭제 batch에서 같이 삭제. |
| `HopUtils` | `ExecPlacementPolicy`와 `PlacementRelationClosure`만 호출한다. | old policy/closure 삭제 batch에서 같이 삭제. |
| `RewireConstants` | `OccurrenceExecutionFrequencyFacts`만 호출한다. | old frequency facts 삭제 batch에서 같이 삭제. |
| `ExecPlacementPolicy` | old placement/exact 경로와 자기 delegate만 필요하다. Memo는 OracleFacade를 직접 사용한다. | old planner entry와 placement generator를 제거한 뒤 전부 삭제. |
| `FederatedCostModel`, `FederatedCostSemantics` | MemoCostModel의 native/movement quantity 계산 owner다. | 유지. 패키지 이름에 `fedCostBased`가 남는다는 이유로 삭제하지 않는다. 이름 이동은 별도 비기능 PR로 미룬다. |
| `FederatedPlannerLogger`, `FederatedPlannerTrace` | OracleFacade/Utils 및 recompilation 진단에서 사용한다. | 1차 삭제에서는 유지. Exact 전용 trace API만 caller 제거 후 축소한다. |
| `FederatedCompilationTimer` | `Statistics`가 공통 compile 통계를 출력한다. | 유지. old candidate/closure phase metric만 제거한다. |
| `FederatedRefedPolicy` | `Recompiler`가 아직 호출하며 old placement audit/action registry도 함께 사용한다. | 통째 삭제 금지. Memo recompile/selected-supply 계약에 필요한 부분과 heuristic/anchor repair를 먼저 구분하고, old audit/action/heuristic 부분만 단계적으로 제거한다. |

## 4. Production dependency entry points

old 두 패키지 밖에서 직접 import하는 production 파일은 현재 정확히 16개다.

| 영역 | 파일과 현재 결합 | 제거 방법 |
|---|---|---|
| Planner API/factory | `AFederatedPlanner.java`의 `PlacementAnalysis` API, `FederatedPlannerFactory.java:25-26,45-50`, `FTypes.java:24-31` | AFederatedPlanner를 receipt-only interface로 축소. `COMPILE_COST_BASED`/`COMPILE_EXACT`를 제거하거나 명시적 unsupported로 전환한 뒤 factory branch 삭제. 호환 alias로 Memo를 몰래 선택하지 않는다. |
| IPA invocation | `IPAPassRewriteFederatedPlan.java:32-34,92-118`가 Neutral graph/PlacementAnalysis/shadow session 생성 | COMPILE_MEMO direct path만 유지. shadow 비교는 historical harness로 옮기고 production 호출 제거. |
| Parser/compiler authority | `DMLProgram.java:46-92`가 `AtomicReference<PlacementAnalysis>` 보유. `DMLTranslator.java:380-483,555-769`가 candidate timing, metrics, analysis build, Exact receipt, emission transaction을 수행 | Memo receipt/transaction path가 parity gate를 통과한 뒤 old authority field와 common search-space preparation 전체 삭제. `ExactPlacementInput` branch 삭제. |
| Other planners | `FederatedPlannerFedAllMaxFedFoutSinglePass`, `FederatedPlannerFedHeuristicSinglePass`가 PlacementAnalysis/adapter/selector 사용 | **결정 필요:** 이 두 legacy planner mode도 함께 retire해야 `placement/` 전체 삭제 가능. 유지하려면 Memo-independent adapter로 별도 이식해야 하며 이는 legacy 삭제보다 큰 기능 작업이다. 권장 결정은 old four-mode compatibility를 종료하고 COMPILE_MEMO만 compiled cost planner로 유지하는 것. |
| Shared policy | `FederatedRefedPolicy`, `FederatedBranchExitNormalizer` | old planner-only authority/action 처리 제거. Memo supply/loop lowering에 필요한 순수 runtime behavior만 남긴다. Branch normalizer는 old branch placement rewrite 전용이면 클래스와 호출을 같이 삭제. |
| Cost/source utility | `FederatedPlannerUtils`, `ExecPlacementPolicy`, `FederatedCostModel` | §3의 순수 primitive 추출 후 old imports 0개를 정적 gate로 검증. |
| Lop lowering | `Dag.java:65,267,440,697,711,1446,1480` | old synthetic action key/audit hook 삭제. MemoSupplyLowering/MemoPlanRegistry 검증은 유지. |
| Runtime/audit | `ProgramBlock.java:226-323`, `FederatedData.java:229-245`, `FederatedLocalData.java:73`, `FederatedWorkerHandler.java:722,966-997`, `Statistics.java:551,1257` | old audit가 off-by-default 진단일 뿐 실행 의미 owner는 아니다. Memo runtime validation tests가 통과하면 hook/import/display/reset을 한 batch로 삭제. capability audit을 re-home할 경우 성공 관찰 hook만 새 중립 API로 교체. |

### 삭제 blocker

- **BLOCKER-L1:** FedAll/Heuristic 지원 여부가 명시적으로 결정되지 않으면 `placement/adapter`, `placement/selector`, `PlacementAnalysis`를 삭제할 수 없다.
- **RESOLVED-L2:** `FederatedCostModel`과 `FederatedPlannerUtils`는 이미 중립 `FederatedCostSemantics`를 사용한다. 다만 old `ExecPlacementPolicy` 자체가 placement 타입을 import하므로 이 클래스는 old planner와 같은 batch에서 제거해야 한다.
- **BLOCKER-L3:** DMLProgram/DMLTranslator가 old analysis authority와 Exact receipt를 public/package API로 노출하는 동안 clean main compile이 실패한다.
- **BLOCKER-L4:** runtime audit hooks와 test probes가 old receipt/key 타입을 참조한다. source 삭제 전 새 Memo emission/runtime parity gate가 필요하다.

### 4.1 Factory/config compatibility 결정

권장 결정은 **silent alias 금지 + 한 단계 fail-fast deprecation**이다.

| 설정값 | 전환 기간 동작 | 최종 동작 | 이유 |
|---|---|---|---|
| `NONE`, `RUNTIME` | 현행 유지 | 현행 유지 | compiled planner 제거와 무관한 public 실행 모드다. 기본값도 `DMLConfig.java:207`의 `RUNTIME`으로 유지한다. |
| `COMPILE_MEMO` | canonical compiled planner | canonical compiled planner | 새 계약의 유일한 compiled cost planner다. |
| `COMPILE_COST_BASED`, `COMPILE_EXACT` | enum parsing은 한 전환 기간 유지하되 factory가 “retired; use COMPILE_MEMO”로 명시적 실패 | 다음 호환성 breaking release에서 enum 삭제 | old exact/local의 worker pool, placement domain, cost semantics가 Memo canonical scope와 같다고 증명되지 않았다. Memo로 조용히 매핑하면 동일 설정의 의미가 바뀐다. |
| `COMPILE_FED_ALL_MAX_FED_FOUT_SINGLE_PASS`, `COMPILE_FED_HEURISTIC_SINGLE_PASS` | 같은 방식으로 명시적 실패 | enum 삭제 | policy 목표 자체가 Memo cost optimum과 다르므로 alias가 특히 부정확하다. |

factory 전환 PR은 네 removed value 각각에 대해 (1) 오류 메시지, (2) planner class를 생성하지 않음, (3) `COMPILE_MEMO` 정상 생성, (4) `NONE/RUNTIME` null 동작을 검사한다. 과거 XML 설정 파일은 active Memo 테스트 입력에서 제거하되 historical fixture로 보존할 수 있다. 프로젝트가 설정 호환성 전환 기간을 운영하지 않는다고 명시하면 enum과 factory branch를 같은 PR에서 바로 삭제하는 것이 더 단순하다. 어느 경우에도 removed value를 Memo에 자동 매핑하지 않는다.

## 5. Test migration map

### 5.1 수량

| 위치 | Java tests/helpers | 행 | 처리 |
|---|---:|---:|---|
| `src/test/.../fedplanner/placement` | 310 | 102,370 | semantic subset ADAPT, 나머지 OBSOLETE |
| `src/test/.../fedplanner/fedCostBased/fedExact` | 228 | 55,623 | cost/global-optimum subset ADAPT, 나머지 OBSOLETE |
| 위 두 디렉터리 밖의 direct legacy imports | 106 | 별도 계수 안 함 | compiler/runtime/fedplanning tests를 반드시 수정하거나 제거 |

외부 direct imports의 큰 묶음은 `component/federated/placement/guard`, `component/federated/placement/shadow`, `functions/federated/fedplanning`, FedAll/Heuristic와 commons cost fixtures다. import가 없는 reflection/string 기반 probes는 이 106개에 포함되지 않으므로 별도 `rg` gate가 필요하다. `scripts/fedplanner/`의 old probe class 이름과 `run_g009_planning_correctness.sh`, `run_current_pe_cell.py`, `run_joint_boundary_e2e.py`, `validate_cost_runtime_docker.py`, `run_supply_sharing_e2e.py`도 source deletion 후 깨진다. Historical `docs/experiments` 결과는 수정하지 않는다.

### 5.2 REUSE

다음은 legacy object를 검증하지 않으며 유지한다.

- `fedplanner/rules/**` Oracle rule tests.
- `fedCostBased/commons/**` 중 pure physical quantity tests. 단, import가 이동한 helper 이름만 수정한다.
- 새 `fedplanner/memo/**`: `MemoProgramFactsTest`, `MemoBindingResolverTest`, `MemoPrivacyTest`, `MemoShapesTest`, `MemoNativeRulesTest`, `MemoCostModelTest`, `MemoOptimizerTest`, `MemoProgramModelTest`, `MemoFederatedModelTest`, `MemoCompilerIntegrationTest`, `MemoPlannerFederatedRuntimeTest`, `MemoFunctionTerminationTest`, `FederatedPlanMemoTest`.
- 새 runtime/lowering tests: `MemoLoopRuntimeTest`, `PlannedSupplyStoreTest`, `MemoSupplyLoweringTest`, `CanonicalWorkerPoolTest`, `FederationReplicaReaderTest`.

### 5.3 ADAPT — 의미를 새 API로 이식

아래 old test class는 삭제 전 assertion의 의미를 Memo test로 옮기거나 이미 옮겨졌음을 대응표로 기록해야 한다.

| 의미 | old 대표 tests | 새 대응/필요 작업 |
|---|---|---|
| exact reaching definition, correlated branch | `JointInputReachingDefinitionsTest`, `PlacementJointInputEnvironmentTest`, `PlacementJointInputBulkEnvironmentTest`, `OccurrenceActivationContextTest` | `MemoProgramFactsTest`, `MemoBindingResolverTest`로 ADAPT. 2 legal branch environments, unrelated definition exclusion, terminal activation을 유지. |
| transient/value occurrence | `CompiledTransientOperationKindTest`, `LogicalTransientReplayFactMemoTest`, `FunctionBoundaryOccurrenceIdentityTest`, `InlinedFunctionInputTraceContractTest` | exact version/call-frame/compiled-owner assertions만 Memo facts/resolver로 이동. old key/hash 문자열 assertion은 버림. |
| loop grounding/entry/backedge | `LoopEntryMaterializationTest`, `LoopIdentityBackedgeClosureTest`, `LoopEntryCompletePlacementSpaceTest`, `LoopEntryRuntimeWitnessTest`, `StepLmDynamicLoopPlacementTest` | Memo loop contract의 entry transfer, DIRECT-only backedge, entry/backedge activation, exit value runtime assertions으로 ADAPT. proof snapshot/pass count는 이식하지 않음. |
| function calls/returns | `FunctionBoundaryRuntimeAliasContractTest`, `WorkerPoolAnchorResolverFunctionReturnTest`, `ExactRepeatedFunctionInputFeasibilityTest`, `ExactFunctionAliasActivationEncodingTest` | `MemoBindingResolverTest`, `MemoFunctionTerminationTest`; bound return 전체 검사, missing-return terminal, shared compiled owner/call frame을 유지. |
| privacy/movement | `EarlyPrivacyPruningLegalSpaceParityTest`, `PrivacyMovementCertificationTest`, `MixedPrivacyRelocationContractTest`, `PrivateAggregateFourPlannerContractTest`, `PrivateAggregateLeftIndexPlanningTest`, `SharedPrivacyMovementLegalityAuditTest`, `StepLmPrivateAggregatePlanningContractTest` | `MemoPrivacyTest`, `MemoProgramModelTest`, federated runtime tests로 ADAPT. PRIVATE/PRIVATE_AGGREGATE, metadata exception, collect 금지를 의미 assertion으로 유지. |
| native capability/layout | `RulesetShapeIndependentKernelContractTest`, `WeightedAggregateOracleConditionParityTest`, `WdivmmFactorAlignmentTest`, `CastAsScalarCpFamilyParityTest`, `MetadataDimensionCpFamilyParityTest`, `TrainingWorkloadOperationCoverageTest`; 외부 `functions/federated/fedplanning/*LayoutPlanningTest` | Rules tests + `MemoNativeRulesTest`/`MemoShapesTest`로 ADAPT. old CandidateRuleFact count가 아니라 rule pattern/native output/runtime dispatch를 검증. |
| cost ownership/shared materialization | `ExactActivationMaterializationCostTest`, `ExactExecutionOwnershipCostTest`, `ExactZeroFrequencyCostTest`, `MMChainCostOwnershipTest`, `CoordinatorMetadataExactCostTest`, `ExactAuxiliaryCommunicationCostTest`, `ExactCtableMaterializationCostTest`, `ExactNativeResultBatchCostTest` | `MemoCostModelTest`/`MemoProgramModelTest`에 numeric expected cost와 once-only activation을 이식. ExactPhysicalCost fingerprint/text는 이식하지 않음. |
| pruning/global optimum | `CertifiedCostPruningParityTest`, `CostBasedPruningTest`, `ExactCategoricalSolverArithmeticParityTest`, `ExactNumericBoundaryMinimaTest`, `SharedRegionalCompactionParityTest` | 작은 exhaustive oracle와 MemoOptimizer 선택/cost parity로 ADAPT. old factor/elimination order/cache counters는 버림. |
| emission/runtime | `PlacementEmissionSameHopOccurrenceRedTest`, `PlacementEmissionTransactionRedTest`, `ExactEmissionBindingIdentityTest`, `RuntimeReadLegalityParityTest`, `ExactStagedSupplyCanonicalCostTest`, `PlannerRuntimeGenerationReceiptTest` | `MemoCompilerIntegrationTest`, `MemoSupplyLoweringTest`, `MemoPlannerFederatedRuntimeTest`, W2 runtime tests로 ADAPT. 실제 instruction/supply/cleanup/numeric 결과가 기준. |
| workload regressions | `CampaignBG014*LogReg*`, `CampaignBG014*Glm*`, `CampaignBG014*L2Svm*`, `CampaignBG014*StepLm*`, `GlmProtectedTransientReplayTest`, `LogregMixedPrivacyPublicationTest`, `LogregProtectedLoopSliceTest` | 공통 canonical support 범위에서 Memo planner로 compile/runtime/numeric parity를 새 workload suite에 이식. Anchor/FOUT/LOUT 이름의 기대는 새 ROW/COL/REP/LOCAL 계약으로 다시 작성. |

### 5.4 OBSOLETE — 구현 형태만 검증

다음 범주는 semantic replacement가 준비된 뒤 class 전체를 삭제한다.

- `AnchorProvenance*`, `*DurableAnchor*`, `WorkerPoolAnchorResolver*`, `DerivedFoutAnchor*` 중 anchor/key/provenance 형태만 검사하는 tests.
- `*Proof*`, `*SupportClause*`, `NativeContinuitySupport*`, `PublicationSupportClosureTest`, `PlacementSupportDeletionWorklistTest`.
- `CandidateRuleKeyEqualityTest`, `CandidateRealizationReferenceHashTest`, `PlacementIdentity*`, `NormalizedText*`, `*Fingerprint*`처럼 old serialization/hash/object identity를 검사하는 tests.
- `NeutralPlacementGraph*`, `PlacementRelationClosure` publication/fixed-point/pass-order, `NativePlacementContinuity*`, `RelocationFactorized*`, `RelocationProductMemoTest`.
- `ExactPhysical*`, `ExactFactor*`, `ExactDyadic*`, `ExactEliminationOrder*`, `IncrementalRegional*`, `LocalPhysicalOptimizer*`의 factor representation, elimination ordering, cache lifecycle, trace 문자열 assertion.
- `SearchSpaceMetrics*`, `CandidateFormationTimingTest`, `PlannerPipelineTimingTest`, old runtime audit hash/receipt display tests.
- `placement/adapter/**`, `placement/selector/**`, component `placement/shadow/**`의 old graph comparison/selector fixtures.

`OBSOLETE`는 “테스트가 실패하므로 삭제”가 아니다. 같은 correctness invariant가 위 ADAPT/REUSE test에 존재하고, old Java representation에만 남은 assertion임을 migration PR에서 표시한 뒤 삭제한다.

### 5.5 OUT_OF_SCOPE 또는 명시적 rejection으로 전환

FedAll/Heuristic/Local/Exact mode를 retire한다면 그 planner의 ranking이나 selected anchor를 요구하는 tests는 새 Memo에 맞추지 않는다. 대신 factory/config 테스트에서 다음을 검증한다.

- 제거한 enum/config value가 명시적으로 rejected 된다.
- 제거한 class가 clean classpath에 없다.
- `COMPILE_MEMO`가 유일한 새 cost planner entry point이며 silent alias가 없다.

과거 planner 비교 결과와 benchmark JSON/Markdown은 historical artifact로 보존하고 test build input에서만 제외한다.

## 6. 권장 삭제 batches

| Batch | 변경 | 삭제량 | 선행 gate |
|---|---|---:|---|
| R0 semantic lock | §5.3 semantic tests를 Memo API/runtime assertion으로 이식. old/new 공통 지원 범위 exhaustive + LogReg/GLM 결과 보존 | 삭제 없음 | Memo compile, runtime, privacy, cost, optimum, emission parity. 현재 ML 실행 성공만으로 old test 의미 전체가 대체됐다고 보지 않음. |
| R1 primitive decoupling 확인 | 이미 이동된 `OracleFacade.supportsForcedLocalFederatedOutput`과 `FederatedCostSemantics`를 canonical owner로 고정. `FederatedTypePropagator` 제거. old `ExecPlacementPolicy`, `PlacementCostSemantics`, worker/hop/rewire helper의 caller inventory를 잠근다. | 독립 unused class 1개; 나머지는 후속 batch 삭제 준비 | `rg` 결과 Memo/rules/FederatedCostModel/FederatedPlannerUtils가 `placement` 또는 `fedExact`를 import하지 않음. commons quantity tests + MemoNativeRules/Cost/Privacy/Sources green. |
| R2 planner entry retirement | `COMPILE_COST_BASED`, `COMPILE_EXACT`와 `FederatedPlanLocalCost/Exact` factory branch 삭제. FedAll/Heuristic도 retire 결정 시 함께 제거 | 354행 + old planner classes | config rejection/classpath-absence test, COMPILE_MEMO compiler/runtime workloads green. |
| R3 compiler authority removal | DMLProgram old analysis authority, DMLTranslator common search-space build/timing/Exact receipt/emission transaction, IPA shadow coordinator 제거 | placement 외부 결합 제거 | clean main compile; recompile/function/loop Memo receipt tests; stale plan rollback tests. |
| R4 old exact engine deletion | `fedCostBased/fedExact/` 32개 전체와 implementation-only tests 삭제; semantic cost/optimum tests는 Memo 위치로 이동 | 27,351 production + 최대 55,623 test행 | small exhaustive optimum parity, deterministic tie, budget/resource result, LogReg/GLM optimizer completion. |
| R5 anchor/proof/closure deletion | placement core-analysis, candidate/closure, graph/selection, adapter/selector 삭제 | 59,745 production행 | no production import; Facts/binding/privacy/native rule exhaustive fixtures; branch/loop/function/transient tests. |
| R6 old emission/audit/metrics deletion | old cost/emission, metrics/shadow/runtime audit와 compiler/runtime hooks 삭제 | 11,664 production행 | Memo lowering/runtime W2, shared movement once-only, loop entry/backedge/exit, function termination, cleanup/lifetime tests. Optional capability recorder는 독립 package로 먼저 이동. |
| R7 test/scripts cleanup | OBSOLETE tests와 old probes/scripts 삭제 또는 historical-only 이동. ADAPT tests는 new suite 유지 | 최대 157,993 old-dir test행 + external refs | fresh testCompile에서 deleted package/class 참조 0. Historical docs/experiment outputs unchanged. |

R4 전에 R3를 수행해도 된다. MemoCostModel/MemoNativeRules/MemoPrivacy/MemoSources의 old placement 직접 의존은 이미 제거됐지만, compiler/runtime/AFederatedPlanner와 old planner entry가 남아 있으므로 placement package를 먼저 물리 삭제할 수는 없다. R5와 R6은 한 PR로 합칠 수 있으나 runtime audit hook 제거 실패를 core deletion과 섞지 않는 편이 진단하기 쉽다.

## 7. Acceptance gates

각 삭제 batch는 incremental `target/` 성공이 아니라 clean output에서 검증한다.

1. `rg` production gate:
   - `org.apache.sysds.hops.fedplanner.placement` import 0
   - `org.apache.sysds.hops.fedplanner.fedCostBased.fedExact` import 0
   - `PlacementAnalysis`, `PlacementRelationClosure`, `SupportClause`, `ProofKey`, `ExactPhysical`, `FederatedPlanLocalCost`, `FederatedPlanExact` 실행 코드 참조 0
2. clean main compile 및 clean test compilation. 삭제 class가 이전 `target/classes`에 남아 성공하는 것을 막기 위해 새 output directory 또는 `clean`을 사용한다.
3. factory/config rejection 및 classpath absence test.
4. Memo focused semantic suite: ProgramFacts, resolver, privacy, shapes, native rules, cost, optimizer, model, compiler/lowering, loop/runtime, function termination.
5. 작은 exhaustive fixture에서 feasible plan set, selected assignment, exact cost, emission schedule parity.
6. canonical support 범위 LogReg/GLM 실제 실행, numeric result, selected schedule, planning time/peak memory 기록.
7. W2 worker test: upload/direct/collect/REP, shared transfer once-only, lease/cleanup, backedge zero-transfer.
8. `git diff --check` 및 old probes를 참조하는 active script/test manifest 0.

## 8. 현재 readiness

**판정: 삭제 순서는 준비됐지만 물리 삭제는 아직 BLOCKED다.** R1의 Memo-facing primitive decoupling은 현재 소스에서 완료됐고, 다음 실질 gate는 R0 semantic lock과 §4.1 planner-mode retirement 결정의 적용이다. 특히 current Memo가 component fixture를 통과하는 것만으로 538개 old-dir tests의 semantic coverage를 대체했다고 볼 수 없다. 반대로 old Proof/Anchor object identity tests를 새 Memo 자료구조에 그대로 재현하는 것도 금지한다.

현재 보유한 evidence에는 Memo native/shape/cost/control-flow/runtime component 통과와 LogReg RuleMemo complete가 포함된다. 그러나 이 문서 작성 시점의 기록상 LogReg global optimization은 제한 시간 내 완료 증거가 없고, GLM은 model/privacy 분석 시간·메모리 문제가 남아 있다. 따라서 workload numeric parity, selected emission parity, bounded global optimum이 확인되기 전에는 R2 이후 삭제에 진입하지 않는다. 이 상태는 성능 담당 변경과 재검증에 따라 갱신해야 하며, component pass를 workload parity로 간주하지 않는다.

권장 즉시 다음 작업은 다음 두 개다.

1. §5.3 표를 migration PR별 실제 old method assertion → Memo test method 대응 matrix로 확장하고, 빠진 invariant를 먼저 추가한다.
2. 이미 분리된 `OracleFacade` forced-local predicate와 `FederatedCostSemantics`를 regression test로 고정하고, old `ExecPlacementPolicy`/`PlacementCostSemantics`를 호출하는 legacy 경로만 남았는지 clean reference gate로 확인한다.

그 뒤 R2에서 지원 planner mode를 명시적으로 확정해야 한다. FedAll/Heuristic을 유지한다는 결정과 `placement/` 전체 legacy 삭제는 동시에 만족할 수 없다.

## 9. R0 concrete semantic transfer checkpoint

This table records method-level replacement evidence; it does **not** authorize deleting all old tests. Historical implementation-shape assertions remain separate from runtime semantics.

| Old assertion / class | New exact test method(s) | Status / invariant |
|---|---|---|
| `CampaignBG011PrivacyResolverOwnerContractTest.coordinatorLocalMetadataWinsWithoutWorkerRequest`, `mountedRelativeMetadataWinsWithoutWorkerRequest` | Same method names in `component/federated/FederatedPrivacyConstraintResolverTest` | ADAPT; local mounted metadata is authoritative, zero worker request, direct typed result. Isolated PASS. |
| Same old class: `malformedMountedMetadataFailsClosedWithoutWorkerRequest` | Same name in new runtime test | ADAPT; malformed present metadata cannot be replaced by a different privacy authority. PASS. |
| Same old class: `successfulWorkerResponsePreservesRawPrivacyAndResponse`, `workerEncodesAbsentPrivacyAsExplicitPublic` | Same names in new runtime test | ADAPT; successful worker payload and exact response identity, explicit absent-privacy default. PASS. |
| Same old class: `emptyFineGrainedPrivacyRetainsOrdinaryPublicDefault`, `nonemptyFineGrainedPrivacyFailsWithTypedUnsupportedState`, `malformedFineGrainedPrivacyFailsClosed`, `remoteUnsupportedFineGrainedResponseRetainsTypedFailure` | Same names in new runtime test | ADAPT; empty ranges preserve coarse semantics; nonempty ranges explicitly unsupported, never public. This is not full fine-grained support. PASS. |
| Same old class: `nullWorkerResponseRecoversFromMetadataCreatedAfterRequest`, `successfulNullPayloadRecoversAndRetainsResponse`, `unsuccessfulWorkerResponseRecoversAndRetainsResponse`, `requestExceptionRecoversAndRetainsCause`, `unresolvedWorkerResultsRetainFailureEvidenceWithoutLocalMetadata` | Same names in new runtime test | ADAPT; retry exact local sidecar after failed request; retain typed failure, original response/cause; no fabricated privacy. PASS. |
| `JointInputReachingDefinitionsTest.oneBranchDecisionRetainsOnlyAaAndBbTuples` | `MemoBindingResolverTest.correlatedBranchHasTwoJointEnvironmentsRatherThanFour` | ADAPT; exact branch correlation, not old tuple/cache identity. v7 PASS. |
| `JointInputReachingDefinitionsTest.sameBlockSequentialReadsKeepTheirObservedDefinitionVersions` | `MemoProgramFactsTest.sequentialReadsRetainExactDefinitionVersions`, `MemoBindingResolverTest.overwriteUsesOnlyTheExactReachingDefinition` | ADAPT; exact observed version; no global variable-name rebinding. v7 PASS. |
| Old same-slice CFG-pass/count assertions | `MemoBindingResolverTest.unrelatedDefinitionsAreOutsideTheDependencyScope` + optimizer `unrelatedDomainsDoNotAppearInJointCheckScope` | REPLACE; dependency scope is semantic/performance contract; exact old CFG pass count is not. v7 PASS. |
| `ExactExecutionOwnershipCostTest` DML call placeholder / callee frequency | `MemoProgramModelTest.sharedCompiledKernelCostsDistinctValueVersionShapesExactly`, `MemoControlFlowParityTest.missingReturnTerminalEnvironmentReducesNormalContinuationCost` | PARTIAL: occurrence/termination costs covered; multi-return builtin execution assertion still needs explicit coverage audit before deleting the old class. |
| `ExactActivationMaterializationCostTest` once-only shared transfer semantics | `MemoSharedSupplyRuntimeTest.sharedUploadMatchesOriginalCostScheduleExecutionAndCleanup` | ADAPT; original cost equals exact solution; one selected upload, one real materialization plus one reuse, exact numeric output, zero live leases/pins at exit. v7 PASS. |
| Old loop entry / live-out / zero-frequency assertions | `MemoPlannerFederatedRuntimeTest.dynamicZeroOneAndManyLoopsPreserveTheActualFinalValue`, `zeroIterationSkipsASelectedEntryUploadAndOneIterationUploadsOnce` | ADAPT; real W2 T=0/1/2/3 exit values; zero iteration skips selected entry upload; no backedge movement. v7 PASS. |
| Old full source/physical-assignment product parity | `MemoExhaustiveParityTest` five parsed DML fixtures | NEW; independently enumerate small immutable native universes, test full predicates, original exact cost, deterministic optimum with/without caches. Isolated 5/5 PASS, 2,604 full assignments with caches on/off (`exhaustive-controlflow-tests.log`). This checks solver preservation, not an independent proof of predicate semantics. |

Remaining non-transferable coverage must be marked explicitly instead of claiming that a passing component count replaces all 538 historical classes. In particular non-DML multi-return native operators require a separate capability/binding/cost check; current native catalog treats FunctionOp as a program boundary and reports an open coverage gap, so QR parity must not be claimed.

## R1 execution — independent dead propagator (2026-10-13)

Cleanup plan before editing:
1. Confirm `FederatedTypePropagator` has no caller in production, tests, resources, configuration, or active scripts. `rg` finds only its own class/constructor/static self-wrapper.
2. Remove that unused legacy entry class only. Do not remove its shared handlers/utilities, active planners, or semantic tests.
3. Baseline v10 central compile, core283/283, W2 runtime10/10, Oracle/commons205/205, harness41/41 are green before deletion.
4. Verify fresh main/test compilation with the removed class absent from output at the next integrated freeze. Do not count incremental stale `target/classes` as deletion proof.

This is the independent R1 dead-code deletion, **not** authorization for R2–R7 core retirement; actual GLM and remaining semantic/runtime gates still apply.
