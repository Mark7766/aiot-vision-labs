package com.sandy.aiot.vision.collector.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimeSeriesDataModelRsp {
    private List<String> timestamps;
    private List<Float> values;
    private List<Float> predictions;
}