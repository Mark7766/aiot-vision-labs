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
        "alert.deviation-percent-threshold=10",
        "alert.duplicate-suppress-minutes=30" // longer window for test clarity
})
public class AlertDuplicateSuppressionTest {

    @Autowired DeviceRepository deviceRepository;
    @Autowired TagRepository tagRepository;
    @Autowired DataStorageService dataStorageService;
    @Autowired AlertScanService alertScanService;
    @Autowired AlertRepository alertRepository;
    @Autowired PredictionCacheService predictionCacheService;

    Device device;
    Tag tag;

    @BeforeEach
    void setup() {
        alertRepository.deleteAll();
        tagRepository.deleteAll();
        deviceRepository.deleteAll();
        device = deviceRepository.save(Device.builder().name("Dev-Dup").protocol("opcua").connectionString("opc.tcp://x:123").build());
        tag = tagRepository.save(Tag.builder().name("Speed").address("ns=2;s:v1").device(device).build());
    }

    private void storeActualAndPrediction(double actual, double predicted, LocalDateTime ts) {
        dataStorageService.save(List.of(DataRecord.builder()
                .deviceId(device.getId())
                .tagId(tag.getId())
                .value(actual)
                .timestamp(ts)
                .build()));
        predictionCacheService.putPredictionPoint(device.getId(), tag.getId(), ts, predicted);
    }

    @Test
    void unackUnignoredAlertSuppressesDuplicate() {
        LocalDateTime ts = LocalDateTime.now().withNano(0);
        storeActualAndPrediction(200, 100, ts); // 100% deviation
        alertScanService.scanOnce();
        List<Alert> alerts1 = alertRepository.findAll();
        assertEquals(1, alerts1.size());
        // second scan should be suppressed
        alertScanService.scanOnce();
        List<Alert> alerts2 = alertRepository.findAll();
        assertEquals(1, alerts2.size(), "Second alert suppressed while first still pending");
    }

    @Test
    void acknowledgedAlertDoesNotSuppressNew() {
        LocalDateTime ts = LocalDateTime.now().withNano(0);
        storeActualAndPrediction(150, 100, ts); // 50% deviation
        alertScanService.scanOnce();
        Alert first = alertRepository.findAll().get(0);
        first.setAcknowledged(true);
        first.setAcknowledgedAt(LocalDateTime.now());
        alertRepository.save(first);
        // new actual at later timestamp should create another
        LocalDateTime ts2 = ts.plusMinutes(1);
        storeActualAndPrediction(160, 100, ts2);
        alertScanService.scanOnce();
        List<Alert> all = alertRepository.findAll();
        assertEquals(2, all.size(), "Acknowledged alert should not suppress new one");
    }

    @Test
    void ignoredAlertDoesNotSuppressNew() {
        LocalDateTime ts = LocalDateTime.now().withNano(0);
        storeActualAndPrediction(180, 100, ts); // 80% deviation
        alertScanService.scanOnce();
        Alert first = alertRepository.findAll().get(0);
        first.setIgnored(true);
        first.setIgnoredAt(LocalDateTime.now());
        alertRepository.save(first);
        // new actual triggers another
        LocalDateTime ts2 = ts.plusMinutes(2);
        storeActualAndPrediction(190, 100, ts2);
        alertScanService.scanOnce();
        List<Alert> all = alertRepository.findAll();
        assertEquals(2, all.size(), "Ignored alert should not suppress new one");
    }
}

