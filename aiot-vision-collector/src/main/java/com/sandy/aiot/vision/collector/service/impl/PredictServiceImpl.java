package com.sandy.aiot.vision.collector.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandy.aiot.vision.collector.entity.DataRecord;
import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.repository.DeviceRepository;
import com.sandy.aiot.vision.collector.service.DataStorageService;
import com.sandy.aiot.vision.collector.service.PredictService;
import com.sandy.aiot.vision.collector.vo.TimeSeriesDataModelVO;
import com.sandy.aiot.vision.collector.vo.TimeSeriesDataModelVO.PredictionPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Service
@Slf4j
@RequiredArgsConstructor
public class PredictServiceImpl implements PredictService {

    private final DeviceRepository deviceRepository;
    private final DataStorageService dataStorageService;
    private final ObjectMapper objectMapper;

    @Value("${predict.api.url}")
    private String predictApiUrl;
    @Value("${predict.api.prediction-length}")
    private int defaultPredictionLength;
    @Value("${predict.api.history-length}")
    private int historyFetchLength;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public TimeSeriesDataModelVO predict(Long deviceId, Long tagId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("Device[deviceId=" + deviceId + "] not found"));
        int fetch = historyFetchLength > 0 ? historyFetchLength : 200;
        List<DataRecord> dataRecords = dataStorageService.findTopN(device.getId(), tagId, fetch);
        List<Float> recentValues = new ArrayList<>(dataRecords.size());
        List<LocalDateTime> recentTimestamps = new ArrayList<>(dataRecords.size());
        for (DataRecord dataRecord : dataRecords) {
            recentValues.add(toFloat(dataRecord.getValue()));
            recentTimestamps.add(dataRecord.getTimestamp());
        }
        Collections.reverse(recentValues);
        Collections.reverse(recentTimestamps);
        List<Float> predictions = doPredict(recentValues);
        List<PredictionPoint> points = buildPredictionPoints(recentTimestamps, predictions);
        TimeSeriesDataModelVO vo = new TimeSeriesDataModelVO();
        vo.setTimestamps(recentTimestamps);
        vo.setPredictionPoints(points);
        return vo;
    }

    private Float toFloat(Object value) {
        if (value == null) return Float.NaN;
        if (value instanceof Float f) return normalizeFloat(f);
        if (value instanceof Number n) return normalizeFloat(n.floatValue());
        if (value instanceof Boolean b) return b ? 1.0f : 0.0f;
        if (value instanceof CharSequence cs) {
            String s = cs.toString().trim();
            if (s.isEmpty()) return Float.NaN;
            String cleaned = s.replace(",", "");
            if (cleaned.endsWith("%")) {
                String pct = cleaned.substring(0, cleaned.length() - 1);
                try { return normalizeFloat(Float.parseFloat(pct) / 100f); } catch (NumberFormatException ignored) { return Float.NaN; }
            }
            try { return normalizeFloat(Float.parseFloat(cleaned)); }
            catch (NumberFormatException e) { return tryParseLeading(cleaned, s); }
        }
        if (value instanceof Collection<?> col) { for (Object v : col) if (v != null) return toFloat(v); return Float.NaN; }
        if (value.getClass().isArray()) { int len = java.lang.reflect.Array.getLength(value); return len>0? toFloat(java.lang.reflect.Array.get(value,0)) : Float.NaN; }
        try { return normalizeFloat(Float.parseFloat(value.toString().trim())); } catch (Exception e) { return Float.NaN; }
    }

    private Float tryParseLeading(String cleaned, String original){
        int i=0; boolean dot=false; boolean sign=false; while(i<cleaned.length()) {
            char c=cleaned.charAt(i);
            if((c>='0'&&c<='9')||(!dot&&c=='.')||(!sign&&(c=='+'||c=='-'))){ if(c=='.') dot=true; if(c=='+'||c=='-') sign=true; i++; } else break;
        }
        if(i>0){ try { return normalizeFloat(Float.parseFloat(cleaned.substring(0,i))); } catch (NumberFormatException ignored) { } }
        log.debug("无法解析字符串为浮点数: '{}'", original); return Float.NaN;
    }

    private Float normalizeFloat(Float f){ return (f==null||Float.isInfinite(f)||Float.isNaN(f))?Float.NaN:f; }

    private List<PredictionPoint> buildPredictionPoints(List<LocalDateTime> recentTs, List<Float> predictions){
        if(predictions==null||predictions.isEmpty()) return Collections.emptyList();
        if(recentTs==null||recentTs.isEmpty()) return buildFromBase(LocalDateTime.now(), Duration.ofMinutes(1), predictions);
        Duration step = inferStep(recentTs);
        LocalDateTime last = recentTs.get(recentTs.size()-1);
        List<PredictionPoint> list = new ArrayList<>(predictions.size());
        for(int i=0;i<predictions.size();i++) list.add(PredictionPoint.builder().timestamp(last.plus(step.multipliedBy(i+1))).value(predictions.get(i)).build());
        return list;
    }

    private List<PredictionPoint> buildFromBase(LocalDateTime base, Duration step, List<Float> values){
        List<PredictionPoint> list = new ArrayList<>(values.size());
        for(int i=0;i<values.size();i++) list.add(PredictionPoint.builder().timestamp(base.plus(step.multipliedBy(i+1))).value(values.get(i)).build());
        return list;
    }

    private Duration inferStep(List<LocalDateTime> ts){
        if(ts.size()<2) return Duration.ofMinutes(1);
        List<Long> diffs = new ArrayList<>();
        for(int i=1;i<ts.size();i++){ long ms=Duration.between(ts.get(i-1), ts.get(i)).toMillis(); if(ms>0) diffs.add(ms); }
        if(diffs.isEmpty()) return Duration.ofMinutes(1);
        Collections.sort(diffs);
        long median = diffs.get(diffs.size()/2); if(median<=0) median=60_000L; return Duration.ofMillis(median);
    }

    private List<Float> doPredict(List<Float> recentValues){
        if(recentValues==null||recentValues.isEmpty()){ log.warn("recentValues 为空, 返回空预测"); return Collections.emptyList(); }
        int predictionLength = defaultPredictionLength > 0 ? defaultPredictionLength : 60;
        Map<String,Object> body = new HashMap<>(); body.put("data", recentValues); body.put("prediction_length", predictionLength);
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpEntity<String> entity = new HttpEntity<>(json, headers);
            long start = System.currentTimeMillis();
            ResponseEntity<String> resp = restTemplate.postForEntity(predictApiUrl, entity, String.class);
            long cost = System.currentTimeMillis()-start;
            if(!resp.getStatusCode().is2xxSuccessful() || resp.getBody()==null){
                log.warn("预测服务返回非成功状态: status={} body={}", resp.getStatusCode(), resp.getBody());
                return fallbackPredict(recentValues, predictionLength);
            }
            Map<String,Object> map = objectMapper.readValue(resp.getBody(), new TypeReference<>(){});
            Object predsObj = map.get("predictions");
            List<Float> predictions = new ArrayList<>();
            if(predsObj instanceof List<?> list){ for(Object v : list){ if(v instanceof Number n) predictions.add(n.floatValue()); else if(v!=null) try { predictions.add(Float.parseFloat(v.toString())); } catch(NumberFormatException ignored) {} } }
            if(predictions.size()!=predictionLength){ log.warn("预测数量 {} 与期望 {} 不一致, 使用回退策略", predictions.size(), predictionLength); return fallbackPredict(recentValues, predictionLength); }
            log.info("调用预测服务成功, cost={}ms, histSize={}, predSize={}", cost, recentValues.size(), predictions.size());
            return predictions;
        } catch (RestClientException e){ log.error("调用预测服务失败: {}", e.getMessage()); return fallbackPredict(recentValues, predictionLength); }
        catch (Exception e){ log.error("解析预测结果失败: {}", e.getMessage()); return fallbackPredict(recentValues, predictionLength); }
    }

    private List<Float> fallbackPredict(List<Float> recentValues, int predictionLength){
        List<Float> fb = new ArrayList<>(predictionLength);
        float fill;
        if(recentValues==null||recentValues.isEmpty()) fill=0f; else { fill=recentValues.get(recentValues.size()-1); if(Float.isNaN(fill)){ double sum=0; int c=0; for(Float v:recentValues){ if(v!=null && !Float.isNaN(v)){ sum+=v; c++; } } fill = c==0?0f:(float)(sum/c); } }
        for(int i=0;i<predictionLength;i++) fb.add(fill); return fb;
    }
}

