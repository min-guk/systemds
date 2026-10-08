# 공통 Placement 분석 세부 계측 — LogReg / GLM

## 1. 결론과 범위

이번 변경은 **계측과 병목 확인**이다. 후보/합법성/증명 의미, Oracle, 비용 DP, runtime은 바꾸지 않았다. timeout도 60초 그대로다.

- **LogReg:** direct-native binding이 관측 분석 시간의 **79.3% (inclusive 43.670초)**다. 상세 계측 OFF의 JFR에서도 direct binding이 main sample의 **80.31%**다. production 우선 병목은 **support/proof key hash와 exact membership**이며, 상세 계측에서 크게 보인 signature publication에는 observer 비용이 상당 부분 포함됐다.
- **GLM:** CFG replay의 **joint input environment 정렬·canonical 비교**가 우선 병목이다. 최종 상세 계측에서 exclusive **23.348초 / 43.6%**다.
- 관측된 후보 생성은 **Cartesian 선택 1,501 / 4,338회**, rule-directed execution relation / CP family / MRV **모두 0회**다. 기존 최적화가 코드에 있다는 것과 이 workload의 병목에 적용된다는 것은 다르다.
- layout cache는 이미 **99.12% / 97.36% hit**지만, memoized native publication은 **1.20% / 9.41% hit**다. 이는 publication 재계산 후보를 보여 주지만, **낮은 hit율만으로 우선순위를 정하지 않는다**. 계측 OFF의 hash/membership 증거를 먼저 따른다.
- 공통 분석이 끝나지 않았다. 따라서 **실제 workload의 Local/Global SUFFIX cut·완료 시간·속도 개선율은 미측정**이다. 이전 작은 DP fixture의 cut을 실제 LogReg/GLM 효과로 대체하지 않는다.

아래 시간은 **상세 계측이 켜진 미완료 관측 구간**의 값이다. 생산 실행 완료 시간이나 최적화 전후 속도 비교가 아니다.

## 2. 변경한 계측

| 파일 | 변경 |
|---|---|
| `SearchSpaceMetrics.java` | direct-work event counters, 두 하위 phase, route/opcode별 bounded counters, overflow와 독립적인 route totals, explicit/factorized relocation 분리, live 출력 |
| `PlacementRelationClosure.java` | 실제 fact/emission/seed/proof/input/layout/memoized-publication 사건에 계측 삽입; 기존 predicate·owner identity·cache 동작 보존 |
| `PlacementCandidateGenerator.java` | 실제 선택된 execution-relation / CP-family / MRV / Cartesian 경로 기록; 기존 MRV guard를 그대로 순수 helper로 추출 |
| `SearchSpaceFineGrainedMetricsTest.java` | 계측 off/on 분석 fingerprint·candidate facts parity, direct 경로 실제 호출 수, live 출력, reset/immutable snapshot, retention/overflow, opcode 정규화 |
| `CandidateRouteMetricsTest.java` | 각 route의 실제 호출, 기존 후보 결과 보존과 total/breakdown 정합성 |

생산 기본 경로는 `DMLTranslator.productionSearchSpaceMetrics()`의 **null collector**를 유지한다. 기존 `-Dsysds.fedplanner.liveMetrics=true`에서만 활성화한다. 새로운 의존성/공개 planner 옵션은 없다.

`DIRECT_PROOF_CONSUMPTION`은 seed 결과의 proof 검증·의존성/bookkeeping·publication 소비 loop를 감싼다. **proof 하나마다 타이머를 생성하지 않는다.** `DIRECT_EMISSION_CANONICALIZATION`에는 grounded equality와 새 `CandidateEmissionFact` 정규화가 포함된다. `try/finally`로 continue/예외 시 phase 종료를 보존했다.

opcode table은 최대 64종이다. `TWrite 변수명`, `LiteralOp 값`, `fcall 함수명` 같은 suffix는 **진단 label에서만** 제거한다. semantic identity는 바꾸지 않는다. 고정 크기 route totals는 table overflow와 무관하게 전체 사건 수를 보존한다. 최종 두 capture의 overflow는 모두 0이다.

## 3. 재현 조건과 증거

- repository HEAD: `e468797556e6789100736355ca2463941302221f` + 보존된 기존 dirty 작업 + 위 계측.
- artifact root: `/grid/3/cofee-lm-sweep-mchoi-20260914/placement-analysis-profile-20261008` (이하 `R`).
- 공식 `scripts/fedplanner/run_LAN_docker.sh --function-boundary-compare`만 사용. `run_LAN.sh` 미사용.
- 실제 builtin `multiLogReg(maxi=30,maxii=5)` / `glm(moi=20,mii=5)` 호출, compile-only. 작은 대체 알고리즘이 아니다.
- X **50,000×128 PRIVATE_AGGREGATE**, Y **50,000×1 PUBLIC**, W1 메타데이터. workload의 보호된 입력이 유지된다.
- pinned image `sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434`; CPU quota 4, container 16 GiB, JVM heap 10 GiB, ActiveProcessorCount 4. 동일 cost/seed/input/config/dependency/builtin/probe.
- JFR `settings=profile,duration=55s,maxsize=128m`; supervisor watchdog **60초**. 종료 시 자기 container에만 SIGQUIT, 이후 kill/부재 확인. supervisor wall 약 62초는 진단·정리 포함이며 planning 시간으로 사용하지 않는다.
- 총 8회: `existing` 2, `instrumented` v1 2, `production-jfr` (상세 계측 OFF) 2, `instrumented-final` v2 2. 모두 watchdog 종료, 완료 receipt 없음. 마지막 snapshot 시점은 workload별로 다르다.

재실행은 `R/run_diagnostics.py`가 기록된 공식 진입점을 호출한다. 원본 command/env/log/JFR/cgroup 기록은 각 phase/case 폴더에 있다. snap Docker의 `/grid` bind 제약 때문에 자기 `/dev/shm/snap.docker.placement-profile-OxxDKu` stage를 사용했다. 재현 시 stage archive를 풀어 **새 stage 경로에 맞게 절대경로를 재설정**해야 한다.

```bash
# 저장된 live log 분석 — workload를 재실행하지 않는다.
python3 "$R/evidence/parse_live_metrics.py" instrumented-final
# 실제 진단 실행의 공식 진입점 형태
bash scripts/fedplanner/run_LAN_docker.sh --function-boundary-compare \
  --artifact-root "$STAGE" --cases logreg_local_w1 --variant B --jobs 1
```

## 4. 실제 시간·할당·반복 횟수

최종 live snapshot은 LogReg **55.082초**, GLM **53.521초**다. exclusive phase wall 합이 각각 ANALYSIS inclusive wall과 정확히 일치한다. inclusive 수치는 중첩되므로 더하지 않는다.

| workload / phase | exclusive wall | 비중 | thread CPU | thread allocated bytes |
|---|---:|---:|---:|---:|
| LogReg proof consumption | 15.309 s | 27.8% | 14.185 s | 18.082 GB |
| LogReg proof topology | 6.810 s | 12.4% | 6.313 s | 5.390 GB |
| LogReg proof overlay | 5.630 s | 10.2% | 5.557 s | 3.146 GB |
| LogReg public proof materialization | 5.257 s | 9.5% | 5.151 s | 2.096 GB |
| LogReg emission canonicalization | 5.211 s | 9.5% | 5.049 s | 1.626 GB |
| LogReg dependency pruning | 4.393 s | 8.0% | 4.287 s | 1.578 GB |
| GLM CFG replay | 23.348 s | 43.6% | 23.048 s | 2.123 GB |
| GLM closure replay | 6.462 s | 12.1% | 6.071 s | 3.346 GB |
| GLM proof consumption | 5.732 s | 10.7% | 5.410 s | 5.840 GB |
| GLM public proof materialization | 3.193 s | 6.0% | 3.084 s | 2.356 GB |
| GLM proof topology | 2.601 s | 4.9% | 2.498 s | 2.331 GB |

GB는 10^9 bytes 기준 **현재 thread의 누적 할당량**이며 retained heap/peak memory/객체 개수가 아니다. 각각 총 38.633 / 21.682 GB다. production null-collector 경로의 할당량으로 일반화하지 않는다.

| 사건 수 — 최종 snapshot | LogReg | GLM |
|---|---:|---:|
| direct binding 완료 호출 | 1,002 | 1,925 |
| emission 방문 / 재사용 / 재구성 | 30,948 / 26,531 / 4,417 | 38,465 / 33,143 / 5,322 |
| dedup 전 seed | 312,295 | 172,151 |
| seed relation 요청 / 중복 차단 | 77,607 / 224,178 | 77,973 / 86,506 |
| 소비한 proof | 2,336,957 | 904,502 |
| required input 검사 | 4,177,602 | 1,467,549 |
| 검사한 binding candidate | 6,023,864 | 2,039,556 |
| incomplete proof | 12,769 | 334 |
| layout 검사 / hit | 4,088,536 / 4,052,540 | 1,455,824 / 1,417,378 |
| memoized native publication 요청 / hit | 2,324,188 / 27,859 | 904,168 / 85,110 |
| 생성한 proof graph | 24,443 | 50,455 |
| 생성한 proof alternative | 26,469,481 | 3,663,991 |
| 생성한 proof dependency edge | 43,579,241 | 2,270,101 |
| support prefix / leaf | 454,838 / 410,449 | 406,436 / 355,950 |
| signature serialization / 문자 수 | 2,268,763 / 7,365,444,275 | 1,503,235 / 2,537,392,271 |

주의:
- 모두 **사건/구축 횟수**다. 동일 semantic proof 중복 개수나 live object 개수가 아니다.
- `SEEDS_BEFORE_DEDUP - SEED_RELATIONS_REQUESTED`에는 incompatible-output skip도 있으므로 전부 중복으로 해석하지 않는다.
- publication 수는 `directNativePublication`의 **memoized recompute-native 경로**만 센다. 전체 publication의 hit rate가 아니다.
- `exactContextRepeatedQueries=0`, `exactContextOverflowQueries=0` (두 workload). 관측한 full-context proof graph query를 동일 요청의 무의미한 재실행으로 단정할 근거는 없다.
- topology cache hit는 LogReg 2,085,314 / (2,085,314+7,101), GLM 156,253 / (156,253+7,326)이다. cache를 이미 쓰더라도 lookup/overlay/consumer 비용은 남는다.
- 새 phase로 이전 DIRECT_BINDING self 비용을 분리했으므로 self가 줄었다는 사실은 속도 개선이 아니다.

## 5. 앞선 최적화는 실제 어디에 적용되는가?

| 메커니즘 | 관측 | 해석 |
|---|---|---|
| rule-directed execution relation | 최종 0 / 0회 | 이 구간에서 사용 안 됨 |
| 비열거 CP family | 최종 0 / 0개 | 적용 가능한 좁은 연산/정책 family가 관측되지 않음 |
| 후보 MRV | 최종 0 / 0회 | direct support 탐색 전체에 MRV가 적용되어 있는 것은 아님 |
| Cartesian route | 최종 1,501 / 4,338회 | generator route 선택 수; tuple 수와 다름 |
| 후보 input leaf / Oracle call | 3,055 / 8,684회 | direct proof 소비 수보다 훨씬 작음; 후보 Cartesian만 고쳐 전체 병목이 해소된다고 볼 수 없음 |
| support descriptor reuse | 최종 0 / 0회 | 확장한 descriptor 22,198 / 26,284개; 새 descriptor 내부 immediate support는 실제 열거 |
| explicit clause interning (`factorizedClauses`) | 최종 0 / 0 | Cartesian 구축을 회피하는 product factorization과 별도 counter |
| relocation compact product | 최종 snapshot 0 / 0 | 최종 GLM snapshot은 relocation에 도달하기 전이라 이후 활동 부재를 뜻하지 않음 |
| relocation compact product (v1 GLM 더 늦은 snapshot) | 50 products / logical leaves 188; explicit leaves 236,084 | 제한된 product 압축은 실제 사용되지만 이 관측에서 explicit 작업이 대부분 |

정적 경로 근거:
- `PlacementCandidateGenerator.java:193–195,308–348`: execution relation을 먼저 요청하고 없으면 기존 tuple 경로를 사용한다. relation 자체도 일반 region에서는 tuple을 열거한다.
- `Rulesets.java:863,915,958,1294`: 현재 shape-independent execution relation 선언은 WSLOSS/WCEMM/WSIGMOID/WDIVMM 계열이다. 최종 관측 opcode 목록에는 이들이 없다. 일반 MM/transpose/elementwise를 포괄하는 범용 rule-directed 생성기가 아니다.
- `PlacementCandidateGenerator.java:848–851`: MRV는 protected payload position과 Oracle partial-FED capability가 모두 필요하다. `rix`의 partial capability가 있더라도 해당 조건을 충족해야 한다. 이번 계측은 route 미사용을 확인하지만 **각 호출이 어느 guard에서 탈락했는지까지 측정하지는 않는다**.
- CP family는 scalar WSLOSS/WCEMM, PUBLIC/no protected payload, CP/LOUT/no required-shape 등 좁은 조건이다. 보호 정책을 완화해서 이 경로를 억지로 쓰면 안 된다.
- `NativePlacementContinuity.java:2288,2582–2615`: direct support는 dependency position ordinal 순서로 prefix conflict 검사를 하며 열거한다. 후보 생성 MRV와 다른 탐색이다.
- `PlacementRelationClosure.java:10775` 주변: relocation은 rectangular product 조건을 만족하면 factorized storage, mixed action/owner correlation 등 조건 밖에서는 explicit 경로다. compact 저장과 이후 consumer의 lazy materialization을 구분해야 한다.
- route 수에는 known-bottom 조기 반환이 포함되지 않으며, CP_FAMILY만 generator 호출 대신 발행 family header 개수다.

## 6. 계측 자체의 비용과 JFR 대조

초기 LogReg JFR에서 기존 `SignatureAdmissionObserver`의 rejected-content hash 경로가 main execution sample의 약 **17.9%**를 차지했다. retention이 bounded여도 큰 identity 내용을 hash하는 CPU 비용까지 bounded가 되는 것은 아니다. 따라서 상세 계측 OFF / JFR ON 대조를 추가했다.

JFR는 `ExecutionSample`을 `--stack-depth 64`로 내보내 분석했다. 기존 5-frame 제한은 recording이 아니라 `jfr print` 기본 export 깊이였다. recorded `truncated` trace는 따로 센다. AllocationSample의 weight는 **표본이 대표하는 추정 byte**이며 event 수를 객체 수로 해석하지 않는다. inclusive stack 비중은 중첩되므로 합산하지 않는다.

| 상세 계측 OFF, JFR ON | LogReg | GLM |
|---|---:|---:|
| main ExecutionSample 수 | 3,495 | 3,611 |
| signature admission observer 포함 sample | **0** | **0** |
| 주요 경로 | direct binding **80.31%** | CFG-forward replay **57.93%** |
| 세부 병목 | support-clause hash **22.32%**, `containsExact` **21.43%**, proof-key hash **19.91%** | canonical compare **52.20%**, environment comparator **49.32%** |

LogReg `StringLatin1.hashCode` leaf는 **740/3,495 (21.17%)**이고, 그중 695개가 `CandidateRealizationSupportClause.hashCode`, 694개가 `PlacementProofKey.hashCode`를 포함한다. 이 hash 작업은 observer를 꺼도 남는다. 반면 `directNativePublication` inclusive sample은 상세 계측 ON **29.64%**, OFF **10.10%**이며 `normalizedSignature`는 ON **25.63%**, OFF **6.35%**다. **publication/serialization만 production 최우선 병목이라고 결론내리면 잘못이다.**

GLM에서는 `jointRelations` → `JointValueMapRelations.from` → `PlacementJointInputAnalysis` → environment comparator → canonical text cursor 경로가 대조 실행에서도 유지된다. canonical compare **1,885/3,611 (52.20%)**, `advanceText` exclusive leaf **39.38%**다. 관측 초반의 큰 CFG 비용은 이후 relocation 정렬과 구분해야 한다.

상세 계측 ON 최종 capture의 observer sample은 LogReg **568/3,016 (18.83%)**, GLM **84/3,379 (2.49%)**였다. OFF 대조는 이 collector 비용을 제거하지만 **JFR 자체의 overhead·표본 오차·host noise까지 제거한 무계측 benchmark는 아니다**. 단일 pair, 서로 다른 진행/cutoff이므로 ON/OFF 비중 차이를 속도 개선율로 환산하지 않는다.

독립 분석/재현 parser: `R/evidence/placement_final_analysis.md`, `placement_final_summary.json`, `analyze_final_profiles.py` 및 `*.execution.depth64.json.gz`.

## 7. 검증과 남은 범위

- 최종 `mvn -o -B -Djacoco.skip=true -Dmaven.test.skip=false -Dtest="$(cat "$R/evidence/test-selector.txt")" package`: **40 suites / 346 tests, failures/errors/skipped 0, BUILD SUCCESS**. 정확한 tmpdir/JVM 설정은 `R` 증거에 기록했다. Java build가 typecheck도 수행한다. 전체 저장소 테스트 green 주장은 아니다.
- 기존 pruning numerical/failure/parity와 placement live/attribution/direct frontier/MRV/route를 포함한 선택 suite다.
- 독립 code review **APPROVE**, architecture **CLEAR**. 최초 지적된 publication counter 범위 명칭과 direct-binding off/on parity coverage를 보완했다. opcode label 정규화/total 보존도 재검토했다.
- 최초 단독 테스트의 incubator vector module 누락과 route snapshot 순서에 대한 잘못된 테스트 가정은 수정 후 재실행했다. 실패 로그를 삭제하지 않았다.
- `git diff --check` 및 기존 dirty 작업 보존을 별도로 검증한다.

- 최종 Maven JAR SHA256: `5d52d5ecbf5a5bfac236f18a46089e23423050601b4a5691015fce5f794fee42` (`R/candidate/SystemDS.jar`). JFR-only controls는 보존된 v1 JAR을 사용했다. source 및 classes/deps/builtins/probe/cases seals는 `evidence/final-artifact-manifest.json`에 있다.
- 독립 audit가 final manifest에 남아 있던 **v1 ZIP diff 목록**을 발견했다. 실제 final ZIP diff **182 entries**로 재계산하고 v1 목록을 별도 보존했다. final package는 3개 계측 class family 외에 **기존 solver family 45개도 debug 재컴파일로 byte가 달라졌다**. 따라서 “3개 family만 byte 변경”이라고 주장하지 않는다.
- solver source는 작업 전 SHA256과 동일하다. `javap` 검토에서 두 dyadic 합산 method의 추가 mask local 및 local-slot 이동을 확인했다. v2의 첫 `9007199254740991`은 high accumulator가 아니라 **final mask**이며 실제 high/low는 여전히 0이다. 나머지 바뀐 metadata/constant-pool reference와 구분했다. 새 solver 의미 변경은 없다. 최종 재컴파일본으로 위 346 tests를 실행했다.
- own container 8개 부재를 확인했고 stage 전체 archive `R/perf-stage.tar` (**402,749,440 bytes**, SHA256 `5d1943752200e0cfec075ab7d1822516d258c5103e09656b514df5d69c867eb7`)를 보존했다. container audit와 archive hash는 `R/evidence/`에 있다. 5371개 sealed file을 archive/live stage 양쪽에서 독립 대조한 뒤 **own stage만 정리 완료**했다. 다른 장기 작업은 건드리지 않았다.
- 최종 독립 evidence audit **PASS (bounded scope)**: `R/evidence/independent-verification.md`. 각 launch에는 class-tree 전체 hash가 별도로 기록되지 않았고, 순차 실행·freeze manifests·현재/보존 artifact seals로 provenance를 확인했다. 이후 재측정은 per-run binary-tree hash도 기록하는 것이 더 강한 증거다.

## 8. 다음 개선 우선순위 — 아직 구현하지 않음

1. **GLM environment 정렬·관계 계산:** `PlacementJointInputAnalysis.environmentOrder` 및 `union/bounded/mutableOrderedCopy/nextBlock`의 ordered-set 재사용·삽입 수를 더 분리하고, 불필요한 `TreeSet` 재구축/긴 canonical-text 재비교를 줄인다. 계측 OFF에서도 한 coherent path가 sample의 절반을 차지한다. structural 비교/memoization을 검토하되 정확한 total order·owner identity·loop fixed point를 보존한다.
2. **LogReg immutable support/proof hash와 exact membership:** `CandidateRealizationSupportClause.hashCode`, `PlacementProofKey.hashCode`, `GroundedNativePreparation.containsExact`를 먼저 분리 계측한다. immutable 구조의 construction-time hash 재사용과 key lifetime을 검토한다. 객체 불변성과 equality/hash 계약을 확인하기 전 cache를 추가하지 않는다. publication의 normalized signature 및 진단 observer hashing은 그 다음 별도 대상으로 삼는다.
3. **proof consumer와 support 표현:** 신규 graph query는 관측 full-context에서 서로 다르다. 무조건 cache 용량만 늘리기보다 topology hit 후 overlay/materialization, input binding lookup, product를 explicit하게 푸는 경계를 우선 줄인다. 고유 support만 생성하는지 semantic parity fixture로 검증한다.
4. **실제 연산에 맞는 candidate 최적화 확대:** 현재 쓰이지 않는 quaternary 특화 rule-directed/MRV를 일반 workload 효과로 주장하지 않는다. 대상 opcode의 runtime/shape/privacy 증명을 먼저 정의한다. 관측 후보 leaf 수가 작으므로 앞 두 병목보다 우선하지 않는다.
5. 공통 분석이 정상 완료된 이후에만 **Local/Global LEGACY↔SUFFIX cut 수·준비 scan 비용·DP 시간·전체 planning 시간**을 동일 Docker/receipt 조건에서 측정한다.

모든 후속 최적화의 승인 조건은 계측 off/on 또는 전후의 analysis fingerprint/candidate/support/proof parity, DP objective/lower/witness parity, runtime fallback 0이다. 합법 후보를 편의상 닫거나 timeout만 늘리지 않는다.
