# Matrix-kernel P-L-R 감사 보고서

작성일: 2026-08-31  
대상: `MMFEDInstruction`, `TsmmFEDInstruction`, `MMChainFEDInstruction`과 selector-visible `AggBinaryOp ba(+*)` domain

## 1. 결론

ROW/COL TSMM 및 MMChain program structure에서 selector가 공개한 직접 matrix-multiply 상태 64개를 각각 격리 JVM으로 강제 실행했다.

- P manifest 상태: **64개**
- exact constraint 적용/충족: **64/64**
- HOP→LOP→instruction→runtime 성공: **64/64**
- Spurious: **0개**
- observed confirmed Missing: **0개**
- target 누락/중복/예상 밖 결과: **0개**

후속 clean-freshness campaign에서는 MapMM/SPARK context의 나머지 12개 상태도 각각 격리 JVM으로 강제했다.

- P manifest 상태: **12개**
- exact constraint 적용/충족: **12/12**
- HOP→LOP→instruction→runtime 성공: **12/12**
- target 누락/중복/예상 밖 결과: **0개**
- retry 필요 상태: **0개**

첫 실행은 64개 중 30개가 실패했다. 이는 selector state 자체가 불법이어서가 아니라, planner-selected incoming `fed_refed`/materialization edge를 TSMM/MMChain fusion이 지워 `Dag`가 공유 transpose의 두 물리 consumer를 모호하게 관찰한 lowering bug였다. Fusion boundary 판정을 보완한 뒤 동일 64개가 모두 성공했다.

다만 현재 selector P는 `tsmm`/`mmchain`이라는 별도 opcode가 아니라 일반 `AggBinaryOp ba(+*)` state로 구성된다. Fixed campaign의 runtime capability에는 `AggregateBinaryFEDInstruction` 28개, `FEDRefedInstruction` 12개, `FEDFoutInstruction` 31개가 관찰되었고 `TsmmFEDInstruction`/`MMChainFEDInstruction`은 관찰되지 않았다. 따라서 이 결과는 **TSMM/MMChain program structure에서 selector가 공개한 generic matrix-kernel P의 soundness**를 증명하지만, specialized runtime kernel의 독립적인 R 완전 열거를 증명하지 않는다.

## 2. Fixture와 P

추가/분리한 fixture:

- `FederatedMatrixKernelLayoutPlanningTest.java`
- `FederatedMatrixKernelLayoutPlanningTestMapMM{,Reference}.dml`
- `FederatedMatrixKernelLayoutPlanningTestTsmmChain{,Reference}.dml`
- `FederatedMatrixKernelLayoutPlanningTestMMChain{,Reference}.dml`

Exact factor cap을 올리지 않고 MapMM(3 outputs), TSMM(4 outputs), MMChain(3 outputs)으로 test context를 분리했다. 일반 회귀는 세 method 모두 수치 결과를 local reference와 비교한다.

Discovery:

`audit-results/fed-runtime-matrix-kernel-discovery-v2-20260831T191856Z`

- 전체 direct manifest: 76 states
- SINGLE_NODE TSMM/MMChain forced manifest: 64 states
- MapMM/SPARK manifest: 12 states
- SINGLE_NODE manifest SHA-256: `face7f9fe80816efe5571c73ff1d3b88c217131d2c1746d40ae74d052251f040`

64-state physical-state 분포:

| State | 수 |
|---|---:|
| CP/LOUT/- | 22 |
| CP/FOUT/BROADCAST | 14 |
| FED/FOUT/BROADCAST | 13 |
| FED/LOUT/COL | 7 |
| FED/LOUT/ROW | 4 |
| FED/LOUT/BROADCAST | 3 |
| FED/FOUT/ROW | 1 |

Fixture input metadata는 `PUBLIC`이므로 privacy가 추가로 상태를 제거하지 않는다. 강제 replay의 `constraintSatisfied=true`는 exact occurrence/input signature, whole-program consistency, selected state와 lowering authority를 함께 검증한다.

## 3. 발견하고 수정한 lowering bug

실패 target `ce38f23532951998`는 `TsmmRight = XC %*% t(XC)`의 `CP/FOUT/BROADCAST` state였다. Shared CSE transpose hop 118은 두 downstream expression에서 쓰였고, 잘못된 fusion 뒤 다음 두 physical edge가 남았다.

- `MMTSJ#24/hop=119/input=0`
- `MMTSJ#42/hop=125/input=0`

기존 `AggBinaryOp.hasPlannerMaterializationBoundary`는 input Hop 자체가 registry producer인지만 검사했다. 그러나 transpose hop 118은 source hop 58에 등록된 selected `fed_refed`의 **consumer**였다. 그 incoming selected edge가 있는데도 TSMM fusion이 transpose를 제거해 exact physical placement를 보존할 수 없었다.

수정:

- `AggBinaryOp.java:678-690`
  - `FederatedRefedRegistry.hasSelectedConsumerInput(hopId)`
  - `FederatedFoutMaterializeRegistry.hasSelectedConsumerInput(hopId)`
  - `FederatedLocalMaterializeRegistry.hasSelectedConsumerInput(hopId)`
  를 fusion boundary에 포함했다.
- 서로 다른 physical edge 중 첫 번째를 고르지 않았다. `Dag`는 여전히 ambiguous multi-edge projection을 fail-closed한다.
- `Dag.java:753-757`의 오류에는 실제 Lop identity/input position을 포함해 향후 모호성 진단을 가능하게 했다.

회귀 테스트:

- selected incoming REFED가 TSMM transpose fusion으로 지워지지 않음
- selected incoming REFED가 MMChain transpose fusion으로 지워지지 않음

## 4. 병렬 forced campaign

Artifact:

`audit-results/fed-runtime-matrix-kernel-campaign-20260831T192211Z-fixed`

실행 구성:

- host: `dams-so006` (`so001` 사용 안 함)
- 독립 source staging 4개 및 독립 Maven `target/`
- disjoint shard 0/4, 1/4, 2/4, 3/4
- shard당 16 targets
- `TARGETS_PER_JVM=1`
- source receipt: `SOURCE_SHA256SUMS_V3.txt`
- source receipt SHA-256: `bb1a04f6ca2f9bfdf5c8f82bb9e5b3edfb03b0863141d836f2275b05f32b5dd7`
- 위 파일은 원격 shard 0의 실행 staging에서 그대로 복사했으며, 네 shard의 `RUN_MANIFEST.txt`가 기록한 digest와 모두 일치한다.

집계:

- `manifestTargets=64`
- `PUBLISHED_LEGAL_EXECUTED=64`
- `SUCCESS=64`
- `unresolvedTargets=0`
- `validationStatus=PASS`
- authoritative runtime capability success rows: 199

핵심 파일:

- `aggregate/SUMMARY.json` SHA-256: `242ce0278620044460f91d54788c8fc1794fa80d0f43e5c5ad1e7fca5bd449db`
- `aggregate/FINAL_RESULTS.jsonl`
- `attempt-local-comparison-v1/summary.json`
- `CORE_SHA256SUMS.txt` (source receipt를 포함한 전체 artifact checksum 목록) SHA-256: `412ad83c14e81b23eff8e9f17c96efffb3dae718daf2de22d5bc59df1a3ab396`

Attempt-local join은 runtime success 199, exact planned target in P 156, selected/runtime input divergence 0, confirmed missing 0을 보고한다. 이 comparator는 observed successful FED instructions만 다루므로 전체 R의 독립 exhaustive enumeration은 아니다.

## 5. MMFED/SPARK gap closure와 증거 경계

Artifact:

`audit-results/mmfed-clean-20260831T220202Z`

최신 discovery에서 MapMM/SPARK context의 federated-input `AggBinaryOp ba(+*)` 상태를 다시 계산하고 12개를 추출했다. 상태 분포는 CP 6개, FED 6개이며, manifest SHA-256은 `43f3f8d5c4d761350243e1486ebe124c247114181a2dbf6718b15c3127c97f50`이다.

실행 구성:

- host: `dams-so003`, `dams-so004` (`so001` 사용 안 함)
- host별 독립 source staging 및 물리적 source-local `target/`
- disjoint shard 0/2, 1/2, shard당 6 targets
- `TARGETS_PER_JVM=1`
- 각 shard가 실행 전에 `mvn clean test-compile` 수행
- source receipt SHA-256: `6a8dfd9037b94d9954ca44e8b1f95da3b12e7ce4c951889b727ac92a178db1da`
- clean-build witness SHA-256: main `dafd924c11d08261276ecbf82fb710ae8d4381938dcb4dd23715ee36e735adca`, test `eb16c3c9dcd30e7ce8a5b12e363d9fc2e858649019a68ee3968155b8d38da31a`

결과:

- `manifestTargets=12`
- `PUBLISHED_LEGAL_EXECUTED=12`
- `SUCCESS=12`
- `constraintSatisfied=12`
- `unresolvedTargets=0`
- authoritative runtime capability success rows: 59
- `sp_chkpoint`/`chkpoint` 실행 또는 실패 흔적: 0
- aggregate validation: `PASS`

Aggregate summary SHA-256은 `48172b751b585bc2b1d7d4c95061d48109b536dde03a297ed5c19b985b4f4b71`이다. 강제된 FED 상태는 실제 `AggregateBinaryFEDInstruction ba+*`로 실행되었고, CP 상태는 global SP mode가 planner-selected CP를 덮어쓰지 않은 채 lowering/runtime audit를 통과했다. 즉 opcode alias 허용은 `ba(+*)`와 closed specialized family(`tsmm`, `mapmm`, `cpmm`, `rmm`)의 논리 연산 동일성에만 적용하며, CP/FED 또는 LOUT/FOUT placement divergence는 여전히 fail-closed이다.

별도의 sparse/large runtime-planner fixture는 2,000×1,000 sparse ROW-federated input과 1,000×8 local right input에서 실제 `fed_mapmm` heavy hitter를 관찰하고 local reference와 수치 결과를 비교했다. 이 fixture는 specialized `MapmmSPInstruction→MMFEDInstruction` 경로가 R에 존재함을 독립적으로 증명한다.

증거 경계는 유지한다. 12-state campaign은 selector가 공개한 P의 soundness를 증명하고, sparse/large fixture는 specialized MMFED의 대표 runtime capability를 증명한다. 이 둘만으로 runtime이 지원하는 모든 shape/size/input combination을 독립적으로 완전 열거한 것은 아니므로 `coverageComplete=false`이며 global Missing=0을 주장하지 않는다. `TsmmFEDInstruction`/`MMChainFEDInstruction`의 independent specialized R exhaustive Missing 판정도 여전히 유보한다.

## 6. 검증

- fixture 일반 실행: 3 tests PASS
- sparse/large MMFED runtime fixture: 1 test PASS, `fed_mapmm` 1회 관찰
- planner-selected global-SP authority unit tests: 5/5 PASS
- runtime placement audit unit tests: 72/72 PASS
- `FederatedDagExactRefedInputProjectionTest`: incoming-edge fusion 회귀 포함 PASS
- fixed forced campaign: 64/64 PASS
- MapMM/SPARK clean-freshness forced campaign: 12/12 PASS
- aggregator/comparator validation: PASS
- source/campaign hashes 기록 완료
