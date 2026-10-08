# FED Oracle inventory와 압축 경계

2026-10-08 코드 조사 기준. `RulesCore` 등록 순서와 `Rulesets`의 실제 `caps/profile` 구현을 조사했다. 아래 의존성은 rule 의미의 요약이며, production `DecisionDependencies`는 shape proof와 note를 보존하기 위해 일부 축을 보수적으로 더 선언한다. 선언만으로 relation-native Closure 구현이 완료되는 것은 아니다.

등록된 49개 family에는 항상 CP를 반환하는 rule, alias/함수 경계 및 placeholder도 포함된다. 일반 matrix multiplication은 `OracleFacade`에서 `mmult`로 정규화되어 **BinaryMMRule**로 간다. MMFedRule 테스트만으로 실제 matrix 경로를 검증할 수 없다.

2026-10-09 추가: 위 dependency 계약은 이제 relation 준비뿐 아니라 **일반 FED-required 후보의 조기 feasibility/MRV**에도 사용한다. 기존 execution relation 경로를 우선하며, relation이 없으면 authoritative `DecisionDependencies`가 선언한 입력·shape-selector 축만 전방 rule로 평가한다. PUBLIC/CP 공간과 선언 없는 UDF는 유지한다. UNKNOWN shape 또는 RULE_ERROR는 확정 거절하지 않고 exact 증거 경로에 남긴다. Prefix 평가4096회 상한도 UNKNOWN으로 fallback한다. 일반 unary/binary/실제 BinaryMM/aggregate의 exhaustive·fixed-seed parity, shape authority 및 고차원 fallback26 tests와 전체687 tests를 통과했다. 이는 일반 FED의 exact fact/support 생성까지 모두 relation-native로 바꿨다는 의미는 아니다. 실제 COFEE 수치는 [대규모 검증 보고서](FEDPLANNER_COFEE_LARGE_VALIDATION_2026-10-08_KO.md)에 기록한다.

| Family | Caps 입력 의존성 | Profile 입력 의존성 | 추가 의미/압축 경계 | 이번 구현/검증 상태 |
|---|---|---|---|---|
| UnaryElemwise | 0 | 0 | 일반 elementwise | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| UnaryCumulative | 0 | 0 | 누적 연산 | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| ReorgUnary | 0 | 0 | roll/index 및 range 유지 | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| Reshape | 0 | 없음 | byrow attribute | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| Reblock | 0 | 0 | 배치 유지 | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| Cast | 0 | 0 | 자료형 변환 | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| UnaryCastToFrame | 0 | 0 | frame 변환 | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| Contains | 0 | 없음 | scalar LOUT | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| Replace | 0 | 0 | profile vector hint | shape 선언 보정·회귀 검증; fact/support는 exact fallback |
| Rmempty | 0 | 0 | 출력 range 재계산 | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| Rexpand | 0 | 0 | FULL partition proof | shape 선언 보정·회귀 검증; fact/support는 exact fallback |
| RightIndex | 추가 matrix/frame 축 모두 | 0 | 추가 FED 입력 거부, slice | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| LeftIndex | 0,1 | 0,1 | FULL proof | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| FrameMap | signature의 frame 위치 | 동일 | margin attribute | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| VariableWrite | 0 | 없음 | 쓰기 side effect | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| TransformEncode | 0 | 0 | 다중 출력 | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| WSLOSS | 0 | 없음 | ROW/COL, scalar LOUT | 생성·Closure relation-native, 명시적 전체 Closure 및 Local/Exact parity |
| WCEMM | 0 | 없음 | ROW/COL/FULL/PART, scalar LOUT | 생성·Closure relation-native, 명시적 전체 Closure 및 Local/Exact parity |
| WSIGMOID | 0 | 0 | FOUT source identity | 기존 Oracle 축 축약 재사용; FOUT exact source/action 유지 |
| WUMM | 0 + ROW의 U1 / COL의 V2 | 0 | 보조 입력이 decision note에도 영향 | 조건부 보조 축/note 의존성: 기존 exact 경로 유지 |
| WDIVMM | 0 | 0 | base type attribute | 기존 Oracle 축 축약 재사용; FOUT exact source/action 유지 |
| AggUnary | 0 | 0 | direction/agg attribute | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| AggTernary | 0,1,2 | FULL 고정 | 공유 axis/출력 scalar shape | shape 선언 보정·회귀 검증; fact/support는 exact fallback |
| CentralMoment | 0, 선택적 1 | 없음 | FED weight 거부 | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| Covariance | 0,1, 선택적 2 | 없음 | weight 관련 note | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| MMFed | 0,1 | 0 | 별도 rule; 실제 mmult와 구별 | 기존 조건부 Oracle 축 축약; 실제 mmult 경로와 별개 |
| BinaryMM | 일반 0,1 / TSMM X | 0,1 | 실제 mmult dispatch, FULL proof | shape 의존성 API·exhaustive parity; 독립 축 없으면 streaming exact |
| MMChain | X0, weighted W2 | 없음 | V1 독립; FULL proof | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| Tsmm | 0 | 없음 | LEFT/RIGHT 출력, FULL proof | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| BinaryElemwise | 0,1 | 0,1 | vector/FULL/pool | shape 의존성 API·exhaustive parity; 독립 축 없으면 streaming exact |
| TernaryElemwise | 모두 | 모두 | leader/outer shape | shape 선언 보정·회귀 검증; fact/support는 exact fallback |
| NaryElemwise | 모두 | 모두 | 동일 axis/FULL/local matrix proof | 보수적 전체 축 선언; 새 선언에 변하는 독립 축이 없으면 region 생성 전 streaming fallback |
| Append | 0,1 | 0,1 | dimension/FULL, nary 경로 | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| CumulativeOffset | 0 | 0 | offset caps 독립, FULL proof | shape 선언 보정·회귀 검증; fact/support는 exact fallback |
| Ctable | 0,1, 선택적 2 | 0,1 | disjoint attribute | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| QuantileSort | 0 | 0 | 출력 열 수 hint/note | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| QuantilePick | 0, 선택적 1 | 없음 | parameter local/FULL proof | Oracle 의존성 선언·기존 rule 회귀; source authority의 exact fact 유지 |
| Quantile/Interquantile deny | 없음 | 없음 | 항상 CP | 상수 CP Oracle 의존성 선언; scalar CP family 이외 fact는 exact |
| Solve | 없음 | 없음 | 항상 CP | 상수 CP Oracle 의존성 선언; scalar CP family 이외 fact는 exact |
| SpoofCellwise | 모두 | 0 | template/leader/mixed axes | 보수적 전체 축 선언; 새 선언에 변하는 독립 축이 없으면 region 생성 전 streaming fallback |
| SpoofRowwise | 모두 | 0 | ROW leader/mixed | 보수적 전체 축 선언; 새 선언에 변하는 독립 축이 없으면 region 생성 전 streaming fallback |
| SpoofMultiAggregate | 모두 | 없음 | ROW/COL leader/mixed | 보수적 전체 축 선언; 새 선언에 변하는 독립 축이 없으면 region 생성 전 streaming fallback |
| SpoofOuterProduct | 모두 | 0 | template/leader/mixed | 보수적 전체 축 선언; 새 선언에 변하는 독립 축이 없으면 region 생성 전 streaming fallback |
| TransientWrite | 0 | 0 | alias/CFG | Oracle 의존성 선언; 함수/CFG/source authority는 기존 exact 경로 |
| PlacementAlias | 0 | 0 | compiler identity | Oracle 의존성 선언; 함수/CFG/source authority는 기존 exact 경로 |
| FunctionOutput | 주로 0 | 0 | function boundary/transform output | Oracle 의존성 선언; 함수/CFG/source authority는 기존 exact 경로 |
| TransientRead | attribute 또는 0 | 0 | CFG source identity | Oracle 의존성 선언; 함수/CFG/source authority는 기존 exact 경로 |
| BuiltinMKMeans | 모두 | 0 | 첫 FED 입력, placeholder | Oracle 의존성 선언; 함수/CFG/source authority는 기존 exact 경로 |
| FunctionCall | 모두 | 0 | placeholder/multi-return | Oracle 의존성 선언; 함수/CFG/source authority는 기존 exact 경로 |

전체 family에서 새 생성·Closure 경로의 exhaustive parity를 완료했다는 의미는 아니다. 변경하지 않은 exact fallback은 기존 회귀로 검증했고, relation-native 전체 경로 검증은 WSLOSS/WCEMM 및 공통 소비자 fixture에 한정한다.

## 공통 조건

- `ABSENT_LOCAL` matrix 입력은 profile 단계에서 모든 matrix FType 후보로 확장되는 경우가 있다. Caps의 입력 독립성은 profile 또는 source/action binding의 독립성을 뜻하지 않는다.
- Shape proof는 원본 runtime FType의 FULL 선택에 의존할 수 있다. Rule에 전달되는 mapped FType만으로 캐시하면 proof가 섞인다. raw shape selector와 fresh exact ShapeHint가 필요하다.
- FOUT source reference는 exact CandidateRuleKey를 포함한다. 출력 형상만 같다고 다른 exact emission authority를 하나의 대표로 대체할 수 없다.
- Scalar weighted LOUT도 입력 DIRECT/RELOCATION binding을 가진다. 후보 tuple를 생략하면서 LOCAL empty support를 붙이는 최적화는 허용되지 않는다.
- VALUE_MAP, 함수 경계, shared/nonseparable cost와 sparse support는 기존 exact legality 및 cost 경로를 유지해야 한다. 입력 축의 직사각형 독립성을 별도로 증명한 경우에만 product를 압축한다.

## 현재 작업의 검증 구분

Oracle 의존성 선언 및 Oracle evidence 캐시, 생성 단계 exact fact 감소, Closure 임시 객체 감소, Physical Alternative 감소, 실제 workload 시간/메모리는 각각 별도로 측정한다. 상세 구현/검증 상태와 남은 전개 지점은 최종 결과 문서에 기록한다.
