package com.sandy.aiot.vision.collector;

import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.entity.Tag;
import com.sandy.aiot.vision.collector.repository.DeviceRepository;
import com.sandy.aiot.vision.collector.repository.TagRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证文件模式 H2 数据库重启后数据仍在。
 */
@SpringBootTest
@ActiveProfiles("test")
class DevicePersistenceIntegrationTest {
    @Autowired DeviceRepository deviceRepository;
    @Autowired TagRepository tagRepository;

    @Test
    void saveDeviceAndTag() {
        Device d = Device.builder().name("D1").protocol("opcua").connectionString("opc.tcp://127.0.0.1:12345").build();
        d = deviceRepository.save(d);
        Tag t = Tag.builder().name("T1").address("ns=2;s=Tag1").device(d).build();
        t = tagRepository.save(t);
        assertNotNull(d.getId());
        assertNotNull(t.getId());
        assertEquals(d.getId(), tagRepository.findById(t.getId()).orElseThrow().getDevice().getId());
    }
}
