package com.example.supportagent.web;

import com.example.supportagent.concurrency.AdmissionController;
import com.example.supportagent.security.RequestIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
        "support.concurrency.user-limit=1",
        "spring.datasource.url=jdbc:h2:mem:concurrency_http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class ConcurrencyHttpTest {
    @Autowired MockMvc mvc;
    @Autowired AdmissionController admission;
    @Test void malformedRequestIsClientErrorNotInternalFailure() throws Exception {
        mvc.perform(post("/api/chat").header("X-API-Key","demo-key").contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"missing-field\",\"message\":\"test\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
    @Test void syncAndSseRejectBeforeStartingModelAndRecoverAfterRelease() throws Exception {
        try(var occupied=admission.acquire(new RequestIdentity("tenant-a","user-001","USER"))) {
            for(String path:new String[]{"/api/chat","/api/chat/stream"}) {
                mvc.perform(post(path).header("X-API-Key","demo-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"new-session\",\"message\":\"E03怎么处理\",\"confirmWrite\":false}"))
                        .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","5"))
                        .andExpect(jsonPath("$.code").value("CAPACITY_EXCEEDED"));
            }
        }
        mvc.perform(post("/api/chat").header("X-API-Key","demo-key").contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"recovered\",\"message\":\"E03怎么处理\",\"confirmWrite\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.citations[0]").exists());
    }
    @Test void successfulSseReleasesItsPermit() throws Exception {
        var response=mvc.perform(post("/api/chat/stream").header("X-API-Key","demo-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"stream-release\",\"message\":\"E03怎么处理\",\"confirmWrite\":false}"))
                .andExpect(request().asyncStarted()).andReturn();
        response.getAsyncResult(10000);
        mvc.perform(asyncDispatch(response)).andExpect(status().isOk());
        try(var recovered=admission.acquire(new RequestIdentity("tenant-a","user-001","USER"))) {
            recovered.checkValid();
        }
    }
}


