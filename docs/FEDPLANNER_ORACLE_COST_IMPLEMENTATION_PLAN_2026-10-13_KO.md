# Oracle 기반 생성·비용 투영 구현 계획

기준 설계: [상세 설계](FEDPLANNER_ORACLE_GUIDED_COST_PROJECTION_DESIGN_2026-10-13_KO.md).
독립 workspace: `/home/mchoi/fedplanner-oracle-cost-20261013`.
원본 workspace는 읽기만 했으며, 별도 Git 저장소의 `abb5a4822a`에 당시의 미커밋 Memo 변경을 포함한 기준을 보존했다. 원래 HEAD는 `4314a345c16c20752421ce6251bbc2f366c85825`다. 원본 build/experiments/OMX runtime state는 공유하지 않는다. 복사 hash는 `experiments/oracle-cost-projection-20261013/baseline-manifest.json`에 있다.

## 동작 보존과 순서

1. 독립 baseline 전체 main/test compile 및 기존 native/model/shape/cost/binding/guard/exhaustive/loop 회귀 실행.
2. 변경할 경로의 회귀를 먼저 추가: native capability parity/UNKNOWN/고차 입력, guarded cost 문맥/scaling/정확한 primitive 합, 공유 owner 문맥 교집합.
3. NativeRules의 layout 전체 product 목록을 제거하고 Oracle 지원 prefix에서 streaming 생성. Runtime dispatch, CP/FED→LOCAL/forced-local/multi-output, coverage 의무 보존.
4. MemoShapes/ProgramModel에서 비용 관련 binding 조건을 먼저 투영하고 실제 도달하는 관련 guard만 분기. Source-only 축, inactive 내부 분기 제거. NNZ/scalar/call/continuation/loop phase 의미 유지.
5. Owner-wide rule 교집합과 coverage propagation 검증. Primitive와 생성/방문 counter를 통해 full-product 작업이 다른 helper로 이동하지 않았는지 확인.
6. 변경된 전체 source를 새 output에 compile하고 targeted/인접 회귀, 독립 코드 검토, 같은 Docker 조건의 LogReg/GLM 실행·수치·선택비용 검증.

## 소유권과 제약

- Native lane: MemoNativeRules, OracleFacade 및 해당 회귀.
- Cost lane: MemoShapes, MemoProgramModel, MemoBindingResolver 및 해당 회귀.
- 통합 lane: MemoRuleBuilder, owner/cost 통합 회귀, 문서, 독립 build/Docker와 최종 검증.
- Runtime/검증 lane은 명령·기존 근거 조사 후 bounded 독립 검토.
- 공용 target에 병렬 compile하지 않는다. Lane 검증은 별도 class output을 사용한다.
- Source/행·열/NNZ/guard/완료 조건을 임의 대표로 바꾸지 않는다. `UNKNOWN != NO`, unknown cost를 0으로 바꾸지 않는다. Runtime fallback, 임의 opcode/source 상한, branch별 immutable universe 재생성 금지.
- 기존 긍정 availability 증명은 보존하되 임시 loop 가정을 global cache 권위로 삼지 않는다. 기존 exact resolver 경로는 합법성 검사용으로 유지하며 native 비용용 full input-product 우회로 사용하지 않는다.
- 새 의존성이나 전체 프로그램 proof/table 계층을 추가하지 않는다. 현재 API와 필요한 작은 조건부 view를 재사용한다.

## 완료 증거

작은 합법 공간·비용 합 parity와 negative UNKNOWN/상관/공유 owner 회귀, source-only scaling의 일정한 native 문맥 수, 불가능 prefix 비용 객체 미생성 counter, 깨끗한 통합 compile, Docker의 전체 planning/runtime/numeric 결과를 각각 기록한다. 과거 host model 진단을 새 Docker 성능 결과로 인용하지 않는다. 전체 GLM에서 남는 문제가 있으면 실제 상태·원인과 수정 범위를 기록하며 비용 생성 성공만으로 완료를 주장하지 않는다.
