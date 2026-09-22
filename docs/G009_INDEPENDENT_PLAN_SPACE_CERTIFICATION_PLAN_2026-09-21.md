# 전체 워크로드 search-space 건전성·완전성 검증 계획

- 날짜: 2026-09-21. 이 문서는 계획 계약이다. 구현 및 인증의 실제 판정은 [단일 최종 보고서](G009_INDEPENDENT_PLAN_SPACE_CERTIFICATION_FINAL_REPORT_2026-09-21.md)를 따른다.
- 검토 대상: `/home/mchoi/systemds-g009-integration`, 작성 시 HEAD `fe000959c48ffa1172399e49124d082fe42d0c6d`.
- 목적: 모든 등록 워크로드의 명시된 유한 조건에서 feasible plan 누락과 illegal plan 추가를 동시에 검출하고, 이후 변경 때 같은 검증을 재실행할 수 있게 한다.
- 완료 산출물: 독립 명세·oracle, 전체 대상 manifest, 양방향 집합 비교기, 재개 가능한 병렬 runner, 인증 bundle, CI 회귀 gate.
- 이번 문서의 구현 경로·CLI는 **제안**이다. 아래 “현재 근거”와 구분한다. 새로운 solver/라이브러리 의존성 도입은 기본안에 포함하지 않는다.

## 1. 현재 근거와 재사용 경계

SystemDS 경로는 저장소 상대 경로다. 외부 harness는 절대 경로로 표시한다.

| 근거 | 확인한 사실 | 검증 설계에 주는 의미 |
|---|---|---|
| `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java:134,312` | 공통 builder가 OracleFacade 및 closure를 사용 | 독립 정답을 이 builder의 결과로 만들면 생성 전 누락을 놓침 |
| `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java:173` | Exact의 도메인도 analysis의 candidate facts에서 생성 | Exact와 새 공간의 일치만으로 충분하지 않음 |
| `/home/mchoi/cofee-evaluation/calibration/java/WorkloadSpaceCensus.java:27,83` | production variables/hardFactors의 encoded assignments를 열거 | 탐색 구현 비교에는 유용하지만 독립적인 물리 계획 universe는 아님 |
| `/home/mchoi/cofee-evaluation/calibration/run_workload_census.py:192,194,247` | capture complete와 exactEnumerationComplete가 별개이며 exit success는 capture 완료 기준 | 기존 성공 상태를 완전성 PASS로 재사용하지 않음 |
| `src/test/java/org/apache/sysds/hops/fedplanner/placement/ProductionDecodedPlanSpaceCompletenessTest.java:79,99,116,127,138` | finite support 1,344개 조합에서 action-free DIRECT의 literal 8-plan 집합과 삭제 변이 검사 | 좋은 시작점이나 전체 action/authority/workload 증명으로 확대 해석하지 않음 |
| `src/test/java/org/apache/sysds/test/component/federated/placement/oracle/builder/BuilderOracle.java:35` 및 `.../shadow/NeutralPlacementGraphShadowDifferentialTest.java:44` | test-owned oracle와 production differential 기반 존재 | fixture·어댑터 재사용 가능; 새 원시 의미론과의 독립성은 다시 확인 |
| `src/test/java/org/apache/sysds/test/component/federated/placement/oracle/selector/ExactSelectorOracle.java:221,264` | explicit domain 탐색 후 best assignment를 반환 | 전체 집합 출력 경로가 별도로 필요 |
| `src/test/java/org/apache/sysds/test/component/federated/placement/oracle/independence/OracleIndependenceContractTest.java:57` | 현재 금지 패턴은 모든 production 의미론 의존을 포괄하지 않음 | package 이름 검사만으로 독립성을 선언하지 않음 |
| `docs/PLAN_SPACE_AUDIT_2026-09-18.md:5,11,12,27` | 기존 감사는 56 transformation, 48 rule family와 미완료 proof를 기록; support AND/OR 명시 | 현재 revision의 등록 목록과 재대조하고 누락 없는 rule/변환 ledger를 작성 |
| `docs/G009_SELECTOR_INDEPENDENT_SEARCH_SPACE_EXECUTION_REPORT_2026-09-21.md:80` | 기존 실행의 전체 공간·quotient·일부 pruning 검증이 부분 또는 미완료 | 이 보고서의 과거 통과를 이번 인증으로 대체하지 않음 |

## 2. 무엇을 “모든 feasible plan”이라고 할 것인가

### 2.1 대상 고정

검증 cell `I`는 다음을 포함한다.

- workload DML 및 transitive function/import closure, compiler options/version, candidate 생성 **이전** compiled HOP/CFG snapshot.
- shape/type/scalar constants, 데이터·metadata hash, 실제 FederationMap의 worker/range/FType와 source identity.
- privacy·허용 release policy, compile/recompile 문맥, callsite/value version, reachability 조건.
- runtime의 물리 연산·전송·재배치 계약과 실제 anchor에서 **독립적으로 유도한** 유한 placement universe. 입력 layout뿐 아니라 연산·재배치가 만드는 derived geometry 및 anchor closure를 포함한다.
- semantic spec·oracle·독립 decoder/normalizer version.

임의의 데이터 크기·무한 loop trace·임의 worker 좌표·불필요한 무한 왕복 전송까지 포함하면 유한 전수 검증이 불가능하다. 따라서 **각 frozen cell과 명시된 물리 계획 문법 안에서 전체**를 인증한다. 이 범위가 실제 제품의 지원 계약과 맞는지 먼저 검토하며, 구현이 만들 수 있는 대안만 보고 문법을 좁히지 않는다. 반복 전송 제거/대체에 의한 유한화도 정당화가 없으면 채택하지 않는다.

각 primitive domain의 범위·derived-layout closure·생략한 action·최대 길이에 completeness 근거를 붙인다. production에 없는 대안을 제외하는 bound는 금지한다. bound의 충분성이나 closure 종료·포괄성을 입증하지 못하면 임의 cap으로 전체를 정의하지 않고 해당 cell을 `UNKNOWN`으로 둔다.

동적 shape/branch/recompile은 선언된 유한 문맥·조건별로 검사한다. 단일 실행에서 관측한 경로만으로 모든 경로를 인증하지 않는다. 모든 입력값에 대한 명세를 주장하려면 별도의 귀납적 invariant·상징적 조건 증명이 필요하다. 이번 유한 인증 밖의 문맥 및 미확인 metadata는 `UNKNOWN`으로 남기고 실패/불법으로 치환하지 않는다.

### 2.2 전체 workload inventory

단일 과거 목록을 전체로 간주하지 않고 아래를 합집합·대조한다.

1. 현행 launcher registry 및 실제 DML/function/template 목록.
2. `/home/mchoi/cofee-evaluation/planning_study/native/protocol-w1.json:63,117,129`의 14 workload·worker 1/3/5/7·네 network snapshot.
3. `/home/mchoi/cofee-evaluation/config/SOURCE_STAGE_CONTRACT.json:5,22,40,46`의 ML10 runtime 조건.
4. `docs/G009_FEDALL_HEURISTIC_STREAMING_SINGLE_PASS_DESIGN_2026-09-21.md:179` 부근의 ML10+P1/P2+SliceLine2 최근 범위.
5. `/home/mchoi/COFEE-Experiment/experiments/parameters.sh:81`과 `experiments/code/exp/`의 LM/FNN/CNN/P2_FFN 등 기존 workload.

이 목록들은 동일하지 않다. 예를 들어 planning snapshot의 PCA shape(`/home/mchoi/cofee-evaluation/planning_study/native/context-w1.json:13`)와 runtime 계약의 PCA shape를 workload 이름만으로 합치지 않는다. ALSCG/LM/GMM 변형, SliceLine dataset도 별도 ID로 관리한다.

각 발견 항목은 `IN_SCOPE / HISTORICAL / UNSUPPORTED / EXCLUDED` 및 근거를 가진다. 최근 작은 workload 14개나 56개 planner run을 임의로 전체 분모로 삼지 않는다. 전체 요청에 해당하는 항목의 미지원·제외는 미완료로 드러내고, 자동 축소하지 않는다. PUBLIC-only 테스트의 기존 ignore 정책도 숨기지 않고 제외 ledger에 기록하며 그 상태로 PUBLIC까지 인증됐다고 쓰지 않는다. protected 입력과 P2의 한정된 public metadata release는 구분한다.

네트워크 비용만 달라지고 feasibility 조건이 같다는 것이 확인되면 semantic cell을 공유할 수 있다. 메모리/worker/topology/정책이 legality에 영향을 주면 별도 cell이다. 재사용 equivalence의 근거와 원래 matrix cell의 매핑을 남긴다.

## 3. 세 가지 집합과 양방향 검사

`P(I)` = 새 production 표현이 **정책 선택 전** 나타내는 전체 joint plan 집합.

`E(I)` = 기존 exhaustive로 열거한 집합. 최적해만 반환하는 모드는 집합 검증에 사용하지 않는다.

`U(I)` = 독립 원시 선택지의 Cartesian product, `Legal_spec(p,I)` = 별도 legality 판정.

`R(I) = { p ∈ U(I) | Legal_spec(p,I) = LEGAL }`.

필수 계약:

```
missing = R(I) \ P(I) = ∅       # completeness
extra   = P(I) \ R(I) = ∅       # soundness
unknown_legality = 0
```

P↔E와 E↔R도 대조하되 다수결로 정답을 정하지 않는다. E가 틀리면 witness와 함께 기존 exhaustive 결함으로 분리한다. E를 고치거나 원인이 설명될 때까지 differential gate는 실패다. 같은 cardinality·최적 비용·winner·해시만 일치하는 것은 집합 일치가 아니다.

E의 오류가 설명되어도 자동 면제하지 않는다. `P_R_PASS`와 `E_KNOWN_DEFECT`를 따로 표시하고, E 수정 후 동일 명세에서 세 집합이 일치하기 전에는 **전체 인증은 non-PASS**다. 최적화 목적에 따른 E의 축소는 공통 공간 비교 모드에서 해제해야 한다.

**검사 경계:** builder publication → 각 selector 입력 → selector 출력 → lowering된 실제 plan. decode 중 oracle로 불법 후보를 미리 지워 P를 정답처럼 만들지 않는다. production 표현의 명시적 support 관계만 해석하고, 별도 checker로 legality를 판정한다. 공개된 raw row는 legal completion witness, 조건부 support, 거부 이유를 전부 분류한다. 개별 row가 조건부라는 이유만으로 불법이라 단정하지 않되, 아무 completion도 없는 row나 selector가 support 없이 선택 가능한 row는 결함으로 보고한다.

### 계획 동일성

독립 schema는 opcode/occurrence/value version/call·recompile context, ordered input occurrence, exec/output/FType, worker/range geometry, source/anchor/authority, relocation·materialization·공유/억제 action 및 producer-consumer 관계를 포함한다.

한 support clause 내부는 AND, clause 사이 대안은 OR이며 native cycle은 실제 source에 근거해야 한다. 동일 물리 plan의 여러 증명 인코딩은 명시된 등가 관계에서만 합친다. authority/action/binding이 다른 물리 plan을 같은 결과값·같은 비용이라는 이유로 합치지 않는다. OR witness encoding coverage도 별도 relation 검사로 유지한다.

production canonicalizer/identity key를 정답 normalizer로 사용하지 않는다. hash는 인덱스와 무결성 확인에 사용하고 동일 hash bucket의 canonical bytes를 비교한다. 상수 hash 주입과 의도적인 구분 필드 삭제 테스트로 충돌·과도한 병합을 검출한다. 안정 ID는 임의 runtime object ID 대신 구조적 occurrence와 명시적인 source 매핑을 사용한다.

## 4. 더 원초적인 독립 oracle

### 4.1 입력과 독립성

`NeutralPlacementGraphBuilder`와 그 occurrence/closure 정제 이전의 compiler HOP/CFG에서 후보와 무관한 직렬화 snapshot을 받는다. raw source/function/callsite 목록과 snapshot을 대조하여 snapshot에서 노드·입력이 사라진 경우도 검출한다. 공유 compiler 자체는 신뢰 경계로 명시하고, 손으로 작성한 IR fixture 및 작은 원본 프로그램 대조로 snapshot adapter를 검증한다. 이것만으로 compiler 전체 정확성을 주장하지 않는다.

oracle core는 별도 test package와 plain DTO만 사용한다. `AVAILABLE` facts, production receipt/domain, `feasibleVariants`, `OracleFacade`, `RulesCore/Rulesets`, `PlacementAnalysis`, Exact hardFactors, selector, production reachability/closure/canonicalization을 정답 생성·legality에 사용하지 않는다. production을 호출하는 adapter는 core와 분리한다. 허용 의존 목록, 직접/전이 참조 검사 및 리뷰로 경계를 고정한다.

### 4.2 독립 명세

runtime instruction dispatch와 실제 operation semantics, privacy 계약을 근거로 versioned `legality-spec`을 작성한다. production planner rule을 그대로 복사하지 않는다. 기존 48 family/56 transformation ledger는 조사 색인으로 쓰고 현 revision 등록 목록과 자동 대조한다.

명세의 각 항목은 `rule_id, operation/shape 조건, 입력·출력 배치, 필요한 행동, global constraint, 근거 소스, positive/negative fixture, UNKNOWN 조건`을 갖는다. 비용이 비싸다는 이유와 selector preference는 legality가 아니다.

각 cell의 모든 raw assignment에 등장하는 opcode/shape/FType/partition/capability tuple을 실제 runtime branch/axis 목록에 연결한다. 판정 receipt에는 적용한 versioned rule ID와 이유를 기록한다. 규칙 미매칭·상충·runtime 분기 미지원 명세는 기본 LEGAL/ILLEGAL로 처리하지 않고 `UNKNOWN`으로 보고한다. family마다 테스트 하나가 있다는 이유로 tuple별 coverage를 대신하지 않는다. shape 구간을 묶을 때는 경계와 구간 전체에 규칙이 적용되는 근거를 명시한다.

필수 규칙: FED input·output 지원, 실제 anchor/geometry, relocation과 privacy, TRead/TWrite의 `<CP,LOUT>` 또는 `<FED,FOUT>`, recompile CP→FOUT 금지, 함수 다중 caller/return, transient reaching definition, AND/OR support, shared action/authority, grounded native cycle. runtime fallback·암묵적 보정은 성공으로 인정하지 않는다. UNKNOWN_METADATA를 단순 불법 처리하지 않는다.

### 4.3 단순 기준 열거기와 독립 checker

작은 입력에서는 최적화 없이 원시 enum의 **전체 Cartesian product**를 열거하고 완성된 assignment에서만 legality를 검사한다. 순회는 정수 mixed-radix index로 구현하여 range별 원소 수와 전체 곱을 검산한다. 생산 pruning·component 분리·memoization은 사용하지 않는다. 합법, 불법, 미확인 개수 합이 원시 product와 정확히 같아야 한다.

확장용 빠른 열거기는 이 기준기와 다른 DFS/prefix 구현으로 만든다. partial pruning은 작은 모든 prefix에서 completion 집합과 대조해 안전성이 확인된 규칙에만 허용한다. reference가 해를 생성할 때의 판정과 결과 witness를 확인하는 checker를 분리한다. 두 구현이 같은 명세를 오해할 위험은 수기 정답·runtime capability test·mutation으로 보완하고 신뢰 가정에 남긴다.

## 5. oracle 자체 및 end-to-end 검증

| 층 | 입력·검사 | 완료 기준 |
|---|---|---|
| 수기 정답 | 0개/1개/여러 feasible plan의 작은 literal universe | R와 수기 전체 집합 양방향 일치 |
| 연산 legality | 각 대상 runtime 분기와 shape/FType/worker 경계의 positive/negative fixture | 각 규칙에 지원·거부 증거 연결, 미확인 분기 0 또는 해당 cell UNKNOWN |
| 전역 조합 | chain, diamond/shared producer, 반복 입력, alias, function 다중 caller/return, loop/recompile, branch, native cycle | 결합 집합 정확 일치; local 후보 집합 비교만으로 대체하지 않음 |
| action/authority | 다른 anchor/range, 공유 relocation, suppression, 혼합 privacy, stale authority | 서로 다른 plan 보존; 불법 조합 모두 거부 |
| mutation | 대안 삭제, 불법 대안 삽입, AND→OR, binding 교환, authority 재사용, cycle grounding 삭제, canonical key 필드 삭제 | 사전 지정된 비동등 mutant 모두 검출; 누락/불법 두 방향 witness 생성 |
| metamorphic | node ID·순회 순서 변경, 비용 변경, selector 호출 순서 교환 | 구조적 rename에 따라 같은 집합; legality 영향 없는 비용 변경으로 공통 공간 불변 |
| 변환별 audit | generation/closure/quotient/index 변경 전후 | 정확성-preserving 변환은 집합 보존; 정당한 refinement는 증거 있는 illegal만 제거 |
| lowering/runtime | fixture의 모든 작은 plan, 큰 cell의 각 capability/경계 대표와 발견 counterexample | 요청한 plan을 강제 실행하고 실제 trace·데이터 이동·수치 결과 대조; fallback 0 |

대표 runtime replay는 **그 표본의 실행 증거**다. 전체 큰 plan의 runtime 실행 증명이라고 발표하지 않는다. 수치 비교는 연산 계약에 맞는 tolerance/비결정성을 명시하고 privacy는 결과값 일치만이 아니라 이동 trace로 확인한다. 모든 실행 증거는 repository의 `run_LAN_docker.sh` 경로로 수집한다. 단위 수준 hermetic enumeration은 runtime workload 실행과 구분한다. 공식 harness 미지원은 먼저 registry/generator/validator 경로를 연결해야 하는 선행 작업이다.

발견 결함은 최소 DML/IR, 조건, missing/extra plan, 위반 rule, source hashes, 재현 명령을 bundle로 남기고 회귀 fixture에 편입한다. minimizer는 차이를 유지하는지 매 단계 재확인한다. production 변경 전에 failing regression을 고정한다.

## 6. 큰 공간: 병렬화와 정확한 완료 증거

1. 우선 workload/semantic cell 단위로 분산한다. 그 안에서는 서로 겹치지 않는 mixed-radix range 또는 exhaustive assignment prefix로 나눈다.
2. coordinator가 고정 `tasks.json`을 생성한다. task마다 cell/domain hash, range/prefix, 예상 raw 수, schema/oracle version을 기록한다. independent reducer가 합집합의 전체 덮음·교집합 없음·BigInteger 합계를 검산한다.
3. shard는 canonical plan의 정렬된 압축 chunk를 stream으로 기록하고 외부 정렬·merge join으로 양방향 차집합을 계산한다. 큰 집합 전체를 RAM에 올리지 않는다. raw illegal assignment 전체 저장 대신 rule별 개수와 재현 가능한 range 및 제한된 witness를 저장한다.
4. graph component 분리는 모든 경계 변수·AND/OR support·action/anchor/authority·transient/function/native SCC·privacy 결합이 보존된 경우만 허용한다. separator의 **모든** assignment에 대한 정확한 relation과 join을 보존한다. “각 component 통과”만으로 global PASS를 내리지 않는다.
5. factorized representation을 쓸 경우 독립 기준기와 작은 입력 전수 대조, decode/join의 soundness·completeness 논거, 전체 경계 assignment coverage를 함께 제공한다. 이 근거가 없으면 전체 prefix 분할로 돌아간다. SAT/BDD 결과나 solver의 단순 UNSAT 문자열을 무검증 대체 증거로 쓰지 않는다.
6. timeout/OOM/cap/interruption은 frontier를 저장한 `INCOMPLETE`; infeasible 아님. 정적 관계 전수 검증이 자원상 끝나지 않으면 해당 큰 cell은 인증 미완료다. sampling·작은 shape 축소는 추가 증거일 뿐 대체 완료 조건이 아니다.

병렬도는 CPU·peak RAM·disk I/O 예산으로 제한한다. 별도 output/container namespace와 필요시 포트 범위를 사용하고 공유 benchmark lock을 우회하지 않는다. remote server 자원 권한이 확보되지 않았다면 로컬 큐·artifact까지 준비하고 원격 배치를 별도 단계로 둔다. 성능 측정과 verification throughput을 섞지 않는다.

## 7. 저장·재사용·재개 계약

현재 root filesystem 여유는 약 0.58GB다. 대형 실행 전 `--artifact-root`의 실제 여유·quota·보존 여유를 검사한다. 실행 단계에서 충분한 기존 volume을 확인해 선택하고 없으면 storage blocker로 기록한다. 사용자 기존 artifact를 자동 삭제하지 않는다. 작은 pilot으로 bytes/plan·RAM·처리율을 추정하여 전체 비용과 shard 크기를 산정한다.

```
artifact-root/<spec-version>/<cell-content-hash>/
  input-manifest.json       # source/import/IR/data/metadata/privacy/FederationMap
  reference/<oracle-hash>/  # independent primitive universe, tasks, chunks
  candidate/<build-hash>/   # production raw relation, decoded chunks
  exhaustive/<tool-hash>/   # 기존 E; complete 여부 별도
  comparison/              # missing, extra, unknown, minimal counterexamples
  certificate.json         # coverage·checksum·completion·재현 명령
```

- Reference key: primitive schema + spec/core/checker/normalizer hash + compiler snapshot provenance + 모든 feasibility 입력. runtime semantics/spec 영향 변경은 reference를 무효화한다.
- Candidate key: reference 입력 identity + production 전체 source tree/dirty diff/untracked 필요한 파일 + JAR/compiler/options/adapter/decoder hash. HEAD만으로 cache hit를 판정하지 않는다.
- 비용만 변경하고 legality가 동일함을 확인한 경우 R을 재사용하고 P와 selector 검증을 다시 한다. compiler snapshot 또는 rule이 바뀌면 영향 범위를 증명한 cache만 재사용한다. 영향 판단이 불명확하면 보수적으로 무효화한다.
- 임시 파일 → checksum·개수 검증 → atomic rename → 완료 marker 순서로 publish한다. 실패 shard를 완료 chunk로 채택하지 않는다. 재시도는 idempotent하고 중복 publish는 한 번만 집계한다.
- checkpoint는 완료한 범위·미처리 frontier·spec binding을 포함한다. 이전 결과를 단순 append하여 누락·중복이 생기지 않도록 한다.
- count/root hash는 진단·무결성 증거다. 정확한 set 비교는 canonical content 또는 검증된 lossless relation을 사용한다. hash-only 성공은 허용하지 않는다.
- certificate가 참조하는 reference/chunk와 counterexample은 보존한다. 보존 정책을 적용할 때 재검증 불가능해지는 artifact 삭제는 명시적으로 관리한다.
- cache hit/cold run, 중간 kill/resume, 누락 shard, 손상 chunk, 잘못된 source/spec hash, constant-hash 충돌을 실제 회귀 테스트로 검사한다.

기존 `run_workload_census.py:38,156,211,241`의 content binding·atomic 저장·병렬 패턴은 재사용 후보다. hardcoded so008 경로, 제한된 ML10 범위, capture 성공 판정은 그대로 복사하지 않는다. `planning_study/native/run_sevenway.py:391,398`의 lock 및 resume 계약도 보존한다.

## 8. 실행 단계와 파일 책임

| 단계 | 구현/산출물 (신규 경로는 제안) | 통과 후 다음 단계 |
|---|---|---|
| S0 범위 동결 | `docs/PLAN_SPACE_CERTIFICATION_SCOPE.md`, `src/test/resources/fedplanner/plan-space/workloads.json`; 실제 source tree/JAR/harness hash, storage preflight | 발견 workload 누락 0, 입력·policy·dynamic scope·제외 사유 확정 |
| S1 명세·identity | `.../oracle/semantic/` plain DTO/spec/checker, rule/transform ledger; 기존 `OracleIndependenceContractTest` 강화 | literal 정답·identity 충돌·core dependency gate 통과 |
| S2 원초적 기준기 | `.../oracle/semantic/PrimitivePlanEnumerator`, `JointPlanLegalityChecker`; 작은 수기 fixture | no-prune 전수, positive/negative, 사전 지정 mutation 전부 통과 |
| S3 production/E 연결 | `.../placement/shadow/` adapter와 decoded set 비교, 기존 completeness tests 확장; census exporter의 full-set 모드 | publication/selector 경계에서 P↔R, P↔E, E↔R; 최소 반례 재현 |
| S4 병렬·저장 | 외부 `cofee-evaluation/calibration/plan_space_verify.py` 및 test; 공식 launcher capture 연결 | 1-worker=N-worker 결과 일치, kill/resume/corruption/cache invalidation 검사 통과 |
| S5 전체 인증 | frozen 전체 manifest 실행, rule 미지원 해결, counterexample 수정 후 관련·전체 회귀 | 모든 in-scope cell 완료; missing/extra/unknown=0; runtime 대표 증거 구분 |
| S6 지속 gate | fast CI, 전체 certification job, report/check-certificate 명령과 운영 문서 | 동일 입력 재실행·캐시 재사용·신규 workload 미등록 실패를 한 명령으로 확인 |

S1 명세 작성과 runtime 근거 검토는 다른 검토자가 확인한다. S2 이후 독립 semantic oracle, production adapter, runner를 명확한 소유 파일로 분리해 병렬 구현할 수 있다. core와 production adapter를 한 구현에 합쳐 독립성을 잃지 않는다. 마지막 통합 검증 책임은 leader가 가진다.

각 생산 코드 수정은 targeted regression → 관련 Maven tests/build 및 적용 가능한 lint/static checks 순으로 검증한다. 전체 기존 suite 실패와 이번 결함을 구분하며 기존 실패를 이번 통과로 숨기지 않는다. 문서 계획 단계에서는 테스트를 실행했다고 주장하지 않는다.

## 9. 지속 검증 명령 및 판정 계약

다음 CLI는 S4/S6에서 구현할 인터페이스 제안이다.

```
python calibration/plan_space_verify.py inventory --source SOURCE --output workloads.json
python calibration/plan_space_verify.py verify --manifest workloads.json --suite tiny --artifact-root ARTIFACTS
python calibration/plan_space_verify.py verify --manifest workloads.json --suite full --jobs N --resume --artifact-root ARTIFACTS
python calibration/plan_space_verify.py replay --counterexample WITNESS --artifact-root ARTIFACTS
python calibration/plan_space_verify.py check-certificate --certificate CERTIFICATE
```

- PR gate: 독립성 검사, literal/mutation, 작은 전수 fixture, 영향받은 고정 cell, 기존 regressions. **bounded PASS**라고 표시한다.
- full gate: 새 release 또는 공통 builder/rules/runtime/privacy/identity/closure 변경 시 전체 frozen corpus 인증. full gate 미실시 시 이전 source의 certificate를 현재 source에 적용하지 않는다.
- inventory gate: workload 추가, opcode/family 추가, 입력 signature·spec 변경이 발견됐는데 검증 등록이 없으면 실패한다.
- report는 cell별 raw/decoded/reference 수, missing/extra 수, UNKNOWN, shard 완료 비율, cache 출처, 소요 시간/peak RAM/storage를 제공한다.
- 프로세스 return code와 결과 receipt를 모두 검사한다. `capture_complete`, `enumeration_complete`, `comparison_complete`, `runtime_replay_pass`를 분리한다.
- `PASS`: 해당 범위 모든 shard·비교·필수 rule 확인 완료. `FAIL`: 구체적 counterexample/무결성/범위 위반. `INCOMPLETE`: timeout/OOM/미실행. `UNKNOWN`: 의미론·metadata·dynamic scope 미결. 어떤 것도 PASS로 합치지 않는다.
- 합법 plan 0개는 작은 의도된 negative fixture에서는 정상이다. 실행 가능해야 하는 workload cell에서는 independent infeasibility witness로 보고하되 workload 정상성 PASS로 세지 않는다.

## 10. 최종 수용 기준

1. 발견된 workload 항목 모두 inventory에 존재하고, 요청 범위의 실행 누락·묵시적 제외 0.
2. 각 frozen cell의 source/IR/input/privacy/FederationMap/compiler/spec/build hash가 재현 가능하게 고정됨.
3. independent reference core가 production 후보·규칙·canonicalizer를 정답 생성에 사용하지 않음; 직접/전이 의존 검사 통과.
4. 현행 대상 rule/전역 제약과 transformation ledger에 근거·positive/negative fixture가 연결됨. 각 cell의 모든 capability tuple에 runtime branch/axis와 적용 rule ID가 대응하고 미매칭·상충·미검증은 UNKNOWN; 임의 domain bound 없음.
5. literal 전체 집합과 단순 Cartesian 기준기가 일치하며 사전 지정 비동등 missing/extra/binding/authority/canonicalization mutant 검출률 100%.
6. 모든 in-scope cell의 `R\P=∅`, `P\R=∅`, `unknown=0`. P↔E↔R 집합 일치까지 전체 PASS로 처리하지 않음. 기존 E 결함이면 `P_R_PASS`와 `E_KNOWN_DEFECT`를 별도 표시하고 전체 인증은 non-PASS; 다수결 통과 없음.
7. publication과 각 selector 입력의 조건부 관계가 보존되고 selector 실행 순서·비용 선호로 공통 공간이 변하지 않음. 정책적 부분집합은 selector 경계 뒤에서만 적용.
8. task manifest 전체 덮음·비중복·모든 shard 완료와 exact set 비교 완료를 machine-check; timeout/cap은 0이어야 full PASS.
9. 1-worker/parallel/cold-cache/warm-cache/kill-resume 결과가 동일하고 손상·stale hash·누락 task를 고의 주입하면 실패.
10. lowering/runtime 대표 검증에서 요청 plan과 trace·수치·privacy 계약 일치, fallback 0; 이를 전수 runtime 증명과 구분해 표기.
11. 공식 Docker harness 및 입력/receipt 경계 일치. 기존 unrelated suite 실패·PUBLIC ignore·dynamic 제외를 빠짐없이 공개.
12. full certificate, 원본 근거와 최소 반례, 한 명령 재검증 경로가 남아 있음. 큰 cell이 미완료이면 **전체 검증 완료로 매듭짓지 않음**.

## 11. 실패 사전 분석과 대응

| 실패 시나리오 | 감지 | 대응 |
|---|---|---|
| 세 도구가 같은 후보 누락 공유 | 정답 생성 dependency gate, 수기 IR/source fixture, production 삭제 mutation | reference 입력 경계를 후보 생성 이전으로 이동; primitive domain 명세 재검토 |
| oracle 명세도 runtime을 잘못 이해 | positive/negative runtime fixture와 최소 witness replay | 명세/생산 rule/runtime 원인을 분리하고 수정 후 reference invalidate |
| decoder가 불법 후보를 먼저 삭제 | raw publication ledger 및 독립 checker | 표현 decode와 legality filter 분리 |
| quotient가 서로 다른 계획 병합 | authority/geometry/action 필드 변이, constant hash | 독립 identity와 lossless decode 계약 수정 |
| component 분리가 전역 coupling 누락 | shared-action/diamond/multi-caller 반례와 separator 검증 | 전체 prefix 분할로 복귀; 근거 없는 축소 금지 |
| 큰 workload가 끝나지 않음 | 미처리 frontier·shard coverage·resource receipt | 정확한 분할/저장 확장; sampling으로 인증 수준을 바꾸지 않음 |
| old cache·부분 기록이 성공으로 재사용 | spec/source/input binding, atomic publish, crash/corruption test | cache miss 또는 실패 처리, 완료 범위만 재개 |
| root disk 고갈·공유 실험 간섭 | 저장 quota·I/O/RAM preflight 및 resource lock | 충분한 artifact volume·독립 실행 슬롯 확보 뒤 시작 |

인증은 명시된 유한 corpus·명세·compiler 신뢰 경계에 대한 강한 재현 가능한 증거다. 미래의 모든 프로그램·입력에 대한 일반 정리는 별도다. 그래도 새 workload/규칙/소스 변경을 자동 감지하고 full gate를 다시 요구하면, 이 작업을 일회성 비교가 아닌 지속적인 정확성 계약으로 사용할 수 있다.
