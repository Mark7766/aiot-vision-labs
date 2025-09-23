package com.sandy.aiot.vision.collector.repository;

import com.sandy.aiot.vision.collector.entity.DataRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DataRecordRepository extends JpaRepository<DataRecord, Long> {
    // 按时间倒序获取最新记录
    List<DataRecord> findTop100ByOrderByTimestampDesc();
}
