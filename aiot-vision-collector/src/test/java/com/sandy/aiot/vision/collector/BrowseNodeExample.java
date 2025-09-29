package com.sandy.aiot.vision.collector;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.nodes.UaNode;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class BrowseNodeExample implements ClientExample {

    public static void main(String[] args) throws Exception {
        String endpointUrl = "opc.tcp://WCNPF5497CNL.apa.gad.schneider-electric.com:53530/OPCUA/SimulationServer"; // 修改为您的 OPC UA 服务器地址
        OpcUaClient client = OpcUaClient.create(endpointUrl);
        client.connect().get();
        BrowseNodeExample example = new BrowseNodeExample();
        example.run(client,null);
    }

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    public void run(OpcUaClient client, CompletableFuture<OpcUaClient> future) throws Exception {
        client.connect();

        // start browsing at root folder
        browseNode("", client, Identifiers.ObjectsFolder);

    }

    private void browseNode(String indent, OpcUaClient client, NodeId browseRoot) {
        try {
            List<? extends UaNode> nodes = client.getAddressSpace().browseNodes(browseRoot);

            for (UaNode node : nodes) {
                if(node.getNodeClass()== NodeClass.Variable && node.getBrowseName().getNamespaceIndex().compareTo(UShort.valueOf(3))==0){
                    logger.info("{} Node={},nodeId={},nodeClass={}", indent, node.getBrowseName(),node.getNodeId(),node.getNodeClass());
                }
                // recursively browse to children
                browseNode(indent + "  ", client, node.getNodeId());
            }
        } catch (UaException e) {
            logger.error("Browsing nodeId={} failed: {}", browseRoot, e.getMessage(), e);
        }
    }
}