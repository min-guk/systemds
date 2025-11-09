# OutputConstraintValidator 구현 요약

## 📅 세션 정보
- **날짜**: 2025-10-11
- **목적**: FederatedTypeHandler와 유사한 구조로 Federated Instruction의 Output Type constraint 검증 필터 구현

## 🎯 요구사항

### 핵심 요구사항
1. FederatedTypeHandler와 비슷한 패턴이지만 **별도 파일로 분리**
2. **OP Type별로 handler 클래스 구성**
3. **OpCode별로 FOUT 가능 여부와 제약사항 체크**
4. **OP Type 필터**: 해당되는 OP Type이 아니면 건너뛰기
5. **입력**: Hop
6. **출력**: FOUT 가능 여부(boolean) + 제약사항 메시지(String)
7. **Propagation 없음**: 검증만 수행
8. **Input 고려**: 필요시 input FType 확인

## 📁 생성된 파일 구조

```
src/main/java/org/apache/sysds/hops/fedplanner/fout/
├── OutputConstraintResult.java              # 결과 컨테이너
├── OutputConstraintValidator.java           # 기본 추상 클래스
├── OutputConstraintValidatorFactory.java    # Factory 패턴 (OP Type 필터)
├── package-info.java                        # 문서 및 사용 예시
└── validators/
    ├── AggregateUnaryValidator.java         # ✅ 스켈레톤
    ├── AggregateBinaryValidator.java        # ✅ 스켈레톤
    ├── MMMValidator.java                    # ✅ 스켈레톤
    ├── TsmmValidator.java                   # ✅ 스켈레톤
    ├── ParameterizedBuiltinValidator.java   # ✅ 스켈레톤
    ├── AggregateTernaryValidator.java       # ✅ 스켈레톤
    ├── MMChainValidator.java                # ✅ 스켈레톤
    ├── QuantilePickValidator.java           # ✅ 스켈레톤
    └── CtableValidator.java                 # ✅ 스켈레톤 (일부 구현)
```

**총 13개 파일 생성**

## 🏗️ 아키텍처 설계

### 1. OutputConstraintResult (결과 컨테이너)
```java
public class OutputConstraintResult {
    private final boolean foutAllowed;
    private final String constraintMessage;

    // Factory methods
    public static OutputConstraintResult allowed(String message);
    public static OutputConstraintResult disallowed(String reason);
    public static OutputConstraintResult conditional(String constraint);
}
```

### 2. OutputConstraintValidator (추상 클래스)
```java
public abstract class OutputConstraintValidator {
    // 핵심 메서드
    public abstract OutputConstraintResult validate(Hop hop, FType[] inputTypes);
    public abstract boolean canValidate(Hop hop); // OP Type 필터

    // Helper 메서드
    protected boolean isScalarOutput(Hop hop);
    protected boolean isPartitionedInput(FType[] inputTypes);
    protected boolean hasBroadcastInput(FType[] inputTypes);
    protected FType getInputPartitionType(FType[] inputTypes);
    protected boolean hasAnyFederatedInput(FType[] inputTypes);
    protected boolean hasFederatedFirstInput(FType[] inputTypes);
}
```

### 3. OutputConstraintValidatorFactory (OP Type 필터)
```java
public class OutputConstraintValidatorFactory {
    // 9개 Validator 등록
    private static final List<OutputConstraintValidator> validators;

    // OP Type 필터 메서드
    public OutputConstraintValidator getValidator(Hop hop) {
        // validator 찾으면 → 해당 validator 반환
        // 못 찾으면 → null 반환 (FOUT 기본 허용)
    }
}
```

## 🎨 설계 원칙

### OP Type 필터링 메커니즘
1. **Whitelist 방식**: 제약사항이 있는 OP Type만 등록
2. **기본 허용**: `getValidator()` → null이면 FOUT 허용
3. **선택적 검증**: `canValidate()`로 OP Type 필터링

### 비교: FederatedTypeHandler vs OutputConstraintValidator

| 항목 | FederatedTypeHandler | OutputConstraintValidator |
|------|---------------------|---------------------------|
| **목적** | FType 전파 및 결정 | FOUT 가능 여부 검증 |
| **입력** | `Hop`, `FType[]` | `Hop`, `FType[]` |
| **출력** | `HandlerResult(FType, reason)` | `OutputConstraintResult(boolean, message)` |
| **Propagation** | ✅ FType 전파 로직 포함 | ❌ 검증만 수행 |
| **Input 고려** | ✅ combineBinaryFTypes 등 | ✅ 조건부 검증에 필요 |
| **등록 방식** | Factory + 핸들러 리스트 | Factory + Validator 리스트 |
| **필터링** | 모든 Hop 처리 | **특정 OP Type만 검증** |
| **미등록 처리** | DefaultHandler | **null → FOUT 허용** |

## 📊 FOUT Constraint 표 (등록된 OP Types)

| Instruction Class | OP Type | OpCode | FOUT Possible? | FOUT Constraint/Reason |
|-------------------|---------|--------|----------------|------------------------|
| AggregateUnaryFEDInstruction | AggregateUnary | uack+, uark+, uarimax, uarimin, var | No (scalar) | Scalars cannot be federated |
| AggregateUnaryFEDInstruction | AggregateUnary | var (variance ops) | No | requires consolidation of partial results |
| AggregateBinaryFEDInstruction | AggregateBinary | ba+* (mmult) | Yes (conditional) | PART output triggers warning; MV defaults to LOUT for performance |
| MMMFEDInstruction | MAPMM | mapmm, pmmj, cpmm, rmm | Yes (conditional) | PART output requires special handling; MV defaults to LOUT for performance |
| TsmmFEDInstruction | Tsmm | tsmm | Yes (conditional) | Converts to BROADCAST type when FOUT forced |
| ParameterizedBuiltinFEDInstruction | ParameterizedBuiltin | contains | No | Boolean scalar result |
| AggregateTernaryFEDInstruction | AggregateTernary | tak*, tack+ | No (scalar) | Scalar output cannot be federated |
| CtableFEDInstruction | Ctable | ctable | Yes (conditional) | Requires isFedOutput() check – slices must have separable ranges |
| MMChainFEDInstruction | MMChain | mmchain | No | Always requires local aggregation |
| QuantilePickFEDInstruction | QPick | qpick | Yes (conditional) | Only COL/FULL partitioning supported; ROW forces LOUT |

## 💻 사용 예시

```java
OutputConstraintValidatorFactory factory = new OutputConstraintValidatorFactory();
OutputConstraintValidator validator = factory.getValidator(hop);

if (validator != null) {
    OutputConstraintResult result = validator.validate(hop, inputFTypes);

    if (!result.isFoutAllowed()) {
        // FOUT 불가 → LOUT 강제
        LOG.warn("FOUT blocked: " + result.getConstraintMessage());
        return FType.LOCAL;
    } else if (result.getConstraintMessage().startsWith("CONDITIONAL")) {
        // 조건부 허용 → 경고
        LOG.info("FOUT conditional: " + result.getConstraintMessage());
    }
}
// validator == null → FOUT 제약 없음 (기본 허용)
```

## 📝 Validator 구현 프롬프트 템플릿

### 표준 템플릿
```
다음 FOUT Constraint 표를 기반으로 {ValidatorName}을 구현해줘.

**FOUT Constraint 표:**
{여기에 해당 OP Type의 row(s)만 붙여넣기}

**구현 전 검증 작업 (REQUIRED):**
1. 해당 OP Type에 대해 표에 **누락된 OpCode가 있는지** 확인
   - Hop 클래스 정의 확인 (getOp() 반환 enum 확인)
   - FEDInstruction 구현 확인 (처리 가능한 OpCode 목록)
   - 누락된 OpCode가 있으면 표에 추가하고 제약사항 분석
2. 표에 명시된 제약사항이 **사실인지 검증**
   - 런타임 FEDInstruction 코드 확인
   - 스칼라 출력 주장 → isScalar() 확인
   - PART/MV 제약 주장 → 실제 구현 로직 확인
   - 잘못된 정보가 있으면 수정하고 근거 명시

**구현 요구사항:**
1. `canValidate(Hop hop)`: 해당 OP Type인지 확인하는 필터 구현
2. `validate(Hop hop, FType[] inputTypes)`: OpCode별 세부 제약사항 검증
   - "No" → OutputConstraintResult.disallowed(reason)
   - "Yes (conditional)" → OutputConstraintResult.conditional(constraint)
   - 조건 확인이 필요하면 inputTypes 활용
3. Helper 메서드 활용: isScalarOutput(), isPartitionedInput(), hasBroadcastInput() 등
4. **검증 결과를 코드 주석에 명시**:
   ```java
   /**
    * VALIDATION RESULT:
    * - Confirmed OpCodes: [list]
    * - Missing OpCodes found: [list with constraints]
    * - Corrected constraints: [original → corrected with reason]
    */
   ```

**참고 파일:**
- src/main/java/org/apache/sysds/hops/fedplanner/fout/OutputConstraintValidator.java (base class)
- src/main/java/org/apache/sysds/hops/fedplanner/ftype/handlers/OperationHandlers.java (유사 패턴)
- src/main/java/org/apache/sysds/hops/{OPType}.java (Hop 클래스 - OpCode enum 확인)
- src/main/java/org/apache/sysds/runtime/instructions/fed/{OPType}FEDInstruction.java (런타임 구현)

**기존 파일 위치:**
src/main/java/org/apache/sysds/hops/fedplanner/fout/validators/{ValidatorName}.java
```

### 각 Validator별 구체적 프롬프트

#### 1. AggregateUnaryValidator
```
다음 FOUT Constraint 표를 기반으로 AggregateUnaryValidator를 구현해줘.

**FOUT Constraint 표:**
Instruction Class                 | OP Type         | OpCode                              | FOUT Possible? | FOUT Constraint/Reason
AggregateUnaryFEDInstruction      | AggregateUnary  | uack+, uark+, uarimax, uarimin, var | No (scalar)    | "Scalars cannot be federated"
AggregateUnaryFEDInstruction      | AggregateUnary  | var (variance ops)                  | No             | "requires consolidation of partial results"

**구현 전 검증 작업 (REQUIRED):**
1. AggUnaryOp 클래스에서 지원하는 전체 OpCode 목록 확인
   - src/main/java/org/apache/sysds/hops/AggUnaryOp.java 읽기
   - getOp() 반환 타입 (AggOp enum) 전체 목록 확인
2. AggregateUnaryFEDInstruction에서 처리 가능한 OpCode 확인
   - src/main/java/org/apache/sysds/runtime/instructions/fed/AggregateUnaryFEDInstruction.java 읽기
   - 표에 없는데 FED 지원하는 OpCode 있는지 확인
3. 각 OpCode가 실제로 스칼라 출력인지 확인
   - uack+, uark+, uarimax, uarimin이 정말 스칼라인지 검증
   - var이 정말 consolidation 필요한지 런타임 코드로 확인
4. 검증 결과를 주석으로 명시

**구현 요구사항:**
1. `canValidate(Hop hop)`: AggUnaryOp 타입인지 확인
2. `validate(Hop hop, FType[] inputTypes)`:
   - isScalarOutput(hop) → disallowed("Scalars cannot be federated")
   - OpCode가 VAR → disallowed("requires consolidation of partial results")
   - 표에서 확인된 다른 제약사항 OpCode 처리
   - 나머지 → allowed()
3. AggUnaryOp의 getOp() 메서드로 OpCode 확인

**참고 파일:**
- src/main/java/org/apache/sysds/hops/AggUnaryOp.java (OpCode enum)
- src/main/java/org/apache/sysds/runtime/instructions/fed/AggregateUnaryFEDInstruction.java (런타임)
- src/main/java/org/apache/sysds/hops/fedplanner/fout/OutputConstraintValidator.java (base class)
- src/main/java/org/apache/sysds/hops/fedplanner/ftype/handlers/OperationHandlers.java (AggUnaryOpHandler 참고)

**기존 파일 위치:**
src/main/java/org/apache/sysds/hops/fedplanner/fout/validators/AggregateUnaryValidator.java
```

#### 2. AggregateBinaryValidator
```
다음 FOUT Constraint 표를 기반으로 AggregateBinaryValidator를 구현해줘.

**FOUT Constraint 표:**
Instruction Class                 | OP Type          | OpCode        | FOUT Possible?     | FOUT Constraint/Reason
AggregateBinaryFEDInstruction     | AggregateBinary  | ba+* (mmult)  | Yes (conditional)  | PART output triggers warning; MV defaults to LOUT for performance

**구현 전 검증 작업 (REQUIRED):**
1. AggBinaryOp가 mmult 외에 다른 OpCode를 지원하는지 확인
   - src/main/java/org/apache/sysds/hops/AggBinaryOp.java 읽기
2. AggregateBinaryFEDInstruction의 PART/MV 제약 검증
   - src/main/java/org/apache/sysds/runtime/instructions/fed/AggregateBinaryFEDInstruction.java 읽기
   - PART 출력이 정말 warning을 발생시키는지 확인
   - MV가 정말 LOUT으로 기본 설정되는지 확인
3. 검증 결과를 주석으로 명시

**구현 요구사항:**
1. `canValidate(Hop hop)`: AggBinaryOp 타입인지 확인
2. `validate(Hop hop, FType[] inputTypes)`:
   - Matrix-Vector 곱셈 패턴 감지 (한쪽 입력이 벡터) → conditional("MV defaults to LOUT for performance")
   - PART 출력 패턴 감지 (inputTypes에서 PART 확인) → conditional("PART output triggers warning")
   - 나머지 → allowed()
3. Helper: isMatrixVectorMult(AggBinaryOp) - 한쪽 입력의 dim이 1인지 확인

**참고 파일:**
- src/main/java/org/apache/sysds/hops/AggBinaryOp.java (OpCode)
- src/main/java/org/apache/sysds/runtime/instructions/fed/AggregateBinaryFEDInstruction.java (런타임)
- src/main/java/org/apache/sysds/hops/fedplanner/fout/OutputConstraintValidator.java (base class)
- src/main/java/org/apache/sysds/hops/fedplanner/ftype/handlers/OperationHandlers.java (AggBinaryOpHandler 참고)

**기존 파일 위치:**
src/main/java/org/apache/sysds/hops/fedplanner/fout/validators/AggregateBinaryValidator.java
```

#### 3. ParameterizedBuiltinValidator
```
다음 FOUT Constraint 표를 기반으로 ParameterizedBuiltinValidator를 구현해줘.

**FOUT Constraint 표:**
Instruction Class                      | OP Type              | OpCode    | FOUT Possible? | FOUT Constraint/Reason
ParameterizedBuiltinFEDInstruction     | ParameterizedBuiltin | contains  | No             | Boolean scalar result

**구현 전 검증 작업 (REQUIRED):**
1. ParameterizedBuiltinOp의 전체 OpCode 목록 확인
   - src/main/java/org/apache/sysds/hops/ParameterizedBuiltinOp.java 읽기
   - ParamBuiltinOp enum 전체 확인
2. ParameterizedBuiltinFEDInstruction에서 지원하는 OpCode 확인
   - src/main/java/org/apache/sysds/runtime/instructions/fed/ParameterizedBuiltinFEDInstruction.java 읽기
   - contains 외에 다른 제약사항이 있는 OpCode 확인
3. contains가 정말 boolean scalar를 반환하는지 검증
4. 검증 결과를 주석으로 명시

**구현 요구사항:**
1. `canValidate(Hop hop)`: ParameterizedBuiltinOp 타입인지 확인
2. `validate(Hop hop, FType[] inputTypes)`:
   - OpCode가 CONTAINS → disallowed("Boolean scalar result")
   - 검증 시 발견된 다른 제약사항 OpCode 처리
   - 나머지 → allowed()
3. ParameterizedBuiltinOp의 getOp() 메서드로 OpCode 확인

**참고 파일:**
- src/main/java/org/apache/sysds/hops/ParameterizedBuiltinOp.java (OpCode enum)
- src/main/java/org/apache/sysds/runtime/instructions/fed/ParameterizedBuiltinFEDInstruction.java (런타임)
- src/main/java/org/apache/sysds/hops/fedplanner/fout/OutputConstraintValidator.java (base class)
- src/main/java/org/apache/sysds/hops/fedplanner/ftype/handlers/OperationHandlers.java (ParameterizedBuiltinOpHandler 참고)

**기존 파일 위치:**
src/main/java/org/apache/sysds/hops/fedplanner/fout/validators/ParameterizedBuiltinValidator.java
```

#### 4. QuantilePickValidator
```
다음 FOUT Constraint 표를 기반으로 QuantilePickValidator를 구현해줘.

**FOUT Constraint 표:**
Instruction Class              | OP Type  | OpCode  | FOUT Possible?     | FOUT Constraint/Reason
QuantilePickFEDInstruction     | QPick    | qpick   | Yes (conditional)  | Only COL/FULL partitioning supported; ROW forces LOUT

**구현 전 검증 작업 (REQUIRED):**
1. QuantilePick 관련 Hop 클래스 탐색
   - src/main/java/org/apache/sysds/hops/ 디렉토리 검색
   - QPick, QuantilePick, Quantile 등 관련 키워드로 찾기
2. QuantilePickFEDInstruction 검증
   - src/main/java/org/apache/sysds/runtime/instructions/fed/QuantilePickFEDInstruction.java 읽기
   - ROW partition이 정말 지원 안되는지 확인
   - COL/FULL partition만 지원하는지 확인
3. 다른 quantile 관련 OpCode가 있는지 확인
4. 검증 결과를 주석으로 명시

**구현 요구사항:**
1. `canValidate(Hop hop)`: QuantilePickOp 타입인지 확인 (Hop 클래스 탐색 필요)
2. `validate(Hop hop, FType[] inputTypes)`:
   - inputTypes[0] == FType.ROW → disallowed("ROW partitioning not supported, forces LOUT")
   - inputTypes[0] == FType.COL || FType.FULL → allowed("COL/FULL partitioning supported")
   - 나머지 → conditional()
3. 먼저 Hop 클래스 중 QPick/QuantilePick 관련 클래스 탐색

**참고 파일:**
- src/main/java/org/apache/sysds/hops/ (Hop 클래스 탐색)
- src/main/java/org/apache/sysds/runtime/instructions/fed/QuantilePickFEDInstruction.java (런타임)
- src/main/java/org/apache/sysds/hops/fedplanner/fout/OutputConstraintValidator.java (base class)

**기존 파일 위치:**
src/main/java/org/apache/sysds/hops/fedplanner/fout/validators/QuantilePickValidator.java
```

#### 5. CtableValidator
```
다음 FOUT Constraint 표를 기반으로 CtableValidator를 구현해줘.

**FOUT Constraint 표:**
Instruction Class      | OP Type  | OpCode  | FOUT Possible?     | FOUT Constraint/Reason
CtableFEDInstruction   | Ctable   | ctable  | Yes (conditional)  | Requires isFedOutput() check – slices must have separable ranges

**구현 전 검증 작업 (REQUIRED):**
1. TernaryOp에서 ctable 외 다른 OpCode 확인
   - src/main/java/org/apache/sysds/hops/TernaryOp.java 읽기
   - OpOp3 enum에서 CTABLE 외 제약사항 필요한 OpCode 확인
2. CtableFEDInstruction의 isFedOutput() 체크 로직 확인
   - src/main/java/org/apache/sysds/runtime/instructions/fed/CtableFEDInstruction.java 읽기
   - separable ranges 제약사항이 정확한지 확인
3. 검증 결과를 주석으로 명시

**구현 요구사항:**
1. `canValidate(Hop hop)`: TernaryOp && OpCode == CTABLE인지 확인 (이미 구현됨)
2. `validate(Hop hop, FType[] inputTypes)`:
   - 항상 conditional("Requires isFedOutput() check - slices must have separable ranges")
   - 실제 검증은 런타임에서 수행됨을 명시
3. 현재 구현 확인 및 개선

**참고 파일:**
- src/main/java/org/apache/sysds/hops/TernaryOp.java (OpCode enum)
- src/main/java/org/apache/sysds/runtime/instructions/fed/CtableFEDInstruction.java (런타임)
- src/main/java/org/apache/sysds/hops/fedplanner/fout/OutputConstraintValidator.java (base class)

**기존 파일 위치:**
src/main/java/org/apache/sysds/hops/fedplanner/fout/validators/CtableValidator.java
```

#### 6. MMChainValidator
```
다음 FOUT Constraint 표를 기반으로 MMChainValidator를 구현해줘.

**FOUT Constraint 표:**
Instruction Class          | OP Type   | OpCode   | FOUT Possible? | FOUT Constraint/Reason
MMChainFEDInstruction      | MMChain   | mmchain  | No             | Always requires local aggregation

**구현 전 검증 작업 (REQUIRED):**
1. MMChain 관련 Hop 클래스 탐색
   - src/main/java/org/apache/sysds/hops/ 디렉토리 검색
   - MMChain, ChainType 등 관련 키워드로 찾기
2. MMChainFEDInstruction 검증
   - src/main/java/org/apache/sysds/runtime/instructions/fed/MMChainFEDInstruction.java 읽기
   - 정말 항상 local aggregation이 필요한지 확인
   - 조건부 FOUT 가능성이 있는지 확인
3. 다른 mmchain 변형 OpCode가 있는지 확인
4. 검증 결과를 주석으로 명시

**구현 요구사항:**
1. `canValidate(Hop hop)`: MMChain 타입인지 확인 (Hop 클래스 탐색 필요)
2. `validate(Hop hop, FType[] inputTypes)`:
   - 항상 disallowed("Always requires local aggregation")
3. 먼저 Hop 클래스 중 MMChain 관련 클래스 탐색

**참고 파일:**
- src/main/java/org/apache/sysds/hops/ (Hop 클래스 탐색)
- src/main/java/org/apache/sysds/runtime/instructions/fed/MMChainFEDInstruction.java (런타임)
- src/main/java/org/apache/sysds/hops/fedplanner/fout/OutputConstraintValidator.java (base class)

**기존 파일 위치:**
src/main/java/org/apache/sysds/hops/fedplanner/fout/validators/MMChainValidator.java
```

#### 7. TsmmValidator
```
다음 FOUT Constraint 표를 기반으로 TsmmValidator를 구현해줘.

**FOUT Constraint 표:**
Instruction Class      | OP Type  | OpCode | FOUT Possible?     | FOUT Constraint/Reason
TsmmFEDInstruction     | Tsmm     | tsmm   | Yes (conditional)  | Converts to BROADCAST type when FOUT forced

**구현 전 검증 작업 (REQUIRED):**
1. Tsmm 관련 Hop 클래스 탐색
   - src/main/java/org/apache/sysds/hops/ 디렉토리 검색
   - AggBinaryOp의 checkTransposeSelf() 메서드 확인
   - Tsmm, MMTSJ 등 관련 키워드로 찾기
2. TsmmFEDInstruction 검증
   - src/main/java/org/apache/sysds/runtime/instructions/fed/TsmmFEDInstruction.java 읽기
   - BROADCAST 변환 로직이 정확한지 확인
   - 다른 조건부 제약사항이 있는지 확인
3. 검증 결과를 주석으로 명시

**구현 요구사항:**
1. `canValidate(Hop hop)`: Tsmm/AggBinaryOp with tsmm 타입인지 확인 (Hop 클래스 탐색 필요)
2. `validate(Hop hop, FType[] inputTypes)`:
   - conditional("Converts to BROADCAST type when FOUT forced")
3. 먼저 Hop 클래스 중 Tsmm 관련 클래스/패턴 탐색

**참고 파일:**
- src/main/java/org/apache/sysds/hops/AggBinaryOp.java (checkTransposeSelf 메서드)
- src/main/java/org/apache/sysds/runtime/instructions/fed/TsmmFEDInstruction.java (런타임)
- src/main/java/org/apache/sysds/hops/fedplanner/fout/OutputConstraintValidator.java (base class)

**기존 파일 위치:**
src/main/java/org/apache/sysds/hops/fedplanner/fout/validators/TsmmValidator.java
```

#### 8. MMMValidator
```
다음 FOUT Constraint 표를 기반으로 MMMValidator를 구현해줘.

**FOUT Constraint 표:**
Instruction Class   | OP Type | OpCode                  | FOUT Possible?     | FOUT Constraint/Reason
MMMFEDInstruction   | MAPMM   | mapmm, pmmj, cpmm, rmm  | Yes (conditional)  | PART output requires special handling; MV defaults to LOUT for performance

**구현 전 검증 작업 (REQUIRED):**
1. MAPMM 관련 Hop 클래스 탐색
   - src/main/java/org/apache/sysds/hops/ 디렉토리 검색
   - AggBinaryOp 내 Map MMult 관련 메서드 확인
   - MMultMethod enum 확인
2. MMMFEDInstruction 검증
   - src/main/java/org/apache/sysds/runtime/instructions/fed/MMMFEDInstruction.java 읽기
   - PART output special handling 로직 확인
   - MV → LOUT 기본 설정 로직 확인
3. mapmm, pmmj, cpmm, rmm 각각의 특성 확인
4. 검증 결과를 주석으로 명시

**구현 요구사항:**
1. `canValidate(Hop hop)`: MAPMM 타입인지 확인 (Hop 클래스 탐색 필요)
2. `validate(Hop hop, FType[] inputTypes)`:
   - Matrix-Vector 패턴 → conditional("MV defaults to LOUT for performance")
   - PART 출력 → conditional("PART output requires special handling")
   - 나머지 → allowed()
3. 먼저 Hop 클래스 중 MAPMM 관련 클래스 탐색

**참고 파일:**
- src/main/java/org/apache/sysds/hops/AggBinaryOp.java (MMultMethod)
- src/main/java/org/apache/sysds/runtime/instructions/fed/MMMFEDInstruction.java (런타임)
- src/main/java/org/apache/sysds/hops/fedplanner/fout/OutputConstraintValidator.java (base class)

**기존 파일 위치:**
src/main/java/org/apache/sysds/hops/fedplanner/fout/validators/MMMValidator.java
```

#### 9. AggregateTernaryValidator
```
다음 FOUT Constraint 표를 기반으로 AggregateTernaryValidator를 구현해줘.

**FOUT Constraint 표:**
Instruction Class                | OP Type            | OpCode      | FOUT Possible? | FOUT Constraint/Reason
AggregateTernaryFEDInstruction   | AggregateTernary   | tak*, tack+ | No (scalar)    | Scalar output cannot be federated

**구현 전 검증 작업 (REQUIRED):**
1. AggregateTernary 관련 Hop 클래스 탐색
   - src/main/java/org/apache/sysds/hops/ 디렉토리 검색
   - TernaryOp 또는 별도 클래스인지 확인
   - Aggregate + Ternary 조합 패턴 확인
2. AggregateTernaryFEDInstruction 검증
   - src/main/java/org/apache/sysds/runtime/instructions/fed/AggregateTernaryFEDInstruction.java 읽기
   - tak*, tack+ OpCode 목록 확인
   - 정말 모두 스칼라 출력인지 확인
3. 다른 aggregate ternary OpCode가 있는지 확인
4. 검증 결과를 주석으로 명시

**구현 요구사항:**
1. `canValidate(Hop hop)`: AggregateTernary 타입인지 확인 (Hop 클래스 탐색 필요)
2. `validate(Hop hop, FType[] inputTypes)`:
   - isScalarOutput(hop) → disallowed("Scalar output cannot be federated")
   - OpCode가 tak* 또는 tack+ 패턴 → disallowed()
   - 나머지 → allowed()
3. 먼저 Hop 클래스 중 AggregateTernary 관련 클래스 탐색

**참고 파일:**
- src/main/java/org/apache/sysds/hops/ (Hop 클래스 탐색)
- src/main/java/org/apache/sysds/runtime/instructions/fed/AggregateTernaryFEDInstruction.java (런타임)
- src/main/java/org/apache/sysds/hops/fedplanner/fout/OutputConstraintValidator.java (base class)

**기존 파일 위치:**
src/main/java/org/apache/sysds/hops/fedplanner/fout/validators/AggregateTernaryValidator.java
```

## ✅ 완료된 작업

1. ✅ 디렉토리 구조 설계 및 생성
2. ✅ OutputConstraintResult 구현
3. ✅ OutputConstraintValidator 기본 클래스 구현 (Helper 메서드 포함)
4. ✅ OutputConstraintValidatorFactory 구현 (OP Type 필터)
5. ✅ 9개 Validator 스켈레톤 생성
6. ✅ package-info.java 문서화
7. ✅ OP Type 필터링 메커니즘 추가 및 문서화
8. ✅ 각 Validator별 구현 프롬프트 작성

## 📋 남은 작업 (TODO)

### 높은 우선순위
1. ⬜ 각 Validator의 `canValidate()` 구현
   - Hop 클래스 탐색 (MMChain, Tsmm, MAPMM, AggregateTernary, QuantilePick 등)
   - 정확한 타입 체크 로직 추가

2. ⬜ 각 Validator의 `validate()` 세부 로직 구현
   - OpCode별 제약사항 체크
   - Input FType 기반 조건부 검증
   - Matrix-Vector 패턴 감지 로직

3. ⬜ 단위 테스트 작성
   - 각 Validator별 테스트 케이스
   - OP Type 필터링 테스트
   - 제약사항 표 기반 검증 테스트

### 중간 우선순위
4. ⬜ FederatedPlanner와 통합
   - OutputConstraintValidatorFactory 호출 지점 추가
   - FOUT 제약 위반 시 처리 로직 구현
   - 경고/에러 로깅

5. ⬜ 성능 최적화
   - Validator 인스턴스 캐싱 검토
   - 불필요한 체크 제거

### 낮은 우선순위
6. ⬜ 문서화 개선
   - JavaDoc 추가
   - 예제 코드 확장
   - 아키텍처 다이어그램

7. ⬜ 통합 테스트
   - End-to-end 시나리오 테스트
   - 실제 Federated 워크로드 검증

## 🔍 다음 단계 가이드

### 1단계: Hop 클래스 탐색
```bash
# Hop 클래스들 확인
find src/main/java/org/apache/sysds/hops -name "*.java" | grep -E "MMChain|Tsmm|MAPMM|AggregateTernary|QuantilePick"
```

### 2단계: Validator 구현 순서 (권장)
1. **ParameterizedBuiltinValidator** (가장 간단 - 이미 구현됨)
2. **CtableValidator** (이미 부분 구현됨)
3. **AggregateUnaryValidator** (AggUnaryOp 타입 확실)
4. **AggregateBinaryValidator** (AggBinaryOp 타입 확실)
5. **MMChainValidator**, **TsmmValidator**, **MMMValidator** (Hop 클래스 탐색 필요)
6. **AggregateTernaryValidator**, **QuantilePickValidator** (Hop 클래스 탐색 필요)

### 3단계: 통합 테스트
```java
// FederatedPlanner에서 사용 예시
OutputConstraintValidatorFactory factory = new OutputConstraintValidatorFactory();
for (Hop hop : hopDAG) {
    OutputConstraintValidator validator = factory.getValidator(hop);
    if (validator != null) {
        OutputConstraintResult result = validator.validate(hop, inputFTypes);
        if (!result.isFoutAllowed()) {
            LOG.warn("FOUT blocked: " + result.getConstraintMessage());
            // Force LOUT
        }
    }
}
```

## 📚 참고 파일

### 구현 참고
- `src/main/java/org/apache/sysds/hops/fedplanner/ftype/handlers/FederatedTypeHandler.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/ftype/handlers/OperationHandlers.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/ftype/handlers/FederatedTypeHandlerFactory.java`

### Hop 클래스
- `src/main/java/org/apache/sysds/hops/AggUnaryOp.java`
- `src/main/java/org/apache/sysds/hops/AggBinaryOp.java`
- `src/main/java/org/apache/sysds/hops/TernaryOp.java`
- `src/main/java/org/apache/sysds/hops/ParameterizedBuiltinOp.java`

## 🎓 핵심 개념 요약

### OP Type 필터링
- **목적**: 제약사항이 없는 OP Type은 자동으로 FOUT 허용
- **메커니즘**: `canValidate()` → false → `getValidator()` → null
- **장점**: 명시적 whitelist 방식으로 안전성 확보

### FederatedTypeHandler와의 차이
- **FederatedTypeHandler**: 모든 Hop 처리, FType 전파
- **OutputConstraintValidator**: 특정 OP Type만 검증, 제약사항 체크

### 설계 철학
> "Only OP Types with FOUT restrictions need validators.
> If no validator matches, FOUT is allowed by default."

---

**문서 작성일**: 2025-10-11
**작성자**: Claude (Sonnet 4.5)
**프로젝트**: Apache SystemDS - Federated Planning
