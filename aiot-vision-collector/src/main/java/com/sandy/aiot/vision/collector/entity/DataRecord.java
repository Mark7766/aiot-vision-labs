package com.sandy.aiot.vision.collector.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "data_records")
@Data
public class DataRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long tagId;
    @Lob
    @Column(name = "\"value\"", columnDefinition = "CLOB")
    private String value; // JSON-like string for simplicity, now CLOB for larger payloads
    private LocalDateTime timestamp;
}