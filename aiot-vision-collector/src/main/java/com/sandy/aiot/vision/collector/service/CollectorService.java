package com.sandy.aiot.vision.collector.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandy.aiot.vision.collector.entity.DataRecord;
import com.sandy.aiot.vision.collector.vo.NamespaceVO;
import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.entity.Tag;
import com.sandy.aiot.vision.collector.repository.DeviceRepository;
import com.sandy.aiot.vision.collector.repository.TagRepository;
import com.sandy.aiot.vision.collector.vo.TagValueVO;
import lombok.extern.slf4j.Slf4j;
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

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class CollectorService {
    private static final Logger logger = LoggerFactory.getLogger(CollectorService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private TagRepository tagRepository; // 保留，后续可能单点读取用
    @Autowired
    private DataStorageService dataStorageService;
    // Cache for reusing OPC UA sessions to avoid TooManySessions burst
    private final Map<Long, OpcUaClient> opcUaClients = new ConcurrentHashMap<>();
    // Backoff map: deviceId -> epochMilli until which collection is skipped
    private final Map<Long, Long> deviceBackoffUntil = new ConcurrentHashMap<>();

    private static final long TOO_MANY_SESSIONS_BACKOFF_MS = TimeUnit.SECONDS.toMillis(30);

    @PreDestroy
    public void shutdown() {
        opcUaClients.forEach((id, c) -> {
            try {
                c.disconnect().get(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignore) {
            }
        });
        opcUaClients.clear();
    }

    private OpcUaClient getOrCreateClient(Device device) throws Exception {
        return opcUaClients.compute(device.getId(), (id, existing) -> {
            try {
                if (existing != null) {
                    return existing; // assume still valid; Milo reconnects on demand
                }
                OpcUaClient c = OpcUaClient.create(device.getConnectionString());
                c.connect().get();
                return c;
            } catch (Exception e) {
                if (existing != null) {
                    try {
                        existing.disconnect().get();
                    } catch (Exception ignore) {
                    }
                }
                throw new RuntimeException(e);
            }
        });
    }

    private void invalidateClient(Long deviceId) {
        OpcUaClient c = opcUaClients.remove(deviceId);
        if (c != null) {
            try {
                c.disconnect().get();
            } catch (Exception ignore) {
            }
        }
    }

    private boolean isInBackoff(Device device) {
        Long until = deviceBackoffUntil.get(device.getId());
        return until != null && System.currentTimeMillis() < until;
    }

    private void applyBackoff(Device device) {
        long until = System.currentTimeMillis() + TOO_MANY_SESSIONS_BACKOFF_MS;
        deviceBackoffUntil.put(device.getId(), until);
        log.warn("设备 {} 进入会话限流回退，暂停采集 {} ms", device.getName(), TOO_MANY_SESSIONS_BACKOFF_MS);
    }

    @Scheduled(fixedRate = 1000)
    public void collectData() { // removed @Transactional to prevent batch rollback
        doCollect();
    }

    // 手动触发调用
    public void collectDataOnce() { // removed @Transactional
        doCollect();
    }

    public List<NamespaceVO> getNameSpaces(Device device) throws ExecutionException, InterruptedException {
        OpcUaClient client = null;
        try {
            client = OpcUaClient.create(device.getConnectionString());
            client.connect().get();
            // 读取 NamespaceArray (NodeId: i=2255)
            NodeId namespaceArrayNode = Identifiers.Server_NamespaceArray;
            DataValue value = client.readValue(0, TimestampsToReturn.Source, namespaceArrayNode).get();
            Variant variant = value.getValue();
            List<NamespaceVO> namespaceVOS = new ArrayList<>();
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
            if (client != null)
                client.disconnect().get();
        }
    }

    public List<TagValueVO> getTagsByDeviceAndNamespace(Device device, NamespaceVO namespaceVO) throws Exception {
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
                log.info("NodeId: {} ,name: {},value:{}", nodeId, node.getDisplayName().getText(), dataValue.getValue().getValue());
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
        List<Device> devices = deviceRepository.findAllWithTags();
        if (devices.isEmpty()) {
            log.info("尚未配置设备，前往 /devices 添加");
            return;
        }
        for (Device device : devices) {
            if (isInBackoff(device)) {
                log.debug("跳过设备 {} 采集（会话回退中）", device.getName());
                continue;
            }
            List<Tag> tags = device.getTags();
            if (tags == null || tags.isEmpty()) {
                log.warn("设备 {} 未配置点位，跳过采集", device.getName());
                continue;
            }
            String conn = device.getConnectionString();
            boolean isOpcUa = conn != null && conn.startsWith("opc");
            if (isOpcUa) {
                log.info("开始采集 OPC UA 设备: {} , tags count: {}", device.getName(), tags.size());
                doOpcUaCollect(device, tags);
            } else {
                log.warn("不支持的设备类型，跳过采集 Device={} ConnectionString={}", device.getName(), conn);
            }
        }
    }

    private void doOpcUaCollect(Device device, List<Tag> tags)  {
        List<DataRecord> dataRecords = new ArrayList<>();
        OpcUaClient client = null;
        try {
            client = getOrCreateClient(device);
            UaClient uaClient = client;
            List<NodeId> nodeIds = new ArrayList<>();
            List<Tag> validTags = new ArrayList<>();
            for (Tag tag : tags) {
                try {
                    nodeIds.add(NodeId.parse(tag.getAddress()));
                    validTags.add(tag);
                } catch (Exception parseEx) {
                    log.warn("解析地址失败 device={} tag={} addr={} err={}", device.getName(), tag.getName(), tag.getAddress(), parseEx.getMessage());
                }
            }
            if (nodeIds.isEmpty()) {
                //打印日志
                log.warn("设备 {} 无有效点位地址，跳过采集", device.getName());
                return;
            }
            CompletableFuture<List<DataValue>> completableFuture = uaClient.readValues(0, TimestampsToReturn.Source, nodeIds);
            List<DataValue> dataValues = completableFuture.get();
            for (int i = 0; i < nodeIds.size(); i++) {
                NodeId nodeId = nodeIds.get(i);
                DataValue dataValue = dataValues.get(i);
                Tag tag = validTags.get(i);
                Object v = (dataValue == null || dataValue.getValue() == null) ? null : dataValue.getValue().getValue();
                log.info("TagName:{},NodeId: {},value:{}", tag.getName(), nodeId, v);
                if (v != null) {
                    dataRecords.add(DataRecord.builder()
                            .deviceId(device.getId())
                            .tagId(tag.getId())
                            .value(v)
                            .timestamp(LocalDateTime.now())
                            .build());
                }

            }
        } catch (Exception ex) {
            String rawMsg = ex.getMessage();
            String msg = rawMsg;
            if (msg != null && msg.length() > 160) {
                msg = msg.substring(0, 160) + "..."; // 避免过长
            }
            logger.error("采集失败 device={} error={}:{}", device.getName(), ex.getClass().getSimpleName(), msg);
            if (rawMsg != null && rawMsg.contains("Bad_TooManySessions")) {
                applyBackoff(device);
            } else {
                // Other errors -> invalidate to force reconnect next round
                invalidateClient(device.getId());
            }
        }
        if (!dataRecords.isEmpty()) {
            dataStorageService.save(dataRecords);
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
                if (ns == null) {
                    ns = new NamespaceTable();
                    ns.putUri(refNodeId.getNamespaceUri(), refNodeId.getNamespaceIndex());
                }
                NodeId snodeId = refNodeId.toNodeId(ns).orElse(null);
                if (snodeId != null) {
                    // 检查 Namespace Index
                    if (refNodeId.getNamespaceIndex().compareTo(UShort.valueOf(targetNamespaceIndex)) == 0) {
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
