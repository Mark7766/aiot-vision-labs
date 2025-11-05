package com.sandy.aiot.vision.collector.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimeSeriesDataModelVO {
    // 历史时间戳（仅用于服务端内部推断步��，不再输出）
    private List<LocalDateTime> timestamps;
    // 新的预测点集合（包含时间与预测值）
    private List<PredictionPoint> predictionPoints;

    private static final DateTimeFormatter OUT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PredictionPoint {
        private LocalDateTime timestamp;
        private Double value;
    }

    public TimeSeriesDataModelRsp toTimeSeriesDataModelRsp() {
        TimeSeriesDataModelRsp rsp = new TimeSeriesDataModelRsp();
        if (this.predictionPoints != null) {
            rsp.setPredictionPoints(this.predictionPoints.stream()
                    .map(p -> new TimeSeriesDataModelRsp.PredictionPointRsp(
                            p.getTimestamp() == null ? null : OUT_FMT.format(p.getTimestamp()),
                            p.getValue()))
                    .collect(Collectors.toList()));
        } else {
            rsp.setPredictionPoints(new ArrayList<>());
        }
        return rsp;
    }
}