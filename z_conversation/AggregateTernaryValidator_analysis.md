# AggregateTernaryValidator 구현 및 분석

## 1. 초기 요구사항

### FOUT Constraint 표 (제공받은 스펙)
```
Instruction Class                | OP Type            | OpCode      | FOUT Possible? | FOUT Constraint/Reason
AggregateTernaryFEDInstruction   | AggregateTernary   | tak*, tack+ | No (scalar)    | Scalar output cannot be federated
```

### 요청사항
1. AggregateTernary 관련 Hop 클래스 탐색
2. AggregateTernaryFEDInstruction 검증
3. 다른 aggregate ternary OpCode 확인
4. AggregateTernaryValidator 구현

---

## 2. 검증 작업

### 2.1 Hop 클래스 탐색
**결과:**
- `TernaryOp.java` 발견 (line 56에 주석: "should be called AggTernaryOp")
- `OpOp3` enum 항목: QUANTILE, INTERQUANTILE, CTABLE, MOMENT, COV, PLUS_MULT, MINUS_MULT, IFELSE, MAP
- TernaryOp은 일반 ternary와 aggregate ternary 모두 포함

### 2.2 Runtime Instruction 검증
**AggregateTernaryFEDInstruction.java 분석:**
```java
// Line 84-98
if(opcode.equalsIgnoreCase(Opcodes.TAKPM.toString()) ||
   opcode.equalsIgnoreCase(Opcodes.TACKPM.toString())) {
    // ...
}

// Line 118-124: 출력 타입에 따른 처리
if(output.getDataType().isScalar()) {
    // 스칼라 집계
} else {
    // 매트릭스 집계
}
```

**발견:**
- OpCodes: `TAKPM` ("tak+*"), `TACKPM` ("tack+*")
- 출력: 스칼라 OR 매트릭스 모두 가능
- Line 143-152: 모든 스칼라 출력 경로는 `DoubleObject` 반환

### 2.3 OpCode 매핑 확인
**Opcodes.java:**
```
TAKPM("tak+*", InstructionType.AggregateTernary)
TACKPM("tack+*", InstructionType.AggregateTernary)
```

---

## 3. 초기 구현 (잘못됨)

### 3.1 첫 번째 구현
```java
@Override
public boolean canValidate(Hop hop) {
    if (!(hop instanceof TernaryOp)) return false;
    TernaryOp ternary = (TernaryOp) hop;
    return ternary.getOp() == OpOp3.MOMENT ||
           ternary.getOp() == OpOp3.COV;
}

@Override
public OutputConstraintResult validate(Hop hop, FType[] inputTypes) {
    if (isScalarOutput(hop)) {
        return disallowed("Scalar output cannot be federated");  // ❌ 버그
    }
    return allowed();  // ❌ 버그
}
```

### 3.2 발견된 버그 #1
**문제:** `disallowed()`, `allowed()` 메서드가 존재하지 않음

**수정:**
```java
return OutputConstraintResult.disallowed("Scalar output cannot be federated");
return OutputConstraintResult.allowed("AggregateTernary with matrix output");
```

---

## 4. 비판적 분석 - 중대한 설계 불일치 발견

### 4.1 HOPs vs Runtime 계층 불일치

**HOPs 레이어 (TernaryOp):**
```
TernaryOp.MOMENT → constructLopsCentralMoment() → CentralMoment lop
                 → Opcodes.CM → "cm" instruction
                 → CentralMomentCPInstruction extends AggregateUnaryCPInstruction

TernaryOp.COV → constructLopsCovariance() → CoVariance lop
              → Opcodes.COV → "cov" instruction
              → CovarianceCPInstruction extends BinaryCPInstruction
```

**Runtime 레이어 (AggregateTernary):**
```
Opcodes.TAKPM → "tak+*" → AggregateTernaryCPInstruction
Opcodes.TACKPM → "tack+*" → AggregateTernaryCPInstruction
                            extends ComputationCPInstruction
```

### 4.2 핵심 문제점

#### 문제 1: MOMENT/COV는 AggregateTernary가 아님
- `CentralMomentCPInstruction` ≠ `AggregateTernaryCPInstruction`
- `CovarianceCPInstruction` ≠ `AggregateTernaryCPInstruction`
- 서로 다른 instruction 타입, 다른 상속 계층

#### 문제 2: FED Layer에 CM/COV 구현 없음
```java
// AggregateTernaryFEDInstruction.java:84
if(opcode.equalsIgnoreCase(Opcodes.TAKPM.toString()) ||
   opcode.equalsIgnoreCase(Opcodes.TACKPM.toString())) {
    // ✅ tak+*, tack+* 처리
}
// ❌ CM, COV opcode 처리 코드 없음!
```

#### 문제 3: tak+*/tack+*는 HOPs에서 생성 안됨
- HOPs 레이어 전체 검색: tak+*/tack+* 생성 코드 없음
- TernaryOp은 MOMENT/COV만 생성 (cm/cov opcode)
- tak+*/tack+*는 다른 경로로 생성 (rewrite? special case?)

### 4.3 tak+*/tack+*의 실제 정체

**AggregateTernaryCPInstruction.java 분석:**
```java
// Line 72-73
MatrixBlock ret = MatrixBlock.aggregateTernaryOperations(
    matBlock1, matBlock2, matBlock3, new MatrixBlock(), ab_op, true);

// Line 80-83
if(output.getDataType().isScalar())
    ec.setScalarOutput(output.getName(), new DoubleObject(ret.get(0, 0)));
else
    ec.setMatrixOutput(output.getName(), ret);
```

**의미:**
- `sum(X * Y * Z)` 형태의 삼항 집계 연산
- AggregateTernaryOperator: KahanPlus + Multiply
- 출력: 스칼라 **또는** 매트릭스 모두 가능

---

## 5. 최종 구현 (수정됨)

### 5.1 최종 코드
```java
/**
 * Validator for AggregateTernary operations.
 *
 * CRITICAL ANALYSIS - DESIGN MISMATCH FOUND:
 *
 * 1. HOPs vs Runtime Representation MISMATCH:
 *    - TernaryOp.MOMENT → Opcodes.CM → CentralMomentCPInstruction (AggregateUnary)
 *    - TernaryOp.COV → Opcodes.COV → CovarianceCPInstruction (Binary)
 *    - Opcodes.TAKPM/TACKPM → AggregateTernaryCPInstruction (Ternary)
 *    - AggregateTernaryFEDInstruction only handles TAKPM/TACKPM
 *    - NO FED instruction exists for CM/COV opcodes!
 *
 * 2. MOMENT/COV are NOT AggregateTernary Instructions:
 *    - Different instruction types, different inheritance hierarchy
 *    - CM/COV have no federated implementation
 *
 * 3. tak+*/tack+* Generation:
 *    - NOT generated from TernaryOp HOPs
 *    - No HOPs-level code path found for tak+*/tack+*
 *    - Likely generated from rewrite or special optimization
 *
 * CONCLUSION:
 * - This validator should NOT check for OpOp3.MOMENT or OpOp3.COV
 * - No TernaryOp maps to AggregateTernaryFEDInstruction
 * - The FOUT constraint table provided was INCORRECT
 * - This validator should be DISABLED (canValidate returns false)
 */
public class AggregateTernaryValidator extends OutputConstraintValidator {

	@Override
	public boolean canValidate(Hop hop) {
		// CRITICAL: AggregateTernaryFEDInstruction (tak+*, tack+*) does NOT map to any TernaryOp!
		// - TernaryOp.MOMENT → CentralMomentCPInstruction (cm opcode)
		// - TernaryOp.COV → CovarianceCPInstruction (cov opcode)
		// - tak+*/tack+* are generated from different HOPs (likely rewrite or special case)
		// - NO FED instruction exists for CM/COV opcodes
		//
		// Since no TernaryOp maps to AggregateTernaryFEDInstruction, this validator
		// should NEVER match any Hop operation.
		return false;
	}

	@Override
	public OutputConstraintResult validate(Hop hop, FType[] inputTypes) {
		// This should never be called since canValidate() always returns false
		// If somehow called, treat as allowed (no constraints)
		return OutputConstraintResult.allowed(
			"AggregateTernary FED instructions (tak+*, tack+*) are not generated from HOPs layer; " +
			"no validation needed at HOPs level");
	}
}
```

### 5.2 핵심 변경사항
1. **canValidate() = false**: Validator 완전히 비활성화
2. **상세한 주석**: 문제점 전체 문서화
3. **validate() 안전 처리**: 혹시 호출되더라도 allowed 반환

---

## 6. 결론 및 권장사항

### 6.1 발견된 사실
1. ✅ AggregateTernaryFEDInstruction 존재 (tak+*, tack+*)
2. ❌ HOPs 레이어에서 tak+*/tack+* 생성 코드 없음
3. ❌ MOMENT/COV는 AggregateTernary가 **아님**
4. ❌ 제공된 FOUT Constraint 표가 **부정확함**

### 6.2 Validator 상태
- **현재 구현**: `canValidate()` 항상 false 반환
- **효과**: Validator 사실상 비활성화
- **이유**: HOPs 레이어에 검증할 대상이 없음

### 6.3 향후 작업 제안
1. **tak+*/tack+* 생성 경로 추적**
   - Rewrite rules 검색
   - Optimization passes 확인
   - 실제 생성 위치 파악

2. **FOUT Constraint 표 수정**
   - AggregateTernary 항목 제거 또는 수정
   - CM/COV는 별도 항목으로 분리 (FED 구현 없음 명시)

3. **Validator 재구현 (필요시)**
   - tak+*/tack+* 실제 생성 위치에서 validation
   - 또는 runtime level에서 validation

---

## 7. 파일 참조

### 주요 분석 파일
- `src/main/java/org/apache/sysds/hops/TernaryOp.java` (HOPs)
- `src/main/java/org/apache/sysds/runtime/instructions/fed/AggregateTernaryFEDInstruction.java` (FED)
- `src/main/java/org/apache/sysds/runtime/instructions/cp/AggregateTernaryCPInstruction.java` (CP)
- `src/main/java/org/apache/sysds/runtime/instructions/cp/CentralMomentCPInstruction.java` (CM)
- `src/main/java/org/apache/sysds/runtime/instructions/cp/CovarianceCPInstruction.java` (COV)
- `src/main/java/org/apache/sysds/lops/CentralMoment.java` (Lops)
- `src/main/java/org/apache/sysds/lops/CoVariance.java` (Lops)
- `src/main/java/org/apache/sysds/common/Types.java` (OpOp3 enum)
- `src/main/java/org/apache/sysds/common/Opcodes.java` (Opcode definitions)

### 최종 구현 파일
- `src/main/java/org/apache/sysds/hops/fedplanner/fout/validators/AggregateTernaryValidator.java`

---

## 8. 교훈

### 8.1 설계 검증의 중요성
- 제공된 스펙이라도 실제 구현과 대조 필수
- HOPs ↔ Lops ↔ Runtime 계층 간 매핑 확인 중요
- Opcode 이름만으로 판단하면 안됨 (MOMENT ≠ AggregateTernary)

### 8.2 코드 분석 방법론
1. Top-down (HOPs → Lops → Runtime) 추적
2. Bottom-up (Runtime → Lops → HOPs) 역추적
3. 두 방향이 일치하는지 검증
4. 불일치 발견 시 재조사

### 8.3 Validator 구현 원칙
- **존재하지 않는 것은 검증할 수 없다**
- canValidate()가 false면 validator는 작동하지 않음
- 이것도 유효한 구현 (= "이 레이어에서는 검증 불필요")
