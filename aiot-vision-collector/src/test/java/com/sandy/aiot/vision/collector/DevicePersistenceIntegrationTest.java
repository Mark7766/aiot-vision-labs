package com.sandy.aiot.vision.collector;

import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.entity.Tag;
import com.sandy.aiot.vision.collector.repository.DeviceRepository;
import com.sandy.aiot.vision.collector.repository.TagRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.Map;

/**
 * 验证文件模式 H2 数据库重启后数据仍在。
 */
public class DevicePersistenceIntegrationTest {

    @Test
    void testDataPersistAfterContextRestart() {
        // 第一次启动
        SpringApplication app1 = new SpringApplication(AiotVisionCollectorApplication.class);
        app1.setDefaultProperties(Map.of("server.port", "0")); // 随机端口
        ConfigurableApplicationContext ctx1 = app1.run();
        DeviceRepository deviceRepo1 = ctx1.getBean(DeviceRepository.class);
        TagRepository tagRepo1 = ctx1.getBean(TagRepository.class);

        Device d = new Device();
        d.setName("TEST-DEVICE-PERSIST");
        d.setProtocol("modbus-tcp");
        d.setConnectionString("modbus:tcp://127.0.0.1:502?unit-id=1");
        d = deviceRepo1.save(d);

        Tag t = new Tag();
        t.setName("TEST-TAG-PERSIST");
        t.setAddress("holding-register:40001");
        t.setDevice(d);
        tagRepo1.save(t);
        Long deviceId = d.getId();
        ctx1.close();

        // 第二次启动
        SpringApplication app2 = new SpringApplication(AiotVisionCollectorApplication.class);
        app2.setDefaultProperties(Map.of("server.port", "0"));
        ConfigurableApplicationContext ctx2 = app2.run();
        DeviceRepository deviceRepo2 = ctx2.getBean(DeviceRepository.class);
        List<Device> all = deviceRepo2.findAll();
        Assertions.assertTrue(all.stream().anyMatch(dev -> dev.getId().equals(deviceId)), "重启后未找到设备, 持久化失败");
        ctx2.close();
    }

}
