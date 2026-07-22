package com.aimall.server.service;

import com.aimall.server.entity.OmsOrderReturnApply;

import java.util.List;

public interface ReturnApplyService {
    List<OmsOrderReturnApply> listByMember(Long memberId);

    OmsOrderReturnApply getByIdForMember(Long memberId, Long id);

    OmsOrderReturnApply getLatestByOrderForMember(Long memberId, Long orderId);

    OmsOrderReturnApply apply(Long memberId, Long orderId, String reason, String description);

    OmsOrderReturnApply apply(
            Long memberId,
            Long orderId,
            String reason,
            String description,
            List<ReturnItemRequest> items
    );
    OmsOrderReturnApply apply(Long memberId, Long orderId, String reason, String description,
                              List<ReturnItemRequest> items, String type);

    OmsOrderReturnApply cancel(Long memberId, Long id);

    List<OmsOrderReturnApply> listAll();

    OmsOrderReturnApply getById(Long id);

    OmsOrderReturnApply review(Long id, boolean approved, String handleNote);

    OmsOrderReturnApply refund(Long id, String requestId, String handleNote);

    OmsOrderReturnApply retryFailedRefund(Long id, String handleNote);

    OmsOrderReturnApply closeFailedRefund(Long id, String handleNote);

    record ReturnItemRequest(Long orderItemId, Integer quantity) {
    }
}
