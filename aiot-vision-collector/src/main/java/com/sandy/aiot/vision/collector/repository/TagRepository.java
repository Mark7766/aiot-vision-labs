package com.sandy.aiot.vision.collector.repository;

import com.sandy.aiot.vision.collector.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TagRepository extends JpaRepository<Tag, Long> {
    List<Tag> findByDeviceId(Long deviceId);
    Optional<Tag> findByDeviceIdAndAddress(Long deviceId, String address);
}
