package com.sandy.aiot.vision.collector.service;

import com.sandy.aiot.vision.collector.vo.TimeSeriesDataModelVO;

public interface PredictService {
    TimeSeriesDataModelVO predict(Long deviceId, Long tagId);
}
