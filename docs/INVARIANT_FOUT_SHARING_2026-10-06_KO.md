# Immutable FOUT staging의 REFED sharing 수정

최신 `origin/main` 통합 및 게시 검증은 [게시 보고서](DERIVED_SUPPLY_MAIN_PUBLICATION_2026-10-06_KO.md)를 참고한다.

## 조사와 설계

작업 위치는 `/home/mchoi/w1357-derived-supply-sharing-20261006`이다. 기준은
`origin/main`의 `eb64f9c939708735940f2ae095c5c8bd526decf7`과 이 worktree에서
이미 검증한 derived-supply-sharing 변경이다. 기존 작업 및 다른 실험은 보존한다.

현재 `ExactPhysicalCostModel`은 source의 실제 creation profile을 찾고도 FOUT이면
`freshStagedUploadSource`로 분리하여 consumer 실행마다 업로드를 과금한다.
`Dag.insertRefedLops`는 FOUT 원본 뒤에 별도 `prefetch`를 만들고,
`PrefetchCPInstruction`은 매번 새 local `MatrixObject`를 발행한다.
`FEDRefedInstruction`은 그 임시 객체를 owned-copy cache의 owner로 사용한다.
따라서 원본의 logical value/version이 변하지 않아도 creation lifetime이 끊긴다.

최소 수정은 **이미 선택된 FOUT→LOUT→FOUT 이동을 하나의 명시적 staged REFED
instruction으로 실행**하는 것이다. 원본 Lop가 직접 입력으로 남아 compiler liveness와
원본 MatrixObject identity가 유지된다. 원본에 별도 semantic definition을 만들거나
모든 MatrixObject에 staging provenance를 전파할 필요가 없다. 선택된 staging flag를
직렬화하고 audit에서도 이 결합된 이동을 검증한다. 일반 prefetch와 legacy REFED의
계약은 유지한다.

비용은 LOUT/FOUT 모두 기존 `creationSources`가 해석한 source profile과 exact
activation 관계로 계산한다. 반복 간 동일 값이 살아있다는 증거가 있을 때만 같은
derived sharing group을 부여한다. runtime은 동일 group과 source identity/version,
target layout/FType, thread scope를 이용한다. cache miss 때만 collect/upload하고,
hit에서는 기존 canonical copy의 독립 alias를 발행한다. single-use는 retain하지 않는다.

원본의 federation mapping도 source identity의 일부다. MatrixObject mutation version
외에 map identity, remote variable IDs, worker/range/FType를 확인한다. 생성 도중
source가 바뀌면 결과를 발행하지 않는다. FED map 변경 뒤 이전에 collect한 local bytes가
남아 있는 경우도 있으므로, federated read의 source witness가 달라지면 local cache를
무효화한다. 단순 materialization이나 nnz metadata의 확정은 새 semantic version으로
취급하지 않는다. DML FED 연산의 새 출력 ID/MatrixObject 및 acquireModify mutation
계약을 따른다. 외부 프로세스가 같은 remote ID의 값을 몰래 바꾸는 경우는 이 계약 밖이다.

Candidate 생성, legal relation, privacy, TW/TR 및 function binding 제약은 수정하지 않는다.
download와 upload의 비용 소유권도 유지한다. 별도 PREFETCH 실행을 했다고 audit에
기록하는 대신 명시적인 staged REFED의 실행을 기록한다.

## 검증 계획

- 생산 cost surface: invariant LOUT/FOUT 각각 1×, updated LOUT/FOUT 각각 N×.
- 실제 instruction: 같은 원본 반복, 원본 mutation/교체, remote ID/map 변경,
  layout/FType 변경, cleanup 및 source 변경 도중 publication 거부.
- Global/Local canonical recost, 기존 legality/boundary/privacy 회귀.
- `run_LAN_docker.sh`의 격리된 새 evidence 디렉터리에서 worker transport와 수치 결과 검증.
- 이전 보고서의 invariant FOUT staging N× 결과는 수정 전 baseline으로 보존한다.

## 결과

**구현과 검증을 완료했다.** Invariant FOUT 원본의 staging은 새 logical definition이
아니므로 반복 간 REFED copy를 공유한다. 실제 갱신된 값은 계속 매 버전마다 업로드한다.

### Representation과 수정 지점

| 코드 | 변경 |
|---|---|
| `ExactPhysicalCostModel`의 materialization demand 수집 | LOUT/FOUT upload mask를 합치고 실제 creation profile로 activation·공유 group을 계산한다. FOUT 전용 fresh-stage helper를 삭제했다. |
| `FederatedRefed`, `Dag.insertRefedLops` | `sharing=...`, `stage=true`를 직렬화한다. 원본을 직접 입력으로 유지하여 별도 PREFETCH output이 source owner가 되는 것을 없앤다. |
| `Dag.insertFoutMaterializeLops` | 이미 선택된 native LOUT→derived FOUT→REFED 경로에서는 REFED가 해당 FOUT materializer 결과를 받도록 연결한다. 중간 Lop의 LOUT 표시를 이유로 후보를 거부하지 않는다. |
| `FEDRefedInstruction.processInstruction` | 명시적으로 선택된 staged REFED만 FOUT을 collect/upload할 수 있다. 실제 FOUT source를 검사하고 기존 owned-copy 실행 경로를 사용한다. |
| `PlannerRuntimePlacementAudit` | 별도 PREFETCH가 있었다고 기록하지 않고 `REFED_STAGED`와 직렬화된 staging 표시를 대조한다. |
| `FederationUtils` | 원본 객체/version, 원본 FED map·remote IDs·배치, target layout/FType, thread 및 group을 구분한다. 생성/alias 발행 도중 source 변경을 거부한다. FED read의 nnz 확정은 새 버전으로 보지 않는다. |
| `CacheableData` | FED에서 collect한 bytes의 source witness를 기록한다. map/version이 달라지면 acquireRead/export에서 오래된 local bytes를 재사용하지 않는다. 함수 alias가 cleanup을 막아도 stale bytes는 무효화한다. |

별도 retained candidate, 범용 staging provenance 전파, content hashing, 새로운 planner
옵션은 추가하지 않았다. 동일 placement만으로 공유하지 않는다. 공유되지 않는 선택은
단일 생성 결과를 반환하고, 공유되는 선택만 원본 value lifetime까지 canonical copy를 유지한다.

### 비용과 실제 실행

3회 반복 fixture의 **upload 부분에 대한 production 모델 예측 비용**은 다음과 같다.
단위는 ms이며 실측 실행 시간이 아니다. GET 비용은 별도 contribution으로 검증했다.

| source | 수정 전 upload | 수정 후 upload | 수정 후 예측 비용 |
|---|---:|---:|---:|
| invariant LOUT | 1× | 1× | 1.002685546875 |
| updated LOUT | 3× | 3× | 3.008056640625 |
| invariant FOUT staging | 3× | **1×** | **1.002685546875** |
| updated FOUT staging | 3× | **3×** | **3.008056640625** |

Stable FOUT의 source collection 비용은 1.0013427734375ms, updated FOUT은
3.0040283203125ms였다. GET/PUT 두 contribution을 별도로 식별하여 중복 업로드로
오인하거나 두 이동을 한 비용으로 합치지 않도록 검사한다.

격리 Docker의 real-worker proof는 production cost surface에서 도출한 sharing 여부/group을
실제 `FederatedRefed` Lop과 파싱된 `FEDRefedInstruction`에 전달했다. 서로 다른 worker
pool에서 원본을 읽고 업로드한 뒤 모든 원소의 값을 비교했다. 매 실행 후 output alias를
실제로 삭제해 다음 실행의 공유 copy가 남아 있는지도 검사했다.

| real-worker proof, 3회 실행 | source GET_VAR | target PUT_VAR | 수치 결과/alias 삭제 |
|---|---:|---:|---|
| invariant FOUT | **1** | **1** | 통과 |
| 서로 다른 3개 FOUT value versions | **3** | **3** | 통과 |

이 proof는 공유 결정과 production movement instruction의 연동 검사다. 전체 DML
optimizer가 이 특정 공급을 최적안으로 선택했다는 주장은 하지 않는다. 별도의 DML E2E
12개 케이스에서 if/else, loop, function, L2SVM 및 privacy의 기대 결과를 확인했다.

### Candidate/factor 수

`stable`과 `updated`를 함께 사용하는 production loop model에 수정 전/후 cost model을
적용했다. 수정 전 소스는 작업 시작 시 저장한 `before.patch`에서 복원하여 별도 class
overlay로 컴파일했다. 두 측정은 같은 모델 및 의존성을 사용한다.

| 항목 | 수정 전 | 수정 후 |
|---|---:|---:|
| authority rows / exact witnesses | 535 | 535 |
| native candidates `a_v` | 130 | 130 |
| supply candidates `b_e` | 374 | 374 |
| physical decision variables | 45 | 45 |
| hard factors | 107 | 107 |
| canonical cost factors | 264 | **243** |
| encoded variables, 내부 auxiliary 포함 | 292 | **267** |
| encoded factors | 493 | **447** |

LOUT/FOUT upload를 별도 mask/factor로 나누던 구조가 줄었다. 이 수치는 planning 시간
벤치마크가 아니다. 기존 단일 matmul count fixture도 authority 33, native 26, supply 41,
hard factors 31로 동일했다. Loop-entry 유한 oracle의 raw 32,928 / admitted 292 /
physical 10도 그대로 통과했다.

### 회귀와 증거

- 원본 identity/cache 회귀: 수정 전 33 cases 중 7 failures → 수정 후 **33/33 통과**.
- 통합 Maven: **38 classes, 327 cases = 323 통과, 기존 skip 4, 실패/오류 0**.
- Global/Local: 같은 production loop cost surface에서 canonical objective bit,
  derived lifetime set, 최종 selected receipt 검증 통과.
- Docker model proof: **12/12**. 기존 모델 proof 10개와 새 real-worker proof 2개다.
- Docker DML E2E: **12/12 기대 결과 충족**, class preflight 통과,
  계획 밖 runtime conversion 위반 0.
- Maven test-compile 및 `git diff --check` 통과. 검증에 사용한 frozen artifact와 현재
  main/test source 및 class 파일의 SHA-256 inventory가 모두 일치했다.

Local evidence: `.omx/invariant-fout-sharing/`의 `regression-results.json`,
`baseline-red.log`, `foundation-green.log`, `loop-candidate-factor-counts.json`,
`docker-model-proof.log`, `docker-result.json`, `frozen-source-class-match.json`.

Docker evidence:
`/grid/3/cofee-lm-sweep-mchoi-20260914/invariant-fout-sharing-20261006/invariant-fout-sharing-r1/`.
실행 entrypoint는 `scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e`이며,
고정 image, 독립 container/loopback worker 및 새로운 output/stage 경로를 사용했다.

### 범위와 남은 한계

공유 copy의 종료 시점은 기존과 같이 원본 value의 invalidation/removal이다.
정확한 마지막 consumer에서 조기 해제하는 기능이나 shared-memory budget 최적화는
추가하지 않았다. 모든 movement를 영구 retain하지 않는다.

정상 DML FED 연산은 새 정의를 새 output ID/owner 또는 mutation version으로 표시한다.
Binary/Index/Reorg와 점검한 Ctable/Decode/cumulative 경로도 이 계약을 따른다.
외부 코드가 같은 remote ID의 bytes를 이 계약 밖에서 직접 덮어쓰는 상황까지 검출하는
분산 mutation protocol은 구현하지 않았다. 기존 보고서의 별도 baseline oracle 실패도
이번 수정으로 해결했다고 주장하지 않는다.
