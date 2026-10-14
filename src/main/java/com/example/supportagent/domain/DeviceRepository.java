package com.example.supportagent.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, String> {
    java.util.List<Device> findByTenantIdAndOwnerIdOrderById(String tenantId, String ownerId);
    Optional<Device> findByIdAndTenantIdAndOwnerId(String id, String tenantId, String ownerId);
}
