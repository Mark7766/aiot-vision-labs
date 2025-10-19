package com.sandy.aiot.vision.collector;

import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.entity.Tag;
import com.sandy.aiot.vision.collector.service.DataService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class DataServiceTest {
    @Autowired DataService dataService;

    @Test
    void deviceAndTagCrud() {
        Device d = new Device();
        d.setName("TestDevice");
        d.setProtocol("opcua");
        d.setConnectionString("opc.tcp://localhost:11111");
        d = dataService.saveDevice(d);
        assertNotNull(d.getId());

        Tag tag = Tag.builder().name("TagA").address("ns=2;s=TagA").device(d).build();
        tag = dataService.saveTag(tag);
        assertNotNull(tag.getId());

        List<Tag> tags = dataService.getTagsByDeviceId(d.getId());
        assertEquals(1, tags.size());

        dataService.deleteTag(tag.getId());
        assertTrue(dataService.getTagsByDeviceId(d.getId()).isEmpty());

        dataService.deleteDevice(d.getId());
        assertNull(dataService.getDeviceById(d.getId()));
    }
}

