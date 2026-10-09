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


### Pinned gate 원격 게시, 순환 overlay 반례, unpinned 경로 수정

- **게시**: pinned gate ab4b54e0ba와 origin29b68fc0b4를6a7096f34b로 병합했다. 전체1,042PASS 뒤 병합범위144PASS(0.972초), source SHA mismatch0을 확인하고 origin/main remote SHA를 검증했다. 첫 병합 test 명령은2개 잘못된 FQCN으로 initialization error였고 올바른 이름으로 재실행했다(`evidence/native-axis-gate-merge-launch-error`, `native-axis-gate-merge-green-144`).
- **후속 정확성 반례**: 별도 root 검토에서, native product의 입력 source owner가 현재 query root일 때 historical pin을 현재 root pin으로 대체하는 기존 overlay 의미를 새 gate filter가 강화할 수 있음을 확인했다. Grounded recurrence explicit reference는 proof1개, native gate는0개였다(`nativeRootOverlayPreservesGroundedRecurrenceWithDifferentPublishedPin`, `evidence/native-axis-root-overlay-red-124`). 해당 fixed owner 축은 native gate를 사용하지 않고 정확한 기존 topology로 돌아가도록 수정했다. v9 c489728b 봉인본은 실제 실행 전에 excluded로 표시하고 acceptance에 사용하지 않는다.
- **Unpinned 생성 경로**: 모든 matching executable realization이 독립 native product일 때만 realization별 OR와 입력별 gate를 만든다. Derived action/mixed VALUE_MAP/중복 authority/지원 불가 관계는 전체 기존 경로로 fallback한다. 기존4×5 fixture에서20개,2×3 fixture에서6개 handle을 생성하던 red를 확보했다. 여러 native realization의 canonical ordered proof, source withdrawal, empty axes, dynamic witness를 explicit와 비교한다.
- **테스트 fixture 보정**: 첫 multi-family fixture는 explicit reference가 사용한 lazy relation을 그대로 재사용하여 기존6handles를 새 query 작업으로 잘못 셌다. Reference와 candidate relation을 분리했다. Duplicate realization은 constructor가 dedup하므로 중복 fact를 넣어 실제 ambiguous authority를 만들었다. 최초 recurrence fixture의 historical pin은 current와 같아서 별도 lineage로 고쳤다. 이 setup 실패를 production 의미 회귀로 계산하지 않는다. 최종 별도 lineage 반례의 실제 proof 차이만 정확성 회귀 근거다.
- **검토 제한**: 병렬 구현/독립 reviewer/workload 에이전트가 사용량 제한으로 종료되어 root가 남은 구현과 검증을 이어받았다. 앞선 pinned core/random test의 독립 CLEAR와 새 unpinned 수정의 root 검토를 구분한다.
- **실제 v8 완료**: LogReg398.232339626초/GLM124.946316443초, numeric PASS, audit mismatch0, 동일plan이다. Peak9,141,903,360/5,134,458,880B. 동일DML/Y/자원/profile/seed이며 v8에는 gate 변경이 없다. 각1회이며 evaluator는20초 초과로FAIL이다.

- **Unpinned/overlay 통합 gate**: fresh main/test compile,1,053 JUnit PASS(184.653초), source SHA mismatch0(`evidence/native-unpinned-overlay-full-green-1053`). 새 grounded recurrence 반례, generated unpinned child, multi-family/duplicate/mixed fallback 및 fixed-seed exact/dynamic source withdrawal이 포함된다. 이 수정본을v10으로 별도 봉인하여 실제 실행한다.
