package com.sandy.aiot.vision.collector.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimeSeriesDataModelVO {
    private List<LocalDateTime> timestamps;
    private List<Float> values;
    private List<Float> predictions;

    public TimeSeriesDataModelRsp toTimeSeriesDataModelRsp() {
        TimeSeriesDataModelRsp rsp = new TimeSeriesDataModelRsp();
        if (this.timestamps != null) {
            List<String> ts = this.timestamps.stream()
                .map(LocalDateTime::toString)
                .toList();
            rsp.setTimestamps(ts);
        }
        rsp.setValues(this.values);
        rsp.setPredictions(this.predictions);
        return rsp;
    }
}