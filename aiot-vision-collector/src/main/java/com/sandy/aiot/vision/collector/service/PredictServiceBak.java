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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
public class PredictServiceBak {
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
            // 拉取更多历史，保证满足模型的季节滞后和上下文长度需求
            List<DataRecord> dataRecords = dataRecordRepository.findTop1000ByTagIdOrderByTimestampDesc(deviceId);
            List<Float> recentValues = new ArrayList<>();
            List<LocalDateTime> recentTimestamps = new ArrayList<>();
            for (DataRecord dataRecord : dataRecords) {
                Map<String, Object> parsed = parseRecordValue(dataRecord);
                Map<String, Object> data = extractDataMap(parsed);
                if (data != null && data.containsKey(tagName)) {
                    try {
                        recentValues.add(Float.valueOf(String.valueOf(data.get(tagName))));
                        recentTimestamps.add(dataRecord.getTimestamp());
                    } catch (NumberFormatException nfe) {
                        // 非数值数据，跳过
                    }
                }
            }
            // 按时间升序（最早 -> 最新）
            Collections.reverse(recentValues);
            Collections.reverse(recentTimestamps);

            float[] valueArray = new float[recentValues.size()];
            for (int i = 0; i < recentValues.size(); i++) {
                valueArray[i] = recentValues.get(i);
            }
            NDArray targetArray = manager.create(valueArray);
            log.info("Target Array Shape: {} length={},[]={}", targetArray.getShape(), recentValues.size(), targetArray.getShape().getShape());
            TimeSeriesData input = new TimeSeriesData(recentValues.size());
            input.setField(FieldName.TARGET, targetArray);
            input.setStartTime(recentTimestamps.get(0)); // 起始时间为最早时间
            try (Predictor<TimeSeriesData, Forecast> predictor = zooModel.newPredictor()) {
                Forecast forecast = predictor.predict(input);
                List<Float> predictions = new ArrayList<>();
                try (NDArray mean = forecast.mean()) {
                    float[] forecastArray = mean.toFloatArray();
                    for (float value : forecastArray) {
                        predictions.add(value);
                    }
                }
                TimeSeriesDataModelVO timeSeriesDataModelVO = new TimeSeriesDataModelVO();
                timeSeriesDataModelVO.setTimestamps(recentTimestamps); // 历史 timestamps（升序）
                timeSeriesDataModelVO.setValues(recentValues); // 历史 values（升序）
                timeSeriesDataModelVO.setPredictions(predictions);
                log.info("Predict result size: hist={}, pred={}", recentValues.size(), predictions.size());
                return timeSeriesDataModelVO;
            }
        } catch (TranslateException e) {
            throw new RuntimeException(e);
        }
    }

    // 解析 DataRecord.value JSON
    private Map<String, Object> parseRecordValue(DataRecord record) {
        if (record == null || record.getValue() == null) return Collections.emptyMap();
        try {
            return objectMapper.readValue(record.getValue(), new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("解析数据失败 id={} err={}", record.getId(), e.getMessage());
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractDataMap(Map<String, Object> parsed) {
        if (parsed == null) return null;
        Object data = parsed.get("data");
        if (data instanceof Map) {
            return (Map<String, Object>) data;
        }
        return null;
    }
}
