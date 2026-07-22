package com.aimall.server.service.impl;

import com.aimall.server.entity.OutboxEvent;
import com.aimall.server.mapper.OutboxEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class OutboxClaimService {
    private final OutboxEventMapper mapper;

    public OutboxClaimService(OutboxEventMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public List<OutboxEvent> claim(String owner, int limit, int leaseSeconds) {
        List<OutboxEvent> claimed = new ArrayList<>();
        LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(leaseSeconds);
        for (OutboxEvent candidate : mapper.listClaimCandidatesForUpdate(limit)) {
            if (mapper.claim(candidate.getId(), owner, leaseUntil) == 1) {
                claimed.add(candidate);
            }
        }
        return List.copyOf(claimed);
    }
}
