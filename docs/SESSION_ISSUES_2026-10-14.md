# Session issues 2026-10-14

## Fixed-point 분석 재사용과 함수 호출 index 공유

- **상태:** 승인된 최소 구현 및 회귀 검증 완료. Loop-carried 국소 invalidation과 privacy/shape 공동 scheduling은 근거 부족으로 보류했다.
- **문제 정의/증상:** Builder와 model이 동일 request의 MemoPrivacy/residency/index를 각각 생성했다. Privacy/residency/shape의 FORMAL dependency 구축도 전체 call 목록을 반복 filtering했다.
- **원인:** 기존 RuleBuilder.Result는 shape만 전달하고 privacy/resolver를 전달하지 않았다. Facts에 함수별 call 조회 index가 없었다.
- **해결:** 기존 privacy/resolver를 Result로 전달하여 재사용하고 Facts/Sources/Shapes identity mismatch를 fail-closed로 거절한다. Immutable 함수별 call index를 Facts에 추가하고 분석별 actual/argument 구분은 유지했다. 새 범용 graph, transfer 또는 후보 제거 규칙은 추가하지 않았다.
- **수정 파일:** `MemoRuleBuilder.java`, `MemoProgramModel.java`, `MemoPrivacy.java`, `MemoProgramFacts.java`, `MemoResidency.java`, `MemoShapes.java`; 신규 `MemoAnalysisReuseTest.java`, `MemoAnalysisDependenciesTest.java`; 기존 `MemoLoopPrivacyTest.java`의 nested-dependent 반례. 상세 문서 [§11](FEDPLANNER_FIXED_POINT_ANALYSIS_REUSE_2026-10-13_KO.md).
- **검증:** 변경 전 73개 PASS, 새 request 회귀의 기존 구현 실패 확인, root 단독 clean compile 성공, focused 17개 및 Memo component 244개 PASS. Tiny exhaustive 2,604 assignments의 before/after feasible 수와 exact 최적 비용 동일. Bounded GLM/LogReg model 회귀 2개 PASS. 독립 코드 검토 APPROVE. 근거는 `experiments/memo-migration-20261013/analysis-reuse/`.
- **검증 중 수정:** Runtime call fixture에 IPA flag만으로 inlining이 꺼진다고 가정한 부분을 retained loop 함수로 교정했다. 보조 Maven output 경로 격리 실패 이후 공유 target을 root 단독 clean build하여 검증했다. 실패 로그는 숨기지 않고 보존한다.
- **잔여 이슈:** `relevantLoopHeads`는 nested head에서 멈추고 whole-call completion을 충분히 포함하지 않는다. 이를 그대로 privacy invalidation에 쓰면 outer head 제거가 inner summary를 거쳐 outer backedge에 미치는 영향을 놓칠 수 있다. 해당 반례를 추가했으며 기존 전체 invariant 정제는 유지한다. 전체 GLM/LogReg 최적성 및 Docker runtime/성능 완료는 주장하지 않는다.
- **수정으로 인한 버그 가능성:** Request 경계 혼합, context cache 누락, 함수 actual/argument 혼동, inlined call 누락. 새 identity/filter-parity 회귀와 기존 branch/loop/function/privacy/shape 회귀로 검사한다. Cache는 request-confined이며 concurrent model 사용을 새로 지원하지 않는다.
- **의사결정 근거:** 동일 계산 제거와 정확한 source/context 유지가 우선이다. Semantic fixed point와 rollback 가능한 search-domain propagation을 합치지 않는다. UNKNOWN→PUBLIC, UNBOUND 삭제, runtime fallback 및 임의 후보 축소는 없다. Commit/push 없음.

## 남은 Loop 국소 재검사와 고정점 공동 scheduling 결정

- **상태:** 문서 §12 범위의 구현·검증 완료. GLM 전체 runtime 실행은 별도 기존 solver/model blocker로 미완료.
- **문제 정의:** §11에서 보류했던 `relevantLoopHeads` 기반 국소 invalidation은 nested head에서 멈추고 whole-call completion을 누락할 수 있었다. 모든 surviving invariant를 반복 확인하는 비용이 남아 있었다.
- **해결:** `MemoPrivacy`가 이미 구축하던 direct binding index의 compact reverse adjacency를 보존하고, concrete return wrapper remap/transient-read binding도 포함했다. 제거된 head에서 reverse reachability를 수행해 영향받는 survivor만 synchronous batch로 재검사한다. Nested/제거된 head를 통과하고 original context에서 nested summary를 재구성한다. Exact privacy/availability/guard/phase/call-frame 의미는 변경하지 않는다.
- **수정 파일:** production `MemoPrivacy.java`, `MemoShapes.java`(counter만); 신규 `MemoLoopPrivacyIncrementalTest.java`, `MemoAnalysisWorkTest.java`; 설계 §12 및 이 session 문서. Root만 공유 Maven target을 빌드했다. Commit/push 없음.
- **의사결정 근거:** no cross-domain transfer dependency + shape eager/privacy contextual lifecycle + workload counter를 근거로 공동 privacy/shape scheduler는 **도입하지 않는 결정**으로 종료했다. 새 generic graph/product lattice/production legacy fallback은 없다. 실제 공동 scheduler A/B benchmark나 보편적 speedup을 주장하지 않는다.
- **검증:** baseline 기존 34개/신규 reference 4개 PASS; root clean build SUCCESS; focused 33개 PASS; 추가 retained multi-return completion fixture 포함 8개 PASS; 최종 29클래스 **256개 PASS**. Tiny exhaustive 2,604 assignments의 feasible 수·exact 최적 비용 동일. 독립 리뷰 APPROVE, finding 0개. 근거 `experiments/memo-migration-20261013/analysis-reuse-remaining-20261014/validation.json`.
- **실제 작업량:** synthetic backedge check 6→4. Bounded rewritten LogReg의 전체 재검사 snapshot 대비 backedge 84→50, exact privacy evaluation 1007→606. GLM은 41→41로 그대로이며 reverse traversal 3159 node/4797 edge가 추가됐다. 추가 index 공간 O(B+E), worst-case reverse work O(R(B+E)); workload 속도·peak memory의 일괄 개선을 주장하지 않는다.
- **검증 중 수정:** 처음 counter fixture는 incoming A를 읽지 않아 carried value가 없었다. 실제 carried 식으로 교정했다. Source override는 cache를 우회하므로 repeated derived query fixture에서 제외했다. Full-scan count는 마지막 independent survivor 확인까지 3+2+1=6이다. 실패 로그 보존.
- **잠재 회귀 위험:** 새 implicit resolver dependency를 추가할 때 영향 index를 갱신하지 않으면 dirty 누락 위험이 있다. Nested/3-wave/completion/inlined/phase/function-label differential fixture와 기존 availability/UNBOUND 회귀로 감지한다. Function fan-out은 false-positive rechecks를 유발할 수 있으나 privacy 값을 join하지 않는다. Cache는 request-confined다.

## Docker GLM validation: native coverage blocker — movement-width 원인 추정 정정

- **상태:** GLM 미해결; 이번 국소 privacy 변경 이전에도 동일하게 재현. 아래 기존 로그의 마지막 diagnostic을 실패 원인으로 해석한 것은 오류였으며, 후속 조사로 정정했다. Runtime fallback은 추가하지 않았다.
- **환경/재현:** `scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e --planner memo --case ml_logreg --case ml_glm --case-timeout-seconds 60 --timeout-seconds 240 --stage-root /home/mchoi/fedplanner-memo-docker-stage-20261013`. Source/classes/dependencies를 고정하고 named fresh evidence root 사용.
- **Infrastructure:** 처음 physical `/grid` stage에서 `/evidence/container-run.sh`가 보이지 않아 rc127로 DML 전에 실패했다. 기존 Snap Docker 제약에 따라 `/home` stage를 사용하여 해결. 첫 로그도 보존.
- **LogReg 결과:** `remaining-fp-20261014-home-stage`에서 model proof/preflight PASS, `OPTIMAL`, Memo planning **26.2756초**(optimization 24.2537초), compilation 27.4831초, execution 2.621초. CP/FED 8×2 model 최대 차이 1.6653345369377348e-16. 단일 Docker 표본이며 개선율 비교가 아니다.
- **GLM 증상:** CP는 8×1 model 생성. FED는 `UNRESOLVED ... MOVEMENT_FACTOR_WIDTH origin=196 width=10`으로 emission 전 종료. Shape/canonical effect 진단도 함께 기록된다. Java rc=0만으로 성공 처리하지 않고 model/OPTIMAL evidence 부재를 harness가 FAIL 처리했다.
- **대조:** `remaining-fp-20261014-baseline-glm`은 변경 직전 `MemoPrivacy.java`에 counter만 더한 별도 compiled snapshot을 사용했다. Class preflight/model proof PASS 후 **동일 오류 문자열** 재현. 기존 source/caches/solver를 완화하지 않았다.
- **원인 정정:** `MOVEMENT_FACTOR_WIDTH`는 `scope.length>8`의 경고이고 CostTerm은 그대로 등록된다. 실제 즉시 종료는 `MemoOptimizer.optimize():228`의 native coverage 검사다. 후속 component probe에서 incomplete owner는 974 하나였고 search/check/cost 평가가 모두 0이었다. 함수 정상 반환 조건을 제외한 shape join이 반환값의 8×1 shape를 잃게 한다. 자세한 근거와 범위는 다음 이슈 및 [분석 문서](FEDPLANNER_GLM_MOVEMENT_DIAGNOSTIC_ANALYSIS_2026-10-14_KO.md) 참조. 기존 실험 원본은 수정하지 않았다.
- **잔여 검증 공백:** GLM의 full OPTIMAL/emission/numeric parity, workload 전반 planning 개선율과 peak allocation. LogReg 성공을 GLM 성공으로 일반화하지 않는다.


## GLM MOVEMENT_FACTOR_WIDTH 원인 분리와 정상 반환 shape 누락

- **상태:** 원인 분석 완료, production/test/DML 수정 및 신규 Docker 검증 미실시. GLM 완료를 주장하지 않는다.
- **문제 정의:** `origin=196 width=10`을 두 width의 불일치 또는 폭 제한에 따른 실패로 해석했다. 실제로 196은 exact origin의 visible binding ID, 10은 factor 선택 변수 수다.
- **확인한 원인:** `MemoProgramModel.registerMovementCosts():509–514`는 폭 경고 후에도 lazy cost를 등록한다. 실제 GLM 재구성에서는 transpose owner 974의 canonical-effect coverage가 미완료여서 optimizer 진입 직후 종료한다. `MemoShapes.resolvedBindingShape()`가 CALL_RETURN의 정상 completion 조건 없이 SELECT 양쪽을 합치고, `MemoRuleBuilder`의 Oracle context도 이 조건을 주지 않는다.
- **분별 검증:** 실제 Docker fed.dml/config/data metadata로 in-memory HOP/Facts/Rules/Model을 구성했다(소스 metadata provider만 동일 3-worker ROW 192×8로 주입; worker 접속/실행 없음). Search nodes/check/cost eval 모두 0. 기존 resolver는 completion 1610을 실패 2환경, 정상 1환경으로 구분한다. 정상 guard21/22 조건을 기존 API에 전달하면 input 8×1/output 1×8 EXACT 및 native coverage complete=true, ROW→COL rule이 확인된다. 이는 host component probe이지 runtime benchmark가 아니다.
- **부수 결함:** `MemoShapes.commonShape()`는 UNKNOWN datatype 두 개를 non-matrix로 취급하여 knowledge EXACT로 올릴 수 있다. 실제 binding1545에서 관측했다. 이 수정만으로 사라진 matrix dimensions가 복구되는 것은 아니다.
- **해결 방향/의사결정 근거:** 지원되는 transpose를 닫거나 width threshold를 높이는 것이 아니라, existing completion/activation 관계를 native shape context에서 재사용해야 한다. 실패 branch는 유지하고 caller continuation에서만 제외한다. 새로운 graph, UNKNOWN→PUBLIC, UNBOUND 삭제, 전역 모든 branch product는 불필요하다.
- **수정 파일:** 이번 분석에서는 본 session 문서, fixed-point 문서 §12.6 정정, 신규 `FEDPLANNER_GLM_MOVEMENT_DIAGNOSTIC_ANALYSIS_2026-10-14_KO.md`만 작성/수정했다. 기존 production/test/실험 결과는 보존했다.
- **잔여 이슈:** shape repair 이후 전체 모델 feasibility/global optimum/emission/numeric parity는 미검증. Wide movement의 약한 lower bound는 별도 성능 위험이다. 현재 폭10은 8 consumers + producer1 + alternative loop-head1이며 이를 임의로 edge별 분리하면 공유 과금 계약이 깨질 수 있다.
- **잠재 회귀 위험/감지:** 반환하지 못하는 환경을 정상 경로로 바꾸는 오류, 호출 frame/다중 반환/guard correlation 누락, EXACT UNKNOWN 승격, scope 축소에 따른 cache dependency 누락. Normal/terminal return shape, unequal normal return shapes, shared cost union, wide-factor OPTIMAL 회귀로 검증해야 한다. 이번에는 새 테스트를 실행하지 않았다.

## 정상 반환 shape repair 구현·검증 계획

- **상태:** 사용자 승인으로 구현 진행. 이전 미커밋 변경과 실험 원본 보존.
- **문제:** contextual CALL_RETURN shape가 정상 completion을 반영하지 않아 GLM transpose coverage를 잃는다. UNKNOWN 타입의 join이 EXACT가 되는 별도 결함이 있다.
- **최소 계획:** (1) 새 normal/terminal/callsite/multi-return/UNKNOWN 회귀를 기존 코드에서 실행, (2) 기존 resolver의 completion 정보만 재사용하여 shape 경계를 복구하고 기존 loop grounding fast path 보존, (3) projected cache의 completion-only 의존 누락 검토, (4) focused 및 전체 Memo component/exhaustive 검증, (5) Docker 전용 GLM/LogReg 검증.
- **제약:** 새로운 graph/전역 source product, 임의 coverage/rule pruning, width threshold 완화, UNKNOWN→PUBLIC, runtime fallback 없음. Root만 Maven target을 빌드한다.
- **회귀 위험:** 정상/실패 경로 간 shape cache 오염, 요청한 다른 return의 미정의 누락, loop shape grounding 소실, 함수 call-frame 혼동. 새 fixture와 기존 privacy/shape/activation/loop/runtime 테스트로 검증한다.
- **근거 위치:** `experiments/memo-migration-20261013/normal-return-shape-20261014/`에 before snapshot/실패 및 성공 로그를 보존한다. GLM 전체 성공은 최종 runtime evidence가 있을 때만 주장한다.

## 정상 반환 shape repair 결과 및 새 GLM search blocker

- **상태:** 위 계획의 contextual shape 수정·회귀 검증 완료. **GLM 전체 runtime/최적성 검증은 미완료**이며 원인은 이제 native coverage 즉시 차단이 아니라 최적화 시간 제한이다. 기존 분석의 “미구현” 상태는 이 결과로 갱신한다.
- **문제 정의/원인:** CALL_RETURN이 함수 completion을 확인하지 않고 payload SELECT를 합쳤다. UNKNOWN datatype join의 EXACT 승격, payload-only projected cache가 secondary return의 completion 의존성을 누락하는 문제도 있었다.
- **해결/원칙:** `MemoShapes`에서 existing completion resolver를 재사용하고 정상 반환 shape만 join한다. Terminal 실패 및 UNRESOLVED는 보존한다. Total completion fast path와 기존 loop grounding/call frame을 유지한다. UNKNOWN 입력의 caller activation을 확인하여 다른 환경의 frozen shape를 재사용하지 않는다. Cache key는 immutable semantic context + head-bound BitSet snapshot이며 mutable SelectionLookup/rule/layout 전체를 보관하지 않는다. UNKNOWN datatype/value type은 EXACT로 승격하지 않는다.
- **수정 파일:** production `MemoShapes.java` 하나; 신규 `MemoReturnShapeTest.java`(9개), 기존 `MemoOptimizerTest.wideLazyCostRetainsTheExactOptimum`(10변수 wide factor). 분석 문서 [§8](FEDPLANNER_GLM_MOVEMENT_DIAGNOSTIC_ANALYSIS_2026-10-14_KO.md), 이 문서 및 새 evidence. DML/Oracle/privacy/movement/solver/runtime 코드는 이번 작업에서 수정하지 않았다. 기존 미커밋 변경 보존, commit/push 없음.
- **검증:** 기존 코드에서 신규 핵심 회귀 3개 실패 확인. 최종 focused9개 및 Memo component30클래스 **266개 PASS**. Tiny exhaustive5fixture **2,604 assignments**의 feasible 수·exact optimum 동일. Maven test-compile 및 `git diff --check` PASS. 독립 production 코드 검토 APPROVE. 상세 로그는 `experiments/memo-migration-20261013/normal-return-shape-20261014/`.
- **실제 실행:** Docker harness class preflight/model proof7개 PASS. LogReg **OPTIMAL**, planning23.250950165초/optimization21.325129019초, CP/FED8×2 최대 오차1.6653345369377348e-16. GLM은 rules/model까지 완료한 뒤 **180초 case timeout(rc124)**으로 실패, FED model 없음. 전체 harness는 FAILED다. 단일 표본이며 개선율/peak memory 효과를 주장하지 않는다.
- **GLM 분별 검증:** 동일 script/config/source metadata의 제한된 host component probe(워크로드 runtime/benchmark 아님)에서 `coverage=true`, nodes10,000, `RESOURCE_EXHAUSTED`, feasible incumbent 있음, boundPrunes7,458, wideBoundRelaxations77. 경고 `origin196 width10`이 남은 채 coverage를 통과한다. Incumbent를 최적해로 발행하지 않았다. 현재 탐색 지연이 wide factor 단독 원인인지는 추가 profile 없이는 미확정이다.
- **검증 중 수정:** 무조건 cache 비활성화는 재귀 재계산을 늘려 폐기. 모든 UNKNOWN input을 거절한 중간안은 nested-loop entry cost 회귀를 깨뜨려 activation 조건으로 제한. Cache-size 증가를 요구한 중간 test는 induction이 두 key를 미리 생성할 수 있어 부적절했으며 key snapshot/hash 불변식 검사로 교정했다. 실패 로그는 보존했다.
- **별도 잔여 버그:** conditional-return function을 loop body에서 호출하는 fixture가 eager `MemoShapes.drain`에서 수렴하지 않는 기존 공백을 발견했다. 수정 전 class도 동일 fixture25초 timeout으로 재현. `preexisting-loop-shape-convergence.md`에 최소 DML/stack/비회귀 근거 보존. Conditional 함수 내부 loop의 phase/exit 테스트 통과를 callback-in-loop 해결로 혼동하지 않는다.
- **수정으로 인한 버그 가능성/감지:** Full semantic context key는 projected key보다 cache 수가 커질 수 있고, head snapshot 생성은 O(H) 비용이다. 완료 조건 변경/다중 반환/호출frame/0·1·다회 경계의 누락은 새9개 및 기존 bound-return/activation/privacy/loop fixture로 감지한다. Cache는 request-confined다. 추가 completion resolver dependence가 생기면 snapshot 계약을 재검토한다.
- **다음 최소 작업:** 동일 Docker에서 global search/bound/cache 작업량을 분리 profile한다. 합법적 domain을 줄이지 않고 certified lower bound 또는 정확한 origin별 scope 개선을 검토하며, full GLM OPTIMAL/emission/numeric parity를 새 acceptance로 남긴다. 별도 eager shape 수렴은 해당 최소 fixture의 transfer/join 단조성부터 조사한다.

## 사용자 요청에 따른 origin/main publication

- **상태/권한:** 사용자가 현재 작업의 commit 및 `origin/main` push를 명시적으로 요청했다. 이전 기록의 commit/push 미요청 상태는 이 요청 이후에는 적용하지 않는다. Fresh fetch 결과 origin/main은 기준 `4314a345c16c20752421ce6251bbc2f366c85825`와 같았다.
- **범위 결정:** Memo 기반 구현 자체가 untracked여서 마지막 shape patch만 독립 커밋하면 빌드할 수 없다. 같은 작업의 source/test, DML 실패 경로, harness, 설계 문서를 dependency-coherent commits로 발행한다. 다른 worktree/branch는 수정하지 않는다. 강제 push, 기존 원본/실험 삭제, 이력 재작성은 하지 않는다.
- **검증:** Publication 직전 Memo component266개와 harness41개 재실행 PASS. 기존 build/Docker/exhaustive 근거 및 알려진 실패를 작은 `docs/evidence/memo-publication-2026-10-14/` bundle에 복사했다. 약2.8GB의 raw 실험/엔진/의존성/compiled artifacts는 Git에 넣지 않고 서버에 보존한다. 민감정보 pattern과 binary 유입 검사를 수행한다.
- **잔여 버그 및 회귀 위험:** GLM180초 search timeout 및 callback-in-loop eager shape 수렴 공백은 그대로 남는다. Commit/push는 전체 GLM 성공 또는 legacy 제거 완료 선언이 아니다. Legacy 경로와 기존 미커밋 작업 내용은 보존한다. 원격 ref가 바뀌면 non-fast-forward를 무시하거나 덮어쓰지 않는다.
- **Publication 중 추가 확인:** 제거된 unused `FederatedTypePropagator`에는 Java caller가 없지만 old source receipt/plan-space ledger의 경로는 남아 있다. Ledger generator `--check`는48families 기대/51관측으로 source hash 검사 전에 실패했다. Generator/RulesCore/ledger 모두 fetched origin/main과 byte 동일하므로 기존 registry audit drift다. 재생성 시도도 write 전에 실패하여 ledger 변경은 없다. Threshold 임의 수정 없이 증거를 publication bundle에 남겼으며, 해당 legacy audit의 최신 인증을 주장하지 않는다.

## Oracle 비용 투영 후속: 유지할 계약과 중복 검사 단순화

- **문제/상태:** 사용자가 source-only projection, 정확한 primitive, UNKNOWN/soundness는 유지하고 native generation 효율 재평가·context 검사 단순화·optimizer 별도 분석을 요청했다. 기존 각1회 비교는 LogReg 전체 planning56.5% 증가, GLM300초 timeout이었다. 후속 단순화·평가·회귀 검증 완료, workload 시간 효과는 미측정.
- **환경/기준:** 독립 workspace `/home/mchoi/fedplanner-oracle-cost-20261013`; 이전 측정 소스를 commit `2aff96c87f`로 보존했다. 원본 workspace는 수정하지 않는다. 이전 [구현 결과](FEDPLANNER_ORACLE_COST_IMPLEMENTATION_RESULT_2026-10-13_KO.md)의 수치를 이번 수정의 성능으로 재해석하지 않는다.
- **관측/원인:** Oracle cache 결과가 같은 owner에서 반복돼도 rule List 교집합을 다시 계산했다. Source-grounded privacy 전제는 rule에 의존하지 않지만 rule×occurrence마다 반복했다. 실제 활성 문맥을 합치거나 shape-only dedup을 복원하지 않고 이 두 반복을 줄일 수 있다.
- **해결 요약:** owner별 단일 교집합 상태를 유지하고 동일 Result identity의 membership 교집합만 생략한다. LinkedHashSet으로 rule ordinal 순서를 보존한다. Roles/coverage/진단/activation/Oracle 요청은 모든 문맥에서 유지한다. Privacy grounding은 occurrence당 한 번, legality는 기존 rule×grounded occurrence 순서로 검사한다.
- **수정 파일:** `MemoRuleBuilder.java`, `MemoOwnerRuleIntersectionTest.java`, `MemoFederatedModelTest.java`, [후속 평가 보고서](FEDPLANNER_ORACLE_COST_FOLLOWUP_2026-10-14_KO.md), 본 기록. Source projection/primitive 계산/Oracle/optimizer production 소스는 수정하지 않았다.
- **검증:** 수정 전 기존49 tests PASS, 신규 회귀 포함 behavior lock16 tests PASS. 중복 known/unknown 문맥 순서, unresolved occurrence별 진단, rule 순서, 같은 owner의 ungrounded→protected-grounded 문맥을 확인했다. 독립 reviewer가 계획·diff의 soundness를 검토했다. Maven compile PASS40.261초; reviewer의 작은 보강 후 javac PASS. 최종43 classes/407 tests PASS 및 git diff --check PASS. Known stale golden inventory class5개는 제외했고 해당 실패는 미해결로 유지했다. 중간/최종 테스트 수를 합산하지 않는다. 근거는 `experiments/oracle-cost-followup-20261014/`의 명령·로그다.
- **Native 생성 평가:** LogReg prefixRequests0/evidenceReuses0이지만 non-FED determinant 영역이 생략한 completion 배수가 없어 최적화 무효로 단정하지 않는다. Native API 계약과 고차 Nary pruning은 유지한다. Full-context catalog 요청188→8,880은 추가 중복 제거의 조사 대상이며 private cache key를 복제하는 우회는 추가하지 않았다.
- **Optimizer 분석:** solver 코드는 baseline과 같고 nodes/cost evaluations/pruning도 동일하다. Movement callback 내부 문맥 순회, grouped factor의 primitive/BigDecimal/배열 복사, check 내부 문맥/키 할당을 후보로 분리했다. Profile이 없어 시간 증가의 원인은 미확정이다. GLM timeout도 optimize 내부 어느 하위 단계인지 아직 모른다.
- **잔여 이슈:** 이번 단순화의 실제 workload 효과는 미측정이다. 기존 GLM 완료·numeric parity gate, LogReg 전체 성능 목표, 기존 training inventory golden drift는 해결된 것으로 표시하지 않는다. 사용자 제한에 따라 새 Docker/LogReg/GLM workload는 실행하지 않았다.
- **잠재 회귀 위험/감지:** owner 밖으로 identity cache를 공유하거나 coverage까지 건너뛰면 잘못된 domain/complete 판정이 생길 수 있다. Cache 수명은 owner 내부로 제한하고 UNKNOWN 반복·순서·진단 회귀로 검사한다. Privacy premise를 owner 전체 boolean으로 축약하지 않으며 mixed-occurrence 회귀로 확인한다.
- **의사결정 근거:** 수정은 원래 합법성 및 정확한 비용 의미가 같은 반복에 한정한다. 근거가 없는 family pruning, UNKNOWN→NO, source identity 병합 또는 부정확한 비용 합산은 도입하지 않는다.

## Oracle 호출 전 full-identity 중복 제거

- **문제/상태:** 사용자가 동일 Oracle 입력의 반복 요청8,880회 중cache hit7,823회를 호출 전에 제거하도록 명시적으로 요청했다. 이전 교집합 최적화는 Oracle 진입을 줄이지 못했다. 구현·회귀·독립 검토 및 변경 전후 Docker 각1회 측정 완료.
- **기준/환경:** baseline `2cf36e8d80`의 source/classes를 `experiments/oracle-request-dedup-20261014/baseline-engine`에 동결했다. 새 요구사항5에 따라 LogReg 변경 전/후 각1회 비교를 실행한다. 원본 workspace, 기존 실험 evidence, 기존 GLM 상태는 바꾸지 않는다.
- **원인:** context마다 fresh HOP과 별도 hint용 HOP을 만들고 canonical 문자열을 생성한 뒤 Oracle cache에 진입했다. 같은 native 결과라도 activation/UNKNOWN/privacy 의무는 별개라 context 자체를 삭제하는 방식은 사용할 수 없다.
- **해결:** `MemoNativeRules`의 canonical writer를 공유하는 prepared Request API를 추가했다. Builder는 기존 완전한 key의 immutable Result만 build-local map에서 재사용한다. Noncacheable은 매번 평가한다. Context별 검사·진단은 그대로 실행한다. StringBuilder와 immutable String interner로 중복 문자열 생성을 줄이고 같은 contextual view에서 hint를 얻는다.
- **수정 파일:** MemoNativeRules, MemoRuleBuilder, MemoShapes(hint helper만), FederatedPlanMemo trace; MemoNativeRequestTest 신규/owner 회귀; Docker harness와parser테스트; [상세 보고서](FEDPLANNER_ORACLE_REQUEST_DEDUP_2026-10-14_KO.md).
- **검증:** 수정 전23 tests PASS, clean compile38.989초 PASS와review수정javac PASS; targeted29 PASS; 최종44 classes/413 tests PASS, Python45 PASS, py_compile/diff check PASS. 기존 golden drift class5개는 제외했다. 독립 reviewer가 synchronized 계약·full key·mutable buffer/HOP 경계·UNKNOWN·noncacheable 동작을 확인했다.
- **변경 전 측정:** 새 baseline LogReg1회 PASS, requests8,880/hits7,823/misses1,057/rules1.948574090초/planning40.489338563초/runtime2.200초. Exact objective와 CP/FED numeric parity 정상. 이전40초대 측정을 이번 baseline으로 재사용하지 않았다.
- **변경 후 측정:** LogReg1회 PASS. 요청1,057/hit0/miss1,057, prepared8,880/reuse7,823, canonical 문자열 생성77/재사용7,823/noncacheable980. 요청88.1% 감소, Rules1.579905977초로18.9% 감소했다. 전체 planning39.712663042초(-1.9%)지만 runtime2.819초로증가했다. 원래 exact objective, native rules920, nodes2,933,891, definition/supply 진단과8×2 출력 parity가동일하다. 단일표본이며 일반적인 speedup이나optimizer개선으로과장하지않는다.
- **잔여 이슈/위험:** 새 key probe/shape snapshot은 context마다 여전히 필요하다. 문자열 생성 수와 RequestKey 생성 수를 혼동하지 않도록 counter를 `canonicalStrings*`로 명명했다. 중복 요청 제거가 exact search 자체를 줄인다고 주장하지 않는다. Request는 prepare한 unmodified private view로 즉시 평가하는 계약이며 mutable HOP을 cache 값으로 공유하지 않는다.
- **의사결정 근거:** 기존 canonical identity와 cacheability 계약을 그대로 사용한 동일 입력 결과 재사용이다. Oracle family capability나 전역 합법 공간, 원본 비용 계산을 변경하지 않는다.

## Optimizer 실제 병목 profiling 및 개선 설계 (분석 완료, 개선 미구현)

- **문제 정의:** LogReg optimizer 25.20→39.08초 증가 및 GLM 300초 timeout의 내부 원인은 기존 trace만으로 확정되지 않았다. 사용자 요청에 따라 production 코드 변경 없이 분석했다.
- **환경/재현:** baseline `abb5a4822a` LogReg와 current `25662ee7b4` LogReg/GLM을 Docker 공식 `run_LAN_docker.sh --joint-boundary-e2e --profile-jfr`로 조건별 1회 실행했다. 4 CPU / 8 GiB / worker 3 / timeout 300초. 명령·원본 경로는 `experiments/optimizer-profile-20261014/`에 기록했다.
- **분석 방법:** JFR 실행/할당 stack, CPU/heap/GC 및 외부 coordinator `/proc` 관측을 결합했다. 기존 정확한 전체 trace와 표본 기반 하위 단계 관측 구간을 구분한다. 모델/solver/정확성은 독립 검토했다.
- **LogReg 결과:** baseline/current optimizer는 JFR 사용 조건 24.599641 / 51.600745초, 같은 2,933,891 nodes와 exact objective로 PASS였다. 과거 비계측 25.20 / 39.08초와 구분한다. Optimizer 실행 표본 중 movementGroupCost는 15.79%→51.59%, 해당 weighted allocation은 8.558→41.910 GiB였다.
- **GLM 결과:** 300초 timeout/rc124이나 299초 JFR을 정상 회수했다. Reduced-core search 관측 구간은 281.751초, movement 68.77%, input legality 28.74%, guardValue 34.58%였다. Normalization 표본은 1.110초 구간으로 주병목 가설을 기각했다. GC pause는 1.679초, heap 관측 최대는 467.35 MiB, coordinator RSS 최대는 988,276 KiB였다. 유효한 245.096초 구간의 CPU 시간은 249.33초였다.
- **개선 설계:** immutable guard-phase index와 exact-frame canonical decision 재사용을 먼저 적용한다. 이후 full semantic context의 반복 shape proof cache, 기존 DIRECT/IMPOSSIBLE gate 뒤 lazy movement projection, legality용 구조 재사용을 검토한다. Solver key/domain 할당은 LogReg 공통 병목으로 다음 단계다. Eager source Cartesian catalog, shape-only dedup, UNKNOWN 축약을 금지한다.
- **수정 파일:** 분석 도구·evidence·문서만. [최종 분석·개선설계 보고서](FEDPLANNER_OPTIMIZER_PROFILE_AND_IMPROVEMENT_DESIGN_2026-10-14_KO.md), profile-comparison.json, optimizer-profile-summary.json, 독립 source/correctness review를 작성했다.
- **검증:** frozen source/classes 일치, 시작/종료 src/scripts 9,061개 파일의 hash 변경 0개. Docker model proof 각각 7 tests PASS, LogReg 양쪽 runtime/numeric gates PASS. JFR 표본/할당 합계 검증, 후처리 syntax, diff check를 통과했다. Production/test 변경이 없어 전체 회귀 suite를 새로 실행하지 않았다.
- **잔여 이슈:** GLM 최적해·numeric parity·잔여 탐색량은 미확정이며 실제 개선은 미구현이다. 하위 단계 시간은 JFR 첫/마지막 표본 관측 구간이지 정확한 entry/exit timer가 아니다. LogReg `/proc` selector가 timeout wrapper를 잡은 자료는 제외했으며 재실행하지 않았다. GLM은 수정된 Java selector 자료만 사용했다. 단일 표본 및 profile overhead를 보고서에 명시했다.
- **잠재 회귀 위험:** 이번 production 변경은 없다. 향후 사전계산은 gate 순서를 바꾸어 미도달 분기의 UNRESOLVED를 먼저 발생시키거나, frame/phase/source identity를 합치면 의미가 바뀔 수 있다. 원본 evaluator differential 검사, exhaustive parity, selected-cost audit로 검증하도록 설계했다.
- **의사결정 근거:** CPU/할당 실측으로 callback 내부 반복 분석을 우선하며, feasibility/privacy/UNKNOWN/DEFER/worker binding/exact primitive/optimality를 그대로 보존한다.

## Optimizer 불변 분석 재사용 구현 (완료, GLM timeout 미해결)

- **문제/상태:** 사용자가 실측 보고서의 개선 계획 실행을 승인했다. Production 무변경 제약은 이전 분석 작업에 한정되었으며 이번에는 구현한다.
- **원인/접근:** 고정 guard/phase 검색, 반복 shape proof, 비용/legality 입력 준비 및 solver cache hit의 중복 할당을 줄인다. `experiments/optimizer-reuse-20261014/implementation-plan.md`에 구현·검증 순서를 기록했다.
- **환경/재현:** 기존 독립 workspace와 frozen25662ee7b4 엔진을 기준으로 Docker LogReg/GLM before/after 각1회 비교한다. 성능 판정은 non-JFR 동일 조건이며 기존 JFR은 원인 근거로 사용한다.
- **수정:** `MemoProgramFacts`에 frozen sparse guard/phase index, exact syntactic-frame CanonicalGuard 및 immutable occurrence context 재사용. `MemoShapes`에 full-input completed proof LRU(각4,096)와 reuse counter. `MemoOptimizer`에 동일 tuple identity의 lookup-only probe를 넣고 hit의 key/clone 할당 제거. `FederatedPlanMemo`에 trace-property 조건부 최종 reuse 통계 추가. Model/movement/privacy/cost 평가와 native universe는 변경하지 않았다.
- **검증:** Maven test-compile PASS37.678초, 최종46classes/423tests PASS48.832초, harness45PASS, 독립review approved, diff check PASS. Clean compile에서 missing Arrays import1곳을 확인해 수정했다. 중간 Facts35PASS는 sparse 최종판 이전이므로 최종 통합검증과 구분한다. 알려진 stale training inventory class는 기존처럼 제외하고 미해결로 남긴다.
- **변경 전 측정:** LogReg PASS, Rules1.937784423초/Model0.754423339초/Optimization36.851172142초/전체 planning39.645421260초, nodes2,933,891 및 exact objective 유지. GLM은300초 timeout. Before source/classes 동결 일치, Java coordinator PID 기반 CPU/RSS 관측 유효. After도 검증한 새 frozen engine으로각1회 완료했다.
- **변경 후 결과:** LogReg planning32.076970336초(-19.09%), optimization29.823222436초(-19.07%), CPU52.95초(-15.78%). Binding proof97.01%/repeat proof98.17% hit. Exact objective, nodes, native rules, supplies, FED CSV byte hash, Oracle 요청, runtime audit/action diagnostics가 동일하다. Cache hit(+12)/support query(+6)는 소폭 차이가 있어 전체 counter 동일이라고 주장하지 않는다. GLM은전후모두300초 timeout으로 완료/최적성/출력 동등성 미검증. HWM은LogReg710,988→725,480KiB,GLM715,800→783,360KiB로증가했다.
- **잔여 이슈:** GLM timeout과 기존 stale training golden은 미해결이다. 별도 movement descriptor/primitive cache와 normalization 전략은 추가하지 않았다. 새로운 GLM hotspot은 이후 profile로 확인해야 하며 이전 분포를 그대로 적용하지 않는다. 각 조건1회 결과이므로 일반적인 speedup 또는 분산 추정으로 확대하지 않는다.
- **잠재 회귀 위험/감지:** full context identity, findFirst 순서/null, cyclic proof, DIRECT/IMPOSSIBLE gate, UNKNOWN/DEFER, worker binding 및 exact cost는 유지했다. 새 cache는 frozen model-local/synchronous 수명에 한정된다. Selected rule/layout payload 제외는 shape evaluator가 그 값을 읽지 않는다는 경로 검토로 확인했고 headIsBound는 snapshot했다. Cache on/off, frame/depth/cardinality/UNKNOWN/eviction, hash collision 및 기존 exhaustive/privacy 회귀로 감지한다.
- **문서/근거:** [구현 및 측정 결과](FEDPLANNER_OPTIMIZER_REUSE_IMPLEMENTATION_RESULT_2026-10-14_KO.md), `experiments/optimizer-reuse-20261014/optimizer-reuse-comparison.json`, `independent-review.md`, `verification.json`. 두 실험 container 종료와 frozen input 일치 확인, 추가 workload 실행 없음.
- **의사결정 근거:** 이미 측정된 중복만 제거하고 후보 공간/검사/최적해 보장을 줄이지 않는다. 낮은 우선순위의 normalization 전략 변경은 보류한다.

## LogReg 반복 support/domain 계산 병목 해결 (완료)

- **문제/상태:** 이전 reuse 최적화로 LogReg planning39.65→32.08초가 되었지만 optimizer29.82초가 남았다. 사용자가 현재 병목의 정확한 확인과 해결을 요청했다. 이번에는 fresh JFR로 확인한 반복 support/domain 계산을 개선하고 전후각1회검증을완료했다.
- **기준/환경:** 기존 독립 workspace. baseline은 이전 최종 frozen engine이며 앞선 미커밋 변경을 모두 보존한다. Docker LogReg JFR before/after각1회만 실행하고 GLM은 이번 범위에서 제외한다.
- **방법:** fresh profiler로 현재 dominant stack을 다시 측정하고 source의 반복 입력·계산을 확인한 후 최소한의 정확한 개선을 구현한다. 이전 JFR의 hotspot 비율을 새코드의 병목이라고 가정하지 않는다.
- **검증/위험:** exact cost/feasibility/privacy/UNKNOWN/DEFER/worker binding을 유지한다. Domain backtracking, support budget, normalization invalidation 및 cache identity를 회귀로 고정하고 독립 검토한다. 계획과 evidence는 `experiments/logreg-bottleneck-20261014/`에 둔다.
- **확인한 원인:** fresh LogReg JFR optimizer34.797404037초/planning37.329601749초. `support`는optimizerCPU표본35.23%; revision이undo시증가해정확히복원된domain에서도cachemiss38,286,830회를유발하는구조였다. Bound11,135,047조회/11,132,120hit에도매번후보ordinal배열을생성했다. 분기선택의BitSet.cardinality224표본(8.45%)도확인했다. GC STW0.407초로주원인이아니다.
- **구현:** `MemoOptimizer` support/bound에공용full-BitSet조회probe와miss시immutable snapshot key를사용한다. Worklist revision은유지한다. Bound hit는budget/product순회전반환하고miss의certifiedMinimum/정확한minimum계산은보존한다. Domain cardinality는remove/undo두경로에서갱신하며assertion으로검사한다. `FederatedPlanMemo`에는supportCacheHits trace만추가했다. Movement/model/privacy평가기와cost합산은그대로다.
- **검증:** 수정전behavior30PASS, 수정후targeted30PASS, 독립25PASS; Maven test-compile42.981초PASS와최종assertion javacPASS, 최종47classes/426tests(-ea)47.326초PASS, harness45PASS, 독립reviewAPPROVED 및diffcheckPASS. Known stale training inventory class5개는기존대로제외했다. `after-engine`12498files를동결했고이전frozen소스대비production2+test1파일만변경했다.
- **전후 측정:** 동일Docker/JFR각1회PASS. Optimization34.797404037→23.714206977초(−31.85%), planning37.329601749→26.819446540초(−28.16%). Support cache miss/DFS38,286,830→171,750(−99.551%), afterhit38,226,419. Conditional-bound요청/hit, worklist방문, 후보제거, boundprune, wide-relax횟수는동일하다. Before supportCacheHits는미계측이며0으로간주하지않는다.
- **결과 보존:** exactobjective전체문자열, nodes2,933,891/nativeRules920/supplies1, FEDfingerprint, actiondiagnostics, 위반없는runtimeaudit,332bytesCSV의SHA-256가동일하다. CP/FEDmaxAbsDifference1.6653345369377348e-16도동일하다. 기존non-JFR시간과이번JFR시간은혼합하지않는다.
- **자원/잔여 비용:** coordinator관측CPU63.98→51.61초(−19.33%), optimizer가중할당추정39.031→30.120GB(−22.83%)지만HWM673,780→785,668KiB(+16.61%)다. 누적할당감소와residentmemory감소를혼동하지않는다. AFTER movement711/1696표본(41.92%)이남으며support333표본(19.63%)은lookup비용도포함한다. 새domainprobe229표본을포함해cache비용을비교했다. GLM과stalegolden은이번미해결상태를유지한다.
- **수정 파일/문서:** `MemoOptimizer.java`, trace만변경한`FederatedPlanMemo.java`, 신규`MemoOptimizerDomainCacheTest.java`; [상세 원인·구현·실측 보고서](FEDPLANNER_LOGREG_BOTTLENECK_FIX_2026-10-14_KO.md), `experiments/logreg-bottleneck-20261014/before-after-comparison.json`.
- **잠재 회귀 위험/감지:** immutablecheck/term의범위가바뀌는데cache를비우지않거나추가domainmutation에서cardinality를갱신하지않으면잘못된재사용/분기가생길수있다. 기존normalizationclear유지,fullBitSetequals/clone,mutationassertions,cacheoff및collision/backtracking회귀로감지한다. 향후mutation경로추가시같은invariant가필요하다. 메모리HWM증가의세부원인은이측정만으로분리하지않았다.
- **의사결정 근거:** 후보군/Oracle/privacy/cost semantics를바꾸지않고같은정확한입력의반복작업만줄였다. 전체source조합사전열거,근사minimum,UNKNOWN축약,검사생략또는runtimefallback을추가하지않았다. 실제1회쌍의개선만주장한다.

## LogReg planning 10초 목표 (완료)

- **문제/목표:** 사용자가 LogReg 10초 미만 최적화를 요청했다. 전체 Memo planning을 기준으로 해석하며 optimizer/학습 execution도 별도로 보고한다. 기준은 직전 최종 frozen engine의 JFR planning26.819446540초/optimizer23.714206977초다.
- **접근:** 현재 profile의 movement/context와 exact search 반복을 개선한다. 같은 owner에 속한 native/leaf-tree boundary cost의 상관관계를 보존하는 더 강한 admissible bound, immutable context/shape 분석 재사용을 먼저 검토한다.
- **검증 계약:** 동일 Docker workload·JFR·resource 조건, candidate별1회 측정. 변경없는 baseline 반복·host workload·GLM 실행은 하지 않는다. OPTIMAL/exact objective/출력 parity와 기존 privacy/UNKNOWN/DEFER/worker binding을 유지한다. 전체 source 조합 materialization이나 근사 pruning은 금지한다.
- **위험/근거:** 강한 bound는 비용 중복 합산과 lexicographic tie에 주의하고 exhaustive parity로 증명한다. 구조 재사용은 syntactic guard/full context identity 및 lazy diagnostic 순서를 보존한다. 구현 전 계획은 `experiments/logreg-under10-20261014/implementation-plan.md`다.

- **진행 중 검증 메모:** Shapes targeted 30개를 단독 cold JVM에서 baseline으로 실행할 때 기존 1,000-depth source-only fixture가 기본 stack에서 `StackOverflowError`를 냈다. 동일 baseline에 host 단위 테스트용 `-Xss8m`을 적용하면 30개 PASS였고 수정 후 같은 조건도 30개 PASS다. Docker 성능 측정 JVM 조건은 변경하지 않는다. 이 기존 깊이/stack 민감성은 이번 성능 개선으로 해결했다고 주장하지 않는다. Solver correlated-bound 신규 4개 및 기존 30개 테스트 PASS, 독립 solver/Shapes 검토 APPROVED. Harness 45개 PASS.
- **구현/통합 검증:** MemoOptimizer의 correlated native+boundary-message bound 및 불변 rule order, MemoProgramModel의 완료 activation/정확한 movement primitive 재사용, MemoShapes의 불변 dependency union/정규화 no-op, trace를 구현했다. 독립 solver/Shapes 및 movement 검토 모두 APPROVED. Maven test-compile39.540초 PASS, 최종49classes/432tests(-ea -Xss8m)44.433초 PASS, harness45PASS 및diffcheckPASS. Candidate01 engine12,509files를동결했다. 기준 frozen소스대비production4+test2파일만변경됐다.
- **Candidate01 실측/다음 수정:** 동일 Docker 1회는 결과 동등성·정확한 objective·CSV·runtime audit·Oracle·JFR·동결hash 전부 PASS지만 planning16.103018585초/optimizer13.563777113초로 10초 목표 FAIL이다. Nodes2,933,891이 그대로다. Movement inclusive 표본711→84, cost context543→28로 개선됐고, 남은 domain cache333/1110 및 hash/equality/HashMap tree lookup이 다음 병목이다. `candidate02-plan.md`에 full domain identity의 compact 표현, 동일 bound 산술 생략, 정확한 탐색 상태 재사용 계획을 기록했다. Candidate01을 재실행하지 않고 별도 수정 후보를 검증한다.
- **Candidate02 통합 검증:** full domain mask(≤64)/BitSet(>64), nonlinear hash+exact equality, 동일 값·scale의 bound 갱신 생략, core owner 목록, reversible branch-neighbor count를 구현했다. Width63/64/65·high-bit undo·실제widehashcollision을 cache0/1/4096과 비교했다. Targeted36PASS 및독립검토APPROVED, Maven40.476초PASS, 최종49classes/434tests(-ea -Xss8m)47.838초PASS. 12,509files frozen candidate02 engine은 candidate01 대비 solver/test 각1파일만 바뀌었다. 동일 Docker/JFR 1회 측정을 진행한다.
- **Candidate02 실측/구조 개선 전환:** 각1회 Docker 결과 planning15.926899887초/optimizer13.318014527초, nodes2,933,891 및 모든 정확성gate 유지, 목표10초는FAIL이다. Candidate01 대비planning1.09%만개선됐고HWM667,616→875,108KiB로증가했다. HashMap tree/BitSet leaf 비용은사라졌지만 반복support/bound/invalidation과조합탐색이남았다. `candidate03-plan.md`에 singleton선택후잔여factor가forest일때 기존TreeCell로정확히완성하는구조개선을기록했다. UNKNOWNfallback,전체lex,finitebudget,exactcost보존을독립검토한다.

- **Candidate03 구현/소규모 검증:** reduced search에서 현재 singleton owner를 투영한 잔여 interaction graph가 forest이면 기존 TreeCell로 정확히 완성한다. Eligibility는 기존 deduplicated branchAdjacent를 먼저 검사하고 통과한 경우에만 factor bucket을 만든다. 기존 boundary message는 비용에 한 번만 포함하고, 재구성 child에는 제거된 leaf까지 넣어 전체 owner 순서의 lex tie를 보존한다. UNKNOWN이면 기존 enumeration으로 돌아간다. 남은 budget으로 모든 tail cell을 평가할 수 없는 경우도 시작 전에 기존 탐색으로 돌아가며, 시작한 cell은 global node 수에 청구한다. Frozen02 기준 새 semantic5회귀 PASS; structuralfixture는 동일 OPTIMAL/선택에서75→33nodes다. 최종 신규6개 포함 targeted42 PASS(-ea), 독립 source 검토 APPROVE. 전체 Maven/50class 회귀 및 단일Docker 측정은 진행 중이다.

- **Candidate03 통합 검증/실험 시작:** Maven offline38.931초PASS, 50classes440tests(-ea -Xss8m)44.190초PASS, 독립reviewAPPROVE 및diffcheckPASS다. Frozen engine12,512files와live source가일치한다. Candidate02대비production2+test1, 작업전체production4+test4다. 동일Docker/JFR LogReg1회만실행하도록측정lane에전달했다. 결과판정은아직대기중이다.

- **최종 결과/상태:** Candidate03의 동일Docker/JFR 단일1회가 모든17개primarygate 및forest검증을PASS했다. 전체planning26.819446540→6.672535049초(−75.12%), optimizer23.714206977→4.237294143초(−82.13%), nodes2,933,891→625,916(−78.67%)으로10초목표달성이다. Forest8,716attempt/3,310completion/570,702chargedcells가포함된다. Exact objective전체문자열·332bytesCSVhash·fingerprint·runtimeaudit33rows·Oracle·nativeuniverse·workers/supplies가동일하다. Compilation8.057937초/planning포함, 별도trainingexecution3.165초이며학습시간개선을주장하지않는다.
- **자원/병목 근거:** coordinatorCPU51.61→30.50초,HWM785,668→721,656KiB,optimizer할당weight30.120→4.857GB다. Conditionalbound11,135,047→207,924,worklist13,616,123→409,814로반복전파/하한작업이감소했다. Costeval4,828→5,103과costcachehit3,407→2,278,731은DPedge평가로증가했으며모든counter감소로과장하지않는다.
- **검증/수정 파일:** production MemoOptimizer/MemoProgramModel/MemoShapes/FederatedPlanMemo, 테스트BoundaryBound/MovementReuse/DomainCache/ForestTail. Maven38.931초PASS,50classes440tests44.190초PASS(-ea-Xss8m),기존미변경harness45PASS,독립reviewAPPROVE,동결12,512files/Docker입력hash일치,JFRdataloss0. [최종보고서](FEDPLANNER_LOGREG_UNDER10_RESULT_2026-10-14_KO.md), `experiments/logreg-under10-20261014/candidate03-evaluation.json`과`candidate03-differential.json`에근거를남겼다.
- **잔여 이슈/회귀 위험:** 각후보1회관측으로일반입력10초보장을주장하지않는다. GLM과stalegolden5개는미해결이다. Movementfailureinjection/IMPOSSIBLE-only/capacity전용fixture공백및기존deepstack민감성은보고서에명시했다. Factor scope/domainmutation변경시forest구조및reversiblecount를재검증하고,UNKNOWN/finitebudget/globallex/exactcost를기존회귀로검출한다. 의사결정은플래너내정확한계산재사용과조건부트리DP이며Oracle/privacy/runtimefallback규칙은변경하지않았다.


## LogReg 최적화의 origin/main 통합 게시 (게시용 검증 완료)

- **문제/범위:** 사용자가 검증한 변경의 commit/push를 요청했다. 독립 구현 workspace의 origin은 로컬 원본을 가리키므로, 원본 origin인 `git@github.com:min-guk/systemds.git`의 실제 main `f9faf7b97a`를 확인하고 별도 publication worktree에서 통합한다. 원본의 미커밋 GLM 작업은 변경하지 않는다.
- **원인/통합:** 독립 snapshot과 main이 같은 Memo migration을 각각 추가한 이력이므로 snapshot 전체 merge 대신 기능 커밋만 cherry-pick했다. Main의 공유 privacy/resolver, request 소유권 검증, function-call index, conditional-return 전체 ShapeContextKey를 유지하면서 source projection·Oracle request dedup·exact optimizer를 결합한다.
- **수정/검증:** Model/RuleBuilder/Shapes 충돌을 양쪽 의미를 보존해 해결했고 Facts index는 자동병합을 검토했다. Main 전용 MemoAnalysisReuseTest의 Result 생성자에 original.requests() 인자를 추가해 기존 assertion을 유지했다. 최초 compile 실패를 보존하고 최종 Maven25.059초PASS,55classes474tests120.506초PASS,Python harness45PASS,독립review 승인 및 source12527파일 frozen엔진 검증을 완료했다.
- **위험/잔여:** full shape-context identity는 정확성을 위해 유지하며 unit suite 시간이 이전44초보다 길다. 단일 stack의 guard/frame lookup 관측만으로 원인을 확정하지 않는다. 통합본 Docker LogReg1회 검증은 아래 결과대로 완료했다. 게시에는 정상 fast-forward push를 사용한다. 근거는 `docs/evidence/logreg-main-publication-2026-10-14/`다.

- **통합본 단일 Docker 검증:** LogReg1회 planning7.770025473초/optimizer3.604053576초로10초목표PASS다. Compilation9.103289초/별도training2.730초,625,916nodes,정확objective·CSVbytes·fingerprint·actions·runtimeaudit·Oracle/nativeuniverse가기준및Candidate03과동일하다. 18개gate및JFR/frozeninput검증PASS,엔진12,527files다. CoordinatorCPU33.07초/HWM1,115,208KiB로Candidate03보다증가한값을그대로기록한다. Rules3.028초/Model1.035초 증가의원인을단일관측만으로확정하지않는다. 강제push없이원격main의기존f9faf7b97a에서정상fast-forward로게시한다.


## GLM·LogReg 전체 Memo planning 3초 목표 (진행 중)

- **문제/환경:** 사용자 목표를 두 실제 ML training Docker workload의 전체 Memo planning 각각 3초 이하로 강화했다. 공개 main f8ff5c4f4d를 별도 worktree/branch에서 최적화한다. 기존 LogReg baseline 7.770025473초를 재사용하고 GLM baseline은 같은 frozen engine으로 1회 측정한다.
- **원인 근거:** LogReg publication Rules 3.028초, Model 1.035초, Optimizer 3.604초. 저장된 JFR의 Rules 표본에서 PhaseEvaluation.headIsBound 중첩 위임과 stream anyMatch가 반복되는 것을 확인했다. Full shape context는 conditional-return 정확성 때문에 유지하며, 먼저 중복 wrapper/stream을 제거한다. Solver는 조건부 forest DP에서 full-scope tuple과 동일 children 배열을 반복 생성한다.
- **구현 계획:** experiments/memo-under3-20261014/implementation-plan.md. Regression을 먼저 고정하고 shape lookup/solver invariant 재사용을 구현한다. GLM 개선은 새 profile 근거로 결정한다.
- **정확성 원칙:** feasibility/privacy/UNKNOWN·DEFER/worker binding/exact cost/최적해/global lex/finite budget을 유지한다. 후보 임의 축소, source Cartesian materialization, runtime fallback, workload 축소는 하지 않는다.
- **검증/잔여:** 아직 구현·3초 달성을 주장하지 않는다. 각 candidate/workload 1회, frozen 입력·공식 Docker·정확성 gate와 CPU/RSS/JFR 근거를 누적 기록한다. 기존 stale inventory 5개는 별도 미해결이며 PUBLIC 회귀는 사용자 요구대로 유지한다.
- **위험:** mutable head state 또는 completion-only guard를 잘못 공유하면 정상/종료 환경을 합칠 수 있다. 실제 full-context snapshot과 query-order/loop/call-return 회귀로 검출한다. Solver scratch는 cache miss 시 복제 및 평가 순서를 보존해야 한다.

- **중간 회귀와 수정:** 첫56class/477test 통합에서476개 통과, 1000-source cost projection 회귀1개가 heap OOM이었다. Row proof closure마다 전체 unselected 배열을 보유한 것이 원인이다. Model-local 불변 unselected 배열 하나를 공유하고 모든 scoped domain이 singleton인 경우에는 기존 lazy checker를 그대로 사용해 불필요한 eager projection을 없앴다. 검사를 생략하지 않으며 재검증 중이다. 첫 측정 전 발견한 실패를 candidate01-unit-preliminary.log에 보존했다.

- **Candidate01 검증 완료/측정 준비:** MemoShapes는 full context를 유지한 채 같은 PhaseEvaluation wrapper 중첩만 제거했다. MemoProgramModel은 COMPLETE activation row별 입력조건을 소비자/source 변수에만 연결하고 실제 layout pair의 legality를 lazy 재사용한다. Movement aggregate primitive와 평가 순서는 유지하고 exact identity에 해당하는 source 변수만 scope에 남긴다. MemoOptimizer는 forest scratch tuple/children topology를 재사용한다. GLM의 알려진 DataGen nrow literal-fold lowering을 엄격한 provenance 검사로 지원했다. Maven40.268초PASS,57classes485tests65.790초PASS,Python45PASS,1001-source6tests7.954초PASS,독립 input/movement 검토 승인이다. Frozen engine으로 각 workload1회 측정하며 아직3초 성공을 주장하지 않는다.

- **Candidate01 실제Docker 결과:** 각1회 LogReg6.745706925초(Rules1.972505009/Model0.843202052/Optimizer3.828221272),GLM30.287181346초(Rules5.100638166/Model2.624926229/Optimizer22.418573633)로 모두3초FAIL이다. GLM baseline152.372563001초 대비80.12% 감소,LogReg7.770025473초 대비13.18% 감소다. 두 OPTIMAL·정확objective·nativeuniverse·costprojection·Oracle건수 유지,LogRegCSVbytes/audit동일,GLM8x1 CP/FED오차9.02e-17 및runtimeprivacygate PASS다. GLM baseline은nrowlowering실패였으므로 baselineCSV동등성은 주장하지 않는다. Candidate01평가의 실패gate는 시간2개뿐이며 freeze12534/JFR손실0/ownedcontainer종료를 검증했다.
- **Candidate02 근거/진행:** 새JFR에서 GLM movementGroupCost43.8%,activationUnion/guardUnionProbability 반복이 남고,forest 시도46331 중완성1076회다. Exact ordered immutable event identity의 bounded union reuse, 잔여cycle위 변수우선분기(순서변경만), publicviewAt의 full-context immutable shape bundle을 검토·구현한다. Candidate02-plan.md에정확성경계와평가계획을 남겼다.

- **Candidate02 기각한 변경:** Shape bundle cache는 실제 모델 구성에서 LogReg6,603요청/0hit,GLM21,971/0hit여서 제거했다. 첫 발견cycle 우선분기는 전체unit의LogReg check106,297→1,569,169/support70,334→1,747,847로 악화하여 제거하고 optimizer를Candidate01바이트그대로복원했다. 작은fixture의forest시도감소만으로 채택하지 않는다. 60classes490tests64.268초PASS는기각전기록이며 최종소스검증으로 혼동하지않는다. 유지하는변경은 exact ordered activation union reuse와 full shape key 사전hash/guard provenance다.

- **Candidate02 최종검증/동결:** 기각한분기를제거한소스로 Maven41.930초PASS,59classes488tests59.279초PASS(-ea-Xss8m),기존미변경harness45PASS,union독립검토APPROVED,diffcheckPASS. 두Docker workload각1회측정전엔진을별도동결한다. 아직3초성공은미확인이다.

- **Candidate02 실측:** LogReg6.885923628초(+2.08%),GLM29.124879859초(−3.84%)로 두시간gate미달,나머지exact/runtime/freeze/JFRgate전부PASS다. GLMoptimizer22.4186→17.9858초,union77,929요청/77,321hit,movementinclusive613→147표본으로재사용효과는확인했다. 반면Rules5.1006→8.0262초가상쇄했다. 단일측정만으로Rules증가의전부를한변경탓으로단정하지않는다. 각1회동결12,543files/자료손실0/ownedcontainers종료검증완료다.
- **Candidate03 진행:** 현재domain에여전히포함된완료YES tuple만재사용하는support witness를구현했다. 제거/undo,부분YES완성,ordinal64,hash충돌,normalization,UNKNOWN/budget46회귀PASS다. Shape는payload뿐아니라activation과hidden call completion·다른boundreturn·head상태의dependencyclosure를키투영에포함한다. 관련58회귀PASS이나GLMphasecache435,708entries가남아3초효과는아직미확인이다. 정확성독립검토와통합검증후후보를동결한다.

- **Candidate03 검증 중 발견한hash충돌:** Candidate02에서exact syntactic provenance를보존하려고도입한ExactGuardAssignment record의hash와Set.sumhash조합이같은true개수의모든guard할당에동일hash를준다. 실제10guard/1,024상태가11hash에몰리는것을reflectionprobe로재현했다(exact-guard-hash-probe.log). 의미동등성은그대로두고fullfields+truth의비선형hash로수정한다. C03초기61classes503tests112.249초PASS는hash수정전이며최종검증을별도로남긴다.

- **Hash 수정검증:** ExactGuardAssignment의동등성은유지하고identity+truth비선형hash를적용했다. 동일1,024상태가11→1,024hash로분산되는것을실제Java객체로확인했고fullShapeContextKey회귀를추가했다. 3class12tests18.454초PASS,root독립검토APPROVED다. C03키투영의unitentries감소는LogReg37,211→31,841(−14.4%),GLM437,888→435,708(−0.5%)에그쳐GLM의큰효과를추정하지않는다. 다음실측전에최종통합suite를진행한다.

- **Candidate03 최종통합검증:** Maven47.937초후최종변경Shapes/회귀test를target에재컴파일했고,동일target으로61classes504tests90.788초PASS(-ea-Xss8m),기존미변경harness45PASS,diffcheck/optimizer/shape/hash독립reviewPASS다. 동결엔진으로각workload1회측정한다.

- **Candidate03 실측:** 동일 Docker/JFR 각1회 LogReg6.648638329초, GLM37.289869714초로 모두3초FAIL이다. GLMoptimizer17.985750513→14.626717090초(−18.68%),supportwitness19,574,133hit지만 Rules8.026216197→15.815756963초,Model2.960962484→6.681370121초로상쇄됐다. Shape/key/canonicalGuard가새profile의주요비용이며완료shape상태449,409개가남았다. OPTIMAL·exactobjective·출력·audit·동결12,553files·JFR무손실gate는PASS다. 성능향상을확정한것으로취급하지않는다.

- **Candidate04 진행:** canonical guard relevance를immutable dependency-set identity와exact frame으로인덱싱한다. 동일predicate를reference와비교한13회귀PASS,unit요청중LogReg99.46%/GLM99.97%가indexhit다(성능실측아님). Loop boundary는완료single-binding activation과정확shape계약이모두증명된경우에만source+head별universalcheck로분해한다. 불완전/상이한frame계약/retention초과/optionalproof예외는기존broadcheck로돌아가며,LOCALprivacy LEGAL/ILLEGAL/DEFER는실제layout요청이후lazy평가한다. 구현전새boundary4fixture의기존truth/exactcost baselinePASS를확인했고수정후독립검증중이다. 비용primitive와후보공간은변경하지않는다.

- **Candidate04 최종검증:** Maven42.280초PASS,기존61classes/505tests73.544초PASS와최종boundary6tests1.485초PASS(총62classes/511tests),미변경harness45PASS,독립boundary/shape검토APPROVED,diffcheckPASS다. Source/head별분해는새6fixture에서complete/partialTruth·exactBigDecimal비용·winner를보존하고cross-frame불일치시기존scopefallback을확인했다. 동일Docker각1회측정용엔진을동결한다.

- **Candidate04 실측:** 각1회 LogReg6.149657852초,GLM19.737127325초로이전대비7.51%/47.07%개선했으나둘다3초미달이다. GLM Rules6.579083/Model2.259770/Optimizer10.723301초. Exact objective·OPTIMAL·runtime numeric/audit·nativeuniverse·동결12,558files·JFR무손실gate전부PASS다. GLM allocation추정34.740→15.111GB,CPU81.88→58.61초,HWM1,598,896→1,375,668KiB이며단일관측임을유지한다.

- **Candidate05 근거/진행:** C04 GLM optimizer support가217/744표본,cache/key/domain154표본으로남았다. 이미증명된binaryYES tuple만currentdomain과교차하는positivepartner mask를추가한다. 임의조합생성/NO추론없이기존predicate결과를재사용하며totalrows는cacheEntries로제한하고normalization시초기화한다. Wide/nonbinary는기존fullwitness경로다. 신규6tests및기존23testsPASS,독립reviewAPPROVED. GuardDecision hash는동일hash값을유지하면서Objects.hash배열생성을제거하고relevanceindexSetN을boundedLinkedHashSet으로바꾸는작업을검증한다.

- **기각한 C05 key cache:** production밖exactimmutablecomponentidentity+headsnapshot key캐시실험은4096LRU에서도GLM19,890/532,452hit(3.74%),LogReg6,390/51,594(12.39%)로GLMguard작업1.61%만줄였다. 생산코드에채택하지않는다. 별도probe13회귀PASS는실험검증일뿐performance성공으로취급하지않는다. C04 residualgraph단위probe는LogReg84active/135edges,GLM170/379edges와degree2제거32/73을확인했으나3초달성또는전체tree해법을증명하지않는다.

- **Candidate05 검증/동결:** Maven46.775초PASS,64classes519tests81.786초PASS(-ea-Xss8m),미변경harness45PASS,독립support/shape검토APPROVED,diffcheckPASS다. GuardDecision extremeid/frame/canonicalalias hash동등성회귀를수정전2PASS·수정후2PASS확인했으며최종shape관련15PASS다. Unit LogReg nodes92,738유지에서supportquery57,746→54,766,cachehit30,338→3,518,positivebinaryhit110,430/retainedrows2,182다(성능실측아님).

- **기각한 static star bound:** 현재dynamiccorebound에frozenanchor별조건부minimum합을MAX로보강하는실험은52정확optimizer회귀PASS이나LogReg/GLM100kbudget단위fixture모두추가prune0이었다. GLMcostevaluation14,901→20,634로증가하고nodes·prune·forestcounter가동일해production에채택하지않는다. 개별변경은증거에따라기각하며3초목표달성을과장하지않는다.

- **Candidate05 실측:** 동일Docker각1회 LogReg6.029726604초(Rules1.747147034/Model0.715002047/Optimizer3.402542805),GLM15.831163478초(Rules6.861084711/Model2.034918229/Optimizer6.763692807). 두3초gate만FAIL,나머지정확cost/OPTIMAL/runtime/audit/동결12,562files/JFRgate모두PASS다. C04대비GLM전체19.79%·optimizer36.93%감소하였지만LogRegoptimizer19.64%증가해모든phase향상을주장하지않는다. GLMpositivebinary9,543,637hit,allocation11.008GB,CPU48.54초,HWM1,458,724KiB다.
- **Candidate06 변경/검증:** exactshape-key guard builder의중간Set해싱과NO_SELECTION head snapshot생성을제거했다. Initialfeasibility의영구domain위에서모든값이YES인unary/emptycheck만사전증명해제거한다(UNKNOWN/NO유지). 잔여각component가tree/unicycle임이증명될때만cycleowner우선분기하여기존exactforestDP를앞당긴다. 전체66classes529tests73.318초PASS(-ea-Xss8m),Maven28.068초PASS,독립3검토APPROVED,diffcheckPASS. GLMfullunit모델의exactcost/fullselected는C05와동일;worklist5,792,351→3,198,043이나nodes1,333,810→1,424,110이므로Docker시간효과는미확인이다. 단위결과를실제workload성능으로표시하지않는다. C06엔진동결후각1회측정한다.
- **기각한 binaryNO cache:** C06외부prototype63정확회귀PASS이나GLM100kprobe에서NOhit2,561중2,273은기존cache대체,checkeval376/query295감소에그치고탐색구조불변이라생산반영하지않았다.

- **Candidate06 실측:** 같은Docker각1회LogReg5.297881735초,GLM13.996137711초로C05대비12.14%/11.59%감소했으나3초미달이다. LogRegnodes37,499/GLM685,615,exactcost/fullruntime/audit/native/12566동결/JFR무손실PASS. CPU29.99/47.64초,allocation3.077/9.994GB,HWM895,640/1,349,944KiB. 남은GLM Rules6.139058/Model2.006728/Optimizer5.634159초다.
- **Candidate07 검증진행:** Oracle completekey를contextualHop복제전에생성하는descriptor를독립fixture에서검증했다. Canonicalwriter를재사용하고sharedinputalias의마지막shape적용까지기존view와동일하게유지한다. Costfactor는completeprojection의rawtarget/source-output-slot/headlayout별동치류로만평가를재사용하며항상같은costclass인변수를해당factor에서만제거한다. Original변수/nativecost/feasibility/lex는유지한다. GLMfullunitnodes1,424,110→215,261,exactnumericcost/coststring/fullselected동일이나실제성능은미측정이다.

- **Candidate07 최종통합검증:** Native descriptor와complete factor costclasses,initialdomain에서cost불변owner제거,groupedterm의class교집합을채택했다. 같은Model객체의metadata유무solver비교에서LogReg/GLM모두OPTIMAL/exactnumericcost/fullselected동일. GLMunitcosteval115,885→5,318,widebound110,465→1,996;unit시간성능주장은아니다. W1LOCAL/REP구분·멀티반환slot0/slot1서로다른class·reuseoffmetadata없음검증을포함해73classes557tests76.165초PASS,Maven45.110초PASS,독립Model/solver/descriptor검토승인,diffcheckPASS다. 별도binarytautologyprobe는LogRegpredicateeval31%증가/GLMnodes1.9%감소에그쳐채택하지않았다. 서로다른JVM에서headordinal위치가달랐다는사실만으로lex회귀를단정한실험보고서는수정했으며같은Model검증을기준으로한다.

- **Candidate07 실측:** 같은 Docker 각1회 LogReg4.758684308초/GLM11.047010574초로 C06대비10.18%/21.07% 감소했으나 두3초gate는FAIL이다. GLM Rules5.657429277/Model2.106660781/Optimizer3.132354578초, nodes685,615→213,240이며 정확objective·OPTIMAL·runtime numeric/audit·nativeuniverse·동결12,584files·JFR손실0은PASS다. LogReg Rules는1.887560→2.475850초로증가했고 한 번의관측으로원인을단정하지않는다. CPU24.77/43.00초, allocation2.875/7.971GB, HWM950,776/1,596,320KiB. 53개evidence hash검증완료이며 상세근거는candidate07-results.md다.
- **다음 후보 분리 검증:** production 밖에서(1) 모든 context/continuation/scalar판정후 동일한순수 shape transfer 결과재사용,(2) COMPLETE row의fullTruth동치류로불변check축제거,(3) 같은occurrence/fullshape/frequency의nativecostvector재사용가능성을검증한다. Partial UNKNOWN/DEFER는원predicate를유지하고 원래 primitive순서와정확비용을보존한다. 단위fixture의counter를Docker성능으로표시하지않는다.

- **Candidate08 독립 검증:** Shape transfer는입력별context/continuation/scalar판정을먼저유지한뒤순수clone/apply/refresh만재사용한다. GLMunit245,022→1,800 builds, LogReg19,362→702이며cache-off와exactcost/fullselected동일,새7회귀PASS다. Sharedinput의마지막null이이전nonnullshape를지우던C07descriptor오류도기존applyShape(null) no-op와맞춰수정했다. Nativecostvector는동일occurrence/fullgeometry/frequencybits에서만재사용하며LogReg10,946/GLM30,739개의원래double값bit비교PASS와새3회귀PASS,독립review승인이다. Checkclasses는COMPLETE input/boundaryrows만대상으로live불변축을제거하며LogRegunitnodes14,210→4,359,GLM215,261→2,072와같은Model exactcost/fullselected동등성을확인했다. 새8회귀및기존optimizer83회귀PASS,partialUNKNOWN/rawordinalwitness/globallex를보존한다. 실제Docker성능은아직미측정이다.
- **C08 검토중위험:** Resolver copy-on-write는기존입력map의Map.copyOf 재생성유무에따른해시충돌key순서차이가가능하다. Map내용동등성뿐아니라이벤트/확률의binary64계산순서도보존해야하므로snapshot의동일재삽입동작을별도검증한후채택한다. Nativevector의차원변경loopfixture는cache-off baseline부터UNRESOLVED여서지원범위를확대했다고주장하지않고기존fail로그를보존했다.

- **C08 resolver 정확순서 수정:** 실제GuardDecision 6개hash=2000 충돌에서MapN직접재사용과기존재삽입이서로다른순서를보이고p=.37 확률의binary64결과가1ULP달라짐을재현했다. Copy-on-write를유지하면서미수정map은unmodifiableMap wrapper를통해기존Map.copyOf 재삽입순서를강제했다. 직접안RED/기준및수정안GREEN,기존포함49회귀PASS,독립review승인이다. Java17 Map.copyOf동작에대한회귀를추가했으며반환된snapshot은immutable이다. Same-owner실제row를만들려던반복함수fixture는서로다른compiledowner로내려가므로구조probe만보존했고incidental구조를고정하는테스트는production에채택하지않았다.

- **Candidate08 최종통합검증/동결:** Maven offline test-compile43.771초PASS,78classes578tests82.729초PASS(-ea-Xss8m-Xmx4g),독립shape/nativevector/check/resolver검토승인,diffcheckPASS다. 기존미변경harness45PASS는앞선검증을유지한다. 최종12,605files 엔진/manifest를동결했으며 동일Docker조건각1회성능측정을시작한다. 아직3초목표달성을주장하지않는다.

- **Candidate08 실측:** 동일Docker각1회LogReg4.159949175초/GLM8.929012602초로C07대비12.58%/19.17%감소했으나두3초gateFAIL이다. GLM Rules4.589380695/Model1.878161262/Optimizer2.294558832초,nodes1,954. LogRegnodes5,431/optimizer1.358026838초. Exactobjective/OPTIMAL/LogRegCSVbytes/양쪽CP-FEDnumeric/runtimeaudit/nativeuniverse/동결12,605files/JFRloss0전부PASS. Allocation2.564/6.021GB,STW.2769/.6695초;이번/procmonitor표본0이라CPU/RSS는보고하지않는다. 이후후보는정적contextguards분석재사용,정확shape-key객체할당감소,직접native정의source해석경량화,중복occurrencephasecache제거가능성을별도prototype로검증한다.

- **Candidate09 통합:** exact syntactic guard atom을identity/truth별로bounded재사용하고변경없는hash를사전계산한다. Context ancestry는region+exactframe의불변guards/reachability만재사용하며selection은매번live조회한다. Direct native root는기존preflight뒤eligiblechildlessdefinitions만가볍게해석하며반환witness/iterator/분기fallback과callback·map재삽입순서를보존한다. Occurrence phase cache는삭제하고binding cache/recursion/context/scalar/continuation/puretransfer를유지한다. 별도old/new동일JVM·동일facts비교에서LogReg/GLM fullrules/diagnostics/roles/exactcost/fullselected/breakdownPASS다. 독립review승인과새10tests(guard2/context2/resolver6)PASS를근거로통합하며공식Docker성능은아직미측정이다. 위험은syntacticalias/가변selection/해시충돌map순서/conditionalreturn의잘못된합병이며기존회귀와differential로검출한다.

- **Candidate09 통합검증:** Maven41.938초PASS,81classes588tests87.692초PASS(-ea-Xss8m-Xmx4g),diffcheckPASS와독립source검토완료다. 기존shape-key상관관계assertion은유지하고삭제한occurrence cache hit assertion만puretransfer hit으로변경했다. Engine동결후각workload1회공식Docker실측을진행한다.

- **Candidate09 실측:** 같은Docker각1회LogReg4.885487979초(+17.44%)/GLM7.628695414초(−14.56%)로두3초gateFAIL이다. LogRegRules1.902697/Model.916798/Optimizer1.900804초,GLMRules3.144879/Model2.062069/Optimizer2.243043초. Exactobjective·OPTIMAL·CSV/numeric/runtimeaudit/nativeuniverse·동결12,620files·JFRloss0등나머지20gate모두PASS다. Allocation2.227/4.552GB(−13.13%/−24.40%),STW.2448/.4738초. LogReg시간악화를감추지않으며단일관측만으로변경별인과를확정하지않는다. Foregroundpersistentmonitor로C08의backgroundmonitor조기종료문제를수정하여8/9JVMsamples·CPU26.66/32.88초·HWM657.3/1012.8MiB를확보했다.
- **C10 기각한캐시2개:** guard-projection identitycache4096은GLMhit0.089%/회피guard방문903,LogReg유효방문604에그쳐39정확회귀PASS에도채택하지않았다. Resolver immutablemap T(G) snapshotidentitycache4096도4focused+58regressionPASS지만nonemptyhit0.57~5.10%이며구성후coldentries로가득차채택하지않았다. 보고서는guard-projection-cache-probe-report.md와resolver-map-snapshot-probe-results.md다. 복잡성만추가하는cache는생산에넣지않는다.

- **Candidate10 통합:** staticcostinputdependency를원순서대로한번계산하고guardrelevance공통frame조회만hoist한다. SemanticContext도기존ShapeContextKey와동일한fullheadBitSet snapshot을사용하여매번HeadBinding객체/list생성을삭제한다. Livehead순서/값은매요청유지하며33focused회귀PASS/독립검토승인이다. MovementPrimitive는실제요청한upload/downloadcategory의성공한rawbytes만lazy재사용하며원MemoCostModel의공통pricing/UNKNOWN/오류/원시binary64/retainedprimitive교체전달을보존한다. MATRIX/FRAME W1/W3와실패재시도등7focused+61combinedPASS,독립review승인. Unit contextualviewLogReg13,444→7,668/GLM28,091→17,205지만아직실제성능주장은없다.
- **기각한narrowSCCproof:** entry-groundedfullcoststats의좁은SCC후보는LogReg기존guard없는즉시stop과중복되고GLMguardvisit1.05%만포함하며추가정적전파0이다. Frozenexactdims만으로cycleinvariant판정은허용하지않으며현좁은증명은복잡도대비효과가작아생산채택하지않았다(invariant-scc-analysis.md).

- **Candidate10 최종검증/동결:** Maven40.908초PASS,82classes595tests73.397초PASS(-ea-Xss8m-Xmx4g),Shapes/ModelCost 독립검토승인,diffcheckPASS다. 전송가격계산은원공통함수에유지하고임의guardleaf합병이나workload조건변경이없다. 엔진동결후양workload각1회공식Docker평가를진행한다.

- **Candidate10 실측:** 동일Docker 각1회 LogReg4.239108735초/GLM7.962475052초로두3초gateFAIL이다. C09대비LogReg13.23%감소/GLM4.38%증가다. LogReg Rules2.230556/Model.688046/Optimizer1.195370초,GLM Rules3.431948/Model1.968973/Optimizer2.352034초. Exactobjective/OPTIMAL/runtime numeric/privacy/audit/nativeuniverse/동결12,622files/JFRloss0등21gatePASS. Allocation1.847/4.101GB,STW.235819/.387964초,CPU23.71/36.49초,HWM588,460/1,055,256KiB. Movement contextualview19,744/17,023으로감소했으나total3초는아직미달이다. 단일실측이므로개별변경의속도인과를확정하지않는다.
- **다음후보 사전검증:** Rules-only frozengeometry shadow는LogReg2,857/GLM9,631개의완전한canonicalkey와ShapeHint가원descriptor와모두일치했고GLM120건은UNKNOWN frozeninput이라fallback했다. 이관측만으로descriptor생략을채택하지않으며availability/loophead/conditionalreturn에대한정적certificate를추가검토한다. Exact indexed private shape-key shadow는24회귀PASS,old/new동등성양방향불일치0이며단위LogReg71,779/GLM404,837keys에서반복97.20%/94.13%,atoms21/83을확인했다. 모두production밖실험이며실제성능주장은없다.

- **C11 fact lookup 단순화:** MemoProgramFacts의immutable입력edge를occurrence/position별privateint배열로동결하여매조회Long객체/해시검색을제거했다. Builder/publicedge순서/부재-1/등록값은유지한다. 신규전체edge+invalidquery회귀는변경전4testsPASS,변경후facts/provenance36testsPASS다. C10 GLM profile의inputBinding표본을근거로하며단독속도효과는아직주장하지않는다. Candidate11-plan.md에계획과검증경계를기록했다.

- **C11 head lookup 단순화:** 등록된동일Head객체를loop/slot별private배열로보관하여headIsBound/selectedHeadLayout의LoopSlot객체/해시조회를제거했다. 도달불가/zero-onlyloop는계속부재이며selectedordinal은매조회현재값을읽는다. 기존context/movement/loop-entry/shape-projection16회귀PASS(8.149초),독립검토대기다.

- **C11 indexed context 통합:** exact syntactic guard/loop/iteration/cardinality 원자에request-local ID를부여하고privateBitSet에직접기록하여매key의Set/Map복사와깊은해시를제거한다. 원래dependency투영과frame/depth/livehead전체를보존하며65,536원자한도초과는완전한legacykey로fallback한다. 실험모드/property/대형shadowmap은생산에서제거했다. Production-ready밖검증4focused+49regressionsPASS,이전양방향partition검증LogReg186,598/GLM984,986불일치0이다. 동일facts의OPTIMAL/exactcost/fullselected동등성을검증했으며최종통합build/suite와독립review를진행한다.
- **C11 기각한input-view단축:** 전체consumerclone을유지하고필요입력만shape해석하는shadow는관측graph/bytes가동일하지만보수적loopfallback후eligible LogReg1.36%/GLM2.14%뿐이다. 생략된다른input의applyShape/computeMemEstimate예외와headseed순서가일반적으로보존되지않아생산채택하지않는다. Frozengeometry만으로Rulesdescriptor를생략하는방안도정적availability/loopinduction증명없이는채택하지않는다. 보고서는input-view-shadow-report.md와rules-geometry-shadow-report.md.

- **Candidate11 통합검증/동결:** Maven43.948초PASS,84classes600tests83.363초PASS(-ea-Xss8m-Xmx4g;wrapper84.698초),두정수index와indexedcontext독립reviewCLEAR,diffcheckPASS다. Samefacts 비교에명시적OPTIMALassertion도추가했다. 정확한key원자65536한도fallback의안정성/완전성을검토했고publicmap순서나originalcostleaf는변경하지않았다. 동결후동일Docker각1회측정하며3초성공은아직미확인이다.

- **Candidate11 실측:** 동일Docker각1회LogReg3.861050224초/GLM6.776918729초로C10대비8.92%/14.89%감소했지만두3초gateFAIL이다. LogRegRules1.734332/Model.554416/Optimizer1.357890초,GLMRules3.136895/Model1.359842/Optimizer2.115568초. 총23gate중21PASS:exactobjective/OPTIMAL/전체native구성/runtimeCSV·numeric/privacy/audit/동결12,628files/JFRloss0를확인했다. Allocation1.774/4.002GB,STW.175542/.247769초,CPU24.69/30.13초,HWM523,708/702,320KiB. Shared48CPU호스트에비소유VSCodeJDT프로세스가새로존재했고실험중최대평균141%CPU를관측했으며임의종료하지않았다. 각1회관측으로환경노이즈와개별변경효과를분리해확정하지않는다. 다음은반복loop-headshape증명과동일한완전한view-recipe의객체구성비용을분리계측한다.

- **C12 alias 경로 단축:** CALL_ARG/nonempty DEFINITION의 불변 첫 child만 사전 연결한다. SELECT/FORMAL/CALL_RETURN/LOOP_HEAD/LAST 및 잘못된/순환 경계는 원 동작을 유지한다. 기존47회귀 및 동일facts 두모델 exactcost/fullselected/OPTIMAL parity PASS, 독립review CLEAR를 확인하고 생산에 반영했다. 단위방문 회피율 LogReg30.35%/GLM21.56%는 순회량이며 실제 시간 개선율이 아니다. malformed/cycle/semantic-boundary 두회귀를 추가했다. 계획 candidate12-plan.md; 통합 build/성능 검증은 미완료다. 잠재 위험은 향후 alias 분기에 부수효과가 추가되는 경우이며 관련 회귀와 코드 검토로 감지한다.

- **C12 반복 head cache 기각:** 초기 empty-head/fullcontext cache는 phase cache history에 따라 내부 head map을 달리 만들어 부정확했다. zero-miss publication과 value-change epoch까지 추가한 shadow에서도 LogReg17/GLM79 map·value 불일치(phase값overwrite0)가 발견돼 채택하지 않았다. 비교시 현재miss가 있는 관측까지 포함해야 기존결함을 드러낼 수 있었다. 강화 shadow4focused+40regressionPASS는 반례 검출에 대한 PASS이며 캐시 정확성 PASS가 아니다. repeat-head-qualified-shadow-report.md에 기록했다.
- **C12 movement recipe 사전증거:** 원계산을 항상 수행한 shadow에서 LogReg7,668중7,052,GLM17,205중16,489회가 같은 완전한 raw recipe measurement이며 불일치0이었다. 이는 unitfixture 구조량이고 실제속도측정이 아니다. 입력생략 없이 성공한 prefix와 완성measurement만 재사용하는 outside prototype은28관련회귀 및 두모델동일facts의exactcost/fullselected동등성을 통과했다. 독립검토 후 채택여부를 결정한다.

- **Repeat-head 반례 원인 확정/후속 기각:** 동일한 초기head bits여도 NO_SELECTION과 현재PhaseEvaluation 소유HeadSelection은 seed추가 이후 lookup응답이 달라진다. semantic key가 이 live wiring을 나타내지 않아 map/trace가 달라졌으며 단순epoch문제가아니다. NO_SELECTION으로만 한정한 shadow는 불일치0/44회귀PASS이나GLM회피8,946visits(해당Rules repeathead방문3.4%)에그쳐추가캐시를채택하지않았다. root-cause 및 no-selection-shadow 보고서에 관측과 한계를기록했다.
- **C12 정적 head index 사전검증:** 매key마다불변dependency의head Set.contains를반복하던부분을생성시ordinal배열로계산하는outside후보를작성했다. 현재headIsBound값과호출순서/신규BitSet은매query유지한다. 수정전12회귀PASS,수정후동일모델두workload분리Shapes동등성포함14PASS. 별도캐시수명/lookup결과재사용은추가하지않으며독립review후통합한다.

- **C12 통합:** alias chain·정적 head index·movement 성공prefix/exactrecipe measurement를채택했다. Movement35회귀PASS/독립reviewCLEAR,별도Shapes로동일facts exactcost/fullselected/OPTIMAL 유지다. Prefix는모든원shape해석순서를유지하고성공한apply단계만재사용하며실패edge는게시하지않는다. Raw null/UNKNOWN/NNZ/orderedinput/category/workers와캐시한도/실패재시도를검증했다. Model이보관하는것은immutable measurement이며mutableHop은cache에남기지않는다. 기존요청내frozenprivateview/singlethread계약에한정하며publicview를외부에서수정하면기존transfercache와마찬가지로위험하다. 전체compile/suite/Docker는아직대기중이다.

- **C12 통합검증/공식측정:** Maven51.446초PASS,86classes612tests87.247초PASS(wrapper88.562),독립reviewCLEAR,diffcheckPASS,12,642files동결. 공식측정중에는소유hostCPU작업을중단했다. LogReg1회3.751252388초로C11대비2.84%감소하지만3초미달; exactobjective/OPTIMAL/numeric/audit 유지. GLM및최종전체gate는측정lane에서완료후기록한다.
- **C13 map snapshot 후보:** 기존first4096상한cache가construction뒤cold해지는원인을확인하고,동일identity정확변환T(G)를성공한miss뒤clear-on-full하는outside후보를검증했다. GLM Shapes546,455/955,058(57.22%)와Model35,334/123,001(28.73%) hit,66회귀PASS/독립sourceCLEAR다. 새로운구조나근사화없이정확한MapN순서를유지한다. 다만동시target재빌드로구조probe는C11-compatible자료이며채택전frozenC12기준분리universe exactcost/fullselected parity를추가한다. 실제시간효과는미검증.

- **C13 map snapshot 채택:** frozenC12의존성에서동일facts/분리Shapes/universe/models cache0vs4096의두workload OPTIMAL/exactBigDecimal/fullselected 및모든owner rules·coverage·roles·diagnostics동등성을PASS했다. Source e2cbac4e/66회귀및추가2parityPASS/독립reviewCLEAR를확인해생산반영했다. 실험toggle은생산에포함하지않았다. 신규snapshot5회귀를전체suite에추가했다. 원Map의identity만재사용하고mutablebranch는계속복사하며T(T(G))와충돌키순서를보존한다. 전체build/Docker검증은대기중이다.

- **C12 최종실측 및 회귀:** 공식각1회LogReg3.751252388초(-2.84%)/GLM8.850169084초(+30.59%)다. 두시간gate만FAIL이고21/23gatePASS,두exactobjective/native구성/OPTIMAL/runtime및동결12642/JFRloss0유지. GLMRules4.558515/Model1.851988/Optimizer2.241848초,CPU36.68초/HWM832156KiB/할당3.929GB다. C11과GLMphaseShape388010,repeatProof67810,guardRelevance3000737, directroots226704등작업counter가같고할당량은감소했으므로단일측정에서특정변경의회귀원인을확정하지않았다. 관측회귀를숨기거나성능달성으로표시하지않는다. 자세한결과 candidate12-results.md.
- **C13 통합검증/동결:** map snapshot 변경에대해Maven43.238초PASS,87classes617tests90.120초PASS(wrapper91.372),diffcheckPASS,독립sourceCLEAR및frozenC12동일facts두모델전체parityPASS다. 12645files동결완료. 소유hostCPU작업을정지후동일Docker각1회평가한다. 반복headownershipsplit shadow는불일치0/44회귀PASS지만GLMrepeat-head방문회피8.2%에그쳐추가상태복잡도를채택하지않았다.

- **C13 최종실측:** 동일Docker각1회LogReg3.657053118초/GLM6.543782842초, C12대비2.51%/26.06%감소·C11대비5.28%/3.44%감소다. 두3초gateFAIL,21/23gatePASS이며OPTIMAL/exactobjective/nativeuniverse/runtime출력·privacy/audit/12645files동결/JFRloss0를확인했다. CPU22.24/29.84초,HWM556148/848452KiB,할당1.656/3.732GB,STW.211885/.286063초. LogReg탐색counter동일,GLM일부check/support/worklistcounter는소폭감소했지만단일측정으로원인을단정하지않는다. C13결과에3초달성주장은없다.
- **C14 phase guard 정적계산 후보:** 반복phaseProvesGuard가루프별고정guard集合과두frameprefix의canonical동등성을다시계산하므로,phase별known guard-id집합을한번계산하여기존guardRelevant index를재사용하는outside후보를작성했다. false-known도포함하며facts.guardValue의first-registration동작을유지한다. malformedframe/nullphase는원helperfallback, invalidloop는false다. 신구predicate outcome(예외class포함)회귀수정전1PASS·수정후32PASS; 독립source검토후채택한다. 실제성능효과는미검증이다.

- **C14 채택/검증 진행:** phase guard index SHA6ceb5587 독립 CLEAR 및 수정전1/수정후32회귀 PASS를 확인해 반영했다. Fair-union은 정확히0.5이며 canonical decision16개 이하인 root만 정수 cube DP로 계산한다. numerator와2^g 분모가 모두 binary64에서 정확하며 최대65535 internal states로 기존100000 예산 내다. 다른 확률/폭은 원알고리즘을 사용한다. 생산 최소판 SHA76323904e37e 독립 CLEAR,22회귀와 두 workload 동일facts exactcost/OPTIMAL/fullselection parity PASS, shadow raw-bit 불일치0. 두 변경의 실제시간 효과는 미확인이고 전체build/Docker 검증을 진행한다. 수정파일 MemoShapes/MemoProgramModel 및 두 신규회귀다. 잠재위험은 frozenfacts 변경과 확률증명범위 확대이며 원predicate outcome/넓은guard·다른확률 fallback 회귀로 감지한다. Private selection-chain head-cache 확대는 정확성 shadow 불일치0이지만 기존 direct NO/SELF보다 추가 재사용0이라 복잡성 증가 없이 기각했다.

- **C14 최종실측:** Maven46.959초/89classes621tests94.289초 PASS,12650files동결 후 공식각1회 LogReg3.923526476초(+7.29%)/GLM5.756094881초(-12.04%)다. 두3초gate FAIL·21/23PASS, 정확objective/OPTIMAL/native/출력/privacy/audit/동결/JFRloss0를 유지했다. LogReg optimizer .996→1.632초 증가가 관측총회귀를 설명하지만 단일측정으로 변경별 인과는 확정하지 않는다. CPU22.49/28.51초,HWM477492/695564KiB,할당1.601/3.494GB. 상세 candidate14-results.md.
- **C15 단순화 채택:** C13 allocation 실제stack의 ListItr는 hash가 아니라 immutable frame List.equals에서 발생했다. MemoShapes guard relevance 비교 네곳을 같은순서 int indexed comparison으로 바꾸고, carried value stream 두곳을 같은first-slot loop로 공유한다. 새cache나hash변경없음. 수정전27/수정후29회귀PASS, 독립CLEAR SHA363abf7d, 동일facts 모델test포함이다. 잠재회귀는 null/mutable/custom list이지만 production 인자는 List.copyOf로 불변nonnull이며 경계테스트로 값/순서/box차이를검증한다. 전체build/Docker 효과는 미검증. firstUnfixedCostGuard occurrence cache shadow는 LogReg10124/GLM39180요청에서 bounded exactkey repeat0이라 기각했다.

- **C15 추가 단순화:** head snapshot의empty값은 private read-only BitSet을 공유하고 첫true에서만 독립BitSet을생성한다. 모든headlookup/filter/순서유지, empty비율unit LogReg21.06%/GLM17.95%,shadow17+candidate17PASS/불일치0/독립CLEAR. Costguard cycle의 active경로복사/역순검색은 nearest LOOP_HEAD 재귀인자로대체한다. originalmemo/active set/counters/proof호출은그대로이며각cycle에서oldnearest와일치검사shadow26PASS/clean26PASS/독립CLEAR다. 중첩head의FIRST/unknown이면outerhead로넘어가지않는동작을유지한다. 수정 MemoShapes와 신규headbits회귀; C15전체91classes625tests 예정, 실제속도는미검증. 추가로memo/active를합치는 C16설계는별도outside검증중이며 C15에포함하지않는다.

- **C15 최종실측/회귀:** Maven45.800초/91classes625tests91.991초PASS,공식각1회 LogReg3.521011574초(-10.26%)/GLM7.039366906초(+22.29%). 21/23gatePASS이나두시간gateFAIL이다. GLM할당3.494→3.375GB로감소했지만coordinatorCPU28.51→33.20초/mainplanner표본175→209로증가했다. 추가GC .108초는전체+1.283초를설명하지못하며기존JFR에JITCompilation/기존monitor에throttle정보가없어원인을확정하지않았다. C16부터CPU조건은그대로두고read-onlycgroup cpu.stat를관측한다. c15-runtime-variance-analysis.md 참조.
- **C16 통합:** costguard의per-query memo/active를한map의완료값/고유ACTIVEsentinel로합치고정확callFrame별sparsebucket+bindingID로기존PhaseVisit identity를표현한다. 모든publication/nonmemoUNKNOWN/finallycleanup/nearesthead/proof/counter순서유지. shadow전체state불일치0,33shadow/31clean회귀PASS,독립CLEAR. query별PhaseVisit 생성회피unit87.90%/92.19%이며성능실측아니다. Model은정확0.5확률의empty/singleton/power-of-two종료조건과unaryprivacy의불필요전체selected배열확장을제거했다. 확률24before/24after와privacy포함65PASS/독립CLEAR. 최종통합전체test/Docker는대기이며최종버킷samefactsparity를추가확인한다. 잠재위험은frame전환/parentbucketpublication/partialUNKNOWN이며operation별shadow와동일facts전체결과/counter비교로감지한다.

- **C16 통합 회귀 완료:** Maven44.973초 PASS, 92classes627tests91.404초 PASS(wrapper92.668), diffcheck PASS다. 최종 MemoShapes SHA664279be6887 / Model5d8b088c8328을 동결한다. 공식 Docker 측정은 최종 sparse-bucket 동일facts parity 확인 후 각1회 수행한다. CPU/메모리 조건 변경 없이 cpu.stat 대기 정보만 읽는다. 아직 두3초 목표를 달성하지 않았다.

- **C16 최종 parity 및 공식 측정 시작:** frozenC15 baseline separate memo/active와 최종sparse bucket을 같은Facts/Sources에서 비교해 두모델 모두OPTIMAL/exactBigDecimal/fullselected/모든guard4fields·unresolved·4counters/모든ReuseStatistics 일치,2PASS다. 이를확인한뒤12,659files동결본 공식각1회를시작했다. 측정창에는소유compile/probeCPU작업을전부중단한다.
- **C17 head membership 사전증거:** privateHeadSelection의즉시delegate가NO_SELECTION인경우만현재heads의(loop,slot)집합을신규snapshot으로투영한다. 불변inverseindex는중복ordinal을모두보존하며futurehead변경을캐시하지않는다. Unsupporteddelegate는원callback순서를유지한다. 매eligible결과legacy비교shadow와동일facts전체결과/ReuseStats두workload비교포함5PASS,불일치0이다. GLMRules3,715,814callbacks→553,133mapkey방문,LogReg206,885→51,658;Model/Optimizer해당경로0회다. cleanSHA2c6d8e39독립CLEAR지만clean회귀/생산채택/실측은미완료다.

- **C16 측정 결과 및 C17 채택:** C16 공식각1회LogReg3.837223433초(+8.98%)/GLM5.300551246초(-24.70%)로두3초목표미달이다. 상세gate/resource/cgroup분석은측정lane에서마무리한다. C17 privatehead snapshot은clean23회귀14.188초PASS/독립CLEAR확인후채택했다. Livehead내용을재사용하는캐시는추가하지않았다. 컴포넌트fragmentcache는GLM1.50Mkey/1.03Mmiss추가비용과검증공백때문에미채택이다. 선택scratch는별도검증중이며fullsuite/Docker결과대기다.

- **C17 최종 통합/정확성:** freshhead snapshot을finiteprivateprefix전체로확장하되terminal응답은매ordinal기존순서대로한번만호출한다. 현재privateheadmap은외부callback에노출되지않고publicbindingShape재진입은새evaluation을생성하므로suspendedmap불변이다. Shadow7/clean25회귀PASS,실제publicMIDDLE-loop재진입을강화한shadow7/clean5PASS,sourcee6676021독립CLEAR. Model/Optimizer의privateprefix반복검색도제거한다. Scratch1408489c는CAS단독임대와busy시원배열확장fallback,모든4callback finally정리/부분populate실패정리를유지한다.43회귀+같은facts정확결과/전체counters4PASS,실제callbackbody예외후복구포함3PASS,독립CLEAR다. 전체94classes/635tests/build/공식측정진행;불필요한componentcache는추가하지않았다.

- **C16 자원 및 C17 통합검증:** C16 최종21/23gatePASS,시간두개만FAIL;CPU19.82/26.8초,할당1.490/3.212GB,STW.1711/.2732초,HWM434880/811836KiB다. 4CPUcgroup throttle기간비율24.71%/18.72%를관측했으나aggregate throttled시간을planning에서빼거나C15회귀원인으로단정하지않는다. C17 Maven42.377초PASS,94classes635tests83.572초PASS(wrapper84.923),diffcheckPASS,독립sourceCLEAR후동결한다. 상세 C16runtime-report/17plan 및 rawlogs.

- **C17 최종 실측/남은 병목:** 각1회LogReg3.715094059초(-3.18%)/GLM5.772636909초(+8.91%),21/23gatePASS·두시간FAIL이다. Searchcounter는C16과동일하고exactobjective/OPTIMAL/native/출력·audit/frozen12668/JFRloss0유지. 할당은-0.09%/-0.37%로거의같고CPU23.26/26.56초,STW.2016/.2463초다. GLMoptimizer1.6505→2.0720초;throttleperiod비율도18.72→21.72%지만단일cold실행에서인과로확정하지않는다. C18은동등ShapeDependencies가1705→20/2972→43그룹이라는구조증거를바탕으로정확한projection중복기회를계측한다. guarditeration순서까지보존해firstexception/provenance순서를유지한다. 상세candidate17-runtime-report.md/candidate18-plan.md.

- **C18 RepeatProofKey 분포 개선 채택:** rawhash자체충돌보다저비트bucket상관을확인했다. 기존private record를논리적동등성유지cachedhashclass로바꾸고compositehash에finalmix를적용한다. GLM4096keys usedbucket2102→3227,maxbin8→5,treebin5→0. 동일facts두모델OPTIMAL/exactBigDecimal/fullselected/전체ReuseStats/finalLRUorder일치2PASS,clean24회귀PASS,독립CLEAR후선언부만현재C17에적용했다. 중간모든hit/evictiontrace를기록한것은아니며순수hash/equals와불변cache연산의source동등성에근거한다. cachedint객체크기와실제속도효과는전체실측에서검증한다.

- **C18 whole-projection 통합:** ordered guard 순서를 포함한 전체 ShapeDependencies 동등성으로 constructor-local 대표를 공유하고, 불변 context 4개 map identity·정확 frame·kind의 완료된 atom BitSet만 4096 상한으로 재사용한다. livehead와depth는매번반영하며 원admission/한도fallback/atom등록순서·ID를유지한다. frozen dede9cab source 독립CLEAR,shadow두workload exactBigDecimal/fullselected/OPTIMAL/전체solverstats/native구성/atom사전일치 및 clean38회귀/최종focused5회귀PASS. cap1 saturation,실제4096clear,명시적guard순서구별,firstrepresentative/liveheads를검증했다. RepeatProofKey변경과최소diff통합 Shapes SHA5e815517b6d0a513ae68fadd52d6b4317b086ea55280b6aad13fd0860d8ac6c1; 전체build/95classes640tests 및 공식실측은대기다. 최악BitSet payload약32MiB상한외map/key비용과실제속도를검증하며중복44~46%구조량을속도개선율로주장하지않는다.

- **C18 통합검증:** Maven45.891초PASS,95classes640tests78.702초PASS(wrapper79.896),diffcheckPASS다. 원model/resolver와독립hashmix를유지한 Shapes5e815517...을동결후공식각1회측정한다. C17기반rawmapidentity probe는66%hit·GLM3.75Mentry 재사용기회를보였지만C18중복제거뒤잔여량과신규배열비용을다시검증해야하므로생산에반영하지않았다.

- **C18 최종실측:** 공식각1회LogReg3.153343246초(-15.12%)/GLM5.618328827초(-2.67%),21/23gatePASS·두3초gateFAIL이다. exactobjective/OPTIMAL/native/runtime출력·privacy/audit/12675동결/JFRloss0/Searchcounter유지. 할당1.436/3.108GB,CPU23.67/30.30초,STW.1878/.2855초,throttle28.07%/16.76%. GLMRules/Model은오히려증가해전단계개선을주장하지않는다. 상세candidate18-runtime-report.md.

- **C19 costguard scratch 채택:** 매query의rootframe Map을CAS임대Object[]로재사용하고foreignframe은기존sparse동작을유지한다. VACANT sentinel로unremembered재방문touch중복을막고finally에서참조제거후임대를반납한다. Busy/reentrant query는독립sparse상태,boxedbindingID는facts범위에서한번만공유한다. Source4bedbd37 독립CLEAR,clean51회귀PASS/trueC18원구현samefacts2PASS(OPTIMAL/exactBigDecimal/fullselected/전체ReuseStats/guard결과·4counters). 실제populatedproofcallback재진입+예외후복구포함4lifecycle회귀를추가했다. 구조상L167030/G610186rootnode생성을피하지만전체실측효과는미확인이다. Rawentry후보는비교baseline이uncachedreplay였던공백을발견해trueoriginal검증을추가하며아직미통합이다.

- **C19 raw entry 채택/검증공백 보완:** rawMap clean00ab36bb는namespace별계측을실험에만남기고집계counter만유지한다. 불변map의동일객체에서원key/value참조·순서를4096상한IdentityHashMap의Object[]로재사용하며현재filter/provenance/atom등록은계속실행한다. Guard/iteration/cardinality만채택하고loopphase는cachechurn순손실로기각했다. 최초OFF가uncachedarrayreplay였던문제를수정해C18원entrySet소비루프와비교했으며전체ReuseStatistics.equals(guardrelevance/guardatom포함),atomIDs/native/OPTIMAL/exactcost/fullselected2PASS11.733초다. 최종clean4focused+38회귀PASS/독립CLEAR후scratch와최소diff통합했다. 최종97classes648tests/빌드/공식실측은대기다. 과거6PASS의의미와신규truebaselinegate를분리보고한다.

- **C19 통합검증/기각실험:** Maven44.655초PASS,97classes648tests91.186초PASS(wrapper92.365),diffcheckPASS. Shapes2dd91b71853327d7a6238934cd1520563d186b82e5278f9e4593b8064539208e동결후공식각1회평가한다. Availability닫힌DAG TRUE certificate는원래순회shadow에서불일치0이나GLM9970/LogReg18descendant방문만절감해추가precompute/shortcut을기각했다. JFR가중128.9MB표본을전체순회량으로오인하지않는다.

- **C19 실측후채택철회:** 공식각1회LogReg3.450021828초(+9.41%)/GLM6.774694799초(+20.58%),21/23gatePASS·시간두개FAIL. exactobjective/native/search/runtime/privacy/audit/12686동결/JFRloss0유지. 할당1.350/3.079GB(-6.01%/-0.93%)이나CPU24.25/33.91초,STW.1733/.3079초로실제시간개선이확인되지않았다. 한 번의관측으로개별변경인과를확정하지않지만성능목표와단순성기준에서C19 scratch+rawentry를함께생산채택철회하고C18 Shapes5e815517바이트로복원했다. 해당두private구현회귀도C19동결본/실험경로에보존하고productiontest에서제거했다. 기존C18선택·비용의미는그대로다. 이후C20은C18+program-invariant legalitytable을기준으로검증하며C19의절감객체수만으로성공을주장하지않는다.

- **C20 legality 정적 분석 반영:** C18 기준 Facts a1749a6a / Resolver 9dc13f49는 loop/cardinality/phase reachability와 guard의 enclosing-loop만 한 번 계산한다. 문맥별 검증 순서/원인/방문/UNKNOWN·privacy·binding은 유지하며 invalid/null은 기존 predicate로 예외를 보존한다. Clean5focused+40regressionPASS, 별도 원래 predicate ON/OFF 모델동등성 및 독립reviewCLEAR다. 실험toggle/counter는 생산에서 제거했다. 전체통합/build/Docker는 대기다. 잠재회귀는 phase enum 확장이나 Facts 구성 불변식 변화이며 exhaustive predicate비교로 감지한다.

- **withPhase map 변환 캐시 기각:** context 전체identity보다 넓은 3개 inputmapidentity+정확loop/frame/phase key를 C18 원연산 shadow로 계측했다. L1340/18102(7.40%), G9066/70576(12.85%) hit이며 원순서/guard전필드 불일치0, 복사절감은7052/96715entries다. 추가 key/lookup/보관비용 대비 제한적인 기회라 생산에 추가하지 않는다. 구조검사의 OPTIMAL과 시간을 Docker 성능으로 대체하지 않는다. 상세 withphase-map-transform-result.md.

- **C20 activation-prefix 재사용 통합:** RuleBuilder의 고정 NO_SELECTION/empty-map/depth4096 Consumer 경로에서만 region·정확frame·orderedPhaseLoops·activationbinding 전체identity의 COMPLETE prefix를 재사용한다. 모든 occurrence별 shape/native/Oracle/coverage/privacy/diagnostic callback은 원순서대로 실행한다. Cache는4096entries/4096contexts/65536전체map·framecomponents로 제한하고 overflow는capture만버리며 원stream을끝까지진행한다. callback예외/UNRESOLVED는게시하지않고 재진입후상한도다시검사한다. 원predicate shadow8PASS, clean4lifecycle+17기존21PASS,독립CLEAR. 실제앞단resolver회피 L2389/G4829는구조량이며시간절감주장아니다. 전체테스트와Docker대기.

- **C20 definition weight 임시배열 제거:** 매primitive별 sizes길이 -1배열을 Model 소유private read-only배열로한번만만든다. context 두호출과headbinding동작은그대로며선택scratch에는대여하지않는다. Model SHA0bc99c2e sourceCLEAR,기존movement전선택동등성과전체suite로검증한다. 기존JFR가중할당 L13.61/G19.87MB관측이지시간효과가아니다.

- **C20 전체suite의 HotSpot 예외메시지 비교 실패 수정:** 최초97classes649tests중1FAIL은 MemoLegalityTableTest의 malformedloop 예외메시지 비교였다. 양쪽동일ArrayIndexOutOfBoundsException이며 원래와신규helper가같은 loops.get을호출한다. 별도 probe에서 첫 message/stack5 뒤199999회 nullmessage/stack0을재현해JVMfastthrow 상세생략을확인했다. Test만해당implicit AIOOBE/NPE의 nullmessage+emptytrace에한정해메시지비교를생략하고예외class는항상엄격비교한다. Production과공식JVM조건은변경하지않는다. 최초실패로그는candidate20-unit-initial.log에보존;수정후전체재검증대기. 잠재회귀는실제예외type변화이며항상검사한다.

- **C20 통합검증 완료/동결:** Maven47.415초 및테스트수정재compile28.115초PASS,97classes649tests86.333초PASS(wrapper87.562),diffcheckPASS. 예외메시지 최초실패와독립fastthrow재현을보존했다. 철회된C19전용9class는동결C19와바이트동일확인후target에서만제거했다. C18Shapes+legality/prefix/private정의선택배열을동결하고소유hostCPU작업중단후공식Docker각1회측정한다. 두3초목표는아직미확인이다.

- **C20 최종실측:** 공식각1회 LogReg3.295243134초(C18대비+4.50%)/GLM5.219770475초(-7.09%),21/23gatePASS·시간둘FAIL. exactobjective/OPTIMAL/전체search/native/runtimeCSV/privacy/audit/12683동결/JFRloss0유지,할당-5.48%/-9.47%다. 단계전체개선이나개별변경인과를주장하지않는다. C20을후속기준으로유지하고 C21은기존canonicalkey가충분한native연산의중복Oracle평가를제거하는최소범위로검증한다. 상세candidate20-runtime-report.md.

- **C21 native Oracle 중복 제거 범위 확장:** 기존fullcanonicalRequestKey가모든결정입력을담는것을source로증명한 TRANS/NaryPLUS·MULT/TernaryIFELSE·PLUS_MULT·MINUS_MULT만immutableResultcache대상에추가했다. RuleBuilderkey복제/shape-onlydedup/문맥검사생략/후보축소는없다. OFF/ON9focused+13기존회귀PASS,같은Facts의전체RuleMemo/diagnostics/exactBigDecimal/fullselected/OPTIMAL/solverstats유지,실제descriptor와별도viewfullkey일치;source5235077b독립CLEAR. UnitOracleL928→141,G2520→570은구조량이고공식시간효과미확인. RESHAPE/MAP/CTABLE/append등은기존보수적경로유지한다. Clean생산테스트통합/전체build/Docker는대기. 잠재회귀는향후Oracleattribute추가이며fullkey분리와독립원결과비교로감지한다.

- **C21 unary TRANS 비용벡터 재사용 통합:** 정확히한입력TRANS만기존per-occurrence/fullShape(NNZ·knowledge포함)/blocksize/weightbits 캐시대상에추가했다. 원shape·예외·primitive순서와성공뒤게시/clone반환을유지한다. C20기준OFF/ON에서nativeCosts모든rawbits와추가primitive20,615/48,333값의rawbits+BigDecimal,전체OPTIMAL/cost/selected/solverstats일치;vectorbuildL152/G590절감이다. Cleanfdec5488·5boundarytest ecc18954독립CLEAR,기존3포함8PASS. 실제compressed-outputflag는Hop.clone이보존하지않아지원fixture로해당분기를검증할수없으며requiresCompression marker만검증했다. UNKNOWN_SIZE는constructor실패를비교하고미게시검사는key생성후INVALID_FREQUENCY+source순서로검증했다. 새캐시나근사화없고전체통합/Docker는대기다.

- **C21 전체suite의 구형 fallback witness 수정:** 99classes662tests 최초실행에서661PASS,1FAIL은 MemoNativeRequestTest가 TRANS를 여전히 uncacheable로 가정한 테스트였다. 새 명세에서 TRANS는 full-key 재사용 대상이므로 해당 witness만 기존 excluded RESHAPE로 바꾸고 nullkey/두독립평가/result동등성/cache통계 assertions는 유지한다. 다른 graph-identity TRANS 테스트는 그대로다. 최초로그와결과는 candidate21-unit-initial.*에보존했다. Production변경 없이 test 재compile 및 전체suite를 재검증한다.

- **C21 통합검증/동결:** Maven41.207초 및 test-witness수정재compile29.069초PASS,99classes662tests83.779초PASS(wrapper85.116),diffcheckPASS. 두생산소스와3개fallbackwitness/새경계테스트독립reviewCLEAR다. 최초1FAIL로그는별도보존했으며 production source5235077b/fdec5488은변경없다. 소유CPU작업중단후 동일공식Docker각1회측정한다. Oracle요청·generation/vector통계는재사용효과로달라질수있으며 native universe/정확비용/최적해/privacy/audit gate와구분한다.

- **C21 실측 중간 결과 및 다음 분석:** 공식각1회LogReg3.680768845초/GLM5.801478409초로두3초목표미달이다. Oracle요청1057→199/2250→450,준비8880/11699동일,재사용8681/11249이며 actualcachehit0/miss199·450이다. LogRegRules1.298759초로줄었지만Model·Optimizer증가로total악화,GLMRules3.029352초다. 정확비용/OPTIMAL/native universe/search동일이며최종평가·JFR후처리대기다. 다음은기존occurrence-transfer key의중복List생성·복사를개인소유배열로단순화하는outsideprototype만검증한다. 캐시신설이나입력생략없고동적scalar분류는유지한다. 계획candidate22-plan.md; 실제개선은미확인이다.

- **C21 공식 후처리/평가 완료 — 상태: 성능 목표 미달, 정확성 유지:** `run_LAN_docker.sh`로 동결 12,691파일을 LogReg/GLM 각각 정확히 1회 실행했고 두 run·monitor·container가 정상 종료했다. C20 대비 LogReg는 3.295243134→3.680768845초(+11.70%), GLM은 5.219770475→5.801478409초(+11.14%)로 회귀했다. 원 evaluator는 19/23 PASS이며 실패는 두 3초 gate와 두 `MemoConstructionEqualReference` gate다. evaluator를 수정하거나 gate를 면제하지 않았다. 두 construction 차이는 `nativeRequests`와 `nativeGeneration`에만 있고 `costProjection`은 C20과 완전 동일하다. LogReg prepared 8880 동일·Oracle 1057→199·reuse 7823→8681, GLM prepared 11699 동일·Oracle 2250→450·reuse 9449→11249다. exact objective/OPTIMAL/모든 search counter/nativeRules·fedOwners·supplies/출력·privacy·audit는 유지됐다. 따라서 별도 telemetry-aware 분류에서는 의미 gate 21/23, 시간 gate 둘만 실패하지만 이는 원 evaluator 결과를 대체하지 않는다. JFR loss 0, 할당은 L -2.59%/G -1.85%지만 CPU는 18.55→21.18/24.67→25.41초다. GLM Rules가 94 CPU sample·846.8MB로 최대이며 `resolvedBindingShape` 11, `occurrenceShape` 10, `OccurrenceTransferKey.equals` 6 sample; disjoint allocation은 resolver282.1MB/phase-shape200.1MB/costguard127.0MB/indexed55.6MB다. LogReg은 Optimization1.3655초·449.3MB가 최대다. **원칙/의사결정:** 정확한 Oracle full-input identity와 모든 context legality/UNKNOWN/privacy 처리는 유지했고, 실측 회귀 때문에 요청 수 감소를 성능 성공으로 간주하지 않는다. **검증/증거:** `candidate21-runtime-report.md`, `candidate21-evaluation.json`, `candidate21-comparison.json`, `candidate21-verification.json`, `candidate21-residual-jfr.json`. **잔여 이슈:** 두 workload 모두 3초 초과이며 C22는 occurrence-transfer 배열 단순화의 동일성·실측을 별도 검증한다. **잠재 회귀 위험/감지:** 향후 Oracle 결과 필드 추가 시 canonical key 누락 가능성은 full-key descriptor/view 비교와 전체 RuleMemo/diagnostic/정확비용 회귀로 감지한다.

- **C22 기존순서 재사용/diagnostic 중간list 제거:** reconstructSupplies의현재use ordinal을private compatible로전달해매번uses.indexOf하는O(n²)검색을제거했다. Facts 고유occurrence당Use1개인불변식과모든L562/G884index의원firstmatch동일성을확인했다. Trueold/new에서rawnative/additionalprimitive20,615·48,333값/OPTIMAL/exactcost/fullselected/stats/supplies일치다. RuleBuilder는immutable diagnostic을같은predicate로직접순회해context별진단순서·중복·complete/UNKNOWN을유지한다. 두clean source52기존회귀PASS/독립sourceCLEAR;새캐시없다. 전체통합/Docker는대기이며counter절감을시간달성으로표시하지않는다.

- **C22 transfer key 단순화 통합:** 새배열·캐시대신기존request-local ArrayList둘의private소유권을key로넘겨중복방어복사/wrapper만제거하고정확한recordhash를한번계산한다. 최초array안은livearity가정을추가해철회했다. 원동적순회·scalar해석·예외/cycle/continuation·LRU순서/cap은그대로다. TrueC21recordcacheON vs새keycacheON3PASS(allrawprimitive20,615/48,333·exactcost·supplies·LRU/counter·OPTIMAL동등),clean1신규+기존16PASS/독립CLEAR. Shapes9dec8a63/test3d24f214. 잠재위험은미래key이후builder변경이며private소유권주석/동등성회귀로감지한다. 전체통합/Docker대기.

- **C22 wide native cost vector 검토 보류/보완:** >2입력exact5연산의초기캐시확대는raw primitive동등성과175/422build절감에도independentreview에서[A,B(A),A]중간input메모리가첫Ashape에의존하는경계를발견했다. effectiveInputShapes key가마지막A만담을수있어일반증명이미완료다. 생산미반영상태로새5family에directinputidentity중복이없는경우만재사용하는최소eligibility를검증한다. 중복graph는원래정확비용계산경로를유지하며native후보/feasibility는줄이지않는다.

- **C22 wide native cost vector 증명 보완/통합:** exact NaryPLUS·MULT/TernaryIFELSE·PLUS_MULT·MINUS_MULT 중directinputHopidentity가모두다른경우만기존fullorderedShape/weightbitscache를사용한다. 중복[A,A,A]와[A,B(A),A]는원uncached path로유지하고,eligible[A,B(A),C]의중간UNKNOWN NNZ메모리까지rawbit동등성을검증했다. 원≤2family/TRANS와key·primitive·예외·게시순서는그대로다. Clean24testsPASS/독립CLEAR,동일C21OFF/ON fullrawnative/additional20,615·48,333값/OPTIMAL/exactcost/선택/stats/supplies일치. UnitbuildL175/G422절감은distinctgate후에도동일하며시간효과미확인이다. 초안proofgap자료는보존했다. Source9c59a8a3의nativeCostVectorhunk만useOrdinal Model위에병합했다. 전체101classes671tests와공식Docker대기.

- **C22 전체검증/동결:** Maven45.878초PASS,101classes671tests82.697초PASS(wrapper83.866),diffcheckPASS. Model통합fa2fd3f1/Shapes9dec8a63/RuleBuilder1e8a3a05;독립sourceCLEAR와trueold/new전체primitive·OPTIMAL·supplies동등성자료를보존한다. 초기widealiasproofgap은distinctidentitycacheeligibility로닫았고실행후보는그대로다. 동일Docker각1회측정전모든소유CPU작업을중단한다. 누적성능목표미달상태는유지한다.

- **C22 공식 중간 결과:** LogReg2.746212609초로3초gatePASS,GLM5.440288582초로FAIL이다. 두OPTIMAL/exactobjective/native구성/runtime유지,GLM일부check/supportcounter는C21과달라최종후처리에서명시한다. 각1회측정조건을유지하며재실행하지않는다. 전체목표는미달이다.

- **C23 exact decimal 사전 설계:** 원newBigDecimal을항상실행한외부계측에서 L29860회/781raw값,G76285회/1506raw값의정확변환반복97.38%/98.03%를확인했다. perOptimizer수명/한도내에서순수binary64→BigDecimal만재사용하고원evaluator·tupleclone·유한성검증·합산순서·예외·최적해는유지하는방안을검증한다. 전역cache/반올림/valueOf(double)없다. 생성자검증순서와0capacity/포화/독립instance 경계를검사한다. 계획candidate23-plan.md;생산미반영,성능효과미확인.

- **C22 공식 후처리/평가 완료 — 상태: LogReg 목표 달성, GLM 목표 미달:** 동결12,698파일을 동일 pinned image/4CPU/8GiB/network-none 조건에서 각1회 실행했다. LogReg2.746212609초로 최초3초gatePASS, GLM5.440288582초로FAIL이다. C21대비 -25.39%/-6.23%, C20대비 -16.66%/+4.22%이며 단일혼합변경실행으로개별인과는주장하지않는다. 원 evaluator는20/23PASS이고 실패는 GLM시간 및 원reference대비MemoConstruction2개다. 후자둘은C21에서이미발생한native request/generation telemetry차이이며 C22는C21과nativeRequests/nativeGeneration/costProjection이전부동일하다. evaluator를수정/면제하지않았고 별도telemetry분류만22/23·GLM시간1개FAIL로기록한다. 두case OPTIMAL/exactobjective/nativeRules·fedOwners·supplies/costProjection primitive·outputCSV/privacy/audit/freeze/JFRloss0 유지다. LogRegsearch는C21과동일. GLM은nodes1954/costeval5139/costcache11876/bound·conditional·wide동일이나 checks97479→97344,cacheHits77037→76999,supportQueries85676→85590,witness49979→49805,binaryHits37945→37771,worklist14370→14353으로 traversal이달라졌다. 정확결과는유지됐지만이차이를숨기지않으며JavaMap순서나특정변경을원인으로단정하지않는다. Nativevectorbuild는L2024→1666/G3834→3484,할당은C21대비-2.52%/-2.96%; GLM CPU25.41→31.25초/HWM870464KiB/STW.321113초로증가했다. Residual GLM은Rules2.3388초·61sample·807.3MB,Optimizer1.6629초·49sample·732.3MB이고 Rules allocation은resolver371.5/phase-shape236.0/costguard69.3/indexed51.7MB다. GLM `OccurrenceTransferKey.equals`는C21 6sample→C22 0sample이나표본상관일뿐인과측정이아니다. **원칙/의사결정:** candidate축소/근사화없이동일exactprimitive/최적해/실행가능성보존,raw evaluator불변. **검증/증거:** `candidate22-runtime-report.md`, `candidate22-evaluation.json`, `candidate22-comparison.json`, `candidate22-verification.json`, `candidate22-residual-jfr.json`. **잔여 이슈:** GLM을3초이하로줄여야하며C23 exact-decimal 재사용을outsideproduction에서먼저검증한다. **잠재 회귀 위험/감지:** GLMsearch traversal차이는후속후보에서exactobjective/fulloutput/nativeuniverse/costProjection/budget와counter를각각비교해감지한다.

- **C23 독립 경계검증/증거 보강:** 외부 clean Optimizer13727043는 독립 source review CLEAR와 기존 optimizer83tests PASS, root 신규7경계tests PASS다. signed zero/subnormal/extreme/negative exact값·scale·precision, 매호출 evaluator/clone, 후속예외/nonfinite, cap0/포화/instance분리, singleton expansion, 생성자검증순서/인증하한실패를 검사했다. 최초 shadow OFF는 캐시만 끄고 새adapter를 공유해 truelegacy라는 표현이 과도했으므로 원 C22 factory/staticconverter wiring 자체를 복원한 별도비교로 보강한다. 원자료는 보존하며 생산통합/공식속도효과는 아직 미확인이다.

- **C23 정확 변환 재사용 통합:** literal C22 term::evaluateExact/static native converter를 유지한 OFF와 새 ON의 두 workload 전체 OPTIMAL/exactBigDecimal/fullselected/stats/supplies/diagnostics/선택비용 동등성을 재검증했다(1test PASS,03로그; 이전자료보존). L23221/G55491회 exact decimal build를 재사용하고 retained값781/1506이다. Clean13727043 및 독립7경계tests를 생산통합했다. 원 evaluator 호출/tupleclone/합산순서/유한성·인증하한검증은 유지하며 BigDecimal반올림·전역상태·새의존성없다. 전체suite/동결/Docker는 대기중이며 실제시간 개선을 주장하지 않는다.

- **C23 전체검증/동결:** Maven45.146초PASS,102classes678tests77.493초PASS(wrapper78.921),diffcheckPASS. 독립 source review CLEAR 및 literal C22 OFF/ON 전체workload동등성/7경계tests증거를 보존한다. 정확변환캐시만통합했고 staticzero shortcut은추가하지않았다. 동결후 소유 CPU작업을중단하고 같은공식Docker조건각1회측정한다. 현재3초목표는GLM미달이며구조적변환회피량을성능달성으로해석하지않는다.

- **C23 공식 중간결과 / C24 의존성 재사용 설계:** C23각1회 LogReg3.658750194초(회귀),GLM4.931508331초(개선)로둘다3초미달이다. exactobjective/OPTIMAL/native구성/runtime유지며 GLM일부searchcounter는C20/C21값으로관측되어C22차이의원인을특정수정으로단정하지않는다. C24는항상원탐색을실행한 shadow에서GLM220535중125540회(56.93%)동일ordered loop-head 의존성을확인했다. 선정값을제외해도원탐색이읽는full입력은binding/frame/guards/iterationPhases뿐임을source독립검토CLEAR했다. request-local4096clear-on-success cache만외부검증하며실제head평가/legality/UNKNOWN은매context유지한다. 계획candidate24-plan.md;생산미반영/실제속도효과미확인.

- **C23 공식 후처리/평가 완료 — 상태: 두 workload 모두 성능 목표 미달, 정확성 유지:** 동결12,700파일을 동일 pinned image/4CPU/8GiB/network-none 조건에서 LogReg/GLM 각각 정확히1회 실행했고 run·foreground monitor·container가 모두 정상 종료했다. LogReg3.658750194초로 C22대비+33.23%, C21대비-0.60%, C20대비+11.03%; GLM4.931508331초로 C22대비-9.35%, C21대비-15.00%, C20대비-5.52%이며 둘 다3초gate FAIL이다. 원 evaluator는19/23PASS이고 실패는 두시간gate와 원reference대비MemoConstruction2개다. evaluator를수정하거나면제하지않았다. C23 construction은 C22와 완전히 동일하고 reference차이는 nativeRequests/nativeGeneration telemetry에 한정되며 costProjection은 동일하다. 별도 telemetry-aware 분류는21/23이며 원결과를대체하지않는다. 두case OPTIMAL/exactobjective/nativeRules·fedOwners·supplies/costProjection primitive/full outputCSV/privacy/audit/frozen input/JFR loss0를 유지했다. LogReg search counter는 C20/C21/C22와 동일하다. GLM은 C20/C21값으로 돌아와 C22와 checks135/cache38/support86/witness174/binary174/worklist17 차이가 있으나 exact 결과와 전체출력은 동일하며 단일실행에서 원인을 특정하지않는다. Exact-decimal reuse와 일치하게 optimizer 할당은 C22대비 L439,983,000→341,508,384bytes(-22.38%), G732,282,192→665,251,080bytes(-9.15%)이고 전체할당은-4.81%/-1.18%지만, optimizer시간은 L+12.51%/G-13.15%라 할당감소를 시간인과로주장하지않는다. CPU23.42/25.55초,STW.193826095/.286660769초,HWM429,976/803,780KiB,cgroup throttle기간24.26%/19.02%다. Residual GLM은 Rules56 CPU sample·920,333,088bytes,Optimizer28sample·665,251,080bytes로 Rules가 우선이며 resolver383,375,192/indexed191,782,008/phase-shape199,440,104/costguard63,927,224bytes다. **검증/증거:** `candidate23-runtime-report.md`, `candidate23-evaluation.json`, `candidate23-comparison.json`, `candidate23-verification.json`, `candidate23-residual-jfr.json`, `candidate23-artifact-sha256.txt`. **잔여 이슈:** 두 workload 모두3초를넘었고 C24의 program-invariant dependency 재사용을 strict parity 뒤 평가한다. **잠재위험/감지:** exact 변환 cache 경계와 GLM traversal 변동은7경계tests, literal C22 OFF/ON parity, exact objective/full output/native universe/costProjection 및 search counter 개별비교로 계속 감지한다.

- **C24 공식 후처리/평가 완료 — 상태: 구조적 할당 감소, 두 workload 성능 목표 미달:** static direct-definition empty-head proof와 Shapes의bounded stream/guard-table cleanup을동결12,704파일에서동일 pinned image/4CPU/8GiB/network-none 조건으로각1회측정했다. LogReg3.115664233초로C23대비-14.84%지만3초를0.115664233초초과했고,GLM5.225493361초로C23대비+5.96%이며두시간gate모두FAIL이다. 원evaluator는19/23PASS;두시간과원reference대비MemoConstruction2개가실패하며수정/면제하지않았다. C24 construction은C23의nativeRequests/nativeGeneration/costProjection과완전동일해별도telemetry-aware분류는21/23이다. 두case exactobjective/OPTIMAL/nativeRules·fedOwners·supplies/byte동일CSV/privacy/audit/frozen/JFRloss0를유지했다. LogRegsearch는C23/C22동일,GLMsearch는C22값으로C23과6필드차이가나지만exact결과는같고단일실행인과를주장하지않는다. C23 GLM profile의LoopDependencyState59,694,096B와collectLoopHeads24,103,376B표본은C24 residual에서사라졌고Rules할당920,333,088→710,616,472B(-22.79%),전체할당-8.42%다. 그럼에도GLM Optimization1.444314228→1.941330320초(+34.41%)로total이회귀해구조절감을전체시간성공으로해석하지않는다. LogReg전체할당-5.05%. CPU23.24/30.11초,STW.182423099/.271049705초,HWM471,268/832,284KiB,throttle25.14%/20.38%. Residual GLM Rules57sample·710,616,472B,Optimizer41sample·639,953,248B이며optimizer시간이현재우선병목이다. **검증/증거:** `candidate24-runtime-report.md`, `candidate24-evaluation.json`, `candidate24-comparison.json`, `candidate24-verification.json`, `candidate24-residual-jfr.json`, `candidate24-artifact-sha256.txt`. **잔여이슈:** 두3초목표미달, 특히GLM optimizer의plan-dependent반복을exact semantics로줄여야한다. **잠재위험/감지:** staticproof범위·guardalias와traversal변동은신규5경계/기존49/full683tests, strictcombinedrawprimitive/orderedRuleMemo/OPTIMAL/fullselected/stats/supplies parity와공식exact gate로감지한다.

- **C25 공식 후처리/평가 완료 — 상태: snapshot할당감소, 두 workload시간회귀/목표미달:** ordered immutableSnapshot self-reuse를동결12,706파일에서동일공식조건각1회측정했다. LogReg3.525030810초(C24대비+13.14%),GLM6.133669572초(+17.38%)로모두3초FAIL이며재실행하지않았다. 원evaluator19/23PASS;두시간과원reference대비MemoConstruction2개FAIL을수정/면제하지않았다. C25 nativeRequests/nativeGeneration/costProjection은C24와완전동일해별도telemetry-aware분류21/23이다. exactobjective/OPTIMAL/nativeuniverse/byte동일CSV/privacy/audit/frozen/JFRloss0와두workload모든searchcounter가C24대비동일하다. 전용snapshot reuse counter는생산로그에없다. 기존실제telemetry는L directroots Shapes49,441/49,441fast·privacy12,461/5,284,context28,415/28,385,guardrelevance175,308/175,241; G226,760/226,707·37,485/19,923·67,403/67,242·1,811,067/1,810,837이다. JFR immutableSnapshot표본은L Rules+Model35,546,208→23,911,496B(-32.73%),G Rules+Model+Opt128,942,520→37,727,480B(-70.74%); 전체할당도-1.53%/-7.72%다. 그러나단일실행total이모두회귀해할당절감과시간인과를주장하지않는다. 단계는L Rules-12.12%지만Model+22.41%/Opt+41.53%;G Rules+20.03%/Model+67.55%/Opt-13.11%로혼재한다. CPU26.33/30.06초,STW.186321523/.257943853초,HWM427,152/604,428KiB,throttle30.29%/17.41%. Residual GLM Rules92sample·625,289,840B,Model53·507,986,176B,Optimizer40·644,341,368B다. **검증/증거:** `candidate25-runtime-report.md`, `candidate25-evaluation.json`, `candidate25-comparison.json`, `candidate25-verification.json`, `candidate25-residual-jfr.json`, `candidate25-artifact-sha256.txt`. **잔여이슈:** 구조절감에도공식시간이악화되어채택여부는단순성/누적증거와후속후보기준으로별도결정하며,두3초목표는미달이다. **잠재위험/감지:** inputidentityadmission/order/value동등성은독립10경계/full688tests/strictOFF-ON전체primitive·orderedRuleMemo·OPTIMAL/fullselected/stats/supplies parity와공식exact gates로감지한다.

- **C25 채택철회/C26 공식 후처리 완료 — 상태: C24대비두 workload소폭개선, 목표미달:** C25는두공식시간회귀로생산채택철회하고증거만보존했다. C26은C24 Resolver를복원한기준에movement key constant identity와primitive scope boxed-stream 제거만통합했다. 동결12,707파일공식각1회에서LogReg3.028378372초(C24대비-2.80%)로3초를28.378ms초과,GLM5.072434165초(-2.93%)로FAIL이다. 원evaluator19/23PASS;두시간+원reference MemoConstruction2개FAIL을그대로유지하고,construction이C24와완전동일한별도분류는21/23이다. exactobjective/OPTIMAL/nativeuniverse/byte동일CSV/privacy/audit/frozen/JFRloss0유지. LogRegsearch는C23/C24/C25동일;GLM은C23값으로C24/C25와6필드차이나exact결과는동일하다. MovementReuse모든로그counter는C24와동일. Shape진단은L phase-shape -3requests/-1hit,guardrelevance -2/-2,occurrence -2/-2;G guardrelevance +8/+8,guardatom +4/+4의작은traversal차이를숨기지않는다. 전체할당은C24대비-0.84%/-0.56%,CPU21.84/23.95초,STW.179046864/.253926433초,HWM506,128/694,264KiB,throttle33.52%/22.28%. C24 uniqueScope표본L12,575,800/G39,819,296B는C26에서미관측이나표본상인과는주장하지않는다. Residual GLM은Rules53sample·637,848,816B,Model37·533,005,000B,Opt30·623,284,192B다. 다음단순후보인GLM Opt LoopDecision.equals는15,719,816→11,527,864B(2sample유지),phaseGuard는29,343,592→12,575,880B(Opt)지만Model13,623,816B/1sample이별도관측된다. 전체phase합은47,425,128/26,199,696B로여전히iteration allocation표적이다. **검증/증거:** `candidate26-runtime-report.md`, `candidate26-evaluation.json`, `candidate26-comparison.json`, `candidate26-verification.json`, `candidate26-residual-jfr.json`, `candidate26-artifact-sha256.txt`. **잔여이슈:** LogReg28.378ms및GLM2.072s초과로joint목표미달. **잠재위험/감지:** constantidentity/scopevalidation은독립review,combinedliteralC24OFF/ON전체primitive·orderedmovement·OPTIMAL/allstats parity,104classes686tests와공식exact gate로감지한다.

- **C24 contextcache안 철회/정적증명 재사용으로 단순화:** 생산통합전 existing directDefinitions표가 DEFINITIONleaf/nochildren/noSymbolicReturn을보장함을발견했다. traverseKernels=false인head의존성탐색은이경우항상empty다. 원탐색을항상실행한추가계측에서L Rules16870/16870,G220528/220532(99.9982%)가이증명에해당하고nonempty반례0이다. 새cache/key/cap/constructor를모두제거하고contextnull/root검증뒤불변empty결과만재사용하며나머지원탐색유지로외부후보를단순화한다. contains(null)/mutation/spliterator도기존unmodifiableLinkedHashSet행동을유지한다. 앞선cacheprototype증거는보존하되최종변경의검증으로혼동하지않는다. 독립sourceproofCLEAR;새경계/전체primitive parity대기.

- **C24 최종 정적경로/Shapes 통합:** Resolver6f01c1bc의기존directDefinitions증명을사용한empty결과단축과Shapesaa5654dd의orderedloop/기존guardEnclosingLoop표재사용만통합했다. 새cache/key/constructor없다. 독립5경계tests/기존49testsPASS,두원본OFF→두후보ON combined전체native및추가primitive20615/48333raw값·exactdecimal·orderedRuleMemo·OPTIMAL/fullselected/stats/supplies/diagnostics/LRU동등성1PASS(14.168초)다. 반환emptySet의null조회/변경예외/spliterator도보존한다. 전체build/suite/동결/Docker대기이며아직3초목표미달이다.

- **C24 전체검증/동결:** Maven43.101초PASS,103classes683tests79.468초PASS(wrapper80.772),diffcheckPASS. 최종Resolver6f01c1bc/Shapesaa5654dd독립sourceCLEAR와strict원본대비전체primitive/해동등성,신규5경계증거를보존한다. 철회한contextcache코드는생산에포함하지않았다. 동결후소유CPU작업을중단하고동일공식Docker각1회측정한다. 시간목표달성은아직미확인이다.

- **C24 공식 중간결과/후속 계측:** LogReg3.115664233초,GLM5.225493361초로둘다3초미달이다. GLM Rules2.045054/Model1.036191은줄었지만Optimization1.941330으로늘어C23대비total회귀다. 정확비용/OPTIMAL/native구성/runtime유지,GLMsearchcounter는C22값으로관측돼특정수정인과는단정하지않는다. 빈frame LoopDecision사전계산은471236호출중해당0으로기각했다. 다음은기존immutableSnapshot의정확ordered동일변환반복과실제phaseShapeCache hashbucket충돌을원경로유지계측한다. 설계candidate25-analysis-plan.md;생산추가변경없다.

- **C25 ordered immutable snapshot 설계:** 원T변환을항상실행한계측에서L36808/36808,G396528/401967(98.6469%)miss가정확한ordered key/value reference동일이었다. G5439건은순서가달라무조건원맵반환은금지한다. 기존miss에서원T성공후copyOf(identity)불변성검사및전체ordered key==/value==일치시에만input을반환해T(T(G))새identity사슬을없애는외부후보를검증한다. 새cache/JDKbucket가정/Map.equals생략없고livehead평가유지다. 독립설계CLEAR/실제시간WATCH;plan candidate25-plan.md. phaseShapeCache maxbucket3/8자료만으로hash변경은추가하지않는다.

- **C25 정확 ordered snapshot 재사용 통합 — 상태: 정확성 사전검증 완료/성능 미검증:** Resolver의 원 T 변환을 먼저 수행하고 입력이 JDK-recognized immutable이며 모든 ordered key/value 참조가 같은 경우에만 입력 map을 유지한다. 변경순서/비허용 입력은 원 T를 반환하며 새 cache/key/상태는 없다. 최종 source0b9289cb는 검토된 fbb660e1에 proof comment만 추가했다. strict literal C24 OFF/ON에서 두 workload ordered environment841/1647, 전체 native rawbits, 추가primitive20615/48333 raw+exact, RuleMemo/OPTIMAL/exactobjective/fullselected/stats/supplies/diagnostics 동등성1PASS, 기존49회귀PASS, 독립경계+기존snapshot10PASS다. 초기 기존test의 두mapidentity 가정만 실패해 원로그를보존하고 실제identity별 entry1/2 기대값으로 수정했으며 ordered second-T 검사는 그대로다. 신규collision검증은 실제 changed-order120/408건을각JVM에서관측했다. 구조상 GLM resolver miss480330→148674/guardatom1208694→660415이나 시간개선을주장하지않는다. **수정:** MemoBindingResolver.java, MemoSnapshotSelfReuseTest.java, MemoBindingResolverMapSnapshotTest.java. **의사결정:** 정확 ordered immutable결과만재사용하며feasibility/privacy/UNKNOWN/선택공간/비용의미유지. **잔여:** 통합104classes/688tests, 동결, 공식각1회측정대기. **위험/감지:** collision재삽입/guard대표객체/캐시상한과예외경계를 strictprimitiveparity 및 경계tests로검사한다. 증거snapshot-self-reuse-result.md/snapshot-self-boundary-result.md/candidate25-production.patch.

- **C25 전체검증/동결 준비:** Maven48.471초PASS,104classes688tests78.012초PASS(wrapper79.384),diffcheckPASS다. 최종Resolver0b9289cb/새5경계test/기존snapshot기대값수정을통합했고원비용·해·ordered primitive parity를보존한다. 신규collision changed-order408건도통합JVM에서관측했다. 같은공식Docker조건각1회측정전동결하며소유CPU작업을중단한다. 실제3초목표달성은아직미확인이다.

- **C25 성능 채택 철회 — 상태: C24 생산기준 복원:** 공식각1회 L3.525030810/G6.133669572초로C24대비+13.14%/+17.38%다. 정확성은유지되고snapshot표본할당-32.73%/-70.74%,전체할당-1.53%/-7.72%이나목표시간개선은입증되지않았다. 단일측정회귀의인과는단정하지않지만성능및단순성기준으로ordered-fixed-point비교를채택철회한다. Resolver와기존snapshottest를frozenC24바이트로복원하고새5경계test는원실험경로와C25동결본에보존한다. C25의688PASS와실측증거는보존하며C24기준으로후속검증한다. **잔여:** 두3초목표미달. **후속:** C26은scope중복검사의boxedstream과group내상수origin event-key필드제거만외부검증한다. **위험/감지:** 원정확map재삽입경로복원SHA6f01c1bc와전체회귀/원비용동등성을확인한다.

- **C26 상수key/boxed검사 제거 통합 — 상태: 정확성 사전검증 완료/실측 대기:** C25철회후C24기준으로 Model629fae1d와Optimizer87c98360을통합했다. 이동eventmap은각MovementGroup별독립이고두삽입경로가동일fullorigin검사를이미통과하므로key에서상수origin필드및무용private인자를제거했다. target/unitbits/LinkedHashMap순서/모든activation과원검사는유지한다. uniqueScope는원clone/검증우선순위/오류/반환순서를유지하고0~2축직접검사,나머지만별도primitivecopy정렬로boxedstream을제거한다. 실제unit폭분포에서정렬은L110/G220회다. **검증:** scope경계3/optimizer기존83PASS; literal원본OFF→두후보ON 전체rawnative+추가primitive20615/48333,exactobjective/OPTIMAL/fullselected/모든optimizer·Shapesstats/supplies동등성1PASS11.730초. 원eventmap authoritative shadow는L2984maps/20585activations,G7055maps/327149activations의상수identity/orderedbucket/동일activation참조를검사했다. 최종clean두파일함께컴파일/기존movement57회귀11.306초PASS,독립sourceCLEAR다. **수정:** MemoProgramModel.java, MemoOptimizer.java, MemoOptimizerScopeValidationTest.java. **원칙:** 새cache/근사화/후보축소없이반복검사객체만제거. **잔여:** 104classes686tests/빌드/동결/공식각1회측정대기. **위험/감지:** origin별분리·binary64순서·null/duplicate검증순서를shadow와경계회귀로보장한다. 증거candidate26-plan.md,movement-key-result.md,scope-validation-result.md.

- **C26 전체검증/동결 준비:** Maven44.940초PASS,104classes686tests70.963초PASS(wrapper72.032),diffcheckPASS다. Model629fae1d/Optimizer87c98360은독립sourceCLEAR,combinedliteralC24대비orderedmovement/전체primitive/정확해동등성을확인했다. Resolver6f01C24복원상태를유지하며C25코드는제외한다. 동일공식Docker각1회전동결하고소유CPU작업을중단한다. 두3초목표는아직미달이다.

- **C26 마무리/게시 요청 — 상태: 게시 준비:** 사용자 최신지시에따라3초추가최적화를종료하고검증된C26을게시한다. 목표미달 L3.028378372/G5.072434165초를명시하며이후loop-frame/empty-relevance실험은production에포함하지않는다. 원작업공간의미커밋변경을건드리지않고독립workspace에서GitHuborigin/main의현재f8ff5c4f기반fast-forward만사용한다. 후속평가는MLtraining10종+P1/P2+SliceLine2종의worker1/3/5/7 planning 가능성과시간이며기존W3tinyE2E와분리한다. 상세FEDPLANNER_MEMO_PERFORMANCE_C26_RESULT_2026-10-14_KO.md.

- **C26 GitHub게시 완료/전체worker행렬 진행:** 커밋802cd1381f38796f61e71956d0730728d44837ae를GitHuborigin/main에fast-forward푸시했고ls-remote로확인했다. 독립검증에서현재main1692/test2263파일과C26동결12707파일전부hash일치,전체104classes686tests및공식tinyE2E증거를확인했다. **문제:** 기존전체행렬하네스는Memo를지원하지않고sealedrenderer는legacyenum만허용한다. **해결:** 입력검증은원renderer로유지하고명시적planning-onlyDocker어댑터로Memo config만실행한다. **잔여:**56조건실행/시간집계미완료;새최적화미적용. **위험/감지:** IPendpoint전환/worker ranges/privacy/원본템플릿hash와timing구분을receipt및독립review로확인한다. 계획FEDPLANNER_MEMO_W1357_PLANNING_MATRIX_PLAN_2026-10-14_KO.md.

- **56조건planning하네스 사전검증 완료 — 상태: 공식실행 준비:** 현재renderer와sealedfixedpoolstage의56cells/88inputs/352shards를독립검증했다. 원DML/endpoint변경0,worker별orderedports/ranges/privacy및모든local sidecar일치다. 구renderer와구stage의StepLMhash불일치는검증완화없이현재sealedstage선택으로해결했다. 기존legacyMatrixProbe가Memo성공을CandidateE2E누락으로실패처리하는오류를제거하고productionDMLScript compile-only를직접사용한다. 각cell의Memo/rendereridentity를분리하고원본metadata를읽으므로workerJVM/RPC/학습runtime은없다. C26동결12707파일exactset/hash일치,adapterunit11/11/syntax/diffcheckPASS,최종독립reviewCLEAR다. runnerSHA39c111b2/testsSHA d1396d7c. timeout/kill/하네스deadline을구분하고resume시동일manifest/schema/cell만허용한다. **잔여:**공식Docker56조건각1회실행/시간집계대기. **위험/감지:** networknone으로예기치않은RPCfailclosed,OPTIMAL후compile실패도별도분류하며완전한Memo/compile시간만기록한다.

- **56조건Docker환경복구 — 상태: planning미실행/재준비:** 첫matrixoutput를/grid에직접bind하자SnapDocker가/evidence/container-run.sh를볼수없어56조건모두setup_failure로JVM시작전에종료했다. coordinator.log/setup-complete/Memo기록0,실제planninginvocation0을확인했다. 원실패artifact는보존하며이를planner실패나측정값으로사용하지않는다. 기존Docker하네스와같이/home staging을사용하는mount행정preflight에서prodclass/봉인stagehash접근PASS했다. 코드·입력·자원변경없이output만/home로지정하고실제56planning은각1회실행한뒤/grid에복사한다. **위험/감지:** Docker 내부파일hash와실제coordinator로그/compilemarker로입력가시성및1회실행을확인한다. 환경실패56회도최종보고서에명시한다.

- **56조건후처리/상대라이브러리환경복구 — 상태:44조건완료/12조건Memo미진입:** home실행56조건에서GNMF4만OPTIMAL/fullcompile/실행0으로성공했고LogReg/GLM8은optimizer300초timeout이다. P1/SliceLine12는source(./code/...)의CWD가봉인원실험디렉터리와달라ParseException으로Memo단계0회였다. 이를Memo미지원으로분류하지않는다. 원artifact를보존하고dependency가있는12조건만원experiments디렉터리에서실행하도록CWD1행을수정했다. 44나머지조건의컨테이너script는byte동일,Java/config/DML/metadata/비용/자원은불변이다. adminDockerpreflight에서두라이브러리sealedhash일치,unit12/12/syntax/diffcheckPASS,독립reviewCLEAR. **잔여:** 이12조건의첫Memo실제시도및통합최종표. **위험/감지:** 이전환경실패와12복구시도도모두공개하고44기측정조건은재실행하지않는다.

- **W1/3/5/7全workload검증 완료 — 상태:56조건평가완료/기능성4성공52실패:** authoritative행렬은44원조건+12라이브러리CWD복구조건이며GNMF만각worker에서OPTIMAL/fullcompile/RuntimeProgram완료,execution0이다. Memo시간W1 .570333045/W3 .848987902/W5 .726946754/W7 .735119537초;fullcompile1.243021/1.636963/1.385830/1.409474초. LogReg/GLM8은model완료후optimizer에서coordinator300초timeout. PARFOR미지원16(KMeans/P1/SliceLine),UNKNOWN_SIZE7(PCA4/ALS W1/P2 W1·5),ALS executionlayout모호3,P2 canonicalingress위반2,LMUNRESOLVED4,L2SVM보고INFEASIBLE4,StepLMcostcontext4,GMM위치미확정NPE4다. 성공하지못한행의Memo/compile완료시간은null이고실패wall과구분했다. rc0만으로성공을주장하지않는다. **검증:** 352주소/range위치,187distinctmetadata/1721실제binaryblock sealedhash일치,engine12707파일불변,하네스12testsPASS/독립reviewPASS/잔여ownedcontainer·Java없음. 앞선payload없음이라는해석은.data가디렉터리임을확인해정정했다(존재하지만compile-onlyruntime읽기없음). grid환경56/JVM0와12relative-parse실패도보존·공개한다. **잔여/위험:** 전체workloadplanning지원은미달;timeout을불가능으로단정하지않고GMM발생위치및L2SVM상태원인을추가추측하지않는다. Java/Oracle/privacy/feasibility수정은없다. 결과FEDPLANNER_MEMO_W1357_PLANNING_MATRIX_RESULT_2026-10-14_KO.md/evidence평가JSON·CSV·원로그.

## 실제 full ML timeout 원인 진단 — 원인 확인, 성능 미해결

- **문제 정의/상태:** 앞선 tiny E2E의 LogReg3.028s/GLM5.072s를 full50000×128 ML workload의 성능으로 일반화할 수 없다. 실제56조건 평가에서 두 workload W1/3/5/7 모두300초 timeout. 이번은 원인 분석 요청이며 production 수정 작업이 아니다.
- **환경/재현:** C26동결 engine, pinned Docker4CPU/8GiB/heap3GiB, sealed fullDML/config/default costs 유지. `run_LAN_docker.sh --memo-planning-matrix`로 W3 LogReg/GLM 진단 각각1회, 순차 실행. 별도 root `/home/mchoi/full-workload-timeout-diagnostic-{logreg,glm}-20261014`; 두rc124/300초, optimizer 완료receipt 없음. 원56조건 시간을 덮어쓰지 않았다.
- **확인된 원인:** 메인JFR samples LogReg4925중4804(97.54%), GLM5099중4741(92.98%)가 `ReducedSearch.tryForestTail` 포함. cyclic core exact 탐색 중 residual forest DP의 구조/TreeCell/정확 비용합을 매번 재계산한다. 스택은 각각17/51 enumerateCore frames(16/50재귀호출)이며 방문노드 수가 아니다. primitive tuple 캐시는 subtree 최적 비용 message를 재사용하지 않는다.
- **CPU/메모리:** LogReg mainCPU162.498s/elapsed163.53s; GLM mainCPU278.718s/elapsed280.37s. 실제 main allocation counter delta 약130.969GB/59.973s 및112.691GB/59.979s. GC pause 합0.262s/0.196s, GC후sampledheap47.22MiB/95.40MiB. OOM/GC정지가 주원인이라는 해석을 배제한다. attach첫allocationweight가 기록전누적값을 포함해 weight합을60초할당으로 쓰면 안 된다.
- **해결 방법/변경:** production 구현은 하지 않았다. 문서·프로파일링근거·읽기전용수집/요약스크립트만 작성. 다음 개선은 완전한 separator assignment/live-domain identity 기반 exact component-message 재사용, 불변 factor 구조 사전계산, bounded factor-local truth/cost table 우선. UNKNOWN/DEFER/privacy/workerbinding/exactcost/lexicographic optimum 유지; 후보축소/근사화/fallback 없음.
- **수정 파일/검증:** `docs/FEDPLANNER_FULL_WORKLOAD_TIMEOUT_ROOT_CAUSE_2026-10-14_KO.md`, `docs/evidence/full-workload-timeout-2026-10-14/`, 이session누적문서. 원matrixW3와DML/config byte동일, source/하네스diff0, profilerDataLoss0, 증거hash확인. 분석-only이므로 새로운production회귀테스트통과나timeout해결을주장하지 않는다.
- **잔여 버그/위험:** full성능미해결. 방문노드/forestDP회수/prune비율/cutset·separator폭 미계측; 정확한입력차이별인과는ablation없어 미확정. profiler는W3만이고W1/5/7내부분포로일반화하지않는다. memoizedmessage key가factor/domain/UNKNOWN/tie-break를빠뜨리면잘못된최적해위험; cacheOFF/ON전선택·원cost audit·최적해동등성으로검증해야 한다.
- **의사결정 근거:** Oracle/런타임/플래너정확성규칙을수정하지않았다. 실제CPU증거가 가리키는optimizer의반복DP계산을후속수정대상으로특정했다.

## 실제 ML compile 10초 목표 — 진행중 (후보01)

- **요청/평가계약:** tiny E2E가 아니라 sealed actual LogReg/GLM×W1/3/5/7 총8조건에서 fullinitial+fullcompile10초이하, RuntimeProgram완료/execution0/OPTIMAL/정확cost audit. `.omx/goals/performance/actual-full-ml-under10`과strict actualML evaluator를먼저고정했다. 이전tiny실측을성능기준으로재사용하지않는다.
- **구현 전 검증:** 독립brute-force회귀7개를C26에먼저실행. 최초1실패는BigDecimal scale-only차이였으며반올림없이compareTo==0으로정확수치를비교해7/7PASS로행동고정했다. 최초실패로그도보존했다.
- **구조수정:** `MemoOptimizer`에bounded exact class-compressed bucket elimination을추가. factor별truth/cost인증class의교집합을해당bucket의separator에만적용하고, 미래owner nativecost는아직포함하지않는다. 제거owner의모든liveordinal을평가,childmessagebackpointer로globallex보존. UNKNOWN/폭예산초과는기존exact경로,finite-nodebudget도기존경로다. cost단위변경/후보삭제/feasibility·privacy·workerbinding완화없음.
- **실측:** candidate01 actualW3각1회 LogRegoptimization0.798584초, GLM2.070155초. OPTIMAL/원cost auditPASS, eliminationcells14744/1360977, messagerows2378/229309. 하지만후속supplySchedule의Duplicate selected supply edge로compile실패했다. 외부wall5.74/8.19초는완료compile시간이아니므로목표성공으로세지않는다.
- **검증:** 새path+기존legacycache/forest/nodebudget를분리해optimizer98testsPASS. independentarchitectCLEAR. sourcefactor/tableinduction과UNKNOWN중간종료·lex·sparseordinal68·예산fallback회귀포함. 전체Memo/build와최종8조건은대기.
- **평가경로삭제:** runnable tiny ml_logreg/ml_glm case/DML생성/Y_ML_LOGREG fixture와tiny실험receipt cost-authority의존삭제. 기존이름요청은실제평가route로명시적오류안내. 과거증거와일반unit-graph정확성테스트는보존. Python하네스64testsPASS.
- **잔여/회귀위험:** 실제compile완료미달. 동일compilededge의여러occurrence를별도Supply로만드는기존재구성문제를정확recipe합집합으로수정중; DIRECT-onlycontext도포함해야하며target/worker/동일source recipe충돌을숨기면안된다. context별합법성과retainedorigin분석은계속실행한다. 모델변경후soundness/regression/실제8조건검증필수.

### 실제 ML 10초 목표 — 물리 supply occurrence 중복 수정 / 전체 회귀 통과

- **상태:** production 수정 및 회귀 완료, 실제 8조건 최종 평가 대기.
- **증상:** 후보01은 실제 W3 LogReg/GLM의 optimizer를 각각 0.799/2.070초에 OPTIMAL로 끝냈지만, 같은 compiled Hop input에 occurrence별 Supply를 생성해 `Duplicate selected supply edge`에서 compile이 중단됐다. 부분 planning 성공을 목표 달성으로 처리하지 않았다.
- **원인/해결:** `MemoProgramModel.reconstructSupplies()`에서 physical edge별로 모든 occurrence의 source recipe를 합친 뒤 한 Supply만 emit한다. DIRECT-only 생략은 전체 occurrence 합산 후 수행하여 LOCAL/UPLOAD와 ROW/DIRECT가 함께 보존된다. target·same-source recipe·worker pool 충돌과 IMPOSSIBLE은 예외로 차단한다. use별 legality 및 origin/retention별 schedule 순회는 그대로 유지했다.
- **수정 파일:** `MemoProgramModel.java`, `MemoControlFlowParityTest.java`.
- **의사결정 근거:** lowering/runtime은 physical input 하나에 완전한 guarded recipe 집합 하나를 요구한다. 서로 다른 동적 origin의 비용·activation을 합치는 변경이 아니다.
- **검증:** 새 LOCAL/LOCAL/protected-ROW shared input fixture가 수정 전 Supply 2개로 실패했고, 수정 후 DIRECT+UPLOAD를 가진 Supply 1개와 schedule 재구성에 성공. 독립 architect review CLEAR. 전체 Maven test-compile 성공, 106개 class / 700개 테스트 통과 (`experiments/full-ml-under10-20261014/candidate02-{compile,unit}.log`).
- **잔여 이슈:** sealed 8조건의 full compile 측정 및 평가기의 artifact/engine provenance 강화 검증이 남음.
- **잠재 회귀 위험/감지:** context별 recipe 유실은 mixed DIRECT/movement 회귀로, 동적 origin action 합침은 retained-supply/runtime 회귀로 검출한다. 성능 성공 판정은 전체 compile/RuntimeProgram receipt와 execution=0을 요구한다.

### 실제 ML 최종 평가 패키징 오류 — 측정 전 Docker mount 실패

- **상태:** packaging 수정, 동일 기준 fresh root 재평가 진행.
- **증상/원인:** 원 final root의 8조건 모두 Docker rc125/setup_failure. `cp -a target/lib`가 실제 JAR 대신 `/grid`로 연결되는 symlink를 복사했고 Snap Docker가 mount를 만들지 못했다. coordinator JVM/성능 측정은 한 번도 시작되지 않았다.
- **해결:** 실패 engine/root를 보존하고 `evaluation-engine-v2`에 dependency를 `cp -aL`로 실제 파일로 동결, 전체 해시 inventory에 포함했다. production class는 700-test 검증 후보와 동일하다. `validate_engine()`은 symlink가 있으면 preflight 실패하도록 강화했다.
- **검증:** valid class manifest + 외부 lib symlink가 기존 preflight를 통과하는 회귀를 먼저 재현했고, 새 검사가 이를 거부한다. 성능 판정/한계/입력/자원은 변경하지 않고 `/home/mchoi/full-ml-under10-final-r2-20261014`에서 조건당 한 번 측정한다.
- **잔여/회귀 위험:** 실제 compile 측정은 여전히 필요하다. mutable 외부 dependency가 snapshot으로 오인되지 않도록 hash 및 symlink 검사를 유지한다.

### 실제 ML 전체 compile 10초 목표 — 최종 통과

- **상태:** 해결. `/home/mchoi/full-ml-under10-final-r2-20261014/acceptance.json` PASS, 8/8.
- **환경/조건:** actual sealed LogReg/GLM 50000×128, worker 1/3/5/7, pinned Docker 4CPU/8GiB/heap3GiB, production 비용, 각 조건 1회.
- **결과:** full compile LogReg 3.429–3.982초, GLM 6.239–8.499초. full initial 최대8.480969256초. 모두 OPTIMAL, RuntimeProgram 완료, runtime execution0. Docker 호출 wall도 최대9.532초.
- **해결 요약:** exact factor-class bucket elimination으로 cyclic core별 반복 forest-tail 계산을 제거하고, occurrence별 source 조건을 보존하며 physical supply를 단일화했다. tiny builtin ML 성능 case/생성기를 삭제하고 actual-only 엄격 evaluator로 전환했다.
- **검증:** 106 Java classes/700 tests, 변경 하네스 Python70tests, compiler/syntax/diff check PASS. 원비용 audit와 feasibility/privacy/UNKNOWN/worker/exact/globallex 계약 유지. 독립 architect 및 evaluator 리뷰 통과.
- **수정 파일/재현/증거:** `docs/FEDPLANNER_ACTUAL_ML_UNDER10_RESULT_2026-10-14_KO.md` 및 `docs/evidence/actual-ml-under10-2026-10-14/` 참조.
- **잔여 이슈:** 임의의 다른 넓은 cyclic graph까지 10초를 보장하지 않는다. actual training runtime 전체 실행은 이번 범위 밖이다.
- **잠재 회귀 위험/감지:** GLM W3 compile 여유는 약1.50초. 이후 성능 판단은 actual8 evaluator로만 하고 tiny 결과를 다시 성능 권위로 사용하지 않는다.

### 범위 밖 Python 전체 discovery 실패 — 명시적 검증 한계

- **상태:** 미해결, 실제 ML 성능 gate 및 관련 soundness 회귀와 별도.
- **검증:** 전체 `python3 -m unittest discover -s scripts/fedplanner/tests -p 'test_*.py'`는 661 tests, failures2/errors16/skipped1. 변경 하네스 70tests와 Java700tests는 PASS.
- **관측 원인 범주:** 외부 plan-space fixture 누락, pinned launcher/source manifest drift, campaign timeout contract fixture 불일치, frozen compact verifier 누락, public-ignore expected14/actual16.
- **실패 파일:** `test_build_plan_space_exclusion_ledger`, `test_verify_compact_physical_shard`, scope audit/certify, closed comparison/inventory, matrix overhead/runtime diagnostic, pinned runtime contract 테스트.
- **조치/잔여:** 실제 성능을 좋게 보이기 위해 이 실패를 skip하거나 assertion을 완화하지 않았다. 변경 전 동일 환경 전체 discovery 비교는 수행하지 않아 전부 기존 실패라고 단정하지 않는다. raw log는 `experiments/full-ml-under10-20261014/full-python-discovery.log`, 문서 evidence에도 복사했다.
- **회귀 위험/감지:** 이 범위의 campaign/tooling을 수정하거나 사용하는 후속 작업에서 fixture/manifest 계약을 별도로 복원하고 전체 discovery를 재검증해야 한다.

## 실제 나머지 workload 지원 — 진행중 (사용자 우선순위 변경)

- **상태/요청:** 기존 actual LogReg/GLM 8조건 under10 구현은 `b95989bdddd7`로 GitHub main 게시 완료. 추가 최적화 계획보다 나머지 실제 workload의 planning+compile 성공을 먼저 수정하라는 새 요청을 적용한다.
- **새 W3 실측:** frozen final engine로 다른 12 workload 각1회: GNMF만 OPTIMAL/fullcompile1.299955s/runtime0. 나머지11은 timeout이 아니라 기존 shape/cost/native catalog, PARFOR, canonical ingress 및 GMM NPE 오류다. raw `/home/mchoi/remaining-optimization-workload-scope-20261014`.
- **해결 계획:** 실제 오류 stack을 확보하고 실패 회귀를 먼저 추가한다. source/Oracle/runtime 계약에 맞는 지원을 구현하고 W3 후보 검증 후 workers1/3/5/7 전체를 검증한다. `MemoProgramFacts`의 PARFOR 거절, `MemoSources`의 정확한 canonical ingress 검사를 단순 삭제하지 않는다.
- **잠재 위험:** sealed P2의 ceil-tail range는 현재 ordered-remainder canonical geometry와 다르다. 입력을 조용히 변경하거나 다른 map을 같은 canonical state로 인정하면 worker binding이 깨진다. PARFOR를 순차loop로 간주하면 task-local state/result merge/retention이 깨진다. 이 두 계약 확장은 별도 설계와 정확성 회귀가 필요하다.
- **변경/검증:** 아직 이 요청의 production 수정 없음. 진단 옵션은 기존 `-debug` CLI만 추가하며 성능 합격으로 사용하지 않는다. 기존 실제 LogReg/GLM under10 gate를 회귀 기준으로 유지한다.

### SliceLine 원본 reproduce와 metadata-assisted planning 경계 — 방법론 결정, 구현 미완료

- **증상/사용자 질문:** P2 dummycode의 exact output width가 봉인 metadata에 없어 worker 집계 수집을 제안했으나, 원본 SliceLine reproduce의 실제 경계와 연구 목적에 근거해야 한다는 지적.
- **확인:** 원본 `sigmod2021-sliceline-p218`은 run4에서 recoded X/error vector를 준비하고 run5에서 본 알고리즘을 실행한다. one-hot/candidate pruning/내부 recode 등은 본 연산에 남아 있다. 원본 shell은 Java 프로세스 시간에서 1.5s를 차감하며 3회 반복하지만 현재 실험에 이 관행을 복사하지 않는다.
- **결정/해결:** 입력 통계 준비는 공통·정확·명시적 privacy 허용·동일 artifact 조건으로 별도 도입 가능. P2 전처리 자체를 측정 밖으로 옮기지 않는다. cold total에는 수집 포함, warm compile-only 별도 표기. aggregate count 허용은 categorical dictionary 공개 허용과 다르다. 새 production collector는 아직 구현하지 않았다.
- **파일/증거:** `docs/FEDPLANNER_SLICELINE_REPRODUCE_METADATA_CONTRACT_2026-10-14_KO.md`, `docs/evidence/sliceline-metadata-methodology-2026-10-14/manifest.json` 및 해시 고정 원본/현재 adaptation 복사본.
- **검증:** 원본 로컬 subtree clean 및 소스/timer 경계 직접 확인. reproduction 스크립트 실행/데이터 변경 없음. 원본 중앙집중 실험을 private federated 공개 권한 또는 compile-only 성능 증거로 사용하지 않는다.
- **잔여 이슈:** SliceLine 중간 candidate shape·일반 PARFOR, P2 global distinct exact 획득/privacy와 canonical geometry는 별도 지원 공백이다. 아직 all14 성공 아님.
- **잠재 회귀 위험/감지:** 사전 실행 비용 숨김·미승인 dictionary 유출·stale statistics·다른 입력 병합. cold/warm 이중 타이머, 공개 정책 부정 테스트, snapshot/spec/pool identity 및 CP/FED encoder parity로 탐지한다.

- **독립 방법론 review 반영:** exact global dictionary 수집은 P2 transformencode build의 spec-specific preprocessing임을 명시했다. workload/spec 이전 공표 통계와 구분하고 cold 비용 전부 포함한다. metadata를 baseline에 제공만 하고 소비하지 못하면 공정성 충분 조건이 아니므로 native/no-new-metadata와 common-information 비교를 분리한다. OPTIMAL/coverage/privacy/worker identity/frozen-cost 재계산/full RuntimeProgram 증거 gate와 artifact provenance를 추가했다. 원본 조사 완료와 collector/전체 지원 완료를 구분한다.

### Actual workload 첫 후보와 후속 수정 — 진행중, 아직 전체 합격 아님

- **환경/재현:** 기존 actual sealed DML·metadata·범위, `run_LAN_docker.sh --memo-planning-matrix`, W3 각1회, 4CPU/8GiB/3GiB coordinator heap/network-none/compile-only. 원본 actual8 under10 기준 main `b95989bdddd7` 유지. 후보별 engine classes/dependencies SHA 동결.
- **관측/원인:** PCA EIGEN output shape 미전파; ALS WDIVMM의 보조 입력 U/V를 주 실행 map으로 오판; LM 실제 CP-only solve를 미구현 catalog로 분류하고 rdiag canonical 축 증명 누락; GMM non-affine counter 처리의 ternary null unboxing. 별도 RED 테스트에서 기존 frozen engine의 PCA/ALS 3개, LM 3개 실패 및 GMM counter NPE 확인.
- **candidate01 해결:** EIGEN exact input 기반 output dimensions, WDIVMM input0 기반 실행 layout, solve의 실제 native missing 분류와 DIAG 축 증명, affineDelta 명시적 분기. test-compile 및 집중76 tests PASS. raw `experiments/actual-workload-support-20261014/candidate01-*`.
- **candidate01 실제 결과:** `/home/mchoi/actual-support-candidate01-w3-20261014`. LM만 OPTIMAL/fullcompile **1.157091s**/runtime0 PASS. PCA는 다음 ctable output shape, ALS는 nested phase-head alias cycle, GMM은 contextual metadata shape 재귀 StackOverflow에 도달했다. 이전 오류 해소를 전체 workload 합격으로 세지 않는다.
- **후속 수정:** PCA seq×sort-index permutation의 no-explicit-dims CTABLE만 정확한 D×D 전파. EIGEN NNZ는 dense 추정을 사실로 넣지 않고 -1 유지. ALS는 entry/backedge 모두 independently exact인 동일 shape invariant만 phase head에서 재사용. L2는 LOCAL/DIRECT/LEGAL scalar INT64 entry→FP64 repeated contract를 분리해 runtime cast 없이 검증. GMM scalar nrow/ncol 조회가 public bindingShape로 PhaseEvaluation을 재생성하던 문제를 같은 phase proof/cycle state 재사용으로 수정.
- **GMM 검증:** `MemoRecursiveMetadataShapeTest`의 first-overwrite+metadata-driven 반복 dimension fixture가 frozen candidate01에서 StackOverflow RED (`gmm-shape-before3.log`), 수정 후 candidate02/03 집중 실행에서 PASS. 이전 두 fixture는 재현 실패/fixture 오류로 보존했고 RED 증거로 사용하지 않는다.
- **검증 상태:** candidate02 126tests 중 5개, candidate03 132tests 중 5개 새 PARFOR/unknown-read fixture 실패. GMM·L2·나머지 집중 테스트는 통과했지만 전체 합격은 아직 아니다. PARFOR fixture 및 exact-call-bound proof를 담당 agent가 수정/재검증 중. 최종 shared build와 actual 재실행 전에는 새 속도/지원 성공을 주장하지 않는다.
- **잔여 이슈/위험:** 일반 PARFOR 지원은 미구현; exact0/1에서만 기존 sequential runtime 경로를 증명하여 허용하는 범위다. review에서 multi-child bound 오판, scalar cast/stride 불일치, zero exit 누락, unused function registry 처리 문제가 발견되어 수정 중이다. Runtime proof drift는 실행 전에 fail-close해야 하며 zero path의 planned exits도 수행해야 한다.
- **원칙/판정 근거:** UNKNOWN을 임의 exact로 전환하거나 입력/DML을 줄이지 않는다. loop-head 가변 shape·명시적 CTABLE 차원·미승인 scalar 타입·다회 PARFOR를 부정 회귀로 탐지한다. 기존 700 soundness 회귀와 actual LogReg/GLM을 후속 gate로 유지한다.

### 비귀납적 metadata dimension의 허위 EXACT head — 근본 원인 수정, 통합 검증중

- **증상:** 새 부정 회귀 `A=matrix(1,1,3); for(i in 1:3){A=matrix(1,rows=nrow(A)+1,cols=ncol(A));}`는 실제2×3→3×3→4×3인데 contextual head를2×3 EXACT로 고정했다. candidate04 full731tests 중 이 신규1개만 실패했다. global head는 UNKNOWN인데 contextual early-return에서 compiler 초기 차원을 재사용한 것이므로 캐시-hit 때문이라고 단정하지 않았다.
- **원인:** `computeBindingDependencies`의 축약 phase 의존성은 globally exact-looking binding을 제외한다. `resolvedBindingShape`가 이를 exact relevance authority로 사용하여 실제 nrow(A) 의존성을 놓쳤다. 별도 probe에서 FIRST/MIDDLE binding 둘다 frozen2×3, phase cache counter0을 확인했다.
- **수정:** 완전한 syntax/transitive closure인 기존 `shapeDependenciesByBinding`을 relevance와 cache key의 공통 authority로 사용. 새 key 중복 구현/전역 캐시 off 없음. root는 동일 PhaseEvaluation 재사용, integer scalar dimension의 exact arithmetic/overflow 거절, 미해결 DataGen dimension을 초기 compiler expression으로 복구하지 않는 검사를 함께 유지했다.
- **검증:** debugger fresh private overlay metadata2tests PASS, 전체shape74tests PASS(34.739s). candidate05에서 최신 통합 test-compile/전체 회귀 재실행 중이다. 성능 효과는 아직 실제 Docker로 확인하지 않았으며, 기존 under10를 다시 검증해야 한다.
- **추가 review 수정:** eager invariant head seed는 NNZ loss를 고친 뒤에도 GLM phase query counter를 거의2배 증가시켰으므로 정상 경로에는 넣지 않고 실제 재귀 cycle에 한해 independently proved invariant를 lazy seed로 사용한다. context별NNZ32/2 유지와 실패 seed 정리를 검증했다.
- **잔여/위험:** 더 정확한 dependency closure가 실제 planning 비용을 증가시킬 수 있다. 그래도 의존성을 생략해 UNKNOWN을 EXACT로 만드는 이전 shortcut으로 복귀하지 않는다. 정확성 gate 후 동일 actual workload로 계측하고, 증명된 불변 결과만 재사용한다.

### Candidate05 실제 W3 — 3/9 완료, GLM compile 회귀 포함

- **상태:** 진행중. test-compile 및 108 classes / 733 tests PASS는 실제 workload 완료를 보장하지 않았다.
- **재현/증거:** `experiments/actual-workload-support-20261014/candidate05-w3-command.sh`, raw `/home/mchoi/actual-support-candidate05-w3-20261014`; 동결 candidate05 engine, W3 각1회, 실제 sealed workload 유지.
- **결과:** LogReg fullcompile3.801030s, LM1.343043s, L2SVM2.409471s PASS/OPTIMAL/runtime0. GLM은 optimizer OPTIMAL 이후 `Selected loop contract has unresolved phase shape/source: beta`로 전체 compile 실패. PCA ctable shape, ALS WDIVMM COL canonical effect, GMM binary dimension, KMeans CTABLE execution anchor, P1 PARFOR proof 미해결. 6건 실패를 timeout이나 성공으로 바꾸어 기록하지 않는다.
- **수정 계획:** GLM phase contract의 physical axes와 NNZ identity를 분리해야 하는지 실제 증거로 확인; PCA는 실제 rewritten pattern, ALS는 runtime range effect를 검증. KMeans의 ordinary CTABLE은 `CtableFEDInstruction.processInstruction()`의 첫 번째 federated category input map을 사용하며 secondary는 materialize/sliced-broadcast한다. 두 축이 달라도 실행 map이 모호하지 않으므로 비용 adapter가 이 dispatch를 정확히 반영하도록 RED 회귀 후 수정한다. expand form은 두 번째 value input을 따로 사용한다.
- **원칙/위험:** 비용 adapter 수정은 후보를 강제 제거하거나 opcode를 CP-only로 축소하는 것이 아니다. metadata-only gate와 canonical/privacy/coverage 검사는 그대로 둔다. GLM의 실패 원인은 아직 가설이며, NNZ를 전역 삭제해 통과시키지 않는다. 수정 후 전체 regression 및 actual candidate별 1회 검증을 다시 수행한다.

### P1 region78은 proof 누락이 아니라 실제 다회 PARFOR — 미지원 범위 확정

- **상태:** 미해결, read-only 근본 원인 확인.
- **증상/근거:** candidate05 P1은 `UNSUPPORTED_PARFOR regionId=78`. 실제 sealed `gmm_p1_compat.dml:118`의 KMeans 초기화는 `runs=10`; main P1 직접 호출은 `runs=1`. builtin `kmeans.dml:122`의 동일 `parfor(run_index in 1:num_runs)`가 두 문맥에서 실행된다. `n_components=1`은 k이지 runs가 아니다. compat 기본 init_params="kmeans"이므로 내부 경로는 실제 도달 가능하다.
- **판정:** 모든 도달 가능한 호출을 검사하는 `MemoProgramFacts.validateActiveLoopIterations`의 >1 및 호출 간 불일치 거절이 맞다. literal10을 1로 바꾸거나 해당 호출을 무시하는 proof 확장은 금지한다.
- **필요한 해결:** invocation/context별 cardinality와 task-local binding, planned supply/retention, result merge, privacy/coverage 및 비용을 모델링하는 일반 PARFOR 지원. 현재 SB별 단일0/1 registry로는 {1,10}을 표현하지 못한다. worker input metadata 수집과 별개 문제다.
- **검증/위험:** mounted dependency receipt/hash와 소스 call chain 확인; 변경/실행 없음. 추후 일반 지원은 다중 호출·병렬 task 상태 격리·merge와 원본 결과 동등성 검증이 필요하다. PARFOR를 FOR로 치환하거나 강제 직렬화해 성공 처리하지 않는다.

### CTABLE 비용 실행 map 정정 — 집중 회귀 통과, actual 검증 대기

- **증상/원인:** KMeans 실제 six-input CTABLE `[ROW,COL,LOCAL,...]`의 두 input axis를 비용 adapter가 모호하다고 판정. runtime ordinary CTABLE은 input0(없으면 input1) map을 사용하고 secondary를 materialize/sliced-broadcast한다. expand는 input1 value map이다.
- **수정 파일:** `MemoCostModel.java`, `MemoCostModelTest.java`. primary 선택만 runtime과 맞추고 기존 준비/통신/결과 비용을 재사용한다. 비용 축소, candidate pruning, privacy gate 변경 없음.
- **검증:** fixture/module 오류인 최초로그와 구분하여 `ctable-cost-before-r2.log`에서 신규2개 AMBIGUOUS 오류 RED; 수정 후 cost+CTABLE preparation **30tests PASS** (`ctable-cost-after.log`). W1/3/5/7, reversed/local-secondary/expand와 exact cost formula 검증.
- **잔여/위험:** actual KMeans는 다음 coverage/shape 문제가 있을 수 있으며 아직 완료를 주장하지 않는다. primary map 외 입력의 준비 비용 유실을 회귀로 감지한다.

- **CTABLE 독립 cross-review:** 처음에는 `[LOCAL,LOCAL,ROW(weights)]`가 누락된 valid anchor라는 지적이 있었으나, 실행 순서 재검토 후 철회했다. `CtableFEDInstruction.java:164–167`은 local input0의 null map을 먼저 역참조하고, intended weights reorder는 `169–176`에 있어 도달하지 못한다. parser/Oracle는 weights-only ROW를 허용하므로 이는 **기존 catalog/runtime 불일치로 미해결**이다. 비용 adapter에서 이 조합을 성공으로 꾸미지 않고 명시적으로 거절한다. 완전 지원은 runtime dimension/reorder 및 준비 비용의 별도 동시 수정·검증이 필요하며 이번 mixed-axis KMeans 수정의 완료 범위가 아니다.

### Candidate06 실제 W3 전체14 — 5개 compile 완료 / 9개 미완료

- **상태:** 전체 지원 미완료. 새 통합 test-compile / 108classes **745tests PASS** (114.016s). `candidate06-{compile,regression}.log`와 result JSON 보존.
- **실측:** `/home/mchoi/actual-support-candidate06-w3-20261014`, 실제14개 W3 각1회. LogReg3.951203s, GLM10.920863s, LM1.306245s, L2SVM2.684724s, GNMF1.275093s fullcompile/OPTIMAL/runtime0. **GLM은 compile 회귀는 해소됐지만 under10 미달**이다.
- **GLM 비교:** published W3 8.499098s 대비 rules2.636→4.297s, optimize2.284→3.380s. objective `426.222945173122011978444871186836413398668632623866869835183024406433105468750000`, nodes1,360,977/checks94,153 동일. 완전 dependency closure의 phaseShape requests389,624→1,204,219/cache entries225,123→788,142 증가. NNZ만 다른 physical runtime contract 합치기는 성공했고 cost NNZ는 유지했다.
- **다음 오류:** PCA ctable exact proof 이후 downstream MM128×-1; KMeans mixed-axis CTABLE 이후 SEQ50×-1; GMM binary<=-1×128; StepLM unresolved repeated cost context. ALS native-effect 오류 해소 후 optimizer **300초 timeout**. P1·SliceLine2개는 genuine PARFOR unsupported. P2는 canonical ingress 실제범위 불일치. 실패를 부분 planning 성공으로 집계하지 않는다.
- **별도 GLM JFR:** `/home/mchoi/actual-support-candidate06-glm-profile-20261014`는 diagnostic-only1회, compile9.545022s. startupJFR 때문에 동일한 acceptance 측정이 아니므로 더 빠른 수치를 under10 합격으로 골라 쓰지 않는다. 원시JFR/process100ms samples + 추출 `candidate06-glm-{events.jsonl,profile.json,profile.txt}` 보존. 368 main execution samples; shape/resolver가 보이는 truncated/unattributed139 samples와 rule-building63 samples가 주요 반복 작업. 26GC pause합0.429s, observed peak RSS1,024,245,760B; 할당샘플 가중치는 추정량이며 실제 메서드 시간/retained bytes가 아니다.
- **잔여/위험:** source closure 정확성을 되돌려 속도를 복원하지 않는다. key 구성의 동일 identity 재사용과 proof의 downstream 전파부터 검토. 미해결 일반PARFOR/data-dependent middle shape를 metadata 수집만으로 해결할 수 있다고 주장하지 않는다. 변경은 아직 미커밋이며 publishedmain은 기존 b95989bdddd7이다.

### SliceLine 문제 정의 보고서 — 문서화 완료, 지원 구현은 미완료

- **상태/요청:** 사용자가 metadata·단계별 planning 선택 대신 정확한 문제의 보고서를 요청했다. 새 production 수정 및 추가 workload 평가를 시작하지 않고 문서화로 전환했다.
- **증상:** candidate06 실제 ADULT/COVTYPE W3의 첫 실패는 `UNSUPPORTED_PARFOR regionId=10`이다. 이 gate 이후의 shape 오류나 timeout은 실제 관측한 것으로 주장하지 않는다. region10의 lexical mapping은 미확인이다.
- **원인/분리:** 일반 PARFOR 의미 지원 공백과 데이터 의존 intermediate shape 정보 부족은 별개다. 원본 reproduce도 준비된 X/e만 분리하며 pruning·후보 생성을 본 실행에 남긴다. P2의 실제 첫 오류는 canonical ingress geometry이며 input domain 정보 수집은 별도 과제다.
- **해결/파일:** `FEDPLANNER_SLICELINE_PLANNING_BLOCKERS_ROOT_CAUSE_2026-10-14_KO.md`에 관측·소스·추론·미확인을 구분했다. 공개 main과 미커밋 candidate06/현재 작업 트리도 구분했다. production 해결 완료를 뜻하지 않는다.
- **검증:** W3/global 개별 통계는 같지만 feature-error 연관성에 따라 기본 S가2×4/4×4로 달라지는 정수 산술 반례 PASS. 성능 benchmark나 실제 데이터 대체가 아니다. 두 evidence manifest 총25파일 SHA와 원본 복사 일치, local 문서 링크 존재 확인. content hash가 다른 입력을 병합하는 근거로 사용하지 않는다.
- **잔여 이슈:** 일반 PARFOR, P2 수집/공개 정책/geometry, intermediate shape의 표현·실행 경계와 최적성 계약은 미해결. KMeans SEQ 후속 패치는 사용자 요청에 따라 미컴파일·미검증 상태로 보존했다. 새 candidate07/commit/push 없음.
- **원칙/위험:** UNKNOWN을 exact로 꾸미거나 pruning 실행을 무료 metadata로 숨기지 않는다. staged optimum을 전체 optimum으로 오인하지 않는다. 후속 검증은 실제 원본 workload와 fullcompile receipt로 수행해야 한다.
- **기존 진단 회수:** 보고서 요청 전에 시작한 ALS60초 JFR은 완료되어 읽기 전용 추출만 수행했다. 5,063 main execution samples 중5,011개가 legacy-search 경로, GC337회 pause합0.308s, observed RSS480,301,056B다. raw `/home/mchoi/actual-support-candidate06-als-profile-20261014`, 추출 `experiments/actual-workload-support-20261014/candidate06-als-profile.{json,txt}`. 진단용 짧은 cutoff이며 기존300초 timeout의 대체/성공 근거가 아니다.
- **보고서 독립 review:** PARFOR 관측/추론 구분, 반례 산술, exact cost 의미, reproduce 경계에 substantive 오류 없음. P1 region78과 GMM-init PARFOR의 직접 매핑 receipt가 보고서에 없다는 추적성 지적을 반영하여 관측 오류와 소스 호출 확인을 분리했다. 앞선 세션 메모의 mapping 표현과 달리 이 보고서는 직접 매핑을 입증했다고 주장하지 않는다.

### P2 전용 근본 원인 보고서 — 문서 검증 완료, production 해결 미실시

- **요청/상태:** 사용자 요청에 따라 P2의 문제를 별도 보고서로 작성했다. production 수정·build·worker/RPC·실제 workload 재실행은 하지 않았다.
- **관측:** C26 W1/W5는 `nrow` 입력 `TRead X -1x-1`의 UNKNOWN_SIZE, W3/W7은 canonical ingress index1/5. 최신 candidate06은 W3 index1을 다시 관측했다. 모두 fullcompile=null이며 약2초 wall은 실패까지의 시간이지 성공 시간이 아니다. 300초 timeout이 아니다.
- **원인:** ADR01/02는 floor+remainder-front ranges를 강제하고 noncanonical scope를 거절한다. 실제 P2 ceil-tail과 W3/W7에서1행 경계 차이가 난다. 거절 자체는 spec 준수다. 별도로 sealed metadata에 dummycode first-column global domain이 없으며, current shape/cost는 recode-only라 **exact width를 채워도 cost predicate가 dummycode를 거절**한다.
- **문서/증거:** `docs/FEDPLANNER_P2_PLANNING_BLOCKERS_ROOT_CAUSE_2026-10-14_KO.md`, `docs/evidence/p2-planning-blockers-2026-10-14/`. 기존 support status와 reproduce metadata 계약에 링크와 cost 지원 공백을 추가했다.
- **검증:** 기존5조건의 raw/DML/result/render receipt, 입력 sidecar8개, source/spec 발췌를 보존.31evidence SHA/원본 복사,12current source/spec SHA,15local links PASS. 정적 검증 스크립트는 DML↔receipt geometry, index mismatch, 실패 status, local distinct counts의 불충분성 반례 PASS. dataset row payload/worker는 읽지 않았다. MemoShapes current whole-file만 candidate06과 다르며 이를 manifest에 공개했다. 나머지 발췌Java9개는 candidate06 source hash와 동일하다.
- **해결 설계:** 명시적인 source-map/import 계약 확장, 기존 encoder build/merge의 재사용, narrow identity-bound exact domain fact, multi-return shape 및 비용 primitive 연결을 분리한다. metadata 수집/필요한 numeric→frame 변환/통신/검증 비용은 cold 시간에 포함한다.
- **잔여 이슈:** 실제 global K·수집시간·privacy runtime audit, geometry 지원, dummycode 비용 지원, 이후 binding/scale/split/metadata output coverage는 미검증. 최신 W1/5/7 평가 없음. metadata collector나 spec 변경은 구현하지 않았다.
- **회귀 위험/검출:** ingress 검사 삭제로 다른 ROW map을 DIRECT 취급, partial dictionary를 count-only로 설명, spec-specific preprocessing 시간을 숨김, metadata만으로 전체 성공 주장할 위험. 실제 range/worker identity 회귀, 원 encoder 결과와 M mapping parity, stale/권한 부정 테스트, exact objective/fullcompile receipt로 검출한다.
- **최종 독립 검토:** code-reviewer가 보고서·증거33파일 및12source/spec를 대조해 구체적 오류0, APPROVE.31/31evidence SHA,12/12source SHA,15/15links와 정적 검증 PASS를 별도 확인했다. production build/workload를 수행하지 않은 문서 검증이다.

### Legacy SliceLine 정적 planning과 비용 추정 — 문서화 완료

- **상태/요청:** legacy가 SliceLine을 계획했던 방법과 default/heuristic shape의 역할을 문서화했다. 새 production 수정·빌드·실제 workload 실행 없이 기존 자료를 검증했다.
- **문제 정의:** 현재 Memo의 concrete dimension 비용 gate를 static planning 일반의 불가능성으로 읽으면 legacy의 초기 fullcompile 성공과 모순되는 인상을 준다. 실제 shape의 사전 결정, 비용 모델의 추정, 실행 합법성 증명을 분리해야 한다.
- **관측/소스:** 2026-09-21 final805 W4의 ADULT/COVTYPE 라벨8조건 receipt success/runtime0, RuntimeProgram 단계 완료. DP-global 전체 compile7.871327/7.970540s. 기준 commit에는 RMEMPTY worst-case, default memory, unknown transfer256MiB clamp, ComputeCost unknownaxis floor1 경로가 있다. 당시 JAR은 base+dirty이므로 각 HOP fallback 사용을 직접 입증한 것은 아니다.
- **문서/변경:** `FEDPLANNER_SLICELINE_LEGACY_STATIC_SHAPE_COST_ESTIMATION_2026-10-14_KO.md`, `docs/evidence/sliceline-legacy-static-planning-2026-10-14/`; 기존 SliceLine root-cause 보고서 상단에 보완 링크를 추가했다. 추정 모델에 대한 exact optimum과 실제 데이터 비용의 optimum 차이, UNKNOWN/DEFER·privacy·worker/range 보존 조건을 명시했다.
- **검증:** evidence34파일 SHA, source7발췌의 원문 SHA/라인, 공식8receipts의 log/config 해시, 문서2개의 local34links PASS. production tracked diff SHA 불변. 실제 dataset row payload/worker RPC 없음.
- **독립 review 반영:** measured JAR에서 fallback이 성공을 유발했다고 단정하지 않도록 요약을 제한했다. 보존된 wrapper는 N/D·주소·range placeholder template이며 개별 rendered DML은 이번 증거 묶음에 없음을 명시했다. 데이터 크기는 당시 보고서 기준이며 실제 input binding 전체를 재검증한 것으로 표현하지 않는다.
- **잔여 이슈/위험:** 비용 추정 정책 도입은 미구현이며 기존 cost contract 변경으로 다뤄야 한다. estimate를 EXACT shape 또는 Oracle 합법성의 근거로 쓰는 회귀가 핵심 위험이다. proof/estimate 격리, identity/context 회귀, declared objective audit, 실제 fullcompile 및 별도 runtime 결과 검증으로 탐지해야 한다. general PARFOR는 별도 미지원 과제다. 새 commit/push 없음.

### P2 보고서 후속 구현 — geometry·encoder 완료, proof/실제 검증 진행 중

- **요청/상태:** 사용자가 보고서대로 진행을 요청했다. 기존 미커밋 workload 수정은 보존하고 P2 지원을 구현 중이다. 아직 actual P2 성공이나 10초 달성을 주장하지 않는다.
- **Baseline:** `experiments/p2-support-20261014/pre-p2-working-tree.patch`, `pre-p2-source-sha256.json`으로 이전 상태를 보존했다. inherited KMeans 테스트의 잘못된 `occurrenceContext` API를 수정한 뒤 Maven test-compile PASS. 기존 63개 집중 테스트 중 fixture의 compiler hint 가정 1개 실패를 test-only로 바로잡았고, 해당 CostShapeProjection 15개 PASS. 이는 P2 기능 성공 증거가 아니다.
- **Geometry 해결:** ADR01/02를 명시적으로 확장하는 `FEDPLANNER_P2_SOURCE_GEOMETRY_EXTENSION_2026-10-14_KO.md`를 작성했다. 요청별 immutable axisLength→cuts profile, POOLv2/v1 호환, all-source 충돌 검증, Oracle geometry 결합, indexing canonical effect authority 재사용을 구현했다. private overlay 35개 PASS. reshard·hidden collect·입력 range 변경 없음.
- **Encoder 해결:** existing worker build/logical-range merge/SORT/getMetaData/initMetaData를 build-only helper로 추출했다. apply는 호출하지 않는다. 원 runtime non-equiheight 경로도 같은 helper를 사용한다. 신규3개 및 privacy/placement 포함89개 private overlay 테스트 PASS. 반환 record의 encoder/frame은 mutable이므로 deep immutable proof로 사용하지 않는다.
- **설계 단순화:** persistent artifact/cache 대신 explicit inline metadata preparation을 선택했다. 별도 planning RPC opt-in + 기존 release 정책을 요구한다. static sealed input lifetime을 명시하며 `:ro` 또는 self-declared SHA로 snapshot isolation을 주장하지 않는다. `FEDPLANNER_P2_METADATA_ASSISTED_COMPILATION_CONTRACT_2026-10-14_KO.md` 참조.
- **진행 작업:** 모든 reachable context의 동일 source 증명, immutable owner proof, multi-return shape와 native cost 연결, preparation-owned read/cast/build/cleanup, 실제 Docker worker lane 구현 중.
- **새 발견:** 기존 FED matrix→frame cast가 FRAME 타입 `newMap`을 만들고도 원 MATRIX map을 output에 설정한다. 좁은 runtime map 수정과 값/schema/range 회귀가 필요하다. 실패 응답을 metadata 성공으로 덮지 않는다.
- **잔여/회귀 위험:** partial dictionary 노출을 count-only로 오해, context/clone identity 혼동, preparation 시간 누락, 실패/부분 cleanup, runtime build 비용 임의 차감, 이후 scale/split 미지원. negative tests·전체 regression·실제 W1/3/5/7 fullcompile 및 metadata/time receipt로 검증한다. 공유 통합 build 및 actual 실행은 아직 대기 중이며 새 commit/push는 하지 않았다.


## SliceLine 중심 추가 metadata 요구사항 문서화

- **상태:** 문서화 완료. metadata 수집·production 구현·새 workload 실행·commit/push는 수행하지 않았다.
- **문제 정의/증상:** 기존 rows/cols/nnz만으로는 SliceLine의 category code 의미·one-hot 폭·X/e 행 대응을 증명할 수 없고, 추가 통계와 본 실행의 경계가 불분명할 수 있다.
- **원인:** encoding 계약·열별 최대 코드·입력 identity·통계 정확성 수준이 별도로 필요하다. distinct count와 최대 코드는 일반적으로 다르며 후속 후보 크기는 입력 통계만으로 확정되지 않는다.
- **해결 요약:** 최소 metadata 4종, 선택적인 category/error 통계, 기존 전처리 fdom 재사용, EXACT/PROVEN_BOUND/ESTIMATE 구분, global/local·privacy·수집 비용·검증 항목을 문서화했다. 진행 중인 P2 inline preparation 계약은 변경하지 않았다.
- **수정 파일:** `docs/FEDPLANNER_ADDITIONAL_METADATA_REQUIREMENTS_2026-10-14_KO.md`, 이 세션 문서의 본 항목.
- **의사결정 근거:** Oracle/runtime 규칙은 수정하지 않고, 증명된 입력 사실과 비용 추정의 역할을 구분하는 metadata 요구사항만 정리했다.
- **검증:** SliceLine core·ADULT/COVTYPE 전처리·보존 sidecar·MemoSources 및 P2 준비 계약과 대조했다. 문서의 로컬 링크 존재, 코드 fence 짝, 변경 문서 whitespace를 확인했다. 코드 테스트와 성능 측정은 문서-only 범위이므로 실행하지 않았다.
- **잔여 이슈:** metadata 보존/reader, source identity 검증, bound/비용 소비 경로, 실제 ADULT/COVTYPE planning/fullcompile 및 runtime 동등성 검증은 후속 작업이다.
- **잠재 회귀 위험/감지:** stale 통계를 EXACT로 수용, local distinct 합산, 추정 range 사용, 집계의 무단 공개, 수집 시간 누락을 금지한다. 문서 §9의 negative·통합 검증으로 감지한다.

### 현재 COFEE main 게시 체크포인트 — 통합 회귀 및 게시 경로 검증

- **상태/요청:** 사용자의 현재 작업 커밋·main push 요청에 따라 누적 source/test/scripts/docs를 체크포인트로 묶는다. 지원 범위와 실제 측정 공백은 `FEDPLANNER_CURRENT_MAIN_CHECKPOINT_2026-10-14_KO.md`에 명시한다. 앞선 항목의 “미커밋/구현 중/새 push 없음”은 각 작업 당시 상태다.
- **문제 정의/증상:** 작업 clone의 `origin`은 실제 GitHub가 아닌 `/home/mchoi/fedplanner-memo-20261013` 로컬 저장소이고, 그 저장소에는 별도의 미커밋 작업이 있다. Python 전체 테스트도 red여서 신규 회귀인지 분리해야 했다. 게시 전 리뷰에서는 metadata receipt stderr 누락, health 증거 수집 오류 뒤 성공 판정, container 시작 전 UUID namespace 정리 누락을 발견했다.
- **원인:** clone의 remote 이름과 실제 게시 endpoint가 다르다. Python은 외부 sealed evaluation fixture/manifest/경로에 의존한다. 새 harness는 성공 stdout 파싱과 시작 이후 cleanup만 고려하여 stderr 및 실패 lifecycle 경계를 놓쳤다.
- **해결 요약:** 실제 GitHub main SHA가 부모 `b95989bdddd7`과 일치함을 확인했고 로컬 원본을 변경하지 않는 direct-URL fast-forward 게시 경로를 선택했다. 독립 detached baseline과 Python 실패 ID/traceback을 대조했다. harness는 stdout+stderr 통합 검증, 필수 health/resource 증거 실패 시 실패 판정, 시작 여부와 무관한 정확한 요청 소유 namespace cleanup으로 좁게 수정한다. 테스트로 실패를 먼저 재현한 후 수정했다. 저장/검증 log 문자열의 개행 차이도 후속 회귀로 고쳤고 최종 독립 review APPROVE를 받았다.
- **의사결정 근거/제약:** Oracle/privacy/feasibility/cost 규칙을 완화하지 않는다. 수정은 실행 도구의 실제 증거와 완료 판정을 일치시키는 것이다. 다른 요청의 container/path, 원본 repository 작업 트리, external fixture를 변경하지 않는다. force push하지 않는다.
- **수정 파일:** `scripts/fedplanner/run_memo_metadata_planning.py`, 해당 Python 회귀 테스트, 최신 체크포인트/상태/metadata 계약 문서, 이 세션 기록. 게시 묶음에는 앞서 구현한 Java와 관련 보고서도 포함한다.
- **검증:** Maven test-compile PASS, Java **114 classes / 779 tests PASS**. 최초 Python 현재670개 vs 부모 baseline661개는 **동일한 2 failures+16 errors, 1 skipped**였고 저장소 경로만 정규화한 traceback도 같았다. 신규 harness9개는 PASS. 리뷰 후 최종 harness **15 tests PASS**, Python 전체 **676 tests / 동일 기존18개 실패**를 재확인했다. 독립 최종 review APPROVE. 실행 인자·원본 로그는 `docs/evidence/main-checkpoint-2026-10-14/` 및 체크포인트 §5에 보존한다.
- **잔여 이슈:** 실제 P2 Docker workers1/3/5/7 fullcompile/시간/runtime 동등성은 아직 검증하지 않았다. 현재 새 lane은 so007/cpuset/netem 조건이라 기존 local CPU-quota no-RPC lane과 동일하지 않다. 입력 column statistics의 저장/reader는 미구현이며 exact K 수집은 기본 OFF 대안이다. 일반 PARFOR·SliceLine 동적 중간 shape·ALS 및 candidate06 GLM under10 회귀는 해결 완료로 표시하지 않는다. 기존 Python18개 실패는 남아 있다.
- **잠재 회귀 위험/감지:** opt-in metadata RPC의 정보 공개 범위, stale input identity, context 혼합, cleanup 실패를 계속 검사해야 한다. 이번 publication은 작은 fixture 성능으로 actual 완료를 주장하지 않으며, 실제 workload acceptance는 별도 조건별1회 receipt와 runtime 결과 검증으로 수행해야 한다.

### 입력 column metadata 최초 생성·보관 — 완료 (중간 shape/cost 소비는 후속)

- **문제 정의/증상:** 기존 `.mtd`에는 shape/format/privacy만 있고 SliceLine X의 exact fdom, encoding/결측 의미, X/e row alignment 및 producer/input revision이 없다. Planner 중 payload scan으로 보충하면 실행 전 planning과 dataset 준비 경계가 무너진다.
- **환경/기준:** 실제 GitHub engine `origin/main=78951e9796a53d52038389ef9a4f89ff77b1f56e`, evaluation `origin/main=66b9383acfcf5a3ebe901032b7149ddf0e4e5c3b`에서 각각 독립 `feature/input-column-metadata-20261014` worktree를 생성했다. 다른 작업 트리의 미커밋 optimizer/P2 변경은 포함하지 않았다.
- **원인:** 전처리는 이미 fdom을 계산하지만 보존하지 않았고, MetaDataAll에는 별도 typed input facts extension/reader가 없었다. exact source fact와 후속 shape estimate 사이의 경계를 먼저 만들어야 한다.
- **해결 요약:** `cofee_input_metadata` v1 typed immutable schema, MTD-only parser, local mirror reader, MemoSources original-owner별 snapshot/diagnostics를 추가했다. endpoint/path/range/type/privacy/base metadata/global shard agreement를 검증한다. exact 관측 최대와 보장 상한을 분리하고, 부동소수 JSON token의 반올림을 exact 정수로 받아들이지 않는다. ADULT/COVTYPE 최초 전처리는 기존 `colMaxs(X)` 결과를 CSV로 보존하고, 별도 opt-in setup producer가 새 sidecar mirror와 receipt를 만든다.
- **사용자 결정:** 기존 실제 dataset을 재스캔하지 않는다. 최초 생성·세팅 attempt에서 저장한다. 따라서 이전 sealed dataset에 metadata가 자동 생성된 것으로 보고하지 않는다.
- **의사결정 근거/제약:** Oracle/runtime/후보군/비용식을 변경하지 않는다. `sysds.privacy.allowInputColumnMetadata=true`와 artifact release 선언 모두 필요하며 PRIVATE_AGGREGATE 자체를 공개 권한으로 보지 않는다. bounds/통계는 HOP exact dimensions나 feasibility/privacy/worker binding 근거로 승격하지 않는다. 새 reader는 추가 payload read/RPC가 없다. 기존 source privacy resolver의 RPC 동작까지 제거한 것은 아니다.
- **수정 파일:** `runtime/meta/InputColumnMetadata.java`, `runtime/meta/MetaDataAll.java`, `parser/DataExpression.java`, `hops/fedplanner/memo/MemoSources.java`, 신규 parser/source 테스트 2개. Evaluation producer·초기 DML·setup 문서·manifest/provenance는 별도 evaluation worktree에 있다.
- **검증:** Java 신규15+관련기존20 = **35 distinct tests PASS**. Python 생성 artifact→Java local reader→MemoSources cross-language smoke PASS (matrix payload 없음, exact max100와 bound1000 분리, privacy/HOP shape 불변). 실제 성능/학습 실행은 하지 않았다. [구현 계약·재현 명령 및 증거](FEDPLANNER_INPUT_COLUMN_METADATA_IMPLEMENTATION_2026-10-14_KO.md).
- **검증 중 수정:** JSON4J checked accessor 컴파일 오류는 `opt`+명시적 타입 검증으로 수정했다. parser 테스트가 초기화 안 된 MetaDataAll을 사용한 NPE는 실제 metadata를 전달하도록 고쳤다. 최종 검증은 수정 후 순차 실행했다. cross-language fixture는 2×1이며 초기 smoke의 4×1 가정은 검사 코드 오류여서 수정 후 재실행했다.
- **잔여 이슈:** 실제 dataset의 신규 생성/배포는 미실행. fdom export는 자동이지만 partition 이후 packaging/install은 명시적 opt-in setup 단계다. remote-only sidecar와 같은 path를 공유하는 서로 다른 worker는 이 path-local mirror 계약으로 표현할 수 없다. X/e identity는 trusted producer assertion이며 payload 외부 변경을 compile마다 검출하지 않는다. dynamic 중간 shape/bound/estimate propagation 및 SliceLine fullcompile/runtime 지원은 후속이다.
- **잠재 회귀 위험/감지:** 잘못된 bound의 EXACT 승격, shard 혼합, stale revision, release 무단 허용을 parser/source negative 테스트로 감지한다. 이후 통계를 cache/비용/shape에 소비하면 revision/facts identity를 cache key에 반영하고 별도 soundness·실제 workload 검증을 추가해야 한다. 이번에는 Git commit/push 및 기존 dirty workspace 변경을 하지 않았다.

### 입력 column metadata main 게시 — 후속 요청의 게시 전 검증

- **문제 정의/상태:** 사용자가 구현 완료된 두 저장소의 변경을 `origin/main`에 게시하도록 요청했다. 앞선 “commit/push하지 않음”은 구현 완료 당시의 상태이며 이번 요청으로 게시 범위를 확정한다.
- **원인/주의점:** worktree의 feature branch와 원격 main을 혼동하면 다른 작업 트리 변경이나 관련 없는 이력을 포함할 수 있다. evaluation manifest 재생성 diff에는 기존 `source_commit` 7개와 `packaging_note` 1개가 의도치 않게 빠져 있었다.
- **해결/변경 파일:** 실제 GitHub origin URL과 fetch 후 두 main SHA가 구현 부모와 같음을 확인했다. metadata source/test/docs/evidence만 선택해 커밋한다. `cofee-evaluation/source-manifest.json`의 기존 출처 필드 8개는 원본 main 값으로 복원했다. 원본 dirty workspace 및 기존 sealed artifact는 변경하지 않는다.
- **검증:** 게시 직전 Java metadata/private·aggregate·UNKNOWN 및 release 회귀 **26 tests PASS**, evaluation producer/package/provenance **12 tests PASS**. `py_compile`, native `SHA256SUMS`, `git diff --check` PASS. 기존 구현 시의 Java35 및 cross-language smoke 증거는 그대로 보존한다. 게시 작업 때문에 workload/데이터 스캔을 추가하지 않았다.
- **의사결정 근거:** 사용자 요청 범위인 metadata 구현만 일반 fast-forward push로 게시하며 force push하지 않는다. 실제 원격 main SHA와 local commit SHA 일치는 push 후 별도로 확인한다.
- **잔여 이슈/회귀 위험:** 앞선 metadata 계약의 원격 mirror·초기 setup opt-in·동적 shape 미해결 범위는 동일하다. 두 repository push는 하나의 원자적 transaction이 아니므로 각각의 결과를 검증한다. 전체 workload/runtime 성공이나 전체 test suite PASS를 새로 주장하지 않는다.


### Tree DP + conflict resolution with certified bounds — 진행중

- **문제 정의/범위:** 사용자 요청에 따라 원격 `origin/main` `b8623e3481`에서 새 worktree `/home/mchoi/cofee-tree-dp-20261014` 및 `feature/memo-tree-dp-certified-20261014` 브랜치를 만들었다. 기존 exact solver의 선택/탐색 동작과 후보군을 유지하고 독립 Global/Local 알고리즘을 추가한다.
- **원인/설계:** HOP DAG의 공유 producer·movement와 함수/분기/루프 경계는 단순 트리 비용 합으로 다룰 수 없다. 기존 MemoProgramModel의 변수/제약/cost ownership을 그대로 사용해 forest relaxation + conflict branching으로 인증한다. 단일 movement factor를 중복 계산하지 않는다.
- **해결 진행:** 새 MemoTreeDpOptimizer; read-only factor accessor; 별도 `sysds.fedplanner.memo.algorithm=TREE_DP_GLOBAL|TREE_DP_LOCAL` 선택; 원래 모델 비용/합법성 재검증 후 같은 reconstruction 경로를 사용한다. 기본 EXACT 경로는 유지한다.
- **적용 원칙:** 사용자 요구가 기존 DP 우선·public fixture 생략 지침보다 우선한다. comprehensive exhaustive correctness tests에는 public/negative/unknown 사례도 포함한다. 시간/반복 수만으로 Local 성공 처리하지 않는다. 자원 한도는 명시적 실패 상태이며 성공은 bound certificate로만 결정한다.
- **검증 계획:** 독립 brute-force 최적값, 모든 LB/UB 기록의 포함관계, 정확한 5% 경계, UNKNOWN/coverage/resource 실패, 함수/분기/루프 모델, 동일 Docker 환경의 synthetic 및 sealed 실제 ML training 프로그램 비교. Docker benchmark는 `run_LAN_docker.sh --memo-tree-dp-benchmark` 새 경로로 실행한다.
- **발견한 실험 한계:** 기존 Memo matrix는 network-none compile-only이며 실제 training 실행을 증명하지 않는다. fullcompile과 runtime 결과를 분리해 보고한다. Legacy Regional과 다른 모델이면 objective 동등성을 주장하지 않는다.
- **잔여 이슈/위험:** 구현/검증 진행중. 기존 high-arity cost/check의 완화가 느슨하면 탐색량이 증가할 수 있다. exact binary64 primitive 합은 BigDecimal로 비교하며 epsilon 경계·UNKNOWN 검사를 독립 테스트로 확인한다.
