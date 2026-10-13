# Oracle 호출 이전 완전한 요청 identity 중복 제거

## 구현 전 계획과 계약

기준은 독립 workspace의 commit `2cf36e8d80`이다. 사용자가 변경 전후 요청 수·cache·Rules 시간·결과 동등성 측정을 명시적으로 요청했으므로 동일 Docker LogReg를 변경 전/후 각1회 실행한다. 과거1회 결과를 새 기준으로 바꾸어 쓰지 않는다. GLM은 새 workload 실행 대상에 넣지 않고 기존 관련 unit/model 회귀로 확인한다.

- `MemoNativeRules`가 기존 canonical key를 생성하는 유일한 권위다. Request는 실제 contextual clone의 operator selectors, 입력 순서/type/dimension, worker 수, 전체 DiagnosticSnapshot을 캡처한다. AggBinary의 TSMM/map-mult-chain 등 topology selector도 기존 key대로 보존한다.
- Key가 완전하다고 기존에 인정한 cacheable family만 재사용한다. Noncacheable family는 key를 노출하지 않고 매번 평가한다. Shape-only 또는 Hop identity만으로 합치지 않는다.
- Request 준비와 평가를 분리하고 같은 key/snapshot을 양쪽에서 사용한다. Builder는 준비된 key로 결과를 조회한 후 없을 때만 `rules(Request)`를 호출한다. 공용 `rules(Hop,workers,hint)`도 같은 준비/평가 로직에 위임한다.
- Request 준비는 실제 contextual clone을 매번 검사해야 한다. 다만 같은 canonical operator 문자열을 매번 새로 만들 필요는 없으므로, scope-local 문자열 interner와 재사용 StringBuilder로 중앙 key writer를 공유한다. 동적 signature 검사 자체를 생략하지 않는다. Immutable key만 map에 보존하고 mutable HOP은 공유하지 않는다.
- Builder가 이미 만든 fresh view에서 hint를 계산해 `hintAt`이 같은 view를 다시 clone하던 비용을 제거한다. 기존 hint 계산 로직은 하나의 helper로 재사용한다.
- 결과 재사용 이후에도 activation, owner intersection, input-role 일관성, complete/UNKNOWN, occurrence별 diagnostic, privacy/DEFER 처리를 그대로 실행한다. 재사용 cache 수명은 한 build로 제한한다.
- 준비 횟수, 동일 결과 재사용, 실제 Oracle 호출, canonical 문자열 생성/재사용, noncacheable 요청을 분리해 계측한다. Cache hit/miss와 요청수 정의를 바꾸지 않는다.

## 검증 계획

1. 기존 owner/caller/phase/UNKNOWN/privacy 회귀를 동작 기준으로 유지한다. 기준 source의 직전 관련407 tests PASS와 새 baseline Docker snapshot을 보존한다.
2. Prepared/legacy API 결과 동등성, worker/full hint/topology selector 분리, noncacheable 보존, hint snapshot 캡처를 검사한다.
3. 동일 문맥 반복에서 실제 Oracle 요청이 줄고, unknown 진단과 owner별 ordered domain이 같은지 비교한다. 새 회귀는 시간 임계값 대신 방문/요청 counter와 의미 결과를 검사한다.
4. Clean compile, 관련 soundness/회귀, 독립 코드 검토를 통과한 소스만 변경 후 Docker로 동결한다.
5. 두 Docker 실행의 요청/cache/Rules 시간, native rule 수·generation counter·exact objective·선택 계획·수치 출력을 대조한다. 단일 표본의 시간 차이를 일반적인 speedup 보장으로 해석하지 않는다.

## 구현 결과

`MemoNativeRules.RequestFactory.prepare`가 기존 canonical writer로 Request를 준비한다. `MemoRuleBuilder.NativeRequests`는 한 build 동안 `RequestKey → Result`를 보존하고, 없을 때만 `MemoNativeRules.rules(Request)`를 호출한다. 실제 평가도 준비된 key와 shape snapshot을 그대로 사용한다. 기존 공개 `rules(Hop,workers,hint)`는 동일 경로를 사용하며 synchronized 계약도 유지한다.

Key의 equality 구성은 기존과 같다. Operator class/opcode/data·value type/output dimensions/blocksize, ordered input type·dimensions, 기존 family별 selectors, workers, 전체 `ShapeHint.DiagnosticSnapshot`을 사용한다. AggBinary는 실제 contextual clone에서 TSMM/map-mult-chain/FOUT selector를 매번 읽는다. Name/source identity를 새로 key에 넣거나, 기존 cache 불가 family를 임의로 cache 가능으로 확장하지 않았다. 이 identity는 native capability용이다. NNZ와 비용 scalar 의미는 기존 비용 문맥/primitive 경로에서 유지하며, 비용을 native request key로 합치지 않는다.

문자열 interner는 `TreeMap<CharSequence,String>`에 immutable String만 저장한다. 재사용 StringBuilder는 comparator lookup에만 사용한다. Key 작성 규칙은 `appendOperatorKey` 한 곳이며 legacy/prepared 경로가 이를 공유한다. Builder에는 key 구성 로직이 없다.

`MemoShapes.hintForView`는 기존 hint 계산을 추출한 helper다. Builder가 방금 만든 private contextual view에서 hint를 만들어 같은 문맥의 HOP clone을 다시 만들지 않는다. Private HOP은 Oracle에 즉시 전달하고 다른 context와 공유하지 않는다.

결과 재사용 뒤에 실행되는 owner intersection, input-role consistency, `complete &= result.complete()`, occurrence별 coverage diagnostic은 그대로 남겼다. Activation/loop/call-frame 방문과 source-grounded privacy 및 모델의 문맥별 privacy/DEFER 검사도 변경하지 않았다. Request가 UNKNOWN 결과를 포함하면 그 결과를 재사용할 뿐, YES/NO로 바꾸거나 해당 context를 생략하지 않는다.

## 계측의 정확한 의미

| Counter | 의미 |
|---|---|
| `preparedRequests` | Context별 요청 identity 준비 횟수. 중복이라도 정확한 실제 입력 비교는 필요하다. |
| `reusedResults` | Builder에서 동일 full key 결과를 찾아 Oracle 호출을 생략한 횟수. |
| `oracleRequests` | `MemoNativeRules.rules(Request)` 진입 횟수. Cache miss 또는 exact Oracle capability 질의 횟수와 구분한다. |
| `canonicalStringsCreated` | Canonical **operator 문자열**을 새로 생성한 횟수. |
| `canonicalStringsReused` | 같은 operator 문자열을 새로 할당하지 않고 재사용한 횟수. |
| `uncacheableRequests` | 기존 cacheability 계약상 key가 없어 매번 평가하는 요청 수. |

`RequestKey` record와 snapshot은 각 cacheable 문맥에서 여전히 준비한다. 제거한 비용은 중복 native catalog 진입, canonical 문자열 재할당/재해시, 평가 단계의 key 재생성, 두 번째 contextual HOP clone이다. 동적 identity 검사 자체가 없어졌다고 주장하지 않는다. `preparedRequests = reusedResults + oracleRequests`와 `preparedRequests = canonicalStringsCreated + canonicalStringsReused + uncacheableRequests`를 회귀로 확인했다.

## 회귀와 독립 검토

- 수정 전 owner/native23 tests PASS. 기준 `2cf36e8d80`의 기존407 tests 증거도 보존한다.
- Clean Maven main/test compile PASS38.989초. 독립 검토의 synchronized 복구와 문자열 counter 이름 보강 후 관련 소스를 `javac --release17`로 다시 검증했다.
- 신규 Request API/owner/native29 tests PASS. Same-shape TSMM shared identity와 서로 다른 입력, mapmm-chain과 ordinary multiply, worker/full hint/UNKNOWN/blocksize, noncacheable 재평가를 검사했다.
- 최종44 classes/413 tests PASS. 이전 단계의43 classes/407 tests에 새 Request5개와 owner1개를 추가했다. 기존에 실패하던 `TrainingWorkloadOperationCoverageTest`5개는 실행 대상에서 제외했고 미해결 상태를 유지한다. 해당 golden failure가 해결됐다는 뜻은 아니다.
- Python harness45 tests PASS, py_compile 및 git diff --check PASS. 요청 counter는 invocation별로 분리하며 timeout/UNRESOLVED에서도 수집한다.
- 독립 reviewer가 계획/API/최종 diff를 검토했다. Mutable HOP identity 재사용, shape-only key, duplicate UNKNOWN 진단 누락, noncacheable 오병합이 없음을 확인했다.

근거: [`experiments/oracle-request-dedup-20261014`](../experiments/oracle-request-dedup-20261014/), [Java 명령](../experiments/oracle-request-dedup-20261014/final-unit-command.txt), [Java 결과](../experiments/oracle-request-dedup-20261014/final-unit.log), [Python 결과](../experiments/oracle-request-dedup-20261014/harness.log).

## 변경 전후 Docker 1회 측정

기준 `request-dedup-before-01`과 변경 `request-dedup-after-01`은 별도의 frozen engine을 사용한다. 같은 pinned image/W3/4CPU/8GiB/network none 및 입력/설정, case timeout300초, 별도 warm-up 없음 조건이다. 각1회 실행하며 결과를 평균하지 않는다. 원본 workspace는 수정하지 않는다.

두 실행 모두 PASS다. Source-only projection, binary64 primitive와 exact 합, optimizer 알고리즘은 이번 변경 대상이 아니다.

| 항목 | 변경 전 | 변경 후 | 차이 |
|---|---:|---:|---|
| 실제 native catalog 요청 | 8,880 | 1,057 | **7,823회 감소(-88.1%)** |
| Catalog cache hit | 7,823 | 0 | 같은 입력을 Builder에서 먼저 재사용 |
| Catalog cache miss | 1,057 | 1,057 | 실제 새 평가를 누락하지 않음 |
| Rules 시간 | 1.948574090초 | 1.579905977초 | **0.368668113초 감소(-18.9%)** |
| 전체 planning | 40.489338563초 | 39.712663042초 | 0.776675521초 감소(-1.9%) |
| 전체 compilation | 41.832841초 | 40.807782초 | 1.025059초 감소 |
| 실제 실행 | 2.200초 | 2.819초 | 0.619초 증가 |

변경 후에도 `preparedRequests=8,880`이다. Context를 지우지 않고 모두 입력 identity를 확인했다. 그중7,823회는 동일 결과를 재사용했다. `canonicalStringsCreated=77`, `canonicalStringsReused=7,823`, `uncacheableRequests=980`이며 77+7,823+980=8,880이다. 재사용 후 남은1,057회는 cacheable 새 평가77회와 noncacheable 평가980회다. Noncacheable에 완전한 identity 증명이 없다는 기존 정책을 유지했으므로 이980회를 임의로 합치지 않았다.

이 감소는 **NativeRules catalog 진입**의 감소다. 하위 Oracle capability 평가나 합법 후보를 줄인 결과가 아니다. `cache hit=0`을 cache 기능 제거로 해석하지 않는다. 직접 API 호출과 build를 넘어선 기존 cache는 계속 동작하며, 이번 build 안에서 반복 요청을 먼저 제거한 것이다.

두 실행의 결과는 OPTIMAL, native rules920개, search nodes2,933,891개이며 exact objective는 모두 `1301.1526531142989790221751587629165844428591469750244868919253349304199218750`이다. CP/FED 출력8×2의16개 값이 finite/matched이고 최대 절대 오차도1.6653345369377348e-16으로 같다. Definition/supply action 진단도 동일하다.

더 구체적으로 native generation과 cost projection counter 전체가 동일하고, `fed-model.csv`도 byte 단위로 같다(SHA256 `c1afd1422eb05ef396bbc13b48fbd09d5966995de43d9f92147ebb1599d5b848`). Runtime capability audit13행과 conversion-frontier audit20행은 PID를 정규화한 뒤 동일하다. 실행 FED kernel과 선택 공급 action도 같다. 출력 metadata 차이는 생성 timestamp뿐이다.

모든 search counter가 완전히 같은 것은 아니다. `checkCacheHits`는82,050,743→82,050,731(-12), `supportQueries`는38,286,836→38,286,830(-6)이다. 나머지 structural search counter는 같고, 이 미세 차이를 결과나 최적성 변화로 해석할 근거는 없다. Raw 비교에서는 `memoSearchExact=false`를 보존해 차이를 숨기지 않았다.

재현 근거: [비교 JSON](../experiments/oracle-request-dedup-20261014/request-dedup-comparison.json), [변경 전 명령](../experiments/oracle-request-dedup-20261014/request-dedup-before-command.txt), [변경 후 명령](../experiments/oracle-request-dedup-20261014/request-dedup-after-command.txt). Frozen main/test source와 classes의 파일별 hash 일치를 확인했고, 두 실행의 컨테이너는 종료됐다. 각1회 이후 추가 workload 실행은 없다.

이번1회 표본에서 Rules 단계는 빨라졌지만, 실제 실행 시간은 증가했다. 전체 planning의1.9% 감소나 실행 증가를 일반적인 성능 보장·회귀 원인으로 단정하지 않는다. 동시 적용한 사전 요청 재사용, 문자열 재사용, 중복 HOP clone 제거 각각의 시간 기여율도 분리 측정하지 않았다. Optimizer의 별도 병목과 기존 GLM timeout 해결은 이 변경의 성과로 주장하지 않는다.

## 요구사항별 판정

| 요구사항 | 확인 결과 |
|---|---|
| Shape-only dedup 금지, 완전한 native 입력 identity | 기존 canonical equality 및 cacheability 정책 보존; graph-selector/worker/hint 분리 회귀 PASS. |
| Key 로직 단일화 | `MemoNativeRules.appendOperatorKey`와 prepare 경로를 legacy/prepared API가 공유. Builder에 key 구성 코드 없음. |
| Context별 검증 유지 | 8,880문맥 요청 준비 유지; duplicate UNKNOWN 진단, activation/caller/phase, privacy 및 ordered intersection 회귀 PASS. |
| 서로 다른 입력/결과 오병합 방지 | 전체 key equality 사용; noncacheable980회는 개별 평가; TSMM/mapmm 구조 구분 PASS. |
| 요청/cache/시간/결과 비교 | 동일 Docker 변경 전/후 각1회 완료. Cache miss·최적 비용·rule 수·출력 동등. |
| Soundness/regression | 관련 Java413/Python45 PASS 및 독립 reviewer 검토 완료. 기존 golden drift는 별도로 미해결 기록. |
