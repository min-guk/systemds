# G014 FED Planning-Only 최종 감사 보고서

- **최종 스냅샷**: 2026-08-11 21:35 CEST (Europe/Berlin)
- **실행 방식**: Docker `run_LAN_docker.sh --planning-only --skip-net-check --net-profile lan`
- **범위**: `7 workloads × 4 workers × 4 planners = 112` planning-only 셀
- **최종 상태**: **112/112 receipt 생성, 최종 감사 `errors=0`, 실제 workload runtime 미실행**
- **다음 단계**: 동일 commit/JAR의 실제 Docker runtime 336셀을 `WAN-Light → WAN-Mid → LAN` 순으로 1회 실행

## 1. 결론

새 소스 commit `86365390936ed2e491c1f406cfce78865b67f763`과 고정 JAR로 네 플래너의 planning-only 행렬을 완료했다.

| 플래너 | 유효 셀 | 결과 |
|---|---:|---|
| DP | 28/28 | 통과 |
| FedAll | 28/28 | 통과 |
| Heuristic | 28/28 | 통과 |
| MinST | 28/28 | 통과 |
| **합계** | **112/112** | **최종 감사 오류 0** |

112개 모든 receipt가 다음을 만족한다.

- `success=true`
- `runtime_executed=false`
- `execution_seconds=0.0`
- `forbidden_output_absent=true`
- `Planner-Invoke`, planner별 필수 trace, `Emission-Summary`, `Planner-Complete` 존재
- receipt, coordinator log, compile config의 SHA-256 일치
- 동일 `(worker, workload)`에서 네 플래너의 common analysis fingerprint 일치
- TRead/TWrite는 `<CP,LOUT>` 또는 `<FED,FOUT>`만 선택
- recompile occurrence에서 `<CP,FOUT>` 없음
- runtime fallback, implicit repair, 금지 진단 없음

이 결과는 **플래닝과 lowering 계약 검증**이다. 실제 runtime 성공률·실행시간·성능 정렬은 아직 새 commit에서 검증하지 않았으므로 주장하지 않는다.

## 2. 고정된 실험 identity

### 소스

- 저장소: `/home/mchoi/g014-planning-audit-source-20260810-v1`
- 브랜치: `g014/planning-audit-20260810`
- commit: `86365390936ed2e491c1f406cfce78865b67f763`
- 제목: `Fix planning-only DP and MinST authority selection`

### 하네스/JAR/stage

- harness commit: `639649e0bcd9089cbabaea42fd09d08686603214`
- JAR: `/home/mchoi/g014-planning-audit-artifact-8636539-20260811-v1/target/SystemDS.jar`
- JAR SHA-256: `55264e62388ecffa55f632e8e8b97bd9e88778e17dc89886552e1906492203fe`
- immutable stage:
  `/home/mchoi/g014-planning-audit-stage-639649e-8636539-20260811-v1/g007-stage-fd6c4219c5c93d419df280ed9f4fb50b582023d9d611428136efc9eb07e75b5d`
- stage ID: `fd6c4219c5c93d419df280ed9f4fb50b582023d9d611428136efc9eb07e75b5d`
- descriptor SHA-256: `867f867ce59e838df8633ce437c5c477838ef2062dcb9768cc8c976f21eccce4`
- data tree SHA-256: `0a7066c7dbb6964292d60820115b87f9368d3a6171bdc2dfbe1f5d599bf07e5f`
- reference tree SHA-256: `8901dd3bc2d1e729c693a138a4d36aec2ad019f9b708a46be93a0fcba6c146bb`
- `test_only=false`

이전 commit/runtime 결과와 새 행렬을 합치지 않았다.

## 3. 이번 소스 수정

### 3.1 MinST relocation authority

- `RelocationAction.compatibleConsumers`는 후보 endpoint universe인데, 이전 projector는 선택된 action을 선택되지 않은 compatible consumer까지 전역 확장했다.
- selected physical alternative의 ordered input authority만 exact consumer/input authority로 사용하도록 수정했다.
- genuinely unbound인 direct demand만 canonical completion이 채운다.
- 후보를 닫거나 runtime fallback을 추가하지 않았다.
- 관련 MinST 10개 class 32 tests와 production tractability certificate 5 tests 통과.

수정 파일:

- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactPhysicalSelection.java`
- `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/CampaignBG014MinStL2SvmInternalEmissionCostRedTest.java`

### 3.2 DP decision-map 합법성

- DP의 local recurrence와 비용 철학은 유지했다.
- 다만 exact occurrence refinement가 neutral graph의 cross-root `SAME_PLACEMENT`, `SAME_FTYPE`, `CONJUNCTIVE` 제약을 무시해 불법 transient family를 더 싼 계획으로 재선택할 수 있었다.
- decision-map 구조 score에 해당 전역 **실행 합법성**만 포함했다.
- DP를 MinST와 같은 전역 비용 optimizer로 변경하지 않았다.
- all-LOUT/all-FOUT 후보는 계속 열어 두고 비용으로 선택한다.

수정 파일:

- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
- `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactProductionTractabilityCertificateTest.java`

## 4. 최종 감사 증거

- auditor: `/tmp/g014_audit_planning_matrix_639649e_8636539_20260811.py`
- 최종 출력: `/tmp/g014-audit-final-112-corrected-20260811.out`
- 출력 SHA-256: `f4b935f09f010e746f25853026ea846655bf6ac4a32a796f352651cf07401127`
- 결과:
  - `validated_receipts=112/112`
  - `missing=0`
  - `errors=0`
  - transient selections: `9,872`
  - recompile selections: `32`
  - DP legality overrides: `0`

실행 커맨드:

```bash
python3 /tmp/g014_audit_planning_matrix_639649e_8636539_20260811.py \
  | tee /tmp/g014-audit-final-112-corrected-20260811.out
```

## 5. Heuristic marker 감사식 수정

### 문제

첫 112/112 감사에서는 LM과 LogReg의 workers 2–4에서 6개 오류가 보고됐다.

- LM: `markerCount=3`, FedAll 대비 FOUT 감소는 2
- LogReg: `markerCount=5`, FedAll 대비 FOUT 감소는 2

초기 auditor가 `markerCount == FedAll FOUT count - Heuristic FOUT count`를 강제한 것이 원인이었다.

### 실제 정책 의미

Heuristic marker는 “이 producer를 `FED/LOUT`으로 유지하고 REFED하지 않는다”는 정책 사실이다. marker가 있다고 해서 FedAll이 반드시 그 producer를 FOUT으로 선택했다는 뜻은 아니다.

직접 occurrence-key 대조 결과:

- LM workers 2–4: marker 3개 중 FedAll에서 이미 FED/LOUT인 marker 1개, FOUT이던 marker 2개
- LogReg workers 2–4: marker 5개 중 FedAll에서 이미 FED/LOUT인 marker 3개, FOUT이던 marker 2개
- 따라서 실제 FOUT 감소 2는 정확하다.

수정된 감사식은 다음을 검증한다.

1. 모든 marker가 exact emitted decision key에 존재한다.
2. Heuristic의 marker state는 `FED/LOUT`, `derivedFedFout=false`다.
3. 현재 행렬처럼 `localPrefixCount == markerCount`, `frontierEdgeCount == 0`이면 변경된 decision은 marker 집합의 부분집합이어야 한다.
4. FOUT 감소 수는 marker 전체 수가 아니라 **FedAll에서 실제 FOUT이었던 marker 수**와 같아야 한다.
5. placement/runtime-plan fingerprint가 exact occurrence 변화 유무와 일치해야 한다.

이 수정 후 동일 112개 receipt 재감사는 `errors=0`이다. 소스 플래너 변경이나 재실행은 필요하지 않았다.

## 6. 플래너별 선택 집계

28개 셀 전체의 emission 선택 합계다. 숫자가 작거나 크다는 사실만으로 runtime 성능 우열을 의미하지 않는다.

| 플래너 | FED | FOUT | derived FOUT | explicit relocation | local materialization |
|---|---:|---:|---:|---:|---:|
| DP | 885 | 772 | 0 | 0 | 35 |
| FedAll | 1,140 | 1,056 | 51 | 26 | 44 |
| Heuristic | 1,140 | 1,008 | 29 | 51 | 37 |
| MinST | 787 | 672 | 4 | 16 | 13 |

관측:

- Heuristic은 모든 28개 셀에서 FedAll과 동일한 FED 수를 유지했다.
- Heuristic/FedAll placement가 같은 셀은 11/28이다. 이 셀들은 marker가 0이라 정책상 동일한 계획이 합리적이다.
- DP/FedAll placement가 같은 셀은 3/28, runtime plan이 같은 셀은 6/28이다.
- DP/MinST placement가 같은 셀은 0/28이다. runtime plan은 lowering 정규화 때문에 19/28에서 같을 수 있으나, placement decision은 전 셀에서 다르다.
- MinST가 가장 적은 FED/FOUT을 선택한 것은 encoded cost objective의 결과다. 이것이 실제 execution time 최적성을 증명하지는 않는다.

## 7. 플래너 철학 판정

### DP

- 자기 hop/자식 hop 중심 local recurrence라는 기존 철학을 유지한다.
- MinST보다 전역 비용 최적성이 낮을 수 있으며 이는 허용된 차이다.
- 다만 emitted transient family와 exact component가 런타임 합법성을 위반하는 것은 허용하지 않는다.

### FedAll

- 합법적인 범위에서 FED를 최대로 유지하고 FOUT/relocation을 exact selector가 선택한다.
- 28개 셀에서 FedAll policy summary와 physical transfer component 합이 일치했다.

### Heuristic

- FedAll의 max-FED 집합을 유지한다.
- heuristic이 LOUT으로 판정한 marker/prefix만 no-REFED 정책으로 투영한다.
- marker가 없는 경우 FedAll과 exact placement/runtime plan이 동일했다.
- marker가 있는 경우 current matrix의 모든 변경은 exact marker producer에 한정됐다.

### MinST

- 공통 node/edge cost 및 legality model 위에서 전역 min-cut physical objective를 최적화한다.
- 모든 objective는 finite/non-negative였고 required physical trace가 존재했다.
- 이는 **인코딩된 합법 plan space와 목적함수에 대한 최적성**이며, 측정 noise를 포함한 실제 runtime 최소값의 수학적 증명은 아니다.

## 8. 소스/테스트 검증

- MinST focused regression: 10 classes, 32 tests, failures/errors/skips 0
- MinST production tractability certificate: 5 tests, failures/errors/skips 0
  - XML: `/tmp/g014-minst-production-tractability-green-20260811.xml`
  - SHA-256: `b265dcd127fa02c2cd96aebe0a9b9cd6a5348436f64868528d628a227379d104`
- DP 관련 회귀: 19 tests 통과
- `mvn -q -DskipTests package` 통과
- `git diff --check` 통과

기존 `CampaignBG014ProgramDynamicAuthorityParityRedTest#allCompiledPlannersResetRunStateAtSharedFinalHopBoundary`의 expected-message 비교 실패는 clean predecessor에서도 동일해 이번 변경의 회귀에서 분리했다.

## 9. 다음 실제 runtime 캠페인

새 commit의 runtime 성능 결과는 아직 0/336이다. 이전 그래프는 predecessor/fallback 결과이므로 새 수정의 최종 성능 증거가 아니다.

다음 실행 규칙:

1. 동일 commit `8636539...`, 동일 JAR SHA-256, 동일 data/reference tree 사용
2. `run_LAN_docker.sh`만 사용; 물리 호스트 `run_LAN.sh` 금지
3. 고정 dataset/seed 유지
4. 순서: `WAN-Light → WAN-Mid → LAN`
5. 각 configuration은 우선 1회만 실행
6. 과거 미검증 문제 셀(DP/PCA)을 각 profile에서 먼저 배치하되 별도 중복 canary는 만들지 않음
7. 실패 시 성공 셀을 재실행하지 않고 실패 셀 및 아직 미실행 셀부터 진행
8. 모든 성공 행은 oracle, fallback absence, runtime scan, cold/warm plan·instruction identity, raw log digest를 검증
9. 336/336 성공 후 runtime/compile 그래프를 새 commit만으로 생성

## 10. 잔여 위험과 중단 조건

- **runtime 미검증**: planning-only가 통과해도 worker 통신, 실제 FED instruction 지원, 실행 결과 oracle, 시간 정렬은 실패할 수 있다.
- **DP/PCA 재발 위험**: 최신 predecessor 캠페인에서 12개 DP/PCA 셀이 실패했다. 새 캠페인에서 각 profile의 DP/PCA를 fail-first로 배치해 빠르게 검출한다.
- **MinST 비용 모델 위험**: 전역 optimizer라도 cost/size/boundary 추정이 틀리면 실제 runtime이 느릴 수 있다. 후보를 닫지 않고 추정과 실제 trace를 비교한다.
- **동일 runtime-plan 해석 위험**: 서로 다른 placement가 lowering 후 같은 instruction plan이 될 수 있다. placement와 runtime explain을 함께 보며 projector 유실 여부를 판단한다.

다음 완료 조건은 **새 commit/JAR만으로 336/336 Docker runtime 성공, oracle/runtime 감사 통과, 최신 runtime·compile 그래프 생성**이다.

## 11. 실제 runtime campaign 착수 후 발견된 차단 결함

planning-only 완료 직후 source `8636539...`의 immutable Docker runtime campaign을 시작했다. 최초 11개 terminal cell 중 10개는 성공했고, WAN-Light Heuristic/KMeans/worker=1이 runtime function recompile에서 실패했다.

- 기존 성공 10개는 재실행하지 않는다.
- 실패 셀: `/home/mchoi/g014-full-results-8636539-639649e-20260811-v1/cells/008-26a3d9502ecc`
- 오류: planner가 선택한 hop 263의 exact REFED authority가 registry 전역 clear에서 사라져 non-federated `VAR:` anchor 재추론으로 진입
- 수정 방향: runtime 관측으로 plan을 재작성하지 않고, typed durable placement key와 exact consumer/input authority를 recompiled DAG에 fail-closed 재투영
- 소스 회귀: Heuristic KMeans + 기존 FedAll KMeans/ALS + direct registry policy 총 4 tests 통과, fallback/repair 0, 실제 `fed_fed_refed` 실행 확인

따라서 이 보고서의 planning-only 112/112 결론은 유효하지만, `8636539...` JAR 자체는 실제 336셀 runtime 최종 identity로 사용하지 않는다. 수정 commit/JAR의 새 immutable stage에서 **기존 실패 셀을 continuation campaign의 첫 셀로 실행**하고, 통과하면 이전 성공 10개를 중복 실행하지 않은 채 아직 실행하지 않은 셀부터 WAN-Light → WAN-Mid → LAN 순으로 계속한다. 새 runtime identity와 predecessor receipt는 별도로 기록하며 서로 같은 commit의 성능 결과처럼 합치지 않는다.
