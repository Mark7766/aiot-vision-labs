package com.sandy.aiot.vision.collector.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertBoardViewControllerTest {
    @Autowired MockMvc mockMvc;

    @Test
    void boardPageRenders() throws Exception {
        mockMvc.perform(get("/alerts/board"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("预警监控大屏")));
    }
}

