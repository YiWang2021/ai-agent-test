package com.example.supportagent.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"support.evaluation.enabled=false","support.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:disabled_eval;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class DisabledEvaluationTest {
    @Autowired MockMvc mvc;
    @Test void evaluationRouteIsUnavailableWhenDisabled() throws Exception {
        mvc.perform(post("/api/admin/evaluations/run").header("X-API-Key","demo-key"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
