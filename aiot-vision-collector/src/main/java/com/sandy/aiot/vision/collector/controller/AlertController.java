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
import java.util.*; // added Map/LinkedHashMap import
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

    // New stats endpoint for big screen monitoring
    @GetMapping("/stats")
    public Stats stats() {
        Stats s = new Stats();
        List<Alert> active = alertRepository.findByAcknowledgedFalseAndIgnoredFalseOrderByCreatedAtDesc();
        s.setActiveCount(active.size());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime dayAgo = now.minusHours(24);
        List<Alert> recent = alertRepository.findByCreatedAtAfterOrderByCreatedAtAsc(dayAgo);
        s.setRecent24hCount(recent.size());
        s.setSeverityActive(countBySeverity(active));
        s.setSeverityRecent24h(countBySeverity(recent));
        // Hour buckets (last 12 hours including current hour)
        Map<String, Integer> hourMap = new LinkedHashMap<>();
        for (int i = 11; i >= 0; i--) {
            LocalDateTime start = now.minusHours(i).withMinute(0).withSecond(0).withNano(0);
            String label = String.format("%02d:00", start.getHour());
            hourMap.put(label, 0);
        }
        for (Alert a : recent) {
            LocalDateTime ts = a.getCreatedAt();
            if (ts == null) continue;
            if (ts.isBefore(now.minusHours(12))) continue;
            String label = String.format("%02d:00", ts.getHour());
            hourMap.computeIfPresent(label, (k, v) -> v + 1);
        }
        List<HourStat> hourStats = hourMap.entrySet().stream().map(e -> {
            HourStat h = new HourStat();
            h.setHour(e.getKey());
            h.setCount(e.getValue());
            return h;
        }).collect(Collectors.toList());
        s.setHourStats(hourStats);
        return s;
    }

    private Map<String, Integer> countBySeverity(List<Alert> alerts) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Alert a : alerts) {
            String sev = Optional.ofNullable(a.getSeverity()).orElse("UNKNOWN").toUpperCase();
            map.put(sev, map.getOrDefault(sev, 0) + 1);
        }
        return map;
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

    // New DTOs
    @Data
    public static class HourStat { private String hour; private int count; }

    @Data
    public static class Stats {
        private int activeCount;
        private int recent24hCount;
        private Map<String,Integer> severityActive;
        private Map<String,Integer> severityRecent24h;
        private List<HourStat> hourStats;
    }

    @PostMapping("/batch-ack")
    public ResponseEntity<BatchActionResp> batchAcknowledge(@RequestBody BatchActionReq req) {
        List<Long> ids = req.getIds();
        List<Long> failed = new ArrayList<>();
        int success = 0;
        for (Long id : ids) {
            Optional<Alert> opt = alertRepository.findById(id);
            if (opt.isEmpty()) { failed.add(id); continue; }
            Alert a = opt.get();
            if (!a.isAcknowledged()) {
                a.setAcknowledged(true);
                a.setAcknowledgedAt(LocalDateTime.now());
                alertRepository.save(a);
                success++;
            } else {
                // already acknowledged, treat as success
                success++;
            }
        }
        BatchActionResp resp = new BatchActionResp();
        resp.setSuccessCount(success);
        resp.setFailedIds(failed);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/batch-ignore")
    public ResponseEntity<BatchActionResp> batchIgnore(@RequestBody BatchActionReq req) {
        List<Long> ids = req.getIds();
        List<Long> failed = new ArrayList<>();
        int success = 0;
        for (Long id : ids) {
            Optional<Alert> opt = alertRepository.findById(id);
            if (opt.isEmpty()) { failed.add(id); continue; }
            Alert a = opt.get();
            if (!a.isIgnored()) {
                a.setIgnored(true);
                a.setIgnoredAt(LocalDateTime.now());
                alertRepository.save(a);
                success++;
            } else {
                // already ignored, treat as success
                success++;
            }
        }
        BatchActionResp resp = new BatchActionResp();
        resp.setSuccessCount(success);
        resp.setFailedIds(failed);
        return ResponseEntity.ok(resp);
    }

    @Data
    public static class BatchActionReq {
        private List<Long> ids;
    }

    @Data
    public static class BatchActionResp {
        private int successCount;
        private List<Long> failedIds;
    }
}
