package com.sandy.aiot.vision.collector.repository;

import com.sandy.aiot.vision.collector.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {
    @Query("select distinct d from Device d left join fetch d.tags")
    List<Device> findAllWithTags();
}
