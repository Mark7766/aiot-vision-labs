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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.FloatBuffer;
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
        // 拉取更多历史，保证满足模型的季节滞后和上下文长度需求
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
                } catch (NumberFormatException nfe) {
                    // 非数值数据，跳过
                }
            }
        }
        // 按时间升序（最早 -> 最新）
        Collections.reverse(recentValues);
        Collections.reverse(recentTimestamps);

        float[] hisData = new float[recentValues.size()];
        for (int i = 0; i < recentValues.size(); i++) {
            hisData[i] = recentValues.get(i);
        }
        List<Float> predictions = doPredict(hisData);
        TimeSeriesDataModelVO timeSeriesDataModelVO = new TimeSeriesDataModelVO();
        timeSeriesDataModelVO.setTimestamps(recentTimestamps); // 历史 timestamps（升序）
        timeSeriesDataModelVO.setValues(recentValues); // 历史 values（升序）
        timeSeriesDataModelVO.setPredictions(predictions);
        log.info("Predict result size: hist={}, pred={}", recentValues.size(), predictions.size());
        return timeSeriesDataModelVO;

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

    private List<Float> doPredict(float[] sampleData) {
        // 模型和数据参数
        String modelPath = "/Users/mark/work/gitspace/pyspace/nbeats_iot_180.onnx"; // 替换为实际路径
        int inputChunkLength = 180; // 过去1分钟（60秒），匹配模型 input_chunk_length
        int outputChunkLength = 60; // 未来1分钟（60秒）
        int batchSize = 1; // 批量大小
        int nFeatures = 1; // 单变量

        List<Float> vs = new ArrayList<>();
        try (OrtEnvironment env = OrtEnvironment.getEnvironment();
             OrtSession session = env.createSession(modelPath, new OrtSession.SessionOptions())) {

            // 打印模型输入/输出元数据（调试用）
            System.out.println("模型输入名称: " + session.getInputNames());
            System.out.println("模型输入形状: " + session.getInputInfo());
            System.out.println("模型输出名称: " + session.getOutputNames());
            System.out.println("模型输出形状: " + session.getOutputInfo());
            // 准备输入数据（示例：从 CSV 加载最后 60 秒；这里用硬编码数组模拟）
            float[] inputData = new float[batchSize * inputChunkLength * nFeatures];
            // 如果 sampleData.length < 60，填充 0 或报错
            if (sampleData.length < inputChunkLength) {
                throw new IllegalArgumentException("sampleData 长度不足 " + inputChunkLength + "，请加载更多数据");
            }
            // 截取最后 60 个值
            System.arraycopy(sampleData, sampleData.length - inputChunkLength, inputData, 0, inputChunkLength);
            // 可选：从 CSV 动态加载（需添加 CSV 解析依赖，如 OpenCSV）
            // 示例：使用 BufferedReader 读取 CSV，最后 60 行 value 列转换为 float
            // 创建输入张量
            long[] inputShape = new long[]{batchSize, inputChunkLength, nFeatures};
            OnnxTensor inputTensor = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(inputData), inputShape);
            // 设置输入（使用模型输入名称 "x_in"）
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("x_in", inputTensor);
            // 运行推理
            try (OrtSession.Result result = session.run(inputs)) {
                // 修复：cast 为 OnnxTensor 以获取形状和值
                OnnxTensor outputTensor = (OnnxTensor) result.get(0);
                long[] outputShape = outputTensor.getInfo().getShape();
                // 修复：输出形状为 [1, 60, 1, 1] (4D)，因此 cast 到 float[][][][]
                float[][][][] outputData = (float[][][][]) outputTensor.getValue();

                // 打印输出
                System.out.println("输出形状: " + Arrays.toString(outputShape)); // 实际为 [1, 60, 1, 1]
                System.out.println("预测结果（未来60秒）:");

                for (int i = 0; i < outputData[0].length; i++) {
                    // 修复：访问 4D 数组 [batch][time][feat1][feat2]，由于 feat1/feat2=1，取 [0][i][0][0]
                    System.out.println("时间步 " + (i + 1) + ": " + outputData[0][i][0][0]);
                    vs.add(outputData[0][i][0][0]);
                }
            }
        } catch (OrtException e) {
            System.err.println("推理时出错: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("一般错误: " + e.getMessage());
            e.printStackTrace();
        }
        return vs;
    }
}
