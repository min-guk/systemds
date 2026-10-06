# Derived supply sharing의 자동 DML 실행 검증과 메모리 확인

남은 작업은 두 가지다. 첫째, 실제 DML에서 optimizer가 공유 가능한 공급을 자동으로 선택하고 runtime이 그 선택대로 실행하는 사례를 회귀 테스트로 고정한다. 둘째, 공유 copy를 유지하는 동안의 메모리 사용량과 실제 성능 영향을 측정한다. 현재 공유 표현을 다시 설계하거나 메모리 최적화를 새로운 candidate 선택 차원으로 추가할 단계는 아니다.

코드 기준은 `ada24ffd4be4d17240f32f5bd1c2b5a98e1a0c8a`이며, 작업 위치는 `/home/mchoi/w1357-derived-supply-sharing-20261006`이다. 아래의 기존 검증 결과는 이 통합본의 기록이고, 추가 검증 항목은 앞으로 고정해야 할 성공 기준이다.

**앞선 메모리 설명은 정정이 필요하다.** 현재 planner가 공유하도록 정한 copy는 기존 REFED 캐시의 LRU 퇴출 대상에서 제외된다. 따라서 이번 경로에서 우선 확인할 것은 퇴출 후 반복 업로드보다, 업로드를 한 번으로 유지하기 위해 copy를 얼마나 오래 보관하고 그 보관이 메모리에 어떤 영향을 주는가이다. 기존 opportunistic cache의 퇴출·재생성과 planned sharing의 유지 정책은 구분해야 한다.

**자동 DML 검증에서는 계획 선택과 실행을 하나의 사례로 연결해야 한다.**

현재 검증은 다음과 같이 나뉜다.

| 검증 | 확인한 내용 | 해당 검증의 범위 |
|---|---|---|
| Production 비용 모델 | invariant source의 이동 비용은 1회, updated source는 N회 | 특정 공급 후보의 비용과 공유 lifetime을 검사한다. |
| 실제 worker proof | 3회 실행에서 invariant FOUT은 source GET 1회·target PUT 1회, updated FOUT은 각각 3회 | 테스트가 구성한 staged REFED instruction을 실제 worker에서 실행한다. |
| Global 및 Local optimizer | 각 선택 결과의 비용, canonical recost, derived lifetime, selected receipt 일치 | 해당 선택 결과로 전체 DML을 실행해 1회·N회를 입증하는 테스트는 아니다. |
| 기존 Docker DML E2E | 13개 사례의 기대 결과 충족, 계획 밖 runtime conversion 0 | 여러 연산·경계·privacy 동작을 검사하지만 이번 공유 경로의 자동 선택과 횟수를 함께 고정하지는 않는다. |

비용 fixture 자체는 DML을 파싱하고 HOP 및 placement analysis를 거쳐 production physical model과 cost surface를 만든다. 이후 `forcedUpload`가 특정 source·consumer 후보를 지정해 해당 공급의 비용을 검사한다. 이 지정은 optimizer의 자동 선택 결과나 완전한 계획 전체의 합법성을 증명하는 결과로 해석하면 안 된다. [비용 fixture와 후보 지정](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCompiledSupplySharingTest.java#L93)

Worker proof는 그 공급에서 도출한 sharing group을 실제 `FederatedRefed` Lop에 넣고 instruction으로 직렬화·파싱한다. Java의 반복문에서 instruction을 세 번 실행하고, 매번 수치 결과와 output alias 정리를 확인한다. 따라서 compiler의 공유 결정과 production movement instruction의 연동은 검증됐지만, 원래 DML의 optimizer 선택부터 DML loop 실행까지가 하나의 검증으로 연결된 것은 아니다. [실제 worker proof](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/InvariantFoutSharingDockerProof.java#L86)

Global/Local 테스트는 실제 optimizer를 호출하고 각 결과가 canonical 비용 및 lifetime과 일치하는지 검사한다. 두 optimizer가 모든 입력에서 같은 계획이나 같은 비용을 선택한다는 의미는 아니다. [Global 및 Local 검증](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactStagedSupplyCanonicalCostTest.java#L28)

추가할 회귀 테스트는 다음 전체 경로를 실행해야 한다.

```text
실제 DML 입력
  → 파싱 및 source/version 분석
  → physical candidate와 비용 생성
  → optimizer가 공유 가능한 staged REFED 공급을 자동 선택
  → 선택 결과로 instruction 생성
  → 실제 DML loop 실행
  → 공급별 이동 횟수, 수치 결과, copy 정리 확인
```

예를 들어 worker A의 FOUT 값 `X`를 worker B의 연산에서 세 번 사용한다고 하자. `A → local staging → B` 이동이 privacy와 boundary 제약상 합법적이고, 세 demand의 target state/layout이 호환되며 source가 필요한 기간 살아 있다고 가정한다.

| source 상태 | 첫 번째 iteration | 두 번째 iteration | 세 번째 iteration | 해당 공급의 총 업로드 |
|---|---|---|---|---:|
| 같은 logical version의 `X` | collect 후 upload | 기존 FED copy 사용 | 기존 FED copy 사용 | 1회 |
| 매번 새로운 `X_i` | `X₁` collect 후 upload | `X₂` collect 후 upload | `X₃` collect 후 upload | 3회 |

로컬 staging 과정에서 임시 Java 객체를 만들더라도 그것만으로 새 logical version이 되는 것은 아니다. 반대로 값의 갱신, source 교체, mutation, 호환되지 않는 target layout/FType는 공유를 허용하는 근거가 되지 않는다. 이 구분을 자동으로 생성된 계획과 instruction에서도 보존하는지가 검증의 핵심이다.

테스트는 다음 조건을 함께 만족해야 한다.

1. 후보 인덱스를 강제로 지정하거나 테스트에서 sharing group을 주입하지 않고, optimizer의 실제 선택 결과에서 staged REFED 공급과 공유 정보를 확인한다.
2. Instruction 생성 및 적용되는 재컴파일 경로에서 그 정보가 유지되고, runtime이 계획 밖 이동을 추가하지 않는다.
3. Invariant 사례는 해당 공급의 GET/PUT이 각각 1회이고, 실제 갱신 사례는 각 version을 별도로 공급해 N회가 된다. 입력 준비와 결과 확인용 통신은 별도로 집계한다.
4. Iteration의 output alias를 정리한 뒤에도 필요한 공유 copy가 유지되고, source가 변경·제거되면 이전 copy가 재사용되지 않는다.
5. 각 optimizer의 선택 비용과 canonical recost가 일치하고, GET과 PUT의 소유권 및 중복 과금 여부를 확인한다.
6. 계산 결과가 기준 결과와 일치한다. 테스트의 연산이 loop 밖으로 이동하거나 제거되어 이동 횟수만 우연히 줄어든 경우는 이번 기능의 검증으로 계산하지 않는다.

**자동 선택 검증을 위해서는 해당 공급이 실제로 선택되는 fixture가 필요하다.** CP 실행이나 다른 공급이 더 저렴해서 선택됐다면 정상적인 최적화일 수 있지만 staged REFED sharing의 검증 사례는 되지 않는다. 합법적인 후보를 제거하거나 privacy를 완화하지 않고, 실제 배치·크기·비용 조건에서 의도한 공급이 선택되는 작은 DML을 고정해야 한다. Global과 Local은 각각 선택 결과를 검사하며, 임의의 모든 DML에서 두 계획이 같아야 한다는 조건을 추가하지 않는다.

이 검증은 선택된 공유 정보가 lowering 또는 재컴파일에서 사라지는 문제, 원본의 조기 정리, runtime 이동과 비용의 불일치를 한 경로에서 감지한다. 이들은 추가 테스트가 감지할 실패 유형이며, 현재 모두 발생한다고 확인된 오류는 아니다.

**메모리 검증의 출발점은 planned sharing과 일반 캐시를 구분하는 것이다.**

| 공급 경로 | 현재 유지 정책 | 일반 REFED LRU/용량 퇴출 |
|---|---|---|
| 공유가 없는 selected supply | 결과의 일반적인 consumer lifetime을 따른다. source에 공유용 canonical copy를 별도로 유지하지 않는다. | 공유 cache entry를 만들지 않는다. |
| Planner가 sharing group을 도출한 supply | canonical copy를 source의 무효화·제거까지 유지한다. 각 사용에는 독립 alias를 발행한다. | 퇴출 대상에서 제외한다. |
| 기존 opportunistic cache | 제한된 캐시에 copy를 보관하고 필요하면 다시 생성한다. | LRU/용량 정책으로 퇴출할 수 있다. |

`materializePlannedRefed`는 비어 있지 않은 sharing group에 대해 source가 소유하는 공유 copy를 유지한다. `evictOwnedRefedEntries`는 `_planned` 항목을 건너뛴다. source 정리나 명시적인 cache clear 등으로 수명이 끝나는 경로는 별도로 존재한다. [공유 copy 생성·유지](../src/main/java/org/apache/sysds/runtime/controlprogram/federated/FederationUtils.java#L135), [LRU 제외 처리](../src/main/java/org/apache/sysds/runtime/controlprogram/federated/FederationUtils.java#L297)

`plannedSharingSurvivesLegacyCacheBudgetUntilSourceCleanup`는 일반 cache budget을 넘는 planned copy도 재요청 시 재생성하지 않고, source 정리 때 해제되는지 검사한다. 반면 `evictedCopyIsRecreatedWhenItsOriginalOwnerRequestsItAgain`은 기존 opportunistic 경로의 퇴출·재생성을 검사한다. 두 결과를 같은 정책의 증거로 합치면 안 된다. [Planned copy 유지 테스트](../src/test/java/org/apache/sysds/runtime/controlprogram/federated/OwnedRefedReuseTest.java#L210), [일반 cache 재생성 테스트](../src/test/java/org/apache/sysds/runtime/controlprogram/federated/OwnedRefedReuseTest.java#L482)

기본 64 MiB인 `sysds.fed.refed.reuse.cache.bytes`는 planned shared copy 전체의 메모리 상한이 아니다. 이 값을 낮추기만 해서는 planned copy의 퇴출을 유도하는 실험이 되지 않는다. Planned entry 때문에 총 추정 보관량이 이 예산을 넘은 상태가 유지될 수도 있다.

현재 정책은 한 번으로 과금한 creation을 공유 lifetime 동안 재사용하도록 한다. 그 대가로 copy가 차지하는 데이터와 참조를 유지한다. 특히 source의 마지막 사용과 source 제거 시점 사이에 간격이 있으면 필요한 사용이 끝난 copy도 더 오래 살아 있을 수 있다.

```text
공유 copy 생성
  → loop에서 반복 사용
  → 공유 copy의 마지막 사용
  → 다른 큰 연산 수행
  → source 제거와 함께 공유 copy 정리
```

정확한 마지막 consumer 직후에 해제하는 기능은 현재 추가하지 않았다. 위 흐름에서 마지막 사용부터 source 제거까지의 유지 시간이 주요 측정 대상이다. 모든 movement 결과를 무조건 보관하는 것은 아니며, selected demands에서 공유가 도출된 copy가 대상이다.

가정상 같은 worker에 서로 다른 100 MiB 공유 copy 여섯 개가 동시에 살아 있다면, copy 데이터만 600 MiB다. 원본과 연산 중간 결과는 여기에 더해진다. 이 수치는 설명용 계산이며 실제 실험 결과가 아니다. 여러 alias는 같은 canonical 데이터를 가리킬 수 있으므로 alias ID 수를 독립적인 전체 데이터 copy 수로 세면 안 된다.

**REFED 캐시에서 copy를 삭제하지 않는 것과 worker의 데이터가 항상 RAM에 상주하는 것은 다르다.** Planned entry의 LRU 제외는 coordinator의 copy 소유·유지 정책이다. worker의 데이터 관리에서는 별도의 spill과 복원이 발생할 수 있다. 이 경우 업로드는 한 번이어도 디스크 I/O, GC, 메모리 대기로 실행 시간이 늘어날 수 있다. 따라서 GET/PUT 횟수의 일치만으로 전체 실행 시간 예측이 정확하다고 판단할 수 없다.

메모리 실험은 다음 항목을 함께 기록해야 한다.

| 측정 항목 | 확인할 질문 |
|---|---|
| 공급별 creation·GET·PUT·cache hit/miss | 한 번으로 계산한 이동이 실제로도 한 번인가? |
| 동시에 살아 있는 canonical copy 수와 추정 bytes | 어느 source/version과 target layout이 메모리를 점유하는가? |
| 마지막 사용과 해제 시점 | 사용이 끝난 copy가 얼마나 오래 유지되는가? |
| 해제·재생성 사유 | source 변경, source 제거, 명시적 clear, 일반 LRU 퇴출을 구분할 수 있는가? |
| Worker와 coordinator 각각의 최대 메모리 | remote copy와 local staging의 부담이 어디에 발생하는가? |
| GC, spill 및 복원 I/O, 전체 실행 시간 | 이동 횟수에 드러나지 않는 비용이 증가하는가? |

Source가 정리된 뒤에는 canonical remote ID와 소유 참조가 해제되는지도 확인한다. JVM의 RSS가 즉시 줄어드는지만으로 copy 정리 성공 여부를 판단하지 않는다. 메모리가 해제되어도 JVM이 확보한 메모리를 즉시 운영체제에 돌려주지는 않을 수 있기 때문이다.

**다음 작업은 작은 자동 E2E를 먼저 고정하고, 같은 실행의 크기와 동시 보관량을 늘리는 순서가 적절하다.**

1. Invariant와 updated DML을 각각 자동 선택부터 실제 loop까지 실행하는 회귀 테스트로 만든다. 선택된 공급, canonical 비용, 실제 통신 횟수, 수치 결과를 함께 기록한다.
2. 같은 논리 구조에서 데이터 크기와 동시에 살아 있는 source·target copy 수를 늘린다. 고정된 Docker 조건에서 worker별 메모리와 spill·GC·실행 시간을 측정한다. 기존 workspace와 실행 중인 실험은 보존하고 `scripts/fedplanner/run_LAN_docker.sh`의 새 실행 경로를 사용한다.
3. 관측된 원인에 맞춰 필요한 변경만 추가한다.

| 관측 결과 | 다음 판단 |
|---|---|
| 공유가 자동 선택되고 메모리·실행 시간 부담도 작음 | 현재 구조를 유지하고 해당 사례를 회귀 테스트로 남긴다. |
| 마지막 사용 이후 보관 시간이 과도함 | 먼저 last-consumer 이후의 안전한 조기 해제를 검토한다. |
| 사용 중인 공유 copy 자체가 메모리 한도를 압박함 | 실제 residency와 생성·재생성 비용을 반영하는 정책을 검토한다. |
| Planned copy가 일반 LRU 때문에 재생성됨 | 현재 유지 계약에 어긋나는 동작으로 조사한다. |
| 값이 갱신돼 N회 생성됨 | 올바른 version 분리다. cache 퇴출 문제로 분류하지 않는다. |

향후 planned copy의 메모리 예산에 따른 퇴출을 허용한다면, 재생성 횟수와 비용도 함께 다뤄야 한다. Runtime에서만 조용히 퇴출하고 정적 모델에서는 계속 한 번으로 계산하는 방식은 비용과 실행의 일관성을 깨뜨린다. 측정 전에 retained 여부를 별도 candidate로 되돌리거나 복잡한 메모리 탐색 차원을 추가할 필요는 없다.

추가 검증과 후속 수정에서도 source/version·layout/FType 호환성, privacy, TW/TR 및 function boundary 제약, operator와 explicit movement의 비용 소유권을 유지한다. 현재 구현 및 기존 검증의 상세 기록은 [FOUT staging 수정 보고서](INVARIANT_FOUT_SHARING_2026-10-06_KO.md)와 [main 게시 검증 보고서](DERIVED_SUPPLY_MAIN_PUBLICATION_2026-10-06_KO.md)에 있다.
