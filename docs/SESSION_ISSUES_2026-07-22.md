# Session Issues — 2026-07-22

## P4 exact planner-to-emission authority integration

- **상태**: 진행중
- **환경/조건**: DP 우선, public privacy 테스트 제외, approved v6/v7 production plans, no Docker. Worker worktree `worker-2`; Maven target is the preserved `/dev/shm` symlink.
- **재현 절차**: `mvn -q -DskipITs -Dtest=org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.CampaignBG014ProgramDynamicAuthorityParityRedTest test`; MinST gate is the three-class command recorded below.
- **관측 증상**: 기존 planner roots가 exact `CompiledHopKey -> PlacementState`/derived/LOCAL authority를 transaction에 완전하게 전달하지 못했다. 구현 후 B-21은 먼저 synthetic boundary exact identity에서, 이후 `PLACEMENT_ANALYSIS_PROGRAM_STRUCTURE_CHANGED`에서 실패했다.
- **원인 분석**: DP memo/selected traversal이 Hop-ID/tuple 중심이었고, MinST projector도 tuple/shared-Hop inference를 사용했다. Transaction에는 derived bit, LOCAL action, complete dynamic replacement, all-registry rollback이 없었다. 추가로 named-function synthetic boundaries는 normalized decision authority이지만 독립 compiled Hop mutation owner가 아니다.
- **해결 요약**: `PlacementEmissionState` 및 exact normalized authority를 도입하고, canonical hash/transaction rollback/dynamic replacement를 확장했다. DP candidate catalog→FedPlan→exact occurrence map, MinST exact selection direct handoff, 네 planner root의 normalized/emission receipts를 연결했다. DP/MinST synthetic boundary는 정확히 하나의 incoming `CONJUNCTIVE` source에서 동일 `PlacementState` 객체를 topological하게 전달하며 DP derived bit도 보존한다. Transaction은 semantic-only boundaries를 검증/해시에 포함하지만 Hop writes에서 제외한다.
- **수정 파일**: v6/v7 allowlist의 placement adapters, DP/MinST/root classes, `PlacementEmissionTransaction.java`, `NeutralPlacementGraphBuilder.java` 등. 최종 diff는 완료 전 재검증 필요.
- **검증**: transaction/same-Hop/derived 16/16 GREEN; Task33 LOCAL 5/5 GREEN; MinST 17/17 GREEN at combined-boundary experiment. B-21은 현재 아래 DMLTranslator ordering blocker 때문에 RED.
- **잔여 이슈**: approved structural-scope amendment가 아직 review 중이다. 승인 후 DMLTranslator ordering과 canonical boundary classification을 적용하고 v6/v7 전체 gate를 실행해야 한다. `CampaignBDpSharedAnalysisOwnerContractTest`에는 tracked architecture-guard SHA와 frozen expected SHA 불일치도 별도 확인이 필요하다.
- **잠재 회귀 위험**: synthetic boundary를 physical Hop write로 다시 취급하면 same-Hop conflict가 재발한다. B-21/B07, same-Hop transaction tests, exact selected-key totality로 감지한다.
- **의사결정 근거**: planner/analysis/transaction authority를 수정했다. runtime fallback/repair 및 tuple reconstruction은 추가하지 않았다.
- **적용 원칙/제약**: runtime fallback 금지; exact analysis-owned identity; synthetic boundary는 semantic authority이나 fabricated carrier가 아님; `CONJUNCTIVE`만 placement authority.

## Synthetic function-boundary state identity loss

- **상태**: 해결
- **환경/조건**: DP B-21 named function call, MinST B07.
- **재현 절차**: B-21 command above. 기존 실패 메시지: `DP synthetic boundary did not retain its exact source state identity`.
- **관측 증상**: boundary legal set에 source와 value-equal한 CP/LOUT은 있었지만 source 객체 identity는 없었다.
- **원인 분석**: `NeutralPlacementGraphBuilder.transientAlternatives`가 새 default CP/LOUT을 먼저 `TreeSet`에 넣어 이후 source-owned equal state를 버렸다.
- **해결 요약**: approved v7에 따라 legal source alternatives를 먼저 삽입하고 default CP/LOUT을 마지막에 넣었다. value set/order는 동일하고 first exact object identity만 보존된다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java`.
- **검증**: B-21 exact-identity guard를 통과해 다음 structural fingerprint guard까지 진행; MinST boundary exact identity guard도 통과.
- **잔여 이슈**: 없음. 이후 실패는 별도 ordering/classification 원인이다.
- **잠재 회귀 위험**: insertion 순서를 되돌리거나 state를 재구성하면 identity가 다시 손실된다. B-21/B07 exact identity assertions로 감지한다.
- **의사결정 근거**: planner-neutral graph builder identity 보존을 수정; legality/candidate set은 변경하지 않음.
- **적용 원칙/제약**: candidate-space 축소 금지; tuple inference 금지.

## Final-hop analysis capture precedes mutating FunctionCallGraph traversal

- **상태**: 진행중
- **환경/조건**: DMLTranslator final-hop federated planner entry, DP B-21/shared-owner fixtures.
- **재현 절차**: B-21 command; log `/tmp/worker2-b21-v7-1784724835.log`, SHA `a450356104b126d59f1b5a094f585bbd98031b26a8eb0316c500e48792cdf0ae`.
- **관측 증상**: `PLACEMENT_ANALYSIS_PROGRAM_STRUCTURE_CHANGED` before transaction mutation.
- **원인 분석**: `DMLTranslator.runFederatedPlannerAtFinalHopBoundary` binds analysis, then constructs `FunctionCallGraph`. FCG resets/sets Hop visit flags, while `PlacementGraphFingerprint` intentionally hashes `hop.isVisited()`.
- **해결 요약**: 승인 대기 중. 최소 수정은 같은 synchronized block에서 FCG를 먼저 생성한 후 analysis를 bind하고 둘을 그대로 planner에 전달하는 순서 변경이다. fingerprint/guard는 완화하지 않는다.
- **수정 파일**: 예정 `src/main/java/org/apache/sysds/parser/DMLTranslator.java`.
- **검증**: Task54 read-only trace로 exact mutation owner 확인. 승인 후 B-21 2/2와 shared-owner suite 재실행 필요.
- **잔여 이슈**: scope amendment 독립 승인 필요.
- **잠재 회귀 위험**: analysis bind 뒤 다른 compiler traversal이 Hop fields를 바꾸면 동일 guard가 재발한다. final-boundary tests와 fingerprint guard로 감지한다.
- **의사결정 근거**: compiler ordering 수정 예정; guard/runtime/planner fallback은 수정하지 않음.
- **적용 원칙/제약**: structure guard weakening 금지; post-bind reset workaround 금지.

## Compiled-occurrence classification has two boundary signals

- **상태**: 진행중
- **환경/조건**: MinST B07 named function, transaction Hop-write grouping, compiled input-edge facts.
- **재현 절차**: region-only experiment log `/tmp/worker2-minst-region-boundary-1784725281.log`, SHA `27d124ab03bf676d4b03fa901f48fcaefb0b5c9a042560e3edd2cf26d80a4de3`; combined experiment GREEN log SHA `9fdf2c551c997edd5c63a4e30385f00628d539b8b5b27cd079a510fa29f24953`.
- **관측 증상**: kind-only classification can miss region-marked synthetic projections; region-only classification misclassifies a `FUNCTION_INPUT` whose region path is `[main/1,input-0]`, producing `MINST_OCCURRENCE_PATH_UNPROVEN`.
- **원인 분석**: NodeKind and `function-boundary:` control-region marker are independent semantic signals. PlacementAnalysis and NeutralPlacementGraphBuilder also duplicate region-only helpers.
- **해결 요약**: 승인 대기 중. Canonical rule must be: compiled iff kind is neither FUNCTION_INPUT nor FUNCTION_OUTPUT **and** no region-path component starts `function-boundary:`. One package-private graph-aware helper must serve PlacementAnalysis and builder compiled-edge derivation.
- **수정 파일**: 예정 `PlacementAnalysis.java`; already-v7-scoped `NeutralPlacementGraphBuilder.java` call-site replacement; transaction semantic-only skip already WIP.
- **검증**: combined experiment restored MinST 17/17; core 16/16 remained GREEN.
- **잔여 이슈**: corrected plan approval and final implementation/tests.
- **잠재 회귀 위험**: duplicated predicates can diverge and make builder facts disagree with PlacementAnalysis constructor re-derivation. B07 plus compiled-edge equality validation detects this.
- **의사결정 근거**: analysis ownership/classification rule 수정; conflicting compiled Hop states remain fail-closed.
- **적용 원칙/제약**: no conflicting-state coalescing, no boundary demotion, no fabricated carriers.

## LOCAL durable provenance lexical alias risk

- **상태**: 해결
- **환경/조건**: selected FED/FOUT producer with CP/LOUT consumers, inherited/absent anchors.
- **재현 절차**: `mvn -q -DskipITs -Dtest=org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.CampaignBG014LocalMaterializationAuthorityRedTest test`.
- **관측 증상**: early WIP used `fed-init:` plus lexical variable, allowing distinct value versions to alias.
- **원인 분석**: lexical name is not durable placement provenance across branch/loop/function/recompile contexts.
- **해결 요약**: shared helper returns the unique compatible analysis-owned `DurableAnchorKey.placementId`; without one unique anchor it uses deterministic full `ValueVersionKey.normalizedSignature` plus exact occurrence signature. Multiple compatible anchors fail closed. Normalizer and transaction validator use the same helper.
- **수정 파일**: `NormalizedPlannerResults.java`, `PlacementEmissionTransaction.java`.
- **검증**: Task33 LOCAL 5/5 GREEN; mismatched/tampered provenance rejects before mutation; log SHA `edc8d35623ef3b5bdbbf28491b2a7624c1b8e3502b34ec0aa1ae61ac754cb9c0`.
- **잔여 이슈**: broader branch/loop/function/recompile workload coverage remains part of related suite.
- **잠재 회귀 위험**: reverting to lexical/Hop-ID provenance causes registry aliasing. Tampered-provenance and distinct-scope/FType tests detect it.
- **의사결정 근거**: planner/transaction analysis-owned metadata rule 수정; runtime registry fallback 없음.
- **적용 원칙/제약**: anchor는 placement metadata; lexical-only/Hop-ID ownership 금지.

## B-21 fixture reaches DP with default PUBLIC privacy

- **상태**: 진행중
- **환경/조건**: v12 ordered B-21 gate after compile PASS; DP planner; `CampaignBG014HermeticPlannerFixtureFactory` does not attach a non-public privacy constraint, so `TRead X` is evaluated as `PUBLIC`.
- **재현 절차**: `mvn -q -DskipITs -Dtest=org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.CampaignBG014ProgramDynamicAuthorityParityRedTest test`; log `/tmp/worker2-v12-b21-1784726636.log`, SHA-256 `0f546fe697349cc3013e94ac073eb90f8e701402f7bfb0332aa2f802aeb8fa8f`.
- **관측 증상**: 2 tests 중 첫 테스트가 `No valid federated plan for hop 0 (TRead X) under privacy PUBLIC (LOUT candidates=false, FOUT candidates=false, allowCpLout=false, allowCpFout=false, allowFedLout=false, oracleFedFout=false, allowFedFout=false, canSatisfyFedInputs=false)`로 emission 전에 종료했고, 두 번째 테스트는 통과했다.
- **원인 분석**: 최초 메시지는 PUBLIC이지만 이전 v7 실행은 동일 fixture로 planner를 통과해 structure guard까지 도달했다. 차이는 v12에서 FCG를 bind 앞으로 옮긴 것이다. `FunctionCallGraph.rConstructFunctionCallGraph`는 모든 방문 Hop에 `setVisited()`를 호출하고 복원하지 않으며, 이후 planner traversal은 그 상태를 관측한다. 따라서 분석/계획 입력이 불완전해지고 첫 실행이 실패한다. 첫 실패 뒤 남은 global state 때문에 같은 클래스의 두 번째 테스트가 통과하는 순서 의존성도 보인다. PUBLIC은 표면 메시지이지 새 회귀의 단독 원인이 아니다.
- **해결 요약**: production/planner/runtime 수정 없이 v12 stop-on-first-failure를 준수해 중단했다. 안전한 구조 후보는 FCG 생성 직후이자 analysis bind 전 기존 `DMLTranslator.resetHopsDAGVisitStatus(dmlp)`로 traversal scratch state를 복원하는 것이지만, v12가 “sole ordering change”로 제한하므로 새 독립 승인 전 적용하지 않는다. bind 후 reset/fingerprint 완화는 금지 유지한다.
- **수정 파일**: 없음(본 세션 문서만 갱신).
- **검증**: compile PASS log `/tmp/worker2-v12-compile-1784726595.log` SHA-256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`; B-21 XML은 tests=2, errors=1, failures=0, skipped=0.
- **잔여 이슈**: v12 계획이 FCG의 persistent visit scratch-state side effect를 빠뜨렸다. bind 전 reset을 DMLTranslator ordering contract에 포함할지 독립 검토/승인이 필요하다. 이후 B-21부터 gate를 재개한다.
- **잠재 회귀 위험**: planner를 완화하면 금지된 `<CP,FOUT>` TRead/TWrite 계획이나 runtime 불가능 계획이 생성될 수 있다. TRead/TWrite consistency 및 B-21 오류 reason flags로 감지한다.
- **의사결정 근거**: 테스트 정책/fixture authority 문제이며 planner/runtime 합법성 규칙은 수정하지 않는다.
- **적용 원칙/제약**: public privacy 케이스 ignore; TRead/TWrite 최상위 제약; runtime fallback 금지; first-failure stop.

## DP exact FED/FOUT source FType parity fixes memo boundary but B-21 remains unsatisfied

- **상태**: 진행중
- **환경/조건**: DP planner, accepted v13 PRIVATE B-21 fixture, approved v14 source-FType-authority amendment, no Docker. Worker-2 HEAD before edits `e54f191194f13086b9e87e369a7cb0fd458d5749`; accepted Task72 test authority `5dd03b08d2286a1bd0240715f26d645231f941e1`.
- **재현 절차**: (1) `mvn -q -DskipITs -DskipTests test-compile`; (2) `mvn -q -DskipITs -Dtest=org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.CampaignBDpMemoOwnerContractTest test`; (3) `mvn -q -DskipITs -Dtest=org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.CampaignBG014ProgramDynamicAuthorityParityRedTest test`.
- **관측 증상**: compile과 focused memo contract 5/5는 GREEN이다. 그러나 다음 ordered B-21 gate는 2/2 실패하며 PRIVATE `TWrite A` hop 55/121에서 `oracleFedFout=true`, `allowFedFout=false`, `canSatisfyFedInputs=false`를 보고한다.
- **원인 분석**: v14가 증명한 memo 결함은 실제였다. FED/FOUT plan의 exact state FType과 plan FType mismatch를 이제 삽입 시 거부하며 source plan은 exact state의 concrete FType만 사용한다. 하지만 B-21의 새 플래그 조합은 이 수정 이후에도 downstream federated-input satisfiability가 별도 이유로 성립하지 않음을 보여준다. v14 범위에서 추가 원인은 증명되지 않았으므로 추측 수정하지 않고 별도 owner proof가 필요하다. 아래의 오래된 `B-21 fixture reaches DP with default PUBLIC privacy` 원인 설명은 v13 PRIVATE fixture와 본 실행으로 superseded되며 최종 인과로 사용하면 안 된다.
- **해결 요약**: `enumerateFederatedDataOp`가 exact source occurrence/state를 한 번만 resolve하고 non-null exact FType을 registration/`setFType`/`setCpFoutType`에 사용하도록 수정했다. Memo validation은 exact FED/FOUT에 한해서만 `exact.fType() == plan.getFType()`를 fail-closed로 요구한다. B-21 실패 후 v14 stop condition에 따라 추가 production 변경을 중단했다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`; `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpMemoTable.java`; accepted test authorities `CampaignBDpMemoOwnerContractTest.java`, `CampaignBG014HermeticPlannerFixtureFactory.java`; 본 문서.
- **검증**: compile PASS `/tmp/worker2-v14-compile-1784729608.log` SHA-256 `e3b0c44298fc1c149afbf4f8996fb92427ae41e4649b934ca495991b7852b855`; memo 5/5 GREEN `/tmp/worker2-v14-memo-green-1784729650.log` SHA-256 `edc8d35623ef3b5bdbbf28491b2a7624c1b8e3502b34ec0aa1ae61ac754cb9c0`, XML SHA-256 `df7d583437fb84275a04c67c8531659feb1a632afcc411b4a366b4539f9ae1e8`; B-21 2/2 RED `/tmp/worker2-v14-b21-green-1784729681.log` SHA-256 `e59c3d19abee15298618185e979d4464699a395ec9a3a35762be4bad9745f8fb`, XML SHA-256 `4b022ba70ed4b52f0f5f3fa39a0e14c6e558bbeeb052de914708f82afa80f4a9`.
- **잔여 이슈**: `canSatisfyFedInputs=false`의 exact owner를 별도 read-only proof로 확정해야 한다. 승인된 후에만 새 범위/수정을 적용하고 B-21부터 ordered gates를 재개한다.
- **잠재 회귀 위험**: FED/FOUT 이외 arm에 FType parity를 확장하면 logical/costing semantics를 부당하게 닫을 수 있다. Focused memo matching/mismatch tests와 기존 DP suite로 감지한다. 추가 owner proof 없이 TWrite/privacy/candidate gates를 완화하면 최상위 합법성 회귀가 발생한다.
- **의사결정 근거**: DP source/memo authority만 수정했고, B-21 잔여 실패는 planner adapter/input-satisfiability owner proof 대상으로 분리했다.
- **적용 원칙/제약**: runtime fallback 금지; TRead/TWrite `<CP,LOUT>` 또는 `<FED,FOUT>`만 허용; candidate-space 임의 축소 금지; stop-on-first-failure; exact analysis-owned FType authority.
