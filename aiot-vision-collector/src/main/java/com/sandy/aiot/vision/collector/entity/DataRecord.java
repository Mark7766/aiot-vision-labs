package com.sandy.aiot.vision.collector.entity;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class DataRecord {
    private Long tagId;
    private Long deviceId;
    private Object value;
    private LocalDateTime timestamp;

}