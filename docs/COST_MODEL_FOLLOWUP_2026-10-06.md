# 비용 모델 후속 점검

기준은 `d57bca99d9`이며 별도 worktree `/home/mchoi/w1357-cost-followup-20261006`에서 작업했다. 대상은 기존 transfer-cost 보고서의 보조 실행 단계, MMChain, 업로드 중복 가능성이다. 후보 생성·privacy·runtime을 바꾸지 않고 DP/Exact가 사용하는 공통 비용을 수정한다.

## 보조 실행 단계

실제 instruction을 mock transport로 실행해 요청 생성과 GET/PUT 내용을 검사했다. 하나의 worker fanout은 하나의 RTT로 계산한다. 아래는 원래 있던 주 실행 batch를 포함한 횟수이며, 일반 LOUT의 별도 결과 materialization은 기존 소유권을 유지한다.

| 경로 | runtime batches | 추가로 반영한 작업 |
| --- | ---: | --- |
| VAR | 2 | 평균 계산·중간 결과 GET |
| aligned covariance | 3 | X와 Y의 평균 계산·GET |
| aligned weighted covariance | 4 | 두 weighted mean과 weight sum |
| ROW cumulative | 3 | 부분 집계 GET, correction 생성·sliced PUT·적용 |
| ROW cumulative sum-product | 4 | 두 중간 결과 GET, 두 correction 행렬 PUT·적용 |
| reshape FOUT | 2 | 출력 schema PUT의 별도 RTT; 행렬 payload는 없음 |
| CTABLE 차원 탐색 | 주 batch + FED 입력당 1 | 첫 두 입력의 max/GET; local 입력은 RPC 없이 slice·scan |

`FederatedCostModel.computeMixedFedLocalCost`의 기존 input-preparation 비용 항목에 보조 작업을 더한다. 보조 작업 자체에는 새 factor 차원이나 후보 조합을 만들지 않는다. 후속 CTABLE GET은 기존 materialization activation factor를 재사용한다. covariance의 주 결과는 worker마다 scalar 하나가 main batch에 실린다. 이전 placeholder RTT를 제거하고 실제 평균 batch에 RTT를 부여하여 중복 과금을 방지했다.

작업량은 기존 compute/memory/network primitive로 가격을 매긴다. known shape와 occurrence-scoped byte estimate를 사용하며, worker 작업은 알려진 range의 최대 분할 비중을 반영한다. ROW가 아닌 cumulative에는 ROW 보정 비용을 붙이지 않는다. SUM correction은 경계의 sparse entries를, PROD/MIN/MAX는 입력 sparsity와 무관한 dense 초기 행렬을 추정한다. unknown-shape sum-product도 두 열이라는 연산 계약을 유지한다.

## MMChain

이전 unselected HOP probe는 FED MMChain 융합의 유효한 전체 플랜을 증명하지 못했다. 실제 선택·emission 후 checked FED fixture는 FOUT/materialization 경계 때문에 explicit multiply를 유지한다. 이 경로의 비용을 제거하지 않았다.

반면 순수 local CP fixture는 실제 `CP°mmchain`으로 lowering되면서 transpose와 inner multiply 비용이 남았다. 참여 노드와 실제 입력이 모두 CP/LOUT으로 고정되고 compiler의 `checkMapMultChain()`이 인정하는 경우에만 fused owner가 작업량을 소유하도록 수정했다. 공유 중간값은 별도 소비자를 위해 실행되는 만큼 비용을 유지한다. 세 chain 유형과 scalar subtraction·rewrite 비활성화·FED 비융합 대조를 포함한다.

작은 XtXv 예제에서 해당 세 kernel의 합은 수정 전 약 `0.00006379 ms`, 수정 후 `0.00002686 ms`다. 이는 비용 모델의 추정치이며 실측 latency가 아니다. 자세한 선택 상태와 lowering 증거는 [MMChain 기록](MMCHAIN_COST_REACHABILITY_2026-10-06.md)에 있다.

## 업로드 중복

기존 B01–B22, 반복 함수 인자, cross-block alias probe와 owner/cache 테스트를 재사용한다. 현재 확인한 경우에 중복 payload 업로드가 선택되는 플랜은 재현되지 않았다. 따라서 업로드 비용을 임의로 빼지 않는다.

일반 native broadcast는 호출할 때마다 PUT한다. owned REFED/FEDFout의 재사용과 같게 취급할 수 없다. cache hit에도 worker alias 제어 요청이 있을 수 있으며 mutation, eviction, 큰 값의 미보관은 새로운 업로드의 정당한 원인이다. 이 판정은 검사한 fixture에 대한 결과이지 모든 프로그램에서 중복 가능성이 없다는 증명이 아니다.

## Two-FED CTABLE 후속 수정

동일 worker/range의 두 FED 입력을 갖는 CTABLE LOUT 계획을 전체 hard+cost 제약으로 선택하고 실제 `FED°ctable…°LOUT` lowering까지 확인했다. 두 번째 입력의 authority는 `DIRECT_FOUT`으로 유지한다. runtime이 이 입력을 coordinator에서 읽는다는 사실을 기존 materialization collector에 추가하여 수집 비용을 소유하게 했다. 별도 cache 상태나 후보 조합은 추가하지 않는다.

| 실제 동작 | 비용 소유권 |
| --- | --- |
| cold secondary GET | MatrixObject 생성과 activation 기준으로 한 번; 동일 객체의 alias·CP 소비자와 공유 |
| warm secondary GET | 같은 생성 수명 안에서는 추가 GET 없음 |
| secondary sliced PUT | 매 CTABLE 실행마다; 이미 내려받았어도 발생 |
| fully aligned FED weights | 기존 worker 값을 직접 사용하여 GET/PUT 없음 |
| local 또는 nonaligned weights | local은 PUT, nonaligned FED는 cold GET과 매 실행 PUT |
| 별도 객체·relocation으로 새 객체 생성 | 원래 객체의 local cache와 병합하지 않음 |

secondary slice 및 min/max scan도 반영한다. 이 검사는 강제 LOUT에서도 runtime이 수행한다. singleton/FULL map의 broadcast는 slice 복사 없이 원본을 보내므로 해당 복사 비용을 붙이지 않는다. FED dimension discovery의 응답 수는 각 입력의 자체 map을 따른다.

추가로, 이전 `mixed.hasInputPreparation()`은 보조 작업의 비용이 양수라는 이유만으로 logical-input PUT이 이미 포함됐다고 판단했다. aligned weighted covariance에서 실제 local weights PUT이 0으로 사라지는 것을 재현했다. 이를 입력 위치별 upload 소유권 판정으로 교체하고 ROW covariance의 weights/counterpart는 scalar 결과 크기에 관계없이 ROW sliced PUT으로 계산한다. CTABLE PUT은 auxiliary 항목에 정확히 한 번 포함되므로 native-local edge에서 중복 부과하지 않는다.

실제 배치·cache 검증과 전체 feasible assignment의 GET 공유 검증은 후속 회귀로 고정한다. [후속 검증 요약](experiments/cost-followup-20261006/ctable-validation.json)과 [도달성 기록](AUXILIARY_COST_REACHABILITY_2026-10-06.md)을 참조한다.

## 비용을 추가하지 않은 경로

- **강제 native FOUT TSMM**: 현재 oracle은 native LOUT와 derived upload를 제공한다. runtime의 강제 FOUT branch에만 있는 입력 수집·전체 broadcast를 일반 TSMM에 추가하면 실제 선택 경로를 잘못 과금한다.
- **native FOUT CTABLE 후처리**: source runtime에는 별도 SliceOutput UDF가 있으나, 검사한 SliceLine 및 two-FED fixture에서 전체 제약을 만족하는 native FOUT 선택을 입증하지 못했다. candidate row만으로 추가했던 비용안을 제거했다. 모든 프로그램에 대한 불가능성 증명은 아니다.

이 경로들과 별도 CTABLE runtime 정합성 관측은 [도달 가능성 기록](AUXILIARY_COST_REACHABILITY_2026-10-06.md)에 분리해 남겼다.

## 1차 검증 및 한계

- Maven `package -DskipTests -Djacoco.skip=true`: 성공.
- 최종 공통 비용·소유권·network·loop/cache·runtime 계약 **447개 중 446개 통과**. 새 회귀 34개는 모두 통과했다.
- 실패 1개는 기존 StepLM `EXACT_VE_FACTOR_CELL_OVERFLOW`다. 수정 전 `d57bca99d9`의 동일 테스트 메서드를 독립 실행하여 같은 오류를 다시 확인했다. 테스트를 제외하거나 기대값으로 숨기지 않았다.
- 최종 classes로 B01–B22 및 반복 함수·cross-block alias probe 3개 재실행 성공. 중복 업로드 의심 지표는 모두 0이다.
- Docker wrapper: 14개 workload × W1/W3 **28/28 compile/lowering 성공**.
- 테스트 시작 시 저장한 production source SHA와 최종 source가 동일하다. `git diff --check` 통과.

[검증 요약 및 SHA](experiments/cost-followup-20261006/validation.json)에 결과를 보존한다. 주요 로그는 `build-final.log`, `java-final.log`, `java-command.json`, `java-classes.json`, `root-baseline/steplm-current-base.log`, `uploads/results.json`, `docker/results/B/runs.json`이다.

Docker 재현 명령:

```bash
bash scripts/fedplanner/run_LAN_docker.sh --function-boundary-compare \
  --artifact-root /home/mchoi/cost-followup-20261006/docker --variant B --jobs 2
```

 증거 root는 `/home/mchoi/cost-followup-20261006/`이다. 기존 컴파일 fixture는 `/home/mchoi/cost-transfer-fix-20261006/docker`에서 복사해 경로만 새 root로 치환한다. 실제 분산 training latency 측정은 하지 않으며 Docker 검증도 compile/lowering 검증이다.

비용은 runtime의 모든 직렬화 header, 캐시 hit, 하드웨어 스케줄링을 정확히 시뮬레이션하는 값이 아니다. unknown geometry는 기존 byte fallback에 의존한다. 이 변경은 확인된 단계 누락과 융합 소유권을 수정한다.

## CTABLE 후속 검증

- 최종 production build 성공. 공통·인접 비용 회귀 460건과 전체 CTABLE assignment/lowering/loop 회귀 4건을 합쳐 **464건 중 463건 통과**했다. 이번 후속 추가 17건은 모두 통과했다. 남은 1건은 위 1차 검증과 동일한 기존 StepLM `EXACT_VE_FACTOR_CELL_OVERFLOW`다.
- 전체 CTABLE 플랜에서 cold GET 비용을 기존 download primitive의 값과 직접 비교했다. 같은 S 값의 모든 TR/TW/CP/CTABLE GET contribution을 합산해 한 번만 과금하는 것을 검사한다. 서로 다른 source 객체는 별도 GET이며 T=1/2/10에서는 GET이 고정되고 CTABLE 연산 비용은 1:2:10으로 증가한다.
- 원래 base classes에 새 materialization 테스트를 얹은 음성 대조는 GET 누락을 검사하는 3건이 예상대로 실패한다. CP가 이미 GET을 유발하는 공유 대조 1건은 base에서도 통과한다. 가격을 snapshot 상수로 갱신해 통과시키지 않았다.
- CTABLE relocation 및 native FOUT의 전체 feasible witness는 검사한 fixture에 없다. 따라서 그 경로의 검증을 수행했다고 주장하지 않는다. 기존 relocation 객체의 독립 cache 소유권과 WDivMM 회귀는 유지한다.

후속 로그 root는 `/home/mchoi/cost-followup-20261006/ctable-followup/`이다. `build-final.log`, `java-final.log` 및 `java-command.json`, `materialization-final.log` 및 `materialization-command.json`, `baseline/`에 build·현재 검증·음성 대조를 보존한다. 이전 `validation.json`과 1차 로그는 이전 단계의 역사적 증거이며, 최신 source 검증은 `ctable-validation.json`을 따른다.

후속 Docker 검증도 동일 wrapper와 fixture를 재사용해 **28/28 compile/lowering 성공**했다. build 때 저장한 production 3파일의 SHA와 최종 source가 동일하다. `git diff --check`도 통과했다. 재현 명령은 다음과 같다.

```bash
bash scripts/fedplanner/run_LAN_docker.sh --function-boundary-compare \
  --artifact-root /home/mchoi/cost-followup-20261006/ctable-followup/docker --variant B --jobs 2
```

결과는 runtime latency 측정이나 모든 미검사 입력에 대한 비용 정확성 증명이 아니다. 이번 범위의 two-FED CTABLE 수집·재업로드 누락은 해결했다. 이후 StepLM solver overflow도 경계 복구 시 factor 분해 보존과 materialization 전 unary/binary 합법성 축소로 해결했다. 후속 통합 검증은 기존 StepLM 테스트를 포함한 **637/637 Java 테스트 및 28/28 Docker compile/lowering 성공**이다. [후속 검증 기록](experiments/cost-followup-20261006/steplm-validation.json)과 [구현·잔여 범위](SESSION_ISSUES_2026-10-06.md)를 참조한다. 도달성이 입증되지 않은 CTABLE native FOUT runtime 관측은 별도로 남긴다.
