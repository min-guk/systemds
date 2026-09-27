# Session issues — 2026-09-28

## 공통 작업 범위와 검증 계획

- **요청:** 이전 체크포인트에 남은 correctness 3건을 모두 해결한다.
- **시작 버전:** `9172a08e288756bed31305c0a812bbff37e495cd`.
- **상태:** 요청한 3건 해결 및 최종 clean 회귀 검증 완료. 기존 48개 클래스는 실패/오류0이다. 확대 검사에서 별도의 기존 Exact cap 오류1과 미완료 추가 FedAll 검사는 아래에 명시한다.
- **고정 제약:** 기존 assertion과 B-21 raw192/physical36 기대값 유지, 새 skip/임의 candidate cap 금지, privacy 완화 금지, TRead/TWrite의 CP/LOUT 또는 FED/FOUT 계약 유지, recompile CP/FOUT 금지, runtime fallback 금지.
- **작업 순서:** (1) 기존 3개 테스트 RED 재현 → (2) 실제 runtime/authority 계약에 근거한 원인 분리 → (3) 좁은 production 수정과 회귀 보강 → (4) 원래 3개 및 관련 회귀 → (5) 이전 48개 클래스 clean 통합 검증과 변경 범위 검사.
- **병렬화:** Oracle, loop, B-21 원인 분석은 독립적으로 수행한다. 공유 builder 변경은 root가 조율하며, Maven은 기존 `all14-searchspace20-20260927/local-maven.lock`으로 직렬 실행한다.
- **증거 디렉터리:** `/home/mchoi/w1357-diagnostics/correctness-three-20260928/`.
- **범위 밖:** 성능 최적화/목표 재개, workload/input/iteration 축소, 자동 commit/push. 기존 20초 성능 목표는 paused 상태를 유지한다.

### Fresh baseline

- 원래 3개 메서드를 그대로 실행하여 **3건 / 실패2 / 오류1 / skip0** 재현.
- 명령: `mvn -q -Dtest-forkCount=2 -Dtest=OracleFacadeTest#binaryFullMatrixWithLocalMatrixDoesNotRequireEncodedWidth,LoopSeedReplayWideningTest#protectedAlsLoopRetainsWidenedNativeSourceAndAllReachingWriters,CurrentPePhysicalSetCorrespondenceTest#allViableProtectedFixturesHaveEqualPhysicalSets test`.
- `baseline-red.json`: 16.314초, 실행 전후 전체 `src`+`pom.xml` 해시 동일(`ab3a554042bf8696e6ad08eca1ec16fb40177a9044725cb1519306dfab786a08`).
- 이전 기록만 재인용한 것이 아니라 현 checkout에서 세 증상을 다시 확인했다.

## 1. Oracle FULL matrix와 local matrix의 실행 capability

- **상태:** 해결; 최종 clean 회귀 검증 완료.
- **환경/조건:** `OracleFacadeTest.binaryFullMatrixWithLocalMatrixDoesNotRequireEncodedWidth`.
- **증상:** FED를 기대한 입력 조합에서 CP 반환.
- **재현:** 공통 baseline 명령과 `baseline-red.log`, `baseline-red.json`에 기록한다.
- **원인 분석:** 기존 fixture가 `[FULL, local]`을 요청하면서 FULL 입력을 일반 TRead로 만들었다. 실제 range cardinality 근거가 없어 `fullSinglePartition=UNKNOWN`이며 Oracle의 CP 판단이 올바르다. `BinaryMatrixMatrixFEDInstruction.java:164–177`은 FULL+local에 단일 range를 요구하고, `Rulesets.java:3289–3302`의 gate는 이를 보존한다.
- **의사결정 근거:** production Oracle/runtime은 올바르므로 이를 완화하지 않고 테스트가 전제하는 single-range source authority를 fixture에 제공한다. unknown encoded width와 원래 FED/FOUT/FULL 기대값은 유지한다.
- **해결/수정 파일:** `src/test/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacadeTest.java`. positive case는 explicit one-range federated source를 사용하고 `fullSinglePartition=true`만 참조함을 검증한다. 기존 TRead/unknown cardinality는 CP/LOUT + `FULL_MULTI_PARTITIONS_UNSUPPORTED`로 거부된다는 companion regression을 추가했다.
- **검증:** `OracleFacadeTest` **26/26 PASS**, `RulesetsGuardTest` **12/12 PASS**. 같은 Maven 실행의 alsCG 단일 테스트만 기존 오류를 재현(전체39/실패0/오류1). `oracle-green-loop-audit.json`에 source 불변과 suite별 결과가 있다. `git diff --check` 통과.
- **잔여 이슈:** 해당 gap은 해소했으며 production Oracle 변경은 없다. 최종 clean48 대상에서 실패/오류0을 확인했다.
- **잠재 회귀 위험:** unknown shape를 근거 없이 같은 shape로 가정하거나 지원되지 않는 broadcast/partition 조합을 허용할 수 있음. 실제 runtime 분기와 음성 테스트로 감지한다.

## 2. alsCG loop의 protected all-reaching-writer 배치

- **상태:** 해결; 최종 clean 회귀 검증 완료.
- **환경/조건:** `LoopSeedReplayWideningTest.protectedAlsLoopRetainsWidenedNativeSourceAndAllReachingWriters`; 비정본 alsCG 4×2/rank2 fixture.
- **증상:** recompile loop-body의 `TRead W`에 `No privacy-safe physical placement` 오류, privacy는 PRIVATE_AGGREGATE.
- **재현:** 공통 baseline 명령과 `baseline-red.log`, `baseline-red.json`에 기록한다.
- **원인 분석:** `recordCompletedLoopSeedRevisions`가 실제로 seed를 실행한 entry뿐 아니라 새로 widening된 exit revision도 completed로 등록했다. 다음 CFG invocation이 아직 seed하지 않은 exit를 memo replay로 건너뛰어 carried writer가 CP-only로 남았다. fresh `0d3f7f2e` baseline은 hop448 widening 뒤 hop442도 FED/FULL로 넓어졌지만, 수정 전 current는 hop448만 넓어지고 hop442가 CP-only였다.
- **반증 이력:** `incrementalDirectClosure=false` 전체 rebuild와 `changedOrdinals.isEmpty()`일 때만 exit를 memo하는 실험은 모두 같은 오류였다. 두 진단 수정은 정확히 원복했다. pending physical 유무는 새 proof revision이 실제로 seed되었다는 증거가 아니다.
- **의사결정 근거:** 실제 수행한 transfer의 entry만 memo해야 한다. privacy/TR-TW/recompile 완화나 임의 재시도 fallback이 아니라 revision별 one-shot 소유 계약을 복원한다.
- **해결/수정 파일:** `NeutralPlacementGraphBuilder.java`의 speculative exit memo 제거, 그 용도로만 쓰던 private 인자/locals 정리. entry memo, all-reaching-writer 검증, changed-ordinal 전달 및 원래 ALS 테스트 기대값은 유지한다. `LoopSeedReplayWideningTest.java`의 기존 dual-memo 단위 계약과 JavaDoc을 entry-only로 정렬하고, exit의 별도 transfer 전 미등록/실행 후 등록, 같은 payload의 idempotency와 충돌 payload 거부를 검증한다.
- **검증:** fresh isolated `0d3f7f2e` baseline 원래 테스트 1/1 PASS (`loop-baseline-0d3.json`, source 불변). 수정 후 원래 `LoopSeedReplayWideningTest` **6/6 PASS**, 10.626초 (`first-integrated-focus.json`, source 불변).
- **잔여 이슈:** 해당 gap 및 broader closure/native 회귀는 최종 clean 검증에서 통과했다. canonical ALS 성능 통과와 이 fixture의 correctness는 구별한다.
- **잠재 회귀 위험:** provisional seed를 최종 모든 writer의 증명으로 오인하거나 stale support를 복원할 수 있음. exact writer support 및 no-seed/negative privacy 회귀로 감지한다.
- **성능 위험:** 새 exit revision에 필요한 실제 seed pass가 다시 수행되므로 closure pass가 늘 수 있다. 동일 entry revision 재실행은 기존 full-key memo로 막으며 이번 작업에서 성능 개선을 주장하지 않는다.

## 3. B-21 physical plan 집합 보존

- **상태:** 해결; 최종 clean 회귀 및 physical multiset 검증 완료.
- **환경/조건:** `CurrentPePhysicalSetCorrespondenceTest.allViableProtectedFixturesHaveEqualPhysicalSets`의 B-21.
- **증상:** 원래 raw proof192/physical36 대비 raw160/physical30. 현재 P/E 상호 equality만으로 기존 공간 보존이 입증되지 않음.
- **재현:** 공통 baseline 명령과 `baseline-red.log`, `baseline-red.json`에 기록한다.
- **원인 분석:** fresh clean baseline과 current의 physical multiset을 비교한 결과 current는 strict subset이며 **6개 physical / 32개 raw proof 유실**, 추가 계획0, 공통30개 multiplicity 변화0이다 (`b21-fresh-before-comparison.json`). 누락 계획은 ordinary `ua(+R) FED/FOUT/ROW` 결과를 `TWrite Y CP/LOUT`의 `ABSENT_LOCAL` 모드로 소비하는 합법적인 coordinator 경계다. producer의 output placement와 consumer의 native/local input mode를 혼동한 `inputDomains()` 재계산이 이를 제거했다.
- **합법성 근거:** `ExactPhysicalModel.inputSatisfied` 및 `inputAuthorityPlacementSatisfied`는 NATIVE_LOCAL을 producer FOUT와 독립적으로 허용한다. 해당 rowSums 결과는 PRIVATE_AGGREGATE_TO_PUBLIC이며 canonical DOWNLOAD 비용과 `LocalMaterializationSelections`가 local 경계를 소유한다. 이 경로는 upload fallback이 아니다. 초기의 'stale invalid baseline' 가설은 위 계약과 독립 review에 의해 철회했다.
- **의사결정 근거:** 정확한 fact 변화의 invalidation은 유지하면서, privacy-safe한 PAYLOAD 경계에 executable ordinary FOUT source가 있으면 합법적인 local input mode를 재생성한다. metadata의 PRESENT map 읽기, function placeholder의 logical boundary 소유권, derived FOUT의 기존 LOUT source view는 별도로 보존한다. PART/OTHER에 upload 제한을 download 제한으로 전이하지 않는다.
- **해결/수정 파일:** `NeutralPlacementGraphBuilder.java`의 physical-rebuild input-domain 보정 및 `CurrentPePhysicalSetCorrespondenceTest.java`의 구체적인 source/input/local-action 검증. `ExactCompiledMaterializationScopeTest.java`는 동일 exact edge의 ordinary FED/FOUT/ROW → CP/LOUT ABSENT_LOCAL에 canonical DOWNLOAD/ANCHOR_TRANSFER 비용이 유한·양수임을 직접 검증한다. 원래 raw192/physical36과 P/E 집합·multiplicity assertion은 그대로 유지한다.
- **검증:** first integrated focus에서 `CurrentPPhysicalPlanRowsTest` 5/5 PASS, `CurrentPe...`의 원래 192/36·집합·multiplicity는 통과. 새 semantic assertion의 export-path와 compiled-path 혼동으로 발생한 `NoSuchElementException`은 실제 Hop identity와 정확한 compiled edge lookup으로 수정했다.
- **잔여 이슈:** FunctionOp logical 경계 제외, PAYLOAD-only, origin-residency 거부, executable source realization 확인 및 indexed lookup은 독립 review에서 확인했다. `b21-fixed-v4-comparison.json`은 clean old baseline과 raw192/physical36, 모든 physical identity 및 multiplicity가 정확히 같음을 검증한다. 최종 clean48 회귀도 실패/오류0이다.
- **잠재 회귀 위험:** 숫자만 회복하려고 무효 proof/중복을 추가할 수 있음. 사라진 대안의 runtime/support 합법성을 직접 설명하고 exact physical identity 비교로 감지한다.

## 4. 수정 과정에서 발견한 회귀와 리뷰

- **상태:** 수정 및 focused 재검증 완료.
- **증상/원인:** B-21 input lookup을 exact consumer/position index로 바꾸는 과정에서 초기 physical closure overload의 `compiledInputEdges == null` 계약을 놓쳤다. `corrected-focus-boundaries.json`의 실패3/오류9는 모두 이 새 NPE에서 파생했다.
- **해결:** null context에서는 immutable empty index를 사용하고 population을 건너뛴다. full context에서만 exact edge를 조회하므로 기존 초기 단계 동작을 보존한다. duplicate edge는 계속 fail-fast한다.
- **수정 파일:** `NeutralPlacementGraphBuilder.java`.
- **검증 계획:** 기존에 오류가 드러난 loop, physical certificate, privacy negative, B-21 export/correspondence를 모두 다시 실행한다. NPE가 예상 privacy 오류를 가리지 않는지도 확인한다.
- **독립 리뷰:** production/Oracle/B-21 semantics review 후 loop JavaDoc/unit의 구형 dual-memo 계약을 정렬했다. 후속 review에서 그 정렬과 null-index guard에 **APPROVE**, 미해결 finding0을 받았다. 비용 회귀 추가분은 별도 review한다.
- **잔여/위험:** 중간 실패도 이력으로 보존하며 최종 실행 결과와 구분한다. 조기 closure, metadata, function-boundary 회귀는 final suite에서 통과했다.

### 추가로 확인한 경계 조건

1. **Empty domain:** 지원 없는 predecessor는 `inputDomains()`에서 immutable `List.of()`를 반환한다. 새 helper의 `contains(null)`이 NPE를 발생시켰다. `domain.isEmpty() || domain.contains(null)`로 short-circuit해 empty/bottom을 그대로 전파한다. local 후보를 추가해 invalid predecessor를 되살리는 수정이 아니다. `matchingFTypeDoesNotAlignDifferentWorkerPools`는 다시 원래 예상한 privacy-safe 배치 거부로 통과한다.
2. **B-21 occurrence 식별:** rowSums op-string은 synthetic function output과 main compiled node 두 곳에 나타난다. export의 `block/main/1`과 실제 compiled key의 `main/1`도 다르다. 전역 opcode 개수나 export 경로에 기대지 않고, unique `TWrite Y`를 먼저 찾아 그 정확한 compiled input0 producer를 검사한다. 실제 endpoint 조회(`b21-actual-endpoints.log`)와 성공한 비용 테스트로 확인했다.
3. **재검증:** `boundary-focus-v4.json`: 10개 클래스, **65건 = 61 PASS / 기존 skip4 / 실패0 / 오류0**, 53.582초. 원래 3개 failing method, loop6건, Oracle26건, RulesGuard12건, metadata/privacy/function-boundary 및 B-21 local-action/비용 검증을 포함한다. 소스 해시 `66aeaec71b3b6c51e4422e057c09a3504aefdfee2723b04f573588e5423ef03b`가 실행 전후 동일하다.
4. **최종 독립 리뷰:** entry-only memo 문서/테스트, nullable edge index, empty domain guard, 두 exact endpoint 회귀 및 비용 의미를 재검토하여 **APPROVE / 미해결 finding0**.

## 5. 확대 검증에서 분리한 기존 한계와 미완료 추가 검사

- **상태:** 요청한 3건과 별도. 숨기거나 cap을 높이지 않는다.
- **기존 Exact 비용 materialization 한계:** 추가한 검사 `ExactPhysicalModelCertificateTest.sevenWorkloadsBuildBaselineFreePhysicalDomainsAndFactors`에서 `EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED|cells=71606548|limit=60000000`가 발생했다. 변경 전 `9172a08e`를 별도 clean worktree에서 실행한 `boundary-baseline-9172.json`도 **동일 testcase, 동일 cells/limit**로 실패했다. 이는 이번 patch의 새 correctness 회귀가 아니며, 기존 cap을 상향하거나 후보를 삭제하지 않았다. 그 baseline에서 나머지 certificate7건 및 privacy의 활성7건은 통과하고 기존 public skip4건은 유지했다.
- **추가 FedAll 검사의 실행 한계:** `CampaignBG014AbsentLocalMaterializationLoweringRedTest`는 v3에서 수 분간 FedAll candidate search를 진행했다. 다른 실패 원인이 이미 확인돼 root가 해당 소유 fork만 종료했다(전체 v3 270.664초). `boundary-focus-v3-long-fork.jstack`과 `boundary-focus-v3-interruption.json`을 보존했다. 이 실행은 PASS나 correctness 실패로 간주하지 않는다. 원래 48개 회귀 클래스에 없던 추가 검사이며, 최종 clean58 대상에서는 분리한다. 최종 결과에 미완료 검증으로 남긴다.
- **검증 범위:** 원래 48개 클래스는 전부 clean58 선택에 그대로 포함한다. 위 pre-existing Exact cap testcase도 최종 clean58에 포함하며, 최종 전체 결과를 모두 green이라고 표현하지 않는다.

## 6. Clean 확대 검증에서 발견한 edge-index 중복 scan 회귀

- **상태:** 해결; focused 및 최종 clean 재검증 완료.
- **증상:** `final-clean-58.json`은 58개 클래스 / 529건 / 실패3 / 오류1 / 기존 skip10이었다. 오류1은 앞서 A/B로 확인한 기존 Exact cap이며, 새 실패3은 `PhysicalGenerationEnvelopeTest`의 compiled-edge traversal 기대값 1→2, 1→2, 2→3이다.
- **원인:** B-21 helper용 eager edge-index 생성이 기존 `MaterializationProofInventory`의 lazy index 생성과 별도로 전체 edge list를 한 번 더 훑었다. `CountingEdgeList`가 이를 정확히 검출했다. 이 테스트는 physical-close를 직접 호출하므로 entry-only loop memo 변경과 무관하다.
- **해결/근거:** 독립 index를 삭제하고, 같은 committed revision의 기존 proof inventory가 helper와 materializer의 lazy edge index를 모두 소유하게 한다. direct predecessor는 privacy/executable source의 필요조건 검사에만 사용하고, 후보를 복원하기 전 inventory의 exact compiled edge로 producer identity를 반드시 다시 확인한다. commit 시 기존 inventory invalidation을 그대로 유지한다.
- **금지한 우회:** iterator 대신 indexed `get()`으로 계측 회피, 원래 1/1/2 assertion 변경, exact compiled-edge authority를 Hop topology로 대체하는 수정은 하지 않는다.
- **수정 파일:** `NeutralPlacementGraphBuilder.java`만. `PhysicalGenerationEnvelopeTest`는 변경하지 않는다.
- **검증:** `shared-inventory-focus.json`: 11개 클래스 / **72건 = 68 PASS / 기존 skip4 / 실패0 / 오류0**, 55.936초, 소스 전후 동일. 원래 `PhysicalGenerationEnvelopeTest` 7건과 그 1/1/2 traversal assertion을 변경 없이 모두 통과했다. `b21-final-comparison.json`에서도 최종 소스의 B-21 raw192/physical36 집합과 중복도가 old clean baseline과 완전히 같다. 원래48개를 포함한 clean58 재검증에서도 이 클래스는 7/7 PASS다.
- **잔여 위험:** 동일 generation 안에서는 index 공유, commit 뒤에는 재생성이라는 경계가 어긋나면 stale authority를 사용할 수 있다. 세 원래 traversal assertion 및 changed-pool semantic assertion으로 검출한다.


### 최종 코드 리뷰 및 성능 경계

- 공유 inventory 구현에 대해 독립 reviewer가 **APPROVE**했다. correctness의 critical/high/medium finding은 없다.
- 비차단 LOW 관찰: 최초 resolver query에서 `nodesByKey`를 index와 resolver가 각각 만드는 경로가 있다. node-map 생성 중복을 후속 성능 작업에서 재사용으로 줄일 수 있다. 이번 검증에서는 source를 freeze하고 추가 최적화하지 않았다.
- 실제로 소비한 loop revision에 필요한 seed pass는 복구했고, 이로 인한 planning 시간 변화는 별도 Docker 성능 검증이 필요하다. 이 문서의 Maven 시간은 workload planning latency가 아니다.
- production 코드에서 새 runtime fallback, privacy 완화, 후보 cap, heap 증가, 테스트 skip 또는 기존 B-21 count 하향은 하지 않았다.

### 최소 재실행 커맨드

```bash
cd /home/mchoi/w1357-logreg-nary-fix
mvn -q -Dtest-forkCount=2 \
  -Dtest=OracleFacadeTest,RulesetsGuardTest,LoopSeedReplayWideningTest,CurrentPePhysicalSetCorrespondenceTest,PhysicalGenerationEnvelopeTest,ExactCompiledMaterializationScopeTest,PrivacyMovementCertificationTest \
  test
```

전체 clean58 선택은 증거 디렉터리의 `final-clean-58-class-selector.txt`, 실제 명령과 소스 해시/개별 결과는 `final-clean-58-v2.json`에 보존한다. 검증 시 Maven 동시 실행을 피하고 기존 `local-maven.lock`을 사용한다.


## 최종 결과 — 요청한 3건 해결

| 검증 범위 | 실행 | PASS | 기존 skip | 실패 | 오류 |
|---|---:|---:|---:|---:|---:|
| 기존 48개 클래스 + 추가 Oracle 음성 회귀 | 469 | 463 | 6 | 0 | 0 |
| 확대 clean 58개 클래스 전체 | 529 | 518 | 10 | 0 | 1 |

- **원래 실패 3개 메서드는 모두 PASS**이며, clean 결과 XML에서 각각 실패/오류/skip이 없음을 확인했다.
- 확대 검사의 오류1은 `ExactPhysicalModelCertificateTest.sevenWorkloadsBuildBaselineFreePhysicalDomainsAndFactors`의 기존 materialization cap(71,606,548 > 60,000,000)이다. 별도 clean 변경 전 checkout에서도 동일하게 재현했다. 따라서 **요청한 3건은 해결**했지만 **전체 저장소/모든 runtime correctness가 검증됐다고 주장하지 않는다**.
- B-21은 raw192/physical36을 유지하고 old clean baseline과 exact physical multiset이 동일하다. missing/added physical plan0, multiplicity 차이0이다. canonical LOCAL action과 양수·유한 DOWNLOAD 비용도 통과했다.
- 최종 clean 실행: `final-clean-58-v2.json`, 196.060초. Maven 실행 전후 전체 `src`+`pom.xml` 해시: `2fba66e380ad35e72353fed3e189056d2aa16a5a3d14b6778fe2b9f9cdb7f4dc`로 동일하다.
- `acceptance-result.json`과 `verify-acceptance.py`는 original48 포함 여부, 원래3 method PASS, 유일한 오류의 baseline 동일성 및 B-21 multiset 동등성을 기계적으로 검증한다.
- Java17 clean production/test compilation 및 원래7-test inventory 회귀 통과, 최종 `git diff --check` 통과. 별도의 전역 Checkstyle green 또는 전체 runtime E2E 통과는 주장하지 않는다.
- 추가 FedAll 검사 미완료와 LOW node-map 중복 생성은 위 절의 남은 검증/성능 항목이다. Docker canonical workload 성능 측정이나 20초 목표 재개는 하지 않았다.
- 위 correctness 검증은 커밋/푸시 전 working tree에서 수행했다.

### 변경 파일과 단순화

1. `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java`
   - 수행하지 않은 exit memo와 이를 위한 인자/계산 삭제.
   - privacy-safe ordinary FOUT→local input을 exact edge authority로 복원.
   - 별도 eager index를 삭제하고 기존 revision-scoped lazy inventory index 재사용.
2. `src/test/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacadeTest.java`
   - FULL single-range fixture 근거 및 unknown-cardinality 음성 회귀.
3. `src/test/java/org/apache/sysds/hops/fedplanner/placement/LoopSeedReplayWideningTest.java`
   - entry-only memo 단위 계약 정렬; 원래 ALS failing method는 그대로 유지.
4. `src/test/java/org/apache/sysds/test/component/federated/placement/shadow/CurrentPePhysicalSetCorrespondenceTest.java`
   - 기존192/36 유지 + exact ordinary FOUT/local-action 의미 검증.
5. `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCompiledMaterializationScopeTest.java`
   - B-21의 exact 다운로드 비용 회귀 추가.
6. `docs/SESSION_ISSUES_2026-09-28.md`
   - 원인, 중간 실패, 해결, 재현 및 검증 한계를 기록.
