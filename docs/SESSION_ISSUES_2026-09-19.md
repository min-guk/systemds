# Session issues — 2026-09-19

## G009 search-space 계산량·중복·메모리 개선 구현

- **상태**: P0–P4 제한 구현 완료. 유한 oracle·전체 selected suite·package PASS. 최종 GLM 계측 실행은
  `build/g009-complexity/final-glm-metrics-tmux-20260919T072534+0200/`에서 진행 중이다.
- **환경/조건**: correctness workspace, `audit/g009-completeness`, baseline
  `35d1f49507a9cf3566674e68c6b97ccc1354d079`. 실제 GLM 실행 증거는
  `build/plan-space-audit-20260919/g009-correctness-unbounded-fork-v6/`에 보존돼 있다.
- **증상/재현**: `NeutralPlacementGraphUploadRelocationRedTest`
  `#rewrittenInlinedOutputRetainsItsCompilerDeclaredTargetAuthority`의 `buildAnalysis`가
  약 113분 실행 후 사용자 요청으로 중단됐다. `run.meta`의 결과는 `INCOMPLETE_NOT_PASS`다.
- **메모리 관측과 추론**: 중단된 진단 GLM에서 `byte[]` 합계가 약 15분 시점
  `2,109,075,104 bytes`(약 1.96 GiB)였다. signature 보유가 기여했을 가능성은 있으나
  histogram만으로 모든 배열의 owner나 원인을 확정할 수 없다.
- **변경 요약**: product streaming, 중복 descriptor suppression, 선형 dead pruning, first-match template
  index, 64Mi-char bounded signature cache, resolver-local completed-result memo(entry/proof/estimated-byte budget),
  conservative dirty cone, owner-safe support substructure factorization을 구현했다.
- **도구 이슈**: 첫 계측 GLM은 약 25:55 뒤 unified exec가 signal/exit `131`로 종료했다. 이는
  algorithm PASS/FAIL가 아니며 이후 장시간 실행은 `nohup` supervisor+status file로 분리했다.
  baseline surefire report-directory property도 적용되지 않아 실제 `target/surefire-reports`를 증거
  디렉터리로 복사하는 방식으로 고정했다.
- **검증 결과**: ACTIONS 계측에서 duplicate descriptor `16`, pending relocation assignment peak `1`,
  dead compaction `144/144`, dirty fact reuse `36`, factorized list reuse를 확인했다. 최종 bounded run은
  161 tests, failures/errors `0/0`, finite missing/illegal-extra `0/0`; package exit `0`.
- **inventory**: production candidate-affecting manifest `5,473→5,637`, added/removed IDs `196/32`를
  memo/streaming/pruning/dirty/factorization/signature-cache 분기로 분류하고 재생성 후 PASS.
- **잔여 이슈**: 최종 GLM 정상 완료와 원래 long assertion, 3-run timing 분산, Docker workload
  qualification, G009 전역 보존/종료 증명은 아직 분리된 의무다.
- **잠재 회귀/감지**: root pin 누락 cache, revision 경계 오류, negative dependency 누락,
  SCC merge/split 처리 누락, OR correlation 소실, cache 메모리 증가를 변형 oracle와
  full-recompute 비교 및 전체 pipeline peak memory로 검출하도록 계획했다.
- **의사결정 근거**: runtime·privacy·TR/TW·recompile 규칙, candidate cardinality, OR correlation은
  바꾸지 않고 동일 합법 의미의 계산과 표현만 개선한다. 유한 oracle 통과를 전역 증명이나 workload
  인증으로 확대하지 않는다.

## G009 10배 목표 알고리즘 재설계 계획

- **상태**: 계획 작성 완료; 새 R1–R3 구현 및 10배 성능 검증 미실행.
- **증상/환경**: 07:50 CEST 관측에서 최적화 GLM이 약 25분째 미완료.
  fork PID 3775111의 thread dump 4회가 direct closure → clause 병합/정렬 →
  normalized signature 또는 구조 hash 경로에 있었다. 당시 RSS 약 3.26GB.
- **원인 추론**: 문자열 중심 identity와 반복 canonicalization이 유력한 병목이다.
  cache 예산 소진 후 재직렬화 가능성이 있으나 saturation 및 전체 시간 비율은 미확정이다.
- **해결 계획**: R0 짧은 scale fixture → R1 구조 ID/중간 정렬 제거 →
  R2 공유 proof topology+query overlay/delta → 조건부 R3 selector-aware 압축 관계와 lazy receipt.
- **수정 파일**: `docs/G009_SEARCH_SPACE_ALGORITHMIC_REDESIGN_PLAN_2026-09-19.md`,
  기존 최적화 문서의 후속 문서 링크, 이 이슈 기록. production 코드는 이번 요청에서 변경하지 않는다.
- **주장 정정**: 앞선 답변의 처리량 4–5배, 메모리 16–33배 감소는 미검증이므로 철회한다.
  객체 수 도달 시점과 cache 예산은 각각 실제 처리량과 전체 메모리 절감의 증거가 아니다.
  기존 P4는 제한적 객체 공유이지 새 계획 R3의 완전한 factorized pipeline이 아니다.
- **검증**: 문서 코드/테스트 경로 존재, Markdown local link, whitespace/diff 검사.
  새 실험은 실행하지 않으며 실행 중인 GLM은 이번 문서 요청으로 중단·재시작하지 않는다.
- **잔여 이슈**: 정량 profiler, 재설계 구현, 동일 Docker 완료 baseline 및 3회 반복 비교,
  ML/P1/P2/SliceLine 개별 qualification, 전역 correctness/termination 증명.
- **잠재 회귀/감지**: ID 소유권 혼동·lexical 순서 변화·pin 누락·negative invalidation 누락·
  OR correlation 소실·shared 비용 중복을 poison matrix, full-recompute shadow,
  작은 전수 decoder oracle 및 DP/Exact 비용 parity로 검출한다.
- **의사결정**: runtime/oracle 규칙과 합법 후보를 줄이지 않고 표현 및 평가 알고리즘만 재설계한다.

## G009 알고리즘 재설계 구현·최종 GLM

- **채택 상태**: R0/R1 구현, R2 부분 구현, R3 lazy receipt/final sharing까지만 구현 후 조건부 보류.
  full global delta-SCC와 selector-native `Choice/Conjunction` relation은 미완료다.
- **핵심 원인**: 첫 정상 GLM은 query마다 proof graph/prune/SCC를 반복해 proof query
  `1,564,819`, alternative `1,055,953,130`, SCC edge scan `2,638,082,016`을 수행했다.
  final receipt는 `99,632` slot이며 실제 receipt/rank 생성은 0이어서 주 병목이 아니었다.
- **채택 변경**: bounded structural handle/legacy-exact rope ordering, shared topology+query overlay,
  dependency handle precompute, 계측 scan fusion, provenance-neutral support solution memo,
  occurrence-footprint 기반 revision invalidation/reuse, lazy owner-bound receipt/rank, no-op final
  factorization object reuse. 후보 cap/sampling/runtime fallback은 추가하지 않았다.
- **correctness**: `build/g009-redesign/final-broad-20260919T144851+0200/`에서 173 tests,
  failure/error `0/0`, skip `4`. ACTIONS와 GLM fingerprint는 각각
  `48f343...d0e7`, `98b41d...7109`로 유지됐다. 이는 유한 corpus 결과이며 전역 증명은 아니다.
- **최종 GLM**: `build/g009-redesign/final-glm-revision-support-20260919T145212+0200/`.
  evaluator `837.223 s`, wall `14:07.82`, max RSS `30,550,684 KB`, node/fact/action
  `1,992/2,160/293`. 첫 정상 완료 대비 wall 1.175x(14.92%), RSS 6.51% 감소다.
  proof query 10.14x, alternative 3.71x, dependency edge 3.53x, SCC edge 3.02x 감소했다.
- **기각 실험**: public-result memo revision 이전은 138,018 entries를 복사했지만 lookup hit를
  늘리지 못하고 `15:50.40`, RSS `31,670,436 KB`로 악화되어 revert했다. 실패 artifact는
  `build/g009-redesign/final-glm-public-memo-20260919T151513+0200/`에 보존한다.
- **추가 기각 실험**: occurrence별 realization structural index는 2,219만 membership lookup을
  만들어 `17:27.72`, handle-key 변형도 같은 수의 lookup으로 `15:47.38`이 걸렸다. 둘 다 채택본
  `14:07.82`보다 느려 revert했고 artifact는 `final-glm-realization-index-*`,
  `final-glm-handle-realization-index-*`에 보존했다.
- **판정**: 10배 공식 목표는 미달/OPEN. 113분 중단 run과 조건이 같다고 가정한 하한도 약
  `>8.0x`일 뿐이고 censored baseline이므로 PASS가 아니다. Docker 3회와 allocation/live-heap
  검증도 미실행이다.
- **inventory**: current `5,805`, HEAD `5,473`, added `618`, removed `286`; 904개 변경 ID를
  `G009_SEARCH_SPACE_BRANCH_INVENTORY_REVIEW_2026-09-19.tsv`에 모두 분류했다.
- **workload 상태**: GLM 외 ML training, P1, P2, SliceLine은 이번 작업에서 검증하지 않았으며
  모두 UNQUALIFIED다.

## G009 correctness·performance 통합 기준점과 LM 동일성

- **상태**: correctness의 accepted GLM 소스를 공통 base `35d1f49507` 위에 복원하고
  `integration/g009-unified-20260919`의 `6392a7ebe7`로 고정했다. performance 전체 history는
  겹치는 구현을 덮어쓰지 않고 기능 단위로 대조한다.
- **환경/조건**: workspace `/home/mchoi/systemds-g009-unified`. accepted artifact
  `/home/mchoi/systemds-g009-correctness/build/g009-redesign/final-glm-revision-support-20260919T145212+0200/`
  의 tracked production diff SHA-256 `9009de2e...7bdc`와 통합 전 production diff가 일치했다.
- **변경 요약**: accepted `source.patch`와 필요한 untracked metrics/test/script/docs만 복원했다.
  `build/`, `.omx*`, 임시 POM은 포함하지 않았다. 별도 evaluator는 timeout을 강제하지 않고
  candidate/support/proof/receipt snapshot 전체를 byte 비교하도록 추가했다.
- **검증**: 초기 통합 gate는 96 tests, failure/error `0/0`, skip `5`; branch inventory도 PASS했다.
  two-source snapshot SHA-256은 `4053fcd5...5c0`, LM은
  `/home/mchoi/systemds-g009-unified/build/g009-unified/lm-baseline-6392a7e-20260919/`에서
  `10,924 ms`, max RSS `1,354,132 KiB`, snapshot SHA-256
  `9000bff4...51a`로 performance branch accepted LM과 byte 동일했다.
- **성능 해석**: correctness 통합 LM 1회는 performance branch 최종 6회 중앙값
  `18,868.5 ms`보다 42.1% 짧다. 단일 run과 다른 source 구조의 비교이므로 최종 채택 성능
  판정은 반복 paired gate로 다시 수행한다. GLM accepted 기준은 여전히 `837.223 s`이며
  180초 목표는 OPEN이다.
- **잔여 이슈**: direct broadcast source index, no-op dead pruning, singleton SCC와 DAG fast path를
  현재 structural-handle/memo 구조에 맞춰 각각 적용하고 small/LM equality 뒤 timeout-free GLM을
  측정한다. 동일 Docker fresh JVM 3회, DP/runtime/final-plan oracle와 전역 보존 증명도 OPEN이다.
- **잠재 회귀/감지**: memo revision footprint, root pin, occurrence identity, support OR 순서가
  달라질 수 있다. 독립 plan-space oracle, 전체 snapshot byte 비교, revision/memo/cycle 테스트,
  fresh manifest로 감지한다.
- **의사결정 근거**: 합법 후보를 제거하지 않고 이미 동일 의미로 계산되는 lookup, no-op scan,
  acyclic/SCC 작업만 줄인다. correctness의 shared topology·structural cache가 대체한 과거
  performance 변경은 중복 이식하지 않는다.

## 통합 구조의 direct broadcast source index 실험

- **상태**: NO-GO, production/test/manifest 변경 전부 revert.
- **증상/원인 가설**: `isBroadcastRowProvablyUnselectable`가 broadcast input마다 전체 node를
  scan한다. resolver 생성 시 동일 value-version의 selectable `BROADCAST/FOUT` source를
  identity set으로 만들면 반복 scan을 없앨 수 있다고 보았다.
- **검증**: broadcast value-version/output/snapshot/missing-edge 경계 테스트 4개와 기존
  continuity/canonicalization/authority suite가 통과했다. two-source/local-mix 및 LM 8회 snapshot은
  accepted 결과와 모두 byte 동일했다.
- **성능 결과**: 4-pair LM control `11,350/12,376/10,209/11,965 ms`, current
  `12,172/10,308/10,666/12,347 ms`; 중앙값 `11,657.5→11,419 ms`(-2.05%)였지만 current가
  빨랐던 pair는 1/4뿐이었다. RSS 중앙값은 `1,354,892→1,315,266 KiB`(-2.92%).
- **판정/근거**: 불안정한 작은 중앙값 차이만으로 GLM 837초 병목을 줄인다고 볼 수 없으며,
  채택 기준인 반복 가능한 wall-clock 개선을 충족하지 못했다. artifact는
  `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-unified-broadcast-index-screen-r1-20260919/`에 보존한다.
- **잔여 이슈**: no-op dead pruning, singleton SCC, structural-handle 기반 DAG fast path를 같은
  snapshot·paired gate로 평가한다.
- **잠재 회귀/감지**: revert 후 통합 HEAD가 clean인지 확인하고 다음 변경은 새 diff로 시작한다.
- **의사결정 근거**: 후보 합법성은 바꾸지 않았으나, 성능 채택에는 의미 보존 외에 측정 가능한
  반복 개선도 요구한다.

## 통합 구조의 no-op dead-pruning fast path 실험

- **상태**: NO-GO, production/test 변경 revert.
- **증상/원인 가설**: dead state나 graph 밖 dependency가 없는 proof graph에서도 reverse
  dependency index, viable list 복사와 compaction을 수행한다. 제거 시작점이 없음을 먼저 scan해
  확인하면 동일 graph를 그대로 반환할 수 있다.
- **검증**: 기존 고비용 fallback은 유지했다. continuity, fixed-point, GlobalReceipt,
  ProductionDecoded, IndependentPlanSpaceGeneration suite와 two-source/local-mix/LM snapshot이
  모두 통과·byte 동일했다.
- **성능 결과**: 4-pair LM control `10,999/11,129/11,437/11,808 ms`, current
  `13,063/10,405/11,950/9,731 ms`; 중앙값 `11,283→11,177.5 ms`(-0.94%)이고 current가
  빨랐던 pair는 2/4였다. RSS 중앙값은 `1,315,856→1,348,798 KiB`(+2.50%)로 악화됐다.
- **판정/근거**: 추가 선행 scan의 비용을 상쇄하는 반복 가능한 LM 개선이 없고 메모리도
  나빠져 채택하지 않았다. artifact는
  `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-unified-dead-prune-screen-r1-20260919/`에 보존한다.
- **잔여 이슈**: singleton SCC와 DAG fast path는 dead-pruning 전체 경로보다 더 큰 SCC work를
  직접 생략하므로 별도 평가한다.
- **잠재 회귀/감지**: fast return은 관측 counter 계약도 바꾸므로 production과 함께 counter
  assertion을 되돌렸다. 다음 변경 전 working tree clean을 확인했다.
- **의사결정 근거**: 계산량 감소가 코드상 가능해도 실제 workload 시간·RSS에서 재현되지 않으면
  최종 성능 개선으로 채택하지 않는다.

## 통합 구조의 singleton SCC grounding fast path 실험

- **상태**: NO-GO, production/test 변경 revert.
- **증상/원인 가설**: 크기 1 SCC도 eligible map과 Tarjan refinement를 다시 만든다. 단일 상태의
  모든 외부 dependency grounded 여부와 direct/external ground path를 직접 검사하면 같은 조건을
  더 적은 객체로 판정할 수 있다.
- **검증**: self-loop 무ground, direct ground+미grounded AND dependency, dependency 없는 direct
  ground 테스트를 추가했고 기존 continuity/fixed-point/독립 plan-space suite가 통과했다.
  two-source/local-mix와 LM 12회 snapshot은 모두 accepted 결과와 byte 동일했다.
- **성능 결과**: 6-pair LM control `8,650/9,793/10,696/11,475/12,481/10,285 ms`, current
  `9,841/11,547/8,677/9,745/12,605/10,349 ms`; 중앙값 `10,490.5→10,095 ms`(-3.77%)였지만
  current 승리는 2/6이었다. RSS 중앙값은 `1,334,786→1,347,958 KiB`(+0.99%)로 악화됐다.
- **판정/근거**: 중앙값만 작게 좋아졌고 paired 승률과 RSS가 지지하지 않아 반복 가능한
  개선으로 채택하지 않았다. artifact는
  `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-unified-singleton-scc-screen-r1-20260919/`에 보존한다.
- **잔여 이슈**: 다음 후보는 acyclic graph에서 dead-prune cascade와 SCC 전체를 함께 생략하는
  DAG fast path다. 기존 performance 구현의 object-local visiting은 unified structural state와
  맞지 않으므로 active-set 기반으로 별도 적응해야 한다.
- **잠재 회귀/감지**: single-state self dependency와 외부 AND dependency를 혼동할 수 있다.
  관련 테스트를 실험과 함께 revert했으며 향후 DAG 구현에서는 같은 반례를 다시 포함한다.
- **의사결정 근거**: 의미상 안전한 국소 fast path도 목표 workload에서 안정적인 시간·메모리
  개선이 없으면 누적하지 않는다.

## 통합 구조의 acyclic pruning·grounding fast path 채택

- **상태**: 구조 개선 단위 채택. timeout 없는 small/LM/GLM 동일성 및 paired 성능 gate를
  통과했다. 전체 `buildAnalysis <= 180,000 ms` 목표와 Docker 3회 gate는 OPEN이다.
- **구조 변경**: proof graph를 만드는 query-local traversal이 equality 기반 active set으로 cycle을
  감지하고 dependency-first completion order를 기록한다. cycle이 하나라도 있으면 기존 dead-prune
  cascade와 SCC fixed point를 그대로 사용한다. DAG이면 reverse-dependency index, dead queue,
  반복 identity removal과 두 차례 SCC 수집을 생략하고 completion order에서 stable pruning과 grounding을
  수행한다.
- **보존 경계**: 기존 graph key와 dead state key를 남겨 occurrence revision invalidation footprint를
  유지한다. 생존 alternative의 객체 identity, 중복과 순서를 유지하고 missing dependency는 기존처럼
  fail-closed다. 새 회귀는 실제로 pruning된 dead sibling의 occurrence만 revision invalidation한 뒤 proof
  값은 같지만 graph가 재생성되는지 확인한다. 독립 최종 review는 이전 footprint 우려가 해소됐다고
  판단했고 CRITICAL/HIGH/MEDIUM/LOW 지적 0건으로 승인했다.
- **small/LM 동일성**: ACTIONS 52개 proof graph는 모두 acyclic이었고 snapshot이 byte 동일했다. LM은
  13,854개 graph 중 acyclic 13,371, cyclic 483이었고 351,893개 alternative를 DAG 경로에서 제거했다.
  control/current 12쌍의 snapshot은 모두 accepted SHA-256
  `9000bff430f7fde79b901e5af6414eb4e537c1806af73056234f05830d63551a`와 같았다.
- **LM 성능**: timeout 없는 fresh JVM 12쌍에서 control 중앙 `10,304.5 ms`, current 중앙
  `9,733 ms`로 `571.5 ms`(5.55%) 감소했고 current가 12쌍 중 7쌍 빨랐다. peak RSS 중앙은
  `1,341,330 -> 1,338,568 KiB`(-0.21%)로 사실상 같다. 원자료는
  `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-unified-acyclic-fastpath-screen-r1-20260919/`에 있다.
- **GLM 동일성/구조 계측**: 같은 JVM heap/config, 외부 timeout과 planning budget 없이 control과
  current를 각각 정상 완료했다. 둘 다 fingerprint `98b41db8...7109`, node/fact/action
  `1,992/2,160/293`이다. current의 proof graph 154,362개는 전부 acyclic이었고 41,415,373개
  alternative를 제거했다. control의 SCC edge scan 872,529,353회는 current에서 0회가 됐다.
- **GLM 성능**: contemporaneous control evaluator `794.699 s`, wall `13:21.06`, peak RSS
  `30,471,044 KiB`; current evaluator `716.592 s`, wall `12:02.81`, peak RSS `30,468,196 KiB`다.
  planning은 `78.107 s`(9.83%), wall은 9.77% 감소했고 RSS는 사실상 같다. accepted historical
  `837.223 s`와의 감소율 14.4%는 참고값이며 최종 판정에는 contemporaneous pair를 사용한다.
  원자료는
  `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-unified-acyclic-fastpath-glm-pair-r1-20260919/`에 있다.
- **회귀/inventory**: 현재 source에 존재하는 12개 보호 클래스는 98 discovered/95 active pass/
  3 intended PUBLIC skip/0 failure/error다. 이전 performance branch의
  `G009RelocationEnumerationTest`는 correctness 통합 source에 존재하지 않아 발견 수에 포함하지 않았다.
  fresh branch manifest 5,833행은 tracked resource와 byte 동일하고, 935개 branch-ID addition/removal을
  `G009_SEARCH_SPACE_BRANCH_INVENTORY_REVIEW_2026-09-19.tsv`에 분류했다. `git diff --check`도 통과했다.
- **판정/다음 병목**: 반복 가능한 LM 개선, 정상 GLM 9.83% 개선, exact snapshot과 독립 review를 모두
  충족했으므로 이 단위는 유지한다. 그러나 716.592초는 180초보다 536.592초 길다. 다음 단위는
  남은 284,280,699개 proof alternative와 513,715,666개 dependency edge의 생성 원인을 계측하고,
  합법적 완성이 불가능한 alternative를 dependency 객체 생성 전에 보수적으로 거절하거나 topology를
  공유해 중복 생성을 줄이는 방향으로 제한한다.
- **잔여 이슈**: 동일 Docker fresh JVM 3회 180초, allocation/live-heap, DP/runtime/final-plan oracle,
  G009 전역 legality/completeness 증명은 계속 OPEN이다.

## Topology dependency 목록 공유 실험

- **상태**: NO-GO. production/metrics/test 변경을 전부 revert했고 accepted DAG fast path
  `024dafb89c`로 돌아왔다.
- **가설/변경**: topology row의 dependency skeleton이 root pin의 영향을 받지 않을 때 immutable
  `CandidateProofDependency` 목록과 nested state를 resolver-local cache에서 재사용했다. root back-edge,
  template fallback과 topology cache 밖 row는 기존 query overlay를 그대로 materialize했고,
  topology eviction 시 prepared 목록도 함께 제거했다. DFS, graph key, logical edge와 revision footprint는
  바꾸지 않았다.
- **구조 계측**: LM logical dependency slot 1,750,172개 중 실제 dependency/state 생성은
  252,820개로 줄어 85.55%를 재사용했다. prepared hit/miss는 1,023,915/138,866,
  root override 20,126, uncached 1,791이었다. peak prepared cache는 5,552 lists/9,960 slots다.
  proof graph/state/alternative/edge, acyclic/cyclic graph, removal count와 analysis fingerprint는 control과
  같았다.
- **동일성**: two-source, local-mix, LM snapshot은 모두 accepted 결과와 byte 동일했다. LM 12쌍의
  SHA-256은 모두 `9000bff430f7fde79b901e5af6414eb4e537c1806af73056234f05830d63551a`다.
  root-pinned self back-edge가 반드시 query overlay를 다시 만들고 cyclic fallback을 유지하는 회귀도
  통과했다.
- **성능 결과**: timeout 없는 fresh JVM 12쌍에서 control 중앙 `10,273.5 ms`, current 중앙
  `10,239.5 ms`로 차이는 `-34 ms`(-0.33%)뿐이고 current가 빨랐던 pair는 4/12였다. peak RSS
  중앙은 `1,347,214 -> 1,380,700 KiB`(+2.49%)로 악화됐다.
- **판정/근거**: 객체 생성량은 크게 줄었지만 row lookup·prepared retention 비용을 상쇄할 반복 가능한
  wall-clock 개선이 없고 RSS도 증가했다. GLM으로 확대하거나 이 cache를 채택하지 않는다. 원자료는
  구조 계측 `build/g009-unified/dependency-share-lm/`, paired screen
  `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-unified-dependency-share-screen-r1-20260919/`에 있다.
- **다음 단위**: dependency 목록을 장기 보유하지 않고 query 안에서 equality로 합쳐질
  `CandidateProofState`만 canonicalize하는 기존 검증 패턴을 unified 구조에 적용한다. 이는 dependency
  객체와 logical edge 순서는 유지하면서 nested state 중복만 query 종료와 함께 폐기한다. small/LM에서
  실제 hit 비율과 wall/RSS가 함께 개선되지 않으면 즉시 되돌린다.
