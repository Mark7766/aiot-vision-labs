package com.sandy.aiot.vision.collector;

import ai.onnxruntime.*;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

public class NBEATSInference {
    public static void main(String[] args) {
        // 模型和数据参数
        String modelPath = "/Users/mark/work/gitspace/pyspace/nbeats_iot.onnx"; // 替换为实际路径
        int inputChunkLength = 60; // 过去1分钟（60秒），匹配模型 input_chunk_length
        int outputChunkLength = 60; // 未来1分钟（60秒）
        int batchSize = 1; // 批量大小
        int nFeatures = 1; // 单变量

        try (OrtEnvironment env = OrtEnvironment.getEnvironment();
             OrtSession session = env.createSession(modelPath, new OrtSession.SessionOptions())) {

            // 打印模型输入/输出元数据（调试用）
            System.out.println("模型输入名称: " + session.getInputNames());
            System.out.println("模型输入形状: " + session.getInputInfo());
            System.out.println("模型输出名称: " + session.getOutputNames());
            System.out.println("模型输出形状: " + session.getOutputInfo());

            // 准备输入数据（示例：从 CSV 加载最后 60 秒；这里用硬编码数组模拟）
            float[] inputData = new float[batchSize * inputChunkLength * nFeatures];
            float[] sampleData = {
                    // ... 原数组的所有值（从 CSV 最后部分），这里省略以节省空间；假设长度 >=60
                    // 示例最后 60 个值（从你的 CSV 截取，实际替换为完整数组）
                    10.45285f, 0.0f, -10.45285f, -20.79117f, -30.9017f, -40.67366f, -50.0f, -58.77852f,
                    -66.91306f, -74.31448f, -80.9017f, -86.60254f, -91.35455f, -95.10565f, -97.81476f,
                    -99.45219f, -100.0f, -99.45219f, -97.81476f, -95.10565f, -91.35455f, -86.60254f,
                    -80.9017f, -74.31448f, -66.91306f, -58.77853f, -50.0f, -40.67367f, -30.9017f,
                    -20.79117f, -10.45285f, 0.0f, 10.45285f, 20.79117f, 30.9017f, 40.67366f, 50.0f,
                    58.77852f, 66.91306f, 74.31448f, 80.9017f, 86.60254f, 91.35455f, 95.10565f,
                    97.81476f, 99.45219f, 100.0f, 99.45219f, 97.81476f, 95.10565f, 91.35455f,
                    86.60254f, 80.9017f, 74.31448f, 66.91306f, 58.77853f, 50.0f, 40.67367f,
                    30.9017f, 20.79117f, 10.45285f, 0.0f, -10.45285f, -20.79117f, -30.9017f,
                    -40.67366f, -50.0f, -58.77852f, -66.91306f, -74.31448f, -80.9017f, -86.60254f,
                    -91.35455f, -95.10565f, -97.81476f, -99.45219f, -100.0f, -99.45219f, -97.81476f,
                    -95.10565f, -91.35455f, -86.60254f, -80.9017f, -74.31448f, -66.91306f, -58.77853f,
                    -50.0f, -40.67367f, -30.9017f, -20.79117f, -10.45285f, 0.0f, 10.45285f,
                    20.79117f, 30.9017f, 40.67366f, 50.0f, 58.77852f, 66.91306f, 74.31448f,
                    80.9017f, 86.60254f, 91.35455f, 95.10565f, 97.81476f, 99.45219f, 100.0f,
                    99.45219f, 97.81476f, 95.10565f, 91.35455f, 86.60254f, 80.9017f, 74.31448f,
                    66.91306f, 58.77853f, 50.0f, 40.67366f, 30.9017f, 20.79117f, 10.45285f,
                    0.0f, -10.45284f, -20.79117f, -30.9017f, -40.67366f, -50.0f, -58.77852f,
                    -66.91306f, -74.31448f, -80.9017f, -86.60254f, -91.35454f, -95.10565f,
                    -97.81476f, -99.45219f, -100.0f, -99.45219f, -97.81476f, -95.10565f,
                    -91.35455f, -86.60254f, -80.9017f, -74.31448f, -66.91306f, -58.77853f,
                    -50.0f, -40.67366f, -30.9017f, -20.79117f, -10.45284f, 0.0f, 10.45284f,
                    20.79117f, 30.9017f, 40.67366f, 50.0f, 58.77852f, 66.91306f, 74.31448f,
                    80.9017f, 86.60254f, 91.35454f, 95.10565f, 97.81476f, 99.45219f, 100.0f,
                    99.45219f, 97.81476f, 95.10565f, 91.35455f
                    // 注意：确保 sampleData.length >= 60；如果从 CSV 加载，使用下面注释代码
            };
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
                System.out.println("输出形状: " + java.util.Arrays.toString(outputShape)); // 实际为 [1, 60, 1, 1]
                System.out.println("预测结果（未来60秒）:");
                for (int i = 0; i < outputData[0].length; i++) {
                    // 修复：访问 4D 数组 [batch][time][feat1][feat2]，由于 feat1/feat2=1，取 [0][i][0][0]
                    System.out.println("时间步 " + (i + 1) + ": " + outputData[0][i][0][0]);
                }
            }

        } catch (OrtException e) {
            System.err.println("推理时出错: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("一般错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
}