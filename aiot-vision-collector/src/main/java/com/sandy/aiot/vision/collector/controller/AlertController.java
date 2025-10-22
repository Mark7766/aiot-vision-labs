package com.sandy.aiot.vision.collector.controller;

import com.sandy.aiot.vision.collector.entity.Alert;
import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.entity.Tag;
import com.sandy.aiot.vision.collector.repository.AlertRepository;
import com.sandy.aiot.vision.collector.repository.DeviceRepository;
import com.sandy.aiot.vision.collector.repository.TagRepository;
import com.sandy.aiot.vision.collector.service.impl.AlertScanService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * REST endpoints for alert listing & user actions (acknowledge / ignore).
 * MVP only provides basic operations.
 */
@RestController
@RequestMapping("/data/api/alerts")
@RequiredArgsConstructor
@Slf4j
public class AlertController {

    private final AlertRepository alertRepository;
    private final AlertScanService alertScanService;
    private final DeviceRepository deviceRepository;
    private final TagRepository tagRepository;

    @org.springframework.beans.factory.annotation.Value("${alert.enabled:true}")
    private boolean alertEnabled;

    @GetMapping
    public List<AlertItem> listActive() {
        return alertRepository.findByAcknowledgedFalseAndIgnoredFalseOrderByCreatedAtDesc()
                .stream().map(this::toItem).collect(Collectors.toList());
    }

    @GetMapping("/recent")
    public List<AlertItem> listRecent() {
        return alertRepository.findTop50ByOrderByCreatedAtDesc().stream().map(this::toItem).collect(Collectors.toList());
    }

    @PostMapping("/{id}/ack")
    public ResponseEntity<ActionResp> acknowledge(@PathVariable Long id) {
        Optional<Alert> opt = alertRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.ok(ActionResp.fail("Alert not found"));
        Alert a = opt.get();
        if (!a.isAcknowledged()) {
            a.setAcknowledged(true);
            a.setAcknowledgedAt(LocalDateTime.now());
            alertRepository.save(a);
        }
        return ResponseEntity.ok(ActionResp.ok());
    }

    @PostMapping("/{id}/ignore")
    public ResponseEntity<ActionResp> ignore(@PathVariable Long id) {
        Optional<Alert> opt = alertRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.ok(ActionResp.fail("Alert not found"));
        Alert a = opt.get();
        if (!a.isIgnored()) {
            a.setIgnored(true);
            a.setIgnoredAt(LocalDateTime.now());
            alertRepository.save(a);
        }
        return ResponseEntity.ok(ActionResp.ok());
    }

    @PostMapping("/scan")
    public ResponseEntity<ActionResp> manualScan() {
        try {
            alertScanService.scanOnce();
            return ResponseEntity.ok(ActionResp.ok());
        } catch (Exception e) {
            return ResponseEntity.ok(ActionResp.fail(e.getMessage()));
        }
    }

    @GetMapping("/meta")
    public Meta meta() {
        Meta m = new Meta();
        m.setEnabled(alertEnabled); // use injected property instead of service accessor
        m.setActiveCount(alertRepository.findByAcknowledgedFalseAndIgnoredFalseOrderByCreatedAtDesc().size());
        return m;
    }

    private AlertItem toItem(Alert a) {
        AlertItem it = new AlertItem();
        it.setId(a.getId());
        it.setDeviceId(a.getDeviceId());
        it.setTagId(a.getTagId());
        it.setType(a.getType());
        it.setSeverity(a.getSeverity());
        it.setMessage(a.getMessage());
        it.setCreatedAt(a.getCreatedAt());
        it.setThresholdValue(a.getThresholdValue());
        it.setActualValue(a.getActualValue());
        it.setPredictedPeakValue(a.getPredictedPeakValue());
        it.setPredictedBaseValue(a.getPredictedBaseValue());
        it.setDeviationPercent(a.getDeviationPercent());
        it.setAcknowledged(a.isAcknowledged());
        it.setIgnored(a.isIgnored());
        // added enrichment (best-effort, null if missing)
        if (a.getDeviceId() != null) {
            try { it.setDeviceName(deviceRepository.findById(a.getDeviceId()).map(Device::getName).orElse(null)); } catch (Exception ignored) { }
        }
        if (a.getTagId() != null) {
            try { it.setTagName(tagRepository.findById(a.getTagId()).map(Tag::getName).orElse(null)); } catch (Exception ignored) { }
        }
        return it;
    }

    @Data
    public static class AlertItem {
        private Long id;
        private Long deviceId;
        private Long tagId;
        private String type;
        private String severity;
        private String message;
        private LocalDateTime createdAt;
        private Double thresholdValue;
        private Double actualValue;
        private Double predictedPeakValue;
        private Double predictedBaseValue;
        private Double deviationPercent;
        private boolean acknowledged;
        private boolean ignored;
        // added fields
        private String deviceName;
        private String tagName;
    }

    @Data
    public static class ActionResp {
        private boolean success;
        private String message;
        public static ActionResp ok() { ActionResp r = new ActionResp(); r.success = true; return r; }
        public static ActionResp fail(String msg) { ActionResp r = new ActionResp(); r.success = false; r.message = msg; return r; }
    }

    @Data
    public static class Meta {
        private boolean enabled;
        private int activeCount;
    }
}
