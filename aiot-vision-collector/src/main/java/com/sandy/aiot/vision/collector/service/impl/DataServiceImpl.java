package com.sandy.aiot.vision.collector.service.impl;

import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.entity.Tag;
import com.sandy.aiot.vision.collector.repository.DeviceRepository;
import com.sandy.aiot.vision.collector.repository.TagRepository;
import com.sandy.aiot.vision.collector.service.DataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DataServiceImpl implements DataService {

    private final DeviceRepository deviceRepository;
    private final TagRepository tagRepository;

    @Override
    public List<Device> getAllDevices() { return deviceRepository.findAll(); }

    @Override
    public Device getDeviceById(Long id) { return deviceRepository.findById(id).orElse(null); }

    @Override
    public List<Tag> getTagsByDeviceId(Long deviceId) { return tagRepository.findByDeviceId(deviceId); }

    @Override
    public Tag getTagById(Long tagId) { return tagRepository.findById(tagId).orElse(null); }

    @Override
    public Device saveDevice(Device device) { return deviceRepository.save(device); }

    @Override
    public void deleteDevice(Long id) { deviceRepository.deleteById(id); }

    @Override
    public Tag saveTag(Tag tag) { return tagRepository.save(tag); }

    @Override
    public void deleteTag(Long id) { tagRepository.deleteById(id); }
}

