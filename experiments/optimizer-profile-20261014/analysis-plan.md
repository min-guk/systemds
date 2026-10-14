# Optimizer 병목 분석 계획

- 범위: source/scripts/compiled production artifacts 변경 없이 Docker JFR 및 외부 /proc 관측.
- baseline abb5a4822a LogReg 1회, current25662ee7b4 LogReg/GLM 각1회. 4CPU/8GiB/W3, coordinator heap3g, case timeout300s.
- 단계: constructor, initial propagation, normalization, tree/leaf solvers, search, original selected-cost audit, supply reconstruction. 기존 trace는 전체 optimize 정확한 wall time; 하위 단계는 JFR stack의 관측 구간/표본이며 계측 경계 시간과 구분한다.
- CPU: coordinator /proc user/system ticks; RSS/HWM; JFR execution samples/CPU load/heap/GC/allocation samples. Workers/container 수치와 혼동하지 않는다.
- 동일 결과: status, exact objective, rules/nodes/search counters, CP/FED output parity. Timeout은 정상 완료나 최적성 증거로 취급하지 않는다.
- 설계: invariant facts/context descriptors/primitive exact values 재사용 vs selection-sensitive legality/layout/supply 비용 분리. UNKNOWN/DEFER/privacy/worker binding/exact sum/optimality 보존.
- 산출물: raw evidence + 재현 커맨드 + source analysis + correctness review + 한국어 보고서. 성능 개선은 미구현이며 예상 효과에 증거와 한계를 명시.
