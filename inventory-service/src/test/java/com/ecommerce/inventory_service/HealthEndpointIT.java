package com.ecommerce.inventory_service;

import com.ecommerce.inventory_service.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the Docker healthcheck from Task 2: /actuator/health must stay reachable without a token
 * and report UP against the real database. (A @WebMvcTest slice has no actuator endpoints, so this
 * runs on the full context.)
 */
@AutoConfigureMockMvc
class HealthEndpointIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void health_isPublic_andUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                // The healthcheck only looks at the status code (curl -f). The body is matched loosely because
                // GlobalResponseHandler currently wraps actuator responses in ApiResponse too.
                .andExpect(content().string(containsString("\"status\":\"UP\"")));
    }
}
