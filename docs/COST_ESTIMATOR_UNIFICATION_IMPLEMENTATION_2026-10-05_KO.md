# R54 비용 추정 통일 구현·검증 계획

기준: [R53 현재 소스 검토](COST_ESTIMATOR_REMAINING_UNIFICATION_REVIEW_2026-10-05_KO.md). 사용자가 구현과 검증을 요청했다. 아래 계획은 편집 전에 작성했으며, 하단에 구현·최종 검증 결과를 기록했다.

## 범위와 보존 계약

- 비용 계산만 변경한다. 후보/Oracle/privacy/TR-TW/재컴파일 합법성, runtime fallback 금지, 실제 materialization version/context/activation 계약을 보존한다.
- 원 작업공간의 기존 수정, target symlink, R51/R52 빌드와 과거 실험 결과는 건드리지 않는다. 새 격리 source/target 및 evidence에서 build/test한다. commit/push나 장시간 runtime campaign 재개는 포함하지 않는다.
- actual kernel work → 공통 execution primitive, transfer quantities → 공통 network primitive로 정리한다. 단순히 floor/cap 이름을 바꾸어 중복 보정을 남기지 않는다.
- 공통 분석을 후보마다 재실행하거나 추가 DP decision dimension을 만들지 않는다. 기존 immutable shape/realization/support 및 per-unary cache를 이용한다. 새로운 quantity가 다르면 projection key도 이를 구분한다.

## 편집 전 분류

| 현재 구조 | 분류 / 처리 |
|---|---|
| AggUnary output-only cap | 실제 입력 작업을 숨기는 보정: 입력 reduction quantities로 대체·삭제 |
| DML call cell floor / 비실행 경계 scan | 실행 소유권 위반: body와 boundary 전달 비용은 유지, placeholder execution 제거 |
| whole primitive/W + unscaled 목록 | 중복/불충분 모델: worker kernel별 FLOPs/read/write 산정으로 대체 |
| WDivMM rank floor / latent kernel floor | 필요한 kernel work를 뒤늦게 보정: explicit/fused kernel의 공통 FLOPs로 이동 |
| indexing CP/FED min cap | slice 모델의 중복: 실제 slice/overlap quantities로 이동 |
| GET 목적별 codec 정책 | 같은 transport에 다른 정책: 공통 response pricing으로 통일 |
| unknown/bounded shape 비용 추정 | exact 증거가 없는 비용 추정 경계: 비용용 근사를 명시하고 shape/후보 합법성 증거로 승격하지 않음 |
| materialization activation union cap | 정당한 lifetime/branch 상한: 유지 및 회귀 검증 |

## 실행 순서 / 소유권

1. R52까지 포함한 비용 source/test를 새 격리 작업공간에 보존하고 baseline JAR와 검사 classpath를 고정한다. 기존 golden이 아닌 runtime/물리량 계약을 검증하는 회귀를 먼저 작성하여 RED를 기록한다.
2. compute lane: ComputeCost, PlacementCostSemantics 및 FederatedCostModel의 execution 부분. worker work·AggUnary/indexing·WDivMM/fused quantities를 정리하고 해당 회귀를 담당한다.
3. network lane: FederatedCostModel의 network 부분 및 전용 테스트. 순수 payload arithmetic을 합친 뒤 동일 GET response 정책을 적용한다. RTT/control batch 소유권은 유지한다. 기존 rate/size calibration을 유지할 경우 공통 calibration으로 명명하고 runtime 불변조건으로 설명하지 않는다. 측정 없는 새 rate를 만들지 않는다.
4. root integration lane: ExactPhysicalCostModel의 DML boundary/physical layout projection 및 source/target worker 연결, 관련 integration 회귀. writer와 별도의 reviewer가 계획/최종 변경을 검토한다.
5. 타깃 RED→GREEN, 기존 비용·shape·function·activation·privacy 및 인접 회귀, 격리 Maven package, 최종 JAR로 재실행, 변경 production javac lint, diff/static 검사를 순차 수행한다. 실패 golden은 숫자를 무조건 갱신하지 않고 물리적 항목별 차이를 먼저 설명한다.

## 수용 기준

- native aggregate는 출력 shape가 같아도 입력량이 증가하면 worker 비용이 증가한다.
- W1 CP/FED 동일 kernel quantities는 일치하고 RPC/입력 전송/결과 조립은 별도 계상된다. partitioned와 replicated 입력, partial/full output, uneven partition을 구분한다.
- transpose/ternary/nary의 blanket unscaled 예외, indexing의 양쪽 min cap, rank 보정용 사후 floor가 실제 work 산정으로 대체된다.
- DML FUNCTION_CALL placeholder execution은0, body frequency와 실제 builtin execution 및 전달 비용은 남는다.
- 동일 GET의 payload 단가는 동일하고 standalone/in-band는 추가 batch 횟수만 다르다. codec concurrency 근사는 한 공통 정책에서 설명한다.
- source/target pool, projection cache, same-value reuse와 updated-value 반복 생성 계약이 보존된다.
- 새 테스트/최종 JAR/로그/소스 SHA를 증거로 남긴다. unit/compile-only 검증을 runtime 개선이나 전체 planning20초 증거로 제시하지 않는다.

## 진행 / 결과

- **상태: 구현·검증 완료.** 원 작업공간 source/test에 동기화했다. commit/push 및 runtime campaign은 수행하지 않았다.
- **변경 소유 범위**: production4파일 + test14파일(신규8, 기존6). 전체 경로와 SHA256은 아래 evidence의 `r54-owned-paths.txt`, `final-build-proof.json`에 있다.

### 구현한 통일 규칙

| 영역 | 변경 / 남기는 실제 차이 |
|---|---|
| CP/FED 실행 | `max(FLOPs/rho, readBytes/mu) + writeBytes/mu`를 공유한다. FED는 실제 worker별 kernel의 최대값을 사용한다. 전체 비용을 W로 나누는 대신 partitioned/replicated 입력과 full partial/sharded 출력량을 먼저 계산한다. |
| AggUnary/indexing | 출력만 보고 비용을 낮추는 cap을 제거했다. aggregate 입력 reduction과 indexing slice/worker overlap이 실제 work를 결정한다. |
| DML call | 실행하지 않는 FUNCTION_CALL placeholder는 kernel0. 본문 occurrence frequency, builtin 실행, 실제 경계 전송은 유지한다. |
| WDivMM/fusion | rank와 active weight entries를 기본 FLOPs에 반영한다. source shell의 제거된 중간 연산은0이고, 공유 소비자가 있어 살아남는 연산은 유지한다. U/V factor의 실제 slice/broadcast/read와 결과 조립은 surviving owner가 부담한다. |
| 0-NNZ | FLOPs0은 유효한 kernel이다. 구조 미인식과 분리하여 실제 read/write와 shell 소유권을 보존한다. |
| factor 차원 | fusion을 식별한 동일 m/n/r 관계로 factor bytes도 산정한다. ALS rank10 factor를 unknown256MiB로 계산하던 통합 회귀를 수정했다. 이는 비용용 관계이며 abstract shape/합법성 증거를 바꾸지 않는다. |
| transpose 소유권 | latent `U%*%B`가 생성하는 t(B)는 owner 준비비용. direct의 기존 RHS=t(B)는 해당 occurrence가 한 번 실행한다. |
| GET | ordinary/reusable/in-band 목적에 같은 wire/codec payload 산식을 사용한다. 차이는 실제 추가 request/response batch 수이다. exact geometry가 있으면 가장 큰 응답과 응답 worker 수를 반영한다. |
| FULL/PART | FULL은 단일 비복제 map, BROADCAST/PART는 모든 replica/partial 응답이다. 전역 pool 크기로 FULL을 복제하지 않는다. |
| layout/cache | 실제 입력 geometry, emitted durable output anchor, 실행 worker pool을 구분한다. 비용 projection key도 geometry를 구분한다. endpoint-only witness를 exact range로 승격하지 않는다. |
| 재사용 | source value version/context/lifetime과 branch activation union cap을 유지했다. operator min/floor와 논리적 activation 상한은 다른 계약이다. |
| 준비 비용 | kernel/ownership 준비는 surface 생성 시 공유한다. FED 대안마다 공통 분석을 다시 하지 않는다. fused owner↔W 가격은 기존 합법성 의존성으로 결합하며 새 DP 변수는 없다. |

### 수정 파일

- `src/main/java/org/apache/sysds/hops/cost/ComputeCost.java`: occurrence dimensions와 rank/NNZ-aware WDivMM FLOPs.
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCostSemantics.java`: prepared kernel quantities, runtime fusion 소유권, per-input layout/slice, response summary.
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java`: 공통 execution/network primitive, GET policy와 실제 batch, FULL/partial 구분. dead floor 필드·전달 인자 삭제; 기존 공개 compatibility accessor는0/false만 반환한다.
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java`: 비실행 boundary0, prepared projection/cache, actual source/target workers, fused owner-W factor.
- 신규 회귀8개: `FederatedNetworkCostUnificationTest`, `ExactExecutionOwnershipCostTest`, `ExactWorkerLayoutProjectionTest`, `ExactFusedKernelLayoutCostTest`, `ExactNativeResultBatchCostTest`, `PreparedExecutionCostQuantityTest`, `PreparedWdivmmOperandQuantityTest`, `FusedKernelOwnershipTest`.
- 기존 회귀6개: ALS partitioned cost, analysis memory, physical unary preparation, aggregate unary topology, cost fallback, planner fallback integration. 마지막 topology 파일은 기존 R52 coverage를 유지하고 라이선스 header를 정리했다.

### RED→GREEN 및 독립 검토

- baseline behavioral RED: DML placeholder 추가 과금, ordinary/reusable GET 가격 차이, 같은 W에서 skew를 구분하지 않는 projection, WDivMM factor slicing 불일치.
- 통합 회귀: fusion shell 이중계상·phantom transfer, EXEC/GET batch 중복, zero-NNZ 패턴 소실, factor unknown256MiB 및 non-TRANS factor 형태를 검출해 수정했다. 상세 경위는 `SESSION_ISSUES_2026-10-05.md` R54에 있다.
- 기존 X+X assertion은 storage footprint1개 대신 operand read2개를 검증한다. unknown bytes는 명시적 per-operand fallback을 검증하며 exact shape로 승격하지 않는다. LEFT/ROW WDivMM은 일반 ROW-MM disjoint shard가 아니라 full partial fan-in을 검증한다.
- **ALS의 기존 FED 선택 assertion은 유지했다.** 실패 결과에 맞춰 기대 planner를 바꾸지 않고 factor bytes 오류를 수정했다.
- 계획 architect 검토와 implementation writer를 분리했고, 두 번의 독립 최종 코드 검토에서 발견한 문제를 보완했다. 마지막 reviewer 판정은 **APPROVE**. 보고서는 `final-independent-review.md`에 보존했다.

### 최종 검증 — 패키징 JAR 기준

| 검사 | 결과 / 증거 파일 |
|---|---|
| 격리 Maven package | **BUILD SUCCESS**,42.193초 / `package-final.log` |
| cost regression | **251 PASS**,89.163초 / `final-cost-tests.log` |
| 인접 privacy/AggLocal/runtime-boundary/MM-chain regression | **188 PASS**,46.369초 / `final-adjacent-tests.log` |
| 합계 | **439 PASS**, 기존 ignored2 유지. full repository test suite나 runtime campaign 결과가 아니다. |
| production4파일 javac lint/typecheck | **경고0** / `final-production-lint.log` |
| 격리 및 main diff/static 검사 | **PASS** / `final-diff-check.log`, `main-final-diff-check.log` |
| source provenance / 안전한 동기화 | baseline SHA guard PASS. production Java와 builtin DML 전체가 최종 격리 build 입력과 일치. 기존 dirty 파일과 target symlink 불변 / `final-sync-proof.json` |

- 최종 JAR: `/grid/3/cofee-lm-sweep-mchoi-20260914/cost-unification-r54-20261005/source/target/systemds-3.4.0-SNAPSHOT.jar`
- JAR SHA256: `4583983f9e1c10d271d6ed423fce21f589356bffb74c73b49b41e2495230e304`
- evidence 절대 경로: `/grid/3/cofee-lm-sweep-mchoi-20260914/cost-unification-r54-20261005/evidence`
- 최종 검증은 원 작업공간에 이미 있던 MM-chain rewrite3파일 및 강제CG steplm까지 포함했다. 이 바이트들은 그대로 보존한 입력이지 새 R54 수정이 아니다. 원 작업공간의 과거 `target` symlink는 교체하지 않았다.

재현:

```bash
cd /grid/3/cofee-lm-sweep-mchoi-20260914/cost-unification-r54-20261005/source
/home/hadoop/apache-maven-3.9.7/bin/mvn -B -DskipTests -Dmaven.test.skip=true package
bash ../evidence/run-suite.sh final cost
bash ../evidence/run-suite.sh final adjacent
```

Maven package 자체는 테스트를 건너뛰므로 후속 두 명령의 packaged-JAR 결과와 함께 해석해야 한다. timeout wrapper는 사용하지 않았다.

### 잔여 근사 / 성능 주장 범위

1. exact range가 없는 ROW/COL은 balanced share, unknown PART는 full worker work로 추정한다. sparse NNZ의 worker별 분포는 별도 통계가 없으면 geometry 비율을 사용한다.
2. aligned FED U/V factor의 reuse를 이번 fused 준비 모델에서 최적화하지 않는다. coordinator 준비와 필요한 payload를 보수적으로 계산하므로 일부 FED alternative는 실제보다 비쌀 수 있다.
3. codec concurrency 상한8과 response-size threshold4MiB는 기존 calibration을 공통 정책으로 정리한 값이다. 모든 환경에서8배 병렬 처리 또는4MiB runtime 분기를 보장하지 않는다. coordinator NIC/codec 경합 정밀화에는 같은 Docker 조건의 측정이 필요하다.
4. 이번 검증은 비용 산식·소유권·계획 합법성 회귀이다. **실측 runtime 개선율이나 모든 workload의 compile+planning20초 달성은 확인하지 않았다.** 실험 재개·commit·push는 하지 않았다.
5. 비용 재순위로 기존과 다른 합법 계획이 선택될 수 있다. 전체 workload 수치 정확성 및 성능 비교는 이후 같은 Docker 조건으로 해야 하며, unit 통과만으로 이를 대체하지 않는다.

### 정리 workflow 판정

- 삭제/통합: output-only min cap, 사후 compute floor, blanket unscaled 분기, GET 목적별 codec 산식 중복을 실제 quantities로 대체했다. 새 dependency 없음.
- 유지한 보수 추정은 external/layout 증거 부족의 비용 경계이며 runtime fallback이나 오류 무시가 아니다. 후보/Oracle/privacy/TR-TW/recompile gate를 완화하지 않았다.
- UI 항목은 해당 없음. 별도 security scanner는 수행하지 않았으며, production lint/typecheck·회귀·정적 diff·독립 코드 검토를 수행했다.


### 후속 R55 안내

위 R54 시점 잔여 항목2(fused aligned U/V reuse)와3(codec concurrency8/4MiB 구조 가정)은 후속 [R55 수정/검증 문서](COST_REUSE_TRANSPORT_REPAIR_2026-10-05_KO.md)에서 처리했다. 기존 R54 검증 기록은 역사적 증거로 보존하며, 현재 코드의 완료 범위·457개 최종 회귀·잔여 calibration/명시적 Quaternary 범위는 R55 문서를 기준으로 한다.
