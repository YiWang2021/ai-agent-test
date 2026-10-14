package com.example.supportagent.web;

import com.example.supportagent.domain.DeviceRepository;
import com.example.supportagent.security.ApiKeyInterceptor;
import com.example.supportagent.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.Instant;
import java.util.List;

@RestController
public class DeviceController {
    private final DeviceRepository devices;

    public DeviceController(DeviceRepository devices) { this.devices = devices; }

    @GetMapping("/api/devices")
    public List<DeviceSummary> list(HttpServletRequest request) {
        var identity = (RequestIdentity) request.getAttribute(ApiKeyInterceptor.IDENTITY_ATTRIBUTE);
        return devices.findByTenantIdAndOwnerIdOrderById(identity.tenantId(), identity.userId()).stream()
                .map(d -> new DeviceSummary(d.getId(), d.getModel(), d.getStatus(), d.getBatteryLevel(),
                        d.getBrushLifePercent(), d.getErrorCode(), d.getLastSeenAt())).toList();
    }

    public record DeviceSummary(String id, String model, String status, int batteryLevel,
                                int brushLifePercent, String errorCode, Instant lastSeenAt) {}
}
