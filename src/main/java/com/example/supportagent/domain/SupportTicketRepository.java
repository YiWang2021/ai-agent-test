package com.example.supportagent.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, String> {
    List<SupportTicket> findByTenantIdAndOwnerIdAndDeviceIdOrderByCreatedAtDescIdDesc(
            String tenantId, String ownerId, String deviceId, org.springframework.data.domain.Pageable pageable);
    Optional<SupportTicket> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);
    List<SupportTicket> findByTenantIdAndOwnerIdAndDeviceIdAndStatusOrderByCreatedAtDesc(
            String tenantId, String ownerId, String deviceId, String status);
}
