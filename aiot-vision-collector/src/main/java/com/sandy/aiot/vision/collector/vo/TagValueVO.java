package com.sandy.aiot.vision.collector.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TagValueVO {
    private String name;
    private String address;
    private Object value;
}
