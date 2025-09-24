package com.sandy.aiot.vision.collector;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;

public class MiloEndpointLister {
    public static void main(String[] args) throws Exception {
        String endpointUrl = "opc.tcp://127.0.0.1:53530/OPCUA/SimulationServer";  // 确保使用 IP
        OpcUaClient client = OpcUaClient.create(endpointUrl);
        client.connect().get();  // 匿名连接
        System.out.println("连接成功！");  // 添加日志确认
        // 断开连接
        client.disconnect().get();
    }
}