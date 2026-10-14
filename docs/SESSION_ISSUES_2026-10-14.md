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
