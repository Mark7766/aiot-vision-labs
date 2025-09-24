package com.sandy.aiot.vision.collector.repository;

import com.sandy.aiot.vision.collector.entity.DataRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DataRecordRepository extends JpaRepository<DataRecord, Long> {
    // 按时间倒序获取最新记录
    List<DataRecord> findTop100ByOrderByTimestampDesc();

    // 每个“deviceId”存在 tagId 字段里，获取指定设备最新一条
    Optional<DataRecord> findTop1ByTagIdOrderByTimestampDesc(Long tagId);

    // 获取指定设备最近 N 条（不提供 limit 参数由方法名定义）
    List<DataRecord> findTop200ByTagIdOrderByTimestampDesc(Long tagId);
}
