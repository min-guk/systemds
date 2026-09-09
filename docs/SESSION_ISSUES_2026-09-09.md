# Session issues — 2026-09-09

## StepLM mixed privacy planning ablation — completed (planning only)

- **문제 정의**: X=PRIVATE_AGGREGATE, Y=PUBLIC만으로 과거의 planner별 physical-plan
  차이가 복원되는지 확인. 기존 보호 데이터를 덮어쓰거나 모든 privacy를 풀지 않음.
- **방법/환경**: c8f13e3654 repaired source의 고정 JAR, StepLM WAN-Mid/w3, 4 planners,
  2 privacy 설정. 세 Y shard sidecar의 privacy 필드만 복사본에서 public으로 변경해
  readonly Docker file overlay. X sidecar와 모든 데이터 값은 불변.
  network none, compile only, skip worker control. 실제 source privacy audit 검증.
- **결과/검증**: 8/8 compile 성공; 오류 부재/complete trace/audit/execution=0 검사.
  matrix singleton 163→149; 16 occurrence domain 확장. FedFirst/AggLocal/Global/
  Regional 선택 변화는 각각 3/3/2/2개. FedFirst↔AggLocal 차이는 0→5개.
  FedFirst는 반복 direct-solve RHS의 local Y 공급, AggLocal은 residual 수집 후
  CP 제곱/합계를 선택. Global/Regional은 초기 y 통계만 변경.
- **과거 대조**: authenticated historical observer의 사라진 Global/Regional 48개
  상태 중 12개가 다시 feasible. 실제 선택이 12개 복원된 것은 아님.
- **산출물/재현**: `/home/mchoi/g014-steplm-y-public-planning-20260909/REPORT.md`,
  `run_ablation.py`, `ANALYSIS.json`, `summary.json`, `diffs/*`.
- **수정 파일**: production source/DML/기존 sidecar 수정 없음. 새 진단 artifact와
  본 session 문서만 작성.
- **잔여 검증**: runtime 미실행. 반복 호출의 materialization 재사용 및 실제 RPC/
  transfer 횟수·성능 차이는 추론 가능성이지만 측정 사실이 아님. 과거 전체 계획
  또는 큰 runtime 격차가 복원됐다고 주장하지 않음.
- **위험/감지**: future runtime은 workers에도 같은 Y-public 시나리오를 별도 배포하고
  privacy/provenance 일치를 검사해야 함. 현재 metadata overlay만으로 worker runtime의
  privacy를 바꾼 것이 아님. 기존 both-PA 결과에 섞지 않음.
- **판단 근거**: user-authorized synthetic metadata ablation, shared domain/selected
  candidate/emission evidence로 비교; privacy/code guard를 임의로 완화하지 않음.

## FedFirst producer-first policy restoration — implemented and verified (bounded scope)

- **Problem**: current first-feasible uses MRV decision ordering; its state rank does not
  compare reachable federated input counts across equally ranked output states. StepLM
  Y-public therefore picks FED/LOUT with collected Y although remote-Y FED/LOUT is legal.
- **Plan before edits**: add failing selector regressions first; retain the frozen domains,
  privacy facts, candidate reachability, equality and release constraints unchanged.
  FEDERATED_FIRST alone will visit producer groups in deterministic dependency postorder
  computed once from shared facts, rather than MRV. Backedges are ordering ties only,
  never removed feasibility constraints. Within equal FED/FOUT rank, maximize reachable
  PRESENT input count (the existing final candidate-row policy) before movement hints.
  This retains remote input supply where possible without adding a cost optimizer.
- **Scope**: policy selector + read-only indexed candidate query + regressions. AggLocal
  MOVEMENT_FIRST and Global/Regional algorithms, candidate builder, privacy and runtime
  untouched. No domain exclusion, explicit binding variable, dependency, or runtime fallback.
- **Verification plan**: red/green targeted tests; broader selector/candidate/privacy tests;
  compile/package; isolated Docker planning-only paired StepLM both-protected/Y-public
  with four planners, same data/config/old JAR control, source-sidecar hashes unchanged.
  Compare domains, candidate inputs and emitted runtime instructions; no runtime-speed claim.
- **User override of old repo instructions**: current explicit FedFirst + Y-public request
  takes precedence over historical DP-first/public-test-ignore instructions. Docker-only
  benchmark and protected X requirements remain in force.
- **Risks**: producer order can need more backtracking than MRV; cyclic relationships have
  no strict topological order. No global FED/FOUT maximum or linear worst-case guarantee.
  Measure planning wall time and inspect regressions, do not hide trade-offs.

### 완료 증거 및 잔여 범위

- 구현: `PolicyFirstFeasiblePlacementSelector.java`, `CandidateSelections.java`.
  producer dependency index를 selector invocation당 한 번만 만들고 component에 재사용.
  동순위의 reachable PRESENT-input hint와 equality-group trial overlay를 추가.
- 테스트: selector source-first/cycle 회귀와 실제 StepLM candidate-fact hint 테스트.
  최종 53/53 PASS, package 및 diff check PASS. AggLocal/Exact/privacy 회귀 포함.
- 실제 Docker planning-only: StepLM WAN-Mid w3 × 4 planners × 2 privacy = 8/8 PASS.
  8개 모두 기존 domain/legality 동일. 다른 3개 플래너의 6개 계획 동일.
  FedFirst Y-public LOCAL 2→0, FED 74/FOUT 69 유지; m_lm 및 m_lmCG 입력 COL,ROW 유지.
  both-PA는 FunctionOp authority 1개만 변경, emitted program 동일.
- 산출물: `/home/mchoi/g014-fedfirst-producer-first-20260909/REPORT.md`,
  `COMPARISON.json`, `SOURCE_MANIFEST.json`, `tests-final/`, `summary.json`.
- 기존 캠페인/데이터/JAR 덮어쓰기 없음. runtime 미실행. compile speed는 단발·진단
  및 테스트 자원 공유 때문에 비교 주장하지 않음. 전역 최적/엄밀한 single-pass 미보장.
- 잔여 위험: high-fanout hint lookup overhead, cycle/SAME_PLACEMENT 특수 alias의 추가
  회귀 커버리지. 전체 2-worker unit fixture의 초기 consistency 실패는 baseline 미확인;
  단독 fixture 문제인지 기존 분석 문제인지 단정하지 않는다. 더 좁은 실제 facts unit
  + 원래 3-worker Docker full planning으로 본 변경을 검증했다.

## AggLocal on Apache-like FedFirst traversal (2026-09-09, in progress)
- User explicitly requests Heuristic implementation following Apache FedAll inheritance and L2SVM planning with X PRIVATE_AGGREGATE/Y PUBLIC. This overrides older DP-first and ignore-public test instructions for this bounded task.
- Plan: retain shared domains/privacy and existing analysis-owned aggregate-vector demotions/local continuation/frontier policy; replace production single-pass AggLocal movement-first/MRV selector with FedFirst FEDERATED_FIRST producer ordering. No oracle, runtime, DML or source metadata changes.
- Regression first: assert production comparator, certificate and policy identity; preserve local Xd HOP/LOP and privacy guards. Compare frozen old backend with new backend for FedFirst/AggLocal and both privacy variants at WAN-Mid w3 in isolated Docker compile-only mode.
- Apache reference: FederatedPlannerFedHeuristic extends FedAll; overrides getFederatedOut for ROW column-vector / COL row-vector AggBinaryOp outputs. COFEE keeps its existing whole-program policy extension and legal reentry; not claiming byte-for-byte Apache implementation or global maximality.
- Risk: different variable/state order may alter layout/relocations or reveal policy-domain inconsistency. Check exact candidate receipts, privacy audit, physical instructions, and no runtime execution before reporting.

### AggLocal Apache-style change: completed verification
- Changed production `FederatedPlannerFedHeuristicSinglePass` to reuse default FEDERATED_FIRST producer-first selector; retained existing adapter demotions/local-prefix/reentry policy. Legacy exhaustive config unchanged.
- Tests: 56/56 PASS, 0 failures/errors/skips. Regression archive `/home/mchoi/g014-agglocal-apache-l2svm-20260909/tests-final/`. New assertions initially failed old comparator as intended; one intermediate test incorrectly expected a new certificate suffix, corrected to existing FedFirst certificate, no production certificate change.
- Maven package and `git diff --check` PASS. Independent code review APPROVE with no findings. Built JAR f59fb7348bbdcfe4edb4dc8df568a76db60ae711bb476592d28108dbd3c635d7 differs from frozen FedFirst baseline05acf by exactly the HeuristicSinglePass class.
- Planning: L2SVM WAN-Mid w3, 4 planners x2 privacy variants x2 backends =16/16 PASS, compile-only Docker/no worker startup/no runtime. X stays PRIVATE_AGGREGATE; Y-public uses read-only diagnostic metadata overlays. Original sidecars unchanged.
- Only old/new AggLocal/Y-public cell changes: selectedFED28→37/FOUT16→28; static FED instructions19→29; LOCAL materializations2→1; relocations3→3. Other7 paired cells unchanged. Canonical base candidate domains identical in all8 fixed-privacy old/new pairs.
- New backend privacy comparison: static FED counts (Y protected→PUBLIC) FedFirst36→38, AggLocal30→29, Global28→8, Regional28→8. This is a real planning change, NOT a demonstrated performance improvement. Restoring FED-first can select more remote work than movement-first.
- Residual/risk: no runtime measurement, and PUBLIC does not force Y or all downstream loops to CP. Do not infer remote operation count at execution or latency from static instruction count. Inner loop104–114; outer post-inner120/124 have different frequency scopes. Existing COFEE pathwise heuristic remains an extension of Apache's local HOP rule; no claim of exact equivalence or linear worst-case traversal.
- Full report/diff/metadata/JAR manifests: `/home/mchoi/g014-agglocal-apache-l2svm-20260909/REPORT.md`, `AGGLOCAL_SOURCE_CHANGES.diff`, `COMPARISON.json`. No commit/push or campaign artifact overwrite.

## ML10 shape/privacy pilot (50Kx128, w3) — 2026-09-09T01:55Z

- Isolated artifacts: `/home/mchoi/g014-ml10-shape-privacy-20260909`.
- No planner/backend source change; frozen `f59fb7348bbdcfe4edb4dc8df568a76db60ae711bb476592d28108dbd3c635d7` JAR.
- Correct-shape metadata planning L2SVM LAN A/B/C x4 =12/12 pass; source audit confirms X PRIVATE_AGGREGATE50K128 and Y PUBLIC local/PUBLIC FED/PA FED.
- Static FED counts A28/23/4/4, B38/29/8/8, C36/30/29/29 (FedFirst/AggLocal/Global/Regional). Inner-loop counts A9/9/0/0, B9/8/0/0, C9/9/9/9. No runtime claim.
- Real synthetic data generated and persisted readback verified (~51MiB); Y public/private views reuse same bytes. Main controlled data differs from old2100-column onehot P2P2D.
- Harness bug: missing central ADULT_features.data.mtd silently rendered100000x1 despite shard metadata50K128. Corrected and invalid attempts separated; must validate generated DML ranges, not requested CLI dimensions alone.
- Subsequent real-data bind-mount setup hit readonly-parent missing mountpoints. Treat as harness failure, not planner/runtime failure; fix and canary before restarting grid.
- Existing so002–004/so007 runtime kept intact. Runtime launch remains resource/data/preflight-gated. Do not reinterpret compile-only results as timings.

## AggLocal local-continuation-first (in progress)
- Problem: analysis traces certified FED reentry before checking local vector continuation. With public worker Y, L2SVM demoted vectors can be re-uploaded despite legal local vector processing.
- Plan: regression first; local scalar/vector supply preference with shared candidate/privacy/materialization certification; preserve protected large-matrix frontier. See /home/mchoi/g014-agglocal-local-continuation-20260909/IMPLEMENTATION_PLAN.md.
- Scope: AggLocal policy facts/preferences only, no Global/Regional/FedFirst policy, runtime, data or frozen campaign change. Latest explicit user request overrides historical public-test-ignore/DP-first guidance.
- Risks: shared formal/CFG obligations and incomplete candidate tuple support; test negative protected sibling and existing loop/function safeguards. Runtime performance remains unmeasured.

## AggLocal local-continuation-first completed (2026-09-09)
- Fixed local-vector preference before FED reentry; exact native LOUT proof and retention at protected downstream matrix markers. No base-domain/privacy/runtime changes.
- Real PUBLIC-worker-Y L2SVM: static FED29->14; inner loop104-114 FED8->0; explicit relocation3->0. Protected Y case unchanged30/9. CP prefetch still provides Y; do not equate FED counts to RPC counts or speedup.
- 71/71 targeted tests, package, diff-check PASS. Docker compile-only25/25 (L2SVM12 + ML9 smoke13), canonical domains and privacy unchanged25/25. Other3selector L2SVM plans unchanged9/9.
- Final JAR d0f7b232bde2a1f063da830056f59697184a33128aea35b9a3d3ff9bc3a48d9c. No runtime deployment/measurement; previous campaign untouched.
- Full report: /home/mchoi/g014-agglocal-local-continuation-20260909/REPORT.md. Final comparisons FINAL_COMPARISON.json and SMOKE_COMPARISON.json; initial COMPARISON.json is failed preliminary design evidence, not the final result.

## ML10 size sweep (frozen backend d0f7b232, 2026-09-09)
- Evidence root: `/home/mchoi/g014-ml10-size-sweep-20260909`.
- Six shapes, ten workloads, four planners, two network cost profiles, w3: 480/480 distinct compile-only cells passed; 480 raw bundles checksum verified. No production planner modifications in this task.
- Provisional runtime-screen baseline 50K×512; not a certified maximum-speedup dataset. L2SVM 200K/LAN produces identical AggLocal/Global/Regional placement and printed-program fingerprints at all three tested d values; increasing n does not necessarily widen policy separation.
- Diagnostic common scorer reproduces one DP objective bit-for-bit, but FedFirst normalized receipt ambiguously maps input-authority alternatives [2,3] at check_min; no policy score invented and no secondary optimization used. Exact/AggLocal scoring not certified.
- Separate so006 single-host pilot setup failed initially on missing /usr/bin/time, then stale Hadoop .mtd CRC after privacy metadata update. These are new pilot harness/data-preparation issues, not evidence of planner failures. Preserve failures and regenerate metadata CRC without weakening privacy; no historical campaign data modified. Root stopped only the failed pilot coordinator after Netty threads persisted; original so002/3/4/7 campaign untouched.
- Follow-up: pilot harness/CRC issues resolved without production backend changes; 12/12 L2SVM LAN/w3 runtime cells passed for50K×{128,512,2100}. Cross-planner model maxabs2.22e-15; pilot containers removed. Runtime evidence refines initial recommendation to50K×128 (not universal ML10 optimum); see task RECOMMENDATION.md/RUNTIME_SUMMARY.json.

## User-selected ML10 50K×128 campaign started (2026-09-09)
- Control: `/home/mchoi/g014-ml10-n50000-d128-20260909`; stage: `/home/mchoi/cofee-ml10-n50000-d128-20260909/stage`.
- X private-aggregate 50K×128, aligned worker Y public 50K×1; binary for L2SVM/LogReg, continuous for LM/StepLM/GLM (GLM thresholds internally). Authentic data27 artifacts / shard reconstruction maxabs0 / all CRCs verified.
- User's scope preserved: ten ML workloads, four selectors, four network profiles, w1/3/5 =>480 runtime cells. so007 coordinator, prefix workers so002–so006; so001 proxy and so008/9 reserved. No production edits, no P1/P2/SliceLine dataset changes.
- Driver thin adapter6/6 tests;120-block supervisor separates planning gates from trace-free runtime, retains lifecycle network/output/cross-planner parity; mode/block-separated archive destinations avoid overwrite. tmux `cofee-ml10-50k128-20260909`.
- First Docker mount failed because excluded data/results/tmp/references directories were absent below read-only parent; added only empty mountpoints, preserved failure, then PCA LAN/w1 planning4/4 and runtime4/4 passed; identical output semantics. Next ALS block runs automatically. No universal performance ordering claimed.
- Offline CP references are separate so006 correctness runs, not additional benchmark cells. CP reference creation must finish before campaign w5 uses so006.
- Previous completed size-screen duplicate expanded logs removed only after per-file hash comparison with retained archive, freeing1.24GB; COMPACTION.json records proof. Local free~2.1GiB remains limited, per-cell bulky archives offload hash-verified toso007.

## P2 explicit metadata-only release (2026-09-09)
- 상태: metadata-only 구현/검증 완료, 전체 P2 downstream planning 차단 별도 확인.
- 요청/권한: 사용자가 P2 합성 범주 사전/인코딩 mapping 공개를 승인했다. X/X0 PRIVATE_AGGREGATE 유지. 일반 PUBLIC/다른 release 허용으로 확장하지 않음.
- 증상/원인: transformencode second output M이 source privacy를 그대로 상속하여 CP metadata output이 배제됨. 별도 선언 없는 사전에는 올바른 기존 거부다. opt-in 구현 초기 named parameter map은 multi-return builtin에서 비어 있었으므로 exact builtin/type/arity/output identity + positional spec으로 수정했다.
- 해결: `cofeePublicRecodeMetadata:true` spec와 `sysds.privacy.allowPublicRecodeMetadata=true`의 dual opt-in. recode/dummycode만 허용. exact M output만 PA→PUBLIC, PRIVATE 및 원본/encoded rows는 그대로. CP/FED는 encoder 전에 validate-and-strip; 일반 transform parser 문법은 변경하지 않음.
- 수정 파일: `placement/NeutralPlacementGraphBuilder.java`; CP/FED `MultiReturnParameterizedBuiltin*Instruction.java`; 신규 `runtime/transform/TransformEncodeMetadataPrivacy.java`; privacy contract/utility tests.
- 검증: 25/25 targeted tests, package, diff-check PASS. Docker compile-only encoded prefix 4 planners×w1/3/5=12/12 PASS; 전부 X0 FED/FOUT, M CP/LOUT; FED transformencode 및 CP metadata write 확인. Deployment opt-in off canary는 M에서 거부. Runtime 실행 없음.
- 잔여: full original P2 12/12은 downstream에서 실패. w1 b(<) FULL single-range proof/domain; w3/5 b(+) X physical domain 또는 policy consistency. 사전 공개가 전체 pipeline 성공을 의미하지 않음. Source closure/physical facts 후속 점검 대상이며 privacy를 완화하지 않는다.
- 회귀 위험: output identity 오류/unknown spec 승인/PRIVATE 혼동은 unit negative tests로 검출. EncoderFactory 원본 marker 거부 및 normalized dictionary/dimension parity로 parser grammar widening 방지. Runtime property gate는 legacy/manual instruction의 독립 worker-side privacy enforcement를 대체하지 않는다.
- 기존 ML10 frozen JAR `d0f7b232...` 변경 없음. 이번 backend `ee970725...`와 실험 artifacts 분리.
- 보고서/증거: `/home/mchoi/g014-p2-public-recode-metadata-20260909/REPORT.md`; task-only diff `P2_METADATA_RELEASE.diff`; `ENCODE_LOWERING_AUDIT.json`, `FULL_P2_BLOCKERS.json`, `TEST_RESULTS.json`.
- 독립 review: metadata 기능 APPROVE. w3/w5 추가 진단: clipping outer b(+)의 두 PRESENT ROW inputs에 필요한 exact input/relocation receipts 미발행으로 ExactPhysicalModel domain이 비어짐; multi-return-primary-result→X0 read anchor 연결 누락은 아직 원인 후보. 일반 fallback 금지. w1은 SinglePartitionFacts의 FRAME cardinality seed 누락 조건 확인, 미수정/미해결로 유지.

## P2 전체 planning: FRAME cardinality / encoded-primary pool continuity — 해결
- **증상**: metadata release 후에도 w1 comparison 후보 소실, w3/w5 clipping 마지막 b(+) physical domain empty; 4 planners×w1/3/5 전체 0/12.
- **원인**: SinglePartitionFacts의 FRAME/cast 증명 단절; multi-return FunOut carrier의 logical FRAME input이 일반 compiled input edge가 아니어서 native pool 공급 증명 누락.
- **해결**: literal FRAME 단일 range 및 matrix↔FRAME cast cardinality; 정확한 transformencode primary의 기존 DOMINATES/input0/multi-return-output-value 관계만 resolver/native continuity에 전달. ROW 행 구간 보존 및 single FULL endpoint만 사용; raw encoded column geometry 복사 금지. generic control marker 및 multi-input fallback은 미허용.
- **수정 파일**: SinglePartitionFacts.java, NativePlacementContinuity.java, NeutralPlacementGraphBuilder.java 및 4개 회귀 테스트.
- **의사결정 근거**: runtime이 보존하는 endpoint/ROW axis 증명을 공통 분석에 복구한 것; privacy나 exact physical feasibility 계약 완화가 아님.
- **검증**: 118/118 focused tests, package, diff-check PASS; 실제 전체 P2 Docker compile-only 12/12 PASS. X0 FULL(w1)/ROW(w3,w5), 보호 carrier FED/FOUT, M CP/LOUT, execution0. 공개승인 off canary는 M/PA에서 차단.
- **잔여 범위**: runtime 실행/수치정확성 미검증; 다른 transform spec은 별도 근거 필요. 이번에 확인한 P2 planning blocker 없음.
- **잠재 회귀 위험/감지**: pool provenance와 value/range identity 혼동; X0 no-anchor, 두 ROW input authority, COL/omit negative, stale-width FULL tests로 감지.
- **상세 보고서**: `/home/mchoi/g014-p2-placement-repair-20260909/REPORT.md`.

## AggLocal/FedFirst 미커밋 변경 전체 게시 — 2026-09-09
- **요청**: P2 게시에서 제외했던 미커밋 변경도 모두 커밋하여 `github` (`min-guk/systemds`) main에 반영.
- **범위**: FedFirst producer-first / reachable federated input preference, AggLocal aggregate/vector local-continuation 및 certified re-entry, 관련 테스트와 본 세션 기록. Privacy/feasibility 우회 없이 기존 수정 그대로 포함.
- **새 검증**: Maven targeted suite 58개, 50 PASS, assertion failures 0, errors 8. 실패는 CampaignBHeuristicProvenanceContractTest 5개와 CampaignBHeuristicRealVectorPolicyRedTest 3개이며 `localhost:1234/1235` Connection refused → source privacy 획득 거부로 종료. 이 검증을 전체 PASS로 보고하지 않는다.
- **통과 범위**: HeuristicLocalContinuation, FedFirstRemoteInputPreference, L2SVM locality, protected nested demotion, first-feasible selector, pathwise re-entry, P2 clipping authority, shared privacy contracts.
- **잔여**: 두 legacy fixture의 워커 또는 hermetic source-privacy 준비 후 재검증 필요. 이 게시 작업에서는 privacy fail-closed를 완화하거나 테스트를 삭제하지 않았다. Runtime 실험 없음.
- **증거**: `/home/mchoi/g014-p2-placement-repair-20260909/agglocal-publish/tests.log`, `TEST_RESULTS.json`, `test-results/`.

- **게시 전 빌드**: `mvn -q package -Dmaven.test.skip=true` PASS, staged diff whitespace 검사 PASS.

## Shared analysis shape → cost wiring — 2026-09-09 (진행 중)
- **문제 정의**: GMM LAN/w1 logical function boundary의 exp(log_resp)→resp 크기가 raw HOP에서는 미상으로 남아 256MiB fallback을 사용함. 동일 canonical surface에서 FedFirst에 178259.15986394562ms download factor가 부과됨; N×K=50000×4 논리 크기는 1600000bytes. 별도로 16GiB raw input placeholder도 kernel memory cost로 전달됨.
- **해결 계획/의사결정 근거**: 이미 frozen shared analysis가 입증한 occurrence shape를 우선 비용에 전달. serialized sparse evidence의 우선순위 보존; concrete HOP memory estimates 유지; 알려지지 않은 차원은 추측하지 않음. 정책/후보/개인정보/런타임 변경 없음.
- **수정 범위**: logical function boundary byte accounting 및 analysis-aware kernel memory accounting, focused regression tests.
- **검증 계획**: red→green focused tests; frozen backend와 수정 클래스 overlay의 network-none Docker compile-only 비교. 공통 analysis의 shape fact 자체가 미상인 경우 비용 연결만으로 해결되었다고 주장하지 않음.
- **기존 실험 보호**: supervisor 신규 scheduling만 일시 중지하고 실행 중 planning child는 완료하도록 둠. 기존 JAR/결과 변경 없음; 진단 후 기존 supervisor 재개.
- **잠재 회귀 위험**: sparse estimate를 dense로 덮기, 동일 HOP의 다른 occurrence shape 혼합, unproven formal dimension 추정, HOP mutation. 각각 회귀 테스트 및 immutable authority 검사로 검증.
- **증거 위치**: /home/mchoi/g014-gmm-analysis-cost-shape-20260909/
- **잔여 이슈**: 전체 compile/runtime ordering 개선은 아직 미검증.

### Shared analysis → cost wiring 검증 완료 (2026-09-09 17:44 UTC)
- GMM common abstract shape는 이미 50000x4이며 raw HOP unknown-size fallback의 비용 경로 소비 누락을 수정. DML/privacy/candidate builder 변경 없음.
- ordinary input/output memory 및 logical function boundary 비용에 occurrence-exact shape 연결; semantic sparse/NNZ 우선, unknown fallback 유지. Sparse estimator는 기존 surface-local context 재사용, global cache 없음.
- 동일 frozen backend planning-only: Global 예상587609.006→4736.271ms, Regional587998.873→4736.271ms. physical decisions Global50/381, Regional19/381 변경; FedFirst/AggLocal0. 실제 runtime 개선 주장 아님.
- 8suite78tests PASS, Maven compile PASS, diff-check PASS. 상세 증거와 제한: `/home/mchoi/g014-gmm-analysis-cost-shape-20260909/REPORT.md`.
- 17:43:52UTC scheduler PID3718988 재개, pca-lan-w5 runtime 확인. Frozen runtime backend 교체하지 않음.

### Shared-analysis cost backend 배포 및 runtime 재개 (2026-09-09 18:09 UTC)
- 사용자 요청에 따라 full Maven package 후 새 stage를 생성, so002–so007 배포 및 전수 manifest 검증. 이전 mounted stage 변경 없음.
- 신규 JAR20c6a2e116c7f4cf77080b82319dcd35c47a9156b9c8256aba3fc513a5f69755. GMM-v2 대비3costclass families만 변경, 추가/삭제class 없음.
- 기존 ALS/LAN/w5 block4/4 종료 후 old supervisor를 supersede. 새 PID4183405는 interleaved planning→comparison→runtime으로 ML10/4net/w1,3,5를 실행한다.
- GMMplanning4/4, Global runtime5.469s 및 Regional5.329s 성공(network+output검증). 전체 성능 개선/정렬 증거 아님.
- 기존 runtime에는 전체physicaltrace/receipt가 없어 계획 대응이 입증되지 않는다. 비용fingerprint만 보고 구결과를 신백엔드결과로 재사용하지 않고 보수적으로 재측정한다.
- 잔여 작업: 신규 캠페인 나머지셀. 실패시durablestate/log 보존 후 원인분석 필요. 상세보고서 `/home/mchoi/g014-ml10-analysis-shape-backend-20260909/STATUS_REPORT.md`.
