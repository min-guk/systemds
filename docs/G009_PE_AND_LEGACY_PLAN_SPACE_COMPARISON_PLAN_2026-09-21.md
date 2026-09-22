# G009 현재 P/E와 확장 이전 Git 공간의 전수 비교 실행 계획

- 작성일: 2026-09-21. 이번 요청의 산출물은 **실행 계획**이며 새 비교 실행 결과를 주장하지 않는다.
- 목적: 현재 production 공간 `P`와 기존 Exact/exhaustive 공간 `E`의 **고정된 유한 모델 전체**를 비교하고, 공간 확장 이전 Git 버전의 공간이 어떻게 달라졌는지 같은 실행에서 판정한다.
- 완료 산출물: 실제 P/E adapter, Git 버전별 adapter, 공통 plan identity, 빠른 정확 관계 열거기와 단순 전수 기준기, 재개 가능한 병렬 runner, 차집합 witness, 재검사 가능한 certificate 및 단일 최종 보고서.
- 앞선 [독립 인증 최종 보고서](G009_INDEPENDENT_PLAN_SPACE_CERTIFICATION_FINAL_REPORT_2026-09-21.md)의 전체 runtime 의미론 `R` 증명은 별도 계약으로 유지한다. 이번 비교를 그 계약의 PASS로 승격하지 않는다.

## 1. 이번 작업에서 확정할 판정

**닫힌 모델의 비교는 R이 미완성이라는 이유로 UNKNOWN에 묶일 필요가 없다.** 버전·입력·모델의 유한 domain, native acceptance와 decode가 고정되고 전체 처리가 끝나면 `EQUAL` 또는 구체적 witness를 가진 `DIFFERENT`로 결론을 낼 수 있다. 유한하다는 사실만으로 두 집합의 일치나 수 시간 이내의 종료가 보장되지는 않는다. 현재 adapter의 누락·exception과 미처리 범위를 고쳐야 하며, 상태 이름만 바꾸어 완료로 취급하지 않는다. [E1–E4]

집합을 다음처럼 구분한다.

```
P_v(I) = decode_P_v({a in native P domain | native P acceptance(a)})
E_v(I) = decode_E_v({a in native E domain | all native hard factors accept(a)})

현재 비교: P_C \ E_C, E_C \ P_C
역사 비교: P_B0 \ P_C, P_C \ P_B0
원인 분리: P_B1 \ P_C, P_C \ P_B1 및 각 버전의 P_v ↔ E_v
```

`C`는 실행 때 고정한 현재 소스 snapshot, `B0/B1`은 아래 Git SHA다. `P`의 domain을 E에서 복사하거나, E의 hard factor로 P를 다시 정의하지 않는다. native production predicate를 P membership에 쓰는 것은 이 **닫힌 production 모델의 정의**를 관측하는 일이다. 이를 독립적인 runtime legality oracle로 부르지 않는다. 비용 최적해·selector winner·raw assignment 개수는 plan 집합을 대신하지 않는다. [E1–E3]

Certificate는 서로 다른 축을 기록한다.

| 필드 | 완료 상태와 의미 |
|---|---|
| `contract` | `closed-model-pe-history-v1`; 기존 full semantic certificate와 다른 namespace |
| `capture`, `nativeCoverage`, `decode`, `comparison` | 모두 `COMPLETE`여야 해당 pair의 전수 비교 완료 |
| `peRelation` | `EQUAL` 또는 `DIFFERENT`; 차이가 나면 방향별 개수와 원본 witness 필수 |
| `historyRelation` | `SAME / EXPANDED / REDUCED / CHANGED`; 예를 들어 `EXPANDED`는 old-only=0, new-only>0 |
| `gate` | 현재 P/E 동등성과 선언된 회귀 정책이 충족되면 `PASS`; 불일치·데이터 손상·계약 위반은 `FAIL` |
| 미완료 | timeout/OOM/frontier가 남으면 `INCOMPLETE`, native capture/decode 예외는 재현 가능한 `ERROR`; 둘 다 완료가 아님 |
| `runtimeSemanticCoverage` | `NOT_ASSESSED_BY_THIS_CONTRACT`; 기존 독립 R 인증의 UNKNOWN을 없앤 것으로 기록하지 않음 |

`DIFFERENT`는 비교가 끝났다는 뜻이다. 현재 P/E 차이는 회귀를 고정하고 원인을 수정할 대상이다. 과거와 현재 사이의 차이는 확장 의도상 정상일 수 있어 자동으로 실패시키지 않는다. 같은 의미론·같은 IR에서 과거 plan이 사라지면 근거 없는 regression으로 실패시키고, privacy/runtime 정정에 따른 삭제라면 그 규칙과 최소 positive/negative 증거를 연결한다. 과거 구현을 정답으로 놓고 현재 공간을 축소해 동등성을 맞추지 않는다.

## 2. 확인한 코드 근거

아래 경로는 현재 checkout의 조사 시점 기준이다. 구현 시 C snapshot에서 다시 고정한다. 신규 파일·CLI는 뒤에서 제안으로 구분한다.

| ID | 확인 사실 | 근거 |
|---|---|---|
| E1 | 현재 P exporter는 decision placement × owner receipt/absent × relocation receipt/absent를 열거한다. graph 밖 owner 또는 derived-FOUT가 있으면 실제 UNKNOWN 경로가 있다. | `src/test/java/org/apache/sysds/test/component/federated/placement/shadow/FullProductionJointPlanExport.java:64,147,174` |
| E2 | E는 native raw domain과 hardFactors를 전수 방문할 수 있다. nonboolean factor/exception은 아직 UNKNOWN이며, 별도의 global domain completeness 플래그도 UNKNOWN이다. | `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalRawSpaceExporter.java:37,49,68`; `ExactPhysicalPlanSpaceExporter.java:21,34,96` |
| E3 | fixture artifact는 P/E 서로 다른 raw identity를 쓰며 모든 receipt/audit를 UNKNOWN으로 기록한다. 현재 상태로는 물리 집합 비교 adapter가 아니다. | `src/test/java/org/apache/sysds/test/component/federated/placement/shadow/PlanSpaceFixtureArtifactAdapter.java:39,76,113,142` |
| E4 | 기존 runner는 reference/candidate/exhaustive 세 종류와 full semantic gate에 결합되어 있다. full은 rule tuple gate 미구현 때문에 마지막에도 UNKNOWN을 강제한다. 정렬·merge 차집합·coverage 검사 자체는 재사용 가능하다. | `/home/mchoi/cofee-evaluation/calibration/plan_space_verify.py:20,313,336,404,555,644,652` |
| E5 | full candidate validator는 active consumer 전부의 explicit receipt를 요구한다. relocation validator도 active demand coverage를 검사하나 내부 candidate 검사는 partial이다. realization 검사만으로 full coverage가 증명되지 않는다. | `src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java:1475,1500,1626,1644`; `RelocationSelections.java:1378,1397,1513` |
| E6 | E에는 synthetic function boundary, execution authority, durable anchor, derived action, ordered input authority, realization/support 관계가 들어간다. P에서 이것들을 빼면 비교 좌표가 다르다. | `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java:203,301,726,746,802,837` |
| E7 | production signature는 namespace/call/recompile/instance에 의존하고 일부 emission signature는 shallow하다. E adapter의 ordered input은 현재 `toString()`도 사용한다. | `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java:275`; `PlacementAnalysis.java:1004`; `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalPlanSpaceExporter.java:84` |
| E8 | pre-builder snapshot은 HOP ID와 구조·ordered edge·함수 경계를 보존한다. 실제 프로파일 56개는 캡처됐지만 24개 template의 28개 call-name metadata는 null이다. | `src/test/java/org/apache/sysds/test/component/federated/placement/shadow/PrebuilderSnapshot.java:46,57,104`; `PlanningTemplateSnapshotSmokeTest.java:82,93,105` |
| E9 | 279개 발견/224개 계획 조건/447개 backlog가 있으나 현재 adapter는 비어 있다. 실제 데이터가 없는 독립 R 인증 입력과 closed compiler-model 입력의 요구를 구분해야 한다. | `/home/mchoi/cofee-evaluation/calibration/build_plan_space_cells.py:186,228,237`; `docs/G009_INDEPENDENT_PLAN_SPACE_CERTIFICATION_FINAL_REPORT_2026-09-21.md:19,22,24` |
| E10 | 기존 P raw 곱은 B-21 324,699,527,577,600, B-22 37,748,736이다. E B-21 6,048개 전수만 저장됐다. 단순 raw parallel 확장만으로 해결됐다고 할 수 없다. | `docs/G009_INDEPENDENT_PLAN_SPACE_CERTIFICATION_FINAL_REPORT_2026-09-21.md:44,45`; `/grid/3/cofee-lm-sweep-mchoi-20260914/plan-space-certification-20260921/fixtures/{P-B-21,P-B-22,E-B-21-full-model}/receipt.json` |
| E11 | 기존 cache/resume/차집합·손상·범위 mutation tests가 있다. 현 번들은 production 전체 tree/build를 완전히 대체하지 않으며 뒤의 source drift도 감지됐다. | `/home/mchoi/cofee-evaluation/calibration/tests/test_plan_space_verify.py:68,79,111,124,134,377`; `scripts/fedplanner/freeze_plan_space_bundle.py:76,87,110`; `docs/G009_INDEPENDENT_PLAN_SPACE_CERTIFICATION_FINAL_REPORT_2026-09-21.md:51` |

## 3. Git 기준을 먼저 고정한다

“확장 이전”의 두 경계를 모두 기록한다. **B0를 기본 역사 비교 대상으로 삼고 B1도 중간 기준으로 비교**한다. `a6281207`은 이 두 변경 이후이므로 pre-expansion으로 쓰지 않는다. G009 문서도 시작 baseline을 B1으로 기록한다. [근거: `docs/PLAN_SPACE_G009_PROGRESS_2026-09-19.md:197`, `docs/PLAN_SPACE_AUDIT_2026-09-18.md:216`, `docs/G009_SELECTOR_INDEPENDENT_SEARCH_SPACE_PLAN_2026-09-21.md:4` 및 아래 Git parent/diff]

| 이름 | 고정 SHA | 의미 |
|---|---|---|
| B0 | `ffb7be5bd85367156ed9ea86dacbaff4be0f035d` | 2026-09-14; realization 확장 커밋 d8fbd30b의 직접 부모 |
| B1 | `d8fbd30b5476a1ceef460c9f3886381a369ac619` | 2026-09-16; realization 확장 후, G009 대규모 completeness 변경 직전 |
| 경계 확인용 | `451acabf038906c4219ede8dab7eecffcabc7eb2` | B1의 직접 자식; completeness tests와 native continuity 보강 포함 |
| C0 | 실행 시 생성하는 content snapshot | HEAD `fe000959c48ffa1172399e49124d082fe42d0c6d` + dirty tracked/untracked 소스 + compiler/runtime/config/build 전체 hash |
| C1… | 발견 결함을 고친 경우 별도 snapshot | C0의 반례와 결과를 보존하고 수정본 전체 비교를 새로 실행 |

B0→B1은 `LogicalBoundaryRealizations`, `CandidateDomainRefinement`, builder closure, `PlacementAnalysis`, 후보/relocation 선택과 `ExactPhysicalModel`을 바꾼다. B0에는 `canonicalCandidateReceipt` 단수 API가 있고 최신 plural API와 `validateRealizationSelections`는 없다. Exact의 package-private domain은 존재하므로 각 Git 버전에 test-only bridge를 만들 수 있다. 이를 확인하는 재현 명령은 `git show --format=fuller --stat <SHA>`, `git rev-parse <SHA>^`, `git show <SHA>:<source-path>`다. 최신 predicate를 B0에 이식하면 역사 비교를 바꾸므로 금지한다.

공유 checkout을 `reset/checkout`하지 않는다. B0/B1은 별도 detached worktree 또는 Git archive, C0는 복사 전후 전체 source manifest가 같은지 확인한 isolated snapshot으로 만든다. Production source와 test instrumentation의 hash를 따로 기록하고, 같은 Java/dependency 환경으로 빌드하되 각 버전 원래 compiler/runtime 소스를 보존한다. Baseline overlay는 관측·직렬화·test bridge만 허용한다. 새 의존성은 추가하지 않는다.

`campaign.json`은 `productionSource`, `instrumentationSource`, `compilerAndBuild`(JDK·dependency·JAR·effective options), `workloadInputs`(형제 `cofee-evaluation`/legacy tree의 DML·imports·metadata·소비된 값), `harness`(공식 launcher/config/image identity)를 별도 hash 묶음으로 고정한다. 복사 전후 source 목록·파일 bytes hash가 같고, isolated copy에서 빌드한 JAR와 캡처 receipt가 이 묶음을 참조해야 T0를 통과한다. 필요 untracked 파일은 포함하고 mutable shared `target/`을 버전 간 공유하지 않는다.

## 4. 비교 입력과 대상 분모

1. **계약/회귀 corpus:** 보호 privacy를 적용한 B-01…B-22, 기존 literal 8-plan/1,344-combination fixture, E6의 함수·derived-FOUT·shared action·authority/geometry 경계 fixture. 기존 PUBLIC-only ignore는 유지하고 제외 사유를 manifest에 둔다. [E1,E6 및 `ProductionShadowFixtureFactory.java:50,76`; `ProductionDecodedPlanSpaceCompletenessTest.java:138,320`]
2. **실제 planning corpus:** 기존 224 profile 조건을 모두 추적한다. 실제 DML은 14×worker 1/3/5/7의 56개이며, 각 network/config가 domain을 바꾸지 않는다는 증거가 있을 때만 relation 재사용을 허용한다. 비용-only 변경이라는 추정으로 조건을 생략하지 않는다. [E8,E9]
3. **나머지 in-scope corpus:** 기존 115 in-scope 발견 중 조건 미고정 59개의 compile 설정·입력 metadata를 확정하고 등록한다. 279개 discovery ID는 모두 새 manifest의 대상 또는 구체적인 helper/미지원/역사 입력 사유에 매핑한다. 현재 148 unsupported와 16 historical을 이번 검증에서 통과한 것으로 계산하지 않는다. 분모 확정 전에 56개 성공을 “전체 workload”로 발표하지 않는다. [E9]

전체 실행 전 T0에서 164개 미분류/역사 발견을 실제 entrypoint, import-only helper, bytes-equivalent alias, 과거 전용 입력으로 근거와 함께 재분류한다. 요청된 현행 workload entrypoint가 발견되면 비교 분모에 추가한다. 이 분류가 남아 있으면 결과 제목은 **고정된 비교 corpus의 결과**로 한정하고 “우리가 가진 전체 workload 완료”를 선언하지 않는다. 최초 P/E 구현은 작은 corpus에서 진행할 수 있지만 최종 전체 실행의 수용 기준은 축소하지 않는다.

동시에 **cell×버전 applicability matrix**를 비교 결과를 보기 전에 고정한다. 공통 입력이 해당 버전의 언어·연산 계약에 속하면 역사 pair도 `REQUIRED`다. 과거 버전에 해당 언어/연산 자체가 없으면 도입 commit·native 진단·source 근거를 붙여 `NATIVE_UNSUPPORTED`로 기록하고 정확 집합 pair 분모와 별도 집계한다. 새 workload 파일이 과거 Git에 없다는 사실만으로는 제외할 수 없다. 주입한 동일 DML을 과거 compiler가 지원하면 비교 대상이다. Native bug, adapter 부재, 시간·메모리 부담 때문에 `REQUIRED`를 나중에 unsupported로 바꾸지 않는다. 현재 C의 P/E 분모는 그대로 유지한다. 역사적으로 지원되지 않은 cell은 capability 차이를 확인한 것이며, full physical equality PASS가 아니다. 보고서에 required 비교 coverage와 unsupported 수를 모두 표시한다.

새 `comparison-input-v1`에는 DML/import, compiler options, source identity, shape/NNZ/type/scalar constants, privacy/release, worker/range/FType, call/recompile/CFG와 native model이 실제로 읽은 모든 입력을 고정한다. **정적 compiler-model 비교에서 소비하지 않는 전체 matrix 값이나 학습 runtime trace는 전제 조건이 아니다.** 실제로 값·remote metadata를 읽어 분기/도메인을 결정하면 그 값은 반드시 캡처한다. 값이 없다고 만들어 넣거나 현재 R용 `preflight=INCOMPLETE`를 강제로 COMPLETE로 바꾸지 않고, 별도 입력 계약을 검증한다. 의미가 명시된 native unknown-dimension sentinel은 그 sentinel을 가진 고정 모델의 입력으로 보존할 수 있지만, unresolved identity/authority는 비교 오류다.

실제 workload의 capture는 공식 `run_LAN_docker.sh`에 test-only capture hook을 붙여 수행한다. frozen DTO/IR에 대한 unit enumeration은 hermetic test로 실행한다. launcher가 필요한 workload/worker profile을 지원하지 않으면 먼저 registry/generator/validator를 연결한다. 원래 runtime 실험 제약과 보호 privacy를 바꾸지 않는다.

### Compiler 변화의 영향을 분리한다

B0에서 현재까지 `DMLTranslator`, `FederatedLocalZeroCallsiteSpecializer`, `FederationUtils`, `BuiltinNaryFEDInstruction`, `ReorgFEDInstruction`도 바뀌었다(`git diff B0 C0 -- <paths>`로 저장). 같은 DML SHA만으로 동일 planner 입력이라 선언하지 않는다. 각 버전의 pre-builder snapshot을 구조·입력 순서·call/recompile/source lineage로 대조한다. [E7,E8]

- IR이 대응하면 같은 고정 입력에 대한 search-space 차이로 비교한다.
- IR이 다르면 전체 버전의 **native compiled-plan 집합 차이**는 보존하되 `compilerDelta`를 함께 보고하며, 이를 즉시 planner 누락이라 판정하지 않는다. 원인 분리가 필요한 cell에는 동일 neutral IR을 버전별로 복원하는 replay를 추가하고 각 importer의 구조 보존을 검증한다.
- 필드를 지우거나 서로 다른 연산을 임의 병합해 IR을 같게 만들지 않는다. Legacy에 어떤 물리 구분 정보가 원래 없고 재구성도 불가능하면 `LEGACY_REPRESENTATION_LIMIT`으로 구체적으로 실패시킨다. 별도 공통 projection의 집합 비교는 가능하나 `comparisonLevel=PROJECTED`로 표기하고 full physical comparison 완료에 포함하지 않는다.

## 5. 구현 단계와 파일 책임

신규 이름은 제안이다. 기존 파일은 재사용하고, 모든 단계는 실패 fixture를 고정한 뒤 구현·검증한다. schema를 먼저 확정한 다음 P/E, legacy, runner 담당을 독립 병렬화할 수 있다. 통합과 최종 판정은 leader가 소유한다.

의존 순서는 `T0 → T1 → T2의 작은 P/E 최초 성공 → (T2 나머지 좌표 보강 · T3 legacy · T4 exact relation · T5 runner) → T6`다. 괄호 안은 확정된 DTO/adapter 인터페이스를 공유하고 파일 책임을 나눠 병렬 진행한다. 이 단계들 중 하나라도 미완료면 전체 workload 완료 단계로 넘어가지 않는다.

| 단계 | 구현 책임과 변경 위치 | 통과 기준 |
|---|---|---|
| T0 소스·입력 동결 | 신규 `scripts/fedplanner/snapshot_plan_space_versions.py`; 신규 `src/test/resources/fedplanner/plan-space/closed-comparison-cases.json`; 기존 inventory 도구 재사용 | B0/B1 ancestry·C0 source/build/input hash, 모든 discovery/조건 매핑, expected pair matrix가 고정되고 누락 시 gate 실패 |
| T1 공통 identity·계약 | 신규 `.../placement/shadow/PlanSpaceComparisonIdentity.java`, contract/test resources; 기존 snapshot adapter 활용 | 아래 physical key 필드·bijection/provenance 검증, rename/충돌/구분필드 삭제 mutation 통과 |
| T2 실제 P/E adapter | `FullProductionJointPlanExport`, `ExactPhysicalRawSpaceExporter`, `ExactPhysicalPlanSpaceExporter`, `PlanSpaceFixtureArtifactAdapter` 확장; 신규 DML/IR 입력 `PlanSpaceComparisonMain` | 같은 I에서 P/E의 native 전체 좌표/constraint를 캡처, 0 unknown/decoder error; B-21/B-22와 literal fixture의 완전한 양방향 집합 결과 |
| T3 역사 adapter | 신규 `scripts/fedplanner/legacy-adapters/ffb7be5/`, `d8fbd30b/` test bridge와 compatibility tests | 각 버전 P/E를 그 버전 native API로 추출; observation overlay가 domain/validator/winner를 바꾸지 않음; 동일 버전을 두 번 비교하면 정확 일치 |
| T4 정확 관계 열거 | 신규 test-owned `ClosedPlanRelationEnumerator`, `ClosedPlanRelationCoverageVerifier` 및 기존 raw enumerator | 단순 Cartesian 전체와 exact prefix/relation 경로의 집합·raw coverage 일치; 큰 relation의 완료 증거를 별도 verifier가 재검사 |
| T5 runner·certificate | 신규 `/home/mchoi/cofee-evaluation/calibration/plan_space_compare.py`와 tests; 기존 sort/merge/atomic/checksum 로직 재사용 | N-version native 집합의 pair matrix, 양방향 차집합, 병렬/resume, 독립 certificate check; 기존 full semantic gate 동작 유지 |
| T6 전체 실행·회귀 고정 | 신규 `scripts/fedplanner/run_plan_space_comparison.sh`; 단일 결과 보고서와 artifact bundle | 고정 분모 전부의 capture/export/compare 완료, 미처리 task 0; 현재 P/E 차이 수정·관련 및 전체 재실행, legacy 변화의 분류·witness 보존 |

### T1: identity를 먼저 맞춘다

Physical bytes에는 source 기반 occurrence/ordered input position, value version/call/recompile/control context, operation, execution/output/FType, worker/range geometry, producer-consumer binding, source/anchor/authority, relocation/derived/materialization 및 action 공유 관계를 포함한다. JVM HOP ID, ordinal/domain index, production normalizedSignature, `toString`, Git SHA, 도구 이름, 실행 시각을 버전 간 의미 identity로 쓰지 않는다. **source/build/version provenance를 plan key에 섞으면 모든 버전이 자동으로 달라지므로 audit 쪽에 둔다.** [E7,E8]

두 native 증명이 같은 physical plan을 뜻하면 그 동등성 규칙이 검증된 경우에만 합친다. 지원 clause/realization이 ordered binding이나 action/authority를 바꾸면 서로 다른 plan이다. Proof encoding multiplicity와 raw→physical mapping도 별도 저장해 OR witness가 사라졌는지 확인한다. Hash는 index이며 최종 동일성은 canonical bytes 또는 검증된 동일 관계 구조로 비교한다.

기존 56개 snapshot capture 성공은 identity 해결 증거가 아니다. 알려진 28개 null call-name에 대해서는 parser의 function signature/호출 위치, formal parameter index, ordered actual edge, output value lineage와 inlined boundary로 일대일 binding을 복원하고 회귀로 검증한다. 이름이 단순 표시 정보일 때만 nullable provenance로 남길 수 있다. 서로 다른 호출/반환을 구별할 수 없는 null을 같은 값으로 합치지 않는다. [E8]

### T2: P에서 현재 빠진 좌표를 닫는다

- decision 밖 owner를 fixed source, synthetic function boundary, 다른 context의 occurrence 등 **원래 역할에 따라** 명시적으로 연결한다. 모두 새 decision으로 만들거나 삭제하지 않는다. [E1,E6]
- execution receipt/provenance, durable anchor, derived-FOUT action, input authority, relocation demand/action/absence, realization/support, shared action에 필요한 좌표와 관계를 P-native facts에서 복원한다. E의 domain/factor는 coverage 점검 색인으로만 사용한다. [E2,E6]
- full candidate validator → explicit relocation validator → realization 교차검사의 순서를 유지하고, 입력/반환 receipt 집합이 canonical 정렬 외에는 바뀌지 않는지 확인한다. `select*`, completion search, partial validator 단독으로 leaf를 수용하지 않는다. Active universe가 비어 있지 않은데 candidate/demand 하나를 제거하면 반드시 거부한다. [E5]
- E는 original unquotiented domains와 hardFactors를 읽는다. 목적함수, winner, dominance quotient를 전체 집합 export로 사용하지 않는다. Factor exception/뜻밖의 numeric 값은 저장 후 오류로 처리하고 해결한다. `modelStatus`와 global semantic coverage를 분리한다. [E2]
- capture를 한 번 수행한 모델에서 size/domain hash와 export를 처리한다. `size`와 각 shard마다 달라진 analysis를 새로 빌드하고도 같은 모델이라고 가정하지 않는다. [E3]

### T3: 역사적 차이를 정확히 해석한다

B0/B1/C0 각각의 P↔E와 B0↔C0, B1↔C0의 P/E 차이를 모두 계산한다. 이로써 production 공간 확장, Exact model 변환의 누락, compiler 변화, 과거 validation 결함을 구분한다. 현재 버전의 P/E가 함께 빠뜨린 plan은 이 비교만으로 발견되지 않을 수 있으므로 R 작업은 여전히 남는다.

`scripts/fedplanner/legacy-adapters/`는 adapter source 템플릿 보관 위치다. 실제 Java bridge는 각 isolated worktree의 `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/` 등 원래 package-private API와 **같은 패키지**에 설치·빌드한다. 외부 scripts 경로의 클래스가 그 API에 바로 접근할 수 있다고 가정하지 않는다. Adapter/클래스패스와 관측 전후 native domain hash를 provenance에 남긴다.

T0에서 `REQUIRED`로 고정한 native baseline이 동일 입력에서 실제로 build에 실패하면 `BASELINE_BUILD_FAILURE`와 stack/input/source hash를 보존한다. Adapter API 호환 문제는 고치되 과거 production 의미론을 몰래 수정하지 않는다. 이 경우 집합을 빈 집합으로 만들어 equality를 내지 않고 해당 full pair는 non-PASS로 둔다. 오류 재현 보고가 끝난 것과 집합 비교가 완료된 것은 별도다. Baseline을 고친 실험이 필요하면 `B0-fixed`라는 다른 버전으로 등록한다.

### T4: 큰 공간을 끝낼 방법을 처음부터 포함한다

기존 B-21 raw 곱의 크기상 “행을 전부 JSON으로 쓰고 worker만 늘리기”를 기본 알고리즘으로 삼지 않는다. [E10]

1. 작은 fixture는 기존 no-prune mixed-radix raw 전수 경로로 기준 결과를 만든다. 초기 literal fixture에서 P/E exact set 비교를 실제로 먼저 성공시킨다.
2. 큰 모델은 state-compatible receipt와 explicit support/action 관계의 **lossless join**, exact prefix DFS와 잔여 관계 memoization으로 처리한다. Prefix reject는 이미 결정된 native hard constraint가 모든 suffix를 거부한다는 증거가 있을 때만 한다. 비용·heuristic preference로 가지치기하지 않는다.
3. 모든 branch의 선택 범위와 rejected suffix의 BigInteger cardinality를 기록해, accepted/rejected raw 영역의 합이 원래 Cartesian domain과 같고 교집합이 없음을 verifier로 검사한다. Nonlocal function/native cycle/shared action의 경계 변수를 빠뜨린 분해는 금지한다.
4. 허용 plan 자체가 거대하면 explicit canonical bytes의 정렬 chunk 또는 물리 좌표를 보존하는 정확 decision DAG/relation을 저장한다. 원시 증명 좌표를 제거하는 projection도 전체 대안의 합집합을 보존해야 한다. Relation node의 전체 payload, 공통 variable order/union domain과 양방향 diff 경로를 검사한다. Hash equality만으로 relation equality를 선언하지 않는다.
5. 작은 fixture 전수 일치는 필수지만 큰 입력의 유일한 근거가 아니다. 큰 실행에도 native 각 좌표·predicate의 coverage, 각 prefix의 native rejection 근거, 전체 branch 분할, decode/projection 보존의 기계 검사 증거를 저장한다. Opaque native predicate의 의존 범위를 입증하지 못하면 해당 prefix를 버리지 않고 leaf까지 평가한다.
6. Pilot에서 각 workload의 raw 규모, relation node/row 수, 처리율·RSS·compressed bytes를 측정한다. 그 결과로 jobs/heap/shard 크기를 결정하고 느린 cell만 분할한다. Resource 제한에 걸리면 frontier를 저장하고 계산·표현을 개선해 재개한다. sampling/timeout을 완료로 바꾸지 않는다.

### T5: 기존 UNKNOWN 강제를 약화시키지 않고 별도 비교 경로를 만든다

현재 `verify --suite full`의 R 요구와 rule gate는 그대로 둔다. 신규 `compare` 명령은 source label을 `{P_C,E_C,P_B0,E_B0,P_B1,E_B1}`로 받아 **각자의 domain·acceptance·decode**를 검사한다. 기존 `compare`, 정렬 chunk, atomic publish, checksum, shard coverage의 테스트된 구현을 공통으로 재사용한다. 단순 코드 복제와 별도 solver 의존성은 피한다. [E4,E11]

Manifest와 cache key에는 계약 버전, source tree/dirty patch/필요 untracked files, instrumented build/JAR/compiler, input/IR/capture, native domain/constraint, adapter, decoder/identity, relation-engine hash를 넣는다. P/E와 버전별 namespace를 분리한다. 고정된 과거 artifacts는 재사용하되 hash가 다르면 새 namespace를 쓴다. 실패/부분 shard는 완료 chunk로 채택하지 않는다.

## 6. 필수 검증 및 완료 기준

아래 항목을 모두 충족한 범위에만 `closed-model comparison complete`를 표기한다. “기반만 구현”, “몇 개 fixture 성공”을 전체 실행 완료로 삼지 않는다.

1. **분모:** 등록한 모든 workload/condition/version/applicability가 결과에 정확히 한 번 존재하고, 모든 `REQUIRED` pair의 비교 결과가 있어야 한다. 빠진 discovery/조건/task와 근거 없는 제외는 gate 실패다. 역사 `NATIVE_UNSUPPORTED`는 별도 집계하며 C P/E 분모를 줄이지 않는다. 현재 P/E가 둘 다 0인 real workload를 정상 성공으로 취급하지 않는다. 의도된 zero-plan negative fixture만 명시적으로 허용한다.
2. **P/E 독립 추출:** P adapter에서 `ExactPhysicalModel`·E factor를 호출하면 dependency test 실패. E adapter가 P의 decoded set을 재사용해 같게 만드는 경로도 금지한다. 공통 DTO/normalizer만 공유한다.
3. **표현 완결:** nondecision owner, synthetic function boundary, derived-FOUT, authority/anchor, repeated ordered input, support AND/OR, shared relocation, recompile를 포함한 모든 native 좌표가 export coverage에 잡힌다. Unsupported coordinate와 decode error는 0이어야 한다.
4. **Literal·mutation:** 수기 전체 집합 일치, 후보 삭제/불법 조합 삽입, AND→OR, active receipt/demand 삭제, binding 순서 교환, authority/worker geometry 변경, canonical 필드 삭제, 상수 hash 충돌을 모두 검출한다. 중복 proof를 넣어도 physical set은 같고 proof audit은 달라져야 한다.
5. **전수·빠른 경로:** 모든 작은 contract fixture에서 no-prune와 빠른 관계 열거기의 canonical full set, 방향별 diff, domain coverage가 일치한다. Prefix별 rejected suffix에 legal completion을 심는 mutation이 검출된다.
6. **역사 bridge:** B0-vs-B0와 B1-vs-B1은 입력/배열 순서·JVM ID가 바뀌어도 EQUAL. 단일 역사 후보 삭제를 주입하면 old-only 또는 new-only witness를 정확히 낸다. Adapter overlay로 native domain 또는 validator 결과가 바뀌면 실패한다.
7. **실제 차이 처리:** 현재 P/E 차이마다 최소 DML/IR, 원본 native witness와 source hash, 재현 명령을 보존하고 올바른 현재 production/model/adapter 층을 수정한다. C0를 보존하고 C1의 전체 matrix를 다시 실행한다. 역사적 차이는 추가/삭제/compiler/의미론 수정으로 분류하고 unexplained loss는 0으로 만든다.
8. **저장·병렬:** 1-worker=N-worker, cold=warm, 중간 kill/resume의 canonical set·relation 결과가 같다. shard gap/overlap/중복 완료·truncation/손상·stale version/schema·decoder exception을 주입하면 재검사가 실패한다. 같은 count지만 다른 set을 통과시키지 않는다.
9. **종료 증거:** 모든 필수 pair의 미처리 frontier·분류 불가·projection loss·adapter error가 0이고, 별도 `check-certificate`가 set/relation 차집합과 native coverage를 다시 계산해 같은 결과를 낸다. 확인된 baseline build failure나 표현 한계는 숨기지 않되 해당 pair의 완료로 세지 않는다.
10. **원래 gate 보존:** 기존 full semantic 인증이 R 증거 없이 PASS로 바뀌지 않는 회귀를 유지한다. Closed PE PASS와 `runtimeSemanticCoverage=NOT_ASSESSED_BY_THIS_CONTRACT`를 함께 검사한다.

## 7. 실행·저장 인터페이스 제안

아래 CLI는 구현해야 할 인터페이스다. 지금 존재하는 명령인 것처럼 실행 결과를 기록하지 않는다.

```bash
# orchestrator는 source freeze → build → capture → export → compare → check까지 수행한다.
scripts/fedplanner/run_plan_space_comparison.sh \
  --baseline ffb7be5bd85367156ed9ea86dacbaff4be0f035d \
  --bridge d8fbd30b5476a1ceef460c9f3886381a369ac619 \
  --current snapshot \
  --manifest src/test/resources/fedplanner/plan-space/closed-comparison-cases.json \
  --artifact-root /grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921 \
  --jobs 4 --resume

python3 /home/mchoi/cofee-evaluation/calibration/plan_space_compare.py \
  check-certificate --certificate CERTIFICATE --expected-campaign-sha CAMPAIGN_SHA
```

```
artifact-root/<campaign-content-hash>/
  campaign.json                 # source/input/contracts/versions/pairs/expected coverage
  versions/{B0,B1,C0,C1}/       # immutable source/build + instrumentation receipts
  cells/<input-hash>/capture/  # native compiler/IR/model domain/constraints
  cells/<input-hash>/<version>/{P,E}/  # tasks, frontiers, relations/chunks, audit
  comparisons/                # intersections, both differences, provenance, witnesses
  certificate.json
  FINAL_REPORT.md
```

공간·quota·RSS preflight 후 격리된 artifact와 Docker namespace에서 실행한다. 병렬도는 jobs 숫자뿐 아니라 JVM peak heap과 I/O 총량으로 제한한다. 기존 `MAX_SHARDS=10000`을 무조건 해제하지 않고 lazy frontier/task page와 exact coverage verifier를 함께 도입한다. [E4,E10,E11]

## 8. 위험과 대응

| 위험 | 계획에 반영한 대응 |
|---|---|
| UNKNOWN 문자열만 없애고 실제 P 누락을 덮음 | T2의 full native coordinate/leaf validation과 zero unresolved acceptance |
| 현재·과거 모두 같은 잘못된 공간을 만들어 같음 | closed-model 주장으로 한정; 수기/mutation 검사와 독립 R 의무 별도 유지 |
| 옛 Git에 최신 legality/realization을 이식 | 버전별 test bridge, production hash unchanged, native self-comparison gate |
| ID/proof 차이가 물리 차이로 오인되거나 실제 action 차이가 사라짐 | source/ordered relation identity, native→physical provenance, field mutation 및 no-loss gate |
| Compiler/runtime 수정으로 subset 가정이 깨짐 | pre-builder 구조 대조, compilerDelta, 필요한 same-IR replay; 정당한 삭제도 witness로 설명 |
| 현실적으로 너무 큰 raw 곱 | 작은 no-prune 기준 + 증거 있는 exact relation/prefix 경로, BigInteger 전체 coverage, resource pilot과 재개 |
| 공유 checkout이 도중 바뀜 | C snapshot 고정, source/build/instrumentation hash, 이후 변경은 C1 새 campaign |
| 실제 baseline 실패·표현 한계 때문에 모든 pair를 만들 수 없음 | 정확한 실패를 보존하고 adapter 문제와 과거 native 결함을 분리; 빈 집합/공통 projection으로 full 성공을 위장하지 않음 |

실행 순서는 **현재 작은 실제 P/E 비교를 먼저 완성 → P 누락 좌표와 빠른 exact 열거 해결 → B0/B1 연결 → 고정된 모든 workload 조건 실행 → 반례 수정·전체 재검사 → 단일 보고서**다. 성공한 closed comparison은 구체적인 집합 일치/차이로 매듭짓고, 미처리 작업이 남은 실행은 완료라고 보고하지 않는다.
