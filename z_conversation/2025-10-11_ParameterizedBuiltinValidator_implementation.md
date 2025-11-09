# ParameterizedBuiltinValidator 구현 - 최종 분석 및 결론

**작성일:** 2025-10-11
**파일:** `src/main/java/org/apache/sysds/hops/fedplanner/fout/validators/ParameterizedBuiltinValidator.java`

## 📋 요약

ParameterizedBuiltin 연산에 대한 FOUT(Federated Output) 제약 조건을 검증하는 validator를 구현했습니다. 구현 과정에서 **CONTAINS OpCode의 FOUT 가능 여부에 대한 치명적 오류**를 발견하고 수정했습니다.

---

## 🔍 구현 검증 과정

### 1차 구현 (잘못됨)
```java
// CONTAINS → disallowed("Boolean scalar result")
```

**문제:** 표 기반으로만 구현, 실제 런타임 동작 미확인

---

### 1차 수정 (역시 잘못됨)
```java
// CONTAINS → allowed("Boolean scalar aggregation → BROADCAST")
```

**근거 (잘못된):**
- FType 전파 핸들러: `return HandlerResult.supported(FType.BROADCAST, ...)`
- 런타임: `ec.setVariable(output.getName(), new BooleanObject(ret))`
- "BROADCAST 타입 = federated output" 오해

**오류:** BROADCAST 타입과 실제 federated output을 혼동

---

### 2차 심층 검증 (올바름)

#### 핵심 증거 발견: `isFederatedExcept(FType.BROADCAST)`

```java
// CacheableData.java:421-423
public boolean isFederatedExcept(FType type) {
    return isFederated() && !isFederated(type);
}

// ParameterizedBuiltinFEDInstruction.java:150
if(ArrayUtils.contains(PARAM_BUILTINS, inst.getOpcode()) &&
   inst.getTarget(ec).isFederatedExcept(FType.BROADCAST))
    return ParameterizedBuiltinFEDInstruction.parseInstruction(inst);
```

**의미 해석:**
- `isFederatedExcept(FType.BROADCAST)` = "federated이면서 BROADCAST가 **아닌** 것"
- 즉, **ROW 또는 COL만 허용**, BROADCAST는 차단

#### CONTAINS 실제 동작 분석

| 단계 | 코드 | 의미 |
|------|------|------|
| **입력 제약** | `isFederatedExcept(FType.BROADCAST)` (line 150) | ROW/COL만 허용 |
| **런타임 실행** | `boolean ret = FederationUtils.aggBooleanScalar(tmp)`<br>`ec.setVariable(output.getName(), new BooleanObject(ret))` (line 182-183) | **LOCAL 스칼라** 생성 |
| **FederationMap** | `.setFedMapping()` 호출 없음 | federated output 없음 |
| **FType 전파** | `HandlerResult.supported(FType.BROADCAST, ...)` (OperationHandlers:1110) | 의미적 타입일 뿐 |

---

## 🎯 최종 결론

### BROADCAST 타입의 정확한 의미

**잘못된 이해:**
> "BROADCAST 타입 = federated output이므로 FOUT 가능"

**올바른 이해:**
> "BROADCAST 타입 = 의미적 타입 표시"
> - coordinator의 LOCAL 값이 워커들에 broadcast될 수 있음을 표시
> - 실제 FederationMap은 없음 (coordinator-local 값)

### CONTAINS OpCode의 FOUT 제약

```java
// CONTAINS: FOUT DISALLOWED
if (opCode == org.apache.sysds.common.Types.ParamBuiltinOp.CONTAINS) {
    return OutputConstraintResult.disallowed(
        "ParameterizedBuiltin(CONTAINS): Returns LOCAL boolean scalar (cannot produce federated output)"
    );
}
```

**검증 체인:**
1. **입력 제약:** `isFederatedExcept(FType.BROADCAST)` → ROW/COL만 허용
2. **출력 타입:** `new BooleanObject(ret)` → LOCAL 스칼라
3. **FederationMap:** 없음 → federated output 불가능
4. **FType 전파:** BROADCAST → 의미적 표현 (실제 federation 아님)

**결론:** Output은 coordinator에만 존재하는 LOCAL scalar → **FOUT 불가능**

---

## 📊 최종 구현 사양

### FOUT 가능 OpCode (allowed)

```java
REPLACE, RMEMPTY, LOWER_TRI, UPPER_TRI, TRANSFORMDECODE, TRANSFORMAPPLY, TOKENIZE
```

**이유:** 모두 `out.setFedMapping(mo.getFedMapping().copyWithNewID(fr1.getID()))`를 호출하여 federated output 구조 유지

### FOUT 불가능 OpCode (disallowed)

1. **CONTAINS**: LOCAL boolean scalar 반환 (FederationMap 없음)
2. **CP-only OpCode**: AUTODIFF, REXPAND, GROUPEDAGG, CDF, INVCDF, TRANSFORMCOLMAP, TRANSFORMMETA, TOSTRING, LIST, PARAMSERV

---

## 🔑 핵심 교훈

### 1. BROADCAST 타입 ≠ Federated Output

**증거:**
- CONTAINS: FType.BROADCAST이지만 LOCAL 스칼라
- AggUnary 전체 집계: FType.BROADCAST이지만 LOCAL 스칼라
- 실제 federated output 여부는 **FederationMap 존재**로 판단

### 2. 검증 체크리스트

✅ **런타임 코드 직접 확인**
- `ec.setVariable()` 호출 확인
- `.setFedMapping()` 호출 여부 확인
- `isFederatedExcept()` 의미 정확히 이해

✅ **FederationMap 존재 여부가 핵심**
- BROADCAST 타입만으로는 FOUT 가능 여부 판단 불가
- 실제 FederationMap 설정 여부 확인 필수

✅ **다른 validator 패턴 참고**
- AggregateUnaryValidator: 스칼라 출력 → disallowed
- AggregateBinaryValidator: conditional 패턴 사용

### 3. 문서화의 중요성

모든 결정에 **소스 코드 라인 번호**와 **검증 근거** 명시:
```java
// Verification chain:
// 1. Input constraint (line 150): isFederatedExcept(FType.BROADCAST)
//    - Meaning: federated AND NOT BROADCAST → Only ROW/COL allowed
// 2. Runtime execution (line 176-183):
//    - boolean ret = FederationUtils.aggBooleanScalar(tmp)
//    - ec.setVariable(output.getName(), new BooleanObject(ret))
//    - Output is LOCAL scalar at coordinator
// 3. FType propagation (OperationHandlers.java:1110): returns BROADCAST
//    - This is SEMANTIC type for downstream operations
//    - NOT actual federated output (no FederationMap)
// 4. CacheableData.java:421-423:
//    - isFederatedExcept(type) = isFederated() && !isFederated(type)
//    - Confirms output is NOT federated
// Conclusion: Output is LOCAL scalar → FOUT impossible
```

---

## 📁 관련 파일

1. **구현 파일:**
   - `src/main/java/org/apache/sysds/hops/fedplanner/fout/validators/ParameterizedBuiltinValidator.java`

2. **검증 참고 파일:**
   - `src/main/java/org/apache/sysds/runtime/instructions/fed/ParameterizedBuiltinFEDInstruction.java` (line 90-92, 150, 176-215)
   - `src/main/java/org/apache/sysds/hops/ParameterizedBuiltinOp.java` (line 128-130)
   - `src/main/java/org/apache/sysds/common/Types.java` (line 805-810)
   - `src/main/java/org/apache/sysds/runtime/controlprogram/caching/CacheableData.java` (line 421-423)
   - `src/main/java/org/apache/sysds/hops/fedplanner/ftype/handlers/OperationHandlers.java` (line 1072-1158)

3. **비교 참고 validator:**
   - `src/main/java/org/apache/sysds/hops/fedplanner/fout/validators/AggregateUnaryValidator.java`
   - `src/main/java/org/apache/sysds/hops/fedplanner/fout/validators/AggregateBinaryValidator.java`

---

## ✅ 구현 완료 확인

- ✅ 런타임 구현 분석 완료
- ✅ `isFederatedExcept()` 의미 확인
- ✅ FederationMap 존재 여부 확인
- ✅ BROADCAST 타입의 의미 정확히 이해
- ✅ 다른 validator들과 패턴 일치 확인
- ✅ 모든 증거에 소스 코드 라인 번호 명시
- ✅ 문서화 완료 (클래스 주석, 메서드 주석, 검증 체인)

**구현 최종 확정 완료** 🎉
