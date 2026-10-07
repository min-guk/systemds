# LogReg replay·proof·cost 반복 계산 후속 검증

## 목표와 시간 구간

사용자가 지적한 약40초는 여전히 긴 **전체 컴파일** 시간이다. 이전 worklist 개선을 충분한 해결로 취급하지 않는다. 실제 multiLogReg 학습을 대상으로 공통 분석, 모델/비용 구성, 최적화를 구분한다. StepLM·GLM 작업은 다른 담당자가 수행한다.

이전 동일 실행 묶음의 lmCG/L2SVM `TARGET_REACHED.plannerElapsed`는 각각1.005/1.847초다. 당시 전체 컴파일은7.354/14.022초였다. 직전 LogReg 두 실행 평균은 공통 분석28.999초, 전체 planner8.641초, checkpoint7.716초, 전체 컴파일38.768초다. 서로 다른 코드 revision의 수치이므로 동일 코드의 통제된 workload 간 비교로 해석하지 않는다. optimizer·checkpoint·전체 planner는 중첩 구간이며 합산하지 않는다.

## 원인과 변경

1. `PlacementRelationClosure.PhysicalCandidateState`는 owner 하나가 바뀔 때 전체 single-partition proof inventory를 폐기했다. 새 `SinglePartitionProofIndex`는 owner별 완전한 revision을 받아 old/new 의존 그래프의 영향을 받는 영역만 bottom으로 되돌린 뒤 least fixed point를 계산한다. missing source의 reverse edge, 삭제된 source, grounded cycle 철회, 세 possibility bit, 같은 reference의 마지막 owner/slot 우선순위를 보존한다.
2. CFG replay는 compatibility 정렬 비교마다 긴 증명 문자열을 만들었다. 기존 `canonicalComparator()`를 사용하고, `PlacementAnalysis`의 transient proof/compatibility 정렬 키도 기존 segmented text 구조로 구성한다. source/reader/proof의 불변 하위 구조를 공유한다. 기존 UTF-16 문자열과 정렬·중복 제거 의미는 동일하다.
3. CFG replay의 이전 edge 생존 검사는 매번 전체 facts를 source/reader 각각 검색했다. 기존 facts 및 **현재 reader** replacementFacts의 정확한 reference membership으로 바꾼다. 같은 owner의 기존 facts도 계속 포함하며 AVAILABLE/executable 필터를 추가하지 않는다.
4. `ExactPhysicalCostModel.PhysicalWorkerCounts`는 anchor의 worker 수만 재사용했다. 동일 Alternative에서 빈 visiting set으로 시작한 **완료된 root exact 결과**를 invocation 단위 identity memo에 저장한다. 재귀 중간 결과는 visiting 문맥에 의존하므로 저장하지 않는다. zero 결과의 fallback은 caller별로 나중에 적용하며 기존 durable-anchor early return을 유지한다.

합법 후보, privacy, TR/TW, support predicate, cost 공식, DP 탐색 정책을 변경하지 않는다. row 분해, 후보 cap, runtime fallback은 추가하지 않는다.

## 프로파일 증거

기준 `73d1eb024f`에서 SearchSpaceMetrics와 JFR을 켠 실제 FED LogReg:

| 공통 분석 phase | exclusive wall(s) | inclusive 누적 할당(GB) |
|---|---:|---:|
| CFG replay |10.253|18.406|
| Physical rebuild |6.138|2.303|
| Closure replay |3.659|22.963|
| Direct binding |2.903|2.278|

중첩 inclusive 할당은 합산하면 안 된다. 전체 analysis 할당은27.794GB다. 이는 누적 할당량이며 peak heap이 아니다.

CFG JFR CPU178샘플 중 candidateRealization 전체 검색69개, compatibility 문자열 생성44개였다. 위1–3과 segmented ordering을 적용한 진단에서는 전체 analysis 할당13.650GB, CFG5.111GB, physical1.813GB다. CFG allocation sample 가중 합은14.745→4.982GB이고, compatibility/proof normalizedSignature의 할당 표본은 새 실행에서0이었다. CPU 표본상 candidateRealization은69/178→15/238이며 잔여 호출은 실제 exact/value-map replay 내부 lookup이다.

후보 진단은 Maven과 겹쳤으므로 wall/CPU 시간을 채택 효과로 사용하지 않는다. allocation 계측과 hotspot 위치 확인에만 사용한다. 남은 CFG 비용은 exactTransientReplay, canonical text 구성, replay fact의 상세 문자열, Node equality 등으로 분산됐다.

최종 planner의 기준 진단은 모델1.427초, 비용 표면2.931초, optimizer3.996초, selection0.280초, 전체9.183초다. 최대1,470,976 logical-cell hard factor는 partial proof9회·leaf0회·약3.95ms로 전체 zero를 인증했다. 지금의 주된 시간 병목으로 지목하지 않는다. shared preparation은0.382초이며 지역 실행 로그마다 재출력되므로 반복 합산하지 않는다. seed boundary40회는 약2.370초, 그중 regional compile 합계1.319초다. 총17,336,646 assignments만으로 이 시간을 설명할 수 없다.

## 첫 ablation — 기준73d1eb

같은 pinned Docker image, 4CPU/8GiB, 새 coordinator/worker JVM을 사용했다. 실제 builtin multiLogReg, X192×8, PRIVATE_AGGREGATE, ROW workers3개, local public label, numclasses3, maxi10/maxii5다. `run_LAN_docker.sh --joint-boundary-e2e --case ml_logreg`만 사용했다.

| 순서/변경 | 공통 분석(s) | 전체 컴파일(s) |
|---|---:|---:|
| 기준1 |29.160|39.793|
| 정렬 키 재사용만 |28.552|39.492|
| 정렬 + CFG membership |27.907|38.536|
| 위 + incremental proof 1 |25.059|36.014|
| 위 + incremental proof 2 |25.209|36.084|
| 기준2 |33.301|44.097|
| 위 결합 + segmented text |25.256|35.710|

별도 incremental-proof 단독 screen도 PASS했으며 분석26.833초/컴파일36.933초다. 단일 screen을 독립적인 확정 효과로 주장하지 않는다. 정렬/조회/segmented 변경의 개별 단일 측정 차이는 작고 호스트 변동이 있어, 메모리 계측 및 최종 결합 결과와 함께 판단한다.

첫 교차6회는 CP/FED16개 계수 최대 절대 오차2.22e-16, audit/conversion 위반0이다. analysis fingerprint와 시간 제외 DP checkpoint1,224개가 모두 같다. 최종 upper122.26631334184357, lower120.54269578813249, gap1.429881%도 같다.

## 통합과 검증 경계

작업 중 origin/main에 StepLM closure 수정이 들어왔다. `fbfd4d790f`를 양쪽에 넣고 최종 후보와 비교한다. 해당 수정의 개선을 LogReg 최적화의 효과로 집계하지 않는다. 원래73d1eb ablation은 위 표로 별도 보존한다.

신규 proof 회귀는 root 삭제/복구, 순환, missing ref, 같은 ref의 support 변경, duplicate owner, batched commit, 고정 seed120회 owner update를 fresh 재계산과 대조한다. 기존250개 독립 synchronous oracle도 유지한다. CFG 회귀는 구조적으로 같은 별도 reference, 같은 owner의 이전/새 후보 합집합, LOCAL/non-executable membership, legacy 정렬/중복 제거를 검증한다. segmented text는90개 값의 정확한 문자열과8,100개 pairwise ordering을 비교한다. root-worker memo 회귀는 A↔B cycle의 fallback3/7, 공유 support, identity 분리, 호출 간 격리와 cache hit에서의 재귀 조회 생략을 확인한다.

기존 PhysicalGenerationEnvelopeTest는 baseline과 후보 양쪽에서 동일4건 실패했다(origins=null3건, null reflection receiver1건). 이번 변경의 성공 회귀로 집계하지 않는다. fixture 문제의 수정은 별도 범위다. 초기 metrics stage 실패2건도 성능 자료에서 제외했다.

원자료: `/grid/3/cofee-lm-sweep-mchoi-20260914/logreg-deeper-20261007`.
명령·동결 source/classes·회귀 로그: `target/logreg-deeper-evidence/`.

## 최신 main 기준 최종 결과 — 검증 완료, 1초 목표 미달

비교 기준은 `fbfd4d790f`, 후보 code commit은 `af761a6fc1`이다. Maven을 마친 뒤 JFR 없이 `baseline → no-cost → final → final → baseline` 순서로 실행했다. no-cost는 최종 후보에서 worker-count root memo만 제외한다. 공유 호스트 상태는 완전히 통제하지 못하므로 반복 원자료를 모두 공개한다.

| 실행 | 공통 분석(s) | 비용 계산(s) | 전체 planner(s) | 컴파일(s) | 학습(s) |
|---|---:|---:|---:|---:|---:|
|baseline 1|47.265|4.102|11.444|60.109|4.066|
|no-cost 1|39.210|4.334|12.311|52.947|3.691|
|final 1|39.758|2.820|12.237|53.401|3.623|
|final 2|36.482|2.678|11.006|48.904|3.982|
|baseline 2|51.084|3.251|11.391|63.863|3.866|
|**baseline 평균**|**49.175**|**3.677**|**11.418**|**61.986**|**3.966**|
|**final 평균**|**38.120**|**2.749**|**11.621**|**51.153**|**3.803**|

공통 분석22.48%, 전체 컴파일17.48% 감소다. 비용 계산은 기준 평균3.677→2.749초다. no-cost 단독4.334초와 비교해도 root memo의 해당 구간 감소가 관측된다. 다만 전체 planner는11.418→11.621초로 개선을 확인하지 못했고 checkpoint도10.288→10.352초다. 학습 자체는 약3.8초다. 일부 구간의 개선을 optimizer 전체 개선으로 확대 해석하지 않는다.

최신 기준의62초와 이전 revision의40초를 같은 비교군으로 묶지 않는다. 코드와 호스트 상태가 달라졌으며 새 baseline이 느려진 원인을 하나로 확정하지 않았다. 각 주요 군2회, no-cost1회이므로 신뢰구간이나 모든 workload에 대한 보장을 주장하지 않는다.

**채택 근거와 한계:** 합법 공간을 유지하면서 공통 분석 시간과 누적 할당을 줄였다. owner-delta proof, CFG membership/정렬 재사용, segmented text, root exact memo를 반영한다. segmented text 단독 wall-time 증분은 작으므로 단독 속도 개선률은 주장하지 않는다. 최종 컴파일51.2초, planner11.6초는 여전히 길다. **1초대 달성은 미완료이며 성능 문제 전체가 해결됐다는 결론이 아니다.** 남은 우선 대상은 common closure의 실제 replay 재구성·증명 context 재사용, 그리고 seed boundary의 반복 조건부 compile이다. 현재 큰 hard factor의 logical-cell 수만으로 우선순위를 정하지 않는다.

## 최종 정확성·provenance

- 최신 main 통합 Java63개 클래스: 총477건 중472건 PASS, 기존 ignore5건, failure/error0. Maven package 성공 및 build 중 Java source 변경0. ignore는 기존 TransientPlacementAlternativesTest5건이며 새 회귀를 skip하지 않았다.
- 새 Docker harness가 포함된 Python30건 PASS. 세 동결 variant 모두 독립 fixed-point oracle9건 PASS. full checkstyle/RAT는 targeted package 관행대로 skip했고 전체 정적 분석 성공으로 주장하지 않는다. Java 재컴파일·타입 검사와 `git diff --check`는 통과했다.
- 실제 builtin multiLogReg 최종5/5 PASS. CP/FED 전체16계수, shape8×2, 최대 절대 오차2.22e-16. audit mismatch/missing 및 conversion 위반0.
- 다섯 실행의 analysis fingerprint와 시간 제외1,224개 DP checkpoint가 전부 같다. upper122.26631334184357/lower120.54269578813249/gap1.429881%, assignments17,336,646/merges4,566도 같다. 전역 최적해 증명은 아니다.
- 입력·fixture·image·runner·dependencies는 동일하다. final main source1,651개와 class/resource4,363개가 최종 Maven 결과와 완전 일치한다. 전체 main/test Java3,579개가 build freeze와 같다. baseline source는 git fbfd4d790f와 전부 일치하며 production 차이는 의도한3개 파일뿐이다. no-cost→final은 ExactPhysicalCostModel.java 하나만 다르다.
- 독립 리뷰에서 owner-delta 철회, exact membership, UTF-16 정렬, root-only cache, upstream activeLoopSeeds 통합의 blocker0. durable-anchor early-return parity 지적은 기존 branch를 유지해 해결했다.

[최종 검증 JSON](experiments/logreg-replay-cost-20261007/validation.json), [정확한 argv](experiments/logreg-replay-cost-20261007/commands.json). 재현 시 새 run ID를 사용한다. 원자료의 frozen-inputs에 모든 소스·클래스·의존성과 실제 runner가 남아 있다.
