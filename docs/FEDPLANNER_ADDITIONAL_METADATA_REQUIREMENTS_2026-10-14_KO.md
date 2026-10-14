# FedPlanner에 추가하면 유용한 metadata — SliceLine 중심

작성일: 2026-10-14

상태: **요구사항·설계 제안 문서화 완료. 신규 metadata 생성, planner 연결 구현, workload 측정은 수행하지 않았다.**

## 1. 결론과 범위

가장 먼저 추가할 것은 **열별 인코딩 정보**와 **그 정보가 현재 입력에 대응한다는 근거**다. 모든 중간 결과의 크기를 사전에 수집하거나, planner가 본 workload를 먼저 실행하도록 할 필요는 없다.

SliceLine의 최소 권장 구성은 다음 네 가지다.

1. 열별 encoding 계약과 원본→출력 열 대응표
2. 전처리에서 이미 계산한 열별 최대 코드 `fdom`
3. X/e의 행 정렬 관계와 error 값의 의미
4. 입력 identity, 정확성 수준, 적용 범위, 공개 정책을 연결한 metadata receipt

이 구성은 one-hot 크기와 기본적인 shape bound를 파악하는 데 도움이 된다. 이후 pruning·후보 생성·중복 제거 결과의 정확한 크기까지 알려 주지는 않는다. 비용 추정에 추가 정보가 필요한 경우에만 category 빈도 등의 통계를 검토한다.

이 문서는 SliceLine용 metadata 요구사항 제안이다. 별도로 진행 중인 [P2 metadata-assisted compilation 계약](FEDPLANNER_P2_METADATA_ASSISTED_COMPILATION_CONTRACT_2026-10-14_KO.md)의 **요청별 inline 준비, persistent artifact/cache 미도입** 결정을 변경하지 않는다. SliceLine에서 기존 입력 준비 결과를 보존하자는 제안과, P2에서 worker encoder build를 실행하는 준비 단계는 구분한다.

## 2. 현재 입력에 있는 정보와 빠진 정보

보존된 SliceLine 입력 sidecar에는 `rows`, `cols`, `nnz`, data/value type, binary format, block 크기, privacy 등이 있다. 예를 들어 ADULT X는 32,561×13, COVTYPE X는 581,012×54로 기록되어 있다. 이는 보존된 입력 metadata의 값이며, 모든 신규 실행의 입력이 동일하다고 주장하는 것은 아니다.

현재 `MemoSources`는 실제 source의 worker/path/range, 전체 rows/columns, privacy를 확보한다. 따라서 추가 metadata가 기존 FederationMap을 대신하거나, 실제 partition을 임의의 균등 분할로 바꾸어서는 안 된다.

부족한 핵심 정보는 다음과 같다.

- 각 열이 일반 숫자인지, category code인지, bin 결과인지
- category code의 범위, 코드가 연속적인지, 결측값을 어떻게 표현하는지
- 입력에 적용된 변환과 현재 열의 대응 관계
- feature X와 error e가 동일한 행 순서를 공유한다는 근거
- 통계가 정확한 값인지, 보장된 범위인지, 추정치인지

**행렬의 rows/cols/nnz만으로 열별 category 수나 최대 코드를 알아낼 수는 없다.**

근거: [보존된 입력 metadata](https://github.com/min-guk/cofee-evaluation/blob/66b9383acfcf5a3ebe901032b7149ddf0e4e5c3b/planning_study/native/input_templates/w3/metadata/inputs.json), [MemoSources](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoSources.java).

## 3. 우선 추가할 metadata

아래 필드명은 **설명용 제안**이며, 현재 지원되는 sidecar schema나 API 이름이 아니다.

| 우선순위 | 정보 | planner에서의 용도 | 획득 경계 |
|---|---|---|---|
| 필수 | 열별 encoding 종류, 코드 시작값, 연속성, bin 개수, 결측값 처리 | 코드 범위와 feature별 category 관계를 증명 | 전처리 설정·검증 결과에서 기록 |
| 필수 | 열별 `observed_max_code` 또는 `code_upper_bound` | one-hot 폭의 정확한 값 또는 상한 계산 | 기존 전처리 결과 재사용; 없으면 별도 분석 필요 |
| 필수 | 입력 snapshot, 변환 spec/version, 원본→출력 열 대응표 | 다른 입력·변환의 통계를 잘못 적용하지 않음 | 입력 준비 receipt에 연결 |
| 높음 | X/e의 row alignment, e의 값 범위·의미 | feature와 error의 행 대응 및 `sum(e)=nnz(e)` 같은 관계 활용 | 입력 생성 과정에서 인증 |
| 선택 | category별 빈도 | support 조건을 통과할 category 수 계산·추정 | 기존 통계가 없으면 추가 집계 |
| 선택 | category별 error 개수·합 | 초기 `support >= minSup && error > 0` 조건 계산·추정 | X/e를 함께 사용하는 추가 집계 |
| 후순위 | 일부 feature 조합의 공동 빈도·상관 통계 | 다중 feature 후보의 생존율 추정 개선 | 추가 분석; 수집량·privacy·비용 검토 필요 |

### 3.1 최대 코드와 distinct count는 다르다

현재 SliceLine은 다음과 같이 one-hot 폭을 결정한다.

```text
fdom = colMaxs(X)
one-hot 폭 = sum(fdom)
```

예를 들어 한 열의 값이 `{1, 100}`이면 distinct count는 2지만 최대 코드는 100이다. 현재 코드의 해당 feature 영역은 2열이 아니라 100열이다. 이 예시는 구분을 설명하기 위한 값이며 실제 ADULT/COVTYPE 통계가 아니다.

| 정보 | 의미 | 허용되는 해석 |
|---|---|---|
| `observed_max_code` | 현재 입력에서 정확히 확인한 최대 코드 | 해당 입력의 one-hot 폭 계산 근거 |
| `code_upper_bound` | encoding 계약이 보장하는 코드 상한 | one-hot 폭의 상한; 실제 폭으로 간주하지 않음 |
| `distinct_count` | 현재 입력의 서로 다른 유효 값 개수 | category 수; 최대 코드와 자동으로 동일시하지 않음 |

distinct count와 최대 코드를 같게 취급하려면 **현재 입력에 1부터 빈틈없는 코드가 부여되었다는 계약**이 필요하다. 다른 데이터에서 만든 dictionary를 적용하거나 일부 행을 추출한 경우에는 관측 코드에 빈틈이 생길 수 있다. `numbins=10`도 일반적으로 코드 상한의 근거이지, 현재 입력에서 최대 코드 10이 실제 관측됐다는 증거는 아니다.

원본 category 문자열 전체는 이 크기 분석의 기본 요구가 아니다. numeric code의 범위와 변환 identity만으로 해결되는 부분에 dictionary 공개를 추가하지 않는다.

근거: [SliceLine core의 one-hot 생성](https://github.com/min-guk/cofee-evaluation/blob/66b9383acfcf5a3ebe901032b7149ddf0e4e5c3b/planning_study/native/input_templates/common/code/workloads/sliceline/slicefinder_core.dml#L14-L19).

### 3.2 encoding 계약으로 support bound를 유도할 수 있다

각 행이 feature별로 정확히 하나의 유효 category에 속한다는 계약이 있으면, 한 feature의 category support 합은 입력 행 수 N이다. 따라서 support가 `minSup` 이상인 category 수는 최대 `floor(N/minSup)`다.

feature 수를 F, one-hot 폭을 D라고 하면 초기 기본 slice의 논리적 생존 개수는 다음으로 제한된다.

```text
기본 slice 생존 개수 <= min(D, F * floor(N / minSup))
```

이는 인코딩 전제에서 도출한 bound이지 실제 생존 개수의 예측값이 아니다. 빈 결과의 물리 행렬 표현은 별도로 처리한다. 이 bound를 아직 support를 평가하지 않은 모든 후속 후보에 그대로 적용해서는 안 된다.

### 3.3 X/e의 행 대응과 error 의미

X와 e의 행 수가 같다는 사실만으로 같은 행을 가리킨다고 증명할 수는 없다. 같은 입력 snapshot에서 같은 행 선택·순서로 생성됐다는 producer 근거가 필요하다.

현재 ADULT 전처리는 `e = (y != yhat)`를 생성한다. 이 의미와 입력 identity가 보존되면 e가 0/1이라는 사실을 이용할 수 있고, 정확한 `nnz(e)`는 `sum(e)`와 같다. 따라서 이미 있는 metadata를 활용할 여지가 생긴다. 일반적인 실수형 error/loss 벡터에는 이 등식을 적용하지 않는다.

이러한 논리적 row alignment와 실제 FED range의 일치는 별도 조건이다. alignment 선언만으로 서로 다른 worker/range를 동일한 배치로 취급하지 않는다.

## 4. 기존 전처리 결과부터 보존한다

ADULT 전처리는 이미 `fdom = colMaxs(X)`를 계산한 뒤 X와 e를 저장한다. COVTYPE 전처리에도 같은 계산이 있다. 따라서 향후 입력을 준비할 때 **이미 계산한 fdom과 인코딩 설정을 함께 기록**하는 방식이 첫 단계로 적절하다.

- planner가 원본 데이터나 row payload를 다시 읽지 않아도 된다.
- 준비된 artifact를 읽는 정적 compile 경로를 유지할 수 있다.
- 결과가 동일한 X의 열 순서·변환·snapshot에 대응하는지 검증할 근거를 남길 수 있다.

현재 전처리 DML은 fdom을 planner용 sidecar로 저장하지 않는다. 이 문서가 그 기능의 구현 완료를 의미하지 않는다. 기존 입력에 해당 artifact가 없다면 과거에 계산했다는 이유만으로 값을 복구할 수는 없으며, 추가 계산이나 재준비 비용이 필요할 수 있다.

근거: [ADULT 전처리](https://github.com/min-guk/cofee-evaluation/blob/66b9383acfcf5a3ebe901032b7149ddf0e4e5c3b/planning_study/native/input_templates/common/code/workloads/sliceline/dataprep_adult.dml#L11-L32), [COVTYPE 전처리](https://github.com/min-guk/cofee-evaluation/blob/66b9383acfcf5a3ebe901032b7149ddf0e4e5c3b/planning_study/native/input_templates/common/code/workloads/sliceline/dataprep_covtype.dml).

## 5. 모든 metadata에 필요한 신뢰·범위 정보

| 항목 | 기록·검증할 내용 |
|---|---|
| 정확성 수준 | `EXACT`, `PROVEN_BOUND`, `ESTIMATE`를 구분. 추정에는 방법·가정·필요한 오차 정보 포함 |
| 입력 identity | 실제 데이터 snapshot/버전, producer, 해당 artifact와 입력의 연결 |
| 변환 identity | transform spec/version, 열 대응, encoding 및 결측값 의미 |
| 통계 범위 | global인지 worker-local인지, 어떤 행/열 범위를 대상으로 했는지 |
| 생성 근거 | 정적 계약, 정확한 집계, 샘플링, 과거 실행 추정 중 무엇인지 |
| 공개 정책 | coordinator가 읽어도 되는 필드인지, 허용 대상·범위·보관 조건 |

해시는 artifact의 byte identity를 확인하는 수단이지, 그것만으로 데이터가 불변이거나 값이 진실이라고 증명하는 수단은 아니다. 신뢰할 수 있는 producer와 입력 수명 계약이 필요하다. 선언만 된 snapshot ID나 Docker read-only mount를 강제 snapshot isolation으로 설명하지 않는다.

global/local 구분도 중요하다.

- worker별 distinct count를 더해 global distinct count로 사용하면 중복 category를 잘못 센다.
- 모든 worker가 동일한 global encoding을 사용하고 대상 shard들이 해당 입력 전체를 구성한다면, global 최대 코드는 worker별 최대 코드의 최댓값으로 계산할 수 있다.
- worker-local dictionary의 코드값이 서로 다른 의미라면 위 최대값만으로 global one-hot 의미를 복원할 수 없다.

오래되거나 identity가 맞지 않는 artifact를 정확한 fact로 수용하지 않는다. artifact 불일치를 곧바로 원 workload의 INFEASIBLE 판정으로 바꾸는 것도 금지한다. 필요한 사실은 미해결로 남기고, 정당한 재준비 또는 명시적인 비용 추정 정책과 구분한다.

## 6. 선택 통계의 효과와 비용

정확한 category 빈도가 있으면 `minSup`를 통과하는 category 수를 알 수 있다. 그러나 현재 초기 필터는 `support >= minSup`뿐 아니라 `error > 0`도 요구한다. 기본 slice의 생존 조건까지 정확히 계산하려면 동일한 입력·code 의미에 대응하는 category별 error 집계도 필요하다. 샘플링이나 sketch에서 얻은 값은 그대로 추정치다.

이 통계는 현재 sidecar에 이미 있는 정보가 아니다. 새로 수집하려면 X/e를 읽고 집계해야 하며, SliceLine 초기 계산과 작업이 겹친다. 이를 일반 metadata라는 이름으로 무료 처리해서는 안 된다.

단일 feature 통계만으로 모든 조합의 생존율을 정확히 결정할 수도 없다. 공동 분포와 error 분포가 영향을 주므로, 조합 통계를 추가하더라도 필요한 일부에 한정하고 수집 비용 대비 이득을 검증해야 한다.

근거: [초기 category support·error 계산과 필터](https://github.com/min-guk/cofee-evaluation/blob/66b9383acfcf5a3ebe901032b7149ddf0e4e5c3b/planning_study/native/input_templates/common/code/workloads/sliceline/slicefinder_core.dml#L102-L124).

## 7. 추가하지 않아도 되는 정보와 해결되지 않는 범위

- **원본 category 문자열 전체:** SliceLine 크기 분석의 기본 요구가 아니다. P2의 실제 encoder dictionary 공유와 혼동하지 않는다.
- **모든 feature 조합의 통계:** 수집 자체가 조합 폭발을 일으킬 수 있다.
- **단계별 실제 후보 수·pruning 결과:** 이를 정확히 수집하는 것은 본 알고리즘의 일부를 실행하는 일이다. 단순 입력 metadata로 취급하지 않는다.
- **동일 mask로 필터링한 출력들의 행 수가 같다는 관계:** 외부 metadata보다 planner가 DML에서 유도해야 하는 관계다.

위 metadata를 추가해도 후속 `getPairedCandidates`, score 기반 pruning, candidate ID 중복 제거의 모든 실제 크기가 확정되는 것은 아니다. 또한 metadata 제공만으로 Oracle/native rule, shape 전파, 비용 계산, worker binding 및 runtime 계약이 자동으로 연결되는 것은 아니다.

P2는 정확한 global domain뿐 아니라 encoder 의미·metadata frame·비용·source geometry를 함께 다루는 별도 경로다. 현재 작업 트리의 `MemoTransformFacts`에는 opt-in 준비 경로가 있지만, 이것이 이 문서의 일반 SliceLine sidecar 계약을 이미 지원한다는 뜻은 아니다.

## 8. 정확성·privacy·실험 경계

1. **사실과 추정을 분리한다.** 증명된 값·관계·bound는 증명 범위 안에서만 사용한다. 예상 크기는 비용 비교용이며 Oracle YES, privacy 허용, 실제 worker range를 만들어 내는 근거가 아니다.
2. **UNKNOWN/DEFER를 보존한다.** 비용을 계산할 수 있어도 필요한 실행 합법성이 미해결이면 그것을 해결된 것으로 바꾸지 않는다. 지원 가능한 후보를 편의상 삭제하지도 않는다.
3. **runtime fallback을 추가하지 않는다.** 실제 FederationMap을 추정 map으로 대체하거나 실패 뒤 임의로 위치를 변경하지 않는다.
4. **집계라고 자동 공개하지 않는다.** category 빈도, 최대 코드, dictionary도 데이터 정보를 드러낼 수 있다. `PRIVATE_AGGREGATE`라는 이름만으로 모든 통계의 공개 권한이 생기지는 않는다.
5. **수집 비용을 명시한다.** 기존 artifact를 이용한 compile과 최초 수집을 포함한 준비→compile 시간을 구분한다. 필요한 수집을 숨긴 채 전체 준비 완료 시간을 주장하지 않는다.
6. **동일 조건으로 비교한다.** baseline과 새 planner에 제공한 metadata·접근 권한·준비 비용의 차이를 공개한다.
7. **최적성의 대상을 명시한다.** 추정 비용에 대한 exact optimum을 실제 미확정 크기나 wall-clock 시간의 최적성으로 확대하지 않는다.

## 9. 구현 시 검증 항목과 남은 작업

| 검증 | 요구 결과 |
|---|---|
| 같은 shape, 다른 데이터/encoding | metadata를 부당하게 재사용하지 않음 |
| `{1,100}` 같은 비연속 코드 | distinct count를 최대 코드로 오인하지 않음 |
| 빈 입력·결측값·0 코드·열 재배열 | 해당 encoding 계약과 실제 연산의 의미 일치 |
| X/e 행 순서 또는 source identity 불일치 | 정렬 관계를 증명된 것으로 수용하지 않음 |
| worker 간 category 중복·상이한 dictionary | global/local 통계를 정확히 구분 |
| estimate 값 변경 | 비용 순위는 달라질 수 있지만 proof·privacy 사실을 오염시키지 않음 |
| 누락·오래된·권한 없는 metadata | 명시적인 미해결/실패 처리; 가짜 성공 없음 |
| 실제 ADULT/COVTYPE | 새 stage에서 planning/fullcompile, 별도 runtime 결과 동등성 및 시간 검증 |

첫 구현 범위는 기존 전처리에서 이미 계산한 최소 정보의 보존과 검증으로 제한하는 것이 적절하다. 그다음 shape/bound 분석과 비용 추정의 소비 경로를 연결하고, 실제 workload의 남은 병목을 계측한 뒤 통계 확장을 결정한다.

현재 완료한 것은 코드·보존 metadata에 근거한 요구사항 문서화다. metadata producer/reader, 일반 bound 표현, 비용 추정 연결, 실제 workload 재검증은 이 문서에서 수행하지 않았다.

## 관련 문서

- [SliceLine legacy static shape·비용 추정](FEDPLANNER_SLICELINE_LEGACY_STATIC_SHAPE_COST_ESTIMATION_2026-10-14_KO.md)
- [SliceLine reproduce·metadata 경계](FEDPLANNER_SLICELINE_REPRODUCE_METADATA_CONTRACT_2026-10-14_KO.md)
- [SliceLine planning blocker 분석](FEDPLANNER_SLICELINE_PLANNING_BLOCKERS_ROOT_CAUSE_2026-10-14_KO.md)
- [P2 문제 분석](FEDPLANNER_P2_PLANNING_BLOCKERS_ROOT_CAUSE_2026-10-14_KO.md)
- [P2 metadata-assisted compilation 계약](FEDPLANNER_P2_METADATA_ASSISTED_COMPILATION_CONTRACT_2026-10-14_KO.md)
