package com.sandy.aiot.vision.collector.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "data_records")
@Data
@Builder
public class DataRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long tagId;
    private Long deviceId;
    @Lob
    @Column(name = "\"value\"", columnDefinition = "CLOB")
    private Object value;
    private LocalDateTime timestamp;
}