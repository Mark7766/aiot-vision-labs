package com.sandy.aiot.vision.collector.repository;

import com.sandy.aiot.vision.collector.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TagRepository extends JpaRepository<Tag, Long> {
    List<Tag> findByDeviceId(Long deviceId);
}
