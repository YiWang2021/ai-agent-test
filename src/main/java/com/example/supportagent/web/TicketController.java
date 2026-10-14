package com.example.supportagent.web;

import com.example.supportagent.domain.DeviceRepository;
import com.example.supportagent.domain.SupportTicketRepository;
import com.example.supportagent.security.ApiKeyInterceptor;
import com.example.supportagent.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.Instant;
import java.util.List;

@RestController
public class TicketController {
    private final DeviceRepository devices;
    private final SupportTicketRepository tickets;

    public TicketController(DeviceRepository devices, SupportTicketRepository tickets) {
        this.devices = devices;
        this.tickets = tickets;
    }

    @GetMapping("/api/devices/{deviceId}/tickets")
    public List<TicketSummary> list(@PathVariable String deviceId,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    HttpServletRequest request) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("page must be >= 0; size must be 1..100");
        var identity = (RequestIdentity) request.getAttribute(ApiKeyInterceptor.IDENTITY_ATTRIBUTE);
        devices.findByIdAndTenantIdAndOwnerId(deviceId, identity.tenantId(), identity.userId())
                .orElseThrow(() -> new ForbiddenException("设备不存在或不属于当前用户"));
        return tickets.findByTenantIdAndOwnerIdAndDeviceIdOrderByCreatedAtDescIdDesc(
                        identity.tenantId(), identity.userId(), deviceId, PageRequest.of(page, size))
                .stream().map(t -> new TicketSummary(t.getId(), t.getDeviceId(), t.getIssue(),
                        t.getStatus(), t.getCreatedAt())).toList();
    }

    public record TicketSummary(String id, String deviceId, String issue, String status, Instant createdAt) {}
}
