package com.aimall.server.service.impl;

import com.aimall.server.service.AiActionExecutionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AiActionExecutionRecoveryJob {

    private final AiActionExecutionService executionService;

    public AiActionExecutionRecoveryJob(AiActionExecutionService executionService) {
        this.executionService = executionService;
    }

    @Scheduled(fixedDelayString = "${aimall.ai-action.recovery-scan-ms:30000}")
    public void detectStaleExecutions() {
        executionService.markStaleExecutionsForRecovery();
    }
}
