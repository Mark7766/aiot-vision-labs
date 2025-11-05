package com.sandy.aiot.vision.collector.service.impl;

import com.sandy.aiot.vision.collector.entity.Alert;
import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.entity.Tag;
import com.sandy.aiot.vision.collector.entity.DataRecord;
import com.sandy.aiot.vision.collector.repository.AlertRepository;
import com.sandy.aiot.vision.collector.repository.DeviceRepository;
import com.sandy.aiot.vision.collector.repository.TagRepository;
import com.sandy.aiot.vision.collector.service.DataStorageService;
import com.sandy.aiot.vision.collector.service.PredictService; // kept for cache prefetch fallback
import com.sandy.aiot.vision.collector.service.PredictionCacheService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Scans latest numeric tag values and generates deviation alerts using cached prediction baseline.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AlertScanService {

    private final DeviceRepository deviceRepository;
    private final TagRepository tagRepository;
    private final DataStorageService dataStorageService;
    private final AlertRepository alertRepository;
    private final PredictService predictService; // retained for prediction cache's lazy prefetch
    private final PredictionCacheService predictionCacheService; // new cache service

    @Value("${alert.enabled:true}")
    private boolean enabled;
    @Value("${alert.duplicate-suppress-minutes:5}")
    private int duplicateSuppressMinutes;
    @Value("${alert.severity-high-percent:20}")
    private double severityHighPercent; // used for deviation severity classification
    @Value("${alert.prediction-enabled:false}")
    private boolean predictionEnabled; // toggle deviation logic
    @Value("${alert.deviation-percent-threshold:15}")
    private double deviationPercentThreshold;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @PostConstruct
    public void init() {
        log.info("Alert scanning service initialized: enabled={} deviationPctThr={} predictionEnabled={}", enabled, deviationPercentThreshold, predictionEnabled);
    }

    @Scheduled(fixedDelayString = "${alert.scan-interval-ms:60000}")
    public void scheduledScan() {
        if (!enabled) return;
        try { scanOnce(); } catch (Exception e) { log.error("Scheduled alert scan failed: {}", e.getMessage(), e); }
    }

    /**
     * Public entry point for tests / manual trigger.
     */
    public void scanOnce() {
        if (!enabled) return;
        List<Device> devices = deviceRepository.findAllWithTags();
        int totalChecked = 0;
        for (Device device : devices) {
            if (device.getTags() == null) continue;
            for (Tag tag : device.getTags()) {
                totalChecked++;
                if (predictionEnabled) {
                    generateDeviationAlertIfNeeded(device, tag);
                }
            }
        }
        if (totalChecked > 0) {
            log.debug("Alert scan completed. devices={} checkedTags={}", devices.size(), totalChecked);
        }
    }

    private void generateDeviationAlertIfNeeded(Device device, Tag tag) {
        try {
            Optional<DataRecord> latestOpt = dataStorageService.findLatest(device.getId(), tag.getId());
            if (latestOpt.isEmpty()) return;
            DataRecord dr = latestOpt.get();
            Double actual = toDouble(dr.getValue());
            if (actual == null || Double.isNaN(actual)) return;
            LocalDateTime actualTs = dr.getTimestamp();
            if (actualTs == null) return; // need timestamp for alignment

            // 从缓存获取对应时间点预测值 (内部容差匹配). 若未命中则缓存内部会尝试懒加载一次再返回.
            Double predicted = predictionCacheService.getPredictedValue(device.getId(), tag.getId(), actualTs);
            if (predicted == null || Double.isNaN(predicted)) {
                log.info("No cached prediction value for deviation alert deviceId={} tagId={} ts={}", device.getId(), tag.getId(), actualTs);
                return; // 无预测基线
            }

            Double deviationPctObj = computeDeviationPercent(actual, predicted);
            if (deviationPctObj == null) return; // 计算失败
            double deviationPct = deviationPctObj;
            if (Double.isNaN(deviationPct)) return;
            log.info("Deviation evaluation deviceId={} tagId={} actual={} predicted={} deviationPct={}% deviationPercentThreshold={}",
                    device.getId(), tag.getId(), actual, predicted, String.format("%.2f", deviationPct), deviationPercentThreshold);
            if (Math.abs(deviationPct) < deviationPercentThreshold) return; // 未达到阈值

            String signature = buildSignature(device.getId(), tag.getId(), "DEVIATION");
            LocalDateTime cutoff = LocalDateTime.now().minus(duplicateSuppressMinutes, ChronoUnit.MINUTES);
            // NEW suppression logic: only suppress if there exists an unacknowledged & unignored alert with same signature within window
            if (alertRepository.findTopBySignatureAndAcknowledgedFalseAndIgnoredFalseAndCreatedAtAfter(signature, cutoff).isPresent()) {
                log.info("Duplicate deviation alert suppressed (pending) deviceId={} tagId={} signature={} since {} (ack=false ignored=false)", device.getId(), tag.getId(), signature, TS_FMT.format(cutoff));
                return; // 重复抑制 (仅未确认且未忽略的仍在窗口内)
            }

            String severity = computeSeverityByPercent(Math.abs(deviationPct));
            LocalDateTime alertTime = LocalDateTime.now();
            String msg = String.format("偏差预警: 设备[%s] 点位[%s] 当前值 %.2f 预测值 %.2f 偏差 %.2f%% (阈值%.2f%%) 采集时间:%s 预警时间:%s",
                    device.getName(), tag.getName(), actual, predicted, deviationPct, deviationPercentThreshold,
                    TS_FMT.format(actualTs), TS_FMT.format(alertTime));
            Alert alert = Alert.builder()
                    .deviceId(device.getId())
                    .tagId(tag.getId())
                    .type("DEVIATION")
                    .severity(severity)
                    .message(msg)
                    .thresholdValue(null)
                    .actualValue(actual)
                    .predictedPeakValue(null)
                    .predictedBaseValue(predicted)
                    .deviationPercent(deviationPct)
                    .createdAt(alertTime)
                    .acknowledged(false)
                    .ignored(false)
                    .signature(signature)
                    .build();
            alertRepository.save(alert);
            log.info("Created deviation alert id={} signature={} severity={} deviationPct={} actualTs={} predictedTs={}", alert.getId(), signature, severity, String.format("%.2f", deviationPct), actualTs, actualTs);
        } catch (Exception e) {
            log.debug("Failed deviation evaluation for deviceId={} tagId={} error={}", device.getId(), tag.getId(), e.getMessage());
        }
    }

    /**
     * 计算偏差百分比 (actual vs predicted) 并对 actual==0 / predicted≈0 的特殊情况做优化。
     * 规则:
     * 1. 两者都几乎为 0 (|value| < EPS) -> 返回 0 (视为无偏差, 跳过)
     * 2. 优先使用预测值作为分母 (若其绝对值 >= EPS)
     * 3. 否则用实际值作为分母 (若其绝对值 >= EPS)
     * 4. 若二者都 < EPS 已在第1步处理，不进入 0 分母情况
     */
    private Double computeDeviationPercent(Double actual, Double predicted) {
        if (actual == null || predicted == null) return null;
        final double EPS = 1e-6; // near-zero 阈值
        double a = actual;
        double p = predicted;
        boolean aZeroLike = Math.abs(a) < EPS;
        boolean pZeroLike = Math.abs(p) < EPS;
        if (aZeroLike && pZeroLike) {
            // 都接近 0 视为无偏差，返回 0 (调用方会跳过阈值判断)
            return 0d;
        }
        double denom;
        if (!pZeroLike) {
            denom = p; // 优先预测值
        } else if (!aZeroLike) {
            denom = a; // 次选实际值
        } else {
            // 理论不会到达 (两者都 zeroLike 已在前面处理)，兜底
            denom = 1.0;
        }
        if (Math.abs(denom) < EPS) {
            // 仍不安全，避免数值炸裂
            log.debug("Skip deviation evaluation due to near-zero denominator deviceId? actual={} predicted={} denom={}", a, p, denom);
            return null;
        }
        return (a - p) / denom * 100.0;
    }

    private String computeSeverityByPercent(double absDeviationPercent) {
        return absDeviationPercent >= severityHighPercent ? "HIGH" : "MEDIUM";
    }

    private String buildSignature(Long deviceId, Long tagId, String type) {
        return deviceId + ":" + tagId + ":" + type;
    }

    private Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof CharSequence cs) {
            String s = cs.toString().trim();
            if (s.isEmpty()) return null;
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
        }
        if (v instanceof Boolean b) return b ? 1.0 : 0.0;
        return null;
    }

    public boolean isEnabled() { return enabled; }
}
