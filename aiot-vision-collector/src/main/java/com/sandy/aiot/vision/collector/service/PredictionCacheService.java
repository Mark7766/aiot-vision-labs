package com.sandy.aiot.vision.collector.service;

import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.entity.Tag;
import com.sandy.aiot.vision.collector.repository.DeviceRepository;
import com.sandy.aiot.vision.collector.vo.TimeSeriesDataModelVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 缓存预测结果: 预先调用预测服务并将 (deviceId, tagId, timestamp) -> value 存入内存.
 * 当实际采集值到来时, 直接按照时间戳获取预测值, 再做偏差判断, 避免每次扫描临时调用预测服务.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PredictionCacheService {

    private final PredictService predictService;
    private final DeviceRepository deviceRepository;

    @Value("${predict.cache.enabled:true}")
    private boolean enabled;
    @Value("${predict.cache.prefetch-interval-ms:30000}")
    private long prefetchIntervalMs;
    @Value("${predict.cache.min-ahead-minutes:2}")
    private int minAheadMinutes; // 预测覆盖最少提前分钟数
    @Value("${predict.cache.tolerance-ms:30000}")
    private long toleranceMs; // 匹配实际时间戳的容差
    @Value("${predict.cache.max-points-per-tag:5000}")
    private int maxPointsPerTag;

    // 缓存结构: key -> (timestamp -> predictedValue)
    private final Map<DeviceTagKey, PredSeries> cache = new ConcurrentHashMap<>();

    /**
     * 获取指定 device/tag 在给定时间戳的预测值 (允许一定容差). 若缓存缺失则懒加载一次预测.
     */
    public Double getPredictedValue(Long deviceId, Long tagId, LocalDateTime timestamp) {
        if (!enabled || timestamp == null) return null;
        DeviceTagKey key = new DeviceTagKey(deviceId, tagId);
        PredSeries series = cache.computeIfAbsent(key, k -> new PredSeries());
        Double val = series.find(timestamp, toleranceMs);
        if (val != null) return val;
        // 缓存中无对应时间点, 尝试懒加载一次
        tryPrefetch(key, series);
        return series.find(timestamp, toleranceMs);
    }

    /**
     * 手动放入一个预测点 (供测试/外部快速注入使用)
     */
    public void putPredictionPoint(Long deviceId, Long tagId, LocalDateTime timestamp, Double value) {
        if (timestamp == null || value == null) return;
        DeviceTagKey key = new DeviceTagKey(deviceId, tagId);
        PredSeries series = cache.computeIfAbsent(key, k -> new PredSeries());
        series.update(List.of(TimeSeriesDataModelVO.PredictionPoint.builder().timestamp(timestamp).value(value).build()), 60_000L);
    }

    /** 定时预取: 确保每个标签的预测序列至少覆盖当前时间之后 minAheadMinutes 分钟. */
    @Scheduled(fixedDelayString = "${predict.cache.prefetch-interval-ms:30000}")
    public void scheduledPrefetch() {
        if (!enabled) return;
        long start = System.currentTimeMillis();
        List<Device> devices = deviceRepository.findAllWithTags();
        int tagCount = 0;
        int refreshed = 0;
        for (Device d : devices) {
            if (d.getTags() == null) continue;
            for (Tag t : d.getTags()) {
                tagCount++;
                DeviceTagKey key = new DeviceTagKey(d.getId(), t.getId());
                PredSeries series = cache.computeIfAbsent(key, k -> new PredSeries());
                if (needsPrefetch(series)) {
                    if (tryPrefetch(key, series)) refreshed++;
                }
                series.trimOld(maxPointsPerTag);
            }
        }
        long cost = System.currentTimeMillis() - start;
        if (tagCount > 0) {
            log.debug("Prediction prefetch completed. tagsChecked={} refreshed={} cost={}ms", tagCount, refreshed, cost);
        }
    }

    private boolean needsPrefetch(PredSeries series) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastTs = series.lastTimestamp();
        if (lastTs == null) return true;
        Duration ahead = Duration.between(now, lastTs);
        return ahead.toMinutes() < minAheadMinutes; // 覆盖时间不足
    }

    private boolean tryPrefetch(DeviceTagKey key, PredSeries series) {
        try {
            TimeSeriesDataModelVO vo = predictService.predict(key.deviceId, key.tagId);
            if (vo == null || vo.getPredictionPoints() == null || vo.getPredictionPoints().isEmpty()) return false;
            // 计算步长
            long stepMillis = inferStepMillis(vo.getTimestamps());
            series.update(vo.getPredictionPoints(), stepMillis);
            return true;
        } catch (Exception e) {
            log.debug("Prefetch prediction failed deviceId={} tagId={} err={}", key.deviceId, key.tagId, e.getMessage());
            return false;
        }
    }

    private long inferStepMillis(List<LocalDateTime> ts) {
        if (ts == null || ts.size() < 2) return 60_000L;
        List<Long> diffs = new ArrayList<>();
        for (int i = 1; i < ts.size(); i++) {
            long ms = Duration.between(ts.get(i - 1), ts.get(i)).toMillis();
            if (ms > 0) diffs.add(ms);
        }
        if (diffs.isEmpty()) return 60_000L;
        Collections.sort(diffs);
        long median = diffs.get(diffs.size() / 2);
        return median <= 0 ? 60_000L : median;
    }

    // 内部 key
    private record DeviceTagKey(Long deviceId, Long tagId) {}

    // 预测序列封装
    private static class PredSeries {
        private final NavigableMap<LocalDateTime, Double> points = new TreeMap<>();
        private long stepMillis = 60_000L;
        private LocalDateTime lastPrefetchTime;

        synchronized void update(List<TimeSeriesDataModelVO.PredictionPoint> newPoints, long stepMillis) {
            for (TimeSeriesDataModelVO.PredictionPoint p : newPoints) {
                if (p.getTimestamp() != null && p.getValue() != null) {
                    points.put(p.getTimestamp(), p.getValue());
                }
            }
            this.stepMillis = stepMillis > 0 ? stepMillis : this.stepMillis;
            lastPrefetchTime = LocalDateTime.now();
        }

        synchronized Double find(LocalDateTime ts, long toleranceMs) {
            if (ts == null || points.isEmpty()) return null;
            Double exact = points.get(ts);
            if (exact != null) return exact;
            // 在最近的前后点里找最小差值
            Map.Entry<LocalDateTime, Double> floor = points.floorEntry(ts);
            Map.Entry<LocalDateTime, Double> ceil = points.ceilingEntry(ts);
            long bestDiff = Long.MAX_VALUE;
            Double bestVal = null;
            if (floor != null) {
                long diff = Math.abs(Duration.between(ts, floor.getKey()).toMillis());
                if (diff < bestDiff) { bestDiff = diff; bestVal = floor.getValue(); }
            }
            if (ceil != null) {
                long diff = Math.abs(Duration.between(ts, ceil.getKey()).toMillis());
                if (diff < bestDiff) { bestDiff = diff; bestVal = ceil.getValue(); }
            }
            return bestDiff <= toleranceMs ? bestVal : null;
        }

        synchronized LocalDateTime lastTimestamp() {
            return points.isEmpty() ? null : points.lastKey();
        }

        synchronized void trimOld(int maxPoints) {
            while (points.size() > maxPoints) {
                points.pollFirstEntry();
            }
        }
    }
}

