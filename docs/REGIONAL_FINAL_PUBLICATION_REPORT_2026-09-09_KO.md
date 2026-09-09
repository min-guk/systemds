# Regional + LB + 남은 결합 exact / Global 최종 게시 검증

작성일: 2026-09-09. 이 문서는 최종 소스 정리와 main 병합 뒤 수행한 검증을 기록한다. 사용자가 게시 대상을 GitHub `min-guk/systemds`의 기존 `main`으로 확정했다. 로컬 `github` remote를 사용하며, push 완료 여부와 원격 commit 확인은 별도 게시 기록으로 남긴다.

## 최종 포함 범위

- Global exact와 Regional seed → global LB 1회 → 5% 인증 판정 → 필요하면 남은 replica equality의 exact 완료.
- 공통 immutable cost table, support/quotient 준비 결과, Regional/LB의 root 재사용 및 phase timing.
- plain Regional 진단용 mode=off. 명시적 mode=remaining-exact와 이전 실험 명령 호환용 mode=anytime + algorithm=remaining-exact.
- 기존 main의 placement, rule propagation, AggLocal 및 metadata privacy 수정은 그대로 병합했다.

AdaptiveThresholdOptimizer, BranchingRegionalOptimizer, TargetAnytimeOptimizer, IncrementalAnytimeOptimizer, NestedMiniBucketRelaxation 및 전용 테스트는 최종 트리에서 제거했다. CertifiedRegionalOptimizer는 설정·factor evaluator만, RegionalSearchOptimizer는 최종 경로의 인증·예산·trace 관리만 남겼다. 옛 알고리즘 선택자는 오류로 처리한다.

MiniBucketLowerBound, IncrementalReplicaBound, ReplicaComponentPreparation은 최종 초기 LB와 exact 완료에 필요하여 유지했다. 이름에 Incremental이 들어가는 bound 자료구조를 유지한 것이 이전 Anytime scheduler를 유지한다는 뜻은 아니다. 과거 보고서는 해당 당시 결과의 기록으로 남긴다.

## 커밋 보존

- 전체 이전 실험과 최신 미커밋 구현 보관: `6b2f32143d8858ed7eed1713852c5ff9f21674ff`.
- 기존 GitHub main `12b41d79f8` 병합: `1caf7a1a30`. 소스 충돌 없음. 충돌한 세션 문서 2개는 양쪽 기록을 모두 유지.
- Certified `6a2cf13a2253f0f1de0624a3de928cb62302fa3a`, A/B/C `b463a46a0ae3018124e069c4d57dca99605be4de`, Target Anytime `370d6a8ba3eb99425c3b5c1c83ffb113ee554e07`, incremental Anytime `acd3f78970e82aac91e65236c18389e9a8e563cb`도 archive의 ancestor로 확인했다.

이력을 squash하거나 삭제하지 않았다. 최종 소스 사용법과 복구 명령은 [REGIONAL_REMAINING_EXACT.md](REGIONAL_REMAINING_EXACT.md)에 있다.

## 새 테스트와 빌드

so002 로컬 JVM/Maven에서 병합된 소스를 clean package: **145개 / 17클래스, 실패·오류·skip 모두 0**. 전체 main/test Java 컴파일과 패키징 완료. 테스트 중 source hash 변경 없음.

검증에는 독립 exhaustive optimum에 대한 checkpoint containment/단조성, 5% 전후 분기, 0 비용·0 예산, resource 중단, 남은 결합 완료·취소, auxiliary/canonical parity, 공통 준비 재사용, 제거된 selector 거부, P2 placement 및 metadata privacy 회귀를 포함했다. 기존 정책에만 해당하는 테스트를 제거하고 유효한 공통 검증은 최종 경로로 옮겼다.

`git diff --check` 통과. Checkstyle/Spotless/license/RAT는 기존 빌드와 동일하게 skip했다. 전체 Java 테스트 스위트나 전체 workload campaign을 통과했다고 주장하지 않는다.

- JAR SHA-256: `76d77443c602abfd7251747a036b0818e7c56e58722429fa8fca3af99c563d14`
- Source manifest SHA-256: `94b9a763de597e45d75ac1b7fbd0106324da6a5598e4ab5cd42e95da4d57ed85`
- [build log](/home/mchoi/so007-regional-publication-evidence-20260909/validation/build.log), [test summary](/home/mchoi/so007-regional-publication-evidence-20260909/validation/tests.json), [source manifest](/home/mchoi/so007-regional-publication-evidence-20260909/validation/source-manifest.json).

## so007 native planning smoke

X PRIVATE_AGGREGATE / Y PUBLIC, LAN modeled 625 MB/s 및 1ms, worker 모델 1개, compact=false 양쪽, 동일 새 JAR. L2SVM·GLM × RegionalShared5·Global × 각 1회 = **4 JVM 모두 정상 종료**. planning-only config, 0 execution time, 출력 없음, input/runtime hash 보존 확인. Docker와 workload/worker 실행은 하지 않았다.

| Workload | 초기 L | 초기 Regional U | 초기 인증 gap | 최종 처리 | Global C* |
| --- | ---: | ---: | ---: | --- | ---: |
| L2SVM | 480.6543391449701 | 846.0188882981099 | 76.0139916355% | 남은 exact 1회, Global canonical bits 일치 | 846.0188882981099 |
| GLM | 8819.915802790667 | 8969.380143933382 | 1.6946232196% | 첫 인증으로 반환, exact 0회 | 8943.130143933382 |

모든 checkpoint의 구간이 같은 workload의 독립 Global objective를 포함했다. 두 Regional 실행 모두 root 재사용=1, root 요청=2. 새 JAR에 제거한 5개 컨트롤러 class가 없고 RemainingExact/SharedPreparation/Global class가 있음을 검사했다. 이번 1회 smoke는 정확성과 연결 검증이며 성능 순위·통계적 speedup의 근거가 아니다.

원본 수집기는 제거한 옛 정책의 `regionCalls` 등을 여전히 요구해 Regional 2행을 수집 실패로 표시했다. JVM은 정상 종료했고 원본 로그·receipt·rows는 수정하지 않았다. 새 컨트롤러의 필수 counter 목록으로 수집기를 맞춰 같은 기록을 다시 검증하여 4/4 통과했다. 이 과정에서 receipt hash, model identity, canonical bits, gap 산술, target 및 monotonicity 검사는 유지했다. 추가 JVM 실험은 실행하지 않았다.

- [검증 결과](/home/mchoi/so007-regional-publication-evidence-20260909/validation/smoke-audit.json)
- [원본 campaign](/home/mchoi/so007-regional-publication-evidence-20260909/native/runs/publication-smoke-l2-glm-v1/summary.json)
- [재검증 스크립트](/home/mchoi/so007-regional-publication-evidence-20260909/validation/audit_smoke.py)

## 잔여 한계

인증 범위는 encoded cost model이다. 충분한 자원이 없으면 반환 구간과 종료 사유를 확인해야 하며, exact solve는 hard wall-clock timeout을 지원하지 않는다. TIME/RESOURCE 종료라도 완료된 부분 작업으로 목표를 만족할 수 있어 targetReached는 최종 구간에서 별도로 계산한다. 이전 3회 pilot의 L2SVM 성능 회귀와 StepLM의 Global 대비 지연을 이번 게시 정리가 해소했다고 주장하지 않는다.
