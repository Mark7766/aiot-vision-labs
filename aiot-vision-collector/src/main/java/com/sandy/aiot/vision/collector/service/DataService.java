package com.sandy.aiot.vision.collector.service;

import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.entity.Tag;
import java.util.List;

public interface DataService {
    List<Device> getAllDevices();
    Device getDeviceById(Long id);
    List<Tag> getTagsByDeviceId(Long deviceId);
    Tag getTagById(Long tagId);
    Device saveDevice(Device device);
    void deleteDevice(Long id);
    Tag saveTag(Tag tag);
    void deleteTag(Long id);
}