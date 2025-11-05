package com.sandy.aiot.vision.collector;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertStatsApiTest {
    @Autowired MockMvc mockMvc;

    @Test
    void statsEndpointReturnsStructure() throws Exception {
        mockMvc.perform(get("/data/api/alerts/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCount", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.recent24hCount", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.hourStats", hasSize(12)))
                .andExpect(jsonPath("$.hourStats[0].hour", matchesRegex("\\d{2}:00")))
                .andExpect(jsonPath("$.severityActive", notNullValue()))
                .andExpect(jsonPath("$.severityRecent24h", notNullValue()));
    }
}

