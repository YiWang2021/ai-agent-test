package com.example.supportagent.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "devices")
public class Device {

    @Id
    private String id;
    private String tenantId;
    private String ownerId;
    private String model;
    private String status;
    private int batteryLevel;
    private int brushLifePercent;
    private String errorCode;
    private Instant lastSeenAt;

    protected Device() {}

    public Device(String id, String tenantId, String ownerId, String model, String status,
                  int batteryLevel, int brushLifePercent, String errorCode, Instant lastSeenAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.ownerId = ownerId;
        this.model = model;
        this.status = status;
        this.batteryLevel = batteryLevel;
        this.brushLifePercent = brushLifePercent;
        this.errorCode = errorCode;
        this.lastSeenAt = lastSeenAt;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getOwnerId() { return ownerId; }
    public String getModel() { return model; }
    public String getStatus() { return status; }
    public int getBatteryLevel() { return batteryLevel; }
    public int getBrushLifePercent() { return brushLifePercent; }
    public String getErrorCode() { return errorCode; }
    public Instant getLastSeenAt() { return lastSeenAt; }
}
