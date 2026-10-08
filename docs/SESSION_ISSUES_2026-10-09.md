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
