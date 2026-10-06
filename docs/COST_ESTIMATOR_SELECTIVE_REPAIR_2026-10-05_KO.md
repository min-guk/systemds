# 비용 추정 제안 비판적 검토 및 제한적 수정 계획

> **후속 검토**: 아래 표는 R52 구현 당시의 판정이다. 현재 소스에 기반한 나머지 항목의 재평가는 [통일 검토 기록](COST_ESTIMATOR_REMAINING_UNIFICATION_REVIEW_2026-10-05_KO.md)을 참고한다. AggUnary cap, DML call surrogate, 동일 GET의 codec 정책 등에 추가 근거가 확인됐다. 후속 검토는 아직 구현하지 않았다.

검토 대상: `cost_estimater_fix.md`. 이 문서는 검토 의견이며 원문 제안을 승인한 설계로 간주하지 않는다. 현재 runtime 실험은 중단 상태로 유지한다.

## 판정과 근거

| 원문 항목 | 판정 | 코드/런타임 근거와 제한 |
|---|---|---|
| 1. 전체 primitive / W 제거 | 문제 제기 수용, 전면 변경 보류 | `FederatedCostModel.computeFedComputeCost`는 mixed 입력의 local cost 전체를 나눈다. 그러나 local 입력도 kernel별로 slice/복제 방식이 다르고, 출력도 disjoint/overlapping partial이 다르다. 모든 local 입력/출력 bytes를 unscaled로 바꾸는 것은 다른 오추정을 만든다. |
| 2. 연산별 worker 수 | 수용 | `ExactPhysicalCostModel.addPhysicalUnaryFactor`가 graph worker union을 재사용한다. 실행 realization의 anchor/pool 증거를 우선하고 projection cache key에도 worker 수를 포함해야 한다. 재배치 목적지 pool을 실행 pool로 오인하지 않는다. |
| 3. /W 예외 제거 | 보류 | 현재 transpose/indexing/all-broadcast 등의 runtime 의미를 대체하는 물리량 모델/회귀 검증 없이는 삭제할 수 없다. |
| 4. generic floor 제거 | 코드 삭제 거절 | 일반 floor가 아니라 WDivMM/FunctionOp의 명시적 보정이다. 실제 kernel 비용 보정을 삭제하면 기존 과소추정이 재발한다. |
| 5. network helper 통일 | 방향만 수용, 이번 변경 제외 | reusable GET/native in-band/explicit movement의 프로토콜과 codec 병렬성은 같다는 근거가 없다. 산식 통일만으로 정확도가 높아지지 않는다. |
| 6. RTT fixed stage 제거, RTT/2 적용 | 거절 | FED/FOUT도 실행 request/response가 필요하다. 데이터 payload가 없는 응답을 지우거나 두 방향을 실제 RPC 단계와 연결하지 않으면 latency가 누락/중복된다. |
| 7. control 분리 | 의미 구분 수용, 수치 변경 없음 | 이미 control과 latency가 별도 설정이다. 항의 분류 이동은 총비용 수정 근거가 아니다. |
| 8. wire/codec critical bytes | 방향 수용, 보류 | codec 병렬성/critical path 측정 없이 `sum` 또는 `max` 중 하나로 일괄 교체하지 않는다. |
| 9. AggUnary q/W | 문제 일부 수용, 제안 방식 거절 | 현재 q=1은 **응답 하나가 아니라 전체 결과 한 벌에 해당하는 payload**다. ROW row-aggregate/COL col-aggregate는 W개의 disjoint 응답을 bind한다. network fan-in은 W를 유지하고 coordinator 비용만 bind/merge/select-first에 맞춘다. |
| 10. cap 제거 | 보류 | 먼저 해당 kernel의 work량 모델을 증명해야 한다. cap 삭제 자체가 개선은 아니다. |
| 11. movement/materialization 통합 | 현재 계약 유지 | 가격 arithmetic의 공유와 activation/lifetime identity의 합병은 다르다. 재사용 경계/중복 제거/생성 횟수를 지우지 않는다. |
| 12. shape를 FLOPs에도 반영 | 수용 | `PlacementCostSemantics.analysisAwareUnitLocalCost`는 occurrence shape로 bytes를 보정하지만 `ComputeCost`는 원본 Hop 차원을 읽는다. immutable occurrence-exact dimensions를 compute에 전달한다. 상한을 exact shape로 승격하거나 공유 Hop을 변이하지 않는다. |

### 증거와 추론의 경계
- **직접 증거 / 높은 확신**: graph-wide worker count 및 worker count가 빠진 projection cache key, memory와 FLOPs의 서로 다른 shape 경로, `FederationUtils.aggMatrix/aggScalar`의 bind/merge/replica 선택과 coordinator 비용의 불일치.
- **추론**: 위 오류는 plan 순위를 왜곡할 수 있다. 개별 workload의 runtime 개선 폭은 아직 측정하지 않았다.
- **미확인**: 네트워크/codec의 실제 critical path와 opcode별 partitioned work량. 원문의 `Pasted markdown` 표기는 검증 가능한 인용이 아니므로 저장소 소스와 테스트를 근거로 대체한다.

## 편집 전 고정한 구현/검증 계획
1. worker pool과 AggUnary topology, exact-shape FLOPs의 회귀 테스트를 먼저 추가하고 현재 실패를 기록한다.
2. 기존 helper와 owned occurrence/realization 증거를 재사용해 세 결함만 수정한다. candidate-space, privacy, runtime fallback, TR/TW 합법성은 변경하지 않는다.
3. 기존 known-shape/sparse/kernel-floor/transfer/worker-count 테스트와 신규 테스트를 함께 실행한다. 별도 worktree/target에서 빌드하며 원 작업공간 target symlink와 실행 결과를 보존한다.
4. 독립 읽기 전용 검토 후 최종 결과/미해결 범위를 아래에 기록한다. 대규모 network 재설계나 runtime 재실험은 하지 않는다.

## 구현 결과

### 1. 실행 worker pool (범위: generic FED unary)
- `ExactPhysicalCostModel.addPhysicalUnaryFactor`: graph union W 대신 해당 alternative의 실행 증거로 산출한 W. projection key에 W를 추가했다.
- 일반 relocation-backed alternative의 execution realization과 emitted map은 별개다. **captured derived FED/FOUT realization은 출력 map**이므로 그 anchor를 실행 pool로 쓰지 않고 support clause의 native witness/input residency를 사용한다. W2 실행/W7 출력 회귀를 추가했다.
- exact 실행 pool을 증명하지 못한 경우 기존 graph fallback을 유지한다. boundary/result upload의 worker 가격까지 고쳤다고 주장하지 않는다.
- identity-scoped per-unary positive-result memoization으로 동일 support 재귀 계산을 반복하지 않는다. contextual zero/cycle 결과는 캐시하지 않는다. KMeans 구조 probe: FED681행, cache445항목,236hit. 이것은 전체 compile20초 달성의 성능 증거가 아니다.

### 2. AggregateUnary topology
- ROW row-aggregate / COL col-aggregate: W개 disjoint payload를 이어붙인다. payload총량은 최종 출력 한 벌, 네트워크 fan-in은 W, coordinator는 전체 출력 read+write. dense W1도 `LibMatrixAppend`가 복사하므로 같은 bind 비용이다.
- 반대 축 aggregate: W개의 overlapping partial을 병합하는 기존 비용 유지.
- BROADCAST: 모든 worker에 GET이 전송되어 payload배수W / 응답fan-inW를 유지한다. critical path는 전체 결과 한 벌이다. coordinator는 기존 결과를 채택하므로 별도 payload scan/copy/reduction은0이다. RPC control/codec 비용은 없애지 않았다.
- 예외: scalar VAR는 `processVar`의 별도 mean/variance merge overload를 호출하므로 BROADCAST라도 기존 W별 coordinator merge 비용을 유지한다. matrix VAR는 select-first다. 이 차이를 회귀 테스트로 고정했다. 추가 mean RPC/CM 연산 계수의 정밀 모델은 이번 수정 범위 밖이다.
- `q` 이름 대신 payload multiplier로 의미를 명시했다. RTT 설정/고정 request-response 단계/codec 처리량/재사용 activation은 변경하지 않았다.

### 3. exact occurrence shape와 FLOPs
- `ComputeCost`의 동일 연산식을 immutable positional dimensions로 호출한다. `PlacementCostSemantics`는 known Hop값을 유지하고 unknown차원만 EXACT analysis fact로 채운다.
- 공유 Hop을 변경하지 않는다. 입력은 Hop identity가 아니라 compiled input position으로 조회한다. RMEMPTY도 named target index를 사용한다.
- nnz가 알려진 sparse matmul의 sparsity와 기존 WDivMM/function correction을 유지했다. unknown shape/cost-size 상한을 exact compute 차원으로 사용하지 않는 회귀를 추가했다.
- 고정 회귀 예:5000×1000와1000×500의 matmul은 unknown Hop에서2.44ms로 내려가던 추정이 약157.01ms로 복원된다. acos50000×4는0.12ms→약9.65ms. **모두 모델 추정값이며 runtime 실측 아님**.

## 기존 테스트 정합성 보정
- `CampaignBG014ExactL2SvmInternalEmissionCostRedTest`: coarse graph activation 대신 `normalized.selectedRelocations()`의 실제 선택 action을 검사한다. 이전 JAR와 수정 JAR 모두 동일한 **선택되지 않은** Y relocation 때문에 기존 assertion이 실패함을 별도 diagnostic copy로 재현했다. 실제 선택 relocation/로컬 materialization/보호 payload의 privacy assertion 및 runtime receipt coverage는 유지한다.
- `ExactNativeLocalAnchorFanoutCostTest`: 이전 R51에서도 달라져 있던 analysis/candidate authority fingerprint만 갱신했다. **기존 factor structure SHA와 numeric raw-bit SHA는 변경하지 않았다.** 비용 golden을 새 값으로 무조건 승인한 것이 아니다.
- 위 진단용 assertion bypass는 저장소 테스트에 포함하지 않는다. 최종 저장소 테스트에는 성능/보호 assertion이 모두 남는다.

## 검증 결과
- 독립 code review: 해결되지 않은 blocker0, APPROVE. 초기 derived-FOUT 실행 pool/단일 worker bind 지적을 수정한 뒤 재검토했다.
- 최종 Maven package 성공. 최종 JAR의 비용/shape/worker/전송/compile-only workload suite **167개**, AggLocal/FULL/재사용 인접 회귀 **139개**, 합계 **306개 PASS**. 기존 ignored1개는 유지했다. 변경4개 production 파일의 `javac -Xlint:all,-path,-processing` 경고0, `git diff --check` PASS.
- 최종 JAR SHA256: `92c2b36359e5ad2817b3e0a9fe78ad735a349f43cc9c2a147080cbe44e0579e0`. 원문보다 더 좁은 scalar VAR 특수 경로까지 확인한 최종 버전이며, 중간 검증 JAR와 구분한다.
- 증거 root: `/grid/3/cofee-lm-sweep-mchoi-20260914/cost-selective-r52-20261005/evidence/`. 주요 파일: `shape-red.log`, `final-v2-packaged-cost-tests.log`, `final-v2-packaged-adjacent-tests.log`, `verification.json`, `r51-l2svm-privacy-diagnostic.log`, `r52-l2svm-privacy-diagnostic.log`. worker별 red/green/구조 probe는 같은 root의 `../worker-pool/evidence/`.
- main target symlink/CG 변경/무관한 rewrite·그래프 파일은 보존했다. 새 runtime 실험은 실행하지 않았다. 성능 개선 폭이나 전체 workload compile20초 달성을 주장하지 않는다.

## 남겨 둔 한계
- whole local cost/W, replicated/sliced operand별 worker work량, kernel별 reducer 연산량·sparse bind 정밀도는 여전히 근사 모델이다.
- boundary upload worker 수의 graph fallback, bounds 기반 memory와 아직 미해결 FLOPs 차원, codec concurrency/critical path는 후속 검증 대상이다.
- 이 변경은 위 세 가지 검증 가능한 결함의 제한적 수정이지 cost estimator 전체의 물리적 정확성을 증명한 것이 아니다.
- 이전 AggLocal/R44는 `ff203f4cc26b4a17393cadae73bf0a148a1684c2`까지 origin/main 게시 완료. **이번 R52 cost 변경은 별도 로컬 수정이며 아직 commit/push하지 않았다.**
