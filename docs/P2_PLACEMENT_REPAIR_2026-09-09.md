# P2 전체 pipeline planning 수정·검증 보고서

- 일자: 2026-09-09
- 저장소: `/home/mchoi/g014-candidate-failfast-systemds-20260908`
- 결과: **전체 P2 planning 0/12 → 12/12 성공**.
- 범위: 4 planners × workers {1,3,5}. transformencode prefix만이 아니라 clipping → scale → split → aggregate checksum / metadata write까지 포함한다.
- Runtime 실험은 실행하지 않았다. 사용자 데이터 실행 시간은 12셀 모두 `0.000s`; Docker `network=none`, `BENCHMARK_COMPILE_ONLY=1`.

## 1. 수정 내용과 원인

### worker=1: 단일 partition 정보 전달
기존 `SinglePartitionFacts`는 matrix 위주로 사실을 전달하여 FRAME 및 실제 P2의 matrix→FRAME cast를 거치는 단일 partition 증명이 끊겼다. transformencode의 primary matrix까지 이 정보가 전달되지 않아 FULL + local companion 비교 연산을 허용할 근거가 사라졌다.

수정:
- literal FED FRAME의 정확한 단일 range를 matrix와 동일한 cardinality 근거로 사용한다.
- native matrix↔FRAME cast의 단일 endpoint 정보를 전달한다.
- 정확한 transformencode primary output만 FRAME 입력의 cardinality를 받는다. metadata 출력에는 전달하지 않는다.
- 동일 서버의 여러 range도 하나로 축약하지 않는다. FULL이라는 이름만으로 single partition을 가정하지 않는다.

### workers=3,5: clipping의 정확한 입력 공급 증명
최종 clipping `X = X0 - mask*X0 + mask*mu`의 마지막 덧셈은 두 ROW matrix를 소비한다. 기존 exact physical model에서는 이 두 입력을 연결할 충분한 authority가 없어 domain이 비었다.

실제 그래프에서 multi-return output carrier는 일반 compiled matrix input edge를 갖지 않는다. FRAME→transformencode call edge만 보고는 FRAME→FunOut X0→function output→TRead X0의 공급 관계를 증명할 수 없었다. 따라서 physical FRAME edge를 일반 허용하는 첫 시도는 실패했다(`first-fix.log`).

수정:
- builder가 이미 기록한 `DOMINATES / inputPosition=0 / multi-return-output-value` 중, 정확한 transformencode primary carrier와 그 FRAME 입력 identity가 일치하는 관계만 사용한다.
- 이 관계를 기존 function-output worker-pool resolver 및 native continuity 증명에 연결한다.
- matrix→FRAME cast의 native map 보존도 증명한다.
- 일반 multi-return control marker를 value alias로 바꾸지 않았다. `multi-return-primary-result`만으로 공급 권한을 발급하지 않는다.
- 두 입력 모두의 exact receipt를 요구하는 기존 physical model은 유지했다. 일반 ROW+ROW 또는 multi-input fallback을 추가하지 않았다.

### 왜 raw range를 그대로 복사하지 않는가
Dummycode는 열 수를 변경한다. 이번 수정은 X0에 원본 FRAME anchor를 붙이는 것이 아니라, worker-pool/partition-axis continuity를 증명한다.
- ROW: literal ids/recode/dummycode spec에서 행 구간과 endpoint를 보존한다. 행을 지우는 omit과 미확인 spec에는 이 증명을 사용하지 않는다.
- COL: 입력의 열 partition interval을 인코딩 후 그대로 재사용하지 않는다.
- FULL: source별 partition이 정확히 하나인 경우만 인정한다. runtime materialization의 범위는 원본 anchor 열 수가 아니라 실제 encoded 입력의 rlen/clen으로 생성된다. stale 10×2 anchor와 실제 10×5 값을 사용한 회귀 테스트로 확인했다.

## 2. Privacy 유지

- Xraw / X0 및 row-level 결과: **PRIVATE_AGGREGATE 유지**.
- 이전 단계에서 명시적으로 공개 승인한 recode metadata M만 PUBLIC이며 CP/LOUT이다.
- privacy 예외를 이번 placement 수정에 추가하지 않았다. 원본이나 encoded matrix를 PUBLIC으로 바꾸지 않았다.
- 회귀 테스트는 X0 occurrence에 직접 anchor가 없고, PA이며, legal output이 모두 FED/FOUT인 것을 확인한다.
- 전체 planning log에서도 X0 및 보호된 train/test carrier가 FED/FOUT임을 확인했다.
- 공개 승인을 끈 negative canary는 다시 `FunOut M (privacy=PRIVATE_AGGREGATE)`에서 거부된다. Java launcher의 returncode가 0이어도 Planner-Complete가 없으면 성공으로 세지 않는다.

## 3. 전체 P2 검증

기존 P2 데이터: raw features **100,000×1,001**, encoding 후 예상 1,050열. 최근 ML training의 50K×128과는 별도다. 보호된 raw data, 실제 P2 DML의 clipping/normalization/split 구간을 유지했다.

| Workers | FedFirst | AggLocal | COFEE-Global | COFEE-Regional | X0 layout |
|---:|:---:|:---:|:---:|:---:|:---:|
| 1 | PASS | PASS | PASS | PASS | FULL |
| 3 | PASS | PASS | PASS | PASS | ROW |
| 5 | PASS | PASS | PASS | PASS | ROW |

12/12에서 모두 확인:
1. 요청한 planner의 Invoke/Complete 기록 각각 1회.
2. 최종 runtime instruction program 생성.
3. FED transformencode 및 CP metadata write.
4. X0 FED/FOUT, M CP/LOUT.
5. 보호된 X/train/test carrier FED/FOUT.
6. execution timer 0.000s, compile-only.

Planning phase timer는 trace를 켠 진단 조건에서 약 0.218–0.627초였다. 공정한 planning 성능 비교용 반복 측정이 아니므로 planner 성능 우열의 근거로 사용하지 않는다.

## 4. 회귀/빌드 검증

- 수정 전 clipping domain 실패 재현: `red.log`.
- 최종 Maven focused suite: **118/118 PASS**, errors/failures/skips 0.
- 범위: SinglePartitionFacts, native continuity, function-return/cycle pool resolver, shared privacy/movement, metadata release, encoded clipping authority, FULL runtime geometry.
- 강화된 clipping 회귀: 두 ROW 입력 모두 source decision과 relocation authority가 존재함을 확인한다.
- negative coverage: multi-range cardinality, COL/omit/unresolved transform spec, metadata 비승계, generic control-marker 비승계, 공개 승인 off 및 PRIVATE 보호.
- Maven package 성공, `git diff --check` 성공.
- 새 JAR의 변경 production class bytecode와 최종 target/classes 일치 확인.
- 독립 code review: APPROVE, substantive blocker 없음.
- 전체 SystemDS 테스트 suite나 runtime payload 실행을 수행한 것으로 주장하지 않는다.

## 5. 변경 파일

Production (3):
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/SinglePartitionFacts.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java`

Regression (4):
- `P2EncodedClippingAuthorityTest.java`
- `SinglePartitionFactsTransformEncodeTest.java`
- `NativePlacementContinuityTransformEncodeTest.java`
- `FEDLocalMaterializeFullGeometryTest.java`

P2 placement-only diff는 시작 시점 `BASELINE.diff`를 기준으로 분리했다. Git 커밋에는 빌드·회귀 테스트의 선행 의존성인 metadata-only 공개 기능도 함께 포함한다. 무관한 기존 AggLocal/selector 변경은 제외한다. 아래 검증은 보고서의 frozen working-tree backend 기준이며, unrelated selector 변경을 제외한 커밋 단독의 성능 측정은 아니다.

## 6. 증거 경로

Root: `/home/mchoi/g014-p2-placement-repair-20260909`

- `P2_PLACEMENT_REPAIR.diff`: 이번 placement 수정과 회귀 테스트만의 diff.
- `PLANNING_RESULTS.json`: 12셀 완료 receipt, planner identity, runtime timer, 로그 경로.
- `LOWERING_PRIVACY_AUDIT.json`: 선택 상태와 최종 instruction 증거.
- `planning-attempt1/`: 셀별 Docker command, generated DML, 전체 fedplan log.
- `planning-release-disabled/receipt.json`: 공개 승인 off 차단 증거.
- `TEST_RESULTS.json`, `TEST_RESULTS/`, `final-tests.log`: 118개 최종 테스트 증거.
- `ARTIFACT_MANIFEST.json`: source/artifact hashes 및 backend 검증.

새 P2 backend SHA256:
`61edfd9bfc6ebdaf9f99e75b698d2d767a7d2ba4875ec27ae351c1f285a9b9d8`

기존 ML10 frozen backend SHA256(변경 없음):
`d0f7b232bde2a1f063da830056f59697184a33128aea35b9a3d3ff9bc3a48d9c`

## 7. 남은 범위 / 위험

- **이번에 확인한 전체 P2 planning blocker는 해결됐다. Runtime 성공·수치 정확성·실행시간은 아직 검증하지 않았다.**
- 다른 row-preserving transform spec은 현재 보수적인 whitelist 밖일 수 있다. 필요 시 runtime 근거와 별도 회귀로 확장해야 한다.
- metadata runtime validator 자체에는 privacy class 문맥이 없으며 PRIVATE와 PA 구분 강제는 기존 공통 planner가 담당한다. 이번 작업이 worker-side 보안 구조를 새로 구현한 것은 아니다.
- pool provenance와 exact value/range identity 혼동이 잠재 회귀 위험이다. X0 no-anchor, 두 입력 authority, stale-width FULL 및 COL/omit negative 테스트로 감지한다.
