package com.example.supportagent.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"SUPPORT_ADMIN_API_KEY=test-admin-key", "SUPPORT_OTHER_USER_API_KEY=test-other-key",
        "spring.datasource.url=jdbc:h2:mem:identity_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class IdentityIsolationTest {
    @Autowired MockMvc mvc;

    @Test void credentialDeterminesIdentityWithoutClientHeaders() throws Exception {
        mvc.perform(get("/api/session").header("X-API-Key", "demo-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value("user-001"))
                .andExpect(jsonPath("$.role").value("USER"));
    }
    @Test void changingTenantUserOrRoleHeadersCannotEscalate() throws Exception {
        for (String[] header : new String[][]{{"X-Tenant-Id", "tenant-b"}, {"X-User-Id", "user-900"}, {"X-Role", "ADMIN"}}) {
            mvc.perform(get("/api/session").header("X-API-Key", "demo-key").header(header[0], header[1]))
                    .andExpect(status().isForbidden());
        }
    }
    @Test void ordinaryCredentialCannotManageKnowledge() throws Exception {
        mvc.perform(post("/api/admin/knowledge/search").header("X-API-Key", "demo-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"E03\",\"rerank\":true}"))
                .andExpect(status().isForbidden());
    }
    @Test void adminCredentialCanInspectRetrievalAndOtherTenantHasOwnIdentity() throws Exception {
        mvc.perform(post("/api/admin/knowledge/search").header("X-API-Key", "test-admin-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"E03\",\"rerank\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rerankEnabled").value(true));
        mvc.perform(get("/api/session").header("X-API-Key", "test-other-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tenantId").value("tenant-b"));
    }
}
