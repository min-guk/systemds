# Heuristic 합법성 공통화 및 bounded selection repair

조사 보고서 `HEURISTIC_LEGALITY_PARITY_2026-10-06.md`의 후속 구현이다. 최신 fetch한 `origin/main` `825acfca9d`와 앞선 publication 수정·조사 `141439ee29`를 새 worktree `/home/mchoi/heuristic-legality-20261006`에서 병합했다. 시작 커밋은 `0ccf4c9c9f`이며 기존 workspace는 수정하지 않았다. 작업은 10월 6일 시작하여 7일에도 이어졌다.

## 구현한 경계

1. **Joint VALUE_MAP 합법성**: Exact가 사용하던 행별 worker-pool 정렬 predicate를 `JointValueMapRelations`로 공유한다. 완전한 후보 선택, normalization 및 emission 사전 검사에서 같은 조건을 확인한다. DIRECT 입력은 해당 control-flow row의 source pool을 사용하고, RELOCATION은 목적지 pool을 사용한다. LOCAL 입력의 collect/broadcast authority는 기존 검사에 맡긴다. correlated AA/BB를 허용하고 independent AB/BA를 거부한다. CP와 함수 forwarding은 실제 FED 커널 입력이 아니므로 정렬 의무가 없다. scalar×matrix처럼 FederationMap 입력이 하나인 경우에도 입력 간 정렬 조건은 항진이다. 이 마지막 조건은 기존 Exact에도 없던 과도한 제약을 함께 수정한다. Exact factor의 변수 scope와 observation decomposition은 바꾸지 않았다.
2. **WDIVMM 입력 제약**: latent/direct WDIVMM owner–weights 관계를 greedy의 기존 support index에 양방향으로 추가한다. 기존 `PlacementCostSemantics`의 실제 execution FType/derived-FOUT 조건을 사용하며, monetary objective나 Exact 탐색을 호출하지 않는다. 관계는 owner와 weights의 작은 범위를 유지한다.
3. **선택 단계의 제한된 복구**: 최초 선택은 기존 정책 순서대로 진행한다. support 충돌 또는 typed joint/grounding 충돌 시 삭제 trail을 역순 복구하고 다음 후보를 시도한다. 조건부 삭제뿐 아니라 support counter와 선택 상태를 복구한다. 기본 한도는 대체 분기 256회이며, 한도 소진과 지원 witness 미발견을 별도 예외로 보고한다. 둘 모두 전역 infeasibility 증명이 아니다. 일반 ownership/invariant 오류는 원래 예외로 전파한다. 런타임 fallback/repair는 추가하지 않았다.
4. **Runtime read–source 감사**: 독립 hard relation으로 이식하지 않는다. 명시적 업로드 `CP/LOUT → placement CP/FOUT → TW/read FED/FOUT`는 합법이다. 기존 cost resolver는 TW/placement를 지나 업로드 전 CP 원본까지 추적하므로 그 원본에 read compatibility 조건을 적용하면 합법적인 업로드를 거부한다. `RuntimeReadLegalityParityTest`가 exact upload authority와 업로드 후 alias layout 일치를 검증한다. 이 fixture는 binary consumer이며 WDIVMM cost factor의 실제 조립 경로까지 증명한 것은 아니다. 따라서 도달 가능한 cost-based 버그로 확정하지 않는다.

## 검증 범위와 한계

Java 17 Maven package 및 14개 클래스의 **80/80 회귀가 통과**했다. Heuristic continuation 9건, protected nested 3건, greedy/grounding 19건, joint/common/Exact 대조 9건, ALS 1건, runtime-read 1건, publication 10건, fixed point 12건, LogReg 2건, WDIVMM alignment/preparation 14건이다. 마지막 변경은 테스트용 budget 생성자의 `public` 노출을 package-private로 줄인 것이며, 이후 greedy/grounding **19/19와 package를 다시 통과**했다. 19건은 80건과 중복이므로 99건으로 가산하지 않는다. 최종 소스 manifest와 JAR/probe SHA를 확인했다.

대형 모델은 제외하고 repo의 `run_LAN_docker.sh --greedy-validation` 경로만 사용한다. PUBLIC metadata 대조와 PRIVATE_AGGREGATE 입력에 PUBLIC 보조 입력을 결합한 검사는 조사 보고서가 요구한 합법 LOCAL/movement 대안 보존을 검증하기 위한 것이다. privacy/TR-TW/geometry 제약을 완화하거나 새 ignore를 추가하지 않았다.

검증 중 실패 기록도 보존했다. 처음 추가한 WDIVMM parser fixture는 필요한 공유 U/V DAG가 없어 runtime fact를 찾지 못했고, 수동 DAG로 보정했다. federated TRead의 CP 상태를 가정한 음성 fixture는 합법 상태가 아니어서 제거했으며, 음성 WDIVMM end-to-end 검증이 성공했다고 주장하지 않는다. direct/latent의 합법 선택과 기존 alignment/preparation 회귀는 통과했다. 첫 통합 실행의 ALS 오류는 위 단일-map 정렬 조건을 수정해 해결했다. 같은 실행의 L2SVM 회귀는 selector에 도달하기 전 기존 closure 확장에 오래 걸려 중단했으며 통과 목록에 포함하지 않았다. 이후 통합 실행에서는 이 클래스를 제외했고 원래 로그와 thread dump를 남겼다.

소스·명령·결과 요약은 [implementation-validation.json](experiments/heuristic-legality-20261006/implementation-validation.json)에 기록한다. 원본 로그는 `/home/mchoi/heuristic-legality-20261006/.omx/heuristic-legality-evidence/`이며 greedy lane의 초기 fixture 이력은 `.omx/greedy-feasibility/fixture-attempts.md`다.

## Docker 결과

모두 동일 pinned image, container별 2 CPU/4 GiB, 외부 network 없이 실행했다. 독립 correctness container의 실행 시간은 일부 겹치므로 성능 A/B 자료로 사용하지 않는다. 모든 runtime run의 production source/POM 2,184개 SHA와 최종 JAR SHA가 일치한다.

| 검증 | 결과 |
| --- | --- |
| Heuristic 함수·루프 / mixed branch | 2/2, 독립 scalar oracle의 parameter 3개와 loss 일치 |
| correlated joint X / Y 분기 | 2/2, sum·sum of squares·shape 일치, 실제 FED 연산 |
| independent protected joint | 올바른 typed joint 원인으로 runtime program 전에 거부 |
| independent protected + public movement | `[168, 2104, 8, 2]` 정확히 일치, 합법 movement 대안 유지 |
| 소형 ALS CP / FedAll | 50×20, rank 10, 2회 반복; V 10×20의 200개 값 최대 오차 0.0, 출력 SHA 동일 |
| 기존 FedAll/Heuristic elementwise·nested·loop | 6/6 |
| no-conflict synthetic selector scaling | 512/4096/16384/65536 nodes × 3, 12/12 |

성공한 FED 실행에서 fallback/repair는 0/0이며 audit missing physical/synthetic 및 mismatch는 모두 0이다. ALS는 `fed_wdivmm` 44회와 실제 FED 산술 연산을 수행했고 physical lowering 74/74를 확인했다. Scaling의 candidate checks는 모든 크기에서 정확히 `2N`, support incidences는 `6N−6`, commits/deleted rows는 각각 `N`이다. 이는 충돌 없는 selector의 구조적 검사이며 전체 compiler의 선형 시간 보장이 아니다.

최종 joint artifact는 `/home/mchoi/heuristic-legality-20261006/target/fedpolicy-greedy-docker/heuristic-legality-run-kxd1tfyo/`, ALS는 `als-run-seokg1pb/`, 기존 smoke/scaling은 `run-4n823l_t/`다. 뒤의 두 경로도 같은 `target/fedpolicy-greedy-docker/` 아래에 있다.

첫 joint Docker 실행에서는 실제 independent rejection을 확인했지만 probe가 compile-time constant folding의 CP heavy hitters를 runtime으로 오인했다. `RewriteConstantFolding`은 컴파일 중 ProgramBlock을 실행하므로 raw heavy-hitter 합계 0을 요구하면 안 된다. 최종 probe는 정확한 joint 원인과 함께 runtime timer 0, FED 실행 없음, authority generation 0, fallback/repair 0을 요구하고 compiler heavy hitters를 별도로 남긴다. production JAR를 변경하지 않고 probe만 재컴파일해 6/6 재검증했다. 최초 artifact `heuristic-legality-run-m7a8gcch/`도 보존했다.

재현은 다음 세 명령을 사용한다.

```bash
bash scripts/fedplanner/run_LAN_docker.sh --greedy-validation --image sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434 --heuristic-legality
bash scripts/fedplanner/run_LAN_docker.sh --greedy-validation --image sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434 --als-only
bash scripts/fedplanner/run_LAN_docker.sh --greedy-validation --image sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434
```

보고서의 네 후속 항목은 구현 또는 검증에 근거한 미채택 결정까지 완료했다. Heuristic에는 합법성 제약과 선택 복구를 가져왔고, 비용 최적화나 원본 read/source에 대한 과도한 제약은 가져오지 않았다. Exact의 변수 scope·auxiliary encoding은 유지하며, 단일 물리 입력의 불필요한 joint 제약만 양쪽에서 바로잡았다.

bounded repair는 완전 탐색을 보장하지 않는다. 금전 비용 최적화, 모든 workload의 성공 또는 전체 실행시간 개선을 주장하지 않는다. Exact의 factor scope는 변경하지 않았으며, greedy에 dense joint table을 추가하지 않았다.
