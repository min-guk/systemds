# Placement bottleneck continuation — 2026-10-09

전일 상세 이력: `SESSION_ISSUES_2026-10-08.md`. 기존 검증 소스는 commit `50855b5df4c6a2312c3a12ee8a31415a9d96aa27`로 origin/main에 게시 완료했다. 아래 후속 수정은 아직 미게시다.

## 캐시 적중 후에도 반복되는 proof materialization / pruning table clear (진행중)

- **환경/재현**: `/grid/3/cofee-lm-sweep-mchoi-20260914/placement-bottleneck-drive-20261008/run_diagnostics.py`; 실제 실행은 `scripts/fedplanner/run_LAN_docker.sh`만 사용한다. pinned image, full LogReg/GLM, PRIVATE_AGGREGATE X, 4CPU/16GiB/10GiB heap, JFR55초/watchdog60초 그대로다.
- **관측**: v4 LogReg OFF JFR에서 2,946 main samples 중 IdentityHashMap.clear 175개, 그중 동일한 pruneDeadAlternatives 호출 stack 162개. NativePoolWitness.equals 251개, support instantiation inclusive 6.99%, authority text factory 9.40%다. GLM의 joint canonical inclusive는 13.14%이며 ListN.get 185개 중 105개는 canonical cursor의 pieces 접근이다. 두 workload 모두 아직 timeout이고 completion receipt가 없다.
- **v4 상태/검증**: complete semantic proof + exact owner/relocation-consumer identity를 사용하는 publication memo. 695 tests, failure/error 0, PUBLIC-only skip 1. 독립 APPROVE. JAR `32c6f2f74682481fbffd47948499f0cfbf50119659c695ae39172399ffeb6042`. 상세 LogReg publication 적중은 2,360,553요청 중159,396으로, 이 변경만으로 병목이 해소되지는 않았다.
- **v5 해결 방향**: (1) 빈/단일 owner에는 duplicate identity map이 불필요하므로 생략하고, 큰 owner 이후 작은 owner의 clear는 table 크기를 제한한다. (2) SupportMemoEntry에서 정확한 suffix 순서를 한 번 정하고 scalar 길이만 보존해 같은 root의 proof를 lazy 생성한다. byte estimate가 다시 text를 강제 생성하지 않게 한다. exact/dynamic 병합은 기존 stable sort + structural distinct와 같은 결과를 내며 모든 dependency footprint를 보존해야 한다. (3) immutable canonical text의 private pieces를 defensive-copy Object[]로 바꿔 megamorphic List 접근을 제거한다.
- **회귀 잠금**: map-clear 전9PASS, array 전5PASS. Materialization 테스트는 fixture의 잘못된 FED/LOUT native-lineage를 FED/FOUT으로 고친 뒤 8개 중7개 의도한 실패를 확인했다. 초기 fixture 오류를 production failure로 해석하지 않는다. UTF-16/length, root rebind collapse, merge first representative, dynamic-empty footprint, memo LRU를 검사한다.
- **수정 파일**: NativePlacementContinuity, PlacementAnalysis, NativePlacementPruneOwnerTest, NativeSupportMaterializationOptimizationTest, NormalizedTextLiteralComparisonTest 및 private pieces reflection 테스트2개.
- **측정 제한**: v3 GLM diagnostic 중 다른 worktree의 Maven 작업을 관측해 uncontended acceptance timing으로 쓰지 않았다. 외부 프로세스를 중단하거나 변경하지 않았다. v4 OFF sampled cgroup peak는 LogReg9.177GB/GLM4.403GB로, 진행 지점이 다른 미완료 실험이므로 메모리 개선/회귀율을 단정하지 않는다. fresh baseline 재실행 중이다.
- **잔여 이슈**: 전체 planning 완료 및 이후 DP SUFFIX cut/총 planning speedup은 아직 검증되지 않았다. v5 통합 green/review/Docker가 남아 있다.
- **잠재 회귀/감지**: lazy length mismatch, rebind 후 정렬/중복 제거, dynamic dependency footprint 누락, private array 노출/aliasing, 작은 owner로 바뀔 때 중복 identity 제거 의미 변경. frozen differential oracle와 실제 lifecycle tests, fresh 전체 selector, 독립 review로 검출한다.
- **의사결정 근거**: oracle/runtime/privacy 규칙과 합법 후보군은 그대로 두고 중복 계산 및 immutable 표현만 바꾼다. timeout 증가, 새 public flag, runtime fallback, dependency 추가는 없다.

### v5 통합 승인 및 fresh package (해결, 실제 성능 검증 중)

- **통합에서 발견/수정**: private `CanonicalText.pieces`를 Object[]로 바꾸면서 test reflection 4파일의 기존 List cast도 함께 바꿨다. 독립 reviewer가 추가 누락 4곳을 찾아 package 전 수정했다. 테스트의 assertion/성능 정책은 완화하지 않았다.
- **실제 구현 범위**: public/일반 NativeContinuityProof constructor의 eager/warm-cache behavior는 유지하고, trusted same-root template constructor에만 lazy text/정확한 scalar length를 적용했다. 같은 proof가 materialize되면 기존 String identity와 rope release 동작을 유지한다. Memo template의 equality/hash는 기존 3필드 기준이다. exact/dynamic 병합은 두 footprint를 합친 뒤 empty fast path 또는 left-first stable merge + proof.equals distinct를 사용한다.
- **검증**: fresh Maven **737 tests, failures/errors 0, skip1(PUBLIC-only)**, BUILD SUCCESS 2026-10-09 00:10:47. JAR SHA `7660312ab9fb6d3229629b87c520d8e363af32f8e5f4509fca5b5363e24fcf6d`. Native 및 canonical-array 두 slice 독립 APPROVE. `candidate-v5`에 source/JAR/patch/보고서 봉인 후 Docker stage 전체를 다시 추출해 stale class를 배제했다.
- **기준선 재검증**: published baseline fresh OFF JFR는 LogReg/GLM 모두60초 timeout, receipt 없음, 자체 container 삭제 확인. 자체 compile/JFR parse와 겹치지 않았고 launch/mid/end 점검에서 외부 Maven도 관측하지 않았다(전체 호스트 독점 검증을 의미하지는 않음). Baseline GLM joint canonical은45.05%, LogReg support instantiation10.35%; 이 표본 비중 자체는 속도 개선율이 아니다.
- **다음 단계/잔여**: v5 OFF Docker 실행 중. 내부 proof graph가 dependency-closed임을 이용한 cyclic pruning dead-seed 사전검사 생략은 설계 검토만 승인됐으며, v6 회귀 테스트부터 별도로 진행한다. 외부/불완전 graph의 기존 conservative entrypoint는 유지한다.

### v5 측정 및 v6 complete-graph pruning 검증 (부분 병목 해결, 전체 완료 미달)

- **v5 OFF 결과**: support instantiation inclusive 표본 비중은 fresh baseline 10.35%에서 0.03%로 줄었고, canonical comparison은14.39%에서2.49%로 줄었다. 큰 IdentityHashMap.clear hotspot도 사라졌다. 다만 LogReg/GLM 모두60초 timeout이므로 이는 전체 속도 개선율이 아니라 부분 경로 변화다.
- **v6 변경**: 내부 proof graph builder는 모든 dependency state를 생성하며 cache-negative state도 empty counter에 포함한다. 이 certificate로 cycle dead-pruning의 초기 missing-dependency/empty-state 재탐색을 생략한다. 외부/불완전 graph용 one-arg 경로는 conservative scan을 유지한다. Owner-scan 계측 의미는 동일하다.
- **검증**: red6tests 중2개 의도한 missing-overload 실패 후 구현. fresh **738 tests, failure/error0, existing PUBLIC skip1**, independent APPROVE. JAR SHA `5b43b66fb3ab23bfd72510ce34935f53b73c2e5889872b7734d13edd96037ca8`. 실제 builder cycle/cached-negative 및 CountingMap 초기 containsKey 미호출을 검증한다.
- **v6 상세 실험**: 두 workload 모두60초 watchdog/no receipt/no observedOOM, own container cleanup 완료. LogReg direct closure1045회 중stable849/full22, proof graph27,305개, publication2,542,105요청/170,633memo hits. Support materialization 표본은0%지만 authority factory8.61%, 상세 signature-observer의 String hash26.99%가 남는다. ON/OFF 표본을 동일 timing 비교로 쓰지 않는다.
- **다음 v7 계획**: native proof에서만 유도한 trusted typed marker로 이미 retained된 동일 clause를 publication 전에 확인한다. arbitrary String authority는 기존 경로를 유지한다. 전체 4필드/owner 및 relocation consumer identity/output metadata 일치만 hit; collision bucket에서 hash만으로 판단하지 않는다. staging/mixed/revision 경로, String/hash/order/rope lifecycle 보존을 테스트부터 잠근다. 무조건 lazy String으로 바꾸는 안은 materialization이 다른 필수 경계로 이동하고 rope retention을 악화시킬 수 있어 채택하지 않았다.
- **잔여/위험**: 전체 planning·DP 완료는 아직 미검증. v7 marker가 live key 수에 비례해 anchors/bindings를 추가 보유할 수 있으므로 전역 interner를 금지하고 actual hitrate/메모리와 함께 검증한다. 후보/합법성/privacy/runtime 규칙 변경은 없다.

- **v6 OFF 공유 호스트 제한**: `candidate-v6-jfr` 도중 unrelated worktree `fed-oracle-native-20261008/workspace-preserved`의 Maven test-compile(PID3202062)을 관측했다. 해당 프로세스/다른 container는 변경하지 않았다. `evidence/v6-off-external-contention.json`에 보존했으며 이 run은 진단용이고 uncontended acceptance timing이 아니다.
- **계측 해석 보정**: directClosurePasses는 전체 프로그램 fixed-point round 수가 아니라 ready SCC/dependency wave 수다. v6에서 wave당 평균 약11.3개 fact만 재계산했고764,616개는 재사용했다. 1,045라는 수만으로 oscillation이라고 판단하지 않는다.

### v7 trusted native authority / publication 이전 exact coverage (검증 중)

- **문제/원인**: grounded clause가 이미 emission에 있어도 새 proof authority 문자열과 publication을 생성한 뒤 최종 `containsExact`에서 동일성을 확인했다. 기존 bounded publication memo의 적중률만으로 이 낭비를 없애지 못했다.
- **해결**: native proof 자체에서만 String/hash와 네 immutable 필드 descriptor를 동시에 유도한다. String 생성자와 generic text factory는 unmarked다. Emission-local output-key/structural-hash collision index에서 완전한 값·owner/relocation consumer identity·clause binding·pool/exactness를 확인한 경우에만 이미 retained된 clause로 대체한다. Required input/layout legality 및 모든 dependency footprint 수집은 여전히 먼저 실행한다. All-covered 상태를 unproved와 구분해 staging을 다시 만들지 않는다.
- **회귀/통합**: 초기 fixture의 null candidateGenerator 때문에 `clearBuildState`가 NPE를 냈다. 테스트에서 publication memo만 비우도록 수정한 뒤8개 모두 의도한 old-binary 실패(7 missing factory/overload,1 early-hit counter)를 확인했다. 초기 구현 full package **746 tests, fail/error0, skip1**, BUILD SUCCESS00:29:12. Architect independent implementation review APPROVE.
- **검토 후 보강**: descriptor의 List.copyOf는 이미 canonical인 immutable binding list를 복사해 cached hash/identity를 잃으므로 공유를 보존한다. 생성 뒤 global signature cache가 warm된 proof는 기존 String을 먼저 채택해 rope 재구성을 피한다. durable/native exact/dynamic output matrix, collision bucket의 후속 exact hit, live mixed result, revision/cold differential 테스트를 추가 검증 중이다.
- **메모리/위험**: descriptor는 proof/rope/context를 보유하지 않지만 live key와 함께 anchors/bindings를 더 오래 보유한다. Index는 invocation-local이며 전역 marker cache를 추가하지 않는다. Actual Docker hitrate/serialized chars/allocation/memory 측정 전 전체 성능 승인을 주장하지 않는다.
- **다음 작은 개선 계획(v8)**: dependency-closed certificate가 있는 cyclic prune 경로에서 slot/edge counting 중 missing-state ID 조회를 생략한다. 보수적 one-arg 경로는 누락 dependency 처리를 그대로 유지한다. 수정 전15tests PASS;300개 random closed graph의 ordered survivor identity/모든 counter parity 및 실제 builder→cached-negative child 전파를 잠갔다. Production v8은 아직 적용하지 않았다.
- **의사결정 근거**: oracle/runtime/privacy 또는 legal candidate set은 바꾸지 않고 exact duplicate publication과 증명된 중복 membership만 제거한다. Full60초 planning 완료는 여전히 미달이다.

- **v7 추가 회귀 테스트에서 발견한 fixture 오류**: 초기8개 green 뒤 확대한11개 중3개가 실패했다. (1) warm-cache 테스트는 두 번째 proof를 cache를 데운 뒤 생성해 이미 String이 존재했다. (2) layout matrix의 directInputsExact만 바뀐 case는 native proof가 같아 cache가 case 사이에 남았다. (3) identity-backed dependency map 전체 Map.equals는 새 Set value의 객체 identity까지 요구했다. 두 proof를 먼저 생성/각 case cache 격리/owner key 및 dependency member identity를 명시 비교하는 방식으로 수정한다. Production 실패로 왜곡하지 않고 원본 failed log를 보존한다. 독립 검토의 최신 판정은 production CLEAR / fixture 수정 전 validation REQUEST CHANGES다.

### v7 최종 검증 및 실제 early coverage 관측 (부분 병목 해결)

- **fixture 수정 확인**:3개 문제를 고친 뒤 mixed fixture가 동일 worker/range에 placementId만 달라 새 물리 출력을 만들지 못한다는 추가 테스트 문제가 드러났다. 실제 다른 worker endpoint를 사용하도록 고쳤다. 이 변경은 planner의 정상적인 relation dedup을 완화하지 않는다. 최종 focused11PASS와 **full749 tests/failure0/error0/skip1**, BUILD SUCCESS00:37:11. 독립 재검토 APPROVE. JAR SHA `2a8762547d97978d96b5c07d261820c36344b1cca3ded24ea261fc985121a640`.
- **실제 LogReg 상세 snapshot**: publication3,275,596요청 중memo1,497,033hit; memo miss 뒤early coverage1,778,563probe 중1,461,201hit(약82%). Signature serialization1,007,552회/1,753,121,575chars. v6보다 더 진행된 partial snapshot이며 총속도 개선율로 환산하지 않는다. Early avoided-authority-char counter는 생략한 publication의 길이 합계이지 실제 할당 byte 절감량이 아니다.
- **완료 한계**:v7 LogReg/GLM 모두60초 watchdog/no receipt, 공통 분석 완료 미달. GLM 도중 다른 worktree의 javac를 관측했고 `evidence/v7-external-contention.json`에 보존했다. 외부 작업은 건드리지 않았으며 이 run은 uncontended wall-time acceptance가 아니다.
- **v8 적용/검토**: certified graph에 대해서만 counting-pass missing-state discovery를 생략했다. 보수적 entrypoint는 그대로다. slot/edge Math.addExact, dense IDs, reverse index, duplicate object semantics 및 counters는 변경하지 않았다. v7 sealed source와 정확한 delta를 대조한 독립 리뷰 APPROVE. 수정 후 전체749 selector package 진행 중이며, 통과 뒤 새 JAR를 봉인하여 같은 Docker 조건으로 측정한다.

### v8 역방향 dependency membership 및 v9 재분석 원인 계측 (부분 해결/계측 중)

- **v8 검증**: 관련 회귀 selector749건, failure/error0, 기존 PUBLIC skip1, BUILD SUCCESS00:41:57. JAR SHA `2fc1e760ace8b677c65682a0ac01cd096cd5b7e8648812d6466eb56c9269488e`. Certified/conservative parity 및 independent APPROVE 이후 봉인했다.
- **v8 Docker**: 같은 watchdog60초에서 LogReg/GLM 모두 timeout/no completion receipt/no observedOOM. OFF LogReg reverse dependency lookup의 NativePoolWitness.equals가 남아 있고, GLM Environment canonical 비교도 남아 있다. 표본 비중 변화만으로 총속도 개선을 주장하지 않는다.
- **v9 문제/해결**: stable ready-wave가 많은 원인을 분리하기 위해, 기존 의사결정을 바꾸지 않는 primitive counters를 추가한다. (1) 변경행 없는 완료 wave의 eligible facts/relation requests/publication requests, (2) VALUE_MAP seed 및 proof-metadata 불완전성, (3) self/immediate/alias/subscriber/incomplete-transitive invalidation 원인, (4) initialOuterDirectDirty의 reset 원인. Incomplete-only-extra는 다른 이유로 이미 required에 들어간 owner를 제외하고 마지막 fallback이 새로 넣은 owner만 센다. metrics OFF에서도 원래 set 삽입은 항상 실행한다.
- **해석 제한**: relation request는 실제 proof graph 계산 횟수가 아니다. Invalidation incidence는 중복되며, 전체 unique-extra와 incomplete-only-extra를 구분한다. Counter는 해당 호출의 required 추가이지 pending에 새로 추가된 wave 수가 아니다. Reset은 initialOuterDirectDirty만 포함한다.
- **검증 범위 보강**: 기존749 selector에 없던 DirectSourceSeedProjectionTest와 NeutralPlacementFixedPointCompositionTest를 추가했다. 후자의 기존 aggregate assertion은 v3 no-empty-DAG shortcut 이후 built alternatives와 compaction scanned를 동일시해 실패했다. 변경 전 v8 단독 재현은 graphs32/acyclic32/noEmptyDagSkips32/alternatives94/scans0/removals0이다. 정확한 shortcut counter 계약으로만 assertion을 고쳤으며 semantic/metrics-on-off/reset assertions는 유지한다. 기존 selector에서 빠졌던 coverage gap을 기록한다.
- **v9 초기 결과**: 새 계측 관련 예상 red7개와 위 stale contract1개를 구분해 보존했다. 수정 뒤 관련 selector **767 tests/failure0/error0/skip1**, BUILD SUCCESS00:49:07; independent APPROVE. Incomplete-only refinement의 단독 red는 expected1/actual0을 확인했다. 최종 package와 상세 Docker 재측정은 진행 중이다.
- **수정 파일**: PlacementRelationClosure, SearchSpaceMetrics, DirectSourceSeedProjectionTest, DirectedDirectClosureDirtyConeTest, NeutralPlacementFixedPointCompositionTest.
- **잔여/위험**: 전체 planning 및 Local/Global DP 완료/수치 parity는 아직 미검증. 계측 observer 비용이 있으므로 ON/OFF run을 동일 timing 비교로 쓰지 않는다. Metadata의 숨은 owner footprint를 증명하지 않고 conservative invalidation guard를 제거하지 않는다. 합법성/privacy/runtime 정책은 변경하지 않았다.

- **v9 최종 계측 gate**: incomplete-only-extra refinement 이후 관련767건 failure/error0, skip1, BUILD SUCCESS00:54:28. JAR `f1d2ed4766afeaaefd04b8284edbca98d3e7f6224447fb7d1743f923eb7573ca`를 source/test seal과 함께 봉인했다. 같은 Docker 상세 관측을 진행하며 own compile/JFR parse는 중단했다.
- **다음 GLM 계획(v10, 아직 production 미적용)**: 현재 canonical 비교 비용의 큰 호출자는 environment마다 TreeSet.add를 반복하는 observe/write/invoke/nextBlock 및 union이다. 문자열 comparator를 완화하지 않고, 현재 encounter order의 candidates를 stable sort하고 comparator-equal tie의 최초 representative를 보존해 bulk 수집한다. 이미 정렬된 union은 left-biased linear merge로 바꾼다. SortedSet views/immutability/membership/limit-after-exact-dedup과 TreeSet differential 테스트를 먼저 작성한다. 서로 다른 환경의 equals/hash로 pre-dedup하지 않는다. 첫 representative identity가 변경되거나 serialized ordering이 변하는 위험을 regression oracle로 잠근다.

### v9 검증 milestone 게시 및 20초 목표 (진행중)

- **사용자 목표 갱신**: 동일 Docker 조건에서 full LogReg/GLM planning 각각20초 이내 완료까지 계속한다. 진단 watchdog60초를 늘리지 않는다. 검증된 변경을 주기적으로 origin/main과 병합/커밋/푸시한다.
- **v9 실제 계측**: 두 workload 모두60초 timeout/no receipt, own container 삭제 확인. LogReg invalidation unique-extra49,372 중 incomplete-only-extra48,511(약98%)이며, no-delta completed waves1,050에서 publication1,283,520요청이 발생했다. 이 수치는 추가 binder 실행 수와 동일하지 않지만 metadata fallback이 지배적임을 보여준다. 숨은 VALUE_MAP owner dependencies를 정확히 보완하는 것을 다음 우선순위로 삼는다.
- **측정 제한**: unrelated worktree Maven/javac를 관측하여 v9 detailed는 진단 근거만 채택한다(`evidence/v9-external-contention.json`). 자체 compile/JFR parse는 timed run과 겹치지 않았다.
- **게시 gate**: 최종 v9 source hashes 전부 fresh767 selected tests/0failure/0error/1skip package seal과 일치. Independent metric/algorithm review CLEAR. origin/main fetch+merge는 Already up to date였다. 미검증 v10 bulk 테스트는 이 milestone에 포함하지 않는다.
- **위험/잔여**:20초 및 전체 planning 완료는 아직 달성하지 못했다. Metadata dependency 보완 전에 기존 conservative guard를 단순 제거하지 않는다. Derived-FOUT 및 ambiguous/foreign owner identity는 보수적으로 남길 계획이다.

### v10 VALUE_MAP metadata owner footprint 및 GLM bulk ordering (구현/검증 중)

- **문제**: fixed-pool resolver가 proof-state 밖의 VALUE_MAP source owner를 읽지만 direct subscription에는 그 owner가 없어 대부분의 consumer를 full-cone fallback으로 재분석했다. v9의 incomplete-only-extra48,511이 우선순위 근거다.
- **해결**: NativePlacementContinuity의 기존 immutable per-fact boundCandidateSources projection을 재사용해, binder owner별 모든 seed/proof dependency를 모은 후 한 번 identity BFS로 확장한다. Positive/negative/cache-hit에도 같은 현재 snapshot의 dependency가 보존된다. 새로운 transitive memo는 없다. Node/fact owner 구조에 equal-but-distinct identity가 있거나 foreign identity를 만나면 certify하지 않으며, available derived-FOUT가 하나라도 도달하면 보수적 fallback을 유지한다. 확장 성공 때만 provisional incompleteness를 제거한다. Seed query의 source owner는 empty/cache-hit일 때도 명시 추가한다.
- **계측**: metadata expansion phase와 certified/fallback/added-owner counters를 추가했다. 기존 seed/proof incomplete incidence는 이제 certification 이전의 provisional trigger이며, INCOMPLETE_UNIQUE_OWNERS는 최종 fallback owner 수다. 이전 v9 계측 의미와 구분한다.
- **회귀/검토**: 변경 전 DirectSource9tests 중5개 예상 실패(누락expander3, 실제binder2)를 확인했다. Positive/negative/missing/cycle/withdrawal-restoration/foreign-twins, deep seed/proof path, multiple owner rows와 hidden derived-FOUT를 검증한다. Production independent review CLEAR; 최종 focused/full gate는 진행 중이다.
- **GLM**: observe/write/nextBlock의 bounded one-to-one 결과를 stable sort+comparator tie compaction으로 수집하며, union은 left-biased linear merge다. invoke의 caller×exit raw product는 메모리 폭증을 피하기 위해 기존 TreeSet을 유지한다. 새 bulk tests5개는 기존v9에서 모두 예상 missing-method red였다. Root review에서 nested bounded view 및 empty mutator 계약 누락을 발견했고, 격리 source build에서5tests/1failure로 재현해 수정 중이다. 문자열 comparator와 첫 representative identity는 그대로 유지한다.
- **검증 중 발견한 빌드 오류**: root 신규 HashSet import 누락으로 focused Maven compile 실패. import를 고쳤고 fresh 재컴파일할 예정이다. 실패 뒤 target/classes가 부분적이라 isolated GLM compile의 첫 시도에서 의존 class가 없었으며, sealed v9 JAR를 의존으로 사용해 실제 view 실패를 분리했다.
- **잔여/위험**: complete이지만 넓은 owner closure가 실제 subscriber invalidation을 얼마나 줄이는지는 새 Docker로 측정해야 한다. 20초 목표 미달이며, legality/privacy/TR/TW/recompile/runtime 규칙 변경은 없다. 변경 후 전체 gate 및 cold-vs-warm identity receipt parity, independent review와 동일 Docker 실측 전에 성능 달성을 주장하지 않는다.

- **v10 최종 regression gate**: missing import 및 GLM immutable view를 수정한 뒤 fresh package **781 selected tests/failure0/error0/skip1**, BUILD SUCCESS01:05:22. Metadata10tests, bulk5tests, invocation exit memo6tests 포함. 두 slice 독립 source/test review CLEAR. JAR SHA `086d7d4528d535390b609c26aa2633efb1a46e51f4d5b9e00c1fcebf7d7bcd99`. 새 봉인 artifact로 상세 Docker 관측 중이다.
- **후속 범위 확인**: v9 route counters에서 MM/ternary/일부binary/cast/TRead/fcall의 정확 oracle residual 경로가 남아 있으므로 모든 연산이 rule-directed로 바뀌었다고 주장하지 않는다. 호출 수는 조합 수/시간과 다르며 current exact residual 표본 비중은 낮다. MM/ternary는 shape-qualified relation의 완전 oracle parity를 증명해야 확대할 수 있다. 현재는20초 목표의 지배적 공통 분석 비용을 먼저 줄인다.

### v10 실측 및 v11 derived-FOUT metadata footprint 확장 (진행중)

- **v10 결과**: 두 workload 모두60초 timeout/no receipt. GLM joint canonical 비교 표본 비중은 v9 상세15.46%에서 v10 상세4.11%로 줄었고 v10은 fixedPoint8/publication1까지 진행했다. 이는 동일 완료 작업의 wall-time speedup은 아니다. LogReg metadata certification은1,727 성공/4,547 fallback, expansion wall 약134ms였다. Incomplete-only-extra48,046이 남아 VALUE_MAP 보완만으로 부족하다.
- **추가 원인/설계**: v10의 blanket derived-action guard가 보수적 fallback을 남길 수 있다. Native topology의 실제 derived authority 읽기는 현재 producer owner와 durableAnchorOwner의 node/fact뿐이다. declaresExactNativeOwnerAuthority는 그 owner의 owned NATIVE_LINEAGE certificate 필드만 읽고 upstream binding을 재귀적으로 해석하지 않는다. 해당 anchorOwner를 기존 identity closure에 성공/실패와 무관하게 넣으면 missing/negative/cache-hit까지 완전하게 기록할 수 있다는 독립 설계 검토 CLEAR를 받았다. 실제 fallback 원인을 구분하기 위해 identity-reject와 derived-edge counters도 추가한다.
- **v11 변경**: available derived action의 anchor owner를 identity-add/enqueue한다. 모호한 구조적 twin/foreign identity는 계속 실패하며, action의 실제 materialization legality/selected-clause grounding/privacy는 전혀 바꾸지 않는다. 추가 memo나 public flag는 없다.
- **회귀**: root deep-derived test10개 중1개 기대한 old-v10 certificate 실패; 실제 MaterializedContinuity11개 중3개 기대 실패를 확인했다. Literal/native positive, warm caches, wrongpool/localonly negative, certificate withdrawal revised/fresh, ordinary edge가 없는 anchor-only subscription invalidation을 추가했다. Cold/warm fixture는 proof query를 사이에 실행하도록 보강했다. Derived/legal/privacy/layout 관련10개 suite를 selector에 추가하여 fresh package 중이다.
- **운영 오류 분리**: v11 첫 test-edit shell의 잘못된 workdir 및 Python 문자열 오류로 테스트가 변경되지 않은 control run10PASS가 있었고 이를 `v11-unmodified-test-control.log`로 보존했다. 실제 patch 적용 뒤10/1 expected red를 별도로 확인했다. Production/test 성공 근거로 잘못된 control을 쓰지 않는다.
- **잔여/위험**: counters가 아직 derived 원인의 지배성을 증명한 것은 아니다. v11 새 identity/derived 계측과 실제 subscriber invalidation을 확인해야 한다.20초 목표 미달; footprint가 넓으면 exact projection별 delta 비교가 다음 후보지만 아직 적용하지 않는다.

### v11 검증 milestone 및 invalidation 계측 해석 보정 (부분 개선/20초 미달)

- **검증**: 관련820tests/failure0/error0/skip1, BUILD SUCCESS01:13:35; independent v10-seal delta review APPROVE. JAR `8582a971ec06b29636001976635e9b7616e0032f1f092bb39bd57c6b71ed4e23`. 새 actual-derived tests11PASS 및 binder/metadata10PASS 포함. 컴포넌트/full-recompute 구성 parity 테스트도 selector에 포함된다.
- **실제 결과**: 동일 Docker 상세 run 두 workload 모두60초 timeout/no receipt, own container 삭제 확인. LogReg certified5,946/fallback0/identityreject0/derivededges115,691; GLM certified7,353/fallback0/identityreject0/derivededges66,904. Derived edge count는 distinct owner가 아니라 방문한 action emission 수다.
- **중요한 해석 보정**: 최종 metadata fallback이0이어도 incomplete-only-extra는 LogReg47,346/GLM59,416이다. 해당 집계에는 아직 처리되지 않았거나 이미 pending에 있는 owner, 그리고 touched로 subscription이 무효화된 owner가 포함된다. 따라서 이전98% 비중을 실제 불필요한 재실행의98%라고 해석하면 안 된다. 다음 v12는 required 집합 중 새 pending/이미 pending을 분리한다. 이 계측 없이 fallback 제거율을 총성능 개선율로 주장하지 않는다.
- **관측된 부분 개선**: v11 LogReg no-delta eligible facts3,750(이전 v9는5,007) 및 no-delta publication968,269(이전1,283,520)이다. 진행지점이 다른 partial snapshots이므로 총속도 개선율이 아니다. GLM canonical collection의 감소도 별도 경로 개선일 뿐20초 완료 증거가 아니다.
- **게시 범위**: v10+v11 sealed/검증/독립승인된 source/tests만 게시하며, 아직 red-run 전인 v12 DirectedDirectClosureDirtyConeTest 추가는 제외한다. Source hash 대조로 구분했다. Candidate legality/privacy/DP arithmetic/derived authority 규칙 변경은 없다.

## Incoming origin/main 5f9edeeb2f session evidence (retained)


## COFEE 50K×128 통합 경로 — 압축 cost preflight와 main 병합 (진행중)

- **목표/승인**: 사용자가 LogReg/GLM 전체 최초 planning 각각 20초 이내까지 계속 구현·검증하고, 검증된 개선을 주기적으로 origin/main에 커밋·푸시하도록 지시했다. 이전 미게시 제한을 이 지시로 갱신한다. 아래 결과는 위의 별도 60초 진단 실행과 구분하며, 새 profiling/JFR 없이 실제 Docker 실행으로 검증한다.
- **고정 환경**: COFEE 50K×128, W1, X PRIVATE_AGGREGATE, Y PUBLIC, 기존 DML/seed/cost profile; so007 coordinator/so006 worker, CPU0–7, Docker24GiB/JVM16GiB. 입력 전처리·worker 수·privacy를 바꾸지 않는다.
- **실제 v2 실패**: engine `2bdb182b594eb3e130888cdf01be3d3a2ca834f863c48c6ae7426e68cb523981`에서 Analysis 460.527664756초, coordinator cgroup peak 7,462,850,560B. singleton worker-count certificate는 refs1135/anchors6522/clauses38711/bindings60002를 검사해 통과했다. 이후 cost preflight가 `EXACT_VE_FACTOR_CELL_OVERFLOW`로 실패했다. process 474.301초는 실패까지의 시간이며 전체 planning 성공 시간으로 사용하지 않는다. numeric/최종 receipt 성공도 없다.
- **원인/변경**: ordinary 비용 테이블과 solver-only lazy relation을 같은 int Cartesian 배열 크기로 검사했다. 새 구조 검사는 모든 variable/scope/dense 비용을 먼저 검증하되, ordinary table의 크기·합산 budget을 유지하고 solver-only 큰 relation은 기존 unary/support reduction으로 넘긴다. reduction 후에도 int 범위로 줄지 않으면 기존 solver는 계속 실패한다. 무제한 배열 생성이나 후보 제거로 우회하지 않는다.
- **회귀에서 발견한 문제**: 초기 변경이 indexable solver-only 12cells + ordinary8cells를 합산하지 않았다. 기존 `ExactNativeLocalSourceProjectionTest`의 limit19 실패/20 성공 계약으로 검출하고 고쳤다. 이어 raw functional map의 reduction 전 budget 부과가 unary 축소 순서를 바꾸는 반례를 별도 테스트로 잠갔다. 해당 projection/reduction 테스트를 통합 verify.py 필수 목록에 추가했다.
- **main 병합**: `a03365eade3e1553340dc36d23baa57f03ac5000`을 fast-forward한 뒤 기존 작업을 복원했다. 사전 patch/tar/SHA 백업과 stash `e8743457c2ba226743ef6e4789d6b0f5d7924f51`은 보존한다. Native/Closure/DirectSourceSeedProjectionTest/전일 이슈 문서 네 충돌을 통합했다. 첫 통합 compile 성공, 906 tests 중1 실패: hidden VALUE_MAP owner footprint를 완전히 추적하는 구현에 upstream의 incomplete-metadata counter=1 기대값이 남아 있었다. owner identity 검사는 유지하고 counter0 계약으로 정정한 뒤 재검증한다.
- **검증 도구**: COFEE harness/evaluator 전체 Python42 tests PASS. acceptance gate는 동일 frozen engine의 LogReg/GLM 각각3회, 매회 `planningFullInitialNanos ≤ 20,000,000,000`, fresh analysis, numeric comparison/receipt와 regression 성공을 요구한다. failed/incomplete/superseded/diagnostic/cached-analysis 실행은 제외한다. 현재 gate는 FAIL(각0/3)이다.
- **수정 파일**: ExactCategoricalSolver, ExactPhysicalCostModel, ExactCompressedFactorStructureTest, ExactPhysicalCompressedPreflightTest, verify.py와 evaluator 도구. 최신 main의 proof publication/canonical 최적화는 기존 source/privacy/MRV 변경과 함께 통합한다.
- **잔여 이슈**: 합법 native support의 leaf별 template→proof→clause 생성과 일부 downstream Cartesian 전개가 남아 있다. 단일 균일 rectangle의 relation-native 경로를 별도 작업공간에서 구현·대조하며, 정확성이 증명되지 않는 sparse/shared-owner/VALUE_MAP/function 경로는 기존 exact 경로를 유지한다. 20초 및 전체 compilation/실행 동등성은 아직 미달/미검증이다.
- **잠재 회귀/감지**: preflight budget 누락, dense validation보다 먼저 evaluator 실행, source/action/proof 권한 손실, union의 sparse holes 재허용. 기존 pinned regression과 explicit-reference differential 테스트, 동일 Docker 실행으로 검출한다.
- **의사결정 근거**: runtime/oracle/privacy/cost 의미를 변경하지 않고 중복 계산과 잘못된 선행 materialization 요구만 제거한다.


### COFEE 통합 v4 게시 검증 (코드 검증 완료, 실제 20초 목표 진행중)

- main/test compile 성공, **JUnit948 PASS(156.279초)**. `ExactNativeLocalSourceProjectionTest`와 `ExactPhysicalReducedSolverTest`를 포함한다. 컴파일 소스112개와 현재 파일 SHA mismatch0, unmerged0, diff-check PASS. 전체 Maven suite 결과로 확대하지 않는다.
- Python COFEE/evaluator42 PASS, joint-boundary harness38 PASS. 별도 reviewer가 merged preflight/caller, singleton worker certificate, metadata-native layout/dependency 계약을 CLEAR로 판정했다. 네 병합 파일의 독립 계약 검토도 통과했다.
- 검증 증거: `/grid/3/cofee-lm-sweep-mchoi-20260914/fed-oracle-native-20261008/merge-backups/origin-main-merge-a03365e-20261008T230334Z/final-merged-green-948`. 초기 실패는 같은 backup의 `first-merged-red-906`에 보존했다. Python 증거는 `evidence/generation-pruning/root-v4-python-validation/validation.json`이다.
- 동일 source를 frozen engine v4로 패키징한 뒤 실제 LogReg를 실행한다. 이 게시 시점에는 20초 목표가 미달이며, 기존 v2의 실패를 성공으로 대체하지 않는다. 새 relation-native native support 구현은 별도 작업공간에서 진행하며 이 검증본에는 포함하지 않는다.

### origin/main 통합 gate 및 v12 반복 작업 구분 (진행중)

- **통합**: v10/v11 commit `6d9bf2ebe6`과 incoming `5f9edeeb2f`를 `1b06166eea`로 병합했다. VALUE_MAP resolution의 identity-keyed cache 및 positive/negative owner-read receipts를 유지하고, 최종 보수적 BFS/derived-anchor footprint와 결합했다. 독립 merge 검토 CLEAR, fresh Maven **1,012 tests/failure0/error0/skip1**. JAR `6bda179dd544d5f59a690888b94b9225a736e6a79906450901574211803fb392`. `1b06166eea`를 origin/main에 push하고 remote SHA를 확인했다.
- **계측 계획/변경**: required owner가 이미 pending인지, 실제 새로 enqueue되는지를 구분한다. 이전 6/7-arg entrypoint 및 원래 set insertion을 보존하며 production call은 selected 제거 후, pending.addAll 이전의 queue를 전달한다. red test33건 중 기대한 missing8arg failure1을 보존했다. 이는 scheduling 변경이 아니다.
- **추가 할당 개선 계획**: merge에서 유입된 topology clause loop는 non-VALUE_MAP clause마다 null-resolution 및 빈 immutable identity set을 생성한다. 같은 null pool 의미를 유지하며 VALUE_MAP일 때만 resolver/owner-read union을 실행한다. 1,012 green baseline과 fixed-map/materialized/topology tests가 의미를 잠그며, 분기 변경 후 재검증한다. 실제 VALUE_MAP positive/negative receipt 및 cached lookup은 바꾸지 않는다.
- **측정 제한/잔여**: merged detailed Docker 진단 중 별도 worktree Maven을 관측했으며 `evidence/merged-main-external-contention.txt`에 저장했다. 자체 build/JFR parse는 timed run과 겹치지 않는다.20초 및 full planning 완료는 아직 달성하지 못했다.
- **위험/검출**: metrics OFF의 삽입 누락, duplicate pending의 과계수, metadata receipt 누락은 queue partition parity/기존 cold-warm revision tests로 검증한다. Oracle/runtime/privacy/후보군/DP arithmetic 변경 없음.

### v12 승인 및 v13 component/receipt 개선 계획 (진행중)

- **v12 검증**: fresh1,013tests/failure0/error0/skip1, independent CLEAR; JAR `49bab1063133a63a5e4d101c5eec18e6f91e2c5c8e898818ee249d1e8a1aefe5`. pending metrics는 후속 SCC 전체 확장을 포함하지 않으며 실제 executed fact 수와 동일하지 않다. 동일 Docker 상세 관측 중.
- **다음 문제**: support edge가 바뀌어도 고정 potential graph에 이미 있는 edge이면 semantic union P∪S와 SCC는 그대로다. 기존 코드는 이 경우에도 old SCC의 모든 settled member를 다시 pending에 넣는다. 완전한 read receipt가 있고 독립 invalidation도 없는 member만 제외할 수 있다.
- **계획/회귀**: 기존 preservation/rebuild decision을 3-arg private helper로 의미 변경 없이 추출하고, 실제 DirectSupportIndex의 covered add/remove delta에서 old helper가 잘못 settled A를 추가함을 red로 확인한다. union이 변할 때의 add/merge, remove/split, structural twin, incomplete/cancelled member 및 later invalidation은 그대로 유지한다. 그 뒤 dependencyUnionChanged만으로 preservation+rebuild를 gate한다. Required invalidation은 무조건 기존대로 실행한다.
- **별도 Native receipt**: derived topology가 실제 읽는 durable anchor owner를 검증/lookup 전에 정확히 기록한다. 모든 positive/negative/cache/component 경로의 receipt를 잠그고, empty metadata set은 traversal map에 저장하지 않는다. binder fast reuse/seed projection에는 query 없는 경로가 있으므로 최종 broad BFS/identity guard를 제거하지 않는다.
- **잔여/위험**:20초 목표 미달. SCC requeue 누락과 hidden metadata cache stale는 production helper regression, full-recompute ordered-fact/fingerprint parity, cold/warm withdrawal-restoration으로 감지한다. Runtime/privacy/oracle/합법 후보군 변경은 없다.

- **v12 실측 해석**: LogReg incomplete-only-extra46,872 중 실제 new-pending67, 전체 required의 new2,097/already47,190. GLM incomplete-only-extra59,838 중 new8,255, 전체 new9,672/already52,371. 따라서 LogReg의 큰 기존 집계 대부분은 이미 대기하던 일이며, GLM에는 실제 새 invalidation이 많이 남아 있다. 두 workload 모두60초 timeout/no receipt이며20초 성공으로 해석하지 않는다.
- **v13 red/green**: 의미를 보존한 old SCC helper에서 covered-edge regression은 예상{B,C}/실제{A,B,C}로 실패했다. 최초 Native 새4tests 중3개 기대 receipt 실패와1개 fixture fingerprint setup 오류를 분리했다. fixture를 수정하고 sealed v12 binary로 재검증한15tests는 정확히4개 receipt assertion 실패였다. 구현 뒤 SCC+Native focused **51PASS**, source freeze 후 full package 중. Broad BFS/identity certification 및 required invalidation은 유지했다.
- **v13 full gate**: fresh **1,020 selected tests/failure0/error0/skip1**, BUILD SUCCESS01:33:37. Independent production delta review CLEAR, sealed-v12 hash reconstruction으로 이전 변경과 구분했다. Covered-edge unit fixture의 AVAILABLE/PRIVACY_EXCLUDED 전환은 index-only adjacency 검증이며 실제 privacy 정책 변경이 아니다. 실제 통합 경로는 기존 full-recompute ordered-fact/fingerprint 비교로 추가 검증했다. 다음 동일 Docker 실측 전 전체 속도 달성을 주장하지 않는다.

### v13 게시 및 v14 다음 병목 계측/개선 계획 (진행중)

- **게시**: v12/v13를 `0af4efd265558cdb893cea71286a197d17868848`로 커밋하고 최신 origin/main 확인 뒤 push/remote SHA 확인. v13 JAR `a73121cad2a807c03081530df174359cfeaf9e950a5986dc1bf0df85e43f4687`. 상세 Docker는 두 workload 모두60초 timeout/no receipt로20초 미달이다.
- **GLM 원인 분류**: incomplete-only new-pending을 direct eligible slot이 전혀 없는 owner / 마지막 committed change / 마지막 boundary cancellation / 기타로 구분한다. 마지막 이유는 재시도 시 지우고, 관측이 꺼져 있으면 추가 identity set/map을 만들지 않는다. Scheduling/receipt completeness 자체는 바꾸지 않는다. 기존 cancellation 테스트는 수동 invalidate 정책 테스트여서 실제 GLM의 cancellation 지배성을 입증하지 않는다. 테스트37건 중 기대한 새 constructor 누락1건을 red로 확인했다.
- **LogReg 계획**: v12 JFR에서 NativePoolWitness.equals141 leaf samples 중94개가 cyclic pruning reverse index의 structural state ID 조회다. 동일 immutable dependency state 객체가 default alternatives에서 반복 재사용되므로 query-local bounded identity memo를 앞에 둔다. miss는 반드시 기존 structural map을 조회하고 cap4096 초과 시 기존 경로를 유지한다. 보존 객체를 변경하거나 전역 캐시를 두지 않는다. Hit/miss 실측 전 효과를 단정하지 않으며 기존 randomized oracle/duplicate object identity/negative graph/collision/counter parity부터 잠근다.
- **위험/검출**: identity miss를 부재로 잘못 해석, cap 초과 오류, 쿼리 간 ID 누수, 원인 계측의 stale label이 주요 위험이다.20초 미달이며 legal state/runtime/privacy/DP arithmetic 변경은 없다.
- **v14 gate**: Native helper regressions13tests 중4개 기대 missing-method red 확인 후 실제 pruning-path 테스트도 추가했다. 원인 계측37PASS, 통합 focused66PASS, fresh **1,026 selected tests/failure0/error0/skip1**, BUILD SUCCESS01:44:53. 두 slice independent CLEAR. JAR `e50729c02179630cb90354bd80d9966d763387d464f792dbedbd803d4357caf0`. Lookup counters는 helper의 hit/fallback만 세며 initial ID table 구축/보수적 기존 선행 containsKey는 포함하지 않는다.
- **계측 OFF 대조**: v13 OFF/JFR run도 LogReg/GLM 모두60초 timeout/no receipt였다. ON observer 비용만으로20초 미달이 설명되지는 않는다. 자체 build/JFR parse는 timed run과 겹치지 않았고 own container 삭제를 확인했다.

### v14 실측 거절 및 v15 generated SUPPORT 재사용 계획 (진행중)

- **실측/판정**: v14 동일 Docker 양쪽60초 timeout/no receipt. LogReg identity lookup hit2,183,311/fallback36,512,436(약5.6%)로 대부분 structural lookup을 그대로 수행하며 추가 lookup 비용을 부과한다. 정확성 gate 통과와 성능 채택은 별개이므로 bounded identity cache와 전용 tests/counters는 철회했다. 원인 계측은 유지한다. GLM incomplete-new8,473 중 no-binding7,559/other914, committed/cancelled0이다. 이 집계는 서로 다른 부분 진행도의 snapshot이며 wall-clock speedup 증거가 아니다.
- **문제/계획**: generated SUPPORT traversal은 root로 향하는 모든 edge를 published-root history read로 취급한다. 하지만 exact owner/proposal에 맞는 recurrence는 GenerationRoot primitive recipe를 재실행하거나 active-state로 돌아갈 뿐이다. Primitive authority와 다른 occurrence full facts가 같고 실제 root metadata read가 없다면 지원 증명은 같다. 독립 architect가 recipe/topology/identity-clause/fixed-map/component cache 경로를 검토해 bounded design CLEAR로 판정했다.
- **변경 범위**: broad dependency-to-root flag만 실제 non-recipe root routing/hidden metadata read로 좁힌다. Exact routing predicate, 모든 DFS/cycle/pruning/self-premise 규칙, complete occurrence footprint, fixed-root component exclusion, public completedProofMemo의 full-history invalidation은 보존한다. boolean 이름은 published-history read 의미를 명시한다.
- **회귀 계획**: publication-only 실제 recurrence의 cached/fresh ordered proofs와 identity footprint, cache-budget0, negative support, hidden derived/VALUE_MAP root certificate withdrawal/restoration, primitive/source revision 및 public dynamic filtering을 잠근다. 초기 fixture의 CP/FED execution-side 및 CP emission executionFType 오류는 production semantics failure와 분리하고 수정한다.
- **잔여/위험**: root history를 실제 읽는 경로를 놓치면 stale positive/negative support가 생길 수 있다. 기존 metadata receipt 및 cold-warm differential로 검출한다. 다른 owner revision은 여전히 invalidate하므로 약35k graph가 모두 없어질 것으로 가정하지 않는다.20초 목표 미달이며 runtime/oracle/privacy/legal candidate set 변경 없음.

### v16 closure-local no-query receipt 보존 (검증중)

- **문제/근거**: v14 GLM에서 incomplete-new8,473건 중7,559건은 eligible direct-binding slot이 없는 owner다. 한 closeDirectComponents 호출 안에서는 status/rule input/opcode와 owner-slot 대응이 불변이고 binder/boundary는 emission만 갱신한다. 따라서 initial eligible BitSet의 모든 슬롯을 검사해 해당 owner의 direct transfer가 항등임을 증명할 수 있다. 독립 architect가 실제 binder/boundary 재구성 경로를 검토했다.
- **변경**: metrics ON/OFF 모두 같은 invocation-local identity certificate를 만들고, 기존 complete receipt가 정확히 자기 자신 하나만 포함할 때에만 invalidate에서 유지한다. 미방문/빈/불완전/다른 identity/복수 의존 receipt는 보존하지 않는다. Changed-self/immediate/subscriber/support/removed-edge/alias propagation 및 boundary 실행은 그대로다. 관측용 원인 map만 metrics ON에 할당한다.
- **회귀**: production 변경 전40tests 중4개 기대 실패를 확인했다. All-slot mixed eligibility, zero-row/TRead/ineligible, exact identity 및 incomplete withdrawal, changed-self/subscriber, 경계 net-delta 후 required work1→0과 실제 diagnostic-map OFF parity를 추가했다. 이 경계 테스트는 production delta/queue helper 검증이며 실제 boundary session 전체 실행 또는 두 번 복사한 동일 list의 비교를 통합 동등성 증거로 주장하지 않는다. 기존 full fixed-point differential suites를 함께 실행한다.
- **v15 gate**: 실제 recurrence positive/negative/cold parity 및 generated memo certificate=true, hidden derived/VALUE_MAP positive·negative metadata receipt certificate=false, withdrawal/restoration을 추가했다. 올바른 FED/LOUT→FED/FOUT fixture로103tests/skip1 통과. 기존 broad guard에서 recurrence 재계산이 red로 재현되었다.
- **잔여/위험**: 신규 전용 end-to-end boundary cancellation fixture는 아직 없다. 기존 full fixed-point output differential 및 실제 Docker로 보완하며, partial counter 감소를 전체20초 달성으로 해석하지 않는다. Legal candidate/privacy/runtime/oracle/DP arithmetic 변경은 없다.
- **v15/v16 full gate**: source freeze 후 focused144PASS, fresh Maven **1,028 selected tests/failure0/error0/skip1**, BUILD SUCCESS01:59:12. 독립 production review 양쪽 CLEAR. Native memo certificate assertion도 직접 검증했다. v16 JAR `0ce7f074aa9b2eb62a6c235a6424babdbe200b59cb7f2b83785563ccbffa6988`을 봉인하고 동일 Docker detailed 진단을 시작했다. 새 전용 boundary end-to-end fixture 부재는 위 제한대로 유지한다.

### v16 실측 및 v17/v19 다음 work 제거 (진행중)

- **게시/측정**: v15/v16를 `ac0b5e602844a9ea3d1d2db175e417fc2dd1e132`로 origin/main 게시/remote 확인. 두 workload 여전히60초 timeout/no receipt. GLM no-binding incomplete-new7,559/other914가 그대로이고, LogReg graph38,789이며 전체20초 미달이다. v16 source/JAR가 봉인된 뒤 v17 tests-only 변경은 별도 미커밋 상태로 분리했다.
- **v19 재분석**: zero eligible slot owner는 한번 selected되면 binder dependency entry가 생길 수 없어 기본 exact-self receipt를 받고, v16에서는 이후 invalidate도 이를 유지한다. 남은 no-binding incomplete owner는 현재 closure에서 미방문한 항등 direct transfer다. 따라서 observed `complete()`와 별개인 `schedulingComplete()`에 static no-query certificate를 허용할 수 있다. 독립 architect가 초기 pending, empty dirty, mixed SCC, 첫 mandatory boundary 실행과 boundary 독립 입력 계약을 재검토했다. 이전 no-precompletion은 안전상 필수가 아닌 보수적 work policy였다.
- **v19 변경/테스트**: 초기 pending/changed/self/immediate/subscriber/alias/support와 전체 affected-cone 탐색은 그대로 두고 ready/fallback 두 위치만 schedulingComplete를 사용한다. 가짜 receipt를 넣지 않는다. Sparse unvisited, mixed SCC, pending zero-query/empty pending, closure-local lifetime, metrics ON/OFF 및 observed receipt 부재를 잠그며 기존 v16에서43tests 중3개 기대 실패를 확인했다. Static certificate가 있는 수동 incomplete receipt fixture의 queue 기대만 명시적으로 갱신한다.
- **v17 계획**: dead propagation 후 liveCounts[owner]==원래 list.size이면 어느 alternative도 제거되지 않았다. 최종 survivor scan만 생략하고 list identity/기존 logical counters/duplicate-object 의미를 유지한다. 최초 CountingList 테스트는 기존 loop가 untouched list.get을 하지 않아 red가 아니었다(10PASS); 이를 수정 전후 work 감소 증거로 사용하지 않는다. 새 aggregate skipped-slot 계측과 mixed removed/untouched fixture로 실제 skip을 검증한다.
- **잔여/위험**: 세부 skip이 전체 speedup/20초를 보장하지 않는다. 구조/eligibility가 closure 중 바뀌면 static certificate가 깨지므로 all-slot classification 및 실제 full fixed-point differential을 계속 실행한다. 실제 boundary-session 전용 새 fixture 부재는 유지하며 source proof와 기존 composition suite를 보완 근거로 명시한다.

### v18 default dependency ordinal 재사용 계획 (진행중)

- **실측/범위**: v16 LogReg JFR에서 dead pruning12.12%, NativePoolWitness.equals7.19%의 inclusive samples가 남아 있다. Default schedule 전체 raw successor88,682,624/unique27,032,864지만 이는 DAG 포함 집계이며 cyclic-dead subset의 절감으로 단정하지 않는다. DefaultAlternativeList는 Cartesian relation이 아니라 상관관계를 보존한 flat immutable list다; 임의 축 분해는 하지 않는다.
- **계획**: 기존 schedule.uniqueSuccessors의 ordinal만 optional immutable int[]로 저장한다. 실제 dead-seed pruning reverse-index에서 unique successor마다 query-local dense ID를 한 번 조회하고 원래 edge 순서의 ordinal로 참조한다. Existing per-slot dependency dedup/duplicate object removal/edge overflow 및 fallback 의미는 그대로다.
- **메모리/수명**: 원래 DefaultAlternativeList가 필터링되지 않은 경우만, E<=65,536 및 E<=8*alternativeCount 및 U<E를 만족할 때 채택한다. Dead pruning core에 진입하기 전에는 만들지 않고, optional allocation 실패는 기존 exact 경로로 복귀한다. Query-local ID/removed flag/survivor는 캐시하지 않는다. Topology lifetime과 row budget의 제한을 따르며 새로운 전역 캐시는 없다.
- **회귀/증거**: 최초3tests 모두 기대 missing-private-method red. Ordinal 순서/중복/warm identity, filter/density/absolute cap bypass, query dense-ID 순서가 다른 duplicate-object graph parity부터 잠갔다. Random default-vs-plain graph differential, no-dead lazy path 및 aggregate E/U resolution 계측을 추가한다. v17 corrected work assertion은10tests 중1 expected red, 적용 후 v17+v19 focused53PASS; v19+실제 composition suite55PASS이다.
- **잔여/위험**: Dense ID를 잘못 retained하거나 필터 후 좌표를 혼동하면 stale edges가 된다. 원래 list identity gate와 reordered-query differential로 검출한다. 구조적 후보 압축/전체20초 달성을 주장하지 않는다.
- **v17/v18/v19 gate**: full source freeze 후 focused162PASS, fresh Maven **1,037 selected tests/failure0/error0/skip1**, BUILD SUCCESS02:12:59. 독립 검토 세 slice 모두 CLEAR. 봉인 JAR `3940a9054a927d54451311cccd97e46968cda44034280d04d5bdea089bde6f53`. 이후 production 변경 없이 certified entry+metrics OFF 전용 회귀1개를 추가했으며 Docker 종료 후 별도 targeted 검증한다. Optional allocation-failure의 결정적 fault injection은 아직 없고 cap fallback만 직접 시험했다.
- **v19 실측/추가 검증**: 상세 Docker는 양쪽60초 timeout/no receipt로20초 미달이다. LogReg에서 ordinal edge44,923,734를 unique dense resolution7,731,137로 처리했고, untouched-owner slot26,640,152의 survivor scan을 생략했다. GLM static no-query new-pending skip12,916, 잔여 no-binding incomplete-new0/other977이며 direct passes2,079이다(v16 partial2,894와 작업 진행도가 달라 wall speedup으로 환산하지 않는다). Native witness equality inclusive sample 비율은7.19%→1.80%, dead pruning12.12%→7.40%지만 전체 completion 개선 증거는 아니다.
- **seal 이후 tests-only 보강**: production source는 봉인 그대로다. Certified entry+metrics OFF 포함 새 suite7tests를 별도 javac/JUnit으로 실행해7PASS를 확인했다(`evidence/v19-supplemental*.log`). Full package의1,037 selected 결과와 supplemental7을 중복 합산해 전체 test count로 보고하지 않는다. 계측 OFF/JFR 대조를 추가한다.

## Incoming native-support integration evidence (retained)


### v4 게시 및 실제 재검증 시작

- **게시 완료**: `5f9edeeb2f68dddf3b28c55ebaee82fc0e7e453f`가 `origin/main`에 반영됐다. force push 없이 a03365e→5f9edeeb2f fast-forward이며 generated evidence와 기존 stash는 보존했다.
- **Frozen v4**: JAR `1c9349ebfbf03f0afa381382af3012c0a2ad2a3d8e34a1e5369e5fa678e2e1d5`, manifest `8b8ec4d528e432a862ec849a31f995ce830b6cf54011f946bc5f460e50a5c039`, class/resource4593개. 검증112개 source는 게시 commit과 모두 일치한다.
- **실제 실행**: `evidence/cofee-50k128-a03365e-v4-validation/candidate-logreg-run2`, attempt `01791501166303919409-afb50896`, campaign `w1357-bounded-8a10a5dc277144`. 앞의 run0/run1 argparse 설정 실패는 runtimeStarted=false로 별도 보존하고 성능 측정에서 제외했다. run2가 실제 workload 실행이다.
- **중간 관측/목표 미달**: seq9 Analysis40.018초에서 아직 분석 중이다. 따라서 이번 실행은 전체 planning20초 gate를 이미 넘었다. proof rows2,619,158, 내부 proof alternatives36,101,769, dependency edges60,821,481; 누적 할당24.69GB는 peak가 아니다. MRV24/조기검사79/cuts0, privacy avoided64/rejected12. 전체 planning/수치 결과 확인을 위해 실행을 계속하며 미완료 결과를 성공으로 집계하지 않는다.
- **후속 구현 검토**: 합법 native support product를 descriptor로 전달하는 별도 구현은 member별 proof authority를 유지해야 한다. lazy list를 도입해도 canonical ordering, emission merge, semantic fingerprint 및 generic Closure stream에서 즉시 전개된다면 end-to-end 압축 성공이 아니다. 실제 publication→PhysicalModel→CostSurface 테스트에서 생성 handle 수와 논리 member 방문 수를 따로 확인한다. Factory 단계에서 축의 position/DIRECT/unique binding 조건을 직접 검증한다.


### v4 종료: Analysis 개선과 후속 DP 전개 실패를 구분

- **결과**: Analysis333.489347229초. v2의460.527664756초보다 짧지만 각각 단일 실행이고 전체 compilation paired 성공 비교가 아니다. Explicit Clause 생성20,882,097→2,550,102, keys27,442/facts222,582/indexed handles2,276은 동일하다. Proof rows19,957,286와 support leaves3,942,161도 그대로여서 객체 생성을 줄인 개선과 논리 조합 전개 제거를 구분한다.
- **새 실패 경계**: cost preflight 및 singleton worker certificate는 통과했다. Local DP의 `RegionalSearchProblem.reducedRoot → ExactPhysicalReducedSolver.reduce → freezeInputs`에서 unary/binary reduction 뒤 남은 lazy hard factor의 Cartesian 크기가 int 범위를 넘어 실패했다. 실행/수치 receipt는 없다. Coordinator peak7,285,755,904B, OOM kill0, own cleanup resolved. GLM은 아직 실행하지 않았다.
- **증거**: `evidence/cofee-50k128-a03365e-v4-validation/candidate-logreg-run2/v4-overflow-evidence.json`, SHA `d6312aa9bd314961fee06671b68070f592336fe225706a6f11ae55f1449a7eda`. 실패 실행을 포함한 evaluator는 LogReg0/3·GLM0/3 FAIL이다.
- **문제 식별의 한계/변경**: 기존 예외에는 factor ordinal/domains/evaluator가 없어 정확한 residual family를 특정할 수 없었다. 정상 경로의 계측을 추가하지 않고 실패 시에만 원본·감축 후 factor의 첫8축과 길이 제한 key/evaluator를 suppressed exception에 남기도록 했다. 기존 primary `EXACT_VE_FACTOR_CELL_OVERFLOW`와 evaluator 호출 순서는 유지한다. 새 프로파일링 실행은 하지 않는다.
- **회귀**: 새 테스트는 변경 전10개 중 의도한1실패(예외 context 없음)를 재현했다. 변경 후 compressed preflight/native-local projection/reduced solver 관련56 tests PASS(57.671초), main/test compile PASS. 이 변경은 진단 정확성 개선이며 성능 최적화로 보고하지 않는다.
- **다음 구현**: native product의 생성·Closure 압축과 별개로 derived-FOUT fixed-pool 합법성을 작은 hard circuit으로 분해하는 lane을 시작했다. 이는 정적으로 확인한 큰 factor 경로이며 실제 v4 culprit이라고 단정하지 않는다. 기존 canonical 판정/비용을 대조 기준으로 보존하고 SCC grounding, source/receipt 권한 및 보조 변수의 비용/tie가0임을 검증한다.


### 다음 후보: derived-FOUT circuit 및 native support metadata 소비 (통합 중)

- **DP 구현**: derived-FOUT의 fixed-pool 합법성을 최대 arity 3인 +0/+INF 제약으로 분해한다. 원본 canonical factor는 최종 검증에 유지한다. Query/source owner는 identity로 구분하고 relation-family receipt를 기존 helper로 복원한다. 순환은 SCC 내 근거 경로로 검증하며, 외부 근거 없는 자기 순환/2-node 순환 및 grounded되지 않은 공동 입력을 거부한다. 보조 변수의 monetary/tie cost는 0이다. 최소 producer gate조차 원본보다 크면 그래프 탐색 전에 기존 경로를 유지한다.
- **DP 증거**: real fixture의 action별 144개 leaf에서 원본 판정과 인코딩의 existential projection이 일치한다. 강제 인코딩된 full PhysicalModel→CostSurface→ExactPhysicalOptimizer→PhysicalSelection 테스트에서 objective raw bits, decision assignment, canonical receipt identity, relocation choices/emitted relocations가 일치했다. 독립 reviewer CLEAR. Lane focused5/combined9 PASS, root 통합 compressed overflow/preflight 포함19 PASS(4.171초). 매우 깊은 proof graph에서 재귀 Tarjan의 stack 사용은 남은 제한이다.
- **Cost Model 후속**: native relation의 Clause witness/exactness를 직접 읽어 입력 layout, recursive worker count 및 singleton certificate가 조합을 다시 펼치지 않도록 한다. Dynamic pool은 endpoint만 보증하므로 exact ranges로 승격하지 않는다. Witness가 없으면 축별 source binding을 확인하며 기존 검증 한계를 유지한다.
- **Cost 검증**: 변경 전4개 회귀가 모두 실패했다. 입력 layout/다중 worker 거부가 각각 Clause1개를 생성했고 recursive count가400개를 생성했다. 100만 member의 single-worker relation은 explicit clause budget 때문에 fast path를 사용하지 못했다. 변경 후 관련23 tests PASS(3.759초), zero-handle assertions 및1/3-worker 결과를 확인했다. 첫 green 시도는 live lane 재빌드와 classpath가 겹쳐 NoClassDefFoundError21건으로 실패했으며, immutable source overlay로 분리하여 다시 compile/test했다. 양쪽 로그를 모두 보존한다.
- **측정 한계**: 이는 작은 회귀 및 큰 논리 relation의 구조 테스트다. 새 실제 COFEE 전체 planning 시간이나20초 달성 결과가 아니다. Native Closure-wave와 전체 통합 검증 뒤 v5 실제 실행을 수행한다.
- **환경 보존**: root filesystem 여유가4.7MB까지 줄어 immutable pinned baseline target317개 파일을 grid로 복사/SHA 검증하고 원래 경로를 symlink로 유지했다. 원본도 grid에 별도 보존했으며 pinned JAR/dependencies 내용은 변경하지 않았다. Manifest: `evidence/pinned-base-target-relocation-20261009/manifest.json`.

- **DP 전체 회귀 완료**: 첫 확장738 tests 중1개는 observation-star 전용 테스트가 새 circuit의 모든 auxiliary domain을 product로 계산해 int overflow가 난 검사기 문제였다. Production의 `isObservationStar()`와 같은 typed 구분을 적용하고 circuit은 독립 existential/forced-optimizer 테스트로 검증한다. Baseline selector까지 합친 최종 **953 tests PASS(174.246초)**, independent reviewer CLEAR. 원본 실패 로그와 최종 로그는 각각 `evidence/root-derived-fout-full-regression/`, `evidence/root-derived-fout-full-regression-final/`에 보존했다. 이는 아직 native relation 통합 전 DP snapshot 결과다.


### COFEE 통합 v5 준비: native DIRECT support의 relation-native 생성

- **통합 범위**: 검증된 derived-FOUT DP circuit을 `0e71e6e792`로 커밋하고 최신 main `0af4efd265`를 `d13f5dd407`로 병합했다. Native slice 12개 파일은 기존 `5f9edeeb2f`를 ancestor로 3-way 통합하여 upstream metadata/closure 변경을 보존했다. 기존 stash/patch와 새 cost follow-up stash는 삭제하지 않는다.
- **새 경로**: 서로 다른 owner의 완전한 독립 Cartesian support를 입력 축으로 보관하고 exact native proof/Clause는 선택된 member를 요청할 때 복원한다. 생성, Closure publication, 삭제 worklist, 재바인딩, fingerprint와 Cost Model metadata 조회가 이 관계를 직접 소비한다. Source/action/proof authority를 대표 하나로 대체하지 않는다.
- **안전한 fallback**: 동일 owner, sparse/mixed/overlap, VALUE_MAP, DURABLE_MAP publication, variable-length canonical binding 및 범위를 넘는 product는 기존 explicit 경로를 유지한다. Canonical proof 순서는 length-prefix에 영향을 받으므로 binding 길이가 다른 축을 임의 row-major로 정렬하지 않는다. Physical Model의 exact Alternative 전개는 아직 남아 있다.
- **독립 검증**: native lane 161 tests/failure0/error0/skip1. 작은 domain의 nested explicit enumeration, fixed-seed random 1–4축, canonical ordering 반례, source/proof identity, 실제 Closure 삭제 worklist와 반복 bind, 2×3 Physical/Cost/selection objective raw bits를 비교했다. Cost metadata 후속은 100만 논리 member를 Clause0개로 검사하고 exact/dynamic pool 차이를 보존한다. 실제 workload 성능으로 일반화하지 않는다.
- **병합 회귀 발견**: 첫 통합998 tests 중2실패. Upstream metadata subscription의 source/reader index가 NATIVE_LINEAGE realization도 Clause별로 순회한 뒤 VALUE_MAP이 아니라고 버려 native relation을 다시 펼쳤다. 두 함수의 VALUE_MAP guard를 clause loop 앞으로 옮겼다. 실제 metadata 확장과 reader index의 0-handle 회귀를 추가했다. 별도 reviewer는 판정/authority가 같음을 CLEAR로 확인했다.
- **계측 테스트 정정**: 기존 `supportLeaves == uniqueProofs + duplicateProofs`는 모든 논리 proof를 방문한다는 가정이었다. 현재 product는 논리 cardinality를 그대로 세고 실제 leaf 방문을 생략한다. `supportLeaves <= logicalProofs`, 엄격히 작으면 product descriptor가 존재한다는 조건으로 바꿨다. 객체/방문 감소와 합법 조합 수 감소를 구분하며 independent review CLEAR다.
- **실패 보존**: 첫998 회귀는 `evidence/merged-native-red-998`, 추가 metadata 테스트의 Collections qualification 누락 compile 실패는 `evidence/merged-native-metadata-first-compile`에 보존했다. Qualification을 고친 뒤 다시 검증한다.
- **현재 한계**: 이 시점 v5 Docker 실행은 아직 없다. v4.5 DP-only 준비는 실제 실행 없이 통합 v5로 대체했다. 실제 COFEE 최신 근거는 여전히 v4 Analysis333.489초 후 DP overflow이며, full initial planning20초 목표는 미달이다.

- **통합 v5 중간 gate**: 수정 후 focused168 PASS(39.343초), 전체998 PASS(160.254초), 컴파일된 소스와 현재 SHA mismatch0, independent metadata/metric review CLEAR. 증거 `evidence/merged-native-focused-green-168`, `evidence/merged-native-full-green-998`. 추가 upstream `ac0b5e6028`이 도착해 이 검증본을 먼저 커밋하고 최신 변경을 병합·재검증한 뒤 게시한다.

- **最新 main 통합 gate**: `ac0b5e6028`을 `b115ee00e8`로 병합했다. Production/test는 충돌 없이 reviewer의3-way preview와 byte-for-byte 일치했고, 문서는 두 evidence block을 모두 보존했다. Fresh compile 및 **1,006 tests PASS(167.58초)**, source SHA mismatch0, independent review CLEAR. 증거 `evidence/merged-ac0-native-full-green-1006`. 이 봉인본으로 실제 COFEE v5를 실행하며 아직20초 달성 주장은 없다.

### v19 이후 origin/main native relation 통합 (검증중)

- **상태/원인**: local v19 commit `8487f28f61` push는 upstream5commits 선행으로 non-fast-forward 거절됐다. 강제 push 없이 `bd00913e2140dd3ee79998def43925a2a790780c`와 merge-base `ac0b5e6028` 기준으로3-way 병합한다. Native/Closure 및 모든 test는 자동 병합됐고, 유일한 문서 append 충돌은 양쪽 증거 block을 모두 유지했다.
- **Incoming 범위**: native DIRECT support product를 Closure/metadata/cost까지 압축 유지하는 `fd5a7988d2`, derived-FOUT exact grounding circuit `0e71e6e792`와 관련 회귀를 통합한다. 기존 v17/v18 ordinal/compaction, v19 static scheduling 및 해당 테스트/계측은 삭제하지 않는다. 오직 두 commit 간 단순 diff에서만 보이는 '삭제'를 실제 incoming 변경으로 오해하지 않는다.
- **검증 계획**: 기존 v19 selector에 새 native metadata/product tests 및 직접 소비자/canonical cost 회귀를 추가한134classes를 fresh package한다. 양쪽 parent 대비 독립 semantic integration 검토 후 publish한다. 현재 병합본20초 결과는 없다.
- **v19 OFF 대조**: 계측 OFF/JFR도 LogReg/GLM 모두60초 timeout/no receipt였다. 실제 timed runner는 병합 전에 종료하고 own container 정리를 확인했다. 선택적 static/no-query 및 ordinal work 감소가 전체20초를 아직 달성하지 못했다는 제한을 유지한다.

### 병합 확장 gate의 기존 실패 분리 (진단 완료, Exact 잔여)

- **증상**: 새134class 확장 검증1,114tests에서 ContinuityRefreshReuseTest1failure와 ExactPhysicalModelCertificateTest1error, skip1을 발견했다. `evidence/candidate-v20-merged-package.log`의 최초 실패 로그는 `candidate-v20-merged-supplemental-package.log`로 보존한다.
- **원인/대조**: sealed v19 JAR를 첫 classpath로 동일9tests를 실행해 두 실패가 동일하게 재현됐다(`v19-expanded-failures-baseline.log`). No linkage errors. v19와 merged 별도 probe 모두 full misses4/hits27, incremental misses4/hits23, fingerprint/facts parity=true다. 따라서 fixture의 literal3만 stale이고 incremental rebuild 회귀가 아니다. 기대값은 정확히4로 수정하며 full/incremental equality, hit>0 및 semantic assertions는 유지한다.
- **Exact 잔여**: PCA 실패는 analyze가 아니라 optimizer(line47)에서 기존10M 한도를 넘는594,284,544-cell separator다. 원래4decision의84*56*47*21에 exact-get 관측7Boolean이 곱해진다. 원인은 activation encoding/elimination width이며 한도 확대, 후보 삭제 또는 테스트 @Ignore로 우회하지 않는다. DP우선 원칙에 따라 이 미변경 suite는 supplemental의 명시적 기존 실패로 분리하고 main mandatory gate는 나머지133classes로 수행한다. 전체 repository tests green이라고 보고하지 않는다.
- **파일/검증**: ContinuityRefreshReuseTest의 fixture literal만 보정한다. 두 probe 로그 `evidence/{v19,merged}-continuity-probe.log` 및 baseline JAR hash는 기존 seal에 있다. 독립 debugger가 scope/근거를 재검증했다.
- **위험/후속**: Exact optimizer 대형PCA는 아직 실패한다. 후순위 Exact width개선 시 유지된 회귀로 재검증한다. Merge 자체의 semantic review는 양쪽 독립 CLEAR이나20초 성능 목표는 계속 미달이다.
- **병합 mandatory gate 결과**:133classes의 fresh package는 **1,106 selected tests/failure0/error0/skip1**, BUILD SUCCESS02:29:10. 원래134class 확장 실패와 preexisting Exact/PCA gap은 위에 별도로 보존한다. v20-merged JAR/source를 봉인한 후 동일Docker로 측정하며 아직20초 성공 증거는 없다.


### COFEE v5 실제 LogReg 완료: DP overflow 해소, 20초 미달

- **봉인/게시**: main `bd00913e2140dd3ee79998def43925a2a790780c`, compiled source `b115ee00e8`, JAR `b843129dd14ac6ad318edbb6d9fa6c8725ad33b6e22851b22d5df2287cfb5db7`; 4,607 class files, 301 pinned dependencies, source/class hash mismatch0. `origin/main` remote SHA까지 확인했다.
- **실제 완료**: COFEE 50K×128 W1 원래 LogReg에서 최종 receipt의 `planningFullInitialNanos` 기준 full initial planning **403.786041598초**. 앞서402.603192964초로 보고한 값은 내부 FedPlanner stage의 `totalNanos`였으므로 전체 최초 planning과 구분한다. Stage별 Analysis316.871011623, model4.295588934, costSurface6.496764345, optimizer73.494074889, conversion1.092545917초다. 전체 cell wall435.229669초와 planning을 구분한다. 숫자 comparator의 decoded output/tree hash가 byte-identical이고 runtime audit MATCH다. 이전 DP overflow를 넘어서 실제 실행까지 도달했지만20초에는 크게 미달한다.
- **분석 지표**: 별도 Analysis 계측316.829011823초/CPU309.962초, 누적 thread allocation203,291,735,248B. 이는 peak memory가 아니다. keys27,442/facts217,553/explicitClauses2,534,754/indexedHandles2,276. supportLeaves3,919,362/logical uniqueProofs3,941,087/duplicate0: 생성 전개가 대부분 남아 있다. descriptorExpanded176,081는 성공한 완전 압축 수가 아니며, alternatives357,839,547/edges614,085,692는 재사용 summary 크기도 포함하므로 실제 새 객체 생성 수로 해석하지 않는다.
- **비교 한계**: v4는 Analysis333.489초 후 DP 실패였으므로 v5 전체403.786초와 v4 실패 wall/process를 속도비로 비교하지 않는다. Analysis의 감소도 각1회 관측이며 paired 전체 성공 workload 개선율이 아니다.
- **운영 증거**: `evidence/cofee-50k128-v5-validation/candidate-logreg-run1`. run0는 mutually-exclusive CLI filter 때문에 runtime 시작 전 실패했으며 별도 setup-failure로 보존했다. 후속 명령은 runtime-selection만 사용한다. 실제 so007/so006 read-only contention 기록은 owned coordinator/worker와 정상 서비스만 보였고, 별도 controller의 다른 Maven은 remote timing 경합으로 계산하지 않는다. 새 profiling/JFR는 실행하지 않았다.
- **다음 단계**: 동일 봉인본 GLM을 검증한다. 별도 다음 후보는 variable-length canonical native relation과 Physical compact 소비다. v5 데이터/스크립트/Y/privacy/seed/cost profile/자원은 변경하지 않는다.

### 다음 후보의 canonical rank 경계 검증

- 동일 길이 binding 축 제한을 없애되, proof authority의 decimal length-prefix 순서와 binding lexical 순서를 suffix-length count로 정확히 rank/unrank한다. 독립 explicit sort, fixed-seed1–4축, decimal prefix 경계, 모든 member 역변환과 structurally-equal foreign binding 거부를 검증한다.
- 첫 검토에서 한 suffix map을 완성한 뒤 budget을 검사하는 문제가 발견되었다. 새 state 삽입 전65,536 retained-state 한계, unique length×suffix transition 실행 전1,000,000 한계를 검사하고 같은 길이는 multiplicity로 묶었다. Arithmetic overflow는 exact fallback이다. 균일 길이는 suffix map 없이 기존 row-major를 사용하되 전체 authority length overflow를 먼저 검사한다.
- 최종 focused6suites **145 PASS(7.868초)**, static independent review CLEAR. 초기 수정본144 PASS 로그와 최종145 PASS 로그를 각각 보존했다. 이것은 아직 전체 gate나 실제 v6 성능 결과가 아니다. Suffix index의 retained memory는 기존 factor-option 계측에 별도로 반영되지 않는 제한이 남는다.


- **v5 GLM 완료**: 동일 b843129d 엔진, 원래 COFEE50K×128/W1 설정에서 `planningFullInitialNanos=142960651865` (**142.960651865초**), 내부 candidateE2E141.119558688초. Analysis97.882653608/model5.263575227/cost7.829526596/optimizer27.836522795/conversion1.763625567초, execution6.781784437초다. Comparator PASS, audit mismatch0, initial/final plan fingerprint 일치, OOM0. Coordinator peak5,284,954,112B, worker760,602,624B. 각1회 성공이며20초 미달이 명백해 v5 반복3회를 성능 채택 증거처럼 실행하지 않았다.
- **정확한 범위 보정**: LogReg의 전체 최초 planning은403.786041598초다. 앞선402.603192964초는 candidateE2E.totalNanos였다. 둘의 field/source를 timing-evidence.json에 분리했다. LogReg coordinator peak9,286,393,856B/worker739,753,984B. Combined evidence `evidence/cofee-50k128-v5-validation/first-valid-both-workloads.json`, SHA03dec9e724012dce2b63f4b6f5b4a81c8fbc4add2fff2875396271e4ba0407a7. Full goal evaluator는20초 초과로FAIL이며 native goal은 active로 유지한다.
- **Physical 후속 제한**: 강제 선택한 모든 tuple의 비용/receipt 동등성만으로는 unconstrained optimizer의 동일-cost tie 순서까지 보장하지 못한다. Consumer clause domain을 축소할 때 canonical member 순서와 producer domain 순서가 달라질 수 있어, free Local/Exact 동률 반례 및 보존 조건을 추가 검증한다. 검증 전 Physical 변경을 실제 성능 엔진에 포함하지 않는다. Rank 단독 후보는 별도 전체 gate를 진행한다.

- **Rank 단독 통합 gate**: fresh main/test compile, **1,012 JUnit PASS(177.301초)**, source SHA mismatch0, static independent review CLEAR. Evidence `evidence/native-variable-rank-full-green-1012`. Multi-member Physical compaction은 동률 receipt 반례 때문에 제외했고, 이 검증본만 v6로 게시·봉인·실측한다.


### COFEE v6 실제 완료: 생성 방문 감소가 전체 시간 감소로 이어지지 않음

- **상태/증상**: v6 `9fb272e355`/JAR `f1eedaa2187e31af274887c9419941a16c6879d91a1ded37ed91f1b77fe912e6`, 동일50K×128/W1/DML/Y/privacy/seed/profile/자원에서 LogReg fullInitial422.957952127초, GLM151.734634551초. v5의403.786/142.961초보다 느리고20초 미달이다. 양쪽 numeric comparator PASS, runtime audit mismatch0, plan fingerprint는 v5와 동일하다.
- **원인 근거**: native support leaf 방문은 LogReg3,919,362→3,072, GLM799,642→356으로 줄었지만 explicit Clause는2,534,754→2,536,770 및1,156,839→1,157,816로 거의 그대로다. `candidateTopologyMeasured`가 모든 support member를 다시 순회하고, PlacementAnalysis validation과 일부 metadata 조회도 전개한다. 잔여 proof alternative/edge 집계에는 summary 재사용 크기가 포함되므로 새 객체/DP 방문 수로 보고하지 않는다. Memo eviction0이며 capacity 증설을 해법으로 가정하지 않는다.
- **증거/재현**: `evidence/cofee-50k128-v6-validation/v6-final-summary.json` SHA77721096e2708e165282e0ca3480060ef601057cde22fdf44badb9f4606ceaa9. 각1회 clean completed run이며20초 초과이므로 반복3회 성공으로 포장하지 않는다. 새 profiling/JFR 없이 정상 harness/receipt 지표만 사용했다.

### v7 후보: native metadata 재전개 제거 및 SCC rank 관계 표현

- **상태**: focused37 PASS(9.742초), independent static review CLEAR; 전체 gate 진행중. 실제 시간 개선은 아직 검증 전이다.
- **변경/근거**: PlacementAnalysis는 각 native axis의 모든 binding과 uniform witness를 검사한다. Physical source-owner 집계와 delivered layout은 native metadata를 읽되 기존 FEDERATED unique-anchor fallback을 보존한다. NPC dynamic-layout와 exact-owner authority는 uniform metadata로 판정한다. Fixed-pool graph는 동일한 grounded pool leaf 반복만 하나로 표현하며 exact member source/proof authority는 원래 relation에 유지한다.
- **DP 변경**: `ExactDerivedFoutAnchorEncoding` SCC bit comparator의16/12/36 Cartesian lazy cells를4/4/12개 finite-support 또는 functional row로 표현한다. 변수, scope, factor 순서, logical-cell 회계,0/+INF raw bits는 같다. 모든 cell explicit parity와 fixed-seed free assignment/tie parity를 검증했다. 이 gate가 실제 optimizer74초의 주원인이라고 주장하지 않는다.
- **수정 파일**: NativePlacementContinuity.java, PlacementAnalysis.java, ExactPhysicalModel.java, ExactDerivedFoutAnchorEncoding.java와 해당 focused 회귀 tests.
- **검증/실패 보존**: metadata 기존 구현은400member 모두 materialize하여 새0-handle assertion에 실패했다(`evidence/native-fixed-metadata-red-13`). 수정 후 exact/dynamic의 alias resolution·ownerReads·action authority explicit parity 및0handles 통과.10,000member dynamic 조회도0handles다. Sparse gate 초기 red와15PASS는 `evidence/derived-rank-sparse-red`, `evidence/derived-rank-sparse-green-15`; 통합 focused37PASS는 `evidence/native-metadata-sparse-focused-green-37`.
- **제외/잔여 위험**: multi-member Physical compaction은 unconstrained equal-cost canonical receipt 반례가 있어 제외했다. Physical exact fallback과 proof topology member 전개는 남는다. AND(per-axis OR) proof graph 설계는 full rectangle/DIRECT/distinct owner만 대상으로 하며 SCC·cache·fixed pin·authority 검증 전에는 통합하지 않는다. Metadata shortcut의 회귀는 dynamic layout/FEDERATED fallback/explicit parity 및 전체 회귀로 감지한다.

- **v7 병합 전 gate**: fresh compile 및1,017 JUnit PASS(185.856초), source SHA mismatch0, independent static review CLEAR. Encoded Local과 Exact의 canonical assignment/raw objective parity까지 통과했다. 증거 `evidence/native-metadata-sparse-full-green-1017`. 이후 도착한 origin/main d27fa82b44의 pruning ordinal/native projection 변경을 병합하여 재검증한다.

### v21 product projection / v22 topology 재사용 계획 (진행중)

- **문제/근거**: incoming native support product가 publication에서 압축돼도 continuity projection이 모든 Cartesian clause를 펼친다. 기존 v19 OFF GLM sample에서 topology revision reindex가237/2,884(8.2%)를 차지한다. 동일 실행 relation을 유지하는 proof-history revision도 snapshot identity가 달라 모든 topology row/default edge를 재생성한다.
- **v21 변경 계획**: private continuity realization projection에서 native product의 identity-sensitive binding axes와 clause witness/exactness만 O(sum axis widths)로 보존한다. Explicit와 product 표현 사이의 비교는 보수적으로 unequal, 동일product만 동일 projection을 허용한다. 기존 metadata footprint/public replay authority 및 root-history 정책은 변경하지 않는다. Static/owned/donor projection을 검증하되 별도 skeleton migration 전체가 zero-materialization이라고 주장하지 않는다.
- **v21 red**: sealedv20에서는 variable-length product fixture가 admission실패했고, incoming9fb의 Native만 isolated compile한 첫red는 native proof가 없는 invalidfixture1개+실제0vs4materialization1개였다. Fixture를 owned proof로 고친 최종red는106tests에서2개 모두 기대0vs4materialization실패다(`v21-corrected-red-test.log`). 앞선 fixture 오류를 성능 회귀 증거로 사용하지 않는다.
- **v22 계획/안전 경계**: unchanged owner+metadata authority gate는 그대로 유지하고 동일structuralContext 안에서 destination의 모든 row/pin handle이 기존과 같은 양수arena ID인 경우에만 raw topology를 공유한다. Revision-local 음수ID는 일치해도 재색인한다. `reindexTopology`도 이전row reference/pin을 그대로 유지하므로 snapshot 객체 동일성은 별도 authority보호가 아니며, destination mapping검사가 실제필요조건이다. Independent debugger가 existingnegative/foreign-owner/collision/crossarena 경로를 검토했다. 기존 fallback 및 shifted-arena differential을 잠근 뒤 구현한다.
- **게시 경합**: e8e5e08f5e push중 incoming9fb272e355가 먼저 게시돼 다시 non-fast-forward 거절됐다. 강제push없이 native variable-length canonical rank index를 추가3-way병합한다. v20봉인bin은 그대로 두고 incoming+v21/v22를 다음freshgate로 검증한다.20초 목표는 미달이다.
- **잔여/위험**: 표현 비교가 더 엄격해 cachemiss가 증가할 수 있으나 합법후보를 제거하지 않는다. 잘못된 handle공유는 pinned dependency를 바꿀 수 있으므로 음수/crossarena/foreign identity회귀 및 warm/cold ordered proof대조로 검출한다.
- **v21 경계 fixture 보정**: static projection은0handles가 맞지만 hidden native metadata warming은 기존 owner-authority lookup에서1member를 선택한다. 따라서 integration은 warming의1을 기록한 뒤 owned/donor revision이 old product에 추가0/replacement product0임을 검사한다. 이후 실제 cold/warm support query가 replacement의1member를 고르는 것은 별도로 기록하며 전체경로0materialization이라고 주장하지 않는다. Withdrawal/restoration proof+identity-footprint parity는 그대로 잠갔다.
- **v22 회귀 검증**: equal-new snapshot의 raw topology identity-sharing은 적용 전108tests에서 기대실패했다. Cross-arena row+pin drift 외에 row ID는 같고 pin ID만 다른 경우를 추가하고, pin-equality guard만 제거한 외부 임시 mutant에서108tests 중 바로 이회귀1개가 실패했다. Production은변경하지않은 mutation이다. 올바른코드는 focused4suites133tests/skip1통과. Arena prefix의 초기 fixture는 우연히pinID2가같아 setup assertion실패했으며, 전체old IDprefix를 점유하도록 고쳐정확한drift를강제했다.
- **계측/리뷰**: shared/reindexed rows는 cache admission에 성공한 행만 계수한다. Positive path shared>0/reindexed0을assert하고 negative namespace기존회귀유지. v21/v22 production 독립review CLEAR. Equal-but-foreign productsource identity추가assert를 포함해 fresh133class package를 수행한다.
- **v20 실측**: LogReg/GLM detailed모두60초 timeout/no finalreceipt. LogReg39,037graphs/4.328Mstates/54.283Malternatives/91.784Medges, GLM74,589graphs/375,852states/6.445Malternatives. Partialprogress라이전버전과전체속도비교로환산하지않는다. v22의가변길이 productrank, 축projection, topologyrawsharing효과는다음봉인측정에서확인한다.
- **v22 full gate**: incoming9fb + v21/v22의 fresh133class package BUILD SUCCESS02:39:48, failure0/error0/skip1. Source/JAR 봉인 후 origin/main에 merge 게시를 재시도한다. Supplemental Exact/PCA 기존 실패는 여전히 위 별도항목이며 전체repository green으로 주장하지 않는다. Pin guard mutation은실제고장1개를검출했고 privateproduct의equal-but-foreign source identity까지fullgate에포함했다.


- **v7 최신 main 병합**:2290e8b2f3의1,017PASS 수정본에 origin/main d27fa82b44를8ac7604a68로 병합했다. Production은 자동 통합됐고 docs append 충돌은 양쪽 기록을 보존했다. 첫 focused142 중1실패는 upstream 테스트의 metadata1handle 기대값이 신규0handle 경로와 달랐기 때문이다. 의미 비교는 그대로 유지하고 두 작업량 기대값만0으로 갱신했다. 실패 `evidence/native-v7-d27-merge-red-142` 보존.
- **Revision authority 후속 검증**: native product externalSeed만 바뀌고 실행 metadata가 같은 revision에서 warm/cold ordered proofs·identity footprint를 양쪽 query seed로 비교했다. 공개 proof는 현재 query seed를 가지며 각 relation의 exact member proof history는 서로 다르다. Focused143PASS(2.061초), independent review CLEAR. 증거 `evidence/native-v7-d27-focused-green-143`; 최종 전체 gate에는 query 후0handle 추가 assertion도 포함한다.

- **v7 최종 통합 gate**: d27fa82b44 병합 및 seed-history regression 포함 fresh compile,1,033 JUnit PASS(161.85초), source SHA mismatch0, independent static review CLEAR. 증거 `evidence/native-v7-d27-full-green-1033`. 이 봉인본을 동일 COFEE 설정으로 실행하며20초 달성 여부는 실제 receipt로 판정한다.

### v23 stable-handle bucket 선형 검사 (검증중)

- **문제**: v22 guard가 한 reference의 여러 topology row마다 같은 handle bucket을 identity검색하여 O(sum bucketWidth²)가 될 수 있다. v22 실측에서 raw-shared rows LogReg1,094,877/GLM2,423,353, reindexed0이므로 이검사도 반복비용이 된다. 양쪽여전히60초timeout/no finalreceipt다.
- **변경/증명**: private topology builders는 모든 exact row를 정확히 하나의 handle bucket에 넣는다. Bucket을 한 번 순회해 destination rowhandle==positivebucketkey와 기존 null/positivepin 검사를 유지하고, visited==rows.size를 방어적으로 확인한다. 추가map/후보축소/해시동치완화없음.
- **회귀**: 실제64clause/64row/하나의reference bucket을 만들고 privateconstructor에CountingList bucket만 대입해 same-fact revision을 실행한다. Applying전109tests 중linearwork assertion1개가실패했고, Applying후 focused4suites134tests/skip1PASS. Ordered proof/identity-footprint coldparity, row/pin-only shiftedarena와negativefallback도유지한다. 시간임계값 대신bucket실제접근횟수로검증한다.
- **위험**: 향후다른constructor가 row-index consistency를깨면linearcheck전제가깨진다. 현재세construction site의동일row단일삽입을확인했고, 전체기능회귀및widebucket검사를유지한다. 완전planning20초효과는아직없다.
- **후속 v24는 계측만**: recipe/emission/witness가같은generatedquery들을batch로재사용할수있는가설을독립architect가검토했다. 하지만실제공유가능비율을모르므로 먼저기존exactmemo결과에대한bounded shadowindex로잠재graph회피수만측정한다. Root-history-independent+root-owner-freebinding, exactowneridentity및기존cachebudget을모두만족해야하고, 결과/현재query수는변경하지않는다. 반복이적으면실제cache구현을기각한다.
- **v23 full gate 결과**: fresh package **1,117 selected tests/failure0/error0/skip1**, BUILD SUCCESS02:46:46. 독립architect productionreview CLEAR이며봉인후게시한다. Widefixture에서정확히64visits와실제rawsameidentity를직접assert하는보강은후속test변경으로분리한다(현재oldcode복잡도red는유효). 기존Exact/PCA supplemental gap은해결주장하지않는다.

### v24 generated-query shadow 계측 / native product metadata (진행중)

- **게시**: v23 `689760d079a7ce818034533a0f9003a0a10fc888`을origin/main push하고remoteSHA를확인했다. 봉인JAR `830789df47865f24fa242a64fb13398b06241c9cea6c019c33d56533b9cf6945`로동일Docker측정한다.
- **shadow계획**: 실제batch재사용은아직구현하지않는다. Exact support/publicmemo keys와LRU순서를그대로두고, recomputeNative의한resolver/fact/emission seedloop에만metrics-ON observer를둔다. 실제graph miss 직전의동일witness에resident certified key가있는지만세며, entry자체는새cache에보유하지않는다. rootIndependent 및returned binding의rootowner없음을검사하고 foreign-owner proposal을identity로제외한다. Product는axes만검사한다. Zero-cache/eviction/saturation도분모와분리한다.
- **초기test gate**: 신규observer API를직접참조한3tests는아직API가없어compile6missing-symbol로red다. 실행결과의semantic실패로혼동하지않는다. 자체shadow가결과를변경하지않는orderedproof/footprint parity와counter를이후검증한다.
- **별도metadata병목**: `hasDynamicNativeLayout(List)`는기존factorized/indexed표현만special-case하고 NativeContinuitySupportClauses는exact(false결과)검사에모든member를생성했다. 이표현의모든member는같은clauseWitness/clauseLayoutExact를사용하므로이를직접읽는다. Proof의partitionRanges와clause의layout을혼동하지않는다.17tests중실제0vs4materialization1red를확인후3linebranch를추가했다. proofExact/clauseExact의독립조합, nullwitness와separateexplicit-oracle를검증한다.
- **v23 tests-only 보강**: 봉인후widebucket검사를정확히64회및실제topology assertSame으로강화했다. 이는v24 gate에포함하며1117봉인결과를새test검증으로과장하지않는다.
- **위험/잔여**: shadow가LRUget으로순서를바꾸거나product를펼치면관측자효과가된다. containsKey만사용하고각loop수명을제한하며OFF동등성을검증한다. 후보/합법성/privacy/실제graph선택은변경하지않는다.20초달성은여전히미확인이다.

- **v24 focused/matrix gate**: isolated javac 후 첫 148 tests PASS/skip1(Composition 포함), 테스트만 추가한 후 Native/NativeProduct/DefaultPruning 140 tests PASS/skip1. 두 집합은 겹치므로 합산하지 않는다. A(X)→B(Y)→C(X)의 budget2 LRU 순서를 observer-null 대조와 매 단계 비교했고, containsKey가 get으로 바뀌면 eviction 순서가 바뀌는 회귀를 잠갔다. Foreign fact/emission/root/resolver identity, zero budget, saturation/eviction, negative missing-root의 순서/footprint, root history와 root-binding 및 lazy axes 검사도 포함한다.
- **독립 검토**: architect는 shadow-only production과 constant metadata branch를 CLEAR로 확인했다. Actual batch result reuse는 미구현이며 별도의 증명/검증 gate가 필요하다.
- **계측 해석 제한**: repeat potential은 같은 emission loop에서 관찰된 실제 MISS 중 기존 certified key가 resident인 경우만 세는 bounded lower bound다. 기존 public/support memo HIT는 index에 넣지 않으므로, 낮은 수치가 전체 재사용 가능성이 없다는 증거는 아니다. Saturation/eviction도 과소계수 요인이다. 후보 축소나 비용/합법성 규칙은 변경하지 않는다.
- **v23 Docker 결과**: pinned image/4CPU/16GiB/10GiB heap, JFR55s/watchdog60s에서 LogReg·GLM 모두 timeout/no completion receipt. own container 제거를 확인했다. Inclusive samples에서 direct binding은 각각52.98%/18.53%였으나 완료 wall-time의 속도 향상으로 해석하지 않는다. Probe는 production DMLScript compile-only 경로로 runtime-program 생성 이후 full-initial planning receipt를 검증한다. `liveMetrics=false`는 DMLTranslator.productionSearchSpaceMetrics가 null을 반환하므로 상세 metrics OFF이며 JFR만 유지된다.
- **v24 full gate 완료**: fresh 133-class package **1,125 selected tests/failure0/error0/skip1**, BUILD SUCCESS 03:00:55. `candidate-v24-package.log`와 source/JAR seal을 보존한 뒤 게시·Docker 계측한다. 기존 Exact/PCA supplemental width 실패는 이번 selected gate에 포함하지 않았고 해결 주장하지 않는다.

- **추가 원격 병합/게시**: c6be0b6916 push는 원격689760d079가 먼저 갱신되어 fast-forward 거부됐다. 강제 push하지 않고 fbc30a527c로 병합, docs 양쪽 보존 및 NPC production/test 자동 통합. 변경 범위 continuity/revision/pruning focused188PASS(4.508초), source SHA mismatch0, independent review CLEAR. 앞선 통합1,033PASS와 함께 검증 근거로 보존한다(`evidence/native-v7-689-focused-green-188`). 실제 v7는 이미 봉인한 정확한 c6be/JAR5172e29a를 계속 사용하며 이 추가 커밋을 소급 포함하지 않는다.


### DP 부분 고정 시 sparse relation의 dense 재전개 제거 (v8 후보)

- **문제/근거**: SharedRegionalPreparation.condition은 boundary 밖 입력을 고정한 뒤 남은 free 축 product를 lazy wrapper+freeze로 전부 평가했다. IncrementalRegionalSeed.condition도 같은 product의 double[]를 만들었다. 원본이 FiniteSupport/FunctionalMap이어도 표현을 잃었다. 실제 optimizer74.5초 중 이 경로의 기여도를 새 profiling으로 측정한 것은 아니다.
- **해결/파일**: ExactCategoricalSolver.Factor.conditionSupport는 sparse admitted row만 filter/project한다. 고정된 singleton 축을 제거하는 projection은 injective이며 row-major 정렬을 유지한다. FunctionalMap은 source 고정 시0/1개 target, target 고정 시정렬된 source preimage, 양쪽 고정 시scalar true/false로 처리한다. SharedRegionalPreparation/IncrementalRegionalSeed가 먼저 이 경로를 소비하고 일반 numeric/conditional relation은 기존 fallback을 유지한다.
- **검증**: 기존 구현은 새4tests 중3개 sparse 표현 유지 검사에서 실패(`evidence/sparse-conditioning-red-4`). 수정 후6suites55PASS(1.357초), independent review CLEAR(`evidence/sparse-conditioning-focused-green-55`).160,000 free cells의 예제가 sparse3rows를 유지한다.35fixed-seed random 관계의 모든 작은 boundary·sparse holes·zero axes, functional -1/unmapped/frozen map, raw cost bits와 exact canonical minimum을 explicit table과 비교한다. Regional preparation/seed lift/incremental 회귀도 통과했다.
- **의미/회귀 위험**: +0/+INF hard relation만 새 경로를 쓴다. 변수 identity/order와 source 관계의 정확한 feasible cells를 보존한다. Seed의 기존 conceptual cell/resource limit 판정은 sparse 처리 전 동일하게 적용하며, 조건부 비용/공유 비용 수식은 변경하지 않는다. 따라서 이전에 conceptual limit으로 거절된 sparse case를 새로 통과시키는 개선은 아직 없다. 실제 workload 시간/peak와 full gate는 별도 확인한다.

- **v8 DP conditioning 통합 gate**: fresh main/test compile 및1,038 JUnit PASS(180.381초), source SHA mismatch0, independent static review CLEAR. 증거 `evidence/sparse-conditioning-full-green-1038`. 본 snapshot은689760의 linear topology check를 포함하나 별도 개발 중인 synthetic axis gate는 아직 포함하지 않는다.


### COFEE v7 완료 및 native axis gate 후속 검증

- **실제 결과**: 정확한 c6be/JAR5172e29a 봉인본의 LogReg fullInitial460.486971960초, GLM236.147365008초로 v6보다 느렸다. Analysis372.203410278/190.022935095초, optimizer73.826497478/25.564320545초다. 숫자 비교 PASS, audit mismatch0, v5/v6와 동일 plan fingerprint. Coordinator peak9,110,831,104/5,587,062,784B. 실제 소스에는 이후689 linear topology check와 sparse conditioning이 없으므로 이를 v7에 소급 포함하지 않는다. `evidence/cofee-50k128-v7-validation` 보존.20초 목표는 미달이다.
- **후속 변경**: pinned native realization proof를 clause별 OR로 펼치는 대신 consumer AND와 입력별 OR gate로 유지한다. 기존 full product/distinct source owner/native authority 검사를 통과한 관계만 적용하며 기존 dependency skeleton에서 invariant와 owner별 pinned dependency를 분리한다. 실제 선택 exact authority는 원래 relation에 남기고 gate 자체는 authority가 아니다. query-local gate identity와 SCC/footprint 처리를 사용하며 mixed/ambiguous/unsupported 관계는 기존 경로로 돌아간다.
- **검증 범위**: 기존20member product가 같은 ordered proof와 identity footprint를 유지하며 clause handle20→1, graph work가 감소한다. Representative1개는 dependency skeleton 추출용이며 전체경로0materialization으로 주장하지 않는다. 죽은 axis, self-cycle, nested source invalidation과 duplicate authority fallback을 검사했다. 고정 seed20회,1–4축/폭1–4의 exact/dynamic relation과 source withdrawal을 explicit reference에 비교했다. Random test independent review CLEAR. logical 조합 수는 그대로이며 줄인 것은 표현/작업이다.
- **중간 실행**: 첫 integration JUnit 명령은 잘못된 test FQCN으로 initialization error였다(`evidence/native-axis-gate-integration-launch-error`). 실제 테스트 실패로 해석하지 않는다. 올바른 suite로 재실행한213tests는4.266초 PASS(`evidence/native-axis-gate-integration-green-213`). Random parity를 더한 fresh full gate는 별도로 실행한다.
- **적용 제한**: 이 후보의 fast path는 exact source로 pinned된 state에 한정된다. Generated root가 unpinned native child를 읽는 경로는 아직 전체 topology를 펼칠 수 있어 별도 후속 수정/검증으로 분리했다. Physical multi-member 압축은 equal-cost receipt 반례 때문에 계속 제외한다.

- **Pinned axis gate 최종 gate**: fresh main/test compile,1,042 JUnit PASS(168.145초), source SHA mismatch0, production/randomized test independent review CLEAR. 증거 `evidence/native-axis-gate-full-green-1042`. Unpinned 후속 경로와 actual workload 성능은 이 테스트 결과에 포함하지 않는다.

### v24 / 最新 native metadata·sparse DP 병합 (검증중)

- **문제/원인**: v24 own gate 1,125 PASS 후 fetch에서 origin/main이6commits 앞선 `3d8e59378f`임을 확인했다. Native metadata 소비 및 sparse DP conditioning을 force push 없이 병합한다.
- **해결/파일**: docs append 충돌은 양쪽 기록을 유지했다. NativePlacementContinuity의 자동 merge가 같은 `NativeContinuitySupportClauses` dynamic-layout branch를 두 번 넣어, 증명/테스트가 동일한 첫 branch 하나만 남겼다. Incoming exact-owner/fixed-pool metadata 및 sparse factor conditioning은 보존한다. Shadow observer의 authority/LRU/bound는 변경하지 않는다.
- **검증 계획**: 신규 DerivedFoutRankRelationTest/ExactSupportConditioningTest 포함135classes fresh package 및 독립 merge review를 진행한다. 원본 v24 seal은 보존하고 병합본을 별도 봉인한다.
- **잔여/위험**: 기존 Exact/PCA supplemental width 실패와20초 미달은 남아 있다. Incoming native metadata의0handle 동작은 기존1handle fixture기대를 갱신하되 ordered proof/identity-footprint authority 대조는 유지한다. 후보/합법성/privacy 규칙은 완화하지 않는다.
- **병합 gate 완료**: fresh135class package **1,135 selected tests/failure0/error0/skip1**, BUILD SUCCESS03:04:12. 독립 integration review CLEAR; duplicate branch 제거를 확인했다. Incoming metadata와 sparse DP 회귀 및 v24 observer의 cold/warm/identity/LRU parity를 함께 검증했다. 병합본만 `candidate-v25-merged`로 따로 봉인·실측한다.

### v26 completed component summary 중복 제거 계획 (test-first)

- **문제/근거**: cacheAcyclicComponent는 viability가 증명된 각 원래 clause를 `(동일 reference, 빈 dependency, true, 동일 witness)`로 바꾼 뒤에도 중복 행을 모두 보관한다. 한 realization의64clauses가64행 budget을 사용하고 재사용 시 같은 경계 작업을 반복한다. 실제 중복률은 아직 계측되지 않았다.
- **독립 설계**: architect는 원래 AND dependency를 pruning 전에 합치지 않고, 이미 ground된 summary 안에서만 정확히 같은 row를 합치는 범위를 CLEAR로 확인했다. Owner identity+reference equality+witness equality, null marker를 구분하고 첫 생존 대표/순서를 유지한다. 기존 cap은 distinct stored rows에 적용하며 증설하지 않는다.
- **보존 조건**: 원래 pre-pruning occurrence footprint/retainedStates, hidden metadata, generated-root 제외, 음성 summary, revision invalidation을 유지한다. 결과 proof의 권한이나 completion receipt는 새로 만들지 않는다.
- **검증 계획**: 64clauses/1reference/cap1 실제 graph의 회귀를 먼저 red로 확인하고, 기존2references/cap1 bypass·cold/warm orderedproof·identity-footprint·withdraw/restore·negative·root recurrence·metrics OFF/zero budget을 검증한다. 대표 압축 전후 rows와 reuse/bypass 카운터를 실제 Docker에서 측정한다.
- **결정/위험**: oracle/runtime/privacy 후보를 바꾸는 pruning이 아니라 검증 완료된 내부 경계 표현만 압축한다. 동치 기준이 약하면 다른 source/witness가 합쳐질 수 있어 foreign-owner identity 및 witness 회귀로 검출한다.20초 효과는 검증 전이며 앞선 v25봉인본 측정과 분리한다.

- **v26 실제 work red**: 현행 v25를 classpath로 새64clause/cap1 graph 회귀를 실행했다.118tests 중 정확히1개가 shared summary 미보존으로 실패했다(`v26-red-test.log`). 최초 실행은 JDK vector module을 누락해 기존 reshape 테스트까지 실패했으며 별도 `v26-red-missing-vector-test.log`로 보존했다. `--add-modules=jdk.incubator.vector`로 수정한 결과만 유효 red로 사용한다.
- **v25 shadow 결과**: 동일 sealed merged 엔진의 LogReg·GLM 모두60초 timeout/no receipt다. 관측 실제 graph misses 중 certified resident repeat potential은 각각21,105/32,498(약65%),56,079/70,822(약79%)였다. 이는 bounded miss-stream 하한이며 actual reuse나 전체 wall-time 개선 수치가 아니다. Root history 거절2,164/0, returned root binding 거절0/0, saturation0/0이다. 실제 batch 재사용은 별도 alpha-renaming 안전 증명과 회귀 이후에만 구현한다.
- **v26 fixture 수정/최종 focused**: FULL public query가 exact+dynamic witness 둘을 검사하여64행 기대가128이 됐다. Cap1 재사용을 명확히 검사하도록 BROADCAST 단일 witness로 고쳤다. 추가 mixed fixture의 equal-but-foreign clause는 canonical duplicate여서 생성 자체가 거절됐으므로, 같은 producer의 미선언 durable realization을 dead pin으로 사용했다. Canonical 길이 prefix 때문에 삽입 위치와 정렬 위치가 달랐던 실패도 보존하고, 동일 길이의 앞서는 key와 실제 canonical 첫 binding assertSame으로 dead-first를 잠갔다. 이후 첫 root로 warm하고 둘째 root가 summary를 실제 재사용하는 counter 증가를 검사했다. 최종143tests/skip1PASS(1.062초), withdrawal/all-negative/restoration의 ordered proof와 identity footprint cold parity도 통과했다. 각 초기 fixture 실패는 evidence/v26-*에 보존한다.
- **v26 독립 검토**: 새 architect가 실제 production diff를 읽어 CLEAR로 확인했다. HashSet은 출력 순서를 공급하지 않고 첫 생존 행만 남기며, null row도 witness를 정확히 비교한다. 중복 판정 뒤에만 grounded row를 할당한다. 원래 graph pruning/전체 footprint/음성 cache/root 제외/합계 budget은 유지한다. Fresh135class 전체 selected gate를 이어서 수행한다.
- **v26 full gate**: fresh135class package **1,137 selected tests/failure0/error0/skip1**, BUILD SUCCESS04:50:38。원본 엔진을 별도 봉인한다. Fetch에서 incoming pinned native axis gate `6a7096f34b`를 발견해, 검증한 v26을 먼저 커밋하고3-way 병합·재검증 후 main에 게시한다. 실제시간은 아직 미측정이며20초 성공 주장은 없다.

### v26 / pinned native axis gate 병합 (검증중)

- **상태/해결**: v26 `c56baecf24`와 incoming `6a7096f34b`를3-way 병합했으며 production/test/docs 모두 자동 통합됐다. 새 query-local axis gate의 summary 제외 및 전체 footprint 처리를 v26 grounded-row 압축과 함께 독립 검토한다.
- **검증/위험**: 기존135class selector가 새 NativePlacementContinuityTest 회귀4개도 포함한다. Fresh package 후 별도 엔진으로 봉인하여 원본 v26과 혼동하지 않는다. Synthetic gate는 proof authority가 아니며, SCC/고정 pin/identity/invalidation을 약화하지 않는다. v27 generated batch는 아직 미구현이며 새 gate와도 alpha-renaming 증명을 재검토한다.
- **병합 독립 검토 BLOCK / 새 회귀 추가**: 기존 selected1,141tests는 통과했지만 reviewer가 incoming axis gate의 두 표현 경계를 발견했다. (1) query-fixed root와 다른 축 pin을 단순 제거하여 기존 A→B overlay와 달라질 수 있다. (2) 한 owner가 여러 compiled input position에 쓰일 때 gate가 하나의 position만 노출한다. 새 explicit-vs-factored 두 회귀를 먼저 실행하고, 확인되면 해당 표현에서만 legacy exact 경로로 fallback한다. 후보를 제거하거나 oracle/privacy를 완화하는 가드가 아니라, 아직 증명되지 않은 압축 표현을 사용하지 않는 조건이다. v27 alpha-renaming도 이 수정 이후 재검증한다.
- **두 문제 실제 재현/수정**: 새125tests에서 두 기대 실패를 확인했다(`v26-axis-red-test.log`). 다른 root pin의 explicit 결과는 proof1개인데 gate는0개였고, 반복 producer의 explicit는0·1 양쪽 binding인데 gate는0만 반환했다. Fixed owner 축 또는 affected nonnegative position이 축의 단일 position과 정확히 일치하지 않으면 기존 exact topology를 사용한다. 적용 후149focused tests/skip1PASS(0.884초). 새 조건은 fast-path 적용 한계이며 지원 후보를 배제하지 않는다. 기존 canonical proof/order/full identity footprint 대조와 random axis gate 회귀를 유지한다.
- **수정 병합 최종 gate**: 두 회귀 수정 후 fresh135class package **1,143 selected tests/failure0/error0/skip1**, BUILD SUCCESS04:59:29. 독립 architect는 두 fallback 및 v26 보존 조건을 실제 코드에서 재검토하여 CLEAR로 변경했다. Synthetic gate의 보수적 root-history 처리도 유지했다. 이 결과만 수정된 병합본 봉인·게시의 근거로 사용한다.

### Pinned gate 원격 게시, 순환 overlay 반례, unpinned 경로 수정

- **게시**: pinned gate ab4b54e0ba와 origin29b68fc0b4를6a7096f34b로 병합했다. 전체1,042PASS 뒤 병합범위144PASS(0.972초), source SHA mismatch0을 확인하고 origin/main remote SHA를 검증했다. 첫 병합 test 명령은2개 잘못된 FQCN으로 initialization error였고 올바른 이름으로 재실행했다(`evidence/native-axis-gate-merge-launch-error`, `native-axis-gate-merge-green-144`).
- **후속 정확성 반례**: 별도 root 검토에서, native product의 입력 source owner가 현재 query root일 때 historical pin을 현재 root pin으로 대체하는 기존 overlay 의미를 새 gate filter가 강화할 수 있음을 확인했다. Grounded recurrence explicit reference는 proof1개, native gate는0개였다(`nativeRootOverlayPreservesGroundedRecurrenceWithDifferentPublishedPin`, `evidence/native-axis-root-overlay-red-124`). 해당 fixed owner 축은 native gate를 사용하지 않고 정확한 기존 topology로 돌아가도록 수정했다. v9 c489728b 봉인본은 실제 실행 전에 excluded로 표시하고 acceptance에 사용하지 않는다.
- **Unpinned 생성 경로**: 모든 matching executable realization이 독립 native product일 때만 realization별 OR와 입력별 gate를 만든다. Derived action/mixed VALUE_MAP/중복 authority/지원 불가 관계는 전체 기존 경로로 fallback한다. 기존4×5 fixture에서20개,2×3 fixture에서6개 handle을 생성하던 red를 확보했다. 여러 native realization의 canonical ordered proof, source withdrawal, empty axes, dynamic witness를 explicit와 비교한다.
- **테스트 fixture 보정**: 첫 multi-family fixture는 explicit reference가 사용한 lazy relation을 그대로 재사용하여 기존6handles를 새 query 작업으로 잘못 셌다. Reference와 candidate relation을 분리했다. Duplicate realization은 constructor가 dedup하므로 중복 fact를 넣어 실제 ambiguous authority를 만들었다. 최초 recurrence fixture의 historical pin은 current와 같아서 별도 lineage로 고쳤다. 이 setup 실패를 production 의미 회귀로 계산하지 않는다. 최종 별도 lineage 반례의 실제 proof 차이만 정확성 회귀 근거다.
- **검토 제한**: 병렬 구현/독립 reviewer/workload 에이전트가 사용량 제한으로 종료되어 root가 남은 구현과 검증을 이어받았다. 앞선 pinned core/random test의 독립 CLEAR와 새 unpinned 수정의 root 검토를 구분한다.
- **실제 v8 완료**: LogReg398.232339626초/GLM124.946316443초, numeric PASS, audit mismatch0, 동일plan이다. Peak9,141,903,360/5,134,458,880B. 동일DML/Y/자원/profile/seed이며 v8에는 gate 변경이 없다. 각1회이며 evaluator는20초 초과로FAIL이다.

- **Unpinned/overlay 통합 gate**: fresh main/test compile,1,053 JUnit PASS(184.653초), source SHA mismatch0(`evidence/native-unpinned-overlay-full-green-1053`). 새 grounded recurrence 반례, generated unpinned child, multi-family/duplicate/mixed fallback 및 fixed-seed exact/dynamic source withdrawal이 포함된다. 이 수정본을v10으로 별도 봉인하여 실제 실행한다.


### Conditional support의 부분 고정 시 union-of-products 유지

- **문제**: v8은 finite support와 functional map만 부분 고정 시 압축을 유지했다. ConditionalSupport는 원래 region 표현을 잃고 남은 축의 product를 다시 평가했다.
- **변경**: selector가 free인 경우 각 region을 고정된 축의 membership으로 필터링한 뒤 고정 축만 제거한다. 원래 constrained selector 집합을 그대로 보존하여 모든 region을 잃은 selector가 wildcard가 되는 오류를 방지한다. Selector까지 고정된 경우는 selector-free union 표현이 없어 기존 exact fallback을 유지한다. 숫자 factor와 seed의 기존 resource preflight는 그대로다.
- **검증**: 기존 구현에서6tests 중2개 표현 검사 실패(`evidence/conditional-conditioning-red-6`), 수정 후6PASS(0.332초).480,000셀 관계의 선택 목록 저장은20개 미만이며 wildcard/forbidden selector와 sparse holes를 검사한다. 고정 seed30관계에서 모든 작은 boundary, selector 위치0/1/2, 빈/겹친 region, selector까지 고정된 fallback을 explicit table과 비교하여 raw cost 및 canonical Exact optimum/assignment parity를 확인했다. 두 실제 conditioning consumer를 모두 통과시켰다. 이 수치를 실제 workload 시간 개선으로 일반화하지 않는다. 전체 gate는 별도로 기록한다.

- **Conditional conditioning 전체 gate**: fresh compile,1,055 JUnit PASS(185.656초), source SHA mismatch0. 증거 `evidence/conditional-conditioning-full-green-1055`. v10 실제 실행은 기존 봉인본 그대로 유지하며 이 후속 변경은 별도 엔진에 포함한다.
- **추가 DP boundary 병합 gate**: `78a25c8e60`의 조건부 product 제한은 independent architect CLEAR. 현재 병합 소스의 ExactCategoricalSolver 및 새 test를 isolated compile하고 ExactSupportConditioning/ExactConditionalSupportSolver/SharedRegionalPreparation/IncrementalRegionalSeed **29tests PASS(0.946초)**를 확인했다. Native 미변경으로 앞선1,147전체 gate와 이 추가 경계 검증을 구분하여 게시한다. 봉인37ffd4ab는 추가 DP 변경 전 엔진이며 실측에 소급 포함하지 않는다.

### v27 emission-local generated support 실제 재사용 (test-first, 진행중)

- **문제/근거**: v26 pinned+summary 상세 진단에서도 certified resident 반복은 LogReg19,478/29,774, GLM57,817/73,112다. 잠재치이며 실제 재사용은 없었다. 새 회귀는 기존 API로 A 이후 B의 graph build가 증가하지 않아야 함을 검사했고, 적용 전129tests 중 정확히1개가 expected2/actual4로 실패했다(`v27-red-test.log`).
- **계획/안전 증명**: 같은 resolver·fact·emission 객체, root owner identity와 full witness에 한정한다. Generated dispatch의 fixed-root alpha-renaming이 graph/hidden read/footprint를 보존하더라도 prior root-history-independent 및 returned-root-binding 없음 두 certificate를 모두 요구한다. Fixed-owner native axes는 이미 exact fallback으로 보존된다.
- **변경**: metrics-only observer를 실제 batch로 교체한다. 기존 bounded support memo의 resident key만 index하고 새 proof cache/alias B support entry는 만들지 않는다. Actual hit는 기존 LRU를 갱신하며 새 externalSeed를 instantiate한다. Root-free product axes와 canonical suffix/order를 유지하고, 바깥 exact/dynamic filtering 및 final public memo의 exact proposal key는 그대로다. Metrics OFF에서도 사용하고 기존 어느 support budget이0이면 batch를 만들지 않는다.
- **검증/위험**: old published/unpublished 양방향, cycle, VALUE_MAP/derived hidden history, returned root binding, full witness, owner/fact/emission/resolver/revision identity, metricsOFF, zero/oversize/eviction/saturation/LRU/noalias/product laziness 회귀와 독립 검토가 게시 gate다. 규칙/후보/비용/privacy 제약은 변경하지 않는다. 아직20초 달성 및 실제 속도향상을 주장하지 않는다.
- **v27 초기 focused**: 테스트 helper의 checked Exception 누락2건을 고쳤다(compile로그 별도 보존). FULL은 partition interval을 의도적으로 무시하므로 range 변경을 endpoint 차이라고 가정한 fixture를 실제 worker 차이로 보정했다. 이후 Native/DefaultPruning/Product156tests/skip1PASS(1.127초). Cold parity와 실제 cycle/root-binding/oversize/witness 구분은 추가 보강중이며 아직 최종 gate가 아니다.
- **전체 closure semantic 대조**: 신규 ACTIONS fixture에서 실제 GENERATED_BATCH_REUSE_HITS>0을 요구하고, 기존 support memo budget0 대조의 reuse0 및 전체 analysis fingerprint/graph/candidate facts/transient-input 일치를 확인했다(1test PASS,1764ms). 대조가 일반 support memo도 끄므로 isolated batch 성능 실험으로 해석하지 않는다.
- **직전 unpinned 엔진 실측**: `candidate-v26-unpinned-merged-detailed` 양쪽60초 timeout/no receipt, OOM0, 컨테이너 제거. LogReg31,328miss 중20,381potential, GLM76,487 중60,491potential이다. 아직 실제 재사용 없는 봉인본37ffd4ab의 계측이며 새v27 성능과 혼동하지 않는다. Inclusive graph support sample은 각각1,142/2,031 및364/2,458; candidateTopology가513/266이다. 다음v27 측정으로 실제 graph 회피 및 남은 병목을 다시 판정한다.
- **v27 보강 중 발견한 fixture 문제**: root-binding white-box 변수를 Object로 선언한 compile 오류를 typed batch로 고쳤다. 새 descendant pin 회귀의 최초174tests/1failure는 정상 pin overlay를 hidden-root metadata read로 오인하여 재계산을 요구한 잘못된 기대였다. Fixed root가 순수 dependency pin B를 현재 A/B로 바꾸므로 alpha-renaming certificate가 성립하고 재사용해야 한다. 기대를 실제 reuse·양방향 cold proof/identity footprint·positive 결과·실제 cycle 관찰로 바꾸었다. Metadata history 거절 회귀는 별도로 유지한다.
- **root-binding 방어 검증 한계**: 독립 architect가 현재 지원 recipe를 추적했으나 실제 changed-witness root binding을 생성하는 유효 fixture는 찾지 못했다. Same-witness self-premise는 이미 제외되고, FType 변경은 emission/witness 검증에 걸리며 현재 range-recompute 경로의 exactness 조건도 이를 제한한다. 따라서 이 조건의 테스트는 injected resident entry에 대한 admission 거절 및 실제 다음 query의 cold-parity fallback을 검사하는 white-box 방어 회귀로 명시한다. 실제 workload에서 해당 graph를 관측했다고 주장하지 않고 두 certificate는 그대로 유지한다.
- **v27 최종 focused/독립 gate**: 최종 Native/DefaultPruning/Product161tests/skip1PASS(1.599초). Exact/dynamic 두 graph·두 key, metricsOFF의 실제 skeleton build 불변과 null-batch positive control, 실제 양·음성 proof cycle 양방향, explicit/native-product descendant pin B의 순서·footprint·positive parity, indexed resident certificate 교체(ABA) 방어를 보강했다. 독립 architect는 production 및 최종 test scope를 CLEAR로 확인했다. 이전 잘못된 fixture 실패는 삭제하지 않고 evidence에 보존한다. 사용하지 않는 shadow helper는 제거했다. Incoming DP conditional2tests까지 포함한 fresh135class package를 진행한다.
- **첫 full gate 실패/수정**: root가 마지막에 shadow helper를 제거하면서 wrapper `batchWork`의 호출까지 치환하여 자기 재귀를 만들었다. 첫 full gate는1,158tests/failure0/error13(StackOverflow, 모두 해당 test helper)로 실패했다. Production 문제로 오인하지 않으며 `v27-recursive-test-helper-package-failure.log`를 보존한다. Helper를 직접 metrics.directWorkCount 호출로 고치고 focused 및 fresh package를 다시 실행한다. 앞선 focused/독립 검토 이후의 작은 test 정리도 재검증해야 한다는 사례다.
- **v27 최종 전체 gate**: helper 수정 후 focused161tests/skip1PASS(1.572초), 이어 fresh135class package **1,158 selected tests/failure0/error0/skip1**, BUILD SUCCESS05:33:21. Production·test scope independent CLEAR 및 전체 closure parity를 함께 충족했다. 이 정확한 source/JAR를 `candidate-v27`로 봉인하여 게시하고 동일 Docker에서 metrics OFF 우선, 상세 계측 후속 순서로 측정한다. 기존 supplemental Exact/PCA 한계와20초 미달은 여전히 남아 있다.


### v10 실제 결과와 generated batch graph 재사용 후보

- **실측**: v10 d59fa583c5/JARb5973fad의 LogReg fullInitial440.740278892초, GLM140.992530652초로 v8의398.232/124.946초보다 느렸다. 두 workload numeric PASS/audit mismatch0/동일plan. Peak9,370,550,272/5,463,453,696B. v10은 conditional DP 후속 변경을 포함하지 않는다. Gate의 synthetic 압축 효과가 이 실제 workload의 전체시간 개선으로 이어지지 않았다.
- **정상 로그 근거**: LogReg generated batch eligible graph misses147,551 중 resident/certified repeat potential92,253, root history rejects12,808, returned root binding rejects0, index saturation0. Potential은 실제 회피 횟수가 아니다. Graphs182,157/rows19,959,391이며 앞선 전개 비용이 남는다. 새 profiler/JFR 없이 coordinator normal metrics로 확인했다.
- **새 후보**: 같은 resolver snapshot, trusted fact/emission identity, root owner 및 exact NativePoolWitness 안에서만 certified resident support template을 재사용한다. 기존 traversal의 root-history independence와 returned root-owner binding 부재가 모두 필요하다. 지원하지 않는 proposed layout, 다른 scope/resolver, hidden VALUE_MAP/derived owner history, root binding 또는 eviction은 기존 계산을 유지한다. Generation/validation query namespace도 그대로다.
- **압축 유지**: 공개 proof가 다른 prospective root를 받으면 기존 instantiateSupportTemplates가 product를 펼칠 수 있다. Root binding 부재를 이미 증명했으므로 memo wrapper만 현재 source로 바꾸고 동일 immutable template product/identity footprint를 공유한다. Members나 source/action/proof authority를 representative로 대체하지 않는다. Batch index는 기존256/기존support cache 한계 안이며 별도 큰 template를 소유하지 않는다. Metrics가 꺼져도 production 재사용을 적용하며 기존 shadow observer API는 explicit reference/진단용으로 유지한다. 실제 reused graphs는 별도 counter로 기록한다.
- **Gate preflight**: native product가 전혀 없는 owner는 snapshot당 한 번만 representation을 확인하고 Boolean으로 기억한다. Source/proof 합법성 캐시가 아니며 snapshot revision 때 새 map을 사용한다. 압축 불가 후보를 매 query마다 다시 native로 분류하는 overhead를 줄인다. Explicit→native revision proof/footprint parity도 검사한다.
- **중간 검증**: focused150PASS(1.297초).8개 작은 product/empty-source 사례×4개 output authority와 seed alias를 cold explicit reference에 비교하고, 두 번째 이후 proofGraphsBuilt 증가0 및 lazy product 유지를 확인했다. Metrics 없는 경로, hidden root history(VALUE_MAP/derived), foreign resolver, eviction을 포함한다. 처음 foreign fixture는 candidate fact만 지워 literal anchor grounding이 남아 setup assertion이 실패했으며 node/origin까지 제거해 실제 missing-source 반례로 수정했다(`evidence/generated-batch-fixture-error-150`). 전체 gate는 최종 proposed-layout guard와 revision test를 포함한다.

- **Batch 첫 full gate의 작업량 범위 오류**:1,058tests 중1개에서 cold native query1handle assertion에 이어 수행한 legacy revision migration의20handle까지 합산했다. Proof/footprint parity는 통과했다. Revision에 별도 relation을 사용해 cold gate 작업과 legacy migration 작업을 분리한 뒤 focused150PASS(1.114초) 재확인했다. 실패 `evidence/generated-batch-full-revision-work-scope-red-1058`, 수정 `generated-batch-final-focused-green-150` 보존. Revision migration의 전개가 사라졌다고 주장하지 않는다.

- **Generated batch 최종 gate**: fresh main/test compile,1,058 JUnit PASS(154.829초), source SHA mismatch0. 증거 `evidence/generated-batch-full-green-1058`. v12로 별도 봉인하여 두 실제 workload를 실행한다.

### Resume: generated batch와 최신 main 통합 (진행중)

- **상태/문제**: 로컬95ed3daeb2의1,058PASS 후 원격은95424a2117까지 진행했다. Native component-summary 중복 제거, 반복 producer 입력의 native gate fallback 및 추가 revision 회귀를 포함한다. 강제 push 없이 병합한다.
- **충돌 해결**: docs의 양쪽 누적 기록을 모두 보존하고 SearchSpaceMetrics의 batch 실제 재사용 counter와 summary row counter를 모두 유지했다. Production NativePlacementContinuity와 테스트는 자동 병합됐으며 fresh 전체 회귀를 실행한다.
- **독립 검토**: 재개한 reviewer가95ed batch/rebase/representation preflight와 incoming summary dedup 상호작용을 CLEAR로 판정했다. Summary는 complete dependency footprint를 유지한 existential boundary 행만 합치며 exact owner identity·realization·witness를 구분한다. Batch certificate의 root-history independence 및 returned root binding 부재는 변하지 않는다. 두 최적화를 한 fixture에서 동시에 exercise하는 추가 테스트는 비차단 coverage gap으로 남는다.
- **실측 분리**: v12는 merge 전95ed3daeb2만 봉인했다. 4,613classes, main/test source mismatch0, JAR3ee4db4e9e4509777cbfd70fb43bf505000fc3cd87116988f879ce47f64187b3. 기존 Docker LogReg/GLM 계약을 그대로 사용한다. Incoming summary dedup 성능을 이 엔진의 효과에 포함하지 않는다.
- **잔여/위험**: 다중 seed·retained relation·DURABLE_MAP publication의 exact union과 Physical/DP materialization이 남는다. v10 normal terminal log에서 proof consumption52.13초, topology62.74초, overlay exclusive68.79초, dependency pruning42.13초였다. Inclusive 시간을 중복 합산하지 않는다.20초 목표는 여전히 미달이다.

- **병합 검증 완료**: fresh main/test compile,1,063 JUnit PASS(152.813초), source SHA mismatch0, 독립 review CLEAR. 증거 `evidence/generated-batch-summary-merge-green-1063`. 이 병합본은v13으로 분리하여 봉인한다.

### v27 / 원격 generated batch 구현 중복 병합 (진행중)

- **문제/상태**: 자체v27 `c8141e81fd`를1,158PASS 후 봉인했지만 fetch에서 원격 `9bfccd8d96`(독립 batch `95ed3daeb2` 포함)를 발견했다. 아직 이v27을push했다고 주장하지 않는다. 원격과3-way merge하되 force하지 않는다.
- **해결**: batch는 더 강한 resident certificate 재검사·no-alias·zero-budget·metricsOFF·lazy instantiation을 갖춘 자체 구현 하나로 유지한다. Incoming 고유 nativeRelationOwners presence preflight와3개 product/history/eviction 회귀, representation revision 회귀는 보존한다. Counter/API만 단일 구현에 맞춘다. 문서는 양쪽 전체 section을 보존한다.
- **layout 범위 결정**: incoming NATIVE_LINEAGE 전용 제한은 채택하지 않는다. Generated recipe는 현재 fact/emission/owner/witness만 읽고 prospective layout/anchor를 authority로 소비하지 않는다. Fixed-owner overlay와 두 certificate가 유지되면 durable/native 차이도 alpha-renaming이다. 실제 closure가DURABLE_MAP proposal도 만들기 때문에 임의로 재사용 범위를 줄이지 않는다. 독립 architect의 코드 기반 CLEAR 후 native↔durable·durableA↔B 양/음성 cold parity를 추가한다.
- **실측 분리**: `candidate-v27` JAR25396b6ace9c8b492efaa50e99dfac22659f803080b92800c9bf2be65404f453의 metricsOFF/JFR 양쪽60초timeout/no receipt, own containers 제거. 이 엔진에는 incoming presence preflight가 없다. 상세계측은 같은 봉인본을 사용하며 아직20초 미달이다.
- **병합 focused gate**: native↔durable 및 durableA↔B에 대해 positive4member/failed-deep-producer negative를 추가했다. 최초 초안의 빠진 method brace와 존재하지 않는 UNAVAILABLE 상태는 compile 전 정적 확인에서 고쳤으며, 기존 RULE_ERROR/empty profile/zero emission invariant를 사용했다. Incoming3tests와 revision 보강을 포함한165focused tests/skip1PASS(1.377초). Fresh135class package를 별도 `v27-merged`로 실행한다.
- **v27 상세 관측**: 원본 봉인본의 실제 batch reuse는 LogReg68,597회/GLM113,008회였다. 같은60초 중 마지막 관측 graph build는19,999/26,422(직전 원본31k/76k potential 계측과 별개)이며 기존 v26-unpinned graph count38,755/81,373보다 작다. 하지만 partial-progress 모집단이 같지 않고 fullInitial receipt는 여전히 없으므로 이를 wall-time 개선율로 환산하지 않는다. LogReg topology 약10.74초·overlay exclusive8.34초가 남아 있고, GLM joint/canonical 및 relocation binding 경로도 남는다. 새 incoming presence preflight 효과와 다음 fallback 진단을 별도 측정한다.
- **v27 통합 최종 gate/봉인**: 독립 reviewer가 실제 최종 diff와165focused 결과를 재검토해 CLEAR로 확인했다. 이어 fresh135class package **1,162 selected tests/failure0/error0/skip1**, BUILD SUCCESS05:41:40. `candidate-v27-merged` JAR SHA256=`e0df82f0e78810481d436a86afefe809daf2158ad98a3b7f71d97f50ac745471`로 봉인했다. 기존 supplemental Exact/PCA 제한은 이 선택 회귀 통과에 포함하지 않는다. 원격 추가 representation preflight의 성능 효과는 이 봉인본으로 별도 측정하며,20초 목표는 미달 상태다.


### Generated batch의 durable proposal 적용 범위 확장 (검증중)

- **문제/원인**: batch 재사용은 NATIVE_LINEAGE proposal에서만 활성화되어, 같은 generator rule/emission/witness의 DURABLE_MAP 요청은 여전히 그래프를 재계산한다. Generated root는 trusted recipe와 witness로 dependency skeleton을 만들고 proposed output은 opaque pinned reference로 사용한다. Output anchor를 proof 권한 대신 채택하지 않는다.
- **설계/보존 조건**: 기존 root-history independence, returned root-owner binding 부재, exact resolver/fact/emission/root identity, 전체 NativePoolWitness 및 resident cache gate는 그대로 유지한다. 검증되는 두 proposal layout만 허용하고 unknown layout은 fallback한다. Template wrapper만 현재 proposal로 rebase하여 external seed와 exact member source/proof는 현재 요청으로 복원한다.
- **검증 계획**: 기존 cold explicit reference와 native/durable/mixed proposal별 ordered proof·identity footprint·lazy product를 비교한다. Empty result 및 metrics OFF도 포함하고, durable에서도 hidden root history/worker-layout mismatch/eviction이 공유되지 않는지 확인한다. 먼저 원래 guard에서 durable/mixed graph-work red를 확보한다.
- **잔여/위험**: query root의 durable identity를 metadata legality에 사용하면서 history certificate에 기록하지 않는 새 경로가 생기면 잘못 공유할 수 있다. Hidden VALUE_MAP/derived 및 cold reference 대조로 감지한다. 실제 성능 개선은 후속 봉인 실측 전 미확인이다.

- **중간 검증**: 기존 guard에서151tests 중 durable/mixed graph 재계산 검사2개가 red였고 ordered proof/footprint 비교는 통과했다(`durable-batch-red-151`). 확장 후 native·product·revision4suites160PASS(1.439초). ROW/COL/FULL/BROADCAST의 다른 worker/partition 및 동일 layout의 다른 seed authority를 cold reference와 대조했다. Durable hidden VALUE_MAP/derived history는 재사용0, capacity1의 witness eviction도 재사용0이다. 전체 gate와 독립 검토를 이어간다.

- **v12 실제 검증의 계획 차이 발견**: LogReg402.506716391초/GLM137.753315043초, 숫자와runtime audit는PASS. 그러나GLM의initial/final planfp가둘다113d2d19…로v10의ebf0cc31…와다르다. Analysisfp5409cf1e…는동일하다. 같은실행안의initial==final만으로버전간계획동등성을PASS로간주하지않는다. Objectivebits/costfingerprint가정상receipt/log에없어동일비용도미확인이다. v11단독ConditionalSupport 엔진을분리실행하고Local heuristic/resource/tie영향을조사한다. v13실행도계속기록하되계획보존성공으로보고하지않는다.

- **Durable batch 전체 gate**: fresh compile1,068PASS(165.143초), source SHA mismatch0. 증거 `evidence/durable-batch-full-green-1068`. 이 검증은 기존v12GLM의선택fingerprint 차이를해결했다는의미가아니다.

- **Durable batch 독립 review CLEAR**: generated root의empty clause dependency 생성, prospective output의emissionFType 외 anchor 미참조, non-recipe/hidden root history flag, root recurrence 및 returned root binding certificate를 별도 reviewer가 확인했다. 기존v12의GLM plan차이는별도미해결항목으로유지한다.


### Dynamic proof 필터의 uniform product 전개 제거 (검증중)

- **문제**: computeCandidateAlternatives는 exact/dynamic witness를 구한 뒤 dynamic proof 전체를 stream으로 펼친다. 연산 자체가 partition range를 재계산하거나 모든 축의 입력이static인 경우에도 product member를 생성한다.
- **설계**: 연산이항상재계산하면전체immutable product를유지한다. 아니면 축의 source layout predicate만검사하여 모든option이static이면빈관계, 한축이모두dynamic이면전체product를반환한다. 두경우모두tuple을만들필요가없다. 나머지mixed축은기존정확한memberfilter를유지한다. Source identity/proof order/privacy판정은그대로다.
- **검증 계획/위험**:1,000member fixture의source검사횟수를축30개이하로제한하는red,32fixed-seed mixed/empty/all조건과canonical proof/source identity를explicit필터에대조한다. Joint/sparse관계에는독립product규칙을적용하지않고기존경로를유지한다. 실제시간절감은미확인이다.


### 多 seed / durable Closure publication의 bounded product 확장 (통합 검증중)

- **변경**: 단일seed/빈retained 조건 대신 prospective output key가seed들사이에서유일하고실제output key가retained와겹치지않을때native product를직접게시한다. 동일exact product는기존authority객체를재사용한다. 단일seed DURABLE_MAP도null clause witness와durable anchor를그대로보존한다. Same-key retained/다중seed충돌은기존exact union으로돌아간다.
- **메모리 검토 수정**: 최초patch는모든CandidateSupportResult를먼저보관해기존boundedmemo밖의큰결과수명을늘릴수있었다. 통합전거절하고작은requestmetadata와prospectivekey count만미리보관하도록수정했다. 각seed의support는이전처럼prove→consume순서로즉시처리한다. 최종원격lane56testsPASS. Root는중복phase계측블록을제거하고한seed당기존consumption범위를유지했다.
- **검증/한계**: single durable,disjoint retained+newseed,retained identityreuse,sourcewithdrawal,andcollidingdurable의explicit proof/source canonical parity를실제bind경로에서검사한다. 개별제품이불가능한요청과실제key가겹치는예외는최종canonicalization의exact union을이용하므로전개될수있다. Native topology의durable fallback과Physical Alternative전개는여전히남는다. 새Physicalselectedreceipt전용회귀는추가검증대상이다.


### Resume: 실제 workload 및 Closure 전체 회귀 (진행중)

- **실측**: 변경 없는 COFEE 50K×128 W1 v14(`592818109f`) LogReg 전체 최초 planning371.222976558초, Analysis280.217789505초다. v12 대비 전체31.284초/Analysis38.986초 줄었지만 optimizer는 약7.928초 늘었다. GLM은136.946062790초로v12보다0.807초 감소에 그쳤다. 각1회 관측이며 반복 성능 보장은 아니다. 숫자/raw-output/runtime audit는 모두PASS,20초 목표는 미달이다.
- **계획 해시 분리**: v11 conditional-only GLM126.740130738초는v10과같은ebf0cc31…해시다. v12/v14는113d2d19…다. 다만 canonicalPlanHash는 선택뿐 아니라 objectiveCertificate(cost surface fingerprint, objective bits, assignment 인덱스, maxFactorCells)도 포함한다. 독립 비교에서v11/v12의6,824 lowering 및278 FED dispatch 의미 필드 multiset은동일했다. 따라서 해시 차이를 실행 계획 회귀로 단정하지 않는다. Objective raw bits와 canonical selected receipt는 아직 분리 검증이 필요하다. 증거 `evidence/generated-batch-v11-v12-semantic-audit/audit.json`.
- **회귀 실패 보존**: Closure+dynamic filter의1,071 전체 테스트 중2개 실패했다(`evidence/closure-dynamic-full-red-1071`). 기존 모델 구조 및 비용 raw bits digest는 통과했으나 durable singleton의 새 relation encoding이 protected fingerprint를 바꿨고, 실제 explicit intern 수136과 logical receipt slot137이 달랐다. 합법성이나 비용을 바꾼 근거는 없지만 기대 hash/counter를 덮어쓰지 않는다.
- **수정**: 새 singleton product는 Cartesian 감소가 없어 기존 exact clause 경로로 보낸다. 기존 single-seed/empty-retained NATIVE_LINEAGE product는 유지한다. Multi-member product의 source/action/proof와 계수 의미는 그대로다. 두 원래 실패는35개 focused run에서 통과했고, 새 singleton fixture만 product를 강제 기대해 실패했다(`closure-singleton-guard-fixture-red-35`). 이 fixture를singleton exact와3옵션 durable product로 나누어 기존 binding/source canonical parity를 함께 검증한다.
- **추가 검증**: 새 DURABLE Physical test는0→6 member의 의도된 exact fallback, 모든 강제 member의 raw 비용·receipt·source/proof, Local/Exact 선택 및 shared lifetime을 비교한다. Native relation을명시적으로복사한reference이므로독립 generation oracle로 과장하지 않는다. 서로다른receipt의동일비용row가있지만unforced optimum 자체의tie를입증한것은아니다. 독립 review CLEAR.
- **잔여/위험**: exact+dynamic proof의혼합 병합, durable proof topology 및 Physical member 전개는남는다. 새 회귀가모두통과하기전에는현재미커밋후보를게시하지않는다. 기존 golden fingerprint/cost model/privacy/runtime 규칙은변경하지않는다.

- **Closure 최종 gate**: singleton exact/3옵션 durable fixture 및 새로운 Physical 회귀 포함 fresh main/test compile **1,073PASS(178.238초)**,source SHA mismatch0. 증거 `evidence/closure-dynamic-full-green-1073`. 기존 protected cost fingerprint/구조/raw bits와 실제 explicit계수의기대값을변경하지않고통과했다. 새post-timer receipt probe는독립compile및9testsPASS이며planner/runtime/timer규칙은바꾸지않는다. 추가gate확장은이봉인본에포함하지않는다.

### v27 / durable batch 원격 후속 통합 (검증 완료)

- **문제/해결**: fetch에서 원격 `592818109f7b`가 추가되어 다시3-way 병합했다. 바로 앞의 'Generated batch의 durable proposal 적용 범위 확장'은 **원격 분기의 역사 기록**이며, 현재 채택한 no-alias batch는 이미 layout에 무관한 동일 recipe/certificate를 검증한다. 이번 통합은 production diff가0이며 원격5개 durable/mixed/witness/history/eviction 테스트와 문서만 추가했다.
- **검증**: 동일 production의 앞선1,162 selected 전체 gate에 더해, fresh isolated compile 및 native3suites **170tests/skip1PASS(1.203초)**. 독립 reviewer는 conflict/retired API 및 보존 회귀를 확인하여 CLEAR. `candidate-v27-merged` 봉인 엔진은 여전히 정확한 current production이다.
- **잔여/위험**: metricsOFF Docker LogReg/GLM 모두60초timeout/no fullInitial receipt, own containers 제거. 원격v12의 GLM 버전간 plan fingerprint 차이는 별개 미해결이며 이번 merge가 해결했다고 주장하지 않는다. 다음 단계는 publication fallback을 실제 logical/consumed work로 나눠 계측하는 것; 지원 후보/합법성/privacy 변경은 없다.

### v28 / native product publication fallback 계측 (진행중)

- **문제**: v27에서 batch graph 재계산은 줄었지만 두 실제 workload는60초 diagnostic watchdog까지 종료하지 못한다. Product 압축은 한 distinct seed·비충돌 retained authority에서만 시도하며, helper가DURABLE_MAP을 거부한다. 많은 logical product가 어느 조건에서 명시적 proof로 풀리는지 현재 수치로 구분할 수 없다.
- **해결/설계**: 기존 분기에서 처음 확인한 결과를 고정 enum별 query수·logical proof수·0/1/2–3/4+ 크기 bucket으로 기록하고, 기존 fallback loop에서 실제 소비한 proof만 별도로 센다. Covered retained/new publication 성공도 분리한다. Helper의 기존 null 반환 이유와 기존 distinct/requested seed 집합 크기만 읽으며 생략된 helper를 실행하거나 product를 펼치지 않는다. MetricsOFF에서는 trace/cardinality 계측을 만들지 않는다.
- **수정 파일**: PlacementRelationClosure.java, SearchSpaceMetrics.java, 새 NativeProductPublicationMetricsTest.java. 후보/pruning/oracle/runtime/privacy 규칙 변경은 없다. Gate fallback 추가계측·single-seed durable압축·multi-seed exactunion은 아직 미구현이다.
- **검증 계획**: counter bucket/reset/immutable snapshot/live opt-in과 PRIVATE_AGGREGATE ACTIONS의 metricsOFF/ON exact fingerprint/facts/graph/transientinput parity, query수 및 실제consumed수 partition을 검사한다. 독립 검토 후 fresh 전체 gate·봉인·동일Docker로 actual blocker를 측정한다.
- **잔여/위험**: logical opportunity와 실제 savedwork를 혼동하지 않는다. MetricsON 실행의 추가counter비용은 성능 개선으로 계산하지 않는다. 20초 목표 및 원격v12 GLM planfingerprint 차이는 미해결이다.
- **계측 해석/독립 검토**: reviewer는 모든 원래7개 helper 거절·필터·순서·authority·호출 조건 불변을 확인하여 static CLEAR. `smallProducts/largeProducts` 필드는 NO_PRODUCT에서도 **결과 cardinality의2–3/4+ bucket**이며 certified product 존재를 의미하지 않는다. Query합계는 완료된 pass에서만 requested와 맞아야 한다; timed live snapshot에는 아직 결과를 분류하지 못한 진행중 query나 소비중 loop가 있고 multi-seed 집계도 seed loop가 끝난 후 기록된다. Partial count를 부정확한 일치/불일치 판정에 쓰지 않는다.
- **Focused gate**: fresh isolated main/test compile 후 NativeProductPublicationMetrics/SearchSpaceFineGrainedMetrics/SearchSpaceLiveMetrics **13testsPASS(2.379초)**. ACTIONS의 metricsOFF/ON semantic parity 및 완료된 query/consumed partition을 확인했다. 136class fresh package를 이어서 수행한다.
- **v28 full gate/봉인**: fresh136class package **1,170 selected tests/failure0/error0/skip1**, BUILD SUCCESS05:53:10. JAR SHA256=`7461475c08d9616f1ae28e4bdf4b3c0ed37f78cd2936bb9ac59d6d6ced529804`로 `candidate-v28` 봉인. Diagnostic-only 변경이며 이 통과를20초 달성이나 성능 개선으로 해석하지 않는다. 동일60초/JFR55초 Docker 상세계측을 수행한다.
- **v28 실제 관측/우선순위 결정**: 동일Docker 두 workload는 여전히60초timeout/no fullInitial receipt였다. 마지막 live snapshot의 LogReg MULTI_SEED는72,317queries·3,401,737consumed, 전체3,401,815consumed의99.9977%; GLM은76,406queries·1,465,227consumed, 전체1,465,234의99.9995%다. 단일seed DURABLE_OUTPUT은각30/3개의singleton뿐이다. LogReg compressed publication0, GLM publication2·covered4도singleton뿐. 따라서 single-seed DURABLE만 확대하는 계획은 저효율로 판단하고 **whole-product exact union** 설계를 우선한다. Axis를 합쳐Cartesian product로 만드는 방식은 불법 조합을 발명하므로 사용하지 않는다. `evidence/v28-publication-outcomes.json`은 completed workload가 아닌 partial-progress 증거다.

### v29 / GLM joint Environment의 pool-hit 전 canonical tree 생성 제거 (진행중)

- **문제/근거**: v27-merged OFF의 GLM main JFR sample2,784개 중 joint path11.03%, 첫 planner frame CanonicalAxisPool.values34개였다. 현재 생성자가 exact interning hit 여부를 확인하기 전에 두 tree를 만들고, with/observe/nextBlock가 이미 아는 unchanged axis도 다시 전체Map hash/probe한다. 전체 canonical sample을 이 문제 하나의 비용으로 귀속하지 않는다.
- **회귀 선행**: test-only CountingHashMap 및 counted definition-key Function으로 새2개 회귀를 추가했다. 보존한 수정전 production을 isolated compile한 실제RED는12tests 중2실패: cached tree 재생성에서 bounded-prefix1회 기대 대비56회 호출, unchanged axis lookup0회 기대 대비1회였다(`v29-red-test.log`). Missing API가 아닌 실제 반복작업을 재현했다.
- **변경/근거**: pool은 exact Map.equals lookup 후 miss일 때만 canonical tree를 만든다. Environment의 전달된 axis는 이미 검증/인터닝한 최종 axis이며 다시 probe하지 않는다. with/observe는 changed axis만 lazy persistent update, nextBlock은 empty read axis만 처리한다. Authoritative Definition map/provenance/canonical comparator/first tie/cap/reset은 그대로 유지한다. 새cache·publicflag·production testcounter·dependency는 없다.
- **수정 파일/검증**: PlacementJointInputAnalysis.java, PlacementJointInputOrderedEnvironmentOptimizationTest.java. 계획은 외부V29_JOINT_AXIS_PLAN.md에 먼저 기록했다. Focused/full 회귀·독립 검토·별도봉인Docker 후 실제효과를 판단한다.
- **잔여/위험**: 새로운 supplier의 작은 할당비용과 cache miss 환경의 효율은 실측이 필요하다. Per-analysis poolclear 이후 이미 만든 parent axis를 재사용해도 bytes/authority는 불변이어야 한다. Native multi-seed exact union이 여전히 primary bottleneck이며 이 작은 개선만으로20초 성공을 주장하지 않는다.
- **v29 focused gate/범위 보강**: poolclear 뒤 기존 parent의with/observe/nextBlock를 cold reconstruction과 byte/sign/order로 비교하고, stableKey가 같아도 다른 provenance의Definition은 axis를 부당하게 공유하지 않는 회귀를 추가했다. 최종 joint5suites **42testsPASS(3.230초)**. Source/test를 freeze하고136class fresh package를 시작했다.
- **v29 독립 검토/전체 gate**: reviewer는 exactMap equality·authoritative map 불변·poolclear/cap 의미 및 production의Definition::stableKey 고정 사용을 확인하여 CLEAR. 최종3개 새회귀를 포함한42focused PASS와 fresh136class package **1,173 selected tests/failure0/error0/skip1**, BUILD SUCCESS06:02:56을 확인했다. 별도봉인 후 metricsOFF 실측부터 수행한다.
- **v29 게시/실측 분리**: `52ef869ac82d61554b164f35c38f7db989a8f9be`를origin/main에push하고 remoteSHA를확인했다. 봉인JAR=`fc18aeb32945c844a5a1aa7610a9d667e08a4261139da3b76f74904f10e1e380`. MetricsOFF Docker는두workload 모두60초timeout/no completion receipt였다. Work-reduction 회귀와실제20초성공은구분하며, 후속 상세계측 뒤JFR을분석한다.
- **v29 상세 결과**: 두 detailed실행도60초/no receipt. GLM metricsOFF의 joint inclusive sample 비율은직전봉인11.03%에서8.21%로낮아졌지만, partial-progress·1회JFR 비율이므로 wall-time 개선율로주장하지않는다. LogReg commonnative경로가계속주병목이다.

### v30 / 다중 seed의 native product를 실제 출력 key 단위로 게시 (진행중)

- **설계 변경 근거**: independent architecture 검토 결과 nativeLineage는전체seed(FType,partitions)를유지하고동일layout별alias요청은이미dedup된다. 서로다른native출력key는합집합표현이필요없다. 반대로DURABLE키는입력nonpartitiondimension을outputshape로정규화하여실제충돌하므로이번작업에서는기존명시적경로를유지한다. v28의적은DURABLE수치는single-seed뒤의조건부관측이었으며multi-seed안의durable비중을뜻하지않는다.
- **변경 범위**: global single-seed조건만제거하고동일product replay체크는먼저유지한다. 실제publication key에grounded scalar/native제품이없는경우다른key의retained지원이있어도게시한다. 같은key의다른지원은원래scalar/earlycoverage fallback이다. Global conflictingauthority, executable/requiredinput/uniformexactness/reconstruction/DURABLE가드모두유지한다. MULTI_SEED first-blocker표시도제거하여후속실제거절사유를센다. 계획은V30_NATIVE_EXACT_UNION_PLAN.md.
- **초기 테스트 fixture 문제**: 첫actualbinder회귀는old/new모두lazyproduct0으로실패했다. Exact sourceNodeanchors가두dynamic source realization외에추가null-ground proof를만들어결과가하나의product가아니었다(NO_PRODUCT2queries/6proofs). 이실패는유효한최적화RED가아니다. Nodeanchor를제거하고공개된dynamic clausewitness가grounding/seed를제공하는fixture로보정한뒤RED/GREEN을다시확인한다. Production법칙을바꾸어test를맞추지않는다.
- **수정 파일/위험**: PlacementRelationClosure.java와새NativeMultiSeedPublicationTest.java. 다른native키의retainedauthority/3wave성장/동일keyfallback/동일alias·identity·canonical order를검사한다. Same-key growth가latermerge에서다시명시적으로전개될수있으며실제후속DURABLE/RETAINED_UNION비중을측정하여generalexactunion필요성을판단한다.20초목표는미달이다.
- **추가fixture 경계/유효RED 확보**: literalanchors 제거 뒤에도ROW dynamicquery의stream filter가product marker를plain list로만드는기존경로때문에NO_PRODUCT2queries/4proofs였다. 이관측도별도경계로기록하며이번Native구현을수정하지않는다. 정확한ROWsource와unknown consumer shape를사용하면exact product는유지되지만완전한durable출력shape는주장하지않아native게시가가능하다. 각binder호출전에trusted recipequery productnonnull·정확한폭2/2→2/2/2→3/2/2를검증하도록고쳤다. 이제수정전Closure는정상productqueries뒤lazy게시0개로실패하고, 수정후20focused testsPASS(3.423초)했다. `v30-exact-row-*`만유효workRED/GREEN근거다.
- **별도 old/new semantic 대조**: 같은3wave fixture의전체expanded emission normalized text를old/new Closure에서각metricsOFF/ON으로실행했다.6개결과가byte-identical이며`v30-semantic-parity.diff`는0bytes이다. Repository회귀의retainedproduct/object및oldclauseidentity검사와함께사용한다. Shape-knownDURABLE·staging-only同key·withdraw/restore 경계보강후전체gate를진행한다.
- **v30 경계 회귀 수정/검증**: 첫 known-shape fixture는같은worker의2/7column source를같은8×2노드에결합하여ROWwitness가두query각4member를선택했다(38tests/1fixturefailure). Production을바꾸지않고shape-consistent8×2의서로다른worker두seed로수정했다. Withdrawal pruning이Bproduct2→1을실제로줄이고A/Cauthority를그대로유지하는지검사한다. 수정전/후3wave metricsOFF/ON6개expandedsemantic행diff0,38focused PASS를확인했다. 마지막proofkey보강도동일focusedgate로검증한다.
- **새 원격 통합 결정**: fetch에서origin/main89c017a094의disjointnative/durableproduct게시·uniformdynamicfilter·durableaxisgate를확인했다. 자체StageA와겹치므로독립후보의heavybuild/Docker를중복수행하지않고focused검증본을localcheckpoint한뒤통합한다. Incoming의singletonlegacyencoding 및collisionfallback을유지하고groundedkey조회는기존map인덱스를사용한다. 통합fresh전체회귀후에만push/봉인/실측한다. 현재20초미달상태는변하지않는다.


### Main52ef 병합 및 durable topology 확장 (검증중)

- **병합**: incoming no-alias GeneratedSupportBatch와canonical environment axes miss-only생성을보존했다. 로컬streaming Closure와uniform proof필터를합치고publication계측이각요청/실제proof소비를정확히한번기록하도록갱신했다. 새fallback 이유SINGLETON_OUTPUT/OUTPUT_COLLISION을분리한다. 이전MULTI_SEED/DURABLE_OUTPUT enum은기록호환성용으로남지만새fastpath의거절사유로사용하지않는다.
- **검증**: source/proof/cache/계측 partition에대한독립reviewCLEAR, freshfocused201PASS(40.233초,기존ignored1). 전체회귀는다음durable topology공통코드통합후수행한다. 로컬7abfe9700a는1,073PASS엔진v15로보존하여병합후코드와실측을혼합하지않는다.
- **후속설계**: DURABLE_MAP의압축support도pinned/unpinned AND/OR축gate로소비한다. Direct grounding은기존anchor match ORclause witness match를그대로유지한다. Fixed owner pin,같은source owner,반복입력위치,mixed/derived/VALUE_MAP 경로는기존fallback이다. 독립156tests 및별도reviewCLEAR. 실제전개감소/시간은병합실측전미확인이다.

- **GLM 해시 차이 해결**: 같은post-timer probe의v11/v14 실제실행에서objectiveRawBits4655470428781502442, assignment,maxFactorCells,selectedPlanFieldsFingerprint 및6개section해시가모두동일했다. Canonical candidate receipt1,129개문자열도정확히같다. Aggregate planhash차이는costSurface representation hash만다른데서발생했다. Numeric/raw/auditPASS. 실제모든비선택cost cell동등성을열거했다는주장은하지않는다. 증거`receipt-parity-v11-v14.json` SHA6ac15002b0c5fce79f1b74d2a8803d5f9934f60f7e49135566fb73bf48d16537. 이결과로이전실행선택회귀의심은해소했다.

- **Durable topology 최종 gate**: DURABLE_MAP의 pinned/unpinned 축 gate를 통합한 fresh main/test compile 및 전체 FedPlanner 회귀 **1,091 tests PASS(155.313초)**, source SHA mismatch 0. 증거 `evidence/main52ef-durable-gates-full-green-1091`. 정확한 anchor/clause grounding, source withdrawal, 동적 proof 및 source identity를 보존하면서 2×3 fixture의 materialized handle을 6→1로 줄였다. 합법 후보 수를 줄였다는 의미는 아니다. 최신 main 병합을 포함한 v16 엔진으로 별도 봉인하며 실제 workload 효과는 아직 측정 전이다.
- **v15 실제 GLM**: 전체 초기 planning **126.728496870초**, Analysis86.086초, optimizer24.170초. v11/v14와 선택 receipt 전체·6개 section·objectiveRawBits4655470428781502442가 동일하고 numeric/audit PASS다. v14 대비 약10.218초 감소한 단일 관측이며 20초에는 미달한다. 이 v15는 main52ef 병합 및 durable topology gate 전 엔진이므로 최신 변경의 효과로 귀속하지 않는다. LogReg 실행을 계속한다.

### Incremental DP의 factor별 최적성 증명 (검증중)

- **문제/변경**: rejected neighborhood의 조건부 문제를 매번 compact/compile하던 경로에서, 정확히 같은 root와 고정 source 경계의 incumbent가 모든 알려진 +0/+INF 제약의0 cell을 만족하면 기존 선택을 그대로 반환한다. 개별 factor가 모두 최솟값0을 달성하므로 공동 최솟값도0임을 증명한다. 기존3인자 준비 경로를 reference로 유지하고 numeric/unknown factor, sparse hole, 다른 경계는 기존 solve를 따른다.
- **범위**: strict improvement 때만 incumbent를 바꾸는 기존 정책과 exact auxiliary witness를 유지한다. Resource preflight도 증명 전에 수행한다. 현재 구현은 conditioning 후 compilation을 생략하며, relation-native root에서 conditioning 자체도 생략할 수 있는 후속 최적화를 검토한다.
- **검증**: 독립 lane91tests와 reviewer CLEAR에 더해 root57tests PASS(1.755초). Root는 hard table/functional map/conditional support 각각을 고정 경계/전체 block으로 검사하여 objective raw bits와 exact incumbent receipt를 비교했다. 전체 회귀는 실행 중이다. 이 결과를 실제 ML 시간 개선으로 주장하지 않는다.

- **DP 회귀/조기 증명 보강**: conditioning 후 certificate 버전의 fresh 전체1,099tests PASS(156.798초), source mismatch0를 `evidence/incremental-factorwise-full-green-1099`에 보존했다. 이후 root relation에서 증명할 수 있으면 conditioning도 생략하도록 확장했다. 새회귀는 변경전 실제conditioned table1개를 만들어 실패했고, 수정후57focused PASS(1.580초)로0개를 확인했다. Numeric→hard 조건부 slice는 기존 후단 증명 경로를 유지한다.
- **v15 두 workload 완료**: LogReg384.003407355초/GLM126.728496870초, numeric/audit PASS. v14 대비 LogReg는12.780초 늘고GLM은10.218초 줄었다. 각1회·probe버전도달라일괄성능개선으로보고하지않는다. GLM selected receipt/rawbits는v11/v14와동일하다. LogReg aggregatehash는eccd516e…로기존ebd0db19…와달라, 새probe의v14 baseline과비교하기전선택receipt의버전간동등성을확정하지않는다. v16실측을진행한다.

### Topology 반복 할당 및 계측 handle 최적화 (통합 검증중)

- **변경**: query마다 생성하던 singleton owner/reference/handle map2개를 identity 경계 객체1개로 바꿨다. Topology LRU에 실제로 남아 있는 key만 보조 identity-owner/value-witness index에 보관하여 cache hit의 임시 key 생성을 없앤다. 기존 LRU/entry/row/owner-read 예산은 그대로이고 eviction/revision/authority를 보존한다.
- **계측**: PROOF_TOPOLOGY의 PhaseToken 생성만 primitive handle로 바꾼다. 호출 수, 중첩 wall/CPU/allocation, live snapshot 및 알 수 없는 측정값 처리는 유지한다. 수집기 owner prefix와 순번으로 cross-collector/stale handle을 거절한다. 리뷰에서 발견된 수집기당2^32호출 상한은 prefix rollover로 제거했고, rollover 중 active parent도 기존 handle로 정확히 종료한다. 계측을 끄거나 sampling하지 않는다.
- **검증**: topology 독립181tests(기존skip1), phase 독립19tests(기존skip1) 및 root 통합focused218tests PASS(4.253초,기존ignored1). Root에는 조기DP certificate도 포함된다. 전체 gate 실행 중이다. 줄어드는 것은 객체 생성과 반복 조회이며 논리적 합법 후보 수 감소가 아니다. 실제 시간과 메모리 효과는 아직 측정 전이다.
- **v16 GLM 실측**:130.794740209초로v15보다4.066초 늘어난 단일 관측이다. Numeric/audit 및v15와전체selected receipt/objective certificate/planhash가동일하다. LogReg 실행을이어간다.

- **통합 전체 gate 실패 보존**: 1,112tests/5fail(181.366초)를 `evidence/topology-phase-dp-full-red-1112`에 저장했다. 4개는 singleton boundary private API 변경 후 오래된 reflection signature였으며 테스트를 새 boundary/cache admission 경로로 갱신했다. 기존 합법성 assertion을 낮추지 않고, production에 없던 null pin fixture는 명시적 constructor 거절과 실제 owner identity 회귀로 바꿨다. 3개 관련 suite 독립12tests PASS.
- **추가로 확인한 실제 전개**: `ExactFiniteSupportInputFactorTest`의 20,000×20,000 sparse support(저장 행3개)가5초 timeout됐다. `sparseRangeSafe`가400,000,000 logical cells에 binary search를 반복하고 있었다. 저장된 numeric values만 검사하고 정확한 +0/+INF conditional relation은 논리적 cardinality를 전개하지 않도록 수정했다. 동일5초 제한의 conditional3region 회귀를 추가하고 sparse/DD 비용 및 rawbits 동등성을 검사한다. 시간 제한을 늘리거나 실패를 삭제하지 않는다.

### 입력 exactness와 무관한 native 출력의 product 유지

- **실제 원인**: v16 정상 종료 로그의 LogReg MIXED_EXACTNESS는14,404,944 consumed proofs, OUTPUT_COLLISION은2,362,130이다. GLM은각681,653/1,358,208이다. 이는 실제 소비한 proof 수이며 논리적 후보 수나 객체 생성 수와 구분한다. 추가 profiling은 실행하지 않았다.
- **수정**: `directNativeOutput`이 입력 exactness를 읽어DURABLE_MAP과NATIVE_LINEAGE를 나누는 경우는 output anchor가 있고 proof partition이exact인 경우뿐이다. 따라서 그 경우에만 mixed input exactness product를 거절한다. Output anchor가없거나proof가dynamic이면입력exactness와무관하게동일한native권한/출력이므로product를유지한다. 서로다른authority를대표하나로교체하지않고모든입력옵션과proof를보존한다.
- **검증**: 실제DirectSourceIndex에같은owner의exact+dynamic DIRECT realization을넣어압축publication2개옵션/0handles를확인한다. exact+anchor는기존fallback이다. Root는각멤버를기존explicit directNativePublication으로생성한key/clause와대조하고source owner identity도검증한다. 전체cost/DP 및실제workload gate는이어진다.
- **남은 전개**: 서로다른seed가같은출력key를공유하는OUTPUT_COLLISION과RETAINED_UNION은exact fallback이다. Exact+dynamic proof-region union은독립후보169tests까지검증했지만public memo 재사용이감소할가능성이있어이번봉인본에섞지않는다.

- **LogReg 버전간 선택·비용 검증 해결**: 같은post-timer probe로 v14를 재실행했다. v14/v15/v16의전체candidate/emission/materialization/relocation/shared receipt, assignment, maxFactorCells0 및objectiveRawBits4653340210026796583이모두동일했다. Aggregatehash ebd0db19→eccd516e 변화는costSurface4064b2a0→66b21979 표현변경에서발생했다. 숫자/audit도PASS다. 근거 `cofee-50k128-v16-validation/logreg-receipt-parity-v14-v16.json`, SHA256 a2d521100896a6e11721055c9a36198084c22faac6ea8b87b65e71bbb649eada. v14추가실행370.098255750초는동일probe의parity 검증용단일관측이다.

### v17 통합 회귀 완료 및 실측 준비

- **검증**: mixed exactness publication, sparse range 검사, topology identity/cache 및 primitive phase handle, 조기 DP certificate를 함께 fresh compile하여 **1,119 tests PASS(172.578초)**, source SHA mismatch 0을 확인했다. 증거 `evidence/mixed-sparse-topology-dp-full-green-1119`. 추가 실제 Incremental neighborhood/replay 통합 회귀 2개와 기존27개 및 관련1개를 포함한 별도 suite **30 tests PASS(1.016초)**; 증거 `evidence/incremental-certificate-integration-root`. 전체 Maven 검증으로 확대해 표현하지 않는다.
- **정확성**: mixed product의 각 member를 기존 explicit publication의 key/clause/source owner와 비교했고, DP certificate는 numeric/shared cost 등 증명 불가능한 경로를 기존 solve에 남겼다. 독립 검토에서 topology/계측/DP/sparse/mixed gate의 correctness blocker는 발견되지 않았다.
- **남은 문제/위험**: v16 실측은 LogReg381.267538321초/GLM130.794740209초로 목표 미달이다. 이번 코드의 workload 개선은 아직 미측정이다. 최신 origin/main의 retained-output-key 변경은 별도 병합·검증하며 v17 봉인본의 증거와 섞지 않는다. 기존 실패 로그와 미추적 작업은 보존한다.

- **통합 전 마지막 fixture 보강의 실패 보존**:38PASS 뒤추가한durable proofkey검사가publishedoutputanchor를proofoutputwitness로잘못사용해38tests/1failure였다(`v30-frozen-focused-test.log`). Localcheckpoint8751746e1f는이마지막test실패를포함하므로게시gate통과본으로간주하지않는다. 정확한trustedquery proof의native-proof-output witness를대조하도록test만수정한다.
- **병합 정적 검토**: origin89c의전체production을보존하고retained.stream membership만기존prior(nonempty) ORnativeProducts map조회로치환했다. IndependentCLEAR. Incoming collision회귀는source literalanchors의zero-binding proof때문에product분기를실제통과하지않을수있어, productnonnull/width/OUTPUT_COLLISION계수선행검증을보강한다. Requestprecollection으로live요청수는분류수보다wholebatch만큼앞설수있고completedpass에서만partition을검증한다.
- **회귀/검토 정정 및 통합 focused gate**: exactsource receipt가존재하면node-direct-ground대안은emptybinding을추가하지않으므로incomingcollisionfixture가반드시무효였다는초기가설은철회한다. 다만guard비공허성을명시적으로고정하는querywidth2·OUTPUT_COLLISION2/4/4 및fullunionparity보강은유지한다. Durablepositive도trustedquery별proof와2published/4logical/0scalar를검사한다. Frozen merged7suites **172testsPASS(4.218초,기존ignore1)**, independentfinalstaticCLEAR. 이어140class freshpackage를수행한다.
- **v30 통합 최종 gate**: fresh140class package **1,185 selected tests/failure0/error0/skip1**, BUILD SUCCESS06:29:49. 독립production/testCLEAR 및172focusedPASS와함께확인했다. Full build와동시실행한첫semanticprobe는target/classes재생성중classnotfound로실패했으므로그빈diff파일을무효증거로분리했다. Build종료후재실행한old/merged3wave metricsOFF/ON6행은byte-identical(`v30-merged-semantic-parity.diff`0bytes). 기존supplementalExact/PCA제한은선택gate성공에포함하지않는다. 이소스만candidate-v30으로봉인하여동일Docker60초조건으로측정한다.
- **v30 게시/실측 결과**: `365dc6b40f5f4262a75f00cd34f50f6c0f198ba7` origin/main remoteSHA확인. JAR1db65b9752e315583d9491d30f954216ffbfddca90e679a6983d7460ebe8fe5a. OFF/detailed LogReg·GLM모두60초timeout/no fullInitialreceipt, OOM없고own컨테이너제거. 마지막partialLogReg consumed3,202,349중MIXED_EXACTNESS2,740,272(85.57%),OUTPUT_COLLISION439,630,RETAINED_UNION15,120. GLM consumed1,360,745중OUTPUT_COLLISION803,391(59.04%),MIXED_EXACTNESS440,786(32.39%),RETAINED_UNION110,073. Covered3,573/210,065·published605/5,796logical은그분기의scalar전개가없었음을뜻하며전체시간개선율이아니다. 외부Maven prelaunch관측도별도보존했다.

### v31 / native 출력에 불필요한 mixed exactness 압축 거절 제거 (진행중)

- **문제/근거**: v30의주전개원인이MIXED_EXACTNESS로드러났다. Helper는모든입력선택의exactness가동일해야product를허용하지만scalar/product directNativeOutput은outputAnchor!=null ANDproof.exactPartitionRanges()일때만directInputsExact를읽는다.
- **계획/보존**: 그durable-eligible조건일때만uniformity를요구한다. 그외에는모든member의native출력key·clausewitness·layout정밀도가이미동일하다. Required/sourceexecutable·owneridentity·zeroaxis·reconstruction·singleton·retainedauthority·prospectivecollision은그대로유지한다. Candidate/pruning/legality/privacy/runtime법칙은바꾸지않는다. 계획V31_NATIVE_EXACTNESS_PLAN.md, independentdesignCLEAR.
- **검증/위험**: 실제mixedsource옵션을확인하는RED부터추가하고exact+unknownoutput, dynamic+knownoutput의전체scalarproof/source/order대조와exact+knownoutput의기존거절을검사한다. 통합회귀·독립검토·봉인Docker전에는성능효과를주장하지않는다. 같은prospectivekey충돌이후속차단사유로남을수있으며이작업에서완화하지않는다.
- **v31 유효RED/fixture수정**: 최초14tests/1failure는한axis에다른sourceowner둘을넣어productfactory가null인잘못된fixture였다. 한owner·한fact의서로다른exact/dynamicrealization으로바꾸고입력순서를canonical정렬했다. 이제nonnull·width2·mixedlayout선행검증뒤수정전helper의두admission이실제로실패한다(16tests/2failure, `v31-valid-red-test.log`). Production은위조건한곳과설명주석만수정하고focusedGREEN을확인한다.
- **낮은 우선순위 조사 보류**: LoopSeedRevision hash에서nativeproduct가전개될수있는코드경로와derivedFOUT검색의불필요member스캔을확인했으나v30OFF sample의loopseedhash는각0.11%이고derivedFOUT/nativeclausematerialize는관측되지않았다. 이것을새주병목으로가정하지않고이번mixedexactness실측을우선한다. 필요시List.hash계약은유지한privateRevision전용hash를별도로검토한다.
- **v31 최종 gate**: 유효RED16tests/2실패후freshisolated6suites **187testsPASS(3.444초,기존ignore1)**. 독립최종production/testCLEAR(회귀는helper수준이며requiredInputs=empty; 변경하지않은sourcevalidation은기존binder회귀로함께검사). 이어fresh140class package **1,188 selected tests/failure0/error0/skip1**, BUILD SUCCESS06:41:56. 실제성능은봉인후동일Docker로확인하며이통과만으로20초목표를달성했다고하지않는다.
- **v31 게시/실측**: `1948f6cff132531c4811065992f770dedf2eb325` origin/main remote확인, 봉인JAR5cf2e01084890f0c8dce1ec87969a837963c9a779ea1d8f397fe44dadac59559. OFF/detailed양쪽여전히60초/no receipt. LogRegpartial MIXED_EXACTNESS2,807,444/3,292,739consumed,GLM OUTPUT_COLLISION884,209/1,418,541. v31이허용하는경계는유효하지만남은대부분은실제로known/exact출력이어서더큰exact분할이필요할수있다. Counters모집단진행량이다르므로절대개수차이를속도개선율로사용하지않는다.

### v32 / prospective durable 충돌과 실제 native 출력 key 분리 (진행중)

- **문제/증명**: prospective DURABLE키가충돌해도실제native키는원래seedFType/전체partitions의prefix-free lineage를유지한다. 같은fact/emission의단일recomputedtemplate에서같은native키라면같은seedlayout이고동일DirectNativeSeedKey로이미dedup된다. 따라서새native게시만prospectivecollision에서제외하며durablecollision/retainedauthority는그대로유지한다. IndependentdesignCLEAR, 계획V32_NATIVE_ACTUAL_KEY_PLAN.md.
- **회귀 선행**: 저장소밖draft를v31에실행하여queryproduct두개width4·같은prospectiveanchor뒤lazy게시2기대/0실제의유효RED1건을확보했다. 최종draft는actualnativekey2개·trustedqueryproof8개·fullsourceset·owneridentity·orderedscalarparity·zerohandles·replayidentity/zeroScalar계수를추가했다. 저장소로옮겨freshRED/GREEN을진행한다.
- **추가계측/위험**: 남은MIXED_EXACTNESS를단일mixedaxis/복수mixedaxes로분리해다음exactpartition설계근거를확보한다. 이미계산한exactbool만사용하고metricsOFF에서추가분류를하지않으며admission/후보/합법성은바꾸지않는다. Native이외DURABLEcollision을제거하거나Cartesian축을임의합치는것은이번범위가아니다.20초는미달이다.
- **v32 RED/GREEN/독립 검토**: 최종nativeactualkey회귀는v31에서선행query/actualkey조건을통과한뒤lazy게시0개로실패했다(1test/1failure). 계측회귀는2axes중1mixed와2mixed를구분하며기존MIXED_EXACTNESS이름에대해18tests/2실패였다. 두변경후fresh9suites **48testsPASS(2.763초)**. 독립최종diffCLEAR: enum값은뒤에추가해기존ordinal을유지하고filtered binding의기존exact값만metricsON에서분류한다. 이제141class전체gate를수행한다.
- **v32 자체 최종 gate/후속 merge**: fresh141class package **1,191 selected tests/failure0/error0/skip1**, BUILD SUCCESS06:54:46, independentfinalCLEAR. 게시직전fetch에서origin/main12f1f712d3의sparse logical조회·certifiedDP재계산회피·topologykey재사용·primitivephasehandle변경을발견했다. 자체v32검증본을localcommit/별도봉인하고강제push없이병합한다. 통합freshgate와봉인후에만최신엔진성능을측정하며아직v32를origin에push했다고하지않는다.


- **origin/main1948f 병합 완료 검증**: staging-only key는 authority로 세지 않고, 같은 key의 grounded explicit clause 또는 native product가 있을 때만 retained authority로 처리하는 incoming 변경과 새 회귀를 보존했다. Mixed exactness 조건은 양쪽이 동일하여 주석 충돌만 정리했다. 독립 정적 review CLEAR 및 fresh 전체 **1,126 tests PASS(160.235초), source SHA mismatch0**. 증거 `evidence/main1948f-mixed-sparse-full-green-1126`. v17 실제 실행은 병합 전 봉인 엔진이며 이 테스트 결과를 v17 runtime의 소스라고 주장하지 않는다.

### 압축 public proof memo 및 다항 sparse pruning (통합 검증중)

- **문제**: singular proof product의 예상 메모리를 Long.MAX_VALUE로 처리해 기존 예산 안의 작은 압축 relation도 public memo에 들어가지 못했다. 별도로 finite support의 저장 행 기반 GAC는 이미 모든 arity를 처리하지만 호출부가 입력2개 이하로 제한되어3개 이상에서는 pruning을 건너뛰었다.
- **변경/근거**: public memo는 제품의 축·옵션 메타데이터만 saturating 추정하고 기존 논리적 proof수65,536/entry/byte 예산은 그대로 적용한다. Finite support는 불변 저장 행을 인덱스로 읽어 모든 arity에서 지원 없는 값만 제거한다. Public clone API는 유지하고 매 fixed-point round의 배열 복사를 없앴다. 합법 tuple·authority·비용은 변경하지 않는다.
- **수정 파일**: NativePlacementContinuity, ExactCategoricalSolver, ExactPhysicalReducedSolver 및 관련 회귀. Native memo 독립169PASS와root169PASS(1.335초), n-ary 독립59PASS. N-ary는3회 전파 cascade와80개 고정seed dense-reference objective rawbits/assignment 비교를 포함한다. 전체 통합 검증은 이어진다.
- **남은 문제/위험**: 큰 논리적 product는 여전히 기존 proof수 예산으로 memo에서 제외된다. Exact/dynamic region union은 이번 메모리 추정 변경에 포함하지 않는다. Cache resident memory와 실제 ML 시간은 동일Docker 실측으로 확인해야 한다.
- **v17 GLM 실측**: planningFullInitial132.662625426초로v16130.794740209초보다1.868초 늘었다. Analysis84.614초, cost10.261초, optimizer28.523초. 숫자 comparator/runtime audit 및선택receipt·objectiveRawBits4655470428781502442 동등성PASS. MIXED_EXACTNESS 소비는681,653→487,531로감소했지만 OUTPUT_COLLISION1,358,208 및RETAINED_UNION355,644가남는다. 이변경을전체시간개선으로보고하지않는다. LogReg는실행중이다.

- **다항·조건부 pruning root gate**: finite-support 60tests PASS(0.857초), conditional과조합한7suites **69tests PASS(1.084초)**. ConditionalSupport는 각region의모든축이현재활성도메인과교차할때만지원mask에기여한다. Unconstrained selector는기존wildcard의미를유지하며서로다른region축을합쳐새tuple을만들지않는다. 160fixed-seed exhaustive mask비교, frozen/partial두경로, removal epochcascade, 10억logical tuple/저장값6개회귀및dense원본objective/tie parity를검증했다. 독립검토CLEAR. 실제workload에얼마나적용되는지는다음봉인본으로측정한다.

- **Native memo + 다항/conditional 통합 전체 gate**: fresh main/test compile 및 **1,138 tests PASS(150.209초), source SHA mismatch0**. 증거 `evidence/native-memo-nary-conditional-full-green-1138`. 이코드를v18로별도봉인한다. v17최종LogReg386.943659265초/GLM132.662625426초는v16보다각5.676/1.868초늘었다. 두실행numeric/audit 및선택receipt/rawbits동등성PASS이나성능목표는여전히미달이다.

- **v32 최신 main 통합 검증**: incoming12f1의production은자동병합됐고문서양쪽기록을보존했다. Native residenttopologykey/identity boundary/primitivephasehandle과DP/sparse certificate를독립된두read-only검토로확인하여correctnessblocker없음/CLEAR. DPcertificate는동일root·fixedboundary·raw+0의recognizedhardrelation만허용하고Incremental strictimprovement의incumbenttie를보존한다. Foreign-equivalentroot, postconditioningnumeric→hardpositive, untouchedsubnormaloutsidefactor의전용회귀는비차단후속coveragegap이다.
- **통합 full gate**: fresh143class package **1,212 selected tests/failure0/error0/skip1**, BUILD SUCCESS06:59:32. IncomingprivateFixedCandidateBoundary시그니처변경과관련기존reflection테스트도함께보존/통과했다. 자체v32봉인96916c26에는incoming최적화가없으므로실제측정은별도candidate-v32-merged봉인으로수행한다. 이통과는전체저장소테스트나20초달성선언이아니다.

### origin/main dbee와 단일 mixed 축 분리 (진행중)

- **병합 검증**: incoming 실제NATIVE출력key의seed identity를활용하는prospectivecollision 예외와MIXED_EXACTNESS 단일/다중축구분을보존했다. 독립reviewCLEAR, root7suites66tests PASS(4.310초). 이추가변경은v18봉인에포함되지않는다.
- **후속 설계/근거**: exact proof와concrete output anchor의경우mixed축이정확히1개이면다른축은모두exact다. 해당축을exact/inexact 옵션으로분리하여DURABLE과NATIVE 두직사각관계를만든다. 원래필터링된합법tuple을겹침·누락없이분할하며서로다른authority를합치지않는다. Multiple mixed axes, 재구성실패, 두region모두singleton은기존scalar경로를유지한다.
- **Authority/잔여 위험**: retained coverage 및동일key충돌은region별로판정한다. 한region이충돌해도다른region은압축을유지하고충돌region만기존scalar권한병합을따른다. PUBLISHED_SPLIT은요청1개와논리적proof수를기록하며최종merge의객체생성절감을의미하지않는다. 강제멤버복원·원본scalarproof·sourceowner identity·retained replay·withdrawal회귀와실제Docker검증이필요하다.

### DP의 동일 비용 벡터 certificate 확장 (통합 검증중)

- **문제/판단**: 기존certificate는hard+0 factor만허용하여값이항상같은numeric factor가포함된경우에도조건부solve를반복한다. 일반적인factor별최솟값합은부동소수보정합의raw bits에대한증명이아니므로그설계는적용하지않았다.
- **안전한 범위**: 이미소유한dense factor의모든finite cell이동일한nonnegative raw bits이고incumbent가그finite cell을선택하는경우만인정한다. +INF hole은허용하되그값을선택하면fallback한다. 모든합법assignment의순서있는factor 비용비트벡터가같으므로동일한ExactCompensatedCostSum의결과도정확히같다. Varied cost, unknown evaluator, 음수/-0/NaN/all-INF는기존solve다.
- **구현/검증**: root/conditioned 분류를한번만계산해보관한다. 이미검사한cost bits를기존순서의보정합에넣어중복factor평가·temporary map을피한다. Resource preflight, 고정경계, incumbent auxiliary witness와strict improvement 정책은보존한다. 독립62tests PASS, rawbits/조건부slice/40fixed-seed reference를포함한다. Root통합회귀와실제workload 효과는아직미검증이다.
- **single mixed 축 추가 회귀**: 독립test에서구조적으로같지만identity가다른source owner가글로벌structural source index의layout을빌릴수있는helper입력을발견했다. 새압축경로는그경우관계전체를기존scalar fallback으로돌린다. Foreign옵션을부분삭제하거나legacy허용집합을바꾸지않는다. 일반planner전체의foreignowner문제를해결했다고주장하지않는다.

- v18 실제 COFEE 고정 조건 결과: LogReg 385.222894848초 / GLM 128.050397173초. v17의 objective raw bits, assignment, selected receipt, costSurface 및 전체 fingerprint와 동일; numeric/audit PASS. 각 1회이며 20초 목표 FAIL. Consumed proof 수는 16,848,259 / 2,210,823으로 변하지 않음. 비교 SHA `6ffe1ab1da692805748c9981dff1c73a86c46fa82592099fbdb2ebea2e11ee47`.
- Constant certificate v2 root focused gate 46개 중 1개 실패: 기존 numeric fallback 통합 fixture가 일정한 MIN_VALUE 비용이어서 새 정당한 constant certificate를 사용함. 실패 증거 `evidence/constant-certificate-root-red-46` 보존. varied-cost fallback fixture와 별도 constant 통합 검증으로 수정 중.
### v33 / 단일 mixed source 축의 정확한 두 product 분할 (진행중)

- **문제/근거**: v32-merged OFF/detailed 두 workload 모두60초 watchdog/no fullInitial receipt. 상세 마지막 partial snapshot에서LogReg single-mixed축2,444,038/3,201,921 consumed(76.3%), GLM126,922/1,382,843이다. 전체workload총량이나walltime개선율이아니다.
- **계획/보존**: exact proof와known output에서한축만mixed일때그축을exact/inexact source옵션으로나누고나머지축을그대로공유한다. 두product의합집합은원래검증된Cartesian관계와동일하며둘다proof/output exactflag를유지한다. DURABLE/NATIVE키별로기존collision/retainedcoverage/authoritygate를독립적용하고거절된part만기존scalarpublication으로소비한다. 둘다효용없으면전체원래fallback; singletonencoding도그대로다. Query계측은PARTITIONED한번,scalar소비만actualcount한다. Candidate/합법성/privacy/runtime규칙변경없음.
- **안전 보강**: legacy scalar는requiredInputs만executability를요구한다. 기존helper가non-required dead옵션을필터했다면새split은부분관계를게시하지않고전체legacy로되돌린다. Uniform기존경로는별도변경하지않는다. Full query dependencyfootprint는분할전에보존한다.
- **회귀/위험**: 동일owner의2exact+2inexact옵션·exactproduct·knownoutput선행조건뒤수정전helper의0개대신2개게시를기대한실제RED1건확보(v33-native-mixed-test-red.log). 전체ordered scalarproof/source/owner와zerohandles,part별collision/replay/singleton/metrics를검증한다. 일반multi-mixed축union과same-key확장union은이번범위가아니다. Freshgate와Docker전20초달성/성능향상을주장하지않는다.

- **중간 검증/fixture 정정**: 새생성binder는VALUE_MAP+exact native source의width4/exact product를실제로노출한다. 첫zero-handle실패는assert message에서realization list를미리toString하여모든member를전개한test부작용이었다; key-only/static message로고치자0handles를확인했다. 또singletons의scalarencoding정책은준비helper가아니라binderadmission의책임이므로helper결과에scalar를요구한RED는무효로분류하고실제binder회귀로옮긴다. 이를production버그수정으로보고하지않는다.
- **현재 gate**: production독립정적CLEAR, freshisolated기존6suites190testsPASS(3.031초,기존ignored1). 새suite는정확한orderedscalarunion·metricsOFF/ON·source와transitiveVALUE_MAPleaf dependency·PARTITIONED1query/4logical/0consumed, replayidentity 및한part2scalar/다른partlazy보존까지확인했다. Singleton/필터경계보강후전체gate를수행한다.

- **v33 최종 gate**: singleton정책을실제binder에서검증하는1+1/1+2/2+1과required/non-required dead옵션쌍을포함한새7testsPASS. Root frozen7suites **197testsPASS(2.527초,기존ignored1)** 및fresh144class package **1,219 selected tests/failure0/error0/skip1**, BUILD SUCCESS07:17:23. 독립최종production/testCLEAR. 실제mixed two-seed collision, exhaustivecoldidentityfootprint 및partialdurableclause assertSame은비차단추가coveragegap이며기존uniformcollision/identity회귀를대체했다고하지않는다. Defaultbudgetproduct의옵션부분집합은canonicalstates/transitions/cardinality를증가시키지않으며두child재구성null을모두게시전에검사한다. 전체저장소테스트나20초성공선언이아니다; 게시/봉인후동일Docker로실측한다.

- origin/main `d0144c5f14`의 동일 single-mixed-axis 구현을 병합했다. 공통 production은 incoming admission/PARTITIONED 경로로 통일하고, root에서 발견한 equal-structural/foreign owner의 권한 대여 방지 fallback과 추가 binder 회귀를 유지했다. Incoming non-required staging option 보존 guard도 유지. 두 가지 테스트 모음과 constant numeric certificate를 함께 재검증한다.

- d014 병합 focused gate에서 private singleton descriptor 생성 계약 차이를 확인했다(82개 중 최초1, early-return 실험시3 실패; 각각 증거 보존). Incoming은 singleton descriptor를 생성한 뒤 실제 admission에서 둘 다 scalar fallback하므로 해당 계약을 유지했다. 추가 테스트는 descriptor의 disjoint key/크기를 확인하고 기존 binder 테스트가 실제 fallback을 검증한다.

- Full gate 1,161개 중 constant certificate 6개 실패(158.439초)는 병렬 lane의 verify.py가 하드코딩된 공통 class 출력 디렉터리를 덮어쓴 검증 오염으로 확인했다. Overlay lane javac 명령은 공통 `engine-integration-main-50855b5/classes`에 pre-certificate 소스를 출력했고, root 기대 source SHA와 달랐다. RED 근거 `evidence/split-constant-full-red-1161`은 보존하되 올바른 candidate의 회귀 결과로 사용하지 않는다. verify.py에 --build-root를 추가하고 v19 전용 출력 `engine-v19-exclusive-gate`를 사용한다. 테스트 의미/기대값이나 arbitrary lazy numeric fallback은 변경하지 않는다.

- v19 exclusive full gate: 1,166 JUnit PASS / 183.996초. Main/test source SHA 불일치0, 실행 전후 7,776개 class/resource 파일 변화0. 근거 `evidence/mixed-overlay-constant-full-green-1166`. Single-mixed-axis publication + foreign-owner guard + constant finite numeric certificate + revision-local exact overlay/schedule memo 통합 검증이다. Overlay 캐시는 identity/positive handle/witness/template 조건별로 분리하고 revision에서 이월하지 않으며 LRU 축출/재계산 parity를 검증했다. Independent source review CLEAR. 전체 ML 성능은 다음 고정 Docker 실행으로 확인한다.
- **v33 게시/실측**: d0144c5f14e0a15d713484f729d3a0d720d13afd origin/main remoteSHA확인, 봉인JARf913a8cd40d3be803f255310f8363164860cf9ef1f516bc8b77396e8b4352cde. OFF/detailed모두60초/no receipt/owncontainers제거. PartialLogReg PARTITIONED2,061,422logical중1,930,871scalar로남고전체3,291,382consumed; GLM은OUTPUT_COLLISION895,540/1,435,528consumed다. 분할효과가큰durablepart의fallback에가려져있으며20초미달이다. JFR에서directNativePublication은LogReg12/2623samples뿐이므로거대한generalunion을즉시구현하기보다공통proofgraph의측정된비용을우선한다.

### v34 / acyclic dependency footprint의 불필요한 재생성 (진행중)

- **문제/근거**: v33OFF acyclicComponentFootprint inclusiveLogReg215/2623(8.20%), GLM53/2641(2.01%)samples. 이함수전체비중을이번두shortcut의예상개선율로해석하지않는다. 세부line분포는대부분visited-state/enqueue BFS이며directreused-boundary비중은별도계측한다.
- **해결/계획**: child자체가이번traversal의검증된reusedsummary라면기존BFS는그summary에서즉시멈춘다. 이미방어복사된immutableidentity occurrence set을그대로공유하고max(retainedStates,ownerCount)와기존cap을보존한다. 두childfootprint수집기는computeIfAbsent의null결과를기억하지못하므로동일oversizedboundary를반복walk했다; query-localmap에null을일시저장하고반환전제거하여한번만계산한다. Root/SCC/axisgate와cacheadmission/LRU/예산은변경하지않는다.
- **회귀/검증**: immutable summaryfootprint와동일setobject를기대하는실제workRED1건확보. Identity/transitive/hiddenowners, cap·불변성, 두collector의negativewalk한번과positiveorder를검사한다. 새진단은footprint요청수/실제boundary재사용수/공유owners/negative재시도회피를센다. OFFcounter추가할당없음. IndependentdesignCLEAR; fullgate와실제Docker전성능성공을주장하지않는다.
- **수정 파일/위험**: NativePlacementContinuity.java, SearchSpaceMetrics.java, 새NativeAcyclicFootprintReuseTest.java. 잘못된scope의negative캐시는invalidations를숨길수있으므로query-local완료graph수집중에만유효하며항상null제거후기존cache단계로넘긴다. Oracle/privacy/후보/정답집합변경없음.

- **v34 최종 gate**: 수정전3tests/3의도된실패후확장4tests는immutableidentity공유·structurally equal foreignowners·retained/ownercount양쪽cap·두수집기의positiveorder/null제거/freshquery재계산을검증했다. Root frozen8suites **201testsPASS(2.678초,기존ignored1)**, fresh145class package **1,223 selected tests/failure0/error0/skip1**, BUILD SUCCESS07:33:04. 독립production/finaltestCLEAR. Negative재시도counter자체의assertion만비차단coveragegap이며실제walk회피는countinggraph로검증했다. REQUESTS는dedup후actualmethodcall, REUSED_OWNERS는누적회피복사incidences이고globalunique가아니다. 동일Docker에서shortcut실제빈도와전체20초달성여부를별도로측정한다.

- **v34 게시/실측**: e24fb093bd4c486076f3ddd419b10a432161d8b7 origin/main 확인, 봉인 e3df080091d95e90dc3dff423392df4da52e1113b0b7f7e70e75ab51233da10d. OFF/detailed 모두 두 workload60초 timeout/no fullInitial receipt, own containers 제거. 마지막 partial 상세에서 LogReg footprint 요청32,786/summary 경계 재사용3,691/복사회피 owner incidences92,024; GLM32,904/7,708/52,420이다. Negative 재시도회피는 둘 다0. OFF footprint inclusive LogReg203/2548, GLM55/2830 samples로 여전히 남는다. 이는 부분 진행 계수이며 완료 시간 개선의 증거가 아니다.

### v35 / footprint BFS에서 기존 default successor schedule 재사용 (검증중)

- **문제/근거**: default topology의 immutable row list는 graph construction에서 이미 full CandidateProofState equality/첫 등장 순서로 중복 제거한 successor schedule을 계산한다. Footprint BFS는 같은 rows/dependencies를 다시 순회하고 중복 queue entry를 생성한다. v34의 측정된 footprint 비용 전체가 이 중복 때문이라고 단정하지 않는다.
- **해결/보존**: graph에 실제 DefaultAlternativeList가 남은 경우만 traversalSchedule(null)의 uniqueSuccessors를 사용한다. 방문 state dedup, reused-summary 중단, 일반/hidden owner와 retained-state cap 처리를 모두 기존 순서로 둔다. Filtered/overlay/plain list는 원래 nested traversal을 유지한다. 기존 schedule 외 새 cache/ordinal array/budget/public flag는 추가하지 않는다. 전체 state equality는 owner identity, handle, witness, template, axis-gate를 보존하므로 후보/합법성/privacy/runtime 규칙 변화가 없다.
- **회귀 선행**: production 수정전 실제 RED6tests/5의도된 실패는 prewarmed schedule 뒤 dependency reread가0이 아니라6/3/1회인 것을 재현했다. Plain list control은 통과했다. 새 테스트는 동일owner의다른handle/witness, structurally equal foreignowner, exact cap3/over-budget cap2/slack cap4, hidden owner 및 nested reused/dead summary를 검사한다. MetricsON은1schedule/6rawedges/2uniqueedges와 기존 schedule build/hit 증가0을 검사한다. Private helper fixture이며 end-to-end workload 적용률 증거로 과장하지 않는다.
- **수정 파일**: NativePlacementContinuity.java, SearchSpaceMetrics.java, NativeAcyclicFootprintScheduleTest.java.
- **계측/위험**: 새 counter는 실제 shortcut state 수와 raw/unique enqueue incidences이고 분석 전체 distinct edge 수가 아니다. MetricsOFF는 기존 null check 외 진단 객체를 만들지 않는다. 잘못된 graph wrapper에 schedule을 재사용하는 회귀는 plain-list fallback·cap·identity 테스트로 감지한다. 20초 목표는 미달이며 fresh 선택 gate/독립 검토/동일 Docker 적용률 실측 후 판단한다.
- **v35 중간 검증/실행 오류 분리**: 최초 focused 명령은 존재하지 않는 NativeContinuitySupportProductTest라는 suite명을 지정해 JUnit 초기화에서 실패했다. Production/test 실패로 집계하지 않고 실제 suite명으로 바로 재실행했다. Root valid frozen8suites **196testsPASS(3.737초,기존ignored1)**; 독립 actual production+6회귀 최종 CLEAR. Fresh146class package **1,229 selected tests/failure0/error0/skip1**, BUILD SUCCESS07:45:02. 이후fetch에서origin/main fe44101e97의native public product memo/fixed overlay 및DP conditional/sparse/constant-cost 변경을발견했다. 자체v35를별도봉인/commit하고원격과통합해다시gate한다. 아직원격게시나20초성공을주장하지않는다.

#### 원격 overlay 통합 기록 (후속 병합으로 보존)

- `b31cc2bf67` push는 remote main이 `e24fb093bd`로 전진하여 non-fast-forward 거절되었다. 강제 push 없이 immutable acyclic footprint reuse 변경을 병합한다. 이미 봉인해 실행 중인 v19 JAR/freeze는 변경하지 않으며, incoming 변경은 별도 통합 gate로 검증한다.

- origin e24 병합 후 Native continuity/footprint/overlay/revision/mixed-axis 관련 171개 JUnit PASS / 1.129초(기존 skip1). 바로 이전 root 전체 gate는 1,166 PASS이다. 실제 실행 중인 v19는 `b31cc2bf67`, JAR `b14f3dc5a6af4a63905b67e0238fab420e326d2bbd3f436ecb4941a61f32defc`, freeze `cd97d7d03dd44cafc2e046b2dea861c945dc7a93c0bd485ebbb61f9eca8617a6`로 고정되었으며 e24 후속 변경은 포함하지 않는다.

- **v19 실제 완료**: 봉인 b31cc2bf67에서 LogReg383.841413562초/GLM127.505152320초. 각1회; numeric/audit PASS, v18 exact assignment/선택 receipt/objective bits 동일. LogReg costSurface/aggregate hash는 변경, GLM은 동일. Coordinator peak 각각9,337,430,016B/5,311,815,680B. Proof 소비량은16,292,799/2,209,575이며 LogReg PARTITIONED9,041,022 중8,488,001은 scalar 소비가 남았다. 비교SHA13d5290f488267500e4d6bc7c1efd27cd8eafb01197eb7f6b9088f690043aa81, bindingSHAfad547ba4d3dc528ba1b08101a11720b8d223663308d4e01ca4d2e910e9d48ce. Goal evaluator FAIL/checkpoint 기록;20초 달성 아님.
- **v20 준비**: replay capability 설명을 immutable metadata로 지연 생성하여 중간 Closure의 전체 normalizedSignature 전개를 피한다. Legacy detail/equals/hash/toString 값은 보존하며 실제 fingerprint 접근 시 전개는 남는다. CandidateCapabilityFact가 Java record에서 immutable class로 바뀌므로 record reflection 결과는 달라진다. 저장소에서 해당 reflection/cast 의존은 발견되지 않았으며 constructor/accessor 계약과 legacy record helper parity를 검증한다. Overlay lookup/hit/build/실제 방문 row 및 split collision/retained/singleton proof 소비 counters를 추가해 원인별 적용량을 확인한다. Root 검증은 별도 engine-v20-exclusive-gate에서 수행한다.

- **지연 문자열 회귀**: replay/overlay/split/footprint 관련41개 PASS(0.615초). CandidateRuleRelation 생성자도 immutable header의 native support 전체를 eager signature로 전개함을 새10개 gate의2개 의도된 RED(6/12 handles)로 재현했다. Region/relation signature를 lazy cache로 바꾸되 MemberEmissions signature의 생성 시점 snapshot/nonnull 검사는 유지했고, 관련27개 PASS(0.467초). Legacy bytes, source/realization identity 및 map/requireExact의 zero-handle 동작 검증. 두 변경 독립 review CLEAR; 실제 v20 성능 검증 전이다.

### v35 통합 / overlay memo가 기존 topology 예산을 중복 사용하는 문제 (검증중)

- **문제/원인**: origin/main fe44101e97의 fixed-boundary overlay memo는 올바른 owner identity/positive handle/witness/template 조건과 revision-local lifetime을 갖지만, topology 캐시와 별개로 같은 maxEntries/maxRows 전액을 사용할 수 있었다. 따라서 기존 논리 보존 예산이 약2배로 늘어날 수 있다. 캐시 예산을 늘려 성능 목표를 맞추지 않는 evaluator 조건과 충돌한다.
- **회귀 선행**: incoming production을 별도 컴파일하여 새 실제 cacheTopology/candidateProofAlternatives fixture를 실행했다. 7tests중2개가 combined row cap/topology 우선 축출에서 의도대로 실패했다(v35-budget-red-test.log). 두 테스트는 각 entry/row cap과 양쪽 insertion 순서를 다루며 첫 row-case에서 RED가 발생했다; 아직 모든 parameter가 개별 RED였다고 주장하지 않는다.
- **해결/보존**: topology entries+overlay entries 및 topology rows+metadata owner reads+overlay rows를 기존 한도 하나로 계산한다. Overlay는 오래된 overlay만 축출하고 topology가 공간을 차지하면 보존하지 않는다. Topology admission은 optional overlay를 먼저 축출한 뒤 원래 topology LRU를 적용한다. Replacement 비용은 먼저 차감한다. 반환되는 정확한 immutable overlay/proof 자체는 바꾸지 않으며 저장 실패/축출 시 동일 계산을 다시 수행한다. 새로운 flag/확대한 cap은 없다.
- **수정 파일/검증**: NativePlacementContinuity.java, NativeFixedBoundaryOverlayMemoTest.java. 기존2개overlay LRU테스트는 자체topology1개도포함하도록fixture 총capacity만3entries/6rows로설정하고모든이전LRU/canonical/sourceidentity 기대값을보존했다. 각admission에서실제resident내용과계수/owner-readweight/residentkeyindex를대조한다. 독립 reviewer가 budget문제를확인하고 shared policy를CLEAR로평가했다.
- **추가 gate/잔여**: synthetic handle test에만의존하지않도록실제analysis scope의cyclic fixed-root query 반복/콜드parity 회귀를추가한다. IncomingDP conditional/sparse reduction과constant-cost certificate는별도read-only 검토에서correctnessblocker없음이나fresh153classgate가아직남아있다. Cache row단위는기존논리계수이며정확한byteceiling은아니다. Zero-budget/oversize의실제query도가능하면이회귀에서검증한다.
- **잠재 회귀 위험**: budget축소로overlayhit가줄수있으며성능영향은merged봉인엔진Docker에서검증한다. Topology우선축출정책에서residentkeyindex와hiddenmetadata읽기가누락되지않는지회귀로감지한다. 합법성/privacy/runtime후보집합은변경하지않는다.
- **통합 최종 gate**: 실제 analysis-scoped loop의정상positivehandle/반복overlayhit/orderedproof·sourceowneridentity·전체dependencyfootprint coldparity, workerwitness변경, nextRevision coldparity 및topology maxEntries0/maxRows0의실제두query 무저장parity를추가했다. Root final focused8suites **186testsPASS(2.651초,기존ignored1)**. Fresh153class package **1,284 selected tests/failure0/error0/skip1**, BUILD SUCCESS07:54:18. Native sharedbudget+통합회귀 및incomingDP 독립최종검토 CLEAR; 기존supplemental Exact/PCA 제약을전체저장소테스트통과로덮지않는다.
- **진단 의미**: 새 FIXED_BOUNDARY_OVERLAY_HITS는실제residenthit, ADMISSIONS는보존성공(모든build가아님), ROWS_VISITED는legacyoverlayloop의모든row(negativehandle/uncached/budgetbypass포함,hit/defaultshortcut제외)이다. 모두metricsON에서만추가계수하며OFF할당없다. V35 successor shortcut은incoming의검증된DefaultAlternativeList overlay에도적용되고일반plain overlay는원래walk한다. 선택/합법성/privacy/runtime조건은그대로다. 실제20초달성은별도봉인Docker에서판단한다.

- **v35 통합 게시/실측**: 9892c03ee0f4b0e9e78ffbee64fbee389cc003bf origin/main 확인. candidate-v35-merged JARb75a2b6367e8043b815d2231b8dc8776f44df8ddba2c8d289dab1233b15f62f3의 OFF/detailed 모두두workload60초timeout/no fullInitial receipt/owncontainers제거. 마지막partial footprint schedule계수는LogReg1,502,737회·raw35,323,307→unique9,801,761enqueue, GLM290,346회·9,088,100→1,966,487이다. OFF footprintinclusive는82/2534LogReg·13/2678GLM samples다. 중복enqueue감소는확인했지만완료walltime개선율로환산하지않는다.
- **overlay 관측 한계**: 상세partial에서overlayadmissions는LogReg39,461/GLM818이나hits는둘다0이었다. 따라서incomingoverlaymemo가실제재사용효과를보였다고보고하지않는다. 새DefaultAlternativeList의schedule경로는작동하며지속보존의효용은별도미확인이다. Scalarconsumed는LogReg3,247,082(PARTITIONED1,919,009), GLM1,297,421(OUTPUT_COLLISION817,137)로남는다. Scalarpublication함수자체의작은sample비중만으로이후explicitclause의index/topology/pruning비용을무시하지않으며same-axis authorityunion의bounded preflight설계를외부문서에보존했다. 아직union은미구현/WATCH다.

### v36 / old/new dependency union의 동일 immutable fact 중복 순회 (검증중)

- **문제/근거**: affectedDirectClosureOccurrences는edges/reaching/nodes에same-object회피가있지만beforeFacts/afterFacts를무조건둘다projection한다. V35 OFF GLM addDirectSupportDependencies firstplanner37/2678samples이며전체함수비중이예상절감율은아니다. 내부worklist의DirectSupportIndex는이old/new frontierunion과별도경로다.
- **해결 계획/보존**: beforeFacts는항상전체projection한다. 동일list면afterprojection을생략하고, 다른list이면같은위치에이미projection한동일fact객체만생략한다. Paired iterator로non-RandomAccess list의quadraticget을피한다. Reordered/추가/삭제/structurally equal foreignidentity는모두기존edgeunion을보존한다. Immutable fact는동일identityedge만추가하므로합법성/privacy/후보집합변화가없고새cache나진단상태도없다.
- **회귀/위험**: nonemptychanged와같은CountingFactList를기존affected진입점에넣어2N대신Nreads를기대하는work회귀를먼저작성했다. Same-slot·old/new지원합집합·reorder·list길이증감·equalforeignsourceowner를identityset으로검사한다. Same-slot작업절감은outerlistreadcounter만으로주장하지않는다. Emptychanged earlyreturn 및nonemptychanged의nullfactlist실패계약을유지한다.
- **수정 파일**: PlacementRelationClosure.java, DirectedDirectClosureDirtyConeTest.java. 실제중복빈도는아직미측정이며전체20초목표는미달이다. Identity아닌valueequality로생략하는회귀는foreignowner테스트로감지한다.
- **v36 RED/선택 focused gate**: 기존엔진45tests중1의도된실패(N2기대/실제4) 후수정된기존4suites78testsPASS(37.006초). 초기foreignidentity검사는두source가동시에dirty라기존edge로consumer가도달해잘못된structuralskip을놓칠수있음을rootreview에서발견했다. 이를foreign-only/old-only로분리하고alias누출을배제했으며LinkedList·null/empty계약을보강했다. 최종dirtycone46testsPASS, independentactualproduction/finaltestCLEAR. Source변경전에작성한외부계획과RED로그를보존한다.
- **최신 원격 통합 예정**: fullgate시작전fetch에서origin/main3fff3de32e의single-axis native product union 및70dd42a510 deferred replay/signature 변경을발견했다. 자체v36은targeted검증상태로localcommit하고원격과합친뒤fresh전체선택gate를한번수행한다. 아직자체v36전체gate/remote게시/실측성공을주장하지않으며incomingunion의전체canonical·identity·restriction·invalidation 계약을독립검토한다.

#### 원격 lazy replay 및 single-axis union 이력 (병합 보존)

- origin/main9892c03ee0 통합: successor schedule reuse 및 overlay/topology 공유 budget을 보존했다. 중복 overlay 계측은 기존 ADMISSIONS/HITS/ROWS_VISITED로 통일하고 root LOOKUPS와 PARTITIONED fallback reason을 추가한다. ADMISSIONS는 모든 계산이 아니라 캐시 보존 성공 수다. Root lazy signature 변경은70dd42a510로 먼저 보존했으며 신규 union과 함께 통합 회귀를 수행한다.

- origin9892 병합 targeted gate190개 PASS(1.333초, 기존skip1). Shared budget/schedule, replay/region 지연 signature, split retained/singleton 계측을 포함한다. 실제 v20 Docker는 최종 native union/full gate 완료 후 봉인한다.

- **v20 native union 통합**: 동일 consumer owner identity/seed/output witness/layout에서 한 축만 다른 native product를 직접 union한다. 첫 donor의 exact clause/source/proof를 기존 ordinal에서 복원하며 추가 donor는 새 option scope만 보유한다. Restriction/withdrawal 후 재추가된 option이 과거 donor 권한을 되살리지 않도록 scope를 줄인다. 두 축 변경, 다른 header/foreign owner, sparse holes, rank budget 초과는 scalar fallback. Closure retained-union 성공은 PUBLISHED, 실패만 RETAINED_UNION으로 계측한다.
- **v20 검증**: isolated engine-v20-exclusive-gate에서 fresh FedPlanner selected full1190개 PASS(154.893초), 전체7791 class/resource 실행전후변경0/source SHA불일치0. 별도 경로에 새로 컴파일한 receipt/probe9개 PASS(1.242초). 근거 evidence/native-union-lazy-full-gate-v20 및 evidence/v20-probe-gate. Scoped donor/실제binder/Physical Local·Exact cost·receipt parity 관련 agent82개 PASS, 독립최종review CLEAR. 실제 performance는 다음봉인Docker에서판단하며20초성공으로보고하지않는다.

### v36 통합 / native union의 fixed-point donor 누락 및 이력 보존 한도 (검증중)

- **문제/원인**: incoming same-seed single-axis union의 restrictBindings는 union 축을 검사한 identity-sensitive predicate를 donor-local binding에 다시 적용했다. 실제 NativeFixedPointSupport는 union 객체만 IdentityHashMap에 등록하므로 equal-but-distinct 공통 축을 가진 donor의 살아 있는 c member가 사라지고 materialize에서 `Native product union lost exact member authority`가 발생했다. 또한 growing-prefix union은 새 scope가 작아도 전체 original relation/index/후속 materialized handle을 계속 보존하여 visible 축보다 큰 이력을 무제한 누적할 수 있었다.
- **회귀 선행/실행 오류 분리**: 첫 fixture는 structural one-pass API를 호출하여6tests GREEN이었고 실제 worklist 재현 증거가 아니었다. missing→doomed→a cascade를 만들어 one-pass에는3members가 남고 실제 fixed-point queue에서2realizations가 제거되는 경로로 수정한 뒤6tests/1의도된예외 RED를 확보했다. 보존회귀의 최초17축 fixture는 같은sourceowner를반복해합법적product생성자에서거절되었으므로별도fixture오류다. 축별독립owner로교정한3tests/3의도된RED는65번째prefix·대형original잠재handle·일반restriction의불필요한donor보존을검증한다.
- **해결**: supported predicate는 union의 실제 축에만 적용한다. donor scope는 이미 필터링한 축의 exact binding authority와 교집합하여 원본 clause/source 객체를 보존한다. 일반 non-union restriction은 기존 no-donor 경로로 유지한다. union admission에서64donors와1,000,000retention units를 동시에 제한하고 초과/산술overflow는 기존 정확한 explicit merge로 보낸다. 후보/합법성/privacy/runtimefallback은 변경하지 않는다.
- **보존 단위/단조성**: original leaf relation의 전체 잠재 member/binding slots, 축/list/index배열/suffixmap/bucket metadata, donor scope metadata/records 및 union의 handle slots를 보수적으로 합산한다. shared reference 중복계산은 허용하며 byte ceiling이라고 주장하지 않는다. original alias가 나중에 scope밖handle을materialize해도accounting은늘지않는다. donor는leaf로flatten하고restriction은축옵션/attainablelengthstates/범위/cardinality/donor수를줄일뿐이므로cap재검사실패를dead support로처리하지않는다.
- **수정 파일**: NativeContinuitySupportClauses.java, NativePlacementContinuity.java, NativeSingleAxisProductUnionTest.java, 새NativeProductAuthorityRetentionTest.java. 원격lazy replay/signature 및자체old/new factidentity최적화도함께통합한다.
- **검증/위험**: 최종focused10testsPASS(0.328초). Cold canonical ordered text와첫donor clause/sourceidentity, 실제cascade, ordinarynohistory,65prefix후explicitparity,17축잠재handle무전개,14축original을singletonscope로줄여도5번째누적에서거절하는경우를검증한다. 한도는큰union을보수적으로거절하여성능을악화시킬수있으나지원집합은유지된다. Fresh156class package (158selector entries;2중복패턴)/독립검토/봉인Docker가남아있으며20초목표는미달이다.
- **통합 최종 gate**: fresh156unique selected suites(158selectorpatterns/중복2) **1,303tests/failure0/error0/skip1**, package BUILD SUCCESS08:19:55. 실제수정·cascade·4retention 회귀독립최종검토CLEAR. 이는선택gate이며전체저장소테스트통과가아니다. Nativeunion 성공은현재일반PUBLISHED/COVERED_RETAINED에포함되고별도성공counter는없어원인별성능귀속에한계가있다. 다른seed/prospectiveOUTPUT_COLLISION은그대로fallback이며모든scalar문제해결로과장하지않는다.
- **v36 통합 게시/실측**: 6c5dc4a76e1a467b707607540c07f128d9ff6056 origin/main remoteSHA확인, candidate-v36-merged JARb745529265cdf8a5702874f3661b1a4e4ffbc13d394446c2197e2021d42350b8. OFF/detailed모두LogReg·GLM60초timeout/no fullInitialreceipt, owncontainers제거. Partial scalarconsumed는LogReg3,259,913(PARTITIONED1,934,492,OUTPUT_COLLISION458,391,RETAINED_UNION11,182), GLM1,466,466(OUTPUT_COLLISION970,010,RETAINED_UNION129,299). 원격same-seedunion의성공은별도counter가없어PUBLISHED일반계수만으로성능효과를귀속하지않는다.
- **v36 다음 병목 근거**: OFF LogReg2430samples중deadPruning168inclusive/NativePoolWitness.equals71firstplanner/prepareGrounded118first; GLM2721중canonicalCompare8.78%/jointcanonical3.45%/ownsCandidateClause35first다. LogReg5.730GB/GLM3.435GBpeak지만timeout부분실행이므로완료속도·전체peak비교로과장하지않는다.

### v37 / native query 준비의 불필요한 재순회 (진행중)

- **계획/원칙**: 기존cached값·동일identity만재사용하고새cache/flag/예산/후보폐쇄는추가하지않는다. Closed proofgraph의defaultschedule이이미존재하고filteredAlternatives==원본list일때만rawDependencyCount를첫countingpass대신사용한다. Conservative missingstate발견, cold/filtered/plainlist는원래순회를유지한다. Checkedintoverflow와원본slot별역의존성/공유alternative removal semantics를유지하며liveCounts는기존ownerloop에서초기화해별도순회를없앤다.
- **회귀 선행**: v36target에서NativeDefaultPruningOrdinalTest9tests/1의도된실패(기대2rowreads/실제4)후production수정. 기존randomfrozenoracle/identity/witnesscollision/closedcycles와함께focused25PASS. Cold·plain·filtered·conservative보강후다시검증한다. 순회절감은전체deadprune6.91%sample과동일한시간절감이아니다.
- **추가 bounded 대상**: witness.equals는이미계산된immutablehash가다르면deep list비교를생략하되collision은원래전체비교한다. Owned explicitclause의기존positiveidentitymemo를nativehandle순회보다먼저검사하되negative에서는새materialized nativehandle을계속검색한다. 테스트선행후적용하며현재해당두수정은아직미적용이다.
- **수정 파일/위험**: NativePlacementContinuity.java, NativeDefaultPruningOrdinalTest.java, witness/ownership회귀. 잘못된schedule/negativecache재사용은살아있는proof를누락할수있어엄격한가드와실제lazyhandle테스트로감지한다. 독립설계CLEAR,최종production/fullgate/봉인Docker는남아있다.20초목표미달.
- **v37 최종 targeted gate**: warm/filtered/cold/plain/conservative counting controls와기존pruningoracle26testsPASS(0.647초). Witnesswork RED는cachedhash불일치시endpointread0기대/1실제를재현했다. 초기ownership fixture의native-first ordering가정은canonical정렬계약과달랐고foreignnativefact거절기대는기존nativecontained-clause빠른경로와달랐다. 두테스트가정을바로잡아기존동작을보존한뒤validownership2tests/1의도된실패(nativehandlewalk0기대/1실제)를확보했다. Production수정후7suites193testsPASS(1.324초,기존ignored1); late-native가실제로0→1handle이되는assertion추가후최종witness/ownership8PASS(0.257초). Fresh157class package진행중이다.
- **v37 변경 확정**: witnesshash불일치만즉시거절하며collision/다른arena의equalvalues/rangeexactness를보존했다. Ownership은기존identityset의positive만먼저반환하고negative는nativehandle검사를통과한뒤반환한다. 실제coldownerscan과foreignexplicitauthority거절을그대로유지한다. Source수정은NativePlacementContinuity.java의작은세영역이고외부규칙/후보/보존예산변화는없다.
- **v37 최종 선택 gate**: fresh157selectedclasses **1,310tests/failure0/error0/skip1**, package BUILD SUCCESS08:34:25. 독립actualproduction/최종회귀검토CLEAR. 반복작업회피는증명했으나실제20초도달은다음동일봉인Docker결과로판정하며현재미달이다.
- **v37 게시/실측**: 0338eb6597b3199cd9a839bd1f38d7d3efecaee5 origin/main remoteSHA확인, candidate-v37 JAR6755b8df6712f374718d206a39f427895e40937164ff3be773a921b825c62705. OFF/detailed두workload모두60초timeout/no fullInitialreceipt/owncontainers제거. OFF LogReg2507samples중deadPruning168/witness.equals88first, GLM2657중canonicalCompare7.11%/ownership23direct. Partialscalarconsumed LogReg3,124,224(PARTITIONED1,844,427), GLM1,317,646(OUTPUT_COLLISION868,887). 작은경로의work회피는검증했으나전체완료시간개선은입증되지않았다.

### v38 / durable output collision의 실제 descriptor 관계 계측 (진행중)

- **문제/의사결정**: 잔여explicitproof가큰데publication함수자체의sample은작다. 이후index/topology/pruning의증폭원인을분리하려면다른fullseed가같은actualDURABLEkey와bindingaxes에모이는지먼저관측해야한다. 복잡한전체unioneligibilityobserver대신안전한Phase1 first-exemplar pairincidence를선택했다. 실제union구현이나새pruning은아니다.
- **정의/한계**: metricsON의binderpass에서actualpreparedDURABLEnative descriptors만관측하고, exactfact/emissionscope의첫exemplar와owner/clause/outputmetadata→orderedbindingobjectidentityaxes→fullseed를비교한다. FIRST/SAME_SEED/DISTINCT_SEED/AXES_CHANGED/METADATA_CHANGED/BUDGET_UNKNOWN중하나와현재descriptorlogicalsize를계수한다. 최종publication/고유proof수/실제consumed절감/whole-emissioneligibility/속도개선의증거로해석하지않는다. Firstexemplarbias와equal-but-newbindingobjects때문에0distinctseed도기회없음을증명하지않는다.
- **보존/제약**: 관측기는perbinder64exemplaradmissions/256processedobservations/8192retainedoptionrefs/65536axiscomparisonunits로제한한다. Emissionreset은refs만지우고cumulativebudget은유지한다. Metadata/keydeep equalityCPU나byteceiling이라고주장하지않는다. Compactexemplar는owner/key/seed/witness/flags/axes만보존하고growableclausehandles/donors/nativeindex는참조하지않는다. OFF에서는observer생성/extra productsize읽기가없다. Helper반환직후split/uniformadmission전에만삽입해버려질durablepart도보며기존합법성·privacy·후보·순서·단계호출은바꾸지않는다.
- **회귀/진행**: 기존v37엔진에서실제mixedbinder7tests/4의도된counter0기대차이RED를확보했다. Admitted/singleton/asymmetric/retainedfallbackpart를관측하고기존ON/OFF orderedemission·footprint 및owneridentity를검증한다. Helpertest의최초compile은잘못된CandidateRealizationInputBindingimport로실패했고,같은축에다른sourceowner를쓴fixture도검토에서발견해정정중이다. 이둘을production실패나유효RED로계산하지않는다.
- **수정 파일/위험**: 새NativeDurableProductPairObserver.java, PlacementRelationClosure.java, SearchSpaceMetrics.java, 새observertest, NativeMixedExactnessPartitionTest.java. MetricsON에는제한된diagnostic비용이추가되며OFF제어경로가바뀌지않도록회귀/독립검토한다. 독립productionCLEAR,최종helper/focused/fullgate/실측은남아있다.20초목표미달/전체MLrule-directedcoverage미완료.
- **v38 targeted 최종**: helperfixture는추가로DURABLE_MAP에nonnullclausewitness를붙여기존생성자검증에서6회실패했다. 관측규칙이나생성자제약을완화하지않고모든durablefixture를nullwitness/exact로고쳤으며불법clausemetadata는생성자거절회귀로분리했다. 최종8helper+7mixedactualbinder+2multiseed+6singleaxisunion **23testsPASS(0.443초)**. FirstA/sameA/B/B에서B는worker/geometry가같고placementId만다른fullseed이며첫exemplar가교체되지않음을검사한다. 네budget의emissionreset뒤누적성과partialcomparisonUNKNOWN도검증했다.
- **다음 통합**: fetch에서origin/main ff34db737a/c56087b543의fromAlreadyCanonicalSupportClauses native-relation rebinding보존수정을발견했다. 자체diagnostic변경을targeted상태로localcommit한뒤병합하여fresh선택fullgate를한번수행한다. 현재v38원격게시/전체gate/실측성공을주장하지않는다.
