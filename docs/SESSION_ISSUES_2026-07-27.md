# Session Issues — 2026-07-27

## DP ALS function-output rewire rejected an exact unroll clone carrier

- **상태**: 진행중 (코드/회귀 테스트 해결, 새 Docker canary 및 336셀 재실행 대기)
- **환경/조건**: Docker-only guarded campaign; planner `DP`; privacy public ignore; workload `als`; dataset `P2P2D`; workers `1`; profile `lan`; SystemDS commit `ea77f99dac730e26f20754988e7213298d5277d2`; campaign JAR SHA-256 `ce7ae2ee6dd5f863516b94525b983f9affd5de7ad436765ec60756a54b39b05e`.
- **재현 절차**: campaign cell `workers=1|planner=DP|workload=als|profile=lan` via immutable stage `g007-stage-fe75ec6a3be60538c39ddbb8b07abb94ba8af560ec809e3b7541018004541bcc`; failure bundle `results/campaign-control/ledger/committed/discovery/af3b2f8a4f9c1d3b76f66f4b468d3c3089bfb2f48a382f6acf3e52c3b5f44635`; hermetic RED: `mvn -q -DskipTests=false -Dtest=FederatedPlannerFallbackIntegrationTest#testDpPlansAlsWithUnrolledFunctionOutputCarrier test` (pre-fix equivalent test log `/tmp/g007-als-dead-output-red.log`).
- **관측 증상**: coordinator log terminates during final Hop-boundary planning with `DpSemanticConstructionException: REWIRE_FUNCTION_OUTPUT_UNMAPPABLE`; no execution-time footer/output is produced. Campaign correctly stops the discovery barrier without retry. Return-code scan reports no runtime fallback, timeout, resource invalidity, or worker failure.
- **원인 분석**: ALS expands through `.builtinNS::m_alsCG`; its caller-dead `U` output is still an executable function-body output. Loop unrolling replaces the output TWrite with a physical clone carrier. `exactPhysicalCloneMapping` already proved the clone→compiled-occurrence bijection, and `RewireOccurrenceSnapshot.occurrenceByCarrier` was designed to retain both direct and clone carriers. However, `exactCandidateFunctionOutputEdges` consulted only `occurrenceByResolvedHop`, so it rejected the already-authenticated clone carrier before the snapshot could publish it.
- **해결 요약**: function-output receipt construction now consumes the same exact `occurrenceByCloneCarrier` ownership projection already used by transient forwarding and root projection. Direct and clone projections must be absent-or-identical; ambiguous ownership remains fail-closed. The semantic output occurrence remains the candidate owner while the physical clone remains the executable carrier. No candidate was closed, no legality rule was weakened, and no runtime fallback was added.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpRewireTransTable.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
  - `docs/SESSION_ISSUES_2026-07-27.md`
- **검증**:
  - Hermetic RED reproduced `REWIRE_FUNCTION_OUTPUT_UNMAPPABLE` with the same ALS structure.
  - Focused GREEN: `FederatedPlannerFallbackIntegrationTest#testDpPlansAlsWithUnrolledFunctionOutputCarrier` passed and asserts that a function-output edge is backed by an exact physical clone projection.
  - Related GREEN: function-output placeholder, candidate snapshot, rewire snapshot, and DP rewire-table test bundle passed (`/tmp/g007-function-output-rewire-suite-green.log`).
  - Docker verification remains pending until a fresh immutable JAR/stage is built.
- **잔여 이슈**: build a fresh JAR, run the exact failed Docker cell as a canary, then restart the full canonical 336-cell campaign from a clean results/ledger namespace because code identity changed.
- **잠재 회귀 위험**: a clone carrier could be attached to the wrong semantic output or duplicate another output position. Detection: snapshot construction still requires exact candidate ownership, unique output identity, contiguous output positions, and a one-to-one prevalidated clone mapping; the ALS regression asserts the physical clone projection explicitly.
- **의사결정 근거**: planner rewire ownership was incomplete; the fix extends typed planner evidence already produced by `exactPhysicalCloneMapping`. Oracle capabilities, runtime support, TRead/TWrite legality, and recompile CP→FOUT prohibition are unchanged.
- **적용 원칙/제약**: planner-first, runtime fallback 금지, 편의상 candidate-space 축소 금지, 함수/loop recompile ownership 유지, Docker-only 검증.

## Campaign preregistration rejected its own nested-tree inventory

- **상태**: 해결
- **환경/조건**: G007 Docker-only guarded campaign; SystemDS commit `bb30c9af39b7288e3912a5c468284e918ced89af`; immutable final stage `g007-stage-fb48376bd8e62f607b096f66641d209355f289ef735765dc908051f99bd6f773`; exact 336-cell preregistration build.
- **재현 절차**: `python3 -m unittest scripts.federated_campaign.tests.test_determinism_contract.DeterminismContractTest.test_campaign_preregistration_accepts_canonical_nested_tree_order`; RED log `/tmp/g007-nested-tree-order-red.log` (SHA-256 `8d71bf375eb2c76837a74554dfdffdc3d34b52ac48453d8daa6eb46a9b7a1031`).
- **관측 증상**: `build_campaign_preregistration_manifest`가 생성한 dataset/source-tree inventory를 `validate_campaign_preregistration_manifest`가 `dataset frozen tree order is not canonical`로 거부했다. 실제 데이터의 `ADULT_features.data/0-m-00000`와 `ADULT_features.data.mtd`, runtime tree의 `target/lib/hadoop/bin/...`과 `target/lib/hadoop-annotations-...jar` 조합에서 재현됐다.
- **원인 분석**: `_dataset_records`는 `Path` 객체를 정렬하여 디렉터리 컴포넌트 우선 순서를 만들었지만, `_validate_frozen_tree`는 직렬화된 POSIX 상대경로 문자열의 전역 사전식 순서를 요구했다. 중첩 디렉터리가 없는 기존 fixture는 두 순서의 차이를 드러내지 못했다.
- **해결 요약**: tree inventory 생성 시 `path.relative_to(root).as_posix()`를 명시적 정렬 키로 사용했다. 생성기와 검증기가 동일한 canonical representation을 사용하며 후보 축소, runtime fallback, planner 동작 변경은 없다. 중첩 디렉터리와 동일 prefix의 sibling metadata 파일을 포함한 회귀 테스트를 추가했다.
- **수정 파일**:
  - `scripts/federated_campaign/determinism_contract.py`
  - `scripts/federated_campaign/tests/test_determinism_contract.py`
  - `docs/SESSION_ISSUES_2026-07-27.md`
- **검증**:
  - Focused GREEN: `/tmp/g007-nested-tree-order-green.log`, 1/1 PASS, SHA-256 `353239c6a391a5fe2e900b988e30c68aa3a27170e16d3a962ba3592c634b44fa`.
  - Full campaign-contract GREEN: `/tmp/g007-federated-campaign-full-green.log`, 102/102 PASS, SHA-256 `f87fee06200eda18eb47b6a0dbf533e5d32f2cf2989f368357d11f9606c4916c`.
- **잔여 이슈**: 수정된 계약 모듈을 새 immutable stage에 포함하고 실제 1,032-file dataset 및 321-file runtime tree로 preregistration을 다시 생성·검증해야 한다.
- **잠재 회귀 위험**: manifest tree order와 hash가 이전 flat-only fixture 기준과 달라질 수 있다. 동일 입력 두 번의 manifest hash 일치 및 semantic validator 통과로 감지한다.
- **의사결정 근거**: determinism/oracle 계약 생성기를 검증기와 일치시킨 수정이다. planner/oracle legality, TRead/TWrite, recompile placement, runtime 실행 규칙은 변경하지 않았다.
- **적용 원칙/제약**: Docker-only, 동일 데이터/시드, runtime fallback 금지, 편의상 후보군 축소 금지, 실패 원인의 구조적 소유자 수정.
