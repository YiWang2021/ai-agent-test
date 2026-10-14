package com.example.supportagent.tool;

import com.example.supportagent.domain.SupportTicket;
import com.example.supportagent.domain.SupportTicketRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class TicketService {

    private final SupportTicketRepository repository;
    private final TicketWriter writer;

    public TicketService(SupportTicketRepository repository, TicketWriter writer) {
        this.repository = repository;
        this.writer = writer;
    }

    public SupportTicket createIdempotently(String tenantId, String ownerId, String deviceId,
                                             String issue, String idempotencyKey) {
        return repository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey)
                .map(ticket -> validateReplay(ticket, ownerId, deviceId, issue))
                .orElseGet(() -> insertOrReadWinner(tenantId, ownerId, deviceId, issue, idempotencyKey));
    }

    private SupportTicket insertOrReadWinner(String tenantId, String ownerId, String deviceId,
                                             String issue, String idempotencyKey) {
        try {
            return writer.insert(tenantId, ownerId, deviceId, issue, idempotencyKey);
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            return repository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey)
                    .map(ticket -> validateReplay(ticket, ownerId, deviceId, issue))
                    .orElseThrow(() -> concurrentDuplicate);
        }
    }

    private SupportTicket validateReplay(SupportTicket ticket, String ownerId, String deviceId, String issue) {
        if (!ticket.getOwnerId().equals(ownerId)) throw new ToolAccessDeniedException("设备不存在或不属于当前用户");
        if (!ticket.getDeviceId().equals(deviceId) || !ticket.getIssue().equals(issue.trim())) {
            throw new IdempotencyConflictException();
        }
        return ticket;
    }
}
