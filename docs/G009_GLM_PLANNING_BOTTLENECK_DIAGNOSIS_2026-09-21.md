# GLM planning 지연 원인: 단일 실행 JFR 진단

## 결론

DP-local GLM(P2P2D 50000×2100, 4-worker 배치)의 현재 지연은 공통 placement analysis 생성 중 확인된다. 93.30초 시점 main thread는 아직 `DMLProgram.bindPlacementAnalysisAtFinalHopBoundary` 아래 builder에 있고, CPU 누적은 90.850초다. 이 호출은 selector 생성/호출 이전이다(`DMLTranslator.java:389–418`). selector 탐색이 90초를 소비했다는 해석은 틀리다. **93.30초는 JVM main thread 경과 시간이며 builder 자체의 정확한 시작/종료 구간 시간은 아니다.**

가장 강한 CPU 근거는 candidate/support의 구조적 equals, canonical text 정렬 비교, proof 생성, 인덱스 처리다. 이전 설명의 "anchor 생성" 한 지점보다 넓고, 특히 깊은 동등성 비교가 중요하다. 후보 수가 많다는 사실만으로 필연적인 비용이라고 결론 내릴 수 없다.

## 실행과 증거

- JAR SHA-256: `8f10f1891f3cd99bd492b1ad5ac4913aff054fe74afd2d1f16370a0acc4833df` (소스/JAR 변경 없음).
- 명령/설정: `/home/mchoi/g009-glm-diagnosis-20260921/profile.sh`.
- DML: `/home/mchoi/ml-p1p2-sliceline-fedplanning-w4-once-20260921/tmp/ml10-remaining/gen_glm_P2P2D_4.dml`.
- config: 같은 workspace `final-selector-space-20260921/manual-docker/mkl-cost.xml` (compile-only).
- coordinator Xmx/Xms16g, ActiveProcessorCount8, cpuset `0-3,24-27`, memory cap24g. 이미지 기존 frozen SHA `2816d74b…`.
- JFR `settings=profile,duration=90s,maxsize=64m`; 파일 `glm-profile.jfr` 약4.1MiB.
- 공식 harness는 `workload is outside the frozen seven-workload set: glm`로 JVM 이전 거절. 별도 `G009_GLM_DIAGNOSTIC_HARNESS_EXCEPTION_2026-09-21.md`에 예외 근거를 먼저 기록했다. 직접 Docker의 **원인 진단**이며 공식 S6/속도 비교/정확성 인증으로 채택하지 않는다.
- worker 시작 명령을 처음 `WORKER`로 잘못 넣어 파싱 실패했다. 약20초 뒤 네 개 모두 `-w`로 수정해 시작했다. coordinator JVM은 재실행하지 않았다. 관측된 coordinator 오류/Connection refused는 없지만 시작부터 준비된 4-worker E2E 표본이 아니므로 완료 시간 비교에는 부적합하다. 이 한 실행의 builder CPU 스택 해석으로 범위를 제한한다.
- 이미지에 `jcmd`가 없어 Thread.print 시도는 실패했다. 동일 JVM PID1에 SIGQUIT를 보내 정상 JVM thread dump를 확보했다. 코드/selector 계약에는 변경 없음.
- 43.03초 main CPU42.032초, 93.30초 main CPU90.850초. 둘 다 `closeCfgTransientCandidateDependenciesMeasured:2649` → `bindDirectNativeCandidateRealizations:2888` 안에 있다. 후자는 support proof의 normalized signature 정렬 경로다.
- 약110초 관측 후 소유한 coordinator/worker/network만 정리. 무관한 컨테이너는 유지. 완료된 planning 시간/selector 시간은 없음.

## JFR 결과

main execution samples 5,589개, 약90초 녹화. **표본 수는 호출 횟수도 wall-time도 아니다.** 다음은 inclusive 범주여서 서로 겹치며 합산하지 않는다.

| 범주 | 표본 | main 표본 대비 |
|---|---:|---:|
| placement 타입의 구조적 `equals` | 2,353 | 42.1% |
| canonical text 생성/비교 경로 | 946 | 16.9% |
| `proveCandidateAlternatives` | 964 | 17.2% |
| `IdentityHashMap` 처리 | 736 | 13.2% |
| placement 타입 `hashCode` | 357 | 6.4% |

최상단 프레임은 `Objects.equals` 1,622개(29.0%), `CanonicalTextCursor.advanceText` 521개(9.3%), IdentityHashMap resize/put 613개(11.0%), StringLatin1.hashCode 221개(4.0%).

builder 호출이 직접 보이는 표본 3,388개. 그러나 **2,187개 스택이 수집 깊이에서 잘렸다.** 그래서 나머지를 selector 시간으로 분류하거나 "builder는 60.6%뿐"이라고 해석하면 안 된다. selector 프레임은 발견되지 않았고, 93.30초 전체 thread dump에서 최초 analysis binding이 끝나지 않았음이 확인된다.

호출 라인별: direct binder의 emission 생성/merge(`NeutralPlacementGraphBuilder:2955`) 968개, proof 요청(`:2888`) 959개. CFG closure의 첫 direct loop(`:2590`) 1,228개, physical rebuild 후 direct loop(`:2649`) 1,168개. **두 경로 모두 실제 실행 비용을 소비**한다. 이것은 pass가 몇 회인지 또는 후보가 몇 개 늘었는지는 알려주지 않는다.

전체 약109초 GC log에서 STW pause67건 합계2.720초. concurrent GC CPU는 포함하지 않으므로 GC 비용 전체라고 부르지 않는다. 메인 CPU 약97%라는 thread dump와 함께 볼 때, STW/네트워크 대기가 주범이라는 설명은 지지되지 않는다. sampled container memory는3.653→5.070→7.715→9.439GiB. JFR allocation weight는 byte[] 약21.5GB, Object[] 약15.1GB, ListItr 약12.7GB의 누적 할당 추정이다. 이는 동시 생존 메모리나 후보 저장량이 아니다.

## 코드와 연결한 해석 / 우선순위

1. `PlacementAnalysis.java:74–110,118–160`: canonical list를 생성할 때 ordering 구조 생성·정렬·인접 equals 중복 검사를 한다. 복합 support의 구조가 커질수록 깊은 비교가 비싸진다.
2. `PlacementAnalysis.java:907–949`: realization merge 후 다시 canonicalize한다. binder의 `:2955` 호출과 profile이 직접 연결된다.
3. `NeutralPlacementGraphBuilder.java:2554–2668`: CFG/direct 중첩 수렴과 physical rebuild 후 re-grounding을 수행한다. `:2690` binder는 dirty subset 경로에서도 전역 인덱스를 다시 만든다.
4. `NativePlacementContinuity.java:398–412`: support cache hit 뒤에도 public proof 객체를 인스턴스화하고 signature로 정렬한다. 캐시가 있다는 이유만으로 재처리 비용이 사라지지 않는다.

**개선 우선순위:** selector pruning을 추가하지 말고 immutable 구조의 동등성/정렬 키·canonical 결과 재사용과 변경 없는 emission 재사용을 먼저 검토한다. fingerprint 단독 동등 판정은 금지하며 충돌 시 정확 비교 및 기존 정렬/authority 계약을 보존해야 한다. 그 다음 dirty 변경분에 비례하는 closure/index 갱신을 검토한다. 이는 이번 실행으로 효과가 입증된 최적화가 아니라 프로파일에 근거한 후속 타깃이다.

## 미확정 / 검증 범위

- 실제 고유 후보/OR clause 수와 증가율, closure 정확한 pass 수 및 중복 재생성 수는 미측정. 따라서 "폭발이 전부 허수" 또는 "실제 조합 폭발이 없다"는 주장 불가.
- builder 구간의 정확한 최종 시간과 selector 소요 시간은 미확정. 이번 작업은 완료 E2E 측정이 아닌 중단 전 병목 확인이다.
- production 코드 변경 없음; 새 JAR/build/tests 불필요. `git diff --check` 확인. JFR/GC/thread dump와 소스 호출 순서를 교차 확인.
- 원시 파일 및 재분석 스크립트: `/home/mchoi/g009-glm-diagnosis-20260921/` (`profile-summary.json`, `detail-summary.json`, `coordinator.log`, `gc.log`, `stats.txt`).
