package com.sandy.aiot.vision.collector;

import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.PlcDriverManager;
import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcSubscriptionResponse;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.nodes.UaNode;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * OPC UA 连接可用性测试 (默认跳过)。
 * 运行方式：
 * mvn -DrunOpcUaTest=true -Dtest=OpcUaConnectionTest test
 * 或在 IDE 的 Run Configuration 中添加 VM 参数: -DrunOpcUaTest=true
 */
@Slf4j
public class OpcUaConnectionTest {

    private static final String CONNECTION_STRING = "opcua:tcp://WCNPF5497CNL.apa.gad.schneider-electric.com:53530/OPCUA/SimulationServer";

   @Test
    void testPullTagValue() throws Exception {
        long start = System.currentTimeMillis();
        try (PlcConnection connection = PlcDriverManager.getDefault().getConnectionManager().getConnection(CONNECTION_STRING)) { // 更新 API 调用
            Assertions.assertTrue(connection.isConnected(), "OPC UA 连接未建立");
            log.info("Pull [OPC UA] 成功连接 -> " + CONNECTION_STRING + ", 耗时 {} ms", System.currentTimeMillis() - start);
            PlcReadRequest.Builder requestBuilder = connection.readRequestBuilder();
            requestBuilder.addTagAddress("QP0801_PSI-L.Value", "ns=3;i=1009");
            requestBuilder.addTagAddress("QP0801_PSI-R.Value", "ns=3;i=1008");
            PlcReadRequest request = requestBuilder.build();
            PlcReadResponse response = request.execute().get();
            for (String tagName : response.getTagNames()) {
                PlcResponseCode code = response.getResponseCode(tagName);
                log.info("Tag: {}, Response Code: {}, Value: {}", tagName, code, response.getObject(tagName));
            }
        }
    }
    @Test
    void testSubValue() throws Exception {
        long start = System.currentTimeMillis();
        try (PlcConnection connection = PlcDriverManager.getDefault().getConnectionManager().getConnection(CONNECTION_STRING)) { // 更新 API 调用
            Assertions.assertTrue(connection.isConnected(), "OPC UA 连接未建立");
            log.info("Sub [OPC UA] 成功连接 -> " + CONNECTION_STRING + ", 耗时 {} ms", System.currentTimeMillis() - start);
            CountDownLatch latch = new CountDownLatch(5); // 接收 5 次事件后结束
            // 订阅方式读取
            PlcSubscriptionResponse plcSubscriptionResponse = connection.subscriptionRequestBuilder()
                    .addChangeOfStateTagAddress("QP0801_PSI-L.Value", "ns=3;i=1009")
                    .addChangeOfStateTagAddress("QP0801_PSI-R.Value", "ns=3;i=1008")
                    .build()
                    .execute()
                    .get(5, TimeUnit.SECONDS);
            plcSubscriptionResponse.getSubscriptionHandles().forEach(consumer -> {
                consumer.register((plcSubscriptionEvent) -> {
                    log.info("Subscription update for {}", plcSubscriptionEvent.getTagNames());
                    // 打印所有标签的值
                    for (String tagName : plcSubscriptionEvent.getTagNames()) {
                        log.info("Tag: {}, Value: {},Time:{}", tagName, plcSubscriptionEvent.getObject(tagName), plcSubscriptionEvent.getTimestamp());
                    }
                    latch.countDown();
                });
            });
            boolean completed = latch.await(Duration.ofSeconds(120).toMillis(), TimeUnit.MILLISECONDS);
            log.info("订阅结束, 是否达到预期事件数: {}", completed);
        }
    }
    @Test
    void testDiscoveryTag() throws Exception {
        String endpointUrl = "opc.tcp://127.0.0.1:53530/OPCUA/SimulationServer"; // 修改为您的 OPC UA 服务器地址
        OpcUaClient client = OpcUaClient.create(endpointUrl);
        client.connect().get();
        browseNode("", client, Identifiers.ObjectsFolder);
    }
    @Test
    void testDiscoveryNameSpace() throws Exception {
        String endpointUrl = "opc.tcp://127.0.0.1:53530/OPCUA/SimulationServer"; // 修改为您的 OPC UA 服务器地址
        OpcUaClient client = OpcUaClient.create(endpointUrl);
        client.connect().get();
        try {
            // 读取 NamespaceArray (NodeId: i=2255)
            NodeId namespaceArrayNode = Identifiers.Server_NamespaceArray;
            DataValue value = client.readValue(0, TimestampsToReturn.Source,namespaceArrayNode).get();
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
                if(node.getNodeClass()== NodeClass.Variable && node.getBrowseName().getNamespaceIndex().compareTo(UShort.valueOf(3))==0){
                    log.info("{} Node={},nodeId={},nodeClass={}", indent, node.getBrowseName(),node.getNodeId(),node.getNodeClass());
                }
                // recursively browse to children
                browseNode(indent + "  ", client, node.getNodeId());
            }
        } catch (UaException e) {
            log.error("Browsing nodeId={} failed: {}", browseRoot, e.getMessage(), e);
        }
    }
}


