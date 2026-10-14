package com.example.supportagent.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import org.slf4j.MDC;

@SpringBootTest
@AutoConfigureMockMvc
class ChatControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void rejectsMissingApiKey() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"c1\",\"message\":\"E03是什么\",\"confirmWrite\":false}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void answersGroundedQuestionWithCitation() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .header("X-API-Key", "demo-key")
                        .header("X-Tenant-Id", "tenant-a")
                        .header("X-User-Id", "user-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"c1\",\"message\":\"E03是什么\",\"confirmWrite\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("主刷")))
                .andExpect(jsonPath("$.citations[0].source").value("manual/error-codes.md"));
    }

    @Test
    void confirmedWriteWithoutIdempotencyKeyIsRejected() throws Exception {
        var result = mockMvc.perform(post("/api/chat")
                        .header("X-API-Key", "demo-key")
                        .header("X-Tenant-Id", "tenant-a")
                        .header("X-User-Id", "user-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"c2\",\"message\":\"创建工单\",\"confirmWrite\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andReturn();
        String traceId = result.getResponse().getHeader("X-Trace-Id");
        assertThat(traceId).isNotBlank();
        assertThat(result.getResponse().getContentAsString()).contains(traceId);
        assertThat(MDC.get("traceId")).isNull();
    }
}
