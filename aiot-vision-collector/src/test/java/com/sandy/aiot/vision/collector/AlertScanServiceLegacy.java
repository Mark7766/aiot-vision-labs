package com.sandy.aiot.vision.collector;

import com.sandy.aiot.vision.collector.entity.Alert;
import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.entity.Tag;
import com.sandy.aiot.vision.collector.entity.DataRecord;
import com.sandy.aiot.vision.collector.repository.AlertRepository;
import com.sandy.aiot.vision.collector.repository.DeviceRepository;
import com.sandy.aiot.vision.collector.repository.TagRepository;
import com.sandy.aiot.vision.collector.service.DataStorageService;
import com.sandy.aiot.vision.collector.service.PredictService;
import com.sandy.aiot.vision.collector.service.impl.AlertScanService;
import com.sandy.aiot.vision.collector.vo.TimeSeriesDataModelVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "alert.prediction-enabled=true",
        "alert.deviation-percent-threshold=15",
        "alert.severity-high-percent=20"
})
public class AlertScanServiceTest {

    @Autowired DeviceRepository deviceRepository;
    @Autowired TagRepository tagRepository;
    @Autowired DataStorageService dataStorageService;
    @Autowired AlertScanService alertScanService;
    @Autowired AlertRepository alertRepository;
    @Autowired PredictService predictService;

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
    void deviationAlertGeneratedWhenActualDeviatesFromPrediction() {
        LocalDateTime ts = LocalDateTime.now().withNano(0);
        // Store one actual data point
        dataStorageService.save(List.of(DataRecord.builder()
                .deviceId(device.getId())
                .tagId(tag.getId())
                .value(100f)
                .timestamp(ts)
                .build()));

        // Mock prediction result: predicted 50 at the same timestamp => 100% deviation
        TimeSeriesDataModelVO vo = new TimeSeriesDataModelVO();
        vo.setTimestamps(List.of(ts.minusMinutes(1), ts));
        TimeSeriesDataModelVO.PredictionPoint pp = TimeSeriesDataModelVO.PredictionPoint.builder()
                .timestamp(ts)
                .value(50d)
                .build();
        vo.setPredictionPoints(List.of(pp));
        when(predictService.predict(device.getId(), tag.getId())).thenReturn(vo);

        alertScanService.scanOnce();

        List<Alert> alerts = alertRepository.findAll();
        assertEquals(1, alerts.size(), "One deviation alert expected");
        Alert a = alerts.get(0);
        assertEquals("DEVIATION", a.getType());
        assertEquals("HIGH", a.getSeverity()); // 100% deviation > 20%
        assertNotNull(a.getDeviationPercent());
        assertTrue(Math.abs(a.getDeviationPercent() - 100.0) < 0.0001, "Deviation percent should be 100%");
        assertTrue(a.getMessage().contains("采集时间"));
        assertTrue(a.getMessage().contains("预警时间"));
    }
}
