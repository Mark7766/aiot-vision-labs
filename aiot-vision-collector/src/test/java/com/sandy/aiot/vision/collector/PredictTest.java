package com.sandy.aiot.vision.collector;

import ai.djl.Application;
import ai.djl.MalformedModelException;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.MRL;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.timeseries.Forecast;
import ai.djl.timeseries.TimeSeriesData;
import ai.djl.timeseries.dataset.FieldName;
import ai.djl.timeseries.translator.DeepARTranslator;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.TranslateException;
import com.sandy.aiot.vision.collector.vo.TimeSeriesDataModelVO;
import com.sandy.aiot.vision.collector.vo.TimeSeriesDataModelVO.PredictionPoint;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
@Slf4j
public class PredictTest {
    @Test
    public void testListModel(){
        Map<Application, List<MRL>> map = ModelZoo.listModels();
        map.forEach((application, mrls) -> {
            mrls.forEach(mrl -> {
                log.info("application:{},mrl:{}", application,mrl);
            });

        });
    }
    @Test
    public void testPath() throws URISyntaxException {
        // Resolve model from test classpath instead of relative working directory
        URL resource = Thread.currentThread().getContextClassLoader().getResource("deepar.zip");
        Assertions.assertNotNull(resource, "deepar.zip not found on test classpath (src/test/resources)");
        Path modelPath = Paths.get(resource.toURI());
        File modelFile = modelPath.toFile();
        log.info("exists:{},path:{}", modelFile.exists(), modelFile.getAbsolutePath());
    }

    @Test
    public void testPredict() throws ModelNotFoundException, MalformedModelException, IOException, URISyntaxException {
        System.out.println("Test");
        List<Float> recentValues = getGecentValues();
        List<LocalDateTime> recentTimestamps =getRecentTimestamps();
        Map<String, Object> arguments = new ConcurrentHashMap<>();
        int predictionLength = 28;
        arguments.put("prediction_length", predictionLength);
        DeepARTranslator translator = DeepARTranslator.builder(arguments).build();
        URL resource = Thread.currentThread().getContextClassLoader().getResource("deepar.zip");
        Assertions.assertNotNull(resource, "deepar.zip not found on test classpath (src/test/resources)");
        Path modelPath = Paths.get(resource.toURI());
        Criteria<TimeSeriesData, Forecast> criteria =
                Criteria.builder()
                        .setTypes(TimeSeriesData.class, Forecast.class)
                        .optModelPath(modelPath)
                        .optTranslator(translator)
                        .optProgress(new ProgressBar())
                        .build();
        try (ZooModel<TimeSeriesData, Forecast> model = criteria.loadModel(); NDManager manager = NDManager.newBaseManager()) {
            // model loaded successfully
            log.info("Model loaded from {}", modelPath);
            float[] valueArray = new float[recentValues.size()];
            for (int i = 0; i < recentValues.size(); i++) {
                valueArray[i] = recentValues.get(i);
            }
            NDArray targetArray = manager.create(valueArray);
            System.out.println("Target Array Shape: " + targetArray.getShape()); // 调试形状
            TimeSeriesData input = new TimeSeriesData(recentValues.size());
            input.setField(FieldName.TARGET, targetArray);
            input.setStartTime(recentTimestamps.get(0)); // 使用最近数据的起始时间

            Predictor<TimeSeriesData, Forecast> predictor = model.newPredictor();
            Forecast forecast = predictor.predict(input);

            List<Float> predictions = new ArrayList<>();
            float[] forecastArray = forecast.mean().toFloatArray();
            for (float value : forecastArray) {
                predictions.add(value);
            }
            // 构造新的预测点列表（使用历史时间步长推断）
            Duration step = inferStep(recentTimestamps);
            LocalDateTime last = recentTimestamps.get(recentTimestamps.size()-1);
            List<PredictionPoint> points = new ArrayList<>();
            for (int i=0;i<predictions.size();i++){
                points.add(PredictionPoint.builder().timestamp(last.plus(step.multipliedBy(i+1))).value(predictions.get(i)).build());
            }
            TimeSeriesDataModelVO timeSeriesDataModelVO = new TimeSeriesDataModelVO();
            timeSeriesDataModelVO.setTimestamps(recentTimestamps); // 保留历史用于验证步长
            timeSeriesDataModelVO.setPredictionPoints(points);
            log.info("Result predictionPoints size={} firstPoint={} lastPoint={}", points.size(), points.isEmpty()?null:points.get(0), points.isEmpty()?null:points.get(points.size()-1));
            Assertions.assertEquals(predictionLength, points.size(), "预测点数量不匹配");
        } catch (TranslateException e) {
            throw new RuntimeException(e);
        }
    }

    private Duration inferStep(List<LocalDateTime> ts){
        if (ts.size()<2) return Duration.ofDays(30); // 测试数据为月度示例
        List<Long> diffs = new ArrayList<>();
        for (int i=1;i<ts.size();i++){
            diffs.add(Duration.between(ts.get(i-1), ts.get(i)).toDays());
        }
        if (diffs.isEmpty()) return Duration.ofDays(30);
        diffs.sort(Long::compare);
        long medianDays = diffs.get(diffs.size()/2);
        if (medianDays <=0) medianDays = 30;
        return Duration.ofDays(medianDays);
    }

    private List<LocalDateTime> getRecentTimestamps() {
        List<LocalDateTime> recentTimestamps = new ArrayList<>();
        recentTimestamps.add(LocalDateTime.of(2023, 1, 1, 0, 0));
        recentTimestamps.add(LocalDateTime.of(2023, 2, 1, 0, 0));
        recentTimestamps.add(LocalDateTime.of(2023, 3, 1, 0, 0));
        recentTimestamps.add(LocalDateTime.of(2023, 4, 1, 0, 0));
        recentTimestamps.add(LocalDateTime.of(2023, 5, 1, 0, 0));
        recentTimestamps.add(LocalDateTime.of(2023, 6, 1, 0, 0));
        recentTimestamps.add(LocalDateTime.of(2023, 7, 1, 0, 0));
        recentTimestamps.add(LocalDateTime.of(2023, 8, 1, 0, 0));
        recentTimestamps.add(LocalDateTime.of(2023, 9, 1, 0, 0));
        recentTimestamps.add(LocalDateTime.of(2023, 10, 1, 0, 0));
        recentTimestamps.add(LocalDateTime.of(2023, 11, 1, 0, 0));
        recentTimestamps.add(LocalDateTime.of(2023, 12, 1, 0, 0));
        recentTimestamps.add(LocalDateTime.of(2024, 1, 1, 0, 0));
        recentTimestamps.add(LocalDateTime.of(2024, 2, 1, 0, 0));
        recentTimestamps.add(LocalDateTime.of(2024, 3, 1, 0, 0));
        recentTimestamps.add(LocalDateTime.of(2024, 4, 1, 0, 0));
        recentTimestamps.add(LocalDateTime.of(2024, 5, 1, 0, 0));
        recentTimestamps.add(LocalDateTime.of(2024, 6, 1, 0, 0));
        recentTimestamps.add(LocalDateTime.of(2024, 7, 1, 0, 0));
        recentTimestamps.add(LocalDateTime.of(2024, 8, 1, 0, 0));
        recentTimestamps.add(LocalDateTime.of(2024, 9, 1, 0, 0));
        recentTimestamps.add(LocalDateTime.of(2024,10 ,1 ,0 ,0 ));
        recentTimestamps.add(LocalDateTime.of(2024 ,11 ,1 ,0 ,0 ));
        recentTimestamps.add(LocalDateTime.of(2024 ,12 ,1 ,0 ,0 ));
        return recentTimestamps;
    }

    private List<Float> getGecentValues() {
        List<Float> recentValues = new ArrayList<>();
        recentValues.add(112f);
        recentValues.add(118f);
        recentValues.add(132f);
        recentValues.add(129f);
        recentValues.add(121f);
        recentValues.add(135f);
        recentValues.add(148f);
        recentValues.add(148f);
        recentValues.add(136f);
        recentValues.add(119f);
        recentValues.add(104f);
        recentValues.add(118f);
        recentValues.add(115f);
        recentValues.add(126f);
        recentValues.add(141f);
        recentValues.add(135f);
        recentValues.add(125f);
        recentValues.add(149f);
        recentValues.add(170f);
        recentValues.add(170f);
        recentValues.add(158f);
        recentValues.add(133f);
        recentValues.add(114f);
        recentValues.add(140f);
        return recentValues;
    }
}
