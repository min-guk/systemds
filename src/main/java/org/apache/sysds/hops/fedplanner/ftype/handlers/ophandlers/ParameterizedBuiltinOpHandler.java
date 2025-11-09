package org.apache.sysds.hops.fedplanner.ftype.handlers.ophandlers;

import org.apache.sysds.common.Types.*;
import org.apache.sysds.hops.*;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.ftype.handlers.FederatedTypeHandler;

// FederatedTypeHandler.java (혹은 네가 쓰는 핸들러 모음 파일 안)
public class ParameterizedBuiltinOpHandler extends FederatedTypeHandler {
    @Override
    public boolean canHandle(Hop hop) {
        return hop instanceof ParameterizedBuiltinOp;
    }

    @Override
    public HandlerResult determineType(Hop hop, FType[] inputTypes) {
        final ParameterizedBuiltinOp pbop = (ParameterizedBuiltinOp) hop;
        final ParamBuiltinOp op = pbop.getOp();

        // 0) FED 미지원 연산: 항상 unsupported
        //    - AUTODIFF: FED 미지원 (ParameterizedBuiltinFEDInstruction에 없음)
        //    - REXPAND: 현재 FED 미지원 (ParameterizedBuiltinFEDInstruction에 없음)
        //    - LIST/PARAMSERV/CDF/INVCDF/TOSTRING/TRANSFORMCOLMAP/TRANSFORMMETA/GROUPEDAGG: FED 경로 아님
        //    주의: CONTAINS는 스칼라 반환이지만 FED 구현 있음 (line 176-183) - 지원됨!
        if (op == ParamBuiltinOp.AUTODIFF
            || op == ParamBuiltinOp.REXPAND
            || op == ParamBuiltinOp.LIST
            || op == ParamBuiltinOp.PARAMSERV
            || op == ParamBuiltinOp.CDF
            || op == ParamBuiltinOp.INVCDF
            || op == ParamBuiltinOp.TOSTRING
            || op == ParamBuiltinOp.TRANSFORMCOLMAP
            || op == ParamBuiltinOp.TRANSFORMMETA
            || op == ParamBuiltinOp.GROUPEDAGG) {
            return HandlerResult.unsupported("ParameterizedBuiltinOp(" + op + "): not federated (LOCAL).");
        }

        // 1) CONTAINS 특수 처리: 스칼라 결과(BROADCAST)를 반환
        //    FED 구현 있음 (ParameterizedBuiltinFEDInstruction line 176-183)
        if (op == ParamBuiltinOp.CONTAINS) {
            final FType xType = (inputTypes != null && inputTypes.length > 0) ? inputTypes[0] : null;
            if (!isPartitioned(xType)) {
                return HandlerResult.unsupported(
                    "ParameterizedBuiltinOp(CONTAINS): requires partitioned target (ROW/COL), got " + xType);
            }
            // CONTAINS는 boolean 스칼라를 반환 → BROADCAST
            return HandlerResult.supported(FType.BROADCAST,
                "ParameterizedBuiltinOp(CONTAINS): federated aggregation returns boolean scalar → BROADCAST");
        }

        // 2) 첫 입력(타깃) 타입 확인: 오직 ROW/COL(분할형)만 FED 지원
        final FType xType = (inputTypes != null && inputTypes.length > 0) ? inputTypes[0] : null;
        if (!isPartitioned(xType)) {
            // BROADCAST 또는 null(LOCAL)인 경우도 런타임에서 FED 파싱 가드에 막힘
            return HandlerResult.unsupported(
                "ParameterizedBuiltinOp(" + op + "): requires partitioned target (ROW/COL), got " + xType);
        }

        // 3) 나머지 파라미터에 federated 유형이 섞이면 바인딩 모호성 → 차단
        if (hasOtherFederatedInputs(inputTypes)) {
            return HandlerResult.unsupported(
                "ParameterizedBuiltinOp(" + op + "): other federated inputs not supported.");
        }

        // 4) 런타임이 FED로 처리하는 지원 연산: 출력은 입력 분할(ROW/COL) 그대로 보존
        switch (op) {
            case REPLACE:
            case RMEMPTY:
            case LOWER_TRI:
            case UPPER_TRI:
            case TRANSFORMAPPLY:
            case TRANSFORMDECODE:
            case TOKENIZE:
                return HandlerResult.supported(xType,
                    "ParameterizedBuiltinOp(" + op + "): preserves partitioned structure " + xType);
            default:
                // 안전망: 위에서 빠진 것이면 보수적으로 차단
                return HandlerResult.unsupported(
                    "ParameterizedBuiltinOp(" + op + "): not supported for federated execution.");
        }
    }

    private static boolean isPartitioned(FType t) {
        return t == FType.ROW || t == FType.COL;
    }

    private static boolean hasOtherFederatedInputs(FType[] in) {
        if (in == null) return false;
        for (int i = 1; i < in.length; i++) {
            if (in[i] == FType.ROW || in[i] == FType.COL || in[i] == FType.BROADCAST)
                return true;
        }
        return false;
    }
}
