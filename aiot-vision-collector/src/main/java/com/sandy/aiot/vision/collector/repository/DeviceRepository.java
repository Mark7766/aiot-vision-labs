package com.sandy.aiot.vision.collector.repository;

import com.sandy.aiot.vision.collector.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {
}
