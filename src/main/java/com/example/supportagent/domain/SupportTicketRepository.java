package com.example.supportagent.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, String> {
    Optional<SupportTicket> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);
    List<SupportTicket> findByTenantIdAndOwnerIdAndDeviceIdAndStatusOrderByCreatedAtDesc(
            String tenantId, String ownerId, String deviceId, String status);
}
