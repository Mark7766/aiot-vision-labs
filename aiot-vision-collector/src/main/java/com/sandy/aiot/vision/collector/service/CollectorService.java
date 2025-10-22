package com.sandy.aiot.vision.collector.service;

import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.vo.NamespaceVO;
import com.sandy.aiot.vision.collector.vo.TagValueVO;

import java.util.List;
import java.util.concurrent.ExecutionException;

public interface CollectorService {
    List<NamespaceVO> getNameSpaces(Device device) throws Exception;
    List<TagValueVO> getTagsByDeviceAndNamespace(Device device, NamespaceVO namespaceVO) throws Exception;

    boolean isConnectionOk(Device device);
}

