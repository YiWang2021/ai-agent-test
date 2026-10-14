package com.example.supportagent.tool;

import com.example.supportagent.domain.SupportTicket;
import com.example.supportagent.domain.SupportTicketRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TicketWriter {

    private final SupportTicketRepository repository;

    public TicketWriter(SupportTicketRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SupportTicket insert(String tenantId, String ownerId, String deviceId,
                                String issue, String idempotencyKey) {
        return repository.saveAndFlush(SupportTicket.create(tenantId, ownerId, deviceId, issue, idempotencyKey));
    }
}
