package com.example.supportagent.web;

import com.example.supportagent.domain.SupportTicket;
import com.example.supportagent.domain.SupportTicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@SpringBootTest(properties = {
        "SUPPORT_OTHER_USER_API_KEY=workspace-other-key",
        "spring.datasource.url=jdbc:h2:mem:workspace_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@Transactional
class DeviceWorkspaceTest {
    @Autowired MockMvc mvc;
    @Autowired SupportTicketRepository tickets;

    @Test void devicesAreScopedAndDoNotExposeInternalIdentity() throws Exception {
        mvc.perform(get("/api/devices").header("X-API-Key", "demo-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value("device-001"))
                .andExpect(jsonPath("$[1].id").value("device-002"))
                .andExpect(jsonPath("$[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$[0].ownerId").doesNotExist());
        mvc.perform(get("/api/devices").header("X-API-Key", "workspace-other-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("device-900"));
    }

    @Test void missingCredentialsAndForgedIdentityAreRejected() throws Exception {
        mvc.perform(get("/api/devices")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/devices/device-001/tickets")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/devices").header("X-API-Key", "demo-key").header("X-User-Id", "user-900"))
                .andExpect(status().isForbidden());
    }

    @Test void ticketsRequireDeviceOwnershipEvenWhenEmpty() throws Exception {
        for (String id : new String[]{"device-900", "missing"}) {
            mvc.perform(get("/api/devices/" + id + "/tickets").header("X-API-Key", "demo-key"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/devices/device-002/tickets").header("X-API-Key", "demo-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test void ticketHistoryIsPaginatedScopedAndRedacted() throws Exception {
        var first = tickets.save(SupportTicket.create("tenant-a", "user-001", "device-001", "first", "workspace-1"));
        var second = tickets.save(SupportTicket.create("tenant-a", "user-001", "device-001", "second", "workspace-2"));
        tickets.save(SupportTicket.create("tenant-a", "another-user", "device-001", "hidden", "workspace-3"));
        tickets.save(SupportTicket.create("tenant-b", "user-001", "device-001", "hidden", "workspace-4"));
        // The same timestamp is valid; the endpoint has a stable ID tie-breaker.
        var sorted = java.util.stream.Stream.of(first, second).sorted(java.util.Comparator
                .comparing(SupportTicket::getCreatedAt).thenComparing(SupportTicket::getId).reversed()).toList();
        for (int page = 0; page < 2; page++) {
            mvc.perform(get("/api/devices/device-001/tickets").param("size", "1").param("page", "" + page)
                            .header("X-API-Key", "demo-key"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].id").value(sorted.get(page).getId()))
                    .andExpect(jsonPath("$[0].idempotencyKey").doesNotExist())
                    .andExpect(jsonPath("$[0].ownerId").doesNotExist());
        }
        mvc.perform(get("/api/devices/device-001/tickets").param("page", "2").param("size", "1")
                        .header("X-API-Key", "demo-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test void invalidPageBoundsAreRejected() throws Exception {
        for (String[] param : new String[][]{{"page", "-1"}, {"size", "0"}, {"size", "101"}}) {
            mvc.perform(get("/api/devices/device-001/tickets").param(param[0], param[1]).header("X-API-Key", "demo-key"))
                    .andExpect(status().isBadRequest());
        }
    }
}
