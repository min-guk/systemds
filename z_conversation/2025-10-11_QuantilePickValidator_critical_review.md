# QuantilePickValidator - Critical Review & Final Implementation

**Date:** 2025-10-11
**Task:** Implement QuantilePickValidator based on FOUT Constraint Table
**Status:** ✅ COMPLETED (after 2 critical review cycles)

---

## 초기 구현의 치명적 오류 ❌

### 잘못된 해석
원래 FOUT Constraint 표:
```
Instruction Class              | OP Type  | OpCode  | FOUT Possible?     | FOUT Constraint/Reason
QuantilePickFEDInstruction     | QPick    | qpick   | Yes (conditional)  | Only COL/FULL partitioning supported; ROW forces LOUT
```

**초기 구현의 오류:**
- "COL/FULL partitioning supported" → "COL/FULL은 FOUT 지원" ❌
- ROW만 disallowed, COL/FULL은 allowed 반환
- **완전히 잘못된 구현**

---

## 비판적 검토 과정

### 1차 검토: setFedMapping() 호출 확인

**QuantilePickFEDInstruction.java 분석:**

```java
// processColumnQPick (line 676-735) - COL/FULL partitions
public void processColumnQPick(ExecutionContext ec) {
    List<Object> res = new ArrayList<>();
    fedMapping.mapParallel(varID, (range, data) -> {
        // ... worker에서 계산
        res.add(response.getData()[0]);  // line 719: 로컬 수집
        return null;
    });

    assert res.size() == 1;  // line 727: 하나의 결과만 예상

    // line 732-735: LOCAL output 설정
    if(output.isScalar())
        ec.setScalarOutput(output.getName(), new DoubleObject((double) res.get(0)));
    else
        ec.setMatrixOutput(output.getName(), (MatrixBlock) res.get(0));
    // ❌ NO setFedMapping() call!
}

// processRowQPick (line 234-293) - ROW partitions
public void processRowQPick(ExecutionContext ec) {
    // ... histogram consolidation
    // line 289, 292: LOCAL output 설정
    getSingleQuantileResult(...);  // calls ec.setScalarOutput/setMatrixOutput
    // ❌ NO setFedMapping() call!
}
```

**결정적 증거:**
- `setFedMapping()` 호출이 **어디에도 없음**
- 비교: BinaryMatrixMatrixFEDInstruction은 `out.setFedMapping()` 호출
- 비교: AggregateUnaryFEDInstruction은 `processFederatedOutput()`에서 `setFedMapping()` 호출

### 2차 검토: _fedOut 필드 사용 확인

**_fedOut 필드 존재 여부:**
```bash
$ grep -n "_fedOut" QuantilePickFEDInstruction.java
74:     boolean inmem, String opcode, String istr, FederatedOutput fedOut) {
81:     this(op, in, in2, out, type, inmem, opcode, istr, FederatedOutput.NONE);
99:     FederatedOutput fedOut = FederatedOutput.valueOf(parts[parts.length-1]);
127:    inst._fedOut = fedOut;
```

**_fedOut 필드 사용 여부:**
```bash
$ grep -n "if.*_fedOut\|_fedOut\.is\|_fedOut ==" QuantilePickFEDInstruction.java
# NO OUTPUT - 전혀 사용되지 않음!
```

**processInstruction() 비교:**
```java
// QuantilePickFEDInstruction (line 132-137)
@Override
public void processInstruction(ExecutionContext ec) {
    if(ec.getMatrixObject(input1).isFederated(FType.COL) ||
       ec.getMatrixObject(input1).isFederated(FType.FULL))
        processColumnQPick(ec);
    else
        processRowQPick(ec);
    // ❌ NO _fedOut check!
}

// AggregateUnaryFEDInstruction (비교)
private void processDefault(ExecutionContext ec){
    // ...
    if ( _fedOut.isForcedFederated() )  // ✓ _fedOut 확인
        processFederatedOutput(map, in, ec);
    else
        processGetOutput(map, aop, ec, in);
}
```

**결론:**
- `_fedOut` 필드는 상속되지만 **전혀 사용되지 않음**
- processInstruction()에서 FType만 확인, _fedOut은 무시

### 3차 검토: Constraint 표의 의미 재해석

**"COL/FULL only, ROW→LOUT"의 정확한 의미:**

1. **Federated COMPUTATION (계산):**
   - COL/FULL: Workers가 독립적으로 quantile 계산 가능 ✓
   - ROW: 전역 histogram consolidation 필요 ✓

2. **Federated OUTPUT (출력):**
   - COL/FULL: ❌ 로컬 output (line 732-735: `ec.setMatrixOutput()`)
   - ROW: ❌ 로컬 output (line 289, 292: `ec.setScalarOutput()`)

**핵심 통찰:**
- Constraint 표는 federated **computation** 지원을 언급
- Federated **output** 지원과는 **완전히 별개**
- COL/FULL도 결국 결과를 수집해서 local output 생성

---

## 최종 구현 ✅

### Hop Type 식별 (canValidate)

```java
@Override
public boolean canValidate(Hop hop) {
    // QuantilePick operations are distributed across multiple Hop types:

    // 1. UnaryOp: MEDIAN, IQM
    if (hop instanceof UnaryOp) {
        UnaryOp uop = (UnaryOp) hop;
        return uop.getOp() == OpOp1.MEDIAN || uop.getOp() == OpOp1.IQM;
    }

    // 2. BinaryOp: IQM, INTERQUANTILE (weighted versions)
    else if (hop instanceof BinaryOp) {
        BinaryOp bop = (BinaryOp) hop;
        return bop.getOp() == OpOp2.IQM || bop.getOp() == OpOp2.INTERQUANTILE;
    }

    // 3. TernaryOp: QUANTILE, INTERQUANTILE
    else if (hop instanceof TernaryOp) {
        TernaryOp top = (TernaryOp) hop;
        return top.getOp() == OpOp3.QUANTILE || top.getOp() == OpOp3.INTERQUANTILE;
    }

    return false;
}
```

**검증 결과:**
- ✅ UnaryOp.java:204, 233 - MEDIAN, IQM → PickByCount lops
- ✅ BinaryOp.java:260, 284, 345 - IQM, INTERQUANTILE → PickByCount lops
- ✅ TernaryOp.java:246 - QUANTILE, INTERQUANTILE → PickByCount lops
- ✅ PickByCount.java:32 - OpCode "qpick"

### FOUT Validation (validate)

```java
@Override
public OutputConstraintResult validate(Hop hop, FType[] inputTypes) {
    String opName = getOperationName(hop);

    // CRITICAL FINDING: QuantilePickFEDInstruction does NOT support FOUT
    // for ANY partitioning type

    return OutputConstraintResult.disallowed(
        "QuantilePick (" + opName + "): FOUT not supported - " +
        "instruction always produces local output (no setFedMapping call)");
}
```

**Validation 근거:**
1. ❌ `setFedMapping()` 호출 없음 (line 676-735, 234-293)
2. ❌ `_fedOut` 필드 미사용 (processInstruction line 132-137)
3. ✓ 항상 `ec.setScalarOutput()` / `ec.setMatrixOutput()` 호출
4. ✓ 결과를 로컬로 수집: `res.add(response.getData()[0])` (line 719)

---

## 검증 증거 체인

### Evidence #1: setFedMapping() Pattern

**FOUT 지원 Instruction:**
```java
// BinaryMatrixMatrixFEDInstruction
MatrixObject out = ec.getMatrixObject(output);
FederationMap outputFedMap = mo1.getFedMapping()
    .copyWithNewIDAndRange(mo1.getNumRows(), mo2.getNumColumns(), outputID);
out.setFedMapping(outputFedMap);  // ✓ FOUT enabled
```

**QuantilePickFEDInstruction:**
```java
// processColumnQPick
ec.setMatrixOutput(output.getName(), (MatrixBlock) res.get(0));  // ❌ Local output
// NO setFedMapping() → NO FOUT
```

### Evidence #2: _fedOut Field Usage

**FOUT 지원 Instruction:**
```java
// AggregateUnaryFEDInstruction.processDefault()
if ( _fedOut.isForcedFederated() )  // ✓ Checks _fedOut
    processFederatedOutput(map, in, ec);
else
    processGetOutput(map, aop, ec, in);
```

**QuantilePickFEDInstruction:**
```java
// processInstruction() - line 132-137
if(ec.getMatrixObject(input1).isFederated(FType.COL) ||
   ec.getMatrixObject(input1).isFederated(FType.FULL))
    processColumnQPick(ec);
else
    processRowQPick(ec);
// ❌ NEVER checks _fedOut
```

### Evidence #3: Result Collection Pattern

```java
// processColumnQPick - line 680-727
List<Object> res = new ArrayList<>();
fedMapping.mapParallel(varID, (range, data) -> {
    // Execute on worker
    res.add(response.getData()[0]);  // Collect locally
    return null;
});

assert res.size() == 1;  // Expect single consolidated result

// Set LOCAL output
if(output.isScalar())
    ec.setScalarOutput(output.getName(), new DoubleObject((double) res.get(0)));
else
    ec.setMatrixOutput(output.getName(), (MatrixBlock) res.get(0));
```

**패턴 분석:**
- `res.add()`: Worker 결과를 **로컬 list에 수집**
- `assert res.size() == 1`: **하나의 consolidated result** 예상
- `ec.setMatrixOutput()`: **로컬 output** 설정
- **Federated map 유지 안함** → No FOUT

---

## 최종 FOUT Constraint Table

```
Instruction Class              | OP Type  | OpCode  | FOUT Possible? | FOUT Constraint/Reason
-------------------------------|----------|---------|----------------|------------------------------------------
QuantilePickFEDInstruction     | QPick    | qpick   | No             | No setFedMapping() call; always produces local output
```

**Constraint Logic:**
- **ALL partitioning types (ROW/COL/FULL)** → `disallowed()`
- **Reason:** processInstruction() always collects results and sets local output
- **No federated output path exists** in the implementation

**Critical Clarification:**
- Original constraint "COL/FULL only, ROW→LOUT" refers to **federated COMPUTATION**
- NOT federated **OUTPUT**
- Both COL/FULL and ROW paths produce **local output**

---

## 파일 위치

**구현 파일:**
```
src/main/java/org/apache/sysds/hops/fedplanner/fout/validators/QuantilePickValidator.java
```

**검증 파일:**
- `src/main/java/org/apache/sysds/runtime/instructions/fed/QuantilePickFEDInstruction.java`
- `src/main/java/org/apache/sysds/hops/UnaryOp.java` (line 204, 233)
- `src/main/java/org/apache/sysds/hops/BinaryOp.java` (line 260, 284, 345)
- `src/main/java/org/apache/sysds/hops/TernaryOp.java` (line 246)
- `src/main/java/org/apache/sysds/lops/PickByCount.java` (line 32)

---

## 교훈

1. **Constraint 표의 애매한 표현 주의**
   - "COL/FULL supported" ≠ "FOUT supported"
   - Federated computation vs. federated output 구분 필요

2. **실제 구현 확인 필수**
   - `setFedMapping()` 호출 여부 확인
   - `_fedOut` 필드 사용 여부 확인
   - 다른 instruction과 패턴 비교

3. **비판적 검토의 중요성**
   - 초기 구현: 완전히 잘못됨 ❌
   - 1차 검토: 올바른 구현으로 수정 ✅
   - 2차/3차 검토: 더 명확한 근거 추가 ✅

4. **증거 기반 구현**
   - 추측이 아닌 코드 분석
   - 명확한 line number 참조
   - 다른 구현과의 비교

---

## 결론

QuantilePickValidator는 **모든 partitioning type에 대해 FOUT를 disallow**합니다.

**핵심 근거:**
- ❌ `setFedMapping()` 호출 없음
- ❌ `_fedOut` 필드 미사용
- ✓ 항상 로컬 output 생성
- ✓ Worker 결과를 로컬로 수집

**구현 상태:** ✅ **검증 완료 및 정확성 확인**
