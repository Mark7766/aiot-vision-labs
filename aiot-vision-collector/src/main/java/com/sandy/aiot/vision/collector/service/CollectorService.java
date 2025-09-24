package com.sandy.aiot.vision.collector.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandy.aiot.vision.collector.vo.NamespaceVO;
import com.sandy.aiot.vision.collector.entity.DataRecord;
import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.entity.Tag;
import com.sandy.aiot.vision.collector.repository.DataRecordRepository;
import com.sandy.aiot.vision.collector.repository.DeviceRepository;
import com.sandy.aiot.vision.collector.repository.TagRepository;
import com.sandy.aiot.vision.collector.vo.TagValueVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.api.PlcDriverManager;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.UaClient;
import org.eclipse.milo.opcua.sdk.client.nodes.UaNode;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.ExpandedNodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseDirection;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResult;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

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

    public List<NamespaceVO> getNameSpaces(Device device) throws ExecutionException, InterruptedException {
        OpcUaClient client =null;
        try {
            client = OpcUaClient.create(device.getConnectionString());
            client.connect().get();
            // 读取 NamespaceArray (NodeId: i=2255)
            NodeId namespaceArrayNode = Identifiers.Server_NamespaceArray;
            DataValue value = client.readValue(0, TimestampsToReturn.Source,namespaceArrayNode).get();
            Variant variant = value.getValue();
            List<NamespaceVO> namespaceVOS= new ArrayList<>();
            // 检查返回值是否为字符串数组
            if (variant.getValue() instanceof String[] namespaces) {
                log.info("Found {} namespaces:", namespaces.length);
                for (int i = 0; i < namespaces.length; i++) {
                    log.info("Namespace [{}] = {}", i, namespaces[i]);
                    namespaceVOS.add(NamespaceVO.builder()
                            .index(i)
                            .uri(namespaces[i])
                            .build());
                }
            } else {
                log.error("NamespaceArray is not a String array");
            }
            return namespaceVOS;
        } catch (UaException e) {
            throw new RuntimeException(e);
        } finally {
            // 断开连接
            client.disconnect().get();
        }
    }
    public List<TagValueVO> getTagsByDeviceAndNamespaceVO(Device device, NamespaceVO namespaceVO) throws Exception {
        OpcUaClient client = OpcUaClient.create(device.getConnectionString());
        client.connect().get();
        List<TagValueVO> tagValueVOS = new ArrayList<>();
        try {
            // 指定要查询的 Namespace Index（例如 1）
            int targetNamespaceIndex = namespaceVO.getIndex();
            // 从 ObjectsFolder 开始浏览
            NodeId startNode = Identifiers.ObjectsFolder;
            List<NodeId> nodeIds = browseNamespace(client, startNode, targetNamespaceIndex);
            // 输出结果
            log.info("Found {} nodes in Namespace {}:", nodeIds.size(), targetNamespaceIndex);
            for (NodeId nodeId : nodeIds) {
                UaNode node = client.getAddressSpace().getNode(nodeId);
                CompletableFuture<DataValue> completableFuture = client.readValue(0, TimestampsToReturn.Source, nodeId);
                DataValue dataValue = completableFuture.get();
                log.info("NodeId: {} ,name: {},value:{}", nodeId,node.getDisplayName().getText(),dataValue.getValue().getValue());
                tagValueVOS.add(TagValueVO.builder()
                                .name(node.getDisplayName().getText())
                                .address(nodeId.toParseableString())
                                .value(dataValue.getValue().getValue())
                        .build());
            }
            return tagValueVOS;
        } finally {
            // 断开连接
            client.disconnect().get();
        }
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
            try  {
                OpcUaClient client = OpcUaClient.create(conn);
                UaClient uaClient = client.connect().get();
                List<NodeId> nodeIds=new ArrayList<>();
                for (Tag tag : tags) {
                    nodeIds.add(NodeId.parse(tag.getAddress()));
                }
                CompletableFuture<List<DataValue>> completableFuture = uaClient.readValues(0, TimestampsToReturn.Source, nodeIds);
                List<DataValue> dataValues = completableFuture.get();
                values = new LinkedHashMap<>();
                for (int i = 0; i < nodeIds.size(); i++) {
                    NodeId nodeId = nodeIds.get(i);
                    DataValue dataValue = dataValues.get(i);
                    Tag tag = tags.get(i);
                    log.info("TagName:{},NodeId: {},value:{}", tag.getName(),nodeId,dataValue.getValue().getValue());
                    values.put(tag.getName(),dataValue.getValue().getValue());
                }
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
    /**
     * 递归浏览指定 Namespace 的节点
     */
    private static List<NodeId> browseNamespace(OpcUaClient client, NodeId nodeId, int targetNamespaceIndex) throws Exception {
        List<NodeId> nodesInNamespace = new ArrayList<>();

        // 配置 Browse 请求
        BrowseDescription browse = new BrowseDescription(
                nodeId,
                BrowseDirection.Forward,
                Identifiers.References, // 所有引用类型
                true,
                UInteger.valueOf(NodeClass.Object.getValue() | NodeClass.Variable.getValue() | NodeClass.Method.getValue()),
                UInteger.valueOf(0xFF) // 所有引用
        );

        // 执行 Browse
        BrowseResult result = client.browse(browse).get();
        ReferenceDescription[] references = result.getReferences();
        NamespaceTable ns = null;
        if (references != null) {
            for (ReferenceDescription ref : references) {
                ExpandedNodeId refNodeId = ref.getNodeId();
                if(ns==null){
                    ns = new NamespaceTable();
                    ns.putUri(refNodeId.getNamespaceUri(),refNodeId.getNamespaceIndex());
                }
                NodeId snodeId = refNodeId.toNodeId(ns).orElse(null);
                if (snodeId != null) {
                    // 检查 Namespace Index
                    if (refNodeId.getNamespaceIndex().compareTo(UShort.valueOf(targetNamespaceIndex))==0) {
                        nodesInNamespace.add(snodeId);
                    }
                    // 递归浏览子节点
                    nodesInNamespace.addAll(browseNamespace(client, snodeId, targetNamespaceIndex));
                }
            }
        }

        // 处理 ContinuationPoint（如果节点过多）
        if (result.getContinuationPoint() != null) {
            // 实现 ContinuationPoint 处理（略，需循环调用 browseNext）
            log.trace("ContinuationPoint detected, implement browseNext for complete results");
        }

        return nodesInNamespace;
    }
}

