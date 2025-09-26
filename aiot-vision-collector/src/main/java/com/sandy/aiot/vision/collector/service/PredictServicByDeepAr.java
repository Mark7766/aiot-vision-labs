package com.sandy.aiot.vision.collector.service;

import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.timeseries.Forecast;
import ai.djl.timeseries.TimeSeriesData;
import ai.djl.timeseries.dataset.FieldName;
import ai.djl.translate.TranslateException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandy.aiot.vision.collector.entity.DataRecord;
import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.repository.DataRecordRepository;
import com.sandy.aiot.vision.collector.repository.DeviceRepository;
import com.sandy.aiot.vision.collector.vo.TimeSeriesDataModelVO;
import com.sandy.aiot.vision.collector.vo.TimeSeriesDataModelVO.PredictionPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
public class PredictServicByDeepAr {
    @Autowired
    private ZooModel<TimeSeriesData, Forecast> zooModel;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private DataRecordRepository dataRecordRepository;
    @Autowired
    private ObjectMapper objectMapper;

    public TimeSeriesDataModelVO predict(Long deviceId, String tagName) {
        try (NDManager manager = NDManager.newBaseManager()) {
            Optional<Device> deviceOpt = deviceRepository.findById(deviceId);
            if (deviceOpt.isEmpty()) {
                throw new RuntimeException("Device[deviceId=" + deviceId + "] not found");
            }
            List<DataRecord> dataRecords = dataRecordRepository.findTop10000ByTagIdOrderByTimestampDesc(deviceId);
            List<Float> recentValues = new ArrayList<>();
            List<LocalDateTime> recentTimestamps = new ArrayList<>();
            for (DataRecord dataRecord : dataRecords) {
                Map<String, Object> parsed = parseRecordValue(dataRecord);
                Map<String, Object> data = extractDataMap(parsed);
                if (data != null && data.containsKey(tagName)) {
                    try {
                        recentValues.add(Float.valueOf(String.valueOf(data.get(tagName))));
                        recentTimestamps.add(dataRecord.getTimestamp());
                    } catch (NumberFormatException ignored) { }
                }
            }
            Collections.reverse(recentValues);
            Collections.reverse(recentTimestamps);

            float[] valueArray = new float[recentValues.size()];
            for (int i = 0; i < recentValues.size(); i++) valueArray[i] = recentValues.get(i);
            NDArray targetArray = manager.create(valueArray);
            long[][] staticCats = {{0,0,0,0,0}};
            NDArray staticCatArray = manager.create(staticCats);
            TimeSeriesData input = new TimeSeriesData(recentValues.size());
            input.setField(FieldName.TARGET, targetArray);
            input.setField(FieldName.FEAT_STATIC_CAT, staticCatArray);
            input.setStartTime(recentTimestamps.isEmpty()? LocalDateTime.now(): recentTimestamps.get(0));
            try (Predictor<TimeSeriesData, Forecast> predictor = zooModel.newPredictor()) {
                Forecast forecast = predictor.predict(input);
                List<Float> predictions = new ArrayList<>();
                try (NDArray mean = forecast.mean()) {
                    float[] forecastArray = mean.toFloatArray();
                    for (float v : forecastArray) predictions.add(v);
                }
                List<PredictionPoint> pts = buildPredictionPoints(recentTimestamps, predictions);
                TimeSeriesDataModelVO vo = new TimeSeriesDataModelVO();
                vo.setTimestamps(recentTimestamps);
                vo.setPredictionPoints(pts);
                return vo;
            }
        } catch (TranslateException e) {
            throw new RuntimeException(e);
        }
    }

    private List<PredictionPoint> buildPredictionPoints(List<LocalDateTime> recentTs, List<Float> predictions){
        if (predictions==null||predictions.isEmpty()) return Collections.emptyList();
        if (recentTs==null||recentTs.isEmpty()){
            return buildFromBase(LocalDateTime.now(), Duration.ofMinutes(1), predictions);
        }
        Duration step = inferStep(recentTs);
        LocalDateTime last = recentTs.get(recentTs.size()-1);
        List<PredictionPoint> list = new ArrayList<>(predictions.size());
        for (int i=0;i<predictions.size();i++){
            list.add(PredictionPoint.builder().timestamp(last.plus(step.multipliedBy(i+1))).value(predictions.get(i)).build());
        }
        return list;
    }
    private List<PredictionPoint> buildFromBase(LocalDateTime base, Duration step, List<Float> values){
        List<PredictionPoint> list = new ArrayList<>(values.size());
        for (int i=0;i<values.size();i++) list.add(PredictionPoint.builder().timestamp(base.plus(step.multipliedBy(i+1))).value(values.get(i)).build());
        return list;
    }
    private Duration inferStep(List<LocalDateTime> ts){
        if (ts.size()<2) return Duration.ofMinutes(1);
        List<Long> diffs = new ArrayList<>();
        for (int i=1;i<ts.size();i++){ long ms = java.time.Duration.between(ts.get(i-1), ts.get(i)).toMillis(); if (ms>0) diffs.add(ms);}        if (diffs.isEmpty()) return Duration.ofMinutes(1);
        Collections.sort(diffs);
        return Duration.ofMillis(diffs.get(diffs.size()/2));
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
