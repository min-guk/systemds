# StepLM CFG closure 수정

보고된 20×5 canonical fixture의 주기2 비수렴은 해결했다. 실제 CSV FED 학습 완료는 아직 확인하지 못했다.
원래 전체 DP compile의 factor overflow와 실제 CSV 공통 분석 지연은 아래와 같이 남는다.

## 범위와 기준

- 기준: fetch한 `origin/main` `93706bbaa9e054e8db4c014ce2dd529498fc0e2b`.
- 작업: `/home/mchoi/steplm-cfg-closure-20261007`, branch `fix/steplm-cfg-closure-20261007`.
- 기존 workspace는 수정하지 않았다. 원본 baseline 클래스는 읽기 전용으로 사용했다.
- 대상은 20×5 StepLM의 공통 placement closure다. 후단 cost-model factor overflow와 실제 학습 완료 여부는 별도로 판정한다.

## 원인과 수정

Snapshot CFG replay와 physical worklist가 서로 다른 간선을 따라 전달한다. StepLM에서는
`R281 → W282 → R414 → placement415 → W416 → R281` identity 경로가 한 번의 composed pass보다 길다.
기존 loop-entry seed를 한 pass 후 해제하면 R281/R414의 CP와 FULL이 서로 교환되어 주기2 진동을 만든다.
R281 및 exit R423의 reaching writers는 `{262,405,416}`이며, cbind398의 FULL과 single-partition 값은
양쪽 pass에서 그대로여서 cardinality 변경 가설은 진단으로 기각했다.

`PlacementRelationClosure.java`는 설치 이력과 현재 유효한 provisional seed를 분리한다.

1. 기존 규칙으로 인증한 entry seed의 source와 geometry 정책을 보존한다.
2. 해당 seed를 유지하면서 CFG replay, physical rebuild, direct grounding, value-map closure가 함께 안정될 때까지 전달한다.
3. seed를 해제하고 모든 reaching writer를 포함하는 transfer가 다시 안정되어야 반환한다.
4. 기존 ledger의 전체 writer 검증 이후에만 완료를 기록한다.

Provisional continuity는 all-definition cache와 분리한다. 최대 반복 횟수, candidate/factor encoding,
privacy/TR-TW/geometry 규칙은 바꾸지 않는다. CP 강제 축소나 runtime fallback을 추가하지 않는다.
독립 검토에서 final replay의 신규 seed 설치가 active 등록을 우회할 수 있음을 발견하여,
신규 설치는 pass 첫 replay에서만 허용하고 final replay는 기존 active seed만 재생하도록 보완했다.
보완 후 독립 읽기 전용 검토에서 추가 차단 사항은 없었다.

## 검증 계약

- `LoopIdentityBackedgeClosureTest.smallBuiltinStepLmCompletesCanonicalCommonClosure`: 원래 20×5 builtin StepLM 입력과
  동일한 pipeline의 canonical common analysis를 검사한다. 모든 reaching writer가 동일한 정확한 FED/FOUT/FULL
  reader realization을 지원하며, function input occurrence 관계가 중복되지 않아야 한다.
- 깊이1·2·4의 중첩 identity branch: 전체 writer 관계와 FULL 후보를 보존한다.
- PRIVATE_AGGREGATE 입력: incremental/full recompute의 fingerprint, facts, logical relations가 일치한다.
- `G=matrix(sum(G),rows=8,cols=1)` backedge: entry FULL만으로 호환되지 않는 loop FULL을 유지할 수 없다.
- 중첩 branch와 privacy 검사는 baseline도 통과하는 보존용 control이다. 비수렴을 검출하는 RED 증거는 builtin StepLM이다.
- Docker `ml_steplm`: opt-in 20×5 full-rank PUBLIC 입력, FULL worker1개, max20, CP/DP의 B 전체5×1과 S 선택 순서를 비교한다.
  원래 compile fixture와 같이 DP에서는 X와 Y를 모두 FED로 공급한다. CP에서는 같은 CSV 값을 local로 읽는다.

## 결과

아래 수치는 최초 수정 커밋 `30e822dacb`의 통합 전 검증이다. 이후 main 통합 검증은 문서 끝에 별도로 기록한다.

| 검증 | 결과 |
|---|---|
| 새 builtin StepLM common-closure 회귀 / baseline `93706bbaa9` | 264.837초 후 기존 CFG 비수렴, `f626/632`, pass1244 |
| 동일 회귀 / 최종 후보 | 30.419초 PASS, 모든 writer의 정확한 FULL 관계 유지 |
| 신규 Java 회귀4건 | 4/4 PASS, 총41.475초 |
| 최종 관련 Java 회귀12개 클래스 | 52 PASS, 기존 ignore5, failure/error0 (총57) |
| 최종 Maven package | BUILD SUCCESS, 4분23초 |
| Python Docker harness 회귀 | 28/28 PASS, `py_compile` 통과 |
| 독립 코드 검토 | late-install 보완 후 추가 차단 사항 없음 |
| 원래 전체 DP compile 테스트 / 최종 후보 | 38.065초 후 `EXACT_VE_FACTOR_CELL_OVERFLOW`; closure 비수렴은 통과 |

최종 Java source는 빌드 중 변경되지 않았다. Python harness는 Y도 FED로 공급하도록 빌드 중 변경했고,
별도의 Python 검증 후 Docker 실행 전에 다시 동결했다. `final-build-status.json`은 이 차이를 명시한다.
기존 unchecked/deprecation 경고는 있으며, 전체 별도 checkstyle/RAT 실행을 주장하지 않는다.
`git diff --check`도 확인한다.

### 소형 Docker fixture 준비 중 확인한 별도 문제

첫 `steplm-closure-baseline-01`은 X만 FED이고 Y가 local인 입력이었다. CP 학습은 완료했으나 baseline DP는
`Final publication has an ungrounded relocation realization: actionPresent=false|sourceLive=true`에서 실패했다.
위치는 builtin lmCG line129의 matrix multiply다. 이 실행은 원래 X/Y 모두 FED인 재현과 다르므로
closure 수정의 A/B 근거로 사용하지 않는다. raw evidence와 frozen runner/source는 그대로 보존한다.
최종 fixture는 원래 입력 경계를 따르며, `steplm-closure-baseline-02`와 `steplm-closure-fixed-01`로 비교한다.

### 후단 비용 모델 오류

`FederatedPlannerFallbackIntegrationTest.testDpPlansSteplmWithSameNamedFormalTransientBinding`는
최종 build에서도 실패한다. 실패 지점은 기존 CFG replay가 아니라
`ExactCategoricalSolver.checkedCells → validateInputStructure → ExactPhysicalCostModel.freezeOrdinaryFactorsAfterPreflight`다.
이 테스트를 통과했다고 집계하지 않는다. 후보/FED plan을 제거하거나 factor encoding을 임의로 바꿔 숨기지 않았다.
후속 작업은 overflow를 일으킨 실제 factor와 domain product를 식별하고 합법 계획·비용을 보존하는 표현을 검증하는 것이다.

### 실제 학습 검증의 한계

| 항목 | baseline-02 | fixed-01 |
|---|---:|---:|
| CP compile | 5.387220초 | 6.420474초 |
| CP 학습 실행 | 0.613초 | 0.673초 |
| CP 선택 순서 S | `[3,1,5]` | `[3,1,5]` |
| FED | common analysis 360초 timeout, rc124 | common analysis 360초 timeout, rc124 |
| FED planner checkpoint / runtime audit row | 0 / 0 | 0 / 0 |
| Docker class preflight / 별도 physical model proof | PASS / 10 PASS | PASS / 10 PASS |

입력/fixture/runner/image/dependency 해시는 두 실행이 일치한다. 후보의 동결 main source1,651개,
main artifact4,353개, test source1,936개, test artifact2,820개, dependency316개는 최종 build와 해시가 일치한다.
FED B/S 출력은 없으므로 계수·선택 순서·선택된 계획/비용·학습 실행시간 비교는 불가능하다.
runtime audit0건은 아직 실행하지 않았다는 뜻이며 runtime 검증 통과가 아니다.

CSV 입력에는 원본 compile fixture와 달리 실제 데이터와 S 출력이 있으며, common analysis가 더 오래 걸린다.
후보136초 스택은 memoized relocation identity normalization의 canonical ordering,
후보222초 스택은 `LoopSeedRevision.equals`의 중첩 support/reference 비교,
후보353초 스택은 outer pass의 `generateRelocationBindingProduct`를 가리킨다.
독립 진단은297초에 `changedCandidateOccurrences`까지 진행한 것도 확인했다.
관찰된 지연 경로는 내부 CFG pass의 주기2 진동과 다르며, outer pass에서 큰 후보 관계를 정렬·비교·재생성하는 CPU 작업이다.
스택 표본만으로 전체 실행의 비용 비율이나 모든 closure의 수렴을 증명하지 않는다. Baseline은 스택을 채집하지 않아
같은 병목이라고 단정할 수 없다.

후속 조사는 기존 canonical binding 순서를 relocation identity 교체 후에도 재사용할 수 있는지와,
ledger revision/후보 관계 비교에서 깊은 구조 비교를 반복하는 지점이다. revision 내용을 줄이거나 hash만으로 동일하다고
판정하면 잘못된 memo reuse가 가능하므로 이번 수정에 섞지 않았다.

## 변경 파일과 잔여 위험

- `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java`: seed 유지/해제 시점과 proof context.
- `src/test/java/org/apache/sysds/hops/fedplanner/placement/LoopIdentityBackedgeClosureTest.java`: 새 회귀4건.
- `scripts/fedplanner/run_joint_boundary_e2e.py`, `scripts/fedplanner/tests/test_run_joint_boundary_e2e.py`: opt-in StepLM 실행과 전체 B/S 비교.
- 이 보고서, 세션 이슈 문서, 검증 요약 JSON: 재현 및 미해결 상태.

잠재 회귀는 provisional entry proof가 최종 loop proof로 유출되거나, geometry 정책이 달라지거나,
late-installed seed가 한 pass만 유지되는 경우다. all-writer exact-reference 검사, incompatible backedge,
protected full/incremental parity와 신규 설치 지점 단일화로 해당 경로를 검증했다. 전체 프로그램 공간의 수렴을 증명한 것은 아니다.

## 실행 환경 기록

Docker 실행 중 공유 root filesystem의 여유 공간이0이 된 것을 발견했다. 기존 workspace나 다른 작업 파일은 지우지 않았다.
이번 worktree가 만든 `target/lib`만 `/grid/3/cofee-lm-sweep-mchoi-20260914/steplm-closure-20261007/build-dependencies`로
이동하고 원래 위치에 symlink를 두어 공간을 확보했다. Docker는 이미 동결한 dependency 복사본을 사용한다.
첫 thread dump는 이때 빈 파일로 끝났고, 공간 확보 후 별도로 채집한 두 dump만 원인 후보의 근거로 사용한다.
실행시간은 공유 환경의 관측이며 성능 향상률을 주장하지 않는다.

## 검증 범위 오류 기록

첫 Maven 회귀 실행에서 `LoopSeedReplayWideningTest` 클래스 전체를 지정하여 기존
`l2svmMemoReplayRebindsCurrentRelocationActionAuthority`의 50,000×2,100 메타데이터 분석1건도 실행되었다.
실제 대형 학습은 실행하지 않았지만 사용자의 대형 모델 제외 요청을 벗어난 선택 오류다.
이를 숨기거나 소형 검증으로 분류하지 않으며, 후속 실행은 해당 메서드를 제외한 명시적 목록을 사용한다.

## 재현 근거

- 상세 raw 로그: `/home/mchoi/steplm-cfg-closure-20261007/target/steplm-evidence/`.
- Docker raw evidence: `/grid/3/cofee-lm-sweep-mchoi-20260914/steplm-closure-20261007/`.
- 요약 JSON: `/home/mchoi/steplm-cfg-closure-20261007/docs/experiments/steplm-cfg-closure-20261007/validation.json`.
- 동결 image: `sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434`.
- 최종 회귀에서는 `LoopSeedReplayWideningTest`의 `failedLoopSeedAttemptDoesNotConsumeItsRevision`,
  `completedLoopSeedLeavesWidenedExitRetryableUntilItsOwnTransfer`, `privacyRejectedLoopSeedDoesNotConsumeEitherProofState`,
  `repeatedReuseUpdateLoopConvergesWithOneAnalysisScopedSeed`,
  `protectedAlsLoopRetainsWidenedNativeSourceAndAllReachingWriters`만 지정했다.
- 진단 overlay의7pass 중단은 원인 관찰용이며 production 상한 변경이 아니다.
- 수정 전후 실행시간은 공유 호스트의 단일 관측이다. 원본은 실패하고 후보는 closure만 통과하므로 학습 속도 향상 비율로 해석하지 않는다.

새 회귀를 다시 실행하는 최소 명령은 다음과 같다.

```bash
mvn -B -Djacoco.skip=true -Dtest-forkCount=1 -Dtest=LoopIdentityBackedgeClosureTest test
```

실제 소형 학습 재검증 명령(새 run ID 사용):

```bash
bash scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e \
  --case ml_steplm --run-id steplm-closure-recheck-01 \
  --output-root /grid/3/cofee-lm-sweep-mchoi-20260914/steplm-closure-20261007 \
  --case-timeout-seconds 360 --timeout-seconds 1200
```

## origin/main 게시 통합

- Push 요청 후 fetch한 main은 `73d1eb024f6f70a7d869f363d895d72313bcbff6`이다.
- `30e822dacb`의 seed 수명 수정에 main의 partition-proof worklist와 opt-in JFR 기능을 통합했다.
- Java는 자동 병합되었고, 세션 문서와 Python harness/tests의 충돌은 양쪽 기능·기록을 모두 유지하여 해결했다.
  StepLM의 S 일치 조건과 JFR 활성화 시 profile 성공 조건을 함께 유지한다.
- 독립 검토에서 Java 의미상 충돌은 발견하지 못했다. partition proof는 각 불변 inventory 안에서 계산하며,
  바깥 physical owner 갱신 시 기존 proof cache 무효화가 유지된다.
- CSV는 기존 실험 하네스의 데이터 공급 형식을 재사용한 추가 합성 학습 검증이다. 기존 운영 데이터나 동일한 전체 캠페인을
  실행했다는 의미가 아니며, 보고된 비수렴 수정의 필수 입력 형식도 아니다.
- 원래 Docker 측정은 통합 전 source에 대한 기록으로 보존한다. 이번 게시 통합에서 실제 CSV 학습을 다시 실행하거나
  앞서 관찰한 timeout/overflow가 해결되었다고 주장하지 않는다.
- 통합 후 Java13개 클래스61 PASS/기존 skip5/failure·error0, Python30 PASS, py_compile/diff check,
  Maven package(4분12초)가 통과했다. StepLM canonical 회귀는26.904초에 통과했다.
  동결 source3,578개는 빌드 중 변경되지 않았다. 대형 metadata 메서드는 실행 목록에서 제외했다.
- 게시 검증: `docs/experiments/steplm-cfg-closure-20261007/publication-validation.json`.
