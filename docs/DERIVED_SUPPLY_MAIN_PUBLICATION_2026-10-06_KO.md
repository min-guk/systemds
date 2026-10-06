# Derived supply sharing: origin/main 게시 검증

## 기준과 범위

- 작업 workspace: `/home/mchoi/w1357-derived-supply-sharing-20261006`.
- 최초 기준: `eb64f9c939708735940f2ae095c5c8bd526decf7`.
- 구현 커밋: `6b9ee37485e6b42cf20513e7536d28f6e7ad2f91`.
- 통합한 origin/main: `0146f043e07ca445d9084257759aa78fe14ddf55`.
- 기존 workspace와 실행 중인 실험은 수정하지 않았다. 새 검증도 독립된 Docker run 디렉터리를 사용한다.

Native/supply 표현 분리와 source/version 기반 공유 구현, invariant FOUT staging 수정은
[구현 결과](DERIVED_SUPPLY_SHARING_RESULT_2026-10-06_KO.md)와
[FOUT staging 보고서](INVARIANT_FOUT_SHARING_2026-10-06_KO.md)에 설명되어 있다.
이 문서는 그 변경을 최신 main에 합친 뒤의 검증을 기록한다.

## 병합에서 보존한 의미

`ExactPhysicalCostModel` 충돌은 upstream의 branch guard를 가진 여러 alias origin과
auxiliary FED 통신 비용을 유지하면서 해결했다. 원본 source/version의 creation profile,
`crossExecutionReuse`, staged REFED upload의 비용 소유권을 함께 보존한다.
Explicit movement는 supply 측에서, FED 연산 내부의 auxiliary 통신은 operator 측에서
계산한다. Download의 guarded activation을 upload retention 선택으로 오해하지 않는다.

최신 main의 native 출력 geometry, 동적 realization identity, branch normalization,
재컴파일 instruction 보존, REV 출력 flag, DP resource guard 수정도 포함했다.
세션 기록의 충돌은 양쪽 독립 항목을 모두 유지했다.

추가로 `ExactPhysicalNativeSupplyRepresentation.nativeCandidate`에서 non-null witness만
보고 `DURABLE_MAP`으로 바꾸던 표현을 고쳤다. 동적 native witness는 endpoint residency만
증명할 수 있으므로, 선택된 realization의 layout kind와 lineage를 보존한다.
`rev(A) → exp(T)` 분기 fixture의 회귀 테스트는 witness가 있어도 `NATIVE_LINEAGE`와
`exactWorkerPool=false`가 유지되는지 확인한다. 후보, witness relation, legality/privacy,
TW/TR 또는 function boundary 제약을 변경하지 않는다.

## 통합 검증

검증 진행 중. 최종 Java/Docker 결과와 재현 경로를 게시 전에 기록한다.

## 남은 범위

공유 copy의 해제는 원본 value invalidation/removal에 따른다. Exact last-consumer 해제,
shared-memory budget 최적화 또는 외부 코드의 같은 remote-ID 덮어쓰기 감지는 추가하지 않았다.
이 검증은 관련 회귀와 격리 Docker 실행의 결과이며, 별도로 기록된 모든 workload의
large-factor/메모리/함수 authority 이슈가 해결됐다는 의미는 아니다.
