package com.sandy.aiot.vision.collector.service;

import com.sandy.aiot.vision.collector.entity.DataRecord;

import java.util.List;
import java.util.Optional;

public interface DataStorageService {
    List<DataRecord> findLatest(Long deviceId);
    Optional<DataRecord> findLatest(Long deviceId, Long tagId);
    List<DataRecord> findTopN(Long deviceId, Long tagId, int limit);
    List<DataRecord> findTopN(Long deviceId, int limit);
    boolean save(List<DataRecord> dataRecords);
}
