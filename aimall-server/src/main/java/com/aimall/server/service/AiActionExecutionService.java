package com.aimall.server.service;

import java.util.Map;
import java.util.List;
import com.aimall.server.entity.AiActionExecution;

public interface AiActionExecutionService {
    Reservation reserve(String actionId, String actionType, Long memberId, Map<String, Object> request);

    void markSuccess(String actionId, Map<String, Object> result);

    void markFailed(String actionId, String errorMessage);

    int markStaleExecutionsForRecovery();

    List<AiActionExecution> listRecoveryRequired(int limit);

    void resolveRecovery(String actionId, boolean succeeded, Map<String, Object> result, String note);

    record Reservation(boolean shouldExecute, Map<String, Object> replayResult) {
    }
}
