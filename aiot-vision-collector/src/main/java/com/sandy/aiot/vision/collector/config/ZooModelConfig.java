package com.sandy.aiot.vision.collector.config;

import ai.djl.ModelException;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.timeseries.Forecast;
import ai.djl.timeseries.TimeSeriesData;
import ai.djl.timeseries.translator.DeepARTranslator;
import ai.djl.training.util.ProgressBar;
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
public class ZooModelConfig {
    @Bean
    public ZooModel<TimeSeriesData, Forecast> deepARModel() throws IOException, ModelException, URISyntaxException {
        Map<String, Object> arguments = new ConcurrentHashMap<>();
        arguments.put("prediction_length", 12);
        arguments.put("freq", "5s");
        arguments.put("context_length", 12);
        DeepARTranslator translator = DeepARTranslator.builder(arguments).build();
        URL resource = Thread.currentThread().getContextClassLoader().getResource("deepar.zip");
        Path modelPath = Paths.get(resource.toURI());
        Criteria<TimeSeriesData, Forecast> criteria =
                Criteria.builder()
                        .setTypes(TimeSeriesData.class, Forecast.class)
                        .optModelPath(modelPath)
                        .optTranslator(translator)
                        .optProgress(new ProgressBar())
                        .build();
        return criteria.loadModel();
    }
}
