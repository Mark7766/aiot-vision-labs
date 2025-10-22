package com.sandy.aiot.vision.collector;

import com.sandy.aiot.vision.collector.entity.Alert;
import com.sandy.aiot.vision.collector.entity.DataRecord;
import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.entity.Tag;
import com.sandy.aiot.vision.collector.repository.AlertRepository;
import com.sandy.aiot.vision.collector.repository.DeviceRepository;
import com.sandy.aiot.vision.collector.repository.TagRepository;
import com.sandy.aiot.vision.collector.service.DataStorageService;
import com.sandy.aiot.vision.collector.service.PredictionCacheService;
import com.sandy.aiot.vision.collector.service.impl.AlertScanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "alert.prediction-enabled=true",
        "alert.deviation-percent-threshold=15",
        "alert.severity-high-percent=20"
})
public class CacheBasedAlertScanServiceTest {

    @Autowired DeviceRepository deviceRepository;
    @Autowired TagRepository tagRepository;
    @Autowired DataStorageService dataStorageService;
    @Autowired AlertScanService alertScanService;
    @Autowired AlertRepository alertRepository;
    @Autowired PredictionCacheService predictionCacheService;

    Device device;
    Tag tag;

    @BeforeEach
    void init() {
        alertRepository.deleteAll();
        tagRepository.deleteAll();
        deviceRepository.deleteAll();
        device = deviceRepository.save(Device.builder().name("Dev-A").protocol("opcua").connectionString("opc.tcp://x:123").build());
        tag = tagRepository.save(Tag.builder().name("Temp").address("ns=2;s=t1").device(device).build());
    }

    @Test
    void deviationAlertGeneratedWhenActualDeviatesFromCachedPrediction() {
        LocalDateTime ts = LocalDateTime.now().withNano(0);
        // 保存实际数据点 (100)
        dataStorageService.save(List.of(DataRecord.builder()
                .deviceId(device.getId())
                .tagId(tag.getId())
                .value(100f)
                .timestamp(ts)
                .build()));
        // 缓存预测点 (50) -> 偏差 100%
        predictionCacheService.putPredictionPoint(device.getId(), tag.getId(), ts, 50d);
        // 执行扫描
        alertScanService.scanOnce();
        // 验证
        List<Alert> alerts = alertRepository.findAll();
        assertEquals(1, alerts.size());
        Alert a = alerts.get(0);
        assertEquals("DEVIATION", a.getType());
        assertEquals("HIGH", a.getSeverity());
        assertNotNull(a.getDeviationPercent());
        assertTrue(Math.abs(a.getDeviationPercent() - 100.0) < 0.0001);
    }

    @Test
    void predictionToleranceMatchGeneratesAlert() {
        LocalDateTime base = LocalDateTime.now().withNano(0);
        // 实际点时间略晚于预测点 (容差内)
        LocalDateTime predictedTs = base;
        LocalDateTime actualTs = base.plusSeconds(20); // 20s 偏移, 容差默认 30s
        dataStorageService.save(List.of(DataRecord.builder()
                .deviceId(device.getId())
                .tagId(tag.getId())
                .value(200f)
                .timestamp(actualTs)
                .build()));
        predictionCacheService.putPredictionPoint(device.getId(), tag.getId(), predictedTs, 100d); // 100% 偏差
        alertScanService.scanOnce();
        List<Alert> alerts = alertRepository.findAll();
        assertEquals(1, alerts.size());
        assertEquals("DEVIATION", alerts.get(0).getType());
        assertTrue(alerts.get(0).getDeviationPercent() > 99.9);
    }
}

