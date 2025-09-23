package com.sandy.aiot.vision.collector.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.util.List;

@Entity
@Table(name = "devices")
@Data
public class Device {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String protocol; // "modbus-tcp" or "opcua"
    private String connectionString; // e.g., "modbus:tcp://ip:502?unit-id=1"
    @OneToMany(mappedBy = "device", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Tag> tags;
}