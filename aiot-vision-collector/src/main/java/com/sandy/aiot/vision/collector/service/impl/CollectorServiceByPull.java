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
import org.eclipse.milo.opcua.sdk.client.nodes.UaNode;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseDirection;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class CollectorServiceByPull implements CollectorService {

    private final DeviceRepository deviceRepository;
    private final TagRepository tagRepository;
    private final DataStorageService dataStorageService;

    private final Map<Long, OpcUaClient> opcUaClients = new ConcurrentHashMap<>();
    private final Map<Long, Long> deviceBackoffUntil = new ConcurrentHashMap<>();
    private static final long TOO_MANY_SESSIONS_BACKOFF_MS = TimeUnit.SECONDS.toMillis(30);

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
                throw new RuntimeException(e);
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
        deviceBackoffUntil.put(device.getId(), System.currentTimeMillis() + TOO_MANY_SESSIONS_BACKOFF_MS);
        log.warn("设备 {} 进入会话限流回退, 暂停采集 {} ms", device.getName(), TOO_MANY_SESSIONS_BACKOFF_MS);
    }

//    @Scheduled(fixedRate = 1000)
    public void collectData() { doCollect(); }

    private void doCollect() {
        List<Device> devices = deviceRepository.findAllWithTags();
        if (devices.isEmpty()) { return; }
        for (Device device : devices) {
            if (isInBackoff(device)) { continue; }
            List<Tag> tags = device.getTags();
            if (tags == null || tags.isEmpty()) { continue; }
            if (isOpcUa(device.getConnectionString())) { doOpcUaCollect(device, tags); }
        }
    }

    private boolean isOpcUa(String conn) { return conn != null && conn.startsWith("opc"); }
    @Override
    public boolean isConnectionOk(Device device) {
        try {
            OpcUaClient client = getOrCreateClient(device);
            List<DataValue> dataValues =client.readValues(0, TimestampsToReturn.Source, List.of(Identifiers.Server_ServerStatus)).get();
            return true;
        } catch (Exception e) {
            log.error("连接测试失败 device={} error={}:{}", device.getName(), e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }
    private void doOpcUaCollect(Device device, List<Tag> tags) {
        List<DataRecord> dataRecords = new ArrayList<>();
        try {
            OpcUaClient client = getOrCreateClient(device);
            UaClient uaClient = client;
            List<NodeId> nodeIds = new ArrayList<>();
            List<Tag> validTags = new ArrayList<>();
            for (Tag tag : tags) {
                try { nodeIds.add(NodeId.parse(tag.getAddress())); validTags.add(tag); }
                catch (Exception ex) { log.warn("解析地址失败 device={} tag={} addr={} err={}", device.getName(), tag.getName(), tag.getAddress(), ex.getMessage()); }
            }
            if (nodeIds.isEmpty()) { return; }
            List<DataValue> dataValues = uaClient.readValues(0, TimestampsToReturn.Source, nodeIds).get();
            for (int i=0;i<nodeIds.size();i++) {
                DataValue dv = dataValues.get(i);
                Object v = (dv == null || dv.getValue() == null) ? null : dv.getValue().getValue();
                if (v != null) {
                    Tag tag = validTags.get(i);
                    dataRecords.add(DataRecord.builder().deviceId(device.getId()).tagId(tag.getId()).value(v).timestamp(LocalDateTime.now()).build());
                }
            }
        } catch (Exception ex) {
            String raw = ex.getMessage();
            if (raw != null && raw.contains("Bad_TooManySessions")) { applyBackoff(device); } else { invalidateClient(device.getId()); }
            log.error("采集失败 device={} err={}:{}", device.getName(), ex.getClass().getSimpleName(), raw);
        }
        if (!dataRecords.isEmpty()) { dataStorageService.save(dataRecords); }
    }

    @Override
    public List<NamespaceVO> getNameSpaces(Device device) throws ExecutionException, InterruptedException {
        OpcUaClient client = null;
        try {
            client = OpcUaClient.create(device.getConnectionString());
            client.connect().get();
            DataValue value = client.readValue(0, TimestampsToReturn.Source, Identifiers.Server_NamespaceArray).get();
            Variant variant = value.getValue();
            List<NamespaceVO> list = new ArrayList<>();
            if (variant.getValue() instanceof String[] namespaces) {
                for (int i=0;i<namespaces.length;i++) list.add(NamespaceVO.builder().index(i).uri(namespaces[i]).build());
            } else { log.error("NamespaceArray 不是字符串数组"); }
            return list;
        } catch (UaException e) { throw new RuntimeException(e); }
        finally { if (client != null) client.disconnect().get(); }
    }

    @Override
    public List<TagValueVO> getTagsByDeviceAndNamespace(Device device, NamespaceVO namespaceVO) throws Exception {
        OpcUaClient client = OpcUaClient.create(device.getConnectionString()); client.connect().get();
        List<TagValueVO> tagValueVOS = new ArrayList<>();
        try {
            int idx = namespaceVO.getIndex();
            List<NodeId> nodeIds = browseNamespace(client, Identifiers.ObjectsFolder, idx);
            for (NodeId nodeId : nodeIds) {
                UaNode node = client.getAddressSpace().getNode(nodeId);
                DataValue dv = client.readValue(0, TimestampsToReturn.Source, nodeId).get();
                tagValueVOS.add(TagValueVO.builder().name(node.getDisplayName().getText()).address(nodeId.toParseableString()).value(dv.getValue().getValue()).build());
            }
            return tagValueVOS;
        } finally { client.disconnect().get(); }
    }

    private static List<NodeId> browseNamespace(OpcUaClient client, NodeId nodeId, int targetNamespaceIndex) throws Exception {
        List<NodeId> nodesInNamespace = new ArrayList<>();
        BrowseDescription browse = new BrowseDescription(nodeId, BrowseDirection.Forward, Identifiers.References, true, UInteger.valueOf(NodeClass.Object.getValue() | NodeClass.Variable.getValue() | NodeClass.Method.getValue()), UInteger.valueOf(0xFF));
        BrowseResult result = client.browse(browse).get();
        ReferenceDescription[] refs = result.getReferences();
        NamespaceTable ns = null;
        if (refs != null) {
            for (ReferenceDescription ref : refs) {
                ExpandedNodeId refNodeId = ref.getNodeId();
                if (ns == null) { ns = new NamespaceTable(); ns.putUri(refNodeId.getNamespaceUri(), refNodeId.getNamespaceIndex()); }
                NodeId real = refNodeId.toNodeId(ns).orElse(null);
                if (real != null) {
                    if (refNodeId.getNamespaceIndex().compareTo(UShort.valueOf(targetNamespaceIndex)) == 0) nodesInNamespace.add(real);
                    nodesInNamespace.addAll(browseNamespace(client, real, targetNamespaceIndex));
                }
            }
        }
        return nodesInNamespace;
    }
}

