# Parameterized Builtin / TransformEncode P-L-R 감사 보고서

## 범위와 판정 기준

본 감사의 직접 대상은 `contains`, `replace`, `rexpand`, 그리고 planner graph에서
`_internal::TRANSFORMENCODE` function occurrence로 표현되는 `transformencode`이다.
`removeEmpty`는 현재 HOP rewrite가 이를 `rix`로 낮추므로 별도의 support-path 집합으로
분리하였다. 각 forced target은 ordered input signature, placement state, privacy label,
occurrence hash를 고정하고 다음을 확인한다.

- **P**: privacy propagation 뒤 selector에 공개된 state.
- **L**: forced constraint가 실제 분석에서 적용·만족되었는지.
- **R**: 같은 attempt에서 실제 FED instruction이 성공하고 physical output/FType receipt를 남겼는지.

`coverageComplete=false`이다. forced campaign은 P의 공개 state를 정확히 재생하지만 runtime이
지원할 수 있는 모든 미공개 state의 독립적 완전 열거는 아니다. 따라서 `confirmedMissing=0`은
관측 범위에서의 결론이지 전역적인 R 완전성 증명이 아니다.

## 입력 역할과 상태 범위

DML fixture는 positional HOP input 순서를 보존한다.

- `contains(target, pattern)`: target은 ROW/COL/FULL, pattern은 local scalar.
- `replace(target, pattern, replacement)`: target은 ROW/COL/FULL, 나머지는 local scalar.
- `removeEmpty(target, margin, select, empty.return, ... )`: 현재 rewrite 뒤 `rix` support path로 관측된다.
- `rexpand(target, max, dir, cast, ignore)`: ROW direct FOUT과 CP/BROADCAST/LOUT 대안을 포함한다.
- `transformencode(target, spec, outputs)`: ROW/COL/FULL과 identity/dummycode spec을 포함한다.

입력 matrix metadata는 `private-aggregate`이고 `contains`의 scalar aggregate 결과는
`PRIVATE_AGGREGATE_TO_PUBLIC`로 추적된다. 공개 local reference context는 discovery에는 존재하지만
private manifest에서는 제외하였다.

## 발견하고 수정한 runtime 결함

### Dead metadata transformencode parser

FED transformencode는 trailing thread-count operand가 없다. metadata output이 dead-code eliminated되면
instruction은 6 parts인데 기존 parser가 7 parts만 optional meta flag로 인식하여 boolean literal
`false`를 matrix output으로 해석했다. `MultiReturnParameterizedBuiltinFEDInstruction`의 판정을
6 parts로 고치고 dead metadata canary를 추가했다.

### COL dummycode FederationMap overlap

첫 column partition에서 dummycode가 열을 확장해도 후속 partition 자체에 dummy encoder가 없으면
`updateIndexRanges` callback이 preceding extra-column offset을 적용하지 않았다. 실제 진단에서는
첫 range가 `[0,0)..[20,7)`로 확장된 반면 두 번째가 `[0,3)..[20,6)`에 남아 overlap했다.
후속 non-expanding partition도 preceding extra columns만큼 begin/end column을 이동하도록 수정했다.

### ROW dummycode의 비결정적 recode ID

worker encoder를 RPC completion 순서대로 merge하면 recode ID의 first-occurrence 의미가 실행마다
달라졌다. 실제 실패에서는 category `0`과 `2`가 서로 다른 dummy columns로 교환되었다.
RPC는 먼저 모두 비동기로 발행하되, 응답 encoder를 logical `(rowBegin,colBegin)` range 순서로
merge하도록 수정했다. 이 방식은 concurrency를 유지하면서 결정적 local whole-frame traversal
순서를 복구한다.

## 검증 및 실험 설계

- so001은 사용하지 않았다.
- 최종 campaign은 so003--so006의 네 개 서버에 53개 target을 4-way shard로
  분할했다. 각 서버는 서로 다른 physical source-local `target/`을 사용한다.
- symlink target은 runner가 fail-closed로 거부한다.
- 각 campaign runner는 source-root lock을 획득한 뒤 `mvn -q -DskipTests clean
  test-compile`을 수행하고, post-clean target device/inode와 `Hop.class`, forced-runner
  test class SHA-256 witness를 기록한다. 이 freshness contract가 없는 실행은
  runtime 결과와 무관하게 `INFRA_INVALID`이다.
- `TARGETS_PER_JVM=1`로 forced target 간 JVM state 누수를 제거했다. 또한 manifest discovery도 한 candidate file에 단 하나의 runtime audit context만 허용하도록 `--require-isolated-runtime-context` fail-closed guard를 추가했다.
- identity 및 dummycode ROW/COL을 포함한 8개 fixture를 so003--so006에서
  독립적으로 실행했고 모두 PASS했다.
- primary non-success는 exact target manifest로 isolated retry하며, persistent
  `TARGET_NOT_REACHED`는 semantic occurrence retry로 다시 확인한다.

## 혼합 discovery context 결함과 교정

초기 clean discovery의 `candidate-space-55527.jsonl`은 하나의 Surefire JVM에서 8개
runtime audit context(495 rows)를 함께 실행해 생성했다. 반면 forced replay는 context마다 fresh JVM을
사용했다. 이 비대칭 때문에 세 state는 동일한 context의 fresh replay에서 재발행되지 않았다.

이를 predecessor closure 미고정이나 runtime 미지원으로 해석하지 않고, `fedAllExecutesParameterizedCol`만
독립 JVM에서 다시 discovery했다. isolated candidate file은 75 rows와 정확히 1개 context를 가지며 다음
경계를 산출했다.

- `REXPAND CP/LOUT/-`: 입력이 mixed discovery의 `PRESENT:COL`이 아니라 replay-stable `PRESENT:FULL`.
- 두 `rix`: 입력은 `PRESENT:COL`을 유지하지만 one-partition shape proof
  (`filteredPartitions=1`, `runtimeOutputFType=FULL`)에 따라 출력은 `COL`이 아니라 `FULL`.

이 세 corrected state를 so003--so005의 서로 다른 physical stage에서 병렬 forced replay한 결과는
모두 `SUCCESS`이고 constraint도 모두 만족했다. 따라서 과거 세 `TARGET_NOT_REACHED`는 planner/runtime
failure가 아니라 **mixed-context discovery로 생성된 infrastructure-invalid target**이며 authoritative
manifest에서 대응하는 isolated-discovery target으로 supersede한다. 같은 결함 재발을 막기 위해
`scripts/fedplanner/build_forced_state_manifest.py --require-isolated-runtime-context`가 PID-scoped candidate
file에 둘 이상의 runtime audit context가 있으면 manifest 생성을 거부한다. 회귀 테스트는 mixed context
거부와 single context 허용을 각각 고정한다.

## 최종 결과

최신 authoritative artifact는 다음과 같다.

`audit-results/fed-runtime-parameterized-clean-20260831T235800Z/`

- corrected authoritative manifest: 53 rows / 53 unique target, SHA-256
  `22332c023de9e3dfd9e3dc2730412be3f3482233a8ad6690c4de9418e330fb10`.
- authoritative result union: 53 rows, duplicate/missing/unexpected target `0/0/0`.
- outcomes and constraints: `53/53 SUCCESS`, `53/53 satisfied`, unresolved `0`.
- target-associated runtime-capability receipts: `145/145 SUCCESS`.
- original clean primary에서 유효한 50개 target과 isolated-discovery corrected target 3개를 결합했다.
- final validation: `PASS`; `53 PUBLISHED_LEGAL_EXECUTED`.

교정 전 mixed-context 자료와 세 번의 `TARGET_NOT_REACHED` retry는 역사적 진단 증거로
`aggregate-final/`, `campaign/isolated/`, `campaign/semantic/`에 보존한다. 최종 판정은
`corrected-authoritative/SUMMARY.json`, `FINAL_RESULTS.jsonl`, `CORRECTED_MANIFEST.jsonl`에 있다.
교정 관계와 superseded/replacement target ID는 `corrected-authoritative/DISCOVERY_CORRECTION.json`에
기록했다. isolated discovery 원본과 corrected replay는 각각 `discovery-isolated-col/`과
`campaign/corrected-isolated/`에 보존한다.

전체 focused campaign 집계는 문서화된 기존 `395/4112`에 clean MMFED supplement
`12/59`와 본 parameterized campaign `53/145`를 더한 **460 unique SUCCESS, 4,316 runtime-capability
SUCCESS, unresolved 0**이다. campaign 간 pairwise target-ID overlap은 0이다. 산술과 출처는
`GLOBAL_BASELINE_COMBINATION.json`에 기록했다.

attempt-local P/R join의 전역 R 완전성은 여전히 주장하지 않는다. 본 결과의
`coverageComplete=false`와 `confirmedMissing` 비추론 원칙은 유지된다. 즉 53개 공개 P state의
합법적 실행은 모두 증명했지만, runtime이 지원할 수 있는 모든 미공개 state를 완전 열거한 결과는 아니다.

## 제외된 실행

다음은 authoritative 결과에 합치지 않았다.

1. stale scalar assertion 또는 dummycode 이전 fixture를 사용한 discovery.
2. runtime-audit fail-closed instrumentation을 ordinary semantic fixture와 함께 켠 exploratory run.
3. source receipt가 최신 parser/dummycode/checkpoint fixes와 다른 이전 47-target campaign.
4. 최신 source를 staging했지만 보존된 mtime과 기존 `target/classes`로 인해 Maven
   incremental compile이 stale bytecode를 재사용한 53-target stage2 campaign.
5. mandatory clean-bytecode freshness runner를 반영하기 전 시작한 4-way campaign.
6. 하나의 candidate file에 8개 runtime audit context를 혼합한 최초 discovery에서만 생성된 세 target. 이들은 isolated discovery target으로 교정했으며 최종 manifest에 포함하지 않았다.

이 실행들은 원인 추적용 historical evidence로만 남고 latest exact union에는 포함되지 않는다.
