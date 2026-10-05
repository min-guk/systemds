# FULL profile/caps 및 W3 Y 재전송 검증

## 결론

1. **FULL capability와 타입 전파 결과의 불일치는 실제 R35 JAR에서 재현됐다.** 단일 partition 증명이 있는 FULL×FULL, FULL×local, local×FULL은 `caps=FED/FOUT/FULL`이지만 `profile=[]`다. profile을 사용하는 anchor 확인은 FULL을 거부한다. 다만 native FULL/FOUT 후보는 별도 caps 경로에서 살아 있다.
2. **변하지 않는 Y라도 임시 FED 복사본이 삭제되면 재사용 캐시가 사라지는 경로를 재현했다.** 현재 W3 로그의 Y 재전송과 결합하면, per-fit 임시 복사본의 수명이 반복 업로드를 설명하는 강한 근거다. 원본 로그에 매 fit의 cache key/purge event는 없으므로 모든 miss 원인을 개별 추적했다고 주장하지 않는다.
3. **이번 검증은 수정이나 성능 개선 실험이 아니다.** 진행 중인 run11, production 코드/JAR, privacy 규칙, cleanup, timeout 정책은 바꾸지 않았다. 비용 과소평가나 FULL 불일치가 특정 최종 계획을 잃게 했는지는 별도 미확인 항목이다.

## 검증 환경과 경계

- 실험 엔진: run11의 격리 R35 DS JAR, SHA-256 `ff8d638ec2c9709fd194c8d946983cd05e974273a71b2a8f7e00f714c2538772`.
- 기존 main R37/CG/target과 활성 run11 worktree에는 쓰지 않았다.
- 새 Java probe/단위 테스트만 **현재 실험에 참여하지 않는 so009의 CPU 40**에서 Java 17로 실행했다. outbound cleanup은 기존 repository test와 같은 counting-map 방식으로 대체했다. 실제 worker를 기동하거나 workload runtime을 실행하지 않았다.
- compiler 출력·로그는 별도 `/home/mchoi/w1357-unit-verification/full-yrefed-20261002`에만 썼다. Maven 전체 build, 기존 target overwrite, 새 의존성 설치는 없다.
- planner fixture는 PRIVATE_AGGREGATE 입력을 사용한다. Y 수명 테스트는 기존 private-aggregate X / public Y workload의 캐시 메커니즘 검증이며 public-only workload 실험이 아니다.
- 소스/결과 보존 root: `/grid/3/cofee-lm-sweep-mchoi-20260914/w1357-full-yrefed-verification-20261002`.

## 1. FULL 직접 실행 검증

`full-oracle/FullOracleProbe.java`가 production `BinaryMMRule.profile/caps`, `oracleConfirmsAnchorDomain`, `NeutralPlacementGraphBuilder`를 호출한다.

| 입력 / 조건 | caps | profile | 관측 |
|---|---|---|---|
| FULL×FULL / single-partition proof | FED/FOUT/FULL | `[]` | 불일치 |
| FULL×local / single-partition proof | FED/FOUT/FULL | `[]` | 불일치 |
| local×FULL / single-partition proof | FED/FOUT/FULL | `[]` | 불일치 |
| FULL×local / proof 없음 | FED/LOUT | `[]` | FULL/FOUT은 허용되지 않음 |
| ROW×local | FED/FOUT/ROW | `[ROW]` | 일치 |

- anchor 확인 직접 호출: **FULL=false, ROW=true**.
- private-aggregate-input DML fixture의 MM 결과 privacy는 `PRIVATE_AGGREGATE_TO_PUBLIC`; native rule fact는 `AVAILABLE`, caps `FED/FOUT/FULL`, profile `[]`.
- **non-derived native FULL/FOUT emission 1개**, realization 2개가 남아 있음을 assertion으로 확인했다. 따라서 FULL MM 전체 미지원 또는 모든 후보 소실이 아니다.
- characterization probe는 exit 0. 별도의 `--require-parity` 가설 검사는 불일치 3개 때문에 의도대로 `AssertionError`, exit 1이다. 이 RED를 production 수정 후 GREEN으로 바꾼 것은 아니다.

### 관련 R35 소스

- `src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java:2880–2901,2918–2945`: 일반 MM profile과 FULL caps.
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java:214–224,296–300`: native caps emission과 profile 기반 anchor 확인은 별도 경로다.
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:2132–2138,2513–2515`: profile 결과를 사용하는 재진입/anchor 전파 경계.
- `src/main/java/org/apache/sysds/hops/fedplanner/rules/RulesApi.java:262,400–407`: profile은 타입 전파 helper이며 `profile ⊇ caps`라는 명시적인 API 완전성 계약은 없다.

**판정 한계:** capability/type-discovery 불일치와 anchor predicate의 거부는 확정이다. 특정 STEP-LM의 합법적인 완성 계획이 실제로 사라졌거나 이 불일치가 W1 시간을 늘렸다는 증거는 아니다. 테스트가 직접 지정한 privacy-policy 입력도 모든 MM의 자동 declassification을 증명하지 않는다.

## 2. 실제 W3에서 무엇을 재전송하는가

원본: run11 `attempts/runtime/01790905470116241172-006748cf`의 STEP-LM / DP-local / W3 / WAN-light, PASS.

- `coordinator.log:175337`: formal `TRead y`의 선택은 CP/LOUT.
- `:579,589,599`: 같은 Y의 ROW REFED가 `lmDS.dml:94`, `b=t(X)%*%y`의 입력으로 투영된다.
- `:488934,500752`: 서로 다른 refed audit key에서 MatrixBlock PUT 3개씩 발생한다.
- 전체 synthetic lowering 기록: LOCAL **1**, REFED **8259**, REFED_LOCAL **0**. 이 수치는 실행 횟수와 다르다.
- 실제 runtime: `fed_fed_refed` **8257회 / 120.272초**; Matrix PUT **33028회 / 3,310,683,352 bytes**.
- W1의 Matrix PUT 8257회를 제외한 추가 횟수 **24771 = 3 workers × 8257 fits**.
- 매 fit의 dense Y 값은 `50000 × 1 × 8` bytes이므로 `8257 × 400000 = 3,302,800,000` bytes. W3의 대부분 PUT 데이터량과 대응한다. 전체 wire traffic으로 해석하면 안 된다.

즉, 이 로그는 Y의 explicit LOCAL 단계가 fit마다 새로 삽입됐다는 설명보다 **한 번 local로 확보한 Y를 반복해서 worker로 올리는 선택**을 뒷받침한다.

## 3. 재사용이 끊기는 수명 관리 검증 — 8/8 PASS

`y-reuse/R35YRefedLifetimeTest.java`, JUnit 4.13.1:

1. 원본 local Y의 객체·mutation version을 유지해도, 마지막 임시 FED 변수의 `rmvar`는 worker cleanup과 cache miss를 만든다.
2. FED 임시 변수의 alias가 남으면 cache hit가 유지되고, 마지막 alias를 지울 때만 purge된다.
3. anchor map ID만 바뀌고 semantic layout이 같으면 hit한다.
4. 입력의 stable filename/version이 같으면 새로운 wrapper UID만으로는 miss하지 않는다. 같은 filename의 임의 객체가 의미적으로 동일하다는 증명은 아니다.
5. mutation version 변화는 miss시킨다.
6. production cleanup request 작성은 대상 FED ID의 cache만 purge한다. 이 테스트는 네트워크 요청을 실행하지 않는다.
7. 같은 Y/layout으로 세 번의 register/use/temporary-cleanup 수명 주기를 구성하면 매번 miss한다. 실제 upload를 세 번 실행한 테스트는 아니다.
8. production `Dag.collectFedInputLabels/processConsumers`는 synthetic REFED를 보호하지 않고 마지막 소비 후 `rmvar`를 생성한다. 생성된 실제 instruction을 ExecutionContext에서 실행하면 캐시가 purge된다.

### 생산 코드 경로

```text
local Y 유지
  → fit 내부 synthetic FED Y 생성 및 reuse cache 등록
  → 마지막 consumer 뒤 rmvar(또는 함수 반환 시 nonreturn 변수 정리)
  → ExecutionContext.cleanupFederatedData
  → reuse cache purge + 해당 worker 변수 삭제
  → 다음 fit의 동일한 Y/layout도 기존 worker 복사본 재사용 불가
```

- `src/main/java/org/apache/sysds/lops/compile/Dag.java:688–701,2017–2040,2058–2075`.
- `src/main/java/org/apache/sysds/runtime/instructions/cp/FunctionCallCPInstruction.java:222–231`.
- `src/main/java/org/apache/sysds/runtime/controlprogram/context/ExecutionContext.java:899–912,949–952`.
- `src/main/java/org/apache/sysds/runtime/instructions/fed/FEDRefedInstruction.java:198–252`.
- `src/main/java/org/apache/sysds/runtime/controlprogram/federated/FederationUtils.java:164–200`.

**확정 범위:** 재현한 임시 변수 수명에서 production lowering/삭제/cache invalidation이 재사용을 끊는 메커니즘은 확인했다. 실제 W3 audit와 반복 횟수는 그 적용을 강하게 뒷받침한다. 테스트의 consumer/consumer count는 구성한 fixture이며 실제 STEP-LM 전체 lowering을 재실행한 것은 아니다. 기존 로그에 없는 각 fit의 key 변화와 purge 시각, 추가 miss 원인은 미확인이다.

## 검증 산출물·재현

- `PLAN.md`, `before.json`: 범위 및 변경 전 identity.
- `full-oracle/FullOracleProbe.java`, `y-reuse/R35YRefedLifetimeTest.java`: production을 고치지 않는 probe/test.
- `run-verification-so009.sh`: pinned JAR hash 검사, 격리 javac/JUnit/characterization/expected-RED 명령.
- `so009-logs/`: input SHA, compile 결과, 8-test PASS, FULL characterization, parity RED 및 exit code.
- `y-reuse/verify-archived-y.py`: 보존 W3 로그의 실제 증거를 재검증하는 script.
- `y-reuse/w3-audit-samples.json`, `observed-repeat-transfer.json`, `w3-runtime-statistics.txt`: 원본 log SHA/행 번호/전송 계측.
- `after.json`, `independent-review.json`: 변경 없음 및 독립 검토 증거.

## 잔여 이슈와 수정 시 주의점

- FULL profile 보완은 single-partition/shape proof 및 실제 runtime 지원 범위를 유지해야 한다. 모든 FULL을 무조건 통과시키면 안 된다.
- Y 재사용 개선은 planner가 정한 공유 materialization의 수명·소유권과 cleanup을 함께 다뤄야 한다. cache purge만 제거하거나 죽은 worker ID를 재사용하는 수정은 잘못이다.
- 현재 cost가 한 번의 공유 업로드와 매-fit 업로드를 정확히 구별해 평가하는지, 원래 FED Y 유지 후보가 왜 선택되지 않았는지는 이번 검증으로 확정하지 않았다.
- 성능 영향, 수정 후 정확성, 다른 workload 회귀는 아직 검증하지 않았다. 진행 중인 cohort의 엔진을 조용히 바꾸지 않는다.
