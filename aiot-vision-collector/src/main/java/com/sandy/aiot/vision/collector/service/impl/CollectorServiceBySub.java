package com.sandy.aiot.vision.collector.service.impl;

import com.sandy.aiot.vision.collector.entity.DataRecord;
import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.entity.Tag;
import com.sandy.aiot.vision.collector.repository.DeviceRepository;
import com.sandy.aiot.vision.collector.repository.TagRepository;
import com.sandy.aiot.vision.collector.service.CollectorService;
import com.sandy.aiot.vision.collector.service.DataStorageService;
import com.sandy.aiot.vision.collector.vo.NamespaceVO;
import com.sandy.aiot.vision.collector.vo.TagValueVO;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.UaClient;
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaMonitoredItem;
import org.eclipse.milo.opcua.sdk.client.nodes.UaNode;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseDirection;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MonitoringMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.*;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Primary
@Slf4j
@RequiredArgsConstructor
public class CollectorServiceBySub implements CollectorService {

    private static final AtomicInteger MONITORED_ITEM_CLIENT_HANDLE = new AtomicInteger(1);
    private static final long TOO_MANY_SESSIONS_BACKOFF_MS = TimeUnit.SECONDS.toMillis(30);

    private final DeviceRepository deviceRepository;
    private final TagRepository tagRepository;
    private final DataStorageService dataStorageService;

    private final Map<Long, OpcUaClient> opcUaClients = new ConcurrentHashMap<>();
    private final Map<Long, Long> deviceBackoffUntil = new ConcurrentHashMap<>();
    private static final Map<NodeId, Tag> NODE_TAG_MAP = new ConcurrentHashMap<>();

    @PreDestroy
    public void shutdown() {
        opcUaClients.forEach((id, c) -> { try { c.disconnect().get(3, TimeUnit.SECONDS); } catch (Exception ignore) { } });
        opcUaClients.clear();
    }

    private OpcUaClient getOrCreateClient(Device device) throws Exception {
        return opcUaClients.compute(device.getId(), (id, existing) -> {
            try {
                if (existing != null) { return existing; }
                OpcUaClient c = OpcUaClient.create(device.getConnectionString());
                c.connect().get();
                return c;
            } catch (Exception e) {
                if (existing != null) { try { existing.disconnect().get(); } catch (Exception ignore) { } }
                throw new CompletionException(e);
            }
        });
    }

    private void invalidateClient(Long deviceId) {
        OpcUaClient c = opcUaClients.remove(deviceId);
        if (c != null) { try { c.disconnect().get(); } catch (Exception ignore) { } }
    }

    private boolean isInBackoff(Device device) {
        Long until = deviceBackoffUntil.get(device.getId());
        return until != null && System.currentTimeMillis() < until;
    }

    private void applyBackoff(Device device) {
        long until = System.currentTimeMillis() + TOO_MANY_SESSIONS_BACKOFF_MS;
        deviceBackoffUntil.put(device.getId(), until);
        log.warn("设备 {} 进入会话限流回退, 暂停采集 {} ms", device.getName(), TOO_MANY_SESSIONS_BACKOFF_MS);
    }

    @Scheduled(fixedRate = 1000)
    public void collectData() { doSub(); }

    private void doSub() {
        List<Device> devices = deviceRepository.findAllWithTags();
        if (devices.isEmpty()) { return; }
        for (Device device : devices) {
            if (isInBackoff(device)) { continue; }
            List<Tag> tags = device.getTags();
            if (tags == null || tags.isEmpty()) { continue; }
            if (isOpcUa(device.getConnectionString())) { doSubOpcUaCollect(device, tags); }
        }
    }

    private boolean isOpcUa(String conn) { return conn != null && conn.startsWith("opc"); }

    private void doSubOpcUaCollect(Device device, List<Tag> tags) {
        try {
            OpcUaClient client = getOrCreateClient(device);
            List<NodeId> newNodeIds = new ArrayList<>();
            for (Tag tag : tags) {
                try {
                    NodeId nodeId = NodeId.parse(tag.getAddress());
                    if (!NODE_TAG_MAP.containsKey(nodeId)) { newNodeIds.add(nodeId); NODE_TAG_MAP.put(nodeId, tag); }
                } catch (Exception parseEx) {
                    log.warn("解析地址失败 device={} tag={} addr={} err={}", device.getName(), tag.getName(), tag.getAddress(), parseEx.getMessage());
                }
            }
            if (newNodeIds.isEmpty()) { return; }
            client.getSubscriptionManager().createSubscription(1000.0).thenAccept(sub -> {
                List<MonitoredItemCreateRequest> requests = new ArrayList<>();
                for (NodeId nodeId : newNodeIds) requests.add(buildMonitoredItemRequest(nodeId));
                sub.createMonitoredItems(TimestampsToReturn.Both, requests, (item, id) -> item.setValueConsumer((it, val) -> handleValueUpdate(it, val)));
            }).get();
            log.info("设备 {} 新增订阅节点数: {}", device.getName(), newNodeIds.size());
        } catch (Exception ex) { processCollectionException(device, ex); }
    }

    private void handleValueUpdate(UaMonitoredItem item, DataValue val) {
        try {
            Tag tag = NODE_TAG_MAP.get(item.getReadValueId().getNodeId());
            if (tag == null || tag.getDevice() == null) return;
            Object value = val.getValue() == null ? null : val.getValue().getValue();
            DataRecord dataRecord = DataRecord.builder().deviceId(tag.getDevice().getId()).tagId(tag.getId()).value(value).timestamp(LocalDateTime.now()).build();
            log.debug("dataRecord:{}",dataRecord.toString());
            dataStorageService.save(List.of(dataRecord));
        } catch (Exception e) { log.warn("处理订阅回调失败: {}", e.getMessage()); }
    }

    private static MonitoredItemCreateRequest buildMonitoredItemRequest(NodeId nodeId) {
        ReadValueId readValueId = new ReadValueId(nodeId, AttributeId.Value.uid(), null, null);
        MonitoringParameters parameters = new MonitoringParameters(UInteger.valueOf(MONITORED_ITEM_CLIENT_HANDLE.getAndIncrement()), 1000.0, null, UInteger.valueOf(10), true);
        return new MonitoredItemCreateRequest(readValueId, MonitoringMode.Reporting, parameters);
    }

    private void processCollectionException(Device device, Exception ex) {
        String rawMsg = ex.getMessage();
        if (rawMsg != null && rawMsg.contains("Bad_TooManySessions")) { applyBackoff(device); } else { invalidateClient(device.getId()); }
        log.error("采集失败 device={} error={}:{}", device.getName(), ex.getClass().getSimpleName(), rawMsg);
    }
    @Override
    public List<NamespaceVO> getNameSpaces(Device device) throws ExecutionException, InterruptedException {
        OpcUaClient client = null;
        try {
            client = OpcUaClient.create(device.getConnectionString());
            client.connect().get();
            DataValue value = client.readValue(0, TimestampsToReturn.Source, Identifiers.Server_NamespaceArray).get();
            Variant variant = value.getValue();
            List<NamespaceVO> namespaceVOS = new ArrayList<>();
            if (variant.getValue() instanceof String[] namespaces) {
                for (int i = 0; i < namespaces.length; i++) namespaceVOS.add(NamespaceVO.builder().index(i).uri(namespaces[i]).build());
            } else { log.error("NamespaceArray 返回类型非字符串数组"); }
            return namespaceVOS;
        } catch (UaException e) { throw new RuntimeException(e); }
        finally { if (client != null) client.disconnect().get(); }
    }

    @Override
    public List<TagValueVO> getTagsByDeviceAndNamespace(Device device, NamespaceVO namespaceVO) throws Exception {
        OpcUaClient client = OpcUaClient.create(device.getConnectionString()); client.connect().get();
        List<TagValueVO> tagValueVOS = new ArrayList<>();
        try {
            int targetNamespaceIndex = namespaceVO.getIndex();
            List<NodeId> nodeIds = browseNamespace(client, Identifiers.ObjectsFolder, targetNamespaceIndex);
            for (NodeId nodeId : nodeIds) {
                UaNode node = client.getAddressSpace().getNode(nodeId);
                DataValue dataValue = client.readValue(0, TimestampsToReturn.Source, nodeId).get();
                tagValueVOS.add(TagValueVO.builder().name(node.getDisplayName().getText()).address(nodeId.toParseableString()).value(dataValue.getValue().getValue()).build());
            }
            return tagValueVOS;
        } finally { client.disconnect().get(); }
    }

    private static List<NodeId> browseNamespace(OpcUaClient client, NodeId nodeId, int targetNamespaceIndex) throws Exception {
        List<NodeId> nodesInNamespace = new ArrayList<>();
        BrowseDescription browse = new BrowseDescription(nodeId, BrowseDirection.Forward, Identifiers.References, true, UInteger.valueOf(NodeClass.Object.getValue() | NodeClass.Variable.getValue() | NodeClass.Method.getValue()), UInteger.valueOf(0xFF));
        BrowseResult result = client.browse(browse).get();
        ReferenceDescription[] references = result.getReferences();
        NamespaceTable ns = null;
        if (references != null) {
            for (ReferenceDescription ref : references) {
                ExpandedNodeId refNodeId = ref.getNodeId();
                if (ns == null) { ns = new NamespaceTable(); ns.putUri(refNodeId.getNamespaceUri(), refNodeId.getNamespaceIndex()); }
                NodeId snodeId = refNodeId.toNodeId(ns).orElse(null);
                if (snodeId != null) {
                    if (refNodeId.getNamespaceIndex().compareTo(UShort.valueOf(targetNamespaceIndex)) == 0) nodesInNamespace.add(snodeId);
                    nodesInNamespace.addAll(browseNamespace(client, snodeId, targetNamespaceIndex));
                }
            }
        }
        return nodesInNamespace;
    }
}

