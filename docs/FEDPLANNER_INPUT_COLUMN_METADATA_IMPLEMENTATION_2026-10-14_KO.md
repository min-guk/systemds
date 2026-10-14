# 입력 column metadata 1차 구현 계획·계약

## 기준 및 범위

- Engine origin/main: `78951e9796a53d52038389ef9a4f89ff77b1f56e`.
- Evaluation origin/main: `66b9383acfcf5a3ebe901032b7149ddf0e4e5c3b`.
- 독립 worktree에서 구현하며 다른 작업 트리의 미커밋 P2 persisted metadata 작업은 포함하지 않는다.
- 이번 완료 경계는 metadata 생성/저장/검증/입력 source별 불변 보관이다. 후속 dynamic shape, 비용 추정, Oracle 후보 및 runtime 실행은 바꾸지 않는다.

## 최소 계약

`.mtd`의 `cofee_input_metadata` 확장에 버전, GLOBAL 입력 identity·rows/columns·rowSetId·transformId, 열별 종류/encoding/코드 범위/결측값 의미를 기록한다. 각 shard는 자신의 endpoint/path/range와 확장 전 base metadata를 함께 바인딩한다. 모든 shard의 global facts는 일치해야 한다. 실제 관측 최대 코드와 보장된 상한은 별도 optional 필드다. 추정 통계·histogram·dictionary 값은 저장하지 않는다.

Producer는 신뢰된 준비 단계의 fdom export와 명시적 계약을 받아 기존 sidecar를 읽고 **새 출력 디렉터리**에 파생 sidecar를 작성한다. 원본 sealed stage, hardlink, 행렬 payload는 수정하지 않는다. source version은 producer의 immutable 입력 계약이며 compile마다 payload 해시를 계산하거나 임의의 외부 변경을 탐지한다고 주장하지 않는다.

Reader는 coordinator에 같은 경로로 제공된 `.mtd`만 읽는다. missing/nonlocal metadata를 보충하기 위한 worker RPC·payload scan은 없다. 공개 통계 사용에는 명시적인 deployment opt-in(`sysds.privacy.allowInputColumnMetadata`)과 artifact의 공개 허용 선언이 모두 필요하다. data privacy 자체는 바꾸지 않는다.

`MemoSources`에서 요청별 immutable facts와 diagnostics를 보관한다. 원본/clone HOP의 rows/cols/nnz에는 값을 주입하지 않는다. rowSetId는 trusted producer가 보장한 논리 행 순서 관계이며 실제 worker map 대체 권한이 아니다.

## 작업 순서

1. 회귀: `.mtd` 확장 수용 및 read 인자 비주입, immutable parsing·잘못된 bound/identity 거절.
2. 최소 typed schema와 parser, local sidecar reader, MemoSources 원본 owner 결합.
3. evaluation producer, ADULT/COVTYPE 준비 단계 fdom export, provenance/hash 및 CLI 테스트.
4. Java targeted/component 회귀, Python producer/packaging 검증, 실제 metadata artifact의 읽기 경로 검증. 실제 workload planning 성공을 주장하지 않는다.

## 검증 기준

- opt-in OFF/missing: 추가 fact 없음, 추가 RPC/payload 읽기 없음.
- EXACT 최대 코드와 상한 구분; 비연속 코드의 NDV 오인 없음.
- endpoint/path/range/base metadata/global facts 불일치: fact 미설치 및 diagnostic.
- 다른 source/동일 shape 및 X/e rowSet 불일치: 부당한 재사용 없음.
- 메타데이터 존재 전후 HOP shape와 기존 Oracle/privacy 동작 불변.
- 기존 미확정 중간 shape·비용 문제는 남겨 둔다.

상태: Engine metadata reader/source 보관 구현 및 Java 회귀 완료. 최초 준비 producer와의 호환성 검증 완료. 기존 dataset backfill 및 중간 shape/cost 수정은 범위 밖이다.

## 최초 생성·세팅 정책 (사용자 결정 반영)

기존 ADULT/COVTYPE payload를 다시 읽어 통계를 보충하지 않는다. **새 데이터셋을 처음 준비할 때 이미 계산한 `fdom = colMaxs(X)`를 저장**하고, 그 생성 revision의 encoding·행 대응 계약과 함께 federation 세팅 시 sidecar에 연결한다. 이후 planner는 이 산출물을 읽기만 한다. 이전 sealed dataset에는 metadata가 없을 수 있으며 자동 backfill하지 않는다.

- `fdom`은 인코딩된 X 각 열의 **관측 최대 코드**다. 예를 들어 `{1,100}`이면 100이지 NDV 2가 아니다.
- bins=10 같은 encoder 설정은 `codeUpperBound=10`의 근거일 수 있지만 관측 최대 10을 증명하지 않는다.
- 초기 전처리의 시간·산출물은 dataset 준비 비용이다. 실행 전 planning 시간에 숨긴 데이터 스캔은 없다.
- 준비 단계에서 얻은 공개 가능한 통계만 배포한다. `private-aggregate` 라벨만으로 임의 통계 공개를 허용하지 않는다.

## 구현 위치와 API

| 위치 | 역할 |
|---|---|
| `runtime/meta/InputColumnMetadata.java` | typed immutable `Dataset`, `Column`, `Binding`; 엄격한 sidecar 검증; 로컬 metadata-only 읽기 |
| `runtime/meta/MetaDataAll.java` | 확장 인식 및 `getInputColumnMetadata()`; 일반 rows/cols/nnz 처리와 분리 |
| `parser/DataExpression.java` | `.mtd` 확장 필드 허용. DML `read()`의 사용 가능한 인자로는 추가하지 않음 |
| `hops/fedplanner/memo/MemoSources.java` | 요청별 원본 compiled owner에 검증된 source facts 결합. `inputMetadata(owner)`, `inputMetadataDiagnostics()` 제공 |
| evaluation `campaign/prepare_input_column_metadata.py` | 준비 결과·원본 sidecar로부터 별도 metadata 산출물을 만드는 producer |
| evaluation `planning_study/native/input_templates/common/code/workloads/sliceline/dataprep_{adult,covtype}.dml` | 이미 계산하는 global fdom의 보존 |

`MemoSources`는 실제 FederationMap/CanonicalWorkerPool로 source geometry를 검증하는 기존 순서를 유지한다. 새 metadata는 이 검증을 대체하지 않는다. source의 coarse privacy와 sidecar privacy도 일치해야 하며 fine-grained privacy는 기존 fail-closed 규칙을 따른다. 새 통계가 없거나 검증에 실패하면 metadata fact를 설치하지 않는다. 이것은 FED 후보를 제거하거나 privacy를 PUBLIC으로 바꾸는 조치가 아니다.

## v1 필드 계약

확장은 일반 `.mtd`의 `cofee_input_metadata` JSON object다.

| 필드 | 의미 / 요구사항 |
|---|---|
| `schema` | `cofee-input-column-metadata-v1` |
| `scope` | `GLOBAL`. shard-local 통계를 global로 합산하지 않음 |
| `release` | `PUBLIC_COLUMN_STATISTICS`. 별도의 배포 opt-in과 함께 요구 |
| `datasetId` | 해당 X/e 등 입력의 논리 ID. X와 e는 서로 다른 ID |
| `datasetVersion` | 변경 불가능한 공동 dataset 생성 revision |
| `producerVersion` | 생산 코드/파이프라인 revision |
| `transformId` | 해당 입력의 encoding/변환 identity. X와 e는 다를 수 있음 |
| `rowSetId` | 순서까지 같은 논리 행 집합 identity. 단순히 행 수가 같은 것과 다름 |
| `rows`, `cols` | 전체 입력의 실제 shape. shard의 base `.mtd` rows/cols와 구분 |
| `columns` | 순서가 있는 전체 열 계약. 1부터 빠짐없이 모든 열 포함 |
| `binding` | 해당 shard의 실제 `host`, `port`, `path`, `[begin,end)` 2차원 range |
| `baseMetadata` | 확장 추가 전의 원본 `.mtd` 전체 object. privacy/shape/format/nnz 등 변경을 구조적으로 검출 |

열별 필수 필드는 `id`, `sourceColumn`, `kind`, `encoding`, `missing`, `contiguousCodes`다. 코드 열은 `codeBase`도 필요하다.

- `CATEGORY_CODE` / `RECODE` / base=1: recode된 범주 열.
- `BIN_CODE` / `EQUI_WIDTH_BIN` / base=1: 구간화된 수치 열.
- `BINARY` / `NONE` / base=0 / upper=1: e 같은 0/1 입력. SliceLine X의 exactFdom 조회 대상은 아님.
- `NUMERIC` / `NONE`: 일반 수치 열. 코드 범위나 연속성 증명을 붙이지 않음.
- `observedMaxCode`: optional exact 관측 최대. `codeUpperBound`: optional 보장된 상한. 한 필드에서 다른 필드를 유추하지 않음.
- `contiguousCodes=false`는 연속성을 보장하지 않는다는 뜻이다. 실제 gap 존재를 주장하는 것이 아니다.
- 결측 의미는 `NONE`, `ZERO`, `NAN`, `CATEGORY`, `ENCODER_DEFAULT`, `UNKNOWN` 중 하나다. 실제 encoder 처리를 확인하지 않고 `NONE`으로 채우지 않는다. `ENCODER_DEFAULT`/`UNKNOWN`은 후속 feasibility에 구체적인 결측 처리 증명으로 사용할 수 없다.
- dimensions/ranges/code 값은 JSON int64 정수 token이어야 한다. `100.0`이나 부동소수점 반올림을 거친 큰 수를 exact 정수로 승인하지 않는다.

`Dataset.exactFdom()`은 **모든 열이 category/bin code이고 각 열의 observedMaxCode가 있을 때만** immutable vector를 반환한다. 상한만 있거나 binary/numeric 열이면 비어 있다.

`Dataset.sameRowOrder(other)`는 rows + rowSetId + datasetVersion + producerVersion의 일치를 확인한다. 이는 trusted producer가 선언한 논리 행 대응이지, shard 수/범위/worker identity 일치의 증명이 아니다.

## 사용 및 실패 경계

Planner JVM에 다음을 명시해야 새 column facts를 취득한다.

```text
-Dsysds.privacy.allowInputColumnMetadata=true
```

기본값은 OFF다. 통계 파일이 있다는 사실만으로 배포 허가를 추론하지 않는다. 이 옵션은 새 facts 취득만 켜며 기존 transform-encode metadata 수집 옵션과 별개다. 새 경로는 encode 실행, matrix payload read, worker RPC를 하지 않는다. 기존 source geometry/privacy resolver의 동작은 그대로이므로 **전체 compiler의 기존 RPC까지 제거했다고 주장하지 않는다**.

Reader 지원은 coordinator가 정확한 worker data path에 가진 `.mtd` mirror다. 일반 로컬 경로(공백 포함)와 `file:` URI를 지원한다. `hdfs:` 등 remote URI나 없는 mirror를 자동 수집하지 않는다. 실제 worker path가 여러 worker에서 동일하고 서로 다른 binding을 같은 로컬 mirror로 표현할 수 없다면 이 v1 경로에서는 facts를 설치할 수 없다. endpoint-indexed manifest/RPC는 별도 설계가 필요하다.

전 shard에 확장이 없으면 facts 없음으로 종료한다. 일부 shard에만 존재하거나 global identity/columns가 다르거나 binding/base metadata가 맞지 않으면 해당 owner에 facts를 설치하지 않고 `INPUT_COLUMN_METADATA_REJECTED` diagnostic을 남긴다. 유효한 다른 source의 metadata는 유지한다. 기존 input/worker legality 검사 자체의 실패를 이 diagnostic으로 삼키지 않는다.

### 신뢰 경계와 남은 문제

- `datasetVersion` 및 통계의 진실성은 dataset producer의 immutable artifact 계약이다. `.mtd`/path를 그대로 두고 payload만 외부에서 바꾸면 이 reader는 알아낼 수 없다. 매 compile payload hash/scan을 수행하지 않으며 암호학적 provenance를 검증하는 시스템도 아니다.
- 이 단계에서는 HOP와 MatrixCharacteristics의 exact shape를 수정하지 않는다. source metadata를 derived HOP로 전파하는 규칙도 넣지 않는다.
- pruning 후 행 수, 후보 생성·중복 제거 크기, 동적 중간 shape는 여전히 미확정이다. 이를 해결하려면 다음 단계에서 proven bounds/estimates와 exact shape를 분리하고 연산별 전파 및 비용 소비 경로를 설계해야 한다.
- actual SliceLine fullcompile/runtime 성공, 정확한 최적해, 성능 향상은 이번 metadata 저장/읽기 검증만으로 주장하지 않는다.

## 검증 결과

- Java 신규 **15 tests PASS**: parser 7, Memo source integration 8.
- 관련 기존 회귀 **20 tests PASS**: source geometry 4, binding resolver snapshot 5, transform metadata release privacy 7, Memo private/aggregate/unknown/privacy metadata 4.
- Evaluation 관련 Python **12 tests PASS**: producer 7, package layout/source manifest 3, native provenance 2. `py_compile`과 native template SHA 검증 PASS. 전체 RealStage render suite는 기존 STEP-LM template pin 불일치가 보고되어 전체 PASS로 표시하지 않으며, 변경과 직접 관련된 gate만 통과했다.
- Python producer가 실제로 작성한 sidecar를 Java `readLocal → MetaDataAll → MemoSources`로 읽는 cross-language smoke PASS. payload 없는 2×1 입력에서 exact max=100, bound=1000을 구분했고 PRIVATE_AGGREGATE와 원본 HOP unknown shape가 유지됐다. 이것은 **metadata protocol 검증 fixture**이지 작은 e2e workload 성능 대체 검증이 아니다.
- `mvn test`의 compile/test-compile 및 `git diff --check` PASS. 별도 Java lint plugin은 이 Maven 프로젝트에 정의되어 있지 않다.
- 실제 dataset 재생성·스캔, 학습 workload 실행, Docker performance 측정은 하지 않았다. 사용자가 선택한 “향후 최초 생성에서 기록” 정책과 이번 완료 경계를 따른다.
- 증거: [Java 결과/재현 명령](evidence/input-column-metadata-2026-10-14/java-verification.json), [cross-language smoke](evidence/input-column-metadata-2026-10-14/cross-language-smoke.log). 같은 디렉터리에 JUnit class별 원본 text 결과를 보관했다.

Evaluation 쪽 상세 setup/프로필/명령/검증은 별도 worktree의
`docs/FEDPLANNER_INPUT_COLUMN_METADATA_SETUP_2026-10-14_KO.md`를 참조한다.
자동화 수준은 **최초 DML 실행의 fdom export는 자동**, partition sidecar 생성 후 metadata packaging은 명시적 opt-in 준비 단계다. 기존 repository에는 raw download부터 partition까지 모두 수행하는 단일 builder가 없으므로, 기존 sealed-stage assembler를 metadata 수집기로 바꾸지 않았다. 생성한 mirror를 실제 exact path에 설치/bind하고 배포 opt-in을 켜야 planner가 읽는다.

후속 작업에서 metadata를 shape/cost 계산에 사용하게 되면 해당 cache identity에도 dataset revision/transform 및 사용한 facts를 포함해야 한다. 현재는 어떤 기존 Oracle/cost cache도 이 새 facts를 소비하지 않으므로 해당 key를 변경하지 않았다.
