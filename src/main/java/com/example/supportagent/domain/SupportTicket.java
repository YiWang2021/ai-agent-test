package com.example.supportagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "support_tickets", uniqueConstraints = {
        @UniqueConstraint(name = "uk_ticket_tenant_idempotency", columnNames = {"tenant_id", "idempotency_key"})
})
public class SupportTicket {

    @Id
    private String id;
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;
    @Column(name = "owner_id", nullable = false)
    private String ownerId;
    @Column(name = "device_id", nullable = false)
    private String deviceId;
    @Column(nullable = false, length = 1000)
    private String issue;
    @Column(nullable = false)
    private String status;
    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SupportTicket() {}

    public static SupportTicket create(String tenantId, String ownerId, String deviceId,
                                       String issue, String idempotencyKey) {
        SupportTicket ticket = new SupportTicket();
        ticket.id = UUID.randomUUID().toString();
        ticket.tenantId = tenantId;
        ticket.ownerId = ownerId;
        ticket.deviceId = deviceId;
        ticket.issue = issue;
        ticket.status = "OPEN";
        ticket.idempotencyKey = idempotencyKey;
        ticket.createdAt = Instant.now();
        return ticket;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getOwnerId() { return ownerId; }
    public String getDeviceId() { return deviceId; }
    public String getIssue() { return issue; }
    public String getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
}
