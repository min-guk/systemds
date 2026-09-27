# Session issues — 2026-09-27

## 최종 정리 상태 — 성능 목표 보류, 검증된 변경 커밋/공유

- **현재 지시:** 사용자가 추가 최적화를 중단하고 정리·커밋·`origin/main` 푸시를 요청했다. 성능 목표는 **paused이며 달성/완료가 아니다**. 아래 과거 진행 기록의 재개·실험 예정 문구는 이 지시로 대체된다.
- **완료 범위:** r8 stable-wave 및 committed-inventory 수명 변경의 회귀·mutation 검증과 독립 검토가 완료됐다. 아래 r8 최초 계획/진단 기록은 시간순 증거로 보존한다.
- **미완료 범위:** r8 성능 실측, 동일 최종 JAR의 LAN W1/W3/W5/W7 × 14개 조건(56개) 및 14개 planning-only consumer 검증. 최신 r7 OFF logreg/GLM/P1은 여전히 20초를 초과한다.
- **게시 절차:** 원격 `main` 변경을 일반 병합하고 최종 합성 소스의 clean 회귀/package를 검증한다. `.omx`, 로컬 진단·실험·생성 산출물은 커밋하지 않는다. 테스트/푸시 결과는 별도 최종 체크포인트 문서에 기록한다.
- **의사결정 근거:** 새로운 privacy 완화, 후보 제한, runtime fallback 또는 추가 성능 실험 없이 현재 검증 가능한 변경을 공유한다.

## origin/main 병합 검증 — 테스트 격차를 숨기지 않는 체크포인트

- **문제/환경:** 로컬 28개 커밋과 원격 `main` 13개 커밋이 분기되어 일반 merge를 수행했다. Native/Builder와 관련 테스트·날짜별 문서가 겹쳤다. 과거 증거/문서를 보존하고 force push나 공유 이력 재작성을 사용하지 않는다.
- **해결:** upstream direct-publication cycle 방지를 로컬 단일 `GenerationRoot` 경로로 통합했다. primitive API는 정확한 current fact/emission을 찾는 fail-closed adapter이며, query root가 자기 executable input receipt가 되는 것을 차단한다. 생성/검증 cache namespace, fresh query state, inventory commit 무효화와 로컬 closure 최적화는 유지한다. 삭제된 구형 privacy inner loop는 복구하지 않고 upstream loop-progress 회귀를 보존한다.
- **수정 파일:** `NativePlacementContinuity.java`, `NeutralPlacementGraphBuilder.java`, 두 continuity/composition 테스트; `PlanningNativeModelCapture`의 upstream V2 inventory와 로컬 CAPTURE/Optional evidence를 모두 보존했다.
- **첫 clean 검증:** 48개 클래스, 468건, 실패 2/오류 2/기존 skip 6. Native70건(기존 skip1), Physical7건, Inventory4건, Exact capture14건, Planning capture8건에는 실패/오류가 없다.
- **테스트 자체 OOM 해결:** recursive direct-publication 회귀는 두 분석과 fingerprint 일치를 통과한 뒤 bounded-shadow 전용 legal-assignment 전수 나열에서 OOM이었다. 전체 graph definition(후보 domain/constraint/action/obligation) signature와 exact candidate facts를 비교하도록 **테스트만** 수정했다. heap 확대/후보 cap/production 변경은 없다. 수정 후 해당 회귀 1/1 PASS.
- **기존 미해결 2건:** `OracleFacadeTest.binaryFullMatrixWithLocalMatrixDoesNotRequireEncodedWidth`의 FED/CP 기대 불일치, `LoopSeedReplayWideningTest.protectedAlsLoopRetainsWidenedNativeSourceAndAllReachingWriters`의 비정본 alsCG PRIVATE_AGGREGATE placement 오류. skip 추가나 privacy 완화 없이 실패를 유지/공개한다.
- **새로 드러난 비교 격차:** `CurrentPePhysicalSetCorrespondenceTest` B-21은 P/E 집합과 multiplicity 상호 비교를 통과하지만, 원격 baseline의 raw proof192/physical36과 현재 raw160/physical30이 다르다. 처음에는 raw 표현만 달라졌을 가능성을 검토했으나 physical36 assertion이 실패하여 그 가정을 철회했다. **기대값 192/36을 그대로 복원했으며 이 격차는 미해결이다.** 합법 계획 감소인지 identity 변화인지 입증하지 않았고 harmless normalization이라고 주장하지 않는다.
- **Python 환경 구분:** 최초 잘못된 하위 cwd 호출의 import 오류는 root cwd에서 재검증했다. upstream38모듈362건(기존skip1), evaluation14모듈122건 통과. library-resolution 한 모듈은 archived receipt가 정확한 원래 checkout 절대경로를 요구하므로 이 worktree에서는 fail-closed이다. 동일 source hash와 정확한 origin/main checkout의 해당 5/5 PASS를 별도 확인했으며 receipt/hash/path 제약을 고치지 않았다.
- **잔여/위험:** 미해결 3건이 있으므로 전체 테스트 green, P/E 전체 동등성 또는 성능 수용 완료로 게시하지 않는다. 사용자 요청에 따라 추가 최적화를 재개하지 않고 이 검증 격차를 포함한 작업 체크포인트를 공유한다. 최종 clean 재검증/package/probe 수치는 체크포인트 문서에 기록한다.
- **의사결정 근거:** 기존 privacy/runtime/exact proof 계약을 보존하며 현재 상태를 정직하게 게시한다. 검증을 통과시키기 위해 정책·후보·test expectation을 임의로 축소하지 않는다.

## All14 r8 — 동일 inventory 인덱스 수명 / exact stable-wave 감지 (정확성 검증 완료, 성능 미측정)

- **증상/근거:** r7 OFF logreg28.0627/GLM32.6594/P1 28.7731초로20초 미달. GLM의 physical normalize 아래 전체 WorkerPoolAnchorResolver 생성이386 CPU samples/4.342GB이며, P1 direct3748waves 중3539stable이다. 후보나 privacy를 축소하지 않고 중복 인덱스 생성만 줄인다.
- **첫 변경/검증:** `changedCandidateOccurrences`에서 동일 list 또는 모든 순서별 parent **identity**와 전체 fact equality가 같은 경우만 empty를 반환한다. 나머지는 기존 IdentityHashMap grouping을 그대로 쓴다. old production21tests 중 의도한 traversal-count1건만 RED(4 vs0), 구현 후4suites50tests 실패0/오류0/skip0. 독립 CLEAR=`D/direct-wave-equality-independent-review.json`(53752892...). Builder SHA=ccec10ba..., test95bb52b8.... 아직 timing 개선 근거는 없다.
- **발견/회피:** 최초 `before.equals(after)` 단독 제안은 CompiledHopKey record의 structural equality와 owner map identity 계약이 달라 안전하지 않았다. root가 이를 발견하여 parent identity까지 검증하도록 계획을 바꾸고 value-equal/distinct-owner 두 identity 모두 dirty인 회귀를 추가했다. 원 분석은 superseded 경고 및 archive로 보존했다. null 입력을 새 성공으로 바꾸지 않는다.
- **다음 변경의 경계:** 물리 worklist 한 번 안에서 committed nodes/facts가 같은 구간만 proof inventory를 공유한다. 실제 nodes/keys/facts commit 직후 무조건 폐기하고 raw owner는 proof authority에 넣지 않는다. Worker query memo는 매 이전 owner 경계마다 초기화하고 Native는 immutable StructuralContext만 공유하는 fresh query를 사용한다. exact action/source/witness, eligibility, privacy, CFG all-writers와 예외를 보존한다. plan review CLEAR지만 actual-loop genuine RED가 먼저 필요하여 아직 이 production 변경은 설치하지 않았다.
- **수정 예정/소유권:** root Builder, executor Native+Native/Worker cycle tests, debugger PhysicalGenerationEnvelopeTest. 모든 Java 설치/Maven은 D/local-maven.lock으로 직렬화하며 actual과 겹치지 않는다. 현재 추가 Native는 D staging뿐이다.
- **baseline:** r7 전체7613 source exact-match 확인 후8suites110tests/실패0/오류0/기존skip1. WorkerPool3개 추가25tests PASS. 후자 report copy glob이 stale NativeMixedWorkerPool XML도 복사한 것을 식별해 제외/명시 보존했고 해당5개는 신규증거에서 제외했다.
- **physical 구현/현재 검증:** unchanged 실제 worklist fixture에서 old6tests 중1건 intended RED(동일구간 edge scan2 vs1)를 얻은 뒤 holder+Native fresh factory를 원자 설치했다. Stable-case는1scan으로 GREEN이며 constructor-time snapshot/두-owner cold parity/eligibility와 duplicate-edge 예외/no-self-authority4tests PASS다. Native/Worker query lifecycle은80tests 실패0/오류0/기존skip1이며 모든 query map/counter의 실제 populated→fresh empty를 검증했다. 실수로 기존 memo fixture에 들어간2privacy lines는 정확히 원복하고 신규 fixture에만 PA를 부여했다.
- **새 테스트 증거 결함/진행중:** old poolA→C commit fixture의2scan assertion은 optimized에서1scan으로 실패했다. Holder capture와 lazy resolver construction은 다르므로 첫 owner가 eligible resolver를 만들지 않고 commit하면1scan이 옳을 수 있다. production은 full commit직후 null을 수행하고 독립검토에서 구체적 stale flaw가 없었다. 따라서 기대값을 임의로 낮추지 않고 실제 중간 commit/eligibility를 확인하는 fixture 및 invalidation-line 제거 mutation 검증을 준비한다. 초기 old2scan만으로 commit무효화를 증명했다고 보지 않는다. `D/physical-inventory-green-v1`에 실패를 보존했고 debugger가 Physical test만 맡아 진단한다. r8 freeze/actual은 이 게이트 전 실행하지 않는다.
- **위 증거 결함 해결:** 최종 실제 loop fixture는 FULL 4×2에서 두 2×2 ROW partition으로 첫 owner의 사실이 실제 commit되는지 확인하고, downstream PRESENT ROW와 두 번의 inventory scan을 검증한다. 별도 A→C 무중간-commit control은 한 번의 scan과 현재 pool C authority를 검증한다. 최종 PhysicalGenerationEnvelopeTest 7/7 PASS. 단순히 실패 기대값을 낮춘 것이 아니다.
- **무효화 mutation 검증:** 유일한 post-commit `proofInventory = null`을 제거하면 intended 1건 실패(expected 2 scans / actual 1), 오류 0이다. production 원복 후 PhysicalGenerationEnvelopeTest + MaterializationProofInventoryTest **11/11 PASS**. 원복 source SHA=`6511955cef38fd02c19c03da33e79071a6aa331a15bc0261cf6c59c16c29b9f1`이다.
- **최종 독립 검토:** `D/physical-proof-inventory-independent-review.json` SHA=`f37e97cc1b21bdff6e54edb6088d68afbcb6251cf138583c45d2c48ff64b7487`, CLEAR. 정확성 체크포인트/공유에 대한 판단이며 20초 성능 수용 판정이 아니다. 사용자 중단 지시에 따라 새 frozen r8 측정은 실행하지 않는다.
- **잔여/위험:** commit 무효화 누락, equal-key/identity 혼동, per-owner query memo 누출, eager validation 순서 변화. 실제 loop unchanged/commit counting regression과 changed-pool downstream authority, 독립 cold parity/리뷰로 감지한다. frozen clean package/actual/final56+14는 아직 미완료이다.
- **의사결정 근거:** runtime/oracle/privacy 규칙이나 허용 후보를 바꾸지 않고 정확히 동일한 planner inventory에서 중복된 표현/인덱스 생성만 삭제한다.

## All14 r7 — canonical literal field wrapper 제거 (진행중)

- **증상/근거:** r6 OFF27.623442615초로20초 미달. completed JFR에서 private `CanonicalText.literal`의 임시 wrapper/list/iterator가 sampled1.148GB를 차지한다. proof/key/binding/action field는 원래 String인데 길이를 읽고 다른 rope에 붙이려고 singleton rope로 다시 감쌌다. 이 값은 할당량 상한이며 예상 시간 절감이 아니다.
- **변경:** private `appendFields`에서 String 또는 immutable CanonicalText만 fail-closed로 받아 동일한 UTF-16 길이 prefix/colon/separator와 nonempty piece를 기록한다. literal은 직접 String piece로, 실제 structural child는 기존 identity 그대로 보존한다. 네 helper와 action consumer loop의 불필요한 literal wrapper만 없앤다. constructor/cursor/sort/cache/metrics/authority/runtime/privacy에는 변경이 없다.
- **계획/behavior lock:** 첫 독립 plan review는 comparator sign만으로 byte parity를 증명할 수 없어 CHANGES였다. 실제 rope를 test에서 flatten하여 full UTF-16 text/저장 length와 legacy signature를 대조하고, literal String representation 및 structural child identity를 추가한 수정 계획은 CLEAR다. old r6 production47tests 중1 intended representation RED/오류0이며 exact-byte test는 통과했다. 구현 후 private Object[] empty/null/invalid-type test를 추가해5suites59tests/실패0/오류0/skip0, hash 안정/diffcheck PASS다. missing-method RED나 wall-clock unit gate는 사용하지 않았다.
- **수정 파일/해시:** `PlacementAnalysis.java`=`c233c86925b7a3c095d41c0b81616fc7e318d7a18626ccee4207cafe5504c309`; `CandidateRealizationCanonicalizationTest.java`=`a481badbb9c39939fe6f37f86381bf2918cfc2ad534a1f17d03678a1baf81626`.
- **빌드/검토 완료:** independent postimplementation CLEAR(`4b55a490...`), r6+이2files만 합성한 clean29suites335tests/실패0/오류0/기존skip6, package/full7613 source 전후 hash/primary inventory/JARentry-class 검증 PASS. JAR=`8185213ee04b3d6fb04759e8bd10ccdcaa3364b4ddde28c428d657c56d216aad`, manifest=`ea7b84bedd5a9aeebaca3a2ad96cf8ba3a5103939e8ac461f51d6426842bf7ba`. Native prune의 primitive-array reuse 제안은 예상35–67MB에 그쳐 보류했다.
- **실측/잔여:** fresh LAN W1 OFF logreg **28.062707632초**, GLM **32.659365073초**, P1 **28.773104992초**로 모두20초 미달이다. r6 logreg27.623442615초보다 약0.44초 느리므로 wrapper 삭제만으로 timing 개선을 주장하지 않는다. 별도 completed ON/JFR GLM34.778857666초/P1 28.778355572초를 회수했고, 전 run receipt/resource/netem/exact cleanup/all-host lease를 확인했다. 이제 더 큰 반복 계산을 찾기 위해 두 workload profile을 분석한다.
- **새 profile의 큰 병목:** GLM PHYSICAL_REBUILD8.495초/5.069GB, CLOSURE_REPLAY6.395초/3.769GB, RELOCATION_BINDING5.328초/6.345GB. per-owner `normalizePhysicalGenerationEnvelope` 아래 full `WorkerPoolAnchorResolver` 재구축이386/1846 CPU sample와4.342GB를 차지한다. P1은 PHYSICAL_REBUILD13.184초/10.764GB, CLOSURE_REPLAY8.669초/5.355GB이며 full dependency-component/SCC 인덱스 생성이 크다. P1 direct waves3748 중3539 stable(94.42%)인데 before/after 전체 owner map을 반복 만드는 변화 감지도139/1823 samples다. 각 inclusive 수치는 중첩이므로 합산하지 않는다. 해당 단계 전체가 삭제 가능하다는 주장이 아니다.
- **후속 설계/금지 경계:** physical worklist는 실제 node/fact commit 때 proof inventory가 바뀌므로 무조건 pass 전체 resolver 공유는 안전하지 않다. 동일 inventory 구간의 index 수명과 정확한 commit 무효화, 기존 per-owner query memo/active 경계를 보존하는 방안을 검토한다. 직접 binding의 stable-wave 변화감지는 별도 읽기 전용 분석 중이다. transient signature 최적화는 P1 sample0/GLM minor여서 우선하지 않는다. 아직 새로운 source 변경은 없다. profile summary/hash manifests는 `D/runs/profile-r7-{glm,p1}-w1/`에 보존했다.
- **하네스 이름 거부:** 최초 P1 run ID에 underscore가 남아 initializer regex에서 run directory 생성/원격 action 전에 거부됐다. 기존 logreg/GLM2개 완료 prefix를 원본 backup+hash+completed_row로 재검증하고, 유효한 hyphen 이름으로 **미시도 P1 suffix만** 한 번 실행했다. OFF 재시도/빠른 결과 선택이 아니다. 증거=`D/r7-run-p1-suffix.py`, `r7-candidate-before-p1-name-fix.json`, `r7-candidate.json`.
- **잔여/회귀 위험:** UTF-16 길이(문자 수가 아닌 code unit), empty field0:, literal/structural 구분, action consumer length prefix, 동일 descriptor tie와 최초 authority. exact-byte/length/identity regression, 독립 patch review 및 actual로 감지한다. D의 `canonical-literal-field-plan.md`, `literal-field-{red,green-v1}`, `literal-field-{production,test}.patch` 참고.
- **의사결정 근거:** 후보나 privacy를 줄이지 않고 불필요한 표현 계층만 삭제한다. 모든 최종56+14 gate는 유지한다.

## All14 r6 — 비교기 임시 객체 / 중복 SCC 계산 제거 (진행중)

- **증상/환경:** fixed canonical logreg, LAN W1, metrics OFF에서 r5는 35.187582008초로 목표20초를 넘는다. r5 completed JFR의 comparator cursor/frame 및 eligibility/refinement SCC 중복 계산을 근거로 두 개의 bounded pass를 선택했다. 입력·privacy·후보·resource·netem·cap은 변경하지 않았다.
- **해결 요약:** `PlacementAnalysis`의 rope cursor를 dense node/index stack으로 바꾸고 grouped-union stable sort 한 번 안에서만 비교 context를 재사용한다. Native grounding은 ordered AND-eligibility/ground-path scan을 합치고 **원본 alternative가 하나도 제거되지 않은 경우에만** unchanged refinement Tarjan을 생략한다. 하나라도 제거되면 중복 edge여도 기존 refinement 경로를 유지한다. 모든 state key, 최초 authority, 순서, 원본 viable map 및 dependency-first 재귀를 보존한다.
- **수정 파일:** `PlacementAnalysis.java`, `CandidateRealizationCanonicalizationTest.java`, `NativePlacementContinuity.java`, `NativePlacementGroundingAndSignatureTest.java`. 기존 CTABLE release fix는 그대로 유지한다. r6는 r5에서 우리 소유의 archive된 experimental RED-proposal test2개만 제외한다.
- **behavior lock/검토:** dense baseline42/42→최종43/43; Native old-production RED7건 중 예상 SCC-work-count2건 실패(grounded 결과는 동일)→focused103건 실패0/오류0/기존skip1. deep/shared-prefix UTF-16/reset/reference-clear와 removed redundant edge/seedless/external seed/incomplete AND/order 회귀를 포함한다. 두 독립 postimplementation review 모두 hash-bound CLEAR.
- **격리 빌드 검증:** clean29suites332tests/실패0/오류0/기존skip6, package, 전체7613 source 전후 hash 및 JAR entry/class 동등성 PASS. obsolete CanonicalTextFrame class는 없다. frozen=`/grid/3/cofee-lm-sweep-mchoi-20260914/w1357-all14-space20-r6-build-20260927`; JAR=`45285e915bb4e787555c97b0aafab1002e3fad09f7b29ce48a8722c48780e94f`; manifest=`4f2f53a19db20a5c02719a028e889519038edc5d802066ad8f257e6cadf278b2`.
- **실측:** fresh one-shot OFF **27.623442615초**(r5 35.187582008초), 여전히20초 미달이다. 별도 ON/JFR **36.25801936초**는 진단이며 OFF를 대체하지 않는다. 두 run 모두 exact receipt/resource/netem/cleanup/all-host lease 해제를 확인했고 JFR을 회수했다. 단일 OFF 비교로 latency 분포 개선을 주장하지 않는다.
- **재현/증거:** D의 `r6-run-selected.py`, `r6-run-profile.py`, `r6-candidate.json`, `r6-source-composition.json`, `r6-scoped-regressions.json`, `native-eligibility-scc-independent-review.json`, `dense-cursor-independent-review.json`, `runs/profile-r6-logreg-w1/`. evaluator-r6는 acceptance candidate 미선언으로 FAIL이다.
- **완료 profile 비교:** exact allocation25.256→22.775GB(-9.82%), SCC grounding3.796→3.109초/3.174→2.395GB.94/100 metrics 동일이며 차이는5개 SCC work counters와 signature identity hits뿐이다. candidate/proof/product/publication counts, script/fingerprint 모두 동일하다. grouped cursor allocation은1.296→0.008GB이지만 CPU samples72→78이므로 CPU 개선으로 단정하지 않는다. `D/runs/profile-r6-logreg-w1/r6-vs-r5-profile-summary.md` 및 hash manifest에 보존했다.
- **잔여/잠재 회귀:** 다음 profile-backed bounded pass는 CanonicalText literal wrapper 제거다. cursor reset/공유 suffix의 정확한 위치, incomplete AND를 direct seed가 우회하는지, 제거 edge가 SCC를 분리하는지가 위험 경계다. targeted differential/authority 회귀와 독립검토로 감지한다. SliceLine r5 W1 두 dataset 통과와 별개로 최종 같은 JAR56조건+14 planning-only consumers는 미완료다.
- **의사결정 근거:** 후보 삭제·privacy 완화·cache budget 확대가 아니라 관측된 반복 계산과 단명 객체만 제거한다. D=`/home/mchoi/w1357-diagnostics/all14-searchspace20-20260927`.

## SliceLine 기존 aggregate-release 연결 누락 / r5 합성 검증 (진행중)

- **증상/사용자 증거:** 사용자가 기존 privacy 경감 경로를 지적했다. `FederatedPlannerUtils.derivePrivacyConstraint`의 CTABLE `PRIVATE_AGGREGATE_TO_PUBLIC` 분류와 Builder의 provenance 보존은 이미 살아 있다. 따라서 원본 PA 입력의 privacy를 낮추거나 새 geometry 우회로를 만들 필요가 있다는 기존 가정은 채택하지 않는다.
- **원인:** `ExecPlacementPolicy.supportsForcedLocalFederatedOutput`가 ordinary CTABLE을 누락했다. Builder는 정확한 runtime FED/FOUT cap이 있을 때만 이 predicate를 통해 FED/LOUT sibling을 추가한다. `CtableFEDInstruction.processInstruction`은 explicit LOUT에 GET_VAR/aggregate/cleanup을 이미 구현한다. canonical SliceLine의 여섯 입력 CTABLE은 ctableexpand가 아닌 ordinary 경로다.
- **작은 수정:** matrix CTABLE이며 `!isSequenceRewriteApplicable(true)`일 때 existing forced-local capability를 알린다. privacy transfer/원본 collection/TRead-TWrite/후보 제한/런타임 fallback은 변경하지 않는다. ctableexpand의 row-preserving result는 이번 새 release capability에 포함하지 않는다. production diff는 import 포함4줄이고 독립 production review CLEAR다.
- **회귀/authority 구분:** 수정 전 `sliceline-privacy-relief-ctable-local-red-v4`는 actual PA fixture의 CTABLE/PATP/six-input exact FOUT precondition을 통과한 뒤 missing LOUT만 실패했다. 이후 모든 LOUT clause에 DIRECT binding을 요구하는 추가 assertion은 기존 LOCAL 표현 계약과 충돌했다. LOCAL empty clause는 native-map output witness를 요구하지 않으며 base input authority는 exact rule의 단일 PRESENT ROW(pos1), compiled input edge, ExactPhysicalModel/CandidateSelections의 FOUT input 선택으로 보장한다. relocation product의 추가 DIRECT-constrained LOCAL clause와 empty LOCAL clause는 공존할 수 있다. 모든 clause에 FOUT map proof를 복사하거나 `anyMatch` 하나로 authority를 대체하지 않는다. mixed-clause invariant와 raw PA의 모든 emission이 FED/FOUT임을 test-only로 보강 중이다.
- **Lowering 검증:** root 소유 `FEDInstructionParserCtableTest`는 explicit dimensions의 ordinary scalar/matrix-weight LOUT Lop→FED parser 2건을 추가했다. r4 기반 격리 r5에서 기존 NONE/FOUT 포함4/4 PASS, source SHA `50691cc336636298781f470186cc7cbaea4297d772c256ede1bd7945234a2284`, 독립 patch review CLEAR. runtime output-contract unit6건은 별도 focused 통과했다. 실제 canonical runtime 실행을 뜻하지 않는다.
- **동시 성능 수정:** r4 profile의 union/cursor 병목에 대해 exact encounter-order dedup 후 stable sort하는 bulk union을 적용했다. genuine deterministic RED→41/41 focused GREEN, 독립 review CLEAR. r5는 r4+이 bulk union+minimal CTABLE capability만 합성하며 미완성 ROW/FULL Rulesets/Native geometry 및 unused rix helper를 포함하지 않는다.
- **수정 파일:** `ExecPlacementPolicy.java`, `ExecPlacementPolicyForcedLocalTest.java`, `SliceLineFullChainOracleProjectionTest.java`, `FEDInstructionParserCtableTest.java`; 별도 성능 pass는 `PlacementAnalysis.java`, `CandidateRealizationCanonicalizationTest.java`.
- **격리/실측 검증 완료:** final focused9/9와 parser4/4, hash-bound 독립CLEAR 후 r4+approved6files만 격리 합성했다. **clean**29suites328tests/실패0/오류0/기존skip6, package/full source 전후 hash/JAR entry-class 동등성 PASS. r5 JAR=`78e38387f87026f14e0b90e0ffaad7ad07e93fe75531dabd6eb8a8a2606addb0`, manifest=`20478f6da39da54944570fb950746cccd0c1d058ed6e4bfba8f14c6d88e6d5c4`,7615 source files.
- **실제 canonical LAN W1 OFF:** SliceLine Adult **8.280152433초**, Covtype **8.898616912초**. 원본 privacy·입력·resource·netem·stage를 유지한 채 기존 privacy failure를 넘어 완료했다. logreg는 **35.187582008초**로 여전히20초 미달이다. 별도 ON/JFR **33.291667638초**는 진단일 뿐 더 좋은 OFF 값으로 대체하지 않는다. 세 OFF와 ON 모두 receipt/exact cleanup/all-host lease 해제를 검증했다. W3/5/7, 최종56조건 및14 planning-only consumers 완료를 주장하지 않는다.
- **빌드 증거 주의:** parser-only preview 뒤 `copy2`로 policy source를 합성하면서 source mtime이 baseline class보다 과거여서 Maven incremental이 낡은 bytecode를 사용한 첫 scoped 시도는 INVALID로 보존했다. `javap`에서 CTABLE branch 부재를 확인했고 production/test를 약화하지 않고 `mvn clean test`로 재검증했다. `D/r5-stale-incremental-attempt`는 product RED나 최종검증이 아니다.
- **불필요한 실험 정리:** 성공 경로에 필요 없는 우리 소유 ROW×single-FULL Rulesets/Native geometry·unused rix helper와2개 untracked RED-proposal test를 전체 archive/정확한 hash로 보존했다. r1부재/r2도입을 확인하고 독립 cleanup plan CLEAR 뒤 exact production hunk reversal과 해당2test만 제거했다. post-cleanup23suites188tests/실패0/오류0/기존skip1, source drift/보호파일/hash 검증 PASS다. Root가 primary의 남은7613 source files 모두 r5와 byte-identical이고 차이는 제거한2개 실험 test뿐임을 추가 확인했다. root Native dense-prune/default-list 성능 변경, 기존 dirty 코드, minimal CTABLE fix 및 frozen r5는 보존했다. archive=`D/archive/sliceline-row-full-experimental-precleanup-v1/`.
- **r5 profile 후속:** 같은 canonical logreg script/fingerprint에서100개 metrics 중96개 동일, 나머지는 정렬/비교/signature cache work counters다. 후보·proof·product·merge/publication 수는 모두 동일하고 비교15.905M→3.198M, exact allocation30.907GB→25.256GB다. 아직 OFF35.19초이며 목표완료가 아니다. 다음 bounded pass는 cursor frame/deque 임시객체 제거와 Native의 반복 eligibility/refinement SCC 계산 제거를 별도 behavior-lock/독립 plan review 후 검토한다. 불필요한 typed geometry나 후보 cap을 다시 넣지 않는다.
- **잠재 회귀/감지:** ordinary/expand 구분, strict PRIVATE collection, raw PA CP/FOUT, LOCAL/native-map authority 혼동, bulk 정렬·동일-text exact equality/최초 authority 순서. targeted regression + 독립 review + 동일 조건 actual로 확인한다.
- **의사결정 근거:** privacy 정책 완화가 아니라 runtime이 이미 지원하는 aggregate-result local 경로를 planner capability에 정확히 연결한다. D=`/home/mchoi/w1357-diagnostics/all14-searchspace20-20260927`.

## All14 r4 실측 / 기존 SliceLine privacy 경감 재확인 (진행중)

- **r4 검증:** PriorityQueue k-way union 수정은 genuine RED(128 singleton groups,16,384 comparisons)→focused40/40GREEN 및 독립CLEAR 뒤 r3 source baseline에 해당 Placement/test만 합성했다. 격리 scoped25suites314건/실패0/오류0/기존PUBLIC skip6, package 및 build 전후 full source hash를 검증했다. JAR=`d07a40a86bda2ccc605e9bc31d5905b709ff79187840c9679cb5c3bfbdce584a`; manifest=`9e1cd509d652d535803d6bb5b2c12dc8d89b62c4a6a768daa61c97acc90078ec`.
- **실측:** 같은 canonical logregW1 OFF **36.880287158초**, 별도 ON/JFR **42.332561325초**. r3의108.07초 회귀는 해소됐으나20초 미달이다. 두 run 모두 receipt/전호스트 netem/정확한 container cleanup/lease 해제를 검증했다. 완료된 r4 JFR로 다음 병목을 분석한다. r3 부분 JFR을 전체 비교값으로 쓰지 않는다.
- **사용자 최신 증거:** “sliceline은 이전에 privacy 경감해주는 게 있었을텐데”. 이에 따라 신규 CTABLE geometry 확장보다 기존 release 경로를 우선 점검한다. 현재 `FederatedPlannerUtils.derivePrivacyConstraint`는 AggUnary·CTABLE 등에 `PRIVATE_AGGREGATE_TO_PUBLIC`을 부여하고 Builder는 그 provenance를 유지하며 ExecPlacementPolicy는 released result의 local 후보를 허용한다. 원본/집계 전 중간값은 별개로 보호한다.
- **현재 한계:** 실제 failure hop1566은 `colMaxs(X2*e)`의 내부 곱이며 PA e를 포함해 PA인 것이 곧 propagation bug라는 뜻은 아니다. X2의 CTABLE release와 함수/recompile 경계, local-X2/FULL-e 후보 및 이전 fusion-aware 경감 경로를 구분해 확인 중이다. 기존 release가 없다고 가정하거나 전체입력을PUBLIC으로 바꾸지 않는다.
- **새 fixture 주의:** Oracle-prefix inventory의 첫 import compile오류와 PA raw-PWrite failure는 보존한다. PUBLIC로 바꾼 일시 진단은 기존규칙/형상 inventory에만 한정하며 canonical PA 성공이나 acceptance 증거가 아니다. PUBLIC-only active test를 남기지 않고 PA+집계sink+실제foffb lineage로 회귀를 바로잡는다. Java 파일 설치까지 공유flock으로 보호해 peer testCompile 충돌을 방지한다.
- **결정/위험:** 현재까지 SliceLine production partial/unused strict rix helper는 r4에 포함하지 않았다. privacy 완화·원본 collection·후보축소 없이 기존계약 적용 여부부터 확인한다. acceptance는 미선언이며 최종56+14 planning-consumer 조건은 미완료다.

## All14 r3 실측 회귀 — canonical head 선택 복잡도 (진행중)

- **증상:** 명시적으로 합성·동결한 r3(Gate A/B + Native default-list 공유, 미완성 SliceLine 제외)는 scoped 313건/실패0/오류0/기존 PUBLIC skip6 및 package를 통과했지만 LAN W1 logreg OFF가 **108.066134648초**로 r2 40.241289715초보다 악화됐다. 이 버전은 acceptance로 채택하지 않는다.
- **원인/증거:** 같은 canonical script/fingerprint의 별도 ON/JFR은 고정120초에 종료되었고 최종 receipt가 없다. 따라서 ON 전체 시간/할당량 비교는 불가하다. 다만 부분 프로파일 8,955 main CPU sample 중 `mergeRealizationGroup`이86.2%, `compareCanonicalText`가83.2%를 차지한다. Gate A가 각 text bucket마다 모든 G개 group head를 두 번 훑어 distinct singleton G개일 때 O(G²) 비교·cursor 할당을 만든 것이 직접 관측됐다. `D/runs/profile-r3-logreg-w1/r3-partial-profile-summary.md`에 중첩 비율·부분 경계를 명시했다.
- **해결 계획:** `PlacementAnalysis.java`의 반복 head scan만 JDK PriorityQueue의 one-head-per-group으로 교체한다. 정확한 canonical text 순서, 같은 text의 새 head까지 포함한 bucket, exact equality dedup, 최초 group/position authority, 기존 sequence reuse/validation/metrics는 유지한다. production 수정 전 deterministic 비교 횟수 회귀 RED와 별도 critic plan review, 이후 scoped test 및 새 frozen candidate OFF를 수행한다. r3는 보존하며 재시도/좋은 결과 선택은 하지 않는다.
- **현재 검증/운영:** r3 OFF와 ON 모두 정확한 container name/ID 삭제 및 all-host lease 해제를 검증했다. ON return124는 timeout이고 cleanup 실패로 해석하지 않는다. actual은 idle; 모든 Maven은 `D/local-maven.lock`으로 직렬화한다. 기존 canonical/CTABLE 동시 Maven 로그는 INVALID로 보존한다.
- **SliceLine 잔여 문제:** strict real-HOP rix maximum helper는 아직 unused다. v8 clean focused20건 중19건 PASS/의도한 CTABLE-chain1건 assertion RED/오류0이다. 새 physical observation은 기존 ROW/COL interval identity를 지우면 안 되며, active layout requirement는 선택된 clause/anchor에서만 증명해야 한다. occurrence-wide node anchor, synthetic-null grounding, external query seed로 geometry를 빌리면 안 된다. 실제 hop833의 FULL/FULL domain은 surviving support 증거가 아니며 현재 trace에서는 supported clause가 없다. runtime-faithful colMax→local/FULL binary→FULL/FULL binary 경로의 exact rectangle/선택 source 관계를 별도 확인 중이며 CTABLE production은 inactive다.
- **잔여/잠재 회귀:** heap tie의 authority 순서, lazy suffix 비교, 중간 리스트 보존량, FULL max-dimension 출력 geometry 및 AND/OR/writer/cycle 격리. 모든 legal candidate와 privacy를 유지하며 profile/test/독립 검토로 감지한다. 최종 동일 JAR56 cells+14 planning-only consumers는 여전히 미실행/미달이다.
- **의사결정 근거:** runtime 후보 축소나 설정 완화가 아니라 관측된 중복 비교의 알고리즘 복잡도만 수정한다. D=`/home/mchoi/w1357-diagnostics/all14-searchspace20-20260927`.

## All14 20초 후속 — group-first union / CTABLE authority review (진행중)

- **증상/현재 결과:** r2 canonical LAN W1 logreg OFF40.241289715초, ON45.250486727초로 20초 미달. ON allocation33.745GB이고 r1보다 약467MB 적으나 grounding 감소를 direct/relocation canonical construction 증가가 상쇄했다. r2 post-GC 최대/최종1.403GB는 retention 개선 증거이지 목표 달성이 아니다.
- **변경 범위:** Gate A는 exact realization key로 먼저 묶어 singleton clause traversal을 없애고 repeated group만 canonical union한다. eager trusted-list descriptor backfill을 삭제한다. 후보/authority/정렬 의미는 유지한다. 독립 critic의 invariant/metrics/동일 text bucket 검토 후 회귀를 보강했다. focused36건 통과, 독립 post-review와 broader 검증은 진행중.
- **다음 bounded pass:** Native topology의 이미 불변인 default alternative 객체뿐 아니라 list를 공유한다. generated/synthetic/template/query-pinned 경로는 그대로 유지한다. 별도 계획 `native-default-list-reuse-plan.md`, 수정 전 회귀5건 중 enclosing-list identity 기대1건만 assertion RED/오류0이다. 아직 이 pass의 production 변경/성능 증거는 없다.
- **SliceLine 잔여 결함:** ROW×FULL paired rule/selected proof 부분만 통과했고 CTABLE lineage는 미해결이다. 실제6-input `table(...,dims,flag)`는 ctableexpand가 아니라 ordinary reversed CTABLE이다. count1은 range 하나를 증명할 뿐 그 끝이 N인지는 증명하지 못한다. 런타임 `getOutputDimension`은 local rix slice의 max로 dims2를 만들므로 임의 DataOp의 행수 N 또는 명시적 outDim1만으로 [0,N] authority를 만들면 안 된다. 실제 rix max=N lineage 증거가 필요하다. CTABLE production 수정은 보류한다.
- **검증 운영:** canonical/CTABLE Maven 실행이 동시에 시작된 두 로그는 보존하되 clean 증거에서 제외했다. 이후 모든 Maven은 diagnostics `local-maven.lock`의 `flock`으로 직렬화한다. 실제 Docker timing은 실행 중이 아니며 remote cleanup/lease 해제는 확인된 상태다.
- **잠재 회귀:** equal-text/unequal-authority 병합, singleton metrics, query-specific pin 누락, endpoint cardinality와 partition cardinality 혼동, dead exact clause의 geometry 차용. 정확한 소스/OR/AND 관계 회귀와 독립 검토 후에만 frozen JAR를 만든다.
- **의사결정 근거:** 표현상 중복 작업만 제거하고 runtime/privacy 제약을 우회하지 않는다. 최종56조건+14 planning-only consumer의 동일 JAR acceptance는 여전히 미달/미선언이다.

## Search-space 알고리즘 계층화 및 불필요한 조건 정리

- **상태:** 소스 조사·6개 소규모 정리·설계 통합 완료. 큰 구조 교체와 60초 달성은 미완료다. 새 성능 실험은 실행하지 않았다.
- **문제 정의:** 규칙·관계 생성·내부 방어·디버그·후단 선택 검사를 동일한 알고리즘 단계처럼 설명하여 설계가 복잡하다. 실제 구현에도 사용하지 않는 인자, 생성자가 보장하는 중복 조건, 계측 OFF의 통계 작업이 남아 있다. 큰 비용은 반복적인 관계 재생성이다.
- **환경/증거:** dirty worktree `w1357-logreg-nary-fix`. 기존 ON receipt `relation-r34-semantic-on06`은 415.750729691초, OFF `relation-r34-semantic-off01`은 406.735203534초. 이번에 새로 측정한 결과가 아니다.

### 수정 전 정리 계획 / behavior lock

아래의 작은 변경만 이번 코드 정리 범위로 삼는다. 기존 dirty 변경·데이터·실험 결과를 보존한다.

1. `NativePlacementContinuity.nextRevision`의 읽지 않는 `invalidatedOccurrences` 인자를 제거한다. 의미 projection/footprint에 의한 무효화와 Builder의 `changedRows` 계산은 유지한다.
2. `CandidateEmissionFact` 생성자가 보장하는 `derivedFedFout ⇒ derivedFoutAction != null`에 따라 native topology의 중복 disjunct만 제거한다. action 자체의 거부는 유지한다.
3. metrics OFF에서는 `rawProofs`용 배열과 증가 연산을 만들지 않는다. 생성되는 support 관계는 그대로 둔다.
4. seed에 무관한 dynamic-output 판정을 seed loop 밖으로 이동하고, 곧바로 버릴 output anchor를 만들지 않는다. 원래 anchor/partition 의미는 유지한다.
5. `OracleFacade.normalizeConcreteOutputPlacement`의 유일한 caller와 `OracleEngine` 반환 계약으로 이미 보장되는 private null guard만 제거한다. scalar 출력 정규화와 UNKNOWN shape authority는 유지한다.
6. `PlacementIdentity.AnchorPartition`의 동일 길이 begin/end 생성자 계약으로 중복인 end 차원 검사를 제거한다. 최소 차원·worker·range 정렬 검사는 유지한다.

수정 전에 해당 기존 targeted 테스트를 실행한다. coverage가 부족한 계측 ON/OFF 출력 동등성은 작은 fixture로 보강한다. 수정 후 같은 테스트와 컴파일/기존 빌드 정적 검사, 변경 범위 whitespace를 확인한다. 전체 suite·수치 reference·896조건·성능 재실행은 이 정리의 선행 조건이 아니다.

### 범위 밖 구조 변경과 fallback 분류

- **후속 구조 변경:** fact revision의 불변 입력/edge/SCC 공유, base와 완료 관계의 소유권 통합, 하나의 component scheduler. 이들은 작은 조건 삭제와 별개이며 이번 정리에서 완료로 세지 않는다.
- **유지하는 compile-time 경로:** 아직 게시하지 않은 prospective native output의 staging template. production caller가 있으므로 미사용 fallback으로 삭제하지 않는다. 이미 선언된 invalid reference는 이 경로로 되살리지 않는다.
- **유지하는 실패 경계:** rule exception 전파, 지원 없는 cycle/잘못된 alignment 거부, privacy/runtime 제약. runtime fallback은 추가하지 않는다.
- **이미 OFF:** 기본 privacy audit/evidence 생성, metrics OFF의 일반 계측. 이를 새로 제거한 성능 개선으로 세지 않는다.
- **보류:** 공개 미사용 `OracleFacade.exploreAll` 삭제(외부 API 영향), 선택 이후 emission snapshot/Exact index 통합(search-only 밖). 이번 60초 전제 조건에서 제외한다.

### 검증 결과 / 잔여 이슈 / 잠재 회귀

- 검증: 수정 전 120건과 동일 묶음 수정 후 121건에서 같은 기존 실패 1/오류 3/skip 1. 새 metrics ON/OFF parity 1건 통과. 최종 관련 7개 클래스/메서드 회귀 **96건, 실패 0/오류 0/기존 skip 1**. compile·변경 diff whitespace 통과; 독립 코드 검토 APPROVE.
- 전체 `checkstyle:check`는 367,216건으로 실패했다. 이번 신규 위반 수로 해석하지 않으며 전체 style 통과를 주장하지 않는다. 새 패키징/배포·전체 suite·별도 보안 scanner는 실행하지 않았다.
- 변경 파일: `NativePlacementContinuity.java`, `NeutralPlacementGraphBuilder.java`, `PlacementIdentity.java`, `rules/bridge/OracleFacade.java`, `NativePlacementContinuityTest.java`. production delta +20/-25, test +25/-25. 기존 dirty 코드 보존.
- 재현/로그: `/home/mchoi/w1357-diagnostics/condition-cleanup-20260927-072939-1180966/{baseline-targeted.log,post-targeted.log,final-narrow-regression.log,final-narrow-regression-counts.txt,incremental-main.diff,incremental-test.diff,INDEPENDENT_REVIEW.md}`.
- 상세 결과/명령/기존 실패 목록: `/home/mchoi/cofee-evaluation/docs/COFEE_W1357_SEARCH_SPACE_SIMPLIFICATION_AUDIT_2026-09-27.md` §4. 정본 계획의 S0는 완료, S1/S2는 미완료.
- 잔여 이슈: 60초 미달과 중첩 closure 구조는 아직 해결하지 않았다.
- 잠재 회귀: 인자 caller, 계측 OFF/ON, dynamic range·transpose는 관련 회귀와 정적 검색으로 확인했다. 큰 관계 공유 리팩터링은 아직 수행하지 않았고, 넓은 테스트의 기존 실패·오류 4건은 남아 있다.
- **의사결정 근거:** 합법 후보를 줄이거나 정책을 완화하지 않고, 기존 생성자·caller가 이미 보장하는 조건과 관측 전용 작업만 정리한다. 논문용 핵심은 프로그램 요약 / 합법 후보 생성 / 관계 연결의 세 계층으로 통합한다.

## S1/S2 실행 재개 — 진행 중

- 사용자 승인: 3계층 정본의 남은 S1/S2를 구현하고 동일 Docker 조건 60초 성능 기준까지 진행.
- 변경 계획: 불변 native 구조를 revision 간 공유 → node-build당 oracle signature/dispatch 준비 → 동일 관계/결과의 반복 구성 제거 → component/loop summary 처리 흐름 연결. 의미 제약과 기존 frequency 유지.
- 회귀: 이전 S0의 기존 실패 4건은 구별하며 변경 영역의 작은 회귀를 수행한다. 후보 제한/정답 reference/896조건을 추가하지 않는다.
- 평가 계약: `/home/mchoi/cofee-evaluation/.omx/goals/performance/w1357-fused60-20260927/evaluator.md`; 측정 runner는 기존 `search-space-60s-revised-20260925` Docker one-shot 경로. native Codex goal은 조회 결과 과거 aggregate `blocked` 상태이며 임의로 완료·덮어쓰지 않는다. 이 실행의 파일 기반 성능 상태와 별도로 보존한다.
- 리소스: 로컬 홈 여유 약211MB 확인. package는 승인된 기존 grid build 위치의 새 전용 작업 디렉터리를 사용하고 Docker overlay는 home에 유지한다. 기존 결과·데이터 삭제 금지.
- 잠재 회귀: immutable 구조와 candidate-dependent cache 혼합, prepared oracle의 mutable Hop/registry 재사용, support/public 관계 상관성 손실. 구조 identity/변경 무효화/tuple별 동등성으로 확인한다.
- 의사결정 근거: runtime/privacy 정책을 변경하지 않고 기존 사실의 수명과 계산 위치를 바로잡는다.

### 직접 binding 구성의 중복 제거 계획

- 동일 immutable source realization의 exact-layout 전체 clause 판정을 한 binder 호출 안에서 한 번만 계산한다. cross-revision cache는 만들지 않는다.
- TWrite의 동일 source·worker pool·exactness alias는 생성 전에 합친다. source reference, pool, exact/dynamic 구분은 유지한다. transitive proof history는 기존에도 출력 alias에 복사하지 않는다.
- 변경 전 transient/dynamic/fixed-point/source-index 회귀를 실행하고 변경 후 동일 묶음을 비교한다. scheduler 영역은 별도 담당이며 이 변경에 포함하지 않는다.

### S1 검증 및 첫 실행 준비

- Native 구조 공유53건(기존skip1), binder/Oracle25건(기존skip5), 실패/오류0. 독립 root-delta 검토 APPROVE.
- grid 독립 소스 snapshot `/grid/3/cofee-lm-sweep-mchoi-20260914/w1357-fused-s1-build-20260927`, `mvn -q -Dmaven.test.skip=true package` 통과. JAR SHA256 `e0658f01ba40e59e4ad51cd5a5d55eaea3231fe6adf291d5612d95dd4475eaa9`.
- `fused-s1-on01`: overlay 준비 후 local preflight 실패. 원인: provenance 대상4파일을 prepare에만 추가하고 runner 목록에는 누락. 원격 실행0. 실패 증거 보존하고 양쪽 목록 일치 회귀 추가(로컬5tests통과).
- 수정 후 새 ID `fused-s1-on02`: preflight 통과, 실제 Docker coordinator 실행 시작. 자동 재시도가 아니라 harness 원인 수정 후 별도 진단. 최종 receipt는 아직 미확인.
- S2의 dirty BFS를 매번 SCC로 재구성하는 접근은 O(V+E) 작업을 늘릴 가능성 때문에 채택하지 않았다. 실제 base/derived 소유권 분리 작업 진행 중; S2 완료로 세지 않는다.

### S1 실제 결과 / S2 검토에서 확인한 결함

- ON 진단 `fused-s1-on02` 완료: search-space **406.15154796초**, `spaceReady=true`, selector/runtime/workload0, cleanup confirmed. 60초 미달. 기존 ON415.750729691초와 단일 비교이며 확정 개선율이나 OFF 수용시험 통과로 주장하지 않는다.
- 재계산 횟수는 direct852/closure53/proof graphs230359로 동일. 총 계측 allocation 약413GB. Direct exclusive154.41초, closure exclusive80.21초.
- S2 첫 시도(물리 rebuild 후 prior fact 재부착)는 독립 검토에서 거부:
  1. prior에 없던 유효 base emission/realization을 복원하지 않아 후보 유실 가능.
  2. prior 전체를 반환해 status/capability/shape/runtime(WDivMM) metadata까지 오래된 값으로 복원 가능.
  3. physical refinedOrdinals/immediate reference 존재만으로 CFG·loop·callsite·relocation·간접 WDivMM의 transitive authority 불변을 증명할 수 없음.
  4. consumer마다 전체 source reference index 재구성은 목표에 역행.
- 이 시도는 완료로 세지 않으며 반영하지 않는다. 올바른 후속은 fresh base metadata + 명시적으로 분류된 derived delta + 생성 시 캡처한 정확한 dependency ownership으로 수명 분리하는 것. 임의의 보수 검사층이나 후보 제한으로 대체하지 않는다.

## 전체 목표 재개 — 단일 합성 transfer

- 사용자 전체 달성 재요청에 따라 중단 없이 S2 교체와 최종3회 성능기준을 추구한다.
- 변경 전 계획: semantic/publication의 중첩 closure를 하나의 합성 transfer로 교체한다. function-boundary/physical, native CFG, privacy, action, executable projection의 의무는 남기되 한 iteration의 동일한 입력에는 다시 호출하지 않는다. 완전한 결과 상태(nodes/domain/facts/logical/actions)에서만 수렴을 판단한다.
- base/derived의 무근거 prior-fact 재사용은 재도입하지 않는다. Native 경로는 별도 공유 relation 작업을 병행한다.
- 회귀 기반: 기존 FixedPointComposition9/DirectedDirtyCone10 최종 통과 상태를 보존. 함수·branch·action·source deletion fixture 및 실제 Docker ON으로 교체의 안정성과 반복 감소를 확인한다. 새 runtime 정책/후보 cap/비용 이동은 없다.
- 위험: pruning 뒤 지연된 physical 갱신, source/action 소멸, 함수 경계의 중간 상태에서 잘못된 수렴. 순서 수정은 모델 의미가 아닌 처리 흐름만 대상으로 한다.

### 동일 native seed 요청 통합

- seed 이름만 다르고 FType·전체 worker/range partitions·계산된 exact output reference가 같은 경우 동일 실행 관계 요청을 한 번만 한다. source-binding 대안은 Native 결과 전체를 보존한다.
- 동적/unknown 출력은 seed에서 다른 pinned reference를 만들므로 합치지 않는다. seed 이름만 보고 무차별 통합하지 않는다.
- 근거: NativePoolWitness는 seed placementId를 읽지 않으며 durable output anchor는 owner로 결정된다. sourceHandle/root pin이 같은 경우에만 공유한다.

### S2 component batch 작업 계획

- 기존 direct closure 두 전체 sweep를 dependency-ready SCC antichain batch로 교체한다. owner→fact slot은 한 번 만들고 선택된 owner의 row만 binder에 전달한다.
- 한 wave당 global source index/Native revision은 한 번만 갱신한다. 새 epoch/cache framework는 추가하지 않는다.
- compiled/reaching/function potential edges + 실제 old/new support edges로 scheduling한다. alias는 invalidation으로 유지한다. changed export의 transitive cone을 유지해 unchanged intermediate 뒤의 grounding 변경을 놓치지 않는다.
- 한 component는 local equality 확인 뒤에만 후속 component를 해제한다. 새로운 binding edge가 생기면 schedule을 재구성하고 pending owners를 재배치한다.
- baseline unified+native+seed 회귀 통과, 실제 ON 진단은 별도 frozen JAR로 실행 중이다. 이 변경은 다음 버전에만 반영한다.

### unified-on01 실패와 후속 component 검증

- frozen unified+native+seed 버전은 실제 logreg에서 7분 이상으로 악화됐다. SIGQUIT main stack은 unified composition 안의 completedLoopSeedTransfer→bindRelocationCandidateRealizations에서 반복 정렬 중이었다. 단일 stack은 전체 시간 비율이 아니다.
- 이 진단은 성능 실패로 분류하여 정확한 소유 coordinator Java PID에 TERM을 보냈다. runner exit143, cleanup confirmed. `operator-stop.json`과 thread dump를 보존하며 성공 receipt/시간으로 집계하지 않는다.
- component batch 첫 회귀는 통과했지만 독립 검토가 alias 재확장 starvation(A→B, A↔B)을 찾아 수정했다. alias closure는 최초 seed/실제 export변경에만 적용; unchanged predecessor가 다음wave에 다시 끼어들지 않는다. 전용2-owner 회귀를 추가했고 두번째 통합묶음 통과.
- 다음 ON 진단에는 metrics와 phaseMarkers가 모두 켜진 경우에만 bounded composition progress를 남긴다. OFF 경로에 출력/계측을 추가하지 않는다.

### 최종 action authority와 1회 검증 변경

- component-on02는 export가 동일해져도 중간 action 목록 비교 때문에 반복했다. 중간 목록 비교를 수렴 조건에서 제외하되 최종 source/action authority 검증은 유지했다.
- component2-on02는 pass9에서 수렴했으나 최종 relocation clause가 더 이상 published action에 속하지 않아 거부됐다. binder의 대체 clause 부재 시 기존 emission fallback이 stale clause를 되살리는 경로를 확인했다.
- 최종 action 목록으로 먼저 재binding하고, 유효한 OR sibling은 유지하면서 만료 action/source가 필요한 AND clause만 제거한다. 이후 projection/logical/action delta를 합성 상태로 다시 반영한다. 검증을 끄지 않았다.
- PublicationSupportClosureTest 신규 회귀: 만료 action+live source AND 전체 제거, direct sibling과 valid-action sibling 유지. 5tests 통과. 관련 action/composition 테스트 묶음도 통과.
- direct-native derived root는 owner + seed FType/전체 partitions의 canonical layout으로 정규화한다. 원본 source/anchor/action/input 선택은 그대로 보존한다. 관련14tests 실패0, 기존skip2.
- **최신 사용자 지침: 성능 수용시험은 새 JVM 1회만, 60초 이하. 종전 3회 기준 폐기.** frozen component3 OFF01을 유일한 현재 수용시험으로 선언하고 실행 중이다. 아직 성공/완료로 세지 않는다.
- component2-on01은 디스크 preflight에서 미시작 실패. 기존65MB checkstyle report를 grid에 SHA256 검증 복사 후 원래 위치에 symlink로 보존하여 공간 확보했다. 데이터/결과 삭제 없음.

### CFG 관계 소유권 / base ownership 후속

- 상태: 로컬 회귀 수정 중, 아직 최종 성능 성공 아님.
- CFG TRead는 inputBindings가 없고 writer 근거가 LogicalTransientInputFact에만 존재한다. 기존 최종 pruning은 이 관계를 읽지 않아 writer가 없어져도 reader를 남겼다. 수정: 완전한 grounded 관계를 pruning 동안 보존하고 각 reaching writer마다 살아 있는 호환 source 대안이 있는 reader만 유지한다. 제거는 기존 source pruning 반복으로 downstream에 전파한다. 조건 자체를 완화하지 않는다.
- 관련 PublicationSupportClosure6 / FixedPoint9 / Transient7(기존skip5), 실패0. 두 writer 중 하나만 살아 있는 reader 제거와 유효 sibling 보존을 회귀로 확인.
- 다음 S2 경계 최적화: analysis 한 번 동안 owner별 runtime-adjusted base를 별도로 보관한다. 새 base와 이전 base, 현재 Node, 모든 row metadata 및 emission semantic slot이 정확히 같은 경우에만 기존 derived proof를 덮어쓰지 않는다. row/emission이 빠졌거나 PROFILE_ERROR면 fresh base를 다시 설치하여 source 재등장 복원을 보존한다. 추가 derived emission도 동일 슬롯으로 취급하지 않는다.
- 물리 closure의 unchanged intermediate도 한 번씩 계속 순회하여 간접 latent-WDiv weight 의존을 전파한다. dirty direct proof 계산 자체를 생략하지 않는다. formal-input rebuild에도 같은 규칙을 적용한다. 새 전역 반복이나 proof cache framework는 추가하지 않는다.
- 근거: metadata/base 생산과 source proof 생산의 소유권 분리. 이전 거부된 prior-fact heuristic과 달리 완전한 adjusted base 변경/누락을 확인하고 source 재등장 시 Native에 AVAILABLE 행을 복원한다.

### 로컬 통합 검증 결과

- Base ownership2/2, publication7/7, fixed-point9/9, function-alias2/2, Native55(기존skip1) 통과. 실제 multiLogReg builtin 컴파일 publication 회귀 통과. JaCoCo155.133초는 성능 측정이 아니다.
- known PCA fixture root-unproven 오류1건 별도 보존. 수정 범위 외이며 전체 Maven green으로 표현하지 않는다.
- 요구 writer 목록은 이제 compiler CFG에서 별도로 얻는다. logical facts가 이전 privacy 처리에서 통째로 사라진 경우도 감지한다. 동일 source geometry로 authority를 대체하지 않는다.
- final-build JAR730a67063fdf8768d120e94eb22d4ea30721d3d89d0f124103689432111a85a4 빌드 통과. 단일 Docker OFF 수용시험 시작, 결과 대기.

### W1/W7 40초 목표 재개

- 새 active goal: logreg40초 이하, 사용자가 W1·W7 모두로 명시. 기존7분 실행은 user-stop143/cleanup확인 후 종료됐고 재사용하지 않는다.
- r1 ON 후보(b47a61...)는 derived-output proof-history 교차곱 제거를 포함했으나 analysis120초 진단cap 초과. pass11까지 domain 축소/확대가 반복됐다. 종료stack canonicalClauseOrderingText→mergeRealizations→removeUngroundedStagingRealizations. 정확한 coordinator Java만 종료/cleanup완료.
- r2 수정: unchanged pruning의 clause/emission/fact객체를 재사용하여 반복 정렬·직렬화를 피한다. metricsOFF에서도 analysis-scoped 강한 signature cache를 ON과 동일하게 활성화한다(계측카운터는OFF). scopecleanup/canonicalization검증통과. Publication8/FixedPoint9/scope2/logreg1 회귀통과.
- r2 frozen b2d4e3abb07087890fd08db061ce7cffbbbbee9e5f3ba0e317272d0296659826, 단일OFF 실행중(90초fail cap). 성능완료아님.
- 추가 구조원인 조사: LoopSeedRevision이 loop마다 whole-program facts를키로 삼아 무관한 변화도 seed재시작을 유발. completed transfer도 whole-programstate복원하므로 키만좁히는수정은 금지(무관한상태 rollback위험). scoped seed summary/exit revision 재사용 검토중.

### r2 실측 실패 및 r3 수정 계획

- r2 OFF 실제 W1: analysis 90초 제한 초과, 종료143, cleanup_confirmed=true, attempt_count=1. 완료 Tspace 없음. W7 성능은 아직 측정하지 않음.
- 개선 계획: 완료된 loop seed EXIT revision을 동일한 전체 proof 상태/authority key로 기록하여 재진입 시 중복 seed 설치 방지. key만 축소하거나 전체-state memo의 의미를 바꾸지 않는다. entry/exit 및 source/action/privacy 변경 회귀로 검증.
- 조사 중: composed transfer 중간 privacy/projection 변화가 최종 변화가 없어도 다음 physical rebuild를 요청하는 중복. 최종 export delta 기반으로 바꿀 수 있는지 의존 관계를 검토한다.
- 완료 기준은 W1 및 W7 각각 실제 새 OFF JVM 1회 Tspace<=40초, 동일 frozen JAR. 실패 후보의 기록은 보존한다. 아직 목표 달성 아님.

### r3 loop memo 및 후속 static privacy projection

- 완료 loop transfer의 전체 EXIT key를 기록한다. 전체 source/action/privacy key는 그대로 유지한다.
- 발견한 memo 버그: 완료 replay의 pending changedOrdinals가 기록 시 소실됐다. 정상 closure는 해당 pending을 caller에 전달하므로 memo도 보존하고 복원 delta와 합친다.
- ALS 회귀 실패는 r2 immutable snapshot을 별도 grid 복사하여 동일 테스트로 재현했다. 새 버전 전체 green으로 주장하지 않는다. L2SVM 및 helper 검증은 별도 결과로 기록한다.
- r3 frozen JAR: 6c3bbde0b5d00c80d22ef16554ccd8d7a6de32a3208f8fb6ce0773ba63e263d6. 실제 W1 OFF 진단 1회 진행 중, analysis 90초 fail cap. 아직 성능 결과 없음.
- **거부한 shortcut**: outer pending을 최종 delta만으로 바꾸면, 중간 상태로 계산된 consumer를 다시 계산하지 않을 수 있어 적용하지 않는다.
- 후속 수정 계획: 이미 확정된 static privacy를 fresh oracle base에 먼저 적용한 다음 기존 base 보존 조건을 그대로 사용한다. 기존 PRIVACY_EXCLUDED status만 보고 재사용하면 안 된다(sourceClosed 제거도 같은 status를 만들기 때문). shared row/node filter로 중복 구현을 피하고, source 재등장/부분행/runtime WDiv 제한 회귀를 유지한다. 최종 privacy pass는 materialization 추가분 때문에 유지한다.

### 사용자 지시에 따른 병목 실측 완료 (최적화와 분리)

- 추가 최적화를 멈추고 마지막 실패 r3에 계측만 추가한 SHA29cfcf...로 W1/W7 각각1회 실행. JFR110초, watchdog120초, 모두 종료143/cleanup확인. 미완료 search-space 시간/40초 성공으로 세지 않는다.
- 마지막 live partial 누적119.147s(W1)/123.307s(W7). 자체 시간 relocation binding31.191/45.719s, direct binding23.344/16.635s, closure 내부 기타21.804/26.265s.
- JFR main sample의 CanonicalText.compareTo 경유42.51%/43.63%. support 병합→realization 생성→정렬 경로가 비교 stack의81.97%/94.30%. privacy 자체0.085/0.142s. 따라서 무계측으로 privacy/loop부터 우선 개선했던 판단 대신 support 병합·정렬을 우선 조사한다.
- JFR print 기본 stack5frames의 caller 누락을 발견하여 같은 원본을 --stack-depth64로 재추출했다. 실험 재실행 없음. leaf비율과 inclusive비율, CPU와wall을 혼동하지 않는다.
- LiveMetrics2/2, AttributionTiming5/5, profile initializer3/3 통과. 현재 primary의 중단된 static privacy/hash 변화는 compile-only/미검증 부분이 남아 있고 이번 profile에는 포함하지 않았다.
- 상세 정본: /home/mchoi/cofee-evaluation/docs/COFEE_W1357_LOGREG_W1_W7_BOTTLENECK_PROFILE_2026-09-27.md

### 계측 후 후보 r4: canonical 처리만 분리

- 사용자 계측 우선 지침에 따라 r3+계측 baseline에서 PlacementAnalysis만 변경하여 효과를 분리한다.
- long canonical 설명을 문자마다 cursor 이동하지 않고 연속 literal chunk 단위로 비교. UTF-16 사전순/전체 후보 유지.
- canonical-list 표시를 보존하여 생성자 재정렬을 피하고, 두 support 목록은 기존 정렬을 이용해 병합한다. 더 많은 group은 기존 일반 경로 유지.
- 중단됐던 static privacy projection/hash 실험은 원인 분리를 위해 active Builder에서 분리했다. 원본/diff/hash test/compiled test를 /grid/3/cofee-lm-sweep-mchoi-20260914/deferred-privacy-hash-20260927 에 보존; 다른 기존 수정은 유지. 독립 diff검토로 해당 두 실험만 분리함을 확인했다.
- baseline 문자정렬 differential2tests 통과. 첫 구현 canonical18/authority10/chunk2 통과. 2-list merge의 comparator context 반복 생성을 추가 제거 중; 최종 후보는 그 후 새로 빌드/측정한다. 아직40초 성공 아님.

### r5 이후: 반복 합성과 boundary index / exact union 개편

- **상태:** 진행 중. W1/W7 40초 목표는 미달성이다. r5 W1은 90초 진단 한계를
  넘었으며, ENOSPC 복구 이후 exact coordinator 종료/manifest cleanup을 확인했다.
  기존 failed 결과는 그대로 보존한다.
- **문제 정의/원인:** r5 composition pass11이 pass4와 같은 전체 게시 상태를
  재생성했다(주기7). loop ledger/base context는 달라 단순 cycle 조기 종료는
  허용하지 않는다. raw base의 정책상 금지된 emission을 복원한 다음 다시 지우는
  경계 불일치와, 매 direct wave의 전체 boundary option 확장을 확인했다.
- **해결 범위:** `LogicalBoundaryRealizations`의 private option index를 이미
  flatten한 boundary target/leaf source에 한정한다. public fact/후보를 삭제하지
  않는다. `PlacementAnalysis`는 두 immutable support relation의 union을 map/hash
  생성 전에 계산하고 unchanged 결과의 authority를 보존한다. exact equality가
  아닌 comparator 동률은 기존 exact map 병합으로 처리한다.
- **수정 파일:** 위 두 production 파일과 각 canonical/boundary 회귀. 확정 privacy
  정책의 fresh base pushdown은 별도 검토·구현 중이며, 최종 privacy 검증은 유지한다.
- **검증:** 새 boundary 회귀가 기존 코드에서 예상대로 실패한 뒤, 수정본 6suite
  36tests 실패/오류0. 마지막 canonical tie 보강 이후 통합 회귀는 다시 수행한다.
  W1/W7 local initializer 테스트10건도 통과하고 두 harness provenance 목록에
  boundary source를 추가했다. 아직 새 Docker 성능 성공 증거는 없다.
- **측정 근거:** r5 JFR 4,838 main samples 중 mergeRealizations 경유1,199,
  boundary close 경유849. inclusive 샘플은 겹치므로 합산하지 않는다. 원본
  `/grid/3/cofee-lm-sweep-mchoi-20260914/w1357-profile-evidence-20260927/r5-w1.jfr`.
  마지막 live 행은 ENOSPC로 잘렸으므로 CPU-only summary를 별도로 작성했다.
- **잔여 이슈/잠재 회귀:** 전체 outer convergence와 실제40초는 미검증.
  boundary 누락 source, exact support OR/AND 상관관계, comparator 동률, static
  privacy 적용 뒤 source 재등장을 회귀와 동일 frozen JAR Docker W1/W7로 확인한다.
- **의사결정 근거:** runtime/privacy 정책을 완화하거나 합법 후보를 cap으로
  자르지 않고, private index의 불필요한 확장과 같은 relation의 재생성만 줄인다.

## Logreg closure authority: exact cycle and root-history sensitivity (진행중)
- 증상: r6 W1의 publication과 ledger/base context 전체가 7-pass 주기로 반복. r7의 90초 진단은 rc143, 단일 시도, workload 0, cleanup 확인.
- 원인 근거: multiLogReg.dml:157:31의 transpose/MM은 배치/행 metadata/realization key가 동일하지만 native FULL support가 76→94 및 33→41로 변한다. 물리 재구성이 기존 native proof를 staging으로 초기화한다. Native validation API는 output이 이미 존재하면 이전 root clause를 읽지만 없으면 prospective dependency를 생성한다. 또한 loop memo hit는 저장된 결과에 없는 의미적 relocation 재계산을 수행한다.
- 결정: 새 generation-root API와 기존 validation API를 분리하고 memo hit는 동일 값의 action identity만 정규화한다. 먼저 기존 물리 재생성/dirty 전파를 유지한 작은 correctness gate를 검증한다. 반복 상태를 성공으로 처리하거나 후보/반복수를 임의 제한하지 않는다.
- 계획: `/home/mchoi/w1357-diagnostics/fused-20260927/closure-authority-repair-plan.md`.
- 검증 현황: 실제 X PRIVATE_AGGREGATE/Y PUBLIC hermetic metadata test 성공; publication fixture는 75초 외부 cap 내 완료하지 못함. 새 구현/성능 검증 대기.
- 잔여 이슈: materialization이 추가한 BROADCAST를 raw base가 계속 제거하는 경계도 확인. 작은 수정 후에도 필요하면 완전한 generation-envelope 소유권을 분리한다.
- 잠재 회귀: generation/validation cache 혼용, root self-grounding, cyclic pin 누락, stale action identity, source 재등장 누락. 각각 focused regression과 실데이터 Docker OFF 양쪽 측정으로 탐지한다.

## Native topology의 revision-local handle 재사용 경계 (진행중)
- **증상**: r8의 혼합 privacy logreg 회귀는 40~46초 후 X loop seed의 모든 reaching-definition 관계가 사라졌다는 검증 오류로 종료한다. 첫 거부 시 entry/carried writer 둘 다 FED/FULL이며 직접 참조도 실제 relation에 존재한다. 이후 CP fallback baseline으로 rollback되어 guard가 실패한다. guard는 완화하지 않았다.
- **가설/코드 근거**: `NativePlacementContinuity.reindexTopology`는 row handle index만 새로 구성하고 dependency skeleton의 `clausePinnedHandle`은 이전 resolver 값을 유지한다. bounded structural arena가 없거나 소진되면 handle은 resolver-local 음수인데 다음 revision은 새 namespace를 쓴다. 참조가 존재해도 이전 음수 handle로 현재 row를 찾으면 잘못 거부할 수 있다.
- **수정 계획**: 먼저 handle 할당 순서가 달라지는 최소 회귀로 fresh/reused resolver의 차이를 재현하고, dependency reference의 handle까지 새 resolver에서 정규화한다. 의미적 proof/candidate는 그대로 유지한다.
- **증거**: `r8-mixed-privacy-pintrace-captured.log` 마지막 첫 실패에서 entry989→read988 및 carried973→read972 모두 `present=true`. 실제 r8 첫 시도는 SSH forwarded-agent hang으로 실행 전 거부되었고 정확한 두 container 부재를 recovery receipt로 확인했다. 기존 로컬 key로 인증할 수 있어 이후 환경에서 SSH_AUTH_SOCK만 제거한다.
- **검증/위험**: focused regression 및 전체 Native, mixed logreg, Docker W1/W7 검증 대기. 잘못된 pin으로 다른 source를 선택하는 위험도 있으므로 단순 missing-handle fallback을 추가하지 않는다.

## r9: generation memo restoration and handle regression verification (진행중)

- Actual frozen JAR `75c3ac5b47689a3ed5e5bea9a774d91ffc7ad445abce829f562b551df05ceda6`, W1 metrics ON diagnostic `profile-runs/w1/trace-r9-w1`: analysis >90s, stopped at the declared diagnostic cap; one attempt, workload execution 0, exact cleanup confirmed. Neither convergence nor 40 seconds is claimed.
- Last 90-second partial proof graph count 66,426 vs r8b 195,419. This is bounded-work evidence, not successful end-to-end latency or an OFF speedup. r9 reached publication pass9. Pass6/8/9 had identical nodes/facts/actions/domain but differing logical-input relations and pending work; materialized raw-base replacement still discards native support histories.
- Initial fallback-handle regression unexpectedly passed old frozen r8: insufficient fixture, not a valid red/green proof. Strengthening forces different resolver-local allocation order, checks that producer handles differ, and now fails at the actual empty-proof assertion on unchanged frozen r8. The repaired source passes the same test; final full Native rerun is tracked separately.
- Next bounded branch: reconcile native support ownership without preserving unvalidated materialization extras, and diagnose exact logical-relation deltas. Do not suppress pending work, accept recurring states as fixed points, remove legal candidates, or relax all-writer/privacy/runtime guards.
- Artifacts: `/home/mchoi/w1357-diagnostics/fused-20260927/profile-runs/w1/trace-r9-w1/bounded-summary.json`; `handle-reindex-red-strengthened.log`. Residual risks: incorrect proof reuse after source change and stale logical/action authority; focused source-withdrawal/reappearance and real mixed-privacy publication tests required.

## r10/r11: native support ownership and CFG reader identity (진행중)
- r10 bounded physical repair retains tentative native support only behind an identical pristine raw base and matching native FED/FOUT emission; fresh inventory/status/metadata win and materialization extras are still dropped. Dirty work and full native regeneration stay active. Architect reviewed CLEAR. Seven scoped suites:114run/0fail/0error/1skip. New generation withdrawal/restoration test included.
- Added metrics+export-only bounded logical deltas. r10 standalone hermetic mixed fixture (no Jacoco,8GiB,8 active processors) timed out150s after publication pass13; this is not Docker acceptance evidence, and no r10 actual run was launched. Log `local-mixed-r10/run.log`.
- Concrete identity defect: exactTransientReplay already deduplicates seeds by complete physical layout, but names generated native readers using the selected carrier placementId. Equal-layout carrier changes renamed reader keys. r10 pass4 source key stays the same while reader key swaps and pool-ID label changes511→507.
- r11 changes only generated native reader identity to reader occurrence + canonical full FType/partitions + exact/dynamic marker. Actual source references, proof witnesses, all-writer checks and DURABLE_MAP identities remain untouched. Extracted old naming into a behavior-preserving seam; new regression failed on the exact carrier-ID difference before applying normalization. `r11-cfg-lineage-red.log`. Fresh full transient/source/Native regressions and next actual measurement pending.
- Residual issue: later same-key proof subsets grow (P_new36→63 atpass11); this is separately audited, not assumed to be memo or safe to truncate. Plans `native-support-reconciliation-plan.md`, `cfg-native-lineage-normalization-plan.md`. Goal remains active/unachieved; no forbidden fallback or candidate cap added.

## r12: compatibility existential projection (검증 진행중)
- **명시적 표현 계약 변경:** Native solver는 모든 immediate binding proof를 유지한다. source candidate realization/support clause도 삭제하지 않는다. Builder의 transient edge만 같은 source/reader/typed witness/precision에 대한 여러 opaque 설명을 하나의 성공한 existential query certificate로 표현한다. 이는 실행 후보 cap이나 선택기로의 작업 이동이 아니다.
- **소비자 감사:** 일반/Exact 선택기, receipt lookup, all-writer 검증, source-prune는 endpoint realization 관계를 사용한다. opaque continuity detail은 선택한 source receipt clause와 결합하지 않는다. Validator의 owned Native kind, VALUE_IDENTITY, geometry, exact/dynamic 검사는 유지한다. Architect와 독립 critic 모두 이 범위 CLEAR.
- **수정:** Builder.nativeTransientCompatibilityProofs는 unchanged Native API의 전체 결과를 순회하고, source ref+전체 seed/output FType/partitions+정밀도 certificate로 투영한 전체 proof의 exact equality로만 중복 제거. 실제 typed witness 객체/ID와 common proof는 그대로 보존. 서로 다른 output layout이 witness-only map으로 합쳐지는 shortcut도 사용하지 않는다.
- **행동 잠금:** 기존 'Builder 설명2개' 테스트를 몰래 삭제하지 않고 provenance 경계 테스트로 강화했다. Native2개의 서로 다른 binding을 먼저 확인한 뒤 Builder1개 요구가 수정 전 실패했다. 수정 후 Native2→3/일부경로제거/전체경로제거/복원과 stable certificate, 원본 facts/support 불변 검증 성공. 첫7suite109run/0fail/0error/6policy skips. 추가 typed evidence 테스트 진행중.
- **복잡도 효과:** Native의 완전한 proof 열거 비용 P는 그대로지만, logical relation이 동일 endpoint/witness의 P개 설명을 실행 relation처럼 복제하지 않는다. 이후 relation 비교/CFG 재생성/물리 반복의 설명 이력 의존성을 제거하는 경계 수정이다. 실제 속도 개선은 아직 주장하지 않는다.
- **잔여:** r11 standalone은150초 cap/pass14; frozen r11은 초기 test fixture에 duplicate partition 오류가 있어 최종 acceptance build로 사용하지 않는다(수정 fixture는 green, production 동일). r12 standalone mixed 테스트 진행 중. Actual Docker 성공 증거는 아직 없음. 원본 source/typed witness의 carrier ID가 바뀌는 잔여 동일성 문제는 이 gate에서 임의 정규화하지 않았다.

## r13 derived-output normalization / r14 atomic replacement (진행중)
- **증상/근거:** r12 standalone120초 제한 rc124. r13도65초 내 미수렴(pass4=59.49s), 실제40초 목표 미달성. r13 첫 local launch는 diagnostic classpath 누락으로 분석 전 실패했고, 수정 launch는 별도 디렉터리에 보존했다.
- **r13 해결:** 동일한 normalized DURABLE_MAP output key와 현재 정확한 owned derived-FOUT certificate가 있는 clause만 fresh template와 합친다. 앞선 action authority binding이 바뀐 경우 이전 proof는 유지하지 않는다. geometry만 같다는 이유로 합치지 않으며 기존 action/source 검증을 유지한다.
- **검증:** 새 normalization 회귀 RED→GREEN, exact source 철회/다른 reference/복원 실제 pruning 경로 추가. 7suite41tests0fail/error0skip. Architect/독립 critic CLEAR. 속도 개선은 확인되지 않았다.
- **r14 원인/계획:** inner CFG는 raw Oracle FULL/LOUT만 설치하고 outer는 materialization BROADCAST를 추가해, 서로 다른 generation inventory를 계속 비교한다. fresh owner output과 전체 current proof context를 분리하여 owner마다 materialization+정규화를 완료한 뒤 한 번에 설치한다. 기존 global discovery/dirty/source 재등장/최종 pruning 유지. bootstrap/full-context memo authority 구분.
- **수정 파일:** NeutralPlacementGraphBuilder.java, DerivedFoutNormalizationTest.java, 다음 PhysicalGenerationEnvelopeTest.java.
- **잔여/위험:** 전체 수렴과 실제 W1/W740초 아직 미달성. full resolver를 owner마다 만드는 비용 및 stale pool/ambiguous pool/action identity 회귀를 확인한다. native witness ID는 registry authority로 흐르므로 임의 정규화하지 않는다.
- **의사결정 근거:** runtime/privacy 제약이나 후보를 축소하지 않고 생성/증명/게시 경계를 맞춘다. 계획과 실패 증거는 `/home/mchoi/w1357-diagnostics/fused-20260927/`에 보존한다.

## r14 verification / r15 exact Native context lifetime (진행중)
- **r14 결과:** atomic owner generation envelope regressions passed, including source withdrawal/ambiguity/restoration, no stale owner extras, exact derived output key coverage, and bootstrap/full-context memo separation. Broad 13-suite run:195 tests,0 assertion failures,1 pre-analysis localhost metadata error,6 policy skips. Repaired that legacy authority fixture with hermetic PRIVATE_AGGREGATE metadata and exact owned clause receipt; targeted test passed. All failing logs retained.
- **측정:** local-mixed-r14b (ON, standalone diagnostic only) reached90s cap rc124 without completed publication; pass4 at82.45s,745facts,352actions,pending22. Not Docker acceptance evidence. First r14 local launch failed before analysis because it overlapped Maven’s test-class rebuild; subsequent runs serialize Maven and standalone diagnostics.
- **r15 변경:** Builder retains one all-definition Native resolver only within a build; complete structural context must match before nextRevision. Always rebase exact current fact/emission objects. Single-seed provisional loop contexts remain temporary; full reaching writers still revalidated. Entry/finally clear the slot. Materialization proof resolver is now constructed lazily only after the unchanged existing owner eligibility checks require it.
- **검증/복잡도:** new strict matcher checks identity-owned inventories, Node values, Hop identities, every edge position/endpoint, ordered reaching writers, incomplete set,privacy. Removed a discovered quadratic lookup from initial implementation: snapshot identity maps once and compare linearly. Native63run/0fail/error1skip; envelope4 and composition9 passed. Explicit exception/success cleanup regression and broader tests are running. Independent review pending.
- **수정 파일:** NativePlacementContinuity.java, NeutralPlacementGraphBuilder.java, NativePlacementContinuityTest.java, NeutralPlacementFixedPointCompositionTest.java; legacy authority test fixture repaired separately.
- **잔여/위험:** still no actual OFF W1/W7 success; whole-program convergence remains unproven. Cache reuse must not preserve stale descendants/global aliases or cross provisional/full reaching contexts. New field-isolation tests plus old withdrawal/restoration and full Native regressions guard these. No new candidate cap, privacy relaxation, runtime fallback, or selector deferral.

## r15 convergence / r16 repeated support construction (진행중)
- **r15 검증:** full scoped result203 tests,0failures,0errors,6 policy skips. Known preexisting protected ALS loop test remains excluded and separately documented. New exception/success cleanup test confirms resolver populated before test-only abort, cleared afterward, and same-builder rebuild equals a fresh builder. Independent critic CLEAR.
- **첫 완료된 혼합 privacy 진단:** local-mixed-r15 exited0; publication pass6 stable at101.834s, LOCAL_COMPLETE102.962s. Metrics ON/standalone only, not Docker acceptance or40-second success. Counters:42,048graphs,3,938,939topology calls. Native overlay self20.21s,direct16.83s,relocation13.80s,grounding10.96s,closureother10.04s,physical7.86s,topology6.16s. Observed behavior is expensive convergence, not a proven whole-state cycle.
- **r16 bounded changes:** preserve independently matching LOCAL/derived emission support when one native sibling is absent, while fresh generation still owns all rows/emissions/keys. Exact current action certificate plus fresh template required for derived output. Existing downstream source/action/privacy validation remains. Genuine RED on r15 isolated target:8tests,2expected failures for local support reset; initial joint compile found one missing import, fixed without discarding failure log.
- **Native representation:** cache immutable default topology-row dependency overlay and effective exact edge key. Only rows touching a query-fixed owner build a dynamic overlay. One shared exact dedup set handles default/dynamic collisions, and topology reindex reconstructs destination-local handles. No truncation or altered generated-root/self-loop rule. Focused and broad validation/review pending.
- **Ownership/artifacts:** root Builder+BaseCandidateOwnershipTest; canonical_reform Native+Native tests. Plans per-emission-reconciliation-plan.md and default-topology-overlay-plan.md; r15 baselines preserved. Current acceptance declaration remains unset/FAIL. Next: validate r16, OFF diagnostic and actual same-frozen-JAR W1/W7; no workload/campaign execution.

## r16 actual acceptance pair: W7 passed / W1 remains above40 (진행중)
- Frozen build `/grid/3/cofee-lm-sweep-mchoi-20260914/w1357-logreg40-r16-build-20260927`, JAR SHA256 `5aeebc43786ca4cc875c2cfc03df7a0ef771447df1a33c62161f255a1d62f60a`; full frozen source checksum verification passed. Scoped14suites209tests0fail/error6policy skips; knownpreexistingALS gap retained.
- Fresh one-shot actual LAN Docker OFF measurements: **W1=54.593504626s, W7=23.909191335s**. Both spaceReady/planningStatus success,attempt1,selector/runtime/workload0,exactcleanupconfirmed. Runs `acceptance-runs/w1/accept-r16-w1` and `acceptance-runs/w7/accept-r16-w7`. Same frozen JAR; no repeated-run best selection. W7 evaluator passed; joint evaluate40_current.py failed because W1>40, goalstillactive.
- Local OFF diagnostic hit90s timeout afterpass5; it is not Docker evidence. Local ON+JFR profile subsequently completed104.469s/stablepass6; current profile retained under `local-mixed-r16-profile/`, no speedup inferred from unlike runs. Performance remains based only on actual Docker pair above.
- Separate r16 planning-only consumer started from same frozen JAR after exact W7cleanup; runtimeprogram emission is allowed for this compile-only consumer, but no workload execution. Outcome pending.
- Next r17: exact generated relocation-product reuse after fresh source/action/obligation filtering. No whole-row shortcut. Behavior-preserving extraction landed uncached for RED locks before memo. Architect design CLEAR; independentreview/implementation/tests pending. Plan `relocation-product-memo-plan.md`.

## r16 shared proof-object pruning contract / r17 product reuse (진행중)
- **정확성 검토 추가 발견:** default topology overlay reuse allows the same immutable SelectedCandidateProof object under distinct pinned/unpinned CandidateProofState owners. `pruneDeadAlternatives` used one global identity removed set: it decremented the first owner only, but final filtering removed the shared object from every owner, leaving reverse ancestors incompletely pruned. Architect and independent critic both reproduced the code path. Downstream grounding still checks all dependencies; no invalid published proof has been demonstrated. Nevertheless the pruning fixed-point contract is broken, and r17 integration is gated on a regression-backed owner-local correction.
- **수정 계획:** owner-state + alternative-object identity removal; shared-row/two-owner/two-ancestor dead-source RED regression; exact no-initial-dead fast path checks every state and dependency before returning original graph. Preserve all dead-state keys/invalidation footprints, metrics accounting, and grounding. No SCC scheduling changes in this pass. Plan owned by Native agent.
- **r17 product memo:** genuine uncached helper RED captured in `r17-relocation-product-memo-red3.log`:3tests,1expected identity-reuse failure (two enumerated leaves/two exact support clauses). Earlier missing-import compile failures retained separately. Root implemented fresh choices/output-key memo and broadened tests: previous/current handoff, source alternatives/empty/restoration, owner/action/source identity, output branch/geometry, fresh carried clauses vs cold generation, success/exception cleanup. No whole-row caching or candidate truncation. Joint compile and independent implementation review pending.
- **r16 planning consumer passed:** `planning-consumer-runs/plan-r16`, planning-only success75.718s, initialCompilation1/runtimeProgramEmission1, runtimeStatus=not_started/runtimeSeconds=null,workload0,exactcleanup. This is consumer validation, not search-space40success or workload execution.

## r17 freeze gate: exact product memo and owner-local pruning (검증 완료 / 성능 측정 대기)
- **수정/근거:** Fresh owner/emission/action/output/anchor/recompute/complete source-choice keys memoize only generated relocation products across the immediately previous/current binding invocation. Every row, fresh source filter, obligation, action and carried clause remains reprocessed; no candidate cap, stale whole-row reuse, selector deferral or runtime fallback. Entry/finally clear build-local memo.
- **Native 수정:** A pre-allocation closure scan returns the original nonempty dependency-closed graph before building reverse/live structures. Slow-path removal is owner-state plus alternative-object identity, so a shared immutable alternative independently decrements pinned and unpinned owners and propagates both ancestor chains. Dead state keys remain for invalidation; grounding and SCC semantics are unchanged.
- **행동 잠금/검증:** Genuine product RED at r17-relocation-product-memo-red3.log and Native RED at native-prune-owner-red-v2/junit.log. Final scoped Maven 16 suites:219 tests,0 failures,0 errors,6 existing policy skips; known preexisting protected ALS gap remains explicitly excluded. Independent critic CLEAR for both implementations; git diff --check passed. Reports r17-scoped-regressions.log/json.
- **수정 파일:** NeutralPlacementGraphBuilder.java, NativePlacementContinuity.java, RelocationProductMemoTest.java, NativePlacementPruneOwnerTest.java, NeutralPlacementFixedPointCompositionTest.java.
- **잔여/회귀 위험:** Exact identity vs equal-value aliasing and stale source/action reuse are covered by focused additions/withdrawal/restoration/foreign-reference tests. Actual OFF W1/W7 and planning-only consumer on frozen r17 are pending; goal remains active and r16 W1>40 failure is not replaced by tests alone.

## r17 actual pair: W1 41.293s / W7 17.385s (목표 미달성)
- Frozen r17 JAR SHA256 `d638d4bee086be5586dc9f95193730309e8b7425b780f451d7dabb108d3677af`; source snapshot contains7610 source files with verified manifest. Both fresh one-shot actual Docker OFF runs completed successfully with identical respective r16 analysis fingerprints, selector/runtime/workload0. W1=41.293271496s exceeds40; W7=17.384545232s passes. Pair evaluator correctly fails; failure checkpoint recorded, goal remains active.
- Exact cleanup: W1 coordinator+1 worker and W7 coordinator+7 workers absent by name and captured ID; prestart absence, network validation, and all stage leases released. Evidence r17-actual-pair-summary.json and declared accept-r17-w1/w7 directories. No same-candidate timing retry or best-run selection.
- Prelaunch W1 local invocation mistakes (watchdog argument separator/path typo and missing exported frozen source root) were rejected before any remote action. Local provenance refusal logs remain in local-preflight-refusal/ and watchdog-cli-invocation-note.txt; only one actual remote attempt occurred.
- Regression evidence archived r17-scoped-surefire/ plus219-test summary; no entire-suite claim (known preexisting protected ALS exclusion and6 policy skips remain).
- Next r18 scope: preserve exact Native signature bytes while removing intermediate bindings serialization, and process dependency-first SCCs once while retaining complete AND eligibility and recursive refinement. Architect gives induction argument; focused behavior locks and independent code review required before freezing. Optional lazy default-overlay dedup is under separate design review, not yet implemented. Actual r18 pair initialized but not built/run.

## r18 Native redundant-work gate (검증 완료 / 실제 측정 대기)
- **구현:** One dependency-first maximal SCC pass; recursive eligible AND refinement unchanged. Exact-capacity signature construction collects each child signature once and uses one StringBuilder with byte-identical legacy text. Default topology edges are already unique, so query-local dedup is allocated only on the first dynamic overlay, seeded with every prior default edge, then used for all subsequent rows. Synthetic ground, template/generated roots, exact references, reindexed handles and full alternative inventory stay unchanged.
- **행동 잠금:** Genuine old-SCC RED:3 invocations versus expected2; exact signature old-expression parity before/after. Added adversarial refined A/B/U/G grounding in both insertion orders, 0/1/multi/long Unicode signature and cached identity, full/pinned static-dynamic-static overlays plus synthetic-first. Existing reindex, dynamic collision metrics and self-pin tests retained.
- **검증:** r18-scoped-regressions.json/log and r18-scoped-surefire/:223 tests,0 failures,0 errors,6 existing policy skips; known preexisting protected ALS exclusion unchanged. Independent critic final implementation CLEAR; git diff --check passed. Primary Maven lane released, sources stable.
- **수정 파일:** NativePlacementContinuity.java and new NativePlacementGroundingAndSignatureTest.java. Untracked test is intentionally included in frozen source/package provenance via rsync and full source manifest. No new dependency, candidate guard, privacy relaxation, selector deferral or runtime fallback.
- **잔여:** r18 actual OFF W1/W7 and fresh planning-only consumer not yet measured; latest r17 still fails W1 40-second threshold.

## r18 actual pair / fresh actual W1 profiling (목표 미달성)
- Frozen r18 JAR `f5c7d505be7bd756613a44d7ef633d9eef08d3c7f3105627177f71406c82e9f8`; all7611 source checksums verified, including new GroundingAndSignature test. Scoped223 tests and independent review passed.
- Actual fresh one-shot OFF pair: **W1=47.512868683s, W7=18.021716941s**. Both succeed with identical respective r17 fingerprints; selector/runtime/workload0, attempt1, full exact cleanup and lease release. W1 worsened from41.293s; no performance improvement claimed, no same-candidate OFF retry. Pair fails40.
- Small allocation/scheduling changes are not sufficient evidence of actual latency improvement. Next one-shot actual W1 metrics ON+JFR diagnostic `profile-runs/w1/trace-r18-w1` uses the same frozen JAR and120s diagnostic cap, not acceptance. No workload execution. Current bottleneck evidence will choose the next representation reform rather than extrapolating the older local r16 sample.

## r18 actual profile / r19 canonical-list and serialization sharing (진행중)
- **관측:** Same frozen r18 actual W1 ON+JFR diagnostic completed48.33674509s; analysis48.288177447s, main allocation38.213GB, fingerprint unchanged, workload0 and exact cleanup. ON is diagnostic, not acceptance. Exclusive phases: direct binding9.982s, closure replay6.628s, relocation5.347s, grounding4.893s.21136proof queries had0repeated exact contexts: no additional query-result cache justified.
- **원인/범위:** Direct binder copied the Native proof's already-canonical immutable list, losing its SharedCanonicalList marker and triggering redundant canonical ordering. Native equal immutable proofs rebuilt identical large signature strings outside the existing structural serialization cache. These are representation/boundary costs, not grounds to remove valid candidate branches.
- **r19 변경:** Reuse the same immutable canonical immediate-binding list at the direct binder. Native proof normalizedSignature consults the existing PlacementIdentity structural cache before unchanged exact serialization and uses existing rememberSignature on a miss. Only String text is shared; proof/binding/source/action objects and all runtime/privacy/all-writer validation remain unchanged.
- **행동 잠금:** Actual production binder regression genuinely failed on list identity before deletion; Native67tests0fail/error1policy skip after. Signature regression genuinely failed before production change:3tests1failure on equal-copy text identity. Added hash collision, metrics ON, zero/insufficient budget, Unicode/punctuation exact parity, authority-object preservation and scope-end/start tests; focused/full verification pending.
- **독립 검토/위험:** Architect CLEAR correctness. Existing64M character admission limit is not a hard heap/object cap: identity cache retains equal copies until analysis end. Previous11.1M serializedChars excluded Native strings, so is not headroom evidence. Latency/retention require new actual measurement; no speedup claimed yet.
- **수정 파일:** NeutralPlacementGraphBuilder.java, NativePlacementContinuity.java, NativePlacementContinuityTest.java, NativeProofSignatureCacheTest.java. Plans and RED evidence in /home/mchoi/w1357-diagnostics/fused-20260927. Goal remains active; latest OFF W1=47.512868683s fails40.

## r19 freeze gate (검증 완료 / 실제 측정 대기)
- Focused signature/Native/cache-scope77tests and full21-suite235tests passed:0failures,0errors,6existing policy skips in full suite. Known preexisting protected ALS exclusion unchanged; selected5loop methods ran. Fresh XML archived r19-scoped-surefire and summary r19-scoped-regressions.json.
- Architect and independent critic CLEAR. Source delta vs frozen r18 is confined to direct immutable-list reuse and existing exact serialization-cache reuse plus regression tests. No candidate reduction, privacy relaxation, all-writer omission, selector deferral or runtime fallback. Cache-retained-object risk remains measured, not assumed away.
- Next frozen r19 one-shot actual OFF W1/W7; same-JAR planning-only consumer required on a qualifying pair. No workload execution authorized. Latest actual r18 W1 still fails40.

## r19 actual OFF pair PASS / completion audit pending
- **실측:** W1=37.768554866s, W7=16.960756383s. Both are fresh one-shot actual Docker OFF runs on identical frozen JAR36314e03ef547661c6a4cdf749a6504b4a7cd786863e837a991b85f57db0c46e. Strict evaluate40_current.py PASS; exact respective analysis fingerprints equal r18. Selector/runtime-program/workload counts all0, attempt1, success, network validation, exact all-node name+ID cleanup and stage lease release confirmed.
- **증거:** r19-actual-pair-summary.json; r19-evaluator.log; acceptance-runs/w1/accept-r19-w1 and w7/accept-r19-w7. Frozen7612source manifest verified;235scopedtests0fail/error6policy skips, independent implementation CLEAR. Prior failed candidates retained; no same-candidate OFF retry or fastest-run selection.
- **캐시 계약 정정:** Code default is64Mi admitted characters; the unchanged existing actual harness already overrides it with -Dsysds.fedplanner.signatureCache.maxChars=536870912 (512Mi characters), in both r18/r19. No budget/config increase was introduced by this reform. Neither limit is a hard total retained-object/heap bound. One end-of-analysis heap snapshot is not a peak/live-retention measurement.
- **남은 완료 게이트:** same-JAR plan-r19 planning-only consumer is running, independent provenance/lifecycle audit pending, and one metricsON+JFR diagnostic will check allocation/heap risk (not acceptance/retry). Goal remains active until these audits finish; no workload/896/reference generation is authorized or performed.

## 최종 r19 완료 감사 — 달성 / 안전 경계 유지
- **결과:** same frozen JAR 실제 OFF W1=37.768554866s/W7=16.960756383s로 모두40초 이하. Same-JAR planning-only compile succeeded63.128017056s, initialCompilation1/programEmission1/runtime not_started/workload0. No workload,896campaign,reference generation.
- **독립 감사:** r19-independent-completion-audit.md,113assertions0failures. Full7612source manifest, exact frozen/overlay/coordinator/remote hashes, fixedADULT DML/shards, resource/privacy/LAN contract, attempt1/prestartabsence, allnode name+ID cleanup/leases verified. Failedpriorruns andREDlogs retained.
- **회귀/정적 검증:**21suites235tests0failures0errors6existingPUBLIC policy skips; known preexisting protectedALS exclusion explicit, other5loopmethods ran. Maven package/compilation passed, git diff --check passed, architect+independent critic static review CLEAR. No separate lint/SAST plugin is configured in this POM; no whole-repository suite claim.
- **할당/heap:** r18→r19 exact main analysis allocation38,212,671,136→34,717,496,776 bytes(-9.147%); Native signature sampledallocation2,329,334,528→155,193,584(-93.337%); observed maxpostGCheap1,288,575,488→890,335,168(-30.905%). Only9signature/canonical counters changed out of100; full proof/query/relocation/pass counts and fingerprints identical. ON44.865505126s is profiling, not the OFF acceptance timing.
- **한계/잔여 위험:** W1 one-shot margin2.231s is not a guarantee over all future runs. Unforced receipt-time heap snapshot increased12.867%; it is not a peak/post-GC live-set measure. No cache-key count/heapdump/forcedfullGC census; no hard retained-object/heap bound claimed. Existing actual512Mi-character cache admission setting unchanged. Allocation/observedGC evidence does not trigger the scoped retention WATCH.
- **주요 단순화:** immutable canonical relation handoff instead of copy+sort; exact shared text instead of repeated serialization; earlier gates introduced boundary-relevant indexing, exact adjacent-context product reuse, changed-fact recomputation, complete proof topology reuse, and repair of generation/publication ownership. Necessary output-sensitive products and recursive AND refinement remain; no arbitrary candidate cap, privacy relaxation, all-writer omission, selector deferral or runtime fallback.
- **보존할 증거:** r19-completion-evidence.json, r19-actual-pair-summary.json, r19-scoped-regressions.json +archivedXML, r19-independent-completion-audit.md, planning-consumer-runs/plan-r19, profile-runs/w1/trace-r19-w1, frozenr19build. No further timing retry or implementation change is needed for this objective.

## 새 목표: LAN 전체14 workload × W1/3/5/7 search-space20초 (진행중)
- **범위:** 사용자 `lan_w1357` 응답으로 ML10+p1/p2+SliceLine ADULT/COVTYPE의56조건 확정. 각0<Tspace<=20, 동일 frozen JAR, metricsOFF, selector/program emission/workload0. 평균/일부조건 통과로 완료하지 않는다. 실제 학습/896campaign/reference생성/입력축소/후보cap/정책완화 금지.
- **계약/증거:** 별도 D=`/home/mchoi/w1357-diagnostics/all14-searchspace20-20260927`; frozen56inputmanifest SHA bdcfdd5eff59d7d74efd3e5f32f713f1322ef327e9c8e6a535903f90692d2a22. fail-closed evaluator+local9tests PASS. Source/JAR/classpath/P2전용flag/일회성/환경/netem/전nodecleanup 게이트 독립 검토 중. 이전r19완료는 이전40초2조건 목표에만 유효.
- **첫 수정:** DirectSupportIndex는 매 changedwave의 전체 support-clause adjacency 재탐색을 post-boundary changedowner의 모든 고정row만 재탐색하도록 교체. new support+removed old pairs로 기존old/new union 보존. exact row key/owner/size invariant, additions/deletions SCC rebuild와 unsettled/Native revision 유지. 후보/proof/authority 자체 불변.
- **검증:** 기존2suite 행동잠금, 새4differential test seam RED 후6suite59tests0fail/error/skip. 처음RED의 profile fixture 오류는 수정 후 missing-new-seam4errors로 재검증했다(기존 semantic bug라고 주장하지 않음). Architect 독립review CLEAR. baselinefrozenr19대비 code변경은 index/호출부/unused comparator 삭제. 수정파일 Builder+DirectedDirectClosureDirtyConeTest.
- **잔여/위험:** Full56 actual 및 보호된ALS 포함 전체workload건전성 미검증. 기존logregW1=37.76855/W7=16.96076;20초미달성. indexscanner CPU2.476%만 근거, 단독전체목표달성추정 금지. 새canonical sidecar는 별도 executor가 행동잠금/검증 중이며 retained descriptors heap WATCH 필요. Native/proof정확성/동일JARplanning-only14consumer/독립완료감사까지 필요.

### 전체14 W1 r19 기준선 완료 / SliceLine correctness 선행 이슈
- **실측:** PCA2.065231291,ALS2.532887148,KMeans4.717713683,LM0.918393884,logreg37.161511091,L2SVM8.206370172,StepLM8.962935413,GLM39.683343308,GNMF2.132735499,GMM6.084583455,P1 27.138205683,P2 2.13010044초. 9개<=20/3개초과이며 다른worker수/새후보전체통과를 뜻하지 않는다.
- **SliceLine ADULT/COVTYPE 실패:** 같은 canonical `slicefinder_core.dml::slicefinderX` line108:24 BinaryOp `b(*)`, recompile영역 PRIVATE_AGGREGATE에서 `No privacy-safe physical placement`. search-space완료시간없음/실패이지0초가 아니다. parserprepareSearchSpaceOnly→buildDetachedAnalysis→closePrivacyDomainsMeasured1748. 원인 분석을 debugger에 읽기전용 위임; privacy/TRTW/재컴파일CP-FOUT 금지조건 유지.
- **환경/정리:** immutable r19 JAR36314e... 실제 Docker OFF W1 단일시도14건, 모두 cleanup_confirmed/lease_release=true. 실패2건도 coordinator_rc1 및 receiptplanningfailed/rawlogs보존. No runtime/reference/896실행. Newacceptancecandidate 미선언, evaluatorFAIL.
- **Harness 이슈:** 초기batch runID의 P1/P2 underscore가 initializer허용문자와충돌해10ML후 P1생성전 로컬중단. v1원본/원본batchprefix/실패증거보존. runID hyphens+명시적frozenSHA+exactallhost정리게이트로수정. 이미완료한10건 root/candidate/byte/lifecycle 재검증 뒤 미시도4건만명시적resume(criticCLEAR,3testsPASS). 완료cell재실행없음. baseline42추가r19셀은 실패목표후보이므로일단보류;최종새JAR전체56gate는변함없음.
- **기존ALS 테스트 명칭 정정:** 제외돼있던 `protectedAlsLoopRetainsWidenedNativeSourceAndAllReachingWriters`는실제로 `alsCG`4x2/rank2를호출하며이번canonicalML`als`50000x128/rank10과다르다. 실제canonicalALS는위2.533초성공했고모든W에서최종검증대상으로포함한다. 기존비정본alsCG실패를canonicalALS누락이라고보고하지않는다.
- **새 bounded변경 검토:** DirectSupportIndex와canonicaltext sidecar는architect+independentcritic correctnessCLEAR. Sidecar는현재exact-two clauseunion만사용하는데모든multi-elementcanonical list에보관하므로retainedheap WATCH. SmallsubList parentbacking보존/nullablelocalmiss eager구축도실측필요. Root finalscopedregressions 실행중,새freeze/속도개선아직미검증.

### r1 실제 성능 반증 / SliceLine 첫 소실 지점 확보
- r1 frozen JAR70fd1adafa1507b7b763f906ab492ac1c927a50c63176478be07c6ff51e16557,7612sourcechecksums검증/manifest23b037f4c730aec7d249ad5ec9a14c9bc1ca46f5047502a930d3cdf9b904fef0.25scoped suites292tests0fail/errors6PUBLICskips, finaldirectslotinvariant검증포함.
- 새 실제OFF W1: logreg40.01512227,GLM39.386695725,P1 29.28161465. 세cell모두20초초과. 각각같은canonicalinput r19기준선과analysisFingerprint일치, attempt1/fullcleanup/leases성공. 속도개선으로채택하지않는다. Rawunforcedheap logreg+1.75GB/GLM+1.75GB이나이는postGC/peak/live-set아니다.
- r1 동일frozenJAR ON+JFR W1logreg45.031487549완료, candidatecounters/runtime0/cleanup/leases정상. JFR+GClog와receipt를 D/runs/profile-r1-logreg-w1 에수집. 현재기록된counter/CPUline/할당/postGC분석을위임했으며OFF시간과혼용금지. r19이전프로파일과프로그램fingerprint차이가있어inputDML차이도확인필요; 이번14기준선OFF↔r1OFF동일fingerprint는확인됨.
- SliceLine r19 ONaudit+trace/exportDiagnostics: D/runs/audit19-sliceline-adult-w1, candidate-space-51.jsonl hop1566. 초기FED/FOUT FULL이publicationphysical단계 inputDomains[[ROW],[FULL]]에서CP/LOUT UNSUPPORTED_ALIGNMENT로교체됨. rowsA/B32561,colsA/outputUNKNOWN,colsB1;그후privacy가정상적으로CP를거부한다. 오류를privacy완화로덮지않는다.
- 제안검토중: runtime의ROW축정렬/sameworker directremote연산만명시증명하는ROW,FULL capability+continuity지원. Nonaligned broadcastSliced는보호된LHS수집위험이있어허가근거불가. RHSFULLNx1을NxKunknown결과의exactgeometry로잘못복제하는위험을root/critic가검토중. 아직production수정없음; debugger새회귀RED테스트한정Mavenlane허용. outputdynamicprecision/endpoint/단일interval/알수없는축/역순/다중range음성테스트필요.

### r1 프로파일 반증 / r2 표현 최적화 (검증 진행중)
- **문제/실측:** r1 ON/JFR main allocation 34.212GB, 이전 r19 ON34.717GB 대비 -1.455%에 그쳤고 ON45.031s/OFF40.015s로 목표20초 미달성. 관측 post-GC maximum/last heap0.890GB→2.446GB로 크게 증가했다. 이는 class별 live census가 아니지만 모든 canonical list의 descriptor 보유를 유지할 근거가 없다.
- **비교 정정:** 이전 r19 ON과 새 r1 ON은 stamp 주석/result write path 및 format 공백이 달라 script SHA/fingerprint가 다르다. 98/100 metric counters가 같아도 byte-identical fingerprint라고 쓰면 안 된다. 보고서/JSON을 정정했다. 새 canonical r19 OFF↔r1 OFF↔r1 ON은 script647180c3.../fingerprint3b265bdb...로 일치한다. 기존 OFF 실패 증거/프로파일을 보존한다.
- **canonical 해결:** ordering descriptor를 실제 소비되는 multi-clause support list와 exact2 union으로 한정. generic proof/binding/reference/realization/transient lists는 value-only. subList는 value/key 범위를 복사하여 부모 backing-array 보유를 피한다. 양쪽 누락 descriptor는 하나의 merge-local context만 사용; first-authority, exact duplicate, collision fallback 및 legacy UTF-16 순서 불변. global/nested cache 추가 없음.
- **Native 해결:** r1 JFR에서 cyclic dead-alternative pruning이 main CPU8.86%, sampled allocation3.405GB를 차지. query-local exact state→dense ID와 primitive reverse adjacency/live/removal 배열로 중첩 map/set/boxing을 줄였다. 모든 원래 state/dependency/slot 유지, owner+alternative object identity 제거 의미와 원래 list-size live count, 반복 dependency per-slot dedup 및 counters 보존. SCC/AND grounding, persistent memo, source/action/witness 권한 불변. obsolete DependentAlternative record 삭제.
- **행동 잠금:** dense 변경 전 5 tests(300 seed 고정 differential graph × metrics ON/OFF 포함) PASS; 테스트 oracle는 frozen r1 map/set 알고리즘. 이후 explicit duplicate-object legacy oddity 및 colliding witness hash 테스트 추가. Canonical은 broad-r1에서4 new tests 중3 expected assertion RED; 잘못된 constructor fixture 기대는 reviewer 지적으로 바로잡았다. 다른 agent의 일시적 미완성 SliceLine test 때문에 발생한 compile 실패 로그도 보존하고 PASS라고 주장하지 않았다.
- **최종 focused 검증:** Candidate canonical25 + chunk2 + shared hash3 + Native prune7 =37 tests,0failures/errors/skips. canonical-narrow-final-xml 및 source hash 보존. Independent critic는 두 production 변경 CLEAR; root가 별도 JSON review 기록 보존. `git diff --check` PASS.
- **수정 파일:** PlacementAnalysis.java, CandidateRealizationCanonicalizationTest.java, NativePlacementContinuity.java(prune만), NativePlacementPruneOwnerTest.java. 기존 dirty-tree와 별도 SliceLine 신규 RED tests 보존.
- **남은 검증/위험:** scoped integration, fresh r2 frozen build 및 OFF latency/ON retention 미측정. 실제20초 개선으로 채택하지 않는다. 전체56 및14 same-JAR planning-only consumers/완료감사 미완료. SliceLine는 current source의 exact input authority와 dynamic output precision, 재귀 child 및 CTABLE-derived actual LHS를 모두 증명해야 하며 아직 production 수정 전이다.

### r2 동결/실측 — 20초 미달성, 절감 효과의 상쇄 확인
- **동결:** `/grid/3/cofee-lm-sweep-mchoi-20260914/w1357-all14-space20-r2-build-20260927`, JAR SHA256 `8f9f00c9ef85a5d6514f4bdc0dbb1679ecfa4fc19cbcf522843eb5b8d8c84f1c`, source manifest `e202bf79b34f572316e6d5dfa17f15a74e678715d1728c78e2abe5366a760d44`,7614source files 빌드 전후 전체 checksum PASS. 별도 frozen clean target에서 Maven package PASS.
- **검증:** performance-only25scoped suites298tests0failures/errors6기존PUBLIC정책 skips. 이 결과는 r2 snapshot에 대한 것이며 이후 primary의 SliceLine 부분 수정까지 PASS라는 뜻이 아니다. 별도 SliceLine14tests는 당시 expected RED였다.
- **실측:** 새로운 OFF W1 logreg40.241289715s, 동일 JAR ON/JFR45.250486727s. 모두 attempt1/receipt-valid/runtime0/exact name+IDcleanup/모든 leases release. 기준 r19 OFF37.161511091s/r1 OFF40.01512227s 대비 개선을 주장하지 않는다. 추가 GLM/P1/나머지56측정은 알려진 실패후보이므로 아직 진행하지 않았고 최종56조건 완료 게이트는 유지한다.
- **프로파일:** r1과r2의 canonical DML SHA647180c3.../fingerprint3b265bdb...는 일치.100metrics 중99개 동일, signatureIdentityCacheHits만 증가. Grounding exclusive4.890s/5.483GB→3.989s/3.175GB로 줄었으나 direct binding8.315s/6.499GB→8.880s/7.952GB, relocation5.020s/5.148GB→6.147s/5.486GB로 상쇄됨. 전체 allocation/JFR main CPU/post-GC 분석은 별도 진행중; unforced heap snapshot은 live/peak가 아니다.
- **후속 방향:** descriptor 보유나 소규모 collection 교체만으로2배 단축을 추정하지 않는다. architect는 general realization merge가 그룹 중복 여부를 알기 전에 모든 clause structuralHandle을 만들고, 재정렬/전체 support 문자열을 eager 구축하는 경계를 조사중. prefix-safe lazy ordering과 group-first complete union을 실제 호출부 증거에 따라 검토한다.
- **SliceLine 잔여/위험:** ROW×FULL oracle+paired selected-input validation은 primary에 부분 구현중이며 전체 성공 아님. Generated proof의 기존 dynamic-retention OR가 새 validator를 우회할 위험을 root review가 발견해 차단 요청했다. CTABLE exact row proof는 distinct endpoint1을 physical partition1로 오인하면 안 된다. Actual FULL→RESHAPE(dynamic ROW)→CTABLE 경로에는 별도 cardinality authority/보존 증명과 공개 API recursive tests가 필요하며 CTABLE production 확장은 아직 금지/gated 상태다.

### 최종14 planning-only consumer 도구 준비 (실행 전)
- D/planning-consumer-tools에 별도 initializer/runner를 구성, 출력은 planning-consumer-runs로 OFF56 inventory와 격리. canonical inputs/stage/P2 flag, frozen JAR pin, source/probe/remote overlay hashes, resources/netem/locks/leases/exact cleanup 재사용. 각14workload에 final same-JAR1건씩 소비 검증해야 하며 아직 실제consumer 초기화/실행 없음.
- Probe의 planning-only branch에 explicit workloadExecutionCount0만 추가(compile success/program emission1 뒤, 기존 runtime gate 앞). Planning-only에는 selection이 허용되므로 selector0을 추론하지 않는다. Missing/bool/nonzero workload count는 runner가 거부한다. 다른 runtime mode와 search-space branch는 변경하지 않는다.
- Python compile 및4local synthetic tests(all56 temporary fixture 포함) PASS, independentcritic CLEAR. Resource reason 문구를 'no runtime workload execution/output generation'으로 정정해 program emission1과 모순 없도록 하고4tests 재실행 PASS. 원래 reviewed hash/log 보존. 최종 aggregate14개가 모두 정확히 동일 candidate JAR/attempt1/strictreceipt/fullcleanup임을 별도 완료감사에서 검증해야 한다.

## SliceLine local-index authority literal hardening (진행중)

- **상태:** strict local-rix recognizer only; CTABLE placement integration remains blocked and unimplemented.
- **증상/원인:** the pre-integration recognizer compared a `double` literal with inclusive
  `Long.MAX_VALUE`; that bound rounds to `2^63`, so FP64 `2^63` could cast/saturate to a false INT64
  authority. It also trusted RAND HOP dimension metadata without binding direct RAND row/column
  parameters.
- **해결:** `NativePlacementContinuity.integralLiteral` now preserves exact INT64 values, accepts FP64
  only for finite integers in `[-2^63,2^63)`, and rejects unsupported/nonnumeric literal types. The
  strict rix matcher now requires literal RAND rows=1 and literal positive columns equal to RAND HOP
  width; constant-one still requires sparsity one. Cell multiplication remains `multiplyExact`.
- **수정 파일:** `NativePlacementContinuity.java`,
  `AlignedRowSingleFullContinuityContractTest.java`, diagnostics
  `sliceline-broadcast-correctness-plan.md`.
- **검증:** shared-flock clean focused run 20 tests: 19 pass, 0 errors/skips, sole expected unfinished
  CTABLE public-chain RED. Evidence:
  `all14-searchspace20-20260927/runs/sliceline-broadcast-rowfull-v7-helper-hardening/maven-clean.log`.
  The preceding compile-only missing-import attempt is retained separately in `maven.log` and is not
  acceptance evidence.
- **잔여 이슈/위험:** CTABLE needs one coherent selected physical-layout constraint proving exact cix
  vector coverage and BROADCAST physical multiplicity through the actual FULL+BROADCAST->RESHAPE
  chain. No endpoint-count inference, privacy relaxation, fallback, or runtime change was added.
- **의사결정 근거:** fail closed at planner authority derivation; the helper remains unused until the
  selected-layout design and public negative fixtures pass independent review.
