package com.sandy.aiot.vision.collector;

import com.google.common.collect.ImmutableList;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OPC UA 连接可用性测试 (默认跳过)。
 * 运行方式：
 * mvn -DrunOpcUaTest=true -Dtest=OpcUaConnectionTest test
 * 或在 IDE 的 Run Configuration 中添加 VM 参数: -DrunOpcUaTest=true
 */
@Slf4j
public class OpcUaConnectionTestByMilo {

    private static final String CONNECTION_STRING = "opcua:tcp://WCNPF5497CNL.apa.gad.schneider-electric.com:53530/OPCUA/SimulationServer";
    private static AtomicInteger atomic = new AtomicInteger(1);

    @Test
    public void testSubscription() throws Exception {
        String endpointUrl = "opc.tcp://127.0.0.1:53530/OPCUA/SimulationServer";
        OpcUaClient client = OpcUaClient.create(endpointUrl);
        client.connect().get();
        System.out.println("Connected to OPC UA server at: " + endpointUrl);
        //创建发布间隔1000ms的订阅对象
         client.getSubscriptionManager()
                .createSubscription(1000.0)
                .thenAccept(t -> {
                    //节点
                    NodeId nodeId = NodeId.parse("ns=3;i=1005");
                    List<MonitoredItemCreateRequest> requests = new ArrayList<>();
                    requests.add(getMonitoredItemCreateRequest(nodeId));
                    //创建监控项，并且注册变量值改变时候的回调函数。
                    t.createMonitoredItems(
                            TimestampsToReturn.Both,
                            requests,
                            (item, id) -> item.setValueConsumer((it, val) -> {
                                log.info("nodeid :{}, value :{}", it.getReadValueId().getNodeId(), val.getValue().getValue());
                            })
                    );
                }).get();
        TimeUnit.SECONDS.sleep(10);
        log.info("10秒后增加");
        client.getSubscriptionManager().getSubscriptions().forEach(sub -> {
            try {
                //打印已有监控项
                sub.getMonitoredItems().forEach(item -> log.info("item:{}", item.getReadValueId().getNodeId()));
                //删除监控项
                ImmutableList<UaMonitoredItem> items = sub.getMonitoredItems();
                List<UaMonitoredItem> toDelete = new ArrayList<>();
                for (UaMonitoredItem item : items) {
                    if (item.getReadValueId().getNodeId().equals(NodeId.parse("ns=3;i=1005"))) {
                        toDelete.add(item);
                    }
                }
                if (!toDelete.isEmpty()) {
                    List<StatusCode> uIntegers = sub.deleteMonitoredItems(toDelete).get();
                    log.info("删除监控项:{}", uIntegers);
                }
                //打印删除后的监控项
                sub.getMonitoredItems().forEach(item -> log.info("after delete item:{}", item.getReadValueId().getNodeId()));
                //增加监控项
                List<MonitoredItemCreateRequest> newRequests = new ArrayList<>();
                newRequests.add(getMonitoredItemCreateRequest(NodeId.parse("ns=3;i=1004")));
//                newRequests.add(getMonitoredItemCreateRequest(NodeId.parse("ns=3;i=1005")));
                sub.createMonitoredItems(
                        TimestampsToReturn.Both,
                        newRequests,
                        (item, id) -> item.setValueConsumer((it, val) -> {
                            log.info("new nodeid :{}, value :{}", it.getReadValueId().getNodeId(), val.getValue().getValue());
                        })
                ).get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        //持续订阅
        Thread.sleep(Long.MAX_VALUE);
        client.disconnect().get();
    }

    private static MonitoredItemCreateRequest getMonitoredItemCreateRequest(NodeId nodeId) {
        ReadValueId readValueId = new ReadValueId(nodeId, AttributeId.Value.uid(), null, null);
        //创建监控的参数
        MonitoringParameters parameters = new MonitoringParameters(UInteger.valueOf(atomic.getAndIncrement()), 1000.0, null, UInteger.valueOf(10), true);
        //创建监控项请求
        //该请求最后用于创建订阅。
        MonitoredItemCreateRequest request = new MonitoredItemCreateRequest(readValueId, MonitoringMode.Reporting, parameters);
        return request;
    }

    @Test
    void testDiscoveryTag() throws Exception {
        String endpointUrl = "opc.tcp://127.0.0.1:53530/OPCUA/SimulationServer"; // 修改为您的 OPC UA 服务器地址
        OpcUaClient client = OpcUaClient.create(endpointUrl);
        client.connect().get();
        browseNode("", client, Identifiers.ObjectsFolder);
    }

    @Test
    void testDiscoveryTagByNS() throws Exception {
        String endpointUrl = "opc.tcp://127.0.0.1:53530/OPCUA/SimulationServer"; // 修改为您的 OPC UA 服务器地址
        OpcUaClient client = OpcUaClient.create(endpointUrl);
        client.connect().get();
        try {
            // 指定要查询的 Namespace Index（例如 1）
            int targetNamespaceIndex = 3;
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
            }
            CompletableFuture<List<DataValue>> completableFuture = client.readValues(0, TimestampsToReturn.Source, nodeIds);
            List<DataValue> dataValues = completableFuture.get();
            for (int i = 0; i < nodeIds.size(); i++) {
                NodeId nodeId = nodeIds.get(i);
                DataValue dataValue = dataValues.get(i);
                log.info("NodeId: {},value:{}", nodeId, dataValue.getValue().getValue());
            }

        } finally {
            // 断开连接
            client.disconnect().get();
        }
    }

    @Test
    void testDiscoveryNameSpace() throws Exception {
        String endpointUrl = "opc.tcp://127.0.0.1:53530/OPCUA/SimulationServer"; // 修改为您的 OPC UA 服务器地址
        OpcUaClient client = OpcUaClient.create(endpointUrl);
        client.connect().get();
        try {
            // 读取 NamespaceArray (NodeId: i=2255)
            NodeId namespaceArrayNode = Identifiers.Server_NamespaceArray;
            DataValue value = client.readValue(0, TimestampsToReturn.Source, namespaceArrayNode).get();
            Variant variant = value.getValue();
            // 检查返回值是否为字符串数组
            if (variant.getValue() instanceof String[] namespaces) {
                log.info("Found {} namespaces:", namespaces.length);
                for (int i = 0; i < namespaces.length; i++) {
                    log.info("Namespace [{}] = {}", i, namespaces[i]);
                }
            } else {
                log.error("NamespaceArray is not a String array");
            }
        } finally {
            // 断开连接
            client.disconnect().get();
        }
    }


    private void browseNode(String indent, OpcUaClient client, NodeId browseRoot) {
        try {
            List<? extends UaNode> nodes = client.getAddressSpace().browseNodes(browseRoot);

            for (UaNode node : nodes) {
                if (node.getNodeClass() == NodeClass.Variable && node.getBrowseName().getNamespaceIndex().compareTo(UShort.valueOf(3)) == 0) {
                    log.info("{} Node={},nodeId={},nodeClass={}", indent, node.getBrowseName(), node.getNodeId(), node.getNodeClass());
                }
                // recursively browse to children
                browseNode(indent + "  ", client, node.getNodeId());
            }
        } catch (UaException e) {
            log.error("Browsing nodeId={} failed: {}", browseRoot, e.getMessage(), e);
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
