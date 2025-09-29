package com.sandy.aiot.vision.collector;

import lombok.extern.slf4j.Slf4j;
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
import org.junit.jupiter.api.Test;

import java.util.List;

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


