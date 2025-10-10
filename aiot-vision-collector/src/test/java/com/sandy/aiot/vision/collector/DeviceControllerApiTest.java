package com.sandy.aiot.vision.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandy.aiot.vision.collector.controller.DataController.DeviceAddReq;
import com.sandy.aiot.vision.collector.controller.DataController.TagAddReq;
import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.repository.DeviceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeviceControllerApiTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired DeviceRepository deviceRepository;

    @Test
    void addDeviceAndTagViaApi() throws Exception {
        DeviceAddReq req = new DeviceAddReq();
        req.setName("DevApi");
        req.setProtocol("opcua");
        req.setConnectionString("opc.tcp://localhost:4840");
        String json = objectMapper.writeValueAsString(req);
        String resp = mockMvc.perform(post("/data/api/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        long deviceId = objectMapper.readTree(resp).get("id").asLong();

        TagAddReq tagReq = new TagAddReq();
        tagReq.setName("TagX");
        tagReq.setAddress("ns=2;s=TagX");
        mockMvc.perform(post("/data/api/"+deviceId+"/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tagReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.id", notNullValue()));

        mockMvc.perform(get("/data/api/"+deviceId+"/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("TagX")));
    }
}

