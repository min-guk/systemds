# Reuse factor encoding: 실제 ML training 실행 비교

**측정 완료. 대형 모델은 제외했다.** LM/KMeans W1·W3의 실제 학습 48회에서 OR-tail contraction의 일관된 실행시간 개선을 확인하지 못했다. 앞선 semantic fusion 보고서의 정의 통합은 실행 코드가 같은 변경이며 그 자체에 성능 개선을 주장할 수 없다. 이번 비교는 실제 실행 경로에 들어가는 선택적 OR-tail contraction의 효과를 측정한다.

## 비교 대상

- A: 앞선 조사에서 fetch한 `origin/main` `8f6bb285e5a27b52308ceceb63ceae2445b22782`, production source 수정 없음.
- B: 같은 commit에서 만든 `/home/mchoi/reuse-training-tail-20261006`, `ExactActivationClassFactorDecomposition.java` 한 파일의 production 변경. 마지막 OR Boolean을 terminal creation-price factor에 흡수한다. canonical monetary evaluator, legal-space guard, 원본 supply decisions는 유지한다.
- semantic relation 정의와 선택적 내부 encoding 최적화는 별개다. B는 원래 보고서에서 일반적 채택을 권고하지 않은 후보를 실제 workload에서 평가하기 위한 실험이며 배포/병합하지 않았다.
- `n` active consumers의 class는 auxiliary `n→n−1`, factors `n+1→n`. 마지막 monetary scope는 source/last-consumer/prior의 identity-dedup 집합으로 최대 3이다. 전체 validity factor scope를 3 이하라고 주장하지 않는다. terminal domain product는 증가할 수 있다. source와 마지막 consumer가 서로 다르고 domain 크기가 S,D이며 prior bit가 있을 때, 기존 tail 두 table의 logical cells는 `4D + 2S`, 합친 table은 `2SD`다. 따라서 factor 수 감소만으로 시간·저장량 개선을 보장하지 않는다.

## 실제 workload와 실행 조건

최종 범위는 **LM/KMeans, 각각 W1/W3**이다. 앞서 시도한 LogReg/ALS는 사용자 지시로 제외했고, 진행 중이던 LogReg W3 후보 실행도 중단했다. 입력 크기와 LM/KMeans 학습 설정은 줄이지 않았다.

ML10의 기존 frozen-data 계약 `/home/mchoi/cofee-evaluation-glm-figure/config/SOURCE_STAGE_CONTRACT.json`을 사용했다. 실제 binary blocks는 `/home/mchoi/cofee-ml10-analysis-shape-backend-20260909/stage/data/{binary,continuous}`에 있다. ADULT는 이 프로젝트의 shaped dataset alias이며 원본 UCI Adult를 그대로 썼다는 의미가 아니다.

| Workload | 데이터/학습 설정 | 검증 출력 |
|---|---|---|
| LM | continuous 50,000×128, `lm(tol=1e-9)`; 128열에서 lmDS 경로 | 128×1 coefficient |
| KMeans | continuous 50,000×128, k=50, max_iter=60, runs=1, eps=1e-9, seed=133815928 | 1×50,000 labels |

X는 PRIVATE_AGGREGATE, worker Y는 PUBLIC인 원래 혼합 privacy 계약을 유지한다. 학습 반복 수나 데이터 크기를 줄이지 않았다. W1/W3은 원래 1/3 shard를 각각 1/3 worker JVM으로 읽는다. 템플릿의 입력 주소/출력 경로만 변경했다. 모든 성능 실행은 `run_LAN_docker.sh --reuse-training-compare`로 진입한다.

- Docker image `sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434`.
- container당 8 CPU quota, cpuset 32–39, 16 GiB; coordinator heap 8 GiB, worker heap 각각 2 GiB. 원래 MKL config 유지.
- 같은 컨테이너 안의 loopback worker 통신, `--network none`. **실제 데이터의 전체 학습이지만, 여러 물리 호스트의 LAN/WAN 성능 측정은 아니다.** 다른 campaign의 container나 데이터는 변경하지 않았다. 호스트의 다른 작업으로 인한 시간 변동은 남는다.
- fresh worker/coordinator JVMs; 반복마다 A/B 실행 순서를 교대. seed=2026072701, statistics/trace/runtime audit 설정 동일.
- 동일하게 고정한 기존 cost environment는 `/home/mchoi/function-boundary-ablation-20261006/cost-environment.json`에서 가져왔다. 모델의 network 비용을 loopback 실측 calibration이라고 해석하지 않는다. modeled objective와 wall time은 서로 다른 수치다.
- frozen production classes, 공통 probe/test classes/dependencies/builtins/data에 SHA-256 inventory를 남겼다. 런타임 fallback/repair는 0이어야 통과하고 `-noFedRuntimeConversion`을 적용한다.

## 시간 및 동등성 판정

`compileNanos`는 초기 컴파일, `executionNanos`는 초기 컴파일 뒤 실제 실행(그 안의 동적 재컴파일 포함), `probeWallNanos`는 probe 내부에서 계측한 컴파일·실행·검증 구간이며 JVM 시작과 receipt JSON 저장은 제외한다. container wall은 worker/JVM 시작과 정리까지 포함하므로 학습시간과 구별한다. `planningFullInitialNanos`는 이름과 달리 planner만의 순수 optimizer 시간으로 간주하지 않는다. `candidateE2E.optimizerNanos`와 phase counters를 따로 기록한다.

전체 출력 CSV를 새로 A/B 비교한다. 기존 reference는 계약상 metadata-only라 정답 oracle로 사용하지 않는다. shape/finite 검사와 `atol=1e-8,rtol=1e-7` 비교를 한다. 동일 원본 selectedAssignments, objective raw bits, 명시적 relocation/materialization도 기록한다. 이 검사는 committed program이 하나일 때의 초기 선택을 대상으로 하며, 동적 재컴파일로 덮어쓴 모든 generation의 계획 동등성을 증명하지 않는다. 첫 r0는 smoke로 분리하고 이후 6쌍의 교대 순서 실행만 성능 통계에 쓴다. encoding descriptor와 auxiliary assignment가 바뀌므로 normalized fingerprint/certificate 문자열 전체는 달라질 수 있다. 이 차이만으로 실행 배치가 바뀌었다고 판정하지 않는다.

## 결과

각 조건 **6쌍**, 총 **48회** 실제 full-training 실행의 결과다. r0 smoke는 통계에서 제외했다. 아래 변화율은 `(후보 중앙값 / 기준 중앙값 − 1) × 100`이며, 양수는 느려짐을 뜻한다.

| 조건 | 학습 A(s) | 학습 B(s) | 변화 | 컴파일 A→B(s) | 변화 | probe A→B(s) | 변화 |
|---|---:|---:|---:|---:|---:|---:|---:|
| lm_w1 | 1.095 | 1.225 | +11.9% | 2.469→2.329 | -5.7% | 4.251→4.265 | +0.3% |
| lm_w3 | 1.288 | 1.224 | -5.0% | 2.243→2.008 | -10.5% | 4.008→3.751 | -6.4% |
| kmeans_w1 | 6.793 | 7.322 | +7.8% | 11.738→12.783 | +8.9% | 19.166→20.688 | +7.9% |
| kmeans_w3 | 5.924 | 6.416 | +8.3% | 8.388→8.525 | +1.6% | 14.806→15.109 | +2.0% |

학습은 초기 컴파일을 제외한 `executionNanos`다. probe는 `probeWallNanos`이며 JVM/worker/container 기동과 receipt JSON 저장은 제외한다. 원시 container wall도 별도로 보관했다.

**24쌍 모두 출력의 최대 절대차 0, 초기 선택 배치·relocation·materialization·canonical objective bits 동일. 실제 opcode별 실행 횟수도 동일하고 fallback/repair 및 audit mismatch는 모두 0이다.**

| 조건 | optimizer A→B(s) | peak retained slots A→B | slots 변화 |
|---|---:|---:|---:|
| lm_w1 | 0.240→0.224 | 177,180→103,468 | -41.6% |
| lm_w3 | 0.116→0.095 | 10,156→4,188 | -58.8% |
| kmeans_w1 | 2.387→2.843 | 9,426,220→7,123,980 | -24.4% |
| kmeans_w3 | 0.814→0.780 | 1,087,314→864,040 | -20.5% |

retained slots는 Local DP trace의 최대 보유 slot 수이며 JVM RSS나 byte 수가 아니다. 모든 표본(r0 포함)의 peak로 표기했으며 시도별 값은 JSON에 있다. optimizer 시간은 초기 `candidateE2E.optimizerNanos` 중앙값이다. canonical model의 cost-factor 개수와 encoded solver-factor/auxiliary 개수는 구별해야 한다. 전체 ML graph의 max separator/induced width는 이 runtime probe에서 별도 측정하지 않았으므로 위 감소로 width 비악화를 증명하지 않는다.

| 조건 | 학습 A 범위(s) | 학습 B 범위(s) | paired 학습 변화 범위 | paired 변화 중앙값 |
|---|---:|---:|---:|---:|
| lm_w1 | 0.960–1.498 | 1.023–1.363 | -9.0%–+18.8% | +4.7% |
| lm_w3 | 1.106–1.442 | 1.101–1.524 | -13.2%–+12.1% | +1.5% |
| kmeans_w1 | 5.443–7.437 | 6.097–7.529 | -2.8%–+18.6% | +7.2% |
| kmeans_w3 | 5.229–6.985 | 5.489–6.713 | -8.7%–+12.0% | +6.1% |

범위는 6개 관측치의 min–max이며 신뢰구간이 아니다. 물리 호스트가 전용이 아니고 fresh JVM 시작, JIT/GC, trace/audit overhead가 포함된다. 이 규모·실행 환경의 관측 결과로 제한하며, 다른 크기나 여러 물리 호스트의 속도 개선/저하를 단정하지 않는다.

### 채택 판단

**semantic relation 통합은 가능하지만, 이번 OR-tail contraction을 성능 개선으로 채택할 근거는 없다.** 네 조건에서 의도적인 runtime 연산/이동 감소는 없었고, retained slots 감소가 일관된 학습시간 개선으로 이어지지 않았다. 기존 small-scope OR-chain과 terminal price를 유지하는 semantic fusion을 권고한다. 후보의 source patch와 실행 클래스는 archive에 보존하며 main production 소스에는 적용하지 않는다.

대형 모델(LogReg/ALS)은 사용자 요청에 따라 최종 비교에서 제외했다. 제외 전 smoke에서 LogReg W1 timeout, LogReg W3 baseline 및 ALS W1 factor overflow를 관측했고 진행 중이던 LogReg W3 후보는 사용자 요청 시점에 중단했다. 이 실패/중단까지 걸린 시간은 완료된 학습시간으로 사용하지 않았다.

## 재현 및 raw evidence

선별 게시 후의 frozen run 보관 위치:

`/grid/3/cofee-lm-sweep-mchoi-20260914/reuse-study-archive-20261006/evidence/reuse-training/docker/20261006T190003Z-956f6dc3`

`manifest.json`, `summary.json`, `trials/<case>-r<repeat>-<arm>/{command.json,receipt.json,result.json,coordinator.log,worker-*.log,output.csv}` 참조. 앞선 `/grid` mount 실패와 동결 중 test-compile 변경 감지 실패는 setup failures이며 학습시간 표에 포함하지 않는다.

후보 검증은 focused 9 classes **57/57 PASS**. canonical raw bits와 원본 assignment 전수 검사를 유지했다. 기존 main의 dyadic test `IDENTITY` 기대 대 `ONE_MONETARY_TABLE` 실제 불일치는 수정하지 않았고, 별도 실행 5건 중 1건 실패로 남긴다.

실험 당시 측정 명령(기록): 아래 전용 runner/probe와 후보 구현은 main 게시 대상이 아니다. 재현하려면 archive의 source를 baseline commit의 새 checkout에 복구해야 한다.

```bash
cd /home/mchoi/reuse-semantic-fusion-20261006
bash scripts/fedplanner/run_LAN_docker.sh --reuse-training-compare \
  --cases lm_w1 lm_w3 kmeans_w1 kmeans_w3 \
  --start-repetition 1 --repetitions 6 --timeout-seconds 90
```

당시에는 두 worktree의 준비된 `target/classes`와 공통 test probe를 사용했다. 해당 worktree들은 push 확인 후 삭제한다. 실험 runner와 경로를 복구한 뒤 고정된 기존 결과만 다시 집계하려면 `--run-dir .omx/reuse-training/docker/20261006T190003Z-956f6dc3 --summarize-only`를 사용한다. `measurement-invocation.json`에 실제 실행 명령과 각 조건의 A-first/B-first 3회씩 순서가 있다. `scope-update.json`에는 사용자 범위 변경, `post-run-integrity.json`에는 종료 후 input/class/dependency/builtin 전체 inventory 일치 결과를 남겼다.

축약된 최종 근거: `docs/evidence/reuse-training-20261006.json`. 후보 source patch/57개 focused test XML/기존 dyadic 실패 XML/probe source는 frozen run의 `validation/`에 보관했다. Python syntax, Bash syntax, `git diff --check`를 통과했고 이 실험의 container는 모두 정리됐다.

## 선별 게시 및 보관

조사 보고서·실행시간 보고서·요약 JSON 2개와 `OwnedRefedReuseTest`의 retirement/eviction 재생성 회귀 테스트 2개만 main에 반영한다. OR-tail production 후보, 실험용 구조 테스트/probe/harness/Docker dispatch는 반영하지 않는다. 본문의 측정·코드 참조는 실험 baseline `8f6bb285e5` 기준이다.

원시 증거와 실험 source patch는 `/grid/3/cofee-lm-sweep-mchoi-20260914/reuse-study-archive-20261006`에 해시 검증 후 보관했다. `evidence/`는 원시 run, `source/primary`와 `source/candidate`는 원본 패치·추가 파일, `archive-manifest.json`은 archive 파일 inventory다. 당시 worktree와 이를 대신해 만든 게시용 임시 worktree는 push 확인 후 삭제한다. 원본 manifest 안의 경로는 실험 당시 provenance로 보존했으며 JSON 최상위의 현재 증거 경로는 archive로 갱신했다. 실험 전용 runner는 main에서 직접 호출할 수 없다. 복구 절차와 Docker 경로 제약은 archive의 `README.md`를 참조한다.

게시 전 검증: 최신 `origin/main` `0146f043e0` 위에서 `mvn -q -DskipTests=false -Djacoco.skip=true -Dtest-forkCount=1 -Dtest-perCoreThreadCount=false -Dtest=OwnedRefedReuseTest test`를 실행했고 **21 tests, 0 failures/errors/skips**를 확인했다. 실험 당시 baseline 검증과 구분하며 main production 코드 변경은 없다.
