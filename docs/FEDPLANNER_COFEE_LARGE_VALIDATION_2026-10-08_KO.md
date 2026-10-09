# COFEE-evaluation 대규모 LogReg/GLM 검증

## 상태

최신 완료 v12(`95ed3daeb2`, JAR `3ee4db4e…`)의 전체 최초 planning은 LogReg **402.506716391초**, GLM **137.753315043초**다. 각1회이며 **20초 미달**이다. Numeric/raw-output 비교와 runtime audit mismatch0은 통과했다. LogReg 선택 fingerprint는 이전과 같지만, **GLM 선택 fingerprint는 `ebf0cc31…`에서 `113d2d19…`로 달라져 버전 간 계획 동등성은 미검증/불일치 상태**다. Analysis fingerprint는 같다. 공개 receipt/log에 objective raw bits와 cost fingerprint가 없어 동일 비용이라고 추정하지 않는다. DP conditional conditioning 단독v11 분리 실행과 코드 검토로 원인을 확인한다.

| v12 관측 | LogReg | GLM |
|---|---:|---:|
| 전체 최초 planning | 402.506716391초 | 137.753315043초 |
| Analysis | 319.203979525초 | 93.232초 |
| Optimizer | 69.676초 | 25.568초 |
| coordinator cgroup peak | 9,262,444,544B | 5,275,860,992B |
| 실제 batch graph 재사용 | 24,191 | 51,564 |

실제 재사용과 기존 v10의 certified repeat potential을 구분한다. v12의 durable proposal은 아직 재사용하지 않으며 다음 수정에서 별도로 검증한다. v12에는 최신 main의 component-summary 중복 제거가 포함되지 않는다. 그 병합본v13(`9bfccd8d96`,1,063PASS,독립 review CLEAR)은 별도 봉인·실측한다.

아래는 v10 및 이전 기록이다.

최신 완료 v10(`d59fa583c5`, JAR `b5973fad…`)은 LogReg **440.740278892초**, GLM **140.992530652초**다. 두 workload의 숫자 비교, runtime audit mismatch0, 이전과 같은 plan fingerprint는 통과했다. 그러나 v8의398.232/124.946초보다 느렸으며20초에는 미달했다. Pinned/unpinned native axis gate의 작은 fixture 압축 효과를 실제 workload 개선으로 채택하지 않는다. v10에는 이후 conditional DP 수정이 포함되지 않았다.

실제 LogReg 정상 로그에서 generated graph miss147,551 중92,253건은 같은 fact/emission/witness의 certified support가 resident였다. 이는 재사용 가능성 계측이며 실제 회피한 graph 수가 아니다. 다음 후보는 이 범위의 generated template 재사용과 native representation이 없는 owner의 반복 gate eligibility 검사 생략이다. Root history/returned root binding/source-action authority를 보존하는 테스트와 별도 실측을 수행한다.

아래는 v8 및 이전 완료 기록이다.

이전 완료 v8(`3d8e59378f`, JAR `b502242c…`)의 전체 최초 planning은 LogReg **398.232339626초**, GLM **124.946316443초**다. v7의460.487/236.147초보다 줄었지만 **20초 목표는 미달**이다. 두 workload 모두 숫자 비교 PASS, runtime audit mismatch0, 이전과 동일한 plan fingerprint다. v8은 topology bucket 선형 검사와 sparse DP conditioning을 포함하며, 이후 native axis gate 변경은 포함하지 않는다. 각1회 관측이고 변경별 효과를 분리하거나 반복 분산을 측정하지 않았다.

| v8 완료 측정 | LogReg | GLM |
|---|---:|---:|
| 전체 최초 planning | 398.232339626초 | 124.946316443초 |
| Analysis | 314.122215976초 | 84.310379781초 |
| Physical Model | 4.428272890초 | 4.722888971초 |
| Cost Surface | 6.590263639초 | 8.143038708초 |
| Optimizer | 70.332101081초 | 23.857477980초 |
| coordinator cgroup peak | 9,141,903,360B | 5,134,458,880B |
| worker cgroup peak | 725,753,856B | 769,208,320B |

정확한 단계별 원본은 `evidence/cofee-50k128-v8-validation/candidate-{logreg,glm}-run0/timing-evidence.json`이다. Acceptance evaluator도 각 실행을20초 초과로 거절했다. 검증 조건은 그대로이며 새 profiling/JFR는 실행하지 않았다.

아래는 v7까지의 이전 완료 기록이다.

이전 완료 실측은 v7(`c6be0b6916`, JAR `5172e29a…`)이다. 같은 COFEE50K×128 W1에서 LogReg **460.486971960초**, GLM **236.147365008초**로, v6보다 느렸고 **20초 목표는 여전히 미달**이다. 두 workload 모두 숫자 비교 PASS, runtime audit mismatch0, v5/v6와 동일한 선택 plan fingerprint를 확인했다. 이 v7 봉인본은 d27의 native projection/revision 재사용과 metadata/SCC 개선을 포함하지만, 이후 main에 반영한 689의 topology bucket 선형 검사와 v8의 sparse DP conditioning은 포함하지 않는다. 복합 변경의 각 원인별 속도 기여를 분리 측정하지 않았으므로 Analysis 악화를 downstream SCC 변경 때문이라고 단정하지 않는다.

| v7 완료 측정 | LogReg | GLM |
|---|---:|---:|
| 전체 최초 planning (`receipt.planningFullInitialNanos`) | 460.486971960초 | 236.147365008초 |
| 내부 Analysis | 372.203410278초 | 190.022935095초 |
| Physical Model | 4.464905928초 | 6.317207005초 |
| Cost Surface | 6.894105908초 | 9.871215495초 |
| Optimizer | 73.826497478초 | 25.564320545초 |
| 실제 실행 | 4.633276577초 | 7.012426216초 |
| coordinator cgroup peak | 9,110,831,104B | 5,587,062,784B |
| worker cgroup peak | 746,065,920B | 761,516,032B |

근거는 `evidence/cofee-50k128-v7-validation/candidate-{logreg,glm}-run0/timing-evidence.json`이며 SHA256은 각각 `d22330c3e8beb0f721ea92d13f187d41d4e7b8c17d453c120cd6cc8676490da6`, `a595a6b74894b2c1cd42df02f4f5beeee1022a875139e7619973bebfa5f8eca2`다. 각1회 성공 관측으로 반복 성능 검증은 아니다. 명백하게20초를 초과하므로 같은 느린 엔진을3회 반복해 성공 근거처럼 집계하지 않는다. v8 완료 결과는 문서 상단에 기록했다.

아래는 v6까지의 이전 완료 기록이다.

이전 실측 v6(`9fb272e355`, JAR `f1eedaa2…`)은 동일 COFEE50K×128 W1에서 LogReg **422.957952127초**, GLM **151.734634551초**에 전체 최초 planning을 완료했다. 두 workload의 숫자 비교와 runtime audit(mismatch0)은 통과했고 v5와 선택한 plan fingerprint도 같았다. 하지만 v5의403.786041598초/142.960651865초보다 느렸으며 **20초 목표는 미달**이다. 각 엔진·workload1회 관측이므로 반복 측정의 통계적 성능 개선으로 해석하지 않는다.

| v5 → v6 실제 관측 | LogReg | GLM |
|---|---:|---:|
| 전체 최초 planning (`receipt.planningFullInitialNanos`) | 403.786 → 422.958초 | 142.961 → 151.735초 |
| 내부 Analysis | 316.871 → 334.660초 | 97.883 → 106.183초 |
| Physical Model | 4.296 → 4.236초 | 5.264 → 4.799초 |
| Cost Surface | 6.497 → 6.354초 | 7.830 → 9.744초 |
| Optimizer | 73.494 → 74.496초 | 27.837 → 26.202초 |
| support leaf 실제 방문 | 3,919,362 → 3,072 | 799,642 → 356 |
| Explicit Clause 생성 | 2,534,754 → 2,536,770 | 1,156,839 → 1,157,816 |
| coordinator cgroup peak | 9,286,393,856 → 9,285,378,048B | 5,284,954,112 → 5,192,859,648B |

v6는 variable-length native relation의 생성 전개를 없앴지만, 후속 validation·proof topology·Physical 단계의 전개는 남아 있다. 따라서 생성 leaf 감소를 전체 객체·메모리·시간 감소로 보고하지 않는다. Proof alternatives/edges 지표는 캐시 summary 크기도 포함하므로 새 객체 생성 수 또는 DP join 방문 수가 아니다. 공통 근거: `evidence/cofee-50k128-v6-validation/v6-final-summary.json`, SHA `77721096e2708e165282e0ca3480060ef601057cde22fdf44badb9f4606ceaa9`.

아래는 이전 v5 완료 기록이다. 전체 최초 planning은 최종 receipt의 `planningFullInitialNanos`를 사용하며 내부 `candidateE2E.totalNanos`와 구분한다.

| v5 측정 | LogReg | GLM |
|---|---:|---:|
| 전체 최초 planning (parse→runtime program) | 403.786초 | 142.961초 |
| 내부 Analysis | 316.871초 | 97.883초 |
| Physical Model | 4.296초 | 5.264초 |
| Cost Surface | 6.497초 | 7.830초 |
| Optimizer | 73.494초 | 27.837초 |
| 변환 | 1.093초 | 1.764초 |
| 실제 실행 | 4.455초 | 6.782초 |
| coordinator cgroup peak | 9,286,393,856B | 5,284,954,112B |
| 숫자 비교 / audit | PASS / mismatch0 | PASS / mismatch0 |

단계별 값은 내부 계측이므로 합계와 전체 최초 planning 구간이 다르다. 각 엔진1회 관측이며 paired 반복 완료 측정의 성능 개선율은 아직 없다. v4는 Analysis333.489초 이후 DP 실패였으므로 v5 전체 성공 시간과 v4 실패까지의 시간을 속도비로 비교하지 않는다.

사용자 지시대로 DML·Y 위치·전처리·privacy·seed·cost profile·자원을 유지했다. 최신 main `ac0b5e6028` + native support/DP 통합 회귀 **1,006 JUnit PASS**, source/class hash mismatch0, independent review CLEAR다. 실제 runtime 근거는 `evidence/cofee-50k128-v5-validation/candidate-logreg-run1`, 범위가 명확한 시간 근거는 각 run 아래 `timing-evidence.json`이다. 두 완료 결과를 묶은 근거는 `evidence/cofee-50k128-v5-validation/first-valid-both-workloads.json`이다. 신규 profiling/JFR는 사용하지 않았다.

v7에는 남은 metadata 조회의 native relation 직접 소비와 SCC rank gate의 sparse/functional 표현을 포함했다. 별도 Physical multi-member compaction은 동일-cost 선택에서 receipt가 달라지는 반례 때문에 제외했다. Proof topology 자체를 입력별 선택 그래프로 표현하는 후속 수정은 별도로 검증한다. 완료된 v7 실제 시간은 위와 같이 악화됐으며 개선 성공으로 보고하지 않는다.

## 현재 병목: pruning 누락과 조합 전개를 구분

현재 가장 직접적인 문제는 **support 조합을 펼쳐 객체를 만들고, 같은 의존 경로를 반복 방문하는 구조**다. v5 실제 LogReg의 support leaf3,919,362건과 Clause 생성2,534,754개가 그 증거다. 논리 proof3,941,087개와 실제 leaf 방문 수가 거의 같으므로 생성 전개를 충분히 피하지 못했다. 이들은 누적 생성·방문량이며 서로 다른 전역 실행 계획의 수나 동시에 메모리에 살아 있는 객체 수가 아니다.

1. **생성·Closure:** Oracle에서 압축한 입력 관계가 일반 FED rule fact, DIRECT support, 공개 proof 복원 단계까지 항상 압축 상태로 이어지지 않는다. 기존 최적화가 일부 HOP 또는 조건에만 적용되는 문제뿐 아니라, 같은 HOP의 후속 단계에서 다시 전개되는 문제도 있다. 최종 저장 region 수만 줄여서는 이미 생성된 임시 Clause 비용을 회수할 수 없다.
2. **조기 pruning:** 일반 rule MRV와 구조적 privacy projection의 적용 누락은 실제로 있었다. 수정 후 Clause 생성량은 약5% 줄었으나 Analysis 시간은 개선되지 않았다. 직접 source owner 충돌이 없는 입력도 전이적 공유 source·joint 조건에서는 충돌할 수 있으며, 그 관계를 모두 생성 단계에서 판정하는 구조는 아직 아니다. 반대로 실제로 모두 합법인 독립 조합은 pruning 대상이 아니므로 압축 상태로 전달해야 한다.
3. **Cost Surface:** Analysis 이후에는 worker 수를 계산하면서 support 경로를 재귀적으로 반복 방문하는 별도 문제가 확인됐다. source·proof의 합법성을 다시 정의할 필요 없이, 동일한 비용 관측값을 증명해 재탐색을 생략할 수 있는 범위를 검증한다.
4. **문자열·비교 비용:** 문자열 key 생성·정렬·비교는 반복 작업의 비용을 키울 수 있다. 그러나 최신 실행에서 문자열 비용만 분리해 측정한 근거는 없다. 문자열을 ID로 바꿔도 수백만 member의 생성·방문이 그대로라면 구조적 문제가 남는다. 현재 이를 단독 주원인으로 보고하지 않는다.

개선 우선순위는 **relation-native 전달로 불필요한 전개 제거 → 증명된 결과의 재탐색 제거 → 공동 제약의 조기 pruning → member당 문자열·비교 비용 축소**다. MRV 적용 횟수와 실제 제거 횟수, 논리 조합 수와 객체 생성 수를 분리해서 검증한다.

다음 표는 a03365e 병합 전 v2/v3 코드 감사다. 최신 main에는 중복 proof publication과 canonical 작업을 줄이는 후속 수정이 포함됐으므로 해당 구간은 병합본 실제 실행으로 다시 검증한다. 아래는 문자열 연산별 시간을 새로 측정한 결과가 아니다.

| 위치 | 현재 실제 작업 | 최적화의 한계 |
|---|---|---|
| `NativePlacementContinuity.java:2324`, `:2647` | 입력별 domain을 만든 뒤 immediate-support product를 순회하고 leaf마다 `CandidateSupportTemplate` 생성 | 독립 owner에서는 MRV 순서를 고정해도 모든 member를 생성함 |
| `NativePlacementContinuity.java:2046`, `:2145` | support memo hit에서도 template마다 공개 `NativeContinuityProof`를 복원하고 정렬 | 그래프 계산 캐시가 공개 proof의 재생성을 없애지는 않음 |
| `ExactPhysicalModel.java:1047`, `:1088` | relation axes를 입력 tuple로 펼친 뒤 emission·realization·support별 `Alternative` 생성 | 앞 단계의 relation 압축이 Physical Model 끝까지 유지되지 않는 경로 |
| `ExactPhysicalModel.java:942` | correlated relation의 허용 row를 순회하며 conditional region 생성 | sparse hole을 정확히 보존하지만 허용 row별 처리 비용이 남음 |
| `NativePlacementContinuity.java:3940`, `:3982` | proof binding별 segmented signature 구성, lazy 문자열 복원 및 정렬 비교 | 전체 문자열은 proof당 한 번만 만들더라도 proof 수가 많으면 비용이 커짐 |
| `ExactPhysicalCostModel.java:610`, `PhysicalSemanticDagFingerprint.java:98` | fact·Alternative를 receipt fingerprint에 반영 | identity memo와 factorized realization encoding이 있어도 이미 생성된 Alternative 순회는 남음 |

즉 문자열 캐시가 전혀 없는 구현은 아니다. lazy/segmented signature와 identity memo가 이미 있으며, 이들을 개선하는 것과 **비교해야 할 member 자체를 만들지 않는 것**은 구분해야 한다.

## 2026-10-09: pruning 0의 원인과 수정 검증

Origin/main 병합 후 frozen candidate의 실제 50K×128 LogReg Analysis는 **476.715초**였다. 이 단계에서 MRV route0, source-conflict cut0, input privacy avoided tuple0이며, Clause 생성은21,979,887개였다. 이는 생성 폭발이 해결됐다는 결과가 아니다. 누적 thread allocation261,498,370,024바이트도 peak memory와 구분한다. 이전 엔진의 미완료 planner 실행은 로그를 보존하고 수정본 검증으로 교체한다. 미완료 실행을 성공 시간/성능 개선율에 포함하지 않으며 새 profiling은 하지 않는다.

0을 만든 경로를 코드와 재현 테스트로 분리했다.

| 항목 | 기존 적용 공백/해석 | 현재 수정 |
|---|---|---|
| 일반 Oracle MRV | partial feasibility를 선언한5개 특수 rule에 한정. 일반 unary/binary/MM/aggregate는 해당 경로에 진입하지 않음 | 전방 rule의 입력·shape dependency 선언으로 조기 FED feasibility를 계산하고 exact evidence를 재사용. PUBLIC/CP 후보와 dependency 미선언 UDF는 보존 |
| 루프/함수 뒤 privacy | 구조적 privacy projection을 이미 추론해도 full physical privacy closure 이전에는 same-block seed만 사용 | 일반 compiled payload 연산에는 확정된 projection을 전달. transient writer/read 및 함수 carrier는 별도 권한 경계를 유지 |
| Direct support source conflict | 검사 자체는 있었지만 입력 순서 product였으며 cut0만으로 검사 여부를 알 수 없었음 | 가장 작은 남은 domain부터 선택하고 같은 owner의 다른 exact source를 incident 축에서 먼저 제거. 검사 횟수·제거 옵션 수를 별도 기록 |

Source fixture는 같은 합법80개 조합을 유지하며 prefix 방문321→83이다.240개 fixed-seed sparse domain 사례와 owner identity 반례를 explicit reference와 비교했다. 일반 MRV 관련26 tests와 direct support 관련119 tests가 통과했다. 루프·분기·함수를 포함한 privacy fixture에서는 Oracle 호출/leaf123→109이며 최종 semantic snapshot이 동일하다. 이는 작은 fixture의 검증이며 실제 LogReg 시간 개선율로 일반화하지 않는다. 첫 통합679 tests 중1개가 UNKNOWN shape 증거 손실로 실패했다. 미확정 shape를 UNKNOWN으로 보존하도록 수정한 뒤 해당2 tests가 통과했다. Privacy certificate의 authority identity 보존 회귀까지 포함해 **687 JUnit PASS(132.232초)** 및 COFEE helper18 tests PASS를 확인했다. Compiled source와 frozen class 전수 hash mismatch0이며 전체 Maven suite 결과는 아니다. 새 실제 workload 검증을 시작한다.

실제 수정본 실행은 campaign `w1357-bounded-cb66bb81880742`, attempt `01791497648443771201-a50c1fad`이다. 분석 약15초의 seq4에서 MRV11회/조기 검사36회, privacy projection379회/보호 입력34회/마스크 검사34회, privacy avoided tuple34와 generator rejection12를 확인했다. 이는 아직 중간 누적 작업량이며 동일 relation의 replay도 포함한다. Oracle MRV cut0, source conflict 비교/제거0이므로 이 두 제거 효과를 주장하지 않는다. Source MRV product8803은 실행됐으며, 같은 owner가 서로 다른 미선택 입력 축에 있는 경우만 exact source 비교를 한다. 별도 공유 owner 반례에서는 검사2/제거1이 검증됐다. 이 중간 관측의 최종 Analysis 결과와 이후 실패 상태는 아래에 기록한다.

### 수정본 v1의 실제 Analysis 완료 결과

동일 COFEE50K×128/W1/DML/Y/자원에서 각 엔진1회 관측이다. 이전 실행은 전체 planner가 완료되지 않아 paired compilation 성공 측정이 아니며, 아래는 완료된 Analysis 단계만 비교한다.

| 항목 | 이전 merged 엔진 | pruning 수정본 v1 | 차이 |
|---|---:|---:|---:|
| Analysis wall seconds | 476.715 | 490.139 | +2.8% |
| CandidateRuleKey 생성 | 27,926 | 27,442 | −484 |
| CandidateRuleFact 생성 | 227,285 | 222,582 | −4,703 |
| Explicit Clause 생성 | 21,979,887 | 20,882,097 | −1,097,790(−5.0%) |
| Indexed handle 생성 | 2,276 | 2,276 | 동일 |
| Direct support leaf | 4,191,721 | 3,942,161 | −249,560(−6.0%) |
| Proof row visits | 20,778,254 | 19,957,286 | −820,968 |
| 누적 thread allocation bytes | 261,498,370,024 | 249,704,432,960 | −4.51% |

수정본 terminal seq99에서 MRV 적용241/검사881/**거절0**, source MRV descriptor176,111/상세 비교0/제거0, privacy projection6069/protected283/mask checks275/avoided125/generator reject12였다. Candidate object 생성량은 줄었지만 **이번 관측에서 Analysis 시간 개선은 없다**. 누적 allocation은 peak memory가 아니며 post-analysis Docker7.371GiB도 현재 사용량이다. 최종 peak/전체 planning/numeric receipt는 아직 완료되지 않았다.

다음 수정은 직접 owner가 독립임을 이미 증명한 product에서 매 prefix마다 owner map과 MRV scan을 다시 수행하지 않는 변경이다. 정적 MRV 순서는 기존 동적 MRV와 같으며 논리적 leaf 수를 줄인다는 주장은 하지 않는다. 독립80×2×1 fixture의 합법160개 조합을 유지하며 원래 입력 순서 대비 prefix401→164, 관련122개 테스트 통과를 확인했다. 공유 owner는 동적 MRV 경로를 유지한다.

Cost Surface의 worker-count 재귀를 단일-worker 조건에서 정확하게 생략할 수 있는 bounded certificate도 추가했다. 출력 anchor만 검사하는 초안은 derived FOUT·WDIVMM이 서로 다른 실행 입력을 사용할 수 있다는 독립 검토 반례로 차단했다. 선택된 support와 W 입력의 권한까지 검사하며, 확증할 수 없거나 예산을 넘으면 기존 계산으로 돌아간다. Factorized 축과 indexed metadata를 직접 읽어 검사 중 product/Clause를 새로 만들지 않는다. 이 두 변경은 v1에 포함되지 않은 v2 변경이다.

v2 통합 검증은 **692 JUnit PASS(143.026초), COFEE helper18 PASS**, 별도 reviewer CLEAR다. main35/test59 source의 compile hash와 frozen4582개 class/resource hash mismatch는0이다. Manifest SHA `9e1066bf4e77cd22d4d209ff06c24207ba884c68374511fbf993d0e50936722b`, JAR SHA `2bdb182b594eb3e130888cdf01be3d3a2ca834f863c48c6ae7426e68cb523981`로 고정했다. 기존 v1 미완료 실행은 superseded로 보존·제외하고 같은 COFEE 조건에서 v2 실제 검증을 시작했다. `COST_WORKER_COUNT_PROOF|certified=true`와 완료 receipt를 확인하기 전에는 실제 적용·시간 개선을 주장하지 않는다.

실제 v2 campaign은 `w1357-bounded-1979f8d127b04e`, attempt `01791499397504112528-81392061`이다. 실행 중 엔진은 바꾸지 않는다. 그와 별도로 `hasDynamicNativeLayout`이 support member를 전개하지 않고 factorized 공통 metadata/indexed admitted-row metadata를 읽도록 수정했다. Authority lookup과 판정 의미는 유지한다. 10×10×10 관계의1000개 논리 member에서 생성 Clause0을 검증했으며 **v3 통합695 JUnit PASS(131.025초)**, 별도 reviewer CLEAR다. v3 manifest SHA `3e32306b7476a176b9305df52143bf82f22700d1689b9075005763a4abf05f9f`; 이 metadata 조회 변경은 v2 실제 성능 결과에 포함하지 않는다.

### v2 실제 종료: Cost Surface 사전 검사 실패

- Analysis wall460.527664756초, 누적 thread allocation249,236,625,608바이트. 생성량과 pruning counters는 v1과 동일하다: keys27,442/facts222,582/Clause20,882,097/indexed handles2,276/proof rows19,957,286/MRV 적용241·검사881·거절0/privacy avoided125·generator reject12/source 비교·제거0.
- 실제 `COST_WORKER_COUNT_PROOF`는 `certified=true`, refs1135/anchors6522/clauses38711/bindings60002/reason=complete였다. 따라서 실제 workload에서도 worker-count 재귀 생략 조건을 통과했다.
- 이후 `ExactCategoricalSolver.checkedCells → validateInputs → validateInputStructure → ExactPhysicalCostModel.freezeOrdinaryFactorsAfterPreflight`에서 `EXACT_VE_FACTOR_CELL_OVERFLOW`가 발생했다. `MODEL_SETUP` 완료 전 실패이며 compilation·실행·numeric receipt 성공은 없다.
- Coordinator cgroup `memory.peak`는7,462,850,560바이트(약6.95GiB), worker236,339,200바이트다. OOM event0이며 정상 오류 종료·소유 container cleanup resolved를 확인했다. 누적 allocation과 실제 peak를 구분한다.
- Process474.301초/wall491.761초는 실패 실행의 경과 시간이며 성공한 planning/runtime 시간으로 사용하지 않는다. GLM은 이 오류를 수정한 뒤 실행한다.

검사 코드는 현재 모든 raw factor의 scope Cartesian 크기를 `int`로 계산한 뒤에 저장된 support 크기를 고려한다. 반면 downstream의 `ExactPhysicalReducedSolver`에는 raw lazy 관계를 검증하고 unary/binary support로 감축한 뒤 materialization 한계를 검사하는 경로가 이미 있다. 두 사전 검사의 경계를 바로잡았다. Ordinary factor의 int 크기와 전체 budget, evaluator 이전의 구조 검사는 유지하고 solver-only symbolic factor는 기존 reduction으로 넘긴다. 작은 lazy factor12cells + ordinary8cells의 합산 한도와 raw functional map의 unary 축소 순서를 별도 회귀로 검증한다. 더 큰 raw 관계가 downstream reduction 후에도 남으면 기존 solver 한계는 여전히 적용된다. 최신 main 병합본 통합 회귀와 실제 재실행은 진행 중이다.

### Source 0을 해석하는 정확한 범위

Seq33(Analysis160.278초)의 실제 수치는 MRV 적용73/검사246/거절0, source MRV descriptor72,050/상세 비교0/제거0, privacy avoided125/generator reject12이며 support leaf1,443,438이다. Descriptor 수는 leaf 수가 아니다.

Source 상세 비교0은 비어 있지 않은 immediate-support 입력 축 사이에 동일한 **직접 owner**가 겹치지 않았다는 뜻이다. Owner 축 인덱스를 만든 뒤 서로 관련된 축에만 exact source equality를 적용하므로, 직접 owner가 다르면 그 비교를 하지 않는다. 이를 전체 plan 합법성으로 확대해서 해석해서는 안 된다.

현재 각 입력 reference는 재귀 경로가 하나 이상 살아 있다는 existential 증거를 가진다. 서로 다른 입력들이 고른 경로를 동시에 만족하는지는 별도 문제다. Transitive 공유 source, sparse/correlated row, VALUE_MAP/CFG joint alignment, relocation/pool 조건은 `ExactPhysicalModel`의 realization-support/correlated/strict-transient factor와 joint grounding 및 DP에 남아 있다. 이 위치의 graph에는 이 전체 관계를 싸게 판정할 미사용 exact prefix predicate가 없다. 따라서 직접 owner 검사만으로 백만 단위 leaf가 모두 전역적으로 합법이라고 주장하지 않는다.

이번 수정은 MRV·privacy의 적용 공백을 고치고 immediate source pruning 순서를 개선한 것이다. 이 정도 수정만으로 전역 상관관계를 generation으로 모두 끌어올리거나 모든 exact support 객체 생성을 제거하지 않았다. 다음 병목은 이러한 상관관계와 member-specific proof를 유지한 relation-native support 전달이다. 합법성을 근사하거나 대표 source 하나로 바꾸어 수치만 줄이지 않는다.

검증 엔진: JAR SHA `83d8ea70497341def4981482fb74c909581ca951ff5c2905a40aa5f96b7cae50`, freeze manifest SHA `17983957e8937229c6d090d7faaa0c56fa3fc4266b90a3dc3034cf401adc36ea`. 증거: `evidence/generation-pruning/final-integration-green/validation-result.json`.

`SEARCH_SPACE_GENERATION_PRUNING`은 조기 검사 적용/호출/거절, privacy projection 전달/보호 입력/마스크 검사, source MRV 적용/검사/제거를 분리한다. 서로 독립적이며 모두 합법인 product는 제거할 수 없으며, 해당 leaf의 relation-native 보존은 별도 남은 작업이다.

## 정본 조건

- Evaluation: `/home/mchoi/cofee-evaluation`, 현재 실행 방법 `docs/CURRENT_EXPERIMENT_METHOD_2026-10-06_KO.md`.
- Stage: `/home/mchoi/w1357-stage-main276-20261008T1025Z`의 데이터와 template을 보존한다.
- X: 50,000×128, nnz 6,400,000, PRIVATE_AGGREGATE. Y: 50,000×1, PUBLIC, worker-resident.
- LogReg: `data/binary`, `(Y<0)+1`, maxi=30, maxii=5, tol=1e-9, icpt=0, numclasses=2.
- GLM: `data/continuous`, `(Y>mean(Y))*1`, dfam=2, link=2, moi=20, mii=5, tol=1e-6, icpt=0, reg=0.
- 현재 runtime contract: 컨테이너 24 GiB, JVM heap 16 GiB, CPU set 0–7, tmpfs 4 GiB. 과거 ML10 16 GiB adapter와 구분한다.
- 먼저 LAN/W1/DP-local의 LogReg와 GLM을 동일 설정 baseline/candidate로 실행한다. 데이터·program·cost coefficient·runtime 자원 설정을 고정하며 planning, compilation, 실제 학습 실행, peak를 구분한다.
- 현재 matrix runner의 workload warmup은 0회다. Calibration probe의 별도 warmup과 과거 bounded-worker warmup 3회를 혼동하지 않는다.
- 실제 workload에는 임의 timeout을 넣지 않는다. 연결/setup/cleanup의 행정 timeout과 구분한다.

## 자원 점유와 실험 topology

정본 runner의 W1/W3는 so002를 포함한다. 현재 so002에는 이 작업 소유가 아닌 global14/local14 LogReg boundary probe 두 개가 2026-10-06부터 실행 중이다. 이를 종료·재시작하거나 network 설정을 변경하지 않는다.

so006와 coordinator so007의 가용성을 확인했다. 현재 runner는 topology override를 직접 지원하지 않으므로, 정본 runner의 실험용 복사본에 명시적 topology 입력을 추가했다. 독립 검토와 테스트에서 side effect가 해당 두 호스트에만 한정됨을 확인했다. 이는 **데이터·workload·자원은 COFEE 기준, 물리 worker는 so006인 실험**이며 정본 so002 배치로 표시하지 않는다. Shared runtime lock은 그대로 사용한다. 이 실행은 후속 진단으로 전환되어 paired 성능 집계에서 제외됐다. 다음 실행도 동일 topology와 입력을 사용한다.

## Frozen engine

기존 단계/JAR/source는 수정하지 않았다. Pinned base JAR에 각 frozen engine의 전체 classes를 결합한 독립 JAR를 만들고 모든 overlay byte 및 195개 builtin resource를 검증했다.

- Baseline: `7c80876e16b332fa435538acf48384e5b1743846fa271ec69dd6637cd41c461a`
- Candidate: `46b4e2f389cadd9394f19a1cc68b7a25b0cc41080c3ecf9fe12df41a557bed05`
- Artifact: `/grid/3/cofee-lm-sweep-mchoi-20260914/fed-oracle-native-20261008/evidence/cofee-50k128-engine-artifacts-final-v2-20261008/`
- Manifest 및 ZIP 무결성, baseline 4,547 / candidate 4,553 overlay entry, 301개 dependency JAR hash를 확인했다.

## 과거 기록과 구분

50K×2100 r3/r4는 별도 compile-only 진단이며 현재 training 기준이 아니다. r3의 analysis 712.442초는 전체 계획·학습 성공 시간이 아니고, r4는 진단 전환을 위해 중단됐다. 과거 GLM v77의 W1 446.962초/W3 293.974초 역시 compile-only다. 이를 이번 frozen engine의 새로운 실행 결과로 재사용하지 않는다.

## 실행 결과

새 대규모 paired 결과가 확보되면 이 절에 명령·hash·단계별 시간·peak·numeric 검증과 실패를 기록한다.

LogReg baseline pair0의 analysis terminal snapshot은486.383956682초다. 이후 `planner_begin`이 기록됐으며, 이 시점에는 전체 planning/compilation/training이 완료되지 않았다. Terminal counters는 queries/graphs189,408, rows20,743,752, supportLeaves4,190,413, relocationLeaves298,492다. 누적 thread allocation263,654,686,448bytes는 peak memory가 아니다.

동일 analysis scope의 생성 계측은 CandidateRuleKey27,926개, fact227,285개, explicit Clause21,979,458개, indexed handle2,276개다. 생성 중 버려진 임시 객체도 포함하며, 최종 논리 후보 수 또는 동시에 생존한 객체 수와 다르다. Candidate Oracle 호출25,664회, relation Oracle 호출362회가 기록됐다. 최종 storage의 factorizedClauses147,012만 보고 생성 단계의2,197만 Clause 비용이 사라졌다고 해석하면 안 된다.

완료된 analysis의 상위 exclusive 시간은 dependency pruning88.438초, public proof materialization79.135초, DIRECT binding78.140초, proof overlay67.539초, proof topology66.070초다. 하위 호출을 제외한 시간들이므로 phase별 병목을 비교할 때 이 값을 사용한다. 원본 terminal snapshot은 campaign root의 `baseline-logreg-pair0-analysis-terminal.json`에 보존했다.

정식 첫 실행의 증거 위치:

- Campaign: `/grid/3/cofee-lm-sweep-mchoi-20260914/fed-oracle-native-20261008/evidence/cofee-50k128-paired-final-v2/baseline-logreg-pair0`
- Runtime attempt: `01791492178151643302-bcc51194`
- 진행 중 `SEARCH_SPACE_LIVE`의 wall time은 해당 분석의 경과 시간이다. `CandidateE2ETiming`, compilation, execution 완료 시간과 혼동하지 않는다.
- `inclusiveAllocatedBytes`는 분석 thread의 누적 할당량이며 회수된 객체도 포함한다. Docker 현재 사용량 및 peak memory와 별도 항목으로 보고한다.

### 시간 측정의 경계

사용자가 요청한 **전체 플래닝 시간**은 COFEE receipt의 `planningFullInitialNanos`를 사용한다. `DMLScript.java`에서 DML parse 직전에 시작해 runtime program 생성 직후에 끝나므로, HOP 생성·rewrite·PlacementAnalysis·FedPlanner·LOP 생성 및 runtime program 구성을 포함한다. 실행 중 재컴파일과 실제 학습은 포함하지 않는다.

`CandidateE2E.totalNanos`는 공통 plan space 준비부터 FedPlanner의 선택·적용·receipt 전달까지인 내부 구간이다. 전체 플래닝과 별도 열로 기록한다. `compileNanos`와 `executionNanos`도 각각 별도 timer이며, 실패한 실행의 경과 시간을 성공한 전체 플래닝 또는 학습 시간으로 대체하지 않는다.

## 준비 검증과 실패 기록

- so006/so007에서 canonical stage manifest의 2,995개 파일(6,401,737,908 bytes)을 전수 검증했고 mismatch 0이다. Manifest SHA: `46cd725a527b8ab2515443fd6b31b5d6dac671246d6c8f800374abaf4b624463`. 원본 stage는 변경하지 않는다.
- 실험용 runner는 canonical stage를 read-only로 유지하고 variant JAR만 별도로 복사한다. Topology override는 so006/so007로 제한하고 원래 shared runtime lane을 유지한다. 독립 검토에서 실제 생성 DML·resource·host confinement·workload timeout=None을 확인했다.
- 첫 calibration root `cofee-50k128-calibration-baseline-logreg`는 MKL probe가 `loaded=false, blas=''`를 보고하여 workload 시작 전에 실패했다. 이 attempt의 planning/runtime 시간은 null이며 성공 측정에 포함하지 않는다. 실제 native 환경을 확인한 새 immutable root를 사용한다.
- 기존 `--pinned-runtime-contract`는 JVM Xms/Xmn과 cpuset을 변경하므로 그대로 쓰지 않는다. 별도로 측정한 동일 cost vector만 적용하고 현재 JAVA/startplan/config와 실제 Docker 자원을 보존하는 adapter를 검증한다.

### 추가: profiling 종료와 정식 paired 준비

- 두 번째 calibration root `cofee-50k128-calibration-unavailable-baseline-logreg`에서 실제 대규모 분석에 진입했다. 비용 calibration은 먼저 정상 완료되어 profile `04d4f1ae…`, 파일 SHA `8bca0f06…`에 11개 측정 계수를 기록했다. 실제 환경은 설정상 mkl이지만 native library가 로드되지 않은 Java kernel이었다.
- 이 calibration 실행은 thread dump 수집 시도에서 coordinator container에 `docker kill --signal=QUIT`가 전달된 뒤 rc137, container exit131로 종료됐다. `OOMKilled=false`, OOM event 없음이다. **진단 개입으로 오염된 invalid/excluded 실행**이며 OOM·알고리즘 실패·runtime 측정으로 집계하지 않는다. 이후 timed run에는 신호 또는 진단 개입을 하지 않는다.
- 비용 replay는 독립 검토 CLEAR 및 Python 7개 테스트를 통과했다. 실제 profile worker schema가 host/ip/port인 점을 반영했으며 topology index=1 제약은 유지했다. 선택 profile SHA/ID/liveMetrics를 campaign identity에 고정하고, 매 실행의 자원/native 조건과 receipt의 11개 effective cost 값이 일치하는지 검사한다. 기존 JVM/config/startplan은 AST 비교에서도 바뀌지 않았다.
- 같은 profile과 `liveMetrics=true`로 새 paired 실행을 시작했으나 baseline pair0을 아래의 진단용 실행으로 전환했다. 아직 완료된 대규모 결과는 없다.

### 정식 대규모 pair 시작

독립 검토 CLEAR 후 LogReg baseline pair0을 새 JVM에서 시작했다. so007/so006 두 노드의 lifecycle에서 24 GiB, cpuset0–7, tmpfs4 GiB, Xms/Xmx16g, Xmn1600m, ActiveProcessorCount8, liveMetrics=true를 확인했다. 측정 profile `04d4f1ae…`를 replay한다. 첫 약30초 analysis sample은 누적 thread allocation15.03GB, supportLeaves238,971이었다. 이는 진행 중 snapshot이며 완료 시간이나 peak memory가 아니다.

Root에서도 대규모 adapter를 포함한 Python24개와 기존 harness38개(합계62개)가 통과했다. Java production source33개 및 test source39개가 이전565-pass 빌드의 SHA와 동일하며, evaluation 저장소의 기존5개 미커밋 파일도 변경하지 않았다.

## 진행 중 로그에 대한 독립 코드 분석

### 기존 최적화의 효과와 소규모 대비 차이

기존 최적화가 무효인 것은 아니다. 이번 baseline의 `relocationProductAvoidedLeaves=5,917,050`은 캐시가 이전 product의 leaf 탐색을 재사용한 계측이다. 다만 Closure의 공개 proof 복원과 downstream의 일반 product 전개가 남아 있어 전체 시간을 지배할 수 있다.

동일 frozen baseline의 소규모 LogReg와 COFEE LogReg는 Candidate Oracle 호출7,989→25,664(약3.2배), fact55,207→227,285(약4.1배), Clause198,577→21,979,458(약111배)다. 이 비교는 동일 조건에서 데이터 크기만 바꾼 실험이 아니므로, 데이터 크기에 따른 복잡도 비율로 일반화하지 않는다.

실제 생성 DML 및 metadata에서 확인한 차이는 다음과 같다.

| 설정 | 소규모 회귀 | COFEE training |
|---|---|---|
| X | 192×8, ROW worker3개 | 50,000×128, ROW worker1개 |
| Y | public, coordinator에서 read | public, worker에서 federated read 후 `(Y<0)+1` |
| X privacy | private-aggregate | private-aggregate |
| LogReg classes | 3 | 2 |
| maxi / maxii | 10 / 5 | 30 / 5 |
| tol / reg | 1e-7 / 1e-4 | 1e-9 / builtin 기본값 |

Privacy label 자체는 두 실험에서 같다. 차이를 설명하며 privacy도 다르다고 말한 초기 설명은 실제 입력 대조 후 정정했다. 데이터 원소마다 계획 후보가 생기는 구조는 아니다. Shape·입력 위치·DML 분기와 rewrite 결과가 지원 관계 및 실행 선택을 바꾼다. 이 설정 차이만으로 이번 폭발의 원인을 Y 전처리라고 단정할 수 없다. 현재 작업은 DML과 입력 배치를 고정한 채 planner 내부의 누락된 압축·재사용 경로를 수정하는 것이다.

아래는 baseline pair0의 seq52 snapshot과 코드 검토 결과이며, 완료된 성능 비교가 아니다. Closure inclusive241.94초 가운데 DIRECT binding inclusive200.28초가 관측됐다. DIRECT 자체 exclusive47.86초, public proof materialization49.41초, dependency pruning41.13초, topology37.03초, overlay exclusive34.27초가 누적됐다. 중첩된 inclusive 값들을 더해서 전체 시간을 계산하지 않는다.

- Support memo hit도 `NativePlacementContinuity.instantiateSupportTemplates()`에서 공개 proof 객체 생성과 정렬을 수행한다(`NativePlacementContinuity.java:1942–1958`, `2035–2067`). 새 query에는 그래프 구성, dependency pruning, support product 전개가 남아 있다(`2122–2235`).
- `fixedValueMapPool()`은 모든 support 경로를 따라 pool/layout 고정점을 구한다. 완전한 의존 owner 집합을 제공하지 않으므로 Closure는 해당 query를 불완전한 구독으로 표시하고 일반 dependency cone을 다시 처리한다(`NativePlacementContinuity.java:3343–3480`; `PlacementRelationClosure.java:4905–4935`, `5495–5499`).
- Joint VALUE_MAP은 입력별 reference product와 조합별 DIRECT binding/Clause 생성 경로를 유지한다(`PlacementRelationClosure.java:1305–1331`, `1519–1532`). 현재 계측으로 이 메서드 단독의 시간 비중은 알 수 없다.
- `reused`는 proof cache hit가 아니라 매 wave의 미선택 fact까지 누적하는 `incrementalFactsReused`다. `rows`도 fact/emission/realization/clause 방문을 합친 값으로 고유 후보 수가 아니다. seq52의 실제 public memo hit/miss는41,276/375,124였다.

현재 relation-native FED 생성의 scalar WSLOSS/WCEMM compiled-context 제한으로 일반 FED/FOUT DIRECT 및 Joint VALUE_MAP 비용은 남는다. 다음 개선 우선순위는 완전한 VALUE_MAP 의존 owner/revision 추적으로 재검사를 줄인 뒤, support template/index를 publication까지 전달해 proof 복원과 정렬을 늦추는 것이다. 서로 다른 source owner, sparse holes, cycle grounding을 worker pool만 같다는 이유로 합치지 않는다. 측정 중 엔진에는 변경하지 않았다.

### Analysis 이후 남은 전개 경로

Local의 실제 경로는 `COMPILE_COST_BASED → FederatedPlanLocalCost → PhysicalModel → CostSurface → LocalPhysicalOptimizer`다. `planner_begin`만으로 내부 어느 메서드가 실행 중인지 알 수 없다. 독립 코드 검토에서 다음 미해결 전개를 확인했다.

| 영역 | 남은 전개 | 코드 근거 |
|---|---|---|
| Physical Model | Conditional relation도 입력 축 product를 순회한다. 압축 불가/clause-sensitive 경로는 support clause × input authority × output action마다 Alternative를 만든다. | `ExactPhysicalModel.java:1047–1086`, `1116–1133`, `1927–1957` |
| Cost Surface | 일반 lazy factor는 solver 전에 전체 scope product를 평가해 고정한다. `cells=Π domainSize`이며 경우에 따라 dense 배열을 만든다. Finite/Conditional support 및 functional mapping은 별도 압축 경로다. | `ExactPhysicalCostModel.java:578–580`, `717–731`; `ExactCategoricalSolver.java:1557–1593` |
| Local/Regional DP | Sparse join 경로 외 일반 fallback은 `outputCells × internalCells`를 방문하고 boundary 배열을 만든다. Sparse 추출도 기존 table을 스캔할 수 있다. | `IncrementalRegionalOptimizer.java:309–311`, `368–375`; `ExactCategoricalSolver.java:1986–2032`, `2092–2105`, `2508–2542` |

Trace의 MODEL_SETUP은 Model+CostSurface가 끝난 뒤, OPTIMIZATION은 최적화 완료 후에 출력된다. 내부 세부 시간은 최종 candidate receipt에서 확인한다. 위 표는 소스로 확인한 남은 비용이며, 진행 중인 특정 호출의 시간 비중을 측정한 결과가 아니다.

## 진단에서 확인한 실제 Cost Surface 병목

Analysis 이후 `planner_begin` 상태가 30분 이상 지속되어 이 attempt를 `diagnostic-conversion.json`으로 진단용으로 전환했다. coordinator의 실제 Java host PID 및 start time을 검증한 뒤 같은 JDK 계열의 host `jcmd Thread.print -l`을 사용했다. 세 dump 모두 성공했으며 main thread는 다음 경로에 있었다.

`physicalCostSurface → addPhysicalCompiledTransferFactors → realizationWorkerCount → realizationSupportWorkerCount → upstream source.supportClauses()`

이는 **DP 실행 전 worker 수 계산**이다. 기존 `PhysicalWorkerCounts`는 최상위 Alternative 결과만 저장한다. Anchor/균일한 factorized product로 즉시 worker 수를 구할 수 없는 source는 모든 upstream support를 재귀적으로 다시 읽는다. `visiting`은 순환을 방지하지만, 여러 경로가 공유하는 중간 DAG 결과를 재사용하지 않는다. 따라서 앞 단계에서 압축·dedup한 관계도 여기서 반복 방문할 수 있다.

이 증거로 전체 Cost Surface의 모든 시간이 해당 메서드라고 계산하거나, 다른 전개 병목이 없다고 결론 내리지는 않는다. 세 snapshot에서 같은 경로를 확인했으므로 우선 수정 대상으로 삼았다. 현재 사용량 약7.37 GiB는 peak 측정값이 아니다.

진단 후 소유한 Java process만 정상 종료 신호로 중단했다. runner는 rc143을 받고 cleanup을 완료했으며 소유한 두 container가 제거되고 shared lane lock이 해제된 것을 확인했다. `result.json`의 일반 `failed` 표시는 **의도적인 진단 종료**로 해석하며 OOM/알고리즘 실패나 유효한 paired timing으로 집계하지 않는다. 보고 도구도 diagnostic marker가 있으면 성공 상태의 result라도 timing에서 제외한다.

증거는 runtime attempt의 `diagnostic-conversion.json`, `diagnostic-thread-print-{1,2,3}.txt`, `diagnostic-summary.json`, `cleanup.json`에 있다.

## Pruning 적용 범위

기존 pruning이 모두 비활성화된 것은 아니다. Early privacy, source-owner conflict 검사, relocation MRV, descriptor dedup 및 closure 재사용은 존재한다. 그러나 중요한 범위가 남아 있다.

- Oracle MRV는 relation 경로가 없고 protected 입력이 있으며 partial Oracle을 지원할 때만 사용한다. PUBLIC/CP 후보에 FED 불가능 판정을 적용해 CP까지 제거해서는 안 된다.
- Oracle region을 얻어도 일반 FED/FOUT는 tuple별 exact fact 생성으로 돌아간다. 현재 새 relation-native FED 구현은 compiled scalar WSLOSS/WCEMM 범위다.
- `NativePlacementContinuity.enumerateImmediateSupports`는 입력 순서대로 product를 순회한다. 같은 source owner의 불일치만 일찍 제거하며, relocation 경로의 MRV/domain narrowing은 여기 적용되지 않는다.
- 서로 독립적인 선택이 모두 합법이면 MRV도 최종 leaf 수를 줄이지 못한다. 이 경우 조합을 제거하는 대신 product를 relation으로 보존해야 한다.
- `factorizedClauses`, `rowsCollapsed`, `incrementalFactsReused`, `relocationProductAvoidedLeaves`는 저장 압축·중복 작업 회피 지표다. 불법 조합 제거 수와 구분한다.

기존 계측에 있던 `supportConflictPrefixes`, `relocationConflictPrefixes`, `privacyAvoidedTuples`, `privacyGeneratorCombinationsRejected`를 `SEARCH_SPACE_WORK`에 노출했다. 앞의 두 수치는 잘린 prefix 수이지 해당 prefix 아래 tuple 수가 아니다. 과거 baseline 로그에는 이 열이 없어 새 로그와 사후 수치 비교는 할 수 없다.

## 통합된 확장과 검증

Frozen v2 engine을 보존하고 별도 `engine-integration-v3`에서 다음 두 변경을 통합했다. 독립 검토 CLEAR 및 통합 **582 JUnit 테스트 통과**를 확인했다. 전체 Maven 테스트 결과라는 뜻은 아니다. 증거: `evidence/integration-v3/validation-result.json`, compile command/source hash 및 tests.log.

1. VALUE_MAP fixed-pool resolver가 성공·실패 경로에서 읽은 identity owner 집합을 추적한다. 이를 topology/support/acyclic/public memo invalidation과 Closure subscription까지 전달한다. 완전한 footprint를 증명한 VALUE_MAP만 넓은 dependency-cone 재검사를 대체하며 derived FOUT는 기존 fallback을 유지한다. 숨은 owner 변경은 invalidation, 무관한 owner 변경은 재사용하는 회귀를 검증했다.
2. `RUNTIME_FUSED_KERNEL`의 owner 비용 관찰을 non-FED / FED-local / FED-nonlocal 최대3 class로 projection한다. 원본 owner와 hard authority는 유지하고, mapping 추가 비용까지 이득인 `C(N+W)<NW`일 때만 적용한다. Fixture에서 numeric callback120→30, 전체 encoded cell120→66, raw cost bits 및 최적값 parity를 검증했다. 실제 COFEE 개선 효과는 아직 측정하지 않았다.

중간 source worker-count 재탐색을 수정했다. source owner identity별로 완전한 aggregate만 invocation-local memo에 저장하고4096개 상한을 유지한다. 순환 경로의 결과가 `visiting`에 의존하면 기존 exact traversal을 사용한다. 최상위 fallback은 exact0 결과 이후 적용한다. 독립 reviewer가 발견한 structural owner 충돌 반례를 수정하고 CLEAR 및 lane588 테스트를 확인했다. 병합된 전체 검증은 아래666 테스트 결과에 기록한다. 최종 총시간·peak·LogReg/GLM 실행 동등성은 완료 결과가 나올 때까지 미검증으로 남긴다.

## origin/main 반영 이후의 측정 기준

사용자 지시로 origin/main `50855b5`를 현재 브랜치에 fast-forward하고 모든 미커밋 변경을 복원했다. 원격 커밋은 fixed-pool worklist, proof payload 재사용, candidate-route 계측, DP certified suffix pruning 수정을 포함한다. 따라서 앞서 기록한 e468797 기반 frozen v2 실행과 이후 실행을 같은 baseline으로 취급하지 않는다.

새 baseline JAR는 origin/main의 수정 production source10개 및 기록된 production source125개를 해당 commit과 전수 대조한 published artifact를 사용한다. Candidate는 병합된 source를 새 compile destination에 빌드한다. 기존 stage/data/cost profile/JVM/CPU/메모리는 유지하며, candidate LogReg를 먼저 실행해 확인된 병목이 해소됐는지 검사한다. 이 순서는 진단 목적이며 완료된 동일 조건 baseline이 없으면 paired 속도 비교로 보고하지 않는다.

첫 병합 통합 suite659개 중7개가 실패했다.6개는 새 Global 비용 certificate/minimum 계산이 conditional hard relation의 없는 numeric array를 읽은 연동 오류이며,1개는 새·기존 determinant 선언을 합친 뒤의 workload inventory 경로 차이다. 실패 로그는 `evidence/origin-main-merge-50855b5/first-integration-red/`에 보존했다. Conditional relation을 typed0/INF로 처리해 certificate/suffix pruning을 유지하고, TRead/TWrite·REPLACE dependency 선언 및 RMEMPTY/rshape inventory를 정합화했다. 관련46+41 테스트와 독립 검토를 거쳐 최종 통합666 테스트가 통과했다.

### 병합 후 최종 검증 및 새 엔진

- 현재 HEAD: `50855b5df4c6a2312c3a12ee8a31415a9d96aa27`. 사용자 미커밋 변경은 보존했고 commit/push는 수행하지 않았다.
- Production/test compile 성공, **666 JUnit tests PASS**(126.948초), validation Python32 + campaign Python46 PASS. 전체 Maven 검증은 아니다. 빌드에 사용한 모든 기록 source hash가 현재 파일과 일치함을 확인했다.
- 증거: `evidence/origin-main-merge-50855b5/final-integration-green/`.
- 새 candidate JAR SHA: `84e97e5d4450e7c81443a6200316bc8e97095a8319ada97e09707e803160304f`.4,575 overlay entry가 모두 compiled class와 byte-identical하다.
- Freeze: `evidence/candidate-engine-merged-50855-worker-memo-final-20261008T2345Z`; 이전 frozen engine/JAR는 변경하지 않았다.
-14단 shared DAG fixture의 resolver call은32,766→26이고 결과 worker count4가 같다. 이는 반복 탐색 감소를 증명하는 구조 테스트이며 실제 COFEE 속도·메모리 결과가 아니다.

### 실제 실행 검증 시작

새 candidate LogReg runtime attempt `01791495720953163961-b7761e40`를 시작했다. Campaign은 `evidence/cofee-50k128-merged-hotspot-validation/candidate-logreg-first-v2`이며 coordinator so007/worker so006,24GiB/16GiB heap/cpuset0–7/tmpfs4GiB와 고정 cost profile을 확인했다. 첫 준비 attempt는 wrapper의 dependency symlink 누락으로 probe compile 단계에서 실패했으며 container나 workload가 시작되지 않았다. 경로를 수정하고301개 pinned dependency를 검증한 뒤 새 root에서 실행했다. Frozen candidate JAR는 변경하지 않았다.

사용자의 최신 지시에 따라 추가 JFR·thread dump·profiling 실험을 진행하지 않고 실제 LogReg 완료 및 수치 동등성을 검증한다. 성공 후 동일 COFEE GLM 검증을 이어간다. 기존 정상 실행 로그의 단계 시간/생성량만 수집한다.
