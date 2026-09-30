# Session issues — 2026-09-30

## 1. GLM 공통 작업량과 전역 DP factor-family 병목 재개 — 진행 중

- **목표/고정 계약:** runtime을 제외한 canonical896 각 compile ≤30초. 기존 60초 watchdog, DP-local timer, logical cap, privacy/authority/합법 공간/비용·tie를 유지한다.
- **현재 증상:** 동결 R10 GLM DP-local W1 56.110871초/W3 40.404205초, global W3 factor-cell overflow. W1 공통 분석만29.888139초이므로 solver-only 개선은 충분하지 않다.
- **기존 증거:** `SESSION_ISSUES_2026-09-29.md`와 기존 evidence root를 보존한다. main의 이전 변경도 되돌리지 않는다.
- **새 evidence:** `/grid/3/cofee-lm-sweep-mchoi-20260914/planning-30s-recovery-20260930`.
- **실행 순서:** 동결 R10의 GLM W1/W3 Docker JFR → 별도 진단 복사본의 생성/재생성/닫힌 epoch 탈락 census → 큰 domain의 full-mask factor 두 개를 canonical family까지 추적 → 정확성/전체 graph cap gate를 통과한 수정만 main에 반영.
- **역할/검토 분리:** root는 Docker/통합/최종 검증, census 및 factor-family는 별도 native agent, architect는 production 변경 전 독립 gate를 담당한다. 모든 성능 실행은 root가 한 번에 하나씩 수행한다.
- **수정 전 계획:** evidence `PLAN.md`. 구체적인 production 변경은 별도 cleanup 계획과 regression behavior lock 뒤 수행한다. 현재는 main production 변경 없음.
- **의사결정 근거:** pruning 부족이라는 가설을 전제하지 않고, 확정-dead 생성·동일 결과 재생성·실제 다른 witness 생성을 구분한다. 단순 입력표 감소를 전역 separator 개선으로 간주하지 않는다.
- **잠재 회귀/감지:** 진단 계측이 성능에 영향을 줄 수 있으므로 diagnostic은 acceptance에서 제외한다. provisional CFG/source 부재는 영구 불법으로 집계하지 않는다. profile identity fallback 여부를 기록해 가짜 최소 의존성 결론을 방지한다.
- **검증/잔여:** 최신 실행 결과는 아래 누적한다. 아직 전체 30초 목표는 미달이다.

### 동결 R10 최신 GLM JFR — 완료, 성능 합격 자료 아님

- W3/LAN/DP-local 진단은 compile 39.830238초, common/search-space 23.374181초로 완료했다. W1 진단은 기존60초 제한에서 rc124이며, analysis marker 구간32.438892초까지 완료했고 model/cost 이후 seed/root 전처리 중 종료됐다. W1을 성공 compile로 집계하지 않는다.
- 두 진단 모두 cleanup resolved=true이고 원래 R10 JAR SHA가 일치한다. W3 receipt는 runtime execution0/workloadExecutionStarted=false. W1은 성공 receipt가 없으며 compile-only 명령과 seed 단계 종료를 보존한다.
- JFR은 `--stack-depth 128`로 재추출했다. common root stack으로 분류한 표본은 W3 1,158개/W1 1,714개이며 잘린 stack은 없다. CFG closure는 각각615/954, direct closure339/562, generation-envelope normalization214/209, worker-pool resolver186/175 표본이다. **inclusive 비중은 겹치므로 합산하거나 함수별 wall time으로 간주하지 않는다.**
- W3 전체 GC pause 합은0.684초로, 공통 분석23초의 대부분을 GC로 설명할 수 없다. W1 optimizer 표본은 timeout으로 잘린 구간이므로 전체 비중으로 확대하지 않는다.
- 증거: `r10-fresh-profile-summary.json`, `r10-glm-w{1,3}-profile.json`, 각 진단 root의 raw JFR/manifest/result/log. 다른 revision의 옛 프로파일을 최신 CPU 원인으로 대신하지 않았다.

### 전역 factor-family 추적과 한정된 관계 분해 — 국소 증명 통과 / 전체 graph 실패

- **새로 확인한 원인:** R10 GLM 진단의23,956-domain에 연결된62축 중 full component tuple을 요구한 두 축은 canonical input-authority hard2580/2585의 deterministic observation link(동결 factor2731/2739)다. 독립 raw-bit 분할과 기존 profile 결과가357 classes로 일치했고 identity fallback은 없었다. 이전의 fallback 가능성 불확실성을 이 두 축에 한해 해소했다.
- **한정 실험:** architect가 승인한 `M(H,B0,B1,B2)` membership + 각 component와 original observation O의 projection 관계를 검사했다. 모든 원래23,956 witness에서 O 교집합이 원래 ordinal 하나와 일치했다. 서로 다른 witness를 삭제하거나 없는 조합을 추가하지 않았다. 두 link 각각8,552,292→241,332 logical cells로 줄었다.
- **전체 구조 결과:** 나머지60개 incidence와 membership까지 반영하면 input95,088,055→99,745,444 cells, 기존4-order portfolio의 최선 최대 intermediate는3,537,220,955,900,814,000으로 오히려 악화했다. 전체 unchanged-cap certificate 실패다. 저장 압축이나 희소 row 수로 logical cap을 우회하지 않았다.
- **독립 판정:** 이 후보는 production 반영 없이 종료한다. 수치/tie 증명도 미완료이며, 국소 relation 동등성만으로 이를 대신하지 않는다. 모든 exact 표현의 불가능성을 뜻하지는 않는다.
- **수정 파일/증거:** main production 변경 없음. E2 `factor-family/GlmFactorFamilyProbe.java`, `GlmFamilyRelationCertificate.java`, `output.log`, `family-output.log`. 재현/원본 복원·작업량 검증만 수행했으며 host-native 성능 근거로 사용하지 않았다.

### 공통 작업량 census — 계측 완료, 무조건적인 pruning/visit 생략 근거는 아님

- **환경/검증:** R10에서 분리한 진단 복사본만 계측했다. JAR SHA `ccb40d397bf2f7a7e81bb997756d6c8500ee52b29dbff00352e1ae0ed8d3e3c4`, 기존 Docker compile-only/60초 조건을 유지했다. W3는 완료했고 W1은 공통 분석34.099888초 이후 rc124로 종료했다. 두 실행 모두 cleanup resolved=true, JFR SHA 검증 및 counter accounting PASS. W1은 성공 receipt가 없으므로 compile 성공으로 세지 않는다.
- **핵심 수치:** W1/W3의 direct owner visit은 각각26,787/22,421회이며 결과 불변은26,253/22,022회(98.01%/98.22%)다. physical visit은2,074/1,914회, 그중 동일 committed revision 재방문은235/195회(11.33%/10.19%)다. owner visit에는 early exit·memo hit도 포함되므로 이 비율을 비싼 proof 재계산 비율로 해석하지 않는다.
- **생성/탈락:** native proof product leaf40,421/28,655회와 relocation binding leaf273,554/137,386회에서 계측된 prefix conflict reject는0이다. 마지막 closed-support snapshot clause는99,844/49,842개다. W1은 worker 수가 적어도 지원 witness 조합이 더 많다. 이것이 모든 complete plan이 합법이라는 뜻은 아니다.
- **계측 해석 제한:** `closedSupport*`는 source-only와 action-aware snapshot의 여러 invocation을 합친 수치다. distinct 최종 불법 후보 수나 영구 삭제/앞당긴 pruning의 증명으로 사용하지 않는다. 독립 reviewer는 진단 수집을 APPROVE했으며 이 해석 제한을 별도로 지적했다.
- **의미 비교:** W3의 동결 R10/진단 복사본은 analysis fingerprint, cost fingerprint, seed objective raw bits `4669861893265871254`, selected-plan audit hash가 같고 둘 다 `RESOURCE_INITIAL`이다. 이는 해당 조건의 계측 parity이며 전체 행렬 증명이나 성능 개선 자료가 아니다.
- **안전성 판단:** unchanged intermediate만 보고 direct 전파를 끊으면 현재 support index에 없는 dead alternative/숨은 proof read-set을 놓치므로 제외했다. 같은 committed revision의 normalized fresh-base만 재사용하는 좁은 후보는 가능하지만, 전체 visit의10–11%라는 횟수만으로 이득이 충분하다고 단정하지 않는다. ownership/reconcile/retained-propagation tail은 계속 실행해야 한다.
- **behavior lock:** main의 기존 physical generation/ownership/privacy/dirty cone/source revision 5개 클래스57 tests PASS. production 변경은 아직 없다.
- **증거/남은 일:** E2 `common-census-results.json`, `census-w3-semantic-control.json`, `common-census/EXTRACTION.md`, `SAME_REVISION_MEMO_FEASIBILITY.md`, `DIRECT_IMMEDIATE_PROPAGATION_REVIEW.md`, `physical-generation-baseline-test-summary.json`. 반복 준비의 재사용 경계 및 canonical factor minima 기반 작은 부정 증명을 후속 확인한다. 전체30초 목표는 미달이다.

### 추가 우회 가설 종료 / 기존 canonical descriptor 재사용 — 검증 중

- **가설 종료:** 같은 hermetic GLM model의 feasible incumbent가 모든 canonical contribution의 독립 최소값을 달성하는지 확인했다. 2-cell ordinary factor에서 incumbent 비용1.00311279296875보다 싼 tuple 비용0을 발견하여 이 incumbent의 단순 최소값 증명 shortcut을 종료했다. Docker campaign objective와 다른 fixture이므로 실제 campaign optimum에 대한 불가능성 주장으로 확대하지 않는다. `incumbent-minima-certificate/REPORT.md`에 hard/forced feasibility·원래 ordinal·raw bits·partial coverage를 보존했다.
- **기존 준비 재사용 확인:** resolver의 구조 context/SCC는 이미 재사용한다. 추가 가능한 query-reset fact grouping은 common JFR의 W1 0.35%/W3 0.78%에 불과해 주된 개선책으로 채택하지 않았다. `common-census/RESOLVER_PREPARATION_REUSE_REVIEW.md`.
- **측정 기반 작은 수정:** common allocation JFR에서 canonical text 재구성이 크게 관측됐다. 이미 multi-clause list가 보관하는 정렬 descriptor를 realization 직렬화에서 다시 만들지 않고 사용한다(Stage A). 기존 불변 sidecar만 사용하므로 새 cache나 보관 수명 확장이 없다. singleton/trusted list에 descriptor가 없으면 원래 경로로 계산한다.
- **제외한 확장:** proof/input list까지 descriptor를 새로 보관하는 Stage B는 기존 두 회귀에서 실패했다. 기존 보관 범위를 넘었고, 서로 다른 clause가 공유하는 proof를 merge-local context에서 공유하지 못했다. 기존 assertion을 완화하지 않고 B만 제외했다. 실패 source/classes/test/log는 E2 `canonical-key-reuse/*stage-b*`에 보존했다.
- **수정 파일/검증:** `PlacementAnalysis.java`의 realization text 구성 한 곳과 `CandidateRealizationCanonicalizationTest.java`의 한 회귀만 R10 대비 변경했다. Stage A 전용 RED→GREEN, B 제외 후7개 클래스75 tests PASS, 실제 동결 JAR를 우선 classpath로 검증한55 tests PASS, diff whitespace 검사 PASS. 독립 reviewer는 Stage A만 APPROVE했다. counts는 서로 겹치므로 합산하지 않는다.
- **동결 artifact:** `candidate-r11-source`의 production/POM2,171개 중 변경은 위 production 파일 하나뿐이며 main과 일치한다. JAR SHA `5d794debd60ea104c6e9083a88cea12d3971d7ea729fbd5e03883123f60944bb`; package exit0. 실제 수정 경로의 allocation/retained-memory 확인과 같은 Docker의 signal-free 비교가 남아 있다. 아직 성능 개선 또는30초 합격을 주장하지 않는다.
- **잠재 회귀/감지:** 유지한 Stage A도 정렬 descriptor의 원래 UTF-16 byte·동등성·first-owned authority를 바꾸면 안 된다. missing sidecar/cold path/동적 native pool/기존 tie·hash 테스트와 cost fingerprint, Docker selected audit를 비교한다. global separator overflow는 이 작은 수정의 해결 범위가 아니다.

### R11 검증 결과 — 작은 할당 개선 유지 / 30초 목표 미달

- **실제 변경 경로의 작업량:** 동일 fixture의 `NormalizedTextContext.emissionRealization()`을 직접 호출해 검사했다. stream 할당량은60–76%, materialize 할당량은34–58% 감소했고 exact UTF-16/hash, 보관 list/text/piece 수는 같았다. GC 후 heap도 측정 변동 범위에서 같았다. legacy `normalizedSignature()`와 constructor는 대조군이며 그 경로의 중립 결과를 새 경로의 성능 근거로 쓰지 않는다. **host-native elapsed-time 성능 측정이 아니라 할당/메모리 검증**이다. 66개 probe overlay class와 실제 R11 JAR class도 byte-identical임을 확인했다.
- **동일 Docker signal-free 반복:** 기존→수정→수정→기존→기존→수정 순서의 GLM W3/LAN/DP-local 각3회다. 기존 compile `[40.504504, 38.451293, 40.132546]`, 수정 `[41.886143, 39.419696, 36.494581]`초. 중앙값은40.132546→39.419696초(-1.78%)다. 5% 초과 control 회귀는 없지만 **세 번으로 통계적 유의성이나 안정적인 전체 속도 개선을 주장하지 않는다.** 수정본 세 번 모두30초를 넘었다.
- **같은 조건/의미 확인:** source/JAR 외 image·input/privacy·JVM·resource·network·harness/probe identity가 같았다. W3 여섯 실행의 selected-plan audit hash는 모두 `93cda9bdd23000b507a88f35115e23c1e71c315e9c02554994b13fd39d9c30f0`; runtime-program construction 성공, runtime execution0, workloadExecutionStarted=false다.
- **W1 실패 재확인:** 새 signal-free 기존/수정 쌍 모두 고정60초에서 rc124였다. analysis marker는 각각30.147699/32.134919초다. 성공 receipt가 없으므로 total compile 시간을 추정하거나 timeout을 성공으로 세지 않는다. 공통 분석만으로30초를 소모하는 문제는 그대로다.
- **전역 실패 재확인:** R11 GLM W3/DP-global도 `EXACT_VE_FACTOR_CELL_OVERFLOW`, rc1이다. 실패 receipt의 음수 compile timer는 측정값으로 사용하지 않는다. 기존 logical cap/검사 위치를 우회하지 않았다.
- **cleanup/acceptance:** 이번9개 signal-free 실행과 앞선4개 JFR/census 진단 모두 각 result의 cleanup resolved=true를 확인했다. R11은 고유3셀만 관측했고30초 합격0셀,893셀 미검증이다. W3 최신 root의 기존 evaluator도 `passed=false`, `compile exceeds 30 seconds`로 판정했다. full896, 다른 control 반복, runtime correctness는 미완료다.
- **반영/검증:** 위 작은 Stage A만 main에 유지한다. production/POM2,171 hashes가 동결본과 같음을 재확인하고 검증된 R11 JAR 자체를 main `target/SystemDS.jar`와 `target/systemds-3.4.0-SNAPSHOT.jar`에 설치했다. 기존 R10 JAR는 E2 `main-artifact-before-r11/`에 백업했다. 설치된 JAR 우선 classpath로75 tests PASS; 기존75/55개와 겹치므로 합산하지 않는다. evaluator8 tests PASS, package/typecheck/`-Xlint:unchecked` build 및 diff whitespace 검사 PASS. 독립 reviewer는 정확성/작업량/retention 범위만 APPROVE했다.
- **남은 병목/다음 설계 게이트:** 단순 no-op 생략이나 pruning 강화를 정당화하지 못했다. 공통 fixed-point의 완전한 read-set/authority delta를 캡처해 안전한 증분 전파를 설계하고, 전역 DP는 국소 table 크기가 아닌 **전체 graph의 intermediate/separator 감소**를 먼저 증명해야 한다. 현재 두 문제에 대한 production 구조 변경안은 아직 이 gate를 통과하지 못했다. 작은 cache/할당 감소를30초 해법으로 확대하지 않는다.
- **증거:** E2 `r11-glm-w3-three-pair-summary.json`, `r11-w1-signal-free-pair.json`, `r11-recovery-final-summary.json`, `r11-w3-acceptance.json`, `r11-main-artifact-install.json`, `r11-installed-jar-tests.log`, `canonical-key-reuse/memory-probe/REPORT.md`. 모든 실패 원본과 제외한 실험을 보존했으며 새 dependency, cap/timer 변경, runtime/solver fallback, assertion 완화는 없다.

### R12 구조 경계 재설계 — 진행 중, 아직 성능 합격 아님

- **문제/원인:** `PhysicalCandidateState.commit`은 변경된 owner 하나를 알고도 proof inventory 전체를 만료시킨다. 다음 eligible query에서 전체 facts/reference/CFG/function/native 구조 색인을 다시 만든다. 이는 이미 기각한 R9 cross-CFG snapshot guard나 기존 query-local reset 재사용과 다른 **호출 내부 cross-commit 증분화 경계**다.
- **변경 계획:** immutable owner slice를 교체하는 committed revision API를 검토한다. Node/anchor/domain/value-version 변화와 candidate-only 변화를 구분하고, 이전 revision 불변성·lazy duplicate-edge 검증 시점을 유지한다. worklist 방문/재생성/privacy/rebind/reconcile은 생략하지 않는다. E2 `owner-delta-inventory/PLAN.md`; 독립 critic 검토 후 회귀 테스트를 먼저 추가한다.
- **baseline lock:** 실제 설치 R11 JAR를 우선 classpath로 `MaterializationProofInventoryTest`, `NativePlacementContinuityTest`, `PhysicalGenerationEnvelopeTest`, `NeutralPlacementFixedPointCompositionTest`를 실행했다. JUnit 96 tests PASS, 기존 ignored test 유지. E2 `owner-delta-inventory/baseline-tests.log`와 exit0.
- **DP 원인 구체화:** hermetic R10 GLM fixture의 최대 domain23,956개를 필드별로 분해했다. placement state5, rule27, emission39, realization46, input-authority vector57, support clause23,930개다. clause를 제외한 나머지 Alternative 필드의 header는109종이며, 각 header의 binding 골격(kind/position/source owner/action)은 하나였다. 즉 실제 위치 선택과 이미 upstream이 소유한 source-reference 선택을 소비자 clause에 복제한 조합을 구분해야 한다. **109개로 그냥 quotient/pruning하는 것은 허용하지 않는다.**
- **DP 해법 후보:** upstream owner의 selected realization reference를 한 번만 소유하고, 소비자 support는 그 공유 선택 위의 정확한 AND/OR membership으로 검사한다. 원래 clause/Alternative ordinal 복원과 실제 DIRECT/RELOCATION 의미는 보존한다. requiredInputSupport의 proof-dependency까지 포함한 relation, canonical cost/tie와 전체 graph unchanged-cap certificate가 필요하다. 단순 H/B 분할의 국소 이득을 재사용하지 않는다.
- **잔여/위험:** 두 구조 변경 모두 아직 production 승인/성능 검증 전이다. 잘못된 delta는 stale proof를, 독립 입력 projection은 원래 상관관계(예: AA 또는 BB만 가능한 관계)에 없던 조합을 만들 수 있다. cold-vs-incremental 회귀와 exhaustive relation/witness 검증 후 같은 frozen Docker 조건으로 판정한다. 전체30초 목표는 계속 미달이다.

#### R12 첫 구현 및 shared-source 첫 전체 certificate

- **공통 구현(검토 중):** `PlacementRelationClosure`의 committed owner revision과 `NativePlacementContinuity`의 owner-indexed/bounded node-authority revision을 추가했다. 구현 담당 targeted141 PASS(기존 ignore1), focused9 PASS, Java17 Xlint compile PASS. physical envelope의 기존 ROW/downstream 의미 assertion을 유지하면서 edge traversal 작업량 assertion만 의도적으로2→1로 바꿨고, R11에서 expected1/actual2 RED를 보존했다. 아직 독립 코드 검토/Docker를 통과하지 않았으므로 설치·성능 달성으로 집계하지 않는다.
- **동결 초안:** `candidate-r12-source`, JAR `b497c38bf0028acca8306f4f5ec7cb33a85d5598c0f8f25041bf3fd953afdc8c`, package exit0. main 대비 source/POM hash 일치, R11 대비 production 변경 두 파일. 리뷰 수정이 발생하면 이 초안은 보존하고 새 artifact를 만든다.
- **DP 전체 census:** identity-safe executable certificate도89,734 original alternatives→3,741 header+pattern, 비직사각형/상충reference0을 확인했다. largest23,956→109, 실제 source reference domains8/8/32. provenance fiber의 전체 original ordinal/clause는 보존한다.
- **첫 DP wholegraph 결과는 실패:** 원래11,519 factor incidence를 모두 raw-bit slice 기준으로 재연결했다. 9,888 physical incidences는 header만 필요하지만131개는 header+shared reference가 필요했다. 기존 observation link를 그대로 dense하게 바꾸면 최댓값이 `H109×R8×R8×R32×O357=79,693,824` input cells가 되며, 네 기존 ordering 중 최선 intermediate는2,728,173,704,970,240으로 baseline63,028,652,259,456보다43.28배 나빠졌다. **로컬 관계 동치만으로 production 반영하지 않는다.**
- **후속 범위:** 공유 source를 유지하면서131개 observation link 자체의 정확한 projection/join 분해를 검증한다. 합법 membership의 모든 tuple(무조건 row의 source wildcard 포함)에서 원래 observation을 정확히 하나 복원해야 하며, 실패 factor는 정확한 기존 표현을 유지한다. 같은 wholegraph cap gate를 다시 적용한다. 단순 order 변경/표 크기 낙관 계산은 하지 않는다.
- **잔여 위험:** 첫 certificate의 canonical aggregate/tie와 production witness 구현은 미완료다. 공통 새 revision의 key identity/collision/global traversal order와 test-only compatibility field 문제도 독립 검토 중이다. 전체30초 목표는 미달이다.
- **근거:** E2 `shared-source-certificate/{REPORT.md,SharedSourceRelationCertificate.java,run.sh,output.log,provenance.json}`, `owner-delta-inventory/*`. 모든 Docker 성능 실행은 검토 뒤 root가 순차 수행한다.

#### R12 독립 검토 수정 / DP 구성 표현의 전체 cap 통과

- **공통 수정 검증:** 같은 값이지만 identity가 다른 owner key를 candidate-only로 잘못 분류하는 위험을 먼저 RED로 고정하고 key identity 검증을 앞당겼다. cold flat facts `A1,B1,A2`의 마지막 참조를 `B1`로 바꾸는 순서 회귀도 RED로 재현했다. cold 경로는 원래 global ordinal을 유지하고 owner-delta 전환을 fail-closed로 막으며, production physical closure의 명시적 owner-indexed 경로만 COW delta를 사용한다. Native의 진단용 flat list/test-only fallback을 제거하고 실제 normalized-reference winner map 하나를 유지한다. actor143 tests PASS, 기존 ignore1/Xlint/diffcheck PASS; 마지막 독립 검토와 새 frozen artifact/Docker가 남아 있다. 이전 R12 초안은 덮어쓰지 않는다.
- **DP 중간 실패 보존:** shared-source projection만으로 input은451,561,837→2,012,489 cells로 줄었으나 최대 intermediate2,728,173,704,970,240은 그대로였다. 국소 표 압축만으로 separator 폭발이 해결되지 않았다.
- **추가 원인:** observation link의357개 label을 구별하던 실제 downstream hard truth의 response class는2개뿐이었다. 전체174축 중51축이 exact raw-bit truth quotient로 줄고, deterministic source link525,682행을 검증했다. truth-only 실제 factor+기존 compaction도 최대 intermediate16,984,068,709,680으로 여전히 실패했다. 이를 단독 해법으로 통합하지 않는다.
- **구성 해법의 첫 긍정 증거:** source-owned H/R + truth-response quotient + exact projected link를 함께 적용한 shadow 전체 graph는11,519 incidences를 모두 보존했고, input556,532/max9,690 cells, 기존4-order 중 MIN_FILL의 최대 intermediate30,046,395 cells가 됐다. 원래63,028,652,259,456 대비 약210만 배 축소다. 기존 factor/materialized cap 및 assignment arithmetic overflow 검사를 통과했다. **실제 production factor 구현 전의 구조 증명이며 30초 달성은 아니다.** 예측 elimination assignments는 여전히12,515,983,414개다.
- **잔여/위험:** 실제 factor 생성·기존 exact reduction 연결·bidirectional witness·canonical aggregate raw bits·명시적 동률 정책 증명이 남았다. `O=1-X`와 constant truth의 독립 counterexample에서 quotient는 기존 동률 선택을 바꾼다. 원래 witness 복원이 된다는 이유만으로 legacy tie parity를 주장하지 않는다. 계획7.1의 구조 변경 gate로 검증하고 DP-local control incumbent 비용 악화는 통합하지 않는다.
- **수정 범위/근거:** DP production 변경은 아직 없음. E2 `shared-source-certificate/{REPORT.md,output.log,SHA256SUMS}`, `truth-observation-quotient/{REPORT.md,output.log,INDEPENDENT_ARCHITECT_REVIEW.md}`. common은 `PlacementRelationClosure.java`, `NativePlacementContinuity.java`와 focused regressions. Oracle/runtime 규칙·후보 집합·caps·timer를 완화하지 않고 표현의 중복과 증분 준비 경계를 수정하는 결정이다.

#### R12 최종 correctness gate / 별도 기존 수치 결함

- **공통 최종 검토:** independent critic는 interleaved cold order, foreign identity, production owner-indexed 경계 수정본(Closure `1ed9fe77…`, Native `d5b176ff…`)을 APPROVE했다. 독립129 tests PASS/기존 ignore1. 새 frozen source는 `candidate-r12-reviewfixed-source`, package exit0/JAR SHA `e30044598ffac62d1cd477d7e29bd71e3e0401101fbc64e6545c4dfc1fca85d3`다. 설치 R11은 아직 교체하지 않는다.
- **확대 검증의 기존 실패:** 실제 R12 JAR로194 tests 실행 중 `ExactPhysicalModelCertificateTest.sevenWorkloadsBuildBaselineFreePhysicalDomainsAndFactors`가 `cells=72182723|limit=60000000`로 실패했다. 실제 frozen R11의 같은8-test 클래스에서도 정확히 같은 실패를 재현했다. cap/assertion을 바꾸지 않았으며 신규 owner-delta 회귀로 숨기지 않는다. 나머지 focused/space parity class는 별도 actual-JAR green 실행으로 보존한다.
- **DP activation 전 발견된 기존 수치 결함:** exact solver가 중간 합을 binary64로 반올림한 뒤 동률이면 더 작은 residual을 버려, 나중 상수를 더한 전역 objective가 더 나빠질 수 있다. `f1=[2^53,2^53], f2=[.75,0], constant=1`에서 x0를 선택하지만 x1이2만큼 더 작다. 또 양수8항 fixture에서 canonical ordered Neumaier와 solver DD 합이1ulp 다르다. root가 R11 실제 JAR로 두 case를 독립 재현했고 `shared-source-production-design/NumericGateProbe.java`와 로그를 보존했다.
- **의사결정/잔여:** shared-source 표현 자체의 양방향 relation과 raw factor 보존 검증은 계속하되, 이것만으로 conditional numeric optimum을 증명했다고 하지 않는다. 신규 encoding class와 회귀를 먼저 만들고 아직 optimizer에 연결하지 않는다. shared solver/DP-local의 기존 동률·수치 계약을 몰래 바꾸지 않으며 별도 numeric eligibility/정확한 비교 설계가 필요하다. universal finite-precision exactness 주장은 금지한다.
- **잠재 회귀/감지:** primitive/DD 비교만 바꾸면 기존 canonical raw bits·동률 trace가 깨질 수 있다. 두 RED numeric fixture, exhaustive conditioned optima, canonical contribution ordinal, 마지막 canonical raw-bit 검사를 유지한다. 구조 표현의 source/receipt/ref identity·강제조건·nonrectangular relation은 unsupported 시 원래 exact 표현을 그대로 유지한다.

### 사용자 목표 강화: 모든 컴파일·플래닝20초 — 진행 중

- **최신 요구 우선:** 사용자가896개 조건 모두 런타임 실행 제외 전체 컴파일·플래닝20초 이내가 될 때까지 반복하도록 명시했다. 이전30초는 역사 기준으로만 남기며 새 합격 기준은20초다. 기존60초 watchdog, DP-local budget 시작/수치, 후보·privacy·authority·logical cap은 유지한다.
- **평가기 수정:** 기존30초 성공이 새 기준에서 거절되는 RED2개를 먼저 확인하고 evaluator threshold/schema/messages와 synthetic positive fixtures를20초로 바꿨다.10 unit tests PASS. frozen 과거 artifact는 수정하지 않았다.
- **durable loop:** `.omx/goals/performance/fedplanner-all-planning-20s/`에 evaluator/contract/ledger를 만들고 native Codex goal을 active로 시작했다. 전체896 same-engine20초/cleanup/correctness gate 전에는 complete로 표시하지 않는다.
- **첫 R12 Docker 결과:** R11→R12 GLM W3 LAN DP-local 한 쌍은 total41.983791→43.264991초, common24.059530→23.399326초였다. 두 cleanup resolved=true이며 고정60초 compile-only다. 한 번으로 개선/회귀 유의성을 주장하지 않지만20초 합격은 명백히 아니다. 새 evaluator는 observed1/within_target0/missing895, total/full-planning20초 초과로FAIL. R12는 아직 main installed JAR로 승격하지 않았다.
- **남은 작업:** 공통 색인 COW만으로 큰 개선이 확인되지 않았다. 새 source-owned H/R 표현의 실제 factor/reduction/decode 검증과 공통의 잔여 반복계산 측정을 계속한다. 기존 수치 결함에는 bounded exact dyadic certificate 설계가 나왔지만 실제 physical 비용의 q/B/n 범위와 새 integer carrier 검증 전에는 적용하지 않는다.

### R13: loop-seed lookup의 반복 deep hash 제거 — 테스트 통과, Docker 측정 중

- **증상/원인:** 동결 R12의 새 GLM W3 JFR에서 common 1,159 main-thread 표본 중 `LoopSeedRevision.hashCode`가 128개였다. 그중 110개가 ledger 조회, 18개가 삽입 경로다. immutable proof snapshot의 일곱 리스트 내용을 조회 때마다 재귀 hash하는 비용이다. 표본은 inclusive이며 함수별 wall time이 아니다.
- **수정 전 계획/behavior lock:** E2 `loop-seed-lookup-partition/PLAN.md`. 실제 R12 JAR의 네 회귀 중 두 개가 RED(반복 hash의 nested 방문 700회, 의도적 same-size collision)임을 보존했다.
- **해결:** private record의 hash bucket만 read + 일곱 리스트 길이 + 두 flag로 선택한다. generated full-field `equals`, ledger `LinkedHashMap`, `get`/`putIfAbsent` 순서, first-owned key는 그대로다. 같은 크기의 다른 proof는 충돌할 수 있으나 같은 revision으로 취급되지 않는다. cache lifetime/권한/read-set/후보 집합은 바꾸지 않는다.
- **수정 파일:** `PlacementRelationClosure.java`, 새 `LoopSeedRevisionLookupTest.java`. frozen R12 대비 production 차이는 private hash/size helper 19줄뿐이며 동시 작업 중인 미연결 DP class는 이번 동결에서 제외했다.
- **검증:** overlay 116 tests와 실제 frozen R13 JAR 116 tests PASS(기존 ignore 유지), 별도 independent scope/test 검증 PASS, evaluator 10 tests PASS, Xlint/diff check PASS. R13 JAR `3263af13ffb2bd58faff184e70fccda4553b670edbf1e5c0b7fdf3edf3e9207c`; E2 `candidate-r13-common-source`, `loop-seed-lookup-partition/jar-tests.log`.
- **잔여/위험:** 더 많은 hash collision으로 full equals 비용이 늘 수 있으므로 signal-free 동일 Docker 비교 전에는 성능 개선으로 채택하지 않는다. 설치 JAR는 R11이며, 모든896개20초 목표는 미달이다.
- **의사결정 근거:** 같은 immutable key 조회의 계산을 줄이는 수정이며 oracle/runtime/합법성 규칙 또는 DP budget/cap 수정이 아니다.

### 공유 source DP: 실제 solve·전체 witness 검증 — 단일 fixture 통과, production 미연결

- **실제 표현:** original 89,734 Alternative를 3,741 identity-preserving header와 66 source-owner reference로 표현한다. 원래 ordinal의 provenance fiber를 모두 보관하고 exact membership으로 불법 교차조합을 거절한다. 174 observation 축 중 51개의 downstream truth class가 축소된다.
- **전체 실제 factor gate:** dense composed link는 1,782,514 input cells, 기존 compaction 후 895변수/1,385,401 cells다. exact projected link도 실제 Factor로 구현해 같은 compaction/4-order를 적용하면 892변수/556,532 pre-compaction cells가 된다. 두 표현의 선택 order는 최대 intermediate 66,939,625, 전체 assignment 약37.79억으로 거의 같았다. **uncompacted shadow와 compacted 실제 표현의 assignment 수를 비교해서 이득이라고 하지 않는다.**
- **복원 결함 발견/해결:** quotient truth class의 대표 old-O는 원래 X→O deterministic link의 정확한 O와 다를 수 있었다. 실패 로그를 보존하고 decoded original X에서 이미 검증한 원래 link를 다시 적용하도록 독립 artifact decoder를 수정했다.
- **검증 결과:** artifact의 기존 sparse solver가 실제 solve를 마친 뒤 encoded 1,912변수→original 1,846변수를 복원했다. original hard factors 3,120개가 모두 raw +0.0이며 solver/canonical objective bits가 모두 `4673189186591374216`이었다. E2 `shared-source-certificate/REPORT.md`, `run-actual-solve-120s.sh` 및 versioned 로그/해시.
- **측정 경계:** 120초/12GiB는 독립 correctness probe의 외부 test watchdog이며 production 60초를 바꾼 것이 아니다. 105초 host probe 경과 시간은 **Docker 성능 근거 또는20초 합격이 아니다**. 하나의 fixture 성공을 전체 numeric conditional optimum이나896개 완전성 증명으로 확대하지 않는다.
- **production 구현/잔여:** 새 `ExactPhysicalSharedSourceEncoding.java` 및 회귀는 아직 optimizer에 연결하지 않았다. typed observation map을 재사용하고 `(H, retained R)` fiber를 한 번 색인하여 옛 거대 link freeze와 cell당 선형 row 검색을 제거했다(원래 profile visits 167,058,995→23,727,770). conditional numeric optimum certificate/carrier, 독립 review와 Docker가 남았다. 기존 double-double RED counterexample는 별도 유지한다.
- **잠재 회귀/감지:** source identity, wildcard membership, AA/BB 비직사각 relation, raw residual truth, original auxiliary 복원, canonical cost와 explicit structural tie를 exhaustive fixture로 검증한다. unsupported 모델은 원래 전체 exact 표현을 유지하며 후보 삭제·근사 fallback은 하지 않는다.

### R13 반복 측정 종료 — 전체 컴파일 회귀로 거절

- **상태/증상:** 같은 Docker GLM W3 LAN DP-local을 R12/R13 각각 세 번 새 JVM으로 실행했다. total 중앙값은 35.734826→37.750761초(+5.6414%), common은 20.764392326→19.838262251초, selection은 11.908533664→14.286605303초였다. 공통 단계만 빨라졌다는 이유로 채택하지 않는다.
- **해결/수정 파일:** 작성한 `LoopSeedRevision.hashCode` 19줄과 새 lookup 회귀 파일만 되돌렸다. `PlacementRelationClosure.java`는 검토된 R12 SHA `1ed9fe77…`와 다시 일치한다. 회귀 테스트 원본과 R13 frozen source/JAR는 evidence에 보존했다. 다른 기존 변경은 되돌리지 않았다.
- **검증/근거:** E2 `r13-w3-first-summary.json`, `loop-seed-lookup-partition/REJECTED.md`, baseline/candidate 각 3개 append-only root. 고정 60초, compile-only, signal-free다. 반복 결과는 20초에도 미달한다.
- **잔여/위험:** 세 번의 분산으로 회귀 원인을 단정하지 않지만, 전체 컴파일 +5% control gate를 넘긴 변경은 승격하지 않는다. 설치 JAR는 R11 유지.

### R14 생성 후보 query-key의 불필요한 published topology 제거 — correctness 통과, 성능 검증 중

- **문제/원인:** generated-root proof에 사용할 key를 만들 때 published-root topology를 먼저 펼쳤지만 실제 generated proof는 그 표를 사용하지 않았다. R12 JFR의 query-key 84표본 중 83개가 proof 요청, 1개만 revision cache 재사용이었다.
- **해결/수정 파일:** `NativePlacementContinuity.java`의 generated key를 full source handle로 즉시 만들도록 수정했다. 기존 coarse absent-row 그룹을 더 세밀하게 나누며 서로 다른 query를 합치지 않는다. validation topology, root/witness identity, descendant traversal, cycle 처리와 revision invalidation은 유지한다. 실제 generated fact/emission 2행을 기존 작업량 계측에 반영해 숨겨졌던 누락도 수정했다.
- **회귀 검증:** 수정 전 새 두 테스트 RED. 기존 aggregate complexity assertion을 약화하지 않고 실제 행 계측을 고쳤다. 최종 targeted 108 PASS, 독립 Native 74 PASS, 기존 PUBLIC ignore 유지. frozen actual JAR로 같은 108 PASS.
- **동결/근거:** E2 `candidate-r14-common-source`, JAR `09f260fc7d09965cc8575c0e531bbf6aaa4c9fc7ab95b7114488f4cec0f399ad`. R12 대비 production 차이는 Native 한 파일, test 차이는 Native test 한 파일. `generated-query-topology/{PLAN.md,frozen-manifest.json,jar-tests.log}`.
- **잔여/잠재 위험:** 캐시 warming/sharing 변화는 Docker 비교 전 판단하지 않는다. 전체 896개 20초 달성 또는 설치 승격으로 집계하지 않는다.
- **의사결정 근거:** proof 권한이나 후보를 줄이지 않고, 사용하지 않는 증명 표를 key 계산에서 제거한다.

### DP exact dyadic kernel 및 공유 표현 cap 보강 — production 미연결

- **문제:** 기존 DD 합/반올림 비교는 모든 binary64 비용에서 canonical 최적성을 보장하지 않는다. 표현 변경과 별개로 정확한 비교가 가능한 범위를 증명해야 한다.
- **해결/수정 파일:** 새 `ExactDyadicCosts`의 두 53비트 정수 digit과 `ExactCategoricalSolver.solveDyadic`의 별도 opt-in seam을 추가했다. 원래 public solver/DP-local 호출은 바꾸지 않았다. 실제 frozen 입력의 q/합계 bit bound를 다시 검사하며, 정수 합·비교 후 마지막에 한 번 반올림한다.
- **검증:** 새 carrier 8 + kernel 8 + 기존 arithmetic/sparse/value-class/freeze 44 = 60 PASS. 독립 16 PASS 및 source review. 별도 exact BigInteger quotient/remainder 오라클과 최종 반올림 100,000건 PASS(서브노멀, 경계, ties-even, overflow 포함). GLM numeric census는 q=-74, B=91, n=4591, B+ceil(log2 n)=104≤106이다. 이는 단일 fixture의 eligibility이지 전 범위 exactness 선언이 아니다.
- **표현 cap 보강:** 독립 검토에서 dense 원본 복사와 cumulative allocation/Long.MAX overflow 문제가 드러났다. 원본 dense table은 복사하지 않고 읽으며 lazy 평가 전에 cap 검사, retained truth/current/output은 overflow 없는 subtraction budget으로 검사하도록 고쳤다. encoder 17 + carrier 8 Maven PASS. actual transformed/raw canonical objective parity와 illegal tuple +infinity 회귀를 추가했다.
- **잔여/위험:** canonical contribution과 encoded monetary factor의 대응을 caller boolean이나 descriptor string에 맡기지 않도록 `PhysicalCostSurface` 소유 typed transport를 구현 중이다. actual GLM에서 두 solver를 동일한 frozen surface로 비교하는 correctness probe도 진행한다. 이전 실행의 objective 숫자는 동일 surface가 보장되지 않으므로 품질 비교 근거로 사용하지 않는다. optimizer 활성화, Docker 성능 합격, 전체 20초 달성은 아직 아니다.

#### R14 첫 Docker / R15 전체 해시 snapshot 공유

- **R14 관측:** fresh Docker GLM W3 LAN DP-local R12→R14는 total 35.917693→34.607032초, common 20.163466669→20.341187168초였다. 같은 plan audit hash `93cda9bd…`, cleanup resolved=true, runtime 실행0을 확인했다. 한 쌍의 total 감소로 common 개선이나 통계적 합격을 주장하지 않는다. 20초 목표 미달.
- **R15 원인/해결:** 한 `loopSeedEligibleReadsMeasured` 호출의 여러 read key가 같은 일곱 proof 목록 전체를 반복 hash했다. R13의 크기-only hash를 다시 쓰지 않고, 첫 eligible read에서 immutable snapshot wrapper를 한 번 만들고 각 목록의 **원래 전체 List.hashCode**를 lazy cache한다. 기존 10개 record component, 생성된 record hash 값, full equality, ledger order와 proof read-set은 그대로다. eligible read가 없으면 domain/action 복사도 추가하지 않는다.
- **R15 수정/검증:** `PlacementRelationClosure.java`, 새 `LoopSeedProofSnapshotTest.java`. 계획 선기록, RED6 중 최초5개 미구현 실패 보존, overlay 및 actual frozen JAR 120 PASS(기존 ignore 유지), 독립 reviewer APPROVE/6 PASS. Aa/BB 실제 충돌, 각 field 변경, 양방향 List equality, 원래 record hash, zero hash, defensive copy, zero-eligible 경로를 검증했다. 실제 호출의 wrapper 공유는 코드 검토로 확인했으며 직접 호출 identity 회귀 추가는 낮은 우선순위 잔여다.
- **동결/위험:** E2 `candidate-r15-common-source`, JAR `eaf239bf7ed32b32ea69619375635dd446d3c889fbcc31dc068a3177741dc321`. R14 대비 production 변경은 Closure 한 파일이다. cross-invocation deep equals는 남으며 Docker 측정/성능 채택은 아직이다.

#### DP 실제 동일-surface 비교와 확대 검증

- **실제 GLM correctness:** 동일 frozen surface에서 legacy와 dyadic solver를 연속 실행해 canonical/solver bits가 모두 `4673189186591479073`임을 확인했다. original hard factors 3,120개 모두 만족했다. E2 `dyadic-solver/glm-same-surface-output.log`; host 시간은 성능 근거가 아니다.
- **cap 추가 수정:** review2의 lazy truth 이중 계상과 cap 검사 전 wildcard Cartesian 임시 목록을 수정했다. retained profile을 재과금하지 않고, wildcard tuple을 동일 순서의 streaming visitor로 순회한다. encoder19 PASS, cap arithmetic BigInteger 100k PASS; whole legacy bypass의 모든 factor identity/order도 검증한다. 작은 실제 fixture에 illegal cross tuple이 없어 실제 prepare-path 음성 fixture를 추가 조사 중이다.
- **통합 경계:** 기본 optimizer는 아직 원래 경로다. root가 별도 `optimizeSharedSource`와 reducer dyadic seam을 추가했다. 독립 review에서 물리 인증서의 최종 compiled-input binding, Zero 증명, decomposition monetary factor의 명시적 타입 문제가 발견돼 activation 전에 보강 중이다. 원본 surface/표현/정확 reduction/최종 compiled object를 identity로 결속하며 단순 스칼라 인증서는 물리 solver 진입을 허용하지 않는다.
- **기존 실패를 신규 회귀로 숨기지 않음:** 확대43 tests 중 `ExactPhysicalForcedStateAuditTest.forcedPublishedStateKeepsWholeProgramFactorsAndSelectsTarget`가 coherent non-baseline state 없는 fixture assertion으로 실패했다. actual frozen R14와 R11 JAR의 같은5-test class에서도 동일한1실패를 각각 재현했다. assertion/ignore/후보/강제조건을 수정하지 않았다. 별도 기존72,182,723>60M cap 실패도 유지한다. 근거 E2 `certified-shared-source-integration/{green.log,forced-r14-baseline.log,forced-r11-baseline.log}`.

#### R15 첫 쌍 종료 / R16 비교기 할당 / 실제 typed GLM 통합

- **R15 관측:** frozen R14→R15 GLM W3 LAN DP-local total 35.065895→35.872965초, common 20.484404115→20.643948608초, selection 11.746655556→12.499080951초였다. 단일 쌍이며 유의성·개선·채택을 주장하지 않는다. 목표20초 미달; 작은 해시 변경만으로 해결되지 않았다.
- **R16 변경/검증:** `PlacementAnalysis.java`의 네 기존 순차 sort/merge에서 comparison cursor를 invocation당 한 번만 만든다. 일반 `compareTo`, UTF-16/tie/equality/metrics/authority/cache lifetime은 그대로다. 기존 reset/early exit/deep shared subtree/Unicode 회귀 포함57 PASS, 독립 reviewer APPROVE. R15에 이 파일만 덮은 공통-only frozen source를 만들고 있으며 DP 미연결 변경을 섞지 않는다.
- **DP typed 실제 통합:** private Decomposition 증명 객체·surface/source/compiled object binding·encoder illegal crossed tuple까지 포함한 Maven124/124 PASS. 이후 실제 GLM의 `certify(surface)` → strict shared-source optimizer → decode/hard/canonical audit 경로가 exit0으로 완료됐다. physical=true, supported=true, q=-74/B=91/n=4591; physical decision1,096개, canonical/solver bits 모두4673189186591479073. 이 host probe는 correctness 증거이며 성능20초 증거가 아니다.
- **추가 cap 문제/수정:** projected link의 BitSet 할당이 최종 logical pairwise cap보다 앞에 있었다. 모든 projected output과 retained cell을 먼저 검사한 뒤 BitSet을 만들도록 수정했고 encoder21 PASS. 기존 cap 단위/숫자는 유지한다. 비직사각 projection의 dense fallback과 조기 cap rejection의 상호작용은 activation review에서 별도 확인 중이다.
- **기본 global 연결 진행:** `ExactPhysicalOptimizer`가 supported physical numeric certificate + transformed encoding일 때만 새 exact 표현을 선택하도록 수정했다. encoding은 한 번만 만들고 forced state/unsupported는 원래 whole exact factors를 유지한다. transformed solve 실패를 두 번째 solver나 runtime fallback으로 덮지 않는다. 기존 compiler timer/limits/compaction/order와 최종 hard/forced/canonical audit 유지. 새 기본 경로 RED1을 확인한 후 통합28 PASS. 기존 explicit-vs-legacy test는 baseline이 새 default를 따라가지 않도록 원래 factor solve를 직접 호출하게 보강했다. 독립 검토·확대 테스트·frozen Docker가 남아 있어 아직 설치/성능 채택은 아니다.


#### R16 첫 Docker 비교와 깊이 128 JFR / R17 전역 DP 동결

- **상태:** 20초 목표 미달, 성능 채택 없음. R16 GLM W3 LAN DP-local total 38.190974초, 비교 R15는 35.447197초였다. 둘 다 고정60초/compile-only/cleanup resolved=true. 한 쌍은 통계적 회귀 확정 근거가 아니지만 개선 근거도 아니다. 별도 JFR 실행40.907431초는 diagnostic-only로 성능 비교에서 제외한다.
- **진단 수정:** JFR 기록 stack depth128과 `jfr print` 기본 출력 depth5는 다르다. 최초 depth5 export는 별도 보존하고 `jfr print --stack-depth 128`로 다시 읽었다. 유효 common1,148표본(잘린 stack0)에서 CFG633, direct357, function209, physical118이다. 서로 겹치는 inclusive 표본이므로 합산 시간으로 읽지 않는다. direct binder134만 병렬화해서 전체 문제가 해결된다고 주장하지 않는다.
- **공통 구조 수정 진행:** 고정 context의 direct closure 안에서 매 wave마다 전체 logical-boundary topology/options를 다시 만드는 대신 owner별 변경과 정적 topology를 유지하는 session을 구현 중이다. direct wave→synchronous boundary fixed point→commit 경계, 전체 changed-owner 검증, cold oracle를 유지한다. 이것이 원래 후보·privacy·권한 제약을 줄이는 pruning은 아니다.
- **DP cap correction:** projected representation의 cap 실패가 더 작은 정확 dense 표현을 막던 XOR(H1,R2,R2,Q2: projected10cells/dense8cells) 문제를 보강했다. BitSet 할당 전에 projected cap을 확인하되 실패하면 기존 dense 경로가 자체 cap을 검사하게 한다. 둘 다 불가능하면 원래 whole exact 표현으로만 이동한다. cap/후보/단위는 유지한다.
- **검증:** actual private projected method XOR RED→GREEN, dense truth/solve parity와 기존 default routing을 포함26/26 PASS. 독립 code-reviewer는 R17 DP-only freeze APPROVE. 이는 성능 승인이 아니다. numeric-unsupported 실제 factory fixture와 full prepareActual XOR 통계 fixture는 여전히 검증 공백이다.
- **동결 방법:** R17은 immutable R16에 fedExact production7개만 덮는다(새2/변경5). 진행 중인 공통 LBR/Closure 수정은 포함하지 않는다. fresh Maven 및 actual JAR 회귀 후 동일 Docker DP-global compile-only로 검증한다. main 설치 JAR는 R11 유지한다.
- **위험/감지:** 원본 대안 ordinal, provenance, source identity, factor order, canonical 비용 raw bits와 certificate의 surface/compiled identity 결속을 회귀로 확인한다. transformed solve 오류를 재시도나 runtime fallback으로 숨기지 않는다.

#### R17 실제 전역 DP 실패: 작은 fixture와 실제 규모의 numeric eligibility 차이

- **상태:** 미해결. R17 fresh Maven144/144, actual frozen JAR144/144 PASS, JAR `01f0bfb99589557aa4c83e0c5edf792747b8b29f1253d99a2b1506cb805492a0`. 하지만 Docker GLM W3 LAN DP-global은 rc1로 실패했다. compile 완료 시간이 없으므로 process wall39.20초를 성공 compilation으로 기록하지 않는다. cleanup resolved=true, workload runtime0, watchdog60초 유지.
- **원인 확인:** 별도 fixed60 JFR diagnostic의 `[Exact-Representation] sharedSource=false reason=INSUFFICIENT_ACCUMULATION_HEADROOM`을 확인했다. 새 solver에 진입하지 않았으며 원래 exact prepare의 `EXACT_VE_FACTOR_CELL_OVERFLOW`로 종료했다. join의 추정 경우의 수를 현재 실패 원인으로 단정하지 않는다.
- **fixture 차이:** 기존 실제 typed integration은 hermetic 8×4, 단일 federated source, moi/mii2 fixture였다. campaign은50,000×128, W3, moi20/mii5다. 비용 표의 binary64 lattice와 최댓값이 달라 작은 fixture의 인증을 실제 입력에 적용할 수 없다.
- **해결 조사:** 106-bit carrier와 원래 canonical Neumaier 비용 계산은 유지한다. producer-owned 각 contribution의 순서 있는 maxima로 보정항의 오차 상한을 더 정확히 증명할 수 있는지 별도 검토한다. 단순히 인증 숫자를 높이거나 unsupported를 강제 통과시키지 않는다. E2 `dyadic-prefix-certificate/PROPOSED_PROOF.md`; 독립 architect 검토와 adversarial oracle 필요.
- **재현/근거:** E2 `r17-glm-w3-global-candidate2` 및 `r17-glm-w3-global-diagnostic1`. candidate1은 frozen tree의 `.git` provenance pointer 누락으로 workload 전 manifest 생성 실패했으며 artifact를 남기고 동일 R16 pointer를 복구한 뒤 새 root에서 재실행했다. JAR/production/harness 변경 없음.
- **잠재 회귀:** numeric eligibility를 넓히려면 모든 허용 assignment의 canonical raw bits와 exact integer 최종 반올림 일치를 증명해야 한다. 단일 최종 plan 비용 일치만으로 허용하지 않는다. 원래 numeric RED counterexample와 privacy/authority/cap gate를 유지한다.

#### R18 구성: 동일 의미의 공통 session + zero-aware 수치 인증

- **zero-aware 원인/수정:** 캠페인 규모 hermetic GLM에서 q=-74/B94/canonical4,593의 기존 headroom107이 거절됐다. frozen monetary maxima가 양수인 ordinal은1,200개뿐이며 나머지3,393개는 모든 상태에서 정확한+0다. +0은 canonical Neumaier의 주합/보정항을 바꾸지 않으므로 **오차 상계에만** k1,200을 적용하면 기존106-bit 조건 안의105가 된다. 순서/실제 평가/전체 canonical count/비용/fingerprint/factor/cap은 유지하며 별도 `accumulationContributionCount`를 노출한다. scalar certificate는 여전히 k=n이고, producer가 모든 cell을 검증한 뒤에만 zero임을 안다. 선택된 plan의0, epsilon, 중복 factor identity로 count를 줄이지 않는다.
- **검증:** 기존 physical regression RED(expected79/actual81) 후 targeted29 PASS, 새 실제 입력 규모의 genuine certificate 회귀1 PASS(q-74/B94/n4593/k1200/107→105), 독립 zero-padding5 PASS(1,000 random canonical-order sequences, positive/negative correction, subnormal, -0/overflow 포함). architect와 code-reviewer 독립 승인. 앞서 제안한 per-prefix residual theorem은 불필요하여 **구현하지 않았다**.
- **공통 변경:** 고정 direct invocation의 LBR session35 PASS/독립 승인. 별도 relocation binding product의 action→immutable proof를 owner 고정 invocation 안에서만 공유하며 모든 assignment/distinct/sorted/branch를 유지한다. eager full-value oracle 및 identity/isolation/합법·불법 branch3 PASS/독립 승인.
- **R18 동결:** immutable R17에서 production4파일만 변경한다: `LogicalBoundaryRealizations`, `PlacementRelationClosure`, `ExactDyadicCosts`, `ExactPhysicalOptimizer`(후자는 진단 필드). isolated session patch와 relocation patch를 적용하고 manifest로 추가/삭제0, 변경4를 확인했다. GNU patch의 `.orig` 보조 파일은 source manifest가 탐지하여 evidence로 옮겼다. 확대 Maven/package/actual JAR/Docker는 진행 중이며 성능20초나 채택으로 집계하지 않는다.
- **남은 위험:** 새 실제 global 경로가 이제 numeric gate를 통과해도 encoding/solve/최종 audit 성공은 별도 검증해야 한다. evaluator pointer는 실패한 R17 ordinary root이며 FAIL이다. 실패 receipt의 누락 필드로 뜬 `runtime was not excluded` 등은 성공 증거 부족을 뜻하며 workload runtime 실행이 관측됐다는 뜻이 아니다.

- **R18 fresh build 결과:** production4파일 manifest 확인 후 Maven319 tests에서 failure0/error0, 기존 ignore1을 유지했고 package exit0이다. JAR `efb3d32fa5a74c5507bc94204effb16110772765f2172d1ea41621988c79ff97`. actual JAR 핵심102개와 fixed60 Docker global diagnostic을 순서대로 실행한다. 이는 아직20초 성능 성공이 아니다.

- **R18 actual JAR 결과/환경 복구:** 핵심102/102 PASS. 첫 Docker diagnostic은 workload 시작 전 so002의 `/home` 여유255,959,040바이트가 고정256MiB storage preflight보다 작아 거절됐다. preflight 숫자는 바꾸지 않고 main repo의 generated `target`496MiB를 E2 `preserved-main-target-before-r18`로 **보존 이동**, 기존 target 경로는 symlink로 유지했다. 전후 installed R11 JAR SHA 동일을 확인했고 `/home` 여유740MiB로 복구했다. 새 append-only `r18-glm-w3-global-diagnostic2`에서 같은 JAR/고정60초로 재검증한다. 첫 실패를 compiler 성능으로 집계하지 않는다.

#### R18 실제 규모 추가 반례와 R19/R20 후속 수정

- **새 현재 증거가 이전 fixture보다 우선:** R18 Docker global diagnostic2는 q=-77/B97/canonical5,063/positive1,519/headroom108로 여전히 numeric eligibility를 거절한 뒤 원래 cap overflow로 실패했다. common17.85894초/model6.13205초는 진단 단계 시간이며 성공 compile이 아니다. 앞선50,000×128 hermetic fixture(q-74/B94/n4593/k1200)는 실제 campaign compiler/config/metadata 전체를 재현하지 못했다. zero-aware 수정만으로 실제 문제가 해결됐다고 보지 않는다.
- **ordinary local 첫 쌍:** 같은 Docker GLM W3 LAN DP-local R18 total33.104423초/common18.614942326/selection11.508499369, R17 control total34.252809초/common19.120889772/selection12.104432420. 한 쌍뿐이며3쌍/control/adoption/20초 달성 증거가 아니다. cleanup true와60초 유지. E2 `r18-first-local-pair-raw.json`. evaluator를 최신 R18 ordinary root로 갱신했으며 observed1/missing895/within_target0, 명시적20초 초과로 FAIL이다.
- **R19 공통 재사용:** sort마다 버리던 immutable canonical rope를 analysis scope 안에서 identity로 재사용한다. 기존 local context/비교/sidecar/authority object는 그대로이며, entry수와 재귀 descriptor-byte 보수 상한으로 retention을 제한한다. 예산 초과는 cache bypass이지 후보 제거가 아니다. scope는 nested/exception/thread에서 복원하며 종료 token의 map까지 비운다. 작성자65tests PASS, 독립 검토 중.
- **R20 수치 증명:** naive B+ceil(log2k)가 거절한 경우에만, original ordered per-contribution maxima로 canonical Neumaier 보정항의 모든 prefix를 bound한다. 각 step U=P+R+m, r=min(halfULP(U),P+R,m), R+=r≤2^53이면 signed 보정항이 q lattice에서 정확히 표현된다. base finite/exponent gate를 별도로 다시 확인하고106bit carrier/원래 n,k/order/비용/caps/bindings는 유지한다. `CERTIFIED_ORDERED_RESIDUAL_BOUND`와 실제 residual bound를 별도 노출하며 기존headroom108을105처럼 바꾸지 않는다. 독립 architect theorem 및 code CLEAR, authority/solver 포함39tests PASS; 독립 prefix/subnormal 경계 회귀와 Docker는 남았다.
- **진단 실행 실수 보존:** 첫39test 실행은 `--add-modules jdk.incubator.vector` 누락으로 fixture9개가 초기화 실패했다. 로그를 별도 보존하고 실제 runtime flag로 재실행39/39 PASS. assertion/production을 고쳐 숨기지 않았다.
- **검증 경계:** `[2^53,.75,1]`는 canonical Neumaier 실패가 아니라 옛 DP 중간 반올림 비교 반례이며 새 dyadic 경로에서 정상 수용된다. 진짜106bit 내 compensation-loss `[2^105,2^52×5,1]`는 R20도 계속 거절한다. E2 `ordered-residual-certificate/{PLAN.md,ARCHITECT_PROOF.md}`. 설치R11 그대로, 전체896/20초 미달, workload runtime실행 없음.

- **R19 review 수정 완료:** nested-only rule/emission/action descriptor가 먼저 cache에 들어간 뒤 generic dispatcher로 들어오면 cold와 달리 수용되던 경계 차이를 독립 검토에서 찾았다. generic8type whitelist를 cache조회 전에 검사하도록 고쳤으며 warm-case RED→GREEN, actual descriptor identity reuse/closedscope isolation까지66tests PASS. architect final CLEAR for freeze.64MiB는 descriptor/entry 추정 weight이며 실제 전체heap 상한이라고 주장하지 않는다.
- **R20 독립 경계 검증:** 별도4tests PASS로 R=2^53 포함/최초초과, 모든 accepted prefix의 exact main+signed correction, 양/음보정, subnormal→normal/maxfinite/overflow와 numerichelper의 physicalauthority 부재를 확인했다.
- **현실성 회귀 보강:** 기존 host fixture가 uniformPUBLIC, default network125/serdes0/control0, 직접builder 경로였다는 독립 audit에 따라 freshchild JVM에 campaigncalibration625/210/14.7/.35와 보호X/PUBLICY, SINGLE_NODE/COMPILE_EXACT, finalHop 공통준비를 적용했다. 이 host fixture는 q-77/B97/n5091/k1525/headroom108이며 R18numeric RED→R20numeric GREEN1이다. Docker의 n5063/k1519와 같다는 가정은 반복 검사에서도 성립하지 않았으므로 **같은 inventory라고 주장하지 않고**, 정확한 host inventory와 별도 Docker gate를 명시한다. source byteformat/8core16GiB/cache 설정을 맞춰도28/6 차이는 남았고 codegen 등의 잔여 경계는 별도 조사한다. never-passed guessed-count assertion을 production을 바꿔 맞추지 않았다. E2 `ordered-residual-certificate/campaign-{red-final,green}.log`.
- **R20 동결/빌드:** immutableR18에서 production4파일 추가삭제없이 변경(PlacementAnalysis,NeutralPlacementGraphBuilder,ExactDyadicCosts,ExactPhysicalOptimizer). 새scope+ordered/boundary tests와 보호된calibrated test를 포함34classes Maven/package 검증 중. 작업중R21(동일dependency-union일 때 SCC재구축만생략)은 이 frozen tree에 포함하지 않는다. 모든896/20초와3pairedcontrols 미달, 설치R11 유지.
- **R20 fresh build/actual JAR:** Maven384 tests에서 failure0/error0/기존ignore1, packageexit0. JAR `2d2aec9671ac784614333ef19db25d6424987deb9f518f3ce7086cd24e78d043`. 같은 actualJAR 핵심177/177 + 별도sharedhash3/3 PASS. fixed60 Docker GLM W3 global diagnostic 실행 중. 이 숫자는 test coverage이며 아직20초 성능성공이 아니다.
- **두 번째 disk preflight 복구:** R20 diagnostic1도 compiler 시작 전에 so002 `/home` free16MiB로 실패했다. 이번 goal의 E2 completed receipt로 소유/종료를 확인한 staging36개만 전후 파일SHA를 비교하며 E2 `preserved-completed-campaign-staging-r20`로 보존 이동했고 원래 경로를 symlink로 유지했다. cleanup 미해결/미확인 타작업 root, Codex SQLite, benchmark/harness/preflight limit은 건드리지 않았다. free474MiB, sameJAR diagnostic2 재시도. manifest `completed-campaign-staging-relocation-manifest.json`.

#### R20 Docker 환경 복구와 R21 SCC schedule 독립 검토

- **상태:** 20초/896개 목표 미달. R20 diagnostic2는 컴파일 전 overlay 배포에서 so008 `/home` ENOSPC로 종료했다. compiler/numeric/join 결과가 없으므로 성능 실패 수치로 해석하지 않는다. 다른 호스트는 충분했고 so008 free3.5MiB였다. harness/256MiB preflight/60초/watchdog는 변경하지 않았다.
- **안전한 복구:** E2 completed receipt로 확인한 동일36개 staging의 so008 파일을 controller so002의 넉넉한 `/grid/3/.../preserved-so008-completed-campaign-staging`로 복사하고 모든 SHA를 비교했다. 원격에서도 변경 직전 재검증 후 중복 사본만 치우고 각 원래 경로에 archive 위치/해시 receipt를 남겼다. 타 작업, 현재 campaign, Codex SQLite는 건드리지 않았다. 첫 receipt 경로 문자열 치환 오류로 1개 디렉터리의 receipt 쓰기가 실패했으나 사본은 이미 검증·보존되어 있었다. 수정 recovery는 이 정확한 빈 디렉터리 1개만 예외로 인정하고36개 전체 receipt를 완료했다. 로그/원본SHA/검증manifest/복구script 모두 E2에 보존. free460MiB 복구 확인.
- **다음 측정:** 동일 immutable R20 JAR로 새 append-only `r20-glm-w3-global-diagnostic3`를 실행한다. 진단과 ordinary 성능 채택은 분리한다. 설치된 main R11 JAR는 유지한다.
- **R21 변경:** 고정 potential P와 direct support S의 union이 실제로 바뀔 때만 SCC schedule을 재구축한다. `(P∪S_old)△(P∪S_new)=(S_old△S_new)\P`에 따라 기존 실제 identity edge add/remove 지점에서만 flag를 계산한다. 기존 changed/dirty expansion/removed invalidation/commit 순서는 유지한다. Closure18추가/5삭제, 새3+기존21 tests=24/24 PASS. 독립 architect CLEAR for freeze; 단일 endpoint identity와 mixed delta 회귀를 추가 보강한다. R21은 R20에 포함되지 않는다.
- **잔여 위험/감지:** SCC 표본 상한을 wall-time 절감으로 주장하지 않는다. R20의 실제 수치 인증/전역 solve/최종 audit, R21 독립 immutable build·actualJAR·Docker·pairedcontrols와 전체896 검증은 아직 필요하다. 모든 작업은 합법 후보·privacy·authority·cap·timer 보존이며 추가 pruning이나 fallback이 아니다.

#### R20 실제 실행 결과와 R22/R23 후속 수정

- **상태:** 여전히20초 미달. R20 ordinary GLM W3 LAN DP-local total33.961132초/search-space19.516575202/adapter11.765170653, cleanup=true. 이전 R18 33.104423초보다 빨라졌다는 근거는 없다. 이번 한 회로 통계적 회귀 확정도 하지 않는다. 최신 evaluator pointer를 R20ordinary로 갱신: tests10PASS, observed1/missing895/within0, compile/fullplanning20초 초과 FAIL.
- **전역 DP 현재 원인:** R20 Docker diagnostic3는 실제 q-77/B97/n5063/k1519/headroom108/residual886705245126656의 강화된 수치 인증을 통과했다. 그러나 encoder가 `UNSELECTABLE_SOURCE_REFERENCE`로 전체 변환을 포기했고 기존 표현의 `EXACT_VE_FACTOR_CELL_OVERFLOW`로 실패했다. 아직 새 transformed solver의 join 실패를 관측한 것은 아니다. common20.009724202초/model9.200603413초는 진단 단계 값이고 process40.70666초를 compile 성공 시간으로 쓰지 않는다.
- **R22 해결 근거:** 기존 hard factor는 consumer가 요구하지만 source의 어떤 alternative도 선택하지 못하는 참조를 equality(+∞)로 이미 금지한다. 변환 전체를 거절할 필요가 없다. 원래 selectable/NONE 순서를 유지한 R domain 뒤에 demanded-only 참조를 추가하고 owner H→R link를 그대로 두면 해당 열 전체가+∞다. 원본 row/header/ordinal/provenance/cost는 삭제하지 않는다. 독립 test-engineer가 이 정확성 근거를 검토했다. 구현/작은 exhaustivereg회귀 진행 중.
- **현실적 재현:** 보호X/PUBLICY calibrated host fixture도 동일한 `UNSELECTABLE_SOURCE_REFERENCE`로 RED이며, 임시 정확 encoder overlay에서는 transformation GREEN. 원래54,565 rows→3,577 header/89 source variables, transformed466,351 factor cells(원래43,359,266),208 projected links. 이는 host correctness evidence로, 실제 Docker inventory/시간/최종 solve 성공을 대체하지 않는다. 기존 campaign regression에 실제 encode 성공 assertion을 추가한다. E2 `unselectable-source-reference-host`.
- **R23 공통 재사용:** generated root가 읽지 않는 자기 published-support history 변경으로 support template를 버리던 부분을 registered acyclic root에서만 primitive projection으로 비교한다. descendants/negative footprints는 기존 full projection, ordinary/public memo는 기존 검증 그대로다. P_A history RED→GREEN 후 독립 review가 missing-node negative query의 새 SCC lookup 예외를 발견했고, 회귀로 재현 후 registered-node guard를 추가했다. cyclic/zero-budget/primitive-change/cold equivalence 포함77tests PASS, architect final CLEAR for freeze. E2 `generated-root-history-reuse`. 선택지/CFG barrier/authority/timer/cap 변경 없음.
- **잔여 이슈/위험:** R21/R22/R23은 다음 immutable 후보로 통합·검증 전이며 설치R11 그대로. 보존된 unsupported/infeasible 참조가 실제 owner 선택으로 유입되지 않는지 exhaustive hard-factor/canonical raw-bit audit와 최종 Docker solver 검증으로 감지한다. 공통 cache의 이득도 paired Docker로만 판단한다.
- **R22 작은 회귀 최종 보강:** proof-only duplicate clauses 때문에 legacy9개 witness가 canonical1개로 합쳐지는 fixture에서 raw-cost 집합만 비교하면 동가 비용의 합법 configuration 누락을 놓칠 수 있었다. 새 dead-reference fixture만 emission당1clause로 단순화하고 exhaustive `Map<decision assignment, raw bits>` 전체 동일성을 복구했다. 기존 별도 provenance/fiber 회귀는 유지했다. R20RED24중1, 수정GREEN24/24, 독립 test-engineer CLEAR for freeze.
- **통합 R23 동결/빌드:** immutableR20에서 production3파일만 변경(Closure,NativePlacementContinuity,SharedSourceEncoding), 추가/삭제0. 테스트3수정/1추가를 명시적으로 복사하고 전체src/main SHA diff로 확인했다. fresh Maven393tests/0failure/0error/기존ignore1, packageexit0, JAR `079aeadd14a6e3989d9602f5128cef787c82ffd3260972d9be48756a823d52a3`. 실제 JAR 회귀 실행 중이며 Docker/performance gate는 아직 아니다. E2 `r23-validation`.

- **R23 actual JAR gate 완료:** frozen JAR에서 핵심260/260 + shared hash3/3 PASS, 기존 PUBLIC ignore는 유지했다. manifest/source SHA를 재확인했고 main 설치R11 SHA도 불변이다. 새 `r23-glm-w3-global-diagnostic1`을 동일60초 watchdog/compile-only/JFR 조건으로 시작했다. 아직 transformed solve/최종 audit/20초 달성을 주장하지 않는다.

#### R23 전역 DP 실제 성공 / 20초는 미달

- **실제 원인 제거:** Docker GLM W3 global diagnostic1에서 변환과 최종 exact solve/compile까지 PASS. 원래54,557row→3,569header, encoded2,243/compiled1,163변수; 공유표현 준비6.045072346초. sparse1161step 전부종료, 실제8,926,444assignment/저장finite175,513cell. 큰두join은 각각5,355,720/2,240,784개의quotient assignment를 평가했다. 원래논리공간/cap단위는 유지한다.
- **진단과 일반측정 분리:** JFR39.73514초/common20.220501636/adapter16.694045879는 진단값. ordinaryglobal1 **38.119685352초**/common17.413890125/adapter18.010338797; ordinarylocal1 **31.675719255초**/common16.951446029/adapter11.778250018. 모두cleanup true, runtimeProgramConstructed true, workloadExecutionStarted false, loweringaudit missing/mismatch0. 단일표본으로 개선율/채택/20초를 주장하지 않는다.
- **잔여병목:** common16.95–17.41초와 모델/비용/optimizer 준비가 합계목표를 초과한다. 최신JFR에서 encoder functional-dependency 확인이 같은 참조를 반복해deep hash/ordinal 조회하는 경로164표본, sharedprepare306/optimizer686표본이다. 각표본은겹치며절감초수아님. 다음은 같은immutable clause의quadratic identity ownership scan 제거와 shared-source ordinal행의1회준비; cap/후보/authority변경없음.
- **회귀위험/검증:** 새ordinal준비는 모든source domain확정후identity-keyed sparseint행으로바꾸고rawmap을버린다. wildcard-1/NONE/동등하지만다른reference/dead demanded값/원래scope·cellbits·caps를테스트한다. 설치R11과20초full896 FAIL상태유지. 근거E2 `r23-first-docker-results.json`, `r23-glm-w3-{profile,sparse-join}.json`.

#### R24 준비 중: 합법 공간을 그대로 둔 반복 준비 제거

- **상태:** R23 일반 GLM W3 global38.119685/local31.675719초로 20초 미달. 최신 evaluator는 R23global root,10unit PASS/observed1/missing895/within0 FAIL. 새 후보를 설치하거나 성공으로 보고하지 않는다.
- **소스 참조 ordinal 재사용:** 모든 selectable/NONE/demanded-only source domain을 확정한 뒤, 각 private sparse owner→reference행을 owner identity→ordinal행으로 한 번 변환한다. 참조 equality는 한 번만 평가하고 기존 rawmap은 해제한다. 별도 dense owner×row 공간을 만들지 않으며 factor cap에 metadata를 혼합 계상하지 않는다. 기존 FD·fiber·membership·decode 순서/값 유지. R23 RED27중1(336회 재조회), GREEN27/27+확대21/21+독립 lifecycle2/2; 보호 host inventory 원본과 완전동일. 독립 CLEAR.
- **Clause 소유권 index:** immutable canonical list의 각 clause를 매 receipt/alternative마다 처음부터 identity scan하던 비용을 list-owned lazy identity→first ordinal로 바꾼다. 작은/일반 list는 기존 선형 경로이고 값이 같아도 다른 객체인 foreign clause는 계속 거절한다. 작성자 focused20/20 및 확대27/27 PASS. 초기 tool응답 미완료를 timeout으로 잘못 요약한 기록은 실제 exit0/51.261초 완료로그에 따라 수정했다. 독립 검토 중.
- **공통 factor freezer 재사용:** shared-root freeze가 기존 odometer helper를 쓰지 않고 매 cell mixed-radix division을 반복하던 중복 loop/globalbuffer를 삭제한다. 전체 input preflight와 per-factor checkedCells 위치/callback 순서/즉시 cost validation/새 solve의 재평가를 유지한다. callback buffer는 원래 계약상 read-only다. 변경 전5/5, 변경 후확대79/79 PASS, architect CLEAR. 첫 expanded command의 잘못된 class이름 초기화 실패는 보존했고 올바른 기존 class로 재실행했다.
- **범위/위험:** R24 source는 immutableR23에서 production5파일만 변경, tests3개 추가/1개 수정으로 조립했다. clause 독립 검토와 fresh Maven/package/actualJAR/Docker는 남아 있다. metadata heap lifetime, foreign authority, 실패시 partial publication을 회귀로 검사한다. 이 작업은 추가 pruning·heuristic·cap상향·timer 이동이 아니다. 설치R11 불변.

#### R24 통합 검증 / 기존 ordered-read-set 회귀 fixture 안정화

- **상태:** production5개 변경의 독립 검토 모두 CLEAR. immutable R24 fresh Maven44classes/458tests/0failure/0error/기존ignore1, packageexit0. JAR `6dd42aa309f2b5e53984600a12825dd08a4e0e4dbb23a6d5e655fa4d04c58718`; 설치R11 불변. 실제 Docker GLM W3 global 진단을 시작했으며 성능 채택은 아니다.
- **새 문제/원인:** actualJAR 통합328tests에서 기존 `structuralRevisionReusesOnlyTheExactOrderedComponentReadSet`의 SCC 재사용 assertion이 한 번 실패했다. 테스트가 `new IdentityHashMap<>(full.nodes)`로 복사하면서 내부 용량과 충돌 key 순서를 바꿀 수 있었다. production guard는 순서까지 같은 identity read-set에서만 SCC를 재사용하므로 재사용 거절은 안전한 기존 동작이다.
- **재현/해결:** 동일 테스트100회씩 기존 R23와 새 R24 JAR에 실행해 둘 다 iteration52에서 정확히1회 실패했다. 독립 architect 검토 후 테스트의 payload-only 변경을 `LinkedHashMap`에 원래 iteration 순서대로 복사하도록 고쳤다. production guard/edge·reaching·foreignidentity 음성 assertion은 그대로다. 교정 테스트100/100 및 actualJAR328/328 PASS. frozenR24 production·테스트 source는 변경하지 않고, 교정 테스트 class overlay를 명시하여 검증했다. 이후 후보에는 main의 교정 test source를 포함한다.
- **수정 파일/근거:** `NativePlacementContinuityTest.java`의 fixture만 수정. E2 `r24-validation/structural-readset-order`에 R23/R24 RED, 교정 GREEN, 계획, SHA. 기존 실패로그도 보존. 원칙/authority 변경 없음.
- **잔여 위험:** 테스트-only overlay라는 사실과 전체 프로젝트 테스트가 아니라 선택 suite라는 범위를 보고한다. 기존 알려진2개 certificate/forcedfixture 실패는 별도 상태를 유지한다. 성능은 Docker 일반측정·paired control·896 gate로만 판단한다.

#### 다음 구조 개선: input-authority의 읽지 않는 DIRECT 구분 제거

- **상태:** R25 후보 구현/검증 중, 아직 성능 채택 없음. R24 immutable 후보와 분리했다.
- **증상/원인:** 현재 input-authority observer가 선택 receipt의 모든 DIRECT binding을 category에 포함한다. canonical evaluator의 `isRelocationActive -> hasDirectBinding`은 관련 action의 obligation에 있는 **owner identity + input position**만 읽는다. 무관한 upstream proof clause 차이 때문에 같은 hard truth가 여러 category로 분리될 수 있다.
- **해결/근거:** 기존 source value-version action 집합별로 private identity-keyed owner→position read-set을 한 번 만들고, DIRECT observation만 그 위치로 제한한다. 모든 alias/self-owner obligation을 포함한다. source reference/상태/worker pool/native residency/derived action identity/RELOCATION 관측은 그대로다. canonical candidate나 factor를 삭제하지 않고 기존 evaluator와 모든 privacy/authority/cap/timer를 유지한다. 독립 architect가 정확 read-set 근거를 확인했다.
- **검증:** 독립 boundary 회귀는 R24에서4개 중 기대한2개 projection assertion RED; relevant source/self-owner 및 RELOCATION 보존은 기존에도 PASS. main `ExactPhysicalModel.java`에 bounded patch 후 GREEN/exhaustive truth 검증 중. E2 `input-authority-observation-projection/IMPLEMENTATION_PLAN.md` 및 `shared-source-certificate/input-authority-observation-projection`.
- **회귀 위험/정직한 범위:** 정확한 category quotient여도 auxiliary domain/descriptor/fingerprint 및 factorization 선택 여부가 바뀐다. DP-local의 시간제한 탐색경로가 동일하다고 주장하지 않는다. canonical truth/cost rawbits/합법성, 전체 solver parity, 선택 quality 및 fresh Docker controls가 필요하다. JFR70표본 중 representative evaluator11표본만이라는 한계를 지키며, 더 많은 factor가 새로 압축될지는 census로 확인한다.

#### R24 일반 측정과 W1 우선순위 교정

- **상태:** full896/20초 목표 계속 FAIL. R24 ordinary GLM W3 global **32.451586초**/common17.449001959/adapter12.380122506; W1 global **56.286440007초**/common24.607929399/model3.694769630/cost5.749478837/optimizer18.747828538. 둘 다 actual compile-only/최종runtimeProgram/lowering audit PASS, cleanup_resolved=true. W3만으로 worst-case를 대표할 수 없다. W1을 다음 진단 우선순위로 변경했다.
- **진단/일반 구분:** W3 JFR35.189304991초/common19.666486856, 공유encoder준비4.089107317초. JFR없는 일반W3값과 섞어 개선율을 계산하지 않는다. 현재 W1 JFR를 같은60초 watchdog으로 시작했고 timeout이 발생해도 성공시간으로 사용하지 않는다.
- **공통 분석 census 결과:** 별도 metrics-only overlay의 GLM host probe에서 owner visited26305=early24089+active2216, active=noop372+changed1844, repeated5472, 보수적 posthoc eligible95. Public memo5068hit/14711miss, support16680hit/9114miss. 기회95개는 complete native positive/negative/dead/empty read footprint가 없는 **성능 기회 지표일 뿐 skip certificate가 아니다**. 전체-owner skip 투자 우선순위를 낮추고 구현하지 않았다. 원본/overlayoracle7/7 및 GLM1/1 PASS, E2 `direct-owner-noop-census/REPORT.md`.
- **다음 변경 근거:** exact state/realization binder가 이미 정확한 graph-owned state를 입력받아도 불변 fact/emission/realization wrapper를 매번 새로 만들고 있었다. 기존 identity 기반 revision/canonical reuse를 방해할 수 있다. 두 binder에서 같은 pointer 입력일 때만 원본을 유지하고, equal-but-distinct state/wrapper나 실제 literal FED anchor 생성은 기존 cold normalization 경로를 유지하는 bounded repair를 준비했다. 아키텍처 검토 CLEAR, 독립4회귀의 identity2개 RED/graphstate·missingtarget control PASS. 구현/추가 음성 회귀/독립 검토 진행 중.
- **R25 구조 검증:** 보호GLM host old/new 모두 PASS; canonical3058factors/523965550cells와54565rows/3577headers 유지, encoded hard39835809→17345171, authority24→54개가 lossless encoding 적용. raw shared profile10878211→6631665. host시간은 성능근거가 아니다. R25projection과 R26identity repair를 다음 동일 immutable 후보로 묶어 fresh Maven/actualJAR/Docker 검증할 예정이며 아직 채택하지 않았다.
- **남은 위험:** W1의 더 큰 optimizer 작업량은 새 JFR로 확인한다. wrapper 재사용의 downstream cache amplification은 아직 가설이며 constructor 작업 카운터를 예전값처럼 조작하지 않는다. 어떤 변경도 privacy/authority/후보/cap/timer를 완화하지 않는다.

#### R24 W1 진단 확정 / R26 동결 / support-free DP 반복 주소 계산

- **상태:** 진행 중, 20초 목표 미달. 일반 W1 56.286440007초가 현재 관측된 더 나쁜 사례이며 W3 일반 32.451586초와 구분한다.
- **새 진단:** `r24-glm-w1-global-diagnostic1`, attempt `01790758542266665173-27dcc597`, compile54.832506초/common24.803076603초/model3.911471136초/cost5.400284703초/optimizer17.093480705초. audit mismatch0, cleanup true, workload 실행0. 진단값은 일반 benchmark와 섞지 않는다.
- **DP 병목:** 1,442개 closed join의 실제 평가40,260,288회 중 variable54 하나가38,856,520회/7.405175862초다. quotient output669,940×eliminated58이며 supportRows0이지만 finite output은425,828개뿐이다. 따라서 supportRows0을 '모든 비용 유한'으로 오인하면 안 된다. JFR의 `dyadicSum`422/`DenseFactor.cell`261 samples는 겹치는 표본이지 절약 가능한 시간의 합이 아니다.
- **다음 수정의 원리:** 인증된 dyadic + support relation 없음인 bucket에서 separator를 한 번 decode/lift하고 factor별 기준 cell을 계산한 뒤, eliminated 좌표의 정확한 offset만 더한다. 모든 후보38,856,520회는 그대로 평가한다. sparse storage lookup, factor 순서의 infinity/overflow 검사, 원래 ordinal tie, 기존 cap/preflight/backpointer를 유지한다. architect 설계 CLEAR 후 executor와 독립 test engineer에 각각 production 한 파일/새 회귀 한 파일을 분리 배정했다. 일반 double-double 경로에는 적용하지 않는다.
- **R26 동결:** R24 기반 `candidate-r26-observation-identity-source`에 검토 완료한 production 정확히3개만 추가 변경했다(`ExactPhysicalModel`, `PlacementSupportRelations`, `PlacementRelationClosure`). 추가/삭제 production0, 전체 source SHA manifest 저장. R25 observer projection + R26 exact identity repair를 포함하고 위 DP kernel은 포함하지 않는다. 독립 binder10/10, 확대13/13 PASS/CLEAR; fresh55-class Maven→package→actual JAR 순서로 검증 중이다.
- **의사결정 근거:** oracle/runtime 규칙, 후보 집합, 비용식을 바꾸지 않고 observer의 실제 read set 및 immutable identity/address 계산만 개선한다. 전체896와 동일-engine 반복 control은 아직 남았으며 installed R11 JAR를 채택 후보처럼 교체하지 않았다.
- **잠재 회귀/탐지:** 관측 동치의 구조적 encoding 변화는 local anytime 경로/descriptor를 바꿀 수 있어 최종 품질 검증이 필요하다. DP kernel은 중간 high/low raw bits, 원래 선택 ordinal, finite counts, sparse/dense crossover, map/scope 순서를 독립 비교한다. temporary low-array 할당 이력까지 같다고 주장하지 않는다.

#### R26 결과와 R28/R29 다음 반복

- **R26 검증:** fresh Maven55classes/526tests, failure0/error0/기존ignore1; 실제 JAR448tests PASS. JAR `2263ef373111c42a59cda562a4233112d356a35366123cbebe8952c884e0bb7d`, source manifest와 일치.
- **일반 Docker:** W1 global54.264059692초/common23.783967586초/model3.479278336초/cost5.937480972초/optimizer17.535790964초; W3 global34.546288316초/common17.454191766초. 모두 compile-only/runtime-program/lowering audit/cleanup PASS이나20초 FAIL이다. R24 단발 W1 56.286초보다 낮고 W3 32.452초보다 높아서 전체 개선이나 채택으로 선언하지 않는다. evaluator10tests PASS, observed1/missing895/within0으로 FAIL 기록.
- **W1 진단:** R26 57.437004341초/common26.3927552초, 공유encoder preparation6.779579365초(R24진단8.977658822초); dominant bucket38,856,520회/8.206563649초, 전체40,260,703회/1500steps. 원래116287rows/4619headers/q-77/B97/n5218/k1742는 유지했다. `jfr print` 최초 export에서 `--stack-depth128` 누락으로 display가 짧아 root attribution이 틀렸으며 그 산출물을 `*-default-print-depth.*`로 보존하고 올바른128depth로 다시 추출했다. 올바른 common1397/model192/cost222/optimizer1349samples이며 시간으로 환산하지 않는다.
- **R28 최종:** separator-major dyadic support-free kernel은 모든 후보를 평가한 후 각 separator의 exact minimum을 한 번만 accumulator에 전달한다. 당초 후보마다 offer하던 중간 SHA의 승인으로 대체하지 않고, 최종 SHA `84ad347b06f7180cd0eeff74c19c294c2a0720649a471bbe7f6e5327e329d946`에 대해 architect 재검토 CLEAR. 독립 BigInteger 중간 high/low raw-bit/backpointer oracle5tests, 명시적2^53carry 포함; 통합79tests PASS. 원래 cap/검증/word arithmetic/ordinal tie와 INF/storage lookup 유지.
- **R29 최종:** fingerprint writer 안에서 작은 immutable canonical subtree의 UTF-16 chunk를 identity로 재사용한다. 일반 appendTo/비교/UTF-8 surrogate 처리/SHA 순서는 바꾸지 않는다. proper-subtree만 캐시하고4096chars/32768entries/64MiB 보수적 descriptor+String charge를 넘으면 원래 내용을 그대로 순회한다. 새 의존성/후보 제거/전역 보관 없음. 새6+기존13tests=19PASS; deep rope/재진입/예외/zero·exact·aggregate 예산/동일hash다른text/split-surrogate 실제 SHA 동치, 별도 architect CLEAR.
- **R29 동결:** R26 기반 `candidate-r29-dyadic-replay-source`, production 정확히3개 변경(`ExactCategoricalSolver`, `PlacementAnalysis`, `ExactPhysicalCostModel`), 추가/삭제0, 새 회귀2classes. fresh58classes Maven/package/53classes actualJAR 진행 중. R27 receipt projection은 포함하지 않았다.
- **다음 우선순위:** W1 공통만23.78초라 DP만으로 목표 달성 불가능하다. whole-owner skip은 기존 census상95/26305로 효율이 낮아 구현하지 않는다. immutable R26 기반 artifact-only 계측으로 공통 active 작업의 wave/relocation product/native memo miss 원인을 분리한다. R27은 보조적인 lossless observer lane이며 공통 분석 해결을 대체하지 않는다.
- **저장 공간:** 정확히9개 완료 campaign의 cleanup receipt와 원격root를 고정한 뒤 so002/so008 staging을 grid3에 SHA 검증 보존했다. so002 원위치는 symlink, so008은 archive receipt를 남겼다. 첫 remote script의 텍스트 치환 syntax error는 실행 전 실패했고 로그 보존 후 재검증·정상 완료했다. preflight256MiB와 unrelated files는 그대로다. `r26-staging-preservation/`에 전체 증거가 있다.

#### R29 실제 결과 / R27 좁은 후속 변경 / 공통 원인 계측

- **R29 검증:** fresh58classes/548 Maven tests, failure0/error0/기존ignore1; 실제 JAR500tests PASS. JAR `4923e8db090843781a7fd977cf977ef2404b235466ff45403e4f2155db624ade`와 frozen source manifest 확인.
- **일반 Docker:** W1 global49.710211126초/common23.714731074초/model4.231421475초/cost5.844145783초/optimizer12.238345051초; W3 global30.712426766초/common17.338439529초/model1.973683924초/cost2.577991619초/optimizer5.405954757초. full compile-only/runtime-program/lowering/cleanup 모두 PASS, runtime0. 여전히20초 FAIL이며3paired controls와896cells는 완료되지 않았다.
- **정확한 kernel 개선 증거:** W1 진단51.455777583초에서 variable54가 R26진단8.206563649초→3.312481032초. 원래38,856,520평가/669,940quotient outputs/425,828finite outputs, 전체40,260,703평가/1500steps, 같은 numeric certificate/116287rows/4619headers 및 최종 plan `7f259253ab783a195da1c602b1fdaa3a51b7310275399eb484c026343286464a`를 유지했다. 후보를 자르지 않은 반복 주소 계산 제거라는 설명을 지지한다. 진단 common24.781977616초/cost5.534681558초/model5.178498178초/optimizer12.283274055초, encoder preparation8.310720271초. 전체 변화나 R29 replay만의 독립 개선율로 혼동하지 않는다.
- **평가:** 최신 pointer는 `r29-glm-w1-global-candidate1`. evaluator10tests PASS, observed1/missing895/within0,20초 초과로 FAIL checkpoint. native goal ACTIVE, 설치 R11 JAR 유지.
- **R27 후속 구현:** root는 `ExactPhysicalModel`만 변경했다. graph.nodes 전체를 VV.equals로 묶고 owner identity 집합으로 고정해 준비 단계에서 한 번 만든다. nonempty action일 때 실제 source owner가 아닌 receipt의 own realization 관찰값만 생략한다. empty action은 기존 관찰값을 전부 유지하며 더 강한 완전성 가정은 사용하지 않는다. 실제 evaluator/receipt/factor/후보/scope/순서/다른 관찰값은 그대로다. 최종 SHA `7d39a9562805d6a9f2b56d33c1dc685a93f9c6c207fff88e366aaf8f38341835`, import 보완 후 javac-v2 PASS/architect 정적 CLEAR. 독립 truth/encoding·실제 prepared-factor probe는 진행 중이며 아직 동결/채택하지 않았다. 첫 javac import 누락 실패를 보존했다.
- **독립 회귀의 범위:** R25 private reflection helper arity만 업데이트하며 기존 DIRECT-binding assertions를 유지한다. 새 인공 truth fixture가 OUTSIDE receipt binding을 읽으면서 action obligation을 다른 CONSUMER로 지정한 불일치를 찾아, evaluator read set과 일치하도록 fixture를 수정하도록 전달했다. production observer를 잘못된 fixture에 맞춰 넓히지 않는다.
- **공통 분석:** 이전 작은8×4 GLM census를 실제50,000×128 Docker W1과 같다고 취급하지 않는다. artifact-only overlay small oracle13/13 및 tiny GLM1/1 PASS 후 보호된 calibrated W1 host census를 실행 중이다. host 시간은 성능근거가 아니며 inventory 차이를 명시한다. Node wrapper 재생성 가설은 기각했다: Native structural guards, dirty checks와 revision 판정은 Node.equals를 사용하고 DirectSourceIndex는 fact만 입력받으므로 equal wrapper identity 자체가 비싼 invalidation을 일으킨다고 볼 수 없다.

## R30 준비 및 R31 source-pruning 삭제 전파 인덱스 지연 (진행중)

- **문제 정의/증거**: R29 일반 Docker GLM W1/global 49.710211126초, W3/global 30.712426766초로 20초 목표 미달이다. 완료된 R26 calibrated W1 host census에서 SOURCE_PRUNING은 1,927,362,568 bytes를 할당했고 초기 clause 701,210개/역방향 incidence 1,539,901개를 만들었으나 10회 모두 deletion queue 방문이 0이었다. Host census는 구조/할당 근거이며 Docker 시간이나 절약 가능한 초 수가 아니다.
- **해결 방법/경계**: R31은 기존 단일-pass source/action/CFG writer 검사를 재사용한다. 처음 executable이던 realization이 하나도 사라지지 않았을 때에만 전파 인덱스 생성을 생략한다. 무효 OR clause와 처음부터 non-executable이던 행은 여전히 걸러낸다. executable deletion seed가 있으면 **원래 입력**에 기존 worklist를 실행한다. 동일 live-reference 집합이 유지되므로 supported cycle/duplicate reference도 그대로이고 추가 전파는 생길 수 없다. 기존 논리 inventory 계수와 실제 materialized incidence 계수를 분리한다.
- **수정 예정 파일**: PlacementSupportRelations.java, 독립 PlacementSupportDeletionWorklistTest 관련 회귀 테스트. 구현 전 계획/RED를 먼저 고정하고 독립 아키텍처 리뷰 후 frozen candidate로 측정한다.
- **R27→R30 검증**: source-receipt observer projection Model SHA `7d39a9562805d6a9f2b56d33c1dc685a93f9c6c207fff88e366aaf8f38341835` 독립 CLEAR. 10 tests PASS, 실제 logreg changed factor ordinal 1331의 68,640 cells를 baseline/new × allocating/allocation-free 모두 canonical/expanded raw-bit 동일 확인. 전체 198,580,938 canonical cells exhaustive 검증이라고 주장하지 않는다. R30은 frozen R29에서 이 production 파일 하나만 변경했으며 전체 src/main manifest 일치 확인 후 59-class Maven/package/actual-JAR gate 진행 중이다.
- **잔여 이슈/회귀 위험**: R31은 seed 판정을 fact count나 최종 비어 있지 않음으로 대신하면 잘못된 fixed point가 된다. invalid OR-but-live, 마지막 duplicate source 삭제, 필수 writer 소멸, chain/cycle/random repeated-pass oracle로 검출한다. 이 수정 하나로 전체 20초를 달성한다는 근거는 없다. 합법 후보/authority/privacy/cap/timer/watchdog 불변, runtime 0건 원칙을 유지한다.
- **근거 경로**: `planning-30s-recovery-20260930/r26-common-work-census/REPORT.md`, `shared-source-certificate/input-authority-source-receipt-projection/REPORT.md`, `r30-validation/`, `no-deletion-seed-pruning/`.

## R30 Docker 결과와 R32 canonical descriptor DAG 중복 계상 수정 (진행중)

- **R30 결과**: JAR `85547ace66fbff8491b7c2a0626e7261d3d4cf2a72ca578ba4ebed627239bfc7`; fresh Maven 59 classes/553 tests/0F/0E/기존 ignore 1, actual JAR 505 PASS. 일반 GLM W1/global compile **48.648454862초**, common 24.740757932/model 4.149622202/cost 5.079039417/optimizer 10.982443955. 진단 compile 49.467397914초는 일반 비교에 합치지 않는다. 둘 다 cleanup/compile-only/runtime-program construction 확인, lowering mismatch 0, runtime 0. 20초 evaluator는 **FAIL, observed 1/missing 895/within 0**.
- **구조/품질 증거**: R30 진단의 원래 physical rows 116,287, header 4,619, 비용 기여 5,218개와 R29의 exact objective raw bits `4675291222064752975` (36804.765706973725 ms)가 동일하다. 관찰자 분해 변경 때문에 encoded variables 2,589→2,619, compiled 1,502→1,527 및 cost/plan fingerprint는 달라졌다. prepare 6.340904880초, 가장 큰 var54 결합은 38,856,520회 그대로/3.485324096초. 전체 1,526 종료/미종료 0, evaluated 40,260,898회이다. 전 workload/3회 paired 품질 통과를 뜻하지 않는다.
- **새 병목 증거**: artifact-only R29 canonical cache census는 64 MiB charge가 10,880 entries에서 소진됨을 확인했다(131,072 entries 제한에는 안 닿음). 같은 retained text DAG를 identity 기준 한 번씩 세면 기존 descriptor model상 17,195,292 bytes였고, weight rejection 9,867,183회였다. 전체 JVM heap 측정이 아니며 key 객체 graph를 포함하지 않는다. shared subtree를 매 root마다 재과금하는 것이 조기 saturation의 원인 중 하나다.
- **R32 해결/파일**: `PlacementAnalysis.ScopedCanonicalTextCache`만 수정. 기존 64 MiB/131,072-key 한도와 whitelist/ordering/hash/signature/authority는 그대로 두고 text/literal identity union을 계상한다. ledger/staging/iterator/table slack 보수 추정 128 bytes/identity, authority entry 64, 최초 admission base 256을 포함한다. iterative DFS로 stage한 후 예산 내 완전한 admission만 commit하며 reject 시 ledger/weight/values는 불변이다. scope close에서 ledger를 함께 비운다. R29 append-session이 쓰는 `CanonicalText.retainedWeight`는 변경하지 않는다.
- **검증**: 새 `CanonicalTextDagCacheTest` 6개를 먼저 RED(15개 중 6 intended failures, 기존 9 PASS)로 고정했다. GREEN 15/15, expanded actual frozen JAR+overlay 105/105. exact production SHA `c95470a3de145f2b77fb5fb91cf87e104fb0a50a90b2dda8ae1141bcccbd3f5a` 독립 아키텍처 CLEAR. 같은 host fixture fingerprint/nodes/facts/actions가 그대로이고 cache hits 151,295→1,004,787, entries 10,880→40,593; 실제 stage walk 40,604회/135,726 descriptors이다. **이 수치는 Docker 시간 개선 증거가 아니다.**
- **통합 준비**: R31 final source SHA `18ddf5f25f46943692cd887cafedb157cff40c098ff4a76b5ae78b8abef2fae6`, 독립 테스트 SHA `7f01351bb42fe7ca7b86d8dde508789b25b26fcddf1b00d33d62195053ac7dc6` 및 양쪽 독립 CLEAR를 받고 frozen R30에서 정확히 두 production 파일(R31/R32)만 바꾼 `candidate-r32-pruning-dag-source`를 구성했다. 63-class fresh Maven/package/58-class actual-JAR gate 진행 중.
- **잔여/회귀 위험**: DAG ledger 자체 조회/할당 비용과 높은 live retention이 있으므로 순시간 개선을 가정하지 않는다. budget 경계/부분 reject/공유 및 동일값-상이 identity/20,000-depth/close/nested/thread/type guard로 회귀 검출한다. 최종 채택은 Docker/full matrix/paired control gate에 따른다. 현재 성능 목표 미달이며 설치된 main JAR는 바꾸지 않았다.
- **보관**: 디스크 preflight 한도는 유지했다. 이미 cleanup 완료된 정확한 R26/R29 6개 staging roots를 full SHA 검증 후 Grid3로 보존(컨트롤러는 동일 경로 symlink, so008은 검증된 controller archive receipt)했다. 무관 파일/활성 run/권한/metadata 정리는 하지 않았다. 근거 `r30-staging-preservation/`.

## R32 실측 미개선, R33 회복 및 사용자 요청 기준선 고정 (최적화 중단)

- **문제 정의/관측**: R32 일반 GLM W1 48.316646초/common24.698131초, W3 34.092742초였다. cache hit/할당 감소만으로 공통 시간 개선을 주장할 수 없었다. fresh R30 W3 control30.458447초와 동일 plan을 확인했지만 단일 비교로 원인이나 통계적 회귀를 확정하지 않았다.
- **R33 해결**: `ExactPhysicalSharedSourceEncoding`의 owner별 membership을 header마다 전체 행을 재탐색하는 방식에서 선형 행 순회와 unbound-header fill로 변경했다. FD 검사는 기존 `IntTuple`의 defensive copy를 재사용하여 boxed list를 제거했다. 원래 순서/중복/-1 wildcard/원시 double 값/cap/exception 경계를 유지한다. 테스트 전용 production wrapper는 제거하고 실제 fill helper를 독립 테스트가 직접 검사한다.
- **검증**: production SHA `08a6866d167d3d2ac5392b6ce4f91513d7b77c3293fa4133d7a4ac7ad9fb0017`, 독립 32/32 PASS와 아키텍처 CLEAR. frozen R33은 전체 production manifest상 R32에서 정확히 이 파일만 변경했다. fresh Maven64classes/581tests/0F/0E/기존ignore1, actualJAR533 PASS. JAR `2faab28099fb383d6240f9868a0c870cea14c75defd484300022fffe76b6fa40`.
- **실측**: R33 일반 compile-only W1/global45.865054330초(common23.913064033), W3/global30.732476831초(common16.474496805). 두 실행 모두 cleanup true/runtime0/runtimeProgramConstructed true/lowering mismatch 및 missing0, R30/R32와 worker별 같은 plan fingerprint. 20초는 미달이고 전체896 및 3회 paired control은 미완료다.
- **사용자 최종 결정**: 추가 반복 대신 이전 측정 중 가장 좋은 기준선을 고정하고 커밋·푸시하도록 요청했다. 두 조건의 최악 시간/합계가 가장 작은 R33을 선택했다. W3 단독 최저는 R30이며, 단일 표본의 통계적 우위나 전체 workload 최저를 주장하지 않는다. 명시적 중단 요청에 따라 native 20초 goal은 paused이며 complete로 표시하지 않는다.
- **제외/보존**: R34 binding-only product context는 설계 단계에서 멈췄고 소스 변경이 없다. R35 UTF-8 writer 변경은 13tests PASS/정적 CLEAR지만 Docker 미측정이므로 main에서 제외했다. 덮어쓰기 전 tracked patch/untracked files, R35 source/test를 `final-r33-pin/`에 보존했다. main production/test 전체를 frozen R33과 byte-for-byte 대조했고, 빌드는 원본 증거를 훼손하지 않는 별도 target 사본으로 고정했다.
- **원칙/잔여 위험**: runtime fallback·후보 임의 축소·privacy/TR-TW/recompile/cap/timer 변경 없음. 사용자 요청에 따른 기준선 고정은 전체 성능 qualification의 대체가 아니다. 알려진 전체 프로젝트의 기존 cap/fixture 실패도 그대로 명시한다. 자세한 비교/재현은 `FEDPLANNER_PINNED_BASELINE_2026-09-30_KO.md` 및 동명 JSON에 기록했다.
