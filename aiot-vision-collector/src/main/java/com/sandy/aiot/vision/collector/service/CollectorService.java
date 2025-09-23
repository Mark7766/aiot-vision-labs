package com.sandy.aiot.vision.collector.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandy.aiot.vision.collector.entity.DataRecord;
import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.entity.Tag;
import com.sandy.aiot.vision.collector.repository.DataRecordRepository;
import com.sandy.aiot.vision.collector.repository.DeviceRepository;
import com.sandy.aiot.vision.collector.repository.TagRepository;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.PlcDriverManager;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfig;
import org.eclipse.milo.opcua.stack.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
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

    @Value("${collector.simulation.enabled:true}")
    private boolean simulationEnabled;

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
            boolean usedSimulation = false;
            if (simulationEnabled || device.getConnectionString() == null || device.getConnectionString().startsWith("sim://")) {
                Map<String, Object> values = new LinkedHashMap<>();
                for (Tag tag : tags) {
                    values.put(tag.getName(), Math.round(Math.random() * 1000) / 10.0);
                }
                values.put("mode", "simulation");
                persistDeviceSnapshot(device.getId(), device.getName(), values);
                usedSimulation = true;
            }
            if (usedSimulation) continue; // 跳过真实连接

            String conn = device.getConnectionString();
            boolean isOpcUa = conn != null && conn.startsWith("opcua:tcp://");
            Map<String,Object> values = null;
            Exception plc4xError = null;
            if (isOpcUa) {
                try (PlcConnection connection = driverManager.getConnectionManager().getConnection(conn)) {
                    PlcReadRequest.Builder requestBuilder = connection.readRequestBuilder();
                    for (Tag tag : tags) {
                        requestBuilder.addTagAddress(tag.getName(), tag.getAddress());
                    }
                    PlcReadRequest request = requestBuilder.build();
                    PlcReadResponse response = request.execute().get();
                    values = new LinkedHashMap<>();
                    for (String tagName : response.getTagNames()) {
                        PlcResponseCode code = response.getResponseCode(tagName);
                        values.put(tagName, code == PlcResponseCode.OK ? response.getObject(tagName) : ("ERROR:" + code));
                    }
                    values.put("mode","opcua-plc4x");
                } catch (Exception ex) {
                    plc4xError = ex;
                    logger.warn("OPC UA PLC4X 读取失败, 准备 Milo 回退 device={} err={}", device.getName(), ex.getMessage());
                }
                if (values == null) {
                    // Milo fallback
                    try {
                        values = readWithMilo(conn, tags);
                        if (values != null) {
                            values.put("mode","opcua-milo-fallback");
                            if (plc4xError != null) {
                                values.put("plc4xError", plc4xError.getClass().getSimpleName()+":"+plc4xError.getMessage());
                            }
                        }
                    } catch (Exception miloEx) {
                        logger.error("OPC UA Milo 回退仍失败 device={} err={}", device.getName(), miloEx.getMessage());
                        persistDeviceSnapshot(device.getId(), device.getName(), Map.of(
                                "error", miloEx.getClass().getSimpleName()+":"+miloEx.getMessage(),
                                "plc4xError", plc4xError!=null?plc4xError.getMessage():"none",
                                "mode", "opcua-double-fail"
                        ));
                        continue;
                    }
                }
            } else {
                // 非 OPC UA, 使用现有逻辑
                try (PlcConnection connection = driverManager.getConnectionManager().getConnection(conn)) {
                    PlcReadRequest.Builder requestBuilder = connection.readRequestBuilder();
                    for (Tag tag : tags) requestBuilder.addTagAddress(tag.getName(), tag.getAddress());
                    PlcReadRequest request = requestBuilder.build();
                    PlcReadResponse response = request.execute().get();
                    values = new LinkedHashMap<>();
                    for (String tagName : response.getTagNames()) {
                        PlcResponseCode code = response.getResponseCode(tagName);
                        values.put(tagName, code == PlcResponseCode.OK ? response.getObject(tagName) : ("ERROR:" + code));
                    }
                    values.put("mode","plc4x");
                } catch (Exception e) {
                    logger.error("采集失败 device={} error={}", device.getName(), e.getMessage());
                    persistDeviceSnapshot(device.getId(), device.getName(), Map.of(
                            "error", e.getClass().getSimpleName() + ":" + e.getMessage(),
                            "tip", "检查连接字符串或设备网络",
                            "time", java.time.LocalDateTime.now().toString(),
                            "mode", simulationEnabled ? "simulation-fallback" : "real"
                    ));
                    continue;
                }
            }
            if (values != null) {
                persistDeviceSnapshot(device.getId(), device.getName(), values);
            }
        }
    }

    // Milo 读取 (匿名 & None 策略) - 只在 PLC4X 失败后调用
    private Map<String,Object> readWithMilo(String plc4xUrl, List<Tag> tags) throws Exception {
        // 提取 host:port
        String base = plc4xUrl; // opcua:tcp://host:port?...
        int schemeIdx = base.indexOf("opcua:tcp://");
        if (schemeIdx < 0) throw new IllegalArgumentException("不是有效的 OPC UA URL: " + plc4xUrl);
        int hostStart = schemeIdx + "opcua:tcp://".length();
        int q = base.indexOf('?', hostStart);
        String hostPort = q>0? base.substring(hostStart,q): base.substring(hostStart);
        if (hostPort.endsWith("/")) hostPort = hostPort.substring(0, hostPort.length()-1);
        String host; int port;
        int colon = hostPort.indexOf(':');
        if (colon>0) { host = hostPort.substring(0, colon); port = Integer.parseInt(hostPort.substring(colon+1)); }
        else throw new IllegalArgumentException("URL 缺少端口: " + plc4xUrl);

        String discovery = "opc.tcp://" + host + ":" + port;
        List<EndpointDescription> eps = DiscoveryClient.getEndpoints(discovery).get(5, TimeUnit.SECONDS);
        if (eps.isEmpty()) throw new IllegalStateException("Milo 未发现端点");
        EndpointDescription target = null;
        for (EndpointDescription ep : eps) {
            if (ep.getSecurityPolicyUri().endsWith("#None") && ep.getSecurityMode() == MessageSecurityMode.None) { target = ep; break; }
        }
        if (target == null) target = eps.get(0);
        // 若端点 host 与我们 host 不同, 克隆替换
        String endpointUrl = target.getEndpointUrl();
        try {
            java.net.URI epUri = java.net.URI.create(endpointUrl);
            if (!host.equalsIgnoreCase(epUri.getHost())) {
                String replaced = "opc.tcp://" + host + ":" + (epUri.getPort()>0? epUri.getPort(): port);
                target = new EndpointDescription(
                        replaced,
                        target.getServer(),
                        target.getServerCertificate(),
                        target.getSecurityMode(),
                        target.getSecurityPolicyUri(),
                        target.getUserIdentityTokens(),
                        target.getTransportProfileUri(),
                        target.getSecurityLevel()
                );
            }
        } catch (Exception ignore) {}

        OpcUaClientConfig config = OpcUaClientConfig.builder()
                .setApplicationName(LocalizedText.english("AIOT Vision Collector"))
                .setApplicationUri("urn:aiot:vision:collector")
                .setEndpoint(target)
                .setRequestTimeout(UInteger.valueOf(10_000))
                .build();
        OpcUaClient client = OpcUaClient.create(config);
        client.connect().get(10, TimeUnit.SECONDS);
        Map<String,Object> result = new LinkedHashMap<>();
        try {
            for (Tag tag : tags) {
                NodeId nodeId = parseNodeId(tag.getAddress());
                if (nodeId == null) {
                    result.put(tag.getName(), "INVALID_ADDRESS");
                    continue;
                }
                DataValue dv = client.readValue(0, TimestampsToReturn.Neither, nodeId).get(5, TimeUnit.SECONDS);
                if (dv.getStatusCode() != null && dv.getStatusCode().isBad()) {
                    result.put(tag.getName(), "BAD:" + dv.getStatusCode());
                } else {
                    result.put(tag.getName(), dv.getValue()!=null? dv.getValue().getValue(): null);
                }
            }
        } finally {
            try { client.disconnect().get(5, TimeUnit.SECONDS); } catch (Exception ignore) {}
        }
        return result;
    }

    private NodeId parseNodeId(String addr) {
        if (addr == null) return null;
        String s = addr.trim();
        try {
            if (s.startsWith("ns=")) { // e.g. ns=2;s=Tag1  or ns=3;i=1002
                // split by ';'
                String[] parts = s.split(";");
                if (parts.length < 2) return null;
                int ns = Integer.parseInt(parts[0].substring(3));
                String idPart = parts[1];
                if (idPart.startsWith("s=")) {
                    return new NodeId(ns, idPart.substring(2));
                } else if (idPart.startsWith("i=")) {
                    return new NodeId(ns, Integer.parseInt(idPart.substring(2)));
                } else if (idPart.startsWith("g=")) { // GUID
                    return new NodeId(ns, java.util.UUID.fromString(idPart.substring(2)));
                } else if (idPart.startsWith("b=")) { // ByteString base64
                    byte[] raw = java.util.Base64.getDecoder().decode(idPart.substring(2));
                    return new NodeId(ns, ByteString.of(raw));
                }
            } else if (s.startsWith("i=")) { // namespace 0 implicit
                return new NodeId(0, Integer.parseInt(s.substring(2)));
            } else if (s.equalsIgnoreCase("ServerStatus.CurrentTime")) {
                return Identifiers.Server_ServerStatus_CurrentTime;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
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
}

