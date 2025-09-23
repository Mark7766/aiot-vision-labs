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
    @Column(name = "\"value\"")
    private String value; // JSON-like string for simplicity
    private LocalDateTime timestamp;
}