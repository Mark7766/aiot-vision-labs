package com.sandy.aiot.vision.collector.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandy.aiot.vision.collector.entity.DataRecord;
import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.entity.Tag;
import com.sandy.aiot.vision.collector.repository.DataRecordRepository;
import com.sandy.aiot.vision.collector.repository.DeviceRepository;
import com.sandy.aiot.vision.collector.repository.TagRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.PlcDriverManager;
import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
public class CollectorService {
    private static final Logger logger = LoggerFactory.getLogger(CollectorService.class);
    private final PlcDriverManager driverManager = PlcDriverManager.getDefault();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private TagRepository tagRepository; // 保留，后续可能单点读取用
    @Autowired
    private DataRecordRepository dataRecordRepository;
    // 避免在没有设备时每次都插入占位记录
    private volatile boolean emittedNoDeviceRecord = false;

    @Scheduled(fixedRate = 5000)
    @Transactional
    public void collectData() {
        doCollect();
    }

    // 手动触发调用
    @Transactional
    public void collectDataOnce() {
        doCollect();
    }

    private void doCollect() {
        List<Device> devices = deviceRepository.findAll();
        if (devices.isEmpty()) {
            if (!emittedNoDeviceRecord && dataRecordRepository.count() == 0) {
                createInfoRecord("no-devices", Map.of(
                        "info", "尚未配置设备，前往 /devices 添加",
                        "timestamp", LocalDateTime.now().toString()
                ));
                emittedNoDeviceRecord = true;
            }
            return;
        }
        for (Device device : devices) {
            List<Tag> tags = device.getTags();
            if (tags == null || tags.isEmpty()) {
                persistDeviceSnapshot(device.getId(), device.getName(), Map.of(
                        "warning", "设备未配置点位"
                ));
                continue;
            }
            String conn = device.getConnectionString();
            boolean isOpcUa = conn != null && conn.startsWith("opcua:tcp://");
            Map<String,Object> values = null;
            try (PlcConnection connection = driverManager.getConnectionManager().getConnection(conn)) {
                PlcReadRequest.Builder requestBuilder = connection.readRequestBuilder();
                for (Tag tag : tags) {
                    requestBuilder.addTagAddress(tag.getName(), tag.getAddress());
                }
                PlcReadRequest request = requestBuilder.build();
                PlcReadResponse response = request.execute().get();
                values = new LinkedHashMap<>();
                for (String tagName : response.getTagNames()) {
                    PlcResponseCode code = response.getResponseCode(tagName);
                    log.info("Tag: {}, Response Code: {}, Value: {}", tagName, code, response.getObject(tagName));
                    values.put(tagName, code == PlcResponseCode.OK ? response.getObject(tagName) : ("ERROR:" + code));
                }
                values.put("mode", isOpcUa ? "opcua-plc4x" : "plc4x");
            } catch (Exception ex) {
                logger.error("采集失败 device={} error={}", device.getName(), ex.getMessage());
                persistDeviceSnapshot(device.getId(), device.getName(), Map.of(
                        "error", ex.getClass().getSimpleName() + ":" + ex.getMessage(),
                        "tip", "检查连接字符串或设备网络",
                        "mode", isOpcUa ? "opcua-plc4x-fail" : "plc4x-fail",
                        "time", LocalDateTime.now().toString()
                ));
                continue;
            }
            if (values != null) {
                persistDeviceSnapshot(device.getId(), device.getName(), values);
            }
        }
    }

    private void persistDeviceSnapshot(Long deviceId, String deviceName, Map<String, Object> values) {
        try {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("deviceId", deviceId);
            wrapper.put("device", deviceName);
            wrapper.put("data", values);
            wrapper.put("ts", LocalDateTime.now().toString());
            String jsonValue = objectMapper.writeValueAsString(wrapper);
            DataRecord record = new DataRecord();
            record.setTagId(deviceId); // 复用字段：存放设备ID
            record.setValue(jsonValue);
            record.setTimestamp(LocalDateTime.now());
            dataRecordRepository.save(record);
        } catch (Exception ex) {
            logger.error("序列化数据失败 deviceId={} err={}", deviceId, ex.getMessage());
        }
    }

    private void createInfoRecord(String key, Map<String, Object> info) {
        try {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("type", key);
            wrapper.putAll(info);
            String jsonValue = objectMapper.writeValueAsString(wrapper);
            DataRecord record = new DataRecord();
            record.setTagId(0L);
            record.setValue(jsonValue);
            record.setTimestamp(LocalDateTime.now());
            dataRecordRepository.save(record);
        } catch (Exception e) {
            logger.error("创建信息记录失败: {}", e.getMessage());
        }
    }
}

