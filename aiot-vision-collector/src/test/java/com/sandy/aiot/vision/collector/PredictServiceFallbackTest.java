package com.sandy.aiot.vision.collector;

import com.sandy.aiot.vision.collector.entity.DataRecord;
import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.repository.DeviceRepository;
import com.sandy.aiot.vision.collector.service.DataStorageService;
import com.sandy.aiot.vision.collector.service.PredictService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class PredictServiceFallbackTest {
    @Autowired PredictService predictService;
    @Autowired DataStorageService dataStorageService;
    @Autowired DeviceRepository deviceRepository;

    @Test
    void predictFallsBackAndReturnsConfiguredLength() {
        Device d = Device.builder().name("PDev").protocol("opcua").connectionString("opc.tcp://localhost:1").build();
        d = deviceRepository.save(d);
        Long tagId = 1L; // Virtual tag id not persisted (Predict service only uses data storage records + device)
        List<DataRecord> records = new ArrayList<>();
        for (int i=0;i<20;i++) {
            records.add(DataRecord.builder().deviceId(d.getId()).tagId(tagId).value(i).timestamp(LocalDateTime.now().minusMinutes(20-i)).build());
        }
        dataStorageService.save(records);
        var vo = predictService.predict(d.getId(), tagId);
        assertNotNull(vo);
        assertEquals(5, vo.getPredictionPoints().size(), "Should produce fallback predictions of configured length 5");
    }
}

