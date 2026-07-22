package com.aimall.server.config;

import com.aimall.server.mapper.InternalRequestNonceMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InternalNonceCleanupJob {

    private final InternalRequestNonceMapper nonceMapper;

    public InternalNonceCleanupJob(InternalRequestNonceMapper nonceMapper) {
        this.nonceMapper = nonceMapper;
    }

    @Scheduled(fixedDelayString = "${aimall.internal-api.nonce-cleanup-ms:60000}")
    public void cleanup() {
        nonceMapper.deleteExpired();
    }
}
