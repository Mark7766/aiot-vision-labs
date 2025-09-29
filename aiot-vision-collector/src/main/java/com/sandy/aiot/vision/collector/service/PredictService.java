package com.sandy.aiot.vision.collector.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandy.aiot.vision.collector.entity.DataRecord;
import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.repository.DeviceRepository;
import com.sandy.aiot.vision.collector.vo.TimeSeriesDataModelVO;
import com.sandy.aiot.vision.collector.vo.TimeSeriesDataModelVO.PredictionPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PredictService {
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private TsFileStorageService tsFileStorageService;
    @Autowired
    private ObjectMapper objectMapper;

    @Value("${predict.api.url:http://localhost:5000/predict}")
    private String predictApiUrl;
    @Value("${predict.api.prediction-length:60}")
    private int defaultPredictionLength;

    private final RestTemplate restTemplate = new RestTemplate();

    public TimeSeriesDataModelVO predict(Long deviceId, Long tagId) {
        Optional<Device> deviceOpt = deviceRepository.findById(deviceId);
        if (deviceOpt.isEmpty()) {
            throw new RuntimeException("Device[deviceId=" + deviceId + "] not found");
        }
        // 替换为 TsFile 读取
        List<DataRecord> dataRecords = tsFileStorageService.findTopN(deviceId, tagId,3);
        List<Float> recentValues = new ArrayList<>();
        List<LocalDateTime> recentTimestamps = new ArrayList<>();
        for (DataRecord dataRecord : dataRecords) {
                recentValues.add(Float.valueOf((String)dataRecord.getValue()));
                recentTimestamps.add(dataRecord.getTimestamp());
        }
        Collections.reverse(recentValues); // 升序
        Collections.reverse(recentTimestamps);
        List<Float> predictions = doPredict(recentValues);
        List<PredictionPoint> points = buildPredictionPoints(recentTimestamps, predictions);
        TimeSeriesDataModelVO vo = new TimeSeriesDataModelVO();
        vo.setTimestamps(recentTimestamps); // 仅内部保留
        vo.setPredictionPoints(points);
        return vo;
    }

    private List<PredictionPoint> buildPredictionPoints(List<LocalDateTime> recentTs, List<Float> predictions){
        if (predictions==null || predictions.isEmpty()) return Collections.emptyList();
        if (recentTs==null || recentTs.isEmpty()) {
            // 没有历史时间，直接使用当前时间步长 1 分钟递增
            LocalDateTime base = LocalDateTime.now();
            return buildFromBase(base, Duration.ofMinutes(1), predictions);
        }
        Duration step = inferStep(recentTs);
        LocalDateTime last = recentTs.get(recentTs.size()-1);
        List<PredictionPoint> list = new ArrayList<>(predictions.size());
        for (int i=0;i<predictions.size();i++){
            LocalDateTime ts = last.plus(step.multipliedBy(i+1));
            list.add(PredictionPoint.builder().timestamp(ts).value(predictions.get(i)).build());
        }
        return list;
    }

    private List<PredictionPoint> buildFromBase(LocalDateTime base, Duration step, List<Float> values){
        List<PredictionPoint> list = new ArrayList<>(values.size());
        for (int i=0;i<values.size();i++){
            list.add(PredictionPoint.builder().timestamp(base.plus(step.multipliedBy(i+1))).value(values.get(i)).build());
        }
        return list;
    }

    private Duration inferStep(List<LocalDateTime> ts){
        if (ts.size()<2) return Duration.ofMinutes(1);
        List<Long> diffs = new ArrayList<>();
        for (int i=1;i<ts.size();i++){
            Duration d = Duration.between(ts.get(i-1), ts.get(i));
            long ms = d.toMillis();
            if (ms>0) diffs.add(ms);
        }
        if (diffs.isEmpty()) return Duration.ofMinutes(1);
        Collections.sort(diffs);
        long median = diffs.get(diffs.size()/2);
        if (median <=0) median = 60_000L;
        return Duration.ofMillis(median);
    }

    private List<Float> doPredict(List<Float> recentValues) {
        if (recentValues == null || recentValues.isEmpty()) {
            log.warn("recentValues 为空, 返回空预测");
            return Collections.emptyList();
        }
        int predictionLength = defaultPredictionLength > 0 ? defaultPredictionLength : 60;
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("data", recentValues);
        requestBody.put("prediction_length", predictionLength);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            String json = objectMapper.writeValueAsString(requestBody);
            HttpEntity<String> entity = new HttpEntity<>(json, headers);
            long start = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.postForEntity(predictApiUrl, entity, String.class);
            long cost = System.currentTimeMillis() - start;
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("预测服务返回非成功状态: status={} body={}", response.getStatusCode(), response.getBody());
                return fallbackPredict(recentValues, predictionLength);
            }
            Map<String, Object> respMap = objectMapper.readValue(response.getBody(), new TypeReference<>() {});
            Object predsObj = respMap.get("predictions");
            List<Float> predictions = new ArrayList<>();
            if (predsObj instanceof List<?> list) {
                for (Object v : list) {
                    if (v instanceof Number n) {
                        predictions.add(n.floatValue());
                    } else if (v != null) {
                        try { predictions.add(Float.parseFloat(v.toString())); } catch (NumberFormatException ignored) { }
                    }
                }
            }
            if (predictions.size() != predictionLength) {
                log.warn("预测数量 {} 与期望 {} 不一致, 使用回退策略", predictions.size(), predictionLength);
                return fallbackPredict(recentValues, predictionLength);
            }
            log.info("调用预测服务成功, cost={}ms, histSize={}, predSize={}", cost, recentValues.size(), predictions.size());
            return predictions;
        } catch (RestClientException e) {
            log.error("调用预测服务失败: {}", e.getMessage());
            return fallbackPredict(recentValues, predictionLength);
        } catch (Exception e) {
            log.error("解析预测结果失败: {}", e.getMessage());
            return fallbackPredict(recentValues, predictionLength);
        }
    }

    private List<Float> fallbackPredict(List<Float> recentValues, int predictionLength) {
        List<Float> fb = new ArrayList<>(predictionLength);
        float fill;
        if (recentValues == null || recentValues.isEmpty()) {
            fill = 0f;
        } else {
            fill = recentValues.get(recentValues.size() - 1);
            if (Float.isNaN(fill)) {
                double sum = 0; int c = 0;
                for (Float v : recentValues) { if (v != null && !Float.isNaN(v)) { sum += v; c++; } }
                fill = c == 0 ? 0f : (float) (sum / c);
            }
        }
        for (int i = 0; i < predictionLength; i++) fb.add(fill);
        return fb;
    }

    private Map<String, Object> parseRecordValue(DataRecord record) {
        if (record == null || record.getValue() == null) return Collections.emptyMap();
        try { return objectMapper.readValue(record.getValue(), new TypeReference<>() {}); }
        catch (Exception e) { log.warn("解析数据失败 id={} err={}", record.getId(), e.getMessage()); return Collections.emptyMap(); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractDataMap(Map<String, Object> parsed) {
        if (parsed == null) return null;
        Object data = parsed.get("data");
        if (data instanceof Map) { return (Map<String, Object>) data; }
        return null;
    }
}
