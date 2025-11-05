package com.sandy.aiot.vision.collector.repository;

import com.sandy.aiot.vision.collector.entity.Alert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AlertRepository extends JpaRepository<Alert, Long> {
    List<Alert> findByAcknowledgedFalseAndIgnoredFalseOrderByCreatedAtDesc();
    Optional<Alert> findTopBySignatureAndAcknowledgedFalseAndIgnoredFalseAndCreatedAtAfter(String signature, LocalDateTime after);
    List<Alert> findTop50ByOrderByCreatedAtDesc();
    List<Alert> findByCreatedAtAfterOrderByCreatedAtAsc(LocalDateTime after);
}
