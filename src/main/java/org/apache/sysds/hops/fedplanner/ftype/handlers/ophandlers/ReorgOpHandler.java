package org.apache.sysds.hops.fedplanner.ftype.handlers.ophandlers;

import org.apache.sysds.common.Types.*;
import org.apache.sysds.hops.*;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.ftype.handlers.FederatedTypeHandler;

public class ReorgOpHandler extends FederatedTypeHandler {
    @Override
    public boolean canHandle(Hop hop) { return hop instanceof ReorgOp; }

    private static boolean allLocal(FType[] in) {
        if (in == null || in.length == 0) return true;
        for (FType t : in) if (t != null) return false;
        return true;
    }

    // LOCAL→BROADCAST 승격 허용 목록 (전파규칙에 맞춤)
    private static boolean supportsLocalPromotion(ReOrgOp op) {
        // TRANS/DIAG/RESHAPE/REV/ROLL/SORT 모두 all-local이면 코디네이터에서 안전하게 계산 후 브로드캐스트 가능
        switch (op) {
            case TRANS:
            case DIAG:
            case RESHAPE:
            case REV:
            case ROLL:
            case SORT:
                return true;
            default:
                return false;
        }
    }

    @Override
    public HandlerResult determineType(Hop hop, FType[] inputTypes) {
        ReorgOp rop = (ReorgOp) hop;
        ReOrgOp op = rop.getOp();

        // 1) all-LOCAL 특례: LOCAL → BROADCAST 승격
        if (allLocal(inputTypes)) {
            if (supportsLocalPromotion(op)) {
                return HandlerResult.supported(
                    FType.BROADCAST,
                    "ReorgOp: " + op + " on LOCAL → promote to BROADCAST"
                );
            }
            return HandlerResult.unsupported(
                "ReorgOp: " + op + " is not safely promotable from LOCAL"
            );
        }

        // 2) federated-first guard (LOCAL 특례 처리 이후에만 적용)
        if (!hasFederatedFirstInput(inputTypes)) {
            return HandlerResult.unsupported(
                "ReorgOp: Requires federated first input or all-local promotable"
            );
        }

        FType first = inputTypes[0];

        // 3) BROADCAST 입력: 그대로 유지
        if (first == FType.BROADCAST) {
            // TRANS 포함 전부 BROADCAST 유지 (분산 복제 데이터의 재배열은 로컬 처리 후 유지)
            return HandlerResult.supported(
                FType.BROADCAST,
                "ReorgOp: " + op + " on BROADCAST maintains BROADCAST"
            );
        }

        // 4) partitioned(ROW/COL) 입력에서 파티션 깨는 연산은 차단
        switch (op) {
            case RESHAPE:
            case REV:
            case ROLL:
            case SORT:
            case DIAG:
                return HandlerResult.unsupported(
                    "ReorgOp: " + op + " breaks partitioning assumptions on federated input"
                );
            case TRANS:
                // TRANS는 ROW↔COL 스왑
                return HandlerResult.supported(
                    transposePartition(first),
                    "ReorgOp: TRANS applied on federated input"
                );
            default:
                // 구조 유지형(추가 케이스)만 first 유지
                return HandlerResult.supported(
                    first, "ReorgOp: " + op + " maintains structure"
                );
        }
    }
}
