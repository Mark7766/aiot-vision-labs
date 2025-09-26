package com.sandy.aiot.vision.collector.config;

import ai.djl.ModelException;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.timeseries.Forecast;
import ai.djl.timeseries.TimeSeriesData;
import ai.djl.timeseries.translator.DeepARTranslator;
import ai.djl.training.util.ProgressBar;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@Slf4j
public class ZooModelConfig {
    @Bean
    public ZooModel<TimeSeriesData, Forecast> deepARModel() throws IOException, ModelException, URISyntaxException {
        int prediction_length=60;
        String freq="1W";
        int context_length=120;
        Map<String, Object> arguments = new ConcurrentHashMap<>();
        arguments.put("prediction_length", prediction_length);
        arguments.put("freq", freq);
        arguments.put("context_length", context_length);
        arguments.put("num_features", 1);
        DeepARTranslator translator = DeepARTranslator.builder(arguments).build();
        URL resource = Thread.currentThread().getContextClassLoader().getResource("deepar.zip");
        Path modelPath = Paths.get(resource.toURI());
        Criteria<TimeSeriesData, Forecast> criteria =
                Criteria.builder()
                        .setTypes(TimeSeriesData.class, Forecast.class)
                        .optModelUrls("djl://ai.djl.mxnet/deepar")
                        .optEngine("MXNet")
                        .optTranslator(translator)
                        .optProgress(new ProgressBar())
                        .optArgument("prediction_length", prediction_length)
                        .optArgument("freq", freq)
                        .optArgument("context_length", context_length)
                        .optArgument("num_features", 1)
                        .optArgument("use_feat_dynamic_real", "false")
                        .optArgument("use_feat_static_real", "false")
                        .optArgument("num_feat_static_cat", 5)  // 关键：指定数量 5
                        .optArgument("cardinalities", "[3,10,4,7,30490]")  // 关键：M5 类别基数
                        .optArgument("embedding_dimension", 1)  // 每个 cat 嵌入 1 维
                        .build();
        ZooModel<TimeSeriesData, Forecast> model = criteria.loadModel();
        log.info("{}",model.getProperties());
        return model;
    }
}
