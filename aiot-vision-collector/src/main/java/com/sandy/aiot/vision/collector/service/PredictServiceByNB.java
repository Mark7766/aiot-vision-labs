package com.sandy.aiot.vision.collector.service;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
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

import java.nio.FloatBuffer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
public class PredictServiceByNB {
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private DataRecordRepository dataRecordRepository;
    @Autowired
    private ObjectMapper objectMapper;

    public TimeSeriesDataModelVO predict(Long deviceId, String tagName) {
        Optional<Device> deviceOpt = deviceRepository.findById(deviceId);
        if (deviceOpt.isEmpty()) {
            throw new RuntimeException("Device[deviceId=" + deviceId + "] not found");
        }
        List<DataRecord> dataRecords = dataRecordRepository.findTop180ByTagIdOrderByTimestampDesc(deviceId);
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

        float[] hisData = new float[recentValues.size()];
        for (int i = 0; i < recentValues.size(); i++) hisData[i] = recentValues.get(i);
        List<Float> predictions = doPredict(hisData);
        List<PredictionPoint> pts = buildPredictionPoints(recentTimestamps, predictions);
        TimeSeriesDataModelVO vo = new TimeSeriesDataModelVO();
        vo.setTimestamps(recentTimestamps); // 内部保留历史时间用于步长推断
        vo.setPredictionPoints(pts);
        log.info("Predict result size: histTs={}, predPts={}", recentTimestamps.size(), pts.size());
        return vo;
    }

    private List<PredictionPoint> buildPredictionPoints(List<LocalDateTime> recentTs, List<Float> predictions){
        if (predictions==null||predictions.isEmpty()) return Collections.emptyList();
        if (recentTs==null||recentTs.isEmpty()){
            return buildFromBase(LocalDateTime.now(), Duration.ofSeconds(1), predictions);
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
        if (ts.size()<2) return Duration.ofSeconds(1);
        List<Long> diffs = new ArrayList<>();
        for (int i=1;i<ts.size();i++){
            long ms = Duration.between(ts.get(i-1), ts.get(i)).toMillis();
            if (ms>0) diffs.add(ms);
        }
        if (diffs.isEmpty()) return Duration.ofSeconds(1);
        Collections.sort(diffs);
        return Duration.ofMillis(diffs.get(diffs.size()/2));
    }

    // 解析 DataRecord.value JSON
    private Map<String, Object> parseRecordValue(DataRecord record) {
        if (record == null || record.getValue() == null) return Collections.emptyMap();
        try {
            return objectMapper.readValue(record.getValue(), new TypeReference<>() {});
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

    private List<Float> doPredict(float[] sampleData) {
        String modelPath = "/Users/mark/work/gitspace/pyspace/nbeats_iot_180.onnx"; // TODO: 外部化配置
        int inputChunkLength = 180;
        int outputChunkLength = 60; // 未来长度
        int batchSize = 1;
        int nFeatures = 1;
        List<Float> vs = new ArrayList<>();
        try (OrtEnvironment env = OrtEnvironment.getEnvironment();
             OrtSession session = env.createSession(modelPath, new OrtSession.SessionOptions())) {
            float[] inputData = new float[batchSize * inputChunkLength * nFeatures];
            if (sampleData.length < inputChunkLength) {
                throw new IllegalArgumentException("sampleData 长度不足 " + inputChunkLength + "，请加载更多数据");
            }
            System.arraycopy(sampleData, sampleData.length - inputChunkLength, inputData, 0, inputChunkLength);
            long[] inputShape = new long[]{batchSize, inputChunkLength, nFeatures};
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), inputShape);
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("x_in", inputTensor);
            try (OrtSession.Result result = session.run(inputs)) {
                OnnxTensor outputTensor = (OnnxTensor) result.get(0);
                float[][][][] outputData = (float[][][][]) outputTensor.getValue();
                for (int i = 0; i < outputData[0].length && i < outputChunkLength; i++) {
                    vs.add(outputData[0][i][0][0]);
                }
            }
        } catch (OrtException e) {
            log.error("ONNX 推理异常: {}", e.getMessage());
        } catch (Exception e) {
            log.error("推理一般错误: {}", e.getMessage());
        }
        return vs;
    }
}
