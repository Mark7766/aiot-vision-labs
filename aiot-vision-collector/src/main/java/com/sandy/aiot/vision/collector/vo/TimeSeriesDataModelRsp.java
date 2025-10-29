package com.sandy.aiot.vision.collector.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimeSeriesDataModelRsp {
    private List<PredictionPointRsp> predictionPoints;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PredictionPointRsp {
        private String timestamp;
        private Double value;
    }

    public static TimeSeriesDataModelRsp empty(){
        return TimeSeriesDataModelRsp.builder().predictionPoints(new ArrayList<>()).build();
    }
}